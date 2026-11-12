# Known issues: InKognito beta 1

Release `InKognito-B1051CPro-beta1-20261112`. This list will change as testers report back.

## Status of this release

- **This is a beta.** A full install (with the data erase), its first start with Google setup and InKognito
  Setup, and `restore-stock` were run on a real tablet with alpha 6 (the developer's tablet, 2026-11-11). The
  problems that run found are fixed in this release. `update` has run on a real tablet with alpha 5, alpha 6
  and alpha 7 (the build before this one; it was not published, and beta 1 is alpha 7 plus the changes listed
  in CHANGELOG.md). The fix for the first start of a fresh install has not been confirmed by a second clean
  install yet, which is why the restart after setup is still asked for. The installer always backs up first; keep that backup.
- **Not yet run on a tablet:** the image without Google (`system-nogapps.img`, Android's own setup instead of
  Google's), and a tablet whose bootloader is already stuck on the hidden dm-verity warning (see "Hidden
  power-off warning" below).
- The installer is also run end to end against a simulated tablet before every release: a disk image laid out
  like the B1051C Pro and filled from a stock 4.9.0 backup, with a stand-in for mtkclient.
- The boot image the installer builds holds the same kernel, device tree and ramdisk contents as the one on the
  developer's tablet. Only the ramdisk's gzip compression differs: Python's zlib instead of Magisk's zopfli.

## Installing

- **The first start restarts once by itself.** Boot 1 installs the InKognito layer and reboots before any
  setup screen appears. This is expected; let it run.
- **Restart once after setup (temporary).** On the alpha 6 test install, part of the display layer's
  permissions only loaded from the second start on, and until then the display ran with some operations
  blocked. This release fixes that, but the fix has not yet been confirmed on a second clean install. Until it
  is, restart the tablet once after InKognito Setup (power menu, Restart).
- **"Magisk needs an internet connection" right after the first start.** If an app or a computer asks for root
  in the first minute, before Magisk has finished its own setup, this message can appear. Close it (Cancel).
  Nothing needs to be downloaded; wait until the home screen is up before you use root.
- **After the installer's final restart the tablet may stay dark or show the charging picture.** Hold power for
  3 to 5 seconds and let go; once more if the charging picture shows (INSTALL.md, Troubleshooting).
- **USB debugging on Bigme's stock firmware** needs a helper app, because Bigme's settings hide the Build
  number (INSTALL.md, section 4).
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

## Apps

- **Phone and Messaging are switched off.** The tablet has no SIM slot, so the dialer and the SMS app are
  disabled for the user (first install, and once on an update). Settings › Apps › See all apps brings them
  back. Contacts stays.
- **Module updates through the Magisk app are new and untested.** The display, Launcher3 and keyboard modules
  now carry an update link, so a later fix can arrive without a full release. No such update has been
  published or run yet.

## Keyboard

- **Swipe typing only in the download with Google.** HeliBoard has no swipe library of its own; the one it uses
  is Google's and comes with the Google apps. In the download without Google there is no swipe typing unless
  you load a library yourself in HeliBoard's settings.
- **No handwriting.** Install Gboard from the Play Store if you want handwriting.
- **No word suggestions in some text fields.** Fields that ask the keyboard for no suggestions get none. The
  search field in Settings is one of them. Try a normal text field before you assume a problem.
- **A language needs a dictionary** for suggestions and swipe typing. HeliBoard has English and a number of
  other languages built in, and InKognito adds a Hebrew dictionary. For a language without one, add a
  dictionary in HeliBoard's settings.
- **Android's own (AOSP) keyboard is switched off**, not removed. It can be switched back on in Settings,
  Apps.
- **After an update from alpha 6** the keyboard becomes HeliBoard only if your default keyboard was still
  Android's own.

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
- **A root request while the screen is off is denied.** Magisk's question opens behind the lock screen and
  counts as "deny" after 10 seconds. Ask again with the screen on. E-Ink Control and Lock & Sleep are allowed
  root during the first start and are not affected.
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

Post in the XDA thread with the installer output, the slot letter it printed, and what the tablet showed.
Please do not post serial numbers, your GSF Android ID, Wi-Fi network names or account names.
