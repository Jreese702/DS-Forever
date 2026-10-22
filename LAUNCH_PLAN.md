# DS Forever: launch support plan (beta to launch)

Branch `launch`, based on `593abfb` (v1.0.3). Not pushed, merged or released.

The beta (`wow_classic_beta`) ends on **21 Oct 2026** and the game launches on **4 Nov 2026**. This build runs **both** clients. It picks one from what the game folder's `.build.info` holds, so 1.0.x keeps working on the beta and moves to the launch client without a new setting.

## 1. What is known about the launch client (checked 2026-10-13)

These come from Blizzard's own patch server: `http://us.patch.battle.net:1119/<product>/versions`, then the `ProductConfig` from `tpr/configs/data`.

| | Beta | Launch |
|---|---|---|
| Product code | `wow_classic_beta` | **`wowf`** |
| Flavor folder (`shared_container_default_subfolder`) | `_classic_beta_` | **`_forever_`** |
| ARM64 exe (win `relative_path_arm64`) | `WowB-arm64.exe` | **`Wow-arm64.exe`** |
| Current build (us/eu/kr/tw) | 1.60.1.70338 | 1.60.1.70245 |
| CDN path / hosts | `tpr/wow`, level3 + us.cdn | same (same CDNConfig) |
| Registry title | "World of Warcraft Classic Beta" | "World of Warcraft Forever" (also "WoW: Forever - Stress Test") |

**Known now:** the product code, folder, exe name and CDN path. `wowf` has been live since the stress test, and `title_info` lists it next to `wow_classic_beta`.

**Only confirmable at launch:**
- the launch build number;
- whether the Battle.net app installs `_forever_` beside an existing `_classic_beta_` (one shared `Data/`) or in a new folder;
- the `## Interface:` number. Our add-ons say 16001. A new number shows them as "out of date", and they still load.
- whether QuestieDB-Forever recognises the launch client;
- whether any fileDataID used by the art recipes changed.

Other probes: `wow_forever*`, `wowfb` and `wow_classic_forever` all return 404.

## 2. Beta assumptions: inventory and what this branch does with each

**Changed (now follow the folder's client):**
- **`companion/.../WowProduct.kt` (new).** It holds both products' facts and the one choice order. Its `choose()` works like this:
  1. Take only the rows of a supported product.
  2. Prefer Active rows.
  3. If two products are still left, prefer the one whose exe is installed.
  4. If both exes are installed, prefer the beta through 21 Oct and the launch client after that.

  Retail or classic rows in the same folder are ignored, as before.
- **`app/.../WowClientDownloader.kt`.**
  - `TARGET_PRODUCT`, `FLAVOR_DIR`, `EXE_NAME` and `PATCH_URL` are gone. `product()`, `flavorDir()`, `exe()` and `product.patchUrl` replace them.
  - The patch-server row is now picked by the install's region (`Branch`) instead of always the first row.
  - `.build.info` updates now rewrite the row of the client that was checked.
- **`app/.../WoWForeverScreen.kt`:**
  - The readiness check, the "wrong game" check and its message, and the saved-path check accept either product.
  - The add-on check, QuestieDB target, `LauncherSetup`, shadow folder and container `executablePath` use the chosen client's folder and exe. The exe is looked up whatever its case.
  - `Config.wtf` defaults: `portal` is `"test"` for the beta and the install's region (default `us`) for launch. `agentUID` is set to the product code. Neither is forced on an existing file, as before.
  - `.flavor.info` is written with the product code.
- **`app/.../BattleNetSignIn.kt`:** `login.txt` cleanup uses the chosen client's folder.
- **`companion/.../GamePaths.kt`:** `log(root)` follows the folder's client, so Home finds the `_forever_` log. It is re-resolved on every follow, so a switch while the app is running is picked up.
- **`companion/.../GameFiles.kt`:** `buildRow` uses `WowProduct.choose`, so the CASC open, the art harvest, interiors and the CDN fallback all read the same row the launcher runs. `localeMask` reads that client's `Config.wtf`.
- **`companion/.../NativeLogin.kt`.** This fixes a real hazard. After a switch, the private shadow still has a real `_classic_beta_` folder full of symlinks into the shared game folder. `ShadowRepair` walks folders by following links, then deletes the "merged" sources, so it could have moved or deleted shared game files. The other client's shadow links are now removed before the repair runs. `clear()` also deletes `login.txt` in both flavor folders.
- **`companion/tools/interiors_native/host/*.cpp` (PC-only tools).** These open whichever of `wowf` / `wow_classic_beta` the folder holds; `DSF_PRODUCT` overrides the choice. *Not compiled in this pass* (they need CascLib and MSVC).

**Unchanged, and why:**
- **Art stamps, indoor `current.json`, `minimap/made.txt`, all-icons packs and `icon_requirements.json`** are keyed by the `.build.info` Build Key (a hash), not by product. At the switch the key changes, so:
  - before-Play art runs again once, and **Play waits for it, about as long as a first setup**;
  - interiors stay hidden until they are re-baked in the background;
  - the minimap tiles are rebuilt.

  This is expected. Keying by Build Key was already right.
- **`IndoorGeneratorDungeonRoots.PROVENANCE_BUILD` (70338)** is only a label. A root whose content key differs falls back to the geometric rule, silently. Re-run the PC catalogue against launch data later; it is not a blocker.
- **`harvest/*.json` recipes** hold fileDataIDs and DB2 ids and have no product key. An id that is missing fails loudly ("not in this game build's index").
- **QuestieDB** (`DependencyAddons`) takes the latest `Questie/QuestieDB` release, `QuestieDB-Forever.zip`. It does not depend on the flavor, and it installs into the chosen client's `Interface/AddOns`.
- **SvKeep.** The kept copies in `Documents/DS Forever/settings` are not per flavor, so DS Forever's own add-on settings carry from the beta into `_forever_` when `_forever_` has nothing newer. That is probably what players want. The game's own WTF (key binds, macros) does **not** carry over, because the client keeps it per flavor folder.
- **Legacy standalone/root paths:**
  - `LogTail.DEFAULT_PATH` (GameNative `WoW-Forever-beta/_classic_beta_`), `GamePaths.OLD_DEFAULT_LOG` and `companion_setup.sh` `GN_ROOT`/`WF_ROOT` are used only by the old root companion. Inside the launcher, `GamePaths.follow` uses the chosen root.
  - `GameLocator` reads the flavor from the container's `executablePath`, so it already works with `_forever_`.
- **Lua:** a dead realm fix keyed on "Yes-Classic Beta PvE", and a guarded `GetScriptsDisallowedForBeta()`. Both are harmless.
- **README "beta" wording** should be updated with the release notes for the release that ships this.

## 2b. The beta closed: the app moves the player itself (2026-10-21)

`WowProduct.choose()` kept a beta-only folder on the beta for ever, so players saw "no realms". Now:

- **Trigger (`companion/.../WowSwitch.kt`):** the folder runs the beta **and** the beta is over (`WowProduct.betaOver`: local date on or after 21 Oct 2026) **and** `wowf/versions` answers for the install's region. No answer = nothing changes, and the screen says so.
- **The move (`WowClientDownloader.switchToLaunch`):** the wowf row is added to `.build.info` (the beta row byte for byte, the line ending kept), then the wowf client is synced through `updateGame`: configs, the `_forever_` client files (a file identical to the beta's is copied, not downloaded; **the exe last**), the CDN config's indices, then the row's keys (Install Key from the build config). Every file is a temp file renamed into place.
- **Resume:** until `_forever_/Wow-arm64.exe` exists, `choose()` still picks the beta, so a killed app finds the move still due and goes on; files already right are skipped.
- **Tie-break:** both clients installed: the launch client from 21 Oct on (was: from 22 Oct). `expected()` (a new folder) is the launch client from 21 Oct.
- **Status:** "Moving to World of Warcraft: Forever… NN% · step" on the updater's line (`WoWForeverScreen.switchIfDue`, run before every client check, and again from Play).
- **Config.wtf (`companion/.../GameConfigWtf.kt`, moved from the screen):** `_forever_` gets `portal` = region and `agentUID "wowf"`; a beta `portal "test"` or beta agentUID in a `_forever_` file is corrected.
- **SvKeep:** `SvKeep.carryOver` copies the beta's `WowCompanion*.lua` SavedVariables into the same account folder under `_forever_/WTF`, once (marker `.dsf-carried-from-beta`), never over a newer file. That account folder also lets `beforeStart` restore the kept copies.

## 3. Off-device tests (pass)

- **`dsforever/src/test/.../WowProductTest.kt`, 14 tests:**
  - beta-only, launch-only and retail-first folders;
  - Active versus inactive rows;
  - both clients installed, before, on and after 21 Oct;
  - only one exe installed;
  - no supported row;
  - CRLF and short rows;
  - Blizzard's product facts;
  - the portal;
  - a case-insensitive exe on disk;
  - a folder with no `.build.info`;
  - the `GamePaths` log;
  - `GameFiles.buildRow` / `activeBuild` reading the same row.
- **`app/src/test/.../WowClientDownloaderTest.kt`, 2 tests:** the region row on the patch server, and paths and `readBuildInfo` for a `wowf` folder.

Run with `ANDROID_HOME` set:

```
./gradlew.bat :dsforever:testDebugUnitTest :app:testModernDebugUnitTest --tests "*WowProductTest" --tests "*WowClientDownloaderTest"
```

`:app` compiled cleanly with these changes.

## 4. Thor test plan (needs the user's yes; nothing installed by this session)

1. **Beta regression (before 21 Oct).** Install the build over 1.0.3 and check:
   - Setup shows all seven steps green.
   - Play works, the game reaches the world, and Home follows the `_classic_beta_` log.
   - The art stamp is *not* redone, because the Build Key is unchanged.
   - Login works through the shadow folder.
   - QuestieDB check says up to date.
   - The game updater still reports 1.60.1.70338.
2. **Launch folder, off the Thor first (PC).** Install `wowf` with the Battle.net app on a PC and record where it goes: beside the beta or in a new folder. Copy that folder's `.build.info` header and rows (no game files) to tracking.
3. **Launch on the Thor** (once `wowf` can be installed or copied there). Point the app at the folder and check:
   - "Incompatible game" does **not** show.
   - The updater checks `wowf/versions`, and `_forever_/Wow-arm64.exe` is fetched if it is missing.
   - `_forever_/WTF/Config.wtf` has `portal "us"` (or the install's region) and `agentUID "wowf"`.
   - Before-Play art runs once; Play then works.
   - Home follows `_forever_/Logs/General.log`.
   - QuestieDB installs into `_forever_/Interface/AddOns`.
   - After a run, the shadow folder holds `_forever_` and the old `_classic_beta_` is empty of links, and the shared `_classic_beta_` is intact.
4. **Both clients in one folder** (if the Battle.net app does that). Check the choice is the beta through 21 Oct and `wowf` after, or whichever client has its exe installed.

## 5. Launch day (4 Nov) checklist

- [ ] `curl http://us.patch.battle.net:1119/wowf/versions`: note the build number. Confirm the `ProductConfig` still says `_forever_` / `Wow-arm64.exe`.
- [ ] `/wowf/cdns` still shows `tpr/wow`.
- [ ] The launch client's `## Interface:` number; bump the 11 `.toc` files if it is not 16001.
- [ ] QuestieDB-Forever has a release that recognises the launch client.
- [ ] The art harvest on the launch build: any "not in this game build's index" step is a fileDataID change and needs a recipe fix.
- [ ] Signing in to a live realm works with portal = region (the beta used `test`).
- [ ] If Blizzard ships launch under another code, add a third `WowProduct` entry: one line, plus a test.

## Shipping

This needs a version bump and a signed release (`tools/build_release.py`) published to the GitHub release the in-app updater reads, **before 21 Oct** ideally (and in any case before 4 Nov). The user decides; this session does not push or publish.

---

## Side item: l-gamenative-server (research and proposal only)

**What the first start fetches** (`app/.../utils/Net.kt` `fetchFileWithFallback`: `https://downloads.gamenative.app/<file>`, with the R2 fallback `pub-9fcd5294bd0d4b85a9d73615bf98f3b5.r2.dev`):
- **`imagefs_bionic.txz`**, 183,506,500 bytes, ETag `097908c18db114f071716b5898c3a80f`, 2025-10-12. Fetched by `PluviaMain.preLaunchApp` and extracted by `ImageFsInstaller`. The code **already prefers an APK asset of the same name** when one is present.
- **`container_files/extras.tzst`**, 85,987,690 bytes, 2026-06-03. Fetched by `ContainerFilesDownloader` (`container_files_download.json`) on the modern variant; the legacy variant reads it from assets.
- The other manifests (dxwrapper, graphics_driver, core_drivers, wincomponents) are fetched only on demand. The WoW screen installs its own bundled Proton, DXVK and Turnip, so they are not needed for Play.

**Gap:** downloads are checked only for length, with no hash check.

**Options:**
1. **Mirror (recommended).** Publish both files as assets of a separate `runtime-v1` release on `Jreese702/DS-Forever`.
   - Add that URL as the *first* source in `fetchFileWithFallback`, keeping gamenative and R2 as fallbacks.
   - Pin a SHA-256 for each file in code and reject a mismatch.
   - The APK stays small, and app updates do not re-download about 270 MB.
2. **Bundle** both into APK assets: no code change for imagefs. The APK grows by about 270 MB, and every in-app update downloads it again. Not recommended.
3. **Hybrid:** bundle `extras.tzst` only and mirror `imagefs`.

Before publishing a mirror, check the licences. The imagefs contains GPL/LGPL parts (Wine, box64, glibc/bionic libs), so a mirror must carry the matching notices and a source offer, as `THIRD_PARTY_NOTICES` does for the components today. Publishing needs the user's yes.
