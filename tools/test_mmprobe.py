"""THE MINIMAP PROBE'S DIAGNOSTICS (Map.lua mp2; issue #87, 1.0.21: "Can't see herbs on minimap. or maybe I'm missing somthing.").
Every report from 1.0.18 on reads d[0] in every mmprobe document, and the report's summary keeps numbers only, so the reason was
invisible. Runs Map.lua's WC.mmp scan section (start / step / finish / send / tracking) alone in Lua 5.1 (lupa) with a stand-in
minimap and checks:
  - a plain answer: the dot is found, dg counts it (pd / pl / pt), fh = 1 with Find Herbs on, fm = 0 with Find Minerals off
  - a SECRET answer: no dot, dg.ps counts the drops (the case that would say "the game hides the minimap's tooltip now")
  - a batch that throws: counted (dg.pe) and the scan still finishes (no stall on one point)
  - the soft cursor read back on (dg.cs) / not (cs = 0)
  - the why as a number (wy) when the probe cannot run (combat = 1, hidden = 3)
Run: python -B tools/test_mmprobe.py
"""
import os
import sys
from lupa import lua51

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MAP = open(os.path.join(ROOT, 'companion', 'app', 'src', 'main', 'assets', 'companion', 'addons', 'WowCompanion', 'Map.lua'),
           encoding='utf-8').read()
passes = fails = 0


def check(ok, what, got=None):
    global passes, fails
    if ok:
        passes += 1; print('PASS', what)
    else:
        fails += 1; print('FAIL', what, '->', got)


start = MAP.index('WC.mmp = {')
end = MAP.index('-- ---------------------------------------------------------------- the Find Herbs / Find Minerals AUTO-SWITCH')
SECTION = MAP[start:end]

STUB = r'''
SECRET = {}                           -- tables marked secret here
function issecret(v) return type(v) == "table" and SECRET[v] == true end
WC = { isPlain = function(v) return not issecret(v) end }
WC.send = function(s) SENT[#SENT + 1] = s end
WC.push = function() end
WC.encode = function(t) return t end
SENT = {}
function CALL(f, ...) return pcall(f, ...) end
function SN(f) local ok, v = pcall(f); if ok then return v end end
function plainNum(v) if type(v) == "number" then return v end end
function mapID() return 1413 end
COMBAT = false
function InCombatLockdown() return COMBAT end
function GetTime() return 100 end
function debugprofilestop() return 0 end
function UnitPosition() return -2387, -1918 end
C_CVar = { GetCVar = function() return "0" end }
function GetPlayerFacing() return 0 end
C_Minimap = { GetViewRadius = function() return 200 end, GetNumTrackingTypes = function() return 2 end,
    GetTrackingInfo = function(i)
        if i == 1 then return { name = "Find Herbs", active = true, spellID = 2383 } end
        return { name = "Find Minerals", active = false, spellID = 2580 }
    end }
MODE = "plain"                        -- plain / secret / throw
SOFT = false
VISIBLE = true
LASTX, LASTY = 0, 0
Minimap = {
    IsVisible = function() return VISIBLE end,
    IsUsingSoftCursor = function() return SOFT end,
    SetUsingSoftCursor = function(self, on) if CURSOR_TAKES then SOFT = on end end,
    UpdateMouseoverAtPoint = function(self, x, y)
        if MODE == "throw" then error("blocked") end
        LASTX, LASTY = x, y
    end,
    GetWidth = function() return 140 end, GetLeft = function() return 0 end, GetBottom = function() return 0 end,
    GetEffectiveScale = function() return 1 end,
}
CURSOR_TAKES = true
C_TooltipInfo = { GetMinimapMouseover = function()
    -- one herb 10 units right of the middle (70, 70)
    if (LASTX - 80) ^ 2 + (LASTY - 70) ^ 2 > 12 ^ 2 then return nil end
    local d = { lines = { { leftText = "Peacebloom" } } }
    if MODE == "secret" then SECRET[d] = true end
    return d
end }
'''


def fresh():
    L = lua51.LuaRuntime(unpack_returned_tuples=True)
    L.execute(STUB)
    L.execute('local WC, CALL, SN, plainNum, mapID = WC, CALL, SN, plainNum, mapID\n' + SECTION)
    return L


def scan(L):
    """one whole scan: start, then steps until it finishes; the last document"""
    L.execute('WC.mmp.sig = ""; WC.mmp.sentAt = -1000; WC.mmp.start()')
    for _ in range(100):
        if L.eval('WC.mmp.scan == nil'):
            break
        L.execute('WC.mmp.step()')
    return L.eval('SENT[#SENT]')


L = fresh()
doc = scan(L)
check(doc is not None and doc['v'] == 'mp2', "plain: a document, version mp2", doc and doc['v'])
check(doc['d'] is not None and len(list(doc['d'].values())) == 1 and doc['d'][1]['t'] == 'Peacebloom', "plain: one dot, the herb")
dg = doc['dg']
check(dg['pd'] > 0 and dg['pl'] == dg['pd'] and dg['pt'] == dg['pd'] and dg['ps'] == 0 and dg['pe'] == 0,
      "plain: dg counts answers / lines / names, no drops", dict(dg))
check(dg['fr'] > 1 and dg['cs'] == dg['fr'], "plain: the soft cursor read back on in every frame", (dg['fr'], dg['cs']))
check(doc['fh'] == 1 and doc['fm'] == 0, "Find Herbs on (fh 1), Find Minerals listed but off (fm 0)", (doc['fh'], doc['fm']))
check(doc['wy'] is None, "a scan that ran: no why", doc['wy'])
check(L.eval('LASTX') >= 0, "the probe used the minimap's points")

L = fresh()
L.execute('MODE = "secret"')
doc = scan(L)
dg = doc['dg']
check(len(list(doc['d'].values())) == 0 and dg['pd'] > 0 and dg['ps'] == dg['pd'] and dg['pl'] == 0,
      "secret: no dot, every answer counted as a secret drop", dict(dg))

L = fresh()
L.execute('MODE = "throw"')
doc = scan(L)
dg = doc['dg']
check(doc is not None and dg['pe'] == dg['fr'] and dg['pe'] > 0, "throw: each batch counted in pe, the scan still finished", dict(dg))

L = fresh()
L.execute('CURSOR_TAKES = false')
doc = scan(L)
check(doc['dg']['cs'] == 0, "a soft cursor that does not take: cs = 0", doc['dg']['cs'])

L = fresh()
L.execute('COMBAT = true; WC.mmp.start()')
doc = L.eval('SENT[#SENT]')
check(doc is not None and doc['wy'] == 1 and doc['why'] == 'combat', "in a fight: wy = 1", doc and (doc['wy'], doc['why']))
L.execute('COMBAT = false; VISIBLE = false; WC.mmp.start()')
doc = L.eval('SENT[#SENT]')
check(doc['wy'] == 3, "the minimap not visible: wy = 3", doc['wy'])

print(f'{passes} passed, {fails} failed')
sys.exit(1 if fails else 0)
