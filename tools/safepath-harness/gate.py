# THE REGRESSION GATE: compares a run of every zone (gate.sh writes one log per zone) against the saved baseline, and fails on
#   - any WALLS line, EMPTY route or "GOAL ... not found";
#   - a goal that was reached in the baseline and is unreachable now;
#   - any route crossing water deeper than the ford (1.5 yd: WATERMAX);
#   - segcheck finding a 45+ degree crossing;
#   - an exit walkable in the baseline that is gone or not walkable now (an approved crossing dropped), a border crossing KEPT
#     in the baseline that is not now;
#   - a route more than 15% longer than in the baseline;
#   - a cross-zone route (cross/routes.json, stitched by py/cross.py into cross.json) with a leg that crosses a wall or deep water or
#     misses its goal, a leg that doesn't start where the last piece ended, or 15% longer than in the baseline. Its gaps (the links
#     we don't have) are listed, not failed: routes.json names them.
#   python gate.py <run folder> [--save]      (--save: this run becomes the baseline; the baseline lives in the work folder)
import os, re, sys, json
try: sys.stdout.reconfigure(encoding='utf-8')
except Exception: pass
WORK = os.environ.get('SAFEPATH_WORK', 'E:/safepath-work')
BASE = os.path.join(WORK, 'gate', 'baseline.json')
FORD = 1.5
LONGER = 0.15
run = sys.argv[1]
def parse(log):
    z = {'routes': {}, 'walls': [], 'empty': [], 'notfound': [], 'water': {}, 'exits': {}, 'kept': [], 'segcheck': None}
    for line in open(log, encoding='utf-8', errors='replace'):
        line = line.rstrip('\n')
        m = re.match(r'ROUTE2 (\w) (.*?): (\d+) yd, .*?flags(.*)$', line)
        if m:
            z['routes'][m.group(1)] = {'name': m.group(2), 'yd': int(m.group(3)), 'reached': 'unreachable' not in m.group(4)}
            continue
        if line.startswith('WALLS route'): z['walls'].append(line)
        elif line.startswith('EMPTY'): z['empty'].append(line)
        elif line.startswith('GOAL') and 'not found' in line: z['notfound'].append(line)
        m = re.match(r'WATERMAX route (\w) ([\d.]+) yd(?: at (.*))?', line)
        if m: z['water'][m.group(1)] = (float(m.group(2)), m.group(3) or '')
        m = re.match(r'EXIT (.*?) \((\w+)\) at (-?\d+),(-?\d+) .* - (WALKABLE|NOT WALKABLE)$', line)
        if m: z['exits']['%s (%s) at %s,%s' % m.group(1, 2, 3, 4)] = m.group(5) == 'WALKABLE'
        m = re.match(r'BORDER crossing \d+: KEPT, (.*?) - (.*)$', line)
        if m: z['kept'].append(re.sub(r' (low|high) danger', '', m.group(2)))
        m = re.match(r'(\d+) segments; crossing a 45\+ degree step: (.*)$', line)
        if m: z['segcheck'] = m.group(2)
    return z
zones = {}
for f in sorted(os.listdir(run)):
    if f.startswith('gate_') and f.endswith('.log'): zones[f[5:-4]] = parse(os.path.join(run, f))
cross = json.load(open(os.path.join(run, 'cross.json'), encoding='utf-8')) if os.path.exists(os.path.join(run, 'cross.json')) else []
if '--save' in sys.argv:
    if cross: zones['_cross'] = {r['id']: {'name': r['name'], 'walkYd': r['walkYd'], 'complete': r['complete'], 'clean': r['clean']} for r in cross}
    os.makedirs(os.path.dirname(BASE), exist_ok=True)
    json.dump(zones, open(BASE, 'w', encoding='utf-8'), indent=1)
    print('baseline saved: %d zones, %d routes -> %s' % (len(zones), sum(len(z['routes']) for t, z in zones.items() if t != '_cross'), BASE)); sys.exit(0)
base = json.load(open(BASE, encoding='utf-8')) if os.path.exists(BASE) else {}
fails = []; notes = []
def near(k, keys):
    # an exit or a crossing moved a few yards is the same one
    m = re.match(r'(.*) at (-?\d+),(-?\d+)', k)
    if not m: return k in keys
    for o in keys:
        mo = re.match(r'(.*) at (-?\d+),(-?\d+)', o)
        if mo and mo.group(1) == m.group(1) and abs(int(mo.group(2)) - int(m.group(2))) <= 60 and abs(int(mo.group(3)) - int(m.group(3))) <= 60: return True
    return False
for tag, z in zones.items():
    b = base.get(tag)
    for w in z['walls']: fails.append('%s: %s' % (tag, w))
    for e in z['empty']: fails.append('%s: %s' % (tag, e))
    for e in z['notfound']: fails.append('%s: %s' % (tag, e))
    if z['segcheck'] not in (None, 'none'): fails.append('%s: segcheck %s' % (tag, z['segcheck'][:200]))
    if z['segcheck'] is None: fails.append('%s: no segcheck result (did the run fail?)' % tag)
    for L, (d, at) in z['water'].items():
        if d > FORD: fails.append('%s: route %s crosses water %.1f yd deep at %s' % (tag, L, d, at))
    if not b: notes.append('%s: no baseline (new zone)' % tag); continue
    for L, r in b['routes'].items():
        now = z['routes'].get(L)
        if now is None: fails.append('%s: route %s (%s) is gone' % (tag, L, r['name'])); continue
        if r['reached'] and not now['reached']: fails.append('%s: route %s (%s) no longer reaches its goal' % (tag, L, r['name']))
        if now['yd'] > r['yd'] * (1 + LONGER): fails.append('%s: route %s (%s) %d -> %d yd (+%d%%)' % (tag, L, r['name'], r['yd'], now['yd'], round(100 * (now['yd'] / r['yd'] - 1))))
        elif now['yd'] < r['yd'] * (1 - LONGER): notes.append('%s: route %s %d -> %d yd (shorter)' % (tag, L, r['yd'], now['yd']))
    for k, ok in b['exits'].items():
        if ok and not any(v and near(k, [o]) for o, v in z['exits'].items()): fails.append('%s: walkable exit %s is gone or not walkable' % (tag, k))
    for k in b['kept']:
        if not near(k, z['kept']) and k not in z['kept']: fails.append('%s: kept border crossing dropped: %s' % (tag, k))
bc = base.get('_cross', {})
for r in cross:
    for p in r['problems']: fails.append('cross %s (%s): %s' % (r['id'], r['name'], p))
    for g in r['gaps']: notes.append('cross %s: stops at a missing link: %s' % (r['id'], g[:140]))
    b = bc.get(r['id'])
    if not b: notes.append('cross %s: no baseline (new route)' % r['id']); continue
    if b['complete'] and not r['complete']: fails.append('cross %s: was complete, now stops at a gap' % r['id'])
    if r['walkYd'] > b['walkYd'] * (1 + LONGER): fails.append('cross %s (%s): %d -> %d yd walked (+%d%%)' % (r['id'], r['name'], b['walkYd'], r['walkYd'], round(100 * (r['walkYd'] / b['walkYd'] - 1))))
for n in notes: print('NOTE ' + n)
for f in fails: print('FAIL ' + f)
print('%s: %d zones, %d routes, %d cross-zone routes, %d failures' % ('GATE FAILED' if fails else 'GATE PASSED', len(zones), sum(len(z['routes']) for z in zones.values()), len(cross), len(fails)))
sys.exit(1 if fails else 0)
