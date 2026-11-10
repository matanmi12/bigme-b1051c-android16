#!/bin/bash
# Dry-run tests for flash-inkognito.sh with MOCK adb/fastboot first in PATH.
# PATH is reduced to the mocks plus system dirs, and the script aborts unless command -v finds the mocks.
HERE=$(cd "$(dirname "$0")" && pwd); WORK="${TEST_WORK:-${TMPDIR:-/tmp}/inkognito-flash-tests}"; mkdir -p "$WORK"
SRC=$(cd "$HERE/.." && pwd)
export PATH="$HERE/mockbin:/usr/bin:/bin:/usr/sbin:/sbin"
export MOCK_LOG="$WORK/mock-calls.log"
export MOCK_STATE="$WORK/mock-state"
a=$(command -v adb); f=$(command -v fastboot)
echo "command -v adb      -> $a"
echo "command -v fastboot -> $f"
[ "$a" = "$HERE/mockbin/adb" ] && [ "$f" = "$HERE/mockbin/fastboot" ] || { echo "MOCKS NOT FIRST IN PATH - ABORT"; exit 99; }

REL="$WORK/rel"
rm -rf "$REL" "$MOCK_STATE"; mkdir -p "$REL"
cp "$SRC/flash-inkognito.sh" "$REL/"
for n in vbmeta boot system logo; do head -c 4096 /dev/urandom > "$REL/$n.img"; done
( cd "$REL" && shasum -a 256 vbmeta.img boot.img system.img logo.img flash-inkognito.sh > SHA256SUMS )
: > "$MOCK_LOG"

pass=0; fail=0
t() { # t NAME EXPECTED_RC ARGS...
  local name="$1" want="$2"; shift 2
  local out rc
  out=$(cd /tmp && bash "$REL/flash-inkognito.sh" "$@" 2>&1 </dev/null); rc=$?
  printf '%s\n' "$out" > "$WORK/out-$name.txt"
  if [ "$rc" = "$want" ]; then echo "PASS $name (rc=$rc)"; pass=$((pass+1)); else echo "FAIL $name (rc=$rc, want $want)"; fail=$((fail+1)); fi
}
t help              0 --help
t dryrun-default    0 --dry-run
t dryrun-skipadb    0 --dry-run --skip-adb --slot b --no-logo
t dryrun-slot-eq    0 --dry-run --slot=a
t dryrun-strict     0 --dry-run --strict-slot --slot b
t bad-option        1 --dry-run --frobnicate
t bad-slot          1 --dry-run --slot c
cp "$REL/logo.img" "$WORK/logo.bak"; rm "$REL/logo.img"
t missing-logo      1 --dry-run
t missing-logo-ok   0 --dry-run --no-logo
cp "$WORK/logo.bak" "$REL/logo.img"
printf 'x' >> "$REL/boot.img"
t corrupt-boot      1 --dry-run
grep -q MISMATCH "$WORK/out-corrupt-boot.txt" && echo "  corrupt-boot output says MISMATCH" || { echo "  corrupt-boot: no MISMATCH line"; fail=$((fail+1)); }
mv "$REL/SHA256SUMS" "$REL/SHA256SUMS.off"
t no-sums           1 --dry-run
mv "$REL/SHA256SUMS.off" "$REL/SHA256SUMS"
grep -q -- "--strict-slot" "$WORK/out-help.txt" && echo "  help lists --strict-slot" || { echo "  help lacks --strict-slot"; fail=$((fail+1)); }
echo "mock calls logged during all dry runs: $(wc -l < "$MOCK_LOG" | tr -d ' ')"
[ -s "$MOCK_LOG" ] && { echo "FAIL: adb/fastboot were called:"; cat "$MOCK_LOG"; fail=$((fail+1)); }
echo "passed=$pass failed=$fail"
