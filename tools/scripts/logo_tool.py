#!/usr/bin/env python3
"""MTK logo partition tool for the Bigme B1051C (layout: research/boot-logo/LOGO-FORMAT.md). Offline only.

  logo_tool.py info   LOGO.bin
  logo_tool.py unpack LOGO.bin OUT_DIR                      # full-screen images -> PNG (needs Pillow)
  logo_tool.py pack   LOGO.bin OUT.bin [--img N=FILE.png ...] [--battery-from-2 N]
  logo_tool.py check  LOGO.bin                              # pack without changes must give the same bytes

Layout (verified on the 2026-05 dump, sha 84983b34…): 512-byte MTK header (magic 0x58881688, dsize at +4 = size of
the image index + streams, ext header at +48) | at 0x200: u32 count, u32 total, count x u32 offsets (relative to
0x200) | zlib(BGRA 32bpp) streams | zero padding to 16 bytes | signature trailer (MTK "cert1" + "cert2" blocks,
0xE86 bytes; LK logs img_auth_required = 0 and does not enforce it) | zero fill to the partition size (15 MiB).
Pack keeps every untouched stream byte-identical and moves the trailer behind the new content.
--battery-from-2 N: image N gets the low-battery icon of the stock image 2 (pixels where stock 2 differs from stock 0).
Writing the partition is a separate, approved step (roadmap wave 7); this tool never touches a device.
"""
import struct, sys, zlib

MAGIC = 0x58881688
W, H = 1860, 2480
PART_SIZE = 0xF00000


def parse(b):
    magic, dsize = struct.unpack_from("<II", b, 0)
    if magic != MAGIC:
        sys.exit("not an MTK logo image (magic %08x)" % magic)
    count, total = struct.unpack_from("<II", b, 0x200)
    offs = list(struct.unpack_from("<%dI" % count, b, 0x208))
    if total != dsize:
        sys.exit("index total %d != header dsize %d" % (total, dsize))
    ends = offs[1:] + [total]
    streams = [b[0x200 + o:0x200 + e] for o, e in zip(offs, ends)]
    end = 0x200 + total
    t0 = (end + 15) & ~15
    if any(b[end:t0]):
        sys.exit("unexpected data in the padding after the content")
    nz = len(b.rstrip(b"\0"))
    trailer = b[t0:nz] if nz > t0 else b""
    return {"header": b[:0x200], "streams": streams, "trailer": trailer, "size": len(b)}


def build(p):
    count = len(p["streams"])
    index_len = 8 + 4 * count
    offs, pos = [], index_len
    for s in p["streams"]:
        offs.append(pos)
        pos += len(s)
    total = pos
    out = bytearray(p["header"])
    struct.pack_into("<I", out, 4, total)
    out += struct.pack("<II", count, total) + struct.pack("<%dI" % count, *offs)
    for s in p["streams"]:
        out += s
    out += b"\0" * (((len(out) + 15) & ~15) - len(out))
    out += p["trailer"]
    if len(out) > p["size"]:
        sys.exit("content %d B does not fit the %d B partition" % (len(out), p["size"]))
    return bytes(out) + b"\0" * (p["size"] - len(out)), total


def bgra_to_image(raw):
    from PIL import Image
    return Image.frombuffer("RGBA", (W, H), raw, "raw", "BGRA", 0, 1).convert("RGB")


def image_to_stream(img):
    from PIL import Image
    img = img.convert("RGB")
    if img.size != (W, H):
        sys.exit("image must be %dx%d, got %dx%d" % (W, H, *img.size))
    raw = img.convert("RGBA").tobytes("raw", "BGRA")
    return zlib.compress(raw, 9)


def main():
    cmd, src = sys.argv[1], sys.argv[2]
    b = open(src, "rb").read()
    p = parse(b)
    if cmd == "info":
        for i, s in enumerate(p["streams"]):
            raw_len = len(zlib.decompress(s))
            print(f"{i:2d}  zlib {len(s):8d}  raw {raw_len:9d}" + ("  full screen" if raw_len == W * H * 4 else ""))
        print("trailer", len(p["trailer"]), "B; partition", p["size"], "B")
    elif cmd == "check":
        rebuilt, _ = build(p)
        print("round trip identical:", rebuilt == b)
        sys.exit(0 if rebuilt == b else 1)
    elif cmd == "unpack":
        import os
        out = sys.argv[3]
        os.makedirs(out, exist_ok=True)
        for i, s in enumerate(p["streams"]):
            raw = zlib.decompress(s)
            if len(raw) == W * H * 4:
                bgra_to_image(raw).save(f"{out}/logo-{i:02d}.png")
    elif cmd == "pack":
        from PIL import Image
        dst, args = sys.argv[3], sys.argv[4:]
        battery_for = []
        while args:
            a = args.pop(0)
            if a == "--img":
                n, f = args.pop(0).split("=", 1)
                p["streams"][int(n)] = image_to_stream(Image.open(f))
            elif a == "--battery-from-2":
                battery_for.append(int(args.pop(0)))
        if battery_for:
            stock = parse(b)
            s0 = bgra_to_image(zlib.decompress(stock["streams"][0]))
            s2 = bgra_to_image(zlib.decompress(stock["streams"][2]))
            from PIL import ImageChops
            # Only the top-right corner: stock 0 and 2 also differ slightly elsewhere (re-encoded logo), and
            # pasting that would bring Bigme's artwork back. LOGO-FORMAT: icon at about x 1690-1830, y 20-95.
            corner = (1500, 0, W, 250)
            diff = ImageChops.difference(s0.crop(corner), s2.crop(corner)).convert("L").point(lambda v: 255 if v > 24 else 0)
            bx = diff.getbbox()
            if not bx:
                sys.exit("no battery icon found in the stock image 2 corner")
            box = (bx[0] + corner[0] - 4, bx[1] + corner[1] - 4, bx[2] + corner[0] + 4, bx[3] + corner[1] + 4)
            for n in battery_for:
                img = bgra_to_image(zlib.decompress(p["streams"][n]))
                img.paste(s2.crop(box), box[:2])
                p["streams"][n] = image_to_stream(img)
                print(f"image {n}: low-battery icon from stock image 2, box {box}")
        rebuilt, total = build(p)
        open(dst, "wb").write(rebuilt)
        print(f"wrote {dst}: content {0x200 + total} B + trailer {len(p['trailer'])} B of {p['size']} B")
    else:
        sys.exit(__doc__)


if __name__ == "__main__":
    main()
