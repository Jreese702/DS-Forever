-- WowCompanionInteract / Dialogs.lua
-- The game's DIALOGS and player requests (release spirit, resurrect, party invite, duel, summon,
-- the yes / no confirmations) for the Thor's bottom screen. First slice of the user's end goal,
-- 2026-09-28: every window the game opens appears on the bottom screen.
--
-- A MIRROR, NOT A CAPTURE. The game drives these dialogs with the controller itself
-- (Blizzard_StaticPopup, GamepadPopupHandler): A = button 1, B = button 2, X = button 3,
-- Y = button 4, right stick click = the extra button. This file leaves all of that alone. It
--   1. READS the dialogs that are shown and sends them as one "dialogs" document,
--   2. puts the top-screen frame to ALPHA 0 while the companion is in charge (never Hide(), never
--      SetParent), and again whenever the game sets that alpha itself,
--   3. offers one SECURE click-through cell per button on the shared tap pad, so that a touch on
--      the bottom screen is one click on the game's real button, run by the game's own code.
-- No function of this file runs any of the dialogs' actions, so nothing is tainted, the
-- protected dialogs work like the others, and it works in combat.
--
-- HIDDEN ONLY WHILE THE APP SHOWS IT (user, 2026-09-28: "Mirror, bottom screen only", with the
-- risk that A answers a dialog nobody can see if the app is gone). The app's SIGN OF LIFE is a
-- tap on the `alive` cell: once when it has drawn a dialog, then every LIFE_EVERY seconds while
-- it shows one. A dialog is invisible on the top screen for its first GRACE seconds (so that it
-- does not flash), and after that only while the last sign is younger than LIFE seconds. When
-- the signs stop, it is back on the top screen within LIFE + 0.25 s, by itself.
--
-- THE SHIELD (user: "Nothing, shield over that window"): while a dialog is invisible, a frame of
-- ours lies over it and swallows finger taps on the top screen (WC.shield, Interact.lua). It goes
-- with the hiding, on every path.
--
-- WHAT STAYS ON THE TOP SCREEN, VISIBLE: a dialog with a text box, a money input, a drop-down or
-- an inserted frame. The bottom screen cannot fill those in yet. The document says so (`top`).
--
-- WHAT IT COSTS: a document when the set of dialogs or their content changes, none per second (a
-- countdown is sent as seconds left at that moment; the app counts down). While a dialog is shown
-- the frames are looked at four times a second; with none shown nothing runs.

local WC = WowCompanion
if type(WC) ~= "table" or type(WC.S) ~= "function" then return end
local S = WC.S

-- d30 (int/1429): combat-nav d27-d28 (the idlg leave cells 33-39, WC.dialogs.mirrored) + starter-prompt d27-d29 (the quest starter
-- prompt, its Start / Not now now on the charter's Sign / Offer cells 28 / 29: the row stays 40 columns)
local DV = "d32"         -- bump on every change; d32 (issue camp-exit, build 1462, the user: "when exiting the game and the timer pops up it doesnt have the 'exit now' option that it normally should"): the QUIT countdown's "Exit Now" (the game's button 1, its OnAccept = ForceQuit in the game's own code) is mirrored again with its touch cell, a secure click on the game's own button; A stays bound to nothing on it (d22's guard: never pressed by the controller, the app's fallback or the safe close); CAMP has no button 1, so it still shows Cancel alone; d31 (issue #69, 1.0.18: "tried to sign a guild chart didnt work"; the offline harness signs by touch and by A, the report's log has no trace of the charter): DIAGNOSTICS ONLY, nothing pressed or written - the charter's Sign is followed for 4 s and one "cap" document says what came of it (petSign: by touch / A, signatures before / after, the window closed, the game's UI error id, which petition system message by its place in D.PETMSG, a pop-up shown), numbers only; d30 int/1429: the quest starter's Start / Not now SHARE the charter's Sign / Offer cells (idlg 28 / 29: the prompt never shows with the charter open, PETITION_SHOW disarms it and gives the cells back), so the row keeps combat-nav's seven leave cells (33-39) at 40 columns; d29 (starter-prompt): the quest starter prompt ONLY when a starter is NEWLY LOOTED this session (the user, 2026-11-11, asked how long "Not now" lasts: "Only when newly looted"): a baseline of the bags' item counts at login / reload, a prompt only for a count rising above it (never for what was already there, a /reload or a move between bags), Not now = that one prompt; d28 (starter-prompt): the quest starter's Start is DISARMED on the very event a vendor / bank / mail / trade / NPC window opens (a bag item used at a vendor is sold) and aimed by item id, not bag and slot; a Start on a disarmed button is no answer; d27 (starter-prompt): A QUEST STARTER IN THE BAGS ASKS "Start quest?" (the user, 2026-11-11: "If I pick up a quest item like that we should make a prompt asking if we want to start that quest"; [Ripped Missive]): a dialog of ours, QUEST_START, A = a secure use of the item, B = Not now (see D.QS below); d28 (combat-nav): WC.dialogs.mirrored() - the pop-ups the app shows as its box (Interact's fight sink leaves A / B to the app while one shows: the review of combat_nav, the user's decision 1, 2026-11-11); d27 (combat-nav): the idlg row's last seven columns (33-39, the row grows to 40: no new row) are the capture's per-window leave cells (combat_nav_design Step 5c, the user 2026-11-11), aimed by Interact (WC.dialogs.leaveCell); d26: while the trade window is open EVERY dialog with an inserted frame is mirrored (the user: trading a green gave an "are you sure" on the top screen)
local POOL = 4          -- StaticPopup1 .. StaticPopup4
local CELLS = 6         -- per dialog: buttons 1-4, the extra button, the close X
local ROW = "idlg"
-- d14: one column after the dialogs' 24 = the capture's FIGHT-CLOSE cell (Interact.lua, capture v8): a secure "click" on the
-- close button of the game window under a room, aimed by Interact out of combat. The app taps it once when a room is left
-- mid-fight, so the game closes its own window in its own code (its controller catcher owned the pad otherwise, 10:47).
local CLOSE_COL = POOL * CELLS
local ENTER_COL = POOL * CELLS + 1      -- d15: the capture's ENTER cell (a room or window taking the controller in a fight)
-- d27 (combat_nav_design Step 5c, the user 2026-11-11: "If I am looting ... my dpad should not stop working"): the capture's PER-WINDOW
-- LEAVE cells after the trade's two (LEAVE_COL .. LEAVE_COL + LEAVE_N - 1, the row's last columns: no new row, TapPad's 40 columns):
-- each a secure "click" on one of Interact's leave buttons, aimed by Interact out of combat at one game window's own close
local LEAVE_COL, LEAVE_N = POOL * CELLS + 9, 7
-- d17: THE GUILD INVITE (the user, 2026-10-09: "the pop up to join a guild is on the top screen instead of the bottom"). Not a
-- StaticPopup: GuildInviteFrame (StaticPopupSpecial_Show), on GUILD_INVITE_REQUEST; Join = GuildInviteFrameJoinButton (AcceptGuild),
-- Decline = GuildInviteFrameDeclineButton (its OnHide declines); it declines by itself after 60 s (read by Social from the
-- client's guildinviteframe.lua). Mirrored as dialog GUILD_I with two cells of its own after the close / enter cells.
local GUILD_I = POOL + 1
local GUILD_COL = POOL * CELLS + 2      -- Join; GUILD_COL + 1 = Decline
local GUILD_SECS = 60
-- d18: THE GUILD CHARTER's buttons (Interact's petition window, i108): pressed by SECURE clicks, so the game's own confirm / rename
-- boxes come from its own code. Three named secure buttons (A's binding clicks, Interact's wantOf) and three touch cells.
local PET_COL = POOL * CELLS + 4        -- Sign, Request Signature, Rename: PET_COL .. PET_COL + 2
local PET = { { key = "sign", name = "WowCompanionPetSign", target = "PetitionFrameSignButton" },
              { key = "offer", name = "WowCompanionPetOffer", target = "PetitionFrameRequestButton" },
              { key = "rename", name = "WowCompanionPetRename", target = "PetitionFrameRenameButton" } }
-- d23: THE TRADE WINDOW's Trade and Cancel (Interact i137): SECURE clicks of the game's own two buttons, so the game accepts and closes
-- its trade in its own code. Two named secure buttons (A's and B's binding clicks, Interact's wantOf) and two touch cells
local TRADE_COL = POOL * CELLS + 7      -- Trade (the game's TradeFrameTradeButton); TRADE_COL + 1 = Cancel (TradeFrameCancelButton)
local TRADE = { { name = "WowCompanionTradeAccept", target = "TradeFrameTradeButton" },
                { name = "WowCompanionTradeCancel", target = "TradeFrameCancelButton" } }
local POLL = 0.25
local GRACE = 1.5       -- seconds a new dialog may be invisible before the app has said it shows it
local LIFE_EVERY = 5    -- the app's sign of life while it shows a dialog, in seconds
local LIFE = 8          -- no sign for this long: the dialog comes back on the top screen
local WORDS_EVERY = 2   -- a dialog whose WORDS change every second (a countdown inside its text or
                        -- on a button) is sent again at most this often; anything else at once

local function real(v) return v ~= nil and v ~= WC.ABSENT and v ~= WC.SECRET and v ~= WC.THREW end
local function TS(fn) local v = S(fn); if type(v) == "string" and real(v) then return v end end
local function TN(fn) local v = S(fn); if type(v) == "number" then return v end end
local function TB(fn) local v = S(fn); if type(v) == "boolean" then return v end end

local D = { mode = false, dimmed = {}, lastKey = nil, polling = false, hid = 0,
            lifeAt = nil,       -- GetTime() of the app's last sign of life
            since = {} }        -- dialog frame -> { which, at }: since when THIS dialog is shown

local function now() return TN(function() return GetTime() end) or 0 end

-- may this dialog be invisible right now?
local function shownByApp(f, which)
    local t = now()
    local e = D.since[f]
    if not e or e.which ~= which then
        e = { which = which, at = t }
        D.since[f] = e
    end
    if D.lifeAt and t - D.lifeAt <= LIFE then return true end
    return t - e.at <= GRACE
end

local function dialogFrame(i)
    local f = _G["StaticPopup" .. i]
    if type(f) == "table" then return f end
end

local function shown(obj)
    return type(obj) == "table" and TB(function() return obj:IsShown() end) == true
end

-- button k of a dialog: 1-4, 5 = the extra button, 6 = the close X
local function buttonOf(f, k)
    if k <= 4 then
        local b = S(function() return f:GetButton(k) end)
        if type(b) == "table" then return b end
    elseif k == 5 then
        if type(f.ExtraButton) == "table" then return f.ExtraButton end
    elseif type(f.CloseButton) == "table" then
        return f.CloseButton
    end
end

-- WHICH KEY the game gives a button: READ from the game, never assumed (user, 2026-09-28: the
-- answer is drawn on the right, so place and key no longer go together by habit). The game hangs
-- its own glyph on every dialog button and keeps them by key in dialog.gamepadIcons; the key of
-- the glyph whose parent is this button is the key that presses it. No glyph found: no key sent,
-- and the app draws none.
local function keyOf(f, button)
    local icons = f.gamepadIcons
    if type(icons) ~= "table" or type(button) ~= "table" then return nil end
    local found
    pcall(function()
        for key, icon in pairs(icons) do
            if type(key) == "string" and type(icon) == "table" and icon:GetParent() == button then
                found = key
            end
        end
    end)
    return TS(function() return found end)
end

local function readItem(f)
    if not shown(f.ItemFrame) then return nil end
    local data = f.data
    local it = {}
    if type(data) == "table" then
        it.name = TS(function() return data.name end)
        it.link = TS(function() return data.link end)
        it.icon = TN(function() return data.texture end)
        it.count = TN(function() return data.count end)
        local c = data.color
        if type(c) == "table" then
            it.rgb = { TN(function() return c[1] or c.r end), TN(function() return c[2] or c.g end),
                       TN(function() return c[3] or c.b end) }
        end
    end
    return it
end

local function read(i, f)
    local d = { i = i, which = TS(function() return f.which end) }
    d.text = TS(function() return f.Text:GetText() end)
    if shown(f.SubText) then d.sub = TS(function() return f.SubText:GetText() end) end
    d.buttons = {}
    for k = 1, 4 do
        local b = buttonOf(f, k)
        if shown(b) then
            d.buttons[#d.buttons + 1] = { k = k, label = TS(function() return b:GetText() end),
                                          on = TB(function() return b:IsEnabled() end) ~= false,
                                          key = keyOf(f, b) }
        end
    end
    -- d22 (the user's standing ruling: NEVER press "Exit Now" FOR the player; test50: A on the mirrored QUIT dialog during the safe close
    -- reached it): while QUIT shows, the dialogs take the controller (D.padSync) with A bound to nothing and B to Cancel, so neither the
    -- game's own A nor ours can press it. d32 (build 1462, the user: "when exiting the game and the timer pops up it doesnt have the 'exit
    -- now' option that it normally should"): the game's "Exit Now" IS mirrored again, exactly as the game shows it (QUIT has it, CAMP has
    -- none), with its touch cell: a finger on it is one secure click on the game's own button (its OnAccept, ForceQuit, runs in the
    -- game's code). It carries no key (A is not its key), and the app never taps it by itself (DialogView.neverPress)
    if d.which == "QUIT" then d.guard = true end
    if shown(f.ExtraButton) then
        d.extra = TS(function() return f.ExtraButton:GetText() end) or ""
        d.extraKey = keyOf(f, f.ExtraButton)
    end
    if shown(f.CloseButton) then d.x = true end
    local left = TN(function() return f.timeleft end)
    if left and left > 0 then d.left = math.ceil(left) end
    -- button 1 is grey for this long (the game's start delay or accept delay)
    local delay = TN(function() return f.acceptDelay end) or TN(function() return f.startDelay end)
    if delay and delay > 0 then d.delay = math.ceil(delay) end
    local info = f.dialogInfo
    if shown(f.AlertIcon) then
        d.alert = (type(info) == "table" and TB(function() return info.showAlertGear and true or false end)) and "gear" or "alert"
    end
    -- the bar: `dur` seconds in all, `left` of them to go; the fraction is left / dur
    if shown(f.ProgressBarBorder) or shown(f.ProgressBarFill) then
        local dur = TN(function() return f.progressBarDuration end)
        if dur and dur > 0 then d.dur = dur end
        d.bar = true
    end
    d.item = readItem(f)
    if shown(f.MoneyFrame) then d.money = TN(function() return f.MoneyFrame.staticMoney end) end
    if TB(function() return f.gamepadFocused end) then
        d.focus = true
    else
        -- NOT FOCUSED: the game gives no controller focus to a dialog the player did not ask for
        -- (its own flag `skipGamepadAutoFocus`: party invite, trade, channel invite, area spirit
        -- healer). Its A / B do nothing until the player presses the game's "focus the UI" key
        -- (TOGGLEUIFOCUS, GAMEPAD_MENU_LEFT = the left menu button). Measured on the Thor 2026-09-29
        -- 10:14: a party invite, B did not decline it. The app shows that key, read here, never assumed.
        d.fkey = TS(function() return GetBindingKey("TOGGLEUIFOCUS") end)
              or TS(function() return GAMEPAD_MENU_LEFT end)
    end
    -- what the bottom screen cannot fill in: such a dialog stays on the top screen, visible
    local why
    if shown(f.EditBox) then why = "text"
    elseif shown(f.MoneyInputFrame) then why = "money"
    elseif shown(f.Dropdown) then why = "list"
    elseif type(f.insertedFrame) == "table" then why = "frame" end
    -- d24 (the user on test15: the trade's "are you sure you want to accept" showed only on the top screen): a TRADE dialog is
    -- mirrored even with a frame inserted (its buttons are what matters); a text box or money input still stays on top
    if why == "frame" and type(d.which) == "string" and d.which:find("TRADE") then d.topWas = why; why = nil end
    -- d26 (the user, 2026-11-08: "There was also a trade where I traded a green and it gave an are you sure prompt on the top screen. We
    -- need to make that for the bottom"): while the game's trade window is open, ANY dialog whose extra is a shown frame (the item it asks
    -- about) is mirrored too, whatever its name; a text box, a money input or a list still stays on top
    if why == "frame" and TB(function() return _G.TradeFrame and _G.TradeFrame:IsShown() end) == true then d.topWas = why; why = nil end
    d.top = why
    return d
end

-- ---------------------------------------------------------------- hiding, by alpha only

local function dim(f)
    if not pcall(function() f:SetAlpha(0) end) then return false end
    if not D.dimmed[f] then D.dimmed[f] = true; D.hid = D.hid + 1 end
    if type(WC.shield) == "table" then pcall(WC.shield.cover, f, "dialogs") end
    return true
end

local function undim(f)
    if type(WC.shield) == "table" then pcall(WC.shield.clear, f) end
    if not D.dimmed[f] then return end
    -- 1, not what it had: the game sets this alpha itself (1 with the focus, 0.5 without) at its
    -- next update, and a dialog must never stay invisible with nobody showing it
    pcall(function() f:SetAlpha(1) end)
    D.dimmed[f] = nil
    D.hid = D.hid - 1
end

-- ---------------------------------------------------------------- LOOT ROLLS (d12)
-- The user, 2026-09-30: "We are missing the loot roles pop up ... if its a weapon or armor it should probably show it
-- and my currently equipped compared automatically". A MIRROR like the dialogs: in controller mode the game shows
-- its rolls in GamepadGroupLootRollFrame and drives them itself (A Need, B Pass, X Greed / Transmog, the D-pad between
-- rolls; read from the client's grouplootframe.lua), in combat too. This file reads the rolls (the game's own
-- GetActiveLootRollIDs / GetLootRollItemInfo), says which one the game has selected, sends the selected one's tooltip
-- with the compare (Interact.lua: WC.rollTip), and hides the game's roll frame by the dialogs' own rule (alive /
-- GRACE / LIFE). A tap is one cell of Interact's (WC.rollCells): its ACT.roll calls the game's RollOnLoot.
local ROLL_FRAMES = { "GamepadGroupLootRollFrame", "GroupLootFrame1", "GroupLootFrame2", "GroupLootFrame3", "GroupLootFrame4" }
local ROLL_SHOW = 4      -- the game's MAX_NUM_GAMEPAD_LOOT_ITEMS; the rest is counted ("+N more")
-- the app half draws rolls only from its build that carries them: until then the game's roll frame stays visible on
-- top (an app that cannot draw a roll never sends its sign of life for it). Switch on in the build with the app half.
local ROLLS_HIDE = true     -- d13: the app half draws rolls (app interact, the user's "Yes, build it")
local function rollText(n)
    if type(n) ~= "number" or n <= 0 then return nil end
    return TS(function() return _G["LOOT_ROLL_INELIGIBLE_REASON" .. n] end)
end
local function readRolls()
    D.rollsMore = nil
    local ok, ids = pcall(function() return GetActiveLootRollIDs() end)
    if not ok or type(ids) ~= "table" or #ids == 0 then return nil end
    -- the roll the game's A / B / X act on: its own frame's selection (read only)
    local sel
    local gf = _G.GamepadGroupLootRollFrame
    if type(gf) == "table" then
        pcall(function()
            local i, w = gf.selectedRollIndex, gf.waitingRolls
            if type(i) == "number" and type(w) == "table" and type(w[i]) == "table" then sel = w[i].rollID end
        end)
    end
    local out = {}
    for k, raw in ipairs(ids) do
        if k > ROLL_SHOW then
            D.rollsMore = #ids - ROLL_SHOW
            break
        end
        local id = TN(function() return raw end)
        if id then
            local okI, tex, name, count, q, bop, need, greed, de, rn, rg, rd, skill, tm = pcall(GetLootRollItemInfo, id)
            name = okI and real(name) and type(name) == "string" and name or nil
            if name then
                local r = { id = id, name = name, icon = type(tex) == "number" and tex or nil,
                            count = type(count) == "number" and count > 1 and count or nil, q = type(q) == "number" and q or nil,
                            bop = bop == true or nil, need = need == true or nil, greed = greed == true or nil,
                            de = de == true or nil, transmog = tm == true or nil }
                local why = { need = (not r.need) and rollText(rn) or nil, greed = (not r.greed) and rollText(rg) or nil,
                              de = (not r.de) and rollText(rd) or nil }
                if next(why) then r.why = why end
                local left = TN(function() return GetLootRollTimeLeft(id) end)
                if left then r.left = math.floor(left / 1000 + 0.5) end
                local dur = TN(function() return C_Loot.GetLootRollDuration(id) end)
                if dur then r.dur = math.floor(dur / 1000 + 0.5) end
                if type(WC.rollCells) == "function" then
                    local okC, c = pcall(WC.rollCells, id)
                    if okC and type(c) == "table" then r.cells = c end
                end
                out[#out + 1] = r
            end
        end
    end
    if #out == 0 then return nil end
    if not sel then sel = out[1].id end
    for _, r in ipairs(out) do
        if r.id == sel then r.sel = true end
        -- EVERY shown roll's tooltip (a finger may choose another card than the game's; the game's selection is
        -- never written): for a weapon or armour, Currently Equipped and the stat changes with it
        if type(WC.rollTip) == "function" then
            local okT, t = pcall(WC.rollTip, r.id)
            if okT and type(t) == "table" and not t.none then r.tip = { lines = t.lines, cmp = t.cmp } end
        end
    end
    return out
end
-- the game's roll frames: invisible while the app shows the rolls (the dialogs' own rule), back otherwise
local function hideRolls(any)
    for _, n in ipairs(ROLL_FRAMES) do
        local f = _G[n]
        if type(f) == "table" then
            if any and ROLLS_HIDE and D.mode and shown(f) and shownByApp(f, "roll") then dim(f)
            else
                if not shown(f) then D.since[f] = nil end
                undim(f)
            end
        end
    end
end

local function guildFrame() local f = _G.GuildInviteFrame; if type(f) == "table" then return f end end
local function guildButton(k)
    local b = (k == 1) and _G.GuildInviteFrameJoinButton or (k == 2) and _G.GuildInviteFrameDeclineButton or nil
    if type(b) == "table" then return b end
end
D.guild = nil           -- { inviter, name, at } from GUILD_INVITE_REQUEST
local function readGuild(f)
    local g = D.guild or {}
    local inviter = g.inviter or TS(function() return GuildInviteFrameInviterName:GetText() end) or "Someone"
    local name = g.name or TS(function() return GuildInviteFrameGuildName:GetText() end) or "a guild"
    local d = { i = GUILD_I, which = "GUILD_INVITE", text = inviter .. " invites you to join " .. name .. "." }
    if shown(_G.GuildInviteFrameWarningText) then d.sub = TS(function() return GuildInviteFrameWarningText:GetText() end) end
    d.buttons = {}
    for k = 1, 2 do
        local b = guildButton(k)
        if shown(b) then
            d.buttons[#d.buttons + 1] = { k = k, label = TS(function() return b:GetText() end) or (k == 1 and "Join" or "Decline"),
                                          on = TB(function() return b:IsEnabled() end) ~= false }
        end
    end
    if g.at then
        local left = GUILD_SECS - ((TN(function() return GetTime() end) or 0) - g.at)
        if left > 0 then d.left = math.ceil(left) end
    end
    if TB(function() return f.gamepadFocused end) then d.focus = true
    else d.fkey = TS(function() return GetBindingKey("TOGGLEUIFOCUS") end) or TS(function() return GAMEPAD_MENU_LEFT end) end
    return d
end
-- ---------------------------------------------------------------- A QUEST STARTER IN THE BAGS (d27)
-- The user, 2026-11-11: "I dont have a polestar objective because its a quest item in my bag I realized. If I pick up a quest item like
-- that we should make a prompt asking if we want to start that quest" (the item was [Ripped Missive]). The game marks such an item in
-- its slot (C_Container.GetContainerItemQuestInfo: a quest id, not active); the bags are looked at when they change (BAG_UPDATE_DELAYED,
-- a loot line) and the first starter whose quest is neither taken nor done is offered as a dialog OF OURS, which = QUEST_START:
--   * A = Start: a binding click (the dialogs' own capture, D.padSync) of a SECURE item button of ours, type "item" on that bag and slot,
--     so the game uses the item in its own code and opens its own quest page (which Interact mirrors as its quest pop-up). Its attributes
--     are set OUT OF COMBAT only, on our own button and our own touch cell (the idlg row, D.QS.col): no macro, no binding saved, nothing
--     of the player's touched (the no-trace ruling).
--   * B = Not now: that one prompt dismissed; the item is still in the bags (the Bags room, Polestar's row) to use by hand.
--   * d29, ONLY WHEN NEWLY LOOTED (the user, 2026-11-11, asked how long "Not now" lasts: "Only when newly looted"): the bags' items
--     are counted by item id (stacks summed, so a move between bags changes nothing); the counts at login / a /reload
--     (PLAYER_ENTERING_WORLD, settled D.QS.settle s and until the bags are seen holding anything) are the BASELINE and never offered.
--     After that a count rising above the last tally (a loot, a quest reward, mail, a trade) marks that item a GAIN, the only thing
--     ever offered; Start or Not now spends the gain, a count falling (used, sold, deleted, banked) lowers the tally, and what comes
--     back while the bank is open is absorbed, not a gain. So an item is offered again only if a NEW copy is looted later.
--   * ONE AT A TIME, NEVER OVER A GAME WINDOW: shown only with no game dialog, no loot roll and no NPC / game window open and no room
--     holding the controller; a starter looted in a fight waits for PLAYER_REGEN_ENABLED. The rest wait their turn in the bags.
-- Everything read is a plain value (WC.S / TN / TS: a secret value is never used), and every field lives in D (no new chunk locals).
-- int/1429: col = the charter's Sign (PET_COL; Not now = PET_COL + 1, Offer): the idlg row is full at 40 with combat-nav's leave cells
D.QS = { i = POOL + 2, col = PET_COL, which = "QUEST_START", use = "WowCompanionQStartUse", no = "WowCompanionQStartNo",
         bags = 5,          -- the backpack, the four bags and the reagent bag (0 .. 5)
         -- the game windows a prompt never covers (Interact's NPC windows, the trade, the readable page)
         busy = { "QuestFrame", "GossipFrame", "MerchantFrame", "ClassTrainerFrame", "PetStableFrame", "MailFrame", "AuctionHouseFrame",
                  "FlightMapFrame", "TaxiFrame", "BankFrame", "LootFrame", "PetitionFrame", "ItemTextFrame", "TradeFrame" },
         -- d28: the opening events of those windows. A bag item used at a vendor is SOLD (at a bank, mail or trade it is moved), so on
         -- the event itself, before the window's frame is even shown, the prompt drops and both buttons are disarmed in the same frame
         opens = { "MERCHANT_SHOW", "BANKFRAME_OPENED", "MAIL_SHOW", "TRADE_SHOW", "AUCTION_HOUSE_SHOW", "GOSSIP_SHOW", "QUEST_DETAIL",
                   "QUEST_GREETING", "QUEST_PROGRESS", "QUEST_COMPLETE", "LOOT_OPENED", "TRAINER_SHOW", "TAXIMAP_OPENED",
                   "PETITION_SHOW", "ITEM_TEXT_BEGIN", "PET_STABLE_SHOW", "GUILDBANKFRAME_OPENED", "PLAYER_INTERACTION_MANAGER_FRAME_SHOW" },
         hold = 1,          -- d28: seconds a window's opening event holds the prompt off (then its shown frame does)
         settle = 5 }       -- d29: seconds after a login / reload the bags still load: every count then is baseline, never a gain
-- d29: base = the last tally (item id -> count; nil = no baseline yet), gain = item ids newly looted and not yet answered, settleAt =
-- the login / reload's PLAYER_ENTERING_WORLD, slots = each bag's slot count at the last tally (bag -> n; a bag never read = 0),
-- bank = the bank (or guild bank) open
D.qs = { base = nil, gain = {}, settleAt = nil, slots = {}, bank = false, cur = nil, dirty = true, clickAt = nil, openAt = nil }

-- the quest this slot's item starts and whether it is already in the log (the C_Container table, or the older numbered returns)
function D.qsInfo(bag, slot)
    local qid, active
    local qi = S(function() return C_Container.GetContainerItemQuestInfo(bag, slot) end)
    if type(qi) == "table" and qi ~= WC.ABSENT and qi ~= WC.SECRET and qi ~= WC.THREW then
        qid = TN(function() return qi.questID end)
        active = TB(function() return qi.isActive end)
    elseif type(GetContainerItemQuestInfo) == "function" and type(WC.CALL) == "function" then
        local ok, _, q, a = WC.CALL(function() return GetContainerItemQuestInfo(bag, slot) end)
        if ok then qid, active = TN(function() return q end), TB(function() return a end) end
    end
    return qid, active
end

-- the starter in this slot, newly looted (d29: a gain) and its quest neither taken nor done; nil otherwise (and, as a second value,
-- the item's id when its quest is taken or done: that gain is spent)
function D.qsAt(bag, slot)
    local qid, active = D.qsInfo(bag, slot)
    if not qid or qid <= 0 then return nil end
    local info = S(function() return C_Container.GetContainerItemInfo(bag, slot) end)
    if type(info) ~= "table" or info == WC.ABSENT or info == WC.SECRET or info == WC.THREW then info = nil end
    local id = (info and TN(function() return info.itemID end)) or TN(function() return C_Container.GetContainerItemID(bag, slot) end)
    if not id then return nil end
    if active == true then return nil, id end
    if TB(function() return C_QuestLog.IsOnQuest(qid) end) == true then return nil, id end
    if TB(function() return C_QuestLog.IsQuestFlaggedCompleted(qid) end) == true then return nil, id end
    if not D.qs.gain[id] then return nil end         -- d29: in the bags before (the baseline) or already answered: never offered
    local it = { id = id, bag = bag, slot = slot, quest = qid }
    it.name = TS(function() return C_Item.GetItemNameByID(id) end)
    if not it.name and info then
        local link = TS(function() return info.hyperlink end)
        it.name = link and link:match("%[(.-)%]") or nil
    end
    if it.name == "" then it.name = nil end
    it.icon = (info and TN(function() return info.iconFileID end)) or TN(function() return C_Item.GetItemIconByID(id) end)
    local q = info and TN(function() return info.quality end)
    if q then
        local c = S(function() return ITEM_QUALITY_COLORS[q] end)
        if type(c) == "table" and c ~= WC.ABSENT and c ~= WC.SECRET and c ~= WC.THREW then
            local r, g, b = TN(function() return c.r end), TN(function() return c.g end), TN(function() return c.b end)
            if r and g and b then it.rgb = { r, g, b } end
        end
    end
    local title = TS(function() return C_QuestLog.GetTitleForQuestID(qid) end)
    if title and title:find("%S") then it.title = title end
    return it
end

-- d29: every item in the bags counted by id (stacks summed: a move between bags or a split changes no count); also each bag's slot
-- count, and the counts of the bags whose slots ROSE since the last tally (late = a bag that loaded late, or a bag just equipped)
function D.qsCount()
    local n, late, sl = {}, {}, {}
    for bag = 0, D.QS.bags do
        local slots = TN(function() return C_Container.GetContainerNumSlots(bag) end) or 0
        sl[bag] = slots
        local rose = slots > (D.qs.slots[bag] or 0)
        for slot = 1, slots do
            local info = S(function() return C_Container.GetContainerItemInfo(bag, slot) end)
            local id, c
            if type(info) == "table" and info ~= WC.ABSENT and info ~= WC.SECRET and info ~= WC.THREW then
                id, c = TN(function() return info.itemID end), TN(function() return info.stackCount end)
            end
            id = id or TN(function() return C_Container.GetContainerItemID(bag, slot) end)
            if id then
                c = (c and c > 0) and c or 1
                n[id] = (n[id] or 0) + c
                if rose then late[id] = (late[id] or 0) + c end
            end
        end
    end
    return n, late, sl
end

-- d29 (the user, 2026-11-11: "Only when newly looted"): this tally against the last. Settling (no login / reload seen yet, its first
-- D.QS.settle s, or [absorb]) or the bank open: the counts ARE the baseline. Otherwise a count above the last is a gain (newly looted),
-- except what sits in a bag whose slots rose since the last tally: a bag that loaded late (backpack read, bags 1-5 still 0 slots on a
-- cold login) holds what was there at login, and a bag just equipped is empty (a bag must be empty to equip), so its contents are
-- baseline, per bag. A gain whose item left the bags is dropped; this tally becomes the new baseline.
function D.qsTally(absorb)
    local q = D.qs
    local n, late, sl = D.qsCount()
    q.slots = sl
    local settling = absorb or q.base == nil or not q.settleAt or (now() - q.settleAt) < D.QS.settle
    if not (settling or q.bank or shown(_G.BankFrame)) then
        for id, c in pairs(n) do
            if c > (q.base[id] or 0) + (late[id] or 0) then q.gain[id] = true end
        end
    end
    for id in pairs(q.gain) do if not n[id] then q.gain[id] = nil end end
    q.base = n
end

-- the bags, in the game's order: the first newly looted starter is the one offered (the others wait their turn)
function D.qsScan()
    D.qs.dirty = false
    D.qs.cur = nil
    D.qsTally()
    if next(D.qs.gain) == nil then return end
    for bag = 0, D.QS.bags do
        local n = TN(function() return C_Container.GetContainerNumSlots(bag) end) or 0
        for slot = 1, n do
            local it, spent = D.qsAt(bag, slot)
            if it then D.qs.cur = it; return end
            if spent then D.qs.gain[spent] = nil end  -- d29: its quest taken or done: that copy's gain is spent
        end
    end
end

-- our two secure buttons (made out of combat, off screen, never under a finger): Start uses the item, Not now presses nothing
function D.qsButtons()
    if _G[D.QS.use] and _G[D.QS.no] then return true end
    if TB(function() return InCombatLockdown() end) == true then return false end
    for _, n in ipairs({ D.QS.use, D.QS.no }) do
        if not _G[n] then
            pcall(function()
                local b = CreateFrame("Button", n, UIParent, "SecureActionButtonTemplate")
                pcall(function() b:SetSize(1, 1); b:SetPoint("TOPLEFT", UIParent, "TOPLEFT", -50, 50); b:EnableMouse(false) end)
                -- a binding click fires a secure button on the press or the release (ActionButtonUseKeyDown): both registered (d21)
                pcall(b.RegisterForClicks, b, "AnyDown", "AnyUp")
                if n == D.QS.use then b:SetAttribute("type", "item") end
                local what = (n == D.QS.use) and "start" or "later"
                b:HookScript("OnClick", function(self, _, down) D.qsClicked(what, down, self) end)
            end)
        end
    end
    return _G[D.QS.use] ~= nil and _G[D.QS.no] ~= nil
end

-- arm (or, with nil, disarm) the item on our button and our touch cell: OUT OF COMBAT ONLY (a secure attribute); false = not armed.
-- int/1429: the touch cells are the charter's Sign / Offer (PET_COL, PET_COL + 1): armed, Start = type "item" and Not now = no action;
-- disarmed, both are the charter's clicks again (type "click" on PetitionFrame's own buttons, as petAim aims them)
function D.qsArmed()
    local c = D.qsCell
    if not c then return false end
    local ok, cur = pcall(c.GetAttribute, c, "item")
    return ok and type(cur) == "string" and cur ~= ""
end
function D.qsAim(c)
    if TB(function() return InCombatLockdown() end) == true then return false end
    -- d28: aimed by the item's id ("item:<id>"), not its bag and slot, so an item moved before the next build is still the one used
    local want = c and ("item:" .. c.id) or nil
    local ok = true
    local use = _G[D.QS.use]
    if use then
        local okG, cur = pcall(use.GetAttribute, use, "item")
        if not okG or cur ~= want then
            if not pcall(function() use:SetAttribute("type", "item"); use:SetAttribute("item", want) end) then ok = false end
        end
    end
    local cell, no = D.qsCell, D.qsNoCell
    if cell then
        local okG, cur = pcall(cell.GetAttribute, cell, "item")
        if not okG or cur ~= want then
            if not pcall(function()
                if want then
                    cell:SetAttribute("type", "item"); cell:SetAttribute("item", want); cell:SetAttribute("clickbutton", nil)
                    if no then no:SetAttribute("type", nil); no:SetAttribute("clickbutton", nil) end
                else
                    cell:SetAttribute("item", nil); cell:SetAttribute("type", "click")
                    local t1 = _G[PET[1].target]
                    if type(t1) == "table" then cell:SetAttribute("clickbutton", t1) end
                    if no then
                        no:SetAttribute("type", "click")
                        local t2 = _G[PET[2].target]
                        if type(t2) == "table" then no:SetAttribute("clickbutton", t2) end
                    end
                end
            end) then ok = false end
        end
    end
    return ok
end

-- may the prompt show now? (one at a time, never over a game window, never in a fight)
function D.qsReady(list, rolls)
    if not (D.mode and D.qs.cur) then return false end
    if #list > 0 or rolls ~= nil then return false end
    if TB(function() return InCombatLockdown() end) == true then return false end
    if TB(function() return UnitIsDeadOrGhost("player") end) == true then return false end
    if now() - (D.qs.clickAt or -1e9) < 2 then return false end        -- the game's quest page is on its way: no next prompt under it
    if now() - (D.qs.openAt or -1e9) < D.QS.hold then return false end -- d28: a game window is opening (its frame not shown yet)
    for _, n in ipairs(D.QS.busy) do if shown(_G[n]) then return false end end
    local cap = WC.capture
    if type(cap) == "table" and type(cap.state) == "function" then
        local ok, st = pcall(cap.state)
        if ok and type(st) == "table" and st.holder ~= nil and st.holder ~= "dialogs" then return false end
    end
    return D.qsButtons()
end

function D.qsDialog()
    local c = D.qs.cur
    return { i = D.QS.i, which = D.QS.which, text = "Start quest?", sub = c.title,
             item = { name = c.name, icon = c.icon, rgb = c.rgb, id = c.id },
             qs = { item = c.id, bag = c.bag, slot = c.slot, quest = c.quest, title = c.title },
             buttons = { { k = 1, label = "Start", on = true }, { k = 2, label = "Not now", on = true } } }
end

-- build(): the prompt joins the list when it may show (armed first); otherwise our cells are disarmed (a stray tap uses nothing)
function D.qsOffer(list, rolls)
    local q = D.qs
    if q.cur then
        local again = D.qsAt(q.cur.bag, q.cur.slot)
        if not again or again.id ~= q.cur.id then q.dirty = true end          -- moved, used, or its quest taken
    end
    if q.dirty then D.qsScan() end
    if q.cur and D.qsReady(list, rolls) and D.qsAim(q.cur) then
        list[#list + 1] = D.qsDialog()
        return true
    end
    if now() - (q.clickAt or -1e9) > 1 then D.qsAim(nil) end
    return false
end

-- Start or Not now pressed (a binding click: [down] is the edge; a touch cell: nil). Only the edge the game's secure action runs on
-- counts, so the item is used before the capture lets go of the key. [b] = the Start button pressed (our button or our cell).
function D.qsClicked(what, down, b)
    if down ~= nil then
        local kd = TB(function() return GetCVarBool("ActionButtonUseKeyDown") end) == true
        if (down == true) ~= kd then return end
    end
    local c = D.qs.cur
    if not c then return end
    if what == "start" and b then
        -- d28: a Start on a disarmed button used nothing (a window opened in between): not an answer, the item is asked about later
        local ok, cur = pcall(b.GetAttribute, b, "item")
        if not ok or cur ~= ("item:" .. c.id) then return end
    end
    D.qs.gain[c.id] = nil       -- d29: Start or Not now answers that copy; only a NEW copy looted later is offered again
    D.qs.cur, D.qs.dirty = nil, true
    if what == "start" then D.qs.clickAt = now() end
    WC.send(WC.encode({ type = "dialogack", dv = DV, which = D.QS.which, i = D.QS.i, k = (what == "start") and 1 or 2,
                        item = c.id, quest = c.quest }))
    WC.push()
    if type(C_Timer) == "table" and type(WC.dialogs) == "table" then C_Timer.After(0, WC.dialogs.look) end
end

local function buttonsOf(d)
    if d.i == D.QS.i then return _G[D.QS.use], _G[D.QS.no] end
    if d.i == GUILD_I then return guildButton(1), guildButton(2) end
    local f = dialogFrame(d.i)
    return f and buttonOf(f, 1), f and buttonOf(f, 2)
end

local function collect()
    local list = {}
    for i = 1, POOL do
        local f = dialogFrame(i)
        if f then
            if shown(f) then
                local d = read(i, f)
                if D.mode and not d.top and shownByApp(f, d.which) then
                    d.hid = dim(f) or nil
                else
                    undim(f)
                end
                list[#list + 1] = d
            else
                D.since[f] = nil
                undim(f)
            end
        end
    end
    local gf = guildFrame()
    if gf then
        if shown(gf) then
            local d = readGuild(gf)
            if D.mode and shownByApp(gf, d.which) then d.hid = dim(gf) or nil else undim(gf) end
            list[#list + 1] = d
        else
            D.since[gf] = nil
            D.guild = nil
            undim(gf)
        end
    end
    return list
end

-- ---------------------------------------------------------------- the document

local function cellOf(i, k) return (i - 1) * CELLS + (k - 1) end

-- ---------------------------------------------------------------- A / B for a pop-up WITHOUT the game's focus (d16)
-- The user, 2026-10-07: A = the first button, B = the second, on "All such pop-ups" the game shows without the controller's
-- focus (a party invite, a trade): out of a fight, while the companion is in charge, the dialogs take the controller (capture v19,
-- guest "dialogs") with A and B as BINDING CLICKS on two named secure buttons of ours, aimed at the pop-up's own buttons (a
-- binding click, as B's spec.back: the game's own code runs, nothing of ours calls it). The D-pad, X and Y stay the game's. The
-- mirror shows A / B on those buttons. In a fight (and when someone else holds the controller) it stays as before.
local DLG = {}
local function dlgButtons()
    if DLG.a then return true end
    if TB(function() return InCombatLockdown() end) == true then return false end
    pcall(function()
        DLG.a = CreateFrame("Button", "WowCompanionDlgA", UIParent, "SecureActionButtonTemplate")
        DLG.b = CreateFrame("Button", "WowCompanionDlgB", UIParent, "SecureActionButtonTemplate")
        DLG.a:SetAttribute("type", "click"); DLG.b:SetAttribute("type", "click")
        -- d21 (the A-on-dialog lead, b-release-spirit): a binding click reaches a secure button on the PRESS when the game's
        -- ActionButtonUseKeyDown is on, and a button registered for the release only never fired it (TapPad's own measure: both)
        DLG.a:RegisterForClicks("AnyDown", "AnyUp"); DLG.b:RegisterForClicks("AnyDown", "AnyUp")
    end)
    return DLG.a ~= nil and DLG.b ~= nil
end
local DLG_PASS = { "PADDLEFT", "PADDUP", "PADDRIGHT", "PADDDOWN", "PAD3", "PAD4" }
function D.padSync(list)
    local cap = WC.capture
    local fight = TB(function() return InCombatLockdown() end) == true
    local target
    if D.mode and not fight and type(cap) == "table" and (cap.version or 0) >= 19 then
        for _, d in ipairs(list) do
            if (not d.focus or d.guard) and d.top == nil and #d.buttons >= 1 then target = d; break end
        end
    end
    if not target then
        if D.held or D.queued then
            D.held, D.queued = nil, nil
            if type(cap) == "table" then pcall(cap.release, "dialogs") end
        end
        return
    end
    local b1, b2 = buttonsOf(target)
    if not (b1 and dlgButtons()) then return end
    local want = target.i .. ":" .. tostring(target.which)
    if D.held ~= want then
        local okS, st = pcall(cap.state)
        if D.queued == want and okS and type(st) == "table" and st.holder == "dialogs" then
            D.held, D.queued = want, nil             -- a queued take granted (the room or the NPC window let go)
        elseif D.queued ~= want then
            local qs = target.i == D.QS.i       -- d27: the quest starter's A / B are binding clicks of OUR two secure buttons themselves
            if not qs then
                pcall(function()
                    DLG.a:SetAttribute("clickbutton", b1)
                    DLG.b:SetAttribute("clickbutton", shown(b2) and b2 or nil)
                end)
            end
            -- d22: a guarded dialog (QUIT) binds A to nothing: its button 1 ("Exit now") is never pressed by the controller
            local spec = { press = function() end, lost = function() D.held, D.queued = nil, nil end,
                           a = qs and D.QS.use or ((not target.guard) and "WowCompanionDlgA" or nil),
                           back = qs and D.QS.no or (shown(b2) and "WowCompanionDlgB" or nil), noFight = true, pass = DLG_PASS }
            local ok, got, why = pcall(cap.take, "dialogs", spec)
            if ok and got and why == nil then D.held = want
            elseif ok and got then D.queued = want end      -- queued behind a room / an NPC window: as before until then
        end
        if D.held ~= want then return end
    end
    -- the mirror: A on the first button, B on the second, as if the game had focused it
    target.focus, target.fkey, target.padAB = true, nil, true
    for _, b in ipairs(target.buttons) do
        if b.k == 1 and not target.guard then b.key = "PAD1" elseif b.k == 2 and shown(b2) then b.key = "PAD2" else b.key = nil end
    end
end

local function build()
    local list = collect()
    local rolls = readRolls()
    pcall(D.qsOffer, list, rolls)       -- d27: a quest starter's prompt, when nothing else is up
    pcall(D.padSync, list)
    hideRolls(rolls ~= nil)
    -- touch = false: this pad has no room for the dialogs' cells; they are answered with the controller
    local doc = { type = "dialogs", dv = DV, mode = D.mode or nil, list = list, touch = D.touch, rolls = rolls,
                  rollsMore = rolls and D.rollsMore or nil }
    local busy = #list > 0 or rolls ~= nil
    -- d28 (review of combat_nav, the user's decision 1, 2026-11-11): how many pop-ups the app shows as its box (mirrored: not left on top)
    -- - while one shows in a fight the app answers A / B on it, and Interact's sink lets those presses go by (WC.dialogs.mirrored)
    D.mirN = 0
    if D.mode then for _, d in ipairs(list) do if d.top == nil then D.mirN = D.mirN + 1 end end end
    if D.mode and busy and WC.pad and type(WC.pad.slotFor) == "function" then
        local ok, col = pcall(WC.pad.slotFor, "iact", "alive")
        if ok and type(col) == "number" then doc.life = { col = col, every = LIFE_EVERY } end
    end
    -- The pad's geometry goes with EVERY document that shows a dialog: the app needs it for its
    -- sign of life, which is a cell of a row we always have. (d6 sent it only together with the
    -- touch cells; on a pad without room for those the app could never say that it shows the
    -- dialog, and the dialog came back on the top screen after the grace time. Seen 2026-09-29.)
    if busy and WC.pad and type(WC.pad.describe) == "function" then
        local ok, p = pcall(WC.pad.describe)
        if ok and type(p) == "table" and type(p.rows) == "table" then
            doc.pad = p
            -- a secure row drops out of describe() while its cells might be stale: then no cells
            if p.rows[ROW] ~= nil then
                for _, d in ipairs(list) do
                    d.cells = {}
                    for _, b in ipairs(d.buttons) do
                        d.cells["b" .. b.k] = (d.i == GUILD_I) and (GUILD_COL + b.k - 1)
                                              or (d.i == D.QS.i) and (D.QS.col + b.k - 1) or cellOf(d.i, b.k)
                    end
                    if d.extra then d.cells.extra = cellOf(d.i, 5) end
                    if d.x then d.cells.x = cellOf(d.i, 6) end
                end
            end
        end
    end
    return doc
end

-- What decides whether a document is worth a line. `shape`: which dialogs, which buttons, grey or
-- not, hidden or not -- a change is sent at once. `words`: the texts and labels, which the game
-- rewrites every second while it counts down inside them -- sent at most every WORDS_EVERY seconds.
-- The seconds themselves (`left`, `delay`) never count: the app counts down from the last document.
local function keysOf(doc)
    local saved = {}
    local rollLeft = {}
    for n, r in ipairs(doc.rolls or {}) do rollLeft[n] = r.left; r.left = nil end
    for n, d in ipairs(doc.list) do
        saved[n] = { d.left, d.delay, d.text, d.sub, {} }
        d.left, d.delay = d.left and true or nil, d.delay and true or nil
        for k, b in ipairs(d.buttons) do saved[n][5][k] = b.label end
    end
    local words = WC.encode(doc)
    for n, d in ipairs(doc.list) do
        -- An EMPTY sentence is part of the shape: the game shows a dialog first and fills its words
        -- in a moment later, and those first words must not wait for the words' two seconds
        -- (user, 2026-09-29: "Yes, include it").
        local t = d.text
        d.blank = (t == nil or not t:find("%S")) or nil
        d.text, d.sub = nil, nil
        for _, b in ipairs(d.buttons) do b.label = nil end
    end
    local shape = WC.encode(doc)
    for n, r in ipairs(doc.rolls or {}) do r.left = rollLeft[n] end
    for n, d in ipairs(doc.list) do
        d.blank = nil
        d.left, d.delay, d.text, d.sub = saved[n][1], saved[n][2], saved[n][3], saved[n][4]
        for k, b in ipairs(d.buttons) do b.label = saved[n][5][k] end
    end
    return shape, words
end

local function emit(force)
    local doc = build()
    local shape, words = keysOf(doc)
    local t = now()
    local up = #doc.list + #(doc.rolls or {})
    if not force and shape == D.lastShape then
        if words == D.lastWords then return up end
        if t - (D.wordsAt or -1e9) < WORDS_EVERY then return up end
    end
    D.lastShape, D.lastWords, D.wordsAt = shape, words, t
    WC.send(WC.encode(doc))
    WC.push()
    -- a dialog came or went: the interact document says anew where B leads (bnext)
    -- focused / unfocused counts: bnext reads the first, the bar's Select listener the second (capture v5)
    local focused, unfocused = 0, 0
    for _, d in ipairs(doc.list) do if d.focus then focused = focused + 1 else unfocused = unfocused + 1 end end
    local open = focused .. "/" .. unfocused
    if open ~= D.wasOpen then
        D.wasOpen = open
        if type(WC.interactPoke) == "function" then pcall(WC.interactPoke) end
    end
    return up
end

-- a dialog opened, closed or changed its focus: look now, within this very frame (no flash);
-- while one is shown, look again four times a second (its buttons, its end)
-- d19: the shown dialogs' cells aimed again at the game's own buttons (out of combat only; a secure attribute), each look: a cell
-- aimed once after the login can never point at anything but the button the game shows now
function D.aim()
    if not D.cellBtn or TB(function() return InCombatLockdown() end) == true then return end
    for i = 1, POOL do
        local f = dialogFrame(i)
        if f and shown(f) then
            for k = 1, CELLS do
                local cb, t = D.cellBtn[(i - 1) * CELLS + (k - 1)], buttonOf(f, k)
                if cb and t and S(function() return cb:GetAttribute("clickbutton") end) ~= t then
                    pcall(function() cb:SetAttribute("type", "click"); cb:SetAttribute("clickbutton", t) end)
                end
            end
        end
    end
end

local function look()
    pcall(D.aim)
    local ok, n = pcall(emit, false)
    if ok and type(n) == "number" and n > 0 and not D.polling then
        D.polling = true
        C_Timer.After(POLL, function()
            D.polling = false
            look()
        end)
    end
end

-- ---------------------------------------------------------------- hooks (secure: they run after)

if type(hooksecurefunc) == "function" then
    if type(StaticPopup_Show) == "function" then pcall(hooksecurefunc, "StaticPopup_Show", look) end
    if type(StaticPopup_Hide) == "function" then pcall(hooksecurefunc, "StaticPopup_Hide", look) end
    -- the game sets a dialog's alpha itself here: put ours back at once
    if type(GamepadPopupHandler) == "table" and type(GamepadPopupHandler.UpdateVisiblePopups) == "function" then
        pcall(hooksecurefunc, GamepadPopupHandler, "UpdateVisiblePopups", look)
    end
    if type(WC.snapshot) == "function" then
        hooksecurefunc(WC, "snapshot", function(reason)
            if reason == "heartbeat" then pcall(emit, true) end
        end)
    end
end

-- ---------------------------------------------------------------- the companion in charge

local function setMode(on)
    D.mode = on and true or false
    look()
    return true
end

-- In charge or not is ONE setting for all game windows: the hub's element "dialogs" (Interact.lua),
-- which calls setMode() here. The hub has five bits and that element holds the last one.
if type(WC.interactMode) == "function" then
    local ok, on = pcall(WC.interactMode)
    D.mode = ok and on == true
end

-- ---------------------------------------------------------------- touch: secure click-through

-- One cell per button, prepared once, out of combat: a tap is a click on the game's own button.
--
-- ONLY ON THE WIDE PAD. The pad of 6 px rows holds 16 rows and is FULL on the Thor (pad owner,
-- 2026-09-28). Rows are handed out in the order they are asked for, so a row of ours, asked for
-- while this addon loads, would take a place and push the row another session registers last off
-- the pad. So the row is asked for LATE (after every addon has loaded) and only when the pad's
-- rows are thinner than 6 px, which is the wide pad. Until then the dialogs are answered with the
-- controller, and the document carries no cells.
-- d19 (b-release-spirit, 1.0.8: "my release spirit button is not working" / "my accept res button isn't working now either"): the
-- row is asked for AS THIS FILE LOADS, before the rooms' add-ons, so the game's dialogs (release, resurrect, invites) always have
-- their cells. Asked LAST (the 6 px pad's rule above), it was the first row to lose its place when the pad's 54 rows were all
-- taken (1.0.8 added one more room row), and then every dialog button on the bottom screen tapped nothing. The 6 px check stays for
-- a pad that is wide again; a pad with no room left says so (doc "dialogpad", the app's WCDLG log) instead of failing silently.
local function offerTouch()
    if D.touch ~= nil then return end
    if not (WC.pad and type(WC.pad.secureRow) == "function") then return end
    if type(WC.pad.describe) == "function" then
        local ok, d = pcall(WC.pad.describe)
        if ok and type(d) == "table" and type(d.ch) == "number" and d.ch >= 6 then D.touch = false; return end
    end
    local got, row = pcall(WC.pad.secureRow, ROW, LEAVE_COL + LEAVE_N, function(btn, col)
        if col >= LEAVE_COL and col < LEAVE_COL + LEAVE_N then
            btn:SetAttribute("type", "click")       -- d27: its clickbutton (Interact's leave button for that window) is set by Interact
            D.leaveBtn = D.leaveBtn or {}
            D.leaveBtn[col - LEAVE_COL + 1] = btn
            return
        end
        if col == TRADE_COL or col == TRADE_COL + 1 then
            btn:SetAttribute("type", "click")       -- d23: aimed at the trade window's own button (again on TRADE_SHOW: tradeAim)
            D.tCell = D.tCell or {}
            D.tCell[col - TRADE_COL + 1] = btn
            local t = _G[TRADE[col - TRADE_COL + 1].target]
            if type(t) == "table" then btn:SetAttribute("clickbutton", t) end
            return
        end
        if col >= PET_COL and col <= PET_COL + 2 then
            btn:SetAttribute("type", "click")       -- aimed at the charter's own button (again on PETITION_SHOW)
            D.pCell = D.pCell or {}
            D.pCell[col - PET_COL + 1] = btn
            local t = _G[PET[col - PET_COL + 1].target]
            if type(t) == "table" then btn:SetAttribute("clickbutton", t) end
            -- d31 (issue #69): which route pressed the charter's Sign (read only: a stamp, the click itself is the game's)
            if col == PET_COL then pcall(btn.HookScript, btn, "OnClick", function() D.petStamp(1) end) end
            -- int/1429: the first two are also the quest starter's Start / Not now (D.qsAim swaps them while its prompt shows)
            if col == D.QS.col then D.qsCell = btn elseif col == D.QS.col + 1 then D.qsNoCell = btn end
            return
        end
        if col == GUILD_COL or col == GUILD_COL + 1 then
            btn:SetAttribute("type", "click")       -- aimed at the guild invite's own button (again when it shows: aimGuild)
            D.gCell = D.gCell or {}
            D.gCell[col - GUILD_COL + 1] = btn
            local target = guildButton(col - GUILD_COL + 1)
            if target then btn:SetAttribute("clickbutton", target) end
            return
        end
        if col == CLOSE_COL or col == ENTER_COL then
            btn:SetAttribute("type", "click")       -- its clickbutton is set by Interact, out of combat
            if col == CLOSE_COL then D.closeBtn = btn else D.enterBtn = btn end
            return
        end
        local i, k = math.floor(col / CELLS) + 1, (col % CELLS) + 1
        local f = dialogFrame(i)
        local target = f and buttonOf(f, k)
        D.cellBtn = D.cellBtn or {}
        D.cellBtn[col] = btn                 -- d19: kept, so a shown dialog's cells are aimed again (D.aim) and a click can say where
        if target then
            btn:SetAttribute("type", "click")
            btn:SetAttribute("clickbutton", target)
        end
    end, function(col)
        -- int/1429 (starter-prompt d27 on the charter's cells): while the starter's item is armed on the shared cell, its two columns are
        -- Start / Not now (the click already ran: the item was used, or nothing); otherwise they are the charter's Sign / Offer below
        if (col == D.QS.col or col == D.QS.col + 1) and D.qsArmed() then
            if col == D.QS.col then D.qsClicked("start", nil, D.qsCell) else D.qsClicked("later") end
            return
        end
        if col == CLOSE_COL or col == ENTER_COL or (col >= LEAVE_COL and col < LEAVE_COL + LEAVE_N) then
            if type(WC.interactPoke) == "function" then pcall(WC.interactPoke) end
            return
        end
        -- the proof a tap landed; the click itself already ran, in the game's own code
        if col == TRADE_COL or col == TRADE_COL + 1 then
            if type(WC.interactPoke) == "function" then pcall(WC.interactPoke) end      -- d23: the trade window looks again
            if col == TRADE_COL and type(WC.tradeProbe) == "function" then pcall(WC.tradeProbe) end       -- d24
            return
        end
        if col >= PET_COL and col <= PET_COL + 2 then
            if type(WC.interactPoke) == "function" then pcall(WC.interactPoke) end      -- the charter's window looks again
            return
        end
        local gi = (col == GUILD_COL or col == GUILD_COL + 1)
        local i, k = gi and GUILD_I or (math.floor(col / CELLS) + 1), gi and (col - GUILD_COL + 1) or ((col % CELLS) + 1)
        -- d19 (b-release-spirit, 1.0.8: "it presses but nothing happens and then it reappears"): the click's answer says WHAT it
        -- clicked: the dialog (which), the game's button, whether that button was enabled, whether the cell was aimed at it; and,
        -- half a second later, whether that dialog is still up (a release that the game took closes DEATH)
        local f = (not gi) and dialogFrame(i) or nil
        local which = f and TS(function() return f.which end)
        local cur = f and buttonOf(f, k)
        local cb = D.cellBtn and D.cellBtn[col]
        local aimed = cb and cur and (S(function() return cb:GetAttribute("clickbutton") end) == cur) or false
        WC.send(WC.encode({ type = "dialogack", dv = DV, cell = col, i = i, k = k, which = which,
                            enabled = cur and TB(function() return cur:IsEnabled() end) or nil, aimed = aimed and true or false,
                            dead = TB(function() return UnitIsDeadOrGhost("player") end) }))
        WC.push()
        if f and type(C_Timer) == "table" then
            C_Timer.After(0.5, function()
                local still = shown(f) and TS(function() return f.which end) == which
                WC.send(WC.encode({ type = "dialogack", dv = DV, after = true, cell = col, i = i, k = k, which = which,
                                    still = still and true or false, ghost = TB(function() return UnitIsGhost("player") end) }))
                WC.push()
            end)
        end
        look()
    end)
    D.touch = got and row ~= nil
    if not D.touch then
        WC.send(WC.encode({ type = "dialogpad", dv = DV, why = got and "the tap pad has no row left" or tostring(row),
                            rows = type(WC.pad.nrows) == "number" and WC.pad.nrows or nil }))
        WC.push()
    end
end
pcall(offerTouch)          -- d19: now, as the add-on loads (PLAYER_LOGIN and the pad's layout below only retry a failed first ask)

local function petAim()
    if TB(function() return InCombatLockdown() end) == true then return end
    for k, e in ipairs(PET) do
        local t = _G[e.target]
        if not _G[e.name] then
            pcall(function()
                local b = CreateFrame("Button", e.name, UIParent, "SecureActionButtonTemplate")
                b:SetAttribute("type", "click")
                b:RegisterForClicks("AnyDown", "AnyUp")         -- d25: the charter's A too (a binding click)
            end)
        end
        local b = _G[e.name]
        if b then pcall(b.RegisterForClicks, b, "AnyDown", "AnyUp") end
        if b and type(t) == "table" then pcall(function() b:SetAttribute("clickbutton", t) end) end
        local c = D.pCell and D.pCell[k]
        if c and type(t) == "table" then pcall(function() c:SetAttribute("clickbutton", t) end) end
    end
end
-- d23: the trade's two secure buttons, made and aimed out of combat (a trade never opens in a fight)
local function tradeAim()
    if TB(function() return InCombatLockdown() end) == true then return end
    for k, e in ipairs(TRADE) do
        local t = _G[e.target]
        if not _G[e.name] then
            pcall(function()
                local b = CreateFrame("Button", e.name, UIParent, "SecureActionButtonTemplate")
                b:SetAttribute("type", "click")
                -- d25 (the user on test16: "the x button didn't work to accept and b didn't close"): a binding click fires a secure
                -- button only when it is registered for the press too (Map.lua: "a binding click fires it once per press")
                b:RegisterForClicks("AnyDown", "AnyUp")
            end)
        end
        local b = _G[e.name]
        if b then pcall(b.RegisterForClicks, b, "AnyDown", "AnyUp") end
        if b and type(t) == "table" then pcall(function() b:SetAttribute("clickbutton", t) end) end
        if b and k == 1 and not D.tHooked then
            D.tHooked = true
            pcall(function() b:HookScript("OnClick", function() if type(WC.tradeProbe) == "function" then pcall(WC.tradeProbe) end end) end)
        end
        local c = D.tCell and D.tCell[k]
        if c and type(t) == "table" then pcall(function() c:SetAttribute("clickbutton", t) end) end
    end
end
do
    local tf = CreateFrame("Frame")
    tf:SetScript("OnEvent", function() tradeAim() end)
    pcall(function() tf:RegisterEvent("PLAYER_LOGIN") end)
    pcall(function() tf:RegisterEvent("TRADE_SHOW") end)
end
-- d31 (issue #69, 1.0.18: "tried to sign a guild chart didnt work"): WHAT CAME OF A SIGN, for the next report. Read only: the game's Sign
-- button is hooked after its own click (HookScript: the game's code runs first and untouched), our two routes to it (the touch cell and
-- A's secure button) only stamp which one it was. For 4 s after the Sign: the game's red error (UI_ERROR_MESSAGE: its numeric id), a
-- petition system message (CHAT_MSG_SYSTEM matched against the game's own ERR_PETITION_* strings: only its PLACE in D.PETMSG leaves, never
-- words), PETITION_CLOSED, a pop-up shown, the signature count before / after. One "cap" document { petSign = {...} }, numbers only (the
-- diagnostics keep shapes, never words). The list's order is fixed: append only.
D.PETMSG = { "ERR_PETITION_SIGNED", "ERR_PETITION_SIGNED_S", "ERR_PETITION_ALREADY_SIGNED", "ERR_PETITION_ALREADY_SIGNED_OTHER",
             "ERR_PETITION_IN_GUILD", "ERR_PETITION_RESTRICTED_ACCOUNT", "ERR_PETITION_RESTRICTED_ACCOUNT_TRIAL", "ERR_PETITION_NOT_SAME_SERVER",
             "ERR_PETITION_FULL", "ERR_PETITION_CREATOR", "ERR_PETITION_DECLINED_S", "ERR_PETITION_OFFERED_S", "ERR_ALREADY_IN_GUILD",
             "ERR_ALREADY_IN_GUILD_S", "ERR_GUILD_NAME_EXISTS_S", "ERR_PLAYER_WRONG_FACTION", "ERR_GUILD_PERMISSIONS" }
D.PET_WATCH = 4
-- our route's own click hook (it runs AFTER the click it made, the game's Sign included): 1 = the touch cell, 2 = A's button
function D.petStamp(n)
    local w = D.petW
    if w and w.via == 0 and now() - w.at < 0.5 then w.via = n else D.petVia = { n = n, at = now() } end
end
function D.petNames() return TN(function() return GetNumPetitionNames() end) end
-- the place in D.PETMSG of the game string this text is (its %s / %d as wildcards), nil = none of them
function D.petMsg(text)
    if type(text) ~= "string" then return nil end
    for i, n in ipairs(D.PETMSG) do
        local g = TS(function() return _G[n] end)
        if g then
            local pat = "^" .. g:gsub("([%%%^%$%(%)%.%[%]%*%+%-%?])", "%%%1"):gsub("%%%%s", ".+"):gsub("%%%%d", "%%d+") .. "$"
            local ok, hit = pcall(string.find, text, pat)
            if ok and hit then return i end
        end
    end
    return nil
end
function D.petPopup()
    for i = 1, 4 do
        local f = _G["StaticPopup" .. i]
        if type(f) == "table" and shown(f) then return i end
    end
    return nil
end
function D.petStart()
    local via = (D.petVia and now() - D.petVia.at < 1) and D.petVia.n or 0     -- 1 = the touch cell, 2 = A, 0 = neither of ours
    D.petVia = nil
    D.petW = { at = now(), via = via, before = D.petNames(), combat = TB(function() return InCombatLockdown() end) == true }
    local w = D.petW
    C_Timer.After(D.PET_WATCH, function()
        if D.petW ~= w then return end
        D.petW = nil
        local p = { via = w.via, before = w.before, after = D.petNames(), closed = w.closed or false, err = w.err, sys = w.sys,
                    popup = w.popup or D.petPopup(), combat = w.combat }
        pcall(function() WC.send(WC.encode({ type = "cap", petSign = p })) end)
    end)
end
function D.petHook()
    if D.petHooked then return end
    local b = _G.PetitionFrameSignButton
    if type(b) ~= "table" then return end
    if pcall(b.HookScript, b, "OnClick", function() pcall(D.petStart) end) then D.petHooked = true end
    local own = _G.WowCompanionPetSign
    if type(own) == "table" and not D.petOwnHooked then
        D.petOwnHooked = pcall(own.HookScript, own, "OnClick", function() D.petStamp(2) end)
    end
end
do
    local pf = CreateFrame("Frame")
    pf:SetScript("OnEvent", function(_, event, a1, a2)
        if event == "PLAYER_LOGIN" or event == "PETITION_SHOW" then petAim(); pcall(D.petHook); return end
        local w = D.petW
        if not w then return end
        if event == "PETITION_CLOSED" then w.closed = true
        elseif event == "UI_ERROR_MESSAGE" then w.err = w.err or TN(function() return a1 end) or -1
        elseif event == "CHAT_MSG_SYSTEM" then w.sys = w.sys or D.petMsg(TS(function() return a1 end))
        end
        w.popup = w.popup or D.petPopup()
    end)
    for _, e in ipairs({ "PLAYER_LOGIN", "PETITION_SHOW", "PETITION_CLOSED", "UI_ERROR_MESSAGE", "CHAT_MSG_SYSTEM" }) do
        pcall(function() pf:RegisterEvent(e) end)
    end
end

do
    local gf = CreateFrame("Frame")
    gf:SetScript("OnEvent", function(_, event, inviter, name)
        if event == "GUILD_INVITE_REQUEST" then
            D.guild = { inviter = type(inviter) == "string" and inviter or nil, name = type(name) == "string" and name or nil,
                        at = TN(function() return GetTime() end) or 0 }
            -- the cells aim at the invite's buttons (the frame may load late); never in a fight
            if D.gCell and TB(function() return InCombatLockdown() end) ~= true then
                for k = 1, 2 do
                    local b, t = D.gCell[k], guildButton(k)
                    if b and t then pcall(function() b:SetAttribute("clickbutton", t) end) end
                end
            end
            local f = guildFrame()
            if f and not D.gHooked then
                D.gHooked = true
                pcall(function() f:HookScript("OnShow", look); f:HookScript("OnHide", look) end)
            end
        end
        look()
    end)
    pcall(function() gf:RegisterEvent("GUILD_INVITE_REQUEST") end)
    pcall(function() gf:RegisterEvent("GUILD_INVITE_CANCEL") end)
end

-- d27: the bags looked at anew when they change, a quest is taken or dropped, a fight ends (a held prompt shows), a game window closes
-- (a queued prompt shows); the frame is kept in D (no chunk local)
D.qsFrame = CreateFrame("Frame")
D.qsFrame:SetScript("OnEvent", function(_, event, a1, a2)
    if event == "PLAYER_REGEN_ENABLED" then look(); return end
    -- d29: a login or a /reload (or the first entering in this Lua state): the bags' counts until settled are the baseline, then one
    -- last absorbing tally (nothing in the bags by then is ever offered); a plain zone change keeps the session's tally
    if event == "PLAYER_ENTERING_WORLD" and (not D.qs.settleAt or a1 or a2) then
        D.qs.settleAt, D.qs.slots = now(), {}
        if type(C_Timer) == "table" then
            C_Timer.After(D.QS.settle + 0.5, function() pcall(D.qsTally, true); D.qs.dirty = true; look() end)
        end
    end
    -- d29: the bank open: what comes out of it is the player's own, moved, not looted (absorbed until its close has settled)
    if event == "BANKFRAME_OPENED" or event == "GUILDBANKFRAME_OPENED" then D.qs.bank = true end
    if (event == "BANKFRAME_CLOSED" or event == "GUILDBANKFRAME_CLOSED") and type(C_Timer) == "table" then
        C_Timer.After(0.5, function() pcall(D.qsTally, true); D.qs.bank = false; D.qs.dirty = true; look() end)
        return
    end
    -- d28: a fight starting (the event comes before the lockdown) or a game window opening: disarm NOW, in this frame, then rebuild
    -- (the prompt drops); a window's frame may not be shown yet, so its event holds the prompt off for D.QS.hold
    if event == "PLAYER_REGEN_DISABLED" or D.QS.openSet[event] then
        if event ~= "PLAYER_REGEN_DISABLED" then D.qs.openAt = now() end
        D.qsAim(nil)
        look()
        return
    end
    if event == "GET_ITEM_INFO_RECEIVED" then
        if D.qs.cur and not D.qs.cur.name then D.qs.dirty = true; look() end
        return
    end
    D.qs.dirty = true
    -- a loot line comes before the item is in the bag, a closing window before its frame is hidden: a moment later
    if (event == "CHAT_MSG_LOOT" or event:find("CLOSED$") or event == "QUEST_FINISHED") and type(C_Timer) == "table" then
        C_Timer.After(0.5, function() D.qs.dirty = true; look() end)
        return
    end
    look()
end)
for _, e in ipairs({ "BAG_UPDATE_DELAYED", "CHAT_MSG_LOOT", "QUEST_ACCEPTED", "QUEST_REMOVED", "QUEST_TURNED_IN", "PLAYER_ENTERING_WORLD",
                     "PLAYER_REGEN_ENABLED", "PLAYER_REGEN_DISABLED", "GET_ITEM_INFO_RECEIVED", "QUEST_FINISHED", "GOSSIP_CLOSED",
                     "MERCHANT_CLOSED", "LOOT_CLOSED", "TRADE_CLOSED", "MAIL_CLOSED", "BANKFRAME_CLOSED", "TRAINER_CLOSED",
                     "ITEM_TEXT_CLOSED", "TAXIMAP_CLOSED", "PETITION_CLOSED", "AUCTION_HOUSE_CLOSED", "GUILDBANKFRAME_CLOSED" }) do
    pcall(D.qsFrame.RegisterEvent, D.qsFrame, e)
end
D.QS.openSet = {}
for _, e in ipairs(D.QS.opens) do D.QS.openSet[e] = true; pcall(D.qsFrame.RegisterEvent, D.qsFrame, e) end      -- d28

do
    local lf = CreateFrame("Frame")
    lf:SetScript("OnEvent", function()
        if type(C_Timer) == "table" then C_Timer.After(1, function() pcall(offerTouch); look() end) end
    end)
    pcall(function() lf:RegisterEvent("PLAYER_LOGIN") end)
    if type(WC.pad) == "table" and type(WC.pad.onLayout) == "function" then
        WC.pad.onLayout(function() pcall(offerTouch) end)
    end
end

WC.dialogs = { version = DV, setMode = setMode, look = look,
               -- d18: the charter's touch cells (Interact's petition window), nil while the row is not built
               petCells = function() if D.pCell then return { sign = PET_COL, offer = PET_COL + 1, rename = PET_COL + 2 } end end,
               -- d23: the trade window's touch cells (Interact's trade window), nil while the row is not built
               tradeCells = function() if D.tCell then return { accept = TRADE_COL, cancel = TRADE_COL + 1 } end end,
               -- the fight-close cell: its button (to aim, out of combat), its row and column; nil while the row is not built
               closeCell = function() if D.closeBtn then return D.closeBtn, ROW, CLOSE_COL end end,
               enterCell = function() if D.enterBtn then return D.enterBtn, ROW, ENTER_COL end end,
               -- d27: the i-th per-window leave cell (1 .. LEAVE_N): its button, row and column; nil while the row is not built
               leaveCell = function(i)
                   local b = D.leaveBtn and D.leaveBtn[i]
                   if b and i >= 1 and i <= LEAVE_N then return b, ROW, LEAVE_COL + i - 1 end
               end,
               leaveN = LEAVE_N,
               mirrored = function() return D.mirN or 0 end,       -- d28: the pop-ups the app shows as its box, at the last document
               rollsHide = function(on) ROLLS_HIDE = on and true or false; look() end,  -- the harness; the build flips the default
               -- how many dialogs the controller answers right now: shown AND with the game's focus
               -- (read by Interact.lua for bnext). A dialog without the focus does not take B.
               open = function()
                   local n = 0
                   for i = 1, POOL do
                       local f = dialogFrame(i)
                       if f and shown(f) and TB(function() return f.gamepadFocused end) then n = n + 1 end
                   end
                   return n
               end,
               -- shown dialogs WITHOUT the game's focus: Select is the game's focus key for them (capture v5)
               unfocused = function()
                   local n = 0
                   for i = 1, POOL do
                       local f = dialogFrame(i)
                       if f and shown(f) and not TB(function() return f.gamepadFocused end) then n = n + 1 end
                   end
                   local gf = guildFrame()
                   if gf and shown(gf) and not TB(function() return gf.gamepadFocused end) then n = n + 1 end
                   return n
               end,
               -- d20: the sign of life also asks for a fresh document (b-release-spirit: the app taps it right after the controller's
               -- press; a dialog still listed in it = the game did not take the press, and the app taps the button's cell instead)
               alive = function()
                   D.lifeAt = now()
                   look()
                   pcall(emit, true)
               end }

-- a dialog that was open across a reload
if type(C_Timer) == "table" and type(C_Timer.After) == "function" then C_Timer.After(0, look) end
