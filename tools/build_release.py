"""Build and gate the self-contained signed APK (the version in app/build.gradle.kts); never install or publish."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import uuid
import zipfile


COMPONENT_NAMES = (
    "proton-11.0-90624-arm64ec.wcp",
    "dxvk-2.4.1-wow-aarch64-test.wcp",
    "turnip-wow-scheduler-test.zip",
    "turnip-V32-RP6sched.zip",
)
COMPONENT_PREFIX = "assets/bundled_components/"


def digest(stream):
    result = hashlib.sha256()
    for block in iter(lambda: stream.read(1024 * 1024), b""):
        result.update(block)
    return result.hexdigest()


def verified_components(launcher):
    manifest = launcher / "tools/components.sha256"
    pinned = {}
    for line in manifest.read_text(encoding="ascii").splitlines():
        if not line.strip():
            continue
        match = re.fullmatch(r"([0-9a-fA-F]{64}) [ *]([^/\\\s]+)", line)
        if not match or match[2] not in COMPONENT_NAMES or match[2] in pinned:
            raise ValueError("Invalid or duplicate component manifest entry")
        pinned[match[2]] = match[1].lower()
    if set(pinned) != set(COMPONENT_NAMES):
        raise ValueError("Component manifest must pin all four runtime files")
    folder = launcher / "app/src/main/assets/bundled_components"
    if not folder.is_dir() or {path.name for path in folder.iterdir()} != set(COMPONENT_NAMES):
        raise ValueError("Expected exactly all four bundled runtime files")
    components = []
    for name in COMPONENT_NAMES:
        source = folder / name
        if not source.is_file():
            raise ValueError("Missing bundled component file: " + name)
        with source.open("rb") as stream:
            actual = digest(stream)
        size = source.stat().st_size
        if not size or actual != pinned[name]:
            raise ValueError("Pinned component hash mismatch: " + name)
        components.append({"file": name, "bytes": size, "sha256": actual})
    return components


def verify_addon_manifest(companion):
    """The game only gets new add-on files when manifest.txt's set is higher and its md5s match (LauncherSetup).
    A changed add-on file with a stale manifest ships nothing to the game (13 Oct batch tests): refuse to build."""
    root = companion / "app/src/main/assets/companion"
    lines = (root / "manifest.txt").read_text(encoding="ascii").splitlines()
    if not lines or not re.fullmatch(r"set \d+", lines[0]):
        raise ValueError("manifest.txt must start with 'set N'")
    listed = {}
    for line in lines[1:]:
        md5, path = line.split("  ", 1)
        listed[path] = md5
    addons = root / "addons"
    actual = {p.relative_to(addons).as_posix(): hashlib.md5(p.read_bytes()).hexdigest()
              for p in addons.rglob("*") if p.is_file()}
    want = lines[0].split()[1]
    for toc in addons.rglob("*.toc"):
        label = re.search(r"^## Version: Set (\d+)", toc.read_text(encoding="utf-8", errors="replace"), re.M)
        if not label or label[1] != want:
            raise ValueError("Add-on label in " + toc.name + " must say 'Set " + want + "' (manifest set)")
    if actual != listed:
        changed = sorted(set(actual.items()) ^ set(listed.items()))
        raise ValueError("Add-on manifest.txt is stale; regenerate it and bump the set: " + ", ".join(p for p, _ in changed[:8]))


def verify_packaged_components(archive, components):
    names = [name for name in archive.namelist() if name.startswith(COMPONENT_PREFIX)]
    expected = {COMPONENT_PREFIX + name for name in COMPONENT_NAMES}
    if set(names) != expected or len(names) != len(expected):
        raise ValueError("APK must contain exactly all four bundled runtime files")
    for component in components:
        entry = COMPONENT_PREFIX + component["file"]
        with archive.open(entry) as stream:
            actual = digest(stream)
        if actual != component["sha256"] or archive.getinfo(entry).file_size != component["bytes"]:
            raise ValueError("Bundled component mismatch: " + component["file"])


def source_stamp(launcher, companion):
    result = hashlib.sha256()
    inputs = []
    for label, root in (("launcher", launcher), ("companion", companion)):
        directories = ("app/src", "dsforever/src") if label == "launcher" else ("app/src/main",)
        files = set()
        for directory in directories:
            files.update(path for path in (root / directory).rglob("*") if path.is_file())
        if label == "launcher":
            files.update(path for path in (root / "gradle").rglob("*") if path.is_file())
            files.update(path for path in root.glob("*.gradle.kts") if path.is_file())
            files.update(root / name for name in ("app/build.gradle.kts", "app/proguard-rules.pro",
                                                  "dsforever/build.gradle.kts", "gradle.properties", "local.properties",
                                                  "tools/components.sha256", "tools/fetch_components.sh")
                         if (root / name).is_file())
        for path in sorted(files):
            with path.open("rb") as source:
                value = digest(source)
            name = label + "/" + path.relative_to(root).as_posix()
            result.update((name + "\u0000" + value + "\n").encode("utf-8"))
            inputs.append({"file": name, "sha256": value})
    return result.hexdigest(), inputs


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    root = Path(__file__).resolve().parent.parent
    parser.add_argument("--launcher", type=Path, default=root)
    parser.add_argument("--companion", type=Path, default=root / 'companion')
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--offline", action='store_true')
    args = parser.parse_args()
    launcher, companion, out = (p.resolve() for p in (args.launcher, args.companion, args.out))
    for repository in (launcher, companion):
        try:
            out.relative_to(repository)
        except ValueError:
            continue
        raise ValueError("Frozen APK output must be outside the source checkouts")
    components = verified_components(launcher)
    verify_addon_manifest(companion)
    wanted = {"WC_KEYSTORE", "WC_KEYSTORE_PASS", "WC_KEY_ALIAS", "WC_KEY_PASS"}
    settings = {key: os.environ[key] for key in wanted if os.environ.get(key)}
    if settings.keys() != wanted:
        raise ValueError("Missing signing setting names: " + ", ".join(sorted(wanted - settings.keys())))
    out.mkdir(parents=True, exist_ok=True)
    if any(out.iterdir()):
        raise ValueError('Use a new empty output directory; never replace frozen release bytes')
    gradle = (launcher / 'app/build.gradle.kts').read_text(encoding='utf-8')
    code = int(re.search(r'versionCode = (\d+)', gradle)[1]); token = re.search(r'versionName = "([^"]+)"', gradle)[1]
    log = out / ("build-" + token + ".log")
    env = dict(os.environ, **settings)
    sdk = Path(env.get('ANDROID_HOME') or env.get('ANDROID_SDK_ROOT') or '')
    aapt = sdk / 'build-tools/36.1.0/aapt2.exe'
    if not aapt.is_file():
        raise ValueError('Set ANDROID_HOME; install Android build-tools36.1.0')
    env.update({"ANDROID_HOME": str(sdk), "AAPT2": str(aapt), "TMP": str(out), "TEMP": str(out)})
    command = ["cmd", "/c", str(launcher / "gradlew.bat"), "--no-daemon", "--no-configuration-cache",
               "--max-workers=1", "-Dorg.gradle.parallel=false", "-Dorg.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8",
               "-Pkotlin.compiler.execution.strategy=in-process", "-PdsAppDir=" + str(companion / "app/src/main"),
               ":app:assembleModernRelease"]
    if args.offline:
        command.insert(3, '--offline')
    print("Building isolated candidate; log:", log, flush=True)
    stamp, inputs = source_stamp(launcher, companion)
    with log.open("w", encoding="utf-8") as output:
        result = subprocess.run(command, cwd=launcher, env=env, stdout=output, stderr=subprocess.STDOUT)
    if result.returncode:
        print("BUILD FAILED; inspect", log)
        return result.returncode
    if source_stamp(launcher, companion)[0] != stamp:
        raise ValueError("Build inputs changed during compilation; retry before freezing an APK")
    candidates = list((launcher / "app/build/outputs/apk/modern/release").glob("*.apk"))
    if len(candidates) != 1:
        raise ValueError("Expected one signed modern release APK")
    apk = out / ('DS-Forever-' + token + '.apk')
    shutil.copy2(candidates[0], apk)
    with zipfile.ZipFile(apk) as archive:
        verify_packaged_components(archive, components)
        for name in ("libcasc.so", "libinteriors.so"):
            if "lib/arm64-v8a/" + name not in archive.namelist():
                raise ValueError("Missing native integration library: " + name)
            if archive.read('lib/arm64-v8a/' + name) != (companion / 'app/src/main/jniLibs/arm64-v8a' / name).read_bytes():
                raise ValueError('Packaged native differs: ' + name)
    check_log = out / (apk.stem + "-zero-art.txt")
    with check_log.open("w", encoding="utf-8") as output:
        check = subprocess.run(["bash", str(companion / "tools/harvest/check_apk_art.sh"),
                                str(apk)], stdout=output, stderr=subprocess.STDOUT)
    if check.returncode:
        print("ZERO-ART FAILED; inspect", check_log)
        return check.returncode
    for gate in ("apk_jni_check", "apk_callback_audit"):
        gate_log = out / (apk.stem + "-" + gate + ".txt")
        with gate_log.open("w", encoding="utf-8") as output:
            command = [sys.executable, "-I", str(companion / "tools/interiors_device" / (gate + ".py")), str(apk)]
            dexdump = str(sdk / 'build-tools/36.1.0/dexdump.exe')
            command += [dexdump] if gate == 'apk_jni_check' else ['--dexdump', dexdump]
            result = subprocess.run(command, stdout=output, stderr=subprocess.STDOUT)
        if result.returncode:
            print("RELEASE CALLBACK GATE FAILED; inspect", gate_log)
            return result.returncode
    badging = subprocess.run([str(aapt), 'dump', 'badging', str(apk)], capture_output=True, text=True, check=True).stdout
    if not re.search("package: name='app.dsforever.companion' versionCode='%d' versionName='%s'" % (code, re.escape(token)), badging):
        raise ValueError('Unexpected package or public version')
    (out / 'badging.txt').write_text(badging, encoding='utf-8')
    signer = sdk / 'build-tools/36.1.0/apksigner.bat'
    signature = subprocess.run(["cmd", "/c", str(signer), "verify", "--print-certs", str(apk)],
                               capture_output=True, text=True)
    if signature.returncode:
        raise ValueError("APK signature verification failed")
    if 'fe226869c3395defedb317eeb6cf6646ea96030d820f3e9f0dee8a3ee4c41947' not in signature.stdout:
        raise ValueError('APK signing identity is not the approved DS certificate')
    (out / (apk.stem + "-signature.txt")).write_text(signature.stdout, encoding="utf-8")
    with apk.open("rb") as stream:
        md5 = hashlib.md5()
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            md5.update(block)
    (out / (apk.stem + "-inputs.json")).write_text(json.dumps(inputs, indent=2) + "\n", encoding="utf-8")
    report = {"apk": str(apk), "bytes": apk.stat().st_size, "md5": md5.hexdigest(), "zero_art": "PASS",
              "build_input_sha256": stamp,
              "signature_verified": True, "bundled_components": components,
              "apk_jni_callbacks": "PASS", "apk_callback_audit": "PASS",
              "sha256": hashlib.sha256(apk.read_bytes()).hexdigest(),
              "versionName": token, "versionCode": code, "release_repo":"Jreese702/DS-Forever",
              "status": "local release-prep artifact; not installed or published"}
    (out / 'SHA256SUMS').write_text(report['sha256'] + '  ' + apk.name + '\n', encoding='ascii')
    (out / (apk.stem + ".json")).write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
