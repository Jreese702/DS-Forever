"""THE WINGS' L / R PLATES AS THE BAND DRAWS THEM (ActionBar nbar17; the user, 2026-11-23, testing 1523: "The triggers look weird and
stretched on both sides and nothing like the bottom").

The bottom band's shoulder plates are NOT Blizzard's art (CastView: "The shoulder plates stay the ORIGINAL vector plates (user ruling
14:1x: Blizzard's plate art came out distorted)"): a 39 x 31-unit plate with its outer top corner cut (pathBadgeR for L: (19.5, -15.5)
-> (-9, -15.5) -> (-19.5, -5) -> (-19.5, 15.5) -> (19.5, 15.5)), filled BADGE_BG (28, 22, 15) / BADGE_BG_HELD (58, 46, 26), a metal edge
inside it (UiKit.ringShape's metal band as the device shows it: a warm 65 / 53 / 41 rim rising to 125 / 101 / 77 half a unit in,
back to the fill by 1.8; held: ringShape's lit filter, the game's gold), and the vector reticle (drawReticle: a 5.2 dark disc 58 / 52 / 46, a 6.6 ring 1.5 wide and
four 2.6 diamonds on it in FRIENDLY 96 / 214 / 80 or HOSTILE 235 / 60 / 50; the shortcuts' four 2.4 gold squares). The add-on cannot
draw paths, so the same shapes are drawn here into our own textures (no Blizzard pixel copied), each at the band's own proportions:

    WowCompanion/Media/wings_plate.tga        256 x 256   the L plate, 6 texels a unit: the used rect 234 x 186 (39 x 31) at the top-left
    WowCompanion/Media/wings_plate_held.tga   256 x 256   the same, held (LB / RB down)
    WowCompanion/Media/wings_ret_friendly.tga 128 x 128   the reticle on an 18-unit square (7.11 texels a unit), centred
    WowCompanion/Media/wings_ret_hostile.tga  128 x 128
    WowCompanion/Media/wings_ret_shortcuts.tga 128 x 128
    WowCompanion/Media/wings_glow.tga          64 x  64   the held trigger's soft disc (UiKit.triggerChip's radial glow: 150 / 255 at the
                                                          centre to 0 at the rim, white: the add-on tints it the game's gold)

The R plate is the L one mirrored (SetTexCoord with left / right swapped). Every edge anti-aliased (4 x 4 supersamples a texel).

    python tools/make_wings_plate.py
"""
import os

import numpy as np
from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MEDIA = os.path.join(ROOT, "companion", "app", "src", "main", "assets", "companion", "addons", "WowCompanion", "Media")

SS = 4                          # supersamples per texel per axis
PLATE_TEX = 256                 # the plate's canvas (power of two)
PLATE_U = 6                     # texels per plate unit: 39 x 31 units -> 234 x 186 texels
PLATE_W, PLATE_H = 39, 31
# the L plate (CastView pathBadgeR, y down, centred): its outer top corner cut
PLATE_POLY = [(19.5, -15.5), (-9.0, -15.5), (-19.5, -5.0), (-19.5, 15.5), (19.5, 15.5)]
FILL, FILL_HELD = (28, 22, 15), (58, 46, 26)
# the metal edge inside the plate, by the depth from the outline (plate units): the band's edge as it shows on the device (UiKit.
# ringShape's ring_square strips along the sides, clipped to the path: a warm metal band peaking ~0.5 unit in, gone into the fill by
# ~1.8 - the four sides' cross-sections averaged, drawn here as a gradient); held = ringShape's lit filter (x 1.95 / 1.7 / 0.55, the
# game's gold) over the held fill
EDGE = [(0.0, (65, 53, 41)), (0.13, (86, 70, 53)), (0.25, (107, 87, 66)), (0.38, (121, 98, 74)), (0.5, (125, 101, 77)),
        (0.63, (118, 95, 72)), (0.75, (108, 87, 66)), (0.88, (96, 78, 59)), (1.0, (84, 68, 52)), (1.13, (70, 57, 44)),
        (1.26, (57, 47, 36)), (1.38, (45, 37, 28)), (1.51, (36, 30, 22)), (1.64, (31, 25, 18)), (1.8, FILL)]
LIT = (1.95, 1.7, 0.55)
EDGE_HELD = [(d, tuple(min(255, int(round(c * k))) for c, k in zip(col, LIT))) for d, col in EDGE[:-2]] + [(1.64, (60, 48, 27)),
                                                                                                         (1.8, FILL_HELD)]
RET_TEX = 128                   # the reticle's canvas
RET_SPAN = 18.0                 # plate units across it
FRIENDLY, HOSTILE, GOLD, RET_DISC = (96, 214, 80), (235, 60, 50), (255, 209, 0), (58, 52, 46)
GLOW_TEX = 64


def grid(n_px, units_per_px, origin):
    """the supersample centres (plate units) of an n_px canvas: x, y arrays"""
    n = n_px * SS
    c = (np.arange(n) + 0.5) / SS * units_per_px + origin
    return np.meshgrid(c, c)


def depth_in_convex(x, y, poly):
    """the signed distance inside a convex polygon (y down, either winding): > 0 inside, the depth from the nearest side"""
    cx = sum(p[0] for p in poly) / len(poly)
    cy = sum(p[1] for p in poly) / len(poly)
    d = np.full(x.shape, np.inf)
    for (ax, ay), (bx, by) in zip(poly, poly[1:] + poly[:1]):
        nx, ny = by - ay, -(bx - ax)
        ln = (nx * nx + ny * ny) ** 0.5
        nx, ny = nx / ln, ny / ln
        if (cx - ax) * nx + (cy - ay) * ny < 0:
            nx, ny = -nx, -ny
        d = np.minimum(d, (x - ax) * nx + (y - ay) * ny)
    return d


def ramp(d, stops):
    out = np.zeros(d.shape + (3,))
    for i in range(3):
        out[..., i] = np.interp(d, [s[0] for s in stops], [s[1][i] for s in stops])
    return out


def down(rgb, a):
    """box-filter the supersamples to texels (colour premultiplied, then back)"""
    h, w = a.shape
    a4 = a.reshape(h // SS, SS, w // SS, SS)
    pm = (rgb * a[..., None]).reshape(h // SS, SS, w // SS, SS, 3).mean(axis=(1, 3))
    am = a4.mean(axis=(1, 3))
    col = np.where(am[..., None] > 1e-6, pm / np.maximum(am[..., None], 1e-6), 0)
    return np.dstack([col, am * 255]).clip(0, 255).astype(np.uint8)


def plate(held):
    x, y = grid(PLATE_TEX, 1.0 / PLATE_U, 0.0)
    x, y = x - PLATE_W / 2, y - PLATE_H / 2            # the used rect's top-left at the canvas's
    d = depth_in_convex(x, y, PLATE_POLY)
    inside = (d >= 0) & (x <= PLATE_W / 2) & (y <= PLATE_H / 2)
    rgb = ramp(np.maximum(d, 0), EDGE_HELD if held else EDGE)
    return down(rgb, inside.astype(float))


def reticle(kind):
    x, y = grid(RET_TEX, RET_SPAN / RET_TEX, -RET_SPAN / 2)
    rgb = np.zeros(x.shape + (3,))
    a = np.zeros(x.shape)

    def put(mask, col):
        rgb[mask] = col
        a[mask] = 1.0
    if kind == "shortcuts":
        for dx in (-3.2, 0.8):
            for dy in (-3.2, 0.8):
                # a 2.4 square, corners rounded 0.6 (drawRoundRect)
                qx = np.maximum(np.abs(x - (dx + 1.2)) - (1.2 - 0.6), 0)
                qy = np.maximum(np.abs(y - (dy + 1.2)) - (1.2 - 0.6), 0)
                put(qx * qx + qy * qy <= 0.36, GOLD)
    else:
        col = FRIENDLY if kind == "friendly" else HOSTILE
        r = np.hypot(x, y)
        put(r <= 5.2, RET_DISC)
        put((r >= 6.6 - 0.75) & (r <= 6.6 + 0.75), col)
        for cx, cy in ((0, -6.6), (6.6, 0), (0, 6.6), (-6.6, 0)):
            put(np.abs(x - cx) + np.abs(y - cy) <= 1.3 * 2 ** 0.5, col)
    return down(rgb, a)


def glow():
    n = GLOW_TEX * SS
    c = (np.arange(n) + 0.5) / n * 2 - 1
    x, y = np.meshgrid(c, c)
    r = np.hypot(x, y)
    a = np.clip(1 - r, 0, 1) * (150 / 255.0)
    rgb = np.full(x.shape + (3,), 255.0)
    out = down(rgb, a)
    return out


def save(arr, name):
    p = os.path.join(MEDIA, name)
    Image.fromarray(arr, "RGBA").save(p, compression=None)
    return p


def main():
    made = [save(plate(False), "wings_plate.tga"), save(plate(True), "wings_plate_held.tga")]
    for k in ("friendly", "hostile", "shortcuts"):
        made.append(save(reticle(k), "wings_ret_%s.tga" % k))
    made.append(save(glow(), "wings_glow.tga"))
    for p in made:
        print(p)


if __name__ == "__main__":
    main()
