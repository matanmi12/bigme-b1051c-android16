#!/usr/bin/env python3
"""Decode a byte jump table: jtab.py IMAGE TEXT_ADDR TABLE_ADDR BASE_ADDR FIRST COUNT [labels as addr=name ...]"""
import sys
img = open(sys.argv[1], 'rb').read()
text = int(sys.argv[2], 16); tab = int(sys.argv[3], 16); base = int(sys.argv[4], 16)
first = int(sys.argv[5], 0); cnt = int(sys.argv[6], 0)
labels = dict((int(a, 16) & 0xffffff, n) for a, n in (x.split('=') for x in sys.argv[7:]))
out = {}
for i in range(cnt):
    t = (base + img[tab - text + i] * 4) & 0xffffff
    out.setdefault(t, []).append(first + i)
for t, ms in sorted(out.items(), key=lambda kv: kv[1][0]):
    s = ','.join(hex(m) for m in ms) if len(ms) < 40 else '%d modes (%s..)' % (len(ms), ','.join(hex(m) for m in ms[:6]))
    print('%06x %-28s %s' % (t, labels.get(t, ''), s))
