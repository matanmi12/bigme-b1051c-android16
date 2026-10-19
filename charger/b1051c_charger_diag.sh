#!/system/bin/sh
# Charger mode only (b1051c-charger.rc): what the next normal boot needs to debug the charging screen.
sleep 12
out=/metadata/b1051c/charger-diag.txt
{
  date; getprop ro.bootmode; getprop ro.charger.no_ui; getprop ro.minui.default_rotation
  grep -E "charger|res/" /proc/mounts
  ls -laZ /product/etc/res/values/charger /product/etc/res/images/charger /system/etc/res/images/charger
  dmesg | grep -i -E "charger|init: .*(b1051c|mount|charger)|avc: .*(charger|b1051c)" | tail -150
} > $out 2>&1
