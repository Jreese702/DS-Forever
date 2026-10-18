import sys, struct, glob, os, csv, re
import heights as HT
names = {}
for row in csv.reader(open(os.environ.get('LISTFILE', 'E:/wc-scratch-c9979a42/casc/community-listfile.csv'), encoding='utf-8', errors='ignore'), delimiter=';'):
    if len(row) >= 2 and (row[1].endswith('.m2') or row[1].endswith('.wmo')): names[int(row[0])] = row[1]
pat = re.compile(sys.argv[1], re.I); t, b, l, r = map(float, sys.argv[2:6])
d = os.path.join(os.environ.get('SAFEPATH_WORK', 'E:/safepath-work'), sys.argv[6] if len(sys.argv) > 6 else 'kalimdor')
seen = set()
for p in glob.glob(os.path.join(d, 'obj0', '*.adt')):
    bb = open(p, 'rb').read(); o = 0
    while o + 8 <= len(bb):
        tag = bb[o:o+4][::-1].decode('latin1'); n = struct.unpack_from('<i', bb, o + 4)[0]
        if tag == 'MDDF':
            for i in range(n // 36):
                a = o + 8 + i * 36
                fid, uid = struct.unpack_from('<II', bb, a); px, py, pz = struct.unpack_from('<fff', bb, a + 8)
                x, y, z = HT.ORG - pz, HT.ORG - px, py
                nm = names.get(fid, '?')
                if uid in seen or not pat.search(nm) or not (b <= x <= t and r <= y <= l): continue
                seen.add(uid); print('%-75s %8.1f %8.1f %6.1f ground %6.1f' % (nm[-75:], x, y, z, HT.height(x, y)))
        o += 8 + n
