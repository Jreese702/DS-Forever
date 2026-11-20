#!/usr/bin/env python3
"""Removes the 32-bit (i386) Windows files from the bundled Proton package.

Ported from WoW Forever for Android v2.4.4, commit 55b0bd23 "Ship Proton without its 32-bit Windows files" (issue #89;
jaredgei/wow-forever-android, GPL-3.0). DS Forever runs only the native ARM64 client (Wow-arm64.exe, -d3d11), so Proton's
32-bit Windows half is never used. It is also what most antivirus engines flag as a generic heuristic hit, and it is about
50 MB of the APK. This rewrites the .wcp:

- lib/wine/i386-windows keeps its directory entry but loses its files.
- prefixPack.txz loses every 32-bit PE file (machine 0x14c), e.g. those in drive_c/windows/syswow64.

Everything else is copied entry by entry with the same order, permissions and links. DS Forever additions to upstream's script:
it refuses to drop a file another kept entry hard-links to; it is a no-op on a package that is already trimmed; it uses the
zstandard Python module when there is no zstd command (Windows); --out writes elsewhere instead of in place.

The input is the pinned original (tools/components.sha256, unchanged); the output is pinned in tools/components-trimmed.sha256,
which tools/build_release.py checks the release APK against.

Usage: python tools/trim_proton_i386.py <proton.wcp> [--out <trimmed.wcp>]
"""
import argparse
import io
import os
import shutil
import struct
import subprocess
import sys
import tarfile
import tempfile

I386_DIR = "lib/wine/i386-windows/"
IMAGE_FILE_MACHINE_I386 = 0x014C
ZSTD_LEVEL = 19


def is_i386_pe(data: bytes) -> bool:
    if len(data) < 0x40 or data[:2] != b"MZ":
        return False
    offset = struct.unpack_from("<I", data, 0x3C)[0]
    if offset + 6 > len(data) or data[offset:offset + 4] != b"PE\0\0":
        return False
    return struct.unpack_from("<H", data, offset + 4)[0] == IMAGE_FILE_MACHINE_I386


def zstd_decompress(path: str) -> bytes:
    try:
        import zstandard
    except ImportError:
        zstandard = None
    if zstandard is not None:
        with open(path, "rb") as source:
            return zstandard.ZstdDecompressor().stream_reader(source).read()
    if shutil.which("zstd"):
        return subprocess.run(["zstd", "-dc", path], check=True, stdout=subprocess.PIPE).stdout
    sys.exit("Needs the zstandard Python module (pip install zstandard) or the zstd command")


def zstd_compress(data: bytes, path: str) -> None:
    try:
        import zstandard
    except ImportError:
        zstandard = None
    if zstandard is not None:
        compressed = zstandard.ZstdCompressor(level=ZSTD_LEVEL, write_checksum=True, write_content_size=True).compress(data)
        with open(path, "wb") as target:
            target.write(compressed)
        return
    with tempfile.NamedTemporaryFile(dir=os.path.dirname(os.path.abspath(path)), delete=False) as temporary:
        temporary.write(data)
    try:
        subprocess.run(["zstd", "-q", "-f", f"-{ZSTD_LEVEL}", temporary.name, "-o", path], check=True)
    finally:
        os.unlink(temporary.name)


def check_links(kept, removed, where):
    """A kept hard link whose target is dropped would fail to extract: refuse instead."""
    for member in kept:
        if member.islnk() and member.linkname.lstrip("./") in removed:
            sys.exit(f"{where}: {member.name} hard-links to the dropped {member.linkname}; not trimming")


def trim_prefix_pack(data: bytes) -> tuple:
    removed = set()
    entries = []
    with tarfile.open(fileobj=io.BytesIO(data), mode="r:xz") as source:
        fmt = source.format
        for member in source:
            payload = source.extractfile(member).read() if member.isfile() else None
            if payload is not None and is_i386_pe(payload):
                removed.add(member.name.lstrip("./"))
                continue
            entries.append((member, payload))
    if not removed:
        return data, 0
    check_links([m for m, _ in entries], removed, "prefixPack.txz")
    output = io.BytesIO()
    with tarfile.open(fileobj=output, mode="w:xz", format=fmt) as target:
        for member, payload in entries:
            target.addfile(member, io.BytesIO(payload) if payload is not None else None)
    return output.getvalue(), len(removed)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("wcp")
    parser.add_argument("--out", help="write the trimmed package here (default: rewrite the input in place)")
    args = parser.parse_args()
    raw = zstd_decompress(args.wcp)
    removed = set()
    entries = []
    removed_prefix = 0
    with tarfile.open(fileobj=io.BytesIO(raw), mode="r:") as source:
        fmt = source.format
        for member in source:
            name = member.name.lstrip("./")
            if name.startswith(I386_DIR) and not member.isdir():
                removed.add(name)
                continue
            payload = source.extractfile(member).read() if member.isfile() else None
            if name == "prefixPack.txz":
                payload, removed_prefix = trim_prefix_pack(payload)
                member.size = len(payload)
            entries.append((member, payload))
    out = args.out or args.wcp
    if not removed and not removed_prefix:
        print("Already trimmed: no 32-bit Windows files left")
        if args.out:
            shutil.copyfile(args.wcp, args.out)
        return
    check_links([m for m, _ in entries], removed, os.path.basename(args.wcp))
    output = io.BytesIO()
    with tarfile.open(fileobj=output, mode="w:", format=fmt) as target:
        for member, payload in entries:
            target.addfile(member, io.BytesIO(payload) if payload is not None else None)
    directory = os.path.dirname(os.path.abspath(out))
    temporary = os.path.join(directory, os.path.basename(out) + ".trim-part")
    zstd_compress(output.getvalue(), temporary)
    os.replace(temporary, out)
    os.chmod(out, 0o644)
    print(f"Removed {len(removed)} files from {I386_DIR} and {removed_prefix} 32-bit PE files from prefixPack.txz")


if __name__ == "__main__":
    main()
