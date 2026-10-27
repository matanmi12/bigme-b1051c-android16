# Installing InKognito alpha 2 on the Bigme B1051C Pro

This guide takes you from a stock B1051C Pro to InKognito. Plan for about an hour the first time, most of it
for the backup. If you have flashed an Android device with fastboot before, you will recognize every step.

**Before you start, know this:**

- **Everything on the tablet's internal storage is erased**: apps, books, notes, settings. Copy what you want
  to keep to your computer or to the microSD card first. The microSD card is not touched by the install.
- This is an **alpha**. At release time the full install and first boot had not yet been run on a tablet.
  Make the backup in step 5. Without it you may not be able to go back.
- Only for the **B1051C Pro on firmware B1051C_R_4.9.0**. The installer checks this and stops otherwise.

**Disclaimer.** InKognito is provided "as is", without warranty of any kind. You install it at your own risk.
Unlocking the bootloader and flashing custom software may void your warranty, and a mistake can leave the
tablet unable to start until you restore a backup. Nobody involved in InKognito is responsible for damage or
lost data. InKognito is an independent community project and is not made, supported or endorsed by Bigme.

## Contents

1. [Check your tablet](#1-check-your-tablet)
2. [Install platform-tools on your computer](#2-install-platform-tools-on-your-computer)
3. [Unlock the bootloader](#3-unlock-the-bootloader)
4. [Turn on USB debugging](#4-turn-on-usb-debugging)
5. [Make a full backup with mtkclient](#5-make-a-full-backup-with-mtkclient-strongly-recommended)
6. [Charge the tablet](#6-charge-the-tablet)
7. [Download and unzip](#7-download-and-unzip)
8. [Run the installer](#8-run-the-installer)
9. [The first start](#9-the-first-start)
10. [After setup](#10-after-setup)
11. [Manual install (advanced)](#11-manual-install-advanced)
12. [Troubleshooting](#12-troubleshooting)
13. [Going back](#13-going-back)
14. [Asking for help](#14-asking-for-help)

---

## 1. Check your tablet

You need a **Bigme B1051C Pro** on stock firmware **B1051C_R_4.9.0**.

- Look at the firmware version in the tablet's settings (the About / system information screen), or, once
  adb works (step 4), run:

  ```
  adb shell getprop ro.build.versionName
  ```

  It must print `B1051C_R_4.9.0`.
- On an older firmware, update the tablet with Bigme's own system update to 4.9.0 first.
- If you are already running another custom system on top of firmware 4.9.0, that is fine too. The installer
  then checks `ro.vendor.build.version.incremental`, which must be `mp1V15125`.

## 2. Install platform-tools on your computer

platform-tools is Google's official package with `adb` and `fastboot`. Always use a recent version; the
installer needs fastboot 31 or newer and stops with a message if yours is older.

Official download: <https://developer.android.com/tools/releases/platform-tools>

**Windows**

1. Download "SDK Platform-Tools for Windows" and unzip it, for example to `C:\platform-tools`.
2. Either add that folder to your `PATH`, or simply unzip the InKognito release **into the same folder**, so
   that `adb.exe`, `fastboot.exe` and `flash-inkognito.bat` sit side by side.
3. Install the **Google USB Driver**: <https://developer.android.com/studio/run/win-usb>. You need it for the
   bootloader and fastbootd steps (see [Troubleshooting](#waiting-for-device-or-the-tablet-is-not-found-in-bootloader-mode)).
4. Open a Command Prompt and check: `adb version` and `fastboot --version`.

**macOS**

1. Either download "SDK Platform-Tools for Mac" and unzip it, or install it with Homebrew:
   `brew install --cask android-platform-tools`.
2. If you used the zip, either add the folder to your `PATH` or copy the release into it.
3. Open Terminal and check: `adb version` and `fastboot --version`.

**Linux**

1. Download "SDK Platform-Tools for Linux" from Google and unzip it. Distribution packages are often too old.
2. Add the folder to your `PATH` (or copy the release into it).
3. Install udev rules so that your user may talk to the tablet. Many distributions have a package called
   `android-udev-rules` (or `android-sdk-platform-tools-common`). Replug the tablet afterwards.
4. Check: `adb version` and `fastboot --version`.

## 3. Unlock the bootloader

InKognito needs an **unlocked bootloader**. The installer does not unlock it; it checks that it is unlocked and
stops if not.

Follow this guide: <https://vbh.ai/unlocking-the-bootloader-and-rooting-the-hibreak-pro/>

It is written for the Bigme HiBreak Pro. According to the BigmeB1051CProPatcher project by dbenjaminb, the
steps are the same for the B1051C Pro. Unlocking usually erases the tablet, so do it before you set things
up again.

After unlocking, the bootloader shows an "orange state" notice for about 5 seconds at every start. The e-ink
screen may not display it at all. That is normal.

## 4. Turn on USB debugging

On the tablet, in stock Android:

1. Open Settings, go to the About screen, and tap **Build number** seven times until it says you are a
   developer.
2. Open the new **Developer options** and turn on **USB debugging**.
3. Connect the tablet to your computer with a USB cable, directly (no hub if you can avoid it).
4. On your computer, run `adb devices`. On the tablet, accept **"Allow USB debugging?"** and tick
   **Always allow from this computer**.
5. `adb devices` must now list one device with the word `device` next to it.

## 5. Make a full backup with mtkclient (strongly recommended)

mtkclient (<https://github.com/bkerler/mtkclient>) reads the tablet's partitions directly through the
MediaTek boot ROM. A backup made with it is the only reliable way back if the install goes wrong. If you
unlocked the bootloader with mtkclient, you already have it set up.

1. Install mtkclient by following its README, including the drivers it needs on Windows.
2. Make sure you have enough free disk space. The partitions together are large, and `userdata` alone is
   about 113 GB.
3. Make the backup **after** unlocking (step 3) and **right before** installing, so that it matches the
   tablet's current state.
4. Start the backup command, then restart the tablet with the USB cable connected (for example with
   `adb reboot`, or by holding power for about 10 to 12 seconds; if it is off, press power). mtkclient
   catches the tablet while it starts up.

   ```
   python mtk.py rl B1051C-backup
   ```

   This reads every partition into the folder `B1051C-backup`. If you do not need the data on the tablet
   any more, you can leave out the very large userdata partition by adding `--skip userdata`.
   (Newer mtkclient versions install a command called `mtk`; then use `mtk rl B1051C-backup`.)
5. Keep this folder somewhere safe until you are sure you want to stay on InKognito.

## 6. Charge the tablet

The battery must be at **50% or more**. The installer reads the level and stops if it is lower.

## 7. Download and unzip

Download `InKognito-B1051CPro-alpha2-20261027.zip` and unzip it. Keep all files together in one folder.
The installer checks every image against `SHA256SUMS` before it does anything, so a damaged download is
caught automatically.

If you want to check the files yourself:

- macOS / Linux: `shasum -a 256 -c SHA256SUMS` (or `sha256sum -c SHA256SUMS`)
- Windows: `certutil -hashfile system.img SHA256`, and compare with the line in `SHA256SUMS`

## 8. Run the installer

Open a terminal (macOS / Linux) or a Command Prompt (Windows) **in the release folder**.

**First, do a dry run.** It checks the files and prints every command the installer would run, without
touching the tablet. adb and fastboot are not run at all:

```
bash flash-inkognito.sh --dry-run          # macOS / Linux
flash-inkognito.bat --dry-run              # Windows
```

**Then run it for real**, with the tablet started normally into Android and connected:

```
bash flash-inkognito.sh                    # macOS / Linux
flash-inkognito.bat                        # Windows (you can also double-click it)
```

What the installer does, in this order. It stops with a clear message at the first problem and never
continues after an error.

1. **Checks your computer:** adb and fastboot are installed and fastboot is recent enough.
2. **Checks the files:** every image matches `SHA256SUMS`.
3. **Checks the tablet over adb:** exactly one device is connected, firmware `B1051C_R_4.9.0` (or vendor
   build `mp1V15125`), screen size `1860x2480`, battery at least 50%, and which slot is active (`a` or `b`).
4. **Restarts the tablet into bootloader mode** (`adb reboot bootloader`). The e-ink screen may not show
   much; watch your computer, not the tablet.
5. **Checks the bootloader:** product `k6877v1_64_k419`, bootloader unlocked, an e-ink `waveform` partition,
   the expected partition sizes, and the same active slot as Android reported.
6. **Shows a summary** of exactly what it will write. Write down the slot letter. Type `FLASH` (capital
   letters) and press Enter to start. Anything else cancels, and nothing is written.
7. **Writes in bootloader mode:** `vbmeta_<slot>`, `boot_<slot>`, and the InKognito boot `logo`.
8. **Restarts into fastbootd** (`fastboot reboot fastboot`), the mode needed to write the system partition,
   and waits for it.
9. **Checks fastbootd:** it prints the slot fastbootd reports. That can be the other slot on this tablet;
   the install is built for that (it writes `system_<slot>` by name and sets `<slot>` active at the end).
   It also checks that fastbootd sees a real `vendor_<slot>` partition and **stops before writing system**
   if not (see [Troubleshooting](#fastbootd-reports-a-different-slot)). If a system update is still
   pending, it cancels it (`fastboot snapshot-update cancel`).
10. **Writes `system_<slot>`.** This takes a few minutes.
11. **Erases user data** (`fastboot -w`). If this tablet's fastbootd skips that, the installer erases
    `userdata` and `metadata` directly instead.
12. **Makes `<slot>` the active slot** (`fastboot set_active <slot>`) and **restarts** the tablet.

Options:

| Option | What it does |
|---|---|
| `--dry-run` | Prints every step and command. Runs no adb or fastboot command at all. |
| `--skip-adb` | For a tablet that is already in bootloader mode. Skips the Android-side checks (firmware, screen size, battery), so make sure of those yourself. |
| `--slot a` / `--slot b` | Extra safety check: stop unless that slot is the active one. Useful with `--skip-adb` after an interrupted install. It never changes which slot is written. |
| `--no-logo` | Keep your current boot logo; do not write `logo.img`. |
| `--strict-slot` | Stop before writing system whenever fastbootd reports a different slot than the bootloader. |
| `--help` | Shows all options. |

## 9. The first start

The first start takes about **5 minutes**. Do not interrupt it.

1. **Boot logo.** Android prepares the freshly erased data partition. (The "orange state" notice of an
   unlocked bootloader may not be visible on e-ink.)
2. **The first start installs the InKognito layer and then restarts the tablet by itself**, before any
   setup screen appears. This is expected.
3. **Second start:** the boot animation plays, then **Google setup** appears: Wi-Fi, an optional Google
   account, and restore. Signing in can be skipped and done later.
4. While you go through setup, the rest is installed and configured in the background: E-Ink Control, the
   e-ink shade theme, Lawnchair, KOReader, Magisk and the icon pack.
5. After setup you land on the **Lawnchair** home screen.

On e-ink the screen can sit unchanged for a minute or more at a time. That alone does not mean anything is
wrong. Give it at least 10 minutes before you assume a problem.

For the curious: the background install writes a log to `/data/adb/b1051c/provision.log` (readable with root).

## 10. After setup

- **USB debugging:** it is on, and the tablet asks "Allow USB debugging?" the first time a computer connects.
  Only allow your own computer: an allowed computer can get a root shell (`adb root`, userdebug build). Turn it
  off in Settings, Developer options, when you do not need it. See [KNOWN-ISSUES.md](KNOWN-ISSUES.md).
  (Leave it on until you are happy with the install if you want to use the Troubleshooting commands below.)
- **Google sign-in or Play Store complains that the device is not certified:** see
  [Troubleshooting](#google-says-the-device-is-not-certified).
- Install the apps you want from the Play Store. No other Google or Amazon apps are bundled.
- E-Ink Control is where the refresh modes, color modes, per-app profiles and the front light live. Its
  Quick Settings tiles can be added from the edit button in Quick Settings.

## 11. Manual install (advanced)

This is exactly what the installer does. Run the commands from the release folder. Replace `<slot>` with the
active slot letter, `a` or `b`, and stop at the first error.

```
adb shell getprop ro.boot.slot_suffix      # _a or _b: your <slot> is a or b
adb reboot bootloader

fastboot getvar product                    # must be k6877v1_64_k419
fastboot getvar unlocked                   # must be yes
fastboot getvar current-slot               # must be <slot>

fastboot flash vbmeta_<slot> vbmeta.img
fastboot flash boot_<slot> boot.img
fastboot flash logo logo.img               # optional: the InKognito boot logo

fastboot reboot fastboot
fastboot getvar is-userspace               # must be yes (you are in fastbootd)
fastboot getvar current-slot               # may differ from <slot>; see Troubleshooting
fastboot getvar partition-size:vendor_<slot>   # if this is 0x0: STOP, see Troubleshooting
fastboot getvar snapshot-update-status     # if not "none":  fastboot snapshot-update cancel

fastboot flash system_<slot> system.img    # always with the explicit _<slot> suffix
fastboot -w                                # if the output has no "Erasing 'userdata'" line, run:
                                           #   fastboot erase userdata
                                           #   fastboot erase metadata
fastboot set_active <slot>
fastboot reboot
```

Never flash or erase any other partition.

## 12. Troubleshooting

### "No tablet found over adb" or "has not allowed this computer yet"

- Check that USB debugging is on (step 4) and that the cable carries data (some cables only charge).
- Unlock the tablet's screen and accept the "Allow USB debugging?" prompt. If it never appears, turn USB
  debugging off and on again and replug the cable.
- If the tablet is already in bootloader mode, run the installer with `--skip-adb`.

### "This tablet is not on firmware B1051C_R_4.9.0"

Update the stock firmware to 4.9.0 with Bigme's own update first. InKognito's boot image and vbmeta come from
that firmware and are not meant for any other.

### "waiting for device", or the tablet is not found in bootloader mode

- **Windows:** the bootloader and fastbootd need a USB driver. Install the Google USB Driver (step 2). If
  Device Manager still shows an unknown "Android" device, right-click it, choose Update driver, Browse my
  computer, Let me pick, then Android Device, and select **Android Bootloader Interface**. Do this again for fastbootd if it shows
  up as a new unknown device.
- **Linux:** install the udev rules (step 2) and replug the cable. If `fastboot devices` says "no
  permissions", the rules are missing.
- **All systems:** try another USB port and cable, avoid hubs, and close other phone tools.

### "fastbootd never shows up"

After `fastboot reboot fastboot` the tablet restarts into fastbootd, which is a different USB device than
the bootloader. On Windows it usually needs the driver step above once more.

When `fastboot devices` lists the tablet again, follow what the installer printed. Usually:

```
fastboot set_active <slot>
fastboot reboot bootloader
```

and run the installer again with `--skip-adb --slot <slot>`.

### fastbootd reports a different slot

On the developer's tablet, `fastboot reboot fastboot` once came up on the other slot (A, while B was active)
and changed the active slot, which left the tablet in a boot loop. The install is built for this: it writes
`system_<slot>` with the slot letter spelled out, and sets `<slot>` active again as the very last step. So a
different slot letter in fastbootd alone does not stop the installer. (If you prefer it to stop in that case,
use `--strict-slot`.)

What does stop it, **on purpose and before writing system**, is when fastbootd's view of the partition table
does not match your slot, shown as `vendor_<slot>` with size `0x0`. That is what the developer's tablet showed
in the situation above. fastbootd writes the partition table it sees back to the tablet when it writes
system, so continuing could damage the vendor partition.

If the installer stopped for either reason, boot and vbmeta are written, but system and vendor are untouched.
Run:

```
fastboot set_active <slot>
fastboot reboot
```

with the slot letter from the summary. If Android does not start, restore `boot_<slot>` and `vbmeta_<slot>`
from your backup (section 13). Please report it in the XDA thread with the two slot letters.

### Writing system fails ("not enough space" or similar)

Do not delete or resize partitions to make room. Leave the tablet in fastbootd, run
`fastboot set_active <slot>`, then `fastboot reboot bootloader`, and ask in the XDA thread with the
installer's output. You can also restore your backup (section 13).

### The installer stopped in the middle

Read the message at the end of the output. It says what was already written and what to do next, usually a
few commands to run or "run the installer again with `--skip-adb --slot <slot>`". Do not start Android in
between unless the message says so. If in doubt, ask in the thread before you do anything else.

### The tablet switches itself off about 5 seconds into starting, with nothing on screen

This is the bootloader's dm-verity warning. The bootloader draws it, but the e-ink panel does not show it, and
if no button is pressed it powers the tablet off after about 5 seconds. Once triggered (by a verification
failure), the state can stick across restarts.

Workaround: hold power for 3 to 5 seconds to switch the tablet on, release, and when the screen flashes,
**press power briefly about once per second for about 10 seconds**. Then stop and let Android start.

### Stuck at the logo, or restarting over and over

- The first start restarts once by itself. That is normal (section 9).
- Give it at least 10 minutes. On e-ink, nothing may change on screen for a long time.
- USB debugging is on by default on this build, so you can check from your computer whether Android is
  running: `adb devices`, then `adb shell getprop sys.boot_completed` (prints `1` when Android has started).
  `adb devices` shows `unauthorized` until you tap Allow on the tablet's "Allow USB debugging?" prompt.
- If it keeps restarting, hold power for about 30 seconds to force it off, unplug USB, wait a minute, and
  switch it on again. If that does not help, restore your backup (section 13) and report it in the thread.

### Google setup gets stuck

USB debugging is on by default on this build, so you can skip the rest of setup from your computer (tap
Allow on the tablet's "Allow USB debugging?" prompt first; it also shows over the setup screens):

```
adb shell settings put secure user_setup_complete 1
adb shell settings put global device_provisioned 1
adb shell pm disable-user --user 0 com.google.android.setupwizard
```

The last line stops Google setup from taking the home screen again. To undo it later:
`adb shell pm enable com.google.android.setupwizard`.

The InKognito first-start log can help when you report a problem: `adb root`, then
`adb shell cat /data/adb/b1051c/provision.log`. It contains no names, networks or account data.

### Google says the device is not certified

InKognito is based on a GSI and is not Play Protect certified. If Google sign-in or the Play Store
complains, register the tablet's GSF Android ID at <https://www.google.com/android/uncertified>, wait a while,
and try again. You can skip sign-in during setup and do it later.

One way to read the GSF Android ID on this build is from a root adb shell. Run `adb root`, then `adb shell`,
then:

```
sqlite3 /data/data/com.google.android.gsf/databases/gservices.db "select value from main where name='android_id';"
```

If `sqlite3` is not available, use a device ID app. Keep this number private; do not post it in the thread.

## 13. Going back

Going back means **restoring your mtkclient backup** (step 5).

The install changes these partitions: `vbmeta_<slot>`, `boot_<slot>`, `logo`, `super` (which holds
`system_<slot>`), `userdata` and `metadata` (erased), and `misc` (where the active slot is stored). Restore
**all** of them **before** you start the tablet again. Restoring only some of them, for example the stock
vbmeta together with the InKognito system, makes the start fail verification and can trigger the hidden
warning described above.

Restore the partitions from the backup folder with mtkclient (one line):

```
python mtk.py w boot_a,boot_b,vbmeta_a,vbmeta_b,logo,super,misc,metadata B1051C-backup/boot_a.bin,B1051C-backup/boot_b.bin,B1051C-backup/vbmeta_a.bin,B1051C-backup/vbmeta_b.bin,B1051C-backup/logo.bin,B1051C-backup/super.bin,B1051C-backup/misc.bin,B1051C-backup/metadata.bin
```

Use the file names that are actually in your backup folder. Start the command, then restart the tablet with
USB connected, as in step 5.

- If your backup **includes userdata**, restore it too (`w userdata B1051C-backup/userdata.bin`) to get your
  old data back.
- If it **does not**, erase the data partitions instead so that stock Android starts fresh: restart into
  bootloader mode and run `fastboot erase userdata` and `fastboot erase metadata`, then `fastboot reboot`.

Do not restore partitions that InKognito never touched (preloader, lk, seccfg and so on) unless you know
why you need to.

## 14. Asking for help

Post in the XDA thread with:

- the full output of the installer (copy it from the terminal),
- the slot letters it printed,
- what the tablet showed and what you tried.

Do **not** post your serial number, GSF Android ID, Wi-Fi network names or account names. The installer does
not print any of these, but other tools you run might.
