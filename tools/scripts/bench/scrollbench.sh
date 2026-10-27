#!/system/bin/sh
# A/B: composer allowed / denied to set vendor props (eink-compat sepolicy lines 1-4). Runs as root.
MP=/data/adb/magisk/magiskpolicy
cpu() { awk '{print $14+$15}' /proc/$1/stat; }
order() { dmesg | grep -o "update image:order=[0-9]*" | tail -1 | sed 's/.*=//'; }
input keyevent WAKEUP; sleep 1; wm dismiss-keyguard; sleep 1
am start -W -n com.android.settings/.Settings >/dev/null 2>&1; sleep 3
LAYER=$(dumpsys SurfaceFlinger --list | grep -m1 "com.android.settings/" | sed "s/^RequestedLayerState{//; s/ parentId.*//")
echo "layer: $LAYER"
for cond in allow deny allow deny allow deny; do
  if [ $cond = allow ]; then
    $MP --live "allow hal_graphics_composer_default vendor_default_prop property_service set" "allow hal_graphics_composer_default vendor_mtk_hwc_debug_log_prop property_service set"
  else
    $MP --live "deny hal_graphics_composer_default vendor_default_prop property_service set" "deny hal_graphics_composer_default vendor_mtk_hwc_debug_log_prop property_service set"
  fi
  sleep 3
  HWC=$(pidof android.hardware.graphics.composer@2.3-service); SF=$(pidof surfaceflinger)
  c0=$(cpu $HWC); s0=$(cpu $SF); i0=$(cpu 1); o0=$(order); dmesg -c >/dev/null
  dumpsys SurfaceFlinger --latency-clear >/dev/null
  t0=$(awk '{print int($1*1000)}' /proc/uptime)
  for k in 1 2 3 4; do
    input swipe 900 1900 900 700 400; sleep 1.5
    input swipe 900 700 900 1900 400; sleep 1.5
  done
  t1=$(awk '{print int($1*1000)}' /proc/uptime)
  c1=$(cpu $HWC); s1=$(cpu $SF); i1=$(cpu 1); o1=$(order)
  den=$(dmesg | grep avc | grep -c "hal_graphics_composer.*property_service")
  # frame present times (ns): column 2 of --latency (actual present); skip the refresh-period line
  dumpsys SurfaceFlinger --latency "$LAYER" | awk 'NR>1 && $2>0 && $2<9e18 {print $2}' > /data/local/tmp/pres.txt
  n=$(wc -l < /data/local/tmp/pres.txt)
  # panel updates from the kernel log: count, mean and max gap between consecutive updates inside a burst (< 1 s)
  up=$(dmesg | grep "update image:order" | sed -E 's/^\[ *([0-9.]+)\].*/\1/' | awk '{if(NR>1){d=($1-p)*1000; if(d<1000){s+=d; k++; if(d>m)m=d}} p=$1} END{if(k>0) printf "upd_gap_mean=%.0fms upd_gap_max=%.0fms", s/k, m}')
  st=$(awk 'NR>1{d=($1-p)/1e6; s+=d; if(d>m)m=d; a[NR]=d} {p=$1} END{n=NR-1; if(n>0){asort(a); printf "mean=%.1fms max=%.1fms p95=%.1fms", s/n, m, a[int(n*0.95)]}}' /data/local/tmp/pres.txt 2>/dev/null)
  echo "$cond: wall=$((t1-t0))ms frames=$n $st panel_updates=$(( ${o1:-0}-${o0:-0} )) composer_cpu=$(( (c1-c0)*10 ))ms sf_cpu=$(( (s1-s0)*10 ))ms init_cpu=$(( (i1-i0)*10 ))ms prop_denials=$den $up"
done
$MP --live "deny hal_graphics_composer_default vendor_default_prop property_service set" "deny hal_graphics_composer_default vendor_mtk_hwc_debug_log_prop property_service set"
echo "final: denied (as 0.1.28)"
input keyevent HOME
