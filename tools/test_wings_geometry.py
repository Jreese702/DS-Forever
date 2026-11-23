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
    bar, the left party column, the minimap, the reserved cast bar / Cooldown Manager) at the bar's scale s = stock scale x the piece's
    size x kk: both halves inside the screen, off the tap pad's band, at least MARGIN (24 px) from every RESOLVED HUD frame (not the
    block), and never the case-5 stock fallback (the solver shrinks first: k 1 / 0.9 / 0.8 beside, raised at 0.8, joined above at 1 /
    0.8, the grid at 0.7 then 0.55 in the lower 60 %, then 0.55 anywhere); the other axes (stock scale 0.8 / 1 / 1.2, the piece's size 70 / 100 / 150, the target
    bar / compass / party column on / off, the reserved frames on / off) over a spread of HUD states.
  2b WHAT IS DRAWN (the 11-22 review: the focus glow reached 15 units past the solved box): every slot's focus glow, a held slot's
    2-unit drop, the glyph badges, the L / R plate with its bumper glyph (nbar12), the ZL / ZR chip under it with its gold disc, Party
    mode's chord row and the swing lines - computed from the bar's constants and its placement code (NB.outerGeo), not NB.halfBox -
    inside the solved boxes and >= MARGIN from every resolved HUD frame.
  1b nbar12 PARTY MODE'S ROWS (the Wings show in Party mode's fights now): Hub's grid under the HUD (its rows from Trail's P.partyGeo at
    Hub's own PF constants, two columns, with the rings' rim) and its Select tab at the grid's left edge join the HUD block as NB.topOurs
    reads them; the Wings (boxes and art) >= MARGIN from every row and the tab, never the case-5 fallback, in every Party state.
  3 THE SLIDE NEVER CROSSES IT: where the solve says `lane`, the slide (from wholly below the screen's bottom edge up to the spot on
    NB.easeOut, sampled every 1/60 s) rises monotonically and never touches a HUD frame.
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
    inp = {"W": 1920, "H": 1080, "s": s, "hud": R(hud), "ours": ours, "game": game, "band": R(BAND), "stock": {"x": 960, "y": 984}}
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
            if over(x, h, MARGIN - 1e-6):
                out.append("within %d px of the HUD's %s %s / %s" % (MARGIN, n, x, h))
    return out


def art_rects(NBL, r):
    """the 11-22 review: what each half DRAWS, in px, from the bar's own constants and NB.anchor / NB.button / NB.topDeco's placement
    (not NB.halfBox): every slot's focus glow (slot + FOCUS, centred; a held slot 2 units lower), its glyph badge, nbar12's L / R plate
    with its bumper glyph, the ZL / ZR chip under it with its held gold disc (CHIP_H + 8), Party mode's chord row, the two swing lines
    under the faces' diamond. -> {1: [rects], 2: [rects]} (top-left origin)"""
    NB = NBL.globals().WowCompanion.nbar
    T = NB.TOP
    s = r["s_used"]
    off, d2 = NB.OFF, NB.OFF + NB.B / 2
    ga = NB.GLYPH_AT
    out = {}
    for h, c in ((1, r["l"]), (2, r["r"])):
        sz = NB.B if h == 1 else NB.BC
        rects = []
        for ax, ay in ((-1, 0), (0, 1), (1, 0), (0, -1)):
            for drop in (0, 2):
                cx, cy = c["x"] + ax * off * s, c["y"] - ay * off * s + drop * s
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
            dw = (2 * NB.OFF + NB.B) * s
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
                if over(x, hf, MARGIN - 1e-6):
                    out.append("half %d's art %s within %d px of the HUD's %s" % (h, tuple(round(v, 1) for v in x), MARGIN, n))
    return out


def slide_problems(NBL, r, hud_frames):
    """the slide's path, sampled every 1/60 s over IN_S: monotonic, never on a HUD frame"""
    if not r["lane"] or r["case"] == 5:                 # (case 5 is check 2's failure already)
        return []
    out = []
    ease = NBL.eval("WowCompanion.nbar.easeOut")
    hb = NBL.eval("WowCompanion.nbar.halfBox")()
    s = r["s_used"]
    ay = hb[3] * s
    to_y = min(r["l"]["y"], r["r"]["y"])
    d = 1080 + ay - to_y + 2
    prev = None
    n = int(math.ceil(0.26 * 60))
    for i in range(n + 1):
        t = min(1.0, i / 60 / 0.26)
        off = d * (1 - ease(t))
        if prev is not None and off > prev + 1e-9:
            out.append("not monotonic at %d" % i)
        prev = off
        for k in (1, 2):
            rr = r["rects"][k]
            x = (rr["x"], rr["y"] + off, rr["w"], rr["h"])
            for nme, h in hud_frames.items():
                if over(x, h):
                    out.append("frame %d: half %d on the HUD's %s" % (i, k, nme))
    if r["rects"][1]["y"] + d < 1080 or r["rects"][2]["y"] + d < 1080:
        out.append("does not start wholly below the edge")
    return out


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
    bad_block, bad_solve, bad_slide, bad_art, bad_hub = [], [], [], [], []
    n_hub = 0
    n_states = n_solves = 0
    cases = {}
    keep = {}
    for ui_h in (768, 900, 1080, 1200):
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
            # the default axes: stock scale 1, the piece's size 100 %, target / compass on, reserved on
            s = 1.0 * 1.0 * kk
            r, obst = solve_case(NBL, fr, block, s)
            r["s_used"] = s * (r.get("k") or 1)
            n_solves += 1
            cases[r["case"]] = cases.get(r["case"], 0) + 1
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
            key = (ui_h, pet, up, pips, party, lift, sc, wd, y)
            if key in SHOT_STATES:
                keep[key] = (fr, block, r)
        # the other axes over a spread of HUD states (this UI height)
        spread = [("none", False, False, 0, False, 100, 280, -350), ("beside", False, True, 0, False, 160, 400, -170),
                  ("under", True, True, 2, True, 160, 400, -470), ("beside", True, False, 1, False, 70, 180, -470),
                  ("under", False, False, 0, False, 160, 280, -470), ("none", True, True, 2, False, 100, 400, -170)]
        for hs in spread:
            fr, block, e = hud_state(st, *hs)
            for stock, size, tg, cp, pc, res in itertools.product((0.8, 1.0, 1.2), (0.7, 1.0, 1.5), (False, True), (False, True),
                                                                 (False, True), (False, True)):
                s = stock * size * kk
                r, obst = solve_case(NBL, fr, block, s, target=tg, compass=cp, partycol=pc, reserved=res)
                r["s_used"] = s * (r.get("k") or 1)
                n_solves += 1
                cases[r["case"]] = cases.get(r["case"], 0) + 1
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
    chk("1. P.lay.block holds every resolved HUD frame (bars + pips + pet under + Party room, the pet beside, the aura host under / "
        "above) in %d HUD states x 4 UI heights" % (n_states // 4), not bad_block, bad_block[:3])
    chk("2. the Wings: inside the screen, off the tap pad band, >= 24 px from every RESOLVED HUD frame, never the case-5 fallback "
        "(%d solves)" % n_solves, not bad_solve, bad_solve[:4])
    chk("2b. review: what the halves DRAW (the focus glow, a held slot's 2-unit drop, the glyph badges, the ZL / ZR chip's gold disc, "
        "the swing lines) inside the solved boxes and >= 24 px from every resolved HUD frame (%d solves)" % n_solves, not bad_art, bad_art[:3])
    chk("2. ... the bar shrinks before it moves: some states solved at k < 1 (cases seen %s)" % sorted(cases, key=str), any(c for c in cases))
    chk("1b. nbar12 Party mode: Hub's rows (both columns, their rim in the block) and its Select tab - the Wings' boxes and art >= %d px "
        "from every one, never the case-5 fallback (%d Party states)" % (MARGIN, n_hub), not bad_hub and n_hub > 0, bad_hub[:3])
    hw_, hh_, ax_, ay_ = hb
    chk("2. nbar12: one half 221 x 185 (the outer column to 133), its diamond's centre 133 / 88 from its top-left", (hw_, hh_, ax_, ay_) == (221, 185, 133, 88),
        (hw_, hh_, ax_, ay_))
    chk("3. the slide (lane true): wholly below the edge at the start, monotonic, never on a HUD frame (sampled every 1/60 s)",
        not bad_slide, bad_slide[:3])
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
