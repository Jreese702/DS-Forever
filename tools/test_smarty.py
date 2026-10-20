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

# ---------------------------------------------------------------- m62 Smart buffs: Y's spell / OR kinds, and the first unsatisfied row


def press_kind(kind, val):
    L.execute('TRUE = {}')
    attrs = L.table_from({"wc-n-top": 1, "wc-dest-top-1": "ForeverInspectButton", "wc-pos": 6, "wc-state": "idle",
                          "wc-smarty": val, "wc-smarty-t": kind})
    mk = L.eval('mkframe')
    btn = mk(attrs, L.table_from({"wc-hdr": mk(L.table_from({})), "wc-bar-top-1": mk(L.table_from({}))}))
    run(btn, "LeftButton", True)
    return btn.a["type"], btn.a["spell"], btn.a["unit"], btn.a["macrotext"]


k = press_kind("spell", "Arcane Intellect")
check(k[0] == "spell" and k[1] == "Arcane Intellect" and k[2] == "player", "a buff row: Y casts it on yourself", k)
k = press_kind("or", "or")
check(k[0] == "macro" and "WowCompanionPadSmartyOr" in (k[3] or ""), "an OR row: Y opens the wheel (Hub's button)", k)
k = press_kind("item", "item:117")
check(k[0] == "item" and k[2] is None, "food: an item, no unit", k)
L.execute('TRUE = {}')
attrs = L.table_from({"wc-n-top": 1, "wc-dest-top-1": "ForeverInspectButton", "wc-pos": 6, "wc-state": "fight", "wc-fight-type": "spell",
                      "wc-fight-spell": "Wrath", "unit": "player"})
mk = L.eval('mkframe')
btn = mk(attrs, L.table_from({"wc-hdr": mk(L.table_from({})), "wc-bar-top-1": mk(L.table_from({}))}))
run(btn, "LeftButton", True)
check(btn.a["unit"] is None, "a fight spell after a buff: the self unit is cleared (never cast a harmful spell on yourself)", btn.a["unit"])

sb = re.search(r'-- SB-EVAL-BEGIN[^\n]*\n(.*?)-- SB-EVAL-END', SRC, re.S).group(1)
Q = lupa.LuaRuntime(unpack_returned_tuples=True)
Q.execute('OV = {}\n' + sb)
first = Q.eval('OV.sbFirst')
NAMES = {1459: "Arcane Intellect", 168: "Frost Armor", 7302: "Ice Armor", 465: "Devotion Aura", 7294: "Retribution Aura",
         8017: "Rockbiter Weapon", 9999: "Forgotten Spell"}
nameOf = Q.eval('function(t) return function(id) return t[id] end end')(Q.table_from(NAMES))


def known(ids):
    t = {}
    for i in ids:
        t[NAMES[i]] = Q.table_from({"id": i, "n": NAMES[i], "k": "imbue" if i == 8017 else "aura"})
    return Q.table_from(t)


def rows(*rs):
    return Q.table_from([Q.table_from({"ids": Q.table_from(list(r))}) for r in rs])


def go(rs, kn, up=(), mh=False):
    r = first(rs, kn, nameOf, Q.table_from({n: True for n in up}), mh)
    if r is None:
        return None
    idx, members = r
    return idx, [members[i + 1]["id"] for i in range(len(members))]


ALL = known([1459, 168, 7302, 465, 7294, 8017])
check(go(rows([1459], [168, 7302]), ALL) == (1, [1459]), "nothing up: the first row", go(rows([1459], [168, 7302]), ALL))
check(go(rows([1459], [168, 7302]), ALL, up=["Arcane Intellect"]) == (2, [168, 7302]), "row 1 up: the OR row next, its members",
      go(rows([1459], [168, 7302]), ALL, up=["Arcane Intellect"]))
check(go(rows([1459], [168, 7302]), ALL, up=["Arcane Intellect", "Ice Armor"]) is None, "one OR member up: the row is satisfied", "")
check(go(rows([8017]), ALL, mh=True) is None, "an imbue: satisfied by the main hand's enchant", "")
check(go(rows([8017]), ALL, mh=False) == (1, [8017]), "an imbue with no enchant: cast it", go(rows([8017]), ALL))
check(go(rows([9999], [465]), ALL) == (2, [465]), "a row of spells no longer known is skipped", go(rows([9999], [465]), ALL))
check(go(rows([465, 9999]), ALL) == (1, [465]), "an OR row keeps only its known members", go(rows([465, 9999]), ALL))
check(go(rows(), ALL) is None, "no rows: nothing", "")

# ---------------------------------------------------------------- m63: food / drink by the item (test29: nothing ever matched)
kf = re.search(r'(function OV\.smartKind\(id\).*?\nend)\n', SRC, re.S).group(1)
K = lupa.LuaRuntime(unpack_returned_tuples=True)
K.execute('''
OV = { SMART_FOOD = 433, SMART_DRINK = 430 }
function T(fn) local ok, v = pcall(fn); if ok then return v end end
ITEMS = {}
C_Item = { GetItemSpell = function(id) return ITEMS[id] and ITEMS[id].sp end,
           GetItemInfoInstant = function(id) local i = ITEMS[id] or {}; return id, "", "", "", 0, i.cls or 15, i.sub or 0 end }
C_Spell = { GetSpellName = function(id) return ({ [433] = "Food", [430] = "Drink" })[id] end }
C_TooltipInfo = { GetItemByID = function(id) local i = ITEMS[id]; if not i or not i.tip then return nil end
                  return { lines = { { leftText = "Use: " .. i.tip } } } end }
''' + kf)
kind = K.eval('function(id, t) ITEMS[id] = t; local k, r = OV.smartKind(id); return k end')
cases = [
    ("the game's Food spell", {"sp": "Food", "cls": 0, "sub": 5}, "food"),
    ("the game's Drink spell", {"sp": "Drink", "cls": 0, "sub": 5}, "drink"),
    ("a spell named Refreshment", {"sp": "Refreshment", "cls": 0, "sub": 5}, "both"),
    ("a spell named Food & Drink", {"sp": "Food & Drink", "cls": 0, "sub": 5}, "both"),
    ("another name, the tooltip says health", {"sp": "Tough Jerky", "cls": 0, "sub": 5, "tip": "Restores 61 health over 18 sec."}, "food"),
    ("another name, the tooltip says mana", {"sp": "Mana Restoration", "cls": 0, "sub": 5, "tip": "Restores 151 mana over 18 sec."}, "drink"),
    ("no spell, a food & drink item restoring both", {"cls": 0, "sub": 5, "tip": "Restores 378 health and 567 mana over 24 sec."}, "both"),
    ("a buff-only food (no restore line)", {"sp": "Well Fed", "cls": 0, "sub": 5, "tip": "Increases Stamina by 6 for 15 min."}, None),
    ("a potion (not food & drink, no restore name)", {"sp": "Healing Potion", "cls": 0, "sub": 1}, None),
    ("a potion whose tooltip restores health: never food", {"sp": "Healing Potion", "cls": 0, "sub": 1, "tip": "Restores 140 to 180 health."}, None),
]
for i, (name, t, want) in enumerate(cases):
    got = kind(9000 + i, K.table_from(t))
    check(got == want, "kind: " + name, got)

# ---------------------------------------------------------------- m64: health / mana under secret values (test32: "hp ? | mana none")
vf = re.search(r'(function OV\.smartVitals\(\).*?\nend)\n', SRC, re.S).group(1)
V = lupa.LuaRuntime(unpack_returned_tuples=True)
V.execute("""
OV, WC = {}, {}
SECRET = setmetatable({}, { __tostring = function() error("secret") end })
function issecretvalue(v) return v == SECRET end
function WC.isPlain(v) return pcall(function() return tostring(v):sub(1, 1) end) end
G = {}
function UnitHealth() return G.hp end
function UnitHealthMax() return G.hpMax end
function UnitPower(_, t) return G.mp end
function UnitPowerMax(_, t) return G.mpMax end
function UnitPowerType() return G.ptype or 0 end
function mkbar(v, lo, hi) return { GetValue = function() return v end, GetMinMaxValues = function() return lo, hi end } end
""" + vf)
vit = V.eval('OV.smartVitals')


def vitals(**k):
    V.execute('G = {}; UnitHealthPercent = nil; UnitPowerPercent = nil; PlayerFrameHealthBar = nil; PlayerFrameManaBar = nil; '
              'PlayerFrameAlternateManaBar = nil')
    for name, val in k.items():
        V.execute('%s = %s' % (name, val))
    v = vit()
    return (v["hp"], v["hpMax"], v["hpSrc"], v["mp"], v["mpMax"], v["mpSrc"])


check(vitals(**{"G.hp": "500", "G.hpMax": "1000", "G.mp": "300", "G.mpMax": "600"}) == (500, 1000, "unit", 300, 600, "unit"),
      "plain numbers: the unit's own", vitals(**{"G.hp": "500", "G.hpMax": "1000", "G.mp": "300", "G.mpMax": "600"}))
r = vitals(**{"G.hp": "SECRET", "G.hpMax": "SECRET", "G.mp": "SECRET", "G.mpMax": "SECRET",
              "UnitHealthPercent": "function() return 0.62 end", "UnitPowerPercent": "function() return 45 end"})
check(abs(r[0] - 62) < 0.01 and r[1:3] == (100, "pct") and r[3:] == (45, 100, "pct"), "secret numbers: the percent readers", r)
r = vitals(**{"G.hp": "SECRET", "G.hpMax": "SECRET", "G.mp": "SECRET", "G.mpMax": "SECRET",
              "PlayerFrameHealthBar": "mkbar(70, 0, 100)", "PlayerFrameManaBar": "mkbar(40, 0, 200)"})
check(r == (70, 100, "frame", 40, 200, "frame"), "secret numbers, no percent reader: the player frame's bars", r)
r = vitals(**{"G.hp": "SECRET", "G.hpMax": "SECRET", "G.mp": "SECRET", "G.mpMax": "SECRET", "G.ptype": "1",
              "PlayerFrameManaBar": "mkbar(10, 0, 100)", "PlayerFrameAlternateManaBar": "mkbar(55, 0, 110)"})
check(r[3:] == (55, 110, "frame"), "a druid in a form: mana from the alternate bar, never the rage bar", r)
r = vitals(**{"G.hp": "SECRET", "G.hpMax": "SECRET", "G.mp": "SECRET", "G.mpMax": "SECRET", "PlayerFrameHealthBar": "mkbar(SECRET, 0, 100)"})
check(r[0] is None and r[2] is None and r[3] is None and r[5] is None, "all secret: unknown (never full)", r)
r = vitals(**{"G.hp": "900", "G.hpMax": "1000", "G.mp": "0", "G.mpMax": "0"})
check(r[5] == "none", "a plain 0 mana max: no mana pool", r)

print('ALL PASS' if fails == 0 else '%d FAILED' % fails)
sys.exit(1 if fails else 0)
