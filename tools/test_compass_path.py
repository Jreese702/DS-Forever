"""cp36 THE PATH MARKER (the user, 2026-11-11: "The polestar doted line needs its own kind of marker to point on the compass to keep
players on path so that they don't resort to walking at the final objective"), off-device.

    python tools/test_compass_path.py

Trail.lua's compass under test_compass.py's stub game, with a stub Polestar route (Map.lua's WC.pnav.legs: {k, c, p = {wx, wy, ...}}):
- C.pathAhead: the look-ahead point AHEAD yd on along the route from the player's nearest point, advancing as the player moves,
  round a corner, never back onto a passed segment, re-started by a new route; a flight leg's start (its distance) once the walk
  reaches it, gone at it and aboard / on a taxi; the hand-over to the objective within HAND yd ALONG the route (not the straight line);
  another continent: none;
- C.pathTarget: only while WC.pnav is active with a fresh route read;
- the drawing: the path marker's own look (light blue lines, no texture of the objective's but the gold dots) beside the objective's
  gold disc, unchanged; hidden when the route goes;
- cp43 THE ROUTE CONVEYOR (the user, 2026-11-17: "on the compass the blue arows arent clear what they are, I want to make them
  animate scrolling upwards with a yellow dot between them"): C.paConveyor(t): three chevrons with a gold dot halfway between each
  pair, every piece moving up at SPEED, the same picture again every P / SPEED s, alpha 0 at both window ends; the drawing: the
  strokes and dots at those places on the marker's x (cp40's eased x, no jitter), the dots C.GOLD on C.DISC_TEX, moving up between
  two frames; all hidden with a stale route / guidance off;
- Map.lua pn3: the 1 s ticker keeps the route read (legs, legSig, legsAt) for the compass, cancel drops it, the docs say pn3.
- pn3 review fix: a journey over 240 points: the compass keeps every corner (the app's copy alone is thinned); the look-ahead goes round it.
- cp37 / pn4 (the user, 2026-11-11: "Okay now I actually do have a polestar objective that is not in my bag and not telling me where to
  go"): with NO focused quest, an accept step's waypoint and the app's matching GO -> Polestar's goal shown (and the path marker with a
  route); the app's word (pn4 38, 8) shows it whatever the GO (a drift, no GO of ours, Polestar guiding its step itself); 38, 9 or
  no word for KEEP_S -> the focused quest again; cp35's guess keeps a drift inside the app's re-send threshold; Map.lua's pad row
  (38, 8 / 9), geom() taking Polestar's own target while the row holds, the ticker running then and dropping the route after.
  The first cp37 check is a BASELINE: it passes on cp35 too (the device's no-goal case was not reproduced off-device).
- cp38 / pn5 (the review of cp37): the diag lines for the next device test - the compass's psd (what Polestar's goal stood on: the step
  read, its world spot, its continent against the player's, the game super-tracking a user waypoint, Map's route pin, the GO) and rt1
  (the list's first and why), and Map's "pnavt" every 10 s (Polestar's Active / owner, its target, what geom() gave and why, our GO,
  the word, the step read).
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
    chk("cp40: the compass's version tag (the jitter fix)", int(C.V[2:]) >= 40)      # (the layout editor: cp41 or later)
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
    chk("cp43: its look: six light blue strokes (three chevrons, rgb 120,200,255)", len(lines) == 6 and all(
        ln.kind == "line" and (ln.shown or ln.alpha == 0) and all(near(ln.ctex[i + 1], R[i], 1e-6) for i in range(3)) for ln in lines))
    chk("a dark rim under each stroke, wider", len(rims) == 6 and all(r.kind == "line" and r.ctex[1] == 0
        and r.thick > lines[0].thick and r.sub < lines[0].sub for r in rims))
    # each chevron points up: its two arms meet at an apex above their ends, on the marker's x
    ys = [(ln.p1[4], ln.p2[4]) for ln in lines]
    chk("each chevron pointing up (apex above the ends)", all(a > b for a, b in ys))
    mx = m["path"].x
    chk("cp43: every piece on the marker's x (the eased x, cp40: no jitter)", all(near(ln.p1[3], C.u(mx), 1e-6) for ln in lines)
        and all(near(o.dot[i].point[4], C.u(mx), 1e-6) for i in range(1, len(o.dot) + 1)))
    PA = C.PA
    pieces = C.paConveyor(G.t)
    pl = [pieces[i] for i in range(1, len(pieces) + 1)]
    chk("cp43: the conveyor: 3 chevrons and 3 dots", [p.kind for p in pl] == ["chev", "dot"] * 3)
    # placed where the conveyor says: chevron k's apex at band line + y + 2, dot k at band line + y
    chev = [p for p in pl if p.kind == "chev"]
    dots = [p for p in pl if p.kind == "dot"]
    chk("cp43: the drawn strokes / dots at the conveyor's places", all(near(lines[2 * k].p1[4], C.u(-C.SPEC.line + chev[k].y + 2), 1e-6)
        and near(lines[2 * k].alpha, chev[k].a, 1e-6) for k in range(3))
        and all(near(o.dot[k + 1].point[5], C.u(-C.SPEC.line + dots[k].y), 1e-6) for k in range(3)))
    chk("cp43: the dots our gold on the quest disc's texture, a dark rim under each", all(
        o.dot[i].tex == C.DISC_TEX and all(near(o.dot[i].col[j + 1], C.GOLD[j + 1], 1e-6) for j in range(3))
        and o.dsh[i].tex == C.DISC_TEX and o.dsh[i].col[1] == 0 and o.dsh[i].w > o.dot[i].w for i in range(1, 4)))
    # a dot between each pair: sorted by height the kinds alternate, each dot halfway (P / 2) between its chevrons
    srt = sorted(((p.y, p.kind) for p in pl))
    chk("cp43: a dot halfway between each pair of chevrons", all(srt[i][1] != srt[i + 1][1] for i in range(5))
        and all(near(srt[i + 1][0] - srt[i][0], PA.P / 2, 1e-6) for i in range(5)))
    # moving up: 0.1 s later every piece (not wrapping) SPEED * 0.1 higher
    def ys_at(t):
        q = C.paConveyor(t)
        return [q[i].y for i in range(1, len(q) + 1)]
    t0 = 1000.03
    a0, a1 = ys_at(t0), ys_at(t0 + 0.1)
    moved = [b - a for a, b in zip(a0, a1) if b > a - 1]
    chk("cp43: scrolling upward (0.1 s: +SPEED / 10 spec px)", PA.SPEED > 0 and len(moved) >= 5
        and all(near(d, PA.SPEED * 0.1, 1e-6) for d in moved))
    q0, q1 = C.paConveyor(t0), C.paConveyor(t0 + PA.P / PA.SPEED)
    s0 = sorted((q0[i].kind, round(q0[i].y, 6), round(q0[i].a, 6)) for i in range(1, 7))
    s1 = sorted((q1[i].kind, round(q1[i].y, 6), round(q1[i].a, 6)) for i in range(1, 7))
    chk("cp43: a loop: the same picture every P / SPEED s (one chevron step)", s0 == s1)
    q = C.paConveyor(0)
    chk("cp43: alpha 0 at the window's ends (the wrap never pops), 1 in the middle", near(q[1].y, -PA.SPAN / 2, 1e-9)
        and q[1].a == 0 and all(q[i].a <= 1 for i in range(1, 7)) and any(q[i].a == 1 for i in range(1, 7))
        and near(C.paConveyor(PA.SPAN / PA.SPEED - 1e-6)[1].a, 0, 1e-3))
    # two frames 0.25 s apart: the drawn dots higher (the same dot, unless it wrapped)
    y0 = [o.dot[i].point[5] for i in range(1, 4)]
    G.t = G.t + 0.25
    C.draw()
    y1 = [o.dot[i].point[5] for i in range(1, 4)]
    chk("cp43: drawn frame to frame: the dots move up", sum(1 for a, b in zip(y0, y1) if near(b - a, C.u(PA.SPEED * 0.25), 1e-6)) >= 2)
    # the verifier's per-frame cost fix: no table made per frame, the drift stepped at most every TICK, nothing re-placed unchanged
    same = L.eval("""(function(C, t)
        local cv = C.pa.cv; local p = {}
        for i = 1, #cv do p[i] = cv[i] end
        local out = C.paConveyor(t, cv)
        if not rawequal(out, cv) or #cv ~= 6 then return false end
        for i = 1, 6 do if not rawequal(cv[i], p[i]) then return false end end
        return true
    end)""")(C, G.t + 3.3)
    chk("cp43 cost: C.paConveyor fills the table it is given (the same piece tables)", same is True)
    G.t = G.t + PA.TICK / 3
    C.draw()
    y2 = [o.dot[i].point[5] for i in range(1, 4)]
    chk("cp43 cost: a frame inside one TICK: the drift holds (nothing moved)", y2 == y1 and o.pt == o.tt)
    tt0 = o.tt
    G.t = G.t + PA.TICK
    C.draw()
    y3 = [o.dot[i].point[5] for i in range(1, 4)]
    chk("cp43 cost: past the TICK: the drift steps again (moving up)", o.tt != tt0
        and sum(1 for a, b in zip(y2, y3) if b > a) >= 2)
    chk("smaller than the objective's disc (18 vs 28 spec px)", C.PA.SIZE < 28)
    chk("terse: no distance text on a walk", not o.d.shown)
    rk = None
    for k, v in C.rk.items():
        rk = v
    chk("the objective's look unchanged: our gold disc texture, no line", rk is not None and rk.disc.tex == C.DISC_TEX and rk.disc.shown
        and rk.disc.kind == "tex")
    chk("the path marker uses no texture of the objective's (lines; the dots the disc in gold)", all(getattr(ln, "tex", None) is None
        for ln in lines + rims))
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
    chk("cp43: stale: every conveyor piece hidden (strokes, rims, dots)", not any(o.all[i].shown for i in range(1, len(o.all) + 1)))
    L.execute("WowCompanion.pnav.legsAt = GAME.t")
    C.draw()
    chk("fresh again: shown", C.pa_ is not None and all(ln.shown for ln in lines))
    L.execute("WowCompanion.pnav.active = false")
    C.draw()
    chk("guidance off: hidden", C.pa_ is None and not any(ln.shown for ln in lines))
    chk("cp43: guidance off: the dots hidden too", not any(o.dot[i].shown or o.dsh[i].shown for i in range(1, 4)))
    C.diagAt = -100
    C.draw()
    chk("the diag line: pa = off", C.lastDiag is not None and C.lastDiag.pa == "off")

    # ------------------------------------------------------------------ cp37: Polestar's goal with NO focused quest (the device's case)
    # Polestar step "The Strange Hermit - Accept The Strange Hermit": no quest in the log, the game's focus cleared ("polestar unfocus");
    # its waypoint north-east (map 0.6, 0.4 = world (100, -100)); the app's GO for it (two base-36 digits per axis)
    G.quest = None
    L.execute("""WowCompanion.pstep = { last = { t = "The Strange Hermit", g = "Accept The Strange Hermit", k = "accept", m = 1429,
            x = 0.6, y = 0.4 } }
        WowCompanion.pnav = { active = true, m = 1429, x = 777 / 1295, y = 518 / 1295, goAt = GAME.t - 1, KEEP_S = 60 }
        function WowCompanion.pnav.psOn()
            local at = WowCompanion.pnav.psAt
            return type(at) == "number" and GetTime() - at <= WowCompanion.pnav.KEEP_S
        end""")

    def goal():
        C.draw()
        return C.rt_[1] if C.rt_ and len(C.rt_) > 0 else None

    it = goal()
    chk("cp37 BASELINE (passes on cp35 too): no focused quest + an accept step + the matching GO: Polestar's goal (goal:ps)",
        it is not None
        and it.id == "goal:ps" and C.qsrc == "polestar" and C.qid is None and near(it.w.x, 100, 0.5) and near(it.w.y, -100, 0.5))
    C.diagAt = -100
    C.draw()
    D = C.lastDiag
    chk("cp38: the diag's psd for it (%s)" % (D.psd if D else None), D is not None
        and D.psd == "loc,m1429,f-,w1,c1,st0,rw0,go1429=,me1429" and D.rt1 == "goal:ps:polestar")
    rk = C.rk["goal:ps"] if C.rk else None
    chk("cp37: ... drawn: the gold disc with its '!'", rk is not None and rk.disc.shown and rk.num.text == "!")
    # cp38: the same moment (1428's add-on: no word, the GO matching) with the game super-tracking a user waypoint - a pin left from
    # before, or the one the game falls back to once "polestar unfocus" clears the quest focus. FAILS on cp35 / cp37 (the pin alone)
    G.pin = L.table(0.5, 0.45); L.execute("C_SuperTrack.IsSuperTrackingUserWaypoint = function() return true end")
    it = goal()
    chk("cp38 (fails on cp37): no focus, the matching GO, a super-tracked user waypoint: Polestar's goal first, the pin second",
        it is not None and it.id == "goal:ps" and len(C.rt_) == 2 and C.rt_[2].why == "ownpin2" and C.psHold is True)
    G.pin = None; L.execute("C_SuperTrack.IsSuperTrackingUserWaypoint = function() return false end")
    # cp35's guess: a drift of 0.003 (inside the app's own MOVE 0.004, so no new GO) no longer loses it
    L.execute("WowCompanion.pstep.last.x = 0.603")
    G.t = G.t + 6
    it = goal()
    chk("cp37: a drift of 0.003 with no new GO (cp35's 0.002 lost it after 5 s): still Polestar's", it is not None and it.id == "goal:ps")
    L.execute("WowCompanion.pstep.last.x = 0.6")
    # with a route: the cp36 path marker too
    L.execute("""WowCompanion.pnav.legs = { { k = "walk", c = 0, p = { 0, 0, 100, 0, 100, -100 } } }
        WowCompanion.pnav.legSig = "hermit"; WowCompanion.pnav.legsAt = GAME.t""")
    goal()
    m = {C.last.marks[i].key: C.last.marks[i] for i in range(1, len(C.last.marks) + 1)}
    chk("cp37: ... and the path marker on Polestar's route (30, 0)", C.pa_ is not None and near(C.pa_.x, 30) and near(C.pa_.y, 0)
        and "path" in m and "rt" in m)
    # THE APP'S WORD (pn4 38, 8): Polestar guiding its step itself after dropping our GO (Map.lua active false, no GO match at all)
    L.execute("WowCompanion.pnav.active = false; WowCompanion.pnav.psSaid = true; WowCompanion.pnav.psAt = GAME.t")
    it = goal()
    chk("cp37: the row holds (38, 8), no GO of ours in force: Polestar's goal", it is not None and it.id == "goal:ps"
        and C.qsrc == "polestar" and C.psHold is True)
    L.execute("WowCompanion.pnav.legsAt = GAME.t")
    goal()
    chk("cp37: ... and the path marker (Polestar's own route)", C.pa_ is not None and C.paWhy == "walk")
    C.diagAt = -100
    C.draw()
    chk("cp38: the diag's psd: no GO of ours (%s)" % C.lastDiag.psd, C.lastDiag.psd.startswith("loc,m1429,f-,w1,c1,st0,rw0,go-,"))
    # the game super-tracking a user waypoint on the player's continent (st1): Polestar's goal still first (cp38)
    G.pin = L.table(0.5, 0.45); L.execute("C_SuperTrack.IsSuperTrackingUserWaypoint = function() return true end")
    C.diagAt = -100
    goal()
    chk("cp38: a super-tracked user waypoint (cp37 showed only that pin): Polestar's goal first, the pin second, st1 (%s / %s)"
        % (C.lastDiag.psd, C.lastDiag.rt1), ",st1," in C.lastDiag.psd and C.rt_[1].id == "goal:ps" and len(C.rt_) == 2
        and C.rt_[2].why == "ownpin2")
    G.pin = None; L.execute("C_SuperTrack.IsSuperTrackingUserWaypoint = function() return false end")
    L.execute("WowCompanion.pnav.active = true; WowCompanion.pnav.x = 0.2; WowCompanion.pnav.goAt = GAME.t")
    it = goal()
    chk("cp37: the row holds, the GO elsewhere: still Polestar's", it is not None and it.id == "goal:ps")
    # a focused quest while the row holds: Polestar's waypoint wins (cp35's rule), its quest id when it has one
    G.quest = L.table(0.4, 0.3)
    L.execute("WowCompanion.pstep.last.q = 555")
    it = goal()
    chk("cp37: a focused quest too: Polestar's waypoint, its quest's id", it is not None and it.id == "goal:555" and C.qsrc == "polestar"
        and near(it.w.x, 100, 0.5))
    C.diagAt = -100
    C.draw()
    chk("cp37: the diag says ps", C.lastDiag is not None and C.lastDiag.ps is True and C.lastDiag.qsrc == "polestar")
    # released (38, 9): the focused quest as before, even with the GO matching Polestar's step (no guessing once the app says)
    L.execute("WowCompanion.pnav.psAt = nil; WowCompanion.pnav.x = 777 / 1295; WowCompanion.pnav.goAt = GAME.t")
    it = goal()
    chk("cp37: released (38, 9): the focused quest again (no guess)", it is not None and it.id == "goal:123" and C.qsrc == "onmap"
        and C.psHold is False)
    # no word for KEEP_S (the app gone): the same
    L.execute("WowCompanion.pnav.psAt = GAME.t")
    G.t = G.t + 61
    it = goal()
    chk("cp37: no word for 60 s: the focused quest again", it is not None and it.id == "goal:123" and C.qsrc == "onmap")
    # a held step with no spot of its own: the focused quest (the app focuses the step's quest)
    L.execute("WowCompanion.pnav.psAt = GAME.t; WowCompanion.pstep.last = { t = 'Train', g = 'Talk to Kandris', nl = true, q = 123 }")
    it = goal()
    chk("cp37: a held step with no spot: the focused quest's own goal", it is not None and it.id == "goal:123" and C.qsrc == "onmap")
    # nothing at all focused, the row holds, no spot: nothing (as before)
    G.quest = None
    it = goal()
    chk("cp37: nothing focused, no spot: no goal", it is None)
    L.execute("WowCompanion.pnav = { active = false }; WowCompanion.pstep = nil")
    G.quest = L.table(0.4, 0.3)

    # ------------------------------------------------------------------ Map.lua pn3: the ticker keeps the route for the compass
    src = open(TC.MAPLUA, encoding="utf-8").read()
    a0 = src.index("WC.pnav = {")
    b0 = src.index("-- ---------------------------------------------------------------- POLESTAR'S AUTOMATION", a0)
    M = lupa.LuaRuntime()
    M.execute(r'''
        SAID = {}
        WC = { send = function(s) SAID[#SAID + 1] = s end, encode = function(t) return t end, push = function() end,
               pad = { op = function(row, k, fn) PAD = fn end } }
        OWNER = "DSForever"
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
            Active = function() return ACTIVE, OWNER end },
            GetRouteGeometry = function() return GEOM end } }
    ''' + src[a0:b0])
    M.execute("WC.pnav.go(1429, 0.5, 0.5); T = 1; TICK()")
    P = M.eval("WC.pnav")
    chk("pn3: the ticker keeps the route read for the compass", P.legs is not None and len(P.legs) == 2 and P.legs[1].k == "walk"
        and P.legs[1].p[3] == 50 and P.legs[2].k == "fly" and P.legSig == P.sig and P.legsAt == 1)
    chk("pn8: the docs say pn8 (pk1, pg1)", all(M.eval("SAID")[i].v in ("pn8",) for i in range(1, len(M.eval("SAID")) + 1)))
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

    # ------------------------------------------------------------------ pn4: the app's word, Polestar guiding its step itself
    M.execute("T = 100; PAD(nil, 38); PAD(nil, 8)")
    chk("pn4: 38, 8 -> the row holds (psOn, psSaid)", M.eval("WC.pnav.psOn()") is True and M.eval("WC.pnav.psSaid") is True)
    chk("pn4: ... not Polestar's automation (pq1's 0-7 only)", M.eval("WC.pnav.digits") is None and M.eval("WC.pnav.qolAt") is None)
    # Polestar dropped our GO at a step change and guides its own step: Active() = true, owner nil; our GO gone (active false)
    M.execute("ACTIVE = true; OWNER = nil; SAID = {}; T = 101; TICK()")
    sent = [s for s in (M.eval("SAID")[i] for i in range(1, len(M.eval("SAID")) + 1)) if s.type == "pnavroute"]
    chk("pn4: the row holds, Polestar's own target: the route read (pnavroute, legs for the compass)", len(sent) == 1
        and M.eval("WC.pnav.legs") is not None and M.eval("WC.pnav.active") is False)
    M.execute("OWNER = 'SomeoneElse'; T = 102; TICK()")
    chk("pn4: (another owner while the row holds: Polestar's guidance still read)", M.eval("WC.pnav.legsAt") == 102)
    M.execute("T = 103; PAD(nil, 38); PAD(nil, 9)")
    chk("pn4: 38, 9 -> released", M.eval("WC.pnav.psOn()") is False and M.eval("WC.pnav.psAt") is None)
    M.execute("T = 104; TICK()")
    chk("pn4: released, no GO of ours: the route dropped", M.eval("WC.pnav.legs") is None)
    chk("pn4: geom() refuses another owner's target again", M.eval("WC.pnav.geom()") is None)
    M.execute("T = 200; PAD(nil, 38); PAD(nil, 8); T = 261; TICK()")
    chk("pn4: no word for over KEEP_S: off, the route dropped", M.eval("WC.pnav.psOn()") is False and M.eval("WC.pnav.legs") is None)
    M.execute("T = 300; PAD(nil, 38); T = 303; PAD(nil, 8)")
    chk("pn4: an 8 more than 2 s after the 38 is not the word", M.eval("WC.pnav.psOn()") is False)
    M.execute("PQ = nil; WC.pqol = { choose = function(m) PQ = m end }; T = 400; PAD(nil, 38); PAD(nil, 5)")
    chk("pn4: 38 then 0-7 is still Polestar's automation mask (pq1)", M.eval("PQ") == 5 and M.eval("WC.pnav.psOn()") is False)
    M.execute("OWNER = 'DSForever'")

    # ------------------------------------------------------------------ pn5: the "pnavt" line every 10 s (the device's missing route)
    def pnavt():
        a = M.eval("SAID")
        out = [a[i] for i in range(1, len(a) + 1) if a[i].probe == "pnavt"]
        return out[-1] if out else None
    M.execute("SAID = {}; WC.pnav.diagAt = nil; T = 500; TICK()")
    chk("pn5: nothing in force (no GO, no word, no step): no pnavt line", pnavt() is None)
    M.execute("""SAID = {}; WC.pstep = { last = { m = 2521, x = 0.54, y = 0.39, fr = 'e' } }
        Polestar.Nav.GetTarget = function() return { map = 2521, x = 53.98, y = 38.92 } end
        ACTIVE = false; T = 510; TICK()""")
    d = pnavt()
    chk("pn5: a step with a spot, nothing guided: act false, gm '-', ps loc, its map and frame", d is not None and d.v == "pn8"
        and d.act is False and d.gm == "-" and d.ps == "loc" and d.psm == 2521 and d.fr == "e" and d.a is False and d.on is False)
    M.execute("SAID = {}; T = 515; TICK()")
    chk("pn5: once per 10 s", pnavt() is None)
    M.execute("SAID = {}; OWNER = 'DSForever'; WC.pnav.go(2521, 699 / 1295, 504 / 1295); T = 521; TICK()")
    d = pnavt()
    chk("pn5: our GO in force: act, own, Polestar's target, geom's legs / points, our GO", d is not None and d.act is True
        and d.own == "DSForever" and d.tm == 2521 and d.tx == 54 and d.ty == 38.9 and d.gm == "2/603" and d.gw == "ok"
        and d.a is True and d.gom == 2521 and abs(d.gox - 0.5398) < 1e-9)
    M.execute("SAID = {}; OWNER = nil; T = 532; TICK()")
    d = pnavt()
    chk("pn5: Polestar's own target (no word): geom refused, gw owner", d is not None and d.own is None and d.gm == "nil"
        and d.gw == "owner")
    M.execute("SAID = {}; OWNER = 'DSForever'; GEOM = nil; T = 543; TICK()")
    d = pnavt()
    chk("pn5: Polestar gives no geometry: gw none", d is not None and d.gm == "nil" and d.gw == "none")
    M.execute("WC.pnav.cancel('test'); WC.pstep = nil")

    print("failed: %d" % len(fails))
    sys.exit(1 if fails else 0)


if __name__ == "__main__":
    main()
