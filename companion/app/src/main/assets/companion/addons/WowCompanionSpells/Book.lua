-- WowCompanionSpells / Book.lua
-- The SPELLBOOK room's controller half: who holds the controller, where the gold glow is, and Bind.
--
-- USER RULINGS (2026-09-28, relayed as the user's answers): two rooms; the Spellbook's first build is "see + put on a button" (select a
-- spell, then press the controller button you want it on, the game's own way); the game's own Spellbook button opens the room on the
-- bottom screen and B hands the controller back; the room is also opened from the bottom bar (touch). UI Art's arrangement B: the
-- spells on the left page, two columns, ten a page, row by row.
--
-- HOW IT HOLDS THE CONTROLLER: as a guest of interact's SHARED CAPTURE (WowCompanion.capture, versions 1-3). The guest receives only the
-- eight buttons: the D-pad on the press, A / B / X / Y on the release. So the game's "LB / RB = tab" cannot be mirrored: the tab row is
-- in the D-pad's reach instead (up from the top row). b25 (the user 2026-10-07: "on the spell book, we need to have x/Y change spell rank and A to bind"): A = Bind, X = a rank lower,
-- Y = a rank higher.
--
-- TAINT RULE (pad owner's caution after the 11:06 freeze): this file NEVER opens or closes PlayerSpellsFrame. It hooks the frame's own
-- OnShow / OnHide, hides it by ALPHA through the capture (spec.hide), and reads a few of its fields. Touch-open takes the capture with no
-- game window at all. Closing the game's book on B is asked of the capture (spec.back, requested from interact); until it exists, B
-- only hands the controller back.
--
-- BIND = what the game's own GamepadActionBarBindingUtil.AssignActionToGamepadStandardSlot does: ClearCursor, pick the spell up from
-- the book, PlaceAction(storage slot), and the cursor tells whether it took. Out of combat only (the capture is never held in combat).
-- WHICH trigger set the press was made in is read from the game's own active bar (GamepadMainActionBarFrame.PageUnit, read only) and,
-- as a cross-check for the first device run, from the controller's own state (LT is the game's Shift on this device); both travel
-- in the answer (`probe`) until the device has said which one is right.

local ADDON = ...
local WC = _G.WowCompanion
if type(WC) ~= "table" or type(WC.S) ~= "function" or type(WC.send) ~= "function" then return end
local S, SN, CALL = WC.S, WC.SN, WC.CALL

local BV = "b38"                -- bump on every change
local COLS, ROWS = 2, 5         -- arrangement B: the left page holds two columns of five
local PER_PAGE = COLS * ROWS

-- b23 THE PAGES of a tab: PER_PAGE entries each; in the General tab the PROFESSIONS start on a page of their own (the user 2026-10-07: "I have no way to bind profession skills. they should appear in a section of the general tab of the spell book";
-- `sect` = how many entries come before them). The app's SpellbookView pages alike.
local function pagesOf(cur)
    local n, s = #cur.list, cur.sect
    if not s or s <= 0 or s >= n then return math.max(1, math.ceil(n / PER_PAGE)) end
    return math.ceil(s / PER_PAGE) + math.ceil((n - s) / PER_PAGE)
end
-- a page's first entry (0-based in the list) and how many entries it holds
local function pageSpan(cur, page)
    local n, s = #cur.list, cur.sect
    if not s or s <= 0 or s >= n then
        local f = (page - 1) * PER_PAGE
        return f, math.max(0, math.min(PER_PAGE, n - f))
    end
    local g = math.ceil(s / PER_PAGE)
    if page <= g then
        local f = (page - 1) * PER_PAGE
        return f, math.max(0, math.min(PER_PAGE, s - f))
    end
    local f = s + (page - g - 1) * PER_PAGE
    return f, math.max(0, math.min(PER_PAGE, n - f))
end
local function entryAt(cur, page, idx)
    local f, c = pageSpan(cur, page)
    if idx >= 1 and idx <= c then return cur.list[f + idx] end
end
-- a list index (1-based) -> its page and its place on that page
local function placeOf(cur, li)
    local s = cur.sect
    if not s or s <= 0 or s >= #cur.list or li <= s then return math.floor((li - 1) / PER_PAGE) + 1, (li - 1) % PER_PAGE + 1 end
    local j = li - s - 1
    return math.ceil(s / PER_PAGE) + math.floor(j / PER_PAGE) + 1, j % PER_PAGE + 1
end
local NS = "spell"              -- the pad row's namespace (pad owner's YES, AGENT_LOG "Pad owner -> spells")
local GUEST = "spells"          -- our name at the shared capture
local BOOK_BANK = 0             -- Enum.SpellBookSpellBank.Player (only the player's book is bound in the first build)

-- the eight, by the position the game's diagram gives them (actionbindingutil.lua): D-pad left / up / right / down = 1..4, then the face
-- buttons left / top / right / bottom = 5..8. PAD1 = the bottom face button (A on an Xbox pad), PAD2 right, PAD3 left, PAD4 top.
local POS = { PADDLEFT = 1, PADDUP = 2, PADDRIGHT = 3, PADDDOWN = 4, PAD3 = 5, PAD4 = 6, PAD2 = 7, PAD1 = 8 }
local FIRST_RESERVED = 5        -- the base set's four face buttons: Interact, Inspect, Back, Jump (no storage)

local function real(v) return v ~= nil and v ~= WC.ABSENT and v ~= WC.SECRET and v ~= WC.THREW end
local function TN(fn) local v = S(fn); if type(v) == "number" then return v end end
local function TB(fn) local v = S(fn); if type(v) == "boolean" then return v end end
local function TS(fn) local v = S(fn); if type(v) == "string" and real(v) then return v end end

local function capture()
    local c = WC.capture
    if type(c) == "table" and (c.version or 0) >= 1 and type(c.take) == "function" and type(c.release) == "function" then return c end
end

-- ---------------------------------------------------------------- the book as the room shows it
-- The same tabs and the same filter as the app's SpellsModel (the game's ShouldDisplaySpellBookItem with the controller UI on): the
-- lines that are not hidden, in order; passives out when the player hides them; lower ranks out unless all ranks are shown; loose
-- flyout members out. The Pet tab is not bound in the first build, so the controller does not visit it.
local function cvar(name)
    if type(GetCVarBool) == "function" then return TB(function() return GetCVarBool(name) end) end
end

-- a spell that can go on a button: known (not a future spell), not passive, a spell (not a flyout)
local T_SPELL = 1
do
    local ok, kinds = CALL(function() return Enum.SpellBookItemType end)
    if ok and type(kinds) == "table" then T_SPELL = SN(function() return kinds.Spell end) or T_SPELL end
end

-- b23 the rank number in a sub-name ("Rank 3"), nil without one
local function rankNo(s) return type(s) == "string" and tonumber(s:match("(%d+)")) or nil end

-- b23 every entry of a RANK FAMILY (the line's known spells of one name, the lower ranks too, shown or not) carries `ranks`, highest first;
-- the room's rank picker (the user 2026-10-07, picked "B: a rank picker on each spell" over the game's Show All Spell Ranks) steps through it. The
-- PROFESSIONS close the General tab (line 1) with `sect` = the entries before them, each with `prof` (Spells.lua s3 reads the same slots).
local function tabs()
    local out = {}
    local inLines = {}
    local n = TN(function() return C_SpellBook.GetNumSpellBookSkillLines() end) or 0
    local hidePas, allRanks = cvar("spellBookHidePassives"), cvar("ShowAllSpellRanks")
    for k = 1, math.min(n, 24) do
        local ok, li = CALL(function() return C_SpellBook.GetSpellBookSkillLineInfo(k) end)
        if ok and type(li) == "table" and not TB(function() return li.shouldHide end) then
            local o = TN(function() return li.itemIndexOffset end)
            local c = TN(function() return li.numSpellBookItems end)
            local list, fam = {}, {}
            if o and c then
                for slot = o + 1, o + c do
                    inLines[slot] = true
                    local oki, info = CALL(function() return C_SpellBook.GetSpellBookItemInfo(slot, BOOK_BANK) end)
                    if oki and type(info) == "table" then
                        local pas = TB(function() return info.isPassive end)
                        local low = TB(function() return C_SpellBook.IsSpellBookItemLowRank(slot, BOOK_BANK) end)
                        local fm = TB(function() return C_SpellBook.IsSpellBookItemLooseFlyoutMember(slot, BOOK_BANK) end)
                        local e = { slot = slot, id = TN(function() return info.spellID end), a = TN(function() return info.actionID end),
                                    t = TN(function() return info.itemType end), pas = pas }
                        if not (hidePas and pas) and not (not allRanks and low) and not fm then list[#list + 1] = e end
                        local name = TS(function() return info.name end)
                        if name and e.t == T_SPELL and not pas and not fm then
                            local sub = TS(function() return info.subName end)
                            if (not sub or sub == "") and e.id then sub = TS(function() return C_Spell.GetSpellSubtext(e.id) end) end
                            e.rn = rankNo(sub)
                            fam[name] = fam[name] or {}
                            table.insert(fam[name], e)
                        end
                    end
                end
            end
            for _, f in pairs(fam) do
                if #f > 1 then
                    table.sort(f, function(x, y)
                        if (x.rn or 0) ~= (y.rn or 0) then return (x.rn or 0) > (y.rn or 0) end
                        return x.slot > y.slot
                    end)
                    for _, m in ipairs(f) do m.ranks = f end
                end
            end
            out[#out + 1] = { k = k, list = list }
        end
    end
    -- b28 the PROFESSIONS, A TAB OF THEIR OWN after the class's lines (the user 2026-10-07: "instead of putting professions on a second page of general, give those buttons their own tab"): k = Spells.lua's PROF_LINE
    if type(WC.spellsProfessions) == "function" then
        local gen = { k = WC.SPELLS_PROF_LINE or 100, list = {} }
        local okp, profs = pcall(WC.spellsProfessions)
        for _, p in ipairs(okp and type(profs) == "table" and profs or {}) do
            for slot = p.o + 1, p.o + p.c do
                if not inLines[slot] then
                    inLines[slot] = true
                    local oki, info = CALL(function() return C_SpellBook.GetSpellBookItemInfo(slot, BOOK_BANK) end)
                    if oki and type(info) == "table" then
                        local pas = TB(function() return info.isPassive end)
                        local fm = TB(function() return C_SpellBook.IsSpellBookItemLooseFlyoutMember(slot, BOOK_BANK) end)
                        if not (hidePas and pas) and not fm then
                            gen.list[#gen.list + 1] = { slot = slot, id = TN(function() return info.spellID end),
                                                        a = TN(function() return info.actionID end),
                                                        t = TN(function() return info.itemType end), pas = pas, prof = p.n or "Professions" }
                        end
                    end
                end
            end
        end
        if #gen.list > 0 then out[#out + 1] = gen end
    end
    return out
end

local function bindable(e) return e and e.t == T_SPELL and not e.pas end

-- ---------------------------------------------------------------- state and reports

-- `pg`: which page of the Spells room the controller drives, "book" (this file) or "talents" (TalentsCtl.lua gets the presses first)
-- `ctrlTab`: the book's CONTROLLER TAB is lit (the tab after the last skill line; PadTabCtl.lua gets its presses first)
local ctl = { on = false, via = nil, tab = 1, page = 1, idx = 1, onTabs = false, mode = "book", bind = nil, note = nil, pg = "book",
              ctrlTab = false, croom = false, tog = 1 }
-- b17 THE CONTROLLER ROOM's three toggles, in order (the user 2026-10-05: "DS WoW toolbar on/off", "Combat controls on/off", "Start attack
-- on cast"); in that room the tab row is this row
local TOGS = { "dpad", "cc", "sa" }
-- b20 (the user 2026-10-06: the radial's way, Hold / Tap, under the toolbar while it is on): "rmode" right after "dpad" while the toolbar is
-- on -- an APP setting (Home's HomeSettings.radialTap), so A on it only counts (tn) and the app flips it; the same order as ControllerView
local function togList()
    local pm = WC.padMap
    local ok, st = false, nil
    if type(pm) == "table" and type(pm.dpadState) == "function" then ok, st = pcall(pm.dpadState) end
    if ok and type(st) == "table" and st.want == true then return { "dpad", "rmode", "cc", "sa" } end
    return TOGS
end
local toggle                    -- b17: below (A on a toggle)
local SELECT = "PADBACK"        -- capture v4: the Select button, the Bind box's way back (see extraFor)
local extraOn = false           -- the capture delivers Select to us now

-- a box of the Talents page is open (Apply / Undo): B cancels it, so B does not lead Home
local function talentsBox()
    return ctl.pg == "talents" and type(WC.talentsCtl) == "table" and WC.talentsCtl.boxOpen() == true
end
-- the Controller tab's chooser or a box of it is open: B goes back inside the tab
local function padTabBox()
    return ctl.pg == "book" and ctl.ctrlTab and not ctl.onTabs and type(WC.padTabCtl) == "table" and WC.padTabCtl.boxOpen() == true
end
local function roomBox() return talentsBox() or padTabBox() end

-- b23 the RANK on show for entry [e] (ctl.rkSlot, the picker's choice, when it is one of e's family), and its place in the family
local function picked(e)
    if not (e and e.ranks) then return e, nil end
    local own = 1
    for i, m in ipairs(e.ranks) do
        if m.slot == ctl.rkSlot then return m, i end
        if m == e then own = i end
    end
    return e, own
end

-- WHERE THE NEXT B LEADS (capture version 3, interact's bnext): "back" while the Bind box or its answer shows (B cancels or closes it),
-- "home" in the book (B leaves, and the app goes Home). The app shows B on the Home button for "home" (user, 2026-09-29 09:23). The
-- capture starts every fresh take at "home"; this tells it only the changes. Versions 1 and 2 have no bnext: nothing is told.
local toldB = nil
local function tellB()
    if not ctl.on then toldB = nil return end
    local cap = capture()
    if not cap or (cap.version or 0) < 3 or type(cap.bnext) ~= "function" then return end
    -- opened from a TRAINER (the user 2026-09-30: "Y opens the Spellbook room; B comes back"): the last B leads back to it, not Home
    local top = ctl.via == "trainer" and "back" or "home"
    local want = (ctl.mode == "book" and not roomBox()) and top or "back"
    if toldB == want then return end
    local ok, got = pcall(cap.bnext, GUEST, want)
    if ok and got then toldB = want end
end

local function report(note)
    tellB()
    ctl.note = note or ctl.note
    local T = tabs()
    if ctl.tab > #T then ctl.tab = math.max(1, #T) end
    local cur = T[ctl.tab]
    local e = cur and entryAt(cur, ctl.page, ctl.idx)
    local p, ri = picked(e)
    WC.send(WC.encode({ type = "spellsctl", v = BV, ctl = {
        on = ctl.on, via = ctl.via, line = cur and cur.k or nil, page = ctl.page, idx = ctl.idx, tabs = ctl.onTabs or nil,
        pages = cur and pagesOf(cur) or 1,
        rk = ri, rks = e and e.ranks and #e.ranks or nil,
        sel = p and (p.id or p.a) or nil, mode = ctl.mode, bind = ctl.bind and ctl.bind.id or nil, pg = ctl.pg, ctrl = ctl.ctrlTab or nil,
        bf = ctl.bind and ctl.bind.fight or nil,                    -- b37: the Bind box puts it on the In combat layout
        bk = extraOn and "select" or nil, croom = ctl.croom or nil,
        tn = ctl.croom and (ctl.tn or 0) or nil, pn = ctl.croom and (ctl.pn or 0) or nil, note = ctl.note } }))
    WC.push()
end

-- capture contract v8 (Interact i74, the user 2026-10-01: a room keeps the controller through a fight and flashes red): every refusal of
-- the Spells rooms BECAUSE OF THE FIGHT tells the capture, which pulses Home's red flash once. Harmless out of a fight or when another
-- room holds ("no fight" / "not the holder"). One name for the Spellbook, Talents and the Controller tab: our guest name.
WC.spellsRefusedCombat = function()
    if type(WC.capture) == "table" and type(WC.capture.refused) == "function" then pcall(WC.capture.refused, GUEST) end
end

local function ack(ok, id, why, pad, probe)
    WC.send(WC.encode({ type = "spellsack", op = "bind", v = BV, id = id, ok = ok and true or false, why = why, pad = pad, probe = probe }))
    WC.push()
end

-- keep the glow on an entry that exists after the book changed (a spell learned, a filter changed)
local function clamp()
    local T = tabs()
    if #T == 0 then ctl.tab, ctl.page, ctl.idx = 1, 1, 1 return T end
    ctl.tab = math.min(math.max(ctl.tab, 1), #T)
    ctl.page = math.min(math.max(ctl.page, 1), pagesOf(T[ctl.tab]))
    local _, onPage = pageSpan(T[ctl.tab], ctl.page)
    ctl.idx = math.min(math.max(ctl.idx, 1), math.max(onPage, 1))
    return T
end

-- ---------------------------------------------------------------- navigation (the eight, D-pad on the press)

local function move(key)
    local T = clamp()
    local cur = T[ctl.tab]
    if not cur then return end
    if ctl.onTabs then
        if ctl.ctrlTab then
            if key == "PADDLEFT" then ctl.ctrlTab = false
            elseif key == "PADDDOWN" then
                ctl.onTabs = false
                if type(WC.padTabCtl) == "table" then pcall(WC.padTabCtl.enter) end
            end
            return
        end
        if key == "PADDLEFT" and ctl.tab > 1 then ctl.tab, ctl.page, ctl.idx = ctl.tab - 1, 1, 1
        elseif key == "PADDRIGHT" and ctl.tab < #T then ctl.tab, ctl.page, ctl.idx = ctl.tab + 1, 1, 1
        -- b19: no Controller tab after the last line any more -- the page is its own room (b17, proven on b48: the user "Both work")
        elseif key == "PADDDOWN" then ctl.onTabs = false end
        return
    end
    local _, onPage = pageSpan(cur, ctl.page)
    local i = ctl.idx - 1                               -- 0-based on the page, row by row
    local row, col = math.floor(i / COLS), i % COLS
    if key == "PADDUP" then
        if row == 0 then ctl.onTabs = true else ctl.idx = ctl.idx - COLS end
    elseif key == "PADDDOWN" then
        if i + COLS < onPage then ctl.idx = ctl.idx + COLS end
    elseif key == "PADDLEFT" then
        if col > 0 then ctl.idx = ctl.idx - 1
        elseif ctl.page > 1 then                        -- turn back: land on the same row's last column
            ctl.page = ctl.page - 1
            ctl.idx = row * COLS + COLS
        end
    elseif key == "PADDRIGHT" then
        if col < COLS - 1 and i + 1 < onPage then ctl.idx = ctl.idx + 1
        elseif ctl.page < pagesOf(cur) then             -- turn over: the next page's same row, first column
            ctl.page = ctl.page + 1
            local _, next = pageSpan(cur, ctl.page)
            ctl.idx = math.min(row * COLS + 1, next)
        end
    end
    clamp()
end

-- ---------------------------------------------------------------- Bind

-- which trigger set the press was made in: 1 none, 2 LT, 3 RT, 4 LT + RT (the Cast replica's order), and the bar the game has ACTIVE for
-- it. In a form or stance (a warrior's stances, a druid's forms, a rogue's stealth) the active bar can be the STANCE BAR (or the possess
-- bar), an override bar laid over one of the four with storage slots of its own (blizzard_gamepadactionbars/pageunit.lua, stancebar.lua):
-- the set is then the pageable bar it overrides.
local function activeFromGame()
    local pu = _G.GamepadMainActionBarFrame and _G.GamepadMainActionBarFrame.PageUnit
    if not pu then return nil end
    local oka, active = CALL(function() return pu:GetActiveBar() end)
    local okb, bars = CALL(function() return pu:GetPageableActionBarsInIndexOrder() end)
    if not (oka and okb and type(bars) == "table" and active) then return nil end
    for i, b in ipairs(bars) do if b == active then return i, active, false end end
    for i, b in ipairs(bars) do
        local ok, yes = CALL(function() return active:IsBarActivelyOverridingActionBar(b) end)
        if ok and yes == true then return i, active, true end
    end
end

-- the storage slot of the active bar's button at [pos], as the game's own bind reads it (actionbareditframe.lua AttemptBindDown:
-- GetActionButtonByIndex(i):GetID()); 0 = a button the game does not bind (Interact, Inspect, Back, Jump); nil = cannot tell
local function storageFromGame(active, pos)
    local ok, btn = CALL(function() return active:GetActionButtonByIndex(pos) end)
    if not ok or type(btn) ~= "table" then return nil end
    return TN(function() return btn:GetID() end)
end

local function gamePage()
    local pu = _G.GamepadMainActionBarFrame and _G.GamepadMainActionBarFrame.PageUnit
    local p = pu and TN(function() return pu:GetCurrentPage() end)
    return p or 1
end

-- b16, MEASURED 2026-10-05 21:23-21:24 (the user's bind test, WoW Forever): while a room holds the controller the game's own trigger layer
-- does NOT change -- every press read the base set (the active bar 1, Shift / both modifiers false), so ZL + X answered "reserved" and one
-- ZL + D-pad press was placed on the BASE D-pad. The controller's own state DOES see the triggers (the router reads it: ZR + A -> the RT
-- bar, 21:25:29), so the set comes from it: 1 none, 2 LT, 3 RT, 4 both; nil = cannot read it (then the game's reading, as before).
local function padIdx(name)
    local ok, v = pcall(function() return C_GamePad.ButtonBindingToIndex(name) end)
    v = ok and tonumber(v) or nil
    return (v and v >= 0) and math.floor(v) + 1 or nil
end
-- b18, MEASURED on b47 (2026-10-05 22:32-22:33, 10 bind presses): padSet() never answered from addon code -- GetGamePadState is the
-- SECURE snippets' helper (the router reads it there), not addon code's. The controller's own state for addon code is
-- C_GamePad.GetDeviceMappedState(<the active device>) (ControllerForever's readHeldLayer does the same; the buttons indexed as the
-- router's, ButtonBindingToIndex + 1). Both are tried; what answered rides the bind answer's probe (padSrc, dev, held).
local padLast = {}
local function padButtons()
    if type(GetGamePadState) == "function" then
        local ok, st = pcall(GetGamePadState)
        if ok and type(st) == "table" and type(st.buttons) == "table" then return st.buttons, "gps" end
    end
    local gp = C_GamePad
    if type(gp) ~= "table" or type(gp.GetDeviceMappedState) ~= "function" then return nil, "none" end
    local dev
    if type(gp.GetActiveDeviceID) == "function" then local ok, v = pcall(gp.GetActiveDeviceID); if ok and WC.isPlain(v) then dev = v end end
    if dev == nil and type(gp.GetCombinedDeviceID) == "function" then
        local ok, v = pcall(gp.GetCombinedDeviceID); if ok and WC.isPlain(v) then dev = v end
    end
    padLast.dev = dev
    if dev == nil then return nil, "nodev" end
    local ok, st = pcall(gp.GetDeviceMappedState, dev)
    if ok and type(st) == "table" and type(st.buttons) == "table" then return st.buttons, "mapped" end
    return nil, "nostate"
end
local function padSet()
    local bt, src = padButtons()
    padLast.src = src
    padLast.held = nil
    if not bt then return nil end
    local held = {}
    for i, v in pairs(bt) do if v == true and type(i) == "number" and #held < 8 then held[#held + 1] = i end end
    table.sort(held)
    padLast.held = held
    local li, ri = padIdx("PADLTRIGGER"), padIdx("PADRTRIGGER")
    padLast.li, padLast.ri = li, ri
    if not li or not ri then return nil end
    local lt, rt = bt[li] == true, bt[ri] == true
    if lt and rt then return 4 elseif lt then return 2 elseif rt then return 3 end
    return 1
end

local function padProbe()
    local p = { shift = TB(function() return IsShiftKeyDown() end), ctrl = TB(function() return IsControlKeyDown() end) }
    -- the game's own reading of the two modifiers that pick the bars (pageunit.lua ActionBarModKeyDownStateCheck)
    local GM = _G.GamepadMode
    if type(GM) == "table" then
        p.lmod = TB(function() return GM.IsLeftModifierDown() end)
        p.rmod = TB(function() return GM.IsRightModifierDown() end)
    end
    local ok, st = CALL(function() return C_GamePad.GetDeviceMappedState() end)
    if ok and type(st) == "table" and type(st.axes) == "table" then
        p.axes = {}
        for i, v in ipairs(st.axes) do
            if i > 8 then break end
            local n = SN(function() return v end)
            p.axes[i] = n and math.floor(n * 100 + 0.5) / 100 or false
        end
    end
    return p
end

-- the inverse of the feed's naming: page + set + position -> storage slot, the game's own formula (actionbindingutil.lua), with the
-- client's constants
local function storageFor(page, set, pos)
    local first = TN(function() return C_GamepadUI.GetFirstGamepadActionStorageSlotIndex() end)
    local K = Constants and Constants.GamepadActionBarConstants
    local perBar = K and SN(function() return K.NUM_SLOTS_PER_GAMEPAD_ACTION_BAR end)
    local perPage = K and SN(function() return K.NUM_PAGEABLE_SLOTS_PER_GAMEPAD_ACTION_BAR_PAGE_UNIT_STANDARD_PAGE end)
    local reserved = K and SN(function() return K.NUM_RESERVED_SLOTS_PER_GAMEPAD_ACTION_BAR_PAGE_UNIT end)
    if not (first and perBar and perPage and reserved) then return nil end
    local uid = (set - 1) * perBar + pos
    local slot = perPage * (page - 1) + first + (uid - 1) - reserved * (page - 1)
    if uid >= FIRST_RESERVED then slot = slot - reserved end
    return slot
end

-- the storage slot of (page, set, position) by the game's own formula (proven on the Thor 2026-09-30): the Controller tab (PadMap.lua) uses it too
WC.spellsStorageFor = storageFor

-- what is on the game's cursor, as one comparable string (internal only, never sent); nil = nothing on it
local function cursorKey()
    local ok, a, b, c, d = CALL(function() return GetCursorInfo() end)
    if not ok then return "?" end
    local kind = S(function() return a end)
    if kind == WC.ABSENT then return nil end
    return table.concat({ tostring(kind), tostring(S(function() return b end)),
                          tostring(S(function() return c end)), tostring(S(function() return d end)) }, "|")
end

-- the press that answers the Bind box. Returns nil when the spell was placed, else the reason (the answer is sent here).
local function bindPress(key)
    local b = ctl.bind
    if not b then return "none" end
    local probe = padProbe()
    local gameSet, activeBar, isOverride = activeFromGame()
    probe.game = gameSet
    probe.over = isOverride or nil
    if TB(function() return InCombatLockdown() end) then ack(false, b.id, "combat", nil, probe); WC.spellsRefusedCombat() return "combat" end
    local pos = POS[key]
    -- b16: the controller's own trigger state first (MEASURED: the game's layer does not move while the capture holds)
    local pset = padSet()
    probe.pad = pset
    -- b18: where the controller's state came from, the device, the held buttons and the trigger indexes (the proof for the next run)
    probe.padSrc, probe.dev, probe.held, probe.li, probe.ri = padLast.src, padLast.dev, padLast.held, padLast.li, padLast.ri
    local set = pset or gameSet or (probe.shift and 2 or 1)
    probe.src = pset and "pad" or (gameSet and "game" or "shift")
    -- without the controller's state: when the game's two readings clearly disagree about LT (Shift held, yet the game shows a set
    -- without LT), refuse and say so instead of guessing (a wrong set would REPLACE what another button holds)
    if not pset and gameSet and probe.shift == true and (gameSet == 1 or gameSet == 3) then
        ack(false, b.id, "unsure", nil, probe) return "unsure"
    end
    -- b37 IN COMBAT (b-bind-combat-toggle, the user on batch2: "when binding I dont see this option at all from the spell book"): the
    -- spell becomes that button's FIGHT spell - PadMap's overlay (m43, no macro, the button itself untouched) or a base X / Y / A's fight
    -- spell (m7); B alone stays the game's Back
    if b.fight then
        local pm = WC.padMap
        if type(pm) ~= "table" then ack(false, b.id, "failed", nil, probe) return "failed" end
        local okp, ok, fwhy
        if set == 1 and pos >= FIRST_RESERVED then
            if pos == 7 then ack(false, b.id, "reserved", nil, probe) return "reserved" end
            okp, ok, fwhy = pcall(pm.putCombatFace, pos, b.slot)
        else
            okp, ok, fwhy = pcall(pm.putCombat, gamePage(), set, pos, b.slot)
        end
        if okp and ok then ack(true, b.id, nil, { pg = gamePage(), b = set, p = pos, fight = true }, probe) return nil end
        local why = okp and fwhy or nil
        ack(false, b.id, (why == "combat" or why == "bind" or why == "dpad bar") and why or "failed", nil, probe)
        return why == "combat" and "combat" or "failed"
    end
    local page = gamePage()
    -- the slot: from the game's own button when it is the set the press was made in (right in a form or stance as well); else the
    -- standard-page formula for that set (b16: the trigger held while the game still shows the base bar)
    local storage = activeBar and (not pset or pset == gameSet) and storageFromGame(activeBar, pos)
    probe.slotSrc = storage and "button" or "formula"
    if storage == 0 then ack(false, b.id, "reserved", nil, probe) return "reserved" end
    if not storage then
        if set == 1 and pos >= FIRST_RESERVED then ack(false, b.id, "reserved", nil, probe) return "reserved" end
        storage = storageFor(page, set, pos)
        local valid = storage and TB(function() return C_GamepadUI.IsValidGamepadActionStorageSlotIndex(storage) end)
        if not valid then ack(false, b.id, "failed", nil, probe) return "failed" end
    end
    -- the spell must still be at that place in the book (a spell learned meanwhile moves the slots)
    local ok, info = CALL(function() return C_SpellBook.GetSpellBookItemInfo(b.slot, BOOK_BANK) end)
    local now = ok and type(info) == "table" and (TN(function() return info.spellID end) or TN(function() return info.actionID end))
    if now ~= b.id then ack(false, b.id, "unknown", nil, probe) return "unknown" end
    CALL(function() return ClearCursor() end)
    CALL(function() return C_SpellBook.PickupSpellBookItem(b.slot, BOOK_BANK) end)
    local before = cursorKey()
    if before == nil then
        CALL(function() return ClearCursor() end)
        ack(false, b.id, "failed", nil, probe) return "failed"
    end
    CALL(function() return PlaceAction(storage) end)
    local after = cursorKey()
    CALL(function() return ClearCursor() end)
    if after == before then ack(false, b.id, "failed", nil, probe) return "failed" end
    ack(true, b.id, nil, { s = storage, pg = page, b = set, p = pos }, probe)
    return nil
end

-- SELECT = BACK IN THE BIND BOX (the user 2026-09-30, relayed by tracking: "spell book works great, EXCEPT binding to the B button closes the
-- bind menu instead of binding" / "in that menu lets just use the select button for back"). With capture VERSION 4 (interact's `extra`: a
-- button beyond the eight delivered to the guest) the box asks for Select while it or its answer shows; B is then a button a spell can go on
-- (with LT / RT / both: B alone is the base set's reserved Back and answers "reserved", like A / X / Y alone). With an older capture NOTHING
-- changes: B cancels, as before.
-- b22 (the user 2026-10-07, the Controller room's tabs): LB / RB are asked for too while the Controller room holds, as Professions does
-- for its tabs -- never a shoulder the player's setup uses as its emulated Shift / Ctrl / Alt (taking it would break that modifier)
local selWant = false           -- the Bind box (or its answer) wants Select
local extraSent = nil           -- the list last asked for (a string), nil = nothing asked
local function shoulders()
    if not ctl.croom and ctl.mode ~= "bind" then return {} end     -- b37: the Bind box's LB / RB flip Normal / In combat
    local used = {}
    for _, cv in ipairs({ "GamePadEmulateShift", "GamePadEmulateCtrl", "GamePadEmulateAlt" }) do
        local v = TS(function() return C_CVar.GetCVar(cv) end)
        if v then used[v] = true end
    end
    local out = {}
    for _, k in ipairs({ "PADLSHOULDER", "PADRSHOULDER" }) do if not used[k] then out[#out + 1] = k end end
    return out
end
local function extraApply()
    local cap = capture()
    if not (ctl.on and cap and (cap.version or 0) >= 4 and type(cap.extra) == "function") then extraOn = false; extraSent = nil return end
    local list = {}
    if selWant then list[#list + 1] = SELECT end
    for _, k in ipairs(shoulders()) do list[#list + 1] = k end
    local key = table.concat(list, ",")
    if key == extraSent then return end
    local ok, got = pcall(cap.extra, GUEST, list)
    if ok and got then extraSent = key; extraOn = selWant elseif not selWant then extraOn = false end
end
local function extraFor(boxOpen)
    selWant = boxOpen and true or false
    extraApply()
end
local function backKey() return extraOn and SELECT or "PAD2" end
-- b16 (the user 2026-10-05, reversing 09-30's "select button for back": "I think B is fine, because I would never be able to bind by just
-- the plain B button, but it should not work when holding any of the trigger combinations"): a PLAIN B (no trigger held) backs out of the
-- Bind box and closes its answer -- it could never take a spell there (the base set's reserved Back); B with ANY trigger combination held
-- binds to that combination. Select stays a second way back. "Plain" = the game's active bar is the base set (a stance / form bar laid
-- over it counts as the base) and LT (the game's Shift on this device) is not down.
local function plainNow()
    local p = padSet()
    if p then return p == 1 end
    local set = activeFromGame()
    local shift = TB(function() return IsShiftKeyDown() end) == true
    return (set == nil or set == 1) and not shift
end

local function startBind(e)
    if not bindable(e) then report("bind-not-bindable") return false end
    if TB(function() return InCombatLockdown() end) then report("bind-combat") return false end
    ctl.bind = { slot = e.slot, id = e.id or e.a }
    ctl.mode = "bind"
    extraFor(true)                                      -- capture v4: Select is the way back, asked for before the box says so
    report("bind-wait")
    return true
end

-- ---------------------------------------------------------------- the capture

local frameBack = nil       -- set when spec.back is honoured by the capture (the game's close button presses itself)

-- the Talents page goes away (a let-go, the Spellbook page): its box closes, its tree stops riding the heartbeat
local function leavePage()
    if ctl.pg == "talents" and type(WC.talentsCtl) == "table" then pcall(WC.talentsCtl.leave) end
    ctl.pg = "book"
    if ctl.ctrlTab and type(WC.padTabCtl) == "table" then pcall(WC.padTabCtl.leave) end
    ctl.ctrlTab = false
    local wasCroom = ctl.croom
    ctl.croom = false                                 -- b17: the Controller room ends with the hold
    if wasCroom and ctl.on then extraApply() end      -- b22: LB / RB back to the game
end

local wantBack = false          -- a fight let go of the room while the game's window stayed up: taken again when it ends
local fightNow = false          -- b13: from PLAYER_REGEN_DISABLED (before the lockdown) to PLAYER_REGEN_ENABLED
local refreshCells              -- below: the secure cells' attributes follow the window and the hold
-- b21 (the user 2026-10-06: "Talents and spell book b button home didn't work, I had to manually touch the home button"): IsShown is a
-- frame's OWN flag. The game's close (HideUIPanel, from its close button, its own B or Escape) hides only the WINDOW, PlayerSpellsFrame;
-- the face it showed keeps its flag. Asking the face alone took a closed window for an open one: B closed it, the hide check saw it
-- "still up" and never let go (the room stuck, every later B a close of a closed window), and the next open did not press the game's
-- button. A face is UP only while the window shows too (read-only).
local function faceUp(key)
    return TB(function() return PlayerSpellsFrame:IsShown() end) == true
        and TB(function() return PlayerSpellsFrame[key]:IsShown() end) == true
end
local function windowShown() return faceUp("SpellBookFrame") or faceUp("TalentsFrame") end
local function onLost(why)
    if why == "combat" and windowShown() then wantBack = true end
    extraOn, extraSent, selWant = false, nil, false
    leavePage()
    ctl.on, ctl.via, ctl.mode, ctl.bind, ctl.onTabs, ctl.back = false, nil, "book", nil, false, nil
    report("lost-" .. tostring(why))
    if refreshCells then refreshCells() end
end

-- why = "B" when the player left with B: the capture (version 2) and the app then go to Home (user, 2026-09-28: "Pressing be to leave the
-- cursor should take me back to the home by default for everything"); nil = any other leaving (a touch elsewhere, the room closed)
-- THE LAST B: Home (the KIT's rule), or back to the trainer that lent the controller (why "trainer": Context Menus takes it back for its window)
local release
local function leaveB()
    if ctl.via == "trainer" then release("left-trainer", "trainer") else release("left-B", "B") end
end

release = function(note, why)
    extraOn, extraSent, selWant = false, nil, false
    local cap = capture()
    if cap and ctl.on then pcall(cap.release, GUEST, why) end
    leavePage()
    ctl.on, ctl.via, ctl.mode, ctl.bind, ctl.onTabs, ctl.back = false, nil, "book", nil, false, nil
    report(note or "released")
    if refreshCells then refreshCells() end
end

-- THE CAPTURE'S SPEC. Opened by the game's button, the game's book is hidden by alpha, and with capture VERSION 2 (interact's answer,
-- AGENT_LOG 43c5476) B is the game's own close button pressed through a binding: the game closes its book in its own code and our
-- OnHide hook lets go. Inside the Bind box B must CANCEL instead, so the spec is taken again without `back` while the box waits
-- (the holder may call take again) and with it once the box is gone. Version 1, or opened by touch: B reaches us and we let go.
local CLOSE_BUTTON = "PlayerSpellsFrameCloseButton"     -- the frame's own $parentCloseButton
local onPress
local function specFor(via, withBack)
    local spec = { press = function(k) onPress(k) end, lost = onLost }
    if via == "button" then
        spec.hide = { "PlayerSpellsFrame" }
        local cap = capture()
        local v = cap and (cap.version or 0) or 0
        if withBack and v >= 2 and _G[CLOSE_BUTTON] then spec.back = CLOSE_BUTTON end
        -- capture v9 (Interact i78): the generic / fight leave clicks the game's own close; a take made because the GAME's window opened
        -- pre-empts the holding room (never in a fight: take() refuses there)
        if v >= 9 and _G[CLOSE_BUTTON] then spec.leave = CLOSE_BUTTON; spec.window = true end
    end
    return spec
end

local setPage                   -- below: it needs backFor
local function take(via, pg)
    if ctl.on then setPage(pg or "book") return true end
    local cap = capture()
    if not cap then report("take-skip-nocapture") return false end
    -- b13 (the room step, SPEC items 4 / 9): no gate of ours in a fight. The capture decides: v10 (Interact i81) holds the take PENDING
    -- until the app taps its ENTER cell (then the router carries the keys into the room); an older capture refuses "combat"
    local fighting = TB(function() return InCombatLockdown() end) == true
    local spec = specFor(via, true)
    local ok, got, why = pcall(cap.take, GUEST, spec)
    if ok and got then
        ctl.on, ctl.via, ctl.mode, ctl.bind, ctl.back, ctl.pg = true, via, "book", nil, spec.back ~= nil, "book"
        -- LATER item 19 (the user 2026-09-30): a page that opens with a tab already selected starts the cursor ON that tab; Down enters it
        ctl.onTabs, ctl.ctrlTab = true, false
        clamp()
        wantBack = false
        report(fighting and "taken-pending" or "taken")
        if pg == "talents" then setPage("talents") end
        if refreshCells then refreshCells() end
        return true
    end
    report("take-refused-" .. tostring(ok and why or "error"))
    return false
end

-- the Bind box opens / closes: B changes meaning (cancel inside the box, the game's close outside it); with capture v4, Select is asked for
local function backFor(boxOpen)
    extraFor(boxOpen and (ctl.mode == "bind" or ctl.mode == "result"))    -- Select: the Bind box only (the Talents boxes keep B)
    if not (ctl.on and ctl.via == "button") then return end
    local want = not boxOpen
    if ctl.back == want then return end
    local cap = capture()
    if not cap or (cap.version or 0) < 2 then return end
    local spec = specFor("button", want)
    local ok, got = pcall(cap.take, GUEST, spec)
    if ok and got then ctl.back = spec.back ~= nil end
end

-- show a page of the Spells room: the Talents page takes the presses (TalentsCtl.lua), the Spellbook page is this file's
setPage = function(pg)
    if pg ~= "talents" then pg = "book" end
    if ctl.pg == pg then return end
    if ctl.pg == "talents" then leavePage() end
    -- the book page comes up (from the Talents page) with the cursor ON the selected tab, at once (item 19; the user: the cursor "should
    -- already be there")
    if pg == "book" then ctl.onTabs = true end
    if pg == "talents" and ctl.ctrlTab then             -- the Controller tab is the book page's: left on the way out
        if type(WC.padTabCtl) == "table" then pcall(WC.padTabCtl.leave) end
        ctl.ctrlTab = false
    end
    ctl.pg = pg
    if pg == "talents" then
        ctl.mode, ctl.bind = "book", nil                -- no Bind box across pages
        if type(WC.talentsCtl) == "table" then pcall(WC.talentsCtl.enter) end
    end
    backFor(roomBox())
    report("page-" .. pg)
end

onPress = function(key)
    if not ctl.on then return end
    -- THE TALENTS PAGE: its controller half first; a press it does not use is B, and B hands the controller back (Home)
    if ctl.pg == "talents" then
        local tcl = WC.talentsCtl
        local ok, used = false, false
        if type(tcl) == "table" and type(tcl.press) == "function" then ok, used = pcall(tcl.press, key) end
        if ok and used then
            backFor(talentsBox())                       -- a box open: B must reach the room (cancel), not the game's close button
            tellB()
            return
        end
        if key == "PAD2" then leaveB() end
        return
    end
    if ctl.mode == "bind" then
        if key == backKey() or (key == "PAD2" and plainNow()) then ctl.bind, ctl.mode = nil, "book"; backFor(false); report("bind-cancel") return end
        if key == SELECT then return end                -- not one of the eight: never a place for a spell
        if key == "PADRSHOULDER" or key == "PADLSHOULDER" then        -- b37: Normal / In combat (never a place for a spell either)
            if ctl.bind then ctl.bind.fight = not ctl.bind.fight or nil end
            report("bind-fight")
            return
        end
        local why = bindPress(key)
        if why == "reserved" or why == "unsure" then report("bind-" .. why) return end    -- the box keeps waiting (SPEC 4)
        -- placed, or refused for good: the box shows the answer for 1.5 s, then the book; B closes it at once (SPEC 4)
        ctl.bind, ctl.mode = nil, "result"
        ctl.resultN = (ctl.resultN or 0) + 1
        local n = ctl.resultN
        report(why and ("bind-" .. why) or "bound")
        C_Timer.After(1.5, function()
            if ctl.on and ctl.mode == "result" and ctl.resultN == n then ctl.mode = "book"; backFor(false); report("result-closed") end
        end)
        return
    end
    if ctl.mode == "result" then
        if key == backKey() or (key == "PAD2" and plainNow()) then ctl.mode = "book"; backFor(false); report("result-closed") end
        return
    end
    -- THE CONTROLLER TAB: its half first; "up" = back to the tab row; a press it does not use is B (Home)
    if ctl.ctrlTab and not ctl.onTabs then
        local pt = WC.padTabCtl
        local ok, used = false, false
        if type(pt) == "table" and type(pt.press) == "function" then ok, used = pcall(pt.press, key) end
        if ok and used == "up" then                     -- b22: the Controller ROOM has no row above its own (its tabs are inside)
            if not ctl.croom then ctl.onTabs = true; report("to-tabs") end
            return
        end
        if ok and used then backFor(roomBox()); tellB() return end
        if key == "PAD2" then leaveB() end
        return
    end
    if key == "PADDUP" or key == "PADDDOWN" or key == "PADDLEFT" or key == "PADDRIGHT" then
        local was = ctl.tab .. ":" .. ctl.page .. ":" .. ctl.idx .. ":" .. tostring(ctl.onTabs)
        move(key)
        if was ~= ctl.tab .. ":" .. ctl.page .. ":" .. ctl.idx .. ":" .. tostring(ctl.onTabs) then ctl.rkSlot = nil end   -- b23
        report("move")
    elseif ctl.ctrlTab and (key == "PAD1" or key == "PAD3" or key == "PAD4") then    -- the lit Controller tab: nothing (Down enters it)
        return
    elseif key == "PAD1" then                           -- b25: A = BIND (the user 2026-10-07: "on the spell book, we need to have x/Y change spell rank and A to bind"), the rank on show
        local T = clamp()
        local cur = T[ctl.tab]
        local e = cur and not ctl.onTabs and entryAt(cur, ctl.page, ctl.idx)
        if startBind((picked(e))) then backFor(true) end
    elseif key == "PAD3" or key == "PAD4" then          -- b25: X = a rank LOWER, Y = a rank HIGHER (the rank picker, no wrap; no game action)
        local T = clamp()
        local cur = T[ctl.tab]
        local e = cur and not ctl.onTabs and entryAt(cur, ctl.page, ctl.idx)
        if e and e.ranks then
            local _, i = picked(e)
            local j = key == "PAD3" and math.min(#e.ranks, i + 1) or math.max(1, i - 1)
            if j ~= i then ctl.rkSlot = e.ranks[j].slot; report("rank") end
        end
    elseif key == "PAD2" then                           -- B: back (with capture version 2 and the game's book, B never gets here)
        leaveB()
    end
end

-- ---------------------------------------------------------------- b17: THE CONTROLLER ROOM
-- The user 2026-10-05: "the controller page really seems to be important so I feel like it should actually be a home tab instead of a
-- spellbook tab" (Home puts it on the bar in Chat's place). The Controller tab's page as a room of its own: the spells row's "croom" column
-- takes the controller by touch (NO game window: the game has none for this page) and goes straight onto the page; the tab row is the
-- three toggles (Up from the page's top; Left / Right; A flips; Down back to the page); B leaves to Home. The app's ControllerView draws it.
local function enterCroom()
    local held = ctl.on or take("touch")
    if held and ctl.pg ~= "book" then setPage("book") end
    ctl.croom, ctl.tog, ctl.tn = true, ctl.tog or 1, 0      -- b20: tn counts A on the Radial plate from 0 in this room
    ctl.pn = 0                                              -- b33: pn counts X there (the wheels' PICK half, the app's setting)
    ctl.ctrlTab, ctl.onTabs = true, false
    local pt = WC.padTabCtl
    -- b30: the cursor starts on the room's tab row, its first tab (PadTabCtl p17; the user 2026-10-07: "start on the top left tab")
    if type(pt) == "table" and type(pt.enter) == "function" then pcall(pt.enter, "tabs") end
    extraApply()                                            -- b22: LB / RB for the tabs
    report(held and "croom" or "croom-nopad")
    if refreshCells then refreshCells() end
end
-- A on a switch: PadMap does it (each refuses in a fight with one red pulse; idempotent). b22: by its key, from the room's tabs (PadTabCtl p9)
toggle = function(k)
    local pm = WC.padMap
    if k == "rmode" then                                             -- b20: the app's setting; said by the count
        ctl.tn = (ctl.tn or 0) + 1
        report("tog-rmode")
        return
    end
    if k == "rpick" then                                             -- b33: the wheels' pick (A / Release), the app's too
        ctl.pn = (ctl.pn or 0) + 1
        report("tog-rpick")
        return
    end
    if type(pm) ~= "table" or not k then return end
    if k == "dpad" and type(pm.dpadState) == "function" and type(pm.dpadBar) == "function" then
        local ok, st = pcall(pm.dpadState)
        pcall(pm.dpadBar, not (ok and type(st) == "table" and st.want == true))
    elseif k == "cc" and type(pm.setCombat) == "function" and type(pm.combatOn) == "function" then
        local ok, on = pcall(pm.combatOn)
        pcall(pm.setCombat, not (ok and on))
    elseif k == "sa" and type(pm.setAttack) == "function" and type(pm.attackOn) == "function" then
        local ok, on = pcall(pm.attackOn)
        pcall(pm.setAttack, not (ok and on))
    end
    report("tog-" .. k)
end
WC.spellsToggle = function(k) toggle(k) end

-- ---------------------------------------------------------------- the game's own Spellbook button

local hooked = false
local function bookShown() return faceUp("SpellBookFrame") end

-- EVERY ROOM THE SAME (the user 2026-10-01 via Home: "Every room opens the game's own window behind it, as Professions does"; "it should
-- act the EXACT same for all rooms and be no different"): every way in now opens the game's window (the secure cells below), so the hold
-- is the window's. A hold taken by touch before the window came up becomes the window's when it shows (the button spec: hidden by alpha,
-- B = the game's close); the trainer's lend stays as it is (the game's book never opens over the trainer window).
local function toWindow()
    local cap = capture()
    if not (cap and ctl.on and ctl.via == "touch") then return end
    local spec = specFor("button", not roomBox())
    local ok, got = pcall(cap.take, GUEST, spec)
    if ok and got then ctl.via, ctl.back = "button", spec.back ~= nil; report("touch-to-window") end
end
local function onFaceShow(pg)
    if ctl.on then
        toWindow()
        setPage(pg)
    else
        take("button", pg ~= "book" and pg or nil)
    end
    if refreshCells then refreshCells() end
end
-- a face hid: the WINDOW closed only when no face shows a frame later (switching faces hides one and shows the other in the same call).
-- B (the game's close, clicked by the capture's binding) = leaving with B, so Home. The close CELL lets go "closed" in its own post-click,
-- which runs in the same click, before this check. Someone else's leave (the capture's generic / fight leave) has let go already.
local function onFaceHide()
    if refreshCells then refreshCells() end
    if not (ctl.on and ctl.via == "button") then return end
    C_Timer.After(0, function()
        if not (ctl.on and ctl.via == "button") or windowShown() then return end
        local cap = capture()
        if cap and type(cap.state) == "function" then
            local okS, st = pcall(cap.state)
            if okS and type(st) == "table" and st.holder ~= nil and st.holder ~= GUEST then onLost("gone") return end
        end
        leaveB()
    end)
end
local function onBookShow() onFaceShow("book") end
local function onBookHide() onFaceHide() end

-- THE GAME'S OWN TALENTS BUTTON (the same window's talents face, PlayerSpellsFrame.TalentsFrame): the room comes up on its Talents page.
-- Either face closing while we hold the controller through the game's button is leaving with B (the game's close button, the capture's
-- `back`), so Home, whichever page the room shows.
local function talentsFrame()
    local f = _G.PlayerSpellsFrame
    local tf = type(f) == "table" and rawget(f, "TalentsFrame") or (type(f) == "table" and f.TalentsFrame) or nil
    if type(tf) == "table" and type(tf.HookScript) == "function" then return tf end
end
local function onTalentsShow() onFaceShow("talents") end
local function onTalentsHide() onFaceHide() end

local talentsHooked = false
local function hookBook()
    local f = _G.PlayerSpellsFrame
    local tf = talentsFrame()
    if tf and not talentsHooked then
        talentsHooked = true
        pcall(tf.HookScript, tf, "OnShow", onTalentsShow)
        pcall(tf.HookScript, tf, "OnHide", onTalentsHide)
        if TB(function() return tf:IsShown() end) then onTalentsShow() end
    end
    if hooked then return end
    local book = f and f.SpellBookFrame
    if not book or type(book.HookScript) ~= "function" then return end
    hooked = true
    pcall(book.HookScript, book, "OnShow", onBookShow)
    pcall(book.HookScript, book, "OnHide", onBookHide)
    if bookShown() then onBookShow() end
end

do
    local hf = CreateFrame("Frame")
    pcall(function() hf:RegisterEvent("ADDON_LOADED") end)
    pcall(function() hf:RegisterEvent("PLAYER_LOGIN") end)
    pcall(function() hf:RegisterEvent("SPELLS_CHANGED") end)
    pcall(function() hf:RegisterEvent("PLAYER_REGEN_ENABLED") end)
    pcall(function() hf:RegisterEvent("PLAYER_REGEN_DISABLED") end)
    local function retake(n)
        if ctl.on or not windowShown() then wantBack = false return end
        local pg = faceUp("TalentsFrame") and "talents" or nil
        if take("button", pg) then return end
        if n < 2 then C_Timer.After(n == 0 and 0.5 or 1.0, function() retake(n + 1) end) end
    end
    hf:SetScript("OnEvent", function(_, event, name)
        if event == "PLAYER_REGEN_DISABLED" then                     -- before the lockdown: the close cell goes idle for the fight
            fightNow = true
            if refreshCells then refreshCells() end
            return
        end
        if event == "PLAYER_REGEN_ENABLED" then
            fightNow = false
            if wantBack then retake(0) end
            if refreshCells then refreshCells() end
            return
        end
        if event == "ADDON_LOADED" and name ~= "Blizzard_PlayerSpells" then return end
        if event == "SPELLS_CHANGED" then
            if ctl.on then clamp() end
            return
        end
        hookBook()
        if refreshCells then refreshCells() end
    end)
end

-- ---------------------------------------------------------------- the pad row (touch)
-- Pad owner's YES (AGENT_LOG "Pad owner -> spells"): row `spells`, namespace `spell`, columns 0..39. "open" and "close" are claimed
-- FIRST so they get 0 and 1; "giveback" next (2: Home's B that shrank the controller out of a fight gives A / X / Y back, the user
-- 2026-10-01; PadMap.lua acts, the column rides the padmap as face.gb), "sa-on" / "sa-off" (3 / 4: Home's Settings room, start attack on
-- cast; padmap sa.cols); each bindable spell then gets a stable column (5..39), `pc`.
-- THE ROW IS SECURE (b12, the user's "every room the same"): a plain row runs as addon code, which may never open or close a game window,
-- so the cells that press the GAME's own buttons are SecureActionButtonTemplate cells (TapPad pad.secureRow; the pad is full, so this row
-- itself turned secure, same name, same columns): "open" = a secure click on SpellbookMicroButton, "topen" on TalentMicroButton (the same
-- window's Talents face), "close" on PlayerSpellsFrameCloseButton. The game's buttons TOGGLE, so each open cell does nothing while its face
-- already shows, and close only while Spells holds the window (set out of combat on every show / hide / take / release; in a fight a cell
-- keeps what it was at its start). Every other cell has no secure action and runs on the PRESS (pad6b's onPress; an older pad: the release).
-- A tap is a mouse click: the game runs a secure action on the RELEASE, then the cell's post-click (onCell) runs.
local MICRO_BOOK, MICRO_TALENTS = "SpellbookMicroButton", "TalentMicroButton"
local CELLS = 40
local pad = WC.pad
local colKey = {}               -- column -> key: the fixed keys, then each spell's column (pad.slotFor in the namespace "spell")
local fixedCols = {}
-- b15: THE fixed keys, in claim order (claimed once, kept by WC.spellsPadColumns' retain: b14 left "suggest" out of that keep list, so the
-- pad freed its column and a later spell could take it). "dpad-on" / "dpad-off" (b15): Home's Settings row, the D-pad home bar (PadMap m24)
-- b37: "bf-on" / "bf-off" the Bind box's Normal / In combat by touch (two columns, so a repeat is harmless; only while the box waits)
-- b38 A SPELL BY TWO TAPS (b-druid-forms-bind, a player: "they don't have the ability to bind change form as a druid"): one column per
-- spell ran out (40 cells, 14 fixed: 26 spells, every rank counted, in book order), so a druid's Feral line - the forms - got none and
-- its touch Bind did nothing. Now every spell has a stable NUMBER (0..V_MAX-1, kept while the spell is in the book, quarantined when it
-- goes) and a touch is a fresh GROUP pick ("vb0".."vb12": numbers from 0 / 13 / ... / 156) then its OFFSET ("vo0".."vo12") in ONE batch,
-- the "extra" row's rule: a lost or stale group pick (older than V_PICK_S) never puts a wrong spell
local V_GROUPS, V_PER, V_PICK_S, V_QUARANTINE = 13, 13, 2, 10     -- 26 columns: 169 numbers
local V_MAX = V_GROUPS * V_PER
local FIXED_KEYS = { "open", "close", "giveback", "sa-on", "sa-off", "topen", "suggest", "dpad-on", "dpad-off", "croom", "cc-on", "cc-off",
                     "bf-on", "bf-off" }
for g = 0, V_GROUPS - 1 do FIXED_KEYS[#FIXED_KEYS + 1] = "vb" .. g end
for k = 0, V_PER - 1 do FIXED_KEYS[#FIXED_KEYS + 1] = "vo" .. k end
local VIDX = { byId = {}, byIdx = {}, freedAt = {}, pick = nil }
local secureRowOk = type(pad) == "table" and type(pad.secureRow) == "function"
local onCell
local function cellInit(btn, col)
    local key = colKey[col]
    local cb = nil
    if key == "open" then
        if not faceUp("SpellBookFrame") then cb = _G[MICRO_BOOK] end      -- b21: a closed window's face keeps its flag
    elseif key == "topen" then
        if not faceUp("TalentsFrame") then cb = _G[MICRO_TALENTS] end
    elseif key == "close" then
        -- b13: in a fight the room's way out is Interact's leave (B through the router, or the app's tap on its leave cell): ours is idle
        if not fightNow and ctl.on and ctl.via == "button" and windowShown() then cb = _G[CLOSE_BUTTON] end
    end
    if type(cb) ~= "table" then cb = nil end
    btn:SetAttribute("type", cb and "click" or nil)
    btn:SetAttribute("clickbutton", cb)
end
refreshCells = function()
    if secureRowOk then pcall(pad.secureRow, "spells", CELLS, cellInit, function(col) onCell(col) end, { onPress = true }) end
end
if type(pad) == "table" and type(pad.slotFor) == "function" and (secureRowOk or type(pad.op) == "function") then
    for _, k in ipairs(FIXED_KEYS) do
        local c = pad.slotFor(NS, k)
        if c then colKey[c], fixedCols[k] = k, c end
    end
    WC.spellsGiveBackCol = fixedCols.giveback
    -- b34: "suggest" stays only as a RESERVED column key (the claim order must not shift); its "Use these" is gone (no suggestions)
    WC.spellsDpadCols = { on = fixedCols["dpad-on"], off = fixedCols["dpad-off"] }   -- b15: the D-pad home bar (padmap dpad.cols)
    WC.spellsCcCols = { on = fixedCols["cc-on"], off = fixedCols["cc-off"] }           -- b17: combat controls (padmap face.ccCols)
    -- Home's Settings room: START ATTACK ON CAST on / off (the user 2026-10-01); two columns, so a repeat is harmless (PadMap acts)
    WC.spellsAttackCols = { on = fixedCols["sa-on"], off = fixedCols["sa-off"] }
    -- the room's way in and out, for the app (SpellbookView.enterRoom / leaveRoom): the spells doc carries them as `win`
    WC.spellsWinCols = { open = fixedCols.open, close = fixedCols.close, topen = fixedCols.topen, croom = fixedCols.croom,
                         bfon = fixedCols["bf-on"], bfoff = fixedCols["bf-off"] }
    for g = 0, V_GROUPS - 1 do WC.spellsWinCols["vb" .. g] = fixedCols["vb" .. g] end      -- b38: the two taps' columns
    for k = 0, V_PER - 1 do WC.spellsWinCols["vo" .. k] = fixedCols["vo" .. k] end
    local function handle(key)
        if key == nil then report("pad-unknown") return end
        -- OPEN (the Spellbook / Talents face): the secure click has opened the window (its OnShow took the controller as the window's);
        -- the cell did nothing when that face already showed: the hold is taken now; no window at all (an old client): the touch hold
        if key == "open" or key == "topen" then
            local pg = key == "topen" and "talents" or "book"
            if ctl.croom then                                            -- b17: the Spellbook room replaces the Controller room
                if ctl.ctrlTab and type(WC.padTabCtl) == "table" then pcall(WC.padTabCtl.leave) end
                ctl.croom, ctl.ctrlTab = false, false
            end
            if ctl.on then if ctl.via == "touch" and windowShown() then toWindow() end; setPage(pg); report("open-" .. pg)
            elseif windowShown() then take("button", pg ~= "book" and pg or nil)
            else take("touch", pg ~= "book" and pg or nil) end
            if refreshCells then refreshCells() end
            return
        end
        -- CLOSE: the secure click has closed the window (when Spells held it); its post-click lets go "closed" at once, before the hide's
        -- frame-later check (which would say B). Not Spells' window, or no window: only our own guest is let go (the "free again" self-heal).
        if key == "close" then
            if fightNow then report("close-fight-leave") return end       -- b13: Interact's leave does it in a fight; never a second close
            WC.spellsRoom.close()
            return
        end
        -- Home's B shrank the big controller out of a fight: A / X / Y back to the game's (a no-action note; idempotent, refused in combat)
        if key == "croom" then enterCroom() return end                    -- b17: the CONTROLLER ROOM (Home's bar, Chat's old place)
        if key == "cc-on" or key == "cc-off" then                       -- b17: combat controls on / off (refused in a fight; idempotent)
            if type(WC.padMap) == "table" and type(WC.padMap.setCombat) == "function" then pcall(WC.padMap.setCombat, key == "cc-on") end
            return
        end
        if key == "dpad-on" or key == "dpad-off" then                   -- b15: the D-pad home bar on / off (refused in a fight; idempotent)
            if type(WC.padMap) == "table" and type(WC.padMap.dpadBar) == "function" then pcall(WC.padMap.dpadBar, key == "dpad-on") end
            return
        end
        if key == "suggest" then return end                              -- b34: retired (nothing is ever suggested)
        -- b38: a spell by two taps: the group (a pick, fresh for V_PICK_S), then the offset = the spell's number
        if type(key) == "string" and key:match("^vb%d+$") then VIDX.pick = { g = tonumber(key:sub(3)), at = GetTime() } return end
        if type(key) == "string" and key:match("^vo%d+$") then
            local p = VIDX.pick
            VIDX.pick = nil
            if not p or GetTime() - p.at > V_PICK_S then report("pad-no-group") return end
            local id = VIDX.byIdx[p.g * V_PER + tonumber(key:sub(3))]
            if id == nil then report("pad-unknown") return end
            key = id                                                     -- on as that spell's own tap
        end
        if key == "bf-on" or key == "bf-off" then                       -- b37: the Bind box's Normal / In combat by touch
            if ctl.mode == "bind" and ctl.bind then ctl.bind.fight = (key == "bf-on") or nil; report("bind-fight") end
            return
        end
        if key == "giveback" then
            if type(WC.padMap) == "table" and type(WC.padMap.giveBack) == "function" then pcall(WC.padMap.giveBack) end
            return
        end
        if key == "sa-on" or key == "sa-off" then
            if type(WC.padMap) == "table" and type(WC.padMap.setAttack) == "function" then pcall(WC.padMap.setAttack, key == "sa-on") end
            return
        end
        -- a spell: Bind by touch. The controller is taken for the press if the room does not hold it yet.
        if type(key) ~= "number" then report("pad-unknown") return end
        -- while the CONTROLLER TAB is open (the pad owner's YES: the same column), the spell goes onto the tab's chosen button
        if ctl.ctrlTab and type(WC.padTabCtl) == "table" then
            pcall(WC.padTabCtl.touchSpell, key)                -- by touch: no controller needed
            return
        end
        local T = clamp()
        local function hit(x) return x and ((x.a or x.id) == key or x.id == key) end
        for ti, t in ipairs(T) do
            for li, e in ipairs(t.list) do
                local m = hit(e) and e or nil
                if not m and e.ranks then for _, r in ipairs(e.ranks) do if hit(r) then m = r end end end   -- b23: a rank the picker showed
                if m then
                    if not ctl.on and not take("touch") then return end
                    ctl.tab, ctl.onTabs = ti, false
                    ctl.page, ctl.idx = placeOf(t, li)
                    ctl.rkSlot = (m ~= e) and m.slot or nil
                    if startBind(m) then backFor(true) end
                    return
                end
            end
        end
        report("pad-not-in-book")
    end
    onCell = function(col) handle(colKey[col]) end
    if secureRowOk then
        refreshCells()
    else
        pad.op("spells", NS, function(key) handle(key) end)            -- an old pad without secure rows: the plain row, as before
    end
end

-- read-only views for the Controller tab's half (PadTabCtl.lua): the set the game has ACTIVE (a trigger held), the book's spells that can go on a
-- button (the book's order, known, not passive), and a spell's book slot
WC.spellsActiveSet = function() local i = activeFromGame(); return i end
WC.spellsBindableList = function()
    local out, seen = {}, {}
    for _, t in ipairs(tabs()) do
        for _, e in ipairs(t.list) do
            local id = e.id or e.a
            if bindable(e) and id and not seen[id] then
                seen[id] = true
                -- b24: `gen` = the General tab's (line 1); b28: `prof` = the Professions tab's: the Controller room's chooser has a tab for each
                out[#out + 1] = { id = id, slot = e.slot, gen = (t.k == 1) or nil, prof = (t.k == (WC.SPELLS_PROF_LINE or 100)) or nil }
            end
        end
    end
    return out
end
WC.spellsBookSlotOf = function(id)
    for _, e in ipairs(WC.spellsBindableList()) do if e.id == id then return e.slot end end
end

-- THE CONTROLLER TAB BY TOUCH: the pad owner's YES (2026-09-30): its codes ride the `talents` row, columns 11-39 (code k = column - 11):
--   0-3 show set 1-4, 4-11 choose button 1-8, 12-14 the bar's page 1-3 (picks); 15 clear, 16-26 command 1-11 (ONE action each, after the room's
--   own box); 27 the tab opened by touch, 28 the tab left by touch (picks)
WC.padTabTouch = function(k)
    local pt = WC.padTabCtl
    if type(k) ~= "number" or type(pt) ~= "table" then return false end
    if k == 27 then
        -- the controller is taken if it can be; TOUCH works without it (the user 2026-09-30: assigning failed while another room kept the
        -- controller): the tab's picks and its ONE actions are the game's cursor road, they never needed the controller
        local held = ctl.on or take("touch")
        if held and ctl.pg ~= "book" then setPage("book") end
        -- the tab lit with the cursor ON it (item 19: the selected tab is where the cursor starts); Down enters its page. Its state is sent
        -- at once, so the app shows the very button the addon would change.
        if not ctl.ctrlTab then
            ctl.ctrlTab, ctl.onTabs = true, true
            if type(pt.sync) == "function" then pcall(pt.sync) end
        end
        report(held and "ctrl-touch" or "ctrl-touch-nopad")
        return true
    end
    if k == 28 then
        if ctl.keep then ctl.keep = false; if type(pt.keepMode) == "function" then pcall(pt.keepMode, false) end end   -- b36
        if ctl.ctrlTab then pcall(pt.leave); ctl.ctrlTab = false; report("ctrl-left") end
        return true
    end
    if not ctl.ctrlTab then return false end                 -- picks and actions count only on the open tab
    if k <= 3 then pt.touchSet(k + 1)
    elseif k <= 11 then pt.touchPos(k - 3)
    elseif k <= 14 then pt.touchPage(k - 11)
    elseif k == 15 then pt.touchClear()
    elseif k <= 26 then pt.touchCommand(k - 15)
    else return false end
    return true
end

-- b22 THE PADTAB ROW (the pad owner's YES 2026-10-07, Quests): the Controller room's tabs and Move by touch. A PLAIN row: it fires on the
-- press and runs insecure code, so column 4 (place / swap: ONE game action) is refused in a fight with an ack (PadMap.move answers
-- "combat"); 0-3 and 5 only move the room's own state; an unused column, or a tap while the room is not open, answers a refusal and does
-- nothing.   0-2 show the tab WoW Defaults / DS / In combat, 3 pick up the chosen button (Move), 4 PLACE / SWAP on the chosen button,
-- 5 put the carried button back
local PADTAB = {
    [0] = function(pt) pt.touchTab(1) end, [1] = function(pt) pt.touchTab(2) end, [2] = function(pt) pt.touchTab(3) end,
    [3] = function(pt) pt.touchCarry() end, [4] = function(pt) pt.touchPlace() end, [5] = function(pt) pt.touchCancel() end,
    -- b26: the "extra" row's page picks (one-shot, the same touch): entries from 0 / 40 / 80
    [6] = function(pt) pt.touchExtraPage(0) end, [7] = function(pt) pt.touchExtraPage(40) end, [8] = function(pt) pt.touchExtraPage(80) end,
    -- b27: LB, tapped by the app (LB is the game's Ctrl on this device: the capture never hears it; PadTabCtl p13)
    [9] = function(pt) pt.touchShoulder(-1) end,
    -- b29: the 4th tab, the SPELL RADIAL (PadTabCtl p16; the pad owner's YES 2026-10-07: "padtab col 10 = the 4th tab"; 11-39 refused)
    [10] = function(pt) pt.touchTab(4) end,
    -- b31: the clean-up (PadTabCtl p19 / PadMap m41 S1): 11 its list, 12 the run after the app's box (13-39 refused)
    [11] = function(pt) pt.touchCleanList() end, [12] = function(pt) pt.touchCleanRun() end,
    -- b32: 13 the shared DS macros' run (PadTabCtl p21 / PadMap m42; 14-39 refused)
    [13] = function(pt) pt.touchSharedRun() end,
}
if type(pad) == "table" and type(pad.op) == "function" then
    pcall(pad.op, "padtab", "padtab", function(_, col)
        local fn = type(col) == "number" and PADTAB[col]
        local pt = WC.padTabCtl
        -- b35 THE QUIET ENTER (the user's ruling after test20, "Both": the app re-applies a character's In-combat settings from its backup
        -- at login, out of combat, by the Controller tab's own touch picks and ONE actions): column 14 opens the tab for touch WITHOUT the
        -- room and WITHOUT taking the controller (the player keeps playing); talents code 28 leaves it as before. Refused in a fight.
        if col == 14 and type(pt) == "table" then
            local fight = InCombatLockdown and InCombatLockdown()
            if not fight and not ctl.ctrlTab then
                ctl.ctrlTab, ctl.onTabs, ctl.keep = true, true, true
                if type(pt.keepMode) == "function" then pcall(pt.keepMode, true) end                  -- b36 / p25: In-combat puts only
                if type(pt.sync) == "function" then pcall(pt.sync) end
            end
            WC.send(WC.encode({ type = "padack", v = BV, op = "keep-enter", ok = not fight, why = fight and "combat" or nil }))
            WC.push()
            return
        end
        if not fn or type(pt) ~= "table" or not ctl.croom then
            WC.send(WC.encode({ type = "padack", v = BV, op = "padtab", ok = false, col = col,
                                why = fn and "room not open" or "unknown column" }))
            WC.push()
            return
        end
        pcall(fn, pt)
    end)
    -- b26 THE "extra" ROW (the pad owner's YES 2026-10-07: plain, 40 fixed columns, 44 of 48): the Controller room's Extra tab by touch, the
    -- entry = the page picked just before (padtab 6-8, REQUIRED) + the column. Every tap answers with a padack naming what was put.
    pcall(pad.op, "extra", "extra", function(_, col)
        local pt = WC.padTabCtl
        local ok, why, idx, x = false, "room not open", nil, nil
        if type(pt) == "table" and type(pt.touchExtra) == "function" and ctl.croom and type(col) == "number" then
            local okc, a, b, c, d = pcall(pt.touchExtra, col)
            if okc then ok, why, idx, x = a, b, c, d else why = "error" end
        end
        WC.send(WC.encode({ type = "padack", v = BV, op = "extra", ok = ok and true or false, col = col, why = why, idx = idx,
                            what = x and (x.key or x.id) or nil, kind = x and x.kind or nil, n = x and x.n or nil }))
        WC.push()
    end)
end

-- THE SPELLS ROOM'S PAGES for the Talents pad row (Talents.lua: column 0 open, 1 close): open the Talents page, taking the controller
-- by touch when the room does not hold it yet (as `spells` 0 does, NO game window); close = let go.
WC.spellsRoom = {
    -- `from` = "trainer": Context Menus' trainer pop-up lends the controller on Y (the user's ruling); the room's last B goes back to it
    open = function(pg, from)
        if from == "trainer" then
            if ctl.on then ctl.via = "trainer"; setPage(pg or "book"); report("from-trainer") return true end
            return take("trainer", pg)
        end
        return take("touch", pg)
    end,
    -- the room was left (by touch, or the app's self-heal "free again", Home's one rule for every room): let go of the shared capture
    -- WHATEVER our own flag says (the capture frees only its holder, so this is safe), and leave the Controller tab
    close = function()
        if ctl.on then release("closed") return end
        local cap = capture()
        if cap and type(cap.release) == "function" then pcall(cap.release, GUEST, "closed") end
        leavePage()
        report("closed-free")
    end,
    page = function() return ctl.pg end,
    -- the Talents page's door back (UI Art, AGENT_LOG 8978431): the book page, the controller kept
    setPage = function(pg) if ctl.on then setPage(pg) end end,
}

-- The feed asks for the NUMBERS of the spells it lists (Spells.lua; b38: two taps, not a column each): stable while the spell stays in
-- the book; a number let go waits V_QUARANTINE s before another spell gets it (a document still on its way never reaches a new spell)
function WC.spellsPadNumbers(ids)
    local now = GetTime()
    local present = {}
    for _, id in ipairs(ids) do present[id] = true end
    for id, v in pairs(VIDX.byId) do
        if not present[id] then VIDX.byId[id] = nil; VIDX.byIdx[v] = nil; VIDX.freedAt[v] = now end
    end
    local out = {}
    for _, id in ipairs(ids) do
        local v = VIDX.byId[id]
        if v == nil then
            for i = 0, V_MAX - 1 do
                if VIDX.byIdx[i] == nil and (not VIDX.freedAt[i] or now - VIDX.freedAt[i] > V_QUARANTINE) then
                    v = i; VIDX.byId[id], VIDX.byIdx[i], VIDX.freedAt[i] = i, id, nil
                    break
                end
            end
        end
        out[id] = v
    end
    return out
end

-- for the harness
WC._spellsBook = { ctl = ctl, press = function(k) onPress(k) end, take = take, release = release, tabs = tabs, colKey = colKey,
                   storageFor = storageFor, hook = hookBook, setPage = function(pg) setPage(pg) end }
