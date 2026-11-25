"""THE AGGRO TINT'S TICK, LIGHTER (WowCompanion/Trail.lua WC.rings at11; test build 1529, the user's pick 2026-11-24: "Lighter add-ons +
log" = "... less per-frame Lua work"), off-device under test_tint's stub game (lupa, Lua 5.1):
  - MEASURED: the Lua memory the tint's frame handler allocates over 10 s at 60 frames a second with four tinted mobs (the collector
    stopped, so every byte made is counted; the stub's own SetVertexColor table is replaced by plain fields so only Trail.lua counts)
  - MEASURED: how often its tick runs at 60 and 144 frames a second (at11: at most every TICK_S 0.03 s, ~30 a second)
  - the tick still sees the elapsed time (the fade's speed is the same: a full fade-in takes the same time at 30 and at 60 frames)
Before at11 (Trail.lua at10) this measured 2532 bytes a frame and 60 ticks a second; at11: 4 bytes, 30 ticks. Needs lupa. Exit code 1 on any failure."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import test_aggro as ta  # noqa: E402
import test_compass as tc  # noqa: E402
import test_tint as tt  # noqa: E402


def main():
    L = tc.lupa.LuaRuntime()
    L.execute(tc.STUB)
    L.execute(ta.WORLD)
    L.execute(tt.PLATES)
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

    def run(secs, dt):
        for _ in range(int(round(secs / dt))):
            G.t = G.t + dt
            A.drainAt = G.t
            ev.scripts.OnUpdate(ev, dt)
            rf.scripts.OnUpdate(rf, dt)

    G.t = 100.0
    G.facing = 0.0
    fire("PLAYER_ENTERING_WORLD")
    Lg.WowCompanion.npWant = L.table_from({"hud": True})
    for tok, d, _, _ in tt.STATES:
        Lg.UNITS[tok] = L.table_from({"d": d, "lv": 6, "r": 2, "guid": "Creature-0-1-2-3-3098-0000AAAA", "ct": 1})
        fire("NAME_PLATE_UNIT_ADDED", tok)
    run(2.0, 1 / 30)
    shown = sum(1 for tok, _, _, _ in tt.STATES if A.byTok[tok] is not None and R.list[A.byTok[tok]] and R.list[A.byTok[tok]].f.shown)
    chk("four mobs tinted for the measurement (%d)" % shown, shown == 4)

    # the stub's SetVertexColor makes a table per call (the client's is a C call): plain fields, so only Trail.lua's own garbage counts
    L.execute("getmetatable(UIParent).SetVertexColor = function(self, r, g, b, a) self.c1, self.c2, self.c3, self.c4 = r, g, b, a end")
    L.execute("TICKS = 0; local t = WowCompanion.rings.tick; WowCompanion.rings.tick = function(dt) TICKS = TICKS + 1; return t(dt) end")
    rfOnUpdate = rf.scripts.OnUpdate
    measure = L.eval('''function(f, frames, dt)
        collectgarbage("collect"); collectgarbage("stop")
        local before = collectgarbage("count")
        for i = 1, frames do f.scripts.OnUpdate(f, dt) end
        local kb = collectgarbage("count") - before
        collectgarbage("restart")
        return kb
    end''')
    L.execute("TICKS = 0")
    frames60 = 600
    kb = measure(rf, frames60, 1 / 60)
    ticks60 = Lg.TICKS
    per = kb * 1024 / frames60
    print("MEASURED: the tint's handler at 60 fps, 4 mobs, 10 s: %.1f KB allocated (%.0f bytes a frame), %d ticks (%.0f a second)"
          % (kb, per, ticks60, ticks60 / 10.0))
    chk("at most a few bytes a frame (%.0f; at10 made 2532)" % per, per <= 16)
    chk("~30 ticks a second at 60 fps (%d in 10 s)" % ticks60, 290 <= ticks60 <= 310)
    L.execute("TICKS = 0")
    measure(rf, 1440, 1 / 144)
    chk("~29 ticks a second at 144 fps (%d in 10 s)" % Lg.TICKS, 270 <= Lg.TICKS <= 300)
    L.execute("TICKS = 0")
    measure(rf, 300, 1 / 30)
    chk("every frame at 30 fps (%d of 300)" % Lg.TICKS, Lg.TICKS == 300)

    # the fade keeps its speed: a combat fade-out takes FADE_S at 60 fps through 30 Hz ticks
    o = R.list[A.byTok.nameplate4]
    Lg.UNITS.nameplate4.cb = True
    steps = 0
    while o.a > 0 and steps < 200:
        G.t = G.t + 1 / 60
        A.drainAt = G.t
        ev.scripts.OnUpdate(ev, 1 / 60)
        rf.scripts.OnUpdate(rf, 1 / 60)
        steps += 1
    secs = steps / 60.0
    chk("the combat fade-out keeps its speed: %.2f s (FADE_S %.2f + the fight's read; at10 every frame: 0.37 s), at most a 30 Hz step or two more" % (secs, R.FADE_S),
        secs <= 0.37 + 2 * R.TICK_S + 1e-6)
    chk("the version tag at11", R.V == "at11")

    print("\n%d failed" % len(fails))
    sys.exit(1 if fails else 0)


if __name__ == "__main__":
    main()
