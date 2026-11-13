"""uneq1 / Character c14 (the user's picks, 2026-11-13: "Unequip lives in the Character room: choose a worn slot, Y = More opens a small
menu with 'Unequip' first"; "WEAPONS (slots 16,17,18) MAY be unequipped in combat, armor is refused in combat"; "Target: first free
plain-bag slot, backpack first; bags full -> row disabled 'Bags full'; ammo (slot 0) not offered"; "Never let a stale bag pick equip
anything"). Loads the REAL WowCompanion/Bags.lua and WowCompanionCharacter/Character.lua in Lua 5.1 with stand-in game functions (a
cursor, bags, worn gear, timers that run) and checks:
  - the route: Character's arm cell (`eqset` key "uneq" on auracancel) then bmvq column slot - 1 = the piece goes into the first free
    plain bag slot (backpack first; a quiver / soul bag and the reagent bag skipped); the ack `unequip` comes back in Character's eqs part;
    confirmed after the bag update;
  - a STALE bag pick (the Bags room's finger-drag, 10 s) never equips anything: through the arm, its late tap, the doubled press, and a
    bmvq tap after an arm that came too late; EquipCursorItem never called;
  - refusals, nothing moved and nothing left on the cursor: empty, settle, cursor busy, bags full, ammo / a bad slot, armour in a fight
    (fightRefused), a weapon in a fight the game BLOCKS (ADDON_ACTION_BLOCKED on the pick-up, or on the put-down) = "combat";
  - a weapon (16 / 17 / 18) in a fight the game allows = moved;
  - Character: moreon / moreoff (B is the room's while the menu is open; B closes it: more false, not a leave), the cells in the eqs
    part, `uneq` kept by the sets' retain, a set's cells still distinct;
  - no trace: no SetBinding / SaveBindings / macro / CVar / Blizzard open-close / StaticPopup call.
Run: python -B tools/test_unequip.py
"""
import os
import sys
from lupa import lua51

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ADD = os.path.join(ROOT, 'companion', 'app', 'src', 'main', 'assets', 'companion', 'addons')
BAGS = open(os.path.join(ADD, 'WowCompanion', 'Bags.lua'), encoding='utf-8').read()
CHAR = open(os.path.join(ADD, 'WowCompanionCharacter', 'Character.lua'), encoding='utf-8').read()
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
TIMERS = {}
C_Timer = { NewTicker = function() return stub end, NewTimer = function() return stub end,
            After = function(s, fn) TIMERS[#TIMERS + 1] = { at = NOW + s, fn = fn } end }
function run(dt)
    local upto = NOW + (dt or 0)
    for _ = 1, 10000 do
        local bi, best = nil, nil
        for i, t in ipairs(TIMERS) do if t.at <= upto and (not best or t.at < best.at) then bi, best = i, t end end
        if not bi then break end
        table.remove(TIMERS, bi)
        if best.at > NOW then NOW = best.at end
        pcall(best.fn)
    end
    NOW = upto
end
HANDLERS = {}
function CreateFrame(kind, name)
    local fr = setmetatable({ attrs = {}, name = name, ev = {} }, { __index = function() return stub end })
    function fr:SetScript(k, fn) if k == "OnEvent" then HANDLERS[#HANDLERS + 1] = { fr = fr, fn = fn } end end
    function fr:RegisterEvent(e) self.ev[e] = true; return true end
    function fr:SetAttribute(k, v) self.attrs[k] = v end
    function fr:GetName() return self.name end
    return fr
end
function fire(ev, ...) for _, h in ipairs(HANDLERS) do if h.fr.ev[ev] then pcall(h.fn, h.fr, ev, ...) end end end
EMITS, SENT = {}, {}
local function enc(v)
    if type(v) ~= "table" then return tostring(v) end
    local keys = {}
    for k in pairs(v) do keys[#keys + 1] = tostring(k) end
    table.sort(keys)
    local parts = {}
    for _, k in ipairs(keys) do local x = v[k]; if x == nil then x = v[tonumber(k)] end; parts[#parts + 1] = k .. "=" .. enc(x) end
    return "{" .. table.concat(parts, ",") .. "}"
end
-- the pad: plain ops by name, and the namespaces' slotFor / retain as TapPad has them
OPS = {}
PAD = { ns = {} }
function PAD.op(name, ns, fn) OPS[name] = fn end
function PAD.secureRow() return 1 end
function PAD.describe() return { rows = {} } end
function PAD.onLayout() end
function PAD.slotFor(ns, key)
    if key == nil then return nil end
    local t = PAD.ns[ns]
    if not t then t = { byKey = {}, byCol = {} }; PAD.ns[ns] = t end
    if t.byKey[key] then return t.byKey[key] end
    for col = 0, 39 do if t.byCol[col] == nil then t.byKey[key] = col; t.byCol[col] = key; return col end end
end
function PAD.retain(ns, present)
    local t = PAD.ns[ns]; if not t then return end
    for key, col in pairs(t.byKey) do if not present[key] then t.byKey[key] = nil; t.byCol[col] = nil end end
end
WowCompanion = {
    S = function(fn) local ok, v = pcall(fn); if ok then return v end end,
    SN = function(fn) local ok, v = pcall(fn); if ok and type(v) == "number" then return v end end,
    CALL = function(fn) return pcall(fn) end,
    emit = function(t) EMITS[#EMITS + 1] = t end,
    send = function(s) SENT[#SENT + 1] = s end, encode = enc, push = function() end, isPlain = function() return true end,
    pad = PAD, VERSION = "test",
}
COMBAT = false
function InCombatLockdown() return COMBAT end
function UnitAffectingCombat() return COMBAT end
function UnitName() return "Shamy" end
function GetRealmName() return "Forever" end
TRACE = {}
for _, n in ipairs({ "SetBinding", "SaveBindings", "DeleteMacro", "CreateMacro", "EditMacro", "SetCVar", "ShowUIPanel", "HideUIPanel",
                     "ToggleCharacter", "CloseAllBags", "OpenAllBags", "ToggleBackpack", "StaticPopup_Show", "StaticPopup_Hide" }) do
    _G[n] = function() TRACE[#TRACE + 1] = n end
end
function GetNumMacros() return 0, 0 end
function SetOverrideBindingClick() end
function SetOverrideBinding() end
function GetBindingAction() return "" end
-- the game's bit library (the stand-in _G would answer a table for it, so every family would "fit")
bit = { band = function(a, b)
    local r, p = 0, 1
    while a > 0 and b > 0 do
        local x, y = a % 2, b % 2
        if x == 1 and y == 1 then r = r + p end
        a, b, p = (a - x) / 2, (b - y) / 2, p * 2
    end
    return r
end }
-- THE GAME: bags (0 backpack, 1 a quiver = family 1, 2 a plain bag), worn gear, ONE cursor
NSLOTS = { [0] = 4, [1] = 2, [2] = 2 }
FAM = { [0] = 0, [1] = 1, [2] = 0 }
BAG = { [0] = { [1] = 117, [2] = 118 }, [1] = {}, [2] = {} }
WORN = { [1] = 801, [5] = 900, [16] = 901, [17] = 902, [18] = 903 }
ST = { cursor = nil, block = nil }
CALLS = { pickInv = 0, equip = 0 }
-- ST.block: "pick": the game blocks PickupInventoryItem; "put": it blocks the put-down; in a fight only
local function blocked(what)
    if COMBAT and ST.block and ST.block.what == what then fire("ADDON_ACTION_BLOCKED", "WowCompanion", ST.block.fn); return true end
end
function PickupInventoryItem(slot)
    CALLS.pickInv = CALLS.pickInv + 1
    if blocked("pick") then return end
    if ST.cursor then return end
    if WORN[slot] then ST.cursor = { inv = slot, id = WORN[slot] } end
end
function EquipCursorItem(slot) CALLS.equip = CALLS.equip + 1; if ST.cursor then WORN[slot] = ST.cursor.id; if ST.cursor.bag then BAG[ST.cursor.bag][ST.cursor.slot] = nil end; ST.cursor = nil end end
function CursorHasItem() return ST.cursor ~= nil end
function ClearCursor() ST.cursor = nil end
function DeleteCursorItem() ST.cursor = nil end
C_Container = {
    GetContainerItemID = function(b, s) return BAG[b] and BAG[b][s] end,
    GetContainerNumSlots = function(b) return NSLOTS[b] or 0 end,
    GetContainerNumFreeSlots = function(b)
        local n = 0; for s = 1, NSLOTS[b] or 0 do if not BAG[b][s] then n = n + 1 end end; return n, FAM[b] or 0 end,
    GetContainerItemInfo = function(b, s) local id = BAG[b] and BAG[b][s]; if id then return { itemID = id, iconFileID = 1, quality = 1, stackCount = 1 } end end,
    GetContainerItemLink = function(b, s) local id = BAG[b] and BAG[b][s]; if id then return "|Hitem:" .. id .. "|h" end end,
    PickupContainerItem = function(b, s)
        if blocked("put") then return end
        if ST.cursor then
            if BAG[b][s] then return end                -- a swap is not needed here
            BAG[b][s] = ST.cursor.id
            if ST.cursor.inv then WORN[ST.cursor.inv] = nil end
            if ST.cursor.bag then BAG[ST.cursor.bag][ST.cursor.slot] = nil end
            ST.cursor = nil
        elseif BAG[b][s] then ST.cursor = { bag = b, slot = s, id = BAG[b][s] } end
    end,
}
C_Item = { GetItemNameByID = function(id) return "Item " .. id end, GetItemInfoInstant = function(id) return id, "Armor", "Mail", "", 1, 4, 0 end,
           GetItemFamily = function() return 0 end, GetItemSpell = function() return nil end, RequestLoadItemDataByID = function() end }
function GetInventoryItemID(unit, slot) return WORN[slot] end
function GetInventoryItemLink(unit, slot) if WORN[slot] then return "|Hitem:" .. WORN[slot] .. "|h" end end
C_EquipmentSet = { GetEquipmentSetIDs = function() return { 7 } end,
                   GetEquipmentSetInfo = function(id) return "Tank", 1, id, false, 1, 1, 0, 0 end }
for _, n in ipairs({ "MerchantFrame", "GossipFrame", "QuestFrame", "BankFrame", "MailFrame", "TradeFrame", "AuctionHouseFrame",
                     "GuildBankFrame", "ClassTrainerFrame", "CharacterFrame" }) do _G[n] = { IsShown = function() return false end } end
-- interact's capture: Character holds it by touch; pass is recorded
REFUSED, PASSES, SPEC = 0, {}, nil
WowCompanion.capture = { version = 11, take = function(id, spec) if id == "character" then SPEC = spec end; return true end,
    pass = function(id, keys) PASSES[#PASSES + 1] = #keys; return true end, release = function() return true end,
    state = function() return { holder = "character" } end, refused = function() REFUSED = REFUSED + 1; return true end }
-- helpers
function lastMove() for i = #EMITS, 1, -1 do local e = EMITS[i]; if e.bagsMove then return e.bagsMove end end end
function movesSince(n) local out = {}; for i = n + 1, #EMITS do if EMITS[i].bagsMove then out[#out + 1] = EMITS[i].bagsMove end end; return out end
function nEmits() return #EMITS end
function eqs() return WowCompanion.character.collect.eqs() end
function cell(key) local t = PAD.ns.eqset; return t and t.byKey[key] end
function tapSet(key) WowCompanion.character.onSetCell(WowCompanion.character.eqFirst + PAD.slotFor("eqset", key)) end
function bmvq(col) OPS.bmvq(nil, col) end
function unequipVia(slot) tapSet("uneq"); NOW = NOW + 0.12; bmvq(slot - 1) end
''')
load = L.eval('function(src, name, addon) local f, e = loadstring(src, "@" .. name); if not f then error(e) end; return f(addon) end')
ok_load = True
try:
    load(BAGS, 'Bags.lua', 'WowCompanion')
except Exception as e:  # noqa: BLE001
    ok_load = False; print(e)
check(ok_load, "Bags.lua loads (stand-in game) with no error")
ok_load = True
try:
    load(CHAR, 'Character.lua', 'WowCompanionCharacter')
except Exception as e:  # noqa: BLE001
    ok_load = False; print(e)
check(ok_load, "Character.lua loads with no error")
L.execute('WowCompanionDB = {}; fire("PLAYER_LOGIN"); WowCompanion._bagsMoveReady = true; '
          'WowCompanion.bagsMoveRows({ { bag = 0, slots = 4 }, { bag = 1, slots = 2 }, { bag = 2, slots = 2 } })')
check(L.eval('type(OPS.bmvq)') == 'function' and L.eval('type(OPS.bmv0)') == 'function', "the drag rows are there")
check(L.eval('type(WowCompanion.bagsUnequip)') == 'function', "WC.bagsUnequip exists")
check(str(L.eval('WowCompanion.character.version')).startswith('c14'), "Character's version is bumped (c14)", L.eval('WowCompanion.character.version'))

# the eqs part carries the route's cells; the sets' retain keeps them
d = L.eval('eqs()')
check(d.uq is not None and d.mon is not None and d.moff is not None and d.row == 'auracancel', "eqs: the uneq / moreon / moreoff cells on auracancel",
      (d.uq, d.mon, d.moff, d.row))
uq = d.uq
L.execute('WowCompanion.character.collect.sets()')
check(L.eval('WowCompanion.character.eqFirst + cell("uneq")') == uq and L.eval('WowCompanion.character.eqFirst + cell("moreon")') == d.mon, "the sets' retain keeps the route's cells")
cells = [L.eval('cell("uneq")'), L.eval('cell("moreon")'), L.eval('cell("moreoff")'), L.eval('cell("equip:7")'), L.eval('cell("save:7")'),
         L.eval('cell("yes")'), L.eval('cell("no")')]
check(None not in cells and len(set(cells)) == len(cells), "every eqset key has its own cell", cells)
check(L.eval('WowCompanion.character.eqFirst + cell("uneq")') < 40, "the arm cell is inside the 40-cell row")

# 1. out of a fight: the chest goes into the backpack's first free slot (3); the ack comes back to Character
n0 = L.eval('nEmits()')
L.execute('NOW = 200; unequipVia(5)')
check(L.eval('WORN[5]') is None and L.eval('BAG[0][3]') == 900, "chest unequipped into the backpack's first free slot", (L.eval('WORN[5]'), L.eval('BAG[0][3]')))
mv = L.eval('lastMove()')
check(mv.ok is True and mv.by == 'unequip' and mv.to.bag == 0 and mv.to.slot == 3, "Bags' reply: ok, by unequip, to 0:3")
check(L.eval('ST.cursor') is None, "nothing left on the cursor")
a = L.eval('eqs().ack')
check(a.op == 'unequip' and a.ok is True and a.id == 5, "Character's eqs ack: unequip ok, slot 5", (a.op, a.ok, a.id))
# 2. the doubled press of the input path (~3 ms later) is swallowed (no pick, no move)
n1 = L.eval('nEmits()')
L.execute('NOW = NOW + 0.003; bmvq(4)')
check(len(L.eval('movesSince')(n1)) == 0, "the input path's second press on the same column is swallowed")
L.execute('run(1)')
conf = [m for m in L.eval('movesSince')(n0).values() if m.confirm is not None]
check(len(conf) == 1 and conf[0].confirm is True, "confirmed after the bag update (0.6 s)")
check(L.eval('eqs().ack.ok') is True, "a good confirm adds no refusal")

# 3. A STALE BAG PICK never equips: the Bags room's drag left bag 0 slot 1 picked; arm + slot
L.execute('NOW = 300; OPS.bmv0(nil, 0)')
check(L.eval('lastMove().picked ~= nil'), "a bag pick is held (the Bags room's finger-drag)")
L.execute('NOW = 301; unequipVia(1)')
check(L.eval('CALLS.equip') == 0 and L.eval('WORN[1]') is None and L.eval('BAG[0][1]') == 117, "stale pick + Unequip: the head came off, nothing equipped",
      (L.eval('CALLS.equip'), L.eval('WORN[1]'), L.eval('BAG[0][1]')))
# the pick is void: a later bmvq tap (no arm) is a pick of worn gear, never a drop
L.execute('NOW = 303; bmvq(15)')
check(L.eval('CALLS.equip') == 0 and L.eval('lastMove().picked ~= nil and lastMove().picked.inv == 16'), "after the arm the old bag pick is void (next tap = a pick)")
L.execute('NOW = 320')       # let that pick expire
# a stale pick, an arm, a LATE slot tap (2..10 s): swallowed, still nothing equipped
L.execute('OPS.bmv0(nil, 1); NOW = 321; tapSet("uneq"); NOW = 324; bmvq(16)')
check(L.eval('lastMove().why') == 'late' and L.eval('CALLS.equip') == 0 and L.eval('WORN[17]') == 902, "a late slot tap: 'late', nothing moved or equipped")
L.execute('NOW = 325; bmvq(16)')
check(L.eval('CALLS.equip') == 0 and L.eval('lastMove().picked ~= nil and lastMove().picked.inv == 17'), "then a plain tap: only a pick, never an equip")
L.execute('NOW = 340')

# 4. refusals: nothing moved, nothing on the cursor
def refusal(lua, why, what):
    before = L.eval('CALLS.pickInv')
    L.execute(lua)
    mv = L.eval('lastMove()')
    check(mv.ok is False and mv.why == why and L.eval('ST.cursor') is None, what, (mv.ok, mv.why))
    return L.eval('CALLS.pickInv') - before
refusal('NOW = 341; unequipVia(3)', 'empty', "an empty slot: empty")
L.execute('NOW = 350')
check(refusal('WowCompanion.bagsUnequip(0)', 'bad', "ammo (slot 0) is not offered: bad") == 0, "ammo: no pick-up")
refusal('WowCompanion.bagsUnequip(20)', 'bad', "slot 20: bad")
L.execute('NOW = 360; ST.cursor = { bag = 0, slot = 2, id = 118 }')
mvc = L.eval('(function() WowCompanion.bagsUnequip(17); return lastMove() end)()')
check(mvc.why == 'cursor' and L.eval('WORN[17]') == 902, "something on the cursor: cursor, nothing moved")
L.execute('ST.cursor = nil')
L.execute('NOW = 370; WowCompanion.bagsUnequip(17); NOW = 370.1')
r = refusal('WowCompanion.bagsUnequip(18)', 'settle', "right after a move: settle")
# every plain slot taken (the quiver, family 1, stays empty: gear never goes there)
L.execute('NOW = 380; for s = 1, 4 do BAG[0][s] = BAG[0][s] or 600 + s end; BAG[2][1] = BAG[2][1] or 556; BAG[2][2] = BAG[2][2] or 557')
check(L.eval('(C_Container.GetContainerNumFreeSlots(1))') == 2, "the quiver still has room (a special bag)")
check(refusal('NOW = 390; WowCompanion.bagsUnequip(18)', 'full', "plain bags full (only the quiver free): full") == 0, "full: no pick-up")
check(L.eval('WORN[18]') == 903, "full: the ranged piece stays worn")
a = L.eval('eqs().ack')
check(a.op == 'unequip' and a.ok is False and a.why == 'full', "Character's ack says full", (a.op, a.ok, a.why))
L.execute('BAG[2][2] = nil')      # one plain slot free again (bag 2 slot 2)

# 5. in a fight: armour refused (fightRefused), weapons tried
L.execute('NOW = 400; COMBAT = true; WORN[5] = 900; BAG[0][3] = 603')
r0 = L.eval('REFUSED')
check(refusal('unequipVia(5)', 'combat', "armour in a fight: combat") == 0, "armour in a fight: no pick-up at all")
check(L.eval('REFUSED') == r0 + 1 and L.eval('WORN[5]') == 900, "armour in a fight: fightRefused, still worn")
L.execute('NOW = 410; ST.block = { what = "pick", fn = "PickupInventoryItem()" }')
refusal('unequipVia(18)', 'combat', "a weapon the game blocks on the pick-up: combat")
check(L.eval('WORN[18]') == 903 and L.eval('BAG[2][2]') is None, "blocked pick-up: nothing moved")
L.execute('NOW = 420; ST.block = { what = "put", fn = "C_Container.PickupContainerItem()" }')
refusal('unequipVia(18)', 'combat', "a weapon the game blocks on the put-down: combat, the cursor cleared")
check(L.eval('WORN[18]') == 903 and L.eval('BAG[2][2]') is None, "blocked put-down: the weapon stays worn")
a = L.eval('eqs().ack')
check(a.why == 'combat' and a.ok is False, "Character's ack says combat")
L.execute('NOW = 430; ST.block = nil')
L.execute('unequipVia(18)')
check(L.eval('WORN[18]') is None and L.eval('BAG[2][2]') == 903 and L.eval('lastMove().ok') is True and L.eval('lastMove().fight') is True,
      "a weapon in a fight the game allows: moved")
check(L.eval('CALLS.equip') == 0, "EquipCursorItem never called on any unequip route")
L.execute('COMBAT = false')

# 6. Character: the More menu holds B; B closes it (one step back), not a leave
L.execute('NOW = 500; WowCompanion.character.touchOpen()')
check(L.eval('SPEC ~= nil'), "the Character room holds the controller (touch)")
L.execute('tapSet("moreon")')
check(L.eval('eqs().more') is True, "moreon: the eqs part says more")
L.execute('SPEC.press("PAD2")')
check(L.eval('eqs().more') is False, "B with the menu open: the menu closes (more false)")
check(L.eval('SPEC ~= nil') and 'back' not in str(L.eval('WowCompanion.character.ctl.note')), "...and the room did not let go",
      L.eval('WowCompanion.character.ctl.note'))
L.execute('tapSet("moreon"); tapSet("moreoff")')
check(L.eval('eqs().more') is False, "moreoff closes it")
L.execute('tapSet("moreon"); NOW = 510; tapSet("uneq")')
check(L.eval('eqs().more') is False, "the Unequip arm closes it too")
L.execute('NOW = 520; bmvq(16)')       # consume the arm (slot 17 worn? it was moved earlier: empty)

# 7. no trace
check(L.eval('#TRACE') == 0, "no trace: no SetBinding / macro / CVar / Blizzard open-close / StaticPopup call", list(L.eval('TRACE').values()))

print(f'\n{passes} passed, {fails} failed')
sys.exit(1 if fails else 0)
