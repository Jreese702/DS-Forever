# Durotar (+ Orgrimmar) off-device: root and tex0 ADTs of Kalimdor's tiles under the zone, and the tex0 textures' names (listfile)
import sys, struct, math, json, os
import os
sys.path.insert(0, os.environ.get('CASC_TOOLS', r'E:/wc-addon-map/tools/icons'))
import casc, db2
h = casc.open_storage(os.environ.get('WOW_DIR', r'C:\Program Files (x86)\World of Warcraft'), os.environ.get('WOW_PRODUCT', 'wow_classic_beta'), locale=0x2)
maps = {r[0]: r[1] for r in db2.DB2(os.path.join(os.environ.get('SAFEPATH_WORK', 'E:/safepath-work'), 'db2', 'Map.db2')).rows(strings=(0, 1))}
ADT = 1600.0 / 3.0
OUT = os.environ.get('SAFEPATH_WORK', 'E:/safepath-work') + '/' + sys.argv[2]
os.makedirs(OUT + '/adt', exist_ok=True); os.makedirs(OUT + '/tex0', exist_ok=True)
def le(b, o): return struct.unpack_from('<i', b, o)[0]
wdt = casc.read_fileid(h, maps[int(sys.argv[3]) if len(sys.argv) > 3 else 1][21])
maid = {}
o = 0
while o + 8 <= len(wdt):
    n = le(wdt, o + 4); tag = wdt[o:o+4][::-1].decode('latin1')
    if tag == 'MAID':
        for i in range(n // 32):
            root = le(wdt, o + 8 + i * 32); tex0 = le(wdt, o + 8 + i * 32 + 12)
            if root: maid[(i % 64, i // 64)] = (root, tex0)
    o += 8 + n
rects = json.load(open(os.environ.get('RECIPE', os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', '..', 'companion', 'app', 'src', 'main', 'assets', 'harvest', 'worldmap.json')), encoding='utf-8'))['rects']
c, t, b, l, r = rects[sys.argv[1]]
col0 = math.floor(32 - l / ADT) - 1; col1 = math.ceil(32 - r / ADT) + 1; row0 = math.floor(32 - t / ADT) - 1; row1 = math.ceil(32 - b / ADT) + 1
mdid = set(); n = 0; tot = 0
for row in range(row0, row1):
    for col in range(col0, col1):
        if (col, row) not in maid: continue
        root, tex0 = maid[(col, row)]
        for fid, d in ((root, 'adt'), (tex0, 'tex0')):
            p = '%s/%s/%d_%d.adt' % (OUT, d, col, row)
            if fid and not os.path.exists(p):
                bb = casc.read_fileid(h, fid)
                if bb: open(p, 'wb').write(bb); tot += len(bb)
        n += 1
        p = '%s/tex0/%d_%d.adt' % (OUT, col, row)
        if os.path.exists(p):
            bb = open(p, 'rb').read(); o = 0
            while o + 8 <= len(bb):
                nn = le(bb, o + 4); tag = bb[o:o+4][::-1].decode('latin1')
                if tag == 'MDID':
                    for i in range(nn // 4): mdid.add(struct.unpack_from('<I', bb, o + 8 + i * 4)[0])
                o += 8 + nn
names = {}
for line in open(os.environ.get('LISTFILE', 'E:/wc-scratch-c9979a42/casc/community-listfile.csv'), encoding='utf-8', errors='replace'):
    k, _, v = line.strip().partition(';')
    if k.isdigit() and int(k) in mdid: names[int(k)] = v
old = {}
if os.path.exists(OUT + '/tex0/names.txt'):
    for line in open(OUT + '/tex0/names.txt', encoding='utf-8'):
        k, _, v = line.strip().partition(';')
        if k.isdigit(): old[int(k)] = v
names.update({k: v for k, v in old.items() if k not in names})   # every zone dumped here keeps its textures' names
open(OUT + '/tex0/names.txt', 'w', encoding='utf-8').write(''.join('%d;%s\n' % kv for kv in sorted(names.items())))
print('tiles', n, 'cols', col0, col1, 'rows', row0, row1, 'MB', tot // 1000000, 'textures', len(mdid), 'named', len(names))
