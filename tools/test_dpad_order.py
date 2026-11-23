"""THE D-PAD ORDER, THE ADD-ON'S HALF (PadMap m184 / Book b77; test build 1518 build 3, the user's ruling 2026-11-21: the top bar's D-pad
icons are apps - dragging one onto another D-pad spot swaps what those two directions open), off-device: the REAL add-on files under
test_party.py's stubbed game (test_fight_layer.py's world), as test_roombar_mirror.py.

    python -B tools/test_dpad_order.py

Proven here:
  0 the tags (PadMap m184 / Book b77, the older notes kept), the main chunk's 200 locals, the app's rank table (ToolbarMirror.WORDS, the
    page-3 pick 24) agrees with the add-on's;
  1 every rank 0-23 (padtab 39, 38, 37, 24 then the rank) is this character's (WowCompanionSpellsDB.rbp[name-realm] = rank + 1), bound at
    once out of combat: each router D-pad button clicks the Hub button of the word at its position; `d` says the order, `m` = 1 only for
    the mirrored table (rank 14), pp = 1;
  2 IN A FIGHT the order is remembered but nothing is re-bound: `d` keeps saying what the keys do, and the fight's end binds it;
  3 no character key (whoKey nil): refused ("no character"), nothing stored, nothing bound;
  4 the mirror bit's codes 20 / 21 win back over an order (rbp 0, never nil); per character (another has none);
  5 the earlier page-3 codes still reach their own handlers, and a stale pick (over 2 s) sends nothing;
plus no spied call, no lockdown breach, no snippet error, no Lua error. Needs lupa. Exit code 1 on any failure."""
import itertools
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
KT = os.path.join(ROOT, "companion", "app", "src", "main", "java", "com", "jrees", "wowcompanion")
TM = open(os.path.join(KT, "ToolbarMirror.kt"), encoding="utf-8").read()
PMM = open(os.path.join(KT, "PadMapModel.kt"), encoding="utf-8").read()
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


def state(L):
    st = L.eval('WowCompanion.padMap.dpadState()')
    dd = st['d']
    return {int(k): dd[k] for k in dd.keys()}, st['m'], st['pp']


def roombar(L, pos):
    return L.eval('router(%d) and router(%d).a["wc-roombar"]' % (pos, pos))


def clean(L, name):
    check(L.eval('#SPY') == 0 and L.eval('#VIOL') == 0 and L.eval('#SNIPERR') == 0 and list(L.eval('ERRS').values()) == [],
          name + ": no spied call (no trace), no lockdown breach, no snippet error",
          (L.eval('SPY[1]'), L.eval('VIOL[1]'), L.eval('SNIPERR[1]'), list(L.eval('ERRS').values())[:2]))


WORDS = ["chat", "radial", "map", "home"]
BTN = {"chat": "WowCompanionPadChat", "radial": "WowCompanionPadRadial", "map": "WowCompanionPadMap", "home": "WowCompanionPadHome"}
ORDERS = list(itertools.permutations(WORDS))          # lexicographic over WORDS' order: index = rank (ToolbarMirror.rank)
NORMAL = {1: "chat", 2: "radial", 3: "map", 4: "home"}
MIRROR = {1: "map", 2: "radial", 3: "chat", 4: "home"}

# ------------------------------------------------------------------------------------------------ 0 the tags and the app's table
check('local PV = "m185"' in SRC["padmap"] and "m184 THE D-PAD ORDER" in SRC["padmap"] and "m178 MOVABLE HOME'S SWAP" in SRC["padmap"],
      "0. PadMap's tag m184 (m178's swap note kept)")
check('local BV = "b77"' in SRC["book"] and "b77 THE D-PAD ORDER" in SRC["book"] and "P3[24] = function()" in SRC["book"]
      and "for k = 0, 23 do P2.P4[k]" in SRC["book"], "0. Book's tag b77: code 24 then the rank 0-23")
check(len(re.findall(r'(?m)^local ', SRC["padmap"])) <= 200, "0. PadMap's main chunk keeps to Lua 5.1's 200 locals")
m = re.search(r'val WORDS = listOf\(([^)]*)\)', TM)
check(m is not None and re.findall(r'"(\w+)"', m.group(1)) == WORDS, "0. the app ranks over the add-on's ROOMBAR words in its order",
      m and m.group(1))
check('const val P3_PERM_PICK = 24' in PMM, "0. the app's pick is page-3 code 24")
check(ORDERS.index(("chat", "radial", "map", "home")) == 0 and ORDERS.index(("map", "radial", "chat", "home")) == 14,
      "0. rank 0 = today's table, 14 = the mirrored one (ToolbarMirror.rank)")

W = boot()
pm = 'WowCompanion.padMap'
W.execute(pm + '.dpadBar(true); runTimers(NOW + 1)')
dirs, mm, pp = state(W)
check(dirs == NORMAL and mm == 0 and pp == 1, "0. no order: today's table, m = 0, pp = 1", (dirs, mm, pp))

# ------------------------------------------------------------------------------------------------ 1 every rank, out of combat
bad = []
for rank, order in enumerate(ORDERS):
    padtab(W, 39, 38, 37, 24, rank)
    W.execute('runTimers(NOW + 1)')
    want = {i + 1: w for i, w in enumerate(order)}
    dirs, mm, pp = state(W)
    stored = W.eval('(WowCompanionSpellsDB.rbp or {})[WHO .. "-" .. REALM]')
    bound = {p: roombar(W, p) for p in range(1, 5)}
    if dirs != want or stored != rank + 1 or mm != (1 if rank == 14 else 0) or bound != {p: BTN[want[p]] for p in range(1, 5)}:
        bad.append((rank, dirs, stored, mm, bound))
check(not bad, "1. all 24 orders: stored as rank + 1, bound at once (each direction clicks its word's Hub button), d says it, m only for 14",
      bad[:2])
clean(W, "1")

# ------------------------------------------------------------------------------------------------ 2 a fight: remembered, bound after it
padtab(W, 39, 38, 37, 24, 0)
W.execute('runTimers(NOW + 1)')
W.execute('fightOn()')
rank = ORDERS.index(("radial", "chat", "home", "map"))
padtab(W, 39, 38, 37, 24, rank)
W.execute('tick(0.5)')
check(W.eval('(WowCompanionSpellsDB.rbp or {})[WHO .. "-" .. REALM]') == rank + 1, "2. in a fight: the order remembered at once")
dirs, mm, pp = state(W)
check(dirs == NORMAL and roombar(W, 1) == "WowCompanionPadChat", "2. in a fight nothing re-bound: d still says what the keys do", (dirs, roombar(W, 1)))
clean(W, "2 (in the fight)")
W.execute('fightOff(); tick(0.5); runTimers(NOW + 2); tick(0.5)')
dirs, mm, pp = state(W)
check(dirs == {1: "radial", 2: "chat", 3: "home", 4: "map"} and roombar(W, 1) == "WowCompanionPadRadial" and roombar(W, 4) == "WowCompanionPadMap",
      "2. the fight's end binds the new order", (dirs, roombar(W, 1), roombar(W, 4)))
clean(W, "2 (after it)")

# ------------------------------------------------------------------------------------------------ 3 no character key: refused
W.execute('_G.__keepKey = WowCompanion.charKey; WowCompanion.charKey = function() return nil end')
before = W.eval('(WowCompanionSpellsDB.rbp or {})[WHO .. "-" .. REALM]')
ok = W.eval('{ WowCompanion.padMap.dpadPerm(3) }')
check(ok[1] is False and ok[2] == "no character", "3. no key: dpadPerm refuses (no character)", (ok[1], ok[2]))
padtab(W, 39, 38, 37, 24, 5)
W.execute('runTimers(NOW + 1)')
check(W.eval('(WowCompanionSpellsDB.rbp or {})[WHO .. "-" .. REALM]') == before, "3. no key: the page-3 code stores nothing")
W.execute('WowCompanion.charKey = _G.__keepKey')
check(W.eval('{ WowCompanion.padMap.dpadPerm(24) }')[1] is False, "3. a rank past 23 is refused")

# ------------------------------------------------------------------------------------------------ 4 the mirror bit wins back; per character
padtab(W, 39, 38, 37, 21)
W.execute('runTimers(NOW + 1)')
dirs, mm, pp = state(W)
check(W.eval('WowCompanionSpellsDB.rbp[WHO .. "-" .. REALM]') == 0 and dirs == MIRROR and mm == 1,
      "4. code 21 clears the order (rbp 0, never nil) and binds the mirrored table, m = 1", (dirs, mm))
padtab(W, 39, 38, 37, 20)
W.execute('runTimers(NOW + 1)')
dirs, mm, pp = state(W)
check(dirs == NORMAL and mm == 0, "4. code 20: today's table", (dirs, mm))
padtab(W, 39, 38, 37, 24, 7)
W.execute('runTimers(NOW + 1)')
check(W.eval('WowCompanionSpellsDB.rbp["Other-" .. REALM]') is None and W.eval('WowCompanionSpellsDB.rbp[WHO .. "-" .. REALM]') == 8,
      "4. the order is this character's only (another has none)")

# ------------------------------------------------------------------------------------------------ 5 the older codes; a stale pick
W.execute('FOLDED = {}; WowCompanion.padTabCtl.touchFold = function(k) FOLDED[#FOLDED + 1] = k end; WowCompanion._spellsBook.ctl.croom = true')
padtab(W, 39, 38, 37, 5)
check(W.eval('#FOLDED') == 1 and W.eval('FOLDED[1]') == 2 and W.eval('WowCompanionSpellsDB.rbp[WHO .. "-" .. REALM]') == 8,
      "5. the earlier page-3 codes still reach their own handlers (Extra's fold 2), the order untouched", W.eval('#FOLDED'))
padtab(W, 39, 38, 37, 24)
W.execute('NOW = NOW + 3')
padtab(W, 3)
W.execute('runTimers(NOW + 1)')
check(W.eval('WowCompanionSpellsDB.rbp[WHO .. "-" .. REALM]') == 8, "5. a pick older than 2 s sends no order")
clean(W, "5")

print('\n%d passed, %d failed' % (passes, fails))
print('failed: %d' % fails)
sys.exit(1 if fails else 0)
