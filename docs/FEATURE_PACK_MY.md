# Remote Assistance Feature Pack (onsite consent-first)

ဒီ Source သည် Android phone-local Feature Pack ပါ။ Cloudflare/Firebase/VNC ရှိပြီးသား project ကို ဆက်ထိန်းထားသည်။

## ပါဝင်သော Feature များ

- ဖုန်းပေါ် File Manager: SAF Folder တစ်ခါရွေးပြီး Browse/Share/Rename/Delete, Revoke Access
- Photo/Video/Document Picker ကနေ ရွေးပြီး ထည့်သွင်းခြင်း
- Accessibility Service ဖွင့်ထားပြီး Android 11+ မှာ ဖုန်းပေါ်က Screenshot ခလုတ်ဖြင့် PNG သိမ်းခြင်း; 4-second delay ဖြင့် အခြား screen သို့ switch လုပ်နိုင်သည်
- Local ZIP Backup manually သို့မဟုတ် ခွင့်ပြုပြီး daily WorkManager; 50 MiB နှင့် first-level files only
- Battery/Network/Storage/Android device status, Cloudflare URL/account status
- Local Contacts CSV export, Paired Bluetooth device list
- Test Notification, local voice note start/stop, local action log
- Server URL / Firebase login / Remote request allow end ရှိပြီးသား feature မပြောင်း

## မပါသေးသော Feature

- Kali ကနေ File Transfer၊ file browse၊ Direct Remote Screenshot trigger / result download
- Cloudflare remote backup, remote access to contact/notification/mic
- Third-party notifications display, Bluetooth active pairing/connect control
- App ဖြင့် Screenshot ယူနိုင်ခြင်းသည် secure windows များကို မကျော်လွှားနိုင်

Permission ပေးပြီးသားဆိုတာ Android က နောက်ထပ်မတောင်းတော့မယ်လို့ *အမြဲအာမခံခြင်းမဟုတ်*။ Android က permission ရုပ်သိမ်းလို့ရပြီး session sharing သည် ပိုင်ရှင်က မြင်နိုင်/ရပ်နိုင်ရမည်။

## APK Build

GitHub source ZIP သည် native submodule content မပါသောကြောင့် ZIP အတွင်းမှ APK ကို တိုက်ရိုက် compile မရနိုင်။ Feature branch အပေါ် GitHub repo ကို update လုပ်ပြီး GitHub Actions stable-final-apk ကို manually run ပါ; Repo checkout သည် native submodule recursive ကို fetch လုပ်ပေးမည်။ Signing key secret လိုအပ်သည်။ Compile / install / device test မလုပ်ရသေးသည့်အတွက် Working APK ဟု မပြောနိုင်ပါ။
