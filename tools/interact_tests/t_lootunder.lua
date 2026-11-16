-- i161 / capture v21 (wk-containers; the user, 2026-11-16: "it should just open the loot menu inside of the bags and then keep the player
-- inside of the bag room"): a loot window the holding room opened itself (spec.lootUnder() true) SUSPENDS the room instead of losing it:
-- the npc hold drives the loot window, the room's windows stay hidden, the document says under = the room; when the loot window closes the
-- room resumes exactly as it was (rel by "resume", spec.resumed("loot"), never lost, never "B"). Every other case keeps today's way.
local N, ACT, ON, build = __N, __ACT, __ON, __build
local WC = WowCompanion
local out = {}
local function chk(n, c) out[#out + 1] = (c and "PASS " or "FAIL ") .. n end
local FrameMT = { __index = {
    GetAlpha = function(self) return self.alpha or 1 end, SetAlpha = function(self, a) self.alpha = a end,
    IsShown = function(self) return self.shown == true end, GetRect = function() return 0, 0, 100, 100 end,
    GetEffectiveScale = function() return 1 end, GetChildren = function() end, GetRegions = function() end,
    HookScript = function() end,
} }
rawset(_G, "LootFrame", setmetatable({ alpha = 1, shown = false }, FrameMT))
rawset(_G, "ContainerFrameCombinedBags", setmetatable({ alpha = 1, shown = true }, FrameMT))
WowCompanionDB = { lootTop = false }
GAME.t = 5000
N.setMode(true)
ACT.trust()
N.lootHook()
local cap = WC.capture
local LOST, RESUMED, MINE = {}, {}, true
local function spec(extra)
    local s = { press = function() end, lost = function(w) LOST[#LOST + 1] = w end, hide = { "ContainerFrameCombinedBags" },
                lootUnder = function() return MINE end, resumed = function(w) RESUMED[#RESUMED + 1] = w end }
    for k, v in pairs(extra or {}) do s[k] = v end
    return s
end
local function reset() LOST, RESUMED, MINE = {}, {}, true end
local function lootOpen()
    GAME.t = GAME.t + 1
    if ON.LOOT_OPENED then pcall(ON.LOOT_OPENED) end
    LootFrame.shown = true
    N.early("loot")
    N.sync(true, { loot = {} })           -- its document, while the loot window is open
end
local function lootClose()
    GAME.t = GAME.t + 1
    if ON.LOOT_CLOSED then pcall(ON.LOOT_CLOSED) end
    LootFrame.shown = false
    N.sync(false, nil)                    -- the document after it: nothing open
end

chk("the capture is v21 (or later)", (tonumber(cap.version) or 0) >= 21)
chk("the version is i161 or later", tonumber(WC.interactIV:match("^i(%d+)$")) >= 161)

-- 1) the room's own loot window: suspended, then resumed as it was
local ok = cap.take("bags", spec())
chk("Bags holds, its bag window hidden (" .. tostring(ContainerFrameCombinedBags.alpha) .. ")",
    ok == true and cap.state().holder == "bags" and ContainerFrameCombinedBags.alpha == 0)
lootOpen()
local st = cap.state()
chk("its own loot window: the room is suspended under it, not lost (holder " .. tostring(st.holder) .. ", under " ..
    tostring(st.under) .. ", lost " .. tostring(LOST[1]) .. ")", st.holder == "npc" and st.under == "bags" and #LOST == 0)
chk("the bag window stays hidden (" .. tostring(ContainerFrameCombinedBags.alpha) .. ") and the loot window is hidden too (" ..
    tostring(LootFrame.alpha) .. ")", ContainerFrameCombinedBags.alpha == 0 and LootFrame.alpha == 0)
local okD, d = pcall(build)
chk("the document says it: holder npc, under bags, the loot window in it (" .. tostring(okD and d.holder) .. ", " ..
    tostring(okD and d.under) .. ", " .. tostring(okD and d.loot ~= nil) .. ")", okD and d.holder == "npc" and d.under == "bags" and d.loot ~= nil)
st = cap.state()
chk("(the document's own sync kept the suspension)", st.holder == "npc" and st.under == "bags")
local relBefore = N.rel and N.rel.n or 0
lootClose()
st = cap.state()
chk("the loot window closed: the room holds again (holder " .. tostring(st.holder) .. ", under " .. tostring(st.under) ..
    ", resumed " .. tostring(RESUMED[1]) .. ", lost " .. tostring(LOST[1]) .. ")",
    st.holder == "bags" and st.under == nil and RESUMED[1] == "loot" and #LOST == 0)
chk("released by 'resume' to bags, never 'B' (the app stays in the room)",
    N.rel and N.rel.n == relBefore + 1 and N.rel.by == "resume" and N.rel.to == "bags")
chk("the bag window still hidden (" .. tostring(ContainerFrameCombinedBags.alpha) .. "), the loot window's alpha back (" ..
    tostring(LootFrame.alpha) .. ")", ContainerFrameCombinedBags.alpha == 0 and LootFrame.alpha == 1)

-- 2) closed with B: still a resume, the room stays
reset()
lootOpen()
N.backAt = GAME.t
lootClose()
st = cap.state()
chk("closed with B: the room resumes all the same (by " .. tostring(N.rel and N.rel.by) .. ", holder " .. tostring(st.holder) .. ")",
    st.holder == "bags" and N.rel.by == "resume" and #LOST == 0 and RESUMED[1] == "loot")

-- 3) not the room's own loot (lootUnder false): today's way, the room is lost to the npc window
reset()
MINE = false
lootOpen()
st = cap.state()
chk("a loot window the room did not open: the room is lost ('npc') as before (holder " .. tostring(st.holder) .. ", under " ..
    tostring(st.under) .. ")", LOST[1] == "npc" and st.holder == "npc" and st.under == nil)
lootClose()
chk("... and its close lets go as before (holder " .. tostring(cap.state().holder) .. ", nothing resumed)",
    cap.state().holder == nil and #RESUMED == 0)

-- 4) a room without spec.lootUnder (any other room, an older Bags): today's way
reset()
cap.take("social", { press = function() end, lost = function(w) LOST[#LOST + 1] = w end })
lootOpen()
chk("a room that does not ask: lost ('npc') as before", LOST[1] == "npc" and cap.state().under == nil)
lootClose()

-- 5) a loot window opening in a fight: today's way (never suspended)
reset()
cap.take("bags", spec())
InCombatLockdown = function() return true end
lootOpen()
chk("in a fight: the room is lost ('npc') as before, never suspended", LOST[1] == "npc" and cap.state().under == nil)
InCombatLockdown = function() return false end
lootClose()

-- 6) a fight starting while the room is suspended (PLAYER_REGEN_DISABLED runs N.lootUnderDrop): the room goes as before, the loot holds on
reset()
cap.take("bags", spec())
lootOpen()
chk("(suspended again)", cap.state().under == "bags")
N.lootUnderDrop("npc")
st = cap.state()
chk("the fight's start lets the suspended room go ('npc'), the loot window still holds (holder " .. tostring(st.holder) .. ")",
    LOST[1] == "npc" and st.under == nil and st.holder == "npc")
lootClose()
chk("... and the loot window's close resumes nothing", #RESUMED == 0 and cap.state().holder == nil)

-- 7) a take that waited while the loot window held waits on BEHIND the resumed room, as v20's resume leaves it (review of i161: it used to
-- win the resume - the room told 'taken', the app sent Home)
reset()
cap.take("bags", spec())
lootOpen()
local okW, why = cap.take("social", { press = function() end, lost = function() end })
chk("another room's take meanwhile waits (" .. tostring(why) .. ")", okW == true and why == "queued")
lootClose()
st = cap.state()
chk("the loot window closed: the room resumes all the same, never told 'taken' (holder " .. tostring(st.holder) .. ", lost " ..
    tostring(LOST[1]) .. ")", st.holder == "bags" and st.under == nil and RESUMED[1] == "loot" and #LOST == 0)
chk("... the take still waits, behind the room (" .. tostring(N.wait and N.wait.id) .. ")", N.wait ~= nil and N.wait.id == "social")
N.grantWait()
chk("... never granted while the room holds (" .. tostring(cap.state().holder) .. ")", cap.state().holder == "bags")
cap.release("bags", "room")
N.grantWait()
chk("... the room lets go: the waiter holds at the next document (" .. tostring(cap.state().holder) .. ")", cap.state().holder == "social")
cap.release("social")

-- 7b) a take ALREADY waiting behind the room when its box was opened (a pop-up queued behind Bags): the same - it waits on
reset()
cap.take("bags", spec())
local okP, whyP = cap.take("dialogs", { press = function() end, lost = function(w) LOST[#LOST + 1] = "dialogs:" .. tostring(w) end })
chk("a pop-up's take waits behind the room (" .. tostring(whyP) .. ")", okP == true and whyP == "queued")
lootOpen()
lootClose()
st = cap.state()
chk("its box looted: the room resumes, the pop-up's take still waits, nobody told lost (holder " .. tostring(st.holder) .. ", wait " ..
    tostring(N.wait and N.wait.id) .. ", lost " .. tostring(LOST[1]) .. ")",
    st.holder == "bags" and N.wait ~= nil and N.wait.id == "dialogs" and #LOST == 0 and RESUMED[1] == "loot")
cap.release("dialogs")
cap.release("bags", "room")

-- 8) an overlay that waited (the pause wheel): it goes over the resumed room, which resumes after it (v20)
reset()
cap.take("bags", spec())
lootOpen()
cap.take("pad:radial", { press = function() end, lost = function() end, overlay = true })
lootClose()
st = cap.state()
chk("a waiting overlay goes over the resumed room (holder " .. tostring(st.holder) .. ", under " .. tostring(st.under) .. ")",
    st.holder == "pad:radial" and st.under == "bags" and RESUMED[1] == "loot" and #LOST == 0)
cap.release("pad:radial")
chk("... and the room resumes after it", cap.state().holder == "bags" and #LOST == 0)

-- 9) the room lets go while suspended (another room picked by touch): dropped; the loot window stays hidden while ours holds
reset()
lootOpen()
chk("(suspended again)", cap.state().under == "bags")
local okR = cap.release("bags", "room")
st = cap.state()
chk("the room lets go while suspended: dropped (under " .. tostring(st.under) .. "), the loot window still hidden (" ..
    tostring(LootFrame.alpha) .. "), the npc still holds", okR == true and st.under == nil and LootFrame.alpha == 0 and st.holder == "npc")
lootClose()
chk("... and the loot window's close lets go (no resume, nothing lost)", cap.state().holder == nil and #RESUMED == 0 and #LOST == 0)

-- 10) the companion not in charge of the NPC windows: the game's own loot window, the room lost as before
reset()
cap.take("bags", spec())
N.setMode(false)
lootOpen()
chk("NPC windows not ours: never suspended (the game's loot window)", cap.state().under == nil and LOST[1] == "npc")
N.setMode(true)
lootClose()

-- 11) THE DOUBLE B (review of i161): the loot window's B is our secure close acting on the PRESS (its PostClick sets N.backAt), so the
-- room may resume before that B's release: the resume marks it, and N.resumeEaten (asked by the frame and the binding route) answers true
-- once for PAD2's release - handed on as its press went, never the room's B. A new press of B clears the mark; other keys never use it;
-- a mark older than RESUME_EAT_S is dropped; a loot window closed without B (A / X took it all, walked away) marks nothing
reset()
cap.take("bags", spec())
lootOpen()
N.backAt = GAME.t
lootClose()
chk("closed by B: the room resumed and that B's release is marked (" .. tostring(N.resumeEat and N.resumeEat.k) .. ")",
    cap.state().holder == "bags" and N.resumeEat ~= nil and N.resumeEat.k == "PAD2")
chk("... its release is handed on, once", N.resumeEaten("PAD2", false) == true and N.resumeEaten("PAD2", false) == false)
lootOpen(); N.backAt = GAME.t; lootClose()
chk("... a new press of B first clears the mark: that press is the room's", N.resumeEaten("PAD2", true) == false and N.resumeEat == nil
    and N.resumeEaten("PAD2", false) == false)
lootOpen(); N.backAt = GAME.t; lootClose()
chk("... another key's edges never use it", N.resumeEaten("PAD1", false) == false and N.resumeEaten("PAD3", true) == false
    and N.resumeEat ~= nil)
GAME.t = GAME.t + 10
chk("... a mark older than RESUME_EAT_S is dropped, not used", N.resumeEaten("PAD2", false) == false and N.resumeEat == nil)
lootOpen(); N.backAt = nil; lootClose()
chk("closed without B (looted, walked away): nothing marked", N.resumeEat == nil and cap.state().holder == "bags")
cap.release("bags", "room")
return table.concat(out, "\n")
