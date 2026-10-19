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
# the user's short steep step (2026-10-18, "Allow short steps to 48°"; Climb.SHORT_STEEP_DEG / SHORT_STEEP_YD): one lattice step under 48
# degrees whose neighbours along its line are no walls, or a line's stretch of 45-48 degree faces no longer than 2 yd, is walked
SHORT_DEG = 48.0
SHORT_YD = 2.0
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
def edge_short(gc, gr, dc, dr):
    """an edge step under SHORT_DEG whose neighbours along its own line are no walls (Climb.build's lone short steep step)"""
    if not (WALL <= edge_deg(gc, gr, dc, dr) < SHORT_DEG): return False
    return edge_deg(gc - dc, gr - dr, dc, dr) < WALL and edge_deg(gc + dc, gr + dr, dc, dr) < WALL
def step_ok(gc, gr, dc, dr):
    """a lattice step no wall: under 45 degrees, or a lone short steep one (a diagonal by either of its corner pairs)"""
    def edge_ok(c, r, ddc, ddr): return edge_deg(c, r, ddc, ddr) < WALL or edge_short(c, r, ddc, ddr)
    if dr == 0 or dc == 0: return edge_ok(gc, gr, dc, dr)
    return (edge_ok(gc, gr, dc, 0) and edge_ok(gc + dc, gr, 0, dr)) or (edge_ok(gc, gr, 0, dr) and edge_ok(gc, gr + dr, dc, 0))
def step_deg(gc, gr, dc, dr):
    if dr == 0 or dc == 0: return edge_deg(gc, gr, dc, dr)
    a = max(edge_deg(gc, gr, dc, 0), edge_deg(gc + dc, gr, 0, dr))
    b = max(edge_deg(gc, gr, 0, dr), edge_deg(gc, gr + dr, dc, 0))
    return min(a, b)
# THE FLOORS A WALKER IS ON instead of the terrain (the harness writes them beside the routes: floors_<tag>.json, each deck's floor
# triangles in 3D; holes_<tag>.json, the terrain's holes a building's floor covers). Every WMO's bounding box was taken for a floor
# here once, and the whole ground under and around a building went unchecked (Wetlands' route G up a 60-75 degree hillside over a
# buried tunnel's floor)
DECK_MARGIN = 1.0          # as WorldBuildings.DECK_MARGIN_YD
BURIED = 1.0               # a floor this far under the ground or more is under it (a tunnel's), not on it
STEP_UP = 1.5              # a step down off a floor or a ledge (Caves.STEP_UP_YD); deeper is a drop, a wall
EDGE_UP = STEP_UP + 0.5    # a step on or off a floor's edge (Climb.build: a floor more than a step over its own ground ends in a wall), a half yard's slope allowed
                           # steps over that run; a platform's 2 yd edge is its stairs' or a ramp's the lattice can't see finer)
RISE = 1.5                 # a step up within half a yard (Caves.STEP_UP_YD, a stair's join): higher is a parapet, a wall top, a roof - not walked onto
_floors = None; _holes = set(); _fgrid = {}; _layers = {}
def load_floors(routes_file):
    global _floors, _holes
    d = os.path.dirname(routes_file); tag = os.path.basename(routes_file).replace('levels_routes_', '').replace('.json', '')
    fp = os.path.join(d, 'floors_%s.json' % tag); hp = os.path.join(d, 'holes_%s.json' % tag)
    _floors = json.load(open(fp)) if os.path.exists(fp) else []
    _holes = {(a, b) for a, b in json.load(open(hp))} if os.path.exists(hp) else set()
    lp = os.path.join(d, 'layers_%s.json' % tag)
    _layers.clear(); _layers.update({(a, b): z for a, b, z in json.load(open(lp))} if os.path.exists(lp) else {})
    _floors = [t for t in _floors if len(t) < 10 or t[9] != 2] + [t for t in _floors if len(t) >= 10 and t[9] == 2]
    for k, t in enumerate(_floors):
        for i in range(int(math.floor((min(t[0], t[3], t[6]) - DECK_MARGIN) / 16)), int(math.floor((max(t[0], t[3], t[6]) + DECK_MARGIN) / 16)) + 1):
            for j in range(int(math.floor((min(t[1], t[4], t[7]) - DECK_MARGIN) / 16)), int(math.floor((max(t[1], t[4], t[7]) + DECK_MARGIN) / 16)) + 1):
                _fgrid.setdefault((i, j), []).append(k)
    return len(_floors), len(_holes)
def _segd(ax, ay, bx, by, x, y):
    dx, dy = bx - ax, by - ay; l2 = dx * dx + dy * dy; u = 0 if l2 <= 0 else max(0, min(1, ((x - ax) * dx + (y - ay) * dy) / l2))
    return math.hypot(ax + u * dx - x, ay + u * dy - y)
LEVEL_BURIED = 8.0         # a floor this far under the terrain or more is under it, never the ground (RenderSafePath.LEVEL_BURIED_YD)
def level_floor(x, y, g=None, cap_near=False):
    """on a floor the lattice takes as level ground (a city's street, a building's porch: floors_<tag>.json's 0): no terrain check"""
    for k in _fgrid.get((int(math.floor(x / 16)), int(math.floor(y / 16))), []):
        t = _floors[k]
        if t[9] == 1: continue
        x0, y0, z0, x1, y1, z1, x2, y2, z2 = t[:9]
        det = (y1 - y2) * (x0 - x2) + (x2 - x1) * (y0 - y2)
        if abs(det) < 1e-9: continue
        a = ((y1 - y2) * (x - x2) + (x2 - x1) * (y - y2)) / det; b = ((y2 - y0) * (x - x2) + (x0 - x2) * (y - y2)) / det
        if (a >= 0 and b >= 0 and a + b <= 1) or min(_segd(x0, y0, x1, y1, x, y), _segd(x1, y1, x2, y2, x, y), _segd(x2, y2, x0, y0, x, y)) <= DECK_MARGIN:
            if t[9] == 2 and not cap_near: return True                  # a capital's street box: leveled whatever its height
            # a buried floor (a mine's under its hill) is no street: the terrain over it is checked (the harness's LEVEL_BURIED_YD)
            a = max(0.0, min(1.0, a)); b = max(0.0, min(1.0 - a, b)); h = a * z0 + b * z1 + (1 - a - b) * z2
            if g is None: g = HT.height(x, y)
            if h >= g - LEVEL_BURIED and (t[9] != 2 or h <= g + LEVEL_BURIED): return True    # (cap_near: a 'capital' floor only near the ground)
    return False
def layer_at(x, y):
    """the floor the walker is on at (x, y) by the harness's layers (Climb.floorLayers: one height a vertex): straight between the
    cell's four vertices when all four have one; the nearest vertex's at a floor's edge; None off every reached floor"""
    gx = (ORG - x) / S; gy = (ORG - y) / S; r0 = int(math.floor(gx)); c0 = int(math.floor(gy)); fr = gx - r0; fc = gy - c0
    v = [_layers.get((r0, c0)), _layers.get((r0, c0 + 1)), _layers.get((r0 + 1, c0)), _layers.get((r0 + 1, c0 + 1))]
    if all(z is not None for z in v):
        return (v[0] * (1 - fc) + v[1] * fc) * (1 - fr) + (v[2] * (1 - fc) + v[3] * fc) * fr
    return None
def edge_cell(x, y):
    """a cell with some but not all of its corners on a reached floor (its edge): the walker there on either"""
    gx = (ORG - x) / S; gy = (ORG - y) / S; r0 = int(math.floor(gx)); c0 = int(math.floor(gy))
    return any(k in _layers for k in ((r0, c0), (r0, c0 + 1), (r0 + 1, c0), (r0 + 1, c0 + 1)))
def floors_at(x, y):
    """every floor's height at (x, y) (its triangles within the margin, on their planes; a capital's street box is no floor)"""
    out = []
    for k in _fgrid.get((int(math.floor(x / 16)), int(math.floor(y / 16))), []):
        t = _floors[k]
        if len(t) >= 10 and t[9] == 2: continue
        x0, y0, z0, x1, y1, z1, x2, y2, z2 = t[:9]
        det = (y1 - y2) * (x0 - x2) + (x2 - x1) * (y0 - y2)
        if abs(det) < 1e-9: continue
        a = ((y1 - y2) * (x - x2) + (x2 - x1) * (y - y2)) / det; b = ((y2 - y0) * (x - x2) + (x0 - x2) * (y - y2)) / det
        if not (a >= 0 and b >= 0 and a + b <= 1) and min(_segd(x0, y0, x1, y1, x, y), _segd(x1, y1, x2, y2, x, y), _segd(x2, y2, x0, y0, x, y)) > DECK_MARGIN: continue
        a = max(0.0, min(1.0, a)); b = max(0.0, min(1.0 - a, b))
        out.append(a * z0 + b * z1 + (1 - a - b) * z2)
    return out
def floor_z(x, y, ground):
    """the floor a walker at (x, y) stands on: of the deck triangles there (within the margin), the lowest at or over the ground (a
    bridge's deck under its roof), else the highest under it (a tunnel's floor under its hill: the walker is in the tunnel only if it
    came in by a mouth); None off every floor"""
    over = None; under = None
    for k in _fgrid.get((int(math.floor(x / 16)), int(math.floor(y / 16))), []):
        t = _floors[k]
        x0, y0, z0, x1, y1, z1, x2, y2, z2 = t[:9]
        det = (y1 - y2) * (x0 - x2) + (x2 - x1) * (y0 - y2)
        if abs(det) < 1e-9: continue
        a = ((y1 - y2) * (x - x2) + (x2 - x1) * (y - y2)) / det; b = ((y2 - y0) * (x - x2) + (x0 - x2) * (y - y2)) / det
        inside = a >= 0 and b >= 0 and a + b <= 1
        if not inside and min(_segd(x0, y0, x1, y1, x, y), _segd(x1, y1, x2, y2, x, y), _segd(x2, y2, x0, y0, x, y)) > DECK_MARGIN: continue
        a = max(0.0, min(1.0, a)); b = max(0.0, min(1.0 - a, b))
        h = a * z0 + b * z1 + (1 - a - b) * z2
        if h >= ground - BURIED: over = h if over is None else min(over, h)
        else: under = h if under is None else max(under, h)
    return over if over is not None else under
def cell_level(x, y):
    """as Climb.build decides a cell's faces: the cell holding (x, y) is level when its middle is on a leveled floor, or (the harness's
    onFloor) the vertex its middle rounds to has a covered hole among its four cells"""
    gr = int(math.floor((ORG - x) / S)); gc = int(math.floor((ORG - y) / S))
    mx = ORG - (gr + 0.5) * S; my = ORG - (gc + 0.5) * S
    vr = kround((ORG - mx) / S); vc = kround((ORG - my) / S)
    if any((vr + a, vc + b) in _holes for a in (-1, 0) for b in (-1, 0)): return True
    return level_floor(mx, my)
def vertex_level(gc, gr):
    """as Climb.build levels a vertex's steps: a covered hole among its four cells, or a leveled floor over it"""
    if any((gr + a, gc + b) in _holes for a in (-1, 0) for b in (-1, 0)): return True
    return level_floor(ORG - gr * S, ORG - gc * S)
def hole_at(x, y):
    """a hole a building's floor covers at (x, y), or one beside its cell: the harness levels the vertices beside such a hole (the
    floor is the ground there: a cave's mouth, a mine's entrance)"""
    r0 = int(math.floor((ORG - x) / S)); c0 = int(math.floor((ORG - y) / S))
    return any((r0 + dr, c0 + dc) in _holes for dr in (-1, 0, 1) for dc in (-1, 0, 1))
_calib = None
def kround(x): return int(math.floor(x + 0.5))
def vertex(wx, wy): return kround(((ORG - wy) / S)), kround(((ORG - wx) / S))
def lattice_worst(a, b, on=None):
    s = vertex(*a); e = vertex(*b)
    n = max(abs(e[0] - s[0]), abs(e[1] - s[1])); worst = 0.0; walls = 0
    ci, cj = s
    for q in range(1, n + 1):
        ni = s[0] + kround(((e[0] - s[0]) * q / n)); nj = s[1] + kround(((e[1] - s[1]) * q / n))
        di = (ni > ci) - (ni < ci); dj = (nj > cj) - (nj < cj)
        if di == 0 and dj == 0: continue
        d = step_deg(ci, cj, di, dj)
        if d >= WALL and step_ok(ci, cj, di, dj): ci, cj = ni, nj; continue      # a lone short steep step
        if d >= WALL:
            # a floor at the step (its own slope is the line's check, line_faces), or a hole a building's floor covers: no terrain
            mx = ORG - (cj + nj) / 2 * S; my = ORG - (ci + ni) / 2 * S
            if vertex_level(ci, cj) or vertex_level(ni, nj) or level_floor(mx, my) or (on and any(abs(px - mx) < 3.0 and abs(py - my) < 3.0 for px, py in on)): ci, cj = ni, nj; continue
            walls += 1
        worst = max(worst, d)
        ci, cj = ni, nj
    return worst, walls
def tri_height(wx, wy):
    """the terrain's height on its own triangles (each cell's four, round its middle vertex, as face_at picks them): the surface the
    faces are measured on. HT.height is bilinear over the outer vertices only, and a line along a cliff's foot read a 7 yd hump there
    (Mulgore A34: bilinear 60.7 -> 67.7 -> 61.4 where the triangles under the line run 60.7-61.3), a false step on the terrain"""
    gx = (ORG - wy) / S; gy = (ORG - wx) / S
    gc, gr = int(math.floor(gx)), int(math.floor(gy)); fx, fy = gx - gc, gy - gr
    a = FC.outer(gr, gc); b = FC.outer(gr, gc + 1); c = FC.outer(gr + 1, gc); d = FC.outer(gr + 1, gc + 1); e = FC.centre(gr, gc)
    if any(math.isnan(v) for v in (a, b, c, d, e)): return HT.height(wx, wy)
    def bary(p0, p1, h0, h1):
        (x0, y0), (x1, y1) = p0, p1; det = (y1 - 0.5) * (x0 - 0.5) + (0.5 - x1) * (y0 - 0.5)
        l0 = ((y1 - 0.5) * (fx - 0.5) + (0.5 - x1) * (fy - 0.5)) / det; l1 = ((0.5 - y0) * (fx - 0.5) + (x0 - 0.5) * (fy - 0.5)) / det
        return l0 * h0 + l1 * h1 + (1 - l0 - l1) * e
    if fy <= fx and fy <= 1 - fx: return bary((0, 0), (1, 0), a, b)
    if fx >= fy and fx >= 1 - fy: return bary((1, 0), (1, 1), b, d)
    if fy >= fx and fy >= 1 - fx: return bary((1, 1), (0, 1), d, c)
    return bary((0, 1), (0, 0), c, a)
def near_face(x, y):
    """a face within the quarter yard lineClear looks round a sample: its slope, or none (0) when its own cell is level ground to the
    router (Climb.build classes a leveled cell's four faces level: a covered hole's, a street box's, a porch's). The sample's own
    cell was asked, its neighbours' were not: a line along a leveled cell's edge read the steep terrain beside it (Darkshore B43,
    Durotar's and Thousand Needles' porches; the router classes such faces 00). A 'capital' floor (WorldBuildings.isCity: any
    WMO wider than CITY_YD) counts here only within LEVEL_BURIED of the terrain: by size alone Talondeep Path's tunnel (its floor 40-60
    yd under the hill), Hyjal's floors 400-600 yd over the ground, Desolace's and the Barrens' caves are 'capitals', and the router
    levels the hillside by them - real crossings, left flagged (the sample's own cell keeps the router's rule, as before)"""
    gr = int(math.floor((ORG - x) / S)); gc = int(math.floor((ORG - y) / S))
    mx = ORG - (gr + 0.5) * S; my = ORG - (gc + 0.5) * S
    vr = kround((ORG - mx) / S); vc = kround((ORG - my) / S)
    # (a capital's floor (flag 2) is now a true capital's, in a capital's own map: RenderSafePath.capital - leveled as the router does)
    if any((vr + a, vc + b) in _holes for a in (-1, 0) for b in (-1, 0)) or level_floor(mx, my): return 0.0
    return FC.face_at(x, y)
def line_walk(a, b, z0=None):
    """THE WALK along the straight line, each half yard: on a floor the harness's layer puts the walker on (layer_at), the floor's
    own rise over each 2 yd; elsewhere the terrain's gentlest face within a quarter yard (as Climb's lineClear), but where a building's
    floor covers a hole or a city's street levels it; and a step between the two - on or off a floor - more than EDGE_UP is a wall
    (off a deck's side, onto it from the hill over it, a floor no walker reaches left for the terrain under it). Returns its steepest
    (degrees), its wall samples, the spots on a floor (for the lattice check), and the heights the walker may be at the end (z0: at
    the start, a height or such a list).
    THE SURFACES (2026-10-19): the walker keeps every surface it can be on - each floor under it (floors_at) where the layer says it is
    on a floor, those and the terrain at a floor's edge cell, the terrain elsewhere - that is within EDGE_UP of one it was on; none so
    is a wall. The layer's own height, straight between a cell's four vertices, only says that a floor is reached: across a cell whose
    corner holds another floor's layer (Mulgore's Thunder Bluff lift: one corner on a ramp 15 yd up) it rose where every floor under
    the line is flat; and keeping only the nearest surface at an edge cell stayed on the terrain beside a ramp's foot (the Crossroads'
    ramp, 1.2 yd up at its edge) and then met the layer 2.1 yd up."""
    L = math.hypot(b[0] - a[0], b[1] - a[1]); n = max(1, int(L / 0.5)); step = L / n
    worst = 0.0; walls = 0; on = []; hist = []
    zs = None if z0 is None else (list(z0) if isinstance(z0, (list, tuple)) else [z0])
    win = max(1, int(round(2.0 / max(step, 1e-6)))); steep_run = 0.0
    for q in range(n + 1):
        x = a[0] + (b[0] - a[0]) * q / n; y = a[1] + (b[1] - a[1]) * q / n
        fl = layer_at(x, y); fs = floors_at(x, y) if (fl is not None or edge_cell(x, y)) else []
        if fl is not None and not fs: fl = None                         # the layer's vertices reach past the floor's own triangles
        g = tri_height(x, y)
        # the layer's own height too: a stair's treads are floors, its risers are not (Zephras' ramps: the layer climbs tread to tread)
        cand = [(h, True) for h in fs] + ([(g, False)] if fl is None else [(fl, True)])
        live = cand if zs is None else [c for c in cand if any(abs(c[0] - z) <= EDGE_UP for z in zs)]
        if not live:
            walls += 1; worst = max(worst, 90.0); live = cand                # no surface within a step of any it was on: a wall
        fl_live = [h for h, isf in live if isf]
        if fl_live and (fl is not None or not any(not isf for _, isf in live)):
            # on a floor: its own rise over each 2 yd, the gentlest between the floors it may be on now and those 2 yd back (decks
            # stacked 2 yd apart, a pier's planks over its beams' floor: the nearest-to-the-layer pick hopped between them)
            on.append((x, y)); hist.append((q, fl_live))
            back = [(qq, hz) for (qq, hz) in hist if q - qq >= win]
            if back:
                qq, hb = back[-1]; d = math.degrees(math.atan(min(abs(c - h0) for c in fl_live for h0 in hb) / ((q - qq) * step)))
                worst = max(worst, d)
                if d >= WALL: walls += 1
        else:
            if not fl_live: hist = []
            if not (hole_at(x, y) or cell_level(x, y)):
                if fl_live: steep_run = 0.0                                # at a floor's edge, the floor beside the terrain carries it
                else:
                    fc = min(near_face(x + dx, y + dy) for dx, dy in ((0, 0), (0.25, 0), (-0.25, 0), (0, 0.25), (0, -0.25)))
                    if WALL <= fc < SHORT_DEG:
                        steep_run += step
                        if steep_run > SHORT_YD: worst = max(worst, fc); walls += 1
                    else:
                        steep_run = 0.0
                        worst = max(worst, fc)
                        if fc >= WALL: walls += 1
            else: zs = None; continue                                  # a covered hole, a city's street: no height to keep
        zs = sorted({round(h, 2) for h, _ in live})
    return worst, walls, on, zs
def line_faces(a, b):
    w, n, _, _ = line_walk(a, b); return w, n
def surface_worst(a, b):
    L = math.hypot(b[0] - a[0], b[1] - a[1]); n = max(2, int(L / 2)); worst = 0.0; prev = a
    for s in range(1, n + 1):
        t = s / n; p = (a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t)
        worst = max(worst, math.degrees(math.atan(abs(HT.height(*p) - HT.height(*prev)) / max(1e-6, L / n)))); prev = p
    return worst
if __name__ == '__main__':
    f = sys.argv[1] if len(sys.argv) > 1 else os.path.join(os.environ.get('SAFEPATH_WORK', 'E:/safepath-work'), 'out', 'levels_routes_durotar.json')
    d = json.load(open(f, encoding='utf-8'))
    nf, nh = load_floors(f)
    bad = []; total = 0
    for rt in d['routes']:
        pts = rt['pts']; z = None
        for k in range(len(pts) - 1):
            a, b = pts[k], pts[k + 1]; total += 1
            if (len(a) > 2 and a[2] in (1, 2, 3)) or (len(b) > 2 and b[2] in (1, 2, 3)): z = None; continue      # a cave: its own floor, not the hill over it
            fw, fn, on, z = line_walk(a, b, z); w, n = lattice_worst(a, b, on); sw = surface_worst(a, b)
            if n: bad.append('%s%d-%s%d (%.0f deg, %d wall steps)' % (rt['letter'], k, rt['letter'], k + 1, w, n))
            if fn: bad.append('%s%d-%s%d (faces: %.0f deg over %.1f yd)' % (rt['letter'], k, rt['letter'], k + 1, fw, fn * 0.5))
            if '-v' in sys.argv: print('%s%d->%s%d  %5.0f yd  vertex steps steepest %4.1f  surface %4.1f  %s' % (rt['letter'], k, rt['letter'], k + 1, math.hypot(b[0] - a[0], b[1] - a[1]), w, sw, 'WALL' if n else 'ok'))
    print('%d segments; crossing a %d+ degree step: %s' % (total, WALL, ', '.join(bad) or 'none'))
