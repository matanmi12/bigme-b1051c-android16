#!/system/bin/sh
# Magisk module installer hook: permissions/labels only. No partition writes.
set_perm_recursive "$MODPATH" 0 0 0755 0644
set_perm "$MODPATH/system/vendor/lib64/hw/hwcomposer.mtk_common.so" 0 0 0644 u:object_r:vendor_file:s0
set_perm "$MODPATH/system/bin/phh-su" 0 2000 0755 u:object_r:phhsu_exec:s0
set_perm "$MODPATH/bin/einkd" 0 0 0755
ui_print "- B1051C E Ink compat: hwcomposer.mtk_common.so staged for /vendor/lib64/hw (magic mount)"
ui_print "- stock touch/pen IDC (ORIENTATION_270), lcd_density 300, phh-su stub (Magisk late stages)"
ui_print "- einkd: check_commit pulses on touch/pen/keys (auto full refresh every N pages)"
ui_print "- Knobs: setprop vendor.debug.hwc.shim.{log,commit_use_ct,ct_src,refresh_mode,disable}"
