#!/usr/bin/env python3
"""einkui 0.4.x shade capture checks (D-shade section 11), Mac side, full-screen PNGs only.

  shade_check.py diff A.png B.png [--ignore x0,y0,x1,y1 ...]
      light identity: max |A-B| per channel, pixels > 2, clusters (bbox) of differing pixels
  shade_check.py palette IMG --mode light|dark [--box x0,y0,x1,y1] [--extra RRGGBB,...]
      share of pixels within +-3 of a token or of a two-token anti-aliasing blend (gate >= 99.5 %),
      clusters >= 200 px outside, and the pixel count of every token (expected fills present?)
  shade_check.py rows IMG --mode light|dark [--box x0,y0,x1,y1]
      row ranges that are 100 % exact page color (gap rows between tile rows / below the cards)

Tokens = einkui Palette LIGHT / DARK (0.4.2: owner verdict G1; keep in sync with Palette.java; add tuned values with
--extra).
diff: compare against a baseline captured in the same Monet / tile / slider state; the QS footer (Compose, only gray-
filtered by einkui) follows SystemUI's Monet colors, so a baseline from before a Monet change differs there.
Run: /opt/homebrew/bin/python3.14 -I shade_check.py ...
"""
import sys
import numpy as np
from PIL import Image

TOKENS = {
    "light": {"page": "F7F4E7", "ink": "000000", "mid": "7A7A7A", "teal(on)": "194D50", "bordeaux(refresh)": "932845",
              "gold(color,warm)": "CD8E2C", "steel(cold)": "5D7E91", "silver(track,icon)": "A7B0B6"},
    "dark": {"page": "000000", "ink": "FFFFFF", "mid": "8C8C8C", "cream(clock,date,thumb)": "F7F4E7",
             "light_gold(on,warm,clear ring)": "F7BF5D", "bordeaux_lift(refresh)": "B03A57",
             "gold(color,icon)": "CD8E2C", "steel_lift(cold)": "8FAABA", "silver(icon)": "A7B0B6"},
}
PAGE = {"light": (247, 244, 231), "dark": (0, 0, 0)}


def rgb(hexs):
    return tuple(int(hexs[i:i + 2], 16) for i in (0, 2, 4))


def load(path, box=None):
    a = np.asarray(Image.open(path).convert("RGB"), dtype=np.int16)
    if box:
        x0, y0, x1, y1 = box
        a = a[y0:y1, x0:x1]
    return a


def parse_box(s):
    v = [int(x) for x in s.split(",")]
    assert len(v) == 4, "box = x0,y0,x1,y1"
    return v


def clusters(mask, min_px, off=(0, 0)):
    from scipy import ndimage
    lab, n = ndimage.label(mask, structure=np.ones((3, 3)))
    out = []
    for i, sl in enumerate(ndimage.find_objects(lab), 1):
        size = int((lab[sl] == i).sum())
        if size >= min_px:
            ys, xs = sl
            out.append((size, xs.start + off[0], ys.start + off[1], xs.stop + off[0], ys.stop + off[1]))
    return sorted(out, reverse=True)


def cmd_diff(args):
    a, b = load(args[0]), load(args[1])
    if a.shape != b.shape:
        sys.exit(f"size differs: {a.shape} vs {b.shape}")
    d = np.abs(a - b).max(axis=2)
    for spec in opt_list(args, "--ignore"):
        x0, y0, x1, y1 = parse_box(spec)
        d[y0:y1, x0:x1] = 0
    m = d > 2
    print(f"max |diff| = {int(d.max())}, pixels > 2: {int(m.sum())}, pixels > 0: {int((d > 0).sum())}")
    for c in clusters(m, 1)[:20]:
        print(f"  cluster {c[0]:6d} px  bbox x {c[1]}-{c[3]} y {c[2]}-{c[4]}")
    print("GATE light identity (<= 2 outside ignored boxes):", "PASS" if not m.any() else "FAIL")


def conform(uniq, toks, tol=3):
    """True per unique color if within tol (max channel) of a token or of a segment between two tokens."""
    p = uniq.astype(np.float64)
    T = np.array(toks, dtype=np.float64)
    ok = np.zeros(len(p), bool)
    for i in range(len(T)):
        ok |= np.abs(p - T[i]).max(axis=1) <= tol
        for j in range(i + 1, len(T)):
            a, ab = T[i], T[j] - T[i]
            t = np.clip(((p - a) @ ab) / max(ab @ ab, 1e-9), 0, 1)
            ok |= np.abs(p - (a + t[:, None] * ab)).max(axis=1) <= tol
    return ok


def cmd_palette(args):
    mode = opt(args, "--mode")
    box = parse_box(opt(args, "--box")) if opt(args, "--box") else None
    toks = dict(TOKENS[mode])
    for i, h in enumerate(x for e in opt_list(args, "--extra") for x in e.split(",") if x):
        toks[f"extra{i}"] = h.lstrip("#")
    a = load(args[0], box)
    flat = a.reshape(-1, 3)
    packed = (flat[:, 0].astype(np.int32) << 16) | (flat[:, 1].astype(np.int32) << 8) | flat[:, 2]
    uniq, inv = np.unique(packed, return_inverse=True)
    uc = np.stack([(uniq >> 16) & 255, (uniq >> 8) & 255, uniq & 255], axis=1)
    ok = conform(uc, [rgb(h) for h in toks.values()])[inv].reshape(a.shape[:2])
    share = ok.mean() * 100
    print(f"{args[0]}: {a.shape[1]}x{a.shape[0]} px, {len(uniq)} colors, conforming {share:.3f} %")
    w = max(24, *(len(k) for k in toks))  # widest token name (light_gold(...) is 30), so the #hex column lines up
    for name, h in toks.items():
        n = int((np.abs(a - np.array(rgb(h))).max(axis=2) <= 3).sum())
        print(f"  {name:{w}s} #{h}: {n:8d} px")
    off = (box[0], box[1]) if box else (0, 0)
    bad = clusters(~ok, 200, off)
    for c in bad[:20]:
        print(f"  outside cluster {c[0]:6d} px  bbox x {c[1]}-{c[3]} y {c[2]}-{c[4]}")
    print("GATE palette conformance (>= 99.5 %):", "PASS" if share >= 99.5 else "FAIL",
          f"({len(bad)} clusters >= 200 px outside)")


def cmd_rows(args):
    mode = opt(args, "--mode")
    box = parse_box(opt(args, "--box")) if opt(args, "--box") else None
    a = load(args[0], box)
    exact = (a == np.array(PAGE[mode])).all(axis=2).all(axis=1)
    y0 = box[1] if box else 0
    runs, start = [], None
    for y, e in enumerate(list(exact) + [False]):
        if e and start is None:
            start = y
        elif not e and start is not None:
            runs.append((start + y0, y - 1 + y0))
            start = None
    print(f"{len(runs)} runs of rows that are 100 % page #{'%02X%02X%02X' % PAGE[mode]}:")
    for r in runs:
        print(f"  y {r[0]}-{r[1]} ({r[1] - r[0] + 1} rows)")


def opt(args, name):
    return args[args.index(name) + 1] if name in args else None


def opt_list(args, name):
    return [args[i + 1] for i, x in enumerate(args) if x == name and i + 1 < len(args)]


if __name__ == "__main__":
    if len(sys.argv) < 3 or sys.argv[1] not in ("diff", "palette", "rows"):
        sys.exit(__doc__)
    {"diff": cmd_diff, "palette": cmd_palette, "rows": cmd_rows}[sys.argv[1]](sys.argv[2:])
