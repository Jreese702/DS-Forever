# DS Forever 1.0.0: Component Inventory And Publication Holds

Prepared2026-10-13. This inventories actual inputs and preserves notices. It is
not legal clearance or an assertion that the source/rights gaps below are closed.
Application fork: GPL-3.0; LICENSE retained. Ancestry: WoW Forever for Android,
GameNative, Pluvia and Winlator contributors; inherited THIRD_PARTY_NOTICES kept.
No old private repository history is part of this source snapshot.

## Four Pinned Runtime Archives

All are ignored build inputs. Original tools/fetch_components.sh and pinned
tools/components.sha256 are unchanged. The exact files are also checked inside
the release APK. Profile/metadata and available archive licence files are copied
verbatim under licenses/; their presence does not establish matching source.

| Archive | Observed provenance and terms | Remaining source/notice work |
|---|---|---|
| proton-11.0-90624-arm64ec.wcp | Archive profile calls this Wine11.0 with local ARM64 copied-syscall/NLS patches;90624 is NOT an upstream Proton version. WINE-COPYING.LIB is LGPL-2.1. Upstream credit points to The412Banner/proton-wine e5fa703ed7f7329e20d7ede481ab185cf8b1a8b2. | Archive the exact local patches, build recipes and full matching source; audit other embedded Wine/prefix components. An upstream tag/link alone is insufficient. |
| dxvk-2.4.1-wow-aarch64-test.wcp | DXVK2.4.1 ARM64; credited revision0cf05780abd7250c2cd713b7749cf32180157cf5, zlib/libpng. Eight archive notices cover DXVK, MinGW, LLVM and libdisplay-info. | Verify exact compiler/build recipes and any test modifications; preserve every relevant dependent notice. |
| turnip-wow-scheduler-test.zip | Mesa fe067b17d9, scheduler/diagnostic/ICD patches. Archive includes MIT, Apache2, SGI-B2, BSL1 and GPL notices. Mesa says individual file terms apply. | Supply exact patches and build configuration for this driver; establish which compiled pieces/terms apply. |
| turnip-V32-RP6sched.zip | Metadata: whitebelyash turnip/gen8 9c7e022, barycentric scheduler and vk_icd export patches. Archive contains ONLY meta.json and driver binary, no licence text. | Missing exact notice/source/patch bundle; short revision is not a verified full commit. Resolve before public redistribution. |

Download sources: [upstream components-v1](https://github.com/jaredgei/wow-forever-android/releases/tag/components-v1)
for the first three, and the original
[V32 URL](https://raw.githubusercontent.com/arusiasotto/wow-forever-a840/main/driver/turnip-V32-RP6sched.zip).
The mutable V32 URL fails closed if the pinned hash changes.

Primary source references checked for inventory:
[Wine licence at credited revision](https://github.com/The412Banner/proton-wine/blob/e5fa703ed7f7329e20d7ede481ab185cf8b1a8b2/COPYING.LIB),
[DXVK licence](https://github.com/doitsujin/dxvk/blob/0cf05780abd7250c2cd713b7749cf32180157cf5/LICENSE),
[Mesa per-component terms](https://github.com/mirror/mesa/blob/fe067b17d9/docs/license.rst).
These support licence identification, NOT verification of all custom binary inputs.

## Source-Built Companion JNI

- libcasc.so: CascLib38a34665624b8775bb875274b36191b21c38d97b (Ladislav Zezula,
  MIT). licenses/CascLib-MIT.txt. JNI code and patch_casclib.py are included;
  bootstrap patches a copy of pinned public source, never game files.
- libinteriors.so: included C++/JNI plus Recast6dc1667f580357e8a2154c28b7867bea7e8ad3a7
  (Mikko Mononen, zlib-style). licenses/Recast-zlib.txt. Native bootstrap is
  reproducible from listed inputs; bit-for-bit reproduction is not claimed.
- Android/system zlib and C++ runtime notices still apply as applicable; NDK
  build recipes and exact compiler version are recorded in the build receipt.

## Actual Launcher Inputs And Outstanding Rights

Included native/prebuilt files and runtime archives are hashed by APK_INVENTORY.json
when the exact APK is built. Gradle dependencies are separately listed in the
release dependency report; final APK entries, not inherited descriptions, decide
what ships.

- redirect.tzst and libkgslshim.so ship in the approved test payload. Upstream
  THIRD_PARTY_NOTICES labels these proprietary/source-withheld. Obtain explicit
  DS redistribution permission/source terms or an independently tested replacement
  before public release; an inherited aggregation argument is not permission.
- box64 archives (0.4.2), PulseAudio runtime/archive (including13.0 native libs),
  libsndfile and libltdl need release-matching source/revision/build recipes and
  their applicable GPL/LGPL/MIT notices. Generic project links do not close this.
- liblsfg-vk-layer.so: inherited notices distinguish MIT early releases from
  GPL later versions. Determine the exact shipped binary's revision/terms.
- libahbimage/libasurface_renderer/libevshim/libgnoverlay/DNS shim: inventory
  source availability, authorship and applicable notices for these prebuilts.
- Samsung perfsdk jar is an inherited source input with proprietary terms.
  The shrunk-Dex inventory must distinguish retained classes from stripped ones;
  resolve any retained SDK redistribution terms.
- Bricolage Grotesque fonts, input-control pictures, cursor/notification artwork
  and achievement audio need their own exact provenance/notices. Kenney's input
  prompt attribution is retained, but does not license arbitrary unknown assets.
  The official Bricolage OFL1.1 notice is now included under licenses/fonts/;
  exact binary revision provenance remains to be matched. NDK distribution and
  toolchain notices are included under licenses/ndk/.
- libsteambootstrap and steamhost/eahost/rgschost archives must not be described
  as shipped unless the exact APK inventory finds them. Inherited sections are
  preserved as historical attribution, not substituted for that inventory.

## Artwork And Publication Boundary

The Blizzard promotional banners and Wine wallpaper were replaced with existing
DS-owned launcher icon/stone assets in this release snapshot. wf_logo, generated
game art/geometry, QuestieDB, PC fixtures/review pictures, credentials, captures
and old private Git history are absent. Extracted notices/profile metadata are
not game-derived pictures/data. Source and APK zero-art checks are necessary,
but they do not clear the remaining proprietary/source/asset rights listed above.

No repo creation, push or publication is authorised by this inventory. Final
public release is held until those issues and the user's separate approval are
resolved. Do not promise an upstream source offer on behalf of DS Forever.
