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
CV = { cameraFov = "90", GamePadCameraYawSpeed = "1.3", gamepadInvertYaw = "0", GamePadTurnWithCamera = "3", GamepadCameraFollowOnStick = "0" }
C_CVar = { GetCVar = function(k) return CV[k] end, SetCVar = function() CVAR_WRITES = CVAR_WRITES + 1 end }
GAME.rx = 0; GAME.gp = true; GAME.fight = false
C_GamePad = {
    IsEnabled = function() return GAME.gp end,
    StickIndexToConfigName = function(i) return ({ [0] = "Movement", [1] = "Camera" })[i] end,
    GetDeviceMappedState = function()
        if not GAME.gp then return nil end
        return { stickCount = 2, sticks = { { x = 0, y = 0, len = 0 }, { x = GAME.rx, y = 0, len = math.abs(GAME.rx) } }, axes = {} }
    end,
}
function InCombatLockdown() return GAME.fight end
function UnitCastingInfo() return nil end
function UnitChannelInfo() return nil end
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
    # cp11, the CAMERA (the user: "it needs to follow the camera not the char"): dead-reckoned from the game's own controller state
    # (C_GamePad), the character's facing while it turns with the camera, fixes from the engine's navigation point
    G.facing = 0.0; G.pin = None; G.quest = None; G.nav = None; G.speed = 0; G.rx = 0
    C.draw()
    chk("cam: OFF by default (heading = facing)", C.camWanted() is False and C.cam.src == "off" and abs(C.h) < 1e-9)
    C.setCam(True)

    def frames(n, dt=1 / 60, read=False):
        for i in range(n):
            G.t = G.t + dt
            if read and i % 6 == 0:
                C.read(True)
            step(f, dt)

    CV = L.globals().CV
    rate = math.radians(120) * 1.3                       # the guessed base x GamePadCameraYawSpeed 1.3
    frames(30)
    G.rx = 1.0                                           # the right stick fully right for one second, the character standing
    frames(60)
    G.rx = 0
    frames(30)
    chk("cam: standing, the right stick orbits the camera: the compass turns clockwise %.0f degrees, the character did not turn (%.0f)"
        % (-math.degrees(C.wrap(C.h)), math.degrees(G.facing)), abs(C.wrap(C.h) + rate) < 0.05 and C.cam.src == "still" and C.cam["from"] == "pad")
    yaw = C.wrap(C.h)
    frames(120)
    chk("cam: the stick let go: the camera stays where it was (no fade back to the character)", abs(C.wrap(C.h) - yaw) < 0.01)
    CV.gamepadInvertYaw = "1"; C.cam.fovAt = -10
    G.rx = 1.0; frames(30); G.rx = 0; frames(30)
    chk("cam: gamepadInvertYaw: the same stick turns it the other way", C.wrap(C.h) > yaw + 0.3)
    CV.gamepadInvertYaw = "0"; C.cam.fovAt = -10
    # cp12: walking (the setting turns the character with the camera), the stick idle, the character straight: the facing only
    # ANCHORS the camera slowly (4 s); a turning character, or the stick in use, never pulls it
    y0 = C.wrap(C.h)
    G.speed = 7; G.facing = C.wrap(y0 + 1.0)
    frames(30)
    chk("cam: walking, the facing 1 rad off: no snap in 0.5 s (%.3f from the camera)" % abs(C.wrap(C.wrap(C.h) - y0)),
        abs(C.wrap(C.wrap(C.h) - y0)) < 0.05)
    frames(60 * 12)
    chk("cam: walking straight 12 s, the stick idle: anchored to the facing (%.3f off), src anchor" % abs(C.wrap(C.wrap(C.h) - G.facing)),
        abs(C.wrap(C.wrap(C.h) - G.facing)) < 0.1 and C.cam.src == "anchor")
    y1 = C.cam.abs                                       # (the camera model itself: the shown heading eases ~0.25 s behind it)
    G.rx = 1.0; frames(60); G.rx = 0
    chk("cam: walking AND orbiting: the stick turns the camera (%.0f deg), the facing does not pull it" % math.degrees(C.wrap(y1 - C.cam.abs)),
        abs(C.wrap(y1 - C.cam.abs) - rate) < 0.08)
    frames(30)
    G.speed = 0; G.fight = True; y2 = C.cam.abs; G.facing = C.wrap(y2 + 0.8); frames(30)
    chk("cam: in a fight (setting 3): no snap to the facing either", abs(C.wrap(C.cam.abs - y2)) < 0.05)
    G.fight = False
    CV.GamePadTurnWithCamera = "2"; C.cam.fovAt = -10; frames(60 * 14)
    chk("cam: setting 2 (always), standing: anchored slowly too (%.3f off)" % abs(C.wrap(C.wrap(C.h) - G.facing)),
        abs(C.wrap(C.wrap(C.h) - G.facing)) < 0.1)
    CV.GamePadTurnWithCamera = "3"; C.cam.fovAt = -10
    G.gp = False; frames(5)
    chk("cam: no game controller state: no stick (from none)", C.cam["from"] == "none")
    C.stickIn(1.0); G.rx = 0; y0 = C.wrap(C.h); frames(6)
    chk("cam: the app's stick reading plugs in (C.stickIn)", C.cam["from"] == "app" and C.wrap(C.h) < y0 - 0.05)
    G.gp = True

    # FIXES: standing, a quest NE (CCW -45 degrees) and the camera truly at -30 degrees: the nav point corrects the dead-reckoned yaw
    G.quest = L.table(0.6, 0.4); G.facing = 0.0
    C.setCam(True); frames(5, read=True)
    c0 = 1365.3334 / 2
    B = math.atan2(-100, 100)
    truecam = -math.radians(30)
    C.cam.abs = -math.radians(10)                        # the dead reckoning 20 degrees off
    G.nav = L.table_from({"x": c0 + c0 * math.tan(truecam - B), "y": 400.0, "clamped": False})
    frames(60, read=True)
    chk("cam: a fix corrects the dead-reckoned yaw to the true camera (%.3f, want %.3f), src nav" % (C.cam.abs, truecam),
        abs(C.cam.abs - truecam) < 0.01 and C.cam.src == "nav")
    # a mirrored (behind-the-camera) projection: the camera truly at -30, the quest put BEHIND it, its mirror near the centre
    G.quest = L.table(0.4, 0.6)                          # SW: behind
    Bb = math.atan2(100, -100)
    G.nav = L.table_from({"x": c0 + c0 * math.tan(C.wrap(truecam - Bb)), "y": 400.0, "clamped": False})
    frames(60, read=True)
    chk("cam: a mirrored projection never counts (src mirror, the yaw stays %.3f)" % C.cam.abs, C.cam.src == "mirror" and abs(C.cam.abs - truecam) < 0.02)
    # the rate is LEARNED: the true rate 60 deg/s (base 46 at speed 1.3; the guess 120); the stick held 0.5 s off screen, then a fix
    G.quest = L.table(0.6, 0.4)
    G.nav = L.table_from({"x": c0 + c0 * math.tan(truecam - B), "y": 400.0, "clamped": False})
    frames(30, read=True)
    G.nav = None
    G.rx = 1.0; frames(30, read=True); G.rx = 0
    cam2 = truecam - math.radians(60) * 0.5
    G.nav = L.table_from({"x": c0 + c0 * math.tan(cam2 - B), "y": 400.0, "clamped": False})
    frames(60, read=True)
    lb = math.degrees(C.cam.base or 0)
    chk("cam: the yaw rate is learned from two fixes (base 120 -> %.0f deg/s, toward the true %.0f), the yaw corrected" % (lb, 60 / 1.3),
        C.cam.base is not None and 60 / 1.3 - 1 < lb < 100 and abs(C.cam.abs - cam2) < 0.02)
    G.nav = None; G.quest = None

    # test20 / test21: a full turn of the CAMERA by the stick while walking, the character following it a little behind (the game's
    # turn lag), the quest's projections true to the camera (mirrored behind it): the compass spins with the camera, not the facing
    G.quest = L.table(0.6, 0.4); G.speed = 7; G.facing = 0.0; C.draw(); C.cam.abs = 0.0
    cam_true, hs, fs = 0.0, [], []
    for k in range(180):
        G.rx = -1.0                                        # stick left: the camera turns counter-clockwise
        cam_true = C.wrap(cam_true + (C.cam.base or C.cam.BASE) * 1.3 * (1 / 60) * 3)
        G.facing = C.wrap(cam_true - 0.6)                  # the character lags 0.6 rad behind the camera
        G.nav = L.table_from({"x": c0 + c0 * math.tan(C.wrap(cam_true - B)), "y": 400.0, "clamped": False})
        frames(3, read=True)
        hs.append(C.wrap(C.wrap(C.h) - cam_true)); fs.append(C.wrap(C.wrap(C.h) - G.facing))
    G.rx = 0
    worst = max(abs(h) for h in hs[10:])
    chk("test21: orbiting while walking: the compass stays on the camera (worst %.0f deg), never on the facing 34 deg behind it"
        % math.degrees(worst), worst < math.radians(10))
    G.speed = 0; G.nav = None; G.quest = None
    G.speed = 7; C.cam.abs = C.wrap(5.707); G.facing = 5.707; C.draw(); C.cam.abs = C.wrap(5.707)
    shown = []
    for fac in (5.096, 4.748, 4.319, 4.555):
        G.facing = fac
        frames(30)
        shown.append(C.wrap(C.h))
    jumps = [abs(C.wrap(shown[i] - shown[i - 1])) for i in range(1, len(shown))]
    chk("test21 walk (f 5.096 4.748 4.319 4.555, 0.5 s apart): no snap to f (largest step %.3f rad)" % max(jumps), max(jumps) < 0.1)
    G.speed = 0
    C.diagAt = -100; C.draw()
    last = L.globals().SENT[len(L.globals().SENT)]
    chk("cam: the compass line carries the camera's state (cam csrc cyaw gp from rx ys base learned tw fol inv nav nx ncl nvp nst fov)",
        last.probe == "compass" and last.cam is True and last.gp is True and last.tw == 3 and last.ys == 1.3 and last.base is not None
        and last.fov == 90 and last.cyaw is not None)
    chk("cam: still no CVar written", L.globals().CVAR_WRITES == 0)
    C.setCam(False); G.quest = L.table(0.6, 0.4); G.facing = math.radians(270); C.draw()
    # cp13: TEST22's chain (08:44:52 .. 08:45:17): standing, the stick idle, f 5.474, a verified camera at -0.251 (cyaw then);
    # the nav x drifting 986.8 1037.2 1074.4 958.6 986.9 implied 0.79 1.049 1.627 1.886: each "fix" was small against the last
    # one, and the yaw walked 120 degrees and then sat on a mirror. Now a fix may differ from the last fix (carried by the stick)
    # by 15 degrees + the stick's share: none of these count
    CV.GamePadTurnWithCamera = "1"; C.cam.fovAt = -10
    G.speed = 0; G.rx = 0; G.facing = 5.474; G.pin = None
    G.quest = L.table(0.6, 0.4)
    C.setCam(True); C.draw()
    C.cam.abs, C.cam.ref, C.cam.lastFix, C.cam.acc = -0.251, -0.251, -0.251, 0
    for impl, nx in ((0.79, 1037.2), (1.049, 1074.4), (1.627, 958.6), (1.886, 986.9)):
        # the quest put where that nav x implies a camera at `impl`
        bq = C.wrap(impl - math.atan((nx - c0) / c0))
        G.quest = L.table((500 - 100 * math.sin(bq)) / 1000, (500 - 100 * math.cos(bq)) / 1000)
        G.nav = L.table_from({"x": nx, "y": 400.0, "clamped": False})
        frames(60, read=True)
        chk("test22 chain: a 'fix' implying %.3f is refused (the camera stays %.3f, src %s)" % (impl, C.cam.abs, C.cam.src),
            abs(C.wrap(C.cam.abs + 0.251)) < 0.02 and C.cam.src != "nav")
    G.nav = None
    # 08:47:44: the estimate 2.69 with the character at 0.023, walking straight, the stick idle: the fast anchor closes it in ~1 s
    G.speed = 7; G.facing = 0.023; C.cam.abs = 2.69; C.cam.ref = 2.69
    frames(150, read=True)
    chk("test22 08:47:44: 2.67 rad off, walking straight: within 20 degrees in 2.5 s (%.0f deg off; cp12 took ~10 s)" % math.degrees(abs(C.wrap(C.cam.abs - 0.023))),
        abs(C.wrap(C.cam.abs - 0.023)) < math.radians(20))
    G.speed = 0

    # the WORLD MARKER: our own diamond on the engine's point, only with the target in front
    G.facing = 0.0; C.setCam(False); C.draw()
    G.quest = L.table(0.6, 0.4)                          # NE: 45 degrees right of north
    C.read(True)
    G.nav = L.table_from({"x": c0 + c0 * math.tan(math.radians(45) * 0.5), "y": 500.0, "clamped": False})
    G.facing = -math.radians(22.5)                       # the quest 22.5 degrees right of the camera
    frames(3, read=True)
    W = C.wm
    pt = W.f.point if W.f else None
    chk("world marker: shown on the point (x %.1f, y %.1f), the quest's atlas, its distance" % ((pt[4], pt[5]) if pt else (-1, -1)),
        W.shown is True and pt is not None and abs(pt[4] - G.nav.x) < 0.01 and abs(pt[5] - 500) < 0.01
        and W.tx.atlas == "Navigation-Tracked-Icon" and W.d.text == "140 yd")
    G.facing = C.wrap(-math.radians(22.5) + math.pi)    # turned round: the quest behind, its mirror unclamped near the centre
    frames(3, read=True)
    chk("world marker: hidden while the quest is behind (a mirrored point)", W.shown is False and W.f.shown is False)
    G.facing = -math.radians(22.5); G.nav.clamped = True
    frames(3, read=True)
    chk("world marker: hidden while the point is clamped", W.shown is False)
    G.nav.clamped = False
    L.execute("C_SuperTrack.IsSuperTrackingUserWaypoint = function() return true end")
    G.pin = L.table(0.6, 0.4)
    frames(3, read=True)
    chk("world marker: the route's pin super-tracked: the pin's atlas", W.shown is True and W.tx.atlas == "Waypoint-MapPin-Tracked")
    L.execute("C_SuperTrack.IsSuperTrackingUserWaypoint = function() return false end")
    C.set(False)
    chk("world marker: the compass switched off: hidden with it", W.f.shown is False)
    C.set(True); G.nav = None; G.pin = None; G.quest = L.table(0.6, 0.4); G.facing = math.radians(270); C.draw()

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
