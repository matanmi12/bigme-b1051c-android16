# Known issues: InKognito alpha 6

Release `InKognito-B1051CPro-alpha6-20261110`. This list will change as testers report back.

## Status of this release

- **This is an alpha.** It was built and checked offline. The installer (install, update, restore-stock) was run
  end to end against a simulated tablet: a disk image laid out like the B1051C Pro and filled from a stock 4.9.0
  backup, with a stand-in for mtkclient. The `update` path was run once on a real tablet
  (alpha 5 over the developer's tablet, 2026-11-10; the fixes it led to are in this release). At release time a
  full install (with the data erase) and its first start had **not yet been run on a real tablet**. The installer always backs up first; keep that backup.
- The boot image the installer builds holds the same kernel, device tree and ramdisk contents as the one on the
  developer's tablet. Only the ramdisk's gzip compression differs: Python's zlib instead of Magisk's zopfli.
  A boot image built this way has booted on the developer's tablet (2026-11-10, through `update`).
- `update` has run twice on a real tablet (alpha 5, then alpha 6). Not yet run on a tablet: a full `install`, the
  image without Google (`system-nogapps.img`, Android's own setup instead of Google's) and the InKognito Setup
  screen (it opens only after a full install).

## Installing

- **The first start restarts once by itself.** Boot 1 installs the InKognito layer and reboots before any
  setup screen appears. This is expected; let it run.
- **mtkclient has to catch the tablet while it starts.** The installer restarts it over adb; without adb, hold
  power for about 12 seconds with the cable connected. The e-ink screen may show nothing while this happens.
- **Only over stock firmware 4.9.0.** The installer builds the InKognito boot image from your stock one; a boot
  image rooted with Magisk, or from another firmware, is refused before anything is written.
- **"Orange state" notice.** With an unlocked bootloader, the bootloader shows a notice for about 5 seconds at
  every start. The e-ink panel may not display it. This is normal.
- **Hidden power-off warning.** If the tablet ever switches itself off about 5 seconds into starting, with
  nothing on screen, that is the bootloader's dm-verity warning: the panel does not show it, and the state can
  stick after a verification failure. Workaround: switch on, and when the screen flashes, press power briefly
  about once per second for about 10 seconds (INSTALL.md, Troubleshooting).
- **e-ink during boot.** The screen can sit unchanged for a minute or more at a time while the tablet starts.
- **Going back to stock** erases your data unless your backup also holds `userdata` (INSTALL.md, section 12).

## Display and pen

- **"Hyper" refresh mode is not available.** The kernel rejects it for this panel size.
- **Anti-shake is off by default.** Turn it on from its Quick Settings tile, or per app in an E-Ink Control
  profile. It makes scrolling look smoother in Fast and Extreme, but each screen update takes longer. In Regal it
  makes no difference.
- **Pen (ink layer) mode is experimental.** InKognito draws a provisional stroke right away, and the app's own
  stroke then replaces it.

## Google

- **Not Play Protect certified** (`system.img`). InKognito is based on a GSI. If Google sign-in or the Play Store complains,
  register the tablet's GSF Android ID at <https://www.google.com/android/uncertified>. You can skip sign-in
  during setup and do it later.
- Only the MindTheGapps core (Play services, Play Store, Google Services Framework) and the Google Setup
  Wizard are included. Install other Google or Amazon apps from the Play Store.

## Security

- **USB debugging is on by default, and a computer must be allowed first.** The first time a computer
  connects, the tablet asks "Allow USB debugging?". Only allow your own computer. This is a userdebug build,
  so an allowed computer can get a root shell with `adb root`. Turn USB debugging off in Developer options
  when you do not need it (INSTALL.md, section 10).
- Root (Magisk 30.7) is included and enabled.
- **The system is signed with public test keys.** The TrebleDroid GSI this release is built on is a userdebug
  build signed with the publicly known AOSP test keys. An app signed with those keys can be given system-level
  permissions. Install apps only from sources you trust. This is normal for GSI-based ROMs,
  but you should know it.

## Hardware

- **Fingerprint sensor: not supported.**
- **No cellular.** The B1051C Pro is a Wi-Fi-only tablet.
- **Front light:** the two channels (cold and warm) are controlled from sliders in the notification shade.
  Android's own brightness slider is replaced by them.
- **Off-mode charging** (charging while the tablet is off) shows a static picture, with no battery percentage
  or animation.

## Reporting a problem

Post in the XDA thread with the installer output, the slot letters it printed, and what the tablet showed.
Please do not post serial numbers, your GSF Android ID, Wi-Fi network names or account names.
