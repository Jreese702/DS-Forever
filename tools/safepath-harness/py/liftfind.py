# A WMO's floor (its walkable collision faces, as Caves.floorTris reads them) in world yards, looked at in columns: where a floor
# piece at the foot stands under another floor piece far above it, with nothing walkable between (a lift shaft: the car is a
# server object, but its two landings are the WMO's own fixed floor). For finding lift landings from the client's data.
#   python liftfind.py <wmo fid> [near_x,near_y] [--col 6] [--gap 40]
# prints each column (a --col yd cell) whose floor heights jump by --gap yd or more with no floor between: its low and high floors.
import sys, os, struct, math, glob, collections
import heights as HT
args = sys.argv[1:]
fid = int(args[0])
near = tuple(map(float, args[1].split(','))) if len(args) > 1 and ',' in args[1] else None
COL = float(args[args.index('--col') + 1]) if '--col' in args else 6.0
GAP = float(args[args.index('--gap') + 1]) if '--gap' in args else 40.0
pl = []
for p in glob.glob(os.path.join(HT.DIR, 'obj0', '*.adt')):
    b = open(p, 'rb').read()
    for tag, o, n in HT.chunks(b):
        if tag != 'MODF': continue
        for i in range(n // 64):
            a = o + 8 + i * 64
            if struct.unpack_from('<I', b, a)[0] != fid: continue
            uid = struct.unpack_from('<I', b, a + 4)[0]
            px, py, pz, rx, ry, rz = struct.unpack_from('<6f', b, a + 8); sc = struct.unpack_from('<H', b, a + 62)[0] or 1024
            pl.append((uid, HT.ORG - pz, HT.ORG - px, py, math.radians(ry), sc / 1024.0))
pl = list({p[0]: p for p in pl}.values())
if near: pl.sort(key=lambda p: math.hypot(p[1] - near[0], p[2] - near[1]))
uid, ox, oy, oz, yaw, sc = pl[0]
print('placement uid %d at %.1f,%.1f z %.1f yaw %.1f scale %.2f' % (uid, ox, oy, oz, math.degrees(yaw), sc))
cs, sn = math.cos(yaw), math.sin(yaw)
pts = []
k = 0
while os.path.exists(os.path.join(HT.DIR, 'wmogroup', '%d_%d' % (fid, k))):
    b = open(os.path.join(HT.DIR, 'wmogroup', '%d_%d' % (fid, k)), 'rb').read()
    for tag, o, n in HT.chunks(b):
        if tag != 'MOGP': continue
        d = {}
        for t2, q, n2 in HT.chunks(b, o + 8 + 0x44, o + 8 + n): d[t2] = (q + 8, n2)
        if 'MOVT' not in d or 'MOVI' not in d: continue
        vs = [struct.unpack_from('<3f', b, d['MOVT'][0] + 12 * i) for i in range(d['MOVT'][1] // 12)]
        ix = struct.unpack_from('<%dH' % (d['MOVI'][1] // 2), b, d['MOVI'][0])
        for t in range(len(ix) // 3):
            fl = b[d['MOPY'][0] + 2 * t] if 'MOPY' in d else (struct.unpack_from('<H', b, d['MPY2'][0] + 4 * t)[0] if 'MPY2' in d else 0)
            if not (fl & 0x08 or (fl & 0x20 and not fl & 0x04)): continue
            A, B_, C = (vs[ix[3 * t + j]] for j in range(3))
            ux, uy, uz = B_[0] - A[0], B_[1] - A[1], B_[2] - A[2]; vx, vy, vz = C[0] - A[0], C[1] - A[1], C[2] - A[2]
            nx, ny, nz = uy * vz - uz * vy, uz * vx - ux * vz, ux * vy - uy * vx; L = math.sqrt(nx * nx + ny * ny + nz * nz) or 1
            if abs(nz) / L < math.cos(math.radians(45)): continue
            lx = (A[0] + B_[0] + C[0]) / 3; ly = (A[1] + B_[1] + C[1]) / 3; lz = (A[2] + B_[2] + C[2]) / 3
            pts.append((ox - lx * sc * cs + ly * sc * sn, oy - lx * sc * sn - ly * sc * cs, oz + lz * sc, k))
    k += 1
print('%d groups, %d floor faces' % (k, len(pts)))
cols = collections.defaultdict(list)
for x, y, z, g in pts: cols[(math.floor(x / COL), math.floor(y / COL))].append((z, g))
hits = []
for key, zs in cols.items():
    zs.sort()
    for (z0, g0), (z1, g1) in zip(zs, zs[1:]):
        if z1 - z0 >= GAP:
            x = (key[0] + 0.5) * COL; y = (key[1] + 0.5) * COL
            hits.append((x, y, z0, z1, g0, g1, HT.height(x, y)))
for h in sorted(hits): print('  column %.0f,%.0f: floor %.1f (group %d) then %.1f (group %d) - rise %.1f; terrain %.1f' % (h[0], h[1], h[2], h[4], h[3], h[5], h[3] - h[2], h[6]))
