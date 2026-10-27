# InKognito for the Bigme B1051C Pro

**Less bloat. More ink.**

InKognito is a custom Android 16 build for the **Bigme B1051C Pro**, the 10.3" Kaleido color e-ink tablet
(MediaTek MT6877, Wi-Fi only). It puts a clean, current Android on the tablet and adds the e-ink layer a
generic Android image is missing on this panel: e-ink refresh modes, color handling, pen and touch tuning,
front light control and the magnetic cover. It also comes with a reading-first setup: KOReader, a simple
launcher and Google Play.

> **This is alpha 2** (`InKognito-B1051CPro-alpha2-20261027`), an early test build.
> It was built and checked offline. At release time the full install and the first boot had **not yet been
> run on a tablet**. Make a full backup before you install, and only install it if you are comfortable
> restoring that backup. See [KNOWN-ISSUES.md](KNOWN-ISSUES.md).

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
- KOReader bridge: KOReader works together with E-Ink Control.

### Look and feel
- An InKognito theme for the notification shade and Quick Settings (light and dark), with cold/warm light
  sliders right in the shade.
- **Lawnchair 15 Beta 3** as the home screen, with the InKognito icon pack (Bigme-style icons),
  a transparent navigation row on the home screen and spray-paint style navigation buttons.
- A patched Launcher3QuickStep with a Recents (stash) fix.
- Boot animation (light and dark), the Rubik system font, and Dark theme with one switch.

### Apps and services
- **KOReader 2026.07.1** with Bigme e-ink patches, plus the derainbowify and ZenOS UI plugins.
- **MindTheGapps 16 core** (Google Play services, Play Store, Google Services Framework) and the
  **Google Setup Wizard**.
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
- A computer with **Windows, macOS or Linux**, Android **platform-tools**, and a good USB cable.
- Battery at **50% or more**.
- A **full backup** made with mtkclient: <https://github.com/bkerler/mtkclient>. Strongly recommended.
  It is your way back if anything goes wrong.
- You accept that **everything on the tablet's internal storage is erased**. Your microSD card is not touched.

## What is in the download

| File | What it is | Written to |
|---|---|---|
| `system.img` | TrebleDroid Android 16 GSI (`android_16.0.0_r1`, `ci-20250617`, arm64 bvS userdebug) with the InKognito layer built in | `system_<slot>` |
| `boot.img` | Bigme's stock kernel (firmware 4.9.0) with Magisk 30.7, the microSD fix and the off-mode charging setup | `boot_<slot>` |
| `vbmeta.img` | Stock firmware 4.9.0 vbmeta with dm-verity hashtree checking off (`flags=1`, HASHTREE_DISABLED) | `vbmeta_<slot>` |
| `logo.img` | InKognito boot logo and charging picture (optional) | `logo` |
| `flash-inkognito.sh` | Installer for macOS and Linux | |
| `flash-inkognito.bat` | Installer for Windows | |
| `SHA256SUMS` | Checksums. The installers check every image against them before they start. | |
| `README.md`, `INSTALL.md`, `KNOWN-ISSUES.md`, `CHANGELOG.md`, `CREDITS.md` | Documentation | |

`<slot>` is the tablet's active slot, `a` or `b`. The installer finds it for you.
Nothing else is written: no vendor, dtbo, lk, preloader, tee, seccfg, nvram or any other partition.

## Quick start

Full step-by-step instructions are in **[INSTALL.md](INSTALL.md)**. In short:

1. Unlock the bootloader, then make a **full mtkclient backup**.
2. Install platform-tools on your computer, turn on **USB debugging** on the tablet, and connect it.
3. Charge the tablet to at least 50%.
4. Unzip the download and run the installer from that folder. Try a dry run first; it only prints what
   would happen:
   - macOS / Linux: `bash flash-inkognito.sh --dry-run`, then `bash flash-inkognito.sh`
   - Windows: `flash-inkognito.bat --dry-run`, then `flash-inkognito.bat`
5. Check the summary and type `FLASH` to start.
6. Wait about 5 minutes. The tablet **restarts once by itself** during the first start, then shows Google
   setup.
7. After setup, turn **USB debugging off** (see [KNOWN-ISSUES.md](KNOWN-ISSUES.md)).

## More

- [INSTALL.md](INSTALL.md): installation, troubleshooting, and how to go back
- [KNOWN-ISSUES.md](KNOWN-ISSUES.md): what does not work yet
- [CHANGELOG.md](CHANGELOG.md): what changed
- [CREDITS.md](CREDITS.md): the projects InKognito is built on, and their licenses

## Source and license

InKognito's own code is open source under the MIT license:
<https://github.com/matanmi12/bigme-b1051c-android16>.
Third-party components keep their own licenses. See [CREDITS.md](CREDITS.md).

InKognito is an independent community project. It is not made, supported or endorsed by Bigme.
Installing it is at your own risk; see the disclaimer in [INSTALL.md](INSTALL.md).
