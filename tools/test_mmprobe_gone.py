"""mp3, THE MINIMAP PROBE IS GONE (issue #87; the user's ruling 2026-11-19: "accept that the live dots are gone"). Since game build
1.60.1.70862 (2026-11-08) the minimap answers the pretend mouse with zero-line tooltips, so Map.lua's probe (mp1 / mp2) found nothing.
This replaces tools/test_mmprobe.py (mp2's diagnostics). Off-device, the REAL Safety / TapPad / Map files under tools/wowstub.py:

    python -B tools/test_mmprobe_gone.py

  a Map.lua no longer calls the probe's API (UpdateMouseoverAtPoint, SetUsingSoftCursor, GetMinimapMouseover, IsUsingSoftCursor -
    the last never existed on this client) and sends no "mmprobe" document; WC.mmp is mp3 with no scan / switch / dots;
  b SPARKLES stay: op 2 on the pin lane with bit 4 sets the soft-target settings (remembered), 1 / 2 (the old probe / auto-switch) are
    dropped (opt 0 or 4 only), bits off put the game's settings back, a fight holds the change until its end, logout puts them back;
  c the options ride the map snapshot as `mpo`;
  d frames: no Lua error, nothing of the spies called. Exit code 1 on a failure."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from wowstub import boot, docs   # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MAP = open(os.path.join(ROOT, 'companion', 'app', 'src', 'main', 'assets', 'companion', 'addons', 'WowCompanion', 'Map.lua'),
           encoding='utf-8').read()

HELPERS = r'''
CV = { SoftTargetIconGameObject = "0", SoftTargetInteractArc = "0", SoftTargetInteractRange = "10", SoftTargetInteract = "1" }
CVDEF = { SoftTargetIconGameObject = "0", SoftTargetInteractArc = "0", SoftTargetInteractRange = "10", SoftTargetInteract = "0" }
CVSETS = 0
C_CVar = { GetCVar = function(n) return CV[n] end, GetCVarDefault = function(n) return CVDEF[n] end,
           SetCVar = function(n, v) CVSETS = CVSETS + 1; CV[n] = tostring(v) end }
WowCompanion.capture.take = function() return false, "test" end
WowCompanion.capture.release = function() return true end
'''

fails = []


def chk(name, ok, got=''):
    print(("ok   " if ok else "FAIL ") + name + ("" if ok else "  got: %s" % (got,)))
    if not ok:
        fails.append(name)


WP_A, WP_B = 6, 23


def opt(L, bits):
    L.execute('WowCompanion.mapRoomCell(%d); NOW = NOW + 0.15; WowCompanion.mapRoomCell(%d); NOW = NOW + 0.15' % (WP_A + 2, WP_B + bits))


def cv(L, n):
    return L.eval('CV["%s"]' % n)


def run():
    # a: the source
    code = '\n'.join(l.split('--', 1)[0] for l in MAP.splitlines())          # the code, the comments left out
    for api in ('UpdateMouseoverAtPoint', 'SetUsingSoftCursor', 'GetMinimapMouseover', 'IsUsingSoftCursor', '"mmprobe"',
                'WC.mmp.scan', 'WC.mmp.switch', 'WC.mmp.finders', 'WC.mmp.tracking', 'WC.mmp.step'):
        chk("a Map.lua's code no longer has %s" % api, api not in code)
    chk("a WC.mmp is mp3", 'V = "mp3"' in MAP)
    chk("a the snapshot carries mpo", 'out.mpo = WC.mmp and WC.mmp.opt or nil' in MAP)

    L = boot(["safety", "tappad", "maproom"], pre=HELPERS)
    L.execute('fire("ADDON_LOADED", "WowCompanion"); fire("PLAYER_LOGIN"); fire("PLAYER_ENTERING_WORLD", true, false); runTimers(NOW + 10)')
    chk("a no probe fields at load", L.eval('WowCompanion.mmp.scan == nil and WowCompanion.mmp.dots == nil and WowCompanion.mmp.opt == 0'))
    L.execute('for i = 1, 30 do NOW = NOW + 0.5; tick(0.5) end')
    chk("a frames run with no mmprobe document", len(docs(L, 0, 'mmprobe')) == 0)

    # b: sparkles
    start = L.eval('#SENT')
    opt(L, 1)
    chk("b bit 1 (the old probe) is dropped: opt 0", L.eval('WowCompanion.mmp.opt') == 0, L.eval('WowCompanion.mmp.opt'))
    chk("b ... and no setting changed", L.eval('CVSETS') == 0, L.eval('CVSETS'))
    acks = [d for d in docs(L, start) if isinstance(d, dict) and d.get('op') == 'opt']
    chk("b ... answered ok with o = 0", bool(acks) and acks[-1].get('ok') in (True, 1) and acks[-1].get('o') == 0, acks[-1:])
    opt(L, 7)
    chk("b 7 (all three old bits): opt 4, sparkles only", L.eval('WowCompanion.mmp.opt') == 4, L.eval('WowCompanion.mmp.opt'))
    chk("b the soft-target icons on", cv(L, 'SoftTargetIconGameObject') == '1' and cv(L, 'SoftTargetInteractArc') == '2'
        and cv(L, 'SoftTargetInteractRange') == '30', (cv(L, 'SoftTargetIconGameObject'), cv(L, 'SoftTargetInteractRange')))
    chk("b the Controller UI's own SoftTargetInteract (on) left alone", cv(L, 'SoftTargetInteract') == '1')
    opt(L, 0)
    chk("b off: the game's settings back", cv(L, 'SoftTargetIconGameObject') == '0' and cv(L, 'SoftTargetInteractRange') == '10')
    L.execute('COMBAT = true')
    opt(L, 4)
    chk("b in a fight: nothing changed yet", cv(L, 'SoftTargetIconGameObject') == '0')
    L.execute('COMBAT = false; fire("PLAYER_REGEN_ENABLED")')
    chk("b the fight's end: sparkles on", cv(L, 'SoftTargetIconGameObject') == '1')
    L.execute('fire("PLAYER_LOGOUT")')
    chk("b logout: the game's settings back", cv(L, 'SoftTargetIconGameObject') == '0' and cv(L, 'SoftTargetInteractArc') == '0')

    # c: the snapshot (WC.collectMap needs the whole map world: the line's place in it is checked)
    body = MAP[MAP.index('function WC.collectMap()'):]
    body = body[:body.index('pcall(WC.gpoi.collect, id, out)')]
    chk("c the map snapshot (WC.collectMap) says mpo = the option taken", 'out.mpo = WC.mmp and WC.mmp.opt or nil' in body)

    # d: clean
    chk("d nothing of the spies called", L.eval('#SPY') == 0, L.eval('SPY[1]'))


run()
print("%d failure(s)" % len(fails))
sys.exit(1 if fails else 0)
