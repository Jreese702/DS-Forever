"""Apply the DS Forever loading-hook (vk_icd export) change to a Mesa tree.
Usage: python3 -I mkpatch.py <mesa_src_dir>
"""
import io, os, sys

src = sys.argv[1]
NL = "\n"
TAB = "\t"
SYMS = ["vk_icdGetInstanceProcAddr",
        "vk_icdGetPhysicalDeviceProcAddr",
        "vk_icdNegotiateLoaderICDInterfaceVersion"]

p = os.path.join(src, "src/vulkan/vulkan-android.sym")
s = io.open(p, encoding="utf-8").read()
anchor = TAB + TAB + "HMI;" + NL
if s.count(anchor) != 1:
    sys.exit("anchor HMI; not found exactly once in vulkan-android.sym")
if "vk_icdGetInstanceProcAddr" in s:
    sys.exit("vulkan-android.sym already exports vk_icd*")
add = NL + TAB + TAB + "# DS Forever: the Vulkan wrapper ICD loads this .so directly and" + NL \
    + TAB + TAB + "# needs the standard loader-ICD entrypoints too." + NL \
    + "".join(TAB + TAB + n + ";" + NL for n in SYMS)
s = s.replace(anchor, anchor + add, 1)
io.open(p, "w", encoding="utf-8", newline=NL).write(s)

p = os.path.join(src, "src/vulkan/vulkan-icd-android-symbols.txt")
s = io.open(p, encoding="utf-8").read()
if not s.endswith(NL):
    s += NL
s += NL + "# DS Forever: loader-ICD entrypoints for the Vulkan wrapper ICD." + NL \
    + "".join(n + NL for n in SYMS)
io.open(p, "w", encoding="utf-8", newline=NL).write(s)
print("loading-hook patch applied")
