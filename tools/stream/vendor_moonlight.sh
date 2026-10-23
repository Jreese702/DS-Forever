#!/usr/bin/env bash
# Vendors Moonlight's streaming core into dsforever/src/main/jni (Streaming mode, S2).
#
# Source: shallow clones of the two OFFICIAL upstream repos (and moonlight-common-c's own submodules), kept OUTSIDE the worktree:
#   E:/stream-src/moonlight-common-c   https://github.com/moonlight-stream/moonlight-common-c  @ 874ac9548f1bd6f095ef2b435c42cdde460e7821
#       enet   (submodule) https://github.com/cgutman/enet          @ aca87840b57f045a1f7f9299e4b1b9b8e2a5e2f1
#       nanors (submodule) https://github.com/sleepybishop/nanors   @ b1e3c22ca0cdc0bb83e3cd6ed1a2fc77869ed99a
#   E:/stream-src/moonlight-android    https://github.com/moonlight-stream/moonlight-android    @ b48494cb96bff23d8886c4775cc4f39a1075495d
#       (pins moonlight-common-c at 874ac954 above: the same commit is vendored, so its proven JNI glue matches)
#
# What lands where:
#   dsforever/src/main/jni/moonlight-common-c/   the C sources + licences (COMMITTED, GPL-3.0; enet MIT; nanors MIT)
#   dsforever/src/main/jni/prebuilt/             moonlight-android's prebuilt arm64-v8a libcrypto.a / libopus.a + headers
#                                                (GIT-IGNORED build inputs, ~14 MB; OpenSSL Apache-2.0, Opus BSD-3-Clause)
#
# Usage: tools/stream/vendor_moonlight.sh [stream-src dir]   (default E:/stream-src). Copies only; never moves or deletes the sources.
set -euo pipefail
SRC="${1:-/e/stream-src}"
HERE="$(cd "$(dirname "$0")/../.." && pwd)"
JNI="$HERE/dsforever/src/main/jni"
MC="$SRC/moonlight-common-c"
MA="$SRC/moonlight-android/app/src/main/jni/moonlight-core"

want() { local dir="$1" sha="$2"; local got; got="$(git -C "$dir" rev-parse HEAD)"; [ "$got" = "$sha" ] || { echo "$dir is at $got, expected $sha" >&2; exit 2; }; }
want "$MC" 874ac9548f1bd6f095ef2b435c42cdde460e7821
want "$MC/enet" aca87840b57f045a1f7f9299e4b1b9b8e2a5e2f1
want "$MC/nanors" b1e3c22ca0cdc0bb83e3cd6ed1a2fc77869ed99a
want "$SRC/moonlight-android" b48494cb96bff23d8886c4775cc4f39a1075495d

# ---- the C sources (committed)
OUT="$JNI/moonlight-common-c"
rm -rf "$OUT"; mkdir -p "$OUT/src" "$OUT/enet/include/enet" "$OUT/nanors/deps/obl"
cp "$MC/LICENSE.txt" "$MC/README.md" "$OUT/"
cp "$MC"/src/*.c "$MC"/src/*.h "$OUT/src/"
for f in callbacks.c compress.c host.c list.c packet.c peer.c protocol.c unix.c win32.c LICENSE README; do cp "$MC/enet/$f" "$OUT/enet/"; done
cp "$MC"/enet/include/enet/*.h "$OUT/enet/include/enet/"
cp "$MC/nanors/rs.c" "$MC/nanors/rs.h" "$MC/nanors/LICENSE" "$MC/nanors/README.md" "$OUT/nanors/"
cp "$MC"/nanors/deps/obl/oblas_common.c "$MC"/nanors/deps/obl/oblas_lite.c "$MC"/nanors/deps/obl/*.h "$OUT/nanors/deps/obl/"
cat > "$OUT/UPSTREAM" <<EOF
moonlight-common-c https://github.com/moonlight-stream/moonlight-common-c 874ac9548f1bd6f095ef2b435c42cdde460e7821 (GPL-3.0)
enet               https://github.com/cgutman/enet                       aca87840b57f045a1f7f9299e4b1b9b8e2a5e2f1 (MIT)
nanors             https://github.com/sleepybishop/nanors                b1e3c22ca0cdc0bb83e3cd6ed1a2fc77869ed99a (MIT)
Copied unmodified by tools/stream/vendor_moonlight.sh (only the files the Android build needs).
EOF

# ---- the prebuilt crypto + audio libraries (git-ignored)
PB="$JNI/prebuilt"
rm -rf "$PB"; mkdir -p "$PB/openssl/arm64-v8a" "$PB/opus/arm64-v8a"
cp "$MA/openssl/arm64-v8a/libcrypto.a" "$PB/openssl/arm64-v8a/"
cp -r "$MA/openssl/include" "$PB/openssl/"
cp "$MA/opus/arm64-v8a/libopus.a" "$PB/opus/arm64-v8a/"
cp -r "$MA/opus/include" "$PB/opus/"
cp "$MA/Build.txt" "$PB/Build.txt"
echo "moonlight-android b48494cb96bff23d8886c4775cc4f39a1075495d app/src/main/jni/moonlight-core/{openssl,opus} (built by https://github.com/cgutman/moonlight-mobile-deps)" > "$PB/UPSTREAM"
echo "vendored into $JNI"
