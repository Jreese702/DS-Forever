-- i161 (bags-de1, the Disenchant grid; the user's pick 3a, 2026-11-16: "With your Auto Loot on, the mats go straight to your bags and a small
-- strip shows what you got. With it off, our loot pop-up comes up ... then the grid comes back"): capture v21 spec.lootPass.
--   * an AUTO loot while a holder that asks holds: the holder keeps the pad (no lost), the document carries no loot pop-up
--   * a loot that is not auto: the holder is lost to the pop-up ("npc"), as before
--   * a holder that did not ask (or answers false): lost, as before
--   * an auto loot still open LOOT.PASS_S later (full bags): the ordinary path (lost, the pop-up)
--   * the loot window left on the top screen (Hub > Loot window: Top): the holder keeps the pad, the game keeps its window
local N, ACT, ON, LOOT = __N, __ACT, __ON, __LOOT
local WC = WowCompanion
local out = {}
local function chk(n, c) out[#out + 1] = (c and "PASS " or "FAIL ") .. n end
local FrameMT = { __index = {
    GetAlpha = function(self) return self.alpha or 1 end, SetAlpha = function(self, a) self.alpha = a end,
    IsShown = function(self) return self.shown == true end, GetRect = function() return 0, 0, 100, 100 end,
    GetEffectiveScale = function() return 1 end, GetChildren = function() end, GetRegions = function() end,
    HookScript = function() end, GetParent = function() return nil end,
} }
rawset(_G, "LootFrame", setmetatable({ alpha = 1, shown = false }, FrameMT))
WowCompanionDB = { lootTop = false }
GAME.t = 4000
N.setMode(true)
ACT.trust()
chk("the capture says v21 (spec.lootPass)", WC.capture.version >= 21)

local lost = {}
local keep = true
local function spec(withPass)
    return { press = function() end, lost = function(why) lost[#lost + 1] = why end,
             lootPass = withPass and function() return keep end or nil }
end
local function take(withPass)
    lost = {}
    local ok, got = pcall(WC.capture.take, "bags", spec(withPass))
    return ok and got
end
local function holder() return WC.capture.state().holder end
local function open(auto)
    GAME.t = GAME.t + 5
    LootFrame.shown = true
    if ON.LOOT_OPENED then pcall(ON.LOOT_OPENED, auto) end
    N.early("loot")
end
local function close()
    LootFrame.shown = false
    if ON.LOOT_CLOSED then pcall(ON.LOOT_CLOSED) end
    N.sync(false, nil)
    N.unhide()
end
local built = __build
local function docLoot()
    local ok, d = pcall(built)
    if ok and type(d) == "table" then return d.loot, d end
    return nil, nil
end

-- 1) AUTO loot, the holder asked: kept
local took = take(true)
chk("the grid takes the pad (" .. tostring(took) .. ", holder " .. tostring(holder()) .. ")", took and holder() == "bags")
open(true)
chk("an auto loot while the grid holds: no lost (" .. #lost .. "), still the holder (" .. tostring(holder()) .. ")",
    #lost == 0 and holder() == "bags")
chk("... the pass is marked (" .. tostring(N.lootPassAt) .. ")", N.lootPassAt ~= nil)
local lt, d = docLoot()
chk("... and the document carries no loot pop-up (" .. tostring(lt) .. "), the pass counted (" .. tostring(d and d.lootPass and d.lootPass.n) .. ")",
    lt == nil and d and d.lootPass and d.lootPass.n == 1)
close()
chk("the auto loot closed: the pass cleared, the grid still holds", N.lootPassAt == nil and holder() == "bags" and #lost == 0)

-- 2) the bags full: still open after LOOT.PASS_S -> the ordinary path (lost to the pop-up)
open(true)
local at = N.lootPassAt
chk("a second auto loot kept too", at ~= nil and #lost == 0)
N.lootPassLate(at)
chk("still open " .. tostring(LOOT.PASS_S) .. " s later (full bags): the grid lost to the pop-up (" .. tostring(lost[1]) .. ")",
    lost[1] == "npc" and holder() ~= "bags" and N.lootPassAt == nil)
lt = docLoot()
chk("... and the pop-up is in the document", lt ~= nil)
close()

-- 3) NOT auto: lost at once, as before
take(true)
open(false)
chk("a loot that is not auto: the grid lost to the pop-up (" .. tostring(lost[1]) .. ")", lost[1] == "npc" and N.lootPassAt == nil)
close()

-- 4) a holder that did not ask, and one that answers false: lost, as before
take(false)
open(true)
chk("an auto loot, a holder without lootPass: lost (" .. tostring(lost[1]) .. ")", lost[1] == "npc")
close()
keep = false
take(true)
open(true)
chk("an auto loot, lootPass answers false (not the grid): lost (" .. tostring(lost[1]) .. ")", lost[1] == "npc")
close()
keep = true

-- 5) a stale late check (the window closed and another opened meanwhile) does nothing
take(true)
open(true)
local first = N.lootPassAt
close()
open(true)
N.lootPassLate(first)
chk("a late check for an earlier window does nothing (lost " .. #lost .. ")", #lost == 0 and N.lootPassAt ~= nil)
close()

-- 6) the loot window on the top screen (Hub > Loot window: Top): the holder keeps the pad, the game keeps its window
WowCompanionDB.lootTop = true
take(true)
open(false)
chk("loot on the top screen: the grid keeps the pad (" .. #lost .. "), no pass marked", #lost == 0 and N.lootPassAt == nil and holder() == "bags")
close()
WowCompanionDB.lootTop = false

-- 7) in a fight an auto loot is never passed (the fight's own loot rules)
InCombatLockdown = function() return true end
LOOT.auto = true
chk("in a fight: no pass", N.lootPass() == false)
InCombatLockdown = function() return false end
LOOT.auto = nil
pcall(WC.capture.release, "bags", "test")
return table.concat(out, "\n")
