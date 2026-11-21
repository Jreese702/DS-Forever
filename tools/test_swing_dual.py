"""SPLIT SWING TIMERS (WowCompanion/ActionBar.lua nbar5), off-device: the REAL swing block of ActionBar.lua (SWING-BEGIN .. SWING-END)
and the REAL Safety.lua readers, Lua 5.1 as the game's.

    python tools/test_swing_dual.py

The user, 2026-11-16: "Dual weilding needs split swing timers for each weapon".
  a the version, the header note, the block compiles and is one do ... end; the event wiring (PLAYER_SWING, UNIT_ATTACK_SPEED for the
    player, WEAPON_SLOT_CHANGED, PLAYER_EQUIPMENT_CHANGED 16 / 17) and the state doc's sw / so;
  b one weapon: ONE swing (hudS.sw), no so - exactly as before (the most recent swing);
  c dual wield: sw = the main hand's swing, so = the off hand's, each with its own length, each reset only by its own PLAYER_SWING;
  d Auto Shot running: the ranged line alone, no so;
  e a haste change mid-swing rescales the running swing (its share done kept); a swing that already ran out is left alone;
  f a hand swapped mid-fight: its running swing starts again now at the new weapon's speed; an off hand left with no weapon drops its
    swing (one line again); a swap with no running swing starts nothing;
  g the off hand's speed not plain (secret): an off-hand swing still running stands in; long gone = one line;
  h no trace: no secure / protected call, no binding, macro or CVar write in the block.
Needs lupa. Exit code 1 on any failure."""
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from wowstub import ADDONS  # noqa: E402
from lupa import lua51 as lupa  # noqa: E402

AB = open(os.path.join(ADDONS, "WowCompanion", "ActionBar.lua"), encoding="utf-8").read()
QUOTE = "Dual weilding needs split swing timers for each weapon"

fails = 0
passes = 0


def check(ok, name, got=''):
    global fails, passes
    print(('PASS ' if ok else 'FAIL ') + name + ((' -> ' + str(got)) if got != '' and not ok else ''))
    if ok:
        passes += 1
    else:
        fails += 1


m = re.search(r'-- SWING-BEGIN[^\n]*\n(.*?)-- SWING-END', AB, re.S)
check(m is not None, "a. the swing block (SWING-BEGIN .. SWING-END) is there")
if m is None:
    print("%d passed, %d failed" % (passes, fails))
    sys.exit(1)
BLOCK = m.group(1)

# ---------------------------------------------------------------- a. the version, the notes, the wiring
v = re.search(r'local NB = \{ V = "nbar(\d+)"', AB)
check(v is not None and int(v.group(1)) >= 5, "a. ActionBar's tag is nbar5 or later", v and v.group(0))
check(QUOTE in "\n".join(AB.split("\n")[:40]) and QUOTE in BLOCK, "a. the header note and the block quote the user")
check('\ndo\n' in BLOCK and BLOCK.rstrip().endswith('end'), "a. the block is one do ... end (its locals scoped)")
check(AB.count('elseif event == "PLAYER_SWING" then\n        WC.swingNote(a1, a2)') == 1, "a. PLAYER_SWING -> WC.swingNote")
check('RegisterUnitEvent("UNIT_ATTACK_SPEED", "player")' in AB and 'elseif event == "UNIT_ATTACK_SPEED" then' in AB,
      "a. UNIT_ATTACK_SPEED registered for the player and handled")
check('"WEAPON_SLOT_CHANGED", "PLAYER_EQUIPMENT_CHANGED",' in AB and 'WC.swingWeapons(nil)' in AB
      and 'if slot == 16 or slot == 17 then WC.swingWeapons(slot)' in AB, "a. the weapon-swap events registered and handled")
check('st.sw, st.so = WC.swingDoc(SN(GetTime) or 0)' in AB and AB.count('WC.swingDoc(') == 2,
      "a. the state doc carries sw and so (in a fight only, as before)")
i_doc = AB.index('st.sw, st.so = WC.swingDoc')
check(AB.rfind('if combat then', 0, i_doc) > AB.rfind('local st = {', 0, i_doc), "a. ... inside the fight's branch")

# ---------------------------------------------------------------- the stub game
STUB = r'''
WowCompanion = {}
NOW = 100
function GetTime() return NOW end
SPD = { 2.6 }                                  -- UnitAttackSpeed("player")'s returns: main, off (nil = no off-hand weapon)
SECRET_OFF = false
SMT = { __eq = function() error("secret compared") end, __lt = function() error("secret compared") end,
        __le = function() error("secret compared") end, __index = function() error("secret indexed") end }
function issecretvalue(v) return type(v) == "table" and rawequal(getmetatable(v), SMT) end
function UnitAttackSpeed(u)
    if SECRET_OFF then return SPD[1], setmetatable({}, SMT) end
    return SPD[1], SPD[2]
end
SPY = {}
for _, n in ipairs({ "SetBinding", "SaveBindings", "CreateMacro", "EditMacro", "SetCVar", "PlaceAction", "CastSpellByName",
                     "RunMacroText", "AttackTarget", "StartAttack", "UseInventoryItem", "EquipItemByName" }) do
    _G[n] = function() SPY[#SPY + 1] = n end
end
'''


def boot():
    L = lupa.LuaRuntime(unpack_returned_tuples=True)
    L.execute(STUB)
    L.eval('function(src, name) local f, e = loadstring(src, name); if not f then error(e) end; return f end')(
        open(os.path.join(ADDONS, "WowCompanion", "Safety.lua"), encoding="utf-8").read(), "@Safety.lua")("WowCompanion")
    f = L.eval('function(src) local f, e = loadstring("local WC = _G.WowCompanion\\nlocal S, SN = WC.S, WC.SN\\n" .. src, '
               '"@ActionBar.lua#swing"); if not f then error(e) end; return f end')
    f(BLOCK)()
    return L


def doc(L):
    """(sw, so) as dicts (None = absent)"""
    r = L.eval('{ WowCompanion.swingDoc(NOW) }')
    out = []
    for i in (1, 2):
        t = r[i]
        out.append(None if t is None else {k: t[k] for k in ('k', 's', 'd')})
    return out[0], out[1]


def swing(L, d, k, at=None):
    if at is not None:
        L.execute('NOW = %r' % at)
    L.execute('WowCompanion.swingNote(%r, %d)' % (d, k))


try:
    L = boot()
    loaded = True
except Exception as e:                                 # a load error fails everything below
    check(False, "a. the block loads under Lua 5.1", str(e)[:300])
    loaded = False

if loaded:
    # ---------------------------------------------------------------- b. one weapon: one line, as before
    sw, so = doc(L)
    check(sw is None and so is None, "b. no swing yet: nothing", (sw, so))
    swing(L, 2.6, 0, 100)
    sw, so = doc(L)
    check(sw == {'k': 0, 's': 100, 'd': 2.6} and so is None, "b. one weapon: hudS.sw the main hand's swing, no so", (sw, so))
    swing(L, 2.6, 0, 102.6)
    sw, so = doc(L)
    check(sw['s'] == 102.6 and so is None, "b. the next swing resets it", (sw, so))

    # ---------------------------------------------------------------- c. dual wield: two lines, each its own
    L = boot()
    L.execute('SPD = { 2.6, 1.8 }')
    swing(L, 2.6, 0, 100)
    swing(L, 1.8, 1, 100.4)
    sw, so = doc(L)
    check(sw == {'k': 0, 's': 100, 'd': 2.6} and so == {'k': 1, 's': 100.4, 'd': 1.8},
          "c. dual wield: sw = the main hand (2.6 s), so = the off hand (1.8 s)", (sw, so))
    swing(L, 1.8, 1, 102.2)
    sw, so = doc(L)
    check(sw['s'] == 100 and so['s'] == 102.2, "c. the off hand's swing resets only the off hand", (sw, so))
    swing(L, 2.6, 0, 102.6)
    sw, so = doc(L)
    check(sw['s'] == 102.6 and so['s'] == 102.2, "c. the main hand's swing resets only the main hand", (sw, so))
    L2 = boot()
    L2.execute('SPD = { 2.6, 1.8 }')
    swing(L2, 1.8, 1, 100)
    sw, so = doc(L2)
    check(sw is None and so == {'k': 1, 's': 100, 'd': 1.8}, "c. the off hand first: it stays on so (no jump from line to line)", (sw, so))

    # ---------------------------------------------------------------- d. Auto Shot: the ranged line alone
    swing(L, 3.0, 2, 103)
    L.execute('NOW = 104')
    sw, so = doc(L)
    check(sw is not None and sw['k'] == 2 and so is None, "d. Auto Shot running: the one ranged line, no so", (sw, so))
    L.execute('NOW = 106.5')
    sw, so = doc(L)
    check(sw is not None and sw['k'] == 0 and so is not None and so['k'] == 1, "d. Auto Shot over: the two hands again", (sw, so))

    # ---------------------------------------------------------------- e. haste
    L = boot()
    L.execute('SPD = { 2.0, 1.6 }')
    swing(L, 2.0, 0, 100)
    swing(L, 1.6, 1, 100)
    L.execute('NOW = 101; SPD = { 1.5, 1.2 }')                    # a 33 % haste buff half way through the main hand's swing
    L.execute('WowCompanion.swingSpeed()')
    sw, so = doc(L)
    check(abs(sw['d'] - 1.5) < 0.006 and abs(sw['s'] - 100.25) < 0.002, "e. haste: the main hand's running swing rescaled (half done kept)", sw)
    check(abs(so['d'] - 1.2) < 0.006 and abs((101 - so['s']) / so['d'] - 1 / 1.6) < 0.01, "e. ... and the off hand's", so)
    L.execute('WowCompanion.swingSpeed()')
    sw2, so2 = doc(L)
    check(sw2 == sw and so2 == so, "e. the same speed again: nothing moves", (sw2, so2))
    L.execute('NOW = 110; SPD = { 2.0, 1.6 }; WowCompanion.swingSpeed()')
    sw3, so3 = doc(L)
    check(sw3 == sw and so3 == so, "e. a swing that already ran out is left alone", (sw3, so3))

    # ---------------------------------------------------------------- f. a hand swapped mid-fight
    L = boot()
    L.execute('SPD = { 2.6, 1.8 }')
    swing(L, 2.6, 0, 100)
    swing(L, 1.8, 1, 100.5)
    L.execute('NOW = 101; SPD = { 2.6, 2.4 }; WowCompanion.swingWeapons(17)')    # a new off-hand dagger
    sw, so = doc(L)
    check(sw['s'] == 100 and so == {'k': 1, 's': 101, 'd': 2.4}, "f. the off hand swapped: its swing starts again at the new speed", (sw, so))
    L.execute('NOW = 101.5; SPD = { 3.3, nil }; WowCompanion.swingWeapons(nil)')  # to a two-hander (the game's WEAPON_SLOT_CHANGED)
    sw, so = doc(L)
    check(sw == {'k': 0, 's': 101.5, 'd': 3.3} and so is None, "f. a two-hander: the main hand restarts, the off hand's line goes", (sw, so))
    L.execute('NOW = 102; SPD = { 3.3, nil }; WowCompanion.swingNote(1.8, 1)')    # (a stray off-hand swing would not bring it back)
    L.execute('WowCompanion.swingWeapons(17)')
    sw, so = doc(L)
    check(so is None, "f. ... and stays one line", (sw, so))
    L = boot()
    L.execute('SPD = { 2.6, 1.8 }')
    swing(L, 2.6, 0, 100)
    swing(L, 1.8, 1, 100)
    L.execute('NOW = 120; SPD = { 2.2, 1.8 }; WowCompanion.swingWeapons(16)')
    sw, so = doc(L)
    check(sw['s'] == 100 and sw['d'] == 2.6, "f. a swap with no running swing starts nothing", sw)
    L.execute('NOW = 120; SPD = { 2.2, 1.8 }; WowCompanion.swingWeapons(18)')
    check(doc(L)[0]['s'] == 100, "f. another slot (18) touches nothing")

    # ---------------------------------------------------------------- g. the off hand's speed not plain
    L = boot()
    L.execute('SPD = { 2.6, 1.8 }; SECRET_OFF = true')
    swing(L, 2.6, 0, 100)
    swing(L, 1.8, 1, 100.4)
    L.execute('NOW = 101')
    sw, so = doc(L)
    check(sw is not None and sw['k'] == 0 and so is not None and so['k'] == 1,
          "g. a secret off-hand speed: an off-hand swing running stands in (two lines)", (sw, so))
    L.execute('NOW = 110')
    sw, so = doc(L)
    check(so is None and sw is not None, "g. ... long gone: one line again", (sw, so))
    check(int(L.eval('#SPY')) == 0, "h. no call into the game's writes", int(L.eval('#SPY')))

check(not re.search(r'\b(SetBinding|SaveBindings|CreateMacro|EditMacro|SetCVar|PlaceAction|CastSpell\w*|RunMacroText|SetAttribute|'
                    r'UseInventoryItem|EquipItemByName|PickupInventoryItem|StartAttack|AttackTarget)\s*\(', BLOCK),
      "h. no trace: the block reads only (no protected call, no binding / macro / CVar / attribute write)")

print("%d passed, %d failed" % (passes, fails))
sys.exit(1 if fails else 0)
