"""cp36 THE PATH MARKER (the user, 2026-11-11: "The polestar doted line needs its own kind of marker to point on the compass to keep
players on path so that they don't resort to walking at the final objective"), off-device.

    python tools/test_compass_path.py

Trail.lua's compass under test_compass.py's stub game, with a stub Polestar route (Map.lua's WC.pnav.legs: {k, c, p = {wx, wy, ...}}):
- C.pathAhead: the look-ahead point AHEAD yd on along the route from the player's nearest point, advancing as the player moves,
  round a corner, never back onto a passed segment, re-started by a new route; a flight leg's start (its distance) once the walk
  reaches it, gone at it and aboard / on a taxi; the hand-over to the objective within HAND yd ALONG the route (not the straight line);
  another continent: none;
- C.pathTarget: only while WC.pnav is active with a fresh route read;
- the drawing: the path marker's own look (light blue lines, a double chevron, no texture of the objective's) beside the objective's
  gold disc, unchanged; hidden when the route goes;
- Map.lua pn3: the 1 s ticker keeps the route read (legs, legSig, legsAt) for the compass, cancel drops it, the docs say pn3.
- pn3 review fix: a journey over 240 points: the compass keeps every corner (the app's copy alone is thinned); the look-ahead goes round it.
Needs lupa. Exit code 1 on any failure."""
import math
import os
import sys

import lupa

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import test_compass as TC  # noqa: E402  (its stub game and paths)

LINES = r'''
function Region:CreateLine(name, layer, tmpl, sub)
    local t = setmetatable({ kind = "line", parent = self, layer = layer, sub = sub, shown = true }, Region)
    self.kids = self.kids or {}; self.kids[#self.kids + 1] = t
    return t
end
function Region:SetColorTexture(r, g, b, a) self.ctex = { r, g, b, a } end
function Region:SetThickness(w) self.thick = w end
function Region:SetStartPoint(...) self.p1 = { ... } end
function Region:SetEndPoint(...) self.p2 = { ... } end
TAXI = false
function UnitOnTaxi() return TAXI end
'''

fails = []


def chk(name, ok):
    print(("PASS " if ok else "FAIL ") + name)
    if not ok:
        fails.append(name)


def near(a, b, tol=0.01):
    return a is not None and b is not None and abs(a - b) <= tol


def main():
    L = lupa.LuaRuntime()
    L.execute(TC.STUB + LINES)                       # (the stub's Region is a local of its chunk)
    L.execute(open(TC.TRAIL, encoding="utf-8").read().replace("local ADDON = ...", "local ADDON = 'WowCompanion'", 1))
    G, WC = L.globals().GAME, L.globals().WowCompanion
    C = WC.compass
    chk("cp36: the compass's version tag", C.V == "cp36")
    chk("cp36: the path marker drawn under the quest (C.ORDER)", [C.ORDER[i] for i in range(1, len(C.ORDER) + 1)]
        == ["corpse", "pin", "path", "quest"])

    mk = L.eval('''function(spec)            -- { {k, c, {x, y, ...}}, ... } -> legs
        local out = {}
        for i, s in ipairs(spec) do out[i] = { k = s[1], c = s[2], p = s[3] } end
        return out
    end''')
    pa = L.eval("function(x, y, legs, st, sig) return WowCompanion.compass.pathAhead({ x = x, y = y, cont = 0 }, legs, st, sig) end")

    def route(*spec):
        return mk(L.table_from([L.table_from([s[0], s[1], L.table_from(s[2])]) for s in spec]))

    def ahead(x, y, R, st, sig):
        w, why = pa(x, y, R, st, sig)
        return (w.x, w.y, w.kind, w.d) if w is not None else None, why

    # an L-shaped walk north then west (world x = north, y = west), then a last walk leg to the goal
    R1 = route(("walk", 0, [0, 0, 50, 0, 100, 0, 100, 50, 100, 100]), ("walk", 0, [100, 100, 100, 200]))
    st = L.table()
    w, why = ahead(10, 2, R1, st, "r1")
    chk("walk: the point 30 yd on from the nearest point (10,0) -> (40,0)", w and near(w[0], 40) and near(w[1], 0) and why == "walk"
        and w[2] == "walk")
    w, _ = ahead(60, -1, R1, st, "r1")
    chk("walk: it advances as the player moves (60 -> 90)", w and near(w[0], 90) and near(w[1], 0) and st.idx == 2)
    w, _ = ahead(95, 0, R1, st, "r1")
    chk("walk: round the corner (95,0) -> (100,25)", w and near(w[0], 100) and near(w[1], 25))
    w, _ = ahead(100, 90, R1, st, "r1")
    chk("walk: across the joined walk legs (100,90) -> (100,120)", w and near(w[0], 100) and near(w[1], 120))
    w, why = ahead(100, 160, R1, st, "r1")
    chk("hand-over: 40 yd left along the route: still the path (100,190)", w and near(w[1], 190) and why == "walk")
    w, why = ahead(100, 170, R1, st, "r1")
    chk("hand-over: 30 yd left (<= HAND 35): yields to the objective", w is None and why == "hand")

    # passed segments: a zig-zag; once on the third segment, the first (2 yd away) is never taken again
    R2 = route(("walk", 0, [0, 0, 100, 0, 100, 20, 0, 20, 0, 40, 100, 40]))
    st2 = L.table()
    ahead(90, 0, R2, st2, "r2")
    ahead(100, 10, R2, st2, "r2")
    w, _ = ahead(50, 20, R2, st2, "r2")
    chk("zig-zag: on the third segment (50,20) -> (20,20)", w and near(w[0], 20) and near(w[1], 20) and st2.idx == 3)
    w, _ = ahead(50, 2, R2, st2, "r2")
    chk("passed segments ignored: by the first segment again, still the third's point", w and near(w[0], 20) and near(w[1], 20)
        and st2.idx == 3)
    w, _ = ahead(50, 2, R2, L.table(), "r2b")
    chk("(a fresh state there would take the first segment: (80,0))", w and near(w[0], 80) and near(w[1], 0))
    w, _ = ahead(10, 0, R2, st2, "r2-new")
    chk("a new route (signature) starts at its first segment again", w and near(w[0], 40) and near(w[1], 0) and st2.idx == 1)

    # a goal close by in a straight line, far along the route (a cliff): the path marker stays
    R3 = route(("walk", 0, [0, 0, 0, 100, 30, 100, 30, 0]))
    w, why = ahead(0, 5, R3, L.table(), "r3")
    chk("the goal 30 yd off straight but 225 yd along: the path marker stays, along the route (0,35)",
        w and near(w[0], 0) and near(w[1], 35) and why == "walk")

    # a flight leg: the walk to the flight master, then its start, then gone at it / aboard / on a taxi
    R4 = route(("walk", 0, [0, 0, 0, 100]), ("flight", 0, [0, 100, 500, 500]), ("walk", 0, [500, 500, 600, 500]))
    st4 = L.table()
    w, _ = ahead(0, 50, R4, st4, "r4")
    chk("flight route: on the walk, 30 yd on (0,80)", w and near(w[1], 80) and w[2] == "walk")
    w, why = ahead(0, 80, R4, st4, "r4")
    chk("the walk reaches the flight leg: its start, kind flight, 20 yd", w and near(w[1], 100) and w[2] == "flight" and near(w[3], 20)
        and why == "end")
    w, why = ahead(0, 95, R4, st4, "r4")
    chk("at the flight master (5 yd): gone", w is None and why == "at")
    R5 = route(("flight", 0, [0, 100, 500, 500]), ("walk", 0, [500, 500, 600, 500]))
    st5 = L.table()
    w, why = ahead(0, 0, R5, st5, "r5")
    chk("a route starting with a flight: points at its start, with its distance", w and near(w[0], 0) and near(w[1], 100)
        and w[2] == "flight" and near(w[3], 100) and why == "leg")
    ahead(0, 96, R5, st5, "r5")
    w, why = ahead(100, 200, R5, st5, "r5")
    chk("aboard (been at the start, now away from it): gone, never pointing back", w is None and why == "aboard")
    L.execute("TAXI = true")
    w, why = ahead(0, 0, R5, L.table(), "r5b")
    chk("on a taxi: gone", w is None and why == "taxi")
    L.execute("TAXI = false")
    w, why = ahead(0, 0, route(("walk", 1, [0, 0, 0, 100])), L.table(), "r6")
    chk("a route on another continent: none", w is None and why == "cont")

    # ------------------------------------------------------------------ the live compass: WC.pnav, the drawing
    G.facing = 0.0                                   # facing north: ahead (world x+) in the band's middle
    # the player at the map's middle = world (0, 0); the objective north-west, 200 yd north and 100 west: map x 0.4, y 0.3
    G.px, G.py = 0.5, 0.5
    G.quest = L.table(0.4, 0.3)
    G.qsrc = "onmap"
    C.refresh()
    C.draw()
    chk("no Polestar guidance: no path point", C.pa_ is None and C.paWhy == "off")
    L.execute('''WowCompanion.pnav = { active = true, legSig = "live1", legsAt = GAME.t,
        legs = { { k = "walk", c = 0, p = { 0, 0, 100, 0, 100, 50, 200, 100 } } } }''')
    C.draw()
    chk("guiding: the path point 30 yd north (30, 0)", C.pa_ is not None and near(C.pa_.x, 30) and near(C.pa_.y, 0))
    m = {C.last.marks[i].key: C.last.marks[i] for i in range(1, len(C.last.marks) + 1)}
    chk("the path marker on the band, in the middle (dead ahead)", "path" in m and abs(m["path"].x) < 0.5 and not m["path"].off)
    chk("the objective marker still there (the route list's goal)", "rt" in m and m["rt"].x < -1)
    o = C.pa
    lines = [o.ln[i] for i in range(1, len(o.ln) + 1)]
    rims = [o.rim[i] for i in range(1, len(o.rim) + 1)]
    R = [120 / 255, 200 / 255, 1]
    chk("its look: four light blue strokes (rgb 120,200,255), shown", len(lines) == 4 and all(
        ln.kind == "line" and ln.shown and all(near(ln.ctex[i + 1], R[i], 1e-6) for i in range(3)) for ln in lines))
    chk("a dark rim under each stroke, wider", all(r.kind == "line" and r.shown and r.ctex[1] == 0 and r.thick > lines[0].thick
        and r.sub < lines[0].sub for r in rims))
    # a double chevron pointing up: each pair meets at an apex above its ends, the rear one lower and dimmer
    ys = [(ln.p1[4], ln.p2[4]) for ln in lines]
    chk("a double chevron pointing up (apexes above the ends, the second lower)", all(a > b for a, b in ys) and ys[2][0] < ys[0][0]
        and lines[2].alpha < lines[0].alpha)
    chk("smaller than the objective's disc (18 vs 28 spec px)", C.PA.SIZE < 28)
    chk("terse: no distance text on a walk", not o.d.shown)
    rk = None
    for k, v in C.rk.items():
        rk = v
    chk("the objective's look unchanged: our gold disc texture, no line", rk is not None and rk.disc.tex == C.DISC_TEX and rk.disc.shown
        and rk.disc.kind == "tex")
    chk("the path marker uses no texture of the objective's", all(getattr(ln, "tex", None) is None for ln in lines + rims))
    # the walk reaches a flight leg: the distance shows, light blue
    L.execute('''WowCompanion.pnav.legs = { { k = "walk", c = 0, p = { 0, 0, 0, -20 } }, { k = "flight", c = 0, p = { 0, -20, 400, -400 } },
        { k = "walk", c = 0, p = { 400, -400, 450, -400 } } }; WowCompanion.pnav.legSig = "live2"''')
    C.draw()
    chk("a flight master 20 yd east: its distance under the chevron", C.pa_ is not None and C.pa_.kind == "flight" and o.d.shown
        and o.d.text == "20 yd" and near(o.d.col[1], R[0], 1e-6))
    m = {C.last.marks[i].key: C.last.marks[i] for i in range(1, len(C.last.marks) + 1)}
    chk("east of a north-facing player: right of centre", m["path"].x > 300)
    # a stale route read, then guidance off: hidden
    G.t = G.t + 10
    C.draw()
    chk("a stale route read (10 s): the path marker hidden", C.pa_ is None and not any(ln.shown for ln in lines) and not o.d.shown)
    L.execute("WowCompanion.pnav.legsAt = GAME.t")
    C.draw()
    chk("fresh again: shown", C.pa_ is not None and all(ln.shown for ln in lines))
    L.execute("WowCompanion.pnav.active = false")
    C.draw()
    chk("guidance off: hidden", C.pa_ is None and not any(ln.shown for ln in lines))
    C.diagAt = -100
    C.draw()
    chk("the diag line: pa = off", C.lastDiag is not None and C.lastDiag.pa == "off")

    # ------------------------------------------------------------------ Map.lua pn3: the ticker keeps the route for the compass
    src = open(TC.MAPLUA, encoding="utf-8").read()
    a0 = src.index("WC.pnav = {")
    b0 = src.index("-- ---------------------------------------------------------------- POLESTAR'S AUTOMATION", a0)
    M = lupa.LuaRuntime()
    M.execute(r'''
        SAID = {}
        WC = { send = function(s) SAID[#SAID + 1] = s end, encode = function(t) return t end, push = function() end }
        T = 0; function GetTime() return T end
        TICK = nil
        C_Timer = { NewTicker = function(_, fn) TICK = fn end }
        function CreateFrame() return { RegisterEvent = function() end, SetScript = function() end } end
        ACTIVE = false
        GEOM = { legs = { { kind = "walk", pts = { { 0, 1, 2 }, { 0, 50, 2 }, { 0, 90, 40 } } },
                          { kind = "fly", pts = { { 0, 90, 40 }, { 0, 500, 600 } } } } }
        Polestar = { Nav = { API = {
            Navigate = function() ACTIVE = true; return true end,
            Cancel = function() ACTIVE = false end,
            Active = function() return ACTIVE, "DSForever" end },
            GetRouteGeometry = function() return GEOM end } }
    ''' + src[a0:b0])
    M.execute("WC.pnav.go(1429, 0.5, 0.5); T = 1; TICK()")
    P = M.eval("WC.pnav")
    chk("pn3: the ticker keeps the route read for the compass", P.legs is not None and len(P.legs) == 2 and P.legs[1].k == "walk"
        and P.legs[1].p[3] == 50 and P.legs[2].k == "fly" and P.legSig == P.sig and P.legsAt == 1)
    chk("pn3: the docs say pn3", all(M.eval("SAID")[i].v == "pn3" for i in range(1, len(M.eval("SAID")) + 1)))
    M.execute("WC.pnav.cancel('test')")
    chk("pn3: cancel drops it", P.legs is None and P.legSig is None and P.legsAt is None)

    # pn3 review fix (the user, 2026-11-11: "keep players on path so that they don't resort to walking at the final objective"): a
    # journey of over PTS (240) points: the app's copy is thinned (every 3rd point here) and loses the first walk's corner (100,0);
    # the compass's copy keeps every corner, and the look-ahead goes round it, not across
    M.execute(r'''
        local far = {}
        for i = 1, 600 do far[i] = { 0, 100 + i, 100 } end
        GEOM = { legs = { { kind = "walk", pts = { { 0, 0, 0 }, { 0, 100, 0 }, { 0, 100, 100 } } }, { kind = "walk", pts = far } } }
        SAID = {}; WC.pnav.go(1429, 0.5, 0.5); T = 20; TICK()
    ''')
    full = [P.legs[1].p[i] for i in range(1, len(P.legs[1].p) + 1)]
    sent = [s for s in (M.eval("SAID")[i] for i in range(1, len(M.eval("SAID")) + 1)) if s.type == "pnavroute"]
    thin = [sent[-1].legs[1].p[i] for i in range(1, len(sent[-1].legs[1].p) + 1)] if sent else None
    chk("pn3: over 240 points: the app's copy is thinned (the corner gone)", thin == [0, 0, 100, 100])
    chk("pn3: the compass's copy keeps every corner", full == [0, 0, 100, 0, 100, 100] and len(P.legs[2].p) == 1200)
    RL = route(("walk", 0, full), ("walk", 0, [P.legs[2].p[i] for i in range(1, len(P.legs[2].p) + 1)]))
    w, why = ahead(90, 0, RL, L.table(), "long")
    chk("pn3: the look-ahead on the long route goes round the corner (90,0) -> (100,20)", w and near(w[0], 100) and near(w[1], 20)
        and why == "walk")
    M.execute("WC.pnav.cancel('test')")

    print("failed: %d" % len(fails))
    sys.exit(1 if fails else 0)


if __name__ == "__main__":
    main()
