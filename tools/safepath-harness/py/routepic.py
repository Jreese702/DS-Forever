# BEFORE / AFTER, one close-up: two harness runs of one spec side by side over the zone's minimap drape and hillshade - the lips (red:
# a lattice vertex within 3 yd of a drop, Climb.dropsOf; drops_<tag>.json), the void (black: no ground, a spec's voidBelow), deep water
# (blue), the buildings (magenta: their rooms, WorldBuildings.feet; grey: the whole WMO's box), the drafted network (cream), each route
# (solid, its flags) and the device's own way today (dashed white: MapView.netLeg / dangerLeg, the DEVICE lines)
#   python routepic.py <spec.json> <before dir> <after dir> <top x> <bottom x> <left y> <right y> <out.png> [letters] [--px 1.5] [--title "..."]
import os, sys, json, math, argparse
ap = argparse.ArgumentParser()
ap.add_argument('spec'); ap.add_argument('before'); ap.add_argument('after')
ap.add_argument('box', nargs=4, type=float); ap.add_argument('out'); ap.add_argument('letters', nargs='?', default='')
ap.add_argument('--px', type=float, default=1.5); ap.add_argument('--title', default='')
ap.add_argument('--labels', default='before (2d37f15),after (edge clearance)')
ap.add_argument('--nodevice', action='store_true')
A = ap.parse_args()
SPEC = json.load(open(A.spec, encoding='utf-8'))
os.environ['ZDIR'] = SPEC.get('dir', 'kalimdor')
import numpy as np
from PIL import Image, ImageDraw, ImageFont
import heights as HT
S, ORG = HT.S, HT.ORG
T, B, L, R = A.box
gc0 = int(math.floor((ORG - L) / S)); gc1 = int(math.ceil((ORG - R) / S))
gr0 = int(math.floor((ORG - T) / S)); gr1 = int(math.ceil((ORG - B) / S))
NC = gc1 - gc0 + 1; NR = gr1 - gr0 + 1
H = np.full((NR, NC), np.nan, dtype=np.float32); WS = np.full((NR, NC), np.nan, dtype=np.float32)
TEX = np.full((NR * 2, NC * 2, 3), 90, dtype=np.uint8)
for c in range(gc0 // 128, gc1 // 128 + 1):
    for r in range(gr0 // 128, gr1 // 128 + 1):
        a0 = max(gr0, r * 128); a1 = min(gr1 + 1, r * 128 + 128); b0 = max(gc0, c * 128); b1 = min(gc1 + 1, c * 128 + 128)
        if a0 >= a1 or b0 >= b1: continue
        H[a0 - gr0:a1 - gr0, b0 - gc0:b1 - gc0] = HT.tile(c, r)[a0 - r * 128:a1 - r * 128, b0 - c * 128:b1 - c * 128]
        WS[a0 - gr0:a1 - gr0, b0 - gc0:b1 - gc0] = HT.water_tile(c, r)[a0 - r * 128:a1 - r * 128, b0 - c * 128:b1 - c * 128]
        p = os.path.join(HT.DIR, 'minimap', '%d_%d.png' % (c, r))
        if os.path.exists(p):
            im = np.asarray(Image.open(p).convert('RGB').resize((256, 256)))
            TEX[(a0 - gr0) * 2:(a1 - gr0) * 2, (b0 - gc0) * 2:(b1 - gc0) * 2] = im[(a0 - r * 128) * 2:(a1 - r * 128) * 2, (b0 - c * 128) * 2:(b1 - c * 128) * 2]
vb = SPEC.get('voidBelow')
void = np.isnan(H) | ((H < vb) if vb is not None else False)
Hf = np.where(np.isnan(H), np.nanmin(H) if np.isfinite(np.nanmin(H)) else 0, H)
dzx = np.gradient(Hf, axis=1) / S; dzy = np.gradient(Hf, axis=0) / S
nrm = np.sqrt(dzx ** 2 + dzy ** 2 + 1); Lv = np.array([-1.0, -1.0, 1.6]); Lv /= np.linalg.norm(Lv)
shade = ((-dzx * Lv[0] - dzy * Lv[1] + Lv[2]) / nrm).clip(0, 1)
deepw = ~np.isnan(WS) & ~np.isnan(H) & (WS - H > 1.5)
W = int((L - R) * A.px); Hh = int((T - B) * A.px)
def base():
    tex = Image.fromarray(TEX).resize((W, Hh), Image.BILINEAR)
    sh = Image.fromarray((shade * 255).astype(np.uint8)).resize((W, Hh), Image.BILINEAR)
    t = np.asarray(tex).astype(np.float32) * (0.35 + 0.65 * np.asarray(sh)[..., None] / 255.0)
    vm = np.asarray(Image.fromarray(void.astype(np.uint8) * 255).resize((W, Hh), Image.NEAREST)) > 0
    wm = np.asarray(Image.fromarray(deepw.astype(np.uint8) * 255).resize((W, Hh), Image.NEAREST)) > 0
    t[wm] = t[wm] * 0.4 + np.array([30, 90, 200]) * 0.6
    t[vm] = (12, 12, 18)
    return Image.fromarray(t.clip(0, 255).astype(np.uint8))
def px(wx, wy): return ((L - wy) * A.px, (T - wx) * A.px)
try:
    FONT = ImageFont.truetype('arialbd.ttf', 15); FONTS = ImageFont.truetype('arial.ttf', 12); FONTB = ImageFont.truetype('arialbd.ttf', 18)
except Exception:
    FONT = FONTS = FONTB = ImageFont.load_default()
COL = [(80, 220, 255), (140, 255, 120), (255, 120, 220), (255, 200, 60), (255, 150, 90), (180, 140, 255), (120, 255, 220), (255, 255, 255)]
def dashed(dr, a, b, fill, width, on=9, off=6):
    d = math.hypot(b[0] - a[0], b[1] - a[1]); n = max(1, int(d / (on + off)))
    for q in range(n + 1):
        t0 = q * (on + off) / max(d, 1e-9); t1 = min(1.0, t0 + on / max(d, 1e-9))
        if t0 >= 1: break
        dr.line([(a[0] + (b[0] - a[0]) * t0, a[1] + (b[1] - a[1]) * t0), (a[0] + (b[0] - a[0]) * t1, a[1] + (b[1] - a[1]) * t1)], fill=fill, width=width)
def yd(pts): return sum(math.hypot(p[0] - q[0], p[1] - q[1]) for p, q in zip(pts, pts[1:]))
def panel(d, label):
    tag = SPEC['tag']
    data = json.load(open(os.path.join(d, 'levels_routes_%s.json' % tag), encoding='utf-8'))
    im = base(); dr = ImageDraw.Draw(im)
    for e in data['net']['edges']:
        a = px(e[0], e[1]); b = px(e[2], e[3])
        (dr.line([a, b], fill=(255, 235, 170), width=3) if e[4] == 'road' else dashed(dr, a, b, (255, 235, 170), 2, 5, 5))
    try:
        for g in json.load(open(os.path.join(d, 'drops_%s.json' % tag))):
            x, y = px(ORG - g[0] * S, ORG - g[1] * S)
            if 0 <= x < W and 0 <= y < Hh: dr.ellipse((x - 1.5, y - 1.5, x + 1.5, y + 1.5), fill=(235, 40, 40))
    except FileNotFoundError: pass
    try:
        for rm in json.load(open(os.path.join(d, 'rooms_%s.json' % tag))):
            pts = [px(rm[2 + 2 * q], rm[3 + 2 * q]) for q in range(4)]
            if all(p[0] < 0 or p[0] > W for p in pts) or all(p[1] < 0 or p[1] > Hh for p in pts): continue
            dr.polygon(pts, outline={1: (255, 60, 255), 2: (255, 150, 30)}.get(rm[1], (170, 170, 170)), width=2 if rm[1] else 1)
    except FileNotFoundError: pass
    rows = []
    for i, rt in enumerate(data['routes']):
        if A.letters and rt['letter'] not in A.letters: continue
        c = COL[i % len(COL)]
        xy = [px(p[0], p[1]) for p in rt['pts']]
        dv = rt.get('device')
        if dv and not A.nodevice:
            dxy = [px(p[0], p[1]) for p in dv]
            for a, b in zip(dxy, dxy[1:]): dashed(dr, a, b, (0, 0, 0), 6); dashed(dr, a, b, (255, 255, 255), 3)
            for p in dxy[1:-1]: dr.rectangle((p[0] - 4, p[1] - 4, p[0] + 4, p[1] + 4), fill=(255, 255, 255), outline=(0, 0, 0))
        dr.line(xy, fill=(0, 0, 0), width=7); dr.line(xy, fill=c, width=4)
        for k, p in enumerate(xy[1:-1], 1):
            dr.ellipse((p[0] - 9, p[1] - 9, p[0] + 9, p[1] + 9), fill=(255, 255, 255), outline=(0, 0, 0), width=2)
            dr.text(p, '%d' % k, font=FONTS, fill=(0, 0, 0), anchor='mm')
        s = xy[0]; g = xy[-1]
        dr.rectangle((s[0] - 7, s[1] - 7, s[0] + 7, s[1] + 7), fill=c, outline=(0, 0, 0), width=2)
        dr.ellipse((g[0] - 9, g[1] - 9, g[0] + 9, g[1] + 9), fill=(255, 209, 0), outline=(0, 0, 0), width=2)
        dr.text((g[0] + 12, g[1] - 12), rt['letter'], font=FONTB, fill=c, stroke_width=2, stroke_fill=(0, 0, 0))
        rows.append((c, '%s. %s: %d yd%s' % (rt['letter'], rt['name'], yd(rt['pts']), '' if A.nodevice or not dv else ' (device: %s, %d yd)' % (rt.get('deviceHow', ''), yd(dv)))))
    dr.rectangle((0, 0, W, 26 + 18 * len(rows)), fill=(0, 0, 0))
    dr.text((8, 4), label, font=FONTB, fill=(255, 255, 255))
    for q, (c, t) in enumerate(rows): dr.text((8, 26 + 18 * q), t, font=FONTS, fill=c)
    return im
labs = A.labels.split(',')
a = panel(A.before, labs[0]); b = panel(A.after, labs[1])
foot = 46
out = Image.new('RGB', (W * 2 + 10, Hh + foot), (0, 0, 0)); out.paste(a, (0, 0)); out.paste(b, (W + 10, 0))
dr = ImageDraw.Draw(out)
dr.text((8, Hh + 4), A.title or SPEC.get('title', ''), font=FONT, fill=(255, 255, 255))
dr.text((8, Hh + 24), 'north up, %.1f px a yard. Red dots: a drop\'s lip (lattice vertex within 3 yd of ground falling 5+ yd or into the void); black: the void; blue: deep water; '
        'magenta: building rooms (solid), orange: an outdoor-only building (the device cost), grey: a WMO box; cream: drafted roads (dashes trails); solid: the harness route, dashed white: the device way (MapView netLeg / dangerLeg)' % A.px, font=FONTS, fill=(220, 220, 220))
out.save(A.out); print('wrote', A.out, out.size)
