import sys, struct, os, glob, io
import os
sys.path.insert(0, os.environ.get('CASC_TOOLS', r'E:/wc-addon-map/tools/icons'))
import casc, db2
from PIL import Image
h = casc.open_storage(os.environ.get('WOW_DIR', r'C:\Program Files (x86)\World of Warcraft'), os.environ.get('WOW_PRODUCT', 'wow_classic_beta'), locale=0x2)
maps = {r[0]: r[1] for r in db2.DB2(os.path.join(os.environ.get('SAFEPATH_WORK', 'E:/safepath-work'), 'db2', 'Map.db2')).rows(strings=(0, 1))}
BASE = os.environ.get('SAFEPATH_WORK', 'E:/safepath-work') + '/' + (sys.argv[1] if len(sys.argv) > 1 else 'kalimdor')
CONT = int(sys.argv[2]) if len(sys.argv) > 2 else 1
OUT = BASE + '/minimap'
os.makedirs(OUT, exist_ok=True)
def le(b, o): return struct.unpack_from('<i', b, o)[0]
wdt = casc.read_fileid(h, maps[CONT][21]); mm = {}; o = 0
while o + 8 <= len(wdt):
    n = le(wdt, o + 4); tag = wdt[o:o+4][::-1].decode('latin1')
    if tag == 'MAID':
        for i in range(n // 32):
            f = le(wdt, o + 8 + i * 32 + 28)
            if f: mm[(i % 64, i // 64)] = f
    o += 8 + n
ok = 0; bad = 0
for c, r in sorted(tuple(map(int, f[:-4].split('_'))) for f in os.listdir(BASE + '/adt')):
    if True:
        f = mm.get((c, r))
        if not f: continue
        p = '%s/%d_%d.png' % (OUT, c, r)
        if os.path.exists(p): ok += 1; continue
        b = casc.read_fileid(h, f)
        try:
            Image.open(io.BytesIO(b)).convert('RGB').save(p); ok += 1
        except Exception as e:
            bad += 1; print('bad', c, r, e)
print('minimap tiles', ok, 'failed', bad, 'of', len(os.listdir(BASE + '/adt')))
