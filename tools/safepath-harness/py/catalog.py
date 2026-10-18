import os
# a zone's catalogue for picking goals: its caves (named), its named nodes, its mobs by subzone, its buildings
import sys, json, csv, math
import heights as HT
d = json.load(open(sys.argv[1], encoding='utf-8'))
t, b, l, r = d['rect']
names = {}
for row in csv.reader(open(os.environ.get('LISTFILE', 'E:/wc-scratch-c9979a42/casc/community-listfile.csv'), encoding='utf-8', errors='ignore'), delimiter=';'):
    if len(row) >= 2 and row[1].endswith('.wmo'): names[int(row[0])] = row[1]
sub = d['subzones']
def where(x, y): return min(sub, key=lambda s: (s['wx'] - x) ** 2 + (s['wy'] - y) ** 2)['name'] if sub else ''
print('CAVES')
for c in d['caves']:
    print('  %6d %-45s at %6.0f %6.0f  (%s)  mouths %d ways %d' % (c['fid'], names.get(c['fid'], '?').split('/')[-1], c['wx'], c['wy'], where(c['wx'], c['wy']), len(c['mouths']), len(c['ways'])))
print('NODES'); [print('  %-45s %6.0f %6.0f %s' % (n[2], n[0], n[1], n[3])) for n in d['net']['nodes']]
print('EXITS'); [print('  ', e) for e in d['exitChecks']]
print('SUBZONES'); [print('  %-30s %6.0f %6.0f n %d' % (s['name'], s['wx'], s['wy'], s['n'])) for s in sorted(sub, key=lambda s: -s['n'])]
print('MOBS (most spawns)')
for m in sorted(d['mobs'], key=lambda m: -m[1])[:45]: print('  %-32s %4d lv %2d-%2d at %6.0f %6.0f  %s' % tuple(m))
print('BUILDINGS')
from collections import Counter
cnt = Counter()
for u, (f, x, y, z) in HT.placements().items():
    if b <= x <= t and r <= y <= l: cnt[(names.get(f, '?').split('/')[-1], f)] += 1
for (n, f), k in sorted(cnt.items(), key=lambda kv: -kv[1])[:60]: print('  %3d %-45s %d' % (k, n, f))
