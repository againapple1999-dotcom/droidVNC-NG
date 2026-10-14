# Remote Assistance — Kali အသုံးပြုမှု

ဒီ Source ZIP က Android မှာ Firebase Account ဝင်ခြင်း၊ Device Register၊ Kali က Request ပို့ခြင်း၊ ဖုန်းပိုင်ရှင် Allow/Deny လုပ်ခြင်း၊ AndroidKeyStore Signature စစ်ခြင်းတွေ ပါပါတယ်။

**Internet ကနေ Screen/Touch ကို Kali မှာ Control လုပ်ဖို့ Relay Server မပါသေးပါဘူး။** `approved` ဆိုတာ Request အတည်ပြုတဲ့ Status ပဲ ဖြစ်ပါတယ်။

## APK ခဏခဏ မထုတ်ချင်ရင်

(၁) Kali ရှိ မူရင်း Project ကို Backup လုပ်ပါ။

(၂) `remote-assistance-changes.zip` ကို မူရင်း `~/droidVNC-NG/` ထဲ ဖြည်ပါ။ `google-services.json` ကို မဖျက်ပါနဲ့။

(၃) `git diff --check` စစ်ပြီး GitHub ဆီ Commit/Push လုပ်ပါ။

(၄) APK ထုတ်မတိုင်ခင် stable debug keystore ကို ကိုယ်တိုင်သိမ်းပြီး GitHub Secret `ANDROID_DEBUG_KEYSTORE_B64` သတ်မှတ်ရပါမယ်။ အသေးစိတ် `docs/REMOTE_ASSISTANCE_BUILD.md` မှာရှိပါတယ်။

(၅) Android ဖုန်းမှာ APK အသစ် Install ပြီး **REGISTER THIS DEVICE** ကို တစ်ကြိမ် ထပ်နှိပ်ပါ။ ဒါမှ Public Key ကို Firebase ထဲတင်မှာပါ။

(၆) Kali Terminal မှာ `python3 tools/kali_remote.py request` လုပ်နိုင်တယ်။ ဒီ CLI က အကြိမ်ပထမ Device Key ကို ယုံကြည်မယုံကြည် မေးပြီး နောက်ပိုင်း Signature ကို ပြန်စစ်ပေးမယ်။

(၇) ဖုန်းပေါ်မှာ **CHECK CONNECTION REQUEST** ကို နှိပ်ပြီး ALLOW/DENY လုပ်ပါ။ Kali မှာ `python3 tools/kali_remote.py status` နှိပ်ပြီး Verified Result စစ်နိုင်ပါတယ်။

## ခွင့်ပြုချက်

ဖုန်း Screen Sharing စဖို့ ဖုန်းပိုင်ရှင်က VNC Start ကို ကိုယ်တိုင်နှိပ်ရပြီး Android Screen Capture System Dialog ကို အတည်ပြုရနိုင်ပါတယ်။ Session ပြတ်သွားရင် ခွင့်ပြုချက်အသစ် လိုနိုင်ပါတယ်။ အမြဲတမ်း Hidden Connection မဖန်တီးပါဘူး။

**လိုအပ်နေသေးတာ**: သင့်ကိုယ်ပိုင် Public Relay/VPS Hostname + TLS Setup။ Relay မရှိဘဲ Firebase တစ်ခုတည်းနဲ့ Kali ကနေ Internet Remote Control မလုပ်နိုင်သေးပါဘူး။
