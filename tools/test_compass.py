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
CAL1 = os.path.join(ROOT, "tools", "cal1_replay.csv")
KB = 3.146                                                  # cp18: the measured camera rate (rad/s per GamePadCameraYawSpeed)
MAPLUA = os.path.join(ROOT, "companion", "app", "src", "main", "assets", "companion", "addons", "WowCompanion", "Map.lua")
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
function IsFalling() return GAME.air == true end
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
        return { stickCount = 2, sticks = { { x = GAME.lx or 0, y = GAME.ly or 0, len = 0 }, { x = GAME.rx, y = 0, len = math.abs(GAME.rx) } }, axes = {} }
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


# Map.lua's route-pin section, run alone (cp19): just what it calls
MAPSTUB = r'''
WC = { pad = { op = function(r, k, fn) UWP = fn end }, send = function() end, encode = function(t) SAID = t end, push = function() end }
T = 0; function GetTime() return T end; CALL = pcall; function currentMap() return 1429 end; function snapshotSoon() end
function superTrackedQuest() return 77 end; WowCompanionDB = {}; PIN = nil
C_Map = { HasUserWaypoint = function() return PIN ~= nil end, GetUserWaypoint = function() return PIN end,
          CanSetUserWaypointOnMap = function() return true end, SetUserWaypoint = function(p) PIN = p end, ClearUserWaypoint = function() PIN = nil end }
C_SuperTrack = { SetSuperTrackedUserWaypoint = function() end, SetSuperTrackedQuestID = function() end }
UiMapPoint = { CreateFromCoordinates = function(m, x, y) return { uiMapID = m, position = { x = x, y = y } } end }
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
    src = open(TRAIL, encoding="utf-8").read()
    names = [n for n in ("SetBinding", "SetRaidTarget", "SetOverrideBinding") if n in src]
    # the ONE approved game-setting exception (ag2, the user's "Add-on turns them on"): nameplateShowEnemies, written in one place
    cvar_lines = [l for l in src.splitlines() if "SetCVar(" in l and not l.strip().startswith("--")]
    chk("no binding / raid mark calls; the only CVar written is nameplateShowEnemies (%d lines)" % len(cvar_lines),
        not names and cvar_lines and all('"nameplateShowEnemies"' in l for l in cvar_lines))

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
    rate = KB * 1.3                                      # the measured rate x GamePadCameraYawSpeed 1.3
    frames(30)
    G.rx = 1.0                                           # the right stick fully right for half a second, the character standing
    frames(30)
    G.rx = 0
    frames(30)
    chk("cam: standing, the right stick orbits the camera: the compass turns clockwise %.0f degrees, the character did not turn (%.0f)"
        % (-math.degrees(C.wrap(C.h)), math.degrees(G.facing)), abs(C.wrap(C.h) + rate / 2) < 0.05 and C.cam.src == "still" and C.cam["from"] == "pad")
    yaw = C.wrap(C.h)
    frames(120)
    chk("cam: the stick let go: the camera stays where it was (no fade back to the character)", abs(C.wrap(C.h) - yaw) < 0.01)
    CV.gamepadInvertYaw = "1"; C.cam.fovAt = -10
    G.rx = 1.0; frames(30); G.rx = 0; frames(30)
    chk("cam: gamepadInvertYaw: the same stick turns it the other way", C.wrap(C.h - yaw) > 0.3)
    CV.gamepadInvertYaw = "0"; C.cam.fovAt = -10
    # cp12: walking (the setting turns the character with the camera), the stick idle, the character straight: the facing only
    # ANCHORS the camera slowly (4 s); a turning character, or the stick in use, never pulls it
    y0 = C.wrap(C.h)
    G.speed = 7; G.facing = C.wrap(y0 + 1.0)
    frames(30)
    chk("cam: walking, the facing 1 rad off: no snap in 0.5 s (%.3f from the camera)" % abs(C.wrap(C.wrap(C.h) - y0)),
        abs(C.wrap(C.wrap(C.h) - y0)) < 0.05)
    frames(60 * 12)
    chk("cp14: walking 12 s with the follow camera OFF: no anchor, the compass stays on the camera (%.3f from it)" % abs(C.wrap(C.wrap(C.h) - y0)),
        abs(C.wrap(C.wrap(C.h) - y0)) < 0.02 and C.cam.src == "still")
    CV.GamepadCameraFollowOnStick = "1"; C.cam.fovAt = -10
    frames(60 * 12)
    chk("cp14: the follow camera ON, walking forward-ish (1 rad off is over 45): still no anchor", abs(C.wrap(C.wrap(C.h) - y0)) < 0.02)
    G.facing = C.wrap(y0 + 0.5); frames(60 * 14)
    chk("cp14: the follow camera ON, walking forward (within 45): anchored slowly (%.3f off)" % abs(C.wrap(C.wrap(C.h) - G.facing)),
        abs(C.wrap(C.wrap(C.h) - G.facing)) < 0.1 and C.cam.src == "anchor")
    CV.GamepadCameraFollowOnStick = "0"; C.cam.fovAt = -10
    y1 = C.cam.abs                                       # (the camera model itself: the shown heading eases ~0.25 s behind it)
    G.rx = 1.0; frames(30); G.rx = 0
    chk("cam: walking AND orbiting: the stick turns the camera (%.0f deg), the facing does not pull it" % math.degrees(C.wrap(y1 - C.cam.abs)),
        abs(C.wrap(y1 - C.cam.abs) - rate / 2) < 0.08)
    frames(30)
    G.speed = 0; G.fight = True; y2 = C.cam.abs; G.facing = C.wrap(y2 + 0.8); frames(30)
    chk("cam: in a fight (setting 3): no snap to the facing either", abs(C.wrap(C.cam.abs - y2)) < 0.05)
    G.fight = False
    CV.GamePadTurnWithCamera = "2"; C.cam.fovAt = -10; y3 = C.cam.abs; G.facing = C.wrap(y3 + 0.8); frames(60 * 14)
    chk("cp14: setting 2 (always), standing, the follow camera off: no anchor (%.3f from the camera)" % abs(C.wrap(C.cam.abs - y3)),
        abs(C.wrap(C.cam.abs - y3)) < 0.02)
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
    # cp18, THE MEASURED LAW, FINE-TUNED (cal1: linear, 180 deg/s x the yaw speed): this camera turns 6 % faster than measured
    # (another device, say); out-and-back turns and full circles with the quest's marker seen between them tune the rate (the
    # exponent stays 1); then a full circle at half deflection lands on the same spot
    G.quest = L.table(0.6, 0.4); G.facing = 0.0; G.speed = 0; G.rx = 0
    if L.globals().WowCompanionDB.compassCal is not None:
        L.globals().WowCompanionDB.compassCal = None
    C.cam.kb, C.cam.p, C.cam.calN, C.cam.pairs, C.cam.base, C.cam.calLoaded = None, None, None, None, None, True
    C.setCam(True); C.draw()
    Bq = math.atan2(-100, 100)
    tk, tp = KB * 1.3 * 1.06, 1.0
    st = {"cam": Bq}                                     # the true camera, starting on the quest

    def show_nav():
        rel = C.wrap(st["cam"] - Bq)
        G.nav = L.table_from({"x": c0 + c0 * math.tan(rel), "y": 400.0, "clamped": False}) if abs(rel) < math.radians(40) else None

    def turn(x, secs):
        G.rx = x
        for i in range(int(round(secs * 60))):
            st["cam"] = C.wrap(st["cam"] - (1 if x > 0 else -1) * abs(x) ** tp * tk / 60)
            show_nav()
            G.t = G.t + 1 / 60
            if i % 6 == 0:
                C.read(True)
            step(f, 1 / 60)
        G.rx = 0

    def look(secs=1.0):
        for i in range(int(secs * 60)):
            show_nav()
            G.t = G.t + 1 / 60
            if i % 6 == 0:
                C.read(True)
            step(f, 1 / 60)

    C.cam.abs = st["cam"]; look()
    turn(1.0, 2 * math.pi / tk); look()                                          # a full circle at full deflection
    o1, b1 = 0.6, 0.8
    turn(o1, math.radians(120) / (tk * o1 ** tp)); turn(-b1, math.radians(120) / (tk * b1 ** tp)); look()   # out 120 at 0.6, back at 0.8
    o2, b2 = 0.4, 1.0
    turn(o2, math.radians(90) / (tk * o2 ** tp)); turn(-b2, math.radians(90) / (tk * b2 ** tp)); look()     # out 90 at 0.4, back at 1.0
    turn(0.7, math.radians(100) / (tk * 0.7 ** tp)); turn(-0.5, math.radians(100) / (tk * 0.5 ** tp)); look()
    chk("cp18: the rate tuned from %s lessons: exponent %s (stays 1), %.3f rad/s per yaw speed (true %.3f)"
        % (C.cam.calN, C.cam.p, C.cam.kb or 0, KB * 1.06),
        C.cam.p == 1 and abs(C.cam.kb - KB * 1.06) < KB * 0.02)
    # the user's test: a full circle at half deflection, the marker NOT seen until the end: the compass lands on the same spot
    st["cam"] = Bq; C.cam.abs = Bq; look()
    G.quest = None; C.read(True)
    turn(0.5, 2 * math.pi / (tk * 0.5 ** tp))
    miss = abs(C.wrap(C.cam.abs - st["cam"]))
    chk("cp18: a full 360 at half deflection lands within %.1f degrees of the same spot" % math.degrees(miss), miss < math.radians(5))
    chk("cp18: the tuned rate is kept in our saved data (v cp18)", L.globals().WowCompanionDB.compassCal.v == "cp18"
        and abs(L.globals().WowCompanionDB.compassCal.kb - C.cam.kb) < 1e-9)
    # a camera 50 % off the measurement is not believed past 10 %
    C.cam.pairs = None
    for _ in range(4):
        C.cam.pairs = (C.cam.pairs or L.table())
    L.execute("""local C = WowCompanion.compass; local K = C.cam
        K.pairs = {}; for i = 1, 5 do K.pairs[i] = { d = 1.5 * K.BASE * K.ys * i, I = { [1] = i } } end; C.fit()""")
    chk("cp18: lessons 50 %% off the measured rate tune it at most 10 %% (%.3f, measured %.3f)" % (C.cam.kb, KB),
        abs(C.cam.kb - KB * 1.1) < 1e-6)
    # a curve saved by cp16 / cp17 is not loaded
    L.globals().WowCompanionDB.compassCal = L.table_from({"kb": 2.0, "p": 3, "n": 9})
    C.cam.kb, C.cam.p = None, None; C.calLoad()
    chk("cp18: a cp16 / cp17 learned curve (p 3) in the saved data is not loaded", C.cam.kb is None and C.cam.p is None)
    chk("cp16: the line's self-check: the last lesson's residual (%s deg)" % (None if C.cam.lastRes is None else round(math.degrees(C.cam.lastRes))),
        C.cam.lastRes is not None)
    # (the next checks: the measured law, the tuned rate and its lessons cleared)
    L.globals().WowCompanionDB.compassCal = None
    C.cam.kb, C.cam.p, C.cam.calN, C.cam.pairs, C.cam.base = None, None, None, None, None
    G.quest = L.table(0.6, 0.4)
    G.nav = None; G.quest = None

    # cp18, THE REPLAY OF CAL1 (test32, the user's own calibration): the recorded right stick, frame by frame, through the add-on's
    # dead reckoning with NO fixes (the marker hidden from it); each stick stretch starts from the true yaw before it and is checked
    # against the true yaw wherever the quest's marker was on screen: full circles right / left at full and slow deflection, flicks
    rows = []
    for line in open(CAL1, encoding="utf-8"):
        if line.startswith("#") or not line.strip():
            continue
        a = line.strip().split(",")
        rows.append((int(a[0]) / 1000.0, int(a[1]) / 1000.0, None if a[2] == "" else int(a[2]) / 1e4))
    L.globals().WowCompanionDB.compassCal = None
    C.cam.kb, C.cam.p, C.cam.calN, C.cam.pairs, C.cam.base, C.cam.calLoaded = None, None, None, None, None, True
    G.quest = None; G.nav = None; G.speed = 0; G.rx = 0; G.facing = 0.0
    wins, i = [], 0
    while i < len(rows):
        if rows[i][1] != 0:
            j = i
            while j + 1 < len(rows) and (any(r[1] != 0 for r in rows[j + 1:j + 13])):
                j += 1
            a0 = i - 1
            while a0 > 0 and rows[a0][2] is None:
                a0 -= 1
            b0 = min(len(rows) - 1, j + 45)
            wins.append((a0, b0)); i = b0
        i += 1
    errs, ends, circles = [], [], 0
    for a0, b0 in wins:
        C.cam.abs = rows[a0][2]; C.cam.ref = None
        turned = 0.0
        for j in range(a0, b0):
            G.rx = rows[j][1]
            dt = rows[j + 1][0] - rows[j][0]
            G.t = G.t + dt
            step(f, dt)
            turned += abs(rows[j][1]) * KB * 1.3 * dt
            if rows[j + 1][2] is not None:
                errs.append(abs(C.wrap(C.cam.abs - rows[j + 1][2])))
        G.rx = 0
        ends.append(errs[-1])
        circles += 1 if turned > 1.8 * math.pi else 0
    rms = math.sqrt(sum(e * e for e in errs) / len(errs))
    chk("cp18: cal1 replayed: %d stick stretches (%d full circles), the true yaw at %d samples: %.1f deg rms, worst stretch end %.1f deg"
        % (len(wins), circles, len(errs), math.degrees(rms), math.degrees(max(ends))),
        len(wins) >= 14 and circles >= 4 and rms < math.radians(2.5) and max(ends) < math.radians(5))
    C.cam.ref, C.cam.obsCam, C.cam.I, C.cam.predSince, C.cam.grossSince = None, None, None, None, None   # (no lesson left from it)

    # test20 / test21: a full turn of the CAMERA by the stick while walking, the character following it a little behind (the game's
    # turn lag), the quest's projections true to the camera (mirrored behind it): the compass spins with the camera, not the facing
    L.globals().WowCompanionDB.compassCal = None                # (a clean law: the checks above set the camera by hand)
    C.cam.kb, C.cam.p, C.cam.calN, C.cam.pairs, C.cam.base = None, None, None, None, None
    G.quest = L.table(0.6, 0.4); G.speed = 7; G.facing = 0.0; C.draw(); C.cam.abs = 0.0
    cam_true, hs, fs = 0.0, [], []
    for k in range(540):
        G.rx = -1.0                                        # stick left: the camera turns counter-clockwise
        cam_true = C.wrap(cam_true + KB * 1.3 * (1 / 60))
        G.facing = C.wrap(cam_true - 0.6)                  # the character lags 0.6 rad behind the camera
        G.nav = L.table_from({"x": c0 + c0 * math.tan(C.wrap(cam_true - B)), "y": 400.0, "clamped": False})
        G.t = G.t + 1 / 60
        if k % 6 == 0:
            C.read(True)
        step(f, 1 / 60)
        if k % 3 == 2:
            # the camera model itself (the shown heading eases ~2v/omega behind a fast constant turn: the display, not the model)
            hs.append(C.wrap(C.cam.abs - cam_true)); fs.append(C.wrap(C.cam.abs - G.facing))
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
    # cp14, test23 (the user: "if I face north and begin walking south while still facing camera north, then the compass flips to
    # south after a delay"): the camera north, the character turned south walking toward it, the stick idle: north for 30 s and more
    G.speed = 7; G.nav = None; C.cam.abs = 0.0; C.cam.ref = 0.0; G.facing = math.pi
    frames(60 * 32, read=True)
    chk("test23: camera north, walking south toward it, 32 s: the compass stays north (%.3f rad)" % C.wrap(C.cam.abs), abs(C.wrap(C.cam.abs)) < 0.02)
    G.rx = 1.0; frames(30); G.rx = 0
    chk("test23: ... and a stick turn still turns it (%.0f deg)" % math.degrees(-C.wrap(C.cam.abs)), C.wrap(C.cam.abs) < -0.5)
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
    C.cam.abs = C.cam.ref = G.facing                     # (cp19: the camera's estimate runs with the bar on the facing too)
    frames(3, read=True)
    chk("world marker: hidden while the quest is behind (a mirrored point)", W.shown is False and W.f.shown is False)
    G.facing = -math.radians(22.5); G.nav.clamped = True
    C.cam.abs = C.cam.ref = G.facing
    frames(3, read=True)
    chk("world marker: hidden while the point is clamped", W.shown is False)
    G.nav.clamped = False
    L.execute("C_SuperTrack.IsSuperTrackingUserWaypoint = function() return true end")
    G.pin = L.table(0.6, 0.4)
    frames(3, read=True)
    chk("world marker: the route's pin super-tracked: the pin's atlas", W.shown is True and W.tx.atlas == "Waypoint-MapPin-Tracked")
    # cp19, THE JUMP (test33, the user: "when I jump it moves up and down"): the engine's point rises with the player; the marker's
    # height is held while airborne, eased back on landing, and a long fall is followed after 1.2 s
    G.nav.y = 500.0; frames(10, read=True)
    G.air = True; G.nav.y = 540.0; frames(30, read=True)
    chk("cp19: in a jump the marker's height is held (y %.1f, the engine's 540)" % W.f.point[5], abs(W.f.point[5] - 500) < 0.01 and W.air is True)
    chk("cp19: ... across it stays the engine's (x %.1f)" % W.f.point[4], abs(W.f.point[4] - G.nav.x) < 0.01)
    G.air = False; G.nav.y = 520.0; frames(1, read=True)
    y1 = W.f.point[5]
    frames(30, read=True)
    chk("cp19: landed (on a slope, the point now 520): eased, not snapped (%.1f after a frame, %.1f after 0.5 s)" % (y1, W.f.point[5]),
        500 < y1 < 510 and abs(W.f.point[5] - 520) < 0.5)
    G.air = True; G.nav.y = 600.0; frames(60, read=True)
    yh = W.f.point[5]
    frames(60, read=True)
    chk("cp19: a long fall is followed after the hold (%.1f at 1 s, %.1f at 2 s)" % (yh, W.f.point[5]), abs(yh - 520) < 0.5 and abs(W.f.point[5] - 600) < 1)
    G.air = False; G.nav.y = 500.0; frames(60, read=True)
    # cp19, THE FLAGS (the user: "they don't always show where the flags are at"): the app's further checkpoints (Map.lua's uwp 38)
    # get our own markers, across by the camera's yaw (exact from the engine's point) and the FOV, at the engine point's height
    L.execute("WowCompanion.routeWp = { active = true, more = { { m = GAME.map, x = 0.7, y = 0.35 }, { m = GAME.map, x = 0.4, y = 0.3 } } }")
    frames(3, read=True)
    m2 = W.list[2]
    want = c0 - c0 * math.tan(math.atan2(-200, 150) + math.radians(22.5))     # flag A: 250 yd at -53 degrees, the camera at -22.5
    chk("cp19: the next flag after the pin's: our marker at x %.1f (want %.1f), y %.1f (the engine's 500), smaller, fainter, '%s'"
        % (m2.f.point[4] if m2 else -1, want, m2.f.point[5] if m2 else -1, m2.d.text if m2 else ""),
        m2 is not None and m2.f.shown is True and abs(m2.f.point[4] - want) < 0.5 and abs(m2.f.point[5] - 500) < 0.1
        and m2.f.w < W.f.w and m2.f.alpha < 1 and m2.d.text == "250 yd")
    chk("cp19: the flag out of view (49 degrees left) has none; markers %d" % W.n, (W.list[3] is None or W.list[3].f.shown is False) and W.n == 2)
    G.nav = None; frames(3, read=True)
    m2 = W.list[2]
    chk("cp19: the engine's point gone: the flag stays, placed by the camera's estimate (x %.1f)" % m2.f.point[4],
        W.shown is False and m2.f.shown is True and abs(m2.f.point[4] - want) < 15)
    L.execute("WowCompanion.routeWp.active = false")
    frames(3, read=True)
    chk("cp19: the route's pin gone: no flags", W.n == 0 and W.list[2].f.shown is False)
    L.execute("WowCompanion.routeWp = nil")
    G.nav = L.table_from({"x": c0 + c0 * math.tan(math.radians(22.5)), "y": 500.0, "clamped": False})
    L.execute("C_SuperTrack.IsSuperTrackingUserWaypoint = function() return false end")
    C.set(False)
    chk("world marker: the compass switched off: hidden with it", W.f.shown is False)
    C.set(True); G.nav = None; G.pin = None; G.quest = L.table(0.6, 0.4); G.facing = math.radians(270); C.draw()

    # cp15, THE TRUE-UP (the user: "when ever the controls stick points directly up, we know the true direction that the camera is
    # currently facing ... very gently true up the compass"): the movement stick forward, the character walks along the camera's yaw
    G.nav = None; G.quest = None; G.pin = None
    L.globals().WowCompanionDB.compassCal = None                # (the law from the hand-set checks above cleared)
    C.cam.kb, C.cam.p, C.cam.calN, C.cam.pairs, C.cam.base = None, None, None, None, None
    C.setCam(True); G.facing = 0.5; C.draw(); C.read(True)
    srcs = set()

    def walk(head, secs, lx=0.0, ly=1.0, rx=0.0, speed=7.0):
        """move the player along world heading `head` (CCW from north) at `speed` yd/s for `secs` s, the sticks as given"""
        G.lx, G.ly, G.rx, G.speed = lx, ly, rx, speed
        steps, prev = [], C.cam.abs
        for i in range(int(secs * 60)):
            dxn, dyw = math.cos(head) * speed / 60, math.sin(head) * speed / 60
            G.px = G.px - dyw / 1000; G.py = G.py - dxn / 1000
            G.t = G.t + 1 / 60
            if i % 6 == 0:
                C.read(True)
            step(f, 1 / 60)
            srcs.add(C.cam.src)
            steps.append(abs(C.wrap(C.cam.abs - prev))); prev = C.cam.abs
        G.lx, G.ly, G.rx, G.speed = 0, 0, 0, 0
        return steps

    G.px, G.py = 0.5, 0.5
    truec = 0.5
    C.cam.abs = truec + math.radians(40); C.cam.ref = C.cam.abs; G.facing = truec
    st = walk(truec, 6)
    chk("cp15: 40 degrees of drift, walking straight forward 6 s: trued up to %.1f degrees, no step over 3 degrees a frame (max %.2f)"
        % (math.degrees(abs(C.wrap(C.cam.abs - truec))), math.degrees(max(st))),
        abs(C.wrap(C.cam.abs - truec)) < math.radians(5) and max(st) <= math.radians(3) + 1e-9)
    chk("cp15: src fwd while truing up (%s)" % sorted(srcs), "fwd" in srcs)
    C.cam.abs = truec + math.radians(40)
    walk(truec + math.pi / 2, 4, lx=1.0, ly=0.0)
    chk("cp15: walking SIDEWAYS (lx 1): never trues up", abs(C.wrap(C.cam.abs - truec - math.radians(40))) < 1e-6)
    walk(truec + math.pi, 4, lx=0.0, ly=-1.0)
    chk("cp15: walking BACKWARD (ly -1): never trues up", abs(C.wrap(C.cam.abs - truec - math.radians(40))) < 1e-6)
    a0 = C.cam.abs
    walk(truec, 1, rx=0.6)
    turned = C.wrap(a0 - C.cam.abs)
    chk("cp15: orbiting (the right stick) while walking forward: the stick turns it, no true-up (%.0f deg turned)" % math.degrees(turned),
        C.cam.src == "stick" and turned > math.radians(60))
    C.cam.abs = truec + math.radians(40); G.facing = truec
    walk(truec, 0.4)
    chk("cp15: forward only 0.4 s: not yet (0.5 s held first)", abs(C.wrap(C.cam.abs - truec - math.radians(40))) < 1e-6)
    G.lx, G.ly = 0.0, 1.0; G.speed = 7.0
    C.diagAt = -100
    for i in range(60):
        G.px = G.px - math.sin(truec) * 7 / 60 / 1000; G.py = G.py - math.cos(truec) * 7 / 60 / 1000
        G.t = G.t + 1 / 60
        if i % 6 == 0:
            C.read(True)
        step(f, 1 / 60)
    C.diagAt = -100; C.draw()
    G.lx, G.ly, G.speed = 0, 0, 0
    last = L.globals().SENT[len(L.globals().SENT)]
    chk("cp15: the compass line carries lx, ly (%s, %s)" % (last.lx, last.ly), last.probe == "compass" and last.lx == 0 and last.ly == 1)
    G.px, G.py = 0.5, 0.5; G.quest = L.table(0.6, 0.4); G.facing = math.radians(270); C.setCam(False); C.draw()

    # cp17: the quest's NUMBER on the compass (the app's: its place among the WATCHED quests in log order)
    L.execute("""
        QLOG = { { isHeader = true, title = "Elwynn" }, { questID = 10 }, { questID = 55, isHidden = true }, { questID = 77 }, { questID = 123 } }
        QWATCH = { [10] = 0, [55] = 0, [123] = 0 }
        C_QuestLog.GetNumQuestLogEntries = function() return #QLOG end
        C_QuestLog.GetInfo = function(i) return QLOG[i] end
        C_QuestLog.GetQuestWatchType = function(q) return QWATCH[q] end
    """)
    G.quest = L.table(0.6, 0.4); G.facing = math.radians(270); C.setCam(False); C.qnAt = -10; C.draw()
    mq = C.mk.quest
    chk("cp17: the focused quest 123 is the 2nd watched (10, [55 hidden], 77 unwatched, 123): '%s' on the POI number disc" % mq.num.text,
        mq.num.text == "2" and mq.num.shown is not False and mq.tx.atlas == "UI-QuestPoi-QuestNumber" and mq.d.shown is not False)
    L.execute("QWATCH[123] = nil")
    C.qnAt = -10; G.t = G.t + 2; C.draw()
    chk("cp17: an unwatched focus: the '!' as before, no number", mq.tx.atlas == "questnormal" and mq.num.shown is False)
    L.execute("QWATCH[123] = 0; C_QuestLog.GetNumQuestLogEntries = nil")

    # cp17: the CALIBRATION recording (cal1): one shot, armed by the marker centred and the stick idle 3 s, 120 s at 30 Hz
    Q = C.cal
    L.globals().WowCompanionDB.calProbe = None
    qf = [fr for fr in [L.globals().FRAMES[i] for i in range(1, len(L.globals().FRAMES) + 1)] if fr.scripts and fr.scripts.OnUpdate and fr.events and fr.events.PLAYER_LOGOUT and not (fr.events.PLAYER_ENTERING_WORLD)][-1]
    def qframes(n):
        for i in range(n):
            G.t = G.t + 1 / 60
            qf.scripts.OnUpdate(qf, 1 / 60)
    G.nav = None; G.rx = 0
    qframes(240)
    chk("cal1: nothing without the quest's marker on screen", not Q.on)
    G.nav = L.table_from({"x": c0 + 200, "y": 400.0, "clamped": False}); qframes(240)
    chk("cal1: nothing while the marker is off centre", not Q.on)
    G.nav.x = c0 + 10; qframes(120)
    chk("cal1: centred but only 2 s: not yet", not Q.on)
    n0 = len(L.globals().SENT)
    qframes(70)
    chk("cal1: centred 3 s with the stick idle: recording", Q.on is True)
    calsent = [L.globals().SENT[i] for i in range(n0 + 1, len(L.globals().SENT) + 1) if L.globals().SENT[i].probe == "cal1"]
    st = [x for x in calsent if x.op == "start"]
    chk("cal1: the start line: every gamepad / camera CVar READ (GamePadCameraYawSpeed %s), the columns" % (st and st[0].cv.GamePadCameraYawSpeed),
        st and st[0].cv.GamePadCameraYawSpeed == "1.3" and "rx" in st[0].cols)
    G.rx = 1.0
    qframes(60)
    G.rx = 0
    lines = [L.globals().SENT[i] for i in range(n0 + 1, len(L.globals().SENT) + 1) if L.globals().SENT[i].probe == "cal1" and L.globals().SENT[i].op == "s"]
    first = lines[0].s[1].split(",") if lines else []
    chk("cal1: ~30 samples a second, 10 a line (%d samples after ~1 s; a sample: %s)" % (Q.n, ",".join(first[:6])),
        25 <= Q.n <= 40 and all(len(l.s) == 10 for l in lines))
    chk("cal1: the countdown on the compass (%s)" % (Q.label and Q.label.text), Q.label is not None and Q.label.text.startswith("CAL "))
    qframes(60 * 121)
    ends = [L.globals().SENT[i] for i in range(n0 + 1, len(L.globals().SENT) + 1) if L.globals().SENT[i].probe == "cal1" and L.globals().SENT[i].op == "end"]
    chk("cal1: ends after 120 s (%s samples), marked done, the countdown gone" % (ends and ends[0].n),
        len(ends) == 1 and 3400 < ends[0].n < 3700 and L.globals().WowCompanionDB.calProbe == "cal1" and Q.label.shown is False)
    n1 = len(L.globals().SENT); qframes(400)
    chk("cal1: never again this set", len(L.globals().SENT) == n1)
    chk("cal1: no CVar written", L.globals().CVAR_WRITES == 0)
    G.nav = None; G.quest = L.table(0.6, 0.4); G.facing = math.radians(270); C.draw()

    # cp19: Map.lua's route-pin row, the further checkpoints (38 + 4 digits each; 38 38 drops them; a new SET drops them)
    src = open(MAPLUA, encoding="utf-8").read()
    a0 = src.index("WC.routeWp = {")
    b0 = src.index("do" + chr(10) + "    local f = CreateFrame", a0)
    M = lupa.LuaRuntime()
    M.execute(MAPSTUB + src[a0:b0])
    tapm = lambda *cols: [M.eval("UWP")(None, c) for c in cols]
    tapm(36, 24, 30, 11, 5)
    tapm(38, 25, 0, 12, 0, 38, 26, 0, 13, 0)
    R = M.eval("WC.routeWp")
    mv = [(R.more[i].x, R.more[i].y, R.more[i].m) for i in range(1, len(R.more) + 1)]
    chk("cp19: uwp 38: two further checkpoints after the pin, in order (%s)" % [(round(x, 3), round(y, 3)) for x, y, _ in mv],
        R.active is True and len(mv) == 2 and abs(mv[0][0] - 900 / 1295) < 1e-9 and abs(mv[1][1] - 468 / 1295) < 1e-9 and mv[0][2] == 1429)
    tapm(38, 38)
    chk("cp19: uwp 38 38 drops them", len(R.more) == 0 and R.active is True)
    tapm(38, 25, 0, 12, 0); tapm(36, 24, 31, 11, 5)
    chk("cp19: a new SET drops them (the app sends the ones after it again)", len(M.eval("WC.routeWp").more) == 0)
    tapm(38, 1, 2, 3, 4, 38, 1, 2, 3, 5, 38, 1, 2, 3, 6, 38, 1, 2, 3, 7)
    chk("cp19: at most 3", len(M.eval("WC.routeWp").more) == 3)
    tapm(39)
    chk("cp19: CLEAR drops them with the pin", len(M.eval("WC.routeWp").more) == 0 and M.eval("WC.routeWp").active is False)
    # ag5: the app ROUTING with no pin up (a direct route, a refused pin): its KEEP says so for 60 s; CLEAR ends it
    chk("ag5: no pin, no word: not routing", M.eval("WC.routeWp.routing()") is False)
    M.execute("T = 100"); tapm(37)
    chk("ag5: a KEEP with no pin: routing", M.eval("WC.routeWp.routing()") is True and M.eval("WC.routeWp").active is False)
    M.execute("T = 159"); r59 = M.eval("WC.routeWp.routing()")
    M.execute("T = 161")
    chk("ag5: ... for 60 s (59 s: %s, 61 s: %s)" % (r59, M.eval("WC.routeWp.routing()")), r59 is True and M.eval("WC.routeWp.routing()") is False)
    tapm(37); tapm(39)
    chk("ag5: CLEAR ends it at once", M.eval("WC.routeWp.routing()") is False)

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
