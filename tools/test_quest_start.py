"""The quest starter prompt (WowCompanionInteract/Dialogs.lua d27, the user 2026-11-11: "If I pick up a quest item like that we should
make a prompt asking if we want to start that quest"), off-device: Dialogs.lua alone under a stub game.

    python tools/test_quest_start.py

Checks (d29, the user 2026-11-11: "Only when newly looted") that only a NEWLY LOOTED starter is offered: none already in the bags at
login or a /reload, before PLAYER_ENTERING_WORLD, in bags that load late, or moved between bags / banked and taken back; a loot after
the login (and after a plain zone change) is; Not now then the item still there or moved = no prompt; a second copy looted later, or a
new one after the first was used up = a prompt. And the bag scan (a starter is found; a normal item, a quest item of a quest already taken and a quest already done are not), one
prompt per looted copy (Start and Not now both), the fight hold (nothing shown or armed in combat; shown at PLAYER_REGEN_ENABLED),
the queue (never over an open game window or a room holding the controller), A = a binding click of our secure item button armed with
that item's id (d28) OUT OF COMBAT only (and only on the edge the game's secure action runs on), B = Not now, the touch cells, and (d28) a vendor / bank / mail / trade window opening
under the prompt (disarmed on its event, a Start in the gap uses nothing). Needs
lupa. Exit code 1 on any failure."""
import os
import sys

import lupa

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, 'companion', 'app', 'src', 'main', 'assets', 'companion', 'addons', 'WowCompanionInteract', 'Dialogs.lua')

STUB = r'''
unpack = table.unpack
GAME = { t = 1000, combat = false, keyDown = false, bags = {}, quests = {}, done = {}, titles = {}, used = {}, combatSets = 0, timers = {} }
SENT, FRAMES, CAP = {}, {}, { holder = nil, spec = nil, takes = 0 }
local FrameMT = {}
FrameMT.__index = {
    SetScript = function(self, k, fn) self.scripts[k] = fn end,
    HookScript = function(self, k, fn) self.hooks[#self.hooks + 1] = fn end,
    RegisterEvent = function(self, e) self.events[e] = true end,
    SetAttribute = function(self, k, v) if GAME.combat then GAME.combatSets = GAME.combatSets + 1; error("protected in combat") end; self.attr[k] = v end,
    GetAttribute = function(self, k) return self.attr[k] end,
    SetSize = function() end, SetPoint = function() end, EnableMouse = function() end, RegisterForClicks = function() end,
    IsShown = function(self) return self.shown ~= false end, SetAlpha = function() end, GetText = function() return nil end,
    IsEnabled = function() return true end,
}
function CreateFrame(kind, name, parent, template)
    local f = setmetatable({ kind = kind, name = name, scripts = {}, hooks = {}, events = {}, attr = {}, template = template }, FrameMT)
    FRAMES[#FRAMES + 1] = f
    if name then _G[name] = f end
    return f
end
UIParent = CreateFrame("Frame", "UIParent")
function InCombatLockdown() return GAME.combat end
function GetTime() return GAME.t end
function GetCVarBool(k) if k == "ActionButtonUseKeyDown" then return GAME.keyDown end end
function UnitIsDeadOrGhost() return false end
function GetActiveLootRollIDs() return {} end
function GetBindingKey() return nil end
function hooksecurefunc() end
C_Timer = { After = function(t, fn) GAME.timers[#GAME.timers + 1] = fn end }
function FLUSH() for _ = 1, 5 do local q = GAME.timers; GAME.timers = {}; for _, fn in ipairs(q) do fn() end end end
ITEM_QUALITY_COLORS = { [1] = { r = 1, g = 1, b = 1 } }
-- the bags: GAME.bags[bag][slot] = { id, name, quest = id?, questItem = bool? }
C_Container = {
    GetContainerNumSlots = function(bag) if GAME.unloaded == true or (type(GAME.unloaded) == 'table' and GAME.unloaded[bag]) then return 0 end; return (bag <= 1) and 6 or 0 end,
    GetContainerItemInfo = function(bag, slot) local it = GAME.bags[bag] and GAME.bags[bag][slot]
        if it then return { itemID = it.id, stackCount = it.count or 1, iconFileID = 134939, quality = 1, hyperlink = "|cffffffff|Hitem:" .. it.id .. "|h[" .. it.name .. "]|h|r" } end end,
    GetContainerItemID = function(bag, slot) local it = GAME.bags[bag] and GAME.bags[bag][slot]; return it and it.id end,
    GetContainerItemQuestInfo = function(bag, slot) local it = GAME.bags[bag] and GAME.bags[bag][slot]
        if not it then return { isQuestItem = false } end
        return { isQuestItem = it.questItem or it.quest ~= nil, questID = it.quest, isActive = it.quest ~= nil and GAME.quests[it.quest] == true } end,
}
C_QuestLog = { IsOnQuest = function(q) return GAME.quests[q] == true end, IsQuestFlaggedCompleted = function(q) return GAME.done[q] == true end,
               GetTitleForQuestID = function(q) return GAME.titles[q] end }
C_Item = { GetItemNameByID = function(id) for _, b in pairs(GAME.bags) do for _, it in pairs(b) do if it.id == id then return it.name end end end end }
local ABSENT, SECRET, THREW = {}, {}, {}
WowCompanion = {
    ABSENT = ABSENT, SECRET = SECRET, THREW = THREW,
    S = function(fn) local ok, v = pcall(fn); if not ok then return THREW end; if v == nil then return ABSENT end; return v end,
    CALL = function(fn, ...) return pcall(fn, ...) end,
    isPlain = function() return true end,
    send = function(t) SENT[#SENT + 1] = t end, encode = function(t) return t end, push = function() end,
    interactMode = function() return true end,
    capture = { version = 19,
                take = function(id, spec) CAP.takes = CAP.takes + 1; CAP.spec = spec; CAP.holder = id; return true, nil end,
                release = function(id) if CAP.holder == id then CAP.holder = nil; CAP.spec = nil end end,
                state = function() return { holder = CAP.holder } end },
    pad = { secureRow = function(name, n, init, onClick)
                ROW = { name = name, n = n, cells = {}, onClick = onClick }
                for col = 0, n - 1 do local b = CreateFrame("Button"); ROW.cells[col] = b; init(b, col) end
                return 1 end,
            describe = function() return { ch = 3, rows = { idlg = 1, iact = 2 } } end,
            slotFor = function() return 0 end },
}
function FIRE(e, ...) for _, f in ipairs(FRAMES) do if f.events[e] and f.scripts.OnEvent then f.scripts.OnEvent(f, e, ...) end end; FLUSH() end
-- a binding click of a secure button of ours: the game's secure action runs on its edge (ActionButtonUseKeyDown), then our hook
function PRESS(name, down)
    local b = _G[name]
    if b.attr.type == "item" and (down == true) == (GAME.keyDown == true) and b.attr.item then GAME.used[#GAME.used + 1] = b.attr.item end
    for _, h in ipairs(b.hooks) do h(b, "LeftButton", down) end
    FLUSH()
end
-- a tap on a touch cell of the idlg row: its secure action, then the row's onClick
function TAP(col)
    local b = ROW.cells[col]
    if b.attr.type == "item" and b.attr.item then GAME.used[#GAME.used + 1] = b.attr.item end
    ROW.onClick(col); FLUSH()
end
-- the prompt in a fresh document (nil when none)
function PROMPT()
    WowCompanion.dialogs.alive(); FLUSH()
    for i = #SENT, 1, -1 do
        local d = SENT[i]
        if d.type == "dialogs" then
            for _, x in ipairs(d.list) do if x.which == "QUEST_START" then return x, d end end
            return nil, d
        end
    end
end
function ACKS() local n = 0; for _, d in ipairs(SENT) do if d.type == "dialogack" and d.which == "QUEST_START" then n = n + 1 end end; return n end
'''

TEST = r'''
local out = {}
local function chk(n, c) out[#out + 1] = (c and "PASS " or "FAIL ") .. n end

-- the bags: a plain item, a quest item of a quest already taken, a starter whose quest is already done, the starter
-- d29 (the user, 2026-11-11: "Only when newly looted"): and a starter ALREADY in the bags at login (an old letter, never offered)
GAME.bags[0] = { [1] = { id = 2589, name = "Linen Cloth", count = 5 },
                 [2] = { id = 5000, name = "Wolf Fang", quest = 700 },
                 [5] = { id = 1990, name = "Old Letter", quest = 901 } }
GAME.bags[1] = { [1] = { id = 1307, name = "Gold Pickup Schedule", quest = 123 } }
GAME.quests[700] = true
GAME.done[123] = true
GAME.titles[900] = "The Missing Courier"
FIRE("BAG_UPDATE_DELAYED")
chk("d29: before PLAYER_ENTERING_WORLD: a starter in the bags is not offered", PROMPT() == nil)
FIRE("PLAYER_ENTERING_WORLD", true, false)
chk("d29: login: a starter already in the bags is not offered", PROMPT() == nil)
GAME.t = GAME.t + 6
FIRE("BAG_UPDATE_DELAYED")
chk("d29: settled: still never offered (it was there at login)", PROMPT() == nil and ACKS() == 0)
GAME.bags[1][5] = GAME.bags[0][5]; GAME.bags[0][5] = nil
FIRE("BAG_UPDATE_DELAYED")
chk("d29: moved to another bag: still not offered", PROMPT() == nil)
-- the starter NEWLY LOOTED
GAME.bags[0][3] = { id = 1972, name = "Ripped Missive", quest = 900 }
FIRE("CHAT_MSG_LOOT"); FIRE("BAG_UPDATE_DELAYED")
local p = PROMPT()
chk("a quest starter in the bags is offered", p ~= nil)
chk("it is the starter (" .. tostring(p and p.item and p.item.name) .. "), not the cloth, the taken quest's item or a done quest's",
    p and p.qs.item == 1972 and p.qs.bag == 0 and p.qs.slot == 3 and p.qs.quest == 900)
chk("its words: Start quest?, the quest's title, the item", p and p.text == "Start quest?" and p.sub == "The Missing Courier"
    and p.item.icon == 134939 and #p.item.rgb == 3)
chk("two buttons: Start, Not now", p and #p.buttons == 2 and p.buttons[1].label == "Start" and p.buttons[2].label == "Not now")
chk("the controller: A and B on them (the dialogs' capture)", p and p.focus == true and p.buttons[1].key == "PAD1" and p.buttons[2].key == "PAD2")
chk("A is a binding click of OUR secure item button, B of our Not now", CAP.spec and CAP.spec.a == "WowCompanionQStartUse"
    and CAP.spec.back == "WowCompanionQStartNo" and CAP.spec.noFight == true)
local use = _G.WowCompanionQStartUse
chk("armed out of combat: type item, that item by id (d28)", use and use.attr.type == "item" and use.attr.item == "item:1972")
chk("the touch cells: Start 33 (armed the same), Not now 34 (no secure action)", p and p.cells and p.cells.b1 == 33 and p.cells.b2 == 34
    and ROW.n == 35 and ROW.cells[33].attr.item == "item:1972" and ROW.cells[34].attr.type == nil)
chk("nothing used yet", #GAME.used == 0)

-- A: the press's other edge does nothing (ActionButtonUseKeyDown off: the release is the action's)
PRESS("WowCompanionQStartUse", true)
chk("the press edge alone neither uses nor closes", #GAME.used == 0 and PROMPT() ~= nil)
PRESS("WowCompanionQStartUse", false)
chk("A uses the item (by id) (the game's own use: it opens its quest page)", GAME.used[1] == "item:1972" and #GAME.used == 1)
chk("and the prompt is gone, with its answer sent", PROMPT() == nil and ACKS() == 1)
chk("the controller let go", CAP.holder == nil)
FIRE("BAG_UPDATE_DELAYED")
chk("never asked again for that item this login", PROMPT() == nil)

-- a second starter, looted in a FIGHT: held, never armed in combat, shown when the fight ends
GAME.t = GAME.t + 5
GAME.combat = true
GAME.bags[1][2] = { id = 1971, name = "Furlbrow's Deed", quest = 184 }
FIRE("CHAT_MSG_LOOT"); FIRE("BAG_UPDATE_DELAYED")
chk("in a fight: no prompt", PROMPT() == nil)
chk("in a fight: no secure attribute written", GAME.combatSets == 0)
GAME.combat = false
FIRE("PLAYER_REGEN_ENABLED")
p = PROMPT()
chk("the fight over: the held prompt shows (" .. tostring(p and p.item.name) .. ")", p ~= nil and p.qs.item == 1971 and p.sub == nil)
chk("re-armed on that item", use.attr.item == "item:1971")

-- B = Not now: gone, never again this login, the item stays in the bags
PRESS("WowCompanionQStartNo", false)
chk("B closes it (Not now)", PROMPT() == nil and ACKS() == 2 and #GAME.used == 1)
FIRE("BAG_UPDATE_DELAYED")
chk("and it is not asked again", PROMPT() == nil)
chk("the item is still in the bags", GAME.bags[1][2] ~= nil)
GAME.bags[1][6] = GAME.bags[1][2]; GAME.bags[1][2] = nil
FIRE("BAG_UPDATE_DELAYED")
chk("d29: Not now, then moved between bags: not offered again", PROMPT() == nil)

-- QUEUED: never over a game window, a room holding the controller or another dialog
GAME.bags[1][3] = { id = 4000, name = "A Mysterious Letter", quest = 555 }
QuestFrame = CreateFrame("Frame", "QuestFrame"); QuestFrame.shown = true
FIRE("BAG_UPDATE_DELAYED")
chk("an open game window: it waits", PROMPT() == nil)
chk("waiting: our cells disarmed (a stray tap uses nothing)", use.attr.item == nil and ROW.cells[33].attr.item == nil)
QuestFrame.shown = false
FIRE("QUEST_FINISHED")
chk("the window closed: it shows", PROMPT() ~= nil)
CAP.holder, CAP.spec = "bags", nil
chk("a room holds the controller: it waits", PROMPT() == nil)
CAP.holder = nil
GuildInviteFrame = CreateFrame("Frame", "GuildInviteFrame")
p = select(2, PROMPT())
chk("another dialog up (a guild invite): only that one is listed", p and #p.list == 1 and p.list[1].which == "GUILD_INVITE")
GuildInviteFrame.shown = false
chk("that dialog gone: the prompt is back", PROMPT() ~= nil)

-- the touch path: the Start cell uses the item, then the row's answer closes it
TAP(33)
chk("a tap on Start uses the item through its cell", GAME.used[2] == "item:4000")
chk("and closes it", PROMPT() == nil and ACKS() == 3)
-- the key-down setting: the press is the action's edge
GAME.t = GAME.t + 5
GAME.keyDown = true
GAME.bags[0][4] = { id = 4100, name = "Torn Page", quest = 556 }
FIRE("BAG_UPDATE_DELAYED")
chk("the next one shows", PROMPT() ~= nil)
PRESS("WowCompanionQStartUse", true)
chk("ActionButtonUseKeyDown on: the press uses it and closes it", GAME.used[3] == "item:4100" and PROMPT() == nil)
PRESS("WowCompanionQStartUse", false)
chk("and the release adds nothing", #GAME.used == 3 and ACKS() == 4)

-- a quest taken some other way while the prompt waits: it goes away
GAME.t = GAME.t + 5
GAME.bags[0][1] = { id = 4200, name = "Bloodstained Note", quest = 557 }
FIRE("BAG_UPDATE_DELAYED")
chk("a fourth starter shows", PROMPT() ~= nil)
GAME.quests[557] = true
FIRE("QUEST_ACCEPTED")
chk("its quest taken elsewhere (the item's own page): the prompt goes", PROMPT() == nil)

-- d28: a VENDOR opens while the prompt is up (a bag item used at a vendor is SOLD): on MERCHANT_SHOW itself, before its frame shows
-- and before any build, both buttons are disarmed and the prompt drops; a Start in that gap uses nothing and is no answer
GAME.t = GAME.t + 5
GAME.bags[0][2] = { id = 4300, name = "Sealed Letter", quest = 558 }
FIRE("BAG_UPDATE_DELAYED")
chk("d28: a fifth starter shows", PROMPT() ~= nil and use.attr.item == "item:4300")
local acks, used = ACKS(), #GAME.used
for _, f in ipairs(FRAMES) do if f.events.MERCHANT_SHOW then f.scripts.OnEvent(f, "MERCHANT_SHOW") end end
chk("d28: MERCHANT_SHOW disarms our button and our Start cell at once", use.attr.item == nil and ROW.cells[33].attr.item == nil)
PRESS("WowCompanionQStartUse", true); TAP(33)
chk("d28: a Start in the gap sells nothing, uses nothing and is no answer", #GAME.used == used and ACKS() == acks)
MerchantFrame = CreateFrame("Frame", "MerchantFrame"); MerchantFrame.shown = false
chk("d28: the prompt drops while the window opens (its frame not shown yet)", PROMPT() == nil)
MerchantFrame.shown = true; GAME.t = GAME.t + 2
chk("d28: and stays off while the vendor is open", PROMPT() == nil and use.attr.item == nil)
MerchantFrame.shown = false; FIRE("MERCHANT_CLOSED")
chk("d28: the vendor closed: it is back, armed again", PROMPT() ~= nil and use.attr.item == "item:4300")
for _, ev in ipairs({ "BANKFRAME_OPENED", "MAIL_SHOW", "TRADE_SHOW", "AUCTION_HOUSE_SHOW", "GOSSIP_SHOW", "QUEST_DETAIL", "LOOT_OPENED" }) do
    local reg = false
    for _, f in ipairs(FRAMES) do if f.events[ev] then reg = true end end
    chk("d28: " .. ev .. " is listened to", reg)
end
-- d28: the item MOVED before the next build: Start still uses that item (aimed by id)
GAME.bags[1][4] = GAME.bags[0][2]; GAME.bags[0][2] = nil
PRESS("WowCompanionQStartUse", true)
chk("d28: moved, then A: that item is used, not whatever is in its old slot", GAME.used[#GAME.used] == "item:4300" and ACKS() == acks + 1)
GAME.bags[0][6] = GAME.bags[1][4]; GAME.bags[1][4] = nil
FIRE("BAG_UPDATE_DELAYED")
chk("d29: answered, then moved again: not offered", PROMPT() == nil)

-- d29: a SECOND copy looted later (onto the Not-now one's stack): offered again, once
GAME.t = GAME.t + 5
acks = ACKS()
GAME.bags[1][6].count = 2
FIRE("CHAT_MSG_LOOT"); FIRE("BAG_UPDATE_DELAYED")
p = PROMPT()
chk("d29: a second copy looted later: offered (" .. tostring(p and p.item.name) .. ")", p ~= nil and p.qs.item == 1971)
PRESS("WowCompanionQStartNo", GAME.keyDown)
FIRE("BAG_UPDATE_DELAYED")
chk("d29: Not now again: gone, both copies still in the bags", PROMPT() == nil and ACKS() == acks + 1 and GAME.bags[1][6].count == 2)
-- d29: used up (gone from the bags), then a new one looted: the tally fell with it, so the new one is a gain
GAME.bags[1][6] = nil
FIRE("BAG_UPDATE_DELAYED")
chk("d29: used up: nothing", PROMPT() == nil)
GAME.bags[0][5] = { id = 1971, name = "Furlbrow's Deed", quest = 184 }
FIRE("CHAT_MSG_LOOT"); FIRE("BAG_UPDATE_DELAYED")
p = PROMPT()
chk("d29: then a new copy looted: offered", p ~= nil and p.qs.item == 1971 and p.qs.bag == 0 and p.qs.slot == 5)
PRESS("WowCompanionQStartNo", GAME.keyDown)
chk("d29: answered", PROMPT() == nil)
-- d29: the bank: a starter deposited and taken back out is the player's own, not newly looted
BankFrame = CreateFrame("Frame", "BankFrame"); BankFrame.shown = true
FIRE("BANKFRAME_OPENED")
local banked = GAME.bags[1][5]; GAME.bags[1][5] = nil
FIRE("BAG_UPDATE_DELAYED")
GAME.bags[1][5] = banked
FIRE("BAG_UPDATE_DELAYED")
BankFrame.shown = false; FIRE("BANKFRAME_CLOSED")
FIRE("BAG_UPDATE_DELAYED")
GAME.t = GAME.t + 2
chk("d29: banked and taken back out: not offered", PROMPT() == nil)
BankFrame.shown = true; FIRE("BANKFRAME_OPENED")
GAME.bags[1][5] = nil; FIRE("BAG_UPDATE_DELAYED")
BankFrame.shown = false; FIRE("BANKFRAME_CLOSED")
BankFrame.shown = true; FIRE("BANKFRAME_OPENED")
GAME.bags[1][5] = banked; FIRE("BAG_UPDATE_DELAYED")
BankFrame.shown = false; FIRE("BANKFRAME_CLOSED"); FIRE("BAG_UPDATE_DELAYED")
GAME.t = GAME.t + 2
chk("d29: deposited on one visit, withdrawn on the next: not offered", PROMPT() == nil)
-- d29: a plain zone change (PLAYER_ENTERING_WORLD, neither login nor reload) keeps the session: a loot right after it is offered
FIRE("PLAYER_ENTERING_WORLD", false, false)
GAME.bags[1][2] = { id = 4400, name = "Wanted Poster", quest = 559 }
FIRE("CHAT_MSG_LOOT"); FIRE("BAG_UPDATE_DELAYED")
chk("d29: a zone change is no baseline: a loot right after it is offered", (PROMPT() or {}).qs and PROMPT().qs.item == 4400)
chk("never in combat, all along", GAME.combatSets == 0)
return table.concat(out, "\n")
'''

# d29: a /reload (a fresh Lua state) with newly looted, unanswered starters in the bags: never offered; and bags that load late
RELOAD = r'''
local out = {}
local function chk(n, c) out[#out + 1] = (c and "PASS " or "FAIL ") .. n end
GAME.bags[0] = { [1] = { id = 4400, name = "Wanted Poster", quest = 559 }, [2] = { id = 1972, name = "Ripped Missive", quest = 900 } }
FIRE("BAG_UPDATE_DELAYED")
FIRE("PLAYER_ENTERING_WORLD", false, true)
chk("d29: a /reload with starters in the bags: not offered", PROMPT() == nil)
GAME.t = GAME.t + 6
FIRE("BAG_UPDATE_DELAYED")
chk("d29: settled after the reload: still not offered", PROMPT() == nil and ACKS() == 0)
GAME.bags[1] = { [1] = { id = 4500, name = "Faded Map", quest = 560 } }
FIRE("CHAT_MSG_LOOT"); FIRE("BAG_UPDATE_DELAYED")
local p = PROMPT()
chk("d29: a starter looted after the reload: offered", p ~= nil and p.qs.item == 4500)
return table.concat(out, "\n")
'''

LATE = r'''
local out = {}
local function chk(n, c) out[#out + 1] = (c and "PASS " or "FAIL ") .. n end
GAME.unloaded = true
GAME.bags[0] = { [1] = { id = 1972, name = "Ripped Missive", quest = 900 } }
FIRE("PLAYER_ENTERING_WORLD", true, false)
GAME.t = GAME.t + 30
FIRE("BAG_UPDATE_DELAYED")
chk("d29: bags still empty long after login: nothing", PROMPT() == nil)
GAME.unloaded = false
FIRE("BAG_UPDATE_DELAYED")
chk("d29: the bags load late: what they hold is baseline, not offered", PROMPT() == nil)
GAME.bags[0][2] = { id = 4500, name = "Faded Map", quest = 560 }
FIRE("BAG_UPDATE_DELAYED")
chk("d29: then a loot: offered", (PROMPT() or {}).qs ~= nil)
return table.concat(out, "\n")
'''

# d29 review: bags truly EMPTY at login (the backpack reports its slots): the first real loot, minutes later, is offered
EMPTY = r'''
local out = {}
local function chk(n, c) out[#out + 1] = (c and "PASS " or "FAIL ") .. n end
FIRE("PLAYER_ENTERING_WORLD", true, false)
FIRE("BAG_UPDATE_DELAYED")
GAME.t = GAME.t + 6
FIRE("BAG_UPDATE_DELAYED")
chk("d29: empty bags at login: nothing", PROMPT() == nil)
GAME.t = GAME.t + 300
GAME.bags[0] = { [1] = { id = 4500, name = "Faded Map", quest = 560 } }
FIRE("CHAT_MSG_LOOT"); FIRE("BAG_UPDATE_DELAYED")
local p = PROMPT()
chk("d29: empty bags at login, then the first loot: offered", p ~= nil and p.qs.item == 4500)
return table.concat(out, "\n")
'''

# d29 review: a cold login, the backpack readable but bag 1 still 0 slots until after the settle: bag 1's old starter is baseline
PARTIAL = r'''
local out = {}
local function chk(n, c) out[#out + 1] = (c and "PASS " or "FAIL ") .. n end
GAME.unloaded = { [1] = true }
GAME.bags[0] = { [1] = { id = 2589, name = "Linen Cloth", count = 5 } }
GAME.bags[1] = { [1] = { id = 1972, name = "Ripped Missive", quest = 900 } }
FIRE("PLAYER_ENTERING_WORLD", true, false)
FIRE("BAG_UPDATE_DELAYED")
GAME.t = GAME.t + 30
FIRE("BAG_UPDATE_DELAYED")
chk("d29: backpack loaded, bag 1 not yet: nothing", PROMPT() == nil)
GAME.unloaded = false
FIRE("BAG_UPDATE_DELAYED")
chk("d29: bag 1 loads after the settle: its old starter is baseline, not offered", PROMPT() == nil and ACKS() == 0)
GAME.bags[1][2] = { id = 4500, name = "Faded Map", quest = 560 }
FIRE("CHAT_MSG_LOOT"); FIRE("BAG_UPDATE_DELAYED")
local p = PROMPT()
chk("d29: then a loot into bag 1: offered (the Faded Map, not the old one)", p ~= nil and p.qs.item == 4500)
return table.concat(out, "\n")
'''


def run(test):
    L = lupa.LuaRuntime()
    L.execute(STUB)
    L.execute(open(SRC, encoding='utf-8').read())
    return str(L.execute(test))


def main():
    res = "\n".join(run(t) for t in (TEST, RELOAD, LATE, EMPTY, PARTIAL))   # d29: each its own Lua state (a /reload is a fresh one)
    print(res)
    fails = res.count("FAIL ")
    print("failed: %d" % fails)
    return 1 if fails else 0


if __name__ == '__main__':
    sys.exit(main())
