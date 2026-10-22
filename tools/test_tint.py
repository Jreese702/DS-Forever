"""The aggro tint on the nameplates (WowCompanion/Trail.lua, WC.rings at1), off-device: Trail.lua under the compass and aggro tests' stub
game.

    python tools/test_tint.py

Checks the radius rule (shared with Map's bottom-map circles), the states by distance (none / yellow / orange / red) and their margin,
who is tinted (aggressive, not dead, not tapped; never neutral or players; none in combat, with a fade), the frame anchored to the plate
and the plate never read or written, the smoothing's speed limit, the setting, and the pull log (ag7). Needs lupa. Exit code 1 on any
failure."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import test_aggro as ta  # noqa: E402
import test_compass as tc  # noqa: E402

PLATES = r'''
PLATE_WRITES, PLATE_READS = 0, 0
-- a plate: anything read or written on it is counted (the tint may only ANCHOR to it)
local function plateFor(u)
    local p = { GetCenter = function() PLATE_READS = PLATE_READS + 1; return 600, 400 end,
                GetEffectiveScale = function() PLATE_READS = PLATE_READS + 1; return 1 end }
    -- (at3: Forever's plate: its UnitFrame's health bar container - u.nobar: a plate without one)
    if not u.nobar then p.UnitFrame = { HealthBarsContainer = { name = "bar" } } end
    return setmetatable(p, { __newindex = function(t, k, v) PLATE_WRITES = PLATE_WRITES + 1; rawset(t, k, v) end })
end
TARGET = nil
function UnitIsUnit(a, b) if b == "target" then return TARGET ~= nil and a == TARGET end
    local tok = a:match("^(.-)target$"); local u = tok and UNITS[tok]; return u ~= nil and u.onMe == true and b == "player" end
C_NamePlate = { GetNamePlateForUnit = function(t) local u = UNITS[t]; if u then u.plate = u.plate or plateFor(u); return u.plate end end }
'''

STATES = [("nameplate1", 50, "far", [0.32, 0.86, 0.36]), ("nameplate2", 27, "approach", [1, 0.84, 0.24]),
          ("nameplate3", 22, "near", [1, 0.55, 0.12]), ("nameplate4", 15, "inside", [0.93, 0.16, 0.12])]


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
    A, R = Lg.WowCompanion.live, Lg.WowCompanion.rings
    frames = [Lg.FRAMES[i] for i in range(1, len(Lg.FRAMES) + 1)]
    ev = [f for f in frames if f.events and f.events.NAME_PLATE_UNIT_ADDED][0]
    rf = frames[-1]                                       # the tint's own updater (the last frame Trail.lua makes)

    def fire(e, a=None):
        ev.scripts.OnEvent(ev, e, a)

    tread = [0]                                          # plate reads made by the TINT's own updates (ag's sx read is its own)

    def run(secs, dt=1 / 30):
        for _ in range(int(round(secs / dt))):
            G.t = G.t + dt
            A.drainAt = G.t
            ev.scripts.OnUpdate(ev, dt)
            r0 = Lg.PLATE_READS
            rf.scripts.OnUpdate(rf, dt)
            tread[0] += Lg.PLATE_READS - r0

    def unit(tok, d, **kw):
        u = {"d": d, "lv": 6, "r": 2, "guid": "Creature-0-1-2-3-3098-0000AAAA", "ct": 1}
        u.update(kw)
        Lg.UNITS[tok] = L.table_from(u)

    def tint(tok):
        s = A.byTok[tok]
        return R.list[s] if s is not None else None

    # at2: Map's fit (2.2 yd per level under; at or above 1 yd per level; clamps 5..45; "??" = +10)
    cases = [((6, 6), 20), ((9, 6), 23), ((60, 6), 45), ((1, 20), 5), ((5, 6), 17.8), ((3, 6), 13.4), ((1, 6), 9.0),
             ((-1, 6), 30), ((None, 6), 30)]
    got = [R.radius(lv, plv) for (lv, plv), _ in cases]
    chk("the radius rule (shared with Map, at2): %s" % got, all(abs(g - w) < 1e-9 for g, (_, w) in zip(got, cases)))
    chk("at2: 7 under is the floor (5), 6 under 6.8", R.radius(6, 13) == 5 and abs(R.radius(7, 13) - 6.8) < 1e-9)

    G.t = 100.0
    G.facing = 0.0
    fire("PLAYER_ENTERING_WORLD")
    Lg.WowCompanion.npWant = L.table_from({"hud": True})
    # THE STATES by distance (the range bracket's middle; a level-6 mob for a level-6 player: radius 20)
    for tok, d, _, _ in STATES:
        unit(tok, d)
        fire("NAME_PLATE_UNIT_ADDED", tok)
    run(1.5)
    for tok, d, want, col in STATES:
        o = tint(tok)
        shown = o is not None and o.f.shown is True
        okc = col is None or (o is not None and [round(v, 2) for v in list(o.t.col.values())[:3]] == col)
        chk("%s yd -> %s (%.1f yd estimated, shown %s)" % (d, want, o.d if o else -1, shown),
            o is not None and o.state == want and shown and okc)
    o = tint("nameplate4")
    chk("at3: our own frame on UIParent, BACKGROUND, mouse off, our art (its bar-aspect rows %.3f), ANCHORED to the plate's HEALTH BAR, 9 out"
        % (o.t.tc[4] if o.t.tc else -1),
        L.eval("rawequal(WowCompanion.rings.list[%d].f.parent, UIParent)" % A.byTok.nameplate4) and o.f.strata == "BACKGROUND"
        and o.f.mouse is False and o.t.tex.endswith("tint_glow") and abs(o.t.tc[4] - 53 / 64) < 1e-9
        and L.eval("rawequal(WowCompanion.rings.list[%d].f.point[2], UNITS.nameplate4.plate.UnitFrame.HealthBarsContainer)" % A.byTok.nameplate4)
        and o.f.point[1] == "BOTTOMRIGHT" and o.f.point[4] == 9 and o.f.point[5] == -9)
    unit("nameplate15", 15, nobar=True); fire("NAME_PLATE_UNIT_ADDED", "nameplate15"); run(0.5)
    o15 = tint("nameplate15")
    chk("at3: a plate with no health bar container: anchored to the plate itself, 6 out (as before)",
        o15 is not None and L.eval("rawequal(WowCompanion.rings.list[%d].f.point[2], UNITS.nameplate15.plate)" % A.byTok.nameplate15)
        and o15.f.point[4] == 6)
    Lg.UNITS.nameplate15 = None; fire("NAME_PLATE_UNIT_REMOVED", "nameplate15"); run(0.2)
    chk("the tint never READS or WRITES a plate (only anchors to it): %d reads, %d writes" % (tread[0], Lg.PLATE_WRITES),
        tread[0] == 0 and Lg.PLATE_WRITES == 0)
    # the margin: a state changes only HYST (1) yd past its line on the way out; at once on the way in
    chk("margin: 20.5 yd from 'inside' stays inside; 21.5 moves to near; 19.5 from 'near' is inside at once",
        R.state(20.5, 20, "inside") == "inside" and R.state(21.5, 20, "inside") == "near" and R.state(19.5, 20, "near") == "inside")
    # the smoothing's speed limit
    o = tint("nameplate2")
    unit("nameplate2", 9)
    steps = []
    for _ in range(6):
        last = o.d
        run(1 / 30)
        steps.append(last - o.d)
    chk("smoothing: a jump of 17 yd moves at most 10 yd/s (largest %.3f yd a frame)" % max(steps), 0 < max(steps) <= 10 / 30 + 1e-9)
    unit("nameplate2", 27)
    run(3)
    # COMBAT: none (a fade), back on reset
    o = tint("nameplate4")
    Lg.UNITS.nameplate4.cb = True
    run(0.3)
    a1 = o.a
    run(0.35)
    chk("in combat: fading (%.2f) then none (%.2f)" % (a1, o.a), 0 < a1 < 0.95 and o.a == 0 and o.f.shown is False)
    Lg.UNITS.nameplate4.cb = False
    run(0.5)
    chk("reset (out of combat): red again", o.a > 0.9 and o.state == "inside")
    # WHO: never neutral, dead, tapped, players
    unit("nameplate5", 15, r=4); fire("NAME_PLATE_UNIT_ADDED", "nameplate5")
    unit("nameplate6", 15, dead=True); fire("NAME_PLATE_UNIT_ADDED", "nameplate6")
    unit("nameplate7", 15, td=True); fire("NAME_PLATE_UNIT_ADDED", "nameplate7")
    unit("nameplate8", 15, player=True); fire("NAME_PLATE_UNIT_ADDED", "nameplate8")
    run(1.0)

    def none(tok):
        return tint(tok) is None or tint(tok).f.shown is False
    chk("no tint for a neutral mob, a dead one, a tapped one, or a player (%s)" % R.whyLine(),
        none("nameplate5") and none("nameplate6") and none("nameplate7") and A.byTok.nameplate8 is None
        and ("%d:not aggressive (4)" % A.byTok.nameplate5) in (R.whyLine() or ""))
    # THE SETTING
    R.set(False)
    run(0.2)
    chk("Settings' Aggro tint off: none (kept in our saved data)", R.n == 0 and tint("nameplate4").f.shown is False
        and Lg.WowCompanionDB.aggroRings is False)
    R.set(True)
    run(0.5)
    chk("on again: back (%d tinted: at3 the far one green too)" % R.n, R.n == 4 and Lg.WowCompanionDB.aggroRings is None)
    chk("no plate read by the tint or written all along (%d / %d); no CVar beyond the layer's own" % (tread[0], Lg.PLATE_WRITES),
        tread[0] == 0 and Lg.PLATE_WRITES == 0 and all(str(v).startswith("nameplateShowEnemies") for v in Lg.CVSET.values()))

    # THE PULL LOG (ag7): a measured pull with the rule's radius; a pull with a secret threat read; a refusal with why
    n0 = len(Lg.SENT)
    st = A.slot[A.byTok.nameplate4]
    m = L.table_from({"s": A.byTok.nameplate4, "lv": 7, "a": 20, "om": 3, "cb": 1})
    st.calm = L.table_from({"b": 25, "t": G.t})
    A.lastAction = -100
    ok = A.pullCheck(A.byTok.nameplate4, st, m, G.t)
    lines = [Lg.SENT[i] for i in range(n0 + 1, len(Lg.SENT) + 1) if getattr(Lg.SENT[i], "probe", None) == "pull"]
    chk("a measured pull is logged: 20..25 yd for a level 7 on a level 6, the rule says %s" % (lines[0].rule if lines else None),
        ok is True and len(lines) == 1 and lines[0].lo == 20 and lines[0].hi == 25 and lines[0].rule == 21 and lines[0].lv == 7)
    n0 = len(Lg.SENT)
    unit("nameplate9", 22); fire("NAME_PLATE_UNIT_ADDED", "nameplate9"); run(0.6)
    Lg.UNITS.nameplate9.d = 18; Lg.UNITS.nameplate9.cb = True; Lg.UNITS.nameplate9.om = Lg.SECRET; Lg.UNITS.nameplate9.onMe = True
    A.lastAction = -100
    run(0.3)
    lines = [Lg.SENT[i] for i in range(n0 + 1, len(Lg.SENT) + 1) if getattr(Lg.SENT[i], "probe", None) == "pull"]
    chk("ag7: a pull with the threat read secret is still caught (targeting us, in combat): %s"
        % [(l.ok, l.why, l.lo, l.hi) for l in lines], len(lines) == 1 and lines[0].ok is True and lines[0].lo == 10 and lines[0].hi == 25)
    n0 = len(Lg.SENT)
    unit("nameplate10", 22); fire("NAME_PLATE_UNIT_ADDED", "nameplate10"); run(0.6)
    Lg.TARGET = "nameplate10"
    A.noteAction(G.t)                                    # the player attacked IT: a fight the player started
    Lg.TARGET = None
    Lg.UNITS.nameplate10.cb = True; Lg.UNITS.nameplate10.om = 3
    run(0.3)
    lines = [Lg.SENT[i] for i in range(n0 + 1, len(Lg.SENT) + 1) if getattr(Lg.SENT[i], "probe", None) == "pull"]
    chk("ag7: a refused check is logged too, with why (%s)" % [(l.ok, l.why) for l in lines],
        len(lines) == 1 and lines[0].ok is False and lines[0].why == "own action")
    # ag8: an attack / cast on ANOTHER mob (or none: a self-heal) no longer refuses this mob's pull
    n0 = len(Lg.SENT)
    unit("nameplate11", 22); fire("NAME_PLATE_UNIT_ADDED", "nameplate11")
    unit("nameplate12", 40); fire("NAME_PLATE_UNIT_ADDED", "nameplate12"); run(0.6)
    Lg.TARGET = "nameplate12"
    A.noteAction(G.t)
    Lg.TARGET = None
    A.noteAction(G.t)                                    # and a self-cast with no target
    Lg.UNITS.nameplate11.d = 18; Lg.UNITS.nameplate11.cb = True; Lg.UNITS.nameplate11.om = 3
    run(0.3)
    lines = [Lg.SENT[i] for i in range(n0 + 1, len(Lg.SENT) + 1) if getattr(Lg.SENT[i], "probe", None) == "pull"
             and Lg.SENT[i].n == A.slot[A.byTok.nameplate11].npc]
    chk("ag8: a cast on another mob / a self-cast does not refuse this pull: %s" % [(l.ok, l.why) for l in lines],
        len(lines) == 1 and lines[0].ok is True)
    # ag8: the mob enters combat, then turns on us 0.8 s later (was "stale" past 0.5 s): kept, measured from the onset
    n0 = len(Lg.SENT)
    unit("nameplate13", 22); fire("NAME_PLATE_UNIT_ADDED", "nameplate13"); run(0.6)
    Lg.UNITS.nameplate13.d = 18; Lg.UNITS.nameplate13.cb = True
    run(0.8)
    Lg.UNITS.nameplate13.d = 9; Lg.UNITS.nameplate13.om = 3
    run(0.3)
    lines = [Lg.SENT[i] for i in range(n0 + 1, len(Lg.SENT) + 1) if getattr(Lg.SENT[i], "probe", None) == "pull"]
    chk("ag8: a turn 0.8 s after combat starts is kept (not stale), the near end at the onset: %s"
        % [(l.ok, l.why, l.lo, l.hi) for l in lines], len(lines) == 1 and lines[0].ok is True and lines[0].lo == 10 and lines[0].hi == 25)
    # a turn long after (2 s) is still stale
    n0 = len(Lg.SENT)
    unit("nameplate14", 22); fire("NAME_PLATE_UNIT_ADDED", "nameplate14"); run(0.6)
    Lg.UNITS.nameplate14.cb = True
    run(2.0)
    Lg.UNITS.nameplate14.om = 3
    run(0.3)
    lines = [Lg.SENT[i] for i in range(n0 + 1, len(Lg.SENT) + 1) if getattr(Lg.SENT[i], "probe", None) == "pull"]
    chk("ag8: a turn 2 s after is still refused as stale: %s" % [(l.ok, l.why) for l in lines],
        len(lines) == 1 and lines[0].why == "stale")
    print("failed: %d" % len(fails))
    sys.exit(1 if fails else 0)


if __name__ == "__main__":
    main()
