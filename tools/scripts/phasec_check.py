#!/usr/bin/env python3
"""Read-only assertions for Phase C (no device access; works on files mtkclient/adb produced).
Exit 0 = check passed, 1 = failed (message on stderr). Run with: python3.12 -I phasec_check.py <check> ...

  gpt   <printgpt-log>        live GPT offsets/lengths of the partitions Phase C touches == May printgpt
  misc  <misc.bin>            BCB empty, bootctrl CRC ok, suffix _b, B active+successful, A not preferred
  super <super-head.bin>      live LP metadata slot 1 (B) == backup; system_b extent 0 == expected
  zeros <file> [len]          file is all zero (and has the given length)
  boot  <candidate.img> <stock-boot.img>   header v2 sanity; kernel+DTB byte-identical to stock
  avb   <vbmeta.bin>          print AVB header flags / rollback index (info only)
"""
import hashlib, struct, sys, zlib

# Absolute byte offsets in the UFS user LU, from printgpt.txt (2026-05-25) — must not have moved.
GPT = {'misc': (0x8000, 0x80000), 'vbmeta_b': (0xa208000, 0x800000), 'metadata': (0xba10000, 0x25f0000),
       'seccfg': (0xf000000, 0x800000), 'boot_b': (0x45d00000, 0x2800000), 'super': (0x4f000000, 0x300000000),
       'userdata': (0x34f000000, 0x1a7aff8000)}
LP_SLOT1_SHA = 'bdb54c65f726cf1e22e057222a20787127c0f519659760a5e32b0f29c1ec62a3'   # backup super.bin, slot 1
SYSTEM_B_EXT0 = (0x23d00000, 0x12d780000)                                            # offset in super, length


def fail(msg):
    print('CHECK FAILED: ' + msg, file=sys.stderr); sys.exit(1)


def ok(msg):
    print('CHECK OK: ' + msg)


def check_gpt(log):
    seen = {}
    for line in open(log, errors='replace'):
        name = line.split(':', 1)[0].strip()
        if name in GPT and 'Offset' in line:
            parts = dict(p.strip().split(' ', 1) for p in line.split(':', 1)[1].split(',') if ' ' in p.strip())
            seen[name] = (int(parts['Offset'], 16), int(parts['Length'], 16))
    for name, want in GPT.items():
        if name not in seen: fail(f'{name} missing from printgpt output')
        if seen[name] != want: fail(f'{name}: live {tuple(map(hex, seen[name]))} != expected {tuple(map(hex, want))}')
    ok('GPT offsets/lengths for ' + ', '.join(GPT) + ' unchanged')


def check_misc(path):
    b = open(path, 'rb').read()
    if len(b) != 0x80000: fail(f'misc size {len(b)}')
    if b[0:32].strip(b'\0'): fail(f'BCB command not empty: {b[0:32]!r}')
    bc = b[0x800:0x820]
    if struct.unpack_from('<I', bc, 4)[0] != 0x42414342: fail('bootctrl magic')
    if struct.unpack_from('<I', bc, 28)[0] != zlib.crc32(bc[:28]) & 0xffffffff: fail('bootctrl CRC')
    if bc[0:3] != b'_b\0': fail(f'slot_suffix {bc[0:4]!r}')
    a, bb = (struct.unpack_from('<H', bc, 12 + 2 * i)[0] for i in range(2))
    pa, pb = a & 0xf, bb & 0xf
    if not (pb == 15 and pb > pa and (bb >> 7) & 1 and (bb >> 4) & 7 > 0): fail(f'slot b not active+successful (a={a:#x} b={bb:#x})')
    if struct.unpack_from('<I', b, 0x1000)[0] != 0: print('NOTE: misc+0x1000 (LK OTA-result word) is non-zero')
    ok(f'misc: BCB empty, suffix _b, prio a={pa} b={pb}, b successful, crc ok')


def check_super(path):
    f = open(path, 'rb'); f.seek(4096); g = f.read(4096)
    if struct.unpack_from('<I', g, 0)[0] != 0x616c4467: fail('LP geometry magic')
    mms = struct.unpack_from('<I', g, 40)[0]
    f.seek(4096 + 8192 + mms); h = f.read(mms)
    if h[:4] != b'0PLA': fail('LP metadata slot 1 magic')
    hsize, tsize = struct.unpack_from('<I', h, 8)[0], struct.unpack_from('<I', h, 44)[0]
    sha = hashlib.sha256(h[:hsize + tsize]).hexdigest()
    if sha != LP_SLOT1_SHA: fail(f'LP slot-1 metadata changed: {sha}')
    t = h[hsize:hsize + tsize]
    po, pn, ps = struct.unpack_from('<III', h, 80)    # partition table descriptor
    eo, en, es = struct.unpack_from('<III', h, 92)    # extent table descriptor
    for i in range(pn):
        name, attrs, fei, ne, gi = struct.unpack_from('<36sIIII', t, po + i * ps)
        if name.split(b'\0')[0] == b'system_b':
            nsec, ttype, tdata, tsrc = struct.unpack_from('<QIQI', t, eo + fei * es)
            ext0 = (tdata * 512, nsec * 512)
            if ttype != 0 or ext0 != SYSTEM_B_EXT0: fail(f'system_b extent 0 is {ext0}, type {ttype}')
            ok(f'LP slot-1 metadata == backup ({sha[:16]}…); system_b extent0 = super+{ext0[0]:#x}, len {ext0[1]:#x}')
            return
    fail('system_b not found in slot-1 metadata')


def check_zeros(path, length=None):
    n = 0
    with open(path, 'rb') as f:
        while True:
            c = f.read(1 << 22)
            if not c: break
            if c.count(0) != len(c): fail(f'{path}: non-zero byte near offset {n + next(i for i, x in enumerate(c) if x)}')
            n += len(c)
    if length is not None and n != int(length, 0): fail(f'{path}: length {n} != {length}')
    ok(f'{path}: {n} bytes, all zero')


def boot_sections(path):
    b = open(path, 'rb').read()
    if b[:8] != b'ANDROID!': fail(f'{path}: not an Android boot image')
    ks, rs, ss = (struct.unpack_from('<I', b, o)[0] for o in (8, 16, 24))
    page, ver = struct.unpack_from('<II', b, 36)
    if ver != 2: fail(f'{path}: header version {ver} (expected 2)')
    rdo_size = struct.unpack_from('<I', b, 1632)[0]; dtb_size = struct.unpack_from('<I', b, 1648)[0]
    al = lambda n: (n + page - 1) // page * page
    off = page; kern = b[off:off + ks]; off += al(ks)
    rd = b[off:off + rs]; off += al(rs) + al(ss) + al(rdo_size)
    dtb = b[off:off + dtb_size]
    return b, kern, rd, dtb


def check_boot(cand, stock):
    cb, ck, cr, cd = boot_sections(cand); sb, sk, sr, sd = boot_sections(stock)
    if len(cb) > 0x2800000: fail(f'{cand}: {len(cb)} bytes > boot_b partition')
    if ck != sk: fail('kernel differs from stock boot_b')
    if cd != sd: fail('DTB differs from stock boot_b')
    ok(f'boot: header v2, kernel ({len(ck)} B) and DTB ({len(cd)} B) identical to stock; ramdisk {len(sr)} -> {len(cr)} B; image {len(cb)} B')


def check_avb(path):
    b = open(path, 'rb').read(256)
    if b[:4] != b'AVB0': fail('no AVB0 magic')
    print(f'flags={struct.unpack_from(">I", b, 120)[0]} rollback_index={struct.unpack_from(">Q", b, 112)[0]}')


if __name__ == '__main__':
    cmd, args = sys.argv[1], sys.argv[2:]
    {'gpt': check_gpt, 'misc': check_misc, 'super': check_super, 'zeros': check_zeros,
     'boot': check_boot, 'avb': check_avb}[cmd](*args)
