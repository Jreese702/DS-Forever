"""Track D: check the RELEASE APK (after R8) still has every Java method the native code looks up by name.

T3 (2026-10-13) found every building deferred with 'no non-static method ...NativeIndoorGenerator$$ExternalSyntheticLambda1;.read(I)[B':
R8 had removed IndoorGeneratorNative$Source.read (nothing in Java calls it; libinteriors does, through GetMethodID), so the lambda
R8 synthesized implements Source but has no read. Every source-level test passed, because none runs the shrunk dex.

  python -I apk_jni_check.py <apk> [dexdump.exe]
For each (interface, method, signature) the native code calls through GetMethodID: the interface declares it, and EVERY class in
the dex that implements the interface has it. Also: each native library in LIBS is in the APK (arm64-v8a) and exports its JNI
names (the NDK's llvm-nm), so a release without libdsfwhisper.so, or with names that no longer match WhisperNative, fails. Exit 1 on
any miss.
"""
import glob
import os
import re
import subprocess
import sys
import tempfile
import zipfile

# what libinteriors calls through GetMethodID (tools/interiors_native/jni/interiors_jni.cpp)
CALLBACKS = [
    ("Lcom/jrees/wowcompanion/IndoorGeneratorNative$Source;", "read", "(I)[B"),
    ("Lcom/jrees/wowcompanion/IndoorGenerator$Yield;", "point", "()V"),
    # the streaming core (dsforever/src/main/jni/dsf/dsf_bridge.c) calls these statics by name (stream1: R8 had dropped them)
    ("Lcom/jrees/wowcompanion/stream/MoonBridge;", "bridgeDrSetup", "(IIII)I"),
    ("Lcom/jrees/wowcompanion/stream/MoonBridge;", "bridgeDrStart", "()V"),
    ("Lcom/jrees/wowcompanion/stream/MoonBridge;", "bridgeDrStop", "()V"),
    ("Lcom/jrees/wowcompanion/stream/MoonBridge;", "bridgeDrCleanup", "()V"),
    ("Lcom/jrees/wowcompanion/stream/MoonBridge;", "bridgeDrSubmitDecodeUnit", "([BIIIIJ)I"),
    ("Lcom/jrees/wowcompanion/stream/MoonBridge;", "bridgeArInit", "(III)I"),
    ("Lcom/jrees/wowcompanion/stream/MoonBridge;", "bridgeArStart", "()V"),
    ("Lcom/jrees/wowcompanion/stream/MoonBridge;", "bridgeArStop", "()V"),
    ("Lcom/jrees/wowcompanion/stream/MoonBridge;", "bridgeArCleanup", "()V"),
    ("Lcom/jrees/wowcompanion/stream/MoonBridge;", "bridgeArPlaySample", "([SI)V"),
    ("Lcom/jrees/wowcompanion/stream/MoonBridge;", "bridgeClStageStarting", "(I)V"),
    ("Lcom/jrees/wowcompanion/stream/MoonBridge;", "bridgeClStageComplete", "(I)V"),
    ("Lcom/jrees/wowcompanion/stream/MoonBridge;", "bridgeClStageFailed", "(II)V"),
    ("Lcom/jrees/wowcompanion/stream/MoonBridge;", "bridgeClConnectionStarted", "()V"),
    ("Lcom/jrees/wowcompanion/stream/MoonBridge;", "bridgeClConnectionTerminated", "(I)V"),
    ("Lcom/jrees/wowcompanion/stream/MoonBridge;", "bridgeClRumble", "(SSS)V"),
    ("Lcom/jrees/wowcompanion/stream/MoonBridge;", "bridgeClConnectionStatusUpdate", "(I)V"),
    # offline voice typing: libdsfwhisper.so binds these natives by their JNI names (companion/tools/whisper_native/jni/whisper_jni.cpp)
    ("Lcom/jrees/wowcompanion/WhisperNative;", "version", "()Ljava/lang/String;"),
    ("Lcom/jrees/wowcompanion/WhisperNative;", "load", "(Ljava/lang/String;)J"),
    ("Lcom/jrees/wowcompanion/WhisperNative;", "transcribe", "(J[SILjava/lang/String;ILjava/lang/String;)Ljava/lang/String;"),
    ("Lcom/jrees/wowcompanion/WhisperNative;", "abort", "(J)V"),
    ("Lcom/jrees/wowcompanion/WhisperNative;", "free", "(J)V"),
    # test build 1489 (#56): libdsf-heap.so binds NativeHeap.purge by its JNI name (dsforever/src/main/jni/dsf/dsf_heap.c)
    ("Lcom/jrees/wowcompanion/NativeHeap;", "purge", "()I"),
    # 2026-11-23 (PageOut): our own idle memory into zram and the Wine helpers' request (dsf_heap.c)
    ("Lcom/jrees/wowcompanion/NativeHeap;", "pageOutSelf", "(I)[J"),
    ("Lcom/jrees/wowcompanion/NativeHeap;", "pageOutSignal", "(I)I"),
]

# native libraries the APK must carry for arm64-v8a, each with the JNI symbols it must define (llvm-nm -D --defined-only)
LIBS = [
    ("lib/arm64-v8a/libdsfwhisper.so", ["Java_com_jrees_wowcompanion_WhisperNative_" + n for n in
                                        ("version", "load", "transcribe", "abort", "free")]),
    ("lib/arm64-v8a/libdsf-heap.so", ["Java_com_jrees_wowcompanion_NativeHeap_" + n for n in ("purge", "pageOutSelf", "pageOutSignal")]),
    # the Wine helpers' preload (PageOut, BionicProgramLauncherComponent): no JNI, only present
    ("lib/arm64-v8a/libdsf-pageout.so", []),
]


def dexdump_path(arg):
    if arg:
        return arg
    found = sorted(glob.glob(os.path.expanduser("~/AppData/Local/Android/Sdk/build-tools/*/dexdump.exe")))
    if not found:
        raise SystemExit("dexdump not found; pass its path")
    return found[-1]


def llvm_nm():
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT") or os.path.expanduser("~/AppData/Local/Android/Sdk")
    found = sorted(glob.glob(os.path.join(sdk, "ndk", "*", "toolchains", "llvm", "prebuilt", "*", "bin", "llvm-nm*")))
    return found[-1] if found else None


def check_libs(apk, work):
    """each LIBS entry is in the APK and defines its JNI symbols; the count of misses"""
    fails = 0
    nm = llvm_nm()
    with zipfile.ZipFile(apk) as z:
        names = set(z.namelist())
        for path, syms in LIBS:
            if path not in names:
                print("FAIL %s is not in the APK" % path)
                fails += 1
                continue
            z.extract(path, work)
            print("PASS %s in the APK (%d bytes)" % (path, z.getinfo(path).file_size))
            if not nm:
                print("FAIL no llvm-nm (the NDK) to read %s's symbols" % path)
                fails += 1
                continue
            out = subprocess.run([nm, "-D", "--defined-only", os.path.join(work, path)], capture_output=True, text=True,
                                 errors="replace").stdout
            have = {ln.split()[-1] for ln in out.splitlines() if ln.strip()}
            for sym in syms:
                ok = sym in have
                print(("PASS " if ok else "FAIL ") + "  %s defines %s" % (os.path.basename(path), sym))
                fails += not ok
    return fails


def classes(text):
    """{descriptor: {"interfaces": [...], "methods": {(name, type)}}} from dexdump's text output"""
    out, cur, section = {}, None, None
    for line in text.splitlines():
        m = re.match(r"\s*Class descriptor\s*: '(.*)'", line)
        if m:
            cur = {"interfaces": [], "methods": set()}
            out[m.group(1)] = cur
            section = None
            continue
        if cur is None:
            continue
        if re.match(r"\s*Interfaces\s*-", line):
            section = "if"; continue
        if re.match(r"\s*(Static fields|Instance fields)\s*-", line):
            section = "fields"; continue
        if re.match(r"\s*(Direct methods|Virtual methods)\s*-", line):
            section = "methods"; continue
        if section == "if":
            m = re.match(r"\s*#\d+\s*: '(L.*;)'", line)
            if m:
                cur["interfaces"].append(m.group(1))
        elif section == "methods":
            m = re.match(r"\s*name\s*: '(.*)'", line)
            if m:
                cur["_name"] = m.group(1)
            m = re.match(r"\s*type\s*: '(.*)'", line)
            if m and "_name" in cur:
                cur["methods"].add((cur.pop("_name"), m.group(1)))
    return out


def main():
    apk = sys.argv[1]
    dd = dexdump_path(sys.argv[2] if len(sys.argv) > 2 else None)
    work = tempfile.mkdtemp(prefix="wc-apk-jni-")
    with zipfile.ZipFile(apk) as z:
        dexes = [n for n in z.namelist() if re.match(r"classes\d*\.dex$", n)]
        for n in dexes:
            z.extract(n, work)
    allc = {}
    for n in dexes:
        allc.update(classes(subprocess.run([dd, os.path.join(work, n)], capture_output=True, text=True, errors="replace").stdout))
    fails = 0
    for iface, name, sig in CALLBACKS:
        decl = allc.get(iface)
        ok = decl is not None and (name, sig) in decl["methods"]
        print(("PASS " if ok else "FAIL ") + "%s declares %s%s" % (iface, name, sig))
        fails += not ok
        impls = [c for c, v in allc.items() if iface in v["interfaces"]]
        for c in impls:
            ok = (name, sig) in allc[c]["methods"]
            print(("PASS " if ok else "FAIL ") + "  %s implements %s%s" % (c, name, sig))
            fails += not ok
    fails += check_libs(apk, work)
    print(("PASS" if fails == 0 else "FAIL") + " APK JNI callbacks: %d dex files, %d classes, %d misses" % (len(dexes), len(allc), fails))
    return 1 if fails else 0


if __name__ == "__main__":
    sys.exit(main())
