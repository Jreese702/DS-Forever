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
-- cp22: the jump press (a binding's function) and the add-ons' hook on it (as the client's: the original, then the hook)
JUMPS = 0
function JumpOrAscendStart() JUMPS = JUMPS + 1 end
function hooksecurefunc(name, fn) local o = _G[name]; _G[name] = function(...) local r = o(...); fn(...); return r end end
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
        return { stickCount = 2, sticks = { { x = GAME.lx or 0, y = GAME.ly or 0, len = 0 }, { x = GAME.rx, y = GAME.ry or 0, len = math.abs(GAME.rx) } }, axes = {} }
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
    chk("cp23: letter sizes 14 / 12 (were 22 / 17)", ls["E"].size == 14 and ls["NE"].size == 12)

    # a pin 100 yd due east of the player: in the middle, "100 yd"; the quest 141 yd north-east
    G.pin = L.table(0.6, 0.5)
    G.quest = L.table(0.6, 0.4)
    lo = lay()
    ms, ls = marks(lo), letters(lo)
    chk("the pin due east sits on the centre", abs(ms["pin"].x) < 0.5 and not ms["pin"].off)
    chk("its distance: 100 yd", ms["pin"].dist == "100 yd")
    chk("the quest NE sits on NE's spot (its spot from GetQuestsOnMap, as on the Thor)", abs(ms["quest"].x - ls["NE"].x) < 0.5 and C.qsrc == "onmap")
    chk("the quest's distance rounds to 5 above 50 (141 -> 140 yd)", ms["quest"].dist == "140 yd")
    chk("LIFT: E under the pin lifts 25 px and shrinks 2 (cp23)", ls["E"].lifted and ls["E"].y == 32 - 25 and ls["E"].size == 12)
    chk("LIFT: NE under the quest lifts too", ls["NE"].lifted and ls["NE"].size == 10)
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
    tex = sorted({k.tex.split("\\")[-1] for k in shown if getattr(k, "tex", None)})
    chk("cp27: the markers are our art: the player's pin our gold pin, the quest's objective our gold disc (%s)" % ", ".join(tex),
        {"marker_pin", "quest_disc"} <= set(tex))
    fs = [k for k in shown if k.kind == "fs" and k.text in ("N", "NE", "E", "SE", "S")]
    endN = [k for k in fs if k.text == "N"]
    midE = [k for k in fs if k.text == "E"]
    chk("letters fade by ALPHA toward the ends (N at the end %.2f, E centred %.2f)" % (endN[0].alpha, midE[0].alpha),
        endN and midE and endN[0].alpha < 0.25 and abs(midE[0].alpha - 0.9) < 1e-9)
    tick = [kk for kk in shown if kk.kind != "fs" and not kk.atlas and kk.w and kk.col]
    tc = tuple(round(tick[0].col[i], 4) for i in (1, 2, 3)) if tick else None
    chk("cp23: every letter (N too) in the ticks' own light colour %s" % (tc,),
        tc is not None and all(tuple(round(kk.col[i], 4) for i in (1, 2, 3)) == tc for kk in fs))
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
    chk("world marker: shown on the point (x %.1f, y %.1f), cp27: the quest's objective our gold number disc, its distance" % ((pt[4], pt[5]) if pt else (-1, -1)),
        W.shown is True and pt is not None and abs(pt[4] - G.nav.x) < 0.01 and abs(pt[5] - 500) < 0.01
        and W.tx.tex.endswith("quest_disc") and W.f.point[1] == "BOTTOM" and W.list[1].num.shown is True and W.d.text == "140 yd")
    G.facing = C.wrap(-math.radians(22.5) + math.pi)    # turned round: the quest behind, its mirror unclamped near the centre
    C.cam.abs = C.cam.ref = G.facing                     # (cp19: the camera's estimate runs with the bar on the facing too)
    frames(60, read=True)                                # (cp24: held 0.6 s, then a 0.25 s fade)
    chk("world marker: hidden while the quest is behind (a mirrored point)", W.shown is False and W.f.shown is False)
    G.facing = -math.radians(22.5); G.nav.clamped = True
    C.cam.abs = C.cam.ref = G.facing
    frames(3, read=True)
    chk("world marker: hidden while the point is clamped", W.shown is False)
    G.nav.clamped = False
    L.execute("C_SuperTrack.IsSuperTrackingUserWaypoint = function() return true end")
    G.pin = L.table(0.6, 0.4)
    frames(40, read=True)                                # (cp24: the quest's marker fades out, then the pin's in)
    chk("cp22: the route's pin super-tracked: our pin, gold, its tip on the point", W.shown is True and W.tx.tex.endswith("marker_pin")
        and abs(W.tx.col[2] - 0.82) < 1e-9 and W.f.point[1] == "BOTTOM")
    # cp19, THE FLAGS (the user: "they don't always show where the flags are at"): the app's further checkpoints (Map.lua's uwp 38)
    # get our own markers, across by the camera's yaw (exact from the engine's point) and the FOV, at the engine point's height
    L.execute("WowCompanion.routeWp = { active = true, more = { { m = GAME.map, x = 0.7, y = 0.35 }, { m = GAME.map, x = 0.4, y = 0.3 } } }")
    frames(30, read=True)                                # (cp26: the second fades in over 0.4 s)
    m2 = W.list[2]
    want = c0 - c0 * math.tan(math.atan2(-200, 150) + math.radians(22.5))     # flag A: 250 yd at -53 degrees, the camera at -22.5
    relA = math.atan2(-200, 150) + math.radians(22.5)
    wantY = 384 + C.wmProj(W.pitch, 12.5, 1.9, 250 * math.cos(relA), 0, c0) if W.pitch else -1
    chk("cp19/cp20: the next flag after the pin's: x %.1f (want %.1f), y %.1f ON THE GROUND (want %.1f), smaller, fainter, '%s'"
        % (m2.f.point[4] if m2 else -1, want, m2.f.point[5] if m2 else -1, wantY, m2.d.text if m2 else ""),
        m2 is not None and m2.f.shown is True and abs(m2.f.point[4] - want) < 0.5 and abs(m2.f.point[5] - wantY) < 0.5
        and m2.f.w < W.f.w and m2.f.alpha < 1 and m2.d.text == "250 yd")
    chk("cp19: the flag out of view (49 degrees left) has none; markers %d" % W.n, (W.list[3] is None or W.list[3].f.shown is False) and W.n == 2)
    # cp20, THE GROUND (the user: "other times they float in the air ... Try to ground them all"): the pitch solved from the
    # engine's point (a camera pitched 0.35 rad over a pivot 1.9 yd up, 12.5 yd out), the flags placed by their own heights
    def proj(phi, dfwd, dz, D=12.5, H=1.9):
        vf = dfwd + D * math.cos(phi)
        vu = dz - H - D * math.sin(phi)
        return c0 * (vf * math.sin(phi) + vu * math.cos(phi)) / (vf * math.cos(phi) - vu * math.sin(phi))
    dpin = math.hypot(100, 100) * math.cos(math.radians(22.5))
    G.nav.y = 384 + proj(0.35, dpin, 0)
    frames(120, read=True)                               # (past the earlier jump's landing ease)
    chk("cp20: the pitch from the engine's point: %.4f rad (true 0.35)" % (W.pitch or -1), W.pitch is not None and abs(W.pitch - 0.35) < 1e-3)
    # cp22, THE JUMP (the user: "when I jump they need to stay anchored"): the camera rises with the character; the engine's point is
    # at the player's height (it rises too: on screen it stays, or wanders), the ground does not. Each marker must stay on its own
    # ground point: the true projection with the camera risen h(t) (WoW's arc: 7.9558 yd/s up, 19.2911 down)
    VJ, GJ = 7.9558, 19.2911
    yStand = 384 + proj(0.35, dpin, 0)
    camKeep = (C.cam.abs, C.cam.ref)                    # (this block's seconds must not move the camera estimate the later checks use)
    fA = 250 * math.cos(relA)
    frames(10, read=True)
    L.execute("JumpOrAscendStart()")
    G.air = True
    errs, errsF, oldErr, ts = [], [], [], []
    for i in range(48):                                  # 0.8 s: up to the apex and down
        G.nav.y = yStand + (25 if i % 8 < 4 else -15)    # (the engine's point wandering in the air: it must not matter)
        frames(1, read=True)
        t = G.t - W.airSince
        h = max(0.0, VJ * t - GJ * t * t / 2)
        wantJ = 384 + proj(0.35, dpin, -h)
        errs.append(abs(W.f.point[5] - wantJ))
        oldErr.append(abs(yStand - wantJ))                # cp21 held the standing height: off by this
        wantF = 384 + proj(0.35, fA, -h)
        errsF.append(abs(W.list[2].f.point[5] - wantF))
        ts.append(h)
    chk("cp22: a jump (apex %.2f yd): the pin on its ground point all through (largest error %.3f px; the old hold was off %.1f px)"
        % (max(ts), max(errs), max(oldErr)), W.kind == "jump" and abs(max(ts) - 1.64) < 0.02 and max(errs) < 0.05 and max(oldErr) > 5)
    chk("cp22: ... the further flag on its own ground too (largest error %.3f px)" % max(errsF), max(errsF) < 0.05)
    chk("cp22: ... across, the engine's (yaw) as before (x %.1f)" % W.f.point[4], abs(W.f.point[4] - G.nav.x) < 0.01)
    n0 = len(L.globals().SENT)
    frames(1, read=True)
    G.air = False; G.nav.y = yStand; frames(1, read=True)
    yL = W.f.point[5]
    frames(12, read=True)
    jl = [L.globals().SENT[i] for i in range(n0 + 1, len(L.globals().SENT) + 1) if getattr(L.globals().SENT[i], "probe", None) == "jump"]
    chk("cp22: landed at the arc's end: on the ground, no hop (%.2f px off on landing, %.3f 0.2 s later)" % (abs(yL - yStand), abs(W.f.point[5] - yStand)),
        abs(yL - yStand) < 1.5 and abs(W.f.point[5] - yStand) < 0.25)
    chk("cp22: the landing logs the jump for the device (air %.2f s, kind %s, press %s)"
        % ((jl[0].air, jl[0].kind, jl[0].press) if jl else (-1, None, None)), len(jl) == 1 and abs(jl[0].air - 0.82) < 0.04
        and jl[0].kind == "jump" and jl[0].press == L.globals().JUMPS)
    frames(60, read=True)
    # A FALL (a step off a ledge: no press): no arc; the ground's height held all the way down, not followed
    G.air = True
    ys = []
    for i in range(120):
        G.nav.y = yStand + 60 * i / 120                  # (the engine's point rides down with us)
        frames(1, read=True)
        ys.append(W.f.point[5])
    chk("cp22: a 2 s fall off a ledge: held (%.2f..%.2f px, the engine's went to %.0f)" % (min(ys), max(ys), G.nav.y),
        W.kind == "fall" and max(ys) - min(ys) < 0.01 and abs(ys[0] - yStand) < 0.01)
    G.air = False; G.nav.y = yStand + 40; frames(1, read=True)
    y1 = W.f.point[5]
    frames(30, read=True)
    chk("cp22: the fall's landing: eased to the new ground (%.1f after a frame, %.1f after 0.5 s; the point %.1f)"
        % (y1, W.f.point[5], G.nav.y), yStand < y1 < yStand + 10 and abs(W.f.point[5] - G.nav.y) < 0.5)
    G.nav.y = yStand; frames(60, read=True)
    # LANDING EARLY on a slope: up 0.3 s (1.5 yd high), then down on ground 20 px further up the screen: no snap, eased there
    L.execute("JumpOrAscendStart()")
    G.air = True; frames(18, read=True)
    yAir = W.f.point[5]
    G.air = False; G.nav.y = yStand + 20; frames(1, read=True)
    y1 = W.f.point[5]
    frames(30, read=True)
    chk("cp22: an early landing on a slope (in the air %.1f, a frame after %.1f, 0.5 s after %.1f; the ground %.1f): eased"
        % (yAir, y1, W.f.point[5], G.nav.y), yAir < yStand - 5 and yAir < y1 < yAir + 0.25 * (G.nav.y - yAir) + 1
        and abs(W.f.point[5] - G.nav.y) < 0.5)
    G.nav.y = yStand; frames(60, read=True)
    # SWIMMING: no compensation (not airborne); MOUNTED: the arc (its own constant)
    L.execute("function IsSwimming() return true end; JumpOrAscendStart()")
    G.air = True; frames(12, read=True)
    chk("cp22: swimming: no compensation (y %.1f, the engine's %.1f)" % (W.f.point[5], G.nav.y), W.air is False and abs(W.f.point[5] - yStand) < 0.01)
    G.air = False; L.execute("IsSwimming = nil; function IsMounted() return true end"); frames(30, read=True)
    L.execute("JumpOrAscendStart()")
    G.air = True; frames(25, read=True)
    t = G.t - W.airSince
    h = 7.9558 * t - GJ * t * t / 2
    chk("cp22: mounted: the mount's arc (%.2f px off its ground)" % abs(W.f.point[5] - 384 - proj(0.35, dpin, -h)),
        W.mounted is True and abs(W.f.point[5] - 384 - proj(0.35, dpin, -h)) < 0.05)
    G.air = False; L.execute("IsMounted = nil"); frames(60, read=True)
    C.cam.abs, C.cam.ref = camKeep
    L.execute("WowCompanion.routeWp.more[1].dz = 6")
    frames(2, read=True)
    m2 = W.list[2]
    wantY = 384 + proj(0.35, 250 * math.cos(relA), 6)
    chk("cp20: a flag 6 yd above us, 250 yd out: y %.1f (the true projection %.1f)" % (m2.f.point[5], wantY), abs(m2.f.point[5] - wantY) < 0.5)
    L.execute("WowCompanion.routeWp.pinZ = { dz = -3 }")
    frames(2, read=True)
    wantP = 384 + proj(0.35, dpin, -3)
    chk("cp20: the pin's own ground 3 yd below us: the main marker moved onto it (y %.1f, the projection %.1f; the engine's %.1f)"
        % (W.f.point[5], wantP, G.nav.y), abs(W.f.point[5] - wantP) < 0.5 and W.pinDz == -3)
    chk("cp22: the further flags are our pin too (gold, faint)", W.list[2].tx.tex.endswith("marker_pin") and W.list[2].f.alpha < 1
        and W.list[2].f.point[1] == "BOTTOM")
    L.execute("WowCompanion.routeWp.pinZ = nil; WowCompanion.routeWp.more[1].dz = nil")
    G.nav = None; frames(3, read=True)
    m2 = W.list[2]
    chk("cp19: the engine's point gone: the flag stays a moment, placed by the camera's estimate (x %.1f)" % m2.f.point[4],
        W.shown is False and m2.f.shown is True and abs(m2.f.point[4] - want) < 15)
    G.ry = 0.6; frames(2, read=True); G.ry = 0
    G.nav = L.table_from({"x": c0 + c0 * math.tan(math.radians(22.5)), "y": 384 + proj(0.35, dpin, 0), "clamped": False})
    G.ry = 0.6; C.wm.pitchAt = -100; frames(1, read=True)
    G.ry = 0; G.nav = None; C.wm.tilted = True; frames(60, read=True)                  # (cp24: held, then a fade)
    chk("cp20: the camera tilted since the pitch was read: the flags hide rather than float", W.list[2].f.shown is False and W.tilted is True)
    G.nav = L.table_from({"x": c0 + c0 * math.tan(math.radians(22.5)), "y": 384 + proj(0.35, dpin, 0), "clamped": False})
    frames(3, read=True)
    chk("cp20: a new pitch from the engine's point: back", W.list[2].f.shown is True and W.tilted is False)
    # cp24, STABLE (test60, the user: "a little glitchy and temperamental, sometimes flashing in and out of existence")
    nx, ny = G.nav.x, G.nav.y

    def navset(x=None):
        G.nav = L.table_from({"x": nx if x is None else x, "y": ny, "clamped": False})
    frames(30, read=True)
    seq = []
    for kind in ("nil", "clamp", "nil", "clamp", "nil"):
        if kind == "nil":
            G.nav = None
        else:
            G.nav.clamped = True
        frames(1, read=True)
        seq.append((W.f.shown, W.f.alpha, W.list[2].f.shown, W.f.point[4]))
        navset()
        frames(4, read=True)
        seq.append((W.f.shown, W.f.alpha, W.list[2].f.shown, W.f.point[4]))
    chk("cp24: one-frame dropouts (no point, clamped) never blink the markers: %s"
        % sorted({(a, round(b, 2), c) for a, b, c, _ in seq}),
        all(a is True and abs(b - 1) < 1e-9 and c is True and abs(x - nx) < 0.01 for a, b, c, x in seq))
    # a super-track flip (a moment off our pin while the route is active): held at the pin, never jumping to the quest's point
    L.execute("C_SuperTrack.IsSuperTrackingUserWaypoint = function() return false end")
    navset()                                             # (the engine now projecting the quest - here on the pin's own spot)
    xs = []
    for _ in range(6):
        frames(1, read=True)
        xs.append((W.f.shown, round(W.f.point[4], 1), W.list[2].f.shown))
    L.execute("C_SuperTrack.IsSuperTrackingUserWaypoint = function() return true end")
    navset()
    frames(10, read=True)
    chk("cp24: a 0.1 s super-track flip: the pin held where it was, the flags too (%s)" % (xs[0],),
        all(a is True and abs(x - nx) < 0.1 and c is True for a, x, c in xs))
    # a tilt of the camera stick with the pin on screen: the pitch is solved each frame, the flags stay
    G.ry = 0.6
    tl = []
    for _ in range(20):
        frames(1, read=True)
        tl.append(W.list[2].f.shown is True and abs(W.list[2].f.alpha - 0.75) < 1e-9)
    G.ry = 0
    chk("cp24: tilting the camera with the pin on screen: the flags stay (%d of 20 frames)" % sum(tl), all(tl))
    # a SUSTAINED loss: held HOLD_S (0.6 s), then a 0.25 s fade, then hidden; the hide's reason counted; a wmvis line
    frames(10, read=True)
    W.visAt = G.t
    W.vis = L.table_from({"show": 0, "hide": 0, "held": 0, "why": L.table()})
    G.nav = None
    al = []
    for _ in range(60):
        frames(1, read=True)
        al.append((round(G.t - W.visAt, 3), W.f.alpha if W.f.shown else 0.0))
    held = [a for t, a in al if t < 0.58]
    fade = [a for t, a in al if 0.62 < t < 0.84]
    chk("cp24: the point gone for 1 s: held at full for 0.6 s (%d frames), then fading (%s), then hidden"
        % (len(held), ", ".join("%.2f" % a for a in fade[::4])),
        all(abs(a - 1) < 1e-9 for a in held) and fade and all(0 < a < 1 for a in fade)
        and all(fade[i] >= fade[i + 1] for i in range(len(fade) - 1)) and al[-1][1] == 0 and W.f.shown is False)
    chk("cp24: ... the hide counted with its reason (%s, %d held frames)" % (W.vis.why.nonav, W.vis.held),
        W.vis.hide >= 1 and W.vis.why.nonav == 1 and W.vis.held > 30)
    n0 = len(L.globals().SENT)
    navset()
    frames(60 * 10, read=True)
    vl = [L.globals().SENT[i] for i in range(n0 + 1, len(L.globals().SENT) + 1) if getattr(L.globals().SENT[i], "probe", None) == "wmvis"]
    chk("cp24: one 'wmvis' line per 10 s: shows %s, hides %s, why %s" % ((vl[0].show, vl[0].hide, dict(vl[0].why)) if vl else (None,) * 3),
        len(vl) == 1 and vl[0].hide >= 1 and vl[0].show >= 1 and vl[0].why.nonav == 1)
    # Map's pin advancing: the further flags' list emptied (and resent): they fade, never blink
    L.execute("SAVEDMORE = WowCompanion.routeWp.more; WowCompanion.routeWp.more = {}")
    fl = []
    for _ in range(24):
        frames(1, read=True)
        fl.append(W.list[2].f.alpha if W.list[2].f.shown else 0.0)
    L.execute("WowCompanion.routeWp.more = SAVEDMORE")
    frames(30, read=True)
    chk("cp24: the flags' list emptied (the pin advanced): a fade (%s), not a blink; back after" % ", ".join("%.2f" % a for a in fl[::4]),
        0.5 < fl[0] < 0.85 and fl[-1] == 0 and all(fl[i] >= fl[i + 1] for i in range(len(fl) - 1)) and W.list[2].f.shown is True)
    # Map's "arrived" (the route's pin CLEARED on purpose, the quest super-tracked): the pin fades out WHERE IT WAS, then the quest's
    # own marker fades in at its point: never shown-hidden-shown in a frame, never a jump while seen
    L.execute("C_SuperTrack.IsSuperTrackingUserWaypoint = function() return false end; WowCompanion.routeWp.active = false")
    navset(nx - 200)
    sw = []
    for _ in range(40):
        frames(1, read=True)
        sw.append((W.f.alpha if W.f.shown else 0.0, round(W.f.point[4], 1)))
    k0 = next((i for i, (a, _) in enumerate(sw) if a == 0), None)
    chk("cp24: the pin cleared (arrived): out at the pin (%.0f), in at the quest (%.0f), alpha down then up"
        % (sw[0][1], sw[-1][1]),
        k0 is not None and all(abs(x - nx) < 0.1 for a, x in sw[:k0])
        and all(sw[i][0] >= sw[i + 1][0] for i in range(k0 - 1)) and all(sw[i][0] <= sw[i + 1][0] for i in range(k0, len(sw) - 1))
        and abs(sw[-1][1] - (nx - 200)) < 0.1 and abs(sw[-1][0] - 1) < 1e-9 and W.tx.tex.endswith("quest_disc"))
    # no target at all: a fade at once (no hold)
    G.quest = None
    C.read(True)
    nt = []
    for _ in range(20):
        frames(1, read=True)
        nt.append(W.f.alpha if W.f.shown else 0.0)
    chk("cp24: no target at all: a fade at once, no hold (%s)" % ", ".join("%.2f" % a for a in nt[::4]),
        nt[0] < 1 and nt[-1] == 0)
    G.quest = L.table(0.6, 0.4)
    L.execute("C_SuperTrack.IsSuperTrackingUserWaypoint = function() return true end; WowCompanion.routeWp.active = true")
    navset()
    frames(60, read=True)
    # the in-front gate's margin: shown within 45 + 10 degrees of the camera, KEPT to 45 + 16 (the estimate's yaw wobbling at the
    # edge no longer blinks it), lost past that
    fk = (G.facing, C.cam.abs, C.cam.ref)
    bq = C.bearing(C.me_, C.tg_.pin)[0]

    def at(deg, n=3):
        G.facing = C.wrap(bq - math.radians(deg)); C.cam.abs = C.cam.ref = G.facing
        frames(n, read=True)
        return W.f.shown is True and W.shown is True
    e1, e2, e3 = at(52), at(59), at(52)
    e4 = at(64)
    e5 = W.f.shown is True                               # (lost: held a moment first)
    frames(60, read=True)
    chk("cp24: the in-front margin: 52 deg shown %s, 59 kept %s, 52 %s; 64 lost %s (held %s, then gone %s)"
        % (e1, e2, e3, not e4, e5, W.f.shown is False), e1 and e2 and e3 and not e4 and e5 and W.f.shown is False)
    e6 = at(59, 30)
    chk("cp24: ... and from hidden, 59 deg does not show it (the show gate is 55): %s" % (not e6), not e6)
    G.facing, C.cam.abs, C.cam.ref = fk
    frames(60, read=True)
    # cp26 (test63, the user: "I always want the world and the compass to show the next two markers ... fade as I get closer to it
    # and then once it vanishes fully the next one after begins to fade in beyond the 2nd marker"). A WALK down a route: the player
    # walks north at 7 yd/s through four checkpoints to the quest's objective; the app's own rule moves the pin on (DangerGrid's
    # 25 yd, 0.3 s later), and Map.lua swaps its further list the way it does (the old kept as moreOld, the new a moment later)
    L.execute("""
        QLOG = { { questID = 123 } }
        QWATCH = { [123] = 0 }
        C_QuestLog.GetNumQuestLogEntries = function() return #QLOG end
        C_QuestLog.GetInfo = function(i) return QLOG[i] end
        C_QuestLog.GetQuestWatchType = function(q) return QWATCH[q] end
        C_SuperTrack.IsSuperTrackingUserWaypoint = function() return true end
    """)
    keepW = (G.px, G.py, G.facing, C.cam.abs, C.cam.ref, G.pin, G.quest)
    G.facing = 0.0; C.cam.abs = C.cam.ref = 0.0
    G.px, G.py = 0.5, 0.5                                # world (0, 0)

    def mp(X, Y):                                        # world (x north, y west) -> the stub map's (x, y)
        return ((500 - Y) / 1000, (500 - X) / 1000)
    CPS = [(70, 10), (130, -12), (190, 8), (250, -10)]
    GOAL = (310, 0)
    G.quest = L.table(*mp(*GOAL))
    st = {"k": 0, "passAt": None, "moreAt": None}

    def setRoute(k, swap):
        L.globals().GAME.pin = L.table(*mp(*CPS[k]))
        more = (CPS + [GOAL])[k + 1:k + 3]                # (the app keeping a GOAL route's end as its last point: cp27's ask)
        lst = "{" + ",".join("{ m = GAME.map, x = %r, y = %r }" % mp(*c) for c in more) + "}"
        if swap:
            L.execute("local R = WowCompanion.routeWp; R.moreOld, R.moreOldAt, R.more = R.more, GetTime(), {}")
            st["moreAt"], st["lst"] = G.t + 0.2, lst
        else:
            L.execute("WowCompanion.routeWp.more = " + lst)
    L.execute("WowCompanion.routeWp = { active = true, qid = 123, MORE_MAX = 2, more = {} }")
    setRoute(0, False)

    def me_xy():
        return (500 - G.py * 1000, 500 - G.px * 1000)

    def engine():
        R = L.globals().WowCompanion.routeWp
        tgt = None
        if R.active and G.pin:
            tgt = CPS[st["k"]]
        elif G.quest:
            tgt = GOAL
        if tgt is None:
            G.nav = None
            return
        X, Y = me_xy()
        dx, dy = tgt[0] - X, tgt[1] - Y
        d = math.hypot(dx, dy)
        rel = math.atan2(dy, dx) - C.cam.abs
        if abs(rel) > math.radians(40) or d < 3:
            G.nav = None
            return
        G.nav = L.table_from({"x": c0 - c0 * math.tan(rel), "y": 384 + proj(0.35, d * math.cos(rel), 0), "clamped": False})
    log = []
    C.read(True)
    engine()
    frames(60, read=True)                                # (standing a moment: the earlier markers out, these in, the heading north)
    for i in range(60 * 48):
        X, Y = me_xy()
        R = L.globals().WowCompanion.routeWp
        # cp29: the player walks the route (toward the pin, then the objective) at 7 yd/s
        tx, ty = CPS[st["k"]] if R.active else GOAL
        dd = math.hypot(tx - X, ty - Y)
        if dd > 2:
            G.py = G.py - (tx - X) / dd * 7 / 60 / 1000
            G.px = G.px - (ty - Y) / dd * 7 / 60 / 1000
        # the app: the pin moves on 0.3 s after the player is within its pass radius (cp31: 10 yd) of it; past the last, the route ends
        if R.active:
            pc = CPS[st["k"]]
            if math.hypot(pc[0] - X, pc[1] - Y) <= 10 and st["passAt"] is None:
                st["passAt"] = G.t + 0.3
            if st["passAt"] is not None and G.t >= st["passAt"]:
                st["passAt"] = None
                if st["k"] + 1 < len(CPS):
                    st["k"] += 1
                    setRoute(st["k"], True)
                else:
                    L.execute("WowCompanion.routeWp.active = false; WowCompanion.routeWp.more = {}; "
                              "C_SuperTrack.IsSuperTrackingUserWaypoint = function() return false end")
                    G.pin = None
        if st["moreAt"] is not None and G.t >= st["moreAt"]:
            L.execute("WowCompanion.routeWp.more = " + st["lst"])
            st["moreAt"] = None
        engine()
        frames(1, read=(i % 6 == 0))
        S = C.RT.st
        row = {"t": G.t, "X": me_xy()[0], "pair": [C.RT.pair[j].id for j in range(1, len(C.RT.pair) + 1)] if C.RT.pair else [], "st": {}}
        for id_ in list(S.keys()):
            s_ = S[id_]
            o = W.obj[id_]
            k_ = C.rk[id_]
            row["st"][id_] = {"a": s_.a, "r": s_.r, "rank": s_.rank, "d": s_.d, "kind": s_.kind,
                              "wa": (o.f.alpha if (o and o.f.shown) else 0.0), "wx": (o.f.point[4] if (o and o.f.point) else None),
                              "wy": (o.f.point[5] if (o and o.f.point) else None), "wt": (o.d.text if o else None),
                              "ca": (k_.d.alpha if (k_ and k_.d.shown) else 0.0), "ct": (k_.d.text if k_ else None),
                              "cx": (k_.pin.point[4] if (k_ and k_.pin.point) else None),
                              "cdisc": bool(k_ and k_.disc.shown), "cpin": bool(k_ and k_.pin.shown),
                              "cdia": bool(k_ and k_.dia.shown), "wdia": bool(o and o.dia.shown), "wpin": bool(o and o.tx.shown)}
        log.append(row)
    ids = ["cp:%d:%d" % (math.floor(x + 0.5), math.floor(y + 0.5)) for x, y in CPS]
    gid = "goal:123"
    mid = [r for r in log if r["X"] < CPS[-1][0] - 11]                       # mid-route: before the last checkpoint is passed
    two = sum(1 for r in mid if len(r["pair"]) == 2)
    one = min(sum(1 for v in r["st"].values() if v["a"] > 0.02) for r in mid)
    chk("cp26: mid-route, always TWO in the pair (%d of %d frames) and never fewer than one shown (at least %d)" % (two, len(mid), one),
        two == len(mid) and one >= 1)
    mono = True
    zeroAt = {}
    for cid in ids:
        seq = [(r["st"][cid]["d"], r["st"][cid]["a"]) for r in log if cid in r["st"] and r["st"][cid]["rank"] == 1]
        for (d0, a0), (d1, a1) in zip(seq, seq[1:]):
            if d1 < d0 - 1e-9 and a1 > a0 + 1e-9:
                mono = False
        zeroAt[cid], seen, last = None, False, None       # (gone: the first frame after being shown with it at 0 or dropped)
        for r in log:
            v = r["st"].get(cid)
            if v and v["a"] > 0:
                seen, last = True, v["d"]
            elif seen:
                zeroAt[cid] = (r["t"], last)
                break
    chk("cp26: the nearer one fades with distance, monotonically, to 0 at the app's pass radius, 10 yd (cp31: full to 25 yd; gone at %s yd)"
        % ", ".join("%.1f" % v[1] if v else "-" for v in zeroAt.values()),
        mono and all(v is not None and 9 <= v[1] <= 11.5 for v in zeroAt.values()))       # (positions read at 10 Hz: ~0.7 yd steps)
    order = True
    for j in range(2, len(ids)):
        first = [r["t"] for r in log if ids[j] in r["st"] and r["st"][ids[j]]["a"] > 0]
        gone = zeroAt.get(ids[j - 2])
        if not first or not gone or first[0] < gone[0] - 1e-9:
            order = False
    chk("cp26: each new one (k+2) fades in only once k has gone", order)
    fadeIn = [r["st"][ids[2]]["a"] for r in log if ids[2] in r["st"] and r["st"][ids[2]]["rank"] == 2][:30]
    chk("cp26: ... and it fades in (over 0.4 s: %s)" % ", ".join("%.2f" % v for v in fadeIn[::6]),
        fadeIn and fadeIn[0] < 0.1 and fadeIn[-1] >= 0.99 and all(fadeIn[i] <= fadeIn[i + 1] for i in range(len(fadeIn) - 1)))
    jumpW = jumpC = 0.0
    for r0, r1 in zip(log, log[1:]):
        for id_, v1 in r1["st"].items():
            v0 = r0["st"].get(id_)
            if v0 and v0["wa"] > 0 and v1["wa"] > 0 and v0["wx"] is not None and v1["wx"] is not None:
                jumpW = max(jumpW, abs(v1["wx"] - v0["wx"]), abs(v1["wy"] - v0["wy"]))
            if v0 and v0["ca"] > 0 and v1["ca"] > 0 and v0["cx"] is not None and v1["cx"] is not None:
                jumpC = max(jumpC, abs(v1["cx"] - v0["cx"]))
    chk("cp26: no jumps: a shown marker moves at most %.1f px a frame in the world, %.2f on the compass" % (jumpW, jumpC),
        jumpW < 12 and jumpC < 3)
    agree = all(v["wt"] == v["ct"] for r in log for v in r["st"].values() if v["wa"] > 0 and v["ca"] > 0)
    both = sum(1 for r in log for v in r["st"].values() if v["wa"] > 0 and v["ca"] > 0)
    chk("cp26: the compass and the world agree: the same ones, the same distances (%d marker-frames compared)" % both, agree and both > 1000)
    ranks = [r["st"][ids[1]]["r"] for r in log if ids[1] in r["st"]]
    chk("cp26: k+1 becomes the nearer without a jump (its rank eased %.2f .. %.2f)" % (max(ranks), min(ranks)),
        max(ranks) == 2 and min(ranks) == 1 and all(abs(b_ - a_) <= 1 / 60 / 0.4 + 1e-9 for a_, b_ in zip(ranks, ranks[1:])))
    # the END: the last checkpoint and the objective; then the objective only (the route ended: the same marker, never faded out)
    endp = [r for r in log if gid in r["pair"]]
    lastTwo = [r for r in endp if r["pair"] == [ids[-1], gid]]
    onlyG = [r for r in log if r["pair"] == [gid]]
    ga = [r["st"][gid]["a"] for r in log if gid in r["st"] and r["st"][gid]["rank"]]
    gStart = ga.index(1.0) if 1.0 in ga else None
    chk("cp26: the end: the last checkpoint + the objective (%d frames), then the objective only (%d frames), never faded once full"
        % (len(lastTwo), len(onlyG)), lastTwo and onlyG and gStart is not None and min(ga[gStart:]) == 1.0
        and not L.globals().WowCompanion.routeWp.active)
    k1 = C.rk[gid]
    chk("cp26: at the end the objective is the quest's disc with its number on the compass ('%s'), the same disc in the world"
        % (k1.num.text if k1 else None), k1 is not None and k1.disc.shown is True and k1.num.text == "1"
        and W.obj[gid].tx.tex.endswith("quest_disc") and W.obj[gid].num.shown is True and W.obj[gid].num.text == "1")
    # the objective joins the list only when the app sent every checkpoint left (fewer than its 2), or one stands on it
    L.execute("C_SuperTrack.IsSuperTrackingUserWaypoint = function() return true end")
    G.pin = L.table(*mp(*CPS[0]))

    def lst(more):
        L.execute("WowCompanion.routeWp = { active = true, qid = 123, MORE_MAX = 3, more = {" +
                  ",".join("{ m = GAME.map, x = %r, y = %r }" % mp(*c) for c in more) + "} }")
        C.read(True)
        return [C.rt_[i].id for i in range(1, len(C.rt_) + 1)]
    l2, l1, l0, lg = lst(CPS[1:3]), lst(CPS[1:2]), lst([]), lst([CPS[1], (GOAL[0] + 25, GOAL[1])])
    chk("cp27: the objective joins ONLY where the app's list reaches it: 2 sent %s; 1 sent (a way's leg) %s; none %s; the last ON it %s"
        % (l2, l1, l0, lg), gid not in l2 and gid not in l1 and gid not in l0 and lg == [ids[0], ids[1], gid]
        and C.rt_[3].why == "onmore2")
    cpLook = [(v["r"], v["cpin"], v["cdia"], v["cdisc"]) for r in log for id_, v in r["st"].items() if id_ != gid and v["ca"] > 0]
    gLook = [(v["cpin"], v["cdia"], v["cdisc"]) for r in log for id_, v in r["st"].items() if id_ == gid and v["ca"] > 0]
    chk("cp32: on the compass the CURRENT checkpoint the diamond, the NEXT the pin (both only while the rank eases), never the disc "
        "(%d marker-frames); the objective the disc only (%d)" % (len(cpLook), len(gLook)),
        cpLook and all((dia and not pin) if r_ == 1 else (pin and not dia) if r_ == 2 else (pin and dia) for r_, pin, dia, disc in cpLook)
        and not any(disc for *_, disc in cpLook) and gLook and all(d and not p and not i for p, i, d in gLook))
    wLook = [(v["r"], v["wpin"], v["wdia"]) for r in log for id_, v in r["st"].items() if id_ != gid and v["wa"] > 0]
    chk("cp32: in the world the same: the current the diamond, the next the pin (%d marker-frames)" % len(wLook),
        wLook and all((dia and not pin) if r_ == 1 else (pin and not dia) if r_ == 2 else (pin and dia) for r_, pin, dia in wLook))
    # cp28 (test66: "most times I can't see anything"; the log: st false, n 0-1): mid-route with the game super-tracking the QUEST
    # (not our pin) - the engine's point is the quest's, neither of the two shown. The camera's yaw and pitch come from it anyway:
    # checkpoints 80 and 130 yd ahead are shown at once, at their projections, the nearer at full alpha, the second at 0.75
    G.px, G.py = 0.5, 0.5
    G.facing = 0.0; C.cam.abs = C.cam.ref = 0.0
    P1, P2, Q = (80, 5), (130, -8), (300, 40)
    G.quest = L.table(*mp(*Q)); G.pin = L.table(*mp(*P1))
    L.execute("C_SuperTrack.IsSuperTrackingUserWaypoint = function() return false end")
    L.execute("WowCompanion.routeWp = { active = true, qid = 123, more = { { m = GAME.map, x = %r, y = %r } } }" % mp(*P2))

    def navAt(t):
        rel = math.atan2(t[1], t[0])
        d = math.hypot(*t)
        G.nav = L.table_from({"x": c0 - c0 * math.tan(rel), "y": 384 + proj(0.35, d * math.cos(rel), 0), "clamped": False})
    navAt(Q)
    n0 = len(L.globals().SENT)
    frames(90, read=True)
    i1, i2 = "cp:80:5", "cp:130:-8"
    o1, o2 = W.obj[i1], W.obj[i2]

    def want(t):
        rel = math.atan2(t[1], t[0])
        return c0 - c0 * math.tan(rel), 384 + proj(0.35, math.hypot(*t) * math.cos(rel), 0)
    w1, w2 = want(P1), want(P2)
    chk("cp28: st false mid-route: the 80 yd checkpoint SHOWN at full alpha (%.2f) at its spot (%.1f, %.1f; want %.1f, %.1f)"
        % (o1.f.alpha if o1 else -1, o1.f.point[4] if o1 else -1, o1.f.point[5] if o1 else -1, w1[0], w1[1]),
        o1 is not None and o1.f.shown is True and abs(o1.f.alpha - 1) < 1e-9 and abs(o1.f.point[4] - w1[0]) < 0.5
        and abs(o1.f.point[5] - w1[1]) < 0.5 and W.engKind == "quest" and W.engId is None)
    chk("cp28: ... the 130 yd one shown too at 0.75 (%.2f) at its spot (%.1f; want %.1f)" % (o2.f.alpha if o2 else -1,
        o2.f.point[4] if o2 else -1, w2[0]), o2 is not None and o2.f.shown is True and abs(o2.f.alpha - 0.75) < 1e-9
        and abs(o2.f.point[4] - w2[0]) < 0.5 and abs(o2.f.point[5] - w2[1]) < 0.5)
    G.nav = None
    frames(60 * 10, read=True)
    chk("cp28: ... and still both shown 10 s after the engine's point left the screen (the pitch kept)", o1.f.shown is True and o2.f.shown is True)
    mk = [L.globals().SENT[i] for i in range(n0 + 1, len(L.globals().SENT) + 1) if getattr(L.globals().SENT[i], "probe", None) == "wmk"]
    last = mk[-1] if mk else None
    ms = {last.m[i].id: last.m[i] for i in range(1, len(last.m) + 1)} if last else {}
    chk("cp28: a 'wmk' line a second (%d in 11.5 s) with each one's distance, alphas, spot and how: %s" % (len(mk),
        [(k_, v.d, v.a, v.wa, v.x, v.how) for k_, v in ms.items()]),
        10 <= len(mk) <= 13 and ms.get(i1) and ms[i1].d == 80 and ms[i1].a == 100 and ms[i1].wa == 100 and ms[i1].how == "est"
        and ms[i1].x is not None and ms.get(i2) and ms[i2].wa == 75 and last.eng == "quest")
    # cp29 (test68: the second marker "EDGE" standing still: rank 1 at 58 yd on the engine's point, rank 2 at 113 yd, pitch 19.6):
    # the 113 yd one ON screen at its spot; an app height far off (40 yd up) is not allowed to lift it over the top
    G.px, G.py = 0.5, 0.5
    G.facing = 0.0; C.cam.abs = C.cam.ref = 0.0
    PH = math.radians(19.6)
    A1, A2 = (58, -14), (113, 20)
    G.pin = L.table(*mp(*A1))
    L.execute("C_SuperTrack.IsSuperTrackingUserWaypoint = function() return true end")
    L.execute("WowCompanion.routeWp = { active = true, qid = 123, more = { { m = GAME.map, x = %r, y = %r } } }" % mp(*A2))

    def at(t, ph):
        rel = math.atan2(t[1], t[0])
        return c0 - c0 * math.tan(rel), 384 + proj(ph, math.hypot(*t) * math.cos(rel), 0)
    ex, ey = at(A1, PH)
    G.nav = L.table_from({"x": ex, "y": ey, "clamped": False})
    frames(90, read=True)
    j1, j2 = "cp:58:-14", "cp:113:20"
    q1, q2 = W.obj[j1], W.obj[j2]
    w2 = at(A2, PH)
    chk("cp29: pitch 19.6 (solved %.2f): the 113 yd second marker ON screen at its spot (%.1f, %.1f; want %.1f, %.1f), 0.75; the first on the engine's"
        % (math.degrees(W.pitch), q2.f.point[4] if q2 else -1, q2.f.point[5] if q2 else -1, w2[0], w2[1]),
        q2 is not None and q2.f.shown is True and abs(q2.f.point[4] - w2[0]) < 0.5 and abs(q2.f.point[5] - w2[1]) < 0.5
        and abs(q2.f.alpha - 0.75) < 1e-9 and q1.f.shown is True and abs(q1.f.point[4] - ex) < 0.5 and W.engId == j1)
    L.execute("WowCompanion.routeWp.more[1].dz = 40")
    n0 = len(L.globals().SENT)
    frames(70, read=True)
    mk = [L.globals().SENT[i] for i in range(n0 + 1, len(L.globals().SENT) + 1) if getattr(L.globals().SENT[i], "probe", None) == "wmk"]
    e2 = None
    if mk:
        for i in range(1, len(mk[-1].m) + 1):
            if mk[-1].m[i].id == j2:
                e2 = mk[-1].m[i]
    chk("cp29: an app height 40 yd up (it would put it at y %s, over the top): placed on our own ground instead (%.1f), shown, logged dzBad"
        % (e2.ry if e2 else None, q2.f.point[5]), q2.f.shown is True and abs(q2.f.point[5] - w2[1]) < 0.5 and e2 is not None
        and e2.dzBad is True and e2.dz == 40 and e2.how == "est")
    L.execute("WowCompanion.routeWp.more[1].dz = nil")
    # THE FADE (the user: "the fade out should be 25 to 0 yards"): full to 25 yd, gone at the pass radius (4 yd)
    L.execute("WowCompanion.routeWp.more = {}")
    fa = []
    for dd in (40, 25, 17.5, 12, 10.6):
        G.py = (500 - (A1[0] - dd)) / 1000; G.px = (500 - A1[1]) / 1000
        C.read(True)
        frames(90, read=True)
        stj = C.RT.st[j1]
        fa.append((dd, round(stj.a, 3) if stj else None))
    G.py = (500 - (A1[0] - 9.5)) / 1000; C.read(True); frames(30, read=True)
    a35 = C.RT.st[j1].a
    chk("cp31: the nearer one full to 25 yd, then fading to 0 at 10 yd (%s; at 9.5 yd %.2f, cp30: still the first until the app moves on)"
        % (fa, a35), fa[0][1] == 1 and fa[1][1] == 1 and abs(fa[2][1] - 0.5) < 0.01 and abs(fa[3][1] - 2 / 15) < 0.01 and fa[4][1] < 0.05
        and a35 == 0 and C.RT.pair[1].id == j1)
    # cp30 (test69: "first pin 55 yards, the next one after 1555 yards"; the pin 23 -> 7 yd away missing): the player overshoots
    # the pin (within its pass radius) and the app keeps it (a re-plan onto the same bend): walking back it shows again, still the FIRST
    L.execute("WowCompanion.routeWp.more = { { m = GAME.map, x = %r, y = %r } }" % mp(*A2))
    G.py = (500 - (A1[0] - 23)) / 1000; C.read(True); frames(60, read=True)
    p23 = [C.RT.pair[i].id for i in range(1, len(C.RT.pair) + 1)]
    a23 = C.RT.st[j1].a
    G.py = (500 - (A1[0] - 13)) / 1000; C.read(True); frames(60, read=True)
    a7 = C.RT.st[j1].a
    chk("cp30: an overshot pin the app keeps: back at 23 yd it is the first again (%s, alpha %.2f; at 13 yd %.2f) - never a further one first"
        % (p23, a23, a7), p23 == [j1, j2] and abs(a23 - 13 / 15) < 0.01 and abs(a7 - 3 / 15) < 0.01)
    # a PHANTOM further point (1555 yd off, from the pin's 58 yd) between two good ones: skipped, logged; the good one after kept
    L.execute("WowCompanion.routeWp.more = { { m = GAME.map, x = %r, y = %r }, { m = GAME.map, x = %r, y = %r } }"
              % (mp(1600, 30) + mp(*A2)))
    C.read(True); frames(10, read=True)
    ids30 = [C.rt_[i].id for i in range(1, len(C.rt_) + 1)]
    bad = [C.RT.bad[i] for i in range(1, len(C.RT.bad) + 1)]
    chk("cp30: a phantom further point (1542 yd past the pin) is dropped and logged (%s); the good one after it kept: %s" % (bad, ids30),
        ids30 == [j1, j2] and len(bad) == 1 and bad[0].startswith("cp:1600:30@") and C.lastDiag is not None)
    G.px, G.py, G.facing, C.cam.abs, C.cam.ref, G.pin, G.quest = keepW
    L.execute("""C_SuperTrack.IsSuperTrackingUserWaypoint = function() return true end
                 WowCompanion.routeWp = { active = true, more = { { m = GAME.map, x = 0.7, y = 0.35 }, { m = GAME.map, x = 0.4, y = 0.3 } } }
                 C_QuestLog.GetNumQuestLogEntries = nil""")
    G.pin = L.table(0.6, 0.4); G.quest = L.table(0.6, 0.4); C.qnAt = -10
    navset()
    frames(60, read=True)
    G.nav = None; frames(60 * 4, read=True)
    chk("cp28: the engine's point gone 4 s: the pitch keeps (only a tilt moves it), the second still shown", W.list[2].f.shown is True)
    G.ry = 0.6; frames(60, read=True); G.ry = 0
    chk("cp20: ... a tilt of the camera since: hidden (held, then a fade) rather than floating", W.list[2].f.shown is False and W.tilted is True)
    L.execute("WowCompanion.routeWp.active = false")
    frames(30, read=True)
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
    mq = C.rk["goal:123"]                                # (cp26: the objective on the band: the route markers' disc)
    chk("cp17: the focused quest 123 is the 2nd watched (10, [55 hidden], 77 unwatched, 123): '%s' on the POI number disc" % mq.num.text,
        mq.num.text == "2" and mq.num.shown is not False and mq.disc.tex.endswith("quest_disc") and mq.d.shown is not False)
    # cp23 (test60: "missing the number in the circle"): this client's number disc is DARK brown; the number must be light on it,
    # outlined, over the disc's layer, its text set and shown
    nc = [mq.num.col[i] for i in (1, 2, 3)]
    lum = 0.2126 * nc[0] + 0.7152 * nc[1] + 0.0722 * nc[2]
    chk("cp27: the number DARK on our gold disc, the classic look (luminance %.2f), OVERLAY over the disc, '%s' shown"
        % (lum, mq.num.text), lum < 0.15 and mq.num.flags == "" and mq.num.layer == "OVERLAY" and mq.disc.layer == "ARTWORK"
        and mq.num.text == "2" and mq.num.shown is True and mq.num.fsize and mq.num.fsize > 0
        and mq.num.point and abs(mq.num.point[4] - mq.disc.point[4]) < 1e-9 and abs(mq.num.point[5] - mq.disc.point[5]) < 1e-9)
    L.execute("QWATCH[123] = nil")
    C.qnAt = -10; G.t = G.t + 2; C.draw()
    chk("cp27: an unwatched focus: a '!' on the gold disc ('%s')" % mq.num.text, mq.num.text == "!" and mq.num.shown is True
        and mq.disc.tex.endswith("quest_disc"))
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
    chk("cp26: ... the dropped ones kept a moment as moreOld (%d)" % len(R.moreOld), len(R.moreOld) == 2)
    tapm(38, 25, 0, 12, 0); tapm(36, 24, 31, 11, 5)
    R = M.eval("WC.routeWp")
    chk("cp19: a new SET drops them (the app sends the ones after it again); cp26: kept a moment as moreOld (%d)" % len(R.moreOld),
        len(R.more) == 0 and len(R.moreOld) == 1)
    tapm(38, 1, 2, 3, 4, 38, 1, 2, 3, 5, 38, 1, 2, 3, 6, 38, 1, 2, 3, 7)
    chk("cp19: at most 3", len(M.eval("WC.routeWp").more) == 3)
    tapm(39)
    chk("cp19: CLEAR drops them with the pin (cp26: moreOld too)", len(M.eval("WC.routeWp").more) == 0 and M.eval("WC.routeWp").active is False
        and M.eval("WC.routeWp").moreOld is None)
    # cp20: 2 height digits may follow a point's 4 (relative to the player, 0.25 yd steps, +648; 1295 unknown)
    tapm(36, 24, 30, 11, 5, 18, 8)                       # the pin 2 yd above us (656 = 18 * 36 + 8)
    tapm(38, 25, 0, 12, 0, 17, 36 - 1)                   # a flag at 647 (17 * 36 + 35): -0.25 yd
    tapm(38, 26, 0, 13, 0, 35, 35)                       # a flag of unknown height (1295)
    tapm(38, 26, 1, 13, 1)                               # an older app: no height digits
    R = M.eval("WC.routeWp")
    chk("cp20: the pin's height %s, the flags' %s / %s / %s (4 points kept, the 4-digit one too)"
        % (R.pinZ and R.pinZ.dz, R.more[1].dz, R.more[2].dz, R.more[3] and R.more[3].dz),
        R.pinZ.dz == 2.0 and R.more[1].dz == -0.25 and R.more[2].dz is None and len(R.more) == 3 and R.more[3].dz is None)
    tapm(39)
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
