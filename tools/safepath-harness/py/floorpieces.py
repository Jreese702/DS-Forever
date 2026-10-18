# A WMO's floor pieces as Caves.cave welds them (vertices within 0.6 yd), and what a step-up allowance would join: vertices within
# --h yd across and --up yd up (a stair's riser, a ramp's lip) linked. For checking why a floor's mouths and landings stay apart.
#   python floorpieces.py <fid> near_x,near_y [--h 0.6] [--up 1.0] [--probe x,y,z ...]
# prints the pieces (size, z range, centre) and, for each probe point, the piece of its nearest floor vertex, before and after.
import sys, math, collections
args = sys.argv[1:]
H = float(args[args.index('--h') + 1]) if '--h' in args else 0.6
UP = float(args[args.index('--up') + 1]) if '--up' in args else 1.0
probes = [tuple(map(float, a.split(','))) for a in args[args.index('--probe') + 1:]] if '--probe' in args else []
sys.argv = ['liftfind.py', args[0], args[1]]
src = open(__file__.replace('floorpieces.py', 'liftfind.py')).read().split("print('%d groups")[0]
src = src.replace("lx = (A[0] + B_[0] + C[0]) / 3; ly = (A[1] + B_[1] + C[1]) / 3; lz = (A[2] + B_[2] + C[2]) / 3\n            pts.append((ox - lx * sc * cs + ly * sc * sn, oy - lx * sc * sn - ly * sc * cs, oz + lz * sc, k))",
                  "pts.append(tuple((ox - V[0] * sc * cs + V[1] * sc * sn, oy - V[0] * sc * sn - V[1] * sc * cs, oz + V[2] * sc) for V in (A, B_, C)))")
g = {}; exec(src, g); tris = g['pts']
vx = []; bucket = collections.defaultdict(list)
def vid(p):
    b = (math.floor(p[0]), math.floor(p[1]), math.floor(p[2]))
    for d0 in (-1, 0, 1):
        for d1 in (-1, 0, 1):
            for d2 in (-1, 0, 1):
                for v in bucket[(b[0] + d0, b[1] + d1, b[2] + d2)]:
                    q = vx[v]
                    if (q[0] - p[0]) ** 2 + (q[1] - p[1]) ** 2 + (q[2] - p[2]) ** 2 <= 0.36: return v
    vx.append(p); bucket[b].append(len(vx) - 1); return len(vx) - 1
adj = collections.defaultdict(set)
for t in tris:
    ids = [vid(p) for p in t]
    for a, b in ((0, 1), (1, 2), (2, 0)):
        if ids[a] != ids[b]: adj[ids[a]].add(ids[b]); adj[ids[b]].add(ids[a])
def comps(adj):
    comp = [-1] * len(vx); n = 0
    for s in range(len(vx)):
        if comp[s] >= 0: continue
        st = [s]; comp[s] = n
        while st:
            u = st.pop()
            for w in adj[u]:
                if comp[w] < 0: comp[w] = n; st.append(w)
        n += 1
    return comp, n
c0, n0 = comps(adj)
# the step-up links
adj2 = collections.defaultdict(set, {k: set(v) for k, v in adj.items()})
grid = collections.defaultdict(list)
for i, p in enumerate(vx): grid[(math.floor(p[0] / H), math.floor(p[1] / H))].append(i)
for i, p in enumerate(vx):
    gx, gy = math.floor(p[0] / H), math.floor(p[1] / H)
    for dx in (-1, 0, 1):
        for dy in (-1, 0, 1):
            for j in grid[(gx + dx, gy + dy)]:
                q = vx[j]
                if j != i and math.hypot(q[0] - p[0], q[1] - p[1]) <= H and abs(q[2] - p[2]) <= UP: adj2[i].add(j); adj2[j].add(i)
c1, n1 = comps(adj2)
print('%d floor vertices; pieces: %d welded, %d with a step-up of %.1f yd within %.1f yd' % (len(vx), n0, n1, UP, H))
def near(pr): return min(range(len(vx)), key=lambda i: math.hypot(vx[i][0] - pr[0], vx[i][1] - pr[1]) + 3 * abs(vx[i][2] - pr[2]))
for pr in probes:
    v = near(pr)
    print('  probe %s: vertex %.1f,%.1f z %.1f  piece %d (of %d verts) -> piece %d (of %d verts)' % (pr, vx[v][0], vx[v][1], vx[v][2], c0[v], c0.count(c0[v]), c1[v], c1.count(c1[v])))
if "--show" in args:
    import heights as HT
    for pr in probes:
        v = near(pr)
        for c, cc in (('step-up', c1),):
            mem = [i for i in range(len(vx)) if cc[i] == cc[v]]
            print('  piece of %s (%s): %d verts' % (pr, c, len(mem)))
            for i in mem[:40]: print('     %.1f,%.1f z %.1f terrain %.1f' % (vx[i][0], vx[i][1], vx[i][2], HT.height(vx[i][0], vx[i][1])))
if '--around' in sys.argv[0:] or '--around' in args:
    cx, cy, r = map(float, args[args.index('--around') + 1].split(','))
    import heights as HT
    byp = collections.defaultdict(list)
    for i, p in enumerate(vx):
        if math.hypot(p[0] - cx, p[1] - cy) <= r: byp[c1[i]].append(i)
    for pc, mem in sorted(byp.items(), key=lambda kv: min(vx[i][2] for i in kv[1])):
        zs = [vx[i][2] for i in mem]; meet = [i for i in mem if abs(HT.height(vx[i][0], vx[i][1]) - vx[i][2]) <= 1.5]
        allm = [i for i in range(len(vx)) if c1[i] == pc]
        meetall = [i for i in allm if abs(HT.height(vx[i][0], vx[i][1]) - vx[i][2]) <= 1.5]
        print('  piece %3d: %4d verts here (%d in all), z %.1f..%.1f, centre %.0f,%.0f; meets terrain at %d here, %d in all%s' % (pc, len(mem), len(allm), min(zs), max(zs),
              sum(vx[i][0] for i in mem) / len(mem), sum(vx[i][1] for i in mem) / len(mem), len(meet), len(meetall),
              (' e.g. %.0f,%.0f z %.0f' % vx[meetall[0]]) if meetall else ''))
if "--shaft" in args or "--overlap" in args:
    sx, sy, z0, z1 = map(float, args[args.index('--shaft' if '--shaft' in args else '--overlap') + 1].split(','))
    def inside(t, x, y):
        def side(a, b): return (b[0] - a[0]) * (y - a[1]) - (b[1] - a[1]) * (x - a[0])
        d = [side(t[0], t[1]), side(t[1], t[2]), side(t[2], t[0])]
        return not (any(v < 0 for v in d) and any(v > 0 for v in d))
    for z in (z0, z1):
        hit = [t for t in tris if abs(sum(p[2] for p in t) / 3 - z) <= 2.5 and inside(t, sx, sy)]
        print('  shaft at z %.1f: %d faces' % (z, len(hit)))
        for t in hit[:3]:
            v = vid(t[0]); print('    face z %.1f, its piece %d (%d verts)' % (sum(p[2] for p in t) / 3, c1[v], c1.count(c1[v])))
if '--overlap' in args:
    sx, sy, z0, z1 = map(float, args[args.index('--overlap') + 1].split(','))
    lo = [t for t in tris if abs(sum(p[2] for p in t) / 3 - z0) <= 1.5 and math.hypot(t[0][0] - sx, t[0][1] - sy) < 30]
    hi = [t for t in tris if abs(sum(p[2] for p in t) / 3 - z1) <= 1.5 and math.hypot(t[0][0] - sx, t[0][1] - sy) < 30]
    pts = []
    x = sx - 25
    while x <= sx + 25:
        y = sy - 25
        while y <= sy + 25:
            a = [t for t in lo if inside(t, x, y)]; b = [t for t in hi if inside(t, x, y)]
            if a and b: pts.append((x, y, c1[vid(a[0][0])], c1[vid(b[0][0])]))
            y += 0.5
        x += 0.5
    by = collections.Counter((p[2], p[3]) for p in pts)
    for (pa, pb), k in by.most_common(6):
        q = [p for p in pts if p[2] == pa and p[3] == pb]
        print('  low piece %d (%d verts) under high piece %d (%d verts): %.0f sq yd, centre %.1f,%.1f' % (pa, c1.count(pa), pb, c1.count(pb), k * 0.25,
              sum(p[0] for p in q) / len(q), sum(p[1] for p in q) / len(q)))
