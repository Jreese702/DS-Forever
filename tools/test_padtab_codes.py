"""THE PADTAB ONE-SHOT CODES' REGISTRY (movable Home, SPEC.txt section 12: "a new registry test asserts no collision; pick final numbers at
build"), off-device, text only (no Lua needed):

    python -B tools/test_padtab_codes.py

Book.lua's second page (padtab 39, 38 then N: the table P2) and third page (padtab 39, 38, 37 then N: the table P3) are read; proven:
  1 no code of either page is defined twice (a loop's range and a single assignment never meet);
  2 every code lies on the pad's 40 columns (0..39);
  3 the app's registry (PadMapModel.PAGE3_CODES) names every third-page code Book defines, and nothing Book does not define;
  4 the app's own third-page sends (PadMapModel.P3_* constants) are registered codes;
  5 PHASE 2 (edit mode) adds no code: edit mode is app-only; its only add-on contact is Save's mirror bit. The 1515 merge (Book b76): the
    sticky realm question keeps 9 / 10-19 (test build 1515's), Home's mirror bit moves to 20 / 21 and the top-screen combat bar to 22 / 23.
Exit code 1 on any failure."""
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BOOK = os.path.join(ROOT, "companion", "app", "src", "main", "assets", "companion", "addons", "WowCompanionSpells", "Book.lua")
PADMAP = os.path.join(ROOT, "companion", "app", "src", "main", "java", "com", "jrees", "wowcompanion", "PadMapModel.kt")
fails = 0


def check(ok, what):
    global fails
    print(("ok   " if ok else "FAIL ") + what)
    if not ok:
        fails += 1


def codes(src, table):
    """every code assigned in [table] (P2 / P3) by Book.lua: `T[n] =`, `T[a + k] =` / `T[k] =` inside `for k = x, y do`"""
    out = []
    for line in src.splitlines():
        code = line.split("--", 1)[0]
        loop = re.search(r"for\s+(\w+)\s*=\s*(\d+)\s*,\s*(\d+)\s+do\b(.*)", code)
        if loop:
            var, lo, hi, body = loop.group(1), int(loop.group(2)), int(loop.group(3)), loop.group(4)
            for m in re.finditer(r"\b%s\[\s*(?:(\d+)\s*\+\s*)?%s\s*\]\s*=" % (table, var), body):
                base = int(m.group(1) or 0)
                out += [base + k for k in range(lo, hi + 1)]
            continue
        for m in re.finditer(r"\b%s\[\s*(\d+)\s*\]\s*=" % table, code):
            out.append(int(m.group(1)))
    return out


book = open(BOOK, encoding="utf-8").read()
kt = open(PADMAP, encoding="utf-8").read()

p2 = codes(book, "P2")
p3 = codes(book, "P3")
check(len(p2) > 0 and len(p3) > 0, "Book.lua defines the second and the third page (%d / %d codes)" % (len(p2), len(p3)))
for name, cs in (("P2", p2), ("P3", p3)):
    dup = sorted({c for c in cs if cs.count(c) > 1})
    check(not dup, "%s: no code defined twice %s" % (name, dup or ""))
    check(all(0 <= c <= 39 for c in cs), "%s: every code on the pad's 40 columns" % name)

# the app's registry of the third page
reg = re.search(r"val PAGE3_CODES[^=]*=\s*mapOf\((.*?)\)\n", kt, re.S)
check(reg is not None, "PadMapModel.PAGE3_CODES is there")
consts = {m.group(1): int(m.group(2)) for m in re.finditer(r"const val (P3_\w+)\s*=\s*(\d+)", kt)}
registered = []
if reg:
    for m in re.finditer(r'"([^"]+)"\s+to\s+([\w]+)\s*\.\.\s*([\w]+)', reg.group(1)):
        lo = consts.get(m.group(2), None) if not m.group(2).isdigit() else int(m.group(2))
        hi = consts.get(m.group(3), None) if not m.group(3).isdigit() else int(m.group(3))
        check(lo is not None and hi is not None, "registry entry %r resolves (%s..%s)" % (m.group(1), m.group(2), m.group(3)))
        if lo is not None and hi is not None:
            registered += list(range(lo, hi + 1))
dup = sorted({c for c in registered if registered.count(c) > 1})
check(not dup, "the registry's ranges never overlap %s" % (dup or ""))
check(sorted(set(p3)) == sorted(set(registered)), "the registry names exactly Book's third-page codes (Book %s, app %s)" % (sorted(set(p3)), sorted(set(registered))))
for name, c in consts.items():
    check(c in registered, "the app's send %s = %d is a registered code" % (name, c))

# phase 2: no new code; phase 3 took 11 / 12 for the top-screen combat bar; the 1515 merge (Book b76): the sticky realm question owns 9
# (a new character) and 10-19 (the candidates 1-10), so Home's mirror is 20 / 21 and the top bar 22 / 23
check(consts.get("P3_KEY_NEW") == 9 and consts.get("P3_KEY_LAST") == 19 and {9} | set(range(10, 20)) <= set(p3),
      "the key question's 9 / 10-19 (Book b74, test build 1515) kept")
check(consts.get("P3_MIRROR_OFF") == 20 and consts.get("P3_MIRROR_ON") == 21 and consts.get("P3_TOP_OFF") == 22 and consts.get("P3_TOP_ON") == 23
      and {20, 21, 22, 23} <= set(p3), "Home's codes off the question's: mirror 20 / 21, top bar 22 / 23 (third page now %s)" % sorted(set(p3)))

print("%s: %d failure(s)" % ("FAIL" if fails else "PASS", fails))
sys.exit(1 if fails else 0)
