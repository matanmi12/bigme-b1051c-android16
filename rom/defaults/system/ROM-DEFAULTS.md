# InKognito alpha 1: Android-level ROM defaults

Sources: `research/phase-c/settings-changes.log` (lines from 2026-10-18 00:18 to 2026-10-20 23:48), and read-only
dumps from the dev tablet on 2026-10-21 00:05–00:20 (`settings list system/secure/global`, `getprop` as root, the
persistent property store, `cmd overlay list/dump/lookup`, role holders, `dumpsys webviewupdate`, accessibility,
package grants and appops, LatinIME prefs, and the `package=` attribution in `settings_*.xml` via abx2xml).
The script is `apply-system-defaults.sh` in this folder. Integration details are in `notes.md`.

Classes:
- **ROM-DEFAULT**: the layer needs it to work, or it is a design decision of the ROM.
- **PERSONAL**: the owner's preference or identity. It is never applied.
- **OBSOLETE**: a test or intermediate value that a later log line superseded.

Each table lists only the final value of an item.

"Log ref" is the time stamp of the line in settings-changes.log. "live" means the value is visible only on the device,
with no log line.

## 1. ROM-DEFAULT, applied by `apply-system-defaults.sh system` (boot 2, no app needed)

| Item | Final value | Class | Reason | Log ref | How applied on a fresh device |
|---|---|---|---|---|---|
| WebView provider | `com.android.webview` (Global `webview_provider`) | ROM-DEFAULT | WebViewUpdateService picked no provider, so Google sign-in (MinuteMaid) crashed with MissingWebViewPackageException. The Setup Wizard needs sign-in. | 2026-10-18 ~12:10 | `sys_webview`: `cmd webviewupdate set-webview-implementation com.android.webview`. **First step** of boot 2. |
| Window / transition / animator scale | `0` / `0` / `0` | ROM-DEFAULT | Every animation frame is one e-ink update (lag, ghosting). Approved by the owner. | 2026-10-18 00:18 | `sys_animations`: `settings put global …`, only over platform defaults (null/1). |
| Screen timeout | `600000` ms (10 min) | ROM-DEFAULT | The panel keeps the last frame, and reading needs more than the GSI's 1 min. Listed as a ROM default in plan B3. | 2026-10-18 00:18 | `sys_screen_timeout`, only over null/60000/121000 (121000 = what a fresh install shows before anyone touched it, install test 2026-11-11). |
| Color contrast | `secure contrast_level 0.5` (Medium) | ROM-DEFAULT | The default Material tones of buttons and switches are too pale on e-ink. Owner: Medium, not High. | 2026-11-11 15:2x | `sys_contrast`: first install only, only over null/0. |
| Stay awake while charging | `0` (= AOSP default) | ROM-DEFAULT | With 7 the lock screen never timed out on the charger, so the keeper never switched the front light off. 7 and 2 were test values. | 2026-10-19 00:49 (final). Earlier 7/2/7: 2026-10-18 00:38, ~04:26, ~05:15 | `sys_stay_on`: ensure 0. |
| Display color mode | `0` (Natural, color-managed; SF saturation 1.0) | ROM-DEFAULT | The shim runs the Bigme color pipeline on SF's client target, so SF output must be neutral. The GSI came up in 2 (Saturated, unmanaged). | 2026-10-18 00:35 | `sys_display_color_mode`: `settings put system display_color_mode 0`. ColorDisplayService then sets `persist.sys.sf.native_mode 0` and `persist.sys.sf.color_saturation 1.0`. |
| Dark theme mode | `ui_night_mode 1` (manual, light) | ROM-DEFAULT | Dark theme is one manual switch, the QS "Dark theme" tile, and ThemeSync follows it. Auto or scheduled mode would swap the wallpaper and keyboard by itself. This build has `config_defaultNightMode=1`, so the step is normally a no-op. | 2026-10-20 19:41 (supersedes "auto", 2026-10-19 02:22/03:37) | `sys_night_mode`: `cmd uimode night no`, only when the value is not 1 or 2. |
| Monet | `{"…color_source":"preset","…system_palette":"CD8E2C","…accent_color":"CD8E2C","…theme_style":"RAINBOW","…color_both":"1","_applied_timestamp":<now>}` | ROM-DEFAULT | Owner verdict G: exact-gray neutrals with a gold accent. "preset" means a wallpaper set never re-seeds the colors. | 2026-10-20 20:29 (candidate M at 20:26 is obsolete) | `sys_monet_pin`: `settings put secure theme_customization_overlay_packages`. SystemUI defers applying it until setup is complete. `post_setup` sets it again after a restore. |
| `persist.wallpapercolors.max_dark_area` | `25` (integer percent) | ROM-DEFAULT | Gives the cream wallpaper dark-text colorHints, so Lawnchair's AUTO text color shows black labels. WallpaperColors reads it once, in zygote. | 2026-10-20 20:01 (int, not 0.25); 21:05 (set) | **Boot 1**: `stage1` (`resetprop -p`), or a module's post-fs-data. Boot 2 runs `sys_wallpaper_dark_area` (setprop) as a fallback, which **needs a reboot** plus one wallpaper set. |
| QS auto-add bookkeeping (`qs_auto_tiles`) | merged with `wallet,cast,night,font_scaling,inversion` | ROM-DEFAULT | Stops SystemUI from pushing tiles into the curated layout later (for example font_scaling after the first font-size change). | live (written by SystemUI on the dev tablet) | `sys_qs_auto_tiles`. Best effort, no read-back since alpha 7: SystemUI owns the key and rewrites it at once. |
| Night Light | available, no tint; `night_display_activated 0` | ROM-DEFAULT | Our night mode (owner 2026-10-27): the fwoverlay makes the colour matrix the identity (a tint distorts e-ink) and E-Ink Control turns the switch into the front light's Night preset + Dark theme, intensity Night -> Bed. A Settings overlay explains it on the Night Light page. Starts off. | 2026-10-18 ~03:50; 2026-10-20 21:05/21:09 (hidden); 2026-10-27 (back as night mode) | The b1051c-eink-compat fwoverlay + settingsoverlay. The script only checks (`sys_night_light_off`). |
| Checks only | `ro.sf.lcd_density 350`, phh backpressure `false/false`, `service.sf.prime_shader_cache 0`, no `ro.frp.pst`, `navigation_mode 0`, default IME | ROM-DEFAULT (modules / GSI) | These are the values the layer expects. A WARN line is logged when one is missing. | see section 3 | `sys_checks` (read-only). |

## 2. ROM-DEFAULT, app-dependent (functions in the script, called after `pm install`)

| Item | Final value | Class | Reason | Log ref | How applied on a fresh device |
|---|---|---|---|---|---|
| Magisk su policy, E-Ink Control | `policies (uid=<real uid>, policy=2, until=0, logging=0, notification=0)` | ROM-DEFAULT | E-Ink Control drives the props, sysfs, eink_debug, wallpaper and LatinIME through su. Policy 2 = allow, until 0 = forever, no toast (each toast is a panel update). The dev tablet's uid 10144 must not be hard-coded. | 2026-10-18 01:00 (row from the 10-18 12:37 layer backup) | `app_su_allow` inside `app_einkctl`: `magisk --sqlite "REPLACE INTO policies …"` with the uid from `pm list packages -U` (exact match). Runs **before** the accessibility service. |
| E-Ink Control `WRITE_SECURE_SETTINGS` | granted | ROM-DEFAULT | Publishes the light values for the QS sliders, and drives the grayscale/inversion tiles and profiles. | 2026-10-18 ~03:50 | `app_einkctl`: `pm grant`. |
| E-Ink Control `POST_NOTIFICATIONS` | granted | ROM-DEFAULT | Keeper foreground notification (channel keeper2, no badge). | 2026-10-18 01:00 | `app_einkctl`: `pm grant`. |
| E-Ink Control accessibility service | `org.b1051c.einkctl/org.b1051c.einkctl.AppProfileService`; `accessibility_enabled 1` | ROM-DEFAULT | Per-app profiles, the nav-row refresh button and the shutdown screen. | 2026-10-18 01:12; re-enabled ~02:00 (lost on an update) | `app_a11y_enable`, merged (other services kept). |
| E-Ink Control keeper | started once | ROM-DEFAULT | BootReceiver runs only at boot or on package replace. ThemeSync's first run (cream wallpaper, keyboard theme, theme flag) happens when the keeper starts. | new (fresh devices only) | `app_einkctl_start`: `am start-foreground-service`. Run it after the wallpapers and IMPORT are staged. |
| SystemUI plugin permission | `com.android.systemui.permission.PLUGIN` granted | ROM-DEFAULT | A signature permission. It is granted automatically because the plugin carries the AOSP platform test key of this GSI's SystemUI. | 2026-10-18 ~03:00 | `app_einkui`: check only. |
| Launcher3 runtime RRO `org.b1051c.overlay.launcher` | enabled | ROM-DEFAULT | Hides the first-screen search row and fixes the nav-row jump (`grid_visualization_vertical_cell_spacing`). It lives in /data, so it starts disabled. The static RROs (fwoverlay, launcher3nav, icons) are immutable and always enabled. | 2026-10-18 13:58, ~16:45 | `app_runtime_overlay`: `cmd overlay enable`. Launcher3 is restarted once in `post_setup`. |
| Quick Settings layout | `internet,bt,rotation,custom(org.b1051c.einkctl/.TileRefresh),custom(…/.TileColour),custom(…/.TileCover),custom(…/.TileGrey),inversion,dark,night,dnd,airplane,custom(…/.TileAntiShake),custom(…/.TileColorRestore)` | ROM-DEFAULT | The owner's curated layout (live 2026-10-21) minus `TileRom` ("Switch system" needs the owner's DSU image). Clear screen and Front light are gone (the nav button and the shade sliders replace them). Font size was removed; Night Light came back next to Dark theme on 2026-10-27. | 2026-10-18 ~03:50, 12:38; 2026-10-19 20:23; live | `qs_tiles_default`: `cmd statusbar set-tiles` (on A16 SystemUI reverts a `settings put sysui_qs_tiles`). `post_setup` sets it again if a restore dropped our tiles. |
| Default IME | `helium314.keyboard/.latin.LatinIME` (HeliBoard 4.2, system app from the module `b1051c-heliboard`) | ROM-DEFAULT | Owner 2026-11-11: HeliBoard for swipe typing; the AOSP keyboard is hidden (`pm disable-user`, reversible). Stage 2 stages HeliBoard's prefs file (`defaults/heliboard/preferences.xml`: InKognito light/dark colors, key borders, popup off, gesture typing, sound on, no auto-correction, clipboard history off; languages follow the system) and the Hebrew dictionary in device-encrypted storage before the switch. Never edit that prefs file with sed. | 2026-11-11 | `heliboard_files` (stage 2) then `ime_defaults`: default when none is set or it is still the AOSP keyboard; a keyboard the user chose is kept; without HeliBoard the AOSP keyboard stays. Update from alpha 6: once, marker `migrate.alpha7.done`. |
| Device name / Bluetooth name (update only) | follow `ro.product.model` (`InKognito B1051C Pro`) | ROM-DEFAULT | An install made by alpha 6 or older derived both from the GSI's model. Changed only while they still read `TrebleDroid vanilla`. A fresh install derives them from the new model by itself. | 2026-11-11 | `identity_names`, from stage 2's update path (marker `migrate.alpha7.done`). |
| KOReader All files access | `MANAGE_EXTERNAL_STORAGE allow` | ROM-DEFAULT (owner to confirm) | KOReader (targetSdk 30) needs it to read `/sdcard/koreader` (Bigme patches, plugins) and books. Without it, KOReader asks on first start. | live (granted in the UI) | `app_all_files_access`: `appops set --uid`. |
| Default home | `app.lawnchair` (role HOME) | ROM-DEFAULT | Lawnchair 15 with the InKognito icon pack is the home screen. | 2026-10-18 ~16:20 | `app_default_home`, called by `post_setup` **after** `user_setup_complete=1` (see notes.md). |

## 3. ROM-DEFAULT applied by other layers (no action in this script)

| Item | Final value | Class | Reason | Log ref | How applied on a fresh device |
|---|---|---|---|---|---|
| Density | `ro.sf.lcd_density 350`, no forced density | ROM-DEFAULT | Owner decision 2026-10-21 (stock Bigme is 300). `wm density 300` was replaced by the module prop. | 2026-10-18 00:18 (wm density); module 0.1.2; 350 since module 0.1.27 | b1051c-eink-compat post-fs-data (boot 2 onward). |
| SF backpressure / shader priming | `persist.sys.phh.enable_sf_{gl,hwc}_backpressure false`, `service.sf.prime_shader_cache 0` | ROM-DEFAULT | SurfaceFlinger reads these once, at start (the "Mali wedge"; 10 s of priming). | 2026-10-19 14:24 | Module post-fs-data, every boot. |
| SF HW overlays off (1008), boot damage, ccci stop | runtime | ROM-DEFAULT | All composition goes through the client target (shim). | 2026-10-18 00:40 (manual, obsolete), ~01:53; 2026-10-19 14:17–14:31 | Module post-fs-data / service.sh. |
| FRP | `ro.frp.pst` deleted | ROM-DEFAULT | No PersistentDataBlockService, so FRP can never be armed. The frp partition is untouched. | 2026-10-18 ~12:10 | b1051c-gapps post-fs-data (see notes.md: Setup Wizard). |
| Wi-Fi only, no fingerprint, hall key, IDC | feature XMLs, fwoverlay, `.kl`/`.idc` | ROM-DEFAULT | Phone ANR loop, IVirtualHal loop, cover sleep/wake, touch orientation. | 2026-10-18 22:40, 23:04; 2026-10-19 15:10 | Module trees. |
| Cover wake | `persist.b1051c.cover_wake` unset (= 1, the cover sleeps and wakes) | ROM-DEFAULT | The live value 1 is the default. The Cover tile changes it, and that is a user state. | 2026-10-19 20:20/20:25 | einkd default. Nothing is written. |
| Dark boot animation flag | `/data/adb/b1051c/theme` absent (= light) | ROM-DEFAULT | | 2026-10-20 21:15 | Written by ThemeSync; read by the bootanim module. |
| `b1051c_*` Secure keys | unset = code defaults (`shade_palette 1`, `shade_theme 0`, stroke default, `taskbar_home_bg 1`, `taskbar_unstash 1`) | ROM-DEFAULT | Absent keys give the InKognito look and both Launcher3 fixes. | 2026-10-19 02:36, 14:00; 2026-10-20 19:59 | Nothing is written. |
| E-Ink Control display state (refresh mode, color preset, N, per-app profiles), front-light default, Lawnchair prefs (smartspace off, hotseat off, text AUTO, system fonts, icon pack), wallpapers, KOReader skeleton | see `defaults/apps` | ROM-DEFAULT (app scope) | | 2026-10-18 02:10, 16:35; 2026-10-19 00:17, 13:50, 20:35; 2026-10-20 21:09, 23:06 | The apps defaults step (another agent) and ThemeSync. Not this script. |

## 4. PERSONAL (never applied)

| Item | Live value | Class | Reason | Log ref | How applied on a fresh device |
|---|---|---|---|---|---|
| Forced density | 350 | PERSONAL | Set by the owner with the E-Ink Control DPI slider (`package=android`). The ROM ships 350 as the module prop, with no forced value. | live (since ~2026-10-18 11:30, app 0.8.0) | not applied |
| Font size | `font_scale 1.3` | PERSONAL | Owner's Settings choice (`package=android`). | live | not applied |
| Auto-rotate | `accelerometer_rotation 1` | PERSONAL | Owner's toggle. | live | not applied |
| Language, 24 h, Celsius | the owner's locale list, `time_12_24 24` | PERSONAL | Identity and preference. The Setup Wizard sets the language. | 2026-10-19 00:29–00:35 | not applied |
| Time zone, auto time zone | the owner's zone, auto off | PERSONAL | | live | not applied |
| Wi-Fi, accounts, Google backup | — | PERSONAL | | live | Setup Wizard |
| Verify apps over USB | `verifier_verify_adb_installs 0` | PERSONAL | A Play Protect toggle (the owner's call to unblock adb installs). | 2026-10-18 ~15:15 | Not applied. `verifier_pause/restore` only pauses it during provisioning (optional). |
| AOSP Calendar removed for user 0 | uninstalled -k | PERSONAL | The owner uses Google Calendar from Play, which the ROM does not bundle. | 2026-10-18 ~15:45 | not applied |
| Front-light levels | the owner's cold/warm values | PERSONAL | | 2026-10-18 ~03:55; 2026-10-19 01:15, 02:22, 03:55; 2026-10-20 23:48 | E-Ink Control default (app scope) |
| Current theme state | `ui_night_mode 2`, LatinIME theme 4, flag dark | PERSONAL (state) | The ROM default is light (section 1). | 2026-10-20 21:17 | not applied |
| Do Not Disturb, inversion/grayscale state, volumes, ringtones, screen brightness | live values | PERSONAL | | live | not applied |
| DSU stock image, "Switch system" tile | installed on the dev tablet | PERSONAL | The owner's dual boot to stock (a Bigme image that cannot ship). | 2026-10-18 01:20–01:28 | `TileRom` is left out of QS. `app_einkctl_hide_dsu_tile` is optional. |
| KOReader home folder on the SD card | the owner's SD card path | PERSONAL | The path contains the owner's card UUID. | 2026-10-19 ~00:00 | not applied |
| Lawnchair notification listener (dots), Browser role (Chrome), Magisk app notifications | granted / Chrome | PERSONAL | User grants and Play apps. | live | not applied |
| Developer options on, USB debugging | `development_settings_enabled 1`, `adb_enabled 1` | PERSONAL / GSI default | USB debugging is on by default on this userdebug GSI (KNOWN-ISSUES). The ROM does not change it. | live | not applied |

## 5. OBSOLETE (superseded in the log)

| Item | Superseded value | Class | Reason | Log ref | How applied on a fresh device |
|---|---|---|---|---|---|
| SF 1008 by hand | runtime call | OBSOLETE | Now in the module. | 2026-10-18 00:40 | — |
| Magisk module re-enable after bootloop protection | rm disable | OBSOLETE | One-time incident. | 2026-10-18 00:35 | — |
| Probes: front light, eink_debug, anti-alias, Regal setprop, DT2W (easy_wakeup_gesture, wakeup, drv_debug, dtbo flags), kptr_restrict 2→0→2, `dmesg -c` | runtime | OBSOLETE | Tests. DT2W was dropped (Bigme's touch driver cuts the touch supply in suspend). | 2026-10-18 00:50, 01:03, 13:38, 13:40; 2026-10-19 16:20–20:20; 2026-10-20 22:18 | — |
| `stay_on_while_plugged_in` 7 / 2 | 7, 2 | OBSOLETE | The final value is 0 (section 1). | 2026-10-18 00:38, ~04:26, ~05:15 | — |
| logcat buffer sizes, `persist.logd.size` | 8M/16M | OBSOLETE | Debug only; `persist.logd.size` was deleted. | 2026-10-18 ~03:00; 2026-10-19 14:20/14:31 | — |
| `adb root` after boots, SystemUI/Launcher3 kills, icon cache clears | runtime | OBSOLETE | Development. | many | — |
| Night mode yes/no/auto toggles | auto (0) | OBSOLETE | Replaced by manual light (section 1). | 2026-10-19 02:22, 03:37 | — |
| Monet candidate M | MONOCHROMATIC | OBSOLETE | The owner chose G. | 2026-10-20 20:26 | — |
| Wallpapers: white (13:53), D-white (10-19 20:21), dark C (10-19 22:05), unmodified dark | ids 5–11 | OBSOLETE | Final: cream D and dark D, set by ThemeSync. | 2026-10-18 13:53; 2026-10-19 20:21, 22:05; 2026-10-20 19:38 | — |
| Per-app profile Keep = Extreme | 179 | OBSOLETE | Removed at 22:57; Keep = Pen at 23:06 (app scope). | 2026-10-20 22:14 | — |
| Live ink knobs (`ink_idle_ms 2000`, `ink_real_lag_ms 300`) | runtime props | OBSOLETE | Now shim defaults. | 2026-10-20 23:17 → 23:23 | — |
| Stale Launcher3 hotseat item, launcher layout edits | content delete | OBSOLETE | Dev-tablet cleanup. | 2026-10-18 22:36 | — |
| `double_tap_to_wake 1`, `wake_gesture_enabled 1` | 1 / 1 (`package=android`) | OBSOLETE | No effect: DT2W is impossible on this kernel. | live | — |
| Earlier QS lists (with Clear screen, Front light, Night Light, font size) | — | OBSOLETE | The final list is in section 2. | 2026-10-18 ~03:50 | — |

Partition writes (boot_b, logo, dtbo_b), module and app builds, and the shim knobs live in their own packages and are
not Android settings. They are out of scope here.

**Anti-shake (2026-10-21):** `einkctl-defaults.json` ships `anti_flicker=false`, so it is off globally and the owner turns it on per app. It works on the GSI since module 0.1.28, which sets the debug nodes to 0666 as stock does and adds the composer→debugfs rule. Measured: no effect in Regal; Fast/Extreme ~+18 ms per update, smoother scrolling.
