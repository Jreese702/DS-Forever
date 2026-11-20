#!/usr/bin/env sh
# Host test for the DNS shim's host-name overrides. Linux/glibc or WSL (on Windows: wsl -e sh tools/dns-shim/test.sh).
# Builds app/src/main/cpp/gamenative_dns_v4mapped.c for the host and LD_PRELOADs it into test_hostname.c.
# The Android .so itself is built with the NDK; see the shim's commit message for the exact command.
set -eu
here=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
repo=$(CDPATH= cd -- "$here/../.." && pwd)
out=$(mktemp -d)
trap 'rm -rf "$out"' EXIT
cc -shared -fPIC -O2 -Wall -o "$out/shim.so" "$repo/app/src/main/cpp/gamenative_dns_v4mapped.c" -ldl -lpthread
cc -O2 -Wall -o "$out/probe" "$here/test_hostname.c"
WOWFOREVER_HOSTNAME=DESKTOP-DSFTEST1 LD_PRELOAD="$out/shim.so" "$out/probe" 2>/dev/null
LD_PRELOAD="$out/shim.so" "$out/probe" 2>/dev/null
# A name that fails validation (a dot) is ignored: libc's answers.
WOWFOREVER_HOSTNAME=bad.name LD_PRELOAD="$out/shim.so" "$out/probe" >/dev/null 2>&1 && { echo "FAIL: invalid name was used"; exit 1; } || true
echo "dns-shim host test: OK"
