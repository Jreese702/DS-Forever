#!/bin/bash
# Host tests of DS Forever's page-out (dsforever/src/main/jni/dsf/dsf_pageout.h and the preload dsf_pageout_hook.c), Linux x86_64:
#   wsl -e bash tools/pageout/test.sh
# 1) the core's unit tests (maps, regions, the never-the-game check, requests, a real page-out of its own memory);
# 2) the preload in stand-in processes named as Wine shows its helpers and the game: only a helper answers rc=0, the game never
#    (in fact it installs nothing), a plain SIGURG or one with another value is ignored (no answer), a process without the preload ignores SIGURG and lives on.
set -u
pids=""
repo=$(cd "$(dirname "$0")/../.." && pwd)
out=$(mktemp -d)
trap 'kill $pids 2>/dev/null || true; rm -rf "$out"' EXIT
src="$repo/dsforever/src/main/jni/dsf"
cc -O2 -Wall -Wextra -o "$out/unit" "$repo/tools/pageout/test_pageout.c"
"$out/unit"
cc -O2 -Wall -Wextra -fPIC -shared -DPO_HOST_TEST=1 -fvisibility=hidden -o "$out/libdsf-pageout.so" "$src/dsf_pageout_hook.c"
cc -O2 -o "$out/child" "$repo/tools/pageout/child.c"
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
for p in $svc $dev $game $srv $bare; do
    if kill -0 "$p" 2>/dev/null; then echo "ok: pid $p alive"; else echo "FAIL: pid $p died"; fail=1; fi
done
[ $fail = 0 ] && echo "pageout host tests: all passed" || { echo "pageout host tests: FAILED"; exit 1; }
