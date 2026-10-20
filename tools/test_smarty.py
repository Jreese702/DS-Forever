"""Smart Y (PadMap m61), off-device: the router's REAL secure pre-click snippet (PRE) for Y, and the pure food / drink choice
(OV.smartPick, between PadMap.lua's SMARTY-PICK-BEGIN / END markers), each run in Lua with the game stubbed.

    python tools/test_smarty.py

The snippet: a bare Y out of combat with no target and no soft-interact target uses the pick (type item); with a target, a soft-interact
target, in combat, in the fight state, or with no pick, Y stays Forever's own Inspect (or the fight spell). The choice: below 80 %, food
first, the drink once eating, the cheapest stack that the level allows, a "both" item for either, no mana check without a mana pool.
Needs lupa. Exit code 1 on any failure."""
import os
import re
import sys

import lupa

HERE = os.path.dirname(os.path.abspath(__file__))
PADMAP = os.path.join(HERE, '..', 'companion', 'app', 'src', 'main', 'assets', 'companion', 'addons', 'WowCompanionSpells', 'PadMap.lua')
SRC = open(PADMAP, encoding='utf-8').read()
fails = 0


def check(ok, name, got=''):
    global fails
    print(('PASS ' if ok else 'FAIL ') + name + ((' -> ' + str(got)) if got != '' else ''))
    if not ok:
        fails += 1


# ---------------------------------------------------------------- the snippet
L = lupa.LuaRuntime(unpack_returned_tuples=True)
L.execute('''
function newtable(...) return {...} end
function mkframe(attrs, refs)
  local f = { a = attrs or {}, refs = refs or {} }
  function f:GetAttribute(k) return self.a[k] end
  function f:SetAttribute(k, v) self.a[k] = v end
  function f:GetFrameRef(k) return self.refs[k] end
  function f:IsShown() return true end
  function f:GetID() return 0 end
  return f
end
TRUE = {}
function SecureCmdOptionParse(s) local c = s:match("^%[(.-)%]"); if TRUE[c] then return "1" end return nil end
function GetGamePadState() return { buttons = {} } end
''')
pre = re.search(r'local PRE = \[=\[(.*?)\]=\]', SRC, re.S).group(1)
run = L.execute('return function(self, button, down) ' + pre + ' end')


def press(conds=(), smart=True, fight=False):
    L.execute('TRUE = {}')
    for c in conds:
        L.execute('TRUE["%s"] = true' % c)
    attrs = L.table_from({"wc-n-top": 1, "wc-dest-top-1": "ForeverInspectButton", "wc-pos": 6,
                          "wc-state": "fight" if fight else "idle", "wc-fight-type": "spell", "wc-fight-spell": "Wrath"})
    if smart:
        attrs["wc-smarty"] = "item:4540"
    mk = L.eval('mkframe')
    btn = mk(attrs, L.table_from({"wc-hdr": mk(L.table_from({})), "wc-bar-top-1": mk(L.table_from({}))}))
    run(btn, "LeftButton", True)
    return btn.a["wc-kind"], btn.a["type"], btn.a["item"]


k = press()
check(k[0] == "smarty" and k[1] == "item" and k[2] == "item:4540", "Y, nothing targeted, out of combat: uses the pick", k)
for name, conds in [("a target", ["@target,exists"]), ("a soft-interact target", ["@softinteract,exists"]), ("in combat", ["combat"])]:
    k = press(conds)
    check(k[0] == "native", "Y with " + name + ": Forever's Inspect", k[0])
k = press(smart=False)
check(k[0] == "native", "Y with no pick: Forever's Inspect", k[0])
k = press(fight=True)
check(k[0] == "fight", "Y in the fight state: its fight spell", k[0])

# ---------------------------------------------------------------- the choice
chunk = re.search(r'-- SMARTY-PICK-BEGIN[^\n]*\n(.*?)-- SMARTY-PICK-END', SRC, re.S).group(1)
P = lupa.LuaRuntime(unpack_returned_tuples=True)
P.execute('OV = {}\n' + chunk)
pick = P.eval('OV.smartPick')
ITEMS = [
    {"id": 117, "kind": "food", "sell": 1, "minLv": 1, "n": 4},      # Tough Jerky
    {"id": 4540, "kind": "food", "sell": 1, "minLv": 1, "n": 2},     # Tough Hunk of Bread (same price: lower id first)
    {"id": 2287, "kind": "food", "sell": 6, "minLv": 5, "n": 10},    # Haunch of Meat
    {"id": 159, "kind": "drink", "sell": 1, "minLv": 1, "n": 5},     # Refreshing Spring Water
    {"id": 1179, "kind": "drink", "sell": 6, "minLv": 5, "n": 8},    # Ice Cold Milk
    {"id": 8079, "kind": "drink", "sell": 0, "minLv": 45, "n": 3},   # Conjured Crystal Water: level 45
]


def choose(hp, mp, mpmax=100, level=10, eating=False, drinking=False, items=ITEMS):
    t = P.table_from({"hp": hp, "hpMax": 100, "mp": mp, "mpMax": mpmax, "level": level, "eating": eating, "drinking": drinking,
                      "items": P.table_from([P.table_from(i) for i in items])})
    r = pick(t)
    if r is None:
        return None
    it, kind = r if isinstance(r, tuple) else (r, None)
    return (it["id"], kind) if it is not None else None


check(choose(79, 100) == (117, "food"), "health below 80 %: the cheapest food (lower id on a tie)", choose(79, 100))
check(choose(80, 100) is None, "health at 80 %: nothing", choose(80, 100))
check(choose(100, 50) == (159, "drink"), "mana below 80 %: the cheapest drink", choose(100, 50))
check(choose(50, 50) == (117, "food"), "both low: food first", choose(50, 50))
check(choose(50, 50, eating=True) == (159, "drink"), "both low and eating: the drink next", choose(50, 50, eating=True))
check(choose(50, 100, eating=True) is None, "eating, mana full: nothing", choose(50, 100, eating=True))
check(choose(100, 0, mpmax=0) is None, "no mana pool: no mana check", choose(100, 0, mpmax=0))
check(choose(100, 10, level=50, items=ITEMS) == (8079, "drink"), "level 50: the level-45 water (cheapest) is allowed",
      choose(100, 10, level=50))
check(choose(100, 10, level=10) == (159, "drink"), "level 10: the level-45 water is skipped", choose(100, 10, level=10))
both = [{"id": 21215, "kind": "both", "sell": 0, "minLv": 1, "n": 1}, {"id": 159, "kind": "drink", "sell": 1, "minLv": 1, "n": 5}]
check(choose(50, 100, items=both) == (21215, "food"), "a food-and-drink item counts as food", choose(50, 100, items=both))
check(choose(100, 50, items=both) == (21215, "drink"), "... and as drink (it is the cheapest)", choose(100, 50, items=both))
check(choose(50, 100, items=[i for i in ITEMS if i["kind"] == "drink"]) is None, "health low, no food in the bags: nothing",
      choose(50, 100, items=[i for i in ITEMS if i["kind"] == "drink"]))
empty = [dict(ITEMS[0], n=0)]
check(choose(50, 100, items=empty) is None, "an empty stack is never picked", choose(50, 100, items=empty))

print('ALL PASS' if fails == 0 else '%d FAILED' % fails)
sys.exit(1 if fails else 0)
