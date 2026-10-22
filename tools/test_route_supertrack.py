"""test66: the route pin keeps the game's super-track through a flip that is no choice; only a real choice ends it.

Runs Map.lua's route-pin section WITH its event handler (SUPER_TRACKING_CHANGED) under stubs:
- a flip to the route's OWN quest (the game's auto-track, our own focus push) keeps the route and re-tracks our pin, logged with its source;
- a flip to ANOTHER quest (or none) is the player's choice: the route ends, its why naming the quest and the source;
- a KEEP finds the pin untracked mid-route (a flip that came while nothing was listening) and tracks it again.
"""
import os
import sys

import lupa

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MAPLUA = os.path.join(ROOT, "companion", "app", "src", "main", "assets", "companion", "addons", "WowCompanion", "Map.lua")

STUB = r'''
WC = { pad = { op = function(r, k, fn) UWP = fn end }, send = function() end, encode = function(t) SAID[#SAID + 1] = t end, push = function() end }
SAID = {}
T = 0; function GetTime() return T end; CALL = pcall; function currentMap() return 1429 end; function snapshotSoon() end
function superTrackedQuest() return 77 end; WowCompanionDB = {}; PIN = nil
STQ = 77; ONPIN = false; RETRACKS = 0
function stQuest() return STQ end
WC.ST_BY_S = 1.5
function WC.stTag(by) WC.stBy, WC.stByAt = by, GetTime() end
function WC.stSource() if WC.stBy and GetTime() - (WC.stByAt or -100) <= WC.ST_BY_S then return WC.stBy end return "the game" end
C_Map = { HasUserWaypoint = function() return PIN ~= nil end, GetUserWaypoint = function() return PIN end,
          CanSetUserWaypointOnMap = function() return true end, SetUserWaypoint = function(p) PIN = p end, ClearUserWaypoint = function() PIN = nil end }
C_SuperTrack = { SetSuperTrackedUserWaypoint = function(v) if v then ONPIN = true; RETRACKS = RETRACKS + 1 else ONPIN = false end end,
                 SetSuperTrackedQuestID = function(q) STQ = q; ONPIN = false end,
                 IsSuperTrackingUserWaypoint = function() return ONPIN end }
UiMapPoint = { CreateFromCoordinates = function(m, x, y) return { uiMapID = m, position = { x = x, y = y } } end }
FRAMES = {}
function CreateFrame() local f = { ev = {} }; function f:RegisterEvent(e) self.ev[e] = true end
    function f:SetScript(k, fn) self[k] = fn end; FRAMES[#FRAMES + 1] = f; return f end
C_Timer = { After = function() end, NewTicker = function() end }
function FIRE(e) for _, f in ipairs(FRAMES) do if f.ev[e] and f.OnEvent then f.OnEvent(f, e) end end end
'''

fails = []


def chk(name, ok):
    print(("ok   " if ok else "FAIL ") + name)
    if not ok:
        fails.append(name)


def run():
    src = open(MAPLUA, encoding="utf-8").read()
    a0 = src.index("WC.routeWp = {")
    b0 = src.index("-- ---------------------------------------------------------------- POLESTAR AS THE ROUTER", a0)
    M = lupa.LuaRuntime()
    M.execute(STUB + src[a0:b0])
    tap = lambda *cols: [M.eval("UWP")(None, c) for c in cols]
    R = lambda: M.eval("WC.routeWp")
    said = lambda: M.eval("SAID[#SAID]")

    tap(36, 24, 30, 11, 5)                         # the route's pin, for quest 77 (the super-tracked one)
    M.execute("ONPIN = true")
    chk("the route's pin set, standing for quest 77", R().active is True and R().qid == 77)

    # the GAME flips to the route's own quest (an objective update): the route stays, our pin tracked again
    M.execute("T = 10; STQ = 77; ONPIN = false; FIRE('SUPER_TRACKING_CHANGED')")
    s = said()
    chk("a game flip to the same quest keeps the route (%s / %s)" % (s.op, s.why),
        R().active is True and s.op == "retrack" and "by the game" in s.why and M.eval("ONPIN") is True)

    # our own focus push re-asserting the same quest: the same, its source named
    M.execute("T = 20; WC.stTag(\"the app's focus push\"); STQ = 77; ONPIN = false; FIRE('SUPER_TRACKING_CHANGED')")
    s = said()
    chk("a focus push to the same quest keeps it, named (%s)" % s.why, R().active is True and "the app's focus push" in s.why)

    # a KEEP finding our pin untracked mid-route (a flip nothing heard): tracked again
    M.execute("T = 40; ONPIN = false")
    n = M.eval("RETRACKS")
    tap(37)
    chk("a KEEP re-tracks an untracked pin", M.eval("RETRACKS") == n + 1 and M.eval("ONPIN") is True and said().why.startswith("keep"))
    # ... and leaves a tracked one alone
    n = M.eval("RETRACKS"); tap(37)
    chk("... and leaves a tracked pin alone", M.eval("RETRACKS") == n)

    # the player focuses ANOTHER quest: their choice ends the route
    M.execute("T = 60; STQ = 88; ONPIN = false; FIRE('SUPER_TRACKING_CHANGED')")
    s = said()
    chk("another quest ends the route (%s)" % s.why, R().active is False and s.op == "clear" and "quest 88" in s.why and "by the game" in s.why)

    # a route again; the player clears every super-track (none): ended too
    M.execute("T = 70; STQ = 77; ONPIN = false; PIN = nil")
    tap(36, 24, 30, 11, 5)
    M.execute("ONPIN = true; T = 80; STQ = 0; ONPIN = false; FIRE('SUPER_TRACKING_CHANGED')")
    chk("none at all ends it (%s)" % said().why, R().active is False and "(none" in said().why)


run()
print("%d failed" % len(fails))
sys.exit(1 if fails else 0)
