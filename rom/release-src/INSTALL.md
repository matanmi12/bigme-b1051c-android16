# Installing InKognito beta 1 on the Bigme B1051C Pro

Release `InKognito-B1051CPro-beta1-20261112`. This guide takes you from a stock B1051C Pro to InKognito. Plan for
about an hour the first time, most of it for the backup.

**Before you start, know this:**

- **Everything on the tablet's internal storage is erased**: apps, books, notes, settings. Copy what you want
  to keep to your computer or to the microSD card first. The microSD card is not touched.
- This is a **beta**. A full install, its first start and going back to stock were run on a real tablet
  (alpha 6, 2026-11-11); what that run found is fixed in this release, which was itself installed on a real
  tablet as an update (2026-11-12). A fresh install of this release and the image without Google have not
  been run on a tablet yet. The installer always makes a backup first and checks every write; keep that backup.
- Only for the **B1051C Pro on firmware B1051C_R_4.9.0**. The installer checks this and stops otherwise.

**Disclaimer.** InKognito is provided "as is", without warranty of any kind. You install it at your own risk.
Unlocking the bootloader and flashing custom software may void your warranty, and a mistake can leave the
tablet unable to start until you restore a backup. Nobody involved in InKognito is responsible for damage or
lost data. InKognito is an independent community project and is not made, supported or endorsed by Bigme.

## Quick start

Ten steps. Each one links to its full explanation under [Details](#details). The commands are for macOS and
Linux; on Windows use `flash-inkognito.bat` where a step says `bash flash-inkognito.sh`.

| # | Do this | You should see |
|---|---|---|
| 1 | Check the firmware in the tablet's settings ([details](#1-check-your-tablet)). | **B1051C_R_4.9.0** |
| 2 | Install Python 3.8+, mtkclient and platform-tools ([details](#2-install-python-mtkclient-and-platform-tools)), then run `mtk --help`. | mtkclient's help text |
| 3 | Unlock the bootloader ([details](#3-unlock-the-bootloader)). This usually erases the tablet. | The tablet starts stock Android again. |
| 4 | Turn on USB debugging. Bigme hides the Build number, so use Activity Launcher ([details](#4-turn-on-usb-debugging)). Connect the cable, run `adb devices`, and tap Allow on the tablet. | One line that ends in `device` |
| 5 | Charge the tablet to 50% or more and free about 20 GB on the computer ([details](#5-charge-the-tablet-and-free-disk-space)). | |
| 6 | Unzip the download and open a terminal in the unzipped folder ([details](#6-download-and-unzip)): `cd InKognito-B1051CPro-beta1-20261112` | |
| 7 | Dry run ([details](#7-dry-run)): `bash flash-inkognito.sh --dry-run` | A list of every step. Nothing is run on the tablet. |
| 8 | Install ([details](#8-install)): `bash flash-inkognito.sh` and, at the summary, type `FLASH`. | A progress line during the backup (about 12.5 GB) and each write, then the installer's closing message with what to do next. |
| 9 | If the screen stays dark or shows the charging picture, hold power for 3 to 5 seconds. Then wait about 5 minutes ([details](#9-the-first-start)). | The tablet restarts once by itself, then setup appears. After setup: the home screen and **InKognito Setup**. |
| 10 | After InKognito Setup, **restart the tablet once**, then turn USB debugging off ([details](#10-after-setup)). | The home screen again. Done. |

Later: [updating](#11-updating-to-a-newer-release) keeps your data, and
[going back to stock](#12-going-back-to-stock) uses the backup from step 8.

---

# Details

## How the install works

The installer, `inkognito.py`, talks to the tablet through **mtkclient**, the tool that also unlocks its
bootloader. mtkclient reaches the tablet through MediaTek's boot ROM, which works even when Android does not.
There is no fastboot step: on this tablet, fastboot's "fastbootd" mode once started on the wrong slot and left the
tablet in a boot loop.

In order, the installer:

1. checks the release files and, over adb, the tablet (firmware, screen, battery, active slot);
2. **backs up** every partition it will change, about 12.5 GB, into a folder on your computer;
3. checks the backup: active slot, stock boot image, stock vbmeta, no system update pending, and where
   `system_<slot>` lies inside the `super` partition;
4. **builds** `boot`, `vbmeta` and the boot `logo` from your own tablet's partitions (nothing of Bigme's is in
   the download; see "What is changed" below);
5. shows a summary and waits for you to type `FLASH`;
6. writes each partition and **reads it back** to compare; it stops at the first difference;
7. erases user data and restarts the tablet.

### What is changed

| Partition | Change |
|---|---|
| `boot_<slot>` | Your stock boot image with Magisk 30.7 added the way Magisk's own patcher adds it, the microSD speed fix (50 MHz) and a setting for the charging screen. The kernel stays byte-identical. |
| `vbmeta_<slot>` | Your stock vbmeta with dm-verity hashtree checking switched off (one byte). |
| `logo` | The boot picture becomes the InKognito badge (optional, `--no-logo` keeps yours). |
| `system_<slot>` | Inside `super`: the InKognito system (TrebleDroid Android 16 GSI + the InKognito layer). The partition table of `super` is not changed. |
| `metadata`, start of `userdata` | Erased; Android formats them on the first start. |

Nothing else is written. The installer refuses, in code, to touch preloader, lk, seccfg, nvram, waveform and the
other device-specific partitions.

## Contents

1. [Check your tablet](#1-check-your-tablet)
2. [Install Python, mtkclient and platform-tools](#2-install-python-mtkclient-and-platform-tools)
3. [Unlock the bootloader](#3-unlock-the-bootloader)
4. [Turn on USB debugging](#4-turn-on-usb-debugging)
5. [Charge the tablet and free disk space](#5-charge-the-tablet-and-free-disk-space)
6. [Download and unzip](#6-download-and-unzip)
7. [Dry run](#7-dry-run)
8. [Install](#8-install)
9. [The first start](#9-the-first-start)
10. [After setup](#10-after-setup)
11. [Updating to a newer release](#11-updating-to-a-newer-release)
12. [Going back to stock](#12-going-back-to-stock)
13. [Troubleshooting](#13-troubleshooting)
14. [Asking for help](#14-asking-for-help)

---

## 1. Check your tablet

You need a **Bigme B1051C Pro** on stock firmware **B1051C_R_4.9.0**. Look at the firmware version in the tablet's
settings (About / system information), or, once adb works (step 4): `adb shell getprop ro.build.versionName`.
On an older firmware, update with Bigme's own system update first, and let it finish completely (restart once
after it).

## 2. Install Python, mtkclient and platform-tools

- **Python 3.8 or newer**: <https://www.python.org/downloads/> (on Windows, tick "Add python.exe to PATH").
  macOS and most Linux distributions already have it: `python3 --version`.
- **mtkclient**: <https://github.com/bkerler/mtkclient>. Follow its README, including the USB driver steps for
  Windows and the udev rules for Linux. If you unlocked the bootloader with it, it is already set up. Check that it
  runs: `mtk --help` (or `python mtk.py --help` in its folder).
- **platform-tools** (adb), Google's official package: <https://developer.android.com/tools/releases/platform-tools>.
  The installer uses adb for its checks and to restart the tablet into the mode mtkclient needs. (fastboot is not
  used.)

If the `mtk` command is not on your PATH, tell the installer where mtkclient is, for example
`--mtk "python C:\mtkclient\mtk.py"`, or put the `mtkclient` folder next to `inkognito.py`.

## 3. Unlock the bootloader

InKognito needs an **unlocked bootloader**; the installer does not unlock it. Guide:
<https://vbh.ai/unlocking-the-bootloader-and-rooting-the-hibreak-pro/>. It is written for the HiBreak Pro;
according to the BigmeB1051CProPatcher project by dbenjaminb, the steps are the same for the B1051C Pro.
Unlocking usually erases the tablet.

After unlocking, the bootloader shows an "orange state" notice for about 5 seconds at every start. The e-ink
screen may not display it. That is normal.

## 4. Turn on USB debugging

USB debugging is switched on in Android's **Developer options**, which appear after you tap **Build number**
seven times. Bigme's own Settings app on the stock firmware does not show the screen with the Build number, so
open it with a small helper app:

1. Connect the tablet to Wi-Fi.
2. In the tablet's browser, open <https://f-droid.org/packages/de.szalkowski.activitylauncher/> and download
   **Activity Launcher** (open source, no account needed). Install the downloaded file; Android asks once to
   allow installs from the browser.
3. Open Activity Launcher, find **Settings** in its list and open the entry **About tablet** (on some versions
   it is named "My device info").
4. Tap **Build number** seven times, until it says you are a developer.
5. Open **Developer options** (in Settings under System, or again through Activity Launcher) and switch
   **USB debugging** on.

Alternative to step 2: the app "Settings Shortcut" from Google Play does the same job.

Then connect the tablet directly (no hub), run `adb devices` on the computer, and accept
**"Allow USB debugging?"** on the tablet. `adb devices` must list one device with the word `device`.

## 5. Charge the tablet and free disk space

- Battery **50% or more**. The installer reads the level and stops if it is lower.
- About **20 GB free** on the computer: 12.5 GB for the backup, the rest for the built images and the
  readback copies.

## 6. Download and unzip

The release comes as **two downloads**. Pick one:

- `InKognito-B1051CPro-beta1-20261112-gapps.zip`: with Google Play (Play Store, Play services, Google setup).
  The system image inside is `system.img`.
- `InKognito-B1051CPro-beta1-20261112-nogapps.zip`: without any Google apps or services; Android's own short
  setup runs instead. The system image inside is `system-nogapps.img`.

Check the download against its `.sha256` file if you like, then unzip it. Keep all files together; the installer
checks the system image and the kit against `SHA256SUMS` before it starts. (`SHA256SUMS` lists both images; the
one you did not download is simply not checked.)

With one image in the folder the installer uses it. If you put both images in one folder, it asks which one you
want, or pass `--variant gapps` / `--variant nogapps`.

## 7. Dry run

Open a terminal (macOS / Linux) or a Command Prompt (Windows) in the release folder:

```
bash flash-inkognito.sh --dry-run          # macOS / Linux
flash-inkognito.bat --dry-run              # Windows
```

This prints every check and every mtkclient command in order and runs neither mtkclient nor adb. Without a
backup it cannot know where `system_<slot>` lies inside `super`, so that line shows a placeholder.

## 8. Install

With the tablet started normally into Android and connected:

```
bash flash-inkognito.sh                    # macOS / Linux   (= python3 inkognito.py install)
flash-inkognito.bat                        # Windows         (you can also double-click it)
```

1. The installer checks the tablet over adb.
2. It starts mtkclient, which waits for the tablet, and restarts the tablet (`adb reboot`). mtkclient catches it
   while it starts. If nothing happens within a minute, hold the power button for about 10 to 12 seconds with the
   cable connected. The e-ink screen may not change at all; watch the computer.
3. It checks the partition table, then **backs up** into a new folder `B1051C-backup-<date-time>` in the current
   folder (or where `--backup` says). This takes a while; the progress line (below) shows how far it is.
4. It checks the backup and builds the images. If anything is unexpected it **stops here, before writing
   anything**, and says why.
5. It shows a summary. **Write down the slot letter.** Type `FLASH` and press Enter to start; anything else
   cancels.
6. It writes and reads back each partition, erases user data, and restarts the tablet.
7. After that restart the tablet often stays dark, or shows the charging picture, instead of starting Android.
   Then hold the power button for **3 to 5 seconds** and let go (if the charging picture appears, do it once more).
   Do not hold it for 10 seconds or longer, and do not press it while the boot logo or animation is shown.
8. The installer ends with a short list of what to do next: allow USB debugging if the tablet asks, restart once
   after setup, and turn USB debugging off at the end (sections 9 and 10).

**The progress line.** During every long read or write (the backup, writing the system, each readback, and
`restore-stock`) the installer shows one line that keeps updating: the partition, the percentage, how much is
done out of the total, the speed and the time left. It looks roughly like this:

```
system_b: 12% (590 MB / 5.06 GB), 7.5 MB/s, ~9 min left
```

The backup of about 12.5 GB and the system write of about 5 GB each take several minutes; as long as the line
moves, all is well. When the output goes to a file instead of a terminal, a line is printed every 10%. The full
mtkclient output is in the log file the installer names.

Options (after `install`):

| Option | What it does |
|---|---|
| `--dry-run` | Prints every step and command; runs neither mtkclient nor adb. With `--backup` it also checks that backup and builds the images, offline. |
| `--backup FOLDER` | Where to put the backup. If the folder already holds a backup made by this installer, it is reused after checking that it still matches the tablet. |
| `--variant gapps` / `nogapps` | Which system image (see step 6). |
| `--no-logo` | Keep your current boot logo. |
| `--skip-adb` | The tablet does not start Android, or adb is not available. Skips the Android-side checks (firmware, screen, battery), so make sure of those yourself. You then start the tablet into mtkclient by hand (power button, see above). |
| `--slot a` / `--slot b` | Extra safety check: stop unless this is the active slot. |
| `--mtk "COMMAND"` | How to run mtkclient, if `mtk` is not on your PATH. |
| `--work FOLDER` | Where the log and the built images go (default: a new `inkognito-work-<date-time>` folder). |

To check a backup without touching the tablet: `python3 inkognito.py prepare --backup <folder>`.

## 9. The first start

The first start takes about **5 minutes**. Do not interrupt it.

1. **Boot logo.** Android formats the freshly erased data partition.
2. **The first start installs the InKognito layer and then restarts the tablet by itself**, before any setup
   screen appears. This is expected.
3. **Second start:** the boot animation, then **Google setup** (with `system.img`) or Android's own short setup
   (with `system-nogapps.img`).
4. While you go through setup, the rest is installed and configured in the background: E-Ink Control, the e-ink
   shade theme, Lawnchair, KOReader, Magisk and the icon pack.
5. After setup you land on the **Lawnchair** home screen, and the **InKognito Setup** screen opens once.
6. **Restart the tablet once** when you are done with InKognito Setup: hold the power button until the power menu
   appears and tap **Restart**. On the alpha 6 test install, part of the display layer's permissions only
   loaded from the second start on. This release fixes that, but the fix has not yet been confirmed on a second
   clean install, so restart once to be safe.

On e-ink the screen can sit unchanged for a minute or more at a time. Give it at least 10 minutes before you
assume a problem.

**Root requests in the first minute.** Right after the first start, Magisk is still finishing its own setup. If
an app or a computer asks for root in that minute (for example `adb shell su`), the tablet can show a message
that Magisk needs an internet connection to finish installing. Close it (Cancel); nothing needs to be
downloaded. Wait until the home screen is up before you use root.

**Checking the background install.** Each finished step is written to Android's log with the tag `InKognito`;
read it without root with `adb logcat -d -s InKognito`; the result of the last run is also kept in
`adb shell getprop persist.debug.inkognito.status`, which survives restarts. The full log is `/data/adb/b1051c/provision.log`
(readable with root).

## 10. After setup

- **InKognito Setup** (opens once after setup; later in E-Ink Control, System, "InKognito Setup"): switch off what
  you don't want and tap Apply. KOReader, the Lawnchair home screen (Android's own home screen takes over) and the
  InKognito icon pack can be removed; Anti-shake can be switched on. "Keep everything" changes nothing.
- **Restart once** after InKognito Setup (section 9, step 6).
- **USB debugging** is on, and the tablet asks "Allow USB debugging?" the first time a computer connects. Only
  allow your own computer: an allowed computer can get a root shell (`adb root`, userdebug build). Turn it off in
  Developer options when you do not need it.
- **Keyboard:** HeliBoard, with swipe typing in the download with Google. For handwriting, install Gboard from
  the Play Store. Details in [KNOWN-ISSUES.md](KNOWN-ISSUES.md).
- **Google says the device is not certified:** see [Troubleshooting](#google-says-the-device-is-not-certified).
- E-Ink Control is where the refresh modes, color modes, per-app profiles and the front light live. Its Quick
  Settings tiles can be added from the edit button in Quick Settings.

## 11. Updating to a newer release

A newer InKognito release can be installed over an older one **without erasing your data**:

```
bash flash-inkognito.sh update             # macOS / Linux
flash-inkognito.bat update                 # Windows
```

The update reads only the partitions it needs (no 12.5 GB backup), writes the new system and, only if they
changed, boot, vbmeta and logo, each with a readback, and restarts. On the first start the new InKognito layer is
installed and the tablet restarts once by itself. Apps and settings stay; parts you removed in InKognito Setup
stay removed. Keep the backup from your first install: it is still your way back to stock.

`update` refuses to run on a tablet that does not have InKognito, and `install` refuses to run on one that does.

**From alpha 6 to beta 1** your apps, settings and books stay. What changes by itself:

- Phone and Messaging are switched off (the tablet has no SIM slot). Settings › Apps brings them back.
- The keyboard becomes HeliBoard, but only if your default keyboard was still Android's own (AOSP) keyboard. If
  you chose another keyboard, it stays.
- The device name and the Bluetooth name become "InKognito B1051C Pro", but only if they still were
  "TrebleDroid vanilla". A name you chose stays.
- Settings you changed (screen timeout, color contrast and the like) are not touched.

## 12. Going back to stock

The backup made by `install` is your way back:

```
bash flash-inkognito.sh restore-stock --backup B1051C-backup-<date-time>
flash-inkognito.bat restore-stock --backup B1051C-backup-<date-time>
```

It checks the backup against its `SHA256SUMS`, checks that `super`'s partition table on the tablet still matches
the backup, and writes back `boot_<slot>`, `vbmeta_<slot>`, `logo` and `system_<slot>` (each read back and
compared). Then it erases `metadata` and the start of `userdata`, so stock Android starts fresh, like after a
factory reset. Type `RESTORE` to start.

- If the backup folder also holds a `userdata.bin` (you made a full mtkclient backup yourself), `metadata` and
  `userdata` are restored from it instead of erased.
- If `super`'s partition table changed since the backup (it should not), add `--full-super` to write the whole
  12 GiB `super` back.
- A backup you made earlier with `mtk rl` works too, as long as it has the partition files the installer needs
  (`boot_a.bin` … `super.bin`); without a `SHA256SUMS` its contents cannot be checked.

## 13. Troubleshooting

### mtkclient never catches the tablet

- Windows: install the drivers from mtkclient's README (UsbDk) and try again.
- Linux: install mtkclient's udev rules and replug the cable.
- All systems: another USB port and cable, no hub, close other phone tools. With the cable connected, hold power
  for about 12 seconds so that the tablet restarts; mtkclient catches it while it starts.

### The installer stopped

Read the message at the end. It always says whether anything was written and what to do next.

- **Before the summary**, nothing was written. If the tablet is still connected to mtkclient, restart it with
  `mtk reset` or by holding power for about 12 seconds.
- **During writing**, do not start the tablet. Either fix the problem and run the same command again, or go
  back with `restore-stock --backup <folder>` (the installer prints the exact command). Ask in the thread if you
  are unsure, with the log file it names.

### "a system update is still pending"

The tablet is in the middle of a Bigme system update (Virtual A/B). Start stock Android, let the update finish,
restart once, then run the installer again; it makes a new backup.

### "the tablet's boot image is not the stock one"

The installer only installs over the stock 4.9.0 boot image, because it builds the InKognito boot from it. If you
rooted the tablet with Magisk, restore the stock boot image first (Magisk: Uninstall, Restore images), or flash
Bigme's 4.9.0 firmware again.

### The tablet stays off, or shows the charging picture, after the install

After the installer's final restart (also after `update` and `restore-stock`) the tablet often does not start
Android by itself. Hold the power button for **3 to 5 seconds** and let go. If the charging picture appears
instead of the boot logo, do it once more. Do not hold the button for 10 seconds or longer, and do not press it
while the boot logo or animation is shown.

### The tablet switches itself off about 5 seconds into starting, with nothing on screen

This is the bootloader's dm-verity warning. The bootloader draws it, but the e-ink panel does not show it, and if
no button is pressed it powers the tablet off after about 5 seconds. Workaround: hold power for 3 to 5 seconds to
switch the tablet on, release, and when the screen flashes, **press power briefly about once per second for about
10 seconds**. Then let Android start.

### Stuck at the logo, or restarting over and over

- The first start restarts once by itself. That is normal (section 9).
- Give it at least 10 minutes. On e-ink, nothing may change on screen for a long time.
- USB debugging is on by default, so you can check whether Android is running: `adb devices`, then
  `adb shell getprop sys.boot_completed` (prints `1` when Android has started), and
  `adb logcat -d -s InKognito` for the steps of the background install.
- If it keeps restarting, go back with `restore-stock` (section 12) and report it in the thread.

### Google setup gets stuck

Tap Allow on the tablet's "Allow USB debugging?" prompt (it also shows over the setup screens), then:

```
adb shell settings put secure user_setup_complete 1
adb shell settings put global device_provisioned 1
adb shell pm disable-user --user 0 com.google.android.setupwizard
```

The last line stops Google setup from taking the home screen again. To undo it later:
`adb shell pm enable com.google.android.setupwizard`.

### Google says the device is not certified

InKognito is based on a GSI and is not Play Protect certified. Register the tablet's GSF Android ID at
<https://www.google.com/android/uncertified>, wait a while, and try again. One way to read the ID: `adb root`,
`adb shell`, then
`sqlite3 /data/data/com.google.android.gsf/databases/gservices.db "select value from main where name='android_id';"`.
Keep this number private; do not post it in the thread.

## 14. Asking for help

Post in the XDA thread with:

- the installer's output (copy it from the terminal), or the `inkognito.log` file it names;
- the slot letter it printed;
- what the tablet showed and what you tried.

Do **not** post your serial number, GSF Android ID, Wi-Fi network names or account names. The installer's log
holds none of these, but other tools you run might. Never post your backup folder: it holds your tablet's own
partitions.
