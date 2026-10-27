#!/system/bin/sh
# InKognito alpha 1 - Android-level ROM defaults (system settings, props, roles, grants).
# Table of every item, its class and reason: ROM-DEFAULTS.md (same folder). Open points: notes.md.
#
# Contract
#  - POSIX sh (Android mksh / toybox). Runs as root in the magisk domain.
#  - Main entry (no argument or "system"): ONCE on a FRESH device in boot 2, after sys.boot_completed=1, while the
#    Google Setup Wizard may be on screen. Never reboots, never touches personal settings (locale, time zone, Wi-Fi,
#    accounts, front light, lock screen, Play Protect, developer toggles). A done-marker makes a second run a no-op
#    (FORCE=1 re-runs it); every step is also guarded on its own (re-running gives the same result).
#  - Logging: log() appends to $LOG (set by the caller; fallback below). DRY_RUN=1 logs the writes instead of doing them.
#  - App-dependent steps are separate functions that take the package name; call them after `pm install`.
#  - Use as a library: INKOGNITO_DEFAULTS_LIB=1 . ./apply-system-defaults.sh   (defines functions, runs nothing)
#    or as a command:  sh apply-system-defaults.sh <function> [args]           (e.g. app_einkctl org.b1051c.einkctl)
#
# Suggested order (integrator, stage 2):
#   apply-system-defaults.sh                      # boot 2, right after boot_completed (WebView first)
#   [pm install the APKs]
#   app_einkctl org.b1051c.einkctl                # su policy BEFORE the accessibility service, grants, a11y
#   app_einkui org.b1051c.einkui                  # check only (signature permission)
#   app_sleep org.b1051c.sleep                    # Lock & Sleep: su policy (frame publish), WRITE_SECURE_SETTINGS, READ_MEDIA_IMAGES
#   app_runtime_overlay org.b1051c.overlay.launcher
#   qs_tiles_default org.b1051c.einkctl
#   ime_defaults
#   app_all_files_access org.koreader.launcher    # owner to confirm (see notes.md)
#   [stage E-Ink Control wallpapers + IMPORT file]  (apps defaults, not this script)
#   app_einkctl_start org.b1051c.einkctl          # keeper = ThemeSync first run
#   app_sleep_start org.b1051c.sleep              # leaves the stopped state, first sleep picture
#   post_setup app.lawnchair org.b1051c.einkctl   # BLOCKS until setup completes: run it in the foreground, last
#
# Takes effect only after a reboot: persist.wallpapercolors.max_dark_area when it is first set in boot 2 (zygote
# reads it once) -> call `stage1` in boot 1 instead (or set it from a module's post-fs-data). Everything else is live.

: "${LOG:=/data/adb/b1051c/provision.log}"
: "${MARK_DIR:=/data/adb/b1051c}"
: "${DRY_RUN:=0}"
: "${FORCE:=0}"
DEFAULTS_VERSION=1

log() { echo "$*" >> "$LOG"; }
say() { log "$(date '+%Y-%m-%d %H:%M:%S' 2>/dev/null) sys-defaults: $*"; }

# ---------------------------------------------------------------------------------------------------------------
# ROM values (one place to edit)
WEBVIEW_PKG=com.android.webview
ANIM_KEYS="window_animation_scale transition_animation_scale animator_duration_scale"
SCREEN_OFF_MS=600000
MONET_SEED=CD8E2C
MONET_STYLE=RAINBOW
MAX_DARK_AREA=25
QS_AUTO_DONE="wallet cast night font_scaling inversion"
# Owner's layout (live 2026-10-21) minus TileRom ("Switch system" = the owner's DSU stock image, not shipped).
# @E@ = E-Ink Control component prefix. Class names (TileColour, TileGrey) are code identifiers, keep them.
QS_TILES_TEMPLATE='internet,bt,rotation,custom(@E@TileRefresh),custom(@E@TileColour),custom(@E@TileCover),custom(@E@TileGrey),inversion,dark,night,dnd,airplane,custom(@E@TileAntiShake),custom(@E@TileColorRestore)'
EINKCTL_CLASS_PKG=org.b1051c.einkctl
LATIN_IME=com.android.inputmethod.latin/.LatinIME

# ---------------------------------------------------------------------------------------------------------------
# Helpers
run() {                                   # run CMD...: execute (output into the log) or, with DRY_RUN=1, only log it
  if [ "$DRY_RUN" = 1 ]; then say "DRY-RUN: $*"; return 0; fi
  "$@" >> "$LOG" 2>&1
}
sget() { settings get "$1" "$2" 2>/dev/null | tr -d '\r'; }
in_list() {                               # in_list VALUE "a b c"
  for _il in $2; do [ "$1" = "$_il" ] && return 0; done
  return 1
}
sput() {                                  # sput NS KEY VALUE, read back
  run settings put "$1" "$2" "$3" || { say "ERROR settings put $1 $2 failed"; return 1; }
  [ "$DRY_RUN" = 1 ] && return 0
  _sp=$(sget "$1" "$2")
  [ "$_sp" = "$3" ] && return 0
  say "WARN $1 $2 reads '$_sp' after put '$3'"
  return 1
}
ensure_setting() {                        # ensure_setting NS KEY TARGET ["values treated as platform defaults"]
  _es_c=$(sget "$1" "$2"); [ -z "$_es_c" ] && _es_c=null
  if [ "$_es_c" = "$3" ]; then say "$1 $2 = $3 (ok)"; return 0; fi
  if [ -n "$4" ] && [ "$FORCE" != 1 ] && ! in_list "$_es_c" "$4"; then
    say "$1 $2 = $_es_c kept (not a platform default; ROM value $3; FORCE=1 overrides)"
    return 0
  fi
  sput "$1" "$2" "$3" && say "$1 $2: $_es_c -> $3"
}
pkg_installed() { [ -n "$(pm path "$1" 2>/dev/null)" ]; }
app_uid() {                               # exact match: a substring filter also lists org.b1051c.icon.<pkg>
  pm list packages -U --user 0 "$1" 2>/dev/null | tr -d '\r' | while read -r _au_a _au_b; do
    if [ "$_au_a" = "package:$1" ]; then _au_u=${_au_b#uid:}; echo "${_au_u%%,*}"; break; fi
  done
}
has_perm() { dumpsys package "$1" 2>/dev/null | grep -qF "$2: granted=true"; }
setup_complete() { [ "$(sget secure user_setup_complete)" = 1 ] && [ "$(sget global device_provisioned)" = 1 ]; }
magisk_bin() {
  for _mb in /data/adb/magisk/magisk /debug_ramdisk/magisk "$(command -v magisk 2>/dev/null)"; do
    [ -n "$_mb" ] && [ -x "$_mb" ] && { echo "$_mb"; return 0; }
  done
  return 1
}
wait_boot() {                             # the caller guarantees boot_completed; double-check, up to 600 s
  _wb=0
  while [ "$(getprop sys.boot_completed)" != 1 ]; do
    [ $_wb -ge 600 ] && { say "ERROR sys.boot_completed not 1 after 600 s"; return 1; }
    sleep 2; _wb=$((_wb + 2))
  done
}
wait_settings() {                         # SettingsProvider answers (up to 180 s)
  _ws=0
  while [ $_ws -lt 180 ]; do
    case "$(settings get global boot_count 2>&1 | tr -d '\r')" in
      ''|*rror*|*xception*|*"not found"*|*"an't find"*) ;;
      *) return 0 ;;
    esac
    sleep 2; _ws=$((_ws + 2))
  done
  say "ERROR settings provider not ready after 180 s"
  return 1
}
eink_full_refresh() {                     # one settled GC16 through the shim (cleans ghosting after a big change)
  _gc=$(getprop vendor.debug.hwc.shim.gc16_req)
  case "$_gc" in ''|*[!0-9]*) _gc=0 ;; esac
  run setprop vendor.debug.hwc.shim.gc16_req $((_gc + 1))
}

# ---------------------------------------------------------------------------------------------------------------
# System defaults (boot 2, no app needed)

# Google sign-in (MinuteMaid) in the Setup Wizard crashes with MissingWebViewPackageException when WebViewUpdateService
# picked no provider (seen 2026-10-18 12:10). Must be the first step.
sys_webview() {
  pkg_installed "$WEBVIEW_PKG" || { say "WARN $WEBVIEW_PKG not installed; WebView provider left alone"; return 0; }
  _wv=$(dumpsys webviewupdate 2>/dev/null | tr -d '\r' | grep -m1 'Current WebView package')
  _wvs=$(sget global webview_provider)
  case "$_wv" in
    *"($WEBVIEW_PKG,"*) [ "$_wvs" = "$WEBVIEW_PKG" ] && { say "WebView provider = $WEBVIEW_PKG (ok)"; return 0; } ;;
  esac
  run cmd webviewupdate set-webview-implementation "$WEBVIEW_PKG" || { say "ERROR set-webview-implementation failed"; return 1; }
  [ "$DRY_RUN" = 1 ] && return 0
  _wv=$(dumpsys webviewupdate 2>/dev/null | tr -d '\r' | grep -m1 'Current WebView package')
  say "WebView provider: '${_wv#*: }' (setting webview_provider=$(sget global webview_provider))"
}

# Every animation frame is an e-ink panel update. Applied only over platform defaults (null/1).
sys_animations() {
  for _k in $ANIM_KEYS; do ensure_setting global "$_k" 0 "null 1 1.0 0.0"; done
}

# The panel keeps the last frame; reading needs more than the GSI's 1 minute.
sys_screen_timeout() { ensure_setting system screen_off_timeout "$SCREEN_OFF_MS" "null 60000"; }

# Stay awake while charging kept the lock screen (and the front light) on at night. 0 = AOSP default, ensured.
sys_stay_on() { ensure_setting global stay_on_while_plugged_in 0 "null 1 2 3 4 5 6 7"; }

# Natural (0) = color-managed SF output with saturation 1.0 into the shim's Bigme color pipeline; the GSI came up in 2
# (Saturated = unmanaged).
sys_display_color_mode() {
  ensure_setting system display_color_mode 0 "null 1 2 3"
  [ "$DRY_RUN" = 1 ] || say "SF color props: native_mode=$(getprop persist.sys.sf.native_mode) saturation=$(getprop persist.sys.sf.color_saturation)"
}

# Dark theme = one manual switch (QS "Dark theme" tile; ThemeSync follows it). Never auto/scheduled: it would swap
# the wallpaper and keyboard by itself. config_defaultNightMode is 1 on this build, so on a fresh device this is a no-op.
sys_night_mode() {
  _nm=$(sget secure ui_night_mode)
  case "$_nm" in
    1) say "ui_night_mode = 1 (manual light, ok)" ;;
    2) say "ui_night_mode = 2 (manual dark) kept" ;;
    *) run cmd uimode night no || { say "ERROR cmd uimode night no failed"; return 1; }
       [ "$DRY_RUN" = 1 ] && return 0
       sleep 1
       say "ui_night_mode: ${_nm:-null} -> $(sget secure ui_night_mode) (manual light)" ;;
  esac
}

# Monet pinned to G (owner verdict 2026-10-20 20:29): preset seed CD8E2C, RAINBOW, exact-gray neutrals. "preset" means
# wallpaper sets never re-seed it. SystemUI defers applying it until setup is complete ("Theme application deferred").
sys_monet_pin() {
  _mj=$(sget secure theme_customization_overlay_packages)
  case "$_mj" in *'"android.theme.customization.color_source":"preset"'*) _m1=1 ;; *) _m1=0 ;; esac
  case "$_mj" in *"\"android.theme.customization.system_palette\":\"$MONET_SEED\""*) _m2=1 ;; *) _m2=0 ;; esac
  case "$_mj" in *"\"android.theme.customization.theme_style\":\"$MONET_STYLE\""*) _m3=1 ;; *) _m3=0 ;; esac
  if [ $_m1 = 1 ] && [ $_m2 = 1 ] && [ $_m3 = 1 ]; then say "Monet pin = preset $MONET_SEED $MONET_STYLE (ok)"; return 0; fi
  _mts="$(date +%s)000"
  _mnew='{"android.theme.customization.color_source":"preset","android.theme.customization.system_palette":"'$MONET_SEED'","android.theme.customization.accent_color":"'$MONET_SEED'","android.theme.customization.theme_style":"'$MONET_STYLE'","android.theme.customization.color_both":"1","_applied_timestamp":'$_mts'}'
  sput secure theme_customization_overlay_packages "$_mnew" && say "Monet pinned: preset $MONET_SEED $MONET_STYLE (was ${_mj:-null})"
}

# Integer PERCENT (getInt(..., 5) / 100), read once in zygote: cream gets dark-text colorHints at 25. Boot 2 can only
# persist it for the NEXT boot (and the wallpaper must be set again after that). Prefer stage1_props in boot 1.
sys_wallpaper_dark_area() {
  _da=$(getprop persist.wallpapercolors.max_dark_area)
  [ "$_da" = "$MAX_DARK_AREA" ] && { say "persist.wallpapercolors.max_dark_area = $MAX_DARK_AREA (ok)"; return 0; }
  run setprop persist.wallpapercolors.max_dark_area "$MAX_DARK_AREA" || { say "ERROR setprop max_dark_area failed"; return 1; }
  say "persist.wallpapercolors.max_dark_area: ${_da:-unset} -> $MAX_DARK_AREA - NEEDS A REBOOT (zygote) + one wallpaper set; it was not set in boot 1"
}

# Mark tiles SystemUI would auto-add later (cast, wallet, font size, ...) as done, so they never land in our layout.
sys_qs_auto_tiles() {
  _qa=$(sget secure qs_auto_tiles); [ "$_qa" = null ] && _qa=""
  _qn=$_qa
  for _t in $QS_AUTO_DONE; do
    case ",$_qn," in *",$_t,"*) ;; *) _qn=${_qn:+$_qn,}$_t ;; esac
  done
  if [ "$_qn" = "$_qa" ]; then say "qs_auto_tiles = $_qa (ok)"; return 0; fi
  sput secure qs_auto_tiles "$_qn" && say "qs_auto_tiles: ${_qa:-null} -> $_qn"
}

# Night Light = our night mode (fwoverlay 1.3: no tint; E-Ink Control NightMode: Night light + Dark theme). A fresh
# device starts with it off, like the light theme.
sys_night_light_off() {
  [ "$(sget secure night_display_activated)" = 1 ] || { say "Night Light not active (ok)"; return 0; }
  sput secure night_display_activated 0 && say "night_display_activated: 1 -> 0"
}

# Read-only: values the layer expects from the modules / GSI. Logs WARN, changes nothing.
sys_checks() {
  _c=$(getprop ro.sf.lcd_density);                           [ "$_c" = 350 ]   || say "WARN ro.sf.lcd_density='$_c' (expected 350 from b1051c-eink-compat)"
  _c=$(getprop persist.sys.phh.enable_sf_gl_backpressure);   [ "$_c" = false ] || say "WARN phh gl backpressure='$_c' (expected false)"
  _c=$(getprop persist.sys.phh.enable_sf_hwc_backpressure);  [ "$_c" = false ] || say "WARN phh hwc backpressure='$_c' (expected false)"
  _c=$(getprop service.sf.prime_shader_cache);               [ "$_c" = 0 ]     || say "WARN service.sf.prime_shader_cache='$_c' (expected 0)"
  _c=$(getprop ro.frp.pst);                                  [ -z "$_c" ]      || say "WARN ro.frp.pst is set (b1051c-gapps deletes it)"
  _c=$(sget secure navigation_mode);                         case "$_c" in 0|null) ;; *) say "WARN navigation_mode='$_c' (the nav-row refresh button needs 3-button navigation)" ;; esac
  _c=$(sget secure default_input_method);                    say "default IME: $_c"
  say "check: user_setup_complete=$(sget secure user_setup_complete) device_provisioned=$(sget global device_provisioned) adb_enabled=$(sget global adb_enabled)"
}

apply_system_defaults() {
  [ "$DRY_RUN" = 1 ] || mkdir -p "$MARK_DIR"
  if [ -f "$MARK_DIR/system-defaults.done" ] && [ "$FORCE" != 1 ]; then
    say "system defaults already applied ($(cat "$MARK_DIR/system-defaults.done" 2>/dev/null)); FORCE=1 to re-run"
    return 0
  fi
  wait_boot || return 1
  wait_settings || return 1
  say "start v$DEFAULTS_VERSION (DRY_RUN=$DRY_RUN FORCE=$FORCE setup_complete=$(sget secure user_setup_complete))"
  sys_webview                 # first: the Setup Wizard's Google sign-in needs a WebView provider
  sys_animations
  sys_screen_timeout
  sys_stay_on
  sys_display_color_mode
  sys_night_mode
  sys_monet_pin
  sys_wallpaper_dark_area
  sys_qs_auto_tiles
  sys_night_light_off
  sys_checks
  if [ "$DRY_RUN" != 1 ]; then
    echo "v$DEFAULTS_VERSION $(date '+%Y-%m-%d %H:%M:%S')" > "$MARK_DIR/system-defaults.done"
  fi
  say "system defaults done"
}

# ---------------------------------------------------------------------------------------------------------------
# Boot 1 (stage 1, post-fs-data, framework not started yet; persistent props are not loaded yet, so a plain setprop
# of persist.* would NOT reach the disk -> resetprop -p writes the persistent store directly).
stage1_props() {
  _rp=""
  for _c in "$(command -v resetprop 2>/dev/null)" /debug_ramdisk/resetprop /data/adb/magisk/resetprop; do
    [ -n "$_c" ] && [ -x "$_c" ] && { _rp=$_c; break; }
  done
  if [ -z "$_rp" ]; then
    _mb=$(magisk_bin) && _rp="$_mb resetprop"
  fi
  [ -n "$_rp" ] || { say "stage1: resetprop not found; max_dark_area left to boot 2 (then it needs a reboot)"; return 1; }
  # Never create /data/property ourselves: init creates it with its FBE policy (encryption=Require).
  [ -d /data/property ] || { say "stage1: /data/property missing; max_dark_area left to boot 2"; return 1; }
  # shellcheck disable=SC2086
  run $_rp -p persist.wallpapercolors.max_dark_area "$MAX_DARK_AREA" \
    && say "stage1: persist.wallpapercolors.max_dark_area=$MAX_DARK_AREA written to the persistent store ($_rp -p)"
}

# ---------------------------------------------------------------------------------------------------------------
# App-dependent steps (call after pm install; each takes the package name)

# Magisk su policy for the app's REAL uid (never the dev tablet's 10144): policy 2 = allow, until 0 = forever,
# logging 0 + notification 0 = no toast (each toast is a panel update). Magisk 30.7: only write statements and SELECTs
# of non-NULL int columns; a SELECT on sqlite_master crashed magiskd (2026-10-21 00:03).
app_su_allow() {                          # app_su_allow PKG [POLICY]
  _sa_p=$1; _sa_pol=${2:-2}
  _sa_uid=$(app_uid "$_sa_p")
  case "$_sa_uid" in ''|*[!0-9]*) say "ERROR su policy: no uid for $_sa_p (installed?)"; return 1 ;; esac
  _sa_m=$(magisk_bin) || { say "ERROR su policy: magisk binary not found"; return 1; }
  run "$_sa_m" --sqlite "REPLACE INTO policies (uid,policy,until,logging,notification) VALUES($_sa_uid,$_sa_pol,0,0,0)" \
    || { say "ERROR su policy for $_sa_p uid $_sa_uid failed"; return 1; }
  [ "$DRY_RUN" = 1 ] && return 0
  # read back from the db file, never through magiskd (a SELECT crashed it on 2026-10-21)
  if command -v sqlite3 >/dev/null 2>&1; then
    _sa_r=$(sqlite3 /data/adb/magisk.db "SELECT policy FROM policies WHERE uid=$_sa_uid" 2>/dev/null | tr -d '\r')
    if [ "$_sa_r" = "$_sa_pol" ]; then say "su policy $_sa_p uid $_sa_uid = $_sa_pol (allow, no toast)"
    else say "WARN su policy $_sa_p uid $_sa_uid reads '$_sa_r'"; return 1; fi
  else
    say "su policy $_sa_p uid $_sa_uid = $_sa_pol written (no sqlite3 to read it back)"
  fi
}

app_grant() {                             # app_grant PKG PERMISSION...
  _gp=$1; shift
  for _perm in "$@"; do
    if has_perm "$_gp" "$_perm"; then say "grant $_gp $_perm (ok)"; continue; fi
    if run pm grant --user 0 "$_gp" "$_perm"; then say "grant $_gp $_perm: granted"; else say "ERROR pm grant $_gp $_perm failed"; fi
  done
}

app_a11y_enable() {                       # app_a11y_enable PKG/full.ClassName  (merged, others kept)
  _ac=$1; _ap=${1%%/*}; _acl=${1#*/}
  _ashort="$_ap/${_acl#"$_ap"}"           # pkg/.Class form, as the framework may store it
  _acur=$(sget secure enabled_accessibility_services); [ "$_acur" = null ] && _acur=""
  case ":$_acur:" in
    *":$_ac:"*|*":$_ashort:"*) say "accessibility $_ac enabled (ok)" ;;
    *) sput secure enabled_accessibility_services "${_acur:+$_acur:}$_ac" && say "accessibility enabled: $_ac" ;;
  esac
  ensure_setting secure accessibility_enabled 1
}

# E-Ink Control: su first (anything below may start the app, and without the Magisk app an unknown uid is denied with
# no prompt), then the development permission + notifications, then the accessibility service.
app_einkctl() {                           # app_einkctl [PKG]
  _e=${1:-org.b1051c.einkctl}
  pkg_installed "$_e" || { say "ERROR $_e not installed"; return 1; }
  app_su_allow "$_e" 2
  app_grant "$_e" android.permission.WRITE_SECURE_SETTINGS android.permission.POST_NOTIFICATIONS
  app_a11y_enable "$_e/$EINKCTL_CLASS_PKG.AppProfileService"
}

# KeeperService is started by BootReceiver (boot / package replaced) or the app; a fresh install gets neither until the
# next boot. ThemeSync's first run (cream wallpaper, keyboard, theme flag) happens at keeper start: stage the
# wallpaper files and the E-Ink Control IMPORT first (apps defaults).
app_einkctl_start() {                     # app_einkctl_start [PKG]
  _e=${1:-org.b1051c.einkctl}
  if dumpsys activity services "$_e" 2>/dev/null | grep -q 'KeeperService'; then say "E-Ink Control keeper running (ok)"; return 0; fi
  if run am start-foreground-service --user 0 -n "$_e/$EINKCTL_CLASS_PKG.KeeperService"; then
    say "E-Ink Control keeper started"
  else
    say "ERROR keeper start failed"; return 1
  fi
}

# Optional (owner decision, notes.md): hide "Switch system" (TileRom = DSU dual boot of the owner's stock image).
app_einkctl_hide_dsu_tile() {             # app_einkctl_hide_dsu_tile [PKG]
  _e=${1:-org.b1051c.einkctl}
  run pm disable --user 0 "$_e/$EINKCTL_CLASS_PKG.TileRom" && say "TileRom disabled for user 0"
}

# SystemUI plugin: com.android.systemui.permission.PLUGIN is a signature permission; it is granted at install because
# the plugin carries the AOSP platform test key of this GSI's SystemUI. Check only.
app_sleep() {                             # app_sleep [PKG]: Lock & Sleep (lock screen look, sleep picture, shutdown picture)
  _s=${1:-org.b1051c.sleep}
  pkg_installed "$_s" || { say "ERROR $_s not installed"; return 1; }
  app_su_allow "$_s" 2                    # it writes the panel frame to /data/vendor/b1051c/sleep as root
  app_grant "$_s" android.permission.WRITE_SECURE_SETTINGS android.permission.READ_MEDIA_IMAGES
  if run appops set --user 0 "$_s" AUTO_REVOKE_PERMISSIONS_IF_UNUSED ignore; then say "$_s: permissions are never auto-revoked"; fi
}

app_sleep_start() {                       # app_sleep_start [PKG]: Android 15 delivers no broadcast to a never-started app
  _s=${1:-org.b1051c.sleep}
  if run am start --user 0 -n "$_s/$_s.WakeActivity"; then
    say "Lock & Sleep started once (first sleep picture rendered)"
  else
    say "ERROR Lock & Sleep start failed"; return 1
  fi
}

app_einkui() {                            # app_einkui [PKG]
  _u=${1:-org.b1051c.einkui}
  pkg_installed "$_u" || { say "ERROR $_u not installed"; return 1; }
  if has_perm "$_u" com.android.systemui.permission.PLUGIN; then
    say "$_u PLUGIN permission granted (ok); SystemUI attaches it on PACKAGE_ADDED"
  else
    say "WARN $_u has no PLUGIN permission (signature mismatch?) - the shade theme and light sliders will not load"
    return 1
  fi
}

# Runtime RROs in /data start disabled. org.b1051c.overlay.launcher (Launcher3: no first-screen search row, nav-row
# jump fix) fully applies after one Launcher3 restart (restart_launcher3, done by post_setup).
app_runtime_overlay() {                   # app_runtime_overlay [OVERLAY_PKG]
  _o=${1:-org.b1051c.overlay.launcher}
  if cmd overlay dump "$_o" 2>/dev/null | grep -q 'mIsEnabled[ .:]*true'; then say "overlay $_o enabled (ok)"; return 0; fi
  run cmd overlay enable --user 0 "$_o" || { say "ERROR overlay enable $_o failed"; return 1; }
  [ "$DRY_RUN" = 1 ] && return 0
  if cmd overlay dump "$_o" 2>/dev/null | grep -q 'mIsEnabled[ .:]*true'; then say "overlay $_o enabled"; else say "WARN overlay $_o not enabled"; return 1; fi
}

# KOReader (targetSdk 30) needs All files access to read /sdcard/koreader (Bigme patches, plugins) and books.
# Owner to confirm the pre-grant (notes.md); without it KOReader asks on its first start.
app_all_files_access() {                  # app_all_files_access [PKG]
  _k=${1:-org.koreader.launcher}
  pkg_installed "$_k" || { say "ERROR $_k not installed"; return 1; }
  case "$(appops get --uid "$_k" MANAGE_EXTERNAL_STORAGE 2>/dev/null | tr -d '\r')" in
    *allow*) say "$_k All files access (ok)" ;;
    *) run appops set --uid "$_k" MANAGE_EXTERNAL_STORAGE allow && say "$_k All files access: allow" ;;
  esac
}

qs_tiles_want() {                         # prints the tile list for E-Ink Control package $1
  if [ "$1" = "$EINKCTL_CLASS_PKG" ]; then _qp="$1/."; else _qp="$1/$EINKCTL_CLASS_PKG."; fi
  echo "$QS_TILES_TEMPLATE" | sed "s|@E@|$_qp|g"
}

# Quick Settings layout. `settings put secure sysui_qs_tiles` is reverted by SystemUI on A16 -> cmd statusbar set-tiles.
# Needs E-Ink Control installed (SystemUI drops custom tiles of missing packages).
qs_tiles_default() {                      # qs_tiles_default [EINKCTL_PKG]
  _e=${1:-org.b1051c.einkctl}
  pkg_installed "$_e" || { say "ERROR $_e not installed; QS tiles not set"; return 1; }
  _qw=$(qs_tiles_want "$_e")
  _qc=$(sget secure sysui_qs_tiles)
  [ "$_qc" = "$_qw" ] && { say "QS tiles (ok)"; return 0; }
  pidof com.android.systemui >/dev/null 2>&1 || { say "ERROR SystemUI not running; QS tiles not set"; return 1; }
  _qt=0
  while [ $_qt -lt 3 ]; do
    run cmd statusbar set-tiles "$_qw"
    [ "$DRY_RUN" = 1 ] && return 0
    sleep 3
    _qc=$(sget secure sysui_qs_tiles)
    [ "$_qc" = "$_qw" ] && { say "QS tiles set: $_qw"; return 0; }
    _qt=$((_qt + 1))
  done
  say "WARN QS tiles read '$_qc' after 3 tries"
  return 1
}
qs_tiles_ensure() {                       # re-set the layout only when one of our custom tiles is missing
  _e=${1:-org.b1051c.einkctl}
  _qc=$(sget secure sysui_qs_tiles)
  for _qs in $(qs_tiles_want "$_e" | tr ',' ' '); do
    case "$_qs" in custom*) case ",$_qc," in *",$_qs,"*) ;; *) say "QS tile $_qs missing -> layout re-set"; qs_tiles_default "$_e"; return $? ;; esac ;; esac
  done
  say "QS custom tiles present (ok)"
}

# LatinIME is the only IME (Gboard is not bundled). Its prefs stay AOSP defaults; pref_keyboard_theme_20140509
# (3 light / 4 dark) is owned by E-Ink Control's ThemeSync. Sets the default only when none is set.
ime_defaults() {                          # ime_defaults [IME_ID]
  _i=${1:-$LATIN_IME}
  _id=$(sget secure default_input_method)
  case "$_id" in
    ''|null) run ime enable --user 0 "$_i"; run ime set --user 0 "$_i"; say "default IME: none -> $_i" ;;
    "$_i") say "default IME = $_i (ok)" ;;
    *) say "default IME = $_id kept" ;;
  esac
}

# Default home. Deferred by post_setup until user_setup_complete=1: a preferred non-system home while the Setup Wizard
# runs can take the Home button away from the wizard (notes.md). Call directly only when setup is complete.
app_default_home() {                      # app_default_home [PKG]
  _h=${1:-app.lawnchair}
  pkg_installed "$_h" || { say "ERROR $_h not installed"; return 1; }
  _hc=$(cmd role get-role-holders --user 0 android.app.role.HOME 2>/dev/null | tr -d '\r')
  [ "$_hc" = "$_h" ] && { say "default home = $_h (ok)"; return 0; }
  run cmd role add-role-holder --user 0 android.app.role.HOME "$_h"
  [ "$DRY_RUN" = 1 ] && return 0
  sleep 1
  _hc=$(cmd role get-role-holders --user 0 android.app.role.HOME 2>/dev/null | tr -d '\r')
  if [ "$_hc" != "$_h" ]; then
    _ha=$(cmd package resolve-activity --brief --user 0 -a android.intent.action.MAIN -c android.intent.category.HOME "$_h" 2>/dev/null | tr -d '\r' | tail -n 1)
    case "$_ha" in "$_h"/*) run cmd package set-home-activity --user 0 "$_ha" ;; esac
    _hc=$(cmd role get-role-holders --user 0 android.app.role.HOME 2>/dev/null | tr -d '\r')
  fi
  if [ "$_hc" = "$_h" ]; then say "default home: $_h"; else say "WARN default home reads '$_hc'"; return 1; fi
}

# Launcher3QuickStep stays the taskbar/recents provider with Lawnchair as home. A restart makes it rebuild its device
# profile with org.b1051c.overlay.launcher (SystemUI rebinds it). Process kill, as used for recovery on the dev tablet.
restart_launcher3() {
  _lp=$(pidof com.android.launcher3 2>/dev/null)
  [ -n "$_lp" ] || { say "Launcher3 not running (nothing to restart)"; return 0; }
  # shellcheck disable=SC2086
  run kill $_lp && say "Launcher3 restarted (was pid $_lp) to load org.b1051c.overlay.launcher"
}
start_home() { run am start --user 0 -a android.intent.action.MAIN -c android.intent.category.HOME; }

# After a Google restore in the Setup Wizard: put back what the layer needs and a restore may replace (QS layout, Monet
# pin, color mode, accessibility service, WebView, night mode). Restored PERSONAL values (timeout, font size, density)
# are kept; animations are only reset when they are back at platform defaults.
after_setup_reassert() {                  # after_setup_reassert [EINKCTL_PKG]
  _ep=${1:-org.b1051c.einkctl}
  sys_webview
  sys_monet_pin
  sys_display_color_mode
  sys_night_mode
  sys_animations
  if pkg_installed "$_ep"; then
    app_a11y_enable "$_ep/$EINKCTL_CLASS_PKG.AppProfileService"
    qs_tiles_ensure "$_ep"
  fi
}

# BLOCKS until the Setup Wizard has finished (user_setup_complete=1 and device_provisioned=1), then: Launcher3 restart,
# default home, Home, re-assert after a possible restore, one GC16. Run it in the FOREGROUND as the last step: an init
# service's background children die with its process group. A pending marker survives a reboot during setup, so a
# boot hook can call it again. POST_SETUP_MAX_S (default 0 = no limit), REASSERT_DELAY_S (default 20).
post_setup() {                            # post_setup [HOME_PKG] [EINKCTL_PKG]
  _hp=${1:-app.lawnchair}; _ep=${2:-org.b1051c.einkctl}
  if [ -f "$MARK_DIR/post-setup.done" ] && [ "$FORCE" != 1 ]; then say "post-setup already done"; return 0; fi
  [ "$DRY_RUN" = 1 ] || { mkdir -p "$MARK_DIR"; : > "$MARK_DIR/post-setup.pending"; }
  _pw=0
  until setup_complete; do
    if [ "${POST_SETUP_MAX_S:-0}" -gt 0 ] && [ $_pw -ge "${POST_SETUP_MAX_S:-0}" ]; then
      say "post-setup: setup not complete after ${_pw}s, giving up (pending marker kept)"; return 1
    fi
    sleep 5; _pw=$((_pw + 5))
  done
  say "post-setup: setup complete (waited ${_pw}s)"
  restart_launcher3
  app_default_home "$_hp"
  sleep 2
  start_home
  sleep "${REASSERT_DELAY_S:-20}"
  after_setup_reassert "$_ep"
  eink_full_refresh
  if [ "$DRY_RUN" != 1 ]; then
    rm -f "$MARK_DIR/post-setup.pending"
    echo "v$DEFAULTS_VERSION $(date '+%Y-%m-%d %H:%M:%S')" > "$MARK_DIR/post-setup.done"
  fi
  say "post-setup done"
}

# Optional, provisioning only (owner's Play Protect choice stays untouched afterwards): root `pm install` counts as an
# adb install, so Play Protect may hold each APK for up to verifier_timeout or ask "send for check". Pause before the
# installs, restore right after.
verifier_pause() {
  _vv=$(sget global verifier_verify_adb_installs); [ -z "$_vv" ] && _vv=null
  if [ ! -f "$MARK_DIR/verifier.saved" ] && [ "$DRY_RUN" != 1 ]; then mkdir -p "$MARK_DIR"; echo "$_vv" > "$MARK_DIR/verifier.saved"; fi
  if [ "$_vv" = 0 ]; then say "verifier_verify_adb_installs already 0"; return 0; fi
  sput global verifier_verify_adb_installs 0 && say "Play Protect adb-install check paused for provisioning (was $_vv)"
}
verifier_restore() {
  [ -f "$MARK_DIR/verifier.saved" ] || { say "verifier: nothing saved, nothing restored"; return 0; }
  _vv=$(cat "$MARK_DIR/verifier.saved")
  if [ "$_vv" = null ]; then run settings delete global verifier_verify_adb_installs; else sput global verifier_verify_adb_installs "$_vv"; fi
  run rm -f "$MARK_DIR/verifier.saved"
  say "Play Protect adb-install check restored ($_vv)"
}

usage() {
  echo "usage: $0 [system|stage1|<function> [args]]"
  echo "functions: app_einkctl app_einkctl_start app_einkctl_hide_dsu_tile app_einkui app_runtime_overlay"
  echo "           app_all_files_access app_default_home app_su_allow app_grant app_a11y_enable qs_tiles_default"
  echo "           qs_tiles_ensure ime_defaults restart_launcher3 post_setup after_setup_reassert verifier_pause"
  echo "           verifier_restore eink_full_refresh sys_* (single system steps)"
}

# ---------------------------------------------------------------------------------------------------------------
if [ "${INKOGNITO_DEFAULTS_LIB:-0}" != 1 ]; then
  mkdir -p "${LOG%/*}" 2>/dev/null
  _cmd=${1:-system}
  [ $# -gt 0 ] && shift
  case "$_cmd" in
    system) apply_system_defaults ;;
    stage1) stage1_props ;;
    app_*|qs_*|ime_*|sys_*|post_setup|after_setup_reassert|restart_launcher3|verifier_pause|verifier_restore|eink_full_refresh|start_home)
      "$_cmd" "$@" ;;
    -h|--help|help) usage ;;
    *) usage; say "unknown command '$_cmd'"; exit 2 ;;
  esac
fi
