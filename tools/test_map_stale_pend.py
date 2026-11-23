"""#584's review, THE MAP ROOM'S STALE PEND: Map.lua off-device (the REAL Safety / TapPad / Map files under tools/wowstub.py's stubbed
game, Lua 5.1 as the game's), with a stub capture whose take in a fight is a pending ENTER (ok "pending", nobody named) and out of one
holds at once, or queues behind another holder.

    python tools/test_map_stale_pend.py

Interact drops a fight's pending ENTER at the fight's end (or a newer fight take replaces it) without telling the room lost, so Map.lua
stayed held + pend while the capture named NOBODY: every hold ask answered "ok, waits", nothing took again, and the app's cursor waited
all visit. Checks: out of a fight with nobody holding the pend is stale - the app's hold ask takes for real (answered held, no waits),
the fight's end (PLAYER_REGEN_ENABLED) takes again, the 1 s tick takes again; in a fight the pend is the ENTER's and is left alone; a
take queued behind another holder stays queued (no re-take, no ping-pong). Nothing is blocked in the fight. Exit code 1 on a failure."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from wowstub import boot, docs, nsent   # noqa: E402

HELPERS = r'''
TAKES = 0
WowCompanion.capture.take = function(id, spec)
    TAKES = TAKES + 1
    if COMBAT then return true, "pending" end                              -- a fight's take: pending until the ENTER (nobody named)
    local h = rawget(_G, "HOLDER")                                         -- (an unset global is the stub's table: rawget)
    if h ~= nil and h ~= id then return true, "queued" end                 -- v11: queued behind the holder
    HOLDER = id
    return true
end
WowCompanion.capture.release = function(id) if rawget(_G, "HOLDER") == id then HOLDER = nil end; return true end
'''

fails = []


def chk(name, ok, got=''):
    print(("ok   " if ok else "FAIL ") + name + ("" if ok else "  got: %s" % (got,)))
    if not ok:
        fails.append(name)


HOLD = 0


def fresh():
    L = boot(["safety", "tappad", "maproom"], pre=HELPERS)
    L.execute('fire("ADDON_LOADED", "WowCompanion"); fire("PLAYER_LOGIN"); fire("PLAYER_ENTERING_WORLD", true, false); runTimers(NOW + 10)')
    L.execute('HOLDER = nil; VIOL = {}')
    return L


def hold(L):
    """the app's hold ask (the room's row, the HOLD cell); the mapack it answered"""
    n = nsent(L)
    L.execute('WowCompanion.mapRoomCell(%d); NOW = NOW + 0.15' % HOLD)
    acks = [d for d in docs(L, n, "mapack") if d.get("op") == "hold"]
    return acks[-1] if acks else None


def st(L):
    return (L.eval('WowCompanion.mapRoom.held') is True, L.eval('WowCompanion.mapRoom.pend') is True, L.eval('rawget(_G, "HOLDER")'), int(L.eval('TAKES')))


def fight_take(L):
    L.execute('COMBAT = true')
    a = hold(L)
    chk("in a fight: the take is pending (ok, waits)", a is not None and a.get("ok") is True and a.get("waits") is True, a)
    held, pend, holder, _ = st(L)
    chk("... held + pend, the capture naming nobody", held and pend and holder is None, st(L))


def run():
    # A: the app's re-ask after the fight (no event, no tick yet: the exit guard's ask)
    L = fresh()
    fight_take(L)
    L.execute('COMBAT = false')
    t0 = st(L)[3]
    a = hold(L)
    held, pend, holder, takes = st(L)
    chk("A: out of the fight, nobody holding: the hold ask takes for real", takes == t0 + 1, (t0, takes))
    chk("A: ... answered held (no waits)", a is not None and a.get("ok") is True and not a.get("waits"), a)
    chk("A: ... the room holds, not pending", held and not pend and holder == "map", st(L))
    a = hold(L)
    chk("A: a second ask while really held takes nothing", st(L)[3] == takes and a.get("ok") is True and not a.get("waits"), (st(L), a))
    chk("A: nothing blocked", L.eval('#VIOL') == 0, L.eval('VIOL[1]'))

    # B: the fight's end squares it (PLAYER_REGEN_ENABLED -> mrAfterFight, a frame later)
    L = fresh()
    fight_take(L)
    L.execute('COMBAT = false; fire("PLAYER_REGEN_ENABLED"); runTimers(NOW + 1)')
    held, pend, holder, _ = st(L)
    chk("B: the fight's end takes again: held, not pending", held and not pend and holder == "map", st(L))
    chk("B: nothing blocked", L.eval('#VIOL') == 0, L.eval('VIOL[1]'))

    # C: the 1 s tick (the fight's end missed: the ENTER replaced by another fight take, then the regen squared nothing)
    L = fresh()
    fight_take(L)
    L.execute('COMBAT = false; tick(1.0)')
    held, pend, holder, _ = st(L)
    chk("C: the tick takes again: held, not pending", held and not pend and holder == "map", st(L))
    L.execute('WowCompanion.mapRoom.want = false; HOLDER = nil; WowCompanion.mapRoom.held = true; WowCompanion.mapRoom.pend = true')
    t0 = st(L)[3]
    L.execute('tick(1.0)')
    chk("C: a room that left (want off) is not taken by the tick", st(L)[3] == t0, st(L))

    # D: in a fight the pend is the ENTER's: left alone
    L = fresh()
    fight_take(L)
    t0 = st(L)[3]
    a = hold(L)
    L.execute('tick(1.0)')
    held, pend, holder, takes = st(L)
    chk("D: in a fight the ask answers pending again, no new take", takes == t0 and a.get("ok") is True and a.get("waits") is True, (st(L), a))
    chk("D: ... still held + pend (the ENTER's)", held and pend, st(L))
    chk("D: nothing blocked in the fight", L.eval('#VIOL') == 0, L.eval('VIOL[1]'))
    L.execute('COMBAT = false')

    # E: queued behind another holder out of a fight: not stale
    L = fresh()
    L.execute('HOLDER = "pad:quests"')
    a = hold(L)
    chk("E: queued behind the Quests room: ok, waits", a is not None and a.get("ok") is True and a.get("waits") is True, a)
    t0 = st(L)[3]
    a = hold(L)
    L.execute('tick(1.0)')
    held, pend, holder, takes = st(L)
    chk("E: ... asked again: still queued, no re-take (no ping-pong in the queue)", takes == t0 and held and pend and holder == "pad:quests", st(L))


run()
print("%d failure(s)" % len(fails))
sys.exit(1 if fails else 0)
