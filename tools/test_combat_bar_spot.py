"""MOVABLE HOME PHASE 3, THE COMBAT BAR ON THE TOP SCREEN ("Wings", WowCompanion/ActionBar.lua nbar7; SPEC.txt section 11, final_07 /
final_07b; the user's rulings 2026-11-20: D1 "In fights: Top screen" is OPT-IN, D7 Wings with an auto spot unless placed), off-device.

    python -B tools/test_combat_bar_spot.py [--shots DIR]

The REAL Safety.lua + ActionBar.lua under test_nintendo_bar.py's stub game (Blizzard frames are proxies that log any method but the
read-only getters and SetAlpha; strict secrets raise on a compare / index / arithmetic and are counted), with MOCKS for everything the
Wings read: our pieces (WC.prd - the HUD bars, pet strip, aura host; WC.compass; WC.uf - the target bar with its cast strip, ToT, Y
prompt, the party column and Party mode's rows under the HUD; WC.abox's aura hosts; Polestar; the top chat strip), the game's shown
frames (WC.gameLay: MinimapCluster, ObjectiveTrackerFrame, the stock gamepad bar, ChatFrame1 - their rects SECRET in a fight), the layout
store (WC.lay: entries, the tap pad's forbidden band, the editor), PadMap's flag (combatTopOn) and the router's switch (WC.tgGrown).
Checks:
  A THE SOLVER (NB.fightSolve, pure; nbar14 MOVE BEFORE SHRINKING): 1 wings beside the HUD level with its middle, 60 px gaps (HUD_MARGIN
    60 from the block), clamped 16 px above the bottom, at k 1, pushed outward in 24 px steps (the stock bar shown); 2 raised in 24 px
    steps at k 1 (a frame under them); 3 joined above the HUD at k 1 (both sides blocked); only then 1 / 2 at k 0.9 / 0.8; 4 a grid scan
    at k 0.8 over the lower 60 %; 5 the piece's own spot (nothing fits) - and a placed
    spot ALWAYS wins (pushed under the tap pad's band if it would cover it); the lane (the column under each half clear: the slide's
    path); several top layouts (the HUD moved / resized, Party mode's taller block, the target bar lowered, a left party column): every
    result inside the screen, never over the tap pad band, cases 1-4 never within 24 px of an obstacle.
  B THE OPT-IN: the flag off -> nothing built, nothing shown, in fights too; the piece listed in the top layout editor only while
    Nintendo mode or the flag is on.
  C THE LIVE BAR: placed out of combat (the game's frames read then), the fight's switch -> nbar11 THE SLIDE: a 60 ms hold, then up
    from wholly below the top screen's bottom edge over 0.26 s on the ease-out (0, 0, 0.2, 1), alpha 1 all the way, the D-pad diamond's
    centre at the solved left spot and the faces' at the right by 0.32 s; a reversal mid-way goes on from where it is; FROZEN: a game frame
    shown mid-fight is never read (rectNow not called, no secret touched) and does not move it; Party mode's lift (our own frames) re-fits
    it with a 0.24 s glide; the top chat strip dips it to 35 %; the chips light with ZL / ZR; the swing lines run; B's give-back (the
    router's switch false) / the fight's end slides it back down past the edge over 0.2 s (ease-in) and hides it; no lane = the fade +
    rise; nbar14 THE B RULE: B's give-back IN the fight (the router's switch false) slides the Wings down too, the next armed press brings
    them back up, PadMap's fightSw wins over tgGrown; Party mode (nbar12, the user's ruling C 11-22: "It should stay at the top in party
    mode"): the Wings in its FIGHTS only (on
    PadMap's `live`, pinned to the app's PartyModel.onLive over on / aside / fight / raid) - an attack armed before the pull keeps them
    off; the router's Y unbound (tgGrown nil) = the armed auto-attack / auto-repeat, as the app's grow.
  I nbar12 PARTY MODE'S FIGHT: the Wings shown with the PARTY layer (the pick's arrows, the party spells by layer, Y's chain, B fixed,
    the chord rows), raids too; the cfg PadMap sent last (cfg() - the spellbook walk - never called in the fight); a Select press mid-fight
    swaps the layer in place (re-fit against ours, redrawn that tick, still shown); Hub's rows and Select tab in the HUD block; nbar14
    PARTY MODE CENTRED: the block's shift to the screen's centre told to Trail (NB.centreDx), the Wings either side of the centred
    block, stable once Trail has applied it, back to 0 at the fight's end; nbar15 (the user's pick 11-23, "Centre the rows"): our HUD
    bars off - Hub's rows and Select tab alone centred (less the shift PF.g says they were drawn with), the Wings either side of
    them, stable at every stage of Hub's fade; no Trail P.partyGeo to carry it: the rows an obstacle where they stand, as before.
  D NINTENDO MODE: the bar glides from its stock spot to the wings and back (alpha 1 all the way) and ends at the stock spot again.
  E THE EDITOR: the flag on, Nintendo off, the editor open out of combat -> the bar shows at its piece's spot; a placed spot = the fight's.
  F LEAVE NO TRACE: no Secure template, EnableMouse never true, no SetBinding / CVar / macro call, nothing of Blizzard's moved, shown or
    hidden, no secret compared (none raised), our frames read through isPlain (a secret of ours skipped, not compared).
  H nbar11 COMBAT SAFETY OF THE SLIDE: the lockdown on from frame 0 through a whole fight in and out - no AnimationGroup, no template
    but the plain cooldown swipe, no binding / CVar / macro call, nothing of Blizzard's touched but reads, every SetPoint's relative
    frame ours or UIParent, no reserved / game rect read in the fight; the reserved cast bar read out of combat while hidden.
--shots renders the top screen (the game capture in the spec folder under the pieces and the solved wings) as PNGs.
Needs lupa (PIL for --shots). Exit code 1 on any failure."""
import math
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from lupa import lua51 as lupa  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ADDONS = os.path.join(ROOT, "companion", "app", "src", "main", "assets", "companion", "addons")
SAFETY = os.path.join(ADDONS, "WowCompanion", "Safety.lua")
AB = os.path.join(ADDONS, "WowCompanion", "ActionBar.lua")
PADMAP = os.path.join(ADDONS, "WowCompanionSpells", "PadMap.lua")
KT = os.path.join(ROOT, "companion", "app", "src", "main", "java", "com", "jrees", "wowcompanion")
NBT = open(os.path.join(ROOT, "tools", "test_nintendo_bar.py"), encoding="utf-8").read()
STUB = re.search(r"\nSTUB = r'''(.*?)'''", NBT, re.S).group(1)
SCENE = re.search(r"\nSCENE = r'''(.*?)'''", NBT, re.S).group(1)
SCRATCH = "C:/Users/Jrees/AppData/Local/Temp/claude/6c5148a90427b4bc/789b5138-341f-4ef8-8102-099551ca3a8a/scratchpad"
SHOTS = SCRATCH + "/home-p3"
BG = SCRATCH + "/homeedit/top_bg.png"
ICONS = SCRATCH + "/homeedit"

# ---------------------------------------------------------------------------------------------------------------- the mocks
MOCKS = r'''
local F = getmetatable(UIParent)                    -- the stub's frame methods (test_nintendo_bar.py's local F)
-- our frames with a rect (1080-line px from the top-left; UIParent units = px here: the screen's factor is 1)
function F:IsVisible()
    if self.shown == false then return false end
    local p = self.parent
    while p ~= nil do if p.shown == false then return false end; p = p.parent end
    return true
end
local function rv(self, v) if self.secretIn and COMBAT then return SECRET() end; return v end
function F:GetLeft() if self.r then return rv(self, self.r.l) end end
function F:GetTop() if self.r then return rv(self, 1080 - self.r.t) end end
function F:GetWidth() if self.r then return rv(self, self.r.w) end; return self.w end
function F:GetHeight() if self.r then return rv(self, self.r.h) end; return self.h end
function MOCKF(l, t, w, h, parent)
    local f = CreateFrame("Frame", nil, parent or UIParent)
    f.r = { l = l, t = t, w = w, h = h }
    return f
end
function SETR(f, l, t, w, h) f.r = { l = l, t = t, w = w, h = h } end

local WC = WowCompanion
-- the layout store (TapPad pad22's shape): entries by id, the screen, the pad's forbidden zones, the editor
LAYE = {}
WC.lay = { editing = false,
           screen = function() return 1920, 1080, 1 end,
           get = function(id) return LAYE[id] end,
           u = function(px) return px end,
           forbidden = function() return { { x = 718, y = 22, w = 484, h = 158, why = "pad" }, { x = 948, y = 417, w = 24, h = 24, why = "sink" } } end }
-- a layout piece: default anchor, size, offset of the rect from it, shown
function PIECE(id, ax, ay, w, h, offc, on)
    return { id = id, base = function() return ax, ay end, size = function(e) local s = ((e and e.s) or 100) / 100; return w * s, h * s end,
             off = function(ww, hh) if offc then return -ww / 2, 0 end; return 0, 0 end, on = on, apply = function() end }
end
HUD_ON, TGT_ON, CP_ON, PARTY_ON, CHAT_UP, PS_ON = true, true, true, false, false, false
-- the HUD: bars 280 x 32 at x 820, y 890 (Trail prd: top 350 below the centre), its aura host under the bars
WC.prd = { lay = PIECE("hud", 960, 890, 280, 32, true, function() return HUD_ON end) }
WC.prd.f = MOCKF(820, 890, 280, 32)
WC.prd.box = { host = MOCKF(820, 926, 280, 26) }
WC.compass = { lay = PIECE("compass", 960, 4, 860, 76, true, function() return CP_ON end) }
-- the target bar (follows the compass) and its parts; the party column on the left
WC.uf = { layTarget = PIECE("target", 790, 86, 372, 46, false, function() return TGT_ON end),
          layParty = PIECE("party", 24, 250, 300, 256, false, function() return PARTY_ON end),
          pf = { at = "left", frames = {} }, tb = {} }
WC.uf.tb.f = MOCKF(790, 86, 372, 46)
WC.uf.tb.f.cast = MOCKF(790, 134, 372, 15, WC.uf.tb.f)
WC.uf.tb.f.prompt = MOCKF(1170, 90, 40, 40, WC.uf.tb.f)
WC.uf.tb.f.tot = MOCKF(1170, 134, 140, 30, WC.uf.tb.f)
WC.uf.tb.f.tot.shown = false
WC.abox = { boxes = { hud = { host = WC.prd.box.host }, target = { host = MOCKF(790, 152, 312, 28) } } }
WC.psLay = { pieces = { psarrow = PIECE("psarrow", 960, 140, 60, 60, true, function() return PS_ON end) } }
WC.chatTop = { lay = PIECE("chat", 960, 655, 1240, 441, true, function() return CHAT_UP end) }
-- the game's frames (Hub hub59 WC.gameLay's shape): shown ones listed; rectNow refuses nothing but a fight makes it SECRET
GAME, GAMEIDS = {}, {}
RECTNOW, RECTNOW_COMBAT = 0, 0
function GAMEF(id, x, y, w, h, shown) GAME[id] = { x = x, y = y, w = w, h = h, shown = shown ~= false }; GAMEIDS[#GAMEIDS + 1] = id end
GAMEF("g.MinimapCluster", 1590, 0, 330, 390)
GAMEF("g.ObjectiveTrackerFrame", 1560, 420, 340, 300, false)
GAMEF("g.GamepadMainActionBarFrame", 640, 944, 640, 120, false)
GAMEF("g.ChatFrame1", 20, 660, 560, 380, false)
GAMEF("g.LowStrip", 300, 1060, 1320, 20, false)       -- nbar14: a thin frame under the halves (no lane: the fade + rise)
WC.gameLay = { ids = GAMEIDS, find = function() end,
               shown = function(id) local g = GAME[id]; return g ~= nil and g.shown end,
               frame = function(id) return GAME[id] end,
               rectNow = function(f)
                   RECTNOW = RECTNOW + 1
                   if COMBAT then RECTNOW_COMBAT = RECTNOW_COMBAT + 1; return { x = SECRET(), y = SECRET(), w = SECRET(), h = SECRET() } end
                   return { x = f.x, y = f.y, w = f.w, h = f.h, es = 1 }
               end }
-- the router's switch and PadMap's flag
TG, CTOP = false, false
WC.tgGrown = function() return TG end
FLAGREADS = 0
WC.padMap.combatTopOn = function() FLAGREADS = FLAGREADS + 1; return CTOP end
-- nbar13: PadMap m187's "Cast bar in fights: Top screen" and Hub hub76's word (a spy: every call's value)
CCB = false
WC.padMap.combatCastTopOn = function() return CCB end
-- nbar14: PadMap m188's read-only word on the game's own swing timer, and its router switch (nil = an older PadMap: tgGrown decides)
GSW, FSW = false, nil
WC.padMap.gameSwingOn = function() return GSW end
WC.padMap.fightSw = function() return FSW end
HUBCF = {}
WC.hubCastFight = function(on) HUBCF[#HUBCF + 1] = on end
function DRV(dt) local d = WC.nbar.drv; d.onOnUpdate(d, dt); local f = WC.nbar.f; if f and f.shown and f.onOnUpdate then f.onOnUpdate(f, dt) end end
function EV(e) local d = WC.nbar.drv; d.onOnEvent(d, e) end
function RUN(sec, dt) dt = dt or 0.02; local n = math.floor(sec / dt + 0.5); for _ = 1, n do NOW = NOW + dt; DRV(dt) end end
-- a button's centre in px (top-left origin): the bar's anchor (UIParent BOTTOMLEFT, its units) + the button's, at the bar's scale
function BCENTRE(p)
    local f = WC.nbar.f
    local fp, bp = f.pts[1], f.btn[p].pts[1]
    local sc = f.scale or 1
    return (fp[4] + bp[4]) * sc, 1080 - (fp[5] + bp[5]) * sc
end
function DIAMOND(h) local x, y = 0, 0; for p = (h - 1) * 4 + 1, h * 4 do local a, b = BCENTRE(p); x, y = x + a / 4, y + b / 4 end; return x, y end
function PARTCENTRE(c)
    local f = WC.nbar.f
    local fp, cp = f.pts[1], c.pts[1]
    local sc = f.scale or 1
    return (fp[4] + cp[4]) * sc, 1080 - (fp[5] + cp[5]) * sc
end
'''


def boot(pre=""):
    L = lupa.LuaRuntime(unpack_returned_tuples=True)
    L.execute(STUB)
    L.execute(SCENE)
    L.execute("PADMAP_ON()")
    if pre:
        L.execute(pre)
    load = L.eval('function(src, name, a) local f, e = loadstring(src, name); if not f then error(e) end; return f(a) end')
    load(open(SAFETY, encoding="utf-8").read(), "@Safety.lua", "WowCompanion")
    load(open(AB, encoding="utf-8").read(), "@ActionBar.lua", "WowCompanion")
    L.execute(MOCKS)
    return L


fails = []
passes = 0


def chk(name, ok, got=''):
    global passes
    print(("PASS " if ok else "FAIL ") + name + ((" -> " + str(got)) if got != '' and not ok else ''))
    if ok:
        passes += 1
    else:
        fails.append(name)


def todict(v):
    if lupa.lua_type(v) == "table":
        return {k: todict(x) for k, x in v.items()}
    return v


def rect(t):
    return (t["x"], t["y"], t["w"], t["h"])


def over(a, b, m=0):
    return a[0] - m < b[0] + b[2] and b[0] < a[0] + a[2] + m and a[1] - m < b[1] + b[3] and b[1] < a[1] + a[3] + m


BAND = (718, 22, 484, 158)
BAND_SPEC = (720, 24, 480, 154)


def solve(L, inp):
    return todict(L.eval("WowCompanion.nbar.fightSolve")(L.table_from(inp, recursive=True) if hasattr(L, "table_from") else inp))


def tbl(L, o):
    if isinstance(o, dict):
        t = L.table()
        for k, v in o.items():
            t[k] = tbl(L, v)
        return t
    if isinstance(o, (list, tuple)):
        t = L.table()
        for i, v in enumerate(o):
            t[i + 1] = tbl(L, v)
        return t
    return o


def R(x, y, w, h, id_=None):
    d = {"x": x, "y": y, "w": w, "h": h}
    if id_:
        d["id"] = id_
    return d


def fsolve(L, **kw):
    inp = {"W": 1920, "H": 1080, "s": 1, "band": R(*BAND)}
    inp.update(kw)
    return todict(L.eval("WowCompanion.nbar.fightSolve")(tbl(L, inp)))


def invariants(name, r, inp_obst, s=1):
    rects = [rect(r["rects"][1]), rect(r["rects"][2])]
    ok_in = all(x[0] >= -0.01 and x[1] >= -0.01 and x[0] + x[2] <= 1920.01 and x[1] + x[3] <= 1080.01 for x in rects)
    chk(name + ": both halves inside the screen", ok_in, rects)
    chk(name + ": never over the tap pad band", not any(over(x, BAND) or over(x, BAND_SPEC) for x in rects), rects)
    if r["case"] in (1, 2, 3):
        hit = [o for o in inp_obst for x in rects if over(x, rect(o), 60 if o.get("id") in ("hud", "partyhud") else 24)]
        chk(name + ": case %s clears every obstacle by 24 px (nbar14: the HUD block by 60)" % r["case"], not hit, hit)


def party_pin(L):
    """the 11-22 review: ONE Party-mode predicate on both sides. The app's PartyModel.onLive (on && (aside == null || fight), read off
    PadMap's party document: on = PM.on(), aside = R.aside, fight = fighting()) and the add-on's PadMap export `live` the Wings switch on:
    the same truth table over on / aside / fight, a raid included (neither side gates raids)"""
    pm = open(PADMAP, encoding="utf-8").read()
    kt = open(os.path.join(KT, "PartyModel.kt"), encoding="utf-8").read()
    hv = open(os.path.join(KT, "HomeView.kt"), encoding="utf-8").read()
    ab = open(AB, encoding="utf-8").read()
    m = re.search(r"\n\s+live = (function\(\) return .*? end),\s*\n", pm)
    chk("C. review pin: PadMap's party export has `live`", m is not None)
    doc = re.search(r"local on = PM\.on\(\)\s*\n.*?return \{ on = on, aside = R\.aside or nil, box = box, fight = fighting\(\),", pm, re.S)
    chk("C. review pin: the party document's on / aside / fight are PM.on() / R.aside / fighting() (what the app reads)", doc is not None)
    chk("C. review pin: the app's PartyModel.onLive = on && (aside == null || fight), Home's partyOnHere reads it",
        "val onLive: Boolean get() = on && (aside == null || fight)" in kt and "private fun partyOnHere(): Boolean = party.party?.onLive == true" in hv)
    nb = re.search(r"function NB\.partyOn\(\)(.*?)\nend", ab, re.S)
    chk("C. review pin: NB.partyOn reads PadMap's live (on() for an older PadMap), no raid gate, not Trail's P.partyModeOn",
        nb is not None and "p.live" in nb.group(1) and "IsInRaid" not in nb.group(1) and "partyModeOn" not in nb.group(1))
    if m is None:
        return
    mk = L.eval('function(src) local f, e = loadstring("local PM, R, fighting = ...; return " .. src); if not f then error(e) end; '
                'return function(on, aside, fight, raid) IsInRaid = function() return raid end; '
                'return f({ on = function() return on end }, { aside = aside }, function() return fight end)() end end')
    live = mk(m.group(1))
    bad = []
    for on in (False, True):
        for aside in (None, "room", "GameMenuFrame"):
            for fight in (False, True):
                for raid in (False, True):
                    app = on and (aside is None or fight)
                    got = live(on, aside, fight, raid)
                    if got is not app:
                        bad.append((on, aside, fight, raid, got, app))
    chk("C. review pin: PadMap's live == the app's onLive over on x aside (none / room / menu) x fight x raid (24 states)", not bad, bad[:4])


def main():
    out_dir = SHOTS
    if "--shots" in sys.argv:
        out_dir = sys.argv[sys.argv.index("--shots") + 1]
    L = boot()
    G = L.globals()
    W = G.WowCompanion
    NB = W.nbar
    src = open(AB, encoding="utf-8").read()
    chk("0. ActionBar's tag nbar13 and nbar7's / nbar8's / nbar9's / nbar10's / nbar11's notes", 'local NB = { V = "nbar15"' in src
        and "nbar12 THE WINGS' PLATES AND PARTY MODE" in src and "nbar11 THE WINGS REWORK" in src and "nbar11 COMBAT SAFETY OF THE SLIDE" in src and "nbar7 THE COMBAT BAR ON THE TOP SCREEN" in src
        and "nbar8 (the fix pass on movable Home)" in src and "nbar9 (movable Home fix round 1)" in src
        and "nbar10 (movable Home fix round 2)" in src)
    chk("0. nbar9: the build gate has no combat test (display-only frames are legal in a fight)",
        "if flag and not NB.built then pcall(NB.build) end" in src and "not NB.built and not combat" not in src)
    chk("0. ActionBar's main chunk loads under Lua 5.1 (the 200-locals limit kept)", NB is not None and NB.fightSolve is not None)

    # ------------------------------------------------------------------------------------------------ A the solver
    hw, hh, ax, ay = L.eval("WowCompanion.nbar.halfBox")()
    # the 11-22 review: the box holds what a half DRAWS - the slots' focus glow on its top and its inner side, the glow + the 2-unit press
    # below it; nbar12 (ruling A): the outer column - the L / R plate, the chip under it, Party mode's chord row. nbar14 THE BAND'S SHAPE:
    # the flattened diamond (OFF_X 47.5 across, OFF_Y 25 up / down): the inner glow 47.5 + 23 + 15 = 85.5, up 25 + 23 + 15 = 63; the
    # plate 64 x 50 at 70.5 + 6 + 34 = 110.5 (out to 142.5); down: the swing lines 48 + 8 + 6 + 4 + 6 = 72
    chk("A. nbar14 one half: outer 142.5 (the 64-wide plate at 110.5), inner 85.5 / up 63 (the focus glow of the flattened diamond), down 72 "
        "(the swing lines); the diamond's centre", (hw, hh, ax, ay) == (228, 135, 142.5, 63), (hw, hh, ax, ay))
    og = todict(L.eval("WowCompanion.nbar.outerGeo")())
    chk("A. nbar14 the outer column: x 110.5, the plate at +23 (its top level with the diamond's top slot), the chip at -20, the chord "
        "row at -46", og == {"x": 110.5, "plateY": 23, "chipY": -20, "chordY": -46, "chordDX": 19}, og)
    chk("A. nbar14 the band's size and shape: MATCH_S 1.75, OFF_X 47.5 / OFF_Y 25, GAP 60, HUD_MARGIN 60, MIN_K 0.8, PUSH_MAX 228",
        (NB.TOP.MATCH_S, NB.TOP.OFF_X, NB.TOP.OFF_Y, NB.TOP.GAP, NB.TOP.HUD_MARGIN, NB.TOP.MIN_K, NB.TOP.PUSH_MAX) == (1.75, 47.5, 25, 60, 60, 0.8, 228))
    hud = R(820, 890, 280, 58)                                   # the bars + the aura host
    ours = [R(820, 890, 280, 58, "hud"), R(530, 4, 860, 76, "compass"), R(790, 86, 372, 94, "target")]
    game = [R(1590, 0, 330, 390, "g.MinimapCluster")]
    r = fsolve(L, hud=hud, ours=ours, game=game, stock=R(960, 984, 0, 0))
    chk("A1. default (final_07): case 1, the wings", r["case"] == 1 and r["form"] == "wings", (r["case"], r["form"]))
    lr, rr = rect(r["rects"][1]), rect(r["rects"][2])
    chk("A1. nbar14: 60 px each side of the HUD block", abs(lr[0] + lr[2] - (820 - 60)) < 0.01 and abs(rr[0] - (1100 + 60)) < 0.01, (lr, rr))
    chk("A1. level with the block's middle (y 919), both halves on one line", r["l"]["y"] == r["r"]["y"] == 919, (r["l"], r["r"]))
    chk("A1. the D-pad's diamond left (x 760 - 85.5 = 674.5), the faces' right (x 1245.5)", (r["l"]["x"], r["r"]["x"]) == (674.5, 1245.5),
        (r["l"], r["r"]))
    chk("A1. nbar14: not pushed (push 0), not raised", r["push"] == 0 and r["raise"] == 0, (r["push"], r["raise"]))
    invariants("A1", r, ours + game)
    chk("A1. nbar11: full size (k 1) and the lane under both halves clear (the slide's path)", r["k"] == 1 and r["lane"] is True, (r["k"], r["lane"]))
    # the bottom clamp: a HUD low on the screen
    low = R(820, 1000, 280, 58)
    r = fsolve(L, hud=low, ours=[dict(low, id="hud")], stock=R(960, 984, 0, 0))
    chk("A1. clamped 16 px above the bottom edge", abs(r["rects"][1]["y"] + r["rects"][1]["h"] - (1080 - 16)) < 0.01 and r["case"] == 1,
        (r["case"], r["rects"][1]))
    # A2: the stock bar shown (Controller UI On) beside the HUD: nbar14 pushes the halves outward past it at k 1 (never shrunk first)
    stockbar = R(640, 944, 640, 120, "g.GamepadMainActionBarFrame")
    r = fsolve(L, hud=hud, ours=ours, game=game + [stockbar], stock=R(960, 984, 0, 0))
    chk("A2. nbar14 the stock bar shown: moved before shrunk - case 1 at k 1, pushed outward in 24 px steps (not raised at 0.8)",
        r["case"] == 1 and r["k"] == 1 and r["push"] > 0 and r["push"] % 24 == 0 and r["raise"] == 0, (r["case"], r["k"], r["push"], r["raise"]))
    chk("A2. ... the least push that clears it (24 px off its side, the next smaller push would not)",
        r["rects"][1]["x"] + r["rects"][1]["w"] <= 640 - 24 + 1e-6 and r["rects"][1]["x"] + r["rects"][1]["w"] + 24 > 640 - 24, r["rects"][1])
    chk("A2. ... still level with the HUD's middle, both halves pushed alike", r["l"]["y"] == r["r"]["y"] == 919
        and abs((820 - r["rects"][1]["x"] - r["rects"][1]["w"]) - (r["rects"][2]["x"] - 1100)) < 1e-6, (r["l"], r["r"]))
    chk("A2. ... the stock bar not under the pushed halves: the lane clear (the slide)", r["lane"] is True, r["lane"])
    invariants("A2", r, ours + game + [stockbar])
    # nbar14: a frame under the full-size halves across the whole width (no push clears it) -> raised at k 1 (24 px steps), never shrunk
    nudge = [R(380, 960, 1160, 120, "g.SomethingLow")]
    r = fsolve(L, hud=hud, ours=ours, game=game + nudge, stock=R(960, 984, 0, 0))
    chk("A2. nbar14 move before shrink: a frame under the halves no push clears -> case 2 at k 1, raised 72 px (24 px steps), level, beside",
        r["case"] == 2 and r["k"] == 1 and r["raise"] == 72 and r["l"]["y"] == r["r"]["y"] == 919 - 72 and r["push"] == 0,
        (r["case"], r["k"], r["raise"], r["l"], r["push"]))
    chk("A2. ... no lane (the frame under them: the fade + rise)", r["lane"] is False, r["lane"])
    invariants("A2 raised", r, ours + game + nudge)
    # nbar14: only when nothing fits at k 1 (beside, pushed, raised, joined): 0.9
    tight = [R(380, 960, 1160, 120, "g.SomethingLow"), R(0, 0, 1920, 780, "g.Ceiling")]
    r = fsolve(L, hud=hud, ours=ours, game=game + tight, stock=R(960, 984, 0, 0))
    chk("A2. nbar14: k 1 blocked everywhere (a ceiling at 780, a floor at 960: 132 px for the 135-px halves) -> only then shrunk, never under 0.8 (case %s, k %s)"
        % (r["case"], r["k"]), r["k"] < 1 and r["k"] >= 0.8 and r["case"] in (1, 2, 4), (r["case"], r["k"]))
    invariants("A2 shrunk", r, ours + game + tight)
    # A3: both sides blocked over the whole raise: joined above the HUD
    walls = [R(0, 560, 620, 520, "g.ChatFrame1"), R(1300, 560, 620, 520, "g.ObjectiveTrackerFrame")]
    r = fsolve(L, hud=hud, ours=ours, game=game + walls, stock=R(960, 984, 0, 0))
    chk("A3. both sides blocked: case 3, the halves joined above the HUD", r["case"] == 3 and r["form"] == "joined", (r["case"], r["form"]))
    chk("A3. centred over the HUD, 60 px above it", abs((r["l"]["x"] + r["r"]["x"]) / 2 - 960) < 0.01
        and abs(r["rects"][1]["y"] + r["rects"][1]["h"] - (890 - 60)) < 0.01, r)
    chk("A3. joined above the HUD: the HUD under it, so no lane (the fade + rise)", r["lane"] is False and r["k"] == 1, (r["lane"], r["k"]))
    invariants("A3", r, ours + game + walls)
    # A4: no HUD, or nothing fits: the piece's own spot, always accepted
    r = fsolve(L, ours=ours[1:], game=game, stock=R(960, 984, 0, 0))
    chk("A4. nbar14 no HUD: case 4, the grid scan at k 0.8 (MIN_K) - the bottom row, the middle first", r["case"] == 4 and r["k"] == 0.8
        and abs((r["l"]["x"] + r["r"]["x"]) / 2 - 960) < 1e-6 and abs(r["rects"][1]["y"] + r["rects"][1]["h"] - (1080 - 16)) < 1e-6, r)
    invariants("A4", r, ours[1:] + game)
    # the grid: the lower middle taken (a frame across it) -> the first clear spot outward / upward, inside the lower 60 %
    block = [R(560, 700, 800, 380, "g.Middle")]
    r = fsolve(L, ours=ours[1:], game=game + block, stock=R(960, 984, 0, 0))
    chk("A4. the grid scan steps outward / upward past an obstacle, inside the lower 60 % (y >= 432)", r["case"] == 4
        and min(r["rects"][1]["y"], r["rects"][2]["y"]) >= 0.4 * 1080 - 1e-6, r)
    invariants("A4 grid", r, ours[1:] + game + block)
    full = [R(0, 0, 1920, 1080, "g.Everything")]
    r = fsolve(L, hud=hud, ours=ours, game=game + full, stock=R(960, 984, 0, 0))
    chk("A5. nothing fits: case 5, the stock spot (always accepted, the last resort)", r["case"] == 5, r["case"])
    # a placed spot always wins (even where the wings would fit), the joined bar there; over the band -> pushed under it
    r = fsolve(L, hud=hud, ours=ours, game=game, stock=R(960, 984, 0, 0), placed=R(500, 600, 0, 0))
    chk("A5. a placed spot wins over the wings (Auto off): the joined bar there", r["case"] == "placed" and r["form"] == "joined"
        and (r["l"]["x"] + r["r"]["x"]) / 2 == 500 and r["l"]["y"] == 600, r)
    r = fsolve(L, hud=hud, ours=ours, game=game, stock=R(960, 984, 0, 0), placed=R(960, 60, 0, 0))
    chk("A5. a placed spot over the tap pad band: moved under it", r["case"] == "placed" and not any(over(rect(x), BAND) for x in r["rects"].values())
        and r["rects"][1]["y"] >= 22 + 158, r["rects"])
    # nbar10: the screen clamp runs BEFORE the band test (a top-left spot the clamp moves right never lands on the band), at MARGIN 24.
    # The editor keeps the piece (nbar14: the Wings' joined bar, 2 x (160 + 142.5) = 605 units wide) on the screen, so its centre x >= 302.5 s
    bad = []
    for s in (1.0, 1.281, 1.3, 1.336, 1.36, 1.392, 1.4, 1.406, 1.5, 1.75):
        for cx in range(int(302.5 * s) + 1, 720, 4):
            for cy in (int(73 * s) + 1, 100, 150, 200):
                rr = fsolve(L, s=s, hud=None, ours=[], game=[], stock=R(960, 984, 0, 0), placed=R(cx, cy, 0, 0))
                if any(over(rect(x), BAND_SPEC, 24) for x in rr["rects"].values()) or rr["l"]["x"] - 142.5 * s < -1e-6:
                    bad.append((s, cx, cy))
    chk("A5. nbar10: a placed spot near the left edge at band height, s 1.0-1.75 (the match x size): never within 24 px of the band, on screen",
        not bad, bad[:5])
    rr = fsolve(L, s=1.336, hud=None, ours=[], game=[], stock=R(960, 984, 0, 0), placed=R(312, 150, 0, 0))
    chk("A5. nbar10: the review's case (s 1.336, placed 312,150): pushed under the band, still left (x clamped to 302.5 s: nbar14's half)",
        rr["case"] == "placed" and abs((rr["l"]["x"] + rr["r"]["x"]) / 2 - 302.5 * 1.336) < 1e-6
        and rr["rects"][1]["y"] >= 24 + 154 + 24 - 1e-6, rr)
    # case 5 (the stock fallback) too: a stock spot moved to the top-left (the whole screen taken, so the grid finds nothing)
    rr = fsolve(L, s=1.336, hud=None, ours=[], game=[R(0, 0, 1920, 1080, "g.Wall")], stock=R(300, 150, 0, 0))
    chk("A5. nbar10: case 5's stock fallback at the top-left: clear of the band", rr["case"] == 5
        and not any(over(rect(x), BAND_SPEC, 24) for x in rr["rects"].values()), rr["rects"])
    # several top layouts: the HUD moved / resized, the target lowered, a left party column, Party mode's block
    layouts = {
        "hud left (dx -560)": (R(260, 890, 280, 58), []),
        "hud right, big (160 %)": (R(1300, 860, 448, 93), []),
        "hud high (dy -420)": (R(820, 470, 280, 58), []),
        "target lowered under the HUD's sides": (hud, [R(560, 840, 372, 94, "target2")]),
        "party column on the left, long": (hud, [R(24, 250, 300, 800, "party")]),
        "Party mode: the taller, wider block (final_07b)": (R(735, 795, 610, 215), []),
        "the HUD at the very left edge": (R(10, 890, 280, 58), []),
        "a big HUD (160 %, 400 wide) low": (R(640, 900, 640, 150), []),
    }
    for name, (h, extra) in layouts.items():
        ob = [dict(h, id="hud"), R(530, 4, 860, 76, "compass"), R(790, 86, 372, 94, "target")] + extra
        r = fsolve(L, hud=h, ours=ob, game=game, stock=R(960, 984, 0, 0))
        invariants("A6 " + name + " (case %s)" % r["case"], r, ob + game)
    rp = fsolve(L, hud=R(735, 795, 610, 215), ours=[R(735, 795, 610, 215, "hud")], game=game, stock=R(960, 984, 0, 0))
    chk("A6. Party mode: the wings re-hug the wider block (60 px off its sides, level with its middle)", rp["case"] == 1
        and abs(rp["rects"][1]["x"] + rp["rects"][1]["w"] - (735 - 60)) < 0.01 and abs(rp["rects"][2]["x"] - (1345 + 60)) < 0.01
        and rp["l"]["y"] == 795 + 215 / 2, rp)
    edge = fsolve(L, hud=R(10, 890, 280, 58), ours=[R(10, 890, 280, 58, "hud")], game=game, stock=R(960, 984, 0, 0))
    chk("A6. the HUD at the left edge: no room for the D-pad's half beside it nor joined above it -> the grid's spot (case 4), never the stock "
        "fallback", edge["case"] == 4, edge["case"])
    big = fsolve(L, hud=hud, ours=ours, game=game, stock=R(960, 984, 0, 0), s=1.5)
    chk("A6. the size scales the halves (150 %: 342 x 202.5)", abs(big["rects"][1]["w"] - 342) < 0.01 and abs(big["rects"][1]["h"] - 202.5) < 0.01
        and big["k"] == 1, big["rects"][1])
    chk("A7. the solver is pure: no frame read, no event, nothing created", G.RAISED == 0 and NB.top.reads == 0 and G.RECTNOW == 0)

    # ------------------------------------------------------------------------------------------------ B the opt-in
    G.TG, G.COMBAT = True, True
    L.execute('EV("PLAYER_REGEN_DISABLED")')
    G.FLAGREADS = 0
    L.execute('RUN(1.0)')
    chk("B. nbar9: the flag off, the driver reads it every FLAG_S (0.5 s), never every frame (50 frames: at most 3 reads)",
        1 <= G.FLAGREADS <= 3, G.FLAGREADS)
    chk("B. the flag off: in a fight nothing is built or shown (Grow / Same size: the add-on does nothing)", not NB.built and not NB.top.shown
        and NB.f is None, (NB.built, NB.top.shown))
    chk("B. the flag off: no frame read, no game frame read", NB.top.reads == 0 and G.RECTNOW == 0, (NB.top.reads, G.RECTNOW))
    chk("B. the Combat bar piece not listed in the editor (Nintendo off, flag off)", NB.lay.want() is False)
    G.TG, G.COMBAT = False, False
    L.execute('EV("PLAYER_REGEN_ENABLED")')
    G.CTOP = True
    chk("B. the flag on: the piece is listed in the top layout editor (so it can be placed)", NB.lay.want() is True and NB.lay.on() is False)
    pw, ph = L.eval("WowCompanion.nbar.lay.size")(None)
    chk("B. nbar14: the piece is the Wings' joined bar at the match size while the flag is on (2 x (160 + 142.5) x 1.75 wide, the "
        "flattened diamonds' 96 x 1.75 high)", abs(pw - 605 * 1.75) < 1e-6 and abs(ph - 96 * 1.75) < 1e-6, (pw, ph))

    # ------------------------------------------------------------------------------------------------ C the live bar
    L.execute('RUN(0.6)')                                         # nbar9: the flag is read every FLAG_S (0.5 s)
    chk("C. the flag on, out of combat: the frames built (out of combat), the spot solved, nothing shown yet", NB.built and NB.top.spot is not None
        and not NB.top.shown and NB.f.shown is False, (NB.built, NB.top.spot is not None, NB.top.shown))
    chk("C. out of combat the game's shown frames were read (plain)", G.RECTNOW >= 1 and G.RECTNOW_COMBAT == 0, (G.RECTNOW, G.RECTNOW_COMBAT))
    sp = todict(NB.top.spot)
    chk("C. the live spot: case 1 beside the live HUD block (bars + aura host: 820-1100 x 890-952), nbar14 at the match size (1.75 px per "
        "unit: 760 - 85.5 x 1.75)", sp["case"] == 1 and sp["l"]["x"] == 760 - 85.5 * 1.75 and sp["l"]["y"] == 921 and sp["s"] == 1.75
        and sp["k"] == 1, (sp["case"], sp["l"], sp["r"], sp["s"]))
    W.swingNote(2.0, 0)                                           # a main-hand swing running
    # the fight: PLAYER_REGEN_DISABLED (frozen), the router's switch on
    G.COMBAT = True
    L.execute('EV("PLAYER_REGEN_DISABLED")')
    G.TG = True
    reads0 = G.RECTNOW
    chk("C. nbar11: the lane under both halves clear -> the slide", sp["lane"] is True)
    L.execute('RUN(0.04, 0.02)')
    f = NB.f
    chk("C. nbar11: the 60 ms hold first (the band's first frames go first): nothing shown yet", not NB.top.shown and NB.top.form == "slide"
        and NB.top.hold is not None, (NB.top.shown, NB.top.form))
    L.execute('RUN(0.04, 0.02)')
    lx, ly = L.eval("DIAMOND")(1)
    rx, ry = L.eval("DIAMOND")(2)
    hb = L.eval("WowCompanion.nbar.halfBox")()
    sc_ = NB.top.spot.s
    d0 = L.eval("WowCompanion.nbar.slideDrop")(NB.top.spot, NB.top.spot)[0]
    chk("C. nbar11: the slide starts with both halves wholly below the top screen's bottom edge (from = spot + drop: the tops past 1080)",
        sp["l"]["y"] + d0 - hb[3] * sc_ >= 1080 and sp["r"]["y"] + d0 - hb[3] * sc_ >= 1080, (sp["l"]["y"], d0))
    chk("C. ... its first frame: shown, below the spot, alpha 1", NB.top.shown and ly > sp["l"]["y"] + 10 and ry > sp["r"]["y"] + 10
        and abs(f.alpha - 1) < 1e-6, (ly, ry, f.alpha))
    seq = []
    for _ in range(6):
        L.execute('RUN(0.02, 0.02)')
        seq.append(L.eval("DIAMOND")(1)[1])
    t_ = NB.top.t
    k_ = L.eval("WowCompanion.nbar.slideIn")(t_)
    chk("C. ... rising every frame (straight up, x fixed), on nbar14's IN_C (1/3, 0.5, 2/3, 1)", all(b < a for a, b in zip(seq, seq[1:]))
        and abs(seq[-1] - (sp["l"]["y"] + d0 * (1 - k_))) < 0.6 and abs(L.eval("DIAMOND")(1)[0] - sp["l"]["x"]) < 0.01, (seq, t_, k_))
    chk("C. ... alpha 1 all the way (the slide is the cue, no fade)", abs(f.alpha - 1) < 1e-6, f.alpha)
    L.execute('RUN(0.14, 0.02)')
    lx, ly = L.eval("DIAMOND")(1)
    rx, ry = L.eval("DIAMOND")(2)
    chk("C. in by 0.32 s (hold 0.06 + 0.26): alpha 1, settled", abs(f.alpha - 1) < 1e-6 and NB.top.t == 1, (f.alpha, NB.top.t))
    chk("C. the D-pad's diamond at the left spot, the faces' at the right (px)", abs(lx - sp["l"]["x"]) < 0.01 and abs(ly - sp["l"]["y"]) < 0.01
        and abs(rx - sp["r"]["x"]) < 0.01 and abs(ry - sp["r"]["y"]) < 0.01, ((lx, ly), (rx, ry), sp["l"], sp["r"]))
    zl = L.eval("PARTCENTRE")(f.chip[1])
    zr = L.eval("PARTCENTRE")(f.chip[2])
    pl = L.eval("PARTCENTRE")(f.plate[1])
    chk("C. nbar14 the ZL / ZR chips at the outer ends (past the flattened diamond's 70.5), under the L / R plates, the plates' tops level "
        "with the diamond's top slot", zl[0] < lx - 70.5 * 1.75 and zr[0] > rx + 70.5 * 1.75 and zl[1] > pl[1] and abs(zl[1] - (ly + 20 * 1.75)) < 0.01
        and abs(pl[1] - (ly - 23 * 1.75)) < 0.01 and f.chip[1].shown and f.chip[2].shown, (zl, zr, pl, (lx, ly)))
    b2 = L.eval("BCENTRE")(2)
    b1 = L.eval("BCENTRE")(1)
    chk("C. nbar14 the flattened diamond: Up 25 units over the centre, Left 47.5 across (x 1.75)", abs(b2[1] - (ly - 25 * 1.75)) < 0.01
        and abs(b1[0] - (lx - 47.5 * 1.75)) < 0.01, (b1, b2, (lx, ly)))
    chk("C. the layer word is the chips' (none over the middle)", f.layer.text == "", f.layer.text)
    # nbar13: "Cast bar in fights" Bottom screen (the default): no swing lines on the Wings (the app's strip carries them), Hub told nothing
    chk("C. nbar13 Cast bar in fights = Bottom screen (default): the Wings draw no swing line, Hub's game cast bar not asked for",
        not f.sw[1].shown and not f.sw[2].shown and len(list(L.eval("HUBCF").values())) == 0, (f.sw[1].shown, todict(L.eval("HUBCF"))))
    G.CCB = True
    L.execute('RUN(0.6, 0.02)')                                   # the flag is read every FLAG_S (0.5 s)
    chk("C. nbar13 Top screen: Hub told once that the Wings' fight shows the game's cast bar (WC.hubCastFight(true))",
        list(L.eval("HUBCF").values()) == [True], todict(L.eval("HUBCF")))
    # nbar14 (the user's pick "Keep ours on the bottom"): the game's own swing timer OFF -> no swing line on the Wings (the app's strip
    # keeps the swing on the bottom screen: one place)
    chk("C. nbar14 Top screen, the game's swing timer off (PadMap's gameSwingOn false): no swing line on the Wings (the bottom strip has it)",
        not f.sw[1].shown and not f.sw[2].shown and NB.top.gsw is False, (f.sw[1].shown, NB.top.gsw))
    G.GSW = True
    L.execute('RUN(0.6, 0.02)')                                   # (read with the flag every FLAG_S)
    chk("C. nbar14 ... the game's swing timer on: the Wings' swing line under the faces runs (main hand), as built",
        f.sw[1].shown and 0 < f.sw[1].k < 1 and not f.sw[2].shown and NB.top.gsw is True, (f.sw[1].shown, f.sw[1].k))
    sx, sy = L.eval("PARTCENTRE")(f.sw[1])
    chk("C. ... under the faces' diamond", abs(sx - rx) < 0.01 and sy > ry + 73, (sx, sy, rx, ry))
    G.LT = True
    L.execute('RUN(0.1)')
    chk("C. ZL held: the ZL chip lit, ZR not", f.chip[1].lit is True and f.chip[2].lit is False and NB.set == 2, (f.chip[1].lit, f.chip[2].lit, NB.set))
    G.LT = False
    L.execute('RUN(0.1)')
    # frozen: a game frame shown in the fight is never read and does not move the spot
    L.execute('GAME["g.ChatFrame1"].shown = true')
    L.execute('RUN(6.0, 0.05)')
    chk("C. frozen in the fight: the game's frames never read (rectNow not called), no secret touched",
        G.RECTNOW == reads0 and G.RECTNOW_COMBAT == 0 and G.RAISED == 0, (G.RECTNOW - reads0, G.RECTNOW_COMBAT, G.RAISED))
    sp2 = todict(NB.top.spot)
    chk("C. ... and the spot is the one placed out of combat (re-fit against ours only)", sp2["l"] == sp["l"] and sp2["r"] == sp["r"], (sp2["l"], sp["l"]))
    # one of OUR frames secret in the fight: skipped through isPlain, never compared
    L.execute('WowCompanion.uf.tb.f.cast.secretIn = true')
    L.execute('RUN(1.0, 0.05)')
    chk("C. a secret on one of our frames: skipped (isPlain), nothing raised", G.RAISED == 0, G.RAISED)
    # Party mode lifts the HUD in the fight: our frames move, the wings re-hug the taller block with a 0.24 s glide
    L.execute('SETR(WowCompanion.prd.f, 820, 800, 280, 32); SETR(WowCompanion.prd.box.host, 820, 772, 280, 26)')
    L.execute('WowCompanion.uf.pf.at = "hud"; for i = 1, 4 do local c = (i - 1) % 2; local r = math.floor((i - 1) / 2); '
              'WowCompanion.uf.pf.frames[i] = MOCKF(740 + c * 330, 870 + r * 76, 300, 64) end')
    L.execute('RUN(0.5, 0.05)')
    chk("C. Party mode in the fight: re-fit (our frames only), a glide under way", NB.top.mt < 1 or todict(NB.top.spot)["l"] != sp["l"],
        (NB.top.mt,))
    L.execute('RUN(0.3, 0.02)')
    sp3 = todict(NB.top.spot)
    lx, ly = L.eval("DIAMOND")(1)
    chk("C. ... the wings beside the taller, wider block (final_07b) and glided there", sp3["case"] == 1 and sp3["l"]["x"] < sp["l"]["x"]
        and abs(lx - sp3["l"]["x"]) < 0.01 and abs(ly - sp3["l"]["y"]) < 0.01 and NB.top.mt == 1, (sp3["l"], (lx, ly)))
    chk("C. ... still no game frame read in the fight", G.RECTNOW == reads0 and G.RECTNOW_COMBAT == 0)
    # the top chat strip: the bar dips to 35 %
    G.CHAT_UP = True
    L.execute('RUN(0.1)')
    chk("C. the top chat strip shown: 35 % alpha, the spot unchanged", abs(f.alpha - 0.35) < 1e-6 and todict(NB.top.spot)["l"] == sp3["l"], f.alpha)
    G.CHAT_UP = False
    L.execute('RUN(0.1)')
    chk("C. the strip gone: alpha 1 again", abs(f.alpha - 1) < 1e-6, f.alpha)
    # nbar14 THE B RULE (the user, 11-23): B gave the buttons back IN the fight (the router's switch false) -> the Wings slide back down
    # (nbar8 kept them to the fight's end); the next armed press (the router's switch true again) slides them back up
    spb = todict(NB.top.spot)
    G.TG = False
    L.execute('RUN(0.1, 0.02)')
    yb = L.eval("DIAMOND")(1)[1]
    chk("C. nbar14 B's give-back in the fight: the Wings slide back down (half way at 0.1 s, alpha 1)", NB.top.shown and NB.top.dir == -1
        and yb > spb["l"]["y"] + 1 and abs(f.alpha - 1) < 1e-6, (NB.top.dir, yb, spb["l"]["y"]))
    L.execute('RUN(0.14, 0.02)')
    chk("C. nbar14 ... gone after 0.2 s though the fight goes on (the lockdown still true)", not NB.top.shown and f.shown is False
        and G.COMBAT is True, (NB.top.shown, f.shown))
    chk("C. nbar14 ... the game's cast bar handed back to the player's Hide with them (WC.hubCastFight(false))",
        list(L.eval("HUBCF").values()) == [True, False], todict(L.eval("HUBCF")))
    G.TG = True
    L.execute('RUN(0.4, 0.02)')
    chk("C. nbar14 a harmful press re-arms the router (its switch true again, still in the fight): the Wings slide back up, settled",
        NB.top.shown and NB.top.t == 1 and abs(L.eval("DIAMOND")(1)[1] - spb["l"]["y"]) < 0.01, (NB.top.shown, NB.top.t))
    chk("C. nbar14 ... and Hub asked again for the game's cast bar", list(L.eval("HUBCF").values()) == [True, False, True], todict(L.eval("HUBCF")))
    # PadMap m188's fightSw (the router's switch over every button) wins over tgGrown (Y's alone): false there -> down, whatever Y says
    G.FSW = False
    L.execute('RUN(0.3, 0.02)')
    chk("C. nbar14 PadMap's fightSw false (B gave back) wins over tgGrown true: the Wings down", not NB.top.shown and NB.top.want is False,
        (NB.top.shown, NB.top.want))
    G.FSW, G.TG = True, False
    L.execute('RUN(0.4, 0.02)')
    chk("C. nbar14 ... fightSw true (Y unbound: tgGrown false / nil) -> up again", NB.top.shown and NB.top.want is True, (NB.top.shown, NB.top.want))
    G.FSW, G.TG = None, True
    L.execute('RUN(0.4, 0.02)')
    # the fight's end (the router's switch drops with it): out over 0.2 s, then hidden
    G.COMBAT, G.TG = False, False
    L.execute('EV("PLAYER_REGEN_ENABLED"); RUN(0.1, 0.02)')
    lyb = L.eval("DIAMOND")(1)[1]
    k_out = L.eval("WowCompanion.nbar.slideK")(NB.top.t, -1)
    spn = todict(NB.top.spot)
    dd = L.eval("WowCompanion.nbar.slideDrop")(NB.top.spot, NB.top.spot)[0]
    chk("C. nbar11 the fight's end: sliding back down (half way at 0.1 s), alpha 1, on the ease-in", NB.top.shown and abs(f.alpha - 1) < 1e-6
        and lyb > spn["l"]["y"] + 1 and abs(lyb - (spn["l"]["y"] + dd * (1 - k_out))) < 0.6, (f.alpha, lyb, spn["l"]["y"]))
    L.execute('RUN(0.12, 0.02)')
    chk("C. ... gone after 0.2 s (below the edge): hidden, the chips and swing lines too", not NB.top.shown and f.shown is False and not f.chip[1].shown
        and not f.sw[1].shown, (NB.top.shown, f.shown))
    chk("C. nbar13: the fight's end told Hub once (WC.hubCastFight(false): the player's Hide back on the game's cast bar)",
        list(L.eval("HUBCF").values()) == [True, False, True, False, True, False], todict(L.eval("HUBCF")))
    G.CCB = False
    L.execute('RUN(0.6, 0.02)')
    L.execute('RUN(0.1)')
    chk("C. after the fight: the spot solved again with the game's frames (ChatFrame1 now shown)", G.RECTNOW > reads0 and not NB.top.frozen,
        (G.RECTNOW, reads0))
    # an attack armed out of combat (the router's switch on, no fight): the wings show, solved fresh
    L.execute('WowCompanion.uf.pf.at = "left"; for i = 1, 4 do WowCompanion.uf.pf.frames[i].shown = false end; '
              'SETR(WowCompanion.prd.f, 820, 890, 280, 32); SETR(WowCompanion.prd.box.host, 820, 926, 280, 26); GAME["g.ChatFrame1"].shown = false')
    n0 = G.RECTNOW
    G.TG = True
    L.execute('RUN(0.3)')
    chk("C. an attack armed before the pull (out of combat): shown, the spot solved fresh first", NB.top.shown and G.RECTNOW > n0
        and abs(f.alpha - 1) < 1e-6, (NB.top.shown, G.RECTNOW - n0))
    G.TG = False
    L.execute('RUN(0.3)')
    # nbar11: a reversal mid-slide (a fight blip) goes on from where the bar is - no jump either way
    G.TG = True
    L.execute('RUN(0.18, 0.02)')
    y_in = L.eval("DIAMOND")(1)[1]
    G.TG = False
    L.execute('RUN(0.02, 0.02)')
    y_rev = L.eval("DIAMOND")(1)[1]
    G.TG = True
    L.execute('RUN(0.02, 0.02)')
    y_back = L.eval("DIAMOND")(1)[1]
    chk("C. nbar11: reversed mid-slide (in -> out -> in): each frame moves on from where it was (no jump), the right way",
        y_rev >= y_in - 0.01 and abs(y_rev - y_in) < 60 and y_back <= y_rev + 0.01 and abs(y_back - y_rev) < 60, (y_in, y_rev, y_back))
    G.TG = False
    L.execute('RUN(0.4)')
    chk("C. ... and out again: hidden", not NB.top.shown)
    # nbar11: Party mode on -> no Wings (the band keeps the controls below: ruling R0's default). The 11-22 review: on PadMap's `live`
    # (the app's PartyModel.onLive: on, and not aside unless fighting) - never Trail's raid-gated P.partyModeOn
    L.execute('PLIVE, PON = true, true; WowCompanion.padMap.party = { live = function() return PLIVE end, on = function() return PON end }')
    G.TG = True
    L.execute('RUN(0.5)')
    chk("C. nbar11: Party mode on -> the Wings stay off (never in both places)", not NB.top.shown and NB.top.want is False, NB.top.shown)
    # in a raid Trail's P.partyModeOn says false (the HUD's lift), the app's onLive still true: the Wings stay off as the band stays below
    L.execute('WowCompanion.prd.partyModeOn = function() return false end; RUN(0.5)')
    chk("C. review: Party mode in a RAID (Trail's raid gate false, PadMap's live true) -> still no Wings (the app keeps the band)",
        not NB.top.shown and NB.top.want is False, NB.top.shown)
    # Party mode on but suspended by an aside out of combat (live false, on true): the app takes over (the band slides away) -> Wings
    L.execute('PLIVE = false; RUN(0.5)')
    chk("C. review: Party mode on but aside out of combat (live false) -> the Wings show (the band slid away: never on neither)",
        NB.top.want is True and NB.top.shown, (NB.top.want, NB.top.shown))
    G.TG = False
    L.execute('RUN(0.4)')
    # an older PadMap without `live`: its plain on()
    L.execute('WowCompanion.padMap.party = { on = function() return true end }')
    G.TG = True
    L.execute('RUN(0.5)')
    chk("C. review: an older PadMap (no live) -> its on()", not NB.top.shown and NB.top.want is False, NB.top.shown)
    G.TG = False
    L.execute('WowCompanion.padMap.party = nil; WowCompanion.prd.partyModeOn = nil; RUN(0.4)')
    party_pin(L)
    # the router's Y not bound (tgGrown nil): an attack armed before the pull (the app's hudS.aa / ar grow) shows the Wings; it ends: gone
    L.execute('WowCompanion.tgGrown = function() return nil end')
    L.execute('RUN(0.3)')
    chk("C. review: tgGrown nil, nothing armed, out of combat -> no Wings", NB.top.want is False, NB.top.want)
    L.execute('for _, x in ipairs(EVENTS["PLAYER_ENTER_COMBAT"] or {}) do if x.onOnEvent then x.onOnEvent(x, "PLAYER_ENTER_COMBAT") end end')
    L.execute('RUN(0.5)')
    chk("C. review: tgGrown nil + auto-attack armed (PLAYER_ENTER_COMBAT) -> the Wings show as the band slides away",
        NB.top.want is True and NB.top.shown, (NB.top.want, NB.top.shown))
    L.execute('for _, x in ipairs(EVENTS["PLAYER_LEAVE_COMBAT"] or {}) do if x.onOnEvent then x.onOnEvent(x, "PLAYER_LEAVE_COMBAT") end end')
    L.execute('RUN(0.5)')
    chk("C. review: ... the attack stops -> the Wings leave", NB.top.want is False and not NB.top.shown, (NB.top.want, NB.top.shown))
    L.execute('for _, x in ipairs(EVENTS["START_AUTOREPEAT_SPELL"] or {}) do if x.onOnEvent then x.onOnEvent(x, "START_AUTOREPEAT_SPELL") end end')
    L.execute('RUN(0.5)')
    chk("C. review: tgGrown nil + Auto Shot (START_AUTOREPEAT_SPELL) -> the Wings show", NB.top.want is True, NB.top.want)
    L.execute('for _, x in ipairs(EVENTS["STOP_AUTOREPEAT_SPELL"] or {}) do if x.onOnEvent then x.onOnEvent(x, "STOP_AUTOREPEAT_SPELL") end end')
    L.execute('WowCompanion.tgGrown = function() return TG end; RUN(0.5)')
    chk("C. review: ... stopped, the router's switch back -> no Wings", NB.top.want is False and not NB.top.shown, (NB.top.want, NB.top.shown))
    # nbar11: no lane (a frame under the halves: nbar14's pushes go round the stock bar, so a thin strip across the bottom) -> the fade + rise
    L.execute('GAME["g.LowStrip"].shown = true; WowCompanion.nbar.top.gameDirty = true; RUN(0.1)')
    chk("C. nbar11: a frame under the halves -> raised over it, no lane", NB.top.spot.lane is False and NB.top.spot.case == 2, NB.top.spot.lane)
    G.TG = True
    L.execute('RUN(0.12, 0.02)')
    chk("C. nbar11 no lane: the fade + rise (no hold, the alpha follows the cubic)", NB.top.form == "fade" and NB.top.shown and 0 < f.alpha < 1,
        (NB.top.form, f.alpha))
    L.execute('RUN(0.3, 0.02)')
    chk("C. ... in: alpha 1", abs(f.alpha - 1) < 1e-6, f.alpha)
    G.TG = False
    L.execute('RUN(0.4); GAME["g.LowStrip"].shown = false; WowCompanion.nbar.top.gameDirty = true; RUN(0.1)')

    # ------------------------------------------------------------------------------------------------ D Nintendo mode: the glide
    W.nbarSet(True)
    L.execute('RUN(0.1)')
    st = (f.pts[1][4], f.pts[1][5], f.scale)
    chk("D. Nintendo mode out of a fight: the joined bar at the stock spot", NB.at == "stock" and L.eval("WowCompanion.nbar.f.pts[1][2] == UIParent")
        and st[0] == 960 and st[1] == 96,
        (NB.at, st))
    jl = L.eval("DIAMOND")(1)
    G.TG, G.COMBAT = True, True
    L.execute('EV("PLAYER_REGEN_DISABLED"); RUN(0.12, 0.02)')
    gl = L.eval("DIAMOND")(1)
    spn = todict(NB.top.spot)
    chk("D. the glide: alpha 1, the D-pad diamond between the stock spot and the left wing", abs(f.alpha - 1) < 1e-6 and NB.top.form == "glide"
        and min(jl[0], spn["l"]["x"]) < gl[0] < max(jl[0], spn["l"]["x"]), (f.alpha, jl, gl, spn["l"]))
    L.execute('RUN(0.3)')
    gl = L.eval("DIAMOND")(1)
    chk("D. ... landed on the wing", abs(gl[0] - spn["l"]["x"]) < 0.01 and abs(gl[1] - spn["l"]["y"]) < 0.01, (gl, spn["l"]))
    G.TG, G.COMBAT = False, False
    L.execute('EV("PLAYER_REGEN_ENABLED"); RUN(0.1, 0.02)')
    chk("D. back: gliding home, still alpha 1", NB.top.shown and abs(f.alpha - 1) < 1e-6, f.alpha)
    L.execute('RUN(0.3)')
    chk("D. ... the Nintendo bar at its stock spot again, the halves joined, shown", not NB.top.shown and f.shown is True and f.pts[1][4] == 960
        and f.pts[1][5] == 96 and f.btn[1].pts[1][4] == -NB.DX - NB.OFF and NB.at == "stock", (f.pts[1][4], f.pts[1][5], f.btn[1].pts[1][4]))
    chk("D. the layer word back over the middle out of the wings", f.chip[1].shown is False)
    W.nbarSet(False)

    # ------------------------------------------------------------------------------------------------ E the editor, a placed spot
    G.WowCompanion.lay.editing = True
    L.execute('RUN(0.1)')
    chk("E. the editor open (flag on, Nintendo off, out of combat): the bar shows at its piece's spot - nbar14 at the fight's size (the "
        "match: scale 1.75 here) and its flattened diamond", NB.top.edit and f.shown is True and abs(f.pts[1][4] * f.scale - 960) < 1e-6
        and abs(f.scale - 1.75) < 1e-9 and NB.ox == 47.5 and NB.oy == 25, (NB.top.edit, f.shown, f.scale, NB.ox))
    n0 = G.RECTNOW
    L.execute('for i = 1, 30 do WowCompanion.nbar.lay.apply(); RUN(0.016, 0.016) end')
    chk("E. the editor's eased view (the piece applied every frame): ours re-solved, the game's frames not read every frame",
        G.RECTNOW - n0 <= 1 and NB.top.n > 0, G.RECTNOW - n0)
    G.WowCompanion.lay.editing = False
    L.execute('RUN(0.1)')
    chk("E. the editor closed: hidden again", not NB.top.edit and f.shown is False)
    L.execute('LAYE.nbar = { dx = -500, dy = -300, s = 100 }; WowCompanion.nbar.lay.apply()')
    L.execute('RUN(0.1)')
    G.TG, G.COMBAT = True, True
    L.execute('EV("PLAYER_REGEN_DISABLED"); RUN(0.4)')
    lx, ly = L.eval("DIAMOND")(1)
    rx, ry = L.eval("DIAMOND")(2)
    # (the spot dx -500 from the stock centre 960 = 460, kept on the screen: the joined bar's half width (160 + 142.5) x 1.75 = 529.375)
    chk("E. a placed spot (Auto off) wins in the fight: the joined bar there (kept on the screen at the match size)", todict(NB.top.spot)["case"] == "placed"
        and abs((lx + rx) / 2 - 302.5 * 1.75) < 0.01 and abs(ly - 684) < 0.01 and abs(rx - lx - 2 * NB.DX * 1.75) < 0.01, ((lx, ly), (rx, ry)))
    G.TG, G.COMBAT = False, False
    L.execute('EV("PLAYER_REGEN_ENABLED"); RUN(0.4); LAYE.nbar = nil; WowCompanion.nbar.lay.apply(); RUN(0.1)')
    chk("E. Reset in the editor (no entry) = Auto again", todict(NB.top.spot)["case"] == 1, todict(NB.top.spot)["case"])
    # nbar8: a SIZE alone (the editor's size step writes dx = dy = 0) is no place: still the Wings' auto spot, at the piece's scale
    fsc0 = L.eval('WowCompanion.nbar.topInputs().fsc')
    L.execute('LAYE.nbar = { dx = 0, dy = 0, s = 130 }; WowCompanion.nbar.lay.apply(); RUN(0.1)')
    rs = todict(L.eval('WowCompanion.nbar.topSolve(true)'))
    fsc1 = L.eval('WowCompanion.nbar.topInputs().fsc')
    chk("E. nbar8: an entry with s = 130 and dx = dy = 0 still solves to the Wings (case 1 / 2), scaled x1.3", rs["case"] in (1, 2)
        and rs["form"] == "wings" and abs(fsc1 / fsc0 - 1.3) < 1e-6, (rs["case"], rs["form"], fsc0, fsc1))
    L.execute('LAYE.nbar = nil; WowCompanion.nbar.lay.apply(); RUN(0.1)')

    # ------------------------------------------------------------------------------------------------ F leave no trace
    tpls = set(str(o.tpl) for o in G.OURS.values() if o.tpl is not None)
    chk("F. our frames plain: no Secure template (only the game's plain cooldown swipe)", len(G.BADTPL) == 0 and tpls <= {"CooldownFrameTemplate"}, tpls)
    mice = [f.mouse, f.chip[1].mouse, f.chip[2].mouse, f.sw[1].mouse, f.sw[2].mouse, NB.drv.mouse] + [f.btn[p].mouse for p in range(1, 9)]
    chk("F. EnableMouse(false) on the bar, its buttons, chips, swing lines and the driver; never true", G.BADMOUSE == 0 and all(m is False for m in mice), mice)
    chk("F. no SetBinding / SetOverrideBinding / CVar / macro / action-slot call", len(G.SPY) == 0, list(G.SPY.values()))
    bliz = [x for x in G.BLIZ.values()]
    chk("F. nothing of Blizzard's moved, shown or hidden (only reads and SetAlpha)", not bliz, bliz[:5])
    chk("F. no secret compared, indexed or computed (none raised)", G.RAISED == 0, G.RAISED)
    chk("F. no game frame read in a fight across the whole run", G.RECTNOW_COMBAT == 0, G.RECTNOW_COMBAT)
    anchors = set()
    for o in G.OURS.values():
        for pt in (o.pts or {}).values():
            rel = pt[2] if len(pt) >= 2 else None
            if rel is not None and lupa.lua_type(rel) == "table" and rel._name is not None:
                anchors.add(rel._name)
    chk("F. nothing of ours anchored to a game frame", not anchors, anchors)

    # ------------------------------------------------------------------------------------------------ G nbar9: built inside a fight
    # a /reload or a login INSIDE a fight (the lockdown on from the first frame), the flag on: the frames are built at once (plain,
    # display-only frames are legal in combat) and the Wings show within IN_S, solved against our own frames (the game's never read)
    L2 = boot()
    G2 = L2.globals()
    NB2 = G2.WowCompanion.nbar
    G2.COMBAT, G2.TG, G2.CTOP = True, True, True
    L2.execute('RUN(0.3, 0.02)')
    chk("G. nbar9: lockdown from the first frame, flag on: built, a spot, shown within IN_S (0.24 s)", NB2.built and NB2.top.spot is not None
        and NB2.top.shown and abs(NB2.f.alpha - 1) < 1e-6, (NB2.built, NB2.top.spot is not None, NB2.top.shown))
    chk("G. ... no game frame read in the fight, nothing raised, still no Secure template / mouse", G2.RECTNOW_COMBAT == 0 and G2.RAISED == 0
        and len(G2.BADTPL) == 0 and G2.BADMOUSE == 0, (G2.RECTNOW_COMBAT, G2.RAISED))
    # the character key arriving mid-fight (R.ctopOn false until then): the flag turns on in the fight, the bar is built and shown
    L3 = boot()
    G3 = L3.globals()
    NB3 = G3.WowCompanion.nbar
    G3.COMBAT, G3.TG, G3.CTOP = True, True, False
    L3.execute('RUN(1.0)')
    chk("G. the key not known yet in the fight: nothing built", not NB3.built and NB3.f is None)
    G3.CTOP = True
    L3.execute('RUN(0.8, 0.02)')
    chk("G. the key arrives mid-fight (flag on): built and shown in the same fight (FLAG_S + IN_S)", NB3.built and NB3.top.shown
        and abs(NB3.f.alpha - 1) < 1e-6 and G3.RECTNOW_COMBAT == 0, (NB3.built, NB3.top.shown))

    # ------------------------------------------------------------------------------------------------ H nbar11 combat safety of the slide
    L4 = boot("ANIMGROUPS = 0; getmetatable(UIParent).CreateAnimationGroup = function() ANIMGROUPS = ANIMGROUPS + 1; return {} end")
    G4 = L4.globals()
    NB4 = G4.WowCompanion.nbar
    # the reserved cast bar: hidden, read out of combat (its rect kept), never in the fight
    L4.execute('GAME["g.PlayerCastingBarFrame"] = { x = 810, y = 820, w = 300, h = 30, shown = false }; '
               'GAMEFR = { ["g.PlayerCastingBarFrame"] = GAME["g.PlayerCastingBarFrame"] }; '
               'WowCompanion.gameLay.frame = function(id) return GAME[id] end')
    G4.CTOP = True
    L4.execute('RUN(0.6)')
    res = NB4.top.res and NB4.top.res["g.PlayerCastingBarFrame"]
    chk("H. nbar11: the reserved cast bar (hidden) read out of combat and kept", res is not None and res.y == 820, res)
    gm = [todict(x) for x in NB4.top.game.values()] if NB4.top.game else []
    chk("H. ... and counted as an obstacle (a cast bar always is)", any(x.get("id") == "g.PlayerCastingBarFrame" and x.get("reserved") for x in gm), gm)
    rn0 = G4.RECTNOW
    G4.COMBAT, G4.TG = True, True
    L4.execute('EV("PLAYER_REGEN_DISABLED"); RUN(0.6, 0.02)')
    G4.TG = False
    L4.execute('RUN(0.1, 0.02)')
    G4.COMBAT = False
    L4.execute('EV("PLAYER_REGEN_ENABLED"); RUN(0.5, 0.02)')
    chk("H. a whole fight in and out (slide in, slide out): no AnimationGroup made", G4.ANIMGROUPS == 0, G4.ANIMGROUPS)
    tpls = set(str(o.tpl) for o in G4.OURS.values() if o.tpl is not None)
    chk("H. ... no template but the plain cooldown swipe, no Secure one", len(G4.BADTPL) == 0 and tpls <= {"CooldownFrameTemplate"}, tpls)
    chk("H. ... no SetBinding / SetOverrideBinding / CVar / macro / state driver call", len(G4.SPY) == 0, list(G4.SPY.values()))
    chk("H. ... nothing of Blizzard's touched but reads (no SetPoint / Show / Hide on a game frame)", len(G4.BLIZ) == 0, list(G4.BLIZ.values())[:5])
    chk("H. ... no game or reserved rect read in the fight (the game's rects only out of combat)", G4.RECTNOW_COMBAT == 0, G4.RECTNOW_COMBAT)
    rels = set()
    for o in G4.OURS.values():
        for pt in (o.pts or {}).values():
            rel = pt[2] if len(pt) >= 2 else None
            if rel is not None and lupa.lua_type(rel) == "table":
                if rel._name is not None and rel._name != "UIParent":
                    rels.add(str(rel._name))
    chk("H. ... every SetPoint's relative frame ours or UIParent (nothing protected in our anchor family)", not rels, rels)
    chk("H. ... EnableMouse never true, nothing raised", G4.BADMOUSE == 0 and G4.RAISED == 0, (G4.BADMOUSE, G4.RAISED))

    party_fight_checks()

    # ------------------------------------------------------------------------------------------------ the renders
    scenes_pin()
    hold_pin()
    if "--shots" in sys.argv or os.environ.get("COMBAT_BAR_SHOTS"):
        render(out_dir)
    print("\n%d passed, %d failed" % (passes, len(fails)))
    print("failed: %d" % len(fails))
    sys.exit(1 if fails else 0)


# ---------------------------------------------------------------------------------------------------------------- the renders
SCENES = [
    ("p3_top_01_default_wings", "In fights: Top screen - Wings beside the HUD (case 1, final_07)", {}),
    # nbar12 (the user's ruling C, 11-22: "It should stay at the top in party mode"): Party mode LIVE in a fight - the HUD lifted for the
    # rows, the Wings beside the whole block (Hub's rows and tab in it) carrying the PARTY layer, the band slid up off the bottom screen
    ("p3_top_02_party_wings", "Party mode in a fight: the HUD lifted for the rows, the Wings carry the Party layer (ruling C)",
     {"party": True}),
    ("p3_top_03_stockbar_raised", "Controller UI On (the stock bar shown): raised in 24 px steps (case 2)", {"stock": True}),
    ("p3_top_04_blocked_joined", "Game chat and tracker on both sides: joined above the HUD (case 3)", {"walls": True}),
    ("p3_top_05_placed", "Placed in the top layout editor (Auto off): the joined bar there", {"placed": True}),
    ("p3_top_06_chat_dip", "The top chat strip up in the fight: the bar dips to 35 % (it never moves for it)", {"chat": True}),
    ("p3_top_07_glide_mid", "Nintendo mode: half way through the glide from the stock spot to the wings", {"glide": True}),
    ("p3_top_08_fade_in_mid", "nbar11 the slide in: 0.19 s of 0.06 + 0.26 s (up from below the edge)", {"fadein": True}),
    ]


# nbar12: PadMap's party export as the Wings read it in Party mode's fight - the cfg it SENT last (cfg(), the spellbook walk, raises in a
# fight and is counted), the entries, the pick, the range, the moves (three full rows: you, party1, party2)
PARTY_MOCK = r'''
PLIVE, PBOX, PRNG, CFGCALLS = false, 1, nil, 0
PUNITS = { "player", "party1", "party2" }
DEAD = {}
function UnitIsDeadOrGhost(u) return DEAD[u] == true end
PCFG = { slots = { a = { id = 331, n = "Healing Wave", i = 136052, k = true }, x = { id = 324, n = "Lightning Shield", i = 136051, k = false },
                   zla = { id = 8017, n = "Rockbiter Weapon", i = 136086, k = true }, zlx = { id = 403, n = "Lightning Bolt", i = 136048, k = true },
                   zra = { id = 2008, n = "Ancestral Spirit", i = 136077, k = true, res = true } },
         sy = { on = true, pick = { party1 = { id = 8017, n = "Rockbiter Weapon", i = 136086 } } } }
WowCompanion.padMap.party = {
    live = function() return PLIVE end, on = function() return PLIVE end,
    cfg = function() CFGCALLS = CFGCALLS + 1; if InCombatLockdown() then error("cfg() in a fight: the spellbook walk") end; return PCFG end,
    cfgLast = function() return PCFG end, units = function() return PUNITS end, box = function() return { box = PBOX } end,
    rng = function(u) return PRNG end,
    nav = function(n, box, dir, pet)
        if dir == "up" and box > 1 then return box - 1 end
        if dir == "down" and box < n then return box + 1 end
        return box
    end }
'''


def party_fight_checks():
    """I nbar12 PARTY MODE'S FIGHT (the user's ruling C, 11-22): the Wings shown with the Party layer, raids too, cfg() never called in
    the fight, a Select press mid-fight swapping the layer in place, Hub's rows and tab in the HUD block"""
    L = boot("ROT = {}; getmetatable(UIParent).SetRotation = function(self, r) self.rotv = r end")
    G = L.globals()
    NB = G.WowCompanion.nbar
    L.execute(PARTY_MOCK)
    G.CTOP = True
    L.execute('RUN(0.6)')
    # Party mode live, an attack armed before the pull (out of combat): no Wings - Party mode has no takeover, the band stays below
    G.PLIVE = True
    G.TG = True
    L.execute('RUN(0.5)')
    chk("I. nbar12: Party mode live + an attack armed out of combat -> no Wings (no takeover: the app keeps the band)",
        NB.top.want is False and not NB.top.shown, (NB.top.want, NB.top.shown))
    # the fight: the Wings with the Party layer
    G.COMBAT = True
    L.execute('EV("PLAYER_REGEN_DISABLED"); RUN(0.5)')
    f = NB.f
    chk("I. nbar12: Party mode's fight -> the Wings show (ruling C: \"It should stay at the top in party mode\")", NB.top.want is True
        and NB.top.shown, (NB.top.want, NB.top.shown))
    chk("I. ... carrying the Party layer (NB.party)", NB.party is not None and NB.party.box == 1 and NB.party.n == 3, NB.party)
    b = f.btn
    chk("I. ... the D-pad = the pick's moves: the game's arrows (left back, up / right / down the forward one turned), no badge",
        b[1].icon.atlas == "common-icon-backarrow" and b[2].icon.atlas == "common-icon-forwardarrow" and b[3].icon.atlas == "common-icon-forwardarrow"
        and b[4].icon.atlas == "common-icon-forwardarrow" and abs((b[2].icon.rotv or 0) - math.pi / 2) < 1e-9 and abs((b[4].icon.rotv or 0) + math.pi / 2) < 1e-9
        and (b[3].icon.rotv or 0) == 0 and all(b[p].glyph.shown is False for p in range(1, 5)),
        [(b[p].icon.atlas, b[p].icon.rotv, b[p].glyph.shown) for p in range(1, 5)])
    chk("I. ... the pick on you (1 of 3): only Down lit, the rest at 0.28", [b[p].ia for p in range(1, 5)] == [0.28, 0.28, 0.28, 1],
        [b[p].ia for p in range(1, 5)])
    sh = NB.shown
    chk("I. ... A = slot a (Healing Wave), X = slot x not learned: dim (grey)", sh[8].id == 331 and sh[8].icon == 136052 and b[8].look == "ok"
        and sh[5].id == 324 and b[5].look == "unusable", (sh[8].id, b[8].look, sh[5].id, b[5].look))
    chk("I. ... Y with no In combat spell and no Smart Y pick for you: Target (the friendly reticle)", b[6].icon.atlas == "gamepad-targeting-friendly",
        b[6].icon.atlas)
    chk("I. ... B = the game's own Back (its slot)", sh[7].kind in ("icon", "slot", "empty") and b[7].icon.atlas != "common-icon-forwardarrow", sh[7].kind)
    back = (sh[7].kind, sh[7].icon, sh[7].s)
    chk("I. ... the chord rows: Party mode's trigger spells under the chips (ZL: zla / zlx; ZR: zra; an Empty place not drawn)",
        f.chord[1][1].shown and f.chord[1][1].tex == 136086 and f.chord[1][2].shown and not f.chord[1][3].shown and f.chord[2][1].shown
        and not f.chord[2][2].shown, [(c.shown, c.tex) for c in list(f.chord[1].values()) + list(f.chord[2].values())])
    chk("I. ... the L / R plates shown, the targeting pictures as the band's", f.plate[1].shown and f.plate[2].shown
        and f.plate[1].kind == "friendly" and f.plate[2].kind == "hostile", (f.plate[1].kind, f.plate[2].kind))
    # the pick moves to party1: Y = Smart Y's pick for it; Up lit
    G.PBOX = 2
    L.execute('RUN(0.3)')
    chk("I. the pick on party1: Smart Y's pick for it on Y (Rockbiter), Up and Down lit", NB.shown[6].id == 8017 and [b[p].ia for p in range(1, 5)] == [0.28, 1, 0.28, 1],
        (NB.shown[6].id, [b[p].ia for p in range(1, 5)]))
    # ZR held: A = zra (a res) dim on a living member; dead: the res lit, the others dim
    G.RT = True
    L.execute('RUN(0.1)')
    chk("I. ZR held: A = zra (Ancestral Spirit, a res): dim on a living member", NB.set == 3 and NB.shown[8].id == 2008 and b[8].look == "unusable",
        (NB.set, NB.shown[8].id, b[8].look))
    chk("I. ... B still the top bar's own Back on the ZR layer (the party pad keeps it on every layer)",
        (NB.shown[7].kind, NB.shown[7].icon, NB.shown[7].s) == back, ((NB.shown[7].kind, NB.shown[7].icon, NB.shown[7].s), back))
    L.execute('DEAD.party1 = true; RUN(0.3)')
    chk("I. ... the picked member dead: the res lit", b[8].look == "ok", b[8].look)
    G.RT = False
    L.execute('RUN(0.3)')
    chk("I. ... back on the base layer, dead: Healing Wave (not a res) dim", NB.shown[8].id == 331 and b[8].look == "unusable", b[8].look)
    L.execute('DEAD.party1 = nil')
    # both triggers: the faces empty, B still Back
    G.LT, G.RT = True, True
    L.execute('RUN(0.1)')
    chk("I. both triggers: the faces empty (nothing), B the game's Back", NB.set == 4 and NB.shown[8].kind == "empty" and NB.shown[5].kind == "empty"
        and NB.shown[6].kind == "empty", [NB.shown[p].kind for p in range(5, 9)])
    G.LT, G.RT = False, False
    # the review (11-22): a form / vehicle bar over the base layer (a Druid's Bear form mid-fight) - B is still the top bar's own Back
    # (the party pad's pos 7 = Party mode off), never that bar's 7th action; Y before a cfg the top bar's own Inspect, never its 6th
    L.execute('OVERRIDE.stanceBar = "topBar"; RUN(0.3)')
    chk("I. a form's bar over the base layer: B still the top bar's own Back (not the form bar's 7th action)",
        (NB.shown[7].kind, NB.shown[7].icon, NB.shown[7].s) == back and b[7].icon.tex == 136245,
        ((NB.shown[7].kind, NB.shown[7].icon, NB.shown[7].s), back))
    L.execute('PCFG_KEEP = PCFG; PCFG = nil; RUN(0.3)')
    chk("I. ... no cfg sent yet: Y the top bar's own Inspect (its crosshair), not the form bar's 6th action",
        NB.shown[6].kind == "icon" and str(NB.shown[6].icon).startswith("atlas:crosshair_"), (NB.shown[6].kind, NB.shown[6].icon, NB.shown[6].s))
    L.execute('PCFG = PCFG_KEEP; OVERRIDE.stanceBar = nil; RUN(0.3)')
    # range: the picked member out of range -> the glyph red (range only), never the target's
    G.PRNG = False
    L.execute('RUN(0.3)')
    chk("I. the picked member out of range (PadMap's rng false): A's glyph red", b[8].range is False, b[8].range)
    G.PRNG = None
    L.execute('RUN(0.3)')
    chk("I. ... in range again (nil = unknown): not red", b[8].range is True, b[8].range)
    chk("I. cfg() (the spellbook walk) never called in the fight: the cfg PadMap sent last", G.CFGCALLS == 0, G.CFGCALLS)
    # a raid: Trail's raid-gated partyModeOn false, PadMap's live true -> still the Party layer (one predicate with the app)
    L.execute('WowCompanion.prd.partyModeOn = function() return false end; RUN(0.3)')
    chk("I. a raid (Trail's gate false, PadMap's live true): the Wings with the Party layer still", NB.top.shown and NB.party is not None)
    # Select mid-fight: Party mode off -> re-fit, the layer swapped in place (still shown: the fight keeps the Wings)
    n0 = NB.top.n
    G.PLIVE = False
    L.execute('DRV(0.02)')
    chk("I. Select mid-fight (Party mode off): marked for a re-fit and a redraw that tick", NB.top.n > n0 or NB.top.dirty, (n0, NB.top.n))
    L.execute('RUN(0.3)')
    chk("I. ... the Wings still shown, the bar's own layer back (no arrows, no turn, the badges back)", NB.top.shown and NB.party is None
        and b[1].icon.atlas != "common-icon-backarrow" and (b[2].icon.rotv or 0) == 0 and b[2].ia == 1 and b[1].glyph.shown is True
        and not f.chord[1][1].shown, (NB.party, b[1].icon.atlas, b[2].icon.rotv, b[1].glyph.shown))
    # the review (11-22): Select on mid-fight - PadMap flips first, Hub's rows move later (its 0.1 s step: the room; the fade-out's end:
    # PF.at "hud", the rows laid under the HUD at alpha 0, then faded in). The re-fit follows each of Hub's moves on the tick it shows,
    # not the next REFIT_S
    L.execute('SOLVES = 0; local o = WowCompanion.nbar.topSolve; WowCompanion.nbar.topSolve = function(...) SOLVES = SOLVES + 1; return o(...) end')
    G.PLIVE = True
    L.execute('DRV(0.02); RUN(0.08)')
    c0 = G.SOLVES
    L.execute('WowCompanion.uf.pf.want = "hud"; WowCompanion.uf.pf.room = 150; WowCompanion.uf.pf.fdir = -1; WowCompanion.prd.partyRoomPx = 150; DRV(0.02)')
    chk("I. Select on mid-fight: Hub's room step (PF.room, Trail's partyRoomPx) re-fits that tick", G.SOLVES > c0, (c0, G.SOLVES))
    L.execute('RUN(0.1)')
    c1 = G.SOLVES
    L.execute('WowCompanion.uf.pf.at = "hud"; WowCompanion.uf.pf.fdir = 1; for i = 1, 4 do local c = (i - 1) % 2; local r = math.floor((i - 1) / 2); '
              'WowCompanion.uf.pf.frames[i] = MOCKF(740 + c * 330, 870 + r * 76, 300, 64); WowCompanion.uf.pf.frames[i].alpha = 0 end; DRV(0.02)')
    sp = todict(NB.top.spot)
    dxc = L.eval("WowCompanion.nbar.centreDx")()                   # nbar14: where Trail / Hub are taking the block (no Trail here: 0 applied)
    rows4 = [(740 + (i % 2) * 330 + dxc, 870 + (i // 2) * 76, 300, 64) for i in range(4)]
    hits = [(h, rw) for h in sp["rects"].values() for rw in rows4
            if h["x"] < rw[0] + rw[2] and rw[0] < h["x"] + h["w"] and h["y"] < rw[1] + rw[3] and rw[1] < h["y"] + h["h"]]
    chk("I. ... the fade-out's end (PF.at \"hud\", the rows laid there at alpha 0): re-fit that tick, the spot off every row (nbar14: "
        "where the centring takes them, %+d px)" % dxc,
        G.SOLVES > c1 and not hits, (c1, G.SOLVES, hits))
    L.execute('WowCompanion.uf.pf.fdir = nil; RUN(0.3)')
    G.PLIVE = True
    L.execute('RUN(0.3)')
    chk("I. ... and on again mid-fight: the Party layer back at once", NB.top.shown and NB.party is not None and b[1].icon.atlas == "common-icon-backarrow")
    # Hub's rows and its Select tab join the HUD block in Party mode (read at any alpha; the rows with their rim)
    L.execute('WowCompanion.uf.pf.at = "hud"; for i = 1, 4 do local c = (i - 1) % 2; local r = math.floor((i - 1) / 2); '
              'WowCompanion.uf.pf.frames[i] = MOCKF(740 + c * 330, 870 + r * 76, 300, 64) end; '
              'WowCompanion.uf.pf.tab = MOCKF(700, 860, 22, 140); WowCompanion.uf.pf.tab.alpha = 0')
    ours, hud = L.eval("WowCompanion.nbar.topOurs")()
    hd = todict(hud)
    rim = NB.TOP.HUB_RIM
    dxc = L.eval("WowCompanion.nbar.centreDx")()
    chk("I. Hub's rows (with their %d px rim) and its Select tab (alpha 0 too) in the HUD block (nbar14: at the centred place, %+d px)"
        % (rim, dxc), hd["x"] <= 700 + dxc + 1e-6 and hd["x"] + hd["w"] >= 740 + 330 + 300 + rim + dxc - 1e-6
        and hd["y"] + hd["h"] >= 870 + 76 + 64 + rim - 1e-6, hd)
    # nbar14 PARTY MODE CENTRED (the user, 11-23): the block (the HUD bars 820-1100 + its aura host, Hub's rows 740-1370 with the rim, the
    # tab at 700) told to stand centred: the shift is 960 - its centre, the solver's HUD block is centred, the Wings either side of it
    x0 = min(700, 740 - rim, 820)
    x1 = max(740 + 330 + 300 + rim, 1100)
    want = math.floor(960 - (x0 + x1) / 2 + 0.5)
    chk("I. nbar14 Party mode's Top fight: NB.top.centre on, the shift told to Trail (NB.centreDx = 960 - the block's centre = %+d)" % want,
        NB.top.centre is True and dxc == want and abs(hd["x"] + hd["w"] / 2 - 960) <= 0.5, (NB.top.centre, dxc, want, hd))
    spc = todict(L.eval("WowCompanion.nbar.topSolve")(False))
    chk("I. nbar14 ... the Wings either side of the centred block, 60 px clear (case %s)" % spc["case"], spc["form"] == "wings"
        and spc["rects"][1]["x"] + spc["rects"][1]["w"] <= hd["x"] - 60 + 1e-6 and spc["rects"][2]["x"] >= hd["x"] + hd["w"] + 60 - 1e-6, spc)
    # Trail applies it (prd22: P.cx, our frames moved with Hub's rows): the block read there, the shift stays the same (no feedback)
    L.execute('local d = %d; WowCompanion.prd.cx = d; local function mv(f) f.r.l = f.r.l + d end; mv(WowCompanion.prd.f); '
              'mv(WowCompanion.prd.box.host); for i = 1, 4 do mv(WowCompanion.uf.pf.frames[i]) end; mv(WowCompanion.uf.pf.tab)' % want)
    ours2, hud2 = L.eval("WowCompanion.nbar.topOurs")()
    hd2 = todict(hud2)
    chk("I. nbar14 ... once Trail has applied it: the same centred block, the same shift (stable)", L.eval("WowCompanion.nbar.centreDx")() == want
        and abs(hd2["x"] - hd["x"]) < 1e-6 and abs(hd2["w"] - hd["w"]) < 1e-6, (hd2, hd))
    # the review (11-23): mid-fade the two halves of the block stand at DIFFERENT shifts - Trail's bars at the one it applied (P.cx),
    # Hub's rows at the one they were last drawn with (PF.g = P.partyGeo's table, its cx). One shift taken off the union moved the centre
    # by half a shift and asked for another (the HUD fading again and again); each part less its own: the same shift at every stage
    L.execute('local d = %d; local function mv(f) f.r.l = f.r.l - d end; for i = 1, 4 do mv(WowCompanion.uf.pf.frames[i]) end; '
              'mv(WowCompanion.uf.pf.tab); WowCompanion.uf.pf.g = { x = 0, y = 0, cx = 0 }' % want)
    ours3, hud3 = L.eval("WowCompanion.nbar.topOurs")()
    hd3 = todict(hud3)
    chk("I. review: Trail applied, Hub's rows not moved yet (PF.g cx 0): the same shift, the same centred block",
        L.eval("WowCompanion.nbar.centreDx")() == want and abs(hd3["x"] - hd["x"]) < 1e-6 and abs(hd3["w"] - hd["w"]) < 1e-6, (hd3, hd))
    L.execute('local d = %d; local function mv(f) f.r.l = f.r.l + d end; local function back(f) f.r.l = f.r.l - d end; '
              'WowCompanion.prd.cx = 0; back(WowCompanion.prd.f); back(WowCompanion.prd.box.host); '
              'for i = 1, 4 do mv(WowCompanion.uf.pf.frames[i]) end; mv(WowCompanion.uf.pf.tab); '
              'WowCompanion.uf.pf.g = { x = 0, y = 0, cx = d }' % want)
    ours4, hud4 = L.eval("WowCompanion.nbar.topOurs")()
    hd4 = todict(hud4)
    chk("I. review: Hub's rows moved first (PF.g cx %+d), Trail not yet (P.cx 0): the same shift, the same centred block" % want,
        L.eval("WowCompanion.nbar.centreDx")() == want and abs(hd4["x"] - hd["x"]) < 1e-6 and abs(hd4["w"] - hd["w"]) < 1e-6, (hd4, hd))
    # (back to the state above: Trail applied, everything moved, no PF.g)
    L.execute('local d = %d; local function mv(f) f.r.l = f.r.l + d end; WowCompanion.prd.cx = d; mv(WowCompanion.prd.f); '
              'mv(WowCompanion.prd.box.host); WowCompanion.uf.pf.g = nil' % want)
    L.execute('local d = %d; WowCompanion.prd.cx = 0; local function mv(f) f.r.l = f.r.l - d end; mv(WowCompanion.prd.f); '
              'mv(WowCompanion.prd.box.host); for i = 1, 4 do mv(WowCompanion.uf.pf.frames[i]) end; mv(WowCompanion.uf.pf.tab)' % want)
    # the review (11-22): our HUD bars switched off, our party frames on - Hub still lays the rows under the HUD's place (PF.want = Party
    # mode and partyOk, PF.geo from P.partyGeo). nbar15: no Trail P.partyGeo to carry a shift (this stub's prd has none) -> the rows stay
    # an obstacle of their own where they stand ("partyhud", with the rim and the tab), no HUD block, nothing centred
    G.HUD_ON = False
    ours, hud = L.eval("WowCompanion.nbar.topOurs")()
    od = [todict(o) for o in ours.values()]
    ph = [o for o in od if o.get("id") == "partyhud"]
    chk("I. HUD bars off, no Trail P.partyGeo: no HUD block, the rows (rim) and the tab an obstacle of their own where they stand",
        hud is None and len(ph) == 1 and ph[0]["x"] <= 700 + 1e-6 and ph[0]["x"] + ph[0]["w"] >= 740 + 330 + 300 + rim - 1e-6
        and ph[0]["y"] <= 860 + 1e-6 and ph[0]["y"] + ph[0]["h"] >= 870 + 76 + 64 + rim - 1e-6, (hud and todict(hud), od))
    sp = todict(L.eval("WowCompanion.nbar.topSolve")(False))
    rows = [(740 + (i % 2) * 330, 870 + (i // 2) * 76, 300, 64) for i in range(4)] + [(700, 860, 22, 140)]
    hits = [(h, rw) for h in sp["rects"].values() for rw in rows
            if h["x"] < rw[0] + rw[2] and rw[0] < h["x"] + h["w"] and h["y"] < rw[1] + rw[3] and rw[1] < h["y"] + h["h"]]
    chk("I. ... the fight's solve keeps the Wings off every row and the tab (case %s)" % sp["case"], not hits, hits)
    # nbar15 THE ROWS CENTRED WITH OUR HUD BARS OFF (the user's pick 11-23, "Centre the rows": "The party rows move to the centre and
    # the controller goes either side"): Trail there (P.partyGeo), Party mode's Top fight (NB.top.centre) - the rows + tab block alone
    # (700 .. 1370 + rim) told to stand centred, the solver's HUD block = that block, the Wings either side of it, HUD_MARGIN clear
    L.execute('WowCompanion.prd.partyGeo = function() return { x = 0, y = 0, cx = WowCompanion.nbar.centreDx() } end')
    ours, hud = L.eval("WowCompanion.nbar.topOurs")()
    hd5 = todict(hud)
    r0, r1 = 700, 740 + 330 + 300 + rim
    want5 = math.floor(960 - (r0 + r1) / 2 + 0.5)
    dx5 = L.eval("WowCompanion.nbar.centreDx")()
    od = [todict(o) for o in ours.values()]
    ph = [o for o in od if o.get("id") == "partyhud"]
    chk("I. nbar15 HUD bars off, Party mode's Top fight: the rows + tab block alone centred (NB.centreDx %+d = 960 - its centre %+d), "
        "it IS the solver's HUD block (the obstacle \"partyhud\" at the same place)" % (dx5, want5),
        NB.top.centre is True and hd5 is not None and dx5 == want5 and abs(hd5["x"] + hd5["w"] / 2 - 960) <= 0.5
        and abs(hd5["x"] - (r0 + want5)) < 1e-6 and abs(hd5["w"] - (r1 - r0)) < 1e-6 and abs(hd5["y"] - (870 - rim)) < 1e-6
        and len(ph) == 1 and abs(ph[0]["x"] - hd5["x"]) < 1e-6, (NB.top.centre, dx5, want5, hd5, ph))
    sp5 = todict(L.eval("WowCompanion.nbar.topSolve")(False))
    chk("I. nbar15 ... the Wings either side of the centred rows, %d px clear, at k 1 (case %s)" % (NB.TOP.HUD_MARGIN, sp5["case"]),
        sp5["form"] == "wings" and (sp5.get("k") or 1) == 1
        and sp5["rects"][1]["x"] + sp5["rects"][1]["w"] <= hd5["x"] - NB.TOP.HUD_MARGIN + 1e-6
        and sp5["rects"][2]["x"] >= hd5["x"] + hd5["w"] + NB.TOP.HUD_MARGIN - 1e-6, sp5)
    rows5 = [(740 + (i % 2) * 330 + want5, 870 + (i // 2) * 76, 300, 64) for i in range(4)] + [(700 + want5, 860, 22, 140)]
    hits = [(h, rw) for h in sp5["rects"].values() for rw in rows5
            if h["x"] < rw[0] + rw[2] and rw[0] < h["x"] + h["w"] and h["y"] < rw[1] + rw[3] and rw[1] < h["y"] + h["h"]]
    chk("I. nbar15 ... off every row and the tab where Hub takes them", not hits, hits)
    # Hub draws them there (PF.g = P.partyGeo's table with the cx it was drawn with; the frames moved): the same block, the same shift -
    # and Trail's own P.cx (none of its frames show) plays no part
    L.execute('local d = %d; local function mv(f) f.r.l = f.r.l + d end; for i = 1, 4 do mv(WowCompanion.uf.pf.frames[i]) end; '
              'mv(WowCompanion.uf.pf.tab); WowCompanion.uf.pf.g = { x = 0, y = 0, cx = d }; WowCompanion.prd.cx = -37' % want5)
    ours, hud = L.eval("WowCompanion.nbar.topOurs")()
    hd6 = todict(hud)
    chk("I. nbar15 ... once Hub has drawn the rows there (PF.g cx %+d; a stale P.cx -37 ignored): the same centred block, the same shift"
        % want5, L.eval("WowCompanion.nbar.centreDx")() == want5 and abs(hd6["x"] - hd5["x"]) < 1e-6 and abs(hd6["w"] - hd5["w"]) < 1e-6,
        (hd6, hd5))
    sp6 = todict(L.eval("WowCompanion.nbar.topSolve")(False))
    chk("I. nbar15 ... the same spot (no re-fit loop)", abs(sp6["l"]["x"] - sp5["l"]["x"]) < 1e-6 and abs(sp6["r"]["x"] - sp5["r"]["x"]) < 1e-6,
        (sp6["l"], sp5["l"]))
    # mid-fade the other way: PF.g says the new shift, the frames still where they were (Hub moves them at its fade's bottom) - each
    # stage less its own drawn shift: still the same block
    L.execute('local d = %d; local function mv(f) f.r.l = f.r.l - d end; for i = 1, 4 do mv(WowCompanion.uf.pf.frames[i]) end; '
              'mv(WowCompanion.uf.pf.tab); WowCompanion.uf.pf.g = { x = 0, y = 0, cx = 0 }' % want5)
    ours, hud = L.eval("WowCompanion.nbar.topOurs")()
    hd7 = todict(hud)
    chk("I. nbar15 ... back where they stood (PF.g cx 0): the same block, the same shift",
        L.eval("WowCompanion.nbar.centreDx")() == want5 and abs(hd7["x"] - hd5["x"]) < 1e-6, (hd7, hd5))
    # the review (11-23, NB.centreKeep): the block read back a hair off where it was (Hub's frames on the pixel grid, float sums) - the
    # shift already told is kept while the block's centre is within 1 px of it (a half-pixel centre rounding to two shifts in turn
    # would fade the rows out and in again, a loop); past 1 px the new shift is told
    raw5 = 960 - (r0 + r1) / 2
    n8 = raw5 - want5 + 0.9                                     # the centre now leaves want5 - 0.9 (rounds to want5 - 1)
    L.execute('local d = %.6f; local function mv(f) f.r.l = f.r.l + d end; for i = 1, 4 do mv(WowCompanion.uf.pf.frames[i]) end; '
              'mv(WowCompanion.uf.pf.tab)' % n8)
    L.eval("WowCompanion.nbar.topOurs")()
    dx8 = L.eval("WowCompanion.nbar.centreDx")()
    chk("I. review NB.centreKeep: the rows read back 0.9 px off their centre: the shift told kept (%+d, not %+d)" % (want5, want5 - 1),
        dx8 == want5, (dx8, want5, raw5))
    L.execute('local d = 0.3; local function mv(f) f.r.l = f.r.l + d end; for i = 1, 4 do mv(WowCompanion.uf.pf.frames[i]) end; '
              'mv(WowCompanion.uf.pf.tab)')
    L.eval("WowCompanion.nbar.topOurs")()
    dx9 = L.eval("WowCompanion.nbar.centreDx")()
    chk("I. review NB.centreKeep: ... 1.2 px off: the new shift told (%+d)" % (want5 - 1), dx9 == want5 - 1, (dx9, want5))
    L.execute('local d = %.6f; local function mv(f) f.r.l = f.r.l - d end; for i = 1, 4 do mv(WowCompanion.uf.pf.frames[i]) end; '
              'mv(WowCompanion.uf.pf.tab)' % (n8 + 0.3))
    L.eval("WowCompanion.nbar.topOurs")()
    chk("I. review NB.centreKeep: ... back where they stood: %+d again" % want5, L.eval("WowCompanion.nbar.centreDx")() == want5,
        L.eval("WowCompanion.nbar.centreDx")())
    L.execute('WowCompanion.uf.pf.g = nil; WowCompanion.prd.cx = 0')
    G.HUD_ON = True
    # the fight's end: gone
    G.COMBAT, G.TG = False, False
    L.execute('EV("PLAYER_REGEN_ENABLED"); RUN(0.5)')
    chk("I. the fight's end in Party mode: the Wings leave, the Party layer gone, the plates hidden", not NB.top.shown and NB.party is None
        and not f.plate[1].shown and not f.chord[1][1].shown, (NB.top.shown, NB.party))
    chk("I. nbar14 ... and the centring off: NB.centreDx 0 (Trail fades the HUD block back)", NB.top.centre is False
        and L.eval("WowCompanion.nbar.centreDx")() == 0, (NB.top.centre, L.eval("WowCompanion.nbar.centreDx")()))
    G.HUD_ON = False
    ours, hud = L.eval("WowCompanion.nbar.topOurs")()
    od = [todict(o) for o in ours.values()]
    ph = [o for o in od if o.get("id") == "partyhud"]
    chk("I. nbar15 ... with our HUD bars off after the fight: no shift (NB.centreDx 0), the rows an obstacle where they stand, no block",
        L.eval("WowCompanion.nbar.centreDx")() == 0 and hud is None and len(ph) == 1 and abs(ph[0]["x"] - 700) < 1e-6,
        (hud and todict(hud), ph))
    G.HUD_ON = True
    chk("I. ... nothing raised, no binding / CVar / macro call, no mouse", G.RAISED == 0 and len(G.SPY) == 0 and G.BADMOUSE == 0,
        (G.RAISED, list(G.SPY.values()), G.BADMOUSE))


def hold_pin():
    """the 11-22 check sheet (fight end + 48 ms: the band and the Wings both showed): the app's down-slide HOLDS until the Wings are mostly
    off the top screen. TopSlide.kt's copy of the Wings' way down must be the add-on's (NB.TOP.OUT_S on NB.easeIn), and its hold must
    cover them [WINGS_MOSTLY_OFF] off even if the app heard the fight's end the very frame they started down"""
    kt = open(os.path.join(KT, "TopSlide.kt"), encoding="utf-8").read()
    ab = open(AB, encoding="utf-8").read()
    out_s = re.search(r"NB\.TOP = \{[^}]*?OUT_S = ([0-9.]+)", ab)
    ms = re.search(r"const val WINGS_OUT_MS = (\d+)L", kt)
    chk("C. hold pin: TopSlide.WINGS_OUT_MS is the add-on's NB.TOP.OUT_S",
        out_s is not None and ms is not None and abs(float(out_s.group(1)) * 1000 - int(ms.group(1))) < 0.5,
        (out_s and out_s.group(1), ms and ms.group(1)))
    chk("C. hold pin: TopSlide.WINGS_OUT is NB.easeIn's (0.4, 0, 1, 1)",
        "val WINGS_OUT = floatArrayOf(0.4f, 0f, 1f, 1f)" in kt and "function NB.easeIn(u) return NB.cubic(u, 0.4, 0, 1, 1) end" in ab)
    # the 11-22 review: a reversal of the way up holds by how far in the Wings had got - TopSlide's copy of their way up is the add-on's
    hold_in = re.search(r"NB\.TOP = \{[^}]*?HOLD_IN = ([0-9.]+)", ab)
    in_s = re.search(r"NB\.TOP = \{[^}]*?IN_S = ([0-9.]+)", ab)
    k_hold = re.search(r"const val WINGS_HOLD_IN_MS = (\d+)L", kt)
    k_in = re.search(r"const val WINGS_IN_MS = (\d+)L", kt)
    chk("C. hold pin: TopSlide.WINGS_HOLD_IN_MS / WINGS_IN_MS are the add-on's NB.TOP.HOLD_IN / IN_S",
        None not in (hold_in, in_s, k_hold, k_in) and abs(float(hold_in.group(1)) * 1000 - int(k_hold.group(1))) < 0.5
        and abs(float(in_s.group(1)) * 1000 - int(k_in.group(1))) < 0.5,
        (hold_in and hold_in.group(1), in_s and in_s.group(1), k_hold and k_hold.group(1), k_in and k_in.group(1)))
    chk("C. hold pin: TopSlide.WINGS_IN is nbar14's NB.TOP.IN_C (1/3, 0.5, 2/3, 1: the way up's curve, NB.slideIn)",
        "val WINGS_IN = floatArrayOf(1f / 3f, 0.5f, 2f / 3f, 1f)" in kt and "IN_C = { 1 / 3, 0.5, 2 / 3, 1 }" in ab
        and "function NB.slideIn(u) local c = NB.TOP.IN_C; return NB.cubic(u, c[1], c[2], c[3], c[4]) end" in ab
        and "return NB.slideIn(t)" in ab)
    chk("C. hold pin: nbar14's B hold - TopSlide.B_HOLD_MS = WINGS_OUT_MS - START_FRAMES_MS - OVERLAP_MS (no channel: the add-on's B "
        "hands the Wings back at the same press)", "const val B_HOLD_MS = WINGS_OUT_MS - START_FRAMES_MS - OVERLAP_MS" in kt)
    # nbar12 / ruling D THE HAND-BACK: the hold is the Wings' whole way down less the fastest channel, the animator's start and one frame
    # of overlap - so on the fastest channel the band's first moving frame comes in the Wings' last frame (they are mostly off by the
    # add-on's own NB.easeIn), on the slowest 2 frames or less after they are gone
    cm = int(re.search(r"const val CHANNEL_MIN_MS = (\d+)L", kt).group(1))
    sf = int(re.search(r"const val START_FRAMES_MS = (\d+)L", kt).group(1))
    ov = int(re.search(r"const val OVERLAP_MS = (\d+)L", kt).group(1))
    chk("C. hold pin: HAND_BACK_MS = WINGS_OUT_MS - CHANNEL_MIN_MS - START_FRAMES_MS - OVERLAP_MS in TopSlide",
        "const val HAND_BACK_MS = WINGS_OUT_MS - CHANNEL_MIN_MS - START_FRAMES_MS - OVERLAP_MS" in kt)
    L = boot()
    ease_in = L.eval("WowCompanion.nbar.easeIn")
    out_ms = int(ms.group(1))
    hand = out_ms - cm - sf - ov
    fast = cm + hand + sf                                           # the band's first moving frame on the fastest channel
    slow = 90 + hand + sf
    chk("C. hold pin: the band's first frame on the fastest channel (%d ms) comes as the Wings leave (%.0f %% off by NB.easeIn)"
        % (fast, ease_in(fast / out_ms) * 100), 0 <= out_ms - fast <= 17 and ease_in(fast / out_ms) >= 0.8, (fast, out_ms))
    chk("C. hold pin: ... on the slowest (90 ms) at most 2 frames after they are gone (%d ms)" % (slow - out_ms), 0 <= slow - out_ms <= 34,
        (slow, out_ms))


def scene_run(o):
    """one render scene's top screen through the fight's start: a fresh add-on, the scene's mocks, the fight (the renders and the checks
    share it, so a render never draws what the rules say cannot show)"""
    L = boot("MFSCALE = 1.3")
    G = L.globals()
    G.CTOP = True
    if o.get("party"):
        L.execute('SETR(WowCompanion.prd.f, 820, 790, 280, 32); SETR(WowCompanion.prd.box.host, 820, 762, 280, 26); WowCompanion.uf.pf.at = "hud"; '
                  'for i = 1, 4 do local c = (i - 1) % 2; local r = math.floor((i - 1) / 2); '
                  'WowCompanion.uf.pf.frames[i] = MOCKF(740 + c * 330, 864 + r * 76, 300, 64) end')
        # Party mode LIVE as PadMap exports it (the Wings' switch reads it: NB.partyOn), with the cfg it sent last (the Party layer)
        L.execute(PARTY_MOCK)
        L.execute('PLIVE = true')
    if o.get("stock"):
        L.execute('GAME["g.GamepadMainActionBarFrame"].shown = true')
    if o.get("walls"):
        L.execute('GAME["g.ChatFrame1"] = { x = 20, y = 560, w = 540, h = 500, shown = true }; '
                  'GAME["g.ObjectiveTrackerFrame"] = { x = 1360, y = 520, w = 540, h = 540, shown = true }')
    if o.get("placed"):
        L.execute('LAYE.nbar = { dx = -560, dy = -290, s = 100 }')
    if o.get("glide"):
        L.execute('WowCompanion.nbarSet(true)')
    L.execute('WowCompanion.swingNote(2.4, 0); WowCompanion.swingNote(1.8, 1)')
    L.execute('NOW = NOW + 0.6')
    L.execute('RUN(0.2)')
    G.COMBAT = True
    G.TG = True
    L.execute('EV("PLAYER_REGEN_DISABLED")')
    if o.get("glide"):
        L.execute('RUN(0.12, 0.02)')
    elif o.get("fadein"):
        L.execute('RUN(0.18, 0.02)')
    else:
        L.execute('RUN(0.5, 0.02)')
    if o.get("chat"):
        G.CHAT_UP = True
        L.execute('RUN(0.1)')
    return L


def scenes_pin():
    """the renders follow the rules: every scene's Wings shown exactly when its caption says so (nbar12: Party mode's fight = the Wings
    with the Party layer)"""
    for name, title, o in SCENES:
        L = scene_run(o)
        T = L.globals().WowCompanion.nbar.top
        shown = bool(T.shown)
        if o.get("party"):
            nb = L.globals().WowCompanion.nbar
            chk("R. render scene %s: Party mode's fight -> the Wings with the Party layer (and its caption says so)" % name,
                shown and nb.party is not None and "Party layer" in title, (shown, title))
        else:
            chk("R. render scene %s: the Wings shown" % name, shown, shown)


def render(out_dir):
    """the top screen from the Lua math: the scene's pieces at their rects, the stock bar where shown, and the bar exactly where nbar7's
    frames stand (each button's anchor read back from the frames, not recomputed)"""
    from PIL import Image, ImageDraw, ImageFont
    os.makedirs(out_dir, exist_ok=True)
    try:
        friz = ImageFont.truetype("arialbd.ttf", 22)
        small = ImageFont.truetype("arialbd.ttf", 15)
        tiny = ImageFont.truetype("arial.ttf", 13)
    except Exception:
        friz = small = tiny = ImageFont.load_default()
    icons = []
    for n in ("ic_bolt", "ic_storm", "ic_earth", "ic_heal", "ic_flame", "ic_shield", "ic_fist", "ic_totem", "ic_heal2", "ic_bigheal"):
        p = os.path.join(ICONS, n + ".png")
        icons.append(Image.open(p).convert("RGBA") if os.path.exists(p) else None)

    scenes = SCENES
    made = []
    for name, title, o in scenes:
        L = scene_run(o)
        G = L.globals()
        img = Image.open(BG).convert("RGBA") if os.path.exists(BG) else Image.new("RGBA", (1920, 1080), (60, 50, 30, 255))
        lay = Image.new("RGBA", img.size, (0, 0, 0, 0))
        d = ImageDraw.Draw(lay)
        W = L.globals().WowCompanion

        def frect(fr, fill, outline, label=None):
            if fr is None or not fr.r or fr.shown is False:
                return
            x, y, w, h = fr.r.l, fr.r.t, fr.r.w, fr.r.h
            d.rectangle((x, y, x + w, y + h), fill=fill, outline=outline, width=2)
            if label:
                d.text((x + 6, y + 4), label, fill=(255, 240, 200, 255), font=tiny)
        # the game's shown frames (as the solver saw them)
        for gid, g in L.eval("GAME").items():
            if g.shown:
                d.rectangle((g.x, g.y, g.x + g.w, g.y + g.h), fill=(40, 60, 90, 110), outline=(120, 170, 230, 220), width=2)
                d.text((g.x + 8, g.y + 6), gid[2:], fill=(200, 225, 255, 255), font=small)
        # the tap pad's band
        d.rectangle((720, 24, 1200, 178), outline=(255, 80, 80, 200), width=2)
        d.text((728, 160), "tap pad band (never covered)", fill=(255, 140, 140, 255), font=tiny)
        # our pieces: the target bar, the HUD bars, its aura host, Party mode's rows
        tb = W.uf.tb.f
        frect(tb, (110, 14, 14, 170), (200, 160, 70, 255), "Target")
        frect(tb.cast, (40, 30, 10, 170), (200, 160, 70, 255))
        hb = W.prd.f
        x, y, w, h = hb.r.l, hb.r.t, hb.r.w, hb.r.h
        d.rectangle((x - 6, y - 6, x + w + 6, y + h + 6), fill=(10, 14, 10, 150))
        d.rectangle((x, y, x + w, y + 14), fill=(20, 20, 20, 255), outline=(190, 150, 60, 255), width=2)
        d.rectangle((x + 3, y + 3, x + 3 + int((w - 6) * 0.72), y + 11), fill=(40, 190, 40, 255))
        d.rectangle((x, y + 18, x + w, y + h), fill=(20, 20, 20, 255), outline=(190, 150, 60, 255), width=2)
        d.rectangle((x + 3, y + 21, x + 3 + int((w - 6) * 0.46), y + h - 3), fill=(30, 100, 230, 255))
        frect(W.prd.box.host, (0, 0, 0, 90), (120, 110, 80, 160), "auras")
        for i, fr in W.uf.pf.frames.items():
            if fr.shown is not False and fr.r:
                x, y, w, h = fr.r.l, fr.r.t, fr.r.w, fr.r.h
                d.text((x + 40, y), ["Brakka", "Elowen", "Quill", "Sly"][i - 1], fill=(240, 200, 120, 255), font=friz)
                d.rectangle((x + 30, y + 28, x + w, y + 50), fill=(20, 20, 20, 255), outline=(190, 150, 60, 255), width=2)
                d.rectangle((x + 33, y + 31, x + 33 + int((w - 36) * (0.9 - 0.15 * i)), y + 47), fill=(40, 190, 40, 255))
        if G.CHAT_UP:
            d.rectangle((340, 639, 1580, 1080), fill=(0, 0, 0, 120), outline=(190, 150, 60, 255), width=3)
            d.text((360, 660), "[Party] Iselle: pulling next pack", fill=(170, 170, 255, 255), font=friz)
        img.alpha_composite(lay)
        # the bar, from its frames
        f = W.nbar.f
        # nothing of the Wings drawn when they are not shown (Party mode live: the band keeps the controls below)
        alpha = float(f.alpha or 1) if (W.nbar.top.shown and f.shown is not False) else 0.0
        bar = Image.new("RGBA", img.size, (0, 0, 0, 0))
        bd = ImageDraw.Draw(bar)
        sc = f.scale or 1
        # nbar11 THE RING LOOK as the frames hold it: each slot's own size (46 square D-pad, 44 round faces), the well, the icon inset
        # 3.5 and masked, the bronze slot ring, the glyph badge (17) at its own offset (top-right), the count bottom-right
        RING = (176, 140, 90, 255)
        for p in range(1, 9):
            b = f.btn[p]
            cx, cy = L.eval("BCENTRE")(p)
            Bp = (b.w or W.nbar.B) * sc
            x0, y0 = cx - Bp / 2, cy - Bp / 2
            face = bool(b.circle)
            fb = (x0 - 10 * sc, y0 - 10 * sc, x0 + Bp + 10 * sc, y0 + Bp + 10 * sc)
            if face:
                bd.ellipse(fb, fill=(255, 196, 90, 28))
                bd.ellipse((x0, y0, x0 + Bp, y0 + Bp), fill=(10, 8, 6, 255))
            else:
                bd.rounded_rectangle(fb, radius=int(Bp / 5), fill=(255, 196, 90, 28))
                bd.rounded_rectangle((x0, y0, x0 + Bp, y0 + Bp), radius=int(Bp / 7), fill=(10, 8, 6, 255))
            ic = icons[(p - 1) % len(icons)]
            ins = W.nbar.INSET * sc
            if ic is not None:
                side = int(Bp - 2 * ins)
                im = ic.resize((side, side))
                m = Image.new("L", im.size, 0)
                if face:
                    ImageDraw.Draw(m).ellipse((0, 0, side - 1, side - 1), fill=255)
                else:
                    ImageDraw.Draw(m).rounded_rectangle((0, 0, side - 1, side - 1), radius=int(side / 7), fill=255)
                im.putalpha(m)
                bar.alpha_composite(im, (int(x0 + ins), int(y0 + ins)))
            ring = (x0, y0, x0 + Bp, y0 + Bp)
            if face:
                bd.ellipse(ring, outline=RING, width=max(2, int(3 * sc)))
            else:
                bd.rounded_rectangle(ring, radius=int(Bp / 7), outline=RING, width=max(2, int(3 * sc)))
            # the glyph badge where the frame put it (CENTER offsets, bar units up = screen up)
            gp = b.glyph.pts[1]
            gx, gy = cx + gp[4] * sc, cy - gp[5] * sc
            gr = W.nbar.GLYPH * sc / 2
            red = p == 8
            atl = b.glyph.atlas or ""
            if "dpad" in atl:
                bd.ellipse((gx - gr, gy - gr, gx + gr, gy + gr), fill=(30, 30, 30, 255), outline=(220, 220, 220, 255), width=1)
                arm = gr * 0.55
                bd.line((gx - arm, gy, gx + arm, gy), fill=(235, 235, 235, 255), width=max(2, int(2 * sc)))
                bd.line((gx, gy - arm, gx, gy + arm), fill=(235, 235, 235, 255), width=max(2, int(2 * sc)))
            else:
                word = {5: "X", 6: "Y", 7: "B", 8: "A"}.get(p, "")
                bd.ellipse((gx - gr, gy - gr, gx + gr, gy + gr), fill=(245, 245, 245, 255), outline=(30, 30, 30, 255), width=1)
                bd.text((gx, gy), word, fill=(230, 40, 40, 255) if red else (20, 20, 20, 255), font=small, anchor="mm")
        if f.chip and f.chip[1].shown:
            for i in (1, 2):
                c = f.chip[i]
                cx, cy = L.eval("PARTCENTRE")(c)
                w2, h2 = W.nbar.TOP.CHIP_W * sc / 2, W.nbar.TOP.CHIP_H * sc / 2
                if c.lit:
                    rr = h2 + 4 * sc
                    bd.ellipse((cx - rr, cy - rr, cx + rr, cy + rr), fill=(255, 200, 76, 96))
                col = (255, 255, 255, 255) if c.lit else (255, 255, 255, 140)
                bd.rounded_rectangle((cx - w2 * 0.8, cy - h2 * 0.75, cx + w2 * 0.8, cy + h2 * 0.75), radius=int(h2 * 0.7), outline=col,
                                     width=max(2, int(2 * sc)))
                bd.text((cx, cy), "ZL" if i == 1 else "ZR", fill=col, font=small, anchor="mm")
            # nbar12: the L / R plates over the chips (the targeting picture's word, the bumper letter at the inner bottom corner) and
            # Party mode's chord rows under them
            for i in (1, 2):
                pl = f.plate[i]
                if pl.shown:
                    cx, cy = L.eval("PARTCENTRE")(pl)
                    w2, h2 = W.nbar.TOP.PLATE_W * sc / 2, W.nbar.TOP.PLATE_H * sc / 2
                    bd.rounded_rectangle((cx - w2, cy - h2, cx + w2, cy + h2), radius=int(h2 * 0.35), fill=(24, 20, 14, 235),
                                         outline=(176, 140, 90, 255), width=max(1, int(2 * sc)))
                    kind = {"friendly": (96, 214, 90, 255), "hostile": (230, 70, 60, 255)}.get(pl.kind, (220, 200, 120, 255))
                    rr = h2 * 0.45
                    bd.ellipse((cx - rr, cy - rr - 2 * sc, cx + rr, cy + rr - 2 * sc), outline=kind, width=max(2, int(3 * sc)))
                    gx = cx + (W.nbar.TOP.PLATE_GX if i == 1 else -W.nbar.TOP.PLATE_GX) * sc
                    gy = cy - W.nbar.TOP.PLATE_GY * sc
                    gr = W.nbar.GLYPH * sc / 2
                    bd.ellipse((gx - gr, gy - gr, gx + gr, gy + gr), fill=(245, 245, 245, 255), outline=(30, 30, 30, 255), width=1)
                    bd.text((gx, gy), "L" if i == 1 else "R", fill=(20, 20, 20, 255), font=small, anchor="mm")
                for j in (1, 2, 3):
                    t = f.chord[i][j]
                    if t.shown:
                        cx, cy = L.eval("PARTCENTRE")(t)
                        rr = W.nbar.TOP.CHORD * sc / 2
                        bd.ellipse((cx - rr, cy - rr, cx + rr, cy + rr), fill=(90, 120, 200, 255), outline=(176, 140, 90, 255), width=1)
            for i in (1, 2):
                s_ = f.sw[i]
                if s_.shown:
                    cx, cy = L.eval("PARTCENTRE")(s_)
                    w2 = s_.w * sc
                    hh_ = W.nbar.TOP.SW_H * sc
                    bd.rectangle((cx - w2 / 2, cy, cx + w2 / 2, cy + hh_), fill=(0, 0, 0, 200), outline=(90, 80, 60, 255))
                    bd.rectangle((cx - w2 / 2, cy, cx - w2 / 2 + w2 * (s_.k or 0), cy + hh_), fill=(240, 200, 50, 255))
        if alpha < 1:
            a = bar.split()[3].point(lambda v, alpha=alpha: int(v * alpha))
            bar.putalpha(a)
        img.alpha_composite(bar)
        # the solver's boxes (thin dashed outline) and the caption
        sp = todict(W.nbar.top.spot) if (W.nbar.top.spot and W.nbar.top.shown) else {"case": "-", "form": "no Wings", "rects": {}}
        dd = ImageDraw.Draw(img)
        for k in ((1, 2) if sp["rects"] else ()):
            rr = sp["rects"][k]
            for x in range(int(rr["x"]), int(rr["x"] + rr["w"]), 12):
                dd.line((x, rr["y"], x + 6, rr["y"]), fill=(255, 215, 0, 255), width=1)
                dd.line((x, rr["y"] + rr["h"], x + 6, rr["y"] + rr["h"]), fill=(255, 215, 0, 255), width=1)
        cap = Image.new("RGBA", img.size, (0, 0, 0, 0))
        ImageDraw.Draw(cap).rectangle((0, 0, 1920, 44), fill=(0, 0, 0, 170))
        img.alpha_composite(cap)
        dd.text((20, 10), "%s   [case %s, %s, alpha %.2f]" % (title, sp["case"], sp["form"], alpha), fill=(255, 230, 160, 255), font=friz)
        path = os.path.join(out_dir, name + ".png")
        img.convert("RGB").save(path)
        made.append(path)
    # a contact sheet
    th = [Image.open(p).resize((640, 360)) for p in made]
    sheet = Image.new("RGB", (1280, 360 * ((len(th) + 1) // 2)), (20, 20, 20))
    for i, t in enumerate(th):
        sheet.paste(t, ((i % 2) * 640, (i // 2) * 360))
    sp_ = os.path.join(out_dir, "p3_top_00_overview.png")
    sheet.save(sp_)
    print("renders: " + ", ".join(made + [sp_]))


if __name__ == "__main__":
    main()
