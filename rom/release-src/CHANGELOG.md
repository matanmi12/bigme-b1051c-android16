# Changelog

## alpha 2 (`InKognito-B1051CPro-alpha2-20261027`), 2026-10-27

**Status:** built and checked offline, like alpha 1. The full install and first start have not yet been run on a
tablet with this package; every part in it runs on the developer's tablet. See [KNOWN-ISSUES.md](KNOWN-ISSUES.md).

- **Night mode = Android's Night Light** (Settings > Display > Night Light and its quick-settings tile, with
  Android's schedule): no amber tint on the panel; instead the front light goes to its warm, dim Night setting
  and Dark theme turns on. Intensity goes from Night to Bed (amber only). Off brings back your light and theme.
- **Scrolling like stock:** a finger drag in HD 256 or Regal scrolls in Fast, then one full refresh cleans up
  (one per scroll, also for several in a row; none when nothing moved). Per app: Fast, Extreme or off.
- **Touch off:** a "Touch" quick-settings tile (pen only until the screen goes off), and touch is ignored
  automatically while the pen is near in apps that use the Pen mode, so the hand can rest on the screen. A finger
  already on the glass is lifted for Android first, so nothing stays "pressed".
- **Modes for parts of the screen:** when only the navigation buttons change, only their row is redrawn, in Fast;
  a playing video puts the screen in Extreme and gets one clean when it stops; the notification shade / quick
  settings and Recents have their own profile rows (Fast by default). All adjustable in E-Ink Control.
- **Color:** Color boost (OKLCh chroma, +60 % by default) and Darker text (reading apps) as settings, quick-settings
  tiles and per-app fields; stock HD 256 smoothing on by default; a full refresh after Fast no longer comes out in
  black and white.
- **Front light presets:** Morning, Night, Bed and Color (the most neutral grays), plus a "Light preset" tile.
- einkd: devices connected later (a Bluetooth page turner) count for the automatic full refresh; the pen stream
  goes only to the display shim, and the shim accepts only einkd; frames drawn during a pen session are no
  longer lost.
- E-Ink Control 0.15.19, display module eink-compat 0.1.47, Lock & Sleep 0.1.2, SystemUI plugin 0.4.8,
  Bigme icons 0.1.8.

### Since alpha 1, also in this release

- **Lock & Sleep** (new app, `org.b1051c.sleep`): the lock screen's look (InKognito clock and date line, or
  Android's; what covers the wallpaper), the picture the panel keeps while the tablet sleeps (the InKognito badge,
  one of your pictures, or pictures from a folder in turn; fit or fill; date and battery on it) and the shutdown
  picture choice, all in one place. The sleep picture is drawn ahead of time and the display shim puts it on the
  panel as one full refresh when the screen goes to sleep, so falling asleep is not delayed. E-Ink Control 0.15.11
  no longer shows these settings and relays wake, battery, date and theme changes to the new app.
- Display shim (eink-compat 0.1.31–0.1.35): the color pass converts only the rows that changed into the shim's own
  commit buffers (fenced against frames still being rendered), vivid off in Fast / Extreme / A2, new optional color
  stages (black and white levels, OKLCh chroma, tone, LUT switch; all off by default), a requested full refresh
  re-runs the color pass, and the sleep picture (standby frame).
- SystemUI plugin (einkui 0.4.3–0.4.7): InKognito lock screen (Lilita One clock mirroring SystemUI's, own date line,
  corner shortcut hidden), lock-screen scrim choice, light sliders forced left-to-right.
- E-Ink Control 0.15.5–0.15.11: review fixes (persistent root shell with timeout, foreground app from the window
  list, KOReader sender check, accessibility status and one-tap enable, TalkBack tile states, keyboard-driven
  sliders), the settings moved to Lock & Sleep.
- Launcher3 patch module 0.2.4: refuses a different system image (fingerprint guard).

## alpha 1 (`InKognito-B1051CPro-alpha1-20261021`), 2026-10-21

First public test release of InKognito for the Bigme B1051C Pro (firmware B1051C_R_4.9.0).

**Status:** built and checked offline. At release time the full install and first start had not yet been
run on a tablet. See [KNOWN-ISSUES.md](KNOWN-ISSUES.md).

### Base

- System: TrebleDroid Android 16 GSI, `android_16.0.0_r1`, `ci-20250617` (arm64 bvS, userdebug), with the
  InKognito layer built into `/system/etc/inkognito` and started by `/system/etc/init/inkognito.rc`.
- Boot: Bigme's stock kernel from firmware 4.9.0 (March 2026), Magisk 30.7, the microSD 50 MHz cap and the
  off-mode charging setup.
- vbmeta: stock firmware 4.9.0 vbmeta with `flags=1` (HASHTREE_DISABLED).
- Logo (optional): InKognito boot logo and charging picture.
- No other partition is changed.
- Screen density 350 dpi by default (stock Bigme uses 300). Settings, Display size changes it.
- USB debugging is on, but the tablet asks before it trusts a computer (`ro.adb.secure=1` in the image).
- Anti-shake (Bigme's anti-flicker) works: the e-ink debug nodes are opened as on stock and the display HAL may write
  them (it failed on every refresh before). Off by default: measured, it does nothing in Regal and in Fast/Extreme it
  makes scrolling smoother but each update ~50% slower. Turn it on per app (E-Ink Control profile) or in the shade.

### E-ink

- E-ink display stack for the GSI: an HWC shim (a port of awong-dev/hibreak-pro-color's hwcshim) with Bigme's
  color pipeline.
- Refresh modes Auto, HD 256, Fast, Extreme, Regal, A2 and GC16, plus Pen (ink layer), a low-latency pen mode
  (experimental).
- GC16 settle refresh and a full refresh every N updates.
- einkd: touch, pen, scroll and cover (hall sensor) input helper; stock touch and pen orientation.
- Magnetic cover sleep and wake.
- microSD fix (50 MHz cap).

### E-Ink Control

- Refresh and color modes, per-app profiles.
- Cold and warm front light.
- Quick Settings tiles: Refresh mode, Clear screen, Color, Grayscale, Front light, Anti-shake, Color
  restoration, Cover, and more.
- Refresh button in the navigation bar.
- Shutdown screen with the animated InKognito badge.
- Dark theme sync (wallpaper, keyboard).
- KOReader bridge.

### Look and feel

- E-ink SystemUI plugin: InKognito shade and Quick Settings theme (light and dark), cold/warm light sliders.
- Lawnchair 15 Beta 3 as the default home screen, InKognito icon pack (Bigme-style icons), transparent
  navigation row on the home screen, spray-paint navigation buttons.
- Patched Launcher3QuickStep (Recents stash fix).
- Boot animation (light and dark), Rubik system font, Dark theme with one switch.

### Apps and services

- KOReader 2026.07.1 with Bigme e-ink patches, derainbowify and ZenOS UI plugins.
- MindTheGapps 16 core (Play services, Play Store, Google Services Framework) and Google Setup Wizard.
  Google and Amazon apps beyond that are not bundled.
- Magisk 30.7.
- Tweaks for Wi-Fi only and no fingerprint sensor.

### First start

- Boot 1 installs the InKognito layer and restarts by itself. Boot 2 shows Google setup while E-Ink Control,
  the SystemUI plugin, Lawnchair, KOReader, Magisk and the icon pack are installed and configured in the
  background (log: `/data/adb/b1051c/provision.log`).

### Installer

- `flash-inkognito.sh` (macOS, Linux) and `flash-inkognito.bat` (Windows): SHA-256 check of every image,
  adb and bootloader checks, a typed `FLASH` confirmation, explicit slot suffixes, `set_active` as the last
  step, and `--dry-run`, `--skip-adb`, `--slot`, `--no-logo` and `--strict-slot` options.
