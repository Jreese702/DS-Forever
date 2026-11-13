"""THE GUARD'S FLAG (Map.lua gp1, Harvest.lua hv2; the user, 2026-11-13: "when asking a guard for directions, it does not put the arrow
and flag to what I am looking for on our map"). The real Map.lua (and Harvest.lua) under tools/wowstub.py's game, with a stand-in
C_GossipInfo / C_Map / C_Minimap, checks:
  - DYNAMIC_GOSSIP_POI_UPDATED is one of Map.lua's own events and asks for a "gossip" map snapshot
  - the flag on the player's map: map.gpoi = {m, x, y, n, id, tex, tc} (the game's own cut from GetPOITextureCoords)
  - no flag: no gpoi (cleared when the game clears it, no timer of ours)
  - the flag held only on the parent map: m = the parent, hx / hy carried onto the player's map
  - a SECRET answer (the id, the info, the position, the name, the cut): dropped, never sent
  - GetPoiInfo's position as a plain table (no GetXY): still read
  - Harvest's /wcpoi probe reads through WC.gpoi.read (v = "hv2") and still finds the flag
  - nothing written (no SetCVar / SetBinding / macro / Blizzard window call)
Run: python -B tools/test_gossip_poi.py
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from wowstub import boot  # noqa: E402
from wowstub import ADDONS  # noqa: E402

passes = fails = 0


def check(ok, what, got=None):
    global passes, fails
    if ok:
        passes += 1; print('PASS', what)
    else:
        fails += 1; print('FAIL', what, '->', got)


HARVEST = open(os.path.join(ADDONS, 'WowCompanion', 'Harvest.lua'), encoding='utf-8').read()
MAPSRC = open(os.path.join(ADDONS, 'WowCompanion', 'Map.lua'), encoding='utf-8').read()

PRE = r'''
SECRETS = {}
function issecretvalue(v) return SECRETS[v] == true end
-- the flag the server holds: [uiMapID] = {id, name, x, y, tex}
FLAG = {}
SECRET_MODE = nil                      -- "id" / "info" / "pos" / "name" / "tc"
TABLEPOS = false
local function vec(x, y)
    if TABLEPOS then return { x = x, y = y } end
    return { x = x, y = y, GetXY = function(self) return self.x, self.y end }
end
C_GossipInfo = {
    GetPoiForUiMapID = function(m) local f = FLAG[m]; if not f then return nil end; return f.id end,
    GetPoiInfo = function(m, id)
        local f = FLAG[m]; if not f or f.id ~= id then return nil end
        local info = { name = f.name, textureIndex = f.tex, position = vec(f.x, f.y), inBattleMap = false }
        if SECRET_MODE == "info" then SECRETS[info] = true end
        return info
    end,
    GetOptions = function() return {} end,
    SelectOption = function() end,
}
PARENT = { [1453] = 1415, [1429] = 1415, [1415] = 947 }
C_Map = setmetatable({
    GetBestMapForUnit = function() return PLAYER_MAP end,
    GetPlayerMapPosition = function(m) return { x = 0.5, y = 0.5, GetXY = function() return 0.5, 0.5 end } end,
    GetMapInfo = function(m) return { mapID = m, parentMapID = PARENT[m] or 0, name = "M" .. m, mapType = 3 } end,
    -- the continent holds the city at x 0.40..0.50, y 0.70..0.80 (a tenth of it each way)
    GetWorldPosFromMapPos = function(m, v) return 0, { mapFrom = m, x = v.x, y = v.y } end,
    GetMapPosFromWorldPos = function(cid, w, to)
        if w.mapFrom == 1415 and to == 1453 then return 0, { x = (w.x - 0.40) * 10, y = (w.y - 0.70) * 10 } end
        return 0, nil
    end,
    GetUserWaypoint = function() return nil end,
    OpenWorldMap = function() SPY[#SPY + 1] = "C_Map.OpenWorldMap" end,
}, { __index = function() return STUB end })
function CreateVector2D(x, y) return { x = x, y = y } end
C_Minimap = setmetatable({
    GetPOITextureCoords = function(i)
        local cols, w = 14, 16 / 256
        local x1 = (i % cols) * w; local y1 = math.floor(i / cols) * w
        local a = x1
        if SECRET_MODE == "tc" then a = {} ; SECRETS[a] = true end
        return a, x1 + w, y1, y1 + w
    end,
    GetViewRadius = function() return 200 end,
}, { __index = function() return STUB end })
PLAYER_MAP = 1453
SlashCmdList = {}
'''

def world():
    """the add-on's files in its .toc order up to Map.lua (Harvest.lua and Coords.lua by path: not wowstub's files)"""
    L = boot(['safety', 'tappad'], pre=PRE)
    load = L.eval('function(src, name) local f, e = loadstring(src, name); if not f then error(e) end; return f end')
    for name in ('Sink.lua', 'Harvest.lua', 'Coords.lua', 'Map.lua'):
        src = open(os.path.join(ADDONS, 'WowCompanion', name), encoding='utf-8').read()
        load(src, '@' + name)('WowCompanion')
    return L


def secret_wrap(L):
    """GetPoiForUiMapID / GetPoiInfo answers marked secret by SECRET_MODE"""
    L.execute(r'''
    local rawGet, rawInfo = C_GossipInfo.GetPoiForUiMapID, C_GossipInfo.GetPoiInfo
    C_GossipInfo.GetPoiForUiMapID = function(m)
        local id = rawGet(m)
        if SECRET_MODE == "id" and id then local t = { id }; SECRETS[t] = true; return t end
        return id
    end
    C_GossipInfo.GetPoiInfo = function(m, id)
        local info = rawInfo(m, id)
        if info and SECRET_MODE == "pos" then
            local sx = {}; SECRETS[sx] = true
            info.position = { x = sx, y = sx, GetXY = function() return sx, sx end }
        end
        if info and SECRET_MODE == "name" then local n = {}; SECRETS[n] = true; info.name = n end
        return info
    end''')


def collect(L):
    L.execute('OUT = {}; WC_OUT_OK = pcall(WowCompanion.gpoi.collect, PLAYER_MAP, OUT)')
    return L.eval('OUT.gpoi')


L = world()
check(L.eval('type(WowCompanion.gpoi) == "table" and WowCompanion.gpoi.V') == 'gp1', "Map.lua carries WC.gpoi, version gp1")
check('"DYNAMIC_GOSSIP_POI_UPDATED"' in MAPSRC[MAPSRC.index('local QUEST_MARKER_EVENTS'):MAPSRC.index('local function onQuestMarkerEvent')],
      "DYNAMIC_GOSSIP_POI_UPDATED is in Map.lua's own event list")
h = MAPSRC[MAPSRC.index('local function onQuestMarkerEvent'):]
k = h.index('event == "DYNAMIC_GOSSIP_POI_UPDATED"')
check('snapshotSoon("gossip")' in h[k:k + 200], "the event asks for a \"gossip\" map snapshot")
check('pcall(WC.gpoi.collect, id, out)' in MAPSRC[MAPSRC.index('function WC.collectMap()'):], "collectMap reads the flag")

# no flag
check(collect(L) is None, "no flag held: no gpoi")

# on the player's map (the Stormwind bank, textureIndex 5)
L.execute('FLAG[1453] = { id = 31, name = "Stormwind Bank", x = 0.6123456, y = 0.7, tex = 5 }')
g = collect(L)
check(g is not None and g['m'] == 1453 and abs(g['x'] - 0.61235) < 1e-9 and g['y'] == 0.7, "the flag on the player's map: m, x, y (1e-5)",
      g and (g['m'], g['x'], g['y']))
check(g['n'] == 'Stormwind Bank' and g['id'] == 31 and g['tex'] == 5, "its name, id, textureIndex", g and (g['n'], g['id'], g['tex']))
tc = g['tc']
check(tc is not None and abs(tc[1] - 5 * 16 / 256) < 1e-9 and abs(tc[2] - 6 * 16 / 256) < 1e-9 and tc[3] == 0 and abs(tc[4] - 1 / 16) < 1e-9,
      "the game's own cut: tc = {x1, x2, y1, y2}", tc and [tc[i] for i in range(1, 5)])
check(g['hx'] is None, "on the player's map: no carried copy")

# cleared
L.execute('FLAG[1453] = nil')
check(collect(L) is None, "the game cleared it: gone at the next snapshot")

# held on the parent map only: carried onto the player's map
L.execute('FLAG[1415] = { id = 7, name = "Ironforge", x = 0.45, y = 0.75, tex = 13 }')
g = collect(L)
check(g is not None and g['m'] == 1415 and g['x'] == 0.45, "the flag on the parent map: m = the parent", g and g['m'])
check(g is not None and abs(g['hx'] - 0.5) < 1e-6 and abs(g['hy'] - 0.5) < 1e-6, "carried onto the player's map: hx / hy",
      g and (g['hx'], g['hy']))
L.execute('FLAG[1415] = nil')

# secrets
for mode, what in (("id", "a secret id"), ("info", "a secret info table"), ("pos", "a secret position")):
    L = world(); secret_wrap(L)
    L.execute('FLAG[1453] = { id = 31, name = "Bank", x = 0.6, y = 0.7, tex = 5 }; SECRET_MODE = "%s"' % mode)
    check(collect(L) is None, what + ": no flag sent")
L = world(); secret_wrap(L)
L.execute('FLAG[1453] = { id = 31, name = "Bank", x = 0.6, y = 0.7, tex = 5 }; SECRET_MODE = "name"')
g = collect(L)
check(g is not None and g['n'] is None and g['x'] == 0.6, "a secret name: the flag without its name", g and g['n'])
L.execute('SECRET_MODE = "tc"')
g = collect(L)
check(g is not None and g['tc'] is None and g['tex'] == 5, "a secret cut: no tc (the app cuts by the index)", g and g['tc'])

# a position with no GetXY
L = world()
L.execute('TABLEPOS = true; FLAG[1453] = { id = 31, name = "Bank", x = 0.25, y = 0.35, tex = 5 }')
g = collect(L)
check(g is not None and g['x'] == 0.25 and g['y'] == 0.35, "a plain position table (no GetXY): read", g and (g['x'], g['y']))

# the whole map document carries it
L = world()
L.execute('FLAG[1453] = { id = 31, name = "Bank", x = 0.6, y = 0.7, tex = 5 }')
L.execute('DOC_OK, DOC = pcall(WowCompanion.collectMap)')
ok = L.eval('DOC_OK')
check(ok and L.eval('DOC.gpoi and DOC.gpoi.id') == 31, "WC.collectMap: map.gpoi in the document", (ok, L.eval('tostring(DOC)')))

# Harvest's probe reads through WC.gpoi.read (Harvest.lua loaded before Map.lua, as in the .toc)
L = world()
L.execute('FLAG[1453] = { id = 31, name = "Bank", x = 0.6, y = 0.7, tex = 5 }')
L.execute('EM = {}; WowCompanion.emit = function(t) EM[#EM + 1] = t end; SlashCmdList["WCPOI"]()')
d = L.eval('EM[#EM]')
check(d is not None and d['probe'] == 'gossipPoi' and d['v'] == 'hv2' and d['pois'][1]['id'] == 31,
      "/wcpoi: Harvest's probe (hv2) reads the flag through WC.gpoi.read", d and dict(d))
check('local function readGossipPoi(mapID)\n    if not (WC.gpoi and WC.gpoi.read)' in HARVEST, "Harvest's own copy of the read is gone")

check(len(list(L.eval('SPY').values())) == 0, "nothing written: no binding / CVar / macro / window call", list(L.eval('SPY').values()))

print(f'{passes} passed, {fails} failed')
sys.exit(1 if fails else 0)
