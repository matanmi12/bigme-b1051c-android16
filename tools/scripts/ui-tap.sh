#!/bin/bash
# Test helper: find a UI element by (regex on) its text via uiautomator and tap it. Scrolls up to N times.
# usage: ui-tap.sh '<regex>' [taps=1] [scrolls=8]      prints the matched text (after tapping) or "NOTFOUND"
set -u
RX="$1"; TAPS="${2:-1}"; SCROLLS="${3:-8}"
T=$(mktemp -d)
find_el() {
  adb shell uiautomator dump /data/local/tmp/ui.xml >/dev/null 2>&1
  adb pull /data/local/tmp/ui.xml "$T/ui.xml" >/dev/null 2>&1
  /opt/homebrew/bin/python3.12 -I - "$T/ui.xml" "$RX" <<'EOF'
import re, sys
x = open(sys.argv[1], encoding='utf-8').read(); rx = re.compile(sys.argv[2])
for m in re.finditer(r'<node [^>]*>', x):
    n = m.group(0)
    t = re.search(r' text="([^"]*)"', n); b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n)
    if t and b and rx.search(t.group(1)):
        a, c, d, e = map(int, b.groups())
        if e - c > 10 and c < 2350:          # skip elements hidden under the nav bar
            print((a + d) // 2, (c + e) // 2, t.group(1)); break
EOF
}
for i in $(seq 0 "$SCROLLS"); do
  r=$(find_el); [ -n "$r" ] && break
  adb shell input swipe 930 1900 930 900 400; sleep 1
done
[ -z "$r" ] && { echo NOTFOUND; exit 1; }
X=$(echo "$r" | awk '{print $1}'); Y=$(echo "$r" | awk '{print $2}')
for k in $(seq 1 "$TAPS"); do adb shell input tap "$X" "$Y"; sleep 0.7; done
find_el | cut -d' ' -f3-
