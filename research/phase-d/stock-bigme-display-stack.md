# Stock Bigme B1051C Pro display stack: how each E Ink feature works (offline analysis, 2026-10-18)

How the stock firmware (B1051C_R_4.9.0: Android 14 system on the MT6877 Android 12 vendor, kernel 4.19) implements each
user-visible E Ink feature. The goal is to rebuild these features on a TrebleDroid A16 GSI.

**Method.** Everything here comes from reading the stock ext4 images offline with `debugfs`. No device was touched.
- **Images read:** `work/super/{system,system_ext,product,vendor}_b.img`.
- **Java code:** decompiled with jadx 1.5.6. This covers `framework.jar`, `services.jar`, `xrz.framework.server.jar`,
  XrzSettings, xSettings, xMenu, StandbyApp and MtkSystemUI.
- **Native code:** disassembled with Apple llvm-objdump. A small helper resolved adrp/add string references.
- **Working files:** scratchpad `.../scratchpad/stock-ui/`. It holds extracted files in `x/`, decompiled sources in `dec/` and
  annotated disassembly in `tools/*.ann`.

**Evidence labels used throughout:**
- **[V]**: verified in the image. A file, class or address is cited. Addresses are file virtual addresses in that ELF.
- **[I]**: inferred from code shape or context. Not confirmed.
- **[R]**: reported by the reference repos (HiBreak family) or earlier notes. Not re-verified here.

---

## (a) Component inventory

### System partition: Bigme ("xrz") code that a GSI loses

| Component | Path | Role |
|---|---|---|
| xrz system services (source of truth) | `services.jar`: `com.android.server.XrzSystemServer` + `xrz.framework.server.*` (also shipped as `/system/framework/xrz.framework.server.jar`) | **[V]** `XrzSystemServer.startOtherServices()` registers four binder services: `xrz_display_policy_service` (DisplayPolicyService), `xrz_app_freeze_service`, `xrz_split_screen_service`, `xrz_system_proxy_service`. The copies in services.jar and the standalone jar are the same code; the services.jar copy is an R8/optimised build (spot-diffed DisplayPolicyController, DatabaseHelper and DisplayPolicyService). |
| DisplayPolicyService | `xrz.framework.server.DisplayPolicyService` | **[V]** Per-app display policy engine. It watches the top task (TaskStackListener, ProcessObserver) and on every change runs `DisplayPolicyController.applyDisplayPolicy()`, which writes props, debugfs nodes and SF calls (see (b)). It also relays system gestures to `IGestureListener`s and toggles global handwriting. While `vendor.xrz.hwc_idle==0` it holds a 4 s `hwc_busy_lock` wakelock so the panel can finish updating. Binder descriptor `xrz.framework.manager.IDisplayPolicyManager`; transaction codes are listed in (c). |
| DatabaseHelper | same jar | **[V]** `/data/system/disp_policy.db`, table `policy_org`, seeded from `/vendor/etc/display_policy` (checked first) or else `/system/etc/display_policy`. Details in (c). |
| Framework API | `framework.jar` classes5.dex: `xrz.framework.manager.{XrzEinkManager, XrzEinkManagerInternal, EinkRefreshMode, DisplayPolicy, DisplayPolicyManager, I*}`, `com.xrz.{HandwrittenClient, IHandwrittenService, IClient}` | **[V]** The Java API used by Bigme apps. Natives live in `libXrzFramework_runtime.so`. |
| AOSP classes Bigme modified | framework: `View`, `ViewGroup`, `ViewRootImpl`, `Window`, `WindowManager.LayoutParams` (+`mRefreshMode`, parcelled), `SurfaceControl` (+`nativeSetRefreshMode`, refreshMode arg in `nativeCreate`), `SurfaceView`, `PopupWindow`, `Activity`, `Editor`, `HandwrittenView`, `KeyEvent` (+keycodes 10016-10021), `android.hwebook.Xrzebook`. services: `WindowSurfaceController`/`WindowStateAnimator`/`Session` (refresh mode → SF transaction), `PowerManagerService` + new `PowerStandbyManager`, `DisplayPowerController` (frontlight, sleep logo), `ShutdownThread` (power-off image), `PhoneWindowManager` (keys), `SystemGesturesPointerEventListener`, `DisplayManagerService` | **[V]** These are the hooks the features depend on. |
| Bigme SurfaceFlinger | `/system/bin/surfaceflinger` (stripped, static) | **[V]** Adds AIDL `ISurfaceComposer` methods 61-65. Has a per-layer refresh mode that it forwards to the composer as command `0x800`. Runs GPU colour/contrast/brightness/saturation and dither shaders on the client composition, with LUTs `/system/etc/g_c001` and `g_c002`. Throttles fps from `vendor.xrz.fps_limit{,_for_handwriting}`. |
| Bigme libgui | `/system/lib64/libgui.so` | **[V]** `SurfaceComposerClient::setLayerRefreshMode(String8,uint)`, `::repaintEverything()`, `Transaction::setRefreshMode(sp<SurfaceControl>,int)`, an extra refresh-mode arg on `createSurfaceChecked`/`BpSurfaceComposerClient::createSurface`, and `BpSurfaceComposer::{remoteRepaintEverything 61, remoteSetLayerRefreshMode 62, startMirrorCapture 63, stopMirrorCapture 64, remoteForceResync 65}`. |
| JNI for XrzEinkManager | `/system/lib64/libXrzFramework_runtime.so` | **[V]** `nativeForceGlobalRefresh`, `nativeSetLayerRefreshMode`, `nativeSetAutoCleanEnable`, `nativeSetAntiFlickerEnable`, `nativeSet/IsAntialiasEnable`, `nativeIsAntialiasSupport` (details in (b)). |
| Frontlight/eBook JNI | `/system/lib64/libXrzebook_jni.so` ← `android.hwebook.Xrzebook` | **[V]** `/dev/lm3630a` ioctls: 0x7901 cold, 0x7902 warm, 0x7905 screen state. `/dev/disp` ioctl 0x408 (panel value). Also VCOM and pen calibration helpers. |
| Pen fast path | `/system/bin/handwrittenservice` (rc: `handwrittenservice -p /dev/input/event0`, class late_start), `libHandwritten_jni.xrz.so`, `libXrzInput_jni.xrz.so`, `libxrzUtil.so`, `libxrz_predict.so`, `libxrzRender.so` | **[V]** A standalone AIDL service `com.xrz.IHandwrittenService`. It reads the pen evdev itself, draws into an ION shared canvas and commits straight to the panel with `DRM_IOCTL_EINK_UPDATE` (strings "commit frame: rect(...) mode=0x%x", "DRM_IOCTL_EINK_UPDATE", "Choose pen device"). It reads `vendor.xrz.{fps_limit_for_handwriting, input_predict_disabled, disable_commit, auto_clean_*, force_global_refresh_mode, gray_256_dither, global_*}` and `persist.vendor.xrz.global_dark_level`. |
| init | `/system/etc/init/init.eink.rc` | **[V]** Mounts debugfs and sets `/sys/kernel/debug` to 0777. Sets `/dev/lm3630a` to 0666, `/sys/power/normal_standby` to 0666, and `eink_debug/{clean_a2,anti_flicker,anti_alias}` to 0666. Sets `/sys/class/pen_switch/xy_calibration` to 0777 and creates `/data/pen_XYCalibration`. Creates `/data/system/devices/keylayout` (user key remaps). Toggles antutu CPU/GPU boost on `vendor.xrz.antutu_test`. |
| | `/system/etc/init/xrzUpdater.rc` + `/system/bin/xrz_updater` | **[V]** Boot logo and waveform update services (`--update-logo` / `--update-waveform /data/*-verified.bin`). |
| Props | `/system/build.prop` lines 247-305 | **[V]** All `ro.vendor.xrz.*` defaults, the 9 build-time `vendor.xrz.*` defaults and the `sys.{maga,comic,video}_*` colour-mode presets are set here, in **system**, not vendor. They are therefore absent on a GSI. |
| SELinux | `plat_property_contexts` 1575-1579: `vendor.xrz.`, `persist.vendor.xrz.`, `ro.vendor.xrz.`, `pm.boot.` → `vendor_xrz_prop`. `plat_service_contexts` 105-109: `handwrittenservice`, `xrz_*_service` | **[V]** Defined in the system policy. The vendor policy has 0 xrz entries, so on a GSI these props get no context and the vendor HWC cannot set them. |
| Data | `/system/etc/display_policy` (JSON, 206 packages), `/system/etc/g_c001` and `g_c002` (512×512 32-bpp BMP 3D-LUTs, 64³ as 8×8 tiles) | **[V]** |

### Bigme apps (UI)

| App (package) | Path | Display-related role |
|---|---|---|
| **XrzSettings** (`com.xrz.settings`, uid system) | `/system/app/XrzSettings` | **[V]** Provides `content://com.xrz.SettingProvider`. `call("setting_einkcenter")` opens **`com.xrz.settings.eink.EinkCenterDialog`** (the "Eink Center"), and `call("setting_globalrefresh")` runs `forceGlobalRefresh(4)`. Also contains `AppRefreshSetActivity`, the screensaver settings and online "user eink configs" (EinkModel; uploads and downloads per-app JSON). |
| **xMenu** (`com.xrz.sys.control`) | `/system/app/xMenu` | **[V]** The **Control Center** (`ControlCenterActivity`/ControlCenterDialog), shown in place of the QS pull-down. SystemUI calls `content://com.xrz.sys.control.provider` `showMenuControl`. Contains the cold/warm frontlight sliders and light presets (`s0/e.java` = LightUtils), plus items: Eink center, Color/Gray, Color inversion, Touch on/off, Global handwriting, … |
| **xSettings** (`com.android.settings`, Bigme-modified AOSP Settings) | `/system/priv-app/xSettings` | **[V]** A second Eink Center, `com.xrz.settings.einkcenter.EinkCenterDialog`, opened from gesture or custom-key actions in `helper/GestureHelper`. Also `LightHelper` (frontlight), `FrameworkHelper.refresh()` = `forceGlobalRefresh(4)`, and `setBackHomeAutoClean` → `vendor.xrz.enable_home_clean`. |
| **StandbyApp** (`com.xrz.standby`) | `/system/app/StandbyApp` | **[V]** Sleep/screensaver image. Provides `content://com.xrz.LogoProvider` (`show_logo`/`hide_logo` → `ScreenSaveActivity`, picture `/data/xrz/standby.png` or clock/translate styles). |
| MtkSystemUI | `/system_ext/priv-app/MtkSystemUI` | **[V]** Pull-down opens xMenu. The NavigationBar refresh button broadcasts `com.xrz.GlobalRefresh`. |
| GlobalWritingService (`com.xrz.globalwritingservice`) | `/system/app/GlobalWritingService` | **[V]** Global handwriting overlay. DisplayPolicyService sends `com.xrz.eink.display.policy` / `REQUEST_GLOBAL_HANDWRITTEN` for listed activities (OneNote, WPS, WeRead…). |
| MTKEinkTest (`com.example.einktest`) | `/system/app/MTKEinkTest` | **[V]** Factory test. Reads `eink_debug/{vcom,waveform,machine_waveform}`. |

### Vendor partition (kept on a GSI)

| Component | Role |
|---|---|
| `/vendor/lib64/hw/hwcomposer.mt6877.so` | **[V]** MTK HWC2 with Bigme's `CommitPolicy` (the E Ink policy engine) and `DrmDevice::triggerEinkCommit`/`mtk_commit`. It reads about 25 `vendor.xrz.*` props (table in (b2)). Exposes a vendor HWC2 function, **descriptor 71 = `HWCMediator::layerStateSetRefreshMode(hwc2_device*, display, layer, int32 mode)`**: `hwcGetFunction` @0x710b8 accepts descriptors 1..71, and the 71 thunk @0x71de8 jumps to HWCMediator vtable+0x1e0. It calls `HWCLayer::setRefreshMode` @0xd46dc, which stores the mode at layer+0xdfc and sets state bit 0x20000. |
| `/vendor/bin/hw/android.hardware.graphics.composer@2.3-service` | **[V]** Bigme-modified HIDL composer. Its command engine accepts a **non-standard command opcode `0x800` (word `0x08000001`, 1 int arg)** @0x14210 and dispatches it to HAL vtable +0x160. `HwcHal::setLayerRefreshMode` @0xaa34 logs "HwcHal setLayerRefreshMode mode=%x" and calls the HWC2 function pointer obtained with `getFunction(71)` @0xdb20. The 0x800 → setLayerRefreshMode link is **[I]**: packed relocations prevented resolving the vtable. |
| `/vendor/lib64/libimage_process.so` | **[V]** `xrz::EffectHandler`. The HWC only calls `convertToGray` and `imageSmoothing`. The library also exports `imageProcess(src,dst,Rect,int,int,int,int,bool)`, `adjustContrast`, `colorDither`, `loadLut`, `process3DLUT` and `imageCompare`, which the HWC does not use. These are CPU equivalents of SF's shaders, available to a vendor shim. |
| `/vendor/lib64/hw/lights.mt6877.so`, `lights-service.mediatek` | **[V]** Stock MTK. Uses `/sys/class/leds/*` (lcd-backlight, RGB LEDs). **Not used for the frontlight**, which goes through Xrzebook → `/dev/lm3630a`. |
| Kernel | **[R]** DRM E Ink ioctls and `/sys/kernel/debug/eink_debug/*` (23 nodes per notes). The kernel blob in `boot_b_after_stock.bin` had no recognisable compression magic (high entropy from offset 0), so it was not analysed here. |

---

## (b) Feature table

### Primary chains (verified)

- **Policy apply** (`DisplayPolicyController.applyDisplayPolicy`, xrz.framework.server, verified): runs on every top-app change and on every per-app setter.
  1. `p = getPolicyByColorMode(policy)`.
  2. `setprop vendor.xrz.global_contrast` = `p.contrast` if `is_contrast_setting==1`, else 0.
  3. `setprop vendor.xrz.global_color_mode` = colorMode; `vendor.xrz.scroll_flip_type` = scrollFlip.
  4. `vendor.xrz.global_text_enhance` = textEnhance if (>0 and mode≠179), else 0.
  5. If `is_refresh_setting==1`: `XrzEinkManagerInternal.setLayerRefreshMode(pkg, refreshMode)` (→ SF code 62) and `setprop vendor.xrz.clean_frequency` = refresh_frequency; else clean_frequency = 0.
  6. Anti-alias is enabled only when mode==178, from `vendor.xrz.anti_alias_enabled` (if ≥0) or else `p.antiAlias`. It writes `eink_debug/anti_alias`.
  7. `persist.vendor.xrz.global_dark_level` = darkLevel.
  8. If mode ∉ {178,179}: `vendor.xrz.disable_cursor_blink=1`. Otherwise colorEnhance is forced to 0 and disable_cursor_blink=0.
  9. `vendor.xrz.global_color_enhance` = colorEnhance; `vendor.xrz.global_brightness_level` = brightness.
  10. `setAutoCleanEnable()` → `vendor.xrz.auto_clean_enabled` + `eink_debug/clean_a2`.
  11. `setAntiFlickerEnable()` → `vendor.xrz.anti_flicker_enabled` (prop only).
  12. `Settings.Secure.high_text_contrast_enabled` = bleachMode && textPlus.
  13. `Settings.System.app_bleach_filter = "pkg,mode,icon,cover,bg"` + broadcast `com.xrz.eink.appcustom`.
  14. If `needRepaint`: `forceGlobalRefresh(0)`.
- **forceGlobalRefresh(mode)** (`libXrzFramework_runtime.so` @0x3d90): `property_set("vendor.xrz.force_global_refresh_mode", mode)`, then `SurfaceComposerClient::repaintEverything()` (AIDL code 61). On the next frame the HWC reads the prop, sets it back to `"-1"` and does a full-screen update. A value of 0 means "current mode, full screen". `mode & 0xffff` = waveform; mode 4 also sets the GC16-clean flag; `mode>>29` = dither.
- **Per-layer refresh mode**, three entry points into SF, all ending at the HWC2 layer mode:
  - (i) at layer creation: `SurfaceControl(...)` asks `IDisplayPolicyManager.getRefreshModeForPackage(name)` (code 4) and passes it in `nativeCreate`/`createSurface`;
  - (ii) `remoteSetLayerRefreshMode(name, mode)`, AIDL `android.gui.ISurfaceComposer` code **62** (args: String name, int mode). SF matches the layer names `pkg…`, `SurfaceView - [pkg`, `HandwrittenView - [pkg`, `HandwrittenView Background - [pkg` (SF @0x2700c0);
  - (iii) window or view level: `WindowManager.LayoutParams.setRefreshMode` → `Session.setRefreshMode` → `WindowSurfaceController.setRefreshMode` → `Transaction::setRefreshMode` (libgui @0x106800: `layer_state.what |= 1<<49`, field +0x330).

  In all three, SF then writes composer command `0x08000001, mode` per layer (SF writer @0x1ab1f0). This reaches HWC2 descriptor 71 and sets `HWCLayer+0xdfc`. The stock `dumpsys SurfaceFlinger` shows a "Refresh Mode" column, e.g. `(0xb2,0xb2)` for Launcher, StatusBar and Wallpaper [V, research dump l.1344-1353].

### Feature-by-feature

| Feature | UI on stock (label) | Mechanism chain | Low-level knob(s) and values | Reproducible on GSI? |
|---|---|---|---|---|
| **Refresh mode (per app)** | Eink Center → "Refresh mode": **HD 256** (−2147483471 = 0x800000B1 = DITHER_256 \| HD 177), **Fast** (178, labelled "Fast" even though the enum calls it NORMAL), **Extreme** (179), **Regal** (180), **Hyper** (183). [V] `EinkCenterDialog.initRefreshModeSet`/`getRefreshModeName`, strings `refreshmode_*`; `EinkHelper.checkRefreshMode` accepts only these 5 values, else 178. | UI → `setRefreshModeForPackage` (code 9) → DB, then `applyDisplayPolicy` → `setLayerRefreshMode(pkg,mode)` → SF code 62 → composer 0x800 → HWC desc 71 → `CommitPolicy` picks the **focused layer's** mode ("change focus refreshMode…") [V hwc @0xef584]. | HWC per-frame mode. Low 16 bits = mode; bits 29-31 = dither (`>>29`: 1 TEXT, 2 PICTURE1, 3 PICTURE2, 4 = 256-grey). Flags 0x10000-0x8000000 (layer types, table in (b2)). If no layer sets a mode, HWC uses `ro.vendor.xrz.default_refresh_mode` (default 178) [V ctor @0xed664]. | **Yes, without SF:** `setprop vendor.xrz.global_refresh_mode <mode\|flags>` overrides all layers whenever ≠0 and the frame is not FORCE_REFRESH [V @0xef804-ef870]. A daemon writes it on top-app change. Caveat [I]: a global 180 becomes 177 unless some visible layer itself carries 180 [V @0xef878-ef894; reading of the flag is I]. **True per-layer modes** need a HWC shim calling desc 71 (as on the HiBreak Pro Color). |
| Other waveforms (A2 16, DU 2, GC16 4, GL16 32, GU16 132, INIT 1, CLEAN 176, HD 177, 181, 182, AUTO 32768) | **Not exposed** in the UI. | GC16 (4) is used by full refresh (`forceGlobalRefresh(4)`) and by the power-off image window (`ShutdownThread`: `setRefreshMode(4)`). The handwriting service uses 1029/1030/132/4. 181 = antutu override [V setCommitInfoForJob @0xf0394]. 182 = set when an internal flag is true [V @0xefde8]. Boot animation 180 and logo 0x800000B1 come from `vendor.xrz.bootanimation_refresh_mode` / `logo_refresh_mode` (read by `libmtkbootanimation.so`, system). | Same knobs as above. Kernel `eink_debug/global_mode` is **not used by any stock userspace** [V grep of all images]. | Yes, via global_refresh_mode or desc 71. The A16 bootanimation won't set a mode (cosmetic). |
| **Full refresh (manual)** | Eink Center refresh icon (`iv_refresh` → `FrameworkHelper.forceRefresh()`); NavigationBar button (broadcast `com.xrz.GlobalRefresh`; **every Activity** registers a receiver in `Activity.onStart` → `forceGlobalRefresh(4)` [V Activity.java:283-288,978]); Control Center/SettingProvider `setting_globalrefresh`; gesture or custom-key action 6/7; BT pen `KEYCODE_BUTTON_12` (199) → `com.xrz.GlobalRefresh` [V PWM ~l.3910]. | `forceGlobalRefresh(4)` → `vendor.xrz.force_global_refresh_mode=4` + SF repaintEverything (AIDL code 61) → HWC full-screen GC16, then resets the prop to -1 [V hwc @0xef89c-ef94c]. | `vendor.xrz.force_global_refresh_mode` (−1 idle; 0 = full screen with current mode; N = mode N; top 3 bits = dither). | **Yes:** `setprop vendor.xrz.force_global_refresh_mode 4`, then force one composition (any redraw; AOSP SF debug codes may work, see open questions). Needs prop write access (Magisk/root daemon). |
| **Auto clean** | Eink Center → Display Effect → "Auto clean" (hidden for HD256/Regal). Toggling it also forces a GC16 refresh. Default `ro.vendor.xrz.default_auto_clean=1`. | `setAutoCleanForPackage` (code 31) → `applyDisplayPolicy` → `setAutoCleanEnable(b)`: `vendor.xrz.auto_clean_enabled=0/1` + JNI writes `"1"/"0"` to `/sys/kernel/debug/eink_debug/clean_a2` (open O_RDWR) [V libXrzFramework_runtime @0x3e10]. HWC: if mode ∈ {178,179} and auto_clean_enabled≥1 (and `auto_clean_check_disable`≤0, with split-screen exception) → "disable rect commit, reason: auto clean", i.e. full-region commits instead of partial rects [V @0xef9bc-efa68]. | `vendor.xrz.auto_clean_enabled`, `vendor.xrz.auto_clean_check_disable`, `eink_debug/clean_a2` (kernel side [R]: A2/fast-mode residue cleanup). | **Yes:** prop + root write to clean_a2 (debugfs perms: stock rc chmods 0666; a GSI needs its own rc or a root daemon). |
| **"Full refresh frequency"** (refresh_frequency) | Eink Center slider `seekbar_refresh_frequency` 0-50 (xSettings variant default 20); "Full refresh frequency". JSON default −1 for most apps; 30 for `com.xrz.ebook`/`com.xrz.xreader`; `ro.vendor.xrz.default_refresh_frequency=0`. | `setRefreshFrequencyForPackage` (code 10) → `vendor.xrz.clean_frequency=N`. HWC `needCleanScreen` @0xee89c returns true when the counter reaches N, which triggers "need auto global flush" (full-screen update). The counter (`countFlushLimit` @0xee754) counts a frame only if its dirty area is **≥40% of the screen** (const 0.4 @0x46510), at least **800 ms** after the last counted one, **and only while `vendor.xrz.check_commit≠0`**. `PowerManagerService.userActivityInternal` sets `check_commit=1` on BUTTON/TOUCH user activity and resets it to 0 after **800 ms** [V PMS l.1361, 3767]. A GC16 frame resets the counter. `vendor.xrz.disable_auto_global_refresh=1` disables it. | **Meaning: "do a full refresh after N user-initiated page-sized updates"**, not an fps value. 0 or −1 = off. | **Yes [I]:** set `clean_frequency`, and have the daemon pulse `check_commit=1` for 800 ms on touch/key input (getevent or input monitor). Leaving check_commit=1 permanently would also count animations. |
| fps limit | Not a user setting. | HWC writes `vendor.xrz.fps_limit` = "20" when `vendor.xrz.antutu_test≥1`, else "0", **on every commit** [V @0xf0394-f03c0]. `DisplayManager::updateRefresh` @0x99478: fps≠0 → vsync period 1e9/fps + usleep. SF reads `vendor.xrz.fps_limit_for_handwriting` (priority) or `fps_limit` to override its vsync period [V SF @0x222ae4]. `persist.vendor.xrz.fps` sets the reported display rate (default 60) [V hwc @0x989e4]. | Same props. `antutu_test` is set by DisplayPolicyService when the top package starts with `com.antutu`. | The HWC part survives but is useless. The SF handwriting throttle is lost. |
| **Anti-shake** (= anti-flicker) | Eink Center → Display Effect → "Anti-shake" (`anti_flicker_switch`; string `anti_flicker`="Anti-shake"). Default `ro.vendor.xrz.default_anti_flicker=1`. | `setAntiFlickerForPackage` (code 32) → `vendor.xrz.anti_flicker_enabled=0/1` (prop only; `nativeSetAntiFlickerEnable` exists but this path does not call it). HWC `setCommitInfoForJob` @0xf0308: if the prop ==1 and the commit is not a rect commit → CommitInfo+0x8c=1. `DrmDevice::triggerEinkCommit` @0xf5ddc then calls `mtk_set_anti_flicker(b)` @0xf1210, which writes "1"/"0" to `eink_debug/anti_flicker` **before every commit**. | `vendor.xrz.anti_flicker_enabled`; kernel node `anti_flicker`. | **Yes:** prop only (the HWC writes the node itself). |
| **Anti-aliasing** | xSettings Eink Center "Anti-aliasing" (`antialias_switch`); `XrzEinkManager.setAntialiasEnable` (stored in MMKV `custom_xrz_system_antialias`). Default 0. | Prop `vendor.xrz.anti_alias_enabled` + JNI writes `eink_debug/anti_alias` "1"/"0". The controller re-applies it per app and **only when mode==178** [V DisplayPolicyController]. | `eink_debug/anti_alias` (kernel semantics [R]). | **Yes:** root write. |
| **Colour modes per app** | Eink Center top row: Default / Magazine / Comic / Video / Custom. In XrzSettings' dialog **the labels of rb_magazine and rb_comic are swapped**: `rb_magazine` (sets MAGAZINE=1) shows "Comic" and `rb_comic` (sets COMIC=2) shows "Magazine" [V `res/layout/dialog_eink_center.xml`]. xSettings' copy labels them correctly. | `setColorModeForPackage` (code 33). `DatabaseHelper.getPolicyByColorMode()` replaces {autoClean, antiFlicker, refreshMode, contrast, colorEnhance, brightness} with: DEFAULT (0) → the package's display_policy JSON entry (or init defaults); MAGAZINE (1) → `sys.maga_*`; COMIC (2) → `sys.comic_*`; VIDEO (3) → `sys.video_*`; CUSTOM (4) → the stored per-app values. Global "Bulk Settings" (`setGlobalPolicy`, code 37) overlays any field ≥0. Then `applyDisplayPolicy`. `vendor.xrz.global_color_mode` reaches SF, where colorMode 2 selects LUT `g_c002` and skips contrast/brightness/saturation (see colour enhancement row). | **Presets [V system build.prop 269-286]:** MAGAZINE: refresh −2147483471, contrast 0, color_enhance 70, brightness 0, auto_clean 0, anti_flicker 0. COMIC: refresh 180, contrast 90, color_enhance 90, auto_clean 0, anti_flicker 0. VIDEO: refresh 179, contrast 0, color_enhance 70, auto_clean 1, anti_flicker 1. *(text_enhance presets are read and discarded.)* | Mode switching: yes, rebuilt in our daemon (setprops + mode). The visual effect needs the colour pipeline below. |
| **Colour enhancement / saturation ("Vivid enhancement")**, **contrast ("Contrast setting")**, **brightness ("Color brightness")** | Eink Center → Display Effect sliders `vividness`, `darkcolor` (the contrast slider; log "darkcolorSeekbar"), `brightness`, each 0-100. "Vivid enhancement (Invalid in Fast Mode, Extreme Mode)". Defaults: color_enhance 70, contrast 0, brightness 0. | Codes 27/12/28 → controller → `vendor.xrz.global_color_enhance` (forced 0 for 178/179), `global_contrast`, `global_brightness_level`. **SurfaceFlinger** @0x32a934: if `vendor.xrz.enable_gpu_image_process≥1` (stock 1) it reads those 4 props and builds a runtime shader (`createProcessShader: colorMode,saturation,contrast,brightness` @0x154ed0), applied to client composition. Stock composes 100% CLIENT ("h/w composer disabled" in the stock dump). | **Shader [V SF string @0x632b8]:** if colorMode≠2: contrast>0 → `c=1-pow(1-c, 1.001-contrast/100)`; brightness>0 → `c=pow(c, 1.001-brightness/100)`; saturation>0 → HSL-style stretch `c += (c-L)*(1/(α+1e-4)-1)` with α = S if increment+S>1 else 1-increment. Then if saturation>0 → 3D-LUT from `/system/etc/g_c001` (colorMode≠2) or `g_c002` (colorMode==2). A second dither shader uses a table plus `ditherAlpha`. | **Needs replacement.** Options: (1) a HWC shim post-processing the client target, possibly with the vendor's own `libimage_process.so` `EffectHandler::imageProcess/adjustContrast/loadLut/colorDither` [I, parameters unverified]; (2) a GLES/Vulkan pass in the shim with the copied LUT BMPs and the shader math above; (3) an AOSP SF colour matrix (`service call SurfaceFlinger 1015`) for linear saturation/brightness only (no gamma curve, no LUT). The LUT files are plain data and can be copied. |
| **Colour restoration** ("Color reproduction") | Display Effect `cb_color_reproduct` (hidden for HD256/Regal) → `XrzEinkManager.enableColorRestoration`. | `vendor.xrz.color_restoration_enabled` (global, not per app). HWC: if `vendor.xrz.hwc.color_restoration_suppoort==1` (sic; system build.prop) and mode ∈ {178,179}, CommitInfo.dither(+0x3c) = enabled ? **3** : **6** [V @0xefe58-efe94 and @0xf0318-f0340]. | Dither type sent to the kernel. | **Yes:** props (`…suppoort=1` must be supplied by the module). |
| **Image smoothing** (part of HD 256) | Implicit in mode HD 256. | `vendor.xrz.hwc.image_smoothing_suppoort==1` and dither==4 and mode==177 (HD 256 = 177 + dither 4) → CommitInfo+0x8a=1, dither=0 → `OverlayEngine::processImage` calls `xrz::EffectHandler::imageSmoothing` [V @0xf0358-f0388, @0xa4788]. HWC also reports `vendor.xrz.gray_256_dither` = "1" when dither==4, else "0" (status output) [V @0xf63d4]. | Props. | **Yes:** props. |
| **Text enhancement** | XrzSettings dialog: checkbox "Text enhancement" (`textCb`) = **app_bleach_text_plus**. xSettings (B/W layout): slider `text_enhance_bk` 0-100 = `setTextEnhanceForPackage` (code 34). | Checkbox → `Settings.Secure.high_text_contrast_enabled` (AOSP high-contrast text). Slider → `vendor.xrz.global_text_enhance`. **No consumer of `global_text_enhance` was found** in system or vendor binaries or decompiled framework/services (only the setters in the 3 jars) [V grep]. | `high_text_contrast_enabled`. | Checkbox: **yes, AOSP-native** (`settings put secure high_text_contrast_enabled 1`). Slider: nothing to reproduce on this firmware. |
| **Background bleaching / icon colour** ("Apply bleach") | Eink Center (App optimization tab): "Icon color" 0-255, "Background color" 0-255. Default `ro.vendor.xrz.default_bleach_bg_color=10`. | Codes 16/18 → controller → `Settings.System.app_bleach_filter="pkg,1,icon,cover,bg"` + broadcast `com.xrz.eink.appcustom`. **In the app process**, `View.refreshCurrentView()`: ImageView colour matrix offset **−icon** on RGB; a ViewGroup with a Bitmap/Color background gets offset **+bg** on RGB [V View.java ~l.1793-1866]. | Per-view ColorMatrixColorFilter. | **No (framework hook).** Needs LSPosed/Xposed or a global brighten pass in the colour pipeline as an approximation. |
| **Scroll optimisation** ("Page sliding optimization") | `cb_slide_opti` with "Smart refresh" (=1) / "Whole screen page flipping" (=2). | Code 35 → `vendor.xrz.scroll_flip_type`. `View.dispatchTouchEvent` → `XrzEinkManagerInternal.checkScroll` [V]. **Type 1:** while dragging (>10 px) set `vendor.xrz.is_scrolling=1`; after lift reset to 0 after 400-2000 ms depending on velocity. HWC: in HD(177)/Regal(180), is_scrolling>0 means "force normal mode for scrolling" (178), then a 400 ms follow-up [V @0xefeac-eff48]. **Type 2:** `ViewRootImpl.mIsScrolling` skips `draw()` while dragging; full redraw on lift [V ViewRootImpl l.3259]. | `vendor.xrz.is_scrolling`. | Type 1: **partly** (a daemon could set is_scrolling from accessibility scroll events; approximate). Type 2: no (framework). |
| Return-to-home clean | xSettings `setBackHomeAutoClean`. | `vendor.xrz.enable_home_clean=1` → HWC sets the clean flag (=2) when the HOME-flagged layer state changes [V @0xefccc-efcfc]. | Needs layer flag HOME 0x80000 [I: set by Bigme SF/WM, not traced]. | Only with a shim that tags the launcher layer. |
| Dark mode / inversion / grey | Control Center: "Color inversion" → `Settings.Secure.accessibility_display_inversion_enabled`; "Color/Gray" → `accessibility_display_daltonizer_enabled=1` + `accessibility_display_daltonizer=0` (monochromacy) [V xMenu `s0/g.java`, k0/a.java]. Per-app "dark level" (`app_dark_level` → `persist.vendor.xrz.global_dark_level`) exists in the API, but **no UI calls it**; only handwrittenservice reads the prop [V]. | AOSP SF colour matrix. | AOSP settings. | **Yes, AOSP-native.** |
| **Frontlight warm/cold** | Control Center: two sliders (`bar_light_cold`, `bar_light_warm`) with ± buttons, 37 steps (index 0-36), plus presets "Lights out / Daytime / Night / Bedding / Other". Also edge-slide gestures and custom keys (xSettings LightHelper). | `s0/e.java` (LightUtils): index → raw via table; `Xrzebook.setColdlight/setWarmlight(raw)` → JNI opens `/dev/lm3630a` O_WRONLY → **ioctl 0x7901** (cold) / **0x7902** (warm), arg = pointer to int [V libXrzebook_jni @0x5710/0x57e0]. Saves `Settings.System.ColdValue/WarmValue` (index) and `screen_brightness_cold/_warm` (raw). `DisplayPowerController.openLight()` on boot/wake re-applies `screen_brightness_cold` (default 210) / `_warm` (default 0) and **ioctl 0x7905** (screen state 1). `closeLight()` on screen-off sends 0x7905(0) and sets cold=warm=0 [V DPC l.446-458]. | **Raw tables [V xMenu s0/e.java]:** colour panel (screen_type>0, our unit): `{0,2,20,30,40,50,60,70,80,90,102,108,114,120,126,132,138,144,150,156,162,168,174,180,188,192,198,204,206,208,210,212,214,216,218,220,222}`; mono: max 210. Presets (cold idx, warm idx): Off (0,0), Day (30,0), Night (20,10), Bed (15,10), User (24,0). **Two independent channels; no CCT mixing.** The lights HAL is not involved. Sysfs alternative `/sys/devices/platform/11d20000.i2c1/i2c-1/1-0036/lm3630a_{cold,warm}_light` (0-222) [R, measured on device earlier]. | **Yes:** a root daemon or app uses ioctls 0x7901/0x7902/0x7905 on `/dev/lm3630a` (needs mode 0666 or root) or the sysfs nodes. **Must also switch the light off on SCREEN_OFF and restore on wake**; stock does this in DPC. |
| **Sleep / screensaver image** | XrzSettings/StandbyApp screensaver settings (picture `/data/xrz/standby.png`, clock, translate styles). | On screen-off in **super standby** (standby mode 2): `DisplayPowerController.showBackLogo()` broadcasts `android.intent.action.SHOW_BACKLOGO` and calls `content://com.xrz.LogoProvider` `show_logo` → StandbyApp starts `ScreenSaveActivity` (full-screen dialog). DPC **blocks the screen-off up to 1500 ms** ("wait logo ready", msg 100), then turns the display off, frontlight off, `vendor.xrz.touchevent.disable=1`. Wake: `hide_logo`, `HIDE_BAKCLOGO` (sic), lights back on [V DPC l.1199-1222, 1980-2003; LogoProvider]. The panel is bistable, so the last commit stays visible. Power-off and reboot: `ShutdownThread` shows `/data/xrz/poweroff.png` / `powerreboot.png` / `poweroff_nopower.png` in a window with `setRefreshMode(4)` (GC16), lights off [V]. | `PowerStandbyManager`: normal standby (1) vs super standby (2) → `PowerManagerService.nativeSetStandbyMode` → suspend service writes `/sys/power/normal_standby` [V strings in `android.system.suspend-service`]. `ro.vendor.xrz.normal_standby_enable=1` starts the network/HWC-busy monitor. | **Partly:** an app shows an image on `ACTION_SCREEN_OFF`. It must win the race before display power-off; use a wakelock delay or AOD/Dream. Whether the vendor HWC/kernel clears the panel on `setPowerMode(OFF)` is open; on the HiBreak Pro Color GSI the last frame stays [R]. normal_standby: write the sysfs node from a root daemon [I]. |
| **Pen fast path** | Bigme apps (xNote etc.) via `com.xrz.HandwrittenClient`; global handwriting (`control_global_hw`). | `handwrittenservice` (AIDL `com.xrz.IHandwrittenService`: getVersion 1, setLayoutRect 2, inValidate 3, requestCommit 4, setInputEnabled 5, setInputEnabledForClient 6, registerClient 7, unRegisterClient 8, clientConnected 9, clientDisConnected 10, setSuspendEnabled 11, setBlendEnabledForClient 12, setRecommitEnabledForClient 13, setOverlayEnabledForClient 14, setHandwrittenBuffer 15, clearOverlay 16, setAutoCleanControlEnabledForClient 17, setPredictEnabledForClient 18; callback `com.xrz.IClient.onCommit`=1) [V framework IHandwrittenService.java]. It reads the pen evdev, renders strokes into ION and commits directly with DRM_IOCTL_EINK_UPDATE, **bypassing SF and HWC**. Layer flags HANDWRITTEN 0x40000 / HANDWRITTEN_BG 0x100000 tell the HWC which layers to treat specially [V enum]. | `/dev/dri/card0` EINK_UPDATE; modes 1029 (HANDWRITE), 1030 (RUBBER), 132, 4. | **Needs a port:** a system binary plus 4 system libs plus the framework client class. The kernel interfaces exist on the GSI. AOSP stylus input works without it (higher latency). |
| Stylus / pen buttons | BT pen ("bt_key" device): `KEYCODE_BUTTON_11` (198) long press → open the app in `Settings.System.CustomAppPenLong` (also `CustomAppPenSingle`); `KEYCODE_BUTTON_12` (199) → full refresh [V PWM]. Wacom EMR side buttons: not traced (AOSP `BUTTON_STYLUS_*`) [I]. | PhoneWindowManager. | | Accessibility service or our own key handling. |
| Keys / gestures | `bt_key.kl`: scancodes 561-569/576 → POWER/BACK/HOME/PAGE_UP(_PRESS/LONG)/PAGE_DOWN(…). `hall_key.kl`: 646/647 → `HALL_SENSOR_NEAR`/`LEAVE` = **Bigme keycodes 10016/10017** (sleep / wake-by-cover). `KEYCODE_PAGE_UP_PRESS` 10018, `PAGE_DOWN_PRESS` 10019, `LONG_PAGE_UP` 10020, `LONG_PAGE_DOWN` 10021 [V KeyEvent, PWM l.3621-3658]. `gpio_keys.kl`: only VOLUME_DOWN/UP (114/115). The idc files point at `custom_gpio_keys`/`custom_bt_key` layouts in `/data/system/devices/keylayout` (user remap). **No dedicated refresh key on this unit** [V]. System gestures go WM `ISystemGestureListener` → DisplayPolicyService → xSettings `GestureHelper`, whose action codes include refresh (6/7), Eink Center (7/8), frontlight toggle (10/11) and edge-slide light. | | | AOSP kl files using these Bigme labels **will not parse** on a GSI; they need AOSP labels (e.g. map hall to `SLEEP`/`WAKEUP`, or use a lid switch). Gestures via accessibility. |
| Touch / stylus disable | Control Center "Touch" (`control_hand_touch`). | `XrzEinkManager.disableTouch` → `vendor.xrz.disable_touch`; `disableStylus` → `vendor.xrz.disable_stylus`; checked in `ViewRootImpl` (framework). `vendor.xrz.touchevent.disable` checked in ViewGroup. | | No (framework); would need an input-filter approach. |

### (b2) HWC-side controls: every `vendor.xrz.*` the vendor HWC touches

Source: string xrefs in `hwcomposer.mt6877.so` mapped to their functions, plus the disassembly reading above. **R** = read, **W** = written by the HWC. "Stock" = value on the stock device (getprop / build.prop).

| Prop | R/W | Where | Meaning | Stock |
|---|---|---|---|---|
| `ro.vendor.xrz.default_refresh_mode` | R | CommitPolicy ctor @0xed694 | Fallback layer mode (default 178) | 178 |
| `ro.vendor.xrz.screen_type` | **W** in `mtk_init` @0xf1160 (from `DRM_IOCTL_EINK_GET_TYPE` 0xC00464D0), **R** in setCommitInfoForJob @0xf02d0 | **CommitInfo+0x54 ("format") = (screen_type>0) ? 2 : 0**. In `OverlayEngine::processImage`, format==0 → CPU `xrz::EffectHandler::convertToGray` (mono path); format≠0 skips the grey conversion and commits RGBA [V @0xa4bc8→0xa4c70]. | 33 |
| `vendor.xrz.global_refresh_mode` | R | checkLayers @0xef804 | ≠0: overrides per-layer mode (low 16 bits) and dither flags (top 4 bits) | unset (0) |
| `vendor.xrz.force_global_refresh_mode` | R+W | @0xef89c, resets to "-1" @0xef90c | One-shot full-screen update (see above) | -1 |
| `vendor.xrz.auto_clean_enabled` / `auto_clean_check_disable` / `split_screen_show` | R | @0xef9bc-ef9f0 | Disable partial (rect) commits for 178/179 when auto-clean is on | 1 / – / – |
| `vendor.xrz.check_commit` / `clean_frequency` / `disable_auto_global_refresh` | R | needCleanScreen @0xee8bc-ee920 | Auto full refresh after N large user updates | 0 / 0 / – |
| `vendor.xrz.enable_home_clean` | R | @0xefccc | Clean on home layer change | 0 |
| `vendor.xrz.is_scrolling` | R | @0xefeb0 | Force 178 during scroll in HD/Regal | 0 |
| `vendor.xrz.hwc.color_restoration_suppoort` / `color_restoration_enabled` | R | @0xefe58, @0xf02c0 | dither 3 (on) / 6 (off) for 178/179 | 1 / unset |
| `vendor.xrz.hwc.image_smoothing_suppoort` | R | @0xf02b0 | HD+dither4 → imageSmoothing | 1 |
| `vendor.xrz.anti_flicker_enabled` | R | @0xf02f0 | → `eink_debug/anti_flicker` per commit | 0 |
| `vendor.xrz.disable_eink_vsync` / `disable_eink_vsync_for_fast` | R | @0xeff54/0xeff84 | Don't wait for E Ink vsync (all modes / 179 only) | 1 / 1 |
| `vendor.xrz.disable_commit` | R | @0xeebd4 | 1 = skip E Ink commits ("skip: system commit") | – |
| `vendor.xrz.antutu_test` | R | @0xf02e0 | Benchmark hack: mode 181, fps 20 | 0 |
| `vendor.xrz.fps_limit` | **W** every commit ("20" or "0") / R in updateRefresh | @0xf03c0, @0x99490 | Frame pacing | 0 |
| `vendor.xrz.driver_color_enhance` | R | @0xf03c8 | Copied into the job (CommitInfo+0x48) when CommitInfo+0x50 is set [I: kernel colour-enhance strength] | unset |
| `persist.vendor.xrz.fps` | R | @0x989e4 | Reported vsync rate (default 60) | unset |
| `vendor.xrz.update_waveform` | R+W | triggerEinkCommit @0xf5de8 | 1 → `DRM_IOCTL_RELOAD_WAVEFORM` (0xC00464D1), then reset | – |
| `vendor.xrz.gray_256_dither` | **W** | @0xf63d4 | Status: last commit used dither 4 | 0 |
| `vendor.xrz.hwc_idle` | **W** | OverlayEngine::onMessage @0x9ed3c | Status for DisplayPolicyService wakelock | 1 |
| `vendor.xrz.capture_commit`, `vendor.xrz.hwc.show_commit_fps` | R | OverlayEngine::threadLoop | Debug | – |

**Props the vendor HWC does *not* read** (system-only; set or used by SF/framework): `enable_gpu_image_process`, `global_{contrast,color_enhance,brightness_level,color_mode,text_enhance}`, `regal_support`, `logo_refresh_mode`, `bootanimation_refresh_mode`, `disable_inputmethod_fullscreen`, `light_limit_level`, `disable_cursor_blink`, `scroll_flip_type`, `touchevent.disable`, `disable_touch`/`disable_stylus`/`disable_input`, `fps_limit_for_handwriting` [V].

**Layer flags OR-ed into the desc-71 mode** (`EinkRefreshMode`, framework [V]): SURFACEVIEW 0x10000, FORCE_REFRESH 0x20000, HANDWRITTEN 0x40000, HOME 0x80000, HANDWRITTEN_BG 0x100000, MULTI_DAMAGE 0x200000, INPUT_METHOD 0x400000, AUTO_DITHER 0x800000, DISABLE_LAYER 0x1000000, OVERLAY 0x2000000, LOGO 0x4000000 (HWC → mode 177 [V @0xefe48]), CURSOR 0x8000000, DITHER_TEXT 0x20000000, DITHER_PICTURE1 0x40000000, DITHER_PICTURE2 0x60000000, DITHER_256 0x80000000, SKIP_FRAME −1.

**Kernel interfaces used by the vendor HWC** [V ioctl constants]:
- `/dev/dri/card0`: EINK_UPDATE 0xC04464CF (68-byte struct; layout in [R] hibreak-pro-color), GET_EINK_TYPE 0xC00464D0, RELOAD_WAVEFORM 0xC00464D1, WAIT_VSYNC 0xC01064D2 (`mtk_waitVSync`), CREATE_USER_FENCE 0xC00864D3, RELEASE_USER_FENCE 0xC00464D4. *(The reference notes give WAIT_VSYNC=0xC00464D1; in this HWC 0xC00464D1 is logged as RELOAD_WAVEFORM.)*
- debugfs `eink_debug/anti_flicker`.
- CommitInfo fields [V offsets]: +0x08 mode, +0x0c..+0x18 rect, +0x20 dirty-rect vector, +0x3c dither, +0x48 driver colour enhance, +0x50 enhance flag, +0x54 format, +0x58 rect-commit flag, +0x80 clean flag (1 → mode forced to 4), +0x8a smoothing, +0x8c anti-flicker.

---

## (c) Per-app policy format

**`/system/etc/display_policy`** [V]: a JSON array of 206 objects, all values as quoted strings. The parser is `DatabaseHelper.loadDefaultPolicy()`. It uses `JsonReader.nextInt()`, which accepts quoted numbers, and `nextDouble()` for `app_dpi_scale`. `/vendor/etc/display_policy` takes precedence when present. Unknown keys only log "invalid tag".

Keys the parser accepts, and how often each occurs in the stock file:

| Key | Count | Stock values |
|---|---|---|
| `package_name` | 206 | Also matches the prefixes `pkg.`, `pkg/`, `SurfaceView - [pkg`, `HandwrittenView - [pkg`, `HandwrittenView Background - [pkg`, `Splash Screen [pkg` (longest match wins). Special entry `StatusBar`. |
| `immutable` | 206 | 0 ×169, 1 ×37. 1 blocks the DPI/contrast-flag/bleach/anim setters. |
| `is_refresh_setting` | 200 | 1 |
| `refresh_mode` | 14 | 178 ×9, 179 ×5 |
| `refresh_frequency` | 186 | −1 ×184, 30 ×2 |
| `is_contrast_setting` | 198 | 1 |
| `app_contrast` | 189 | 0-60 |
| `app_color_mode` | 13 | 3 ×11 (video apps), 0 ×2 |
| `app_anti_flicker` | 13 | – |
| `app_bleach_mode` | 184 | 1 |
| `app_bleach_text_plus` | 186 | – |
| `app_bleach_icon_color` | – | 0 |
| `app_bleach_cover_color` | – | 0 |
| `is_dpi_setting` | – | – |
| `app_dpi` | 184 | 213-420 |
| `app_dpi_scale` | 1 | 1.4 |
| `app_anim_filter` | – | – |

Keys the parser accepts but the stock file never uses: `app_bleach_bg_color`, `app_dark_level`, `app_color_enhance`, `app_brightness_level` (these four only applied if >0), `app_auto_clean`, `app_anti_alias`, `app_text_enhance`, `app_scroll_flip`.

**Defaults for missing keys or unknown apps** (`getInitPolicy`, the +global overlay) [V]:
- immutable 0; app_dpi = `ro.sf.lcd_density` (300); dpi_scale 1.0; is_dpi_setting 1; is_refresh_setting 1.
- refresh_mode = `ro.vendor.xrz.default_refresh_mode` (178); refresh_frequency = `…default_refresh_frequency` (0).
- is_contrast_setting 1; contrast = `…default_contrast_level` (0).
- bleach_mode 1; text_plus = `…default_text_plus` (0); bleach_bg = `…default_bleach_bg_color` (10).
- dark = `…default_dark_level` (unset → 0); color_enhance = `…default_color_enhance` (70); brightness 0.
- auto_clean = `…default_auto_clean` (1); anti_flicker = `…default_anti_flicker` (1); color_mode = `…default_color_mode` (unset → 0).
- anti_alias 0; text_enhance 0; scroll_flip 0.

**Runtime DB `/data/system/disp_policy.db`** [V `DatabaseHelper.onCreate`]. DB version = `ro.vendor.xrz.display_policy_version` (6). Any version change copies the table to `backup_table`, drops it, recreates it and reloads the JSON; no migration cases exist, so user edits are lost.
```
CREATE TABLE policy_org (id integer primary key autoincrement, package_name text, immutable integer default '0',
 app_dpi integer default '-1', app_dpi_scale integer default '1', is_dpi_setting integer default '0',
 is_refresh_setting integer default '0', refresh_mode integer default '-1', refresh_frequency integer default '-1',
 is_contrast_setting integer default '0', app_contrast integer default '0', app_anim_filter integer default '0',
 app_bleach_mode integer default '0', app_bleach_text_plus integer default '0', app_bleach_icon_color integer default '0',
 app_bleach_cover_color integer default '0', app_bleach_bg_color integer default '0', app_dark_level integer default '0',
 app_color_enhance integer default '0', app_brightness_level integer default '0', app_auto_clean integer default '0',
 app_anti_flicker integer default '0', app_color_mode integer default '0', app_anti_alias integer default '0',
 app_text_enhance integer default '0', app_scroll_flip integer default '0')
```
**Persistence quirk** [V `insertOrUpdate`]: `refresh_mode`, `app_contrast`, `app_dark_level`, `app_color_enhance`, `app_brightness_level`, `app_auto_clean` and `app_anti_flicker` are written to the DB **only when loading defaults or when `app_color_mode==4` (CUSTOM)**. The other columns are always written. The UI therefore switches to Custom whenever the user changes the refresh mode. Effective values = colour-mode preset unless CUSTOM.

**Binder API** (`xrz_display_policy_service`, descriptor `xrz.framework.manager.IDisplayPolicyManager`) [V framework `IDisplayPolicyManager.Stub`]:

| Code | Method | Code | Method |
|---|---|---|---|
| 1 | getPolicyByPackage | 20/21 | register/unregisterGestureListener |
| 2 | getPolicyByPid | 22 | forceSetOrientation |
| 3 | getTopAppPolicy | 23 | getDarkLevelForPackage |
| 4 | getRefreshModeForPackage | 24 | getColorEnhanceForPackage |
| 5 | getContrastForPackage | 25 | getBrightnessLevelForPackage |
| 6 | setDpiForPackage | 26 | setDarkLevel |
| 7 | setIsDpiSetting | 27 | setColorEnhance |
| 8 | setIsRefreshSetting | 28 | setBrightnessLevel |
| 9 | setRefreshMode | 29 | getDefaultPolicyByPackage |
| 10 | setRefreshFrequency | 30 | setNetworkMonitorEnable |
| 11 | setIsContrastSetting | 31 | setAutoClean |
| 12 | setContrast | 32 | setAntiFlicker |
| 13 | setAnimFilter | 33 | setColorMode |
| 14 | setBleachMode | 34 | setTextEnhance |
| 15 | setBleachTextPlus | 35 | setScrollFlip |
| 16 | setBleachIconColor | 36 | getGlobalPolicy |
| 17 | setBleachCoverColor | 37 | setGlobalPolicy |
| 18 | setBleachBgColor | | |
| 19 | onPackageIdle | | |

All `set…` methods take `(String pkg, int)` and return a boolean. Broadcasts sent by the service: `com.xrz.eink.appcustom.state` (`top_package_changed`, dpi state) and `com.xrz.eink.appcustom` (`control_type=bleach`).

---

## (d) What survives on a GSI, and open questions

### Summary: where each mechanism bottoms out

- **Vendor props only.** Works on a GSI once `vendor.xrz.*` is labelled for the composer in `vendor_property_contexts` and the system-side defaults are re-seeded:
  - global refresh mode;
  - one-shot full refresh;
  - auto-clean prop;
  - anti-flicker;
  - colour restoration;
  - HD-256 image smoothing;
  - E Ink vsync options;
  - auto full refresh frequency (plus a check_commit pulser);
  - waveform reload;
  - scrolling hint.
- **Kernel nodes (root daemon).**
  - `eink_debug/{clean_a2, anti_alias}`; anti_flicker is written by the HWC itself.
  - `/dev/lm3630a` ioctls 0x7901/0x7902/0x7905, or the `lm3630a_{cold,warm}_light` sysfs nodes.
  - `/sys/power/normal_standby`.
- **HWC entry points.** Per-layer mode (with layer-type flags) needs a shim that calls **HWC2 descriptor 71**, because A16's SF will not send composer command 0x800.
- **Needs Bigme SF/libgui (lost):**
  - the GPU colour/contrast/brightness/saturation/LUT/dither shaders (`enable_gpu_image_process`);
  - remote layer mode by name (codes 61/62);
  - `Transaction::setRefreshMode`;
  - SF fps throttling for handwriting.

  Replacement is either a post-process in the HWC shim (LUTs `g_c001`/`g_c002` are copyable; libimage_process has CPU routines) or an SF colour-matrix approximation.
- **Needs the Bigme framework (lost):**
  - per-app policy service (rebuild as our daemon plus accessibility);
  - view/window refresh modes;
  - bleach filters;
  - scroll type 2;
  - touch disable;
  - sleep-logo orchestration and frontlight on/off at sleep (rebuild in our app);
  - Bigme keycodes.
- **AOSP-native equivalents:** high-contrast text (the "Text enhancement" checkbox), daltonizer monochromacy (Color/Gray), inversion.

### Key GSI finding: why the twin's GSI showed tiled grey
The vendor HWC decides grey vs colour by **`ro.vendor.xrz.screen_type`**. It sets the property itself in `mtk_init` from `DRM_IOCTL_EINK_GET_TYPE` and reads it back on every commit to fill `CommitInfo.format` (+0x54). When the value is 0 or unset, it runs the CPU gray8 conversion (`convertToGray`) and commits that buffer [V].

On a GSI the HWC's `property_set` lands on a prop with no vendor context, so it is most likely denied and the value reads 0 [I]. This matches the HiBreak Pro Color's "gray8 committed, kernel reads RGBA, 4× tiling" bug [R]. Their shim swapped the fd; a cleaner fix is probably to make `ro.vendor.xrz.screen_type=33` readable by the composer, set early by the module or allowed via `vendor_property_contexts`.

**Test first on our GSI:** `getprop ro.vendor.xrz.screen_type` and the composer avc denials.

### Open questions
1. **Kernel waveform semantics** of the Bigme modes 178/179/180/183 (and 181/182/176) and of `clean_a2`, `anti_alias`, `anti_flicker`. The kernel image was not decodable offline (no compression magic). The UI descriptions are the only hint: 178 "Scrolling/Web/Comics, minor ghosting", 179 "high-freq scrolling/videos", 180 "tap-to-turn, partial flicker", 183 "fastest, moderate ghosting", HD256 "static reading, slight flicker".
2. Composer opcode 0x800 → HAL vtable +0x160 → `setLayerRefreshMode`: inferred. The vtable sits behind APS2 packed relocations.
3. Whether Bigme SF automatically ORs layer-type flags (SURFACEVIEW, INPUT_METHOD, HOME, LOGO, CURSOR) into the desc-71 value. The stripped SF was not traced that far. HOME/IME behaviour on a GSI depends on it.
4. REGAL fallback: global 180 → 177 unless a layer carries 180 (reading of stack slot 0x8c). Verify on device.
5. What the vendor HWC/kernel does on `setPowerMode(OFF)` and doze (keeps the last frame vs clears it). This matters for the sleep image.
6. Parameter semantics of `libimage_process.so` `EffectHandler::imageProcess(src,dst,Rect,int,int,int,int,bool)` / `loadLut`, for a vendor-side colour pipeline.
7. `vendor.xrz.global_text_enhance`, `app_anim_filter` and `app_dark_level` have setters but no consumer was found (dark level is read only by handwrittenservice). Possibly dead on this firmware.
8. `driver_color_enhance` (CommitInfo+0x48) is never set by stock userspace. Its kernel effect may offer a vendor-side saturation knob without SF.
9. The kernel debugfs nodes `saturation`, `contrast`, `brightness`, `global_dither`, `global_mode`, `manual_refresh` [R] are **not used by any stock userspace**; their effect is untested.
10. Which forced-composition call on A16 SF reliably triggers one HWC present after `force_global_refresh_mode` is set (AOSP debug codes 1004/1006 vs an invisible overlay redraw).
11. handwrittenservice is started with `-p /dev/input/event0`, but the Wacom node was event4 on the running device (and it also has "Choose pen device" logic). Its runtime dependencies on an A16 system (`libion`, binder ABI) are unchecked.
12. Wacom EMR side-button mapping (BTN_STYLUS/BTN_STYLUS2 → AOSP stylus buttons) was not traced in Bigme's input stack.
