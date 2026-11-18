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
