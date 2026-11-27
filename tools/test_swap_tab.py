"""A SWAP MOVES ONLY THE TAB'S OWN SPELLS (PadMap m208 / PadTabCtl p65), off-device: the REAL add-on files (Safety, TapPad, Book,
PadMap, PadTabCtl) under tools/wowstub.py's game, with test_form_fight.py's Druid world and a working game cursor.

    python -B tools/test_swap_tab.py

The 1.0.39 report: on the Controller room's In combat tab, moving or swapping two buttons also swapped the Out of combat spells on the
same two buttons. The user's ruling (2026-11-27): "a swap moves ONLY the spells of the tab it's done on. In combat moves only the In
combat spells; Out of combat moves only the Out of combat spells."

Proves: (1) the In combat tab: X then A swaps the two buttons' In-combat spells; the Out of combat bar is untouched (no game cursor
call at all); (2) onto an empty In-combat place: the spell moves, the source's In-combat place is empty after, both bar slots stay;
(3) a button with only an Out of combat spell is nothing to move on the In combat tab; (4) the Out of combat tab: X then A swaps the
bar slots; both In-combat spells stay where they were; (5) the In combat tab with a form chosen: only that form's own In-combat
spells move (the caster's fight spells and bar slots untouched); (6) the DS WoW toolbar's D-pad still refuses an In-combat move onto
it; (7) the fight-layer texts follow the move (the router's "wc-ft-<slot>"); (8) no spied call, no lockdown breach, no snippet error.
Needs lupa. Exit code 1 on any failure."""
import os
import re
import sys

from lupa import lua51 as lupa

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from wowstub import GAME, SRC, F  # noqa: E402

FF_SRC = open(os.path.join(ROOT, "tools", "test_form_fight.py"), encoding="utf-8").read()
EXTRA = re.search(r"\nEXTRA = r'''(.*?)'''", FF_SRC, re.S).group(1)
AFTER = re.search(r"\nAFTER = r'''(.*?)'''", FF_SRC, re.S).group(1)
PARTY = open(os.path.join(ROOT, "tools", "test_party.py"), encoding="utf-8").read()
WORLD_HELPERS = re.search(r"\nWORLD_HELPERS = r'''(.*?)'''", PARTY, re.S).group(1)
fails = passes = 0


def check(ok, name, got=''):
    global fails, passes
    print(('PASS ' if ok else 'FAIL ') + name + ((' -> ' + str(got)) if got != '' and not ok else ''))
    if ok:
        passes += 1
    else:
        fails += 1


# the game's cursor over the stub's bar (ACTIONINFO): pick up / place swap what the cursor holds; every call is counted (CURSOR)
CURSORMODEL = r'''
CURSOR = {}
local held = nil
function ClearCursor() CURSOR[#CURSOR + 1] = "clear"; held = nil end
function PickupAction(s) CURSOR[#CURSOR + 1] = "pick " .. s; local was = ACTIONINFO[s]; ACTIONINFO[s] = held; held = was end
function PlaceAction(s) CURSOR[#CURSOR + 1] = "place " .. s; local was = ACTIONINFO[s]; ACTIONINFO[s] = held; held = was end
function GetCursorInfo() if held then return held[1], held[2] end end
function BARID(s) local a = ACTIONINFO[s]; return a and a[2] or nil end
'''


def boot():
    L = lupa.LuaRuntime(unpack_returned_tuples=True)
    L.execute(GAME)
    L.execute(EXTRA)
    load = L.eval('function(src, name) local f, e = loadstring(src, name); if not f then error(e) end; return f end')
    for k in ("safety", "tappad", "book", "padmap", "padtabctl"):
        load(SRC[k], '@' + os.path.basename(F[k]))(os.path.basename(F[k]))
    L.execute(WORLD_HELPERS)
    L.execute('fire("PLAYER_LOGIN"); fire("PLAYER_ENTERING_WORLD", true, false); runTimers(NOW + 10)')
    L.execute(AFTER)
    L.execute(CURSORMODEL)
    L.execute('driveAll(); WowCompanion.padMap.emit(true); runTimers(NOW + 1)')
    return L


def clean(L, name):
    check(L.eval('#SPY') == 0 and L.eval('#VIOL') == 0 and L.eval('#SNIPERR') == 0 and list(L.eval('ERRS').values()) == [],
          name + ": no spied call (no trace), no lockdown breach, no snippet error",
          (L.eval('SPY[1]'), L.eval('VIOL[1]'), L.eval('SNIPERR[1]'), list(L.eval('ERRS').values())[:2]))


pm = 'WowCompanion.padMap'
BEAR, CAT = 5487, 768
WRATH, SHRED, CLAW, MAUL, MANGLE, MOONFIRE = 5176, 5221, 1082, 6807, 33917, 8921


def room(L, view, set_, pos, form_k=0):
    """the Controller room on `view` ("combat" / "normal"), the cursor on (set, pos), the form row's k (0 = the caster)"""
    L.execute('WowCompanion.padTabCtl.enter("buttons")')
    L.execute('local t = WowCompanion.padTabCtl.state(); t.view = "%s"; t.set = %d; t.pos = %d; t.carry = nil' % (view, set_, pos))
    if view == "combat":
        L.execute('WowCompanion.padTabCtl.touchForm(%d)' % form_k)
    L.execute('local t = WowCompanion.padTabCtl.state(); t.focus = "buttons"; t.lock = nil; t.chooser = nil; WowCompanion.padTabCtl.sync()')


def at(L, pos):
    L.execute('WowCompanion.padTabCtl.state().pos = %d' % pos)


def swap(L, view, set_, a, b, form_k=0):
    """X on (set, a), A on (set, b) on that tab: the room's own carry and place"""
    room(L, view, set_, a, form_k)
    L.execute('CURSOR = {}')
    L.execute('pp("PAD3")')
    at(L, b)
    L.execute('pp("PAD1"); runTimers(NOW + 1)')
    return list(L.eval('CURSOR').values())


def ftn(L, slot):
    return L.eval('(FT(%d) or {}).n' % slot)


def ffn(L, form, k):
    return L.eval('(FF(%d, %d) or {}).n' % (form, k))


# ================================================================ the world: page 1's bare D-pad Left (slot 1) / Up (slot 2) / Right (3)
# Out of combat (the bar): Left = Wrath, Up = Shred, Right = Claw; In combat (the fight layer): Left = Moonfire, Up = Mangle, Right none
W = boot()
W.execute('ACTIONINFO[1] = { "spell", %d }; ACTIONINFO[2] = { "spell", %d }; ACTIONINFO[3] = { "spell", %d }' % (WRATH, SHRED, CLAW))
check(W.eval(pm + '.putCombat(1, 1, 1, 6)') is True and W.eval(pm + '.putCombat(1, 1, 2, 5)') is True,
      "0. In combat: D-pad Left = Moonfire, D-pad Up = Mangle")
check(ftn(W, 1) == "Moonfire" and ftn(W, 2) == "Mangle" and ftn(W, 3) is None, "0. the fight layer", (ftn(W, 1), ftn(W, 2), ftn(W, 3)))
W.execute('SPY = {}')

# ---------------------------------------------------------------- (1) the In combat tab: a swap of two In-combat spells
cur = swap(W, "combat", 1, 1, 2)
check(ftn(W, 1) == "Mangle" and ftn(W, 2) == "Moonfire", "(1) In combat tab, X on Left, A on Up: the In-combat spells swap",
      (ftn(W, 1), ftn(W, 2)))
check(W.eval('BARID(1)') == WRATH and W.eval('BARID(2)') == SHRED, "(1) ... the Out of combat bar is untouched (Wrath / Shred)",
      (W.eval('BARID(1)'), W.eval('BARID(2)')))
check(not any(c.startswith('pick') or c.startswith('place') for c in cur), "(1) ... no game cursor pick up / place at all", cur)
check(W.eval('HDR("wc-ft-1")') == "/cast Mangle" and W.eval('HDR("wc-ft-2")') == "/cast Moonfire",
      "(7) the router's fight texts follow the swap", (W.eval('HDR("wc-ft-1")'), W.eval('HDR("wc-ft-2")')))

# ---------------------------------------------------------------- (2) onto an empty In-combat place (D-pad Right: a bar spell, no fight)
cur = swap(W, "combat", 1, 1, 3)
check(ftn(W, 3) == "Mangle" and ftn(W, 1) is None, "(2) In combat tab, Left onto Right (no In-combat spell there): it moves",
      (ftn(W, 1), ftn(W, 3)))
check(W.eval('BARID(1)') == WRATH and W.eval('BARID(3)') == CLAW and not any(c.startswith('pick') or c.startswith('place') for c in cur),
      "(2) ... both bars stay (Wrath / Claw), no cursor call", (W.eval('BARID(1)'), W.eval('BARID(3)'), cur))

# ---------------------------------------------------------------- (3) a button with only an Out of combat spell: nothing to move there
cur = swap(W, "combat", 1, 1, 2)
check(W.eval('BARID(1)') == WRATH and W.eval('BARID(2)') == SHRED and ftn(W, 2) == "Moonfire" and ftn(W, 1) is None,
      "(3) In combat tab, X on Left (no In-combat spell): nothing moves", (W.eval('BARID(1)'), W.eval('BARID(2)'), ftn(W, 1), ftn(W, 2)))

# ---------------------------------------------------------------- (4) the Out of combat tab: the bar swaps, the fight layer stays
cur = swap(W, "normal", 1, 2, 3)
check(W.eval('BARID(2)') == CLAW and W.eval('BARID(3)') == SHRED, "(4) Out of combat tab, X on Up, A on Right: the bar spells swap",
      (W.eval('BARID(2)'), W.eval('BARID(3)')))
check(ftn(W, 2) == "Moonfire" and ftn(W, 3) == "Mangle", "(4) ... the In-combat spells stay on their buttons (Moonfire / Mangle)",
      (ftn(W, 2), ftn(W, 3)))
clean(W, "(1)-(4)")

# ---------------------------------------------------------------- (5) the In combat tab with Cat Form chosen: only Cat Form's own
check(W.eval(pm + '.putCombatForm(%d, 1, 2)' % CAT) is True and W.eval(pm + '.putCombatForm(%d, 2, 4)' % CAT) is True,
      "(5) setup: Cat Form's own D-pad Left = Shred, D-pad Up = Maul")
W.execute('SPY = {}')
cur = swap(W, "combat", 1, 1, 2, form_k=2)
check(ffn(W, CAT, 1) == "Maul" and ffn(W, CAT, 2) == "Shred", "(5) Cat Form chosen, X on Left, A on Up: Cat Form's own spells swap",
      (ffn(W, CAT, 1), ffn(W, CAT, 2)))
check(ftn(W, 1) is None and ftn(W, 2) == "Moonfire" and W.eval('BARID(1)') == WRATH and W.eval('BARID(2)') == CLAW
      and not any(c.startswith('pick') or c.startswith('place') for c in cur),
      "(5) ... the caster's In-combat spells and both bar slots untouched, no cursor call",
      (ftn(W, 1), ftn(W, 2), W.eval('BARID(1)'), W.eval('BARID(2)'), cur))
cur = swap(W, "combat", 1, 1, 3, form_k=2)
check(ffn(W, CAT, 3) == "Maul" and ffn(W, CAT, 1) is None and ftn(W, 3) == "Mangle",
      "(5) Cat Form's Left onto its empty Right: moves; the caster's Right (Mangle) stays", (ffn(W, CAT, 1), ffn(W, CAT, 3), ftn(W, 3)))
check(W.eval('HDR("wc-ff-2-3")') == "/cast Maul" and W.eval('HDR("wc-ff-2-1")') is None, "(7) the form's fight texts follow",
      (W.eval('HDR("wc-ff-2-3")'), W.eval('HDR("wc-ff-2-1")')))
clean(W, "(5)")

# ---------------------------------------------------------------- (6) the DS WoW toolbar on: no In-combat move onto its D-pad
W.execute(pm + '.putCombat(1, 3, 5, 6)')
W.execute('WowCompanionSpellsDB.dpad = { [WowCompanion.charKey()] = true }')
r = W.eval(pm + '.move(1, 3, 5, 1, 1, 4, "combat")')
check(tuple(r) == (False, "dpad bar") and ftn(W, 4) is None and ftn(W, 29) == "Moonfire",
      "(6) the toolbar on: an In-combat move onto its D-pad Down is refused (dpad bar)", (tuple(r) if r else r, ftn(W, 4), ftn(W, 29)))
clean(W, "(6)")

print("\n%d passed, %d failed" % (passes, fails))
sys.exit(1 if fails else 0)
