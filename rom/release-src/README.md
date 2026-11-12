# InKognito for the Bigme B1051C Pro

**Less bloat. More ink.**

InKognito is a custom Android 16 build for the **Bigme B1051C Pro**, the 10.3" Kaleido color e-ink tablet
(MediaTek MT6877, Wi-Fi only). It puts a clean, current Android on the tablet and adds the e-ink layer a
generic Android image is missing on this panel: e-ink refresh modes, color handling, pen and touch tuning,
front light control and the magnetic cover. It also comes with a reading-first setup: KOReader, a simple
launcher and Google Play (or, if you prefer, no Google at all).

> **This is beta 1** (`InKognito-B1051CPro-beta1-20261112`), a test build.
> A full install, its first start and going back to stock were run on a real tablet (alpha 6, 2026-11-11), and
> what that run found is fixed here. This build itself was installed on a real tablet as an update
> (2026-11-12). A fresh install of this build and the image without Google have **not yet been run on a
> tablet**. The installer always backs up first; only install it if you are comfortable
> going back with that backup. See [KNOWN-ISSUES.md](KNOWN-ISSUES.md).

---

## What you get

### E-ink display
- Refresh modes: **Auto, HD 256, Fast, Extreme, Regal, A2, GC16**, plus **Pen (ink layer)**, a low-latency
  mode for writing with the pen (experimental).
- A GC16 refresh once the screen settles, and a full refresh every N updates.
- Bigme's color pipeline for the Kaleido panel.
- Touch, pen, scrolling and cover events tuned for e-ink by a small helper service (einkd), with the stock
  touch and pen orientation.
- Magnetic cover: close it to sleep, open it to wake.
- microSD fix: the card speed is capped at 50 MHz so that writing to the card works reliably.

### E-Ink Control (app)
- Refresh and color modes, with **per-app profiles**.
- Cold and warm **front light**.
- Quick Settings tiles: Refresh mode, Clear screen, Color, Grayscale, Front light, Anti-shake,
  Color restoration, Cover, and more.
- A refresh button in the navigation bar.
- A shutdown screen with the animated InKognito badge.
- Dark theme sync: the wallpaper and the keyboard follow Android's Dark theme.
- A refresh mode of its own for the keyboard: Fast while you type, A2 for the swipe trail, and a cleanup of the
  keyboard area after a pause.
- KOReader bridge: KOReader works together with E-Ink Control.

### Look and feel
- An InKognito theme for the notification shade and Quick Settings (light and dark), with cold/warm light
  sliders right in the shade.
- **Lawnchair 15 Beta 3** as the home screen, with the InKognito icon pack (Bigme-style icons),
  a transparent navigation row on the home screen and spray-paint style navigation buttons.
- A patched Launcher3QuickStep with a Recents (stash) fix and a **Clear all** button that is there as soon as
  Recents opens, next to Screenshot and Split screen.
- Boot animation (light and dark), the Rubik system font, and Dark theme with one switch.
- The tablet identifies itself as **InKognito B1051C Pro** (USB, About tablet, Bluetooth, your Google device
  list).

### Keyboard
- **HeliBoard 4.2**, an open-source keyboard without internet permission, with an e-ink look: white keys with a
  black outline, no key pop-ups, light and dark.
- **Swipe typing** in the download with Google (it uses Google's swipe library from MindTheGapps).
- It follows your system languages. A Hebrew dictionary is included (HeliBoard has none built in).
- Handwriting is not part of it: install Gboard from the Play Store if you want it.

### Apps and services
- **KOReader 2026.07.1** with Bigme e-ink patches, plus the derainbowify and ZenOS UI plugins.
- **MindTheGapps 16 core** (Google Play services, Play Store, Google Services Framework) and the
  **Google Setup Wizard**, or a second system image without any Google apps.
- **InKognito Setup**: after the first setup, switch off the parts you don't want (KOReader, Lawnchair, the icon
  pack) and they are removed.
- **Magisk 30.7** (root).
- Tweaks for a Wi-Fi-only tablet without a fingerprint sensor.

### What is not included
- No other Google or Amazon apps. Install the ones you want from the Play Store.
- Nothing personal: no accounts, no Wi-Fi networks, no user settings. Your tablet starts fresh.

## Screenshots

_Screenshots will be added here._

<!-- integrator: add screenshots taken on a clean test install, never from a personal device -->

## Requirements

- A **Bigme B1051C Pro**. No other model.
- Stock firmware **B1051C_R_4.9.0**. No other firmware version.
- An **unlocked bootloader**. InKognito does not unlock it for you. Guide:
  <https://vbh.ai/unlocking-the-bootloader-and-rooting-the-hibreak-pro/> (written for the HiBreak Pro; the
  steps are the same for the B1051C, according to the BigmeB1051CProPatcher project by dbenjaminb).
- A computer with **Windows, macOS or Linux**, **Python 3.8+**, **mtkclient** (<https://github.com/bkerler/mtkclient>,
  the tool used to unlock the bootloader), Android **platform-tools** (adb), a good USB cable and about 20 GB free.
- Battery at **50% or more**.
- You accept that **everything on the tablet's internal storage is erased**. Your microSD card is not touched.
  The installer backs up every partition it changes first; that backup is your way back.

## What is in the download

| File | What it is |
|---|---|
| `system.img` | TrebleDroid Android 16 GSI (`android_16.0.0_r1`, `ci-20250617`, arm64 bvS userdebug) with the InKognito layer and Google Play built in. Written to `system_<slot>` inside `super`. |
| `system-nogapps.img` | The same without any Google apps or services. |
| `kit/` | InKognito's own parts for the boot image (Magisk 30.7 files, the charging-screen setting) and the boot logo pictures. |
| `inkognito.py` | The installer (install, update, restore-stock, backup, prepare). Python, standard library only. |
| `flash-inkognito.sh`, `flash-inkognito.bat` | Short front ends for macOS/Linux and Windows. |
| `SHA256SUMS` | Checksums. The installer checks every file against them before it starts. |
| `README.md`, `INSTALL.md`, `KNOWN-ISSUES.md`, `CHANGELOG.md`, `CREDITS.md`, `LICENSES.md`, `licenses/` | Documentation |

**Nothing of Bigme's is in the download.** The installer builds `boot`, `vbmeta` and the boot `logo` on your
computer from your own tablet's backup: the stock kernel and device tree stay byte-identical (plus the 50 MHz
microSD cap), and Magisk and the InKognito parts are added the way Magisk's own patcher does it.

`<slot>` is the tablet's active slot, `a` or `b`; the installer finds it. Written are `boot_<slot>`,
`vbmeta_<slot>`, `logo` (optional), `system_<slot>`, and `metadata`/`userdata` are erased. Nothing else: no
vendor, dtbo, lk, preloader, tee, seccfg, nvram, waveform or any other partition.

## Quick start

The ten-step quick start with what you should see after each step, and the full instructions, are in
**[INSTALL.md](INSTALL.md)**. In short:

1. Unlock the bootloader. Install Python, mtkclient and platform-tools.
2. Turn on **USB debugging** on the tablet. Bigme's settings hide the Build number; the free app Activity
   Launcher (F-Droid) opens that screen. Connect the tablet and charge it to at least 50%.
3. Unzip the download and run the installer from that folder. Try a dry run first; it only prints what
   would happen:
   - macOS / Linux: `bash flash-inkognito.sh --dry-run`, then `bash flash-inkognito.sh`
   - Windows: `flash-inkognito.bat --dry-run`, then `flash-inkognito.bat`
4. The installer backs up (about 12.5 GB, with a progress line), checks everything, shows a summary: type
   `FLASH` to start.
5. If the screen stays dark afterwards, hold power for 3 to 5 seconds. Wait about 5 minutes. The tablet
   **restarts once by itself** during the first start, then shows setup.
6. After setup, **InKognito Setup** opens once. Then **restart the tablet once** and turn **USB debugging off**
   (see [KNOWN-ISSUES.md](KNOWN-ISSUES.md)).

Later: `update` installs a newer release and keeps your data; `restore-stock --backup <folder>` goes back to
stock.

## More

- [INSTALL.md](INSTALL.md): installation, updating, troubleshooting, and how to go back
- [KNOWN-ISSUES.md](KNOWN-ISSUES.md): what does not work yet
- [CHANGELOG.md](CHANGELOG.md): what changed
- [CREDITS.md](CREDITS.md): the projects InKognito is built on, and their licenses

## Source and license

InKognito's own code is open source under the MIT license:
<https://github.com/matanmi12/bigme-b1051c-android16>.
Third-party components keep their own licenses. See [CREDITS.md](CREDITS.md).

InKognito is an independent community project. It is not made, supported or endorsed by Bigme.
Installing it is at your own risk; see the disclaimer in [INSTALL.md](INSTALL.md).
