# Changelog

## alpha 6 (`InKognito-B1051CPro-alpha6-20261110`), 2026-11-10

**Status:** built and checked offline like alpha 5. `update` was run once on a real tablet (alpha 5 over
the developer's tablet); a full install and its first start have still not been run on one. See
[KNOWN-ISSUES.md](KNOWN-ISSUES.md).

Found by the first real flash (alpha 5, `update` on the developer's tablet, 2026-11-10):

- **First-start setup fixed:** the setup scripts wrote the output of Android commands (`pm`, `am`, `settings`,
  `cmd`, ...) straight into their log under `/data/adb`. Android's system server may not write there, so those
  commands failed ("Failed transaction"): every app update in `update` failed, and an `install` would have lost most
  of its defaults, grants and app setup. The output now goes through a pipe. An update that could not install an
  app now says so in the log and in a notification.
- **Installer:** mtkclient and adb no longer inherit the terminal's input (with piped input they could take the
  answer meant for the `FLASH` prompt); a closed input at a prompt now cancels cleanly instead of crashing.
- **Two downloads:** one zip with Google and one without (each under 2 GiB), instead of one zip with both images.
- **Installer and INSTALL.md:** after the final restart the tablet may stay dark or show the charging picture;
  both now say to hold power for 3 to 5 seconds.

## alpha 5 (`InKognito-B1051CPro-alpha5-20261109`), 2026-11-09

**Status:** built and checked offline like alpha 4; the install and first start have still not been run on a real
tablet. See [KNOWN-ISSUES.md](KNOWN-ISSUES.md).

- **Smaller Google setup:** Google Partner Setup is no longer included (Setup Wizard does not need it).
- **Taskbar long-press fixed:** long-pressing an app in the taskbar (or in its app drawer) while Lawnchair is the
  home screen no longer crashes the system launcher and freezes the screen; the menu (App info, Split screen, app
  shortcuts) and dragging an app into split screen work.
- **"Switch system" tile hidden:** it is no longer offered in the Quick Settings tile editor. It only works with a
  stock dynamic-system image, which InKognito does not install.

## alpha 4 (`InKognito-B1051CPro-alpha4-20261109`), 2026-11-09

**Status:** built and checked offline like alpha 3; the install and first start have still not been run on a real
tablet. See [KNOWN-ISSUES.md](KNOWN-ISSUES.md).

- **Sleep picture:** shows when the tablet fell asleep ("asleep since 22:51"). The display module writes the time at
  the moment the panel goes off; the date and battery come from the same moment. New choice: the cover of the
  book open in KOReader (a KOReader patch is included). Text over a picture sits on a page-colored plate.
- **Power while asleep** (Lock & Sleep): Wi-Fi turns off after 15 minutes asleep and comes back when the tablet
  wakes (5, 30 or 60 minutes, or never). Optional power-off after 6 hours to 3 days asleep, off by default: the
  screen wakes for a moment with the light off, so the shutdown picture reaches the panel.
- **Apps in the background:** choose apps to keep at Android's "Restricted" battery level, several at once.
- **Lock screen:** the battery level after the date (switch in Lock & Sleep).
- **Lock & Sleep settings file:** export and import.
- **E-ink broadcasts from other apps:** `com.xrz.GlobalRefresh` and `EINK_FORCE_CLEAR` give one full refresh;
  `EINK_REFRESH_SPEED_CLEAR` / `BALANCED` / `SMOOTH` / `FAST` set Regal / HD 256 / Fast / Extreme for the app on
  screen until the next app switch. For Tasker and apps made for Bigme or other e-ink Android builds. Switch in
  E-Ink Control, Refresh mode.
- **Quick Settings taps:** a normal tap on a tile body now toggles it or opens its dialog. Before, with animations
  off (the e-ink default), any press longer than about 0.1 s counted as a long press and opened the app's settings.
  A press of about half a second still opens them.
- E-Ink Control 0.15.22, display module eink-compat 0.1.49, Lock & Sleep 0.2.6, SystemUI plugin 0.4.10.

## alpha 3 (`InKognito-B1051CPro-alpha3-20261027`), 2026-10-27

**Status:** built and checked offline; the installer was run end to end against a simulated tablet. The install
and first start have not yet been run on a real tablet. See [KNOWN-ISSUES.md](KNOWN-ISSUES.md).

- **New installer, `inkognito.py`, through mtkclient instead of fastboot.** No fastbootd step any more (on the
  developer's tablet fastbootd once started on the other slot and caused a boot loop). One Python script for
  Windows, macOS and Linux; `flash-inkognito.sh` / `.bat` are short front ends.
- **Backup first, always.** `install` backs up every partition it will change (about 12.5 GB) and refuses to write
  without a complete, checksummed backup. Every write is read back and compared.
- **Going back:** `restore-stock --backup <folder>` puts the stock firmware back from that backup.
- **Updates without losing data:** `update` installs a newer release over an older one; on the first start the
  InKognito modules and apps are updated, settings stay, and parts you removed stay removed.
- **No Bigme files in the download.** boot, vbmeta and the boot logo are built on your computer from your own
  tablet's backup; the kernel stays byte-identical.
- **Choice of system image:** with Google Play (`system.img`) or without any Google apps (`system-nogapps.img`).
- **InKognito Setup** (E-Ink Control 0.15.20): after the first setup, switch off KOReader, the Lawnchair home
  screen or the icon pack to remove them, and switch Anti-shake on if you want it. Reopen it any time from E-Ink
  Control, System.
- Safety checks before anything is written: the partition table, the active slot (from the tablet and from adb),
  a pending Bigme system update (Virtual A/B), stock boot and vbmeta, and where `system_<slot>` lies in `super`.
  The installer refuses in code to touch preloader, lk, seccfg, nvram, waveform and similar partitions.
- mtkclient's output is logged with the chip and storage IDs removed, so the log can be posted.

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
