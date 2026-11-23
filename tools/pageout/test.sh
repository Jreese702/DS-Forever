#!/bin/bash
# Host tests of DS Forever's page-out (dsforever/src/main/jni/dsf/dsf_pageout.h and the preload dsf_pageout_hook.c), Linux x86_64:
#   wsl -e bash tools/pageout/test.sh
# 1) the core's unit tests (maps, regions, the never-the-game check, requests, a real page-out of its own memory);
# 2) the preload in stand-in processes named as Wine shows its helpers and the game: only a helper answers rc=0, the game never
#    (in fact it installs nothing), a plain SIGURG or one with another value is ignored (no answer), a process without the preload ignores SIGURG and lives on.
# 3) the game client's cold-only mode (test_game.c: simulated memory; then stand-ins with DSF_PAGEOUT_GAME_MODE=1): the client answers
#    the game request, refuses the helpers' one; Battle.net, our sign-in bridge and explorer starting the game still install nothing;
#    a helper refuses a game request; without the game mode the client installs nothing (as before).
set -u
pids=""
repo=$(cd "$(dirname "$0")/../.." && pwd)
out=$(mktemp -d)
trap 'kill $pids 2>/dev/null || true; rm -rf "$out"' EXIT
src="$repo/dsforever/src/main/jni/dsf"
cc -O2 -Wall -Wextra -o "$out/unit" "$repo/tools/pageout/test_pageout.c"
"$out/unit" || exit 1
cc -O2 -Wall -Wextra -o "$out/unitg" "$repo/tools/pageout/test_game.c"
"$out/unitg" || exit 1
cc -O2 -Wall -Wextra -fPIC -shared -DPO_HOST_TEST=1 -fvisibility=hidden -o "$out/libdsf-pageout.so" "$src/dsf_pageout_hook.c"
cc -O2 -o "$out/child" "$repo/tools/pageout/child.c"
# the game client's stand-in runs as a file named like the client, so its comm is the client's (as Wine sets it)
cp "$out/child" "$out/Wow-arm64.exe"; cp "$out/child" "$out/Battle.net.exe"; cp "$out/child" "$out/wow-session-bri"
cc -O2 -o "$out/sendreq" "$repo/tools/pageout/sendreq.c"
dir="$out/pageout"; mkdir -p "$dir"
fail=0
# start <var> <argv0> [preload yes|no]: the stand-in's pid into <var>
start() {
    if [ "${3:-yes}" = yes ]; then
        DSF_PAGEOUT_DIR="$dir" DSF_PAGEOUT_GAME="Wow-arm64.exe:WowB-ARM64.exe" LD_PRELOAD="$out/libdsf-pageout.so"             bash -c 'exec -a "$0" "$1"' "$2" "$out/child" </dev/null >/dev/null 2>&1 &
    else
        bash -c 'exec -a "$0" "$1"' "$2" "$out/child" </dev/null >/dev/null 2>&1 &
    fi
    eval "$1=$!"; pids="$pids $!"
}
# gstart <var> <argv0> <binary> [args...]: a stand-in with the game mode on
gstart() {
    v=$1; a0=$2; bin=$3; shift 3
    DSF_PAGEOUT_DIR="$dir" DSF_PAGEOUT_GAME="Wow-arm64.exe:WowB-ARM64.exe" DSF_PAGEOUT_GAME_MODE=1 LD_PRELOAD="$out/libdsf-pageout.so"         bash -c 'a0=$1; bin=$2; shift 2; exec -a "$a0" "$bin" "$@"' x "$a0" "$bin" "$@" </dev/null >/dev/null 2>&1 &
    eval "$v=$!"; pids="$pids $!"
}
gask() { # seq op pid [mode]
    printf 'DSFPO2 %s %s 32768
' "$1" "$2" > "$dir/greq.tmp" && mv "$dir/greq.tmp" "$dir/greq"
    "$out/sendreq" "$3" ${4:-game}
}
gexpect() { # pid rc|none [op]
    f="$dir/gres-$1"
    i=0; while [ ! -f "$f" ] && [ $i -lt 50 ]; do sleep 0.1; i=$((i+1)); done
    case "$2" in
        none) if [ -f "$f" ]; then echo "FAIL: pid $1 answered the game request: $(cat "$f")"; fail=1; else echo "ok: pid $1 no game answer"; fi ;;
        *) if [ -f "$f" ] && grep -q " rc=$2 op=${3:-.}" "$f"; then echo "ok: pid $1 $(cat "$f")"; else echo "FAIL: pid $1 wanted rc=$2 op=${3:-}: $(cat "$f" 2>/dev/null)"; fail=1; fi ;;
    esac
    rm -f "$f"
}
ask() { # seq pid [mode]
    printf 'DSFPO1 %s 32768\n' "$1" > "$dir/req.tmp" && mv "$dir/req.tmp" "$dir/req"
    "$out/sendreq" "$2" ${3:-}
}
expect() { # pid what
    f="$dir/res-$1"
    i=0; while [ ! -f "$f" ] && [ $i -lt 50 ]; do sleep 0.1; i=$((i+1)); done
    case "$2" in
        none) if [ -f "$f" ]; then echo "FAIL: pid $1 answered: $(cat "$f")"; fail=1; else echo "ok: pid $1 no answer"; fi ;;
        *) if [ -f "$f" ] && grep -q " rc=$2 " "$f"; then echo "ok: pid $1 $(cat "$f")"; else echo "FAIL: pid $1 wanted rc=$2: $(cat "$f" 2>/dev/null)"; fail=1; fi ;;
    esac
    rm -f "$f"
}
start svc 'C:\windows\system32\services.exe'
start dev 'C:\windows\system32\winedevice.exe'
start game 'G:\_forever_\Wow-arm64.exe'
start srv '/opt/wine/bin/wineserver'
start bare 'C:\windows\system32\plugplay.exe' no
sleep 0.5
ask 1 "$svc";  expect "$svc" 0
ask 2 "$dev";  expect "$dev" 0
ask 3 "$game"; expect "$game" none           # the game: no handler at all, SIGURG ignored
ask 4 "$srv";  expect "$srv" 1
ask 5 "$svc" plain; expect "$svc" none
ask 6 "$svc" wrong; expect "$svc" none
ask 6 "$svc";  expect "$svc" 0
"$out/sendreq" "$svc"; expect "$svc" none             # the same request again: one answer only
ask 7 "$bare"; expect "$bare" none
# the handler: in the helper, never in the game's process (the library stays inert there)
cgt() { awk '/^SigCgt:/ {print $2}' "/proc/$1/status"; }
urg() { [ $(( 0x$(cgt "$1") >> 22 & 1 )) = 1 ]; }
if urg "$svc"; then echo "ok: helper catches SIGURG"; else echo "FAIL: helper has no handler"; fail=1; fi
if urg "$game"; then echo "FAIL: the game's process has a handler"; fail=1; else echo "ok: the game's process has none"; fi
# THE GAME MODE ON (the user's pick "Yes, carefully"): the client only, cold-only; everything else that names the game: nothing
gstart gcl 'G:\_forever_\Wow-arm64.exe' "$out/Wow-arm64.exe" -launcherlogin
gstart gbn 'C:\Program Files (x86)\Battle.net\Battle.net.exe' "$out/Battle.net.exe"
gstart gbr 'G:\_forever_\wow-session-bridge.exe' "$out/wow-session-bri" --port 1
gstart gex 'C:\windows\explorer.exe' "$out/child" '/desktop=shell,1920x1080' 'G:\_forever_\Wow-arm64.exe'
gstart gsv 'C:\windows\system32\services.exe' "$out/child"
sleep 0.5
if urg "$gcl"; then echo "ok: the game client catches SIGURG (game mode)"; else echo "FAIL: the game client has no handler"; fail=1; fi
for p in $gbn $gbr $gex; do
    if urg "$p"; then echo "FAIL: pid $p (names the game, not the client) has a handler"; fail=1; else echo "ok: pid $p has none"; fi
done
gask 20 c "$gcl"; gexpect "$gcl" 7 c                  # a commit with no pass started: nothing done
gask 21 P "$gcl"; gexpect "$gcl" 0 P
gask 22 p "$gcl"; gexpect "$gcl" 0 p
gask 23 c "$gcl" gamethread; gexpect "$gcl" 0 c       # to its thread, as the app sends it
gask 24 k "$gcl"; gexpect "$gcl" 0 k
"$out/sendreq" "$gcl" game; gexpect "$gcl" none       # the same request again: one answer only
gask 25 P "$gcl" wrong; gexpect "$gcl" none           # another value: ignored
ask 26 "$gcl"; expect "$gcl" 2                        # the helpers' request: refused in the game, as before
gask 27 P "$gsv"; gexpect "$gsv" 6 P                  # a helper never runs the game's pass
ask 28 "$gsv"; expect "$gsv" 0                        # and is a helper as before
gask 29 P "$game"; gexpect "$game" none               # the game mode off: the client has no handler at all
for p in $svc $dev $game $srv $bare $gcl $gbn $gbr $gex $gsv; do
    if kill -0 "$p" 2>/dev/null; then echo "ok: pid $p alive"; else echo "FAIL: pid $p died"; fail=1; fi
done
[ $fail = 0 ] && echo "pageout host tests: all passed" || { echo "pageout host tests: FAILED"; exit 1; }
