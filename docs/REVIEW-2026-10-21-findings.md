# Review 2026-10-21 — all findings (appendix)

122 findings from the lens reviewers and the critic; 102 survived adversarial verification, 20 were refuted.
> **Status update 2026-10-21 16:00:** statuses refreshed after E-Ink Control 0.15.4, eink-compat 0.1.30 and b1051c-adbsecure 0.1.0. Of the 9 High findings, 4 are fixed and verified on the tablet; the other 5 are "fixed in the ROM" only and stay unverified until the ROM is flashed. Anti-shake now works (module 0.1.28) and ships off.


## Critical (0)

## High (9)

### Exported KoBridgeReceiver lets any app trigger root-executed full-screen GC16 flashes and front-light changes, without a permission
- **Where:** `work/app/einkctl/src/org/b1051c/einkctl/KoBridgeReceiver.java:21` (lens sec-system; reported medium, verified high)
- **Status:** fixed: E-Ink Control 0.15.2 (KO_* only while KOReader is in front; root/adb SET_LIGHT)
- **What:** The receiver is exported with no permission (AndroidManifest.xml:43). KO_REFRESH runs Root.async("setprop vendor.debug.hwc.shim.gc16_req ...") with a 400 ms in-process rate limit, so the shim's gc16 thread re-sends the frame as a full-screen GC16. KO_LIGHT, with no rate limit, runs a root shell that writes the front-light sysfs node whenever the values differ from the current ones. The values are clamped integers, so there is no injection. This is the only path I found by which an unprivileged app reaches the shim's knobs or a root shell. It may also be reported by the app lens.
- **When it fails:** A zero-permission background app loops on sendBroadcast(new Intent("org.b1051c.einkctl.KO_REFRESH").setPackage("org.b1051c.einkctl")). The panel then does a full GC16 flash roughly every 0.4-0.8 s indefinitely, in whatever app is in front, and every request forks a Magisk su session, which costs battery and may add Magisk toasts or log rows. Alternating KO_LIGHT extras (cold=222,warm=222 / 0,0) while the screen is on keeps the front light at maximum at night, or flickering, and queues an unbounded number of root shells on Root's single-thread executor.
- **Fix:** Accept KO_* only while KOReader is the foreground package: AppProfileService already tracks currentPackage(), so ignore the intent unless it equals org.koreader.launcher (or the KOReader package list). Add a rate limit to KO_LIGHT, and coalesce Root.async work, for example by replacing a pending light command instead of queuing a new one. Keep the existing 400 ms limit on KO_REFRESH.

### KoBridgeReceiver is exported with no permission: any installed app can flash the panel (GC16) without limit and set or persist the front light
- **Where:** `work/app/einkctl/src/org/b1051c/einkctl/KoBridgeReceiver.java:21` (lens java-einkctl; reported high, verified high)
- **Status:** fixed: E-Ink Control 0.15.2 (KO_* only while KOReader is in front; root/adb SET_LIGHT)
- **What:** The receiver is exported in AndroidManifest.xml:43 with no android:permission and never checks who sent the intent. KO_REFRESH only has a 400 ms rate limit (lines 22-26), so a sender gets up to 2.5 full-screen GC16 flashes per second, and each one also starts a root su process. KO_LIGHT (lines 27-29) calls Eink.setLight, which saves cold/warm into prefs, forces link_brightness=false (Eink.java:128-131) and lights the panel now, or at the next SCREEN_ON.
- **When it fails:** Any Play app with no permissions calls sendBroadcast(new Intent("org.b1051c.einkctl.KO_REFRESH").setPackage("org.b1051c.einkctl")) every 400 ms from a background service or job. The e-ink panel keeps flashing and cannot be used, battery drains through the su spawns, and Magisk logs (and by default toasts) every request. Or the app sends KO_LIGHT cold=222 warm=222 at night: the light goes to maximum on the next wake and the user's 'Follow Android's brightness slider' setting is silently turned off.
- **Fix:** Accept KO_* only while KOReader is the foreground app: check AppProfileService.currentPackage() against KOReader's package (org.koreader.launcher*), because KOReader only sends these while it is in front. Optionally, on API 34+, also use BroadcastOptions.setShareIdentityEnabled in the Lua patch together with getSentFromPackage(). Add a rate limit to KO_LIGHT, and do not change link_brightness from the bridge.

### Exported KoBridgeReceiver lets any app flash the whole panel every 400 ms and force the front light on, each request starting a root su process
- **Where:** `work/app/einkctl/src/org/b1051c/einkctl/KoBridgeReceiver.java:22` (lens compat-perf; reported high, verified high)
- **Status:** fixed: E-Ink Control 0.15.2 (KO_* only while KOReader is in front; root/adb SET_LIGHT)
- **What:** KoBridgeReceiver is exported with no permission (AndroidManifest.xml:43). The only guard on KO_REFRESH is a 400 ms gap, and KO_LIGHT has no limit at all. Every accepted request queues a `su` fork on Root's unbounded single-thread executor (Root.java:10). KO_LIGHT also writes two Settings.Secure keys, which SystemUI and the keeper both observe. Because KeeperService keeps the app's uid out of the idle state, broadcasts from background apps are delivered too.
- **When it fails:** A Play app with no permissions loops sendBroadcast(new Intent("org.b1051c.einkctl.KO_REFRESH").setPackage("org.b1051c.einkctl")) every 400 ms. The shim gets a gc16_req each time and sends a full-screen GC16 flash 2.5 times a second for as long as the app runs, which drains the battery and makes the device unusable. A second loop sends KO_LIGHT with cold=222 and warm=222, alternating with 221 to get past the echo check in Eink.setLight. Whenever the screen is on, the front light is held at maximum and any change the user makes is overwritten. Each broadcast forks su. If broadcasts arrive faster than su can finish (about 10-20/s), the Root queue grows without bound in the einkctl process.
- **Fix:** Act on KO_* only while KOReader (org.koreader.launcher*) is the foreground app (AppProfileService.currentPackage()) and the screen is interactive; drop the request otherwise. Coalesce light requests: keep only the latest value and apply it at most every ~150 ms, so there is one su call per window. For refresh, add a per-minute budget alongside the 400 ms gap. Bound the Root queue, or collapse identical pending commands.

### 'Wallpaper follows theme' (default on) and the Dark theme note promise a wallpaper switch that never happens on a fresh install
- **Where:** `work/app/einkctl/src/org/b1051c/einkctl/MainActivity.java:161` (lens a11y-ui-release; reported medium, verified high)
- **Status:** fixed in the ROM: provisioning places both wallpapers (unverified: the ROM has not been flashed)
- **What:** The note says 'Switching also changes the wallpaper (home + lock screen)'. The switch defaults to true. ThemeSync only sets wallpaper-D-cream.png / dark-D.png from the app's external files dir. Nothing in the app, the modules or the scripts ships or provisions those files (grep finds them only in ThemeSync.java). Without them, ThemeSync logs a warning and leaves the wallpaper alone.
- **When it fails:** An XDA user flips Dark theme. The wallpaper stays on the GSI default (dark navy, which reads as a black screen on e-ink) or whatever was there before, and there is no on-screen hint why. Only the owner's device, where the files were pushed with adb, behaves as the text says.
- **Fix:** Ship the two wallpapers (or plain cream/black fallbacks) as APK assets and copy them to the external files dir on first run. Alternatively, hide or disable the switch and change the note when ThemeSync.status reports the files missing.

### Dark theme and light wallpapers exist only as files pushed by hand into E-Ink Control's external files folder
- **Where:** `work/app/einkctl/src/org/b1051c/einkctl/ThemeSync.java:293` (lens fresh-device; reported high, verified high)
- **Status:** fixed in the ROM: provisioning places both wallpapers (unverified: the ROM has not been flashed)
- **What:** ThemeSync takes wallpaper-D-cream.png and dark-D.png (ThemeSync.java:40) only from getExternalFilesDir(). The owner pushed those files there over adb. The APK does not contain them, and when a file is missing the wallpaper is silently left unchanged.
- **When it fails:** A fresh flash wipes /data, so /sdcard/Android/data/org.b1051c.einkctl/files/ is empty. Clearing the app's storage or reinstalling it has the same effect. ThemeSync logs 'wallpaper ... not found; wallpaper left as it is' and the TrebleDroid default stays on home and lock. That wallpaper is almost entirely dark navy and reads as a black screen on e-ink (owner, log 2026-10-18 ~13:26). Switching the Dark theme never changes the wallpaper. The user has no UI to fix this; it needs adb plus the right file ownership in Android/data.
- **Fix:** Bundle both wallpapers as einkctl assets, as is already done for the shutdown frames. Fall back to the asset stream (WallpaperManager.setStream(getAssets().open(...))) when the external file is missing, and keep the external file only as an override. If you would rather not bundle them, the provisioner must push both files with the app's uid and run THEME_SYNC force.

### Google sign-in needs a WebView provider that was fixed only by a manual setting
- **Where:** `work/gapps/module/b1051c-gapps/post-fs-data.sh:6` (lens fresh-device; reported medium, verified high)
- **Status:** fixed in the ROM: provisioning sets it (unverified: the ROM has not been flashed)
- **What:** After the gapps module was added, WebViewUpdateService had no current provider and Google sign-in (MinuteMaid) crashed with MissingWebViewPackageException. The fix, `cmd webviewupdate set-webview-implementation com.android.webview`, was applied by hand. The root cause was never found and nothing in the layer reproduces the fix.
- **When it fails:** The alpha ROM adds the Setup Wizard, which signs in through WebView. If the provider is null again on a fresh flash, sign-in crashes on the first screen the user sees. The user can skip it, but Play Store cannot be set up.
- **Fix:** Have the provisioner run set-webview-implementation com.android.webview, or set Settings.Global webview_provider, before the Setup Wizard reaches sign-in. Add `dumpsys webviewupdate` (current package not null) to the fresh-flash test.

### On a fresh device, home-screen nav buttons, labels and status icons turn white on the cream wallpaper: persist.wallpapercolors.max_dark_area=25 was set by hand and nothing in the layer sets it
- **Where:** `work/module/b1051c-eink-compat/post-fs-data.sh:24` (lens fresh-device; reported high, verified high)
- **Status:** fixed in the ROM: provisioning stage 1 sets it (unverified: the ROM has not been flashed)
- **What:** Dark text over the cream wallpaper relies on WallpaperColors returning HINT_SUPPORTS_DARK_TEXT. That hint was obtained only after a manual `setprop persist.wallpapercolors.max_dark_area 25`. The default threshold is 5 %, and the shipped cream cat wallpaper has about 11 % dark pixels.
- **When it fails:** On a fresh install the prop is unset (default 5). ThemeSync or the provisioner sets wallpaper-D-cream.png in the Light theme. WallpaperColors counts about 11 % dark pixels, which is over 5 %, so it gives no dark-text hint. Launcher3's taskbar, which HomeBg makes transparent on home, then draws back/home/recents/all-apps in taskbar_nav_icon_light_color #FFFFFFFF on cream. NavRefresh samples that glyph and turns the refresh button white too. Lawnchair labels set to AUTO turn white, and the status bar icons over home likely go light as well. The result is navigation and text that cannot be seen on the home screen.
- **Fix:** Seed the prop in the GSI block of post-fs-data.sh (for example `resetprop -n persist.wallpapercolors.max_dark_area 25`), before zygote reads it, or have the provisioner persist it. Add a fresh-device check: after setting the cream wallpaper, dumpsys wallpaper colorHints must include bit 1 (dark text).

### GSI detection by ro.build.fingerprint fails after TrebleDroid 'Securize' or a props-spoofing module, so every display fix and einkd are skipped
- **Where:** `work/module/b1051c-eink-compat/service.sh:11` (lens native-einkd-scripts; reported high, verified high)
- **Status:** fixed: eink-compat 0.1.26 (/system/phh)
- **What:** Both scripts check for the GSI with `case $(getprop ro.build.fingerprint) in *treble*|*tdgsi*|*phh*)` (service.sh:11, post-fs-data.sh:24). The GSI's own Securize feature rewrites ro.build.fingerprint to the vendor fingerprint. The same happens with any module that spoofs the fingerprint (for example MagiskHide Props Config). The case then fails and service.sh exits at line 11. That skips backpressure-off, the SF 1008 boot guard, the shader-priming opt-out, boot_damage, the SF watchdog, the 1008/DEVICE re-check loop and the einkd start.
- **When it fails:** A user taps Treble Settings > Misc > Securize, which phh-securize.sh implements by touching /metadata/phh/secure and rebooting. rw-system.sh then runs `copyprop ro.build.fingerprint ro.vendor.build.fingerprint` (confirmed in work/gsi/system-td-arm64-vanilla.img), so the fingerprint becomes `alps/vnd_k6877v1_64_k419/...:user/dev-keys` (live ro.vendor.build.fingerprint), which matches none of the patterns. At the next boot SF starts with backpressure (the Mali wedge), the boot animation stays a DEVICE layer, and HW overlays stay on, giving the 'stale/black panel' that service.sh:6-8 describes. There is no watchdog. einkd never starts, so the Cover tile, Pen ink layer and check_commit are all dead. The user has no working screen to undo Securize from.
- **Fix:** Detect the GSI with something Securize and spoofers do not rewrite, such as `[ -e /system/bin/rw-system.sh ]` or `[ -d /system/phh ]`. Or gate on the Bigme vendor (ro.vendor.build.fingerprint starts with alps/vnd_k6877v1_64_k419). Since the module only ever ships for this GSI, the gate could also be dropped. Log a line when the gate does skip.

### First boot after flashing loops: stage 1 reboots inside the uncommitted userdata checkpoint, so every write from boot 1 is rolled back
- **Where:** `work/rom/payload-src/provision-stage1.sh:83` (lens critic; reported critical, verified high)
- **Status:** fixed in the ROM: stage 1 commits a pending checkpoint first (unverified: the ROM has not been flashed)
- **What:** /data on this tablet is f2fs with checkpoint=fs (/vendor/etc/fstab.mt6877, read on the device). The installer runs `fastboot set_active <slot>` in fastbootd, and that call marks the slot unsuccessful. On the next boot vold's needsCheckpoint therefore returns true and fs_mgr mounts /data with checkpoint=disable. update_verifier then defers markBootSuccessful because checkpointing is supported. The checkpoint is committed only by system_server when boot completes; the tablet's own logs show 'ActivityManager: About to commit checkpoint' at boot completion. Stage 1 stops zygote at zygote-start (line 54) and reboots (lines 56/83) before that commit. f2fs discards everything written while checkpointing is disabled once the filesystem is unmounted. That loses the modules in /data/adb/modules_update, /data/adb/magisk, stage1.done, the stage1.attempts counter, the persisted max_dark_area and magisk.db.
- **When it fails:** Precondition: any normal install with flash-inkognito.sh (set_active, then -w). Boot 1 formats /data, mounts it with checkpoint=disable, installs the modules, writes the markers and reboots. On boot 2 /data looks exactly as it did right after the format: no markers, attempts=0, no modules. Stage 1 runs again, and so on. The 'at most 2 attempts, never loops' guard does not help because its counter lives on the rolled-back /data. Google setup never appears. Each boot uses up one of LK's tries on the unsuccessful slot. After 7 tries LK marks the slot unbootable and falls back to the other slot, which the project's own notes say is not bootable. Result: boot loop or brick, and recovery needs a reflash or backup restore. Inferred from AOSP vold, update_verifier and f2fs behaviour plus this device's fstab and logs; the ROM's first boot has never run on a device.
- **Fix:** Before stage 1 writes anything to /data, commit the checkpoint as root (vold commitChanges through vdc; it also marks the slot successful and remounts /data with checkpoint=enable), and check that vold.checkpoint_committed=1. The other option is to let boot 1 reach boot completion, which commits, and only then install the modules and reboot. Keep the attempt counter outside the checkpointed filesystem (for example /metadata) so the 'never loops' guarantee holds. On the first test flash, confirm in the boot-1 log that update_verifier says 'Deferred marking slot' and that the commit happens before the stage-1 reboot.

## Medium (38)

### Home-app detection misses Launcher3 (package visibility): the 'Home screen' profile and 'Full refresh when returning to the home screen' never work with Launcher3
- **Where:** `work/app/einkctl/AndroidManifest.xml:11` (lens java-einkctl; reported high, verified medium)
- **Status:** fixed: E-Ink Control 0.15.5 (`<queries>` MAIN+HOME; MATCH_DEFAULT_ONLY, priority < 0 skipped). Verified 21.10 on the tablet: Launcher3 (Recents) now logs "(home)", Settings no longer does. Side effect for the owner to judge: the home GC16 now fires when Recents opens from an app
- **What:** Profiles.homePackages() (Profiles.java:107-116) calls queryIntentActivities(MAIN+HOME, 0), but <queries> only declares MAIN+LAUNCHER. Under Android 11+ package visibility the app cannot see a launcher that has no LAUNCHER activity. Launcher3 QuickStep (QuickstepLauncher declares HOME/DEFAULT/MONKEY/LAUNCHER_APP and no LAUNCHER) is therefore never treated as home. With flags=0 the query also probably returns Settings' FallbackHome (HOME, priority -1000), which would make com.android.settings count as home.
- **When it fails:** On a fresh device where Launcher3 (TrebleDroid's default, patched by b1051c-launcher3) is the home app: X -> home gives home=false, so the default-on home_clean GC16 never fires and ghosting stays on the home screen. The 'Home screen (launcher)' profile row in the app does nothing. The live tablet log shows this: 'foreground com.android.launcher3' without '(home)', while 'app.lawnchair (home)' is detected. With Lawnchair as home, Recents/overview (Launcher3) is treated as an ordinary app and gets the global profile. Opening Settings from an app probably applies the home profile and triggers a home GC16.
- **Fix:** Add <intent><action android:name="android.intent.action.MAIN"/><category android:name="android.intent.category.HOME"/></intent> to <queries>. In homePackages, use MATCH_DEFAULT_ONLY and skip entries with r.priority < 0 (FallbackHome), or exclude com.android.settings.

### Nothing restarts KeeperService after the user stops the app from 'Active apps': the front light then stays on while the screen sleeps
- **Where:** `work/app/einkctl/src/org/b1051c/einkctl/AppProfileService.java:79` (lens java-einkctl; reported medium, verified medium)
- **Status:** fixed: E-Ink Control 0.15.5 + 0.15.6. Correction found on the device: after Active apps -> Stop (stopAppForUser) the accessibility framework lists the service as *crashed* and never rebinds it, so "the system rebinds" was wrong. 0.15.6 adds AppProfileService.recover (from the QS tiles, the settings screen and KOReader's bridge): when the service is enabled but not connected, the enabled-services setting is rewritten, the bind returns, and onServiceConnected starts the keeper (KeeperService.ensure). Verified end to end 21.10 with `am stop-app`
- **What:** Only KeeperService registers SCREEN_OFF/ON (frontlight off/on), SHUTDOWN (shutdown screen and theme flag) and the ThemeSync config hook. It is started only by BootReceiver (boot or update) and MainActivity.onCreate. The accessibility service, which the system rebinds and which is allowed to start an FGS, never starts it.
- **When it fails:** The user taps 'Stop' on 'E-Ink Control active' in Android's Active apps list (FGS Task Manager). The keeper is torn down and not restarted (an explicit stop, not a sticky restart). From then until the next reboot or app open, sleeping does not switch off the lm3630a front light, because only this app writes it. The result is battery drain and a lit panel in a dark room. Theme sync and the shutdown screen also stop working without any message.
- **Fix:** In AppProfileService.onServiceConnected (and maybe in TileService onStartListening), call startForegroundService(KeeperService). This is allowed while the system's accessibility binding holds the uid. Optionally also check that the keeper is running from the tiles.

### Spoofable foreground tracking: profile engine trusts TYPE_WINDOW_STATE_CHANGED events from any app
- **Where:** `work/app/einkctl/src/org/b1051c/einkctl/AppProfileService.java:127` (lens sec-apps; reported medium, verified medium)
- **Status:** fixed: E-Ink Control 0.15.8 (TYPE_WINDOW_STATE_CHANGED is a hint: the active application window from the window list decides the package). Normal switching verified 22.10 (Settings, KOReader, home)
- **What:** Android dispatches TYPE_WINDOW_STATE_CHANGED events to accessibility services whether or not the sender has a visible window. The framework only forces the package name to be one of the sender's own packages. onAccessibilityEvent takes e.getPackageName() as the new foreground app and immediately runs Profiles.onForeground. That re-resolves the profile, changes the refresh mode, colour and N settings through setprop, and can rewrite Secure settings (inversion, greyscale, high contrast) and trigger a GC16. KoBridgeReceiver's gate reads the same `current` value.
- **When it fails:** Precondition: an installed app with no permission emits window-state events while another app (for example KOReader with its own profile, or an app with per-app inversion) is really in front. E-Ink Control then applies the global profile instead of the real app's profile, which can revert inversion or greyscale and trigger clean-on-dark refreshes. Repeated events make the display flip and flash. Spoofing cannot impersonate KOReader, so the KOReader gate does not open, but `current` becomes unreliable. The dispatch rule should be checked against the A16 AccessibilitySecurityPolicy.
- **Fix:** Treat the event as a hint only. On TYPE_WINDOW_STATE_CHANGED, confirm the package with the window list (activeApp(): the active or focused TYPE_APPLICATION window) before calling foreground(), and ignore events whose package does not own the active application window. Use the same window-derived value in frontPackage() for the KOReader gate.

### applyFrontlight has no 'screen is on' guard: the brightness observer, adb IMPORT and the keeper start can light the panel while the device sleeps
- **Where:** `work/app/einkctl/src/org/b1051c/einkctl/Eink.java:52` (lens java-einkctl; reported low, verified medium)
- **Status:** fixed: E-Ink Control 0.15.3 (LEDs written only while the screen is on)
- **What:** setLight checks isInteractive() (line 136), but applyFrontlight does not. It is reached from the SCREEN_BRIGHTNESS ContentObserver when link_brightness is on (KeeperService.java:78), from importSettings -> applyAll (Eink.java:275, also through the adb IMPORT broadcast), and from KeeperService.onCreate. In that last path the light is switched on and then immediately off again (KeeperService.java:62-64).
- **When it fails:** With 'Follow Android's brightness slider' on, an automation or bedtime app writes SCREEN_BRIGHTNESS while the tablet sleeps, or a restore is triggered over adb at night. The front light switches on with the screen off and stays on until the next SCREEN_OFF. An app update while asleep makes the light flash briefly.
- **Fix:** Inside applyFrontlight, skip the sysfs write when the PowerManager is not interactive and only publish the value; the keeper applies it at SCREEN_ON. Pass the interactive state into applyAll instead of switching on and then off.

### Light-follows-brightness path switches the front light on while the screen is off
- **Where:** `work/app/einkctl/src/org/b1051c/einkctl/KeeperService.java:78` (lens sec-apps; reported medium, verified medium)
- **Status:** fixed: E-Ink Control 0.15.3 (LEDs written only while the screen is on)
- **What:** When 'Follow Android's brightness slider' (link_brightness) is on, every change to Settings.System.SCREEN_BRIGHTNESS calls Eink.applyFrontlight. That function (Eink.java:52-56) writes the sysfs light values as root with no isInteractive() check. setLight has had that guard since 2026-10-19 ('must never switch the light on in a dark room while the screen is off'), but this path does not.
- **When it fails:** Preconditions: link_brightness is enabled, and any app holding the user-granted 'Modify system settings' access (WRITE_SETTINGS) changes the brightness value while the tablet sleeps. Automation and night-scheduler apps do this legitimately. The front light turns on with the screen off and stays on until the next SCREEN_OFF broadcast, which drains the battery overnight and lights a dark room. importSettings -> applyAll run from adb or root while the screen is asleep has the same gap.
- **Fix:** Make applyFrontlight respect the screen state the same way setLight does. Always publish the values, but only write the sysfs light when PowerManager.isInteractive() is true; otherwise leave the write to the SCREEN_ON handler. Keep frontlightOff() unconditional.

### KOReader bridge trusts whichever app is in front, not who sent the broadcast, and accepts everything when the accessibility service is off
- **Where:** `work/app/einkctl/src/org/b1051c/einkctl/KoBridgeReceiver.java:28` (lens sec-apps; reported medium, verified medium)
- **Status:** fixed: E-Ink Control 0.15.8 + KOReader patch 22.10 (the patch sends with BroadcastOptions.setShareIdentityEnabled; the receiver accepts only getSentFromPackage() == org.koreader.launcher, fails closed otherwise; the foreground check stays as the second condition). Verified 22.10 05:5x with the patch's self-test (marker file .bridge-test): identity accepted; at KOReader start the foreground check still said Lawnchair (the window had not been reported yet), which is the startup ordering only
- **What:** The 0.15.2 gate (line 28) only checks that KOReader is the app in front. It never checks the sender. While KOReader is in front, any installed app running in the background can still send KO_LIGHT or KO_REFRESH and have it accepted. When AppProfileService is not connected (the user turned it off, it crashed, or a build has no provisioning), the receiver fails open for every app. The effects are bounded: GC16 refreshes are limited to one per 400 ms, the light values are clamped to 0..222, nothing reaches root as a string, and setLight never lights the panel while the screen is off.
- **When it fails:** Preconditions: an untrusted app is installed (no permission needed), and either KOReader is in front, or the E-Ink Control accessibility service is not running. That app can change the cold/warm front light (including full brightness or off) and flash full-screen GC16 refreshes up to about 2.5 times a second during a reading session. The results are discomfort, battery drain and the user's light settings being overwritten (the QS sliders follow). This is the residual of the 2026-10-21 fix. The ROM provisioning (apply-system-defaults.sh app_einkctl) does enable the service, so on a fresh device it narrows to the 'KOReader in front' case.
- **Fix:** Authenticate the sender instead of guessing from the foreground app. On API 34+ (this ROM is API 36), have the KOReader patch send with BroadcastOptions.makeBasic().setShareIdentityEnabled(true) (sendBroadcast(intent, null, options.toBundle()) through the JNI path it already uses). In onReceive, accept only when getSentFromPackage() equals org.koreader.launcher, and optionally pin KOReader's signing certificate with PackageManager.hasSigningCertificate. Fail closed when the sender identity is missing, and also when AppProfileService.running() is false; keep the foreground check as a second condition. Add a small rate limit to KO_LIGHT as well.

### KoBridgeReceiver 'KOReader in front' fix is incomplete: KOReader stays 'front' under the lock screen, the shade and while asleep, and the check fails open whenever the accessibility service is unbound
- **Where:** `work/app/einkctl/src/org/b1051c/einkctl/KoBridgeReceiver.java:28` (lens critic; reported medium, verified medium)
- **Status:** fixed: E-Ink Control 0.15.2 (KO_* only while KOReader is in front; root/adb SET_LIGHT)
- **What:** frontPackage() returns AppProfileService.current. ignored() skips com.android.systemui, 'android' and E-Ink Control's own windows, and activeApp() looks only at TYPE_APPLICATION windows. So when the keyguard, shade or AOD covers KOReader, or the screen is off after reading, current stays org.koreader.launcher. During that time the receiver still accepts KO_LIGHT and KO_REFRESH from any installed app, exported and without a permission. When the service is not connected (the user turns it off from Android's 'can view and control your screen' review notification, since accessibility.xml has no isAccessibilityTool, or user 0's services are unbound during a guest session), the receiver accepts every request as before the fix.
- **When it fails:** Typical use: the user reads in KOReader, then the tablet sleeps or locks. Any background app can then change the stored front-light level and on/off state through KO_LIGHT. setLight stores it, publishes it to the QS sliders, and the keeper applies it at the next SCREEN_ON (for example full brightness at night, or light forced off). The same app can also trigger GC16 flashes over the lock screen, at most every 400 ms. No permission is needed.
- **Fix:** Accept KO_* only when PowerManager.isInteractive() is true, KeyguardManager.isKeyguardLocked() is false and shadeOpen() is false. Clear 'current' on SCREEN_OFF. Fail closed (drop KO_LIGHT, keep only a rate-limited KO_REFRESH) when the accessibility service is not running. Where possible, also check the sender's identity (Android 14+ getSentFromUid with an identity-sharing broadcast from the KOReader patch).

### The accessibility service carries half the features, but the app has no status, prompt or recovery for it
- **Where:** `work/app/einkctl/src/org/b1051c/einkctl/MainActivity.java:284` (lens fresh-device; reported medium, verified medium)
- **Status:** fixed: E-Ink Control 0.15.8 (state line + "Turn the accessibility service on" button at the top of the screen when the service is not connected; AppProfileService.enable keeps the other services; KeeperService posts a notification 20 s after start when the service is off; adb/provisioning action ActionReceiver A11Y_ENABLE). Verified 22.10: setting emptied -> A11Y_ENABLE -> restored and bound
- **What:** These features need AppProfileService to be connected:
- per-app profiles
- Fast while typing
- the nav refresh button
- shade detection for ThemeSync's full refresh
- the shutdown picture

On the owner's device it was enabled by hand. The only hint in the app is the note 'Needs the E-Ink Control accessibility service.', with no state shown and no button.
- **When it fails:** On a fresh device, or after an einkctl update that changes the service capabilities (enabled_accessibility_services was found null after the 0.6.1 install, log line 25):
- coverScreen returns null (AppProfileService.java:24), so ShutdownScreen draws nothing. The panel keeps the power menu or the last app frame after power-off, which the owner explicitly rejected.
- Profiles never switch, and the nav refresh button never appears.
- ThemeSync's 'shade closed' check is always false, so its full refresh fires under the open shade.

None of this is visible to the user.
- **Fix:** Show the service state at the top of MainActivity with a button to Settings.ACTION_ACCESSIBILITY_SETTINGS. Optionally, enable it as root by appending to enabled_accessibility_services while keeping the other entries. Have KeeperService post a notification when the service is not bound, and re-check after MY_PACKAGE_REPLACED.

### The DPI 'Keep?' dialog and its countdown are not tied to the activity: if MainActivity is destroyed within 15 s, d.dismiss() crashes the whole app process and the auto-revert never runs
- **Where:** `work/app/einkctl/src/org/b1051c/einkctl/MainActivity.java:327` (lens java-einkctl; reported medium, verified medium)
- **Status:** fixed: E-Ink Control 0.15.5 (countdown and revert are process-level; onDestroy only drops the dialog, a recreated activity shows it again with the time left). Code only, not exercised on the panel: FEATURE-TESTS verification item 13
- **What:** applyDpi posts a self-rescheduling Runnable on a main-looper Handler that calls d.dismiss() and then revert.run(). Nothing cancels it in onDestroy. When the activity is destroyed, WindowManagerGlobal.closeAll removes the dialog's window as leaked. A later Dialog.dismiss() then calls removeViewImmediate on a detached decor and throws IllegalArgumentException ('not attached to window manager'). That exception is thrown before revert.run(), on the main thread of the process that also hosts KeeperService and the accessibility service.
- **When it fails:** The user drags DPI to 450, the UI turns huge, and they swipe E-Ink Control away in Recents, or toggle Dark theme or Bold text (uiMode/fontWeight are not in configChanges, so the activity is recreated). The process survives through the FGS. 15 s later the old runnable calls d.dismiss(): the app crashes, the keeper and accessibility service restart, and the DPI is not reverted. The protection the countdown exists for is gone. A DPI change that crosses a size or screenLayout bucket may also relaunch the activity directly (not verified).
- **Fix:** Keep the Handler and dialog as fields, remove the callbacks and dismiss the dialog in onDestroy. If the countdown is still running at that point, run revert (or move the countdown into the keeper). Guard with isFinishing()/isDestroyed() and d.isShowing() before dismiss().

### E-Ink Control sliders never save or apply when changed with TalkBack, Switch Access or the keyboard
- **Where:** `work/app/einkctl/src/org/b1051c/einkctl/MainActivity.java:585` (lens a11y-ui-release; reported medium, verified medium)
- **Status:** fixed: E-Ink Control 0.15.9 (every slider, the DPI bar and the outline bar: a change from the keyboard / TalkBack / Switch Access (fromUser without a touch) is saved and applied 400 ms (DPI: 600 ms) after the last step; touch keeps apply-on-release). Code only, not exercised with TalkBack
- **What:** Every slider persists and applies its value only in onStopTrackingTouch: cold/warm/warmth light, the Color sliders, clean_every, n_dark (line 585), DPI (line 105) and outline width (line 153). SeekBar accessibility actions (ACTION_SCROLL_FORWARD/BACKWARD, ACTION_SET_PROGRESS) and D-pad/arrow keys go through setProgressInternal(..., fromUser=true). They call onProgressChanged but never onStart/onStopTrackingTouch, so the label changes and nothing is saved or applied.
- **When it fails:** A TalkBack user swipes up on 'Cold (white)' to raise the light. The label shows the new value but the front light does not change and the pref is not written; on reopening, the slider is back at the old value. 'DPI: 250 (release to apply)' is never applied for a non-touch user.
- **Fix:** In the shared listener, track a 'touching' flag (set in onStartTrackingTouch, cleared in onStopTrackingTouch). In onProgressChanged with fromUser && !touching, persist and apply right away, or debounce about 400 ms to keep one e-ink refresh. Do the same for the DPI and stroke bars; for DPI, start the keep/revert dialog after the debounce.

### Export/import carries the tri-state 'saved live value' keys (base_grey/base_invert/base_hc): color inversion, grayscale or high-contrast text can get stuck system-wide
- **Where:** `work/app/einkctl/src/org/b1051c/einkctl/Profiles.java:263` (lens java-einkctl; reported medium, verified medium)
- **Status:** fixed: E-Ink Control 0.15.4 (base_* are device state: not exported, kept across import; verified 21.10)
- **What:** deviceState() only protects keys starting with 'state_'. base_grey, base_invert and base_hc are this device's runtime memory of Android's real setting before a per-app override, yet they are exported, and import clears and replaces them. tri() (line 224) then records the overridden value as the new 'base', or later restores a stale base copied from another moment or device.
- **When it fails:** App X has profile invert=On. While in X (inversion on, base_invert=0 saved), the user long-presses the nav refresh button. That opens E-Ink Control and the current package stays X, because the app's own package is ignored. The user imports a backup taken with no override active. importAll removes base_invert. applyAll -> apply(X, force) -> tri(want=true) finds no base and saves live=1 as the base. When the user leaves X, tri(null) 'restores' 1, so color inversion stays on in every app until it is turned off by hand. The reverse also happens: an export made during an override carries base_grey=0, and a later import turns off a grayscale the user had set globally.
- **Fix:** Treat base_* as device state, the same as state_*: leave them out of exportAll and keep this device's values across importAll. Alternatively, before importing, restore every base_* to the live setting and remove it.

### Settings import and stored modes are not validated before reaching the vendor HWC
- **Where:** `work/app/einkctl/src/org/b1051c/einkctl/Profiles.java:286` (lens sec-apps; reported medium, verified medium)
- **Status:** fixed: E-Ink Control 0.15.4 (normMode maps unknown modes to Auto; resolve() clamps; verified mode 999 -> 0)
- **What:** importAll copies any key with any declared type into the prefs (lines 286-297), with no allowlist and no range checks. The resulting integers are not command-injection vectors, because every root command is built from constants and ints. They do reach `setprop vendor.xrz.global_refresh_mode <int>` and `vendor.xrz.global_color_mode <int>` unvalidated: normMode only remaps 183/Hyper, line 169 takes a user preset's `refresh` without normMode at all, and colorMode (line 196) is never clamped. Hyper 183 has already been shown to freeze the panel, so other out-of-range waveform or mode values carry the same risk. A non-JSON or odd-typed value also silently replaces all of the user's settings, because clear() runs first.
- **When it fails:** Precondition: the user imports a corrupt, hand-edited or forwarded backup. The import location (Android/data/org.b1051c.einkctl/files, mode 2770 on the device, or files/) is not writable by other apps, so this is not reachable by an unprivileged app. A refresh value like 183 inside a user preset, or any unsupported mode or colour-mode number, is applied by KeeperService on every boot and app switch. That can freeze the e-ink panel persistently until the user recovers it over adb.
- **Fix:** Validate centrally. Make normMode return 0 (Auto) for any value not in Eink.MODES after the Hyper and Pen mapping, and use it on every read path, including presetInto line 169. Clamp colorMode to 0..4 and n_dark/clean_every to 0..50. In importAll, accept only known keys with their expected types and ranges (plus profile:<valid package name> entries that parse as JSON with known fields). Parse and validate the whole file before calling clear(), and reject the import on any error.

### Import commits unvalidated typed prefs: one type mismatch puts the app (keeper, accessibility service, settings screen) in a crash loop until its data is cleared
- **Where:** `work/app/einkctl/src/org/b1051c/einkctl/Profiles.java:290` (lens java-einkctl; reported medium, verified medium)
- **Status:** fixed: E-Ink Control 0.15.4 (every entry parsed and type-checked before clear(); a bad-type import is rejected with no crash, verified 21.10)
- **What:** importAll clears all prefs and writes each entry with whatever type its 't' tag says, then calls commit() (line 298). Nothing checks that well-known keys keep their expected types. The rest of the app reads with typed getters (p.getInt("mode"), getBoolean("light_on"), getInt("profile")...), and those throw ClassCastException. importSettings catches the exception thrown by applyAll after the commit (Eink.java:275-279) and reports 'Import failed', but the bad prefs are already saved. presetNames (Profiles.java:70) also throws an NPE when a 'presets' element is not an object.
- **When it fails:** A user imports a shared or hand-edited einkctl-settings.json in which "mode" is {"t":"s","v":"178"}, or a key's type changed between versions. MainActivity.recreate() crashes in onCreate. Every accessibility event runs Profiles.apply -> resolve -> getInt("mode") and crashes the process. KeeperService.onCreate crashes on every START_STICKY restart and on every boot. Front light and refresh mode cannot be controlled until the user clears the app's data.
- **Fix:** Parse into a temporary map first and check every known key against a schema (type and range). Reject the whole file, or drop the bad keys, before calling clear()/commit(). Wrap resolve's typed reads, or read through helpers that fall back to the default on ClassCastException.

### Every root command starts a new su session: with Magisk's defaults each one shows a toast and adds a superuser-log row (app switches, KOReader full refreshes, shade opens), and a hung command blocks the whole queue
- **Where:** `work/app/einkctl/src/org/b1051c/einkctl/Root.java:25` (lens java-einkctl; reported medium, verified medium)
- **Status:** fixed: E-Ink Control 0.15.8 (one persistent su shell per namespace, sentinel per command, 10 s timeout that restarts the shell; runningDsu read once per process with plain getprop, no root). Verified 22.10 05:5x: keeper applied the mode through the shell, no ERR lines
- **What:** Root.exec runs ProcessBuilder("su","-c",cmd) for every call. Nothing in the repo sets the app's Magisk policy (logging/notification). Magisk's default per-app policy notifies (toast) and logs on every granted request, and each notify also costs magiskd a broadcast. Hot paths that call it: Profiles.apply on most app switches (line 202), Eink.clearScreen on every nav tap, home return and KO_REFRESH, frontlight at every screen on/off, and TileRom.onStartListening, which starts a thread and su on every shade pull just to read an immutable ro.gsid.image_running. exec has no timeout, and Q is a single thread, so one blocked command (an unanswered Magisk prompt, a debugfs write stuck in the e-ink driver) stalls every later command, including frontlight-off at SCREEN_OFF.
- **When it fails:** A user installs the public alpha with Magisk defaults. Every app switch, every KOReader full refresh and every pull of the shade with the Switch-system tile shows a 'granted Superuser rights' toast. On e-ink each toast costs extra refreshes, and the Magisk superuser log grows without bound. If a command ever blocks, the light stays on through sleep and mode changes stop working until the process restarts.
- **Fix:** Use one persistent root shell (a su process fed through stdin, with a sentinel per command) and give each command a timeout and process.destroy() when it expires. Cache runningDsu() once per process, since ro.* props are fixed until reboot. Document or provision (from the module) the app's Magisk policy with logging and notification off.

### QS tiles expose no on/off state or current value to TalkBack
- **Where:** `work/app/einkctl/src/org/b1051c/einkctl/Tiles.java:24` (lens a11y-ui-release; reported medium, verified medium)
- **Status:** fixed: E-Ink Control 0.15.9 (TOGGLEABLE_TILE meta-data on Front light, Grayscale, Anti-shake, Color restore and Cover; Tiles.show sets the state description to the subtitle for every tile). Code only, not exercised with TalkBack
- **What:** Tiles.show sets only label, subtitle and state. No tile declares the TOGGLEABLE_TILE meta-data in the manifest (lines 64-99), so SystemUI gives them a plain State: no Switch role, no checked state, and empty 'On/Off' state text. No Tile.setStateDescription() is set either, and the subtitle (secondary label) is not part of the tile's content description.
- **When it fails:** A TalkBack user focuses 'Grayscale', 'Anti-shake', 'Cover', 'Color restore' or 'Front light' and hears only the label and 'button', with no indication whether it is on. 'Refresh mode' and 'Color' never announce the selected mode or preset, so the user cannot tell what a tap changed.
- **Fix:** Add <meta-data android:name="android.service.quicksettings.TOGGLEABLE_TILE" android:value="true"/> to the true toggles (Anti-shake, Color restore, Grayscale, Cover, Front light). In Tiles.show call t.setStateDescription(sub) (API 30+) for every tile, so mode and preset names and the light levels are announced.

### Any unrelated SystemUI crash permanently disables the e-ink SystemUI plugin (shade theme and QS light sliders), and nothing in the layer notices or re-enables it
- **Where:** `work/app/einkui/AndroidManifest.xml:10` (lens critic; reported medium, verified medium)
- **Status:** fixed: E-Ink Control 0.15.5 (KeeperService.checkPlugin at keeper start and every SCREEN_ON; re-enable as root at most once per boot; SystemUI reloads the plugin on the PACKAGE_CHANGED, confirmed in the decompiled PluginManagerImpl). Verified 21.10: pm disable -> re-enabled; second time the same boot -> left off
- **What:** In the device's A16 SystemUI (decompiled PluginManagerImpl.PluginExceptionHandler, in the scratchpad review notes), checkStack() looks for plugin classes in a crash stack. If none are found it calls disableAll() on every non-privileged plugin. That runs PluginEnabler.setDisabled, which persists COMPONENT_ENABLED_STATE_DISABLED on org.b1051c.einkui/.EinkOverlayPlugin and survives reboots. Only a PACKAGE_REPLACED of the plugin re-enables it. The plugin is not in SystemUI's allowlist, E-Ink Control never checks the component state, and provisioning (app_einkui) checks only the PLUGIN permission, once.
- **When it fails:** Any Java crash in SystemUI on this GSI and MTK vendor combination, even one unrelated to the plugin (a SystemUI restart through kill is harmless), silently brings back vanilla A16 white/Monet shade styling on e-ink. It also removes the cold/warm light sliders that replace Android's brightness slider (which does nothing for the front light while link_brightness is off). This lasts until the user reinstalls an updated einkui APK, which most users cannot do.
- **Fix:** When KeeperService starts, and once per boot, check the plugin component's enabled state as root (and SystemUI's auto-disabled plugin prefs). Re-enable it at most once per boot so a plugin that really crashes cannot cause a SystemUI crash loop, and post a notification or log entry when that happens. Also add the check to provisioning's re-assert step.

### Platform is signed with the public AOSP test key: any APK signed with it gets platform-signature permissions or the system uid
- **Where:** `work/app/einkui/build.sh:29` (lens sec-system; reported high, verified medium)
- **Status:** disclosed: release KNOWN-ISSUES (Security) + SPEC; cannot be fixed without re-signing the GSI
- **What:** The live /system/framework/framework-res.apk is signed with the well-known AOSP test platform certificate (SHA-256 c8a2e9bccf597c2fb6dc66bee293fc13f2fc47ec77bc6b2b0d52c11f51192ab8, DN CN=Android/android@android.com). einkui is signed with the public work/sysui/keys/platform.pk8 and SystemUI accepts it. Because that private key is in the public AOSP tree, anyone can produce an APK that the system treats as platform-signed. The gapps module's `resetprop ro.control_privapp_permissions log` (work/gapps/module/b1051c-gapps/post-fs-data.sh:6) widens this further: privileged-permission allowlisting is not enforced.
- **When it fails:** A user sideloads an APK built for test-keys ROMs, signed with build/target/product/security/platform.pk8. It is granted every signature-level permission (INSTALL_PACKAGES, INJECT_EVENTS, WRITE_SECURE_SETTINGS, MANAGE_USERS, READ_FRAME_BUFFER, ...), or declares sharedUserId=android.uid.system and runs as uid 1000 in system_app. That domain may also set vendor_default_prop and default_prop, which covers all vendor.xrz.* knobs and persist.b1051c.*. The app can then install packages silently, inject input and take screenshots: system-level control without root. By the rubric's wording this is critical. I rated it high because it is inherited from every test-keys GSI and needs a purpose-built APK; I did not check whether Play's upload checks reject this key, so the realistic path is a sideloaded or third-party-store APK.
- **Fix:** This cannot be fixed inside the module layer without re-signing the GSI. Disclose it clearly in the XDA post ("test-keys platform: only install APKs you trust; platform-signed APKs gain system privileges"). For the release, keep ro.control_privapp_permissions at enforce and ship a complete privapp-permissions XML for the gapps subset instead of `log`. Longer term, build or re-sign the system image with private release keys and re-sign einkui with them.

### Front-light slider icons are drawn outside the slider in RTL locales
- **Where:** `work/app/einkui/src/org/b1051c/einkui/LightSliders.java:192` (lens java-sysui-launcher; reported medium, verified medium)
- **Status:** fixed: einkui 0.4.7 (both SeekBars forced LAYOUT_DIRECTION_LTR). Installed 22.10 05:5x; the en-US shade unchanged in a screencap; a Hebrew-locale check is for the owner
- **What:** The badge sliders put the cold/warm icon in an asymmetric left padding (setPadding(zone+ext, 0, ext, 0)), and SliderTrack draws it at b.left - ext - iconZone/2. SeekBar has mirrorForRtl=true by default, so in RTL ProgressBar.drawTrack translates to getWidth()-paddingRight and scales x by -1. The icon then lands at about W + zone/2, past the slider's right edge, and the left icon zone stays empty.
- **When it fails:** Device language is Hebrew, Arabic or Persian (the owner and many XDA users) with the InKognito palette. Opening QS shows two sliders with no cold/warm icon: it is clipped or drawn into the margin, and an empty gap sits on the left. The icon is the only visual cue that tells the two front-light sliders apart, apart from the fill color.
- **Fix:** Call setLayoutDirection(View.LAYOUT_DIRECTION_LTR) on each SeekBar (or on the box), or draw the icon in SliderTrack mirror-aware: put it on the side given by getLayoutDirection() and use symmetric padding.

### In RTL locales (e.g. Hebrew) the QS front-light slider icons are drawn off-screen
- **Where:** `work/app/einkui/src/org/b1051c/einkui/LightSliders.java:192` (lens a11y-ui-release; reported medium, verified medium)
- **Status:** fixed: einkui 0.4.7 (same change as the previous finding)
- **What:** The badge sliders use absolute, asymmetric padding: setPadding(zone + ext, 0, ext, 0), with the icon in the left padding, drawn at b.left - ext - iconZone/2 (SliderTrack.java:85). They are default-style SeekBars in SystemUI's RTL-capable context (Widget.SeekBar has mirrorForRtl=true), so in RTL ProgressBar draws the progress drawable translated to width - paddingRight and mirrored. The icon then lands about 0.15h-0.75h beyond the view's right edge (fully clipped), the track is offset, and an empty zone remains on the left.
- **When it fails:** With the system language set to Hebrew or Arabic, which is plausible for the Hebrew-speaking owner and for XDA users, the cold and warm sliders in the shade lose their sun and flame icons. The two identical-looking bars can then be told apart only by fill color, which also collapses in plain mode or on gray rendering.
- **Fix:** Either force LTR on the two sliders (b.setLayoutDirection(View.LAYOUT_DIRECTION_LTR)), or use setPaddingRelative and let SliderTrack check getLayoutDirection() to put the icon at the end in RTL.

### Choosing "Android default" for the shade leaves most of the e-ink styling in place
- **Where:** `work/app/einkui/src/org/b1051c/einkui/ShadeStyler.java:265` (lens java-sysui-launcher; reported medium, verified medium)
- **Status:** open
- **What:** When b1051c_shade_theme changes live to -1 (E-Ink Control > Notification shade > "Android default"), resolveMode() sets mode=0. After that, walk() skips styleTile/styleNotification* (line 265) and styleHeaderView returns early (line 503), but nothing puts back what earlier frames changed. Only scrims and the footer RenderEffect are reverted. destroy() (130-141) restores tiles, scrims and footer only, never header colors, notification pills, the expand pill or icon RenderEffects.
- **When it fails:** The shade is in e-ink light or dark. The user picks "Android default" in E-Ink Control. The scrims go back to vanilla, but every QS tile keeps the replaced colorActive/colorInactive... fields and the TileShape/ripple background. The clock, date, status icons and battery keep their forced colors (black INK in light mode). Notification cards keep the cream/black tint and outline, and "Manage"/"Clear all"/action buttons stay as e-ink pills. The result is half-styled until SystemUI recreates those views (theme, density or locale change, or a reboot). If that happens while the vanilla shade is dark, black header text sits on a dark scrim and is unreadable. The same leftovers stay after the plugin is uninstalled or disabled.
- **Fix:** When mode goes from styled to 0, call the restore path once: restoreTile for every key in tiles, save the original text colors/tints in headerGen and restore them, restore the saved backgrounds of footer pills and notification children (keep the original Drawable/ColorStateList the first time each view is styled), and setRenderEffect(null) on the icons. destroy() should run the same restore.

### The nav-row jump fix and the hidden search bar are a runtime overlay that is off after a fresh install
- **Where:** `work/app/launcheroverlay/AndroidManifest.xml:7` (lens fresh-device; reported medium, verified medium)
- **Status:** fixed in the ROM: provisioning enables it (unverified: the ROM has not been flashed)
- **What:** The dimen that stops the taskbar's 3-button row from jumping 170 px between home and apps (grid_visualization_vertical_cell_spacing) and the hidden search bar sit in a non-static RRO installed into /data. Overlays installed this way stay disabled until `cmd overlay enable`, and Launcher3 has to be restarted once after that.
- **When it fails:** On a fresh install the overlay is missing, or installed but disabled. With Lawnchair as home, the nav buttons sit about 170 px higher on the home screen than in apps (owner report, log line 71), and the HomeBg transparent row is misaligned.
- **Fix:** Move the dimen and the layout into the static overlay already shipped in the b1051c-launcher3 module (B1051cNavOverlay, isStatic=true), or add a second static RRO there, so the module carries it. Failing that, the provisioner must run `cmd overlay enable org.b1051c.overlay.launcher` and then force-stop com.android.launcher3.

### ro.charger.no_ui switches the AOSP charger to its no-op health loop; power-key and unplug handling claimed 'unchanged' is unverified
- **Where:** `work/charger/b1051c-charger.rc:8` (lens native-einkd-scripts; reported medium, verified medium)
- **Status:** open
- **What:** In AOSP charger.cpp, main() calls healthd_charger_nops() when ChargerProperties::no_ui() is true (the GSI's charger binary contains the ro.charger.no_ui sysprop). That mode has no key handling (Charger::ProcessKey: hold power about 2 s -> reboot to normal boot) and no 'unplugged -> shut down in 10 s' logic. The rc comment (line 6) says the power key and shutdown are unchanged, but the changes log only records the picture test (WRITE 12), not a power-key or unplug test.
- **When it fails:** The tablet is powered off and plugged in, showing LK's charging picture. The user presses or holds power for 2-3 s to start it. With the no-op charger nothing happens, and only a PMIC long-press reset (about 8-11 s, if enabled in KPOC) or unplugging gets out. The MTK kernel's kpoc_power_off_check probably handles unplug, but nothing in our layer guarantees it. New users will think the device will not boot while charging.
- **Fix:** On the device, test in charger mode: a short and a 3 s power press, and unplugging (does it power off?). If the power key is dead, either keep the UI charger (no no_ui) with an animation that leaves the panel alone, or document the long-press. Fix the comment either way.

### einkd sends the pen stream to whoever holds the abstract name @b1051c_ink, with no peer check
- **Where:** `work/einkd/einkd.c:91` (lens native-einkd-scripts; reported medium, verified medium)
- **Status:** fixed: eink-compat 0.1.42 (einkd checks SO_PEERCRED uid 1000 + SO_PEERSEC composer domain, else closes and retries)
- **What:** Abstract unix names are first come, first served, and every domain may create/bind/listen on its own unix_stream_socket (plat policy: `allow domain self (unix_stream_socket (... bind connect listen accept ...))`; SEQPACKET maps to that class). einkd runs as root in u:r:magisk:s0, which may connect to any domain's socket. It connects to whatever listens on @b1051c_ink and never checks SO_PEERCRED or SO_PEERSEC.
- **When it fails:** A Play app with no permissions keeps trying to bind @b1051c_ink. When the composer restarts (crash or HAL restart; init's restart delay leaves a gap of seconds), the app wins the name. The new shim's bind then fails ('pen ink layer off'), and einkd's next connect in Pen mode reaches the app. The app receives every Wacom report (x, y, pressure, touch flags): the user's handwriting in note apps, signatures and so on. Fast ink also stays disabled until the next composer restart.
- **Fix:** After connect, call getsockopt(SO_PEERCRED) and require uid == 1000 (the composer's user). Better, also check SO_PEERSEC == u:r:hal_graphics_composer_default:s0. Close and retry if it does not match. Alternatively use a filesystem socket in a directory only system/root can write.

### No SYN_DROPPED resync and silent drop of final ink samples can leave the shim's pen session 'down', holding all vendor commits (frozen panel)
- **Where:** `work/einkd/einkd.c:117` (lens native-einkd-scripts; reported medium, verified medium)
- **Status:** fixed: eink-compat 0.1.26 (einkd re-reads the pen; shim watchdog)
- **What:** pen_event() builds pen state only from deltas and ignores EV_SYN/SYN_DROPPED, with no EVIOCGKEY/EVIOCGABS resync. ink_send() silently drops a sample on EAGAIN (line 99). If the lift and out-of-range events are lost (evdev buffer overrun while einkd is not scheduled, or a full socket queue while the shim thread is busy), the last sample the shim receives has INK_TOUCH set with p>0. The shim clears st.down only on a non-touch sample, and settles (ending g_ink_session) only when !st.down for idle_ms. Until then hook_commit holds every vendor commit (hwcshim.cpp:976-979).
- **When it fails:** In a Pen-mode app, einkd gets no CPU for about 160 ms or more under load. The Wacom evdev client buffer is 128 events, about 32 reports of X/Y/PRESSURE/SYN, so the kernel drops the queue, including BTN_TOUCH 0 and ABS_PRESSURE 0. Or the shim thread stalls about 1 s (full-screen ink_prepare plus colour pipeline) and the queued sends hit EAGAIN. The pen leaves range, no further reports arrive, and the shim keeps st.down=true and g_ink_session=1. Every app frame (finger scrolling, page turns) is held, so the panel looks frozen until the pen touches again, Pen mode is switched off, or the display goes off. If einkd dies mid-stroke, the shim logs 'einkd gone' and is left in the same stuck state.
- **Fix:** In einkd: on SYN_DROPPED, ignore events until the next SYN_REPORT, then re-read BTN_TOUCH/BTN_TOOL_PEN/BTN_TOOL_RUBBER/BTN_STYLUS (EVIOCGKEY) and ABS_X/Y/PRESSURE (EVIOCGABS) and send a fresh sample. Never drop a sample whose flags differ from the last one sent: keep it pending and retry. In the shim: reset st.down on einkd EOF, and treat 'no sample for ~300 ms while down' as pen up.

### einkd scans /dev/input only once at start, so page turns from devices connected later never count for the auto full refresh
- **Where:** `work/einkd/einkd.c:149` (lens native-einkd-scripts; reported medium, verified medium)
- **Status:** fixed: eink-compat 0.1.42 (inotify on /dev/input: new nodes opened through wanted(), removed ones dropped; MAXDEV 32)
- **What:** Device discovery is a single readdir at startup (lines 146-167), with no inotify or rescan. Any input device that appears after einkd starts is never watched: Bluetooth page turners and keyboards (connected after boot, and re-created on every reconnect), USB keyboards, or a digitizer that re-registers. Its key presses never raise vendor.xrz.check_commit, so the shim and HWC do not count those page turns for clean_frequency.
- **When it fails:** A reader connects a Bluetooth page turner after boot (the usual case) and turns pages in an app whose profile sets clean_frequency=N. Each page turn arrives from a /dev/input/eventN that einkd never opened. check_commit stays 0, the page turn is not counted, and the 'full refresh every N pages' never fires, so ghosting builds up. A page turner that idles out and reconnects gets a new node each time. If it was present at boot, the old node also triggers the busy-loop finding above.
- **Fix:** Watch /dev/input with inotify (IN_CREATE/IN_DELETE, like EventHub). Open new nodes through wanted(), drop removed ones, and keep pen_idx and hall_path in step with the changes. Raise MAXDEV or make pfd dynamic.

### einkd spins at 100 % CPU forever once any watched input device disappears
- **Where:** `work/einkd/einkd.c:197` (lens native-einkd-scripts; reported high, verified medium)
- **Status:** fixed: eink-compat 0.1.26 (einkd drops vanished devices)
- **What:** The main loop only acts on POLLIN. When an evdev device is unregistered, evdev_poll returns POLLHUP|POLLERR on every call (no POLLIN once drained) and read() returns ENODEV. The fd is never closed or removed from pfd, so poll() returns at once on every iteration and einkd busy-loops until reboot.
- **When it fails:** The tablet boots with a removable input device attached. Examples: USB-C earbuds with inline buttons (a HID device), a USB keyboard, or a Bluetooth keyboard or page turner that reconnected before einkd's one-time scan just after boot_completed. Name filters do not exclude these, and they have EV_KEY, so einkd watches them. The user then unplugs the device, turns Bluetooth off, or the BT device idles out and its uhid node is destroyed. poll() now returns r>0 immediately with revents=POLLHUP|POLLERR, line 197 skips it, `activity` stays 0, `continue`, and the loop repeats. One CPU core is pegged whenever the device is awake (hours a day of reading on an e-reader): heavy battery drain and heat, and nothing recovers it short of a reboot. The same happens if the Wacom digitizer or the grabbed hall device ever re-enumerates.
- **Fix:** Handle revents & (POLLHUP|POLLERR|POLLNVAL), and read() returning -1 with errno other than EAGAIN/EINTR. For those, close the fd and compact pfd, adjusting pen_idx. If it was the hall device, clear hall_fd and hall_path. Then rescan /dev/input (see the hot-plug finding).

### KOReader patch reads the light only at startup; a QS slider change is overwritten by the next KOReader light action
- **Where:** `work/koreader/patches/1-bigme-eink.lua:76` (lens a11y-ui-release; reported low, verified medium)
- **Status:** fixed: patch 959da9d9 on the tablet 21.10 (syncFromSystem before every light read and HW action). Patch loads clean; interactive check = FEATURE-TESTS item 15
- **What:** light.level and light.warmth come from Settings.Secure once, at patch load. Changes made in the QS sliders or E-Ink Control while KOReader runs are never read back, and every later setIntensityHW or setWarmthHW pushes both channels from the stale state.
- **When it fails:** While reading, the user pulls the shade and sets cold 20 / warm 120, then nudges KOReader's warmth or a brightness gesture. The light jumps back to KOReader's start-up level and mix.
- **Fix:** Re-read b1051c_fl_cold/warm (secureInt) at the start of setIntensityHW, setWarmthHW and turnOnFrontlightHW, or on KOReader's Resume event, and update light.level and light.warmth before computing channels.

### KOReader patch: Toggle frontlight does nothing when KOReader starts while the light is off (fresh devices too)
- **Where:** `work/koreader/patches/1-bigme-eink.lua:103` (lens a11y-ui-release; reported medium, verified medium)
- **Status:** fixed: patch 959da9d9 on the tablet 21.10 (0/0 at start = off with a 40 % default level, so turnOnFrontlight proceeds). Interactive check = FEATURE-TESTS item 15
- **What:** When the E-Ink Control light is off, KeeperService publishes b1051c_fl_cold/warm = 0/0, because frontlightValues returns {0,0} when light_on is false, which is the default. The patch then starts with light.level = 0, so BasePowerD:new sets fl_intensity = frontlightIntensityHW() = 0 = fl_min. BasePowerD:turnOnFrontlight returns early on 'fl_intensity == fl_min' (generic/powerd.lua:159). The patch's own fallback in turnOnFrontlightHW (line 124, 'if light.level == 0 then … fl_intensity or 50') is therefore unreachable.
- **When it fails:** On a fresh device, or any time the light was off when KOReader started, the frontlight widget's Toggle, the 'Toggle frontlight' gesture and the menu toggle all do nothing. The user has to discover that '+' or the slider works instead. Turning the light off from KOReader also publishes 0/0, so the problem returns on the next KOReader start.
- **Fix:** When cold0+warm0 == 0, start with a remembered or default level (for example the cold/warm E-Ink Control keeps in its prefs, published under separate keys, or 40) and light.off = true. frontlightIntensityHW then returns a non-zero intensity while isFrontlightOnHW stays false, and turnOnFrontlight works.

### KOReader patch: brightness changed after a KOReader 'off' turns the light on while KOReader still thinks it is off
- **Where:** `work/koreader/patches/1-bigme-eink.lua:108` (lens a11y-ui-release; reported medium, verified medium)
- **Status:** fixed: patch 959da9d9 on the tablet 21.10 (setIntensityHW sets off = level <= 0). Interactive check = FEATURE-TESTS item 15
- **What:** turnOffFrontlightHW sets light.off = true. Only turnOnFrontlightHW clears it. setIntensityHW pushes the new level to the hardware (pushLight) but leaves light.off set, and isFrontlightOnHW returns 'level > 0 and not light.off'. The following _decideFrontlightState therefore records is_fl_on=false while the panel light is physically on.
- **When it fails:** In KOReader, turn the light off (Toggle or gesture), then open the frontlight widget and tap '+'. The widget's fl.cur is 0 (frontlightIntensity() returns 0 when off), so '+' calls setIntensity(1): the light comes on at 2/222, KOReader still reports off and shows 0. Every further '+' sets 1 again, so brightness is stuck near zero. The next Toggle 'turns on' a light that is already on, and the one after that turns it off, so the controls are out of step until the user notices.
- **Fix:** In AndroidPowerD:setIntensityHW set light.off = (intensity <= 0) (or false) before pushLight, so the HW state and isFrontlightOnHW agree.

### eink-compat installs the HWC shim on any device: no device guard in customize.sh
- **Where:** `work/module/b1051c-eink-compat/customize.sh:3` (lens a11y-ui-release; reported high, verified medium)
- **Status:** fixed: eink-compat 0.1.26 (customize.sh guard)
- **What:** customize.sh only sets permissions. post-fs-data.sh and service.sh check only that the system is a Treble/phh GSI fingerprint. The shim is magic-mounted as /vendor/lib64/hw/hwcomposer.mtk_common.so, the generic MediaTek HWC name (ro.hardware.hwcomposer=mtk_common is common on MTK vendors). It dlopens the hard-coded /vendor/lib64/hw/hwcomposer.mt6877.so and returns -ENOENT when that is missing (hwcshim.cpp:2165-2166). The vendor device name here is the generic MTK reference board 'vnd_k6877v1_64_k419', so even another MT6877 product would load the shim and its Bigme e-ink assumptions.
- **When it fails:** An XDA user with another MediaTek tablet or phone on a TrebleDroid GSI flashes the module zip. On a non-6877 vendor the composer HAL fails to open, SurfaceFlinger cannot start and the device bootloops. On another MT6877 vendor the Bigme-specific interposer runs against a different HWC or panel. Recovery needs Magisk safe mode or adb.
- **Fix:** In customize.sh, abort unless device-specific markers exist. For example, require /vendor/lib64/hw/hwcomposer.mt6877.so plus the B1051C lm3630a node /sys/devices/platform/11d20000.i2c1/i2c-1/1-0036 (or another unique Bigme sysfs/device-tree marker). Print a clear 'B1051C Pro only' message. Add the same check in post-fs-data.sh so a copied module touches its own disable file.

### Unauthenticated adb (ro.adb.secure=0) on a debuggable build: any USB host gets a root shell without the RSA prompt
- **Where:** `work/module/b1051c-eink-compat/post-fs-data.sh:6` (lens sec-system; reported medium, verified medium)
- **Status:** fixed on the tablet: module b1051c-adbsecure 0.1.0 (verified 21.10 14:27: unauthorized until allowed, then device); ROM: ro.adb.secure=1 in the image (ROM not flashed, unverified)
- **What:** Live device: ro.adb.secure=0 (from the GSI's /system_ext/etc/build.prop and /system/build.prop), ro.debuggable=1, ro.build.type=userdebug, persist.sys.usb.config=adb, verifiedbootstate=orange. adbd does not ask the user to authorize the computer, and `adb root` gives uid 0 in the permissive `su` domain (the CIL has typepermissive su). None of our module scripts override this, although post-fs-data already seeds properties with resetprop at line 6 and could.
- **When it fails:** With USB debugging on (it is on on the reference unit, and the project's own install and tooling flow uses adb root), anyone who plugs in a cable, even with the screen locked, can run `adb root; adb shell` and read or modify everything: all of /data once the user has unlocked since boot, Magisk's DB, and Wi-Fi keys. They can also install a persistent backdoor module. If the user ever runs `adb tcpip 5555`, the same unauthenticated root is open to anyone on the Wi-Fi network. Combined with FRP being disabled (separate finding), a stolen tablet is fully exposed. Rated medium because it needs physical access, or a user who opted into adb over TCP.
- **Fix:** In the release build add `resetprop -n ro.adb.secure 1` to the eink-compat post-fs-data.sh, which runs before adbd starts on a normal boot, or ship it as a module system.prop. adb then shows the RSA authorization dialog once per computer. `adb root` keeps working for the owner's tooling because ro.debuggable stays 1. Also state the debuggable/test-keys posture in the release notes and advise turning USB debugging off when not in use.

### Patched Launcher3QuickStep is tied to one GSI build, and nothing checks for that at install or boot
- **Where:** `work/module/b1051c-launcher3/customize.sh:3` (lens java-sysui-launcher; reported medium, verified medium)
- **Status:** fixed: b1051c-launcher3 0.2.4 (customize.sh refuses any ro.system.build.fingerprint other than TrebleDroid ci-20250617; post-fs-data.sh touches `disable` and logs to /data/adb/b1051c/launcher3.log when the image changed). Staged 22.10 05:5x; the APK is read through the magic mount, so the fingerprint is the check
- **What:** The module .replace's /system/system_ext/priv-app/Launcher3QuickStep with a smali-patched copy of the Launcher3 from TrebleDroid ci-20250617 (sha bf92e8d5, checked only at build time in build.sh:27). customize.sh only sets permissions. Nothing checks the running GSI's fingerprint or the stock APK hash at install or boot.
- **When it fails:** A public-alpha user flashes a newer TrebleDroid A16 build (monthly CI/QPR) and keeps or installs the InKognito modules. The old Launcher3QuickStep then runs against a newer framework and SystemUI: different SystemUI-Launcher AIDL (IOverviewProxy/ISystemUiProxy) and hidden APIs, with no oat. On a large screen the 3-button nav lives in Launcher3's taskbar, so a quickstep crash or incompatibility leaves the user without working recents/taskbar or nav buttons until Magisk safe mode or adb removes the module.
- **Fix:** In customize.sh, abort unless sha256 of the stock /system/system_ext/priv-app/Launcher3QuickStep/Launcher3QuickStep.apk (read via the real system path, not the module mount) equals bf92e8d5... and/or ro.system.build.fingerprint matches. In post-fs-data.sh, touch $MODPATH/disable when the fingerprint changes after a GSI update.

### processImage same-frame guard reads and writes BUILD-SPECIFIC struct offsets with no BuildID gate
- **Where:** `work/shim/hwcshim.cpp:914` (lens native-shim; reported high, verified medium)
- **Status:** fixed: eink-compat 0.1.31 (g_b1051c_ok gate on BuildID bdef0c8d…, read back from the live HWC; customize.sh also refuses a vendor HWC whose sha256 differs). Verified 22.10 night
- **What:** hook_processImage always reads CommitInfo+0x8b, follows OverlayPortParam+0x178 as a pointer, reads gb+0x4c/+0x50 and writes 0 to ci+0x8b. These are raw offsets taken from disassembly of the B1051C build bdef0c8d. Every other struct peek is gated on g_build_id_ok, which matches the HiBreak build (8a5cbea6) and is false on this device. This guard is not gated at all, so it runs on any vendor HWC that exports the mangled processImage symbol.
- **When it fails:** An XDA user flashes the ROM over a different Bigme stock firmware whose hwcomposer.mt6877.so has another CommitInfo/OverlayPortParam layout. customize.sh does no vendor/HWC check. On every frame where the byte at ci+0x8b is non-zero, port_fd_mappable dereferences whatever 8 bytes sit at param+0x178. If that is not a valid pointer, the composer gets SIGSEGV on every present, init respawns it, SurfaceFlinger keeps dying and the device hangs at boot. If it is a valid pointer, the shim zeroes an unrelated byte of the vendor struct while processImage runs. The author's own note asked for this gate: research/phase-d/pen/HWC-COMMIT-POLICY.md:289 says "Gate on BuildID bdef0c8d... needs its own flag". It was not implemented.
- **Fix:** Add a second constant for the B1051C build (bdef0c8d90fec30662441f364f4f06b7) and a flag g_b1051c_build_ok. Run the processImage guard (and any future CommitInfo/param peeks) only when that flag is true. Also make customize.sh compare the BuildID or sha256 of /vendor/lib64/hw/hwcomposer.mt6877.so with the known value, and either abort or install the shim in ABI-only mode (no struct peeks) when it differs.

### Commit swaps in the newest client target, not this job's, and never waits on its acquire fence
- **Where:** `work/shim/hwcshim.cpp:1036` (lens native-shim; reported medium, verified medium)
- **Status:** fixed: eink-compat 0.1.31+ (client targets kept in a ring with dup'd acquire fences; the newest signalled one is read, an older complete frame over a newer unfinished one, ≤ 50 ms wait otherwise; the ink paths use the same pick). The commit now carries the shim's own buffer, so the vendor's job/CT mismatch no longer matters. Verified 22.10 night (no tearing seen in screencaps; counters in the dump line)
- **What:** hook_mtk_commit runs on the vendor's asynchronous OverlayEngine thread, after the vendor's vsync wait, CT fence wait and 18 MB memcmp/memcpy. It replaces the commit fd with g_ct_ion_fd, which the binder thread overwrites at every setClientTarget. The fd that reaches the kernel (and the colour pipeline's source) can therefore belong to frame N+1 while the vendor fenced frame N. Nothing waits on N+1's GPU acquire fence.
- **When it fails:** During continuous rendering SF composes faster than commits leave: the Keep log shows SF at about 40 fps with present-to-ioctl taking 15 ms plus 15-20 ms of colour pipeline. In that gap SF calls setClientTarget(N+1) with an unsignalled GPU fence. The ioctl, which reads and converts the buffer synchronously, or color_apply then reads a half-rendered N+1. On Mali that means tiles of the buffer's old frame (N-2) mixed with new ones. The same frame is often dropped as "No dirty region" (470 of 483 frames in the Keep session). When that happens no later commit repairs it, and the torn picture stays on the panel until the next commit or GC16. ink_realize and the settle (lines 1562, 1357) read the same unfenced latest CT.
- **Fix:** Commit the job's own CT. On bdef, capture OverlayPortParam+0x78 in hook_processImage, which runs on the same thread for the same job after the vendor's sync_wait, behind the BuildID flag from the previous finding. Where processImage is skipped (Regal), dup the acquire fence in hook_setClientTarget into a small ring keyed by handle. Then sync_wait it (bounded timeout) in hook_mtk_commit and in the ink paths before using the buffer. Keep the hold check and ink block as they are.

### Colour pipeline processes the full 2480x1860 frame on every commit on the HWC commit thread, starting 3 threads per frame
- **Where:** `work/shim/hwcshim.cpp:1041` (lens compat-perf; reported medium, verified medium)
- **Status:** fixed: eink-compat 0.1.31–0.1.34 (row-incremental pass against a raw copy of the last converted frame, into 3 own cached ION commit buffers; persistent 4-thread pool). Measured 22.10 night: Fast scrolling converts ~620 of 1860 rows in 18 ms mean; a clock tick 43 rows. Remaining fixed cost = the uncached read of the whole client target; next step would be the vendor's dirty rects (B1051C build)
- **What:** Whenever any vivid, contrast or brightness value is non-zero (the Magazine, Comic and Video presets, Custom, or per-app presets), hook_mtk_commit calls color_apply over the vendor's commit area. That area is always full-screen, so every commit costs a 4.6 Mpx 3D-LUT pass. color_apply also creates and joins nt-1 pthreads on every commit (line 576). The pass runs synchronously inside mtk_commit, so it is added to every panel update.
- **When it fails:** With the owner's current settings (vivid 50, contrast 50, brightness 50, mode 4), logcat shows `colour: ... area=[0,0,2479,1859] 3D 12655-32055us` on every commit. Total commit time is 16.8-21.6 ms, against 6.8-9.3 ms for commits without colour. Any small animated element, such as a blinking text cursor, a loading spinner, a progress bar or a GIF, pays the full-frame cost on every frame: about 15 ms of wall time on 4 threads, roughly 60 ms of CPU. Scrolling in Fast or A2 roughly doubles per-commit latency and burns extra CPU for the whole session.
- **Fix:** Use a persistent worker pool (create the threads once and wake them with a condvar or futex). Process only the region that changed: track the setClientTarget damage per frame, and for each HWC destination buffer (identified by fd inode) process the union of the damage since that buffer was last written. Skip the pass entirely when the client target has not changed since the last processed frame.

### Vendor frames held during a pen session are thrown away (final settle, failed ink_begin, display-off cancel)
- **Where:** `work/shim/hwcshim.cpp:1449` (lens native-shim; reported medium, verified medium)
- **Status:** fixed: eink-compat 0.1.42 (held frames replayed through the normal commit path at session end and after a failed ink_begin, which now backs off 1 s; display-off: counter reset, SF presents again at the wake)
- **What:** While g_ink_session is 1, hook_mtk_commit keeps only the struct of the last vendor commit (g_ink_held_u, g_ink_held++) and returns 0. The only consumer is ink_settle_start, which reads it for mode/dither and resets the counter. In three cases held frames are never shown: (a) frames that arrive after the final settle started, because session end at line 1449 never checks g_ink_held; (b) frames held while a failed ink_begin waits, because it clears the session at line 1326 without replaying them, and it retries on every touch sample; (c) frames held when g_ink_cancel drops the session on display-off (line 1689).
- **When it fails:** (a) A handwriting app such as Nebo, or an autosave or sync indicator, redraws about 2 s after the last stroke. That is exactly ink_idle_ms, when the settle runs (up to 8 boxes, tens to hundreds of ms). That frame is swallowed. The settle commits boxes from the CT snapshot taken at settle start, and g_last_update becomes the canvas, so a later gc16_req or auto-GC16 also re-sends the stale canvas. The panel shows the pre-recognition ink until something else redraws. (b) If ink_prepare keeps failing (no usable last commit or picture, e.g. a stale fd), every pen sample (about 200 Hz) opens a hold window of up to 100 ms, and the app's own stroke frames are dropped at random. The log says "stroke left to the app", but the app's frames do not reach the panel either. (c) Write, then press power within about 2 s: the frames drawn just before display-off are dropped and the standby picture keeps the black preview strokes.
- **Fix:** Keep the hold check. At session end (inside g_commit_lock, before clearing g_ink_session), read g_ink_held under g_lock. If it is non-zero, run ink_settle_start again against the newest CT (bounded passes), or commit the newest CT full-area in g_ink_held_u's mode. Do the same replay when ink_begin fails and when the session is cancelled with held frames. Also back off ink_begin retries after a failure instead of retrying on every sample.

### Pen ink session can hold every vendor commit indefinitely (frozen panel) when the pen-up report never arrives
- **Where:** `work/shim/hwcshim.cpp:1694` (lens sec-system; reported medium, verified medium)
- **Status:** fixed: eink-compat 0.1.26 (pen-up watchdog)
- **What:** While a pen session is active, hook_mtk_commit (line 976) swallows every vendor commit. The session ends only when st.down is false and idle_ms has passed, or when the ink prop goes to 0, or on a display power change (line 1688). Three paths can leave st.down stuck at true: (1) when einkd's connection closes (line 1683, "einkd gone") only the fd is closed and st.down/st.drawing are not reset, and nothing restarts einkd (service.sh:20 starts it once with `&`); (2) einkd.c:99 silently drops a report when send() returns EAGAIN; (3) einkd ignores SYN_DROPPED (einkd.c:106-120, 199-204). If an evdev overflow loses the BTN_TOUCH 0 / ABS_PRESSURE 0 packet, pen.flags keeps INK_TOUCH with a stale pressure above 0, and every later hover report is treated as contact (hwcshim.cpp:1476).
- **When it fails:** Case A: the user is writing in a Pen-mode app and einkd is killed or crashes (lmkd under pressure, or a manual kill) while the nib is down. Case B: einkd is starved for a few tens of ms, overflows its small evdev buffer and loses the lift packet. Either way the shim stays in the session: in case B it draws phantom ink that follows the hovering pen, and when the pen leaves range the last sample still carries TOUCH. App frames, the shade and navigation are never committed, so the panel looks frozen. It recovers only on the next real pen contact, the power key (setPowerMode leads to g_ink_cancel), or a switch to a non-Pen app. In case A even a new pen contact does not help, because einkd is gone until reboot.
- **Fix:** In the shim, on "einkd gone" (line 1683) set st.down = st.drawing = false and st.up_ms = now so the normal idle settle runs. Add a watchdog: if st.down and no sample has arrived for about 300 ms (Wacom reports continuously while touching), treat it as pen-up. In einkd, on SYN_DROPPED discard events until the next SYN_REPORT, then resync with EVIOCGKEY (BTN_TOUCH, BTN_TOOL_PEN) and EVIOCGABS(ABS_PRESSURE/X/Y) before sending. Use a blocking send with a short timeout for samples where the flags changed, so a state change (lift) is never dropped. Supervise einkd: restart it from a loop in service.sh, or run it as an init service through the module.

### Pen session (and the hold on all vendor commits) ends only when a pen-up sample arrives; losing einkd mid-stroke freezes the panel
- **Where:** `work/shim/hwcshim.cpp:1694` (lens native-shim; reported medium, verified medium)
- **Status:** fixed: eink-compat 0.1.26 (pen-up watchdog)
- **What:** A settle, and so the release of the vendor hold, needs st.down == false (set only when ink_sample sees a non-touch report) or k.enable == 0. When the einkd connection closes or errors (line 1683), the shim only closes cfd. It does not reset st.down or start a settle, and nothing times out a pen-down with no samples.
- **When it fails:** The pen is on the glass in an app using Pen mode, and einkd dies or restarts, or the final BTN_TOUCH 0 / BTN_TOOL_PEN 0 SYN_REPORT is lost. einkd sends with MSG_DONTWAIT and drops on EAGAIN (einkd.c:99) while the ink thread is stuck in a long settle, prepare or ioctl. st.down stays true, g_ink_session stays 1 and every vendor commit is swallowed. Finger input still works but nothing on the panel changes, and gc16_thread skips requests while the session is set (line 1108), so the refresh tile does nothing either. Only the power button (setPowerMode leads to g_ink_cancel) or blindly switching apps (E-Ink Control clears the ink knob) recovers.
- **Fix:** Treat 'einkd gone' as a pen-up: on r == 0 or an error, set st.down = st.drawing = false and st.up_ms = now. Also add a watchdog: if st.down and no sample for more than about 500 ms (pens report at 100+ Hz while touching), force pen-up so the normal settle runs.

## Low (55)

### The icon pack and the icon RRO set mirror the owner's own installed apps and on-device renders
- **Where:** `tools/scripts/build-iconpack.py:55` (lens fresh-device; reported low, verified low)
- **Status:** open
- **What:** The pack is built from frames that RENDER_FRAMES draws from the apps installed on the owner's tablet. Its appfilter and the b1051c-icons overlays list the owner's app inventory, including Kindle, Gemini, Google Home, Keep, Docs and KOReader.
- **When it fails:** Shipped as is, the ROM shows which apps the owner uses, and ships frame PNGs rendered from those apps' icons (frame_0, frame_23). On other devices it maps icons for apps the user may not have.
- **Fix:** Rebuild the pack and the overlays from a curated, documented list on a clean device, or accept it as is and note it in CREDITS.

### einkctl build defaults to versionCode 1 / versionName 0.1.0
- **Where:** `work/app/einkctl/build.sh:14` (lens a11y-ui-release; reported low, verified low)
- **Status:** open
- **What:** --version-code "${VERSION_CODE:-1}" --version-name "${VERSION_NAME:-0.1.0}". The installed build is vc50 / 0.15.0. einkui/build.sh already removed this default for the same reason.
- **When it fails:** A release or public build run without the env vars produces 0.1.0 (vc1). It cannot be installed over vc50 (INSTALL_FAILED_VERSION_DOWNGRADE), or it ships mislabelled, and an XDA user who installs it is stuck below later updates' expectations.
- **Fix:** Require VERSION_CODE and VERSION_NAME as einkui/build.sh does (': "${VERSION_CODE:?…}"'), or derive them from one version file checked for monotonic increase.

### Builds are not reproducible or portable (classes.dex timestamp, hard-coded Homebrew paths)
- **Where:** `work/app/einkctl/build.sh:18` (lens a11y-ui-release; reported low, verified low)
- **Status:** open
- **What:** 'zip -q ../unsigned.apk classes.dex' stores the build-time mtime (seen: classes.dex dated 10-20-2026 23:03 inside einkctl-0.15.0.apk, while the aapt2 entries are 1980), so identical sources give different APK hashes. SDK, JDK and NDK paths are fixed to /opt/homebrew.
- **When it fails:** A published APK cannot be verified against the source by rebuilding, and a contributor on Linux cannot run the scripts unchanged.
- **Fix:** touch -t 198001010000 classes.dex (or zip -X with a fixed mtime) before adding it, and take SDK, BT and JAVA_HOME from environment variables with the current values as defaults.

### Signing key for the root-granted einkctl app: a single copy, auto-regenerated if missing, password 'android' hard-coded
- **Where:** `work/app/einkctl/build.sh:21` (lens a11y-ui-release; reported medium, verified low)
- **Status:** open
- **What:** einkctl, B1051cFrameworkOverlay and B1051cNavOverlay are signed with work/app/debug.keystore (alias einkctl, store and key password 'android' in three build scripts). If the file is missing, build.sh line 21 silently generates a new key. The keystore is the only copy found in the project tree, including backups/.
- **When it fails:** The alpha ships signed with this key. If the file is lost, or a fresh checkout builds, the next einkctl release gets a new certificate. Every user's update then fails with INSTALL_FAILED_UPDATE_INCOMPATIBLE and they must uninstall, losing settings, the pm-granted WRITE_SECURE_SETTINGS, the enabled accessibility service and the Magisk su grant. The weak password also leaves the keystore file as the only secret protecting updates of an app that has root.
- **Fix:** Create a dedicated release keystore with a strong password read from the environment or Keychain. Back it up offline. Make build.sh fail if the keystore is missing instead of generating one. If the key is changed after release, use apksigner key rotation (--lineage, v3.1) so installs keep updating.

### Accessibility service description understates what it captures
- **Where:** `work/app/einkctl/res/values/strings.xml:13` (lens a11y-ui-release; reported low, verified low)
- **Status:** open
- **What:** The text users approve says it 'reads only the position and color of those buttons… No other screen content is read'. The service declares canTakeScreenshot (xml/accessibility.xml:8), and NavRefresh takes a full-display screenshot (copied to an ARGB bitmap) on window changes, at most once a second. It then samples one rect, and it also reads the active app's package.
- **When it fails:** A privacy-conscious XDA user enables a service described as reading almost nothing, while it repeatedly captures whole-screen images (in memory only), including other apps' content.
- **Fix:** State plainly that it takes a screenshot of the screen to read the color of the Recents button and processes it in memory without storing it. Better: crop to the button rect right away, or sample less often, e.g. only after theme or wallpaper changes.

### publishLight called off the main thread races the keeper's own QS-light observer: link_brightness gets forced off and cold/warm values get mixed after an import
- **Where:** `work/app/einkctl/src/org/b1051c/einkctl/Eink.java:99` (lens java-einkctl; reported low, verified low)
- **Status:** open
- **What:** publishLight writes KEY_COLD and KEY_WARM as two separate Secure puts. The echo filter in setLight assumes the observer only runs after both writes, which holds only when the caller is the main thread. importSettings -> applyAll -> applyFrontlight runs on a worker thread, so the main thread can run lightFromSystemUi between the two puts. It then sees (new cold, old warm), which differs from frontlightValues, adopts it, sets link_brightness=false and saves the mixed warm value. The second callback fixes the values but not link_brightness.
- **When it fails:** The user imports settings with link_brightness=true and different light values. Right afterwards link_brightness reads false and the panel briefly shows a cold/warm mix. The restore did not restore what the file contained.
- **Fix:** Run applyAll/applyFrontlight on the main thread (post to the main thread from importSettings). Alternatively, suppress the observer while publishing (a self-write flag), or publish both values as one key.

### normMode passes unknown refresh-mode values straight to the vendor HWC; a kernel-rejected mode freezes the panel (the Hyper 183 precedent)
- **Where:** `work/app/einkctl/src/org/b1051c/einkctl/Eink.java:161` (lens java-einkctl; reported medium, verified low)
- **Status:** fixed: E-Ink Control 0.15.4 (unknown values -> Auto)
- **What:** normMode maps only -1 and the 183 family. Any other int stored as the global 'mode', as a profile 'refresh' (Profiles.java:140), or as a user preset 'refresh' (Profiles.java:169, not normalized at all) reaches modeCmd and is written to vendor.xrz.global_refresh_mode unchanged. Per CONCLUSIONS.md the kernel rejects unsupported modes ('This mode(0xNN) is unsupport') and the panel freezes. The UI cannot even show such a value (modeIndex falls back to 'Auto'), so the user cannot see what is applied.
- **When it fails:** A shared or older settings JSON imports a profile {"refresh":181} or {"refresh":184}, or a legacy GL16 value from app <=0.5. Opening that app sets a mode the kernel rejects. The panel freezes on every visit, and the profile editor shows 'Auto', which hides the cause.
- **Fix:** Make normMode return only values from MODES (plus the HD flag form); map anything else to 178 or 0. Apply it in presetInto too, and validate on import.

### bootOtherSystem() runs a synchronous su call on the UI thread (dialog click in MainActivity and in the Switch-system tile)
- **Where:** `work/app/einkctl/src/org/b1051c/einkctl/Eink.java:332` (lens java-einkctl; reported low, verified low)
- **Status:** open
- **What:** runningDsu() calls Root.run, which is documented as 'Do not call on the UI thread', and it is evaluated on the main thread before Root.async is queued. It is reached from the dialog's positive button in MainActivity.java:267 and TileRom.java:21.
- **When it fails:** If magiskd is slow, or the first root request shows the Magisk prompt (10 s timeout), the app's main thread blocks. That freezes MainActivity or the tile host, and delays the keeper's and accessibility service's main-thread work in the same process, for up to the prompt timeout.
- **Fix:** Evaluate the DSU state inside the async command (a shell 'if [ "$(getprop ro.gsid.image_running)" = 1 ]; then …') or on a worker thread.

### Shutdown screen, nav refresh button and per-app profiles fail silently without the accessibility service
- **Where:** `work/app/einkctl/src/org/b1051c/einkctl/MainActivity.java:256` (lens a11y-ui-release; reported low, verified low)
- **Status:** open
- **What:** ShutdownScreen.cover() returns null when AppProfileService is not connected (AppProfileService.java:24), and NavRefresh and the profiles depend on the same service. Only the per-app section mentions the service, and the app shows no status and no shortcut to the accessibility settings.
- **When it fails:** On a fresh install the user picks 'InKognito: color to black-and-white (animation)' and turns on the nav refresh button. Neither ever appears, and nothing tells them to enable the service.
- **Fix:** Show a status row with an 'Enable' button (Settings.ACTION_ACCESSIBILITY_SETTINGS) when the service is not enabled, and mention the dependency under the shutdown-screen and nav-button settings.

### 'Boot the other system' and the 'Switch system' tile do nothing silently on devices without the owner's DSU
- **Where:** `work/app/einkctl/src/org/b1051c/einkctl/MainActivity.java:265` (lens a11y-ui-release; reported low, verified low)
- **Status:** open
- **What:** Eink.bootOtherSystem runs 'gsi_tool enable --single-boot && reboot'. With no DSU installed, gsi_tool fails and nothing happens, with no message. The button and tile are shown to every user.
- **When it fails:** An XDA user taps 'Restart' in the confirmation dialog and the device simply does not restart.
- **Fix:** Hide or disable the button and tile unless dsuStatus() reports an installed DSU, and report the gsi_tool error in a Toast.

### The per-app list sorts with loadLabel() inside the comparator on the main thread in MainActivity.onCreate
- **Where:** `work/app/einkctl/src/org/b1051c/einkctl/MainActivity.java:349` (lens java-einkctl; reported low, verified low)
- **Status:** open
- **What:** Collections.sort calls ri.loadLabel(pm) twice per comparison (O(n log n) resource loads across packages). The list is built on the main thread in onCreate, which also runs again on every Dark-theme, Bold-text or import recreate.
- **When it fails:** On a device with 150+ Play apps, the first open after boot loads every app's resources several times. The settings screen stalls for seconds on the slow e-ink refresh path, and a tap during that time risks an input ANR.
- **Fix:** Load each label once into a list of (label, pkg) pairs, ideally on a worker thread, then sort that list and add the rows on the UI thread.

### E-Ink Control controls lack accessible names and headings; sliders are 44 dp high
- **Where:** `work/app/einkctl/src/org/b1051c/einkctl/MainActivity.java:455` (lens a11y-ui-release; reported low, verified low)
- **Status:** open
- **What:** Spinner labels (per-app editor, shutdown screen) are separate TextViews with no setLabelFor or contentDescription. SeekBars have no contentDescription. Section titles are not setAccessibilityHeading(true). Ink.styleSeekBar sets the bar height to 44 dp (Ink.java:285), below the 48 dp minimum, and has no focus indicator (background and thumb null).
- **When it fails:** With explore-by-touch, the per-app dialog reads 'Inherit, drop-down list' six times with no field name, sliders read only 'NN percent', and TalkBack users cannot jump between sections by heading.
- **Fix:** t.setLabelFor(spinnerId) / sb.setContentDescription(label) in spinner() and slider(), tv.setAccessibilityHeading(true) in section(), and sb.setMinHeight(dp 48).

### Fixed 60 dp button height clips labels at large font scales
- **Where:** `work/app/einkctl/src/org/b1051c/einkctl/MainActivity.java:608` (lens a11y-ui-release; reported low, verified low)
- **Status:** open
- **What:** button() uses LayoutParams(MATCH_PARENT, dp(60)) with 18 sp text, so two-line labels do not fit.
- **When it fails:** At 200 % font scale with DPI 400 (offered by the app), 'Save these colors as a named preset…' or 'Clear screen (full refresh)' wraps to two lines and the second line is cut off.
- **Fix:** Use WRAP_CONTENT with setMinHeight(dp(60)).

### Nav refresh button takes a full-display screenshot and an 18 MB software copy after every window-change burst to sample one glyph colour
- **Where:** `work/app/einkctl/src/org/b1051c/einkctl/NavRefresh.java:115` (lens compat-perf; reported medium, verified low)
- **Status:** open
- **What:** Every window event, whether TYPE_WINDOWS_CHANGED or TYPE_WINDOW_STATE_CHANGED, calls nav.update(). That schedules relayout(), which calls sampleTint() every time, not only when the tint is unknown or the theme changed. sampleTint takes an accessibility screenshot of the whole 1860x2480 display and hw.copy()s it into an ARGB_8888 software bitmap (18.4 MB) just to read the area of the Recents button. The wrapped hardware Bitmap is never recycled, so the graphic buffer stays alive until a GC.
- **When it fails:** Each app switch, keyboard show or hide, dialog, shade open or keyguard window at screen-off triggers, 300 ms later, a SurfaceFlinger GPU capture plus an 18 MB copy and allocation in the einkctl process. The only limits are the 1 s gap and the 300 ms debounce. Typing in an app with frequent IME or popup window changes, or switching apps, repeats this all day. The result is CPU and GPU work, memory churn (the einkctl RSS was 176 MB) and wake time that a cached tint would avoid.
- **Fix:** Resample only when needed: when the nav target rect moved, on retint() after a theme or config change, or when the top window's light-nav-bar appearance changes. Otherwise reuse the stored tint. When sampling, avoid the full-frame software copy by reading only the button rect (crop first, or copy a downscaled version), and call hw.recycle() in addition to sw.recycle().

### Accessibility service takes full-screen screenshots for a colour sample
- **Where:** `work/app/einkctl/src/org/b1051c/einkctl/NavRefresh.java:115` (lens sec-apps; reported low, verified low)
- **Status:** open
- **What:** sampleTint takes a full-display screenshot (up to about once a second during window-change bursts and four times after each theme switch), copies it into a full-size ARGB_8888 software bitmap (about 18 MB at 1860x2480) and reads only the Recents button's rectangle. The wrapped hardware bitmap is never recycled. The service also declares canRetrieveWindowContent, flagRetrieveInteractiveWindows and canTakeScreenshot. These are broad capabilities for a root-holding app, though the data stays in memory and is not stored.
- **When it fails:** Every sample captures whatever is on screen (messages, banking apps) in E-Ink Control's process memory and adds memory churn and battery cost on a power-constrained e-ink device. If the process is ever compromised, or a heap dump is taken over the open adb, screen content can be exposed.
- **Fix:** Copy only the needed rectangle: create the software bitmap from a cropped region (Bitmap.createBitmap(hw, rect...)) and recycle hw right away. Cache the tint per night mode and sample only when the uiMode or the nav window changes. Say in the a11y_desc string that the screen is captured for colour sampling, and drop canTakeScreenshot if a theme-attribute colour can replace sampling.

### NavRefresh takes a full-screen screenshot and makes an 18 MB software copy on every relayout, even when the tint is already known
- **Where:** `work/app/einkctl/src/org/b1051c/einkctl/NavRefresh.java:121` (lens java-einkctl; reported low, verified low)
- **Status:** open
- **What:** relayout() calls sampleTint() every time the nav buttons are found (each window-event burst, at most once a second). Each call is a SurfaceFlinger capture of 1860x2480 plus hw.copy(ARGB_8888) of the whole screen (about 18 MB) to read a single button rect. The wrapping hardware Bitmap is never recycled, so it waits for GC.
- **When it fails:** In normal use (app switches, dialogs, IME, title changes) the accessibility service keeps capturing and copying the screen, roughly once a second during activity. That costs extra CPU/GPU, native-memory churn and battery on a device that is meant to idle.
- **Fix:** Sample only when needed (first show, after retint(), or when the nav window or theme changed), and copy just the sampleAt rect, e.g. Bitmap.createBitmap(hw, rect...) or a PixelCopy-style crop. Call hw.recycle().

### Foreground app names written to logcat on every app switch
- **Where:** `work/app/einkctl/src/org/b1051c/einkctl/Profiles.java:241` (lens sec-apps; reported low, verified low)
- **Status:** open
- **What:** Profiles.onForeground (line 241) and Profiles.apply (line 203) log every foreground package, and KoBridgeReceiver line 29 logs the front package on each rejected request. This builds a continuous app-usage history in logcat (OWASP M6).
- **When it fails:** On this ROM, USB debugging is on and unauthenticated by default (ro.adb.secure=0, per SPEC.md known issues). Anyone with a cable, or any tool or bug report that collects logcat, gets the user's app usage timeline.
- **Fix:** Put the per-switch Log.d calls behind a debug flag (Log.isLoggable("einkctl", Log.DEBUG) or a pref that is off by default) and keep only error and state-change logs in release builds.

### Profiles.summary throws StringIndexOutOfBounds for a profile that contains only unknown fields, which crashes the settings screen on every open
- **Where:** `work/app/einkctl/src/org/b1051c/einkctl/Profiles.java:259` (lens java-einkctl; reported low, verified low)
- **Status:** open
- **What:** If o.length()>0 but none of the known fields is present, s stays empty and s.substring(0, -3) throws. MainActivity.appRow calls summary for every launchable app inside onCreate.
- **When it fails:** The user imports a profile JSON from a newer build that has a new field (e.g. {"pen":true}) or a typo'd key. Opening E-Ink Control crashes the process, including the keeper and accessibility service, every time, until the app's data is cleared.
- **Fix:** Return "Global" (or "Custom") when s is empty. Also drop unknown keys on import.

### The settings export carries device state (base_* saved Android values) and the import does not restore the cover prop
- **Where:** `work/app/einkctl/src/org/b1051c/einkctl/Profiles.java:263` (lens fresh-device; reported low, verified low)
- **Status:** partly fixed: E-Ink Control 0.15.4 (base_* no longer exported); the cover prop on import = open
- **What:** deviceState() excludes only 'state_' keys. Two problems follow:
- The base_grey, base_invert and base_hc values (the live Android values saved while a profile overrides them) are exported and imported.
- The imported cover_wake pref is never pushed to persist.b1051c.cover_wake, which only TileCover.java:19 sets.
- **When it fails:** The ROM defaults JSON is made with EXPORT while an app with a grayscale, invert or high-contrast override is in front. On a fresh device, the first app switch without an override then 'restores' the owner's saved base value: for example, color inversion turns on (Profiles.tri). Separately, an export with cover_wake=false makes the Cover tile show 'ignored' on a fresh device while the cover still sleeps and wakes.
- **Fix:** Treat base_* as device state in deviceState(), keeping it on import and excluding it from export. In importSettings/applyAll, run `setprop persist.b1051c.cover_wake` from the pref.

### With a guest or secondary user, every user switch makes ThemeSync rewrite user 0's wallpaper, keyboard theme and boot-animation flag, and kill every LatinIME process
- **Where:** `work/app/einkctl/src/org/b1051c/einkctl/ThemeSync.java:83` (lens critic; reported low, verified low)
- **Status:** open
- **What:** uiMode is global: UiModeManagerService pushes the incoming user's night mode as a global configuration change. E-Ink Control is installed only for user 0 (pm install --user 0), but its KeeperService gets that change on every user switch. ThemeSync then sets user 0's wallpaper, rewrites user 0's LatinIME prefs (path hard-coded to /data/user_de/0), and runs `kill $(pidof com.android.inputmethod.latin)`. pidof returns the IME processes of all users, so every user's keyboard is killed. It also writes the boot-animation flag for whichever user was active last.
- **When it fails:** An owner with Dark theme on switches to a guest (light by default) and back. Each switch costs a wallpaper set, an IME kill in the foreground user, a GC16 flash and a flag rewrite. The next boot animation follows the last user who was in front rather than the owner.
- **Fix:** In onConfig, ignore configuration changes while the foreground user is not the app's own user (ActivityManager.getCurrentUser() or UserManager), and kill only the IME process that runs under user 0's uid.

### Switch-system tile forks su every time the shade opens, and 'Restart into the other system' blocks the UI thread on su
- **Where:** `work/app/einkctl/src/org/b1051c/einkctl/TileRom.java:11` (lens compat-perf; reported low, verified low)
- **Status:** open
- **What:** TileRom.onStartListening starts a new Thread that runs `su -c getprop ro.gsid.image_running` every time QS starts listening. The value is a ro. property and cannot change during a boot. Eink.bootOtherSystem() (Eink.java:332) calls runningDsu(), a synchronous Root.run, from the dialog click handler on the main thread (MainActivity.java:267, TileRom.java:21).
- **When it fails:** With the tile added, every shade pull-down spawns a thread plus a Magisk su round trip. Pressing Restart blocks the einkctl main thread until su returns. If Magisk is slow or shows its grant prompt (fresh install, revoked grant), that block can exceed the 5 s input-dispatch limit and cause an ANR.
- **Fix:** Read ro.gsid.image_running once per process and cache it. Move the runningDsu() check in bootOtherSystem onto the Root executor; Root.async already serialises the work.

### 'Switch system' / 'Boot the other system' depends on the owner's DSU in /data and is a dead control on every fresh install
- **Where:** `work/app/einkctl/src/org/b1051c/einkctl/TileRom.java:17` (lens fresh-device; reported low, verified low)
- **Status:** open
- **What:** The DSU with the stock system was installed into /data/gsi by hand. A fresh flash wipes it, but the tile and the button are always offered, and the owner's QS tile list includes TileRom.
- **When it fails:** A fresh user taps 'Switch system' and confirms Restart. `gsi_tool enable --single-boot` fails, so nothing happens and no error is shown (Eink.java:331-334).
- **Fix:** Hide or disable the tile and the button unless `gsi_tool status` reports an installed DSU, and leave TileRom out of the provisioned QS layout.

### Plugin entry is an exported <service> with no permission, and its class is not a Service
- **Where:** `work/app/einkui/AndroidManifest.xml:10` (lens java-sysui-launcher; reported low, verified low)
- **Status:** open
- **What:** SystemUI only queries this component to find the plugin, but because it is exported with no permission any installed app can bindService/startService org.b1051c.einkui/.EinkOverlayPlugin. ActivityThread then instantiates EinkOverlayPlugin as a Service, which throws ClassCastException and crashes the einkui app process.
- **When it fails:** Any Play app can bind the component in a loop and repeatedly crash the einkui process (crash logs, possibly a "keeps stopping" dialog, the package marked as a bad process). SystemUI is not affected because the plugin code runs there through createPackageContext.
- **Fix:** Add android:permission="com.android.systemui.permission.PLUGIN" to the <service>. SystemUI only queries it, so discovery keeps working, and third-party binds are refused.

### einkui plugin component is exported without a permission
- **Where:** `work/app/einkui/AndroidManifest.xml:10` (lens sec-apps; reported low, verified low)
- **Status:** open
- **What:** EinkOverlayPlugin is declared as an exported <service> with no android:permission, but the class is not an android.app.Service. SystemUI only needs to discover it, through the PLUGIN_OVERLAY intent and the PLUGIN permission held by the package.
- **When it fails:** Any app can start or bind the component, which makes the org.b1051c.einkui process fail to instantiate it and crash. The plugin code itself runs inside SystemUI and is not affected, so the impact is a crash entry and log noise only.
- **Fix:** Add android:permission="com.android.systemui.permission.PLUGIN" to the <service> element, so only SystemUI (the holder) can bind or start it. Discovery through queryIntentServices is unaffected. Confirm on the device that the plugin still loads after the change.

### 120 ms write throttle with no trailing write drops the last step(s) for keyboard, D-pad and TalkBack
- **Where:** `work/app/einkui/src/org/b1051c/einkui/LightSliders.java:132` (lens java-sysui-launcher; reported low, verified low)
- **Status:** open
- **What:** onProgressChanged writes only if more than 120 ms have passed since the last write. Only onStopTrackingTouch writes the final value, and key and accessibility adjustments never call start/stop tracking.
- **When it fails:** Holding an arrow key (repeat about 50 ms) or quickly repeating TalkBack adjust gestures moves the slider to step N while the last write was N-1 or lower. The front light ends at the wrong level, then E-Ink Control's publish-back triggers syncFromSettings and the slider snaps back, which is confusing for keyboard and accessibility users.
- **Fix:** Post a delayed trailing write (e.g. h.removeCallbacks/postDelayed 150 ms with the latest value) whenever a write is skipped.

### einkui slider throttle can drop the final step for key and accessibility adjustments
- **Where:** `work/app/einkui/src/org/b1051c/einkui/LightSliders.java:132` (lens a11y-ui-release; reported low, verified low)
- **Status:** open
- **What:** Writes are throttled to one per 120 ms in onProgressChanged. The final value is written only in onStopTrackingTouch, which key and accessibility changes never call. The observer then resets the slider to the last written step.
- **When it fails:** Holding an arrow key on a keyboard (or fast TalkBack adjustments) leaves the light at an intermediate level, and the slider jumps back to it.
- **Fix:** When !tracking, write on every fromUser change, or post a trailing write of the last value 150 ms after the last change.

### Fallback SET_LIGHT broadcast has no receiver; light changes silently dropped if the Settings write fails
- **Where:** `work/app/einkui/src/org/b1051c/einkui/LightSliders.java:146` (lens java-sysui-launcher; reported low, verified low)
- **Status:** open
- **What:** If Settings.Secure.putInt fails, write() sends org.b1051c.einkctl.SET_LIGHT to org.b1051c.einkctl, but no receiver in einkctl declares or handles that action. ActionReceiver is not exported and KoBridgeReceiver filters only KO_REFRESH/KO_LIGHT. Only the first failure is logged.
- **When it fails:** If a secure-settings write ever fails (e.g. a SystemUI user context without the permission), the sliders move but the front light never changes, with one warning in the log.
- **Fix:** Remove the dead fallback, or route it to an action einkctl actually handles (behind a signature permission), and show feedback to the user when it fails.

### LightSliders fallback broadcast SET_LIGHT has no receiver
- **Where:** `work/app/einkui/src/org/b1051c/einkui/LightSliders.java:146` (lens compat-perf; reported low, verified low)
- **Status:** open
- **What:** When Settings.Secure.putInt fails, the plugin sends org.b1051c.einkctl.SET_LIGHT to the einkctl package. No component in einkctl handles that action: KoBridgeReceiver accepts only KO_REFRESH and KO_LIGHT, and ActionReceiver is not exported and has no such action. The fallback is therefore dead.
- **When it fails:** If the secure-settings write ever fails inside SystemUI, for example on a future build or a different SystemUI, the QS light sliders silently stop controlling the light. The broadcast is dropped without any error.
- **Fix:** Remove the fallback, or point it at an existing handler such as KO_LIGHT with both cold and warm values. Log loudly when the write fails.

### einkui fallback broadcast SET_LIGHT has no receiver
- **Where:** `work/app/einkui/src/org/b1051c/einkui/LightSliders.java:146` (lens a11y-ui-release; reported low, verified low)
- **Status:** open
- **What:** When Settings.Secure.putInt fails, the slider sends org.b1051c.einkctl.SET_LIGHT to einkctl. einkctl has no receiver for that action (KoBridgeReceiver handles only KO_REFRESH/KO_LIGHT, and ActionReceiver is not exported and has no SET_LIGHT).
- **When it fails:** If the secure-settings write is ever refused (e.g. a future SystemUI permission change), both QS light sliders stop working while the log claims a broadcast fallback.
- **Fix:** Remove the fallback, or point it at KO_LIGHT with both cold and warm extras (KoBridgeReceiver needs both), or add a SET_LIGHT action to KoBridgeReceiver.

### einkui fallback broadcast can never reach E-Ink Control
- **Where:** `work/app/einkui/src/org/b1051c/einkui/LightSliders.java:146` (lens sec-apps; reported low, verified low)
- **Status:** open
- **What:** When the Secure settings write fails, the fallback sends an implicit broadcast (action plus setPackage) to org.b1051c.einkctl.SET_LIGHT. ActionReceiver is exported=false, has no intent-filter, and SystemUI is a different uid, so the broadcast is never delivered. It also carries only one of cold/warm, while SET_LIGHT requires both.
- **When it fails:** If WRITE_SECURE_SETTINGS were ever missing from SystemUI, for example on another GSI, the QS light sliders would silently do nothing.
- **Fix:** Remove the dead fallback and keep the Settings.Secure channel, which SystemUI is always allowed to write. Do not make ActionReceiver exported to make the fallback work, because it carries root-backed maintenance actions.

### SystemUI plugin walks the whole shade tree every frame, with reflection that allocates strings and two Settings reads per frame
- **Where:** `work/app/einkui/src/org/b1051c/einkui/ShadeStyler.java:214` (lens compat-perf; reported low, verified low)
- **Status:** open
- **What:** onPreDraw runs on every frame of the NotificationShadeWindowView. It does 4 findViewById searches, a full recursive walk with class-name endsWith checks on every view, and about 5 Refl lookups per QS tile and 2 per notification. Each lookup builds a new `c.getName()+"#"+name` key String (Refl.java:15). On top of that, debug() reads Settings.Secure b1051c_shade_debug twice per frame (lines 214 and 216).
- **When it fails:** While the shade is dragged or animating, or the lock screen redraws, SystemUI's main thread does this extra work and allocates hundreds of short-lived Strings per frame, which adds GC pressure and frame time in SystemUI. After any secure setting changes (for example the light sliders writing b1051c_fl_*), the next debug() read also becomes a binder call on SystemUI's UI thread.
- **Fix:** Cache Fields in an identity map keyed by Class, or by pre-resolved per-class field sets, without building String keys. Read the debug flag in readPrefs() through the existing ContentObserver. Restyle only when gen changes or the hierarchy changes (OnGlobalLayout or child add/remove), not on every pre-draw.

### Notification restyling also hits app-owned custom views
- **Where:** `work/app/einkui/src/org/b1051c/einkui/ShadeStyler.java:417` (lens java-sysui-launcher; reported low, verified low)
- **Status:** open
- **What:** Inside ExpandableNotificationRow, every view with android:id/icon gets the grey+1.8x-contrast RenderEffect. Apps often reuse @android:id/icon in custom RemoteViews for album art or photos. Every android.widget.Button, including CompoundButton subclasses (CheckBox, Switch, RadioButton allowed in RemoteViews since API 31), is replaced with a pill background.
- **When it fails:** A music player or messenger with a custom notification layout that uses @android:id/icon for a color image shows it desaturated and contrast-crushed on the Kaleido panel. A custom notification with a Switch or CheckBox gets a pill background behind the control. CallStyle Answer/Decline lose their green/red emphasis.
- **Fix:** Limit the icon effect to the framework header's small icon (check the parent id android:id/notification_header or the CachingIconView class), and skip CompoundButton and views under the app's custom content container (android:id/notification_main_column custom views).

### Negative grid_visualization_vertical_cell_spacing also affects Launcher3 when it is the home app
- **Where:** `work/app/launcheroverlay/res/values/dimens.xml:8` (lens java-sysui-launcher; reported low, verified low)
- **Status:** open
- **What:** -72.2dp feeds DeviceProfile.gridVisualizationPaddingY. getTaskbarOffsetY() is used by TaskbarViewController, NavbarButtonsViewController, LauncherTaskbarUIController, RecentsHitboxExtender and Snackbar, and CellLayout's grid visualization uses min(..., padding). The RRO is enabled (cmd overlay list: [x] org.b1051c.overlay.launcher) whether or not Lawnchair is installed or set as home.
- **When it fails:** A user without Lawnchair, or one who switches the default home back to Launcher3: on Launcher3's home the taskbar-to-hotseat icon alignment and the nav-button lift use an offset near 0 instead of about 170 px, so icons jump or misalign with the hotseat during home transitions. Launcher3's edit-mode grid preview draws with negative insets.
- **Fix:** Enable the overlay only when a third-party home is the default (E-Ink Control could toggle it on role change), or drop it and rely on HomeBg, which already handles the 3P-home case.

### Nav-button PNGs are fixed-pixel (nodpi) and get cropped at lower DPI set from E-Ink Control
- **Where:** `work/app/navoverlay/AndroidManifest.xml:5` (lens java-sysui-launcher; reported medium, verified low)
- **Status:** open
- **What:** back/home/recents are 78x78 px and all-apps is 82x82 px in drawable-nodpi. The glyphs fill almost the whole canvas (alpha bbox e.g. back 8..77 x 0..76). Launcher3's taskbar_nav_button is a 44dp ImageView with scaleType CENTER. At the default 300 dpi the button is 82.5 px, so they fit. E-Ink Control's own DPI slider allows 100-500 dpi (Eink.java:71).
- **When it fails:** The user lowers DPI in E-Ink Control below about 280 (common on a 10.3" reader to fit more text). At 250 dpi the button is 69 px and the 78 px glyph loses about 4-5 px on each side. At 200 dpi the button is 55 px and about 11 px per side is lost, so back/home/recents/all-apps look visibly cut off or overflow the button. At high DPI they become small (25dp at 500 dpi) next to the stock 20-dp-per-density icons. Landscape behaves the same.
- **Fix:** Ship density-bucketed PNGs (e.g. drawable-xhdpi, scaled by the system) or wrap them in <bitmap>/<inset> XML that scales to the view. Or switch the button to fitCenter in the overlay, though the layout is in Launcher3 code, so density-scaled assets are the practical fix.

### Nav PNGs drop autoMirrored, so the back and all-apps glyphs point the wrong way in RTL
- **Where:** `work/app/navoverlay/res/drawable-nodpi/ic_sysbar_back.png:1` (lens java-sysui-launcher; reported low, verified low)
- **Status:** open
- **What:** Stock Launcher3 ic_sysbar_back.xml and ic_taskbar_all_apps_search_button.xml are vectors with android:autoMirrored=true. The overlay replaces them with plain PNGs, which cannot carry autoMirrored, while Launcher3's nav layout does handle RTL (Utilities.isRtl in AbstractNavButtonLayoutter/NavbarButtonsViewController).
- **When it fails:** In Hebrew or Arabic the button order mirrors, but the spray back arrow and all-apps glyph keep their LTR orientation, unlike stock.
- **Fix:** Put the PNGs under another name and override ic_sysbar_back / ic_taskbar_all_apps_search_button with <bitmap android:src="@drawable/..._png" android:autoMirrored="true"/> XML in the overlay.

### Charger-mode diagnostics run on every user's off-mode charge
- **Where:** `work/charger/b1051c-charger.rc:15` (lens native-einkd-scripts; reported low, verified low)
- **Status:** open
- **What:** Every charger boot copies /proc/self/mounts and, 12 s later, runs a shell that writes dmesg excerpts and directory listings to /metadata/b1051c/. This is development diagnostics in the release ramdisk, and sepolicy.rule still carries the 'allow charger tmpfs file' rule from the abandoned bind-mount approach.
- **When it fails:** There is no functional harm today. It is extra writes to the small /metadata partition on every charge and debug output in a public build, and it is easy to forget when the charger path changes.
- **Fix:** Drop the 'on charger' block and the diag script from the public boot image, or gate them on a flag file in /metadata. Remove the now-unused charger tmpfs rule.

### Module metadata hygiene: author first name, no updateJson, US-spelling slip
- **Where:** `work/icons-bigme/module/b1051c-icons/module.prop:6` (lens a11y-ui-release; reported low, verified low)
- **Status:** open
- **What:** Every module.prop has author=matanmi12. stage_public.py:131 rewrites this to matanmi12 for the repo, but zips built from work/module keep it. No module has updateJson, so alpha users get no update notice in Magisk. The b1051c-icons description, shown in the Magisk app, says 'main colour set', against the owner's US-spelling rule.
- **When it fails:** Release zips show a different identity than the repo, users stay on stale alpha builds, and the Magisk list shows British spelling.
- **Fix:** Set author to the public handle in the source module.prop files, add an updateJson that points at GitHub release JSON, and change 'colour' to 'color'.

### HomeBg decides "home" by package name: transparent nav row over Lawnchair's own non-home tasks, and PiP flips it
- **Where:** `work/launcher3patch/src/org/b1051c/l3/HomeBg.java:145` (lens java-sysui-launcher; reported low, verified low)
- **Status:** open
- **What:** isHome() treats any task whose top/base activity is in the default-home package as home. Lawnchair's PreferenceActivity, WidgetPickerActivity, AddItemActivity and SettingsActivity use the default task affinity (app.lawnchair), while LawnchairLauncher has affinity "", so they run as separate non-home tasks and still count as home. onTaskStackChanged also re-reads ActivityManagerWrapper.getRunningTask(), which can return a pinned (PiP) task over home. Each refresh makes two binder calls (getTasks + resolveActivity) on Launcher3's main thread.
- **When it fails:** The user opens Lawnchair > Home settings or the widget picker. The taskbar background turns transparent and the 3-button row draws straight over the edge-to-edge settings list. Separately, with a PiP window on the home screen, onTaskMovedToFront(home) makes the row transparent and the next onTaskStackChanged (top = PiP task) makes it opaque again, so it flips with each stack event.
- **Fix:** Use activity type instead of package: info.configuration.windowConfiguration.getActivityType() == ACTIVITY_TYPE_HOME, or TopTaskTracker.INSTANCE.get(ctx).getCachedTopTask(true, displayId).isHomeTask() as FallbackTaskbarUIController does. Skip WINDOWING_MODE_PINNED, and cache the home package (refresh it on ACTION_PACKAGE_CHANGED / role change) instead of calling resolveActivity on every stack change.

### Informational: the ROM ships private and proprietary content whose module descriptions say 'never publish'
- **Where:** `work/module/b1051c-bootanim/module.prop:6` (lens fresh-device; reported low, verified low)
- **Status:** open
- **What:** The owner approved including this content in the ROM (decision 20.10 23:45), so this is reported for information only:
- the owner's artwork: boot animation, shutdown frames, spray-paint nav glyphs (B1051cNavOverlay, assets/nav/refresh.png), the cat wallpapers, the logo partition
- Bigme proprietary content: the stock icons in b1051c-icons and the iconpack, and the g_c001/g_c002 LUTs compiled into the shim from lut_data.h

The module descriptions still say 'private, never publish' (bootanim module.prop:6, icons module.prop:6), and navoverlay/AndroidManifest.xml says the same.
- **When it fails:** The published alpha contradicts its own module descriptions, and third-party (Bigme) assets ship without attribution or a license note.
- **Fix:** Update the module descriptions and list the Bigme assets and the owner's artwork in CREDITS/LICENSES for the release.

### The owner's first name ships as the author of all six live Magisk modules
- **Where:** `work/module/b1051c-eink-compat/module.prop:5` (lens fresh-device; reported medium, verified low)
- **Status:** open
- **What:** The author= line in every live module.prop holds the owner's first name: eink-compat, launcher3, bootanim, fonts, icons and gapps (b1051c-gapps/module.prop:5). The owner's own public-repo redaction (tools/scripts/stage_public.py:131) rewrites this exact line to the public handle, so by the owner's rule it counts as a personal identifier.
- **When it fails:** The ROM payload copies the module trees as they are into system.img. Every XDA user then sees the owner's first name in Magisk's module list, and anyone can read it in the published image. The plan's pre-package scan would catch it only if it runs the stage_public patterns over the payload.
- **Fix:** Change author= to the public handle in all six module.prop sources (and the gapps module), rebuild the zips, and run the stage_public.py identifier scan over the final payload and images before packaging.

### `service check SurfaceFlinger | grep -q found` also matches 'not found'
- **Where:** `work/module/b1051c-eink-compat/post-fs-data.sh:35` (lens native-einkd-scripts; reported low, verified low)
- **Status:** open
- **What:** `service check` prints 'Service SurfaceFlinger: found' or '...: not found', and grep -q found matches both. The registration check does nothing (also service.sh:38). The loops stay correct only because `service call` returns 10 when the service does not exist.
- **When it fails:** Correctness depends by accident on the exit code of `service call`. Any change to that, such as wrapping it or adding `|| true`, would make the boot guard record `last=$p` without 1008 being applied. The new SF would keep DEVICE layers (black boot animation) until service.sh's loop, which starts at boot_completed+15 s.
- **Fix:** Use `grep -q ': found'`, or drop the check and rely on `service call`'s exit status.

### Stale or redundant sepolicy.rule lines widen init/charger permissions with no remaining user
- **Where:** `work/module/b1051c-eink-compat/sepolicy.rule:5` (lens sec-system; reported low, verified low)
- **Status:** fixed: eink-compat 0.1.30 (stale charger bind-mount lines dropped, the rest in magiskpolicy syntax; 0 avc after reboot, 21.10)
- **What:** Lines 5-7 (`allow init system_file file mounton`, `allow init magisk_file file {... mounton}`, `allow charger tmpfs file {read open getattr map}`) were for the charger bind mounts. The charger rc in the live boot_b ramdisk (identical to work/charger/b1051c-charger.rc) no longer mounts anything; it only sets ro.charger.no_ui and runs the diag script. Line 5 still lets init mount over any system_file. Line 8 is redundant, because plat policy already has `allow domain self unix_stream_socket {create bind listen accept ...}`. Lines 1-4 are the known type:class syntax problem and are not repeated here.
- **When it fails:** There is no direct exploit. These are least-privilege leftovers that make the shipped policy delta harder to audit, and line 5 lets any future init-level mistake shadow arbitrary system files.
- **Fix:** Remove lines 5-8, or keep only what a current component needs, and re-test an off-mode charge plus a Pen-mode session (the ink socket works without line 8).

### einkd is never supervised or restarted; leftover state after it exits
- **Where:** `work/module/b1051c-eink-compat/service.sh:20` (lens native-einkd-scripts; reported low, verified low)
- **Status:** open
- **What:** einkd is started once with `&`. It exits on a non-EINTR poll() error (einkd.c:194) and has no SIGTERM handler. If it dies it is not restarted until reboot. A raised vendor.xrz.check_commit=1 stays set, the cover grab silently ends while the QS tile still says 'ignored', and a stroke in progress leaves the shim session stuck (see the SYN_DROPPED finding).
- **When it fails:** einkd is killed (kernel OOM under memory pressure, a manual kill while debugging) during a page-turn burst. check_commit stays 1, so the shim counts every big update (video, animations) as a page turn and flashes GC16 every N of them. Closing the cover sleeps the device although the tile says 'Cover ignored'. Pen mode has no fast ink until reboot.
- **Fix:** Run einkd in a respawn loop from service.sh (`while :; do "$MODDIR/bin/einkd"; sleep 5; done &`) and have einkd catch SIGTERM/SIGINT to reset check_commit to 0 before exiting.

### Gaps in 1008 re-application: the boot guard is capped at about 4 min and service.sh starts its loop only after the 15 s watchdog sleep
- **Where:** `work/module/b1051c-eink-compat/service.sh:24` (lens native-einkd-scripts; reported low, verified low)
- **Status:** open
- **What:** The post-fs-data guard stops after 1200 iterations (about 4-5 min incl. command time) even if boot has not completed, and then clears boot_damage. service.sh's pid/DEVICE loop starts only after `sleep 15` plus the watchdog. After the watchdog's own ctl.restart, the first iteration often sees no SF pid and waits another 10 s.
- **When it fails:** Case 1: a slow first boot after installing gapps (GmsCore dexopt) passes about 4 min, and SF or the composer restarts after that. Case 2: the watchdog restarts SF and pidof is still empty on the first loop pass. In both cases the restarted framework's boot animation is a DEVICE layer and does not reach the panel for 10-25 s or more, and the panel looks hung.
- **Fix:** Run the watchdog in its own background subshell so the 1008 loop starts at boot_completed. Bound the post-fs-data guard by boot_completed only, or hand over explicitly to service.sh. Poll at 1 s for about 60 s after a restart.

### Watchdog loop runs dumpsys SurfaceFlinger plus pidof every 10 s forever
- **Where:** `work/module/b1051c-eink-compat/service.sh:49` (lens native-einkd-scripts; reported low, verified low)
- **Status:** open
- **What:** Every 10 s while awake: pidof scans /proc, then dumpsys SurfaceFlinger --hwclayers (a binder dump into SF) piped to grep. That is several forks and SF dump work around the clock, including with the screen off but the CPU awake.
- **When it fails:** There is a small but continuous CPU and wakeup cost: about 8,600 dumpsys calls per awake day on a device whose main selling point is low power. It also adds work to the SF main thread every 10 s.
- **Fix:** Back off to 30-60 s when the display is off (check via the shim prop or dumpsys power once), or re-check only after an SF pid change or a composer restart (watch init.svc_debug_pid.* or the composer pid).

### service.sh guard runs pidof plus `dumpsys SurfaceFlinger --hwclayers | grep` every 10 s for the life of the device, screen on or off
- **Where:** `work/module/b1051c-eink-compat/service.sh:49` (lens compat-perf; reported low, verified low)
- **Status:** open
- **What:** The endless loop at lines 44-53 forks pidof, dumpsys and grep, and makes SurfaceFlinger produce an HWC layer dump, every 10 s while the system is awake, including when the display is off. This happens even though the shim already sees every layer's composition type for free.
- **When it fails:** During an evening of reading with the screen on (about 3 h), the guard makes about 1,000 SurfaceFlinger dump calls and about 3,000 process spawns that serve no purpose while the screen is off or static. It keeps going while the device is awake with the screen off, for example on Wi-Fi or while charging. The cost is a small but constant amount of extra CPU wake time.
- **Fix:** Detect DEVICE layers in the shim instead: hook_getChangedCompositionTypes / validateDisplay already track comp types. On detection it can raise a property, and the script then only waits on the surfaceflinger pid change. Alternatively, lengthen the interval sharply, for example to 60 s, or skip the dumpsys while the display is off.

### The deferred module install in stage 2 can never succeed: after boot completion /debug_ramdisk/magisk is TrebleDroid's phh-su, then setup is reset and the device reboots with no layer and no error
- **Where:** `work/rom/payload-src/provision-stage2.sh:31` (lens critic; reported medium, verified low)
- **Status:** fixed in the ROM: binary fallback + no setup reset without modules (unverified: the ROM has not been flashed)
- **What:** The fallback for 'Magisk daemon not reachable at zygote-start' runs stage 1 again after sys.boot_completed with MAGISK=/debug_ramdisk/magisk. By then no eink-compat stub is mounted, and sudaemon (class main, started after zygote-start) has bind-mounted phh-su over that path, as STATUS.md records. The `--path` probe fails for 30 s and stage 1 marks itself 'deferred' again and touches stage1.done. Stage 2 then sees stage1.done, resets user_setup_complete and device_provisioned to 0, and reboots. On the next boot the deferred block is skipped (stage1.deferred.done exists) and FAILED stays 0.
- **When it fails:** Precondition: Magisk's daemon was unreachable within 30 s at zygote-start on boot 1 (rare, but this is the designed fallback). The user is pulled out of setup by an unexpected reboot with setup reset. The device then finishes 'done' with no modules: no shim (unusable panel), no gapps, no fonts or icons. No 'Setup had errors' notification is posted.
- **Fix:** In the deferred path use /data/adb/magisk/magisk (copied by stage 1 and not covered by phh-su), or the system-image phh-su neutralisation from the critical finding. Only reset setup and reboot if modules_update really contains the modules. Set FAILED=1 (and post the notification) when the deferred install did not install anything.

### GOT pages of the vendor HWC are left writable after hooking (RELRO undone)
- **Where:** `work/shim/hwcshim.cpp:765` (lens native-shim; reported low, verified low)
- **Status:** open
- **What:** got_cb mprotect()s each GOT page to PROT_READ|PROT_WRITE and never restores PROT_READ, so the vendor library's RELRO protection stays off for the life of the privileged composer process.
- **When it fails:** Any memory-corruption bug in the composer, which parses buffers and metadata from SurfaceFlinger, can overwrite GOT entries of hwcomposer.mt6877.so to redirect control flow, which RELRO normally prevents. This is defence-in-depth hygiene only.
- **Fix:** After patching, mprotect each touched page back to PROT_READ (collect page addresses during the scan and restore once at the end).

### Hold handshake between hook_mtk_commit and ink_begin has a check-then-increment race and a 100 ms cap
- **Where:** `work/shim/hwcshim.cpp:980` (lens native-shim; reported low, verified low)
- **Status:** open
- **What:** hook_mtk_commit checks g_ink_session first and increments g_ink_inflight afterwards. ink_begin sets the session and then reads inflight. That is not a Dekker pattern (it needs increment-then-check with seq_cst on both sides). A vendor commit can pass the check, ink_begin can see inflight == 0, and the commit then goes out in full mid-session. Separately, ink_begin stops waiting after 100 ms even when a commit is still in flight. The same check-then-act gap exists in gc16_thread (line 1108 vs recommit_gc16).
- **When it fails:** A vendor commit passes the session check, then spends 15-35 ms in frame_change_pct or color_apply (or longer if build_tables runs after a colour-slider change). Meanwhile the pen touches down and the ink session starts. That commit's full-screen frame, which has no stroke yet, is committed after the first ink boxes and erases the preview of the first stroke until the settle. A gc16 request that fires at the same moment can likewise flash a GC16 of the old frame over fresh ink.
- **Fix:** In hook_mtk_commit, do __atomic_fetch_add(&g_ink_inflight, 1, SEQ_CST) first, then load g_ink_session with SEQ_CST. If it is set, decrement and take the existing hold path. Use SEQ_CST for the store and load in ink_begin. Keep the hold block. In gc16_thread, re-check g_ink_session inside g_commit_lock in recommit_gc16.

### recommit_gc16 checks the fd size at 1 byte per pixel and trusts fd numbers owned by other code
- **Where:** `work/shim/hwcshim.cpp:1078` (lens native-shim; reported low, verified low)
- **Status:** open
- **What:** The GC16 re-send checks lseek(fd) >= stride*height, but RGBA commits (format != 0) are read by the kernel at 4 B/px. g_last_update.fd, g_ct_ion_fd and g_last_ct_fd are bare fd numbers owned by the composer/gralloc cache. They are never dup()'d, and they are used later from other threads (gc16 up to 2.5 s later, on request on a static screen, and in the ink paths).
- **When it fails:** SF restarts, or the CT slot is replaced, while the screen is static. The cached fd number is closed and reused by another dma-buf of 4.6 to 18.4 MB (for example an app window). A gc16_req bump then sends that buffer to EINK_UPDATE with stride 2480 x height 1860 x 4 B: the wrong picture at best, and the kernel reading past the dma-buf end depending on the driver's checks.
- **Fix:** Require size >= stride*height*(format ? 4 : 1). dup() the fd when recording g_last_update (close the previous dup under g_lock) so the GC16 and ink paths always refer to the same buffer that was committed. Or record (st_dev, st_ino) and verify with fstat before re-sending.

### Helper threads poll for the life of the composer process (gc16 at 20 Hz, ink at 4 Hz with 14 property reads), even on a static page or with the display off
- **Where:** `work/shim/hwcshim.cpp:1099` (lens native-shim; reported medium, verified low)
- **Status:** open
- **What:** gc16_thread wakes every 50 ms to read gc16_req, whether or not anything is pending. ink_thread wakes every 250 ms (20 ms during a session) and re-reads 14 knobs even when the pen layer is disabled. Together that is about 24 timer wakeups per second in the composer while the CPU is awake.
- **When it fails:** The main use case is reading a static page with the screen on. The SoC would otherwise sit in deep idle for seconds between page turns, but these timers wake it about 24 times a second indefinitely, which costs measurable idle battery on an e-reader. The magnitude has not been measured.
- **Fix:** Have gc16_thread block on __system_property_wait() for the gc16_req prop_info (API 26+), plus a condvar that hook_mtk_commit signals for the auto-N request. Use a timed wait only while req_ms is pending. In ink_thread use poll(-1) when no session is active and ink is disabled, and refresh knobs on a property-serial change instead of every 250 ms.

### HWC shim helper threads poll forever: gc16 thread wakes 20 times a second, ink thread 4 times a second, with the display static or off
- **Where:** `work/shim/hwcshim.cpp:1099` (lens compat-perf; reported medium, verified low)
- **Status:** open
- **What:** gc16_thread runs usleep(50 ms) plus a property read in an endless loop, even when no full-refresh request is pending. ink_thread polls with a 250 ms timeout and re-reads 14 properties every 250 ms (lines 1652-1654) even when Pen mode is off and einkd is not connected. Neither thread looks at the display power mode. This keeps a CPU out of deep idle while the panel shows a static page, which is the normal state of an e-reader.
- **When it fails:** The user reads one page for 30 minutes with the screen on and nothing being committed. The composer still wakes about 43,000 times (gc16 thread) plus about 7,000 times (ink thread). Measured on the device: TID 1416 (nanosleep) had 43,595 voluntary context switches and 3.43 s of CPU at an uptime of 2,200 s, about 19.8 wakeups per second; TID 1417 (poll) had 14,050. The same polling runs while the screen is off whenever the system is awake (Wi-Fi activity, charging, adb). Polling stops only during kernel suspend.
- **Fix:** gc16: block in __system_property_wait() on the gc16_req prop_info (bionic API 26+) with an infinite timeout while nothing is pending. The auto-N path should signal a condvar or eventfd from hook_mtk_commit. Use timed waits only while a request waits for the 350 ms quiet period. ink: when ink is 0 and no client is connected, block in poll() with timeout -1 on the listening socket. Re-read the knobs when einkd connects or through __system_property_wait on vendor.debug.hwc.shim.ink. Skip both threads entirely while setPowerMode != ON.

### No range check on pen-layer and GC16 knobs: a negative ink_idle_ms holds vendor commits forever
- **Where:** `work/shim/hwcshim.cpp:1287` (lens native-shim; reported low, verified low)
- **Status:** open
- **What:** Knob values are cast straight to uint64_t in comparisons. ink_idle_ms = -1 makes `ms - st.up_ms >= (uint64_t)-1` never true, so a started session never settles while ink=1. Similar problems: a negative ink_real_every_ms never realizes; a huge ink_wmax or start_px overflows int math and makes every sample paint a whole-screen segment (4.6 Mpx of float work per Wacom report); negative gc16_quiet_ms always falls through to the 2.5 s timeout.
- **When it fails:** Someone debugging, or a future app setting, writes vendor.debug.hwc.shim.ink_idle_ms=-1 (or any value of 2^31 ms or more). The first pen stroke in a Pen-mode app holds every vendor commit until the app changes or the display goes off, so the panel looks frozen. These knobs are root-only today (E-Ink Control writes only ink, gc16_req, commit_use_ct, log, boot_damage), hence low.
- **Fix:** Clamp in ink_knobs() and gc16_thread: idle_ms 100..60000, real_every_ms 20..5000, real_lag_ms 0..5000, wmin/wmax 1..64, start_px 0..200, real_gap_px 0..2480, quiet_ms 50..5000. Reject values for ink_mode and real_mode outside a known set.

### @b1051c_ink peers are not authenticated in either direction; the shim binds once and never retries
- **Where:** `work/shim/hwcshim.cpp:1638` (lens sec-system; reported low, verified low)
- **Status:** fixed: eink-compat 0.1.42 (shim: root-only peer via SO_PEERCRED, bind retried every 5 s, pressure clamped to INK_PMAX; einkd: composer peer check)
- **What:** In the static CIL, only the composer itself, phhsu_daemon (permissive, connectto on all domains), su, magisk (unconfined) and the permissive tkcore/ueventd domains may connect to a hal_graphics_composer_default socket. Untrusted apps cannot connect to the shim. The design still trusts the abstract name instead of the peer. The shim accepts any connecting peer and closes the current one (lines 1657-1658), with no SO_PEERCRED check. einkd connects to whoever owns @b1051c_ink (einkd.c:91) and streams raw pen x/y/pressure, with no check that the peer is the composer. The shim also tries bind() once and gives up for the composer's lifetime (lines 1638-1641). Because `allow domain self unix_stream_socket {bind listen accept}` lets any app bind an abstract name, an app that grabs @b1051c_ink while the composer is down takes the name. Pressure is also not clamped (line 1498): p up to INT32_MAX gives a radius of about 786k px, so every sample paints and commits the whole 2480x1860 canvas.
- **When it fails:** After a composer crash (one has happened, on 2026-10-18), a background app that keeps calling bind("\0b1051c_ink") can win the name before the restarted composer's ink thread binds. The shim then logs "pen ink layer off" and Pen mode stays dead until reboot. While the squatter is alive, einkd (unconfined, so it may connect to untrusted_app sockets) sends it the user's raw handwriting stream. In practice the composer's `onrestart restart surfaceflinger` restarts the framework and kills the app soon after, so the leak window is short and the main effect is losing the feature. A root-level peer can already do anything, so the lack of a peer check matters only for squatting.
- **Fix:** Shim: after accept4, use getsockopt(SO_PEERCRED) and accept only uid 0; optionally check SO_PEERSEC == u:r:magisk:s0. Keep the existing connection rather than letting the newest one win. Retry bind() every few seconds instead of returning from the thread. einkd: after connect, require SO_PEERCRED uid == 1000 (AID_SYSTEM, the composer's user) or SO_PEERSEC == u:r:hal_graphics_composer_default:s0, otherwise close and retry. Clamp s.p to [0, INK_PMAX] in ink_sample.

### Pen-mode canvas (18 MB ION + 18 MB malloc) stays pinned in the composer forever after first use
- **Where:** `work/shim/hwcshim.cpp:1664` (lens compat-perf; reported low, verified low)
- **Status:** open
- **What:** The first time vendor.debug.hwc.shim.ink becomes 1, ink_thread allocates a 2480x1860 RGBA ION canvas plus an equal-size malloc'd raw copy. Nothing ever frees them when Pen mode is turned off or the display goes off, and ink_flush keeps running on st.px every loop.
- **When it fails:** The user opens one app with the Pen profile once (logcat on the device: `pen ink layer: canvas 2480x1860 RGBA, dma-buf fd 258` at 23:23:57). From then until the composer restarts, about 36 MB stays reserved in the vendor composer process, even though the user never uses the pen again.
- **Fix:** Free the canvas and raw buffer (munmap, close, free) when ink has been 0 for some seconds, or on setPowerMode OFF, and allocate them again on the next enable.

## Refuted (20)

- `work/gapps/module/b1051c-gapps/post-fs-data.sh:10` — Factory Reset Protection is disabled for every install (ro.frp.pst deleted) (lens sec-system)
- `work/shim/hwcshim.cpp:565` — dma-buf map cache can munmap the source mapping that color_apply is about to read (use after munmap, composer SIGSEGV) (lens native-shim)
- `work/shim/hwcshim.cpp:2025` — Vendor refresh-mode function called while holding g_lock (possible lock inversion with the OverlayEngine thread) (lens native-shim)
- `work/shim/hwcshim.cpp:1493` — Pen sessions are not gated on the display being ON (lens native-shim)
- `work/app/einkctl/src/org/b1051c/einkctl/NavRefresh.java:130` — NavRefresh can re-show the refresh button after it was hidden: the screenshot callback calls show() with a stale target (lens java-einkctl)
- `work/app/navoverlay/AndroidManifest.xml:6` — Owner-private spray artwork ships inside the b1051c-launcher3 module (lens java-sysui-launcher)
- `work/app/einkui/src/org/b1051c/einkui/ShadeStyler.java:212` — One reflection mismatch takes down the whole frame, front-light sliders included (lens java-sysui-launcher)
- `work/app/einkui/src/org/b1051c/einkui/EinkOverlayPlugin.java:42` — Downgrade or rollback of the plugin can leave no styler running (lens java-sysui-launcher)
- `work/app/einkctl/build.sh:12` — Release artifacts embed content the project marks private or proprietary (owner art, Bigme LUTs, Bigme modules) (lens a11y-ui-release)
- `work/module/b1051c-launcher3/customize.sh:4` — b1051c-launcher3 replaces Launcher3QuickStep with a build from one GSI version, with no version check (lens a11y-ui-release)
- `work/module/b1051c-eink-compat/customize.sh:4` — Module file labels exist only because Magisk's installer ran customize.sh; a hand-copied module tree leaves the HWC shim with the wrong label (lens fresh-device)
- `work/app/einkctl/src/org/b1051c/einkctl/Root.java:16` — Every su call is a separate Magisk request; with Magisk's default grant this shows a toast and logs an entry on each app switch (lens fresh-device)
- `work/app/einkctl/src/org/b1051c/einkctl/AppProfileService.java:79` — KeeperService starts only from BOOT_COMPLETED or MainActivity; after a provisioning install, the light sliders, light-off on sleep, theme sync and shutdown screen stay dead until a reboot (lens fresh-device)
- `work/app/einkctl/src/org/b1051c/einkctl/ThemeSync.java:299` — System colors re-seed from the wallpaper on every Dark-theme switch unless the hand-set Monet pin exists (lens fresh-device)
- `work/app/einkctl/src/org/b1051c/einkctl/ThemeSync.java:153` — Turning an automatic Dark theme into manual is tried once and never retried if su is not available yet (lens fresh-device)
- `work/app/einkctl/src/org/b1051c/einkctl/Profiles.java:241` — Every foreground app is written to logcat on a userdebug build with adb open (lens fresh-device)
- `work/app/einkctl/src/org/b1051c/einkctl/Eink.java:35` — The front-light channel swap was verified on one unit only (lens fresh-device)
- `work/app/einkctl/AndroidManifest.xml:49` — BootReceiver is exported without need (lens sec-apps)
- `work/module/b1051c-eink-compat/system/bin/phh-su:5` — Any Magisk safe mode becomes permanent: the phh-su neutraliser is itself a Magisk module, so magisk --boot-complete can never reset Magisk's boot counter once modules are off (lens critic)
- `work/rom/payload-src/provision-stage1.sh:76` — Stage 1's retry-by-reboot uses up Magisk's boot-loop budget: any retry ends in Magisk safe mode on boot 3 with every module disabled (lens critic)
