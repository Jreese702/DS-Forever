# THE SHIPPED NETWORK'S GATE (export.sh; NET1): which of a run folder's zones may ship their network. A zone's section
# (net1_<tag>.json, RenderSafePath with SAFEPATH_EXPORT=1) ships only when its own log (gate_<tag>.log, gate.sh's shape) has
#   - no WALLS line, no EMPTY route, no "GOAL ... not found";
#   - segcheck "none" (no 45+ degree step crossed);
#   - a wetcheck result for every route, and no swim by it (deeper than the narrow stream, 2.2 yd, at all, or deeper than the ford
#     for more than 6 yd on end) and no WATERMAX over 2.2 yd - gate.py's own readings, without the baseline's comparisons.
#   python exportcheck.py <run folder>     prints EXPORT OK <tag> <file> / EXPORT SKIP <tag>: why
import os, re, sys
try: sys.stdout.reconfigure(encoding='utf-8')
except Exception: pass
NARROW = 2.2; NARROW_YD = 6
run = sys.argv[1]
for f in sorted(os.listdir(run)):
    if not (f.startswith('gate_') and f.endswith('.log')): continue
    tag = f[5:-4]; sec = os.path.join(run, 'net1_%s.json' % tag).replace('\\', '/')
    why = []; routes = 0; wet = 0; seg = None
    for line in open(os.path.join(run, f), encoding='utf-8', errors='replace'):
        line = line.strip()
        if line.startswith('WALLS route'): why.append(line)
        elif line.startswith('EMPTY'): why.append(line[:120])
        elif line.startswith('GOAL') and 'not found' in line: why.append(line[:120])
        elif line.startswith('ROUTE2 '): routes += 1
        m = re.match(r'WATERMAX route (\w) ([\d.]+) yd', line)
        if m and float(m.group(2)) > NARROW: why.append('route %s WATERMAX %s yd' % (m.group(1), m.group(2)))
        m = re.match(r'WET (\w): deepest ([\d.]+) yd.*?, (\d+) yd deeper than .*?longest wade (\d+) yd.*?, (\d+) yd deeper than', line)
        if m:
            wet += 1
            if int(m.group(5)): why.append('route %s swims (wetcheck): %s yd deeper than %.1f' % (m.group(1), m.group(5), NARROW))
            elif int(m.group(4)) > NARROW_YD: why.append('route %s swims (wetcheck): %s yd on end deeper than the ford' % (m.group(1), m.group(4)))
        m = re.match(r'(\d+) segments; crossing a 45\+ degree step: (.*)$', line)
        if m: seg = m.group(2)
    if seg is None: why.append('no segcheck result')
    elif seg != 'none': why.append('segcheck: ' + seg[:160])
    if routes == 0: why.append('no routes')
    elif wet < routes: why.append('wetcheck read %d of %d routes' % (wet, routes))
    if not os.path.isfile(sec): why.append('no net1_%s.json (run without SAFEPATH_EXPORT=1?)' % tag)
    print(('EXPORT OK %s %s' % (tag, sec)) if not why else ('EXPORT SKIP %s: %s' % (tag, '; '.join(why))))
