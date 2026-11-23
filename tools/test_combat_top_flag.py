"""MOVABLE HOME PHASE 3, THE TOP-SCREEN COMBAT BAR'S FLAG, THE ADD-ON'S HALF (PadMap m179 / Book b75; SPEC.txt sections 11 / 12, the
user's rulings 2026-11-20: D1 "In fights: Top screen" is OPT-IN), off-device: the REAL add-on files under test_party.py's stubbed game
(test_fight_layer.py's world, as test_roombar_mirror.py).

    python -B tools/test_combat_top_flag.py

Proven here:
  1 by default (no flag): `ct` = 0 in the dpad state, WC.padMap.combatTopOn() false (ActionBar nbar7 shows nothing: the bottom band's
    Grow / Same size stay the player's);
  2 the app's page-3 code (padtab 39, 38, 37 then 23; Book b76, the 1515 merge: off the sticky realm question's 9-19): the flag is this character's (WowCompanionSpellsDB.ctop[name-realm] = 1), `ct` = 1,
    the padmap sent again; nothing bound (the router's buttons and the D-pad's sides unchanged);
  3 code 22 clears it; a code in a fight is remembered at once (a display flag only: no binding, no attribute - nothing protected);
  4 per character: another character has its own (none = off);
  5 the earlier page-3 codes still reach their own handlers (the mirror 20 / 21, Extra's folds 4-8);
  6 the add-on's own Layouts reset (WC.dsfReset 21) clears it with the other layout stores, and lists it for the app's ResetPlan;
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


def st(L):
    s = L.eval('WowCompanion.padMap.dpadState()')
    dd = s['d']
    return s['ct'], {int(k): dd[k] for k in dd.keys()}, s['m']


def clean(L, name):
    check(L.eval('#SPY') == 0 and L.eval('#VIOL') == 0 and L.eval('#SNIPERR') == 0 and list(L.eval('ERRS').values()) == [],
          name + ": no spied call (no trace), no lockdown breach, no snippet error",
          (L.eval('SPY[1]'), L.eval('VIOL[1]'), L.eval('SNIPERR[1]'), list(L.eval('ERRS').values())[:2]))


def sent_ct(L, n0):
    """the `ct` of every padmap sent since [n0] (the documents the app reads)"""
    import json
    out = []
    for i in range(n0 + 1, int(L.eval('#SENT')) + 1):
        t = L.eval('SENT[%d]' % i)
        try:
            d = json.loads(t)
        except Exception:
            continue
        if isinstance(d, dict) and d.get('type') == 'padmap':
            out.append(((d.get('m') or {}).get('dpad') or {}).get('ct'))
    return out


NORMAL = {1: "chat", 2: "radial", 3: "map", 4: "home"}

# ------------------------------------------------------------------------------------------------ the tags
check('local PV = "m185"' in SRC["padmap"] and "m179 THE COMBAT BAR ON THE TOP SCREEN" in SRC["padmap"], "0. PadMap's tag m179 and its note")
check('local BV = "b76"' in SRC["book"] and "b75 THE COMBAT BAR ON THE TOP SCREEN" in SRC["book"], "0. Book's tag b76 (b75's note kept; its codes now 22 / 23)")
check(len(re.findall(r'(?m)^local ', SRC["padmap"])) <= 200, "0. PadMap's main chunk keeps to Lua 5.1's 200 locals")

W = boot()
pm = 'WowCompanion.padMap'
W.execute(pm + '.dpadBar(true); runTimers(NOW + 1)')

# ------------------------------------------------------------------------------------------------ 1 the default: off
ct, dirs, m = st(W)
check(ct == 0 and W.eval(pm + '.combatTopOn()') is False, "1. no flag: ct = 0, combatTopOn() false (the bottom's Grow / Same size)", ct)
binds0 = [W.eval('router(%d) and router(%d).a["wc-roombar"]' % (p, p)) for p in range(1, 5)]

# ------------------------------------------------------------------------------------------------ 2 the app's code 23, out of combat
n0 = int(W.eval('#SENT'))
padtab(W, 39, 38, 37, 23)
W.execute('runTimers(NOW + 1)')
who = W.eval('WHO .. "-" .. REALM')
check(W.eval('(WowCompanionSpellsDB.ctop or {})[WHO .. "-" .. REALM]') == 1, "2. the flag kept for this character (ctop[%s] = 1)" % who)
ct, dirs, m = st(W)
check(ct == 1 and W.eval(pm + '.combatTopOn()') is True, "2. ct = 1, combatTopOn() true (ActionBar's Wings read it)", ct)
check(1 in sent_ct(W, n0), "2. a padmap with ct = 1 sent (the app stops re-sending)", sent_ct(W, n0))
check(dirs == NORMAL and m == 0 and [W.eval('router(%d) and router(%d).a["wc-roombar"]' % (p, p)) for p in range(1, 5)] == binds0,
      "2. nothing bound: the D-pad's sides and the router's buttons unchanged", (dirs, m))
clean(W, "2")

# ------------------------------------------------------------------------------------------------ 3 code 22, and a code in a fight
padtab(W, 39, 38, 37, 22)
W.execute('runTimers(NOW + 1)')
check(W.eval('(WowCompanionSpellsDB.ctop or {})[WHO .. "-" .. REALM]') == 0 and st(W)[0] == 0, "3. code 22: off again (m180: an explicit 0, kept by the sync merge)")
W.execute('fightOn()')
padtab(W, 39, 38, 37, 23)
W.execute('tick(0.5)')
check(W.eval('(WowCompanionSpellsDB.ctop or {})[WHO .. "-" .. REALM]') == 1 and W.eval(pm + '.combatTopOn()') is True,
      "3. in a fight: remembered at once (a display flag: nothing protected)")
clean(W, "3 (in the fight)")
W.execute('fightOff(); tick(0.5); runTimers(NOW + 2); tick(0.5)')
check(st(W)[0] == 1, "3. after the fight: still on")
clean(W, "3 (after it)")

# ------------------------------------------------------------------------------------------------ 4 per character
check(W.eval('(WowCompanionSpellsDB.ctop or {})["Other-" .. REALM]') is None, "4. another character's flag: none (off)")
W.execute('WowCompanionSpellsDB.ctop["Other-" .. REALM] = 1; WowCompanionSpellsDB.ctop[WHO .. "-" .. REALM] = nil')
check(st(W)[0] == 0 and W.eval(pm + '.combatTopOn()') is False, "4. the flag is this character's only (another's on: mine off)")
W.execute('WowCompanionSpellsDB.ctop["Other-" .. REALM] = nil')

# ------------------------------------------------------------------------------------------------ 5 the older page-3 codes
padtab(W, 39, 38, 37, 21)
W.execute('runTimers(NOW + 1)')
ct, dirs, m = st(W)
check(m == 1 and dirs[1] == "map" and ct == 0, "5. code 21 still the toolbar's mirror (the flag untouched)", (ct, dirs, m))
padtab(W, 39, 38, 37, 20)
W.execute('runTimers(NOW + 1)')
W.execute('FOLDED = {}; WowCompanion.padTabCtl.touchFold = function(k) FOLDED[#FOLDED + 1] = k end; WowCompanion._spellsBook.ctl.croom = true')
padtab(W, 39, 38, 37, 5)
check(W.eval('#FOLDED') == 1 and W.eval('FOLDED[1]') == 2 and st(W)[0] == 0, "5. Extra's fold 2 still its own (the flag untouched)")
clean(W, "5")

# ------------------------------------------------------------------------------------------------ 6 the add-on's own Layouts reset
padtab(W, 39, 38, 37, 23)
W.execute('runTimers(NOW + 1)')
keys = W.eval('WowCompanion.dsfResetKeys.spells.layouts')
check(keys is not None and "ctop" in [keys[i] for i in range(1, len(keys) + 1)], "6. ctop is in the add-on's Layouts list (= ResetPlan's)")
W.execute('WowCompanion.dsfReset(21)')
check(W.eval('(WowCompanionSpellsDB.ctop or {})[WHO .. "-" .. REALM]') is None and st(W)[0] == 0, "6. Layouts reset: the flag gone (off)")
clean(W, "6")

print('\n%d passed, %d failed' % (passes, fails))
print('failed: %d' % fails)
sys.exit(1 if fails else 0)
