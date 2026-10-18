#!/bin/bash
# NOT PART OF THE CURRENT PLAN (2026-10-17 evening): the user chose Phase C; do not re-run DSU unless explicitly asked.
# The two earlier DSU runs are confounded: LK's hidden dm-verity warning powered the device off in every stored
# session (research/hidden-verity-warning-2026-10-17.md). Any retry needs the power-press routine on every boot.
# Phase B: install the GSI as a DSU (Dynamic System Update). No partition is written; the GSI lives in
# /data/gsi and is used for ONE boot (non-sticky). A cold reboot returns to stock.
source "$(dirname "$0")/common.sh"
GZ="$WORK/gsi/system-td-arm64-vanilla.raw.gz"; RAW="$WORK/gsi/system-td-arm64-vanilla.img"
[ -f "$GZ" ] && [ -f "$RAW" ] || die "GSI files missing"
SYS_SIZE=$(stat -f%z "$RAW"); USERDATA_SIZE=$((8*1024*1024*1024))
$ADB shell "pm path com.android.dynsystem" | grep -q dynsystem || die "DSU installer not present"
[ "$($ADB shell getprop ro.boot.dynamic_partitions | tr -d '\r')" = "true" ] || die "no dynamic partitions"
say "pushing $(basename "$GZ") to /storage/emulated/0/Download/ (read-only for all partitions; writes a file to /sdcard)"
confirm_write "adb push of the GSI gzip to /sdcard/Download" $ADB push "$GZ" /storage/emulated/0/Download/system_raw.gz
confirm_write "start DSU installation (creates images under /data/gsi; stock partitions untouched)" \
  $ADB shell am start-activity -n com.android.dynsystem/com.android.dynsystem.VerificationActivity \
  -a android.os.image.action.START_INSTALL -d file:///storage/emulated/0/Download/system_raw.gz \
  --el KEY_SYSTEM_SIZE "$SYS_SIZE" --el KEY_USERDATA_SIZE "$USERDATA_SIZE"
say "Accept the on-screen DSU prompt on the tablet, wait for the 'Restart' notification, then tap it (or: adb shell gsi_tool status)."
say "After the test: a cold reboot returns to stock (non-sticky). To purge: adb shell gsi_tool wipe (confirm separately)."
