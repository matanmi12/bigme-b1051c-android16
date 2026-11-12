# Changelog

## beta 1 (`InKognito-B1051CPro-beta1-20261112`), 2026-11-12

**Status:** the first beta. It is alpha 7 (below; alpha 7 was run as an update on a real tablet on 2026-11-11
but never published) plus the changes here. Coming from alpha 6 you get both sections. This release was
installed on a real tablet as an update (2026-11-12); a fresh install of it and the image without Google have
not been run on a tablet yet. See [KNOWN-ISSUES.md](KNOWN-ISSUES.md).

New:

- **Phone and Messaging are switched off.** The tablet has no SIM slot, so the dialer and the SMS app no longer
  show in the app list. First install, and once on an update; Settings › Apps › See all apps brings them back.
  Contacts stays.
- **Icons for the keyboard and Calendar** in the style of the rest of the icon set (Settings, Recents and, with
  the icon pack, the home screen).
- **Icon pack and icons module made for the ROM:** they cover the system apps, the apps InKognito installs and a
  fixed list of widely used apps (Kindle, Chrome, Gmail, Keep, Photos, Docs and the like).
- **Module updates through the Magisk app.** The display module (eink-compat), the Launcher3 module and the
  keyboard module carry an update link. When a newer version of one of them is published, the Magisk app offers
  it, so a fix can arrive without a full release. Nothing is downloaded unless you accept the update. This has
  not been run on a tablet yet.
- **The result of the first-start setup without root, for good:** besides Android's log, the last result is kept
  in a property that survives restarts: `adb shell getprop persist.debug.inkognito.status`.

Fixed:

- **Updating without a Play Protect question** (found by the alpha 7 update): see the alpha 7 section.

Display module eink-compat 0.1.55, Launcher3 module 0.2.7, keyboard module 0.2.1 (the same code as in alpha 7,
with the update link); icons module 0.1.9; E-Ink Control 0.15.24.

## alpha 7 (`InKognito-B1051CPro-alpha7-20261111`), 2026-11-11 (not published)

**Status:** a full install, its first start and `restore-stock` were run on a real tablet for the first time
(alpha 6, 2026-11-11). This release fixes what that run found and adds a new keyboard and a Recents button. The
image without Google has still not been run on a tablet. See [KNOWN-ISSUES.md](KNOWN-ISSUES.md).

Fixed (found by the first full install):

- **Display permissions on a fresh install:** after the data erase, the security rules of the InKognito modules
  were not loaded on the first start with setup, only from the next restart on. Until then the display layer
  ran with some operations blocked. The first start now loads them itself. Until this is confirmed on a second
  clean install, restart once after setup (INSTALL.md, section 9).
- **App icons in the navigation row:** on the first start both launchers filled a dock of their own, and the
  icons showed behind the Back button. Both docks now start empty. First install only; your own layout is never
  touched.
- **"WebView Browser Test"**, a test app of the base system, no longer shows in the app list.
- **Screen timeout:** the 10-minute default now applies. Before, the screen went off after two minutes.
- **Updating without a Play Protect question:** while an update installs InKognito's own apps, Google Play
  Protect's check of computer-installed apps is paused and put back right after, as on a first install. Before,
  a "send the app for a security check?" dialog could hold the update until someone answered it.
- **Checking the first start without root:** every finished setup step is also written to Android's log
  (`adb logcat -d -s InKognito`).

New:

- **Device name: InKognito B1051C Pro.** USB, Settings › About tablet, Bluetooth and your Google device list
  showed "TrebleDroid vanilla". The build number now reads
  "InKognito alpha 7 (TrebleDroid 16, BP2A.250605.031.A3)". On an update, the device name and the Bluetooth
  name change only if you had not renamed them.
- **"Clear all" in Recents:** a button next to Screenshot and Split screen that is there as soon as Recents
  opens. Before, Clear all was only at the far end of the list.
- **New keyboard: HeliBoard 4.2** instead of Android's own keyboard. Open source, no internet permission.
  - An e-ink look: white keys with a black outline, a short bar under a pressed key instead of a pop-up, light
    and dark (it follows Dark theme).
  - **Swipe typing** in the download with Google.
  - Follows your system languages; a Hebrew dictionary is included.
  - Word suggestions without automatic replacement, key sound on, clipboard history off.
  - Handwriting is not included: install Gboard from the Play Store.
  - On an update, the keyboard changes only if your default was still Android's own keyboard.
- **A refresh mode of its own for the keyboard:** Fast while you type, A2 for the swipe trail (finger or pen),
  and one cleanup of the keyboard area after a pause. While you swipe in an app that updates results as you
  go, only the keyboard is refreshed; the rest of the screen follows once when you stop. The modes and the
  pause are under "Keyboard" in E-Ink Control.
- **Color contrast: Medium** by default (Settings › Accessibility › Color contrast). First install only.

Installer:

- **Progress line** during every long read and write (backup, system write, readback, `restore-stock`): the
  partition, percent, size done, speed and time left. Before, the screen showed nothing for many minutes.
- **Closing message:** restart once after setup; allow USB debugging when the tablet asks, and turn it off at
  the end.
- **INSTALL.md:** a ten-step quick start at the top; how to reach Developer options on Bigme's stock firmware
  (its settings hide the Build number); a troubleshooting entry for a tablet that stays off after the install.

E-Ink Control 0.15.24, display module eink-compat 0.1.54, Launcher3 module 0.2.6, new module b1051c-heliboard.

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
