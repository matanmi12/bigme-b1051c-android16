#!/usr/bin/env bash
# flash-inkognito.sh - installs InKognito alpha 2 on a Bigme B1051C Pro (macOS and Linux).
#
# Run it from the unzipped release folder:
#     bash flash-inkognito.sh              normal install (tablet booted into Android, USB debugging on)
#     bash flash-inkognito.sh --dry-run    print every step and command, touch nothing
#     bash flash-inkognito.sh --help       all options
#
# What it writes, and nothing else: vbmeta_<slot>, boot_<slot>, logo (optional) in bootloader mode,
# then system_<slot> in fastbootd, then it wipes user data (fastboot -w) and sets <slot> active.
# Read INSTALL.md first. Licensed MIT, like the rest of InKognito's own code.

if [ -z "${BASH_VERSION:-}" ]; then
  echo "Please run this script with bash:  bash flash-inkognito.sh"
  exit 1
fi

set -u
set -o pipefail

RELEASE_NAME="InKognito-B1051CPro-alpha2-20261027"

# ---- device facts (Bigme B1051C Pro, firmware B1051C_R_4.9.0) ----
EXPECT_FW="B1051C_R_4.9.0"            # ro.build.versionName on stock Android
EXPECT_VENDOR_INC="mp1V15125"         # ro.vendor.build.version.incremental on any ROM over fw 4.9.0
EXPECT_WM="1860x2480"                 # wm size, physical
MIN_BATTERY=50
EXPECT_PRODUCT="k6877v1_64_k419"      # LK fastboot getvar product
EXPECT_SUPER_SIZE="300000000"         # 0x300000000, compared after normalizing
EXPECT_BOOT_SIZE="2800000"            # 0x2800000
MIN_FASTBOOT_MAJOR=31
UNLOCK_GUIDE="https://vbh.ai/unlocking-the-bootloader-and-rooting-the-hibreak-pro/"
WAIT_BOOTLOADER_SECS=120
WAIT_FASTBOOTD_SECS=180

# ---- options ----
DRY_RUN=0
SKIP_ADB=0
FLASH_LOGO=1
WANT_SLOT=""
STRICT_SLOT=0

# ---- state ----
ADB=""
FASTBOOT=""
SLOT=""
STAGE="local"
GV=""
INFO_FW="(not checked)"
INFO_BATTERY="(not checked)"

say()  { printf '%s\n' "$*"; }
step() { printf '\n==> %s\n' "$*"; }
note() { printf '    %s\n' "$*"; }
warn() { printf '\n!!  %s\n' "$*" >&2; }

usage() {
  cat <<EOF
InKognito installer for the Bigme B1051C Pro ($RELEASE_NAME)

Usage: bash flash-inkognito.sh [options]

  --dry-run     Show every check and command in order. Does not run adb or fastboot at all.
                (The local SHA-256 check of the image files still runs.)
  --skip-adb    The tablet is already in bootloader mode (fastboot). Skips the Android-side checks
                (firmware version, screen size, battery). Only use this on a B1051C Pro on firmware
                $EXPECT_FW with at least $MIN_BATTERY% battery.
  --slot a|b    Optional safety check: stop unless the tablet's active slot is this one.
                Use it with --skip-adb when you re-run after an interrupted install.
                It never changes which slot is written; that is always the active slot.
  --no-logo     Do not flash the InKognito boot logo (logo.img). Everything else is the same.
  -h, --help    Show this help.

  --strict-slot Stop before writing system if fastbootd reports a different slot than the
                bootloader. By default the script continues: on the developer's tablet fastbootd
                came up on slot a although b was active, and the install handles that by writing
                system_<slot> with an explicit slot letter and setting <slot> active at the end.

Full instructions: INSTALL.md. Problems: INSTALL.md, section "Troubleshooting".
EOF
}

# ---------------------------------------------------------------------------
# Pure helpers (no adb/fastboot calls). Kept separate so they can be tested.
# ---------------------------------------------------------------------------

# Lowercase, drop a leading 0x, drop leading zeros: "0x300000000" and "300000000" -> "300000000".
norm_hex() {
  local v
  v=$(printf '%s' "$1" | tr -d '[:space:]' | tr '[:upper:]' '[:lower:]')
  v=${v#0x}
  while [ "${#v}" -gt 1 ] && [ "${v#0}" != "$v" ]; do v=${v#0}; done
  printf '%s' "$v"
}

# "_b", "b", "B" -> "b". Anything that is not a or b -> empty.
norm_slot() {
  local v
  v=$(printf '%s' "$1" | tr -d '[:space:]' | tr '[:upper:]' '[:lower:]')
  v=${v#_}
  case "$v" in a|b) printf '%s' "$v" ;; *) printf '' ;; esac
}

# parse_getvar NAME OUTPUT -> prints the value of NAME from "fastboot getvar NAME" output.
# Handles "NAME: value", "NAME:value" and "(bootloader) NAME: value". Returns 1 if not found.
parse_getvar() {
  printf '%s\n' "$2" | tr -d '\r' | awk -v k="$1:" '
    { line = $0
      sub(/^\(bootloader\)[ \t]*/, "", line)
      if (index(line, k) == 1) {
        v = substr(line, length(k) + 1)
        gsub(/^[ \t]+|[ \t]+$/, "", v)
        if (v != "" && !found) { print v; found = 1 }
      }
    }
    END { exit found ? 0 : 1 }'
}

# parse_adb_devices OUTPUT -> prints "TOTAL READY UNAUTHORIZED"
parse_adb_devices() {
  printf '%s\n' "$1" | tr -d '\r' | awk '
    NF >= 2 && $1 != "List" && $1 != "*" {
      t++
      if ($2 == "device") r++
      if ($2 == "unauthorized") u++
    }
    END { printf "%d %d %d\n", t, r, u }'
}

# parse_fastboot_devices OUTPUT -> prints the number of devices listed
parse_fastboot_devices() {
  printf '%s\n' "$1" | tr -d '\r' | awk 'NF >= 2 { n++ } END { print n + 0 }'
}

# parse_battery "dumpsys battery" OUTPUT -> prints the level (digits only)
parse_battery() {
  printf '%s\n' "$1" | tr -d '\r' | awk -F: '
    /^[ \t]*level:/ && !done { v = $2; gsub(/[^0-9]/, "", v); print v; done = 1 }'
}

# parse_wm "wm size" OUTPUT -> prints the physical size, e.g. 1860x2480
parse_wm() {
  printf '%s\n' "$1" | tr -d '\r' | awk -F': ' '
    /^Physical size:/ && !done { v = $2; gsub(/[ \t]/, "", v); print v; done = 1 }'
}

# expected_sha FILE -> prints the lowercase SHA-256 listed for FILE in SHA256SUMS
expected_sha() {
  tr -d '\r' < SHA256SUMS | awk -v f="$1" '
    NF >= 2 && !done {
      n = $2; sub(/^\*/, "", n); sub(/^\.\//, "", n)
      if (n == f) { print tolower($1); done = 1 }
    }'
}

sha256_of() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{ print tolower($1) }'
  elif command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$1" | awk '{ print tolower($1) }'
  elif command -v openssl >/dev/null 2>&1; then
    openssl dgst -sha256 "$1" | awk '{ print tolower($NF) }'
  else
    return 1
  fi
}

# ---------------------------------------------------------------------------
# Errors: always say what state the tablet is in and what to do next.
# ---------------------------------------------------------------------------

recovery_hint() {
  local s="${SLOT:-<slot>}"
  say ""
  say "-------------------------------------------------------------------"
  case "$STAGE" in
    local)
      say "Nothing was sent to your tablet." ;;
    adb)
      say "Nothing was written to your tablet. It is still running Android normally." ;;
    to-bootloader|bootloader)
      say "Nothing was written to your tablet."
      say "If it is in bootloader mode and you want to restart it normally:  fastboot reboot" ;;
    lk-writing)
      say "The tablet is in bootloader mode. Some of vbmeta_$s / boot_$s / logo may already be written."
      say "System was NOT written. Do not start Android now."
      say "Fix the problem above, then run the script again:  bash flash-inkognito.sh --skip-adb --slot $s"
      say "To give up instead, restore boot_$s, vbmeta_$s and logo from your backup (INSTALL.md, 'Going back')." ;;
    to-fastbootd)
      say "vbmeta_$s and boot_$s were written; system was NOT written."
      say "The tablet was asked to restart into fastbootd and did not show up in time."
      say "See INSTALL.md, Troubleshooting, 'fastbootd never shows up'."
      say "When 'fastboot devices' lists it again, run:"
      say "    fastboot set_active $s"
      say "    fastboot reboot bootloader"
      say "and then run the script again:  bash flash-inkognito.sh --skip-adb --slot $s" ;;
    fastbootd)
      say "The tablet is in fastbootd. vbmeta_$s and boot_$s were written; system was NOT written."
      say "Put the slot back and return to bootloader mode:"
      say "    fastboot set_active $s"
      say "    fastboot reboot bootloader"
      say "Then fix the problem and run the script again:  bash flash-inkognito.sh --skip-adb --slot $s"
      say "Or restore boot_$s, vbmeta_$s and logo from your backup (INSTALL.md, 'Going back')." ;;
    fastbootd-slot)
      say "STOPPED ON PURPOSE before writing system (--strict-slot): fastbootd reports a different"
      say "active slot than the bootloader did. Run the script again without --strict-slot to continue."
      say "vbmeta_$s and boot_$s were written; your system partition was NOT touched."
      say "Put the slot back and restart:"
      say "    fastboot set_active $s"
      say "    fastboot reboot"
      say "If Android does not start, restore boot_$s and vbmeta_$s from your backup."
      say "Please report this in the XDA thread (the two slot letters above are all we need)." ;;
    fastbootd-stale)
      say "STOPPED ON PURPOSE before writing system. fastbootd's partition table does not match slot $s"
      say "(vendor_$s looks empty). This happens when fastbootd starts on the other slot with an outdated"
      say "table. Writing system from here could overwrite the vendor partition (see KNOWN-ISSUES.md)."
      say "vbmeta_$s and boot_$s were written; system and vendor were NOT touched."
      say "Put the slot back and restart:"
      say "    fastboot set_active $s"
      say "    fastboot reboot"
      say "If Android does not start, restore boot_$s and vbmeta_$s from your backup."
      say "Please report this in the XDA thread with the slot letters printed above." ;;
    system-writing)
      say "The tablet is in fastbootd. Writing system_$s failed or was interrupted, so it may be incomplete."
      say "Do not start Android now. Retry the remaining steps by hand (INSTALL.md, 'Manual install'):"
      say "    fastboot flash system_$s system.img"
      say "    fastboot -w"
      say "    fastboot set_active $s"
      say "    fastboot reboot"
      say "Or restore your backup (INSTALL.md, 'Going back')." ;;
    wiping)
      say "The tablet is in fastbootd. system_$s was written; wiping user data did not complete."
      say "Finish by hand:"
      say "    fastboot erase userdata"
      say "    fastboot erase metadata"
      say "    fastboot set_active $s"
      say "    fastboot reboot" ;;
    set-active)
      say "Everything was written except the final slot selection. In fastbootd, run:"
      say "    fastboot set_active $s"
      say "    fastboot reboot" ;;
    rebooting)
      say "Everything was written. Restart the tablet with:  fastboot reboot" ;;
    *)
      say "Check INSTALL.md, section Troubleshooting." ;;
  esac
  say "-------------------------------------------------------------------"
}

die() {
  printf '\nERROR: %s\n' "$*" >&2
  recovery_hint >&2
  exit 1
}

on_interrupt() {
  printf '\n\nInterrupted.\n' >&2
  recovery_hint >&2
  exit 130
}
trap on_interrupt INT TERM

# ---------------------------------------------------------------------------
# Running commands
# ---------------------------------------------------------------------------

show() { printf '    > %s\n' "$*"; }

# fb ARGS... : show and run fastboot (dry-run: show only)
fb() {
  show "fastboot $*"
  [ "$DRY_RUN" -eq 1 ] && return 0
  "$FASTBOOT" "$@"
}

# ad ARGS... : show and run adb (dry-run: show only)
ad() {
  show "adb $*"
  [ "$DRY_RUN" -eq 1 ] && return 0
  "$ADB" "$@"
}

# fb_getvar NAME -> sets GV; returns 1 if the variable is missing or the call failed
fb_getvar() {
  local out
  GV=""
  out=$("$FASTBOOT" getvar "$1" 2>&1) || return 1
  GV=$(parse_getvar "$1" "$out") || { GV=""; return 1; }
  return 0
}

adb_prop() {
  "$ADB" shell getprop "$1" 2>/dev/null | tr -d '\r' | sed -n 1p
}

find_tool() {
  local p
  if p=$(command -v "$1" 2>/dev/null); then printf '%s' "$p"; return 0; fi
  for p in "$SCRIPT_DIR/platform-tools/$1" "$SCRIPT_DIR/$1"; do
    if [ -x "$p" ]; then printf '%s' "$p"; return 0; fi
  done
  return 1
}

# ---------------------------------------------------------------------------
# Steps
# ---------------------------------------------------------------------------

parse_args() {
  while [ $# -gt 0 ]; do
    case "$1" in
      --dry-run) DRY_RUN=1 ;;
      --skip-adb) SKIP_ADB=1 ;;
      --no-logo) FLASH_LOGO=0 ;;
      --slot)
        [ $# -ge 2 ] || { usage; die "--slot needs a or b."; }
        WANT_SLOT=$(norm_slot "$2")
        [ -n "$WANT_SLOT" ] || die "--slot needs a or b (got '$2')."
        shift ;;
      --slot=*)
        WANT_SLOT=$(norm_slot "${1#--slot=}")
        [ -n "$WANT_SLOT" ] || die "--slot needs a or b (got '${1#--slot=}')." ;;
      --strict-slot) STRICT_SLOT=1 ;;
      -h|--help) usage; exit 0 ;;
      *) usage; die "Unknown option: $1" ;;
    esac
    shift
  done
}

check_tools() {
  step "Checking platform-tools on this computer"
  if [ "$SKIP_ADB" -eq 0 ]; then
    if ADB=$(find_tool adb); then note "adb: $ADB"
    elif [ "$DRY_RUN" -eq 1 ]; then note "adb: not found (fine for a dry run; needed for a real install)"
    else die "adb was not found. Install Android platform-tools (INSTALL.md, step 2) and try again."
    fi
  fi
  if FASTBOOT=$(find_tool fastboot); then note "fastboot: $FASTBOOT"
  elif [ "$DRY_RUN" -eq 1 ]; then note "fastboot: not found (fine for a dry run; needed for a real install)"
  else die "fastboot was not found. Install Android platform-tools (INSTALL.md, step 2) and try again."
  fi

  if [ "$DRY_RUN" -eq 1 ]; then
    note "(dry run: the fastboot version check is skipped; a real run needs version $MIN_FASTBOOT_MAJOR or newer)"
    return 0
  fi
  local ver major
  ver=$("$FASTBOOT" --version 2>/dev/null | tr -d '\r' | sed -n 's/^fastboot version \([^ ]*\).*/\1/p' | sed -n 1p)
  major=${ver#*:}
  major=${major%%.*}
  case "$major" in
    ''|*[!0-9]*)
      warn "Could not read the fastboot version ('$ver'). Continuing, but please use the latest platform-tools." ;;
    *)
      if [ "$major" -lt "$MIN_FASTBOOT_MAJOR" ]; then
        die "Your fastboot ($ver) is too old. Install the latest platform-tools from Google (INSTALL.md, step 2). Distro packages are often outdated."
      fi
      note "fastboot version $ver (OK)" ;;
  esac
}

check_files() {
  step "Checking the image files against SHA256SUMS"
  [ -f SHA256SUMS ] || die "SHA256SUMS is missing. Run the script from the unzipped release folder."
  local files="vbmeta.img boot.img system.img"
  [ "$FLASH_LOGO" -eq 1 ] && files="$files logo.img"
  local f want got
  for f in $files; do
    [ -f "$f" ] || die "$f is missing from this folder. Unzip the whole release again (or use --no-logo if only logo.img is missing)."
    want=$(expected_sha "$f")
    [ -n "$want" ] || die "$f is not listed in SHA256SUMS."
    printf '    %-11s ' "$f"
    got=$(sha256_of "$f") || die "No SHA-256 tool found (need sha256sum, shasum or openssl)."
    if [ "$got" != "$want" ]; then
      printf 'MISMATCH\n'
      die "$f does not match SHA256SUMS. The download is damaged or incomplete. Download the release again."
    fi
    printf 'OK\n'
  done
  [ "$FLASH_LOGO" -eq 1 ] || note "logo.img: skipped (--no-logo)"
}

preflight_adb() {
  STAGE="adb"
  step "Checking the tablet over USB debugging (adb)"
  if [ "$DRY_RUN" -eq 1 ]; then
    show "adb devices                                      (exactly one device, state 'device')"
    show "adb shell getprop ro.build.versionName           (expect $EXPECT_FW, or:)"
    show "adb shell getprop ro.vendor.build.version.incremental   (expect $EXPECT_VENDOR_INC)"
    show "adb shell wm size                                (expect Physical size: $EXPECT_WM)"
    show "adb shell dumpsys battery                        (expect level >= $MIN_BATTERY)"
    show "adb shell getprop ro.boot.slot_suffix            (the active slot: _a or _b)"
    return 0
  fi

  "$ADB" start-server >/dev/null 2>&1 || true
  local list counts total ready unauth
  list=$("$ADB" devices 2>/dev/null) || die "adb devices failed. Unplug and replug the USB cable and try again."
  counts=$(parse_adb_devices "$list")
  read -r total ready unauth <<EOF
$counts
EOF
  if [ "$total" -eq 0 ]; then
    die "No tablet found over adb. Turn on USB debugging (INSTALL.md, step 4), plug the tablet in, and try again. If the tablet is already in bootloader mode, use --skip-adb."
  fi
  if [ "$total" -gt 1 ]; then
    die "More than one Android device is connected. Unplug everything except the tablet."
  fi
  if [ "$unauth" -gt 0 ]; then
    die "The tablet has not allowed this computer yet. Unlock the tablet, accept the 'Allow USB debugging?' prompt (tick 'Always allow'), then run the script again."
  fi
  [ "$ready" -eq 1 ] || die "The tablet is connected but not ready (adb state is not 'device'). Replug the cable and try again."
  note "one device connected (OK)"

  local fw vinc
  fw=$(adb_prop ro.build.versionName)
  vinc=$(adb_prop ro.vendor.build.version.incremental)
  note "firmware (ro.build.versionName): ${fw:-<empty>}"
  note "vendor build (ro.vendor.build.version.incremental): ${vinc:-<empty>}"
  if [ "$fw" = "$EXPECT_FW" ] || [ "$vinc" = "$EXPECT_VENDOR_INC" ]; then
    INFO_FW="${fw:-other ROM} / vendor $vinc"
  else
    die "This tablet is not on firmware $EXPECT_FW. Update the stock firmware to $EXPECT_FW with Bigme's own update first. InKognito supports only that firmware."
  fi

  local wm
  wm=$(parse_wm "$("$ADB" shell wm size 2>/dev/null)")
  note "screen (wm size, physical): ${wm:-<empty>}"
  [ "$wm" = "$EXPECT_WM" ] || die "Screen size is '${wm:-unknown}', expected $EXPECT_WM. This does not look like a B1051C Pro. Stopping."

  local bat
  bat=$(parse_battery "$("$ADB" shell dumpsys battery 2>/dev/null)")
  note "battery: ${bat:-<unknown>}%"
  case "$bat" in ''|*[!0-9]*) die "Could not read the battery level. Charge to at least $MIN_BATTERY% and try again." ;; esac
  [ "$bat" -ge "$MIN_BATTERY" ] || die "Battery is at $bat%. Charge it to at least $MIN_BATTERY% and run the script again."
  INFO_BATTERY="$bat%"

  local suffix
  suffix=$(adb_prop ro.boot.slot_suffix)
  SLOT=$(norm_slot "$suffix")
  [ -n "$SLOT" ] || die "Could not read the active slot (ro.boot.slot_suffix = '${suffix}')."
  note "active slot: $SLOT"
  if [ -n "$WANT_SLOT" ] && [ "$WANT_SLOT" != "$SLOT" ]; then
    die "You asked for --slot $WANT_SLOT but the tablet runs on slot $SLOT. Stopping."
  fi
}

reboot_to_bootloader() {
  STAGE="to-bootloader"
  step "Restarting the tablet into bootloader mode"
  note "The e-ink screen may not change much. Watch this window, not the tablet."
  ad reboot bootloader || die "adb reboot bootloader failed."
}

# wait_fastboot_device SECONDS -> 0 when exactly one fastboot device is listed
wait_fastboot_device() {
  local limit="$1" waited=0 list n
  while :; do
    list=$("$FASTBOOT" devices 2>&1 | tr -d '\r')
    if printf '%s\n' "$list" | grep -q "no permissions"; then
      die "fastboot sees the tablet but has no permission to use it. On Linux, install the Android udev rules (INSTALL.md, step 2) and replug the cable."
    fi
    n=$(parse_fastboot_devices "$list")
    [ "$n" -eq 1 ] && return 0
    [ "$n" -gt 1 ] && die "More than one fastboot device is connected. Unplug everything except the tablet."
    [ "$waited" -ge "$limit" ] && return 1
    sleep 2
    waited=$((waited + 2))
  done
}

check_bootloader() {
  STAGE="bootloader"
  step "Checking the tablet in bootloader mode (fastboot)"
  if [ "$DRY_RUN" -eq 1 ]; then
    show "fastboot devices                         (wait up to ${WAIT_BOOTLOADER_SECS}s for exactly one device)"
    show "fastboot getvar is-userspace             (expect no = bootloader, not fastbootd)"
    show "fastboot getvar product                  (expect $EXPECT_PRODUCT)"
    show "fastboot getvar unlocked                 (expect yes)"
    show "fastboot getvar partition-size:waveform  (must exist: e-ink device)"
    show "fastboot getvar partition-size:super     (expect 0x$EXPECT_SUPER_SIZE)"
    show "fastboot getvar current-slot             (must equal the slot read over adb)"
    show "fastboot getvar partition-size:boot_<slot>  (expect 0x$EXPECT_BOOT_SIZE)"
    SLOT="${WANT_SLOT:-<slot>}"
    return 0
  fi

  note "Waiting for the tablet in bootloader mode (up to ${WAIT_BOOTLOADER_SECS}s)..."
  wait_fastboot_device "$WAIT_BOOTLOADER_SECS" || die "The tablet did not show up in bootloader mode. Check the cable and drivers (INSTALL.md, Troubleshooting, 'waiting for device')."
  note "one fastboot device (OK)"

  fb_getvar is-userspace || die "Could not talk to the bootloader (getvar is-userspace failed)."
  if [ "$GV" = "yes" ]; then
    die "The tablet is in fastbootd, not in the bootloader. Run 'fastboot reboot bootloader', then run this script again with --skip-adb."
  fi

  fb_getvar product || die "Could not read the product name from the bootloader."
  note "product: $GV"
  [ "$GV" = "$EXPECT_PRODUCT" ] || die "This is not a Bigme B1051C Pro bootloader (product '$GV', expected $EXPECT_PRODUCT)."

  fb_getvar unlocked || die "Could not read the bootloader lock state."
  note "unlocked: $GV"
  [ "$GV" = "yes" ] || die "The bootloader is LOCKED. Unlock it first, then come back: $UNLOCK_GUIDE"

  fb_getvar partition-size:waveform || die "No 'waveform' partition: this is not a B1051C Pro e-ink tablet. Stopping."
  note "waveform partition: present (0x$(norm_hex "$GV"))"

  fb_getvar partition-size:super || die "Could not read the size of the super partition."
  note "super partition: 0x$(norm_hex "$GV")"
  [ "$(norm_hex "$GV")" = "$EXPECT_SUPER_SIZE" ] || die "Unexpected super partition size ($GV). Stopping."

  fb_getvar current-slot || die "Could not read the active slot from the bootloader."
  local lk_slot
  lk_slot=$(norm_slot "$GV")
  [ -n "$lk_slot" ] || die "The bootloader reports an unknown slot ('$GV')."
  note "bootloader active slot: $lk_slot"
  if [ "$SKIP_ADB" -eq 1 ]; then
    if [ -n "$WANT_SLOT" ] && [ "$WANT_SLOT" != "$lk_slot" ]; then
      die "You asked for --slot $WANT_SLOT but the bootloader's active slot is $lk_slot. Stopping. See INSTALL.md, Troubleshooting."
    fi
    SLOT="$lk_slot"
  elif [ "$lk_slot" != "$SLOT" ]; then
    die "Android ran on slot $SLOT but the bootloader reports slot $lk_slot. Stopping."
  fi

  fb_getvar "partition-size:boot_$SLOT" || die "Could not read the size of boot_$SLOT."
  note "boot_$SLOT partition: 0x$(norm_hex "$GV")"
  [ "$(norm_hex "$GV")" = "$EXPECT_BOOT_SIZE" ] || die "Unexpected boot_$SLOT size ($GV). Stopping."
}

confirm() {
  STAGE="bootloader"
  local logo_line="logo       <- logo.img     (InKognito boot logo; skip with --no-logo)"
  [ "$FLASH_LOGO" -eq 1 ] || logo_line="logo       (skipped: --no-logo)"
  local s="$SLOT"
  cat <<EOF

=====================================================================
 Ready to install $RELEASE_NAME
---------------------------------------------------------------------
 Tablet        Bigme B1051C Pro ($EXPECT_PRODUCT), bootloader unlocked
 Firmware      $INFO_FW
 Battery       $INFO_BATTERY
 Target slot   $s      <- write this letter down
---------------------------------------------------------------------
 Bootloader:   vbmeta_$s   <- vbmeta.img
               boot_$s     <- boot.img
               $logo_line
 fastbootd:    system_$s   <- system.img
               ERASE all user data on the tablet (fastboot -w)
               set slot $s active, restart
 Nothing else is written. Your microSD card is not touched.
=====================================================================
EOF
  if [ "$SKIP_ADB" -eq 1 ]; then
    say " --skip-adb: firmware, screen size and battery were NOT checked."
    say " Continue only if this is a B1051C Pro on $EXPECT_FW with at least $MIN_BATTERY% battery."
  fi
  if [ "$DRY_RUN" -eq 1 ]; then
    say ""
    say "    (dry run: here you would be asked to type FLASH; nothing is asked or written)"
    return 0
  fi
  say ""
  local ans=""
  read -r -p "Type FLASH (capital letters) and press Enter to start. Anything else cancels: " ans || ans=""
  if [ "$ans" != "FLASH" ]; then
    say ""
    say "Cancelled. Nothing was written."
    say "The tablet is in bootloader mode. To restart it normally:  fastboot reboot"
    exit 0
  fi
}

flash_bootloader_parts() {
  STAGE="lk-writing"
  step "Writing vbmeta, boot and logo (bootloader mode)"
  fb flash "vbmeta_$SLOT" vbmeta.img || die "Writing vbmeta_$SLOT failed."
  fb flash "boot_$SLOT" boot.img || die "Writing boot_$SLOT failed."
  if [ "$FLASH_LOGO" -eq 1 ]; then
    fb flash logo logo.img || die "Writing logo failed."
  fi
}

enter_fastbootd() {
  STAGE="to-fastbootd"
  step "Restarting into fastbootd (needed to write system)"
  fb reboot fastboot || die "fastboot reboot fastboot failed."
  if [ "$DRY_RUN" -eq 1 ]; then
    show "fastboot devices + fastboot getvar is-userspace   (wait up to ${WAIT_FASTBOOTD_SECS}s for: yes)"
    STAGE="fastbootd"
    return 0
  fi
  note "Waiting for fastbootd (up to ${WAIT_FASTBOOTD_SECS}s). If this window says"
  note "'< waiting for any device >' for minutes, see INSTALL.md, Troubleshooting."
  sleep 5
  local waited=5
  while :; do
    if wait_fastboot_device 4; then
      if fb_getvar is-userspace && [ "$GV" = "yes" ]; then break; fi
    fi
    [ "$waited" -ge "$WAIT_FASTBOOTD_SECS" ] && die "fastbootd did not come up in time."
    sleep 3
    waited=$((waited + 7))
  done
  STAGE="fastbootd"
  note "fastbootd is up (is-userspace: yes)"
}

check_fastbootd() {
  STAGE="fastbootd"
  step "Checking fastbootd"
  if [ "$DRY_RUN" -eq 1 ]; then
    show "fastboot getvar current-slot             (printed; may differ from $SLOT: system is written as system_$SLOT and $SLOT is set active at the end)"
    show "fastboot getvar partition-size:vendor_$SLOT   (must not be 0x0, else stop: outdated partition table)"
    show "fastboot getvar snapshot-update-status   (if not 'none': fastboot snapshot-update cancel)"
    return 0
  fi

  fb_getvar current-slot || die "Could not read the slot that fastbootd reports."
  local fbd_slot
  fbd_slot=$(norm_slot "$GV")
  note "fastbootd reports current slot: ${fbd_slot:-$GV}   (target slot: $SLOT)"
  if [ "$fbd_slot" != "$SLOT" ]; then
    if [ "$STRICT_SLOT" -eq 1 ]; then
      STAGE="fastbootd-slot"
      die "fastbootd reports slot '${fbd_slot:-$GV}' but the target slot is $SLOT (--strict-slot)."
    fi
    note "fastbootd came up on slot '${fbd_slot:-$GV}', not $SLOT (known on this tablet). That is handled:"
    note "system is written to system_$SLOT by name, and slot $SLOT is set active before the restart."
  fi

  # fastbootd writes the partition table it sees back to every slot when it resizes system_<slot>.
  # If it started on the other slot with an outdated table (on the developer's tablet that view showed
  # vendor_b with size 0x0), writing system could put system on top of the real vendor partition.
  # Read-only guard: the target slot's vendor partition must have a size in fastbootd's view.
  fb_getvar "partition-size:vendor_$SLOT" || { STAGE="fastbootd-stale"; die "fastbootd could not read the size of vendor_$SLOT."; }
  note "vendor_$SLOT as fastbootd sees it: 0x$(norm_hex "$GV")"
  if [ "$(norm_hex "$GV")" = "0" ]; then
    STAGE="fastbootd-stale"
    die "fastbootd sees vendor_$SLOT with size 0: its partition table for slot $SLOT is outdated."
  fi

  fb_getvar snapshot-update-status || die "Could not read the update (snapshot) status from fastbootd."
  note "snapshot-update-status: $GV"
  if [ "$GV" != "none" ]; then
    note "A system update is still pending on this tablet. Cancelling it, as required before writing system."
    fb snapshot-update cancel || die "fastboot snapshot-update cancel failed. Start Android normally once, let any pending update finish, then try again."
    fb_getvar snapshot-update-status || die "Could not read the update status after cancelling."
    note "snapshot-update-status: $GV"
    [ "$GV" = "none" ] || die "The pending update could not be cancelled (status '$GV')."
  fi
}

flash_system_and_wipe() {
  STAGE="system-writing"
  step "Writing system_$SLOT (this takes a few minutes)"
  fb flash "system_$SLOT" system.img || die "Writing system_$SLOT failed."

  STAGE="wiping"
  step "Erasing user data (fastboot -w)"
  if [ "$DRY_RUN" -eq 1 ]; then
    show "fastboot -w"
    note "(if its output does not show userdata being erased:)"
    show "fastboot erase userdata"
    show "fastboot erase metadata"
  else
    show "fastboot -w"
    local out rc
    out=$("$FASTBOOT" -w 2>&1); rc=$?
    printf '%s\n' "$out" | sed 's/^/      /'
    [ "$rc" -eq 0 ] || die "fastboot -w failed."
    if ! printf '%s\n' "$out" | grep -q "Erasing 'userdata'"; then
      note "fastboot -w did not erase userdata on this tablet (its fastbootd does not report partition types)."
      note "Erasing userdata and metadata directly instead (the same partitions -w is meant to wipe)."
      fb erase userdata || die "fastboot erase userdata failed."
      fb erase metadata || die "fastboot erase metadata failed."
    fi
  fi

  STAGE="set-active"
  step "Making slot $SLOT the active slot"
  fb set_active "$SLOT" || die "fastboot set_active $SLOT failed."

  STAGE="rebooting"
  step "Restarting the tablet"
  fb reboot || die "fastboot reboot failed."
  STAGE="done"
}

what_next() {
  cat <<EOF

=====================================================================
 Done. Leave the USB cable connected until Google setup appears.
 It does no harm, and you need it if setup gets stuck.

 What happens now (about 5 minutes, do not interrupt):
  1. The boot logo shows. Android prepares the empty data partition.
  2. The first start installs the InKognito layer and RESTARTS BY ITSELF,
     before you see any setup screen. This is normal.
  3. The boot animation plays, then Google setup appears (Wi-Fi, Google
     account optional). Apps and settings finish installing in the
     background while you go through setup. Then the Lawnchair home screen.

 On e-ink, the screen can sit still for a minute or more. Be patient.
 If the tablet switches itself off about 5 seconds into starting, with
 nothing on screen, see INSTALL.md, Troubleshooting (hidden warning).
 If Google setup gets stuck, see INSTALL.md, Troubleshooting.
 After setup: turn OFF USB debugging (KNOWN-ISSUES.md explains why).
=====================================================================
EOF
}

main() {
  parse_args "$@"
  SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd) || die "Cannot find the script folder."
  cd "$SCRIPT_DIR" || die "Cannot enter $SCRIPT_DIR."

  say "InKognito installer - $RELEASE_NAME - Bigme B1051C Pro"
  [ "$DRY_RUN" -eq 1 ] && say "DRY RUN: nothing will be run on the tablet. adb and fastboot are not called."
  [ "$SKIP_ADB" -eq 1 ] && say "--skip-adb: expecting the tablet in bootloader mode already."

  check_tools
  check_files
  if [ "$SKIP_ADB" -eq 0 ]; then
    preflight_adb
    reboot_to_bootloader
  fi
  check_bootloader
  confirm
  flash_bootloader_parts
  enter_fastbootd
  check_fastbootd
  flash_system_and_wipe
  if [ "$DRY_RUN" -eq 1 ]; then
    say ""
    say "Dry run finished. Nothing was run on the tablet."
  else
    what_next
  fi
}

# Run main only when executed, not when sourced (lets the helpers be tested on their own).
if [ "${BASH_SOURCE[0]}" = "$0" ]; then
  main "$@"
fi
