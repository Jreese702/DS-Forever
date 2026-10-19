# every route's water, independently of the app's water grid: each route sampled every yard, the liquid's surface there (MH2O: the
# instance's own per-vertex heights where it keeps them, as Terrain.water reads them, else its top) over the terrain's bilinear height.
# Spots on a walkway's deck (the harness writes them to decks_<tag>.json, world 2D triangles) are not water. Prints each route's deepest wading
# and how many yards of it are deeper than the ford limit (1.5 yd).
#   ZDIR=<dir> python wetcheck.py <levels_routes_<tag>.json> [decks_<tag>.json, default: beside it]
import json, math, os, struct, sys
import numpy as np
import heights as HT
FORD = 1.5
data = json.load(open(sys.argv[1], encoding='utf-8'))
_dk = sys.argv[2] if len(sys.argv) > 2 else os.path.join(os.path.dirname(sys.argv[1]), os.path.basename(sys.argv[1]).replace('levels_routes_', 'decks_'))
decks = json.load(open(_dk, encoding='utf-8')) if os.path.exists(_dk) else []
_surf = {}
def surface_tile(c, r):
    """per liquid cell (128 x 128 a tile) the surface: the mean of its 4 vertices' heights where the instance keeps heights in its own
    low..top, else its top; the highest layer"""
    if (c, r) in _surf: return _surf[(c, r)]
    w = np.full((128, 128), np.nan, dtype=np.float32)
    p = os.path.join(HT.DIR, 'adt', '%d_%d.adt' % (c, r))
    if os.path.exists(p):
        b = open(p, 'rb').read()
        for tag, o, n in HT.chunks(b):
            if tag != 'MH2O': continue
            base = o + 8; end = base + n
            for ch in range(256):
                io, lay, _ = struct.unpack_from('<III', b, base + ch * 12)
                if not lay or lay > 8 or io <= 0: continue
                for l in range(lay):
                    ip = base + io + l * 24
                    if ip + 24 > end: break
                    low, top = struct.unpack_from('<ff', b, ip + 4); xo, yo, ww, hh = b[ip + 12:ip + 16]
                    bm, vd = struct.unpack_from('<II', b, ip + 16)
                    if math.isnan(top) or ww == 0 or hh == 0 or xo + ww > 8 or yo + hh > 8: continue
                    nv = (ww + 1) * (hh + 1); vh = None
                    if vd > 0 and base + vd + nv * 4 <= end:
                        a = struct.unpack_from('<%df' % nv, b, base + vd)
                        if all(not math.isnan(v) and min(low, top) - 0.5 <= v <= top + 0.5 for v in a): vh = a
                    for y in range(hh):
                        for x in range(ww):
                            bit = y * ww + x
                            if bm and not (b[base + bm + bit // 8] >> (bit % 8)) & 1: continue
                            s = top if vh is None else (vh[y * (ww + 1) + x] + vh[y * (ww + 1) + x + 1] + vh[(y + 1) * (ww + 1) + x] + vh[(y + 1) * (ww + 1) + x + 1]) / 4
                            k = ((ch // 16) * 8 + yo + y, (ch % 16) * 8 + xo + x)
                            if np.isnan(w[k]) or s > w[k]: w[k] = s
    _surf[(c, r)] = w
    return w
def depth(x, y):
    gx = (HT.ORG - y) / HT.ADT; gy = (HT.ORG - x) / HT.ADT; c = int(gx); r = int(gy)
    s = surface_tile(c, r)[min(127, int((gy - r) * 128)), min(127, int((gx - c) * 128))]
    return float('nan') if np.isnan(s) else max(0.0, float(s) - HT.height(x, y))
# the decks bucketed by 16 yd
grid = {}
for k, t in enumerate(decks):
    for i in range(int(math.floor(min(t[0], t[2], t[4]) / 16)) - 1, int(math.floor(max(t[0], t[2], t[4]) / 16)) + 2):
        for j in range(int(math.floor(min(t[1], t[3], t[5]) / 16)) - 1, int(math.floor(max(t[1], t[3], t[5]) / 16)) + 2):
            grid.setdefault((i, j), []).append(k)
def seg(ax, ay, bx, by, x, y):
    dx, dy = bx - ax, by - ay; l2 = dx * dx + dy * dy; u = 0 if l2 <= 0 else max(0, min(1, ((x - ax) * dx + (y - ay) * dy) / l2))
    return math.hypot(ax + u * dx - x, ay + u * dy - y)
def on_deck(x, y, m=1.0):
    for k in grid.get((int(math.floor(x / 16)), int(math.floor(y / 16))), []):
        t = decks[k]
        d0 = (t[2] - t[0]) * (y - t[1]) - (t[3] - t[1]) * (x - t[0]); d1 = (t[4] - t[2]) * (y - t[3]) - (t[5] - t[3]) * (x - t[2]); d2 = (t[0] - t[4]) * (y - t[5]) - (t[1] - t[5]) * (x - t[4])
        if not ((d0 < 0 or d1 < 0 or d2 < 0) and (d0 > 0 or d1 > 0 or d2 > 0)): return True
        if min(seg(t[0], t[1], t[2], t[3], x, y), seg(t[2], t[3], t[4], t[5], x, y), seg(t[4], t[5], t[0], t[1], x, y)) <= m: return True
    return False
# the user's narrow-stream ruling (2026-10-18; SafePath.NARROW_DEPTH / NARROW_YD): water up to 2.2 yd is waded for at most 6 yd at a
# time; deeper than the ford for longer, or deeper than 2.2 at all, is a swim
NARROW, NARROW_YD = 2.2, 6.0
for r in data['routes']:
    pts = r['pts']; worst = (0.0, None); deep = 0; decked = 0; run = 0; longest = (0, None); over = 0
    for a, b in zip(pts, pts[1:]):
        if len(a) > 2 or len(b) > 2: continue                       # a cave's floor
        n = max(1, int(math.hypot(b[0] - a[0], b[1] - a[1])))
        for q in range(n):
            x = a[0] + (b[0] - a[0]) * q / n; y = a[1] + (b[1] - a[1]) * q / n
            d = depth(x, y)
            if math.isnan(d) or d <= FORD: run = 0
            if math.isnan(d): continue
            if on_deck(x, y): decked += 1; run = 0; continue
            if d > FORD:
                deep += 1; run += 1
                if run > longest[0]: longest = (run, (round(x), round(y)))
            if d > NARROW: over += 1
            if d > worst[0]: worst = (d, (round(x), round(y)))
    print('WET %s: deepest %.1f yd%s, %d yd deeper than %.1f, %d yd on decks; longest wade %d yd%s, %d yd deeper than %.1f' % (r['letter'], worst[0],
        ' at %d,%d' % worst[1] if worst[1] else '', deep, FORD, decked, longest[0], ' at %d,%d' % longest[1] if longest[1] else '', over, NARROW))
