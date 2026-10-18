import sys, struct, os, glob, math
import heights as HT
fid = int(sys.argv[1]); ng = int(sys.argv[2]); D = HT.DIR
# the placement with its full MODF rotation
for p in glob.glob(os.path.join(D, 'obj0', '*.adt')):
    b = open(p, 'rb').read(); o = 0
    while o + 8 <= len(b):
        tag = b[o:o+4][::-1].decode('latin1'); n = struct.unpack_from('<i', b, o + 4)[0]
        if tag == 'MODF':
            for i in range(n // 64):
                a = o + 8 + i * 64
                if struct.unpack_from('<I', b, a)[0] == fid:
                    px, py, pz, rx, ry, rz = struct.unpack_from('<6f', b, a + 8); sc = struct.unpack_from('<H', b, a + 62)[0]
                    print('MODF', os.path.basename(p), 'pos', px, py, pz, 'rot', rx, ry, rz, 'scale', sc)
        o += 8 + n
def tris(g):
    b = open(g, 'rb').read(); o = 0
    while o + 8 <= len(b):
        tag = b[o:o+4][::-1].decode('latin1'); n = struct.unpack_from('<i', b, o + 4)[0]
        if tag == 'MOGP':
            q = o + 8 + 0x44; e = o + 8 + n; d = {}
            while q + 8 <= e:
                t2 = b[q:q+4][::-1].decode('latin1'); n2 = struct.unpack_from('<i', b, q + 4)[0]; d[t2] = (q + 8, n2); q += 8 + n2
            vt = d['MOVT']; vs = [struct.unpack_from('<3f', b, vt[0] + 12 * k) for k in range(vt[1] // 12)]
            return vs, struct.unpack_from('<I', b, o + 8 + 8)[0]
        o += 8 + n
    return [], 0
for k in range(ng):
    vs, fl = tris(os.path.join(D, 'wmogroup', '%d_%d' % (fid, k)))
    if not vs: continue
    zs = [v[2] for v in vs]; xs = [v[0] for v in vs]; ys = [v[1] for v in vs]
    print('group %2d flags %08x verts %5d  x %7.1f..%7.1f y %7.1f..%7.1f z %6.1f..%6.1f' % (k, fl, len(vs), min(xs), max(xs), min(ys), max(ys), min(zs), max(zs)))
