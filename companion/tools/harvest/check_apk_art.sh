#!/bin/sh
# THE ZERO-ART CHECK (the art harvest, 2026-10-09): an APK may carry NO game-derived art or data. Lists every picture / data file in it and
# fails on anything that is not on the allow-list of OUR files (the app's own icons, the add-on Lua and its D icon, GameNative's control
# icons, the runtime components) or that matches a known game-derived path. Run on the APK a release would ship:
#   sh tools/harvest/check_apk_art.sh <app.apk>
set -e
APK=${1:?apk}
# Resource filenames may be shortened by AAPT; inspect the resource table too.
AAPT2=${AAPT2:-$(command -v aapt2 || true)}
if [ -z "$AAPT2" ]; then
    SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-${LOCALAPPDATA:-}/Android/Sdk}}
    AAPT2="$SDK/build-tools/36.1.0/aapt2.exe"
fi
if [ ! -f "$AAPT2" ]; then echo "FAIL: aapt2 required to check forbidden game-art resources"; exit 1; fi
if ! RESOURCES=$("$AAPT2" dump resources "$APK"); then echo "FAIL: could not inspect APK resource table"; exit 1; fi
if printf '%s\n' "$RESOURCES" | grep -q -E '[[:space:]]drawable/wf_logo([[:space:]]|$)'; then
    echo "FAIL: forbidden game-art resource drawable/wf_logo (user ruling 2026-10-12)"
    exit 1
fi
LIST=$(unzip -l "$APK" | awk 'NR>3 {print $4}' | grep -v '^$' | grep -v '/$')
# known game-derived outputs of the PC harvest (they must come from the device harvest now)
BAD=$(printf '%s\n' "$LIST" | grep -E '^assets/(maps|interiors|runtime)/|^assets/ui/poi_icons\.png$|^assets/ui/npc_icons\.(png|json)$|^assets/(quests_[0-9]+|quest_areas|poi_[0-9]+|poi_zones|poi_meta)\.json$|^res/.*/wf_logo\.(png|webp)$|\.blp$|\.(m2|wmo|adt|db2|dbc)$' || true)
# every picture / data asset, minus the allow-list of ours
PICS=$(printf '%s\n' "$LIST" | grep -E '^assets/.*\.(png|webp|jpg|jpeg|tga|ktx|dds|ttf|otf|json|grid|pak)$' \
    | grep -v -E '^assets/companion/addons/[^/]+/.*\.(tga)$' \
    | grep -v -E '^assets/inputcontrols/' \
    | grep -v -E '^assets/harvest/[a-z0-9_]+\.json$|^assets/harvest/(orig|own)/|^assets/own/' \
    | grep -v -E '^assets/ui/npc_kinds\.json$' \
    | grep -v -E '^assets/[a-z_]+_download\.json$|^assets/(common_dlls|gpu_cards)\.json$' \
    | grep -v -E '^assets/(lsfg_vk|wincomponents)/' || true)          # GameNative's runtime component manifests
# what is already listed as game-derived is not repeated
[ -n "$BAD" ] && PICS=$(printf '%s\n' "$PICS" | grep -v -x -F "$BAD" || true)
echo "APK: $APK"
echo "assets: $(printf '%s\n' "$LIST" | grep -c '^assets/') files; recipes: $(printf '%s\n' "$LIST" | grep -c '^assets/harvest/')"
FAIL=0
if [ -n "$BAD" ]; then echo "GAME-DERIVED (must not ship):"; printf '%s\n' "$BAD" | sed 's#/[^/]*$#/…#' | sort | uniq -c | head -20; FAIL=1; fi
if [ -n "$PICS" ]; then echo "NOT ON THE ALLOW-LIST (check each, then allow it here if it is ours):"; printf '%s\n' "$PICS" | head -30; FAIL=1; fi
# the add-on's own Media: our D icon, the compass band and the world markers (our own art: tools/make_compass_band.py, tools/make_world_markers.py)
MEDIA=$(printf '%s\n' "$LIST" | grep -E '^assets/companion/addons/[^/]+/Media/' | grep -vE '(ds_forever_icon|compass_band|marker_ring|marker_beam|tint_glow)\.tga$' || true)
if [ -n "$MEDIA" ]; then echo "ADD-ON MEDIA other than ours:"; printf '%s\n' "$MEDIA"; FAIL=1; fi
# OUR OWN required art must BE in the APK (the user on h1: the swirl was missing on a fresh install): tools/harvest/own_art.txt
OWN=$(dirname "$0")/own_art.txt
if [ -f "$OWN" ]; then
    MISS=$(tr -d '\r' < "$OWN" | grep -v '^$' | while read -r f; do printf '%s\n' "$LIST" | grep -q -x -F "$f" || echo "$f"; done)
    if [ -n "$MISS" ]; then echo "OUR OWN ART MISSING from the APK:"; printf '%s\n' "$MISS"; FAIL=1
    else echo "own art: $(tr -d '\r' < "$OWN" | grep -c -v '^$') required pictures present"; fi
fi
# the launcher's own pictures: upstream's Blizzard marketing art, not game files (the user 2026-10-09: "Decide later": kept for the
# private test, decide before any public release). Listed, never failed on.
DEF=$(printf '%s\n' "$LIST" | grep -E '^res/.*/(ic_banner|wallpaper)\.(png|webp)$' || true)
[ -n "$DEF" ] && { echo "DEFERRED (decide before any public release): $(printf '%s\n' "$DEF" | sed 's#.*/##' | sort -u | tr '\n' ' ')"; }
if [ $FAIL = 0 ]; then echo "PASS: no game-derived art or data in the APK"; else echo "FAIL"; exit 1; fi
