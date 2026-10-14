# Remote Assistance — source review and build notes

## What is implemented in this package

- Android email/password Firebase sign-in (from your original source).
- Android device registration to Firestore (from your original source).
- Kali-originated, five-minute Firestore request signaling; owner manually checks and chooses Allow/Deny (from your original source).
- **New:** local AndroidKeyStore P-256 key for each registered device, published as `approvalPublicKey` on explicit registration.
- **New:** each owner-issued decision is signed over account UID, device ID, unique request ID, decision and expiry. The Kali tool pins the public key and checks signatures; it **does not** treat an unverified `approved` Firestore field as actual authorization.
- **New:** re-registering merges device properties, so pending request data is not overwritten by a repeated register action.
- **New:** a visible **End Connection Request** action marks signaling as ended (it is not a VNC session stop button). Android Sign Out also requests that the original local VNC service stop and best-effort marks signaling as ended; future relays still require server-side revocation.
- **New:** reject start of the original VNC server if the VNC password is empty. Default *new-install* Start on Boot is disabled. Existing device preferences are unchanged.
- **New:** optional manual stable-signing GitHub Actions workflow. Follow setup below.
- **New:** `tools/kali_remote.py` to list, send, and **cryptographically verify** request decisions from Kali.

## NOT implemented — must not be mistaken for a functioning remote-control tunnel

- No publicly reachable **secure relay**, no WebSocket/WebRTC TURN service, no authenticated transport for VNC screen/input streams.
- No automated remote file upload/download. Original VNC local-network file transfer behavior may differ by Android version.
- No auto-resume after Android closes a capture session or force-stops the app. It is not possible to bypass MediaProjection consent for a fresh Android 14+ session.
- `approved` confirms a time-limited **signaling request**, not that Android started screen sharing. The owner must explicitly start a visible VNC session and accept Android screen-capture prompts.
- `registered` is **not** an online/active status. Do not interpret it as a real-time heartbeat.
- **No compiled APK is included.** This ZIP has no Firebase application configuration or native Git submodule payloads. Building must happen in your existing repository or GitHub Actions after checking out submodules.

## To update your existing Kali checkout

The `remote-assistance-changes.zip` file contains only modified or new paths. Back up your existing project first and unpack it into `~/droidVNC-NG/`. Never delete your existing `app/google-services.json`.

```sh
cp -a ~/droidVNC-NG ~/droidVNC-NG.backup
unzip -o /sdcard/Download/remote-assistance-changes.zip -d ~/droidVNC-NG
cd ~/droidVNC-NG
python3 -m py_compile tools/kali_remote.py
git diff --check
```

Use the *new* Kali script after building/installing an Android APK with the key enrollment change:

```sh
sudo apt install -y python3-cryptography
python3 tools/kali_remote.py devices
python3 tools/kali_remote.py request
# On Android, tap Register This Device again once to publish its public key;
# then the owner can CHECK CONNECTION REQUEST and ALLOW/DENY.
python3 tools/kali_remote.py status
```

The first `request` asks to pin the Android device's public key locally. It is not a connection code. **If the key changes**, the script refuses verification; investigate before trusting the replacement.

## Stable APK signing (one signing identity for future updates)

The existing `ci.yml` still does *ephemeral* debug builds; each fresh GitHub runner may get a different signing certificate. The new manual workflow `stable-final-apk.yml` **fails closed** unless a signing-key secret is set.

On your own Kali device, generate a NEW stable debug key **once**, if you don't already have a trusted debug key you control:

```sh
mkdir -p ~/.android
keytool -genkeypair -v -keystore ~/.android/debug.keystore \
 -alias androiddebugkey -storepass android -keypass android \
 -dname 'CN=Android Debug,O=Android,C=US' \
 -keyalg RSA -keysize 2048 -validity 10000
```

If this file already exists, do not overwrite it. Keep your keystore private and backed up. To prepare the secret, `base64 -w0 ~/.android/debug.keystore` produces a sensitive value; paste it **ONLY** into your GitHub repository's Actions Secret `ANDROID_DEBUG_KEYSTORE_B64`, never in chat/issue/commit/source. Run the new manual **Stable Signed APK (Manual)** workflow on your feature branch. The resulting SHA-1 will remain the same on subsequent stable builds using that keystore.

Since prior CI APKs used different debug keys, installing the new APK on top of the old one may fail (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`); uninstalling the old app can erase its local settings and device ID. Back up any needed data first, then re-register the device after installation. Since email/password authentication is used, the Google OAuth SHA-1 fingerprints are not necessary for that provider.

`app/google-services.json` was omitted intentionally from the uploaded ZIP. Keep it in **your** private working repository/CI configuration. The **Google Services Gradle plugin** still requires it to build.

## Required to finish internet-based Kali remote control

You must provision or choose a reachable **TLS-secured WSS/TURN relay endpoint** (server domain or managed relay provider). Firebase Firestore is a signaling store, not a VNC transport or tunneling service. The relay must validate Firebase ID tokens server-side, bind each tunnel to an authenticated device and an approved session, encrypt traffic in transit and expire/revoke access on owner Disconnect/Logout. The Android host also needs a visible, owner-started foreground screen-sharing service and Android MediaProjection consent per capture session. Kali then connects through the authenticated tunnel with a normal VNC client.

Do **not** port-forward bare VNC port 5900 to the Internet. Google/Firebase login alone does not authenticate existing VNC connections.

## Important security limitation

Your Firestore rule allows every client logged into the **same UID** to write `/users/{uid}/devices/{deviceId}`. A compromised authenticated client could therefore forge the plain `remoteRequest.status`. The new Kali script ignores it unless the owner device's previously pinned AndroidKeyStore key signed the exact short-lived request. This does not defend against compromise of the host device itself or first-use key impersonation before manual pin verification.

The Android UI's Allow decision must **not** be interpreted as granting indefinite screen capture, stealth access, or access to private files. All sharing remains opt-in and visible, with a way for the owner to stop the session.
