#!/bin/sh
# Builds the harvest's file reader, app/src/main/jniLibs/arm64-v8a/libcasc.so: CascLib (MIT, Ladislav Zezula) + tools/harvest/
# casc_jni.cpp, with the Android NDK's clang for arm64 (Android 10 / API 29, the app's minSdk). Not in git, like the add-on set:
# a build without it has no harvest (CascNative.available() is false) and everything else works.
#   sh tools/harvest/build_native.sh [<CascLib source folder>]
# Needs the NDK (27.2.12479018, from dl.google.com) under the Android SDK. CascFiles.cpp uses the Windows type LPDWORD, which
# CascLib's Linux port does not define: supplied with -DLPDWORD=DWORD*.
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
APP=$(cd "$HERE/../.." && pwd)/app
SRC=${1:-$(cd "$HERE/../../.." && pwd)/.deps/CascLib}/src
SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-${LOCALAPPDATA:-}/Android/Sdk}}
NDK=${ANDROID_NDK:-$SDK/ndk/27.2.12479018}
BIN="$NDK/toolchains/llvm/prebuilt/windows-x86_64/bin"
CXX="$BIN/clang++.exe"; CC="$BIN/clang.exe"
T=--target=aarch64-linux-android29
OBJ=$(mktemp -d "${TMP:-/tmp}/wc-casc-obj.XXXXXX")
# our two patches go on a COPY of CascLib (downloads to the app's cache, read-only fallback): tools/harvest/patch_casclib.py
cp -r "$SRC" "$OBJ/src"; SRC="$OBJ/src"
python "$HERE/patch_casclib.py" "$SRC"
FLAGS="$T -O2 -fPIC -fvisibility=hidden -DCASC_USE_SYSTEM_ZLIB -DCASCLIB_NO_AUTO_LINK_LIBRARY -DCASCLIB_NODEBUG -DNDEBUG -I$SRC"
for f in common/Common common/Directory common/Csv common/FileStream common/FileTree common/ListFile common/Mime \
         common/RootHandler common/Sockets hashes/md5 hashes/sha1 overwatch/apm overwatch/cmf overwatch/aes CascDecompress \
         CascDecrypt CascDumpData CascFiles CascFindFile CascIndexFiles CascOpenFile CascOpenStorage CascReadFile \
         CascRootFile_Diablo3 CascRootFile_Install CascRootFile_MNDX CascRootFile_Text CascRootFile_TVFS CascRootFile_OW \
         CascRootFile_WoW; do
    "$CXX" $FLAGS '-DLPDWORD=DWORD*' -std=c++17 -c "$SRC/$f.cpp" -o "$OBJ/$(basename $f).o"
done
"$CC" $FLAGS -c "$SRC/jenkins/lookup3.c" -o "$OBJ/lookup3.o"
# the JNI glue keeps default visibility for its exported functions (JNIEXPORT)
"$CXX" $T -O2 -fPIC -DCASC_USE_SYSTEM_ZLIB -DCASCLIB_NO_AUTO_LINK_LIBRARY -DCASCLIB_NODEBUG -DNDEBUG -I"$SRC" -std=c++17 \
    -c "$HERE/casc_jni.cpp" -o "$OBJ/casc_jni.o"
mkdir -p "$APP/src/main/jniLibs/arm64-v8a"
"$CXX" $T -shared -static-libstdc++ -Wl,-soname,libcasc.so -Wl,--gc-sections -o "$APP/src/main/jniLibs/arm64-v8a/libcasc.so" "$OBJ"/*.o -lz -llog
"$BIN/llvm-strip.exe" --strip-unneeded "$APP/src/main/jniLibs/arm64-v8a/libcasc.so"
ls -la "$APP/src/main/jniLibs/arm64-v8a/libcasc.so"
