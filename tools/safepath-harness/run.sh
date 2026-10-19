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
grep -E "${2:-ROUTE2|EXIT|CAVE|WALLS|GOAL|EMPTY}" "$HERE/robo/build/test-results/test/TEST-$T.xml" | head -80 || true
