"""test69 ("first pin is 55 yards, the next one after is 1555 yards"): Map.lua's route row decoded exactly as the app sends it.

- every SET shape the app sends round-trips: the pin + 2 further points with heights; the further list replaced (38 38 + points);
  a pass row; the goal as more2 (the rows taken from test69's own log);
- the further points are decoded on the PIN'S map, never the map the game names that instant (a micro-map near its edge);
- a further point more than MORE_JUMP_YD from the point before it is refused and logged.
"""
import os
import sys

import lupa

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MAPLUA = os.path.join(ROOT, "companion", "app", "src", "main", "assets", "companion", "addons", "WowCompanion", "Map.lua")

STUB = r'''
WC = { pad = { op = function(r, k, fn) UWP = fn end }, send = function() end, encode = function(t) SAID[#SAID + 1] = t end, push = function() end }
SAID = {}
T = 0; function GetTime() return T end; CALL = pcall; MAP = 2521; function currentMap() return MAP end; function snapshotSoon() end
function superTrackedQuest() return 92645 end; WowCompanionDB = {}; PIN = nil
-- the world from a map point: map 2521 is 3000 x 3000 yd; map 9999 (a town's micro-map) 200 x 200 yd far to the north
SCALE = { [2521] = { 3000, 0 }, [9999] = { 200, 5000 } }
function CreateVector2D(x, y) return { x = x, y = y } end
C_Map = { HasUserWaypoint = function() return PIN ~= nil end, GetUserWaypoint = function() return PIN end,
          CanSetUserWaypointOnMap = function() return true end, SetUserWaypoint = function(p) PIN = p end, ClearUserWaypoint = function() PIN = nil end,
          GetWorldPosFromMapPos = function(m, v) local s = SCALE[m]; return 1, { x = s[2] + v.x * s[1], y = v.y * s[1] } end }
C_SuperTrack = { SetSuperTrackedUserWaypoint = function() end, SetSuperTrackedQuestID = function() end }
UiMapPoint = { CreateFromCoordinates = function(m, x, y) return { uiMapID = m, position = { x = x, y = y } } end }
'''

fails = []


def chk(name, ok):
    print(("ok   " if ok else "FAIL ") + name)
    if not ok:
        fails.append(name)


src = open(MAPLUA, encoding="utf-8").read()
a0 = src.index("WC.routeWp = {")
b0 = src.index("do" + chr(10) + "    local f = CreateFrame", a0)
M = lupa.LuaRuntime()
M.execute(STUB + src[a0:b0])
t = [0.0]


def tap(*cols):
    for c in cols:
        M.execute("T = %f" % t[0]); M.eval("UWP")(None, c); t[0] += 0.15


def state():
    R = M.eval("WC.routeWp")
    return (round(R.x * 1295), round(R.y * 1295)), [(round(R.more[i].x * 1295), round(R.more[i].y * 1295), R.more[i].dz, R.more[i].m)
                                                   for i in range(1, len(R.more) + 1)]


# test69's own rows (08:47:46, 08:47:52, 08:48:17), as the app sent them
tap(36, 22, 15, 25, 15, 18, 0, 38, 22, 18, 25, 10, 18, 0, 38, 22, 24, 25, 8, 18, 8)
pin, more = state()
chk("the SET: the pin (807, 915) and two flags with their heights (%s %s)" % (pin, more),
    pin == (807, 915) and more == [(810, 910, 0.0, 2521), (816, 908, 2.0, 2521)])
t[0] = 6.0
tap(38, 38, 38, 22, 24, 25, 8, 18, 8, 38, 22, 24, 25, 9, 18, 8)
chk("the list replaced (38 38, then two points): %s" % (state()[1],), state()[1] == [(816, 908, 2.0, 2521), (816, 909, 2.0, 2521)])
t[0] = 31.0
tap(38, 38, 38, 22, 18, 25, 25, 18, 0, 38, 22, 18, 25, 17, 18, 2)
chk("... again (%s)" % (state()[1],), state()[1] == [(810, 925, 0.0, 2521), (810, 917, 0.5, 2521)])

# a pass SET with one flag, then the goal as the second: a new SET drops the list, its own flags follow
t[0] = 40.0
tap(36, 22, 18, 25, 25, 18, 0, 38, 22, 18, 25, 17, 18, 2, 38, 22, 20, 25, 12, 35, 35)
pin, more = state()
chk("a pass SET: its pin and its two flags, the goal's height unknown (%s %s)" % (pin, more),
    pin == (810, 925) and more == [(810, 917, 0.5, 2521), (812, 912, None, 2521)])

# test69's phantom: the game names a town's micro-map mid-row - the flags stay on the pin's map
t[0] = 50.0
tap(36, 22, 15, 25, 15, 18, 0)
M.execute("MAP = 9999")
tap(38, 22, 18, 25, 10, 18, 0)
chk("a flag decoded while the game names another map: on the pin's own map (%s)" % (state()[1],), state()[1][0][3] == 2521)
M.execute("MAP = 2521")

# a flag far from the point before it (a 1500 yd jump) is refused and said
t[0] = 60.0
tap(36, 22, 15, 25, 15, 18, 0, 38, 22, 18, 25, 10, 18, 0)
n = len(state()[1])
tap(38, 34, 0, 25, 10, 35, 35)                    # x 1224/1295: about 1 km east of the flag before it
said = M.eval("SAID[#SAID]")
chk("a 1 km jump refused and logged (%s)" % said.why, len(state()[1]) == n and said.op == "more" and said.ok is False and "refused" in said.why)

print("%d failed" % len(fails))
sys.exit(1 if fails else 0)
