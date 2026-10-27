#!/system/bin/sh
# Magisk module installer hook: permissions/labels only. No partition writes.
set_perm_recursive "$MODPATH" 0 0 0755 0644
set_perm "$MODPATH/system/system_ext/priv-app/Launcher3QuickStep/Launcher3QuickStep.apk" 0 0 0644 u:object_r:system_file:s0
ui_print "- Launcher3QuickStep with transparent taskbar on the home screen (replaces the directory, no old oat)"
# This Launcher3QuickStep is patched from one exact TrebleDroid build (review 2026-10-21): on another system image
# its hidden-API and SystemUI bindings may not match, and a broken quickstep takes the nav buttons with it.
FP=$(getprop ro.system.build.fingerprint)
[ "$FP" = "google/treble_arm64_bvS/tdgsi_arm64_ab:16/BP2A.250605.031.A3/250617:userdebug/test-keys" ] \
  || abort "! System image $FP is not TrebleDroid A16 ci-20250617 (the build this Launcher3 was patched from): not installed"
