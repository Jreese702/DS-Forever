# a set of placed WMOs' walkable floor as pieces (welded 0.8 yd, steps joined 0.8 across / 1.5 up), the largest listed with their
# heights and extents - for laying a chain of manual links on a place built in the air
#   python floorpieces.py <fid,fid,...> <near x> <near y> [radius]
import sys, os, math, struct, glob, collections
import heights as HT
fids = set(int(v) for v in sys.argv[1].split(',')); nx, ny = float(sys.argv[2]), float(sys.argv[3]); rad = float(sys.argv[4]) if len(sys.argv) > 4 else 600
mods = []
for p in glob.glob(os.path.join(HT.DIR, 'obj0', '*.adt')):
    b = open(p, 'rb').read()
    for tag, o, n in HT.chunks(b):
        if tag != 'MODF': continue
        for i in range(n // 64):
            a = o + 8 + i * 64; fid, uid = struct.unpack_from('<II', b, a); px, py, pz, rx, ry, rz = struct.unpack_from('<6f', b, a + 8)
            sc = struct.unpack_from('<H', b, a + 62)[0] / 1024 or 1
            x, y, z = HT.ORG - pz, HT.ORG - px, py
            if fid in fids and math.hypot(x - nx, y - ny) < rad: mods.append((uid, fid, x, y, z, math.radians(ry), sc))
mods = list({m[0]: m for m in mods}.values())
V = []; idx = {}; adj = collections.defaultdict(set)
def vid(x, y, z):
    k = (round(x / 0.8), round(y / 0.8), round(z / 0.8))
    if k not in idx: idx[k] = len(V); V.append((x, y, z))
    return idx[k]
for uid, fid, ox, oy, oz, yaw, sc in mods:
    cs, sn = math.cos(yaw), math.sin(yaw); k = 0
    while os.path.exists(os.path.join(HT.DIR, 'wmogroup', '%d_%d' % (fid, k))):
        b = open(os.path.join(HT.DIR, 'wmogroup', '%d_%d' % (fid, k)), 'rb').read(); k += 1
        for tag, o, n in HT.chunks(b):
            if tag != 'MOGP': continue
            d = {}; q = o + 8 + 0x44
            while q + 8 <= o + 8 + n:
                t2 = b[q:q+4][::-1].decode('latin1'); n2 = struct.unpack_from('<i', b, q + 4)[0]; d[t2] = (q + 8, n2); q += 8 + n2
            if 'MOVT' not in d or 'MOVI' not in d: continue
            vs = [struct.unpack_from('<3f', b, d['MOVT'][0] + 12 * i) for i in range(d['MOVT'][1] // 12)]
            ix = struct.unpack_from('<%dH' % (d['MOVI'][1] // 2), b, d['MOVI'][0])
            fl = (lambda t: b[d['MOPY'][0] + 2 * t]) if 'MOPY' in d else (lambda t: b[d['MPY2'][0] + 4 * t]) if 'MPY2' in d else None
            if fl is None: continue
            for t in range(len(ix) // 3):
                f = fl(t)
                if not (f & 0x08 or (f & 0x20 and not f & 0x04)): continue
                pa, pb, pc = (vs[ix[3 * t + j]] for j in range(3))
                ux, uy, uz = (pb[i] - pa[i] for i in range(3)); vx, vy, vz = (pc[i] - pa[i] for i in range(3))
                nx_, ny_, nz_ = uy * vz - uz * vy, uz * vx - ux * vz, ux * vy - uy * vx; L = math.sqrt(nx_ ** 2 + ny_ ** 2 + nz_ ** 2) or 1
                if abs(nz_) / L < math.cos(math.radians(45)): continue
                w = [vid(ox - lx * sc * cs + ly * sc * sn, oy - lx * sc * sn - ly * sc * cs, oz + lz * sc) for lx, ly, lz in (pa, pb, pc)]
                for u, v in ((w[0], w[1]), (w[1], w[2]), (w[2], w[0])):
                    if u != v: adj[u].add(v); adj[v].add(u)
grid = collections.defaultdict(list)
for i, (x, y, z) in enumerate(V): grid[(math.floor(x), math.floor(y))].append(i)
for i, (x, y, z) in enumerate(V):
    for dx in (-1, 0, 1):
        for dy in (-1, 0, 1):
            for j in grid[(math.floor(x) + dx, math.floor(y) + dy)]:
                if j > i and math.hypot(V[j][0] - x, V[j][1] - y) <= 0.8 and abs(V[j][2] - z) <= 1.5: adj[i].add(j); adj[j].add(i)
comp = [-1] * len(V); pcs = []
for s in range(len(V)):
    if comp[s] >= 0: continue
    st = [s]; comp[s] = len(pcs); mem = []
    while st:
        u = st.pop(); mem.append(u)
        for w in adj[u]:
            if comp[w] < 0: comp[w] = len(pcs); st.append(w)
    pcs.append(mem)
print('%d placements, %d vertices, %d pieces' % (len(mods), len(V), len(pcs)))
for m in mods: print('  placement fid %d at %.0f %.0f z %.0f' % (m[1], m[2], m[3], m[4]))
for k, mem in sorted(enumerate(pcs), key=lambda kv: -len(kv[1]))[:14]:
    xs = [V[v][0] for v in mem]; ys = [V[v][1] for v in mem]; zs = [V[v][2] for v in mem]
    print('  piece %3d: %5d verts  z %6.0f..%6.0f  x %5.0f..%5.0f  y %5.0f..%5.0f' % (k, len(mem), min(zs), max(zs), min(xs), max(xs), min(ys), max(ys)))
for name, (px, py) in (('Peacekeeper 256635', (3061, 314)), ('Lorthuna 252957', (2970, 77)), ('Ayessa 253849', (3004, 112))):
    cand = sorted(range(len(V)), key=lambda v: math.hypot(V[v][0] - px, V[v][1] - py))[:40]
    print('  %-20s nearest floor: %s' % (name, sorted(set((comp[v], round(V[v][2])) for v in cand if math.hypot(V[v][0] - px, V[v][1] - py) < 6))[:10]))
