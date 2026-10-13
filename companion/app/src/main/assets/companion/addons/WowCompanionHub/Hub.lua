-- WowCompanionHub / Hub.lua
-- GAME UI ON THE TOP SCREEN: which of the game's own elements are drawn, set by ONE TAP.
--
-- The companion rebuilds pieces of the game's UI on the bottom screen (the minimap, the controller
-- bars, ...), so the game's own copy can leave the top screen. USER RULING 2026-09-27: every such
-- element has a setting with three modes, kept by the APP (SavedVariables do not read back on this
-- build):
--     On       the game keeps drawing it
--     Off      hidden while the companion runs
--     Dynamic  hidden only while the room that replaces it is on the bottom screen
-- The app works out from those modes and the room on screen which elements should be hidden, and
-- says so here.
--
-- ONE TAP CARRIES THE WHOLE LAYOUT. Going from the Map room to the Cast room with both elements on
-- Dynamic changes two elements at once. Two taps for one touch would break the lock (one touch =
-- one game action), so the pad row "ui" has one cell per LAYOUT: the column number is a bit mask,
-- bit b set = the element with that bit is hidden. A layout is absolute, so a repeated or stale tap
-- changes nothing, and a finger landing on the row by accident only shows or hides UI.
--
-- COMBAT: an element that cannot change in combat waits here until combat ends. The app sends its
-- one tap and reads `pending`; it never has to tap again for it.
--
-- NOTHING IN HERE EDITS ANOTHER SESSION'S FILE. The elements are driven through functions their
-- owners already export (Map.lua: WC.hideMinimap / WC.showMinimap; ActionBar.lua on the cast
-- branch: WC.hudHide / WC.hudShow). Another addon adds its own with WC.hub.element().

local ADDON = ...
local WC = _G.WowCompanion
if type(WC) ~= "table" or type(WC.S) ~= "function" or type(WC.send) ~= "function" then return end
local S = WC.S

local HV = "hub9"           -- hub9: the PAUSE wheel's button (Spells, Home's radial pause menu); hub8: the SPELL RADIAL's button (Spells, the user's pick 2026-10-07; Home's yes); hub7: a D-pad room command takes its room's controller in the same click (Quests q16), its guestack right behind the barcmd; hub6: the "bye" document at logout (the app's waiting screen at once); hub5: the D-pad layout's four commands (map / chat / home / radial); hub2: the "hud" element carries the game's buffs, debuffs and XP bar (LATER item 20); hub3: re-checked
local ROW = "ui"
local MAX_BITS = 5          -- 2^5 = 32 layouts; a pad row has 40 columns

-- Bits are FIXED per element, so a layout means the same thing whichever addons are loaded.
-- An id that is not listed takes the lowest bit nobody holds.
local FIXED = { minimap = 0, hud = 1, tracker = 2, chat = 3 }

local hub = { els = {}, byId = {}, want = nil, pending = false, taps = 0, row = nil }
WC.hub = hub

local function say(s)
    if DEFAULT_CHAT_FRAME then DEFAULT_CHAT_FRAME:AddMessage("|cffffd100WowCompanion|r " .. s) end
end

-- ---------------------------------------------------------------- elements

local function bitFree(b)
    for _, e in ipairs(hub.els) do if e.bit == b then return false end end
    return true
end

-- Register an element. spec = {
--   hide = fn, show = fn     each returns ok[, why]; a plain return counts as ok
--   hidden = fn              true while the element is hidden
--   combatOk = bool          true when hide/show are allowed during combat
-- }
-- Returns the element's bit, or nil when there is no room for it.
function hub.element(id, spec)
    if type(id) ~= "string" or type(spec) ~= "table" then return nil end
    local e = hub.byId[id]
    if e then e.spec = spec return e.bit end
    local b = FIXED[id]
    if b == nil or not bitFree(b) then
        b = nil
        for c = 0, MAX_BITS - 1 do
            local reserved = false
            for _, fb in pairs(FIXED) do if fb == c then reserved = true end end
            if not reserved and bitFree(c) then b = c break end
        end
    end
    if b == nil then return nil end
    e = { id = id, bit = b, spec = spec }
    hub.els[#hub.els + 1] = e
    hub.byId[id] = e
    table.sort(hub.els, function(x, y) return x.bit < y.bit end)
    return b
end

local function isHidden(e)
    local ok, v = pcall(e.spec.hidden)
    return ok and v == true
end

local function wants(mask, bit)
    return math.floor(mask / 2 ^ bit) % 2 == 1
end

local function inCombat()
    local ok, v = pcall(InCombatLockdown)
    return ok and v and true or false
end

-- Move every element towards hub.want. What combat forbids stays pending.
local function apply()
    hub.pending = false
    if hub.want == nil then return end
    local combat = inCombat()
    for _, e in ipairs(hub.els) do
        local w = wants(hub.want, e.bit)
        if w ~= isHidden(e) then
            if combat and not e.spec.combatOk then
                hub.pending = true
                e.why = "in combat"
            else
                local ok, res, why = pcall(w and e.spec.hide or e.spec.show)
                if not ok then
                    e.why = "threw"
                elseif res == false then
                    e.why = type(why) == "string" and why or "refused"
                    if e.why == "in combat" then hub.pending = true end
                else
                    e.why = nil
                end
            end
        else
            e.why = nil
        end
    end
end

-- ---------------------------------------------------------------- the wire

-- Kept under 2^30: on this client a whole number from 2^31 up is not safe to format with %d
-- (safety session, 2026-09-27), and uptime in milliseconds passes that after 25 days.
local BOOT = math.floor((GetTime and GetTime() or 0) * 10) % 1073741824

local function typing()
    local ok, focus = pcall(GetCurrentKeyBoardFocus)
    return ok and type(focus) == "table"
end

-- ---------------------------------------------------------------- the riders of the "hud" element
-- THE GAME'S BUFFS, DEBUFFS AND XP BAR (LATER item 20, the user 2026-09-30: "The buffs, debuffs XP and minimap elements all need to
-- get hidden"). Home's page replaces all three (its buff rows over the small map, the bar's XP line), as it replaces the controller
-- bars, so they ride the "hud" element: the five layout bits are all taken and the pad has no free row for a sixth. ALPHA ONLY
-- (never Hide / Show / SetParent from addon code: the taint floods of 2026-09-28). Frame names from this client's own UI source
-- (Blizzard_BuffFrame, Blizzard_StatusTrackingBar; read by the cast session): weapon enchants are buttons inside BuffFrame; the
-- exp / reputation / pet bars are children of the two containers. BuffFrame and DebuffFrame are Edit Mode systems whose
-- UpdateSystemSettingOpacity sets their alpha again whenever a layout applies (login, /reload, leaving Edit Mode), so a secure
-- post-hook puts the 0 back (and keeps the opacity it set, to give back on show). A frame this client does not have is skipped and
-- reported (els[].miss), so the first run on the device says which ones exist.
local RIDERS = { "BuffFrame", "DebuffFrame", "DeadlyDebuffFrame", "ExternalDefensivesFrame",
                 "MainStatusTrackingBarContainer", "SecondaryStatusTrackingBarContainer" }
local riders = { hidden = false, saved = {}, hooked = {} }

local function riderFrame(name)
    local fr = _G[name]
    if type(fr) == "table" and type(fr.SetAlpha) == "function" then return fr end
end

local function riderZero(name, fr)
    if riders.saved[name] == nil then
        local ok, a = pcall(fr.GetAlpha, fr)
        riders.saved[name] = (ok and type(a) == "number") and a or 1
    end
    pcall(fr.SetAlpha, fr, 0)
end

local function riderHook(name, fr)
    if riders.hooked[name] then return end
    riders.hooked[name] = true
    -- Edit Mode wrote its opacity: keep it as the alpha to give back, and hide again
    if type(fr.UpdateSystemSettingOpacity) == "function" then
        hooksecurefunc(fr, "UpdateSystemSettingOpacity", function(self)
            if not riders.hidden then return end
            local ok, a = pcall(self.GetAlpha, self)
            if ok and type(a) == "number" and a > 0 then riders.saved[name] = a end
            pcall(self.SetAlpha, self, 0)
        end)
    end
    -- a frame the game shows by itself (the deadly-debuff warning)
    if type(fr.HookScript) == "function" then
        pcall(fr.HookScript, fr, "OnShow", function(self) if riders.hidden then pcall(self.SetAlpha, self, 0) end end)
    end
end

local function ridersSet(hide)
    riders.hidden = hide
    for _, name in ipairs(RIDERS) do
        local fr = riderFrame(name)
        if fr then
            riderHook(name, fr)
            if hide then riderZero(name, fr)
            elseif riders.saved[name] ~= nil then
                pcall(fr.SetAlpha, fr, riders.saved[name])
                riders.saved[name] = nil
            end
        end
    end
end

-- hub3: THE GAME CAN GIVE A RIDER ITS ALPHA BACK without Edit Mode (the user 2026-09-30, after a level-up: "The xp bar is showing
-- again", while this file still said hidden). While the riders are hidden, every heartbeat and every XP / level / rest event checks
-- each one and zeroes any the game made visible again, and the document says which frame it was and the alpha it had (els[hud].vis,
-- with a running count .fix), so the trigger can be named instead of guessed.
riders.vis, riders.fix = nil, 0
local function ridersCheck()
    if not riders.hidden then return end
    for _, name in ipairs(RIDERS) do
        local fr = riderFrame(name)
        if fr then
            local ok, a = pcall(fr.GetAlpha, fr)
            if ok and type(a) == "number" and a > 0.01 then
                riders.vis = riders.vis or {}
                riders.vis[name] = math.floor(a * 100 + 0.5) / 100
                riders.fix = riders.fix + 1
                pcall(fr.SetAlpha, fr, 0)
            end
        end
    end
end
hub.ridersCheck = ridersCheck   -- for the harness

-- the riders this client does not have (nil when it has them all)
local function ridersMissing()
    local miss = {}
    for _, name in ipairs(RIDERS) do if not riderFrame(name) then miss[#miss + 1] = name end end
    return #miss > 0 and miss or nil
end
hub.ridersSet = ridersSet       -- for the harness

local function document()
    local els = {}
    for _, e in ipairs(hub.els) do
        els[#els + 1] = { id = e.id, bit = e.bit, hidden = isHidden(e), why = e.why,
                          miss = e.id == "hud" and ridersMissing() or nil,
                          vis = e.id == "hud" and riders.vis or nil,
                          fix = (e.id == "hud" and riders.fix > 0) and riders.fix or nil }
    end
    return {
        hv = HV,
        boot = BOOT,                 -- changes at every /reload: the game forgets what was hidden
        n = hub.taps,                -- taps received since then; the app matches its tap to a reply
        want = hub.want,             -- the layout the last tap asked for; absent before the first
        pending = hub.pending,       -- part of it is waiting for combat to end
        combat = inCombat(),
        typing = typing(),           -- a text box has the keyboard: keys would be typed into it
        els = els,
        row = hub.row and ROW or nil,
        pad = hub.row and WC.pad and WC.pad.describe() or nil,
    }
end

local lastKey = nil

-- Send if changed (or forced). Does NOT push: a heartbeat ride-along costs no flush of its own.
local function emitHub(force)
    local doc = document()
    local key = WC.encode(doc)
    if key == lastKey and not force then return false end
    lastKey = key
    WC.send(WC.encode({ type = "hub", hub = doc }))
    return true
end

-- ---------------------------------------------------------------- the pad row

local function onTap(_, col)
    hub.taps = hub.taps + 1
    if type(col) == "number" and col >= 0 and col < 2 ^ MAX_BITS and col == math.floor(col) then
        hub.want = col
        apply()
    end
    emitHub(true)
    WC.push()
end

local function setup()
    -- elements other addons export, in a fixed order
    if not hub.byId.minimap and type(WC.hideMinimap) == "function" and type(WC.showMinimap) == "function" then
        hub.element("minimap", {
            hide = WC.hideMinimap, show = WC.showMinimap,
            hidden = function()
                -- Map.lua's hide is ALPHA ONLY since minimap-alpha (no Hide / Show from addon code: the
                -- bags taint flood of 2026-09-28 23:05), so the frame always reads as shown; the state
                -- is Map.lua's own flag, as the bars' is WC.hudHidden
                if type(WC.minimapIsHidden) == "function" then return WC.minimapIsHidden() == true end
                -- an older Map.lua that still used Hide / Show: read the frame, the same candidates in
                -- the same order as its hide
                for _, name in ipairs({ "MinimapCluster", "MinimapBackdrop", "Minimap" }) do
                    local fr = _G[name]
                    if fr and type(fr.Hide) == "function" then
                        return S(function() return fr:IsShown() end) == false
                    end
                end
                return false
            end,
        })
    end
    if not hub.byId.hud and type(WC.hudHide) == "function" and type(WC.hudShow) == "function" then
        hub.element("hud", {
            -- the controller bars (cast's ActionBar.lua) and their riders: the game's buffs, debuffs and XP bar (above)
            hide = function() WC.hudHide() ridersSet(true) return true end,
            show = function() WC.hudShow() ridersSet(false) return true end,
            hidden = function() return WC.hudHidden == true end,
            combatOk = true,         -- alpha only, which combat does not lock
        })
    end
    if not hub.row and WC.pad and type(WC.pad.op) == "function" then
        hub.row = WC.pad.op(ROW, ROW, onTap)
        if hub.row and type(WC.pad.onLayout) == "function" then
            WC.pad.onLayout(function() if emitHub(false) then WC.push() end end)
        end
    end
end

local f = CreateFrame("Frame")
local missing = {}
-- hub3: the XP / level / rest events are where the game redraws the status-tracking bars (ridersCheck)
local RECHECK = { PLAYER_LEVEL_UP = true, PLAYER_XP_UPDATE = true, UPDATE_EXHAUSTION = true, PLAYER_UPDATE_RESTING = true }
for _, e in ipairs({ "PLAYER_LOGIN", "PLAYER_ENTERING_WORLD", "PLAYER_REGEN_DISABLED", "PLAYER_REGEN_ENABLED",
                     "PLAYER_LEVEL_UP", "PLAYER_XP_UPDATE", "UPDATE_EXHAUSTION", "PLAYER_UPDATE_RESTING" }) do
    local ok, registered = pcall(function() return f:RegisterEvent(e) end)
    if not ok or registered == false then missing[#missing + 1] = e end
end
if #missing > 0 then
    pcall(function() WC.send(WC.encode({ type = "cap", hub = HV, missingEvents = missing })) end)
end
-- hub6: THE LOGOUT WORD (the user, 2026-10-06: "I tested logging out and the bottom screen still looks like its in game"). The
-- game's own logout lines sit in its 48 KiB log buffer for minutes (measured 08:53 -> 09:02), so the app learned of character
-- select only from 30 s of silence. PLAYER_LOGOUT fires the moment the world closes (after the game's own countdown; a /reload
-- fires it too, and the app ignores it during its own reload): ONE "bye" document, flushed at once. Registered on its own frame,
-- so a client without the event only loses this.
local byeFrame = CreateFrame("Frame")
local byeOk = pcall(function() return byeFrame:RegisterEvent("PLAYER_LOGOUT") end)
if byeOk then
    byeFrame:SetScript("OnEvent", function()
        pcall(function()
            WC.send(WC.encode({ type = "bye", why = "logout", boot = BOOT, v = HV }))
            if type(WC.flush) == "function" then WC.flush() end
        end)
    end)
end

f:SetScript("OnEvent", function(_, event)
    setup()
    if event == "PLAYER_REGEN_ENABLED" and hub.pending then apply() end
    if RECHECK[event] then
        ridersCheck()
        -- the bars may be rebuilt a moment after the event (a level-up's flash): look once more
        if C_Timer and C_Timer.After then C_Timer.After(0.5, function() ridersCheck(); if emitHub(false) then WC.push() end end) end
    end
    if emitHub(false) then WC.push() end
end)

-- RIDE THE CORE HEARTBEAT, the way Quests does: whatever is sent in this post-hook lands in the
-- snapshot's own pad burst. Only on reasons that always emit.
local RIDE = { heartbeat = true, login = true, zone = true, manual = true }
if type(WC.snapshot) == "function" then
    hooksecurefunc(WC, "snapshot", function(reason)
        if RIDE[reason] then setup(); ridersCheck(); emitHub(true) end
    end)
end

-- hub4: THE BAR COMMANDS (the user, 2026-09-30: "I want to add some keybinds to the commands to basically navigate the dsforever
-- homebar left or right, basically letting me use the home bar cursor with the dpad"). Spells' Controller tab makes the macros
-- "DS Bar Left / Right / Open" (`/click WowCompanionBarLeft` ...) and the player puts them on any pad button. Each click is ONE
-- barcmd document; the app moves its bar cursor (or opens the room under it). Plain buttons, no protected call, no game frame
-- touched, so a macro may click them in combat too. Up AND down registered: a bare /click fires once whatever ActionButtonUseKeyDown says.
local barN = 0
-- hub7: THE ROOM'S CONTROLLER TAKEN IN THE SAME CLICK (Home's plan A, the user's go 2026-10-06: measured, a room's guest hold
-- asked by the app's tap was in place only after a median 462 ms, so the room's first half second ran on the slow route). The
-- command's room guest is held in-game at once through Quests' WC.questGuests.hold (q16: the same code as the qact columns, one
-- guestack answer) RIGHT BEHIND the barcmd in the same flush. ORDER MATTERS: the app opens the room on the barcmd, then the answer
-- finds it shown; an answer first would find no room and be freed at once (the app's refree rule). The app frees it too if the
-- command did not open that room (a toggle closing it): the same rule.
local HOLDS = { map = "home", chat = "chat", home = "bar", radial = "radial", sradial = "radial", pause = "radial" }   -- hub8: the spell radial, hub9: the pause wheel, the same guest
local function barCmd(k)
    barN = barN + 1
    WC.send(WC.encode({ type = "barcmd", k = k, n = barN, boot = BOOT, v = HV }))
    local gid, api = HOLDS[k], WC.questGuests
    if gid and type(api) == "table" and type(api.hold) == "function" then pcall(api.hold, gid) end
    WC.push()
end
-- hub5: THE D-PAD LAYOUT (the user, 2026-10-05: "I want right to open that home map module. Left to open the chat module, down to
-- place the home bar selection down on the swirly to start, and then up to be ... a radial menu of all of my currently available
-- consumables"; all four in fights too). Spells' router clicks these four from the D-pad while the Controller page's "DS WoW
-- toolbar" box is on; the app opens the map module / the Chat room / the bar cursor on the swirl / the consumables radial.
for name, k in pairs({ WowCompanionBarLeft = "left", WowCompanionBarRight = "right", WowCompanionBarOpen = "open",
                       WowCompanionPadMap = "map", WowCompanionPadChat = "chat", WowCompanionPadHome = "home",
                       WowCompanionPadRadial = "radial",
                       -- hub8 (Spells, AGENT_LOG): the SPELL RADIAL -- put on a button from the Controller room's Extra tab
                       WowCompanionPadSpellRadial = "sradial",
                       -- hub9 (Spells, AGENT_LOG e17fb7b): the PAUSE wheel -- Start on plain Home (Spells' router, PadMap m37)
                       WowCompanionPadPause = "pause" }) do
    local b = CreateFrame("Button", name, UIParent)
    b:RegisterForClicks("AnyUp", "AnyDown")
    b:SetScript("OnClick", function() barCmd(k) end)
end
hub.barCmd = barCmd     -- for the harness

-- /wcui          print the elements and their state
-- /wcui <mask>   ask for a layout by hand, exactly as a tap would (0 = everything shown)
SLASH_WCUI1 = "/wcui"
SlashCmdList["WCUI"] = function(arg)
    setup()
    local n = tonumber((arg or ""):match("^%s*(%d+)%s*$"))
    if n then onTap(nil, n) end
    for _, e in ipairs(hub.els) do
        say(e.id .. " (bit " .. e.bit .. ")  " .. (isHidden(e) and "hidden" or "shown") ..
            (e.why and ("  |cffff5555" .. e.why .. "|r") or ""))
    end
    say("layout " .. tostring(hub.want) .. "  pad row " .. tostring(hub.row) ..
        (hub.pending and "  |cffffd100waiting for combat to end|r" or ""))
    if not n then emitHub(true); WC.push() end
end
