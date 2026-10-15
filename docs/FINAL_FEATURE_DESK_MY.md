# Kali ↔ Android Remote Feature Desk (VNC မလို)

**အလုပ်လုပ်ပုံ** — Firebase Login (တူညီတဲ့ Account) → Kali Connection Request → Android `CHECK CONNECTION REQUEST` → `ALLOW` → `START REMOTE FEATURE DESK (NO VNC)` → Kali `python3 tools/kali_features.py` → Menu ရွေး → ဖုန်းပေါ်မှာ `ALLOW ONCE` → Result/Transfer။

လက်ရှိ Device Monitor၊ VNC၊ Local Feature Pack ကို မဖယ်ထားပါ။ ဒီ Feature Desk က သီးခြား၊ အသုံးပြုသူမြင်နိုင်တဲ့ Foreground Session ဖြစ်ပါတယ်။ **ဒီ Source ကို Android ဖုန်းပေါ်မှာ Build/Install စမ်းပြီးကြောင်း အတည်မပြုထားပါ။** Cloudflare Unit Tests၊ Kali Protocol Unit Tests နဲ့ Java Syntax Parsing ကို စစ်ထားပါတယ်။

## အဆင့် ၁ — Final Patch ထည့်ပြီး စစ်ရန်

Termux/Kali ရဲ့ `/home/kali` ထဲမှာ မူလ Source/Worker Project များရှိရမယ်။ ဖုန်းရဲ့ Download ထဲက Patch ဖိုင်ကို ဒီလို ဖြည်ပါ—

```bash
cd /home/kali
tar -xzf /sdcard/Download/remote-feature-desk-final-patch.tar.gz -C /home/kali
```

အလွယ်ဆုံး တစ်ခါတည်း စစ်ပြီး Deploy/Push လုပ်ဖို့ (Patch Extract ပြီးနောက်)—

```bash
bash ~/remote-feature-desk-setup.sh
```

Script က Tests အရင်စစ်ပြီး Cloudflare Deploy နဲ့ GitHub Push မလုပ်ခင် တစ်ခုစီ **[y/N]** မေးမယ်။ အောင်မြင်ရင် GitHub Actions မှာ APK တစ်ကြိမ် Build လုပ်ပါ။ ကိုယ်တိုင် Manual လုပ်ချင်ရင် အောက်က Commands ကို သုံးနိုင်တယ်။

Worker Tests—

```bash
cd ~/remote-cloudflare/remote-assist-bridge
node --test tests/*.test.mjs
```

Kali Tests—

```bash
cd ~/droidVNC-NG
python3 -m unittest discover -s tests -p test_kali_features.py
```

## အဆင့် ၂ — Worker ကို Update Deploy လုပ်ရန်

```bash
cd ~/remote-cloudflare/remote-assist-bridge
npx wrangler deploy
```

Worker URL မပြောင်းပါ: `https://remote-assist-bridge.thantzinhtoo808.workers.dev`။ `SIGNAL_ROOM`, `VNC_ROOM`, `MONITOR_ROOM` များအပြင် `FEATURE_ROOM` ထပ်ပေါ်ရမည်။ မူလ `accessibility-remote` Worker ကို မထိပါ။

## အဆင့် ၃ — GitHub ကို Push ပြီး APK **တစ်ကြိမ်** Build

```bash
cd ~/droidVNC-NG
git add app .github/workflows/stable-final-apk.yml tools/kali_features.py tests/test_kali_features.py docs/FINAL_FEATURE_DESK_MY.md
git commit -m "Integrate visible approved Kali Android Feature Desk"
git push myfork feature/secure-remote
```

GitHub → Actions → **Stable Signed APK (Manual)** → Run workflow → `feature/secure-remote` → Run workflow။ Build အောင်မြင်ရင် `Remote-Assistance-Stable-APK` Artifact Download → Device B မှာ Install လုပ်ပါ။ Signing Certificate တူဖို့ Workflow ရဲ့ `ANDROID_DEBUG_KEYSTORE_B64` Secret မှန်ဖို့ လိုပါတယ်။ NDK `28.2.13676358` ပျက်နေခဲ့တဲ့ Download Error ကို လျှော့ဖို့ ၃ ကြိမ်ထိ Retry ထည့်ထားပါတယ်။ APK Build မအောင်မြင်ရင် Log ကိုကြည့်ပြီး ပြင်ဆင်ရန် လိုနိုင်ပါတယ်။

## အဆင့် ၄ — အသုံးပြုရန်

Device B Android:

1. Cloudflare URL မှန်အောင် `SAVE SERVER URL` နှိပ်၊ Firebase Login၊ Register Device။
2. `START REMOTE FEATURE DESK (NO VNC)` ကိုဝင်၊ **CHOOSE SHARED FOLDER** တစ်ကြိမ် ရွေးပြီး Folder Access ပေးပါ (SAF Permission ကို App က သိမ်းထားတယ်)။
3. Kali မှာ `python3 tools/kali_remote.py request` Run; Device B ကိုရွေးပါ။ Android မှာ `CHECK CONNECTION REQUEST` → `ALLOW`။
4. Android မှာ `START REMOTE FEATURE DESK` → `START` နှိပ်ပါ။
5. Kali မှာ—

```bash
cd ~/droidVNC-NG
python3 tools/kali_features.py
```

Menu (16 မျိုး): Status, Folder/Photo/Video List, Download, Upload, Rename, Delete, Screenshot → Kali, Local Backup, Backup ZIP Download, Contacts Export (CSV on phone), Paired Bluetooth List, Test Notification, Local 10-sec Voice Note, Local Action History, Local Daily Backup Enable/Disable။

Kali မှာ Download Files ကို `~/remote-feature-downloads/` ထဲသိမ်းပါတယ်။ တစ်ဖိုင် 50MiB အထိပဲ လက်ခံတယ်။ Phone Folder ထဲမှာ မရှိတဲ့ File ကို remote မဖတ်နိုင်ပါ။ Upload မှာ ရှိပြီးသား နာမည်တူဖိုင်ကို အလိုအလျောက် မထပ်ရေးပါ။ Contacts CSV နဲ့ Voice Note ကို ဖုန်းပေါ်မှာ Local Save လုပ်ပြီးမှ **သီးခြား Download + သီးခြား ALLOW** နဲ့ Kali ဆီ ယူနိုင်ပါတယ်။ Screenshot ကို Android Accessibility screenshot API သုံးထားပြီး Android က Secure Screen ဖမ်းယူမှုကို တားနိုင်ပါတယ်။ Screenshot အတွက် ALLOW ပြီး ၃ စက္ကန့်အတွင်း target Screen ပြောင်းနိုင်တယ်; Android Activity/OS က Session ကို အလိုအလျောက် ပိတ်နိုင်ပါတယ်။

## Permission နဲ့ Security အရေးကြီးချက်

- `Folder Access` (SAF) ကို အသုံးပြုသူရွေးပြီး တစ်ကြိမ်ပေးထားရင် persisted URI grant ကို သိမ်းနိုင်တယ်။ ဖုန်း Settings/Feature Pack ကနေ Revoke လုပ်နိုင်တယ်။
- Contacts, Bluetooth, Microphone runtime permission ကို Android က မပယ်မချင်း သိမ်းနိုင်တယ်; OS က Permissions Auto-reset/Revoke လုပ်နိုင်ပြီး တချို့အခါ ပြန်တောင်းနိုင်တယ်။
- Android 14+ MediaProjection ကို Session အသစ်တိုင်း ခွင့်ပြုရတာမျိုး ရှိတယ်; App က စနစ်ခွင့်ပြုချက်ကို ကျော်လို့ မရဘူး။ ဒီ Desk ရဲ့ Screenshot က MediaProjection မဟုတ်ဘဲ Accessibility API သုံးတယ်။
- **Feature Command တစ်ခုချင်းစီ Android မှာ `ALLOW ONCE` နှိပ်ဖို့ လိုတယ်။** ဒါဟာ Android Permission ထပ်ပေးရတာမဟုတ်ဘဲ ဖိုင်ဖျက်ခြင်း၊ ဓာတ်ပုံပို့ခြင်း၊ Contacts၊ Microphone စတဲ့ လုပ်ဆောင်ချက်တစ်ခုချင်းစီအတွက် ဖုန်းပိုင်ရှင်အတည်ပြုချက် ဖြစ်တယ်။
- ဖုန်းက Feature Desk Screen ကို ထွက်လိုက်ရင် ပုံမှန်အားဖြင့် Disconnect ဖြစ်တယ်၊ Screenshot Capture လုပ်ခိုင်းထားတဲ့ ခဏတာအခြေအနေကလွဲလို့။ Reconnect အတွက် Request/Allow အသစ်လိုလာနိုင်တယ်။ Session က ၁ နာရီအများဆုံးပဲ။
- **Remote Touch/Swipe/Input ကို ဒီ Feature Desk က မပို့ဘူး။** ရှိပြီးသား VNC Input နဲ့ သီးခြားပါ။ Android Notification အခြား App တွေကို ဖတ်တာ၊ နောက်ကွယ် Microphone ဖွင့်တာ၊ အလိုအလျောက် ဖိုင်အားလုံး Cloud Upload လုပ်တာ မပါပါ။

ဤ Patch က အပြီးအပိုင် အမှားကင်းတဲ့ APK ဖြစ်ကြောင်း အာမမခံနိုင်ပါ။ Android Version၊ OEM Permission များနဲ့ GitHub Build ကို ဖုန်းတကယ် Install ပြီး စမ်းမှ အတည်ပြုနိုင်ပါတယ်။
