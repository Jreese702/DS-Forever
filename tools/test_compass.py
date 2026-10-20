"""The compass bar (WowCompanion/Trail.lua, WC.compass), off-device: Trail.lua under a stub game with a facing / position mock.

    python tools/test_compass.py [--render out.png]

Checks the layout (letters, ticks, markers, the lift-letter rule, off-bar pinning, distances), the show / hide rules (instance,
no facing, the world map, the player's switch) and that the frames are our own (UIParent, MEDIUM, mouse off, no secure template).
With --render, it also paints what the frames would show (boxes and text from the stub's recorded calls) on a plain ground: a
check of placement, not of the game's art. Needs lupa (pip install lupa). Exit code 1 on any failure."""
import math
import os
import sys

import lupa

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TRAIL = os.path.join(ROOT, "companion", "app", "src", "main", "assets", "companion", "addons", "WowCompanion", "Trail.lua")

STUB = r'''
GAME = { facing = math.rad(270), inst = false, map = 1429, px = 0.5, py = 0.5, pin = nil, quest = nil, dead = false, corpse = nil,
         worldmap = false, cont = 0, physH = 1080, uiH = 768 }
CALLS = {}
local function rec(o, k, ...) CALLS[#CALLS + 1] = { o = o, k = k, a = { ... } } end
local Region = {}
Region.__index = Region           -- (an unknown method is an error: the stub lists every call the compass may make)
function Region:Show() self.shown = true end
function Region:Hide() self.shown = false end
function Region:IsShown() return self.shown == true end
function Region:SetPoint(...) self.point = { ... } end
function Region:ClearAllPoints() self.point = nil end
function Region:SetSize(w, h) self.w, self.h = w, h end
function Region:SetAllPoints(t) self.all = t end
function Region:SetAlpha(a) self.alpha = a end
FONTSETS = 0
local setFont = Region.SetFont
function Region:SetText(t) self.text = t end
function Region:SetFont(f, s, fl) self.font, self.fsize, self.flags = f, s, fl; FONTSETS = (FONTSETS or 0) + 1 end
function Region:SetTextColor(r, g, b, a) self.col = { r, g, b, a } end
function Region:SetVertexColor(r, g, b, a) self.col = { r, g, b, a } end
function Region:SetAtlas(a) self.atlas = a end
function Region:SetTexture(t) self.tex = t end
function Region:SetRotation(r) self.rot = r end
function Region:SetFrameStrata(s) self.strata = s end
function Region:EnableMouse(m) self.mouse = m end
function Region:SetScript(k, fn) self.scripts = self.scripts or {}; self.scripts[k] = fn end
function Region:RegisterEvent(e) self.events = self.events or {}; self.events[e] = true end
function Region:GetHeight() return self.hh or 0 end
function Region:GetWidth() return self.ww or 0 end
function Region:GetEffectiveScale() return 1 end
GAME.speed = 0
function GetUnitSpeed() return GAME.speed end
function Region:CreateTexture(name, layer, tmpl, sub)
    local t = setmetatable({ kind = "tex", parent = self, layer = layer, sub = sub, shown = true }, Region)
    self.kids = self.kids or {}; self.kids[#self.kids + 1] = t
    return t
end
function Region:CreateFontString(name, layer)
    local t = setmetatable({ kind = "fs", parent = self, layer = layer, shown = true }, Region)
    self.kids = self.kids or {}; self.kids[#self.kids + 1] = t
    return t
end
UIParent = setmetatable({ name = "UIParent", hh = 768, ww = 1365.3334 }, Region)
FRAMES = {}
function CreateFrame(kind, name, parent, template)
    local f = setmetatable({ kind = kind, parent = parent, template = template, shown = true }, Region)
    FRAMES[#FRAMES + 1] = f
    return f
end
function CreateColor(r, g, b, a) return { r = r, g = g, b = b, a = a } end
function CreateVector2D(x, y) return { x = x, y = y, GetXY = function(s) return s.x, s.y end } end
function GetPhysicalScreenSize() return 1920, GAME.physH end
function GetPlayerFacing() return GAME.facing end
function IsInInstance() return GAME.inst end
function UnitIsDeadOrGhost() return GAME.dead end
-- the stub's map: 1000 x 1000 yards, map x right = EAST, map y down = SOUTH; world x = north, y = west
local function toWorld(mx, my) return 500 - my * 1000, 500 - mx * 1000 end
C_Map = {
    GetBestMapForUnit = function() return GAME.map end,
    -- as on the Thor (test15): the C API hands back PLAIN { x, y } tables, no :GetXY (that is FrameXML's mixin)
    GetPlayerMapPosition = function() return { x = GAME.px, y = GAME.py } end,
    GetWorldPosFromMapPos = function(m, v) local wx, wy = toWorld(v.x, v.y); return GAME.cont, { x = wx, y = wy } end,
    GetUserWaypoint = function() if GAME.pin then return { uiMapID = GAME.map, position = { x = GAME.pin[1], y = GAME.pin[2] } } end end,
}
C_SuperTrack = {
    IsSuperTrackingUserWaypoint = function() return false end,
    GetSuperTrackedQuestID = function() return GAME.quest and 123 or 0 end,
}
-- as on the Thor (test13): GetNextWaypoint answers only for another zone; the spot on the player's own map comes from
-- GetQuestsOnMap (GAME.qsrc = "onmap") or GetNextWaypointForMap ("wpmap"); "wp" = another zone
C_QuestLog = {
    GetNextWaypoint = function(q) if GAME.quest and GAME.qsrc == "wp" then return GAME.map, GAME.quest[1], GAME.quest[2] end end,
    GetNextWaypointForMap = function(q, m) if GAME.quest and GAME.qsrc == "wpmap" then return GAME.quest[1], GAME.quest[2] end end,
    GetQuestsOnMap = function(m)
        local out = { { questID = 999, x = 0.1, y = 0.1 } }
        if GAME.quest and GAME.qsrc == "onmap" then out[2] = { questID = 123, x = GAME.quest[1], y = GAME.quest[2] } end
        return out
    end,
}
GAME.qsrc = "onmap"
SENT = {}
C_Map.HasUserWaypoint = function() return GAME.pin ~= nil end
C_DeathInfo = { GetCorpseMapPosition = function() if GAME.corpse then return { x = GAME.corpse[1], y = GAME.corpse[2] } end end }
WorldMapFrame = { IsShown = function() return GAME.worldmap end }
GAME.t = 100
function GetTime() return GAME.t end
function GetCameraZoom() return 12.5 end
CVAR_WRITES = 0
C_CVar = { GetCVar = function(k) if k == "cameraFov" then return "90" end end, SetCVar = function() CVAR_WRITES = CVAR_WRITES + 1 end }
GAME.nav = nil         -- { x, y, clamped }
local NAV = setmetatable({}, Region)
function NAV:GetCenter() return GAME.nav.x, GAME.nav.y end
function NAV:GetEffectiveScale() return 1.0 end
C_Navigation = {
    GetFrame = function() if GAME.nav then return NAV end end,
    WasClampedToScreen = function() return GAME.nav and GAME.nav.clamped or false end,
    HasValidScreenPosition = function() return GAME.nav ~= nil end,
    GetTargetState = function() return 2 end,
    GetDistance = function() return 141.4 end,
}
WowCompanion = { send = function(j) SENT[#SENT + 1] = j end, encode = function(t) return t end }
WowCompanionDB = {}
'''


def run():
    L = lupa.LuaRuntime()
    L.execute(STUB)
    L.execute(open(TRAIL, encoding="utf-8").read().replace("local ADDON = ...", "local ADDON = 'WowCompanion'", 1))
    return L


def main():
    render = "--render" in sys.argv
    out = sys.argv[sys.argv.index("--render") + 1] if render else None
    L = run()
    fails = []

    def chk(name, ok):
        print(("PASS " if ok else "FAIL ") + name)
        if not ok:
            fails.append(name)

    G, C = L.globals().GAME, L.globals().WowCompanion.compass
    lay = lambda: C.layout(C.facing(), C.me(), C.targets(C.me()))

    def letters(lo):
        return {lo.letters[i].name: lo.letters[i] for i in range(1, len(lo.letters) + 1)}

    def marks(lo):
        return {lo.marks[i].key: lo.marks[i] for i in range(1, len(lo.marks) + 1)}

    # facing east (CCW 270 degrees): E in the middle, NE to the left, SE to the right, N and S at the ends
    G.facing = math.radians(270)
    lo = lay()
    ls = letters(lo)
    chk("facing east: E centred", abs(ls["E"].x) < 0.01)
    chk("NE left of E, SE right", ls["NE"].x < 0 < ls["SE"].x)
    chk("N and S at the ends (+-400 px)", abs(abs(ls["N"].x) - 400) < 0.01 and abs(abs(ls["S"].x) - 400) < 0.01 and ls["N"].x < 0)
    chk("nothing behind shown (no W)", "W" not in ls)
    chk("ticks every 15 degrees between the letters (8 in +-90)", len(lo.ticks) == 8)
    chk("the ends fade (N dimmer than E)", ls["N"].fade < ls["E"].fade and abs(ls["E"].fade - 1) < 1e-6)
    chk("letter sizes 22 / 17", ls["E"].size == 22 and ls["NE"].size == 17)

    # a pin 100 yd due east of the player: in the middle, "100 yd"; the quest 141 yd north-east
    G.pin = L.table(0.6, 0.5)
    G.quest = L.table(0.6, 0.4)
    lo = lay()
    ms, ls = marks(lo), letters(lo)
    chk("the pin due east sits on the centre", abs(ms["pin"].x) < 0.5 and not ms["pin"].off)
    chk("its distance: 100 yd", ms["pin"].dist == "100 yd")
    chk("the quest NE sits on NE's spot (its spot from GetQuestsOnMap, as on the Thor)", abs(ms["quest"].x - ls["NE"].x) < 0.5 and C.qsrc == "onmap")
    chk("the quest's distance rounds to 5 above 50 (141 -> 140 yd)", ms["quest"].dist == "140 yd")
    chk("LIFT: E under the pin lifts 25 px and shrinks 5", ls["E"].lifted and ls["E"].y == 32 - 25 and ls["E"].size == 17)
    chk("LIFT: NE under the quest lifts too", ls["NE"].lifted and ls["NE"].size == 12)
    chk("SE (no marker) stays on the line", not ls["SE"].lifted and ls["SE"].y == 32)

    G.qsrc = "wpmap"
    chk("the quest's spot from GetNextWaypointForMap", "quest" in marks(lay()) and C.qsrc == "wpmap")
    G.qsrc = "wp"
    chk("... or GetNextWaypoint (another zone)", "quest" in marks(lay()) and C.qsrc == "wp")
    G.qsrc = "nowhere"
    chk("no spot anywhere: no quest marker, qsrc none", "quest" not in marks(lay()) and C.qsrc == "none")
    G.qsrc = "onmap"
    # a corpse behind (west): pinned 48 px in from the LEFT or RIGHT end, flagged off-bar
    G.dead = True
    G.corpse = L.table(0.3, 0.52)
    ms = marks(lay())
    chk("the corpse behind is off the bar, pinned at +-382", ms["corpse"].off and abs(abs(ms["corpse"].x) - 382) < 0.01)
    ls = letters(lay())
    end = "S" if ms["corpse"].x > 0 else "N"
    chk("the end letter under the pinned corpse (%s) lifts too" % end, ls[end].lifted)
    G.dead = False
    chk("alive: no corpse marker", "corpse" not in marks(lay()))

    # turning: facing north puts the east pin at the right end, the NE quest at +210
    G.facing = 0.0
    ms = marks(lay())
    chk("facing north: the east pin at the right end (+400)", abs(ms["pin"].x - 400) < 0.5)
    chk("facing north: the NE quest halfway right (+200)", abs(ms["quest"].x - 200) < 0.5)

    # show / hide
    C.refresh()
    f = C.frame
    chk("shown outdoors with a facing", f is not None and f.shown)
    chk("our own frame: UIParent, MEDIUM, mouse off, no template",
        L.eval("rawequal(WowCompanion.compass.frame.parent, UIParent)") and f.strata == "MEDIUM" and f.mouse is False and f.template is None)
    G.inst = True; C.refresh()
    chk("hidden in an instance", not f.shown and C.why == "instance")
    G.inst = False; G.facing = None; C.refresh()
    chk("hidden without a facing", not f.shown and C.why == "no facing")
    G.facing = 0.0; G.worldmap = True; C.refresh()
    chk("hidden while the world map is open", not f.shown and C.why == "world map")
    G.worldmap = False; C.set(False)
    chk("the player's switch off: hidden, kept in WowCompanionDB.compass", not f.shown and L.globals().WowCompanionDB.compass is False)
    C.set(True)
    chk("switched on again: shown", f.shown and L.globals().WowCompanionDB.compass is True)
    chk("default ON (no key yet)", L.eval("(function() WowCompanionDB = {} return WowCompanion.compass.wanted() end)()") is True)

    # the draw: the atlases by name, letters in FRIZQT with an outline, the scale (768 / 1080 UI units per pixel)
    G.facing = math.radians(270)
    C.draw()
    kids = [f.kids[i] for i in range(1, len(f.kids) + 1)]
    shown = [k for k in kids if k.shown]
    atl = sorted({k.atlas for k in shown if k.atlas})
    chk("markers use the game's atlases: " + ", ".join(atl),
        set(atl) == {"Waypoint-MapPin-Tracked", "questnormal"})
    fs = [k for k in shown if k.kind == "fs" and k.text in ("N", "NE", "E", "SE", "S")]
    endN = [k for k in fs if k.text == "N"]
    midE = [k for k in fs if k.text == "E"]
    chk("letters fade by ALPHA toward the ends (N at the end %.2f, E centred %.2f)" % (endN[0].alpha, midE[0].alpha),
        endN and midE and endN[0].alpha < 0.25 and midE[0].alpha > 0.99 and endN[0].col[1] == 1)
    chk("letters in FRIZQT, OUTLINE", fs and all(k.font == "Fonts\\FRIZQT__.TTF" and k.flags == "OUTLINE" for k in fs))
    k = 768 / 1080
    chk("the band is 860 x 76 spec px in UI units (1/1080 of UIParent's height each)", abs(f.w - 860 * k) < 1e-6 and abs(f.h - 76 * k) < 1e-6)
    chk("the ground is ONE texture: our own soft band", L.eval("#WowCompanion.compass.ground") == 1
        and "compass_band" in L.eval("WowCompanion.compass.ground[1].tex"))
    names = [n for n in ("SetBinding", "SetCVar", "SetRaidTarget", "SetOverrideBinding") if n in open(TRAIL, encoding="utf-8").read()]
    chk("no binding / CVar / raid mark calls in Trail.lua", not names)

    sent = L.globals().SENT
    diag = [sent[i] for i in range(1, len(sent) + 1) if sent[i].probe == "compass"]
    chk("the diagnostic line: pin, quest and its source, the marker count (%d sent)" % len(diag),
        len(diag) == 1 and diag[0].pin is True and diag[0].quest is True and diag[0].qsrc == "onmap" and diag[0].n == 2 and diag[0].uwp is True)
    # cp5 (the user: "the compass scrolling is kind of choppy"): every frame, an eased heading, pieces only moved
    G.facing = 1.0
    C.draw()
    nk = len([1 for i in range(1, len(f.kids) + 1)])
    fonts0 = L.globals().FONTSETS
    G.facing = 1.5
    hs, step = [], f.scripts.OnUpdate
    for i in range(60):
        step(f, 1 / 60)
        hs.append(C.h)
    chk("eases to the new facing in ~0.3 s (%.3f after 18 frames, %.4f after 60)" % (hs[17], hs[59]), abs(hs[59] - 1.5) < 0.005 and 1.3 < hs[17] < 1.5)
    chk("critically damped: never overshoots", max(hs) <= 1.5 + 1e-6)
    chk("moves every frame, no throttle (60 different headings)", len(set(round(h, 6) for h in hs)) >= 55)
    nk2 = len([1 for i in range(1, len(f.kids) + 1)])
    chk("no new pieces per frame (%d before, %d after 60 frames)" % (nk, nk2), nk == nk2)
    chk("no SetFont per frame while nothing lifts (%d calls)" % (L.globals().FONTSETS - fonts0), L.globals().FONTSETS - fonts0 <= 4)
    xs = [k.point[4] for k in [f.kids[i] for i in range(1, len(f.kids) + 1)] if k.kind == "fs" and k.shown and k.point and k.text == "N"]
    chk("sub-pixel placement (an x offset like %.3f)" % (xs[0] if xs else -1), xs and abs(xs[0] - round(xs[0])) > 1e-6)
    G.facing = 6.2; C.draw()
    G.facing = 0.1
    path = []
    for i in range(40):
        step(f, 1 / 60)
        path.append(C.h)
    chk("across north the short way (6.2 -> 0.1 never passes 3)", all(h > 6.0 or h < 0.2 for h in path) and abs(path[-1] - 0.1) < 0.01)
    G.facing = math.radians(270); C.draw()
    # cp7, the camera experiment (the user: "Try the experiment"): off by default; on, the nav point's x sets the camera offset
    G.facing = 0.0; G.pin = None; C.draw()
    chk("cam: OFF by default (heading = facing)", C.camWanted() is False and C.cam.src == "off" and abs(C.h) < 1e-9)
    C.setCam(True)
    half = 1365.3334 / 2
    cam = -math.radians(30)                         # the camera turned 30 degrees clockwise of the character (facing north)
    b = math.atan2(-100, 100)                       # the quest NE, as the compass computes it (CCW)
    G.nav = L.table_from({"x": half + half * math.tan(cam - b), "y": 400.0, "clamped": False})
    for i in range(60):
        step(f, 1 / 60)
    chk("cam: the nav point locks the offset to the camera (%.3f, want %.3f), src nav" % (C.cam.off, cam), abs(C.cam.off - cam) < 0.01 and C.cam.src == "nav")
    chk("cam: the shown heading follows the camera, not the character (%.3f)" % C.wrap(C.h), abs(C.wrap(C.h) - cam) < 0.02)
    # cp9: lost, the compass turns LIVE with the character, the last measured offset fading over 1.5 s (test18: "doesnt spin 360")
    G.nav.clamped = True; G.facing = 2.5
    for i in range(6):
        G.t = G.t + 1 / 60
        step(f, 1 / 60)
    off1 = C.cam.off
    chk("cam: off screen: the offset starts fading at once (%.3f, src decay)" % off1, -0.524 < off1 < -0.45 and C.cam.src == "decay")
    for i in range(300):
        G.t = G.t + 1 / 60
        step(f, 1 / 60)
    chk("cam: after 5 s the compass is on the character (%.3f)" % C.wrap(C.h), abs(C.wrap(C.h) - 2.5) < 0.02)
    hs = []
    for k in range(72):                                   # the character turns a full circle, 5 degrees a frame-step
        G.facing = C.wrap(2.5 + math.radians(5 * (k + 1)))
        for i in range(4):
            G.t = G.t + 1 / 60
            step(f, 1 / 60)
        hs.append(C.wrap(C.h))
    turned = sum(abs(C.wrap(hs[i] - hs[i - 1])) for i in range(1, len(hs)))
    chk("cam: the compass spins the full 360 with the character (%.0f degrees)" % math.degrees(turned), math.degrees(turned) > 340)
    G.speed = 0

    # the TEST16 LOG (cp7, 12:56:12 .. 12:56:32), its exact f / nx / clamped, re-read for cp10: the camera FOLLOWED the character
    # (the pin's bearing ~2.05 from the honest samples); 12:56:12 (the pin 94 degrees left, shown on the RIGHT) and 12:56:27 (the
    # pin straight behind, shown near the centre) were MIRRORED projections of a target behind the camera: never fixes
    G.quest = None
    C.setCam(True)
    c0 = 1365.3334 / 2
    B = 2.05

    def put_pin(bear):
        dx, dy = 100 * math.cos(bear), 100 * math.sin(bear)
        G.pin = L.table((500 - dy) / 1000, (500 - dx) / 1000)

    def run_frames(n=90):
        for i in range(n):
            G.t = G.t + 1 / 60
            if i % 6 == 0:
                C.read(True)
            step(f, 1 / 60)

    put_pin(B)
    L.execute("C_SuperTrack.IsSuperTrackingUserWaypoint = function() return true end")
    for ts, fac, nx, clamped, kind in [("12:56:12", 0.38, 1276.6, False, "mirror"), ("12:56:17", 2.652, 1198.3, False, "nav"),
                                        ("12:56:22", 1.804, 1357.3, True, "decay"), ("12:56:27", 5.941, 633.1, False, "mirror"),
                                        ("12:56:32", 1.956, 604.6, False, "nav")]:
        G.facing = fac
        G.nav = L.table_from({"x": nx, "y": 400.0, "clamped": clamped})
        run_frames()
        off = abs(C.wrap(C.wrap(C.h) - fac))
        chk("test16 %s (f %.3f, nx %.1f%s): %s, the compass within 15 degrees of the character (%.0f)"
            % (ts, fac, nx, ", clamped" if clamped else "", kind, math.degrees(off)), C.cam.src == kind and off < math.radians(15))

    # test20's case: the quest fixed, the character (and the camera, which follows it) turning a FULL circle; the engine's x as a
    # projection, mirrored when the target is behind, unclamped all the way (the worst case): the compass spins with the facing
    G.facing = 0.0; G.nav = None; C.draw(); C.cam.off = 0      # start ON the character (no catch-up from the replay's heading)
    hs, fixes, mirrors = [], 0, 0
    for k in range(144):
        fac = C.wrap(math.radians(2.5 * (k + 1)))
        G.facing = fac
        x = c0 + c0 * math.tan(C.wrap(fac - B))
        G.nav = L.table_from({"x": x, "y": 400.0, "clamped": False})
        for i in range(3):
            G.t = G.t + 1 / 60
            if i == 0:
                C.read(True)
            step(f, 1 / 60)
        hs.append(C.wrap(C.h))
        fixes += C.cam.src == "nav"
        mirrors += C.cam.src == "mirror"
    turned = sum(abs(C.wrap(hs[i] - hs[i - 1])) for i in range(1, len(hs)))
    worst = max(abs(C.wrap(hs[i] - C.wrap(math.radians(2.5 * (i + 1))))) for i in range(len(hs)))
    chk("test20: a full turn, the quest fixed: the compass spins %.0f degrees, never more than %.0f off the character (%d fixes, %d mirrors)"
        % (math.degrees(turned), math.degrees(worst), fixes, mirrors), math.degrees(turned) > 340 and worst < math.radians(20) and mirrors > 0)
    L.execute("C_SuperTrack.IsSuperTrackingUserWaypoint = function() return false end")
    G.pin = None; G.speed = 0
    G.speed = 0; G.quest = None; C.read(True)
    step(f, 1 / 60)
    chk("cam: no target: nothing from the nav point (src none)", C.cam.src == "none")
    C.diagAt = -100; C.draw()
    last = L.globals().SENT[len(L.globals().SENT)]
    chk("cam: the compass line carries the experiment's state (cam, csrc, coff, cyaw, nav, nx, ncl, nvp, nst, fov)",
        last.probe == "compass" and last.cam is True and last.csrc is not None and last.fov == 90 and last.ncl is not None
        and last.cyaw is not None)
    C.setCam(False); G.nav = None; G.quest = L.table(0.6, 0.4); G.facing = math.radians(270); C.draw()
    # the camera-yaw probe (one shot, read only): starts 20 s after login once the game's nav diamond exists, logs 4 samples a line
    P = C.probe
    L.eval("WowCompanion.compass.probe")
    G.t = 100.0
    L.eval("(function() local P = WowCompanion.compass.probe; P.loginAt = 100 end)()")
    P.tick()
    chk("probe: nothing before the nav diamond exists", not P.on)
    G.nav = L.table_from({"x": 1100.0, "y": 500.0, "clamped": False})
    G.t = 110.0; P.tick()
    chk("probe: waits 20 s after the login", not P.on)
    G.t = 121.0
    before = len(sent)
    for i in range(8):
        G.t = 121.0 + i * 0.25
        P.tick()
    probe = [sent[i] for i in range(before + 1, len(sent) + 1) if sent[i].probe == "camyaw"]
    starts = [x for x in probe if x.op == "start"]
    samples = [x for x in probe if x.op == "s"]
    chk("probe: a start line with the screen, the FOV (read) and the columns", len(starts) == 1 and starts[0].fov == "90" and starts[0].ui.h == 768)
    first = samples[0].s[1] if samples else ""
    chk("probe: samples batched 4 a line: t,facing,navX,navY,... (%s)" % first,
        len(samples) == 2 and first.split(",")[2] == "1100.0" and first.split(",")[5] == "false" and first.split(",")[9] != "nil")
    for i in range(400):
        G.t = 125.0 + i * 0.25
        P.tick()
    ends = [sent[i] for i in range(1, len(sent) + 1) if sent[i].probe == "camyaw" and sent[i].op == "end"]
    chk("probe: ends after 360 samples, marked done once per set", len(ends) == 1 and ends[0].n == 360 and L.globals().WowCompanionDB.camProbe == "cp4")
    n = len(sent); G.t = 300.0; P.tick()
    chk("probe: never again (no more lines)", len(sent) == n)
    chk("probe: no CVar written", L.globals().CVAR_WRITES == 0)
    if render:
        paint(f, kids, k, out)
        print("rendered", out)
    print("failed: %d" % len(fails))
    sys.exit(1 if fails else 0)


def paint(f, kids, k, out):
    """what the frames would show, from the recorded points (boxes for textures, text for font strings), at 2x"""
    from PIL import Image, ImageDraw, ImageFont
    W, H = int(f.w / k), int(f.h / k)
    img = Image.new("RGB", (W * 2, H * 2 + 40), (30, 34, 30))
    d = ImageDraw.Draw(img)
    d.rectangle((0, 0, W * 2, H * 2), fill=(12, 12, 12))
    cx = W
    for o in kids:
        if not o.shown or not o.point:
            continue
        x = cx + o.point[4] / k * 2
        y = -o.point[5] / k * 2
        if o.kind == "fs":
            col = tuple(int(255 * c) for c in (o.col[1], o.col[2], o.col[3]))
            anchor = "mt" if o.point[1] == "TOP" else "mm"
            d.text((x, y), o.text or "", fill=col, anchor=anchor, font=ImageFont.load_default(), stroke_width=1, stroke_fill=(0, 0, 0))
        elif o.w:
            w, h = o.w / k * 2, o.h / k * 2
            if o.atlas:
                if o.col and o.col[1] == 0:
                    continue
                d.rectangle((x - w / 2, y - h / 2, x + w / 2, y + h / 2), outline=(255, 210, 0))
                d.text((x, y), {"Waypoint-MapPin-Tracked": "pin", "questnormal": "!", "Navigation-Tombstone-Icon": "corpse",
                                "Navigation-Tracked-Arrow": ">"}.get(o.atlas, "?"), fill=(255, 210, 0), anchor="mm")
            else:
                d.rectangle((x - w / 2, y - h / 2, x + w / 2, y + h / 2), fill=(200, 180, 120))
    d.text((8, H * 2 + 12), "test_compass: placement from Trail.lua's recorded calls (boxes = the game's atlases)", fill=(220, 220, 220))
    img.save(out)


if __name__ == "__main__":
    main()
