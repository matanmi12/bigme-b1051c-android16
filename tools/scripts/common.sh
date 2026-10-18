#!/bin/bash
# Shared helpers. Every device WRITE goes through confirm_write() or confirm_phrase(), which print the exact
# operation and require the operator to type the confirmation. Nothing in these scripts writes without it.
BIGME="${BIGME:-$HOME/Desktop/Bigme}"
DUMP="$BIGME/BIGME_B1051C_system_dump"; WORK="$BIGME/work"; RES="$BIGME/research"; PHC="$WORK/phase-c"
ADB="${ADB:-adb}"; FB="${FB:-fastboot}"
PY="${PY:-/opt/homebrew/bin/python3.12}"
MTKDIR="$WORK/ref/mtkclient"; MTKPY="$BIGME/tools/venv/bin/python"
CHECK="$BIGME/tools/scripts/phasec_check.py"
say() { printf '\n\033[1m%s\033[0m\n' "$*"; }
die() { printf '\n\033[31mSTOP: %s\033[0m\n' "$*" >&2; exit 1; }
confirm_write() {  # confirm_write "<description>" <cmd...>
  local desc="$1"; shift
  say "WRITE OPERATION: $desc"; printf '  command: %q ' "$@"; echo
  read -r -p "Type YES to execute this single write, anything else to abort: " ans
  [ "$ans" = "YES" ] || die "not confirmed"
  "$@"
}
confirm_phrase() { # confirm_phrase "<exact phrase>" "<summary line>"...   (does not run anything itself)
  local phrase="$1"; shift
  say "WRITE REQUEST — read every line"; printf '  %s\n' "$@"
  read -r -p "Type exactly '$phrase' to execute, anything else aborts: " ans
  [ "$ans" = "$phrase" ] || die "not confirmed — nothing written"
}
sha() { [ -s "$1" ] || die "missing/empty file: $1"; shasum -a 256 "$1" | cut -d' ' -f1; }
need_hash() { # need_hash <file> <expected-sha256>
  local got; got=$(sha "$1"); [ "$got" = "$2" ] || die "hash mismatch for $1: $got != $2"; echo "hash OK: $(basename "$1")"
}
check() { "$PY" -I "$CHECK" "$@" || die "pre-flight/readback check failed: $*"; }
run_dir() { RUN="$RES/phase-c/$(date +%Y%m%d-%H%M%S)-$1"; mkdir -p "$RUN"; echo "logs: $RUN"; }
# mtkclient runner. MTK_EXTRA may carry e.g. "--preloader $DUMP/preloader_boot1.bin" for BROM mode.
mtk() {
  local log=/dev/null; [ -n "${RUN:-}" ] && log="$RUN/mtk.log"
  echo "+ mtk $*" >> "$log"
  ( cd "$MTKDIR" && "$MTKPY" mtk.py "$@" ${MTK_EXTRA:-} ) 2>&1 | tee -a "$log"; return "${PIPESTATUS[0]}"
}
mtk_session() { # decide whether to reuse a live DA session (.state) or start fresh
  if [ -f "$MTKDIR/.state" ]; then
    read -r -p "A DA session file exists. Is the device still in that mtkclient session (not rebooted since)? [y/N] " a
    if [ "$a" != "y" ]; then mv "$MTKDIR/.state" "$RES/mtk_stale_state_$(date +%H%M%S).bak"; echo "stale .state moved aside"; fi
  fi
  [ -f "$MTKDIR/.state" ] || say "Catch the device: start now; then reboot it (adb reboot, or hold power ~10-12 s, or press power if off) with USB connected. mtkclient grabs the preloader."
}
