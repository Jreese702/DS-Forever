#!/usr/bin/env bash
# THE SHIPPED NETWORK'S EXPORT (NET1; the user, 2026-10-19: "Ship our path network"): zones through the harness with SAFEPATH_EXPORT=1,
# each for BOTH factions (the spec's own side and the other, as gate.sh), checked as the gate checks them (py/exportcheck.py:
# segcheck / wetcheck none, no WALLS / EMPTY / GOAL not found), and the passing sections packed one file a map into the app's assets
# (companion/app/src/main/assets/safepath/net/<map>.bin, or $NET1_ASSETS) by the PackNet test (ShippedNet.write, read back to check).
#   bash tools/safepath-harness/export.sh zephras durotar elwynn      the zones by their spec's tag (zones/<tag>.json)
#   bash tools/safepath-harness/export.sh --list <file>               the tags in a file (one a line, # comments)
#   bash tools/safepath-harness/export.sh --pack <run folder>         pack a finished run's sections (gate.sh with SAFEPATH_EXPORT=1)
#   ONE_FACTION=1 ...                                                 only the spec's own side
# Probes (export_exclude.txt, or a spec's "probe": true) are skipped. Specs sharing a map (alterac and dalaran: 1416) pack into ONE
# file, one section a faction holding their union (PackNet, ShippedNet.merge).
# Runs into $SAFEPATH_OUT/export-<time>/ (or $EXPORT_OUT). A side that fails its checks ships nothing; the other side still ships.
# A map's file holds the sections that passed in THIS run (an earlier asset of that map is replaced).
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"; REPO="$(cd "$HERE/../.." && pwd)"
export SAFEPATH_WORK="${SAFEPATH_WORK:-E:/safepath-work}"
export NET1_ASSETS="${NET1_ASSETS:-$REPO/companion/app/src/main/assets/safepath/net}"
pack() {   # $1 the run folder
  (cd "$HERE/py" && PYTHONIOENCODING=utf-8 python exportcheck.py "$1") | tr -d '\r' | tee "$1/export_check.log"
  IN=$(grep '^EXPORT OK' "$1/export_check.log" | awk '{print $4}' | paste -sd ';' -)
  if [ -z "$IN" ]; then echo "NET1PACK nothing passed: no asset written"; return 1; fi
  NET1_IN="$IN" SAFEPATH_TEST=PackNet bash "$HERE/run.sh" "" "NET1PACK|^e:|Exception" | tr -d '\r' | tee "$1/export_pack.log"
  if grep -q "NET1PACK FAIL" "$1/export_pack.log" || ! grep -q "NET1PACK OK" "$1/export_pack.log"; then return 1; fi
  return 0
}
if [ "${1:-}" = "--pack" ]; then pack "$2"; exit $?; fi
TAGS="$*"; if [ "${1:-}" = "--list" ]; then TAGS=$(grep -v '^[[:space:]]*#' "$2" | tr -d '\r'); fi
OUT="${EXPORT_OUT:-${SAFEPATH_OUT:-$SAFEPATH_WORK/out}/export-$(date +%Y%m%d-%H%M%S)}"; mkdir -p "$OUT"
export SAFEPATH_OUT="$OUT" SAFEPATH_EXPORT=1
# one stamp for the whole export: this checkout's commit ("-dirty" with uncommitted app or harness sources); the game build run.sh reads
if [ -z "${SAFEPATH_RULES_COMMIT:-}" ]; then
  SAFEPATH_RULES_COMMIT="$(git -C "$REPO" rev-parse HEAD)"
  [ -n "$(git -C "$REPO" status --porcelain -- companion/app/src/main/java tools/safepath-harness)" ] && SAFEPATH_RULES_COMMIT="$SAFEPATH_RULES_COMMIT-dirty"
  export SAFEPATH_RULES_COMMIT
fi
for tag in $TAGS; do
  spec="$HERE/zones/$tag.json"; [ -f "$spec" ] || { echo "no spec $spec"; continue; }
  if (cd "$HERE/py" && python exportcheck.py --excluded "$tag"); then echo "skip $tag: a probe (export_exclude.txt)"; continue; fi
  RUNS="$tag:$spec"
  [ -z "${ONE_FACTION:-}" ] && RUNS="$RUNS $(cd "$HERE/py" && PYTHONIOENCODING=utf-8 python otherspec.py "$tag" "$OUT" | tr -d '\r')"
  for r in $RUNS; do
    t=${r%%:*}; sp=${r#*:}
    dir=$(PYTHONIOENCODING=utf-8 python -c "import json,sys; print(json.load(open(sys.argv[1], encoding='utf-8')).get('dir', 'kalimdor'))" "$sp" | tr -d '\r')
    echo "=== $t ($dir)"
    bash "$HERE/run.sh" "$sp" "ROUTE2|WALLS|EMPTY|GOAL|WATERMAX|SWIM|DETOUR|WADE route|EXIT |SAFEPATH [A-Z][a-z' ]+:|^e:|Exception" > "$OUT/gate_$t.log" 2>&1
    (cd "$HERE/py" && PYTHONIOENCODING=utf-8 ZDIR=$dir python segcheck.py "$OUT/levels_routes_$t.json" 2>&1 | tail -1) >> "$OUT/gate_$t.log"
    (cd "$HERE/py" && PYTHONIOENCODING=utf-8 ZDIR=$dir python wetcheck.py "$OUT/levels_routes_$t.json" 2>&1 | grep -E "^WET|Error") >> "$OUT/gate_$t.log"
    grep -E "^NET1 |^WALLS|^EMPTY|segments; crossing|^e:|Exception" "$OUT/gate_$t.log" | cut -c1-300
    # the bulky intermediates go (disk E: is tight): the pictures, the renders' layers; the floors; the routes and the section stay
    rm -f "$OUT"/*.png "$OUT/layers_$t.json" "$OUT/drops_$t.json" "$OUT/decks_$t.json" "$OUT/rooms_$t.json" "$OUT/floors_$t.json" "$OUT/holes_$t.json"
  done
done
pack "$OUT"
