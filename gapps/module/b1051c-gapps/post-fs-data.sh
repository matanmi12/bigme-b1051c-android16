#!/system/bin/sh
# b1051c-gapps safety net: with "enforce", a privileged permission missing from the privapp
# allowlist makes system_server throw at boot (boot loop). "log" still grants and only logs.
MODDIR=${0%/*}
[ -f "$MODDIR/disable" ] && exit 0
resetprop ro.control_privapp_permissions log
# No Google setup wizard on this ROM, so a Google account must never be able to arm Factory Reset Protection
# (after a reset nothing could clear it). Without ro.frp.pst, system_server does not start
# PersistentDataBlockService: neither GMS nor the lock screen can write FRP data. The frp partition is untouched.
resetprop --delete ro.frp.pst
