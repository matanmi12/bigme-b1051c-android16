#!/system/bin/sh
# Magisk module installer hook: permissions/labels only. No partition writes.
set_perm_recursive "$MODPATH" 0 0 0755 0644
set_perm "$MODPATH/system/fonts/Roboto-Regular.ttf" 0 0 0644 u:object_r:system_file:s0
ui_print "- Rubik replaces Roboto-Regular.ttf (system sans-serif); reboot to apply, disable the module to undo"
