<!--
DRAFT, NOT PUBLISHED. Thread text for XDA in BBCode (paste it into the XDA editor in BBCode mode).
Replace every {{...}} placeholder before posting. Suggested thread title:

  [ROM][16][ALPHA] InKognito for the Bigme B1051C Pro - Less bloat. More ink.

Post as the public handle matanmi12 only.
-->

[CENTER][SIZE=7][B]InKognito[/B][/SIZE]
[SIZE=5][I]Less bloat. More ink.[/I][/SIZE]
Android 16 for the Bigme B1051C Pro - alpha 1
[/CENTER]

[QUOTE][B]Alpha release.[/B] InKognito alpha 1 was built and checked offline. At release time the full install and the first start had [B]not yet been run on a tablet[/B]. Make a full mtkclient backup before installing, and only install it if you are comfortable restoring that backup. Read the disclaimer at the end of this post.[/QUOTE]

[SIZE=5][B]What is InKognito?[/B][/SIZE]
InKognito is a custom Android 16 build for the Bigme B1051C Pro, the 10.3" Kaleido color e-ink tablet (MediaTek MT6877, Wi-Fi only). It runs the TrebleDroid Android 16 GSI and adds the e-ink layer a generic image is missing on this panel: refresh modes, color handling, pen and touch tuning, front light control and the magnetic cover. On top of that you get a reading-first setup with KOReader, Lawnchair and Google Play.

Bigme's kernel and vendor partition stay as they are. The install writes only system, boot, vbmeta and (optionally) the boot logo.

{{SCREENSHOTS: add 3 to 5 screenshots from a clean test install - never from a personal device}}

[SIZE=5][B]Features[/B][/SIZE]
[B]E-ink display[/B]
[LIST]
[*]Refresh modes Auto, HD 256, Fast, Extreme, Regal, A2 and GC16, plus Pen (ink layer), a low-latency pen mode (experimental)
[*]GC16 settle refresh and a full refresh every N updates
[*]Bigme's color pipeline for the Kaleido panel (HWC shim, a port of awong-dev's hibreak-pro-color hwcshim)
[*]Touch, pen, scroll and cover handling for e-ink (einkd), stock touch and pen orientation
[*]Magnetic cover sleep and wake
[*]microSD fix (50 MHz cap, so writes to the card work)
[/LIST]
[B]E-Ink Control app[/B]
[LIST]
[*]Refresh and color modes with per-app profiles
[*]Cold and warm front light
[*]Quick Settings tiles: Refresh mode, Clear screen, Color, Grayscale, Front light, Anti-shake, Color restoration, Cover and more
[*]Refresh button in the navigation bar
[*]Shutdown screen with the animated InKognito badge
[*]Dark theme sync (wallpaper, keyboard)
[*]KOReader bridge
[/LIST]
[B]Look and feel[/B]
[LIST]
[*]InKognito theme for the shade and Quick Settings (light and dark), with cold/warm light sliders in the shade
[*]Lawnchair 15 Beta 3 as home, InKognito icon pack (Bigme-style icons), transparent navigation row on the home screen, spray-paint navigation buttons
[*]Patched Launcher3QuickStep (Recents stash fix)
[*]Boot animation (light and dark), Rubik system font, Dark theme with one switch
[/LIST]
[B]Apps and services[/B]
[LIST]
[*]KOReader 2026.07.1 with Bigme e-ink patches, derainbowify and ZenOS UI plugins
[*]MindTheGapps 16 core (Play services, Play Store, Google Services Framework) and Google Setup Wizard
[*]Magisk 30.7 (root)
[*]Tweaks for Wi-Fi only and no fingerprint sensor
[/LIST]
Not included: other Google or Amazon apps (get them from the Play Store), and anything personal. The tablet starts fresh.

[SIZE=5][B]Requirements[/B][/SIZE]
[LIST]
[*]Bigme B1051C Pro only, on stock firmware [B]B1051C_R_4.9.0[/B] only
[*][B]Unlocked bootloader[/B]. Guide (written for the HiBreak Pro; the steps are the same for the B1051C according to dbenjaminb's BigmeB1051CProPatcher): [URL]https://vbh.ai/unlocking-the-bootloader-and-rooting-the-hibreak-pro/[/URL]
[*]A computer with Windows, macOS or Linux and up-to-date Android platform-tools
[*]Battery at 50% or more
[*]A full backup with mtkclient, strongly recommended: [URL]https://github.com/bkerler/mtkclient[/URL]
[*]Everything on internal storage is erased. The microSD card is not touched.
[/LIST]

[SIZE=5][B]Downloads[/B][/SIZE]
[LIST]
[*]InKognito-B1051CPro-alpha1-20261021.zip: {{DOWNLOAD LINK}}
[*]SHA-256 of the zip: {{ZIP SHA256}}
[/LIST]
The zip contains system.img, boot.img, vbmeta.img, logo.img, the installers (flash-inkognito.sh for macOS/Linux, flash-inkognito.bat for Windows), SHA256SUMS and the documentation (README, INSTALL, KNOWN-ISSUES, CHANGELOG, CREDITS).

[SIZE=5][B]Installation[/B][/SIZE]
Full step-by-step instructions, troubleshooting and how to go back are in [B]INSTALL.md[/B] in the zip. Short version:
[LIST=1]
[*]Unlock the bootloader, then make a full mtkclient backup.
[*]Install platform-tools, turn on USB debugging, connect the tablet, charge it to at least 50%.
[*]Unzip, open a terminal in that folder, and do a dry run first (it runs no adb or fastboot command at all):
[CODE]bash flash-inkognito.sh --dry-run      (macOS / Linux)
flash-inkognito.bat --dry-run          (Windows)[/CODE]
[*]Run the installer for real, check the summary, and type FLASH:
[CODE]bash flash-inkognito.sh
flash-inkognito.bat[/CODE]
[*]Wait about 5 minutes. The first start restarts once by itself, then Google setup appears.
[*]After setup, turn USB debugging off.
[/LIST]
The installer checks every image against SHA256SUMS, checks the tablet (firmware, screen, battery, bootloader unlocked, partition sizes, active slot), writes vbmeta, boot and the logo in bootloader mode, then system in fastbootd with the slot letter spelled out, wipes data, and sets the slot active as its last step. It never writes any other partition and stops at the first error.

[SPOILER="Manual install (advanced)"]
Replace <slot> with your active slot (a or b). Stop at the first error.
[CODE]adb reboot bootloader
fastboot getvar current-slot
fastboot flash vbmeta_<slot> vbmeta.img
fastboot flash boot_<slot> boot.img
fastboot flash logo logo.img
fastboot reboot fastboot
fastboot getvar current-slot
fastboot getvar partition-size:vendor_<slot>     (if 0x0: STOP)
fastboot getvar snapshot-update-status           (if not none: fastboot snapshot-update cancel)
fastboot flash system_<slot> system.img
fastboot -w                                      (no "Erasing 'userdata'" line? then: fastboot erase userdata, fastboot erase metadata)
fastboot set_active <slot>
fastboot reboot[/CODE]
[/SPOILER]

[SIZE=5][B]Known issues[/B][/SIZE]
[LIST]
[*]Alpha: the full install and first start had not been run on a tablet at release time
[*]The first start restarts once by itself (expected)
[*]On the developer's tablet, fastbootd once came up on the other slot. The installer handles it (explicit slot suffix, set_active at the end, and a stop if fastbootd's partition table looks outdated). This has not been tested on a device yet.
[*]"Hyper" refresh mode is not available (the kernel rejects it for this panel size)
[*]Pen (ink layer) mode is experimental
[*]Not Play Protect certified (GSI). Register your GSF Android ID at [URL]https://www.google.com/android/uncertified[/URL] if Google complains; sign-in can be skipped during setup
[*]USB debugging is on by default; the tablet asks before it trusts a computer. Only allow your own computer (userdebug: an allowed computer can use adb root). Turn it off when you do not need it
[*]If the tablet powers off about 5 seconds into starting with nothing on screen: that is the bootloader's hidden dm-verity warning. Switch on, and when the screen flashes, press power briefly about once per second for about 10 seconds
[*]No fingerprint sensor support; no cellular (Wi-Fi only device)
[*]Front light: two channels (cold/warm) in the shade; Android's brightness slider is replaced
[*]Off-mode charging shows a static picture
[/LIST]
Full list: KNOWN-ISSUES.md in the zip.

When you report a problem, please include the installer output and the slot letters it printed. Do not post serial numbers, your GSF Android ID, Wi-Fi network names or account names.

[SIZE=5][B]Credits[/B][/SIZE]
InKognito stands on the work of many projects. {{CREDITS: paste the short list from CREDITS.md}}
Full credits and licenses: CREDITS.md in the zip.

[SIZE=5][B]Source code[/B][/SIZE]
[LIST]
[*]InKognito (MIT): [URL]https://github.com/matanmi12/bigme-b1051c-android16[/URL]
[*]Kernel: {{KERNEL SOURCE / GPL NOTE: the boot image carries Bigme's stock kernel; link or statement from CREDITS.md}}
[*]Third-party components: see CREDITS.md for each project's source and license
[/LIST]

[SIZE=5][B]Disclaimer[/B][/SIZE]
[QUOTE]InKognito is provided "as is", without warranty of any kind. You install it at your own risk. Unlocking the bootloader and flashing custom software may void your warranty, and a mistake can leave your tablet unable to start until you restore a backup. Nobody involved in InKognito is responsible for bricked devices, lost data or anything else that happens to your tablet. InKognito is an independent community project and is not made, supported or endorsed by Bigme.[/QUOTE]

[B]Developer:[/B] matanmi12
[B]ROM OS version:[/B] Android 16
[B]Based on:[/B] TrebleDroid Android 16 GSI (android_16.0.0_r1, ci-20250617)
[B]Version:[/B] alpha 1 (InKognito-B1051CPro-alpha1-20261021)
[B]Status:[/B] Alpha
[B]Release date:[/B] {{RELEASE DATE}}
