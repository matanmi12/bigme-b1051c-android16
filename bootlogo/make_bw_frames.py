#!/usr/bin/env python3
"""InKognito badge as black pen on white + boot-animation frames that colour it in, one palette colour per frame
(owner 2026-10-19: "black pen on a white canvas with a little gray shading", then bordeaux / steel / teal / gold;
colour parts, not dots). Needs Pillow + numpy (+ scipy for the dark variant only).

Method: every pixel of the owner's logo (boot logo.jpeg) is assigned to one of 24 k-means clusters (computed on the
1600 px logo; indices below come from that run and are deterministic). Each cluster has a family and a pen tone:
ink and the black cat's fur -> pure black (no red cast on Kaleido), colour fills -> white, metal / shadows -> three
light grays. A frame paints the clusters of its 'on' families in the flat palette colour, but only inside that
family's own area of the badge (radius from the badge centre), so the steel of the hat does not spill into the
riveted silver ring and the teal of the ring does not tint the lens. Eyes: light gold from the gold step on.
Mapping runs at 2048 px and is scaled to 1600 px (anti-aliased edges), placed like make_bootlogo.py.

Usage: make_bw_frames.py SRC_SQUARE_LOGO OUT_DIR [--paper RRGGBB]
                         [--outside RRGGBB [--outline PX] [--outline-color RRGGBB] [--outline-card 5,6,8,10]]
       -> bw-step0.png (B&W) .. bw-step4.png (all colors), bw-sheet.png [, outline-card.png]
--paper: the canvas, outside the badge and in its unpainted areas (default FFFFFF). Owner 2026-10-19 evening:
cream F7F4E7 for the boot animation, the LK boot image and the shutdown animation; white only for the charging screen.
--outside: dark theme (owner 2026-10-20): only the canvas OUTSIDE the badge silhouette changes (000000); the badge is
the same drawing as on paper (pen lines, paper-colored unpainted interior, the color parts). A thin ring of
--outline-color (default = paper) runs around the whole silhouette, laid UNDER the drawing, so not one pixel of the
badge or of its soft edge changes (self-check below). --outline: ring width, default 6 px with --outside, minimum
5 (the badge's soft edge reaches 4.12 px past the silhouette). The ring's anti-aliased edge is neutral gray and a
black outside is exact 0 (Magazine turns any tint blue/red). --outline-card W,..: also outline-card.png, 1:1 crops
of the last frame with up to four ring widths, labelled, to pick one on the panel.
"""
import os, sys
import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont, ImageOps

W, H, LOGO_W, CENTER_Y = 1860, 2480, 1600, 1160
X0, Y0 = (W - LOGO_W) // 2, CENTER_Y - LOGO_W // 2              # where the 1600 px badge sits on the canvas
WHITE = (255, 255, 255)
MIN_OUTLINE = 5
# outline card: 900x1200 crops of the canvas (left half of the badge, top to banner) on a black 1860x2480 card
CARD_CROP = (240, 470, 1140, 1670)
CARD_TILES = [(30, 40), (930, 40), (30, 1260), (930, 1260)]
FONT = "/System/Library/Fonts/Supplemental/Arial Unicode.ttf"
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


def ring_alpha(bgc, outline_px):
    """bgc: canvas-sized 'L' mask, 255 = paper connected to the border (soft edge 1..254). Returns the silhouette
    (bgc < 128), d = Euclidean px from it (0 inside) and the ring's coverage a: 1 out to outline - 0.5 px, then a
    1 px linear anti-aliased edge, 0 beyond. EDT, not MaxFilter: equally thick on the diagonals of the cog."""
    from scipy import ndimage                       # lazily: only the dark variant needs scipy
    sil = np.asarray(bgc) < 128
    d = ndimage.distance_transform_edt(~sil)
    return sil, d, np.clip(outline_px + 0.5 - d, 0, 1)


def dark_under(bgc, outline_px, outside_rgb, outline_rgb):
    """The canvas the badge is pasted onto: outside_rgb with an outline_rgb ring around the silhouette. The ring's
    anti-aliased edge is the neutral gray of the outline color's luma (no warm cream/black mix); with a black outside
    every near-black (<= 8) left by that edge is snapped to exact 0. Shared with make_bootlogo.py (dark logo)."""
    a = ring_alpha(bgc, outline_px)[2][..., None]
    oc = np.array(outline_rgb, np.float32)
    y = 0.299 * oc[0] + 0.587 * oc[1] + 0.114 * oc[2]
    ring = np.where(a >= 1, oc, y)
    out = (np.array(outside_rgb, np.float32) * (1 - a) + ring * a + 0.5).astype(np.uint8)
    if tuple(outside_rgb) == (0, 0, 0):
        out[(out.max(2) <= 8) & (a[..., 0] < 1)] = 0
    return Image.fromarray(out)


def on_under(f, bg, under):
    """The badge (1600 px frame before its paper composite) on `under`. Image.composite(paper, f, bg) is
    f.paste(paper, mask=bg); this is the same blend with the roles swapped, so wherever `under` is the paper color
    the result is bit-identical to the paper frame."""
    canvas = under.copy()
    canvas.paste(f, (X0, Y0), ImageOps.invert(bg))
    return canvas


def self_check(name, canvas, cream, bgc, outline_px, outside, outline_rgb, paper):
    """Per dark frame: the drawing (bgc < 255, i.e. the silhouette and its soft edge) must match the paper frame
    pixel for pixel, and everything past the ring must be exactly the outside color. Exits non-zero otherwise."""
    from scipy import ndimage
    c, p = np.asarray(canvas), np.asarray(cream)
    sil, d, a = ring_alpha(bgc, outline_px)
    drawing = np.asarray(bgc) < 255
    diff = (c != p).any(2)
    n_in = int((diff & drawing).sum())
    near_ring = int((diff & (d <= outline_px - 0.5)).sum())
    beyond = a == 0
    n_beyond = int(beyond.sum())
    exact_beyond = int((c[beyond] == outside).all(1).sum())
    fringe = (a > 0) & (a < 1)
    fc = c[fringe].astype(int)
    neutral = bool((fc.max(1) == fc.min(1)).all())
    cc = c[~drawing].astype(int)
    tinted = int(((cc.max(1) <= 40) & (cc.max(1) != cc.min(1))).sum())
    holes = ndimage.label(~sil)[1] - 1
    hx = '#%02X%02X%02X' % tuple(outside)
    print(f'{os.path.basename(name)}: {100 * (c == outside).all(2).mean():.2f} % exact {hx}; '
          f'ring {int(((a >= 1) & ~sil).sum())} px, fringe {int(fringe.sum())} px (R=G=B: {"yes" if neutral else "NO"}); '
          f'enclosed paper islands kept as drawing: {holes}')
    print(f'  interior identical to cream frame: {n_in} px differ')
    print(f'  differences only where d > {outline_px - 0.5:g} px: {"yes" if near_ring == 0 else f"NO ({near_ring} px)"}')
    print(f'  outside the ring: {100 * exact_beyond / n_beyond:.4f} % exact {hx} ({n_beyond - exact_beyond} px not); '
          f'tinted near-blacks outside the drawing: {tinted}')
    ok = exact_beyond == n_beyond and neutral and tinted == 0
    if tuple(outline_rgb) == tuple(paper):          # a paper-colored ring must leave the drawing untouched
        ok = ok and n_in == 0 and near_ring == 0
    if not ok:
        sys.exit(f'self-check FAILED for {name}')


def text_neutral(img, xy, text, font, rgb):
    """Labels on black without warm anti-aliasing: full coverage = rgb, partial = the neutral gray of its luma,
    near-blacks (<= 8) = exact 0."""
    m = Image.new('L', img.size, 0)
    ImageDraw.Draw(m).text(xy, text, font=font, fill=255)
    m = np.asarray(m).astype(np.float32)[..., None] / 255
    y = 0.299 * rgb[0] + 0.587 * rgb[1] + 0.114 * rgb[2]
    ink = np.where(m >= 1, np.array(rgb, np.float32), y)
    base = np.asarray(img).astype(np.float32)
    out = (base * (1 - m) + ink * m + 0.5).astype(np.uint8)
    out[(out.max(2) <= 8) & (m[..., 0] > 0) & (m[..., 0] < 1)] = 0
    return Image.fromarray(out)


def hexrgb(hx):
    return tuple(int(hx[i:i + 2], 16) for i in (0, 2, 4))


def opt(flag, default=None):
    return sys.argv[sys.argv.index(flag) + 1] if flag in sys.argv else default


def main():
    src, outdir = sys.argv[1], sys.argv[2]
    paper = hexrgb(opt('--paper', 'FFFFFF'))
    outside_rgb = hexrgb(opt('--outside', '%02X%02X%02X' % paper))
    outline = int(opt('--outline', 6 if outside_rgb != paper else 0))
    outline_rgb = hexrgb(opt('--outline-color', '%02X%02X%02X' % paper))
    card = [int(v) for v in opt('--outline-card', '').split(',') if v]
    for w in [outline] * (outline != 0) + card:
        assert w >= MIN_OUTLINE, f'outline {w} px: must be >= {MIN_OUTLINE} (the soft edge reaches 4.12 px past the silhouette)'
    assert outline or outside_rgb == paper, '--outside needs --outline >= 5: the drawing\'s soft edge must sit on the paper color'
    assert len(card) <= len(CARD_TILES), f'--outline-card: at most {len(CARD_TILES)} widths'
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

    # dark variant: the badge's own mask on the canvas, the ring under it (computed once)
    under = bgc = None
    if outline or card:
        bgc = Image.new('L', (W, H), 255)
        bgc.paste(bg, (X0, Y0))
    if outline:
        under = dark_under(bgc, outline, outside_rgb, outline_rgb)
        if tuple(outline_rgb) == tuple(paper):
            u = np.asarray(under)[np.asarray(bgc) < 255]
            assert (u == paper).all(), 'ring does not cover the soft edge: the drawing would change'

    names = []
    for k in range(len(ORDER) + 1):
        on = set(ORDER[:k])
        out = np.repeat(tone[..., None], 3, axis=2)
        paint = np.isin(fam, list(on)) & colour.any(2)
        out[paint] = colour[paint]
        if 'gold' in on:
            out[eye] = LIGHT_GOLD
        out[(out == 255).all(2)] = paper                # unpainted areas are the canvas too (pen on paper)
        raw = f = Image.fromarray(out).resize((LOGO_W, LOGO_W), Image.LANCZOS)
        f = Image.composite(Image.new('RGB', f.size, paper), f, bg)
        canvas = Image.new('RGB', (W, H), paper)
        canvas.paste(f, (X0, Y0))
        name = os.path.join(outdir, f'bw-step{k}.png')
        if under is not None:                           # same drawing, only the canvas outside it changes
            cream, canvas = canvas, on_under(raw, bg, under)
            self_check(name, canvas, cream, bgc, outline, outside_rgb, outline_rgb, paper)
        canvas.save(name, optimize=True)
        names.append(name)

    if card:                                            # the last frame (all colors) with each ring width, 1:1
        board = Image.new('RGB', (W, H), outside_rgb)
        try:
            font = ImageFont.truetype(FONT, 64)
        except OSError:
            font = ImageFont.load_default(64)
        for (tx, ty), w in zip(CARD_TILES, card):
            board.paste(on_under(raw, bg, dark_under(bgc, w, outside_rgb, outline_rgb)).crop(CARD_CROP), (tx, ty))
            xy, label = (tx + 28, ty + 20), f'{w} px'
            lb = ImageDraw.Draw(board).textbbox(xy, label, font=font)
            lb = (lb[0] - 4, lb[1] - 4, lb[2] + 4, lb[3] + 4)
            assert (np.asarray(board.crop(lb)) == outside_rgb).all(), f'label "{label}" would touch the badge or its ring'
            board = text_neutral(board, xy, label, font, outline_rgb)
        name = os.path.join(outdir, 'outline-card.png')
        board.save(name, optimize=True)
        print(name)
    sheet = Image.new('RGB', (len(names) * 382 + 10, 516), (200, 200, 200))
    for k, n in enumerate(names):
        sheet.paste(Image.open(n).resize((372, 496), Image.LANCZOS), (10 + k * 382, 10))
    sheet.save(os.path.join(outdir, 'bw-sheet.png'))
    print('\n'.join(names))


if __name__ == '__main__':
    main()
