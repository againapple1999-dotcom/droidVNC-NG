"""Offline protocol tests for Kali side of the consent-based Feature Desk."""
import asyncio
import base64
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0,str(Path(__file__).resolve().parent.parent/'tools'))
import kali_features as features

class FakeSocket:
    def __init__(self, messages):
        self.messages=list(messages)
        self.sent=[]
    async def recv(self):
        if not self.messages: raise AssertionError('No more messages')
        return json.dumps(self.messages.pop(0))
    async def send(self,raw):
        self.sent.append(json.loads(raw))

class FeatureTests(unittest.IsolatedAsyncioTestCase):
    async def test_phone_peer_connection_and_denied(self):
        ws=FakeSocket([{'type':'peer','state':'joined'}, {'type':'decision','id':'a','allow':False}])
        self.assertEqual((await features.receive(ws))['type'],'peer')
        self.assertFalse((await features.wait_for(ws,'decision','a'))['allow'])

    async def test_download_full_file_only_then_rename(self):
        rid='aaaaaaaa-aaaa-4aaa-aaaa-aaaaaaaaaaaa'
        ws=FakeSocket([{'type':'file_start','id':rid,'name':'hello.txt','size':11,'mime':'text/plain'},
                       {'type':'file_chunk','id':rid,'seq':0,'data':base64.b64encode(b'hello world').decode()},
                       {'type':'file_end','id':rid}])
        with tempfile.TemporaryDirectory() as tmp, patch('pathlib.Path.home',return_value=Path(tmp)):
            await features.download(ws,rid)
            self.assertEqual((Path(tmp)/'remote-feature-downloads'/'hello.txt').read_bytes(),b'hello world')
            self.assertEqual([x['type'] for x in ws.sent],['file_ready','file_ack','result'])

    async def test_download_rejects_invalid_sequence_and_no_partial_remains(self):
        rid='aaaaaaaa-aaaa-4aaa-aaaa-aaaaaaaaaaaa'
        ws=FakeSocket([{'type':'file_start','id':rid,'name':'bad.txt','size':5,'mime':'text/plain'},
                       {'type':'file_chunk','id':rid,'seq':8,'data':base64.b64encode(b'abcde').decode()}])
        with tempfile.TemporaryDirectory() as tmp, patch('pathlib.Path.home',return_value=Path(tmp)):
            with self.assertRaises(RuntimeError): await features.download(ws,rid)
            self.assertFalse((Path(tmp)/'remote-feature-downloads'/'bad.txt.part').exists())

    async def test_upload_receives_ready_and_ordered_ack(self):
        rid='aaaaaaaa-aaaa-4aaa-aaaa-aaaaaaaaaaaa'
        with tempfile.TemporaryDirectory() as tmp:
            path=Path(tmp)/'test.bin';path.write_bytes(b'abcde')
            ws=FakeSocket([{'type':'file_ready','id':rid},
                           {'type':'file_ack','id':rid,'seq':0},
                           {'type':'result','id':rid,'ok':True,'data':{'uploadedBytes':5}}])
            result=await features.upload(ws,rid,path)
            self.assertTrue(result['ok'])
            self.assertEqual([x['type'] for x in ws.sent],['file_start','file_chunk','file_end'])
            self.assertEqual(base64.b64decode(ws.sent[1]['data']),b'abcde')

    def test_names_reject_path_traversal(self):
        for x in ['../../secret','a\\b','', '..', '/tmp/private']:
            self.assertFalse(features.name_ok(x))
        self.assertTrue(features.name_ok('IMG_1234.jpg'))

if __name__=='__main__':unittest.main()
