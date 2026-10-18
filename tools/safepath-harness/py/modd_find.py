# a placed WMO's own doodads (MODD, by MODI file ids or MODN names) matching a pattern, in world yards
import sys, struct, os, csv, re, math
import heights as HT
names = {}
for row in csv.reader(open(os.environ.get('LISTFILE', 'E:/wc-scratch-c9979a42/casc/community-listfile.csv'), encoding='utf-8', errors='ignore'), delimiter=';'):
    if len(row) >= 2: names[int(row[0])] = row[1]
pat = re.compile(sys.argv[1], re.I); wmo_pat = re.compile(sys.argv[2], re.I); d = os.path.join(os.environ.get('SAFEPATH_WORK', 'E:/safepath-work'), sys.argv[3] if len(sys.argv) > 3 else 'kalimdor')
def chunks(b):
    o = 0
    while o + 8 <= len(b):
        n = struct.unpack_from('<i', b, o + 4)[0]; yield b[o:o+4][::-1].decode('latin1'), o + 8, n; o += 8 + n
for uid, (fid, x, y, z) in HT.placements().items():
    if not wmo_pat.search(names.get(fid, '')): continue
    p = os.path.join(d, 'wmo', str(fid))
    if not os.path.exists(p): continue
    b = open(p, 'rb').read(); modi = []; modn = b''; modd = []
    for tag, o, n in chunks(b):
        if tag == 'MODI': modi = list(struct.unpack_from('<%dI' % (n // 4), b, o))
        if tag == 'MODN': modn = b[o:o + n]
        if tag == 'MODD': modd = [b[o + i * 40:o + i * 40 + 40] for i in range(n // 40)]
    # the placement's rotation (MODF yaw) as WorldBuildings: its own frame to world
    print('WMO', names.get(fid), 'uid', uid, 'at', round(x), round(y), round(z), 'doodads', len(modd))
    rows = []
    for dd in modd:
        ni = struct.unpack_from('<I', dd, 0)[0] & 0xFFFFFF; lx, ly, lz = struct.unpack_from('<fff', dd, 4)
        nm = names.get(modi[ni], '?') if modi else modn[ni:modn.index(b'\0', ni)].decode('latin1')
        if pat.search(nm): rows.append((nm, lx, ly, lz))
    for r in rows: print('   %-70s local %8.1f %8.1f %6.1f' % (r[0][-70:], r[1], r[2], r[3]))
