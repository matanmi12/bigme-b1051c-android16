#!/usr/bin/env python3
"""Find BL/B call sites and ADRP+ADD/LDR references to given targets in a raw arm64 Image.

Usage: xref.py IMAGE KALLSYMS TARGET [TARGET...]
TARGET = symbol name or hex runtime address. Scans the whole .text (_stext.._etext).
For data targets, reports ADRP(page)+ADD/LDR/STR(lo12) pairs within the next 8 instructions.
"""
import sys, struct, bisect
sys.path.insert(0, __import__('os').path.dirname(__file__))
from kdis import K


def main():
    img, ks, *targets = sys.argv[1:]
    k = K(img, ks)
    tv = {}
    for t in targets:
        try:
            a = int(t, 16)
        except ValueError:
            a = k.byname[t]
        tv[a] = t
    st, en = k.byname['_stext'], k.byname['_etext']
    data = k.img[k.off(st):k.off(en)]
    n = len(data) // 4
    words = struct.unpack('<%dI' % n, data[:n * 4])
    pages = {a & ~0xfff: a for a in tv}
    for i, w in enumerate(words):
        pc = st + i * 4
        op = w & 0xfc000000
        if op in (0x94000000, 0x14000000):  # BL / B
            imm = w & 0x3ffffff
            if imm & 0x2000000:
                imm -= 0x4000000
            t = pc + imm * 4
            if t in tv:
                print('%s %x (%s) -> %s' % ('bl' if op == 0x94000000 else 'b ', pc, k.sym(pc), tv[t]))
        elif (w & 0x9f000000) == 0x90000000:  # ADRP
            rd = w & 0x1f
            immlo = (w >> 29) & 3
            immhi = (w >> 5) & 0x7ffff
            imm = ((immhi << 2) | immlo)
            if imm & (1 << 20):
                imm -= 1 << 21
            page = (pc & ~0xfff) + (imm << 12)
            cands = [a for a in tv if (a & ~0xfff) == page]
            if not cands:
                continue
            for j in range(1, 12):
                if i + j >= n:
                    break
                w2 = words[i + j]
                # ADD imm (64-bit) : 1001000100 sh imm12 Rn Rd
                if (w2 & 0xff800000) == 0x91000000 and ((w2 >> 5) & 0x1f) == rd:
                    lo = (w2 >> 10) & 0xfff
                    if (w2 >> 22) & 1:
                        lo <<= 12
                    for a in cands:
                        if page + lo <= a < page + lo + 0x400:
                            print('ref %x (%s) -> %s+0x%x [add]' % (pc + 4 * j, k.sym(pc + 4 * j), tv[a], a - page - lo))
                    break
                # LDR/STR unsigned offset: size 1x111001 opc imm12 Rn Rt
                if (w2 & 0x3b000000) == 0x39000000 and ((w2 >> 5) & 0x1f) == rd:
                    size = w2 >> 30
                    lo = ((w2 >> 10) & 0xfff) << size
                    for a in cands:
                        if a == page + lo:
                            print('ref %x (%s) -> %s [ldst]' % (pc + 4 * j, k.sym(pc + 4 * j), tv[a]))
                    # do not break: same base reg may be reused


if __name__ == '__main__':
    main()
