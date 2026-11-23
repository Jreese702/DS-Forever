"""nbar11 THE TOP-SCREEN COMBAT BAR NEVER OVER THE HUD (the user, 2026-11-22, after trying In fights = Top screen: "it overlaps with my
hud"), off-device: the REAL Trail.lua HUD (WC.prd, under test_prd.py's stub game with an anchor resolver: SetPoint / SetAllPoints /
SetSize / SetScale / ClearAllPoints feed every frame's real rect) and the REAL ActionBar.lua Wings (WC.nbar, under test_nintendo_bar.py's
stub), across the HUD's states and the screen's.

    python -B tools/test_wings_geometry.py [--shots DIR]

Checks:
  1 THE BLOCK IS THE HUD: Trail's P.lay.block (a pure function of the HUD's fields, nbar11) holds every HUD frame the resolver lays - the
    bars (P.f with the combo pips, the pet strip under, Party mode's room), the pet strip beside the HP bar, the aura host under the bars
    or above them - for pet none / under / beside x auras under / above x pips off / on x Party rows 0 / 1 / 2 (with and without the
    on-screen lift) x HUD scale 70 / 100 / 160 x width 180 / 280 / 400 x height -470 / -350 / -170, at UIParent heights 768 / 900 / 1080
    / 1200 (the UI scale: the px per UI unit kk).
  2 THE WINGS CLEAR IT: NB.fightSolve on the inputs NB.topInputs builds (the block unioned with the live frames, the compass, the target
    bar, the left party column, the minimap, the reserved cast bar / Cooldown Manager) at the bar's scale s = nbar14's MATCH_S (1.75 px
    per unit: the band's physical size, whatever the UI scale) x the piece's size: both halves inside the screen, off the tap pad's band,
    at least HUD_MARGIN (60 px) from every RESOLVED HUD frame (not the block), and never the case-5 stock fallback (nbar14: the solver
    MOVES BEFORE IT SHRINKS - beside at k 1 with GAP 60, pushed outward to 228, raised with the push, joined above at 1; only then
    beside / raised at 0.9 / 0.8; the grid at 0.8 in the lower 60 %, then anywhere; never under k 0.8); the other axes (the stock
    scale - no longer in s, so one value -, the piece's size 70 / 100 / 150, the target bar / compass / party column on / off, the
    reserved frames on / off) over a spread of HUD states. 2c nbar14 THE BAND'S SIZE: a square slot 46 x MATCH_S px = 5.54 mm (+-3 %)
    on the Thor's top panel (0.06918 mm / px); the default HUD at the Thor's UI (UIParent 817) solves beside at k 1, ~6 mm clear.
  2b WHAT IS DRAWN (the 11-22 review: the focus glow reached 15 units past the solved box): every slot's focus glow, a held slot's
    2-unit drop, the glyph badges, the L / R plate with its bumper glyph (nbar12), the ZL / ZR chip under it with its gold disc, Party
    mode's chord row and the swing lines - computed from the bar's constants and its placement code (NB.outerGeo), not NB.halfBox -
    inside the solved boxes and >= MARGIN from every resolved HUD frame.
  1d nbar15 OUR HUD BARS OFF (the user's pick 11-23, "Centre the rows"): in every Party state Hub's rows and Select tab alone, centred
    on the screen as NB.topOurs hands them to the solver, the Wings either side of them, >= HUD_MARGIN from every row and the tab; and
    (6) Trail prd23's P.partyGeo carries the shift with its bars off (Party mode read live), none of its frames moved.
  1b nbar12 PARTY MODE'S ROWS (the Wings show in Party mode's fights now): Hub's grid under the HUD (its rows from Trail's P.partyGeo at
    Hub's own PF constants, two columns, with the rings' rim) and its Select tab at the grid's left edge join the HUD block as NB.topOurs
    reads them; the Wings (boxes and art) >= MARGIN from every row and the tab, never the case-5 fallback, in every Party state.
  3 THE SLIDE NEVER CROSSES IT: where the solve says `lane`, the slide (from wholly below the screen's bottom edge up to the spot on
    nbar14's NB.slideIn, IN_C; a spot under k 1 easing its scale from k 1 on the same progress; sampled every 1/60 s) rises
    monotonically and never touches a HUD frame. 3b nbar14 THE SEAM'S SPEED: the default HUD's slide starts at the band's exit speed
    (Motion.TOP_SLIDE_UP 240 ms on (0.4, 0, 1, 1), its ~242 bottom px x 0.06055 mm) within 10 %, in the same 0.26 s.
  4 THE INPUTS: NB.topOurs unions P.lay.block with the live frames read at ANY alpha (the HUD faded out of combat still counts).
  5 THE FIGHT'S EDGE AT ONCE: a state sent at t = 0, then PLAYER_REGEN_DISABLED at t = 0.1 (before the lockdown reads true): the next
    frame's state goes out at t = 0.1 with c = true (not at the 0.5 s window's end).
--shots draws schematic PNGs of the top screen (the resolved HUD frames, the block, the Wings, the slide's path) from the computed rects.
Needs lupa (PIL for --shots). Exit code 1 on any failure."""
import itertools
import json
import math
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import test_compass as tc  # noqa: E402
import test_prd as tp  # noqa: E402
from lupa import lua51 as lupa  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ADDONS = os.path.join(ROOT, "companion", "app", "src", "main", "assets", "companion", "addons")
SAFETY = os.path.join(ADDONS, "WowCompanion", "Safety.lua")
HUB_SRC = open(os.path.join(ADDONS, "WowCompanionHub", "Hub.lua"), encoding="utf-8").read()
AB = os.path.join(ADDONS, "WowCompanion", "ActionBar.lua")
NBT = open(os.path.join(ROOT, "tools", "test_nintendo_bar.py"), encoding="utf-8").read()
NB_STUB = re.search(r"\nSTUB = r'''(.*?)'''", NBT, re.S).group(1)
NB_SCENE = re.search(r"\nSCENE = r'''(.*?)'''", NBT, re.S).group(1)
SCRATCH = "C:/Users/Jrees/AppData/Local/Temp/claude/6c5148a90427b4bc/789b5138-341f-4ef8-8102-099551ca3a8a/scratchpad"
SHOTS = SCRATCH + "/topbar/build"
MARGIN = 24
HUD_MARGIN = 60                         # nbar14: the HUD block's own margin
MM_TOP = 0.06918                        # the Thor's top panel: 6.0", 1920 x 1080 (367.2 ppi)
MM_BOTTOM = 0.06055                     # its bottom panel: 3.92", 1240 x 1080 (419.5 ppi)
BAND_SQUARE_MM = 91.5 * MM_BOTTOM       # the band's square slot at rest (Diamonds: 200 px tall, its scale 200 / 354)
BAND = (718, 22, 484, 158)

# ---------------------------------------------------------------------------------------------------------------- the anchor resolver
# every point a region holds (the compass stub keeps the last only), SetAllPoints as its two points, and each region's rect in UIParent
# units (origin bottom-left) from its points, its size and the scales up its parent chain
RESOLVER = r'''
local Region = getmetatable(CreateFrame("Frame"))
function Region:SetPoint(...) self.point = { ... }; self.anch = self.anch or {}; self.anch[#self.anch + 1] = { ... } end
function Region:ClearAllPoints() self.point = nil; self.anch = nil; self.all = nil end
function Region:SetAllPoints(t)
    self.all = t
    local rel = t or self.parent
    self.anch = { { "TOPLEFT", rel, "TOPLEFT", 0, 0 }, { "BOTTOMRIGHT", rel, "BOTTOMRIGHT", 0, 0 } }
end
function Region:SetSize(w, h) self.w, self.h = w, h end
function Region:SetWidth(w) self.w = w end
function Region:SetHeight(h) self.h = h end
function Region:SetScale(s) self.scale = s end
function Region:GetHeight() if rawequal(self, UIParent) then return self.hh end; return self.h or 0 end
function Region:GetWidth() if rawequal(self, UIParent) then return self.ww end; return self.w or 0 end
local FR = { TOPLEFT = { 0, 1 }, TOP = { 0.5, 1 }, TOPRIGHT = { 1, 1 }, LEFT = { 0, 0.5 }, CENTER = { 0.5, 0.5 }, RIGHT = { 1, 0.5 },
             BOTTOMLEFT = { 0, 0 }, BOTTOM = { 0.5, 0 }, BOTTOMRIGHT = { 1, 0 } }
function ESC(f)
    local s, p = 1, f
    while p ~= nil and not rawequal(p, UIParent) do s = s * (p.scale or 1); p = p.parent end
    return s
end
local function num(v) if type(v) == "number" then return v end; return 0 end
-- l, b, r, t of [f] in UIParent units (nil = not laid out)
function RR(f, depth)
    depth = (depth or 0) + 1
    if depth > 60 then error("anchor loop") end
    if f == nil or rawequal(f, UIParent) then return 0, 0, UIParent.ww, UIParent.hh end
    local pts = f.anch
    if not pts or #pts == 0 then return nil end
    local es = ESC(f)
    local xs, ys = {}, {}
    for _, a in ipairs(pts) do
        local point, rel, relPoint, x, y = a[1], nil, nil, 0, 0
        local i = 2
        if type(a[2]) == "table" then rel = a[2]; i = 3 elseif a[2] == nil and #a >= 2 then i = 3 end
        if type(a[i]) == "string" then relPoint = a[i]; i = i + 1 end
        x, y = num(a[i]), num(a[i + 1])
        rel = rel or f.parent
        relPoint = relPoint or point
        local rl, rb, rr, rt = RR(rel, depth)
        if rl == nil then return nil end
        local fr, frr = FR[point], FR[relPoint]
        local X = rl + (rr - rl) * frr[1] + x * es
        local Y = rb + (rt - rb) * frr[2] + y * es
        xs[#xs + 1] = { fr[1], X }
        ys[#ys + 1] = { fr[2], Y }
    end
    local function axis(list, size)
        local a, b = list[1], nil
        for _, c in ipairs(list) do if c[1] ~= a[1] then b = c end end
        if b then
            local span = (b[2] - a[2]) / (b[1] - a[1])
            local lo = a[2] - a[1] * span
            return lo, lo + span
        end
        local lo = a[2] - a[1] * size
        return lo, lo + size
    end
    local l, r = axis(xs, (f.w or 0) * es)
    local b, t = axis(ys, (f.h or 0) * es)
    return l, b, r, t
end
-- the client's aura container (prd20: the HUD's aura host is ours, the container inside it): a frame whose own methods all answer
CustomAuraContainerGroupDefaultOptions = {}
do
    local CF = CreateFrame
    function CreateFrame(kind, name, parent, tpl)
        local f = CF(kind, name, parent, tpl)
        if kind == "AuraContainer" then
            setmetatable(f, { __index = function(t, k) local v = Region[k]; if v ~= nil then return v end; return function() return true end end })
        end
        return f
    end
end
-- the rect in 1080-line px from the top-left
function PXR(f)
    local l, b, r, t = RR(f)
    if l == nil then return nil end
    local k = 1080 / UIParent.hh
    return { x = l * k, y = (UIParent.hh - t) * k, w = (r - l) * k, h = (t - b) * k }
end
'''


def boot_trail(ui_h):
    L = tc.lupa.LuaRuntime()
    L.execute(tc.STUB)
    L.execute(tp.GAMEPLUS)
    L.execute(RESOLVER)
    L.execute("UIParent.hh = %r; UIParent.ww = %r" % (float(ui_h), ui_h * 16 / 9))
    L.execute(open(tc.TRAIL, encoding="utf-8").read().replace("local ADDON = ...", "local ADDON = 'WowCompanion'", 1))
    G = L.globals()
    frames = [G.FRAMES[i] for i in range(1, len(G.FRAMES) + 1)]
    ev = [f for f in frames if f.events and f.events.UNIT_AURA][0]
    st = {"L": L, "G": G, "ev": ev}

    def run(secs, dt=1 / 30):
        for _ in range(int(round(secs / dt))):
            G.GAME.t = G.GAME.t + dt
            ev.scripts.OnUpdate(ev, dt)
    st["run"] = run
    G.GAME.t = 1000.0
    ev.scripts.OnEvent(ev, "PLAYER_ENTERING_WORLD", None)
    G.WowCompanion.npWant = L.table_from({"hud": True, "pet": True})
    run(0.5)
    ev.scripts.OnEvent(ev, "PLAYER_REGEN_DISABLED", None)
    G.ME.fight = True
    run(0.5)
    return st


def hud_state(st, pet, aura_up, pips, party, lift, scale, width, y):
    """lay the real HUD in this state; -> (the resolved frames {name: rect px}, the block px, the entry)"""
    L, G = st["L"], st["G"]
    P = G.WowCompanion.prd
    dy = -(y - (-350))                              # the entry's dy (1080-line px down) for the bars' offset y (P.y at 100 %)
    e = L.table_from({"dx": 0, "dy": dy, "s": scale})
    G.WowCompanionDB = G.WowCompanionDB or L.table()
    lay = G.WowCompanionDB.lay or L.table()
    G.WowCompanionDB.lay = lay
    lay["Yes-Lander"] = L.table_from({"hud": e})
    P.lay.apply()
    P.wd = width
    P.resize()
    if pet == "none":
        G.PET = None
    else:
        G.PET = L.table_from({"hp": 500, "hpMax": 800, "pw": 40, "pwMax": 100, "ptype": 2, "tok": "FOCUS", "name": "Wolf", "icon": 132203,
                              "hunter": True, "happy": 3})
    st["run"](0.2)
    P.petSide = "beside" if pet == "beside" else "under"
    if P.petL is not None:
        if pet == "none":
            P.petL.Hide(P.petL)
        else:
            P.petL.Show(P.petL)
    if pips:
        P.cp.Show(P.cp)
    else:
        P.cp.Hide(P.cp)
    room_px = (party * 76 - 12 + 8) if party > 0 else 0              # Hub's grid rows (pitch 76, the last row's ring) under the bars
    one = L.eval("function(s) return WowCompanion.compass.u(1) * s end")(scale / 100)
    P.partyRoom(room_px * one)
    P.petLayout(P.dropFor(), True)
    P.auraUp = aura_up
    if P.box is not None:
        P.box.y = None
        P.boxDraw(True)
    P.lift = P.liftFor(True) if (lift and party > 0) else 0
    P.place()
    fr = {}
    PXR = L.eval("PXR")
    for name, f in (("bars", P.f), ("pet", P.petL if pet != "none" else None), ("auras", P.box.host if P.box is not None else None)):
        if f is not None:
            r = PXR(f)
            if r is not None:
                fr[name] = (r.x, r.y, r.w, r.h)
    if pet == "under" or party > 0 or pips:
        pass                                          # (inside P.f's height: petDrop)
    b = P.lay.block(e)
    return fr, (b.x, b.y, b.w, b.h), e


def inside(a, b, tol=0.51):
    return a[0] >= b[0] - tol and a[1] >= b[1] - tol and a[0] + a[2] <= b[0] + b[2] + tol and a[1] + a[3] <= b[1] + b[3] + tol


def over(a, b, m=0):
    return a[0] - m < b[0] + b[2] and b[0] < a[0] + a[2] + m and a[1] - m < b[1] + b[3] and b[1] < a[1] + a[3] + m


def union(a, b):
    if a is None:
        return b
    x0, y0 = min(a[0], b[0]), min(a[1], b[1])
    x1, y1 = max(a[0] + a[2], b[0] + b[2]), max(a[1] + a[3], b[1] + b[3])
    return (x0, y0, x1 - x0, y1 - y0)


def boot_nb():
    L = lupa.LuaRuntime(unpack_returned_tuples=True)
    L.execute(NB_STUB)
    L.execute(NB_SCENE)
    L.execute("PADMAP_ON()")
    load = L.eval('function(src, name, a) local f, e = loadstring(src, name); if not f then error(e) end; return f(a) end')
    load(open(SAFETY, encoding="utf-8").read(), "@Safety.lua", "WowCompanion")
    load(open(AB, encoding="utf-8").read(), "@ActionBar.lua", "WowCompanion")
    return L


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


def todict(v):
    if lupa.lua_type(v) == "table":
        return {k: todict(x) for k, x in v.items()}
    return v


def R(r, id_=None):
    d = {"x": r[0], "y": r[1], "w": r[2], "h": r[3]}
    if id_:
        d["id"] = id_
    return d


fails = []
passes = 0
fail_lines = {}


def chk(name, ok, got=''):
    global passes
    print(("PASS " if ok else "FAIL ") + name + ((" -> " + str(got)) if got != '' and not ok else ''))
    if ok:
        passes += 1
    else:
        fails.append(name)


COMPASS = (530, 4, 860, 76)
TARGET = (790, 86, 372, 94)
PARTYCOL = (24, 250, 300, 256)
MINIMAP = (1590, 0, 330, 390)
CASTBAR = (810, 740, 300, 26)          # the game's cast bar's default spot over the action bar (reserved: always)
COOLDOWNS = (735, 640, 450, 52)        # the Cooldown Manager's essential row (reserved once seen)


def solve_case(NBL, hud_frames, block, s, target=True, compass=True, partycol=False, reserved=True):
    hud = block
    for r in hud_frames.values():
        hud = union(hud, r)
    ours = [R(hud, "hud")]
    if compass:
        ours.append(R(COMPASS, "compass"))
    if target:
        ours.append(R(TARGET, "target"))
    if partycol:
        ours.append(R(PARTYCOL, "party"))
    game = [R(MINIMAP, "g.MinimapCluster")]
    if reserved:
        game += [R(CASTBAR, "g.PlayerCastingBarFrame"), R(COOLDOWNS, "g.EssentialCooldownViewer")]
    inp = {"W": 1920, "H": 1080, "s": s, "size": s / (NBL.globals().WowCompanion.nbar.TOP.MATCH_S or 1.75), "hud": R(hud), "ours": ours, "game": game, "band": R(BAND), "stock": {"x": 960, "y": 984}}
    r = todict(NBL.eval("WowCompanion.nbar.fightSolve")(tbl(NBL, inp)))
    return r, ours + game


def problems(r, hud_frames, obst, s):
    out = []
    rects = [(r["rects"][i]["x"], r["rects"][i]["y"], r["rects"][i]["w"], r["rects"][i]["h"]) for i in (1, 2)]
    if r["case"] == 5:
        out.append("the case-5 stock fallback")
    for x in rects:
        if x[0] < -0.01 or x[1] < -0.01 or x[0] + x[2] > 1920.01 or x[1] + x[3] > 1080.01:
            out.append("off the screen %s" % (x,))
        if over(x, BAND):
            out.append("over the tap pad band %s" % (x,))
        for n, h in hud_frames.items():
            if over(x, h, HUD_MARGIN - 1e-6):
                out.append("within %d px of the HUD's %s %s / %s" % (HUD_MARGIN, n, x, h))
    if (r.get("k") or 1) < 0.8 / max(1.0, s / 1.75) - 1e-9:      # (MIN_K of the size step; one over 100 %: of the band's match size)
        out.append("shrunk under 0.8 of the band's size (k %s, s %s)" % (r.get("k"), s))
    return out


def art_rects(NBL, r):
    """the 11-22 review: what each half DRAWS, in px, from the bar's own constants and NB.anchor / NB.button / NB.topDeco's placement
    (not NB.halfBox): every slot's focus glow (slot + FOCUS, centred; a held slot 2 units lower), its glyph badge, nbar12's L / R plate
    with its bumper glyph, the ZL / ZR chip under it with its held gold disc (CHIP_H + 8), Party mode's chord row, the two swing lines
    under the faces' diamond. -> {1: [rects], 2: [rects]} (top-left origin)"""
    NB = NBL.globals().WowCompanion.nbar
    T = NB.TOP
    s = r["s_used"]
    offx, offy = T.OFF_X, T.OFF_Y                               # nbar14: the Wings' flattened diamond
    d2 = offy + NB.B / 2
    ga = NB.GLYPH_AT
    out = {}
    for h, c in ((1, r["l"]), (2, r["r"])):
        sz = NB.B if h == 1 else NB.BC
        rects = []
        for ax, ay in ((-1, 0), (0, 1), (1, 0), (0, -1)):
            for drop in (0, 2):
                cx, cy = c["x"] + ax * offx * s, c["y"] - ay * offy * s + drop * s
                g = (sz + NB.FOCUS) / 2 * s
                rects.append((cx - g, cy - g, 2 * g, 2 * g))
                gy = ga[2] if h == 1 else ga[3]
                b = NB.GLYPH / 2 * s
                rects.append((cx + ga[1] * s - b, cy - gy * s - b, 2 * b, 2 * b))
        sgn = -1 if h == 1 else 1
        # nbar12 the outer column (NB.topDeco's places, NB.outerGeo): the plate at the top, the chip under it, the chord row under that
        g = NB.outerGeo()
        ox = c["x"] + sgn * g.x * s
        pcy = c["y"] - g.plateY * s
        rects.append((ox - T.PLATE_W / 2 * s, pcy - T.PLATE_H / 2 * s, T.PLATE_W * s, T.PLATE_H * s))
        gb = NB.GLYPH / 2 * s
        gcx = ox + (-sgn) * T.PLATE_GX * s                      # (toward the bar's middle)
        gcy = pcy - T.PLATE_GY * s
        rects.append((gcx - gb, gcy - gb, 2 * gb, 2 * gb))
        ccx, ccy = ox, c["y"] - g.chipY * s
        hw = max(T.CHIP_W, T.CHIP_H + 8) / 2 * s
        hh = max(T.CHIP_H, T.CHIP_H + 8) / 2 * s
        rects.append((ccx - hw, ccy - hh, 2 * hw, 2 * hh))
        for j in (1, 2, 3):
            tx, ty = ox + (j - 2) * g.chordDX * s, c["y"] - g.chordY * s
            rb = T.CHORD / 2 * s
            rects.append((tx - rb, ty - rb, 2 * rb, 2 * rb))
        if h == 2:
            dw = (2 * offx + NB.B) * s
            top = c["y"] + (d2 + T.SW_GAP) * s
            rects.append((c["x"] - dw / 2, top, dw, (2 * T.SW_H + T.SW_SEP) * s))
        out[h] = rects
    return out


def art_problems(NBL, r, hud_frames):
    """every drawn rect inside its half's solved box, so the box's MARGIN from the HUD holds for the art; and the art itself never
    within MARGIN of a resolved HUD frame (cases 1-4)"""
    if r["case"] == 5:
        return []
    out = []
    art = art_rects(NBL, r)
    for h in (1, 2):
        box = (r["rects"][h]["x"], r["rects"][h]["y"], r["rects"][h]["w"], r["rects"][h]["h"])
        for x in art[h]:
            if not inside(x, box, 0.01):
                out.append("half %d draws %s outside its box %s" % (h, tuple(round(v, 1) for v in x), tuple(round(v, 1) for v in box)))
            for n, hf in hud_frames.items():
                if over(x, hf, HUD_MARGIN - 1e-6):
                    out.append("half %d's art %s within %d px of the HUD's %s" % (h, tuple(round(v, 1) for v in x), HUD_MARGIN, n))
    return out


def slide_problems(NBL, r, hud_frames):
    """the slide's path, sampled every 1/60 s over IN_S: monotonic, never on a HUD frame"""
    if not r["lane"] or r["case"] == 5:                 # (case 5 is check 2's failure already)
        return []
    out = []
    ease = NBL.eval("WowCompanion.nbar.slideIn")                 # nbar14: IN_C
    hb = NBL.eval("WowCompanion.nbar.halfBox")()
    s = r["s_used"]
    kk_ = r.get("k") or 1
    ay = hb[3] * s / kk_                                         # (the drop at the k 1 size: a shrunk spot starts at k 1)
    to_y = min(r["l"]["y"], r["r"]["y"])
    d = 1080 + ay - to_y + 2
    prev = None
    n = int(math.ceil(0.26 * 60))
    for i in range(n + 1):
        t = min(1.0, i / 60 / 0.26)
        e = ease(t)
        off = d * (1 - e)
        m = (1 + (kk_ - 1) * e) / kk_                            # nbar14: this frame's scale over the spot's
        if prev is not None and off > prev + 1e-9:
            out.append("not monotonic at %d" % i)
        prev = off
        for k, cen in ((1, r["l"]), (2, r["r"])):
            rr = r["rects"][k]
            # the half's box at this frame's scale round its diamond's centre (the box's own offsets scaled)
            bx = cen["x"] + (rr["x"] - cen["x"]) * m
            by = cen["y"] + (rr["y"] - cen["y"]) * m
            x = (bx, by + off, rr["w"] * m, rr["h"] * m)
            for nme, h in hud_frames.items():
                if over(x, h):
                    out.append("frame %d: half %d on the HUD's %s" % (i, k, nme))
    if r["rects"][1]["y"] + d < 1080 or r["rects"][2]["y"] + d < 1080:
        out.append("does not start wholly below the edge")
    return out


UI_HS = (768, 817, 900, 1080, 1200)      # nbar14: the Thor's own UI (UIParent 817, scale 0.94) too


def note_k(NBL, r, fr, block, s, kw, ks, bad_order, key):
    """nbar14 MOVE BEFORE SHRINKING: count (case, k); a solve under k 1 (or the grid) only when nothing at k 1 fits - the same inputs
    with the shrink and the grid taken away (KS { 1 }, KG {}) must fall through to the case-5 fallback"""
    k = r.get("k") or 1
    ks[(r["case"], round(k, 2))] = ks.get((r["case"], round(k, 2)), 0) + 1
    if k < 1 - 1e-9 or r["case"] == 4:
        T = NBL.globals().WowCompanion.nbar.TOP
        old_ks, old_kg, old_mk = T.KS, T.KG, T.MIN_K
        T.KS, T.KG, T.MIN_K = tbl(NBL, [1]), tbl(NBL, []), 0
        try:
            r1, _ = solve_case(NBL, fr, block, s, **kw)
        finally:
            T.KS, T.KG, T.MIN_K = old_ks, old_kg, old_mk
        if r1["case"] != 5:
            bad_order.append((key, r["case"], k, "k 1 fits as case %s" % r1["case"]))


def hub_pf():
    """Hub's party grid constants (hub42's PF), read from Hub.lua itself"""
    m = re.search(r"local PF = \{(.*?)cells = \{\} \}", HUB_SRC, re.S)
    out = {}
    for k, v in re.findall(r"\b([A-Z_]+) = ([0-9.]+)\b", m.group(1)):
        out.setdefault(k, float(v))
    return out


def hub_rows(st, party, ui_h, lift=False):
    """nbar12 1b: Hub's grid under the HUD in Party mode (PF.place at P.partyGeo: TOPLEFT at the HP bar's left - BAR_X, the second
    column COL_W + COL_GAP to the right, PITCH a row; each row BAR_X + w + 30 by NAME_H + HP + GAP + MP 1080-line px) and its Select tab
    (PF.tabStep: TOPRIGHT TAB_GAP left of the first column's glyph slot, TAB_W wide, the rows' height) - in px from the top-left.
    party = the rows (each with both columns: the widest grid)"""
    pf = hub_pf()
    # (Party mode on as PadMap says it: P.partyGeo follows the on-screen guard's lift the block is laid with)
    st["L"].execute("WowCompanion.padMap = %s" % ("{ party = { on = function() return true end } }" if lift else "nil"))
    g = st["G"].WowCompanion.prd.partyGeo()
    st["L"].execute("WowCompanion.padMap = nil")
    k = ui_h / 1080.0                                       # Hub's K: UI units per 1080-line px
    ww = ui_h * 16 / 9
    gx, gy = g.x / k, g.y / k                               # px from UIParent's CENTER, y up
    w = min(pf["W_BAR"], pf["COL_W"] - pf["BAR_X"] - 30)
    rw, rh = pf["BAR_X"] + w + 30, pf["NAME_H"] + pf["HP"] + pf["GAP"] + pf["MP"]
    rows = []
    for r in range(party):
        for side in (0, 1):
            dx = side * (pf["COL_W"] + pf["COL_GAP"])
            x = ww / k / 2 + gx - pf["BAR_X"] + dx
            y = 540 - gy + r * pf["PITCH"]
            rows.append((x, y, rw, rh))
    th = party * pf["PITCH"] - (pf["PITCH"] - pf["HP"] - pf["GAP"] - pf["MP"]) + 4
    tr = ww / k / 2 + gx - (pf["BAR_X"] + pf["TAB_GAP"])
    tab = (tr - pf["TAB_W"], 540 - gy + (pf["NAME_H"] - 4), pf["TAB_W"], th)
    return rows, tab


def main():
    shots = "--shots" in sys.argv or os.environ.get("WINGS_SHOTS")
    out_dir = sys.argv[sys.argv.index("--shots") + 1] if "--shots" in sys.argv and len(sys.argv) > sys.argv.index("--shots") + 1 else SHOTS
    NBL = boot_nb()
    NBG = NBL.globals()
    NB = NBG.WowCompanion.nbar
    hb = NBL.eval("WowCompanion.nbar.halfBox")()
    # ------------------------------------------------------------------------------------------------ 1-3 the matrix
    bad_block, bad_solve, bad_slide, bad_art, bad_hub, bad_order = [], [], [], [], [], []
    ks = {}
    bad_centre, n_centre, n_centre_w, keep_c = [], 0, 0, {}
    bad_off, n_off, n_off_w = [], 0, 0
    n_hub = 0
    n_states = n_solves = 0
    cases = {}
    keep = {}
    MATCH = NB.TOP.MATCH_S
    for ui_h in UI_HS:
        st = boot_trail(ui_h)
        kk = 1080 / ui_h
        matrix = itertools.product(("none", "under", "beside"), (False, True), (False, True), (0, 1, 2), (False, True),
                                   (70, 100, 160), (180, 280, 400), (-470, -350, -170))
        for pet, up, pips, party, lift, sc, wd, y in matrix:
            if lift and party == 0:
                continue
            fr, block, e = hud_state(st, pet, up, pips, party, lift, sc, wd, y)
            n_states += 1
            for n, r in fr.items():
                if not inside(r, block):
                    bad_block.append((ui_h, pet, up, pips, party, lift, sc, wd, y, n, r, block))
            # the default axes: the piece's size 100 % (nbar14: s = MATCH_S, the UI scale not in it), target / compass on, reserved on
            s = MATCH * 1.0
            r, obst = solve_case(NBL, fr, block, s)
            r["s_used"] = s * (r.get("k") or 1)
            n_solves += 1
            cases[r["case"]] = cases.get(r["case"], 0) + 1
            note_k(NBL, r, fr, block, s, {}, ks, bad_order, (ui_h, pet, up, pips, party, lift, sc, wd, y))
            p = problems(r, fr, obst, s)
            if p:
                bad_solve.append(((ui_h, pet, up, pips, party, lift, sc, wd, y), p[:2]))
            ap = art_problems(NBL, r, fr)
            if ap:
                bad_art.append(((ui_h, pet, up, pips, party, lift, sc, wd, y), ap[:2]))
            sp = slide_problems(NBL, r, fr)
            if sp:
                bad_slide.append(((ui_h, pet, up, pips, party, lift, sc, wd, y), sp[:2]))
            # nbar12 1b: Party mode's rows and tab with the Wings (NB.topOurs unions them, the rows with HUB_RIM, into the HUD block)
            if party > 0:
                rows, tab = hub_rows(st, party, ui_h, lift)
                rim = NB.TOP.HUB_RIM
                fr_p = dict(fr)
                for i, rr in enumerate(rows):
                    fr_p["hubrow%d" % i] = (rr[0] - rim, rr[1] - rim, rr[2] + 2 * rim, rr[3] + 2 * rim)
                fr_p["hubtab"] = tab
                rp, obst_p = solve_case(NBL, fr_p, block, s)
                rp["s_used"] = s * (rp.get("k") or 1)
                n_hub += 1
                raw = dict(fr)
                for i, rr in enumerate(rows):
                    raw["hubrow%d" % i] = rr
                raw["hubtab"] = tab
                pp = problems(rp, raw, obst_p, s) + art_problems(NBL, rp, raw)
                if pp:
                    bad_hub.append(((ui_h, pet, up, pips, party, lift, sc, wd, y), pp[:2]))
                # nbar14 1c PARTY MODE CENTRED: the whole block (Trail's HUD + Hub's rows and tab) shifted to the screen's centre (what
                # NB.topOurs hands the solver in Party mode's Top fight, NB.centreDx), the Wings either side of it, clear of every row
                hud_u = block
                for rr in fr_p.values():
                    hud_u = union(hud_u, rr)
                dxc = 960 - (hud_u[0] + hud_u[2] / 2)
                mv = lambda q: (q[0] + dxc, q[1], q[2], q[3])  # noqa: E731
                fr_c = {n: mv(q) for n, q in fr_p.items()}
                raw_c = {n: mv(q) for n, q in raw.items()}
                rc, obst_c = solve_case(NBL, fr_c, mv(block), s)
                rc["s_used"] = s * (rc.get("k") or 1)
                n_centre += 1
                hc = mv(hud_u)
                pc_ = problems(rc, raw_c, obst_c, s) + art_problems(NBL, rc, raw_c)
                if abs(hc[0] + hc[2] / 2 - 960) > 0.01:
                    pc_.append("the block not centred")
                if rc["form"] == "wings" and not (rc["rects"][1]["x"] + rc["rects"][1]["w"] <= hc[0] - HUD_MARGIN + 0.01
                                                  and rc["rects"][2]["x"] >= hc[0] + hc[2] + HUD_MARGIN - 0.01):
                    pc_.append("the Wings not either side of the centred block")
                if rc["form"] == "wings":
                    n_centre_w += 1
                if pc_:
                    bad_centre.append(((ui_h, pet, up, pips, party, lift, sc, wd, y), pc_[:2]))
                # nbar15 1d OUR HUD BARS OFF (the user's pick 11-23, "Centre the rows"): Hub's rows and tab alone (NB.topOurs' "partyhud",
                # its rects less the shift they were drawn with) shifted to the screen's centre and handed to the solver as the HUD block
                # (NB.centreDx: P.partyGeo carries it with Trail's bars off, prd23) - the Wings either side of them, clear of every row
                ro_u = None
                for n_, q in fr_p.items():
                    if n_.startswith("hub"):
                        ro_u = union(ro_u, q)
                dxr = math.floor(960 - (ro_u[0] + ro_u[2] / 2) + 0.5)
                mvr = lambda q: (q[0] + dxr, q[1], q[2], q[3])  # noqa: E731
                ro_c = {n_: mvr(q) for n_, q in fr_p.items() if n_.startswith("hub")}
                raw_r = {n_: mvr(q) for n_, q in raw.items() if n_.startswith("hub")}
                rr_, obst_r = solve_case(NBL, ro_c, mvr(ro_u), s)
                rr_["s_used"] = s * (rr_.get("k") or 1)
                n_off += 1
                hr = mvr(ro_u)
                po_ = problems(rr_, raw_r, obst_r, s) + art_problems(NBL, rr_, raw_r)
                if abs(hr[0] + hr[2] / 2 - 960) > 0.5:
                    po_.append("the rows not centred")
                if rr_["form"] == "wings" and not (rr_["rects"][1]["x"] + rr_["rects"][1]["w"] <= hr[0] - HUD_MARGIN + 0.01
                                                   and rr_["rects"][2]["x"] >= hr[0] + hr[2] + HUD_MARGIN - 0.01):
                    po_.append("the Wings not either side of the centred rows")
                if rr_["form"] == "wings":
                    n_off_w += 1
                if po_:
                    bad_off.append(((ui_h, pet, up, pips, party, lift, sc, wd, y), po_[:2]))
                if (ui_h, pet, up, pips, party, lift, sc, wd, y) in CENTRE_SHOT:
                    keep_c[(ui_h, pet, up, pips, party, lift, sc, wd, y)] = (fr_p, block, rp, fr_c, mv(block), rc, dxc)
            key = (ui_h, pet, up, pips, party, lift, sc, wd, y)
            if key in SHOT_STATES:
                keep[key] = (fr, block, r)
        # the other axes over a spread of HUD states (this UI height)
        spread = [("none", False, False, 0, False, 100, 280, -350), ("beside", False, True, 0, False, 160, 400, -170),
                  ("under", True, True, 2, True, 160, 400, -470), ("beside", True, False, 1, False, 70, 180, -470),
                  ("under", False, False, 0, False, 160, 280, -470), ("none", True, True, 2, False, 100, 400, -170)]
        for hs in spread:
            fr, block, e = hud_state(st, *hs)
            for stock, size, tg, cp, pc, res in itertools.product((1.0,), (0.7, 1.0, 1.5), (False, True), (False, True),
                                                                 (False, True), (False, True)):
                s = MATCH * size                                  # nbar14: the stock scale no longer in it (the axis kept: no effect)
                r, obst = solve_case(NBL, fr, block, s, target=tg, compass=cp, partycol=pc, reserved=res)
                r["s_used"] = s * (r.get("k") or 1)
                n_solves += 1
                cases[r["case"]] = cases.get(r["case"], 0) + 1
                note_k(NBL, r, fr, block, s, dict(target=tg, compass=cp, partycol=pc, reserved=res), ks, bad_order,
                       (ui_h,) + hs + (stock, size, tg, cp, pc, res))
                p = problems(r, fr, obst, s)
                if p:
                    bad_solve.append(((ui_h,) + hs + (stock, size, tg, cp, pc, res), p[:2]))
                ap = art_problems(NBL, r, fr)
                if ap:
                    bad_art.append(((ui_h,) + hs + (stock, size, tg, cp, pc, res), ap[:2]))
                sp = slide_problems(NBL, r, fr)
                if sp:
                    bad_slide.append(((ui_h,) + hs + (stock, size, tg, cp, pc, res), sp[:2]))
    print("states %d, solves %d, cases %s" % (n_states, n_solves, sorted(cases.items(), key=lambda kv: str(kv[0]))))
    print("(case, k): %s" % sorted(ks.items(), key=lambda kv: str(kv[0])))
    nk = sum(ks.values())
    k1 = sum(v for (c, k), v in ks.items() if k == 1 and c in (1, 2, 3))
    print("at k 1 beside / raised / joined: %d of %d (%.1f %%)" % (k1, nk, 100.0 * k1 / max(1, nk)))
    chk("1. P.lay.block holds every resolved HUD frame (bars + pips + pet under + Party room, the pet beside, the aura host under / "
        "above) in %d HUD states x %d UI heights" % (n_states // len(UI_HS), len(UI_HS)), not bad_block, bad_block[:3])
    chk("2. the Wings: inside the screen, off the tap pad band, >= %d px from every RESOLVED HUD frame, never the case-5 fallback, never "
        "under k 0.8 (%d solves)" % (HUD_MARGIN, n_solves), not bad_solve, bad_solve[:4])
    chk("2b. review: what the halves DRAW (the focus glow, a held slot's 2-unit drop, the glyph badges, the L / R plate, the ZL / ZR "
        "chip's gold disc, the swing lines) inside the solved boxes and >= %d px from every resolved HUD frame (%d solves)"
        % (HUD_MARGIN, n_solves), not bad_art, bad_art[:3])
    chk("2. nbar14 moves before it shrinks: every solve under k 1 (or on the grid) has nothing at k 1 beside / pushed / raised / joined "
        "(%d shrunk or grid solves checked)" % sum(v for (c, k), v in ks.items() if k < 1 or c == 4), not bad_order, bad_order[:3])
    chk("2. nbar14 ... and most solves stand at the band's size (k 1 beside / raised / joined: %d of %d)" % (k1, nk), k1 >= 0.8 * nk)
    chk("1b. nbar12 Party mode: Hub's rows (both columns, their rim in the block) and its Select tab - the Wings' boxes and art >= %d px "
        "from every one, never the case-5 fallback (%d Party states)" % (HUD_MARGIN, n_hub), not bad_hub and n_hub > 0, bad_hub[:3])
    chk("1c. nbar14 Party mode centred: the block (the HUD with Hub's rows and tab) on the screen's centre, the Wings either side of it "
        ">= %d px from the HUD and every row, never the case-5 fallback (%d Party states, %d as wings)" % (HUD_MARGIN, n_centre, n_centre_w),
        not bad_centre and n_centre > 0 and n_centre_w >= 0.8 * n_centre, bad_centre[:3])
    chk("1d. nbar15 our HUD bars off: Hub's rows and tab alone on the screen's centre, the Wings either side of them >= %d px from every "
        "row and the tab, never the case-5 fallback (%d Party states, %d as wings)" % (HUD_MARGIN, n_off, n_off_w),
        not bad_off and n_off > 0 and n_off_w >= 0.8 * n_off, bad_off[:3])
    hw_, hh_, ax_, ay_ = hb
    chk("2. nbar14: one half 228 x 135 (the flattened diamond: the outer column to 142.5, the inner glow 85.5, up 63, the swing lines "
        "down to 72)", (hw_, hh_, ax_, ay_) == (228, 135, 142.5, 63), (hw_, hh_, ax_, ay_))
    # 2c nbar14 the band's size on the Thor's top panel
    sq = NB.B * MATCH * MM_TOP
    rd = NB.BC * MATCH * MM_TOP
    chk("2c. nbar14: a square slot %.2f mm, a round one %.2f mm on the top panel - the band's square %.2f mm within 3 %%"
        % (sq, rd, BAND_SQUARE_MM), abs(sq / BAND_SQUARE_MM - 1) <= 0.03 and abs(rd / (88.1 * MM_BOTTOM) - 1) <= 0.03)
    T_ = NB.TOP
    dpad_mm = ((2 * T_.OFF_X + NB.B) * MATCH * MM_TOP, (2 * T_.OFF_Y + NB.B) * MATCH * MM_TOP)
    chk("2c. nbar14: the flattened D-pad diamond %.1f x %.1f mm (the band's 17.0 x 11.6 within 5 %%), the plate %.1f x %.1f mm (7.8 x 6.1)"
        % (dpad_mm + (T_.PLATE_W * MATCH * MM_TOP, T_.PLATE_H * MATCH * MM_TOP)),
        abs(dpad_mm[0] / 17.0 - 1) <= 0.05 and abs(dpad_mm[1] / 11.6 - 1) <= 0.05
        and abs(T_.PLATE_W * MATCH * MM_TOP / 7.8 - 1) <= 0.05 and abs(T_.PLATE_H * MATCH * MM_TOP / 6.1 - 1) <= 0.05)
    st817 = boot_trail(817)
    fr0, block0, _ = hud_state(st817, "none", False, False, 0, False, 100, 280, -350)
    r0, _ = solve_case(NBL, fr0, block0, MATCH)
    gap0 = min(min(abs(r0["rects"][1]["x"] + r0["rects"][1]["w"] - block0[0]), abs(r0["rects"][2]["x"] - (block0[0] + block0[2]))), 1e9)
    chk("2c. nbar14: the default HUD at the Thor's UI (817): beside at k 1, 60 px off the block, level (case %s, k %s, gap %.1f)"
        % (r0["case"], r0.get("k"), gap0), r0["case"] == 1 and (r0.get("k") or 1) == 1 and abs(gap0 - 60) < 0.01)
    chk("3. the slide (lane true): wholly below the edge at the start, monotonic, never on a HUD frame (sampled every 1/60 s; a shrunk "
        "spot easing from k 1)", not bad_slide, bad_slide[:3])
    # 3b nbar14 the seam's speed: the band's exit speed (its UP curve's last slope 1 / 0.6 over its travel) = the Wings' first speed
    band_mm = 242 * MM_BOTTOM
    v_band = (1 / 0.6) * band_mm / 240.0
    drop_px = 1080 + hb[3] * MATCH - min(r0["l"]["y"], r0["r"]["y"]) + 2
    ease = NBL.eval("WowCompanion.nbar.slideIn")
    h_ = 1e-4
    slope0 = ease(h_) / h_
    v_wings = slope0 * drop_px * MM_TOP / 260.0
    chk("3b. nbar14 the seam's speed: the Wings start at %.3f mm / ms, the band leaves at %.3f (within 10 %%; the old ease-out: %.3f)"
        % (v_wings, v_band, 5 * drop_px * MM_TOP / 260.0), abs(v_wings / v_band - 1) <= 0.10)
    peak = max((ease((i + 1) / 200.0) - ease(i / 200.0)) * 200 for i in range(200))
    chk("3b. ... and eases in from there (its speed never more than 3 %% over the first: peak %.3f / first %.3f), in the same 0.26 s"
        % (peak, slope0), peak <= slope0 * 1.03 and NB.TOP.IN_S == 0.26)
    # ------------------------------------------------------------------------------------------------ 6 nbar14 / prd22: Trail centres
    # the REAL Trail HUD (Party mode on, two rows' room): ActionBar's NB.centreDx asks for a shift; P.partyGeo (Hub's rows) goes to it at
    # once, the bars fade out, move, fade in (P.cx applied in P.place and P.lay.block); a protected frame in the lockdown makes it wait
    st6 = boot_trail(817)
    L6, G6 = st6["L"], st6["G"]
    P6 = G6.WowCompanion.prd
    hud_state(st6, "none", False, False, 2, False, 100, 280, -350)
    L6.execute('CDX = 0; WowCompanion.nbar = { centreDx = function() return CDX end }; '
               'WowCompanion.padMap = { party = { on = function() return true end, live = function() return true end } }')
    st6["run"](0.6)
    PXR6 = L6.eval("PXR")
    e6 = L6.eval("function() return WowCompanionDB.lay['Yes-Lander'].hud end")()
    f0 = PXR6(P6.f)
    b0 = P6.lay.block(e6)
    g0 = P6.partyGeo()
    L6.execute("CDX = -84")
    st6["run"](1 / 30)
    g1 = P6.partyGeo()
    k6 = 817 / 1080.0
    chk("6. prd22: Hub's rows told the shift at once (P.partyGeo's x %+.1f px; the bars not moved yet: they fade first)"
        % ((g1.x - g0.x) / k6), abs((g1.x - g0.x) / k6 + 84) < 0.6 and (P6.cx or 0) == 0, ((g1.x - g0.x) / k6, P6.cx))
    st6["run"](0.6)
    f1 = PXR6(P6.f)
    b1 = P6.lay.block(e6)
    chk("6. prd22: after the fade the bars and the block stand 84 px left (P.cx applied: P.place, P.lay.block), alpha back",
        P6.cx == -84 and abs((f1.x - f0.x) + 84) < 0.6 and abs((b1.x - b0.x) + 84) < 1e-6 and P6.petFadeA is None,
        (P6.cx, f1.x - f0.x, b1.x - b0.x, P6.petFadeA))
    # the lockdown with a protected frame (never expected: all ours): the shift waits, the one applied kept
    L6.execute("GAME.fight = true; WowCompanion.prd.f.IsProtected = function() return true end; CDX = 0")
    st6["run"](0.6)
    chk("6. prd22: in the lockdown a protected HUD frame makes it wait (P.cx kept, no fade loop)", P6.cx == -84 and P6.petFadeA is None,
        (P6.cx, P6.petFadeA))
    L6.execute("GAME.fight = false")
    st6["run"](0.6)
    f2 = PXR6(P6.f)
    chk("6. prd22: out of the lockdown the shift goes back to 0 (the fight's end / B: NB.centreDx 0)", (P6.cx or 0) == 0
        and abs(f2.x - f0.x) < 0.6, (P6.cx, f2.x - f0.x))
    L6.execute("WowCompanion.prd.f.IsProtected = nil; CDX = -84; WowCompanion.padMap = nil")
    st6["run"](0.6)
    chk("6. prd22: Party mode off -> no shift whatever ActionBar says", (P6.cx or 0) == 0, P6.cx)
    # prd23 (the user's pick 11-23, "Centre the rows"): our HUD bars OFF (P.wanted false: P.tick returns before it reads Party mode, so
    # P.partyOn keeps its last word - false here) and Party mode turned on after: P.cxFor reads Party mode live, so P.partyGeo (Hub's
    # rows and tab) carries ActionBar nbar15's shift; none of Trail's frames move (P.cx left as it is, the bars hidden)
    L6.execute("WowCompanion.npWant.prd = false")
    st6["run"](0.3)
    g2 = P6.partyGeo()
    L6.execute("WowCompanion.padMap = { party = { on = function() return true end, live = function() return true end } }")
    st6["run"](1 / 30)
    g3 = P6.partyGeo()
    chk("6. prd23 our HUD bars off, Party mode on after (P.partyOn stale %s): P.partyGeo carries the shift (cx %s, x %+.1f px), P.cx "
        "untouched, the bars hidden" % (P6.partyOn, g3.cx, (g3.x - g2.x) / k6),
        P6.partyOn is not True and g3.cx == -84 and abs((g3.x - g2.x) / k6 + 84) < 0.6 and (P6.cx or 0) == 0
        and not P6.f.IsShown(P6.f), (P6.partyOn, g3.cx, (g3.x - g2.x) / k6, P6.cx))
    L6.execute("CDX = 0")
    chk("6. prd23 ... the fight's end / B (NB.centreDx 0): the rows' shift back to 0", P6.partyGeo().cx == 0, P6.partyGeo().cx)
    L6.execute("CDX = -84; WowCompanion.padMap = nil")
    chk("6. prd23 ... Party mode off with our HUD bars off: no shift whatever ActionBar says", P6.partyGeo().cx == 0, P6.partyGeo().cx)
    L6.execute("WowCompanion.npWant.prd = nil; CDX = 0")
    st6["run"](0.6)
    # ------------------------------------------------------------------------------------------------ 4 NB.topOurs: the block, any alpha
    NBL.execute('''
        local F = getmetatable(UIParent)
        function F:IsVisible() return self.shown ~= false end
        function F:GetLeft() return self.rl end
        function F:GetTop() return self.rt end
        function F:GetWidth() return self.rw or self.w end
        function F:GetHeight() return self.rh or self.h end
        function F:GetEffectiveAlpha() return self.alpha end
        local function piece(on) return { base = function() return 960, 890 end, size = function() return 280, 32 end,
                                          off = function(w) return -w / 2, 0 end, on = function() return on end } end
        WowCompanion.lay = { screen = function() return 1920, 1080, 1 end, get = function() return nil end }
        local bars = CreateFrame("Frame", nil, UIParent); bars.rl, bars.rt, bars.rw, bars.rh = 820, 1080 - 890, 280, 32
        bars:SetAlpha(0)                                  -- the HUD faded out of combat
        WowCompanion.prd = { lay = piece(true), f = bars }
        WowCompanion.prd.lay.block = function(e) return { x = 820, y = 860, w = 330, h = 180 } end
    ''')
    ours, hud = NBL.eval("WowCompanion.nbar.topOurs")()
    hd = todict(hud)
    chk("4. NB.topOurs: the HUD faded to alpha 0 still read (any alpha) and unioned with P.lay.block's geometry",
        hd is not None and (hd["x"], hd["y"], hd["w"], hd["h"]) == (820, 860, 330, 180), hd)
    NBL.execute("WowCompanion.prd.lay.block = nil")
    ours, hud = NBL.eval("WowCompanion.nbar.topOurs")()
    hd = todict(hud)
    chk("4. ... no block (an older Trail): the live frame at alpha 0 alone", hd is not None and (hd["x"], hd["y"], hd["w"], hd["h"]) == (820, 890, 280, 32), hd)
    # ------------------------------------------------------------------------------------------------ 5 the fight's edge at once
    L5 = boot_nb()
    G5 = L5.globals()
    L5.execute('for _, f in ipairs(EVENTS["PLAYER_LOGIN"] or {}) do if f.onOnEvent then pcall(f.onOnEvent, f, "PLAYER_LOGIN") end end')
    L5.execute('''function RUNT() local t = TIMERS; TIMERS = {}; for _, fn in ipairs(t) do fn() end end
                  function FIRE(e) for _, f in ipairs(EVENTS[e] or {}) do if f.onOnEvent then f.onOnEvent(f, e) end end end''')

    def states():
        out = []
        for x in G5.SENT.values():
            d = todict(x) if lupa.lua_type(x) == "table" else None
            if d is None:
                try:
                    d = json.loads(x)
                except Exception:
                    continue
            if isinstance(d, dict) and d.get("type") == "full" and isinstance(d.get("actionbar"), dict) and "hudS" in d["actionbar"]:
                out.append(d["actionbar"]["hudS"])
        return out
    L5.execute("NOW = 100; RUNT(); RUNT()")
    n0 = len(states())
    L5.execute("NOW = 100.1; FIRE('PLAYER_REGEN_DISABLED'); RUNT()")
    s1 = states()
    last = s1[-1] if s1 else {}
    chk("5. a state at t = 0, then PLAYER_REGEN_DISABLED at t = 0.1 (lockdown not yet true): sent on the next frame, c = true",
        n0 >= 1 and len(s1) == n0 + 1 and last.get("c") is True and abs((last.get("g") or 0) - 100.1) < 1e-6, (n0, len(s1), last.get("c"), last.get("g")))
    L5.execute("NOW = 100.2; FIRE('UNIT_AURA'); RUNT()")
    chk("5. ... an ordinary change right after still waits for the 0.5 s window (only the edges skip it)", len(states()) == len(s1), len(states()))
    L5.execute("NOW = 103; COMBAT = false; FIRE('PLAYER_REGEN_ENABLED'); RUNT()")
    s3 = states()
    chk("5. PLAYER_REGEN_ENABLED: at once, c = false", s3 and s3[-1].get("c") is False and abs(s3[-1].get("g", 0) - 103) < 1e-6,
        s3[-1] if s3 else None)
    # ------------------------------------------------------------------------------------------------ the schematic shots
    if shots:
        render(out_dir, NBL, keep)
    print("\n%d passed, %d failed" % (passes, len(fails)))
    print("failed: %d" % len(fails))
    sys.exit(1 if fails else 0)


SHOT_STATES = {
    (768, "none", False, False, 0, False, 100, 280, -350): "default HUD, no pet (768-line UI)",
    (768, "beside", False, True, 0, False, 160, 400, -350): "HUD 160 %, 400 wide, pet beside, pips",
    (768, "under", False, True, 0, False, 100, 280, -470): "HUD low, pet under, pips, auras under",
    (1080, "under", True, False, 2, True, 160, 400, -470): "Party rows 2 + lift, auras above, HUD 160 % (1080-line UI)",
    (900, "beside", True, True, 1, False, 70, 180, -170): "HUD high and small, pet beside",
    (1200, "none", False, False, 0, False, 160, 400, -470): "a big HUD low (1200-line UI)",
}


CENTRE_SHOT = {(817, "none", False, False, 2, False, 100, 280, -350): "Party mode, 2 rows (4 members), the default HUD"}


def render(out_dir, NBL, keep):
    from PIL import Image, ImageDraw, ImageFont
    os.makedirs(out_dir, exist_ok=True)
    try:
        f1 = ImageFont.truetype("arialbd.ttf", 22)
        f2 = ImageFont.truetype("arial.ttf", 15)
    except Exception:
        f1 = f2 = ImageFont.load_default()
    ease = NBL.eval("WowCompanion.nbar.easeOut")
    hb = NBL.eval("WowCompanion.nbar.halfBox")()
    made = []
    for i, (key, title) in enumerate(SHOT_STATES.items()):
        if key not in keep:
            continue
        fr, block, r = keep[key]
        img = Image.new("RGB", (1920, 1080), (34, 40, 30))
        d = ImageDraw.Draw(img, "RGBA")
        # the fixed pieces
        for rr, lab, col in ((COMPASS, "compass", (90, 90, 140)), (TARGET, "target bar", (140, 60, 60)), (MINIMAP, "minimap", (60, 90, 140)),
                             (CASTBAR, "cast bar (reserved)", (150, 120, 40)), (COOLDOWNS, "Cooldown Manager (reserved)", (120, 80, 140))):
            d.rectangle((rr[0], rr[1], rr[0] + rr[2], rr[1] + rr[3]), fill=col + (110,), outline=col + (255,), width=2)
            d.text((rr[0] + 6, rr[1] + 4), lab, fill=(235, 235, 235), font=f2)
        d.rectangle((BAND[0], BAND[1], BAND[0] + BAND[2], BAND[1] + BAND[3]), outline=(255, 80, 80), width=2)
        d.text((BAND[0] + 6, BAND[1] + BAND[3] - 20), "tap pad band", fill=(255, 140, 140), font=f2)
        # the HUD block (dashed) and its resolved frames
        bx, by, bw, bh = block
        for x in range(int(bx), int(bx + bw), 14):
            d.line((x, by, x + 7, by), fill=(255, 255, 255), width=2)
            d.line((x, by + bh, x + 7, by + bh), fill=(255, 255, 255), width=2)
        for y in range(int(by), int(by + bh), 14):
            d.line((bx, y, bx, y + 7), fill=(255, 255, 255), width=2)
            d.line((bx + bw, y, bx + bw, y + 7), fill=(255, 255, 255), width=2)
        cols = {"bars": (40, 190, 40), "pet": (230, 180, 40), "auras": (90, 160, 230)}
        for n, rr in fr.items():
            c = cols.get(n, (200, 200, 200))
            d.rectangle((rr[0], rr[1], rr[0] + rr[2], rr[1] + rr[3]), fill=c + (120,), outline=c + (255,), width=2)
            d.text((rr[0] + 4, rr[1] + 2), "HUD " + n, fill=(255, 255, 255), font=f2)
        # the slide's ghosts (every 4th frame) then the Wings at the spot
        s = r["s_used"]
        ay = hb[3] * s
        dd = 1080 + ay - min(r["l"]["y"], r["r"]["y"]) + 2
        if r["lane"]:
            for fi in range(0, 17, 4):
                off = dd * (1 - ease(min(1.0, fi / 60 / 0.26)))
                for k in (1, 2):
                    q = r["rects"][k]
                    d.rectangle((q["x"], q["y"] + off, q["x"] + q["w"], q["y"] + q["h"] + off), outline=(255, 215, 0, 90), width=2)
        for k, cen in ((1, r["l"]), (2, r["r"])):
            q = r["rects"][k]
            d.rectangle((q["x"], q["y"], q["x"] + q["w"], q["y"] + q["h"]), outline=(255, 215, 0), width=3)
            # the diamond's four rings
            off = 50 * s
            for (ax_, ay_) in ((-1, 0), (0, -1), (1, 0), (0, 1)):
                cx, cy = cen["x"] + ax_ * off, cen["y"] + ay_ * off
                rad = (44 if k == 2 else 46) * s / 2
                if k == 2:
                    d.ellipse((cx - rad, cy - rad, cx + rad, cy + rad), fill=(16, 12, 9, 255), outline=(176, 140, 90), width=max(2, int(3 * s)))
                else:
                    d.rounded_rectangle((cx - rad, cy - rad, cx + rad, cy + rad), radius=int(rad / 3.5), fill=(16, 12, 9, 255),
                                        outline=(176, 140, 90), width=max(2, int(3 * s)))
        d.rectangle((0, 0, 1920, 44), fill=(0, 0, 0, 180))
        d.text((16, 10), "%s   [case %s, %s, k %.1f, lane %s]" % (title, r["case"], r["form"], r.get("k") or 1, r["lane"]),
               fill=(255, 230, 160), font=f1)
        p = os.path.join(out_dir, "top_wings_%02d.png" % (i + 1))
        img.save(p)
        made.append(p)
    th = [Image.open(p).resize((640, 360)) for p in made]
    sheet = Image.new("RGB", (1280, 360 * ((len(th) + 1) // 2)), (20, 20, 20))
    for i, t in enumerate(th):
        sheet.paste(t, ((i % 2) * 640, (i // 2) * 360))
    sp = os.path.join(out_dir, "top_wings_00_overview.png")
    sheet.save(sp)
    print("renders: " + ", ".join(made + [sp]))


if __name__ == "__main__":
    main()
