"""Off-device check of tools/trim_proton_i386.py on a small made-up Proton package (needs the zstandard module).

Usage: python tools/test_trim_proton_i386.py
"""
import io
import os
import struct
import subprocess
import sys
import tarfile
import tempfile

import zstandard

TRIM = os.path.join(os.path.dirname(os.path.abspath(__file__)), "trim_proton_i386.py")


def pe(machine: int) -> bytes:
    data = bytearray(0x100)
    data[:2] = b"MZ"
    struct.pack_into("<I", data, 0x3C, 0x80)
    data[0x80:0x84] = b"PE\0\0"
    struct.pack_into("<H", data, 0x84, machine)
    return bytes(data)


def tar(entries, mode):
    out = io.BytesIO()
    with tarfile.open(fileobj=out, mode=mode) as t:
        for name, kind, payload in entries:
            info = tarfile.TarInfo(name)
            if kind == "dir":
                info.type = tarfile.DIRTYPE
                info.mode = 0o755
                t.addfile(info)
            elif kind == "link":
                info.type = tarfile.LNKTYPE
                info.linkname = payload
                t.addfile(info)
            else:
                info.size = len(payload)
                info.mode = 0o755
                t.addfile(info, io.BytesIO(payload))
    return out.getvalue()


def names(path):
    with open(path, "rb") as source:
        raw = zstandard.ZstdDecompressor().stream_reader(source).read()
    outer, prefix = [], []
    with tarfile.open(fileobj=io.BytesIO(raw), mode="r:") as t:
        for m in t:
            outer.append(m.name)
            if m.name == "prefixPack.txz":
                with tarfile.open(fileobj=io.BytesIO(t.extractfile(m).read()), mode="r:xz") as p:
                    prefix = [x.name for x in p]
    return outer, prefix


def write_wcp(path, outer):
    with open(path, "wb") as target:
        target.write(zstandard.ZstdCompressor(level=3).compress(tar(outer, "w:")))


def run(*args):
    return subprocess.run([sys.executable, TRIM, *args], capture_output=True, text=True)


def main():
    with tempfile.TemporaryDirectory() as folder:
        prefix = tar([(".wine/drive_c/windows/syswow64", "dir", None),
                      (".wine/drive_c/windows/syswow64/kernel32.dll", "file", pe(0x14C)),
                      (".wine/drive_c/windows/system32/kernel32.dll", "file", pe(0xAA64)),
                      (".wine/drive_c/windows/system32/readme.txt", "file", b"text")], "w:xz")
        wcp = os.path.join(folder, "proton.wcp")
        write_wcp(wcp, [("profile.json", "file", b"{}"), ("lib/wine/i386-windows", "dir", None),
                        ("lib/wine/i386-windows/kernel32.dll", "file", pe(0x14C)),
                        ("lib/wine/aarch64-windows/kernel32.dll", "file", pe(0xAA64)),
                        ("prefixPack.txz", "file", prefix)])
        result = run(wcp)
        assert result.returncode == 0, result.stderr
        assert "Removed 1 files" in result.stdout and "1 32-bit PE" in result.stdout, result.stdout
        outer, inner = names(wcp)
        assert outer == ["profile.json", "lib/wine/i386-windows", "lib/wine/aarch64-windows/kernel32.dll", "prefixPack.txz"], outer
        assert inner == [".wine/drive_c/windows/syswow64", ".wine/drive_c/windows/system32/kernel32.dll",
                         ".wine/drive_c/windows/system32/readme.txt"], inner
        # a second run changes nothing
        before = open(wcp, "rb").read()
        again = run(wcp)
        assert again.returncode == 0 and "Already trimmed" in again.stdout, again.stdout
        assert open(wcp, "rb").read() == before
        # --out leaves the input alone
        copy = os.path.join(folder, "copy.wcp")
        assert run(wcp, "--out", copy).returncode == 0 and open(copy, "rb").read() == before
        # a kept hard link to a dropped file: refused, the package untouched
        linked = os.path.join(folder, "linked.wcp")
        write_wcp(linked, [("lib/wine/i386-windows/a.dll", "file", pe(0x14C)), ("lib/other/a.dll", "link", "lib/wine/i386-windows/a.dll")])
        original = open(linked, "rb").read()
        refused = run(linked)
        assert refused.returncode != 0 and "hard-links" in (refused.stderr + refused.stdout), refused.stderr
        assert open(linked, "rb").read() == original
    print("trim_proton_i386: all checks passed")


if __name__ == "__main__":
    main()
