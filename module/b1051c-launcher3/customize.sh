#!/system/bin/sh
# Magisk module installer hook: permissions/labels only. No partition writes.
set_perm_recursive "$MODPATH" 0 0 0755 0644
set_perm "$MODPATH/system/system_ext/priv-app/Launcher3QuickStep/Launcher3QuickStep.apk" 0 0 0644 u:object_r:system_file:s0
ui_print "- Launcher3QuickStep with transparent taskbar on the home screen (replaces the directory, no old oat)"
