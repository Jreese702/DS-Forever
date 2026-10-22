-- test76 ("the loot is popping up in both places"): with the Loot window hidden on the top screen (lootTop off) and the companion in
-- charge (mode, proof), a loot window that opens is dimmed on the top screen by the opening event, by the window's own show and by
-- its fade; in a fight it is not (the controller cannot be taken: v10's ENTER wait) and the log says "combat"
local N, ACT, ON = __N, __ACT, __ON
local out = {}
local function chk(n, c) out[#out + 1] = (c and "PASS " or "FAIL ") .. n end
local hooks = {}
local FrameMT = { __index = {
    GetAlpha = function(self) return self.alpha or 1 end, SetAlpha = function(self, a) self.alpha = a end,
    IsShown = function(self) return self.shown == true end, GetRect = function() return 0, 0, 100, 100 end,
    GetEffectiveScale = function() return 1 end, GetChildren = function() end, GetRegions = function() end,
    HookScript = function(self, ev, fn) hooks[ev] = fn end,
} }
rawset(_G, "LootFrame", setmetatable({ alpha = 1, shown = false }, FrameMT))
WowCompanionDB = { lootTop = false }
GAME.t = 2000
N.setMode(true)
ACT.trust()
N.lootHook()                                         -- (the harness's LootFrame came after Interact's load)
local function cap()
    for i = #SENT, 1, -1 do local t = SENT[i]; if t.type == "cap" and t.probe == "npchide" and t.w == "loot" then return t end end
end
-- 1) the event first, then the game shows its window
if ON.LOOT_OPENED then pcall(ON.LOOT_OPENED) end
N.early("loot")
LootFrame.shown = true
if hooks.OnShow then hooks.OnShow(LootFrame) end
local c = cap()
chk("loot opens (hidden on the top screen, ours in charge): the game's LootFrame dimmed (" .. tostring(LootFrame.alpha) .. "), logged ("
    .. tostring(c and c.ok) .. ", " .. tostring(c and c.how) .. ")", LootFrame.alpha == 0 and c and c.ok == true)
if ON.LOOT_CLOSED then pcall(ON.LOOT_CLOSED) end
LootFrame.shown = false
if hooks.OnHide then hooks.OnHide(LootFrame) end
N.unhide()
-- 2) the game shows its window BEFORE our event handler (the client's order): the OnShow hook dims it
LootFrame.alpha = 1
GAME.t = GAME.t + 5
if ON.LOOT_OPENED then pcall(ON.LOOT_OPENED) end
LootFrame.shown = true
if hooks.OnShow then hooks.OnShow(LootFrame) end
chk("the game's show before our event: the OnShow hook dims it (" .. tostring(LootFrame.alpha) .. ", by " .. tostring(N.lootDiag.by) .. ")",
    LootFrame.alpha == 0)
N.early("loot")
chk("... and the event after it keeps it dimmed (" .. tostring(LootFrame.alpha) .. ")", LootFrame.alpha == 0)
if ON.LOOT_CLOSED then pcall(ON.LOOT_CLOSED) end
LootFrame.shown = false
if hooks.OnHide then hooks.OnHide(LootFrame) end
N.unhide()
-- 3) IN A FIGHT (still flagged in combat a moment after the kill): the game keeps its window, the log says why
LootFrame.alpha = 1
GAME.t = GAME.t + 5
InCombatLockdown = function() return true end
if ON.LOOT_OPENED then pcall(ON.LOOT_OPENED) end
LootFrame.shown = true
if hooks.OnShow then hooks.OnShow(LootFrame) end
N.early("loot")
c = cap()
chk("looting while still flagged in combat: the game keeps its window (" .. tostring(LootFrame.alpha) .. "), logged why ("
    .. tostring(c and c.why) .. ")", LootFrame.alpha == 1 and c and c.ok == false and c.why == "combat")
InCombatLockdown = function() return false end
return table.concat(out, "\n")
