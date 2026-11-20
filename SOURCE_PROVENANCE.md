# 1.0.0 Source Snapshot

Prepared2026-10-13 from the user's signed-off integration candidate
0be925c14f9c (MD5d4e377b9ef7e667cb2a3d0f407140584, reported revisiond7ed255f).
File-only export: no inherited Git objects, branches, reflogs or history.
The old private ds-forever repository is NOT this source or public history.
Public destination approved by the user: Jreese702/DS-Forever; this local tree
has no Git remote and does not create or publish that repository.

Companion and launcher Java/Kotlin production files are unchanged from the
accepted APK inputs. The changes for release preparation are build metadata,
repo-relative companion layout, explicit release signing, native bootstrap
portability, licence/README documentation, and DS-owned replacements for the
previous promotional banners/wallpaper. Native generator/runtime policy remains
the accepted4M/ALGORITHM3 implementation.

CascLib source:38a34665624b8775bb875274b36191b21c38d97b; included patch_casclib.py
and casc_jni.cpp. Rebuilt libcasc.so matches the accepted binary byte-for-byte.
Recast source:6dc1667f580357e8a2154c28b7867bea7e8ad3a7; included generator/JNI.
Rebuilt libinteriors.so is identical to the accepted binary after excluding only
its build-ID note; the shipped binary is not rewritten to remove that note.

Original fetch_components.sh SHA-256:
78bfda279467f9f7f9c23d476c81cea9f1e3769ed3b26b47982170ca14143bd1.
Original components.sha256 SHA-256:
b2f139280749e08fdcf417820b06a36dcce764e30a4028528d892755ca0c163b.
Both remain unchanged. All four runtime archives are fetched/verified, ignored
from Git and inventoried in the APK; full-source/rights gaps are separately
listed in RELEASE_NOTICES.md and do not vanish because checksums match.

Private real-game fixtures, baseline images, review sheets, device logs,
credentials and extracted art are not included in this clean source snapshot.
The full D suite is run externally with a byte-matched private verification tree;
public builds need no game installation or private test corpus.

Public version1.0.0/code1000 is distinct from the accepted private2.2.2/code222
artifact. It requires its own exact-checksum installation/publication approval.
No main merge, push, publication or device operation is implied by this file.

## Browser login helper (wow-session-bridge.exe), 2026-11-18

Source: `tools/wow-session-bridge/` = WoW Forever for Android
(jaredgei/wow-forever-android) at v2.4.5, commit
aba66f9c54bf16b4b7440af2e452232f8fc2caea, every file byte-identical to
upstream's blob (git hash-object checked), plus DS Forever's `build_dsf.sh`
and `wow-session-bridge.sha256`. Upstream's prebuilt helper (8e7585fb, later
dropped by upstream in 1f2446b) is NOT used, run or copied.

Toolchain: LLVM-MinGW 20261104 (Clang 23.1.5, ARM64 Windows UCRT), official
release https://github.com/mstorsjo/llvm-mingw/releases/tag/20261104:
- llvm-mingw-20261104-ucrt-x86_64.zip (Windows host, used by build_dsf.sh
  under Git Bash): SHA-256
  552191b5754c49a5a20723276a6fc56541ea1922c4c50b7b669022333fc1b40f
  (matches GitHub's published asset digest).
- llvm-mingw-20261104-ucrt-ubuntu-22.04-x86_64.tar.xz (upstream's pin in its
  release workflow): SHA-256
  919380403de87dca3c581c2053230cd298af5e891286628ba0613b7fed370926
  (matches upstream's pin and GitHub's digest); cross-check build in WSL.

Output: `app/src/main/assets/wow-session-bridge.exe` (git-ignored, as
upstream), 0098d642a594d5a541537c3c1a96b5bc317227efb961267d5c3b8cc740d3af51.
The Windows build, a repeat Windows build and the Linux/WSL build with
upstream's pinned toolchain are byte-identical, and equal to the hash
upstream's own v2.4.5 release CI printed for this source (run 44308379224,
head aba66f9c). The portable C protocol tests (`test.sh`) pass. The app build
refuses any helper with other bytes (`checkSessionBridge`, app/build.gradle.kts).
Rebuild: `sh tools/wow-session-bridge/build_dsf.sh` (Git Bash).

## DNS shim (libgamenative_dns_v4mapped.so), 2026-11-20

Source: `app/src/main/cpp/gamenative_dns_v4mapped.c`. The prebuilt
`app/src/main/jniLibs/arm64-v8a/libgamenative_dns_v4mapped.so` is committed
(not built by Gradle). The gethostbyname()/gethostbyname_r() overrides are
from WoW Forever for Android v2.4.3, commit
17c46d533d88e4bb7567a70e196e6be40b862635 (from jaredgei/wow-forever-android#85
by @Demineo04), adapted to DS Forever's WOWFOREVER_HOSTNAME.

Rebuild (Git Bash, NDK 27.2.12479018; the same command reproduces the
previous committed .so byte-for-byte from its source,
af9a7fd5dd986e26beb41903e075623b57afe57a3048831862b49e7e7b3f9e49):

    T=$LOCALAPPDATA/Android/Sdk/ndk/27.2.12479018/toolchains/llvm/prebuilt/windows-x86_64/bin
    O=app/src/main/jniLibs/arm64-v8a/libgamenative_dns_v4mapped.so
    $T/aarch64-linux-android26-clang -shared -O2 -fPIC -Wl,-z,max-page-size=16384 \
        -o $O app/src/main/cpp/gamenative_dns_v4mapped.c -ldl
    $T/llvm-strip --strip-unneeded $O

Output: fe752fbd6a2584c37920698719f70c8c3f0f74566025da4ba4d781d88079a00e.
Host test: `wsl -e sh tools/dns-shim/test.sh` (Linux/glibc, LD_PRELOAD).

## Proton without its 32-bit Windows files, 2026-11-20

The shipped `proton-11.0-90624-arm64ec.wcp` is the pinned original (tools/components.sha256, unchanged, still fetched and
checked by the unchanged tools/fetch_components.sh) rewritten by `tools/trim_proton_i386.py`, a port of WoW Forever for
Android v2.4.4 commit 55b0bd234c3af27f9c157936589c999f4805025a (issue #89). It drops the 1012 files of
lib/wine/i386-windows (the directory entry stays) and the 49 32-bit PE files of prefixPack.txz; every other entry is copied
with the same order, permissions and links. 125,790,679 B -> 64,738,601 B.

Trimmed SHA-256, pinned in tools/components-trimmed.sha256 and checked by tools/build_release.py (which refuses the
untrimmed original): 4512cd6dd092e038d99a8856076a7a339dab81499135d85e74db3cad2ef4614a. Made on Windows with Python 3.13.3
and the zstandard module 0.25.0 (level 19, single-threaded); two runs were byte-identical. Another zstd/xz build may give
other bytes: the release check then fails and the pin must be renewed from a verified run.
