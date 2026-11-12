# Bigme stock keyboards: security review (2026-11-11)

Source: stock firmware B1051C_R_4.9.0, system_b from `BIGME_B1051C_system_dump/super.bin` (5 LP extents, ext4), extracted
with debugfs. Static analysis only (jadx 1.5.6, aapt2 badging, strings). Nothing was installed or run.

| | `/system/app/XRZ_Input/XRZ_Input.apk` | `/system/app/BK_Input/BK_Input.apk` |
|---|---|---|
| sha256 (16) | `4bf73e4f75346d36` | `5cbb0bc86b9ea334` |
| package / label | `com.xrz.input` "Bigme input method" 4.0 (vc 1, target 32) | `com.android.inputmethod.latin` "Android Keyboard (AOSP)" 1.3 (vc 1, target 29) |
| INTERNET | **yes** | **no** |
| other notable perms | RECORD_AUDIO, SYSTEM_ALERT_WINDOW, GET_TASKS, KILL_BACKGROUND_PROCESSES, WRITE_SETTINGS, BLUETOOTH(_ADMIN), CHANGE_WIFI_STATE, BILLING, MOUNT_UNMOUNT_FILESYSTEMS | the standard AOSP LatinIME set (contacts/user dictionary/accounts/DOWNLOAD_WITHOUT_NOTIFICATION) |
| native | armeabi-v7a only: digitalink (ML Kit), jni_pinyinime, legacy agc/ns, vad, mp3lame, xrzEncrypt | arm64/armv7/x86 (AOSP JNI) |

## XRZ_Input ("Bigme input method")
- **No analytics/ad/push SDKs:** no umeng, bugly, jpush, getui, tencent, baidu or iflytek. Firebase is present
  only as core/common pieces pulled in by ML Kit. There is no Firebase Analytics or Crashlytics.
- **No hard-coded Chinese hosts.** The only `.cn` host is `translate.google.cn`, inside an unused houbb `NetUtil`
  connectivity helper (a DNS lookup only, and nothing in the app calls it).
- **Network path 1, voice input (`com.xrz.inputapi.bigmeai.BigmeRT`, from `com/ocr/RecodLayout`):**
  - It records the microphone (with VAD, noise suppression and AGC) and streams the audio over a **plain,
    unencrypted** WebSocket to `ws://<Settings.System domain_sound_sever>:8888/BigmeAI?function=1&language=…&accent=…&isSoftInput=true`.
  - On stock, `domain_sound_sever=usa.bigme.vip` (settings-backup-2026-10-17).
  - On InKognito the setting is **unset** (`null`), so the URL is `ws://null:8888/…` and nothing is sent.
  - It runs only when the user presses the voice button.
- **Network path 2, handwriting:** ML Kit Digital Ink downloads recognition models from `dl.google.com/handwriting/models/…`
  (Google), on demand, per language.
- `domain_user_sever` (stock `usa.bigme.vip`) is read by `DomainClient.getUserDomain`, which has no callers.
- `com/ocr/HttpUtil` / `OkHttpUtils` are generic helpers with no callers in the app code. `libxrzEncrypt.so` is never
  loaded.
- The declared GET_TASKS / KILL_BACKGROUND_PROCESSES / clipboard / contacts / Wi-Fi / Bluetooth / Settings.System writes
  are **not used** in the app code (boilerplate).
- `XrzHttpUtils.isSignOk` checks its own signing certificate (MD5 against 3 Bigme certificates), so the APK must be
  installed unmodified (original signature).
- `com/App.java` looks up the Bigme framework service `xrz.framework.manager`, which does not exist on the GSI. On-device
  test needed (the handwriting pad may depend on it).

**Verdict:** it can be ported if the voice server stays unset.
- Never set `domain_sound_sever`, and have the layer delete it at boot if anything sets it. Then the only possible
  traffic is Google's handwriting model downloads.
- Voice input will not work (by design).
- Install the APK unmodified as a normal app (on stock it was updated in /data/app too). It is 32-bit only; this
  device supports 32-bit (`ro.zygote=zygote64_32`).

## BK_Input (Bigme-modified AOSP keyboard)
- No INTERNET permission. The AOSP dictionary-pack downloader is present but goes through the system DownloadManager,
  and the metadata URI is AOSP's empty default.
- Safe, but its package name `com.android.inputmethod.latin` is the same as the ROM's AOSP keyboard (different
  signature). It cannot be installed next to it, only instead of it.
