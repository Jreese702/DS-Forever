# THE REGRESSION GATE: compares a run of every zone (gate.sh writes one log per zone) against the saved baseline, and fails on
#   - any WALLS line, EMPTY route or "GOAL ... not found";
#   - a goal that was reached in the baseline and is unreachable now;
#   - any route crossing water deeper than a narrow stream may be waded (2.2 yd: WATERMAX), or wetcheck's own reading of it deeper
#     than the ford (1.5 yd) for more than 6 yd on end or deeper than 2.2 at all (the user's narrow-stream ruling, 2026-10-18);
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
NARROW, NARROW_YD = 2.2, 6   # the user's narrow-stream ruling (2026-10-18): a stream up to 2.2 yd deep is waded for 6 yd at most
run = sys.argv[1]
def parse(log):
    z = {'routes': {}, 'walls': [], 'empty': [], 'notfound': [], 'water': {}, 'wet': {}, 'exits': {}, 'kept': [], 'segcheck': None, 'swims': []}
    for line in open(log, encoding='utf-8', errors='replace'):
        line = line.rstrip('\n')
        m = re.match(r'ROUTE2 (\w) (.*?): (\d+) yd, .*?flags(.*)$', line)
        if m:
            z['routes'][m.group(1)] = {'name': m.group(2), 'yd': int(m.group(3)), 'reached': 'unreachable' not in m.group(4)}
            continue
        if line.startswith('SWIM route'): z['swims'].append(line)                # a declared swim (the user's "Swim link, flagged"): noted
        if line.startswith('WARNING WMO'): z.setdefault('unread', []).append(line[12:].split(':')[0])     # a WMO whose floors weren't dumped
        if line.startswith('WALLS route'): z['walls'].append(line)
        elif line.startswith('EMPTY'): z['empty'].append(line)
        elif line.startswith('GOAL') and 'not found' in line: z['notfound'].append(line)
        m = re.match(r'WATERMAX route (\w) ([\d.]+) yd(?: at (.*))?', line)
        if m: z['water'][m.group(1)] = (float(m.group(2)), m.group(3) or '')
        m = re.match(r'EXIT (.*?) \((\w+)\) at (-?\d+),(-?\d+) .* - (WALKABLE|NOT WALKABLE)$', line)
        if m: z['exits']['%s (%s) at %s,%s' % m.group(1, 2, 3, 4)] = m.group(5) == 'WALKABLE'
        m = re.match(r'BORDER crossing \d+: KEPT, (.*?) - (.*)$', line)
        if m: z['kept'].append(re.sub(r' (low|high) danger', '', m.group(2)))
        m = re.match(r'WET (\w): deepest ([\d.]+) yd(?: at (-?\d+,-?\d+))?, (\d+) yd deeper than .*?longest wade (\d+) yd(?: at (-?\d+,-?\d+))?, (\d+) yd deeper than', line)
        if m: z['wet'][m.group(1)] = (float(m.group(2)), m.group(3) or '', int(m.group(4)), int(m.group(5)), m.group(6) or '', int(m.group(7)))
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
        if d > NARROW: fails.append('%s: route %s crosses water %.1f yd deep at %s' % (tag, L, d, at))
    for L, (d, at, n, run, rat, over) in z['wet'].items():
        # the user's narrow-stream ruling (2026-10-18): deeper than the ford for no more than NARROW_YD at a time and never deeper than
        # NARROW is a wade; else a swim
        if over: fails.append('%s: route %s swims: %d yd deeper than %.1f, %.1f yd at %s (wetcheck)' % (tag, L, over, NARROW, d, at))
        elif run > NARROW_YD: fails.append('%s: route %s swims: %d yd on end deeper than the ford at %s (wetcheck)' % (tag, L, run, rat))
        elif n: notes.append('%s: route %s wades a narrow stream: %d yd deeper than the ford, at most %d yd on end, %.1f yd deep at %s' % (tag, L, n, run, d, at))
    if 'wet' in z and not z['wet'] and z['routes']: fails.append('%s: no wetcheck result' % tag)
    for sw in z.get('swims', []): notes.append('%s: %s' % (tag, sw))
    if z.get('unread'): notes.append('%s: %d WMOs with their floors not dumped (the ground under them taken as the terrain): %s' % (tag, len(z['unread']), ', '.join(z['unread'][:12])))
    if not b: notes.append('%s: no baseline (new zone)' % tag); continue
    for L, r in b['routes'].items():
        now = z['routes'].get(L)
        if now is None: fails.append('%s: route %s (%s) is gone' % (tag, L, r['name'])); continue
        if r['reached'] and not now['reached']: fails.append('%s: route %s (%s) no longer reaches its goal' % (tag, L, r['name']))
        if now['yd'] > r['yd'] * (1 + LONGER): fails.append('%s: route %s (%s) %d -> %d yd (+%d%%)' % (tag, L, r['name'], r['yd'], now['yd'], round(100 * (now['yd'] / r['yd'] - 1))))
        elif now['yd'] < r['yd'] * (1 - LONGER): notes.append('%s: route %s %d -> %d yd (shorter)' % (tag, L, r['yd'], now['yd']))
    for k, ok in b['exits'].items():
        if ok and not any(v and near(k, [o]) for o, v in z['exits'].items()): fails.append('%s: walkable exit %s is gone or not walkable' % (tag, k))
    def same_crossing(a, b):
        # "The Barrens 316,-3704, Durotar 320,-3758": the same zones in order, each end within 60 yd
        pa = re.findall(r'(.*?) (-?\d+),(-?\d+)(?:, |$)', a); pb = re.findall(r'(.*?) (-?\d+),(-?\d+)(?:, |$)', b)
        return len(pa) == len(pb) and len(pa) > 0 and all(x[0] == y[0] and abs(int(x[1]) - int(y[1])) <= 60 and abs(int(x[2]) - int(y[2])) <= 60 for x, y in zip(pa, pb))
    for k in b['kept']:
        if k not in z['kept'] and not any(same_crossing(k, o) for o in z['kept']): fails.append('%s: kept border crossing dropped: %s' % (tag, k))
bc = base.get('_cross', {})
for r in cross:
    for p in r['problems']: fails.append('cross %s (%s): %s' % (r['id'], r['name'], p))
    for g in r['gaps']: notes.append('cross %s: stops at a missing link: %s' % (r['id'], g[:140]))
    b = bc.get(r['id'])
    if not b: notes.append('cross %s: no baseline (new route)' % r['id']); continue
    if b['complete'] and not r['complete']: fails.append('cross %s: was complete, now stops at a gap' % r['id'])
    if r['walkYd'] > b['walkYd'] * (1 + LONGER): fails.append('cross %s (%s): %d -> %d yd walked (+%d%%)' % (r['id'], r['name'], b['walkYd'], r['walkYd'], round(100 * (r['walkYd'] / b['walkYd'] - 1))))
# the planner's checks (PlanCross): a FAIL fails; no plan.log is no check run
pl = os.path.join(run, 'plan.log')
if os.path.exists(pl):
    pls = [l.rstrip('
') for l in open(pl, encoding='utf-8', errors='replace') if l.startswith('PLAN ')]
    if not pls: fails.append('plan: no PLAN lines (did PlanCross run?)')
    for l in pls:
        if ' FAIL' in l.split('|')[0]: fails.append(l[5:])
        else: notes.append(l[5:300])
for n in notes: print('NOTE ' + n)
for f in fails: print('FAIL ' + f)
print('%s: %d zones, %d routes, %d cross-zone routes, %d failures' % ('GATE FAILED' if fails else 'GATE PASSED', len(zones), sum(len(z['routes']) for z in zones.values()), len(cross), len(fails)))
sys.exit(1 if fails else 0)
