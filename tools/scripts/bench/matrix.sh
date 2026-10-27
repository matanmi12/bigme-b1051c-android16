#!/system/bin/sh
# Vendor-prop writes (allow/deny) x Anti-shake (1/0) x mode (179 Extreme, 178 Fast); 2 alternating rounds each. Root.
MP=/data/adb/magisk/magiskpolicy
R1="hal_graphics_composer_default vendor_default_prop property_service set"
R2="hal_graphics_composer_default vendor_mtk_hwc_debug_log_prop property_service set"
cpu() { awk '{print $14+$15}' /proc/$1/stat; }
input keyevent WAKEUP; sleep 1; wm dismiss-keyguard; sleep 1
am start -W -n com.android.settings/.Settings >/dev/null 2>&1; sleep 3
for mode in 179 178; do
  setprop vendor.xrz.global_refresh_mode $mode
  for rnd in 1 2; do
    for w in allow deny; do
      $MP --live "$w $R1" "$w $R2"
      for af in 1 0; do
        setprop vendor.xrz.anti_flicker_enabled $af
        input swipe 900 1500 900 1400 200; sleep 2
        lvl=$(cat /sys/kernel/debug/eink_debug/anti_flicker | sed 's/.*=//')
        HWC=$(pidof android.hardware.graphics.composer@2.3-service); c0=$(cpu $HWC); i0=$(cpu 1)
        dmesg -c >/dev/null
        for k in 1 2 3 4; do
          input swipe 900 1900 900 700 400; sleep 1.5
          input swipe 900 700 900 1900 400; sleep 1.5
        done
        c1=$(cpu $HWC); i1=$(cpu 1)
        den=$(dmesg | grep avc | grep -c "hal_graphics_composer.*property_service")
        up=$(dmesg | grep "update image:order" | sed -E 's/^\[ *([0-9.]+)\].*/\1/' | awk '{if(NR>1){d=($1-p)*1000; if(d<1000){s+=d; k++; if(d>m)m=d}} p=$1} END{printf "%d %.1f %.0f", NR, (k?s/k:0), m}')
        echo "R $mode $w $af $lvl $up $(( (c1-c0)*10 )) $(( (i1-i0)*10 )) $den"
      done
    done
  done
done
$MP --live "deny $R1" "deny $R2"
setprop vendor.xrz.global_refresh_mode 180; setprop vendor.xrz.anti_flicker_enabled 1
input keyevent HOME
echo "restored: deny, mode $(getprop vendor.xrz.global_refresh_mode), antishake $(getprop vendor.xrz.anti_flicker_enabled)"
