import sys, struct, os, math
import heights as HT
fid, ng, ox, oy, oz, yaw = int(sys.argv[1]), int(sys.argv[2]), float(sys.argv[3]), float(sys.argv[4]), float(sys.argv[5]), math.radians(float(sys.argv[6]))
cs, sn = math.cos(yaw), math.sin(yaw)
best = []
stats = {'tris': 0, 'coll': 0, 'flat': 0}
for k in range(ng):
    b = open(os.path.join(HT.DIR, 'wmogroup', '%d_%d' % (fid, k)), 'rb').read(); o = 0
    while o + 8 <= len(b):
        tag = b[o:o+4][::-1].decode('latin1'); n = struct.unpack_from('<i', b, o + 4)[0]
        if tag == 'MOGP':
            q = o + 8 + 0x44; e = o + 8 + n; d = {}
            while q + 8 <= e:
                t2 = b[q:q+4][::-1].decode('latin1'); n2 = struct.unpack_from('<i', b, q + 4)[0]; d[t2] = (q + 8, n2); q += 8 + n2
            vs = [struct.unpack_from('<3f', b, d['MOVT'][0] + 12 * i) for i in range(d['MOVT'][1] // 12)]
            ix = struct.unpack_from('<%dH' % (d['MOVI'][1] // 2), b, d['MOVI'][0])
            for t in range(len(ix) // 3):
                stats['tris'] += 1
                fl = b[d['MOPY'][0] + 2 * t] if 'MOPY' in d else b[d['MPY2'][0] + 4 * t] if 'MPY2' in d else 0   # the newer groups: MPY2
                if not (fl & 0x08 or (fl & 0x20 and not fl & 0x04)): continue
                stats['coll'] += 1
                a, bb, c = (vs[ix[3 * t + j]] for j in range(3))
                ux, uy, uz = bb[0] - a[0], bb[1] - a[1], bb[2] - a[2]; vx, vy, vz = c[0] - a[0], c[1] - a[1], c[2] - a[2]
                nx, ny, nz = uy * vz - uz * vy, uz * vx - ux * vz, ux * vy - uy * vx; L = math.sqrt(nx * nx + ny * ny + nz * nz) or 1
                if abs(nz) / L < math.cos(math.radians(45)): continue
                stats['flat'] += 1
                for lx, ly, lz in (a, bb, c):
                    wx = ox - lx * cs + ly * sn; wy = oy - lx * sn - ly * cs; wz = oz + lz
                    best.append((abs(HT.height(wx, wy) - wz), wx, wy, wz, HT.height(wx, wy), k))
        o += 8 + n
best.sort(); print(stats)
for r in best[:8]: print('  |dz| %.2f at %.0f,%.0f floor %.1f ground %.1f group %d' % r)
