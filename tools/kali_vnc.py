#!/usr/bin/env python3
"""A user-initiated, authenticated Kali VNC viewer bridge for an active host-approved session.

This script listens on localhost only. It never starts screen capture on Android,
and a new host approval is required after the five-minute request window expires.
"""
import asyncio
import argparse
import json
import os
import shutil
import subprocess
import sys
from urllib.parse import urlencode
from pathlib import Path
from kali_remote import (load_config, login, fetch_devices, choose_device,
                         saved_server_url, verify_approval, LAST_FILE)

try:
    from websockets.asyncio.client import connect as websocket_connect
    HEADER_ARG = "additional_headers"
except ImportError:
    try:
        from websockets.client import connect as websocket_connect
        HEADER_ARG = "extra_headers"
    except ImportError:
        sys.exit("Missing websockets library: python3 -m pip install websockets")

async def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--port', type=int, default=5901)
    parser.add_argument('--no-viewer', action='store_true')
    args = parser.parse_args()
    if not (1024 <= args.port <= 65535): parser.error('Choose port 1024..65535')
    server_url = saved_server_url()
    project, api_key = load_config()
    account = login(api_key)
    uid, token = account['localId'], account['idToken']
    latest = json.loads(LAST_FILE.read_text())
    if latest['uid'] != uid:
        sys.exit('Last request belongs to another account. Run kali_remote.py request again.')
    did, rid = latest['deviceId'], latest['requestId']
    devices = dict(fetch_devices(project, uid, token))
    device = devices.get(did)
    if not device or device.get('serverUrl') != server_url:
        sys.exit('Device not found / Cloudflare server mismatch')
    status = device.get('remoteRequest', {})
    valid, reason = verify_approval(uid, did, rid, status, device)
    if not valid or status.get('status') != 'approved':
        sys.exit('Host approval is not valid: ' + reason)
    print('Host approval verified. Start VNC on Android using its visible START button.')
    url = (server_url.replace('https://', 'wss://', 1) + '/vnc?' + urlencode({
        'role':'controller', 'deviceId': did, 'requestId': rid}))
    client_connected = False

    async def accept(reader, writer):
        nonlocal client_connected
        if client_connected:
            writer.close(); await writer.wait_closed(); return
        client_connected = True
        try:
            async with websocket_connect(url, **{HEADER_ARG: {'Authorization': 'Bearer ' + token}},
                                         max_size=2**20, ping_interval=20) as ws:
                print('Cloudflare bridge connected; waiting for Android host...')
                async def from_vnc():
                    while True:
                        b = await reader.read(16384)
                        if not b: return
                        await ws.send(b)
                async def to_vnc():
                    async for msg in ws:
                        if isinstance(msg, bytes):
                            writer.write(msg)
                            await writer.drain()
                        elif '"end"' in msg: return
                tasks = [asyncio.create_task(from_vnc()), asyncio.create_task(to_vnc())]
                done, pending = await asyncio.wait(tasks, return_when=asyncio.FIRST_COMPLETED)
                for t in pending: t.cancel()
                await asyncio.gather(*tasks, return_exceptions=True)
        except Exception as exc:
            print('Bridge ended:', type(exc).__name__, str(exc)[:160])
        finally:
            client_connected = False
            writer.close()
            await writer.wait_closed()

    local = await asyncio.start_server(accept, '127.0.0.1', args.port)
    print('Kali VNC viewer local address: 127.0.0.1::' + str(args.port))
    print('Android: APPROVE request, START VNC, then START REMOTE SESSION.')
    viewer = shutil.which('xtigervncviewer') or shutil.which('vncviewer')
    if viewer and not args.no_viewer and os.environ.get('DISPLAY'):
        subprocess.Popen([viewer, '127.0.0.1::' + str(args.port)])
    else:
        print('Open a VNC viewer and connect to 127.0.0.1::' + str(args.port))
    async with local: await local.serve_forever()

if __name__ == '__main__':
    try: asyncio.run(main())
    except KeyboardInterrupt: print('\nStopped')
    except Exception as exc: print('ERROR:', str(exc), file=sys.stderr); sys.exit(1)
