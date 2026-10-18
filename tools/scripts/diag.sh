#!/bin/bash
# Read-only snapshot of the running system (stock or GSI) into research/<timestamp>/.
source "$(dirname "$0")/common.sh"
OUT="$RES/snap-$(date +%Y%m%d-%H%M%S)"; mkdir -p "$OUT"
$ADB get-state >/dev/null 2>&1 || die "no adb device"
$ADB shell getprop > "$OUT/getprop.txt"
$ADB shell "getprop ro.build.fingerprint; getprop ro.boot.slot_suffix; getprop ro.boot.verifiedbootstate; getprop ro.boot.veritymode; getprop sys.boot_completed; getenforce" | tee "$OUT/summary.txt"
$ADB shell dumpsys SurfaceFlinger > "$OUT/dumpsys_sf.txt" 2>&1 || true
$ADB shell dumpsys display > "$OUT/dumpsys_display.txt" 2>&1 || true
$ADB logcat -d -s hwcshim > "$OUT/logcat_hwcshim.txt" 2>&1 || true
$ADB logcat -d -b all -t 4000 > "$OUT/logcat_tail.txt" 2>&1 || true
$ADB shell "su -c 'dmesg'" > "$OUT/dmesg.txt" 2>&1 || $ADB shell dmesg > "$OUT/dmesg.txt" 2>&1 || true
$ADB shell "su -c 'cd /sys/kernel/debug/eink_debug && for f in *; do [ \"\$f\" = waveform ] && continue; printf \"%s=\" \$f; head -c 200 \$f 2>&1 | tr \"\\n\" \" \"; echo; done'" > "$OUT/eink_debug.txt" 2>&1 || true
$ADB logcat -d | grep -E "avc: denied" | sort | uniq -c | sort -rn > "$OUT/avc_denials.txt" 2>/dev/null || true
echo "snapshot in $OUT"; ls -l "$OUT"
