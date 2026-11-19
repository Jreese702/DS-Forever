-- i141 (issue 90, the player: "Pet training causes an addon error and requires a restart after use"; the log: "WowCompanionInteract
-- forbidden: BuyTrainerService()"): at a PET trainer our code never buys. i142: the game's own row click, then its own Train button.
-- i170 (the user, 2026-11-19: "the pet training in general is super inconsistent, it opens and doesnt work halfd the time or shows stuff
-- thats already know for the pet regardless of the filter"): one check per cause found -
--   identity: every command names its service (name + rank) and the index is looked up again at press time (a buy / filter shifts it);
--   the list honours the game's filters by kind, and the PET's own book decides "known" at a pet trainer;
--   the pet press: no row on the game's page = refused (never the old select-then-Train), the row re-pointed after a list rebuild,
--   the Train cell armed ONLY after the game took our selection and disarmed after one Train, a tainted window refused, a doubled press
--   refused, a lost tap times out.
local N, ACT, ON, build = __N, __ACT, __ON, __build
local out = {}
local function chk(n, c) out[#out + 1] = (c and "PASS " or "FAIL ") .. n end

-- ---- the game
local FrameMT = { __index = {
    GetAlpha = function(self) return self.alpha or 1 end, SetAlpha = function(self, a) self.alpha = a end,
    IsShown = function(self) return self.shown ~= false end, IsVisible = function(self) return self.shown ~= false end,
    HookScript = function() end, GetRect = function() return 0, 0, 100, 100 end,
    GetEffectiveScale = function() return 1 end, GetChildren = function() end, GetRegions = function() end,
    GetParent = function() return nil end,
} }
-- SVC: the game's list as GetTrainerServiceInfo answers it (already filtered by the game, as the C side does)
SVC = {
    { "Bite", "available", "Rank 1" },
    { "Growl", "available", "Rank 1" },
    { "Growl", "available", "Rank 2" },
    { "Cower", "unavailable", "Rank 1" },
}
FILT = { available = true, unavailable = true, used = false }
function GetNumTrainerServices() return #SVC end
function GetTrainerServiceInfo(i) local s = SVC[i]; if s then return s[1], s[2], 132278, 1, s[3] end end
function GetTrainerServiceTypeFilter(k) return FILT[k] end
function SetTrainerServiceTypeFilter(k, v) FILT[k] = v end
function GetTrainerServiceCost(i) return 10, false end
function IsTradeskillTrainer() return false end
BOUGHT, SELECTED = {}, {}
function BuyTrainerService(i) BOUGHT[#BOUGHT + 1] = i end
function SelectTrainerService(i) SELECTED[#SELECTED + 1] = i; GAMESEL = i end
GAMESEL = nil
function GetTrainerSelectionIndex() return GAMESEL end
TRAIN_ON = true
ClassTrainerTrainButton = { IsEnabled = function() return TRAIN_ON end }
-- the game's visible page: pooled row frames, each showing one service (skillIndex)
local ROWS = {}
for k = 1, 3 do
    ROWS[k] = { sk = k, IsShown = function(self) return self.shown ~= false end,
                GetElementData = function(self) return { data = { skillIndex = self.sk, displayIndex = self.sk } } end }
end
rawset(_G, "ClassTrainerFrame", setmetatable({ alpha = 1, ScrollBox = { GetFrames = function() return ROWS end } }, FrameMT))
ClassTrainerFrame_Update = function() end
HOOKS = {}
function hooksecurefunc(a, b) if type(a) == "string" then HOOKS[a] = b end end
TAINT = {}
function issecurevariable(t, k) if TAINT[k] and (TAINT[k] == true or TAINT[k] == t) then return false, "SomeAddon" end; return true end
TIMERS = {}
C_Timer.After = function(d, fn) TIMERS[#TIMERS + 1] = { d = d, fn = fn } end
PTS_TOTAL, PTS_USED = 200, 0
C_PetInfo = { GetPetTrainingPoints = function() return PTS_TOTAL, PTS_USED end }
PETBOOK = {}
C_SpellBook = { HasPetSpells = function() return #PETBOOK end,
                GetSpellBookItemInfo = function(slot) local b = PETBOOK[slot]; if b then return { name = b[1], subName = b[2] } end end }
GAME.t = 3000
TYPE_NOW = 1
Enum = { TrainerType = { Class = 0, Pet = 1 } }
C_Trainer = { GetTrainerType = function() return TYPE_NOW end, GetCategorizeTrainerUI = function() return false end }
if ON.TRAINER_SHOW then pcall(ON.TRAINER_SHOW) end

-- the app's two taps, as the secure cells would run them: col 1 = the game's row click (its own ClassTrainer_SetSelection), col 0 =
-- the game's Train button (buys the window's selection) - each only when the cell is armed, then the cells' post-click
local SR = SECROWS.itrain
local CELL = { [0] = { a = {} }, [1] = { a = {} } }
for c = 0, 1 do CELL[c].SetAttribute = function(self, k, v) self.a[k] = v end end
if SR then SR.init(CELL[1], 1); SR.init(CELL[0], 0) end
local function tapRow()
    local f = CELL[1].a.type == "click" and CELL[1].a.clickbutton or nil
    if f then
        SelectTrainerService(f.sk)
        ClassTrainerFrame.selectedService = f.sk; ClassTrainerFrame.displayIndexToSkillIndex = nil
    end
    SR.onClick(1)
end
local function tapTrain()
    if CELL[0].a.type == "macro" then BuyTrainerService(GAMESEL) end
    SR.onClick(0)
end
local function svcOf(doc, name, sub)
    for _, sv in ipairs(doc.trainer and doc.trainer.services or {}) do if sv.name == name and sv.sub == sub then return sv end end
end
local function cmdOf(sv) return "train " .. sv.i .. " " .. sv.k end

chk("i170: the itrain row is registered (2 cells)", SR ~= nil and SR.n == 2)
chk("i170 cause 3: the Train cell starts DISARMED (no type), its macro set", CELL[0].a.type == nil and CELL[0].a.macrotext == "/click ClassTrainerTrainButton")

-- ---- identity
local doc = build()
local g1 = svcOf(doc, "Growl", "Rank 1")
chk("i170: every service carries its identity k", g1 and type(g1.k) == "string" and #g1.k > 0)
chk("i170: two ranks of one spell have different identities", g1 and svcOf(doc, "Growl", "Rank 2") and g1.k ~= svcOf(doc, "Growl", "Rank 2").k)
chk("i170: the slot is keyed by the identity (train <i> <k>)", g1 and CELLS["ibuy:" .. cmdOf(g1)] ~= nil)
pcall(N.build, doc)
chk("i170: the controller's cursor ids carry it too", N.zones[1] and N.zones[1].items[1] and N.zones[1].items[1].id:match("^train %d+ %x+$") ~= nil)

-- ---- the pet press chain, the good path
local ok, why = ACT.train(tostring(g1.i), g1.k)
chk("pet trainer: train answers ok (" .. tostring(why) .. ")", ok == true)
chk("pet trainer: never BuyTrainerService from our code", #BOUGHT == 0)
chk("i142: the game's own row for the service is the row cell's target (type click, ptr 1)", CELL[1].a.type == "click" and CELL[1].a.clickbutton == ROWS[2] and N.ptr == 1)
chk("i142: with the row found, no add-on select call", #SELECTED == 0)
chk("pet trainer: the press is counted for the app (ptc 1)", N.ptc == 1)
chk("i170 cause 3: Train still disarmed before the row click", CELL[0].a.type == nil)
tapRow()
chk("i170 cause 3: the game took OUR selection: Train armed", CELL[0].a.type == "macro")
tapTrain()
chk("i170: one Train bought the pressed service", BOUGHT[1] == 2 and #BOUGHT == 1)
chk("i170 cause 3: after one Train the cell is disarmed and the press over", CELL[0].a.type == nil and N.ptWant == nil)
tapTrain()
chk("i170 cause 3: a second (stray / replayed) Train tap buys nothing", #BOUGHT == 1)

-- ---- cause 1: index drift (a learned rank dropped out of the list: every index after it moved up one)
table.remove(SVC, 2)                     -- Growl R1 learned, "used" filtered off: Growl R2 is now index 2, Cower 3
ROWS[2].sk, ROWS[3].sk = 2, 3
local g2 = svcOf(doc, "Growl", "Rank 2")  -- the app still holds the OLD document: Growl R2 at index 3
ok, why = ACT.train(tostring(g2.i), g2.k)
chk("i170 cause 1: a stale index finds its service by identity (" .. tostring(why) .. ")", ok == true and N.ptWant and N.ptWant.i == 2 and CELL[1].a.clickbutton == ROWS[2])
N.ptDrop(nil)
ok, why = ACT.train(tostring(g1.i), g1.k)
chk("i170 cause 1: a service no longer in the list is refused, never the one now at its index (" .. tostring(why) .. ")", ok == false and why == "the trainer's list changed")

-- ---- cause 2: the game rebuilt its list between the press and the row tap (pooled frames now show other services)
GAME.t = 3100
doc = build()
g2 = svcOf(doc, "Growl", "Rank 2")
ACT.train(tostring(g2.i), g2.k)
ROWS[1].sk, ROWS[2].sk, ROWS[3].sk = 2, 3, 1
chk("i170: the press hooked the game's list update", type(HOOKS.ClassTrainerFrame_Update) == "function")
HOOKS.ClassTrainerFrame_Update()
chk("i170 cause 2: after the rebuild the row cell points at the frame showing OUR service", CELL[1].a.clickbutton == ROWS[1])
ROWS[1].sk = 9
HOOKS.ClassTrainerFrame_Update()
chk("i170 cause 2: our service scrolled off the page in the rebuild: the press dropped, said why", N.ptWant == nil and CELL[1].a.clickbutton == nil
    and type(N.ptw) == "string" and (N.ptwn or 0) >= 1)
ROWS[1].sk, ROWS[2].sk, ROWS[3].sk = 1, 2, 3

-- ---- cause 3: the game did NOT take our selection (the row click went elsewhere / the window kept its own)
GAME.t = 3200
local nb = #BOUGHT
ACT.train(tostring(g2.i), g2.k)
local wn = N.ptwn or 0
CELL[1].a.clickbutton = ROWS[3]          -- as a stale row would
tapRow()
chk("i170 cause 3: selection not ours: Train stays disarmed, the press dropped with its reason", CELL[0].a.type == nil and N.ptWant == nil and (N.ptwn or 0) == wn + 1)
tapTrain()
chk("i170 cause 3: and the Train tap that follows buys nothing", #BOUGHT == nb)
-- the game's Train button off for it (cannot afford, the window disagrees): no Train
GAME.t = 3300
ACT.train(tostring(g2.i), g2.k)
TRAIN_ON = false
tapRow()
chk("i170: the game's Train button off: never armed", CELL[0].a.type == nil and N.ptWant == nil)
TRAIN_ON = true

-- ---- cause 1b (i170b): off the game's visible page: the game's OWN list is scrolled there by the app's real clicks, then trained
-- timers of delay d, run once each (a timer set while running waits for the next call); the clock moves on by dt first
local function runTimers(d, dt)
    GAME.t = GAME.t + (dt or 0)
    local due, keep = {}, {}
    for _, t in ipairs(TIMERS) do if math.abs(t.d - d) < 1e-6 then due[#due + 1] = t else keep[#keep + 1] = t end end
    TIMERS = keep
    for _, t in ipairs(due) do t.fn() end
    return #due
end
-- the screen: UI 1000 x 500 units, physical 2000 x 1000 px (2 px a unit); the game's scroll bar beside the list
GetPhysicalScreenSize = function() return 2000, 1000 end
local function box(l, t, w, h) return { GetLeft = function() return l end, GetTop = function() return t end, GetWidth = function() return w end,
    GetHeight = function() return h end, GetEffectiveScale = function() return 1 end, IsShown = function() return true end } end
rawset(_G, "UIParent", { GetWidth = function() return 1000 end, GetHeight = function() return 500 end, GetEffectiveScale = function() return 1 end,
    GetChildren = function() end })
local THUMB = box(300, 390, 8, 20)                     -- near the top of the track: the list scrolled to its start
ClassTrainerFrame.ScrollBox.GetLeft, ClassTrainerFrame.ScrollBox.GetTop = function() return 100 end, function() return 400 end
ClassTrainerFrame.ScrollBox.GetWidth, ClassTrainerFrame.ScrollBox.GetHeight = function() return 200 end, function() return 165 end
ClassTrainerFrame.ScrollBox.GetEffectiveScale, ClassTrainerFrame.ScrollBox.IsShown = function() return 1 end, function() return true end
ClassTrainerFrame.ScrollBar = { Back = box(300, 400, 8, 8), Forward = box(300, 243, 8, 8), Track = box(300, 392, 8, 140) }
ClassTrainerFrame.ScrollBar.Track.Thumb = THUMB
ClassTrainerFrame.skillIndexToDisplayIndex = setmetatable({}, { __index = function(_, k) return k end })
local SAVED = SVC
SVC = {}
for k = 1, 12 do SVC[k] = { "Skill" .. k, "available", "Rank 1" } end
local function page(first) for r = 1, 3 do ROWS[r].sk = first + r - 1 end end
page(1)
GAME.t = 3400
TIMERS = {}
local ptc0, nsel = N.ptc, #SELECTED
doc = build()
local s10 = svcOf(doc, "Skill10", "Rank 1")
ok, why = ACT.train(tostring(s10.i), s10.k)
local pts = build().trainer.pts
chk("i170b: off the page: the press goes on (" .. tostring(why) .. ")", ok == true)
chk("i170b: never the old select from our code, not yet counted for the row taps", #SELECTED == nsel and N.ptc == ptc0 and CELL[0].a.type == nil)
chk("i170b: the document asks for a scroll click: down, 7 rows, on the track below the thumb",
    pts and pts.dir == "down" and pts.rows == 7 and pts.step == "track" and pts.x == 608 and pts.y > 2 * (500 - 370) and pts.y < 2 * (500 - 252)
    and pts.n == build().trainer.ptsn)
chk("i170b: the arrow, track and list boxes are in it (game px, top-left)", pts and pts.arrow and pts.arrow.y == 514 and pts.track and pts.list
    and pts.list.x == 200 and pts.list.y == 200)
chk("i170b: the shield over the hidden window lets the app's click through meanwhile", WowCompanion.shieldPassing[ClassTrainerFrame] == true)
-- the app's track click scrolls one page (3 rows here); the add-on looks again at its next tick
local n0 = pts.n
page(4); runTimers(N.PT_TICK, 0.15)
pts = build().trainer.pts
chk("i170b: the list moved: the next click asked at once", pts and pts.n == n0 + 1 and pts.dir == "down")
page(7); runTimers(N.PT_TICK, 0.15)
pts = build().trainer.pts
chk("i170b: 1 row left: the arrow this time", pts and pts.step == "arrow" and pts.rows == 1 and pts.x == 608 and pts.y == 2 * (500 - 243) + 8)
page(8); runTimers(N.PT_TICK, 0.15)
chk("i170b: the row on the page: the row phase (counted, its row the target, the shield back, no scroll asked)",
    N.ptc == ptc0 + 1 and CELL[1].a.clickbutton == ROWS[3] and N.ptWant and not N.ptWant.scroll and build().trainer.pts == nil
    and WowCompanion.shieldPassing[ClassTrainerFrame] == nil)
local nb2 = #BOUGHT
tapRow(); tapTrain()
chk("i170b: and the scrolled-to service is the one bought", BOUGHT[#BOUGHT] == 10 and #BOUGHT == nb2 + 1)
-- up: the row above the page
GAME.t = 3420
page(8)
local s2 = svcOf(doc, "Skill2", "Rank 1")
ACT.train(tostring(s2.i), s2.k)
pts = build().trainer.pts
chk("i170b: above the page: up (6 rows)", pts and pts.dir == "up" and pts.rows == 6)
N.ptDrop(nil)
-- a click that never moves the list: asked again after PT_RETAP, at most PT_STEPS clicks, then a short note
GAME.t = 3440
page(1)
ACT.train(tostring(s10.i), s10.k)
local asks = 1
for _ = 1, 40 do
    if not N.ptWant then break end
    local before = N.ptsn
    runTimers(N.PT_TICK, 0.2)
    if N.ptsn ~= before then asks = asks + 1 end
end
chk("i170b: a list that never moves: " .. asks .. " clicks, then dropped with a short note", asks == N.PT_STEPS and N.ptWant == nil
    and N.ptw == "Couldn't reach it in the game's list" and WowCompanion.shieldPassing[ClassTrainerFrame] == nil)
SVC = SAVED
ROWS[1].sk, ROWS[2].sk, ROWS[3].sk = 1, 2, 3
ClassTrainerFrame.skillIndexToDisplayIndex = nil
TIMERS = {}

-- ---- cause 4: the game's window values tainted (an add-on rebuilt its list inside its own call): refused before a forbidden Train
TAINT.displayIndex = true
ok, why = ACT.train(tostring(g2.i), g2.k)
chk("i170 cause 4: a tainted row refused, the taint named in the document (" .. tostring(why) .. ")", ok == false and N.ptTaint == "SomeAddon"
    and build().trainer.ptt == "SomeAddon")
TAINT.displayIndex = nil

-- ---- cause 5: a doubled press, and a lost tap
GAME.t = 3500
TIMERS = {}
local ptc5 = N.ptc
ok = ACT.train(tostring(g2.i), g2.k)
local ok2, why2 = ACT.train(tostring(g2.i), g2.k)
chk("i170 cause 5: a second press while one is on its way is refused (" .. tostring(why2) .. ")", ok == true and ok2 == false and N.ptc == ptc5 + 1)
local fired = runTimers(N.PT_TTL, N.PT_TTL) > 0
chk("i170: a press whose taps never came times out (dropped, said why)", fired and N.ptWant == nil and N.ptw ~= nil)
GAME.t = 3600
ok = ACT.train(tostring(g2.i), g2.k)
chk("i170: after the timeout the next press goes", ok == true)
pcall(ON.TRAINER_CLOSED)
chk("i170: the window closing ends a press on its way", N.ptWant == nil and CELL[0].a.type == nil)
pcall(ON.TRAINER_SHOW)

-- ---- training points (i142)
GAME.t = 3700
PTS_USED = 195
local okp, whyp = ACT.train(tostring(g2.i), g2.k)
chk("i142: a skill costing more training points than the pet has left is refused (" .. tostring(whyp) .. ")", okp == false and whyp == "not enough training points")
PTS_USED = 0

-- ---- "shows stuff that's already known regardless of the filter"
SVC = {
    { "Bite", "available", "Rank 1" },
    { "Growl", "available", "Rank 1" },    -- the pet knows Growl 2: this client still says "available"
    { "Growl", "available", "Rank 2" },
    { "Growl", "available", "Rank 3" },
    { "Dash", "used", "Rank 1" },           -- "used" although the game's "used" filter is off
    { "Cower", "unavailable", "" },
}
PETBOOK = { { "Growl", "Rank 2" }, { "Bite", nil } }
doc = build()
chk("i170 cause 6: the PET's book decides: Growl 1 and 2 (the pet has 2) are not listed with Already Known off",
    svcOf(doc, "Growl", "Rank 1") == nil and svcOf(doc, "Growl", "Rank 2") == nil)
chk("i170 cause 6: a rank above the pet's stays learnable", svcOf(doc, "Growl", "Rank 3") and svcOf(doc, "Growl", "Rank 3").cat == "available")
chk("i170 cause 6: a book entry without a rank never hides a ranked service (unsure = shown)", svcOf(doc, "Bite", "Rank 1") ~= nil)
chk("i170 cause 7: a \"used\" row the game sent with its \"used\" filter off is left out", svcOf(doc, "Dash", "Rank 1") == nil)
chk("i170: the count left out is in the document", doc.trainer.hid == 3)
chk("i170: the kept rows keep the game's own index", svcOf(doc, "Growl", "Rank 3").i == 4 and svcOf(doc, "Cower", "").i == 6)
FILT.used = true
doc = build()
local k1 = svcOf(doc, "Growl", "Rank 1")
chk("i170: with Already Known on they show, as known (used, pk)", k1 and k1.cat == "used" and k1.pk == true and svcOf(doc, "Dash", "Rank 1") ~= nil)
ok, why = ACT.train(tostring(k1.i), k1.k)
chk("i170 cause 6: a press on a skill the pet knows is refused (" .. tostring(why) .. ")", ok == false and why == "your pet already knows this")
FILT.unavailable = false
doc = build()
chk("i170: the Unavailable filter off leaves the unavailable rows out", svcOf(doc, "Cower", "") == nil)
FILT.unavailable, FILT.used = true, false

-- ---- the tooltip follows the identity too
local looked
C_TooltipInfo = { GetTrainerService = function(i) looked = i; return { lines = {} } end }
pcall(N.tipOf, "train 2 " .. svcOf(build(), "Growl", "Rank 3").k)
chk("i170: the tooltip of a moved service is read at its index now", looked == 4)

-- ---- a class trainer keeps its direct buy, by identity
TYPE_NOW = 0
PETBOOK = {}
SVC = { { "Fireball", "available", "Rank 2" }, { "Frostbolt", "available", "Rank 1" } }
doc = build()
local fb = svcOf(doc, "Frostbolt", "Rank 1")
table.remove(SVC, 1)
BOUGHT = {}
ok = ACT.train(tostring(fb.i), fb.k)
chk("class trainer: the direct buy, at the service's index NOW", ok == true and BOUGHT[1] == 1)
ok, why = ACT.train("1")
chk("class trainer: the old \"train <i>\" (no identity) still works", ok == true and BOUGHT[2] == 1)
return table.concat(out, "\n")
