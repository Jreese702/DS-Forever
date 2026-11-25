"""THE SAVED-SETTINGS PACK ONLY AFTER A CHANGE (PadMap m187; stream smoothness, a tester's "stutters every few seconds" while streaming,
the user's pick 2026-11-23), off-device: the REAL Spells files (Safety, TapPad, Book, PadMap, PadTabCtl) under the stubbed game
(tools/wowstub.py), Lua 5.1 as the game's.

    python tools/test_svkeep_dirty.py

m51's svkeep serialized and sorted ALL of both saved tables every 5 s whatever happened. Proves m187: (1) nothing happened = no pack
on the 5 s tick (the work itself is skipped, not only the send); (2) a tap from the app (TapPad's landed count) = a pack on the next
tick and the changed table sent; (3) a mouse click in the game, a fight's end, a new world, a spec change each mark it the same way;
(4) never in a fight, even with a change waiting - it is packed at the first tick after the fight; (5) the safety net: a write by any
other route reaches the app within 30 s (out of combat); (6) send(true) (login, a reset) still sends at once; (7) the "only when the
text changed" rule holds (a pack of unchanged tables sends nothing); (8) no spied call (nothing of the game's written: no CVar,
binding, macro), no Lua error. Needs lupa. Exit code 1 on any failure."""
import sys

from wowstub import boot, docs, nsent, SRC

fails = 0
passes = 0


def check(ok, name, got=''):
    global fails, passes
    print(('PASS ' if ok else 'FAIL ') + name + ((' -> ' + str(got)) if got != '' else ''))
    if ok:
        passes += 1
    else:
        fails += 1


def world():
    L = boot(["safety", "tappad", "book", "padmap", "padtabctl"], pre='WowCompanionSpellsDB.dpad = { ["Korvash-Realm"] = true }')
    L.execute('fire("PLAYER_LOGIN"); fire("PLAYER_ENTERING_WORLD", true, false); runTimers(NOW + 10)')
    return L


def packs(L):
    return int(L.eval('WowCompanion.svKeep.state.packs'))


def sv(L, n0, var='WowCompanionSpellsDB'):
    return [x for x in docs(L, n0, 'svkeep') if x.get('var') == var]


def tap(L):
    """a tap from the app as TapPad counts it (pressCell -> landed: every plain and secure cell's press)"""
    L.execute('WowCompanion.pad.landed(0, 0); NOW = NOW + 0.1')


pm = SRC["padmap"]
check('local PV = "m194"' in pm and "m187 THE SAVED-SETTINGS PACK ONLY AFTER A CHANGE" in pm, "PadMap m187 and its note")
check(pm.count('pcall(C_Timer.NewTicker, 5, function() pcall(send, false) end)') == 0, "the old every-5-s pack is gone")

L = world()
check(list(L.eval('ERRS').values()) == [] and L.eval('#SNIPERR') == 0, "the world loads, no error", list(L.eval('ERRS').values())[:2])
check(L.eval('type(WowCompanion.svKeep.tick)') == 'function' and int(L.eval('WowCompanion.svKeep.SAFETY_S')) == 30,
      "svKeep.tick on the 5 s ticker; the safety net 30 s")

# the first tick packs (dirty at load), then quiet
L.execute('tick(5)')
p0 = packs(L)
check(p0 >= 1, "the first tick after load packs", p0)

# ---------------------------------------------------------------- (1) nothing happened: no pack
for _ in range(4):
    L.execute('NOW = NOW + 5; tick(5)')
check(packs(L) == p0, "(1) 20 s of nothing: no pack on any 5 s tick", (p0, packs(L)))

# ---------------------------------------------------------------- (2) a tap: a pack on the next tick, the change sent
n0 = nsent(L)
L.execute('WowCompanionSpellsDB.startOver = "game"')
tap(L)
L.execute('NOW = NOW + 5; tick(5)')
s = sv(L, n0)
check(packs(L) == p0 + 1 and len(s) == 1 and '["startOver"]="game"' in s[-1]['lua'], "(2) a tap: one pack, the changed table sent",
      (packs(L), len(s)))
# (7) the next tap with nothing changed: a pack, nothing sent
n0 = nsent(L)
tap(L)
L.execute('NOW = NOW + 5; tick(5)')
check(packs(L) == p0 + 2 and sv(L, n0) == [] and sv(L, n0, 'WowCompanionDB') == [], "(7) a pack of unchanged tables sends nothing")

# ---------------------------------------------------------------- (3) the other signs
for ev in ("GLOBAL_MOUSE_UP", "PLAYER_ENTERING_WORLD", "LOADING_SCREEN_DISABLED", "ACTIVE_TALENT_GROUP_CHANGED",
           "PLAYER_SPECIALIZATION_CHANGED", "PLAYER_REGEN_ENABLED"):
    before = packs(L)
    L.execute('NOW = NOW + 5; tick(5)')
    check(packs(L) == before, "(3) %s: quiet before it" % ev)
    L.execute('fire("%s")' % ev)
    L.execute('NOW = NOW + 5; tick(5)')
    check(packs(L) == before + 1, "(3) %s marks a pack" % ev, (before, packs(L)))

# ---------------------------------------------------------------- (4) never in a fight
L.execute('fire("PLAYER_REGEN_DISABLED"); COMBAT = true')
before = packs(L)
n0 = nsent(L)
L.execute('WowCompanionSpellsDB.startOver = nil')
tap(L)
for _ in range(8):                                  # 40 s of fight: past the safety net too
    L.execute('NOW = NOW + 5; tick(5)')
check(packs(L) == before and sv(L, n0) == [], "(4) a tap in a fight: no pack for the whole fight (40 s)", (before, packs(L)))
L.execute('COMBAT = false; fire("PLAYER_REGEN_ENABLED")')
L.execute('NOW = NOW + 5; tick(5)')
s = sv(L, n0)
check(packs(L) == before + 1 and len(s) == 1 and 'startOver' not in s[-1]['lua'], "(4) the first tick after the fight packs it and sends it",
      (packs(L), len(s)))
check(int(L.eval('#VIOL')) == 0, "(4) no protected call in the fight")

# ---------------------------------------------------------------- (5) the safety net: any other route within 30 s
n0 = nsent(L)
before = packs(L)
L.execute('WowCompanionSpellsDB.castBarTab = 3')       # a write with no tap, no click, no event
got_at = None
for i in range(1, 8):
    L.execute('NOW = NOW + 5; tick(5)')
    if sv(L, n0) and got_at is None:
        got_at = i * 5
check(got_at is not None and got_at <= 30, "(5) a write by another route: sent within 30 s (at %s s)" % got_at)
check(packs(L) - before <= 2, "(5) one safety pack per 30 s, not one per tick", packs(L) - before)

# ---------------------------------------------------------------- (6) send(true) unchanged
n0 = nsent(L)
L.execute('WowCompanion.svKeep.send(true)')
check(len(sv(L, n0)) == 1 and len(sv(L, n0, 'WowCompanionDB')) == 1, "(6) send(true): both tables at once, as before")

# ---------------------------------------------------------------- (8) clean
check(L.eval('#SPY') == 0 and L.eval('#SNIPERR') == 0 and list(L.eval('ERRS').values()) == [], "(8) no spied call, no snippet error, no event error",
      (L.eval('SPY[1]'), list(L.eval('ERRS').values())[:2]))

print("\n%d passed, %d failed" % (passes, fails))
sys.exit(1 if fails else 0)
