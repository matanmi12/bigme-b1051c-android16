# Known issues: InKognito alpha 2

Release `InKognito-B1051CPro-alpha2-20261027`. This list will change as testers report back.

## Status of this release

- **This is an alpha.** It was built and checked offline. At release time the full install (the flash) and
  the first start had **not yet been run on a tablet**. Make a full mtkclient backup before you install
  (INSTALL.md, step 5), and expect rough edges.

## Installing

- **The first start restarts once by itself.** Boot 1 installs the InKognito layer and reboots before any
  setup screen appears. This is expected; let it run.
- **fastbootd and the active slot.** On the developer's tablet, `fastboot reboot fastboot` once came up on
  the other slot and changed the active slot, which caused a boot loop. The installer handles this by writing
  `system_<slot>` with an explicit slot letter and setting `<slot>` active as its last step. This mitigation
  has not been tested on a device yet. The installer also stops before writing system if fastbootd's view of
  the partition table does not match your slot (`vendor_<slot>` reported as `0x0`), because writing system
  from that view could damage the vendor partition. See INSTALL.md, Troubleshooting.
- **`fastboot -w` may not wipe anything here.** This tablet's fastbootd does not report partition types, and
  fastboot then skips the wipe. The installer notices this and erases `userdata` and `metadata` directly. If
  you install by hand, check that `fastboot -w` printed an "Erasing 'userdata'" line (INSTALL.md, section 11).
- **"Orange state" notice.** With an unlocked bootloader, the bootloader shows a notice for about 5 seconds at
  every start. The e-ink panel may not display it. This is normal.
- **Hidden power-off warning.** If the tablet ever switches itself off about 5 seconds into starting, with
  nothing on screen, that is the bootloader's dm-verity warning: the panel does not show it, and the state can
  stick after a verification failure. Workaround: switch on, and when the screen flashes, press power briefly
  about once per second for about 10 seconds (INSTALL.md, Troubleshooting).
- **e-ink during boot.** The screen can sit unchanged for a minute or more at a time while the tablet starts.

## Display and pen

- **"Hyper" refresh mode is not available.** The kernel rejects it for this panel size.
- **Anti-shake is off by default.** Turn it on from its Quick Settings tile, or per app in an E-Ink Control
  profile. It makes scrolling look smoother in Fast and Extreme, but each screen update takes longer. In Regal it
  makes no difference.
- **Long-pressing an app icon in the taskbar can crash the system launcher** while Lawnchair is the home screen. The
  launcher restarts by itself. A fix is planned.
- **Pen (ink layer) mode is experimental.** InKognito draws a provisional stroke right away, and the app's own
  stroke then replaces it.

## Google

- **Not Play Protect certified.** InKognito is based on a GSI. If Google sign-in or the Play Store complains,
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
