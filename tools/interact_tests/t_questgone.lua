-- i143 (the user, 2026-11-11: "polestar forced me into accepting a quest ... now my bottom screen is stuck with a blank quest page that
-- wont close"; the log: stage "detail", id 0, title "" every 15 s): Polestar accepts INSIDE the game's QUEST_DETAIL, so its nested
-- QUEST_FINISHED reaches us before our own QUEST_DETAIL. The game's own quest state now decides: nothing there = closed.
local N, ACT, ON, build = __N, __ACT, __ON, __build
local out = {}
local function chk(n, c) out[#out + 1] = (c and "PASS " or "FAIL ") .. n end
local FrameMT = { __index = {
    GetAlpha = function(self) return self.alpha or 1 end, SetAlpha = function(self, a) self.alpha = a end,
    IsShown = function(self) return self.shown ~= false end, HookScript = function() end, GetRect = function() return 0, 0, 100, 100 end,
    GetEffectiveScale = function() return 1 end, GetChildren = function() end, GetRegions = function() end,
    GetParent = function() return nil end,
} }
rawset(_G, "QuestFrame", setmetatable({ alpha = 1 }, FrameMT))
WowCompanionDB = { lootTop = false }
GAME.t = 3000
N.setMode(true)
ACT.trust()
local Q = { id = 0, title = "" }
GetQuestID = function() return Q.id end
GetTitleText = function() return Q.title end
GetQuestText = function() return Q.title ~= "" and "Some words." or "" end
GetObjectiveText = function() return "" end
local function questDoc() local d = build(); return d and d.quest end

-- (a fresh session first: the add-on has not yet seen a quest window of the game's, as after the user's log in with Polestar
-- accepting everything - the case that stuck; the normal case last)
-- 2. Polestar's order: the window is already gone (its accept ran first, the nested QUEST_FINISHED came first) when our QUEST_DETAIL runs
GAME.t = GAME.t + 1
QuestFrame.shown = true; Q.id, Q.title = 102, "Dead Cultist"
ON.QUEST_FINISHED()                       -- nested inside the game's QUEST_DETAIL, reaching us first
QuestFrame.shown = false; Q.id, Q.title = 0, ""
ON.QUEST_DETAIL()                         -- ours, after
chk("an offer another add-on already accepted does not open a blank pop-up", questDoc() == nil)

-- 3. a window already stuck open (the device's state): the next build sees nothing there and closes it, no QUEST_FINISHED needed
GAME.t = GAME.t + 1
QuestFrame.shown = false; Q.id, Q.title = 103, "Kill Assassins"     -- Polestar dims and closes the game's windows
ON.QUEST_DETAIL()
chk("a third offer shows", questDoc() ~= nil)
QuestFrame.shown = false; Q.id, Q.title = 0, ""       -- gone, and the close event never came
chk("a blank, closed quest window is closed by the next build", questDoc() == nil)
chk("and stays closed", questDoc() == nil)

-- 1. the normal case: the game shows a quest offer, we show it
QuestFrame.shown = true; Q.id, Q.title = 101, "The Turncoat"
ON.QUEST_DETAIL()
local q = questDoc()
chk("a real offer shows (" .. tostring(q and q.title) .. ")", q ~= nil and q.title == "The Turncoat")
QuestFrame.shown = false; ON.QUEST_FINISHED()
chk("and closes on the game's word", questDoc() == nil)

return table.concat(out, "\n")
