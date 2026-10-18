# Project status — Bigme B1051C Pro → Android 16 (updated 2026-10-18, 23:30)

Plan: `~/.claude/plans/pasted-content-id-25ff-bigme-mellow-sutton.md` (approved; **Revision 2** at its top).
Phase C procedure: `docs/PHASE-C-RUNBOOK.md`. Key evidence: `research/hidden-verity-warning-2026-10-17.md`,
`research/codex-recovery-2026-10-17/RECOVERY.md`. Day-by-day log: `research/phase-c/settings-changes.log`.
Feature plan: `docs/ROADMAP.md` (waves 0–8 → soak → Phase E ROM); owner test log: `docs/FEATURE-TESTS.md`.

## Overnight 2026-10-19 00:00–00:30 (owner-approved; supersedes the versions listed in the next section)
- **Module `b1051c-eink-compat` 0.1.11** (zip `d714838d…`, shim `7597ce3b…`), verified after a reboot (00:20):
  (1) `setLayerSurfaceDamage` with 0 rects (HWC2 "whole buffer") is passed as one full rect: the Bigme HWC read 0
  rects as "no change" and skipped every frame of CPU-drawn apps (KOReader; = vbbot issue #6). (2) Settled full
  refresh: `vendor.debug.hwc.shim.gc16_req` (and auto-N) -> one GC16 re-send of the final frame after 350 ms quiet,
  also on a static screen; replaces the 1.2 s commit_mode-4 window that flashed every frame of a transition.
- **E-Ink Control 0.10.9 build 32** (refresh/color tiles open an e-ink pick list; clearScreen = gc16_req; US
  spelling), **SystemUI plugin 0.2.6 build 17** (gap under the light sliders, no marquee in tile labels).
- **KOReader** v2026.07.1 (owner-installed) restored from the stock backup with `research/koreader/build_restore.py`:
  ZenOS + settings + history + statistics + 8 sidecars; books on the SD card `/storage/EEDD-A114/Books`, 70 renamed
  to Hebrew (`research/koreader/rename-plan.json`, undo script next to it). Profile: Regal + preset "KOReader"
  (contrast 30) + N 6. derainbowify.koplugin built from source (`work/koreader/`). Icon pack rebuilt (KOReader A).
- After a reboot adbd is not root: `getprop vendor.xrz.*` from the shell reads empty — use `su -c getprop` or
  `adb root` (build-iconpack.py needs `adb root`).
- Rollbacks: shim `/data/local/tmp/shim-e548e050.so` (+ module zip 0.1.10); KOReader `koreader.fresh-20261019`;
  layer backup `backups/layer-20261019-000113`.

## Current state — 2026-10-18 23:30 (sections below this one are history)
- **Partitions:** slot `_b`; boot_b = `57d07a2b…` (stock Mar-2026 kernel + Magisk 30.7 + microSD 50 MHz DTB cap,
  WRITE 6); vbmeta_b flags=1; system_b = TD16 GSI; misc/seccfg clean (no verity warning, cold boot OK 13:26).
  No partition write pending. Only planned ones: logo (wave 7) and the ROM flash (Phase E), each with approval.
- **Magisk modules:** `b1051c-eink-compat` **0.1.10** (zip `aec1f8de…`: shim `e548e050…` with Bigme colour
  pipeline, force_regal, auto GC16 every N; einkd; SF watchdog + 1008 guard; stock IDC; phh-su stub; framework RRO
  1.1 = squircle icon mask + Wi-Fi-only configs; Wi-Fi-only and no-fingerprint feature files), `b1051c-gapps`
  (MindTheGapps subset, FRP never armed), `b1051c-icons` 0.1.3 (private Bigme icon RROs).
- **Apps / overlays in /data:** E-Ink Control **0.10.5 build 28** (`b3ebcdbd…`; 0.10.6 build 29 built, not yet
  installed: refresh/colour tiles open a pick list instead of cycling); SystemUI plugin `org.b1051c.einkui` 0.2.4
  build 15; Lawnchair 15 Beta 3 = default home + private icon pack `org.b1051c.iconpack`; Launcher3 runtime RRO
  `org.b1051c.overlay.launcher` 1.1 (search bar hidden, nav-row height fix).
- **Fixed this evening (other sessions, see the log):** microSD (WRITE 6 + SD guard in every boot-writing script),
  taskbar long-press crash with Lawnchair as home (stale hotseat item removed; any taskbar-icon long-press still
  crashes Launcher3 — avoid it), com.android.phone ANR loop (module 0.1.9), fingerprint IVirtualHal loop (0.1.10),
  nav-row refresh button on a static home screen (app 0.10.5).
- **Waves:** 0 and 1 done; 2 implemented, waiting for the owner's panel review; next = wave 3 (colour). KOReader
  work deferred to wave 8 until the owner says otherwise.
- **Owner feedback 23:15 (to do):** refresh/colour tiles should open a dialog (done in 0.10.6, to install); the
  lower QS light slider overlaps the tiles (needs a look on the device); question about the nav-button layout
  (end-aligned tablet taskbar) and auto-hide.
- **Backups:** layer backups `backups/layer-20261018-{123754,163435}` (a new one is due before wave 3); dump and
  settings backup copied off-machine by the owner (Google Drive, 2026-10-18 evening); dump folder made read-only.

## Phase C result — 2026-10-18 00:45 (owner-verified on the device)
TrebleDroid Android 16 + Magisk 30.7 + module v0.1.3 on slot B: **full-screen colour picture** (dark), **touch smooth
and correctly oriented, stylus works, Wi-Fi works**, density 300 good, faster with animations off. Boots without the
power-press routine; the module keeps SF unwedged (watchdog) and HW overlays off (SF 1008, so the shim always has a
client target). Not yet checked: frontlight, sleep/wake, BT, audio. Next = Phase D (e-ink feature parity with stock).

## Feature roadmap — 2026-10-18 12:30
Owner answered FEATURES-NEXT (`bigme features.docx`). Waves 0–7, then ≥2 weeks soak, then Phase E (dedicated GSI ROM):
`docs/ROADMAP.md`. Test log: `docs/FEATURE-TESTS.md`. Wave-0 probes 12:33 (read-only): FRP off verified (`ro.frp.pst`
empty, no persistent_data_block); kernel has `hall_key` (event1), `bt_key`, `pt_mt`, Wacom; MTK LIGHT + PROXIMITY sensors
listed, but system_server's light activation logged PERMISSION_DENIED/BAD_VALUE (needs a test in wave 5); load avg ~16
is D-state MTK kthreads (CPU 785% idle), not a problem. Logo partition format: `research/boot-logo/LOGO-FORMAT.md`.
Wave 0 done so far (12:45): layer backup `backups/layer-20261018-123754`; app **0.9.0 build 17** (night schedule removed, Colour
restoration toggle + QS tile, keeper restarts on update and keeps the light off when started asleep); plugin **0.2.4 build 15**
(flame icon). Waiting for the owner: cold boot from full power-off, anti-alias look, hall/touch-off/pen-button getevent, Regal log.

## Phase D state — 2026-10-18 05:00 (superseded by "Current state"; details in research/phase-d/CONCLUSIONS.md §2b/§2c)
- **Installed and verified after a reboot (04:27):** Magisk modules `b1051c-eink-compat` **v0.1.6** (shim with Bigme
  colour pipeline + processImage crash guard, SF-1008 guard loop every 10 s, `einkd` check_commit pulser) and
  `b1051c-gapps` (MindTheGapps 16 subset: Play services, Services Framework, Play Store; GMS self-updated to 26.40.32).
- **Apps (in /data):** E-Ink Control **0.7.2** (refresh modes on `global_refresh_mode`, colour profiles, front light
  synced with QS, tiles Refresh mode / Clear screen / Colour / Greyscale / Front light / Anti-shake / Switch system,
  nav-row refresh button, shade theme settings; WRITE_SECURE_SETTINGS granted) and the **E-Ink SystemUI plugin**
  `org.b1051c.einkui` build 14 (platform-signed OverlayPlugin: e-ink light/dark shade, outlined tiles, cold/warm
  light sliders replacing brightness, notification cards in B/W).
- **Owner-verified:** nav refresh button, vivid. **Pending owner review:** shade theme, sliders, tiles, auto refresh
  every N (einkd), notification styling, Google sign-in (+ uncertified registration, see `work/gapps/README.md`).
- Next features + open questions: `research/phase-d/FEATURES-NEXT.md` (40 items, with recommended answers).

## Phase D progress — 2026-10-18 01:30 (superseded: the global refresh knob is `global_refresh_mode`, not `force_…`)
- **Knobs verified on the device:** frontlight `…/1-0036/lm3630a_{cold,warm}_light` raw **0–255** (wraps above 255;
  Android's brightness drives the dummy `lcd-backlight`, not the frontlight); **`vendor.xrz.force_global_refresh_mode`**
  is read live by the vendor HWC (179 → commits mode 0xb3, -1 → 0xb2); kernel `eink_debug` accepts writes to
  `saturation`, `contrast`, `brightness`, `anti_flicker` (a **level**: `set_anti_flicker_level`), `global_dither`;
  driver **v5.03_20260301**, `panel_type=0x21` (colour panel recognised — why the GSI shows colour).
- **App `org.b1051c.einkctl` "E-Ink Control" v0.3.0** (`work/app/einkctl`, Gradle-free `build.sh`, Magisk su policy
  for uid 10144): frontlight cold/warm sliders + on/off, auto-off while the screen sleeps, optional link to Android's
  brightness slider with a warmth mix; global refresh mode (Auto/Normal/Fast/Regal/HD/GL16/A2); "Clear screen" (two
  full GC16 commits, verified); anti-flicker + saturation/contrast/brightness (ranges pending stock analysis);
  **per-app refresh profiles** via an accessibility service (verified: Settings→179, home→-1); QS tiles Refresh /
  Clear / Light / Switch system; foreground keeper service re-applies everything at boot.
- **Dual system (DSU):** stock system_b + signed AVB hashtree footer (`work/dsu/stock-system-dsu.img`, `d4feb51a…`,
  ext4 region identical to stock) installed with `gsi_tool` (+16 GiB separate userdata), then **disabled**; the app's
  "Switch system" = `gsi_tool enable --single-boot` + reboot; from stock, return = reboot (one-shot) or the DSU
  notification. **Stock DSU boot not yet tested.**
- Screen-off trace: HWC `SetPowerMode(0)` → kernel `eink_suspend` only (no black frame seen in logs); why the panel
  shows black when asleep still needs the owner's eyes.

## Gates passed tonight (read-only)
- **G5 23:14** `adb-check stock`: slot `_b`, boot_completed 1, kernel `#1 SMP PREEMPT Wed Mar 18 20:50:16 CST 2026`,
  `veritymode=eio`, **vbmeta digest `e9211d4a…` = offline prediction**, no DSU, setup complete.
- **G4 23:16** `mtk-check` (preloader caught after `adb reboot`): GPT unchanged; misc slot B; super LP slot-1 = backup;
  vbmeta_b / vbmeta_system_b / vbmeta_vendor_b / boot_b = stock. seccfg vs May: `+0x10` 0→1 (dm-verity state),
  lock_state still 3, AVB entry `avb.managed_verity_mode` = `e9211d4a…`. DA left connected (tablet held in DA).

## Phase C writes + first GSI boot (2026-10-17, all owner-approved, all read back)
- **WRITE 1 23:22** vbmeta_b ← flags=1 copy (readback `12e57602…`). Stock booted (press routine): `veritymode=disabled`,
  digest `5a084f7a…` = prediction, no verity dm devices, /data survived (FBE keys not bound to the digest). **G6 passed.**
- **WRITE 2 23:33–23:39** GSI → system_b extent 0. Pre-image == stock slice `d2e0fe99…` (mapping proven); readback `a0704a79…`.
- **WRITE 3 23:41** metadata + userdata head zeroed, readback all-zero (G7: owner confirmed data disposable, backup uploaded).
- **First GSI boot 23:41:48** (press routine): USB "TrebleDroid vanilla" + ADB at 23:43:32; **`sys.boot_completed=1` at
  uptime 80 s**; slot `_b`, TD16 fingerprint, userdebug, `veritymode=disabled`, SELinux enforcing, /data re-formatted
  FBE, uptime monotonic (no reboots). Kernel = stock Mar-2026 boot_b. Evidence: `research/phase-c/20261017-234432-adb-gsi/`.
- Display: `init.svc.bootanim=running` after boot_completed + Mali fence timeouts in hwcomposer → SurfaceFlinger GPU
  wedge (HiBreak trap; fix = phh SF GL/HWC backpressure off). Composer runs (pid 1495, frames 2480×1860) but is
  denied `vendor.xrz.*` sets (expected; compat module). 4442 `ccci_mdinit` denials = modem noise.
- **Runtime fix 23:45 (owner-approved, /data props only):** `adb root` OK (`u:r:su:s0`);
  `persist.sys.phh.enable_sf_{gl,hwc}_backpressure=false`; `ctl.restart surfaceflinger` (framework restarted with it,
  no kernel reboot). Afterwards: bootanim stopped, **0 Mali fence timeouts**, Launcher3 focused. After a wake key +
  `wm dismiss-keyguard`, `screencap` shows the **correct Android 16 home screen, 1860×2480 portrait**
  (`research/phase-c/20261017-234432-adb-gsi/screencap-awake.png`). Panel (owner): **4 copies, portrait, in the right
  quarter** — the HiBreak 4:1 packing bug the shim fixes.
- **WRITE 5 23:54 (owner-approved):** misc+0x1000 ← 1 (LK OTA-pass word), built from the LIVE misc (Android 16 had set
  bootctrl B tries 7→1 via markBootSuccessful, successful=1 — valid AOSP state, LK logs `retry = 0, 7` ×77 showing it
  does not decrement a successful slot — and added misc_kcmdline/misc_control messages at 0x8080/0x80c0). Only 4 bytes
  differ; readback `7a54f6ab…`; pre-image kept. Before it, seccfg's AVB digest had become `5a084f7a…` (LK rewrites
  seccfg every boot; lock_state 3). GSI booted after the routine (23:55). Clear-result check pending (next DA session).
- **SF wedge recurs on every GSI boot** even with the persist props =false (2/2 boots); one `ctl.restart
  surfaceflinger` after boot clears it (00:00, panel back to 4 copies). Module **v0.1.1** (`7cf6eae7…`): service.sh
  watchdog (one SF restart if bootanim still running 15 s after boot_completed), real META-INF installer
  (v0.1.0 had an empty META-INF), extra composer `system_prop` read rule.
- Magisk candidate ready: `work/phase-c/boot_b.magisk-v30.7.img` (`170538a9…`, kernel+DTB identical to stock).
- **00:03 write-5 result (read-only DA):** seccfg `dmverity_state` 1→**0**, `lock_state` **3**, AVB digest zeroed,
  misc+0x1000 back to 0 (LK cleared it). **WRITE 4 00:04** boot_b ← Magisk image, readback OK (a first attempt was
  refused by mtkclient — relative path — nothing written, boot_b verified still stock; script fixed). **Boots since
  then without the power-press routine** (00:04, 00:07) → hidden warning gone.
- Magisk 30.7 runs (`/debug_ramdisk/magisk -v` = 30.7:MAGISK:R). /data/adb/magisk populated by hand from the same APK
  (no Magisk app UI) → `magisk --install-module` installed module v0.1.1; after reboot the module is mounted, xrz props
  seeded, shim loaded into composer (pid 1264). **Shim rewrites every EINK_UPDATE** (fd → RGBA client target,
  mode 0xb2, full area, ret 0).
- **Problem found:** TrebleDroid's `phh-su --daemon` (`sudaemon`, su.rc) bind-mounts `/dev/su/su` (nosuid) over every
  `magisk` binary incl. `/debug_ramdisk/magisk` → init's `magisk --service` / `--boot-complete` fail
  (`nosuid_transition` denied, exit 127) → module service.sh (SF watchdog) never runs. SF restarted manually 00:12.
  Fix candidate: module stubs `/system/bin/phh-su` (no system write). `phh-securize.sh` NOT used (rewrites system).
- **Owner 00:13: one full, correct picture, in colour** (dark, inaccurate colour), UI tiny, sluggish, touch rotated 90°.
- **UI settings 00:18 (approved):** `wm density 300` (was 213), animation scales 0, screen timeout 10 min
  (`research/phase-c/settings-changes.log`).
- Touch cause: stock `/system/usr/idc/pt_mt.idc` + `Wacom_I2C_Digitizer.idc` (`touch.orientation = ORIENTATION_270`)
  live in Bigme's system image, absent on the GSI; touch raw axes are landscape (2480×1860).
- **Module v0.1.2 (`7a8b91e1…`, installed 00:19 with `/data/adb/magisk/magisk` — `/debug_ramdisk/magisk` was phh-su):**
  stock IDC files, `ro.sf.lcd_density=300`, phh-su stub (label `phhsu_exec`; `phhsu_daemon` is permissive).
  **Reboot 00:20, no presses:** boot_completed 00:21; Magisk `--service`/`--boot-complete` exit 0; sudaemon = `sleep`;
  no phh bind mounts; watchdog logged one SF restart → bootanim stopped; touch + pen at `Rotation270`.

## Current device state before Phase C writes (historical, superseded by the section above)
- Android 14 stock reached **initial setup** (owner-confirmed); `sys.boot_completed` not yet checked over ADB;
  USB debugging off.
- Slot **B** active (misc: suffix `_b`, B prio 15/tries 7/successful, A prio 14/unsuccessful). **Slot A is not a fallback.**
- **boot_b = official stock** (`dd26e524…`, kernel built 2026-03-18), **no Magisk, no root**. boot_a not re-read (last known: Magisk).
- vbmeta_b / vbmeta_system_b / vbmeta_vendor_b = backup (re-read by Codex). super = backup and slot-B hashtrees (earlier full read).
- `/metadata` was zeroed and the userdata head partly zeroed; Android re-created both at first boot → tablet data = fresh setup.
- **Hidden LK dm-verity warning on every boot** (sticky seccfg state, `veritymode=eio`): invisible on e-ink; LK powers
  off after 5 s unless power is pressed. Routine: short presses ~1/s for ~10 s at boot. Not fixed (optional fix: runbook / research note §4).
- Writes done on 2026-10-17 (all approved): misc (several, final = `4dc68cb2…`), metadata (DSU fixes, then zeroed),
  boot_b (several times — stock boot_a image, Magisk again, … — final = official stock boot_b), userdata first 2 MiB
  (an erase meant to be 256 MiB; stopped by the mtkclient `es` bug). Never written: seccfg, lk, preloader, tee,
  vbmeta*, super, calibration partitions.

## Corrections — do not reuse these earlier conclusions
| Earlier claim | Now |
|---|---|
| Phase-A loop = LK e-ink display / PMIC latch; cold power-off cures warm-reset loops | **Refuted** for every stored session: each ends in LK's deliberate warning power-off (`mt_power_off new` ~5.04 s after the verity check), not a hang. Cold power-off "worked" most likely because power was held through LK's 5-s window. |
| `start save pllk log` = LK jumping to the kernel | Wrong — it is also on the power-off path. |
| DSU route closed: the GSI fails early (×2), Magisk ruled out | **Confounded / inconclusive.** Every stored loop session is the warning, but this LK does not store kernel-reaching boots (today's successful 19:41 boot is absent from expdb), so whether the GSI ran in the 31–436 s gaps is unknown. Neither failure nor compatibility is shown. (An earlier version of this table said "most likely never ran" — retracted 23:20.) |
| `veritymode=eio` benign | It is the sticky seccfg state that triggers the hidden warning. vbmeta changes don't clear it. |
| Magisk on both slots; Phase C can reuse the Magisk boot | boot_b is official stock (Mar-2026 kernel); the old Magisk image is from the Oct-2025 kernel and is rejected by `phasec_check.py boot`. |
| "No device partition has been written" | Historical; see writes above. |
| fastbootd + `fastboot -w` for Phase C | Banned: fastbootd booted on slot A and rewrote bootctrl. All writes via mtkclient; data reset = zero writes. |
| mtkclient erase is a wipe | `e/es/ess` invert the write result locally → stop after 2 MiB. Use `w`/`wo` with zero files. |
| `bootonce-bootloader` in misc reaches fastboot | LK does not consume it (Codex). |

## Prepared for Phase C (offline, no device writes)
- `tools/scripts/flash-gsi.sh` (rewritten: `adb-check`, `mtk-check`, `vbmeta`, `system`, `wipe-data`, `boot`, `reset`)
  and `restore-stock.sh` (`system|vbmeta|boot|misc|wipe-data|all|super-full`); assertions in `phasec_check.py`,
  LP layout in `lp_extents.py`. Each write: pre-read assertion → typed phrase → write → exact readback.
- Inputs in `work/phase-c/` (`SHA256SUMS.txt`): `vbmeta_b.hashtree-disabled.bin` (1 byte vs stock),
  stock system_b rollback slice, zero files. Magisk v30.7 in `work/magisk/` (GitHub digest verified).
- Verified offline: GSI = raw ext4 (2,211,401,728 B) fits system_b extent 0 (`super+0x23d00000`, 5.06 GB);
  GSI `skip_mount.cfg` skips /product and /system_ext; GSI sepolicy has mapping 31.0; userdebug, `ro.adb.secure=0`;
  LP slot-1 metadata of the backup `bdb54c65…`; misc VAB merge status NONE.
- Offline sepolicy compile test (GSI + our vendor): **passed** (exit 0, `-c 30`; stock control reproduces the vendor
  precompiled_sepolicy exactly) → `research/sepolicy-offline-compile-2026-10-17.md`. Caveats: needs `skip_mount.cfg`
  to be honoured (stock system_ext would break the compile); 46 vendor types have no 31.0 mapping (incl. Bigme
  `vendor_xrz_prop`, `xrz_debugfs`, `xrz_system_file`) → expect denials, relevant to the compat module's props.

## microSD (2026-10-18) — FIXED by WRITE 6
Owner's 128 GB FAT32 card: detected and read fine, but every write at SDR104/96 MHz ended in a data-CRC storm and
MediaTek's `msdc_set_bad_card_and_remove` (card then ignored until re-insert). Same kernel for every ROM → "no ROM
recognises it". **WRITE 6 21:55 (owner-approved):** boot_b ← `work/sdcard/boot_b_magisk_sd50mhz.bin` (sha
`57d07a2b…`) = the Magisk boot_b with the DTB value `mmc@11240000/max-frequency` 100 → 50 MHz (4 bytes at
boot_b+0x195957c). Pre-read = `170538a9…` as expected, readback = `57d07a2b…`
(`research/phase-c/20261018-215432-sd50-boot/`). Verified after boot: slot _b, veritymode disabled, Magisk 30.7 +
3 modules, `/proc/device-tree/mmc@11240000/max-frequency` = `02faf080`, card mounts by itself at SDR104 48 MHz,
128 MiB + 16 MiB (after a sleep/wake cycle) written and md5-verified, 0 CRC / bad-SD events, ~19 MB/s.
Rollback: `tools/scripts/flash-sd50.sh rollback`. Evidence: `research/sdcard/`.
**boot_b is now `57d07a2b…`**: a future boot image (Magisk re-patch from stock, new kernel) must carry the same
4-byte DTB patch or the card breaks again.

**SD guard (22:00–22:20, owner: "guard it so it survives switching ROMs"):** the cap lives in boot_b, so system-only
switches (another GSI, a TD update) keep it automatically. Every path that writes boot_b now enforces it:
- `phasec_check.py sd-status | sd-patch | boot` — one shared guard; `boot` refuses any image whose DTB is not
  stock + cap. Tested: official + WRITE 4 images refused, capped images pass, `sd-patch` of the WRITE 4 image
  reproduces the live `57d07a2b…` byte for byte, patching is idempotent.
- `restore-stock.sh boot|all` → stock kernel + Magisk 30.7 + cap (`57d07a2b…`); `boot-plain` → stock + cap, no
  Magisk (`work/sdcard/boot_b_stock_sd50mhz.bin`, `bc82b1b6…`); `boot-official` = untouched official image,
  emergency only, with an SD warning line.
- `flash-gsi.sh boot` auto-makes a capped copy of an uncapped Magisk image; live pre-image kept as exact rollback;
  `mtk-check` reports the cap of the live boot_b; `adb-check` asserts `/proc/device-tree/.../max-frequency = 02faf080`.
- `magisk-patch.sh` now patches the capped plain image and checks input and output (ran it: output capped,
  `work/sdcard/boot_b.magisk-v30.7.sd50-repatch.img`). Magisk's own re-patch of the live image (= app update) also
  verified to keep the cap.
- Magisk restore/uninstall: `/data/magisk_backup_566c9a01…/boot.img.gz` = plain stock + cap (root-only, 0600);
  `flash-sd50.sh magisk-backup` recreates it (needed again after any /data wipe).
- Not guardable from here: Bigme OTA / SP Flash Tool full flash (both write an uncapped boot) → `adb-check` catches it.

## Phase C next steps (historical — all done 2026-10-17/18)
1. Owner: finish setup, enable + authorize USB debugging → `flash-gsi.sh adb-check stock` (read-only).
2. `flash-gsi.sh mtk-check` (read-only DA session; needs a reboot into the preloader — announced).
3. WRITE 1 (vbmeta_b) → stock boot check → WRITE 2 (system_b) + WRITE 3 (wipe) → first GSI boot → baseline → WRITE 4 (Magisk boot) → module.
Owner items from then: off-machine copy of the dump + settings backup — **done** (Google Drive); dump folder
read-only — **done** 2026-10-18 23:30 (`chmod -R u+w` to undo); optional `brew install scrcpy`.

## History (earlier today; superseded statements are listed in the table above)
- Phase 1 recon + Phase 0 prep: device identity (plan §1); May dump re-verified; super proven consistent with slot-B
  vbmetas; 23 missing partitions + preloader + then-current Magisk boot added (`BIGME_B1051C_system_dump/`, 64 files,
  `SHA256SUMS-2026-10-17.txt`, `MANIFEST.md`); settings/app-data + KOReader backup (`settings-backup-2026-10-17/`,
  private; media/books not included); GSI staged (TrebleDroid `android_16.0.0_r1 ci-20250617`, bvS userdebug,
  raw `a0704a79…`); stock slot-B images extracted (`work/super/`); HWC shim ported (`work/shim/`); Magisk module
  `work/module/b1051c-eink-compat-v0.1.0.zip` (untested).
- Phase A: bootloader getvar OK (unlocked, slot b); `fastboot reboot fastboot` came up on slot A and rewrote bootctrl
  → loop; misc fixed to slot B via mtkclient; then the (now explained) warning loop; device booted after a long power hold.
- Two DSU attempts (Magisk boot, then stock boot_a image) → loops recovered by metadata edits; now known to be the
  LK warning loop. Later: wipes of metadata/userdata head, official boot_b restored; Codex recovery (misc restored,
  owner reached initial setup with the power-press routine).

## Open risks / unknowns
- GSI on this kernel/vendor is untested here (HiBreak Pro Color twin boots TD16 on the same 4.19 line + Android-12 vendor).
- VNDK: TD16 ships no VNDK APEX; reported harmless on the twin.
- Colour path (`panel_type=-1` on stock) needs the shim log-only capture (plan Phase D.5).
- Magisk vs phh su coexistence on TD16; Magisk overlay timing for the composer.
