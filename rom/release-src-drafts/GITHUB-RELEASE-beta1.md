<!-- DRAFT, NOT PUBLISHED.
     Tag: beta1-20261112   Title: InKognito beta 1 (2026-11-12)   Mark as pre-release.
     Assets: the two zips and their .sha256 files, the three module zips the update links point to
     (b1051c-eink-compat-v0.1.55.zip, b1051c-launcher3-v0.2.7.zip, b1051c-heliboard-v0.2.1.zip), plus the
     HeliBoard v4.2 source archive
     (HeliBoard-4.2-source.tar.gz from https://github.com/Helium314/HeliBoard/archive/refs/tags/v4.2.tar.gz).
     Fill the two <<SHA256 after build>> values from the .sha256 files before posting. -->

# InKognito beta 1 for the Bigme B1051C Pro

**Less bloat. More ink.** Android 16 (TrebleDroid GSI) for the Bigme B1051C Pro, with an e-ink layer for refresh
modes, color, front light, pen, touch and the magnetic cover, plus a reading-first setup (KOReader, Lawnchair).

> **Beta.** A full install (which erases the tablet), its first start and `restore-stock` were run on a real
> tablet with alpha 6 on 2026-11-11, and `update` with alpha 5, alpha 6 and alpha 7 (not published). This release fixes what the full
> install found. The image **without Google has not yet been run on a tablet**. The installer always makes a
> backup first. Keep it.

## Downloads (pick one)

| File | What it is | SHA-256 |
|---|---|---|
| `InKognito-B1051CPro-beta1-20261112-gapps.zip` | With Google Play (Play Store, Play services, Google setup) | `<<SHA256 after build>>` |
| `InKognito-B1051CPro-beta1-20261112-nogapps.zip` | Without any Google apps or services | `<<SHA256 after build>>` |
| `HeliBoard-4.2-source.tar.gz` | Source code of the bundled keyboard (GPL-3.0), unchanged from upstream tag `v4.2` | |
| `b1051c-*.zip` (three files) | Magisk modules already inside the images; here only as the download for module updates through the Magisk app. You do not need them to install. | |

Requirements: B1051C Pro on stock firmware **B1051C_R_4.9.0**, unlocked bootloader, Python 3.8+, mtkclient,
platform-tools, battery at 50% or more, about 20 GB free on the computer. `INSTALL.md` in the zip starts with a
ten-step quick start.

Coming from alpha 6: `bash flash-inkognito.sh update` (Windows: `flash-inkognito.bat update`) keeps your apps
and settings.

## What changed since alpha 6

New in beta 1:

- **Phone and Messaging are switched off** (the tablet has no SIM slot). Settings › Apps brings them back.
- **Icons for the keyboard and Calendar** in the style of the rest of the icon set.
- **Module updates through the Magisk app** for the display, Launcher3 and keyboard modules (new, not yet run).
- The result of the first-start setup is kept in `adb shell getprop persist.debug.inkognito.status`.
- An update no longer stops at a Google Play Protect question.

Fixed, found by the first full install on a real tablet:

- **Display permissions on a fresh install.** After the data erase, the security rules of the InKognito modules
  were only loaded from the second start on, so the display layer ran with some operations blocked until a
  restart. The first start now loads them itself. Until a second clean install confirms it, **restart once
  after setup**.
- **App icons in the navigation row.** Both launchers filled a dock on the first start, and the icons showed
  behind the Back button. Both docks now start empty.
- **Screen timeout.** The 10-minute default now applies (it stayed at two minutes).
- **"WebView Browser Test"**, a test app of the base system, is hidden.
- The first-start setup also writes its steps to Android's log: `adb logcat -d -s InKognito` (no root needed).

New:

- **Device name "InKognito B1051C Pro"** instead of "TrebleDroid vanilla" (USB, About tablet, Bluetooth, Google
  device list).
- **"Clear all" in Recents**, next to Screenshot and Split screen, there as soon as Recents opens.
- **HeliBoard 4.2 keyboard** with an e-ink look (outlined keys, no pop-ups, light and dark). Swipe typing in
  the download with Google. A Hebrew dictionary is included. For handwriting, install Gboard.
- **A refresh mode of its own for the keyboard:** Fast while typing, A2 for the swipe trail, a cleanup of the
  keyboard area after a pause.
- **Color contrast Medium** by default.

Installer:

- **Progress line** during the backup, every write and readback, and `restore-stock`: partition, percent, size,
  speed, time left.
- The closing message now says to restart once after setup and to turn USB debugging off at the end.
- **INSTALL.md:** quick start at the top, and how to reach Developer options on Bigme's stock firmware (its
  settings hide the Build number; Activity Launcher from F-Droid opens that screen).

The full history is in `CHANGELOG.md`, and open problems are listed in `KNOWN-ISSUES.md`.

## Reporting problems

Please include the installer output (or its `inkognito.log`) and the slot letter it printed. Never post your
backup folder, serial numbers, GSF Android ID, Wi-Fi names or account names.

Source: this repository (MIT). Credits and third-party licenses: `CREDITS.md` and `LICENSES.md` in the zip.
