"""THE LOADING SCREENS REACH THE APP AT ONCE (memory relief 2; the user's picks, 2026-11-14: "RELIEF AT ZONE CHANGES: the add-on reports
loading screens (LOADING_SCREEN_ENABLED / DISABLED, plain events, a tiny doc or an existing doc field; no polling) and the app runs
MemWatch.relieve at each loading screen"). Runs the REAL Safety.lua, Sink.lua and Core.lua (core1) in Lua 5.1 (lupa) under the shared
stubbed game (wowstub.GAME), with C_Log.LogMessage captured as the log file's lines:
  - both events are registered on Core's frame (a build without them would name them in missingEvents)
  - LOADING_SCREEN_ENABLED: ONE tiny document {"type":"load","on":true,"v":"core1"} and its flush (the pad lines) IN THE EVENT ITSELF:
    no timer is needed (the push's C_Timer may not run during the load); no snapshot, no ticker
  - LOADING_SCREEN_DISABLED: {"on":false} and the usual coalesced push (one timer, then the pad)
  - before PLAYER_LOGIN too (the reload's), and nothing of the game's is written (no binding, CVar, macro: wowstub's spies)
Run: python -B tools/test_loading_screens.py
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
WowCompanion.snapshot = function(r) SNAPS2[#SNAPS2 + 1] = r; return false end
''')

core = open(os.path.join(ADDON, 'Core.lua'), encoding='utf-8').read()
check('local COREV = "core1"' in core, 'Core.lua carries its version tag core1')


def nlog():
    return int(L.eval('#LOG'))


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


def pads_since(start):
    return sum(1 for i in range(start + 1, nlog() + 1) if L.eval('LOG[%d]' % i).startswith('WCB1|pad|'))


def timers():
    return int(L.eval('#TIMERS'))


def tickers():
    return int(L.eval('#TICKERS'))


reg = L.eval('function(e) for _, f in ipairs(EVT[e] or {}) do if f.scripts.OnEvent then return true end end; return false end')
check(reg('LOADING_SCREEN_ENABLED') and reg('LOADING_SCREEN_DISABLED'), 'both loading-screen events are registered on a frame with an OnEvent')

# ------------------------------------------------------------------ before the login (a reload's own loading screen): still told
n0, t0, k0 = nlog(), timers(), tickers()
L.execute('fire("LOADING_SCREEN_ENABLED")')
d = docs_since(n0)
check(d == [{'type': 'load', 'on': True, 'v': 'core1'}], 'ENABLED before the login: the one tiny document', d)
check(pads_since(n0) > 0, 'ENABLED: flushed in the event itself (the pad lines written now)', pads_since(n0))
check(timers() == t0 and tickers() == k0, 'ENABLED: no timer, no ticker (no polling; the load may hold timers back)', (timers() - t0, tickers() - k0))
check(int(L.eval('#SNAPS2')) == 0, 'ENABLED: never a snapshot', int(L.eval('#SNAPS2')))

# ------------------------------------------------------------------ in the world
L.execute('fire("PLAYER_LOGIN"); runTimers(NOW + 1)')
L.execute('SNAPS2 = {}')
L.execute('runTimers(NOW + 1)')
n1, t1, k1 = nlog(), timers(), tickers()
L.execute('NOW = NOW + 5; fire("LOADING_SCREEN_ENABLED")')
d = docs_since(n1)
check(d == [{'type': 'load', 'on': True, 'v': 'core1'}], 'ENABLED in the world: the one tiny document', d)
check(pads_since(n1) > 0 and timers() == t1, 'ENABLED in the world: flushed at once, nothing waits on a timer', (pads_since(n1), timers() - t1))
check(tickers() == k1, 'ENABLED in the world: no ticker started', tickers() - k1)
check(int(L.eval('#SNAPS2')) == 0, 'ENABLED in the world: no snapshot (the router sends it to core1, not WC.snapshot)', L.eval('SNAPS2[1]'))

n2, t2 = nlog(), timers()
L.execute('fire("LOADING_SCREEN_DISABLED")')
d = docs_since(n2)
check(d == [{'type': 'load', 'on': False, 'v': 'core1'}], 'DISABLED: {"on":false}', d)
check(timers() == t2 + 1 and pads_since(n2) == 0, 'DISABLED: the usual coalesced push (one timer, no pad yet)', (timers() - t2, pads_since(n2)))
L.execute('runTimers(NOW + 1)')
check(pads_since(n2) > 0, 'DISABLED: the push flushes on the next frame', pads_since(n2))
check(int(L.eval('#SNAPS2')) == 0, 'DISABLED: no snapshot', L.eval('SNAPS2[1]'))

# ------------------------------------------------------------------ leave no trace
spy = [L.eval('SPY[%d]' % i) for i in range(1, int(L.eval('#SPY')) + 1)]
check(not spy, 'nothing of the game written (no binding, CVar, macro, Blizzard window)', spy)
errs = [L.eval('ERRS[%d]' % i) for i in range(1, int(L.eval('#ERRS')) + 1)]
check(not any('LOADING_SCREEN' in e for e in errs), 'no error from the loading-screen events', errs)

print('\n%d passed, %d failed' % (passes, fails))
sys.exit(1 if fails else 0)
