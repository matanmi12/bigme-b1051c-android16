# InKognito alpha 1 — ROM build spec (2026-10-21, private)

Owner-approved plan: ~/.claude/plans/smooth-sparking-raven.md. Owner decisions (2026-10-20 23:45):
full system.img + fastboot flashing; Bigme files may ship (boot image with the Bigme kernel, logo partition image,
colour LUT compiled into the shim, Bigme icon RROs); apps = our own + open source + MindTheGapps core + Google
Setup Wizard (Google/Amazon Play apps are NOT bundled); personal settings excluded.

Brand: **InKognito** — "Less bloat. More ink." Release name: `InKognito-B1051CPro-alpha1-20261021`.
Public author handle: **matanmi12** (never the owner's real name or email). Public repo:
https://github.com/matanmi12/bigme-b1051c-android16 (MIT).

## Device facts
- Bigme B1051C Pro (10.3" Kaleido colour e-ink, MT6877, Wi-Fi only), firmware **B1051C_R_4.9.0** only.
  Stock Android: `ro.build.versionName=B1051C_R_4.9.0`, `ro.build.versionCode=B1051C`. Any ROM:
  `ro.vendor.build.version.incremental=mp1V15125`, vendor fingerprint
  `alps/vnd_k6877v1_64_k419/vnd_k6877v1_64_k419:12/SP1A.210812.016/mp1V15125:user/dev-keys`, `wm size` physical 1860x2480.
- LK fastboot (bootloader): `product=k6877v1_64_k419`, `unlocked=yes` required, `partition-size:waveform=0x1000000`
  (e-ink only), `partition-size:super=0x300000000`, `partition-size:boot_a/boot_b=0x2800000`, max-download-size 0x8000000.
  A/B with Virtual A/B; `current-slot` a or b.
- **Known hazard (developer's device, 2026-10-17):** `fastboot reboot fastboot` came up in fastbootd on slot A although
  slot B was active, and rewrote bootctrl (A active). Slot A was not bootable → boot loop. Mitigation in our script:
  flash logical partitions with an explicit suffix (`system_b`), and `fastboot set_active <slot>` at the very end.
- Bootloader unlock is a prerequisite (not done by us). Community guide (identical steps for B1051C, per
  dbenjaminb/BigmeB1051CProPatcher): https://vbh.ai/unlocking-the-bootloader-and-rooting-the-hibreak-pro/
  Strongly recommend a full mtkclient backup first (https://github.com/bkerler/mtkclient).
- Unlocked bootloader → LK shows an "orange state" notice for ~5 s that the e-ink panel may not display; normal.
- If the device ever powers off ~5 s into boot with nothing on screen: that is LK's hidden dm-verity warning (sticky
  state from a verity failure). Workaround: power on, then short power presses ~1/s for ~10 s.

## Package contents (files to flash)
| File | Partition | What |
|---|---|---|
| `system.img` | `system_<slot>` (logical, via fastbootd) | TrebleDroid A16 GSI `android_16.0.0_r1 ci-20250617` (arm64 bvS userdebug) + InKognito payload in /system/etc/inkognito + hook /system/etc/init/inkognito.rc |
| `boot.img` | `boot_<slot>` | Bigme stock kernel (Mar 2026, fw 4.9.0) + Magisk 30.7 + microSD 50 MHz DT cap + off-mode charging rc |
| `vbmeta.img` | `vbmeta_<slot>` | stock fw 4.9.0 vbmeta with flags=1 (HASHTREE_DISABLED) |
| `logo.img` (optional) | `logo` | InKognito boot logo (LK image 0, cream badge) + charging picture |
Nothing else is written (no vendor, dtbo, lk, preloader, tee, seccfg, nvram...).

## First boot (what the user sees)
1. LK logo. Android formats /data. **Boot 1 installs the InKognito layer and reboots by itself** (before any UI).
2. Boot 2: boot animation, then **Google setup** (Wi-Fi, Google account optional, restore). Then the Lawnchair home.
3. In the background during setup: E-Ink Control, the e-ink SystemUI plugin, Lawnchair, KOReader, Magisk and the
   icon pack are installed and configured. Log: /data/adb/b1051c/provision.log (root).
Total ~5 min. If Google setup ever gets stuck: `adb shell settings put secure user_setup_complete 1` and
`adb shell settings put global device_provisioned 1` (USB debugging is on by default on this userdebug build).

## Features (for docs)
- E-ink display stack for the GSI: HWC shim (port of awong-dev/hibreak-pro-color hwcshim, Bigme colour pipeline,
  refresh modes Auto/HD 256/Fast/Extreme/Regal/A2/GC16 + "Pen (ink layer)" low-latency pen mode, GC16 settle
  refresh, full refresh every N), einkd (touch/pen/scroll/hall input helper), stock touch/pen orientation,
  magnetic cover sleep/wake, microSD fix (50 MHz cap).
- E-Ink Control app: refresh/colour modes, per-app profiles, cold/warm front light, QS tiles (Refresh mode, Clear
  screen, Color, Grayscale, Front light, Anti-shake, Color restoration, Cover, ...), nav-bar refresh button,
  shutdown screen (animated InKognito badge), Dark theme sync (wallpaper, keyboard), KOReader bridge.
- E-ink SystemUI plugin: InKognito shade/QS theme (light + dark), cold/warm light sliders.
- Launcher: Lawnchair 15 Beta 3 default + InKognito icon pack (Bigme-style icons), transparent nav row on home,
  spray-paint nav buttons, patched Launcher3QuickStep (Recents stash fix, "Clear all" in the Recents action row).
- Keyboard (alpha 7): HeliBoard 4.2 as a system app (module b1051c-heliboard) with the InKognito outlined-key RRO,
  staged prefs + Hebrew dictionary, its own refresh handling in the shim (Fast typing, A2 swipe trail, GC16 of the
  keyboard area after a pause); Google's swipe library comes with the gapps module only.
- Identity (alpha 7): build-rom.py sets ro.product.product.model = "InKognito B1051C Pro" and ro.build.display.id =
  "InKognito alpha N (TrebleDroid 16, BP2A.250605.031.A3)"; fingerprint/device/name/brand are never edited.
- Provisioning (alpha 7): stage 1 creates Magisk's preinit store when it is missing, stage 2 checks every module
  sepolicy rule against the running policy and loads missing ones live; stage lines go to logcat tag InKognito.
- Boot animation (light/dark), Rubik system font, Dark theme (one switch).
- KOReader 2026.07.1 with Bigme e-ink patches, derainbowify and ZenOS UI plugins.
- MindTheGapps 16 core (Play services, Play Store, GSF) + Google Setup Wizard. Wi-Fi-only/fingerprint-less tweaks.

## Known issues (alpha)
- Alpha. Built and checked offline; the full flash + first boot has NOT been run on a device yet at release time
  (state this honestly unless updated).
- "Hyper" refresh mode is not available (the kernel rejects it for this panel size).
- Pen (ink layer) mode is experimental (provisional ink is drawn by us, then the app's own stroke replaces it).
- Device is not Play Protect certified (GSI). Register the GSF Android ID at https://www.google.com/android/uncertified
  if Google sign-in or Play complains; sign-in can be skipped in setup and done later.
- USB debugging is on; since 2026-10-21 (owner) the image sets ro.adb.secure=1, so a computer must be allowed on the
  tablet first; an allowed computer gets `adb root` (userdebug). Density 350 dpi (owner, module 0.1.27).
- Anti-shake ships off (`anti_flicker=false`); it works since module 0.1.28 and is meant per app.
- Long-press on a taskbar app icon can crash Launcher3 while Lawnchair is home (owner-seen 21.10; fix later).
- The GSI is signed with the public AOSP test keys (userdebug); say so in KNOWN-ISSUES.
- Fingerprint sensor not supported. No cellular (Wi-Fi only device).
- Front light: two channels (cold/warm) in the shade; Android's brightness slider is replaced.
- Off-mode charging shows a static picture (no battery percentage animation).

## Personal data rules
Never include: Wi-Fi networks/SSIDs, accounts, GSF ID / ANDROID_ID, serial `<SERIAL>`, MAC/BSSID,
home LAN IPs, the owner's real name or email, book titles/library, KOReader history/stats/sidecars, the Lawnchair
home layout (launcher.db), front-light levels, screenshots. Use stage_public.py redaction patterns as the scanner.
