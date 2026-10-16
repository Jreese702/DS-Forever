-- WowCompanionSpells / PadTabCtl.lua
-- The CONTROLLER ROOM's controller half (UI Art's PADTAB_SPEC section 8; the user's picks 2026-09-30: "Pick a button, then what goes on
-- it", the game's commands as small General macros, Clear at the end of the Change list, ZL / ZR held shows that set).
--
-- It holds NO controller of its own: Book.lua's guest holds it for the room (b17 "croom") and hands every press here first. press(key)
-- answers true when it used the press, false to let Book.lua do its own (B: Home).
--
-- p9 (the user 2026-10-07, the Controller room): THREE TABS, "WoW Defaults" | "DS" | "In combat" (LB / RB, or the tab row above), each with
-- its own switches ("as drawn": DS = the DS WoW toolbar; In combat = Combat controls, Start attack on cast). The set row has no "None": ZL /
-- ZR / ZL + ZR, A shows that set and A again goes back to the base one (holding a trigger shows its set, as before). On a button:
--   * an EMPTY one: A = choose what goes on it (the Change list);
--   * one that holds something: A = Change, X = MOVE (pick it up);
--   * carrying: A on an empty button PLACES, A on a taken one SWAPS (PadMap.move: ONE game action), B puts it back.
-- The DS tab shows the D-pad toolbar (left Chat, up Radial, right Map, down the Home bar); A on Up flips the radial's Hold / Tap.
--
-- WHERE THE CURSOR LIVES: the tab row, the tab's switches, the set buttons, the eight buttons (the D-pad's diamond, then the face diamond)
-- or the DS tab's D-pad, the chooser, the boxes. In the chooser A chooses a row, B goes back; in a box A is its ONE game action, B cancels.
--
-- THE BOXES (SPEC 6): a button that holds something asks "Replace" first; a command whose macro is not made yet then asks "Make" (or, with no
-- General macro left, says so: "full"); Clear asks. A spell or a made command on an EMPTY button goes straight on (ONE game action).
-- p9 (m32): a combat spell may go on ANY button: a spell or an item stays for outside fights; a macro, a mount, a flyout is REPLACED, and
-- the Replace box names it first.
--
-- THE LAYOUT (the Cast room's rule, proven on the Thor): on a Nintendo print the face diamond is DRAWN swapped, X (the game's PAD3, position 5)
-- on top, Y (PAD4, 6) on the left, A (PAD1, 8) on the right, B (PAD2, 7) at the bottom; Xbox / PlayStation draw 5-8 as left / top / right /
-- bottom. The D-pad moves among the buttons AS DRAWN, so it follows the app's layout: the app says it with a no-action pick (`talentn` column
-- 36 = plain, 37 = Nintendo), remembered in WowCompanionSpellsDB; Nintendo until told (the Thor's print, the app's default).
--
-- Nothing here touches a game window or runs the game's UI code: it moves a cursor and calls WC.padMap (PadMap.lua), whose actions are each the
-- game's cursor road, out of combat, and Book.lua's switches (WC.spellsToggle).

local ADDON = ...
local WC = _G.WowCompanion
if type(WC) ~= "table" or type(WC.send) ~= "function" or type(WC.encode) ~= "function" then return end

local CV = "p29"                -- bump on every change; p29: the In combat tab's FORM row (f-combat-forms, PadMap m55)
-- the eight on a grid for the D-pad (the SPEC's two diamonds side by side): column, row; positions 1-4 the D-pad's left / up / right / down
local GRIDS = {
    plain = { { 0, 1 }, { 1, 0 }, { 2, 1 }, { 1, 2 }, { 3, 1 }, { 4, 0 }, { 5, 1 }, { 4, 2 } },   -- 5-8 left / top / right / bottom
    swap  = { { 0, 1 }, { 1, 0 }, { 2, 1 }, { 1, 2 }, { 4, 0 }, { 3, 1 }, { 4, 2 }, { 5, 1 } },   -- Nintendo: 5 top, 6 left, 7 bottom, 8 right
}
local function db()
    if type(_G.WowCompanionSpellsDB) ~= "table" then _G.WowCompanionSpellsDB = {} end
    return _G.WowCompanionSpellsDB
end
local function layout() return db().padLayout == "plain" and "plain" or "swap" end
local DIRS = { PADDUP = { 0, -1 }, PADDDOWN = { 0, 1 }, PADDLEFT = { -1, 0 }, PADDRIGHT = { 1, 0 } }

-- p9 THE TABS, in the user's order; "normal" = WoW Defaults (the name the app has always read), "combat" = In combat
-- p16 THE SPELL RADIAL's tab (the user's picks 2026-10-07: "A: You choose" -- up to 8 per page, 3 pages, filled in the Controller room like buttons): its 3 pages on the
-- page plates ("rpages", where the set plates are), its 8 slices drawn AS THE WHEEL ITSELF (UI Art 2026-10-07: slice 1 up, clockwise, as
-- Home's wheel; Left / Right step round it counter-clockwise / clockwise, Up goes to the page plates, Down does nothing), filled through
-- SpellRadial.lua (WC.spellRadial: put / clear / move). Its chooser: Spells / General / Professions (no Extra: a radial casts spells).
local TABS = { "normal", "ds", "combat", "radial" }
-- each tab's switches ("as drawn"); "rmode" (the radial's Hold / Tap) is A on the DS tab's Up
local SWITCHES = { normal = {}, ds = { "dpad" }, combat = { "cc", "sa" }, radial = {} }
-- the rows from the top, per tab
-- p19: the DS tab's "cleanup" row (PadMap m41 S1, the user's pick 2026-10-09: "a 'Clean up this character' button in the Controller
-- room's DS tab lists exactly what goes; one yes per character"): under the D-pad, only while the plan has something
-- p21: "shared" (PadMap m42, the user's answer 2026-10-09 "Last, separately"): the shared DS macros' own button, beside the clean-up's on
-- the left page's bottom (shared left, cleanup right); Left / Right between the two, Up back to the D-pad
-- p29 (f-combat-forms): the In combat tab's "forms" row (Caster | the character's forms / stances), only while it has forms
local ORDER = { normal = { "tabs", "sets", "buttons" }, ds = { "tabs", "switches", "dpad" }, combat = { "tabs", "switches", "forms", "sets", "buttons" },
                radial = { "tabs", "rpages", "buttons" } }
local HOME_FOCUS = { normal = "buttons", ds = "dpad", combat = "buttons", radial = "buttons" }
local function SR() return type(WC.spellRadial) == "table" and WC.spellRadial or nil end

-- `view`: the tab ("normal" = WoW Defaults, "ds", "combat" = In combat)
local tc = { on = false, focus = "buttons", set = 1, pos = 1, chooser = nil, box = nil, note = nil, view = "normal",
             tcur = 1, sw = 1, scur = 2, dpos = 2, carry = nil, rpage = 1, form = 0, fcur = 0 }
-- p25 THE KEEP MODE (Book b36's quiet enter; test22 device: two of the restore's taps were lost, the "combat layer" pick among them, so
-- the In-combat spell went onto the GAME's bar slot as a plain spell): while the app restores its backup, a spell is ONLY ever an
-- In-combat setting (the overlay / a face's fight spell), and only right after a FRESH set AND button pick; nothing else acts
local keep = { on = false, set = false, pos = false, at = 0 }
local KEEP_S = 60               -- a lost leave tap never leaves the keep mode on: it ends by itself
local function keepOn() if keep.on and GetTime() - keep.at > KEEP_S then keep.on = false end return keep.on end
local EXTRA_PICK_S = 2          -- p12: an extra tap's page pick is fresh this long (the pad owner's PICK_SECONDS)
local COMBAT_PICK_S = 3         -- a touch's "combat layer" pick (talentn 39) counts for the action that follows within this many seconds

local function PM() return type(WC.padMap) == "table" and WC.padMap or nil end
local function inCombat()
    local ok, v = pcall(function() return InCombatLockdown() end)
    return ok and v == true
end
local function tabIndex(v) for i, t in ipairs(TABS) do if t == v then return i end end return 1 end

-- the set a press is in: a held trigger's (the game's active set) wins over the one chosen by touch or the set buttons
-- p26 (b-bind-hold-triggers, the user 2026-10-13: "after choosing Bind while holding ZL / ZR, the layer should stay held until the bind
-- completes, even if the triggers are released"): the set is LATCHED when the chooser opens (tc.lock) and kept while the chooser or its
-- box is up; releasing the trigger no longer drops the spell onto the base set
local function shownSet()
    if tc.lock and (tc.chooser or tc.box) then return tc.lock end
    if type(WC.spellsActiveSet) == "function" then
        local ok, a = pcall(WC.spellsActiveSet)
        if ok and type(a) == "number" and a >= 2 and a <= 4 then return a end
    end
    return tc.set
end

local rows                      -- below: the chooser's rows (the report names the one under the cursor)
local fightView     -- p26 below: the chooser's layout (p28: the tab's own; the user's pick "No flip in the chooser")
-- p16 the radial's page on show: its 8 slices' spell ids, 0 = empty
local function radialPage()
    local sr = type(WC.spellRadial) == "table" and WC.spellRadial or nil
    local out = {}
    for i = 1, 8 do out[i] = (sr and sr.get((tc.rpage - 1) * 8 + i)) or 0 end
    return out
end
local function report(note)
    tc.note = note or tc.note
    local pm = PM()
    local box = tc.box and { kind = tc.box.kind, what = tc.box.thing and (tc.box.thing.key or tc.box.thing.id) or nil,  -- p12: a mount / item's key
                             thing = tc.box.thing and tc.box.thing.kind or nil } or nil
    -- the row under the chooser's cursor, by what it is (a spell id, a command key, "clear"): the app finds it in ITS list by that
    local ch = nil
    if tc.chooser then
        local r = rows()[tc.chooser.row]
        -- a mount / an item row is named by its key ("m:<id>" / "i:<id>"): a mount and an item may share a number
        local rid = r and ((r.kind == "mount" or r.kind == "item") and r.key or (r.id or r.key or r.kind)) or nil
        ch = { list = tc.chooser.list, row = tc.chooser.row, id = rid, tabs = tc.chooser.tabs or nil }
    end
    WC.send(WC.encode({ type = "padtabctl", v = CV, c = {
        on = tc.on or nil, focus = tc.focus, set = tc.set, shown = shownSet(), pos = tc.pos, page = pm and pm.page() or 1,
        chooser = ch, box = box, lay = layout(), view = tc.view, note = tc.note,
        tcur = tc.tcur, sw = tc.sw, scur = tc.scur, dpos = tc.dpos,
        carry = tc.carry and { set = tc.carry.set, pos = tc.carry.pos, page = tc.carry.page } or nil,
        rpage = tc.view == "radial" and tc.rpage or nil, rad = tc.view == "radial" and radialPage() or nil,
        form = tc.form ~= 0 and tc.form or nil, fcur = tc.view == "combat" and tc.fcur or nil } }))
    WC.push()
end

local function here()
    if tc.view == "radial" then                                     -- p16: the slice (place s on the radial)
        local s = (tc.rpage - 1) * 8 + tc.pos
        local sr = SR()
        local id = sr and sr.get(s)
        return { kind = id and "spell" or "none", id = id, s = s, radial = true }
    end
    local pm = PM()
    if not pm then return nil end
    -- p29: a FORM chosen on the In combat tab: the bare set's buttons are that form's own In-combat spells (B stays the game's Back)
    if tc.view == "combat" and tc.form ~= 0 and shownSet() == 1 and tc.pos ~= 7 and type(pm.formFight) == "function" then
        local e = pm.formFight(tc.form, tc.pos)
        return { kind = e and "combat" or "none", form = tc.form, pos = tc.pos, c = e and { id = e.id, n = e.n, i = e.i } or nil }
    end
    return pm.slotAt(pm.page(), shownSet(), tc.pos)
end
local function filled(slot) return slot and slot.kind ~= "none" and slot.kind ~= "fixed" end
local function changeable(slot) return slot and slot.kind ~= "fixed" and not inCombat() end
local function isCombat(slot) return slot and slot.kind == "combat" end
-- m32: what a combat spell keeps for outside fights (a spell, an item, nothing); anything else it replaces, and Replace names it first
local function keeps(slot) return slot and (slot.kind == "none" or slot.kind == "spell" or slot.kind == "item") end
-- p26 / p28: the chooser's layout is the tab's (In combat = fight spells); p26's Y / "-" flip is gone (the user's pick on batch5: "No flip
-- in the chooser": the room's own In combat tab makes fight spells; the Spellbook's Bind box has Normal | In combat)
fightView = function()
    if tc.chooser and tc.chooser.fight ~= nil then return tc.chooser.fight end
    return tc.view == "combat"
end
local function onlySpells() return fightView() or isCombat(here()) end
-- the base set's X / Y / A (PadMap m7, the user 2026-10-01 "Yes, per button"): in the combat view they take a spell for fights (no macro);
-- B (7) stays the game's Back
local function isFace(slot) return slot and slot.kind == "fixed" and slot.face == true end

-- p11 THE CHOOSER'S TABS (the user 2026-10-07: "in the controller menu, when assigning a spell, the spell menu needs some tabs. start on spells, then general
-- (including professions), then extra"): Spells (the class's lines), General (the book's General tab, its professions too), Extra (the commands).
-- LB / RB, or its tab row (Up from the first row; Left / Right OPEN the tab the cursor lands on, p10's rule; Down / A back to the rows). A
-- fight spell's chooser has no Extra: a command is no fight spell.
-- p14 (the user 2026-10-07: "instead of putting professions on a second page of general, give those buttons their own tab"): Professions is a tab of the chooser too
local CTABS = { "spells", "general", "professions", "extra" }
local function ctabs() if onlySpells() or tc.view == "radial" then return { "spells", "general", "professions" } end return CTABS end
local function ctabIndex(list)
    for i, v in ipairs(ctabs()) do if v == list then return i end end
    return 1
end

-- the chooser's rows: the book's spells or the commands, then "Clear this button" on a filled button
-- in the COMBAT view: spells only (the combat spell), then "Clear combat spell" on a combat button; out of combat on a COMBAT button: spells
-- only (its "Otherwise" spell), then "Clear this button" (both go)
rows = function()
    local out = {}
    local h = here()
    local spellKind = tc.view == "radial" and "rspell"
        or fightView() and (isFace(h) and "cface" or "combat") or (isCombat(h) and "otherwise" or "spell")
    -- the combat view leaves out the spells already on the button (its own spell, its combat and its otherwise spell): the same spell
    -- in and out of combat would only spend a macro
    local skip = {}
    if fightView() and h then
        if h.kind == "spell" and h.id then skip[h.id] = true end
        if h.c and h.c.id then skip[h.c.id] = true end
        if h.o and h.o.id and not h.o.item then skip[h.o.id] = true end
        if h.cf and h.cf.id then skip[h.cf.id] = true end
    end
    -- (p24: p8's class "suggestion" row is gone - the user: nothing is ever suggested; the chooser is the player's own spells only)
    local list = tc.chooser and tc.chooser.list or "spells"
    if list == "extra" and (onlySpells() or tc.view == "radial") then list = "spells" end
    if list ~= "extra" then
        if type(WC.spellsBindableList) == "function" then
            for _, e in ipairs(WC.spellsBindableList()) do
                local tab = e.prof and "professions" or e.gen and "general" or "spells"
                if not skip[e.id] and tab == list then
                    out[#out + 1] = { kind = spellKind, id = e.id, slot = e.slot }
                end
            end
        end
    else
        -- p12 EXTRA (the user's picks 2026-10-07): the commands, then the character's mounts, then the bags' usable items (PadMap m33's
        -- extraList: the padmap doc carries the same three lists in the same order, so the app and the "extra" pad row count alike)
        local pm = PM()
        local list = pm and type(pm.extraList) == "function" and pm.extraList() or {}
        if #list == 0 then for _, c in ipairs(pm and pm.commands() or {}) do list[#list + 1] = { kind = "command", key = c.key } end end
        for _, x in ipairs(list) do
            if x.kind == "command" or x.kind == "bind" then out[#out + 1] = { kind = x.kind, key = x.key }     -- p15: the game's bindings
            else out[#out + 1] = { kind = x.kind, id = x.id, key = (x.kind == "mount" and "m:" or "i:") .. tostring(x.id), n = x.n } end
        end
    end
    if fightView() then
        if isCombat(h) then out[#out + 1] = { kind = "uncombat" }
        elseif isFace(h) and h.cf then out[#out + 1] = { kind = "uncface" } end
    elseif filled(here()) then out[#out + 1] = { kind = "clear" } end
    return out
end

-- ---------------------------------------------------------------- doing it

local function act(thing)
    if tc.view == "radial" then                                     -- p16: the spell radial's slice (SpellRadial.lua)
        local sr, h = SR(), here()
        if sr and h then
            if thing.kind == "rspell" then sr.put(h.s, thing.id)
            elseif thing.kind == "clear" then sr.clear(h.s) end
        end
        tc.box, tc.chooser, tc.focus = nil, nil, "buttons"
        return
    end
    local pm = PM()
    if not pm then return end
    local page, set, pos = pm.page(), shownSet(), tc.pos
    -- p29: with a form chosen (the In combat tab, the bare set) a fight spell is THAT form's own (PadMap m55)
    local h = here()
    if h and h.form and type(pm.putCombatForm) == "function" then
        if thing.kind == "combat" or thing.kind == "cface" then pm.putCombatForm(h.form, pos, thing.slot)
        elseif thing.kind == "uncombat" or thing.kind == "uncface" then pm.clearCombatForm(h.form, pos) end
        tc.box, tc.chooser, tc.focus = nil, nil, "buttons"
        return
    end
    if thing.kind == "spell" then pm.putSpell(page, set, pos, thing.slot)
    elseif thing.kind == "command" then pm.putCommand(page, set, pos, thing.key)
    elseif thing.kind == "item" then pm.putItem(page, set, pos, thing.id)            -- p12: the bags' items (PadMap m33)
    elseif thing.kind == "bind" then pm.putBind(page, set, pos, thing.key)            -- p15: a game binding (PadMap m34)
    elseif thing.kind == "mount" then pm.putMount(page, set, pos, thing.id)          -- p12: the Mount Journal's
    elseif thing.kind == "clear" then pm.clear(page, set, pos)
    elseif thing.kind == "combat" then pm.putCombat(page, set, pos, thing.slot)
    elseif thing.kind == "otherwise" then pm.putOtherwise(page, set, pos, thing.slot)
    elseif thing.kind == "uncombat" then pm.clearCombat(page, set, pos)
    elseif thing.kind == "cface" then pm.putCombatFace(pos, thing.slot)
    elseif thing.kind == "uncface" then pm.clearCombatFace(pos) end
    tc.box, tc.chooser, tc.focus = nil, nil, "buttons"
end

-- the box a thing needs before it goes on, in the SPEC's order; nil = straight on
local function boxFor(thing, afterReplace)
    if thing.kind == "clear" or thing.kind == "uncombat" or thing.kind == "uncface" then return "clear" end
    -- a face button's fight spell: its own box the first time (it changes what X / Y / A do in every fight), Replace after
    if thing.kind == "cface" then
        local h = here()
        if h and h.cf then return (not afterReplace) and "replace" or nil end
        return "cface"
    end
    if thing.kind == "otherwise" then return (not afterReplace) and "replace" or nil end
    if thing.kind == "combat" then
        -- p22 (PadMap m43): the overlay: nothing on the button is replaced and no macro is made; Replace only names a fight spell there
        local h = here()
        if isCombat(h) then return (not afterReplace) and "replace" or nil end
        return nil
    end
    if not afterReplace and filled(here()) then return "replace" end
    local pm = PM()
    if thing.kind == "command" and pm and not pm.made(thing.key) then return pm.macrosFull() and "full" or "make" end
    return nil
end

-- capture v8: a refusal because of the fight pulses Home's red flash (Book.lua's helper)
local function refusedCombat()
    report("combat")
    if type(WC.spellsRefusedCombat) == "function" then WC.spellsRefusedCombat() end
end

-- p15: may the chosen button take this binding (PadMap m34's rule: no trigger on the D-pad, or ZL)? Said when not
local function bindHere(thing)
    if thing.kind ~= "bind" then return true end
    local pm = PM()
    local ok, why = true, nil
    if pm and type(pm.bindAllowed) == "function" then ok, why = pm.bindAllowed(shownSet(), tc.pos) end
    if not ok then report("bind-" .. tostring(why)) end
    return ok
end

local function choose(thing)
    if inCombat() then refusedCombat() return end
    if not bindHere(thing) then return end
    local kind = boxFor(thing, false)
    if kind then tc.box = { kind = kind, thing = thing }; report("box-" .. kind)
    else act(thing); report("put") end
end

local function confirm()
    local b = tc.box
    if not b then return end
    if b.kind == "cleanup" then                                         -- p19: the clean-up, the player's one yes (PadMap m41 S1)
        tc.box = nil
        local pm = PM()
        local ok = pm and type(pm.cleanupRun) == "function" and pm.cleanupRun()
        if tc.focus == "cleanup" then tc.focus = "dpad" end
        report(ok and "cleaned" or "clean-refused")
        return
    end
    if b.kind == "cleanshared" then                                     -- p21: the shared DS macros, their own yes (PadMap m42)
        tc.box = nil
        local pm = PM()
        local ok = pm and type(pm.sharedRun) == "function" and pm.sharedRun()
        if tc.focus == "shared" then tc.focus = "dpad" end
        report(ok and "shared-cleaned" or "shared-refused")
        return
    end
    if b.kind == "full" or b.kind == "cfull" then tc.box = nil; report("box-closed") return end
    if b.kind == "replace" then
        local nextKind = boxFor(b.thing, true)
        if nextKind then tc.box = { kind = nextKind, thing = b.thing }; report("box-" .. nextKind) return end
    end
    act(b.thing)
    report(b.kind == "clear" and "cleared" or "put")
end

-- ---------------------------------------------------------------- p9: tabs, switches, Move

local function setTab(v)
    tc.view = v
    tc.carry, tc.chooser, tc.box = nil, nil, nil
    tc.tcur, tc.sw = tabIndex(v), 1
    if tc.focus ~= "tabs" then tc.focus = HOME_FOCUS[v] end
    report("tab")
end

-- one row up / down in the tab's ORDER (the carried button stays among the sets and the buttons)
-- p19 / p21: the DS tab's bottom rows are there only while they have something to do
local function formList()
    local pm = PM()
    return (pm and type(pm.forms) == "function") and pm.forms() or {}
end
local function rowThere(f)
    local pm = PM()
    if not pm then return false end
    if f == "forms" then return #formList() > 0 end                    -- p29: a class without forms has no form row
    -- p24: the clean-up rows are gone (the user after test14: "I dont understand why we have a cleanup button"; PadMap m45 removes
    -- our own old macros by itself)
    if f == "cleanup" or f == "shared" then return false end
    return true
end

local function step(dir)
    local order = ORDER[tc.view] or ORDER.normal
    local i = 1
    for k, f in ipairs(order) do if f == tc.focus then i = k end end
    local j = i + dir
    -- p19 / p21: the clean-up's and the shared macros' rows only while they are there; a missing one is stepped over
    while order[j] == "cleanup" or order[j] == "shared" or order[j] == "forms" do
        if rowThere(order[j]) then break end
        j = j + dir
    end
    if j < 1 or j > #order then return false end
    if tc.carry and order[j] ~= "sets" and order[j] ~= "rpages" and order[j] ~= "buttons" then return false end
    tc.focus = order[j]
    if tc.focus == "tabs" then tc.tcur = tabIndex(tc.view) end
    if tc.focus == "sets" then tc.scur = (tc.set >= 2 and tc.set) or 2 end
    if tc.focus == "switches" then tc.sw = math.min(math.max(tc.sw or 1, 1), math.max(1, #(SWITCHES[tc.view] or {}))) end
    report("to-" .. tc.focus)
    return true
end

-- a switch: Book.lua does it (each refuses in a fight with one red pulse; idempotent)
local function flip(k)
    if type(WC.spellsToggle) == "function" then pcall(WC.spellsToggle, k) end
    report("switch-" .. k)
end

-- pick up the button under the cursor (X; touch's padtab 3)
local function carryStart()
    local h = here()
    if h and h.radial then                                          -- p16: a slice is picked up (no game action)
        if h.kind == "none" then report("nothing-to-move") return end
        if inCombat() then refusedCombat() return end
        tc.carry = { radial = true, s = h.s, page = tc.rpage, set = 1, pos = tc.pos }
        report("carry")
        return
    end
    if h and h.kind == "bind" then report("bind-move") return end         -- p15: a binding rides the key, not the bar
    if not filled(h) then report(h and h.kind == "fixed" and "locked" or "nothing-to-move") return end
    if inCombat() then refusedCombat() return end
    local pm = PM()
    tc.carry = { page = pm and pm.page() or 1, set = shownSet(), pos = tc.pos }
    report("carry")
end

-- put it down on the button under the cursor: an empty one gets it, a taken one SWAPS (ONE game action: PadMap.move)
local function carryPlace()
    local c = tc.carry
    if not c then report("nothing-carried") return end
    if c.radial then                                                -- p16: a slice onto another (a taken one SWAPS)
        local h, sr = here(), SR()
        if not (h and sr) then return end
        if h.s == c.s then tc.carry = nil; report("carry-cancel") return end
        if inCombat() then refusedCombat() return end
        local swap = h.kind ~= "none"
        local ok = sr.move(c.s, h.s)
        tc.carry = nil
        report(ok and (swap and "swapped" or "moved") or "move-refused")
        return
    end
    local pm = PM()
    if not pm then return end
    local h = here()
    if not h then return end
    if h.kind == "fixed" then report("locked") return end
    if h.kind == "bind" then report("bind-move") return end               -- p15
    local page, set = pm.page(), shownSet()
    if c.page == page and c.set == set and c.pos == tc.pos then tc.carry = nil; report("carry-cancel") return end
    if inCombat() then pm.move(c.page, c.set, c.pos, page, set, tc.pos); refusedCombat() return end     -- refused, answered; still carried
    local swap = h.kind ~= "none"
    local ok = pm.move(c.page, c.set, c.pos, page, set, tc.pos)
    tc.carry = nil
    report(ok and (swap and "swapped" or "moved") or "move-refused")
end

-- ---------------------------------------------------------------- presses

local function nearest(key, n)
    local d = DIRS[key]
    local grid = GRIDS[layout()]
    local cur = grid[n == 4 and tc.dpos or tc.pos]
    local best, bestScore
    for i = 1, n or #grid do
        local g = grid[i]
        if i ~= (n == 4 and tc.dpos or tc.pos) then
            local dx, dy = g[1] - cur[1], g[2] - cur[2]
            local along = dx * d[1] + dy * d[2]
            if along > 0 then
                local score = along + 2 * (math.abs(dx * d[2]) + math.abs(dy * d[1]))
                if not bestScore or score < bestScore then best, bestScore = i, score end
            end
        end
    end
    return best
end

local function pressButtons(key)
    if tc.view == "radial" and DIRS[key] then                       -- p16: round the wheel (8 slices, wrapping); Up = the page plates
        if key == "PADDLEFT" or key == "PADDRIGHT" then
            tc.pos = (tc.pos - 1 + (key == "PADDLEFT" and -1 or 1)) % 8 + 1
            report("move")
        elseif key == "PADDUP" then step(-1) end
        return true
    end
    if DIRS[key] then
        local nxt = nearest(key)
        if nxt then tc.pos = nxt; report("move")
        elseif key == "PADDUP" then step(-1) end
        return true
    end
    if key == "PAD1" then                               -- A: carrying -> place / swap; else choose / Change (the ONE action's chooser)
        if tc.carry then carryPlace() return true end
        local h = here()
        if not h then return true end
        if h.kind == "fixed" then
            if tc.view == "combat" and isFace(h) then
                if not inCombat() then tc.lock = shownSet(); tc.chooser = { list = "spells", row = 1 }; report("chooser") else refusedCombat() end
            else report(tc.view == "combat" and "stays-back" or "locked") end
            return true
        end
        -- p22 (PadMap m43): a fight spell on any button is the overlay now (no macro): the p18 "paused" stop is gone
        if changeable(h) then tc.lock = shownSet(); tc.chooser = { list = "spells", row = 1 }; report("chooser")      -- p26: latched
        elseif inCombat() then refusedCombat() end                      -- Change during a fight: said, and pulsed
        return true
    end
    if key == "PAD3" then                               -- X: MOVE (pick up what is on the button)
        if not tc.carry then carryStart() end
        return true
    end
    if key == "PAD4" then return true end
    return false
end

local function pressTabs(key)
    -- p10 (the user 2026-10-07: "the character tabs auto open to the tab that the cursor is on- I REALLY like this and want to make the other
    -- tabs consistent with this behavior"): the cursor LANDING on a tab opens it at once and stays on it (once per landing: the wall opens
    -- nothing; no game action: the room's own page). LB / RB as before.
    if key == "PADDLEFT" or key == "PADDRIGHT" then
        local i = math.min(#TABS, math.max(1, tc.tcur + (key == "PADDLEFT" and -1 or 1)))
        if i ~= tc.tcur then setTab(TABS[i]) end
    elseif key == "PAD1" then
        if TABS[tc.tcur] ~= tc.view then setTab(TABS[tc.tcur]) end      -- open already (the landing did it)
    elseif key == "PADDDOWN" then step(1)
    elseif key == "PAD2" then return false
    end
    return true
end

local function pressSwitches(key)
    local list = SWITCHES[tc.view] or {}
    if key == "PADDLEFT" then tc.sw = math.max(1, tc.sw - 1); report("switch-cursor")
    elseif key == "PADDRIGHT" then tc.sw = math.min(math.max(1, #list), tc.sw + 1); report("switch-cursor")
    elseif key == "PAD1" then if list[tc.sw] then flip(list[tc.sw]) end
    elseif key == "PADDUP" then step(-1)
    elseif key == "PADDDOWN" then step(1)
    elseif key == "PAD2" then return false
    end
    return true
end

-- p29 the form row: Caster, then the character's forms in the game's order; LANDING on one shows its set (p10's rule, no A needed)
local function pressForms(key)
    local list = formList()
    if key == "PADDLEFT" or key == "PADDRIGHT" then
        tc.fcur = math.min(#list, math.max(0, (tc.fcur or 0) + (key == "PADDLEFT" and -1 or 1)))
        tc.form = tc.fcur == 0 and 0 or (list[tc.fcur] and list[tc.fcur].id or 0)
        report("form")
    elseif key == "PADDUP" then step(-1)
    elseif key == "PADDDOWN" then step(1)
    elseif key == "PAD2" then return false
    end
    return true
end

local function pressSets(key)
    if key == "PADDLEFT" then tc.scur = math.max(2, tc.scur - 1); report("set-cursor")
    elseif key == "PADDRIGHT" then tc.scur = math.min(4, tc.scur + 1); report("set-cursor")
    elseif key == "PAD1" then tc.set = (tc.set == tc.scur) and 1 or tc.scur; report("set")      -- p9: no "None": A again = the base set
    elseif key == "PADDDOWN" then step(1)
    elseif key == "PADDUP" then step(-1)
    elseif key == "PAD2" then return false
    end
    return true
end

-- p16 the radial's page plates: the cursor LANDING on a page shows it (p10's rule: no A needed; a carried slice goes along)
local function pressRpages(key)
    if key == "PADDLEFT" or key == "PADDRIGHT" then
        local p = math.min(3, math.max(1, tc.rpage + (key == "PADDLEFT" and -1 or 1)))
        if p ~= tc.rpage then tc.rpage = p; report("rpage") end
    elseif key == "PADDDOWN" or key == "PAD1" then step(1)
    elseif key == "PADDUP" then step(-1)
    elseif key == "PAD2" then return false
    end
    return true
end

-- the DS tab's D-pad (left Chat, up Radial, right Map, down the Home bar): Up's A flips the radial's Hold / Tap (the app's setting)
local function pressDpad(key)
    if DIRS[key] then
        local nxt = nearest(key, 4)
        if nxt then tc.dpos = nxt; report("ds-move")
        elseif key == "PADDUP" then step(-1)
        elseif key == "PADDDOWN" then step(1) end                      -- p19: down to the clean-up row (when there is one)
        return true
    end
    if key == "PAD1" then
        if tc.dpos == 2 then flip("rmode") else report("ds-info") end
        return true
    end
    -- p23 (Home 2026-10-09: two settings for every wheel, open Tap / Hold and pick A / Release): X on the Radial flips the PICK half
    if key == "PAD3" then
        if tc.dpos == 2 then flip("rpick") else report("ds-info") end
        return true
    end
    if key == "PAD2" then return false end
    return true
end

local function pressChooser(key)
    local list = rows()
    local T = ctabs()
    -- p11: the neighbour tab, opened at its first row (no wrap); the cursor stays where it was (the tab row or the rows)
    local function nextTab(d)
        local i = ctabIndex(tc.chooser.list) + d
        if T[i] then tc.chooser.list, tc.chooser.row = T[i], 1; report("list") end
    end
    if key == "PADLSHOULDER" or key == "PADRSHOULDER" then nextTab(key == "PADLSHOULDER" and -1 or 1) return true end
    if tc.chooser.tabs then                                 -- the cursor on the chooser's tab row
        if key == "PADDLEFT" or key == "PADDRIGHT" then nextTab(key == "PADDLEFT" and -1 or 1)
        elseif key == "PADDDOWN" or key == "PAD1" then tc.chooser.tabs = nil; report("to-rows")
        elseif key == "PAD2" then tc.chooser = nil; report("chooser-closed") end
        return true
    end
    if key == "PADDUP" then
        if tc.chooser.row <= 1 then tc.chooser.tabs = true; report("to-tabs")
        else tc.chooser.row = tc.chooser.row - 1; report("row") end
    elseif key == "PADDDOWN" then tc.chooser.row = math.min(math.max(1, #list), tc.chooser.row + 1); report("row")
    elseif key == "PADDLEFT" or key == "PADDRIGHT" then nextTab(key == "PADDLEFT" and -1 or 1)
    elseif key == "PAD1" then
        local thing = list[tc.chooser.row]
        if thing then choose(thing) end
    elseif key == "PAD2" then tc.chooser = nil; report("chooser-closed")
    end
    return true
end

local function pressBox(key)
    if key == "PAD1" then confirm()
    elseif key == "PAD2" then tc.box = nil; report("box-cancel") end
    return true
end

WC.padTabCtl = {
    version = 2,                -- p9: tabs, switches, Move
    -- the room opened: the cursor on the tab's own place (the buttons; the DS tab's D-pad); the tab is the last one shown.
    -- p17 `where` = "tabs" (the Controller ROOM's open, Book b30; the user 2026-10-07: "the tabs at the top of the controller menu should
    -- have the arrows side to side when highlighted, and be changed by the Dpad. when opening the room the cursor should start on the top
    -- left tab"): the cursor ON THE TAB ROW, on the first tab (WoW Defaults), which it opens (p10: landing opens)
    enter = function(where)
        tc.on, tc.chooser, tc.box, tc.carry = true, nil, nil, nil
        if where == "tabs" then tc.view = TABS[1] end
        tc.focus = where == "tabs" and "tabs" or HOME_FOCUS[tc.view] or "buttons"
        tc.tcur = tabIndex(tc.view)
        local pm = PM()
        if pm then pm.emit(true) end
        report("enter")
    end,
    leave = function()
        tc.on, tc.chooser, tc.box, tc.carry = false, nil, nil, nil
        report("leave")
    end,
    isOn = function() return tc.on end,
    -- the room is shown but not entered: its chosen set / button / page are sent, so the app shows (and touch changes) the same button
    sync = function() report("sync") end,
    -- a chooser, a box or a carried button: B goes back inside the room, not Home
    boxOpen = function() return tc.chooser ~= nil or tc.box ~= nil or tc.carry ~= nil end,
    state = function() return tc end,
    press = function(key)
        if not tc.on then return false end
        if tc.box then return pressBox(key) end
        if tc.chooser then return pressChooser(key) end
        if key == "PADLSHOULDER" or key == "PADRSHOULDER" then                    -- p9: LB / RB = the tabs
            local i = tabIndex(tc.view) + (key == "PADLSHOULDER" and -1 or 1)
            if i >= 1 and i <= #TABS then setTab(TABS[i]) end
            return true
        end
        if tc.carry and key == "PAD2" then tc.carry = nil; report("carry-cancel") return true end
        if tc.focus == "tabs" then return pressTabs(key) end
        if tc.focus == "switches" then return pressSwitches(key) end
        if tc.focus == "sets" then return pressSets(key) end
        if tc.focus == "forms" then return pressForms(key) end
        if tc.focus == "rpages" then return pressRpages(key) end
        if tc.focus == "dpad" then return pressDpad(key) end
        if tc.focus == "cleanup" or tc.focus == "shared" then           -- p19 / p21: A asks (a box that names it all), Up the D-pad, B Home
            if key == "PAD1" then
                local k = tc.focus == "cleanup" and "cleanup" or "cleanshared"
                tc.box = { kind = k }; report("box-" .. k) return true
            end
            if key == "PADDUP" then tc.focus = "dpad"; report("to-dpad") return true end
            local other = tc.focus == "cleanup" and "shared" or "cleanup"
            if (key == "PADDLEFT" and other == "shared") or (key == "PADDRIGHT" and other == "cleanup") then
                if rowThere(other) then tc.focus = other; report("to-" .. other) end
                return true
            end
            if key == "PAD2" then return false end
            return true
        end
        return pressButtons(key)
    end,
    -- TOUCH (the app's own boxes asked first): picks and the ONE actions on the chosen button
    -- a finger's pick moves the cursor with it (item 19: one cursor, where the last choice was made)
    -- p25: Book's quiet enter / leave
    keepMode = function(on) keep.on, keep.set, keep.pos, keep.at = on and true or false, false, false, GetTime() end,
    touchSet = function(k)
        if tc.view == "radial" and not keepOn() then report("not-on-radial") return end  -- p16: the radial has no sets
        if k >= 1 and k <= 4 then
            if keepOn() then keep.set = true end
            tc.set = k
            if k >= 2 then tc.scur = k end
            if tc.on and not tc.chooser and not tc.box and not tc.carry then tc.focus = "sets" end
            report("touch-set")
        end
    end,
    touchPos = function(k)
        if k >= 1 and k <= 8 then
            if keepOn() then keep.pos = true end
            tc.pos = k
            if tc.on and not tc.chooser and not tc.box then tc.focus = "buttons" end
            report("touch-pos")
        end
    end,
    touchPage = function(k)
        if tc.view == "radial" then                                     -- p16: the radial's page (a pick)
            if k >= 1 and k <= 3 then
                tc.rpage = k
                if tc.on and not tc.chooser and not tc.box then tc.focus = "rpages" end
                report("touch-rpage")
            end
            return
        end
        local pm = PM(); if pm and pm.showPage(k) then report("touch-page") end
    end,
    -- p9 (the padtab row): the tab (1-3, a pick), Move's pick-up (a pick), its PLACE / SWAP (ONE game action), its cancel (a pick)
    touchTab = function(k) if TABS[k] then setTab(TABS[k]) end end,
    -- p29: a finger on the form row (padtab 15 = Caster, 16-25 = the forms in the game's order): a pick, no game action
    touchForm = function(k)
        local list = formList()
        if k == 0 or list[k] then
            tc.fcur = k; tc.form = k == 0 and 0 or list[k].id
            if tc.on and not tc.chooser and not tc.box then tc.focus = "forms" end
            report("touch-form")
        end
    end,
    touchCarry = function() if not tc.carry then carryStart() end end,
    touchPlace = function() carryPlace() end,
    -- p20: a finger's Cancel also closes the controller's clean-up box (the room draws that box over the DS tab; nothing done)
    touchCancel = function()
        if tc.carry then tc.carry = nil; report("carry-cancel")
        elseif tc.box and (tc.box.kind == "cleanup" or tc.box.kind == "cleanshared") then tc.box = nil; report("box-closed") end
    end,
    -- p19 (padtab 11 / 12, Book b31): the clean-up's list (its "padclean" document) and the run after the app's own box named it all
    touchCleanList = function() local pm = PM(); if pm and type(pm.cleanupDoc) == "function" then pm.cleanupDoc("ask") end end,
    touchCleanRun = function()
        local pm = PM()
        local ok = pm and type(pm.cleanupRun) == "function" and pm.cleanupRun()
        if tc.box and tc.box.kind == "cleanup" then tc.box = nil end     -- p20: the controller's box, answered by a finger
        if tc.focus == "cleanup" then tc.focus = "dpad" end
        report(ok and "cleaned" or "clean-refused")
    end,
    -- p21 (padtab 13, Book b32): the shared DS macros' run, after the app's own box named them
    touchSharedRun = function()
        local pm = PM()
        local ok = pm and type(pm.sharedRun) == "function" and pm.sharedRun()
        if tc.box and tc.box.kind == "cleanshared" then tc.box = nil end
        if tc.focus == "shared" then tc.focus = "dpad" end
        report(ok and "shared-cleaned" or "shared-refused")
    end,
    -- the touch's one-shot "combat layer" pick (talentn 39, the app sends it in the same touch, before the ONE action)
    combatLayer = function() tc.combatAt = GetTime() end,
    touchClear = function()
        if keepOn() then report("keep-refused") return end                                 -- p25: a restore never clears
        local combat = tc.combatAt and GetTime() - tc.combatAt <= COMBAT_PICK_S
        tc.combatAt = nil
        local h = here()
        if combat then
            if isCombat(h) then act({ kind = "uncombat" }); report("cleared")
            elseif isFace(h) and h.cf then act({ kind = "uncface" }); report("cleared") end
        elseif filled(h) then act({ kind = "clear" }); report("cleared") end
    end,
    -- by touch too, the view the app shows (a no-action pick is not needed: the action's own pick says it); the controller's view is the addon's
    view = function() return tc.view end,
    -- command k (1-11 by the talents row's codes 16-26); right after the one-shot pick `commandsFrom12` (talentn 38, the app sends it in the
    -- same touch) k counts from command 12: the talents row has no column left for more commands
    commandsFrom12 = function() tc.from12 = GetTime() end,
    touchCommand = function(k)
        local pm = PM()
        local now = GetTime()
        if tc.from12 and now - tc.from12 <= 3 then k = k + 11 end
        tc.from12 = nil
        if keepOn() then report("keep-refused") return end                                 -- p25: a restore puts spells only
        if tc.view == "radial" then report("not-on-radial") return end                 -- p16: a radial casts spells
        if tc.combatAt and now - tc.combatAt <= COMBAT_PICK_S then tc.combatAt = nil; report("not-in-combat-view") return end
        local c = pm and pm.commands()[k]
        if c then act({ kind = "command", key = c.key }); report("put") end
    end,
    touchSpell = function(id)
        local combat = tc.combatAt and GetTime() - tc.combatAt <= COMBAT_PICK_S
        tc.combatAt = nil
        if keepOn() then
            -- p25: in the keep mode a spell is ALWAYS an In-combat setting, and only after a fresh set and button pick (a lost pick must
            -- never put it on a wrong button or the game's bar); the picks are used up by this put
            local fresh = keep.set and keep.pos
            keep.set, keep.pos = false, false
            if not fresh then report("keep-no-pick") return end
            if tc.view == "radial" then report("keep-refused") return end
            combat = true
        end
        local slot = type(WC.spellsBookSlotOf) == "function" and WC.spellsBookSlotOf(id)
        if not slot then report("spell-not-in-book") return end
        local h = here()
        if h and h.kind == "fixed" and not (combat and isFace(h)) then report(isFace(h) and "fixed" or "stays-back") return end
        local kind = tc.view == "radial" and "rspell"
            or combat and (isFace(h) and "cface" or "combat") or (isCombat(h) and "otherwise" or "spell")
        if kind == "rspell" and inCombat() then refusedCombat() return end              -- p16: the cells freeze in a fight
        act({ kind = kind, id = id, slot = slot }); report("put")
    end,
    -- p12 THE "extra" PAD ROW (the pad owner's YES 2026-10-07, with ONE mandatory rule): every extra tap needs a FRESH page pick first
    -- (padtab 6 / 7 / 8 = entries from 0 / 40 / 80; one-shot, stale after PICK_S), so a lost pick never puts a wrong entry. The tap then puts
    -- Extra entry page + col + 1 on the chosen button: ONE put out of combat (refused in a fight before any pickup); the answer names it.
    -- p13 THE LEFT BUMPER FROM THE APP (the user on b124: "the main tabs in the controller room move right with R but wont move back left
    -- with L"): on this device LB is the game's emulated Ctrl (GamePadEmulateCtrl = PADLSHOULDER, MEASURED 2026-09-28, AGENT_LOG "BUTTON
    -- TEST ANSWERED"), so the game never hands it to the capture; the app reads the bumper itself (getevent, read-only) and taps padtab 9
    -- on its release. It does what LB does: a box open, nothing; the chooser open, its tab before; else the room's tab before.
    touchShoulder = function(d)
        if not tc.on or tc.box then return end
        local key = d < 0 and "PADLSHOULDER" or "PADRSHOULDER"
        if tc.chooser then pressChooser(key) return end
        local i = tabIndex(tc.view) + (d < 0 and -1 or 1)
        if i >= 1 and i <= #TABS then setTab(TABS[i]) end
    end,
    touchExtraPage = function(base) tc.xpage = { base = base, at = GetTime() } end,
    touchExtra = function(col)
        local pick = tc.xpage
        tc.xpage = nil
        if not pick or GetTime() - pick.at > EXTRA_PICK_S then return false, "no page picked" end
        if tc.view == "radial" then report("not-on-radial") return false, "radial" end   -- p16
        if inCombat() then refusedCombat() return false, "combat" end
        local pm = PM()
        local list = pm and type(pm.extraList) == "function" and pm.extraList() or {}
        local idx = pick.base + col + 1
        local x = list[idx]
        if not x then return false, "no entry", idx end
        local h = here()
        if h and h.kind == "fixed" then report(isFace(h) and "fixed" or "stays-back") return false, "fixed", idx, x end
        if not bindHere(x) then return false, "bind not here", idx, x end
        act((x.kind == "command" or x.kind == "bind") and { kind = x.kind, key = x.key } or { kind = x.kind, id = x.id })
        report("put")
        return true, nil, idx, x
    end,
    -- the app's layout (a no-action pick): true = Nintendo's swapped face diamond
    setLayout = function(swap)
        db().padLayout = swap and "swap" or "plain"
        if tc.on then report("layout") end
    end,
    layout = layout,
}
