"""LIGHTER ADD-ONS IN FIGHTS (test build 1529; the user's pick 2026-11-24, "Lighter add-ons + log (Recommended)" = "Slower log polling,
fewer add-on flushes in fights, no 15 s snapshot mid-fight, less per-frame Lua work"). Runs the REAL Safety.lua, Sink.lua (sink2) and
Core.lua (core2) in Lua 5.1 (lupa) under the shared stubbed game (wowstub.GAME), C_Log.LogMessage captured as the log's lines:
  - sink2: the throttle's interval 0.2 s out of a fight, 0.3 s in one (InCombatLockdown); measured: the flushes a second of a stream of
    pushes every 0.1 s (the position ticker while walking), out of a fight and in one
  - sink2: WC.push makes no closure per call (the end-of-frame callback is the same function every time)
  - core2: the 15 s heartbeat out of a fight = the full snapshot (as before); in a fight = one tiny {"type":"beat"} and NO snapshot;
    the snapshot owed goes out at PLAYER_REGEN_ENABLED, once; a fight's end with nothing owed = the usual (deduped) snapshot
  - core3 (PR 615, based on a pull request by RenaeTheRaven): the heartbeat in stages (WC.heartbeatStaged), one collect per frame (bar,
    equipment, player + map, bags, then the emit), the same "full" document, a second heartbeat while one runs dropped; and our two
    fixes: a full snapshot sent mid-chain makes the last stage send nothing (no older bar after the newer one, lastBar kept), and a
    fight that starts mid-chain stops it at the next stage (heartOwed set, nothing more collected)
  - nothing of the game written (wowstub's spies)
Run: python -B tools/test_combat_light.py
"""
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from wowstub import GAME, lupa  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ADDON = os.path.join(ROOT, 'companion', 'app', 'src', 'main', 'assets', 'companion', 'addons', 'WowCompanion')
FILES = ['Safety.lua', 'Sink.lua', 'Core.lua']
passes = fails = 0


def check(ok, what, got=None):
    global passes, fails
    if ok:
        passes += 1; print('PASS', what)
    else:
        fails += 1; print('FAIL', what, '->', got)


L = lupa.LuaRuntime(unpack_returned_tuples=True)
L.execute(GAME)
L.execute(r'''
LOG = {}
C_Log = { LogMessage = function(s) LOG[#LOG + 1] = s; return true end }
''')
load = L.eval('function(src, name) local f, e = loadstring(src, name); if not f then error(e) end; return f end')
for n in FILES:
    load(open(os.path.join(ADDON, n), encoding='utf-8').read(), '@' + n)('WowCompanion')
L.execute(r'''
SNAPS2 = {}
REAL_SNAP = WowCompanion.snapshot
WowCompanion.snapshot = function(r) SNAPS2[#SNAPS2 + 1] = r; return false end
REAL_HB = WowCompanion.heartbeatStaged
WowCompanion.heartbeatStaged = function() SNAPS2[#SNAPS2 + 1] = "heartbeat" end
''')

sink = open(os.path.join(ADDON, 'Sink.lua'), encoding='utf-8').read()
core = open(os.path.join(ADDON, 'Core.lua'), encoding='utf-8').read()
check('WC.SINKV = "sink2"' in sink, 'Sink.lua carries sink2')
check('local COREV = "core3"' in core, 'Core.lua carries core3 (the heartbeat in stages)')

W = L.eval('WowCompanion')
L.execute('COMBAT = false')
check(abs(W.flushInterval() - 0.2) < 1e-9, 'out of a fight: 0.2 s, as before', W.flushInterval())
L.execute('COMBAT = true')
check(abs(W.flushInterval() - 0.3) < 1e-9, 'in a fight: 0.3 s', W.flushInterval())
L.execute('COMBAT = false')


def nlog():
    return int(L.eval('#LOG'))


def pad_bursts(start):
    """the flushes since [start]: runs of pad lines"""
    n, inrun = 0, False
    for i in range(start + 1, nlog() + 1):
        p = L.eval('LOG[%d]' % i).startswith('WCB1|pad|')
        if p and not inrun: n += 1
        inrun = p
    return n


def flushes_per_second(combat, secs=10):
    L.execute('COMBAT = %s' % ('true' if combat else 'false'))
    L.execute('runTimers(NOW + 5)')
    n0 = nlog()
    steps = int(secs / 0.1)
    for _ in range(steps):
        # the position ticker's push: a document written, then the throttled push
        L.execute('WowCompanion.send(WowCompanion.encode({ type = "pos", map = { x = NOW } })); WowCompanion.pushThrottled(); runTimers(NOW + 0.1)')
    L.execute('runTimers(NOW + 1)')
    return pad_bursts(n0) / float(secs)


calm = flushes_per_second(False)
fight = flushes_per_second(True)
print('MEASURED: walking, a push every 0.1 s: %.1f flushes/s out of a fight, %.1f in a fight (%d%% fewer)' % (calm, fight, round(100 - 100 * fight / calm)))
check(4.0 <= calm <= 5.2, 'out of a fight: ~5 flushes a second as before', calm)
check(2.4 <= fight <= 3.6, 'in a fight: ~3 flushes a second (the 0.1 s ticker lands on 0.3 or 0.4 s)', fight)
L.execute('COMBAT = false')

# sink2: WC.push's end-of-frame callback is one function, not a new closure per call
L.execute(r'''
PUSHFN = {}
local after = C_Timer.After
C_Timer.After = function(s, fn) if s == 0 then PUSHFN[#PUSHFN + 1] = fn end; return after(s, fn) end
for i = 1, 3 do WowCompanion.send("{}"); WowCompanion.push(); runTimers(NOW + 0.5) end
C_Timer.After = after
''')
check(int(L.eval('#PUSHFN')) == 3 and L.eval('PUSHFN[1] == PUSHFN[2] and PUSHFN[2] == PUSHFN[3]'),
      'WC.push: the same callback every time (no closure per push)', int(L.eval('#PUSHFN')))

# ------------------------------------------------------------------ core2: the heartbeat
L.execute('fire("PLAYER_LOGIN"); runTimers(NOW + 1)')
hb = L.eval('(function() for _, t in ipairs(TICKERS) do if t.s == 15.0 then return t end end end)()')
check(hb is not None, 'the 15 s heartbeat ticker is there')


def docs_since(start):
    out = []
    for i in range(start + 1, nlog() + 1):
        s = L.eval('LOG[%d]' % i)
        parts = s.split('|', 3)
        if len(parts) == 4 and parts[0] == 'WCB1' and parts[2] == '1/1':
            try:
                out.append(json.loads(parts[3]))
            except Exception:
                pass
    return out


L.execute('SNAPS2 = {}; COMBAT = false')
hb.fn()
check(list(L.eval('SNAPS2').values()) == ['heartbeat'], 'out of a fight: the full heartbeat snapshot, as before', list(L.eval('SNAPS2').values()))

L.execute('SNAPS2 = {}; COMBAT = true')
n0 = nlog()
hb.fn(); L.execute('runTimers(NOW + 1)')
hb.fn(); L.execute('runTimers(NOW + 1)')
d = docs_since(n0)
check(int(L.eval('#SNAPS2')) == 0, 'in a fight: no full snapshot built', list(L.eval('SNAPS2').values()))
check(d == [{'type': 'beat', 'v': 'core3'}, {'type': 'beat', 'v': 'core3'}], 'in a fight: one tiny beat a heartbeat (the app\'s live clock)', d)
check(pad_bursts(n0) >= 1, 'the beat is flushed (it reaches the app)', pad_bursts(n0))

L.execute('COMBAT = false; fire("PLAYER_REGEN_ENABLED")')
check(list(L.eval('SNAPS2').values()) == ['heartbeat'], 'the fight ends: the owed heartbeat snapshot, once', list(L.eval('SNAPS2').values()))
L.execute('SNAPS2 = {}; fire("PLAYER_REGEN_ENABLED")')
check(list(L.eval('SNAPS2').values()) == ['PLAYER_REGEN_ENABLED'], 'a fight end with nothing owed: the usual snapshot (deduped there)', list(L.eval('SNAPS2').values()))

# ------------------------------------------------------------------ core3: the staged heartbeat, one stage per frame
L.execute(r'''
WC = WowCompanion
ORDER = {}
BARN = 1
WC.collectActionBar = function() ORDER[#ORDER + 1] = "bar"; return { { slot = BARN } } end
WC.collectEquipped = function() ORDER[#ORDER + 1] = "equipped"; return { head = 1 } end
WC.collectPlayer = function() ORDER[#ORDER + 1] = "player"; return { hp = 1 } end
WC.collectMapSafe = function() ORDER[#ORDER + 1] = "map"; return { ok = true } end
WC.collectBags = function() ORDER[#ORDER + 1] = "bags"; return { n = 1 } end
EMITS = {}
REAL_EMIT = WC.emit
WC.emit = function(d) EMITS[#EMITS + 1] = d end
-- one frame: the earliest timer that is due now, and only that one
function oneFrame()
    local best, bi = nil, nil
    for i, t in ipairs(TIMERS) do if t.at <= NOW and (not best or t.at < best.at) then best, bi = t, i end end
    if best then table.remove(TIMERS, bi); best.fn() end
end
function orderText() local t = {} for i, v in ipairs(ORDER) do t[i] = v end return table.concat(t, ",") end
''')
L.execute('COMBAT = false; runTimers(NOW + 60); TIMERS = {}; REAL_HB()')
check(L.eval('orderText()') == '', 'staged: nothing collected in the frame the heartbeat fires', L.eval('orderText()'))
L.execute('REAL_HB()')
frames = []
for _ in range(5):
    L.execute('oneFrame()'); frames.append(L.eval('orderText()'))
check(frames == ['bar', 'bar,equipped', 'bar,equipped,player,map', 'bar,equipped,player,map,bags', 'bar,equipped,player,map,bags'],
      'staged: one collect per frame (bar / equipment / player + map / bags / emit)', frames)
check(int(L.eval('#EMITS')) == 1 and L.eval('EMITS[1].type') == 'full' and L.eval('EMITS[1].reason') == 'heartbeat'
      and L.eval('EMITS[1].bags.n') == 1 and L.eval('EMITS[1].map.ok') and L.eval('EMITS[1].equipped.head') == 1,
      'staged: one "full" heartbeat document with everything, sent in the last frame (and a second call while busy dropped)',
      int(L.eval('#EMITS')))
L.execute('oneFrame(); ORDER = {}; REAL_HB(); for i = 1, 5 do oneFrame() end')
check(L.eval('orderText()') == 'bar,equipped,player,map,bags' and int(L.eval('#EMITS')) == 2, 'staged: the next heartbeat runs again once done',
      L.eval('orderText()'))

# fix (a): an event's snapshot mid-chain (a newer bar): the last stage sends nothing, and the dedup key stays the NEWER bar
L.execute('EMITS = {}; ORDER = {}; BARN = 1; REAL_HB(); oneFrame(); oneFrame()')     # stage 1 collected the OLD bar (slot 1)
L.execute('BARN = 2; REAL_SNAP("ACTIONBAR_SLOT_CHANGED")')                          # the newer bar goes out now
check(int(L.eval('#EMITS')) == 1 and L.eval('EMITS[1].reason') == 'ACTIONBAR_SLOT_CHANGED' and L.eval('EMITS[1].actionbar[1].slot') == 2,
      'race: the event snapshot mid-chain sends the newer bar', int(L.eval('#EMITS')))
L.execute('for i = 1, 4 do oneFrame() end')
check(int(L.eval('#EMITS')) == 1, 'race: the chain\'s last stage sends NOTHING after it (no older bar after the newer one)', int(L.eval('#EMITS')))
check(L.eval('REAL_SNAP("ACTIONBAR_SLOT_CHANGED")') is False and int(L.eval('#EMITS')) == 1,
      'race: lastBar is still the newer bar (the same bar again is deduped, not resent)', int(L.eval('#EMITS')))
L.execute('BARN = 1; REAL_HB(); for i = 1, 5 do oneFrame() end')
check(int(L.eval('#EMITS')) == 2 and L.eval('EMITS[2].reason') == 'heartbeat', 'race: the next heartbeat with nothing in between sends as usual',
      int(L.eval('#EMITS')))

# fix (b): a fight that starts mid-chain stops it at the next stage; the heartbeat is owed and goes out at the fight's end
L.execute('EMITS = {}; ORDER = {}; WC.heartOwed = false; COMBAT = false; REAL_HB(); oneFrame(); oneFrame()')
L.execute('COMBAT = true; for i = 1, 5 do oneFrame() end')
check(L.eval('orderText()') == 'bar,equipped' and int(L.eval('#EMITS')) == 0,
      'combat mid-chain: nothing more collected and nothing sent once the fight began', L.eval('orderText()'))
check(L.eval('WC.heartOwed') is True, 'combat mid-chain: the heartbeat is owed (core2: it goes out at PLAYER_REGEN_ENABLED)')
L.execute('SNAPS2 = {}; COMBAT = false; fire("PLAYER_REGEN_ENABLED")')
check(list(L.eval('SNAPS2').values()) == ['heartbeat'] and L.eval('WC.heartOwed') is False, 'combat mid-chain: the fight ends, the owed heartbeat starts',
      list(L.eval('SNAPS2').values()))
L.execute('runTimers(NOW + 1); ORDER = {}; REAL_HB(); for i = 1, 5 do oneFrame() end')
check(L.eval('orderText()') == 'bar,equipped,player,map,bags' and int(L.eval('#EMITS')) == 1, 'combat mid-chain: a stopped chain frees the next one',
      L.eval('orderText()'))
L.execute('WC.emit = REAL_EMIT')

spy = [L.eval('SPY[%d]' % i) for i in range(1, int(L.eval('#SPY')) + 1)]
check(not spy, 'nothing of the game written (no binding, CVar, macro, Blizzard window)', spy)

print('\n%d passed, %d failed' % (passes, fails))
sys.exit(1 if fails else 0)
