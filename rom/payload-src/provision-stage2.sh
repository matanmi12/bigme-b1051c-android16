#!/system/bin/sh
# InKognito first boot, stage 2 (inkognito.rc, on sys.boot_completed=1, in the background): install and configure the
# apps once, while Google setup is on screen. Every step is logged to /data/adb/b1051c/provision.log; a failed step is
# logged and skipped, never retried in a loop. Phases leave markers, so a reboot during setup resumes instead of
# redoing them. Nothing personal is set: language, time, Wi-Fi and accounts come from Google setup.
# Android-level defaults and the per-app steps live in defaults/apply-system-defaults.sh (run as a command).
# Update (new payload VERSION over an existing install, after stage 1 re-staged the modules): only the apps are
# installed again (an app the user removed stays removed); settings and defaults are left as they are.
# Test hook: ROOT=<dir> DRY_RUN=1 runs the checks and logs the actions instead of doing them.
P=${ROOT}/system/etc/inkognito
D=${ROOT}/data/adb/b1051c
LOG=$D/provision.log
MAGISK=${MAGISK:-/debug_ramdisk/magisk}
SD=$P/defaults/apply-system-defaults.sh
EINK=org.b1051c.einkctl
LAWN=app.lawnchair
KO=org.koreader.launcher
HB=helium314.keyboard
APPS="com.topjohnwu.magisk org.b1051c.iconpack $EINK org.b1051c.einkui org.b1051c.sleep org.b1051c.overlay.launcher $LAWN $KO"

V=$(cat $P/VERSION 2>/dev/null)
# Every stage line also goes to logcat (tag InKognito), so an install can be checked without root (a remote `su` on a
# fresh system raises Magisk's dialog on the tablet):  adb logcat -d -s InKognito
log() { echo "$(date '+%F %T') stage2: $*" >> $LOG; [ -n "$DRY_RUN" ] || /system/bin/log -p i -t InKognito "stage2: $*" 2>/dev/null; }
# logged CMD...: run CMD with its output captured through a pipe, then appended to the log by this shell. Never
# redirect pm/am/cmd/settings/appops/ime straight into $LOG: they hand their stdout fd to system_server, which may not
# write adb_data_file, so the whole call fails ("Failed transaction", test flash 2026-11-10).
logged() { _lo=$("$@" 2>&1); _lr=$?; [ -n "$_lo" ] && printf '%s\n' "$_lo" >> $LOG; return $_lr; }
run() { if [ -n "$DRY_RUN" ]; then log "DRY: $*"; else logged "$@" || { log "WARN: failed ($?): $*"; return 1; }; fi; }
sd() { LOG=$LOG MARK_DIR=$D DRY_RUN=${DRY_RUN:+1} sh "$SD" "$@" || { log "WARN: defaults step '$*' reported a problem"; FAILED=1; }; }
FAILED=0

# ---- module sepolicy rules --------------------------------------------------------------------------------------
# magiskinit loads the modules' rules from the preinit store early in the boot. When the store was empty at that
# point (first boot after an install that wiped /metadata, before stage 1 learned to create it; a store Magisk had to
# rebuild), the modules run without their rules until the next reboot: the shim's debugfs writes were denied 884
# times in the install test of 2026-11-11. Check every rule of every enabled module against the running policy and
# load a module's file live when one is missing. Rules that are already in the policy are left alone.
rules_missing() {                         # rules_missing RULE_FILE POLICY_DUMP: prints the first rule that is not loaded
  local a s t c perms l p
  while read -r a s t c perms; do
    case "$a" in ''|'#'*) continue ;; esac
    case "$a$s$t$c" in allow*[{}*]*) echo "$a $s $t $c (not checked)"; return ;; allow*) ;; *) echo "$a $s (not checked)"; return ;; esac
    l=$(grep "^allow $s $t $c {" "$2" | tr '\n' ' ')
    for p in $perms; do
      case "$p" in '{'|'}') continue ;; esac
      case " $l " in *" $p "*) ;; *) echo "allow $s $t $c $p"; return ;; esac
    done
  done < "$1"
}
sepolicy_live() {
  local mp=/data/adb/magisk/magiskpolicy dump=$D/policy.dump r d id miss
  [ -n "$DRY_RUN" ] && { log "DRY: check the module sepolicy rules against the running policy"; return; }
  ls /data/adb/modules/*/sepolicy.rule >/dev/null 2>&1 || return
  [ -x $mp ] || { log "WARN: no magiskpolicy: module sepolicy rules not checked"; return; }
  $mp --print-rules > $dump 2>/dev/null
  for r in /data/adb/modules/*/sepolicy.rule; do
    d=${r%/*}; id=${d##*/}
    [ -f $d/disable ] || [ -f $d/remove ] && continue
    miss=$(rules_missing $r $dump)
    if [ -z "$miss" ]; then log "sepolicy: $id rules are in the running policy"; continue; fi
    log "sepolicy: $id: '$miss' is not in the running policy; loading $r now"
    if logged $mp --live --apply $r; then log "sepolicy: $id rules loaded live (a restart makes every service start with them)"
    else log "ERROR: sepolicy: live load failed for $id"; FAILED=1; fi
  done
  rm -f $dump
}

# HeliBoard (system app from the module b1051c-heliboard) keeps its data in device-encrypted storage. InKognito
# settings (outlined keys through the RRO, light/dark colors, no key popup, gesture typing) as a ready file: never
# edit that file with sed (an '&' in a replacement once broke it; HeliBoard then reset everything). The Hebrew
# dictionary is always staged: HeliBoard has none built in and only loads it when Hebrew is one of the languages.
# $1 = keep: existing settings are never replaced (update).
heliboard_files() {
  local d=/data/user_de/0/$HB f u c
  [ -d $d ] || [ -n "$DRY_RUN" ] || { log "WARN: $HB has no data dir (module b1051c-heliboard removed?): keyboard left as it is"; return 1; }
  u=$(stat -c %u $d 2>/dev/null); c=$(stat -c %C $d 2>/dev/null)
  f=$d/shared_prefs/${HB}_preferences.xml
  if [ "$1" = keep ] && [ -f $f ]; then
    log "HeliBoard settings exist: kept"
  else
    run am force-stop $HB
    run mkdir -p $d/shared_prefs
    run cp -f $P/defaults/heliboard/preferences.xml $f
    run chown -R $u:$u $d/shared_prefs
    run chmod 0771 $d/shared_prefs; run chmod 0660 $f
    run chcon -R "$c" $d/shared_prefs
    log "HeliBoard: InKognito settings written"
  fi
  if [ ! -f $d/files/dicts/he/main_user.dict ] || [ -n "$DRY_RUN" ]; then
    run mkdir -p $d/files/dicts/he
    run cp -f $P/defaults/heliboard/main_he.dict $d/files/dicts/he/main_user.dict
    run chown $u:$u $d/files; run chown -R $u:$u $d/files/dicts
    run chmod 0771 $d/files; run chmod 0700 $d/files/dicts $d/files/dicts/he; run chmod 0600 $d/files/dicts/he/main_user.dict
    run chcon "$c" $d/files; run chcon -R "$c" $d/files/dicts
    log "HeliBoard: Hebrew dictionary staged"
  fi
}

# One line per finished run for `adb logcat -s InKognito`: what stage 1 did in the boot before (its own logcat lines
# are gone after the reboot) and how this run ended.
# logcat is short-lived (the first lines of a run were already gone a minute after the alpha 7 update), so the same
# result is also kept in a property that survives reboots and needs no root:
#   adb shell getprop persist.debug.inkognito.status
summary() {
  local s1 st
  s1=$(grep 'stage1: modules:' $LOG 2>/dev/null | tail -n 1 | sed 's/.*stage1: //')
  log "summary: payload $V; $1; stage 1: ${s1:-no record}; log: /data/adb/b1051c/provision.log"
  st=$(echo "$s1" | sed -n 's/^modules: \([0-9]*\) installed, \([0-9]*\) failed; sepolicy rules in the preinit store: \([0-9]*\).*/\1 modules, \2 failed, \3 rules/p')
  st=$(echo "${V#InKognito-B1051CPro-}: $1; ${st:-stage 1: no record}" | cut -c1-91)
  [ -n "$DRY_RUN" ] && { log "DRY: setprop persist.debug.inkognito.status '$st'"; return; }
  setprop persist.debug.inkognito.status "$st" 2>/dev/null || log "WARN: could not set persist.debug.inkognito.status"
}
if [ -f $D/stage2.done ]; then
  [ "$(cat $D/payload.version 2>/dev/null)" = "$V" ] && exit 0
  [ "$(cat $D/update.stage1.done 2>/dev/null)" = "$V" ] || exit 0   # stage 1 re-stages the modules first
  until [ "$(getprop sys.user.0.ce_available)" = true ] || [ -n "$DRY_RUN" ]; do sleep 2; done
  until cmd package list packages android >/dev/null 2>&1; do sleep 2; done
  sepolicy_live
  log "update to $V: apps"
  # As on a first install: a root `pm install` counts as an adb install, and Play Protect held the update of the
  # SystemUI plugin for a "send the app for a security check?" dialog until someone answered it (alpha 7 update,
  # 2026-11-11). Paused only for these installs; the owner's own setting is put back right after the loop (and by
  # the next run of this block when a reboot interrupts it: the saved value stays in verifier.saved).
  sd verifier_pause
  for pkg in $APPS; do
    a=$P/apps/$pkg.apk
    [ -f "$a" ] || continue
    if [ -z "$(pm path $pkg 2>/dev/null)" ] && grep -qx "$pkg" $D/apps.installed 2>/dev/null; then
      log "skip $pkg: removed by the user"; continue
    fi
    if [ -n "$DRY_RUN" ]; then log "DRY: pm install -r $a"; continue; fi
    if logged pm install -r --user 0 "$a"; then log "updated $pkg"; grep -qx "$pkg" $D/apps.installed 2>/dev/null || echo $pkg >> $D/apps.installed
    else log "ERROR: pm install $pkg failed"; ufail=$((ufail+1)); fi
  done
  sd verifier_restore
  # One-time steps for an install made by an older release (own marker each: a later update never repeats them).
  # alpha 7: the device name, only while it is still the GSI's ("TrebleDroid vanilla"); HeliBoard becomes the
  # keyboard only while the default is still the AOSP keyboard (a keyboard the user chose is never replaced).
  if [ ! -f $D/migrate.alpha7.done ]; then
    sd identity_names
    if [ -n "$(pm path $HB 2>/dev/null)" ] || [ -n "$DRY_RUN" ]; then
      ime=$(settings get secure default_input_method 2>/dev/null | tr -d '\r')
      case "$ime" in
        ''|null|com.android.inputmethod.latin/*) heliboard_files keep && sd ime_defaults ;;
        *) log "keyboard: default is $ime, kept"; heliboard_files keep ;;
      esac
    else
      log "keyboard: HeliBoard is not installed (module removed?), kept as it is"
    fi
    [ -n "$DRY_RUN" ] || touch $D/migrate.alpha7.done
  fi
  # beta 1: Phone and Messaging hidden (no SIM in this tablet); once, so an app the user turned back on stays on.
  if [ ! -f $D/migrate.beta1.done ]; then
    sd sys_hide_phone_apps
    [ -n "$DRY_RUN" ] || touch $D/migrate.beta1.done
  fi
  [ -n "$DRY_RUN" ] || echo "$V" > $D/payload.version
  if [ "${ufail:-0}" = 0 ]; then log "update to $V done"; summary "update done"
  else
    log "update to $V done with errors ($ufail app(s) not updated)"; summary "update done with errors"
    [ -n "$DRY_RUN" ] || logged cmd notification post -S bigtext -t InKognito inkognito "The update could not install $ufail app(s). Log: /data/adb/b1051c/provision.log"
  fi
  exit 0
fi
[ -f $D/stage1.done ] || exit 0          # stage 1 has not run (no Magisk boot?) or is about to reboot
done_() { [ -f $D/stage2.$1.done ]; }
mark() { [ -n "$DRY_RUN" ] || touch $D/stage2.$1.done; log "phase $1 done"; }

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
sepolicy_live                              # before anything else: the shim and the apps below rely on the rules

# ---- 1. Android defaults (WebView first: Google sign-in in setup needs a provider; Monet pin before any wallpaper) -
done_ sys || { sd system; mark sys; }

# ---- 2. apps (icon pack before Lawnchair; Magisk app first so su prompts have a UI) --------------------------------
if ! done_ apps; then
  sd verifier_pause                        # Play Protect would hold root installs for a dialog
  for pkg in $APPS; do
    a=$P/apps/$pkg.apk
    [ -f "$a" ] || { log "ERROR: missing $a"; FAILED=1; continue; }
    if [ -n "$DRY_RUN" ]; then log "DRY: pm install $a"; continue; fi
    if logged pm install -r --user 0 "$a"; then log "installed $pkg"; echo $pkg >> $D/apps.installed
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
  sd app_einkctl_hide_dsu_tile $EINK      # "Switch system" (DSU) off the tile editor too (owner 09.11)
  heliboard_files                          # before it becomes the keyboard: it reads the file at its first start
  sd ime_defaults                          # HeliBoard = the keyboard, AOSP keyboard hidden (falls back to AOSP without it)
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

# ---- 5. InKognito Setup (E-Ink Control): untick the parts you don't want; shown once ------------------------------
done_ setupui || { run am start --user 0 -n $EINK/.SetupActivity; mark setupui; }

[ -n "$DRY_RUN" ] || { touch $D/stage2.done $D/migrate.alpha7.done $D/migrate.beta1.done; echo "$V" > $D/payload.version; }
if [ $FAILED = 0 ]; then log "done"; summary "first install done"
else
  log "done with errors"; summary "first install done with errors"
  run cmd notification post -S bigtext -t InKognito inkognito "Setup of the e-ink layer had errors. Log: /data/adb/b1051c/provision.log"
fi
exit 0
