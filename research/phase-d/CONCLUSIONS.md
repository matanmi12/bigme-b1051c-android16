# Phase D — conclusions to resume from (saved 2026-10-18 01:45)

Sources: `stock-bigme-display-stack.md` (offline analysis of the stock images, verified addresses), `survey-hibreak-community.md`,
the Boox/Bigme/others benchmarks (summarised in the session; key points below), and on-device tests logged in
`research/phase-c/settings-changes.log`. [V] = verified on device or in images, [O] = owner-verified on the panel.

## 1. Facts to rely on
| Topic | Conclusion |
|---|---|
| Colour on the GSI | Vendor HWC chooses RGBA vs CPU-gray by `ro.vendor.xrz.screen_type` (>0 → colour). Stock HWC sets it from a kernel ioctl; on the GSI our module seeds 33 [V]. Driver v5.03, `panel_type=0x21` [V]. |
| Refresh mode (global) | Correct knob = **`vendor.xrz.global_refresh_mode`** (≠0 overrides every layer; low 16 bits mode, top bits dither) [V report]. `force_global_refresh_mode` also changed commits in our test but is the **one-shot** knob (HWC resets it to -1 after a frame) — app v0.5.x still uses it for the global mode → switch to `global_refresh_mode`. Stock UI modes: **HD 256** (−2147483471 = 0x800000B1), **Fast 178**, **Extreme 179**, **Regal 180**, **Hyper 183**. Caveat: global 180 may become 177 unless a layer carries 180. True per-layer modes need the shim to call HWC desc 71. |
| Full refresh (manual) | `setprop vendor.xrz.force_global_refresh_mode 4` + force one composition → HWC does full GC16 and resets to -1 [V report; our app's 1008-toggle variant produced two 0x4 commits — works, but the stock way is cleaner]. |
| Auto clean | `vendor.xrz.auto_clean_enabled=1` **and** write `1` to `eink_debug/clean_a2`; affects modes 178/179 (full-region instead of rect commits). App v0.5 only set the prop → owner: "doesn't work properly" [O]. |
| Full-refresh frequency (0–50) | Not fps. HWC counts frames with ≥40 % dirty area, ≥800 ms apart, **only while `vendor.xrz.check_commit≠0`**; stock PowerManager pulses `check_commit=1` for 800 ms on touch/key. Needs a daemon that pulses `check_commit` on input. |
| Anti-shake | = anti-flicker. Prop `vendor.xrz.anti_flicker_enabled=1`; the HWC writes `eink_debug/anti_flicker` itself before each commit [V report]. |
| Colour restoration | `vendor.xrz.color_restoration_enabled` (+ `…hwc.color_restoration_suppoort=1`, seeded) → dither 3 vs 6 in 178/179. |
| HD-256 smoothing | Automatic in HD 256 (177 + dither 4) with `…image_smoothing_suppoort=1` (seeded). |
| Saturation / contrast / colour brightness | **Were GPU shaders in Bigme's SurfaceFlinger** (formulas recovered, LUTs `/system/etc/g_c001`, `g_c002`). Lost on the GSI. The kernel `eink_debug/{saturation,contrast,brightness}` nodes accept writes (`set_system_*`) but no stock userspace uses them and the owner sees no proper effect [O]. Rebuild options: shim post-process (GLES pass with copied LUTs / vendor `libimage_process.so`), or SF colour matrix 1015 as a linear approximation. |
| Text enhancement | = AOSP `Settings.Secure.high_text_contrast_enabled` (native). |
| Invert / Grey | Stock Control Center = AOSP `accessibility_display_inversion_enabled` and daltonizer monochromacy (`accessibility_display_daltonizer_enabled=1`, `accessibility_display_daltonizer=0`). Native QS tiles exist (Color inversion, Color correction). |
| Frontlight | Two independent channels, raw 0–222 (stock table, 37 steps: `0,2,20,30,40,50,60,70,80,90,102,108,114,120,126,132,138,144,150,156,162,168,174,180,188,192,198,204,206,208,210,212,214,216,218,220,222`); presets Off(0,0) Day(30,0) Night(20,10) Bed(15,10) User(24,0) as step indices; stock switches it off at screen-off and restores on wake. **Sysfs names are swapped vs the LEDs on this unit** (`lm3630a_warm_light` drives cold) [O] — fixed in app v0.5.2. Stock uses `/dev/lm3630a` ioctls 0x7901 cold / 0x7902 warm / 0x7905 screen state. |
| Sleep image | RRO `config_displayColorFadeDisabled=true` (module v0.1.4) → panel keeps the last image while asleep [O]. Custom sleep picture: show an activity on SCREEN_OFF, delaying display-off (stock blocks up to 1.5 s). |
| SF wedge | Every GSI boot wedges SF (bootanim never ends); one SF restart fixes it (module watchdog) [V]. |
| Composition | Shim commits the GPU client target → HW overlays must stay off (SF 1008, module loop) [V]. |
| Magisk on TD16 | phh-su daemon bind-mounts over Magisk binaries → module stubs `/system/bin/phh-su` [V]. Install modules with `/data/adb/magisk/magisk`. |
| DSU | Stock system + signed AVB hashtree footer installed as DSU (16 GiB data), **disabled**; switch = `gsi_tool enable --single-boot` + reboot; not yet boot-tested (owner: later). |
| App compatibility | Onyx/Bigme-aware apps need hidden framework APIs or hard-coded device lists; not worth emulating now. KOReader: contribute a Bigme controller upstream later. |

## 2. Benchmark takeaways (Boox, Bigme, Hisense, Meebook, Kobo, Kindle, PocketBook)
Recurring features: per-app refresh profiles (4–5 levels); full refresh every N (user-set, 0–50/0–30); one-tap full refresh
(tile/key/gesture/floating button); vividness/contrast/brightness + a B/W switch; warm/cold presets with a bedtime schedule
(Bigme lacks the schedule); sleep screen (cover/custom/clock/"transparent"); dark mode that keeps images; text bold/bleach;
app freeze. Boox 4.1 "EinkWise": HD/Speed/Regal + Anti-flicker 0–32 + High Contrast; frontlight presets Bright/Soft/Off/Custom.

## 2b. Done since (2026-10-18 02:15)
- Shim colour pipeline = port of the stock SF process shader (contrast pow, brightness pow, HSL vivid, then stock LUT g_c001 / g_c002 for colour mode 2) on the CPU (8 threads, 64³ table when vivid>0, 1D otherwise); reads `vendor.xrz.global_{color_enhance,contrast,brightness_level,color_mode}`; client target taken from setClientTarget so it works in every mode incl. Regal. Measured vivid=70 full screen ≈12.8 ms/frame [V].
- Manual full refresh that works on the GSI: shim `vendor.debug.hwc.shim.commit_mode=4` + one composition + reset −1 (force_global_refresh_mode=4 did not fire because a stale 180 was left in it and the HWC never reset it on the GSI) [V].
- App 0.6.0 + module v0.1.5 use these knobs (see settings-changes.log).
- **Vendor HWC crash in Regal/global 180**: `OverlayEngine::processImage` maps the port fd (param+0x78) at its GraphicBuffer size (param+0x178: h@0x4c, stride@0x50) and runs the same-frame memcmp (CommitInfo+0x8b) even when the mmap failed → SIGSEGV → SF/framework restart. Shim now clears +0x8b for that call when the dma-buf is too small [V].
- After a composer/SF restart, 1008 can be lost even though the loop sent it → frozen panel. Loop now polls `dumpsys SurfaceFlinger --hwclayers` (≈10 ms) every 10 s and re-sends 1008 when a DEVICE layer appears [V].
- "Frozen" can also simply be sleep: doze keeps the last frame; check `dumpsys power | grep mWakefulness` first.
- Nav-row refresh button (app 0.6.2): accessibility overlay mirroring Recents (bounds from `com.android.launcher3:id/recent_apps`, tint sampled from its glyph); taskbar row y differs on home (≈2295) vs apps (≈2427) — tracked live [V].

## 2c. SystemUI work (2026-10-18 night)
- **Route chosen: SystemUI OverlayPlugin**, not a patched SystemUI. Debuggable builds load plugins holding `com.android.systemui.permission.PLUGIN` (signature); this GSI's SystemUI is signed with the public AOSP test platform key (cert SHA-256 C8A2E9…, keys in `work/sysui/keys/`), so a plugin signed with it qualifies. `CentralSurfacesImpl$3.onPluginConnected` calls `setup(mWindowRootView = NotificationShadeWindowView, navBar, …)`. Source: `work/app/einkui/` [V].
- QS is the **legacy view-based** QS (QSPanel, QSTileViewImpl, BrightnessSliderView); only the footer actions are Compose (styled with a RenderEffect colour matrix) [V].
- Pitfalls found: (1) never modify drawables obtained from SystemUI resources in place — the change leaks into the cached state and all tiles share one LayerDrawable; build own drawables. (2) there are two `brightness_slider` views (QS panel + brightness mirror). (3) plugin updates may call `setup()` of old and new instances in any order → highest build wins (System properties slot). (4) `ScrimDrawable.setColorFilter` doesn't invalidate. (5) `settings put secure sysui_qs_tiles` is reverted by A16 SystemUI; use `cmd statusbar set-tiles` [V].
- Tile colours: overriding QSTileViewImpl's (final) colour fields via reflection keeps SystemUI's state logic; the tile background must be our own ripple > LayerDrawable(TileShape, overlay) because a tinted GradientDrawable paints the stroke in the fill colour [V].
- Notifications: NotificationBackgroundView layer 0 is tinted (SRC_ATOP); the outline goes on layer 1 (untinted state overlay) [V].

## 2d. Auto full refresh (2026-10-18 05:00)
- The vendor HWC's clean_frequency "auto global flush" only disables rect commits (full-area commit in the current mode),
  it is not a GC16 [V disassembly of CommitPolicy::needCleanScreen/countFlushLimit + logs]. With our always-full-area
  shim commits it did nothing. Now: einkd raises check_commit on touch/pen/keys (800 ms) and the shim turns every
  N-th page turn (>= 800 ms apart) into GC16; `vendor.debug.hwc.shim.auto_gc16=0` restores vendor behaviour [V].

## 3. Current deliverables (as of 01:45; superseded by 2b/2c/2d)
- Module `b1051c-eink-compat` v0.1.4 (`4c13e032…`): shim, xrz props, density 300, stock IDC, SF watchdog, HW-overlays-off loop,
  phh-su stub, framework RRO (no colour fade).
- App `org.b1051c.einkctl` v0.5.2: frontlight (swapped fix, max 222, link to brightness slider, night schedule, off in sleep),
  refresh modes (stock names, per-app via accessibility), clear screen, anti-shake, auto-clean slider (incomplete), image sliders
  (ineffective), QS tiles Refresh/Clear/Light/Switch system, DSU switch.

## 4. To do (owner priorities to be asked)
1. QS / theming work (current request): dual frontlight sliders in place of the brightness slider; e-ink QS tiles; outline,
   solid-colour (no gradients) shade and notification styling; invert + grey tiles.
2. Fix refresh knobs (global_refresh_mode; force=4 for clear); auto clean (clean_a2) + frequency (check_commit pulser).
3. Colour pipeline in the shim (saturation/contrast/brightness, presets Default/Magazine/Comic/Video).
4. Scroll optimisation (`is_scrolling`), custom sleep image, hall-sensor keys, per-layer modes.
5. DSU boot test (with owner present), stock-side shortcut.
6. KOReader install + settings/progress restore.
