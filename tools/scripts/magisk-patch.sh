#!/bin/bash
# Build the Magisk v30.7 boot image for WRITE 4 — no partition is touched.
# Patches the CURRENT official stock boot_b (dd26e524…, Mar-2026 kernel) with Magisk's own boot_patch.sh, run on the
# GSI as root (adb root, userdebug) inside /data/local/tmp/mp only. Then pulls the result and checks it offline
# (header v2, kernel + DTB byte-identical to stock, full 40 MiB). The Magisk UI is not needed (panel is tiled).
# Flags: KEEPVERITY=true KEEPFORCEENCRYPT=true (leave the ramdisk fstab alone — verity is already off via vbmeta_b).
source "$(dirname "$0")/common.sh"
set -o pipefail
APK="$WORK/magisk/Magisk-v30.7.apk"; APK_SHA=e0d32d2123532860f97123d927b1bb86c4e08e6fd8a48bfc6b5bee0afae9ebd5
X="$WORK/magisk/v30.7-extract"; OUT="$PHC/boot_b.magisk-v30.7.img"
BOOT_STOCK="$DUMP/boot_b.bin"; BOOT_STOCK_SHA=dd26e52466aedf7d93546b07741673af1ecac1d1b441f251e81d6878c00405e0
need_hash "$APK" $APK_SHA; need_hash "$BOOT_STOCK" $BOOT_STOCK_SHA
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
$ADB push "$BOOT_STOCK" $D/boot.img >/dev/null
$ADB shell "cd $D && chmod 755 magiskboot magiskinit magisk init-ld busybox boot_patch.sh && sha256sum boot.img" | grep -q $BOOT_STOCK_SHA \
  || die "boot.img on device does not match stock"
say "Running Magisk boot_patch.sh on the device (files in $D only)"
$ADB shell "cd $D && KEEPVERITY=true KEEPFORCEENCRYPT=true PATCHVBMETAFLAG=false RECOVERYMODE=false ./busybox sh boot_patch.sh boot.img" 2>&1 | tee "$PHC/magisk-patch.log"
$ADB pull $D/new-boot.img "$OUT" >/dev/null || die "no new-boot.img produced"
check boot "$OUT" "$BOOT_STOCK"
echo "$(sha "$OUT")  $(basename "$OUT")" | tee -a "$PHC/SHA256SUMS.txt"
say "Candidate ready: $OUT  ->  WRITE 4 = flash-gsi.sh boot $OUT (separate approval)."
