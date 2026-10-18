# LK hidden dm-verity warning — mechanism and what it invalidates (Claude, 2026-10-17 evening)

Builds on Codex's finding (`codex-recovery-2026-10-17/RECOVERY.md`): LK draws a dm-verity corruption warning that
is invisible on the e-ink panel, waits 5 s for the power key, then powers off. This note adds (1) why the warning is
persistent, (2) the only clear path in LK, and (3) evidence that it explains *every* loop recorded after Phase A,
including both DSU attempts. Everything here is offline analysis of `BIGME_B1051C_system_dump/lk_b.bin` (Thumb, file
offset 0x200 = 0x48200000) and saved expdb dumps; nothing was written to the device.

## 1. Mechanism (verified by disassembly)

| Address | Function | Behaviour |
|---|---|---|
| `0x48225aa0` | `get_dm_verity_status(int *out)` | If hw register `0x1001a088` bit 0 is set (kernel signalled a dm-verity corruption reboot): `seccfg_set_dm_verity_state(1)`, clear the bit, `*out=1`. Otherwise `*out = seccfg_get_dm_verity_state()` (read error ⇒ 1). **The state is sticky in seccfg.** |
| `0x48262984` | warning routine, arg = AVB-resolved hashtree mode (`slot_data+0x118`) | `get_dm_verity_status`; if `check_ota_result()==1` → `clear_dm_verity_status(); clear_ota_result(); status=0`. Shows the warning if `status==1` **or** mode `== 2` (`AVB_HASHTREE_ERROR_MODE_EIO`). Polls power key 50×100 ms; key ⇒ return (boot continues); timeout ⇒ `mt_power_off` (`0x4821f29c`). |
| `0x48227704` | `check_ota_result()` | `partition_read("misc", offset 0x1000, 4 bytes)`; non-zero ⇒ "[LK] ota-pass", returns 1; zero ⇒ "[LK] ota-fail", returns 0. |
| `0x48227774` | `clear_ota_result()` | writes 4 zero bytes at `misc+0x1000`. |
| `0x48225b24` | `clear_dm_verity_status()` | clears `avb.managed_verity_mode` in the AVB persistent store (kept in **seccfg**, header at `seccfg+0x200`) and `seccfg_set_dm_verity_state(0)`. Its only caller is the OTA-pass branch above. |

libavb runs with `MANAGED_RESTART_AND_EIO` (`androidboot.veritymode.managed=yes`). Once the seccfg state is 1, LK passes
"restart caused by corruption" to libavb, which stores the current vbmeta digest and resolves `EIO` — so **changing
vbmeta_b (e.g. the HASHTREE_DISABLED flag) does not clear the warning**; the seccfg state alone keeps it on.

Live state (from Codex's 19:35 captures): `misc+0x1000 = 0`, expdb `check_ota_result = 0`, veritymode `eio`.
misc bootctrl is healthy (suffix `_b`, B prio 15 / tries 7 / successful, A prio 14 / not successful, `verity_corrupted=0`
on both — the bootctrl bit is **not** what LK uses). The 520 bytes at `misc+0xc5c` are leaked LK stack memory written
during the Phase-A fastbootd session (LK code/stack addresses); in the reserved area, harmless.

**Live confirmation (mtkclient read, 23:16 2026-10-17, `research/phase-c/20261017-231507-mtk-check/`):** seccfg
differs from May only at `+0x10` (0 → **1**, LK's dm-verity state; `lock_state` at `+0xc` still **3**), the LK-
recomputed hash `+0x1c..+0x3b`, and a new AVB persistent entry at `+0x240`: key `avb.managed_verity_mode`, value
`e9211d4a…` = the current vbmeta digest (libavb writes the current digest on every boot while the state is 1).

> **Correction, 23:20 (supersedes the "never reached the kernel" reading below).** This LK does **not** store every
> session in expdb: the owner-confirmed successful boot at ≈16:41 UTC (Android up 3.5 h, kernel certainly ran) is
> **absent** from the expdb read at 23:16 — the newest stored session is 16:38:59, a warning power-off. So "0 kernel
> handoffs" in a snapshot does not show that no kernel ran. What stays proven: every *stored* session ended in the
> warning power-off. Sessions that passed the warning (power key pressed) and booted a kernel would be invisible;
> the DSU-1 loop has 31–57 s gaps (owner pressing power, 12 sessions with `powerkey:1`) and DSU-2 a 436 s gap where
> such runs could hide. **DSU result: confounded / inconclusive** — not evidence of failure, not of compatibility.

## 2. Every *stored* loop session after Phase A is this warning (verified from expdb)

| expdb snapshot (local time) | context | `veritymode` | sessions ending `mt_power_off new` | kernel handoffs (`booting linux` / `jump to linux`) |
|---|---|---|---|---|
| `BIGME_B1051C_system_dump/expdb.bin` (14:58) | before Phase A | `enforcing` ×6 | 2 of 4 | **8** |
| `research/expdb_after_fix.bin` (15:49) | Phase-A "display latch" loop | `eio` ×11 | 11 | **0** |
| `research/_expdb_strings.txt` (15:53) | same | `eio` ×11 | 11 | 0 |
| `research/expdb_gsiloop.bin` (16:31) | **DSU attempt 1 loop** | `eio` ×11 | 13 | **0** |
| `research/expdb_after_md.bin` (16:45) | after metadata fix | `eio` ×12 | 12 | 0 |
| `research/dsu2/expdb.bin` (17:20) | **DSU attempt 2 loop** | `eio` ×11 | 12 | **0** |
| `research/dead1..4/expdb.bin` (17:34–18:00) | later loops | `eio` ×11–12 | 11–12 | 0 |
| `research/normalboot/expdb.bin` (18:07) | | `eio` ×11 | 11 | 0 |
| `codex-recovery-2026-10-17/expdb*.bin` (19:35, 19:39) | Codex session | `eio` ×11 | 11 | 0 |

Each looping session: LK on slot B → `[avb] cmdline … veritymode=eio` at ~4.3 s → `check_ota_result = 0` → **`mt_power_off
new` ~5.04 s later** → with USB power attached the PMIC restarts (`kernel_power_off_charging_detection`), period ≈16 s
(device clock 12:45:43, :59, 12:46:15, :31 …; Codex measured 15.6 s preloader re-enumeration). ~~The pre-Phase-A
dump proves handoff lines are logged, so their absence is meaningful~~ — **retracted** (see correction above: a known
kernel boot was not stored). Stored-session gaps (s): Phase-A loop `16,16,16,15,16,52,15,16,16,16`; DSU-1
`16,31,15,16,21,38,57,42,15,16,53`; DSU-2 `16,16,16,15,32,15,436,16,15,16,16`. 16 s = pure warning cycle.

Caveat: expdb keeps only the last ~11–12 sessions (≈3 min of looping). The first boot after each event may be outside
the window. Because the seccfg state was already set before the DSU attempts (eio appears from 15:49 on), LK would have
shown the warning on that first DSU boot as well unless the power key happened to be pressed.

## 3. Consequences

* **Phase-A "LK e-ink display latch / PMIC latch" — refuted for every stored session**: they end in LK's deliberate
  power-off, not a hang or watchdog. It was this warning + power-off + USB restart.
  "Cold power-off fixed it" is best explained by the power key being held through LK's 5-s window during the
  20–30 s hold (hypothesis consistent with `pwrkey_dbg_status powerkey:1` lines and `boot_reason(0)` in the session
  that then booted).
* **DSU attempts 1 and 2 — confounded, not negative** (see correction above). The warning caused most loop cycles;
  whether the GSI kernel ever ran in the unstored gaps, and how it ended, is unknown. GSI compatibility is untested.
* Origin of the sticky state: most likely the Phase-A slot-A boot (slot-A vbmeta vs slot-B data in super → kernel
  dm-verity corruption reboot). Plausible, not proven (that session is not in any saved expdb).
* **Every future boot shows the warning** (stock, after the vbmeta flag, the GSI, Magisk reboots) until LK clears the
  seccfg state. Without the power-press routine LK powers off — this must never be read as a GSI/boot failure. To
  classify a failed boot: a stored expdb session with `mt_power_off new` ~5 s after `check_ota_result` = warning not
  acknowledged; a boot that left LK leaves **no** expdb session (so timing gaps / USB pattern / ADB are the evidence).
  Open test: whether the kernel-reaching boot shows up in expdb later (e.g. after the next LK run).

## 4. Options to remove it (none applied; each needs its own approval)

1. **Keep the press routine** (default). Zero writes. Cost: every boot needs the routine.
2. **Let LK clear it via its own post-OTA path:** write a non-zero 32-bit word at `misc+0x1000` (one 512 KiB misc
   image = current misc with 4 bytes changed; readback; rollback = current misc `4dc68cb2…`). On the next boot LK runs
   `clear_dm_verity_status()` — **LK then rewrites seccfg itself** (dm-verity state + AVB persistent store) and zeroes
   `misc+0x1000`. That boot still shows the warning once (mode already resolved to EIO); later boots should not.
   Supporting evidence: LK already wrote seccfg once on this unit (state 0→1) and `[AVB20] lock_state = 0x3` is
   logged in every later session, i.e. its seccfg write path kept the unlock intact. Risk: a seccfg write by LK that
   went wrong could affect lock state (severe); stock OTA uses exactly this path. Prerequisite: read the live seccfg
   (read-only, `flash-gsi.sh mtk-check`) and diff it against the May backup to see the bytes LK changed.
   Prepared, not proposed: `work/phase-c/misc.ota-pass-flag.OPTIONAL.bin` (sha `18c5ac13…`) = canonical misc
   `4dc68cb2…` with only `misc+0x1000` = `01 00 00 00`; passes `phasec_check.py misc`.
3. Writing seccfg ourselves — **forbidden** (lock state; never write seccfg).

Not decoded: the gate `0x4822754c` before the warning call (which boot modes it applies to), and where the kernel
sets `0x1001a088` bit 0.
