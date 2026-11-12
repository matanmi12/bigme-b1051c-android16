<!--
DRAFT, NOT PUBLISHED. Thread text for XDA in BBCode (paste it into the XDA editor in BBCode mode).
Replace every {{...}} placeholder before posting. Suggested thread title:

  [ROM][16][BETA] InKognito for the Bigme B1051C Pro - Less bloat. More ink.

Post as the public handle matanmi12 only.
-->

[CENTER][SIZE=7][B]InKognito[/B][/SIZE]
[SIZE=5][I]Less bloat. More ink.[/I][/SIZE]
Android 16 for the Bigme B1051C Pro - beta 1
[/CENTER]

[QUOTE][B]Beta release.[/B] A full install, its first start and going back to stock were run on a real tablet with alpha 6 (2026-11-11). This release fixes what that run found and was itself installed on a real tablet as an update (2026-11-12). [B]A fresh install of this release and the image without Google have not yet been run on a tablet.[/B] The installer always backs up first; only install it if you are comfortable going back with that backup. Read the disclaimer at the end of this post.[/QUOTE]

[SIZE=5][B]What is InKognito?[/B][/SIZE]
InKognito is a custom Android 16 build for the Bigme B1051C Pro, the 10.3" Kaleido color e-ink tablet (MediaTek MT6877, Wi-Fi only). It runs the TrebleDroid Android 16 GSI and adds the e-ink layer a generic image is missing on this panel: refresh modes, color handling, pen and touch tuning, front light control and the magnetic cover. On top of that you get a reading-first setup with KOReader, Lawnchair, an e-ink styled keyboard and Google Play.

Bigme's kernel and vendor partition stay as they are, and nothing of Bigme's is in the download: the installer builds boot, vbmeta and the boot logo on your computer from your own tablet's backup. It writes only system, boot, vbmeta and (optionally) the boot logo, through mtkclient, with a backup first and a readback of every write.

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
[*]A refresh mode of its own for the keyboard: Fast while typing, A2 for the swipe trail, a cleanup of the keyboard area after a pause
[*]KOReader bridge
[/LIST]
[B]Look and feel[/B]
[LIST]
[*]InKognito theme for the shade and Quick Settings (light and dark), with cold/warm light sliders in the shade
[*]Lawnchair 15 Beta 3 as home, InKognito icon pack (Bigme-style icons, with our own for the keyboard and Calendar), transparent navigation row on the home screen, spray-paint navigation buttons
[*]Patched Launcher3QuickStep: Recents stash fix, and a "Clear all" button that is there as soon as Recents opens
[*]Boot animation (light and dark), Rubik system font, Dark theme with one switch
[*]The tablet identifies itself as "InKognito B1051C Pro" (USB, About tablet, Bluetooth)
[/LIST]
[B]Keyboard[/B]
[LIST]
[*]HeliBoard 4.2 (open source, no internet permission) with an e-ink look: outlined keys, no key pop-ups, light and dark
[*]Swipe typing in the download with Google
[*]Follows your system languages; a Hebrew dictionary is included
[*]Handwriting: install Gboard from the Play Store
[/LIST]
[B]Apps and services[/B]
[LIST]
[*]KOReader 2026.07.1 with Bigme e-ink patches, derainbowify and ZenOS UI plugins
[*]MindTheGapps 16 core (Play services, Play Store, Google Services Framework) and Google Setup Wizard
[*]Magisk 30.7 (root)
[*]Tweaks for Wi-Fi only and no fingerprint sensor; Phone and Messaging are switched off (no SIM slot), Contacts stays
[*]Module updates through the Magisk app for the display, Launcher3 and keyboard modules (new, not yet run)
[/LIST]
Not included: other Google or Amazon apps (get them from the Play Store), and anything personal. The tablet starts fresh.

[SIZE=5][B]Requirements[/B][/SIZE]
[LIST]
[*]Bigme B1051C Pro only, on stock firmware [B]B1051C_R_4.9.0[/B] only
[*][B]Unlocked bootloader[/B]. Guide (written for the HiBreak Pro; the steps are the same for the B1051C according to dbenjaminb's BigmeB1051CProPatcher): [URL]https://vbh.ai/unlocking-the-bootloader-and-rooting-the-hibreak-pro/[/URL]
[*]A computer with Windows, macOS or Linux, Python 3.8+, mtkclient ([URL]https://github.com/bkerler/mtkclient[/URL]), Android platform-tools (adb) and about 20 GB free
[*]Battery at 50% or more
[*]Everything on internal storage is erased. The microSD card is not touched.
[/LIST]

[SIZE=5][B]Downloads[/B][/SIZE]
[LIST]
[*]With Google Play: InKognito-B1051CPro-beta1-20261112-gapps.zip: {{DOWNLOAD LINK GAPPS}}
[*]Without Google: InKognito-B1051CPro-beta1-20261112-nogapps.zip: {{DOWNLOAD LINK NOGAPPS}}
[*]SHA-256: {{SHA256 GAPPS}} (with Google), {{SHA256 NOGAPPS}} (without Google)
[/LIST]
Each zip contains one system image (system.img with Google Play, or system-nogapps.img without any Google apps), the installer inkognito.py with front ends for macOS/Linux and Windows, the InKognito boot and logo parts (kit/), SHA256SUMS and the documentation (README, INSTALL, KNOWN-ISSUES, CHANGELOG, CREDITS, LICENSES).

[SIZE=5][B]Installation[/B][/SIZE]
INSTALL.md in the zip starts with a ten-step quick start that says what you should see after each step, followed by the full instructions, updating, troubleshooting and how to go back. Short version:
[LIST=1]
[*]Unlock the bootloader. Install Python, mtkclient and platform-tools.
[*]Turn on USB debugging. Bigme's stock settings hide the Build number: install Activity Launcher from F-Droid ([URL]https://f-droid.org/packages/de.szalkowski.activitylauncher/[/URL]) in the tablet's browser, open Settings > About tablet with it, tap Build number seven times, then switch on USB debugging in Developer options. (Alternative: "Settings Shortcut" from Google Play.)
[*]Connect the tablet, accept "Allow USB debugging?", charge it to at least 50%.
[*]Unzip, open a terminal in that folder, and do a dry run first (it runs neither mtkclient nor adb):
[CODE]bash flash-inkognito.sh --dry-run      (macOS / Linux)
flash-inkognito.bat --dry-run          (Windows)[/CODE]
[*]Run the installer for real. It restarts the tablet into mtkclient, backs up every partition it will change (about 12.5 GB), checks the backup, builds boot/vbmeta/logo, shows a summary: type FLASH. A progress line shows how far every long read and write is.
[CODE]bash flash-inkognito.sh
flash-inkognito.bat[/CODE]
[*]If the screen stays dark or shows the charging picture afterwards, hold power for 3 to 5 seconds.
[*]Wait about 5 minutes. The first start restarts once by itself, then setup appears, then InKognito Setup lets you drop the parts you don't want.
[*]After InKognito Setup, restart the tablet once (power menu, Restart).
[*]Turn USB debugging off.
[/LIST]
[B]Updating[/B] to a newer release keeps your data (also from alpha 6): [CODE]bash flash-inkognito.sh update[/CODE]
[B]Going back[/B] to stock from the backup: [CODE]bash flash-inkognito.sh restore-stock --backup B1051C-backup-<date-time>[/CODE]
The installer never writes any partition other than boot, vbmeta, logo, system (inside super, without changing its partition table), metadata and the start of userdata, and it stops at the first error.

[SIZE=5][B]Known issues[/B][/SIZE]
[LIST]
[*]Beta. Not yet run on a tablet: a fresh install of this release (updating to it has been), and the image without Google
[*]The first start restarts once by itself (expected)
[*]Restart once after setup (temporary): on the alpha 6 test install part of the display layer's permissions only loaded from the second start on. This release fixes that, but the fix is not yet confirmed on a second clean install
[*]If something asks for root in the first minute after the first start, a "Magisk needs an internet connection" message can appear. Close it; nothing needs to be downloaded
[*]A root request while the screen is off is denied after 10 seconds. Ask again with the screen on
[*]Keyboard: swipe typing only in the download with Google; no handwriting (install Gboard); no word suggestions in fields that switch them off, such as the Settings search field
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

When you report a problem, please include the installer output (or its inkognito.log) and the slot letter it printed. Never post your backup folder. Do not post serial numbers, your GSF Android ID, Wi-Fi network names or account names.

[SIZE=5][B]Credits[/B][/SIZE]
InKognito stands on the work of many projects:
[LIST]
[*][B]TrebleDroid[/B] (phhusson and contributors): the Android 16 GSI underneath
[*][B]Magisk[/B] (topjohnwu): root and the module system
[*][B]awong-dev/hibreak-pro-color[/B]: the HWC shim this display layer started from
[*][B]Lawnchair[/B], [B]KOReader[/B] with [B]ZenOS[/B] (AnthonyGress / xZenLabs) and [B]derainbowify[/B] (Euphoriyy)
[*][B]HeliBoard[/B] (Helium314 and contributors): the keyboard, and the Hebrew dictionary from its dictionary collection (CC BY-SA 4.0)
[*][B]MindTheGapps[/B]: the Google apps packaging
[*][B]Vasu's Journal[/B] (vbh.ai) and [B]dbenjaminb[/B] (BigmeB1051CProPatcher): unlock and root groundwork
[*][B]Rubik[/B], [B]Lilita One[/B] and [B]Material Design icons[/B]
[/LIST]
Full credits and licenses: CREDITS.md in the zip.

[SIZE=5][B]Source code[/B][/SIZE]
[LIST]
[*]InKognito (MIT): [URL]https://github.com/matanmi12/bigme-b1051c-android16[/URL]
[*]Kernel: InKognito does not distribute a kernel; the installer keeps Bigme's stock kernel already on your tablet, unchanged (see LICENSES.md, "Kernel source")
[*]HeliBoard (GPL-3.0): [URL]https://github.com/Helium314/HeliBoard/tree/v4.2[/URL]; the source archive of v4.2 is also attached to the InKognito release
[*]Third-party components: see CREDITS.md and LICENSES.md for each project's source and license
[/LIST]

[SIZE=5][B]Disclaimer[/B][/SIZE]
[QUOTE]InKognito is provided "as is", without warranty of any kind. You install it at your own risk. Unlocking the bootloader and flashing custom software may void your warranty, and a mistake can leave your tablet unable to start until you restore a backup. Nobody involved in InKognito is responsible for bricked devices, lost data or anything else that happens to your tablet. InKognito is an independent community project and is not made, supported or endorsed by Bigme.[/QUOTE]

[B]Developer:[/B] matanmi12
[B]ROM OS version:[/B] Android 16
[B]Based on:[/B] TrebleDroid Android 16 GSI (android_16.0.0_r1, ci-20250617)
[B]Version:[/B] beta 1 (InKognito-B1051CPro-beta1-20261112)
[B]Status:[/B] Beta
[B]Release date:[/B] {{RELEASE DATE}}
