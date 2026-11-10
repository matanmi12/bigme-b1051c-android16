# Testing the InKognito flash scripts (developer notes, not for the release zip)

Scope: `flash-inkognito.sh` (macOS/Linux) and `flash-inkognito.bat` (Windows), alpha 1.
Date: 2026-10-21. Host: macOS, `/bin/bash` 3.2.57 (the oldest bash the script has to run on).

**Rule for these tests:** the real `adb` and `fastboot` were never run. A tablet was connected to the test
machine, so the scripts were only exercised with `--dry-run` and with mock `adb`/`fastboot` executables first
in `PATH`. The live path (a real flash) has not been run by anyone yet.

## What was run

| Check | Tool | Result |
|---|---|---|
| Syntax of the bash script | `bash -n flash-inkognito.sh` | OK |
| shellcheck | not installed on the test machine | **not run** |
| Dry-run scenarios with mocks first in PATH | `dev-tests/run-dryrun-tests.sh` | 11/11 pass, **0 adb/fastboot calls** |
| Pure parsing helpers (script sourced, `main` not run) | `dev-tests/run-helper-tests.sh` | 31/31 pass, 0 adb/fastboot calls |
| Static checks of the .bat (CRLF, labels, blocks, `%` escapes, ASCII) | ad-hoc Python | OK, see below |
| Personal-data scan of every file with the `stage_public.py` patterns | ad-hoc Python | 0 hits |
| Windows .bat executed | no Windows machine | **not run** |

## Mock adb and fastboot

`dev-tests/mockbin/adb` and `dev-tests/mockbin/fastboot` are small bash scripts. They never talk to a device.
Every call is appended to `$MOCK_LOG`, so a test can prove whether the flash script called them. They answer
like a B1051C Pro on firmware 4.9.0 (slot b, LK `getvar` values in the LK format without `0x`, fastbootd
values, a `fastboot -w` that skips the wipe as this tablet's fastbootd is expected to), so they can also be
used for a full simulated run later.

Each test run sets

```
PATH="<dev-tests>/mockbin:/usr/bin:/bin:/usr/sbin:/sbin"
```

which leaves the real platform-tools out of `PATH`, and then aborts unless `command -v` resolves to the mocks:

```
command -v adb      -> <dev-tests>/mockbin/adb
command -v fastboot -> <dev-tests>/mockbin/fastboot
```

(The first run used identical mocks in the session scratchpad; the `dev-tests` copies were then run again
with the same results.)

## Dry-run scenarios

The runner builds a throwaway release folder with four 4 KiB random "images", a matching `SHA256SUMS`, and a
copy of the script, then runs it from a different working directory (to prove it switches to its own folder):

| Scenario | Arguments | Expected | Result |
|---|---|---|---|
| help | `--help` | exit 0, lists `--strict-slot` | pass |
| default dry run | `--dry-run` | exit 0, prints every adb/fastboot command in order | pass |
| bootloader-only | `--dry-run --skip-adb --slot b --no-logo` | exit 0, concrete `_b` commands, no logo | pass |
| `--slot=a` form | `--dry-run --slot=a` | exit 0 | pass |
| strict slot | `--dry-run --strict-slot --slot b` | exit 0 | pass |
| unknown option | `--dry-run --frobnicate` | exit 1, "Nothing was sent to your tablet" | pass |
| bad slot | `--dry-run --slot c` | exit 1 | pass |
| logo.img missing | `--dry-run` | exit 1 | pass |
| logo.img missing, `--no-logo` | `--dry-run --no-logo` | exit 0 | pass |
| boot.img altered by 1 byte | `--dry-run` | exit 1, prints `MISMATCH` | pass |
| SHA256SUMS missing | `--dry-run` | exit 1 | pass |

After all scenarios the mock call log was **empty**: `--dry-run` runs neither adb nor fastboot, not even
`fastboot --version`.

The dry-run command order printed (slot b) is:

```
adb devices / getprop ro.build.versionName / getprop ro.vendor.build.version.incremental /
  wm size / dumpsys battery / getprop ro.boot.slot_suffix
adb reboot bootloader
fastboot devices; getvar is-userspace, product, unlocked, partition-size:waveform,
  partition-size:super, current-slot, partition-size:boot_b
(confirmation: type FLASH)
fastboot flash vbmeta_b vbmeta.img
fastboot flash boot_b boot.img
fastboot flash logo logo.img
fastboot reboot fastboot
fastboot devices + getvar is-userspace (wait for yes)
fastboot getvar current-slot, partition-size:vendor_b, snapshot-update-status
  (snapshot-update cancel only if not none)
fastboot flash system_b system.img
fastboot -w   (fallback: erase userdata, erase metadata)
fastboot set_active b
fastboot reboot
```

## Helper checks

`run-helper-tests.sh` sources the script (the `main` guard keeps it from running) and feeds the parsing
helpers with output shaped like the 2026-10-17 captures from this tablet
(`research/fastboot-getvar-bootloader-2026-10-17.txt`, `research/fastboot-getvar-fastbootd-2026-10-17.txt`):

- `partition-size:super: 300000000` (LK, no `0x`) and `0x300000000` (fastbootd) both normalize to `300000000`;
  `0X2800000`, `0x02800000`, `0x0` normalize as expected.
- `current-slot: b`, `_a`, `B`; unknown slot letters are rejected.
- `(bootloader) NAME: value` lines, `NAME:value` without a space, CRLF output, a missing variable
  (`FAILED (remote: 'unknown variable')`), and no false match on a longer variable name.
- `adb devices` with daemon start-up lines, unauthorized, two devices, none.
- `dumpsys battery` level (also with CR), `wm size` with an override line.
- `SHA256SUMS` with CRLF, `*file` (binary mode) and `./file` entries, and a missing entry.

## Static checks of flash-inkognito.bat

No Windows machine was available, so the batch file was only checked statically:

- all 696 lines end in CRLF, no bare LF, ASCII only;
- every `goto` / `call` target exists (57 labels, 57 targets);
- parenthesized blocks balance, and no `echo` inside a block contains an unescaped parenthesis;
- every literal `%` is doubled (`%%`); `<`, `>` and `|` in `echo` text are escaped;
- no `!` in any text (delayed expansion is on);
- the device command order is the same as in the bash script (compared line by line).

## Not tested (needs a decision or hardware)

- **The live path of both scripts**: no real adb or fastboot output was parsed, nothing was flashed.
- **flash-inkognito.bat at all** on Windows: `certutil` parsing, `for /f` handling of adb output, `set /p`,
  drivers.
- LK and fastbootd behavior on a real tablet: whether `fastboot reboot fastboot` lands on the same slot,
  whether `fastboot -w` wipes (the fastbootd capture has no `partition-type` lines, so the client is expected
  to skip the wipe and the scripts fall back to `erase userdata` / `erase metadata`), whether `erase` there
  leaves the partitions blank enough for Android to format them, and the `partition-size:vendor_<slot>` guard.
- The full simulated (non-dry) run against the mocks. It is possible with these mocks
  (`cd` to a test release folder, mocks first in `PATH`, then `bash flash-inkognito.sh </dev/null` exits at
  the FLASH prompt; feed `FLASH` on stdin to go further), but it was not run because the test rules for this
  session allowed only `bash -n`, shellcheck and `--dry-run` runs.

## How to re-run

```
bash dev-tests/run-dryrun-tests.sh       # TEST_WORK=<dir> to choose the scratch folder
bash dev-tests/run-helper-tests.sh
```

Do not ship `dev-tests/` or this file in the release zip.
