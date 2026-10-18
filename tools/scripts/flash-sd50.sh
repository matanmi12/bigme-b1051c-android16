#!/bin/bash
# WRITE 6 — cap the microSD host (DT node mmc@11240000, msdc1) at 50 MHz by patching the 4-byte
# `max-frequency` value inside the DTB of the LIVE Magisk boot_b. Evidence and reasoning:
# research/sdcard/SDCARD-2026-10-18.md (card works flawlessly at 48 MHz, data-CRC storms + MTK "bad SD"
# removal at 96 MHz). Same DA mechanism as flash-gsi.sh: pre-read assertion, exact readback, no fastboot.
#
# Usage: flash-sd50.sh <check|write|rollback|reset|magisk-backup>
#   check     offline: both images present, hashes match, exactly 4 bytes differ at the expected offset
#   write     DA session: live boot_b must equal the pre-image (Magisk 30.7, 170538a9…) -> write -> readback
#             (DONE 2026-10-18 21:55 as WRITE 6)
#   rollback  DA session: write the pre-image back (live boot_b must equal the sd50 image) — removes the SD fix
#   reset     leave DA and boot
#   magisk-backup  adb, /data only: Magisk's restore/uninstall slot <- capped plain stock image
# The SD guard itself lives in phasec_check.py (sd-status / sd-patch / boot) and is enforced by flash-gsi.sh boot,
# restore-stock.sh boot|boot-plain|all and magisk-patch.sh.
source "$(dirname "$0")/common.sh"
set -o pipefail
SD="$WORK/sdcard"
PRE="$SD/boot_b_backup_magisk_2026-10-18.bin"; PRE_SHA=170538a9565f1091e3cb54a5419c7f3409399f47ddeac2a0d5e2b3ccb35b5114
IMG="$SD/boot_b_magisk_sd50mhz.bin";           IMG_SHA=57d07a2b5a3d99a1a3c3c37e544a85116e6837952f917de1524a83da5509b1e7
PLAIN="$SD/boot_b_stock_sd50mhz.bin";          PLAIN_SHA=bc82b1b6a4d6556d662b3c7d0fdc7bc91907c7119cb6308284ca6ba44606bea0
PLAIN_GZ="$SD/boot_b_stock_sd50mhz.img.gz";    PLAIN_GZ_SHA=fb70205431f60d31ab33426652e0a4e28d6e8467f43af36d6b90d5c6ffe4eb8a
PATCH_OFF=$((0x195957c))   # dtb section 0x1952800 + 0x40 (MTK prefix before the FDT) + 0x6d3c (mmc@11240000/max-frequency)
[ "$PATCH_OFF" -eq $((0x1952800 + 0x40 + 0x6d3c)) ] || die "internal offset constant inconsistent — do not run"

verify_images() {
  need_hash "$PRE" $PRE_SHA; need_hash "$IMG" $IMG_SHA
  "$PY" -I -c '
import sys
a=open(sys.argv[1],"rb").read(); b=open(sys.argv[2],"rb").read(); off=int(sys.argv[3])
assert len(a)==len(b)==0x2800000, (len(a),len(b))
d=[i for i in range(len(a)) if a[i]!=b[i]]
assert d==list(range(off,off+4)), d[:8]
assert a[off:off+4].hex()=="05f5e100" and b[off:off+4].hex()=="02faf080", (a[off:off+4].hex(), b[off:off+4].hex())
# the FDT that contains the patch: magic at 0x1952840, and the property name table must still say max-frequency
assert b[0x1952840:0x1952844]==bytes.fromhex("d00dfeed")
print("OK: images differ only at %#x..%#x (mmc@11240000 max-frequency 100000000 -> 50000000)" % (off, off+3))' \
    "$PRE" "$IMG" "$PATCH_OFF" || die "image pair failed verification — nothing written"
}

case "${1:-check}" in
check) verify_images ;;

write)  # WRITE 6
  verify_images; run_dir sd50-boot; mtk_session
  mtk r boot_b "$RUN/boot_b.pre.bin" </dev/null >/dev/null; pre=$(sha "$RUN/boot_b.pre.bin")
  [ "$pre" = $IMG_SHA ] && { say "boot_b already holds the sd50 image — nothing to do"; exit 0; }
  [ "$pre" = $PRE_SHA ] || die "live boot_b is not the expected Magisk image ($pre) — NOT writing; inspect $RUN/boot_b.pre.bin"
  confirm_phrase "WRITE boot_b sd50" \
    "Operation: mtkclient w boot_b $IMG  (+ readback)   sha ${IMG_SHA:0:12}…" \
    "Change   : 4 bytes at boot_b+0x195957c (DTB mmc@11240000/max-frequency): 100 MHz -> 50 MHz; kernel, ramdisk, Magisk untouched" \
    "Partition: boot_b only (40 MiB). vbmeta/misc/seccfg/lk untouched. Live pre-image sha ${PRE_SHA:0:12}… kept in $RUN and $SD" \
    "Risk     : kernel rejects the DTB -> no boot of slot B (rollback = write the pre-image back, same DA session)" \
    "Rollback : flash-sd50.sh rollback   (or restore-stock.sh boot for the official stock image)" \
    "Verify   : readback sha == ${IMG_SHA:0:12}…; after boot: /proc/device-tree/mmc@11240000/max-frequency = 02faf080, card mounts and writes"
  mtk multi "w boot_b $IMG;r boot_b $RUN/boot_b.post.bin" </dev/null
  [ "$(sha "$RUN/boot_b.post.bin")" = $IMG_SHA ] && say "READBACK OK: boot_b == sd50 image. Next: flash-sd50.sh reset" \
    || { say "READBACK MISMATCH. DA still connected — run: flash-sd50.sh rollback"; exit 1; }
  ;;

rollback)
  verify_images; run_dir sd50-rollback; mtk_session
  mtk r boot_b "$RUN/boot_b.pre.bin" </dev/null >/dev/null; pre=$(sha "$RUN/boot_b.pre.bin")
  [ "$pre" = $PRE_SHA ] && { say "boot_b already holds the pre-image — nothing to do"; exit 0; }
  confirm_phrase "ROLLBACK boot_b" \
    "Operation: mtkclient w boot_b $PRE  (+ readback)   sha ${PRE_SHA:0:12}…" \
    "Current  : live boot_b sha ${pre:0:12}… (kept in $RUN)"
  mtk multi "w boot_b $PRE;r boot_b $RUN/boot_b.post.bin" </dev/null
  [ "$(sha "$RUN/boot_b.post.bin")" = $PRE_SHA ] && say "READBACK OK: boot_b == pre-image. Next: flash-sd50.sh reset" \
    || { say "READBACK MISMATCH — DA still connected; retry or restore-stock.sh boot"; exit 1; }
  ;;

reset) run_dir sd50-reset; mtk reset </dev/null; say "Device leaves DA and boots slot B (no press routine needed since WRITE 5)." ;;

magisk-backup)  # /data only (no partition write): Magisk's "Restore images" / uninstall flashes
  # /data/magisk_backup_<SHA1 from the live Magisk config>/boot.img.gz without any hash check. Put the capped plain
  # stock image there, so an unroot keeps the microSD fix (without it the restore just fails: no backup exists).
  # /data is wiped on a return to stock: run this again there after Magisk is set up.
  need_hash "$PLAIN" $PLAIN_SHA; need_hash "$PLAIN_GZ" $PLAIN_GZ_SHA
  $ADB get-state >/dev/null 2>&1 || die "no adb device"
  $ADB root >/dev/null 2>&1; $ADB wait-for-device
  R=sh; [ "$($ADB shell id -u | tr -d '\r')" = 0 ] || R="su -c sh"
  $ADB push "$PLAIN_GZ" /data/local/tmp/sdguard-boot.img.gz >/dev/null
  $ADB shell "cat > /data/local/tmp/sdguard-mb.sh" <<'EOS'
set -e
cfg="$(magisk --path)/.magisk/config"
SHA1=$(grep '^SHA1=' "$cfg" | cut -d= -f2)
[ -n "$SHA1" ] || { echo "no SHA1 in $cfg"; exit 1; }
d=/data/magisk_backup_$SHA1; mkdir -p "$d"
[ -f "$d/boot.img.gz" ] && mv "$d/boot.img.gz" "$d/boot.img.gz.prev-$(date +%Y%m%d%H%M%S)"
mv /data/local/tmp/sdguard-boot.img.gz "$d/boot.img.gz"
chown -R root:root "$d"; chmod 700 "$d"; chmod 600 "$d"/*; restorecon -R "$d" 2>/dev/null || true
echo "Magisk backup slot: $d"; ls -la "$d"; sha256sum "$d/boot.img.gz"
EOS
  $ADB shell "$R /data/local/tmp/sdguard-mb.sh; rm -f /data/local/tmp/sdguard-mb.sh" | tee /dev/stderr | grep -q "$PLAIN_GZ_SHA" \
    && say "OK: Magisk restore/uninstall now writes the capped plain stock image (${PLAIN_SHA:0:12}…)" \
    || die "backup slot not verified — see output above"
  ;;
*) die "unknown subcommand: $1 (check|write|rollback|reset|magisk-backup)" ;;
esac
