#!/usr/bin/env python3
"""Extract logical partitions from a super image using a chosen metadata slot (0=A, 1=B).
Usage: lpextract_slot.py SUPER_IMG SLOT OUT_DIR [partition ...]   (read-only on input)"""
import struct, sys, os, hashlib
sup, slot, out = sys.argv[1], int(sys.argv[2]), sys.argv[3]; want = sys.argv[4:]
f = open(sup, 'rb'); f.seek(4096); g = f.read(4096); mms = struct.unpack_from('<I', g, 40)[0]
cstr = lambda b: b.split(b'\x00')[0].decode()
base = 4096 + 8192 + slot*mms; f.seek(base); h = f.read(mms)
assert h[:4] == b'0PLA', 'bad metadata magic'
hsize = struct.unpack_from('<I', h, 8)[0]; tsize = struct.unpack_from('<I', h, 44)[0]
(po,pn,ps),(eo,en,es),(go,gn,gs),(bo,bn,bs) = [struct.unpack_from('<III', h, 80+12*i) for i in range(4)]
t = h[hsize:hsize+tsize]; os.makedirs(out, exist_ok=True)
for i in range(pn):
    name, attrs, fei, ne, gi = struct.unpack_from('<36sIIII', t, po+i*ps); name = cstr(name)
    exts = [struct.unpack_from('<QIQI', t, eo+(fei+j)*es) for j in range(ne)]
    if want and name not in want: continue
    if not exts: print(f"{name}: no extents, skipped"); continue
    sha = hashlib.sha256(); total = 0
    with open(os.path.join(out, name + '.img'), 'wb') as o:
        for ns, tt, td, src in exts:
            assert tt == 0, f'{name}: non-linear extent'
            f.seek(td*512); rem = ns*512
            while rem:
                buf = f.read(min(1<<24, rem)); o.write(buf); sha.update(buf); rem -= len(buf); total += len(buf)
    print(f"{name}: {total} bytes, {len(exts)} extents, sha256={sha.hexdigest()}")
