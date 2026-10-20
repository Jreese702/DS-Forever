#!/usr/bin/env bash
# NET2's extra ways (NetFloors) checked as routes: py/segcheck.py and py/wetcheck.py on <out>/nf/levels_routes_<tag>.json, the run's
# floors / holes / layers / decks linked beside it (hard links: no copies on the tight disk)
#   bash tools/safepath-harness/nfcheck.sh <out folder> <tag> <dump dir>
HERE="$(cd "$(dirname "$0")" && pwd)"; OUT="$1"; T="$2"; D="$3"
for f in floors holes layers decks; do [ -f "$OUT/${f}_$T.json" ] && ln -f "$OUT/${f}_$T.json" "$OUT/nf/${f}_$T.json"; done
cd "$HERE/py"
PYTHONIOENCODING=utf-8 ZDIR=$D python segcheck.py "$OUT/nf/levels_routes_$T.json" 2>&1 | tail -1
PYTHONIOENCODING=utf-8 ZDIR=$D python wetcheck.py "$OUT/nf/levels_routes_$T.json" 2>&1 | python -c "
import re, sys
ok = 0; bad = []
for l in sys.stdin:
    m = re.match(r'WET (\w+): deepest ([\d.]+) yd.*?, (\d+) yd deeper than .*?longest wade (\d+) yd.*?, (\d+) yd deeper than', l)
    if not m: continue
    if int(m.group(5)) or int(m.group(4)) > 6: bad.append(l.strip())
    else: ok += 1
print('WETCHECK %d ways dry or a ford; swims: %s' % (ok, '; '.join(bad) or 'none'))"
rm -f "$OUT/nf/floors_$T.json" "$OUT/nf/holes_$T.json" "$OUT/nf/layers_$T.json" "$OUT/nf/decks_$T.json"
