"""bags-act1 (the user's picks, 2026-11-13, on E:/wow-build/bags_design_1113.md): the Bags room's Compare action, the hardened Destroy
and the Destroy picker's ranked "Suggested" list. Loads the real WowCompanion/Bags.lua in Lua 5.1 with stand-in game functions and
drives the room's controller (and its touch cells) to check:
  - the Y menu has four rows (Split, Bind, Compare, Destroy; ctl.menus names them); Compare only on gear, Destroy greyed in a fight;
  - Compare (the controller's A on its row, or a finger: bmvq 27 then column 8) opens mode "compare": the item's tooltip rides in it,
    the D-pad changes nothing in the game, B goes back to the grid; nothing is picked up or deleted;
  - Destroy with nothing to suggest opens the single confirm at once (as before); A = ONE delete through WC.bagsDestroy (pick-up +
    DeleteCursorItem), noted "destroying", then "destroyed" at the next bag update (not in the same instant); B = No;
  - something on the cursor (GetCursorInfo: a spell, not only an item) refuses the delete; a fight refuses it (greyed + refused);
    rare and better are refused (the box, nothing deleted);
  - the picker's Suggested list is the ranked mix: grey / no-value (lowest stack value first), then worse or unusable gear, then the
    cheapest whites; never quest items, items a quest in the log needs, rare and better, the hearthstone, keys or a fishing pole; the
    cursor's own item last ("This item", focused); select + A = that item's confirm; B there = back to the picker; Yes = that item;
  - a finger on a picker row (bmvq 36 + its bmv cell) moves the gold row; a value still loading is re-ranked when it loads;
  - no trace: no StaticPopup, SetBinding, SaveBindings, macro or CVar call.
Run: python -B tools/test_bags_actions.py
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
        best.fn()
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
local function enc(v)
    local t = type(v)
    if t == "table" then
        local keys = {}
        for k in pairs(v) do keys[#keys + 1] = tostring(k) end
        table.sort(keys)
        local parts = {}
        for _, k in ipairs(keys) do local x = v[k]; if x == nil then x = v[tonumber(k)] end; parts[#parts + 1] = k .. "=" .. enc(x) end
        return "{" .. table.concat(parts, ",") .. "}"
    end
    return tostring(v)
end
EMITS = {}
WowCompanion = {
    S = function(fn) local ok, v = pcall(fn); if ok then return v end end,
    SN = function(fn) local ok, v = pcall(fn); if ok and type(v) == "number" then return v end end,
    CALL = function(fn) return pcall(fn) end,
    emit = function(t) EMITS[#EMITS + 1] = t end,
    send = function() end, encode = enc, push = function() end, isPlain = function() return true end,
    pad = { secureRow = function() return 1 end, op = function() end, describe = function() return nil end },
    bagsPadRows = function() end, bagsMoveRows = function() end,
}
COMBAT = false
function InCombatLockdown() return COMBAT end
function UnitName() return "Shamy" end
function GetRealmName() return "Forever" end
TRACE = {}
for _, n in ipairs({ "SetBinding", "SaveBindings", "DeleteMacro", "CreateMacro", "EditMacro", "SetCVar", "ShowUIPanel", "HideUIPanel",
                     "CloseAllBags", "OpenAllBags", "ToggleBackpack", "StaticPopup_Show", "StaticPopup_Hide", "StaticPopup_OnClick" }) do
    _G[n] = function() TRACE[#TRACE + 1] = n end
end
function GetNumMacros() return 0, 0 end
function SetOverrideBindingClick() end
function SetOverrideBinding() end
function GetBindingAction() return "" end
-- ITEMS: id -> { q quality, v sell price each, loc, cls, sub, ilvl, n stack }
ITEMS = {
    [601] = { q = 0, v = 5 },                                   -- grey, 5 c
    [602] = { q = 0, v = 1, n = 1 },                            -- grey, 1 c
    [603] = { q = 1, v = 40, loc = "INVTYPE_CHEST", cls = 4, sub = 1, ilvl = 5 },   -- worse chest (worn ilvl 10)
    [604] = { q = 2, v = 90, loc = "INVTYPE_HEAD", cls = 4, sub = 4, ilvl = 20 },   -- a helm the player cannot wear (red line)
    [605] = { q = 2, v = 70, loc = "INVTYPE_FEET", cls = 4, sub = 1, ilvl = 30 },   -- boots for an EMPTY feet slot: kept
    [606] = { q = 1, v = 10, n = 2 },                           -- white stack, 20 c
    [607] = { q = 1, v = 3 },                                   -- white, 3 c
    [608] = { q = 1, v = 1 },                                   -- a quest item (the game's quest info)
    [609] = { q = 1, v = 1 },                                   -- an item a quest in the log needs
    [6948] = { q = 1, v = 0 },                                  -- the hearthstone
    [611] = { q = 1, v = 0, cls = 13 },                         -- a key
    [612] = { q = 3, v = 1 },                                   -- rare
    [613] = { q = 1, v = 2, cls = 2, sub = 20, loc = "INVTYPE_2HWEAPON" },          -- a fishing pole
    [614] = { q = 2, v = 50 },                                  -- a green trade good: never suggested
}
BAG = { [0] = { 601, 602, 603, 604, 605, 606, 607, 608, 609, 6948, 611, 612, 613, 614 } }
STACK = { [606] = 2 }
function itemAt(b, s) return BAG[b] and BAG[b][s] end
HAND = {}               -- what is on the cursor (HAND.c: a nil global would read as the stand-in table)
PICKS, DELETES = {}, {}
function GetCursorInfo() if HAND.c then return HAND.c.kind or "item" end end
function CursorHasItem() return HAND.c ~= nil and (HAND.c.kind == nil or HAND.c.kind == "item") end
function ClearCursor() if HAND.c and HAND.c.from then BAG[HAND.c.from[1]][HAND.c.from[2]] = HAND.c.id end; HAND.c = nil end
function DeleteCursorItem() if HAND.c and HAND.c.id then DELETES[#DELETES + 1] = HAND.c.id; HAND.c = nil end end
C_Container = {
    GetContainerItemID = function(b, s) return itemAt(b, s) end,
    GetContainerNumSlots = function(b) return b == 0 and 16 or 0 end,
    GetContainerNumFreeSlots = function(b) return 2, 0 end,
    GetContainerItemInfo = function(b, s) local id = itemAt(b, s); if id then local it = ITEMS[id] or {}
        return { itemID = id, iconFileID = 1, quality = it.q or 1, stackCount = STACK[id] or 1, hyperlink = "|Hitem:" .. id .. "|h",
                 hasNoValue = (it.v == 0) or nil } end end,
    GetContainerItemLink = function(b, s) local id = itemAt(b, s); if id then return "|Hitem:" .. id .. "|h" end end,
    GetContainerItemQuestInfo = function(b, s) if itemAt(b, s) == 608 then return { isQuestItem = true } end; return {} end,
    PickupContainerItem = function(b, s)
        PICKS[#PICKS + 1] = b .. ":" .. s
        if HAND.c then BAG[b][s], HAND.c = HAND.c.id, nil; return end
        local id = itemAt(b, s); if not id then return end
        HAND.c = { id = id, from = { b, s } }; BAG[b][s] = nil
    end,
}
LOADING = {}
local function idOf(x) if type(x) == "number" then return x end; return tonumber(tostring(x):match("item:(%d+)")) end
C_Item = {
    GetItemNameByID = function(id) return "Item " .. id end,
    GetItemInfoInstant = function(x) local id = idOf(x); local it = ITEMS[id] or {}
        return id, "Misc", "Junk", it.loc or "INVTYPE_NON_EQUIP_IGNORE", 1, it.cls or 15, it.sub or 0 end,
    GetItemInfo = function(x) local id = idOf(x); local it = ITEMS[id] or {}
        if LOADING[id] then return nil end
        return "Item " .. id, "|Hitem:" .. id .. "|h", it.q or 1, it.ilvl or 1, 1, "Misc", "Junk", 20, it.loc or "", 1, it.v or 0 end,
    GetDetailedItemLevelInfo = function(x) local id = idOf(x); if ITEMS[id] then return ITEMS[id].ilvl end; return WORNILVL[id] end,
    GetItemSpell = function() return nil end, RequestLoadItemDataByID = function() end, GetItemStats = function() return {} end,
}
-- worn: a chest of item level 10 and a helm of 40; the feet slot empty
WORN = { [5] = 900, [1] = 901 }
WORNILVL = { [900] = 10, [901] = 40 }
function GetInventoryItemID(unit, slot) return WORN[slot] end
function GetInventoryItemLink(unit, slot) if WORN[slot] then return "|Hitem:" .. WORN[slot] .. "|h" end end
function GetWeaponEnchantInfo() return false end
function CanDualWield() return false end
RETRIEVING_ITEM_INFO = "Retrieving item information"
WowCompanion.tipOf = function(key)
    local id = tonumber(key:match("(%d+)$"))
    local lines = { { t = "Item " .. id, c = "ffffff" } }
    if id == 604 then lines[#lines + 1] = { t = "Plate", c = "ff2020" } end
    return { key = key, lines = lines }
end
-- the quests in the log need item 609 (Quests.lua's WC.questNeedItems)
WowCompanion.questNeedItems = function() return { ids = { [609] = true }, words = {} } end
for _, n in ipairs({ "MerchantFrame", "GossipFrame", "QuestFrame", "BankFrame", "MailFrame", "TradeFrame", "AuctionHouseFrame",
                     "GuildBankFrame", "ClassTrainerFrame", "CharacterFrame" }) do _G[n] = { IsShown = function() return false end } end
SPEC = nil
WowCompanion.capture = { version = 11, take = function(_, spec) SPEC = spec; return true end, pass = function() return true end,
                         release = function() return true end, state = function() return { holder = "bags" } end,
                         chord = function() return true end, refused = function() return false end }
function press(k) SPEC.press(k); run(0) end
function doc() return WowCompanion._bagsCtlDoc() end
function to(slot) local d = doc(); while d.cur.slot ~= slot do press(d.cur.slot < slot and "PADDRIGHT" or "PADDLEFT"); d = doc() end end
function menuTo(name) for _ = 1, 8 do if doc().menu == name then return true end; press("PADDDOWN") end; return doc().menu == name end
function listIds() local d = doc(); local out = {}; if d.dpick then for i, e in ipairs(d.dpick.list) do out[i] = e.id end end; return out end
function listSec() local d = doc(); local out = {}; if d.dpick then for i, e in ipairs(d.dpick.list) do out[i] = e.sec end end; return out end
function deletes() return #DELETES end
function lastDelete() return DELETES[#DELETES] end
''')
load = L.eval('function(src) local f, e = loadstring(src, "@Bags.lua"); if not f then error(e) end; return f end')
ok_load = True
try:
    load(SRC)()
except Exception as e:  # noqa: BLE001
    ok_load = False; print(e)
check(ok_load, "Bags.lua loads (stand-in game) with no error")
L.execute('WowCompanionDB = {}; fire("PLAYER_LOGIN"); WowCompanion._bagsLayout = WowCompanion.collectBags().bags; WowCompanion._bagsTouch(29); run(0)')
check(L.eval('SPEC') is not None and L.eval('doc().on') is True, "the room took the controller")


def lst(t):
    return [t[i] for i in range(1, len(t) + 1)] if t is not None else None


# 1. the Y menu: four rows by name; Compare only on gear; Destroy greyed in a fight
L.execute('to(1); press("PAD4")')
check(lst(L.eval('doc().menus')) == ['split', 'bind', 'compare', 'destroy'], "the Y menu names four rows", lst(L.eval('doc().menus')))
u = L.eval('doc().usable')
check(u[3] is False and u[4] is True, "a grey item: Compare cannot be used, Destroy can", (u[3], u[4]))
L.execute('press("PAD2"); to(3); press("PAD4")')
check(L.eval('doc().usable[3]') is True, "gear: Compare can be used")
L.execute('COMBAT = true')
check(L.eval('doc().usable[4]') is False, "in a fight: Destroy greyed")
L.execute('COMBAT = false; press("PAD2")')

# 2. Compare by the controller: the item's tooltip rides in mode compare; the D-pad does nothing; B back to the grid
L.execute('to(3); press("PAD4")')
check(L.eval('menuTo("compare")'), "the menu's cursor reaches Compare")
L.execute('P0 = #PICKS; press("PAD1"); run(0.2)')
check(L.eval('doc().mode') == 'compare', "A on Compare: the compare sheet (mode compare)", L.eval('doc().mode'))
check(L.eval('doc().tip') is not None and L.eval('doc().tip.key') == 'bag 0 3 603', "its tooltip rides in the document",
      L.eval('doc().tip and doc().tip.key'))
L.execute('press("PADDDOWN"); press("PADDRIGHT")')
check(L.eval('doc().mode') == 'compare' and L.eval('doc().cur.slot') == 3, "the D-pad: the sheet stays, the cursor too (the app scrolls)")
L.execute('press("PAD2")')
check(L.eval('doc().mode') == 'grid' and L.eval('#PICKS') == L.eval('P0') and L.eval('deletes()') == 0,
      "B: back to the grid; nothing picked up or deleted", L.eval('doc().mode'))

# 2b. Compare by touch: bmvq 27 then column 8 while the menu is open; not on a non-gear item
L.execute('press("PAD4"); WowCompanion._bagsTouch(27); WowCompanion._bagsShutTap(8); run(0)')
check(L.eval('doc().mode') == 'compare', "a finger on Compare (27, then 8): the sheet", L.eval('doc().mode'))
L.execute('press("PAD2"); to(6); press("PAD4"); WowCompanion._bagsTouch(27); WowCompanion._bagsShutTap(8); run(0)')
check(L.eval('doc().mode') == 'menu', "a finger on Compare for a non-gear item: nothing (the row is greyed)", L.eval('doc().mode'))
L.execute('press("PAD2")')

# 3. the picker's Suggested list: the ranked mix
L.execute('to(14); press("PAD4"); WowCompanion._bagsTouch(21); run(0)')
check(L.eval('doc().mode') == 'dpick', "Destroy: the picker (something to suggest)", L.eval('doc().mode'))
ids = lst(L.eval('listIds()'))
check(ids == [602, 601, 603, 604, 607, 606, 614], "Suggested: greys by value, worse / unusable gear by value, whites by value; "
      "then the cursor's own item", ids)
secs = lst(L.eval('listSec()'))
check(secs == ['s'] * 6 + ['i'], "the cursor's item (a green trade good, never suggested) is 'This item'", secs)
check(L.eval('doc().dpick.sel') == 7, "the gold row starts on the cursor's own item", L.eval('doc().dpick.sel'))
for bad, why in [(605, "boots for an empty slot (a gain)"), (608, "a quest item"), (609, "an item a quest needs"),
                 (6948, "the hearthstone"), (611, "a key"), (612, "rare"), (613, "a fishing pole")]:
    check(bad not in ids, "never suggested: " + why)
check(L.eval('doc().dpick.list[1].v') == 1 and L.eval('doc().dpick.list[6].v') == 20, "each row carries its stack's value")

# 4. select + A = that item's confirm; B = back to the picker; Yes = that item, ONE delete through WC.bagsDestroy
L.execute('press("PADDUP"); press("PADDUP")')
check(L.eval('doc().dpick.sel') == 5, "up / down move the gold row", L.eval('doc().dpick.sel'))
L.execute('press("PAD1")')
check(L.eval('doc().mode') == 'destroy' and L.eval('doc().dest.slot') == 7, "A: the confirm, about the chosen item (dest)",
      (L.eval('doc().mode'), L.eval('doc().dest and doc().dest.slot')))
L.execute('press("PAD2")')
check(L.eval('doc().mode') == 'dpick' and L.eval('doc().dpick.sel') == 5 and L.eval('deletes()') == 0,
      "No (B): back to the picker, the same row, nothing deleted", L.eval('doc().mode'))
L.execute('press("PAD1"); PK = #PICKS; press("PAD1")')
check(L.eval('deletes()') == 1 and L.eval('lastDelete()') == 607 and L.eval('PICKS[PK + 1]') == '0:7',
      "Yes (A): that item picked up and deleted, once", (L.eval('deletes()'), L.eval('lastDelete()')))
check(L.eval('doc().mode') == 'grid' and L.eval('doc().note') == 'destroying', "noted destroying until the bag update",
      L.eval('doc().note'))
L.execute('fire("BAG_UPDATE_DELAYED")')
check(L.eval('doc().note') == 'destroying', "a bag update in the same instant is not its answer", L.eval('doc().note'))
L.execute('NOW = NOW + 0.1; fire("BAG_UPDATE_DELAYED"); run(0)')
check(L.eval('doc().note') == 'destroyed', "the next bag update: the slot is empty, destroyed", L.eval('doc().note'))
conf = [e for e in L.eval('EMITS').values() if e.type == 'cap' and e.bagsDestroy is not None and e.bagsDestroy.confirm is not None]
check(len(conf) == 1 and conf[-1].bagsDestroy.confirm is True, "one confirm message, true", len(conf))
L.execute('run(2)')
check(L.eval('deletes()') == 1, "nothing more deleted later (one item per press)")

# 5. a finger on a picker row: the gold row there (36 + its bmv cell); the same row again is the app's A
L.execute('press("PAD4"); WowCompanion._bagsTouch(21); run(0)')
check(L.eval('doc().mode') == 'dpick', "the picker again")
L.execute('WowCompanion._bagsTouch(36); WowCompanion._bagsCursorTap(0, 3); run(0)')
check(L.eval('doc().dpick.list[doc().dpick.sel].slot') == 3, "a finger on the chest's row: the gold row there",
      L.eval('doc().dpick.sel'))
L.execute('press("PAD2")')
check(L.eval('doc().mode') == 'grid' and L.eval('deletes()') == 1, "B: back to the grid, nothing deleted")

# 6. guards: a spell on the cursor refuses; a fight refuses; rare and better = the box, nothing deleted
L.execute('to(1); press("PAD4"); WowCompanion._bagsTouch(21); run(0)')     # the picker: slot 1 (a grey) is suggested
L.execute('while doc().dpick.list[doc().dpick.sel].slot ~= 1 do press("PADDUP") end; press("PAD1")')
check(L.eval('doc().mode') == 'destroy', "a grey's confirm")
L.execute('HAND.c = { kind = "spell" }; press("PAD1"); HAND.c = nil')
check(L.eval('deletes()') == 1 and L.eval('doc().note') == 'cursor' and L.eval('itemAt(0, 1)') == 601,
      "a spell on the cursor (GetCursorInfo): refused, the item still there", L.eval('doc().note'))
L.execute('press("PAD4"); COMBAT = true; WowCompanion._bagsTouch(21); run(0)')
check(L.eval('doc().mode') == 'menu' and L.eval('deletes()') == 1, "a finger on Destroy in a fight: the greyed row does nothing")
L.execute('SPEC.press("PAD1"); run(0)')                 # the menu's cursor is on its last usable row: never Destroy
check(L.eval('deletes()') == 1, "in a fight: nothing deleted")
L.execute('COMBAT = false; if doc().mode ~= "grid" then press("PAD2") end; to(12); press("PAD4"); WowCompanion._bagsTouch(21); run(0)')
check(L.eval('doc().mode') == 'dpick' and L.eval('doc().dpick.list[doc().dpick.sel].id') == 612, "a rare item: 'This item' in the picker")
L.execute('press("PAD1")')
check(L.eval('doc().mode') == 'destroy' and L.eval('doc().rare') is True, "its confirm is the box that says no")
L.execute('press("PAD1")')
check(L.eval('doc().mode') == 'dpick' and L.eval('deletes()') == 1 and L.eval('itemAt(0, 12)') == 612,
      "Okay: back to the picker, nothing deleted")
L.execute('press("PAD2")')

# 7. nothing to suggest: Destroy is the confirm at once, as before
L.execute('SAVED = BAG[0]; BAG[0] = { 614, 612 }; WowCompanion._bagsLayout = WowCompanion.collectBags().bags; to(1); press("PAD4"); WowCompanion._bagsTouch(21); run(0)')
check(L.eval('doc().mode') == 'destroy' and L.eval('doc().dest.slot') == 1, "nothing suggested: the confirm at once", L.eval('doc().mode'))
L.execute('press("PAD1")')
check(L.eval('deletes()') == 2 and L.eval('lastDelete()') == 614, "Yes: that item deleted")
L.execute('NOW = NOW + 0.1; fire("BAG_UPDATE_DELAYED"); run(0); BAG[0] = SAVED; WowCompanion._bagsLayout = WowCompanion.collectBags().bags')

# 8. a value still loading goes last in its group, and is re-ranked when it loads
L.execute('BAG[0][7] = 607; LOADING[606] = true; to(14); press("PAD4"); WowCompanion._bagsTouch(21); run(0)')
ids = lst(L.eval('listIds()'))
check(ids[:6] == [602, 601, 603, 604, 607, 606] and L.eval('doc().dpick.list[6].v') is None, "loading: last of the whites, no value",
      ids)
L.execute('LOADING[606] = nil; ITEMS[606].v = 1; fire("GET_ITEM_INFO_RECEIVED", 606, true); run(0)')
ids = lst(L.eval('listIds()'))
check(ids[:6] == [602, 601, 603, 604, 606, 607], "loaded (2 c): ranked again among the whites", ids)
L.execute('press("PAD2")')

check(int(L.eval('#TRACE')) == 0, "no trace: no StaticPopup / SetBinding / SaveBindings / macro / CVar / window call",
      list(L.eval('TRACE').values()))
print('ALL PASS (%d checks)' % passes if fails == 0 else '%d FAILED (of %d)' % (fails, fails + passes))
sys.exit(1 if fails else 0)
