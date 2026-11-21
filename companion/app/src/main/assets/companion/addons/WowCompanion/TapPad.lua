-- WowCompanion / TapPad.lua
-- THE ONE SHARED INPUT CHANNEL (user ruling 2026-09-27): every room's app -> addon action is ONE
-- TAP on the top screen. Owned by the quests session; ask in AGENT_LOG.md for changes.
--
-- An addon cannot receive anything from outside the game at runtime: no sockets, no file reads,
-- no clipboard read (checked against the client's string table). The only way in is input. Typed
-- chat was the first route and it is broken on this device: Android's keyboard system swallows the
-- characters once the game's edit box has focus, leaving the box open (measured 2026-09-27).
--
-- So the addon owns a grid of INVISIBLE buttons at a spot it publishes, and the app taps a cell.
-- GameNative runs the container in touchscreen mode, so a tap on display 0 arrives as a mouse
-- click, and the button's OnClick runs. PROVEN ON THE THOR (probe, 2026-09-27): the click
-- landed in 69 ms, at exactly the intended pixel, with no chat box and the controller left in play
-- mode. One touch = one click = one action.
--
-- GRID: one ROW per operation, one COLUMN per slot.
--   * pad.op(name, ns, handler): a plain row. A slot is a stable key (a questID) within a
--     namespace. A key keeps its column for as long as it lives, and a freed column is quarantined
--     for QUARANTINE seconds before reuse, so a tap made from a slightly stale document can never
--     land on a different key. The handler receives the KEY; replies echo it so the app can check.
--   * pad.secureRow(name, ncols, init, onClick[, opts]): a row of SecureActionButtonTemplate cells, for
--     actions the game only allows from a hardware event (use an item, cast). A mouse click on a
--     secure button IS a hardware event. The cells' attributes are set by init(button, col) and are
--     STATIC: secure frames cannot be created, changed or moved in combat, so all of that happens
--     out of combat (queued until PLAYER_REGEN_ENABLED), while clicking them works in combat.
--
-- A PLAIN ROW'S HANDLER RUNS AS ADDON (TAINTED) CODE. It must NEVER call Blizzard's own UI Lua that
-- opens, closes or rearranges a game window (CloseAllBags / OpenAllBags / ToggleSpellBook / ShowUIPanel
-- / ...), and must never write a field on a Blizzard frame or table. Blizzard's code then runs tainted,
-- and on this controller client the gamepad action bar's UpdateInteractIcons hits the protected
-- SetPreferredGamepadInteractTarget() and is refused on every target change until a reload. It
-- happened twice on 2026-09-28: 4,012 refusals after a `/wcpad show` typed into chat (11:06), and 2,050
-- in 90 s after a plain op called CloseAllBags / OpenAllBags (22:57). Game-engine calls (C_QuestLog.*,
-- C_SuperTrack.*, SetAlpha, ...) are fine; to press one of the GAME's controls, use a secureRow cell
-- whose click presses that control.
--
-- A PLAIN CELL FIRES ON THE PRESS (pad5, 2026-09-30). The professions session measured on the Thor that in controller
-- freelook the game puts its hidden cursor back in the middle of the screen (640,287 read just after a tap), and when that
-- lands between `input tap`'s press and release, the release is off the button: a cell registered for the release
-- ("AnyUp", pad4) never fired. 2 of 10 taps right after a controller press were lost that way, while the press itself
-- landed inside the intended cell 10 of 10 times. So plain cells register for the PRESS only: they fire where the pointer
-- is known to be right.
-- BUT ONE TAP ARRIVES AS TWO PRESSES on this device (pad5 on the Thor, 2026-09-30 14:04: every Quests action answered twice,
-- 2 ms apart; bags had seen a "stray second press" 3 ms after a drop). pad4 hid that, because both presses share one release.
-- So a cell ignores a repeat press within REPEAT seconds of its last one (pad5b): once per tap, on the first press.
-- SECURE CELLS CANNOT DO THE SAME: for a real mouse click, the game's secure template acts on the RELEASE whatever the
-- ActionButtonUseKeyDown setting (securetemplates.lua SecureActionButton_OnClick: `useOnKeyDown = not isSecureMousePress
-- and ...`), and so does their onClick hook. A secure cell tapped right after a controller press can still be lost; the
-- owning room's answer (or its absence) is what shows it.
-- UNLESS THE CELL HAS NO SECURE ACTION (pad6b, the spells session's ask 2026-10-01): a secure row may mix cells that press a
-- game control with cells that only run the row's onClick (spells turned its whole row secure for 3 such cells, the pad
-- being full). With opts.onPress, a cell whose "type" is nil runs onClick on the PRESS, like a plain cell and with the same
-- REPEAT guard, and nothing on the release; a cell with a secure action keeps the release. Older pads ignore the argument.
--
-- A STRAY PRESS LANDS ON THE FREELOOK PARK POINT, SO A SINK SITS THERE (pad6, 2026-09-30). In controller freelook the game
-- parks its hidden cursor at one fixed point, game px (480, 214.5) on this 960x540 screen: measured by the mouse diagnostic on
-- 2026-09-28, and again on 2026-09-30 15:44:37 by the professions session, when the game snapped the cursor there just
-- BEFORE an injected press. That press landed on the 3D world (GetMouseFoci empty) and could have targeted or interacted
-- with whatever stood there: a game action from no touch of its own. A tap is never meant to land there, so a small frame
-- covers that point: BACKGROUND strata, so every game window above it still takes its own clicks and only the WORLD is
-- below it; CLICK ONLY (SetMouseMotionEnabled false), so the parked cursor's hover over the world still works. A press
-- that lands on it does nothing in the game and is reported (a "padstray" document, at most one a second), so the room's
-- missing answer has a reason. What it cannot catch: a press snapped anywhere else (none measured).
-- WHILE ANYONE HOLDS THE CONTROLLER IT GOES ON TOP (pad6c, Home's ask 2026-10-01, raised by calendar). The user's ruling "every
-- room opens the game's own window behind it" means a holding room's game window is OPEN, hidden by alpha, and an invisible
-- window still takes clicks: on BACKGROUND the sink sat under it, so a stray press would land on a spell, an item or a calendar
-- control nobody can see. So while the shared capture has a holder (a room, or interact's "npc"), the sink is TOOLTIP strata,
-- above every window; with no holder it is BACKGROUND again (today's behaviour: visible windows take their own clicks). The
-- capture has no change hook, so a ticker reads its state() every SINK_POLL seconds. The sink is a plain frame: changing its
-- strata is allowed in combat, so the rule holds through a fight. Still click only: hover is unchanged either way.
--
-- GEOMETRY IS MEASURED, NEVER ASSUMED. The game renders at 960x540 on this device and GameNative
-- scales it 2x; the UI scale is 0.94. describe() publishes the grid in the game's own pixels,
-- top-left origin, together with that pixel size, and the app converts to display pixels itself.
-- A secure row is only published while its cells really sit at the published geometry: if the
-- screen is re-laid out in combat, secure rows drop out of describe() until combat ends.

local ADDON = ...
local WC = _G.WowCompanion
if type(WC) ~= "table" or type(WC.emit) ~= "function" then return end
local S, SN, CALL = WC.S, WC.SN, WC.CALL

local PADV = "pad27"     -- pad27 NO PARTIAL CHARACTER KEY (the 11-20 hunter, test build 1513): L.who() (the layouts' key) is Safety k6's WC.charKey(), nil until the realm is known (it was "Name-" with an empty realm); pad26 THE ROW ORDER (GitHub #550, with #564 / #570 / #249 / #260: "top screen ui doesnt update when editing in settings"; the reporter: the switches only failed with the game at about 960x540, at 1280x720 everything toggled): a row is no longer given the next free row in the order the add-ons happen to register, but ITS OWN ROW from ORDER (below), ranked by impact - the top screen's switches "uisw" and its one-tap restore / layout row "ui" FIRST (rows 0-1, placed at any resolution and UI scale), the least used rows last, so a smaller layout drops those; describe() says `drop` = the registered rows this layout cannot place (name -> row; normally absent), so the app can say so once instead of waiting on a row that is not there; same cells, same geometry, same fits(); a name not in ORDER takes the lowest free row, as before; pad25 NO CURSOR FROM OUR OWN PRESSES (the user, 2026-11-19, streaming 1.0.36: "the cursor keeps randomly showing on the game"; the Thor's taps.log: every press Desktop posts moves the game's own cursor to the cell, and pad13's rule showed it again on that move - ResetCursor - until it had rested a second at the park): a press of ours (a cell, the calibration strip, the layout catcher) ARMS the blank cursor at once; the moves a press explains (to the cell and the game's park, within PRESS_WIN of it, or back to the park spot it learned) keep it blank wherever it rests, over the world or a window; the first move nothing of ours explains (a real mouse, the trackpad, a touch on the top screen) disarms it and is kept as pad.humanAt (Dialogs d35 reads it); an item on the cursor or a spell aiming shows it, and it is still only under the game's own cursor drawing (pad14's soft()); nothing saved, no setting changed; pad24 THE STREAM'S TAPS MEASURED (the user, 2026-11-18, test 1494 streamed: "testing stream, ui still didnt hide"; pad23's ring proved every "ui" tap (row 51, aimed y 115.5) pressed "uisw" (row 50) and every "pnav" tap (row 1, y 15.5) pressed "chq" (row 2) while the rows between were right: on the PC the game sees a press about 2 % closer to y ~65 than the app aims it): THE CALIBRATION STRIP, one plain press-only Button of ours, CAL_W game px wide, CAL_GAP left of the grid, over the band's full placed height, that does nothing but keep where the game saw each press (GetCursorPosition, read only; describe()'s `calib`: its box, the count and the last 4 "x,y" in game px), so the app taps it twice (top and bottom) and aims every tap through what it measured; the ring of presses also keeps where each was seen (rxDoc `at`, beside `last`); the catcher's band zone takes the strip in; nothing else changed; pad23 WHERE A TAP LANDED (the user, 2026-11-18, test 1492 streamed: "it looked like it was doing a lot of clicks again and also the UI didnt hide"; the "ui" row's restore and layout taps were never answered on the PC while the "uisw" row 2 px above them was): every cell press is kept in a ring of the last 8 ("row:col", pad.rxDoc: the count and the ring, read by Hub's document), and describe() says `off` = the rows whose first cell does NOT stand where the grid says (its real top in game px, read off the frame; normally absent) - read only, nothing else changed; pad22 THE LAYOUT EDITOR'S CATCHER AND STORE (the user, 2026-11-16: "we need to make a way to basically edit the UI locations on the top screen from our UI menu"; the design's picks, all the recommended ones: our own pieces first, taps that set an exact spot): WC.lay at the end of this file - the per-character saved layout of our own top-screen pieces (WowCompanionDB.lay[name-realm]), the editor's view of its draft, and THE CATCHER, one full-screen plain button at TOOLTIP strata under every cell and the sink, press only, that reads where a tap landed (its own 60 ms same-spot guard, the pad band and the sink left out, the reach switch); nothing else changed; pad21 THE SHARED ROW (the 1.0.32 test build, code 1472: the Macros room's lazy row "mac" had no row at the fullest layout - six bags, the cast bar, both radials - so the room said "No pad row"): a secure row registered with opts.share is a GUEST on another secure row (its own cells; one of the two on the row at a time, the host by default; the other hidden and left out of describe()), pad.lease(name) puts one on the row out of combat (in a fight it waits), pad.holds(name); the Macros room is the Calendar room's guest (Spells s12, Book b65: padtab 39 then 34 / 35); pad20: pad15's switch-back-on now finds an add-on the game has off by the game's own reason too (issue #278, the user relaying a player, 2026-11-13: "says waitin on wow controller. i press start and the wheel only pops up on top screen": the login probe said WowCompanionSpells:DISABLED and pad15 never switched it on - GetAddOnEnableState(name, character) did not say 0 on that client); pad19: pad.op(..., { last = true }) pins a row to the LAST row (ROWS_MAX - 1; Bags' shared drag row "bmv", the user 2026-11-13: "Bags share one row"); pad18: pad16's health probe and pad17's health strips REMOVED (the user, 2026-11-05: reading hidden health off the screen would break Blizzard's terms); pad17: five hair-thin health strips in the top-left corner, filled by the game (probe strips); pad16: a read-only health probe (every reader's kind on a health change, probe hpprobe); pad15: our add-ons the game has switched off are switched on again at login (the app reloads once); pad14: the software cursor (gxCursor 0, set once) and the hiding only under it (the hardware cursor drew the blank as a black box); pad13: the cursor hidden once still for a second anywhere over the world (the game parks it at y 41 %, not the centre); pad12: the PARKED CURSOR hidden (a blank cursor while it rests at the screen centre over the world); pad11 (Party mode, the lead's contract PARTY_BUILD.md 2.1): ROWS_MAX 56 for the party row; nothing else changed
local COLS = 40          -- columns per row; the quest log holds at most 40, a bag at most 36
local ROWS_MAX = 56      -- rows that may register; only those that end ABOVE the pop-up dialogs are placed (pad8 / pad9 / pad11, see below)
local POPUP_MARGIN = 1   -- game px a row keeps clear of popupTop
local CELL_W = 6         -- game pixels per cell, across (12 display px on the Thor)
local CELL_H = 2         -- game pixels per cell, down (4 display px) -- see below
local QUARANTINE = 30    -- seconds a freed column stays unused
local REPEAT = 0.25       -- seconds: a second press on the same plain cell within this is the same tap (see above)
local PARK_X, PARK_Y = 0.5, 214.5 / 540   -- the freelook park point, as fractions of the game's screen (see above)
local SINK = 10          -- game pixels across the stray-press sink, centred on the park point
local SINK_POLL = 0.1    -- seconds between reads of the capture's holder (the sink's strata follows it)
local TOP_GAP = 12       -- game pixels between the screen's top edge and the first row
local CAL_W = 6          -- pad24: game pixels across the calibration strip (see THE STREAM'S TAPS MEASURED)
local CAL_GAP = 6        -- pad24: game pixels between the strip and the grid's first column
-- ROW HEIGHT (user rulings 2026-09-28: ~18:55 "thinner rows, 3 px"; ~21:10 "2 px rows now", before the first push, because the
-- rooms' requests reached 27 rows, 31 with more bags). 16 rows of 6 px filled up, and rows 13+ reached down into the game's
-- pop-up dialogs, which Blizzard anchors TOP at -135 UI units, centred right under this grid (89 game px at this device's 0.94
-- UI scale; `popupTop` in describe()). The cells are TOOLTIP strata, so a row inside a dialog turns a finger tap on its
-- buttons into a pad action. 2 px rows fit 38 rows above the dialogs. A tap lands at a row's centre, +/-1 game px from its
-- edges; the mouse diagnostic measured the vertical landing EXACT (y 15.0 / 81.0). An earlier try at 2 px ("pad3") looked
-- dead, but that was the TapBridge 1240-width bug misaiming x, not the rows. The app never assumes a size: it reads cw / ch
-- from describe(). Any change here gets ONE real tap test on the Thor before rooms rely on it; if it misses, 6 px goes back.
-- ROWS PAST 37 (pad8, 2026-10-06, the consumables radial's row; the pad was full). 38 was the count that fits above the dialogs
-- on GameNative's 960x540 (popupTop 89.2 game px). WoW Forever renders 1920x1080, where the dialogs start at 135 UI units = 121.5
-- px or lower at any UI scale the game allows (0.64-1.15), so 48 rows (ending at 108 px) fit there. So up to ROWS_MAX rows
-- register, and a row is PLACED (shown, published in describe()) only while its bottom is above popupTop, measured at every
-- layout: on 960x540 that is still exactly rows 0-37; a row that does not fit is hidden, never left inside a dialog. Rows keep
-- their places, so rows 0-37 never move.
-- pad9 (2026-10-07, the pad owner's: the rooms' rows reached 45 of 48, 47 with a reagent bag): ROWS_MAX 54. At 1920x1080 the
-- dialogs' top is 135 UI units = 121.5 px at the SMALLEST UI scale the game allows (UIParent 1200 units tall); a row is placed only
-- while it ends at least POPUP_MARGIN above it, so row 53 (ending at 12 + 54 x 2 = 120) is the last that fits at EVERY UI scale --
-- all 54 are placed whatever scale the player picks (the Thor's own scale fits 56). Nothing else changed: same cells, same rules.
-- pad10 / pad10c (2026-10-09, L1 THE KEY CHANNEL: the user's pick "L1 key channel" via Home): see THE KEY CHANNEL below.
-- pad11 (2026-10-28, Party mode's ONE secure row "party"; the pad was full at 54 with every bag and the reagent bag): ROWS_MAX 56.
-- On the Thor's own UI scale (popupTop 126.6 game px) rows 0-55 fit (row 55 ends at 12 + 56 x 2 = 124); at the SMALLEST scale (121.5)
-- rows 54-55 are neither placed nor described, exactly as pad8 / pad9 drop rows, and the app finds those cells missing. Same cells,
-- same geometry, same fits() and describe(). One real tap test on the device is owed before release (probe P11).
-- pad26 THE ROW ORDER (GitHub #550 and its duplicates #564, #570, #249, #260). Rows were handed out in the order the add-ons registered
-- them, and Hub's top-screen switch rows "uisw" / "ui" register late (rows 49-50 of 56). fits() places only the rows that end above
-- popupTop, and that is fewer rows than 56 on a smaller layout: at the game's 960x540 (popupTop 94.9) rows 0-39, at 1280x720 with a
-- lowered UI scale (#249: 107.6) rows 0-46, at 1920x1080's smallest scale (121.5) rows 0-53, at 960x540's smallest (0.64: 60.75) rows
-- 0-22. So the switches were never placed there and their tiles waited forever. Now every known row has ITS OWN ROW, its place in
-- ORDER, ranked by impact: the rows the top screen's switches and the one-tap restore need first (they fit at any layout the game
-- allows), then the rooms' everyday rows, the least used last. A row keeps its row whatever order the add-ons load in or whichever
-- add-on is off (a lazy row's row waits for it), so taps from a stale document still never land on a different row. Which rows a
-- smaller layout drops (the bands are the measured layouts above; a room's rows are kept together across each band's edge):
--   every layout:  uisw ui padtab spells sradial qpick qact ipick iact idlg pause gamemenu party pfocus bag0-5 binv pnav uwp (rows 0-22)
--   below 1920x1080's smallest scale (rows 54-55):  keytest, bmv
--   and below 1280x720 lowered (#249, rows 47-53):  prof profsel profopen, gf gfl gfw gfclose
--   and below 960x540 (rows 40-46):                 talents talentn, aurapick auracancel, spick sact, cal (+ its guests mac, lay)
--   and below 960x540's smallest (rows 23-39):      chq chr chs chcmd, ibuy isell iqty itrain, castbar radial classr tcast, mapw,
--                                                   bagsort bmvq, extra radialpin
-- A name not in ORDER (a new row nobody ranked yet: add it here; tools/test_pad_order.py fails on one) takes the lowest free row, as
-- every row did before; opts.last (pad19) is kept for such a name. A ranked row whose own row is taken does the same. describe()'s `drop` lists the registered rows this layout does not place, so the
-- app can say so (once) and never waits on them.
local ORDER = {
    "uisw", "ui",                                                    -- 0-1   the top screen's switches; the one-tap restore / layout
    "padtab",                                                        -- 2     Book's one-shot pick (leases, the chooser)
    "spells", "sradial",                                             -- 3-4   the Spellbook's cells; the spell radials (fights)
    "qpick", "qact",                                                 -- 5-6   Quests
    "ipick", "iact", "idlg",                                         -- 7-9   the NPC dialogs
    "pause", "gamemenu",                                             -- 10-11 the pause wheel; the Game Menu
    "party", "pfocus",                                               -- 12-13 Party mode; the unit menu
    "bag0", "bag1", "bag2", "bag3", "bag4", "bag5", "binv",          -- 14-20 Bags: the items, the worn gear
    "pnav", "uwp",                                                   -- 21-22 the routes
    "chq", "chr", "chs", "chcmd",                                    -- 23-26 Chat's keyboard
    "ibuy", "isell", "iqty", "itrain",                               -- 27-30 vendors, trainers
    "castbar", "radial", "classr", "tcast",                          -- 31-34 the cast bar, the radials, touch cast
    "mapw",                                                          -- 35    the Map room
    "bagsort", "bmvq",                                               -- 36-37 Bags' sort / move queue
    "extra", "radialpin",                                            -- 38-39 the Controller room's Extra tab; the radials' pins
    "talents", "talentn",                                            -- 40-41 Talents
    "aurapick", "auracancel",                                        -- 42-43 the Character room's auras
    "spick", "sact",                                                 -- 44-45 Social
    "cal",                                                           -- 46    Calendar (Macros and the layout editor are its guests)
    "prof", "profsel", "profopen",                                   -- 47-49 Professions
    "gf", "gfl", "gfw", "gfclose",                                   -- 50-53 the Group Finder
    "keytest",                                                       -- 54    the key channel's sweep / handshake (no keys: clicks)
    "bmv",                                                           -- 55    Bags' drag row (pad19: the first to go)
}
local SLOT = {}
for i, n in ipairs(ORDER) do SLOT[n] = i - 1 end
local POPUP_TOP_UNITS = 135

local pad = { ORDER = ORDER, SLOT = SLOT, rows = {}, byRow = {}, nrows = 0, ns = {}, geom = nil, info = nil, visible = false,
              listeners = {}, secureAt = nil, securePending = false }
WC.pad = pad

local function scaleInfo()
    local okP, pw, ph = CALL(function() return GetPhysicalScreenSize() end)
    local uw = SN(function() return UIParent:GetWidth() end)
    local uh = SN(function() return UIParent:GetHeight() end)
    if not okP or type(pw) ~= "number" or type(ph) ~= "number" or not uw or not uh
       or uw <= 0 or uh <= 0 then
        return nil
    end
    return { sx = pw / uw, sy = ph / uh, pw = pw, ph = ph }
end

-- identity of the current geometry, to tell whether secure cells still sit where describe() says
local function geomKey()
    local g, i = pad.geom, pad.info
    if not g or not i then return nil end
    return string.format("%d|%d|%d|%d|%.6f|%.6f", g.x, g.y, g.pw, g.ph, i.sx, i.sy)
end

-- ---------------------------------------------------------------- slots

function pad.slotFor(ns, key)
    if key == nil then return nil end
    local t = pad.ns[ns]
    if not t then t = { byKey = {}, byCol = {}, freedAt = {} }; pad.ns[ns] = t end
    local c = t.byKey[key]
    if c then return c end
    local now = GetTime()
    for col = 0, COLS - 1 do
        if t.byCol[col] == nil and (not t.freedAt[col] or now - t.freedAt[col] > QUARANTINE) then
            t.byKey[key] = col
            t.byCol[col] = key
            t.freedAt[col] = nil
            return col
        end
    end
    return nil          -- every column busy or quarantined; the app shows the actions as unavailable
end

-- Free the columns of keys that are no longer present. present = { [key] = true, ... }
function pad.retain(ns, present)
    local t = pad.ns[ns]
    if not t then return end
    local now = GetTime()
    for key, col in pairs(t.byKey) do
        if not present[key] then
            t.byKey[key] = nil
            t.byCol[col] = nil
            t.freedAt[col] = now
        end
    end
end

-- ---------------------------------------------------------------- cells

local function paint(btn)
    if not btn.tex then
        btn.tex = btn:CreateTexture(nil, "OVERLAY")
        btn.tex:SetAllPoints()
    end
    if pad.visible then btn.tex:SetColorTexture(1, 0.82, 0, 0.35) else btn.tex:SetColorTexture(0, 0, 0, 0) end
end

-- a brief flash is the only thing a player ever sees of the pad: proof a tap landed
local function flash(btn)
    if not btn.tex then return end
    btn.tex:SetColorTexture(0.3, 1, 0.3, 0.7)
    C_Timer.After(0.35, function() paint(btn) end)
end

local function place(btn, row, col)
    local g, i = pad.geom, pad.info
    if not g or not i then return end
    btn:SetSize(CELL_W / i.sx, CELL_H / i.sy)
    btn:ClearAllPoints()
    btn:SetPoint("TOPLEFT", UIParent, "TOPLEFT", (g.x + col * CELL_W) / i.sx, -(g.y + row * CELL_H) / i.sy)
    paint(btn)
end

local function fire(row, col)
    local r = pad.byRow[row]
    if not r or type(r.handler) ~= "function" then return end
    local key = nil
    if r.ns then
        local t = pad.ns[r.ns]
        key = t and t.byCol[col] or nil
    end
    -- the handler owns the reply; an unknown column arrives as key == nil and is refused there
    pcall(r.handler, key, col)
end

-- a clock that moves within a frame (the two presses of one tap arrive in the same frame, so GetTime() alone would do too)
local clock = type(GetTimePreciseSec) == "function" and GetTimePreciseSec or GetTime

-- pad8: whether row `row` ends above the game's pop-up dialogs at the current layout (see ROWS PAST 37)
local function fits(row)
    local i = pad.info
    if not i then return false end
    return TOP_GAP + (row + 1) * CELL_H <= POPUP_TOP_UNITS * i.sy - POPUP_MARGIN
end
pad.fits = fits

-- a press of a plain cell, by a click or by the key channel: once per tap (REPEAT), a flash, the row's handler
-- pad23: the last presses, "row:col" (the row's name), newest last; read by Hub's document (rx) so the app can log where its taps landed
-- pad24: where the game sees the cursor now, in the pad's own game px (top-left, 0.1 px), or nil (read only; GetCursorPosition is in
-- screen units, UIParent's effective scale turns it into UI units, the layout's sx / sy into game px)
local function cursorPx()
    local i = pad.info
    if not i then return nil end
    local ok, x, y = pcall(GetCursorPosition)
    local okS, es = pcall(function() return UIParent:GetEffectiveScale() end)
    local function n(v) return type(v) == "number" and (type(WC.isPlain) ~= "function" or WC.isPlain(v)) and v == v and v or nil end
    x, y, es = ok and n(x) or nil, ok and n(y) or nil, okS and n(es) or nil
    if not x or not y or not es or es <= 0 then return nil end
    return math.floor(x / es * i.sx * 10 + 0.5) / 10, math.floor((i.ph - y / es * i.sy) * 10 + 0.5) / 10
end
pad.cursorPx = cursorPx

pad.rx = { n = 0, last = {}, at = {} }
local function landed(row, col)
    local r = pad.byRow[row]
    local L = pad.rx
    L.n = L.n + 1
    L.last[#L.last + 1] = tostring(r and r.name or row) .. ":" .. tostring(col)
    local x, y = cursorPx()                                       -- pad24: where the game saw this press ("-" = not readable)
    L.at[#L.at + 1] = x and (x .. "," .. y) or "-"
    if #L.last > 8 then table.remove(L.last, 1) end
    if #L.at > 8 then table.remove(L.at, 1) end
end
pad.landed = landed
function pad.rxDoc()
    local L = pad.rx
    if L.n == 0 then return nil end
    return { n = L.n, last = table.concat(L.last, " "), at = table.concat(L.at, " ") }
end

-- pad24 THE CALIBRATION STRIP (see THE STREAM'S TAPS MEASURED in the version note): a press on it only keeps where the game saw it
pad.cal = { n = 0, p = {}, btn = nil }
local function calPress()
    local c = pad.cal
    pad.pressT = clock()                                             -- pad25: our press: the cursor stays blank
    local x, y = cursorPx()
    if not x then return end
    local t = clock()
    -- the Thor's one tap arrives as two presses ms apart: the same spot within 0.1 s is the same press
    if c.lastAt and t - c.lastAt < 0.1 and c.lastY and math.abs(y - c.lastY) < 0.5 then return end
    c.lastAt, c.lastY = t, y
    c.n = c.n + 1
    c.p[#c.p + 1] = x .. "," .. y
    if #c.p > 4 then table.remove(c.p, 1) end
    -- the answer goes out at once: the feeds re-publish the grid (describe()'s `calib` changed), as on a new layout
    for _, fn in ipairs(pad.listeners) do pcall(fn) end
end
pad.calPress = calPress
-- the strip's box in game px: CAL_GAP left of the grid, from the first row's top to the bottom of the last row that fits this layout
local function calBox()
    local g = pad.geom
    if not g then return nil end
    local n = 0
    while n < ROWS_MAX and fits(n) do n = n + 1 end
    if n == 0 then return nil end
    return { x = g.x - CAL_GAP - CAL_W, y = g.y, w = CAL_W, h = n * CELL_H }
end
pad.calBox = calBox
local function placeCal()
    local i, b = pad.info, calBox()
    local c = pad.cal
    if not c.btn then
        if not b then return end
        c.btn = CreateFrame("Button", nil, UIParent)
        c.btn:SetFrameStrata("TOOLTIP")
        c.btn:EnableMouse(true)
        c.btn:RegisterForClicks("AnyDown")                 -- the press, as the plain cells
        c.btn:SetScript("OnClick", function() pcall(calPress) end)
    end
    if not b or not i then c.btn:Hide() return end
    c.btn:SetSize(b.w / i.sx, b.h / i.sy)
    c.btn:ClearAllPoints()
    c.btn:SetPoint("TOPLEFT", UIParent, "TOPLEFT", b.x / i.sx, -b.y / i.sy)
    c.btn:Show()
end

local function pressCell(btn, row, col)
    local t = clock()
    pad.pressT = t                                                   -- pad25: our press (also its second): the cursor stays blank
    if btn.pressedAt and t - btn.pressedAt < REPEAT then return false end   -- the tap's second press
    btn.pressedAt = t
    flash(btn)
    pcall(landed, row, col)
    fire(row, col)
    return true
end

local function makeRow(r)
    r.cells = r.cells or {}
    local ok = fits(r.row)
    for col = 0, r.n - 1 do
        local btn = r.cells[col]
        if not btn then
            btn = CreateFrame("Button", nil, UIParent)
            btn:SetFrameStrata("TOOLTIP")
            btn:EnableMouse(true)
            btn:RegisterForClicks("AnyDown")     -- the press, not the release: see "A PLAIN CELL FIRES ON THE PRESS"
            local row, c = r.row, col
            btn:SetScript("OnClick", function(self) pressCell(self, row, c) end)
            r.cells[col] = btn
        end
        place(btn, r.row, col)
        if ok then btn:Show() else btn:Hide() end      -- pad8: never inside a dialog
    end
end

-- pad19 (the user, 2026-11-13: "Bags share one row"): a row may be PINNED to the last row (ROWS_MAX - 1) instead of the next one, so
-- every row registered after it still takes the lowest free row and the pinned one is the row that drops first when a smaller UI scale
-- places fewer rows (Bags' shared drag row "bmv": at the smallest scale the drag row goes, never the rooms' or the radials' rows).
-- Rows keep their places as before; pad.nrows stays one past the highest row in use (the readers loop 0 .. nrows - 1, a gap is nil).
-- pad26: a ranked name takes its own row (ORDER); any other name the lowest free row (opts.last: ROWS_MAX - 1 first, as pad19)
local function newRow(name, last)
    local idx = SLOT[name]
    if idx == nil or idx >= ROWS_MAX or pad.byRow[idx] then
        idx = nil
        if last and not pad.byRow[ROWS_MAX - 1] then
            idx = ROWS_MAX - 1
        else
            -- first a free row no ranked name owns, then the lowest free row (the old rule: the next one)
            for i = 0, ROWS_MAX - 1 do
                if not pad.byRow[i] and ORDER[i + 1] == nil then idx = i break end
            end
            if idx == nil then
                for i = 0, ROWS_MAX - 1 do
                    if not pad.byRow[i] then idx = i break end
                end
            end
        end
        if idx == nil then return nil end
    end
    local r = { name = name, row = idx, cells = {} }
    pad.nrows = math.max(pad.nrows, idx + 1)
    pad.rows[name] = r
    pad.byRow[r.row] = r
    return r
end

-- pad21 THE SHARED ROW (the 1.0.32 test build, code 1472: with six bags, the cast bar and both radials all 56 rows are taken at the
-- Thor's UI scale, so the Macros room's lazy row "mac" had none and the room said "No pad row"). Two rooms that are never on the bottom
-- screen together may hold ONE row: a secure row registered with opts.share = <a secure row's name> is that row's GUEST - the same row
-- index, but its OWN cells, attributes and onClick (nothing of the host's is touched, so the host's own code - the Calendar's
-- syncCells - runs on its own cells as before). One of them is on the row at a time, the HOST by default: the other's cells are
-- hidden and the other is left out of describe(), so the app finds that row's name missing and never taps it there. pad.lease(name)
-- puts `name` on its row (out of combat: secure cells are shown / hidden only then; in a fight it waits for the fight's end, the last
-- ask winning) and tells every feed (onLayout) so each re-publishes its grid. Each room asks for its row when it comes on the screen,
-- by a tap on ANOTHER row (Book's padtab pick: 39 then 34 = the Macros room's, 39 then 35 = the Calendar's), so no tap is ever under
-- way on the row while its cells change. pad.nrows, pad.byRow (the host) and every other row are unchanged.
pad.tenant = {}          -- row index -> the GUEST on that shared row now (nil = its host)
pad.leaseWait = {}       -- row index -> a lease asked in a fight: the guest's name, or false = the host
local function onRow(r)
    local t = pad.tenant[r.row]
    if r.guestOf then return t == r.name end
    return t == nil
end
function pad.holds(name)
    local r = pad.rows[name]
    return type(r) == "table" and onRow(r)
end

-- Register a plain row. ns = the slot namespace its columns refer to, or nil for a single-cell
-- operation (column 0, no key). opts.last (pad19): pinned to the last row (see newRow).
function pad.op(name, ns, handler, opts)
    local r = pad.rows[name]
    if r then r.handler = handler return r.row end
    r = newRow(name, type(opts) == "table" and opts.last == true)
    if not r then return nil end
    r.ns, r.handler, r.n = ns, handler, ns and COLS or 1
    if pad.geom then makeRow(r) end
    if pad.keysSoon then pad.keysSoon() end          -- pad10: a new plain row may get a row key
    return r.row
end

-- ---------------------------------------------------------------- secure rows

-- whether a secure cell has a secure action for this click: the template reads "type" with its modifier / button variants
local function hasAction(btn, button)
    local get = _G.SecureButton_GetModifiedAttribute
    if type(get) == "function" then
        local ok, v = pcall(get, btn, "type", button)
        if ok then return v ~= nil end
    end
    return btn:GetAttribute("type") ~= nil
end

local function secureCell(r, col)
    local btn = r.cells[col]
    if btn then return btn end
    btn = CreateFrame("Button", nil, UIParent, "SecureActionButtonTemplate")
    btn:SetFrameStrata("TOOLTIP")
    -- MEASURED on this client (bags probe): a secure button registered for both fires its action
    -- exactly once per press; the template picks the phase from ActionButtonUseKeyDown.
    btn:RegisterForClicks("AnyDown", "AnyUp")
    local row, c = r, col
    -- A POST-hook, never SetScript: replacing OnClick would replace the secure action itself.
    btn:HookScript("OnClick", function(self, button, down)
        local early = row.onPress and not hasAction(self, button)       -- pad6b: no secure action -> the press, as a plain cell
        pad.pressT = clock()                                            -- pad25: our press (down and up): the cursor stays blank
        if down then
            flash(self)
            if not early then return end
            local t = clock()
            if self.pressedAt and t - self.pressedAt < REPEAT then return end   -- the tap's second press
            self.pressedAt = t
        elseif early then
            return                                                      -- it ran on the press
        end
        pcall(landed, row.row, c)                                         -- pad23: once per tap, as the plain cells
        if type(row.onClick) == "function" then pcall(row.onClick, c) end   -- once per tap
    end)
    r.cells[col] = btn
    return btn
end

-- Build or re-place every secure row. Out of combat only; in combat it is queued.
function pad.buildSecure()
    if not pad.geom then return false end
    if InCombatLockdown() then pad.securePending = true return false end
    -- pad21: a lease asked in a fight, now (the last ask won)
    for idx, want in pairs(pad.leaseWait) do pad.tenant[idx] = want or nil; pad.leaseWait[idx] = nil end
    for _, r in pairs(pad.rows) do
        if r.secure then
            local ok = fits(r.row) and onRow(r)            -- pad21: a shared row's other tenant stays hidden
            for col = 0, r.n - 1 do
                local btn = secureCell(r, col)
                if not r.built and type(r.init) == "function" then pcall(r.init, btn, col) end
                btn:EnableMouse(true)
                place(btn, r.row, col)
                if ok then btn:Show() else btn:Hide() end  -- pad8: never inside a dialog
            end
            -- a row that shrank (a smaller bag): nothing may sit under a stale tap
            for col, btn in pairs(r.cells) do
                if col >= r.n then btn:Hide() end
            end
            r.built = true
        end
    end
    pad.secureAt = geomKey()
    pad.securePending = false
    return true
end

-- Register or rebuild a secure row: ncols cells, attributes set once by init(button, col).
-- Calling it again (a bag changed size) re-runs init on every cell, out of combat.
-- onClick(col), optional, runs after the secure action, once per tap.
-- opts.onPress (pad6b): cells with no secure action ("type" nil) run onClick on the press instead of the release.
-- opts.share (pad21): the name of a secure row to be the GUEST of (THE SHARED ROW, above): its row, its own cells, off the row until
-- pad.lease(name); with no such host (its add-on off, or not registered), a row of its own as before.
function pad.secureRow(name, ncols, init, onClick, opts)
    local r = pad.rows[name]
    if r and not r.secure then return nil end        -- the name is taken by a plain row
    if not r then
        local host = type(opts) == "table" and type(opts.share) == "string" and pad.rows[opts.share] or nil
        if type(host) == "table" and host.secure and not host.guestOf and host.name ~= name then
            r = { name = name, row = host.row, cells = {}, guestOf = host.name }
            pad.rows[name] = r
        else
            r = newRow(name)
            if not r then return nil end
        end
        r.secure = true
    end
    r.n = math.max(0, math.min(ncols or 0, COLS))
    r.init, r.onClick, r.built = init, onClick, false
    r.onPress = type(opts) == "table" and opts.onPress == true
    pad.buildSecure()
    return r.row
end

-- pad21: put [name] on its shared row (a guest, or its host back). true = it is on the row now; false, "combat" = it waits for the
-- fight's end (the cells are secure); false, "no row" = no such secure row. A row that is not shared: always on its row.
function pad.lease(name)
    local r = pad.rows[name]
    if type(r) ~= "table" or not r.secure then return false, "no row" end
    local want = r.guestOf and r.name or nil
    if pad.tenant[r.row] == want then pad.leaseWait[r.row] = nil return true end
    if InCombatLockdown() then pad.leaseWait[r.row] = want or false; pad.securePending = true return false, "combat" end
    pad.tenant[r.row] = want
    pad.leaseWait[r.row] = nil
    pad.buildSecure()
    for _, fn in ipairs(pad.listeners) do pcall(fn) end
    return true
end

-- ---------------------------------------------------------------- THE KEY CHANNEL (L1, pad10c)
-- The user's pick 2026-10-09 ("L1 key channel", via Home): an action goes out at the controller PRESS, not its release. WHY (Home's
-- lat.log): press -> tap reaching the game p50 196 ms / p90 358 ms, nearly all of it the app waiting for the face button's RELEASE,
-- because a click while a face button is held strays to the freelook park point (TapQuiet). A key binding never meets the cursor.
-- So, INSIDE THE DS FOREVER LAUNCHER (its X server carries the modifiers; adb's keys lost them, Bags 2026-09-27), a plain cell can
-- also be pressed by TWO KEYS, both on CTRL-SHIFT:
--   a ROW key (KEY_ROWS), the cell's row -- a NO-ACTION pick (the user's lock, amended 2026-09-28: a no-action pick may come before
--     the action), one-shot, gone after KEY_PICK_TTL;
--   then a COLUMN key (KEY_COLS: A..Z, 0..9, MINUS, EQUALS, [, ] = the 40 columns), which presses that cell exactly as a click does
--     (pressCell: the same REPEAT guard, the same handler and reply). A column key with no pending row is REFUSED (reported as a
--     "padkey" document), never a guess: a lost row key can never press a wrong cell; a lost column key lets the pick expire.
-- NO ALT, NO F-KEY, NO SYSTEM KEY, EVER (pad10c). pad10 held Ctrl+Alt+Shift, and its row key for Interact's row was F4: Ctrl+Alt+Shift+F4
-- CONTAINS ALT+F4, which Wine turns into the window's close -- the game closed seconds after every world entry (2026-10-09 13:50,
-- 14:03, 14:04; the user's probe). A real Alt makes Wine handle any key as a SYSTEM key (WM_SYSKEYDOWN: Alt+F4 close, Alt+Space the
-- system menu, Alt+Tab / Esc / Enter), and F10 / Shift+F10 are system keys without it. So: the chords are CTRL-SHIFT only -- the
-- gamepad's own emulated modifiers (LB = Ctrl, LT = Shift; Home's read of the game log), so a held LB / LT never changes the chord --
-- and keySafe() refuses any other chord before it is bound or published, whatever the alphabets grow to (tested against an
-- independent list of system chords). An emulated ALT (GamePadEmulateAlt set to a button) only exists inside the game, never as a
-- key we send: for it each key is ALSO bound as ALT-CTRL-SHIFT-<key> to the same dispatcher, so a held emulated Alt cannot lose it.
-- ONE plain dispatcher button holds every binding, the key's own name riding along as the click's "button" (Interact's padSink trick).
-- Bindings are set once, OUT of combat, on our own owner frame, never taking a key the player or another add-on already uses
-- (GetBindingAction); describe() publishes which rows have keys and the column keys, so the app never guesses, and the app keeps
-- clicking for everything else (and while a text box has keyboard focus: bindings do not fire there). Only the hot rows (Home's
-- lat.log order) and this file's own "keytest" row are keyed, each by NAME. Secure rows are not keyed (phase 2).
-- THE SWEEP (pad10c; before keys go live, on the user's word): a CLICK on cell 0 of the plain row "keytest" (its handler does nothing
-- in the game) turns a trace on for KEY_TRACE_S; while it is on, every key the dispatcher hears is reported ("padkeytest": k, the row
-- or column it means, or why it was refused). The app then sends each row key ALONE (a pick that just expires) and keytest's row key
-- with each column key (keytest's cells: no action), so every key is checked once with no game action, and a lost key -- or a key
-- with a side effect -- shows on its own.
-- A KEY MUST NEVER BE SENT UNBOUND (pad10c): when a chord with modifiers has no binding, WoW falls back to the key's PLAIN binding
-- (that is how Shift+1 fires action button 1 for a [mod:shift] macro), so a CTRL-SHIFT-1 nobody bound is the player's "1": a spell. A
-- pad document the app kept from an earlier session (it replays the last one at its start) or from an add-on since rolled back must
-- never make the app send keys. So the keys carry this session's token (describe().keys.s), and the app sends keys only after a
-- HANDSHAKE in this session: its CLICK on keytest's cell 0 (no game action) is answered "padkeytest" { on, s, bound, ... }, and only a
-- live answer whose token matches the document's lets keys go.
local KEY_MOD_NAMES = "CTRL-SHIFT"           -- the game's order for a chord without Alt
local KEY_MODS = KEY_MOD_NAMES .. "-"
local KEY_ROWS = { "INSERT", "HOME", "END", "PAGEUP", "PAGEDOWN", "UP", "DOWN", "LEFT", "RIGHT" }
local KEY_HOT = { "qact", "qpick", "ipick", "iact", "chq", "pause", "spick", "padtab", "keytest" }   -- = KEY_ROWS, by name
local KEY_COLS = { "A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L", "M", "N", "O", "P", "Q", "R", "S", "T", "U", "V",
                   "W", "X", "Y", "Z", "0", "1", "2", "3", "4", "5", "6", "7", "8", "9", "MINUS", "EQUALS", "[", "]" }
-- keys that mean something to Windows, Wine, the launcher or the controller: never bound, never published
local KEY_NEVER = { ESCAPE = true, TAB = true, ENTER = true, SPACE = true, BACKSPACE = true, DELETE = true, PRINTSCREEN = true,
                    SCROLLLOCK = true, PAUSE = true, NUMLOCK = true, CAPSLOCK = true, LWIN = true, RWIN = true, APPS = true,
                    MENU = true, LALT = true, RALT = true, LCTRL = true, RCTRL = true, LSHIFT = true, RSHIFT = true }
local KEY_PICK_TTL = 0.5                     -- seconds a row key waits for its column key
local KEY_TRACE_S = 30                       -- seconds the sweep's trace stays on
local KEY_BTN = "WowCompanionPadKeys"
local keys = { rowOf = {}, keyOf = {}, colOf = {}, colKey = {}, usedRow = {}, pending = nil, bound = false, wantBind = false,
               queued = false, given = 0, n = 0, bad = 0, badAt = -1, traceUntil = -1, session = nil }
pad.keys = keys                              -- for the harness

-- the chord we SEND (mods + key) means nothing to the system: no Alt / Meta, no F-key, no system or controller key
local function keySafe(mods, k)
    if type(mods) ~= "string" or type(k) ~= "string" or k == "" then return false end
    if mods:find("ALT", 1, true) or mods:find("META", 1, true) then return false end
    if KEY_NEVER[k] or k:match("^F%d+$") or k:match("^NUMPAD") or k:match("^PAD") or k:match("BUTTON") then return false end
    return true
end
pad.keySafe = keySafe                        -- for the harness

local function tracing() return GetTime() <= keys.traceUntil end
local function trace(doc)
    doc.type, doc.v = "padkeytest", PADV
    pcall(function() WC.send(WC.encode(doc)); if type(WC.pushThrottled) == "function" then WC.pushThrottled() else WC.push() end end)
end

local function keyBad(why, what)
    keys.bad = keys.bad + 1
    if tracing() then trace({ k = what, err = why }) end
    local t = GetTime()
    if t - keys.badAt < 1 then return end    -- rate-capped, as padstray
    keys.badAt = t
    pcall(WC.emit, { type = "padkey", err = why, k = what, bad = keys.bad, n = keys.n, v = PADV })
end

local function onKey(_, button, down)
    if down == false then return end
    local k = type(button) == "string" and button or nil
    local t = clock()
    local rname = k and keys.rowOf[k]
    if rname then
        keys.pending = { name = rname, at = t }
        if tracing() then trace({ k = k, row = rname }) end
        return
    end
    local col = k and keys.colOf[k]
    local p = keys.pending
    keys.pending = nil
    if col == nil then return keyBad("unknown key", k) end
    if not p or t - p.at > KEY_PICK_TTL then return keyBad("no row", k) end
    local r = pad.rows[p.name]
    local btn = r and not r.secure and r.cells and r.cells[col]
    if not btn then return keyBad("no cell", p.name .. ":" .. col) end
    keys.n = keys.n + 1
    if tracing() then trace({ k = k, row = p.name, col = col }) end
    pressCell(btn, r.row, col)
end

local function bindingOf(chord)
    local ok, a = pcall(GetBindingAction, chord, true)
    if not ok then return nil end
    return a or ""
end
local function ours(k) return "CLICK " .. KEY_BTN .. ":" .. k end
-- an emulated Alt exists only inside the game (a gamepad button set as GamePadEmulateAlt)
local function emuAlt()
    local ok, v = pcall(GetCVar, "GamePadEmulateAlt")
    return ok and type(v) == "string" and v ~= "" and v ~= "none"
end
-- the key is free for us: safe to send, and nothing bound to its chord, or it is already ours
local function keyFree(k)
    if not keySafe(KEY_MOD_NAMES, k) then return false end
    local a = bindingOf(KEY_MODS .. k)
    return a == "" or a == ours(k)
end
local function keyBind(k)
    if not pcall(SetOverrideBindingClick, keys.owner, true, KEY_MODS .. k, KEY_BTN, k) then return false end
    if bindingOf(KEY_MODS .. k) ~= ours(k) then return false end
    -- the emulated Alt's mirror: a binding only (we never send Alt), and only on a free chord
    if emuAlt() then
        local alt = "ALT-" .. KEY_MODS .. k
        local a = bindingOf(alt)
        if a == "" or a == ours(k) then pcall(SetOverrideBindingClick, keys.owner, true, alt, KEY_BTN, k) end
    end
    return true
end
local function giveRow(name, k)
    if keys.keyOf[name] or keys.usedRow[k] then return end
    if keyFree(k) and keyBind(k) then keys.keyOf[name], keys.rowOf[k], keys.usedRow[k] = k, name, true; keys.given = keys.given + 1 end
end

-- bind what is not bound yet: the 40 column keys once, then each keyed row's own key (out of combat only)
function pad.bindKeys()
    if type(SetOverrideBindingClick) ~= "function" or type(GetBindingAction) ~= "function" then return false end
    if InCombatLockdown() then keys.wantBind = true; return false end
    keys.wantBind = false
    if not keys.btn then
        local ok = pcall(function()
            keys.btn = CreateFrame("Button", KEY_BTN, UIParent)
            keys.btn:RegisterForClicks("AnyDown")
            keys.btn:SetScript("OnClick", onKey)
            keys.owner = CreateFrame("Frame", "WowCompanionPadKeyOwner", UIParent)
        end)
        if not ok or not keys.btn or not keys.owner then keys.btn = nil; return false end
    end
    if not keys.colsDone then
        for col = 0, COLS - 1 do
            local k = KEY_COLS[col + 1]
            if keyFree(k) and keyBind(k) then keys.colOf[k], keys.colKey[col] = col, k end
        end
        keys.colsDone = true
    end
    -- the keyed rows: their own key, by name (a row that registers later still finds it)
    for i, name in ipairs(KEY_HOT) do
        local r = pad.rows[name]
        if r and not r.secure and KEY_ROWS[i] then giveRow(name, KEY_ROWS[i]) end
    end
    keys.bound = next(keys.keyOf) ~= nil and next(keys.colKey) ~= nil
    if keys.bound and not keys.session then
        -- this session's token: (server seconds mod 10^6) x 1000 + a random part, unique across sessions (as the pins' set, q23)
        local okT, t = pcall(GetServerTime)
        if not (okT and type(t) == "number") then okT, t = pcall(time) end
        t = (okT and type(t) == "number") and t or 0
        keys.session = tostring((math.floor(t) % 1000000) * 1000 + math.random(0, 999))
    end
    return keys.bound
end
-- a bind pass at the end of this frame (rows register in bursts at load)
function pad.keysSoon()
    if keys.queued then return end
    keys.queued = true
    C_Timer.After(0, function()
        keys.queued = false
        local before = keys.given
        pcall(pad.bindKeys)
        if keys.given ~= before then for _, fn in ipairs(pad.listeners) do pcall(fn) end end   -- a new row key: re-publish
    end)
end

-- the sweep's own row: cell 0 (a CLICK) turns the trace on; every cell is a no-action target for the column keys
local function keytestRow()
    if pad.rows.keytest then return end
    pad.op("keytest", "keytest", function(_, col)
        if col == 0 then
            keys.traceUntil = GetTime() + KEY_TRACE_S
            local nr, nc = 0, 0
            for _ in pairs(keys.keyOf) do nr = nr + 1 end
            for _ in pairs(keys.colKey) do nc = nc + 1 end
            -- the handshake's answer: this session's token, whether keys are bound, how many (the app sends keys only on a match)
            trace({ on = true, s = keys.session, bound = keys.bound, mods = KEY_MOD_NAMES, rows = nr, cols = nc, secs = KEY_TRACE_S })
        end
    end)
end

-- NOTHING OF THIS STAYS IN THE PLAYER'S SETUP (the user's ruling 2026-10-09: the controller layer is "an accessory to the addon" and
-- must "not affect peoples normal set up on their charactors" -- the PC showed what the companion had left behind). Every key here is an
-- OVERRIDE binding on our own owner frame: never SetBinding / SaveBindings, so nothing reaches the saved, server-synced binding set the
-- PC also loads, and never a key the player has bound (keyFree). The client drops overrides itself when the UI unloads; at
-- PLAYER_LOGOUT ours are cleared anyway.
function pad.clearKeys()
    if not keys.owner or InCombatLockdown() then return false end
    if not pcall(ClearOverrideBindings, keys.owner) then return false end
    keys.rowOf, keys.keyOf, keys.colOf, keys.colKey, keys.usedRow = {}, {}, {}, {}, {}
    keys.colsDone, keys.bound, keys.pending, keys.session = false, false, nil, nil
    return true
end

local function keysDescribe()
    if not keys.bound then return nil end
    local rows = {}
    for name, k in pairs(keys.keyOf) do
        local r = pad.rows[name]
        if r and fits(r.row) and keySafe(KEY_MOD_NAMES, k) then rows[name] = k end
    end
    local cols = {}
    for col = 0, COLS - 1 do
        local k = keys.colKey[col]
        cols[col + 1] = (k and keySafe(KEY_MOD_NAMES, k)) and k or ""
    end
    return { mods = KEY_MOD_NAMES, ttl = KEY_PICK_TTL, rows = rows, cols = cols, s = keys.session }
end

-- ---------------------------------------------------------------- layout

-- Centred on the top edge of the game screen: 3D world on every screen seen so far (the controller
-- hint panel is top-left, the minimap top-right). Invisible, so all it costs is mouse clicks in
-- that strip, and on a handheld nobody clicks the top screen.
-- the stray-press sink (see "A STRAY PRESS LANDS ON THE FREELOOK PARK POINT")
local sink
pad.strays = 0
local strayReportedAt = -1

-- the sink's strata follows the capture's holder (see "WHILE ANYONE HOLDS THE CONTROLLER IT GOES ON TOP")
local function captureHeld()
    local c = WC.capture                                 -- set by WowCompanionInteract when it loads
    if type(c) ~= "table" or type(c.state) ~= "function" then return false end
    local ok, st = pcall(c.state)
    return ok and type(st) == "table" and st.holder ~= nil
end
function pad.sinkSync()
    if not sink then return end
    local want = captureHeld() and "TOOLTIP" or "BACKGROUND"
    if pad.sinkStrata ~= want then
        pad.sinkStrata = want
        sink:SetFrameStrata(want)
    end
end

local function placeSink()
    local g, i = pad.geom, pad.info
    if not g or not i then return end
    if not sink then
        sink = CreateFrame("Button", nil, UIParent)
        pad.sink = sink                                  -- for the harness
        sink:SetFrameStrata("BACKGROUND")
        pad.sinkStrata = "BACKGROUND"
        pcall(sink.SetMouseClickEnabled, sink, true)
        pcall(sink.SetMouseMotionEnabled, sink, false)
        sink:RegisterForClicks("AnyDown", "AnyUp")
        sink:SetScript("OnClick", function(_, _, down)
            if down == false then return end                -- one count per press
            pad.strays = pad.strays + 1
            local t = GetTime()
            if t - strayReportedAt < 1 then return end       -- rate-capped: never a flood (the 09-28 lesson)
            strayReportedAt = t
            pcall(WC.emit, { type = "padstray", n = pad.strays, v = PADV })
        end)
        if C_Timer and type(C_Timer.NewTicker) == "function" then
            C_Timer.NewTicker(SINK_POLL, function() pcall(pad.sinkSync) end)
        end
    end
    sink:SetSize(SINK / i.sx, SINK / i.sy)
    sink:ClearAllPoints()
    sink:SetPoint("CENTER", UIParent, "TOPLEFT", g.pw * PARK_X / i.sx, -(g.ph * PARK_Y) / i.sy)
    sink:Show()
    pad.sinkSync()
end

function pad.layout()
    local i = scaleInfo()
    if not i then return false end
    local w = COLS * CELL_W
    local x0 = math.floor((i.pw - w) / 2)
    local changed = (not pad.geom) or pad.geom.pw ~= i.pw or pad.geom.ph ~= i.ph or pad.info == nil
                    or math.abs(pad.info.sx - i.sx) > 1e-6 or math.abs(pad.info.sy - i.sy) > 1e-6
    pad.info = i
    -- TOP_GAP keeps the cells off the panel's top edge, where Android treats touches specially;
    -- the probe's proven tap was 18 game px down, and the rows straddle that.
    pad.geom = { x = x0, y = TOP_GAP, pw = i.pw, ph = i.ph }
    for _, r in pairs(pad.rows) do if not r.secure then makeRow(r) end end
    pad.buildSecure()
    pcall(placeSink)           -- the sink is a safety net: it must never be able to break the grid
    pcall(placeCal)            -- pad24: the calibration strip, likewise
    if changed then for _, fn in ipairs(pad.listeners) do pcall(fn) end end
    return true
end

-- Called when the published grid changes, so a feed can re-publish it at once.
function pad.onLayout(fn) pad.listeners[#pad.listeners + 1] = fn end

function pad.describe()
    if not pad.geom and not pad.layout() then return nil end
    local rows, sizes = {}, {}
    local secureOK = pad.secureAt ~= nil and pad.secureAt == geomKey()
    for name, r in pairs(pad.rows) do
        if fits(r.row) and onRow(r) and (not r.secure or (r.built and secureOK)) then   -- pad21: only the tenant on a shared row
            rows[name] = r.row
            sizes[name] = r.n
        end
    end
    -- pad26: the registered rows this layout cannot place (a shared row once, by its host), name -> row; normally absent
    local drop
    for name, r in pairs(pad.rows) do
        if not r.guestOf and not fits(r.row) then drop = drop or {}; drop[name] = r.row end
    end
    local g = pad.geom
    -- pad23: a described row whose first cell does not stand where the grid says (its real top, game px from the screen's top, read
    -- off the frame): `off` lists it, so a tap that never lands can be told from a row that moved. Read only; normally absent
    local off
    local top = SN(function() return UIParent:GetTop() end)
    for name, idx in pairs(rows) do
        local r = pad.rows[name]
        local b = r and r.cells and r.cells[0]
        local bt = b and SN(function() return b:GetTop() end)
        if top and bt then
            local real = (top - bt) * pad.info.sy
            if math.abs(real - (g.y + idx * CELL_H)) > 0.5 then
                off = off or {}
                off[name] = math.floor(real * 10 + 0.5) / 10
            end
        end
    end
    -- pad24: the calibration strip while it is up - its box, its press count and where the game saw its last presses ("x,y", newest last)
    local cal
    local cb, c = calBox(), pad.cal
    if cb and c.btn and c.btn:IsShown() then
        cal = { x = cb.x, y = cb.y, w = cb.w, h = cb.h, n = c.n, p = #c.p > 0 and table.concat(c.p, " ") or nil }
    end
    -- popupTop: where the game's dialogs start, in the same game pixels, so any reader can check the grid's bottom
    -- (y + rows * ch) stays above them at whatever UI scale the player picks
    return { v = PADV, x = g.x, y = g.y, cw = CELL_W, ch = CELL_H, cols = COLS,
             popupTop = math.floor(POPUP_TOP_UNITS * pad.info.sy * 10 + 0.5) / 10,
             pw = g.pw, ph = g.ph, rows = rows, sizes = sizes, keys = keysDescribe(), off = off, calib = cal, drop = drop }
end

local f = CreateFrame("Frame")
for _, e in ipairs({ "PLAYER_LOGIN", "PLAYER_ENTERING_WORLD", "UI_SCALE_CHANGED",
                     "DISPLAY_SIZE_CHANGED", "PLAYER_REGEN_ENABLED", "PLAYER_LOGOUT" }) do
    pcall(function() f:RegisterEvent(e) end)
end
f:SetScript("OnEvent", function(_, event)
    if event == "PLAYER_LOGOUT" then pcall(pad.clearKeys); return end   -- pad10: our key overrides go with us
    if event == "PLAYER_REGEN_ENABLED" then
        -- secure work queued during combat: do it now, then let feeds re-publish the grid
        if pad.securePending and pad.buildSecure() then
            for _, fn in ipairs(pad.listeners) do pcall(fn) end
        end
        -- pad10: key bindings asked for in a fight
        if keys.wantBind and pcall(pad.bindKeys) then for _, fn in ipairs(pad.listeners) do pcall(fn) end end
        return
    end
    pad.layout()
    pcall(keytestRow)                         -- pad10c: the sweep's own row, once the rooms' rows are in
    local had = keys.bound
    pcall(pad.bindKeys)                       -- pad10: out of combat at login / a layout change; a no-op once everything is bound
    if keys.bound and not had then for _, fn in ipairs(pad.listeners) do pcall(fn) end end
end)

-- NO SLASH COMMAND, ON PURPOSE. There was a `/wcpad show` that tinted every cell. Typed into the
-- game's chat in controller mode on 2026-09-28 it set off 4,012 ADDON_ACTION_FORBIDDEN on
-- SetPreferredGamepadInteractTarget (Blizzard's gamepad action bar) in 3 minutes and froze the game
-- until a restart. It had no other use, so it is gone (user ruling 11:35). Do not add a command
-- here; if the cells must be seen, set pad.visible from code under a test, never from chat.

-- ---------------------------------------------------------------- pad12 THE PARKED CURSOR (the user, 2026-11-04: "cursor is still
-- sitting in the middle being annoying as hell. can we like hide it"): every tap of the app is a mouse click, and after one the game
-- parks its cursor mid-screen in controller mode, where it stays. While the cursor RESTS (pad13: still for a second, anywhere),
-- over the 3D world (nothing under it but WorldFrame), with nothing held and no spell aiming, it is drawn as a blank picture
-- (SetCursor, Media/cursor_blank.tga, fully transparent); anywhere else, or anything held / aimed, it is the game's own again
-- (ResetCursor). A real mouse moved away gets its cursor back at once. Nothing saved, no setting changed. If this client refuses the
-- picture, nothing changes (every call guarded).
do
    local BLANK = "Interface\\AddOns\\WowCompanion\\Media\\cursor_blank"
    local f = CreateFrame("Frame")
    local hidden, acc = false, 0
    local function plainNum(v) return type(v) == "number" and (type(WC.isPlain) ~= "function" or WC.isPlain(v)) and v or nil end
    -- pad13 (the Thor's screenshots: the game parks it at x 50 %, y about 41 %, not the centre; the user: "make the cursor like 1 pixel
    -- while not using the track pad or touching the top screen"): hidden once it has NOT MOVED for a second, wherever it rests over
    -- the world; the first move (the track pad, a touch, a real mouse) shows it again
    local lastX, lastY, stillAt = nil, nil, 0
    local function parked()
        local x, y = GetCursorPosition()
        x, y = plainNum(x), plainNum(y)
        if not x or not y then return false end
        local now = GetTime()
        if not lastX or math.abs(x - lastX) > 1 or math.abs(y - lastY) > 1 then lastX, lastY, stillAt = x, y, now; return false end
        if now - stillAt < 1.0 then return false end
        if type(GetCursorInfo) == "function" and GetCursorInfo() ~= nil then return false end
        if type(SpellIsTargeting) == "function" and SpellIsTargeting() then return false end
        local focus = nil
        if type(GetMouseFoci) == "function" then local t = GetMouseFoci(); focus = type(t) == "table" and t[1] or nil
        elseif type(GetMouseFocus) == "function" then focus = GetMouseFocus() end
        return focus == nil or focus == WorldFrame
    end
    -- pad14 (the user's test14: the blank cursor "is showing a big black box": the HARDWARE cursor ignores its alpha; the user's pick
    -- "Yes, try software cursor"): WoW's own Hardware Cursor setting (gxCursor) is switched OFF ONCE (WowCompanionDB.cursorSoftSet,
    -- never again, so the player's later choice stays), and the cursor is hidden only while the game draws it itself (gxCursor "0"),
    -- where the picture's alpha works. What the game says is reported (cap probe "cursorsoft"; the app logs it as WCCURSOR).
    local function soft()
        local ok, v = pcall(function() return C_CVar.GetCVar("gxCursor") end)
        return ok and type(v) == "string" and (type(WC.isPlain) ~= "function" or WC.isPlain(v)) and v == "0"
    end
    local cv = CreateFrame("Frame")
    cv:RegisterEvent("PLAYER_ENTERING_WORLD")
    cv:SetScript("OnEvent", function()
        cv:UnregisterEvent("PLAYER_ENTERING_WORLD")
        pcall(function()
            if type(WowCompanionDB) ~= "table" then WowCompanionDB = {} end
            local before = C_CVar.GetCVar("gxCursor")
            local out = { type = "cap", probe = "cursorsoft", v = PADV, before = before }
            if not WowCompanionDB.cursorSoftSet and not InCombatLockdown() then
                WowCompanionDB.cursorSoftSet = true
                if before ~= nil and before ~= "0" then
                    out.set = pcall(C_CVar.SetCVar, "gxCursor", "0") and "0" or "refused"
                    out.after = C_CVar.GetCVar("gxCursor")
                end
            end
            WC.send(WC.encode(out))
        end)
    end)
    -- pad25 (the user, 2026-11-19, streaming: "the cursor keeps randomly showing on the game"): OUR OWN PRESSES never show it. A press
    -- of ours (pad.pressT: a cell, the calibration strip, the layout catcher) ARMS the blank cursor; a move within PRESS_WIN of a press
    -- (to the cell, the game's park after it) or back to the park spot learned (PARK_PX) is ours and keeps it blank wherever it rests;
    -- the first other move is a real mouse (the PC's user, the trackpad, a touch on the top screen): disarmed, pad.humanAt = then.
    -- While armed it is looked at every frame (the game resets it over a unit), otherwise as pad13 (every 0.1 s).
    local PRESS_WIN, PARK_PX, HUMAN_S = 1.5, 2, 0.15
    local armed, armedAt, parkX, parkY, mx, my, pending = false, nil, nil, nil, nil, nil, nil
    pad.humanAt = nil
    local function track()
        local x, y = GetCursorPosition()
        x, y = plainNum(x), plainNum(y)
        if not x or not y then return end
        local c = clock()                                              -- pad.pressT's clock
        local pt = type(pad.pressT) == "number" and pad.pressT or nil
        if pt and pt ~= armedAt then armedAt = pt; armed = true end
        if mx and (math.abs(x - mx) > 1 or math.abs(y - my) > 1) then
            if pt and c - pt <= PRESS_WIN then
                parkX, parkY, pending = x, y, nil                          -- the press's own moves: the last is where the game parks it
            elseif not (parkX and math.abs(x - parkX) <= PARK_PX and math.abs(y - parkY) <= PARK_PX) then
                pending = pending or c                                     -- nothing of ours explains it (yet)
            end
        end
        mx, my = x, y
        -- a posted press's move may reach the game a frame before the press itself: a move is a real mouse only when no press of
        -- ours follows it within HUMAN_S
        if pending then
            if pt and pt >= pending then pending = nil
            elseif c - pending > HUMAN_S then
                pending = nil
                armed = false
                pad.humanAt = GetTime()                                    -- Dialogs d35 compares it with its own GetTime()
            end
        end
    end
    local function busy()
        if type(GetCursorInfo) == "function" and GetCursorInfo() ~= nil then return true end
        return type(SpellIsTargeting) == "function" and SpellIsTargeting() == true
    end
    pad.cursorArmed = function() return armed end
    local function look(_, el)
        acc = acc + el
        if not armed and acc < 0.1 and pad.pressT == armedAt then return end   -- a new press of ours is looked at in its own frame
        acc = 0
        pcall(track)
        local p
        if armed then
            local okB, b = pcall(busy)
            p = not (okB and b) and soft()
        else
            local ok, q = pcall(parked)
            p = ok and q == true and soft()
            if p then local x, y = GetCursorPosition(); parkX, parkY = plainNum(x), plainNum(y) end   -- pad13's rest: the park spot
        end
        if p then
            pcall(SetCursor, BLANK)                                    -- again each look: the game resets it over a unit or an object
            hidden = true
        elseif hidden then
            pcall(ResetCursor)
            hidden = false
        end
    end
    f:SetScript("OnUpdate", look)
    pad.cursorLook = look                                              -- for the harness (tools/test_stream_cursor.py)
end

-- ---------------------------------------------------------------- pad15 OUR ADD-ONS SWITCHED BACK ON (the Thor's log, 2026-11-04: on a
-- character where the game's "WowCompanionSpells has been blocked ..." pop-up was answered with Disable, Spells never loaded again - no
-- Smart Y, no Party mode, its settings gone - and the app's install does not touch the game's per-character add-on list). At each login
-- every DS Forever add-on the game has OFF is switched ON (for every character), and the app is told (cap probe "addonsoff"); it reloads
-- the game's interface once so they load. Never another add-on's state.
-- pad20 (issue #278, the user relaying a player, 2026-11-13: "says waitin on wow controller. i press start and the wheel only pops up on
-- top screen"): the login probe (Chat.lua's "ours") said WowCompanionSpells:DISABLED, yet pad15 found nothing off - its only test was
-- GetAddOnEnableState(name, character) == 0, which that client did not answer so (an argument order / a per-character state it does
-- not report). OFF is now ALSO the game's own reason (GetAddOnInfo's "DISABLED": what the probe read), the state asked both ways and
-- for every character; ON is EnableAddOn for every character and for this one; the probe says how each was found and what the game
-- reports after (`after`: the reason now, "" = loadable), so a report shows whether the switch took.
do
    local OURS = { "WowCompanionSpells", "WowCompanionHub", "WowCompanionQuests", "WowCompanionInteract", "WowCompanionCharacter",
                   "WowCompanionSocial", "WowCompanionCalendar", "WowCompanionGroupFinder", "WowCompanionProfessions", "WowCompanionPvp" }
    local function num(fn) local ok, v = pcall(fn); if ok and type(v) == "number" and (type(WC.isPlain) ~= "function" or WC.isPlain(v)) then return v end end
    local function reasonOf(a)
        local info = (type(C_AddOns) == "table" and C_AddOns.GetAddOnInfo) or rawget(_G, "GetAddOnInfo")
        if type(info) ~= "function" then return nil end
        local ok, _, _, _, _, reason = pcall(info, a)
        return ok and type(reason) == "string" and (type(WC.isPlain) ~= "function" or WC.isPlain(reason)) and reason or (ok and "" or nil)
    end
    -- why [a] is off ("state" = the enable state 0, "reason" = the game's DISABLED), nil = on
    local function offWhy(a, me)
        local A = C_AddOns
        if num(function() return A.GetAddOnEnableState(a, me) end) == 0 then return "state" end
        if num(function() return A.GetAddOnEnableState(a) end) == 0 then return "state" end
        if reasonOf(a) == "DISABLED" then return "reason" end
        return nil
    end
    function WC.addonsOn()
        if type(C_AddOns) ~= "table" or type(C_AddOns.EnableAddOn) ~= "function" then return nil end
        local me = UnitName("player")
        local off, how, after = {}, {}, {}
        for _, a in ipairs(OURS) do
            local why = offWhy(a, me)
            if why then
                local ok1 = pcall(C_AddOns.EnableAddOn, a)                       -- every character
                local ok2 = me and pcall(C_AddOns.EnableAddOn, a, me) or false   -- and this one
                if ok1 or ok2 then
                    off[#off + 1] = a; how[#how + 1] = why
                    after[#after + 1] = reasonOf(a) or "?"
                end
            end
        end
        if #off > 0 then
            pcall(function() if type(C_AddOns.SaveAddOns) == "function" then C_AddOns.SaveAddOns() end end)
            WC.send(WC.encode({ type = "cap", probe = "addonsoff", v = PADV, list = off, how = how, after = after }))
            WC.push()
        end
        return off
    end
    local ev = CreateFrame("Frame")
    ev:RegisterEvent("PLAYER_ENTERING_WORLD")
    ev:SetScript("OnEvent", function()
        ev:UnregisterEvent("PLAYER_ENTERING_WORLD")
        pcall(WC.addonsOn)
    end)
end

-- ---------------------------------------------------------------- pad22 THE LAYOUT EDITOR'S CATCHER AND STORE
-- The user, 2026-11-16: "we need to make a way to basically edit the UI locations on the top screen from our UI menu". The design's
-- picks (all the recommended ones): stage 1 moves DS Forever's OWN top-screen pieces only (the HUD, the compass, the target bar, the
-- party block, the Nintendo bar, the top chat strip); a tap that sets an EXACT spot; a linked piece follows its parent until it is moved;
-- one saved layout per character, plus "Copy from character". The editor itself is Hub.lua's (hub55); this is what every owner reads.
--   THE STORE: WowCompanionDB.lay[name-realm][id] = { dx, dy, s } - our own saved table, plain numbers only: dx / dy in 1080-line pixels
--   (right / down) from the piece's own default anchor, s its size in percent. No entry = the default (and a linked piece follows its
--   parent). Written ONLY by the editor's Save (the player's press) and the HUD's one-time move of its old height / scale (Trail prd18);
--   SvKeep (PadMap m51) carries it with the rest of WowCompanionDB. WC.lay.get(id) is what an owner places by: the editor's VIEW of its
--   draft while it is open (WC.lay.view: id -> entry, or false = the default), else the saved entry.
--   THE CATCHER (the design's "absolute channel": a repeated or stale tap changes nothing): one plain Button of ours, the whole screen, at
--   TOOLTIP strata and frame level 0 - under every pad cell (TOOLTIP, its default level) and the stray-press sink, over every game window
--   (an invisible window a room keeps open by alpha never takes the tap). The PRESS only (a stray release does nothing); where the press
--   landed (GetCursorPosition, read only) is handed to the editor in 1080-line pixels. Left out: the pad's band (describe()'s rows, its
--   gaps too) and the sink at the park point - a tap meant for a spot there would press a cell, so THE REACH SWITCH (L.reach) reads every
--   tap REACH pixels lower (L.reachPx: never less than the band's own bottom, so a spot at the very top is reached on any screen): the app
--   taps under the spot. Its own double-press guard: a second press at the same spot within CATCH_REPEAT
--   (60 ms; TapPad's REPEAT of 0.25 s would cap it at 4 taps a second). INERT (L.inert) it takes the press and does nothing: a fight's
--   start or the editor's close leaves it so for LINGER_S after its last tap (taps already in flight never reach the world), then hides it.
--   One real tap test on the Thor is owed before release (TapPad's rule; probe 5 of the design: 1 px accuracy, taps a second).
do
    local L = { V = PADV, editing = false, view = nil, reach = false, inert = false, REACH = 160, CATCH_REPEAT = 0.06, CATCH_SAME = 1.5,
                LINGER_S = 1.5, BAND_PAD = 2, n = 0, refused = 0, lastAt = -1, lastX = nil, lastY = nil, onSpot = nil }
    WC.lay = L
    local function plain(v) return (type(WC.isPlain) ~= "function" or WC.isPlain(v)) end
    local function num(v) if type(v) == "number" and plain(v) and v == v and v ~= math.huge and v ~= -math.huge then return v end end
    L.num = num
    -- "Name-Realm", the key every per-character store of ours uses
    function L.who()                                                    -- pad27: nil until the realm is known (never "Name-")
        return ((function() if type(WC.charKey) == "function" then return (WC.charKey()) end; local ok, k = pcall(function() local n, r = UnitName("player"), GetRealmName(); if type(n) == "string" and type(r) == "string" and n:find("%S") and r:find("%S") and r ~= "?" and r ~= "_" then return n .. "-" .. r end end); return ok and k or nil end)())
    end
    -- one entry as whole plain numbers (nil = the default)
    function L.clean(e)
        if type(e) ~= "table" then return nil end
        local dx, dy, s = num(e.dx) or 0, num(e.dy) or 0, num(e.s) or 100
        return { dx = math.floor(dx + 0.5), dy = math.floor(dy + 0.5), s = math.floor(s + 0.5) }
    end
    -- [who]'s saved table (nil = none); never made here
    function L.store(who)
        who = who or L.who()
        local db = type(WowCompanionDB) == "table" and WowCompanionDB.lay or nil
        local t = who and type(db) == "table" and db[who] or nil
        return type(t) == "table" and t or nil
    end
    function L.saved(id, who) local t = L.store(who); return t and L.clean(t[id]) or nil end
    -- what an owner places [id] by: the editor's view while it is open, else the saved entry; nil = the default
    function L.get(id)
        if L.editing and type(L.view) == "table" then
            local v = L.view[id]
            if v == false then return nil end
            if v ~= nil then return L.clean(v) end
        end
        return L.saved(id)
    end
    -- the screen in 1080-line pixels: its width (the game's aspect), its height, and the factor from UIParent units
    function L.screen()
        local okH, uh = pcall(function() return UIParent:GetHeight() end)
        local okW, uw = pcall(function() return UIParent:GetWidth() end)
        uh, uw = okH and num(uh) or nil, okW and num(uw) or nil
        if not (uh and uw and uh > 0 and uw > 0) then return 1920, 1080, 1 end
        return uw * 1080 / uh, 1080, 1080 / uh
    end
    -- 1080-line pixels -> UIParent units
    function L.u(px)
        local _, _, k = L.screen()
        return px / k
    end

    -- ---- THE CATCHER
    local catcher
    -- the zones a tap is never read in (1080-line pixels): the pad's band (every row it may hold) and the sink
    function L.forbidden()
        local out = {}
        local g = pad.geom
        if not g or not g.ph or g.ph <= 0 then return out end
        local k = 1080 / g.ph
        local rows = math.max(1, pad.nrows or 0)
        -- pad24: the band takes the calibration strip in (CAL_GAP + CAL_W left of the grid)
        local cb = pad.calBox and pad.calBox()
        local left = cb and (CAL_GAP + CAL_W) or 0
        local hh = math.max(rows * CELL_H, cb and cb.h or 0)
        out[#out + 1] = { x = (g.x - left - L.BAND_PAD) * k, y = (g.y - L.BAND_PAD) * k, w = (left + COLS * CELL_W + 2 * L.BAND_PAD) * k,
                          h = (hh + 2 * L.BAND_PAD) * k, why = "pad" }
        out[#out + 1] = { x = (g.pw * PARK_X - SINK / 2) * k, y = (g.ph * PARK_Y - SINK / 2) * k, w = SINK * k, h = SINK * k, why = "sink" }
        return out
    end
    local function inside(z, x, y) return x >= z.x and x <= z.x + z.w and y >= z.y and y <= z.y + z.h end
    function L.blocked(x, y)
        for _, z in ipairs(L.forbidden()) do if inside(z, x, y) then return z.why end end
        return nil
    end
    -- the reach distance: REACH, or more where the pad's band reaches lower (a smaller screen: the band is taller in 1080-line pixels),
    -- so a spot at the very top always has its tap under the band (the review of 2026-11-16)
    function L.reachPx()
        for _, z in ipairs(L.forbidden()) do
            if z.why == "pad" then return math.max(L.REACH, math.ceil(z.y + z.h + 8)) end
        end
        return L.REACH
    end
    -- where the press landed, in 1080-line pixels from the top-left (nil = not readable)
    function L.cursor()
        local ok, x, y = pcall(GetCursorPosition)
        x, y = ok and num(x) or nil, ok and num(y) or nil
        local okS, es = pcall(function() return UIParent:GetEffectiveScale() end)
        es = okS and num(es) or nil
        local okH, uh = pcall(function() return UIParent:GetHeight() end)
        uh = okH and num(uh) or nil
        if not (x and y and es and es > 0 and uh and uh > 0) then return nil end
        local k = 1080 / uh
        return (x / es) * k, (uh - y / es) * k
    end
    -- one press on the catcher: the guard, the zones, the reach, then the editor's handler with the spot
    function L.press()
        pad.pressT = clock()                                             -- pad25: the editor's tap is ours: the cursor stays blank
        local x, y = L.cursor()
        if not x then L.refused = L.refused + 1; return false, "cursor" end
        local t = clock()
        if L.inert then L.lastAt = t; return false, "inert" end
        if L.lastX and t - L.lastAt < L.CATCH_REPEAT and math.abs(x - L.lastX) <= L.CATCH_SAME and math.abs(y - L.lastY) <= L.CATCH_SAME then
            return false, "repeat"                                       -- the tap's own second press
        end
        L.lastAt, L.lastX, L.lastY = t, x, y
        local why = L.blocked(x, y)
        if why then L.refused = L.refused + 1; return false, why end
        L.n = L.n + 1
        local sy = y - (L.reach and L.reachPx() or 0)
        if type(L.onSpot) == "function" then pcall(L.onSpot, x, sy) end
        return true
    end
    local function make()
        if catcher then return catcher end
        catcher = CreateFrame("Button", nil, UIParent)
        L.catcher = catcher                                              -- for the harness
        catcher:SetFrameStrata("TOOLTIP")
        pcall(catcher.SetFrameLevel, catcher, 0)                         -- under every pad cell and the sink (their default level)
        catcher:SetAllPoints(UIParent)
        catcher:EnableMouse(true)
        pcall(catcher.SetMouseMotionEnabled, catcher, false)             -- click only: the parked cursor's hover is unchanged
        catcher:RegisterForClicks("AnyDown")                             -- the press only
        catcher:SetScript("OnClick", function(_, _, down) if down ~= false then L.press() end end)
        catcher:Hide()
        return catcher
    end
    -- on: the catcher shown and live; [fn](x, y) gets every spot
    function L.catchOn(fn)
        make()
        L.onSpot, L.inert, L.lingerTo = fn, false, nil
        catcher:Show()
        return true
    end
    -- off: inert at once, hidden LINGER_S after its last tap (taps still in flight land on it, never on the world)
    function L.catchOff()
        if not catcher or not catcher:IsShown() then return end
        L.inert = true
        L.inertAt = clock()
        local token = {}
        L.lingerTo = token
        local function later()
            if L.lingerTo ~= token or not L.inert then return end
            local left = L.LINGER_S - (clock() - math.max(L.lastAt or -1, L.inertAt or -1))
            if left > 0.01 then C_Timer.After(left, later); return end
            catcher:Hide()
            L.lingerTo = nil
        end
        C_Timer.After(L.LINGER_S, later)
    end
    function L.catching() return catcher ~= nil and catcher:IsShown() and not L.inert end
    -- the catcher as the app reads it: its rect, the zones left out, the reach distance (1080-line pixels)
    function L.catchDescribe()
        local w, h = L.screen()
        return { x = 0, y = 0, w = math.floor(w + 0.5), h = h, no = L.forbidden(), reach = L.reachPx(), on = L.catching() or nil,
                 inert = (catcher ~= nil and catcher:IsShown() and L.inert) or nil }
    end
end
