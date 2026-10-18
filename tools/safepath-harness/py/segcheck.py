# every flag-to-flag segment of the test routes against the real heights, at the 4 yd vertex level (the hard rule: a step of 45
# degrees or more is a wall, never crossed). The line's own vertices (as Climb.walkable walks them), each step's slope from the ADT
# outer heights (a diagonal by the easier of its corner pairs, the way a player steps round it); plus, for comparison, the steepest
# 2 yd stretch of the straight line on the bilinear surface.
import json, math, os, sys
import heights as HT
try: sys.stdout.reconfigure(encoding='utf-8')     # the routes' names carry arrows (Windows' console is cp1252)
except Exception: pass
S = HT.S; ORG = HT.ORG
WALL = 45.0
def vh(gc, gr):
    c = gc // 128; r = gr // 128
    v = HT.tile(c, r)[gr - r * 128, gc - c * 128]
    return float(v)
def deg(h0, h1, run):
    if math.isnan(h0) or math.isnan(h1): return 90.0
    return math.degrees(math.atan(abs(h1 - h0) / run))
import faces as FC
def face_deg(ha, hb, m):
    """the triangle on an edge (heights ha, hb, S apart) and a cell's middle vertex m: its face's slope"""
    if math.isnan(ha) or math.isnan(hb): return 90.0
    if math.isnan(m): return deg(ha, hb, S)
    return FC.tri_slope((0, 0, ha), (S, 0, hb), (S / 2, S / 2, m))
def edge_deg(gc, gr, dc, dr):
    """an edge step (one of the 4): its vertices' own slope, or the gentler of the two faces beside it when steeper (as Climb.build)"""
    if dr == 0:
        c0 = min(gc, gc + dc); ha, hb = vh(c0, gr), vh(c0 + 1, gr)
        f = min(face_deg(ha, hb, FC.centre(gr - 1, c0)), face_deg(ha, hb, FC.centre(gr, c0)))
    else:
        r0 = min(gr, gr + dr); ha, hb = vh(gc, r0), vh(gc, r0 + 1)
        f = min(face_deg(ha, hb, FC.centre(r0, gc - 1)), face_deg(ha, hb, FC.centre(r0, gc)))
    return max(deg(ha, hb, S), f)
def step_deg(gc, gr, dc, dr):
    if dr == 0 or dc == 0: return edge_deg(gc, gr, dc, dr)
    a = max(edge_deg(gc, gr, dc, 0), edge_deg(gc + dc, gr, 0, dr))
    b = max(edge_deg(gc, gr, 0, dr), edge_deg(gc, gr + dr, dc, 0))
    return min(a, b)
_calib = None
def kround(x): return int(math.floor(x + 0.5))
def vertex(wx, wy): return kround(((ORG - wy) / S)), kround(((ORG - wx) / S))
def lattice_worst(a, b):
    s = vertex(*a); e = vertex(*b)
    n = max(abs(e[0] - s[0]), abs(e[1] - s[1])); worst = 0.0; walls = 0
    ci, cj = s
    for q in range(1, n + 1):
        ni = s[0] + kround(((e[0] - s[0]) * q / n)); nj = s[1] + kround(((e[1] - s[1]) * q / n))
        di = (ni > ci) - (ni < ci); dj = (nj > cj) - (nj < cj)
        if di == 0 and dj == 0: continue
        d = step_deg(ci, cj, di, dj)
        if d >= WALL:
            global _calib
            if _calib is None:
                import calib as _c; _calib = _c
            # a building's or a cave's floor there (as Climb.build's level over a covered hole): its terrain no wall
            if _calib.covered(ORG - (cj + nj) / 2 * S, ORG - (ci + ni) / 2 * S): ci, cj = ni, nj; continue
            walls += 1
        worst = max(worst, d)
        ci, cj = ni, nj
    return worst, walls
def line_faces(a, b):
    """the straight line's faces: sampled each half yard, at each the gentlest face within a quarter yard (as Climb's lineClear);
    a building's footprint over a hole is its floor. Its steepest and how many samples at the wall limit."""
    global _calib
    if _calib is None:
        import calib as _c; _calib = _c
    L = math.hypot(b[0] - a[0], b[1] - a[1]); n = max(1, int(L / 0.5)); worst = 0.0; walls = 0
    for q in range(n + 1):
        x = a[0] + (b[0] - a[0]) * q / n; y = a[1] + (b[1] - a[1]) * q / n
        f = min(FC.face_at(x + dx, y + dy) for dx, dy in ((0, 0), (0.25, 0), (-0.25, 0), (0, 0.25), (0, -0.25)))
        if f >= WALL and _calib.covered(x, y): continue
        worst = max(worst, f)
        if f >= WALL: walls += 1
    return worst, walls
def surface_worst(a, b):
    L = math.hypot(b[0] - a[0], b[1] - a[1]); n = max(2, int(L / 2)); worst = 0.0; prev = a
    for s in range(1, n + 1):
        t = s / n; p = (a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t)
        worst = max(worst, math.degrees(math.atan(abs(HT.height(*p) - HT.height(*prev)) / max(1e-6, L / n)))); prev = p
    return worst
if __name__ == '__main__':
    f = sys.argv[1] if len(sys.argv) > 1 else os.path.join(os.environ.get('SAFEPATH_WORK', 'E:/safepath-work'), 'out', 'levels_routes_durotar.json')
    d = json.load(open(f, encoding='utf-8'))
    bad = []; total = 0
    for rt in d['routes']:
        pts = rt['pts']
        for k in range(len(pts) - 1):
            a, b = pts[k], pts[k + 1]; total += 1
            if (len(a) > 2 and a[2] in (1, 2)) or (len(b) > 2 and b[2] in (1, 2)): continue      # a cave: its own floor, not the hill over it
            w, n = lattice_worst(a, b); sw = surface_worst(a, b); fw, fn = line_faces(a, b)
            if n: bad.append('%s%d-%s%d (%.0f deg, %d wall steps)' % (rt['letter'], k, rt['letter'], k + 1, w, n))
            if fn: bad.append('%s%d-%s%d (faces: %.0f deg over %.1f yd)' % (rt['letter'], k, rt['letter'], k + 1, fw, fn * 0.5))
            if '-v' in sys.argv: print('%s%d->%s%d  %5.0f yd  vertex steps steepest %4.1f  surface %4.1f  %s' % (rt['letter'], k, rt['letter'], k + 1, math.hypot(b[0] - a[0], b[1] - a[1]), w, sw, 'WALL' if n else 'ok'))
    print('%d segments; crossing a %d+ degree step: %s' % (total, WALL, ', '.join(bad) or 'none'))
