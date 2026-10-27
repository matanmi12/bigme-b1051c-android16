#!/system/bin/sh
# Scroll benchmark for the colour pipeline. Swipes at x=300: the scrolling list pane of Settings (two-pane layout at density 350; x=900 is the static right pane, 2026-10-22 04:5x).
# Scroll benchmark for the colour pipeline (eink-compat 0.1.31+): rounds of 8 scripted swipes in Settings, alternating
# the global refresh mode; same metrics as scrollbench.sh (kernel update count and gaps, composer/SF CPU) plus the
# shim's sampled "colour:" lines (rows converted, pass time). Runs as root; runtime props only, mode restored at the end.
#   sh colorbench.sh [modes...]      default: 180 178 180 178
cpu() { awk '{print $14+$15}' /proc/$1/stat; }
order() { dmesg | grep -o "update image:order=[0-9]*" | tail -1 | sed 's/.*=//'; }
MODE0=$(getprop vendor.xrz.global_refresh_mode)
MODES="${*:-180 178 180 178}"
input keyevent WAKEUP; sleep 1; wm dismiss-keyguard; sleep 1
am start -W -n com.android.settings/.Settings >/dev/null 2>&1; sleep 3
for mode in $MODES; do
  setprop vendor.xrz.global_refresh_mode $mode; sleep 2
  input swipe 300 1900 300 700 400; sleep 2        # one swipe so the first full conversion is out of the round
  HWC=$(pidof android.hardware.graphics.composer@2.3-service); SF=$(pidof surfaceflinger)
  c0=$(cpu $HWC); s0=$(cpu $SF); o0=$(order); dmesg -c >/dev/null; logcat -c
  t0=$(awk '{print int($1*1000)}' /proc/uptime)
  for k in 1 2 3 4; do
    input swipe 300 1900 300 700 400; sleep 1.5
    input swipe 300 700 300 1900 400; sleep 1.5
  done
  t1=$(awk '{print int($1*1000)}' /proc/uptime)
  c1=$(cpu $HWC); s1=$(cpu $SF); o1=$(order)
  up=$(dmesg | grep "update image:order" | sed -E 's/^\[ *([0-9.]+)\].*/\1/' | awk '{if(NR>1){d=($1-p)*1000; if(d<1000){s+=d; k++; if(d>m)m=d}} p=$1} END{if(k>0) printf "upd_gap_mean=%.0fms upd_gap_max=%.0fms", s/k, m}')
  col=$(logcat -d -s hwcshim | grep -o "rows=[0-9]*/[0-9]* [13]D [0-9]*us" | awk -F'[=/ u]' '{r+=$2; t+=$5; n++} END{if(n>0) printf "colour_samples=%d rows_mean=%.0f pass_mean=%.1fms", n, r/n, t/n/1000}')
  echo "mode=$mode: wall=$((t1-t0))ms panel_updates=$(( ${o1:-0}-${o0:-0} )) composer_cpu=$(( (c1-c0)*10 ))ms sf_cpu=$(( (s1-s0)*10 ))ms $up $col"
done
setprop vendor.xrz.global_refresh_mode $MODE0
input keyevent HOME
echo "restored global_refresh_mode=$MODE0"
