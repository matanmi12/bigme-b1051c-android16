#!/usr/bin/env python3
"""Print the LP metadata (geometry, partitions, extents as absolute byte offsets) of a super image or of a
super-head dump, for a chosen metadata slot (0=A, 1=B). Read-only.
Usage: lp_extents.py SUPER_IMG_OR_HEAD SLOT [--json]
A head dump only needs the first 4096 + 8192 + 2*slots*max_metadata_size bytes (1 MiB is plenty)."""
import hashlib, json, struct, sys
img, slot = sys.argv[1], int(sys.argv[2])
f = open(img, 'rb'); f.seek(4096); g = f.read(4096)
assert struct.unpack_from('<I', g, 0)[0] == 0x616c4467, 'bad geometry magic'
mms, nslots, lbs = struct.unpack_from('<III', g, 40)
cstr = lambda b: b.split(b'\x00')[0].decode()
base = 4096 + 8192 + slot * mms; f.seek(base); h = f.read(mms)
assert h[:4] == b'0PLA', 'bad metadata magic'
major, minor, hsize = struct.unpack_from('<HHI', h, 4)
tsize = struct.unpack_from('<I', h, 44)[0]
(po, pn, ps), (eo, en, es), (go, gn, gs), (bo, bn, bs) = [struct.unpack_from('<III', h, 80 + 12 * i) for i in range(4)]
t = h[hsize:hsize + tsize]
out = {'image': img, 'slot': slot, 'metadata_max_size': mms, 'metadata_slots': nslots, 'logical_block_size': lbs,
       'header_version': f'{major}.{minor}', 'metadata_sha256': hashlib.sha256(h[:hsize + tsize]).hexdigest(),
       'groups': [], 'partitions': []}
for i in range(gn):
    name, flags, maxsz = struct.unpack_from('<36sIQ', t, go + i * gs); out['groups'].append({'name': cstr(name), 'max_size': maxsz})
for i in range(pn):
    name, attrs, fei, ne, gi = struct.unpack_from('<36sIIII', t, po + i * ps)
    exts = []
    for j in range(ne):
        nsec, ttype, tdata, tsrc = struct.unpack_from('<QIQI', t, eo + (fei + j) * es)
        exts.append({'offset': tdata * 512, 'size': nsec * 512, 'linear': ttype == 0, 'block_device': tsrc})
    out['partitions'].append({'name': cstr(name), 'attributes': attrs, 'group': gi,
                              'size': sum(e['size'] for e in exts), 'extents': exts})
if '--json' in sys.argv:
    print(json.dumps(out, indent=1))
else:
    print(f"slot {slot}: header v{out['header_version']}, metadata sha256 {out['metadata_sha256']}")
    for p in out['partitions']:
        ex = ', '.join(f"[{e['offset']:#x}+{e['size']:#x}{'' if e['linear'] else ' NONLINEAR'}]" for e in p['extents'])
        print(f"  {p['name']:<14} {p['size']:>12} B  {ex}")
