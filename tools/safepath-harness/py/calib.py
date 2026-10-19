# calibrating the wall: along each route, its runs of terrain faces steeper than a limit (buildings' footprints left out: their
# floors are the ground there) and how high each run climbs - a short steep step a player steps up, a long one is a wall
import os, sys, json, math, struct, glob
import heights as HT
import faces as FC
try: sys.stdout.reconfigure(encoding='utf-8')     # the routes' names carry arrows (Windows' console is cp1252)
except Exception: pass
S = HT.S; ORG = HT.ORG
def surface_z(wx, wy):
    gx = (ORG - wy) / S; gy = (ORG - wx) / S
    gc, gr = int(math.floor(gx)), int(math.floor(gy)); fx, fy = gx - gc, gy - gr
    a = (0, 0, FC.outer(gr, gc)); b = (1, 0, FC.outer(gr, gc + 1)); c = (0, 1, FC.outer(gr + 1, gc)); d = (1, 1, FC.outer(gr + 1, gc + 1))
    e = (0.5, 0.5, FC.centre(gr, gc))
    if fy <= fx and fy <= 1 - fx: t = (a, b, e)
    elif fx >= fy and fx >= 1 - fy: t = (b, d, e)
    elif fy >= fx and fy >= 1 - fx: t = (d, c, e)
    else: t = (c, a, e)
    (x1, y1, z1), (x2, y2, z2), (x3, y3, z3) = t
    den = (y2 - y3) * (x1 - x3) + (x3 - x2) * (y1 - y3)
    l1 = ((y2 - y3) * (fx - x3) + (x3 - x2) * (fy - y3)) / den; l2 = ((y3 - y1) * (fx - x3) + (x1 - x3) * (fy - y3)) / den
    return l1 * z1 + l2 * z2 + (1 - l1 - l2) * z3
# the buildings' footprints (every placed WMO's groups' boxes, as the harness's covered())
boxes = []
for u, (fid, x, y, z) in HT.placements().items():
    p = os.path.join(HT.DIR, 'wmo', str(fid))
    if not os.path.exists(p): continue
    b = open(p, 'rb').read()
    for tag, o, n in HT.chunks(b):
        if tag != 'MOGI': continue
        for g in range(n // 32):
            bx = struct.unpack_from('<6f', b, o + 8 + g * 32 + 4)
            boxes.append((x, y, min(bx[0], bx[3]), max(bx[0], bx[3]), min(bx[1], bx[4]), max(bx[1], bx[4]), fid, u))
yaws = {}
for p in glob.glob(os.path.join(HT.DIR, 'obj0', '*.adt')):
    b = open(p, 'rb').read()
    for tag, o, n in HT.chunks(b):
        if tag != 'MODF': continue
        for i in range(n // 64):
            a = o + 8 + i * 64; uid = struct.unpack_from('<I', b, a + 4)[0]; ry = struct.unpack_from('<f', b, a + 8 + 16)[0]
            sc = struct.unpack_from('<H', b, a + 62)[0] / 1024.0
            yaws[uid] = (math.radians(ry), sc or 1.0)
grid = {}
for bx in boxes:
    ox, oy, lx0, lx1, ly0, ly1, fid, u = bx
    yaw, sc = yaws.get(u, (0.0, 1.0)); r = max(abs(lx0), abs(lx1), abs(ly0), abs(ly1)) * sc
    for cx in range(int((ox - r) // 64), int((ox + r) // 64) + 1):
        for cy in range(int((oy - r) // 64), int((oy + r) // 64) + 1): grid.setdefault((cx, cy), []).append((bx, yaw, sc))
def covered(wx, wy):
    for (ox, oy, lx0, lx1, ly0, ly1, fid, u), yaw, sc in grid.get((int(wx // 64), int(wy // 64)), []):
        cs, sn = math.cos(yaw), math.sin(yaw); dx, dy = wx - ox, wy - oy
        # inverse of worldX = ox - lx*cs + ly*sn, worldY = oy - lx*sn - ly*cs
        lx = -(dx * cs + dy * sn); ly = dx * sn - dy * cs
        if lx0 * sc <= lx <= lx1 * sc and ly0 * sc <= ly <= ly1 * sc: return True
    return False
LIMITS = [30, 35, 40, 45, 50]
def runs(rt, step=0.5):
    """per limit: the highest climb (yd) of any run of faces at least that steep along the route (cave floors and buildings left out)"""
    pts = rt['pts']; best = {l: (0.0, '') for l in LIMITS}
    for k in range(len(pts) - 1):
        a, b = pts[k], pts[k + 1]
        if (len(a) > 2 and a[2] in (1, 2)) or (len(b) > 2 and b[2] in (1, 2)): continue
        L = math.hypot(b[0] - a[0], b[1] - a[1]); n = max(1, int(L / step))
        samp = []
        for s in range(n + 1):
            x = a[0] + (b[0] - a[0]) * s / n; y = a[1] + (b[1] - a[1]) * s / n
            # the gentlest face within a quarter yard (as Climb's lineClear: a line along an edge walks its gentler side)
            f = min(FC.face_at(x + dx, y + dy) for dx, dy in ((0, 0), (0.25, 0), (-0.25, 0), (0, 0.25), (0, -0.25)))
            samp.append((f, surface_z(x, y), covered(x, y)))
        for l in LIMITS:
            z0 = None; zl = zh = 0
            for sl, z, cov in samp:
                if sl >= l and not cov:
                    if z0 is None: zl = zh = z; z0 = z
                    zl = min(zl, z); zh = max(zh, z)
                else:
                    if z0 is not None and zh - zl > best[l][0]: best[l] = (zh - zl, '%s%d-%d' % (rt['letter'], k, k + 1))
                    z0 = None
            if z0 is not None and zh - zl > best[l][0]: best[l] = (zh - zl, '%s%d-%d' % (rt['letter'], k, k + 1))
    return best
if __name__ == '__main__':
    d = json.load(open(sys.argv[1], encoding='utf-8')); only = sys.argv[2] if len(sys.argv) > 2 else None
    print('route: the highest climb (yd) of a run of faces steeper than %s deg (where)' % '/'.join(map(str, LIMITS)))
    for rt in d['routes']:
        if only and rt['letter'] not in only: continue
        bst = runs(rt)
        print('  %s %-48s %s' % (rt['letter'], rt['name'][:48], '  '.join('%2d:%5.1f %-8s' % (l, bst[l][0], bst[l][1]) for l in LIMITS)))
