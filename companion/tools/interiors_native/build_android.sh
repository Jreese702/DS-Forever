#!/bin/sh
# Builds the on-device interiors generator, app/src/main/jniLibs/arm64-v8a/libinteriors.so (Track B): this folder's C++ +
# upstream Recast v1.6.0 (pinned 6dc1667f, checked by CMake), NDK clang for arm64, Android 10 / API 29 (the app's minSdk),
# release. Not in git, like libcasc.so: a build without it has no interiors step (IndoorGeneratorNative.available is false).
#   sh tools/interiors_native/build_android.sh [<recastnavigation checkout>] [<build dir>]
# Needs the NDK (27.2.12479018) and the Visual Studio Build Tools' CMake + Ninja (or any CMake >= 3.16 and Ninja on PATH).
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
APP=$(cd "$HERE/../.." && pwd)/app
ROOT=$(cd "$HERE/../../.." && pwd)
RECAST=${1:-$ROOT/.deps/recastnavigation}
BUILD=${2:-$ROOT/.bootstrap/interiors-android}
SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-${LOCALAPPDATA:-}/Android/Sdk}}
NDK=${ANDROID_NDK:-$SDK/ndk/27.2.12479018}
VSCMAKE="C:/Program Files (x86)/Microsoft Visual Studio/2022/BuildTools/Common7/IDE/CommonExtensions/Microsoft/CMake"
CMAKE=${CMAKE:-$(command -v cmake || echo "$VSCMAKE/CMake/bin/cmake.exe")}
NINJA=${NINJA:-$(command -v ninja || echo "$VSCMAKE/Ninja/ninja.exe")}
"$CMAKE" -S "$HERE" -B "$BUILD" -G Ninja -DCMAKE_MAKE_PROGRAM="$NINJA" \
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-29 \
    -DANDROID_STL=c++_static -DCMAKE_BUILD_TYPE=Release -DRECAST_SOURCE="$RECAST"
"$CMAKE" --build "$BUILD" --target interiors
mkdir -p "$APP/src/main/jniLibs/arm64-v8a"
"$NDK/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-strip.exe" --strip-unneeded -o "$APP/src/main/jniLibs/arm64-v8a/libinteriors.so" \
    "$BUILD/libinteriors.so"
ls -la "$APP/src/main/jniLibs/arm64-v8a/libinteriors.so"
