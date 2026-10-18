#!/bin/bash
# Build a Magisk v30.7 boot image — no partition is touched.
# Patches the official stock boot_b (dd26e524…, Mar-2026 kernel) WITH the microSD cap (work/sdcard/boot_b_stock_sd50mhz.bin,
# = stock + DT mmc@11240000/max-frequency 50 MHz, SD guard 2026-10-18) using Magisk's own boot_patch.sh, run on the
# GSI as root (adb root, userdebug) inside /data/local/tmp/mp only. Then pulls the result and checks it offline
# (header v2, kernel identical to stock, DTB = stock + SD cap, full 40 MiB). The Magisk UI is not needed.
# History: the WRITE 4 image (work/phase-c/boot_b.magisk-v30.7.img, 170538a9…) was built from the uncapped official
# image; the live boot_b since WRITE 6 is that image + cap (57d07a2b…). Magisk re-patches keep the DTB as it is.
# Flags: KEEPVERITY=true KEEPFORCEENCRYPT=true (leave the ramdisk fstab alone — verity is already off via vbmeta_b).
source "$(dirname "$0")/common.sh"
set -o pipefail
APK="$WORK/magisk/Magisk-v30.7.apk"; APK_SHA=e0d32d2123532860f97123d927b1bb86c4e08e6fd8a48bfc6b5bee0afae9ebd5
X="$WORK/magisk/v30.7-extract"; OUT="$WORK/sdcard/boot_b.magisk-v30.7.sd50-repatch.img"
BOOT_STOCK="$DUMP/boot_b.bin"; BOOT_STOCK_SHA=dd26e52466aedf7d93546b07741673af1ecac1d1b441f251e81d6878c00405e0   # reference only
BOOT_SRC="$WORK/sdcard/boot_b_stock_sd50mhz.bin"; BOOT_SRC_SHA=bc82b1b6a4d6556d662b3c7d0fdc7bc91907c7119cb6308284ca6ba44606bea0
need_hash "$APK" $APK_SHA; need_hash "$BOOT_STOCK" $BOOT_STOCK_SHA; need_hash "$BOOT_SRC" $BOOT_SRC_SHA
check boot "$BOOT_SRC" "$BOOT_STOCK"   # SD guard on the input
[ -s "$X/assets/boot_patch.sh" ] && [ -s "$X/lib/arm64-v8a/libmagiskboot.so" ] || die "extract the APK first (work/magisk/v30.7-extract)"
$ADB get-state >/dev/null 2>&1 || die "no adb device"
$ADB shell getprop ro.build.fingerprint | grep -q tdgsi || die "not running the TrebleDroid GSI"
$ADB root >/dev/null; $ADB wait-for-device
[ "$($ADB shell id -u | tr -d '\r')" = 0 ] || die "adb root failed"
D=/data/local/tmp/mp
$ADB shell "rm -rf $D && mkdir -p $D"
$ADB push "$X/assets/boot_patch.sh" "$X/assets/util_functions.sh" "$X/assets/stub.apk" $D/ >/dev/null
for p in magiskboot:libmagiskboot.so magiskinit:libmagiskinit.so magisk:libmagisk.so init-ld:libinit-ld.so busybox:libbusybox.so; do
  $ADB push "$X/lib/arm64-v8a/${p#*:}" "$D/${p%%:*}" >/dev/null; done
$ADB push "$BOOT_SRC" $D/boot.img >/dev/null
$ADB shell "cd $D && chmod 755 magiskboot magiskinit magisk init-ld busybox boot_patch.sh && sha256sum boot.img" | grep -q $BOOT_SRC_SHA \
  || die "boot.img on device does not match the capped stock image"
say "Running Magisk boot_patch.sh on the device (files in $D only)"
$ADB shell "cd $D && KEEPVERITY=true KEEPFORCEENCRYPT=true PATCHVBMETAFLAG=false RECOVERYMODE=false ./busybox sh boot_patch.sh boot.img" 2>&1 | tee "${OUT%.img}.log"
$ADB pull $D/new-boot.img "$OUT" >/dev/null || die "no new-boot.img produced"
check boot "$OUT" "$BOOT_STOCK"   # SD guard on the output (Magisk must not have touched the cap)
echo "$(sha "$OUT")  $(basename "$OUT")" | tee -a "$WORK/sdcard/SHA256SUMS"
say "Candidate ready: $OUT  ->  flash-gsi.sh boot $OUT (separate approval)."
