# the game's own terrain surface: each quad of outer vertices split into 4 triangles through its centre (inner) vertex; a point's face
# and that face's slope (from its normal). Measures the routes' steepest faces walked over.
import os, sys, json, math, struct
import numpy as np
import heights as HT
try: sys.stdout.reconfigure(encoding='utf-8')     # the routes' names carry arrows (Windows' console is cp1252)
except Exception: pass
S = HT.S; ORG = HT.ORG
_inner = {}
def inner_tile(c, r):
    """the inner vertices: 128 x 128, one in each cell's middle (row south, column east)"""
    if (c, r) in _inner: return _inner[(c, r)]
    h = np.full((128, 128), np.nan, dtype=np.float32)
    p = os.path.join(HT.DIR, 'adt', '%d_%d.adt' % (c, r))
    if os.path.exists(p):
        b = open(p, 'rb').read()
        for tag, o, n in HT.chunks(b):
            if tag != 'MCNK': continue
            d = o + 8
            ix, iy = struct.unpack_from('<ii', b, d + 4)
            z = struct.unpack_from('<f', b, d + 0x68 + 8)[0]
            for t2, p2, n2 in HT.chunks(b, d + 128, d + n):
                if t2 != 'MCVT': continue
                k = 0
                for row in range(17):
                    if row % 2 == 0: k += 9
                    else:
                        for vc in range(8):
                            h[iy * 8 + row // 2, ix * 8 + vc] = z + struct.unpack_from('<f', b, p2 + 8 + k * 4)[0]; k += 1
    _inner[(c, r)] = h
    return h
def outer(gr, gc):
    c, r = gc // 128, gr // 128
    return float(HT.tile(c, r)[gr - r * 128, gc - c * 128])
def centre(gr, gc):
    c, r = gc // 128, gr // 128
    return float(inner_tile(c, r)[gr - r * 128, gc - c * 128])
def tri_slope(p0, p1, p2):
    ux, uy, uz = (p1[i] - p0[i] for i in range(3)); vx, vy, vz = (p2[i] - p0[i] for i in range(3))
    nx, ny, nz = uy * vz - uz * vy, uz * vx - ux * vz, ux * vy - uy * vx
    L = math.sqrt(nx * nx + ny * ny + nz * nz)
    if L == 0 or math.isnan(L): return 90.0
    return math.degrees(math.acos(min(1.0, abs(nz) / L)))
def face_at(wx, wy):
    """the slope (degrees) of the terrain triangle under world (wx, wy)"""
    gx = (ORG - wy) / S; gy = (ORG - wx) / S            # column east, row south
    gc, gr = int(math.floor(gx)), int(math.floor(gy)); fx, fy = gx - gc, gy - gr
    a = (0, 0, outer(gr, gc)); b = (S, 0, outer(gr, gc + 1)); c = (0, S, outer(gr + 1, gc)); d = (S, S, outer(gr + 1, gc + 1))
    e = (S / 2, S / 2, centre(gr, gc))
    # which of the 4 triangles (split by the two diagonals)
    if fy <= fx and fy <= 1 - fx: t = (a, b, e)          # north
    elif fx >= fy and fx >= 1 - fy: t = (b, d, e)        # east
    elif fy >= fx and fy >= 1 - fx: t = (d, c, e)        # south
    else: t = (c, a, e)                                  # west
    return tri_slope(*t)
def seg_faces(a, b, step=0.5):
    L = math.hypot(b[0] - a[0], b[1] - a[1]); n = max(1, int(L / step))
    return [face_at(a[0] + (b[0] - a[0]) * k / n, a[1] + (b[1] - a[1]) * k / n) for k in range(n + 1)]
if __name__ == '__main__':
    d = json.load(open(sys.argv[1], encoding='utf-8'))
    only = sys.argv[2] if len(sys.argv) > 2 else None
    for rt in d['routes']:
        if only and rt['letter'] not in only: continue
        pts = rt['pts']; worst = 0; over = {35: 0.0, 40: 0.0, 45: 0.0, 50: 0.0}; segs = []
        for k in range(len(pts) - 1):
            a, b = pts[k], pts[k + 1]
            if (len(a) > 2 and a[2] in (1, 2)) or (len(b) > 2 and b[2] in (1, 2)): continue
            f = seg_faces(a, b); m = max(f); worst = max(worst, m)
            for lim in over: over[lim] += sum(0.5 for x in f if x >= lim)
            segs.append((m, k))
        top = sorted(segs, reverse=True)[:4]
        print('%s %-60s steepest face %4.1f deg; yd over 35/40/45/50: %s; worst segs %s' % (rt['letter'], rt['name'][:60], worst,
              '/'.join('%.0f' % over[l] for l in sorted(over)), ', '.join('%s%d-%d %.0f' % (rt['letter'], k, k + 1, m) for m, k in top)))
