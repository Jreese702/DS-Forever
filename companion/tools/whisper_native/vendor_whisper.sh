#!/bin/sh
# Vendors whisper.cpp (MIT, "Copyright (c) 2023-2026 The ggml authors") for DS Forever's offline voice typing (libdsfwhisper.so):
# the CPU-only parts of a pinned release checkout, into ./whisper.cpp next to this script. The GPU and other-platform backends,
# the examples, the tests, the bindings and the models are left out; LICENSE is kept, UPSTREAM names the tag and commit.
#   sh companion/tools/whisper_native/vendor_whisper.sh <whisper.cpp checkout at v1.9.5>
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
SRC=${1:?"usage: vendor_whisper.sh <whisper.cpp checkout>"}
TAG=v1.9.5
COMMIT=d1be6fde11ac6e0407606b4e42fe72d34add8037
got=$(git -C "$SRC" rev-parse HEAD)
[ "$got" = "$COMMIT" ] || { echo "the checkout is at $got, not $TAG ($COMMIT)"; exit 1; }
OUT="$HERE/whisper.cpp"
rm -rf "$OUT"
mkdir -p "$OUT/ggml/src"
cp "$SRC/LICENSE" "$SRC/CMakeLists.txt" "$OUT/"
cp -r "$SRC/cmake" "$SRC/include" "$SRC/src" "$OUT/"
rm -rf "$OUT/src/coreml" "$OUT/src/openvino" "$OUT/src/vitisai"
cp "$SRC/ggml/CMakeLists.txt" "$OUT/ggml/"
cp -r "$SRC/ggml/cmake" "$SRC/ggml/include" "$OUT/ggml/"
for f in "$SRC"/ggml/src/*; do [ -f "$f" ] && cp "$f" "$OUT/ggml/src/"; done
cp -r "$SRC/ggml/src/ggml-cpu" "$OUT/ggml/src/"
C="$OUT/ggml/src/ggml-cpu"
rm -rf "$C/kleidiai" "$C/spacemit"
for a in x86 loongarch powerpc riscv s390 wasm; do rm -rf "$C/arch/$a"; done
printf 'whisper.cpp %s\ncommit %s\nhttps://github.com/ggml-org/whisper.cpp\nCPU sources only (vendor_whisper.sh); MIT, see LICENSE\n' "$TAG" "$COMMIT" > "$OUT/UPSTREAM"
du -sh "$OUT"
