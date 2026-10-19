# the cave / tunnel / mine WMOs placed in the dumped tiles: their group files (the root's GFID), for the floors
# usage: python dump_cavegroups.py <dir> [fid,fid,...]   (the extra fids: bridges, ramps, platforms)
import sys, struct, os, glob, csv
import os
sys.path.insert(0, os.environ.get('CASC_TOOLS', r'E:/wc-addon-map/tools/icons'))
import casc
h = casc.open_storage(os.environ.get('WOW_DIR', r'C:\Program Files (x86)\World of Warcraft'), os.environ.get('WOW_PRODUCT', 'wow_classic_beta'), locale=0x2)
OUT = os.environ.get('SAFEPATH_WORK', 'E:/safepath-work') + '/' + (sys.argv[1] if len(sys.argv) > 1 else 'kalimdor')
os.makedirs(OUT + '/wmogroup', exist_ok=True)
names = {}
for row in csv.reader(open(os.environ.get('LISTFILE', 'E:/wc-scratch-c9979a42/casc/community-listfile.csv'), encoding='utf-8', errors='ignore'), delimiter=';'):
    if len(row) >= 2 and row[1].endswith('.wmo'): names[int(row[0])] = row[1]
def le(b, o): return struct.unpack_from('<i', b, o)[0]
caves = []
# A CAVE BY ITS SHAPE (no cave word in its name: Dustwallow's Den of Flame and Onyxia's Lair, Felwood's Timbermaw Hold, Ashenvale's
# Thistlefur Hold, Arathi's Boulderfist Hall): most of its rooms (interior groups) lie under the terrain where it is placed
os.environ.setdefault('ZDIR', sys.argv[1] if len(sys.argv) > 1 else 'kalimdor')
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'py'))
import math, heights as HT
_placed = {}
for _u, (_f, _x, _y, _z) in HT.placements().items(): _placed.setdefault(_f, []).append((_x, _y, _z))
def buried(rb, fid):
    """most interior groups' boxes (their tops) under the terrain at their middles, at any placement of [fid] (yaw ignored: a room's
    middle is near the origin's)"""
    boxes = []; ro = 0
    while ro + 8 <= len(rb):
        rn = le(rb, ro + 4); rt = rb[ro:ro+4][::-1].decode('latin1')
        if rt == 'MOGI':
            for g in range(rn // 32):
                fl = le(rb, ro + 8 + g * 32)
                if fl & 0x2000: boxes.append(struct.unpack_from('<6f', rb, ro + 8 + g * 32 + 4))
        if rn < 0: break
        ro += 8 + rn
    if not boxes: return False
    for (x, y, z) in _placed.get(fid, []):
        under = 0
        for bx in boxes:
            cx = (bx[0] + bx[3]) / 2; cy = (bx[1] + bx[4]) / 2; top = z + max(bx[2], bx[5])
            g = HT.height(x - cx, y - cy)
            if not math.isnan(g) and top < g - 2.0: under += 1
        if under * 2 > len(boxes): return True
    return False
# the manifest the harness reads: which WMOs' floors are ways (a cave, a walkway, one asked for by id)
kinds = {}
kp = OUT + '/wmogroup/kinds.txt'
if os.path.exists(kp):
    for line in open(kp):
        k, _, v = line.strip().partition(' ')
        if k.isdigit(): kinds[int(k)] = v
# extra WMOs by file id (argv[2], comma-separated): bridges, ramps, platforms - every WMO whose groups are dumped is read for its floor
extra = {int(x) for x in sys.argv[2].split(',')} if len(sys.argv) > 2 else set()
for f in os.listdir(OUT + '/wmo'):
    fid = int(f); n = names.get(fid, '')
    # and every walkway (a WMO with an outdoor group and no room in it: a bridge, a pier, a dock) - its deck is its floor
    rb = open(OUT + '/wmo/' + f, 'rb').read(); ro = 0; walkway = False
    while ro + 8 <= len(rb):
        rn = le(rb, ro + 4); rt = rb[ro:ro+4][::-1].decode('latin1')
        if rt == 'MOGI':
            fls = [le(rb, ro + 8 + g * 32) for g in range(rn // 32)]
            walkway = any(v & 0x8 for v in fls) and not any(v & 0x2000 for v in fls)     # outdoor only, no room: a bridge, a pier, a dock
        if rn < 0: break
        ro += 8 + rn
    cave = any(k in n for k in ('cave', 'tunnel', 'mine', 'den', 'pass', 'crypt', 'barrow', 'hollow', 'grave', 'mound', 'furbog', 'blackfathom')) and ('/dungeon/' in n or 'cave' in n)
    if not cave and not walkway and fid not in extra and buried(rb, fid): cave = True; print('%7d %s: its rooms under the terrain - a cave' % (fid, n[-60:]))
    if fid not in extra and not walkway and not cave: continue
    kinds[fid] = 'extra' if fid in extra else 'cave' if cave else 'walkway'
    b = open(OUT + '/wmo/' + f, 'rb').read(); o = 0; gf = []
    while o + 8 <= len(b):
        nn = le(b, o + 4); tag = b[o:o+4][::-1].decode('latin1')
        if tag == 'GFID': gf = list(struct.unpack_from('<%dI' % (nn // 4), b, o + 8))
        o += 8 + nn
    got = 0
    for k, g in enumerate(gf):
        p = '%s/wmogroup/%d_%d' % (OUT, fid, k)
        if g and not os.path.exists(p):
            d = casc.read_fileid(h, g)
            if d: open(p, 'wb').write(d)
        got += os.path.exists(p)
    caves.append((fid, n, len(gf), got))
for c in caves: print('%7d %-70s groups %d, got %d' % c)
open(kp, 'w').write(''.join('%d %s\n' % kv for kv in sorted(kinds.items())))
