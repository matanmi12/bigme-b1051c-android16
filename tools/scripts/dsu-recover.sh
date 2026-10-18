#!/bin/bash
# Recover from a DSU boot loop: read metadata via mtkclient (preloader), set /gsi/dsu/install_status=disabled offline
# (exactly what `gsi_tool disable` writes), prove /vold (FBE keys) unchanged, then write back (requires typing YES).
# After the write: unplug USB, hold power 30s, wait 60-90s, cold boot.
set -euo pipefail
source "$(dirname "$0")/common.sh"
E2=/opt/homebrew/opt/e2fsprogs/sbin; MTK="$BIGME/tools/venv/bin/python $WORK/ref/mtkclient/mtk.py"
TS=$(date +%Y%m%d-%H%M%S); OUT="$RES/dsu-recover-$TS"; mkdir -p "$OUT"
say "STEP 1/4: read metadata (read-only). Now: hold power ~12s, release, press once."
( cd "$WORK/ref/mtkclient" && $MTK r metadata "$OUT/metadata_dump.bin" ) 2>&1 | grep -E "Dumped|Error|error" || true
[ -s "$OUT/metadata_dump.bin" ] || die "metadata dump failed"
cp "$OUT/metadata_dump.bin" "$OUT/metadata_fixed.bin"
say "STEP 2/4: offline edit (journal replay + install_status=disabled)"
"$E2/e2fsck" -fy "$OUT/metadata_fixed.bin" >/dev/null 2>&1 || true
CUR=$("$E2/debugfs" -R "cat /gsi/dsu/install_status" "$OUT/metadata_fixed.bin" 2>/dev/null || true)
echo "  current install_status: [$CUR]"
mkdir -p "$OUT/vb" "$OUT/va"
"$E2/debugfs" -R "rdump /vold $OUT/vb" "$OUT/metadata_fixed.bin" >/dev/null 2>&1
printf 'disabled' > "$OUT/is"; printf 'u:object_r:gsi_metadata_file:s0\0' > "$OUT/ctx"
"$E2/debugfs" -w -R "rm /gsi/dsu/install_status" "$OUT/metadata_fixed.bin" >/dev/null 2>&1 || true
"$E2/debugfs" -w -R "write $OUT/is /gsi/dsu/install_status" "$OUT/metadata_fixed.bin" >/dev/null 2>&1
for c in "sif /gsi/dsu/install_status mode 0100644" "sif /gsi/dsu/install_status uid 0" "sif /gsi/dsu/install_status gid 0"; do "$E2/debugfs" -w -R "$c" "$OUT/metadata_fixed.bin" >/dev/null 2>&1; done
"$E2/debugfs" -w -R "ea_set -f $OUT/ctx /gsi/dsu/install_status security.selinux" "$OUT/metadata_fixed.bin" >/dev/null 2>&1
say "STEP 3/4: verify"
"$E2/e2fsck" -fn "$OUT/metadata_fixed.bin" >/dev/null 2>&1 && echo "  fsck CLEAN" || die "fsck not clean"
NEW=$("$E2/debugfs" -R "cat /gsi/dsu/install_status" "$OUT/metadata_fixed.bin" 2>/dev/null); [ "$NEW" = "disabled" ] || die "edit failed"
"$E2/debugfs" -R "ea_get /gsi/dsu/install_status security.selinux" "$OUT/metadata_fixed.bin" 2>/dev/null | grep -q "gsi_metadata_file" || die "label missing"
"$E2/debugfs" -R "rdump /vold $OUT/va" "$OUT/metadata_fixed.bin" >/dev/null 2>&1
diff -r "$OUT/vb" "$OUT/va" >/dev/null && echo "  /vold (encryption keys) IDENTICAL" || die "/vold changed"
shasum -a 256 "$OUT"/metadata_*.bin
say "STEP 4/4: write fixed metadata. Do the power dance again when prompted by mtkclient."
confirm_write "mtk w metadata (DSU disabled) + reset" bash -c "cd '$WORK/ref/mtkclient' && $MTK multi 'w metadata $OUT/metadata_fixed.bin;reset'"
say "Done. Unplug USB, hold power 30s, wait 60-90s, cold boot."
