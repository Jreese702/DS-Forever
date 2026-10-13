"""Track D: check the RELEASE APK (after R8) still has every Java method the native code looks up by name.

T3 (2026-10-13) found every building deferred with 'no non-static method ...NativeIndoorGenerator$$ExternalSyntheticLambda1;.read(I)[B':
R8 had removed IndoorGeneratorNative$Source.read (nothing in Java calls it; libinteriors does, through GetMethodID), so the lambda
R8 synthesized implements Source but has no read. Every source-level test passed, because none runs the shrunk dex.

  python -I apk_jni_check.py <apk> [dexdump.exe]
For each (interface, method, signature) the native code calls through GetMethodID: the interface declares it, and EVERY class in
the dex that implements the interface has it. Exit 1 on any miss.
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
]


def dexdump_path(arg):
    if arg:
        return arg
    found = sorted(glob.glob(os.path.expanduser("~/AppData/Local/Android/Sdk/build-tools/*/dexdump.exe")))
    if not found:
        raise SystemExit("dexdump not found; pass its path")
    return found[-1]


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
    print(("PASS" if fails == 0 else "FAIL") + " APK JNI callbacks: %d dex files, %d classes, %d misses" % (len(dexes), len(allc), fails))
    return 1 if fails else 0


if __name__ == "__main__":
    sys.exit(main())
