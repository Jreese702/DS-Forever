# DS Forever

Android launcher and companion for the World of Warcraft: Forever beta. Forked
from [WoW Forever](https://github.com/jaredgei/wow-forever-android) and
[GameNative](https://github.com/utkarshdalal/GameNative); their contributors'
copyright/licence notices are retained.

Unofficial community project, not affiliated with or endorsed by Blizzard,
WoW Forever's maintainers or GameNative. World of Warcraft and Battle.net are
their owners' marks. Supply your own authorised game installation/account.
No Blizzard client files, extracted art or QuestieDB are bundled.

## Tested Scope

**AYN Thor only**; other devices are unverified. Android10/API29+, arm64-v8a.
Package `app.dsforever.companion`; version1.0.0/code1000. Same-package, same-key
DS builds update in place. Do not uninstall or clear data for signing conflicts;
developer builds with another key cannot update an official installation.

The user accepted the predecessor's T3 walk-through. Full-catalogue storage and
map-reader checks are PC evidence, not a completed full-device bake. The native
peak includes the game; large-dungeon device memory/backoff was not captured.
Some layouts remain deferred. Whole-city/floorless/liquid coverage is incomplete.

## Setup And Play

Choose the folder containing `.build.info` and `Data/`, including hidden files.
Thor testing used `/storage/emulated/0/Documents/WoW Forever`; every game path
comes from the chosen root, not a hard-coded old `/sdcard` folder.

Two-screen setup checks environment readiness and automatically prepares art
from your installed game. Seven required steps gate Play; required failures
offer **Try again**. QuestieDB downloads after the art check. Optional interiors
and all-icons background work never gate Play. No extracted PC/server pictures
are used as a fallback.

Configure Login/Update Login manages sign-in; updating login restarts the game
to read it. Refused sign-in has recovery controls. Credentials are excluded from
diagnostic logs. Keyboard opens Android's keyboard on the Thor's top screen.
Authenticator challenges are manual; avoid repeated wrong-password tests.

The companion shows observed NPCs, not database-only ghosts. No suggested spells.
Old DS-created macros are removed silently, preserving user-created macros.
Map probe defaults ON, tracking auto-switch OFF, sparkles OFF. Indoor legend:
**red main exit, green up, blue down**. Outdoors remains visible while a plan loads.

## Updates

The **app updater** uses
[`Jreese702/DS-Forever`](https://github.com/Jreese702/DS-Forever/releases/latest)
and `DS-Forever-<version>.apk`. Offline checks do not block Play. Downloads check
size/any supplied SHA-256; installation checks package, DS certificate and a newer
numeric versionCode. Android owns installer permission/confirmation. Updates are
held while the game runs. Release1.0.0 asset: `DS-Forever-1.0.0.apk` + SHA256SUMS.
The updater is not a working public channel until the public release exists.

The **game updater** separately checks Blizzard's build and updates client/CASC
metadata in the chosen folder. App updates do not replace game data. Game updates
can invalidate generated art/interiors. Neither updater grants beta entitlement.

## Self-Contained Windows Build

Companion and set115 add-on source live in `companion/`. Never reuse/publish the
old private `ds-forever` history; this source snapshot has no inherited Git history.
Install JDK17, Python3.8+, Git for Windows (Bash/curl), CMake>=3.16, Ninja,
Android platform36/build-tools36.1.0/NDK27.2.12479018. Put tools on PATH; set
JAVA_HOME and ANDROID_HOME. Gradle8.12.1 and bootstrap fetch public dependencies.

```powershell
./tools/bootstrap.ps1
./gradlew.bat :app:assembleModernDebug
```

Debug signing is for development, not updating official installs. Signed release:
securely supply all four environment values WC_KEYSTORE, WC_KEYSTORE_PASS,
WC_KEY_ALIAS, WC_KEY_PASS (never put secrets in source), then:

```powershell
python tools/build_release.py --out C:/build-output/DS-Forever-1.0.0
```

The original `tools/fetch_components.sh` is unchanged: all FOUR pinned files,
three from upstream components-v1 and TurnipV32 from its original upstream URL.
`./tools/bootstrap.ps1 -Components C:/verified-files` supports a local folder.
The Windows SHA-256 check-mode shim in tools/bin supplies shasum without editing
the fetch script. Runtime archives stay ignored. Bootstrap fetches pinned
CascLib/Recast, patches a CascLib copy and builds both JNI libraries. Gradle uses
repo-relative `companion/app/src/main`; -PdsAppDir is a developer override.
Release tooling checks frozen inputs, runtime hashes, signing/version, zero-art
including resources, and post-R8 callbacks. Linux/bit-for-bit reproducibility
has not been verified.

## Credits And Licences

WoW Forever and GameNative: launcher, container and X-server foundation. DS:
companion, chosen-root setup/sign-in, controller workflow, local art/interiors
and app updater. Thanks to the RP6 runtime contributors, Wine/Proton, DXVK,
Mesa, CascLib (Ladislav Zezula), Recast (Mikko Mononen), box64, PulseAudio and
the projects in THIRD_PARTY_NOTICES. Application fork: GPL-3.0 (LICENSE).
Dependencies have separate terms: RELEASE_NOTICES.md, licenses/ and APK inventory.
Source/patch/redistribution gaps are explicit, not presumed cleared for publication.
