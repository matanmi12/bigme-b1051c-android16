# Phase A incident (2026-10-17): bootloop after fastbootd session

> **Second correction, 21:30 2026-10-17 (Claude, offline LK disassembly + all saved expdb dumps):** after the slot-A
> boot, LK holds a sticky dm-verity "corrupt" state in **seccfg**; every later boot shows the hidden warning and
> powers off after 5 s. Every *stored* expdb session from 15:49 on — the "display latch" loop **and both DSU loops** —
> ends in `mt_power_off new`. So the "LK display/PMIC latch" and "warm-reset needs cold power-off" models below are
> refuted for every stored session. This LK does not store kernel-reaching boots (verified 23:16), so whether the DSU
> GSI ran in the loop gaps is unknown: DSU result confounded / inconclusive, not negative. Details and the table:
> `research/hidden-verity-warning-2026-10-17.md`. The slot-A flip by fastbootd (section 1) remains a real, separate fact.

> **Later correction, evening 2026-10-17:** Codex identified LK's hidden dm-verity warning and intentional five-second power-off path. The owner reached Android initial setup by briefly pressing power repeatedly during boot. `start save pllk log` also occurs during shutdown and does not prove a kernel jump. Hardware-latch explanations below are historical hypotheses, not established facts. See `research/codex-recovery-2026-10-17/RECOVERY.md` for evidence and the verified working procedure. The initial Android 16 DSU failure itself is still unexplained.

Sequence: adb reboot bootloader -> fastboot getvar all (OK, current-slot b) -> fastboot reboot fastboot ->
fastbootd reported current-slot:a and the slot-0 (stale) super metadata -> fastboot reboot -> device bootloops at the logo.
No partition was written by us.

misc before (May/Oct backup): BCB "bootonce-bootloader"; bootctrl a: prio14 tries0 succ0 | b: prio15 tries1 succ1 | suffix _a
misc after fastbootd (research/misc_live_2026-10-17b.bin): BCB empty; bootctrl a: prio15 tries7 succ1 | b: prio15 tries7 succ0 | suffix _a
=> the recovery/fastbootd environment booted on slot A rewrote the bootctrl, making A active+successful. A cannot boot
(stale super metadata slot 0 + old vbmeta_system_a hashtree) and, being "successful", is never demoted -> endless loop.

Fix candidate: research/misc_fix_slot_b_2026-10-17.bin = live misc with ONLY the 32-byte bootctrl patched:
a: prio14 tries0 succ0 | b: prio15 tries7 succ1 | suffix _b | crc recomputed. BCB stays empty (normal boot).

Lesson: on this device `fastboot reboot fastboot` boots recovery/fastbootd on slot A and the bootctrl gets rewritten.
Never flash logical partitions from fastbootd here unless fastbootd reports current-slot:b AND the expected partition sizes.

## Resolution (2026-10-17)
Root cause was TWO independent problems, both now fixed:
1. **Slot flip (software, persistent):** `fastboot reboot fastboot` booted fastbootd on **slot A** and rewrote the
   misc bootctrl to make A active+successful. A is unbootable (stale slot-0 super metadata). Fixed by writing a
   corrected misc via mtkclient (slot B active+successful, suffix _b, BCB normal) — `misc_fix_slot_b_2026-10-17.bin`.
2. **E Ink display latch (hardware, transient):** even after the misc fix the device still bootlooped. expdb showed
   LK selecting slot B correctly (`slot_info priority=14,15 retry=0,7`), loading tee_b, then **hanging in the LK
   e-ink display init** (`dsi0 is busy`, repeated `gpio set lcm rst pin` / `icn6211_write_bytes` / `mt6360_i2c`),
   watchdog-reset at ~9.7s, forever. Kernel never started (`Linux version` = 0 hits, `exp_type 0x0` = no panic).
   The icn6211/SC1452 bridge + tps65185/SYV776A e-ink PMICs latch and are NOT cleared by a warm/watchdog reset.
   **Fix: full COLD power-off** — USB unplugged, hold power 20-30s, wait ~60s, cold boot. Device then booted normally.

Both fixes were required: the cold boot does not reset the persistent bootctrl, and the misc fix does not clear the
e-ink latch.

## Post-recovery state (verified, read-only)
slot _b, boot_completed=1, Magisk 30.7 root OK, all logical partitions mounted with dm-verity active, eink driver
v4.90 alive. One benign change vs pre-Phase-A: `ro.boot.veritymode` is now `eio` (was `enforcing`); verity is still
enforced (verity dm devices mapped, verity_corrupted=0). `veritymode.managed=yes`, so LK manages this.

## Lessons for future phases
- **Do NOT use `fastboot reboot fastboot` casually on this device.** It flips to slot A and rewrites bootctrl.
  Phase B (DSU) does not need fastbootd at all (runs from Android). For Phase C, immediately after any fastbootd
  work, re-assert misc -> slot B (mtk w misc misc_fix_slot_b...bin) before rebooting to Android.
- **A warm-reset bootloop here can be an LK e-ink display latch, NOT a brick.** Always try a COLD power-off
  (power 20-30s, USB out, wait 60s) before concluding anything is wrong with the flash.
- Keep `misc_fix_slot_b_2026-10-17.bin` as the canonical "force slot B, normal boot" image.

## CORRECTION (2026-10-17, after the DSU incident)
The earlier claim "LK hangs in the e-ink display path and the kernel never starts" was WRONG.
- expdb only stores preloader+LK logs. Every looping LK session ends with `start save pllk log`, which LK does right
  before jumping to the kernel. `dsi0 is busy` / lcm-reset lines are normal logo-animation chatter (identical in all sessions).
- The kernel log lives in RAM (pstore) and is erased by the cold power-off, so "no `Linux version` in expdb" proves nothing.
- Every looping session shows `kedump: last is not full pmic reset!` and `boot_reason(1)` (watchdog).
Corrected model: one boot fails (slot A in incident 1, the DSU GSI in incident 2) -> HW watchdog -> warm reset ->
some hardware stays in a bad state across warm resets -> even stock hangs in the kernel phase -> watchdog -> loop.
Only a full PMIC reset (cold power-off: USB out, hold power 30s, wait 60-90s) breaks it. Proven 3 times.

## DSU incident (2026-10-17)
DSU of TrebleDroid A16 bvS (installed via DynamicSystemInstallationService; the direct gsi_tool path is blocked by
SELinux: gsid cannot read shell_data_file). Single-boot -> GSI failed early (adb never came up) -> warm-reset loop.
DSU retried forever because install_status stayed "0" (the boot-attempt counter was never committed to /metadata:
the metadata ext4 had `needs_recovery`). Fix: offline edit of a metadata dump (install_status=disabled, exactly what
`gsi_tool disable` writes; /vold keys proven byte-identical; SELinux label copied) + mtkclient write (approved) +
cold power-off. Device recovered; DSU then wiped with `gsi_tool wipe`. User data intact.

## DSU root-cause hypothesis REFUTED (2026-10-17)
Hypothesis "TrebleDroid GSI has no AVB footer, ramdisk has no /avb keys -> first-stage DSU mount fails" is wrong:
the GSI already carries a valid AVB hashtree footer (partition "system", SHA256_RSA2048, AOSP test key sha1 cdbb7717…,
the same key that signs the device's own vbmeta). The `invalid AVB footer` log line came from the first gsi_tool install,
which SELinux prevented from reading the image. The ramdisk does lack /avb keys, but on an unlocked device a key mismatch
is permitted. True cause of the DSU GSI failure: UNKNOWN (kernel log lost; failure happened before adbd started).

## DSU attempt 2 (2026-10-17, stock boot WITHOUT Magisk)
boot_b <- stock boot_a.bin (same kernel/DTB/cmdline as the Magisk boot, minus Magisk) via mtkclient (approved; first try
crashed on a stale mtkclient `.state` from a session that ended without reset — nothing written; retry OK).
DSU installed via the service (notification "Dynamic system is ready"), single-boot enabled from shell (no root needed).
Result: identical failure — early boot loop, DSU infinite retry. **Magisk is ruled out as the cause.**
Recovered with the same metadata fix (install_status=disabled, /vold identical) + cold power-off; DSU wiped.
Device now: stock slot B, NO root (boot_b = stock boot_a.bin image). Root restore = write boot_magisk_live_2026-10-17.bin.
Conclusion: the DSU route is closed on this device. Cause unknown (kernel logs lost; failure before adbd).
mtkclient gotcha: always move `work/ref/mtkclient/.state` aside before a new session.
