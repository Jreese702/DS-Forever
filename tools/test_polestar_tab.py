"""pg1, THE POLESTAR TAB (the user, 2026-11-16: "if Polestar is active it should have its own tab at the top of the quests tab"; the
picks: every recommended option of design_polestar): Map.lua's WC.pg, the REAL file loaded under tools/wowstub.py with a fake Polestar
(its Engine, Guides, Compat, On / Fire, CharState as Core/Engine.lua, Core/Guides.lua and Core/Init.lua behave):
- framed commands on the "pnav" row (38, 12, op, s1, s2, args, k): DROPPING ANY SINGLE TAP of any frame calls nothing, writes no
  automation mask (WowCompanionDB.pqol, Polestar's Set) and skips nothing; the next whole frame still works;
- a wrong check digit is "garbled", a stamp that no longer matches "changed" (a guide that chained on, another list, another tab),
  nothing called either; a tap later than 2 s after the one before it ends the frame; a slow but steady frame (each tap < 2 s) works;
- every op calls the one Engine function the design names (Goto, Unskip / Goto(live), SetGuide, SelectTab, ChooseRoute, ResetProgress,
  ToggleGoal) and reads (GetRouteChoice, GetStepView) for the rest; no window message, never ns.Set, never OfferRouteChoice;
- a fight: Load, Continue, Choose and Reset refused (why "combat", nothing called, never queued); Look, Back, Tick work;
- the docs: pguide always (its st / tabs only while the tab is open), pglist / pgsteps / pgstep / pgroute while open; a secret value
  (the stub's issecretvalue) is left out and the line still goes; statuses d / s / l / u / k; outlevelled; a waiting route;
- 38 then 0-11 as before (the automation mask, the row's word, Skip / Previous);
- THE PIN GUARD: every Engine / Guides name WC.pg uses is defined in the pinned Polestar source (E:/wow-build/polestar-src, v0.2.5 =
  ThirdPartyAddons' pin), so a pin bump that renames one fails here.
"""
import json
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from wowstub import boot, docs, nsent, SRC      # noqa: E402

POLESTAR_SRC = os.environ.get("POLESTAR_SRC", r"E:/wow-build/polestar-src/Polestar")

FAKE = r'''
CALLS = {}
local function call(n, ...) local a = {}; for i, v in ipairs({ ... }) do a[i] = tostring(v) end; CALLS[#CALLS + 1] = n .. "(" .. table.concat(a, ",") .. ")" end
SETS, FIRES, LISTEN = {}, {}, {}
LEVEL = 9
PSDB = { skipped = {}, manual = {}, flags = {} }        -- Polestar's CharState()
ROUTES = {}
local function steps(n, fn)
    local out = {}
    for i = 1, n do out[i] = fn(i) end
    return out
end
local function plainSteps(n, name)
    return steps(n, function(i) return { title = (i % 5 ~= 0) and (name .. " " .. i) or nil,
        goals = { { t = "kill", text = "Do " .. name .. " " .. i, quest = 100 + i } } } end)
end
GUIDES = {
    ["forever/alliance/1-10/elwynn"] = { id = "forever/alliance/1-10/elwynn", title = "Elwynn Forest", zone = 1429, zoneName = "Elwynn Forest",
        minLevel = 1, maxLevel = 10, faction = "Alliance", nextChoices = { "forever/alliance/10-20/westfall", "forever/alliance/10-20/lochmodan" },
        steps = plainSteps(12, "Elwynn") },
    ["forever/alliance/10-20/westfall"] = { id = "forever/alliance/10-20/westfall", title = "Westfall", zone = 1436, zoneName = "Westfall",
        minLevel = 10, maxLevel = 20, faction = "Alliance", pitch = "Farms and Defias", pros = { "Close", "Quick", "Many quests", "Fourth" },
        cons = { "Busy", "Long walks", "Third" }, next = "forever/alliance/20-30/redridge", steps = plainSteps(30, "Westfall") },
    ["forever/alliance/10-20/lochmodan"] = { id = "forever/alliance/10-20/lochmodan", title = "Loch Modan", zone = 1432, zoneName = "Loch Modan",
        minLevel = 10, maxLevel = 20, faction = "Alliance", pitch = "Dwarves and lakes", steps = plainSteps(20, "Loch") },
    ["forever/alliance/20-30/redridge"] = { id = "forever/alliance/20-30/redridge", title = "Redridge", zone = 1433, minLevel = 20, maxLevel = 30,
        faction = "Alliance", steps = plainSteps(25, "Redridge") },
    ["forever/horde/1-10/durotar"] = { id = "forever/horde/1-10/durotar", title = "Durotar", zone = 1411, minLevel = 1, maxLevel = 10,
        faction = "Horde", steps = plainSteps(10, "Durotar") },
    ["class/shaman/earth"] = { id = "class/shaman/earth", title = "Earth Totem", category = "Class", classes = { "SHAMAN" }, minLevel = 4,
        maxLevel = 10, group = { kind = "elite" }, steps = plainSteps(6, "Totem") },
    ["prof/fishing"] = { id = "prof/fishing", title = "Fishing", category = "Professions", minLevel = 5, maxLevel = 60, steps = plainSteps(8, "Fish") },
}
ORDER = { "forever/alliance/1-10/elwynn", "forever/alliance/10-20/westfall", "forever/alliance/10-20/lochmodan", "forever/alliance/20-30/redridge",
          "forever/horde/1-10/durotar", "class/shaman/earth", "prof/fishing" }
-- the engine's state: tabs, the active one, per guide the done steps and the look (pin)
TABS = { { id = "forever/alliance/1-10/elwynn" }, { id = "prof/fishing" } }
ACTIVE = 1
DONE = { ["forever/alliance/1-10/elwynn"] = { [1] = true, [2] = true, [3] = true } }
PIN = {}
SECRET_GOAL = false
local function g() local t = TABS[ACTIVE]; return t and GUIDES[t.id] end
local function sk(id, create) if create and not PSDB.skipped[id] then PSDB.skipped[id] = {} end; return PSDB.skipped[id] end
local function isDone(id, i) return DONE[id] and DONE[id][i] end
local function computed()
    local gd = g(); if not gd then return nil end
    local s = sk(gd.id) or {}
    for i = 1, #gd.steps do if not s[i] and not isDone(gd.id, i) then return i end end
    return nil
end
local function current() local gd = g(); if not gd then return nil end; return PIN[gd.id] or computed() end
local function fire(m, ...) FIRES[#FIRES + 1] = m; for _, fn in ipairs(LISTEN[m] or {}) do fn(...) end end
local function statusOf(i)
    local cur, comp = current(), computed()
    local gd = g()
    if i == cur then return "current" end
    if cur and i > cur then return "upcoming" end
    if isDone(gd.id, i) then return "done" end
    if comp and i >= comp and not (sk(gd.id) or {})[i] then return "upcoming" end
    return "skipped"
end
Polestar = {
    L = { Leveling = "Leveling", Class = "Class", Professions = "Professions" },
    On = function(msg, fn) LISTEN[msg] = LISTEN[msg] or {}; table.insert(LISTEN[msg], fn); return {} end,
    Fire = function(m, ...) fire(m, ...) end,
    CharState = function() return PSDB end,
    Get = function() return true end,
    Set = function(path, v) SETS[#SETS + 1] = tostring(path) end,
    FactionName = function(f) return f end,
    UI = { ShowPicker = function() call("UI.ShowPicker") end, ShowRouteChoice = function() call("UI.ShowRouteChoice") end },
    Compat = {
        GetPlayerInfo = function() return { name = "Korvash", realm = "Realm", level = LEVEL, faction = "Alliance", class = "SHAMAN", race = "Dwarf" } end,
        MapName = function(m) return ({ [1429] = "Elwynn Forest", [1436] = "Westfall", [1432] = "Loch Modan", [1433] = "Redridge Mountains" })[m] end,
    },
    Guides = {
        Get = function(id) return GUIDES[id] end,
        List = function()
            local out = {}
            for _, id in ipairs(ORDER) do
                local h = {}
                for k, v in pairs(GUIDES[id]) do if k ~= "steps" then h[k] = v end end
                h.stepCount = #GUIDES[id].steps
                out[#out + 1] = h
            end
            return out
        end,
        GuideMatches = function(h, p)
            if h.faction and p.faction and h.faction ~= p.faction then return false end
            if h.classes then for _, c in ipairs(h.classes) do if c == p.class then return true end end; return false end
            return true
        end,
        Suggestions = function()
            if LEVEL >= 10 then return { "forever/alliance/10-20/westfall", "forever/alliance/10-20/lochmodan" } end
            return { "forever/alliance/1-10/elwynn" }
        end,
        Suggest = function() return Polestar.Guides.Suggestions()[1] end,
    },
    Engine = {
        GetGuide = function() return g() end,
        GetStepIndex = function() return current() end,
        GetLiveStep = function() return computed() end,
        GetStepCount = function() local gd = g(); return gd and #gd.steps or 0 end,
        GetProgress = function()
            local gd = g(); if not gd then return 0, 0 end
            local d = 0
            for i = 1, #gd.steps do if isDone(gd.id, i) or (sk(gd.id) or {})[i] then d = d + 1 end end
            return d, #gd.steps
        end,
        GetWaypoint = function() return nil end,
        GetStepView = function(i)
            local gd = g(); if not gd or not gd.steps[i] then return nil end
            local st = gd.steps[i]
            local cur = current()
            local goals = {}
            for gi, goal in ipairs(st.goals) do
                local ticked = PSDB.manual[gd.id] and PSDB.manual[gd.id][i .. ":" .. gi] == "tick"
                goals[gi] = { index = gi, t = goal.t, text = (SECRET_GOAL and i == cur) and SECRETV or goal.text, quest = goal.quest,
                    status = ticked and "done" or (i == cur and "active" or "pending"), tickable = true, ticked = ticked,
                    progress = { cur = (SECRET_GOAL and i == cur) and SECRETV or 2, max = 8 } }
            end
            goals[#goals + 1] = { index = #goals + 1, t = "note", text = "A note", status = "active", tickable = false }
            return { index = i, title = st.title, note = (i == 4) and "Watch the |cffff0000murlocs|r" or nil, status = statusOf(i), goals = goals,
                focus = 1, current = (i == cur) and 1 or nil, skipped = (sk(gd.id) or {})[i] and true or false, live = computed() }
        end,
        GetTabs = function()
            local out = {}
            for i, t in ipairs(TABS) do
                local gd = GUIDES[t.id]
                out[i] = { guideId = t.id, title = gd.title, stepIndex = (i == ACTIVE) and current() or 1, stepCount = #gd.steps,
                    done = 0, total = #gd.steps, complete = false }
            end
            return out, ACTIVE
        end,
        SelectTab = function(i) call("SelectTab", i); ACTIVE = i; fire("GUIDE_CHANGED", TABS[i].id); fire("TABS_CHANGED") end,
        SetGuide = function(id)
            call("SetGuide", id)
            for i, t in ipairs(TABS) do if t.id == id then ACTIVE = i; fire("GUIDE_CHANGED", id); return true end end
            TABS[ACTIVE] = { id = id }; fire("GUIDE_CHANGED", id); return true
        end,
        Goto = function(i)
            call("Goto", i)
            local gd = g(); local s = sk(gd.id); if s then s[i] = nil end
            PIN[gd.id] = (i ~= computed()) and i or nil
            fire("STEP_CHANGED", current())
        end,
        Skip = function()
            call("Skip")
            local gd = g(); local s = current(); if not s then return nil end
            sk(gd.id, true)[s] = true
            if PIN[gd.id] then PIN[gd.id] = nil end
            fire("PLAYER_SKIPPED_STEP", gd.id, s)
            return s
        end,
        Unskip = function(i)
            call("Unskip", i)
            local gd = g(); local s = sk(gd.id)
            if not (s and s[i]) then return false end
            s[i] = nil; return true
        end,
        ToggleGoal = function(i, gi)
            call("ToggleGoal", i, gi)
            local gd = g()
            PSDB.manual[gd.id] = PSDB.manual[gd.id] or {}
            local key = i .. ":" .. gi
            if PSDB.manual[gd.id][key] then PSDB.manual[gd.id][key] = nil else PSDB.manual[gd.id][key] = "tick" end
            fire("STEP_UPDATED", current())
            return true
        end,
        ResetProgress = function()
            call("ResetProgress")
            local gd = g(); PSDB.manual[gd.id] = nil; PSDB.skipped[gd.id] = nil; PIN[gd.id] = nil
            fire("STEP_CHANGED", current())
        end,
        PendingRouteChoice = function()
            local gd = g()
            if gd and gd.nextChoices and computed() == nil and current() == nil and not ROUTES[gd.id] then return gd.id end
            return nil
        end,
        RouteChoiceFor = function(id)
            for _, gid in ipairs(ORDER) do
                local gd = GUIDES[gid]
                if gd.nextChoices then for _, c in ipairs(gd.nextChoices) do if c == id then return gid end end end
            end
        end,
        GetRouteChoice = function(from)
            local f = GUIDES[from]; if not (f and f.nextChoices) then return nil end
            local out = { from = from, fromTitle = f.title, recommended = f.nextChoices[1], chosen = ROUTES[from], options = {} }
            for i, id in ipairs(f.nextChoices) do
                local o = GUIDES[id]
                out.options[i] = { id = id, title = o.title, zone = o.zone, zoneName = o.zoneName, minLevel = o.minLevel, maxLevel = o.maxLevel,
                    pitch = o.pitch, pros = o.pros, cons = o.cons, seconds = 120 * i, quests = 28 - i, steps = #o.steps,
                    recommended = i == 1, chosen = ROUTES[from] == id, outleveled = false, open = false }
            end
            return out
        end,
        ChooseRoute = function(from, to)
            call("ChooseRoute", from, to)
            ROUTES[from] = to; TABS[ACTIVE] = { id = to }; fire("GUIDE_CHANGED", to); fire("ROUTE_CHOSEN", from, to); return true
        end,
        OfferRouteChoice = function() call("OfferRouteChoice") end,
        StartGate = function(id) if id == "class/shaman/earth" and LEVEL < 24 then return { level = 24 } end; return nil end,
        GetGuideState = function(id)
            for i, t in ipairs(TABS) do if t.id == id then return "started", (i == ACTIVE) and 3 or 0, #GUIDES[id].steps end end
            return "new"
        end,
    },
}
'''

fails = []


def chk(name, ok):
    print(("ok   " if ok else "FAIL ") + name)
    if not ok:
        fails.append(name)


def h1296(s):
    h = 5381
    for b in s.encode("utf-8"):
        h = (h * 33 + b) % 1296
    return h


def frame(op, stamp, *args):
    body = [op, stamp // 36, stamp % 36] + list(args)
    return [38, 12] + body + [sum(body) % 36]


def tap(L, *cols, dt=0.15):
    for c in cols:
        L.execute('WowCompanion.pad.rows.pnav.handler(nil, %d); NOW = NOW + %f' % (c, dt))


def settle(L):
    L.execute('advance(0.05)')


def last(L, typ, n0):
    ds = docs(L, n0, typ)
    return ds[-1] if ds else None


def calls(L):
    n = L.eval('#CALLS')
    return [L.eval('CALLS[%d]' % i) for i in range(1, n + 1)]


ELW = "forever/alliance/1-10/elwynn"
WF = "forever/alliance/10-20/westfall"
LOCH = "forever/alliance/10-20/lochmodan"


def fresh(pre=""):
    L = boot(["safety", "tappad", "maproom"], pre=FAKE + pre)
    return L


def opened(L):
    n0 = nsent(L)
    tap(L, *frame(13, 0))
    settle(L)
    return n0


def run():
    L = fresh()
    chk("the pnav row is there", L.eval('WowCompanion.pad.rows.pnav ~= nil'))

    # ---- the summary, closed: no statuses, no tabs; open: statuses, tabs, the list, the titles, the shown step
    n0 = nsent(L)
    L.execute('tick(1)')
    d = last(L, "pguide", n0)
    chk("pguide (pg1) at the first second: the guide, its stamp, zone, levels, step 4 of 12, progress 3/12",
        d is not None and d["v"] == "pg1" and d["have"] is True and d["gs"] == h1296(ELW) and d["t"] == "Elwynn Forest"
        and d["z"] == "Elwynn Forest" and d["zm"] == 1429 and d["lo"] == 1 and d["hi"] == 10 and d["s"] == 4 and d["l"] == 4
        and d["n"] == 12 and d["dn"] == 3 and d["tt"] == 12)
    chk("... closed: no statuses, no tabs, no list", "st" not in d and "tabs" not in d and not docs(L, n0, "pglist"))
    chk("... no name or realm in it", "Korvash" not in json.dumps(d) and "Realm" not in json.dumps(d))
    n0 = opened(L)
    ans = last(L, "pgop", n0)
    d = last(L, "pguide", n0)
    ld = last(L, "pglist", n0)
    ps = docs(L, n0, "pgsteps")
    sd = last(L, "pgstep", n0)
    chk("op 13 opens: answered ok", ans is not None and ans["op"] == 13 and ans["ok"] is True)
    chk("... pguide with the statuses (ddd l uuuuuuuu) and both tabs, the active one 1",
        d is not None and d.get("st") == "dddluuuuuuuu" and len(d.get("tabs", [])) == 2 and d.get("ta") == 1
        and d["tabs"][0]["s"] == h1296(ELW) and d["tabs"][1]["t"] == "Fishing" and d.get("tb") is True)
    chk("... pgsteps: 12 titles, step 5's from its first goal (%s)" % [x["t"] for x in ps], len(ps) == 1 and ps[0]["n"] == 12 and ps[0]["o"] == 1
        and ps[0]["t"][0] == "Elwynn 1" and ps[0]["t"][4] == "Do Elwynn 5")
    titles = [x["t"] for x in ld["g"]] if ld else []
    chk("pglist: only what this Alliance Shaman can follow, in folders (%s)" % titles, ld is not None and "Durotar" not in titles
        and titles[:4] == ["Elwynn Forest", "Loch Modan", "Westfall", "Redridge"] and "Earth Totem" in titles and "Fishing" in titles)
    chk("... the folders: Alliance 1-30, then Class, then Professions (%s)" % (ld["f"] if ld else None),
        ld is not None and ld["f"][0].startswith("Alliance 1") and "Class" in ld["f"] and "Professions" in ld["f"]
        and ld["f"].index("Class") < ld["f"].index("Professions"))
    el = ld["g"][0]
    tot = [x for x in ld["g"] if x["t"] == "Earth Totem"][0]
    chk("... chips: Elwynn started 3/12 in tab 1, suggested 1st, here; the class guide a group one gated at level 24",
        el["st"] == "s" and el["dn"] == 3 and el["tt"] == 12 and el["tb"] == 1 and el["sg"] == 1 and el.get("n") == 12
        and tot.get("gr") is True and tot.get("gl") == 24 and tot["st"] == "n")
    chk("... its stamp is the ids' hash", ld["ls"] == h1296("\n".join([ELW, WF, LOCH, "forever/alliance/20-30/redridge", "class/shaman/earth", "prof/fishing"])))
    chk("pgstep: the shown step 4 with its goals (status, words, 2/8), the note's colour codes gone",
        sd is not None and sd["i"] == 4 and sd["gs"] == h1296(ELW) and sd["nt"] == "Watch the murlocs"
        and sd["g"][0]["x"] == "Do Elwynn 4" and sd["g"][0]["c"] == 2 and sd["g"][0]["m"] == 8 and sd["g"][0]["tk"] is True)
    chk("... a note goal is not tickable", sd["g"][1].get("tk") is None)

    # ---- every op, whole: the one Polestar call each
    gs = h1296(ELW)
    L.execute('CALLS = {}')
    n0 = nsent(L)
    tap(L, *frame(15, gs, 0, 9))
    settle(L)
    a = last(L, "pgop", n0)
    d = last(L, "pguide", n0)
    chk("op 15 looks at step 9: Goto(9), ok, the summary at once (shown 9, live 4)", calls(L) == ["Goto(9)"] and a["ok"] is True
        and a["i"] == 9 and d is not None and d["s"] == 9 and d["l"] == 4)
    chk("... the statuses: the live step still 4", d.get("st") == "dddluuuuuuuu")
    L.execute('CALLS = {}')
    tap(L, *frame(16, gs))
    settle(L)
    chk("op 16 back (not skipped): Goto(the live step 4)", calls(L) == ["Goto(4)"] and L.eval('Polestar.Engine.GetStepIndex()') == 4)
    L.execute('Polestar.Engine.Goto(7); Polestar.Engine.Skip(); CALLS = {}')
    L.execute('PIN[ "%s" ] = 7' % ELW)
    n0 = nsent(L)
    tap(L, *frame(16, gs))
    settle(L)
    chk("op 16 back on a skipped looked-at step: Unskip(7) (the away banner's own action)", calls(L) == ["Unskip(7)"])
    L.execute('PIN[ "%s" ] = nil; CALLS = {}' % ELW)
    L.execute('Polestar.Engine.Goto(6); Polestar.Engine.Skip(); Polestar.Engine.Goto(4); CALLS = {}')
    n0 = nsent(L)
    tap(L, *frame(13, 0))
    settle(L)
    d = last(L, "pguide", n0)
    chk("statuses: a step skipped ahead reads k (%s)" % d.get("st"), d.get("st") == "dddlukuuuuuu")
    L.execute('CALLS = {}')
    n0 = nsent(L)
    tap(L, *frame(23, gs, 0, 4, 1))
    settle(L)
    sd = last(L, "pgstep", n0)
    chk("op 23 ticks step 4's goal 1: ToggleGoal(4,1), its goal doc again, ticked", calls(L) == ["ToggleGoal(4,1)"] and sd is not None
        and sd["i"] == 4 and sd["g"][0].get("ti") is True and last(L, "pgop", n0)["ok"] is True)
    tap(L, *frame(23, gs, 0, 4, 1))
    settle(L)
    chk("... again clears it", calls(L)[-1] == "ToggleGoal(4,1)" and L.eval('PSDB.manual["%s"]["4:1"]' % ELW) is None)
    L.execute('CALLS = {}')
    n0 = nsent(L)
    tap(L, *frame(22, gs, 0, 11))
    sd = last(L, "pgstep", n0)
    chk("op 22 describes step 11: read only, its doc", calls(L) == [] and sd is not None and sd["i"] == 11 and sd["t"] == "Elwynn 11")
    ld = last(L, "pglist", 0)
    ls = ld["ls"]
    k = [x["t"] for x in ld["g"]].index("Loch Modan") + 1
    L.execute('CALLS = {}')
    n0 = nsent(L)
    tap(L, *frame(17, ls, k // 36, k % 36))
    settle(L)
    chk("op 17 loads list guide %d (Loch Modan): SetGuide, never ns.Set" % k, calls(L) == ["SetGuide(%s)" % LOCH] and L.eval('#SETS') == 0
        and last(L, "pgop", n0)["ok"] is True and last(L, "pguide", n0)["t"] == "Loch Modan")
    L.execute('CALLS = {}')
    tabs = last(L, "pguide", 0)["tabs"]
    n0 = nsent(L)
    tap(L, *frame(18, tabs[1]["s"], 2))
    settle(L)
    chk("op 18 continues tab 2 (stamped with its guide): SelectTab(2)", calls(L) == ["SelectTab(2)"] and last(L, "pguide", n0)["t"] == "Fishing")
    L.execute('CALLS = {}')
    n0 = nsent(L)
    tap(L, *frame(18, h1296(ELW), 2))
    chk("... a tab stamp that does not match: changed, nothing called", calls(L) == [] and last(L, "pgop", n0)["why"] == "changed")
    tap(L, *frame(17, ls, 0, 1))                    # back to Elwynn (list #1), and finish it: the path choice waits
    L.execute('DONE["%s"] = {}; for i = 1, 12 do DONE["%s"][i] = true end; PSDB.skipped = {}; PIN = {}' % (ELW, ELW))
    L.execute('Polestar.Fire("STEP_CHANGED")')
    n0 = nsent(L)
    L.execute('tick(1)')
    settle(L)
    d = last(L, "pguide", n0)
    chk("the guide done, its path choice waiting: cp, rw, rc, fs = its stamp", d is not None and d.get("cp") is True and d.get("rw") is True
        and d.get("rc") is True and d.get("fs") == h1296(ELW) and "s" not in d)
    L.execute('CALLS = {}')
    n0 = nsent(L)
    tap(L, *frame(19, h1296(ELW)))
    r = last(L, "pgroute", n0)
    chk("op 19 sends the choice: read only, 2 options, pitch, at most 3 pros and 2 cons, the recommended one",
        calls(L) == [] and r is not None and r["fs"] == h1296(ELW) and r["ft"] == "Elwynn Forest" and len(r["o"]) == 2
        and r["o"][0]["t"] == "Westfall" and r["o"][0]["rec"] is True and r["o"][0]["pt"] == "Farms and Defias"
        and r["o"][0]["pr"] == ["Close", "Quick", "Many quests"] and r["o"][0]["cn"] == ["Busy", "Long walks"] and r["o"][0]["sec"] == 120
        and r["o"][0]["q"] == 27 and r["o"][1]["t"] == "Loch Modan")
    n0 = nsent(L)
    tap(L, *frame(20, h1296(ELW), 1))
    settle(L)
    chk("op 20 chooses option 1: ChooseRoute(from, Westfall); no OfferRouteChoice", calls(L) == ["ChooseRoute(%s,%s)" % (ELW, WF)]
        and last(L, "pgop", n0)["ok"] is True and last(L, "pguide", n0)["t"] == "Westfall")
    L.execute('CALLS = {}')
    n0 = nsent(L)
    tap(L, *frame(21, h1296(WF)))
    settle(L)
    chk("op 21 resets: ResetProgress", calls(L) == ["ResetProgress()"] and last(L, "pgop", n0)["ok"] is True)
    L.execute('CALLS = {}')
    n0 = nsent(L)
    tap(L, *frame(21, h1296(ELW)))
    chk("... stamped for the guide before (it chained on): changed, nothing reset", calls(L) == [] and last(L, "pgop", n0)["why"] == "changed")
    tap(L, *frame(15, h1296(ELW), 0, 3))
    chk("... a look stamped for the old guide: nothing", calls(L) == [])

    # ---- outlevelled: level >= max + 2, the suggestion (and its index while open)
    L.execute('LEVEL = 23; TABS[ACTIVE] = { id = "%s" }; DONE["%s"] = {}' % (ELW, ELW))
    L.execute('Polestar.Fire("GUIDE_CHANGED"); fire("PLAYER_LEVEL_UP")')
    n0 = opened(L)
    d = last(L, "pguide", n0)
    chk("outlevelled: ol, Westfall suggested, its list index, offered by a path choice (oc)", d is not None and d.get("ol") is True
        and d.get("ot") == "Westfall" and d.get("oi") == 3 and d.get("oc") is True)
    L.execute('LEVEL = 9; Polestar.Fire("GUIDE_CHANGED"); fire("PLAYER_LEVEL_UP")')

    # ---- DROP ANY SINGLE TAP: nothing called, no mask written, nothing skipped; the next whole frame works
    L.execute('WowCompanionDB.pqol = nil; SETS = {}')
    n0 = opened(L)
    gs = last(L, "pguide", n0)["gs"]
    ld = last(L, "pglist", 0)
    tabs = last(L, "pguide", n0)["tabs"]
    plan = [(15, gs, (0, 3)), (15, gs, (1, 1)), (16, gs, ()), (17, ld["ls"], (0, 3)), (18, tabs[1]["s"], (2,)), (20, h1296(ELW), (1,)),
            (21, gs, ()), (22, gs, (0, 7)), (23, gs, (0, 4, 1)), (23, gs, (0, 3, 3)), (19, gs, ()), (13, 0, ()), (14, 0, ())]
    bad = []
    for op, st, args in plan:
        f = frame(op, st, *args)
        for drop in range(len(f)):
            M = fresh()
            tap(M, *frame(13, 0))
            settle(M)
            M.execute('CALLS = {}; SETS = {}; WowCompanionDB.pqol = nil; SKIPS0 = 0')
            sk0 = M.eval('(function() local n = 0; for _, t in pairs(PSDB.skipped) do for _ in pairs(t) do n = n + 1 end end; return n end)()')
            n1 = nsent(M)
            cut = f[:drop] + f[drop + 1:]
            tap(M, *cut)
            # the next command right behind it (a Skip's word, 38 then 10, is its own two taps): the frame ends unfinished
            seen = calls(M)
            wrote = M.eval('WowCompanionDB.pqol ~= nil') or M.eval('#SETS') > 0
            sk1 = M.eval('(function() local n = 0; for _, t in pairs(PSDB.skipped) do for _ in pairs(t) do n = n + 1 end end; return n end)()')
            answered_ok = [x for x in docs(M, n1, "pgop") if x.get("ok") and x.get("op") not in (13, 14)]
            if seen or wrote or sk1 != sk0 or answered_ok:
                bad.append((op, drop, cut, seen, wrote))
            # then a whole frame still works
            M.execute('NOW = NOW + 3; CALLS = {}')
            tap(M, *frame(15, h1296(ELW), 0, 6))
            if calls(M) != ["Goto(6)"]:
                bad.append(("after", op, drop, calls(M)))
    chk("dropping any single tap of any frame (%d frames): nothing called, no mask, no skip, no ok (%s)" % (len(plan), bad[:3]), not bad)

    # ... with the next command right behind the cut frame (no pause): still nothing, and that command works as before
    M = fresh()
    tap(M, *frame(13, 0))
    settle(M)
    M.execute('CALLS = {}')
    f = frame(15, h1296(ELW), 0, 9)
    tap(M, *(f[:4] + f[5:]))                        # a lost digit...
    tap(M, 38, 10)                                  # ...and the next press, a Skip, right behind
    chk("a cut frame and a Skip right behind it: only the Skip runs", calls(M) == ["Skip()"])
    M.execute('CALLS = {}')
    tap(M, *(f[:2] + f[3:]))                        # the op lost: 38, 12, s1 ... - s1 (0) must not be read as the op
    tap(M, *frame(15, h1296(ELW), 0, 8))
    chk("... the op lost, the next frame whole: only Goto(8)", calls(M) == ["Goto(8)"])
    M.execute('CALLS = {}; SETS = {}; WowCompanionDB.pqol = nil')
    tap(M, *(f[:1] + f[2:]))                        # the 12 lost: 38 then 15 - the old handler's (no mask: 15 > 7)
    chk("... the 12 lost (38 then 15): no mask, nothing", calls(M) == [] and M.eval('WowCompanionDB.pqol == nil') and M.eval('#SETS') == 0)

    # ---- garbled, late, slow
    M.execute('CALLS = {}')
    n0 = nsent(M)
    f = frame(15, h1296(ELW), 0, 9)
    f[-1] = (f[-1] + 1) % 36
    tap(M, *f)
    chk("a wrong check digit: garbled, nothing called", calls(M) == [] and last(M, "pgop", n0)["why"] == "garbled")
    f = frame(15, h1296(ELW), 0, 5)
    f[5] = 7                                         # a tap read as its neighbour
    n0 = nsent(M)
    tap(M, *f)
    chk("a digit read wrong: garbled", calls(M) == [] and last(M, "pgop", n0)["why"] == "garbled")
    f = frame(15, h1296(ELW), 0, 9)
    tap(M, *f[:5])
    M.execute('NOW = NOW + 2.5')
    tap(M, *f[5:])
    chk("a tap 2.5 s after the one before: the frame ends, nothing called", calls(M) == [])
    tap(M, *frame(15, h1296(ELW), 0, 9), dt=1.8)
    chk("a slow frame (each tap 1.8 s after the last, 12+ s in all): it works", calls(M) == ["Goto(9)"])

    # ---- combat (D3)
    M.execute('COMBAT = true; CALLS = {}')
    ld = last(M, "pglist", 0)
    tabs = last(M, "pguide", 0)["tabs"]
    refused = []
    for op, st, args in [(17, ld["ls"], (0, 3)), (18, tabs[1]["s"], (2,)), (20, h1296(ELW), (1,)), (21, h1296(ELW), ())]:
        n0 = nsent(M)
        tap(M, *frame(op, st, *args))
        a = last(M, "pgop", n0)
        refused.append((op, a["why"] if a else None))
    chk("in a fight Load, Continue, Choose and Reset are refused (combat), nothing called (%s)" % refused,
        calls(M) == [] and all(w == "combat" for _, w in refused))
    M.execute('COMBAT = false; advance(5)')
    chk("... never queued: nothing runs when the fight ends", calls(M) == [])
    M.execute('COMBAT = true')
    live = M.eval('Polestar.Engine.GetLiveStep()')
    tap(M, *frame(15, h1296(ELW), 0, 10))
    tap(M, *frame(16, h1296(ELW)))
    tap(M, *frame(23, h1296(ELW), 0, 4, 1))
    chk("... Look, Back and Tick work in it (%s)" % calls(M), calls(M) == ["Goto(10)", "Goto(%d)" % live, "ToggleGoal(4,1)"])
    M.execute('COMBAT = false')

    # ---- leave no trace: no window message, never ns.Set, never OfferRouteChoice / UI.Show*
    every = calls(L) + calls(M)
    wins = [m for m in (L.eval('FIRES') and [L.eval('FIRES[%d]' % i) for i in range(1, L.eval('#FIRES') + 1)])
            if re.match(r"(SHOW|TOGGLE)_", m or "")]
    chk("no window message, no ns.Set, no OfferRouteChoice, no UI.Show* (%s)" % wins, not wins and L.eval('#SETS') == 0 and M.eval('#SETS') == 0
        and not any(("OfferRouteChoice" in c or c.startswith("UI.")) for c in every))
    chk("nothing in WowCompanionDB but what was there", L.eval('WowCompanionDB.pg == nil and WowCompanionDB.pqol == nil'))

    # ---- a secret value is left out, the line still goes
    S = fresh('SECRET_GOAL = true')
    n0 = nsent(S)
    tap(S, *frame(13, 0))
    settle(S)
    sd = last(S, "pgstep", n0)
    raw = [S.eval('SENT[%d]' % i) for i in range(n0 + 1, nsent(S) + 1)]
    chk("a secret goal text / count: the step doc still goes, the goal without them (%s)" % (sd and sd["g"]), sd is not None
        and sd["g"][0].get("x") is None and "c" not in sd["g"][0] and sd["g"][0].get("tk") is True and sd["g"][1]["x"] == "A note")
    chk("... and no line carries a secret", all("secret" not in (x or "") for x in raw))

    # ---- the tab closed: no list / steps / goals on later changes; the summary still
    n0 = nsent(L)
    tap(L, *frame(14, 0))
    L.execute('Polestar.Engine.Goto(6); tick(1)')
    settle(L)
    chk("op 14 closes: later changes send pguide only (no st, no tabs)", docs(L, n0, "pglist") == [] and docs(L, n0, "pgsteps") == []
        and docs(L, n0, "pgstep") == [] and all("st" not in x and "tabs" not in x for x in docs(L, n0, "pguide")))
    n0 = nsent(L)
    tap(L, *frame(13, 0))
    L.execute('NOW = NOW + 61; tick(1); Polestar.Engine.Goto(7); tick(1)')
    settle(L)
    chk("... the open lapses after 60 s without a say", all("tabs" not in x for x in docs(L, n0 + 0, "pguide")[-1:]))

    # ---- coalesced: several Polestar messages in one second, one summary
    n0 = nsent(L)
    L.execute('for i = 1, 5 do Polestar.Fire("STEP_UPDATED") end; tick(1)')
    L.execute('tick(1)')
    chk("five messages, one pguide", len(docs(L, n0, "pguide")) <= 1)

    # ---- the old words of 38 stay
    O = fresh()
    O.execute('WowCompanionDB.pqol = nil')
    tap(O, 38, 3)
    chk("38 then 3: the automation mask, as before", O.eval('WowCompanionDB.pqol ~= nil'))
    tap(O, 38, 8)
    chk("38 then 8: the row holds, as before", O.eval('WowCompanion.pnav.psOn()') is True)
    O.execute('CALLS = {}')
    tap(O, 38, 10)
    chk("38 then 10: Skip, as before", calls(O) == ["Skip()"])
    tap(O, 12, 15, 0, 0, 15)
    chk("12 without 38: nothing", calls(O) == ["Skip()"])

    # ---- no Polestar / an old one: no doc, a frame answered "no Polestar"
    N = boot(["safety", "tappad", "maproom"], pre='')
    n0 = nsent(N)
    N.execute('tick(1)')
    tap(N, *frame(15, 5, 0, 3))
    a = last(N, "pgop", n0)
    chk("no Polestar: no pguide, a look answered 'no Polestar'", docs(N, n0, "pguide") == [] and a is not None and a["why"] == "no Polestar")

    # ---- THE PIN GUARD: every Engine / Guides / namespace name WC.pg calls is in the pinned source
    sec = SRC["maproom"]
    a0 = sec.index("-- ---------------------------------------------------------------- POLESTAR TAB (pg1)")
    b0 = sec.index("-- ---------------------------------------------------------------- QUESTTRAIL'S ARROW", a0)
    sec = sec[a0:b0]
    eng = sorted(set(re.findall(r"\bE\.([A-Z][A-Za-z]+)", sec)))
    gui = sorted(set(re.findall(r"\bG\.([A-Z][A-Za-z]+)", sec)) - {"LEN"})
    nsn = sorted(set(re.findall(r"\bP\.([A-Z][A-Za-z]+)", sec)))
    if os.path.isdir(POLESTAR_SRC):
        def read(rel):
            return open(os.path.join(POLESTAR_SRC, rel), encoding="utf-8").read()
        src_e, src_g = read("Core/Engine.lua"), read("Core/Guides.lua")
        src_ns = read("Core/Init.lua") + read("Core/Compat.lua")
        miss = [n for n in eng if not re.search(r"function Engine\.%s\b" % n, src_e)]
        miss += [n for n in gui if not re.search(r"(function Guides\.%s\b|Guides\.%s\s*=)" % (n, n), src_g)]
        miss += [n for n in nsn if n not in ("Engine", "Guides", "Compat", "L", "On", "UI") and not re.search(r"function ns\.%s\b" % n, src_ns)]
        comp = sorted(set(re.findall(r"\bC\.([A-Z][A-Za-z]+)", sec)))
        miss += [n for n in comp if not re.search(r"function Compat\.%s\b" % n, src_ns)]
        chk("pin guard: Engine %s, Guides %s, ns %s all defined in the pinned Polestar (missing: %s)" % (eng, gui, nsn, miss), not miss)
        chk("pin guard: the source is v0.2.5 (ThirdPartyAddons' pin)", "0.2.5" in read("Polestar.toc"))
    else:
        print("skip pin guard: no Polestar source at %s" % POLESTAR_SRC)


run()
print("%d failed" % len(fails) if fails else "all ok")
sys.exit(1 if fails else 0)
