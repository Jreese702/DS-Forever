#!/usr/bin/env bash
# one zone through the harness: builds the app's classes, runs RenderSafePath on the zone's spec, prints the lines matching $2
#   bash tools/safepath-harness/run.sh tools/safepath-harness/zones/elwynn.json "ROUTE2|EXIT|CAVE|WALLS|GOAL"
# needs: SAFEPATH_WORK (default E:/safepath-work), QDBTOC (QuestieDB_Forever.toc), the Android SDK (ANDROID_HOME)
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"; REPO="$(cd "$HERE/../.." && pwd)"
export SAFEPATH_WORK="${SAFEPATH_WORK:-E:/safepath-work}"
export QDBTOC="${QDBTOC:-E:/wow-build/questiedb/unz/QuestieDB/QuestieDB_Forever.toc}"
export RECIPE="${RECIPE:-$REPO/companion/app/src/main/assets/harvest/worldmap.json}"
export ANDROID_HOME="${ANDROID_HOME:-$(cygpath -w "$LOCALAPPDATA/Android/Sdk" 2>/dev/null || echo "$LOCALAPPDATA/Android/Sdk")}"
# SAFEPATH_EXPORT=1: RenderSafePath also writes the zone's network as one faction's NET1 section (<out>/net1_<tag>.json; ShippedNet),
# stamped with this checkout's commit (SAFEPATH_RULES_COMMIT, "-dirty" with uncommitted app or harness sources) and the game build THE
# DUMPS were read from: the BUILD.txt (build_key=<hex>, version=<x.y.z.n>) the re-dump writes into each dump folder - the zone's own
# (<work>/<spec dir>) and the tables' (<work>/db2, or DB2_DIR). No BUILD.txt, or two folders of other builds: no export (exit 3).
# SAFEPATH_BUILD=<key> overrides it (say so in the commit). Never the live client's .build.info: it moves on with the launcher's update
# while the dumps stay as they were (the 70374 -> 70450 update, 2026-10-19). export.sh gates and packs the sections into the assets
build_of() {   # $1 a dump folder: its BUILD.txt's "key version", or nothing
  [ -f "$1/BUILD.txt" ] || return 0
  k=$(grep -iE '^[[:space:]]*build_?key[[:space:]]*[=:]' "$1/BUILD.txt" | head -1 | sed -E 's/^[^=:]*[=:][[:space:]]*//' | tr -d '\r[:space:]' | tr 'A-F' 'a-f')
  v=$(grep -iE '^[[:space:]]*version[[:space:]]*[=:]' "$1/BUILD.txt" | head -1 | sed -E 's/^[^=:]*[=:][[:space:]]*//' | tr -d '\r[:space:]')
  echo "$k $v"
}
if [ "${SAFEPATH_EXPORT:-}" = "1" ]; then
  if [ -z "${SAFEPATH_RULES_COMMIT:-}" ]; then
    SAFEPATH_RULES_COMMIT="$(git -C "$REPO" rev-parse HEAD)"
    [ -n "$(git -C "$REPO" status --porcelain -- companion/app/src/main/java tools/safepath-harness)" ] && SAFEPATH_RULES_COMMIT="$SAFEPATH_RULES_COMMIT-dirty"
    export SAFEPATH_RULES_COMMIT
  fi
  if [ -n "$1" ]; then
    if [ -n "${SAFEPATH_BUILD:-}" ]; then echo "NET1 STAMP $SAFEPATH_BUILD (SAFEPATH_BUILD, an override: the dumps' BUILD.txt not read)"
    else
      DUMPDIR="$(PYTHONIOENCODING=utf-8 python -c "import json,sys; print(json.load(open(sys.argv[1], encoding='utf-8')).get('dir', 'kalimdor'))" "$1" | tr -d '\r')"
      STAMP=""; STAMPV=""
      for d in "$SAFEPATH_WORK/$DUMPDIR" "${DB2_DIR:-$SAFEPATH_WORK/db2}"; do
        read -r k v <<< "$(build_of "$d")"
        if ! echo "$k" | grep -qE '^[0-9a-f]{32}$'; then echo "NET1 REFUSED: $d has no BUILD.txt with its build_key (the dumps' build is not known): no export"; exit 3; fi
        if [ -n "$STAMP" ] && [ "$k" != "$STAMP" ]; then echo "NET1 REFUSED: $d is build $k ($v), another folder of this zone is $STAMP ($STAMPV): no export"; exit 3; fi
        STAMP="$k"; STAMPV="$v"
      done
      export SAFEPATH_BUILD="$STAMP"
      echo "NET1 STAMP $STAMP ($STAMPV) from the dumps' BUILD.txt ($SAFEPATH_WORK/$DUMPDIR, ${DB2_DIR:-$SAFEPATH_WORK/db2})"
    fi
  fi
fi
if [ -n "$1" ]; then export ZONESPEC="$(cd "$(dirname "$1")" && pwd)/$(basename "$1")"; else unset ZONESPEC; fi
# the app's own classes (the dsforever module compiles the companion's sources), as a jar
(cd "$REPO" && ./gradlew --offline -q :dsforever:compileDebugKotlin) 2>&1 | grep -E "^e: " || true
# kept in THIS checkout's own build folder: several agents share the work folder, each with its own branch's classes
CLS="$HERE/robo/build/appclasses"; rm -rf "$CLS"; mkdir -p "$CLS/com/jrees"
cp -r "$REPO/dsforever/build/tmp/kotlin-classes/debug/com/jrees/wowcompanion" "$CLS/com/jrees/"
(cd "$CLS" && jar cf "$HERE/robo/build/app.jar" .)
mkdir -p "${SAFEPATH_OUT:-$SAFEPATH_WORK/out}"
# SAFEPATH_TEST: another of the harness's tests (PlanCross: the cross-continent planner's checks)
T="${SAFEPATH_TEST:-RenderSafePath}"
(cd "$REPO" && ./gradlew -p "$HERE/robo" --offline -q test --tests "$T" --rerun-tasks) 2>&1 | grep -E "^e:|FAIL|Exception" | head -20 || true
PAT="${2:-ROUTE2|EXIT|CAVE|WALLS|GOAL|EMPTY}"; [ "${SAFEPATH_EXPORT:-}" = "1" ] && PAT="$PAT|^NET1"
# (an export's lines are all kept: its checks' verdicts come late, after a zone's many JOINS / APART lines)
if [ "${SAFEPATH_EXPORT:-}" = "1" ]; then grep -E "$PAT" "$HERE/robo/build/test-results/test/TEST-$T.xml" || true
else grep -E "$PAT" "$HERE/robo/build/test-results/test/TEST-$T.xml" | head -80 || true; fi
