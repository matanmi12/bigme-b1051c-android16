#!/usr/bin/env python3
"""Generate shim/lut_data.h from the stock Bigme colour LUTs.

The two 512x512 LUT bitmaps (8x8 tiles = 64^3 RGB cube) are Bigme's and are not distributed here. Copy them from
YOUR device's stock system image, e.g. `adb pull /system/etc/g_c001` and `/system/etc/g_c002` on the stock ROM, or
from a dump of system_b (`debugfs -R "dump /system/etc/g_c001 g_c001.bmp" system_b.img`), then:

    python3 -I make_lut_header.py g_c001.bmp g_c002.bmp > ../lut_data.h

Output layout: top-down RGB, index (y*512+x)*3, as used by the shim's colour pipeline (Bigme SF lutMap()).
"""
import struct
import sys


def load(path):
    d = open(path, "rb").read()
    if d[:2] != b"BM":
        sys.exit(path + ": not a BMP")
    off = struct.unpack_from("<I", d, 10)[0]
    w, h = struct.unpack_from("<ii", d, 18)
    bpp = struct.unpack_from("<H", d, 28)[0]
    if abs(w) != 512 or abs(h) != 512 or bpp not in (24, 32):
        sys.exit("%s: expected 512x512 24/32-bit, got %dx%d %d-bit" % (path, w, h, bpp))
    bpr = bpp // 8
    stride = (512 * bpr + 3) & ~3
    out = bytearray(512 * 512 * 3)
    for y in range(512):
        src_row = (511 - y) if h > 0 else y            # positive height = bottom-up rows
        base = off + src_row * stride
        for x in range(512):
            b, g, r = d[base + x * bpr], d[base + x * bpr + 1], d[base + x * bpr + 2]
            i = (y * 512 + x) * 3
            out[i], out[i + 1], out[i + 2] = r, g, b
    return out


def emit(name, data):
    print("static const uint8_t %s[512 * 512 * 3] = {" % name)
    for i in range(0, len(data), 48):
        print(",".join(str(v) for v in data[i:i + 48]) + ("," if i + 48 < len(data) else ""))
    print("};")


if len(sys.argv) != 3:
    sys.exit(__doc__)
print("// Generated from stock /system/etc/g_c001 and g_c002 (Bigme B1051C_R_4.9.0): 512x512 3D LUTs (64^3 as 8x8 tiles).")
print("// Layout: top-down RGB, index (y*512+x)*3. Used by the colour pipeline exactly like Bigme SurfaceFlinger lutMap().")
print("#pragma once")
print("#include <stdint.h>")
emit("kLutC001", load(sys.argv[1]))
emit("kLutC002", load(sys.argv[2]))
