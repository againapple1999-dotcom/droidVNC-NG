#!/usr/bin/env python3
"""Consent-bound Firebase signaling for a Kali -> Android support request.

IMPORTANT: This tool does not transmit VNC/video/input/file data and cannot
start an Android remote-control session. The approved signal must not be
mistaken for Android's MediaProjection permission or a secure data tunnel.
"""
import argparse
import base64
import getpass
import json
import os
import sys
import time
import urllib.error
import urllib.request
import uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path
from urllib.parse import urlsplit

API = "https://firestore.googleapis.com/v1/"
PIN_FILE = Path.home() / ".config/remote-assistance/device-pins.json"
LAST_FILE = Path.home() / ".config/remote-assistance/last-request.json"
SERVER_FILE = Path.home() / ".config/remote-assistance/server.json"


def normalize_server_url(raw):
    """Canonical HTTPS origin: login configuration, NOT a control authorization."""
    parsed = urlsplit(raw.strip())
    try:
        port = parsed.port
    except ValueError as exc:
        raise RuntimeError("Invalid port in Cloudflare URL") from exc
    if (parsed.scheme.lower() != "https" or not parsed.hostname
            or "." not in parsed.hostname or parsed.username is not None
            or parsed.password is not None or port is not None
            or parsed.query or parsed.fragment or parsed.path not in ("", "/")):
        raise RuntimeError("Enter only an HTTPS Cloudflare server root URL")
    return "https://" + parsed.hostname.lower()


def saved_server_url():
    try:
        return normalize_server_url(json.loads(SERVER_FILE.read_text())["serverUrl"])
    except (FileNotFoundError, ValueError, KeyError):
        raise RuntimeError("Set Cloudflare URL first: python3 tools/kali_remote.py configure")


def configure_server():
    current = None
    try:
        current = saved_server_url()
    except RuntimeError:
        pass
    prompt = "Cloudflare server URL"
    if current:
        prompt += f" [{current}]"
    supplied = input(prompt + ": ").strip()
    url = normalize_server_url(supplied or current or "")
    dump_state(SERVER_FILE.parent, SERVER_FILE.name, {"serverUrl": url})
    print("Server URL saved:", url)
    print("This config does not establish a live remote-control tunnel.")


def load_config():
    config_file = Path(os.environ.get(
        "FIREBASE_CONFIG", str(Path.home() / "droidVNC-NG/app/google-services.json")))
    try:
        data = json.loads(config_file.read_text())
        return data["project_info"]["project_id"], data["client"][0]["api_key"][0]["current_key"]
    except (OSError, KeyError, IndexError, ValueError) as exc:
        raise RuntimeError(f"Cannot read Android Firebase configuration from {config_file}: {exc}") from exc


def request_json(url, data=None, token=None, method=None):
    headers = {"Accept": "application/json"}
    if data is not None:
        headers["Content-Type"] = "application/json"
    if token is not None:
        headers["Authorization"] = f"Bearer {token}"
    req = urllib.request.Request(url,
                                 data=json.dumps(data).encode() if data is not None else None,
                                 headers=headers,
                                 method=method)
    try:
        with urllib.request.urlopen(req, timeout=25) as response:
            return json.load(response)
    except urllib.error.HTTPError as exc:
        try:
            detail = json.load(exc).get("error", {}).get("message", "unknown error")
        except (OSError, ValueError):
            detail = "unavailable"
        raise RuntimeError(f"Firebase HTTP {exc.code}: {detail}") from exc


def login(api_key):
    email = input("Firebase Email: ").strip()
    password = getpass.getpass("Firebase Password: ")
    try:
        return request_json(
            "https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=" + api_key,
            {"email": email, "password": password, "returnSecureToken": True})
    finally:
        del password


def parse_fields(d):
    obj = {}
    for key, val in d.items():
        if "mapValue" in val:
            obj[key] = parse_fields(val["mapValue"].get("fields", {}))
        elif "stringValue" in val:
            obj[key] = val["stringValue"]
        elif "timestampValue" in val:
            obj[key] = val["timestampValue"]
    return obj


def doc_url(project, uid, device_id=None):
    url = API + f"projects/{project}/databases/(default)/documents/users/{uid}/devices"
    if device_id is not None:
        url += "/" + device_id
    return url


def fetch_devices(project, uid, token):
    data = request_json(doc_url(project, uid), token=token)
    return [(item["name"].rsplit("/", 1)[-1], parse_fields(item.get("fields", {})))
            for item in data.get("documents", [])]


def choose_device(devices):
    if not devices:
        raise RuntimeError("No registered devices. Register from the Android app first.")
    for index, (device_id, item) in enumerate(devices, 1):
        print(f"  {index}. {item.get('name', 'Android device')} [{device_id}]")
    choice = input("Device number: ")
    if not choice.isdigit() or not (1 <= int(choice) <= len(devices)):
        raise RuntimeError("Invalid device number")
    return devices[int(choice) - 1]


def dump_state(folder, filename, content):
    folder.mkdir(mode=0o700, parents=True, exist_ok=True)
    path = folder / filename
    path.write_text(json.dumps(content, indent=2) + "\n")
    path.chmod(0o600)


def pin_key(uid, device_id, public_b64):
    if not public_b64:
        raise RuntimeError("No device approval public key. Update the Android app and Register This Device again.")
    try:
        import hashlib
        raw = base64.b64decode(public_b64, validate=True)
        fingerprint = hashlib.sha256(raw).hexdigest()
    except ValueError as exc:
        raise RuntimeError("Invalid public key in device document") from exc
    try:
        pins = json.loads(PIN_FILE.read_text())
    except FileNotFoundError:
        pins = {}
    key = f"{uid}/{device_id}"
    stored = pins.get(key)
    if stored is not None and stored != fingerprint:
        raise RuntimeError("DEVICE KEY CHANGED! Possible reinstallation/account compromise. Refusing approval verification. Investigate before resetting the pin.")
    if stored is None:
        print("First-time Android device public key SHA-256:", fingerprint)
        print("Verify you registered this device yourself. This is not a connection code.")
        if input("Trust and pin this device key? [y/N]: ").strip().lower() != "y":
            raise RuntimeError("Device key was not trusted")
        pins[key] = fingerprint
        dump_state(PIN_FILE.parent, PIN_FILE.name, pins)
    return raw


def verify_approval(uid, device_id, request_id, request, device):
    # Returns (bool, string). Firestore's `approved` flag by itself is forgeable
    # by another client sharing the UID; require this host-keystore signature.
    try:
        from cryptography.hazmat.primitives import hashes, serialization
        from cryptography.hazmat.primitives.asymmetric import ec
        from cryptography.exceptions import InvalidSignature
    except ImportError as exc:
        raise RuntimeError("Install python3-cryptography on Kali to verify Android approvals") from exc
    if request.get("id") != request_id:
        return False, "Request replaced or ID mismatch"
    state = request.get("status", "missing")
    if state not in ("approved", "denied"):
        return False, f"Request state: {state}"
    if request.get("approvalDeviceId") != device_id:
        return False, "Approval not bound to the selected device"
    expiry_text = request.get("expiresAt", "")
    try:
        expires_ms = int(datetime.fromisoformat(expiry_text.replace("Z", "+00:00")).timestamp() * 1000)
    except (ValueError, TypeError):
        return False, "Invalid request expiry"
    if time.time() * 1000 >= expires_ms:
        return False, "Approval expired"
    key_bytes = pin_key(uid, device_id, device.get("approvalPublicKey"))
    key = serialization.load_der_public_key(key_bytes)
    if not isinstance(key, ec.EllipticCurvePublicKey):
        return False, "Invalid public key type"
    if not request.get("approvalSignature"):
        return False, "No device signature: do not trust status alone"
    body = f"remote-assistance-approval-v1\n{uid}\n{device_id}\n{request_id}\n{state}\n{expires_ms}".encode()
    try:
        signature = base64.b64decode(request["approvalSignature"], validate=True)
        key.verify(signature, body, ec.ECDSA(hashes.SHA256()))
        return True, f"Verified on-device {state} decision (signaling only)"
    except (InvalidSignature, ValueError):
        return False, "Invalid host approval signature"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["configure", "devices", "request", "status", "watch"])
    args = parser.parse_args()
    if args.command == "configure":
        configure_server()
        return
    server_url = saved_server_url()
    project, api_key = load_config()
    account = login(api_key)
    uid, token = account["localId"], account["idToken"]
    devices = fetch_devices(project, uid, token)
    if args.command == "devices":
        for device_id, data in devices:
            matched = data.get("serverUrl") == server_url
            label = "MATCH" if matched else "MISMATCH (save URL and re-register on Android)"
            print(f"{data.get('name', 'Android')}  ID: {device_id}  registration: {data.get('status', 'unknown')}  Cloudflare: {label}")
        print("Registration is not evidence of an online or connected session.")
        return
    if args.command == "request":
        device_id, device = choose_device(devices)
        if device.get("serverUrl") != server_url:
            raise RuntimeError("Cloudflare URL does not match this phone. Save the same URL on Android and Register This Device again.")
        now = datetime.now(timezone.utc)
        request_id = str(uuid.uuid4())
        expiry = now + timedelta(minutes=5)
        data = {
            "fields": {
                "remoteRequest": {"mapValue": {"fields": {
                    "id": {"stringValue": request_id},
                    "source": {"stringValue": "Kali (manual request)"},
                    "serverUrl": {"stringValue": server_url},
                    "status": {"stringValue": "pending"},
                    "requestedAt": {"timestampValue": now.isoformat(timespec="seconds").replace("+00:00", "Z")},
                    "expiresAt": {"timestampValue": expiry.isoformat(timespec="seconds").replace("+00:00", "Z")},
                }}}
            }
        }
        # Confirm current key exists before sending request; user approves pairing on first use.
        pin_key(uid, device_id, device.get("approvalPublicKey"))
        request_json(doc_url(project, uid, device_id) + "?updateMask.fieldPaths=remoteRequest",
                     data, token=token, method="PATCH")
        dump_state(LAST_FILE.parent, LAST_FILE.name,
                   {"uid": uid, "deviceId": device_id, "requestId": request_id})
        print("REQUEST SENT. Open the Android app and check the request within 5 minutes.")
        print("Note: APPROVAL DOES NOT START SCREEN CAPTURE OR REMOTE CONTROL.")
        return
    try:
        last = json.loads(LAST_FILE.read_text())
    except (FileNotFoundError, ValueError) as exc:
        raise RuntimeError("No saved request. Run `request` first.") from exc
    if last.get("uid") != uid:
        raise RuntimeError("Saved request belongs to another account")
    device_id, req_id = last["deviceId"], last["requestId"]
    for _ in range(150 if args.command == "watch" else 1):
        doc = request_json(doc_url(project, uid, device_id), token=token)
        device = parse_fields(doc.get("fields", {}))
        req = device.get("remoteRequest", {})
        valid, msg = verify_approval(uid, device_id, req_id, req, device)
        if valid:
            print(msg)
            print("No Internet VNC relay is configured; there is NO screen/control tunnel.")
            return
        if args.command == "status" or req.get("status") != "pending":
            print("NOT VERIFIED:", msg)
            return
        time.sleep(2)
    print("Request timed out without valid approval")


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, OSError, KeyError, urllib.error.URLError) as exc:
        print("ERROR:", exc, file=sys.stderr)
        sys.exit(1)
