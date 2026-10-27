#!/system/bin/sh
# A system image update after the install (TrebleDroid monthly): the patched Launcher3 stays off until it is rebuilt
# for that image (review 2026-10-21). Disables the module for this boot and the next ones; delete the file to retry.
MODDIR=${0%/*}
FP=$(getprop ro.system.build.fingerprint)
if [ "$FP" != "google/treble_arm64_bvS/tdgsi_arm64_ab:16/BP2A.250605.031.A3/250617:userdebug/test-keys" ]; then
  touch "$MODDIR/disable"
  mkdir -p /data/adb/b1051c
  echo "$(date) launcher3: system image changed ($FP), module disabled" >> /data/adb/b1051c/launcher3.log
fi
