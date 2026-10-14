# Cloudflare URL + Same Firebase Account (Kali Controller / Android Host)

**Status: setup and request validation only. NOT yet Internet screen/control.**

ဒီပြင်ဆင်ချက်က အောက်ပါအရာတွေပဲ လုပ်ထားပါတယ်။

1. Android Login Screen မှာ Cloudflare Server URL ကို HTTPS root URL အဖြစ် သိမ်းမယ်။
2. Email/Password နဲ့ Firebase Login ဝင်ပြီးတာနဲ့ Android ကို အကောင့်အောက်မှာ Device Register လုပ်မယ်။
3. Android ဖုန်းက Device Document မှာ Server URL ကို သိမ်းမယ်။
4. Kali မှာ အဲဒီ URL ကို တစ်ကြိမ် သတ်မှတ်ထားမယ်။
5. Server URL မတူရင် Kali က Remote Request မပို့ဘူး; Android ကလည်း Request မပြဘူး။
6. ALLOW/DENY က User-selected Request တစ်ခုအတွက်သာဖြစ်ပြီး Android Screenshot Permission မပေးပါ။

## Installation into the existing Kali project (do not build yet)

Download `cloudflare-same-account-patch.zip` to Android Download folder. Then:

```bash
cd ~/droidVNC-NG
# Keep the original modifications as a new branch or copy before overwriting
unzip -o /sdcard/Download/cloudflare-same-account-patch.zip -d ~/droidVNC-NG
python3 -m py_compile tools/kali_remote.py tools/test_same_server.py
python3 tools/test_same_server.py
python3 tools/test_approval_protocol.py
git diff --check
```

## Configure Kali once

```bash
cd ~/droidVNC-NG
python3 tools/kali_remote.py configure
```

Enter the **root HTTPS URL** (e.g., `https://YOUR-NEW-SIGNAL-WORKER.workers.dev`, no `/ws` or tokens). Stored at `~/.config/remote-assistance/server.json` with permissions `0600`. Credentials are NOT stored there. The GitHub Personal Access Token is not required for this step.

Later, after you build/install this revised Android app:
- Enter the **same** Cloudflare URL and tap **Save Server URL**.
- Sign in using the **same verified Firebase Email + Password** as Kali. Android automatically registers this device; you do not need to enter a connection code.
- On Kali run `python3 tools/kali_remote.py devices` to check `Cloudflare: MATCH`.
- For a new explicit session: `python3 tools/kali_remote.py request`, then Android **Check Connection Request → ALLOW**, and `python3 tools/kali_remote.py status`.

## Important limits

- The previously used `accessibility-remote` Worker may NOT implement the signaling protocol required by the current project. Matching a URL does not make incompatible backends work together.
- A Worker used for WebRTC *signaling* cannot itself transmit the video or VNC session automatically. The Android WebRTC host, Kali WebRTC receiver, and TURN/relay integration have not been completed.
- Sharing an account does not grant remote-control rights. Android host-local approval, foreground indication and Android screen-capture consent are required.
- No attempt is made to circumvent Android force-stop, screen-capture permission, or device-owner Disconnect.
- Do not expose VNC port 5900 publicly or leave the VNC password blank.
- Rebuild after completing all code changes, not now. A stable Android signing key is needed to update the same installed APK without reinstalling and losing local device identity.
