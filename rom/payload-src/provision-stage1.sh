#!/system/bin/sh
# InKognito first boot, stage 1 (inkognito.rc, on zygote-start, blocking init): install the Magisk modules of the
# release into /data/adb/modules_update with Magisk's own installer (the path every module took on the developer's
# tablet), then reboot so Magisk mounts them and loads their sepolicy rules from boot 2 on.
# Never loops: at most 2 attempts, then the device boots without the layer and stage 2 reports it.
# Test hook: ROOT=<dir> DRY_RUN=1 runs every check and prints the actions instead of doing them.
P=${ROOT}/system/etc/inkognito
D=${ROOT}/data/adb/b1051c
LOG=$D/provision.log
MAGISK=${MAGISK:-/debug_ramdisk/magisk}

[ -f $D/stage1.done ] && exit 0

# /data is f2fs with checkpoint=fs. If this boot runs inside an uncommitted userdata checkpoint (the slot is not marked
# successful yet: an interrupted earlier boot, an OTA slot switch), every write below would be rolled back by the
# reboot at the end and stage 1 would start over on every boot. system_server commits only at boot completion, which
# this boot never reaches, so commit before the first write (a no-op when no checkpoint is active; on a freshly wiped
# device there is nothing to roll back to). Review 2026-10-21.
cp_state=no
if [ -z "$DRY_RUN" ]; then
  vdc checkpoint needsCheckpoint >/dev/null 2>&1; [ $? = 1 ] && { cp_state=committed; vdc checkpoint commitChanges >/dev/null 2>&1 || cp_state=commit-failed; }
fi
mkdir -p $D && chmod 0700 $D
log() { echo "$(date '+%F %T') stage1: $*" >> $LOG; }
run() { if [ -n "$DRY_RUN" ]; then log "DRY: $*"; else "$@"; fi; }
finish() { run touch $D/stage1.done; exit 0; }

n=$(cat $D/stage1.attempts 2>/dev/null); n=$(( ${n:-0} + 1 )); echo $n > $D/stage1.attempts
log "attempt $n ($(getprop ro.build.display.id); payload $(cat $P/VERSION 2>/dev/null); userdata checkpoint: $cp_state)"
if [ $n -gt 2 ]; then
  log "ERROR: two attempts failed; booting without the InKognito layer (see above)"
  touch $D/stage1.failed; finish
fi

# /data/adb/magisk = Magisk's own binaries and scripts from the bundled Magisk 30.7 APK (what the Magisk app's
# "additional setup" would write). The installer needs util_functions.sh and busybox from here.
run mkdir -p /data/adb/magisk
for f in $P/magisk/*; do run cp -f "$f" /data/adb/magisk/; done
run chmod 0755 /data/adb/magisk /data/adb/magisk/*
run chown -R 0:0 /data/adb/magisk

# Magisk's daemon serves --path. It is started before zygote-start; wait a little anyway. After boot completion
# (stage 2's deferred path) TrebleDroid's phh-su covers /debug_ramdisk/magisk: use the copy above instead.
i=0
until $MAGISK --path >/dev/null 2>&1; do
  i=$((i+1))
  if [ $i -gt 30 ]; then
    if [ "$MAGISK" != /data/adb/magisk/magisk ] && /data/adb/magisk/magisk --path >/dev/null 2>&1; then MAGISK=/data/adb/magisk/magisk; fi
    break
  fi
  sleep 1
done
if ! $MAGISK --path >/dev/null 2>&1; then
  # Not fatal for this boot: stage 2 retries the modules after boot_completed (then resets setup and reboots).
  log "Magisk daemon not reachable after 30 s ($MAGISK); deferring the modules to stage 2"
  touch $D/stage1.deferred; finish
fi
MAGISKTMP=$($MAGISK --path)

# Module sepolicy rules go to the preinit store ($MAGISKTMP/.magisk/preinit -> e.g. /metadata/...), which the wipe
# emptied; Magisk's copy step skips the rules when the directory is missing.
pre=$(readlink $MAGISKTMP/.magisk/preinit)
[ -n "$pre" ] && [ ! -d "$pre" ] && { run mkdir -p "$pre"; log "created preinit dir $pre"; }

# Boot 1 only from here on. init started zygote just before this trigger; system_server would wait for the HALs and
# SurfaceFlinger (started by "boot", after us) and could be restarted by its watchdog while the modules unpack, which
# RescueParty counts. Stop it: this boot ends with a reboot either way (also on failure, so zygote is never left
# stopped; the attempt counter above ends a loop).
if [ -z "$DRY_RUN" ] && [ -z "$NO_REBOOT" ]; then
  setprop ctl.stop zygote_secondary; setprop ctl.stop zygote; log "zygote stopped for the install"
fi
reboot_now() { log "$1"; run setprop sys.powerctl "reboot,inkognito-stage1"; exit 0; }

# Read once by system_server at start (wallpaper colorHints are computed then and saved): an integer percent, so the
# cream wallpaper gets dark-text hints (black Lawnchair labels / nav buttons). Persistent props are loaded by now.
run setprop persist.wallpapercolors.max_dark_area 25

ok=0; bad=0
for z in $P/modules/*.zip; do
  [ -f "$z" ] || continue
  if [ -n "$DRY_RUN" ]; then log "DRY: install $z"; ok=$((ok+1)); continue; fi
  # = magisk --install-module, with BOOTMODE set explicitly: util_functions.sh otherwise decides by looking for a
  # running zygote, which is stopped here (BOOTMODE=false = recovery-mode install). Checked on the dev tablet.
  if BOOTMODE=true MAGISKTMP=$MAGISKTMP ASH_STANDALONE=1 OUTFD=1 ZIPFILE="$z" timeout 300 \
       /data/adb/magisk/busybox sh -c '. /data/adb/magisk/util_functions.sh; install_module' >> $LOG 2>&1; then
    ok=$((ok+1)); log "installed $(basename $z)"
  else
    bad=$((bad+1)); log "ERROR: install failed for $(basename $z)"
  fi
done
log "modules: $ok installed, $bad failed; rules: $(wc -l < $MAGISKTMP/.magisk/preinit/sepolicy.rule 2>/dev/null) lines"
if [ $bad -gt 0 ]; then                # no marker: the next boot retries (attempt counter above stops a loop)
  [ -n "$NO_REBOOT" ] && exit 0
  reboot_now "rebooting to retry"
fi

touch $D/stage1.done
[ -n "$NO_REBOOT" ] && { log "modules staged; the caller reboots"; exit 0; }
reboot_now "rebooting into the layer"
