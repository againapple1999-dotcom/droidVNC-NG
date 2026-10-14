#!/usr/bin/env python3
"""Offline check of approval-signature payload format used by Kali and Android."""
import base64
import importlib.util
import tempfile
import builtins
from datetime import datetime, timedelta, timezone
from pathlib import Path
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives import hashes, serialization

spec = importlib.util.spec_from_file_location('remote', Path(__file__).with_name('kali_remote.py'))
remote = importlib.util.module_from_spec(spec)
spec.loader.exec_module(remote)

with tempfile.TemporaryDirectory() as tmp:
    remote.PIN_FILE = Path(tmp) / 'pinned.json'
    key = ec.generate_private_key(ec.SECP256R1())
    device_key = base64.b64encode(key.public_key().public_bytes(
        serialization.Encoding.DER,
        serialization.PublicFormat.SubjectPublicKeyInfo)).decode()
    user_id, device_id, request_id = 'test-user', 'test-device', 'test-request'
    expiration = (datetime.now(timezone.utc) + timedelta(minutes=3)).replace(microsecond=0)
    expires_ms = int(expiration.timestamp()) * 1000
    payload = (f'remote-assistance-approval-v1\n{user_id}\n{device_id}\n'
               f'{request_id}\napproved\n{expires_ms}').encode()
    signed = base64.b64encode(key.sign(payload, ec.ECDSA(hashes.SHA256()))).decode()
    request = {
        'id': request_id, 'status': 'approved', 'approvalDeviceId': device_id,
        'expiresAt': expiration.isoformat().replace('+00:00', 'Z'),
        'approvalSignature': signed
    }
    orig = builtins.input
    builtins.input = lambda _: 'y'
    try:
        valid, message = remote.verify_approval(user_id, device_id, request_id,
                                                 request, {'approvalPublicKey':device_key})
    finally:
        builtins.input = orig
    assert valid, message
    request['status'] = 'denied'  # tampering with Firestore cannot forge host consent
    valid, message = remote.verify_approval(user_id, device_id, request_id,
                                             request, {'approvalPublicKey':device_key})
    assert not valid, 'Tampered Firestore status should fail signature verification'
print('PASS: genuine signature verifies; tampered status rejected')
