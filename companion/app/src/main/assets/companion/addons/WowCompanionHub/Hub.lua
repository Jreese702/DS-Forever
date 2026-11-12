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

-- hub40 (fix/top-party, the user 2026-11-12: the party bar "a mile long"): every top party bar one column wide, re-sized on a K change.
-- hub39 (int/1429): party-combat hub38 (the player / pet row, the Select side tab) with combat-nav's PadMap m134-m136
local HV = "hub42"          -- hub42: THE TOP PARTY BLOCK MOVES WITH PARTY MODE (the user, 2026-11-12: "party members are listed in ONE column ... on the LEFT side"; in Party mode they "fade out of the left column and fade back in UNDER the player's HUD bar"; "the player should not have their HP bar on the right side with party mode any more"; the Select tab: their pick B): out of Party mode party1..4 one a row, top to bottom, in the top-left column (PF.X / PF.Y as before), no Select tab; Party mode on: the block fades out (FADE_S 0.2), moves while unseen and fades in under the HUD bar (Trail prd15 WC.prd.partyGeo: party1's bar under the HP bar's left), two a row - [party1][party2] / [party3][party4] - and the reverse when it goes off (alpha only, our own plain frames: allowed in a fight; no secure frame moves); the game's side tab, lit, at the grid's LEFT edge, the rows' full height (none on the screen's edge any more); the grid tells Trail the room it takes (WC.prd.partyRoom: the HUD's aura row under it); still no self row, every bar one column's (W_BAR), raids nothing; hub41: THE TOP BLOCK'S REVIEW FIXES (the user, 2026-11-12, the top screen's party renders): the Select tab follows a K change too (PF.tabScale: its place, width, cap slices and glyphs again at today's K; before, it stayed at the scale it was made with while the rows moved), and the rows' pitch 55 -> 64 so Party mode's gold ring on a row ends above the next row's name (it cut through the name); hub40: THE TOP PARTY BARS ONE COLUMN WIDE (the user, 2026-11-12: "I had someone join my group on an old build and their health bar was a mile long"): a full row's bar spanned both columns (W_FULL 510 px, hub35 / 1428 on), so one member alone, or the first rows of a party of 3 / 4, had a bar wider than the target bar; now every party bar is one column's (W_BAR 194), a full row left-aligned, and PF.place re-sizes a row when K changes (PLAYER_ENTERING_WORLD); hub39: int/1429 (hub38 + combat-nav). hub38: THE PLAYER / PET ROW AND THE SELECT TAB IN THE GAME'S ART (the user, 2026-11-11, after using 1428: "I think the pet should be to the right of the player" and "The side panel around the select button looks like an AI border. It needs to use wow assets and sit outside of the player brand look more like its attached to the side of the screen"): the top block follows PadMap m134's rows (you + the pet share the first row, the party members under it: 1-2 full rows, 3: a full row then a shared one, 4: 2x2), so the party block no longer depends on the pet; the top Select tab is the game's own side tab (atlas common-sidetab-c60-2x, sliced top / middle / bottom so its bevels keep their shape; lit = common-sidetab-selected-c60-2x over it), flush on the screen's left edge (x 0); no drawn fill or edge any more; hub37: hub35 (the party grid) and hub36 (Polestar's hide sticks) merged, both kept. hub35: THE TOP PARTY BLOCK (the user, 2026-11-11: "the other 4 party members are in a square shape in the top left", then the adaptive rows "when one two or three full sized, when 4, bottom 2 consolidate, when 5 the next 2 consolidate"): ONLY the party members, in the bottom's own arrangement (PadMap m131's entries you / pet / party1..4: the same rows and left / right sides, the rows with no party member dropped) as a compact block in the top left - a full row's bar spans both columns, a shared row's halves one each; no self row any more (picking you / your pet boxes the HUD's bars / pet strip: Trail.lua prd14); the Select tab beside the block (its rows; one row's height while solo with Party mode on); hub34: THE TOP PARTY COLUMN (party_design.md D and the user's decisions 2026-11-11): you, then party1..4, one row each in the bottom's order, every row the target bar's builder (npBar + the HUD rim, the level in a hudBox, the name above the lip, the HUD's green re-tinted every step); your own row while grouped or whenever Party mode is on; Party mode on: each row's LB glyph, a gold ring on the pick (PadMap box.u) and a white chevron at your target; dead / offline / out of range (PadMap's one rng) as the bottom; an 18 px Select tab beside the column (off / on / not bound); in a raid nothing; the combat log's switch calls retired (PadMap m129); hub33: THE PET'S STRIP IN THE HUD (the user, 2026-11-11: "find a way to include Pet HP in the HUD, and better distinguish the player HUD vs Pet HUD"): "uisw" 32 / 33 -> WC.npWant.pet (Trail.lua prd11: the pet's strip under the resource bar, on unless turned off); the game's pet frame is its own group now ("pet"): as the player frame (hidden with it while our strip stands in; a review fix 2026-11-11: never hidden by our strip alone); the doc's sw.pet / set.pet; hub32: THE GAME'S COMBO POINTS UNDER A HIDDEN TARGET FRAME (issue #120, a Rogue: "I can't see the combo points that are accumulated during combat"; 2026-11-11): the game draws them on ComboFrame, a child of TargetFrame (Blizzard's Classic FrameXML), so HUD mode's target switch (TargetFrame at alpha 0) hid them with it; with our target bar off nothing showed them. Now, while the game's host frame is hidden by us and our target bar (its own pips) is off, the combo frame ignores its parent's alpha (SetIgnoreParentAlpha, alpha only like the rest of HUD mode); otherwise it follows it again; hub31: HIDE POLESTAR ON THE TOP SCREEN (the user, 2026-11-11: "one other setting to hide the polestar UI from the top"): "uisw" 30 / 31 - every top-level frame Polestar names (PolestarGuideFrame, its arrow, its pad hints, toasts ...) at alpha 0 while on, and kept there when it shows again; off gives each its alpha 1 back; alpha only, nothing of Polestar changed; the doc's sw.ps; hub30: the third choice's RULES (the user, 2026-11-10: "it would be better if it stay on out of combat incase someone else pulls me into combat, and it should turn off when I target something out of combat AND start attack and then swap back when an ability is used before that ability fires" ... "make sure hunters mark does not trigger it to swap back on"): on by default and after every fight; OFF when an attack starts out of a fight with a target (PLAYER_ENTER_COMBAT); back ON at an ability's send (UNIT_SPELLCAST_SENT, before it fires) - never for Hunter's Mark, Auto Shot or Attack; hub29 (replaced by hub30): "In combat" turns the option on AT THE PULL (the user, 2026-11-10: "I pull with arcane shot and then when they get close, my auto attack melee is not on"): a spell you cast, Auto Shot or a melee swing starting out of a fight writes 1 right then (allowed: no lockdown yet), and 0 again 8 s later if no fight came of it; hub28: COMBAT AUTO SHOT "In combat" (the user, 2026-11-10: "add a third toggle to the hunter option to basically only turn it on during combat"): "uisw" 28 / 29 - while on, the game's autoRangedCombat is written 1 as a fight starts and 0 when it ends (and 0 at once out of a fight); the doc's sw.shotf, sw.shotfk = whether the fight's write took; hub27: the Party mode switch also starts / stops the combat log (PadMap m112); hub26: DS FOREVER'S OWN PARTY FRAMES AND TARGET BAR (WC.uf, at the end of this file; "uisw" 18 / 19 our party frames, 20 / 21 our target bar; while one is on, the game's own frames of that group stay hidden); hub25: PARTY MODE is an EXPERIMENTAL switch, off by default (the user, 2026-11-07: "Make the party mode an experimental toggle that is off by default"): "uisw" 16 / 17 -> WC.partyOk, which PadMap reads (Select toggles Party mode only while it is true); hub24: the Start attack switch reported while OFF (it read as absent: no QOL row); hub23: Combat Auto Shot for EVERY class (the user: "other classes use a bow"); hub22: START ATTACK ON A HOSTILE TARGET ("uisw" 12 / 13, the secure cell "atkgo") and the hunter's AUTO SHOT (the game's own autoRangedCombat, "uisw" 14 / 15); hub21: a one-time probe of the frames in the bottom-left bar's spot (probe "barprobe"); hub20: the raid / party manager tab with the Party frames switch; hub19: the PARTY FRAMES switch on "uisw" 10 / 11; hub18: the WORLD MARKERS' switch on "uisw" 8 / 9 (Trail.lua's WC.npWant.wm; the doc's sw.wm); hub17: HUD mode SPLIT into its own switches on the row "uisw" (the player frame, the target frame, the enemy nameplates, the personal bar; columns 34 / 35 still the whole bundle for an older app; the doc's sw); hub16: HUD mode's bar WIDTH and SCALE too (column 39 selects which setting columns 36 / 37 / 38 step; the doc's prd carries w / s / sel); hub15: HUD mode's bar height (Interact's WC.prd: columns 36 down / 37 up / 38 default; the doc's prd); hub14: HUD mode wants the enemy nameplates on (ag2 / Trail.lua owns that CVar: WC.npWant.hud); hub13: HUD mode (the player and target frames hidden, alpha only, columns 34 / 35); hub12: Smart buffs' OR wheel (barcmd "smartor", PadMap m62); hub11: the game's CAST BAR on the top screen, Show / Hide by its own two columns of the row (f-top-castbar-toggle); hub10: the game's durability figure rides the "hud" element (f-durability); hub9: the PAUSE wheel's button (Spells, Home's radial pause menu); hub8: the SPELL RADIAL's button (Spells, the user's pick 2026-10-07; Home's yes); hub7: a D-pad room command takes its room's controller in the same click (Quests q16), its guestack right behind the barcmd; hub6: the "bye" document at logout (the app's waiting screen at once); hub5: the D-pad layout's four commands (map / chat / home / radial); hub2: the "hud" element carries the game's buffs, debuffs and XP bar (LATER item 20); hub3: re-checked hub36 (hub35 is fix/party-grid's tag: merge both, keep the higher): POLESTAR'S HIDE STICKS (the user, 2026-11-11, the switch on: "The polestar window randomly reappears a lot"): Polestar fades its windows back by itself (every fight's start and end, a setting change, each window's own fade-in, windows made later), with no Show; now a secure post-hook on each frame's SetAlpha puts the 0 back in the same frame and keeps the alpha Polestar asked for, its known names looked up every 0.25 s while hidden, Off gives back every frame touched (re-parented too); hub34: THE TOP PARTY COLUMN (party_design.md D and the user's decisions 2026-11-11): you, then party1..4, one row each in the bottom's order, every row the target bar's builder (npBar + the HUD rim, the level in a hudBox, the name above the lip, the HUD's green re-tinted every step); your own row while grouped or whenever Party mode is on; Party mode on: each row's LB glyph, a gold ring on the pick (PadMap box.u) and a white chevron at your target; dead / offline / out of range (PadMap's one rng) as the bottom; an 18 px Select tab beside the column (off / on / not bound); in a raid nothing; the combat log's switch calls retired (PadMap m129); hub33: THE PET'S STRIP IN THE HUD (the user, 2026-11-11: "find a way to include Pet HP in the HUD, and better distinguish the player HUD vs Pet HUD"): "uisw" 32 / 33 -> WC.npWant.pet (Trail.lua prd11: the pet's strip under the resource bar, on unless turned off); the game's pet frame is its own group now ("pet"): as the player frame (hidden with it while our strip stands in; a review fix 2026-11-11: never hidden by our strip alone); the doc's sw.pet / set.pet; hub32: THE GAME'S COMBO POINTS UNDER A HIDDEN TARGET FRAME (issue #120, a Rogue: "I can't see the combo points that are accumulated during combat"; 2026-11-11): the game draws them on ComboFrame, a child of TargetFrame (Blizzard's Classic FrameXML), so HUD mode's target switch (TargetFrame at alpha 0) hid them with it; with our target bar off nothing showed them. Now, while the game's host frame is hidden by us and our target bar (its own pips) is off, the combo frame ignores its parent's alpha (SetIgnoreParentAlpha, alpha only like the rest of HUD mode); otherwise it follows it again; hub31: HIDE POLESTAR ON THE TOP SCREEN (the user, 2026-11-11: "one other setting to hide the polestar UI from the top"): "uisw" 30 / 31 - every top-level frame Polestar names (PolestarGuideFrame, its arrow, its pad hints, toasts ...) at alpha 0 while on, and kept there when it shows again; off gives each its alpha 1 back; alpha only, nothing of Polestar changed; the doc's sw.ps; hub30: the third choice's RULES (the user, 2026-11-10: "it would be better if it stay on out of combat incase someone else pulls me into combat, and it should turn off when I target something out of combat AND start attack and then swap back when an ability is used before that ability fires" ... "make sure hunters mark does not trigger it to swap back on"): on by default and after every fight; OFF when an attack starts out of a fight with a target (PLAYER_ENTER_COMBAT); back ON at an ability's send (UNIT_SPELLCAST_SENT, before it fires) - never for Hunter's Mark, Auto Shot or Attack; hub29 (replaced by hub30): "In combat" turns the option on AT THE PULL (the user, 2026-11-10: "I pull with arcane shot and then when they get close, my auto attack melee is not on"): a spell you cast, Auto Shot or a melee swing starting out of a fight writes 1 right then (allowed: no lockdown yet), and 0 again 8 s later if no fight came of it; hub28: COMBAT AUTO SHOT "In combat" (the user, 2026-11-10: "add a third toggle to the hunter option to basically only turn it on during combat"): "uisw" 28 / 29 - while on, the game's autoRangedCombat is written 1 as a fight starts and 0 when it ends (and 0 at once out of a fight); the doc's sw.shotf, sw.shotfk = whether the fight's write took; hub27: the Party mode switch also starts / stops the combat log (PadMap m112); hub26: DS FOREVER'S OWN PARTY FRAMES AND TARGET BAR (WC.uf, at the end of this file; "uisw" 18 / 19 our party frames, 20 / 21 our target bar; while one is on, the game's own frames of that group stay hidden); hub25: PARTY MODE is an EXPERIMENTAL switch, off by default (the user, 2026-11-07: "Make the party mode an experimental toggle that is off by default"): "uisw" 16 / 17 -> WC.partyOk, which PadMap reads (Select toggles Party mode only while it is true); hub24: the Start attack switch reported while OFF (it read as absent: no QOL row); hub23: Combat Auto Shot for EVERY class (the user: "other classes use a bow"); hub22: START ATTACK ON A HOSTILE TARGET ("uisw" 12 / 13, the secure cell "atkgo") and the hunter's AUTO SHOT (the game's own autoRangedCombat, "uisw" 14 / 15); hub21: a one-time probe of the frames in the bottom-left bar's spot (probe "barprobe"); hub20: the raid / party manager tab with the Party frames switch; hub19: the PARTY FRAMES switch on "uisw" 10 / 11; hub18: the WORLD MARKERS' switch on "uisw" 8 / 9 (Trail.lua's WC.npWant.wm; the doc's sw.wm); hub17: HUD mode SPLIT into its own switches on the row "uisw" (the player frame, the target frame, the enemy nameplates, the personal bar; columns 34 / 35 still the whole bundle for an older app; the doc's sw); hub16: HUD mode's bar WIDTH and SCALE too (column 39 selects which setting columns 36 / 37 / 38 step; the doc's prd carries w / s / sel); hub15: HUD mode's bar height (Interact's WC.prd: columns 36 down / 37 up / 38 default; the doc's prd); hub14: HUD mode wants the enemy nameplates on (ag2 / Trail.lua owns that CVar: WC.npWant.hud); hub13: HUD mode (the player and target frames hidden, alpha only, columns 34 / 35); hub12: Smart buffs' OR wheel (barcmd "smartor", PadMap m62); hub11: the game's CAST BAR on the top screen, Show / Hide by its own two columns of the row (f-top-castbar-toggle); hub10: the game's durability figure rides the "hud" element (f-durability); hub9: the PAUSE wheel's button (Spells, Home's radial pause menu); hub8: the SPELL RADIAL's button (Spells, the user's pick 2026-10-07; Home's yes); hub7: a D-pad room command takes its room's controller in the same click (Quests q16), its guestack right behind the barcmd; hub6: the "bye" document at logout (the app's waiting screen at once); hub5: the D-pad layout's four commands (map / chat / home / radial); hub2: the "hud" element carries the game's buffs, debuffs and XP bar (LATER item 20); hub3: re-checked
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
-- hub10 (f-durability, the user 2026-10-15: "show the heads up display of broken armor from the top screen and move that to the bottom
-- between the buffs"): the game's DURABILITY figure (DurabilityFrame) rides too. Home draws it between its buff and debuff rows from
-- what Bags.lua reads of it; the game shows / hides it by itself (DurabilityFrame_SetAlerts), and the OnShow hook below puts the 0 back.
-- Its IsShown is untouched (alpha only), so Bags.lua still reads when the game shows it.
local RIDERS = { "BuffFrame", "DebuffFrame", "DeadlyDebuffFrame", "ExternalDefensivesFrame",
                 "MainStatusTrackingBarContainer", "SecondaryStatusTrackingBarContainer", "DurabilityFrame" }
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

-- ---------------------------------------------------------------- the game's cast bar (hub11)
-- f-top-castbar-toggle (the user, 2026-10-15: "We need to have a UI Toggle for the top screen to Show/hide the cast bar"): the
-- game's own casting bar, its own Show / Hide (the app's Settings > Game UI "Cast bar"; Home's bottom bar mirrors every cast). The
-- five layout bits are all held (minimap, hud, tracker, chat, dialogs) and the row's 40 columns carry the 32 layouts, so it takes
-- two of the row's spare columns: CB_SHOW / CB_HIDE, one absolute tap each. ALPHA ONLY, as the riders above (never Hide / Show /
-- SetParent, no CVar). The bar sets its own alpha back at every cast (CastingBarMixin:ApplyAlpha(1.0) at a start, and from Edit
-- Mode): a post-hook puts the 0 back; an OnShow hook does too. Its finish fade plays only while its alpha is above 0
-- (CastingBarMixin:PlayFadeAnim), but an interrupt's HoldFadeOutAnim plays regardless and drives the frame's alpha itself (1 for a
-- second, then fading): while hidden that one animation is stopped as it starts, and the bar stays invisible until the next cast.
-- Shown again: a bar that is casting gets its alpha back at once; an idle one gets it from the game's own next ApplyAlpha.
local CASTBARS = { "PlayerCastingBarFrame", "CastingBarFrame", "PetCastingBarFrame", "OverlayPlayerCastingBarFrame" }
local CB_SHOW, CB_HIDE = 32, 33
local cb = { hidden = false, hooked = {}, fix = 0 }

local function cbZero(fr) pcall(fr.SetAlpha, fr, 0) end

local function cbHook(name, fr)
    if cb.hooked[name] then return end
    cb.hooked[name] = true
    if type(fr.ApplyAlpha) == "function" then
        hooksecurefunc(fr, "ApplyAlpha", function(self) if cb.hidden then cbZero(self) end end)
    end
    if type(fr.UpdateSystemSettingOpacity) == "function" then
        hooksecurefunc(fr, "UpdateSystemSettingOpacity", function(self) if cb.hidden then cbZero(self) end end)
    end
    if type(fr.HookScript) == "function" then
        pcall(fr.HookScript, fr, "OnShow", function(self) if cb.hidden then cbZero(self) end end)
    end
    for _, key in ipairs({ "HoldFadeOutAnim", "FadeOutAnim" }) do
        local ag = rawget(fr, key)
        if type(ag) == "table" and type(ag.HookScript) == "function" then
            pcall(ag.HookScript, ag, "OnPlay", function(self)
                if cb.hidden then pcall(self.Stop, self); cbZero(fr) end
            end)
        end
    end
end

local function cbCasting(fr)
    return rawget(fr, "casting") == true or rawget(fr, "channeling") == true or rawget(fr, "reverseChanneling") == true
end

local function cbSet(hide)
    cb.hidden = hide
    for _, name in ipairs(CASTBARS) do
        local fr = riderFrame(name)
        if fr then
            cbHook(name, fr)
            if hide then cbZero(fr)
            elseif cbCasting(fr) then pcall(fr.SetAlpha, fr, 1) end
        end
    end
end
hub.cbSet = cbSet               -- for the harness

-- while hidden, the heartbeat zeroes any cast bar the game made visible again (and counts it, as the riders do)
local function cbCheck()
    if not cb.hidden then return end
    for _, name in ipairs(CASTBARS) do
        local fr = riderFrame(name)
        if fr then
            cbHook(name, fr)
            local ok, a = pcall(fr.GetAlpha, fr)
            if ok and type(a) == "number" and a > 0.01 then cb.fix = cb.fix + 1; cbZero(fr) end
        end
    end
end
hub.cbCheck = cbCheck

-- hub13 HUD MODE (the user: "I want to add a "HUD" mode where we turn off the player and the target action bars and rely on the I game
-- head up display and bar over top the mobs"; their pick: "Player frame, Target frame"): the game's PlayerFrame and TargetFrame and the
-- frames that hang off them (the target's target, the pet's frame) hidden ALPHA ONLY, as the cast bar (never Hide / Show / SetParent:
-- they are secure unit frames; SetAlpha is allowed in fights and taints nothing), on two more spare columns of the row: HUD_SHOW /
-- HUD_HIDE. The game shows / fades them itself (a new target, Edit Mode's opacity): an OnShow hook, the opacity post-hook and the
-- heartbeat put the 0 back. Shown again = alpha 1 (or Edit Mode's own opacity). Nothing saved; the app keeps the choice per character.
-- An invisible frame still takes a mouse click where it stands (a touch there on the top screen would still target yourself).
-- hub19 (the user, 2026-11-04: "the party frames need to be hidden with a menu toggle like other UI"): the game's party frames
-- (PartyFrame, and the raid-style CompactPartyFrame when that option is on), alpha only, their own switch PARTY_SHOW / PARTY_HIDE
-- hub20 (the user's screenshot, 2026-11-04: "the party bar on the far left"): the raid / party manager's tab on the left edge too
local HUD_FRAMES = { "PlayerFrame", "TargetFrame", "TargetFrameToT", "PetFrame", "PartyFrame", "CompactPartyFrame", "CompactRaidFrameManager" }
local HUD_SHOW, HUD_HIDE = 34, 35
local hm = { hidden = false, hooked = {}, fix = 0 }
-- hub17 (the user: "we need to split out the HUD mode into the visible things that should be in the top menu, like the cast bar and the
-- unit frames for myself and my target ... with the unit frames over the enemies' heads ON by default"): HUD mode's bundle as its OWN
-- switches, on a row of their own ("uisw": the "ui" row is full), two columns each, absolute (a repeated or stale tap changes nothing):
--   SW.PLAYER_SHOW / SW.PLAYER_HIDE   the player frame (and the pet's, which hangs off it), alpha only as before
--   SW.TARGET_SHOW / SW.TARGET_HIDE   the target frame (and the target's target)
--   SW.NP_OFF / SW.NP_ON              the enemy nameplates wanted (Trail.lua's ag2 owns the CVar: WC.npWant.np)
--   SW.PRD_OFF / SW.PRD_ON            the personal resource bar (Trail.lua's WC.prd: WC.npWant.prd)
-- The app keeps each choice per character and taps it at every connect (its defaults: the frames hidden, the nameplates and the bar on).
-- A switch once set wins over the bundle's columns 34 / 35 for its own part; those still move every part for an older app.
local SW_ROW = "uisw"
--   SW.WM_OFF / SW.WM_ON              hub18: the world markers (Trail.lua's compass: WC.npWant.wm; the user: "off by default")
local SW = { PLAYER_SHOW = 0, PLAYER_HIDE = 1, TARGET_SHOW = 2, TARGET_HIDE = 3, NP_OFF = 4, NP_ON = 5, PRD_OFF = 6, PRD_ON = 7, WM_OFF = 8, WM_ON = 9,
             PARTY_SHOW = 10, PARTY_HIDE = 11, ATK_OFF = 12, ATK_ON = 13, SHOT_OFF = 14, SHOT_ON = 15,
             PMODE_OFF = 16, PMODE_ON = 17,
             -- hub26: DS Forever's own party frames / target bar (WC.uf below)
             UFP_OFF = 18, UFP_ON = 19, UFT_OFF = 20, UFT_ON = 21,
             -- uf8 (the user, 2026-11-09: "I want the target of target to be a toggle for this and turned off by default")
             UFTOT_OFF = 22, UFTOT_ON = 23,
             -- uf11 (the user: "I dont like the percentage on the health bar but I want toggles for health total, Percentage and blank with
             -- blank as the default"): the bar's text - Total wins over Percent, neither = blank
             UFHPP_OFF = 24, UFHPP_ON = 25, UFHPT_OFF = 26, UFHPT_ON = 27,
             -- hub28: Combat Auto Shot only in fights
             SHOTF_OFF = 28, SHOTF_ON = 29,
             -- hub31: Polestar's own windows on the top screen
             PS_SHOW = 30, PS_HIDE = 31,
             -- hub33: the pet's strip under the resource bar (Trail.lua prd11: WC.npWant.pet)
             PET_OFF = 32, PET_ON = 33 }
-- hub22 (the user, 2026-11-07: "a QOL setting for Start attack on Hostile targeting, so that when I press RB to target a hostile target it
-- starts the attack, and then under that ... for hunters ... the 'Combat Auto Shot'"):
--   ATK: a NEW hostile target starts the auto attack. The game lets no add-on start it on an event (StartAttack is protected), so the
--     add-on only SAYS so (one "atkgo" document) and the app taps the secure /startattack cell (Spells' party row, column 36: WC.atkCell;
--     a new row might not fit above the pop-ups): a real click, as the game's own button would be. Not when the attack already runs, the target is dead, or not attackable.
--   SHOT: the game's own option "autoRangedCombat" (its words: "your character will automatically switch between auto attack and auto
--     shot"), every class (hub23: "other classes use a bow"); the user asked for the game's setting by name, so this one CVar is written (out of a fight) - the switch
--     reads it back, so the app's choice and the game's agree
local hubAtk = { on = nil, sent = 0 }
local function shotNow()
    local get = (C_CVar and C_CVar.GetCVarBool) or _G.GetCVarBool
    if type(get) ~= "function" then return nil end
    local ok, v = pcall(get, "autoRangedCombat")
    if not ok or v == nil then return nil end
    return v == true
end
local function shotSet(on)
    if InCombatLockdown() then return false end
    local set = (C_CVar and C_CVar.SetCVar) or _G.SetCVar
    if type(set) ~= "function" then return false end
    hubAtk.shotSet = true
    return pcall(set, "autoRangedCombat", on and "1" or "0")
end
-- hub28 "IN COMBAT": the game's Auto Shot switching only while a fight is on. The write at the fight's start is made inside the fight
-- (PLAYER_REGEN_DISABLED comes with the lockdown already on): pcall'd and read back, the answer kept for the doc (shotfk) so the app
-- can say when the game refused it. Out of a fight the option is 0.
local function shotWrite(on)
    local set = (C_CVar and C_CVar.SetCVar) or _G.SetCVar
    if type(set) ~= "function" then return false end
    pcall(set, "autoRangedCombat", on and "1" or "0")
    return shotNow() == on
end
local function shotFightSync(on)
    if hubAtk.shotFight ~= true then return end
    local ok = shotWrite(on == true)
    if InCombatLockdown() then hubAtk.shotFightOk = ok end     -- the doc's shotfk: did a write inside a fight take
end
WC.shotFightSync = shotFightSync
-- hub30 THE RULES: on by default (someone else's pull finds it on); a plain attack started OUT of a fight with a target turns it off
-- (PLAYER_ENTER_COMBAT: the auto attack began); an ability turns it back on at its SEND, before it fires - except Hunter's Mark (every
-- rank, by its spell IDs and by its name), Auto Shot and Attack themselves; the fight's end turns it on again
local SHOT_KEEP = { [1130] = true, [14323] = true, [14324] = true, [14325] = true, [53338] = true, [257284] = true,   -- Hunter's Mark
                    [75] = true, [6603] = true }                                                                      -- Auto Shot, Attack
local function markName()
    local get = (C_Spell and C_Spell.GetSpellName) or nil
    local ok, n = false, nil
    if type(get) == "function" then ok, n = pcall(get, 1130) end
    if (not ok or type(n) ~= "string") and type(GetSpellInfo) == "function" then ok, n = pcall(GetSpellInfo, 1130) end
    return ok and type(n) == "string" and n ~= "" and n or nil
end
local function shotEvent(event, ...)
    if hubAtk.shotFight ~= true then return end
    if event == "PLAYER_ENTER_COMBAT" then
        if not InCombatLockdown() and type(UnitExists) == "function" and UnitExists("target") then shotFightSync(false) end
    elseif event == "UNIT_SPELLCAST_SENT" then
        local unit, _, _, spellID = ...
        if unit ~= "player" then return end
        local id = tonumber(spellID)
        if id and SHOT_KEEP[id] then return end
        local mn = markName()
        if mn and id then
            local get = (C_Spell and C_Spell.GetSpellName) or nil
            local okN, n = false, nil
            if type(get) == "function" then okN, n = pcall(get, id) end
            if okN and n == mn then return end
        end
        shotFightSync(true)
    elseif event == "PLAYER_REGEN_ENABLED" then
        shotFightSync(true)
    end
end
local shotFrame = CreateFrame("Frame")
pcall(function() shotFrame:RegisterEvent("PLAYER_ENTER_COMBAT") end)
pcall(function() shotFrame:RegisterEvent("PLAYER_REGEN_ENABLED") end)
pcall(function() shotFrame:RegisterUnitEvent("UNIT_SPELLCAST_SENT", "player") end)
shotFrame:SetScript("OnEvent", function(_, event, ...) pcall(shotEvent, event, ...) end)
WC.shotEvent = shotEvent
local function plainTrue(f, ...)
    local ok, v = pcall(f, ...)
    if not ok then return false end
    if type(WC.isPlain) == "function" and not WC.isPlain(v) then return false end
    return v == true or v == 1
end
-- a new target: worth starting the attack on?
local function atkWanted()
    if hubAtk.on ~= true then return false end
    if not plainTrue(UnitExists, "target") then return false end
    if not plainTrue(UnitCanAttack, "player", "target") then return false end
    if plainTrue(UnitIsDeadOrGhost, "target") then return false end
    if type(IsCurrentSpell) == "function" and (plainTrue(IsCurrentSpell, 6603) or plainTrue(IsCurrentSpell, 75)) then return false end
    return true
end
-- hub33: the game's pet frame its own group: as the player frame (hidden with it while our pet strip, Trail.lua prd11, stands in)
-- (in the game's UI PetFrame hangs off PlayerFrame, so a hidden player frame takes it along whatever its own alpha)
local GROUP = { PlayerFrame = "player", PetFrame = "pet", TargetFrame = "target", TargetFrameToT = "target",
                PartyFrame = "party", CompactPartyFrame = "party", CompactRaidFrameManager = "party" }
hm.g = { player = nil, target = nil, party = nil }  -- per group: true hidden / false shown / nil as the bundle says (party: shown)
hub.SW = SW
local function groupHidden(name)
    -- hub26: our own party frames / target bar replace the game's while on (the party only out of a raid: the raid frames stay the game's)
    local uf = WC.uf
    if type(uf) == "table" then
        if GROUP[name] == "party" and uf.party and not (type(IsInRaid) == "function" and IsInRaid() == true) then return true end
        if GROUP[name] == "target" and uf.target then return true end
    end
    if GROUP[name] == "pet" then
        -- (review of hub33, 2026-11-11: hiding it whenever our strip was on left a player who SHOWS the game's player frame with no pet
        -- health once the HUD faded out of combat, or while dead - the strip only shows with the HUD. Now it follows the player frame
        -- only: hidden with it (HUD mode: our strip stands in), shown with it)
        local g = hm.g.player
        if g == nil then return hm.hidden end
        return g
    end
    local g = hm.g[GROUP[name]]
    if g == nil then return GROUP[name] ~= "party" and hm.hidden end      -- the old HUD bundle never covered the party frames
    return g
end
local function hmZero(fr) pcall(fr.SetAlpha, fr, 0) end
local function hmHook(name, fr)
    if hm.hooked[name] then return end
    hm.hooked[name] = true
    if type(fr.HookScript) == "function" then pcall(fr.HookScript, fr, "OnShow", function(self) if groupHidden(name) then hmZero(self) end end) end
    if type(fr.UpdateSystemSettingOpacity) == "function" then
        hooksecurefunc(fr, "UpdateSystemSettingOpacity", function(self) if groupHidden(name) then hmZero(self) end end)
    end
end
local function hmSet(hide)
    hm.hidden = hide
    -- hub14 (the user: "Yes, force them on"): the enemy nameplates are wanted while HUD mode hides the frames. Trail.lua's ag2 is the ONE
    -- owner of that game setting (it keeps the player's own value in our saved data, sets it out of combat, gives it back when neither
    -- the route nor HUD mode wants it, and at log out); here only the wish
    WC.npWant = type(WC.npWant) == "table" and WC.npWant or {}
    WC.npWant.hud = hide or nil
    hub.hmApply()
end
-- every frame as its group says (hidden: alpha 0; shown: Edit Mode's own opacity, else 1)
function hub.hmApply()
    for _, name in ipairs(HUD_FRAMES) do
        local fr = riderFrame(name)
        if fr then
            hmHook(name, fr)
            if groupHidden(name) then hmZero(fr)
            elseif type(fr.UpdateSystemSettingOpacity) == "function" then
                if not pcall(fr.UpdateSystemSettingOpacity, fr) then pcall(fr.SetAlpha, fr, 1) end
            else pcall(fr.SetAlpha, fr, 1) end
        end
    end
    pcall(hub.cpLift)                                              -- hub32: the game's combo points (issue #120)
end
-- hub32 (issue #120, a Rogue: "I can't see the combo points that are accumulated during combat"; 2026-11-11): the game's own combo
-- points live on a child of a frame HUD mode hides by alpha (Classic: ComboFrame on TargetFrame; the later client's ComboPointPlayerFrame
-- on PlayerFrame), and a child's alpha is its parent's times its own, so they went with it. While that host is hidden by us and our own
-- target bar (which has its own pips) is off, the combo frame ignores its parent's alpha; else it follows it again. Alpha only: the game
-- still shows / hides it (a target, points or none), nothing of it is moved or changed.
hub.CP_HOST = { ComboFrame = "TargetFrame", ComboPointPlayerFrame = "PlayerFrame" }
function hub.cpLift()
    local ours = type(WC.uf) == "table" and WC.uf.target == true
    for name, host in pairs(hub.CP_HOST) do
        local fr = _G[name]
        if type(fr) == "table" and type(fr.SetIgnoreParentAlpha) == "function" then
            pcall(fr.SetIgnoreParentAlpha, fr, (groupHidden(host) and not ours) and true or false)
        end
    end
end
-- hub17: one switch column of the row "uisw"
function hub.swTap(col)
    WC.npWant = type(WC.npWant) == "table" and WC.npWant or {}
    if col == SW.PLAYER_SHOW or col == SW.PLAYER_HIDE then hm.g.player = (col == SW.PLAYER_HIDE); hub.hmApply()
    elseif col == SW.TARGET_SHOW or col == SW.TARGET_HIDE then hm.g.target = (col == SW.TARGET_HIDE); hub.hmApply()
    elseif col == SW.PARTY_SHOW or col == SW.PARTY_HIDE then hm.g.party = (col == SW.PARTY_HIDE); hub.hmApply()
    elseif col == SW.NP_OFF or col == SW.NP_ON then WC.npWant.np = (col == SW.NP_ON)
    elseif col == SW.PRD_OFF or col == SW.PRD_ON then WC.npWant.prd = (col == SW.PRD_ON); hub.hmApply()      -- hub33: the pet frame follows
    elseif col == SW.PET_OFF or col == SW.PET_ON then WC.npWant.pet = (col == SW.PET_ON); hub.hmApply()      -- hub33: the pet's strip
    elseif col == SW.WM_OFF or col == SW.WM_ON then WC.npWant.wm = (col == SW.WM_ON)
    elseif col == SW.ATK_OFF or col == SW.ATK_ON then hubAtk.on = (col == SW.ATK_ON)
    elseif col == SW.SHOT_OFF or col == SW.SHOT_ON then shotSet(col == SW.SHOT_ON)
    elseif col == SW.SHOTF_OFF or col == SW.SHOTF_ON then
        -- hub28: "In combat" on / off; on: the option follows the fight from now (0 at once out of one)
        hubAtk.shotFight = (col == SW.SHOTF_ON)
        if hubAtk.shotFight and not InCombatLockdown() then shotFightSync(true) end     -- hub30: on by default
    elseif col == SW.UFP_OFF or col == SW.UFP_ON or col == SW.UFT_OFF or col == SW.UFT_ON then
        -- hub26: our own frames on / off; the game's of that group follow (hidden while ours show)
        if type(WC.uf) == "table" and type(WC.uf.set) == "function" then
            if col == SW.UFP_OFF or col == SW.UFP_ON then WC.uf.set("party", col == SW.UFP_ON) else WC.uf.set("target", col == SW.UFT_ON) end
            WC.uf.chosen = WC.uf.chosen or {}
            WC.uf.chosen[(col == SW.UFP_OFF or col == SW.UFP_ON) and "party" or "target"] = true
        end
        hub.hmApply()
    elseif col == SW.UFTOT_OFF or col == SW.UFTOT_ON then
        -- uf8: the target bar's target of target on / off (off by default)
        if type(WC.uf) == "table" and type(WC.uf.set) == "function" then
            WC.uf.set("tot", col == SW.UFTOT_ON)
            WC.uf.chosen = WC.uf.chosen or {}
            WC.uf.chosen.tot = true
        end
    elseif col == SW.UFHPP_OFF or col == SW.UFHPP_ON or col == SW.UFHPT_OFF or col == SW.UFHPT_ON then
        -- uf11: the target bar's health text (percent / total)
        if type(WC.uf) == "table" and type(WC.uf.set) == "function" then
            local part = (col == SW.UFHPP_OFF or col == SW.UFHPP_ON) and "hpp" or "hpt"
            WC.uf.set(part, col == SW.UFHPP_ON or col == SW.UFHPT_ON)
            WC.uf.chosen = WC.uf.chosen or {}
            WC.uf.chosen[part] = true
        end
    elseif col == SW.PS_SHOW or col == SW.PS_HIDE then
        hub.psSet(col == SW.PS_HIDE)
    elseif col == SW.PMODE_OFF or col == SW.PMODE_ON then
        -- hub25: Party mode allowed (PadMap's PM.allowed); turned off, PadMap ends a Party mode that is on (out of combat)
        WC.partyOk = (col == SW.PMODE_ON)
        if type(WC.partyAllow) == "function" then pcall(WC.partyAllow, WC.partyOk) end
        -- hub34: the combat log is retired (PadMap m129): nothing to start or stop here any more
    else return false end
    return true
end
hub.hmSet = hmSet               -- for the harness
-- hub31: Polestar's top-level frames (every global frame named Polestar..., parented to UIParent or nothing), alpha 0 while hidden.
-- hub36: THE HIDE STICKS (the user, 2026-11-11, with the switch on: "The polestar window randomly reappears a lot"). Polestar 0.2.5
-- gives its windows their alpha back by itself, with no Show, so hub31's OnShow hook never saw it and only the 5 s look put the 0 back:
--   - every fight's start and end: PLAYER_REGEN_DISABLED / _ENABLED -> GF.ApplyAlpha(true) (UI/GuideFrame.lua:2528-2540), a fade of
--     PolestarGuideFrame from its alpha (our 0) to ui.opacity (GuideFrame.lua:1649-1653) through Anim.To -> o:SetAlpha each frame
--     (Theme/Anim.lua:35, :70-80); a setting change too (ui.opacity / ui.combat / ui: GuideFrame.lua:1725, :1738, :1948);
--   - the fade-ins of its other windows as they show: the rare toast (Rares/Toast.lua:187), the transit banner (Nav/Alerts.lua:162),
--     the menu (Theme/Widgets.lua:1185), Options / Picker (Widgets.lua:2214), Report / RouteChoice / Copy's OnShow
--     (UI/Report.lua:464, UI/RouteChoice.lua:322, UI/Copy.lua:62), the arrow's own opacity (Nav/Arrow.lua:604);
--   - windows made the first time they are needed (the toast, the banner, the menu, Copy, Report, RouteChoice, Welcome, the pad
--     hints / ring), not there at our look: up to 5 s on screen;
--   - the pad hints and ring re-parented into a window (UI/Gamepad.lua:1501, :1511) dropped out of the look, so the switch's Off
--     never gave them their alpha back.
-- Now: a secure post-hook on each frame's own SetAlpha (Anim calls o:SetAlpha, so the instance hook sees every step) puts the 0 back
-- in the same frame and keeps the alpha Polestar asked for (given back at Off); OnShow as before; Polestar's known names looked up
-- every 0.25 s while hidden (cheap: a name each), and the full look every 5 s for any other; Off gives back every frame ever touched,
-- re-parented or not, and the hooks pass everything through. Alpha only: nothing of Polestar's code, settings, Show / Hide or parents.
-- A protected frame (the QoL secure buttons) gets no SetAlpha hook (none of Polestar's code fades them); the 0.25 s look keeps it 0.
hub.ps = { hidden = nil, hooked = {}, touched = {}, want = {}, busy = false,
           NAMES = { "PolestarGuideFrame", "PolestarNavArrow", "PolestarTransitAlert", "PolestarRareToast", "PolestarMenu",
                     "PolestarCopy", "PolestarPadHints", "PolestarPadRing", "PolestarReport", "PolestarRouteChoice", "PolestarWelcome",
                     "PolestarOptions", "PolestarPicker", "PolestarFlightTimer", "PolestarInstanceTracker", "PolestarQuestItemButton",
                     "PolestarHearthButton", "PolestarTargetButton", "PolestarTravelButton", "PolestarStepButton" } }
function hub.psTop(fr)
    if type(fr) ~= "table" or type(fr.GetObjectType) ~= "function" or type(fr.SetAlpha) ~= "function" then return false end
    local okP, parent = pcall(fr.GetParent, fr)
    return okP and (parent == nil or parent == UIParent)
end
function hub.psFrames()
    local out = {}
    for name, fr in pairs(_G) do
        if type(name) == "string" and name:sub(1, 8) == "Polestar" and hub.psTop(fr) then out[#out + 1] = { name, fr } end
    end
    return out
end
function hub.psZero(fr)
    local P = hub.ps
    P.busy = true
    pcall(fr.SetAlpha, fr, 0)
    P.busy = false
end
function hub.psHook(fr)
    local P = hub.ps
    if P.hooked[fr] then return end
    P.hooked[fr] = true
    local okA, a0 = pcall(fr.GetAlpha, fr)
    P.want[fr] = (okA and type(a0) == "number" and a0 > 0.01) and a0 or 1
    if type(fr.HookScript) == "function" then
        pcall(fr.HookScript, fr, "OnShow", function(self) if hub.ps.hidden then hub.psZero(self) end end)
    end
    local okPr, prot = pcall(function() return fr.IsProtected and fr:IsProtected() end)
    if okPr and not prot then
        pcall(hooksecurefunc, fr, "SetAlpha", function(self, a)
            local Q = hub.ps
            if Q.busy then return end
            if type(a) == "number" then Q.want[self] = a end
            if Q.hidden then hub.psZero(self) end
        end)
    end
end
function hub.psHide(fr)
    local P = hub.ps
    hub.psHook(fr)
    P.touched[fr] = true
    local ok, a = pcall(fr.GetAlpha, fr)
    if not ok or type(a) ~= "number" or a > 0.01 then hub.psZero(fr) end
end
function hub.psQuick()
    local P = hub.ps
    if not P.hidden then return end
    for _, name in ipairs(P.NAMES) do
        local fr = _G[name]
        if P.touched[fr] then
            local ok, a = pcall(fr.GetAlpha, fr)
            if ok and type(a) == "number" and a > 0.01 then hub.psZero(fr) end
        elseif hub.psTop(fr) then
            hub.psHide(fr)
        end
    end
end
function hub.psApply()
    local P = hub.ps
    if P.hidden then
        for _, nf in ipairs(hub.psFrames()) do hub.psHide(nf[2]) end
        hub.psQuick()
    else
        for fr in pairs(P.touched) do
            P.busy = true
            pcall(fr.SetAlpha, fr, P.want[fr] or 1)
            P.busy = false
        end
        P.touched = {}
    end
end
function hub.psSet(hide)
    hub.ps.hidden = hide == true
    hub.psApply()
end
if type(C_Timer) == "table" and type(C_Timer.NewTicker) == "function" then
    C_Timer.NewTicker(5, function() if hub.ps.hidden then pcall(hub.psApply) end end)
    C_Timer.NewTicker(0.25, function() if hub.ps.hidden then pcall(hub.psQuick) end end)
end
local function hmCheck()
    for _, name in ipairs(HUD_FRAMES) do
        local fr = groupHidden(name) and riderFrame(name)
        if fr then
            hmHook(name, fr)
            local ok, a = pcall(fr.GetAlpha, fr)
            if ok and type(a) == "number" and a > 0.01 then hm.fix = hm.fix + 1; hmZero(fr) end
        end
    end
end
-- hub15 HUD MODE'S BAR HEIGHT (tracking / Interact: the personal resource bar HUD mode shows, Trail.lua's WC.prd; the user: "Yes, add a
-- slider", per character): Interact's bar owns its place (WC.prd.y, its steps, kept in WowCompanionDB.prdY); the row's last spare columns
-- only pass the app's step on: HUD_LOWER one step down, HUD_HIGHER one up, HUD_HDEF back to the default (WC.prd.nudge(-1 / 1 / 0)). The
-- hub document says where it is (`prd`), and the app steps until that matches the player's choice.
local HUD_LOWER, HUD_HIGHER, HUD_HDEF = 36, 37, 38
-- hub16 (test50, the user: "can we add some scaling settings so that I can tweak it"): the row's LAST column selects which of the bar's
-- settings the step columns move (height -> width -> scale; Interact's WC.prd.select, back to the height 4 s after the last tap)
local HUD_SEL = 39
local function prdDoc()
    local p = WC.prd
    if type(p) ~= "table" then return nil end
    return { y = tonumber(p.y), on = p.on and true or nil, min = tonumber(p.min), max = tonumber(p.max), step = tonumber(p.step),
             def = tonumber(p.def),
             -- hub16: the width and the scale, their ranges, and which one the step columns move now
             w = tonumber(p.wd), wmin = tonumber(p.W_MIN), wmax = tonumber(p.W_MAX), wstep = tonumber(p.W_STEP), wdef = tonumber(p.W_DEF),
             s = tonumber(p.sc), smin = tonumber(p.S_MIN), smax = tonumber(p.S_MAX), sstep = tonumber(p.S_STEP), sdef = tonumber(p.S_DEF),
             sel = type(p.sel) == "string" and p.sel or nil }
end

local function hmDoc()
    local have = {}
    for _, name in ipairs(HUD_FRAMES) do if riderFrame(name) then have[#have + 1] = name end end
    return { hidden = hm.hidden, have = #have > 0 and have or nil, fix = hm.fix > 0 and hm.fix or nil }
end
-- hub26: our own frames' switch as it stands: true / false (never nil while the module is loaded: nil reads as "no switch", hub24's lesson)
function hub.ufFlag(part)
    if type(WC.uf) ~= "table" then return nil end
    return WC.uf[part] == true
end
-- hub17: the switches as they stand (each: what it does NOW, the bundle's answer where the switch was never set), the row's name
local function swDoc()
    local np = type(WC.npWant) == "table" and WC.npWant or {}
    local function either(v, b) if v == nil then return b == true end return v == true end
    -- hub24: "x and false or nil" is nil - the switch read as absent while off (the user: "QOL is missing the target based combat start")
    local atkv = nil
    if type(WC.atkCell) == "table" then atkv = hubAtk.on == true end
    return { row = hub.swRow and SW_ROW or nil, cols = SW,
             player = either(hm.g.player, hm.hidden) and "hidden" or "shown",
             target = either(hm.g.target, hm.hidden) and "hidden" or "shown",
             party = hm.g.party == true and "hidden" or "shown",
             np = either(np.np, np.hud), prd = either(np.prd, np.hud), wm = np.wm == true,
             atk = atkv, shot = shotNow(), pmode = WC.partyOk == true,
             shotf = hubAtk.shotFight == true, shotfk = hubAtk.shotFightOk,
             ps = hub.ps.hidden == true,
             -- hub33: the pet's strip (on unless turned off)
             pet = np.pet ~= false,
             -- hub26: our own party frames / target bar on
             ufp = hub.ufFlag("party"), uft = hub.ufFlag("target"), uftot = hub.ufFlag("tot"), ufhpp = hub.ufFlag("hpp"), ufhpt = hub.ufFlag("hpt"),
             set = { player = hm.g.player ~= nil or nil, target = hm.g.target ~= nil or nil, party = hm.g.party ~= nil or nil, np = np.np ~= nil or nil, prd = np.prd ~= nil or nil, wm = np.wm ~= nil or nil,
                     atk = hubAtk.on ~= nil or nil, shot = hubAtk.shotSet or nil, pmode = WC.partyOk ~= nil or nil,
                     shotf = hubAtk.shotFight ~= nil or nil, ps = hub.ps.hidden ~= nil or nil, pet = np.pet ~= nil or nil,
                     ufp = (type(WC.uf) == "table" and type(WC.uf.chosen) == "table" and WC.uf.chosen.party) or nil,
                     uft = (type(WC.uf) == "table" and type(WC.uf.chosen) == "table" and WC.uf.chosen.target) or nil,
                     uftot = (type(WC.uf) == "table" and type(WC.uf.chosen) == "table" and WC.uf.chosen.tot) or nil,
                     ufhpp = (type(WC.uf) == "table" and type(WC.uf.chosen) == "table" and WC.uf.chosen.hpp) or nil,
                     ufhpt = (type(WC.uf) == "table" and type(WC.uf.chosen) == "table" and WC.uf.chosen.hpt) or nil } }
end

local function cbDoc()
    local have = {}
    for _, name in ipairs(CASTBARS) do if riderFrame(name) then have[#have + 1] = name end end
    return { hidden = cb.hidden, have = #have > 0 and have or nil, fix = cb.fix > 0 and cb.fix or nil }
end

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
        cb = cbDoc(),                -- hub11: the game's cast bar (Show / Hide on columns CB_SHOW / CB_HIDE)
        hud = hmDoc(),               -- hub13: HUD mode, the player / target frames (Show / Hide on columns HUD_SHOW / HUD_HIDE)
        sw = swDoc(),                -- hub17: HUD mode's own switches (the row "uisw")
        prd = prdDoc(),              -- hub15: HUD mode's own bar's place (Interact's WC.prd), stepped on columns 36 / 37 / 38
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
    elseif col == CB_SHOW or col == CB_HIDE then
        cbSet(col == CB_HIDE)
    elseif col == HUD_SHOW or col == HUD_HIDE then
        hmSet(col == HUD_HIDE)
    elseif col == HUD_SEL then
        if type(WC.prd) == "table" and type(WC.prd.select) == "function" then pcall(WC.prd.select) end
    elseif col == HUD_LOWER or col == HUD_HIGHER or col == HUD_HDEF then
        if type(WC.prd) == "table" and type(WC.prd.nudge) == "function" then
            pcall(WC.prd.nudge, col == HUD_HIGHER and 1 or (col == HUD_LOWER and -1 or 0))
        end
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
    if not hub.swRow and WC.pad and type(WC.pad.op) == "function" then
        hub.swRow = WC.pad.op(SW_ROW, SW_ROW, function(_, col)
            hub.taps = hub.taps + 1
            hub.swTap(col)
            emitHub(true)
            WC.push()
        end)
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
-- hub22: a new target, on its own frame (nothing else of the hub runs for it)
local atkFrame = CreateFrame("Frame")
if pcall(function() return atkFrame:RegisterEvent("PLAYER_TARGET_CHANGED") end) then
    atkFrame:SetScript("OnEvent", function()
        local cell = WC.atkCell
        if type(cell) ~= "table" or not atkWanted() then return end
        hubAtk.sent = hubAtk.sent + 1
        pcall(function() WC.send(WC.encode({ type = "atkgo", n = hubAtk.sent, row = cell.row, col = cell.col, v = HV })) end)
        if type(WC.flush) == "function" then pcall(WC.flush) else WC.push() end
    end)
end
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
        if RIDE[reason] then setup(); ridersCheck(); cbCheck(); hmCheck(); emitHub(true) end
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
local HOLDS = { map = "home", chat = "chat", home = "bar", radial = "radial", sradial = "radial", pause = "radial", smartor = "radial" }   -- hub8: the spell radial, hub9: the pause wheel, the same guest
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
                       WowCompanionPadPause = "pause",
                       -- hub12: Smart buffs' OR row (PadMap m62): Y with none of the row's buffs up opens the app's wheel of them
                       WowCompanionPadSmartyOr = "smartor" }) do
    local b = CreateFrame("Button", name, UIParent)
    b:RegisterForClicks("AnyUp", "AnyDown")
    b:SetScript("OnClick", function() barCmd(k) end)
end
hub.barCmd = barCmd     -- for the harness

-- /wcui          print the elements and their state
-- /wcui <mask>   ask for a layout by hand, exactly as a tap would (0 = everything shown; 32 / 33 = the cast bar shown / hidden)
SLASH_WCUI1 = "/wcui"
SlashCmdList["WCUI"] = function(arg)
    setup()
    local n = tonumber((arg or ""):match("^%s*(%d+)%s*$"))
    if n then onTap(nil, n) end
    for _, e in ipairs(hub.els) do
        say(e.id .. " (bit " .. e.bit .. ")  " .. (isHidden(e) and "hidden" or "shown") ..
            (e.why and ("  |cffff5555" .. e.why .. "|r") or ""))
    end
    say("cast bar " .. (cb.hidden and "hidden" or "shown"))
    say("layout " .. tostring(hub.want) .. "  pad row " .. tostring(hub.row) ..
        (hub.pending and "  |cffffd100waiting for combat to end|r" or ""))
    if not n then emitHub(true); WC.push() end
end

-- hub21 THE BAR AT THE BOTTOM LEFT (the user's screenshot, 2026-11-05: an empty framed bar, x 75..700, y 965..990 of 1920 x 1080, that is
-- NOT the chat box): 6 s after each login, the SHOWN named frames standing there (left edge in the screen's left 12 %, bottom in its
-- bottom 15 %, 20..45 % of its width) are named once in a cap probe "barprobe" (the app logs it as WCHUB), so the next build hides the
-- right one. Read only: nothing is changed.
do
    local pf = CreateFrame("Frame")
    pf:RegisterEvent("PLAYER_ENTERING_WORLD")
    pf:SetScript("OnEvent", function()
        pf:UnregisterEvent("PLAYER_ENTERING_WORLD")
        if type(C_Timer) ~= "table" or type(EnumerateFrames) ~= "function" then return end
        C_Timer.After(6, function()
            pcall(function()
                local W, H = UIParent:GetWidth(), UIParent:GetHeight()
                local out, n = {}, 0
                local f = EnumerateFrames()
                while f and n < 4000 do
                    n = n + 1
                    local ok, hit = pcall(function()
                        if f:IsForbidden() or not f:IsVisible() then return nil end
                        local name = f:GetName()
                        if type(name) ~= "string" then return nil end
                        local l, b, w, h = f:GetLeft(), f:GetBottom(), f:GetWidth(), f:GetHeight()
                        if type(l) ~= "number" or type(b) ~= "number" then return nil end
                        if l < W * 0.12 and b < H * 0.15 and w > W * 0.20 and w < W * 0.45 and h < H * 0.08 then
                            return string.format("%s %d,%d %dx%d a%.2f", name, l, b, w, h, f:GetEffectiveAlpha())
                        end
                    end)
                    if ok and hit and #out < 30 then out[#out + 1] = hit end
                    f = EnumerateFrames(f)
                end
                WC.send(WC.encode({ type = "cap", probe = "barprobe", v = HV, frames = out, scanned = n }))
                WC.push()
            end)
        end)
    end)
end

-- ==================================================================== hub26: DS FOREVER'S OWN PARTY FRAMES AND TARGET BAR (top screen)
-- The user, 2026-11-09: "we need our own Party UI that ties a little closer to the party mode features that shows on the top screen
-- since we cant seem to sync up the hp right on the bottom screen. Along with that a under the compass centered target bar ... (Including
-- all normal target ui and combo points for rogues)". Picks: the party frames a LEFT COLUMN that REPLACES the game's (Party mode adds
-- the picked member's highlight and each member's LB glyph); the target bar with the power bar, the cast bar, buffs and debuffs and the
-- target of target; the game's target frame hidden while ours is on.
-- THE HEALTH IS REAL: the game hides health NUMBERS from add-ons (secret values), but its widgets take them - every value here goes
-- straight from the game's call into OUR StatusBar / FontString / Cooldown (the HUD mode bar's way, Trail.lua's WC.prd): no arithmetic,
-- no comparison, nothing read back. Our own frames only: nothing of the game's is written, called or moved (the game's own frames
-- are hidden by the switches above, alpha only). No mouse (EnableMouse false): touch goes through to the world as before.
-- The switches: "uisw" UFP_OFF / UFP_ON (our party frames), UFT_OFF / UFT_ON (our target bar), UFTOT_OFF / UFTOT_ON (the bar's target of
-- target, uf8: off by default); the app keeps each per character.
do
    local UF = { party = true, target = true, tot = false, hpp = false, hpt = false, V = "uf14" }
    WC.uf = UF
    local PRD = type(WC.prd) == "table" and WC.prd or {}
    local function plainV(v) return v ~= nil and (type(WC.isPlain) ~= "function" or WC.isPlain(v)) end
    local function call(f, ...) local ok, a, b, c, d, e, g, h, i = pcall(f, ...); if ok then return a, b, c, d, e, g, h, i end end
    local function bool(f, ...) local v = call(f, ...); if plainV(v) then return v == true end return nil end
    local function scaleK()
        local okH, uh = pcall(function() return UIParent:GetHeight() end)
        return (okH and type(uh) == "number" and uh > 0) and (uh / 1080) or (768 / 1080)
    end
    local K = scaleK()
    local function u(px) return px * K end
    local FONT = "Fonts\\FRIZQT__.TTF"

    -- ---------------------------------------------------------------- pieces
    local function text(parent, size, layer)
        local fs = parent:CreateFontString(nil, layer or "OVERLAY")
        fs:SetFont(FONT, u(size), "OUTLINE")
        fs:SetShadowOffset(1, -1)
        return fs
    end
    local function bar(parent, w, h)
        local b = CreateFrame("StatusBar", nil, parent)
        b:SetSize(u(w), u(h))
        local edge = b:CreateTexture(nil, "BACKGROUND", nil, -2)
        edge:SetPoint("TOPLEFT", b, "TOPLEFT", -1, 1); edge:SetPoint("BOTTOMRIGHT", b, "BOTTOMRIGHT", 1, -1)
        edge:SetColorTexture(0, 0, 0, 0.85)
        local bg = b:CreateTexture(nil, "BACKGROUND", nil, -1)
        bg:SetAllPoints(b); bg:SetColorTexture(0.06, 0.06, 0.06, 0.75)
        local fill = b:CreateTexture(nil, "ARTWORK")
        b.fill = fill
        if type(PRD.setFill) == "function" then pcall(PRD.setFill, b) else fill:SetTexture("Interface\\TargetingFrame\\UI-StatusBar") end
        b:SetStatusBarTexture(fill)
        local okT, real = pcall(function() return b:GetStatusBarTexture() end)
        if okT and type(real) == "table" then b.fill = real end
        b:SetMinMaxValues(0, 1); b:SetValue(1)
        return b
    end
    -- hub34 (party_design.md D): every call re-tints (the early return left a colour the game had changed on the fill)
    local function paint(b, r, g, bl)
        b.rgb = { r, g, bl }
        pcall(function() b:SetStatusBarColor(r, g, bl) end)
        pcall(function() b.fill:SetVertexColor(r, g, bl) end)
    end
    -- a unit's health / power straight into its bars (secret values pass through the widget setters)
    local function feed(hp, pw, unit)
        pcall(function() hp:SetMinMaxValues(0, UnitHealthMax(unit)) end)
        pcall(function() hp:SetValue(UnitHealth(unit)) end)
        if pw then
            local pt, tok = call(UnitPowerType, unit)
            if not plainV(pt) then pt, tok = 0, "MANA" end
            local c = (type(PRD.POWER) == "table" and PRD.POWER[tok]) or (type(PowerBarColor) == "table" and PowerBarColor[tok]) or nil
            if type(c) == "table" then
                local r, g, bl = c[1] or c.r, c[2] or c.g, c[3] or c.b
                if type(r) == "number" then paint(pw, r, g, bl) end
            end
            pcall(function() pw:SetMinMaxValues(0, UnitPowerMax(unit, pt)) end)
            pcall(function() pw:SetValue(UnitPower(unit, pt)) end)
        end
    end
    local function classRGB(unit)
        local _, cls = call(UnitClass, unit)
        if not plainV(cls) or type(cls) ~= "string" then return nil end
        local c = (C_ClassColor and call(C_ClassColor.GetClassColor, cls)) or (type(RAID_CLASS_COLORS) == "table" and RAID_CLASS_COLORS[cls])
        if type(c) == "table" and type(c.r) == "number" then return c.r, c.g, c.b end
    end
    local function setText(fs, v) if not pcall(fs.SetText, fs, v) then fs:SetText("") end end

    -- the game's controller glyph for a button (the layout the Y glyph uses: PadMap's padLayout), else its word
    local GLYPH_WORD = { PAD1 = "A", PAD2 = "B", PAD3 = "X", PAD4 = "Y", PADDUP = "Up", PADDDOWN = "Down", PADDLEFT = "Left", PADDRIGHT = "Right" }
    local function glyphAtlas(key)
        local db = _G.WowCompanionSpellsDB
        local plain = type(db) == "table" and db.padLayout == "plain"
        local face = plain and { PAD1 = "Ltr_A", PAD2 = "Ltr_B", PAD3 = "Ltr_X", PAD4 = "Ltr_Y" }
                           or { PAD1 = "Rev_B", PAD2 = "Rev_A", PAD3 = "Rev_Y", PAD4 = "Rev_X" }
        if face[key] then return { "Gamepad_" .. face[key] .. "_64" } end
        local d = ({ PADDUP = "Up", PADDDOWN = "Down", PADDLEFT = "Left", PADDRIGHT = "Right" })[key]
        if d then return { "Gamepad_Gen_" .. d .. "_64", "Gamepad_Gen_DPad" .. d .. "_64", "Gamepad_Ltr_" .. d .. "_64" } end
        return {}
    end
    local function setGlyph(tex, fs, key)
        if tex.key == key then return end
        tex.key = key
        if not key then tex:Hide(); fs:SetText(""); return end
        for _, a in ipairs(glyphAtlas(key)) do
            if type(PRD.atlasOn) == "function" and PRD.atlasOn(tex, a) then tex:Show(); fs:SetText("LB"); return end
        end
        tex:Hide()
        fs:SetText("LB " .. (GLYPH_WORD[key] or key))
    end

    -- ---------------------------------------------------------------- THE TARGET BAR (centred under the compass)
    -- uf3 (the user's sign-off 2026-11-09, layout "A: big level shield"; "the buffs/debuffs need to appear under the bar, and timers should
    -- be small inside of them", "the healthbar frame is like tiny behind the actual colored health bar", "the box for level is too small",
    -- "when targetting their nameplate healthbar should not be visible", "instead of class colors, make sure it actually matchs the name
    -- plate colors"): the nameplate's own bar art in a black frame that wraps it whole; a gold-rimmed level shield on the left (the elite /
    -- rare dragon behind it); the raid marker on the right; combo pips, the cast bar, then the auras UNDER it, their seconds small in a
    -- corner; the colour READ from the target's nameplate (else the game's own nameplate rule, UnitSelectionColor); the target's nameplate
    -- health bar hidden (alpha) while it is targeted. No power bar.
    local TB = { W = 372, SHIFT = 16, NAME = 20, HP = 24, CP = 6, CAST = 15, LV = 30, ICON = 26, IGAP = 3, DEBUFF_MAX = 8, BUFF_MAX = 6,
                 TOT_W = 140, TOT_HP = 10, AURA_S = 0.25 }
    UF.tb = TB
    local function atlas(tex, name)
        if type(PRD.atlasOn) == "function" then return PRD.atlasOn(tex, name) end
        return pcall(function() tex:SetAtlas(name) end)
    end
    -- a black frame (2 px) with a thin dark-gold rim round the bar, a plain dark ground: nothing of it sits inside the bar's own edge
    local function edgeBox(parent, target, px, r, g, b, a, layer, sub)
        for _, e in ipairs({ { "TOPLEFT", "TOPRIGHT", 1 }, { "BOTTOMLEFT", "BOTTOMRIGHT", -1 } }) do
            local t = parent:CreateTexture(nil, layer, nil, sub)
            t:SetColorTexture(r, g, b, a)
            t:SetPoint(e[1], target, e[1], e[1] == "TOPLEFT" and -px or -px, e[3] * px)
            t:SetPoint(e[2], target, e[2], px, e[3] * px)
            t:SetHeight(px)
        end
        for _, side in ipairs({ "LEFT", "RIGHT" }) do
            local t = parent:CreateTexture(nil, layer, nil, sub)
            t:SetColorTexture(r, g, b, a)
            t:SetPoint("TOP" .. side, target, "TOP" .. side, side == "LEFT" and -px or px, px)
            t:SetPoint("BOTTOM" .. side, target, "BOTTOM" .. side, side == "LEFT" and -px or px, -px)
            t:SetWidth(px)
        end
    end
    -- uf6 (the user: "the healthbar border is inconsistent with the HUD ui as well, I wanted it too match the HUD and name plates with the
    -- borders"): the HUD bar's own frame (Trail.lua's P.frameFor, the same art and cut, scaled to this bar's height): the BG art's dark
    -- middle as the track under the bar, its top and bottom rows as the lip (end caps + a mark-free middle), its sides, the inner shade
    local function hudFrame(b, h)
        local BG, BW, BH = PRD.BG, tonumber(PRD.BG_W), tonumber(PRD.BG_H)
        if type(BG) ~= "table" or not BW or not BH or not (C_Texture and C_Texture.GetAtlasInfo) then return false end
        local info
        for _, a in ipairs(BG) do
            local ok, i = pcall(C_Texture.GetAtlasInfo, a)
            if ok and type(i) == "table" and i.file and type(i.leftTexCoord) == "number" then info = i; break end
        end
        if not info then return false end
        local k = u(h / 8)
        local L, R, Tt, B = info.leftTexCoord, info.rightTexCoord, info.topTexCoord, info.bottomTexCoord
        local host = b:GetParent()
        local lip = CreateFrame("Frame", nil, host)
        lip:SetAllPoints(b); lip:SetFrameLevel(b:GetFrameLevel() + 2); lip:EnableMouse(false)
        local function sub(parent, layer, level, c0, r0, c1, r1)
            local t = parent:CreateTexture(nil, layer, nil, level)
            t:SetTexture(info.file)
            t:SetTexCoord(L + (R - L) * c0 / BW, L + (R - L) * c1 / BW, Tt + (B - Tt) * r0 / BH, Tt + (B - Tt) * r1 / BH)
            return t
        end
        local tr = sub(host, "BACKGROUND", -4, 0, 3, BW, 11)
        tr:SetPoint("TOPLEFT", b, "TOPLEFT", -3 * k, 0); tr:SetPoint("BOTTOMRIGHT", b, "BOTTOMRIGHT", 7 * k, 0)
        local function rim(r0, r1, hh, edge, sideL, sideR)
            local l = sub(lip, "ARTWORK", 1, 0, r0, 11, r1)
            l:SetPoint(edge == "top" and "BOTTOMLEFT" or "TOPLEFT", b, sideL, -3 * k, 0); l:SetSize(11 * k, hh)
            local r = sub(lip, "ARTWORK", 1, 118, r0, BW, r1)
            r:SetPoint(edge == "top" and "BOTTOMRIGHT" or "TOPRIGHT", b, sideR, 7 * k, 0); r:SetSize(14 * k, hh)
            local m = sub(lip, "ARTWORK", 1, 31, r0, 50, r1)
            m:SetPoint(edge == "top" and "BOTTOMLEFT" or "TOPLEFT", l, edge == "top" and "BOTTOMRIGHT" or "TOPRIGHT", 0, 0)
            m:SetPoint(edge == "top" and "BOTTOMRIGHT" or "TOPRIGHT", r, edge == "top" and "BOTTOMLEFT" or "TOPLEFT", 0, 0)
            m:SetHeight(hh)
        end
        rim(0, 3, 3 * k, "top", "TOPLEFT", "TOPRIGHT")
        rim(11, BH, 8 * k, "bottom", "BOTTOMLEFT", "BOTTOMRIGHT")
        local lf = sub(lip, "ARTWORK", 1, 0, 3, 3, 11)
        lf:SetPoint("TOPRIGHT", b, "TOPLEFT", 0, 0); lf:SetPoint("BOTTOMRIGHT", b, "BOTTOMLEFT", 0, 0); lf:SetWidth(3 * k)
        local rt = sub(lip, "ARTWORK", 1, 125, 3, BW, 11)
        rt:SetPoint("TOPLEFT", b, "TOPRIGHT", 0, 0); rt:SetPoint("BOTTOMLEFT", b, "BOTTOMRIGHT", 0, 0); rt:SetWidth(7 * k)
        local sh = lip:CreateTexture(nil, "ARTWORK", nil, 0)
        sh:SetColorTexture(0, 0, 0, 1)
        sh:SetPoint("TOPLEFT", b, "TOPLEFT", 0, 0); sh:SetPoint("TOPRIGHT", b, "TOPRIGHT", 0, 0); sh:SetHeight(u(h) * 0.45)
        if not pcall(function() sh:SetGradient("VERTICAL", CreateColor(0, 0, 0, 0), CreateColor(0, 0, 0, 0.47)) end) then sh:SetAlpha(0.2) end
        return true
    end
    -- uf7 (the user: "The level part also needs the border"): the same HUD art as a NINE-SLICE for a small box - its corners, its lip
    -- edges at the bar's thickness (3 texels left / top, 7 right, 8 bottom: the art's own shade side) and its dark track as the ground
    local function hudBox(box, kh)
        local BG, BW, BH = PRD.BG, tonumber(PRD.BG_W), tonumber(PRD.BG_H)
        if type(BG) ~= "table" or not BW or not BH or not (C_Texture and C_Texture.GetAtlasInfo) then return false end
        local info
        for _, a in ipairs(BG) do
            local ok, i = pcall(C_Texture.GetAtlasInfo, a)
            if ok and type(i) == "table" and i.file and type(i.leftTexCoord) == "number" then info = i; break end
        end
        if not info then return false end
        local k = u(kh / 8)
        local L, R, Tt, B = info.leftTexCoord, info.rightTexCoord, info.topTexCoord, info.bottomTexCoord
        -- uf9 (the user: "the shadows in side of the level box on the top and bottom of the number look bad"): the art's top lip and left
        -- side only, mirrored for the bottom and the right (an even 3-texel edge all round, none of the bar's shade rows), a FLAT ground
        local function piece(layer, c0, r0, c1, r1)
            local t = box:CreateTexture(nil, layer, nil, 1)
            t:SetTexture(info.file)
            t:SetTexCoord(L + (R - L) * c0 / BW, L + (R - L) * c1 / BW, Tt + (B - Tt) * r0 / BH, Tt + (B - Tt) * r1 / BH)
            return t
        end
        local g = box:CreateTexture(nil, "BACKGROUND", nil, 1); g:SetAllPoints(box); g:SetColorTexture(0.06, 0.055, 0.05, 0.94)
        local e = 3 * k
        local tl = piece("BORDER", 0, 0, 3, 3);   tl:SetSize(e, e); tl:SetPoint("BOTTOMRIGHT", box, "TOPLEFT", 0, 0)
        local tr = piece("BORDER", 3, 0, 0, 3);   tr:SetSize(e, e); tr:SetPoint("BOTTOMLEFT", box, "TOPRIGHT", 0, 0)
        local bl = piece("BORDER", 0, 3, 3, 0);   bl:SetSize(e, e); bl:SetPoint("TOPRIGHT", box, "BOTTOMLEFT", 0, 0)
        local br = piece("BORDER", 3, 3, 0, 0);   br:SetSize(e, e); br:SetPoint("TOPLEFT", box, "BOTTOMRIGHT", 0, 0)
        local top = piece("BORDER", 31, 0, 50, 3); top:SetPoint("BOTTOMLEFT", box, "TOPLEFT", 0, 0); top:SetPoint("BOTTOMRIGHT", box, "TOPRIGHT", 0, 0); top:SetHeight(e)
        local bot = piece("BORDER", 31, 3, 50, 0); bot:SetPoint("TOPLEFT", box, "BOTTOMLEFT", 0, 0); bot:SetPoint("TOPRIGHT", box, "BOTTOMRIGHT", 0, 0); bot:SetHeight(e)
        local lf = piece("BORDER", 0, 3, 3, 11);  lf:SetPoint("TOPRIGHT", box, "TOPLEFT", 0, 0); lf:SetPoint("BOTTOMRIGHT", box, "BOTTOMLEFT", 0, 0); lf:SetWidth(e)
        local rt = piece("BORDER", 3, 3, 0, 11);  rt:SetPoint("TOPLEFT", box, "TOPRIGHT", 0, 0); rt:SetPoint("BOTTOMLEFT", box, "BOTTOMRIGHT", 0, 0); rt:SetWidth(e)
        return true
    end
    local function npBar(parent, w, h, fillAtlas, rim)
        local b = CreateFrame("StatusBar", nil, parent)
        b:SetSize(u(w), u(h))
        local bg = b:CreateTexture(nil, "BACKGROUND", nil, -1)
        bg:SetAllPoints(b); bg:SetColorTexture(0.07, 0.07, 0.07, 0.9)
        local fill = b:CreateTexture(nil, "ARTWORK")
        b.fill = fill
        if rim and type(PRD.setFill) == "function" then b.atlasFill = PRD.setFill(b)            -- the HUD bar's own fill
        else b.atlasFill = atlas(fill, fillAtlas) end
        if not b.atlasFill and not rim then fill:SetTexture("Interface\\TargetingFrame\\UI-StatusBar") end
        b:SetStatusBarTexture(b.fill)
        local okT, real = pcall(function() return b:GetStatusBarTexture() end)
        b.fill = (okT and type(real) == "table") and real or b.fill
        -- uf6: the main bars wear the HUD bar's frame (its track is their ground); the others a thin black edge
        if rim and hudFrame(b, h) then bg:SetColorTexture(0, 0, 0, 0)
        else edgeBox(b, b, rim and 2 or 1, 0, 0, 0, 1, "BACKGROUND", -8) end
        b:SetMinMaxValues(0, 1); b:SetValue(1)
        return b
    end
    local function auraIcon(parent)
        local o = { f = CreateFrame("Frame", nil, parent) }
        o.f:SetSize(u(TB.ICON), u(TB.ICON))
        o.tx = o.f:CreateTexture(nil, "ARTWORK"); o.tx:SetAllPoints(o.f); o.tx:SetTexCoord(0.07, 0.93, 0.07, 0.93)
        o.bd = o.f:CreateTexture(nil, "OVERLAY"); o.bd:SetPoint("TOPLEFT", o.f, "TOPLEFT", -u(1), u(1)); o.bd:SetPoint("BOTTOMRIGHT", o.f, "BOTTOMRIGHT", u(1), -u(1))
        o.bd:Hide()
        o.cd = CreateFrame("Cooldown", nil, o.f, "CooldownFrameTemplate")
        o.cd:SetAllPoints(o.f); o.cd:SetReverse(true)
        -- the game draws the seconds itself (secret-safe); small, in the icon's lower right corner
        pcall(function() o.cd:SetHideCountdownNumbers(false) end)
        pcall(function()
            for _, rg in ipairs({ o.cd:GetRegions() }) do
                if rg and rg.GetObjectType and rg:GetObjectType() == "FontString" then
                    rg:SetFont(FONT, u(10), "OUTLINE"); rg:ClearAllPoints(); rg:SetPoint("BOTTOMRIGHT", o.f, "BOTTOMRIGHT", u(1), -u(1))
                end
            end
        end)
        o.n = text(o.f, 10); o.n:SetPoint("TOPLEFT", o.f, "TOPLEFT", -u(1), u(1))
        o.f:Hide()
        return o
    end
    -- ---------------------------------------------------------------- THE PARTY BLOCK (hub35: party1..4 in the bottom's arrangement)
    -- hub34 (party_design.md D and the user's decisions 2026-11-11): each row the target bar's own builder (npBar with the HUD rim). A
    -- 36 px glyph slot always kept (the LB glyph while Party mode is on), the level in a hudBox, the bar, the name above its lip, the
    -- power under it. Party mode on: a gold ring on the pick (PadMap's box.u, the bottom's box) and a small white chevron at your
    -- target; off: a white ring on your target. Dead: a grey ground and a skull; offline or out of range (PadMap's one rng): the row at
    -- 0.45. In a raid: nothing. Follows our party switch (SW_UFP). Beside it an 18 px Select tab ("Top screen: small Select tab (18 px)
    -- beside the party column, same off/on/not-bound looks as the bottom"). Health and power only through feed (the widget setters);
    -- every other read plain-checked.
    -- hub35 (the user, 2026-11-11: "the other 4 party members are in a square shape in the top left"; the adaptive rows): ONLY the party
    -- members, placed as on the bottom - PadMap's entries (you, the pet while out, party1..4: WC.padMap.party.units) in min(n, 3) rows,
    -- the bottom max(0, n - 3) shared by two cells; the rows holding no party member are dropped, the rest keep their order and sides.
    -- A full row's bar spans both columns (W_FULL), a half one column (W_HALF). You and your pet are the HUD's own bars (Trail prd14).
    -- hub40 (the user, 2026-11-12: "I had someone join my group on an old build and their health bar was a mile long"): that spanning
    -- bar WAS the mile - one member (or the first rows of 3 / 4) got W_FULL = 510 px, both columns and the gap, wider than the target
    -- bar (372). Now EVERY bar is one column's bar (W_BAR = COL_W - BAR_X - 30 = 194): a full row is a row alone, left-aligned in the
    -- left column; the right column only ever holds a shared row's right half. W_HALF / W_FULL stay as names, both W_BAR.
    local PF = { X = 24, Y = 250, PITCH = 64, SLOT = 36, LV_X = 44, LV_W = 26, BAR_X = 76, W = 228, NAME = 15, NAME_H = 20, HP = 18,
                 GAP = 2, MP = 7, TAB_GAP = 6, TAB_W = 22, FADE_S = 0.2, fa = 1, TAB_CAP = 0.3, DIM = 0.45, frames = {}, GOLD = { 1, 0.82, 0 }, SOFT = { 226 / 255, 170 / 255, 80 / 255 },
                 EDGE = { 140 / 255, 110 / 255, 52 / 255 }, PANEL = { 20 / 255, 17 / 255, 13 / 255 },
                 COL_W = 300, COL_GAP = 16, W_BAR = 194, W_HALF = 194, W_FULL = 194, cells = {} }
    UF.pf = PF
    -- a ring of four edges, recoloured in place (party_design.md: "3 px outside the row's hudFrame")
    function PF.ring(parent, layer)
        local r = { t = {} }
        for i = 1, 4 do r.t[i] = parent:CreateTexture(nil, layer or "OVERLAY", nil, 6); r.t[i]:Hide() end
        -- hub35 review (2026-11-11, the user's gold ring on the pick): ONE mouse-less area frame, TOPLEFT on tl and BOTTOMRIGHT on br, and
        -- every edge's two points on that area alone (an edge's two points on two regions clashed: the top edge's right end on the power
        -- bar's top, the bottom edge's left end on the health bar's bottom)
        function r.place(tl, br, tlx, tly, brx, bry, px)
            local t = r.t
            if not r.area then r.area = CreateFrame("Frame", nil, parent); r.area:EnableMouse(false) end
            local a = r.area
            a:ClearAllPoints()
            a:SetPoint("TOPLEFT", tl, "TOPLEFT", tlx, tly); a:SetPoint("BOTTOMRIGHT", br, "BOTTOMRIGHT", brx, bry)
            for i = 1, 4 do t[i]:ClearAllPoints() end
            t[1]:SetPoint("TOPLEFT", a, "TOPLEFT", 0, 0); t[1]:SetPoint("TOPRIGHT", a, "TOPRIGHT", 0, 0); t[1]:SetHeight(px)
            t[2]:SetPoint("BOTTOMLEFT", a, "BOTTOMLEFT", 0, 0); t[2]:SetPoint("BOTTOMRIGHT", a, "BOTTOMRIGHT", 0, 0); t[2]:SetHeight(px)
            t[3]:SetPoint("TOPLEFT", a, "TOPLEFT", 0, 0); t[3]:SetPoint("BOTTOMLEFT", a, "BOTTOMLEFT", 0, 0); t[3]:SetWidth(px)
            t[4]:SetPoint("TOPRIGHT", a, "TOPRIGHT", 0, 0); t[4]:SetPoint("BOTTOMRIGHT", a, "BOTTOMRIGHT", 0, 0); t[4]:SetWidth(px)
        end
        function r.show(rr, g, b, a)
            for i = 1, 4 do r.t[i]:SetColorTexture(rr, g, b, a); r.t[i]:Show() end
            r.on = true
        end
        function r.hide() for i = 1, 4 do r.t[i]:Hide() end; r.on = false end
        return r
    end
    -- hub35: the entries in pick order - PadMap's own list (one truth with the bottom), else you + the party by its plain count
    function PF.units()
        local p = type(WC.padMap) == "table" and WC.padMap.party
        local list = type(p) == "table" and type(p.units) == "function" and call(p.units) or nil
        local out = {}
        if type(list) == "table" then
            for _, x in ipairs(list) do if type(x) == "string" and plainV(x) then out[#out + 1] = x end end
        end
        if #out == 0 then
            out[1] = "player"
            for i = 1, UF.partyCount() do out[#out + 1] = "party" .. i end
        end
        return out
    end
    -- hub38: the bottom's arrangement for [units] (PadMap m134; the user, 2026-11-11, after using 1428: "the pet should be to the right of
    -- the player"): you (+ the pet on your right) share the first row, which the top never draws (the HUD's own bars); the k party
    -- members fill the rows under it by the adaptive rule among themselves - the first f full rows (f = k up to 2, else 4 - k), the rest
    -- in pairs, left then right -> { { unit, row, side } } (rows from 0), the row count
    -- hub42 (the user, 2026-11-12): [at] = where the block stands. "left": ONE column in the top left, one member a row, party1..4 top to
    -- bottom (side "left"); "hud" (Party mode on): under the HUD bar, two a row - [party1][party2] / [party3][party4] (a lone last
    -- member on the left). The bottom screen's order: party1..4 by number, whatever order PadMap's list holds them in.
    function PF.layout(units, at)
        local party, seen = {}, {}
        for k = 1, math.min(#units, 6) do
            local unit = units[k]
            if type(unit) == "string" and unit:match("^party[1-4]$") and not seen[unit] then seen[unit] = true; party[#party + 1] = unit end
        end
        table.sort(party)
        local cells, rows = {}, 0
        for j, unit in ipairs(party) do
            local row, side
            if at == "hud" then row, side = math.floor((j - 1) / 2), ((j % 2) == 1) and "left" or "right"
            else row, side = j - 1, "left" end
            cells[#cells + 1] = { unit = unit, row = row, side = side }
            if row + 1 > rows then rows = row + 1 end
        end
        return cells, rows
    end
    -- hub42: the HUD bar's place (Trail prd15 WC.prd.partyGeo, UI units from UIParent's CENTER: x = its HP bar's left, y = the top of the
    -- room under its bars) - the grid's first column has its bar under the HP bar's left. No Trail: the HUD's default place.
    function PF.geo()
        local P = WC.prd
        local g = type(P) == "table" and type(P.partyGeo) == "function" and call(P.partyGeo) or nil
        if type(g) == "table" and type(g.x) == "number" and type(g.y) == "number" then return g end
        return { x = -u(140), y = -u(350 + 49), fallback = true }
    end
    -- party member i's row (1..4): made once, never destroyed; placed by PF.place
    function PF.frame(i)
        if PF.frames[i] then return PF.frames[i] end
        local f = CreateFrame("Frame", "WowCompanionUFParty" .. i, UIParent)
        f:SetSize(u(PF.BAR_X + PF.W_BAR + 30), u(PF.NAME_H + PF.HP + PF.GAP + PF.MP))
        f:SetPoint("TOPLEFT", UIParent, "TOPLEFT", u(PF.X), -u(PF.Y + (i - 1) * PF.PITCH))
        f:SetFrameStrata("LOW"); f:EnableMouse(false); f:Hide()
        f.unit = "party" .. i
        f.look = {}
        -- the bar (the target bar's builder: the HUD rim), the power under it
        f.hp = npBar(f, PF.W_BAR, PF.HP, "UI-HUD-CoolDownManager-Bar", true)
        f.hp:SetPoint("TOPLEFT", f, "TOPLEFT", u(PF.BAR_X), -u(PF.NAME_H))
        f.mp = npBar(f, PF.W_BAR, PF.MP, "UI-HUD-CoolDownManager-Bar", true)
        f.mp:SetPoint("TOPLEFT", f.hp, "BOTTOMLEFT", 0, -u(PF.GAP))
        -- the name above the bar's lip (the target bar's rule)
        f.name = text(f, PF.NAME); f.name:SetJustifyH("LEFT")
        f.name:SetPoint("BOTTOMLEFT", f.hp, "TOPLEFT", u(2), u(PF.HP / 8) * 3 + u(2))
        -- the glyph slot (36 px, always kept), the LB glyph in it while Party mode is on
        f.glyph = f:CreateTexture(nil, "OVERLAY"); f.glyph:SetSize(u(PF.SLOT - 6), u(PF.SLOT - 6))
        f.glyph:SetPoint("CENTER", f, "TOPLEFT", u(PF.SLOT / 2), -u(PF.NAME_H + (PF.HP + PF.GAP + PF.MP) / 2)); f.glyph:Hide()
        f.lb = text(f, 11); f.lb:SetPoint("CENTER", f.glyph, "CENTER", 0, 0)
        -- the level in the HUD art's small box, the skull there when dead
        f.lvBox = CreateFrame("Frame", nil, f)
        f.lvBox:SetSize(u(PF.LV_W), u(PF.HP)); f.lvBox:SetPoint("RIGHT", f.hp, "LEFT", -u(6), 0)
        if not hudBox(f.lvBox, PF.HP) then
            local g = f.lvBox:CreateTexture(nil, "BACKGROUND"); g:SetAllPoints(f.lvBox); g:SetColorTexture(0.06, 0.055, 0.05, 0.94)
            edgeBox(f.lvBox, f.lvBox, 1, 0.42, 0.39, 0.33, 1, "BORDER", 0)
        end
        f.lvl = text(f.lvBox, 12); f.lvl:SetAllPoints(f.lvBox); f.lvl:SetJustifyH("CENTER"); f.lvl:SetTextColor(1, 0.83, 0.42)
        f.skull = f.lvBox:CreateTexture(nil, "ARTWORK"); f.skull:SetSize(u(15), u(15)); f.skull:SetPoint("CENTER", f.lvBox, "CENTER", 0, 0); f.skull:Hide()
        if not atlas(f.skull, "ui-hud-nameplates-levelindicator-skull") then f.skull:SetTexture("Interface\\TargetingFrame\\UI-TargetingFrame-Skull") end
        -- dead: a grey ground over the bar
        f.deadG = f.hp:CreateTexture(nil, "OVERLAY", nil, 5); f.deadG:SetAllPoints(f.hp); f.deadG:SetColorTexture(0.5, 0.5, 0.5, 0.35); f.deadG:Hide()
        -- the rings: 3 px outside the HUD frame (its lip: 3 texels up / left, 7 right, 8 down, the power bar's under the bar's)
        local kh, km = u(PF.HP / 8), u(PF.MP / 8)
        f.ring = PF.ring(f)
        f.ring.place(f.hp, f.mp, -3 * kh - u(3), 3 * kh + u(3), 7 * kh + u(3), -8 * km - u(3), u(2))
        -- your target, while Party mode is on: a small white chevron right of the bar (the pick is the gold ring)
        f.chev = f:CreateTexture(nil, "OVERLAY"); f.chev:SetSize(u(12), u(14)); f.chev:SetPoint("LEFT", f.hp, "RIGHT", 7 * kh + u(5), 0)
        f.chev:SetTexture("Interface\\ChatFrame\\ChatFrameExpandArrow"); pcall(function() f.chev:SetTexCoord(1, 0, 0, 1) end)
        f.chev:SetVertexColor(1, 1, 1); f.chev:Hide()
        f.look.k = K
        PF.frames[i] = f
        return f
    end
    -- hub40: a row made at another scale (K recomputed at PLAYER_ENTERING_WORLD after it was built): its inner offsets and sizes again
    -- at today's K (the bar's start, the power under it, the name, the glyph slot, the level box, the ring, the chevron, the fonts)
    function PF.rescale(f)
        if f.look.k == K then return end
        f.look.k = K
        local kh, km = u(PF.HP / 8), u(PF.MP / 8)
        f.hp:ClearAllPoints(); f.hp:SetPoint("TOPLEFT", f, "TOPLEFT", u(PF.BAR_X), -u(PF.NAME_H))
        f.mp:ClearAllPoints(); f.mp:SetPoint("TOPLEFT", f.hp, "BOTTOMLEFT", 0, -u(PF.GAP))
        f.name:ClearAllPoints(); f.name:SetPoint("BOTTOMLEFT", f.hp, "TOPLEFT", u(2), kh * 3 + u(2))
        f.glyph:SetSize(u(PF.SLOT - 6), u(PF.SLOT - 6)); f.glyph:ClearAllPoints()
        f.glyph:SetPoint("CENTER", f, "TOPLEFT", u(PF.SLOT / 2), -u(PF.NAME_H + (PF.HP + PF.GAP + PF.MP) / 2))
        f.lvBox:SetSize(u(PF.LV_W), u(PF.HP)); f.lvBox:ClearAllPoints(); f.lvBox:SetPoint("RIGHT", f.hp, "LEFT", -u(6), 0)
        f.skull:SetSize(u(15), u(15))
        f.ring.place(f.hp, f.mp, -3 * kh - u(3), 3 * kh + u(3), 7 * kh + u(3), -8 * km - u(3), u(2))
        f.chev:SetSize(u(12), u(14)); f.chev:ClearAllPoints(); f.chev:SetPoint("LEFT", f.hp, "RIGHT", 7 * kh + u(5), 0)
        pcall(function()
            f.name:SetFont(FONT, u(PF.NAME), "OUTLINE"); f.lb:SetFont(FONT, u(11), "OUTLINE"); f.lvl:SetFont(FONT, u(12), "OUTLINE")
        end)
    end
    -- hub35: a row's place in the block (its row, its side) and its bar's width; only when it changed
    -- hub40 (the user, 2026-11-12, "a mile long"): one column's bar whatever the side (min(W_BAR, the column's room), never W_FULL's
    -- old two-column span), and the place keyed on K too, so a scale recomputed at PLAYER_ENTERING_WORLD re-sizes a row made before it
    -- (its size, its two bars' width AND height, all from today's K; before, a row kept the scale it was made with until it moved)
    -- hub42: [g] = PF.geo() while the block stands under the HUD (Party mode), nil in the top-left column; our own plain frames only (no
    -- secure frame is moved: this runs in fights too)
    function PF.place(f, row, side, g)
        local key = row .. side .. "@" .. tostring(K) .. (g and string.format(":%.2f,%.2f", g.x, g.y) or "")
        if f.look.place == key then return end
        f.look.place, f.look.row, f.look.side, f.look.at = key, row, side, g and "hud" or "left"
        local w = math.min(PF.W_BAR, PF.COL_W - PF.BAR_X - 30)
        local dx = (side == "right") and (PF.COL_W + PF.COL_GAP) or 0
        f:ClearAllPoints()
        if g then f:SetPoint("TOPLEFT", UIParent, "CENTER", g.x - u(PF.BAR_X) + u(dx), g.y - u(row * PF.PITCH))
        else f:SetPoint("TOPLEFT", UIParent, "TOPLEFT", u(PF.X + dx), -u(PF.Y + row * PF.PITCH)) end
        f:SetSize(u(PF.BAR_X + w + 30), u(PF.NAME_H + PF.HP + PF.GAP + PF.MP))
        f.hp:SetSize(u(w), u(PF.HP)); f.mp:SetSize(u(w), u(PF.MP))
        PF.rescale(f)
        f.look.w = w
    end
    -- Party mode's word (PadMap's exports): on, the picked unit, the LB button of each unit, Select's state, the one range answer
    function PF.mode()
        local p = type(WC.padMap) == "table" and WC.padMap.party
        if type(p) ~= "table" then return { on = false, lb = {} } end
        local box = type(p.box) == "function" and call(p.box) or nil
        local lb = type(p.lb) == "function" and call(p.lb) or nil
        return { on = type(p.on) == "function" and bool(p.on) == true, pick = type(box) == "table" and box.u or nil,
                 lb = type(lb) == "table" and lb or {}, sel = type(p.selWant) == "function" and call(p.selWant) or nil,
                 rng = type(p.rng) == "function" and p.rng or nil }
    end
    function UF.partyCount()
        if bool(IsInRaid) then return 0 end
        local n = call(GetNumSubgroupMembers)
        return plainV(n) and type(n) == "number" and math.min(4, n) or 0
    end
    -- hub35: what shows - the party members' cells (none: a raid, our switch off, solo) and the tab's rows (the block's; one while solo
    -- with Party mode on, so the tab still says it is on)
    -- hub42: laid out where the block stands now (PF.at; it moves only while unseen, PF.fadeTick); the tab only under the HUD
    function UF.partyCells(pm)
        if not UF.party or bool(IsInRaid) then return {}, 0, 0 end
        local cells, rows = PF.layout(PF.units(), PF.at)
        local tab = 0
        if PF.at == "hud" then tab = math.max(rows, 1) end
        return cells, rows, tab
    end
    function PF.row(f, pm)
        local unit = f.unit
        setText(f.name, call(UnitName, unit) or "")
        local r, g, b = classRGB(unit)
        if r then f.name:SetTextColor(r, g, b) else f.name:SetTextColor(1, 0.82, 0) end
        local lv = call(UnitLevel, unit)
        if plainV(lv) and type(lv) == "number" and lv < 0 then f.lvl:SetText("??") else setText(f.lvl, lv) end
        -- the health in the HUD's own green, set every step (the colour is never left to a past paint)
        local hc = type(PRD.HEALTH_RGB) == "table" and PRD.HEALTH_RGB or { 0.12, 0.86, 0.12 }
        paint(f.hp, hc[1], hc[2], hc[3])
        feed(f.hp, f.mp, unit)
        local online = bool(UnitIsConnected, unit)
        local dead = bool(UnitIsDeadOrGhost, unit)
        local near = nil
        if unit ~= "player" and pm.rng then
            local v = call(pm.rng, unit)
            if v == false then near = false end
        end
        f.look.dead = dead == true
        if dead then f.deadG:Show(); f.skull:Show(); f.lvl:Hide() else f.deadG:Hide(); f.skull:Hide(); f.lvl:Show() end
        f.look.dim = online == false or near == false
        f:SetAlpha((f.look.dim and PF.DIM or 1) * (PF.fa or 1))                -- hub42: times the block's fade
        -- the LB glyph in its slot while Party mode is on (the slot stays, empty, while it is off)
        local key = pm.on and (pm.lb["p" .. unit:sub(6)]) or nil
        setGlyph(f.glyph, f.lb, key)
        f.look.glyph = key
        local isTarget = bool(UnitIsUnit, unit, "target")
        if pm.on and pm.pick == unit then f.ring.show(PF.GOLD[1], PF.GOLD[2], PF.GOLD[3], 0.95); f.look.ring = "gold"
        elseif not pm.on and isTarget then f.ring.show(1, 1, 1, 0.75); f.look.ring = "white"
        else f.ring.hide(); f.look.ring = nil end
        f.look.chev = (pm.on and isTarget) == true
        if f.look.chev then f.chev:Show() else f.chev:Hide() end
        f:Show()
    end
    -- hub38 THE GAME'S SIDE TAB (the user, 2026-11-11: "It needs to use wow assets and sit outside of the player brand look more like its
    -- attached to the side of the screen"): [name]'s atlas in three slices on [t] (the top and bottom TAB_CAP of the art at their own
    -- aspect, the middle stretched), so a tall narrow tab keeps the art's bevelled corners; the art's open (left) side on the screen's
    -- edge. -> the slices, else one stretched texture (no atlas info), else an empty list (no art: the glyphs alone, never a drawn box)
    function PF.sideTab(t, name, layer, sub)
        local info
        if type(C_Texture) == "table" and type(C_Texture.GetAtlasInfo) == "function" then
            local ok, i = pcall(C_Texture.GetAtlasInfo, name)
            if ok and type(i) == "table" and i.file and type(i.leftTexCoord) == "number" and tonumber(i.width) and tonumber(i.height) then info = i end
        end
        if not info then
            local x = t:CreateTexture(nil, layer, nil, sub)
            if atlas(x, name) then x:SetAllPoints(t); return { x } end
            x:Hide()
            return {}
        end
        local L, R, Tt, B, c = info.leftTexCoord, info.rightTexCoord, info.topTexCoord, info.bottomTexCoord, PF.TAB_CAP
        local capR = (tonumber(info.height) * c) / tonumber(info.width)
        local capPx = u(PF.TAB_W) * capR
        local function piece(r0, r1)
            local x = t:CreateTexture(nil, layer, nil, sub)
            x:SetTexture(info.file)
            x:SetTexCoord(L, R, Tt + (B - Tt) * r0, Tt + (B - Tt) * r1)
            return x
        end
        local top, bot, mid = piece(0, c), piece(1 - c, 1), piece(c, 1 - c)
        top:SetPoint("TOPLEFT", t, "TOPLEFT", 0, 0); top:SetPoint("TOPRIGHT", t, "TOPRIGHT", 0, 0); top:SetHeight(capPx)
        bot:SetPoint("BOTTOMLEFT", t, "BOTTOMLEFT", 0, 0); bot:SetPoint("BOTTOMRIGHT", t, "BOTTOMRIGHT", 0, 0); bot:SetHeight(capPx)
        mid:SetPoint("TOPLEFT", top, "BOTTOMLEFT", 0, 0); mid:SetPoint("BOTTOMRIGHT", bot, "TOPRIGHT", 0, 0)
        top.capR, bot.capR = capR, capR
        return { top, mid, bot }
    end
    PF.TAB_ART, PF.TAB_LIT = "common-sidetab-c60-2x", "common-sidetab-selected-c60-2x"
    -- THE TOP SELECT TAB (party_design.md E, decision 2): left of the column, its full height; off (Select bound), on (lit, the B glyph
    -- under the Select glyph), not bound (the glyph at 40 %, a lock); no words. hub38: the game's side tab flush on the screen's left
    -- edge (x 0), lit by the game's selected side tab over it; no drawn fill or edge
    function PF.tabMake()
        if PF.tab then return PF.tab end
        local t = CreateFrame("Frame", "WowCompanionUFPartyTab", UIParent)
        t:SetFrameStrata("LOW"); t:EnableMouse(false); t:Hide()
        t:SetWidth(u(PF.TAB_W))                                         -- (hub42: placed by PF.tabStep, beside the block under the HUD)
        t.base = PF.sideTab(t, PF.TAB_ART, "BACKGROUND", 0)
        t.lit = PF.sideTab(t, PF.TAB_LIT, "BORDER", 1)
        t.sel = t:CreateTexture(nil, "ARTWORK"); t.sel:SetSize(u(PF.TAB_W - 2), u(PF.TAB_W - 2)); t.sel:SetPoint("TOP", t, "TOP", 0, -u(4))
        t.selW = text(t, 11); t.selW:SetPoint("CENTER", t.sel, "CENTER", 0, 0)
        t.b = t:CreateTexture(nil, "ARTWORK"); t.b:SetSize(u(PF.TAB_W - 6), u(PF.TAB_W - 6)); t.b:SetPoint("TOP", t.sel, "BOTTOM", 0, -u(3)); t.b:Hide()
        t.lock = t:CreateTexture(nil, "OVERLAY"); t.lock:SetSize(u(12), u(14)); t.lock:SetPoint("BOTTOM", t, "BOTTOM", 0, u(3)); t.lock:Hide()
        if not atlas(t.lock, "Forge-Lock") then t.lock:SetTexture("Interface\\LFGFrame\\UI-LFG-ICON-LOCK") end
        local db = _G.WowCompanionSpellsDB
        local plain = type(db) == "table" and db.padLayout == "plain"
        local names = plain and { "Gamepad_Ltr_View_64", "Gamepad_Ltr_Minus_64", "Gamepad_Gen_Minus_64" }
                             or { "Gamepad_Rev_Minus_64", "Gamepad_Gen_Minus_64" }
        t.hasSel = false
        for _, a in ipairs(names) do if atlas(t.sel, a) then t.hasSel = true break end end
        if not t.hasSel then t.sel:Hide(); t.selW:SetText("-") end
        local bk = glyphAtlas("PAD2")
        t.hasB = false
        for _, a in ipairs(bk) do if atlas(t.b, a) then t.hasB = true break end end
        t.k = K
        PF.tab = t
        return t
    end
    -- hub41 (the user, 2026-11-12, the top screen's party renders): the tab made at another scale (K recomputed at PLAYER_ENTERING_WORLD
    -- after it was built) - its place beside the first row, its width, the side tab's cap slices, the glyphs, the lock again at today's K
    function PF.tabScale(t)
        if t.k == K then return end
        t.k = K
        t.at = nil                                                      -- (its place again at today's K: PF.tabStep)
        t:SetWidth(u(PF.TAB_W))
        for _, parts in ipairs({ t.base, t.lit }) do
            local top, bot = parts[1], parts[3]
            if top and bot and top.capR then top:SetHeight(u(PF.TAB_W) * top.capR); bot:SetHeight(u(PF.TAB_W) * bot.capR) end
        end
        t.sel:SetSize(u(PF.TAB_W - 2), u(PF.TAB_W - 2)); t.sel:ClearAllPoints(); t.sel:SetPoint("TOP", t, "TOP", 0, -u(4))
        t.b:SetSize(u(PF.TAB_W - 6), u(PF.TAB_W - 6)); t.b:ClearAllPoints(); t.b:SetPoint("TOP", t.sel, "BOTTOM", 0, -u(3))
        t.lock:SetSize(u(12), u(14)); t.lock:ClearAllPoints(); t.lock:SetPoint("BOTTOM", t, "BOTTOM", 0, u(3))
        pcall(function() t.selW:SetFont(FONT, u(11), "OUTLINE") end)
    end
    -- hub42 (the user's pick B, 2026-11-12): only while the block stands under the HUD (Party mode), at its LEFT edge - the tab's right
    -- side TAB_GAP left of the first column's glyph slot, its top level with the first row's name, the rows' full height (one row's
    -- while solo); no tab in the top-left column (Party mode off) and none on the screen's edge any more
    function PF.tabStep(rows, pm, g)
        local want = rows > 0 and WC.partyOk == true and PF.at == "hud" and g ~= nil
        if not want then
            if PF.tab then PF.tab:Hide() end
            PF.tabLook = nil
            return
        end
        local t = PF.tabMake()
        PF.tabScale(t)
        local at = string.format("%.2f,%.2f@%s", g.x, g.y, tostring(K))
        if t.at ~= at then
            t.at = at
            t:ClearAllPoints()
            t:SetPoint("TOPRIGHT", UIParent, "CENTER", g.x - u(PF.BAR_X + PF.TAB_GAP), g.y - u(PF.NAME_H - 4))
        end
        t:SetHeight(u(rows * PF.PITCH - (PF.PITCH - PF.HP - PF.GAP - PF.MP) + 4))
        t:SetAlpha(PF.fa or 1)
        -- (lit through its fade out too: Party mode just went off, the block leaving)
        local look = (pm.on or PF.fdir == -1) and "on" or (pm.sel == "bound" and "off" or "unbound")
        if look ~= PF.tabLook then
            PF.tabLook = look
            -- hub38: lit = the game's selected side tab over the plain one; off / not bound: the plain one alone, dimmer while not bound
            for _, x in ipairs(t.base) do x:SetAlpha(look == "unbound" and 0.6 or 1); x:Show() end
            for _, x in ipairs(t.lit) do if look == "on" then x:Show() else x:Hide() end end
            t.sel:SetAlpha(look == "unbound" and 0.4 or 1); t.selW:SetAlpha(look == "unbound" and 0.4 or 1)
            if look == "on" and t.hasB then t.b:Show() else t.b:Hide() end
            if look == "unbound" then t.lock:Show() else t.lock:Hide() end
        end
        t:Show()
    end
    -- hub42: draw the block where it stands (PF.at), at the fade's alpha; under the HUD it tells Trail the room it takes (prd15
    -- WC.prd.partyRoom: the HUD's aura row moves under it, as it moved under the pet strip)
    function PF.draw(pm)
        local cells, rows, tab = UF.partyCells(pm)
        local g = (PF.at == "hud") and PF.geo() or nil
        local at = {}
        for _, c in ipairs(cells) do at[tonumber(c.unit:sub(6))] = c end
        for i = 1, 4 do
            local c = at[i]
            local f = c and PF.frame(i) or PF.frames[i]
            if f then
                if c then PF.place(f, c.row, c.side, g); PF.row(f, pm) else f:Hide() end
            end
        end
        PF.cells, PF.rows, PF.g = cells, rows, g
        PF.tabStep(tab, pm, g)
        local room = (g and rows > 0) and u(rows * PF.PITCH - (PF.PITCH - PF.NAME_H - PF.HP - PF.GAP - PF.MP)) or 0
        if room ~= PF.room then
            PF.room = room
            local P = WC.prd
            if type(P) == "table" and type(P.partyRoom) == "function" then pcall(P.partyRoom, room) end
        end
    end
    function PF.anyShown()
        for i = 1, 4 do local f = PF.frames[i]; if f and f:IsShown() then return true end end
        return PF.tab ~= nil and PF.tab:IsShown() == true
    end
    -- hub42 THE SEAMLESS MOVE (the user, 2026-11-12: "fade out of the left column and fade back in UNDER the player's HUD bar"): the block
    -- fades out (FADE_S), moves while unseen, fades in (FADE_S); the reverse when Party mode goes off. Alpha only, on our own plain
    -- frames (allowed in a fight); a turn back mid-fade fades in where it stands. Nothing shown: it moves at once.
    function PF.fadeTick(el)
        if not PF.fdir then return end
        PF.fa = (PF.fa or 1) + PF.fdir * (tonumber(el) or 0) / PF.FADE_S
        if PF.fdir < 0 and PF.fa <= 0 then
            PF.fa, PF.fdir = 0, 1
            PF.at = PF.want or PF.at
            PF.draw(PF.mode())                                           -- moved while unseen
        elseif PF.fdir > 0 and PF.fa >= 1 then
            PF.fa, PF.fdir = 1, nil
        end
        for i = 1, 4 do
            local f = PF.frames[i]
            if f and f:IsShown() then f:SetAlpha((f.look.dim and PF.DIM or 1) * PF.fa) end
        end
        if PF.tab and PF.tab:IsShown() then PF.tab:SetAlpha(PF.fa) end
    end
    function UF.partyStep()
        local pm = PF.mode()
        PF.want = (pm.on and WC.partyOk == true) and "hud" or "left"
        if PF.at == nil then PF.at = PF.want end
        if PF.want ~= PF.at then
            if PF.fdir ~= -1 then
                if PF.anyShown() then PF.fdir = -1
                else PF.at, PF.fa, PF.fdir = PF.want, 0, 1 end              -- nothing shown: moved at once, faded in there
            end
        elseif PF.fdir == -1 then
            PF.fdir = 1                                                  -- turned back mid-fade: in again where it stands
        end
        PF.draw(pm)
    end

    local function buildTarget()
        if TB.f then return TB.f end
        local f = CreateFrame("Frame", "WowCompanionUFTarget", UIParent)
        f:SetSize(u(TB.W), u(TB.NAME + 2 + TB.HP))
        f:SetFrameStrata("LOW"); f:EnableMouse(false); f:Hide()
        TB.f = f
        -- the name above the bar, centred
        f.name = text(f, 16); f.name:SetJustifyH("CENTER")
        f.hp = npBar(f, TB.W, TB.HP, "UI-HUD-CoolDownManager-Bar", true)
        f.hp:SetPoint("TOPLEFT", f, "TOPLEFT", 0, -u(TB.NAME + 2))
        -- uf7 (the user: "The name shouldn't sit behind the border"): the name above the bar's lip (3 texels of the HUD art over the bar)
        f.name:SetPoint("BOTTOM", f.hp, "TOP", 0, u(TB.HP / 8) * 3 + u(3))
        f.pct = text(f.hp, 12); f.pct:SetPoint("RIGHT", f.hp, "RIGHT", -u(6), 0)
        -- the level shield on the left: its own dark box in a gold rim, the level in gold (the skull for ??); the elite / rare dragon behind
        f.shield = CreateFrame("Frame", nil, f)
        f.shield:SetSize(u(TB.LV), u(TB.LV)); f.shield:SetPoint("RIGHT", f.hp, "LEFT", -u(14), 0)
        f.shield:SetSize(u(TB.HP + 6), u(TB.HP))                           -- uf7: the bar's height, so its frame lines up with the bar's
        if not hudBox(f.shield, TB.HP) then
            local sg = f.shield:CreateTexture(nil, "BACKGROUND"); sg:SetAllPoints(f.shield); sg:SetColorTexture(0.17, 0.13, 0.09, 0.95)
            edgeBox(f.shield, f.shield, 2, 0.72, 0.58, 0.35, 1, "BORDER", 0)
        end
        f.lvl = text(f.shield, 14); f.lvl:SetShadowOffset(0, 0)
        f.lvl:SetAllPoints(f.shield); f.lvl:SetJustifyH("CENTER"); f.lvl:SetJustifyV("MIDDLE"); f.lvl:SetTextColor(1, 0.83, 0.42)
        f.skull = f.shield:CreateTexture(nil, "ARTWORK"); f.skull:SetSize(u(20), u(20)); f.skull:SetPoint("CENTER", f.shield, "CENTER", 0, 0); f.skull:Hide()
        if not atlas(f.skull, "ui-hud-nameplates-levelindicator-skull") then f.skull:SetTexture("Interface\\TargetingFrame\\UI-TargetingFrame-Skull") end
        f.cls = f:CreateTexture(nil, "BACKGROUND", nil, -5); f.cls:SetSize(u(48), u(48)); f.cls:SetPoint("CENTER", f.shield, "CENTER", -u(4), u(2)); f.cls:Hide()
        f.mark = f:CreateTexture(nil, "OVERLAY"); f.mark:SetSize(u(22), u(22)); f.mark:SetPoint("LEFT", f.hp, "RIGHT", u(7), 0)
        f.mark:SetTexture("Interface\\TargetingFrame\\UI-RaidTargetingIcons"); f.mark:Hide()
        -- uf9 / uf10 (the user's pick "3: Both in the box", "it needs to be a bit bigger"): the inspect prompt right of the bar - the GAME'S
        -- own button glyph (the one over the nameplates: its atlas on our texture, m106's rule) and the spyglass, in the level box's frame
        local pr = CreateFrame("Frame", nil, f)
        pr:SetSize(u(62), u(30)); pr:SetPoint("LEFT", f.hp, "RIGHT", u(14), 0); pr:Hide()
        if not hudBox(pr, TB.HP) then
            local pg = pr:CreateTexture(nil, "BACKGROUND"); pg:SetAllPoints(pr); pg:SetColorTexture(0, 0, 0, 0.6)
            edgeBox(pr, pr, 1, 0.42, 0.39, 0.33, 1, "BORDER", 0)
        end
        pr.btn = pr:CreateTexture(nil, "ARTWORK"); pr.btn:SetSize(u(26), u(26)); pr.btn:SetPoint("LEFT", pr, "LEFT", u(3), 0)
        pr.letter = pr:CreateFontString(nil, "OVERLAY"); pr.letter:SetFont(FONT, u(15), "OUTLINE")   -- only without the atlas
        pr.letter:SetPoint("CENTER", pr.btn, "CENTER", 0, 0); pr.letter:Hide()
        pr.icon = pr:CreateTexture(nil, "ARTWORK"); pr.icon:SetSize(u(24), u(24)); pr.icon:SetPoint("LEFT", pr.btn, "RIGHT", u(4), 0)
        pr.icon:SetTexture("Interface\\Icons\\INV_Misc_Spyglass_02"); pr.icon:SetTexCoord(0.07, 0.93, 0.07, 0.93)
        f.prompt = pr
        -- combo points: a thin pip strip right under the bar (0..max with the gaps drawn over)
        f.cp = npBar(f, TB.W * 0.4, TB.CP, "UI-HUD-CoolDownManager-Bar", false)
        f.cp:SetPoint("TOP", f.hp, "BOTTOM", 0, -u(5)); paint(f.cp, 1, 0.82, 0.1); f.cp:Hide()
        f.cpGaps = {}
        -- the cast bar under it, in the game's casting bar fill
        f.cast = npBar(f, TB.W - TB.CAST - 8, TB.CAST, "ui-castingbar-filling-standard", false)
        f.cast:Hide()
        f.castIcon = f.cast:CreateTexture(nil, "OVERLAY"); f.castIcon:SetSize(u(TB.CAST), u(TB.CAST))
        f.castIcon:SetPoint("RIGHT", f.cast, "LEFT", -u(5), 0); f.castIcon:SetTexCoord(0.07, 0.93, 0.07, 0.93)
        f.castName = text(f.cast, 11); f.castName:SetPoint("CENTER", f.cast, "CENTER", 0, 0)
        -- the auras under it all: debuffs (yours first) from the left, buffs from the right
        f.deb, f.buf = {}, {}
        for i = 1, TB.DEBUFF_MAX do f.deb[i] = auraIcon(f) end
        for i = 1, TB.BUFF_MAX do f.buf[i] = auraIcon(f) end
        -- the target of target, beyond the marker
        f.tot = CreateFrame("Frame", nil, f)
        f.tot:SetSize(u(TB.TOT_W), u(18 + TB.TOT_HP)); f.tot:SetPoint("LEFT", f.hp, "RIGHT", u(36), u(4))
        f.totName = text(f.tot, 13); f.totName:SetJustifyH("LEFT")
        pcall(function() f.totName:SetFont(FONT, u(13), "THICKOUTLINE") end)       -- uf7 ("I cant read the name on the target of target")
        f.totHp = npBar(f.tot, TB.TOT_W, TB.TOT_HP, "UI-HUD-CoolDownManager-Bar", true)
        f.totHp:SetPoint("TOPLEFT", f.tot, "TOPLEFT", 0, -u(17)); paint(f.totHp, 0.1, 0.8, 0.1)
        f.totName:SetPoint("BOTTOMLEFT", f.totHp, "TOPLEFT", 0, u(TB.TOT_HP / 8) * 3 + u(2))
        f.tot:Hide()
        UF.placeTarget()
        return f
    end
    -- centred under the compass bar (Trail.lua's WC.compass.frame), else where it would be
    function UF.placeTarget()
        local f = TB.f
        if not f then return end
        f:ClearAllPoints()
        local c = type(WC.compass) == "table" and WC.compass.frame or nil
        -- uf13 (the user: "extend it a bit more right so that it looks more centered when the Y disappears"): the bar 32 wider and its
        -- middle SHIFT right, so the level box + the bar sit centred under the compass (the prompt adds to the right when it shows)
        if c then f:SetPoint("TOP", c, "BOTTOM", u(TB.SHIFT), -u(6)) else f:SetPoint("TOP", UIParent, "TOP", u(TB.SHIFT), -u(90)) end
    end
    local RAID_ICON = { [1] = { 0, 0.25, 0, 0.25 }, [2] = { 0.25, 0.5, 0, 0.25 }, [3] = { 0.5, 0.75, 0, 0.25 }, [4] = { 0.75, 1, 0, 0.25 },
                        [5] = { 0, 0.25, 0.25, 0.5 }, [6] = { 0.25, 0.5, 0.25, 0.5 }, [7] = { 0.5, 0.75, 0.25, 0.5 }, [8] = { 0.75, 1, 0.25, 0.5 } }
    -- the nameplate's own classification icons (Blizzard_NamePlateClassificationFrame)
    local CLASS_ATLAS = { elite = "nameplates-icon-elite-gold", worldboss = "nameplates-icon-elite-gold",
                          rareelite = "nameplates-icon-elite-silver", rare = "UI-HUD-UnitFrame-Target-PortraitOn-Boss-Rare-Star" }
    -- [unit]'s nameplate, when the game gives one (never a forbidden one: GetNamePlateForUnit hides those)
    local function plateOf(unit)
        local plate = C_NamePlate and call(C_NamePlate.GetNamePlateForUnit, unit)
        return type(plate) == "table" and plate or nil
    end
    -- THE NAMEPLATE'S COLOUR, read from its health bar (what the player sees over the mob); without a plate the game's own nameplate
    -- rule (UnitSelectionColor, the colour the plate would take), then the reaction. Plain values only.
    local function plateRGB(unit)
        local plate = plateOf(unit)
        local hb = plate and call(function() return plate.UnitFrame and plate.UnitFrame.healthBar end)
        if type(hb) == "table" then
            local r, g, b = call(hb.GetStatusBarColor, hb)
            if plainV(r) and plainV(g) and plainV(b) and type(r) == "number" then return r, g, b end
        end
        if type(UnitSelectionColor) == "function" then
            local r, g, b = call(UnitSelectionColor, unit, true)
            if plainV(r) and plainV(g) and plainV(b) and type(r) == "number" then return r, g, b end
        end
        local re = call(UnitReaction, unit, "player")
        if plainV(re) and type(re) == "number" then
            if re <= 2 then return 0.9, 0.12, 0.1 elseif re <= 4 then return 0.95, 0.8, 0.1 end
        end
        return 0.1, 0.8, 0.1
    end
    -- the name's colour as the nameplate writes it, else white
    local function plateNameRGB(unit)
        local plate = plateOf(unit)
        local fs = plate and call(function() return plate.UnitFrame and plate.UnitFrame.name end)
        if type(fs) == "table" then
            local r, g, b = call(fs.GetTextColor, fs)
            if plainV(r) and plainV(g) and plainV(b) and type(r) == "number" then return r, g, b end
        end
        return 1, 1, 1
    end
    -- the TARGET'S nameplate health bar hidden while it is targeted (alpha only; our bar shows it), the previous one given back
    TB.hidPlate = nil
    local function plateBarAlpha(hbc, a) if type(hbc) == "table" then pcall(hbc.SetAlpha, hbc, a) end end
    -- uf8 (the user: "when hiding the name plates, the name and level are still showing"): the WHOLE plate (its UnitFrame: the bars, the
    -- name, the level), alpha only; never your own (the personal bar)
    local function plateHide(on)
        local hbc = nil
        if on and bool(UnitIsUnit, "target", "player") ~= true then
            local plate = plateOf("target")
            hbc = plate and call(function() return plate.UnitFrame end) or nil
            if type(hbc) ~= "table" then hbc = nil end
        end
        if TB.hidPlate and TB.hidPlate ~= hbc then plateBarAlpha(TB.hidPlate, 1) end
        TB.hidPlate = hbc
        if hbc then plateBarAlpha(hbc, 0) end
    end
    UF.plateHide = plateHide
    -- uf4 (the user: "only hide the targets name plate if I am attacking it actually"): your auto attack or Auto Shot on, or both of you in a
    -- fight with a target you can attack (plain readings only)
    local function attacking()
        if bool(IsCurrentSpell, 6603) or bool(IsCurrentSpell, 75) then return true end
        return bool(UnitCanAttack, "player", "target") == true and bool(UnitAffectingCombat, "player") == true
            and bool(UnitAffectingCombat, "target") == true
    end
    -- uf8 (the user: "the hide name plate needs to have the same rules as the hide glyph"): Spells' GROW rule (a fight or an attack on, not
    -- given back by B: WC.tgGrown); attacking() only without Spells' Y binding to read
    local function plateRule()
        if type(WC.tgGrown) == "function" then
            local ok, g = pcall(WC.tgGrown)
            if ok and g ~= nil then return g == true end
        end
        return attacking()
    end
    -- the combo points' gaps for [max] points (the bar is cut into that many pips)
    local function cpGaps(f, max)
        if f.cpMax == max then return end
        f.cpMax = max
        for _, g in ipairs(f.cpGaps) do g:Hide() end
        for k = 1, max - 1 do
            local g = f.cpGaps[k] or f.cp:CreateTexture(nil, "OVERLAY")
            f.cpGaps[k] = g
            g:SetColorTexture(0, 0, 0, 1); g:SetSize(u(3), u(TB.CP))
            g:ClearAllPoints(); g:SetPoint("LEFT", f.cp, "LEFT", u(TB.W * 0.4) * k / max - u(1.5), 0); g:Show()
        end
    end
    local function comboStep(f)
        local _, cls = call(UnitClass, "player")
        local pt = call(UnitPowerType, "player")
        local energy = Enum and Enum.PowerType and Enum.PowerType.Energy or 3
        local want = cls == "ROGUE" or (cls == "DRUID" and plainV(pt) and pt == energy)
        if not want then f.cp:Hide(); return end
        local CP = Enum and Enum.PowerType and Enum.PowerType.ComboPoints or 4
        local max = call(UnitPowerMax, "player", CP)
        max = (plainV(max) and type(max) == "number" and max > 0) and max or 5
        cpGaps(f, max)
        pcall(function() f.cp:SetMinMaxValues(0, max) end)
        local v = nil
        if type(GetComboPoints) == "function" then v = call(GetComboPoints, "player", "target") end
        if v == nil then v = call(UnitPower, "player", CP) end
        if not pcall(function() f.cp:SetValue(v or 0) end) then f.cp:SetValue(0) end
        f.cp:Show()
    end
    -- the cast: the game's own times straight to the bar (min = start, max = end, value = now), moved every frame while it casts;
    -- the game's casting bar fills (standard / channel), else the plain bar in the cast's colour
    local function castStep(f)
        local name, txt, tex, st, en = call(UnitCastingInfo, "target")
        local chan = false
        if name == nil then name, txt, tex, st, en = call(UnitChannelInfo, "target"); chan = name ~= nil end
        if name == nil then f.cast:Hide(); f.casting = nil; return end
        pcall(function() f.cast:SetMinMaxValues(st, en) end)
        local want = chan and "ui-castingbar-filling-channel" or "ui-castingbar-filling-standard"
        if f.cast.fillAtlas ~= want then
            f.cast.fillAtlas = want
            if atlas(f.cast.fill, want) then paint(f.cast, 1, 1, 1)
            elseif chan then paint(f.cast, 0.3, 0.85, 0.3) else paint(f.cast, 1, 0.7, 0) end
        end
        pcall(function() f.castIcon:SetTexture(tex) end)
        setText(f.castName, txt or name)
        -- a channel drains: only when its times are plain (no arithmetic on a secret); else it fills like a cast
        f.chan = (chan and plainV(st) and plainV(en) and type(st) == "number" and type(en) == "number") and { st, en } or nil
        f.casting = true
        f.cast:Show()
    end
    local function auraList(filter, max, skip)
        local out = {}
        for i = 1, 40 do
            if #out >= max then break end
            local a = C_UnitAuras and call(C_UnitAuras.GetAuraDataByIndex, "target", i, filter)
            if type(a) ~= "table" then break end
            local id = a.auraInstanceID
            if not (skip and plainV(id) and skip[id]) then out[#out + 1] = a end
        end
        return out
    end
    -- below the bar: the combo pips, the cast bar, then the auras (the stack closes up when a part is not shown)
    local function underLayout(f)
        local y = TB.HP + 5
        if f.cp:IsShown() then y = y + TB.CP + 5 end
        f.cast:ClearAllPoints(); f.cast:SetPoint("TOPRIGHT", f.hp, "TOPRIGHT", 0, -u(y))
        if f.casting then y = y + TB.CAST + 6 end
        f.auraY = -u(y)
    end
    local function putAuras(objs, list, fromRight)
        local step = u(TB.ICON + TB.IGAP)
        local y = TB.f.auraY or -u(TB.HP + 5)
        for i, o in ipairs(objs) do
            local a = list[i]
            if not a then o.f:Hide()
            else
                o.f:ClearAllPoints()
                if fromRight then o.f:SetPoint("TOPRIGHT", TB.f.hp, "TOPRIGHT", -(i - 1) * step, y)
                else o.f:SetPoint("TOPLEFT", TB.f.hp, "TOPLEFT", (i - 1) * step, y) end
                pcall(function() o.tx:SetTexture(a.icon) end)
                if not pcall(function() o.cd:SetCooldownFromExpirationTime(a.expirationTime, a.duration) end) then
                    if not pcall(function() o.cd:SetCooldown(a.expirationTime - a.duration, a.duration) end) then o.cd:Clear() end
                end
                local n = a.applications
                if plainV(n) and type(n) == "number" and n > 1 then o.n:SetText(n) else o.n:SetText("") end
                if objs == TB.f.deb then
                    local kind = (plainV(a.dispelName) and type(a.dispelName) == "string" and a.dispelName ~= "") and a.dispelName or "none"
                    local atl = "UI-Debuff-Border-" .. ((type(PRD.DEBUFF_ATLAS) == "table" and PRD.DEBUFF_ATLAS[kind]) or "default") .. "-NoIcon"
                    if o.bdAtlas ~= atl then
                        o.bdAtlas = atl
                        if not pcall(function() o.bd:SetAtlas(atl) end) then
                            local c = (type(PRD.DEBUFF) == "table" and PRD.DEBUFF[kind]) or { 0.8, 0, 0 }
                            o.bd:SetColorTexture(c[1], c[2], c[3], 0.9)
                        end
                    end
                    o.bd:Show()
                else o.bd:Hide() end
                o.f:Show()
            end
        end
    end
    local function aurasStep(f)
        local moved = f.lastAuraY ~= f.auraY
        if not moved and TB.auraAt and GetTime() - TB.auraAt < TB.AURA_S and not TB.auraDirty then return end
        f.lastAuraY = f.auraY
        TB.auraAt, TB.auraDirty = GetTime(), false
        -- debuffs: yours first (the "PLAYER" filter), then the others (not repeated, by the game's own aura id where it is plain)
        local mine = auraList("HARMFUL|PLAYER", TB.DEBUFF_MAX)
        local seen = {}
        for _, a in ipairs(mine) do if plainV(a.auraInstanceID) then seen[a.auraInstanceID] = true end end
        local others = auraList("HARMFUL", TB.DEBUFF_MAX, seen)
        local deb = {}
        for _, a in ipairs(mine) do deb[#deb + 1] = a end
        for _, a in ipairs(others) do if #deb < TB.DEBUFF_MAX then deb[#deb + 1] = a end end
        local buf = auraList("HELPFUL", TB.BUFF_MAX)
        putAuras(f.deb, deb, false)
        putAuras(f.buf, buf, true)
    end
    -- uf14 THE FLY (the user, 2026-11-09: "When targetting an enemy I want their nameplate to animate up into the target space centered at
    -- the top of the screen and vise versa"): a new ENEMY target's nameplate bar flies up into the target bar (our own copy: the plate is
    -- only read for where it is on screen, never moved), the bar shows when it lands; losing the target, the bar flies back down onto the
    -- plate it came from while that plate still shows. Our own frame, alpha / place only; read-only geometry of the plate.
    local FLY_S = 0.24
    local FLY = { host = nil, bar = nil, t0 = 0, from = nil, to = nil, dir = nil }
    local function rectOf(r)
        if type(r) ~= "table" then return nil end
        local ok, l, b, w, h, k = pcall(function()
            return r:GetLeft(), r:GetBottom(), r:GetWidth(), r:GetHeight(), r:GetEffectiveScale() / UIParent:GetEffectiveScale()
        end)
        if not ok then return nil end
        for _, v in ipairs({ l, b, w, h, k }) do if type(v) ~= "number" or not plainV(v) then return nil end end
        if w <= 0 or h <= 0 then return nil end
        return { l * k, b * k, w * k, h * k }
    end
    local function plateBarOf(plate)
        local hb = plate and call(function() return plate.UnitFrame and (plate.UnitFrame.HealthBarsContainer or plate.UnitFrame.healthBar) end)
        return type(hb) == "table" and hb or nil
    end
    local function flyMake()
        if FLY.host then return end
        local host = CreateFrame("Frame", nil, UIParent)
        host:SetFrameStrata("LOW"); host:EnableMouse(false); host:Hide()
        FLY.bar = bar(host, 10, 4)
        FLY.bar:ClearAllPoints(); FLY.bar:SetAllPoints(host)
        host:SetScript("OnUpdate", function()
            local p = (GetTime() - FLY.t0) / FLY_S
            if p >= 1 or not FLY.from or not FLY.to then
                host:Hide()
                if FLY.dir == "up" and TB.f then TB.f:SetAlpha(1) end
                FLY.dir = nil
                return
            end
            local e = 1 - (1 - p) ^ 3
            local a, z = FLY.from, FLY.to
            host:ClearAllPoints()
            host:SetPoint("BOTTOMLEFT", UIParent, "BOTTOMLEFT", a[1] + (z[1] - a[1]) * e, a[2] + (z[2] - a[2]) * e)
            host:SetSize(a[3] + (z[3] - a[3]) * e, a[4] + (z[4] - a[4]) * e)
        end)
        FLY.host = host
    end
    local function flyGo(dir, from, to)
        if not from or not to then return false end
        flyMake()
        FLY.dir, FLY.from, FLY.to, FLY.t0 = dir, from, to, GetTime()
        FLY.host:ClearAllPoints()
        FLY.host:SetPoint("BOTTOMLEFT", UIParent, "BOTTOMLEFT", from[1], from[2]); FLY.host:SetSize(from[3], from[4])
        FLY.host:Show()
        return true
    end

    function UF.targetStep()
        local has = UF.target and bool(UnitExists, "target")
        if has == nil and UF.target then has = call(UnitName, "target") ~= nil end
        if not has then
            -- uf14: the bar flies back down onto the plate it came from (while that plate still shows)
            if TB.f and TB.f:IsShown() and TB.enemy and TB.lastPlate and TB.lastRect then
                local okS, shown = pcall(function() return TB.lastPlate:IsVisible() end)
                if okS and shown == true then
                    flyMake()
                    if TB.lastRGB then paint(FLY.bar, TB.lastRGB[1], TB.lastRGB[2], TB.lastRGB[3]) end
                    flyGo("down", TB.lastRect, rectOf(plateBarOf(TB.lastPlate)))
                end
            end
            TB.enemy, TB.lastPlate, TB.lastRect, TB.changed = nil, nil, nil, nil
            if TB.f then TB.f:Hide(); TB.f:SetAlpha(1) end
            plateHide(false)
            return
        end
        local f = buildTarget()
        setText(f.name, call(UnitName, "target") or "")
        paint(f.hp, plateRGB("target"))
        f.name:SetTextColor(plateNameRGB("target"))
        -- the level in the shield (the skull for ??), the elite / rare dragon behind it
        local lv = call(UnitLevel, "target")
        if plainV(lv) and type(lv) == "number" and lv < 0 then f.lvl:SetText(""); f.skull:Show()
        else f.skull:Hide(); setText(f.lvl, lv) end
        local cl = call(UnitClassification, "target")
        local ca = plainV(cl) and CLASS_ATLAS[cl] or nil
        if ca then
            if f.clsA ~= ca then f.clsA = ca; if not atlas(f.cls, ca) then f.clsA = nil end end
            if f.clsA then f.cls:Show() else f.cls:Hide() end
        else f.cls:Hide() end
        feed(f.hp, nil, "target")
        -- uf11: blank by default; Total = health / max (the game's short form, else whole numbers), Percent = the percent. Secret values
        -- only go straight into the setter
        if UF.hpt then
            if not pcall(function()
                f.pct:SetFormattedText("%s / %s", AbbreviateLargeNumbers(UnitHealth("target")), AbbreviateLargeNumbers(UnitHealthMax("target")))
            end) and not pcall(function()
                f.pct:SetFormattedText("%d / %d", UnitHealth("target"), UnitHealthMax("target"))
            end) then f.pct:SetText("") end
        elseif UF.hpp then
            if not pcall(function()
                f.pct:SetFormattedText("%d%%", UnitHealthPercent("target", true, CurveConstants and CurveConstants.ScaleTo100))
            end) then f.pct:SetText("") end
        else f.pct:SetText("") end
        local mark = call(GetRaidTargetIndex, "target")
        local tc = plainV(mark) and RAID_ICON[mark] or nil
        if tc then f.mark:SetTexCoord(tc[1], tc[2], tc[3], tc[4]); f.mark:Show() else f.mark:Hide() end
        -- uf9: the prompt while the button would open the target's wheel (Spells says the letter: WC.tgPrompt), then the marker and the
        -- target's target after it
        local after = f.hp
        local pa = WC.tgPrompt
        if type(pa) == "string" and pa ~= "" then
            if f.prompt.lt ~= pa then
                f.prompt.lt = pa
                -- uf10 / uf12: the same glyph as over the nameplate (Spells' atlas for the layout), the letter only without the atlas
                if atlas(f.prompt.btn, pa) then f.prompt.btn:Show(); f.prompt.letter:Hide()
                else f.prompt.btn:Hide(); f.prompt.letter:SetText("Y"); f.prompt.letter:Show() end
            end
            f.prompt:Show(); after = f.prompt
        else f.prompt:Hide() end
        f.mark:ClearAllPoints(); f.mark:SetPoint("LEFT", after, "RIGHT", u(after == f.hp and 14 or 10), 0)
        if f.mark:IsShown() then after = f.mark end
        f.tot:ClearAllPoints(); f.tot:SetPoint("LEFT", after, "RIGHT", u(after == f.hp and 36 or 14), u(4))
        comboStep(f)
        castStep(f)
        underLayout(f)
        aurasStep(f)
        -- the target of target, in its nameplate colour too
        local tt = bool(UnitExists, "targettarget")
        if tt == nil then tt = call(UnitName, "targettarget") ~= nil end
        if tt and UF.tot then                                                -- uf8: only while its switch is on
            setText(f.totName, call(UnitName, "targettarget") or "")
            paint(f.totHp, plateRGB("targettarget"))
            feed(f.totHp, nil, "targettarget")
            f.tot:Show()
        else f.tot:Hide() end
        plateHide(plateRule())
        f:Show()
        -- uf14: a NEW enemy target: its plate's bar flies up into the bar (the bar shows when it lands); the fly copy follows the health
        local rgb = { plateRGB("target") }
        if TB.changed then
            TB.changed = nil
            TB.enemy = bool(UnitCanAttack, "player", "target") == true
            local plate = TB.enemy and plateOf("target") or nil
            TB.lastPlate = plate
            if plate then
                flyMake()
                paint(FLY.bar, rgb[1], rgb[2], rgb[3])
                feed(FLY.bar, nil, "target")
                if flyGo("up", rectOf(plateBarOf(plate)), rectOf(f.hp)) then f:SetAlpha(0) end
            elseif FLY.dir == "up" then FLY.host:Hide(); FLY.dir = nil; f:SetAlpha(1) end
        end
        if TB.enemy then
            TB.lastRect = rectOf(f.hp) or TB.lastRect
            TB.lastRGB = rgb
            if FLY.bar then feed(FLY.bar, nil, "target"); paint(FLY.bar, rgb[1], rgb[2], rgb[3]) end
        end
        if FLY.dir ~= "up" then f:SetAlpha(1) end
    end

    -- ---------------------------------------------------------------- the driver: events mark dirty, a 0.1 s step draws
    local drv = CreateFrame("Frame")
    local acc = 0
    local function stepAll()
        pcall(UF.partyStep)
        pcall(UF.targetStep)
    end
    for _, e in ipairs({ "PLAYER_ENTERING_WORLD", "GROUP_ROSTER_UPDATE", "PLAYER_TARGET_CHANGED", "UNIT_AURA", "UNIT_SPELLCAST_START",
                         "UNIT_SPELLCAST_STOP", "UNIT_SPELLCAST_FAILED", "UNIT_SPELLCAST_INTERRUPTED", "UNIT_SPELLCAST_CHANNEL_START",
                         "UNIT_SPELLCAST_CHANNEL_STOP", "UNIT_SPELLCAST_DELAYED", "UNIT_SPELLCAST_CHANNEL_UPDATE", "RAID_TARGET_UPDATE" }) do
        pcall(drv.RegisterEvent, drv, e)
    end
    drv:SetScript("OnEvent", function(_, e, unit)
        if e == "UNIT_AURA" then if unit == "target" then TB.auraDirty = true end return end
        if e == "PLAYER_ENTERING_WORLD" then K = scaleK() end
        if e == "PLAYER_TARGET_CHANGED" then TB.auraDirty = true; TB.changed = true end      -- uf14: the fly
        stepAll()
    end)
    drv:SetScript("OnUpdate", function(_, el)
        -- the cast moves every frame; the rest every 0.1 s
        if TB.f and TB.f.casting then
            local now, c = GetTime() * 1000, TB.f.chan
            pcall(function() TB.f.cast:SetValue(c and (c[1] + c[2] - now) or now) end)
        end
        if UF.pf and UF.pf.fdir then pcall(UF.pf.fadeTick, el) end     -- hub42: the party block's fade, every frame
        acc = acc + (el or 0)
        if acc < 0.1 then return end
        acc = 0
        stepAll()
    end)
    function UF.set(part, on)
        UF[part] = on
        stepAll()
    end
end
