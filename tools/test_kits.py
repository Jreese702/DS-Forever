"""ARMOR KITS, STONES, ENCHANTS: "CHOOSE AN ITEM" (the user, 2026-11-07: "there is no way to apply my light armor kits ... enchants, and
sharpening stones ... from both the bags room as well as the charactor room"). Loads the real WowCompanion/Bags.lua in Lua 5.1 with
stand-in game functions (anything not given is a harmless stub) and checks the pieces the app relies on:
  - the secure row "binv": 19 cells, type item, item = the slot (the game's /use <slot>: a pick's target)
  - binv cols 20..39: the pick's BAG GEAR targets, type macro "/use <bag> <slot>" (issue #126: the bag cell's "item" action equipped)
  - Disenchant (SpellIsTargeting + IsCurrentSpell 13262): only the bags' weapons / armour of quality >= 2 (issues #126 / #39)
  - binv col 19 stops the pick with the game's own /stopspelltarget (SpellStopTargeting is FORBIDDEN to add-ons: the user's error)
  - the pick watcher: an "itarget" document when the game starts / ends "pick an item", the used item named and learned
  - the list: worn gear first (chest, legs, hands, feet first), then the bags' gear; while ENCHANTING the game's own check greys
Run: python -B tools/test_kits.py
"""
import os
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
-- anything the file reaches for and the test does not give: a stub that is callable, indexable and returns itself
local stub
stub = setmetatable({}, { __index = function() return stub end, __call = function() return stub end })
setmetatable(_G, { __index = function(_, k) return stub end })
NOW = 100
function GetTime() return NOW end
TICKERS = {}
C_Timer = { NewTicker = function(s, fn) TICKERS[#TICKERS + 1] = { s = s, fn = fn }; return stub end, After = function(_, fn) end,
            NewTimer = function() return stub end }
EMITS = {}
SECURE, OPS = {}, {}
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
}
-- the game: worn chest (5) and hands (10); bag 0 slot 2 = a belt (gear), slot 1 = the kit
WORN = { [5] = 2580, [10] = 2584 }
BAG = { [0] = { [1] = 2304, [2] = 4239, [3] = 117, [4] = 118 } }   -- 3 = jerky (food), 4 = a potion
EQUIP = { [2580] = "INVTYPE_CHEST", [2584] = "INVTYPE_HAND", [4239] = "INVTYPE_WAIST", [2304] = "" }
NAMES = { [2580] = "Rugged Leather Vest", [2584] = "Handstitched Gloves", [4239] = "Embossed Leather Belt", [2304] = "Light Armor Kit",
          [117] = "Tough Jerky", [118] = "Minor Healing Potion" }
CLS = { [117] = { 0, 5 }, [118] = { 0, 1 }, [2304] = { 0, 6 } }        -- consumables: food & drink, potion, item enhancement
PICKING, ENCHANTING, STOPPED = false, false, 0
FITS = {}
function GetInventoryItemID(_, slot) return WORN[slot] end
function GetInventoryItemTexture() return 1 end
function GetInventoryItemQuality() return 1 end
function SpellCanTargetItem() return PICKING end
function SpellCanTargetItemID() return false end
function SpellStopTargeting() STOPPED = STOPPED + 1; PICKING = false end
function InCombatLockdown() return false end
C_Item = { GetItemNameByID = function(id) return NAMES[id] end, GetItemIconByID = function() return 1 end,
           GetItemInfoInstant = function(id) local c = CLS[id] or { 4, 2 }; return id, "Armor", "Leather", EQUIP[id] or "", 1, c[1], c[2] end,
           DoesItemMatchTargetEnchantingSpell = function(loc) return FITS[loc.k] == true end }
C_Spell = { TargetSpellIsEnchanting = function() return ENCHANTING end }
ItemLocation = { CreateFromEquipmentSlot = function(_, s) return { k = "inv" .. s } end,
                 CreateFromBagAndSlot = function(_, b, s) return { k = "bag" .. b .. ":" .. s } end }
C_Container = { GetContainerItemID = function(b, s) return BAG[b] and BAG[b][s] end, GetContainerNumSlots = function(b) return b == 0 and 4 or 0 end,
                GetContainerItemInfo = function(b, s) if BAG[b] and BAG[b][s] then return { iconFileID = 1, quality = 1 } end end }
WowCompanion._bagsLayout = { { bag = 0, slots = 4 } }
''')
load = L.eval('function(src) local f, e = loadstring(src, "@Bags.lua"); if not f then error(e) end; return f end')
ok_load = True
try:
    load(SRC)()
except Exception as e:  # noqa: BLE001
    ok_load = False; print(e)
check(ok_load, "Bags.lua loads (stand-in game) with no error")
kit = L.eval('WowCompanion._kit')
check(kit is not None, "WC._kit is published (the room's document reads it)")

# the secure row and the op
b = L.eval('SECURE.binv')
check(b is not None and b.n == 40, "the secure row binv: 19 slots + the stop cell + 20 bag-gear target cells (issue #126)", b and b.n)
# the pad builds every cell (TapPad's init), so the bag-gear cells exist as on the device
L.execute('''CELLS = {}
for col = 20, 39 do
    local c = { a = {} }; function c:SetAttribute(k, v) self.a[k] = v end
    CELLS[col] = c; SECURE.binv.init(c, col)
end''')
check(L.eval('CELLS[20].a.type') == 'macro' and L.eval('CELLS[20].a.macrotext') == '',
      "binv col 20+ = a secure macro cell, empty until a pick lists bag gear", (L.eval('CELLS[20].a.type'), L.eval('CELLS[20].a.macrotext')))
L.execute('BTN = { a = {} }; function BTN:SetAttribute(k, v) self.a[k] = v end; SECURE.binv.init(BTN, 4)')
check(L.eval('BTN.a.type') == 'item' and L.eval('BTN.a.item') == '5', "binv col 4 = type item, item \"5\" (the chest: the game's /use 5)",
      (L.eval('BTN.a.type'), L.eval('BTN.a.item')))
L.execute('BTN2 = { a = {} }; function BTN2:SetAttribute(k, v) self.a[k] = v end; SECURE.binv.init(BTN2, 19)')
check(L.eval('BTN2.a.type') == 'macro' and L.eval('BTN2.a.macrotext') == '/stopspelltarget',
      "binv col 19 = the game's /stopspelltarget (a secure macro attribute)", (L.eval('BTN2.a.type'), L.eval('BTN2.a.macrotext')))
import re
CODE = chr(10).join(re.sub(r'--.*$', '', ln) for ln in SRC.splitlines())
check('SpellStopTargeting(' not in CODE, "Bags.lua never calls SpellStopTargeting (forbidden to add-ons: 'WowCompanion forbidden')")

# the pick watcher
step = L.eval('WowCompanion._kit.step')
L.execute('WowCompanion._kit.used(0, 1)')
L.execute('PICKING = true')
n0 = int(L.eval('#EMITS'))
step()
e = L.eval('EMITS[#EMITS]')
check(int(L.eval('#EMITS')) == n0 + 1 and e.type == 'itarget' and e.on is True and e.kit.name == 'Light Armor Kit',
      "the game starts a pick: an itarget document, on, naming the kit just used", e and dict(e))
check(L.eval('WowCompanion._kitLearned[2304]') is True, "the kit is learned (the Character room's list)")
step()
check(int(L.eval('#EMITS')) == n0 + 1, "no change: nothing sent again")

# the list
lst = L.eval('WowCompanion._kit.list()')
rows = [lst[i + 1] for i in range(len(lst))]
check([(r.src, r.slot if r.src == 'inv' else r.s) for r in rows] == [('inv', 5), ('inv', 10), ('bag', 2)],
      "the list: chest, hands (worn, kits' order), then the belt in the bags (the kit itself is no gear)",
      [(r.src, r.slot if r.src == 'inv' else r.s) for r in rows])
check(all(r.ok is True for r in rows) and rows[0].name == 'Rugged Leather Vest', "an armor kit (not enchanting): every row offered",
      [r.ok for r in rows])
L.execute('ENCHANTING = true; FITS = { inv10 = true }')
lst = L.eval('WowCompanion._kit.list()')
rows = [lst[i + 1] for i in range(len(lst))]
check([r.ok for r in rows] == [None, True, None], "enchanting: only what the game's own check allows (the gloves)", [r.ok for r in rows])
L.execute('ENCHANTING = false')

# (issue 90, the player: "feed pet brings up item select, but doesnt show consumables"): a pick a SPELL started (Feed Pet: no bag item
# used just now) lists the bags' consumables first, food and drink before the rest, then the gear as before
L.execute('NOW = NOW + 30')
lst = L.eval('WowCompanion._kit.list()')
rows = [lst[i + 1] for i in range(len(lst))]
got = [(r.src, r.slot if r.src == 'inv' else r.s) for r in rows]
check(got[:3] == [('bag', 3), ('bag', 1), ('bag', 4)] and rows[0].name == 'Tough Jerky' and rows[0].ok is True,
      "Feed Pet (a spell's pick): the food first, then the other consumables in bag order", got)
check(got[3:] == [('inv', 5), ('inv', 10), ('bag', 2)], "... then the gear as for a kit", got)
L.execute('ENCHANTING = true')
lst = L.eval('WowCompanion._kit.list()')
check(all(lst[i + 1].src == 'inv' or lst[i + 1].s == 2 for i in range(len(lst))), "an enchant's pick: gear only (no consumables)")
L.execute('ENCHANTING = false; NOW = 100')

# DISENCHANT (issues #126 / #39, the player 2026-11-11: "disenchantment of items not possible. Game tries to equip instead. Items
# specifically for disenchanting don't get listed at all"): SpellIsTargeting while Disenchant (13262) is the current spell
L.execute(r'''
TARGETING, CURRENT, COMBAT = false, nil, false
function SpellIsTargeting() return TARGETING end
function IsCurrentSpell(s) return s == CURRENT end
function GetSpellInfo(id) if id == 13262 then return "Disenchant", nil, 132853 end end
function InCombatLockdown() return COMBAT end
QUAL = { [5001] = 2, [5002] = 3, [4239] = 1 }
EQUIP[5001] = "INVTYPE_WEAPON"; EQUIP[5002] = "INVTYPE_CHEST"
NAMES[5001] = "Green Sword"; NAMES[5002] = "Blue Vest"
CLS[5001] = { 2, 7 }; CLS[5002] = { 4, 2 }
BAG[0][5] = 5001; BAG[0][6] = 5002
C_Container.GetContainerNumSlots = function(b) return b == 0 and 6 or 0 end
C_Container.GetContainerItemInfo = function(b, s) local id = BAG[b] and BAG[b][s]; if id then return { iconFileID = 1, quality = QUAL[id] or 1 } end end
WowCompanion._bagsLayout = { { bag = 0, slots = 6 } }
PICKING = false
''')
check(L.eval('WowCompanion._kit.on()') is False, "no pick: Disenchant not targeting")
L.execute('TARGETING = true; CURRENT = 13262')
check(L.eval('WowCompanion._kit.on()') is True and L.eval('WowCompanion._kit.de()') is True,
      "Disenchant targeting is a pick even when SpellCanTargetItem says no")
lst = L.eval('WowCompanion._kit.list()')
rows = [lst[i + 1] for i in range(len(lst))]
check([(r.src, r.s) for r in rows] == [('bag', 5), ('bag', 6)],
      "Disenchant lists ONLY the bags' weapons / armour of quality >= 2 (no worn gear, no consumables, no white belt)",
      [(r.src, r.slot if r.src == 'inv' else r.s) for r in rows])
cells = [r.cell for r in rows]
check(all(r.ok is True for r in rows) and all(c is not None and 20 <= c <= 39 for c in cells) and len(set(cells)) == 2,
      "each row offered, on its own target cell (binv 20..39)", [(r.ok, r.cell) for r in rows])
mt = lambda c: L.eval('CELLS[%d].a.macrotext' % c)
check(all(mt(r.cell) == '/use 0 %d' % r.s for r in rows),
      "the cells are the game's /use <bag> <slot> (the item-target click), never the bag cell's equip", [mt(c) for c in cells])
check(all(L.eval('CELLS[%d].a.type' % c) == 'macro' and L.eval('CELLS[%d].a.item' % c) is None for c in cells),
      "a target cell has no secure item attribute (no equip path)")
vest_cell = rows[1].cell
lst = L.eval('WowCompanion._kit.list()')
check([lst[i + 1].cell for i in range(len(lst))] == cells, "the list built again: the same cells (no rewrite)")
n0 = int(L.eval('#EMITS'))
L.execute('WowCompanion._kit.was = false')
step()
e = L.eval('EMITS[#EMITS]')
check(int(L.eval('#EMITS')) == n0 + 1 and e.type == 'itarget' and e.on is True and e.kit.name == 'Disenchant',
      "the pick starts: itarget on, named Disenchant", e and dict(e))
# (review 2026-11-11) the bags change mid-pick: every listed item KEEPS its cell (a stale list's tap still hits the item it showed)
L.execute('COMBAT = true; BAG[0][5] = nil')                     # the sword is gone: the vest is now the first row
lst = L.eval('WowCompanion._kit.list()')
rows = [lst[i + 1] for i in range(len(lst))]
check([(r.s, r.cell, r.ok) for r in rows] == [(6, vest_cell, True)] and mt(vest_cell) == '/use 0 6',
      "an item earlier in the bags leaves: the vest keeps its own cell, still offered (in a fight too)", [(r.s, r.cell, r.ok) for r in rows])
# a fight: a NEW item would need a cell set, which the fight forbids; that row is greyed, never answered by a plain use
L.execute('''QUAL[5003] = 2; EQUIP[5003] = "INVTYPE_WEAPON"; NAMES[5003] = "Green Axe"; CLS[5003] = { 2, 0 }; BAG[0][7] = 5003
C_Container.GetContainerNumSlots = function(b) return b == 0 and 7 or 0 end; WowCompanion._bagsLayout = { { bag = 0, slots = 7 } }''')
lst = L.eval('WowCompanion._kit.list()')
rows = [lst[i + 1] for i in range(len(lst))]
check([(r.s, r.cell, r.ok) for r in rows] == [(6, vest_cell, True), (7, None, None)],
      "in a fight a new item gets no cell: greyed; the vest's cell untouched", [(r.s, r.cell, r.ok) for r in rows])
L.execute('COMBAT = false')
lst = L.eval('WowCompanion._kit.list()')
rows = [lst[i + 1] for i in range(len(lst))]
check(rows[1].ok is True and rows[1].cell not in (None, vest_cell) and mt(rows[1].cell) == '/use 0 7' and rows[0].cell == vest_cell,
      "out of the fight the new item gets a FREE cell; the vest still its own", [(r.s, r.cell) for r in rows])
L.execute('TARGETING, CURRENT = false, nil')
step()
check(L.eval('next(WowCompanion._kit.byKey)') is None, "the pick ended: its cells are free again")
L.execute('''TARGETING, CURRENT = true, 13262; BAG[0][7] = nil
C_Container.GetContainerNumSlots = function(b) return b == 0 and 6 or 0 end; WowCompanion._bagsLayout = { { bag = 0, slots = 6 } }''')
step()
# an armor kit's pick on bag gear goes through a target cell too (the same equip trap)
L.execute('TARGETING, CURRENT = false, nil; PICKING = true')
lst = L.eval('WowCompanion._kit.list()')
bagrows = [lst[i + 1] for i in range(len(lst)) if lst[i + 1].src == 'bag']
check(bagrows and all(r.cell is not None and r.cell >= 20 for r in bagrows), "a kit's bag gear rows have target cells too",
      [(r.s, r.cell) for r in bagrows])
# THE ROOM (review 2026-11-11, issue #126): Disenchant cast from the bar, THEN the Bags room taken: straight to the list, never the grid
# (whose X / Use tap the bag's own cell, and that EQUIPS gear)
L.execute(r'''
for _, n in ipairs({ "MerchantFrame", "GossipFrame", "QuestFrame", "BankFrame", "MailFrame", "TradeFrame", "AuctionHouseFrame",
                     "GuildBankFrame", "ClassTrainerFrame", "CharacterFrame" }) do _G[n] = { IsShown = function() return false end } end
WowCompanion.capture = { version = 11, take = function() return true end, pass = function() return true end,
                         release = function() return true end, state = function() return { holder = "bags" } end }
TARGETING, CURRENT, PICKING = true, 13262, false
''')
step()                                                             # the pick started before the room (KIT.was = true)
L.eval('WowCompanion._bagsTouch')(29)                             # the room taken (TOUCH_IN)
doc = L.eval('WowCompanion._bagsCtlDoc()')
check(doc.on is True and doc.mode == 'target' and doc.target is not None, "taken mid-pick: the room opens on the pick's list", doc and doc.mode)
L.eval('WowCompanion._bagsTouch')(33)                             # B: the game's /stopspelltarget, the list closes
doc = L.eval('WowCompanion._bagsCtlDoc()')
check(doc.mode == 'grid' and doc.use.stop is True, "B: the stop asked, the grid", (doc.mode, doc.use and dict(doc.use)))
step()
check(L.eval('WowCompanion._bagsCtlDoc()').mode == 'grid', "in the moment after B (the pick ending) the room stays on the grid")
seq0 = L.eval('WowCompanion._bagsCtlDoc()').use.seq
L.eval('WowCompanion._bagsTouch')(22)                             # X on a bag item while the game still picks
doc = L.eval('WowCompanion._bagsCtlDoc()')
check(doc.mode == 'target' and doc.use.stop is True and doc.use.seq == seq0,
      "the grid's X while a pick is on: the list, NO bag-cell use", (doc.mode, dict(doc.use)))
L.eval('WowCompanion._bagsTouch')(33); L.execute('NOW = NOW + 2')
step()
check(L.eval('WowCompanion._bagsCtlDoc()').mode == 'target', "the pick still on after the stop's moment: back on the list (every step)")
L.execute('TARGETING, CURRENT = false, nil')
step()
check(L.eval('WowCompanion._bagsCtlDoc()').mode == 'grid', "the pick ended: the grid")
L.execute(r'''
BAG[0][5] = nil; BAG[0][6] = nil; WowCompanion._bagsLayout = { { bag = 0, slots = 4 } }
C_Container.GetContainerNumSlots = function(b) return b == 0 and 4 or 0 end
''')

# the end of the pick, and the stop
L.execute('PICKING = false')                                       # the game's /stopspelltarget ran (binv 19)
step()
e = L.eval('EMITS[#EMITS]')
check(e.type == 'itarget' and e.on is False, "the pick ended: itarget off", dict(e))
check(not any('SetBinding' in ln or 'CreateMacro' in ln or 'SetCVar' in ln for ln in SRC.splitlines() if 'KIT' in ln or 'kit' in ln),
      "no trace: no binding, macro or CVar in the kit code")

print('ALL PASS (%d checks)' % passes if fails == 0 else '%d FAILED (of %d)' % (fails, fails + passes))
sys.exit(1 if fails else 0)
