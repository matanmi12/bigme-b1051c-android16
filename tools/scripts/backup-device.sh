#!/bin/bash
# Read-only dump of the partitions an InKognito install, update or stock restore touches, taken from the running
# tablet through a root shell (no reboot, no mtkclient). userdata is left out: a live raw copy of an encrypted,
# mounted fs is useless, and backup-layer.sh covers what lives there. Private: never stage backups/.
# Usage: backup-device.sh [--with-layer]   -> $BIGME/backups/device-YYYYMMDD-HHMMSS/ + SHA256SUMS
source "$(dirname "$0")/common.sh"
set -o pipefail
PARTS="boot_a boot_b vbmeta_a vbmeta_b dtbo_a dtbo_b logo misc metadata expdb super"
OUT="$BIGME/backups/device-$(date +%Y%m%d-%H%M%S)"; mkdir -p "$OUT"
say "device backup -> $OUT"
$ADB get-state >/dev/null 2>&1 || die "no adb device"
[ "$($ADB shell su -c id -u | tr -d '\r')" = 0 ] || die "no root shell (su)"

for p in $PARTS; do
  want=$($ADB shell su -c "blockdev --getsize64 /dev/block/by-name/$p" | tr -d '\r')
  [ -n "$want" ] || die "no partition $p"
  $ADB exec-out "su -c 'cat /dev/block/by-name/$p'" > "$OUT/$p.bin"
  got=$(stat -f %z "$OUT/$p.bin")
  [ "$got" = "$want" ] || die "$p: read $got bytes, partition is $want"
  # second read of the device side only, to catch a torn copy (super/metadata/expdb can change while running)
  dev=$($ADB shell su -c "sha256sum /dev/block/by-name/$p" | cut -d' ' -f1)
  loc=$(sha "$OUT/$p.bin")
  if [ "$dev" = "$loc" ]; then echo "$p: $got bytes, sha256 $loc"
  else echo "$p: $got bytes, sha256 $loc (device now $dev: changed while running)" | tee -a "$OUT/LIVE-CHANGES.txt"; fi
done

{ date; $ADB shell getprop ro.boot.slot_suffix; $ADB shell getprop ro.build.fingerprint
  $ADB shell getprop ro.vendor.build.version.incremental; } | tr -d '\r' > "$OUT/device-state.txt"
"$PY" -I "$BIGME/tools/scripts/parse_misc.py" "$OUT/misc.bin" > "$OUT/misc-decoded.txt"
for s in 0 1; do "$PY" -I "$BIGME/tools/scripts/lp_extents.py" "$OUT/super.bin" $s > "$OUT/super-slot$s.txt" || true; done
(cd "$OUT" && find . -type f ! -name SHA256SUMS | sort | xargs shasum -a 256 > SHA256SUMS)
[ "${1:-}" = --with-layer ] && "$BIGME/tools/scripts/backup-layer.sh"
say "done: $(du -sh "$OUT" | cut -f1)"; cat "$OUT/misc-decoded.txt"
