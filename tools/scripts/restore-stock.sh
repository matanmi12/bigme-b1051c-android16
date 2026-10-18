#!/bin/bash
# Return slot B to the verified stock state. REVISED 2026-10-17 (evening): mtkclient only — no fastboot/fastbootd
# (fastbootd flipped this device to slot A once). Works whether or not Android boots, as long as mtkclient can
# catch the preloader. Each write asks for its own phrase, reads back and compares to the stock hash.
# Restore everything you changed BEFORE the next boot (stock vbmeta + GSI system = dm-verity failure).
# Usage: restore-stock.sh <system|vbmeta|boot|boot-plain|boot-official|misc|wipe-data|all|super-full>
#   all = system + vbmeta + boot (only those that differ), then wipe-data; then: flash-gsi.sh reset
# SD GUARD (2026-10-18, research/sdcard/): the official boot_b leaves the microSD at 100 MHz, where every write fails.
#   boot / all     -> stock kernel + Magisk 30.7 + SD cap   (work/sdcard/boot_b_magisk_sd50mhz.bin, = live WRITE 6 image)
#   boot-plain     -> stock kernel, no Magisk, + SD cap     (work/sdcard/boot_b_stock_sd50mhz.bin)
#   boot-official  -> the untouched official image          (emergency only: the SD card stops working)
# Every capped image is re-checked by phasec_check.py boot (kernel == stock, DTB == stock + cap) before the write.
source "$(dirname "$0")/common.sh"
set -o pipefail
SYS_SLICE="$WORK/phase-c/system_b.stock-head-0x83cf5000.img"; SYS_SLICE_SHA=d2e0fe994300ba5ad15e943bfa0ed372b49754e52785eedacf772062f6e33dc2
VB_STOCK="$DUMP/vbmeta_b.bin";   VB_STOCK_SHA=578cdaaa76f08157f118b306c96041f27cddc06676f2c3c9ba42aa4187e303c2
BOOT_STOCK="$DUMP/boot_b.bin";   BOOT_STOCK_SHA=dd26e52466aedf7d93546b07741673af1ecac1d1b441f251e81d6878c00405e0   # official, NO SD cap
BOOT_MAGISK_SD="$WORK/sdcard/boot_b_magisk_sd50mhz.bin"; BOOT_MAGISK_SD_SHA=57d07a2b5a3d99a1a3c3c37e544a85116e6837952f917de1524a83da5509b1e7
BOOT_PLAIN_SD="$WORK/sdcard/boot_b_stock_sd50mhz.bin";   BOOT_PLAIN_SD_SHA=bc82b1b6a4d6556d662b3c7d0fdc7bc91907c7119cb6308284ca6ba44606bea0
MISC_OK="$RES/misc_fix_slot_b_2026-10-17.bin"; MISC_OK_SHA=4dc68cb24cc30bd5c991acabeca7feebbd1e507585a8c3e2f5d1e2c459c08ee7
SUPER="$DUMP/super.bin"
SYS_ABS=0x72d00000; SYS_LEN=0x83cf5000
[ $((SYS_ABS)) -eq $((0x4f000000 + 0x23d00000)) ] && [ $((SYS_LEN)) -eq 2211401728 ] || die "offset constants inconsistent"

restore_part() { # restore_part <partition> <file> <sha> [extra summary line]
  local p="$1" f="$2" s="$3" note="${4:-Risk     : none beyond the partition itself; source is a verified image}"; need_hash "$f" "$s"
  mtk r "$p" "$RUN/$p.pre.bin" </dev/null >/dev/null; local pre; pre=$(sha "$RUN/$p.pre.bin")
  [ "$pre" = "$s" ] && { echo "$p already holds the target image — skipped"; return 0; }
  confirm_phrase "RESTORE $p" "Operation: mtkclient w $p $f (+ readback)" "Current  : sha ${pre:0:16}… (kept in $RUN)" \
    "Target   : sha ${s:0:16}…" "$note"
  mtk multi "w $p $f;r $p $RUN/$p.post.bin" </dev/null
  [ "$(sha "$RUN/$p.post.bin")" = "$s" ] && say "READBACK OK: $p == target" || die "READBACK MISMATCH on $p — DA still connected, retry this subcommand"
}
restore_boot() { # restore_boot <image> <sha> <label>   — SD guard: capped images only
  need_hash "$BOOT_STOCK" $BOOT_STOCK_SHA; check boot "$1" "$BOOT_STOCK"
  restore_part boot_b "$1" "$2" "Image    : $3 (kernel == stock, DTB == stock + microSD cap 50 MHz)"
}
restore_system() {
  need_hash "$SYS_SLICE" $SYS_SLICE_SHA
  mtk ro 0x4f000000 0x100000 "$RUN/super_head.bin" >/dev/null
  check super "$RUN/super_head.bin"   # if this fails, the LP metadata changed: use super-full instead
  mtk ro $SYS_ABS $SYS_LEN "$RUN/system_b.pre.img" >/dev/null; local pre; pre=$(sha "$RUN/system_b.pre.img"); rm -f "$RUN/system_b.pre.img"
  [ "$pre" = $SYS_SLICE_SHA ] && { echo "system_b extent 0 already stock — skipped"; return 0; }
  confirm_phrase "RESTORE system_b" "Operation: mtkclient wo $SYS_ABS $SYS_LEN $SYS_SLICE (+ readback)" \
    "Current  : sha ${pre:0:16}… (GSI = a0704a79…)" "Target   : stock system_b first 2,211,401,728 B, sha ${SYS_SLICE_SHA:0:16}…" \
    "Note     : rest of system_b (beyond this range) was never written by Phase C"
  mtk wo $SYS_ABS $SYS_LEN "$SYS_SLICE"
  mtk ro $SYS_ABS $SYS_LEN "$RUN/system_b.post.img" >/dev/null; local post; post=$(sha "$RUN/system_b.post.img"); rm -f "$RUN/system_b.post.img"
  [ "$post" = $SYS_SLICE_SHA ] && say "READBACK OK: system_b extent 0 == stock" || die "READBACK MISMATCH on system_b — retry"
}

case "${1:-}" in
system)    run_dir restore-system; mtk_session; restore_system;;
vbmeta)    run_dir restore-vbmeta; mtk_session; restore_part vbmeta_b "$VB_STOCK" $VB_STOCK_SHA;;
boot)      run_dir restore-boot;   mtk_session; restore_boot "$BOOT_MAGISK_SD" $BOOT_MAGISK_SD_SHA "stock + Magisk 30.7 + SD cap";;
boot-plain) run_dir restore-boot-plain; mtk_session; restore_boot "$BOOT_PLAIN_SD" $BOOT_PLAIN_SD_SHA "stock + SD cap, no Magisk";;
boot-official) run_dir restore-boot-official; mtk_session
           restore_part boot_b "$BOOT_STOCK" $BOOT_STOCK_SHA \
             "WARNING  : official image WITHOUT the SD cap — microSD writes fail again (research/sdcard/). Emergency only.";;
misc)      run_dir restore-misc;   mtk_session; restore_part misc "$MISC_OK" $MISC_OK_SHA;;
wipe-data) exec "$(dirname "$0")/flash-gsi.sh" wipe-data;;
all)       run_dir restore-all; mtk_session; restore_system; restore_part vbmeta_b "$VB_STOCK" $VB_STOCK_SHA
           restore_boot "$BOOT_MAGISK_SD" $BOOT_MAGISK_SD_SHA "stock + Magisk 30.7 + SD cap"
           say "Partitions back to stock (boot = stock kernel + Magisk + SD cap). Stock cannot use GSI-formatted /data: run restore-stock.sh wipe-data, then flash-gsi.sh reset.";;
super-full)
  # Last resort, only if the super LP metadata check fails. 12 GiB, several minutes.
  run_dir restore-super; [ -s "$SUPER" ] || die "missing $SUPER"; mtk_session
  confirm_phrase "RESTORE super FULL" "Operation: mtkclient w super $SUPER (12 GiB)" \
    "Effect   : all logical partitions + LP metadata back to the May-2026 slot-B state (verified vs vbmeta_b hashtrees)" \
    "Then     : restore vbmeta/boot as needed, wipe-data, reset"
  mtk w super "$SUPER"; say "Write issued. Verify by: flash-gsi.sh mtk-check (super LP metadata) before rebooting.";;
*) die "usage: restore-stock.sh <system|vbmeta|boot|boot-plain|boot-official|misc|wipe-data|all|super-full>";;
esac
