"""THE DISENCHANT GRID (bags-de1; the user's list 2026-11-16: "A custom grid of bag items that can be disenchanted. Hover shows the full
tooltip, as in Bags. It stays open after each one; B closes it. The disenchant cast bar is visible."; their picks the same day: every
RECOMMENDED option of design_disenchant.md). Loads the real WowCompanion/Bags.lua in Lua 5.1 with stand-in game functions and checks:
  - the strip's Disenchant icon only when the spell is known (C_SpellBook.ContainsAnyDisenchantSpell); a finger on it (bmvq 27, col 9)
    opens the grid over the Bags grid (B goes back to it, the grid's cursor where it was)
  - any cast of the spell (the game opens its bags for the pick -> the take) opens the grid, B then goes Home
  - the list: the bags' weapons / armour of quality >= 2 in bag order, locked ones greyed, refused ones hidden per item AND Enchanting rank,
    never worn gear; the green arrow (all gains / an empty slot it fits; never a red line)
  - A: ONE secure tap on the item's own cell - "c" (type spell 13262 + target-bag / target-slot) with no pick, "t" (target only) while a
    pick waits; never a /use or an item action; the cast time rides on the request (the app's predicted bar)
  - refused while casting ("busy"), while a loot window is open ("loot"), in a fight ("combat"), on a locked item; blue / purple ask first
  - B: a cast = /stopcasting (binv 39) and stay; a pick waiting = /stopspelltarget (binv 19), closed when that tap lands (or 1 s later);
    else close (to the grid it came from, else Home); in a fight Home
  - the cast's start / success / failure / the game's refusal (UI_ERROR_MESSAGE within the second, the item hidden until a skill-up)
  - the cursor stays on its item; the item gone = the same place (the next one slides in)
  - one cell per item, the least recently used one goes; the pick going on and off never frees them while the grid is open; the pull
    clears the cells that could cast
  - the game's bags closing after a pick never closes the grid; the app's close door still leaves the room
  - loot: the strip of what came after OUR disenchant; the loot pop-up takes the pad ("left-lootwait") and the grid takes it back at
    LOOT_CLOSED with its cursor (DE.retake); the room left meanwhile = no take back; the game's own question (END_BOUND_TRADEABLE): A / B passed
  - no trace (no binding / macro / CVar; no SpellStopTargeting / SpellStopCasting from add-on code)
Run: python -B tools/test_disenchant.py
"""
import os
import re
import sys
from lupa import lua51

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = open(os.path.join(ROOT, 'companion', 'app', 'src', 'main', 'assets', 'companion', 'addons', 'WowCompanion', 'Bags.lua'),
           encoding='utf-8').read()
passes = fails = 0


def check(ok, what, got=None):
    global passes, fails
    if ok:
        passes += 1; print('PASS', what)
    else:
        fails += 1; print('FAIL', what, '->', got)


L = lua51.LuaRuntime(unpack_returned_tuples=True)
L.execute(r'''
local stub
stub = setmetatable({}, { __index = function() return stub end, __call = function() return stub end })
setmetatable(_G, { __index = function(_, k) return stub end })
NOW = 100
function GetTime() return NOW end
AFTER = {}
C_Timer = { NewTicker = function() return stub end, After = function(s, fn) AFTER[#AFTER + 1] = { s = s, fn = fn } end,
            NewTimer = function() return stub end }
function RUN_TIMERS(max)
    for _ = 1, max or 50 do
        if #AFTER == 0 then return end
        local q = AFTER; AFTER = {}
        for _, t in ipairs(q) do pcall(t.fn) end
    end
end
EMITS, SECURE, OPS = {}, {}, {}
PASSED = {}
HOLDER = nil
TAKES = 0
WowCompanion = {
    S = function(fn) local ok, v = pcall(fn); if ok then return v end end,
    SN = function(fn) local ok, v = pcall(fn); if ok and type(v) == "number" then return v end end,
    CALL = function(fn) return pcall(fn) end,
    emit = function(t) EMITS[#EMITS + 1] = t end,
    send = function() end, encode = function() return "" end, push = function() end, isPlain = function() return true end,
    pad = {
        secureRow = function(name, n, init, onClick) SECURE[name] = { n = n, init = init, onClick = onClick }; return 1 end,
        op = function(name, row, fn) OPS[name] = fn end,
    },
    capture = { version = 21,
                take = function(id, spec) TAKES = TAKES + 1; SPEC = spec; HOLDER = id; return true end,
                pass = function(id, keys) PASSED[#PASSED + 1] = table.concat(keys, " "); return true end,
                release = function(id, why) RELEASED = why; HOLDER = nil; return true end,
                state = function() return { holder = rawget(_G, "HOLDER") } end },
}
for _, n in ipairs({ "MerchantFrame", "GossipFrame", "QuestFrame", "BankFrame", "MailFrame", "TradeFrame", "AuctionHouseFrame",
                     "GuildBankFrame", "ClassTrainerFrame", "CharacterFrame" }) do _G[n] = { IsShown = function() return false end } end
-- the game: bag 0 (8 slots) and bag 1 (4 slots); worn: chest (5) and main hand (16)
WORN = { [5] = 900, [16] = 902 }
BAG = { [0] = { [1] = 6948, [2] = 5001, [3] = 5002, [4] = 117, [5] = 5003, [6] = 5004 }, [1] = { [1] = 5005, [2] = 5006 } }
SLOTS = { [0] = 8, [1] = 4 }
ITEM = {      -- id = { cls, loc, quality }
    [6948] = { 15, "INVTYPE_NON_EQUIP_IGNORE", 1 }, [117] = { 0, "INVTYPE_NON_EQUIP_IGNORE", 1 },
    [5001] = { 2, "INVTYPE_WEAPON", 2 }, [5002] = { 4, "INVTYPE_CHEST", 3 }, [5003] = { 4, "INVTYPE_WAIST", 1 },
    [5004] = { 4, "INVTYPE_FINGER", 4 }, [5005] = { 4, "INVTYPE_HAND", 2 }, [5006] = { 4, "INVTYPE_BAG", 2 },
    [900] = { 4, "INVTYPE_CHEST", 2 }, [902] = { 2, "INVTYPE_WEAPON", 2 },
}
LOCKED = {}
COMBAT, TARGETING, SCT, TSID, KNOWN, CASTING, RANK = false, false, false, nil, true, nil, 120
function InCombatLockdown() return COMBAT end
function SpellIsTargeting() return TARGETING end
function SpellCanTargetItem() return SCT end
function SpellCanTargetItemID() return false end
function IsCurrentSpell(v) return false end
function UnitCastingInfo() return CASTING end
function UnitChannelInfo() return nil end
function GetInventoryItemID(_, slot) return WORN[slot] end
function GetProfessions() return 5, nil end
function GetProfessionInfo(i) if i == 5 then return "Enchanting", 1, RANK, 300, 0, 0, 333 end end
function CanDualWield() return false end
C_SpellBook = { ContainsAnyDisenchantSpell = function() return KNOWN end }
C_Spell = { GetTargetSpellID = function() return TSID end,
            GetSpellInfo = function(id) if id == 13262 then return { name = "Disenchant", iconID = 132853, castTime = 3000 } end end }
C_Item = { GetItemNameByID = function(id) return "Item " .. id end,
           GetItemInfoInstant = function(id) local t = ITEM[id] or { 15, "", 1 }; return id, "T", "S", t[2], 1, t[1], 0 end }
C_Container = {
    GetContainerNumSlots = function(b) return SLOTS[b] or 0 end,
    GetContainerItemID = function(b, s) return BAG[b] and BAG[b][s] end,
    GetContainerItemInfo = function(b, s) local id = BAG[b] and BAG[b][s]; if id then
        return { itemID = id, iconFileID = 1000 + id, quality = (ITEM[id] or { 0, "", 1 })[3], isLocked = LOCKED[b .. ":" .. s] == true } end end,
    GetContainerItemLink = function(b, s) local id = BAG[b] and BAG[b][s]; return id and ("link:" .. id) or nil end,
}
ItemLocation = { CreateFromEquipmentSlot = function(_, s) return { k = "inv" .. s } end,
                 CreateFromBagAndSlot = function(_, b, s) return { k = "bag" .. b .. ":" .. s } end }
WowCompanion._bagsLayout = { { bag = 0, slots = 8 }, { bag = 1, slots = 4 } }
''')
load = L.eval('function(src) local f, e = loadstring(src, "@Bags.lua"); if not f then error(e) end; return f end')
ok_load = True
try:
    load(SRC)()
except Exception as e:  # noqa: BLE001
    ok_load = False; print(e)
check(ok_load, "Bags.lua loads (stand-in game) with no error")
L.execute('''CELLS = {}
for col = 0, 39 do
    local c = { a = {}, hooks = {} }; function c:SetAttribute(k, v) self.a[k] = v end
    function c:HookScript(ev, fn) self.hooks[ev] = fn end
    CELLS[col] = c; SECURE.binv.init(c, col)
end''')
DE = L.eval('WowCompanion._de')
KIT = L.eval('WowCompanion._kit')
check(DE is not None and KIT is not None, "WC._de and WC._kit are published")
doc = lambda: L.eval('WowCompanion._bagsCtlDoc()')
touch = L.eval('WowCompanion._bagsTouch')
step = L.eval('WowCompanion._kit.step')
at = lambda c, k: L.eval('CELLS[%d].a["%s"]' % (c, k))
ev = L.eval('function(e, a1, a2, a3) WowCompanion._de.event(e, a1, a2, a3) end')


def rows(d):
    lst = d.bulk.list
    return [(lst[i + 1].b, lst[i + 1].s) for i in range(len(lst))]


# ---------------------------------------------------------------- the strip's icon
sd = L.eval('WowCompanion._de.stripDoc()')
check(sd is not None and sd.n == 'Disenchant' and sd.i == 132853 and sd.ct == 3000 and sd.col == 9,
      "the strip's word: the spell known -> its name, icon, cast time and the finger's column", sd and (sd.n, sd.i, sd.ct, sd.col))
L.execute('KNOWN = false')
check(L.eval('WowCompanion._de.stripDoc()') is None, "the spell not known: no icon on the strip")
L.execute('KNOWN = nil; IsPlayerSpell = function(id) return id == 13262 end')
check(L.eval('WowCompanion._de.known()') is True, "an older client without C_SpellBook's check: IsPlayerSpell answers")
L.execute('KNOWN = true')

# a finger on the icon (bmvq 27, then column 9): the room is taken and the grid opens over the Bags grid
touch(36); L.execute('WowCompanion._bagsCursorTap(0, 4)')         # (the Bags grid's cursor on the jerky first)
touch(27); L.eval('WowCompanion._bagsShutTap')(9)
d = doc()
check(d.on is True and d.mode == 'bulk' and d.bulk is not None and d.bulk.back == 'grid',
      "a finger on the strip's icon: the room taken, the Disenchant grid over the Bags grid (B goes back to it)", d and d.mode)
check(L.eval('TAKES') == 1, "... through the ordinary take (one)", L.eval('TAKES'))
check(rows(d) == [(0, 2), (0, 3), (0, 6), (1, 1)],
      "the list: bag order, weapons / armour of quality >= 2 only (no hearthstone, jerky, white belt, bag; nothing worn)", rows(d))
check(d.bulk.n == 'Disenchant' and d.bulk.i == 132853 and d.bulk.ct == 3000 and d.bulk.sel == 1,
      "the document: the spell's name, icon, cast time; the cursor on the first item", (d.bulk.n, d.bulk.i, d.bulk.ct, d.bulk.sel))
check(d.cur.bag == 0 and d.cur.slot == 2, "the room's cursor follows the grid's (the tooltip is that item's)", (d.cur.bag, d.cur.slot))

# ---------------------------------------------------------------- the D-pad
touch(35)                                                          # right
check(doc().bulk.sel == 2, "right: the next item")
touch(38)                                                          # down: no second row
check(doc().bulk.sel == 2, "down with no row under it: stays", doc().bulk.sel)
touch(34); touch(34)
check(doc().bulk.sel == 1, "left stops at the first", doc().bulk.sel)

# ---------------------------------------------------------------- A: one secure tap on the item's own cell ("c": cast + target)
touch(31)
d = doc()
c = d.use and d.use.cell
check(d.note == 'de-go' and c is not None and 20 <= c <= 38, "A on the green sword: one use request, on a binv cell 20..38", (d.note, c))
check(at(c, 'type') == 'spell' and at(c, 'spell') == 13262 and at(c, 'target-bag') == 0 and at(c, 'target-slot') == 2
      and at(c, 'macrotext') is None and at(c, 'item') is None,
      "no pick waiting: the cell CASTS Disenchant and targets bag 0 slot 2 (never /use, never an item action)",
      (at(c, 'type'), at(c, 'spell'), at(c, 'target-bag'), at(c, 'target-slot')))
check(d.use.ct == 3000, "the request carries the cast time (the app's bar starts at the press)", d.use.ct)
sword_cell = c
# the cast starts, ends; the grid stays open on its item; the item gone = the same place
ev('UNIT_SPELLCAST_START', 'player', 'g', 13262)
d = doc()
check(d.bulk.cast is not None and d.bulk.cast.s == 2 and d.bulk.busy is True, "the cast starts: the tile's cast and busy", d.bulk.cast and d.bulk.cast.s)
L.execute('CASTING = "Disenchant"')
seq = doc().use.seq
touch(35); touch(31)
d = doc()
check(d.note == 'de-busy' and d.use.seq == seq and d.bulk.fail.why == 'busy', "A while casting: refused ('busy'), nothing tapped", (d.note, d.bulk.fail.why))
touch(34)
touch(33)                                                          # B during the cast
d = doc()
check(d.use.stopcast is True and d.mode == 'bulk' and d.note == 'de-stop', "B during the cast: /stopcasting asked, the grid stays", (d.note, d.mode))
check(L.eval('WowCompanion._kit.STOPCAST_COL') == 39, "... binv 39 (KitTarget.ofUse stopcast)")
L.execute('CASTING = nil')
ev('UNIT_SPELLCAST_INTERRUPTED', 'player', 'g', 13262)
d = doc()
check(d.bulk.fail.why == 'stop' and d.bulk.cast is None, "the cast cut by the player's B: a 'stop', no refusal", d.bulk.fail.why)
# again, to the end
touch(31)
ev('UNIT_SPELLCAST_START', 'player', 'g', 13262)
ev('UNIT_SPELLCAST_SUCCEEDED', 'player', 'g', 13262)
d = doc()
check(d.bulk.done is not None and d.bulk.done.n == 1 and d.bulk.done.s == 2 and d.bulk.cast is None and d.mode == 'bulk',
      "the disenchant succeeds: done (the tile sparkles away), the grid STAYS open", d.bulk.done and (d.bulk.done.n, d.bulk.done.s))
L.execute('BAG[0][2] = nil')                                       # the sword is gone
d = doc()
check(rows(d)[0] == (0, 3) and d.bulk.sel == 1 and d.cur.slot == 3, "the item gone: the cursor stays at its place, the next one slid in",
      (rows(d)[:2], d.bulk.sel))

# ---------------------------------------------------------------- blue / purple ask once (the user's pick 2a)
seq = doc().use.seq
touch(31)
d = doc()
check(d.note == 'de-ask' and d.bulk.ask is not None and d.bulk.ask.s == 3 and d.bulk.ask.q == 3 and d.use.seq == seq,
      "A on the blue vest: the ask ('Disenchant?'), nothing tapped yet", (d.note, d.bulk.ask and d.bulk.ask.s))
touch(33)
d = doc()
check(d.note == 'de-ask-no' and d.bulk.ask is None and d.mode == 'bulk', "B: No - back on the grid, nothing done", d.note)
touch(31); touch(31)
d = doc()
c = d.use.cell
check(d.note == 'de-go-asked' and at(c, 'target-slot') == 3 and at(c, 'type') == 'spell', "A, then A Yes: the vest's own cast cell", (d.note, c))
check(c != sword_cell or at(c, 'target-slot') == 3, "one cell per item")
seq = doc().use.seq
touch(31); touch(31)
check(doc().note == 'de-busy' and doc().use.seq == seq, "A again before its cast has started (the tap on its way): refused ('busy')", doc().note)
ev('UNIT_SPELLCAST_FAILED', 'player', 'g', 13262)                 # (the vest's cast did not start: moving)
check(doc().bulk.fail.why == 'cast' and doc().bulk.fail.s == 3, "its cast failed: the vest's tile flashes red ('cast'), the item stays", doc().bulk.fail.why)

# ---------------------------------------------------------------- a pick already waiting (cast from the bar): target only
L.execute('TARGETING, SCT, TSID = true, true, 13262')
touch(35); touch(31)
d = doc()
check(d.note == 'de-ask' and d.bulk.pick is True and d.bulk.cx.sct is True, "the purple ring asks too; the pick's device check rides along (sct)",
      (d.note, d.bulk.pick))
touch(31)
c = doc().use.cell
check(at(c, 'type') is None and at(c, 'spell') is None and at(c, 'target-bag') == 0 and at(c, 'target-slot') == 6,
      "a pick waiting: the cell is TARGET ONLY (it answers the pick, never casts again)", (at(c, 'type'), at(c, 'target-slot')))
check(L.eval('WowCompanion._kit.cst[%d].sh' % c) == 't', "... shape t")
# the pick goes off: the grid's cells are NOT freed (its pick goes on and off with every disenchant)
held = dict(L.eval('WowCompanion._kit.byKey'))
L.execute('TARGETING, SCT, TSID = false, false, nil')
step()
check(dict(L.eval('WowCompanion._kit.byKey')) == held and doc().mode == 'bulk', "the pick ends while the grid is open: cells kept, the grid stays")
# the pick on again while the grid is open: never the old list ("target")
L.execute('TARGETING, SCT, TSID = true, true, 13262')
step()
check(doc().mode == 'bulk', "a Disenchant pick while the grid is open: still the grid (never the old list)")

# B with a pick waiting: /stopspelltarget first, the grid closes when that tap lands
touch(33)
d = doc()
check(d.use.stop is True and d.mode == 'bulk' and d.note == 'de-stopping', "B with a pick waiting: /stopspelltarget asked, the grid waits", d.note)
SECURE = L.eval('SECURE')
SECURE.binv.onClick(19)                                            # the app's tap on binv 19 landed
d = doc()
check(d.mode == 'grid' and d.on is True and d.cur.bag == 0 and d.cur.slot == 4,
      "binv 19 landed: closed, back on the Bags grid it came from, its cursor where it was (the jerky)", (d.mode, d.cur.bag, d.cur.slot))
check(all(L.eval('WowCompanion._kit.cst[%d]' % c2) is None or L.eval('WowCompanion._kit.cst[%d].sh' % c2) != 'c' for c2 in range(20, 39)),
      "closed: no cell that would cast is left")
L.execute('TARGETING, SCT, TSID = false, false, nil'); L.execute('NOW = NOW + 5'); step()

# ---------------------------------------------------------------- a cast from the bar: the game opens its bags -> the take -> the grid; B -> Home
L.execute('BAG[0][2] = 5001')
touch(30)                                                          # the room left (touch out)
check(doc().on is False, "the room left")
L.execute('TARGETING, SCT, TSID = true, true, 13262; TAKES = 0')
touch(29)                                                          # the take (as the game's bags opening for the pick)
d = doc()
check(d.on is True and d.mode == 'bulk' and d.bulk.back == 'home' and rows(d)[0] == (0, 2),
      "taken while a Disenchant pick waits (a cast from the bar): straight to the grid, B goes Home", (d.mode, d.bulk.back))
lp = L.eval('SPEC.lootPass')
check(lp is not None and lp() is True, "the take asks Interact to keep the pad through an auto loot (spec.lootPass) while the grid holds")
L.execute('TARGETING, SCT, TSID = false, false, nil')
step()
touch(33)                                                          # B: nothing to stop -> Home
d = doc()
check(d.on is False and d.note == 'left-B' and L.eval('RELEASED') == 'B', "B with nothing to stop: closed -> Home (the player's B)", (d.on, d.note))

# ---------------------------------------------------------------- refusals: in a fight, loot open, a locked item
touch(29); touch(27); L.eval('WowCompanion._bagsShutTap')(9)
check(doc().mode == 'bulk', "opened again from the strip")
L.execute('LOCKED["0:2"] = true')
d = doc()
check(d.bulk.list[1].lk is True, "a locked item: greyed (lk)")
useSeq0 = d.use and d.use.seq
touch(31)
d = doc()
check(d.note == 'de-locked' and (d.use and d.use.seq) == useSeq0 and d.bulk.fail.why == 'locked',
      "A on a locked item: refused ('locked')", d.note)
L.execute('LOCKED["0:2"] = nil')
useSeq = d.use and d.use.seq
ev('LOOT_OPENED', False)
touch(31)
d = doc()
check(d.note == 'de-loot' and d.bulk.loot is True and (d.use and d.use.seq) == useSeq, "A while a loot window is open: refused ('loot')", d.note)
ev('LOOT_CLOSED')
L.execute('COMBAT = true')
touch(31)
d = doc()
check(d.note == 'de-combat' and d.bulk.fight is True and (d.use and d.use.seq) == useSeq, "A in a fight: refused ('combat'), tiles dimmed (fight)", d.note)
L.execute('COMBAT = false')

# ---------------------------------------------------------------- the game's refusal: hidden for the session, offered again after a skill-up
touch(31)
c = doc().use.cell
L.execute('NOW = NOW + 0.3')
L.execute('ERR_CANT_BE_DISENCHANTED = "Item cannot be disenchanted"')
ev('UI_ERROR_MESSAGE', 51, 'Item cannot be disenchanted')
d = doc()
check(d.bulk.fail.why == 'refused' and (0, 2) not in rows(d), "the game refuses within the second: the item is hidden ('refused')", rows(d))
L.execute('RANK = 125')
check((0, 2) in rows(doc()), "a skill-up: it is offered again")
L.execute('RANK = 120')
check((0, 2) not in rows(doc()), "(the same rank: still hidden)")
# an error later than the second, or another message, refuses nothing
touch(31)
L.execute('NOW = NOW + 2')
ev('UI_ERROR_MESSAGE', 51, 'Item cannot be disenchanted')
check(rows(doc())[0] == (0, 3), "an error two seconds after the tap is not ours", rows(doc())[:1])
touch(33); touch(33)
L.execute('RANK = 130')

# ---------------------------------------------------------------- the strip of what came, the loot pop-up and the take back
touch(29); touch(27); L.eval('WowCompanion._bagsShutTap')(9)
touch(35)                                                          # the cursor on the 2nd item (the blue vest: asked, then Yes)
key_before = doc().bulk.sel
touch(31); touch(31)
ev('UNIT_SPELLCAST_START', 'player', 'g', 13262)
ev('UNIT_SPELLCAST_SUCCEEDED', 'player', 'g', 13262)
L.execute('''GetNumLootItems = function() return 2 end
GetLootSlotInfo = function(i) if i == 1 then return 132871, "Strange Dust", 3, nil, 1 else return 132874, "Lesser Magic Essence", 1, nil, 2 end end''')
ev('LOOT_OPENED', True)
d = doc()
check(d.bulk.got is not None and d.bulk.got.auto is True and [(d.bulk.got['items'][i + 1].i, d.bulk.got['items'][i + 1].c) for i in range(2)]
      == [(132871, 3), (132874, 1)], "after OUR disenchant the loot's icons and counts ride along (the strip of what came)", d.bulk.got and d.bulk.got.n)
L.execute('HOLDER = "npc"')
L.eval('SPEC.lost')('npc')                                         # the loot pop-up took the pad
d = doc()
check(d.on is False and d.note == 'left-lootwait' and d.resume is True,
      "the loot pop-up takes the pad: 'left-lootwait' (the app keeps the room), the take back waits", (d.on, d.note))
L.execute('HOLDER = nil; TAKES = 0')
ev('LOOT_CLOSED')
L.execute('RUN_TIMERS()')
d = doc()
check(d.on is True and d.mode == 'bulk' and d.note == 'de-back' and L.eval('TAKES') == 1 and d.bulk.back == 'grid',
      "LOOT_CLOSED: the grid takes the pad back itself (the ordinary take), still over the Bags grid", (d.on, d.mode, d.note))
check(d.bulk.sel == key_before or d.bulk.sel == key_before - 1, "... its cursor where it was", (d.bulk.sel, key_before))
# the room left under the pop-up: no take back
L.execute('HOLDER = "npc"'); ev('LOOT_OPENED', False)
L.eval('SPEC.lost')('npc')
check(doc().resume is True, "lost to a pop-up again")
touch(30)                                                          # the player left the room by touch meanwhile
L.execute('HOLDER = nil; TAKES = 0')
ev('LOOT_CLOSED'); L.execute('RUN_TIMERS()')
check(doc().on is False and L.eval('TAKES') == 0, "the room left meanwhile: no take back")
# a pop-up that is not about our loot (an NPC window): lost as before, no take back
touch(29); touch(27); L.eval('WowCompanion._bagsShutTap')(9)
L.execute('NOW = NOW + 10'); L.eval('SPEC.lost')('npc')
check(doc().resume is None and doc().note == 'lost-npc', "another window's pop-up (no loot): lost as before, nothing waits", doc().note)

# ---------------------------------------------------------------- the game's bags close after a pick; the close door still leaves
touch(29); touch(27); L.eval('WowCompanion._bagsShutTap')(9)
check(doc().mode == 'bulk', "(the grid again)")
check('if ctl.mode == "bulk" then syncPass(); return end' in SRC, "onBagsHidden: the game's own close after a pick never closes the grid")
i_door = SRC.index('if doors.closingAt and GetTime() - doors.closingAt < 1 then doors.closingAt = nil; leave("room"); return end')
i_bulk = SRC.index('if ctl.mode == "bulk" then syncPass(); return end')
check(i_door < i_bulk, "... after the app's close door (the room left by touch still leaves)")

# ---------------------------------------------------------------- the game's own question (END_BOUND_TRADEABLE): A / B are the game's
L.execute('''POP = { IsShown = function() return true end, which = "END_BOUND_TRADEABLE" }; StaticPopup1 = POP''')
L.execute('PASSED = {}')
step()
check(L.eval('PASSED[#PASSED]') == 'PAD1 PAD2', "END_BOUND_TRADEABLE asks: A / B passed to the game", L.eval('PASSED[#PASSED]'))
seq = doc().use and doc().use.seq
touch(31)
check(doc().note == 'de-gameask-PAD1' and (doc().use and doc().use.seq) == seq, "an A that still reaches the grid acts on nothing", doc().note)
L.execute('StaticPopup1 = { IsShown = function() return false end }')
step()

# ---------------------------------------------------------------- the cursor by touch (36 + the tile's bmv cell)
touch(36); L.execute('WowCompanion._bagsCursorTap(0, 6)')
check(doc().bulk.sel == 3 and doc().cur.slot == 6, "a finger on a tile: the grid's cursor there (no game action)", doc().bulk.sel)

# ---------------------------------------------------------------- one cell per item, the least recently used goes
L.execute('''for s = 1, 30 do BAG[2] = BAG[2] or {}; BAG[2][s] = 7000 + s; ITEM[7000 + s] = { 4, "INVTYPE_FEET", 2 } end
SLOTS[2] = 30; WowCompanion._bagsLayout = { { bag = 0, slots = 8 }, { bag = 1, slots = 4 }, { bag = 2, slots = 30 } }''')
cells = []
for s in range(1, 23):
    L.execute('WowCompanion._de.fire({ b = 2, s = %d, id = %d }, false)' % (s, 7000 + s))
    L.execute('WowCompanion._de.cast = nil; WowCompanion._de.tap = nil')
    cells.append(doc().use.cell)
    L.execute('NOW = NOW + 0.01')
check(len(set(cells[:19])) == 19 and all(20 <= x <= 38 for x in cells), "19 items: 19 cells (binv 20..38; 39 is /stopcasting)", sorted(set(cells)))
check(cells[19] == cells[0] and cells[20] == cells[1], "the 20th item takes the least recently used cell (the 1st item's)", (cells[19], cells[0]))
check(at(cells[19], 'target-bag') == 2 and at(cells[19], 'target-slot') == 20, "... set for its own item", at(cells[19], 'target-slot'))
# the pull: the cells that could cast are cleared, binv 19 / 39 stay
L.execute('WowCompanion._kit.fightOff()')
check(all(at(x, 'type') is None and at(x, 'target-bag') is None for x in set(cells)) and at(39, 'macrotext') == '/stopcasting'
      and at(19, 'macrotext') == '/stopspelltarget', "PLAYER_REGEN_DISABLED: every cast cell cleared; binv 19 / 39 live")

# ---------------------------------------------------------------- the green arrow (the user's pick 6a)
L.execute('''BAG[2] = nil; SLOTS[2] = 0; WowCompanion._bagsLayout = { { bag = 0, slots = 8 }, { bag = 1, slots = 4 } }
TIPS = {}
WowCompanion.tipOf = function(key) return TIPS[key] end
local function l(t, c) return { t = t, c = c or "ffffff" } end
TIPS["bag 0 2 5001"] = { lines = { l("Item 5001", "1eff00"), l("One-Hand") }, cmp = { { slot = 16, dn = 2, lines = { l(" "), l("If you replace"), l("+3 Strength", "00ff00"), l("+1.2 Damage Per Second", "00ff00") } } } }
TIPS["bag 0 3 5002"] = { lines = { l("Item 5002", "0070dd"), l("Chest") }, cmp = { { slot = 5, dn = 2, lines = { l(" "), l("If you replace"), l("+4 Stamina", "00ff00"), l("-2 Spirit", "ff2020") } } } }
TIPS["bag 0 6 5004"] = { lines = { l("Item 5004", "a335ee"), l("Finger") }, cmp = {} }
TIPS["bag 1 1 5005"] = { lines = { l("Item 5005", "1eff00"), l("Hands"), l("Requires Level 40", "ff2020") }, cmp = {} }
''')
touch(29); touch(27); L.eval('WowCompanion._bagsShutTap')(9)
for _ in range(6):
    L.eval('WowCompanion._bagsTip')()
    L.execute('RUN_TIMERS()')
    touch(35)
d = doc()
ups = {(d.bulk.list[i + 1].b, d.bulk.list[i + 1].s): d.bulk.list[i + 1].up for i in range(len(d.bulk.list))}
check(ups.get((0, 2)) is True, "the sword: every stat change a gain -> the arrow", ups)
check(ups.get((0, 3)) is None, "the vest: a loss among the changes -> no arrow", ups.get((0, 3)))
check(ups.get((0, 6)) is True, "the ring: a finger slot empty -> the arrow (anything beats nothing)", ups.get((0, 6)))
check(ups.get((1, 1)) is None, "the gloves: a red line (cannot be worn) -> never the arrow, even into an empty slot", ups.get((1, 1)))
check(d.tip is not None, "the cursor's full tooltip rides with the grid's document (TIP)")

# ---------------------------------------------------------------- B in a fight: Home
L.execute('COMBAT = true')
touch(33)
check(doc().note in ('left-B',) and L.eval('RELEASED') == 'B', "B in a fight: Home (even over the Bags grid)", doc().note)
L.execute('COMBAT = false')

# ---------------------------------------------------------------- no trace
de_code = SRC[SRC.index('-- ---------------------------------------------------------------- bags-de1: THE DISENCHANT GRID'):
              SRC.index('-- ---------------------------------------------------------------- THE ROOM\'S TOP STRIP')]
code = '\n'.join(re.sub(r'--.*$', '', ln) for ln in de_code.splitlines())
check(not any(w in code for w in ('SetBinding', 'CreateMacro', 'SetCVar', 'SpellStopTargeting(', 'SpellStopCasting(', 'CloseLoot(',
                                  'LootSlot(', 'CastSpell', 'UseContainerItem', 'PickupContainerItem')),
      "no trace: no binding, macro, CVar, no protected stop / loot / cast / use call from the grid's own code")
ALL = '\n'.join(re.sub(r'--.*$', '', ln) for ln in SRC.splitlines())
check('SpellStopTargeting(' not in ALL and 'SpellStopCasting(' not in ALL, "Bags.lua never calls SpellStopTargeting / SpellStopCasting")

print('ALL PASS (%d checks)' % passes if fails == 0 else '%d FAILED (of %d)' % (fails, fails + passes))
sys.exit(1 if fails else 0)
