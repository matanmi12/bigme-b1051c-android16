# Community E Ink work for HiBreak / Bigme / Hisense on GSIs: survey and reuse plan for the B1051C Pro

Date: 2026-10-18 · Phase D research only. I read code and web pages only. Nothing touched the tablet or any USB device.

* **Target:** Bigme B1051C Pro. 10.3" Kaleido 3, panel EC103KH3C6, MT6877, kernel 4.19.191, Android 12 MTK vendor.
* **Current stack:** TrebleDroid A16 + Magisk 30.7 + `b1051c-eink-compat` v0.1.3. The module bundles the hwcshim port,
  the xrz props and the SF 1008 loop.
* **Method:**
  * Local clones in `~/Desktop/Bigme/work/ref/`.
  * Our own stock binaries, read offline: vendor HWC, kernel, `handwrittenservice`, `display_policy`.
  * AOSP `android16-release` sources.
  * GitHub through the API.
  * XDA through Wayback snapshots, because direct fetches return HTTP 403.
* Reddit and 4PDA were **not reachable** (403 or anti-bot), so r/Bigme and r/eink are not covered except where XDA or
  GitHub quote them.
* Web content was treated as data only. §8 lists all sources.

---

## 0. Summary

1. **Every community GSI project drives the MTK Bigme panel the same way:** root writes to
   `/sys/kernel/debug/eink_debug/*` plus the lm3630a sysfs nodes. The nodes are `global_mode` (overrides every
   commit's mode), `clean_a2` (auto-clean after A2/DU) and `anti_flicker`. None of this needs Bigme's
   SurfaceFlinger or libgui. Our kernel exposes the same nodes, plus `saturation`, `contrast`, `brightness`,
   `panel_type`, `manual_refresh` and others (§4).
2. **Our vendor HWC already holds most of Bigme's refresh policy, driven by `vendor.xrz.*` props.** It covers the
   global/forced mode, auto clean after N, the auto global flush, scrolling and anti-flicker recommit. On a MT6765
   HiBreak GSI, `setprop vendor.xrz.global_refresh_mode 176` was reported to switch modes. Stock "GlobalRefresh" is
   `setprop vendor.xrz.force_global_refresh_mode <mode>` + Bigme's `repaintEverything()`. The closest AOSP
   equivalent of `repaintEverything()` is SF `1008 i32 1`, which runs `scheduleRepaint()`, or SF 1006.
   **Per-app modes and "clear screen" may therefore need only root `setprop` + an SF poke.** This is the first thing
   to test (Q1, Q2).
3. **Reuse the hwcshim (#1) for frame-aware features.** No public project controls the per-commit mode inside the
   HWC. The shim can, which makes it the right home for:
   * guaranteed full refresh;
   * auto full refresh after N commits, if the HWC's own counter does not run;
   * fast mode during bursts with cleanup on idle;
   * a held power-off frame for a screensaver.
4. **Front end: copy micheal65536/hibreak-eink-manager's design.** Per-app profiles through `getWindows()`, fast mode
   while the IME is open or a drag is in progress, contrast and colour maps through SF 1015, blank/invert full
   refresh. From the **a9 family** (damianmqr → vbbot → dbenjaminb), take the root-daemon socket, QS tiles,
   broadcasts and frontlight sliders. dbenjaminb's B1051C fork gives **our** frontlight: `11d20000.i2c1/i2c-1/1-0036`,
   0–222. Two of its bugs matter: its validator silently drops the `-2147483471` (HD+256-dither) mode, and its
   "force clear" is an empty function.
5. **Kaleido colour.**
   * Bigme's per-app colour/contrast/text-enhance are SurfaceFlinger shaders. A GSI loses them.
   * GSI replacements: SF 1022 saturation (0–2.0), SF 1015 colour matrix, and the kernel `saturation`/`contrast` nodes.
   * Reading-side options: **derainbowify.koplugin** for KOReader.
6. **Sleep image.** AOSP fades to a black surface at screen-off (ColorFade "cool-down"). An overlay with
   `config_displayColorFadeDisabled=true` stops that without patching services.jar, as the A9 patcher does.
   A screensaver can then be drawn just before power-off.
7. **Stylus.** No GSI-portable public solution exists. I checked stock `handwrittenservice` offline. It commits strokes
   with **`DRM_IOCTL_EINK_UPDATE` directly on `/dev/dri/card0`**, bypassing SurfaceFlinger. But it links Bigme's A14
   `libgui`, hwui and skia, so it cannot be dropped onto A16. A small root daemon using the same ioctl is feasible
   later. Short term: switch to A2 while the pen is down.
8. **Security note.** The a9 daemon accepts commands from any app on its abstract socket; there is no caller check.
   If we reuse it, add an `SO_PEERCRED` uid check. Your organisation's policy asks that any possible vulnerability be
   reported, so please mention this to your security team, even though it is third-party hobby code.

---

## 1. Project table

### 1a. Projects with local clones (read in full)

| # | Project | URL | Target device | Features | Mechanism | License | Last activity | Reusability for B1051C |
|---|---|---|---|---|---|---|---|---|
| 1 | **hibreak-pro-color** (awong-dev), incl. `tools/hwcshim` | https://github.com/awong-dev/hibreak-pro-color | HiBreak Pro **Color** (MT6877, EC061KH1C1 6.1" Kaleido 3, A14 sys / A12 vendor) | GSI display fix (4× tiling); logs every HWC2 call + `DRM_IOCTL_EINK_UPDATE`; knobs `commit_mode/stride/height/use_ct`, `refresh_mode` (HWC2 desc 71); xrz prop seeding; vendor prop-context fix; reverse engineering of eink_debug, ioctls and kallsyms; debloat/revert docs | HWC2 interposer `/vendor/lib64/hw/hwcomposer.mtk_common.so`, loaded before `hwcomposer.mt6877.so` via `ro.hardware.hwcomposer`. GOT rewrites of `mtk_commit`, `ioctl`, `xrz::EffectHandler::convertToGray`, `OverlayEngine::processImage`, libdrm. Commits the RGBA client-target fd instead of the HWC's gray8 | **None in repo** | 2026-09-17 | **Core of our module.** Struct peeks are BuildID-gated to the HBPC HWC (`8a5cbea6…`), so only the ABI-safe hooks run on ours. Extend it (§6.1). Ask the author for a license before redistributing |
| 2 | **a9_accessibility_service** → renamed **A9PatchingService** (damianmqr), upstream | https://github.com/damianmqr/A9PatchingService | Hisense A9 (Snapdragon 662 / SM6115) on TrebleDroid | Per-app refresh modes, clear, floating menu, E Ink key (scanCode 766) gestures, QS tiles, broadcasts, warm/cold via Night Light, static AOD (last frame kept at sleep), volume-wake, system.img patcher | Daemon `a9_eink_server` on abstract socket `@a9_eink_socket`, writing Qualcomm `/sys/devices/platform/soc/soc:qcom,dsi-display-primary/epd_{display_mode,force_clear,commit_bitmap,white_threshold,black_threshold,contrast}` (modes 515/513/518/521). Labelled `phhsu_exec`. `vndk.rc` starts it on `boot_completed` and runs `exec_background u:r:phhsu_daemon:s0 … service call SurfaceFlinger 1008 i32 1`. Smali patches to services.jar (DisplayPowerController, ColorDisplayService, ColorFade, PhoneWindowManager) + SystemUI | MIT (+Apache-2.0 `HardwareGestureDetector.kt`) | push 2024-09-19; release v3.5.0 2024-08-22; 46★, 15 forks | Architecture reusable; **every device path is Qualcomm**. Its smali approach needs a system.img rebuild. We avoid that (§6) |
| 3 | **HibreakProPatchingService** (vbbot) | https://github.com/vbbot/HibreakProPatchingService | HiBreak Pro **mono** (B651, MT6877) | Fork of #2: 4 modes, cold/warm sliders (0–254), auto refresh (`clean_a2`), anti-shake (`anti_flicker`), key gestures (scanCodes 59/60), QS tiles. Prebuilt GSIs: ponces AOSP 14 (14.0-20250425); Evolution X 10.5 A15 (20250507) | Same daemon; writes `/sys/kernel/debug/eink_debug/global_mode` = 180/178/177/179, `clean_a2`, `anti_flicker`, `/sys/devices/platform/11d01000.i2c7/i2c-7/7-0036/lm3630a_{cold,warm}_light`. App runs as `android.uid.system`. Leftover Qualcomm paths fail silently | MIT (+Apache-2.0) | 2025-06-28; 40★ | **High** with path changes. Open issues: **#6 KOReader, maps and camera don't redraw until an overlay appears** (workaround: Developer options → "Show taps"); #9 accessibility service lost after OTA |
| 4 | **hibreak_pro_patches** (vbbot) | https://github.com/vbbot/hibreak_pro_patches | HiBreak Pro (source-built GSIs) | Permanently forces GPU composition | Source patch: SF `mDebugDisableHWC=true` and ignore 1008; treble_app `noHwcomposer` defaults to on | None | 2025-05-02 | **Not needed.** Our `service.sh` re-applies SF `1008 i32 1` for every SF pid |
| 5 | **BigmeB1051CProPatcher** (dbenjaminb) | https://github.com/dbenjaminb/BigmeB1051CProPatcher | **Bigme B1051C Pro (our model)** | Fork of #3. README: "I never did get this working 100%". Documents stock xrz props | **B1051C constants**: frontlight `/sys/devices/platform/11d20000.i2c1/i2c-1/1-0036/lm3630a_{warm,cold}_light` **0–222**; mode map clear=178, balanced=`-2147483471`, smooth=180, speed=179 → `global_mode`; `clean_a2` = "auto refresh"; `vendor.xrz.clean_frequency` 0–50 = full-refresh frequency. **Bugs:** `valid_number()` rejects >4 chars, so "balanced" is silently dropped; `r` (force clear) is an empty function | MIT (+Apache-2.0) | 2026-02-21 | **Most directly reusable constants.** Fix the validator. The author's XDA posts are in §5 |
| 6 | **hibreak-eink-manager** (micheal65536) | https://github.com/micheal65536/hibreak-eink-manager | "Hibreak phone running custom ROM". Its nodes (SYV776A at i2c `2-0062`, key code 0x240, touch `event2`) point to the **original HiBreak (MT6765)** (inference) | Per-app profiles (Room DB); Normal/Fast/Quality; fast while dragging; fast while IME open; global and fast-mode contrast maps; per-app RGB colour maps; manual full refresh Blank/Invert; function-key refresh; VCOM; EPD rail power-off after screen-off delay; QS dialog; `REFRESH` broadcast | `su -c` per command: `echo N > …/eink_debug/global_mode`, Normal=132 (GU16), Fast=16 (A2), Quality=180 (REGAL). Full refresh: write 1 (INIT) or 4 (GC16) → SF **1006** → sleep 500/30 ms → restore → SF 1006. Colour: SF **1015** column-major matrix. SF 1008 on every screen-on. Raw input via `su -c cat /dev/input/eventN`. Accessibility `typeWindowsChanged` + `getWindows()`. Sets `vendor.xrz.patch.{fcmt,frfsh}` around refreshes (consumer unknown; not in our HWC) | **None in repo** | 2026-04-25 (releases 1.0.0, 1.1.0) | **Best feature design to copy.** Remap codes and input nodes, drop the SYV776A paths unless Q7 says otherwise, and replace per-call `su` with a persistent daemon. Re-implement rather than copy (no license) |
| 7 | **hibreak-navbar-refresh-button** (micheal65536) | https://github.com/micheal65536/hibreak-navbar-refresh-button | as #6 | Refresh key in the SystemUI navbar | LSPosed hooks on `NavigationBarInflaterView`/`NavigationBarView`; layout `left[0.5W],back[1WC],home[1WC],recent[1WC],refresh[1WC];;right[0.5W]`; sends broadcast `micheal65536.hibreak.einkmanager.REFRESH` | **None** | 2026-04-23 | Usable with LSPosed and our own broadcast name. A QS tile gives the same without LSPosed |
| 8 | **inksdk** (imedwei) | https://github.com/imedwei/inksdk | Bigme HiBreak **Plus** stock (+ Onyx) | Low-latency stylus library + latency metrics; Bigme ink-path reverse engineering | Reflection into `com.xrz.HandwrittenClient` (framework.jar) → binder `handwrittenservice` → ION Canvas → `inValidate(rect, 1029)`. Documents `XrzEinkManager` and the **HWC_EINK_* flag bits** | **None in repo** | 2026-05-02 | **Not usable on a GSI as-is**: everything is /system. Its flag table and architecture notes feed §6.1 and §6.7 |
| 9 | **hibreak-android-auto-fix** (joemk88) | https://github.com/joemk88/hibreak-android-auto-fix | HiBreak / HiBreak Pro **stock** | Removes halos on the Android Auto display | `service call xrz_display_policy_service 34 s16 <pkg> i32 0` (`setTextEnhanceForPackage`, no permission check); tx 1 `getPolicyByPackage`. Documents Bigme SF shaders `createProcessShader(colorMode,saturation,contrast,brightness)` and `createProcessShader2(textEnhance)` | MIT | 2026-07-14 (v1.0) | Stock-only. Key knowledge: stock colour, contrast and text enhance live in Bigme's SF |
| 10 | **bigme-ota-client** (marek-vybiral) | https://github.com/marek-vybiral/bigme-ota-client | HiBreak Pro BW (B651), "probably other Bigme" | Full-OTA URL fetcher | RSA-wrapped JSON POST to `ereader.bigme.vip:8090/json/osupgrade.action` with keys from `libxrzEncrypt.so` | Unlicense | 2026-04-13 | Indirect: a source of stock images for blob reference |

### 1b. Web-found projects (GitHub sweep, source fetched where noted)

| Project | URL | Target device | Features → mechanism | License | Last activity | Reusability for B1051C |
|---|---|---|---|---|---|---|
| farfromrefug/**A9PatchingService** (fork, 37 commits ahead) | https://github.com/farfromrefug/A9PatchingService | Hisense A9 | Double-tap-to-wake in the daemon: finds the touch device by name, `EVIOCSMASK`, tap ≤250 ms/≤40 px/gap ≤400 ms → `input keyevent 224`, toggled with `dt1`/`dt0`. `EINK_COMMIT_BITMAP` broadcast. Magisk module swapping the daemon (`set_perm … phhsu_exec`). Docker-based patcher for macOS. Notes that init parses `vndk.rc` before Magisk mounts | MIT | 2026-08-15 | **DT2W is valuable on the B1051C, which has only a power button.** Port it to our daemon (cyttsp5 device name) |
| farfromrefug/**hisense_a9_lsposed** | https://github.com/farfromrefug/hisense_a9_lsposed | Hisense A9 | LSPosed "reader mode": volume keys turn pages with the screen off (`PhoneWindowManager.wakeUpFromWakeKey` → refresh → `goToSleep`); keep chosen apps awake (`TaskFragment.shouldSleepActivities`); dodge the black-panel race on wake (`Transition.onTransitionReady`) | Apache-2.0 | 2026-08 | Pattern for KOReader + BT page-turner reading with the screen "off". Needs LSPosed |
| izissise/**HiBreakPatchingService** | https://github.com/izissise/HiBreakPatchingService | Original HiBreak (MT6765) | Socket `@hibreak_eink_socket`. Frontlight `ioctl("/dev/lm3630a", 0x7901/0x7902, &u8)`, 0–255. `clean_a2`/`anti_flicker` written as a binary `uint[2]{0x3102 on / 0x3002 off, 0}` (bug: can never enable) | MIT | 2024-11 | Low. Check whether `/dev/lm3630a` exists on our kernel (`CONFIG_MTK_LM3630A=y`). Write formats differ between forks: read before writing |
| vbbot/**minimal-phone-patching-service** | https://github.com/vbbot/minimal-phone-patching-service | Minimal Phone (MTK) | Same daemon; mode/clear through `…/7-004b/eink_cpld_registers`; lights through `/sys/class/leds/{lcd-backlight,led-warmlight}`; `clean_a2`/`anti_flicker` as **ASCII** | MIT | 2025-07 | Low. Confirms ASCII writes to `clean_a2` on another MTK kernel |
| Cyanogenbot/HibreakProPatchingServiceEOS | https://github.com/Cyanogenbot/HibreakProPatchingServiceEOS | HiBreak Pro, /e/OS | vbbot's service as a treble `.mk` | MIT | 2025-07 | Low |
| flxholle/HisenseA9---Custom-ROM | https://github.com/flxholle/HisenseA9---Custom-ROM | Hisense A9 | Restructured patcher; drops patches needing the system signature | (fork) | 2026-04 | Low |
| **right9code/bigmelight.koplugin** | https://github.com/right9code/bigmelight.koplugin | HiBreak/B6 (root) | KOReader frontlight plugin: writes `/sys/bus/i2c/devices/2-0036/lm3630a_{cold,warm}_light` (0–255); mirrors Bigme settings keys; `magiskpolicy --live "allow untrusted_app_all sysfs file {read write open getattr}"` | not stated in sweep | 2026-09 | **High for KOReader use.** Change the path to `1-0036` and the max to 222. Prefer our daemon over that very broad live policy |
| right9code/hibigzero | https://github.com/right9code/hibigzero | HiBreak | Power manager (hotplug, charge limit); uses `reboot -p` so the E Ink image stays on the panel | not stated | 2026-09 | Idea only: E Ink keeps whatever was last committed |
| Yannick158/sidekeys-hibreak | https://github.com/Yannick158/sidekeys-hibreak | HiBreak Pro | Accessibility key-filter button mapper | MIT | 2026-09 | Medium (BT page-turner / stylus-button mapping) |
| joemk88/HiBreak-ScreenSaver-WeatherStrip | https://github.com/joemk88/HiBreak-ScreenSaver-WeatherStrip | HiBreak stock | Stock `com.xrz.standby` shows `/sdcard/cover.jpg` | — | 2026 | Info: the stock screensaver model |
| loopback7084/hibreak_pro_color_scripts | https://github.com/loopback7084/hibreak_pro_color_scripts | HiBreak Pro Color | Super unpack/mount/debloat/repack | GPL-3.0 | 2025-10 | Not needed (Magisk route) |
| tamnys/**Minimal-GrapheneOS** | https://github.com/tamnys/Minimal-GrapheneOS | Minimal Phone (MP01) | Cleanest enforcing-SELinux design: `genfscon debugfs /eink_debug/clean_a2 u:object_r:mp01_eink_debugfs:s0`; init-owned socket `MP01_eink_socket` + `SO_PEERCRED` system-uid check; init property triggers (`on property:sys.mp01.eink.clean_a2=* write /sys/kernel/debug/eink_debug/clean_a2 ${…}`); platform-signed app | none | 2026-10 | **Pattern** for a hardened version of our daemon (the `SO_PEERCRED` check, or init triggers in a Magisk `.rc`) |
| at10ti0n/inkpalm5-android11 | https://github.com/at10ti0n/inkpalm5-android11 | Xiaomi InkPalm 5 (Allwinner), PHH GSI | QS tiles set `persist.sys.mRefreshMode` and `persist.sys.canRefresh`; app **platform-signed with the GSI's public AOSP test key**, so it runs as system without root; replacement lights HAL for LM3630A + Night Light → warm bank | not stated | 2026-10 | Pattern: test-key platform signing (check that TD16 uses test-keys), and the lights-HAL idea for brightness/Night Light |
| PaddleStroke/hisense-a6l-lineageos | https://github.com/PaddleStroke/hisense-a6l-lineageos | Hisense A6L (LineageOS device tree) | Vendor E Ink daemon; `persist.vendor.eink.*`, **`clear_every` (default 10)** = auto full refresh after N partials; Clear/Refresh/Frontlight tiles | not stated | 2026-10 (active) | Pattern for the N-partials counter (§6.1) |
| LGiki/lineage_device_onyx_leaf3 | https://github.com/LGiki/lineage_device_onyx_leaf3 | Onyx Leaf3 | SF-side policy `Leaf3EpdcPolicy.h`: full refresh every N reader pages | not stated | — | Pattern |
| Enter-tainer/einknotif | https://github.com/Enter-tainer/einknotif | Rockchip readers | `service call ebook 2` / `sys.ebook.mode`; tiles; per-app memory via UsageStats polling (2.5 s); per-app contrast/saturation | MIT | — | Pattern: UsageStats as a fallback if accessibility is killed (vbbot #9) |
| ChenXu178/Hisense-E-Ink-auto-refresh | https://github.com/ChenXu178/Hisense-E-Ink-auto-refresh | Hisense stock | Counts touches/clicks, then `EpdManager.forceClear()` | — | — | Pattern: "clear after N interactions" |
| denzilferreira/vendor_hisense | https://github.com/denzilferreira/vendor_hisense | Hisense A9 | Open re-implementation of `IEinkServiceInterface` | — | — | Low |
| **Euphoriyy/derainbowify.koplugin** | https://github.com/Euphoriyy/derainbowify.koplugin | Kaleido readers (KOReader) | Software suppression of Kaleido colour fringing in KOReader | GPL-3.0 | — (31★) | **High for colour reading in KOReader.** Device-independent |
| zanjie1999/eink256 | https://github.com/zanjie1999/eink256 | generic | Xposed 4→8-bit dithering | — | — | Low/medium |
| ekalaitzis/Rooting-Bigme-B751C | https://github.com/ekalaitzis/Rooting-Bigme-B751C | Bigme B751C | Root guide (no recovery; BROM through both page buttons) | — | 2025-05 | Info |
| mio-19 `hibreak_pro.nix` (robotnix) | (in mio-19/repo) | claims HiBreak Pro | Pulls vbbot's service, sets `mDebugDisableHWC` | — | — | Claims MT6789/"Xelex Q25"; looks wrong |

**Not found anywhere:** a `device/bigme/*` tree, a Bigme MT6877 GPL kernel drop, or any B1051C/B751C/inkNote GSI E Ink
project other than #5. No public tool controls MTK **Kaleido saturation**. The stylus search found nothing beyond
inksdk.

---

## 2. Baseline: what we already have on the B1051C

* The display works through the hwcshim: it commits the RGBA client target, and HW overlays stay off (SF 1008).
  Boot is unwedged by the phh backpressure props + one SF restart (`work/module/b1051c-eink-compat/service.sh`).
  `post-fs-data.sh` seeds 17 `ro.vendor.xrz.*` and 30 `vendor.xrz.*` stock values. It does **not** set
  `vendor.xrz.global_refresh_mode`. `sepolicy.rule` lets the composer read and set `vendor_default_prop`.
* **Stock values** (`research/getprop_stock_2026-10-17.txt`):
  * `ro.vendor.xrz.screen_type=33`, `default_refresh_mode=178`, `default_anti_flicker=1`, `default_color_enhance=70`;
  * runtime `vendor.xrz.anti_flicker_enabled=0`, `auto_clean_enabled=1`, `clean_frequency=0`,
    `force_global_refresh_mode=-1`;
  * presets `sys.{maga,comic,video}_refresh_mode = -2147483471 / 180 / 179`;
  * `init.svc.handwrittenservice=running`, `sys.handwritten.pen.name=Wacom_I2C_Digitizer`.
* **Stock per-app policy seed** `/system/etc/display_policy` (stock `work/super/system_b.img`, 206 entries, read
  offline). Keys used: `refresh_mode` (178 or 179 on 14 apps), `app_color_mode` (0, or 3 = VIDEO), `app_anti_flicker`,
  `refresh_frequency` (-1 for most apps; 30 for Bigme's own readers), `app_contrast` (0–60), `app_dpi`, `app_bleach_*`.
  * Video apps get **179 + colour mode 3 + anti_flicker 1**.
  * **KOReader** (`org.koreader.launcher`) gets `app_contrast=30`, `app_dpi=300`, `refresh_frequency=-1`,
    `app_bleach_mode=1`.

  This is a ready-made default table for our per-app profiles.
* The stock **vendor HWC dump** has a per-layer "Refresh Mode" column, e.g. `(0xb2,0xb2)`
  (`research/dumpsys_surfaceflinger_stock_2026-10-17.txt`). It works as a diagnostic on the GSI too. Stock was also
  all-CLIENT on the B1051C when that dump was taken (3 CLIENT layers). It sets `support_color_transform:1` and
  `check_skip_client_color_transform:1`, which bear on Q5.
* B1051C GSI history (XDA, dbenjaminb):
  * 2025-06: vbbot's HiBreak Pro Evolution X GSI booted with camera OK, stylus "does something", and display scaling
    issues.
  * 2026-02: "the issue with the HiBreak Pro GSI was the frame buffer mode". This is the 4:1 packing that awong's
    shim fixes.
  * The tablet has **only a power button**, so EDL and recovery are reachable only through adb.

---

## 3. Mechanisms in detail

### 3.1 a9 family (#2, #3, #5, forks): accessibility service + root daemon

* **App** (`com.lmqr.ha9_comp_service`): `sharedUserId=android.uid.system`, persistent, directBootAware, baked into
  system.img. It is enabled through `persist.accessibility.enabled_service`, or through `settings put secure
  enabled_accessibility_services` + `appops set … SYSTEM_ALERT_WINDOW allow` in `vndk.rc` upstream.
  Config: `typeWindowStateChanged`, `flagRequestFilterKeyEvents`.
* **Per-app switching**:
  1. `TYPE_WINDOW_STATE_CHANGED` gives `(pkg, class)`.
  2. `packageManager.getActivityInfo()` drops non-activities.
  3. A SharedPreferences lookup uses `package:<pkg>` (or `package:None`).
  4. The service sends one letter over `@a9_eink_socket`.

  (`RefreshModeManager.kt`, `A9AccessibilityService.kt`, `UnixSocketCommandRunner.kt`.)
* **Daemon protocol** (`eink_daemon.c`): newline-separated commands; numbers must be ≤4 digits and unsigned.
  * `c/b/s/p` → `global_mode`;
  * `br_co<N>` / `br_wm<N>` → cold/warm;
  * `au_br<0|1>` → `clean_a2`;
  * `an_fl<0|1>` → `anti_flicker`;
  * `r` / `cm` → Qualcomm clear/commit (**empty on the Bigme forks**);
  * `stw/stb/sco` → Qualcomm thresholds/contrast (dead on MTK);
  * `smb<N>` → `sys.linevibrator_touch` (a Hisense prop hack).
* **Clear screen**:
  * The working path is `ClearScreenButtonAction`: switch to the "clear" mode, add a full-screen black
    `TYPE_ACCESSIBILITY_OVERLAY` for 100 ms, then a white one for 100 ms, then restore the mode. vbhai reports a
    white-then-black flash as the reliable ghost clear on the HiBreak Pro.
  * The QS "Clear Screen" tile sends `r`, which is a **no-op** on the Bigme forks.
* **Screen off/on**: SCREEN_OFF optionally sends a "clear" mode after 150 ms and turns both frontlights to 0.
  SCREEN_ON restores them.
* **Keys**: `onKeyEvent` scanCodes 59/60 on the HiBreak Pro forks (766 on the A9). `HardwareGestureDetector` gives
  single, double and long press, mapped separately for screen on and off. The B1051C has only a power button, so
  this matters mostly for BT remotes and the stylus button.
* **Broadcasts** (opt-in): `EINK_FORCE_CLEAR`, `EINK_REFRESH_SPEED_{CLEAR,BALANCED,SMOOTH,FAST}`, plus farfromrefug's
  `EINK_COMMIT_BITMAP`.
* **SELinux**: `/system/bin/a9_eink_server u:object_r:phhsu_exec:s0` → it runs in TrebleDroid's phh-su domain. This
  needs the binary inside system.img, because init parses `vndk.rc` before Magisk mounts (farfromrefug's note).

### 3.2 Device constants across forks

| Item | Hisense A9 (upstream) | HiBreak Pro mono (vbbot) | **B1051C Pro (dbenjaminb)** | HiBreak (MT6765) | HiBreak Pro Color (awong-dev) |
|---|---|---|---|---|---|
| Mode write | `…qcom,dsi-display-primary/epd_display_mode` 515/513/518/521 | `eink_debug/global_mode` 180/178/177/179 | `global_mode` **178 / (-2147483471, dropped by bug) / 180 / 179** | `global_mode` (micheal65536: 132/16/180); `setprop vendor.xrz.global_refresh_mode 176` (dferreira) | `global_mode` has a write handler (mode bits r--) |
| Clear | `epd_force_clear`, `epd_commit_bitmap` | overlay flash | overlay flash | `global_mode` 1/4 + SF 1006 | — |
| Frontlight | `/sys/class/backlight/{aw99703-bl-1,-2,ktd3137-bl-3,-4}` through Night Light smali | `11d01000.i2c7/i2c-7/7-0036`, 0–254 | **`11d20000.i2c1/i2c-1/1-0036`, 0–222** | `2-0036` lm3630a 0–255, or `/dev/lm3630a` ioctl 0x7901/0x7902 | `11d01000.i2c7/i2c-7/7-0036`; raw cold read 182 |
| EPD PMIC | — | — | unknown (Q7) | SYV776A / TPS65185 at `2-0062` | TPS65185 |
| Auto clean / anti-flicker | — | `clean_a2`, `anti_flicker` (ASCII) | same | same; izissise uses a binary struct | writable by mode bits |

### 3.3 hibreak-eink-manager (#6)

* **Refresh mode**: reads `global_mode` (format `mode=0x..`), writes it with `echo`.
* **VCOM**: `eink_debug/vcom` and `SYV776A_store_vcom` (guarded −1500…0 mV).
* **EPD rail**: `SYV776A_turnOnOff_power` after `screenPowerOffDelay`, so the last image stays.
* **Executor**: single-threaded. After every change: `setprop vendor.xrz.patch.fcmt 1; …frfsh 1;
  service call SurfaceFlinger 1006; sleep; props back to 0`.
* **Colour**: `service call SurfaceFlinger 1015 i32 1 f×16`, column-major, with per-channel scale on the diagonal and
  offsets in column 3. Contrast maps (min/max %) and RGB maps compose into scale and offset.
* **Drag detection**: raw `input_event` (BTN_TOUCH 0x14a, ABS_MT_SLOT 0x2f, ABS_MT_POSITION_X/Y 0x35/0x36), with
  start delays, a distance threshold and an end delay. A second finger switches to FAST immediately.
* **IME and app identity**: a window of `TYPE_INPUT_METHOD` means FAST. The current app is the topmost
  `TYPE_APPLICATION` window's package. "System UI" is when there is none, with an option to keep the last app's
  mode there.

### 3.4 hwcshim (#1): hooks we can build on

* `DRM_IOCTL_EINK_UPDATE = 0xC04464CF` (68-byte struct):
  * `+0x00` mode (mode + flags)
  * `+0x04..0x10` rect
  * `+0x14` dither
  * `+0x1c..0x24` overridden from kernel globals when those are non-zero
  * `+0x28` dma-buf fd (RGBA)
  * `+0x30/34` x,y
  * `+0x38/3c` w,h

  `0xC00464D1` is `wait_vsync`. The other private nrs 0x8f–0x9f are get_type, reload_waveform and user fences.
* HWC2 descriptor **71** = Bigme `setLayerRefreshMode(display, layer, mode)`. AOSP SF never calls it. The shim can
  push it to every layer.
* The kernel selects the grey or colour pipeline by `disp_get_eink_panel_type()`. `panel_type` is `-1`/unset on stock
  too (per `docs/STATUS.md`).
* The knobs are re-read every validate/commit, so one-shot actions are cheap to add.

### 3.5 Stock stylus path (inksdk + our own offline check)

* `/system/bin/handwrittenservice -p /dev/input/event0` (late_start, root):
  * reads the pen directly (pressure ranges from absinfo);
  * owns ION buffers the app draws into through `HandwrittenClient.getCanvas()`;
  * **opens `/dev/dri/card0` and issues `DRM_IOCTL_EINK_UPDATE` itself** ("commit frame: rect(...) mode=0x%x");
  * touches `eink_debug/clean_a2`.
* AIDL methods: `inValidate`, `requestCommit`, `setHandwrittenBuffer`, `setLayoutRect`, `setPredictEnabledForClient`,
  `setRecommitEnabledForClient`, `setAutoCleanControlEnabledForClient`, `setOverlayEnabledForClient`, …
* The framework ORs `HANDWRITTEN|SURFACEVIEW` (0x50000) into the layer mode. That reaches the HWC through desc 71
  (`HwcHal setLayerRefreshMode mode=500b2`).
* Dependencies:
  * A14 `libgui` including Bigme's `SurfaceComposerClient::repaintEverything`, plus `libhwui`, `libskia_shard`,
    `libandroid_runtime`, `libinput`;
  * Bigme's `libxrzUtil` (~40 system libs);
  * `libxrz_predict.so`, which needs only libc, libc++, libm, liblog and libcutils.
* inksdk measured ~30 ms per `HANDWRITE` update. The touch IC sleeps after 1–2 s idle.

---

## 4. The MT6877 Bigme E Ink control surface: what survives on a GSI

### 4.1 Refresh-mode values

From framework.jar (vbhai, XDA 2025-04-14), confirmed on the HBPC:

| Value | Mode |
|---|---|
| 1 | INIT |
| 2 | DU |
| 4 | GC16 |
| 8 | GC4 |
| 16 | A2 |
| 32 | GL16 |
| 64 | GLR16 |
| 128 | GLD16 |
| 132 | GU16 |
| 136 | GU4 |
| 137 | INPUT |
| 176 | CLEAN |
| 177 | HD |
| **178** | **NORMAL** |
| **179** | **FAST** |
| **180** | **REGAL** |
| 1024 | RECT |
| 1029 | HANDWRITE |
| 1030 | RUBBER |
| 32768 | AUTO |
| **-2147483471** | **HD_256** (= `0x80000000 \| 177`) |

The stock B1051C presets use 178 (default), `HD_256` (magazine and boot logo), 180 (comic and boot animation) and
179 (video).

### 4.2 Flag bits ORed into a layer's mode

Sources: inksdk probe, and Renate on XDA 2025-04-15.

| Bits | Flag |
|---|---|
| `1<<16` | SURFACEVIEW |
| `1<<17` | FORCE_REFRESH |
| `1<<18` | HANDWRITTEN |
| `1<<19` | HOME |
| `1<<20` | HANDWRITTEN_BG |
| `1<<21` | MULTI_DAMAGE |
| `1<<22` | INPUT_METHOD |
| `1<<23` | AUTO_DITHER |
| `1<<24` | DISABLE_LAYER |
| `1<<25` | OVERLAY |
| `1<<26` | LOGO |
| `1<<27` | CURSOR |
| `1<<29` | DITHER_TEXT |
| `2<<29` | DITHER_PICTURE1 |
| `3<<29` | DITHER_PICTURE2 |
| `4<<29` | DITHER_256 |

### 4.3 Our kernel's `eink_debug` nodes

Extracted offline from stock `boot_b`, Mar-2026 kernel.

* Created, per the driver's "fail to create eink X file" error strings: `anti_alias`, `anti_flicker`, `brightness`,
  `clean_a2`, `contrast`, `frame_data`, `global_dither`, `global_mode`, `panel_type`, `saturation`, `sys_update`,
  `version`.
* Also present as names: `manual_refresh` (reads `init=0,pos(0,0,0), neg(0,0,0),loop=0`), `wf_ota`, `save_level`,
  `print_level`, `machine_waveform`, `vcom`, `temperature`, `waveform`, `enable`.
* Kaleido 3–specific code: `init_eink_ctrl_data_k3`, `get_delt_Q_for_kaleido3_78_du3`.
* Kernel-side contrast ("set contrast to %d, alpha=%d") and A2/DU auto-clean waveforms ("auto clean A2 mode …").
* Drivers for both TPS65185 and SYV776A PMICs, plus `icn6211`.
* `CONFIG_DEBUG_FS=y`, `CONFIG_MTK_LM3630A=y`.

### 4.4 Our vendor HWC's xrz props

From `strings work/vendor_files/lib64/hw/hwcomposer.mt6877.so`.

| Group | Props |
|---|---|
| Refresh mode | `vendor.xrz.global_refresh_mode`, `vendor.xrz.force_global_refresh_mode` (logged as `… = %x`) |
| Auto clean | `vendor.xrz.auto_clean_enabled`, `vendor.xrz.auto_clean_check_disable`, `vendor.xrz.clean_frequency`, `vendor.xrz.disable_auto_global_refresh` |
| Other policy | `vendor.xrz.is_scrolling`, `vendor.xrz.enable_home_clean`, `vendor.xrz.anti_flicker_enabled`, `vendor.xrz.driver_color_enhance`, `vendor.xrz.color_restoration_enabled`, `vendor.xrz.gray_256_dither`, `vendor.xrz.hwc_idle`, `vendor.xrz.fps_limit`, `vendor.xrz.disable_eink_vsync(_for_fast)` |
| Commit and boot | `vendor.xrz.disable_commit`, `vendor.xrz.capture_commit` (dumps to `/data/SF_dump/commit_%d_fd%d`), `vendor.xrz.update_waveform`, `ro.vendor.xrz.{default_refresh_mode,screen_type}` |

Logic strings in the same binary:

* `CommitPolicy::needCleanScreen` / `checkCleanScreen`
* "need auto global flush, cur mode: %x", "threshold=%d,cur=%d"
* "disable rect commit, reason: auto clean / auto global flush"
* "handle delay clean, globalRefreshMode=%x", "trigger clean delay"
* "force normal mode for scrolling"
* "recommit for antiFlicker done"
* "rectFlushEnabled=%d,hasForceRefresh=%d,…"
* `IdleThread` / `idle_refresh`
* "open disp success, panel type: %d"

### 4.5 Control points and GSI status

| Control | Path / API | Survives GSI? | Notes |
|---|---|---|---|
| Global mode (kernel) | `eink_debug/global_mode` (echo decimal) | **Yes** | Used by #3, #5, #6. Root. Overrides every commit |
| Global mode (HWC) | `setprop vendor.xrz.global_refresh_mode <m>` | **Yes** (prop + vendor HWC) | Worked on a MT6765 GSI (dferreira, XDA 2025-01). Needs root to set; our sepolicy lets the composer read it |
| One-shot full refresh | `setprop vendor.xrz.force_global_refresh_mode <m>` + a repaint, then back to -1 | Likely | Stock `XrzEinkManager_jni.nativeForceGlobalRefresh` = `property_set(force_global_refresh_mode)` + `repaintEverything()` (XDA HiBreak thread, decompiled). AOSP substitute: SF `1008 i32 1` (`scheduleRepaint`: geometry dirty + composite) or 1006 |
| Per-commit mode | shim `commit_mode` | **Yes** (our shim) | Finest lever. Frame-aware |
| Per-layer mode | HWC2 desc 71 | Yes (vendor HWC) | Effect under all-CLIENT composition is untested (Q1) |
| Auto clean after A2/DU | `eink_debug/clean_a2` | Yes | "auto refresh" in the a9 forks. ASCII works on vbbot's MTK targets |
| Auto full refresh after N | `vendor.xrz.clean_frequency` (0–50), `auto_clean_enabled`; HWC "auto global flush" | Probably | Q4 |
| Anti-flicker | `eink_debug/anti_flicker`; `vendor.xrz.anti_flicker_enabled` | Yes | The HWC does "recommit for antiFlicker" |
| Manual refresh | `echo "1 0 0 0 0 0 1" > eink_debug/manual_refresh` | Yes | Blinks but is **unreliable**: the screen often stays white (HiBreak, XDA). Last resort |
| Kaleido saturation, contrast, brightness | `eink_debug/{saturation,contrast,brightness,global_dither,panel_type}` | Yes | Untested on this device (Q5) |
| VCOM, waveform | `eink_debug/{vcom,waveform,wf_ota,sys_update}` | Yes | **Never write.** Per-unit calibration and waveform updates |
| Frontlight | `/sys/devices/platform/11d20000.i2c1/i2c-1/1-0036/lm3630a_{cold,warm}_light` | Yes | 0–222 per dbenjaminb (Q6) |
| SF composite / repaint | SF 1006 (= 1004) `scheduleComposite`; SF `1008 i32 x` → `scheduleRepaint` | Yes (AOSP A16) | Checked in `SurfaceFlinger.cpp` |
| Colour matrix / saturation | SF `1015 i32 1 f×16` (column-major); SF `1022 f 0..2`; `persist.sys.sf.color_saturation` | Yes (AOSP) | DisplayTransformManager also issues 1014/1015/1022/1023 and overwrites ours on night light, inversion or colour mode changes |
| Bigme per-app DB, shaders, text enhance | `xrz_display_policy_service`, Bigme SF `createProcessShader*` | **No** | Replace with our app + SF 1015/1022 or the kernel nodes |
| Stylus fast path | `handwrittenservice`, `HandwrittenClient` | **No** | §6.7 |

---

## 5. Forum findings (XDA through Wayback; GitHub issues)

All XDA reads are Wayback snapshots. Posts after a snapshot's date were unreachable. Reddit and 4PDA were blocked.

**B1051C Pro**: XDA "Bigme B1051C Pro Development Thread"
(https://xdaforums.com/t/bigme-b1051c-pro-development-thread.4745663/, snapshot 2026-03-02), dbenjaminb:
* 2025-06-22: unlocked using the HiBreak Pro steps; mtkclient reports SBC/SLA/DAA disabled. The Evolution X
  HiBreak Pro GSI boots: camera works, stylus partial, display scaling issue.
* 2025-10-10: power button only, so EDL and recovery are reachable only through adb.
* 2026-02-21: the GSI issue was the "frame buffer mode". He published the unfinished patcher (#5).

**HiBreak Pro (mono)**:
* Dev thread (https://xdaforums.com/t/bigme-hibreak-pro-development-thread.4731041/, snapshots 2025-05/07):
  * vbhai 2025-04-14: the GSI shows a picture only after `SF 1008 i32 1`; controls are in `eink_debug`; the mode
    table in §4.1; frontlight at `7-0036`.
  * Renate 2025-04-15: the flag bits in §4.2.
  * vbhai 2025-04-16 to 23: stock GlobalRefresh = `force_global_refresh_mode` + `repaintEverything` (Bigme libgui
    only); the `manual_refresh` blink is unreliable; there is no `/dev/ebc`.
  * vbhai 2025-04-29/05-01: the reliable ghost clear is clear mode + a white then black overlay.
* vbh.ai guides (https://vbh.ai/hibreak-pro-guide-4-flashing-the-android-15-gsi/, https://vbh.ai/hibreak-pro-gsi-release/):
  * the release is ponces A14 + the ported A9 service;
  * phh settings: MediaTek GED KPI, SF GL/HWC backpressure off, force-allow AOD;
  * the first boot shows black until a lock/unlock;
  * "Do not patch with the repo's script – it will brick" (DisplayManager bootloop).
* vbbot issue **#6**: KOReader, Kindle, maps and camera don't redraw until an overlay appears. Workaround: Developer
  options → **Show taps** (2025-08-17).
* Main thread (https://xdaforums.com/t/bigme-hibreak-pro-dimensity-900-mt6877-e-ink-phone.4723924/):
  * an A16 ponces GSI gave a black screen + adb unauthorized, and only a full restore helped (bruceloco, 2025-07);
  * no frontlight control on the A15 GSI;
  * a rooted boot needs a power press within ~5 s.

**HiBreak Pro Color**:
* https://xdaforums.com/t/bigme-hibreak-pro-color-color-s-root-magisk-debloat-general-impressions.4764527/ (snapshot
  2026-05): unlock works; back up everything except userdata, waveform included; a modified super or mixed firmware
  soft-bricks, and recovery needed a drained battery + full reflash.
* https://xdaforums.com/t/bigme-hbp-android-16.4788393/ (2026-05-12): the A16 GSI shows a tiny repeated grey strip,
  which is the tiling the shim fixes.

**HiBreak (MT6765, same xrz stack)**: https://xdaforums.com/t/bigme-hibreak-root-mediatek-6765.4697830/
* Panel `/sys/devices/platform/eink`; PMIC SYV776A/TPS65185 at `2-0062`; `/system/bin/eink_flash` is lost on a GSI.
* Frontlight through `/dev/lm3630a` ioctl. Contrast is done in GL by an xrz library preloaded into apps.
* **`setprop vendor.xrz.global_refresh_mode 176` toggles the mode on the GSI** (dferreira, 2025-01).
  `global_brightness_level` maxes at ~36.
* On the GSI, the **frontlight stays on at screen-off**, so it has to be handled.
* LineageOS A14 GSI OK, A15 not (a "vertical mini display", i.e. tiling).

**Hisense A9**: damianmqr's patcher, v3.5.0 (2024-08-22):
* "Static AOD" through ColorFade shader patches;
* warm light through Night Light;
* the `vndk.rc` + `phhsu_daemon` trick.

The A7 is Unisoc and the InkPalm 5 is Allwinner; neither uses `eink_debug`.

**Reported pitfalls worth keeping in mind:**
* Back up every partition except userdata, waveform included.
* A GSI that comes up black with adb unauthorized has no way in.
* Mixed firmware or a modified super has soft-bricked devices.
* A `property_contexts` entry with an undeclared type stops boot.
* A15/A16 GSIs show tiling without a shim; A14 was historically the most reliable.
* Patched boot images saved into Download come out as 0 bytes on Bigme.
* OTAs flip slots.
* Some newer HiBreak Pro batches block the public Kamakiri payload.

---

## 6. Best reusable building blocks for the B1051C (recommendations, in order)

### 6.1 First: read-only probes, then single-write tests (each write needs per-operation approval)

1. `ls -l /sys/kernel/debug/eink_debug/` and `cat` each node except `waveform`. This confirms which nodes exist
   (§4.3), their value formats (ASCII vs binary, see izissise) and `manual_refresh`'s format.
2. Prop test of per-app modes: `setprop vendor.xrz.global_refresh_mode 179` (then 178). Watch logcat for the HWC's
   `vendor.xrz.global_refresh_mode = %x` line and the shim's `EINK_UPDATE mode=`.
3. Prop test of clear: `setprop vendor.xrz.force_global_refresh_mode 4` (GC16), then `service call SurfaceFlinger 1008
   i32 1`, then `force_global_refresh_mode -1`.
4. Kernel route: `echo 179 > global_mode`, then `echo 0` to see whether 0 means "no override". Verify that reading
   back gives `mode=0x..`.

Whichever of 2 and 4 works becomes the "mode" primitive. Item 3, or the shim one-shot (6.2-1), becomes the "clear"
primitive.

### 6.2 Extend hwcshim (vendor code, GSI-independent)

1. **One-shot full refresh.** Add a knob such as `vendor.debug.hwc.shim.full_refresh=<mode>`. On the next commit the
   shim forces a full-panel rect with mode 4 (GC16), or 1 (INIT) for a flash; the daemon triggers the frame with
   SF `1008 i32 1`. This is deterministic whatever the HWC's internal state, and it avoids racing other
   `global_mode` writers. Later option: replay the last fd directly (`dup()` at `processImage`).
2. **Auto full refresh after N partials.** First try the HWC's own logic: `clean_frequency=N`, `auto_clean_enabled=1`,
   then look for "need auto global flush" (Q4). Otherwise count non-full-rect commits in the shim and upgrade every
   Nth to GC16, like A6L's `clear_every=10` and Leaf3's page schedule. Defaults per app come from `display_policy`
   (`refresh_frequency`).
3. **Fast during bursts, clean on idle** (optional). Above X commits/s, rewrite to FAST 179 or A2 16. After T ms
   idle, one GL16/GC16 full-rect. This is Bigme's `is_scrolling`/`hwc_idle` behaviour in generic form, and it covers
   KOReader and every other app.
4. **Hold power-off for a screensaver frame** (§6.6). `setPowerMode(OFF)` is already hooked for logging; delay it by
   about 300 ms when a screensaver commit is pending.
5. **Keep the BuildID gate.** Struct peeks stay off until the offsets are re-derived for our `hwcomposer.mt6877.so`.

### 6.3 Root daemon: port `a9_eink_daemon` (MIT) as a Magisk-started service

* **Keep** the socket and line protocol.
* **Change**:
  * paths: our `eink_debug` nodes and the `1-0036` frontlight;
  * validator: allow a sign and 10 digits, so HD_256 works;
  * add `setprop vendor.xrz.*` and shim-knob commands;
  * add `service call SurfaceFlinger 1006/1008/1015/1022`;
  * add farfromrefug's **double-tap-to-wake** (`EVIOCSMASK` tap detector + `input keyevent 224`), using the cyttsp5
    device name. Important on a power-button-only tablet.
* **Delete**: the Qualcomm paths, the theme command and the `sys.linevibrator_*` hacks.
* **Never** write `vcom`, `waveform`, `wf_ota` or `sys_update`.
* **Start it from `service.sh`** so it runs as `u:r:magisk:s0`. No system.img change and no `phhsu_exec` label are
  needed. If apps cannot connect, add one `sepolicy.rule` line for `unix_stream_socket connectto`.
* **Authenticate callers with `SO_PEERCRED`.** Allow only our app's uid and root, as in tamnys/Minimal-GrapheneOS.
* Optional hardened variant: init property triggers (`on property:… write /sys/kernel/debug/eink_debug/…`) in a
  Magisk-provided `.rc`, plus `genfscon` labels.

### 6.4 Front-end app

* **Detection**: micheal65536's `typeWindowsChanged` + `getWindows()` (topmost `TYPE_APPLICATION`, IME, System UI).
  It needs only the accessibility permission. Add a UsageStats fallback (einknotif) for when an update kills
  accessibility (vbbot #9).
* **Modes**:

  | Mode | Value |
  |---|---|
  | Normal | 178 |
  | Fast | 179 |
  | Regal / Quality | 180 |
  | HD_256 / Magazine | -2147483471 |
  | GU16 | 132 |
  | A2 | 16 |

  Per-app colour preset: DEFAULT/MAGAZINE/COMIC/VIDEO.
* **Default profiles** from stock `display_policy`: video apps 179 + anti-flicker; KOReader 178 with contrast 30 and
  no forced refresh frequency.
* **Fast while dragging or typing**: micheal65536's logic. Feed raw cyttsp5 events from the daemon, not
  `su -c cat`. Or use accessibility `TYPE_VIEW_SCROLLED` + `vendor.xrz.is_scrolling`, which the HWC honours.
* **Clear**:
  * QS tile;
  * broadcasts `EINK_FORCE_CLEAR` / `EINK_REFRESH_SPEED_*` (a9-compatible names, so existing Tasker or KOReader
    setups carry over);
  * optional LSPosed navbar button (#7).

  Back them all with 6.1-3 or 6.2-1. Keep the black→white overlay as a fallback.
* **Frontlight**:
  * a9 cold/warm seekbars with max 222 (Q6);
  * the screen-off → 0 / screen-on → restore behaviour. Needed: the frontlight stays on at screen-off on GSIs.
  * To make the **system brightness slider** and **Night Light** work without smali or a HAL, observe
    `Settings.System.SCREEN_BRIGHTNESS` and the Night Light settings and map them to cold/warm. This is the A9 split
    done at app level. Reason: our stock lights HAL (`android.hardware.lights-service.mediatek`) only knows
    `/sys/class/leds/*`, not lm3630a.
  * In KOReader, bigmelight.koplugin with the path and max patched works today.

### 6.5 Colour, saturation and contrast on Kaleido

* **GSI-level, likely working now** (Q5):
  * SF 1022 (saturation 0–2) and `persist.sys.sf.color_saturation`;
  * SF 1015 matrices (micheal65536 contrast and RGB maps).

  Re-apply after DisplayTransformManager events.
* **Kernel-level**: `eink_debug/{saturation,contrast,brightness,global_dither}` and `panel_type`. Closer to stock's
  per-mode behaviour. Test with single writes after reading the current values. Stock defaults: color_enhance 70
  (comic 90), comic contrast 90.
* **Reading side**: **derainbowify.koplugin** (GPL-3.0) for KOReader.

### 6.6 Behaviour when the screen sleeps

* **Why it goes black:** AOSP draws a black ColorFade "cool-down" surface before `STATE_OFF`. In A16
  `DisplayPowerController`, `mColorFadeEnabled` is false when `config_displayColorFadeDisabled=true`. In that case
  it sets the fade level to 0 and turns the screen off without drawing the black surface.
* **Fix:** a framework-res **RRO overlay** in the Magisk module (`config_displayColorFadeDisabled=true`) should keep the
  last frame. This replaces the A9's ColorFade smali patch, and the HBPC notes' "Force allow AOD". Verify with Q8.
* **Screensaver:** on `ACTION_SCREEN_OFF` the app shows a full-screen bitmap overlay with mode 180 or GC16, like stock
  `com.xrz.standby` (`/sdcard/cover.jpg`). The shim holds the power-off until it is committed. The frontlight goes to 0.
* **Optional:** farfromrefug's LSPosed "read while asleep" (volume or BT keys turn pages with the screen off).

### 6.7 Stylus latency

* **Now:** A2 (16) or HANDWRITE (1029) while the pen is down, then a GL16/GC16 cleanup of the dirty rect on pen-up,
  with `clean_a2=1`. Detect the pen from the Wacom event node through the daemon. Works with any notes app.
* **Later:** a small root daemon modelled on `handwrittenservice`. It reads the Wacom node, rasterises strokes into a
  dma-buf, and calls `DRM_IOCTL_EINK_UPDATE` with small rects in A2/DU/1029. `libxrz_predict.so` is portable for
  prediction, as a Bigme binary for private use. It needs a client protocol, and the shim must avoid overwriting the
  stroke rect until the app's frame catches up. Bigme does that with the HANDWRITTEN flag.

### 6.8 Skip

* vbbot's SF source patch (#4).
* The a9 system.img patcher and the services.jar smali (the A9 Night Light split and static AOD). The Magisk, RRO and
  app equivalents above cover them.
* Writing `xrz_display_policy_service` (absent on the GSI).
* `vendor.xrz.patch.fcmt/frfsh` (no consumer in our HWC).
* `/sys/class/backlight/aw99703*` (Hisense).

---

## 7. Open questions

Probes are read-only unless marked. Any write needs per-operation approval under the project rules.

1. **Q1.** Does `vendor.xrz.global_refresh_mode` change the mode on our GSI? Does the HWC read it live or only at
   init? And does desc 71 have any effect under all-CLIENT composition? Use logcat and the HWC dump "Refresh Mode"
   column.
2. **Q2.** What is the best native clear: `force_global_refresh_mode` + SF 1008/1006, or shim full-rect GC16, or the
   `FORCE_REFRESH` flag through desc 71? Rule out `manual_refresh`, which is unreliable on the HiBreak.
3. **Q3.** Who consumes `vendor.xrz.patch.fcmt/frfsh`? Probably micheal65536's own ROM. Low priority.
4. **Q4.** Does the HWC's auto-clean counter (`clean_frequency`, "need auto global flush") run on the GSI commit path?
5. **Q5.** Do SF 1022/1015 changes reach the panel? Compare `screencap` with a photo. The HWC advertises
   `check_skip_client_color_transform`, so confirm that SF still bakes the matrix into the client target. What do the
   kernel `saturation`, `contrast` and `panel_type` nodes do on the B1051C?
6. **Q6.** Frontlight: the exact ranges on our unit (dbenjaminb says 0–222; our log has a "300/255" reading). Do
   `/dev/lm3630a` and `/sys/class/leds/lcd-backlight` exist, and what do they drive?
7. **Q7.** Which EPD PMIC is on the B1051C: TPS65185 or SYV776A (`ls /sys/bus/i2c/devices/*/name`)? This decides
   whether micheal65536's rail power-off and VCOM-read paths apply. Never write VCOM.
8. **Q8.** What does the panel show after screen-off on the GSI today: black, white or the last frame? This decides
   whether the ColorFade RRO is needed.
9. **Q9 (answered offline).** Stock `handwrittenservice` is not portable as a binary. Its method (a direct
   `DRM_IOCTL_EINK_UPDATE`) is. Still open: do the eink ioctls need DRM master or `DRM_AUTH`? Check the kernel's
   ioctl table offline.
10. **Q10.** Is debugfs mounted and writable from Magisk's context on our boot? (`CONFIG_DEBUG_FS=y`; the HBPC GSI
    could read `/sys/kernel/debug/dri/0/state`.)
11. **Q11.** KOReader redraw: does vbbot issue #6 (stale frames until an overlay appears) happen with our shim? If so,
    does "Show taps", or a periodic SF 1006, fix it?
12. **Q12.** Is TD16 signed with the public AOSP test keys (`ro.build.tags`)? If so, a platform-signed app could run as
    system without root (the InkPalm approach). That is an alternative to a root daemon, with its own risks.
13. **Q13.** Licensing: hwcshim, hibreak-eink-manager, inksdk and hibreak_pro_patches have no license. Fine for
    private use. Ask the authors before publishing anything that bundles their code.

---

## 8. Sources

**Local clones** (`~/Desktop/Bigme/work/ref/`):

* `hibreak-pro-color/{CLAUDE.md,STATE.md,docs/*.md,patches/vendor-xrz-property-context.md,tools/hwcshim/{README.md,hwcshim.cpp},bin/apply-xrz-props}`
* `BigmeB1051CProPatcher/{README.md,a9_eink_daemon/*,sepolicy/*,a9_accessibility_service/**}`
* `HibreakProPatchingService/**` (diffed against the B1051C fork)
* `hibreak_pro_patches/001-permanently_disable_hw_overlays.patch`
* `hibreak-eink-manager/app/src/main/**`
* `inksdk/{README.md,docs/bigme-architecture.md,docs/bigme-sdk-reverse-engineered.md}`
* `hibreak-android-auto-fix/{README.md,TECHNICAL.md,*.zip:repo/LICENSE}`
* `bigme-ota-client/{README.md,LICENSE}`

**Local B1051C evidence (read-only):**

* `research/getprop_stock_2026-10-17.txt`, `research/super_eink_strings.txt`,
  `research/dumpsys_surfaceflinger_stock_2026-10-17.txt`, `research/kernel_config_4.19.191.txt`,
  `research/phase-c/settings-changes.log`, `docs/STATUS.md`
* `work/vendor_files/lib64/hw/hwcomposer.mt6877.so` (strings)
* `work/super/vendor_b.img` (`debugfs cat` of the lights HAL)
* `work/super/system_b.img` (stock; `handwrittenservice`, its `.rc`, `libxrz*.so`, `/system/etc/display_policy`)
* `research/boot_b_after_official.bin` (kernel strings; decompressed in the scratchpad only)
* `work/module/b1051c-eink-compat/*`

**AOSP `android16-release`:**

* https://android.googlesource.com/platform/frameworks/native/+/refs/heads/android16-release/services/surfaceflinger/SurfaceFlinger.cpp
* https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/services/core/java/com/android/server/display/color/DisplayTransformManager.java
* https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/services/core/java/com/android/server/display/DisplayPowerController.java
* https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/core/res/res/values/config.xml

**GitHub:** every repository URL in §1a and §1b, plus:

* https://github.com/micheal65536?tab=repositories
* https://github.com/TrebleDroid/treble_app (no E Ink code)
* https://github.com/vbbot/HibreakProPatchingService/issues/6 and /issues/9

**XDA** (read via web.archive.org snapshots):

* https://xdaforums.com/t/bigme-b1051c-pro-development-thread.4745663/
* https://xdaforums.com/t/bigme-hibreak-pro-development-thread.4731041/
* https://xdaforums.com/t/bigme-hibreak-pro-dimensity-900-mt6877-e-ink-phone.4723924/
* https://xdaforums.com/t/bigme-hibreak-pro-color-color-s-root-magisk-debloat-general-impressions.4764527/
* https://xdaforums.com/t/bigme-hbp-android-16.4788393/
* https://xdaforums.com/t/bigme-hibreak-root-mediatek-6765.4697830/

**Blogs:**

* https://vbh.ai/hibreak-pro-guide-4-flashing-the-android-15-gsi/
* https://vbh.ai/hibreak-pro-gsi-release/
* https://vbh.ai/unlocking-the-bootloader-and-rooting-the-hibreak-pro/

**Not reachable:** reddit.com/r/Bigme and r/eink (403 / anti-bot), 4PDA (403).

The text the web agents captured is in the session scratchpad:
`/private/tmp/claude-501/-Users-owner-Desktop-Bigme/25eca8b2-1aea-45dc-8b97-e26ee6409819/scratchpad/web/` and
`…/scratchpad/dl_*`.
