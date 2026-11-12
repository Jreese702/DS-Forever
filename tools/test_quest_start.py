"""The quest starter prompt (WowCompanionInteract/Dialogs.lua d27, the user 2026-11-11: "If I pick up a quest item like that we should
make a prompt asking if we want to start that quest"), off-device: Dialogs.lua alone under a stub game.

    python tools/test_quest_start.py

Checks the bag scan (a starter is found; a normal item, a quest item of a quest already taken and a quest already done are not), one
prompt per item per login (Start and Not now both), the fight hold (nothing shown or armed in combat; shown at PLAYER_REGEN_ENABLED),
the queue (never over an open game window or a room holding the controller), A = a binding click of our secure item button armed with
that bag and slot OUT OF COMBAT only (and only on the edge the game's secure action runs on), B = Not now, and the touch cells. Needs
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
    GetContainerNumSlots = function(bag) return (bag <= 1) and 4 or 0 end,
    GetContainerItemInfo = function(bag, slot) local it = GAME.bags[bag] and GAME.bags[bag][slot]
        if it then return { itemID = it.id, iconFileID = 134939, quality = 1, hyperlink = "|cffffffff|Hitem:" .. it.id .. "|h[" .. it.name .. "]|h|r" } end end,
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
function FIRE(e) for _, f in ipairs(FRAMES) do if f.events[e] and f.scripts.OnEvent then f.scripts.OnEvent(f, e) end end; FLUSH() end
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
GAME.bags[0] = { [1] = { id = 2589, name = "Linen Cloth" },
                 [2] = { id = 5000, name = "Wolf Fang", quest = 700 },
                 [3] = { id = 1972, name = "Ripped Missive", quest = 900 } }
GAME.bags[1] = { [1] = { id = 1307, name = "Gold Pickup Schedule", quest = 123 } }
GAME.quests[700] = true
GAME.done[123] = true
GAME.titles[900] = "The Missing Courier"
FIRE("BAG_UPDATE_DELAYED")
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
chk("armed out of combat: type item, that bag and slot", use and use.attr.type == "item" and use.attr.item == "0 3")
chk("the touch cells: Start 33 (armed the same), Not now 34 (no secure action)", p and p.cells and p.cells.b1 == 33 and p.cells.b2 == 34
    and ROW.n == 35 and ROW.cells[33].attr.item == "0 3" and ROW.cells[34].attr.type == nil)
chk("nothing used yet", #GAME.used == 0)

-- A: the press's other edge does nothing (ActionButtonUseKeyDown off: the release is the action's)
PRESS("WowCompanionQStartUse", true)
chk("the press edge alone neither uses nor closes", #GAME.used == 0 and PROMPT() ~= nil)
PRESS("WowCompanionQStartUse", false)
chk("A uses the item in its slot (the game's own use: it opens its quest page)", GAME.used[1] == "0 3" and #GAME.used == 1)
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
chk("re-armed on its slot", use.attr.item == "1 2")

-- B = Not now: gone, never again this login, the item stays in the bags
PRESS("WowCompanionQStartNo", false)
chk("B closes it (Not now)", PROMPT() == nil and ACKS() == 2 and #GAME.used == 1)
FIRE("BAG_UPDATE_DELAYED")
chk("and it is not asked again", PROMPT() == nil)
chk("the item is still in the bags", GAME.bags[1][2] ~= nil)

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
chk("a tap on Start uses the item through its cell", GAME.used[2] == "1 3")
chk("and closes it", PROMPT() == nil and ACKS() == 3)
-- the key-down setting: the press is the action's edge
GAME.t = GAME.t + 5
GAME.keyDown = true
GAME.bags[0][4] = { id = 4100, name = "Torn Page", quest = 556 }
FIRE("BAG_UPDATE_DELAYED")
chk("the next one shows", PROMPT() ~= nil)
PRESS("WowCompanionQStartUse", true)
chk("ActionButtonUseKeyDown on: the press uses it and closes it", GAME.used[3] == "0 4" and PROMPT() == nil)
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
chk("never in combat, all along", GAME.combatSets == 0)
return table.concat(out, "\n")
'''


def main():
    L = lupa.LuaRuntime()
    L.execute(STUB)
    L.execute(open(SRC, encoding='utf-8').read())
    res = str(L.execute(TEST))
    print(res)
    fails = res.count("FAIL ")
    print("failed: %d" % fails)
    return 1 if fails else 0


if __name__ == '__main__':
    sys.exit(main())
