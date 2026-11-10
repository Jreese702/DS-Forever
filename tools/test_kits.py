"""ARMOR KITS, STONES, ENCHANTS: "CHOOSE AN ITEM" (the user, 2026-11-07: "there is no way to apply my light armor kits ... enchants, and
sharpening stones ... from both the bags room as well as the charactor room"). Loads the real WowCompanion/Bags.lua in Lua 5.1 with
stand-in game functions (anything not given is a harmless stub) and checks the pieces the app relies on:
  - the secure row "binv": 19 cells, type item, item = the slot (the game's /use <slot>: a pick's target)
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
check(b is not None and b.n == 20, "the secure row binv: 19 slots + the stop cell", b and b.n)
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

# the end of the pick, and the stop
L.execute('PICKING = false')                                       # the game's /stopspelltarget ran (binv 19)
step()
e = L.eval('EMITS[#EMITS]')
check(e.type == 'itarget' and e.on is False, "the pick ended: itarget off", dict(e))
check(not any('SetBinding' in ln or 'CreateMacro' in ln or 'SetCVar' in ln for ln in SRC.splitlines() if 'KIT' in ln or 'kit' in ln),
      "no trace: no binding, macro or CVar in the kit code")

print('ALL PASS (%d checks)' % passes if fails == 0 else '%d FAILED (of %d)' % (fails, fails + passes))
sys.exit(1 if fails else 0)
