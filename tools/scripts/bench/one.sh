sleep 45
input keyevent WAKEUP; sleep 1; wm dismiss-keyguard; am start -W -n com.android.settings/.Settings >/dev/null 2>&1; sleep 3
for r in 1 2 3; do
HWC=$(pidof android.hardware.graphics.composer@2.3-service); c0=$(awk '{print $14+$15}' /proc/$HWC/stat); i0=$(awk '{print $14+$15}' /proc/1/stat); dmesg -c >/dev/null
for k in 1 2 3 4; do input swipe 900 1900 900 700 400; sleep 1.5; input swipe 900 700 900 1900 400; sleep 1.5; done
c1=$(awk '{print $14+$15}' /proc/$HWC/stat); i1=$(awk '{print $14+$15}' /proc/1/stat)
up=$(dmesg | grep "update image:order" | sed -E 's/^\[ *([0-9.]+)\].*/\1/' | awk '{if(NR>1){d=($1-p)*1000; if(d<1000){s+=d;k++; if(d>m)m=d}} p=$1} END{printf "updates=%d gap_mean=%.0fms gap_max=%.0fms", NR, s/k, m}')
echo "round $r: $up composer_cpu=$(( (c1-c0)*10 ))ms init_cpu=$(( (i1-i0)*10 ))ms avc=$(dmesg | grep -c avc)"
done
input keyevent HOME
