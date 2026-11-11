"""s7 (the user, 2026-11-11: Smart Y's Pick added nothing): every spell Book lists must get a tap number (`pv`), the loose fly-out
members (the Hunter aspects) included. Runs the feed's own numbering condition from Spells.lua on a fly-out member and a plain spell."""
import os
import re
import sys

from lupa import LuaRuntime

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SPELLS = os.path.join(ROOT, "companion", "app", "src", "main", "assets", "companion", "addons", "WowCompanionSpells", "Spells.lua")

src = open(SPELLS, encoding="utf-8").read()
m = re.search(r"\n\s*(if it\.t == T_SPELL [^\n]*ids\[#ids \+ 1\] = it\.a end)", src)
fails = 0


def chk(name, ok):
    global fails
    print(("PASS " if ok else "FAIL ") + name)
    if not ok:
        fails += 1


chk("the numbering condition is found in Spells.lua", m is not None)
if m:
    lua = LuaRuntime()
    run = lua.eval("""function(cond)
        local f = load("local T_SPELL = 1; return function(items) local ids = {}; for pass = 1, 2 do for _, it in ipairs(items) do "
            .. cond .. " end end; return ids end")()
        return f
    end""")(m.group(1))
    items = lua.eval("""{
        { t = 1, a = 13165, fm = true },              -- Aspect of the Hawk, a loose fly-out member
        { t = 1, a = 1978 },                          -- Serpent Sting
        { t = 1, a = 75, pas = true },                -- a passive: no number
        { t = 1, a = 14260, low = true },             -- a lower rank: the second pass
    }""")
    ids = list(run(items).values())
    chk("a loose fly-out member gets a number (%s)" % ids, 13165 in ids)
    chk("a plain spell gets one", 1978 in ids)
    chk("a passive gets none", 75 not in ids)
    chk("lower ranks come last", ids[-1] == 14260)

print("failed: %d" % fails)
sys.exit(1 if fails else 0)
