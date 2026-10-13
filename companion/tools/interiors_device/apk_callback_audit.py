"""Audit remaining owned JNI/name-reflection contracts in the shrunk release DEX.

Run alongside Track D's apk_jni_check.py (Source/Yield + every implementor).
Framework-owned java/android members are not app shrinker inputs.
"""
import argparse
import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import zipfile

gate_spec = importlib.util.spec_from_file_location("apk_jni_check", Path(__file__).with_name("apk_jni_check.py"))
gate = importlib.util.module_from_spec(gate_spec)
gate_spec.loader.exec_module(gate)
classes, dexdump_path = gate.classes, gate.dexdump_path


# app/src/main/cpp GetMethodID/GetStaticMethodID, plus RadialMenuCoordinator's
# Class.forName/getMethod tree-owner bridge. Keep signatures exact, not names alone.
CONTRACTS = [
    ("com/jrees/wowcompanion/NativeIndoorGenerator$GameSource", "read", "(I)[B"),
    ("com/winlator/xconnector/XConnectorEpoll", "handleNewConnection", "(I)V"),
    ("com/winlator/xconnector/XConnectorEpoll", "handleExistingConnection", "(I)V"),
    ("com/winlator/xconnector/ClientSocket", "addAncillaryFd", "(I)V"),
    ("com/winlator/renderer/AHBImage", "setSwapchainFence", "(II)V"),
    ("com/winlator/renderer/AHBImage", "setStride", "(S)V"),
    ("com/winlator/renderer/GPUImage", "setStride", "(S)V"),
    ("com/winlator/renderer/ASurfaceRenderer", "onScanoutFrameComplete", "(J)V"),
    ("com/winlator/xenvironment/components/VirGLRendererComponent", "killConnection", "(I)V"),
    ("com/winlator/xenvironment/components/VirGLRendererComponent", "getSharedEGLContext", "()J"),
    ("com/winlator/xenvironment/components/VirGLRendererComponent", "flushFrontbuffer", "(II)V"),
    ("com/winlator/core/AppUtils", "getNativeLibDir", "(Landroid/content/Context;)Ljava/lang/String;"),
    ("com/winlator/contents/AdrenotoolsManager", "<init>", "(Landroid/content/Context;)V"),
    ("com/winlator/contents/AdrenotoolsManager", "getLibraryName", "(Ljava/lang/String;)Ljava/lang/String;"),
]
for owner, result in (
    ("androidx/lifecycle/ViewTreeLifecycleOwner", "androidx/lifecycle/LifecycleOwner"),
    ("androidx/lifecycle/ViewTreeViewModelStoreOwner", "androidx/lifecycle/ViewModelStoreOwner"),
    ("androidx/savedstate/ViewTreeSavedStateRegistryOwner", "androidx/savedstate/SavedStateRegistryOwner"),
):
    CONTRACTS += [(owner, "get", "(Landroid/view/View;)L" + result + ";"),
                  (owner, "set", "(Landroid/view/View;L" + result + ";)V")]

NATIVE_ENTRIES = [
    ("CascNative", "open", "(Ljava/lang/String;Ljava/lang/String;I)J"),
    ("CascNative", "read", "(JI)[B"), ("CascNative", "readDb2", "(JI)[B"),
    ("CascNative", "readName", "(JLjava/lang/String;)[B"),
    ("CascNative", "lastStatus", "()I"), ("CascNative", "lastReadError", "()I"),
    ("CascNative", "addEncryptionKey", "(JJ[B)Z"), ("CascNative", "lastOpenError", "()I"),
    ("CascNative", "setDownloadRoot", "(Ljava/lang/String;)V"),
    ("CascNative", "takeNeed", "()Ljava/lang/String;"), ("CascNative", "close", "(J)V"),
    ("IndoorGeneratorCasc", "contentKey", "(JI)[B"),
    ("IndoorGeneratorNative", "generate", "(Ljava/lang/String;Lcom/jrees/wowcompanion/IndoorGeneratorNative$Source;Lcom/jrees/wowcompanion/IndoorGenerator$Yield;Ljava/lang/String;)Ljava/lang/String;"),
    ("IndoorGeneratorNative", "units", "([BIIDD)Ljava/lang/String;"),
    ("IndoorGeneratorNative", "inputs", "([BI[IIIZ)[I"),
    ("IndoorGeneratorNative", "placements", "([BZ)Ljava/lang/String;"),
    ("IndoorGeneratorNative", "objectTiles", "([B)[I"),
]
CONTRACTS += [("com/jrees/wowcompanion/" + owner, name, sig) for owner, name, sig in NATIVE_ENTRIES]
# version() is a host-test diagnostic, unused on Android, not a Java callback.
# Its removal is allowed; every production native entry is required above.


def check_contracts(found):
    rows = []
    for owner, name, sig in CONTRACTS:
        declared = found.get("L" + owner + ";")
        ok = declared is not None and (name, sig) in declared["methods"]
        rows.append({"class": owner, "method": name, "signature": sig, "present": ok})
    return rows


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--report", type=Path)
    parser.add_argument("--dexdump", type=Path)
    args = parser.parse_args()
    found = {}
    dd = dexdump_path(str(args.dexdump) if args.dexdump else None)
    with tempfile.TemporaryDirectory(prefix="wc-callback-audit-") as folder, zipfile.ZipFile(args.apk) as archive:
        dexes = [name for name in archive.namelist() if name.endswith(".dex")]
        if not dexes:
            raise ValueError("Release APK has no DEX files")
        for name in dexes:
            path = Path(folder) / Path(name).name
            path.write_bytes(archive.read(name))
            dump = subprocess.run([dd, str(path)], capture_output=True, text=True, errors="replace", check=True)
            parsed = classes(dump.stdout)
            if not parsed:
                raise ValueError("dexdump produced no class declarations")
            found.update(parsed)
    rows = check_contracts(found)
    for row in rows:
        print(("PASS " if row["present"] else "FAIL ") + row["class"] + "." + row["method"] + row["signature"])
    misses = sum(not row["present"] for row in rows)
    print(("PASS" if not misses else "FAIL") + " APK callback audit: %d contracts, %d misses" % (len(rows), misses))
    if args.report:
        args.report.write_text(json.dumps({"apk": str(args.apk), "contracts": rows, "misses": misses}, indent=2) + "\n")
    return bool(misses)


if __name__ == "__main__":
    raise SystemExit(main())
