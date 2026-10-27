# InKognito alpha 1 — default configurations for the bundled apps

Built 2026-10-21 from the owner's live configs on the tablet (read-only queries), minus personal data, device state,
layout and front-light levels. Nothing here contains Wi-Fi networks, accounts, IDs, serials, LAN addresses, names,
book titles/paths, reading history or the home layout. Every file was checked against the live device (round trips
and line diffs below).

## Files

| File in this folder | Destination on the device | Owner / mode / label | When to place it |
|---|---|---|---|
| `einkctl-defaults.json` (sha256 `609cfb32…b7f8`) | `/data/data/org.b1051c.einkctl/files/einkctl-import.json`, then the IMPORT broadcast | app uid:app uid, `0600`; dir `files` `0771` app uid; label = the package dir's (`u:object_r:app_data_file:s0:cNNN,c256,c512,c768`) | after `pm install` + WRITE_SECURE_SETTINGS grant + Magisk su policy for the app's real uid; wallpapers in place first (below). Before or after the app's first start (import replaces every non-state pref) |
| `work/design/cat-test/wallpaper-D-cream.png` (not copied here; sha256 `4d8d487b9fc9dd1bcddc420f574321d978c7f8af2ae347b7b0e012c791a97412`, 1792x2400, no metadata chunks) | `/sdcard/Android/data/org.b1051c.einkctl/files/wallpaper-D-cream.png` (light theme) | app uid:`1078` (ext_data_rw), `0660` (live copies: shell:1078 `0666`, label `media_rw_data_file:s0`, work too) | after the app's external files dir exists, before IMPORT |
| `work/design/dark-test/dark-D.png` (sha256 `84a2ad266876d2f7c9d193343da0b1704464ad7a7407482dac46e169ecd89903`, 1792x2400) | `/sdcard/Android/data/org.b1051c.einkctl/files/dark-D.png` (dark theme) | same | same |
| `einkui-settings.md` | — (documentation) | — | nothing to set on a fresh device |
| `lawnchair/preferences.preferences_pb` (sha256 `ac963fc5…d690`, 488 bytes, 13 keys) | `/data/data/app.lawnchair/files/datastore/preferences.preferences_pb` | app uid:app uid, `0600`; dirs `files` `0771`, `files/datastore` `0700`; label = package dir's | after `pm install app.lawnchair`, **before Lawnchair's first start** (= before it becomes the HOME role holder) |
| `lawnchair/com.android.launcher3.prefs.xml` (sha256 `01e2a138…05ce`, 13 keys) | `/data/data/app.lawnchair/shared_prefs/com.android.launcher3.prefs.xml` | app uid:app uid, `0660`; dir `shared_prefs` `0771`; label = package dir's | same |
| `lawnchair/lawnchair-defaults.json` | — (source + listing: what is in both files, why, and what was dropped) | — | — |
| `lawnchair/prefs_pb.py` | — (DataStore codec / builder, stdlib only) | — | — |
| `koreader/settings.reader.lua` (sha256 `95363f62…9e97`) | `/sdcard/koreader/settings.reader.lua` | write **through `/sdcard`** (FUSE): MediaProvider then owns it (live: MediaProvider's uid : `1023` media_rw, `0660`, `media_rw_data_file` with MediaProvider's categories). Never write into `/data/media/0` directly | any time after `/sdcard` is mounted and **before KOReader's first start**; KOReader need not be installed yet |
| `work/koreader/patches/1-bigme-eink.lua` (sha256 `26dc8fa5…b3e7`), `2-bigme-light-dialog.lua` (sha256 `a07de7f3…b161`) | `/sdcard/koreader/patches/` (KOReader never creates this dir) | through `/sdcard` | same as settings.reader.lua |
| `zenos.koplugin` (ZenOS 3.0.0, GPLv3, 358 files, 7.5 MB; the live copy pulled tonight: scratchpad `live/koreader/plugins/zenos.koplugin`, or `adb pull /sdcard/koreader/plugins/zenos.koplugin`) | `/sdcard/koreader/plugins/zenos.koplugin/` | through `/sdcard` | same |
| `work/koreader/derainbowify.koplugin` (0.0.12, GPLv3; identical to live except the extra `SOURCE-COMMIT.txt`) | `/sdcard/koreader/plugins/derainbowify.koplugin/` (path is hard-coded in the plugin) | through `/sdcard` | same |

App uids differ per device: read them, never hard-code the owner's (`stat -c %u /data/data/<pkg>` or
`pm list packages -U <pkg>`). Label: copy the package dir's context (`stat -c %C /data/data/<pkg>`), see the commands.

---

## 1. E-Ink Control (`org.b1051c.einkctl` 0.15.0)

`einkctl-defaults.json` is in the app's own export format (`Profiles.exportAll`): `{"app", "format": 1, "prefs":
{key: {"t": b|i|l|f|s, "v": value}}}`. `Profiles.importAll` **clears** all prefs except the `state_*` keys, then
writes these, so every key left out falls back to the app's code default.

| Key | Value | Why |
|---|---|---|
| `mode` | 180 | global refresh mode Regal (owner's display setting, restored by the owner 2026-10-20) |
| `profile` | 4 | global color preset Custom … |
| `vivid` / `contrast` / `cbright` | 50 / 50 / 50 | … with these sliders (owner's "Custom 50/50/50") |
| `clean_every` | 15 | shim full refresh every 15 big updates (owner value) |
| `n_dark` | 0 | same N while dark (code default, explicit) |
| `anti_flicker` / `auto_clean` / `color_restoration` / `anti_alias` | false | owner/default: anti-shake off, auto clean off, color restoration off, anti-aliasing off (striped grays) |
| `home_clean` / `ime_fast` / `clean_on_dark` | true | full refresh on return home, Fast while the keyboard is open, full refresh on inversion switch (code defaults, explicit) |
| `nav_refresh` | true | nav-bar refresh button shown (code default, explicit) |
| `cover_wake` | true | magnetic cover sleeps/wakes (default; einkd treats an unset `persist.b1051c.cover_wake` as on) |
| `shutdown_screen` | `anim_bw` | shutdown: reversed boot animation, black-and-white drawing left on the panel |
| `theme_wallpaper` / `theme_keyboard` | true | "Wallpaper follows theme" / "Keyboard follows theme" |
| `wallpaper_light` / `wallpaper_dark` | `wallpaper-D-cream.png` / `dark-D.png` | ThemeSync file names (= code defaults, pinned explicitly) |
| `presets` | `[{"name": "KOReader", vivid 0, contrast 30, cbright 0, mode 4, refresh 180}]` | user preset used by the KOReader profile (stock Bigme KOReader values) |
| `profile:org.koreader.launcher` | `{"refresh": 180, "preset": "KOReader", "n": 0}` | KOReader: Regal + KOReader colors; shim full refresh off (KOReader asks for its own via KO_REFRESH) |
| `profile:com.google.android.keep` | `{"refresh": 1029}` | Keep = Pen (ink layer) — owner: "works great" (applies once the user installs Keep) |
| `profile:com.amazon.kindle` | `{"refresh":180}` | the app's own seed (Kindle = Regal) |
| `seeded_v1` / `migrated_app_v1` | true | the seed / 0.9.5 migration already applied (keeps the seed from running again) |

**Left out on purpose:** `cold`, `warm`, `warmth`, `light_on`, `link_brightness` (front light: app defaults = off,
40/40, warmth 50, not linked to Android brightness); `state_theme`, `state_wp_name`, `state_wp_id`, `state_kb`
(ThemeSync device state; never exported, kept across an import anyway); `base_grey` / `base_invert` / `base_hc`
(saved Android values around a running override; not present live); `saturation` (dead key of app 0.3–0.5, not
read by 0.15.0).

### Wallpapers ThemeSync expects

ThemeSync (`ThemeSync.WP_LIGHT/WP_DARK`, prefs `wallpaper_light/_dark`) and `Eink.setWallpaper` read only from the
app's external files dir `getExternalFilesDir(null)` = `/storage/emulated/0/Android/data/org.b1051c.einkctl/files/`
(a path check refuses anything outside it). A missing file leaves the wallpaper as it is (no fallback):

| Theme | File name (exact) | Source |
|---|---|---|
| light (Dark theme off) | `wallpaper-D-cream.png` | `work/design/cat-test/wallpaper-D-cream.png` (= live, sha above) |
| dark (Dark theme on) | `dark-D.png` | `work/design/dark-test/dark-D.png` (= live, sha above) |

Do not ship the other files in the owner's dir (`dark-A/B/C.png`, `dark_bg.jpeg`, `wallpaper-D-white.png`,
`wallpaper1-neutral*.png`, `einkctl-settings.json`). The shutdown screen images are APK assets (nothing to place).

### Order for the stage-2 provisioner (root, after `sys.boot_completed=1`)

`/sdcard/Android/data` is a direct bind mount of the lower f2fs (not FUSE) in the init namespace, so files written
there as root keep root's owner: chown them. The dir itself is created by the system when the app first calls
`getExternalFilesDir` (vold sets owner, `ext_data_rw` group and quota project) — let the app create it.

```sh
P=org.b1051c.einkctl; D=/data/data/$P; U=$(stat -c %u $D); CTX=$(stat -c %C $D)
# prerequisites: pm grant $P android.permission.WRITE_SECURE_SETTINGS; Magisk su policy for uid $U;
# accessibility service enabled; Monet pinned (theme_customization_overlay_packages color_source "preset")
# BEFORE the import, or the wallpaper set below re-seeds the system colors.
# 1. let the app create its external files dir (a freshly installed app is in the stopped state):
am broadcast --include-stopped-packages -n $P/.ActionReceiver -a $P.THEME_SYNC --ez dry true
X=/sdcard/Android/data/$P/files; [ -d $X ] || echo "external files dir missing"
# 2. wallpapers
cp wallpaper-D-cream.png dark-D.png $X/ && chown $U:1078 $X/wallpaper-D-cream.png $X/dark-D.png \
  && chmod 0660 $X/wallpaper-D-cream.png $X/dark-D.png \
  && chcon u:object_r:media_rw_data_file:s0 $X/wallpaper-D-cream.png $X/dark-D.png   # = the working live label
# 3. settings: drop file in the private files dir, then IMPORT (ActionReceiver is not exported: send as root)
mkdir -p $D/files && chown $U:$U $D/files && chmod 0771 $D/files && chcon "$CTX" $D/files
cp einkctl-defaults.json $D/files/einkctl-import.json && chown $U:$U $D/files/einkctl-import.json \
  && chmod 0600 $D/files/einkctl-import.json && chcon "$CTX" $D/files/einkctl-import.json
am broadcast --include-stopped-packages -n $P/.ActionReceiver -a $P.IMPORT
# 4. a fresh install gets no BOOT_COMPLETED: start the keeper (seeds, applies, starts ThemeSync)
am start-foreground-service -n $P/.KeeperService
```

Verify: the drop file is gone (the app deletes it after a good import; logcat tag `einkctl`: "Imported …" or
"Import failed: …"); `shared_prefs/einkctl.xml` holds the 27 keys; `getprop vendor.xrz.global_refresh_mode` = 180;
`… -a $P.THEME_SYNC --ez dry true` reports both wallpaper files present (no "(missing)") and Monet `preset`.

## 2. einkui SystemUI plugin

Nothing to set: see `einkui-settings.md` (every `b1051c_*` Secure key with its code default and live value). The
shade look comes from the code defaults; `b1051c_fl_cold/warm` are written by E-Ink Control.

## 3. Lawnchair (`app.lawnchair` 15 Beta 3)

Lawnchair keeps settings in two stores (both names verified in the decompiled 15 Beta 3 APK): PreferenceManager2 =
DataStore `"preferences"` -> `files/datastore/preferences.preferences_pb`, and PreferenceManager = SharedPreferences
`com.android.launcher3.prefs`. `lawnchair-defaults.json` lists every kept key with its reason and every dropped
item. Highlights: icon pack `org.b1051c.iconpack`, smartspace off, dock search bar off (`hotseat_mode=disabled`),
wallpaper depth effect off, labels in the dock, icons 120 %, workspace text color `AUTO`, system fonts
(`sans-serif` / `sans-serif-medium` = Rubik through the fonts module), grid 5 x 7 with 5 dock icons, new apps not
added to home. Dropped: the home layout DB (personal), usage counters, grid-migration bookkeeping, device prefs
(widget id, icon-shape cache key), caches, logs, the announcement cache.

Checks done: `prefs_pb.py check` on the live file = byte-identical round trip; the built `preferences.preferences_pb`
is byte-identical to the live one (no DataStore key needed dropping); the built XML equals the live file minus the
dropped lines. Rebuild: `python3 -I lawnchair/prefs_pb.py build lawnchair/lawnchair-defaults.json lawnchair`.

```sh
P=app.lawnchair; D=/data/data/$P; U=$(stat -c %u $D); CTX=$(stat -c %C $D)
pidof $P && am force-stop $P        # it must not be running: DataStore/SharedPreferences cache and rewrite
mkdir -p $D/shared_prefs $D/files/datastore
cp com.android.launcher3.prefs.xml $D/shared_prefs/ && cp preferences.preferences_pb $D/files/datastore/
chown -R $U:$U $D/shared_prefs $D/files
chmod 0771 $D/shared_prefs $D/files && chmod 0700 $D/files/datastore
chmod 0660 $D/shared_prefs/com.android.launcher3.prefs.xml && chmod 0600 $D/files/datastore/preferences.preferences_pb
chcon -R "$CTX" $D/shared_prefs $D/files      # or: restorecon -RFD $D (then compare with ls -Z)
ls -lZ $D/shared_prefs $D/files/datastore     # categories must equal the package dir's
# only now: make Lawnchair the HOME role holder (its first start reads these files)
```

Notes: install `org.b1051c.iconpack` before Lawnchair first starts. `version=2` matters (a value < 2 makes Lawnchair
re-derive the grid from launcher-DB state). Lawnchair's SharedPreferences-to-DataStore migration only moves 28
legacy keys (`pref_darkStatusBar`, `pref_iconSizeFactor`, `hidden-app-set`, …), none of which is in this XML, so
nothing here is migrated or deleted.

## 4. KOReader (`org.koreader.launcher` v2026.07.1)

Where things live on Android (checked in the APK's `assets/android.lua` and `datastorage.lua`): the program is in
`assets/module/koreader.7z`, extracted (first start, and again after an update) into the app's **internal** files dir
(`/data/data/org.koreader.launcher/files`, the working dir; built-in plugins = `./plugins`). All settings and user
data are in `DataStorage:getDataDir()` = `getExternalPath()/koreader` = `/storage/emulated/0/koreader`. The
extraction never touches `/sdcard/koreader`; at start KOReader only creates its missing subdirs (`cache`,
`clipboard`, `data/dict`, `data/tessdata`, `ota`, `plugins`, `screenshots`, `settings`, `styletweaks` — **not**
`patches`). So the folder can be created before the first launch and an existing `settings.reader.lua` is used as is
(KOReader then adds `device_id`, `dev_no_c_blitter`, `extra_plugin_paths` = `{"/storage/emulated/0/koreader/plugins/"}`
itself). KOReader needs "All files access": `MANAGE_EXTERNAL_STORAGE` app-op `allow` (live) — grant it in stage 2
(`appops set --uid org.koreader.launcher MANAGE_EXTERNAL_STORAGE allow`) or KOReader asks on its first start.

Minimal `settings.reader.lua` (comments inside explain each key): `color_rendering=true`, `full_refresh_count=6`,
`inertial_scroll=false`, `last_migration_date=20260623` (the `CURRENT_MIGRATION_DATE` of v2026.07.1),
`plugins_disabled={}` (zenos + derainbowify load). The patches need no setting (user patches are on unless
`patches/.patches_disabled` exists; `1-` = early, before the device is created; `2-` = late).

Ship: `settings.reader.lua`, `patches/1-bigme-eink.lua`, `patches/2-bigme-light-dialog.lua`,
`plugins/zenos.koplugin/`, `plugins/derainbowify.koplugin/`.
Do not ship: `bookshelf.koplugin` (disabled on the live device, ZenOS replaces it; if shipped anyway add
`["plugins_disabled"] = { ["bookshelf"] = true }`), `plugins/filebrowser.koplugin_main_….tar` (leftover archive),
`settings/` (history, statistics, vocabulary, lookup history, bookinfo/docprops caches, kosync/OPDS/wallabag/cloud
storage, bookshelf, gestures, and `settings/ZenOS/` incl. the library cache and home), `history.lua`, `*.old`,
`data/dict` (the owner's dictionary), `icons/` (ZenOS copies its icons there itself), `cache/`, `clipboard/`,
`screenshots/`, `news/`, `ota/`, `help/`, `defaults.custom.lua`.

Removed from the owner's `settings.reader.lua` (11 KB): personal (`device_id`, `home_dir`, `lastdir`, `lastfile`,
`language`/`keyboard_layout` Hebrew, RTL gesture flags, `cre_fonts_recently_selected`, `statistics`,
`vocabulary_builder`, `wifi_was_on`, `clipboard`, `exporter`, `bookshelf_hero_regions`, `start_with` "bookshelf"),
and everything ZenOS writes on its own first run (all `copt_*` reader defaults = ZenOS `CRE_DEFAULTS`, the `footer`
preset and `reader_footer_*`, `screensaver_*`), plus KOReader defaults.

ZenOS keeps its own settings in `koreader/settings/ZenOS/config.lua` (merged over its `config/defaults.lua`); none is
shipped, so ZenOS runs its quickstart on the first start (library view, navbar tabs) and applies its reader/footer
defaults. The owner's non-default ZenOS choices (diff of the live config vs the plugin defaults) are listed under
open questions.

---

## Open questions / risks for the integrator

1. **Untested end to end.** The IMPORT path, the Lawnchair pre-seed and the KOReader skeleton were verified only
   against the live files/code, not on a freshly wiped device.
2. **Stopped state.** A package installed in stage 2 is "stopped": use `--include-stopped-packages` for the
   broadcasts and start KeeperService explicitly (BootReceiver only runs at the next boot / on an app update).
3. **Import clears everything** except `state_*`: run it before the user can touch E-Ink Control or the QS light
   sliders (the Setup Wizard normally blocks the shade). A light set before the import is reset to off.
4. **Monet before wallpaper.** ThemeSync logs a warning and the wallpaper set re-seeds Monet unless
   `theme_customization_overlay_packages` already has `color_source: preset` (ROM default "Monet pin G").
5. **Lawnchair must not have run** when its files are written; place them before assigning the HOME role. If it ran,
   force-stop first (or wipe its data) — a running DataStore overwrites the file.
6. **Owner decisions to confirm:** (a) the Lawnchair grid 5 x 7 / icons 120 % / dock labels are the owner's taste,
   kept as design choices; (b) global Regal + Custom 50/50/50 + full refresh every 15 as the ROM default for every
   app (KOReader/Keep/Kindle have profiles); (c) ZenOS: bake in any of the owner's changes? Live vs defaults:
   quick settings hide Wi-Fi, restart, front-light and warmth (front light is in the Android shade); navbar without
   labels, tabs history + stats on, favorites off; library font Noto Sans instead of the bundled Hyperreadable
   (Hebrew titles); custom icons on; page browser off; mosaic group views; verbose chapter time. Hiding Wi-Fi /
   front-light / warmth in ZenOS looks ROM-level; the rest looks personal. (d) KOReader's own Quickstart guide opens
   on the first start (`quickstart_shown_version` not set) in addition to the ZenOS quickstart — keep upstream
   behavior or suppress it.
7. **KOReader UI language** is not set (owner's Hebrew excluded): KOReader starts in its environment default.
8. **einkui fallback** `org.b1051c.einkctl.SET_LIGHT` has no receiver in E-Ink Control (see `einkui-settings.md`).
