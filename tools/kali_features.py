#!/usr/bin/env python3
"""Foreground, host-approved Feature Desk. Not an unattended remote-control agent.

Workflow: tools/kali_remote.py request -> Android CHECK/ALLOW -> START REMOTE FEATURE DESK
-> python3 tools/kali_features.py. Each requested action is shown to the phone owner.
"""
import asyncio
import base64
import json
import mimetypes
import os
from pathlib import Path
import sys
import uuid
from urllib.parse import urlencode

from kali_remote import (load_config, login, fetch_devices, saved_server_url,
                         verify_approval, LAST_FILE)
try:
    from websockets.asyncio.client import connect as websocket_connect
    HEADER_ARG = 'additional_headers'
except ImportError:
    try:
        from websockets.client import connect as websocket_connect
        HEADER_ARG = 'extra_headers'
    except ImportError:
        sys.exit('Install websockets: python3 -m pip install websockets')

CHUNK = 24576
LIMIT = 50*1024*1024
MENU = {
    '1': ('status', 'Device status'),
    '2': ('list', 'List selected phone folder (photos/videos/files)'),
    '3': ('download', 'Download chosen file to Kali'),
    '4': ('upload', 'Upload Kali file to phone shared folder'),
    '5': ('rename', 'Rename phone file'),
    '6': ('delete', 'Delete phone file (owner must confirm)'),
    '7': ('screenshot', 'Capture screenshot and download to Kali'),
    '8': ('backup', 'Create local phone folder backup ZIP'),
    '9': ('backup_download', 'Download latest phone backup ZIP'),
    '10': ('contacts_export', 'Export contacts CSV to phone folder'),
    '11': ('bluetooth', 'Show phone paired Bluetooth names'),
    '12': ('notify', 'Show notification on phone'),
    '13': ('voice_note', 'Record 10-second voice note ON PHONE'),
    '14': ('history', 'View app action history'),
    '15': ('backup_daily_enable', 'Enable LOCAL daily folder backup'),
    '16': ('backup_daily_disable', 'Disable LOCAL daily folder backup'),
}

def name_ok(name):
    return bool(name) and len(name) <= 160 and '/' not in name and '\\' not in name and name not in ('.','..') and all(ord(c) >= 32 for c in name)

async def receive(ws, timeout=120):
    while True:
        raw = await asyncio.wait_for(ws.recv(), timeout)
        if not isinstance(raw, str):
            raise RuntimeError('Expected feature JSON')
        obj = json.loads(raw)
        if obj.get('type') == 'peer':
            if obj.get('state') == 'left': raise RuntimeError('Phone disconnected')
            return obj
        if obj.get('type') == 'end': raise RuntimeError('Session ended on phone')
        return obj

async def wait_for(ws, expected, request_id, timeout=120):
    while True:
        msg = await receive(ws,timeout)
        if msg.get('type') == 'peer' and msg.get('state') == 'joined':
            continue
        if msg.get('type') == 'result' and msg.get('id') == request_id and not msg.get('ok',True):
            raise RuntimeError(msg.get('data',{}).get('error','Phone operation failed'))
        if msg.get('type') == 'error' and msg.get('id') == request_id:
            raise RuntimeError(msg.get('message','Phone operation failed'))
        if msg.get('id') == request_id and msg.get('type') == expected:
            return msg
        raise RuntimeError('Unexpected message in this transfer: ' + str(msg.get('type')))

async def download(ws, request_id):
    first=await wait_for(ws,'file_start',request_id,240)
    name=first['name']
    size=first['size']
    if not name_ok(name) or not isinstance(size,int) or size < 0 or size > LIMIT:
        raise RuntimeError('Invalid or oversized file metadata')
    target_dir=Path.home()/'remote-feature-downloads'
    target_dir.mkdir(parents=True, exist_ok=True)
    target=target_dir/name
    if target.exists(): target=target_dir/(str(uuid.uuid4())[:8]+'-'+name)
    partial=target.with_name(target.name+'.part')
    total=0; expected_seq=0
    try:
        with partial.open('wb') as out:
            await ws.send(json.dumps({'type':'file_ready','id':request_id}))
            while True:
                frame=await receive(ws,240)
                if frame.get('type') == 'peer' and frame.get('state') == 'joined': continue
                if frame.get('id') != request_id: raise RuntimeError('Invalid transfer ID')
                if frame.get('type') == 'error': raise RuntimeError(frame.get('message','Transfer failed'))
                if frame.get('type') == 'file_end':
                    if total != size: raise RuntimeError(f'Truncated file: {total}/{size}')
                    break
                if frame.get('type') != 'file_chunk' or frame.get('seq') != expected_seq:
                    raise RuntimeError('Invalid file chunk sequence')
                buf=base64.b64decode(frame['data'],validate=True)
                if len(buf) > CHUNK or total+len(buf)>size: raise RuntimeError('Bad chunk size')
                out.write(buf)
                total+=len(buf)
                await ws.send(json.dumps({'type':'file_ack','id':request_id,'seq':expected_seq}))
                expected_seq+=1
        partial.replace(target)
        await ws.send(json.dumps({'type':'result','id':request_id,'ok':True,'data':{'saved':name}}))
        print(f'\nSaved on Kali: {target} ({total} bytes)')
    except Exception as exc:
        partial.unlink(missing_ok=True)
        try: await ws.send(json.dumps({'type':'error','id':request_id,'message':str(exc)[:160]}))
        except Exception: pass
        raise

async def upload(ws, request_id, path):
    size=path.stat().st_size
    await ws.send(json.dumps({'type':'file_start','id':request_id,'name':path.name,
                              'size':size,'mime':mimetypes.guess_type(path.name)[0] or 'application/octet-stream'}))
    await wait_for(ws,'file_ready',request_id,120)
    with path.open('rb') as src:
        seq=0
        while chunk := src.read(CHUNK):
            await ws.send(json.dumps({'type':'file_chunk','id':request_id,'seq':seq,
                                      'data':base64.b64encode(chunk).decode('ascii')}))
            await wait_for(ws,'file_ack',request_id,120)
            seq+=1
    await ws.send(json.dumps({'type':'file_end','id':request_id}))
    return await wait_for(ws,'result',request_id,120)

async def operation(ws, op, args, upload_path=None):
    rid=str(uuid.uuid4())
    await ws.send(json.dumps({'type':'command','id':rid,'op':op,'args':args}))
    print('Waiting for phone owner to ALLOW this action...')
    decision=await wait_for(ws,'decision',rid,120)
    if not decision['allow']:
        print('Denied on phone.');return
    if op == 'upload':
        result=await upload(ws,rid,upload_path)
    elif op in ('download','screenshot','backup_download'):
        await download(ws,rid)
        return
    else:
        result=await wait_for(ws,'result',rid,180)
    if result.get('ok'):
        print(json.dumps(result.get('data',{}),indent=2,ensure_ascii=False))
    else:
        print('Phone reported error:',result.get('data',{}).get('error','unknown'))

async def main():
    server=saved_server_url()
    project,api_key=load_config()
    account=login(api_key)
    uid,token=account['localId'],account['idToken']
    last=json.loads(LAST_FILE.read_text())
    if uid != last.get('uid'): raise RuntimeError('Account mismatch: run kali_remote.py request again')
    did,rid=last['deviceId'],last['requestId']
    device=dict(fetch_devices(project,uid,token)).get(did)
    if not device or device.get('serverUrl') != server: raise RuntimeError('Device URL mismatch')
    request=device.get('remoteRequest',{})
    valid,reason=verify_approval(uid,did,rid,request,device)
    if not valid or request.get('status') != 'approved': raise RuntimeError('Ask for fresh phone approval: '+reason)
    url=server.replace('https://','wss://',1)+'/features?'+urlencode(
        {'role':'controller','deviceId':did,'requestId':rid})
    print('Approved session. Open START REMOTE FEATURE DESK on the phone.')
    async with websocket_connect(url,**{HEADER_ARG:{'Authorization':'Bearer '+token}},
                                 max_size=45000,ping_interval=20) as ws:
        while True:
            hello=await receive(ws,60)
            if hello.get('type')=='peer' and hello.get('state')=='joined':break
            raise RuntimeError('Unexpected message waiting for Android')
        print('Phone connected. Each action requires phone-owner confirmation.')
        while True:
            print('\n--- Kali <-> Android Feature Desk ---')
            for key,(op,title) in MENU.items(): print(f'{key:>2} {title}')
            print(' 0 Disconnect / Exit')
            choice=await asyncio.to_thread(input,'Choose: ')
            if choice.strip()=='0':
                await ws.send(json.dumps({'type':'end'}));break
            if choice.strip() not in MENU:continue
            op=MENU[choice.strip()][0];args={}; upload_path=None
            if op in ('download','delete','rename'):
                name=(await asyncio.to_thread(input,'Exact file name from list: ')).strip()
                if not name_ok(name): print('Invalid name');continue
                args['name']=name
                if op=='rename':
                    new_name=(await asyncio.to_thread(input,'New file name: ')).strip()
                    if not name_ok(new_name):print('Invalid name');continue
                    args['newName']=new_name
            elif op=='upload':
                path=(await asyncio.to_thread(input,'Kali file path: ')).strip()
                upload_path=Path(path).expanduser()
                if not upload_path.is_file() or not name_ok(upload_path.name) or upload_path.stat().st_size > LIMIT:
                    print('File missing, unsafe name, or larger than 50 MiB');continue
                args={'name':upload_path.name,'size':upload_path.stat().st_size,
                      'mime':mimetypes.guess_type(upload_path.name)[0] or 'application/octet-stream'}
            try: await operation(ws,op,args,upload_path)
            except Exception as exc:
                print('Action failed:',exc)
                print('If transfer interrupted, disconnect and request a new session.')
                break

if __name__=='__main__':
    try: asyncio.run(main())
    except KeyboardInterrupt: print('\nDisconnected')
    except Exception as exc:
        print('ERROR:',exc,file=sys.stderr);sys.exit(1)
