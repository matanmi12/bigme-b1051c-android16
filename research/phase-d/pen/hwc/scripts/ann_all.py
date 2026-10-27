#!/usr/bin/env python3
"""Annotate every function in .text (bounds from .eh_frame_hdr) into one file. usage: ann_all.py ELF OUT"""
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from ann import Elf, annotate
elf = Elf(sys.argv[1])
t = elf.secs['.text']
starts = [s for s in elf.fstarts if t['addr'] <= s < t['addr'] + t['size']]
with open(sys.argv[2], 'w') as f:
    for i, s in enumerate(starts):
        e = starts[i + 1] if i + 1 < len(starts) else t['addr'] + t['size']
        annotate(elf, s, e, f)
print(len(starts), 'functions')
