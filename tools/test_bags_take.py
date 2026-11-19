"""bags-take1 (the user, 2026-11-16, test build 1478: "read my DS my bag is behaving weird and opening the bag on top for some reason").
The top screen showed the GAME's Combined Backpack, the bottom our Bags room with no cursor, and the log said "note=take-skip-window"
three times with no NPC window up. Bags' open-window state (ctx, from MERCHANT_SHOW and the like) was cleared only by its matching close
event, and the take trusted ctx alone, so one close event the add-on never saw skipped every take after it. Loads the real
WowCompanion/Bags.lua in Lua 5.1 with stand-in game frames and checks:
  - a vendor visit whose MERCHANT_CLOSED never came (MerchantFrame hidden): the game's bags opening later ARE taken (fails on dbd79dee);
    the stale state is let go and said (ctxStale), and the room's buttons no longer say SELL;
  - a vendor really open: the take is still skipped, the note names the window ("take-skip-window:MerchantFrame"), and once the vendor
    window hides with the bags still open the take happens (the game's bag window never stays on top with the room holding nothing);
  - any other listed window shown (QuestFrame, e.g. kept at alpha 0): named, and taken on its OnHide; the close event retries too;
  - the open event a moment before its frame shows (the 1 s grace): still skipped ("ctx-merchant");
  - the bags closed while waiting, or the room left by touch: no take back;
  - bags-take2: a leave from an EARLIER visit (taken / free / B) does not stop a later visit's retry or 1 s check;
  - read-only: no binding / macro / CVar / window call.
Run: python -B tools/test_bags_take.py
"""
import os
import sys
from lupa import lua51

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ADDONS = os.path.join(ROOT, 'companion', 'app', 'src', 'main', 'assets', 'companion', 'addons')
SRC = open(os.path.join(ADDONS, 'WowCompanion', 'Bags.lua'), encoding='utf-8').read()
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
ContainerFrameCombinedBagsCloseButton = nil
NOW = 100
function GetTime() return NOW end
TIMERS = {}
TICKS = {}
C_Timer = { NewTicker = function(s, fn) TICKS[#TICKS + 1] = fn; return stub end, NewTimer = function() return stub end,
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
WIN = {}
local function win(n)
    local f = { shown = false, hooks = {} }
    function f:IsShown() return self.shown end
    function f:HookScript(ev, fn) self.hooks[ev] = self.hooks[ev] or {}; table.insert(self.hooks[ev], fn) end
    _G[n] = f; WIN[n] = f
end
for _, n in ipairs({ "MerchantFrame", "GossipFrame", "QuestFrame", "BankFrame", "MailFrame", "TradeFrame", "AuctionHouseFrame",
                     "GuildBankFrame", "ClassTrainerFrame", "CharacterFrame", "ContainerFrameCombinedBags" }) do win(n) end
for i = 1, 13 do win("ContainerFrame" .. i) end
function show(n) local f = WIN[n]; f.shown = true; for _, fn in ipairs(f.hooks.OnShow or {}) do fn(f) end; run(0) end
function hide(n) local f = WIN[n]; f.shown = false; for _, fn in ipairs(f.hooks.OnHide or {}) do fn(f) end; run(0) end
HOLDER = nil
SPEC = nil
CAPV = CAPV or 21
WowCompanion.capture = { version = CAPV, take = function(_, spec) SPEC = spec; TAKES = (TAKES or 0) + 1; return true end, pass = function() return true end,
                         release = function() return true end, state = function() return { holder = rawget(_G, "HOLDER") } end,
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


def runtime():
    L = lua51.LuaRuntime(unpack_returned_tuples=True)
    L.execute('CAPV = 21')
    L.execute(STUB)
    load = L.eval('function(src) local f, e = loadstring(src, "@Bags.lua"); if not f then error(e) end; return f end')
    load(SRC)()
    L.execute('WowCompanionDB = {}; fire("PLAYER_LOGIN"); WowCompanion._bagsLayout = WowCompanion.collectBags().bags; TAKES = 0; run(0)')
    return L


def on(L):
    return L.eval('doc().on') is True


def note(L):
    return L.eval('doc().note')


# 1. the report: a vendor visit whose close event never came, then (much later) the game's bags open
L = runtime()
L.execute('fire("MERCHANT_SHOW"); show("MerchantFrame"); hide("MerchantFrame"); NOW = NOW + 30')
L.execute('show("ContainerFrameCombinedBags")')
check(on(L), "a MERCHANT_SHOW with no MERCHANT_CLOSED (vendor window gone): the game's bags opening are TAKEN", note(L))
check(L.eval('TAKES') == 1, "... one take", L.eval('TAKES'))
L.execute('SPEC.lost("npc"); run(0)')
check(L.eval('doc().ctxStale') == "merchant", "the stale state is said once off (ctxStale = merchant)", L.eval('doc().ctxStale'))
L.execute('DOC = WowCompanion.collectBags()')
check(L.eval('DOC.ctx') is None, "the room's buttons no longer say SELL (bags doc ctx nil)", L.eval('DOC.ctx'))

# 2. a vendor really open: skipped, named; taken once the vendor window hides with the bags still open
L = runtime()
L.execute('fire("MERCHANT_SHOW"); show("MerchantFrame"); show("ContainerFrameCombinedBags")')
check(not on(L) and note(L) == "take-skip-window:MerchantFrame", "a vendor open: the take is skipped and names the window", note(L))
L.execute('DOC = WowCompanion.collectBags()')
check(L.eval('DOC.ctx') == "merchant", "... and the buttons still say SELL there", L.eval('DOC.ctx'))
L.execute('for _, t in ipairs(TICKS) do t() end; run(0)')
check(not on(L), "the 1 s check does not take while the vendor is up (holder nil)", note(L))
L.execute('NOW = NOW + 5; hide("MerchantFrame")')
check(on(L), "the vendor window hides (no close event yet), bags still open: taken", note(L))

# 2b. the close event alone (the frame hides with it) retries too
L = runtime()
L.execute('fire("MERCHANT_SHOW"); show("MerchantFrame"); show("ContainerFrameCombinedBags"); HOLDER = "interact"')
L.execute('WIN.MerchantFrame.shown = false; fire("MERCHANT_CLOSED"); run(0)')
check(on(L), "MERCHANT_CLOSED with the bags still open (Interact still named holder): taken", note(L))

# 3. another listed window shown (e.g. at alpha 0): named, taken on its OnHide
L = runtime()
L.execute('show("QuestFrame"); show("ContainerFrameCombinedBags")')
check(not on(L) and note(L) == "take-skip-window:QuestFrame", "a QuestFrame shown: skipped, named", note(L))
L.execute('hide("QuestFrame")')
check(on(L), "... its OnHide: taken", note(L))

# 4. the open event a moment before its frame shows: still skipped (grace)
L = runtime()
L.execute('fire("MERCHANT_SHOW"); show("ContainerFrameCombinedBags")')
check(not on(L) and note(L) == "take-skip-window:ctx-merchant", "MERCHANT_SHOW just now, frame not shown yet: skipped", note(L))
L.execute('show("MerchantFrame")')
check(not on(L), "... the vendor frame shows: still not taken", note(L))

# 5. bags closed while waiting: no take back; the room left by touch: no take back
L = runtime()
L.execute('show("MerchantFrame"); show("ContainerFrameCombinedBags"); hide("ContainerFrameCombinedBags"); hide("MerchantFrame")')
check(not on(L) and L.eval('TAKES') == 0, "bags closed while waiting, then the window hides: nothing taken", L.eval('TAKES'))
L = runtime()
L.execute('show("MerchantFrame"); show("ContainerFrameCombinedBags"); WowCompanion._bagsTouch(28); run(0); hide("MerchantFrame")')
check(not on(L) and L.eval('TAKES') == 0, "Home freed the room while waiting: the window hides, nothing taken", L.eval('TAKES'))

# 6. bags-take2 (review): an EARLIER visit's leave (another room took the pad, Home's free, B) must not stop this visit's retry
for prior, how in (("taken", 'SPEC.lost("taken"); run(0)'), ("free", 'WowCompanion._bagsTouch(28); run(0)'), ("B", 'SPEC.lost("B"); run(0)')):
    L = runtime()
    L.execute('show("ContainerFrameCombinedBags")')
    L.execute(how)
    L.execute('hide("ContainerFrameCombinedBags"); NOW = NOW + 60')
    L.execute('show("GossipFrame"); show("ContainerFrameCombinedBags")')
    n1 = note(L)
    L.execute('NOW = NOW + 3; hide("GossipFrame")')
    check(n1 == "take-skip-window:GossipFrame" and on(L),
          "an earlier visit left by %s, later a GossipFrame skip: taken when it hides" % prior, (n1, note(L)))
# ... and the 1 s check too (holder nil, no window hook involved): a stale leave from the earlier visit no longer stops it
L = runtime()
L.execute('show("ContainerFrameCombinedBags"); SPEC.lost("taken"); run(0); hide("ContainerFrameCombinedBags"); NOW = NOW + 60')
L.execute('WIN.QuestFrame.shown = true; show("ContainerFrameCombinedBags"); WIN.QuestFrame.shown = false')
L.execute('for _, t in ipairs(TICKS) do t() end; run(0)')
check(on(L), "an earlier visit lost to another room, this visit skipped (window hid without its OnHide): the 1 s check takes", note(L))
# a leave DURING this visit still stops the take back (B pressed while waiting)
L = runtime()
L.execute('show("ContainerFrameCombinedBags"); SPEC.lost("taken"); run(0); NOW = NOW + 5')
L.execute('show("GossipFrame"); WowCompanion._bagsTouch(29); run(0); hide("GossipFrame"); for _, t in ipairs(TICKS) do t() end; run(0)')
check(on(L), "touch entry while the bags stayed open after an earlier 'taken': skip, then taken when the window hides", note(L))
L = runtime()
L.execute('show("ContainerFrameCombinedBags"); SPEC.lost("taken"); run(0)')
L.execute('show("GossipFrame"); hide("GossipFrame"); for _, t in ipairs(TICKS) do t() end; run(0)')
check(not on(L), "lost to another room during this visit (bags still open, no new entry): no take back", note(L))

# 7. bags-take4: bags-take3's orphan rule is gone. A listed window that STAYS shown (a real guild bank, the game's own Character
#    window, a vendor whose Interact document is late) keeps the skip on every route and names itself, however long it lasts.
for w in ("GuildBankFrame", "CharacterFrame", "MerchantFrame", "GossipFrame"):
    L = runtime()
    L.execute('show("%s"); show("ContainerFrameCombinedBags")' % w)
    L.execute('for _ = 1, 5 do NOW = NOW + 1; for _, t in ipairs(TICKS) do t() end; run(0) end')
    L.execute('WowCompanion._bagsTouch(29); run(0)')
    check(not on(L) and note(L) == "take-skip-window:" + w and L.eval('TAKES') == 0,
          "%s shown 5 s with no holder: still skipped after the 1 s checks and the touch entry, named" % w, note(L))
    L.execute('hide("%s")' % w)
    check(on(L), "... %s hides: taken" % w, note(L))
check('take-orphan' not in SRC and 'ORPHAN_S' not in SRC, "bags-take4: no orphan take in Bags.lua")
ISRC = open(os.path.join(ADDONS, 'WowCompanionInteract', 'Interact.lua'), encoding='utf-8').read()
# i170 (fix/pet-trainer) moved the IV on: what this guards is bags-take3's i166 change staying out (npcOpen), at i165 or later
_ivm = __import__('re').search(r'local IV = "i(\d+)"', ISRC)
check(_ivm is not None and int(_ivm.group(1)) >= 165 and 'npcOpen = N.lastOpen' not in ISRC, "bags-take4: Interact i166 taken out (IV i165 or later)")
check('bags-take4' in SRC, "Bags.lua's header note for bags-take4")

# 8. bags-take5: the device log's own order. 15:49:43 a vendor (Interact holds "npc"), its window goes with no MERCHANT_CLOSED seen;
#    53 minutes of other rooms; 16:42:50 the Controller room's leave, then Bags' open door (bag0:39) shows the game's bags. Taken, the
#    stale state said once; three entries in a row (the log's 16:42:50 / :56 / :59) are each taken.
L = runtime()
L.execute('fire("MERCHANT_SHOW"); show("MerchantFrame"); HOLDER = "interact"; run(0)')
L.execute('hide("MerchantFrame"); HOLDER = nil; NOW = NOW + 53 * 60; run(0)')
for i in range(3):
    L.execute('show("ContainerFrameCombinedBags"); run(0)')
    check(on(L) and L.eval('TAKES') == i + 1, "log order, Bags entry %d after the vendor: taken (not take-skip-window)" % (i + 1), note(L))
    L.execute('SPEC.lost("free"); hide("ContainerFrameCombinedBags"); run(0)')
check(L.eval('doc().ctxStale') == "merchant", "the vendor's stale state is said (ctxStale = merchant)", L.eval('doc().ctxStale'))
check('bags-take5' in SRC and 'Morg Gnarltree' in SRC, "Bags.lua's header note for bags-take5 (the traced cause)")

check(L.eval('#TRACE') == 0, "read-only: no binding / macro / CVar / window call", L.eval('TRACE[1]'))
check('"read my DS my bag is behaving weird and opening the bag on top for some reason"' in SRC,
      "Bags.lua's header note quotes the user (bags-take1)")
check('bags-take2' in SRC, "Bags.lua's header note for bags-take2")
print('passes %d, failed: %d' % (passes, fails))
sys.exit(1 if fails else 0)
