#!/bin/bash
# Phase C — permanent TrebleDroid A16 on slot B.  REVISED 2026-10-17 (evening); procedure: docs/PHASE-C-RUNBOOK.md
#
# What changed vs. the first version (which must not be used):
#  * No fastboot and NO fastbootd. `fastboot reboot fastboot` booted recovery on slot A and rewrote bootctrl
#    (Phase A). Every write now goes through mtkclient (DA) with a pre-read assertion and an exact readback.
#  * vbmeta_b: only the HASHTREE_DISABLED flag (1 byte), and stock must be booted and checked before the GSI.
#  * system_b: the GSI is written into system_b's existing extent 0 (super+0x23d00000); LP metadata untouched.
#    The target range is first read back and must equal the stock system_b bytes (proves the mapping).
#  * Data reset by writing zeros (never mtkclient e/es/ess: their result check is inverted locally).
#  * boot: only a Magisk image patched from the CURRENT stock boot_b (kernel+DTB must be byte-identical).
#  * Nothing reboots unless you run `reset`. Every boot hits LK's hidden dm-verity warning: do the
#    power-press routine (runbook §0) or LK powers off after 5 s, which is NOT a GSI failure.
#
# Usage: flash-gsi.sh <plan|adb-check [stock|vbmeta|gsi]|mtk-check|vbmeta|system|wipe-data|boot <img>|reset>
source "$(dirname "$0")/common.sh"
set -o pipefail
GSI="$WORK/gsi/system-td-arm64-vanilla.img";       GSI_SHA=a0704a7909328cc077f314f25ed47c5ce02b4a095cc932dd36060e4a90d333a8
VB_STOCK="$DUMP/vbmeta_b.bin";                     VB_STOCK_SHA=578cdaaa76f08157f118b306c96041f27cddc06676f2c3c9ba42aa4187e303c2
VB_NOHT="$PHC/vbmeta_b.hashtree-disabled.bin";     VB_NOHT_SHA=12e576024eb3a59570bf034fd5f35316403752df17f884fefc2d7dcde3f8adf1
SYS_SLICE="$PHC/system_b.stock-head-0x83cf5000.img"; SYS_SLICE_SHA=d2e0fe994300ba5ad15e943bfa0ed372b49754e52785eedacf772062f6e33dc2
BOOT_STOCK="$DUMP/boot_b.bin";                     BOOT_STOCK_SHA=dd26e52466aedf7d93546b07741673af1ecac1d1b441f251e81d6878c00405e0
Z_META="$PHC/zeros-metadata-39780352.bin";         Z_UD="$PHC/zeros-userdata-head-64MiB.bin"
SYS_ABS=0x72d00000; SYS_LEN=0x83cf5000             # super 0x4f000000 + system_b extent0 0x23d00000; GSI = 2211401728 B
SUPER_ABS=0x4f000000; UD_ABS=0x34f000000; UD_HEAD=0x4000000
DIGEST_STOCK=e9211d4a1da0847afc55b59d83b18a6aa084efd1d2900eea9bf7ed8e0ab29671   # predicted ro.boot.vbmeta.digest, stock
DIGEST_NOHT=5a084f7abaac13cc6de1d08d05e00f9ededbd76b0f981bfcd82495cc63eedad7    # predicted, after WRITE 1 (stock boot_b)

pre_misc() { mtk r misc "$RUN/misc.pre.bin" >/dev/null; check misc "$RUN/misc.pre.bin"; }
# mtkclient's getint() turns an unparsable offset into 0 (= GPT). Assert every constant arithmetically.
[ $((SYS_ABS)) -eq $((0x4f000000 + 0x23d00000)) ] && [ $((SYS_LEN)) -eq 2211401728 ] && [ $((SYS_LEN)) -le $((0x12d780000)) ] \
  && [ $((UD_ABS)) -eq $((0x34f000000)) ] && [ $((UD_HEAD)) -eq 67108864 ] && [ $((SUPER_ABS)) -eq $((0x4f000000)) ] \
  || die "internal offset constants inconsistent — do not run"

case "${1:-plan}" in
plan)
  cat <<EOF
Phase C order (each WRITE asks for its own phrase; read docs/PHASE-C-RUNBOOK.md first):
  0. adb-check stock     read-only, stock Android (USB debugging on)      expect veritymode=eio, digest ${DIGEST_STOCK:0:12}…
  1. mtk-check           read-only DA session: GPT, misc, vbmeta_b, boot_b, super LP metadata, seccfg, expdb
  2. vbmeta              WRITE 1  vbmeta_b <- flags=1 copy (1 byte)  ->  reset  ->  press routine  ->  adb-check vbmeta
  3. system              WRITE 2  GSI -> system_b extent 0 (super+0x23d00000, $SYS_LEN bytes)
  4. wipe-data           WRITE 3  zero metadata + userdata head        ->  reset  ->  press routine  ->  adb-check gsi
  5. boot <img>          WRITE 4  (later) Magisk-patched CURRENT stock boot_b  ->  reset -> adb-check gsi
Rollback at any point: tools/scripts/restore-stock.sh (same DA mechanism).
EOF
  ;;

adb-check)  # read-only; never writes
  mode="${2:-stock}"; run_dir "adb-$mode"
  $ADB get-state >/dev/null 2>&1 || die "no adb device (stock: enable + authorize USB debugging; GSI: adb is open)"
  for p in ro.boot.slot_suffix sys.boot_completed ro.boot.veritymode ro.boot.veritymode.managed ro.boot.verifiedbootstate \
           ro.boot.vbmeta.digest ro.boot.vbmeta.device_state ro.build.fingerprint ro.build.type ro.debuggable \
           ro.gsid.image_running ro.boot.dynamic_partitions partition.system.verified partition.vendor.verified \
           partition.product.verified partition.system_ext.verified persist.sys.phh.enable_sf_gl_backpressure; do
    printf '%-42s %s\n' "$p" "$($ADB shell getprop "$p" | tr -d '\r')"; done | tee "$RUN/props.txt"
  $ADB shell cat /proc/version | tee "$RUN/proc_version.txt"
  $ADB shell 'cat /proc/uptime; getenforce; id; command -v su; command -v magisk && magisk -v' 2>&1 | tee "$RUN/misc.txt"
  $ADB shell getprop > "$RUN/getprop_full.txt"
  $ADB shell 'mount | grep -E " /(system|vendor|product|system_ext|metadata|data) "' > "$RUN/mounts.txt" 2>&1
  $ADB logcat -b all -d > "$RUN/logcat_all.txt" 2>&1
  $ADB shell dmesg > "$RUN/dmesg.txt" 2>&1 || true
  $ADB shell dumpsys SurfaceFlinger > "$RUN/dumpsys_sf.txt" 2>&1 || true
  $ADB shell dumpsys display > "$RUN/dumpsys_display.txt" 2>&1 || true
  grep -h "avc: denied" "$RUN/logcat_all.txt" "$RUN/dmesg.txt" 2>/dev/null | sort | uniq -c | sort -rn > "$RUN/avc.txt"
  g() { grep -E "^$1 " "$RUN/props.txt" | awk '{print $2}'; }
  expect() { if [ "$2" = "$3" ]; then echo "  PASS $1 = $2"; else echo "  FAIL $1 = '$2' (expected '$3')"; fi; }
  say "Assertions ($mode)"
  { expect slot_suffix "$(g ro.boot.slot_suffix)" _b; expect boot_completed "$(g sys.boot_completed)" 1
    case "$mode" in
      stock)  expect veritymode "$(g ro.boot.veritymode)" eio; expect vbmeta.digest "$(g ro.boot.vbmeta.digest)" $DIGEST_STOCK;;
      vbmeta) expect veritymode "$(g ro.boot.veritymode)" disabled; expect vbmeta.digest "$(g ro.boot.vbmeta.digest)" $DIGEST_NOHT
              expect partition.system.verified "$(g partition.system.verified)" "";;
      gsi)    expect veritymode "$(g ro.boot.veritymode)" disabled; expect build.type "$(g ro.build.type)" userdebug
              grep -q tdgsi "$RUN/props.txt" && echo "  PASS fingerprint is TrebleDroid" || echo "  FAIL fingerprint is not TrebleDroid";;
    esac; } | tee "$RUN/assertions.txt"
  echo "avc denials (top): $(head -3 "$RUN/avc.txt" | tr -s ' ' | tr '\n' '|')"
  ;;

mtk-check)  # read-only DA session; leaves the DA connected for a following write (no reset)
  run_dir mtk-check; mtk_session
  mtk printgpt > "$RUN/printgpt.txt"; check gpt "$RUN/printgpt.txt"
  pre_misc
  mtk r vbmeta_b,vbmeta_system_b,vbmeta_vendor_b,boot_b,seccfg,expdb \
      "$RUN/vbmeta_b.bin,$RUN/vbmeta_system_b.bin,$RUN/vbmeta_vendor_b.bin,$RUN/boot_b.bin,$RUN/seccfg.bin,$RUN/expdb.bin" >/dev/null
  mtk ro $SUPER_ABS 0x100000 "$RUN/super_head.bin" >/dev/null; check super "$RUN/super_head.bin"
  for f in vbmeta_b vbmeta_system_b vbmeta_vendor_b boot_b; do printf '%-16s %s\n' $f "$(sha "$RUN/$f.bin")"; done | tee "$RUN/hashes.txt"
  vb=$(sha "$RUN/vbmeta_b.bin"); bt=$(sha "$RUN/boot_b.bin")
  [ "$vb" = $VB_STOCK_SHA ] && echo "vbmeta_b: STOCK"; [ "$vb" = $VB_NOHT_SHA ] && echo "vbmeta_b: HASHTREE_DISABLED (write 1 done)"
  [ "$bt" = $BOOT_STOCK_SHA ] && echo "boot_b: official stock (Mar-2026 kernel), no Magisk"
  cmp -s "$RUN/vbmeta_system_b.bin" "$DUMP/vbmeta_system_b.bin" && cmp -s "$RUN/vbmeta_vendor_b.bin" "$DUMP/vbmeta_vendor_b.bin" \
    && echo "vbmeta_system_b / vbmeta_vendor_b: identical to backup" || echo "WARNING: vbmeta_system_b/vendor_b differ from backup"
  cmp -s "$RUN/seccfg.bin" "$DUMP/seccfg.bin" && echo "seccfg: identical to May backup" || echo "seccfg: differs from May backup (expected: LK's dm-verity state) — evidence only, NEVER written by us"
  "$PY" -I -c "
import struct,sys
s=open(sys.argv[1],'rb').read(); m=open(sys.argv[2],'rb').read()
print('seccfg: lock_state=%d dmverity_state=%d' % struct.unpack_from('<II',s,0xc))
i=s.find(b'avb.managed_verity_mode'); print('seccfg AVB managed_verity_mode digest:', s[i+23:i+55].hex() if i>0 else 'absent')
print('misc+0x1000 (LK OTA-result word): %#x' % struct.unpack_from('<I',m,0x1000))" "$RUN/seccfg.bin" "$RUN/misc.pre.bin"
  strings -a "$RUN/expdb.bin" | grep -cE "mt_power_off new" | sed 's/^/expdb sessions ending in LK power-off: /'
  strings -a "$RUN/expdb.bin" | grep -cE "booting linux|jump to linux" | sed 's/^/expdb kernel handoffs (this LK does not store boots that reach the kernel — 0 proves nothing): /'
  say "DA still connected. Next: a write subcommand, or 'reset'."
  ;;

vbmeta)  # WRITE 1
  run_dir vbmeta; need_hash "$VB_NOHT" $VB_NOHT_SHA; need_hash "$VB_STOCK" $VB_STOCK_SHA; mtk_session
  pre_misc
  mtk r vbmeta_b "$RUN/vbmeta_b.pre.bin" >/dev/null; pre=$(sha "$RUN/vbmeta_b.pre.bin")
  [ "$pre" = $VB_NOHT_SHA ] && { say "vbmeta_b already HASHTREE_DISABLED — nothing to do"; exit 0; }
  [ "$pre" = $VB_STOCK_SHA ] || die "live vbmeta_b is neither stock nor the prepared copy ($pre)"
  confirm_phrase "WRITE vbmeta_b" \
    "Operation: mtkclient w vbmeta_b $VB_NOHT  (+ immediate readback)" \
    "Change   : AVB header flags 0 -> 1 (HASHTREE_DISABLED); exactly 1 byte (offset 0x7b) differs from stock" \
    "Partition: vbmeta_b only (8 MiB, physical, abs 0xa208000). misc/boot/seccfg/lk untouched." \
    "Risk     : LK refuses or misparses -> no boot. Signature becomes invalid (allowed while unlocked, like fastboot --disable-verity)." \
    "Rollback : restore-stock.sh vbmeta  (writes $VB_STOCK, sha ${VB_STOCK_SHA:0:12}…)" \
    "Verify   : readback sha == ${VB_NOHT_SHA:0:12}…; then stock boot: veritymode=disabled, digest ${DIGEST_NOHT:0:12}…"
  mtk multi "w vbmeta_b $VB_NOHT;r vbmeta_b $RUN/vbmeta_b.post.bin"
  post=$(sha "$RUN/vbmeta_b.post.bin")
  if [ "$post" = $VB_NOHT_SHA ]; then say "READBACK OK: vbmeta_b == prepared copy. Next: flash-gsi.sh reset -> press routine -> flash-gsi.sh adb-check vbmeta"
  else say "READBACK MISMATCH ($post). DA still connected — run: tools/scripts/restore-stock.sh vbmeta"; exit 1; fi
  ;;

system)  # WRITE 2
  run_dir system; need_hash "$GSI" $GSI_SHA; need_hash "$SYS_SLICE" $SYS_SLICE_SHA; mtk_session
  pre_misc
  mtk r vbmeta_b "$RUN/vbmeta_b.bin" >/dev/null
  [ "$(sha "$RUN/vbmeta_b.bin")" = $VB_NOHT_SHA ] || die "vbmeta_b is not HASHTREE_DISABLED — do WRITE 1 and the stock boot check first"
  mtk ro $SUPER_ABS 0x100000 "$RUN/super_head.bin" >/dev/null; check super "$RUN/super_head.bin"
  say "Reading the target range (2.2 GB, read-only) to prove the mapping..."
  mtk ro $SYS_ABS $SYS_LEN "$RUN/system_b.pre.img" >/dev/null; pre=$(sha "$RUN/system_b.pre.img")
  [ "$pre" = $GSI_SHA ] && { say "system_b extent 0 already holds the GSI — nothing to do"; exit 0; }
  [ "$pre" = $SYS_SLICE_SHA ] || die "target range is not stock system_b ($pre) — NOT writing; keep $RUN/system_b.pre.img"
  rm -f "$RUN/system_b.pre.img"; echo "pre-image == stock slice ${SYS_SLICE_SHA:0:12}… (rollback copy: $SYS_SLICE)"
  confirm_phrase "WRITE system_b" \
    "Operation: mtkclient wo $SYS_ABS $SYS_LEN $GSI  (+ readback of the same range)" \
    "Target   : super+0x23d00000 = system_b extent 0 (len 0x12d780000); GSI 2,211,401,728 B fits; LP metadata NOT modified" \
    "Untouched: vendor_b, product_b, system_ext_b (GSI skip_mount.cfg skips product/system_ext), boot_b, slot A data" \
    "Risk     : GSI fails to boot (untested here; the old DSU result is confounded by LK's hidden warning)." \
    "Rollback : restore-stock.sh system  (writes the verified stock slice back, sha ${SYS_SLICE_SHA:0:12}…)" \
    "Verify   : readback sha == ${GSI_SHA:0:12}…"
  mtk wo $SYS_ABS $SYS_LEN "$GSI"
  mtk ro $SYS_ABS $SYS_LEN "$RUN/system_b.post.img" >/dev/null; post=$(sha "$RUN/system_b.post.img"); rm -f "$RUN/system_b.post.img"
  if [ "$post" = $GSI_SHA ]; then say "READBACK OK: system_b extent 0 == GSI. Next: flash-gsi.sh wipe-data (same session)"
  else say "READBACK MISMATCH ($post). DA still connected — run: tools/scripts/restore-stock.sh system"; exit 1; fi
  ;;

wipe-data)  # WRITE 3
  run_dir wipe-data; mtk_session
  pre_misc
  mtk r metadata "$RUN/metadata.pre.bin" >/dev/null; mtk ro $UD_ABS $UD_HEAD "$RUN/userdata_head.pre.bin" >/dev/null
  chmod 600 "$RUN"/*.pre.bin; echo "pre-images kept (private, encrypted user data): $RUN"
  confirm_phrase "WIPE metadata userdata" \
    "Operation: mtkclient w metadata <zeros 39,780,352 B>  and  wo $UD_ABS $UD_HEAD <zeros 64 MiB>" \
    "Effect   : /metadata (FBE metadata-encryption key dir, OTA/DSU state) and the userdata superblocks erased;" \
    "           first boot re-formats both (fs_mgr 'formattable'). ALL current user data on the tablet is lost." \
    "Backups  : settings-backup-2026-10-17 (app data, KOReader); media/books are NOT backed up." \
    "Risk     : none to partitions outside metadata/userdata. Not reversible (data is gone)." \
    "Verify   : both ranges read back all-zero"
  mtk multi "w metadata $Z_META;wo $UD_ABS $UD_HEAD $Z_UD"
  mtk r metadata "$RUN/metadata.post.bin" >/dev/null; mtk ro $UD_ABS $UD_HEAD "$RUN/userdata_head.post.bin" >/dev/null
  check zeros "$RUN/metadata.post.bin" 39780352; check zeros "$RUN/userdata_head.post.bin" 67108864
  rm -f "$RUN"/*.post.bin; say "Data reset verified. Next: flash-gsi.sh reset -> press routine -> wait (first boot formats /data) -> adb-check gsi"
  ;;

boot)  # WRITE 4 (only after the GSI baseline is recorded)
  img="${2:?usage: flash-gsi.sh boot <magisk_patched.img>}"; [ -s "$img" ] || die "no such image: $img"
  img="$(cd "$(dirname "$img")" && pwd)/$(basename "$img")"   # mtkclient runs from its own dir: absolute path required
  run_dir boot; need_hash "$BOOT_STOCK" $BOOT_STOCK_SHA
  check boot "$img" "$BOOT_STOCK"; cand=$(sha "$img"); csize=$(stat -f%z "$img")
  [ "$csize" -eq $((0x2800000)) ] || die "image is $csize B, not the full 40 MiB partition — stop and inspect"
  mtk_session; pre_misc
  mtk r boot_b "$RUN/boot_b.pre.bin" >/dev/null; pre=$(sha "$RUN/boot_b.pre.bin")
  [ "$pre" = "$cand" ] && { say "boot_b already holds this image"; exit 0; }
  [ "$pre" = $BOOT_STOCK_SHA ] || die "live boot_b is not the official stock image ($pre)"
  confirm_phrase "WRITE boot_b" \
    "Operation: mtkclient w boot_b $img  (+ readback)   sha ${cand:0:12}…" \
    "Checked  : header v2; kernel + DTB byte-identical to current stock boot_b (Mar-2026 kernel)" \
    "Risk     : bad ramdisk patch -> no boot (LK + vbmeta unaffected)." \
    "Rollback : restore-stock.sh boot  (official stock boot_b, sha ${BOOT_STOCK_SHA:0:12}…)" \
    "Verify   : readback sha == candidate; after boot: magisk -v, su -c id"
  mtk multi "w boot_b $img;r boot_b $RUN/boot_b.post.bin"
  [ "$(sha "$RUN/boot_b.post.bin")" = "$cand" ] && say "READBACK OK: boot_b == candidate. Next: reset -> press routine -> adb-check gsi" \
    || { say "READBACK MISMATCH. DA still connected — run: tools/scripts/restore-stock.sh boot"; exit 1; }
  ;;

warning-fix)  # WRITE 5: let LK clear its sticky dm-verity state via its own post-OTA path (research note §4)
  # Built from the LIVE misc (Android 16 legitimately updates bootctrl tries and its misc_kcmdline/control messages),
  # changing only the 32-bit word at 0x1000. Rollback = the live pre-image saved in the run folder.
  run_dir warning-fix; mtk_session
  pre_misc
  FLAG="$RUN/misc.ota-flag.bin"
  "$PY" -I -c "
import sys
b=bytearray(open(sys.argv[1],'rb').read()); assert len(b)==0x80000
if b[0x1000:0x1004]!=bytes(4): sys.exit('misc+0x1000 already non-zero')
b[0x1000:0x1004]=(1).to_bytes(4,'little'); open(sys.argv[2],'wb').write(b)
o=open(sys.argv[1],'rb').read(); d=[i for i in range(len(o)) if o[i]!=b[i]]
assert d==[0x1000], d; print('flag image differs from live misc only at', [hex(i) for i in d])" "$RUN/misc.pre.bin" "$FLAG" \
    || die "could not build the flag image (see above) — nothing written"
  check misc "$FLAG"; pre=$(sha "$RUN/misc.pre.bin"); new=$(sha "$FLAG")
  confirm_phrase "WRITE misc ota-flag" \
    "Operation: mtkclient w misc $FLAG  (+ readback)" \
    "Change   : 4 bytes at misc+0x1000: 0 -> 1 (LK check_ota_result 'ota-pass'); everything else = live misc ${pre:0:12}…" \
    "Effect   : next boot LK runs clear_dm_verity_status() — LK itself rewrites seccfg (dm-verity state, AVB digest) — and clears the word" \
    "Risk     : an LK seccfg write that went wrong could affect the lock state" \
    "Rollback : mtkclient w misc $RUN/misc.pre.bin (live pre-image) or restore-stock.sh misc; seccfg emergency = separate approval" \
    "Verify   : readback ${new:0:12}…; next session misc+0x1000=0, seccfg dmverity_state=0, lock_state=3"
  mtk multi "w misc $FLAG;r misc $RUN/misc.post.bin"
  [ "$(sha "$RUN/misc.post.bin")" = "$new" ] && say "READBACK OK: misc carries the OTA-pass flag. Next: reset -> press routine (last time) -> GSI" \
    || { say "READBACK MISMATCH. DA still connected — rollback: mtk w misc $RUN/misc.pre.bin"; exit 1; }
  ;;

reset)
  run_dir reset; mtk reset
  say "Device leaves DA and boots slot B. LK's hidden dm-verity warning WILL appear (invisible on e-ink)." \
      "When the screen flashes, press power briefly about once per second for ~10 s, then wait." \
      "If it powers off instead: power on 3-5 s, release, then the same short presses (runbook §0)."
  ;;
*) die "unknown subcommand: $1 (see header)";;
esac
