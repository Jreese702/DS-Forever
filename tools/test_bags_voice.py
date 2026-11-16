"""bags-voice1 (the user, 2026-11-16: voice to text in the message box and ALL search boxes, "A opens the keyboard, X opens our own voice
prompt"; the pick: "Every box that takes words ... Bags search"): X on the Bags room's strip Search. Loads the real WowCompanion/Bags.lua in
Lua 5.1 with stand-in game functions (test_bags_actions.py's) and drives the room's controller and touch cells:
  - X on Search is the same request as A with the act named "voice" (a new seq), mode "search" while the app types; the app's close
    (bmvq 27 + 17) gives the presses back with the cursor still on Search;
  - A on Search is unchanged (act "search"); a finger on the box's microphone is the strip's touch X (bmvq 22): the voice page;
  - X on Clean Up or a bag does nothing; X on the grid is still the item's own X;
  - no trace: no game call at all.
Run: python -B tools/test_bags_voice.py
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


# ---------------------------------------------------------------- bags-voice1: X on the strip's Search
L.execute('to(1); press("PADDUP")')                         # the grid's top row: up onto the strip
check(L.eval('doc().bar ~= nil'), "up from the top row: the cursor on the strip")
L.execute('for _ = 1, 12 do if doc().bar and doc().bar.k == "search" then break end; press("PADDRIGHT") end')
check(L.eval('doc().bar and doc().bar.k') == "search", "the strip's last item is Search", L.eval('doc().bar and doc().bar.k'))
seq0 = L.eval('doc().act and doc().act.seq or 0')
L.execute('T0 = #TRACE; press("PAD3")')
check(L.eval('doc().act and doc().act.k') == "voice", "X on Search asks for the voice page (act voice)", L.eval('doc().act and doc().act.k'))
check(L.eval('doc().act.seq') > seq0, "a new request (its own seq)")
check(L.eval('doc().mode') == "search", "Bags stands still while the app types (mode search, as A)", L.eval('doc().mode'))
check(L.eval('#TRACE == T0'), "no game call (no trace)", L.eval('#TRACE'))
# the keyboard closes (the app's bmvq 27 + col 17): Bags' presses again, the cursor still on Search
L.execute('WowCompanion._bagsTouch(27); WowCompanion._bagsShutTap(17); run(0)')
check(L.eval('doc().mode') == "grid", "the app closed its keyboard: the grid's presses again", L.eval('doc().mode'))
check(L.eval('doc().bar and doc().bar.k') == "search", "the cursor still on Search")
# A on Search: the keyboard as before
seq1 = L.eval('doc().act.seq')
L.execute('press("PAD1")')
check(L.eval('doc().act.k') == "search" and L.eval('doc().act.seq') > seq1, "A on Search: the keyboard (act search), unchanged")
L.execute('WowCompanion._bagsTouch(27); WowCompanion._bagsShutTap(17); run(0)')
# a finger on the box's microphone: the strip's touch X (bmvq 22)
seq2 = L.eval('doc().act.seq')
L.execute('WowCompanion._bagsTouch(22); run(0)')
check(L.eval('doc().act.k') == "voice" and L.eval('doc().act.seq') > seq2, "the box's microphone (bmvq 22) = X: the voice page",
      L.eval('doc().act.k'))
L.execute('WowCompanion._bagsTouch(27); WowCompanion._bagsShutTap(17); run(0)')
# X on a bag or Clean Up does nothing (the strip has no X there)
L.execute('press("PADDLEFT")')
check(L.eval('doc().bar.k') == "sort", "left: Clean Up")
seq3 = L.eval('doc().act.seq')
L.execute('press("PAD3")')
check(L.eval('doc().act.seq') == seq3 and L.eval('doc().mode') == "grid", "X on Clean Up: nothing (no request, no mode)")
L.execute('press("PADDLEFT")')
check(L.eval('doc().bar.k') == "bag", "left again: a bag")
L.execute('press("PAD3")')
check(L.eval('doc().act.seq') == seq3 and L.eval('doc().mode') == "grid", "X on a bag: nothing")
# X on the grid is still Use (not voice)
L.execute('press("PADDDOWN")')
check(L.eval('doc().bar') is None, "down: back on the grid")
L.execute('to(1); press("PAD3")')
check(L.eval('doc().act.seq') == seq3, "X on the grid: no voice request (the item's own X)")
check(L.eval('#TRACE') == 0, "no trace anywhere: no SetBinding / macro / CVar / window call", L.eval('#TRACE'))
# the header says so, quoting the user
src_ok = 'bags-voice1 (the user, 2026-11-16' in SRC and '"A opens the keyboard, X opens our own voice' in SRC
check(src_ok, "Bags.lua's header note for bags-voice1 quotes the user")

print("%d passed, %d failed" % (passes, fails))
sys.exit(1 if fails else 0)
