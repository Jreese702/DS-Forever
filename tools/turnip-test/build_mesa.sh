#!/bin/bash
# THE OPT-IN TEST DRIVER turnip-test-mesa-26.3.1.zip (2026-11-23; GraphicsDriver.kt, SOURCE_PROVENANCE.md): Turnip (freedreno Vulkan,
# KGSL) cross-compiled from the OFFICIAL Mesa 26.3.1 release tarball for Android arm64 with ONE local change, the vk_icd export patch
# (0001-android-export-vk_icd-entrypoints.diff, applied by mkpatch.py). The IR3 scheduler fix is upstream since 26.3.0 (2f8dc9eaba06).
# -Dandroid-strict=false (2026-11-24): with it on (Mesa's default) an API 29 build offers only the device extensions Mesa's Android
# allowlist permits at API 29 (vk_android_allowed_device_extensions); VK_KHR_load_store_op_none is allowed only from API 35, so the
# driver hid it although Turnip supports it, and DXVK 3.x (which requires it) refused the GPU: "Your 3D accelerator card is not
# supported" (its log: "Device does not support required feature 'khrLoadStoreOpNone'"). Same tarball, patch and NDK otherwise.
#
# Run in WSL (Ubuntu) with an Android NDK r27c and meson / ninja / mako:
#   MESA_TARBALL=/path/mesa-26.3.1.tar.xz NDK=/root/ndk W=/root/dsf-1123 bash tools/turnip-test/build_mesa.sh
# then, on Windows, package it (flat zip, meta.json, Mesa's licence texts):
#   python -I tools/turnip-test/pack.py <the folder with libvulkan_freedreno.so, the diff and licenses/> <out dir>
# The tarball must be https://archive.mesa3d.org/mesa-26.3.1.tar.xz with sha256
# 4e4e438eac7b840a9383988cbb7c08dad8041da544b9319fc26d319fa7348961 (the 26.3.1 release notes' value). Meson must fetch NOTHING
# (--wrap-mode=nofallback; a first run without it pulled expat / libarchive / libxml2 wraps that were never linked).
set -eo pipefail
: "${MESA_TARBALL:?set MESA_TARBALL to the verified mesa-26.3.1.tar.xz}"
W=${W:-/root/dsf-1123}
NDK=${NDK:-/root/ndk}
S=$(cd "$(dirname "$0")" && pwd)
API=29
TC=$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin
SRC=$W/mesa-26.3.1
BUILD=$W/build
PREFIX=$W/install
LOG=$W/build.log

echo "4e4e438eac7b840a9383988cbb7c08dad8041da544b9319fc26d319fa7348961  $MESA_TARBALL" | sha256sum -c -
mkdir -p "$W"
[ -d "$SRC" ] || tar -xf "$MESA_TARBALL" -C "$W"
grep -q "baryf->block != instr->block" "$SRC/src/freedreno/ir3/ir3_sched.c" || { echo "the upstream scheduler fix is missing"; exit 1; }

cd "$W"
if [ ! -f "$W/patched.stamp" ]; then
  mkdir -p "$W/pristine/src/vulkan"
  cp "$SRC/src/vulkan/vulkan-android.sym" "$SRC/src/vulkan/vulkan-icd-android-symbols.txt" "$W/pristine/src/vulkan/"
  python3 -I "$S/mkpatch.py" "$SRC"
  ( cd "$W"; for f in vulkan-android.sym vulkan-icd-android-symbols.txt; do
      diff -u --label "a/src/vulkan/$f" --label "b/src/vulkan/$f" "pristine/src/vulkan/$f" "mesa-26.3.1/src/vulkan/$f" || true
    done ) > "$W/0001-android-export-vk_icd-entrypoints.diff"
  touch "$W/patched.stamp"
fi
cmp "$W/0001-android-export-vk_icd-entrypoints.diff" "$S/0001-android-export-vk_icd-entrypoints.diff"

cat > "$W/android-aarch64.ini" <<EOF
[binaries]
ar = '$TC/llvm-ar'
c = ['$TC/aarch64-linux-android$API-clang']
cpp = ['$TC/aarch64-linux-android$API-clang++', '-fno-exceptions', '-fno-unwind-tables', '-fno-asynchronous-unwind-tables', '--start-no-unused-arguments', '-static-libstdc++', '--end-no-unused-arguments']
c_ld = 'lld'
cpp_ld = 'lld'
strip = '$TC/llvm-strip'
pkg-config = ['env', 'PKG_CONFIG_LIBDIR=$W/nopc', '/usr/bin/pkg-config']

[host_machine]
system = 'android'
cpu_family = 'aarch64'
cpu = 'armv8'
endian = 'little'
EOF
mkdir -p "$W/nopc"

rm -rf "$BUILD" "$PREFIX"
cd "$SRC"
# No network fetches during configure: drop any wrap downloads and forbid fallbacks.
rm -rf subprojects/packagecache subprojects/expat-2.5.0 subprojects/libarchive-3.7.2 subprojects/libxml2-2.11.6
meson setup "$BUILD" \
  --wrap-mode=nofallback \
  --cross-file "$W/android-aarch64.ini" \
  --prefix "$PREFIX" \
  -Dbuildtype=release \
  -Db_lto=false \
  -Dstrip=true \
  -Dplatforms=android \
  -Dplatform-sdk-version=$API \
  -Dandroid-stub=true \
  -Dandroid-strict=false \
  -Dandroid-libbacktrace=disabled \
  -Dgallium-drivers= \
  -Dvulkan-drivers=freedreno \
  -Dvulkan-beta=true \
  -Dfreedreno-kmds=kgsl \
  -Dopengl=false \
  -Degl=disabled \
  -Dgles1=disabled \
  -Dgles2=disabled \
  -Dglx=disabled \
  -Dgbm=disabled \
  -Dllvm=disabled \
  -Dxmlconfig=disabled \
  -Dzstd=disabled \
  -Dvalgrind=disabled \
  -Dlibunwind=disabled \
  -Dbuild-tests=false \
  -Dwerror=false \
  > "$W/setup.log" 2>&1 || { tail -40 "$W/setup.log"; exit 1; }
tail -60 "$W/setup.log"
ninja -C "$BUILD" -j"$(nproc)" install > "$LOG" 2>&1 || { grep -nE "error:|FAILED" "$LOG" | head -30; exit 1; }
tail -3 "$LOG"

# the folder pack.py reads: the library, the diff, Mesa's licence texts (licenses/* as LICENSE-*, docs/license.rst)
OUT=$W/pack-input
rm -rf "$OUT"; mkdir -p "$OUT/licenses"
cp "$PREFIX/lib/libvulkan_freedreno.so" "$W/0001-android-export-vk_icd-entrypoints.diff" "$OUT/"
for f in "$SRC"/licenses/*; do cp "$f" "$OUT/licenses/LICENSE-$(basename "$f")"; done
cp "$SRC/docs/license.rst" "$OUT/licenses/mesa-docs-license.rst"
sha256sum "$OUT/libvulkan_freedreno.so"
echo "pack input: $OUT"
