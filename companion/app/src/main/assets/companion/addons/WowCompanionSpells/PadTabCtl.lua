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

-- p42 (int/1429): ctl-sets p39-p41 (the In combat chooser's five tabs, the fight-only layer) merged with party-combat / combat-nav
local CV = "p50"                -- bump on every change; p50 THE 1.0.30 TEST BUILD MERGE (code 1453): feat/multi-radial-v2's p49b next to p49, both kept; p49b: SEVERAL SPELL RADIALS back (feat/multi-radial-v2; p40 of 1.0.18): the plates = the radials + New, A on one = its settings (Name / Icon / Opening / Delete), the wheel = its spells + "+", no pages; the radial ops ride Book's padtab 39 + code 5-18 (38 / 39 are rank up / Smart Range now); p48 SMART RANGE: WHO HAS IT (PadMap m148; the user, 2026-11-12: "this option should only appear for classes with next swing pairs- and it should only list the abilities that can work with it in the melee options"; "for druid this should only appear for the bear form action bar, and that's even if there is ranged attacks that bear druid can use. maybe taunt?"): Y / A / touch offer Smart Range only where PadMap's srWhere says (the class; a Druid's Bear Form set, its form passed on to srToggle / srPut / srClear: the "sr-class" / "sr-bear" / "sr-form" notes elsewhere); the Melee half's chooser lists only the next-swing attacks the character knows; a form set's button carries its halves and box (here); no Move of a form's half; p47 SMART RANGE REVIEW (the user, 2026-11-12: "okay so I was skeptical and so I tried it with a different melee skill, wing clip and it did not work"): a Smart Range half's Clear goes straight off like its put (no box: the Clear box named the button's other half and said the wrong result); the box flip and the Melee / Range pick are refused while a button is carried (a stray tap on the Move page changed the hidden target button: "sr-carry"); p46 SMART RANGE (the user, 2026-11-12: "revise the right section and remove the "Otherwise spell", and instead move down the assigned spell and make space for an optional checkbox called "Smart Range". when enabled I want two selectable buttons, one for melee and one for range, and after selecting that button then the bind and move (if applicable) appear ... this should be per button/ button modifier combo"): on the In combat tab Y (PAD4) flips the button's Smart Range box (PadMap m146 srToggle); with it on, A first asks Melee or Range (the small picker, `srp`), then the chooser opens for that half (spells only; its Clear row clears that half); X moves the picked half (onto the target's same half, PadMap srMove); the picked half is `srh` in the doc; by touch padtab 39 is the one-shot Smart Range pick and the padtab column after it says what (0 the box, 1 Melee, 2 Range, 3 the box on: the restore); p45 RANK UP REVIEW (the user, 2026-11-12: "I know we have something in place to rank up abilities on login, but maybe for skills that are already on a bar a prompt to allow them to be ranked up when a new rank is learned is available?"): keepOn() for PadMap m144 (a backup restore's puts never mark a rank "picked on purpose"); p44 (review): the auto form only while the game SHOWS a stance bar (PadMap stanceShown: Ghost Wolf / Travel Form / an aura keep the plain set); a fresh open forgets ANY pick (form, cursor, auto); p44: THE ACTIVE STANCE'S BAR BY DEFAULT (issue #179, 1.0.25: "buttons do not stay mapped on ZR +FACE buttons"; the user's "Build it"): until the player picks a form in the row (tc.formPicked), WoW Defaults' form is the one the player is in while the game lays a stance bar over a set (PadMap m142's OV.fe follows), so that set shows and edits the bar the game presses; the caster picked on purpose stays the caster; In combat keeps the caster until a pick (as before); a fresh room open forgets the pick; p43: ALL FIVE TABS ON EVERY In combat BUTTON (the user, 2026-11-12, on 1429: "In combat still does not show all 5 tabs", then the pick "All 5 on every button"): a base X / Y / A, a form's own set and the bare D-pad with the DS WoW toolbar on too; an Extra / DS entry there goes on the fight layer (PadMap m141 putFight: the face store, the form's set, the slot), WoW Defaults untouched; a binding where no chord of ours can go (a base face, a form's set, the toolbar's D-pad) is refused with its reason (bind-fixed / bind-form / bind-dpad bar); spells only on the radial and out of combat's combat button; p41: THE FIGHT-ONLY LAYER (the user, 2026-11-11: "Build a fight-only layer"; PadMap m132): an Extra / DS entry on the In combat tab goes on the button's fight layer only (PadMap.putFight), the WoW Defaults assignment stays out of combat; its box is a plain Set / Replace on the fight layer (Replace only when a fight spell / fight-layer entry is there, naming it; p40's "replace both" retired); Clear combat spell removes it; p40: an Extra / DS entry on the In combat tab always asks first (Replace, an empty button too): it changes the button out of combat as well, and the box says so; p39: THE IN COMBAT TAB'S CHOOSER HAS ALL FIVE TABS (the user, 2026-11-11, on 1428: "The in combat tab of the controller bindings is missing all 5 pages of options, it only shows the first 3"): Extra and DS on In combat too (a command, binding, mount or item goes on the button itself, a fight spell there gives way); the three spell tabs only where nothing but a fight spell can go (a base X / Y / A, a form's own set, the bare D-pad the DS WoW toolbar holds, the radial; out of combat a combat button, as before); p38: THE D-PAD'S LEFT / RIGHT MOVE THE PICK TOO (the user, 2026-11-11, the party grid: "when one two or three full sized, when 4, bottom 2 consolidate, when 5 the next 2 consolidate" - PadMap m131 moves the pick over the rows with all four directions): Left / Right are locked "moves the pick" places like Up / Down (note locked), no longer kept for raids; p37: THE PARTY MODE TAB ON THE NORMAL LAYERS' PICTURE (party_design.md B, the user's decisions 2026-11-11): the set row (ZL / ZR; ZL + ZR locked), the Touch row (Touch; Hold locked), the two diamonds (Up / Down move the pick, locked; Left / Right kept for raids, locked; A / X / Y as set; B locked) - ppos = the router's 8 positions, pset, ptouch, pfocus tabs / sets / touch / diam / right; the tab only while Party mode is allowed (WC.partyOk); p36: the DS WoW toolbar claims only WoW Defaults' bare D-pad, never In combat's (the user 2026-11-10: "The ds toolbar is overwriting my combat bar and it shouldn't, i should still be able to assign combat keys with it on"; PadMap m44 already casts them in fights); p35: the room bar's Left / Right / Open in no picker (the user 2026-11-10: "the home bar right left and open should be removed"; a button that has one keeps it); p34: the DS tab folded into WoW Defaults (its switch there, the toolbar's four on the bare D-pad; the tab kept for its padtab numbers, never shown) and the chooser's DS tab ("dstool": DS Forever's own commands, out of Extra); p33: the 5th tab, PARTY MODE (PARTY_BUILD.md 4.4: its cursor, chooser and puts); p32: the form row on the out-of-combat tab too (the game's own form bars); p49 STICK BUTTONS (GitHub #78; PadMap m150): WoW Defaults shows L3 / R3 (positions 9 / 10, under the diamonds, between them: GRIDS 9 / 10) for the game bindings only; A on one opens the chooser on Extra with the bindings alone (its only tab), Clear at the end; no Move, no spell, no command (said: "stick-binds"); In combat, the radial and Party never show them (the cursor walks 1-8 there; a tab change off WoW Defaults puts the cursor back on a button); by touch the one-shot talentn 38 pick before a button code 1 / 2 = L3 / R3; p48 SMART RANGE: WHO HAS IT (PadMap m148; the user, 2026-11-12: "this option should only appear for classes with next swing pairs- and it should only list the abilities that can work with it in the melee options"; "for druid this should only appear for the bear form action bar, and that's even if there is ranged attacks that bear druid can use. maybe taunt?"): Y / A / touch offer Smart Range only where PadMap's srWhere says (the class; a Druid's Bear Form set, its form passed on to srToggle / srPut / srClear: the "sr-class" / "sr-bear" / "sr-form" notes elsewhere); the Melee half's chooser lists only the next-swing attacks the character knows; a form set's button carries its halves and box (here); no Move of a form's half; p47 SMART RANGE REVIEW (the user, 2026-11-12: "okay so I was skeptical and so I tried it with a different melee skill, wing clip and it did not work"): a Smart Range half's Clear goes straight off like its put (no box: the Clear box named the button's other half and said the wrong result); the box flip and the Melee / Range pick are refused while a button is carried (a stray tap on the Move page changed the hidden target button: "sr-carry"); p46 SMART RANGE (the user, 2026-11-12: "revise the right section and remove the "Otherwise spell", and instead move down the assigned spell and make space for an optional checkbox called "Smart Range". when enabled I want two selectable buttons, one for melee and one for range, and after selecting that button then the bind and move (if applicable) appear ... this should be per button/ button modifier combo"): on the In combat tab Y (PAD4) flips the button's Smart Range box (PadMap m146 srToggle); with it on, A first asks Melee or Range (the small picker, `srp`), then the chooser opens for that half (spells only; its Clear row clears that half); X moves the picked half (onto the target's same half, PadMap srMove); the picked half is `srh` in the doc; by touch padtab 39 is the one-shot Smart Range pick and the padtab column after it says what (0 the box, 1 Melee, 2 Range, 3 the box on: the restore); p45 RANK UP REVIEW (the user, 2026-11-12: "I know we have something in place to rank up abilities on login, but maybe for skills that are already on a bar a prompt to allow them to be ranked up when a new rank is learned is available?"): keepOn() for PadMap m144 (a backup restore's puts never mark a rank "picked on purpose"); p44 (review): the auto form only while the game SHOWS a stance bar (PadMap stanceShown: Ghost Wolf / Travel Form / an aura keep the plain set); a fresh open forgets ANY pick (form, cursor, auto); p44: THE ACTIVE STANCE'S BAR BY DEFAULT (issue #179, 1.0.25: "buttons do not stay mapped on ZR +FACE buttons"; the user's "Build it"): until the player picks a form in the row (tc.formPicked), WoW Defaults' form is the one the player is in while the game lays a stance bar over a set (PadMap m142's OV.fe follows), so that set shows and edits the bar the game presses; the caster picked on purpose stays the caster; In combat keeps the caster until a pick (as before); a fresh room open forgets the pick; p43: ALL FIVE TABS ON EVERY In combat BUTTON (the user, 2026-11-12, on 1429: "In combat still does not show all 5 tabs", then the pick "All 5 on every button"): a base X / Y / A, a form's own set and the bare D-pad with the DS WoW toolbar on too; an Extra / DS entry there goes on the fight layer (PadMap m141 putFight: the face store, the form's set, the slot), WoW Defaults untouched; a binding where no chord of ours can go (a base face, a form's set, the toolbar's D-pad) is refused with its reason (bind-fixed / bind-form / bind-dpad bar); spells only on the radial and out of combat's combat button; p41: THE FIGHT-ONLY LAYER (the user, 2026-11-11: "Build a fight-only layer"; PadMap m132): an Extra / DS entry on the In combat tab goes on the button's fight layer only (PadMap.putFight), the WoW Defaults assignment stays out of combat; its box is a plain Set / Replace on the fight layer (Replace only when a fight spell / fight-layer entry is there, naming it; p40's "replace both" retired); Clear combat spell removes it; p40: an Extra / DS entry on the In combat tab always asks first (Replace, an empty button too): it changes the button out of combat as well, and the box says so; p39: THE IN COMBAT TAB'S CHOOSER HAS ALL FIVE TABS (the user, 2026-11-11, on 1428: "The in combat tab of the controller bindings is missing all 5 pages of options, it only shows the first 3"): Extra and DS on In combat too (a command, binding, mount or item goes on the button itself, a fight spell there gives way); the three spell tabs only where nothing but a fight spell can go (a base X / Y / A, a form's own set, the bare D-pad the DS WoW toolbar holds, the radial; out of combat a combat button, as before); p38: THE D-PAD'S LEFT / RIGHT MOVE THE PICK TOO (the user, 2026-11-11, the party grid: "when one two or three full sized, when 4, bottom 2 consolidate, when 5 the next 2 consolidate" - PadMap m131 moves the pick over the rows with all four directions): Left / Right are locked "moves the pick" places like Up / Down (note locked), no longer kept for raids; p37: THE PARTY MODE TAB ON THE NORMAL LAYERS' PICTURE (party_design.md B, the user's decisions 2026-11-11): the set row (ZL / ZR; ZL + ZR locked), the Touch row (Touch; Hold locked), the two diamonds (Up / Down move the pick, locked; Left / Right kept for raids, locked; A / X / Y as set; B locked) - ppos = the router's 8 positions, pset, ptouch, pfocus tabs / sets / touch / diam / right; the tab only while Party mode is allowed (WC.partyOk); p36: the DS WoW toolbar claims only WoW Defaults' bare D-pad, never In combat's (the user 2026-11-10: "The ds toolbar is overwriting my combat bar and it shouldn't, i should still be able to assign combat keys with it on"; PadMap m44 already casts them in fights); p35: the room bar's Left / Right / Open in no picker (the user 2026-11-10: "the home bar right left and open should be removed"; a button that has one keeps it); p34: the DS tab folded into WoW Defaults (its switch there, the toolbar's four on the bare D-pad; the tab kept for its padtab numbers, never shown) and the chooser's DS tab ("dstool": DS Forever's own commands, out of Extra); p33: the 5th tab, PARTY MODE (PARTY_BUILD.md 4.4: its cursor, chooser and puts); p32: the form row on the out-of-combat tab too (the game's own form bars)
-- the eight on a grid for the D-pad (the SPEC's two diamonds side by side): column, row; positions 1-4 the D-pad's left / up / right / down
local GRIDS = {
    plain = { { 0, 1 }, { 1, 0 }, { 2, 1 }, { 1, 2 }, { 3, 1 }, { 4, 0 }, { 5, 1 }, { 4, 2 }, { 2, 2 }, { 3, 2 } },   -- 5-8 left / top / right / bottom
    swap  = { { 0, 1 }, { 1, 0 }, { 2, 1 }, { 1, 2 }, { 4, 0 }, { 3, 1 }, { 4, 2 }, { 5, 1 }, { 2, 2 }, { 3, 2 } },   -- Nintendo: 5 top, 6 left, 7 bottom, 8 right
}
-- p49 (PadMap m150): positions 9 / 10 = L3 / R3, on WoW Defaults only (the In combat tab, the radial, Party: the eight)
GRIDS.STICK = { [9] = true, [10] = true }
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
-- p33 PARTY MODE (PARTY_BUILD.md 4.4): the 5th tab, "party" (PadMap m77's slots, Y's In combat action, Touch, Smart Y's party list)
local TABS = { "normal", "ds", "combat", "radial", "party" }
-- each tab's switches ("as drawn"); "rmode" (the radial's Hold / Tap) is A on the DS tab's Up
-- p34 (the user 2026-11-10: "consolidate the Controller menu. move the DS wow toolbar check box to the Wow defaults tab, when its
-- selected replace those buttons with the DS WoW toolbar buttons, when uncheck it goes back to the normal D-pad skills"): the switch is
-- WoW Defaults' own; "ds" stays in TABS (every padtab column keeps its number) but is never shown or stepped onto
local SWITCHES = { normal = { "dpad" }, ds = { "dpad" }, combat = { "cc", "sa" }, radial = {}, party = {} }
-- the rows from the top, per tab
-- p19: the DS tab's "cleanup" row (PadMap m41 S1, the user's pick 2026-10-09: "a 'Clean up this character' button in the Controller
-- room's DS tab lists exactly what goes; one yes per character"): under the D-pad, only while the plan has something
-- p21: "shared" (PadMap m42, the user's answer 2026-10-09 "Last, separately"): the shared DS macros' own button, beside the clean-up's on
-- the left page's bottom (shared left, cleanup right); Left / Right between the two, Up back to the D-pad
-- p29 (f-combat-forms): the In combat tab's "forms" row (Caster | the character's forms / stances), only while it has forms
-- p32 (the user's pick A on test20): the out-of-combat tab's form row too, the GAME's own form bars (PadMap m57), while the game puts one
local ORDER = { normal = { "tabs", "switches", "forms", "sets", "buttons" }, ds = { "tabs", "switches", "dpad" }, combat = { "tabs", "switches", "forms", "sets", "buttons" },
                radial = { "tabs", "rpages", "buttons" }, party = { "tabs", "psets", "ptouch", "pdiam" } }     -- p37: the party tab's rows
local HOME_FOCUS = { normal = "buttons", ds = "dpad", combat = "buttons", radial = "rpages", party = "pdiam" }
local function SR() return type(WC.spellRadial) == "table" and WC.spellRadial or nil end
-- p40 SEVERAL SPELL RADIALS (the user's picks 2026-11-08: "create new radial on that tab ... select them from a list to edit each"; "A on its
-- plate": the settings; "no empty slots ... evenly distributed"): the plates row = the character's radials (slots, Radial.lua q2) + "New"
-- (while fewer than 6); the cursor LANDING on a radial shows it (p10's rule); A on a radial = its settings on the right page (focus "rset":
-- 1 Name, 2 Icon, 3 Opening (Hold / Tap), 4 Delete; Up / Down, A acts, B back to the plates); A on New makes one. The wheel = the
-- radial's spells in order + a "+" place while fewer than 8 (Left / Right round it, Up the plates). Name, Icon and Opening are the APP's
-- (per character, per radial): A on Name / Icon opens a box ("rname" / "ricon") whose presses go to the app as events (`rev`), until the
-- app says it is done (touch radial op 13); A on Opening is an event the app acts on. Delete: A, then A again within DEL_S.
local DEL_S = 3
local function rSlots() local sr = SR(); return (sr and type(sr.slots) == "function") and sr.slots() or {} end
local function rCount(r) local sr = SR(); return (sr and type(sr.count) == "function") and sr.count(r) or 0 end
-- the wheel's places: its spells, then the "+" while there is room
local function rShown(r) local n = rCount(r); return n + ((n < 8) and 1 or 0) end
-- the plates' entries: each radial's slot, then "new" while there is room
local function rPlates() local out = rSlots(); if #out < 6 then out[#out + 1] = "new" end return out end

-- `view`: the tab ("normal" = WoW Defaults, "ds", "combat" = In combat)
local tc = { on = false, focus = "buttons", set = 1, pos = 1, chooser = nil, box = nil, note = nil, view = "normal",
             tcur = 1, sw = 1, scur = 2, dpos = 2, carry = nil, rpage = 1, form = 0, fcur = 0, formPicked = false, ppos = 8, pright = 1, pset = 1, ptouch = 1,
             rplate = 1, rrow = 1, revn = 0 }
local PT = {}                   -- p33: the Party tab's functions (filled at the end of this file)
local PFOCUS = { tabs = "tabs", psets = "sets", ptouch = "touch", pdiam = "diam", pright = "right" }      -- p37: the doc's pfocus
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
-- p34: the tabs the room shows; LB / RB / the tab row step over the hidden one
local HIDDEN = { ds = true }
-- p37 (party_design.md B): the Party mode tab shows only while Party mode is allowed (Hub's experimental switch: WC.partyOk)
local function hidden(v) return HIDDEN[v] == true or (v == "party" and WC.partyOk ~= true) end
local shownSet                  -- below
local function tabStep(i, d)
    local j = i + d
    while TABS[j] and hidden(TABS[j]) do j = j + d end
    return TABS[j] and j or nil
end
-- p34: the bare D-pad is the DS WoW toolbar's while it is on (WoW Defaults and In combat show its four there): nothing goes on it
local function toolbarHas(pos)
    if tc.view ~= "normal" or pos > 4 or shownSet() ~= 1 then return false end      -- p36: In combat's D-pad takes fight spells
    local pm = PM()
    local ok, st = pcall(function() return pm and type(pm.dpadState) == "function" and pm.dpadState() end)
    return ok and type(st) == "table" and st.want == true
end

-- the set a press is in: a held trigger's (the game's active set) wins over the one chosen by touch or the set buttons
-- p26 (b-bind-hold-triggers, the user 2026-10-13: "after choosing Bind while holding ZL / ZR, the layer should stay held until the bind
-- completes, even if the triggers are released"): the set is LATCHED when the chooser opens (tc.lock) and kept while the chooser or its
-- box is up; releasing the trigger no longer drops the spell onto the base set
shownSet = function()
    if tc.lock and (tc.chooser or tc.box) then return tc.lock end
    if type(WC.spellsActiveSet) == "function" then
        local ok, a = pcall(WC.spellsActiveSet)
        if ok and type(a) == "number" and a >= 2 and a <= 4 then return a end
    end
    return tc.set
end

local rows                      -- below: the chooser's rows (the report names the one under the cursor)
local srHalfNow                 -- p46 below: the Smart Range half picked on the button under the cursor
local fightView     -- p26 below: the chooser's layout (p28: the tab's own; the user's pick "No flip in the chooser")
-- p40 the radial on show (tc.rpage = its slot): its spells in order
local function radialPage()
    local sr = SR()
    return (sr and type(sr.ids) == "function" and sr.has(tc.rpage)) and sr.ids(tc.rpage) or {}
end
local formList                  -- below
-- p44 (issue #179): no form picked yet = WoW Defaults shows the ACTIVE form's bar while the game lays a stance bar over a set
-- (tc.formAuto = the form put there this way; In combat and out of every stance it goes back to the caster's)
local function formAuto()
    if tc.formPicked then return end
    local want, at = 0, 0
    local pm = PM()
    local shown = (tc.view == "normal" and pm and type(pm.stanceShown) == "function") and pm.stanceShown() or nil
    if shown then
        for k, f in ipairs(formList()) do if f.id == shown then want, at = f.id, k end end
    end
    if tc.form ~= 0 and tc.form ~= tc.formAuto then return end          -- a form set some other way counts as a pick
    if want ~= tc.form then tc.form, tc.fcur = want, at end
    tc.formAuto = want ~= 0 and want or nil
end
-- p40 keep the radial cursor on things that exist: the slot shown, the plate cursor, the wheel place
local function rFix()
    local sr = SR()
    if not sr or type(sr.has) ~= "function" then return end
    if not sr.has(tc.rpage) then tc.rpage = rSlots()[1] or 0 end
    local plates = rPlates()
    if tc.rplate < 1 or tc.rplate > #plates then
        tc.rplate = 1
        for i, v in ipairs(plates) do if v == tc.rpage then tc.rplate = i end end
    end
    if tc.rpage == 0 and (tc.focus == "buttons" or tc.focus == "rset") then tc.focus = "rpages" end
    tc.pos = math.max(1, math.min(tc.pos, math.max(1, rShown(tc.rpage))))
end
-- an event for the app (`rev`: what, numbered so the same one twice is two)
local function rEvent(what) tc.revn = (tc.revn or 0) + 1; tc.rev = what end
local function report(note)
    tc.note = note or tc.note
    formAuto()
    if tc.view == "radial" then rFix() end
    local pm = PM()
    -- p32: the form chosen on the out-of-combat tab is the one whose own bar the room shows and edits (PadMap m57); else the caster's
    if pm and type(pm.formEdit) == "function" then
        pcall(pm.formEdit, (tc.on and tc.view == "normal" and tc.form and tc.form ~= 0) and tc.form or 0)
    end
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
        carry = tc.carry and { set = tc.carry.set, pos = tc.carry.pos, page = tc.carry.page, h = tc.carry.h } or nil,
        srp = tc.srp, srh = (srHalfNow and srHalfNow()) or nil,         -- p46: the Smart Range picker's cursor, the picked half
        rpage = tc.view == "radial" and tc.rpage or nil, rad = tc.view == "radial" and radialPage() or nil,
        -- p40: the radials (slots), the plate cursor, the settings row, an open Name / Icon box, the last event, a Delete waiting
        rslots = tc.view == "radial" and rSlots() or nil, rplate = tc.view == "radial" and tc.rplate or nil,
        rrow = tc.view == "radial" and tc.rrow or nil,
        rbox = (tc.view == "radial" and tc.box and (tc.box.kind == "rname" or tc.box.kind == "ricon")) and tc.box.kind or nil,
        revn = tc.view == "radial" and tc.revn or nil, rev = tc.view == "radial" and tc.rev or nil,
        rdel = (tc.view == "radial" and tc.rdel and GetTime() - tc.rdel <= DEL_S) and true or nil,
        form = tc.form ~= 0 and tc.form or nil, fcur = tc.view == "combat" and tc.fcur or nil,
        ppos = tc.view == "party" and tc.ppos or nil, pright = tc.view == "party" and (tc.pright - 1) or nil,   -- 0-based (the app's)
        -- p37: the party tab's places (the router's 8 positions), its layer on show, the Touch row's tile, where the cursor is
        pset = tc.view == "party" and PT.shown and PT.shown() or nil, ptouch = tc.view == "party" and tc.ptouch or nil,
        pfocus = tc.view == "party" and (PFOCUS[tc.focus] or "diam") or nil } }))
    WC.push()
end

local function here()
    if tc.view == "radial" then                                     -- p40: place pos on the radial shown (the "+" after its spells)
        local sr = SR()
        local id = sr and sr.has(tc.rpage) and sr.get(tc.rpage, tc.pos) or nil
        return { kind = id and "spell" or "none", id = id, s = tc.pos, r = tc.rpage, radial = true, add = id == nil }
    end
    local pm = PM()
    if not pm then return nil end
    -- p29: a FORM chosen on the In combat tab: the bare set's buttons are that form's own In-combat spells (B stays the game's Back)
    if tc.view == "combat" and tc.form ~= 0 and shownSet() == 1 and tc.pos ~= 7 and type(pm.formFight) == "function" then
        local e = pm.formFight(tc.form, tc.pos)
        -- p48 (PadMap m148): a Bear Form set's Smart Range halves (sr / h / rg) and its box
        local rg = e and type(e.rg) == "table" and { id = e.rg.id, n = e.rg.n, i = e.rg.i } or nil
        return { kind = e and "combat" or "none", form = tc.form, pos = tc.pos,
                 sr = (type(pm.srFormFlag) == "function" and pm.srFormFlag(tc.form, tc.pos)) or nil,
                 c = e and { id = (e.t == nil) and e.id or nil, n = e.n, i = e.i, t = e.t, key = e.key,
                             sr = e.sr, h = e.h, rg = rg } or nil }   -- p43: `t` / `key`
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
-- the base set's X / Y / A (PadMap m7, the user 2026-10-01 "Yes, per button"): in the combat view they take a spell for fights (no macro);
-- B (7) stays the game's Back
local function isFace(slot) return slot and slot.kind == "fixed" and slot.face == true end
-- p39 (the user 2026-11-11, on 1428: "The in combat tab of the controller bindings is missing all 5 pages of options, it only shows the
-- first 3") and p43 (the user 2026-11-12, on 1429: "In combat still does not show all 5 tabs", then the pick "All 5 on every button"):
-- the In combat tab's chooser has ALL FIVE tabs on EVERY button - a base X / Y / A, a form's own set, the bare D-pad with or without the
-- DS WoW toolbar, every page and ZL / ZR / ZL + ZR set. An Extra / DS entry goes on that button's FIGHT LAYER only (PadMap putFight,
-- m132 / m141); its WoW Defaults assignment never changes. Only three spell tabs where nothing but a spell can go: the radial (it casts
-- spells) and, out of combat, a combat button (its Otherwise spell). The app's ChooserTabs.spellsOnly is this same rule, same inputs
local function onlySpells()
    if tc.view == "radial" then return true end
    if tc.chooser and tc.chooser.srh then return true end              -- p46: a Smart Range half takes spells only
    if fightView() then return false end
    return isCombat(here())
end

-- p11 THE CHOOSER'S TABS (the user 2026-10-07: "in the controller menu, when assigning a spell, the spell menu needs some tabs. start on spells, then general
-- (including professions), then extra"): Spells (the class's lines), General (the book's General tab, its professions too), Extra (the commands).
-- LB / RB, or its tab row (Up from the first row; Left / Right OPEN the tab the cursor lands on, p10's rule; Down / A back to the rows). A
-- fight spell's chooser has no Extra: a command is no fight spell.
-- p14 (the user 2026-10-07: "instead of putting professions on a second page of general, give those buttons their own tab"): Professions is a tab of the chooser too
-- p34 (the user 2026-11-10: "add a 4th skill assignment category where we can add the DS toolbar buttons to any button slot"): "dstool",
-- DS Forever's own commands (the toolbar's Chat / Radial / Map / Home bar, the spell radial, the room bar's), out of Extra; the same
-- commands by the same "extra" row (the app counts them in the padmap doc's own order), so only the lists split
local CTABS = { "spells", "general", "professions", "extra", "dstool" }
local function dsKey(k) return type(k) == "string" and k:sub(1, 2) == "DS" end
-- p35: the room bar's own three (the Home Bar command opens the bar and the D-pad moves it, as the toolbar's Down does)
local function barKey(k) return type(k) == "string" and k:sub(1, 5) == "DSBAR" end
local function ctabs()
    if GRIDS.STICK[tc.pos] and tc.view == "normal" then return { "extra" } end      -- p49: a stick takes the game bindings only
    if onlySpells() then return { "spells", "general", "professions" } end
    return CTABS
end
local function ctabIndex(list)
    for i, v in ipairs(ctabs()) do if v == list then return i end end
    return 1
end

-- the chooser's rows: the book's spells or the commands, then "Clear this button" on a filled button
-- in the COMBAT view: spells only (the combat spell), then "Clear combat spell" on a combat button; out of combat on a COMBAT button: spells
-- only (its "Otherwise" spell), then "Clear this button" (both go)
rows = function()
    if tc.view == "party" and PT.rows then return PT.rows() end         -- p33
    local out = {}
    if GRIDS.STICK[tc.pos] and tc.view == "normal" then                 -- p49: L3 / R3: the Extra list's bindings, then Clear
        local pm = PM()
        for _, x in ipairs(pm and type(pm.extraList) == "function" and pm.extraList() or {}) do
            if x.kind == "bind" then out[#out + 1] = { kind = "bind", key = x.key } end
        end
        if filled(here()) then out[#out + 1] = { kind = "clear" } end
        return out
    end
    local h = here()
    local spellKind = tc.view == "radial" and "rspell"
        or fightView() and (isFace(h) and "cface" or "combat") or (isCombat(h) and "otherwise" or "spell")
    -- the combat view leaves out the spells already on the button (its own spell, its combat and its otherwise spell): the same spell
    -- in and out of combat would only spend a macro
    local skip = {}
    local srh = tc.chooser and tc.chooser.srh or nil                    -- p46: the Smart Range half the chooser fills
    if fightView() and h and srh then
        local c = (h.kind == "fixed" and h.cf) or (h.kind == "combat" and h.c) or nil
        local half = c and c.sr and ((srh == "m" and c.h ~= "r" and c) or (srh == "r" and (c.h == "r" and c or c.rg))) or nil
        if half and half.id then skip[half.id] = true end
    elseif fightView() and h then
        if h.kind == "spell" and h.id then skip[h.id] = true end
        if h.c and h.c.id then skip[h.c.id] = true end
        if h.o and h.o.id and not h.o.item then skip[h.o.id] = true end
        if h.cf and h.cf.id then skip[h.cf.id] = true end
    end
    -- (p24: p8's class "suggestion" row is gone - the user: nothing is ever suggested; the chooser is the player's own spells only)
    local list = tc.chooser and tc.chooser.list or "spells"
    if (list == "extra" or list == "dstool") and onlySpells() then list = "spells" end
    if list ~= "extra" and list ~= "dstool" then
        if type(WC.spellsBindableList) == "function" then
            for _, e in ipairs(WC.spellsBindableList()) do
                local tab = e.prof and "professions" or e.gen and "general" or "spells"
                -- p48 (the user: "it should only list the abilities that can work with it in the melee options"): the Melee half's
                -- chooser lists only next-swing attacks (PadMap's srSwingKnown; the put still refuses any other: "swing")
                local swingOk = srh ~= "m" or (fightView() and type(PM().srSwingKnown) == "function" and PM().srSwingKnown(e.id))
                if not skip[e.id] and tab == list and swingOk then
                    out[#out + 1] = { kind = spellKind, id = e.id, slot = e.slot, srh = srh }
                end
            end
        end
    else
        -- p12 EXTRA (the user's picks 2026-10-07): the commands, then the character's mounts, then the bags' usable items (PadMap m33's
        -- extraList: the padmap doc carries the same three lists in the same order, so the app and the "extra" pad row count alike)
        local pm = PM()
        local list = pm and type(pm.extraList) == "function" and pm.extraList() or {}
        if #list == 0 then for _, c in ipairs(pm and pm.commands() or {}) do list[#list + 1] = { kind = "command", key = c.key } end end
        local want = tc.chooser and tc.chooser.list or "extra"
        for _, x in ipairs(list) do
            local ds = x.kind == "command" and dsKey(x.key)                 -- p34: the DS tab's, else Extra's
            if (want == "dstool") ~= (ds == true) or (x.kind == "command" and barKey(x.key)) then
            elseif x.kind == "command" or x.kind == "bind" then out[#out + 1] = { kind = x.kind, key = x.key }     -- p15: the game's bindings
            else out[#out + 1] = { kind = x.kind, id = x.id, key = (x.kind == "mount" and "m:" or "i:") .. tostring(x.id), n = x.n } end
        end
    end
    if fightView() and srh then
        local c = h and ((h.kind == "fixed" and h.cf) or (h.kind == "combat" and h.c)) or nil
        local filledHalf = c and c.sr and ((srh == "m" and c.h ~= "r") or (srh == "r" and (c.h == "r" or c.rg ~= nil)))
        if filledHalf then out[#out + 1] = { kind = isFace(h) and "uncface" or "uncombat", srh = srh } end
    elseif fightView() then
        if isCombat(h) then out[#out + 1] = { kind = "uncombat" }
        elseif isFace(h) and h.cf then out[#out + 1] = { kind = "uncface" } end
    elseif filled(here()) then out[#out + 1] = { kind = "clear" } end
    return out
end

-- ---------------------------------------------------------------- doing it

local function act(thing, fightTouch)
    if tc.view == "radial" then                                     -- p16: the spell radial's slice (SpellRadial.lua)
        local sr, h = SR(), here()
        if sr and h and sr.has(h.r) then
            local ok, why = true, nil
            if thing.kind == "rspell" then ok, why = sr.put(h.r, math.min(h.s, sr.count(h.r) + 1), thing.id)
            elseif thing.kind == "clear" then ok, why = sr.clear(h.r, h.s) end
            if not ok and why then tc.note = "radial-" .. tostring(why) end
        end
        tc.box, tc.chooser, tc.focus = nil, nil, "buttons"
        return
    end
    local pm = PM()
    if not pm then return end
    local page, set, pos = pm.page(), shownSet(), tc.pos
    -- p46 SMART RANGE: the picked half takes the spell (or is cleared); a refusal is the room's note ("Melee needs a next-swing attack")
    if thing.srh and type(pm.srPut) == "function" and (thing.kind == "combat" or thing.kind == "cface" or thing.kind == "uncombat"
        or thing.kind == "uncface") then
        local ok, why
        local hf = here()                                                -- p48: a Bear Form set's place (its form)
        local form = hf and hf.form or nil
        if thing.kind == "uncombat" or thing.kind == "uncface" then ok, why = pm.srClear(page, set, pos, thing.srh, form)
        else ok, why = pm.srPut(page, set, pos, thing.srh, thing.slot, form) end
        tc.srNote = (not ok) and ("sr-" .. tostring(why)) or nil
        tc.box, tc.chooser, tc.focus = nil, nil, "buttons"
        return
    end
    -- p29: with a form chosen (the In combat tab, the bare set) a fight spell is THAT form's own (PadMap m55)
    local h = here()
    local whole = thing.kind == "command" or thing.kind == "bind" or thing.kind == "item" or thing.kind == "mount"
    -- p43 (the user 2026-11-12: "All 5 on every button"): a form's set / a base X / Y / A take the fight layer's whole-button thing too
    -- (PadMap m141: putFight with the form, or set 1's face position)
    if whole and (fightView() or fightTouch) and h and (h.form or isFace(h)) and type(pm.putFight) == "function" then
        pm.putFight(page, set, pos, thing.kind, (thing.kind == "command" or thing.kind == "bind") and thing.key or thing.id, h.form)
        tc.box, tc.chooser, tc.focus = nil, nil, "buttons"
        return
    end
    if h and h.form and type(pm.putCombatForm) == "function" then
        if thing.kind == "combat" or thing.kind == "cface" then pm.putCombatForm(h.form, pos, thing.slot)
        elseif thing.kind == "uncombat" or thing.kind == "uncface" then pm.clearCombatForm(h.form, pos) end
        tc.box, tc.chooser, tc.focus = nil, nil, "buttons"
        return
    end
    -- p41 THE FIGHT-ONLY LAYER (the user, 2026-11-11: "Build a fight-only layer"; PadMap m132): an Extra / DS entry put on the In combat
    -- tab goes on the button's FIGHT LAYER only (PadMap.putFight); its WoW Defaults assignment stays as it is out of combat, and a fight
    -- spell there gives way (one fight-layer entry per button: the newer wins)
    if whole and (fightView() or fightTouch) and type(pm.putFight) == "function" then
        pm.putFight(page, set, pos, thing.kind, (thing.kind == "command" or thing.kind == "bind") and thing.key or thing.id)
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
    if thing.srh then return nil end                                   -- p46: a Smart Range half's spell goes straight on; p47: its Clear too
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
    -- p41 (the user, 2026-11-11: "Build a fight-only layer"; p40's "replace both" box retired): on the In combat tab an Extra / DS
    -- entry goes on the fight layer only, so it asks only when the fight layer already holds something there (a fight spell or another
    -- whole-button entry: Replace names that one); on an empty fight layer it goes straight on
    local whole = thing.kind == "command" or thing.kind == "bind" or thing.kind == "item" or thing.kind == "mount"
    if whole and fightView() then
        local h = here()                                                -- p43: a base X / Y / A's fight action (cf) counts too
        return (not afterReplace and (isCombat(h) or (isFace(h) and h.cf ~= nil))) and "replace" or nil
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
    local h = here()
    if h and h.form then ok, why = false, "form"                         -- p43 (PadMap m141): a form's set has no key of its own
    elseif pm and type(pm.bindAllowed) == "function" then ok, why = pm.bindAllowed(shownSet(), tc.pos) end
    if not ok then report("bind-" .. tostring(why)) end
    return ok
end

local function choose(thing)
    if inCombat() then refusedCombat() return end
    if not bindHere(thing) then return end
    local kind = boxFor(thing, false)
    if kind then tc.box = { kind = kind, thing = thing }; report("box-" .. kind)
    else act(thing); report(tc.srNote or "put"); tc.srNote = nil end
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
    report(tc.srNote or (b.kind == "clear" and "cleared" or "put"))
    tc.srNote = nil
end

-- ---------------------------------------------------------------- p46 SMART RANGE (PadMap m146)
-- the button's place for Smart Range (the In combat tab; a form's own set and B are not offered): page, set, pos; else nil, the note
-- p48 (PadMap m148): the form whose own set is on show (0 = none); a Smart Range place is page, set, pos and this form
local function srFormHere()
    if tc.view == "combat" and tc.form ~= 0 and shownSet() == 1 and not keepOn() then return tc.form end
    return 0
end
local function srCur()
    if tc.view ~= "combat" and not keepOn() then return nil, "sr-view" end
    if shownSet() == 1 and tc.pos == 7 then return nil, "stays-back" end
    local pm = PM()
    if not pm or type(pm.srToggle) ~= "function" then return nil, "sr-old" end
    local form = srFormHere()
    if type(pm.srWhere) == "function" then
        local ok, why = pm.srWhere(shownSet(), tc.pos, form)
        if not ok then return nil, "sr-" .. tostring(why) end
    elseif form ~= 0 then return nil, "sr-form" end
    return pm.page(), shownSet(), tc.pos, form ~= 0 and form or nil
end
local function srOn(h) return h ~= nil and h.sr == true end
srHalfNow = function()
    local s, pm = tc.srh, PM()
    if s and pm and s.page == pm.page() and s.set == shownSet() and s.pos == tc.pos and (s.f or 0) == srFormHere() then return s.h end
    return nil
end
-- the halves a button's view carries: melee, range (the documents' refs), nil when the box is off
local function srHalves(h)
    local c = h and ((h.kind == "fixed" and h.cf) or (h.kind == "combat" and h.c)) or nil
    if not (c and c.sr) then return nil, nil end
    if c.h == "r" then return nil, c end
    return c, c.rg
end
-- the half picked on THIS button ("m" / "r"), nil = none
local function srHalf() return srHalfNow() end
local function srToggle(want)
    if inCombat() then refusedCombat() return end
    if tc.carry then report("sr-carry") return end                     -- p47: never the carried-to button, unseen
    local page, set, pos, form = srCur()
    if not page then report(set) return end
    local ok, why = PM().srToggle(page, set, pos, want, form)
    tc.srh, tc.srp = nil, nil
    report(ok and "sr-toggled" or ("sr-" .. tostring(why)))
end
-- the small Melee / Range picker A opens (1 Melee, 2 Range): A picks and opens the chooser for that half, B closes it
local function pressSrPick(key)
    if key == "PADDLEFT" or key == "PADDRIGHT" then tc.srp = key == "PADDLEFT" and 1 or 2; report("sr-picker")
    elseif key == "PAD1" then
        local page, set, pos, form = srCur()
        tc.srp = (tc.srp == 2) and 2 or 1
        if not page then tc.srp = nil; report(set) return true end
        tc.srh = { page = page, set = set, pos = pos, h = tc.srp == 1 and "m" or "r", f = form }
        tc.srp = nil
        if inCombat() then refusedCombat() return true end
        tc.lock = shownSet()
        tc.chooser = { list = "spells", row = 1, srh = tc.srh.h }
        report("chooser")
    elseif key == "PAD2" then tc.srp = nil; report("sr-picker-closed")
    elseif key == "PADLSHOULDER" or key == "PADRSHOULDER" then tc.srp = nil; return false
    end
    return true
end

-- ---------------------------------------------------------------- p9: tabs, switches, Move

local function setTab(v)
    if hidden(v) then v = "normal" end                                   -- p34: an old padtab 1 (the DS tab) opens WoW Defaults
    tc.view = v
    if v ~= "normal" and GRIDS.STICK[tc.pos] then tc.pos = tc.pos == 9 and 4 or 8 end     -- p49: the sticks are WoW Defaults' only
    tc.carry, tc.chooser, tc.box, tc.srp = nil, nil, nil, nil
    tc.tcur, tc.sw = tabIndex(v), 1
    if tc.focus ~= "tabs" then tc.focus = HOME_FOCUS[v] end
    report("tab")
end

-- one row up / down in the tab's ORDER (the carried button stays among the sets and the buttons)
-- p19 / p21: the DS tab's bottom rows are there only while they have something to do
formList = function()
    local pm = PM()
    return (pm and type(pm.forms) == "function") and pm.forms() or {}
end
local function rowThere(f)
    local pm = PM()
    if not pm then return false end
    if f == "forms" then                                                -- p29: a class without forms has no form row
        if #formList() == 0 then return false end
        -- p32: on the out-of-combat tab only while the game lays a form bar over a set (its Controller setting is not "None")
        return tc.view ~= "normal" or (type(pm.stance) == "function" and pm.stance() ~= nil)
    end
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
    if tc.focus == "psets" then tc.scur = ((tc.pset or 1) >= 2 and tc.pset) or 2 end           -- p37
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
        tc.carry = { radial = true, s = h.s, r = tc.rpage, page = tc.rpage, set = 1, pos = tc.pos }
        report("carry")
        return
    end
    if h and h.kind == "bind" then report("bind-move") return end         -- p15: a binding rides the key, not the bar
    if tc.view == "combat" and srOn(h) then                             -- p46: X moves the PICKED half (a base X / Y / A's stay: no Move there)
        if isFace(h) or h.form then report("locked") return end            -- p48: nor a Bear Form set's half
        local hh = srHalf()
        if not hh then report("sr-pick-half") return end
        local m, g = srHalves(h)
        if not ((hh == "m" and m) or (hh == "r" and g)) then report("nothing-to-move") return end
        if inCombat() then refusedCombat() return end
        local pm = PM()
        tc.carry = { page = pm and pm.page() or 1, set = shownSet(), pos = tc.pos, h = hh }
        report("carry")
        return
    end
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
        if h.s == c.s or h.r ~= c.r or h.kind == "none" then tc.carry = nil; report("carry-cancel") return end    -- p40: onto the "+": back
        if inCombat() then refusedCombat() return end
        local swap = true
        local ok = sr.move(c.r, c.s, h.s)
        tc.carry = nil
        report(ok and (swap and "swapped" or "moved") or "move-refused")
        return
    end
    local pm = PM()
    if not pm then return end
    local h = here()
    if not h then return end
    if c.h and type(pm.srMove) == "function" then                      -- p46: a Smart Range half onto this button's same half
        local page, set = pm.page(), shownSet()
        if c.page == page and c.set == set and c.pos == tc.pos then tc.carry = nil; report("carry-cancel") return end
        if set == 1 and tc.pos == 7 then report("locked") return end
        if inCombat() then refusedCombat() return end
        local swap = select(c.h == "m" and 1 or 2, srHalves(h)) ~= nil
        local ok, why = pm.srMove(c.page, c.set, c.pos, c.h, page, set, tc.pos)
        tc.carry = nil
        if ok then tc.srh = { page = page, set = set, pos = tc.pos, h = c.h } end
        report(ok and (swap and "swapped" or "moved") or ("sr-" .. tostring(why)))
        return
    end
    if h.kind == "fixed" then report("locked") return end
    if h.kind == "bind" or h.stick then report("bind-move") return end    -- p15; p49: nothing is carried onto a stick
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
    for i = 1, n or (tc.view == "normal" and #grid or 8) do             -- p49: the sticks (9 / 10) on WoW Defaults only
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
    if tc.view == "radial" and DIRS[key] then                       -- p40: round the wheel (its places, wrapping); Up = the plates
        if key == "PADDLEFT" or key == "PADDRIGHT" then
            local n = math.max(1, rShown(tc.rpage))
            tc.pos = (tc.pos - 1 + (key == "PADDLEFT" and -1 or 1)) % n + 1
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
    if (key == "PAD1" or key == "PAD3") and toolbarHas(tc.pos) then report("dpad-bar") return true end   -- p34
    if key == "PAD1" then                               -- A: carrying -> place / swap; else choose / Change (the ONE action's chooser)
        if tc.carry then carryPlace() return true end
        local h = here()
        if not h then return true end
        -- p46 SMART RANGE: a checked box asks Melee or Range first (the picker), then the chooser for that half
        if tc.view == "combat" and srOn(h) and srCur() then
            if inCombat() then refusedCombat() return true end
            tc.srp = (srHalf() == "r") and 2 or 1
            report("sr-picker")
            return true
        end
        if h.kind == "fixed" then
            if tc.view == "combat" and isFace(h) then
                if not inCombat() then tc.lock = shownSet(); tc.chooser = { list = "spells", row = 1 }; report("chooser") else refusedCombat() end
            else report(tc.view == "combat" and "stays-back" or "locked") end
            return true
        end
        -- p22 (PadMap m43): a fight spell on any button is the overlay now (no macro): the p18 "paused" stop is gone
        if changeable(h) then tc.lock = shownSet(); tc.chooser = { list = ctabs()[1], row = 1 }; report("chooser")   -- p26: latched; p49: a stick's Extra
        elseif inCombat() then refusedCombat() end                      -- Change during a fight: said, and pulsed
        return true
    end
    if key == "PAD3" then                               -- X: MOVE (pick up what is on the button)
        if not tc.carry then carryStart() end
        return true
    end
    if key == "PAD4" then                               -- p46: Y flips the button's Smart Range box (In combat tab)
        if tc.view == "combat" and not tc.carry then srToggle(nil) end
        return true
    end
    return false
end

local function pressTabs(key)
    -- p10 (the user 2026-10-07: "the character tabs auto open to the tab that the cursor is on- I REALLY like this and want to make the other
    -- tabs consistent with this behavior"): the cursor LANDING on a tab opens it at once and stays on it (once per landing: the wall opens
    -- nothing; no game action: the room's own page). LB / RB as before.
    if key == "PADDLEFT" or key == "PADDRIGHT" then
        local i = tabStep(tc.tcur, key == "PADDLEFT" and -1 or 1)       -- p34: over the hidden tab
        if i and i ~= tc.tcur then setTab(TABS[i]) end
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
        tc.formPicked = true                                            -- p44
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

-- p40 the radials' plates: the cursor LANDING on a radial shows it (p10's rule); A on it = its settings, A on New makes one; Down its wheel
local function rSelect(r)
    if tc.rpage ~= r then tc.rpage = r; tc.pos = 1 end
end
local function rNew()
    local sr = SR()
    if not sr or type(sr.new) ~= "function" then return false end
    if tc.carry then tc.carry = nil end
    local r, why = sr.new()
    if not r then
        if why == "combat" then refusedCombat() else report("radial-" .. tostring(why)) end
        return false
    end
    tc.rpage, tc.pos = r, 1
    for i, v in ipairs(rPlates()) do if v == r then tc.rplate = i end end
    tc.focus = "buttons"
    report("rnew")
    return true
end
local function pressRpages(key)
    local plates = rPlates()
    if key == "PADDLEFT" or key == "PADDRIGHT" then
        local i = math.min(#plates, math.max(1, tc.rplate + (key == "PADDLEFT" and -1 or 1)))
        if i ~= tc.rplate then
            tc.rplate = i
            if plates[i] ~= "new" and not tc.carry then rSelect(plates[i]) end
            report("rplate")
        end
    elseif key == "PAD1" then
        local v = plates[tc.rplate]
        if v == "new" then rNew()
        elseif v then rSelect(v); tc.focus, tc.rrow, tc.rdel = "rset", 1, nil; report("to-rset") end
    elseif key == "PADDDOWN" then if tc.rpage ~= 0 then step(1) end
    elseif key == "PADDUP" then step(-1)
    elseif key == "PAD2" then return false
    end
    return true
end
-- p40 the radial's settings (the right page): 1 Name, 2 Icon, 3 Opening, 4 Delete; B back to the plates
local function rDrop()
    local sr = SR()
    if inCombat() then refusedCombat() return end
    local ok, why = false, "radial"
    if sr then ok, why = sr.drop(tc.rpage) end
    tc.rdel = nil
    if not ok then report("radial-" .. tostring(why)) return end
    tc.rpage = rSlots()[1] or 0
    tc.rplate, tc.pos, tc.focus = 1, 1, "rpages"
    for i, v in ipairs(rPlates()) do if v == tc.rpage then tc.rplate = i end end
    report("rdeleted")
end
local function pressRset(key)
    if key == "PADDUP" then
        tc.rdel = nil
        if tc.rrow > 1 then tc.rrow = tc.rrow - 1; report("rrow") else tc.focus = "rpages"; report("to-rpages") end
    elseif key == "PADDDOWN" then
        tc.rdel = nil
        if tc.rrow < 4 then tc.rrow = tc.rrow + 1; report("rrow") end
    elseif key == "PAD1" then
        if tc.rrow == 1 then tc.box = { kind = "rname" }; rEvent("name"); report("rbox")
        elseif tc.rrow == 2 then tc.box = { kind = "ricon" }; rEvent("icon"); report("rbox")
        elseif tc.rrow == 3 then rEvent("mode"); report("rmode")
        elseif tc.rdel and GetTime() - tc.rdel <= DEL_S then rDrop()
        else tc.rdel = GetTime(); report("rdel-ask") end
    elseif key == "PAD2" then tc.rdel = nil; tc.focus = "rpages"; report("to-rpages")
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
    -- p30 (the user on 1.0.9-test19: "the spells with tabs have the l and r glyph but those control the tabs at the top of the screen and
    -- they should stay that way. Instead they should have the arrows side to side"): LB / RB are the ROOM's tabs, chooser or not (the
    -- chooser closes with the tab it was on, as a tap on a room tab does); the chooser's own tabs page with D-pad Left / Right
    if key == "PADLSHOULDER" or key == "PADRSHOULDER" then
        local i = tabStep(tabIndex(tc.view), key == "PADLSHOULDER" and -1 or 1)     -- p34: over the hidden tab
        if i then setTab(TABS[i]) end
        return true
    end
    if tc.view == "party" and PT.pressChooser then return PT.pressChooser(key) end   -- p33: the party chooser (one list, no tabs)
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
    -- p40: the app's Name keyboard / Icon grid: every press is the app's (an event), until it says the box is done
    if tc.box.kind == "rname" or tc.box.kind == "ricon" then rEvent("key:" .. tostring(key)); report("rkey") return true end
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
        tc.form, tc.fcur, tc.formAuto, tc.formPicked = 0, 0, nil, false  -- p44: a fresh open forgets the pick, starts on the shown stance bar
        if where == "tabs" then tc.view = TABS[1] end
        tc.focus = where == "tabs" and "tabs" or HOME_FOCUS[tc.view] or "buttons"
        tc.tcur = tabIndex(tc.view)
        local pm = PM()
        if pm then pm.emit(true) end
        report("enter")
    end,
    leave = function()
        tc.on, tc.chooser, tc.box, tc.carry, tc.srp = false, nil, nil, nil, nil
        report("leave")
    end,
    isOn = function() return tc.on end,
    -- the room is shown but not entered: its chosen set / button / page are sent, so the app shows (and touch changes) the same button
    sync = function() report("sync") end,
    -- a chooser, a box or a carried button: B goes back inside the room, not Home
    boxOpen = function() return tc.chooser ~= nil or tc.box ~= nil or tc.carry ~= nil or tc.srp ~= nil end,
    state = function() return tc end,
    press = function(key)
        if not tc.on then return false end
        if tc.box then return pressBox(key) end
        if tc.chooser then return pressChooser(key) end
        if tc.srp and pressSrPick(key) then return true end               -- p46: the Melee / Range picker
        if key == "PADLSHOULDER" or key == "PADRSHOULDER" then                    -- p9: LB / RB = the tabs
            local i = tabStep(tabIndex(tc.view), key == "PADLSHOULDER" and -1 or 1)     -- p34: over the hidden tab
            if i then setTab(TABS[i]) end
            return true
        end
        if tc.carry and key == "PAD2" then tc.carry = nil; report("carry-cancel") return true end
        -- p31 (the user on test20: "We can use the select button to cycle these tabs"): Select cycles the form row, the caster then each
        -- form, round, wherever the cursor is (Book b43 keeps Select for the room)
        if key == "PADBACK" then
            if tc.view == "party" then return true end                  -- p33: no form row on the Party tab
            local list = formList()
            if (tc.view == "combat" or (tc.view == "normal" and rowThere("forms"))) and #list > 0 then
                tc.fcur = ((tc.fcur or 0) + 1) % (#list + 1)
                tc.form = tc.fcur == 0 and 0 or list[tc.fcur].id
                tc.formPicked = true                                    -- p44
                report("form")
            end
            return true
        end
        if tc.focus == "tabs" then return pressTabs(key) end
        if tc.focus == "switches" then return pressSwitches(key) end
        if tc.focus == "sets" then return pressSets(key) end
        if tc.focus == "forms" then return pressForms(key) end
        if tc.focus == "rpages" then return pressRpages(key) end
        if tc.focus == "rset" then return pressRset(key) end
        if tc.focus == "dpad" then return pressDpad(key) end
        if tc.focus == "psets" and PT.pressSets then return PT.pressSets(key) end      -- p37
        if tc.focus == "ptouch" and PT.pressTouch then return PT.pressTouch(key) end
        if tc.focus == "pdiam" and PT.pressDiam then return PT.pressDiam(key) end
        if tc.focus == "pright" and PT.pressRight then return PT.pressRight(key) end
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
    keepOn = function() return keepOn() end,                                -- p45: PadMap m144 reads it (read only)
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
        -- p49: right after the one-shot talentn 38 pick (the app sends it in the same touch), button codes 1 / 2 are L3 / R3 (9 / 10)
        if tc.from12 and GetTime() - tc.from12 <= 3 and (k == 1 or k == 2) and tc.view == "normal" then k = k + 8 end
        tc.from12 = nil
        if GRIDS.STICK[k] and tc.view == "normal" then
            tc.pos = k
            if tc.on and not tc.chooser and not tc.box then tc.focus = "buttons" end
            report("touch-pos")
            return
        end
        if tc.view == "radial" and k > rShown(tc.rpage) then report("touch-pos-none") return end     -- p40: past the "+"
        if k >= 1 and k <= 8 then
            if keepOn() then keep.pos = true end
            tc.pos = k
            if tc.on and not tc.chooser and not tc.box then tc.focus = "buttons" end
            report("touch-pos")
        end
    end,
    touchPage = function(k)
        if tc.view == "radial" then report("not-on-radial") return end          -- p40: no pages (the radials are radial ops)
        local pm = PM(); if pm and pm.showPage(k) then report("touch-page") end
    end,
    -- p9 (the padtab row): the tab (1-3, a pick), Move's pick-up (a pick), its PLACE / SWAP (ONE game action), its cancel (a pick)
    touchTab = function(k) if TABS[k] then setTab(TABS[k]) end end,
    -- p29: a finger on the form row (padtab 15 = Caster, 16-25 = the forms in the game's order): a pick, no game action
    touchForm = function(k)
        local list = formList()
        if k == 0 or list[k] then
            tc.fcur = k; tc.form = k == 0 and 0 or list[k].id; tc.formPicked = true      -- p44
            if tc.on and not tc.chooser and not tc.box then tc.focus = "forms" end
            report("touch-form")
        end
    end,
    touchCarry = function() if not tc.carry then carryStart() end end,
    -- p40 / p49 RADIAL OPS BY TOUCH (Book b53: padtab 39, Smart Range's one-shot pick, then code 5 + c = radial op c): 0-5 show radial
    -- slot c + 1 (a pick), 6 New, 7 its settings (a pick), 10 DELETE the radial shown (the app asked "Tap again" first), 11 / 12 the
    -- Name / Icon box opened by a finger (the controller's presses go to the app), 13 that box done (the controller back)
    touchRadial = function(c)
        if tc.view ~= "radial" then report("not-radial") return end
        local sr = SR()
        if not sr then return end
        if c >= 0 and c <= 5 then
            if sr.has(c + 1) then
                rSelect(c + 1)
                for i, v in ipairs(rPlates()) do if v == c + 1 then tc.rplate = i end end
                if tc.on and not tc.chooser and not tc.box then tc.focus = "rpages" end
                report("touch-rplate")
            end
        elseif c == 6 then rNew()
        elseif c == 7 then
            if sr.has(tc.rpage) then tc.focus, tc.rrow, tc.rdel = "rset", 1, nil; report("touch-rset") end
        elseif c == 10 then
            if sr.has(tc.rpage) then rDrop() end
        elseif c == 11 or c == 12 then
            tc.box = { kind = c == 11 and "rname" or "ricon" }; tc.focus = "rset"; tc.rrow = c == 11 and 1 or 2; report("rbox")
        elseif c == 13 then
            if tc.box and (tc.box.kind == "rname" or tc.box.kind == "ricon") then tc.box = nil end
            report("rbox-done")
        end
    end,
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
    -- p46 SMART RANGE BY TOUCH (Book b52): padtab 39 = the one-shot pick (fresh 2 s); the padtab column after it is the code: 0 the box
    -- (flip), 1 pick Melee, 2 pick Range, 3 the box on (the restore: never off), 4 the box off
    srPick = function() tc.srAt = GetTime() end,
    srWants = function() return tc.srAt ~= nil and GetTime() - tc.srAt <= 2 end,
    srDrop = function() tc.srAt = nil end,
    touchSr = function(code)
        tc.srAt = nil
        if tc.carry then report("sr-carry") return end                 -- p47: a tap on the Move page changes nothing
        if code == 0 then srToggle(nil)
        elseif code == 3 or code == 4 then srToggle(code == 3)
        elseif code == 1 or code == 2 then
            local page, set, pos, form = srCur()
            if not page then report(set) return end
            tc.srh = { page = page, set = set, pos = pos, h = code == 1 and "m" or "r", f = form }
            tc.srp = nil
            report("touch-sr-half")
        else report("sr-code") end
    end,
    touchClear = function()
        if keepOn() then report("keep-refused") return end                                 -- p25: a restore never clears
        local combat = tc.combatAt and GetTime() - tc.combatAt <= COMBAT_PICK_S
        tc.combatAt = nil
        local h = here()
        if combat and srOn(h) and srHalf() then                          -- p46: the picked Smart Range half
            act({ kind = isFace(h) and "uncface" or "uncombat", srh = srHalf() }); report(tc.srNote or "cleared"); tc.srNote = nil
        elseif combat then
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
        if GRIDS.STICK[tc.pos] then report("stick-binds") return end                    -- p49: a stick takes the game bindings only
        if tc.combatAt and now - tc.combatAt <= COMBAT_PICK_S then tc.combatAt = nil; report("not-in-combat-view") return end
        local c = pm and pm.commands()[k]
        if c then act({ kind = "command", key = c.key }); report("put") end
    end,
    touchSpell = function(id)
        if tc.view == "party" and PT.touchSpell then return PT.touchSpell(id) end      -- p33: the picked party slot's spell
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
        if GRIDS.STICK[tc.pos] and tc.view == "normal" then report("stick-binds") return end    -- p49
        local slot = type(WC.spellsBookSlotOf) == "function" and WC.spellsBookSlotOf(id)
        if not slot then report("spell-not-in-book") return end
        local h = here()
        if h and h.kind == "fixed" and not (combat and isFace(h)) then report(isFace(h) and "fixed" or "stays-back") return end
        local kind = tc.view == "radial" and "rspell"
            or combat and (isFace(h) and "cface" or "combat") or (isCombat(h) and "otherwise" or "spell")
        if kind == "rspell" and inCombat() then refusedCombat() return end              -- p16: the cells freeze in a fight
        -- p46 SMART RANGE: a checked box's picked half takes it (none picked: PadMap puts it in the half it is for)
        local srh = (combat and srOn(h)) and srHalf() or nil
        act({ kind = kind, id = id, slot = slot, srh = srh }); report(tc.srNote or "put"); tc.srNote = nil
    end,
    -- p12 THE "extra" PAD ROW (the pad owner's YES 2026-10-07, with ONE mandatory rule): every extra tap needs a FRESH page pick first
    -- (padtab 6 / 7 / 8 = entries from 0 / 40 / 80; one-shot, stale after PICK_S), so a lost pick never puts a wrong entry. The tap then puts
    -- Extra entry page + col + 1 on the chosen button: ONE put out of combat (refused in a fight before any pickup); the answer names it.
    -- p13 THE LEFT BUMPER FROM THE APP (the user on b124: "the main tabs in the controller room move right with R but wont move back left
    -- with L"): on this device LB is the game's emulated Ctrl (GamePadEmulateCtrl = PADLSHOULDER, MEASURED 2026-09-28, AGENT_LOG "BUTTON
    -- TEST ANSWERED"), so the game never hands it to the capture; the app reads the bumper itself (getevent, read-only) and taps padtab 9
    -- on its release. It does what LB does: a box open, nothing; else the room's tab before (p30: the chooser open too, it closes).
    touchShoulder = function(d)
        if not tc.on or tc.box then return end
        local key = d < 0 and "PADLSHOULDER" or "PADRSHOULDER"
        if tc.chooser then pressChooser(key) return end
        local i = tabStep(tabIndex(tc.view), d < 0 and -1 or 1)            -- p34: over the hidden tab
        if i then setTab(TABS[i]) end
    end,
    touchExtraPage = function(base) tc.xpage = { base = base, at = GetTime() } end,
    touchExtra = function(col)
        local pick = tc.xpage
        tc.xpage = nil
        -- p41: the touch's one-shot "combat layer" pick before it (the app on In combat) = the entry is for the fight layer
        local combat = tc.combatAt and GetTime() - tc.combatAt <= COMBAT_PICK_S or nil
        tc.combatAt = nil
        if not pick or GetTime() - pick.at > EXTRA_PICK_S then return false, "no page picked" end
        if tc.view == "radial" then report("not-on-radial") return false, "radial" end   -- p16
        if inCombat() then refusedCombat() return false, "combat" end
        local pm = PM()
        local list = pm and type(pm.extraList) == "function" and pm.extraList() or {}
        local idx = pick.base + col + 1
        local x = list[idx]
        if not x then return false, "no entry", idx end
        local h = here()
        -- p43: on In combat a base X / Y / A takes it on its fight layer (B stays the game's Back)
        if h and h.kind == "fixed" and not ((fightView() or combat) and isFace(h)) then
            report(isFace(h) and "fixed" or "stays-back") return false, "fixed", idx, x
        end
        if (fightView() or combat) and onlySpells() then report("spells-only") return false, "spells only", idx, x end
        if GRIDS.STICK[tc.pos] and (x.kind ~= "bind" or combat) then report("stick-binds") return false, "stick binds", idx, x end   -- p49
        if not bindHere(x) then return false, "bind not here", idx, x end
        act((x.kind == "command" or x.kind == "bind") and { kind = x.kind, key = x.key } or { kind = x.kind, id = x.id }, combat)
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

-- ---------------------------------------------------------------- p33 THE PARTY MODE TAB (PARTY_BUILD.md 4.4; PadMap m77 holds the settings)
-- p37 (party_design.md B and the user's decisions 2026-11-11): the tab is drawn on the normal layers' own picture, and the cursor walks
-- it as it walks them: the SET ROW (ZL, ZR; ZL + ZR locked: nothing happens with both held), the TOUCH ROW (Touch: the second tap's
-- action; Hold: the player menu, locked), the two DIAMONDS (the D-pad: all four directions move the pick, locked - p38, the user's
-- adaptive rows 2026-11-11; the faces: A, X, Y as set, B the red Back, locked).
-- ZL / ZR (chosen on the set row, or held) show that layer's A / X / Y. A on a place = the right page; there A = its ONE red action
-- (Change: the chooser; with Smart Y on, Y's page: Remove a party buff / Add one / Change the In combat action), B = back.
-- By touch (Book's padtab): 26 the tab, 27-36 a slot pick (fresh 2 s), 37 the picked slot's no-spell value, Book's two-tap spell.
-- Every setting is PadMap's (WC.padMap.party.put / padd / pbRemove, each answered with a partyack); nothing here touches the game.
local function partyTab()
    -- the slot on (set, position): position 8 A, 5 X, 6 Y (the router's numbers, as the normal page); set 1 base, 2 ZL, 3 ZR
    local FACE = { [8] = "a", [5] = "x", [6] = "y" }
    local function slotAt(set, pos)
        local f = FACE[pos]
        if not f or set == 4 then return nil end
        if set == 1 then return f == "y" and "yc" or f end
        return (set == 2 and "zl" or "zr") .. f
    end
    -- padtab 27-36: slot k -> where the cursor goes (PartyModel.PICKS' order)
    local PICK = { { 1, 8 }, { 1, 5 }, { 1, 6 }, "touch", { 2, 8 }, { 2, 5 }, { 2, 6 }, { 3, 8 }, { 3, 5 }, { 3, 6 } }
    local NONE = { yc = "inspect", touch = "target" }      -- the chooser's no-spell row (else "empty")
    local PICK_S = 2
    local function party() local pm = PM(); return pm and type(pm.party) == "table" and pm.party or nil end
    local function cfg() local p = party(); if not p then return nil end; local ok, c = pcall(p.cfg); return ok and c or nil end
    -- the layer on show: a held trigger (the game's active set) wins over the one chosen on the set row
    local function shownP()
        if type(WC.spellsActiveSet) == "function" then
            local ok, a = pcall(WC.spellsActiveSet)
            if ok and type(a) == "number" and a >= 2 and a <= 4 then return a end
        end
        return tc.pset or 1
    end
    PT.shown = shownP
    -- the slot under the cursor (the Touch row's Touch, a face on the layer on show), else nil (a locked place)
    local function curSlot()
        if tc.focus == "ptouch" or (tc.focus == "pright" and tc.pfrom == "touch") then return tc.ptouch == 1 and "touch" or nil end
        return slotAt(shownP(), tc.ppos)
    end
    PT.curSlot = curSlot
    -- the right page's rows: one (Change), or for Y with Smart Y on the party list's rows, the Add row, then "In combat" (Change)
    local function rightRows()
        if curSlot() ~= "yc" then return { { kind = "change" } } end
        local c = cfg()
        if not (c and c.sy and c.sy.on) then return { { kind = "change" } } end
        local out = {}
        for k, e in ipairs(c.sy.list or {}) do out[#out + 1] = { kind = "remove", k = k, id = e.id } end
        out[#out + 1] = { kind = "add" }
        out[#out + 1] = { kind = "change" }
        return out
    end
    PT.rightRows = rightRows
    -- the chooser's rows: "party" = the no-spell row, then partycfg.spells by id; "pbuff" = the party buffs known and not listed yet
    PT.rows = function()
        local out = {}
        if not tc.chooser then return out end
        local c = cfg() or {}
        if tc.chooser.list == "pbuff" then
            local listed = {}
            for _, e in ipairs(c.sy and c.sy.list or {}) do listed[e.id] = true end
            for _, e in ipairs(c.sy and c.sy.known or {}) do if not listed[e.id] then out[#out + 1] = { kind = "pbuff", id = e.id } end end
            return out
        end
        out[1] = { kind = "pnone", key = NONE[tc.chooser.slot] or "empty" }
        for _, e in ipairs(c.spells or {}) do out[#out + 1] = { kind = "pspell", id = e.id } end
        return out
    end
    local function put(slot, v)
        local p = party()
        if not p then return false end
        if inCombat() then refusedCombat() end           -- PadMap answers partyack why "combat": nothing changes in a fight
        local ok = p.put(slot, v)
        return ok == true
    end
    local function openChooser(list)
        if inCombat() then refusedCombat() return end
        tc.chooser = { list = list, row = 1, slot = curSlot() }
        report("chooser")
    end
    PT.pressChooser = function(key)
        local list = PT.rows()
        if key == "PADLSHOULDER" or key == "PADRSHOULDER" then
            local i = tabStep(tabIndex(tc.view), key == "PADLSHOULDER" and -1 or 1)     -- p34: over the hidden tab
            if i then setTab(TABS[i]) end
            return true
        end
        if key == "PADDUP" then tc.chooser.row = math.max(1, tc.chooser.row - 1); report("row")
        elseif key == "PADDDOWN" then tc.chooser.row = math.min(math.max(1, #list), tc.chooser.row + 1); report("row")
        elseif key == "PAD1" then
            local r, ch = list[tc.chooser.row], tc.chooser
            if not r then return true end
            if inCombat() then refusedCombat() return true end
            local p, ok = party(), false
            if r.kind == "pbuff" then ok = p and p.padd(r.id) == true
            elseif r.kind == "pnone" then ok = put(ch.slot, false)
            else ok = put(ch.slot, r.id) end
            tc.chooser = nil
            report(ok and "put" or "put-refused")
        elseif key == "PAD2" then tc.chooser = nil; report("chooser-closed") end
        return true
    end
    local function toRight(from)
        tc.pfrom, tc.focus, tc.pright = from, "pright", 1
        report("to-pright")
    end
    -- the set row: ZL / ZR shown by A (A again: the base layer); ZL + ZR locked
    PT.pressSets = function(key)
        if key == "PADDLEFT" then tc.scur = math.max(2, tc.scur - 1); report("set-cursor")
        elseif key == "PADDRIGHT" then tc.scur = math.min(4, tc.scur + 1); report("set-cursor")
        elseif key == "PAD1" then
            if tc.scur == 4 then report("locked") return true end
            tc.pset = (tc.pset == tc.scur) and 1 or tc.scur; report("set")
        elseif key == "PADDDOWN" then step(1)
        elseif key == "PADDUP" then step(-1)
        elseif key == "PAD2" then return false
        end
        return true
    end
    -- the Touch row: 1 Touch (the picked plate's second tap), 2 Hold (the player menu, locked)
    PT.pressTouch = function(key)
        if key == "PADDLEFT" then tc.ptouch = 1; report("ptouch")
        elseif key == "PADDRIGHT" then tc.ptouch = 2; report("ptouch")
        elseif key == "PADDUP" then step(-1)
        elseif key == "PADDDOWN" then step(1)
        elseif key == "PAD1" then
            if tc.ptouch ~= 1 then report("locked") return true end
            toRight("touch")
        elseif key == "PAD2" then return false
        end
        return true
    end
    -- the diamonds (as drawn: the layout's grid, as the normal page); Up from the top row = the Touch row
    PT.pressDiam = function(key)
        local d = DIRS[key]
        if d then
            local grid = GRIDS[layout()]
            local cur = grid[tc.ppos]
            local best, bestScore
            for i = 1, 8 do
                local g = grid[i]
                if i ~= tc.ppos then
                    local dx, dy = g[1] - cur[1], g[2] - cur[2]
                    local along = dx * d[1] + dy * d[2]
                    if along > 0 then
                        local score = along + 2 * (math.abs(dx * d[2]) + math.abs(dy * d[1]))
                        if not bestScore or score < bestScore then best, bestScore = i, score end
                    end
                end
            end
            if best then tc.ppos = best; report("pmove")
            elseif key == "PADDUP" then step(-1) end
            return true
        end
        if key == "PAD1" then
            if not curSlot() then report("locked") return true end                      -- p38: the D-pad moves the pick; B; ZL + ZR
            toRight("diam")
            return true
        end
        if key == "PAD2" then return false end
        return true
    end
    -- the right page: its rows (Y with Smart Y on) and the ONE red action for the row under the cursor
    PT.pressRight = function(key)
        local rr = rightRows()
        if key == "PADDUP" then tc.pright = math.max(1, tc.pright - 1); report("pright")
        elseif key == "PADDDOWN" then tc.pright = math.min(#rr, tc.pright + 1); report("pright")
        elseif key == "PAD2" then tc.focus = tc.pfrom == "touch" and "ptouch" or "pdiam"; report("to-prows")
        elseif key == "PAD1" then
            local r = rr[math.min(tc.pright, #rr)]
            if not r then return true end
            if r.kind == "remove" then
                if inCombat() then refusedCombat() return true end
                local p = party()
                local ok = p and p.pbRemove(r.k) == true
                tc.pright = math.max(1, math.min(tc.pright, #rightRows()))
                report(ok and "removed" or "remove-refused")
            elseif r.kind == "add" then openChooser("pbuff")
            else openChooser("party") end
        end
        return true
    end
    -- TOUCH: padtab 27-36 = a slot pick (fresh PICK_S; the cursor moves with it), 37 = its no-spell value, the spell = Book's two taps
    WC.padTabCtl.touchPartySlot = function(k)
        local where = PICK[k]
        if not where then return end
        local slot
        if where == "touch" then
            tc.ptouch, tc.pfrom, slot = 1, "touch", "touch"
            if tc.on and not tc.chooser and tc.view == "party" then tc.focus = "ptouch" end
        else
            tc.pset, tc.ppos = where[1], where[2]
            slot = slotAt(where[1], where[2])
            if tc.on and not tc.chooser and tc.view == "party" then tc.focus = "pdiam" end
        end
        tc.ppick = { slot = slot, at = GetTime() }
        report("touch-pslot")
    end
    local function fresh()
        local p = tc.ppick
        tc.ppick = nil
        if not p or GetTime() - p.at > PICK_S then return nil end
        return p.slot
    end
    WC.padTabCtl.touchPartyNone = function()
        local s = fresh()
        if not s then report("pslot-no-pick") return end
        report(put(s, false) and "put" or "put-refused")
    end
    PT.touchSpell = function(id)
        local s = fresh()
        if not s then report("pslot-no-pick") return end     -- a lost pick never puts a spell on a wrong slot
        report(put(s, id) and "put" or "put-refused")
    end
end
partyTab()
