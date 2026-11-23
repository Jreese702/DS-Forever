"""MOVABLE HOME'S SWAP, THE ADD-ON'S HALF (PadMap m178 / Book b74; SPEC.txt section 6 item 4, the user's rulings 2026-11-20), off-device: the
REAL add-on files under test_party.py's stubbed game (test_fight_layer.py's world).

    python -B tools/test_roombar_mirror.py

With the app's chat on the right of Home, the DS toolbar's D-pad Left opens the map and Right the chat. Proven here:
  1 by default (no bit): `d` = 1 chat, 2 radial, 3 map, 4 home; m = 0; the router's Left button clicks WowCompanionPadChat;
  2 the app's page-3 code (padtab 39, 38, 37 then 21; Book b76, the 1515 merge: 9 / 10 were the mirror's, now the sticky realm question's): the bit is this character's (WowCompanionSpellsDB.rbm[name-realm] = 1), bound at
    once out of combat: Left clicks WowCompanionPadMap, Right WowCompanionPadChat; `d` says 1 map / 3 chat, m = 1; Up / Down unchanged;
  3 IN A FIGHT the code is remembered but nothing is re-bound (no protected call from insecure code): `d` keeps saying what the keys
    do (the table last bound), and the fight's end binds the new sides (the router's out-of-combat check), `d` following;
  4 per character: another character has its own bit (none = today's sides);
  5 code 20 clears it; the earlier page-3 codes (Extra's folds 4-8) still reach their own handlers;
plus no spied call, no lockdown breach, no snippet error, no Lua error. Needs lupa. Exit code 1 on any failure."""
import os
import re
import sys

from lupa import lua51 as lupa

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ADDONS = os.path.join(ROOT, "companion", "app", "src", "main", "assets", "companion", "addons")
F = {
    "safety": os.path.join(ADDONS, "WowCompanion", "Safety.lua"),
    "tappad": os.path.join(ADDONS, "WowCompanion", "TapPad.lua"),
    "book": os.path.join(ADDONS, "WowCompanionSpells", "Book.lua"),
    "padmap": os.path.join(ADDONS, "WowCompanionSpells", "PadMap.lua"),
    "padtabctl": os.path.join(ADDONS, "WowCompanionSpells", "PadTabCtl.lua"),
}
SRC = {k: open(p, encoding="utf-8").read() for k, p in F.items()}
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from wowstub import GAME  # noqa: E402
PARTY = open(os.path.join(ROOT, "tools", "test_party.py"), encoding="utf-8").read()
WORLD_HELPERS = re.search(r"\nWORLD_HELPERS = r'''(.*?)'''", PARTY, re.S).group(1)
FIGHT = open(os.path.join(ROOT, "tools", "test_fight_layer.py"), encoding="utf-8").read()
EXTRA = re.search(r"\nEXTRA = r'''(.*?)'''", FIGHT, re.S).group(1)
AFTER = re.search(r"\nAFTER = r'''(.*?)'''", FIGHT, re.S).group(1)
fails = 0
passes = 0


def check(ok, name, got=''):
    global fails, passes
    print(('PASS ' if ok else 'FAIL ') + name + ((' -> ' + str(got)) if got != '' else ''))
    if ok:
        passes += 1
    else:
        fails += 1


def boot():
    L = lupa.LuaRuntime(unpack_returned_tuples=True)
    L.execute(GAME)
    L.execute(EXTRA)
    load = L.eval('function(src, name) local f, e = loadstring(src, name); if not f then error(e) end; return f end')
    for k in ("safety", "tappad", "book", "padmap", "padtabctl"):
        load(SRC[k], '@' + os.path.basename(F[k]))(os.path.basename(F[k]))
    L.execute(WORLD_HELPERS)
    L.execute('fire("PLAYER_LOGIN"); fire("PLAYER_ENTERING_WORLD", true, false); WowCompanion.pad.layout(); runTimers(NOW + 10)')
    L.execute(AFTER)
    L.execute('WowCompanion.padMap.emit(true); runTimers(NOW + 1)')
    return L


def padtab(L, *cols):
    for c in cols:
        L.execute('WowCompanion.pad.rows["padtab"].handler(nil, %d); NOW = NOW + 0.1' % c)


def d(L):
    st = L.eval('WowCompanion.padMap.dpadState()')
    dd = st['d']
    return {int(k): dd[k] for k in dd.keys()}, st['m']


def roombar(L, pos):
    return L.eval('router(%d) and router(%d).a["wc-roombar"]' % (pos, pos))


def clean(L, name):
    check(L.eval('#SPY') == 0 and L.eval('#VIOL') == 0 and L.eval('#SNIPERR') == 0 and list(L.eval('ERRS').values()) == [],
          name + ": no spied call (no trace), no lockdown breach, no snippet error",
          (L.eval('SPY[1]'), L.eval('VIOL[1]'), L.eval('SNIPERR[1]'), list(L.eval('ERRS').values())[:2]))


NORMAL = {1: "chat", 2: "radial", 3: "map", 4: "home"}
MIRROR = {1: "map", 2: "radial", 3: "chat", 4: "home"}

# ------------------------------------------------------------------------------------------------ the tags
check('local PV = "m185"' in SRC["padmap"] and "m178 MOVABLE HOME'S SWAP" in SRC["padmap"], "0. PadMap's tag m179 (m178's swap note kept)")
check('local BV = "b76"' in SRC["book"] and "b74 MOVABLE HOME'S SWAP" in SRC["book"] and "for k = 0, 1 do P3[20 + k]" in SRC["book"], "0. Book's tag b76 (b74's mirror note kept; its codes now 20 / 21)")
check(len(re.findall(r'(?m)^local ', SRC["padmap"])) <= 200, "0. PadMap's main chunk keeps to Lua 5.1's 200 locals")

W = boot()
pm = 'WowCompanion.padMap'
W.execute(pm + '.dpadBar(true); runTimers(NOW + 1)')

# ------------------------------------------------------------------------------------------------ 1 today's sides
dirs, m = d(W)
check(dirs == NORMAL and m == 0, "1. no bit: d = chat / radial / map / home, m = 0", (dirs, m))
check(roombar(W, 1) == "WowCompanionPadChat" and roombar(W, 3) == "WowCompanionPadMap", "1. Left clicks the Chat, Right the Map",
      (roombar(W, 1), roombar(W, 3)))

# ------------------------------------------------------------------------------------------------ 2 the app's code 21, out of combat
padtab(W, 39, 38, 37, 21)
W.execute('runTimers(NOW + 1)')
who = W.eval('WHO .. "-" .. REALM')
check(W.eval('(WowCompanionSpellsDB.rbm or {})[WHO .. "-" .. REALM]') == 1, "2. the bit kept for this character (rbm[%s] = 1)" % who)
dirs, m = d(W)
check(dirs == MIRROR and m == 1, "2. d = map / radial / chat / home, m = 1", (dirs, m))
check(roombar(W, 1) == "WowCompanionPadMap" and roombar(W, 3) == "WowCompanionPadChat"
      and roombar(W, 2) == "WowCompanionPadRadial" and roombar(W, 4) == "WowCompanionPadHome",
      "2. bound at once: Left the Map, Right the Chat; Up / Down unchanged", [roombar(W, p) for p in range(1, 5)])
clean(W, "2")

# ------------------------------------------------------------------------------------------------ 3 a fight: remembered, bound after it
W.execute('fightOn()')
padtab(W, 39, 38, 37, 20)
W.execute('tick(0.5)')
check(W.eval('(WowCompanionSpellsDB.rbm or {})[WHO .. "-" .. REALM]') == 0, "3. in a fight: code 20 remembered at once (m180: the bit 0, never deleted)")
dirs, m = d(W)
check(dirs == MIRROR and m == 1 and roombar(W, 1) == "WowCompanionPadMap",
      "3. in a fight nothing re-bound: d still says what the keys do (Left the map)", (dirs, m, roombar(W, 1)))
clean(W, "3 (in the fight)")
W.execute('fightOff(); tick(0.5); runTimers(NOW + 2); tick(0.5)')
dirs, m = d(W)
check(dirs == NORMAL and m == 0 and roombar(W, 1) == "WowCompanionPadChat",
      "3. the fight's end binds the new sides: Left the Chat again", (dirs, m, roombar(W, 1)))
clean(W, "3 (after it)")

# ------------------------------------------------------------------------------------------------ 4 per character
padtab(W, 39, 38, 37, 21)
W.execute('runTimers(NOW + 1)')
W.execute('WowCompanionSpellsDB.rbm["Other-' + W.eval('REALM') + '"] = nil')
check(W.eval('WowCompanionSpellsDB.rbm["Other-" .. REALM]') is None and W.eval('WowCompanionSpellsDB.rbm[WHO .. "-" .. REALM]') == 1,
      "4. the bit is this character's only (another has none: today's sides)")

# ------------------------------------------------------------------------------------------------ 5 code 20 and the older codes
padtab(W, 39, 38, 37, 20)
W.execute('runTimers(NOW + 1)')
dirs, m = d(W)
check(dirs == NORMAL and m == 0, "5. code 20: today's sides again", (dirs, m))
W.execute('FOLDED = {}; WowCompanion.padTabCtl.touchFold = function(k) FOLDED[#FOLDED + 1] = k end; WowCompanion._spellsBook.ctl.croom = true')
padtab(W, 39, 38, 37, 5)
check(W.eval('#FOLDED') == 1 and W.eval('FOLDED[1]') == 2 and d(W)[1] == 0,
      "5. the earlier page-3 codes still reach their own handlers (Extra's fold 2), the bit untouched", (W.eval('#FOLDED'), d(W)))
clean(W, "5")

print('\n%d passed, %d failed' % (passes, fails))
print('failed: %d' % fails)
sys.exit(1 if fails else 0)
