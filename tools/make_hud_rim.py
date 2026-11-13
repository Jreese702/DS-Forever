"""THE SMOOTH HUD RIM (Hub hub49 / uf20, the user 2026-11-13: "The shaper bar still looks so fucking pixelated on the corners").

The target bar, its level box, the target of target, the party rows and every hudBox wore the game's HUD bar art
(UI-HUD-CoolDownManager-Bar-BG: 132 x 19 texels, a 3-texel rim) blown up ~3x: smeared with the default filter (uf17 and before),
stair-stepped with NEAREST (uf18 / uf19). Neither is acceptable. This is OUR OWN rim in the same look, drawn analytically
(anti-aliased rounded corners) at 16 texture pixels per art texel, with box-filtered levels at 8 / 4 / 2:

    WowCompanion/Media/hud_rim_16.tga   256 x 512
    WowCompanion/Media/hud_rim_8.tga    128 x 256
    WowCompanion/Media/hud_rim_4.tga     64 x 128
    WowCompanion/Media/hud_rim_2.tga     32 x  64

Hub picks, per frame, the smallest level with at least one texture pixel per screen pixel (k = the art texel in physical pixels:
level >= k), so the GPU's plain bilinear filter only ever DOWNSCALES by 1x-2x (no mipmaps needed: a bigger minification would skip
texels). The largest on-screen texel today is the target bar at 1440 lines: 24 / 8 * 1440 / 1080 = 4 px; level 16 is 4x that.

THE LAYOUT (art texels; the canvas is 16 x 32, rows 19-31 empty). It is a nine-slice of the old art's cut, same thicknesses:
    cols 0-3   the left side (3)        cols 3-9   the middle (uniform across)    cols 9-16  the right side (7: rim 3 + shadow 1 + 3 empty)
    rows 0-3   the top lip (3)          rows 3-11  the bar's own height (8)       rows 11-19 the bottom lip (8: rim 3 + shadow 1 + 4 empty)
The rim is the old art's three bands, by the distance in from a rounded rectangle (x 0-12, y 0-14, radius 3): an outer black
(0.64 alpha on top like the art's row 0, 0.95 elsewhere), the light metal line (warm silver on top, bronze down the sides with the
art's own gradient, tan on the bottom), the inner black; a 1-texel soft drop shadow right and below (0.25); the middle the art's
track (black, its alpha profile down the bar). Colours are the old art's own samples.

    python tools/make_hud_rim.py
"""
import os

import numpy as np
from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MEDIA = os.path.join(ROOT, "companion", "app", "src", "main", "assets", "companion", "addons", "WowCompanion", "Media")

CW, CH = 16, 32            # the canvas in art texels
TOP_S = 16                 # texture pixels per art texel at the biggest level
LEVELS = (16, 8, 4, 2)
SS = 4                     # supersamples per texture pixel per axis at the biggest level
OX0, OY0, OX1, OY1, RAD = 0.0, 0.0, 12.0, 14.0, 3.0      # the rim's outer rounded rectangle
IX0, IY0, IX1, IY1 = 3.0, 3.0, 9.0, 11.0                 # the bar's own rectangle (the middle)

# the old art's samples (UI-HUD-CoolDownManager-Bar-BG-c60)
TOP_LINE = (200, 174, 155)                  # row 1, the middle (194,163,141) .. the caps (215,197,182)
BOT_LINE = (192, 162, 134)                  # row 12, the middle
LEFT_LINE = [(198, 190, 184), (225, 217, 210), (135, 111, 93), (131, 106, 88), (117, 94, 77), (111, 85, 66), (149, 129, 114), (117, 91, 72)]
RIGHT_LINE = [(156, 144, 132), (121, 97, 79), (115, 89, 70), (115, 89, 70), (109, 83, 65), (110, 85, 66), (148, 126, 108), (117, 91, 72)]
TRACK_A = [223, 163, 109, 80, 78, 97, 146, 206]     # the middle's alpha, rows 3-10
INNER_TOP, INNER_BOT = (0, 0, 0), (8, 7, 7)
OUTER_A_TOP, OUTER_A = 0xa2, 0xf0                 # the outer black: the art's row 0 / its sides and bottom
SHADOW_A = 0x3f


def sd_round_rect(x, y, x0, y0, x1, y1, r):
    """Signed distance INSIDE a rounded rectangle (positive inside)."""
    cx = np.clip(x, x0 + r, x1 - r)
    cy = np.clip(y, y0 + r, y1 - r)
    dx, dy = x - cx, y - cy
    inner = np.minimum(np.minimum(x - x0, x1 - x), np.minimum(y - y0, y1 - y))
    corner = (dx != 0) & (dy != 0)
    return np.where(corner, r - np.hypot(dx, dy), inner)


def grad(tab, y):
    """A side's colour down the bar: the 8 samples at rows 3..10 (texel centres), clamped."""
    t = np.clip(y - IY0 - 0.5, 0, len(tab) - 1)
    i0 = np.floor(t).astype(int)
    i1 = np.minimum(i0 + 1, len(tab) - 1)
    f = (t - i0)[..., None]
    a = np.array(tab, dtype=float)
    return a[i0] * (1 - f) + a[i1] * f


def master():
    n = TOP_S * SS
    W, H = CW * n, CH * n
    xs = (np.arange(W) + 0.5) / n
    ys = (np.arange(H) + 0.5) / n
    x, y = np.meshgrid(xs, ys)
    d = sd_round_rect(x, y, OX0, OY0, OX1, OY1, RAD)
    # which side a point belongs to: straight parts by region, the corners by the angle round the corner's centre
    cx = np.clip(x, OX0 + RAD, OX1 - RAD)
    cy = np.clip(y, OY0 + RAD, OY1 - RAD)
    ax, ay = np.abs(x - cx), np.abs(y - cy)
    tot = ax ** 2 + ay ** 2
    wv = np.where(tot > 0, ay ** 2 / np.where(tot > 0, tot, 1), 0.0)      # weight of the top / bottom side
    wh = np.where(tot > 0, ax ** 2 / np.where(tot > 0, tot, 1), 0.0)      # weight of the left / right side
    top = y < (IY0 + IY1) / 2
    left = x < (IX0 + IX1) / 2
    vcol = np.where(top[..., None], np.array(TOP_LINE, float), np.array(BOT_LINE, float))
    hcol = np.where(left[..., None], grad(LEFT_LINE, y), grad(RIGHT_LINE, y))
    line = vcol * wv[..., None] + hcol * wh[..., None]
    inner = np.where(top[..., None], np.array(INNER_TOP, float), np.array(INNER_BOT, float))
    outer_a = np.where(top, OUTER_A_TOP, OUTER_A) * wv + OUTER_A * wh
    # the bands (coverage per supersample; the box filter below makes the edges smooth)
    rgb = np.zeros((H, W, 3))
    a = np.zeros((H, W))
    inside = d > 0
    b_out = inside & (d <= 1)
    b_line = (d > 1) & (d <= 2)
    b_in = (d > 2) & (d <= 3)
    mid = d > 3
    a[b_out] = outer_a[b_out] / 255
    a[b_line] = 1.0
    rgb[b_line] = line[b_line]
    a[b_in] = 1.0
    rgb[b_in] = inner[b_in]
    # the middle: the art's track, black, its alpha down the bar (it only exists inside the bar's own rectangle)
    t = np.clip(y - IY0 - 0.5, 0, 7)
    i0 = np.floor(t).astype(int)
    i1 = np.minimum(i0 + 1, 7)
    f = t - i0
    ta = (np.array(TRACK_A, float)[i0] * (1 - f) + np.array(TRACK_A, float)[i1] * f) / 255
    a[mid] = ta[mid]
    # the drop shadow: the same rounded rectangle one texel right and down, under everything
    ds = sd_round_rect(x, y, OX0 + 1, OY0 + 1, OX1 + 1, OY1 + 1, RAD)
    sh = (ds > 0) & ~inside
    a[sh] = SHADOW_A / 255
    # premultiplied, box-filtered down to TOP_S per texel
    pm = np.concatenate([rgb * a[..., None], a[..., None]], axis=2)
    return pm.reshape(CH * TOP_S, SS, CW * TOP_S, SS, 4).mean(axis=(1, 3))


def down(pm, f):
    h, w, _ = pm.shape
    return pm.reshape(h // f, f, w // f, f, 4).mean(axis=(1, 3))


def to_image(pm):
    a = pm[..., 3]
    rgb = np.where(a[..., None] > 1e-6, pm[..., :3] / np.maximum(a[..., None], 1e-6), 0)
    out = np.concatenate([rgb, (a * 255)[..., None]], axis=2)
    return Image.fromarray(np.clip(np.round(out), 0, 255).astype(np.uint8), "RGBA")


def main():
    pm = master()
    os.makedirs(MEDIA, exist_ok=True)
    for s in LEVELS:
        lv = down(pm, TOP_S // s) if s != TOP_S else pm
        p = os.path.join(MEDIA, "hud_rim_%d.tga" % s)
        to_image(lv).save(p, compression=None)
        print(p, lv.shape[1], "x", lv.shape[0])


if __name__ == "__main__":
    main()
