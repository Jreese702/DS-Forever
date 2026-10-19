# Durotar from its real heights: an oblique 3D view with the game's minimap tiles draped over it, and a top-down height map
# (hypsometric tint, hillshade, 10 yd contours), both with the test routes A-H and their numbered flags
import os, json, math, sys
import numpy as np
# the zone: its spec (zones/<tag>.json: tag, dir, title, crop [top, bottom, left, right], closeups), Durotar by default
SPEC = json.load(open(sys.argv[1], encoding='utf-8')) if len(sys.argv) > 1 else {'tag': 'durotar', 'dir': 'kalimdor', 'title': 'Durotar',
    'crop': [1808.3333, -1350.0, -3300.0, -5650.0], 'level': 8, 'faction': 'horde', 'closeups': [{'name': 'Drygulch Ravine', 'box': [1150, 640, -4480, -4960]}]}
os.environ['ZDIR'] = SPEC.get('dir', 'kalimdor')
TAG = SPEC['tag']; TITLE = SPEC.get('title', TAG.title())
from PIL import Image, ImageDraw, ImageFont
import heights as HT
ADT, S, ORG = HT.ADT, HT.S, HT.ORG
HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.environ.get('SAFEPATH_OUT', os.path.join(os.environ.get('SAFEPATH_WORK', 'E:/safepath-work'), 'out'))
T, B, L, R = SPEC['crop']                                             # the zone's land (its world rect less the open sea, its neighbours)
gc0 = int(math.floor((ORG - L) / S)); gc1 = int(math.ceil((ORG - R) / S))
gr0 = int(math.floor((ORG - T) / S)); gr1 = int(math.ceil((ORG - B) / S))
NC = gc1 - gc0 + 1; NR = gr1 - gr0 + 1
H = np.full((NR, NC), np.nan, dtype=np.float32)
for c in range(gc0 // 128, gc1 // 128 + 1):
    for r in range(gr0 // 128, gr1 // 128 + 1):
        t = HT.tile(c, r)[:128, :128]
        a0 = max(gr0, r * 128); a1 = min(gr1 + 1, r * 128 + 128); b0 = max(gc0, c * 128); b1 = min(gc1 + 1, c * 128 + 128)
        if a0 < a1 and b0 < b1: H[a0 - gr0:a1 - gr0, b0 - gc0:b1 - gc0] = t[a0 - r * 128:a1 - r * 128, b0 - c * 128:b1 - c * 128]
# the minimap tiles: 256 px a tile, 2 px a vertex (x east, y south)
TEX = np.zeros((NR * 2, NC * 2, 3), dtype=np.uint8)
for c in range(gc0 // 128, gc1 // 128 + 1):
    for r in range(gr0 // 128, gr1 // 128 + 1):
        p = os.path.join(HT.DIR, 'minimap', '%d_%d.png' % (c, r))
        if not os.path.exists(p): continue
        im = np.asarray(Image.open(p).convert('RGB').resize((256, 256)))
        a0 = max(gr0, r * 128); a1 = min(gr1 + 1, r * 128 + 128); b0 = max(gc0, c * 128); b1 = min(gc1 + 1, c * 128 + 128)
        if a0 < a1 and b0 < b1:
            TEX[(a0 - gr0) * 2:(a1 - gr0) * 2, (b0 - gc0) * 2:(b1 - gc0) * 2] = im[(a0 - r * 128) * 2:(a1 - r * 128) * 2, (b0 - c * 128) * 2:(b1 - c * 128) * 2]
# the water at its own surface (MH2O): the sea and the river drawn flat where they are, never their floor
WS = np.full((NR, NC), np.nan, dtype=np.float32)
for c in range(gc0 // 128, gc1 // 128 + 1):
    for r in range(gr0 // 128, gr1 // 128 + 1):
        t = HT.water_tile(c, r)
        a0 = max(gr0, r * 128); a1 = min(gr1 + 1, r * 128 + 128); b0 = max(gc0, c * 128); b1 = min(gc1 + 1, c * 128 + 128)
        if a0 < a1 and b0 < b1: WS[a0 - gr0:a1 - gr0, b0 - gc0:b1 - gc0] = t[a0 - r * 128:a1 - r * 128, b0 - c * 128:b1 - c * 128]
water = ~np.isnan(WS) & (np.isnan(H) | (WS > H))
H = np.where(water, WS, H)
Hf = np.where(np.isnan(H), np.nanmin(H), H)
# the light from the north-west and above: the surface's normal from the heights (east = +column, south = +row)
dzx = np.gradient(Hf, axis=1) / S; dzy = np.gradient(Hf, axis=0) / S
nrm = np.sqrt(dzx ** 2 + dzy ** 2 + 1)
Lv = np.array([-1.0, -1.0, 1.6]); Lv /= np.linalg.norm(Lv)
shade = ((-dzx * Lv[0] - dzy * Lv[1] + Lv[2]) / nrm).clip(0, 1)
data = json.load(open(os.path.join(OUT, 'levels_routes_%s.json' % TAG), encoding='utf-8'))
COL = [(80, 220, 255), (140, 255, 120), (255, 120, 220), (255, 200, 60), (255, 150, 90), (180, 140, 255), (120, 255, 220), (255, 255, 255), (255, 240, 120), (150, 200, 255)]
CAVE = (190, 90, 255)
data['caves'] = [c for c in data['caves'] if B <= c['wx'] <= T and R <= c['wy'] <= L]
# the danger grid (QuestieDB's hostile mobs, a level 8 Horde player, "high"): 0..4 a 25 yd cell, as the routes weighed it
DG = data['danger']; DGA = np.array(DG['d'], dtype=np.float32).reshape(DG['ny'], DG['nx'])
def dang_at(wx, wy):
    u = ((DG['l'] - wy) / (DG['l'] - DG['r']) * DG['nx']).astype(int).clip(0, DG['nx'] - 1)
    v = ((DG['t'] - wx) / (DG['t'] - DG['b']) * DG['ny']).astype(int).clip(0, DG['ny'] - 1)
    return DGA[v, u]
RRv, CCv = np.meshgrid(np.arange(NR), np.arange(NC), indexing='ij')
DANG = dang_at(ORG - (RRv + gr0) * S, ORG - (CCv + gc0) * S)
DALPHA = np.clip((DANG - 0.3) / 3.7, 0, 1) * 0.55                                  # semi-transparent red: the denser the mobs, the redder
def incave(p): return len(p) > 2 and p[2] in (1, 2, 3)       # 1 a cave's floor, 2 a lift's landing, 3 a declared swim
def atlift(p): return len(p) > 2 and p[2] == 2
def dashed(dr, xy, fill, width, on=10, off=7):
    for (x0, y0), (x1, y1) in zip(xy, xy[1:]):
        L = math.hypot(x1 - x0, y1 - y0); t = 0.0
        if L < 1e-6: continue
        while t < L:
            t1 = min(L, t + on)
            dr.line((x0 + (x1 - x0) * t / L, y0 + (y1 - y0) * t / L, x0 + (x1 - x0) * t1 / L, y0 + (y1 - y0) * t1 / L), fill=fill, width=width)
            t = t1 + off
def atswim(p): return len(p) > 2 and p[2] == 3                          # a declared swim (the user: "Swim link, flagged")
SWIMCOL = (40, 140, 255)
def runs(pts):
    # the route in stretches: on the ground / in a cave (a stretch touching a cave point is the cave's)
    out = []; cur = [pts[0]]; cv = None
    for a, b in zip(pts, pts[1:]):
        c = incave(a) or incave(b)
        if cv is not None and c != cv: out.append((cv, cur)); cur = [a]
        cv = c; cur.append(b)
    out.append((bool(cv), cur)); return out
try: FONT = ImageFont.truetype('arialbd.ttf', 26); FONTS = ImageFont.truetype('arialbd.ttf', 18); FONTB = ImageFont.truetype('arialbd.ttf', 34)
except Exception: FONT = FONTS = FONTB = ImageFont.load_default()
def vert(wx, wy): return (ORG - wx) / S - gr0, (ORG - wy) / S - gc0          # row, column (fractional)
def text(dr, xy, s, f, fill=(255, 255, 255), anchor='mm'):
    x, y = xy
    for dx in (-2, -1, 0, 1, 2):
        for dy in (-2, -1, 0, 1, 2):
            if dx or dy: dr.text((x + dx, y + dy), s, font=f, fill=(0, 0, 0), anchor=anchor)
    dr.text((x, y), s, font=f, fill=fill, anchor=anchor)

# ---------------------------------------------------------------- the top-down height map
SC = 4
lo, hi = np.nanpercentile(H[~water], 1), np.nanpercentile(H[~water], 99.5)
stops = [(0.0, (60, 110, 60)), (0.25, (150, 170, 90)), (0.5, (205, 180, 110)), (0.75, (170, 120, 80)), (1.0, (245, 240, 235))]
def tint(v):
    v = np.clip((v - lo) / (hi - lo), 0, 1); out = np.zeros(v.shape + (3,))
    for (a, ca), (b, cb) in zip(stops, stops[1:]):
        m = (v >= a) & (v <= b); t = ((v - a) / (b - a))[m][:, None]
        out[m] = np.array(ca) * (1 - t) + np.array(cb) * t
    return out
img = tint(Hf) * (0.35 + 0.75 * shade)[..., None]
img[water] = (50, 80, 120)
band = np.floor(Hf / 10.0)
cont = (np.abs(np.diff(band, axis=1, prepend=band[:, :1])) > 0) | (np.abs(np.diff(band, axis=0, prepend=band[:1, :])) > 0)
img[cont & ~water] *= 0.55
img = img * (1 - DALPHA[..., None]) + np.array([230, 30, 30]) * DALPHA[..., None]
top = Image.fromarray(img.clip(0, 255).astype(np.uint8)).resize((NC * SC, NR * SC), Image.BILINEAR)
dr = ImageDraw.Draw(top)
for z in data['subzones']:
    if z['n'] < 15 or z['name'] in ('The Great Sea',): continue
    r_, c_ = vert(z['wx'], z['wy'])
    if 0 < r_ < NR and 0 < c_ < NC: text(dr, (c_ * SC, r_ * SC), z['name'], FONTS, (255, 255, 230))
for e in data.get('net', {}).get('edges', []):
    if e[4] == 'cave': continue
    (r0_, c0_), (r1_, c1_) = vert(e[0], e[1]), vert(e[2], e[3]); xy = [(c0_ * SC, r0_ * SC), (c1_ * SC, r1_ * SC)]
    if e[4] == 'road': dr.line(xy, fill=(0, 0, 0), width=6); dr.line(xy, fill=(245, 235, 200), width=3)
    else: dashed(dr, xy, (0, 0, 0), 4, 8, 6); dashed(dr, xy, (230, 230, 230), 2, 8, 6)
for ex in data.get('exitChecks', []):
    r_, c_ = vert(ex[0], ex[1]); x, y = c_ * SC, r_ * SC
    if ex[4]: dr.rectangle((x - 9, y - 9, x + 9, y + 9), fill=(60, 200, 90), outline=(0, 0, 0), width=2)
    else: dr.line((x - 10, y - 10, x + 10, y + 10), fill=(230, 30, 30), width=5); dr.line((x - 10, y + 10, x + 10, y - 10), fill=(230, 30, 30), width=5)
    text(dr, (x + 14, y), 'exit: %s (%s)%s' % (ex[2], ex[3], '' if ex[4] else ' - NOT walkable'), FONTS, (200, 255, 200) if ex[4] else (255, 150, 150), 'lm')
for cv in data['caves']:
    for w in cv['ways']:
        xy = [(c * SC, r * SC) for r, c in (vert(p[0], p[1]) for p in w)]
        dashed(dr, xy, (0, 0, 0), 7); dashed(dr, xy, CAVE, 4)
    for m in cv['mouths']:
        r, c = vert(m[0], m[1]); x, y = c * SC, r * SC
        dr.polygon([(x, y - 8), (x + 8, y), (x, y + 8), (x - 8, y)], fill=CAVE, outline=(0, 0, 0))
LINK = (60, 230, 255)
for lk in data.get('manualLinks', []):
    xy = [(c * SC, r * SC) for r, c in (vert(p[0], p[1]) for p in lk['pts'])]
    dashed(dr, xy, (0, 0, 0), 8, 14, 6); dashed(dr, xy, LINK, 4, 14, 6)
    mx, my = xy[len(xy) // 2]
    text(dr, (mx + 10, my), 'manual link: ' + lk['kind'], FONTS, LINK, 'lm')
# the lifts (the spec's, measured from their WMOs' landings): an orange square on the shaft
for lf in SPEC.get('lifts', []):
    r_, c_ = vert(lf['x'], lf['y']); x, y = c_ * SC, r_ * SC
    dr.rectangle((x - 7, y - 7, x + 7, y + 7), outline=(255, 150, 30), width=3)
for i, rt in enumerate(data['routes']):
    pts = [vert(p[0], p[1]) for p in rt['pts']]; xy = [(c * SC, r * SC) for r, c in pts]
    for cv, part in runs(rt['pts']):
        pxy = [(c * SC, r * SC) for r, c in (vert(p[0], p[1]) for p in part)]
        if cv: dashed(dr, pxy, (0, 0, 0), 9); dashed(dr, pxy, SWIMCOL if any(atswim(p) for p in part) else COL[i % len(COL)], 5)
        else: dr.line(pxy, fill=(0, 0, 0), width=9); dr.line(pxy, fill=COL[i % len(COL)], width=5)
    for k, (x, y) in enumerate(xy):
        if k == 0: dr.ellipse((x - 9, y - 9, x + 9, y + 9), fill=(120, 220, 110), outline=(0, 0, 0), width=2); continue
        if k == len(xy) - 1: dr.ellipse((x - 10, y - 10, x + 10, y + 10), fill=(255, 209, 0), outline=(0, 0, 0), width=2); text(dr, (x + 14, y - 14), rt['letter'], FONT, COL[i % len(COL)], 'lm'); continue
        if atlift(rt['pts'][k]) and not atlift(rt['pts'][k - 1]):           # a lift's first landing: the "take the lift" flag
            dr.rectangle((x - 16, y - 16, x + 16, y + 16), fill=(255, 150, 30), outline=(0, 0, 0), width=3)
            text(dr, (x + 20, y + 18), '%s%d take the lift' % (rt['letter'], k), FONTS, (255, 200, 120), 'lm'); continue
        if atswim(rt['pts'][k]) and not atswim(rt['pts'][k - 1]):           # a declared swim's first point: the "swim here" flag
            dr.ellipse((x - 16, y - 16, x + 16, y + 16), fill=SWIMCOL, outline=(0, 0, 0), width=3)
            text(dr, (x + 20, y + 18), '%s%d swim here' % (rt['letter'], k), FONTS, (150, 200, 255), 'lm'); continue
        if incave(rt['pts'][k]): continue                                   # the cave's floor points: its dashes, no flags
        dr.ellipse((x - 13, y - 13, x + 13, y + 13), fill=(255, 255, 255), outline=(0, 0, 0), width=2)
        dr.text((x, y), '%s%d' % (rt['letter'], k), font=FONTS, fill=(0, 0, 0), anchor='mm')
legend = '%s from its real heights (top-down): tint %.0f - %.0f yd, hillshade, 10 yd contours; red = mob danger (to a level %d %s character); cream = drafted roads, white dashes = trails; orange squares = lift shafts, a filled one the take-the-lift flag; green squares = exits walkable on the ground, red X = not; purple dashes = cave floors, diamonds their mouths; routes (dashed inside a cave; blue dashes a declared swim, its blue flag "swim here")' % (TITLE, lo, hi, SPEC.get('level', 8), SPEC.get('faction', 'horde').title())
text(dr, (16, NR * SC - 24), legend, FONTS, anchor='lm')
for i, rt in enumerate(data['routes']): text(dr, (16, 24 + i * 30), '%s. %s' % (rt['letter'], rt['name']), FONTS, COL[i % len(COL)], 'lm')
top.save(os.path.join(OUT, '%s_heightmap.png' % TAG))
# the close-ups (a valley, a mine): 2x of the height map
for cu in SPEC.get('closeups', []):
    bt, bb, bl, br = cu['box']
    r0, c0 = vert(bt, bl); r1, c1 = vert(bb, br)
    crop = top.crop((int(min(c0, c1) * SC), int(min(r0, r1) * SC), int(max(c0, c1) * SC), int(max(r0, r1) * SC)))
    crop = crop.resize((crop.size[0] * 2, crop.size[1] * 2), Image.LANCZOS)
    dc = ImageDraw.Draw(crop); text(dc, (20, 30), '%s: purple dashes cave floors, diamonds their mouths, red its mobs' % cu['name'], FONT, anchor='lm')
    crop.save(os.path.join(OUT, '%s_%s.png' % (TAG, cu['name'].lower().replace(' ', '_').replace("'", ''))))

# ---------------------------------------------------------------- the 3D view: from the south-east, looking north-west
EX = 1.5; EL = math.radians(38); STEP = 2
rows = np.arange(0, NR, STEP); cols = np.arange(0, NC, STEP)
RR, CC = np.meshgrid(rows, cols, indexing='ij')
Z = Hf[RR, CC]
fwd = -(RR + CC) * S / math.sqrt(2)            # north-west is far
rgt = (CC - RR) * S / math.sqrt(2)             # north-east is right
def proj(f, r, z): return r, -(f * math.sin(EL) + z * EX * math.cos(EL))
SX, SY = proj(fwd, rgt, Z)
W3 = 3000
sx0, sx1 = SX.min(), SX.max(); sy0, sy1 = SY.min(), SY.max()
k = (W3 - 80) / (sx1 - sx0); H3 = int((sy1 - sy0) * k) + 120
def to_px(x, y): return (x - sx0) * k + 40, (y - sy0) * k + 80
PX, PY = to_px(SX, SY)
im3 = Image.new('RGB', (W3, H3), (25, 30, 40)); d3 = ImageDraw.Draw(im3)
# the quads, far to near (row + column ascending is north-west first)
order = sorted(((i, j) for i in range(len(rows) - 1) for j in range(len(cols) - 1)), key=lambda t: t[0] + t[1])
for i, j in order:
    r_, c_ = rows[i], cols[j]
    if water[r_, c_] and water[min(r_ + STEP, NR - 1), min(c_ + STEP, NC - 1)]:
        t = TEX[min(r_ * 2 + STEP, TEX.shape[0] - 1), min(c_ * 2 + STEP, TEX.shape[1] - 1)].astype(float)
        col = tuple(int(v) for v in (t * 0.5 + np.array([40, 80, 130]) * 0.5).clip(0, 255))
    else:
        t = TEX[min(r_ * 2 + STEP, TEX.shape[0] - 1), min(c_ * 2 + STEP, TEX.shape[1] - 1)].astype(float)
        sh = shade[r_, c_]; a = DALPHA[r_, c_] * 0.7
        col = tuple(int(v) for v in ((t * (0.45 + 0.75 * sh)) * (1 - a) + np.array([230, 30, 30]) * a).clip(0, 255))
    poly = [(PX[i, j], PY[i, j]), (PX[i, j + 1], PY[i, j + 1]), (PX[i + 1, j + 1], PY[i + 1, j + 1]), (PX[i + 1, j], PY[i + 1, j])]
    d3.polygon(poly, fill=col)
def p3(wx, wy, lift=2.0, z=None):
    r_, c_ = vert(wx, wy)
    if z is not None:
        f = -(r_ + c_) * S / math.sqrt(2); rr = (c_ - r_) * S / math.sqrt(2)
        return to_px(*proj(f, rr, z + lift))
    z = HT.height(wx, wy)
    if math.isnan(z): z = float(np.nanmin(H))
    ri, ci = int(min(max(r_, 0), NR - 1)), int(min(max(c_, 0), NC - 1))
    if water[ri, ci]: z = max(z, float(H[ri, ci]))
    f = -(r_ + c_) * S / math.sqrt(2); rr = (c_ - r_) * S / math.sqrt(2)
    return to_px(*proj(f, rr, z + lift))
for z in data['subzones']:
    if z['n'] < 15 or z['name'] in ('The Great Sea',): continue
    text(d3, p3(z['wx'], z['wy'], 20), z['name'], FONTS, (255, 255, 230))
# a cave's floor height at a point: its nearest way point's
CPTS = [p for cv in data['caves'] for w in cv['ways'] for p in w]
def cave_z(x, y): return min(CPTS, key=lambda p: (p[0] - x) ** 2 + (p[1] - y) ** 2)[2] if CPTS else None
for lk in data.get('manualLinks', []):
    xy = [p3(p[0], p[1], 2, p[2]) for p in lk['pts']]
    dashed(d3, xy, (0, 0, 0), 7, 14, 6); dashed(d3, xy, (60, 230, 255), 4, 14, 6)
    text(d3, xy[len(xy) // 2], lk['kind'], FONTS, (60, 230, 255))
for cv in data['caves']:
    for w in cv['ways']:
        xy = [p3(p[0], p[1], 2, p[2]) for p in w]
        dashed(d3, xy, (0, 0, 0), 7); dashed(d3, xy, CAVE, 4)
    for m in cv['mouths']:
        x, y = p3(m[0], m[1], 2, m[2]); d3.polygon([(x, y - 8), (x + 8, y), (x, y + 8), (x - 8, y)], fill=CAVE, outline=(0, 0, 0))
for i, rt in enumerate(data['routes']):
    pts = rt['pts']
    for cv, part in runs(pts):
        if cv:
            sw = any(atswim(p) for p in part)
            xy = [p3(p[0], p[1], 2) if sw else p3(p[0], p[1], 2, cave_z(p[0], p[1])) for p in part]
            dashed(d3, xy, (0, 0, 0), 8); dashed(d3, xy, SWIMCOL if sw else COL[i % len(COL)], 4); continue
        dense = []
        for a, b in zip(part, part[1:]):
            n = max(1, int(math.hypot(b[0] - a[0], b[1] - a[1]) / 8))
            for t in range(n): dense.append((a[0] + (b[0] - a[0]) * t / n, a[1] + (b[1] - a[1]) * t / n))
        dense.append(tuple(part[-1][:2]))
        xy = [p3(x, y) for x, y in dense]
        d3.line(xy, fill=(0, 0, 0), width=8); d3.line(xy, fill=COL[i % len(COL)], width=4)
    for kk, p in enumerate(pts):
        x, y = p3(p[0], p[1], 3)
        if kk == 0: d3.ellipse((x - 8, y - 8, x + 8, y + 8), fill=(120, 220, 110), outline=(0, 0, 0), width=2); continue
        if kk == len(pts) - 1: d3.ellipse((x - 9, y - 9, x + 9, y + 9), fill=(255, 209, 0), outline=(0, 0, 0), width=2); text(d3, (x + 14, y - 14), rt['letter'], FONT, COL[i % len(COL)], 'lm'); continue
        if atlift(p) and not atlift(pts[kk - 1]):
            x, y = p3(p[0], p[1], 3, cave_z(p[0], p[1]))
            d3.rectangle((x - 12, y - 40, x + 12, y - 16), fill=(255, 150, 30), outline=(0, 0, 0), width=2)
            d3.text((x, y - 28), 'L', font=FONTS, fill=(0, 0, 0), anchor='mm'); continue
        if incave(p): continue
        d3.line((x, y, x, y - 26), fill=(0, 0, 0), width=3)
        d3.ellipse((x - 12, y - 38, x + 12, y - 14), fill=(255, 255, 255), outline=(0, 0, 0), width=2)
        d3.text((x, y - 26), '%s%d' % (rt['letter'], kk), font=FONTS, fill=(0, 0, 0), anchor='mm')
text(d3, (20, 30), '%s in 3D from its real heights (1.5x), minimap tiles draped, looking north-west; red = mob danger to a level %d %s character, purple dashes = cave floors' % (TITLE, SPEC.get('level', 8), SPEC.get('faction', 'horde').title()), FONTB, anchor='lm')
for i, rt in enumerate(data['routes']): text(d3, (20, 80 + i * 30), '%s. %s' % (rt['letter'], rt['name']), FONT, COL[i % len(COL)], 'lm')
im3.save(os.path.join(OUT, '%s_3d.png' % TAG))
print('top-down', top.size, '3d', im3.size, 'heights %.0f..%.0f' % (lo, hi))
