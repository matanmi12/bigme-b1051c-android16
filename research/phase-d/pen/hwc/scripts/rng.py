#!/usr/bin/env python3
"""rng.py FILE START END [-a]: print annotated lines with START<=addr<=END (hex); -a keeps logger boilerplate."""
import sys, re
f, s, e = sys.argv[1], int(sys.argv[2], 16), int(sys.argv[3], 16)
keep = '-a' in sys.argv
noise = re.compile(r'DbgLogger::~DbgLogger|stp +q[01], q[01], \[x29|movi +v[01]\.2d|stur +xzr, \[x29, #-0x|sturb +w8, \[x29, #-0x|stur +w[89], \[x29, #-0x|str +q[01], \[x2[47], #0xa0\]|stur +q[01], \[x29')
for line in open(f):
    m = re.match(r'\s+([0-9a-f]+):', line)
    if not m: continue
    a = int(m.group(1), 16)
    if s <= a <= e and (keep or not noise.search(line)):
        sys.stdout.write(line)
