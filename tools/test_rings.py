"""The aggro rings on the ground (WowCompanion/Trail.lua, WC.rings), off-device: Trail.lua under the compass and aggro tests' stub game,
each mob's plate placed on the screen where the camera model puts a point PLATE_H above its feet.

    python tools/test_rings.py

Checks the radius rule (agreed with Map for both screens), who gets a ring (aggressive, not dead, not tapped, never neutral or players),
the distance recovered from the plate's height and clamped into the range bracket, yellow outside / red inside, the combat fade, the
smoothing's speed limit, the plates only READ (nothing written), the setting, and the pull log. Needs lupa. Exit code 1 on any failure."""
import math
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import test_aggro as ta  # noqa: E402
import test_compass as tc  # noqa: E402

PHI, D, H, PH, FL, CY, CX = 0.35, 12.5, 1.9, 3.0, 1365.3334 / 2, 384.0, 1365.3334 / 2

PLATES = r'''
PLATE_WRITES, PLATE_READS = 0, 0
-- a plate: only read (GetCenter, GetEffectiveScale); anything written to it is counted
local function plateFor(u)
    local p = { GetCenter = function() PLATE_READS = PLATE_READS + 1; if u.secretPos then return SECRET, SECRET end; return u.px or 682.6667, u.py or 400 end,
                GetEffectiveScale = function() return 1 end }
    return setmetatable(p, { __newindex = function(t, k, v) PLATE_WRITES = PLATE_WRITES + 1; rawset(t, k, v) end })
end
-- "is it targeting me" (the pull's fallback when the threat read is secret)
function UnitIsUnit(a, b) local tok = a:match("^(.-)target$"); local u = tok and UNITS[tok]; return u ~= nil and u.onMe == true and b == "player" end
C_NamePlate = { GetNamePlateForUnit = function(t) local u = UNITS[t]; if u then u.plate = u.plate or plateFor(u); return u.plate end end }
'''


def proj(dfwd, dz):
    vf = dfwd + D * math.cos(PHI)
    vu = dz - H - D * math.sin(PHI)
    return FL * (vf * math.sin(PHI) + vu * math.cos(PHI)) / (vf * math.cos(PHI) - vu * math.sin(PHI))


def main():
    L = tc.lupa.LuaRuntime()
    L.execute(tc.STUB)
    L.execute(ta.WORLD)
    L.execute(PLATES)
    L.execute(open(tc.TRAIL, encoding="utf-8").read().replace("local ADDON = ...", "local ADDON = 'WowCompanion'", 1))
    fails = []

    def chk(name, ok):
        print(("PASS " if ok else "FAIL ") + name)
        if not ok:
            fails.append(name)

    G, Lg = L.globals().GAME, L.globals()
    A, R, C = Lg.WowCompanion.live, Lg.WowCompanion.rings, Lg.WowCompanion.compass
    frames = [Lg.FRAMES[i] for i in range(1, len(Lg.FRAMES) + 1)]
    ev = [f for f in frames if f.events and f.events.NAME_PLATE_UNIT_ADDED][0]
    rf = frames[-1]                                       # the rings' own updater (the last frame Trail.lua makes)

    def fire(e, a=None):
        ev.scripts.OnEvent(ev, e, a)

    def run(secs, dt=1 / 30):
        for _ in range(int(round(secs / dt))):
            G.t = G.t + dt
            A.drainAt = G.t
            ev.scripts.OnUpdate(ev, dt)
            rf.scripts.OnUpdate(rf, dt)

    def unit(tok, d, **kw):
        u = {"d": d, "lv": 6, "r": 2, "guid": "Creature-0-1-2-3-3098-0000AAAA", "ct": 1, "px": CX, "py": CY + proj(d, PH)}
        u.update(kw)
        Lg.UNITS[tok] = L.table_from(u)

    # the RADIUS RULE (both screens): clamp(20 + (mob - you), 5, 45); "??" = you + 10
    cases = [((6, 6), 20), ((9, 6), 23), ((60, 6), 45), ((1, 20), 5), ((5, 6), 19), ((-1, 6), 30), ((None, 6), 30)]
    got = [R.radius(lv, plv) for (lv, plv), _ in cases]
    chk("the radius rule: %s" % got, got == [w for _, w in cases])

    G.t = 100.0
    G.facing = 0.0
    fire("PLAYER_ENTERING_WORLD")
    Lg.WowCompanion.npWant = L.table_from({"hud": True})
    C.wm.pitch, C.wm.pitchAt, C.wm.tilted = PHI, G.t, False
    unit("nameplate1", 18)
    fire("NAME_PLATE_UNIT_ADDED", "nameplate1")
    run(1.0)
    o = R.list[A.byTok.nameplate1]
    chk("a hostile mob gets a ring: our own frame on UIParent, BACKGROUND, mouse off, our own art",
        o is not None and o.f.shown is True and L.eval("rawequal(WowCompanion.rings.list[0].f.parent, UIParent)")
        and o.f.strata == "BACKGROUND" and o.f.mouse is False and o.t.tex.endswith("marker_ring"))
    chk("its distance from the plate's height on screen: %.2f yd (true 18)" % o.d, abs(o.d - 18) < 0.3)
    chk("radius 20 (a level-6 mob for a level-6 player); you 18 yd away: INSIDE, red", o.r == 20 and o.inside is True
        and list(o.t.col.values())[:3] == [0.92, 0.2, 0.12])
    # the ring's size: the ground circle's projection at that distance
    g = C.wmGround(18, 0, 20, False, FL)
    chk("the ring is the 20 yd ground circle's projection (%.1f x %.1f)" % (o.f.w, o.f.h), abs(o.f.w - g.rw) < 1 and abs(o.f.h - g.rh) < 1)
    # outside: farther than its radius -> yellow
    unit("nameplate1", 28, py=CY + proj(28, PH)); run(3.0)
    chk("28 yd away (outside its 20): yellow (%.1f yd)" % o.d, o.inside is False and list(o.t.col.values())[:3] == [1, 0.84, 0.27])
    # the bracket clamp: a plate height that says 5 yd while the range checks say 20..25 -> kept in the bracket
    unit("nameplate1", 22, py=CY + proj(5, PH)); run(3.0)
    chk("a misleading plate height (5 yd) inside a 20-25 yd bracket: clamped into the bracket (%.1f)" % o.d, 20 - 1e-6 <= o.d <= 25 + 1e-6)

    # the SMOOTHING's speed limit: a jump from 18 to 40 yd moves the estimate at most VMAX (10) yd/s
    unit("nameplate1", 18); run(3.0)
    d0 = o.d
    unit("nameplate1", 40, py=CY + proj(40, PH))
    steps = []
    for _ in range(6):
        last = o.d
        run(1 / 30)
        steps.append(o.d - last)
    chk("smoothing: a jump of 22 yd moves at most 10 yd/s (largest step %.3f yd a frame; from %.1f)" % (max(steps), d0),
        max(steps) <= 10 / 30 + 1e-9 and max(steps) > 0)

    # COMBAT: a mob in combat fades out (0.3 s) and is back when it resets
    unit("nameplate1", 30, py=CY + proj(30, PH)); run(3.0)
    a0 = o.a
    Lg.UNITS.nameplate1.cb = True
    run(0.2)
    a1 = o.a
    run(0.3)
    chk("in combat: fading out (%.2f -> %.2f) and gone within 0.3 s more (%.2f)" % (a0, a1, o.a), a0 > 0.8 and 0 < a1 < a0 and o.a == 0
        and o.f.shown is False)
    Lg.UNITS.nameplate1.cb = False
    run(0.5)
    chk("reset (out of combat): back (%.2f)" % o.a, o.a > 0.8 and o.f.shown is True)

    # WHO: never a neutral mob, a dead one, one tapped by others; never a player (no slot at all)
    unit("nameplate2", 25, r=4, px=CX - 200); fire("NAME_PLATE_UNIT_ADDED", "nameplate2")
    unit("nameplate3", 25, dead=True, px=CX + 200); fire("NAME_PLATE_UNIT_ADDED", "nameplate3")
    unit("nameplate4", 25, td=True, px=CX + 300); fire("NAME_PLATE_UNIT_ADDED", "nameplate4")
    unit("nameplate5", 25, player=True); fire("NAME_PLATE_UNIT_ADDED", "nameplate5")
    run(1.0)
    has = lambda tok: A.byTok[tok] is not None and R.list[A.byTok[tok]] is not None and R.list[A.byTok[tok]].f.shown
    chk("no ring for a neutral mob, a dead one, a tapped one, or a player (%d rings)" % R.n,
        not has("nameplate2") and not has("nameplate3") and not has("nameplate4") and A.byTok.nameplate5 is None and R.n == 1)

    # NO PLATE WRITES: the plates were read every frame, never written
    chk("the plates only read (%d reads), nothing written to them (%d)" % (Lg.PLATE_READS, Lg.PLATE_WRITES),
        Lg.PLATE_READS > 100 and Lg.PLATE_WRITES == 0)
    chk("no CVar written beyond the layer's own nameplateShowEnemies", all(str(v).startswith("nameplateShowEnemies") for v in Lg.CVSET.values()))

    # THE SETTING: off -> every ring gone; on -> back
    R.set(False); run(0.2)
    chk("Settings' Aggro rings off: none shown (kept in our saved data)", R.n == 0 and o.f.shown is False and Lg.WowCompanionDB.aggroRings is False)
    R.set(True); run(0.5)
    chk("on again: back", R.n == 1 and Lg.WowCompanionDB.aggroRings is None)

    # NO PITCH YET: the stand-in pitch, said so
    C.wm.pitch = None; run(0.2)
    chk("no pitch read yet: the stand-in pitch (%s), the ring still placed" % R.src, R.src == "guess" and R.n == 1)
    C.wm.pitch = PHI

    # THE PULL LOG: every measured pull, with the rule's radius beside it
    n0 = len(Lg.SENT)
    st = A.slot[A.byTok.nameplate1]
    m = L.table_from({"s": A.byTok.nameplate1, "lv": 7, "a": 20, "om": 3, "cb": 1})
    st.calm = L.table_from({"b": 25, "t": G.t})
    A.lastAction = -100
    ok = A.pullCheck(A.byTok.nameplate1, st, m, G.t)
    lines = [Lg.SENT[i] for i in range(n0 + 1, len(Lg.SENT) + 1) if getattr(Lg.SENT[i], "probe", None) == "pull"]
    chk("a measured pull is logged: 20..25 yd for a level 7 on a level 6, the rule says %s" % (lines[0].rule if lines else None),
        ok is True and len(lines) == 1 and lines[0].lo == 20 and lines[0].hi == 25 and lines[0].rule == 21 and lines[0].lv == 7)
    # ar2 (test52: plates 2, slots 2, icons 2 - and no ring): the plate's position SECRET, as on Forever
    Lg.UNITS.nameplate1.secretPos = True
    run(1.0)
    o = R.list[A.byTok.nameplate1]
    chk("ar2: the plate's position secret: the ring still shown, ANCHORED to the plate (%s)" % R.whyLine(),
        o.f.shown is True and o.anchored is True and L.eval("rawequal(WowCompanion.rings.list[%d].f.point[2], UNITS.nameplate1.plate)" % A.byTok.nameplate1)
        and "shown (anchored)" in (R.whyLine() or ""))
    mrec = [A.last.m[i] for i in range(1, len(A.last.m) + 1) if A.last.m[i].s == A.byTok.nameplate1][0]
    chk("ar2: ... its distance from the range bracket instead (%.1f in %s..%s)" % (o.d, mrec.a, mrec.b), mrec.a - 1e-6 <= o.d <= mrec.b + 1e-6)
    chk("ar2: anchoring wrote nothing to the plate (%d)" % Lg.PLATE_WRITES, Lg.PLATE_WRITES == 0)
    # no upper bracket (beyond our largest check): lo + 10
    unit("nameplate1", 50, secretPos=True); run(5.0)
    chk("ar2: beyond the largest range check (30, no upper): 40 yd stands in (%.1f)" % o.d, abs(o.d - 40) < 0.5)
    unit("nameplate1", 30, py=CY + proj(30, PH)); run(3.0)
    # the reaction: one read plainly is kept for the plate (a record whose reaction is missing keeps its ring); never read: the reason
    R.react.cachedtok = 2
    okA = R.eligible(L.table_from({"s": 0}), "cachedtok")
    okB, whyB = R.eligible(L.table_from({"s": 0}), "nevertok")
    chk("ar2: a missing reaction: the plate's last plain one stands (%s); never read: no ring, \"%s\"" % (okA, whyB),
        okA is True and okB is False and whyB == "reaction secret")
    chk("ar2: every slot's reason in the probe: %s" % R.whyLine(), ("%d:not aggressive (4)" % A.byTok.nameplate2) in (R.whyLine() or "")
        and ("%d:dead" % A.byTok.nameplate3) in (R.whyLine() or "") and ("%d:tapped" % A.byTok.nameplate4) in (R.whyLine() or ""))
    line = None
    Lg.WowCompanion.live.probeAt = -100
    # ag7: the pull detector when the threat read is secret: "on me" = in combat and targeting the player; every check logged
    n0 = len(Lg.SENT)
    unit("nameplate9", 22, px=CX + 120); fire("NAME_PLATE_UNIT_ADDED", "nameplate9"); run(0.6)
    Lg.UNITS.nameplate9.d = 18; Lg.UNITS.nameplate9.cb = True; Lg.UNITS.nameplate9.om = Lg.SECRET; Lg.UNITS.nameplate9.onMe = True
    A.lastAction = -100
    run(0.3)
    lines = [Lg.SENT[i] for i in range(n0 + 1, len(Lg.SENT) + 1) if getattr(Lg.SENT[i], "probe", None) == "pull"]
    chk("ag7: a pull with the threat read secret is still caught (targeting us, in combat): %s"
        % [(l.ok, l.why, l.lo, l.hi) for l in lines], len(lines) == 1 and lines[0].ok is True and lines[0].lo == 10 and lines[0].hi == 25)
    n0 = len(Lg.SENT)
    unit("nameplate10", 22, px=CX + 220); fire("NAME_PLATE_UNIT_ADDED", "nameplate10"); run(0.6)
    A.lastAction = G.t                                   # the player attacked: a fight the player started
    Lg.UNITS.nameplate10.cb = True; Lg.UNITS.nameplate10.om = 3
    run(0.3)
    lines = [Lg.SENT[i] for i in range(n0 + 1, len(Lg.SENT) + 1) if getattr(Lg.SENT[i], "probe", None) == "pull"]
    chk("ag7: a refused check is logged too, with why (%s)" % [(l.ok, l.why) for l in lines],
        len(lines) == 1 and lines[0].ok is False and lines[0].why == "own action")
    print("failed: %d" % len(fails))
    sys.exit(1 if fails else 0)


if __name__ == "__main__":
    main()
