#!/system/bin/sh
# Late-boot: phh/TrebleDroid GSI only.
# 1) SurfaceFlinger wedges in Mali GL on this vendor; phh backpressure props (HiBreak Pro Color fix). No-ops on stock.
# 2) Boot-time wedge seen on several GSI boots (2026-10-17): boot_completed=1 but bootanim never stops, Mali fences
#    time out, panel stays black. One SurfaceFlinger restart clears it (the framework restarts with it). At most once.
# 3) The shim commits SF's GPU client target to the e-ink driver, so every layer must be CLIENT-composed. With
#    several layers the vendor HWC keeps them as DEVICE (no client target) -> stale/black panel. Keep SurfaceFlinger's
#    "disable HW overlays" (transaction 1008) on, re-applied whenever surfaceflinger (re)starts.
MODDIR=${0%/*}
[ -f "$MODDIR/disable" ] && exit 0
case "$(getprop ro.build.fingerprint)" in *treble*|*tdgsi*|*phh*) ;; *) exit 0 ;; esac
TAG=b1051c-compat
resetprop persist.sys.phh.enable_sf_gl_backpressure false
resetprop persist.sys.phh.enable_sf_hwc_backpressure false
until [ "$(getprop sys.boot_completed)" = 1 ]; do sleep 2; done
# 4) einkd: raises vendor.xrz.check_commit for 800 ms after each touch/pen/key, like stock PowerManager, so the HWC's
#    "full refresh every N big updates" (clean_frequency) counts page turns. Source: work/einkd/einkd.c
if [ -x "$MODDIR/bin/einkd" ] && [ ! -f "$MODDIR/no_einkd" ]; then
  "$MODDIR/bin/einkd" &
  log -t $TAG "einkd started"
fi
if [ ! -f "$MODDIR/no_sf_watchdog" ]; then
  sleep 15
  if [ "$(getprop init.svc.bootanim)" = running ]; then
    log -t $TAG "bootanim still running 15 s after boot_completed -> one surfaceflinger restart"
    setprop ctl.restart surfaceflinger
  else
    log -t $TAG "bootanim stopped by itself; no surfaceflinger restart"
  fi
fi
[ -f "$MODDIR/keep_hw_overlays" ] && exit 0
# Re-apply on every surfaceflinger (re)start, and whenever a DEVICE layer shows up anyway: after a composer crash
# (2026-10-18) the restarted SF was found with every layer DEVICE although the call had been made -> frozen panel.
# The check is cheap (dumpsys --hwclayers ~10 ms) and 1008 is only re-sent when needed.
disable_overlays() {
  n=0
  until service check SurfaceFlinger 2>/dev/null | grep -q found && service call SurfaceFlinger 1008 i32 1 >/dev/null 2>&1; do
    n=$((n+1)); [ $n -gt 30 ] && break; sleep 1
  done
  log -t $TAG "HW overlays disabled (SF 1008) for surfaceflinger pid $1 ($2) after $n retries"
}
last=""
while true; do
  p=$(pidof surfaceflinger)
  if [ -n "$p" ] && [ "$p" != "$last" ]; then
    disable_overlays "$p" "new pid"
    last=$p
  elif [ -n "$p" ] && dumpsys SurfaceFlinger --hwclayers 2>/dev/null | grep -qE '[|] +DEVICE +[|]'; then
    disable_overlays "$p" "DEVICE layers seen"
  fi
  sleep 10
done
