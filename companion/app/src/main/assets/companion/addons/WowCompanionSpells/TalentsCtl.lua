-- WowCompanionSpells / TalentsCtl.lua
-- The TALENTS room's controller half: where the cursor is, and what the eight buttons do there (UI Art's SPEC, section 6,
-- wow-companion-design/talents/SPEC.md; the user's picks "A, one school at a time" and "See and learn").
--
-- It holds NO controller of its own: the Spellbook's guest of the shared capture (Book.lua) holds it for the whole Spells room, and
-- while the room shows its Talents page it hands every press here first. press(key) answers true when it used the press, false to
-- let Book.lua do its own (B = hand the controller back, and the app goes Home).
--
-- WHERE THE CURSOR LIVES: the spec tabs, the school tabs, the grid, and the foot under the grid (Undo, Reset, Apply).
--   grid     the D-pad goes to the NEAREST talent in the pressed direction (the game's own gamepad rule), empty cells skipped. Up with
--            no talent above: the school tabs; Down with no talent below: the foot. A = Add Point, Y = Remove (a STAGED point only),
--            X = Apply from anywhere.
--   schools  Left / Right OPEN the tab the cursor lands on (c4, the user 2026-10-07: "the character tabs auto open to the tab that the
--            cursor is on- I REALLY like this and want to make the other tabs consistent with this behavior"; the cursor stays on the tab;
--            no game action: the room's own view); A goes into it (its first talent); Down back to the grid; Up the spec tabs.
--   specs    shown only in this build (dual spec is not learned): A does nothing; Down back to the school tabs.
-- NO DOOR (the user 2026-09-30: Talents gets its own button on the bar, "remove the buttons for the spells and talents in those rooms").
--   foot     Left / Right: Undo, Reset, Apply; A presses the lit one; Up back to the talent it came from.
-- THE TWO BOXES: Apply ("Apply these talent changes?", the list is the app's) and Undo (the game's own "You will lose any pending
-- changes if you continue."): A confirms, B cancels, nothing else does anything. Apply = the ONE game action (CommitConfig); until the
-- game answers, every action is grey (`busy`).
--
-- Nothing here touches a game window or runs the game's UI code: it moves a cursor and calls WC.talents (Talents.lua), whose actions
-- are each one C_Traits call.

local ADDON = ...
local WC = _G.WowCompanion
if type(WC) ~= "table" or type(WC.send) ~= "function" or type(WC.encode) ~= "function" then return end

local CV = "c4"                 -- bump on every change
local PX, PY = 160, 116         -- the room's pitch (SPEC 2): the nearest talent is measured in the room's own pixels
local FOOT = { "undo", "reset", "apply" }

local tc = { focus = "grid", tab = 1, spec = 1, foot = 1, box = nil, from = nil, note = nil }

local function T() return type(WC.talents) == "table" and WC.talents or nil end
local function inCombat()
    local ok, v = pcall(function() return InCombatLockdown() end)
    return ok and v == true
end

-- the game's talent button is still disabled (no talent point earned yet): the page is greyed, the controller moves nothing on it (B still
-- leaves, Home); the room shows NO cursor then (the user 2026-09-30: "Greyed out until its actually available in the game")
local function locked()
    local t = T()
    return t ~= nil and type(t.locked) == "function" and t.locked() == true
end

local function report(note)
    tc.note = note or tc.note
    local t = T()
    WC.send(WC.encode({ type = "talentsctl", v = CV, c = {
        focus = tc.focus, tab = tc.tab, spec = tc.spec, foot = tc.foot, box = tc.box,
        busy = t and t.busy() or nil, lock = locked() or nil, note = tc.note } }))
    WC.push()
end

-- the talents of the school on screen, placed on the grid
local function schoolNodes()
    local t = T()
    if not t then return {} end
    local out, s = {}, t.school()
    for _, n in ipairs(t.nodes()) do
        if n.g == s and n.c and n.r then out[#out + 1] = n end
    end
    return out
end

local function byId(id)
    local t = T()
    if not (t and id) then return nil end
    for _, n in ipairs(t.nodes()) do if n.id == id then return n end end
end

-- the first talent of the school on screen: the top row, then the left
local function firstOfSchool()
    local best
    for _, n in ipairs(schoolNodes()) do
        if not best or n.r < best.r or (n.r == best.r and n.c < best.c) then best = n end
    end
    return best
end

-- the nearest talent from `cur` in a D-pad direction: strictly that way, scored by the distance along the direction plus twice the
-- distance across it (so a talent straight ahead wins over a closer one off to the side)
local DIRS = { PADDUP = { 0, -1 }, PADDDOWN = { 0, 1 }, PADDLEFT = { -1, 0 }, PADDRIGHT = { 1, 0 } }
local function nearest(cur, key)
    local d = DIRS[key]
    if not (d and cur) then return nil end
    local best, bestScore
    for _, n in ipairs(schoolNodes()) do
        if n.id ~= cur.id then
            local dx, dy = (n.c - cur.c) * PX, (n.r - cur.r) * PY
            local along = dx * d[1] + dy * d[2]
            if along > 0 then
                local across = math.abs(dx * d[2]) + math.abs(dy * d[1])
                local score = along + 2 * across
                if not bestScore or score < bestScore or (score == bestScore and n.id < best.id) then best, bestScore = n, score end
            end
        end
    end
    return best
end

-- the cursor's talent; when there is none (a new school, a talent gone after a change), the school's first
local function cursorNode()
    local t = T()
    local n = t and byId(t.selected())
    if n and n.g == (t and t.school()) then return n end
    local first = firstOfSchool()
    if first and t then t.select(first.id) end
    return first
end

local function canEditNow() local t = T(); return t and not inCombat() and not t.busy() end

local function openApply()
    local t = T()
    if not (canEditNow() and t.staged()) then return false end
    tc.box = "apply"
    return true
end

local function openUndo()
    local t = T()
    if not (canEditNow() and t.staged()) then return false end
    tc.box = "undo"
    return true
end

-- ---------------------------------------------------------------- presses

local function pressBox(key)
    local t = T()
    if key == "PAD1" then
        local box = tc.box
        tc.box = nil
        if box == "apply" and t then t.apply() elseif box == "undo" and t then t.undo() end
        report(box == "apply" and "applying" or "undone")
    elseif key == "PAD2" then
        tc.box = nil
        report("box-cancel")
    end
    return true                     -- nothing else does anything while a box is open
end

local function pressGrid(key)
    local t = T()
    local cur = cursorNode()
    if DIRS[key] then
        local nxt = nearest(cur, key)
        if nxt then t.select(nxt.id); report("move")
        elseif key == "PADDUP" then tc.focus, tc.tab = "schools", t.school(); report("to-schools")
        elseif key == "PADDDOWN" then tc.focus, tc.foot, tc.from = "foot", 1, cur and cur.id or nil; report("to-foot")
        end
        return true
    end
    if key == "PAD1" then                                       -- A: Add Point, when the talent can take one
        if cur and cur.cp and canEditNow() then t.add(); report("add") end
        return true
    end
    if key == "PAD4" then                                       -- Y: Remove, a STAGED point only (SPEC 6.3: nothing otherwise)
        if cur and cur.pend == "+" and canEditNow() then t.remove(); report("remove") end
        return true
    end
    return false
end

local function pressSchools(key)
    local t = T()
    local count = math.max(1, t.schools())
    if key == "PADDLEFT" or key == "PADDRIGHT" then
        -- c4: the landing opens the school (once per landing: the wall opens nothing)
        local to = key == "PADDLEFT" and math.max(1, tc.tab - 1) or math.min(count, tc.tab + 1)
        if to ~= tc.tab then
            tc.tab = to
            t.showSchool(to)
            report("tab")
        end
    elseif key == "PADDDOWN" then tc.focus = "grid"; cursorNode(); report("to-grid")   -- c4: on a talent of the school on show
    elseif key == "PADDUP" then tc.focus = "specs"; report("to-specs")
    elseif key == "PAD1" then
        t.showSchool(tc.tab)
        local first = firstOfSchool()
        if first then t.select(first.id) end
        tc.focus = "grid"
        report("school")
    else return false end
    return true
end

local function pressSpecs(key)
    if key == "PADDLEFT" then tc.spec = 1; report("spec")
    elseif key == "PADDRIGHT" then tc.spec = 2; report("spec")
    elseif key == "PADDDOWN" then tc.focus = "schools"; report("to-schools")
    elseif key == "PAD1" or key == "PADDUP" then return true    -- shown only in this build (dual spec not learned)
    else return false end
    return true
end

local function pressFoot(key)
    local t = T()
    if key == "PADDLEFT" then tc.foot = math.max(1, tc.foot - 1); report("foot")
    elseif key == "PADDRIGHT" then tc.foot = math.min(#FOOT, tc.foot + 1); report("foot")
    elseif key == "PADDUP" then
        tc.focus = "grid"
        local back = byId(tc.from)
        if back then t.select(back.id) end
        report("to-grid")
    elseif key == "PAD1" then
        local what = FOOT[tc.foot]
        if what == "undo" then if openUndo() then report("box-undo") end
        elseif what == "reset" then if canEditNow() and t.spent() > 0 then t.reset(); report("reset") end
        elseif what == "apply" then if openApply() then report("box-apply") end end
    else return false end
    return true
end

WC.talentsCtl = {
    version = 1,
    -- the Talents page comes up: the tree is sent, the cursor stands on a talent
    -- LATER item 19 (the user 2026-09-30): the page opens with the cursor ON the selected school's tab; Down reaches the talents
    enter = function()
        local t = T()
        tc.focus, tc.box, tc.from = "schools", nil, nil
        if t then
            t.setOpen(true)
            tc.tab = t.school()
            cursorNode()
        end
        report("enter")
    end,
    -- the page goes away (the Spellbook page, Home, the controller lost): no box stays open
    leave = function()
        local t = T()
        tc.box = nil
        if t then t.setOpen(false) end
        report("leave")
    end,
    boxOpen = function() return tc.box ~= nil end,
    state = function() return tc end,
    -- one press of the eight; true = used here, false = Book.lua's own (B: back to Home)
    press = function(key)
        if not T() then return false end
        if locked() then return key ~= "PAD2" end               -- greyed: every press is swallowed, B leaves (Home)
        if tc.box then return pressBox(key) end
        if key == "PAD2" then return false end
        if key == "PAD3" then                               -- X: Apply, from anywhere
            if openApply() then report("box-apply") end
            return true
        end
        if tc.focus == "grid" then return pressGrid(key)
        elseif tc.focus == "schools" then return pressSchools(key)
        elseif tc.focus == "specs" then return pressSpecs(key)
        elseif tc.focus == "foot" then return pressFoot(key) end
        return false
    end,
    -- a touch opened or answered a box (the app's own buttons): keep the controller's view in step
    closeBox = function() if tc.box then tc.box = nil; report("box-closed") end end,
}

-- an Apply or Undo answered by TOUCH (the pad's columns 5 / 4, after the app's own box) closes the controller's box as well
if type(WC.talents) == "table" and type(hooksecurefunc) == "function" then
    for _, name in ipairs({ "apply", "undo" }) do
        if type(WC.talents[name]) == "function" then
            hooksecurefunc(WC.talents, name, function() WC.talentsCtl.closeBox() end)
        end
    end
    -- a FINGER's choice moves the cursor with it (the cursor may stand on the school tabs since item 19): a talent picked by touch puts it on
    -- the talents, a school shown by touch puts the school cursor there
    if type(WC.talents.pick) == "function" then
        hooksecurefunc(WC.talents, "pick", function()
            local t = T()
            if t and t.isOpen and t.isOpen() and tc.focus ~= "grid" and not tc.box then tc.focus = "grid"; report("to-grid-touch") end
        end)
    end
    if type(WC.talents.showSchool) == "function" then
        hooksecurefunc(WC.talents, "showSchool", function()
            local t = T()
            if t and t.school and tc.tab ~= t.school() then tc.tab = t.school(); report("school-touch") end
        end)
    end
end
