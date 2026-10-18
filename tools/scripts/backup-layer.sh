#!/bin/bash
# Back up everything our compatibility layer adds on top of the GSI (roadmap feature 32): Magisk modules as
# installed, our APKs, E-Ink Control's preferences + settings/profiles JSON, Magisk's su policy DB, Android settings
# (secure/system/global), Lawnchair (home screen) + the private icon pack, and KOReader when present. Read-only on the device. Private: never stage backups/
# for the public repo (contains GApps APKs and device settings).
# Usage: backup-layer.sh            -> $BIGME/backups/layer-YYYYMMDD-HHMMSS/ + SHA256SUMS
source "$(dirname "$0")/common.sh"
set -o pipefail
OUT="$BIGME/backups/layer-$(date +%Y%m%d-%H%M%S)"; mkdir -p "$OUT"
say "backup -> $OUT"
$ADB get-state >/dev/null 2>&1 || die "no adb device"
[ "$($ADB shell id -u | tr -d '\r')" = 0 ] || { $ADB root >/dev/null; sleep 2; }
[ "$($ADB shell id -u | tr -d '\r')" = 0 ] || die "adbd is not root (adb root failed)"
sh_() { $ADB shell "$@" | tr -d '\r'; }

# 1. Magisk modules exactly as installed (incl. disable/remove flags) + Magisk su policies
mods=$(sh_ 'cd /data/adb/modules && ls -d b1051c-* 2>/dev/null' | tr '\n' ' ')
[ -n "$mods" ] || die "no b1051c-* modules found"
$ADB exec-out "tar -C /data/adb/modules -cf - $mods" > "$OUT/modules.tar"
$ADB exec-out "cat /data/adb/magisk.db" > "$OUT/magisk.db"
for m in $mods; do sh_ "cat /data/adb/modules/$m/module.prop" | grep -E '^(id|version|versionCode)=' | sed "s/^/$m: /"; done > "$OUT/modules.txt"

# 2. Our apps: APKs + app data (E-Ink Control keeps everything in shared_prefs)
for pkg in org.b1051c.einkctl org.b1051c.einkui; do
  apk=$(sh_ "pm path $pkg" | sed -n 's/^package://p' | head -1)
  [ -n "$apk" ] || { echo "not installed: $pkg"; continue; }
  $ADB exec-out "cat $apk" > "$OUT/$pkg.apk"
  sh_ "dumpsys package $pkg" | grep -m2 -E 'versionCode|versionName' | sed "s/^ */$pkg: /" >> "$OUT/apps.txt"
  sh_ "[ -d /data/data/$pkg/shared_prefs ]" && $ADB exec-out "tar -C /data/data/$pkg -cf - shared_prefs" > "$OUT/$pkg.prefs.tar"
done

# 2b. E-Ink Control settings + per-app profiles as one JSON (restore: copy to /data/data/org.b1051c.einkctl/files/
#     einkctl-import.json, chown to the app uid, restorecon, then am broadcast ... -a org.b1051c.einkctl.IMPORT)
sh_ 'am broadcast -n org.b1051c.einkctl/.ActionReceiver -a org.b1051c.einkctl.EXPORT >/dev/null; sleep 2'
$ADB exec-out "cat /sdcard/Android/data/org.b1051c.einkctl/files/einkctl-settings.json" > "$OUT/einkctl-settings.json" || true
# Home screen: Lawnchair (default launcher since 2026-10-18) layout + settings, and the private icon pack APK
if sh_ '[ -d /data/data/app.lawnchair ] && echo y' | grep -q y; then
  $ADB exec-out "tar -C /data/data/app.lawnchair -cf - shared_prefs files databases" > "$OUT/lawnchair-data.tar"
  apk=$(sh_ "pm path app.lawnchair" | sed -n 's/^package://p' | head -1); [ -n "$apk" ] && $ADB exec-out "cat $apk" > "$OUT/app.lawnchair.apk"
  apk=$(sh_ "pm path org.b1051c.iconpack" | sed -n 's/^package://p' | head -1); [ -n "$apk" ] && $ADB exec-out "cat $apk" > "$OUT/org.b1051c.iconpack.apk"
fi

# 3. Android state we changed (density, animations, tiles, webview, a11y, b1051c_* keys …)
for ns in secure system global; do sh_ "settings list $ns" > "$OUT/settings-$ns.txt"; done
sh_ 'wm density; wm size; getprop | grep -E "vendor\.xrz\.|vendor\.debug\.hwc\.shim|persist\.sys\.phh|ro\.sf\.lcd_density"' > "$OUT/display-props.txt"
cp "$RES/phase-c/settings-changes.log" "$OUT/" 2>/dev/null || true

# 4. KOReader (optional): global settings dir + *.sdr sidecars next to the books
if sh_ '[ -d /sdcard/koreader ] && echo y' | grep -q y; then
  $ADB exec-out "tar -C /sdcard -cf - koreader" > "$OUT/koreader-global.tar"
  sdrs=$(sh_ 'cd /sdcard && find . -name "*.sdr" -type d 2>/dev/null' | tr '\n' ' ')
  [ -n "$sdrs" ] && $ADB exec-out "cd /sdcard && tar -cf - $sdrs" > "$OUT/koreader-sidecars.tar"
else
  echo "KOReader not installed yet — skipped" | tee "$OUT/koreader-SKIPPED.txt"
fi

(cd "$OUT" && find . -type f ! -name SHA256SUMS | sort | xargs shasum -a 256 > SHA256SUMS)
for t in "$OUT"/*.tar; do tar -tf "$t" >/dev/null || die "corrupt archive: $t"; done
say "done: $(ls "$OUT" | wc -l | tr -d ' ') files, $(du -sh "$OUT" | cut -f1)"; cat "$OUT/modules.txt" "$OUT/apps.txt"
