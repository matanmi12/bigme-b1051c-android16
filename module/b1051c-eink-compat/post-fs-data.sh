#!/system/bin/sh
# Runs before HALs start. Seed the properties Bigme's framework would set; harmless on stock
# (values identical to stock), required on a GSI. 'ro.' props are only settable if unset.
MODDIR=${0%/*}
[ -f "$MODDIR/disable" ] && exit 0
sp() { resetprop -n "$1" "$2" 2>/dev/null; }
sp ro.vendor.xrz.screen_type 33
sp ro.sf.lcd_density 300          # stock value (system build.prop); GSI default was 213
for kv in default_anti_alias=0 default_anti_flicker=1 default_auto_clean=1 default_bleach_bg_color=10 default_brightness_level=0 default_color_enhance=70 default_contrast_level=0 default_refresh_frequency=0 default_refresh_mode=178 default_text_enhance=0 default_text_plus=0 display_policy_version=6 normal_standby_enable=1 pen_type=1 phy_rotation=270 sytlus_type=0; do
  sp "ro.vendor.xrz.${kv%%=*}" "${kv#*=}"
done
for kv in anti_alias_enabled=0 anti_flicker_enabled=0 antutu_test=0 auto_clean_enabled=1 bootanimation_refresh_mode=180 check_commit=0 clean_frequency=0 disable_cursor_blink=0 disable_eink_vsync=1 disable_eink_vsync_for_fast=1 disable_inputmethod_fullscreen=1 enable_gpu_image_process=1 enable_home_clean=0 force_global_refresh_mode=-1 fps_limit=0 global_brightness_level=0 global_color_enhance=0 global_color_mode=0 global_contrast=0 global_text_enhance=0 gray_256_dither=0 hwc.color_restoration_suppoort=1 hwc.image_smoothing_suppoort=1 hwc_idle=1 is_scrolling=0 light_limit_level=0 logo_refresh_mode=-2147483471 regal_support=1 scroll_flip_type=1 touchevent.disable=0; do
  sp "vendor.xrz.${kv%%=*}" "${kv#*=}"
done
# Shim defaults: quiet log, fix ON. (Override from adb shell as root at any time.)
sp vendor.debug.hwc.shim.log 1
sp vendor.debug.hwc.shim.commit_use_ct 1
