#!/usr/bin/env python3
"""Annotated AArch64 disassembly of functions in an ELF shared object (offline, read-only).

usage: ann.py ELF OUTDIR name_or_addr [name_or_addr ...]
  name_or_addr: a demangled-symbol substring (first match per pattern unless prefixed with '*' = all matches)
                or 0xADDR (function containing ADDR, bounds from .eh_frame_hdr)
Annotations: bl/b targets (dynsym / PLT), adrp+add strings, adrp+ldr literal floats/doubles/ints,
GOT loads (GLOB_DAT symbol), and a call/xref summary per function.
"""
import struct, sys, os, subprocess, re
import capstone
from capstone import arm64_const as A

def u32(b, o): return struct.unpack_from('<I', b, o)[0]
def u64(b, o): return struct.unpack_from('<Q', b, o)[0]

class Elf:
    def __init__(self, path):
        self.b = open(path, 'rb').read()
        b = self.b
        shoff = u64(b, 0x28); shentsize = struct.unpack_from('<H', b, 0x3a)[0]
        shnum = struct.unpack_from('<H', b, 0x3c)[0]; shstrndx = struct.unpack_from('<H', b, 0x3e)[0]
        secs = []
        for i in range(shnum):
            o = shoff + i * shentsize
            name, typ, flags, addr, off, size, link, info, align, entsize = struct.unpack_from('<IIQQQQIIQQ', b, o)
            secs.append(dict(name=name, type=typ, addr=addr, off=off, size=size, link=link, entsize=entsize))
        stro = secs[shstrndx]['off']
        for s in secs:
            e = b.index(b'\0', stro + s['name']); s['name'] = b[stro + s['name']:e].decode()
        self.secs = {s['name']: s for s in secs}
        # program headers for vaddr->offset
        phoff = u64(b, 0x20); phentsize = struct.unpack_from('<H', b, 0x36)[0]; phnum = struct.unpack_from('<H', b, 0x38)[0]
        self.loads = []
        for i in range(phnum):
            o = phoff + i * phentsize
            typ, flags, off, vaddr, paddr, filesz, memsz, align = struct.unpack_from('<IIQQQQQQ', b, o)
            if typ == 1: self.loads.append((vaddr, off, filesz, memsz))
        self._syms()
        self._relocs()
        self._eh()

    def v2o(self, va):
        for vaddr, off, filesz, memsz in self.loads:
            if vaddr <= va < vaddr + filesz: return off + (va - vaddr)
        return None

    def read(self, va, n):
        o = self.v2o(va)
        return None if o is None else self.b[o:o + n]

    def cstr(self, va, maxlen=200):
        o = self.v2o(va)
        if o is None: return None
        e = self.b.find(b'\0', o, o + maxlen)
        if e < 0: return None
        s = self.b[o:e]
        if len(s) < 2: return None
        try:
            t = s.decode('utf-8')
        except UnicodeDecodeError:
            return None
        if sum(1 for c in t if c.isprintable() or c in '\n\t') < len(t): return None
        return t

    def _syms(self):
        ds = self.secs['.dynsym']; st = self.secs['.dynstr']
        self.dynsyms = []
        names = []
        for i in range(ds['size'] // 24):
            o = ds['off'] + i * 24
            nm, info, other, shndx, value, size = struct.unpack_from('<IBBHQQ', self.b, o)
            e = self.b.index(b'\0', st['off'] + nm)
            name = self.b[st['off'] + nm:e].decode()
            self.dynsyms.append((name, value, size, info & 0xf, shndx))
            names.append(name)
        dem = subprocess.run(['c++filt'], input='\n'.join(names), capture_output=True, text=True).stdout.split('\n')
        self.dem = {names[i]: dem[i] for i in range(len(names))}
        self.addr2sym = {}
        for (name, value, size, typ, shndx) in self.dynsyms:
            if value and shndx != 0:
                self.addr2sym.setdefault(value, (self.dem.get(name, name), size, typ))

    def _aps2(self, s):
        b = self.b; p = [s['off'] + 4]
        def sleb():
            r = 0; sh = 0
            while True:
                c = b[p[0]]; p[0] += 1
                r |= (c & 0x7f) << sh; sh += 7
                if not c & 0x80:
                    if c & 0x40: r -= 1 << sh
                    return r
        out = []
        num = sleb(); off = sleb(); addend = 0
        while num > 0:
            gs = sleb(); gf = sleb(); od = 0; info = 0
            if gf & 2: od = sleb()
            if gf & 1: info = sleb()
            if (gf & 8) and (gf & 4): addend += sleb()
            elif not gf & 8: addend = 0
            for _ in range(gs):
                off += od if gf & 2 else sleb()
                if not gf & 1: info = sleb()
                if (gf & 8) and not (gf & 4): addend += sleb()
                out.append((off & 0xffffffffffffffff, info & 0xffffffffffffffff, addend))
            num -= gs
        return out

    def _relocs(self):
        self.got = {}  # GOT slot va -> symbol name
        for sec in ('.rela.plt', '.rela.dyn'):
            s = self.secs.get(sec)
            if not s: continue
            if self.b[s['off']:s['off'] + 4] == b'APS2':
                ents = self._aps2(s)
            else:
                ents = [struct.unpack_from('<QQq', self.b, s['off'] + i * 24) for i in range(s['size'] // 24)]
            for off, info, addend in ents:
                sym = info >> 32; typ = info & 0xffffffff
                if sym:
                    name = self.dynsyms[sym][0]
                    self.got[off] = self.dem.get(name, name)
                elif typ == 1027:  # R_AARCH64_RELATIVE
                    self.got.setdefault(off, 'RELATIVE->0x%x' % addend)
        # PLT stubs: decode adrp/ldr to GOT
        self.plt = {}
        p = self.secs['.plt']
        md = capstone.Cs(capstone.CS_ARCH_ARM64, capstone.CS_MODE_ARM)
        code = self.b[p['off']:p['off'] + p['size']]
        insns = list(md.disasm(code, p['addr']))
        for i, ins in enumerate(insns):
            if ins.mnemonic == 'adrp' and i + 1 < len(insns) and insns[i + 1].mnemonic == 'ldr':
                page = int(ins.op_str.split('#')[-1], 16)
                m = re.search(r'#(0x[0-9a-f]+|\d+)\]', insns[i + 1].op_str)
                if not m: continue
                slot = page + int(m.group(1), 0)
                self.plt[ins.address] = self.got.get(slot, 'GOT@0x%x' % slot)

    def _eh(self):
        s = self.secs.get('.eh_frame_hdr')
        self.fstarts = []
        if not s: return
        b = self.b; o = s['off']; base = s['addr']
        ver, enc_ptr, enc_cnt, enc_tab = b[o], b[o + 1], b[o + 2], b[o + 3]
        assert enc_tab == 0x3b, hex(enc_tab)  # datarel sdata4
        p = 4
        # eh_frame_ptr (enc_ptr), fde_count (enc_cnt) assume sdata4/udata4
        p += 4
        cnt = u32(b, o + p); p += 4
        for i in range(cnt):
            loc = struct.unpack_from('<i', b, o + p + i * 8)[0]
            self.fstarts.append(base + loc)
        self.fstarts.sort()

    def func_bounds(self, va):
        import bisect
        i = bisect.bisect_right(self.fstarts, va) - 1
        if i < 0: return None
        st = self.fstarts[i]
        en = self.fstarts[i + 1] if i + 1 < len(self.fstarts) else self.secs['.text']['addr'] + self.secs['.text']['size']
        sym = self.addr2sym.get(st)
        if sym and sym[1]: en = min(en, st + sym[1]) if st + sym[1] > va else en
        return st, en

    def name_at(self, va):
        if va in self.plt: return 'PLT:' + self.plt[va]
        s = self.addr2sym.get(va)
        if s: return s[0]
        b = self.func_bounds(va)
        if b and b[0] in self.addr2sym and b[0] != va:
            return '%s+0x%x' % (self.addr2sym[b[0]][0], va - b[0])
        if b and b[0] == va: return 'sub_%x' % va
        return None


def annotate(elf, start, end, out):
    md = capstone.Cs(capstone.CS_ARCH_ARM64, capstone.CS_MODE_ARM)
    md.detail = True
    code = elf.read(start, end - start)
    regs = {}  # reg -> value from adrp (page) or adrp+add (full)
    calls = []
    strs = []
    title = elf.name_at(start) or 'sub_%x' % start
    out.write('\n==== %s  [0x%x-0x%x] ====\n' % (title, start, end))
    for ins in md.disasm(code, start):
        note = ''
        mn = ins.mnemonic; ops = ins.operands
        try:
            if mn == 'adrp':
                regs[ops[0].reg] = ops[1].imm
            elif mn == 'add' and len(ops) == 3 and ops[2].type == A.ARM64_OP_IMM and ops[1].type == A.ARM64_OP_REG and ops[1].reg in regs:
                v = regs[ops[1].reg] + ops[2].imm
                regs[ops[0].reg] = v
                s = elf.cstr(v)
                if s is not None:
                    note = '"%s"' % s.replace('\n', '\\n')[:160]; strs.append((ins.address, s))
                else:
                    n = elf.name_at(v) or elf.got.get(v)
                    note = '=0x%x %s' % (v, n or '')
            elif mn.startswith('ldr') and len(ops) == 2 and ops[1].type == A.ARM64_OP_MEM and ops[1].mem.base in regs and ops[1].mem.index == 0:
                v = regs[ops[1].mem.base] + ops[1].mem.disp
                if v in elf.got:
                    note = '[GOT %s]' % elf.got[v]
                else:
                    rn = ins.reg_name(ops[0].reg)
                    raw = elf.read(v, 8)
                    if raw:
                        if rn.startswith('s'): note = '=%r (float @0x%x)' % (struct.unpack('<f', raw[:4])[0], v)
                        elif rn.startswith('d'): note = '=%r (double @0x%x)' % (struct.unpack('<d', raw)[0], v)
                        elif rn.startswith('q'): note = '=q @0x%x' % v
                        elif rn.startswith('w'): note = '=0x%x (@0x%x)' % (struct.unpack('<I', raw[:4])[0], v)
                        else: note = '=0x%x (@0x%x)' % (struct.unpack('<Q', raw)[0], v)
                if ops[0].reg in regs: del regs[ops[0].reg]
            elif mn in ('bl', 'b') or mn.startswith('b.') or mn in ('cbz', 'cbnz', 'tbz', 'tbnz'):
                tgt = ops[-1].imm if ops and ops[-1].type == A.ARM64_OP_IMM else None
                if tgt is not None:
                    n = elf.name_at(tgt)
                    if mn == 'bl' or (mn == 'b' and not (start <= tgt < end)):
                        note = '-> %s' % (n or 'sub_%x' % tgt); calls.append((ins.address, n or 'sub_%x' % tgt))
            elif mn == 'blr' or mn == 'br':
                note = '(indirect)'
            if ins.op_str and len(ops) and ops[0].type == A.ARM64_OP_REG and mn not in ('adrp', 'add', 'str', 'stp', 'cmp', 'tst', 'cbz', 'cbnz', 'tbz', 'tbnz', 'b', 'bl'):
                if ops[0].reg in regs and mn not in ('ldr',): del regs[ops[0].reg]
        except Exception as e:  # never stop annotating on odd encodings
            note = '(ann err %s)' % e
        out.write('  %06x: %-8s %-40s %s\n' % (ins.address, mn, ins.op_str, ('// ' + note) if note else ''))
    return calls, strs


def main():
    elfp, outdir = sys.argv[1], sys.argv[2]
    elf = Elf(elfp)
    os.makedirs(outdir, exist_ok=True)
    for spec in sys.argv[3:]:
        targets = []
        if spec.startswith('0x'):
            b = elf.func_bounds(int(spec, 16)); targets.append(b)
        else:
            allm = spec.startswith('*'); pat = spec.lstrip('*')
            for va, (n, sz, typ) in sorted(elf.addr2sym.items()):
                if typ == 2 and pat in n:
                    b = elf.func_bounds(va)
                    targets.append((va, b[1] if b else va + sz))
                    if not allm: break
        for (st, en) in targets:
            nm = elf.name_at(st) or 'sub_%x' % st
            safe = re.sub(r'[^A-Za-z0-9_]+', '_', nm.split('(')[0])[:60] + '_%x' % st
            with open(os.path.join(outdir, safe + '.ann'), 'w') as f:
                calls, strs = annotate(elf, st, en, f)
                f.write('\n-- calls --\n'); [f.write('  %06x %s\n' % c) for c in calls]
            print('%s -> %s.ann (%d bytes code, %d calls)' % (nm[:90], safe, en - st, len(calls)))

if __name__ == '__main__':
    main()
