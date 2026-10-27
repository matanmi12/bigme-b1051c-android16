#!/system/bin/sh
# Anti-shake on/off x Regal/Extreme, 2 alternating rounds each, 8 swipes per round. Runs as root.
cpu() { awk '{print $14+$15}' /proc/$1/stat; }
input keyevent WAKEUP; sleep 1; wm dismiss-keyguard; sleep 1
am start -W -n com.android.settings/.Settings >/dev/null 2>&1; sleep 3
for mode in 180 179; do
  setprop vendor.xrz.global_refresh_mode $mode
  for af in 1 0 1 0; do
    setprop vendor.xrz.anti_flicker_enabled $af
    input swipe 900 1500 900 1400 200; sleep 2          # one commit so the kernel gets the new level
    lvl=$(cat /sys/kernel/debug/eink_debug/anti_flicker | sed 's/.*=//')
    HWC=$(pidof android.hardware.graphics.composer@2.3-service); c0=$(cpu $HWC)
    dmesg -c >/dev/null
    for k in 1 2 3 4; do
      input swipe 900 1900 900 700 400; sleep 1.5
      input swipe 900 700 900 1900 400; sleep 1.5
    done
    c1=$(cpu $HWC)
    up=$(dmesg | grep "update image:order" | sed -E 's/^\[ *([0-9.]+)\].*/\1/' | awk '{if(NR>1){d=($1-p)*1000; if(d<1000){s+=d; k++; if(d>m)m=d}} p=$1} END{printf "updates=%d gap_mean=%.0fms gap_max=%.0fms", NR, (k?s/k:0), m}')
    echo "mode=$mode antishake=$af(kernel $lvl): $up composer_cpu=$(( (c1-c0)*10 ))ms"
  done
done
setprop vendor.xrz.global_refresh_mode 180; setprop vendor.xrz.anti_flicker_enabled 1
input keyevent HOME; sleep 2
echo "restored: mode $(getprop vendor.xrz.global_refresh_mode), antishake $(getprop vendor.xrz.anti_flicker_enabled)"
