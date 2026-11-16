"""bags-open1 (wk-containers; the user, 2026-11-16: "opening containers from my bags is a little cluncky, there is not a button although
weirdly x does work to open them, it opens the loot menu back in the home room, it should just open the loot menu inside of the bags and
then keep the player inside of the bag room."). Loads the real WowCompanion/Bags.lua in Lua 5.1 with stand-in game functions and checks:
  - a bag item that opens says so (row.opens): the game's hasLoot, or its tooltip's ITEM_OPENABLE line; a box the game says is LOCKED
    (its tooltip's LOCKED line) says lockedBox and never opens, whatever hasLoot says; ordinary items, junk and usable items say neither,
    and only boxes (hasLoot, or a Miscellaneous item with no other action) have their tooltip read;
  - X on a Locked box is refused ("locked", no use request: the game is never asked); X on an openable box is the usual use request
    (the slot's own secure cell, the game's right-click);
  - capture v21: the take's spec carries lootUnder / resumed (not for an older capture); lootUnder answers true only for a loot window
    right after a click of one of our bag cells (its PreClick), once per click, within OPEN.TTL, out of a fight, while Bags holds;
  - while the loot window is over the room the room's finger cells do nothing; resumed ("loot-resumed") gives them back on the same slot;
    a lost controller ends it too;
  - read-only: no binding / macro / CVar / window call.
Interact's side (the suspension itself) is tools/interact_tests/t_lootunder.lua; the static checks at the end tie the two files together.
Run: python -B tools/test_bags_open.py
"""
import os
import re
import sys
from lupa import lua51

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ADDONS = os.path.join(ROOT, 'companion', 'app', 'src', 'main', 'assets', 'companion', 'addons')
SRC = open(os.path.join(ADDONS, 'WowCompanion', 'Bags.lua'), encoding='utf-8').read()
ISRC = open(os.path.join(ADDONS, 'WowCompanionInteract', 'Interact.lua'), encoding='utf-8').read()
passes = fails = 0


def check(ok, what, got=None):
    global passes, fails
    if ok:
        passes += 1; print('PASS', what)
    else:
        fails += 1; print('FAIL', what, '->', got)


STUB = r'''
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
ROWS = {}
WowCompanion = {
    S = function(fn) local ok, v = pcall(fn); if ok then return v end end,
    SN = function(fn) local ok, v = pcall(fn); if ok and type(v) == "number" then return v end end,
    CALL = function(fn) return pcall(fn) end,
    emit = function() end, send = function() end, encode = function() return "" end, push = function() end,
    isPlain = function() return true end,
    pad = { secureRow = function(name, n, build, post) ROWS[name] = { n = n, build = build, post = post }; return 1 end,
            op = function() end, describe = function() return nil end },
}
COMBAT = false
function InCombatLockdown() return COMBAT end
function UnitName() return "Shamy" end
function GetRealmName() return "Forever" end
TRACE = {}
for _, n in ipairs({ "SetBinding", "SaveBindings", "DeleteMacro", "CreateMacro", "EditMacro", "SetCVar", "ShowUIPanel", "HideUIPanel",
                     "CloseAllBags", "OpenAllBags", "ToggleBackpack", "UseContainerItem", "PickupContainerItem", "StaticPopup_Show",
                     "CloseLoot", "LootSlot" }) do
    _G[n] = function() TRACE[#TRACE + 1] = n end
end
function GetNumMacros() return 0, 0 end
function GetCursorInfo() return nil end
function CursorHasItem() return false end
LOCKED = "Locked"
ITEM_OPENABLE = "<Right Click to Open>"
-- the backpack: 1 a clam (the game's hasLoot), 2 a LOCKED lockbox, 3 a lockbox picked open (its tooltip's open line, hasLoot not given),
-- 4 a lockbox the game says has loot but still LOCKED, 5 cloth (Trade Goods), 6 grey junk, 7 a potion (its "Use:"), 8 a box in a bag
-- that the game says has loot (no tooltip line at all)
ITEMS = {
    [701] = { cls = 15, q = 1, loot = true, tip = { "Small Barnacled Clam", "<Right Click to Open>" } },
    [702] = { cls = 15, q = 1, tip = { "Ornate Bronze Lockbox", "Locked", "Requires Lockpicking (25)" } },
    [703] = { cls = 15, q = 1, tip = { "Ornate Bronze Lockbox", "  <Right Click to Open>  " } },
    [704] = { cls = 15, q = 1, loot = true, tip = { "Heavy Bronze Lockbox", "Locked" } },
    [705] = { cls = 7, q = 1, tip = { "Linen Cloth" } },
    [706] = { cls = 15, q = 0, tip = { "Broken Fang", "Locked" } },
    [707] = { cls = 0, q = 1, use = true, tip = { "Minor Healing Potion", "Use: Restores 80 health." } },
    [708] = { cls = 15, q = 1, loot = true, tip = { "Bag of Loot" } },
}
BAG = { [0] = { 701, 702, 703, 704, 705, 706, 707, 708 } }
function itemAt(b, s) return BAG[b] and BAG[b][s] end
TIPREADS = {}
C_TooltipInfo = { GetBagItem = function(b, s)
    TIPREADS[#TIPREADS + 1] = b .. ":" .. s
    local it = ITEMS[itemAt(b, s) or 0]; if not it then return nil end
    local lines = {}
    for i, t in ipairs(it.tip) do lines[i] = { leftText = t, leftColor = { r = 1, g = 1, b = 1 } } end
    return { lines = lines }
end }
C_Container = {
    GetContainerItemID = function(b, s) return itemAt(b, s) end,
    GetContainerNumSlots = function(b) return b == 0 and 8 or 0 end,
    GetContainerNumFreeSlots = function(b) return 0, 0 end,
    GetContainerItemInfo = function(b, s) local id = itemAt(b, s); if id then local it = ITEMS[id]
        return { itemID = id, iconFileID = 1, quality = it.q, stackCount = 1, hyperlink = "|Hitem:" .. id .. "|h",
                 hasLoot = it.loot == true, isLocked = false, isReadable = false } end end,
    GetContainerItemLink = function(b, s) local id = itemAt(b, s); if id then return "|Hitem:" .. id .. "|h" end end,
    GetContainerItemQuestInfo = function() return {} end,
}
local function idOf(x) if type(x) == "number" then return x end; return tonumber(tostring(x):match("item:(%d+)")) end
C_Item = {
    GetItemNameByID = function(id) return "Item " .. id end,
    GetItemInfoInstant = function(x) local id = idOf(x); local it = ITEMS[id] or {}
        return id, "Miscellaneous", "Junk", "INVTYPE_NON_EQUIP_IGNORE", 1, it.cls or 15, 0 end,
    GetItemInfo = function(x) local id = idOf(x); return "Item " .. id, "|Hitem:" .. id .. "|h", 1, 1, 1, "Misc", "Junk", 20, "", 1, 1 end,
    GetItemSpell = function(id) if (ITEMS[id] or {}).use then return "Healing Potion", 1 end end,
    RequestLoadItemDataByID = function() end, GetItemStats = function() return {} end,
}
function GetInventoryItemID() return nil end
function GetInventoryItemLink() return nil end
for _, n in ipairs({ "MerchantFrame", "GossipFrame", "QuestFrame", "BankFrame", "MailFrame", "TradeFrame", "AuctionHouseFrame",
                     "GuildBankFrame", "ClassTrainerFrame", "CharacterFrame" }) do _G[n] = { IsShown = function() return false end } end
SPEC = nil
CAPV = CAPV or 21
WowCompanion.capture = { version = CAPV, take = function(_, spec) SPEC = spec; return true end, pass = function() return true end,
                         release = function() return true end, state = function() return { holder = "bags" } end,
                         chord = function() return true end, refused = function() return false end }
function press(k) SPEC.press(k); run(0) end
function doc() return WowCompanion._bagsCtlDoc() end
function to(slot) local d = doc(); for _ = 1, 20 do if d.cur.slot == slot then return end; press(d.cur.slot < slot and "PADDRIGHT" or "PADDLEFT"); d = doc() end end
function rowOf(d, slot) for _, it in ipairs(d.items or {}) do if it.bag == 0 and it.slot == slot then return it end end end
-- one of our secure bag cells, as TapPad builds it, and a click of it (its PreClick hooks, then the game's action)
CELL = {}
function cell(bag, col)
    local key = bag .. ":" .. col
    if CELL[key] then return CELL[key] end
    local btn = { attrs = {}, hooks = {} }
    function btn:SetAttribute(k, v) self.attrs[k] = v end
    function btn:GetAttribute(k) return self.attrs[k] end
    function btn:HookScript(ev, fn) self.hooks[ev] = self.hooks[ev] or {}; table.insert(self.hooks[ev], fn) end
    ROWS["bag" .. bag].build(btn, col)
    CELL[key] = btn
    return btn
end
function click(bag, slot)
    local btn = cell(bag, slot - 1)
    for _, fn in ipairs(btn.hooks.PreClick or {}) do fn(btn) end
    for _, fn in ipairs(btn.hooks.PostClick or {}) do fn(btn) end
end
'''


def runtime(capv):
    L = lua51.LuaRuntime(unpack_returned_tuples=True)
    L.execute('CAPV = %d' % capv)
    L.execute(STUB)
    load = L.eval('function(src) local f, e = loadstring(src, "@Bags.lua"); if not f then error(e) end; return f end')
    ok = True
    try:
        load(SRC)()
    except Exception as e:  # noqa: BLE001
        ok = False; print(e)
    return L, ok


L, ok_load = runtime(21)
check(ok_load, "Bags.lua loads (stand-in game) with no error")
L.execute('WowCompanionDB = {}; fire("PLAYER_LOGIN"); DOC = WowCompanion.collectBags(); WowCompanion._bagsLayout = DOC.bags')


def row(slot):
    return L.eval('rowOf(DOC, %d)' % slot)


r = [row(s) for s in range(1, 9)]
check(all(x is not None for x in r), "every slot's row is in the document")
if all(x is not None for x in r):
    check(r[0].opens is True and r[0].lockedBox is None, "a clam (the game's hasLoot): opens", (r[0].opens, r[0].lockedBox))
    check(r[1].lockedBox is True and r[1].opens is None, "a LOCKED lockbox (its tooltip's Locked): lockedBox, never opens",
          (r[1].lockedBox, r[1].opens))
    check(r[2].opens is True and r[2].lockedBox is None, "a lockbox picked open (its tooltip's open line, no hasLoot): opens",
          (r[2].opens, r[2].lockedBox))
    check(r[3].lockedBox is True and r[3].opens is None, "hasLoot but the tooltip says Locked: Locked wins", (r[3].lockedBox, r[3].opens))
    check(r[4].opens is None and r[4].lockedBox is None, "cloth: neither", (r[4].opens, r[4].lockedBox))
    check(r[5].opens is None and r[5].lockedBox is None, "grey junk (even with a 'Locked' line): never read, neither",
          (r[5].opens, r[5].lockedBox))
    check(r[6].use is True and r[6].opens is None and r[6].lockedBox is None, "a potion: its Use as before, no Open", (r[6].use, r[6].opens))
    check(r[7].opens is True, "a bag of loot (hasLoot, no tooltip line): opens", r[7].opens)
reads = sorted(set(L.eval('TIPREADS')[i] for i in range(1, len(L.eval('TIPREADS')) + 1)))
check(reads == ['0:1', '0:2', '0:3', '0:4', '0:8'], "only boxes have their tooltip read at a collect", reads)

# the room takes the controller (the touch route): the v21 spec
L.execute('WowCompanion._bagsTouch(29); run(0)')
check(L.eval('doc().on') is True, "the room took the controller")
check(L.eval('type(SPEC.lootUnder) == "function" and type(SPEC.resumed) == "function"'),
      "capture v21: the take's spec has lootUnder and resumed")

# X on the Locked box: refused, never asked
L.execute('to(2); TIPREADS = {}; SEQ0 = doc().seq; press("PAD3")')
check(L.eval('doc().note') == "locked" and L.eval('doc().use') is None, "X on a Locked box: refused ('locked'), no use request",
      (L.eval('doc().note'), L.eval('doc().use')))
check(L.eval('#TIPREADS') >= 1, "... read fresh at the press", L.eval('#TIPREADS'))
# X on the clam: the usual use request (the app taps the slot's secure cell)
L.execute('to(1); press("PAD3")')
check(L.eval('doc().use ~= nil and doc().use.bag == 0 and doc().use.slot == 1') is True and L.eval('doc().note') == "use",
      "X on an openable box: the use request for its own secure cell, as before", L.eval('doc().note'))

# lootUnder: the room's own loot window only
check(L.eval('SPEC.lootUnder()') is False, "lootUnder: no click of ours yet -> false")
L.execute('click(0, 1)')
check(L.eval('SPEC.lootUnder()') is True and L.eval('doc().note') == "loot-under",
      "a loot window right after a click of our bag cell: the room's own ('loot-under')", L.eval('doc().note'))
check(L.eval('SPEC.lootUnder()') is False, "... once per click (a second loot window is not)")

# while the loot window is over the room: the room's finger cells do nothing; resumed gives them back on the same slot
L.execute('SLOT0 = doc().cur.slot; WowCompanion._bagsTouch(35); run(0)')
check(L.eval('doc().cur.slot') == L.eval('SLOT0') and L.eval('doc().on') is True,
      "while the loot window is over the room: a finger's D-pad cell does nothing, the room still on", L.eval('doc().cur.slot'))
L.execute('SPEC.resumed("loot"); run(0)')
check(L.eval('doc().note') == "loot-resumed" and L.eval('doc().on') is True and L.eval('doc().cur.slot') == L.eval('SLOT0'),
      "resumed: 'loot-resumed', the room on, the same slot", (L.eval('doc().note'), L.eval('doc().cur.slot')))
L.execute('WowCompanion._bagsTouch(35); run(0)')
check(L.eval('doc().cur.slot') == L.eval('SLOT0') + 1, "... and the finger cells act again", L.eval('doc().cur.slot'))

# the time limit, a fight, a lost controller
L.execute('click(0, 1); NOW = NOW + 6')
check(L.eval('SPEC.lootUnder()') is False, "a loot window more than OPEN.TTL after the click: not the room's")
L.execute('click(0, 1); COMBAT = true')
check(L.eval('SPEC.lootUnder()') is False, "in a fight: never (the capture keeps today's way too)")
L.execute('COMBAT = false; click(0, 1)')
check(L.eval('SPEC.lootUnder()') is True, "(under again)")
L.execute('SPEC.lost("npc"); run(0)')
check(L.eval('doc().on') is False and L.eval('doc().note') == "lost-npc", "a lost controller while under: off, as before",
      L.eval('doc().note'))
L.execute('WowCompanion._bagsTouch(29); run(0); WowCompanion._bagsTouch(34); run(0)')
check(L.eval('doc().on') is True and L.eval('doc().cur.slot') == L.eval('SLOT0'), "taken again: the finger cells act (no stale suspension)",
      L.eval('doc().cur.slot'))
check(L.eval('#TRACE') == 0, "read-only: no binding / macro / CVar / window / loot call", L.eval('TRACE[1]'))

# an older capture (v20): no lootUnder in the spec, the loot window goes as before
L2, ok2 = runtime(20)
L2.execute('WowCompanionDB = {}; fire("PLAYER_LOGIN"); WowCompanion._bagsLayout = WowCompanion.collectBags().bags; WowCompanion._bagsTouch(29); run(0)')
check(ok2 and L2.eval('SPEC ~= nil and SPEC.lootUnder == nil and SPEC.resumed == nil') is True,
      "an older capture (v20): the spec asks for nothing new")

# static ties between the two files
check("bags-open1 (wk-containers; the user, 2026-11-16: \"opening containers from my bags is a little cluncky" in SRC,
      "Bags.lua's header note quotes the user")
check('local IV = "i161"' in ISRC and "opening containers from my bags is a little cluncky" in ISRC,
      "Interact.lua is i161 with the user's words")
check(re.search(r'version = 21,', ISRC) is not None, "the capture says version 21")
m = re.search(r'if event == "PLAYER_REGEN_DISABLED" then(.*?)local who = G\.holder', ISRC, re.S)
check(m is not None and 'N.lootUnderDrop("npc")' in m.group(1), "a fight's start lets a suspended room go before anything else")
m = re.search(r'function N\.release\(why, who\)(.*?)\n    N\.unhide\(\)', ISRC, re.S)
check(m is not None and 'if G.under and G.under.loot then' in m.group(1) and 'if N.lootResume() then' in m.group(1)
      and 'N.resumeEat = byB' in m.group(1),
      "N.release resumes a room under its loot window (first; review of i161: marking the release of the B that closed it)")
check('if G.holder and not (slot == "loot" and N.lootUnder()) then' in ISRC, "N.early suspends instead of losing, only for loot")
print('passes %d, failed: %d' % (passes, fails))
sys.exit(1 if fails else 0)
