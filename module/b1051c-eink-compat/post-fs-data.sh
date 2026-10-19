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
# Boot display (2026-10-19 boot captures, research/phase-c/20261019-140815-boot-capture):
#  - SurfaceFlinger reads the phh backpressure switches once, at start; service.sh set them only after boot, so the
#    boot SF always ran with backpressure (= the Mali wedge; "one SF restart clears it" was the restart reading them).
#  - SF spent 10.1 s priming 93 shaders before it showed anything; useless on e-ink.
#  - The shim needs CLIENT composition (SF 1008); until boot_completed the boot animation was a DEVICE layer and the
#    vendor HWC skipped every panel update ("No dirty region", eink_update=0 for 43 s).
case "$(getprop ro.build.fingerprint)" in *treble*|*tdgsi*|*phh*)
  resetprop persist.sys.phh.enable_sf_gl_backpressure false
  resetprop persist.sys.phh.enable_sf_hwc_backpressure false
  [ -f "$MODDIR/prime_shader_cache" ] || resetprop service.sf.prime_shader_cache 0
  # shim: whole-buffer damage for every new layer buffer while booting (SF sends none for ~2 s at first)
  [ -f "$MODDIR/no_boot_damage" ] || resetprop vendor.debug.hwc.shim.boot_damage 1
  if [ ! -f "$MODDIR/keep_hw_overlays" ]; then
    (
      last=""; n=0
      while [ "$(getprop sys.boot_completed)" != 1 ] && [ $n -lt 1200 ]; do
        p=$(pidof surfaceflinger)
        if [ -n "$p" ] && [ "$p" != "$last" ] && service check SurfaceFlinger 2>/dev/null | grep -q found \
           && service call SurfaceFlinger 1008 i32 1 >/dev/null 2>&1; then
          log -t b1051c-compat "boot: HW overlays disabled (SF 1008) for surfaceflinger pid $p"
          last=$p
        fi
        n=$((n+1)); sleep 0.2
      done
      resetprop vendor.debug.hwc.shim.boot_damage 0
    ) &
  fi
;; esac
# Wi-Fi-only unit: the vendor modem init (ccci_mdinit / ccci3_mdinit: class core, priority -20, RT I/O) has no modem
# to start. On the GSI it spins ~31 s at boot (9.4 -> 40.1 s) retrying a property read SELinux denies, ~100 denials/s,
# right while SurfaceFlinger and the boot animation start (2026-10-19). It ends with status 0 and changes nothing, so
# stop it as soon as init starts it. Opt out: touch $MODDIR/keep_ccci_mdinit
if [ ! -f "$MODDIR/keep_ccci_mdinit" ]; then
  (
    done0=0; done3=0; n=0
    while [ $n -lt 300 ] && { [ $done0 = 0 ] || [ $done3 = 0 ]; }; do
      if [ "$(getprop init.svc.ccci_mdinit)" = running ]; then setprop ctl.stop ccci_mdinit; done0=1
        log -t b1051c-compat "ccci_mdinit stopped (Wi-Fi only, no modem)"; fi
      if [ "$(getprop init.svc.ccci3_mdinit)" = running ]; then setprop ctl.stop ccci3_mdinit; done3=1; fi
      [ "$(getprop init.svc.ccci3_mdinit)" = stopped ] && done3=1
      n=$((n+1)); sleep 0.1
    done
  ) &
fi
