# Testing the InKognito installer (developer notes, not for the release zip)

Scope: `inkognito.py` and its front ends `flash-inkognito.sh` / `.bat`. Written for alpha 3 (2026-10-27), last
brought up to date for beta 1 (2026-11-12). Host: macOS,
Python 3.12. Rule: the real adb and mtkclient are never run by the tests. `dev-tests/test_inkognito.py` puts
`dev-tests/mockbin` first in a reduced PATH and checks that `adb` and `mtk` resolve to the mocks; the mocks log
every call. (The alpha 2 fastboot scripts and their tests are in `work/rom/release-src-old/`.)

## Suites

| Suite | What it does | Inputs (private, read-only) |
|---|---|---|
| `unit` | the progress meter (parser on mtkclient's real progress lines from the 2026-11-11 logs, terminal and piped rendering, `Mtk._run` with a stand-in mtkclient: the log keeps every line); boot/vbmeta/logo builders against the tested boot and logo images and the stock dump; boot classification (stock, InKognito, Magisk-only, changed kernel); `super` extents (WRITE 2 offset, multi-extent, too big, the stale slot-0 table from Phase A); misc slot and Virtual A/B status; the write guards (forbidden partitions, partitions and ranges not on the write list) | `BIGME_B1051C_system_dump`, `work/rom/out/images` (the tested boot.img / logo.img; `TEST_TESTED_IMAGES` overrides), `work/sdcard`, a device backup (`TEST_DEVICE_BACKUP`) |
| `cli` | `--help`, dry runs of install/update/restore-stock/backup, bad arguments, a damaged kit file; asserts **no mock was called** by any dry run, that a dry run without a backup prints no concrete `super` offset, and that a dry run with `--backup` computes `wo 0x72d00000` | as above + `TEST_REL` (a release folder) |
| `sim` | the whole install → update → install-again (refused) → restore-stock → readback error, against `dev-tests/mockbin/mtk`: a mock mtkclient whose "tablet" is a sparse disk image laid out by the real GPT (`fixtures/printgpt.txt`, UUIDs removed) and filled from the stock dump | as above; about 25 GB temp space |

Run: `TEST_REL=<release folder> TEST_DEVICE_BACKUP=<backups/device-…> python3 -I dev-tests/test_inkognito.py unit cli sim`.
`build-rom.py release` runs `unit cli` on the assembled folder and refuses to zip on a failure.

## Results 2026-10-27

- `unit` + `cli`: all pass, 0 mock calls in the dry runs.
- `sim` on the alpha 3 test folder: install, update, restore-stock and the readback-error stop all behave; after
  install `boot_b` classifies as InKognito, `vbmeta_b`/`logo` equal the tested images, `system_b` (super+0x23d00000)
  holds `system.img`, `metadata` and the userdata head are zero, slot A / dtbo / misc and `super`'s LP metadata are
  untouched; after restore-stock every restored region equals the stock dump.
- Built from the stock dump, the boot image passes `tools/scripts/phasec_check.py boot` (kernel identical, DTB =
  stock + SD cap); its ramdisk equals the tested alpha 2 ramdisk entry for entry; only the gzip bytes differ.
- `redact()` removes every ME_ID / SOC_ID / CID / Serial value from the developer's earlier real mtkclient logs.

## Results 2026-11-11 / 2026-11-12 (alpha 7, beta 1)

- `unit` + `cli` + `sim` on the assembled release: 115 passed, 0 failed (alpha 7, both builds).
- Real hardware (owner's tablet): `restore-stock` and a full `install` of alpha 6 (2026-11-11, run by the owner from
  the downloaded zip), `update` to alpha 5, alpha 6 and alpha 7. The progress meter ran against the real mtkclient in
  piped mode (a line every 10%); the self-updating terminal line has only been checked by the unit tests.
- After `mtk reset` the tablet stays off until the power button is held for 3 to 5 seconds (every flash so far).

## Not run

- Windows (`flash-inkognito.bat`, mtkclient on Windows): no Windows machine.
- Python 3.8–3.11: only 3.12 was used (the code avoids newer syntax).
- The image without Google apps on a tablet.
