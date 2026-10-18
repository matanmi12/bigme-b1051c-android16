#!/system/bin/sh
# b1051c-gapps safety net: with "enforce", a privileged permission missing from the privapp
# allowlist makes system_server throw at boot (boot loop). "log" still grants and only logs.
MODDIR=${0%/*}
[ -f "$MODDIR/disable" ] && exit 0
resetprop ro.control_privapp_permissions log
