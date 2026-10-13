-- WowCompanionQuests / Tracker.lua
-- THE GAME'S QUEST TRACKER as a hub element ("tracker", bit 2): hidden on the top screen while the
-- companion shows the quests instead. USER RULING 2026-09-28 ~20:35 (via the tracking session):
-- "Yes, hide them while Home shows" -- the tracker, chat and controller bars. The app decides WHEN
-- (On / Off / Dynamic per element, in Settings); the hub (WowCompanionHub/Hub.lua) turns one tap into
-- hide / show calls; this file only knows HOW for the tracker.
--
-- ALPHA ONLY. `ObjectiveTrackerFrame:SetAlpha(0)`, never Hide / SetParent / a field write. Blizzard's own
-- tracker code (Blizzard_ObjectiveTracker, read offline) Show()s and Hide()s the root as quests come and
-- go but never sets the root's alpha -- it only sets its background's (NineSlice) -- so our 0 stays put,
-- and a /reload brings the tracker back (fail-open). SetAlpha is a C method: nothing Lua-side is written
-- into a Blizzard table, so nothing Blizzard reads is tainted. (The 2026-09-28 taint flood came from an
-- addon slash command run through chat, not from alpha.)
--
-- NOT IN COMBAT. The tracker holds the game's secure quest-item buttons, so nothing here touches it while
-- combat lockdown is on: combatOk = false, and hide / show refuse "in combat" on their own as well. The
-- hub keeps the change pending and applies it when combat ends.
--
-- LOAD ORDER. The hub addon lists this addon as an optional dependency, so it loads AFTER us: WC.hub does
-- not exist yet at file load. Register on the hub's ADDON_LOADED (or at PLAYER_LOGIN at the latest).

local ADDON = ...
local WC = _G.WowCompanion
if type(WC) ~= "table" then return end

local hidden = false      -- our own state; a /reload resets it together with the frame's alpha

local function frame()
    local f = _G.ObjectiveTrackerFrame
    if type(f) == "table" and type(f.SetAlpha) == "function" then return f end
end

local function set(alpha)
    local ok, v = pcall(InCombatLockdown)
    if ok and v then return false, "in combat" end
    local f = frame()
    if not f then return false, "no tracker frame" end
    local okA = pcall(f.SetAlpha, f, alpha)
    if not okA then return false, "SetAlpha threw" end
    hidden = (alpha == 0)
    return true
end

local SPEC = {
    hide = function() return set(0) end,
    show = function() return set(1) end,
    hidden = function() return hidden end,
    combatOk = false,
}

local registered = false
local function register()
    if registered then return true end
    if type(WC.hub) ~= "table" or type(WC.hub.element) ~= "function" then return false end
    registered = WC.hub.element("tracker", SPEC) ~= nil
    return registered
end

if not register() then
    local f = CreateFrame("Frame")
    f:RegisterEvent("ADDON_LOADED")
    f:RegisterEvent("PLAYER_LOGIN")
    f:SetScript("OnEvent", function(self)
        if register() then self:UnregisterAllEvents() end
    end)
end

-- for the test harness
WC.trackerElement = SPEC
