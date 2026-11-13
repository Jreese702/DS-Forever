"""char-more (the user, 2026-11-13, on the Character room's Y = More menu for a worn piece: "if unequip is the only thing in this more
menu then what even is the point? we should have options there like unequip, replace and enchant"). Loads the REAL WowCompanion/Bags.lua
and WowCompanionCharacter/Character.lua in Lua 5.1 with stand-in game functions and checks the add-on's half:
  - Character c16: the `moresub` cell (a list of the menu is open): B goes back to the menu first (msub false, more still true, not a
    leave), then closes it; `moreon` from a list = the menu alone again; the sets' retain keeps the cell;
  - Character c16: the game's own question over the room (EQUIP_BIND*, REPLACE_ENCHANT ...) hands A and B to the game while it shows,
    then the pass goes back; any other pop-up is left alone;
  - Bags: a gear row says `nouse` when the game won't let this character wear it (required level, C_PlayerInfo.CanUseItem false, a red
    line on its kept tooltip); a wearable one does not;
  - Bags: a kit's pick asks the game about every worn piece (KIT.learn) and the kit's row then carries kfy / kfn for the pieces worn now;
    a new piece in a slot drops that slot's answer; only picks with a real check (enchant / cond) are learned;
  - Bags: a kit used from its own bag cell (the Character room's Enchant) names the pick; gear on its cell does not;
  - Bags: a drop on a worn slot that makes the game ask "bind it to you?" leaves the piece on the cursor while it asks; a No puts it back;
  - no trace: no SetBinding / macro / CVar / Blizzard open-close / StaticPopup call.
Run: python -B tools/test_char_more.py
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
EMITS = {}
OPS, SECURE = {}, {}
PAD = { ns = {} }
function PAD.op(name, ns, fn) OPS[name] = fn end
function PAD.secureRow(name, n, init, onClick) SECURE[name] = { n = n, init = init, onClick = onClick }; return 1 end
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
    send = function() end, encode = function() return "" end, push = function() end, isPlain = function() return true end,
    pad = PAD, VERSION = "test",
}
COMBAT = false
function InCombatLockdown() return COMBAT end
function UnitAffectingCombat() return COMBAT end
function UnitName() return "Shamy" end
function GetRealmName() return "Forever" end
LEVEL = 10
function UnitLevel() return LEVEL end
TRACE = {}
for _, n in ipairs({ "SetBinding", "SaveBindings", "DeleteMacro", "CreateMacro", "EditMacro", "SetCVar", "ShowUIPanel", "HideUIPanel",
                     "ToggleCharacter", "CloseAllBags", "OpenAllBags", "ToggleBackpack", "StaticPopup_Show", "StaticPopup_Hide",
                     "SpellStopTargeting" }) do
    _G[n] = function() TRACE[#TRACE + 1] = n end
end
function GetNumMacros() return 0, 0 end
function SetOverrideBindingClick() end
function SetOverrideBinding() end
function GetBindingAction() return "" end
bit = { band = function(a, b)
    local r, p = 0, 1
    while a > 0 and b > 0 do
        local x, y = a % 2, b % 2
        if x == 1 and y == 1 then r = r + p end
        a, b, p = (a - x) / 2, (b - y) / 2, p * 2
    end
    return r
end }
-- THE GAME: the backpack (4 slots), worn gear, ONE cursor, the game's pop-ups
NSLOTS = { [0] = 4 }
BAG = { [0] = { [1] = 2304, [2] = 4239, [3] = 5001, [4] = 5002 } }    -- a kit, a belt, a plate belt (can't use), a level-30 belt
WORN = { [5] = 2580, [6] = 4200, [10] = 2584 }
EQUIP = { [2580] = "INVTYPE_CHEST", [2584] = "INVTYPE_HAND", [4200] = "INVTYPE_WAIST", [4239] = "INVTYPE_WAIST",
          [5001] = "INVTYPE_WAIST", [5002] = "INVTYPE_WAIST", [2304] = "", [6001] = "INVTYPE_HAND" }
MINLVL = { [5002] = 30 }
CANUSE = { [5001] = false }
SPELL = { [2304] = "Armor +24" }
ST = { cursor = nil }
POPUP = nil          -- the game's StaticPopup1: nil = none, else its `which`
StaticPopup1 = { IsShown = function() return POPUP ~= nil end }
setmetatable(StaticPopup1, { __index = function(_, k) if k == "which" then return POPUP end end })
for i = 2, 4 do _G["StaticPopup" .. i] = { IsShown = function() return false end } end
BINDS = {}           -- item ids the game asks "bind it to you?" for when equipped
CALLS = { equip = 0 }
function PickupInventoryItem(slot) if not ST.cursor and WORN[slot] then ST.cursor = { inv = slot, id = WORN[slot] } end end
function EquipCursorItem(slot)
    CALLS.equip = CALLS.equip + 1
    if not ST.cursor then return end
    if BINDS[ST.cursor.id] then POPUP = "EQUIP_BIND"; ST.pending = slot; return end          -- the game asks; the piece stays on the cursor
    local was = WORN[slot]; WORN[slot] = ST.cursor.id; BAG[ST.cursor.bag][ST.cursor.slot] = was; ST.cursor = nil
end
function CursorHasItem() return ST.cursor ~= nil end
function GetCursorInfo() if ST.cursor then return "item", ST.cursor.id end end
function ClearCursor() ST.cursor = nil end
C_Container = {
    GetContainerItemID = function(b, s) return BAG[b] and BAG[b][s] end,
    GetContainerNumSlots = function(b) return NSLOTS[b] or 0 end,
    GetContainerNumFreeSlots = function(b) local n = 0; for s = 1, NSLOTS[b] or 0 do if not BAG[b][s] then n = n + 1 end end; return n, 0 end,
    GetContainerItemInfo = function(b, s) local id = BAG[b] and BAG[b][s]; if id then return { itemID = id, iconFileID = 1, quality = 1, stackCount = 1 } end end,
    GetContainerItemLink = function(b, s) local id = BAG[b] and BAG[b][s]; if id then return "|Hitem:" .. id .. "|h" end end,
    PickupContainerItem = function(b, s) if not ST.cursor and BAG[b][s] then ST.cursor = { bag = b, slot = s, id = BAG[b][s] } end end,
}
C_Item = { GetItemNameByID = function(id) return "Item " .. id end, GetItemIconByID = function() return 1 end,
           GetItemInfoInstant = function(id) return id, "Armor", "Leather", EQUIP[id] or "", 1, (id == 2304) and 0 or 4, (id == 2304) and 6 or 2 end,
           GetItemFamily = function() return 0 end, GetItemSpell = function(id) return SPELL[id] end, RequestLoadItemDataByID = function() end,
           DoesItemMatchSpellItemCondition = function(loc) return CONDOK[loc.k] end }
function GetItemInfo(id) return "Item " .. id, "|Hitem:" .. id .. "|h", 1, 10, MINLVL[id] or 1 end
C_PlayerInfo = { CanUseItem = function(id) if CANUSE[id] == nil then return true end; return CANUSE[id] end }
PICKING, COND, CONDOK = false, false, {}
function SpellCanTargetItem() return PICKING end
function SpellCanTargetItemID() return false end
function SpellIsTargeting() return PICKING end
function IsCurrentSpell() return false end
C_Spell = { TargetSpellIsEnchanting = function() return false end, TargetSpellChecksItemCondition = function() return COND end,
            GetTargetSpellID = function() return 2831 end }
ItemLocation = { CreateFromEquipmentSlot = function(_, s) return { k = "inv" .. s } end,
                 CreateFromBagAndSlot = function(_, b, s) return { k = "bag" .. b .. ":" .. s } end }
function GetInventoryItemID(unit, slot) return WORN[slot] end
function GetInventoryItemLink(unit, slot) if WORN[slot] then return "|Hitem:" .. WORN[slot] .. "|h" end end
C_EquipmentSet = { GetEquipmentSetIDs = function() return { 7 } end,
                   GetEquipmentSetInfo = function(id) return "Tank", 1, id, false, 1, 1, 0, 0 end }
for _, n in ipairs({ "MerchantFrame", "GossipFrame", "QuestFrame", "BankFrame", "MailFrame", "TradeFrame", "AuctionHouseFrame",
                     "GuildBankFrame", "ClassTrainerFrame", "CharacterFrame" }) do _G[n] = { IsShown = function() return false end } end
PASSES, SPEC = {}, nil
WowCompanion.capture = { version = 11, take = function(id, spec) if id == "character" then SPEC = spec end; return true end,
    pass = function(id, keys) if PASS_REFUSE == true then return false, "combat" end; PASSES[#PASSES + 1] = table.concat(keys, " "); return true end, release = function() return true end,
    state = function() return { holder = "character" } end, refused = function() return true end }
function eqs() return WowCompanion.character.collect.eqs() end
function tapSet(key) WowCompanion.character.onSetCell(WowCompanion.character.eqFirst + PAD.slotFor("eqset", key)) end
function rowAt(bag, slot)
    for _, r in ipairs(WowCompanion.collectBags().items) do if r.bag == bag and r.slot == slot then return r end end
end
function lastMove() for i = #EMITS, 1, -1 do local e = EMITS[i]; if e.bagsMove then return e.bagsMove end end end
''')
load = L.eval('function(src, name, addon) local f, e = loadstring(src, "@" .. name); if not f then error(e) end; return f(addon) end')
for src, name, addon in ((BAGS, 'Bags.lua', 'WowCompanion'), (CHAR, 'Character.lua', 'WowCompanionCharacter')):
    ok_load = True
    try:
        load(src, name, addon)
    except Exception as e:  # noqa: BLE001
        ok_load = False; print(e)
    check(ok_load, f"{name} loads (stand-in game) with no error")
L.execute('WowCompanionDB = {}; fire("PLAYER_LOGIN"); WowCompanion._bagsLayout = { { bag = 0, slots = 4 } }; WowCompanion.bagsPadRows({ { bag = 0, slots = 4 } })')

# ---- Character c16
check(str(L.eval('WowCompanion.character.version')) == 'c18', "Character's version is bumped (c18)", L.eval('WowCompanion.character.version'))
d = L.eval('eqs()')
check(d.msb is not None and d.msb not in (d.uq, d.mon, d.moff) and d.msub is False, "eqs: the moresub cell (its own), msub false", (d.msb, d.uq, d.mon, d.moff))
L.execute('WowCompanion.character.collect.sets()')
check(L.eval('PAD.ns.eqset.byKey.moresub') is not None, "the sets' retain keeps the moresub cell")
L.execute('NOW = 200; WowCompanion.character.touchOpen()')
check(L.eval('SPEC ~= nil'), "the Character room holds the controller (touch)")
L.execute('tapSet("moreon")')
check(L.eval('eqs().more') is True and L.eval('eqs().msub') is False, "moreon: the menu (more, no list)")
L.execute('tapSet("moresub")')
check(L.eval('eqs().more') is True and L.eval('eqs().msub') is True, "moresub: a list over the menu (more + msub)")
L.execute('SPEC.press("PAD2")')
check(L.eval('eqs().more') is True and L.eval('eqs().msub') is False, "B on the list: one step back to the menu (more stays, msub false)")
check('back' not in str(L.eval('WowCompanion.character.ctl.note')), "...not a leave", L.eval('WowCompanion.character.ctl.note'))
L.execute('SPEC.press("PAD2")')
check(L.eval('eqs().more') is False, "B on the menu: it closes (more false)")
check(L.eval('SPEC ~= nil') and 'back' not in str(L.eval('WowCompanion.character.ctl.note')), "...and the room did not let go")
L.execute('tapSet("moreon"); tapSet("moresub"); tapSet("moreon")')
check(L.eval('eqs().more') is True and L.eval('eqs().msub') is False, "the app's own Back from a list (moreon): the menu alone again")
L.execute('tapSet("moreoff")')
check(L.eval('eqs().more') is False and L.eval('eqs().msub') is False, "moreoff from anywhere: all closed")
L.execute('SPEC.press("PAD2")')
check('back' in str(L.eval('WowCompanion.character.ctl.note')), "with nothing open B leaves as before", L.eval('WowCompanion.character.ctl.note'))
L.execute('NOW = 210; WowCompanion.character.touchOpen()')

# the game's own question hands A / B to the game, then back
n0 = int(L.eval('#PASSES'))
L.execute('POPUP = "EQUIP_BIND"; WowCompanion.character.syncAsk()')
check(int(L.eval('#PASSES')) == n0 + 1 and L.eval('PASSES[#PASSES]') == 'PAD1 PAD2', "the game asks 'bind it to you?': A and B go to the game",
      L.eval('PASSES[#PASSES]'))
L.execute('WowCompanion.character.syncAsk()')
check(int(L.eval('#PASSES')) == n0 + 1, "still asking: nothing passed again")
L.execute('POPUP = nil; WowCompanion.character.syncAsk()')
check(int(L.eval('#PASSES')) == n0 + 2 and L.eval('PASSES[#PASSES]') == '', "answered: the pass goes back (none: the room holds by touch)",
      L.eval('PASSES[#PASSES]'))
L.execute('POPUP = "REPLACE_ENCHANT"; WowCompanion.character.syncAsk()')
check(L.eval('PASSES[#PASSES]') == 'PAD1 PAD2', "'Replace enchant?' over the room: A and B to the game")
L.execute('POPUP = nil; WowCompanion.character.syncAsk(); POPUP = "DELETE_ITEM"; WowCompanion.character.syncAsk()')
check(L.eval('PASSES[#PASSES]') == '', "another pop-up (Delete?) is not ours to hand over", L.eval('PASSES[#PASSES]'))
L.execute('POPUP = nil; WowCompanion.character.syncAsk()')
# char-more2 (review): a pass the capture refuses (a fight) is tried again; the menu's own B pass never takes A / B back from the game
n0 = int(L.eval('#PASSES'))
L.execute('PASS_REFUSE = true; POPUP = "EQUIP_BIND"; WowCompanion.character.syncAsk()')
check(int(L.eval('#PASSES')) == n0, "the capture refused the pass (a fight): nothing handed over yet")
L.execute('PASS_REFUSE = false; WowCompanion.character.syncAsk()')
check(int(L.eval('#PASSES')) == n0 + 1 and L.eval('PASSES[#PASSES]') == 'PAD1 PAD2', "...tried again on the next tick: A and B to the game",
      L.eval('PASSES[#PASSES]'))
n1 = int(L.eval('#PASSES'))
L.execute('tapSet("moreon")')
check(int(L.eval('#PASSES')) == n1, "the More menu opening while the game asks leaves A / B with the game", L.eval('PASSES[#PASSES]'))
L.execute('PASS_REFUSE = true; POPUP = nil; WowCompanion.character.syncAsk()')
check(int(L.eval('#PASSES')) == n1, "answered, but the way back refused: tried again")
L.execute('PASS_REFUSE = false; WowCompanion.character.syncAsk()')
check(int(L.eval('#PASSES')) == n1 + 1 and L.eval('PASSES[#PASSES]') == '', "...then the pass goes back", L.eval('PASSES[#PASSES]'))
L.execute('WowCompanion.character.syncAsk()')
check(int(L.eval('#PASSES')) == n1 + 1, "nothing asked: nothing passed again")
L.execute('tapSet("moreoff")')

# c18 (the user on build 1462: "pressing B in the more menu closes the character page and leaves the menu open."): the eqs part says
# what B does while the menu is up, so the app goes Home on B only when B really leaves (gask: the game's question; bgo: B still
# reaches the game's window because the capture refused the pass in a fight); a refused pass is tried again on the tick
check(L.eval('eqs().gask') is False and L.eval('eqs().bgo') is False, "c18: nothing open: gask false, bgo false")
L.execute('WowCompanion.character.ctl.via = "window"; WowCompanion.character.syncB()')
n0 = int(L.eval('#PASSES'))
L.execute('tapSet("moreon")')
check(int(L.eval('#PASSES')) == n0 + 1 and L.eval('PASSES[#PASSES]') == '' and L.eval('eqs().bgo') is False,
      "c18: the menu opened from the game's window: B taken off the game (pass none), bgo false", (L.eval('PASSES[#PASSES]'), L.eval('eqs().bgo')))
L.execute('tapSet("moreoff")')
check(L.eval('PASSES[#PASSES]') == 'PAD2' and L.eval('eqs().bgo') is False, "c18: closed: B back to the game (its window's B), bgo false")
L.execute('PASS_REFUSE = true; tapSet("moreon")')
check(L.eval('eqs().more') is True and L.eval('eqs().bgo') is True, "c18: the pass refused (a fight): bgo true (B still reaches the game's window)")
n0 = int(L.eval('#PASSES'))
L.execute('WowCompanion.character.syncB()')
check(int(L.eval('#PASSES')) == n0 and L.eval('eqs().bgo') is True, "c18: still refused on the tick: bgo stays true")
L.execute('PASS_REFUSE = false; WowCompanion.character.syncB()')
check(int(L.eval('#PASSES')) == n0 + 1 and L.eval('PASSES[#PASSES]') == '' and L.eval('eqs().bgo') is False,
      "c18: the tick tries again: B taken off the game, bgo false", (L.eval('PASSES[#PASSES]'), L.eval('eqs().bgo')))
L.execute('WowCompanion.character.syncB()')
check(int(L.eval('#PASSES')) == n0 + 1, "c18: once taken: nothing passed again")
L.execute('POPUP = "EQUIP_BIND"; WowCompanion.character.syncAsk()')
check(L.eval('eqs().gask') is True and L.eval('eqs().bgo') is False, "c18: the game's own question up: gask true (B answers it), bgo false")
L.execute('POPUP = nil; WowCompanion.character.syncAsk()')
check(L.eval('eqs().gask') is False and L.eval('PASSES[#PASSES]') == '' and L.eval('eqs().bgo') is False,
      "c18: answered with the menu still open: B the room's again (pass none), gask false", L.eval('PASSES[#PASSES]'))
L.execute('SPEC.press("PAD2")')
check(L.eval('eqs().more') is False and L.eval('PASSES[#PASSES]') == 'PAD2' and 'back' not in str(L.eval('WowCompanion.character.ctl.note')),
      "c18: B closes the menu only (the room keeps the controller; B the game's window's again)", L.eval('WowCompanion.character.ctl.note'))
L.execute('tapSet("moreon")')
L.execute('PASS_REFUSE = true; tapSet("moreoff")')
n0 = int(L.eval('#PASSES'))
L.execute('PASS_REFUSE = false; WowCompanion.character.syncB()')
check(int(L.eval('#PASSES')) == n0 + 1 and L.eval('PASSES[#PASSES]') == 'PAD2', "c18: closed while the way back was refused: the tick gives B to the window again",
      L.eval('PASSES[#PASSES]'))
L.execute('WowCompanion.character.syncB()')
check(int(L.eval('#PASSES')) == n0 + 1, "c18: ... once")
L.execute('WowCompanion.character.ctl.via = "touch"')

# ---- Bags: what Replace may list
check(L.eval('rowAt(0, 2).nouse') is None, "a wearable belt: no nouse")
check(L.eval('rowAt(0, 3).nouse') is True, "the game's CanUseItem says no (a plate belt): nouse")
check(L.eval('rowAt(0, 4).nouse') is True, "required level 30 at level 10: nouse")
L.execute('LEVEL = 30')
check(L.eval('rowAt(0, 4).nouse') is None, "at level 30 it can be worn: no nouse")
L.execute('LEVEL = 10')
check(L.eval('rowAt(0, 1).nouse') is None and L.eval('rowAt(0, 1).kit') is True, "the kit: a kit row, never nouse")

# ---- Bags: the kits' fits, learned from the game
check(L.eval('rowAt(0, 1).kfy') is None and L.eval('rowAt(0, 1).kfn') is None, "nothing asked yet: no kfy / kfn (the room offers the kit)")
step = L.eval('WowCompanion._kit.step')
L.execute('NOW = 300; SECURE.bag0.onClick(0)')       # the Character room's tap on the kit's own cell (its use already ran)
check(L.eval('WowCompanion._kit.last and WowCompanion._kit.last.id') == 2304, "a kit used from its own cell is named (KIT.used)")
L.execute('PICKING = true; COND = true; CONDOK = { inv5 = true, inv10 = true, inv6 = false }')
step()
check(L.eval('WowCompanion._kit.pickKit') == 2304, "the pick that starts is that kit's")
r = L.eval('rowAt(0, 1)')
check(r.kfy == ',5,10,' and r.kfn == ',6,', "the kit row says the game's answers: chest + hands yes, waist no", (r.kfy, r.kfn))
L.execute('PICKING = false'); step()
check(L.eval('WowCompanion._kit.pickKit') is None, "the pick ended: nobody's")
L.execute('WORN[10] = 6001')
r = L.eval('rowAt(0, 1)')
check(r.kfy == ',5,' and r.kfn == ',6,', "new gloves worn: their slot's answer is dropped (asked again next time)", (r.kfy, r.kfn))
L.execute('WORN[10] = 2584')
# a pick with no real check (spell) is never learned
L.execute('WowCompanion._kitFits = {}; NOW = 400; SECURE.bag0.onClick(0); PICKING = true; COND = false')
step()
check(L.eval('rowAt(0, 1).kfy') is None and L.eval('rowAt(0, 1).kfn') is None, "a pick the game gives no check for: nothing learned")
# its type known late (the game's item context arrives): learned then
L.execute('COND = true; CONDOK = { inv5 = true }')
step()
check(L.eval('rowAt(0, 1).kfy') == ',5,', "the pick's type came late: learned then", L.eval('rowAt(0, 1).kfy'))
L.execute('PICKING = false; COND = false'); step()
# a GEAR tap on its own cell (its equip ran) names nothing
L.execute('WowCompanion._kit.last = nil; SECURE.bag0.onClick(1)')
check(L.eval('WowCompanion._kit.last') is None, "a gear piece's cell (its equip): no kit named")

# ---- Bags: the drag onto a worn slot that the game asks "bind it to you?" about
L.execute('NOW = 500; BINDS[4239] = true; OPS.bagsort(nil, 10); OPS.bmv(nil, 1); NOW = 500.2; OPS.bmvq(nil, 5)')
mv = L.eval('lastMove()')
check(mv.ok is True and mv.ask == 'bind' and L.eval('ST.cursor and ST.cursor.id') == 4239,
      "the game asks: the piece stays on the cursor (not cleared = not a No)", (mv.ok, mv.ask, L.eval('ST.cursor and ST.cursor.id')))
L.execute('run(1)')
check(L.eval('ST.cursor and ST.cursor.id') == 4239, "still asking a second later: left alone")
L.execute('POPUP = nil; run(0.5)')         # the player said No: the game closed its question, the piece still in the hand
check(L.eval('ST.cursor') is None, "a No: the piece is put back (the cursor cleared)")
# char-more2 (review): in the Bags room (it holds the controller) the drag's bind question gets A / B too, then they come back
L.execute('NOW = 550; OPS.bmvq(nil, 29)')
bags_on = L.eval('WowCompanion._bagsCtlDoc().on')
n0 = int(L.eval('#PASSES'))
L.execute('NOW = 560; ST.cursor = nil; BINDS[4239] = true; BAG[0][2] = 4239; WORN[6] = 4200; OPS.bagsort(nil, 10); OPS.bmv(nil, 1); NOW = 560.2; OPS.bmvq(nil, 5)')
check(bags_on is True and 'PAD1 PAD2' in [L.eval(f'PASSES[{i}]') for i in range(n0 + 1, int(L.eval('#PASSES')) + 1)],
      "the Bags room's drag: the game's bind question gets A and B", (bags_on, [L.eval(f'PASSES[{i}]') for i in range(n0 + 1, int(L.eval('#PASSES')) + 1)]))
L.execute('POPUP = nil; run(0.5)')
check(L.eval('ST.cursor') is None and L.eval('PASSES[#PASSES]') != 'PAD1 PAD2', "answered: the piece put back, A / B the room's again",
      (L.eval('ST.cursor'), L.eval('PASSES[#PASSES]')))
L.execute('NOW = 570; OPS.bmvq(nil, 30)')
L.execute('NOW = 600; BINDS = {}; OPS.bagsort(nil, 10); OPS.bmv(nil, 1); NOW = 600.2; OPS.bmvq(nil, 5)')
check(L.eval('WORN[6]') == 4239 and L.eval('ST.cursor') is None and L.eval('lastMove().ok') is True and L.eval('lastMove().ask') is None,
      "no question: equipped into THAT slot at once, as before", (L.eval('WORN[6]'), L.eval('lastMove().ask')))

check(L.eval('#TRACE') == 0, "no trace: no SetBinding / macro / CVar / Blizzard open-close / StaticPopup / SpellStopTargeting call",
      list(L.eval('TRACE').values()))
print(f'\n{passes} passed, {fails} failed')
sys.exit(1 if fails else 0)
