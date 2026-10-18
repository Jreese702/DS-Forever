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
# extra WMOs by file id (argv[2], comma-separated): bridges, ramps, platforms - every WMO whose groups are dumped is read for its floor
extra = {int(x) for x in sys.argv[2].split(',')} if len(sys.argv) > 2 else set()
for f in os.listdir(OUT + '/wmo'):
    fid = int(f); n = names.get(fid, '')
    if fid not in extra and (not any(k in n for k in ('cave', 'tunnel', 'mine', 'den', 'pass', 'crypt', 'barrow', 'hollow', 'grave')) or '/dungeon/' not in n and 'cave' not in n): continue
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
