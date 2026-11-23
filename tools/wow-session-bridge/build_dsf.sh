#!/usr/bin/env sh
# DS Forever: builds the browser-login helper FROM SOURCE into the APK's assets (the user, 2026-11-18: never upstream's prebuilt exe).
# The toolchain is upstream's pin, LLVM-MinGW 20261104 (Clang 23.1.5, ARM64 Windows UCRT), from its official GitHub release:
#   Windows (Git Bash):  llvm-mingw-20261104-ucrt-x86_64.zip                  SHA-256 552191b5754c49a5a20723276a6fc56541ea1922c4c50b7b669022333fc1b40f
#                        (GitHub's published release-asset digest; upstream's workflow pins only the Linux archive)
#   Linux (or WSL):      llvm-mingw-20261104-ucrt-ubuntu-22.04-x86_64.tar.xz  SHA-256 919380403de87dca3c581c2053230cd298af5e891286628ba0613b7fed370926
#                        (upstream's release.yml pin, the same as GitHub's digest)
# Both toolchains build the same bytes, which match wow-session-bridge.sha256. Since 2026-11-23 the sources carry DS Forever's
# game-account change, so the pin is no longer upstream's v2.4.5 CI hash for aba66f9c (SOURCE_PROVENANCE.md).
# Usage: tools/wow-session-bridge/build_dsf.sh [toolchains-dir]   (default C:/dsf-work/toolchains on Windows, ~/.cache/dsf-toolchains else)
set -eu
bridge_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
repo=$(CDPATH= cd -- "$bridge_dir/../.." && pwd)
release=https://github.com/mstorsjo/llvm-mingw/releases/download/20261104
case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*)
        tools=${1:-/c/dsf-work/toolchains}
        name=llvm-mingw-20261104-ucrt-x86_64; archive=$name.zip
        sha=552191b5754c49a5a20723276a6fc56541ea1922c4c50b7b669022333fc1b40f
        compiler=$tools/$name/bin/aarch64-w64-mingw32-clang.exe ;;
    *)
        tools=${1:-$HOME/.cache/dsf-toolchains}
        name=llvm-mingw-20261104-ucrt-ubuntu-22.04-x86_64; archive=$name.tar.xz
        sha=919380403de87dca3c581c2053230cd298af5e891286628ba0613b7fed370926
        compiler=$tools/$name/bin/aarch64-w64-mingw32-clang ;;
esac
mkdir -p "$tools"
if [ ! -x "$compiler" ]; then
    [ -f "$tools/$archive" ] || curl -fL --retry 3 -o "$tools/$archive" "$release/$archive"
    echo "$sha  $tools/$archive" | sha256sum -c -
    case "$archive" in
        *.zip) (cd "$tools" && unzip -q "$archive") ;;
        *) tar -xJf "$tools/$archive" -C "$tools" ;;
    esac
fi
out="$repo/app/src/main/assets/wow-session-bridge.exe"
sh "$bridge_dir/build.sh" "$compiler" "$out"
expected=$(cut -d' ' -f1 "$bridge_dir/wow-session-bridge.sha256")
actual=$(sha256sum "$out" | cut -d' ' -f1)
if [ "$actual" != "$expected" ]; then
    echo "wow-session-bridge.exe: $actual, expected $expected (wow-session-bridge.sha256). Sources or toolchain changed?" >&2
    exit 1
fi
echo "wow-session-bridge.exe: $actual (matches wow-session-bridge.sha256)"
