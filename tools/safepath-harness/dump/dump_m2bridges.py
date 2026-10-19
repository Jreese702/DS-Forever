# the M2 doodads (MDDF) in the dumped obj0 tiles that may be bridges, into <dir>/m2/<fid> (local CASC reads only)
# usage: python dump_m2bridges.py <dir>
# The listfile only narrows the dump (bridge / dock / pier / walkway in the file's path); the app itself picks a bridge by the model's
# OWN name inside the file (WorldBuildings.isM2Bridge: the phone has no listfile), and the harness does the same on these files.
import sys, struct, os, glob, csv
sys.path.insert(0, os.environ.get('CASC_TOOLS', r'E:/wc-addon-map/tools/icons'))
import casc
h = casc.open_storage(os.environ.get('WOW_DIR', r'C:\Program Files (x86)\World of Warcraft'), os.environ.get('WOW_PRODUCT', 'wow_classic_beta'), locale=0x2)
OUT = os.environ.get('SAFEPATH_WORK', 'E:/safepath-work') + '/' + (sys.argv[1] if len(sys.argv) > 1 else 'kalimdor')
os.makedirs(OUT + '/m2', exist_ok=True)
KINDS = ('bridge', 'dock', 'pier', 'walkway')
names = {}
for row in csv.reader(open(os.environ.get('LISTFILE', 'E:/wc-scratch-c9979a42/casc/community-listfile.csv'), encoding='utf-8', errors='ignore'), delimiter=';'):
    if len(row) >= 2 and row[1].endswith('.m2') and any(k in row[1].rsplit('/', 1)[-1] for k in KINDS): names[int(row[0])] = row[1]
fids = {}
for p in glob.glob(OUT + '/obj0/*.adt'):
    b = open(p, 'rb').read(); o = 0
    while o + 8 <= len(b):
        n = struct.unpack_from('<i', b, o + 4)[0]; tag = b[o:o+4][::-1].decode('latin1')
        if n < 0: break
        if tag == 'MDDF':
            for i in range(n // 36):
                fid = struct.unpack_from('<I', b, o + 8 + i * 36)[0]; fl = struct.unpack_from('<H', b, o + 8 + i * 36 + 34)[0]
                if fl & 0x40 and fid in names: fids[fid] = fids.get(fid, 0) + 1
        o += 8 + n
got = 0
for fid, k in sorted(fids.items()):
    p = '%s/m2/%d' % (OUT, fid)
    if not os.path.exists(p):
        d = casc.read_fileid(h, fid)
        if d: open(p, 'wb').write(d)
    got += os.path.exists(p)
    print('%8d x%-4d %s' % (fid, k, names[fid]))
print('m2 bridge candidates', len(fids), 'got', got)
