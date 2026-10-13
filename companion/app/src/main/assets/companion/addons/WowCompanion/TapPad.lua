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

local PADV = "pad10c"
local COLS = 40          -- columns per row; the quest log holds at most 40, a bag at most 36
local ROWS_MAX = 54      -- rows that may register; only those that end ABOVE the pop-up dialogs are placed (pad8 / pad9, see below)
local POPUP_MARGIN = 1   -- game px a row keeps clear of popupTop
local CELL_W = 6         -- game pixels per cell, across (12 display px on the Thor)
local CELL_H = 2         -- game pixels per cell, down (4 display px) -- see below
local QUARANTINE = 30    -- seconds a freed column stays unused
local REPEAT = 0.25       -- seconds: a second press on the same plain cell within this is the same tap (see above)
local PARK_X, PARK_Y = 0.5, 214.5 / 540   -- the freelook park point, as fractions of the game's screen (see above)
local SINK = 10          -- game pixels across the stray-press sink, centred on the park point
local SINK_POLL = 0.1    -- seconds between reads of the capture's holder (the sink's strata follows it)
local TOP_GAP = 12       -- game pixels between the screen's top edge and the first row
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
local POPUP_TOP_UNITS = 135

local pad = { rows = {}, byRow = {}, nrows = 0, ns = {}, geom = nil, info = nil, visible = false,
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
local function pressCell(btn, row, col)
    local t = clock()
    if btn.pressedAt and t - btn.pressedAt < REPEAT then return false end   -- the tap's second press
    btn.pressedAt = t
    flash(btn)
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

local function newRow(name)
    if pad.nrows >= ROWS_MAX then return nil end
    local r = { name = name, row = pad.nrows, cells = {} }
    pad.nrows = pad.nrows + 1
    pad.rows[name] = r
    pad.byRow[r.row] = r
    return r
end

-- Register a plain row. ns = the slot namespace its columns refer to, or nil for a single-cell
-- operation (column 0, no key).
function pad.op(name, ns, handler)
    local r = pad.rows[name]
    if r then r.handler = handler return r.row end
    r = newRow(name)
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
        if down then
            flash(self)
            if not early then return end
            local t = clock()
            if self.pressedAt and t - self.pressedAt < REPEAT then return end   -- the tap's second press
            self.pressedAt = t
        elseif early then
            return                                                      -- it ran on the press
        end
        if type(row.onClick) == "function" then pcall(row.onClick, c) end   -- once per tap
    end)
    r.cells[col] = btn
    return btn
end

-- Build or re-place every secure row. Out of combat only; in combat it is queued.
function pad.buildSecure()
    if not pad.geom then return false end
    if InCombatLockdown() then pad.securePending = true return false end
    for _, r in pairs(pad.rows) do
        if r.secure then
            local ok = fits(r.row)
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
function pad.secureRow(name, ncols, init, onClick, opts)
    local r = pad.rows[name]
    if r and not r.secure then return nil end        -- the name is taken by a plain row
    if not r then
        r = newRow(name)
        if not r then return nil end
        r.secure = true
    end
    r.n = math.max(0, math.min(ncols or 0, COLS))
    r.init, r.onClick, r.built = init, onClick, false
    r.onPress = type(opts) == "table" and opts.onPress == true
    pad.buildSecure()
    return r.row
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
        if fits(r.row) and (not r.secure or (r.built and secureOK)) then
            rows[name] = r.row
            sizes[name] = r.n
        end
    end
    local g = pad.geom
    -- popupTop: where the game's dialogs start, in the same game pixels, so any reader can check the grid's bottom
    -- (y + rows * ch) stays above them at whatever UI scale the player picks
    return { v = PADV, x = g.x, y = g.y, cw = CELL_W, ch = CELL_H, cols = COLS,
             popupTop = math.floor(POPUP_TOP_UNITS * pad.info.sy * 10 + 0.5) / 10,
             pw = g.pw, ph = g.ph, rows = rows, sizes = sizes, keys = keysDescribe() }
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
