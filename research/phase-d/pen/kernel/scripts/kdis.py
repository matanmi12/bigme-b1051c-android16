#!/usr/bin/env python3
"""Annotated arm64 disassembly of a kernel function from a raw Image + kallsyms.

Usage: kdis.py IMAGE KALLSYMS NAME_OR_ADDR [--len N] [--raw]
  NAME_OR_ADDR: symbol name (exact, or prefix before '$'), or hex runtime address.
Annotations: call/branch targets -> symbol names; adrp+add/ldr/str -> symbol or string.
Offsets printed as runtime address and as Image offset (addr - _text).
"""
import sys, bisect, re, struct, argparse
M64 = (1 << 64) - 1
from capstone import Cs, CS_ARCH_ARM64, CS_MODE_ARM
from capstone.arm64 import ARM64_OP_IMM, ARM64_OP_REG, ARM64_OP_MEM


def load_syms(path):
    syms = []
    for line in open(path, errors='replace'):
        p = line.split()
        if len(p) < 3:
            continue
        if len(p) > 3:  # module symbol
            continue
        try:
            a = int(p[0], 16)
        except ValueError:
            continue
        syms.append((a, p[1], p[2]))
    syms.sort()
    return syms


class K:
    def __init__(self, img, ks):
        self.img = open(img, 'rb').read()
        self.syms = load_syms(ks)
        self.byname = {}
        for a, t, n in self.syms:
            self.byname.setdefault(n, a)
            base = n.split('$')[0]
            self.byname.setdefault(base, a)
        self.text = self.byname['_text']
        self.slide = self.text - 0xffffff8008080000  # link-time pointers in the Image are unslid
        self.addrs = [s[0] for s in self.syms]

    def off(self, a):
        return a - self.text

    def read(self, a, n):
        o = self.off(a)
        if 0 <= o < len(self.img):
            return self.img[o:o + n]
        return None

    def u32(self, a):
        b = self.read(a, 4)
        return struct.unpack('<I', b)[0] if b and len(b) == 4 else None

    def u64(self, a):
        b = self.read(a, 8)
        return struct.unpack('<Q', b)[0] if b and len(b) == 8 else None

    def sym(self, a):
        i = bisect.bisect_right(self.addrs, a) - 1
        if i < 0:
            return None
        # prefer text/data names that are not cfi_jt duplicates at same address
        sa = self.syms[i][0]
        j = i
        cands = []
        while j >= 0 and self.syms[j][0] == sa:
            cands.append(self.syms[j][2])
            j -= 1
        name = sorted(cands, key=lambda n: ('__typeid' in n, 'cfi_jt' in n, len(n)))[0]
        d = a - sa
        return name if d == 0 else '%s+0x%x' % (name, d)

    def func_end(self, a):
        i = bisect.bisect_right(self.addrs, a)
        while i < len(self.addrs) and self.addrs[i] <= a:
            i += 1
        return self.addrs[i] if i < len(self.addrs) else a + 0x1000

    def cstr(self, a, maxlen=200):
        b = self.read(a, maxlen)
        if not b:
            return None
        z = b.find(b'\0')
        if z <= 0:
            return None
        s = b[:z]
        if s[:1] == b'\x01' and len(s) > 1:  # KERN_ level prefix
            s = b'<' + s[1:2] + b'>' + s[2:]
        try:
            t = s.decode('ascii')
        except UnicodeDecodeError:
            return None
        if sum(c.isprintable() or c in '\n\t' for c in t) < len(t):
            return None
        if len(t) < 3:
            return None
        return t


def describe(k, a):
    s = k.sym(a)
    st = None
    if not (k.byname['_text'] <= a < k.byname['_etext']):
        st = k.cstr(a)
    out = s or hex(a)
    if st:
        out += ' "%s"' % st.replace('\n', '\\n')[:160]
    return out


def disasm(k, start, end, raw=False):
    md = Cs(CS_ARCH_ARM64, CS_MODE_ARM)
    md.detail = True
    code = k.read(start, end - start)
    regs = {}
    out = []
    for ins in md.disasm(code, start):
        ann = ''
        m = ins.mnemonic
        ops = ins.operands
        try:
            if m == 'adrp':
                r = ins.reg_name(ops[0].reg)
                regs[r] = ops[1].imm & M64
                ann = describe(k, ops[1].imm & M64)
            elif m in ('add',) and len(ops) == 3 and ops[1].type == ARM64_OP_REG and ops[2].type == ARM64_OP_IMM:
                src = ins.reg_name(ops[1].reg)
                dst = ins.reg_name(ops[0].reg)
                if src in regs:
                    v = (regs[src] + ops[2].imm) & M64
                    ann = describe(k, v)
                    regs[dst] = v
                else:
                    regs.pop(dst, None)
            elif m.startswith('ldr') or m.startswith('str') or m.startswith('ldp') or m.startswith('stp'):
                for o in ops:
                    if o.type == ARM64_OP_MEM:
                        b = ins.reg_name(o.mem.base) if o.mem.base else None
                        if b in regs:
                            v = (regs[b] + o.mem.disp) & M64
                            ann = describe(k, v)
                            if m == 'ldr' and ins.reg_name(ops[0].reg).startswith('x'):
                                pv = k.u64(v)
                                if pv is not None and (pv >> 32) == 0xffffff80:
                                    pv += k.slide
                                if pv is not None and (pv >> 40) == 0xffffff:
                                    ann += ' -> [%s]' % describe(k, pv)
                if m.startswith('ldr') or m.startswith('ldp'):
                    for o in ops:
                        if o.type == ARM64_OP_REG:
                            regs.pop(ins.reg_name(o.reg), None)
            elif m in ('bl', 'b') or m.startswith('b.') or m in ('cbz', 'cbnz', 'tbz', 'tbnz'):
                for o in ops:
                    if o.type == ARM64_OP_IMM and (o.imm & M64) > 0xffff000000000000:
                        t = o.imm & M64
                        if m == 'bl' or not (start <= t < end):
                            ann = k.sym(t)
                if m == 'bl':
                    for r in list(regs):
                        if r in ('x0', 'x1', 'x2', 'x3', 'x4', 'x5', 'x6', 'x7', 'x8', 'x9', 'x10', 'x11', 'x12', 'x13', 'x14', 'x15', 'x16', 'x17'):
                            regs.pop(r)
            else:
                if ops and ops[0].type == ARM64_OP_REG and m not in ('cmp', 'cmn', 'tst', 'ccmp', 'ccmn', 'fcmp'):
                    regs.pop(ins.reg_name(ops[0].reg), None)
        except Exception as e:  # keep going
            ann = '?' + str(e)
        i0 = bisect.bisect_left(k.addrs, ins.address)
        if i0 < len(k.addrs) and k.addrs[i0] == ins.address and ins.address != start:
            out.append('')
            out.append('<%s>:' % k.sym(ins.address))
            regs = {}
        line = '%x %07x: %-8s %-40s' % (ins.address, k.off(ins.address), m, ins.op_str)
        if ann:
            line += ' ; ' + ann
        out.append(line)
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('image')
    ap.add_argument('kallsyms')
    ap.add_argument('target')
    ap.add_argument('--len', type=lambda x: int(x, 0))
    ap.add_argument('--end')
    a = ap.parse_args()
    k = K(a.image, a.kallsyms)
    t = a.target
    if re.fullmatch(r'(0x)?[0-9a-fA-F]{16}', t):
        st = int(t, 16)
    else:
        st = k.byname[t]
    if a.end:
        en = int(a.end, 16) if re.fullmatch(r'(0x)?[0-9a-fA-F]{16}', a.end) else k.byname[a.end]
    else:
        en = st + a.len if a.len else k.func_end(st)
    print('; %s  [%x .. %x)  image off 0x%x' % (k.sym(st), st, en, k.off(st)))
    for l in disasm(k, st, en):
        print(l)


if __name__ == '__main__':
    main()
