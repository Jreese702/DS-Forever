"""char-popout / Bags bags-swap1 / Character c20, THE SWAP BETWEEN TWO WORN SLOTS (the user, 2026-11-16: "on weapons that are both 1
hand or can actually be swapped to the offhand or vice versa then that option should be listed in the pop out options."). The add-on's
half, off-device: the REAL add-on files in Lua 5.1 with stand-in game functions.

PART 1 (WowCompanion/Bags.lua, tools/test_equip_slideout.py's stand-in game plus a paper doll's own pick-up of a worn piece):
  - the arm "swap" (WC._bagsUnequipArm("swap")), then two bmvq columns (slot - 1): FROM, then TO -> WC.bagsSwapWorn(from, to);
  - two one-handers change hands (the game swapping them itself, or leaving TO's on the cursor: it goes into FROM); rings, trinkets;
  - a main-hand one-hander into the off hand over a SHIELD: the shield into the first free bag slot first; no room: "full", nothing moved;
  - bags-swap2 (Character c21; THE USER, 2026-11-16, picked "Allow it in fights"): IN A FIGHT the two hands' swap goes weapon
    Unequip's watched route (the game's own PickupInventoryItem / EquipCursorItem, its ADDON_ACTION_BLOCKED watched): it moves; a
    block on the pick-up or on the equip = "combat", nothing moved, the cursor clear; a block after the answer = a late "combat" swap
    answer; a locked piece (nothing reaches the cursor) = "refused"; a displaced shield with the bags full = "full"; rings and trinkets
    in a fight = "combat", nothing picked up;
  - the refusals: something on the cursor (cursor), an empty FROM (empty), a piece the game's slot rules keep out of TO
    (bad: a one-hander into the off hand for a class that can't dual wield, a shield into the main hand), inside SETTLE (settle);
  - the doubled presses of both columns swallowed (never a pick of a worn piece), a late TO column said late, a column 19+ disarms;
  - every answer goes to WC.character.swapAck (by "swap") and is confirmed 0.6 s later.
PART 2 (the REAL Character.lua beside Bags.lua, tools/wowstub.py's world, as tools/test_equip_slideout.py PART 2):
  - Character's version is c21; the eqs part carries `ws` (the swap's arm) on its own cell, kept by the sets' retain;
  - wswap, then two columns: the swap happens and the eqs ack is op "swap", ok; the rings in a fight: the ack says combat and nothing
    moves; the two hands in a fight: they change hands, ack ok, no lockdown breach;
  - no trace: no spied call, no lockdown breach, no Lua error.
Run: python -B tools/test_char_swap.py
"""
import os
import re
import sys
from lupa import lua51

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ADD = os.path.join(ROOT, 'companion', 'app', 'src', 'main', 'assets', 'companion', 'addons')
BAGS = open(os.path.join(ADD, 'WowCompanion', 'Bags.lua'), encoding='utf-8').read()
CHAR = open(os.path.join(ADD, 'WowCompanionCharacter', 'Character.lua'), encoding='utf-8').read()
SLIDE = open(os.path.join(ROOT, 'tools', 'test_equip_slideout.py'), encoding='utf-8').read()
PRELUDE = re.search(r"\nL\.execute\(r'''(.*?)'''\)", SLIDE, re.S).group(1)
passes = fails = 0


def check(ok, what, got=None):
    global passes, fails
    if ok:
        passes += 1; print('PASS', what)
    else:
        fails += 1; print('FAIL', what, '->', got)


check('bags-swap1 (Character c20; the user, 2026-11-16: "on weapons that are both 1 hand' in BAGS,
      "Bags.lua's header note (bags-swap1) quotes the user")
check('c20 (char-popout; the user, 2026-11-16: "on weapons that are both 1 hand' in CHAR, "Character.lua's version note (c20) quotes the user")
check('bags-swap2 (Character c21; THE USER, 2026-11-16, picked "Allow it in fights": the hand swap works in combat; if the game refuses, the' in BAGS,
      "Bags.lua's header note (bags-swap2) quotes the user's pick")
check('c21 (char-popout; THE USER, 2026-11-16, picked "Allow it in fights"' in CHAR, "Character.lua's version note (c21) quotes the user's pick")

# ====================================================================================== PART 1: Bags.lua, the stand-in game
L = lua51.LuaRuntime(unpack_returned_tuples=True)
L.execute(PRELUDE)
L.execute(r'''
-- a paper doll: the worn pieces, the game's own pick-up of one, its EquipCursorItem and a bag slot's put-down
EQUIP[7202] = "INVTYPE_WEAPON"; EQUIP[7203] = "INVTYPE_WEAPONMAINHAND"; EQUIP[7051] = "INVTYPE_FINGER"
SLOTS_OF = { INVTYPE_WEAPON = { 16, 17 }, INVTYPE_WEAPONMAINHAND = { 16 }, INVTYPE_SHIELD = { 17 }, INVTYPE_FINGER = { 11, 12 },
             INVTYPE_TRINKET = { 13, 14 }, INVTYPE_2HWEAPON = { 16 } }
function fits(id, slot)
    local loc = EQUIP[id]
    if loc == "INVTYPE_WEAPON" and slot == 17 and not DW then return false end
    for _, s in ipairs(SLOTS_OF[loc] or {}) do if s == slot then return true end end
    return false
end
GAME_SWAPS = true    -- the game swaps two worn pieces itself (else it leaves TO's piece on the cursor)
PICKS = {}
BLOCK = nil      -- bags-swap2: "pick" / "equip": the game blocks that call in a fight (ADDON_ACTION_BLOCKED naming us)
LOCKED = false   -- bags-swap2: the piece is locked: the pick-up brings nothing onto the cursor
function PickupInventoryItem(slot)
    PICKS[#PICKS + 1] = slot
    if COMBAT and BLOCK == "pick" then fire("ADDON_ACTION_BLOCKED", "WowCompanion", "PickupInventoryItem()"); return end
    if LOCKED then return end
    if not ST.cursor and WORN[slot] then ST.cursor = { inv = slot, id = WORN[slot] } end
end
function EquipCursorItem(slot)
    EQUIPS[#EQUIPS + 1] = slot
    if COMBAT and BLOCK == "equip" then fire("ADDON_ACTION_BLOCKED", "WowCompanion", "EquipCursorItem()"); return end
    local c = ST.cursor
    if not c then return end
    if not fits(c.id, slot) then return end                                  -- the game refuses: the piece stays on the cursor
    local was = WORN[slot]
    if c.inv then WORN[c.inv] = nil elseif c.bag then BAG[c.bag][c.slot] = nil end
    WORN[slot] = c.id
    ST.cursor = nil
    if was then
        if GAME_SWAPS and c.inv and fits(was, c.inv) then WORN[c.inv] = was
        else ST.cursor = { id = was, loose = true } end
    end
end
C_Container.PickupContainerItem = function(b, s)
    local c = ST.cursor
    if c then
        if BAG[b][s] then return end
        BAG[b][s] = c.id
        if c.inv then WORN[c.inv] = nil elseif c.bag then BAG[c.bag][c.slot] = nil end
        ST.cursor = nil
        return
    end
    if BAG[b][s] then ST.cursor = { bag = b, slot = s, id = BAG[b][s] } end
end
LOST = {}
function ClearCursor() if ST.cursor and ST.cursor.loose then LOST[#LOST + 1] = ST.cursor.id end; ST.cursor = nil end
ACKS = {}
WowCompanion.character = { swapAck = function(t) ACKS[#ACKS + 1] = t end, bindWorn = function() end }
function lastAck() return ACKS[#ACKS] end
function armSwap(from, to, gap)
    NOW = NOW + 5
    WowCompanion._bagsUnequipArm("swap")
    NOW = NOW + 0.12; OPS.bmvq(nil, from - 1)
    NOW = NOW + (gap or 0.12); OPS.bmvq(nil, to - 1)
end
''')
load = L.eval('function(src, name, addon) local f, e = loadstring(src, "@" .. name); if not f then error(e) end; return f(addon) end')
try:
    load(BAGS, 'Bags.lua', 'WowCompanion')
    ok_load = True
except Exception as e:  # noqa: BLE001
    ok_load = False; print(e)
check(ok_load, "Bags.lua loads (stand-in game) with no error")
L.execute('WowCompanionDB = {}; fire("PLAYER_LOGIN"); WowCompanion._bagsLayout = { { bag = 0, slots = 8 } }; WowCompanion.bagsPadRows({ { bag = 0, slots = 8 } })')
check(L.eval('type(WowCompanion.bagsSwapWorn)') == 'function', "WC.bagsSwapWorn exists")


def worn(s):
    return L.eval('WORN[%d]' % s)


# ---- two one-handers change hands (a dual wielder): the off hand's into the main hand
L.execute('DW = true; WORN[16] = 7250; WORN[17] = 7202; BAG[0] = {}; ACKS = {}')
L.execute('armSwap(17, 16)')
a = L.eval('lastAck()')
check(worn(16) == 7202 and worn(17) == 7250 and L.eval('ST.cursor') is None,
      "the off hand's one-hander into the main hand: the two change hands", (worn(16), worn(17)))
check(a is not None and a.ok is True and a.by == 'swap' and a.to.inv == 16 and a['from'].inv == 17,
      "...answered ok to the Character room (by swap, from 17 to 16)", a and (a.ok, a.why))
check(list(L.eval('PICKS').values()) == [17] and list(L.eval('EQUIPS').values())[-1] == 16,
      "...the game's own way: PickupInventoryItem(17), EquipCursorItem(16)", (list(L.eval('PICKS').values()), list(L.eval('EQUIPS').values())))
L.execute('run(1)')
a = L.eval('lastAck()')
check(a.confirm is True, "...confirmed 0.6 s later off the slot itself", a.confirm)

# the game leaves TO's piece on the cursor: it goes into FROM
L.execute('GAME_SWAPS = false; armSwap(16, 17)')
check(worn(16) == 7250 and worn(17) == 7202 and L.eval('ST.cursor') is None and L.eval('#LOST') == 0,
      "the main hand's into the off hand, the game leaving the other on the cursor: it goes into the main hand", (worn(16), worn(17)))
L.execute('GAME_SWAPS = true; run(1)')

# ---- rings and trinkets
L.execute('WORN[11] = 7050; WORN[12] = 7051; armSwap(11, 12)')
check(worn(11) == 7051 and worn(12) == 7050 and L.eval('lastAck().ok') is True, "the rings change fingers", (worn(11), worn(12)))
L.execute('run(1); WORN[14] = nil; armSwap(13, 14)')
check(worn(13) is None and worn(14) == 7150 and L.eval('lastAck().ok') is True, "a trinket into the empty trinket slot", (worn(13), worn(14)))
L.execute('run(1)')

# ---- over a shield: the shield goes into the bags first; no room = full, nothing moved
L.execute('WORN[16] = 7250; WORN[17] = 7260; BAG[0] = { [1] = 1, [2] = 2, [3] = 3 }; armSwap(16, 17)')
check(worn(17) == 7250 and worn(16) is None and L.eval('BAG[0][4]') == 7260 and L.eval('lastAck().ok') is True,
      "the main hand's one-hander into the off hand over a shield: the shield into the first free bag slot", (worn(16), worn(17), L.eval('BAG[0][4]')))
L.execute('run(1); WORN[16] = 7250; WORN[17] = 7260; BAG[0] = { 1, 2, 3, 4, 5, 6, 7, 8 }; armSwap(16, 17)')
check(worn(16) == 7250 and worn(17) == 7260 and L.eval('lastAck().ok') is False and L.eval('lastAck().why') == 'full',
      "...bags full: 'full', nothing moved", L.eval('lastAck().why'))
# a main-hand-only weapon into the off hand: bad
L.execute('BAG[0] = {}; WORN[16] = 7203; WORN[17] = 7202; armSwap(16, 17)')
check(worn(16) == 7203 and L.eval('lastAck().why') == 'bad', "a main-hand-only weapon into the off hand: bad, nothing moved")
# the off hand's one-hander into the main hand over a main-hand-only weapon: that one to the bags
L.execute('armSwap(17, 16)')
check(worn(16) == 7202 and worn(17) is None and L.eval('BAG[0][1]') == 7203 and L.eval('lastAck().ok') is True,
      "the off hand's one-hander over a main-hand-only weapon: that one into the bags", (worn(16), worn(17), L.eval('BAG[0][1]')))
L.execute('run(1)')
# a shield into the main hand: bad
L.execute('WORN[16] = 7250; WORN[17] = 7260; armSwap(17, 16)')
check(worn(16) == 7250 and worn(17) == 7260 and L.eval('lastAck().why') == 'bad', "a shield into the main hand: bad")
# no dual wield: a one-hander never into the off hand
L.execute('DW = false; WORN[16] = 7250; WORN[17] = nil; armSwap(16, 17)')
check(worn(16) == 7250 and worn(17) is None and L.eval('lastAck().why') == 'bad', "no dual wield: a one-hander into the off hand is bad")
L.execute('DW = true')

# ---- the refusals
# ---- bags-swap2: IN A FIGHT (the user's pick "Allow it in fights"): the two hands go weapon Unequip's watched route
L.execute('run(1); BAG[0] = {}; WORN[16] = 7250; WORN[17] = 7202; COMBAT = true; PICKS = {}; EQUIPS = {}; ACKS = {}; armSwap(17, 16)')
a = L.eval('lastAck()')
check(worn(16) == 7202 and worn(17) == 7250 and L.eval('ST.cursor') is None and a.ok is True and a.fight is True,
      "a fight: the off hand's one-hander into the main hand: the hands change, answered ok (fight)", (worn(16), worn(17), a.ok, a.why))
check(list(L.eval('PICKS').values()) == [17] and list(L.eval('EQUIPS').values()) == [16],
      "...the same calls as out of a fight (PickupInventoryItem(17), EquipCursorItem(16)), no other route",
      (list(L.eval('PICKS').values()), list(L.eval('EQUIPS').values())))
L.execute('run(1)')
check(L.eval('lastAck().confirm') is True, "...confirmed 0.6 s later")
# the game blocks the pick-up: combat, nothing moved
L.execute('BLOCK = "pick"; armSwap(16, 17)')
check(worn(16) == 7202 and worn(17) == 7250 and L.eval('ST.cursor') is None and L.eval('lastAck().why') == 'combat',
      "a fight, the game blocks the pick-up: combat, nothing moved, the cursor clear", (worn(16), worn(17), L.eval('lastAck().why')))
# the game blocks the equip: the piece goes back on, combat
L.execute('BLOCK = "equip"; armSwap(16, 17)')
check(worn(16) == 7202 and worn(17) == 7250 and L.eval('ST.cursor') is None and L.eval('lastAck().why') == 'combat',
      "a fight, the game blocks the equip: combat, the piece back on, the cursor clear", (worn(16), worn(17), L.eval('lastAck().why')))
# a block arriving after the answer: the watcher answers it as a swap
L.execute('BLOCK = nil; armSwap(16, 17)')
check(worn(16) == 7250 and L.eval('lastAck().ok') is True, "a fight: the main hand's into the off hand (ok)")
L.execute('fire("ADDON_ACTION_BLOCKED", "WowCompanion", "EquipCursorItem()")')
a = L.eval('lastAck()')
check(a.ok is False and a.why == 'combat' and a.late is True and a.by == 'swap',
      "...a block a moment after the answer: a late combat answer by swap", (a.ok, a.why, a.late, a.by))
L.execute('run(1)')
# the piece is locked (nothing reaches the cursor): refused
L.execute('LOCKED = true; armSwap(17, 16)')
check(worn(16) == 7250 and worn(17) == 7202 and L.eval('lastAck().why') == 'refused',
      "a fight, a locked piece: refused (the game refused it), nothing moved", L.eval('lastAck().why'))
L.execute('LOCKED = false')
# over a shield, the bags full: full
L.execute('run(1); WORN[16] = 7250; WORN[17] = 7260; BAG[0] = { 1, 2, 3, 4, 5, 6, 7, 8 }; armSwap(16, 17)')
check(worn(16) == 7250 and worn(17) == 7260 and L.eval('lastAck().why') == 'full', "a fight, over a shield, bags full: full")
L.execute('BAG[0] = {}; armSwap(16, 17)')
check(worn(17) == 7250 and worn(16) is None and L.eval('BAG[0][1]') == 7260 and L.eval('lastAck().ok') is True,
      "a fight, over a shield with room: the shield into the bags, the one-hander into the off hand")
L.execute('run(1)')
# rings and trinkets stay out of a fight
L.execute('WORN[11] = 7050; WORN[12] = 7051; WORN[13] = 7150; WORN[14] = 7151; PICKS = {}; armSwap(11, 12)')
check(worn(11) == 7050 and worn(12) == 7051 and L.eval('lastAck().why') == 'combat' and L.eval('#PICKS') == 0,
      "a fight: the rings are refused (combat), nothing picked up", L.eval('lastAck().why'))
L.execute('armSwap(14, 13)')
check(worn(13) == 7150 and worn(14) == 7151 and L.eval('lastAck().why') == 'combat' and L.eval('#PICKS') == 0,
      "a fight: the trinkets are refused (combat), nothing picked up", L.eval('lastAck().why'))
L.execute('NOW = NOW + 5; WowCompanion.bagsSwapWorn(18, 16)')
check(L.eval('lastAck().why') == 'combat' and L.eval('#PICKS') == 0, "a fight: the ranged slot never swaps", L.eval('lastAck().why'))
L.execute('COMBAT = false; run(1); BAG[0] = {}; WORN[16] = 7250; WORN[17] = 7202; WORN[14] = nil; WORN[13] = 7150')
n_picks = L.eval('#PICKS')
L.execute('ST.cursor = { bag = 0, slot = 1, id = 1 }; armSwap(17, 16)')
check(worn(16) == 7250 and L.eval('lastAck().why') == 'cursor' and L.eval('#PICKS') == n_picks,
      "something on the cursor: cursor, nothing picked up")
L.execute('ST.cursor = nil; WORN[12] = nil; WORN[11] = 7050; armSwap(12, 11)')
check(L.eval('lastAck().why') == 'empty' and worn(11) == 7050, "an empty FROM: empty")
L.execute('NOW = NOW + 5; WowCompanion.bagsSwapWorn(11, 12); WowCompanion.bagsSwapWorn(12, 11)')
check(L.eval('lastAck().why') == 'settle' and worn(12) == 7050, "a second swap inside SETTLE: settle", L.eval('lastAck().why'))
L.execute('run(1)')

# ---- the columns: doubled presses swallowed, late, disarm
L.execute('WORN[16] = 7250; WORN[17] = 7202; ACKS = {}; armSwap(17, 16)')
check(worn(16) == 7202 and L.eval('#ACKS') == 1, "the swap again (one answer)")
mv0 = L.eval('#EMITS')
L.execute('NOW = NOW + 0.1; OPS.bmvq(nil, 16); NOW = NOW + 0.1; OPS.bmvq(nil, 15)')
check(worn(16) == 7202 and worn(17) == 7250 and L.eval('#ACKS') == 1 and L.eval('lastMove().picked') is None,
      "...the input path's second presses of BOTH columns swallowed: no pick of a worn piece, nothing moved",
      L.eval('lastMove() and lastMove().picked'))
L.execute('run(1); ACKS = {}')
L.execute('armSwap(17, 16, 3)')
check(L.eval('lastAck().why') == 'late' and worn(16) == 7202, "a TO column after the arm's 2 s: late, nothing moved", L.eval('lastAck().why'))
L.execute('NOW = NOW + 5; WowCompanion._bagsUnequipArm("swap"); NOW = NOW + 0.1; OPS.bmvq(nil, 16); NOW = NOW + 0.1; OPS.bmvq(nil, 30)')
L.execute('NOW = NOW + 0.1; ACKS = {}; OPS.bmvq(nil, 15)')
check(L.eval('#ACKS') == 0 and worn(16) == 7202, "a Bags-room column (19+) between FROM and TO disarms the swap: TO's column moves nothing")
check(L.eval('#TRACE') == 0, "part 1, no trace: no SetBinding / macro / CVar / Blizzard open-close / StaticPopup call",
      list(L.eval('TRACE').values()))

# ====================================================================================== PART 2: the REAL Character.lua's wswap
F = {
    "safety": os.path.join(ADD, "WowCompanion", "Safety.lua"),
    "tappad": os.path.join(ADD, "WowCompanion", "TapPad.lua"),
    "book": os.path.join(ADD, "WowCompanionSpells", "Book.lua"),
    "padmap": os.path.join(ADD, "WowCompanionSpells", "PadMap.lua"),
    "padtabctl": os.path.join(ADD, "WowCompanionSpells", "PadTabCtl.lua"),
    "bags": os.path.join(ADD, "WowCompanion", "Bags.lua"),
    "character": os.path.join(ADD, "WowCompanionCharacter", "Character.lua"),
}
SRC = {k: open(p, encoding="utf-8").read() for k, p in F.items()}
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from wowstub import GAME  # noqa: E402
PARTY = open(os.path.join(ROOT, "tools", "test_party.py"), encoding="utf-8").read()
WORLD_HELPERS = re.search(r"\nWORLD_HELPERS = r'''(.*?)'''", PARTY, re.S).group(1)
STICKS = open(os.path.join(ROOT, "tools", "test_stick_binds.py"), encoding="utf-8").read()
EXTRA = re.search(r"\nEXTRA = r'''(.*?)'''", STICKS, re.S).group(1)
AFTER = re.search(r"\nAFTER = r'''(.*?)'''", STICKS, re.S).group(1)
BINDBOX = open(os.path.join(ROOT, "tools", "test_bags_bindbox.py"), encoding="utf-8").read()
MORE = re.search(r"\nMORE = r'''(.*?)'''", BINDBOX, re.S).group(1)
WORLD = re.search(r"\nWORLD = r'''(.*?)'''", SLIDE, re.S).group(1)
# two rings worn, a paper doll's pick-up and EquipCursorItem (the game swapping them itself)
DOLL = r'''
WORNC = { [11] = 7050, [12] = 7051 }
function GetInventoryItemID(unit, slot) return WORNC[slot] end
local instant = C_Item.GetItemInfoInstant
C_Item.GetItemInfoInstant = function(id)
    if id == 7050 or id == 7051 then return id, "Armor", "Misc", "INVTYPE_FINGER", 1, 4, 0 end
    if id == 7250 or id == 7202 then return id, "Weapon", "Swords", "INVTYPE_WEAPON", 1, 2, 7 end     -- bags-swap2: two one-handers
    if instant then return instant(id) end
end
DOLLC = { cur = false }               -- (a nil global reads as the stub world's STUB: a table field instead)
function PickupInventoryItem(slot) if not DOLLC.cur and WORNC[slot] then DOLLC.cur = { inv = slot, id = WORNC[slot] } end end
function EquipCursorItem(slot)
    local c = DOLLC.cur
    if not c then return end
    local was = WORNC[slot]; WORNC[c.inv] = was; WORNC[slot] = c.id; DOLLC.cur = false
end
function CursorHasItem() return DOLLC.cur and true or false end
function GetCursorInfo() if DOLLC.cur then return "item", DOLLC.cur.id end end
function ClearCursor() DOLLC.cur = false end
'''


def boot():
    W = lua51.LuaRuntime(unpack_returned_tuples=True)
    W.execute(GAME)
    W.execute(EXTRA)
    W.execute(MORE)
    W.execute(WORLD)
    W.execute(DOLL)
    ld = W.eval('function(src, name) local f, e = loadstring(src, name); if not f then error(e) end; return f end')
    for k in ("safety", "tappad", "book", "padmap", "padtabctl", "bags", "character"):
        ld(SRC[k], '@' + os.path.basename(F[k]))(os.path.basename(F[k]))
    W.execute('WowCompanion._bagsLayout = { { bag = 0, slots = 4 } }')
    W.execute(WORLD_HELPERS)
    W.execute('fire("PLAYER_LOGIN"); fire("PLAYER_ENTERING_WORLD", true, false); runTimers(NOW + 10)')
    W.execute(AFTER)
    W.execute('local c = WowCompanion._spellsBook.ctl; c.croom, c.ctrlTab, c.onTabs = false, false, false')
    W.execute('CH = WowCompanion.character')
    W.execute('WowCompanion.padMap.emit(true); runTimers(NOW + 1)')
    W.execute('function tapSet(key) CH.onSetCell(CH.eqFirst + WowCompanion.pad.slotFor("eqset", key)) end')
    W.execute('function eqs() return CH.collect.eqs() end')
    W.execute('function bmvq(col) WowCompanion.pad.rows.bmvq.handler(nil, col) end')
    return W


W = None
try:
    W = boot()
    booted = True
except Exception as e:  # noqa: BLE001
    booted = False; print(e)
check(booted, "part 2: Bags and Character files load in the stub world")
if W is not None:
    check(W.eval('CH.version') == 'c21', "Character's version is c21", W.eval('CH.version'))
    W.execute('NOW = NOW + 1; CH.touchOpen()')
    e = W.eval('eqs()')
    check(e.ws is not None and len({e.ws, e.wb, e.ba, e.bb, e.uq, e.mon, e.moff, e.msb}) == 8,
          "eqs: the swap's arm (ws) on its own cell", (e.ws, e.wb))
    W.execute('CH.collect.sets()')
    check(W.eval('WowCompanion.pad.ns.eqset.byKey.wswap') is not None, "the sets' retain keeps the swap's cell")
    W.execute('tapSet("moreon")')
    check(W.eval('eqs().more') is True, "the pop-out opened (moreon)")
    W.execute('NOW = NOW + 1; tapSet("wswap"); NOW = NOW + 0.12; bmvq(10); NOW = NOW + 0.12; bmvq(11)')
    e = W.eval('eqs()')
    check(W.eval('WORNC[11]') == 7051 and W.eval('WORNC[12]') == 7050, "wswap, then columns 10 and 11: the rings change fingers",
          (W.eval('WORNC[11]'), W.eval('WORNC[12]')))
    check(e.ack is not None and e.ack.op == 'swap' and e.ack.ok is True and e.more is False,
          "...the eqs ack is op swap, ok; the arm closed the pop-out in the add-on", e.ack and (e.ack.op, e.ack.ok, e.ack.why))
    W.execute('runTimers(NOW + 1)')
    check(W.eval('eqs().ack.op') == 'swap' and W.eval('eqs().ack.ok') is True, "...its confirm adds no refusal")
    W.execute('NOW = NOW + 2; COMBAT = true; tapSet("wswap"); NOW = NOW + 0.12; bmvq(10); NOW = NOW + 0.12; bmvq(11)')
    check(W.eval('WORNC[11]') == 7051 and W.eval('eqs().ack.op') == 'swap' and W.eval('eqs().ack.why') == 'combat',
          "in a fight: nothing moves, the ack says combat", W.eval('eqs().ack.why'))
    W.execute('COMBAT = false')
    # bags-swap2: the two hands in a fight change hands (a dual wielder), the eqs ack ok, no lockdown breach
    W.execute('WORNC[16] = 7250; WORNC[17] = 7202; CanDualWield = function() return true end')
    W.execute('NOW = NOW + 2; COMBAT = true; tapSet("wswap"); NOW = NOW + 0.12; bmvq(16); NOW = NOW + 0.12; bmvq(15)')
    check(W.eval('WORNC[16]') == 7202 and W.eval('WORNC[17]') == 7250 and W.eval('eqs().ack.op') == 'swap' and W.eval('eqs().ack.ok') is True,
          "in a fight: the two hands change hands, the ack ok", (W.eval('WORNC[16]'), W.eval('WORNC[17]'), W.eval('eqs().ack.why')))
    W.execute('runTimers(NOW + 1); COMBAT = false')
    check(W.eval('#SPY') == 0 and W.eval('#VIOL') == 0 and list(W.eval('ERRS').values()) == [],
          "part 2: no spied call (no trace), no lockdown breach, no Lua error",
          (W.eval('SPY[1]'), W.eval('VIOL[1]'), list(W.eval('ERRS').values())[:2]))

print(f'\n{passes} passed, {fails} failed')
sys.exit(1 if fails else 0)
