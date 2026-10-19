#!/usr/bin/env bash
# THE REGRESSION GATE: every zone spec and the climb tests through the harness on this checkout, then gate.py against the baseline.
#   bash tools/safepath-harness/gate.sh            run and compare (exit 1 on any failure)
#   bash tools/safepath-harness/gate.sh --save     run and make this run the baseline (only the rules owner, after a review)
#   CROSS_ONLY=1 bash tools/safepath-harness/gate.sh   only the cross-zone routes (cross/routes.json), compared as the rest
#   ONE_FACTION=1 bash tools/safepath-harness/gate.sh  each zone for its own spec's faction only (not the other side's run too)
#   SAFEPATH_EXPORT=1 bash tools/safepath-harness/gate.sh  also packs the passing zones' networks (NET1) into the app's assets
# Runs into $SAFEPATH_WORK/gate/run-<time>/ (or $GATE_OUT); one log per zone, gate_<tag>.log. Takes about 30-40 minutes, the
# cross-zone routes 20 more: their legs, one spec per zone they cross (py/cross.py specs), stitched into cross.json.
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
export SAFEPATH_WORK="${SAFEPATH_WORK:-E:/safepath-work}"
OUT="${GATE_OUT:-$SAFEPATH_WORK/gate/run-$(date +%Y%m%d-%H%M%S)}"
mkdir -p "$OUT"
export SAFEPATH_OUT="$OUT"
ZONES="durotar:kalimdor elwynn:azeroth zephras:zephras tirisfal:azeroth teldrassil:kalimdor dunmorogh:azeroth mulgore:kalimdor barrens:kalimdor westfall:azeroth lochmodan:azeroth silverpine:azeroth darkshore:kalimdor climb_barrens:kalimdor climb_mulgore:kalimdor climb_durotar:kalimdor hillsbrad:azeroth duskwood:azeroth redridge:azeroth ashenvale:kalimdor stonetalon:kalimdor wetlands:azeroth alterac:azeroth arathi:azeroth arathi_horde:azeroth dalaran:dalaran blastedlands:azeroth badlands:azeroth thousandneedles:kalimdor swampofsorrows:azeroth deadwind:azeroth shendralas:kalimdor silithus:kalimdor burningsteppes:azeroth searinggorge:azeroth tanaris:kalimdor felwood:kalimdor winterspring:kalimdor dustwallow:kalimdor azshara:kalimdor hinterlands:azeroth wplaguelands:azeroth desolace:kalimdor ungoro:kalimdor stranglethorn:azeroth hyjal:kalimdor moonglade:kalimdor riverglades:azeroth eplaguelands:azeroth feralas:kalimdor stormwind:azeroth"
# EVERY ZONE FOR BOTH FACTIONS (the user: "danger maps are going to change faction to faction"): each zone's spec for the other side too,
# its own tag (<tag>_<faction>), compared against its own baseline; a zone with its own spec for that side (arathi_horde) keeps that
OTHER=$(cd "$HERE" && PYTHONIOENCODING=utf-8 python - "$OUT" $ZONES <<'PY' | tr -d '\r'
import json, os, sys
out = sys.argv[1]; os.makedirs(os.path.join(out, 'specs'), exist_ok=True)
tags = [z.split(':')[0] for z in sys.argv[2:]]
for z in sys.argv[2:]:
    tag, d = z.split(':')
    if tag.startswith('climb_') or tag.endswith('_horde') or tag.endswith('_alliance'): continue
    s = json.load(open(os.path.join('zones', tag + '.json'), encoding='utf-8'))
    other = 'horde' if s.get('faction', 'horde') == 'alliance' else 'alliance'
    if tag + '_' + other in tags: continue
    s['faction'] = other; s['tag'] = tag + '_' + other; s.pop('closeups', None)
    json.dump(s, open(os.path.join(out, 'specs', s['tag'] + '.json'), 'w', encoding='utf-8'), ensure_ascii=False)
    print('%s:%s' % (s['tag'], d))
PY
)
[ -n "${CROSS_ONLY:-}" ] && ZONES="" && OTHER=""
[ -n "${ONE_FACTION:-}" ] && OTHER=""
# the cross-zone routes' legs: each zone they cross, its own spec with the legs for goals
CROSS=$(cd "$HERE/py" && PYTHONIOENCODING=utf-8 python cross.py specs "$OUT" | tr -d '\r')
for z in $ZONES $OTHER $CROSS; do
  tag=${z%%:*}; dir=${z##*:}
  spec="$HERE/zones/$tag.json"; [ -f "$spec" ] || spec="$OUT/specs/$tag.json"
  echo "=== $tag"
  bash "$HERE/run.sh" "$spec" "ROUTE2|WALLS|EMPTY|GOAL|WATERMAX|SWIM|WARNING WMO|DETOUR|WADE route|DANGER typical|EXIT |EXITS added|BORDER crossing|ROADWALLS|DROPWALLED|SAFEPATH [A-Z][a-z' ]+:|^e:|Exception" > "$OUT/gate_$tag.log" 2>&1
  (cd "$HERE/py" && PYTHONIOENCODING=utf-8 ZDIR=$dir python segcheck.py "$OUT/levels_routes_$tag.json" 2>&1 | tail -1) >> "$OUT/gate_$tag.log"
  # the water, read apart from the app's grid: every yard of every route over the liquid's own surface and the terrain (py/wetcheck.py)
  (cd "$HERE/py" && PYTHONIOENCODING=utf-8 ZDIR=$dir python wetcheck.py "$OUT/levels_routes_$tag.json" 2>&1 | grep -E "^WET|Error") >> "$OUT/gate_$tag.log"
  grep -cE "^ROUTE2" "$OUT/gate_$tag.log" | sed "s/^/  routes: /"
done
(cd "$HERE/py" && PYTHONIOENCODING=utf-8 python cross.py stitch "$OUT" > "$OUT/cross.log" 2>&1; python cross.py draw "$OUT" >> "$OUT/cross.log" 2>&1); grep -E "^CROSS|MISSING" "$OUT/cross.log"
# the cross-continent planner's checks (cross/plans.json over cross/links.json: one-way portals, rides, flights)
CROSS_DIR="$HERE/cross" SAFEPATH_TEST=PlanCross bash "$HERE/run.sh" "" "PLAN |^e:|Exception" > "$OUT/plan.log" 2>&1; grep -E "^PLAN" "$OUT/plan.log" | cut -c1-200
# SAFEPATH_EXPORT=1: the runs above also wrote each zone's NET1 section (run.sh); the zones that pass their own checks are packed
# into the app's assets (export.sh --pack: py/exportcheck.py, then PackNet)
[ "${SAFEPATH_EXPORT:-}" = "1" ] && bash "$HERE/export.sh" --pack "$OUT"
cd "$HERE" && PYTHONIOENCODING=utf-8 python gate.py "$OUT" "$@"
