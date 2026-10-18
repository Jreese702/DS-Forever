# Durotar's real heights from the root ADTs (MCNK position.z + MCVT), parsed as the app's Terrain.tile does, and checked against the
# buildings the game places on the ground (MODF: each placement's own height)
import struct, os, math, glob
import numpy as np
ADT = 1600.0 / 3.0; S = ADT / 128.0; ORG = 32 * ADT
WORK = os.environ.get('SAFEPATH_WORK', 'E:/safepath-work')
DIR = os.path.join(WORK, os.environ.get('ZDIR', 'kalimdor'))

def chunks(b, o=0, end=None):
    end = len(b) if end is None else end
    while o + 8 <= end:
        tag = b[o:o+4][::-1].decode('latin1'); n = struct.unpack_from('<i', b, o + 4)[0]
        if n < 0 or o + 8 + n > end: return
        yield tag, o, n
        o += 8 + n

_tiles = {}
def tile(c, r):
    """outer vertices: 128 x 128 (row south, column east) + the full 129th via the neighbour; NaN = none"""
    if (c, r) in _tiles: return _tiles[(c, r)]
    h = np.full((129, 129), np.nan, dtype=np.float32)
    p = os.path.join(DIR, 'adt', '%d_%d.adt' % (c, r))
    if os.path.exists(p):
        b = open(p, 'rb').read()
        for tag, o, n in chunks(b):
            if tag != 'MCNK': continue
            d = o + 8
            ix, iy = struct.unpack_from('<ii', b, d + 4)
            z = struct.unpack_from('<f', b, d + 0x68 + 8)[0]
            for t2, p2, n2 in chunks(b, d + 128, d + n):
                if t2 != 'MCVT': continue
                k = 0
                for row in range(17):
                    if row % 2 == 0:
                        for vc in range(9):
                            h[iy * 8 + row // 2, ix * 8 + vc] = z + struct.unpack_from('<f', b, p2 + 8 + k * 4)[0]; k += 1
                    else: k += 8
    _tiles[(c, r)] = h
    return h

_water = {}
def water_tile(c, r):
    """the MH2O surface over each of the tile's 128 x 128 cells (NaN = dry), as the app's Terrain.water"""
    if (c, r) in _water: return _water[(c, r)]
    w = np.full((128, 128), np.nan, dtype=np.float32)
    p = os.path.join(DIR, 'adt', '%d_%d.adt' % (c, r))
    if os.path.exists(p):
        b = open(p, 'rb').read()
        for tag, o, n in chunks(b):
            if tag != 'MH2O': continue
            base = o + 8
            for ch in range(256):
                io, lay, _ = struct.unpack_from('<III', b, base + ch * 12)
                if not lay or lay > 8: continue
                for l in range(lay):
                    ip = base + io + l * 24
                    top = struct.unpack_from('<f', b, ip + 8)[0]; xo, yo, ww, hh = b[ip + 12:ip + 16]; bm = struct.unpack_from('<I', b, ip + 16)[0]
                    if xo + ww > 8 or yo + hh > 8: continue
                    for y in range(hh):
                        for x in range(ww):
                            bit = y * ww + x
                            if bm and not (b[base + bm + bit // 8] >> (bit % 8)) & 1: continue
                            k = ((ch // 16) * 8 + yo + y, (ch % 16) * 8 + xo + x)
                            if np.isnan(w[k]) or top > w[k]: w[k] = top
    _water[(c, r)] = w
    return w

def height(wx, wy):
    """bilinear over the outer vertices at world (wx north, wy west)"""
    gx = (ORG - wy) / S; gy = (ORG - wx) / S
    c = int(gx // 128); r = int(gy // 128)
    h = tile(c, r)
    fx = gx - c * 128; fy = gy - r * 128
    i = int(fx); j = int(fy); i = min(i, 127); j = min(j, 127)
    tx = fx - i; ty = fy - j
    a = h[j, i]; b_ = h[j, i + 1]; cc = h[j + 1, i]; d = h[j + 1, i + 1]
    if np.isnan(b_): b_ = a
    if np.isnan(cc): cc = a
    if np.isnan(d): d = a
    return float((a * (1 - tx) + b_ * tx) * (1 - ty) + (cc * (1 - tx) + d * tx) * ty)

def placements():
    """every WMO placed on the obj0s: world x, y, z (MODF: ORIGIN - pz, ORIGIN - px, py), its file id"""
    out = []
    for p in glob.glob(os.path.join(DIR, 'obj0', '*.adt')):
        b = open(p, 'rb').read()
        for tag, o, n in chunks(b):
            if tag != 'MODF': continue
            for i in range(n // 64):
                a = o + 8 + i * 64
                fid, uid = struct.unpack_from('<II', b, a)
                px, py, pz = struct.unpack_from('<fff', b, a + 8)
                out.append((uid, fid, ORG - pz, ORG - px, py))
    return {u: (f, x, y, z) for u, f, x, y, z in out}

if __name__ == '__main__':
    rect = (1808.3333, -1716.6666, -1962.4999, -7249.9995)        # Durotar: top, bottom, left, right
    pl = [v for v in placements().values() if rect[1] <= v[1] <= rect[0] and rect[3] <= v[2] <= rect[2]]
    diffs = []
    for f, x, y, z in pl:
        g = height(x, y)
        if not math.isnan(g): diffs.append((abs(g - z), g, z, x, y, f))
    diffs.sort()
    d = [t[0] for t in diffs]
    print('WMO placements in Durotar: %d; |ground - placement z|: median %.1f, 75%% %.1f, 90%% %.1f yd' % (len(d), d[len(d) // 2], d[3 * len(d) // 4], d[9 * len(d) // 10]))
    # the swapped axes, for comparison: if x and y were crossed, the placements would not sit on the ground
    sw = []
    for f, x, y, z in pl:
        gx = (ORG - x) / S; gy = (ORG - y) / S                    # crossed
        c = int(gx // 128); r = int(gy // 128); h = tile(c, r); i = min(int(gx - c * 128), 127); j = min(int(gy - r * 128), 127)
        v = h[j, i]
        if not np.isnan(v): sw.append(abs(v - z))
    sw.sort()
    if sw: print('axes crossed instead: median %.1f yd (%d)' % (sw[len(sw) // 2], len(sw)))
    # spot heights at known places (the app's own nodes)
    for name, x, y in [('Razor Hill (town)', 321.7, -4769.2), ('Orgrimmar gate (exit)', 1408.0, -4319.0), ("Sen'jin Village", -813.0, -4915.0),
                       ('Valley of Trials camp', -599.0, -4201.0), ('zeppelin tower dock', 1360.9, -4631.3)]:
        print('  %-24s ground %.1f' % (name, height(x, y)))
    for t in diffs[:3] + diffs[len(diffs) // 2: len(diffs) // 2 + 3]: print('  sample |d| %.1f ground %.1f wmo %.1f at %.0f,%.0f' % t[:5])
