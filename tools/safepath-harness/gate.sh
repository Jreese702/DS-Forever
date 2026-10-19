#!/usr/bin/env bash
# THE REGRESSION GATE: every zone spec and the climb tests through the harness on this checkout, then gate.py against the baseline.
#   bash tools/safepath-harness/gate.sh            run and compare (exit 1 on any failure)
#   bash tools/safepath-harness/gate.sh --save     run and make this run the baseline (only the rules owner, after a review)
#   CROSS_ONLY=1 bash tools/safepath-harness/gate.sh   only the cross-zone routes (cross/routes.json), compared as the rest
# Runs into $SAFEPATH_WORK/gate/run-<time>/ (or $GATE_OUT); one log per zone, gate_<tag>.log. Takes about 30-40 minutes, the
# cross-zone routes 20 more: their legs, one spec per zone they cross (py/cross.py specs), stitched into cross.json.
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
export SAFEPATH_WORK="${SAFEPATH_WORK:-E:/safepath-work}"
OUT="${GATE_OUT:-$SAFEPATH_WORK/gate/run-$(date +%Y%m%d-%H%M%S)}"
mkdir -p "$OUT"
export SAFEPATH_OUT="$OUT"
ZONES="durotar:kalimdor elwynn:azeroth zephras:zephras tirisfal:azeroth teldrassil:kalimdor dunmorogh:azeroth mulgore:kalimdor barrens:kalimdor westfall:azeroth lochmodan:azeroth silverpine:azeroth darkshore:kalimdor climb_barrens:kalimdor climb_mulgore:kalimdor climb_durotar:kalimdor"
[ -n "${CROSS_ONLY:-}" ] && ZONES=""
# the cross-zone routes' legs: each zone they cross, its own spec with the legs for goals
CROSS=$(cd "$HERE/py" && PYTHONIOENCODING=utf-8 python cross.py specs "$OUT" | tr -d '\r')
for z in $ZONES $CROSS; do
  tag=${z%%:*}; dir=${z##*:}
  spec="$HERE/zones/$tag.json"; [ -f "$spec" ] || spec="$OUT/specs/$tag.json"
  echo "=== $tag"
  bash "$HERE/run.sh" "$spec" "ROUTE2|WALLS|EMPTY|GOAL|WATERMAX|EXIT |EXITS added|BORDER crossing|ROADWALLS|DROPWALLED|SAFEPATH [A-Z][a-z' ]+:|^e:|Exception" > "$OUT/gate_$tag.log" 2>&1
  (cd "$HERE/py" && PYTHONIOENCODING=utf-8 ZDIR=$dir python segcheck.py "$OUT/levels_routes_$tag.json" 2>&1 | tail -1) >> "$OUT/gate_$tag.log"
  # the water, read apart from the app's grid: every yard of every route over the liquid's own surface and the terrain (py/wetcheck.py)
  (cd "$HERE/py" && PYTHONIOENCODING=utf-8 ZDIR=$dir python wetcheck.py "$OUT/levels_routes_$tag.json" 2>&1 | grep -E "^WET|Error") >> "$OUT/gate_$tag.log"
  grep -cE "^ROUTE2" "$OUT/gate_$tag.log" | sed "s/^/  routes: /"
done
(cd "$HERE/py" && PYTHONIOENCODING=utf-8 python cross.py stitch "$OUT" > "$OUT/cross.log" 2>&1; python cross.py draw "$OUT" >> "$OUT/cross.log" 2>&1); grep -E "^CROSS|MISSING" "$OUT/cross.log"
cd "$HERE" && PYTHONIOENCODING=utf-8 python gate.py "$OUT" "$@"
