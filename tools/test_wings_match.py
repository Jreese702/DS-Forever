"""nbar17 THE WINGS AS THE BAND, ON THE DEVICE'S TERMS (the user, 2026-11-23, testing 1523 on the Thor: "The triggers look weird and
stretched on both sides and nothing like the bottom. It also looks like the top slides out faster than the bottom slides up", then
"The buttons need to shift in party mode to make space for the hud shifting out"), off-device: the REAL ActionBar.lua (and Safety.lua)
under test_nintendo_bar.py's stub, driven frame by frame through its own driver (NB.topTick), with the real plate art read from disk.

    python -B tools/test_wings_match.py

  A THE PLATES AND THE CHIPS (what is DRAWN, not the frame box - 1523's pins checked the 64 x 50 box while the Blizzard plate it
    stretched into it showed a 1.53-wide body; the ZL / ZR glyph (76 x 75) went into a 40 x 28 box, 1.41x wide): every texture of the
    L / R plate (fill, held fill, reticle, bumper glyph) and of the ZL / ZR chip (glyph, held glow) on BOTH sides drawn at its source's
    own aspect (within 2 %: no stretch); the plate's visible body the band's 39 x 31 plate (aspect within 2 %) at its size on the top
    panel (nbar18: 0.75 x the band's 124 x 98.6 bottom px = 5.63 x 4.48 mm within 3 %); the bumper glyph 0.75 x the band's 46 bottom px
    (2.09 mm) and the ZL / ZR glyph 0.75 x its 51.5 (2.34 mm) within 5 %; the band's own art (our plate TGA from its vector path, the
    Gamepad_*_64 glyphs) first.
  B THE SLIDE, ON ONE WALL CLOCK: the fight's switch at t = 0 (the add-on's frame that sees it); the band (the app) starts 82 ms later (the 40-90 ms channel's middle,
    65, and the animator's first frame, 17) on Motion.TOP_SLIDE_UP 240 ms over (0.4, 0, 1, 1), 242 bottom px x 0.06055 mm, its whole
    way up TopSlide.COLS_UP_MS; the Wings sampled at 60 fps (and 30 fps) as the add-on's own driver lays them: never ahead of the band
    (<= 1 mm) before it has left the bottom screen (no early / fast start); the same speed at the seam (the band's last pixel off, 230
    ms into its slide: within 10 %); the same whole time (the Wings' motion COLS_UP_MS within 5 %, landing within 25 ms of the band);
    the first moving frame <= 10 px at 30 fps.
  C PARTY MODE'S CENTRING, FRAME BY FRAME: a Trail-like HUD (prd22's rule: a new shift = the bars and the pet strip fade out 0.2 s, move
    while unseen, fade in 0.2 s; P.lay.block at the shift applied) and Hub-like rows (hub42's: the same fade, PF.g the cx they are drawn
    at; rows and the Select tab as the party's size lays them) answering NB.centreDx every frame - for 1-5 members, the block right of
    the centre and left of it (both shifts): from the fight's start through a LATE move / resize (a member joins, a member leaves, the
    pet comes out beside the bars) no Wings half box touches a drawn block part (alpha > 0.02) on any frame; once settled the block
    stands centred, the Wings >= HUD_MARGIN (60 px) either side of it, the shift asked = the shift applied; the Wings visibly move with
    the block (a late resize that moves it moves them).
  D nbar18 THE TRIGGERS A QUARTER SMALLER (the user, 2026-11-24, playing 1.0.40: "I dont like how much bigger that the triggers are in
    the transition from the bottom screen to the top"; picks "Both" and "About a quarter smaller"): every plate / chip part and offset
    0.75 x nbar17's (aspect kept), the plate's inner edge and top where nbar17 had them by the diamond, the chip under it at 0.75 x the
    gap, nothing else resized, the solver's half box holding them all and narrower than nbar17's 227 (fails on 94e79938c).
No device is read. Needs lupa and PIL. Exit code 1 on any failure."""
import math
import os
import re
import sys

from lupa import lua51 as lupa
from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ADDONS = os.path.join(ROOT, "companion", "app", "src", "main", "assets", "companion", "addons")
MEDIA = os.path.join(ADDONS, "WowCompanion", "Media")
NBT = open(os.path.join(ROOT, "tools", "test_nintendo_bar.py"), encoding="utf-8").read()
STUB = re.search(r"\nSTUB = r'''(.*?)'''", NBT, re.S).group(1)
SCENE = re.search(r"\nSCENE = r'''(.*?)'''", NBT, re.S).group(1)
TOPSLIDE = open(os.path.join(ROOT, "companion", "app", "src", "main", "java", "com", "jrees", "wowcompanion", "TopSlide.kt"),
                encoding="utf-8").read()
MOTION = open(os.path.join(ROOT, "companion", "app", "src", "main", "java", "com", "jrees", "wowcompanion", "Motion.kt"),
              encoding="utf-8").read()

MM_TOP = 0.06918                        # the Thor's top panel: 6.0", 1920 x 1080
MM_BOTTOM = 0.06055                     # its bottom panel: 3.92", 1240 x 1080
HUD_MARGIN = 60
# the band (CastView, Diamonds at rest, 1240 wide: ps 1): the L / R plate 124 x 98.6 px (39 x 31 units), its bumper glyph 46 px
# (BUMPER_GLYPH), the ZL / ZR glyph TRIG_H 56 x 0.92 = 51.52 px (UiKit.triggerChip), square
BAND_PLATE_MM = (124 * MM_BOTTOM, 124 * 31 / 39 * MM_BOTTOM)
BAND_PLATE_ASPECT = 39 / 31
BAND_BUMPER_MM = 46 * MM_BOTTOM
BAND_TRIG_MM = 56 * 0.92 * MM_BOTTOM
# nbar18 THE TRIGGERS A QUARTER SMALLER (the user, 2026-11-24, playing 1.0.40: "I dont like how much bigger that the triggers are in the
# transition from the bottom screen to the top"; picks "Both" and "About a quarter smaller"): the Top screen's plate and chip parts are
# TRIG_K x the band's: the plate 5.63 x 4.48 mm, the bumper glyph 2.09 mm, the ZL / ZR glyph 2.34 mm
TRIG_K = 0.75
TOP_PLATE_MM = (BAND_PLATE_MM[0] * TRIG_K, BAND_PLATE_MM[1] * TRIG_K)
TOP_BUMPER_MM = BAND_BUMPER_MM * TRIG_K
TOP_TRIG_MM = BAND_TRIG_MM * TRIG_K
# nbar17's sizes (bar units; 94e79938c, the band's size): what nbar18 scales by TRIG_K
NBAR17 = {"PLATE_W": 62, "PLATE_H": 62 * 31 / 39, "PLATE_GLYPH": 23, "PLATE_GX": 26, "PLATE_GY": -20.15, "PLATE_RET": 18 * 62 / 39,
          "PLATE_RET_DY": 62 / 39, "CHIP_W": 25.8, "CHIP_H": 25.8, "CHIP_G": 25.8, "CHIP_GLOW": 50.4, "CHIP_GAP": 4.86}
# the game's atlases these textures may wear: (w, h) native (the harvest manifest, ui/cast/manifest.json) and the plate's opaque body
# (alpha > 200) inside its 50 x 45 cell; the Gamepad_*_64 glyphs are 64 x 64 (the band's kit pad_* art: the same family)
ATLAS = {
    "gamepad-targeting-left-shoulder-bg": (50, 45), "gamepad-targeting-right-shoulder-bg": (50, 45),
    "gamepad-targeting-left-shoulder-pressed": (50, 45), "gamepad-targeting-right-shoulder-pressed": (50, 45),
    "gamepad-targeting-friendly": (50, 45), "gamepad-targeting-hostile": (50, 45), "gamepad-targeting-shortcuts": (50, 45),
    "gamepad-switch-128x-zl-normal": (76, 75), "gamepad-switch-128x-zr-normal": (76, 75), "gamepad-switch-128x-zl-pressed": (76, 75),
    "gamepad-switch-128x-zr-pressed": (76, 75), "gamepad-switch-128x-shoulder-l-normal": (76, 75),
    "gamepad-switch-128x-shoulder-r-normal": (76, 75), "gamepad-switch-128x-shoulder-l-pressed": (76, 75),
    "gamepad-switch-128x-shoulder-r-pressed": (76, 75),
}
ATLAS_BODY = {"gamepad-targeting-left-shoulder-bg": (5, 8, 46, 39), "gamepad-targeting-right-shoulder-bg": (4, 8, 45, 39)}

fails = []
passes = 0


def chk(name, ok, got=""):
    global passes
    print(("PASS " if ok else "FAIL ") + name + ((" -> " + str(got)) if got != "" and not ok else ""))
    if ok:
        passes += 1
    else:
        fails.append(name)


# ------------------------------------------------------------------------------------------------------------------------ the scene
SIM = r'''
local F = getmetatable(UIParent)
function F:IsVisible() return self.shown ~= false end
function F:GetLeft() return self.rl end
function F:GetTop() return self.rt end
function F:GetWidth() return self.rw or self.w end
function F:GetHeight() return self.rh or self.h end
function F:GetEffectiveAlpha() return self.alpha end
UIParent.GetEffectiveScale = function() return 1 end
WowCompanion.lay = { screen = function() return 1920, 1080, 1 end, get = function() return nil end }
local pm = WowCompanion.padMap
pm.combatTopOn = function() return true end
PARTY = false
pm.party = { on = function() return PARTY end, live = function() return PARTY end }
-- (1080-line px from the top-left, the scene's UI at 1080 lines: 1 UI unit = 1 px)
FADE = 0.2
-- (the bars 42 tall; the block P.lay.block gives the default HUD's at the Thor's UI: 820, 890, 280 x 140 - the bars, the pips, the
-- aura row's room - so the halves rest on the floor as on the device: the default drop)
HUD = { x0 = 820, top = 890, w = 280, h = 42, bh = 140, pet = false, members = 1 }
local P = { cx = 0, a = 1, PET_FADE_S = FADE, partyRoomPx = 0, lift = 0, petSide = "beside", auraUp = true }
local bars = CreateFrame("Frame", nil, UIParent)
local petL = CreateFrame("Frame", nil, UIParent)
P.f, P.petL = bars, petL
P.lay = { base = function() return 960, 890 end, size = function() return 280, 42 end, off = function(w) return -w / 2, 0 end,
          on = function() return HUDON ~= false end }
HUDON = true
-- prd22: the block at the shift APPLIED (the bars, the pet strip beside them in Party mode)
function P.lay.block(e)
    if HUDON == false then return nil end
    local w = HUD.w + (HUD.pet and 132 or 0)
    return { x = HUD.x0 + P.cx, y = HUD.top, w = w, h = HUD.bh }
end
function P.place()
    bars.rl, bars.rt, bars.rw, bars.rh = HUD.x0 + P.cx, 1080 - HUD.top, HUD.w, HUD.h
    petL.rl, petL.rt, petL.rw, petL.rh = HUD.x0 + P.cx + HUD.w + 12, 1080 - HUD.top, 120, HUD.h
    bars.shown = HUDON ~= false
    petL.shown = (HUDON ~= false) and HUD.pet
end
P.place()
WowCompanion.prd = P
-- Hub: the party members' rows under the HUD (hub35 / hub40: one column's bar each, a shared bottom row two cells) and the Select tab
local PF = { at = "hud", want = "hud", frames = {}, g = { cx = 0 }, room = 0, rows = 0, fa = 1 }
for i = 1, 4 do PF.frames[i] = CreateFrame("Frame", nil, UIParent); PF.frames[i].shown = false end
PF.tab = CreateFrame("Frame", nil, UIParent)
-- n party members (the group less you) -> the cells' (row, column)
CELLS = { [0] = {}, [1] = { { 0, 0 } }, [2] = { { 0, 0 }, { 1, 0 } }, [3] = { { 0, 0 }, { 1, 0 }, { 1, 1 } },
          [4] = { { 0, 0 }, { 0, 1 }, { 1, 0 }, { 1, 1 } } }
function PF.draw()
    local n = math.max(0, math.min(4, HUD.members - 1))
    local cells = CELLS[n]
    local cx = PF.g.cx
    local x0 = HUD.x0 - 76 + cx
    local rows = 0
    for i = 1, 4 do
        local f, c = PF.frames[i], cells[i]
        if c then
            f.rl, f.rt, f.rw, f.rh = x0 + c[2] * 316, 1080 - (HUD.top + HUD.h + 25 + c[1] * 64), 300, 47
            f.shown = true
            rows = math.max(rows, c[1] + 1)
        else f.shown = false end
    end
    local th = (rows > 0) and (rows * 64 - 17) or HUD.h
    local ty = (rows > 0) and (HUD.top + HUD.h + 25) or HUD.top
    local tx = (rows > 0) and (x0 - 6 - 22) or (HUD.x0 + cx - 6 - 22)
    PF.tab.rl, PF.tab.rt, PF.tab.rw, PF.tab.rh = tx, 1080 - ty, 22, th
    PF.tab.shown = PARTY
    for i = 1, 4 do if not PARTY then PF.frames[i].shown = false end end
    PF.rows = rows
    local room = rows * 64
    if room ~= PF.room then PF.room = room; P.partyRoomPx = room end
end
PF.draw()
WowCompanion.uf = { pf = PF, layParty = { on = function() return false end } }
-- the two fades, as Trail prd22's P.petMoveStep and Hub hub42's PF.fadeTick: out FADE, the move while unseen, in FADE
local function alphaAll(fs, a) for _, f in ipairs(fs) do f.alpha = a end end
function MOVES(dt)
    local want = WowCompanion.nbar.centreDx()
    if type(want) ~= "number" then want = 0 end
    if math.abs(want - P.cx) > 0.5 then
        P.a = P.a - dt / FADE
        if P.a <= 0 then P.a = 0; P.cx = want; P.place() end
    elseif P.a < 1 then P.a = math.min(1, P.a + dt / FADE) end
    alphaAll({ bars, petL }, P.a)
    if math.abs(want - PF.g.cx) > 0.5 then
        PF.fdir = -1
        PF.fa = PF.fa - dt / FADE
        if PF.fa <= 0 then PF.fa = 0; PF.g = { cx = want }; PF.fdir = 1; PF.draw() end
    elseif PF.fa < 1 then PF.fdir = 1; PF.fa = math.min(1, PF.fa + dt / FADE); if PF.fa >= 1 then PF.fdir = nil end end
    local fs = { PF.tab }
    for i = 1, 4 do fs[#fs + 1] = PF.frames[i] end
    alphaAll(fs, PF.fa)
end
-- the Wings' frames laid this frame: each half's box at its place (NB.topPlace's [at], the spot's scale x [m])
PLACED = {}
local place0 = WowCompanion.nbar.topPlace
WowCompanion.nbar.topPlace = function(at, sp, m)
    PLACED[#PLACED + 1] = { t = NOW, lx = at.l.x, ly = at.l.y, rx = at.r.x, ry = at.r.y, s = (sp.s or 1) * (m or 1) }
    return place0(at, sp, m)
end
'''


def boot(scene=True):
    L = lupa.LuaRuntime(unpack_returned_tuples=True)
    L.execute(STUB)
    L.execute(SCENE)
    L.execute("PADMAP_ON()")
    load = L.eval('function(src, name, a) local f, e = loadstring(src, name); if not f then error(e) end; return f(a) end')
    load(open(os.path.join(ADDONS, "WowCompanion", "Safety.lua"), encoding="utf-8").read(), "@Safety.lua", "WowCompanion")
    ab = os.environ.get("WINGS_AB") or os.path.join(ADDONS, "WowCompanion", "ActionBar.lua")   # (another ActionBar: the 1523 one's run)
    load(open(ab, encoding="utf-8").read(), "@ActionBar.lua", "WowCompanion")
    if scene:
        L.execute(SIM)
    return L


def ticker(L, dt):
    G = L.globals()
    NB = G.WowCompanion.nbar

    def tick(n=1):
        for _ in range(n):
            G.NOW = G.NOW + dt
            NB.topTick(dt)
            NB.tick(dt)                                    # (the bar's own poll: the pictures, the plates' topPlates)
            G.MOVES(dt)
    return tick


# ------------------------------------------------------------------------------------------------------------------------ A: art
def tex_src(t):
    """a texture's source: ('atlas', name, (w, h), body or None) / ('file', path, (w, h) of its texcoord rect in texels, body)"""
    if t.atlas:
        a = str(t.atlas)
        if a.startswith("Gamepad_") and a.endswith("_64"):
            return ("atlas", a, (64, 64), None)
        if a in ATLAS:
            return ("atlas", a, ATLAS[a], ATLAS_BODY.get(a))
        return ("atlas", a, None, None)
    if t.tex:
        p = str(t.tex)
        if p.lower().startswith("interface\\addons\\wowcompanion\\media\\"):
            fn = os.path.join(MEDIA, p.split("\\")[-1])
            if not fn.lower().endswith(".tga"):
                fn += ".tga"
            im = Image.open(fn).convert("RGBA")
            W, H = im.size
            tc = [float(t.tcoord[i]) for i in range(1, 5)] if t.tcoord else [0.0, 1.0, 0.0, 1.0]
            l, r = sorted((tc[0], tc[1]))
            u, b = sorted((tc[2], tc[3]))
            box = (int(round(l * W)), int(round(u * H)), int(round(r * W)), int(round(b * H)))
            crop = im.crop(box)
            a = crop.split()[3].point(lambda v: 255 if v > 200 else 0)
            bb = a.getbbox()
            return ("file", fn, (box[2] - box[0], box[3] - box[1]), bb)
        return ("file", p, None, None)
    return (None, None, None, None)


def drawn(t):
    """a texture's drawn size (bar units): SetAllPoints -> its parent's box, else its own SetSize"""
    pts = t.pts
    if pts and pts[1] and pts[1][1] == "ALL":
        par = pts[1][2] or t.parent
        return float(par.w), float(par.h)
    return (float(t.w) if t.w else None), (float(t.h) if t.h else None)


def part_a():
    L = boot()
    G = L.globals()
    NB = G.WowCompanion.nbar
    tick = ticker(L, 1 / 60)
    tick(30)
    G.COMBAT = True
    tick(40)
    f = NB.f
    chk("A. the Wings up in the fight (the scene: flag on, fightSw, a clear lane)", bool(NB.top.shown) and f is not None and f.plate is not None,
        (NB.top.shown, NB.top.form))
    if not (f and f.plate):
        return
    s = float(NB.TOP.MATCH_S)
    for side, i in (("L", 1), ("R", 2)):
        p = f.plate[i]
        c = f.chip[i]
        # every texture the plate and the chip draw: at its source's aspect
        texs = [("plate fill", p.bg), ("plate held", p.held), ("reticle", p.icon), ("bumper glyph", p.glyph), ("ZL/ZR glyph", c.g)]
        if c.disc is not None:
            texs.append(("chip glow", c.disc))
        bad = []
        for nm, t in texs:
            kind, name, wh, _ = tex_src(t)
            dw, dh = drawn(t)
            if kind is None or wh is None or not dw or not dh:
                bad.append("%s: source %s %s drawn %s x %s (unknown)" % (nm, kind, name, dw, dh))
                continue
            ra = (dw / dh) / (wh[0] / wh[1])
            if abs(ra - 1) > 0.02:
                bad.append("%s: %s %dx%d drawn %.1f x %.1f units (x%.2f wide)" % (nm, name, wh[0], wh[1], dw, dh, ra))
        chk("A. %s: every plate / chip texture drawn at its source's own aspect (within 2 %%: no stretch)" % side, not bad, bad)
        # the plate's visible body
        kind, name, wh, body = tex_src(p.bg)
        dw, dh = drawn(p.bg)
        if wh and body and dw:
            bw = dw * (body[2] - body[0]) / wh[0]
            bh = dh * (body[3] - body[1]) / wh[1]
            asp = bw / bh
            mm = (bw * s * MM_TOP, bh * s * MM_TOP)
            chk("A. %s: the plate's visible body is the band's 39 x 31 plate (aspect %.3f, the band's %.3f, within 2 %%)"
                % (side, asp, BAND_PLATE_ASPECT), abs(asp / BAND_PLATE_ASPECT - 1) <= 0.02, (name, round(asp, 3)))
            chk("A. nbar18 %s: ... a quarter under the band's size on the top panel (%.2f x %.2f mm, 0.75 x the band's: %.2f x %.2f "
                "within 3 %%)" % ((side,) + mm + TOP_PLATE_MM),
                abs(mm[0] / TOP_PLATE_MM[0] - 1) <= 0.03 and abs(mm[1] / TOP_PLATE_MM[1] - 1) <= 0.03)
        else:
            chk("A. %s: the plate's visible body known (%s %s)" % (side, kind, name), False)
        gw, gh = drawn(p.glyph)
        cw, ch = drawn(c.g)
        gmm = (gw or 0) * s * MM_TOP
        cmm = (ch or 0) * s * MM_TOP
        chk("A. nbar18 %s: the bumper glyph %.2f mm (0.75 x the band's: %.2f within 5 %%), the trigger glyph %.2f mm (0.75 x the band's: "
            "%.2f within 5 %%)" % (side, gmm, TOP_BUMPER_MM, cmm, TOP_TRIG_MM),
            abs(gmm / TOP_BUMPER_MM - 1) <= 0.05 and abs(cmm / TOP_TRIG_MM - 1) <= 0.05)
        chk("A. %s: the band's own art first - the plate our vector plate (not Blizzard's shoulder-bg), the glyphs the band's "
            "Gamepad_*_64 family" % side,
            p.bg.tex is not None and "wings_plate" in str(p.bg.tex) and str(p.glyph.atlas or "").startswith("Gamepad_")
            and str(c.g.atlas or "").startswith("Gamepad_"), (p.bg.tex, p.bg.atlas, p.glyph.atlas, c.g.atlas))
    # the two plates mirror each other (the cut on each one's OUTER top corner)
    t1 = [float(f.plate[1].bg.tcoord[k]) for k in range(1, 5)] if f.plate[1].bg.tcoord else None
    t2 = [float(f.plate[2].bg.tcoord[k]) for k in range(1, 5)] if f.plate[2].bg.tcoord else None
    chk("A. the right plate is the left one mirrored (its texcoords' left / right swapped)", t1 is not None and t2 is not None
        and abs(t1[0] - t2[1]) < 1e-9 and abs(t1[1] - t2[0]) < 1e-9 and t1[2:] == t2[2:], (t1, t2))


# ------------------------------------------------------------------------------------------------------------------------ B: slide
def cubic(u, x1, y1, x2, y2):
    if u <= 0:
        return 0.0
    if u >= 1:
        return 1.0

    def bz(t, a, b):
        m = 1 - t
        return 3 * m * m * t * a + 3 * m * t * t * b + t * t * t
    lo, hi = 0.0, 1.0
    for _ in range(50):
        mid = (lo + hi) / 2
        if bz(mid, x1, x2) < u:
            lo = mid
        else:
            hi = mid
    return bz((lo + hi) / 2, y1, y2)


BAND_UP_MS = float(re.search(r"TOP_SLIDE_UP\s*=\s*(\d+)L?", MOTION).group(1))
BAND_TRAVEL_MM = 242 * MM_BOTTOM                       # HomeLayoutSolver: restBottom 226 + AWAY_PAST 16
BAND_START_MS = 65 + 17                                # the channel's middle + the animator's first frame
BAND_EXIT_K = 226 / 242.0                              # its last pixel off the bottom screen


def cols_up_ms():
    """TopSlide.COLS_UP_MS as TopSlide works it out (BRAKE_AT_MS 180 on UP, then the even brake)"""
    ba = float(re.search(r"BRAKE_AT_MS = (\d+)L", TOPSLIDE).group(1))
    u = ba / BAND_UP_MS
    k = cubic(u, 0.4, 0, 1, 1)
    h = 1e-5
    v = (cubic(u + h, 0.4, 0, 1, 1) - cubic(u - h, 0.4, 0, 1, 1)) / (2 * h) / BAND_UP_MS
    return round(ba + 2 * (1 - k) / v)


def band_mm(t):
    return BAND_TRAVEL_MM * cubic((t - BAND_START_MS) / BAND_UP_MS, 0.4, 0, 1, 1)


def band_exit_ms():
    lo, hi = 0.0, BAND_UP_MS
    for _ in range(60):
        mid = (lo + hi) / 2
        if cubic(mid / BAND_UP_MS, 0.4, 0, 1, 1) < BAND_EXIT_K:
            lo = mid
        else:
            hi = mid
    return BAND_START_MS + (lo + hi) / 2


def wings_run(fps):
    """the Wings' left half's centre y (px) per laid frame, t in ms after the fight's switch"""
    L = boot()
    G = L.globals()
    dt = 1.0 / fps
    tick = ticker(L, dt)
    tick(int(fps))
    G.PLACED = L.table()
    G.COMBAT = True
    tick(1)                                             # the switch's frame: t = 0 (the add-on's state goes out on it)
    t0 = float(G.NOW)
    tick(int(fps * 0.9))
    out = []
    for i in range(1, len(G.PLACED) + 1):
        e = G.PLACED[i]
        out.append(((float(e.t) - t0) * 1000.0, float(e.ly)))
    NB = G.WowCompanion.nbar
    sp = NB.top.spot
    d = NB.slideDrop(sp, sp)[0]                         # (where the slide starts: wholly under the edge)
    return out, todict_spot(sp, d)


def todict_spot(sp, d):
    return {"ly": float(sp.l.y), "case": sp.case, "lane": sp.lane, "y0": float(sp.l.y) + float(d)} if sp else None


def part_b():
    seq, sp = wings_run(60)
    chk("B. the scene's spot: beside the HUD, a clear lane (the slide)", sp is not None and sp["lane"] is True, sp)
    if not seq:
        chk("B. the Wings laid in the fight", False)
        return
    y_end = sp["ly"]
    y0 = sp["y0"]
    mm = [(t, (y0 - y) * MM_TOP) for t, y in seq]
    moving = [t for t, d in mm if d > 0.01]
    t_move = moving[0] - 1000.0 / 60 if moving else None          # (the last still frame: the motion starts within it)
    landed = [t for t, y in seq if abs(y - y_end) < 0.01]
    t_land = landed[0] if landed else None
    t_seam = band_exit_ms()
    ahead = [(round(t), round(d, 2), round(band_mm(t), 2)) for t, d in mm if t <= t_seam and d > band_mm(t) + 1.0]
    chk("B. no early / fast start: the Wings never more than 1 mm ahead of the band before it has left the bottom screen (%d ms)"
        % round(t_seam), not ahead, ahead[:4])
    # the speed at the seam: both differenced over the same frame either side
    h = 1000.0 / 60

    def wings_at(t):
        for (ta, da), (tb, db) in zip(mm, mm[1:]):
            if ta <= t <= tb:
                return da + (db - da) * (t - ta) / (tb - ta)
        return mm[-1][1]
    vw = (wings_at(t_seam + h) - wings_at(t_seam - h)) / (2 * h)
    vb = (band_mm(t_seam + 0.25) - band_mm(t_seam - 0.25)) / 0.5            # (the band's own, at the instant)
    chk("B. the same speed at the seam: the Wings %.3f mm / ms, the band %.3f as its last pixel leaves (within 10 %%)" % (vw, vb),
        vb > 0 and abs(vw / vb - 1) <= 0.10)
    cu = cols_up_ms()
    band_land = BAND_START_MS + cu
    whole = (t_land - t_move) if (t_land is not None and t_move is not None) else None
    chk("B. the same whole time: the Wings move for %s ms (the band's whole way up COLS_UP_MS %d within 5 %%), landing %s ms after the "
        "switch (the band's %d, within 25 ms)" % (whole and round(whole), cu, t_land and round(t_land), band_land),
        whole is not None and abs(whole / cu - 1) <= 0.05 and abs(t_land - band_land) <= 25)
    seq30, sp30 = wings_run(30)
    ys = [sp30["y0"]] + [y for _, y in seq30]
    jumps = [abs(b - a) for a, b in zip(ys, ys[1:])]
    first = next((j for j in jumps if j > 0.01), None)
    chk("B. at 30 fps the first moving frame is small (%.1f px <= 10)" % (first or -1), first is not None and first <= 10)


# ------------------------------------------------------------------------------------------------------------------------ C: party
def rect(f):
    return (float(f.rl), 1080 - float(f.rt), float(f.rw or f.w), float(f.rh or f.h))


def over(a, b, m=0.0):
    return a[0] - m < b[0] + b[2] and b[0] < a[0] + a[2] + m and a[1] - m < b[1] + b[3] and b[1] < a[1] + a[3] + m


def union(a, b):
    if a is None:
        return b
    x0, y0 = min(a[0], b[0]), min(a[1], b[1])
    return (x0, y0, max(a[0] + a[2], b[0] + b[2]) - x0, max(a[1] + a[3], b[1] + b[3]) - y0)


def drawn_parts(G):
    P, PF = G.WowCompanion.prd, G.WowCompanion.uf.pf
    out = []
    for nm, f in (("bars", P.f), ("pet", P.petL), ("tab", PF.tab)):
        if f.shown and (f.alpha or 0) > 0.02:
            out.append((nm, rect(f)))
    for i in range(1, 5):
        f = PF.frames[i]
        if f.shown and (f.alpha or 0) > 0.02:
            out.append(("row%d" % i, rect(f)))
    return out


def wings_boxes(G, NBL):
    """the two half boxes as the last laid frame stands (NB.topPlace's centres, the half box at that frame's scale)"""
    NB = G.WowCompanion.nbar
    if not NB.top.shown or (NB.top.alpha or 0) <= 0.02 or len(G.PLACED) == 0:
        return []
    e = G.PLACED[len(G.PLACED)]
    hw, hh, ax, ay = NB.halfBox()
    s = float(e.s)
    lb = (float(e.lx) - ax * s, float(e.ly) - ay * s, hw * s, hh * s)
    rb = (float(e.rx) - (hw - ax) * s, float(e.ry) - ay * s, hw * s, hh * s)
    return [lb, rb]


def party_run(members, x0, late):
    L = boot()
    G = L.globals()
    dt = 1 / 30.0
    tick = ticker(L, dt)
    G.PARTY = True
    G.HUD.members = members
    G.HUD.x0 = x0
    G.WowCompanion.prd.place()
    G.WowCompanion.uf.pf.draw()
    tick(30)
    G.PLACED = L.table()
    G.COMBAT = True
    hits = []
    xs = []
    for i in range(int(3.6 / dt)):
        t = (i + 1) * dt
        if abs(t - 1.5) < dt / 2:
            if late == "join":
                G.HUD.members = min(5, members + 1)
            elif late == "leave":
                G.HUD.members = max(1, members - 1)
            elif late == "pet":
                G.HUD.pet = True
            G.WowCompanion.prd.place()
            G.WowCompanion.uf.pf.draw()
        tick(1)
        wb = wings_boxes(G, L)
        if wb:
            bl = None
            for nm, r in drawn_parts(G):
                bl = union(bl, r)
            xs.append((t, wb[0][0], wb[1][0], bl))
        for b in wb:
            for nm, r in drawn_parts(G):
                if over(b, r):
                    hits.append((round(t, 2), nm))
    NB = G.WowCompanion.nbar
    parts = drawn_parts(G)
    block = None
    for nm, r in parts:
        if nm.startswith("row"):
            r = (r[0] - 12, r[1] - 12, r[2] + 24, r[3] + 24)          # (the rows' drawn rim: NB.TOP.HUB_RIM)
        block = union(block, r)
    wb = wings_boxes(G, L)
    P, PF = G.WowCompanion.prd, G.WowCompanion.uf.pf
    res = {"hits": hits, "block": block, "wings": wb, "cdx": NB.centreDx(), "pcx": P.cx, "rcx": PF.g.cx, "xs": xs,
           "case": NB.top.spot.case if NB.top.spot else None}
    return res


def part_c():
    bad, settle, moved_with = [], [], []
    n = 0
    for members in (1, 2, 3, 4, 5):
        for x0 in (980, 640):                                 # the block right of the centre (a shift left) / left of it (right)
            for late in ("join", "leave", "pet"):
                if (late == "join" and members == 5) or (late == "leave" and members == 1):
                    continue
                n += 1
                r = party_run(members, x0, late)
                key = (members, x0, late)
                if r["hits"]:
                    bad.append((key, r["hits"][:3], len(r["hits"])))
                b, wb = r["block"], r["wings"]
                ok = b is not None and len(wb) == 2 and abs(b[0] + b[2] / 2 - 960) <= 1.0
                ok = ok and wb[0][0] + wb[0][2] <= b[0] - HUD_MARGIN + 0.01 and wb[1][0] >= b[0] + b[2] + HUD_MARGIN - 0.01
                ok = ok and abs(r["cdx"] - r["pcx"]) <= 0.5 and abs(r["cdx"] - r["rcx"]) <= 0.5
                if not ok:
                    settle.append((key, b and tuple(round(v) for v in b), [tuple(round(v) for v in x) for x in wb], r["cdx"], r["pcx"],
                                   r["rcx"], r["case"]))
                # the Wings move with the block: after the late change (1.5 s) the halves' x moved where the block's left / right
                # edge moved (settled before and after)
                before = [x for x in r["xs"] if 1.2 <= x[0] < 1.5 and x[3] is not None]
                after = [x for x in r["xs"] if x[0] >= 3.3 and x[3] is not None]
                if before and after:
                    b0, b1 = before[-1][3], after[-1][3]
                    if abs(b1[0] - b0[0]) > 1 and abs(after[-1][1] - before[-1][1]) < 1:
                        moved_with.append((key, "left", round(b1[0] - b0[0])))
                    if abs((b1[0] + b1[2]) - (b0[0] + b0[2])) > 1 and abs(after[-1][2] - before[-1][2]) < 1:
                        moved_with.append((key, "right", round(b1[0] + b1[2] - b0[0] - b0[2])))
    chk("C. Party mode centred, frame by frame (%d runs: 1-5 members x both shifts x a late join / leave / pet): no Wings half box on a "
        "drawn HUD / pet / row / tab part, any frame" % n, not bad, bad[:4])
    chk("C. ... settled: the block centred, the Wings >= %d px either side of it, the shift asked = the shift applied (Trail's and the "
        "rows')" % HUD_MARGIN, not settle, settle[:4])
    chk("C. ... a late join / pet that resizes the block moves the Wings with it", not moved_with, moved_with[:4])


# ------------------------------------------------------------------------------------- D: nbar18 the triggers a quarter smaller
def part_d():
    """nbar18 (the user, 11-24: "About a quarter smaller" - "Keep everything else, shrink just these by ~25%"): in the live Wings every
    L / R plate part (plate, held plate, reticle, bumper glyph and their offsets) and ZL / ZR chip part (box, glyph, held glow, the gap
    under the plate) is TRIG_K x nbar17's, aspect kept; the plate's inner edge 9 units past the diamond's outer slot and its top level
    with the top slot's (as nbar17); the chip under it, centred; nothing else resized (the slots, the diamond's spacing, MATCH_S); the
    solver's half box holds every plate / chip rect and is narrower than nbar17's 227."""
    L = boot()
    G = L.globals()
    NB = G.WowCompanion.nbar
    tick = ticker(L, 1 / 60)
    tick(30)
    G.COMBAT = True
    tick(40)
    f = NB.f
    T = NB.TOP
    if not (f and f.plate and NB.top.shown):
        chk("D. the Wings up in the fight", False)
        return
    K = TRIG_K
    bad = []

    def near(name, got, want, tol=1e-6):
        if got is None or abs(float(got) - want) > tol:
            bad.append("%s %s (want %.4f)" % (name, got, want))

    near("TRIG_K", T.TRIG_K, K)
    for i in (1, 2):
        p, c = f.plate[i], f.chip[i]
        sgn = -1 if i == 1 else 1
        hx, hy = float(NB.hx[i]), float(NB.hy[i])
        near("plate w", p.w, NBAR17["PLATE_W"] * K)
        near("plate h", p.h, NBAR17["PLATE_H"] * K)
        for nm, t in (("plate fill", p.bg), ("plate held", p.held)):
            dw, dh = drawn(t)
            near(nm + " w", dw, NBAR17["PLATE_W"] * K)
            near(nm + " h", dh, NBAR17["PLATE_H"] * K)
        near("reticle w", p.icon.w, NBAR17["PLATE_RET"] * K)
        near("reticle h", p.icon.h, NBAR17["PLATE_RET"] * K)
        near("reticle dy", p.icon.pts[1][5], NBAR17["PLATE_RET_DY"] * K)
        near("bumper glyph w", p.glyph.w, NBAR17["PLATE_GLYPH"] * K)
        near("bumper glyph h", p.glyph.h, NBAR17["PLATE_GLYPH"] * K)
        near("bumper glyph x", p.glyph.pts[1][4], -sgn * NBAR17["PLATE_GX"] * K)
        near("bumper glyph y", p.glyph.pts[1][5], NBAR17["PLATE_GY"] * K)
        near("chip w", c.w, NBAR17["CHIP_W"] * K)
        near("chip h", c.h, NBAR17["CHIP_H"] * K)
        near("chip glyph w", c.g.w, NBAR17["CHIP_G"] * K)
        near("chip glyph h", c.g.h, NBAR17["CHIP_G"] * K)
        near("chip glow", c.disc.w, NBAR17["CHIP_GLOW"] * K)
        # the anchors, from the half's diamond centre (bar units; y up)
        ppx, ppy = float(p.pts[1][4]) - hx, float(p.pts[1][5]) - hy
        cpx, cpy = float(c.pts[1][4]) - hx, float(c.pts[1][5]) - hy
        inner = abs(ppx) - float(p.w) / 2
        near("plate inner edge past the diamond's centre", inner, float(T.OFF_X) + float(NB.B) / 2 + 9)       # 79.5, as nbar17
        near("plate top", ppy + float(p.h) / 2, float(T.OFF_Y) + float(NB.B) / 2)                            # 48, as nbar17
        near("chip x under the plate", cpx, ppx)
        near("chip under the plate's centre", ppy - cpy, (NBAR17["PLATE_H"] + 2 * NBAR17["CHIP_GAP"] + NBAR17["CHIP_H"]) / 2 * K)
        # the plate clear of the diamond's outer slot, the chip (and its glow's box) clear of the plate's body
        if inner < float(T.OFF_X) + float(NB.B) / 2:
            bad.append("plate over the outer slot")
        if (ppy - float(p.h) / 2) < (cpy + float(c.h) / 2):
            bad.append("chip box over the plate")
    chk("D. nbar18: every plate / chip part 0.75 x nbar17's (plate 46.5 x 36.96, reticle 21.46 raised 1.19, bumper glyph 17.25 at 19.5 in "
        "/ 15.11 down, chip box and glyph 19.35, glow 37.8), the plate's inner edge and top where nbar17 had them, the chip 31.8 under it",
        not bad, bad[:6])
    mm = (float(T.PLATE_W) * float(T.MATCH_S) * MM_TOP, float(T.PLATE_H) * float(T.MATCH_S) * MM_TOP)
    chk("D. nbar18: the plate %.2f x %.2f mm on the top panel (0.75 x the band's 7.51 x 5.97 = %.2f x %.2f within 1 %%), the bumper glyph "
        "%.2f mm, the ZL / ZR glyph %.2f mm" % (mm + TOP_PLATE_MM + (float(T.PLATE_GLYPH) * 1.75 * MM_TOP, float(T.CHIP_G) * 1.75 * MM_TOP)),
        abs(mm[0] / TOP_PLATE_MM[0] - 1) <= 0.01 and abs(mm[1] / TOP_PLATE_MM[1] - 1) <= 0.01
        and abs(float(T.PLATE_GLYPH) * 1.75 * MM_TOP / TOP_BUMPER_MM - 1) <= 0.03
        and abs(float(T.CHIP_G) * 1.75 * MM_TOP / TOP_TRIG_MM - 1) <= 0.03)
    keep = (NB.B, NB.BC, NB.INSET, NB.FOCUS, NB.DX, T.OFF_X, T.OFF_Y, T.MATCH_S, T.CHORD, T.CHORD_GAP, T.CHORD_SEP, T.PLATE_GAP,
            T.IN_S, T.OUT_S, T.HOLD_IN, T.BAND_UP_MS)
    chk("D. nbar18: everything else as nbar17 (slots 46 / 44, inset, focus, DX 160, the diamond 47.5 / 25, MATCH_S 1.75, the chord row, "
        "the slide's times)", tuple(float(x) for x in keep) == (46, 44, 3.5, 30, 160, 47.5, 25, 1.75, 16, 3, 4, 6, 0.26, 0.20, 0.08, 240),
        keep)
    hw, hh, ax, ay = L.eval("WowCompanion.nbar.halfBox")()
    g = NB.outerGeo()
    gx, py, cy = float(g.x), float(g.plateY), float(g.chipY)
    hold = (gx + float(T.PLATE_W) / 2 <= ax + 1e-9 and gx + float(T.CHIP_GLOW) / 2 <= ax + 1e-9
            and py + float(T.PLATE_H) / 2 <= ay + 1e-9 and -(cy - float(T.CHIP_GLOW) / 2) <= hh - ay + 1e-9)
    chk("D. nbar18: the solver's half box (%.2f x %.2f) holds every plate / chip rect and is narrower than nbar17's 227 x 135 (the same "
        "height)" % (hw, hh), hold and hw < 227 and hh == 135, (hw, hh, ax, ay))


def main():
    part_a()
    part_b()
    part_c()
    part_d()
    print("")
    print("%d passed, %d failed" % (passes, len(fails)))
    sys.exit(1 if fails else 0)


if __name__ == "__main__":
    main()
