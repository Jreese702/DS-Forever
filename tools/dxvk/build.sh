#!/bin/bash
# DXVK 3.1.1 for DS Forever (the Adreno 740 profile's DXVK; WoWForeverScreen.kt DXVK_3), built as plain Windows ARM64 PE DLLs
# (dxgi.dll, d3d11.dll) and packed as app/src/main/assets/bundled_components/dxvk-3.1.1-wow-aarch64.wcp (git-ignored like the
# other bundled components; pinned in tools/components-dxvk.sha256).
#
#   tools/dxvk/build.sh <llvm-mingw dir> <work dir>
#
# Needs: git, meson + ninja, glslangValidator, zstd, and llvm-mingw 20261104 (ucrt, x86_64 Linux host) from
# https://github.com/mstorsjo/llvm-mingw/releases. Built and tested on Linux.
# Source: https://github.com/doitsujin/dxvk tag v3.1.1 (b1a1c99) + dxvk-3.1.1-wow.patch. DXVK is zlib-licensed.
#
# THE PATCH: DXVK 3 creates a MUTABLE-FORMAT swapchain (to draw sRGB views of the swap images). The Winlator wrapper accepts the flag
# but its real swapchain images are not mutable, so Turnip's debug driver aborts at vk_image.c:569 "image_view->format ==
# image->format". The patch skips the mutable swapchain. It also logs one info line: the driver ID and whether DXVK's tiler mode is on.
#
# The tar's times differ run to run, so a rebuild is not byte-identical to the pinned file; the DLLs are.
set -euo pipefail
MINGW=$(readlink -f "$1"); W=$(readlink -f "$2"); HERE=$(cd "$(dirname "$0")" && pwd); ROOT=$(cd "$HERE/../.." && pwd)
OUT=$ROOT/app/src/main/assets/bundled_components/dxvk-3.1.1-wow-aarch64.wcp
mkdir -p "$W" "$(dirname "$OUT")"; cd "$W"
[ -d dxvk-3.1.1 ] || git clone --branch v3.1.1 --depth 1 --recurse-submodules --shallow-submodules https://github.com/doitsujin/dxvk dxvk-3.1.1
git -C dxvk-3.1.1 apply --check "$HERE/dxvk-3.1.1-wow.patch" 2>/dev/null && git -C dxvk-3.1.1 apply "$HERE/dxvk-3.1.1-wow.patch"
sed "s|@LLVM_MINGW@|$MINGW|" "$HERE/aarch64-windows.ini.in" > aarch64-windows.ini
[ -d build-3.1.1 ] || (cd dxvk-3.1.1 && meson setup --cross-file ../aarch64-windows.ini --buildtype release -Db_ndebug=if-release --strip ../build-3.1.1)
ninja -C build-3.1.1
rm -rf pkg; mkdir -p pkg/system32 pkg/licenses
cp build-3.1.1/src/dxgi/dxgi.dll build-3.1.1/src/d3d11/d3d11.dll pkg/system32/
# the archive's notices: DXVK's own licence and the toolchain / dependency notices (the same eight as the 2.4.1 archive)
cp "$ROOT"/licenses/dxvk-3.1.1/licenses/* pkg/licenses/
cp dxvk-3.1.1/LICENSE pkg/licenses/DXVK.txt
cp "$HERE/profile.json" pkg/
tar --owner=0 --group=0 --numeric-owner -C pkg -cf - . | zstd -19 -f -o "$OUT"
sha256sum "$OUT" pkg/system32/*.dll
