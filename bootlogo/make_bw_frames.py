#!/usr/bin/env python3
"""InKognito badge as black pen on white + boot-animation frames that colour it in, one palette colour per frame
(owner 2026-10-19: "black pen on a white canvas with a little gray shading", then bordeaux / steel / teal / gold;
colour parts, not dots). Needs Pillow + numpy.

Method: every pixel of the owner's logo (boot logo.jpeg) is assigned to one of 24 k-means clusters (computed on the
1600 px logo; indices below come from that run and are deterministic). Each cluster has a family and a pen tone:
ink and the black cat's fur -> pure black (no red cast on Kaleido), colour fills -> white, metal / shadows -> three
light grays. A frame paints the clusters of its 'on' families in the flat palette colour, but only inside that
family's own area of the badge (radius from the badge centre), so the steel of the hat does not spill into the
riveted silver ring and the teal of the ring does not tint the lens. Eyes: light gold from the gold step on.
Mapping runs at 2048 px and is scaled to 1600 px (anti-aliased edges), placed like make_bootlogo.py.

Usage: make_bw_frames.py SRC_SQUARE_LOGO OUT_DIR   -> bw-step0.png (B&W) .. bw-step4.png (all colours), bw-sheet.png
"""
import os, sys
import numpy as np
from PIL import Image, ImageDraw, ImageFilter

W, H, LOGO_W, CENTER_Y = 1860, 2480, 1600, 1160
WHITE = (255, 255, 255)
BORDEAUX, TEAL, GOLD, STEEL = (0x93, 0x28, 0x45), (0x19, 0x4D, 0x50), (0xCD, 0x8E, 0x2C), (0x5D, 0x7E, 0x91)
GOLD_SHADOW, LIGHT_GOLD = (0xA8, 0x6F, 0x1C), (0xF7, 0xBF, 0x5D)
ORDER = ['gold', 'steel', 'teal', 'bordeaux']          # one more colour per frame

PAPER = [5, 8, 2, 3, 11, 1, 6, 7, 4, 0]
# cluster -> (family, pen tone, colour when the family is on)
MAP = {i: ('paper', 255, None) for i in PAPER}
MAP.update({
    23: ('ink', 0, None), 22: ('ink', 0, None), 21: ('ink', 0, None), 20: ('ink', 0, None),
    19: ('ink', 0, None),                       # the black cat's fur
    18: ('ink', 150, None),                     # fur highlights / dark metal
    17: ('bordeaux', 255, BORDEAUX),
    16: ('teal', 255, TEAL),
    13: ('gold', 255, GOLD), 14: ('gold', 205, GOLD_SHADOW),
    15: ('steel', 175, STEEL),
    12: ('metal', 200, None), 9: ('metal', 225, None), 10: ('metal', 242, None),
})
# where each family may be coloured (1600 px scale). Steel: only inside radius 470 from the badge centre (hat and
# "InK"; the riveted ring's metal shading shares the cluster). Teal: everywhere except the lens (fitted circle of
# its rim) and the dark hat band, which share the teal cluster but are not teal in the logo.
CENTER_1600 = (799.5, 804.0)
AREA = {'steel': (0, 470)}
EXCLUDE = {'teal': [('circle', 922, 715, 142), ('box', 590, 490, 1110, 610)]}
EYE_BOXES_1600 = [(630, 675, 715, 745), (790, 670, 900, 745)]   # left eye, right eye behind the lens
# the "InK" letters are steel in the logo but shaded with the metal clusters: steel inside these boxes (they stop
# below the cat's claws, which sit just above the K)
LETTER_BOXES_1600 = [(345, 1095, 600, 1240), (575, 1040, 725, 1240)]
HAT_BAND_1600 = (590, 490, 1110, 610)                              # dark band of the hat: black pen


def main():
    src, outdir = sys.argv[1], sys.argv[2]
    os.makedirs(outdir, exist_ok=True)
    logo = Image.open(src).convert('RGB')
    small = logo.resize((LOGO_W, LOGO_W), Image.LANCZOS)
    pal = small.quantize(colors=24, method=Image.Quantize.MEDIANCUT, kmeans=3)
    q = np.asarray(logo.quantize(palette=pal, dither=Image.Dither.NONE))
    a = np.asarray(logo).astype(np.float32) / 255
    s = logo.width / LOGO_W
    yy, xx = np.mgrid[0:logo.height, 0:logo.width]
    r = np.hypot(xx - CENTER_1600[0] * s, yy - CENTER_1600[1] * s) / s

    # eyes: greenish-yellow, light pixels inside the two eye boxes
    mx, mn = a.max(2), a.min(2)
    hue = np.zeros_like(mx)
    d = mx - mn + 1e-6
    rr, gg, bb = a[..., 0], a[..., 1], a[..., 2]
    hue = np.where(mx == gg, 60 * (2 + (bb - rr) / d), np.where(mx == rr, 60 * ((gg - bb) / d % 6), 60 * (4 + (rr - gg) / d)))
    eye = (hue >= 45) & (hue <= 115) & (d > 0.18) & (mx > 0.45)
    box = np.zeros_like(eye)
    for x0, y0, x1, y1 in EYE_BOXES_1600:
        box[int(y0 * s):int(y1 * s), int(x0 * s):int(x1 * s)] = True
    eye &= box

    tone = np.full(q.shape, 255, np.uint8)
    fam = np.empty(q.shape, object)
    fam[:] = 'paper'
    colour = np.zeros(q.shape + (3,), np.uint8)
    for c, (f, t, col) in MAP.items():
        m = q == c
        tone[m] = t
        fam[m] = f
        if col: colour[m] = col
    for f, band in AREA.items():
        outside = (fam == f) & ~((r >= band[0]) & (r <= band[1]))
        fam[outside] = 'off'                                      # stays in its pen tone
    for f, zones in EXCLUDE.items():
        for z in zones:
            if z[0] == 'circle':
                inside = np.hypot(xx - z[1] * s, yy - z[2] * s) < z[3] * s
            else:
                inside = (xx >= z[1] * s) & (xx < z[3] * s) & (yy >= z[2] * s) & (yy < z[4] * s)
            fam[(fam == f) & inside] = 'off'
    def boxmask(x0, y0, x1, y1):
        return (xx >= x0 * s) & (xx < x1 * s) & (yy >= y0 * s) & (yy < y1 * s)
    for bx in LETTER_BOXES_1600:
        m = boxmask(*bx) & np.isin(q, [12, 9, 10, 15])
        fam[m] = 'steel'
        colour[m] = STEEL
    tone[boxmask(*HAT_BAND_1600) & (q == 16)] = 0

    # paper connected to the border -> flat white (as make_bootlogo.py)
    probe = small.copy()
    for xy in ((0, 0), (LOGO_W - 1, 0), (0, LOGO_W - 1), (LOGO_W - 1, LOGO_W - 1)):
        if probe.getpixel(xy) != (255, 0, 255):
            ImageDraw.floodfill(probe, xy, (255, 0, 255), thresh=40)
    bg = Image.new('L', small.size, 0)
    bg.putdata([255 if p == (255, 0, 255) else 0 for p in probe.getdata()])
    bg = bg.filter(ImageFilter.MaxFilter(3)).filter(ImageFilter.GaussianBlur(1.2))

    names = []
    for k in range(len(ORDER) + 1):
        on = set(ORDER[:k])
        out = np.repeat(tone[..., None], 3, axis=2)
        paint = np.isin(fam, list(on)) & colour.any(2)
        out[paint] = colour[paint]
        if 'gold' in on:
            out[eye] = LIGHT_GOLD
        f = Image.fromarray(out).resize((LOGO_W, LOGO_W), Image.LANCZOS)
        f = Image.composite(Image.new('RGB', f.size, WHITE), f, bg)
        canvas = Image.new('RGB', (W, H), WHITE)
        canvas.paste(f, ((W - LOGO_W) // 2, CENTER_Y - LOGO_W // 2))
        name = os.path.join(outdir, f'bw-step{k}.png')
        canvas.save(name, optimize=True)
        names.append(name)
    sheet = Image.new('RGB', (len(names) * 382 + 10, 516), (200, 200, 200))
    for k, n in enumerate(names):
        sheet.paste(Image.open(n).resize((372, 496), Image.LANCZOS), (10 + k * 382, 10))
    sheet.save(os.path.join(outdir, 'bw-sheet.png'))
    print('\n'.join(names))


if __name__ == '__main__':
    main()
