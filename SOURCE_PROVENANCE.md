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

Changed by DS Forever, 2026-11-23 (the user's ruling: Browser login hands the
game account to the game, as the PC Battle.net app does). bridge.c, protocol.c,
protocol.h, test_protocol.c, test_integration.py and README.md are NO LONGER
byte-identical to upstream's aba66f9c: the launcher sends one more frame after
the token ("-" or "<REGION>:<name>"), and the helper writes GAME_ACCOUNT and
REGION beside the credential, or removes both as before (each changed source file
says so at its top, and every change is marked "DS Forever"). That follows nyrda/scuffle
5efccb44 bridge/auth_bridge.c (MIT; the same commit as the device ID). The
same toolchain still builds upstream's unchanged sources to 0098d642... (checked
again on 2026-11-23). The changed sources build to
099b867e195bb10f0ac0f184dcfd46eb7c1143457443c566b71ac73c15f98b7b, the new pin
in `wow-session-bridge.sha256`; a repeat build is byte-identical. Upstream's CI
printed no hash for this source. `test.sh` passes with the new frame tests.
The Wine integration test was updated but not run: there is no Wine on this PC.

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

## Idle-memory page-out (libdsf-pageout.so, NativeHeap.pageOutSelf), 2026-11-23

Sources: `dsforever/src/main/jni/dsf/dsf_pageout.h`, `dsf_pageout_hook.c` and the
page-out half of `dsf_heap.c`; `companion/.../PageOut.kt`. DS Forever's own
code, written from the Linux man pages madvise(2) (MADV_PAGEOUT), mincore(2) and
proc(5). The idea (a process paging out its own idle memory into the device's
existing zram on request) was seen in DroidDeck (GPL-3.0); none of DroidDeck's
code or text is used. A few design choices match its published description
(SIGURG installed only while at its default, 1 MiB chunks, 32 MiB per request,
chunks that come straight back skipped for a while); the code differs throughout
(sigqueue with our own value, mincore instead of pagemap, no worker thread, an
allow list of Wine helpers, our own request and answer files). Built from source by the dsforever ndkBuild
(no prebuilt binary is committed). Host tests: `wsl -e bash tools/pageout/test.sh`.

The game client's cold-only mode (the user's pick 2026-11-23, "Yes, carefully"; `dsf_pageout.h` po_g_run,
`dsf_pageout_hook.c` po_gserve, `dsf_heap.c` pageOutSignalGame, `companion/.../GamePageOut.kt`) is DS Forever's own
code from the same man pages plus rt_tgsigqueueinfo(2). DroidDeck's published description also trims the game's cold
memory with a cap per pass (256 MiB), a critical level at half its floor and a 10 s spacing; those numbers match
(and unlike DroidDeck, ours never drops the cold-only check when memory is critical), the rest differs (a two-step probe-and-commit idle check with mincore instead of a single trim, our own request file and
answer line, the request sent to an idle thread of the game, the character select trigger from DS Forever's own
signals). No DroidDeck code or text is used.

WHAT THE LIBRARY DOES INSIDE THE GAME'S PROCESS (the ToS note): it reads `/proc/self/maps` (address ranges and their
kinds), `/proc/self/cmdline` and `/proc/self/comm` (to know it is the game client), asks mincore(2) which of its own
pages are resident and calls madvise(MADV_PAGEOUT) on its own private anonymous ranges, then writes one answer line to
DS Forever's private folder. It never reads or writes the game's memory contents, never hooks, patches or calls a game
function and looks up no game symbol; its only entry points are its constructor and a SIGURG handler that serves only
DS Forever's own signal value. The kernel brings every paged-out page back unchanged on its next touch.

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

## Opt-in test graphics driver (turnip-test-mesa-26.3.1.zip), 2026-11-23

Source: the official Mesa 26.3.1 release tarball, https://archive.mesa3d.org/mesa-26.3.1.tar.xz,
SHA-256 4e4e438eac7b840a9383988cbb7c08dad8041da544b9319fc26d319fa7348961 (the value in
https://docs.mesa3d.org/relnotes/26.3.1.html). Its detached signature was verified on
2026-11-24: gpg "Good signature" from Dylan Baker, key 71C4B75620BC75708B4BDB254C95FAAB3EB073EC (4C95FAAB3EB073EC); the
tarball's SHA-256 matches the release notes' value. ONE local change: `tools/turnip-test/0001-android-export-vk_icd-entrypoints.diff`
(made by `mkpatch.py`): vulkan-android.sym / vulkan-icd-android-symbols.txt also export the three
vk_icd* loader entrypoints the Vulkan wrapper loads through. The IR3 scheduler fix our default driver
carries by hand is upstream (2f8dc9eaba06, in 26.3.0; ir3_sched.c:531 in 26.3.1) and is not re-applied;
the default driver's CODEX_* scheduler diagnostics are not carried.

Toolchain: WSL Ubuntu 26.04, Android NDK r27c 27.2.12479018 (clang 18.0.3), target
aarch64-linux-android29, meson 1.10.1, ninja 1.13.2, `--wrap-mode=nofallback` (no Meson download;
NEEDED: libhardware, liblog, libnativewindow, libsync, libm, libz, libdl, libc). Recipe:
`tools/turnip-test/build_mesa.sh`, then `python -I tools/turnip-test/pack.py <input> <out>`, which
reproduces the shipped zip byte-for-byte from the build's library, the diff and Mesa's licence texts.

Output: libvulkan_freedreno.so d93b06280c1d4bada8117cceb812f1b10165a2a44918bc236f11703f7459d8b0
(14,423,088 B); zip 3272d6189aa4202898457503236dad88980691fc0347fe56c60888cc73d09a33 (2,698,400 B),
pinned in tools/components-test-drivers.sha256 and checked by app/build.gradle.kts (checkTestDrivers)
and tools/build_release.py. meta.json name "Turnip-Test-Mesa-26.3.1" (its unpack folder),
driverVersion "26.3.1-dsf-icd", minApi 29. The launcher's TU_DEBUG=noconform (kept so only the driver changes
between A/B runs) names no option in 26.3.1: `parse_debug_string` (src/util/u_debug.c) skips unknown names silently,
and 26.3.1 reports Vulkan 1.4 on every a7xx and 1.3 on a6xx without it (src/freedreno/vulkan/tu_device.cc).
The C++ runtime (LLVM libc++) is linked statically from the NDK (`-static-libstdc++`; licenses/ndk/).

### Rebuilt without android-strict, 2026-11-24

DXVK 3.1.1 refused the GPU on this driver ("Your 3D accelerator card is not supported"; its log: "Device does not support
required feature 'khrLoadStoreOpNone'"). Mesa's Android build with `android-strict` on (the default) offers only the device
extensions its allowlist permits at the target API (vk_android_allowed_device_extensions), and VK_KHR_load_store_op_none is
allowed from API 35, not 29. The SAME tarball, patch and NDK, with `-Dandroid-strict=false` added to build_mesa.sh, on CachyOS
Linux (meson 1.12.1, ninja 1.13.2). pack.py reproduces the shipped zip byte-for-byte.

Output: libvulkan_freedreno.so 03d86a9f117960637adee77a83ac72d588215b8cb165835d886d088cb941b2dc (14,422,384 B); zip
95a028aeb061263868e828a06f873151151fd54939d19bf8ca4d56bfd4000e94 (2,687,936 B), pinned in tools/components-test-drivers.sha256.
meta.json name "Turnip-Test-Mesa-26.3.1-nostrict" (a new unpack folder, so a phone holding the first build unpacks this one),
driverVersion "26.3.1-dsf-icd-nostrict". With it DXVK 3.1.1 starts (tiler mode on). On an Adreno 740 Thor some UI elements (the
personal resource bars) have been seen drawing flat grey with this driver and DXVK 3.1.1; Recommended is unaffected.

## DXVK 3.1.1 (dxvk-3.1.1-wow-aarch64-diag2.wcp), 2026-11-24

Source: https://github.com/doitsujin/dxvk tag v3.1.1 (b1a1c99), zlib/libpng, plus `tools/dxvk/dxvk-3.1.1-wow.patch` (no
mutable-format swapchain: the Winlator wrapper's swapchain images are not mutable and Turnip aborts at vk_image.c:569; two log
lines). Toolchain: llvm-mingw 20261104 (ucrt, x86_64 Linux host), cross file tools/dxvk/aarch64-windows.ini.in, meson release
build, stripped. Recipe: `tools/dxvk/build.sh`. Pinned in tools/components-dxvk.sha256
(e4bd1c6f3a79ce7220281650ae4938aa4937695c161fce9a4819948db3db2539, 2,315,248 B), checked by app/build.gradle.kts and
tools/build_release.py; notices in licenses/dxvk-3.1.1/.
