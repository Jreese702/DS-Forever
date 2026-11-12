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
for name, conds in [("a target", ["@target,exists"]), ("in combat", ["combat"])]:
    k = press(conds)
    check(k[0] == "native", "Y with " + name + ": Forever's Inspect", k[0])
# m101 (the user, 2026-11-07: "When there is something soft targeted to loot, smart y does not work. I press the button and nothing happens")
k = press(["@softinteract,exists"])
check(k[0] == "smarty" and k[2] == "item:4540", "m101: Y with a soft-interact target (a corpse to loot): still uses the pick", k)
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

# ---------------------------------------------------------------- m70: the health / mana model (every reader secret; UNIT_COMBAT plain)
mc = re.search(r'-- SMARTY-MODEL-BEGIN[^\n]*\n(.*?)-- SMARTY-MODEL-END', SRC, re.S).group(1)
H = lupa.LuaRuntime(unpack_returned_tuples=True)
H.execute('OV = {}\n' + mc)
H.execute("""
function run(steps)
  local m = OV.hmNew(271, 457, 0)
  for _, s in ipairs(steps) do s(m) end
  return m
end
""")
def model(lua_steps):
    return H.execute('return run({' + lua_steps + '})')

m = model('function(m) OV.hmWound(m, 15, 0); OV.hmWound(m, 25, 0) end')
check(m["hp"] == 231 and m["wounds"] == 40, "wounds come off the max (271 - 15 - 25)", (m["hp"], m["wounds"]))
m = model('function(m) OV.hmWound(m, 100, 0); OV.hmTick(m, 10, { class = "DRUID", spirit = 20 }) end')
check(abs(m["hp"] - (171 + 10 * (0.09 * 20 + 6.5) / 2 * 0.8)) < 0.01, "out of combat: the druid's spirit regen, at 80 %", m["hp"])
m = model('function(m) OV.hmWound(m, 100, 0); OV.hmTick(m, 10, { combat = true, class = "DRUID", spirit = 20 }) end')
check(m["hp"] == 171, "in combat: no regen", m["hp"])
H.execute('OV.HM_SAFE = 0')                                            # regen off: the anchor alone
m = model('function(m) OV.hmWound(m, 200, 0); OV.hmTick(m, 100, { class = "DRUID", spirit = 0 }) end')
check(m["hp"] == 71, "under 3 minutes: still the estimate", m["hp"])
m = model('function(m) OV.hmWound(m, 200, 0); OV.hmTick(m, 100, { class = "DRUID", spirit = 0 }); OV.hmTick(m, 181, { class = "DRUID", spirit = 0 }) end')
check(m["hp"] == 271 and m["anchor"] == "quiet", "3 minutes with no wound out of combat: full", (m["hp"], m["anchor"]))
H.execute('OV.HM_SAFE = 0.8')
m = model('function(m) OV.hmCast(m, 100, 0); OV.hmTick(m, 4, { class = "DRUID", spirit = 20 }) end')
check(m["mp"] == 357, "a cast's cost comes off; no mana regen inside the 5-second rule", m["mp"])
m = model('function(m) OV.hmCast(m, 100, 0); OV.hmTick(m, 4, { class = "DRUID", spirit = 20 }); OV.hmTick(m, 14, { class = "DRUID", spirit = 20 }) end')
check(abs(m["mp"] - (357 + 10 * (0.2 * 20 + 15) / 2 * 0.8)) < 0.01, "after 5 s: spirit mana regen", m["mp"])
m = model('function(m) OV.hmWound(m, 200, 0); OV.hmTick(m, 6, { combat = true, eatHp = 10 }) end')
check(m["hp"] == 131, "eating: the item's rate per second", m["hp"])
m = model('function(m) OV.hmWound(m, 300, 0) end')
check(m["hp"] == 0, "never below 0", m["hp"])
rate = H.eval('OV.hmRate')
check(abs(rate("Use: Restores 61 health over 18 sec.  Must remain seated while eating.", "health") - 61 / 18) < 1e-9, "the food's rate from its tooltip", "")
check(abs(rate("Use: Restores 151 mana over 18 sec.", "mana") - 151 / 18) < 1e-9, "the drink's rate", "")
check(abs(rate("Use: Restores 378 health and 567 mana over 24 sec.", "mana") - 567 / 24) < 1e-9, "a food and drink's mana rate", "")
check(rate("Use: Increases Stamina by 6 for 15 min.", "health") is None, "a buff food: no rate", "")
# the 80 % rule on the model's numbers
t = P.table_from({"hp": 216, "hpMax": 271, "mp": 457, "mpMax": 457, "level": 10, "items": P.table_from([P.table_from(ITEMS[0])])})
r = pick(t)
check(r is not None and r[1] == "food", "the model's 216 / 271 (under 80 %): food", r and r[1])

# ---------------------------------------------------------------- m71: the spells out of mana (the game's flag, else the estimate)
nb = SRC[SRC.index('function OV.nmBool(v)'):SRC.index('OV.nmF = CreateFrame("Frame")')]
N = lupa.LuaRuntime(unpack_returned_tuples=True)
N.execute("""
OV, WC, PV = {}, {}, "m71"
SECRET = setmetatable({}, {})
issecretvalue = function(v) return rawequal(v, SECRET) end
function WC.isPlain(v) return not rawequal(v, SECRET) end
function T(fn) local ok, v = pcall(fn); if ok then return v end end
function OV.hmPlain(v) if rawequal(v, SECRET) then return nil end; if type(v) == "number" then return v end end
SENT = {}
function WC.encode(t) return t end
function WC.send(t) SENT[#SENT + 1] = t end
function WC.push() end
MANA = 100; FLAG = {}
function OV.smartVitals() return { mp = MANA, mpSrc = "model" } end
WC.spellsBindableList = function() return { { id = 1 }, { id = 2 }, { id = 3 } } end
COST = { [1] = 50, [2] = 150, [3] = 0 }
C_Spell = { IsSpellUsable = function(id) return true, FLAG[id] end, GetSpellPowerCost = function(id) return { { type = 0, cost = COST[id] } } end }
""" + nb)
def nm(flags, mana=100):
    N.execute('SENT = {}; MANA = %d; FLAG = {%s}' % (mana, flags))
    N.execute('OV.nmCheck()')
    sent = N.eval('SENT')
    if len(sent) == 0:
        return None
    t = sent[1]
    return (sorted(t["ids"][i] for i in range(1, len(t["ids"]) + 1)), t["src"])

check(nm('[1] = false, [2] = true, [3] = false') == ([2], "game"), "the game's own plain noMana", "")
check(nm('[1] = false, [2] = true, [3] = false') is None, "the same set again: nothing sent", "")
check(nm('[1] = SECRET, [2] = SECRET, [3] = SECRET', mana=40) == ([1, 2], "model"),
      "the flag secret: the estimate (40) against each cost (50, 150, 0)", nm('[1] = SECRET, [2] = SECRET, [3] = SECRET', mana=41))
check(nm('[1] = SECRET, [2] = SECRET, [3] = SECRET', mana=500) == ([], "none"), "enough for all: an empty set", "")

# ---------------------------------------------------------------- m74: the quest item on Y (armed out of combat; used when its target exists)
def press_q(conds=(), qi="item:12345", qu="target", fight=False, smart=None, trad=False):
    L.execute('TRUE = {}')
    for c in conds:
        L.execute('TRUE["%s"] = true' % c)
    attrs = L.table_from({"wc-n-top": 1, "wc-dest-top-1": "ForeverInspectButton", "wc-pos": 6, "wc-state": "fight" if fight else "idle",
                          "wc-fight-type": "spell", "wc-fight-spell": "Wrath"})
    if qi: attrs["wc-qitem"] = qi; attrs["wc-qitem-u"] = qu
    if smart: attrs["wc-smarty"] = smart
    if trad: attrs["wc-tradial"] = True
    mk = L.eval('mkframe')
    btn = mk(attrs, L.table_from({"wc-hdr": mk(L.table_from({})), "wc-bar-top-1": mk(L.table_from({}))}))
    run(btn, "LeftButton", True)
    return btn.a["wc-kind"], btn.a["type"], btn.a["item"], btn.a["unit"]

k = press_q(["@target,exists,nodead", "@target,exists"])
check(k == ("qitem", "item", "item:12345", "target"), "armed, its NPC targeted: the item ON the target", k)
k = press_q(["@target,exists,nodead", "@target,exists", "combat"], fight=True)
check(k[0] == "qitem", "in a fight too: the quest item before the fight spell", k[0])
k = press_q([], fight=True)
check(k[0] == "fight", "armed but no target: Y's fight spell as before", k[0])
k = press_q(["@softinteract,exists"], qu="soft")
check(k[0] == "qitem" and k[3] is None, "an object (soft-interact): the item, no unit", k)
k = press_q(["@target,exists"], qu="soft")
check(k[0] == "native", "an object's item with only an NPC targeted: Y as before", k[0])
k = press_q(["@target,exists,nodead", "@target,exists"], smart="item:117")
check(k[0] == "qitem", "the quest item beats Smart Y's food", k[0])
k = press_q([], qi=None)
check(k[0] == "native", "nothing armed: Y as before", k[0])

# m132 (the user, 2026-11-11: "My current polestar objective does not give any direction on where to go"): the picked Polestar step's
# starting item, used from the bags ("bag")
k = press_q([], qu="bag")
check(k == ("qitem", "item", "item:12345", None), "m132: a starter item, nothing targeted: the item from the bags, no unit", k)
# review (2026-11-11): only with NOTHING targeted - a target keeps Y (the target radial), so a starter that cannot be accepted
# never takes every Y on every target
k = press_q(["@target,exists,nodead", "@target,exists"], qu="bag")
check(k[0] == "native", "m132: ... with a target: not the starter (Y as before)", k)
k = press_q(["@target,exists,nodead", "@target,exists"], qu="bag", trad=True)
check(k[0] == "tradial", "m132: ... with a target and the radial on: the target radial", k)
k = press_q(["@softinteract,exists"], qu="bag")
check(k[0] == "qitem" and k[3] is None, "m132: ... only a soft-interact unit (no target): the starter, no unit", k)
k = press_q(["combat"], qu="bag", fight=True)
check(k[0] == "fight", "m132: ... in a fight: Y's fight spell as before", k[0])
k = press_q([], qu="bag", smart="item:117")
check(k[0] == "qitem", "m132: ... before Smart Y's food", k[0])

ps = re.search(r'-- QI-PS-BEGIN[^\n]*\n(.*?)-- QI-PS-END', SRC, re.S).group(1)
S = lupa.LuaRuntime(unpack_returned_tuples=True)
S.execute('OV = {}\n' + ps)
S.execute('function st(t, p) local e = OV.qiStarter(t, p); if e then return e.item, e.q, e.icon end end')


def starter(t, picked):
    return S.eval('st')(S.eval(t), picked)


check(starter('{ nl = true, q = 8460, it = 20310, ih = true, ic = 134939, itn = "Ripped Missive" }', True) == (20310, 8460, 134939),
      "m132: a held starter, the row picked: armed with that item", "")
check(starter('{ nl = true, q = 8460, it = 20310, ih = false }', True) is None, "m132: not held: no arm", "")
check(starter('{ nl = true, q = 8460, it = 20310, ih = true }', False) is None, "m132: the row not picked: no arm", "")
check(starter('{ none = true }', True) is None, "m132: no step: no arm", "")
check(starter('{ nl = true, q = 33 }', True) is None, "m132: a step with no item: no arm", "")

qm = re.search(r'-- QI-MATCH-BEGIN[^\n]*\n(.*?)-- QI-MATCH-END', SRC, re.S).group(1)
M = lupa.LuaRuntime(unpack_returned_tuples=True)
M.execute('OV = {}\n' + qm)
M.execute("""
CANDS = {
  { q = 1, item = 100, npc = { [500] = true }, obj = {}, words = { "infected wolves weakened: 0/5" } },
  { q = 2, item = 200, npc = { [600] = true }, obj = { [7000] = true }, words = { "use the totem at the shrine" }, focus = true },
  { q = 3, item = 300, npc = { [500] = true }, obj = {}, words = {} , focus = true },
}
function m(t) local e, how = OV.qiMatch(CANDS, t); if e then return e.q, how end end
""")
def qm_(t):
    r = M.eval('m(%s)' % t)
    return r
check(qm_('{ npc = 600 }') == (2, "target"), "the target's npc id in a quest's objectives", qm_('{ npc = 600 }'))
check(qm_('{ npc = 500 }') == (3, "target"), "two quests match: the focused one", qm_('{ npc = 500 }'))
check(qm_('{ obj = 7000 }') == (2, "soft"), "an object by its id: soft", qm_('{ obj = 7000 }'))
check(qm_('{ npc = 999, name = "infected wolf" }') == (1, "target"), "no ids (no QuestieDB): the name in the objective's words", qm_('{ npc = 999, name = "infected wolf" }'))
check(qm_('{ npc = 999, name = "kobold" }') is None, "no match: nothing", "")

# ---------------------------------------------------------------- m75: a due buff before food / drink when the mana covers it
oc = re.search(r'-- SMARTY-ORDER-BEGIN[^\n]*\n(.*?)-- SMARTY-ORDER-END', SRC, re.S).group(1)
O = lupa.LuaRuntime(unpack_returned_tuples=True)
O.execute('OV = {}\n' + oc)
order = O.eval('OV.smartOrder')
check(order(True, 50, 200) is True, "food due, the buff costs 50, mana 200: the buff first", "")
check(order(True, 50, 30) is False, "food due, mana 30 under the buff's 50: food / drink first", "")
check(order(True, 50, 50) is True, "mana exactly the cost: the buff", "")
check(order(False, 50, 10) is True, "no food due: the buff (as before)", "")
check(order(True, None, 10) is True, "a buff with no mana cost (a shout, an aura): it never waits", "")
check(order(True, 50, None) is True, "mana unknown: the buff", "")

# ---------------------------------------------------------------- m75: the bars' "not enough mana" in a fight (ActionBar.lua)
AB = open(os.path.join(HERE, '..', 'companion', 'app', 'src', 'main', 'assets', 'companion', 'addons', 'WowCompanion', 'ActionBar.lua'),
          encoding='utf-8').read()
ub = AB[AB.index('local function bit01(b)'):AB.index('-- Raw read of a field')]
U = lupa.LuaRuntime(unpack_returned_tuples=True)
U.execute("""
WC = { SECRET = "" }
function WC.isPlain(v) return true end
MP = 30
function WC.vitals() return { mp = MP, mpSrc = "model" } end
C_Spell = { GetSpellPowerCost = function(id) return { { type = 0, cost = 50 } } end }
""" + ub + """
function um(u, m) local o = {}; usableMana(o, u, m, 774); return o.u, o.m end
""")
um = U.eval('um')
check(tuple(um(True, False)) == (1, 0), "plain flags: the game's own", "")
check(tuple(um(True, True)) == (1, 1), "plain not-enough-mana: marked", "")
check(tuple(um("", "")) == (1, 1), "secret flags in a fight: usable, and the estimate (30) under the cost (50): marked", tuple(um("", "")))
U.execute('MP = 400')
check(tuple(um("", "")) == (1, 0), "secret flags, enough mana by the estimate: not marked", tuple(um("", "")))
check(tuple(um(None, None))[0] == 0, "no answer at all (nil): unusable, as before", "")

# ---------------------------------------------------------------- m76: Smart Y On / Off, per character, OFF by default
so = SRC[SRC.index('function OV.smartOn()'):SRC.index('function OV.setSmartOn(on)')]
S2 = lupa.LuaRuntime(unpack_returned_tuples=True)
S2.execute('OV = {}; WHO = "Me-Realm"; function whoKey() return WHO end\n' + so)
on = S2.eval('OV.smartOn')
check(on() is False, "no saved choice: Smart Y is OFF", on())
S2.execute('WowCompanionSpellsDB = { smartOn = { ["Me-Realm"] = true } }')
check(on() is True, "turned on for this character: on", "")
S2.execute('WHO = "Alt-Realm"')
check(on() is False, "another character: still off (per character)", "")

# ---------------------------------------------------------------- m78: Manners on Smart Y (its prompt's lines, after your own)


def press_manners(text, conds=()):
    L.execute('TRUE = {}')
    for c in conds:
        L.execute('TRUE["%s"] = true' % c)
    attrs = L.table_from({"wc-n-top": 1, "wc-dest-top-1": "ForeverInspectButton", "wc-pos": 6, "wc-state": "idle",
                          "wc-smarty": "manners", "wc-smarty-t": "manners"})
    if text is not None:
        attrs["wc-manners-text"] = text
    mk = L.eval('mkframe')
    btn = mk(attrs, L.table_from({"wc-hdr": mk(L.table_from({})), "wc-bar-top-1": mk(L.table_from({}))}))
    run(btn, "LeftButton", True)
    return btn.a["wc-kind"], btn.a["type"], btn.a["macrotext"], btn.a["unit"]


MT = "/target Bob\n/cast Arcane Intellect\n/cleartarget"
k = press_manners(MT)
check(k[0] == "smarty" and k[1] == "macro" and k[2] == MT and k[3] is None, "Manners armed: Y runs its prompt's lines (no self unit)", k)
k = press_manners(None)
check(k[0] == "smarty" and k[1] is None, "Manners with nothing for this press: Y casts nothing", k)
for name, conds in [("a target", ["@target,exists"]), ("in combat", ["combat"])]:
    k = press_manners(MT, conds)
    check(k[0] == "native", "Manners with " + name + ": Forever's Inspect, as all of Smart Y", k[0])

mr = re.search(r'-- MANNERS-READ-BEGIN[^\n]*\n(.*?)-- MANNERS-READ-END', SRC, re.S).group(1)
MR = lupa.LuaRuntime(unpack_returned_tuples=True)
MR.execute('OV = {}\n' + mr)
rd = MR.eval('OV.mannersRead')
check(tuple(rd(MT)) == ("Arcane Intellect", "Bob"), "the lines read: the spell and the player", tuple(rd(MT)))
check(tuple(rd("/cast [@player] Mark of the Wild(Rank 2)\n"))[0] == "Mark of the Wild(Rank 2)", "a conditional is dropped",
      tuple(rd("/cast [@player] Mark of the Wild(Rank 2)\n")))
check(tuple(rd("/use item:1234")) == ("item:1234", None), "a scroll: the item use", tuple(rd("/use item:1234")))
check(rd("") is None and rd(None) is None and rd("/targetlasttarget") is None, "nothing armed: nil", "")

# m127: smartApply walks ONE ordered list (OV.smartWalk); Manners is asked only when the list has its row
sa = SRC[SRC.index('function OV.smartApply()'):SRC.index('OV.smartF = CreateFrame("Frame")')]
check('OV.smartWalk(rows, due' in sa and 'if OV.sbHas(rows, "manners") then' in sa
      and sa.index('OV.sbHas(rows, "manners")') < sa.index('OV.mannersArmed()'),
      "m127: smartApply walks the ordered list; Manners only with its row", "")

# ---------------------------------------------------------------- m127: TRACKING NEVER ON Y (the user, 2026-11-11)
tb = re.search(r'-- TRACK-BEGIN[^\n]*\n(.*?)-- TRACK-END', SRC, re.S).group(1)
TR = lupa.LuaRuntime(unpack_returned_tuples=True)
TR.execute('OV = {}\n' + tb + '\n' + mr)
TR.execute(r"""
NAMES = { [2383] = "Find Herbs", [19883] = "Track Humanoids", [5225] = "Track Humanoids", [1494] = "Track Beasts" }
LIST = { { name = "Find Minerals" }, "Track Hidden", false, { name = "Sense Undead(Rank 1)" } }
function info(i) local v = LIST[i]; if v == nil then return nil end; if type(v) == "table" then return v.name end; return v end
OV.trackSet = OV.trackBuild({ 2383, 19883, 5225, 1494, 9999 }, function(id) return NAMES[id] end, info)
""")
ist = TR.eval('OV.isTracking')
check(ist("Track Humanoids") and ist("Find Herbs") and ist("Track Beasts"), "tracking: the static ids by name", "")
check(ist("Find Herbs(Rank 1)") and ist("find herbs (Rank 2)"), "tracking: any rank, any case", "")
check(ist("Find Minerals") and ist("Track Hidden") and ist("Sense Undead"), "tracking: the minimap list (a table, a name; its rank stripped)", "")
check(not ist("Arcane Intellect") and not ist(None) and not ist(""), "not tracking: a buff, nothing", "")
TR.execute('LIST = { { name = "A" }, nil, { name = "Find Fish" } }; OV.trackSet2 = OV.trackBuild({}, function() end, info)')
check(TR.eval('OV.trackSet2["a"] == true and OV.trackSet2["find fish"] == nil'), "the list is read until it answers nothing", "")

# Manners: OTHER players only (at the pick)
ok_ = TR.eval('function(t, me) local s, w = OV.mannersOk(t, me, OV.isTracking); if s then return s .. "|" .. tostring(w) end return false end')
cases = [
    ("/target Me\n/cast Track Humanoids\n/cleartarget", "Me", False, "its own tracking reminder on you (the report's case)"),
    ("/cast Find Herbs", "Me", False, "a tracking spell with no /target"),
    ("/cast Find Herbs(Rank 1)", "Me", False, "a tracking spell with a rank"),
    ("/target Bob\n/cast Track Beasts", "Me", False, "a tracking spell on anybody"),
    ("/target Me-Realm\n/cast Aspect of the Hawk", "Me", False, "your own buff on you (name-realm)"),
    ("/target me\n/cast Arcane Intellect", "Me", False, "your own buff on you (any case)"),
    ("/target Me Surname\n/cast Arcane Intellect", "Me", False, "your own buff on you (a surname)"),
    ("/cast [@player] Mark of the Wild", "Me", False, "a cast on @player"),
    ("/use item:1234", "Me", False, "a bare /use (your own scroll)"),
    ("/target Bob\n/cast Arcane Intellect\n/cleartarget", "Me", "Arcane Intellect|Bob", "another player's buff: kept"),
    ("/target Bob-Other\n/cast Arcane Intellect", "Me", "Arcane Intellect|Bob-Other", "another player on another realm: kept"),
    ("/target Mel\n/cast Arcane Intellect", "Me", "Arcane Intellect|Mel", "a name that only starts like yours: kept"),
    ("/cast Arcane Brilliance", "Me", "Arcane Brilliance|nil", "a group buff with no /target (Manners' selfcast): kept"),
]
for text, me, want, name in cases:
    got = ok_(text, me)
    check(got == want, "Manners pick: " + name, got)

# Manners at the PRESS (OV.mannersPre: the same filter on the lines copied now)
mpre = re.search(r'(function OV\.mannersPre\(b\).*?\nend)\n', SRC, re.S).group(1)
marm = re.search(r'(function OV\.mannersArmed\(\).*?\nend)\n', SRC, re.S).group(1)
TR.execute(r"""
WC = { isPlain = function() return true end }
function T(fn) local ok, v = pcall(fn); if ok then return v end end
function inCombat() return false end
function UnitExists() return false end
function UnitName() return "Me" end
function SecureCmdOptionParse() return nil end
function OV.mannersHook() end
function OV.myName() return "Me" end
TEXT = ""
MannersPrompt = { IsShown = function() return true end,
                  GetAttribute = function(_, k) if k == "type" then return "macro" end if k == "macrotext" then return TEXT end end }
function mkb() local b = { a = { ["wc-smarty-t"] = "manners" } }
  function b:GetAttribute(k) return self.a[k] end
  function b:SetAttribute(k, v) self.a[k] = v end
  return b end
""" + marm + '\n' + mpre + r"""
function pressWith(t) TEXT = t; local b = mkb(); OV.mannersPre(b); return b.a["wc-manners-text"] or false end
function armedWith(t) TEXT = t; local s = OV.mannersArmed(); return s or false end
""")
pw, aw = TR.eval('pressWith'), TR.eval('armedWith')
BOB = "/target Bob\n/cast Arcane Intellect\n/cleartarget"
check(pw(BOB) == BOB, "press: another player's buff, its lines copied", "")
check(pw("/target Me\n/cast Track Humanoids") is False, "press: tracking on you -> no lines (Y casts nothing)", "")
check(pw("/target Me\n/cast Aspect of the Hawk") is False, "press: your own buff on you -> no lines", "")
check(pw("/use item:4321") is False, "press: a bare /use -> no lines", "")
check(aw(BOB) == "Arcane Intellect" and pw("/target Me\n/cast Track Humanoids") is False,
      "the prompt re-armed to tracking between the pick and the press: the press sends no lines", "")
check(aw("/target Me\n/cast Track Humanoids") is False, "armed: tracking on you is never picked", "")

# ---------------------------------------------------------------- m127: tracking never in the Add list, never added
kn = re.search(r'(function OV\.sbKnown\(\).*?\nend)\n', SRC, re.S).group(1)
sp = re.search(r'(WC\.smartYSpell = function\(id\).*?\nend)\n', SRC, re.S).group(1)
mg = re.search(r'-- SB-MIGRATE-BEGIN[^\n]*\n(.*?)-- SB-MIGRATE-END', SRC, re.S).group(1)
TR.execute(r"""
WC.spellsBindableList = function() return { { id = 1459 }, { id = 19883 }, { id = 168 } } end
BOOK = { [1459] = "Arcane Intellect", [19883] = "Track Humanoids", [168] = "Frost Armor" }
function OV.sbName(id) return BOOK[id] end
C_Spell = { GetSpellTexture = function() return 1 end }
OV.SB_IDS = { 1459, 19883, 168 }; OV.SB_IMBUE = {}; OV.SB_MAX = 16
ACKS = {}
function OV.sbAck(op, ok, why) ACKS[#ACKS + 1] = { op = op, ok = ok, why = why } end
function OV.smartApply() end
function emit() end
function WC.push() end
function GetTime() return 100 end
MN = false
function OV.mannersOn() return MN end
DB = { rows = {} }
function OV.sbdb() return OV.sbMigrate(DB, MN) end
""" + kn + '\n' + sp + '\n' + mg + r"""
function knownNames() local out = {}; for _, e in ipairs(OV.sbKnown()) do out[#out + 1] = e.n end; return table.concat(out, ",") end
function addId(id) OV.sbPick = { op = "add", at = 100 }; WC.smartYSpell(id); local a = ACKS[#ACKS]; return a and (tostring(a.ok) .. ":" .. tostring(a.why)) end
""")
check(TR.eval('knownNames()') == "Arcane Intellect,Frost Armor", "the Add list: the book's self-buffs, never a tracking spell", TR.eval('knownNames()'))
got = TR.eval('addId(19883)')
check(got == "false:tracking is never on Y", "a tracking spell sent anyway is refused (and said)", got)
got = TR.eval('addId(1459)')
check(got == "true:nil" and TR.eval('DB.rows[#DB.rows].ids[1] == 1459'), "a known buff is added at the end", got)

# ---------------------------------------------------------------- m127: the v1 -> v2 migration (typed rows), shown rows
MG = lupa.LuaRuntime(unpack_returned_tuples=True)
MG.execute('OV = {}\n' + mg + r"""
function kinds(e) local o = {}; for _, r in ipairs(e.rows) do o[#o + 1] = r.k or ("b" .. table.concat(r.ids, "+")) end; return table.concat(o, ",") end
""")
kinds = MG.eval('kinds')
mig = MG.eval('OV.sbMigrate')
v1 = MG.eval('function() return { rows = { { ids = { 1459 } }, { ids = { 168, 7302 } } } } end')()
e = mig(v1, True)
check(kinds(e) == "food,drink,b1459,b168+7302,manners" and e["v"] == 2, "v1 -> v2: Food, Water, the old rows, then Manners (installed)", kinds(e))
check(kinds(mig(e, True)) == "food,drink,b1459,b168+7302,manners", "v2 again: unchanged", "")
e2 = mig(MG.eval('function() return { rows = { { ids = { 1459 } } } } end')(), False)
check(kinds(e2) == "food,drink,b1459", "v1 -> v2 without Manners: no Manners row", kinds(e2))
check(kinds(mig(e2, True)) == "food,drink,b1459,manners", "Manners installed later: its row added at the end", "")
check(kinds(mig(MG.eval('function() return { rows = {} } end')(), False)) == "food,drink", "a fresh character: Food, Water", "")
e3 = MG.eval('function() return { v = 2, mnOff = true, rows = { { ids = { 1 } }, { k = "food" } } } end')()
check(kinds(mig(e3, True)) == "b1,food", "removed by the player (mnOff): Manners not added back; a buff above Food stays", kinds(e3))
e4 = MG.eval('function() return { v = 2, rows = { { k = "food" }, { k = "food" }, { ids = {} }, "x", { k = "nope" }, { k = "drink" } } } end')()
check(kinds(mig(e4, False)) == "food,drink", "malformed rows and a second Food dropped", kinds(e4))
vis = MG.eval('function(mn) local e = { v = 2, rows = { { k = "food" }, { k = "manners" }, { ids = { 1 } } } }; return table.concat(OV.sbVis(e.rows, mn), ",") end')
check(vis(False) == "1,3" and vis(True) == "1,2,3", "Manners' row hidden while it is not installed (kept in the list)", (vis(False), vis(True)))

# ---------------------------------------------------------------- m127: the edits on typed rows (sbOp / sbAddKind)
so_ = re.search(r'(function OV\.sbOp\(col\).*?\nend)\n', SRC, re.S).group(1)
ak = re.search(r'(function OV\.sbAddKind\(kind\).*?\nend)\n', SRC, re.S).group(1)
TR.execute(r"""
OV.SB_SEL0, OV.SB_OPS = 8, { remove = 24, up = 25, down = 26, add = 27, addor = 28, out = 29 }
OV.SB_KINDS = { "food", "drink", "manners" }
""" + so_ + '\n' + ak + r"""
function setRows(t, mn, off) DB = { v = 2, rows = t, mnOff = off }; MN = mn; ACKS = {}; OV.sbPick = nil; OV.sbSel = nil end
function k2() local o = {}; for _, r in ipairs(DB.rows) do o[#o + 1] = r.k or ("b" .. table.concat(r.ids, "+")) end; return table.concat(o, ",") end
function taps(...) for _, c in ipairs({ ... }) do OV.sbOp(c) end; local a = ACKS[#ACKS]; return k2() .. (a and (" " .. tostring(a.ok) .. ":" .. tostring(a.why)) or "") end
""")
TR.execute('setRows({ { k = "food" }, { k = "drink" }, { ids = { 1459 } } }, false)')
got = TR.eval('taps(10, 25)')
check(got.startswith("food,b1459,drink"), "a buff moved up above Water (select row 3, Up)", got)
got = TR.eval('taps(9, 25)')
check(got.startswith("b1459,food,drink"), "... and above Food", got)
TR.execute('setRows({ { k = "food" }, { k = "manners" }, { ids = { 1459 } } }, false)')
got = TR.eval('taps(8, 26)')
check(got.startswith("b1459,manners,food"), "Manners not installed: Down on Food swaps with the next SHOWN row", got)
TR.execute('setRows({ { k = "food" }, { k = "manners" } }, true)')
got = TR.eval('taps(9, 24)')
check(got.startswith("food ") and TR.eval('DB.mnOff == true'), "Manners removed: gone, and marked so it is not added back", got)
got = TR.eval('taps(27, 10)')
check(got.startswith("food,manners true") and TR.eval('DB.mnOff == nil'), "Add then select cell + 2: Manners back at the end", got)
got = TR.eval('taps(27, 8)')
check(got.endswith("false:already on the list"), "Add Food when it is there: refused, said", got)
TR.execute('setRows({ { k = "drink" } }, false)')
check(TR.eval('taps(27, 8)').startswith("drink,food true"), "Add then select cell + 0: Food back (at the end)", "")
check(TR.eval('taps(27, 10)').endswith("false:Manners is not installed"), "Add Manners while it is not installed: refused", "")
check(TR.eval('taps(8, 28)').endswith("false:not a buff row"), "Or on Water: refused (buff rows only)", "")

# ---------------------------------------------------------------- m127: the ORDERED walk (any order; a buff above food)
wk = re.search(r'-- SMARTY-WALK-BEGIN[^\n]*\n(.*?)-- SMARTY-WALK-END', SRC, re.S).group(1)
W = lupa.LuaRuntime(unpack_returned_tuples=True)
W.execute('OV = {}\n' + oc + '\n' + wk + r"""
F, D, M = { v = "food" }, { v = "drink" }, { v = "manners" }
B = { v = "buff", cost = 50 }
function walk(rows, due, mana) local st, k = OV.smartWalk(rows, due, mana); return st and (st.v .. ":" .. k) or false end
""")
walk = W.eval('walk')
lua = W.execute
std = 'return { { k = "food" }, { k = "drink" }, { ids = { 1 } }, { k = "manners" } }'
up = 'return { { ids = { 1 } }, { k = "food" }, { k = "drink" }, { k = "manners" } }'
def due(**k):
    parts = ['%s = %s' % (n, v) for n, v in k.items()]
    return W.execute('return { ' + ', '.join(parts) + ' }')
check(walk(lua(std), due(food='F', buff='B', buffAt=3), 200) == "food:food", "the default order: Food first", "")
check(walk(lua(up), due(food='F', buff='B', buffAt=1), 200) == "buff:buff", "a buff moved above Food: the buff first", "")
check(walk(lua(up), due(food='F', buff='B', buffAt=1), 30) == "food:food", "... the mana short for it: Food / Water below go first (m75)", "")
check(walk(lua(up), due(buff='B', buffAt=1), 30) == "buff:buff", "... the mana short, nothing below due: the buff", "")
check(walk(lua(up), due(food='F', buff='B', buffAt=1), None) == "buff:buff", "... the mana unknown: the buff", "")
check(walk(lua(std), due(drink='D', buff='B', buffAt=3), 10) == "drink:drink", "Water due above a buff: Water", "")
check(walk(lua('return { { k = "drink" }, { k = "food" } }'), due(food='F', drink='D'), 10) == "drink:drink", "Water moved above Food: Water first", "")
check(walk(lua('return { { k = "manners" }, { k = "food" } }'), due(food='F', manners='M'), 10) == "manners:manners", "Manners moved to the top: Manners first", "")
check(walk(lua(std), due(manners='M', buff='B', buffAt=3), 200) == "buff:buff", "Manners below the buffs (the default): the buff first", "")
check(walk(lua('return { { k = "drink" } }'), due(food='F'), 10) is False, "Food removed from the list: never food", "")
check(walk(lua(std), due(buff='B', buffAt=1), 200) is False, "buffAt on a typed row: nothing (only a buff row takes the buff)", "")
check(walk(lua(std), due(), 200) is False, "nothing due: nothing", "")

# m128 (review of m127): the model's eating / drinking rate and the why line follow the step the walk CHOSE, not the old fixed pick
EF, WP = W.eval('OV.smartEatFrom'), W.eval('OV.smartWhyPick')
W.execute('''
FS = { val = "item:4540", typ = "item", ik = "food", shown = { id = 4540, k = "food" } }
DS = { val = "item:159", typ = "item", ik = "drink", shown = { id = 159, k = "drink" } }
BS = { val = "item:2288", typ = "item", ik = "both", shown = { id = 2288, k = "drink" } }
SP = { val = "spell:1126", typ = "spell", shown = { id = 1126, k = "buff" } }
''')
wst, wk2 = W.eval('(function() return OV.smartWalk({ { k = "drink" }, { k = "food" } }, { food = FS, drink = DS }, 10) end)')()
e1 = EF(None, wst)
check(e1['drink'] == 159 and e1['food'] is None, "Water above Food, both due: the drink's item is the one Y uses (its rate for the drink aura)", (e1['drink'], e1['food']))
wp = WP(wst, wk2)
check(wp['kind'] == "drink" and wp['id'] == 159, "... and the why line says the drink", (wp['kind'], wp['id']))
e2 = EF(e1, W.eval('FS'))
check(e2['food'] == 4540 and e2['drink'] == 159, "Food armed next (drinking already): the drink's item kept for its running aura", (e2['food'], e2['drink']))
e3 = EF(e2, None)
check(e3['food'] == 4540 and e3['drink'] == 159, "nothing due (both auras running): both kept", "")
e4 = EF(e3, W.eval('SP'))
check(e4['food'] == 4540 and e4['drink'] == 159, "a buff step: the items untouched", "")
e5 = EF(None, W.eval('BS'))
check(e5['food'] == 2288 and e5['drink'] == 2288, "a food-and-drink item: counts for both auras", "")
check(WP(None, None) is None and WP(W.eval('SP'), "buff")['kind'] == "buff", "the why pick: nothing due = nil, a buff step = buff", "")
check('OV.smartPick(state)' not in sa and 'OV.smartEatFrom(OV.smartEat, st)' in sa and 'OV.smartWhyPick(st, sk)' in sa,
      "m128: smartApply no longer uses the fixed food-then-drink pick for the rate / why line", "")
hs = SRC[SRC.index('function OV.hmStep()'):SRC.index('function OV.hmItemRate(')]
check('OV.smartEat[k]' in hs and 'OV.smartItem' not in hs, "m128: hmStep takes each aura's rate from its own kind's item", "")

# the kind pickers the walk uses
check(pick(P.table_from({"hp": 50, "hpMax": 100, "mp": 50, "mpMax": 100, "level": 10, "drinking": False,
                         "items": P.table_from([P.table_from(i) for i in ITEMS])}), "drink")["id"] == 159,
      "smartPick(s, \"drink\"): the drink alone, with food due too", "")
check(pick(P.table_from({"hp": 50, "hpMax": 100, "mp": 100, "mpMax": 100, "level": 10, "eating": True,
                         "items": P.table_from([P.table_from(i) for i in ITEMS])}), "food") is None,
      "smartPick(s, \"food\") while eating: nothing", "")

print('ALL PASS' if fails == 0 else '%d FAILED' % fails)
sys.exit(1 if fails else 0)
