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
function Region:SetText(t) self.text = t end
function Region:SetFont(f, s, fl) self.font, self.fsize, self.flags = f, s, fl end
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
UIParent = setmetatable({ name = "UIParent", hh = 768 }, Region)
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
    GetPlayerMapPosition = function() return CreateVector2D(GAME.px, GAME.py) end,
    GetWorldPosFromMapPos = function(m, v) local wx, wy = toWorld(v.x, v.y); return GAME.cont, CreateVector2D(wx, wy) end,
    GetUserWaypoint = function() if GAME.pin then return { uiMapID = GAME.map, position = CreateVector2D(GAME.pin[1], GAME.pin[2]) } end end,
}
C_SuperTrack = {
    IsSuperTrackingUserWaypoint = function() return false end,
    GetSuperTrackedQuestID = function() return GAME.quest and 123 or 0 end,
}
C_QuestLog = { GetNextWaypoint = function(q) if GAME.quest then return GAME.map, GAME.quest[1], GAME.quest[2] end end }
C_DeathInfo = { GetCorpseMapPosition = function() if GAME.corpse then return CreateVector2D(GAME.corpse[1], GAME.corpse[2]) end end }
WorldMapFrame = { IsShown = function() return GAME.worldmap end }
WowCompanion = {}
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
    chk("N and S at the ends (+-420 px)", abs(abs(ls["N"].x) - 420) < 0.01 and abs(abs(ls["S"].x) - 420) < 0.01 and ls["N"].x < 0)
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
    chk("the quest NE sits on NE's spot", abs(ms["quest"].x - ls["NE"].x) < 0.5)
    chk("the quest's distance rounds to 5 above 50 (141 -> 140 yd)", ms["quest"].dist == "140 yd")
    chk("LIFT: E under the pin lifts 25 px and shrinks 5", ls["E"].lifted and ls["E"].y == 32 - 25 and ls["E"].size == 17)
    chk("LIFT: NE under the quest lifts too", ls["NE"].lifted and ls["NE"].size == 12)
    chk("SE (no marker) stays on the line", not ls["SE"].lifted and ls["SE"].y == 32)

    # a corpse behind (west): pinned 48 px in from the LEFT or RIGHT end, flagged off-bar
    G.dead = True
    G.corpse = L.table(0.3, 0.52)
    ms = marks(lay())
    chk("the corpse behind is off the bar, pinned at +-402", ms["corpse"].off and abs(abs(ms["corpse"].x) - 402) < 0.01)
    ls = letters(lay())
    end = "S" if ms["corpse"].x > 0 else "N"
    chk("the end letter under the pinned corpse (%s) lifts too" % end, ls[end].lifted)
    G.dead = False
    chk("alive: no corpse marker", "corpse" not in marks(lay()))

    # turning: facing north puts the east pin at the right end, the NE quest at +210
    G.facing = 0.0
    ms = marks(lay())
    chk("facing north: the east pin at the right end (+420)", abs(ms["pin"].x - 420) < 0.5)
    chk("facing north: the NE quest halfway right (+210)", abs(ms["quest"].x - 210) < 0.5)

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
    chk("letters in FRIZQT, OUTLINE", fs and all(k.font == "Fonts\\FRIZQT__.TTF" and k.flags == "OUTLINE" for k in fs))
    k = 768 / 1080
    chk("the band is 900 x 76 px in UI units", abs(f.w - 900 * k) < 1e-6 and abs(f.h - 76 * k) < 1e-6)
    names = [n for n in ("SetBinding", "SetCVar", "SetRaidTarget", "SetOverrideBinding") if n in open(TRAIL, encoding="utf-8").read()]
    chk("no binding / CVar / raid mark calls in Trail.lua", not names)

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
