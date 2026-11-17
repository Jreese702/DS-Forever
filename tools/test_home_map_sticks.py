"""mr8, HOME'S MAP CONTROLS HOLD THE STICKS (the user, 2026-11-17: "when opening the map, pressing to the right should open some map
controls ... when using map controls it should function just like the map room with the sticks"): Map.lua's stick catch (mr7's frame,
WowCompanionMapStickCatch) off-device, the REAL Safety / TapPad / Map files under tools/wowstub.py's stubbed game, Lua 5.1 as the game's.

    python tools/test_home_map_sticks.py

Checks: the app's op 3 on the "mapw" pin lanes (lane A 3, lane B 1 / 0) shows the catch while Home's guest "pad:home" holds and hides
it at "0"; it never shows for "pad:home" without the op, nor for the op while another holder holds; a holder change away from Home lets
the sticks go and a later "1" is needed again; the hold lapses by itself 10 s after the last "1" (the 1 s tick), a fresh "1" keeps it;
the Map room's own hold ("map") still shows the catch as before; in a fight Show / Hide work with nothing blocked; the other pin-lane ops
(the minimap options' op 2) are untouched. Exit code 1 on a failure."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from wowstub import boot   # noqa: E402

HELPERS = r'''
function Frame:EnableGamePadStick(v) if COMBAT then error("ADDON_ACTION_BLOCKED EnableGamePadStick") end; self._stick = v and true or false end
function Frame:IsGamePadStickEnabled() return self._stick == true end
-- the shared capture as Map.lua looks it up (mrCap: take / release / state)
WowCompanion.capture.take = function() return false, "test" end
WowCompanion.capture.release = function() return true end
'''

fails = []


def chk(name, ok, got=''):
    print(("ok   " if ok else "FAIL ") + name + ("" if ok else "  got: %s" % (got,)))
    if not ok:
        fails.append(name)


WP_A, WP_B = 6, 23


def cell(L, col):
    L.execute('WowCompanion.mapRoomCell(%d); NOW = NOW + 0.15' % col)


def sticks(L, on):
    cell(L, WP_A + 3)
    cell(L, WP_B + (1 if on else 0))


def holder(L, h):
    L.execute('HOLDER = %s; pcall(WowCompanion.mapRoom.stickSync)' % ('nil' if h is None else '"%s"' % h))


def shown(L):
    return L.eval('(function() local f = rawget(_G, "WowCompanionMapStickCatch"); return f ~= nil and f._shown == true end)()')


def run():
    L = boot(["safety", "tappad", "maproom"], pre=HELPERS)
    L.execute('fire("ADDON_LOADED", "WowCompanion"); fire("PLAYER_LOGIN"); fire("PLAYER_ENTERING_WORLD", true, false); runTimers(NOW + 10)')
    holder(L, None)
    chk("the catch frame exists, the sticks enabled once out of a fight",
        L.eval('rawget(_G, "WowCompanionMapStickCatch") ~= nil and WowCompanion.mapRoom.stickOn == true'))
    chk("nobody holds: hidden", not shown(L))

    holder(L, "pad:home")
    chk("Home's guest holds, no op: hidden (the 10-09 ruling: the module walks)", not shown(L))
    sticks(L, True)
    chk("op 3 then 1 with Home's guest holding: the catch shows", shown(L))
    chk("... the handler eats the stick (answers false)",
        L.eval('(function() local f = rawget(_G, "WowCompanionMapStickCatch"); return f.scripts.OnGamePadStick(f, "Left", 0.9, 0) end)()') is False)
    sticks(L, False)
    chk("op 3 then 0: hidden", not shown(L))

    # another holder: the op alone shows nothing
    holder(L, "pad:quests")
    sticks(L, True)
    chk("op 1 while another guest holds: hidden", not shown(L))
    holder(L, "pad:home")
    chk("... and Home taking the controller later needs a fresh 1", not shown(L))
    sticks(L, True)
    chk("a fresh 1: shown", shown(L))

    # a holder change away from Home lets go
    holder(L, "pad:radial")
    chk("Home's guest lost the controller: hidden", not shown(L))
    holder(L, "pad:home")
    chk("... back to Home: still hidden until the app says 1", not shown(L))

    # the lapse: 10 s after the last 1, by the 1 s tick
    sticks(L, True)
    L.execute('NOW = NOW + 6; tick(1.0)')
    chk("6 s after the 1: still shown", shown(L))
    sticks(L, True)                                   # the app's keep-alive
    L.execute('NOW = NOW + 8; tick(1.0)')
    chk("8 s after a keep-alive: still shown", shown(L))
    L.execute('NOW = NOW + 3; tick(1.0)')
    chk("11 s after the last 1 (the app gone): the sticks go by themselves", not shown(L))

    # fights: Show / Hide work, nothing blocked
    sticks(L, True)
    L.execute('COMBAT = true; VIOL = {}')
    sticks(L, False)
    chk("in a fight: op 0 hides", not shown(L))
    sticks(L, True)
    chk("in a fight: op 1 shows", shown(L))
    L.execute('NOW = NOW + 11; tick(1.0)')
    chk("in a fight: the lapse lets go too", not shown(L))
    chk("... nothing blocked in the fight", L.eval('#VIOL') == 0, L.eval('VIOL[1]'))
    L.execute('COMBAT = false')

    # the Map room's own hold, as before
    holder(L, "map")
    chk("the Map room holds: shown (mr7, unchanged)", shown(L))
    holder(L, None)
    chk("the Map room let go: hidden", not shown(L))

    # op 2 (the minimap options) still goes to WC.mmp
    L.execute('GOT = nil; WowCompanion.mmp = WowCompanion.mmp or {}; local was = WowCompanion.mmp.setOpt; '
              'WowCompanion.mmp.setOpt = function(b) GOT = b end')
    cell(L, WP_A + 2); cell(L, WP_B + 5)
    chk("op 2's bits still reach the minimap options", L.eval('GOT') == 5, L.eval('GOT'))


run()
print("%d failure(s)" % len(fails))
sys.exit(1 if fails else 0)
