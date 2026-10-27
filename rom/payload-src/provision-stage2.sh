#!/system/bin/sh
# InKognito first boot, stage 2 (inkognito.rc, on sys.boot_completed=1, in the background): install and configure the
# apps once, while Google setup is on screen. Every step is logged to /data/adb/b1051c/provision.log; a failed step is
# logged and skipped, never retried in a loop. Phases leave markers, so a reboot during setup resumes instead of
# redoing them. Nothing personal is set: language, time, Wi-Fi and accounts come from Google setup.
# Android-level defaults and the per-app steps live in defaults/apply-system-defaults.sh (run as a command).
# Test hook: ROOT=<dir> DRY_RUN=1 runs the checks and logs the actions instead of doing them.
P=${ROOT}/system/etc/inkognito
D=${ROOT}/data/adb/b1051c
LOG=$D/provision.log
MAGISK=${MAGISK:-/debug_ramdisk/magisk}
SD=$P/defaults/apply-system-defaults.sh
EINK=org.b1051c.einkctl
LAWN=app.lawnchair
KO=org.koreader.launcher
APPS="com.topjohnwu.magisk org.b1051c.iconpack $EINK org.b1051c.einkui org.b1051c.sleep org.b1051c.overlay.launcher $LAWN $KO"

[ -f $D/stage2.done ] && exit 0
[ -f $D/stage1.done ] || exit 0          # stage 1 has not run (no Magisk boot?) or is about to reboot
log() { echo "$(date '+%F %T') stage2: $*" >> $LOG; }
run() { if [ -n "$DRY_RUN" ]; then log "DRY: $*"; else "$@" >> $LOG 2>&1 || { log "WARN: failed ($?): $*"; return 1; }; fi; }
sd() { LOG=$LOG MARK_DIR=$D DRY_RUN=${DRY_RUN:+1} sh "$SD" "$@" || { log "WARN: defaults step '$*' reported a problem"; FAILED=1; }; }
done_() { [ -f $D/stage2.$1.done ]; }
mark() { [ -n "$DRY_RUN" ] || touch $D/stage2.$1.done; log "phase $1 done"; }
FAILED=0

# ---- deferred stage 1: Magisk's daemon was not reachable at zygote-start on boot 1 --------------------------------
if [ -f $D/stage1.deferred ] && [ ! -f $D/stage1.deferred.done ]; then
  log "deferred module install"
  rm -f $D/stage1.done $D/stage1.deferred
  ROOT=$ROOT DRY_RUN=$DRY_RUN MAGISK=$MAGISK NO_REBOOT=1 sh $P/provision-stage1.sh
  touch $D/stage1.deferred.done
  if [ -f $D/stage1.done ] && [ ! -f $D/stage1.deferred ] && ls /data/adb/modules_update/*/module.prop >/dev/null 2>&1; then
    # AOSP Provision may already have finished setup in this boot: let Google setup run after the reboot
    run settings put secure user_setup_complete 0
    run settings put global device_provisioned 0
    sleep 5                                # SettingsProvider writes its file ~2 s after a change
    log "rebooting into the layer (deferred)"
    run svc power reboot
    exit 0
  fi
  log "ERROR: deferred module install did not install anything; continuing without the layer"
  FAILED=1; touch $D/stage1.failed
fi
[ -f $D/stage1.failed ] && { FAILED=1; log "stage 1 had failed: continuing with the apps only"; }

# ---- wait for user 0's credential-encrypted storage and the package manager -------------------------------------
i=0
until [ "$(getprop sys.user.0.ce_available)" = true ] || [ -n "$DRY_RUN" ]; do
  i=$((i+1)); [ $i -gt 300 ] && { log "ERROR: user 0 storage not unlocked after 10 min"; exit 0; }; sleep 2
done
until cmd package list packages android >/dev/null 2>&1; do sleep 2; done
log "start (payload $(cat $P/VERSION 2>/dev/null))"

# ---- 1. Android defaults (WebView first: Google sign-in in setup needs a provider; Monet pin before any wallpaper) -
done_ sys || { sd system; mark sys; }

# ---- 2. apps (icon pack before Lawnchair; Magisk app first so su prompts have a UI) --------------------------------
if ! done_ apps; then
  sd verifier_pause                        # Play Protect would hold root installs for a dialog
  for pkg in $APPS; do
    a=$P/apps/$pkg.apk
    [ -f "$a" ] || { log "ERROR: missing $a"; FAILED=1; continue; }
    if [ -n "$DRY_RUN" ]; then log "DRY: pm install $a"; continue; fi
    if pm install -r --user 0 "$a" >> $LOG 2>&1; then log "installed $pkg"
    else log "ERROR: pm install $pkg failed"; FAILED=1; fi
  done
  sd verifier_restore
  mark apps
fi

uid_of() { stat -c %u /data/data/$1 2>/dev/null; }
ctx_of() { stat -c %C /data/data/$1 2>/dev/null; }

# E-Ink Control: wallpapers in its external files dir (ThemeSync reads only there), settings via its IMPORT drop file
einkctl_files() {
  local u x c i=0
  u=$(uid_of $EINK); c=$(ctx_of $EINK)
  [ -n "$u" ] || { log "ERROR: $EINK has no data dir"; FAILED=1; return; }
  # the app creates its own external dir (vold sets owner, ext_data_rw group and quota project)
  run am broadcast --user 0 --include-stopped-packages -n $EINK/.ActionReceiver -a $EINK.THEME_SYNC --ez dry true
  x=/storage/emulated/0/Android/data/$EINK/files    # a bind mount of the lower f2fs in this namespace: chown by hand
  until [ -d $x ] || [ -n "$DRY_RUN" ]; do i=$((i+1)); [ $i -gt 20 ] && break; sleep 1; done
  if [ -d $x ] || [ -n "$DRY_RUN" ]; then
    for w in wallpaper-D-cream.png dark-D.png; do
      run cp -f $P/defaults/$w $x/$w && run chown $u:1078 $x/$w && run chmod 0660 $x/$w
    done
  else
    log "ERROR: $x was not created; no theme wallpapers"; FAILED=1
  fi
  run mkdir -p /data/data/$EINK/files
  run cp -f $P/defaults/einkctl-defaults.json /data/data/$EINK/files/einkctl-import.json
  run chown $u:$u /data/data/$EINK/files /data/data/$EINK/files/einkctl-import.json
  run chmod 0771 /data/data/$EINK/files; run chmod 0600 /data/data/$EINK/files/einkctl-import.json
  run chcon "$c" /data/data/$EINK/files /data/data/$EINK/files/einkctl-import.json
  run am broadcast --user 0 --include-stopped-packages -n $EINK/.ActionReceiver -a $EINK.IMPORT
  sleep 3
  [ -f /data/data/$EINK/files/einkctl-import.json ] && [ -z "$DRY_RUN" ] && { log "WARN: E-Ink Control did not consume the import file"; FAILED=1; }
}

# Lawnchair reads both files on its first start, which comes after setup (it becomes the home app in post_setup)
lawnchair_prefs() {
  local d=/data/data/$LAWN u c
  u=$(uid_of $LAWN); c=$(ctx_of $LAWN)
  [ -n "$u" ] || { log "ERROR: $LAWN has no data dir"; FAILED=1; return; }
  pidof $LAWN >/dev/null 2>&1 && run am force-stop $LAWN
  run mkdir -p $d/shared_prefs $d/files/datastore
  run cp -f $P/defaults/lawnchair/com.android.launcher3.prefs.xml $d/shared_prefs/
  run cp -f $P/defaults/lawnchair/preferences.preferences_pb $d/files/datastore/
  run chown -R $u:$u $d/shared_prefs $d/files
  run chmod 0771 $d/shared_prefs $d/files; run chmod 0700 $d/files/datastore
  run chmod 0660 $d/shared_prefs/com.android.launcher3.prefs.xml; run chmod 0600 $d/files/datastore/preferences.preferences_pb
  run chcon -R "$c" $d/shared_prefs $d/files
}

# KOReader keeps settings, user patches and user plugins in /sdcard/koreader; write through FUSE so MediaProvider owns them
koreader_skel() {
  local t=/data/local/tmp/inkognito-ko k=/storage/emulated/0/koreader
  run rm -rf $t; run mkdir -p $t
  run tar -xf $P/defaults/koreader.tar -C $t
  run mkdir -p $k
  if [ -f $k/settings.reader.lua ] && [ -z "$DRY_RUN" ]; then
    log "KOReader settings already exist (restored?): only patches and plugins are added"; run rm -f $t/settings.reader.lua
  fi
  run cp -rf $t/. $k/
  run rm -rf $t
}

# ---- 3. app setup -------------------------------------------------------------------------------------------------
if ! done_ cfg; then
  sd app_einkctl $EINK                     # su policy for its real uid, WRITE_SECURE_SETTINGS + notifications, a11y
  sd app_einkui org.b1051c.einkui
  sd app_sleep org.b1051c.sleep              # Lock & Sleep: su policy (it publishes the sleep picture as root), grants
  sd app_runtime_overlay org.b1051c.overlay.launcher
  sd qs_tiles_default $EINK
  sd ime_defaults
  sd app_all_files_access $KO
  einkctl_files
  sd app_einkctl_start $EINK               # keeper (ThemeSync first run: cream wallpaper, keyboard, theme flag)
  sd app_sleep_start org.b1051c.sleep        # leaves Android's "stopped" state, renders the first sleep picture
  lawnchair_prefs
  koreader_skel
  mark cfg
fi

# ---- 4. after setup: Lawnchair as home, Home, re-assert what a Google restore may replace, one full refresh --------
done_ post || { sd post_setup $LAWN $EINK; mark post; }

touch $D/stage2.done
if [ $FAILED = 0 ]; then log "done"
else
  log "done with errors"
  run cmd notification post -S bigtext -t InKognito inkognito "Setup of the e-ink layer had errors. Log: /data/adb/b1051c/provision.log"
fi
exit 0
