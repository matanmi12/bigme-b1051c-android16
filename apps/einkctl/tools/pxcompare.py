"""Light pixel check for E-Ink Control 0.14.0: compare a pre-install capture (0.13.4) with the same screen after.

usage: python3.14 -I pxcompare.py PRE.png POST.png [--skip-top PX]
Prints the size, the number of differing pixels below the top PX rows (default 80: the status bar with the clock),
and the bounding box of those differences. Exit 0 = identical below the status bar, 1 = differences (box printed),
2 = sizes differ.
"""
import sys

from PIL import Image, ImageChops

args = sys.argv[1:]
skip = 80
if "--skip-top" in args:
    i = args.index("--skip-top")
    skip = int(args[i + 1])
    del args[i:i + 2]
a, b = (Image.open(p).convert("RGB") for p in args[:2])
print("sizes", a.size, b.size)
if a.size != b.size:
    sys.exit(2)
w, h = a.size
box = (0, skip, w, h)
d = ImageChops.difference(a.crop(box), b.crop(box))
bb = d.getbbox()
r, g, bl = d.split()
m = ImageChops.lighter(ImageChops.lighter(r, g), bl)          # per pixel: the largest channel difference
n = d.width * d.height - m.histogram()[0]
if bb is None:
    print("IDENTICAL below y=%d" % skip)
    sys.exit(0)
print("DIFFERENT: %d pixels, box x %d..%d, y %d..%d (full-image coordinates)"
      % (n, bb[0], bb[2], bb[1] + skip, bb[3] + skip))
sys.exit(1)
