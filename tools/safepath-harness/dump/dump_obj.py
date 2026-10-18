import sys, struct, os, glob
import os
sys.path.insert(0, os.environ.get('CASC_TOOLS', r'E:/wc-addon-map/tools/icons'))
import casc, db2
h = casc.open_storage(os.environ.get('WOW_DIR', r'C:\Program Files (x86)\World of Warcraft'), os.environ.get('WOW_PRODUCT', 'wow_classic_beta'), locale=0x2)
maps = {r[0]: r[1] for r in db2.DB2(os.path.join(os.environ.get('SAFEPATH_WORK', 'E:/safepath-work'), 'db2', 'Map.db2')).rows(strings=(0, 1))}
OUT = os.environ.get('SAFEPATH_WORK', 'E:/safepath-work') + '/' + (sys.argv[1] if len(sys.argv) > 1 else 'kalimdor')
os.makedirs(OUT + '/obj0', exist_ok=True); os.makedirs(OUT + '/wmo', exist_ok=True)
def le(b, o): return struct.unpack_from('<i', b, o)[0]
wdt = casc.read_fileid(h, maps[int(sys.argv[2]) if len(sys.argv) > 2 else 1][21]); maid = {}; o = 0
while o + 8 <= len(wdt):
    n = le(wdt, o + 4); tag = wdt[o:o+4][::-1].decode('latin1')
    if tag == 'MAID':
        for i in range(n // 32):
            obj0 = le(wdt, o + 8 + i * 32 + 4)
            if obj0: maid[(i % 64, i // 64)] = obj0
    o += 8 + n
wmos = set(); n = 0
for f in glob.glob(OUT + '/adt/*.adt'):
    c, r = map(int, os.path.basename(f)[:-4].split('_'))
    fid = maid.get((c, r))
    if not fid: continue
    p = '%s/obj0/%d_%d.adt' % (OUT, c, r)
    if not os.path.exists(p): open(p, 'wb').write(casc.read_fileid(h, fid))
    b = open(p, 'rb').read(); o = 0; n += 1
    while o + 8 <= len(b):
        nn = le(b, o + 4); tag = b[o:o+4][::-1].decode('latin1')
        if tag == 'MODF':
            for i in range(nn // 64): wmos.add(struct.unpack_from('<I', b, o + 8 + i * 64)[0])
        o += 8 + nn
for w in wmos:
    p = '%s/wmo/%d' % (OUT, w)
    if not os.path.exists(p): open(p, 'wb').write(casc.read_fileid(h, w) or b'')
print('obj0', n, 'wmo roots', len(wmos))
