#!/system/bin/sh
# Magisk module installer hook for b1051c-gapps. Sets permissions only; no partition writes.
[ "$API" = 36 ] || abort "! Built for Android 16 (SDK 36); this system is SDK $API"
[ "$ARCH" = arm64 ] || abort "! Built for arm64; this system is $ARCH"
set_perm_recursive $MODPATH/system 0 0 0755 0644
ui_print "- MindTheGapps 16.0.0-arm64-20260915_222150 subset (magic mount, no partition writes):"
ui_print "  /product/priv-app/GmsCore, /product/priv-app/Phonesky,"
ui_print "  /system_ext/priv-app/GoogleServicesFramework, /product/overlay/GmsOverlay.apk,"
ui_print "  privapp-permissions + sysconfig XMLs for gms/gsf/vending"
ui_print "- First boot after install is slow (GmsCore is dexopted). Reboot to activate."
