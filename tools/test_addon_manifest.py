"""1521 integration (add-on set 424): the add-on manifest matches the add-on files byte for byte, off-device.
- manifest.txt's md5s are the REAL files' (LauncherSetup only copies a set whose md5s match; build_release refuses a stale one);
- every add-on .toc says "Set N" for manifest.txt's set N, and the set is 424 or higher (1.0.38 shipped 423 with older files);
- the tags the 1521 merges carry (and the 1523 merges'): Hub hub75 -> hub76, PadMap m186 -> m188 (stream smoothness, the LT / RT macro fixes, feat/fight-castbar's m187 / m188), Interact i173, Bags bags-kit6 (its notes; Bags has no tag line).
No lupa needed. Exit code 1 on any failure."""
import hashlib
import os
import pathlib
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import build_release      # noqa: E402

FAILS = []


def check(ok, what):
    print(("PASS " if ok else "FAIL ") + what)
    if not ok:
        FAILS.append(what)


COMPANION = pathlib.Path(HERE).parent / "companion"
ROOT = COMPANION / "app/src/main/assets/companion"
ADDONS = ROOT / "addons"

try:
    build_release.verify_addon_manifest(COMPANION)
    check(True, "a. build_release's manifest check passes (md5s and .toc labels)")
except ValueError as e:
    check(False, "a. build_release's manifest check: %s" % e)

lines = (ROOT / "manifest.txt").read_bytes().decode("ascii").split("\n")
m = re.fullmatch(r"set (\d+)", lines[0])
check(m is not None and int(m[1]) >= 424, "b. the set is 424 or higher (%s)" % lines[0])
check(b"\r" not in (ROOT / "manifest.txt").read_bytes(), "b. manifest.txt stays LF")

listed = {}
for line in lines[1:]:
    if line:
        md5, path = line.split("  ", 1)
        listed[path] = md5
for rel in ("WowCompanion/Bags.lua", "WowCompanionHub/Hub.lua", "WowCompanionInteract/Interact.lua", "WowCompanionSpells/PadMap.lua"):
    check(listed.get(rel) == hashlib.md5((ADDONS / rel).read_bytes()).hexdigest(), "c. %s's md5 is listed" % rel)


def src(rel):
    return (ADDONS / rel).read_text(encoding="utf-8")


check('local HV = "hub77"' in src("WowCompanionHub/Hub.lua"), "d. Hub hub77 (the 1523 merge of both hub76s)")
check('local PV = "m189"' in src("WowCompanionSpells/PadMap.lua"), "d. PadMap m189 (the 1523 merge of the m187s / m188s)")
check('local IV = "i173"' in src("WowCompanionInteract/Interact.lua"), "d. Interact i173")
check("-- bags-kit6 " in src("WowCompanion/Bags.lua"), "d. Bags bags-kit6")

print("\n%d failure(s)" % len(FAILS))
sys.exit(1 if FAILS else 0)
