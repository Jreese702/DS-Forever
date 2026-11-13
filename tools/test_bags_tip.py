"""bags-tip1 (the user, 2026-11-13: "When looking at items in bags it sometimes feels really laggy and the tool tip shows one thing for a
half second and has to load the rest and reloads the tool tip. I want it to feel fast and load that tool tip as its full description
each time"; E:/wow-build/bags_design_1113.md section 1, fixes 2, 4, 5 and 6). Loads the real WowCompanion/Bags.lua in Lua 5.1 with
stand-in game functions (a clock and timers that run) and drives the room's controller to check:
  - the first item's tooltip is read after the rest (0.12 s) and sent once; meanwhile the slots around the cursor are READ AHEAD
    (2 a tick, out of a fight) and nothing is sent for them;
  - a press onto a slot read ahead carries its FULL tooltip in that same message (no rest, no second send);
  - a bags collect (BAG_UPDATE_DELAYED, the heartbeat) keeps the tooltip: the full document's ctl has it (was: dropped every time);
  - an item whose link changed (same id) is read again, never served stale; an item gone from its slot is forgotten;
  - PLAYER_EQUIPMENT_CHANGED forgets them all (the compare depends on what is worn);
  - a tooltip still loading ("Retrieving item information", or none) is sent marked loading and never kept; ITEM_DATA_LOAD_RESULT /
    TOOLTIP_DATA_UPDATE read it again, at most TRIES times; the complete one is then kept and sent;
  - a kept tooltip older than FRESH is sent at once and read again behind it; the same text sends nothing more;
  - in a fight nothing is read ahead, the cursor's own item still is;
  - the 19 worn tooltips are read once, then only when worn gear changes (a slot's link, UPDATE_INVENTORY_DURABILITY,
    PLAYER_EQUIPMENT_CHANGED), not at every collect;
  - no trace: no SetBinding / SaveBindings / macro / CVar call.
Run: python -B tools/test_bags_tip.py
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
            After = function(s, fn) TIMERS[#TIMERS + 1] = { at = NOW + s, fn = fn, n = #TIMERS } end }
-- run every timer due by NOW + dt, in time order (timers set while running run too when due)
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
-- a plain encoder: sorted keys, so equal tables encode equal
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
                     "CloseAllBags", "OpenAllBags", "ToggleBackpack" }) do
    _G[n] = function() TRACE[#TRACE + 1] = n end
end
function GetNumMacros() return 0, 0 end
function SetOverrideBindingClick() end
function SetOverrideBinding() end
function GetBindingAction() return "" end
-- the backpack: 8 slots, items in 1..6
BAG = { [0] = { [1] = 501, [2] = 502, [3] = 503, [4] = 504, [5] = 505, [6] = 506 } }
LINK = {}
function linkOf(b, s) local id = BAG[b] and BAG[b][s]; if id then return LINK[b .. ":" .. s] or ("|Hitem:" .. id .. "|h") end end
C_Container = {
    GetContainerItemID = function(b, s) return BAG[b] and BAG[b][s] end,
    GetContainerNumSlots = function(b) return b == 0 and 8 or 0 end,
    GetContainerNumFreeSlots = function(b) return 2, 0 end,
    GetContainerItemInfo = function(b, s) local id = BAG[b] and BAG[b][s]; if id then return { itemID = id, iconFileID = 1, quality = 1, stackCount = 1, hyperlink = linkOf(b, s) } end end,
    GetContainerItemLink = function(b, s) return linkOf(b, s) end,
    GetContainerItemQuestInfo = function() return {} end,
}
C_Item = { GetItemNameByID = function(id) return "Item " .. id end, GetItemInfoInstant = function(id) return id, "Misc", "Junk", "", 1, 15, 0 end,
           GetItemSpell = function() return nil end, RequestLoadItemDataByID = function() end }
RETRIEVING_ITEM_INFO = "Retrieving item information"
-- Interact's tooltip reader, counted per key; TIPMODE[key] = "loading" / "none" for a tooltip the game has not finished
TIPREADS, TIPMODE, TIPTEXT = {}, {}, {}
WowCompanion.tipOf = function(key)
    TIPREADS[key] = (TIPREADS[key] or 0) + 1
    local m = TIPMODE[key]
    if m == "none" then return { key = key, none = true } end
    if m == "loading" then return { key = key, lines = { { t = RETRIEVING_ITEM_INFO, c = "ff2020" } } } end
    return { key = key, lines = { { t = "Full " .. key, c = "ffffff" }, { t = TIPTEXT[key] or "Use: does a thing", c = "00ff00" } } }
end
function reads(key) return TIPREADS[key] or 0 end
function allReads() local n = 0; for _, v in pairs(TIPREADS) do n = n + v end; return n end
-- worn gear: the chest and the main hand
WORN = { [5] = 900, [16] = 901 }
WORNREADS = 0
function GetInventoryItemID(unit, slot) return WORN[slot] end
function GetInventoryItemLink(unit, slot) if WORN[slot] then return LINK["inv" .. slot] or ("|Hitem:" .. WORN[slot] .. "|h") end end
function GetWeaponEnchantInfo() return WEPENCH or false, 0, 0, WEPENCH and 1 or 0, false, 0, 0, 0 end
C_TooltipInfo = { GetInventoryItem = function(unit, slot) WORNREADS = WORNREADS + 1; return { lines = { { leftText = "Worn " .. slot } } } end }
for _, n in ipairs({ "MerchantFrame", "GossipFrame", "QuestFrame", "BankFrame", "MailFrame", "TradeFrame", "AuctionHouseFrame",
                     "GuildBankFrame", "ClassTrainerFrame", "CharacterFrame" }) do _G[n] = { IsShown = function() return false end } end
SPEC = nil
WowCompanion.capture = { version = 11, take = function(_, spec) SPEC = spec; return true end, pass = function() return true end,
                         release = function() return true end, state = function() return { holder = "bags" } end,
                         chord = function() return true end, refused = function() return false end }
function press(k) SPEC.press(k) end
function doc() return WowCompanion._bagsCtlDoc() end
function stats() return WowCompanion._bagsTipStats() end
function lastEmit() local e = EMITS[#EMITS]; if e and e.type == "full" then return { type = "full", ctl = e.bags.ctl, bags = e.bags } end; return e end
function emitsN() return #EMITS end
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
check(L.eval('doc().cur.slot') == 1, "the cursor on slot 1", L.eval('doc().cur.slot'))

# 1. the first item: read after the rest, sent once; the slots around it read ahead, nothing sent for them
check(L.eval('doc().tip') is None, "nothing read yet: no tooltip in the first message (the app draws its stand-in)")
L.execute('E0 = emitsN(); run(0.13)')
check(L.eval('reads("bag 0 1 501")') == 1, "slot 1 read once after the rest (0.12 s)", L.eval('reads("bag 0 1 501")'))
e = L.eval('lastEmit()')
check(e is not None and e.type == 'bagsctl' and e.ctl.tip is not None and e.ctl.tip.key == 'bag 0 1 501' and e.ctl.tip.loading is None,
      "slot 1's full tooltip sent (one bagsctl)", e and (e.type, e.ctl.tip and e.ctl.tip.key))
L.execute('E1 = emitsN(); run(0.6)')
check(L.eval('reads("bag 0 2 502")') == 1 and L.eval('reads("bag 0 3 503")') == 1 and L.eval('reads("bag 0 6 506")') == 1,
      "the slots around the cursor read ahead", [L.eval('reads("bag 0 %d %d")' % (s, 500 + s)) for s in range(1, 7)])
check(L.eval('stats().n') == 6 and L.eval('reads("bag 0 1 501")') == 1, "six kept, slot 1 not read again", L.eval('stats().n'))
check(L.eval('emitsN()') == L.eval('E1'), "nothing sent for the reads ahead", L.eval('emitsN() - E1'))
check(L.eval('allReads()') == 6, "each slot read once in all (empty slots 7 and 8 never)", L.eval('allReads()'))

# 2. a press onto a slot read ahead: its full tooltip in that very message, no rest, no second send
L.execute('E2 = emitsN(); press("PADDRIGHT"); run(0)')
e = L.eval('lastEmit()')
check(L.eval('emitsN() - E2') == 1 and e.type == 'bagsctl' and e.ctl.cur.slot == 2 and e.ctl.tip and e.ctl.tip.key == 'bag 0 2 502',
      "PADDRIGHT: the cursor's message carries slot 2's FULL tooltip", e and (e.type, e.ctl.tip and e.ctl.tip.key))
L.execute('E3 = emitsN(); run(0.5)')
check(L.eval('emitsN()') == L.eval('E3') and L.eval('reads("bag 0 2 502")') == 1, "no second send, no second read for slot 2",
      (L.eval('emitsN() - E3'), L.eval('reads("bag 0 2 502")')))

# 3. a bags collect keeps it (was: forgotten at every collect, the card fell back to its stand-in)
L.execute('BAG[0][7] = 507; fire("BAG_UPDATE_DELAYED"); run(0)')
e = L.eval('lastEmit()')
check(e.type == 'full' and e.bags.ctl.tip is not None and e.bags.ctl.tip.key == 'bag 0 2 502' and e.bags.ctl.tip.loading is None,
      "the full document after a loot still carries slot 2's tooltip", e.type)
check(L.eval('reads("bag 0 2 502")') == 1 and L.eval('stats().n') >= 6, "nothing read again for the collect", L.eval('reads("bag 0 2 502")'))
L.execute('run(0.5)')
check(L.eval('reads("bag 0 7 507")') == 1, "the new item is read ahead too", L.eval('reads("bag 0 7 507")'))

# 4. a changed link (same id: an enchant) is read again, never stale; an item gone is forgotten
L.execute('LINK["0:2"] = "|Hitem:502:1|h"; TIPTEXT["bag 0 2 502"] = "Enchanted"; E4 = emitsN()')
check(L.eval('doc().tip') is None or L.eval('doc().tip.lines[2].t') != 'Use: does a thing', "the old text is not served for a changed link")
L.execute('run(0.13); run(0)')
check(L.eval('reads("bag 0 2 502")') == 2 and L.eval('lastEmit().ctl.tip.lines[2].t') == 'Enchanted', "read again and sent",
      L.eval('reads("bag 0 2 502")'))
L.execute('N4 = stats().n; BAG[0][6] = nil; fire("BAG_UPDATE_DELAYED"); run(0)')
check(L.eval('stats().n') == L.eval('N4') - 1, "slot 6 emptied: its tooltip forgotten", (L.eval('N4'), L.eval('stats().n')))

# 5. what is worn changed: all forgotten (the compare), the cursor's read again
L.execute('fire("PLAYER_EQUIPMENT_CHANGED")')
check(L.eval('stats().n') == 0, "PLAYER_EQUIPMENT_CHANGED forgets them all", L.eval('stats().n'))
L.execute('run(0); run(0.13); run(0)')
check(L.eval('reads("bag 0 2 502")') == 3 and L.eval('lastEmit().ctl.tip.key') == 'bag 0 2 502', "the cursor's item read again and sent")

# 6. loading: sent marked loading, never kept; the game's load event reads it again; complete = kept and sent
L.execute('TIPMODE["bag 0 3 503"] = "loading"; fire("PLAYER_EQUIPMENT_CHANGED"); run(0); press("PADDRIGHT"); run(0); run(0.13); run(0)')
e = L.eval('lastEmit()')
check(e.ctl.cur.slot == 3 and e.ctl.tip and e.ctl.tip.loading is True, "a loading tooltip is sent marked loading", e.ctl.tip and e.ctl.tip.loading)
check(L.eval('stats().loading') == 'bag 0 3 503', "... and never kept", L.eval('stats().loading'))
L.execute('R6 = reads("bag 0 3 503"); fire("TOOLTIP_DATA_UPDATE"); fire("TOOLTIP_DATA_UPDATE"); run(0)')
check(L.eval('reads("bag 0 3 503")') == L.eval('R6') + 1, "two load events in a frame: one read again", L.eval('reads("bag 0 3 503") - R6'))
L.execute('TIPMODE["bag 0 3 503"] = nil; fire("ITEM_DATA_LOAD_RESULT", 503, true); run(0); run(0)')
e = L.eval('lastEmit()')
check(e.ctl.tip and e.ctl.tip.loading is None and e.ctl.tip.lines[1].t == 'Full bag 0 3 503', "loaded: the full one sent")
check(L.eval('stats().loading') is None and L.eval('doc().tip.loading') is None, "... and kept")
L.execute('R6b = reads("bag 0 3 503"); fire("ITEM_DATA_LOAD_RESULT", 503, true); run(0)')
check(L.eval('reads("bag 0 3 503")') == L.eval('R6b'), "a load event with nothing loading reads nothing")
# none is loading too, and the load events give up after TRIES
L.execute('TIPMODE["bag 0 4 504"] = "none"; fire("PLAYER_EQUIPMENT_CHANGED"); run(0); press("PADDRIGHT"); run(0); run(0.13); run(0)')
check(L.eval('lastEmit().ctl.tip.loading') is True, "none: sent as loading, not as a final empty card")
L.execute('R7 = reads("bag 0 4 504"); for i = 1, 12 do fire("TOOLTIP_DATA_UPDATE"); run(0) end')
check(L.eval('reads("bag 0 4 504")') - L.eval('R7') == 6, "the load events read it again at most 6 times", L.eval('reads("bag 0 4 504") - R7'))
L.execute('TIPMODE["bag 0 4 504"] = nil')

# 7. older than FRESH: sent at once, read again behind it; the same text sends nothing more
L.execute('press("PADDLEFT"); run(0); run(0.6); NOW = NOW + 9; R8 = reads("bag 0 3 503"); E8 = emitsN()')
check(L.eval('doc().tip') is not None and L.eval('doc().tip.key') == 'bag 0 3 503', "an old one is still sent at once")
L.execute('run(0.13); run(0)')
check(L.eval('reads("bag 0 3 503")') == L.eval('R8') + 1 and L.eval('emitsN()') == L.eval('E8'), "read again behind it; the same text: nothing sent",
      (L.eval('reads("bag 0 3 503") - R8'), L.eval('emitsN() - E8')))

# 8. a fight: nothing read ahead; the cursor's own item still is
L.execute('fire("PLAYER_EQUIPMENT_CHANGED"); run(0); COMBAT = true; A9 = allReads(); press("PADDLEFT"); run(0); run(1.0)')
check(L.eval('allReads()') == L.eval('A9') + 1 and L.eval('reads("bag 0 2 502")') >= 1 and L.eval('lastEmit().ctl.tip.key') == 'bag 0 2 502',
      "in a fight: only the cursor's item read (and sent)", L.eval('allReads() - A9'))
L.execute('COMBAT = false; press("PADDRIGHT"); run(0); run(1.0)')
check(L.eval('stats().n') >= 5, "the fight over: the reads ahead go on", L.eval('stats().n'))

# 9. the worn tooltips: read once, then only when worn gear changes
L.execute('WowCompanion._bagsWornDirty(); WowCompanion.collectEquipped(); W0 = WORNREADS')
L.execute('WowCompanion.collectEquipped(); WowCompanion.collectEquipped(); fire("BAG_UPDATE_DELAYED"); run(0)')
check(L.eval('WORNREADS') == L.eval('W0'), "collects that change no worn gear read no worn tooltip", L.eval('WORNREADS - W0'))
eq = L.eval('WowCompanion.collectEquipped()')
check(eq.slots[5].tip is not None and eq.slots[5].tip[1].t == 'Worn 5', "the kept worn tooltip still rides in the document")
L.execute('LINK.inv5 = "|Hitem:900:7|h"; W1 = WORNREADS; WowCompanion.collectEquipped()')
check(L.eval('WORNREADS') == L.eval('W1') + 1, "the chest's link changed: that slot alone read again", L.eval('WORNREADS - W1'))
L.execute('W2 = WORNREADS; WEPENCH = true; WowCompanion.collectEquipped()')
check(L.eval('WORNREADS') == L.eval('W2') + 1, "a weapon's temporary enchant: the main hand read again", L.eval('WORNREADS - W2'))
L.execute('W3 = WORNREADS; fire("UPDATE_INVENTORY_DURABILITY"); WowCompanion.collectEquipped()')
check(L.eval('WORNREADS') == L.eval('W3') + 2, "UPDATE_INVENTORY_DURABILITY: the worn ones read again", L.eval('WORNREADS - W3'))
L.execute('W4 = WORNREADS; NOW = NOW + 61; WowCompanion.collectEquipped(); WowCompanion.collectEquipped()')
check(L.eval('WORNREADS') == L.eval('W4') + 2, "after a minute: read again once (timed text)", L.eval('WORNREADS - W4'))

check(int(L.eval('#TRACE')) == 0, "no trace: no SetBinding / SaveBindings / macro / CVar / window call", list(L.eval('TRACE').values()))
print('ALL PASS (%d checks)' % passes if fails == 0 else '%d FAILED (of %d)' % (fails, fails + passes))
sys.exit(1 if fails else 0)
