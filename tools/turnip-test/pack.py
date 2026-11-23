"""Package the Mesa 26.3.1 Turnip test driver in DS Forever's driver-zip layout (tools/turnip-test/build_mesa.sh makes the input).
Run: python -I tools/turnip-test/pack.py <input dir: libvulkan_freedreno.so, the diff, licenses/> <output dir>
Layout matches turnip-wow-scheduler-test.zip: flat entries, meta.json with
name / driverVersion / libraryName (read by AdrenotoolsManager), licence texts.
The entries' times are fixed, so the same input gives the same zip (pinned in tools/components-test-drivers.sha256).
"""
import hashlib, json, os, sys, zipfile

B = sys.argv[1]
OUT = sys.argv[2]
ZIP = os.path.join(OUT, "turnip-test-mesa-26.3.1.zip")
DT = (2026, 11, 23, 0, 0, 0)

meta = {
    "schemaVersion": 1,
    "name": "Turnip-Test-Mesa-26.3.1",
    "description": "OPT-IN TEST: official Mesa 26.3.1 Turnip (KGSL), upstream IR3 scheduler fix 2f8dc9eaba06 included; only local change exports vk_icd* loader entrypoints. Not yet validated on Thor.",
    "author": "Mesa contributors; DS Forever local build (vk_icd export patch only)",
    "packageVersion": "1",
    "vendor": "Mesa",
    "driverVersion": "26.3.1-dsf-icd",
    "minApi": 29,
    "libraryName": "libvulkan_freedreno.so",
}

so = os.path.join(B, "libvulkan_freedreno.so")
diff = os.path.join(B, "0001-android-export-vk_icd-entrypoints.diff")
so_sha = hashlib.sha256(open(so, "rb").read()).hexdigest()

source_txt = f"""Turnip-Test-Mesa-26.3.1 - source and build record

Source: official Mesa 26.3.1 release tarball
  https://archive.mesa3d.org/mesa-26.3.1.tar.xz
  sha256 4e4e438eac7b840a9383988cbb7c08dad8041da544b9319fc26d319fa7348961
  (matches the SHA256 published in docs.mesa3d.org/relnotes/26.3.1.html)
Local change: 0001-android-export-vk_icd-entrypoints.diff (in this zip).
  Exports vk_icdGetInstanceProcAddr, vk_icdGetPhysicalDeviceProcAddr and
  vk_icdNegotiateLoaderICDInterfaceVersion next to HMI. No other source change.
  The IR3 scheduler fix is upstream (2f8dc9eaba06) and is NOT re-applied.
Toolchain: Android NDK r27c (27.2.12479018), clang 18.0.3, target
  aarch64-linux-android29, meson 1.10.1, ninja 1.13.2, WSL Ubuntu 26.04.
Meson options: --wrap-mode=nofallback -Dbuildtype=release -Db_lto=false
  -Dstrip=true -Dplatforms=android -Dplatform-sdk-version=29 -Dandroid-stub=true
  -Dandroid-libbacktrace=disabled -Dgallium-drivers= -Dvulkan-drivers=freedreno
  -Dvulkan-beta=true -Dfreedreno-kmds=kgsl -Dopengl=false -Degl=disabled
  -Dgles1=disabled -Dgles2=disabled -Dglx=disabled -Dgbm=disabled
  -Dllvm=disabled -Dxmlconfig=disabled -Dzstd=disabled -Dvalgrind=disabled
  -Dlibunwind=disabled -Dbuild-tests=false -Dwerror=false
libvulkan_freedreno.so sha256 {so_sha}
Licences: Mesa is mostly MIT; individual files carry their own SPDX terms
  (texts included: MIT, Apache-2.0, BSL-1.0, SGI-B-2.0, GPL-2.0-only,
  GPL-1.0-or-later). See mesa-docs-license.rst.
"""

os.makedirs(OUT, exist_ok=True)
if os.path.exists(ZIP):
    os.remove(ZIP)

def add(z, name, data):
    zi = zipfile.ZipInfo(name, DT)
    zi.compress_type = zipfile.ZIP_DEFLATED
    zi.external_attr = 0o644 << 16
    z.writestr(zi, data, compresslevel=9)

with zipfile.ZipFile(ZIP, "w") as z:
    add(z, "libvulkan_freedreno.so", open(so, "rb").read())
    add(z, "meta.json", json.dumps(meta, indent=2) + "\n")
    lic = os.path.join(B, "licenses")
    for f in sorted(os.listdir(lic)):
        add(z, f, open(os.path.join(lic, f), "rb").read())
    add(z, "0001-android-export-vk_icd-entrypoints.diff", open(diff, "rb").read())
    add(z, "SOURCE.txt", source_txt)

zsha = hashlib.sha256(open(ZIP, "rb").read()).hexdigest()
print(ZIP, os.path.getsize(ZIP), zsha)
print("so", os.path.getsize(so), so_sha)
