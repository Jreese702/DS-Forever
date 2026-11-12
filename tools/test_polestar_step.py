"""ps3 (the user, 2026-11-11: "The polestar tracked quest went missing from my quests"): Map.lua's Polestar step read.

Runs Map.lua's WC.pstep section under a stubbed Polestar Engine (read only):
- a waypoint: the step with m / x / y (0..1);
- no waypoint but a current step (a talk / use / train goal): t / g / q / nl, no none, no m;
- no guide (no current step): none;
- review: a done step (no focus) -> none; a quest only from an open goal; a gated Class guide's live step -> none;
- the 1 s ticker sends each of them (doc tag ps4) without an error, and WC.pstep.last carries no location for an nl step;
- ps4 (the user, 2026-11-11: "Okay now I actually do have a polestar objective that is not in my bag and not telling me where to go"):
  the waypoint's world spot (wx, wy, c); a waypoint in Polestar's Era map frame (f = "e") turned into the live frame by Polestar's own
  Nav.LiveCoords (ps3 sent the Era percent as if it were live: these checks fail on ps3), and sent with no spot when it cannot be;
- ps4 (the user, 2026-11-11: "My current polestar objective does not give any direction on where to go"): an item-started quest's
  accept goal carries it / ih / ic / itn (held by the bags' count; Polestar's starter.held when the count is not plain); the pad row's
  38, 8 / 38, 9 pick tell (int/1429: the one "row holds" word, WC.pnav.psOn, forgotten after KEEP_S).
"""
import os
import sys

import lupa

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MAPLUA = os.path.join(ROOT, "companion", "app", "src", "main", "assets", "companion", "addons", "WowCompanion", "Map.lua")

STUB = r'''
SAID = {}
WC = { send = function(s) SAID[#SAID + 1] = s end, encode = function(t) return t end, push = function() end,
       isPlain = function(v) return type(v) == "string" or type(v) == "number" end }
T = 0; function GetTime() return T end; function time() return 12345 end
TICK = nil
C_Timer = { After = function() end, NewTicker = function(_, fn) TICK = fn end }
WP = nil; IDX = nil; VIEWS = {}
Polestar = { Engine = {
    GetWaypoint = function() return WP end,
    GetStepIndex = function() return IDX end,
    GetStepView = function(i) return VIEWS[i] end,
} }
-- ps4: the game's map -> world (map 2521: x 0..1 -> world y 1000..0, y 0..1 -> world x 2000..1000, continent 1) and Polestar's
-- Nav.LiveCoords (an Era point on 2521: live x = era x / 2 + 10, live y = era y / 2 + 20; LC_FAIL: it cannot)
function CreateVector2D(x, y) return { x = x, y = y } end
C_Map = { GetWorldPosFromMapPos = function(m, v)
    if m ~= 2521 then return nil end
    return 1, { x = 2000 - 1000 * v.y, y = 1000 - 1000 * v.x }
end }
LC_FAIL = false
Polestar.Nav = { LiveCoords = function(p)
    if not p.f then return p.map, p.x, p.y end
    if LC_FAIL then return nil end
    return p.map, p.x / 2 + 10, p.y / 2 + 20
end }
'''

fails = []


def chk(name, ok):
    print(("ok   " if ok else "FAIL ") + name)
    if not ok:
        fails.append(name)


def run():
    src = open(MAPLUA, encoding="utf-8").read()
    a0 = src.index("WC.pstep = {")
    b0 = src.index("-- ---------------------------------------------------------------- QUESTTRAIL'S ARROW", a0)
    M = lupa.LuaRuntime()
    M.execute(STUB + src[a0:b0])
    read = lambda: M.eval("WC.pstep.read()")
    tick = lambda: M.eval("TICK")()
    said = lambda: M.eval("SAID[#SAID]")

    # a waypoint: the step's location
    M.execute('''
        VIEWS[3] = { title = "Wolves", goals = { { text = "Kill wolves", quest = 33, status = "active" } } }
        IDX = 3
        WP = { map = 1429, x = 40, y = 60, label = "Kill wolves", kind = "kill", stepIndex = 3, goalIndex = 1 }
    ''')
    d = read()
    chk("a waypoint: m / x / y", d.m == 1429 and abs(d.x - 0.4) < 1e-9 and abs(d.y - 0.6) < 1e-9 and d.q == 33 and d.nl is None
        and d.none is None)
    tick()
    s = said()
    chk("... sent as ps5 (int/1429) with its stamp", s is not None and s.type == "pstep" and s.v == "ps5" and s.b == 12345 and s.m == 1429)

    # ps4: the waypoint's world spot (map 2521, 54 / 39 -> world (1610, 460), continent 1)
    M.execute('''
        VIEWS[8] = { title = "The Strange Hermit", focus = 1, goals = { { text = "Accept The Strange Hermit", t = "accept", status = "active" } } }
        WP = { map = 2521, x = 54, y = 39, label = "Accept The Strange Hermit", kind = "accept", stepIndex = 8, goalIndex = 1 }
    ''')
    d = read()
    chk("ps4: a live waypoint: m / x / y as before, its world spot and continent", d.m == 2521 and abs(d.x - 0.54) < 1e-9
        and abs(d.y - 0.39) < 1e-9 and d.wx == 1610 and d.wy == 460 and d.c == 1 and d.fr is None and d.q is None)
    # an Era-frame point (f = "e"): Polestar's own Nav.LiveCoords gives the live percent (ps3 sent 0.54 / 0.39 as if live)
    M.execute("WP.f = 'e'")
    d = read()
    chk("ps4: an Era-frame waypoint: the live frame through Nav.LiveCoords (%s / %s)" % (d.x, d.y), d.m == 2521
        and abs(d.x - 0.37) < 1e-9 and abs(d.y - 0.395) < 1e-9 and d.fr == "e" and d.wx == 1605 and d.wy == 630)
    # it cannot be turned: a step with no spot (no wrong GO), the frame said
    M.execute("LC_FAIL = true; IDX = 8")
    d = read()
    chk("ps4: an Era-frame waypoint Polestar cannot turn: no spot (nl), fr said", d.nl is True and d.m is None and d.x is None
        and d.fr == "e" and d.t == "The Strange Hermit")
    M.execute("LC_FAIL = false; WP.map = 1429; WP.f = nil")
    d = read()
    chk("ps4: a map the game gives no world spot for: m / x / y, no wx / wy / c", d.m == 1429 and d.wx is None and d.c is None)
    M.execute("WP = nil; IDX = nil")

    # no waypoint, a current step (a talk goal; the first goal done, the marked one = 2)
    M.execute('''
        T = 1; WP = nil
        VIEWS[4] = { title = "Train at Kandris", status = "current", focus = 1, live = 4, current = 2, goals = {
            { text = "Go to Thunder Bluff", status = "done" },
            { text = "Talk to Kandris Dreamseeker", t = "talk", quest = 77, status = "active" } } }
        IDX = 4
    ''')
    d = read()
    chk("no waypoint but a step: t / g / q / nl (%s / %s / %s)" % (d.t, d.g, d.q),
        d.none is None and d.nl is True and d.m is None and d.x is None and d.t == "Train at Kandris"
        and d.g == "Talk to Kandris Dreamseeker" and d.q == 77 and d.k == "talk")
    n = M.eval("#SAID")
    ok = True
    try:
        tick()
    except Exception as e:      # noqa: BLE001
        ok = False
        print("  tick error:", e)
    s = said()
    chk("... the ticker sends it without an error", ok and M.eval("#SAID") == n + 1 and s.nl is True and s.v == "ps5" and s.m is None)
    last = M.eval("WC.pstep.last")
    chk("... WC.pstep.last has no location (the compass shows no Polestar point)", last.nl is True and last.m is None and last.none is None)

    # no marked goal: the first open one; a goal's quest from the first open goal that has one
    M.execute('''
        VIEWS[5] = { title = "Use the totem", status = "current", focus = 1, goals = {
            { text = "Done one", status = "done", quest = 1 },
            { text = "Use the Totem of Hawkwind", t = "use", status = "active" },
            { text = "Hand in", t = "turnin", status = "pending", quest = 88 } } }
        IDX = 5
    ''')
    d = read()
    chk("the first open goal's words, the first open goal's quest", d.g == "Use the Totem of Hawkwind" and d.q == 88 and d.nl is True)

    # review: a done step paged back to with Prev (the pin; GetStepView: status current, no focus, no marked goal, all done)
    M.execute('''
        VIEWS[2] = { title = "Turned in", status = "current", live = 4, goals = {
            { text = "Hand in Wolves", t = "turnin", quest = 33, status = "done" } } }
        IDX = 2
    ''')
    d = read()
    chk("a done step (no focus): none, no stale quest", d.none is True and d.q is None and d.nl is None)
    # a focus group but every goal closed: never a done goal's quest
    M.execute('''
        VIEWS[6] = { title = "Odd", status = "current", focus = 1, current = 1, goals = {
            { text = "Hand in Wolves", t = "turnin", quest = 33, status = "done" } } }
        IDX = 6
    ''')
    d = read()
    chk("no open goal: no quest, no done goal's words", d.q is None and d.g is None and d.t == "Odd")
    # a Class guide this character cannot start yet, on its live step: none (Polestar's arrow has nothing either)
    M.execute('''
        VIEWS[7] = { title = "Class start", status = "current", focus = 1, live = 7, goals = {
            { text = "Talk to the trainer", t = "talk", quest = 90, status = "active" } } }
        IDX = 7; GATE = { level = 10 }
        Polestar.Engine.GetGuide = function() return { id = "cls" } end
        Polestar.Engine.StartGate = function(id) return id == "cls" and GATE or nil end
    ''')
    chk("a gated Class guide's live step: none", read().none is True)
    M.execute("GATE = nil")
    d = read()
    chk("... once it can start: the step", d.nl is True and d.q == 90)

    # ps4: an item-started quest (Polestar's accept row with its item and starter; no waypoint), the item in the bags
    M.execute('''
        T = 3; WP = nil; COUNT = { [20310] = 1 }
        C_Item = { GetItemCount = function(id) return COUNT[id] or 0 end, GetItemIconByID = function(id) return 134939 end,
                   GetItemNameByID = function(id) return "Ripped Missive (game)" end }
        VIEWS[8] = { title = "Vayn Moongaze", status = "current", focus = 1, live = 8, current = 2, goals = {
            { text = "Collect Ripped Missive", t = "collect", item = 20310, status = "done", folded = true },
            { text = "Accept Ripped Missive", t = "accept", item = 20310, quest = 8460, status = "active",
              starter = { item = "Ripped Missive", held = true } } } }
        IDX = 8
    ''')
    d = read()
    chk("ps4: a held starter item: it / ih / ic / itn (%s %s %s %s)" % (d.it, d.ih, d.ic, d.itn),
        d.nl is True and d.it == 20310 and d.ih is True and d.ic == 134939 and d.itn == "Ripped Missive" and d.q == 8460
        and d.k == "accept")
    tick()
    s = said()
    chk("... sent with the item (ps4, doc tag ps5)", s.v == "ps5" and s.it == 20310 and s.ih is True)
    n = M.eval("#SAID")
    M.execute("T = 4; COUNT = {}")
    tick()
    chk("... the item gone from the bags: re-sent at once, ih false", M.eval("#SAID") == n + 1 and said().ih is False)
    M.execute("C_Item.GetItemCount = function() error('secret') end")
    chk("... a count the game will not give: Polestar's starter.held", read().ih is True)
    M.execute("VIEWS[8].goals[2].starter = { item = 'Ripped Missive' }")
    chk("... and no held from Polestar either: not held", read().ih is False)
    M.execute('''
        VIEWS[9] = { title = "Wolves", status = "current", focus = 1, current = 1, goals = {
            { text = "Talk to Vayn", t = "talk", quest = 33, status = "active", item = 999 } } }
        IDX = 9
    ''')
    d = read()
    chk("... a talk goal with an item field: no it (only an accept)", d.it is None and d.ih is None)

    # ps4: the pick tell (pad row pnav 38, then 8 / 9) and its time-out
    src2 = src[src.index("WC.pnav = {"):src.index("function WC.pnav.api()")]
    M.execute(src2)
    # int/1429: the pick tell IS ps-compass's "row holds" word: ONE reader, WC.pnav.psOn (KEEP_S 60 s, the stricter of 60 / 75)
    p1 = src.index("function WC.pnav.psOn()")
    M.execute(src[p1:src.index("\nend\n", p1) + 5])
    M.execute('''
        WC.pad = nil; OPS = {}
        WC.pad = { op = function(_, _, fn) OPS[#OPS + 1] = fn end }
    ''')
    a1 = src.index('if WC.pad and type(WC.pad.op) == "function" then\n    WC.pad.op("pnav"')
    a2 = src.index("\nend\n", a1) + 5
    M.execute(src[a1:a2])
    M.execute("WC.pqol = WC.pqol or { choose = function() end }; WC.pnav.cancel = function() end; WC.pnav.go = function() end")
    tap = lambda c: M.eval("OPS[1]")(None, c)
    picked = lambda: M.eval("WC.pnav.psOn()")
    chk("pick: none said: not picked", picked() is False)
    M.execute("T = 100"); tap(38); tap(8)
    chk("pick: 38, 8 = picked", picked() is True)
    chk("int/1429: PadMap's reader is the same function (no second 'picked' reader)", M.eval("WC.pnav.picked") is None
        and M.eval("WC.pnav.KEEP_S") == 60)
    M.execute("T = 159")
    chk("... still within KEEP_S (60 s)", picked() is True)
    M.execute("T = 161")
    chk("... no word for KEEP_S: not picked", picked() is False)
    M.execute("T = 200"); tap(38); tap(8); M.execute("T = 201"); tap(38); tap(9)
    chk("pick: 38, 9 = not picked", picked() is False)
    M.execute("T = 300"); tap(38); M.execute("T = 303"); tap(8)
    chk("pick: an 8 more than 2 s after 38 is no pick", picked() is False)
    # int/1429: ONE Lua reader - PadMap's starter-item arming (OV.qiStarter's callers) reads WC.pnav.psOn, the compass's own
    padmap = open(os.path.join(os.path.dirname(os.path.dirname(MAPLUA)), "WowCompanionSpells", "PadMap.lua"), encoding="utf-8").read()
    padmap = "\n".join(ln.split("--", 1)[0] for ln in padmap.splitlines())          # the code only (the history comments name it)
    chk("int/1429: PadMap reads the one 'row holds' word (WC.pnav.psOn), never a second reader",
        padmap.count("WC.pnav.psOn()") >= 2 and "WC.pnav.picked" not in padmap and "pickAt" not in src and "PICK_S" not in src)

    # no guide: none
    M.execute("T = 2; IDX = nil")
    d = read()
    chk("no guide: none", d.none is True and d.nl is None)
    tick()
    chk("... sent as none", said().none is True)
    M.execute("IDX = 99")
    chk("a step index with no view: none", read().none is True)

    # Polestar not loaded: nothing
    M.execute("Polestar = nil")
    chk("no Polestar: nil", read() is None)

    if fails:
        print("%d FAIL" % len(fails))
        sys.exit(1)
    print("all ok")


if __name__ == "__main__":
    run()
