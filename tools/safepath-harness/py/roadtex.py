# A zone's ground textures at full resolution (the tex0 ADTs' layers and alpha maps, as the app's Roads.tile / Roads.showing read
# them): where a texture SHOWS MOST at each ~4 yd point, and how much of each 25 yd draft cell it covers. For checking whether a
# zone's learned road textures (Roads.learn; the harness stands in with the client's names) can carry its road network.
#   python roadtex.py <regex of the road textures> t b l r <out.png> [art_draft.png]
# (t b l r: world x top / bottom, world y left / right). Prints, per texture matching the regex, its share of the points and how many
# 25 yd cells it dominates (the draft's own test) against how many it merely touches (>= 1 point of 36).
import sys, os, re, struct, csv
import numpy as np
from PIL import Image
import heights as HT

ADT = HT.ADT; CHUNK = ADT / 16
names = {}
for l in open(os.path.join(HT.DIR, 'tex0', 'names.txt'), encoding='utf-8', errors='ignore'):
    a = l.strip().split(';', 1)
    if len(a) == 2: names[int(a[0])] = a[1]

_t = {}
def tex_tile(c, r):
    if (c, r) in _t: return _t[(c, r)]
    p = os.path.join(HT.DIR, 'tex0', '%d_%d.adt' % (c, r)); out = None
    if os.path.exists(p):
        b = open(p, 'rb').read(); mdid = []; chunks = []
        for tag, o, n in HT.chunks(b):
            if tag == 'MDID': mdid = list(struct.unpack_from('<%dI' % (n // 4), b, o + 8))
            elif tag == 'MCNK':
                mcly = mcal = None
                for t2, p2, n2 in HT.chunks(b, o + 8, o + 8 + n):
                    if t2 == 'MCLY': mcly = (p2 + 8, n2)
                    if t2 == 'MCAL': mcal = (p2 + 8, n2)
                if not mcly or mcly[1] < 16: chunks.append(None); continue
                L = mcly[1] // 16
                tex = []; alpha = []
                for k in range(L):
                    ti, fl, ofs = struct.unpack_from('<III', b, mcly[0] + k * 16)
                    tex.append(mdid[ti] if ti < len(mdid) else 0)
                    if k == 0 or not mcal or not fl & 0x100 or ofs >= mcal[1]: alpha.append(None if k == 0 else np.zeros(4096, np.uint8)); continue
                    nxt = struct.unpack_from('<I', b, mcly[0] + (k + 1) * 16 + 8)[0] if k + 1 < L else mcal[1]
                    ln = min(mcal[1], max(nxt, ofs)) - ofs; at = mcal[0] + ofs
                    a = np.zeros(4096, np.uint8)
                    if fl & 0x200:
                        i = at; w = 0
                        while w < 4096 and i < at + ln:
                            cc = b[i]; i += 1; cnt = cc & 0x7f
                            if cc & 0x80: a[w:w + cnt] = b[i]; i += 1; w += cnt
                            else: a[w:w + cnt] = np.frombuffer(b[i:i + cnt], np.uint8)[:max(0, 4096 - w)]; i += cnt; w += cnt
                    elif ln >= 4096: a[:] = np.frombuffer(b[at:at + 4096], np.uint8)
                    elif ln >= 2048:
                        v = np.frombuffer(b[at:at + 2048], np.uint8); a[0::2] = (v & 15) * 17; a[1::2] = (v >> 4) * 17
                    alpha.append(a)
                chunks.append((tex, alpha))
        out = chunks
    _t[(c, r)] = out
    return out

def showing(wx, wy):
    gx = (HT.ORG - wx) / ADT; gy = (HT.ORG - wy) / ADT
    row = int(gx // 1); col = int(gy // 1)
    t = tex_tile(col, row)
    if not t: return 0
    ix = (gx - row) * ADT; iy = (gy - col) * ADT
    cy = min(15, int(ix / CHUNK)); cx = min(15, int(iy / CHUNK))
    ch = t[cy * 16 + cx] if cy * 16 + cx < len(t) else None
    if not ch: return 0
    py = min(63, int((ix - cy * CHUNK) / CHUNK * 64)); px = min(63, int((iy - cx * CHUNK) / CHUNK * 64))
    tex, alpha = ch; best = 0; bw = -1
    for k in range(len(tex)):
        w = 1.0 if k == 0 else alpha[k][py * 64 + px] / 255
        for m in range(k + 1, len(tex)): w *= 1 - alpha[m][py * 64 + px] / 255
        if w > bw: bw = w; best = tex[k]
    return best

if __name__ == '__main__':
    pat = re.compile(sys.argv[1], re.I); t, b, l, r = map(float, sys.argv[2:6]); outp = sys.argv[6]
    step = 25.0 / 6
    nx = int((l - r) / step); ny = int((t - b) / step)
    g = np.zeros((ny, nx), np.int64)
    for j in range(ny):
        for i in range(nx): g[j, i] = showing(t - (j + 0.5) * step, l - (i + 0.5) * step)
    ids, cnt = np.unique(g, return_counts=True)
    print('top textures (points):', [(names.get(int(k), str(k)).split('/')[-1], int(c)) for k, c in sorted(zip(ids, cnt), key=lambda kv: -kv[1])[:8]])
    road = np.zeros_like(g, bool)
    for k in ids:
        if pat.search(names.get(int(k), '')): road |= g == k
    C = 6; cy = ny // C; cx = nx // C
    cells = road[:cy * C, :cx * C].reshape(cy, C, cx, C).sum(axis=(1, 3))
    print('road points %d of %d (%.2f%%); 25 yd cells: dominated (>= 18 of 36) %d, half-cover 9+ %d, touched (>= 1) %d of %d' % (
        road.sum(), road.size, 100 * road.mean(), (cells >= 18).sum(), (cells >= 9).sum(), (cells >= 1).sum(), cells.size))
    img = np.zeros((ny, nx, 3), np.uint8); img[:] = (40, 70, 40)
    for k in ids:
        n = names.get(int(k), '').lower()
        if 'dirt' in n: img[g == k] = (120, 100, 60)
        if 'rock' in n: img[g == k] = (90, 90, 90)
    img[road] = (255, 220, 0)
    Image.fromarray(img).save(outp)
    np.save(outp.replace('.png', '.npy'), road)
