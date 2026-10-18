# Phase C runbook — permanent TrebleDroid A16 on slot B (revised 2026-10-17, evening)

Supersedes plan §5 "Phase C" and the first `flash-gsi.sh`. Evidence behind every change:
`research/hidden-verity-warning-2026-10-17.md`, `research/codex-recovery-2026-10-17/RECOVERY.md`,
`research/PHASE-A-INCIDENT.md`. Scripts: `tools/scripts/flash-gsi.sh`, `tools/scripts/restore-stock.sh`.

**Rules:** each WRITE below is a separate approval (the script also asks for its own phrase). Reboots are announced.
Never: fastboot / fastbootd (`fastboot reboot fastboot` flipped this unit to slot A), `set_active`, relock, writes to
seccfg / preloader / lk / tee / otp / sec1 / nvram / nvdata / proinfo / frp / waveform / vcom, `mtk e|es|ess`
(their success check is inverted in our mtkclient: they stop after 2 MiB), DSU retries, a full dump flash.

## 0. Every boot: the hidden dm-verity warning

LK has a persistent dm-verity "corrupt" state (in seccfg). On **every** boot it draws an invisible warning, waits
5 s for the power key, then powers off. A vbmeta change does not clear it.

**Routine** (owner-verified 2026-10-17): if the device is off, hold power 3–5 s and release; when the display flashes
(or right after `flash-gsi.sh reset` / an `adb reboot`), press power **briefly about once per second for ~10 s**, then
stop and wait. Holding power ≥10 s forces a restart — avoid that.

**Classifying a "failed" boot** — never conclude from the screen alone:
| Observation | Meaning | Action |
|---|---|---|
| `MT65xx Preloader` re-appears on USB every ~16 s (`ioreg -p IOUSB` / `system_profiler SPUSBDataType`) | LK warning → power-off → USB power restart | redo the routine |
| device stays off (USB unplugged) | same, without USB power | redo the routine |
| period much longer than 16 s, or `fastboot devices` shows LK fastboot | the kernel/Android ran and failed (init fatal errors reboot to the bootloader) | note the timing; read expdb (`flash-gsi.sh mtk-check`) — stored sessions are only the warning power-offs, a boot that left LK leaves **no** entry; then decide rollback |
| `adb devices` lists the device, USB product name "TrebleDroid…" | GSI userspace is up | `flash-gsi.sh adb-check gsi` |

## 1. Gates (all must be true before WRITE 2)

| Gate | Status 2026-10-17 21:30 |
|---|---|
| G1 offline sepolicy compile of GSI + our VNDK-31 vendor succeeds | **passed** — exit 0 with `-c 30` (init's fixed value), 3 secilc builds; stock control == vendor `precompiled_sepolicy` (0-line diff). Residual: relies on the A14 first-stage init honouring the GSI's `skip_mount.cfg` — if stock system_ext_b were mounted, the compile fails (`xrz_proc_file`) → init fatal → reboot to LK fastboot (§0 table). `research/sepolicy-offline-compile-2026-10-17.md` |
| G2 inputs hashed (`work/phase-c/SHA256SUMS.txt`) | done |
| G3 rollback images present and hash-verified (stock vbmeta_b, boot_b, system_b slice, misc, super.bin) | done |
| G4 read-only DA session in the current state: GPT unchanged, misc = slot B, super LP slot-1 metadata = backup | **passed 23:16** (`research/phase-c/20261017-231507-mtk-check/`) |
| G5 stock baseline: `adb-check stock` (slot _b, boot_completed, `vbmeta.digest` = predicted `e9211d4a…`) | **passed 23:14** (digest matched the prediction) |
| G6 after WRITE 1: stock boots, `veritymode=disabled`, digest `5a084f7a…`, `partition.system.verified` empty | needs WRITE 1 |
| G7 owner confirms: tablet data is disposable (fresh setup only), off-machine copy of `BIGME_B1051C_system_dump/` + `settings-backup-2026-10-17/` exists | owner |

**Transition rule (replaces plan Phase B go/no-go):** the DSU criterion ("boot_completed in DSU") was **not met** and
is not marked as passed. The two DSU runs are confounded by the hidden warning (inconclusive, not negative). By the owner's
decision (2026-10-17) Phase C proceeds on G1–G7 instead, accepting that GSI compatibility is untested.

## 2. Step 0 — stock baseline (read-only; owner action needed)

1. Finish Android setup quickly (skip accounts/Wi-Fi; the data will be wiped). Settings → About tablet → tap *Build
   number* 7× → Developer options → **USB debugging** on → connect USB → accept the RSA prompt ("Always allow").
2. `tools/scripts/flash-gsi.sh adb-check stock` — saves props, `/proc/version` (expect the **Mar-18-2026** kernel),
   mounts, logcat, SurfaceFlinger, and asserts slot `_b`, `boot_completed=1`, `veritymode=eio`, digest `e9211d4a…`.
   No root on stock (boot_b is the official image); dmesg may be denied — fine.
   A digest mismatch stops the plan: our model of the live vbmeta chain would be wrong.

## 3. Step 1 — read-only DA session

`tools/scripts/flash-gsi.sh mtk-check` — catch the preloader (start the command, then `adb reboot`). Asserts GPT
offsets, misc (slot B active/successful, BCB empty), super LP slot-1 metadata hash `bdb54c65…` with system_b extent 0
at `super+0x23d00000`; hashes vbmeta_b / vbmeta_system_b / vbmeta_vendor_b / boot_b; reads **seccfg** (evidence only,
diffed against May) and expdb. Leaves the DA connected so WRITE 1 can follow without another catch.

## 4. WRITE 1 — vbmeta_b HASHTREE_DISABLED (`flash-gsi.sh vbmeta`)

* **Operation:** `mtk w vbmeta_b work/phase-c/vbmeta_b.hashtree-disabled.bin` + readback.
* **Purpose:** let first-stage init mount a non-stock system (fs_mgr skips all hashtrees when the top-level flag is set).
* **Change:** AVB header flags 0 → 1, exactly one byte (offset `0x7b`). Same as `fastboot --disable-verity`, without
  `--disable-verification` (minimal; LK keeps verifying/loading boot and dtbo as now). Fallback if LK misbehaves:
  flags=3 (both), separate approval.
* **Partition:** vbmeta_b only. **Risk:** LK refuses → no boot; signature becomes invalid (allowed while unlocked).
* **Recovery:** `restore-stock.sh vbmeta` (stock `578cdaaa…`). **Verify:** readback `12e57602…`.
* Then announce + `flash-gsi.sh reset` → routine (§0) → `flash-gsi.sh adb-check vbmeta`: stock boots,
  `veritymode=disabled`, digest `5a084f7a…`, `partition.*.verified` empty. If stock asks for a factory reset,
  that is /data, not vbmeta — stop and report.

## 5. WRITE 2 + WRITE 3 — GSI into system_b, data reset (one DA session)

`flash-gsi.sh mtk-check` (re-catch) → `flash-gsi.sh system` → `flash-gsi.sh wipe-data` → `flash-gsi.sh reset`.

**WRITE 2 `system`:** checks vbmeta_b is the flags=1 copy, misc, super LP metadata; reads the 2.2 GB target range and
requires it to equal the stock system_b slice (`d2e0fe99…`) — this proves the address mapping before anything is
written. Then `mtk wo 0x72d00000 0x83cf5000 system-td-arm64-vanilla.img` and a full readback (`a0704a79…`).
LP metadata, vendor_b, product_b, system_ext_b (skipped by the GSI's `skip_mount.cfg`), boot_b and slot A stay
untouched. Recovery: `restore-stock.sh system` (writes the slice back, verified).

**WRITE 3 `wipe-data`:** zero `metadata` (39,780,352 B) and the first 64 MiB of `userdata`; readback all-zero.
First boot re-formats both (same mechanism that produced today's successful stock first boot). Destroys all tablet
data — confirm G7. Pre-images kept privately in the run folder.

**First GSI boot** (announce; routine §0). Expect several minutes (format + first boot). Display: most likely blank or
tiled greyscale (no shim yet) — irrelevant for the verdict. Verdict comes from ADB only:
`flash-gsi.sh adb-check gsi` → `boot_completed=1`, `ro.build.type=userdebug`, TrebleDroid fingerprint,
`veritymode=disabled`; `/proc/uptime` increasing on two reads (no hidden reboots).
If SurfaceFlinger wedges (HiBreak trap): `adb root; adb shell setprop persist.sys.phh.enable_sf_gl_backpressure false;
setprop persist.sys.phh.enable_sf_hwc_backpressure false; setprop ctl.restart surfaceflinger` — runtime/`/data` only,
still announced first.
No ADB after 10 min → §0 table → expdb → rollback `restore-stock.sh all` + `wipe-data` + `reset`.

## 6. Root and the compat module (item 7 of the brief)

The **current boot_b is the official stock image (`dd26e524…`, Mar-18-2026 kernel), unrooted.** The old
`boot_magisk_live_2026-10-17.bin` was built from boot_a (Oct-2025 kernel) — `phasec_check.py boot` rejects it against
the current boot_b (kernel differs). Installing the Magisk APK alone gives no root.

* **Diagnostics need no Magisk:** the GSI is userdebug (`ro.debuggable=1`, `ro.adb.secure=0`) → `adb root`; phh su
  is also present.
* **Delivering the shim into /vendor needs one of:**
  * **A (default): Magisk v30.7** (`work/magisk/Magisk-v30.7.apk`, sha `e0d32d21…` = GitHub digest). After the GSI
    baseline: `adb install` the APK; `adb push BIGME_B1051C_system_dump/boot_b.bin /sdcard/Download/boot_b.img`;
    Magisk → Install → **Select and Patch a File** (never "Direct install"; scrcpy if the panel is unusable — not yet
    installed: `brew install scrcpy`). Pull `magisk_patched-*.img` → `flash-gsi.sh boot <img>` = **WRITE 4**
    (asserts kernel+DTB byte-identical to stock boot_b, full 40 MiB, readback). Then
    `magisk --install-module b1051c-eink-compat-v0.1.0.zip` (only `/data`) → reboot → shim checks. Possible
    conflict with phh su: if Magisk su misbehaves, remove phh su via TrebleApp before debugging further.
    Recovery: `restore-stock.sh boot`; module off: `touch /data/adb/modules/b1051c-eink-compat/disable`.
  * **B (fallback, no Magisk): patched vendor_b** — the HiBreak route. Shim is 40 KB; stock vendor_b.img has 484 free
    blocks and 14 free inodes, so it fits via offline `debugfs -w` (label `u:object_r:vendor_file:s0`) plus the
    `vendor.xrz.` property-context patch. Written into vendor_b's extent (`super+0x183d00000`), recovery = stock
    `work/super/vendor_b.img` (`116a3af8…`). Prepared only if A fails.

## 7. Post-install verification (evidence, not logos)

| # | Item | Evidence |
|---|---|---|
| 1 | ADB, boot_completed, no reboots | `adb-check gsi`, two `/proc/uptime` reads, USB product name |
| 2 | Display | `dumpsys SurfaceFlinger` (composition, HWC loaded), `logcat -s hwcshim`, photo of the panel |
| 3 | Touch | `getevent -lt` on the cyttsp5 node (event5 on stock — re-identify with `getevent -pl`) |
| 4 | Pen | `getevent -lt` on the Wacom node: `BTN_TOOL_PEN`, `ABS_PRESSURE`, hover |
| 5 | Frontlight | root read of `/sys/devices/platform/11d20000.i2c1/i2c-1/1-0036/lm3630a_{cold,warm}_light`; any write = runtime, announced, value restored |
| 6 | Sleep/wake | power key off/on, `dumpsys power` wakefulness, `dmesg` suspend/resume lines, panel content after wake |
| 7 | Wi-Fi / BT / audio | later (vendor HALs unchanged) |

Logs go to `research/phase-c/<timestamp>-*/`. A step is "done" only with its evidence file.

## 8. Rollback matrix (all via mtkclient; works without a booting Android)

| Changed | Restore | Source / sha |
|---|---|---|
| vbmeta_b | `restore-stock.sh vbmeta` | `BIGME_B1051C_system_dump/vbmeta_b.bin` `578cdaaa…` |
| system_b extent 0 | `restore-stock.sh system` | `work/phase-c/system_b.stock-head-0x83cf5000.img` `d2e0fe99…` |
| boot_b | `restore-stock.sh boot` | `BIGME_B1051C_system_dump/boot_b.bin` `dd26e524…` |
| misc (if anything rewrites bootctrl) | `restore-stock.sh misc` | `research/misc_fix_slot_b_2026-10-17.bin` `4dc68cb2…` |
| /data after any system swap | `restore-stock.sh wipe-data` | zeros |
| LP metadata damaged (check fails) | `restore-stock.sh super-full` | `BIGME_B1051C_system_dump/super.bin` (12 GiB) |

Back to stock = `restore-stock.sh all` → `wipe-data` → `reset` → routine → `adb-check stock` (expect `eio`, `e9211d4a…`).
