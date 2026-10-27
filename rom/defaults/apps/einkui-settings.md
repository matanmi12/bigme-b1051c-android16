# einkui (E-Ink SystemUI plugin) — Settings.Secure keys

Plugin `org.b1051c.einkui` 0.4.7 (source `work/app/einkui/src/org/b1051c/einkui/`). It runs inside SystemUI and
only **reads** `Settings.Secure` (plus writes the two light keys when a slider moves). Every read has a code default,
so a fresh device needs **no** `b1051c_*` key set for the ROM look. Live values read 2026-10-21 00:00 with
`adb shell settings list secure` (read-only); "unset" = the key does not exist on the owner's tablet either.

| Key | Type | Read in | Code default (missing key) | Live value | Fresh device |
|---|---|---|---|---|---|
| `b1051c_shade_theme` | int | ShadeStyler.readPrefs | `0` = InKognito shade, follows Android's Dark theme. `1` forced light, `2` forced dark (adb rollback only), `-1` off (vanilla shade) | unset (= 0) | **do not set** (rely on default 0) |
| `b1051c_shade_palette` | int | ShadeStyler.readPrefs / Palette | `1` = InKognito colors (board 05 light, "Night Badge" dark). `0` = plain black and white | unset (= 1) | **do not set** |
| `b1051c_shade_stroke` | int, tenths of dp | ShadeStyler.readPrefs | `-1` -> 25 (2.5 dp) with the InKognito palette, 15 with the plain one | unset | **do not set** |
| `b1051c_shade_tune_light` | string `role=RRGGBB,...` | Palette.tuned | none (built-in tokens) | unset | **never set** (live retune aid; malformed input is skipped) |
| `b1051c_shade_tune_dark` | string `role=RRGGBB,...` | Palette.tuned | none | unset | **never set** |
| `b1051c_shade_debug` | int | ShadeStyler.debug | `0` | unset | **never set** (1 = verbose view log) |
| `b1051c_fl_cold` | int, raw 0..222 | LightSliders (observer) | `0` | `0` (light switched off for the night: owner state) | **do not seed**: E-Ink Control publishes it |
| `b1051c_fl_warm` | int, raw 0..222 | LightSliders (observer) | `0` | `0` (owner state) | **do not seed** |
| `b1051c_lock_style` | int | LockStyler (0.4.6+) | `1` = InKognito lock screen (Lilita One clock, own date line, corner shortcut hidden); `0` = Android's | unset (= 1) | **do not set** |
| `b1051c_lock_scrim` | int | ShadeStyler (0.4.3+) | `1` = nothing over the wallpaper; `2` = the page colour; `0` = Android's 20 % shade | `1` (set by the owner's app, = default) | **do not set** |

## Notes for the integrator

- **Front-light keys are shared state, not ROM defaults.** E-Ink Control writes `b1051c_fl_cold/warm` every time it
  applies the light (KeeperService start -> `Eink.applyAll` -> `publishLight`), and the plugin writes them when a
  slider moves. With the ROM's E-Ink Control defaults (no light keys in `einkctl-defaults.json` -> `light_on` false)
  the first publish is `0/0`, so the sliders start at 0 = light off. Seeding other values would be overwritten.
  E-Ink Control needs `pm grant org.b1051c.einkctl android.permission.WRITE_SECURE_SETTINGS` (otherwise it falls
  back to `su` + `settings put`). KOReader's patch also reads these two keys at start.
- **Who else writes the shade keys:** only E-Ink Control's "Notification shade" section (`b1051c_shade_theme` 0 / -1,
  `b1051c_shade_palette` 1 / 0, `b1051c_shade_stroke` 5..40) when the user changes them. Shipping them unset keeps
  "default" distinguishable from "chosen by the user".
- **The lock keys** (`b1051c_lock_style`, `b1051c_lock_scrim`) are written by the **Lock & Sleep** app
  (`org.b1051c.sleep`, since 2026-10-22; E-Ink Control 0.15.7–0.15.10 wrote them before). Lock & Sleep needs
  `pm grant org.b1051c.sleep android.permission.WRITE_SECURE_SETTINGS` (provisioning `app_sleep`), else it falls
  back to `su` + `settings put`.
- **Plugin enablement needs no setting.** SystemUI on this debuggable (userdebug) GSI discovers the plugin through
  its `com.android.systemui.action.PLUGIN_OVERLAY` service, because the APK is signed with the AOSP test platform key
  (`com.android.systemui.permission.PLUGIN`). SystemUI writes its own
  `/data/user_de/0/com.android.systemui/shared_prefs/plugin_prefs.xml` (`plugins=true`, action list); do not ship it.
  After `pm install` the plugin is picked up live (a SystemUI restart gives a clean first style pass, optional).
- **Dead fallback (observation, Low):** if `Settings.Secure.putInt` throws, LightSliders sends the broadcast
  `org.b1051c.einkctl.SET_LIGHT` to E-Ink Control, but no receiver for that action exists in E-Ink Control 0.15.0
  (manifest or code). SystemUI holds WRITE_SECURE_SETTINGS, so the fallback should never run; the sliders would
  silently do nothing if it did.

## Other `b1051c_*` Secure keys on the ROM (not einkui, for completeness)

| Key | Read by | Code default | Live | Fresh device |
|---|---|---|---|---|
| `b1051c_taskbar_home_bg` | patched Launcher3QuickStep (`work/launcher3patch/src/org/b1051c/l3/HomeBg.java`) | `1` = transparent nav row on home; `0` = stock | unset | do not set |
| `b1051c_taskbar_unstash` | same (Recents stash fix) | `1` = fix on | unset | do not set |
| `b1051c_shutdown_screen` | E-Ink Control `ShutdownScreen.index()` (0.15.11+); written by Lock & Sleep's "Shutdown" spinner | unset -> the E-Ink Control pref `shutdown_screen` -> `anim_bw` (badge animation); `logo_cream`, `black`, `off` | unset | do not set |
