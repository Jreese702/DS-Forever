"""bags-qstart1 (issue #252, 1.0.29: "Items with quests attached to them cannot be started from the bag menu"): an item that BEGINS a
quest has no "Use:" spell, is not readable and is not worn, so its bag row carried no action and the Bags room's tooltip offered none.
Loads the real WowCompanion/Bags.lua in Lua 5.1 with stand-in game functions and checks the rows of collectBags():
  - a starter whose quest is neither in the log nor done says qstart (the app's "Start Quest" on X: the slot's own secure item cell);
  - a starter whose quest is active (the game's isActive, or C_QuestLog.IsOnQuest) or already done says nothing new;
  - a plain quest item (isQuestItem, no quest id) and an ordinary item say nothing new; every quest item still says quest;
  - read-only: no SetBinding / SaveBindings / macro / CVar call, nothing used.
Run: python -B tools/test_bags_qstart.py
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
C_Timer = { NewTicker = function() return stub end, NewTimer = function() return stub end, After = function() end }
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
WowCompanion = {
    S = function(fn) local ok, v = pcall(fn); if ok then return v end end,
    SN = function(fn) local ok, v = pcall(fn); if ok and type(v) == "number" then return v end end,
    CALL = function(fn) return pcall(fn) end,
    emit = function() end, send = function() end, encode = function() return "" end, push = function() end,
    isPlain = function() return true end,
    pad = { secureRow = function() return 1 end, op = function() end, describe = function() return nil end },
    bagsPadRows = function() end, bagsMoveRows = function() end,
}
function InCombatLockdown() return false end
function UnitName() return "Shamy" end
function GetRealmName() return "Forever" end
TRACE = {}
for _, n in ipairs({ "SetBinding", "SaveBindings", "DeleteMacro", "CreateMacro", "EditMacro", "SetCVar", "ShowUIPanel", "HideUIPanel",
                     "UseContainerItem", "PickupContainerItem", "StaticPopup_Show" }) do
    _G[n] = function() TRACE[#TRACE + 1] = n end
end
function GetNumMacros() return 0, 0 end
function GetCursorInfo() return nil end
-- the backpack: 1 a starter (quest 501, not taken), 2 a starter whose quest is active (the game's flag), 3 a starter on a quest the log
-- has (IsOnQuest), 4 a starter whose quest is done, 5 a plain quest item, 6 an ordinary item
BAG = { 701, 702, 703, 704, 705, 706 }
QINFO = { [701] = { questID = 501, isActive = false }, [702] = { questID = 502, isActive = true }, [703] = { questID = 503 },
          [704] = { questID = 504, isActive = false }, [705] = { isQuestItem = true } }
ONQUEST = { [503] = true }
DONE = { [504] = true }
C_QuestLog = { IsOnQuest = function(q) return ONQUEST[q] == true end, IsQuestFlaggedCompleted = function(q) return DONE[q] == true end }
C_Container = {
    GetContainerItemID = function(b, s) return b == 0 and BAG[s] or nil end,
    GetContainerNumSlots = function(b) return b == 0 and 8 or 0 end,
    GetContainerNumFreeSlots = function(b) return 2, 0 end,
    GetContainerItemInfo = function(b, s) local id = b == 0 and BAG[s]; if id then
        return { itemID = id, iconFileID = 1, quality = 1, stackCount = 1, hyperlink = "|Hitem:" .. id .. "|h" } end end,
    GetContainerItemLink = function(b, s) local id = b == 0 and BAG[s]; if id then return "|Hitem:" .. id .. "|h" end end,
    GetContainerItemQuestInfo = function(b, s) local id = b == 0 and BAG[s]; return (id and QINFO[id]) or {} end,
}
C_Item = {
    GetItemNameByID = function(id) return "Item " .. id end,
    GetItemInfoInstant = function(x) local id = tonumber(tostring(x):match("(%d+)")); return id, "Quest", "Quest", "INVTYPE_NON_EQUIP_IGNORE", 1, 12, 0 end,
    GetItemInfo = function(x) local id = tonumber(tostring(x):match("(%d+)")); return "Item " .. id, "|Hitem:" .. id .. "|h", 1, 1, 1, "Quest", "Quest", 1, "", 1, 0 end,
    GetItemSpell = function() return nil end, RequestLoadItemDataByID = function() end, GetItemStats = function() return {} end,
}
function GetInventoryItemID() return nil end
function GetInventoryItemLink() return nil end
for _, n in ipairs({ "MerchantFrame", "GossipFrame", "QuestFrame", "BankFrame", "MailFrame", "TradeFrame", "AuctionHouseFrame",
                     "GuildBankFrame", "ClassTrainerFrame", "CharacterFrame" }) do _G[n] = { IsShown = function() return false end } end
function rowOf(doc, slot)
    for _, it in ipairs(doc.items or {}) do if it.bag == 0 and it.slot == slot then return it end end
end
''')
load = L.eval('function(src) local f, e = loadstring(src, "@Bags.lua"); if not f then error(e) end; return f end')
ok_load = True
try:
    load(SRC)()
except Exception as e:  # noqa: BLE001
    ok_load = False; print(e)
check(ok_load, "Bags.lua loads (stand-in game) with no error")
L.execute('WowCompanionDB = {}; fire("PLAYER_LOGIN"); DOC = WowCompanion.collectBags()')


def row(slot):
    return L.eval('rowOf(DOC, %d)' % slot)


r = [row(s) for s in range(1, 7)]
check(all(x is not None for x in r), "every slot's row is in the document", [x is not None for x in r])
if all(x is not None for x in r):
    check(r[0].qstart is True and r[0].quest is True, "a starter whose quest is not taken: qstart (and quest)", (r[0].qstart, r[0].quest))
    check(r[1].qstart is None and r[1].quest is True, "the game says its quest is active: no qstart", r[1].qstart)
    check(r[2].qstart is None and r[2].quest is True, "its quest is in the log (IsOnQuest): no qstart", r[2].qstart)
    check(r[3].qstart is None and r[3].quest is True, "its quest is done: no qstart", r[3].qstart)
    check(r[4].qstart is None and r[4].quest is True, "a plain quest item (no quest id): no qstart, still quest", r[4].qstart)
    check(r[5].qstart is None and r[5].quest is None, "an ordinary item: neither", (r[5].qstart, r[5].quest))
    check(r[0].use is None, "nothing pretends a 'Use:' spell (the app's Start Quest comes from qstart)", r[0].use)
    L.execute('ONQUEST[501] = true; DOC = WowCompanion.collectBags()')
    check(row(1).qstart is None, "the quest taken (it is in the log now): qstart gone at the next collect", row(1).qstart)
check(L.eval('#TRACE') == 0, "read-only: no binding / macro / CVar / use call", L.eval('TRACE[1]'))
check("bags-qstart1 (issue #252" in SRC and "Items with quests attached to them cannot be started from the bag menu" in SRC,
      "the header note quotes the report")
print('passes %d, failed: %d' % (passes, fails))
sys.exit(1 if fails else 0)
