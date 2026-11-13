"""THE TAP-TO-CAST BAR (GitHub #43; WowCompanion/ActionBar.lua cb1 / cb2, WowCompanionSpells/Book.lua b53 / b55), off-device: the REAL TapPad and the
REAL cast-bar block of ActionBar.lua (CASTBAR-BEGIN .. CASTBAR-END) under tools/wowstub.py's stubbed game, Lua 5.1 as the game's.

    python tools/test_cast_bar.py

  a the row: "castbar", 13 secure cells (12 slots + the pick), within TapPad's ROWS_MAX; cb2 THE LAZY ROW: NOT registered at the login
    with an empty bar (the document: no pick cell, nopad, pp = padtab 39 then 19), registered by the first edit (the padtab pick,
    WC.castBarPick), a login with stored slots registers it at once; emptied later it stays (TapPad cannot give a row back);
  b an edit out of combat: the Spellbook's pick then the spell -> the first free slot, stored per character (name-realm), the cell a
    type=spell cast of that id on OUR button; the same spell again takes it off; 12 full refuses the 13th; passives / unknown refused;
  c a tap on a slot's cell casts the game's own spell (one secure action), the pick cell casts nothing;
  d in a fight: no secure call at all (the lockdown would refuse it), the edit queued, its slot dimmed (q) with no cell; after
    PLAYER_REGEN_ENABLED the queue is in the store and the cells are rebuilt;
  e another character has its own bar;
  f the cooldowns ("castcd"): the GCD / a cooldown from isActive / isOnGCD only; a secret length is never read (the learned one, else
    1.5 s for the GCD); a cooldown that ran out is gone;
  g the pick is stale after 2 s; Book hands the spell to the bar first (b53), in a full world load; Book b55: padtab 39 then 19 is the
    pick with no castbar row yet (the row then registered), 39 then another code stays Smart Range's;
  h no trace: no SetBinding / SaveBindings / macro / CVar / PlaceAction, no Blizzard window call.
Needs lupa. Exit code 1 on any failure."""
import json
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from wowstub import ADDONS, SRC, boot, boot_world, docs  # noqa: E402

AB = open(os.path.join(ADDONS, "WowCompanion", "ActionBar.lua"), encoding="utf-8").read()
BLOCK = re.search(r'-- CASTBAR-BEGIN[^\n]*\n(.*?)-- CASTBAR-END', AB, re.S).group(1)
BOOK = SRC["book"]

fails = 0
passes = 0


def check(ok, name, got=''):
    global fails, passes
    print(('PASS ' if ok else 'FAIL ') + name + ((' -> ' + str(got)) if got != '' else ''))
    if ok:
        passes += 1
    else:
        fails += 1


PRE = r'''
CD = {}                                        -- spell id -> the cooldown table C_Spell.GetSpellCooldown answers
C_Spell.GetSpellCooldown = function(id) return CD[id] or { isActive = false, isOnGCD = false } end
PASSIVE = { [9999] = true }
SPELLNAME[9999] = "Some Passive"
C_Spell.IsSpellPassive = function(id) return PASSIVE[id] == true end
for i = 1, 13 do SPELLNAME[50000 + i] = "Spell " .. i end
'''


def load_block(L):
    load = L.eval('function(src) local f, e = loadstring("local WC = _G.WowCompanion\\n" .. src, "@ActionBar.lua#castbar"); '
                  'if not f then error(e) end; return f end')
    load(BLOCK)()


def tap(L, col, combat=None):
    L.execute('local c = WowCompanion.pad.rows.castbar.cells[%d]; doClick(c, "LeftButton", true); doClick(c, "LeftButton", false); '
              'NOW = NOW + 0.3' % col)


def store(L, who="Korvash-Realm"):
    t = L.eval('(WowCompanionSpellsDB.castBar or {})["%s"]' % who)
    if t is None:
        return {}
    return {int(k): int(v) for k, v in t.items()}


def last(L, typ):
    d = docs(L, 0, typ)
    return d[-1] if d else None


def edit(L, sid, cell=False):
    """the Spellbook's route: the pick (cb2: padtab 39 + 19 -> WC.castBarPick; cell=True the row's own pick cell 12), then the spell
    (what Book hands on after the spell's two taps)"""
    if cell:
        tap(L, 12)
    else:
        L.execute('WowCompanion.castBarPick(); NOW = NOW + 0.3')
    L.execute('WowCompanion.castBarSpell(%d)' % sid if L.eval('WowCompanion.castBarWants()') else 'STALE = true')


# ================================================================ a the row
check(len(re.findall(r'local function \w+', BLOCK)) < 60 and BLOCK.strip().startswith('--'), "the block is one do ... end (its locals scoped)")
check('\ndo\n' in BLOCK and BLOCK.rstrip().endswith('end'), "the block is wrapped in do ... end")
L = boot(["safety", "tappad"], pre=PRE)
load_block(L)
L.execute('fire("PLAYER_LOGIN"); fire("PLAYER_ENTERING_WORLD", true, false); WowCompanion.pad.layout(); runTimers(NOW + 5)')
nrows0 = int(L.eval('WowCompanion.pad.nrows'))
check(L.eval('WowCompanion.pad.rows.castbar') is None, "cb2: an empty bar at the login registers NO castbar row")
d = last(L, "castbar")
check(d is not None and d.get("pick") is None and d.get("nopad") == 1 and d.get("pp") == [39, 19] and d.get("e") in ([], {})
      and d.get("v") == "cb2", "the first document: an empty bar, no pick cell (nopad), the padtab pick 39 then 19", d)
check(L.eval('WowCompanion.pad.describe().rows.castbar') is None, "describe() has no castbar row yet")
L.execute('WowCompanion.castBar.sync(); fire("PLAYER_REGEN_ENABLED"); runTimers(NOW + 5)')
check(L.eval('WowCompanion.pad.rows.castbar') is None, "a sync / fight's end with an empty bar: still no row")

# ================================================================ b edits out of combat
edit(L, 403)
row = L.eval('WowCompanion.pad.rows.castbar')
check(row is not None and bool(row.secure) and int(row.n) == 13, "cb2: the first edit registers the row 'castbar': 13 secure cells", row and (row.secure, row.n))
check(row is not None and int(row.row) < int(L.eval('WowCompanion.pad.nrows')) <= 56 and int(L.eval('WowCompanion.pad.nrows')) == nrows0 + 1,
      "within ROWS_MAX (56), one row more than before")
desc = L.eval('WowCompanion.pad.describe()')
check(desc['rows']['castbar'] is not None, "describe() publishes the castbar row (the app's TapBridge finds it)")
check(L.eval('WowCompanion.pad.rows.castbar.cells[1].a["type"]') is None, "an empty slot's cell has no action")
check(store(L) == {1: 403}, "the pick then Lightning Bolt: slot 1, the character's store (name-realm)", store(L))
check(L.eval('WowCompanion.pad.rows.castbar.cells[0].a["type"]') == "spell"
      and L.eval('WowCompanion.pad.rows.castbar.cells[0].a["spell"]') == 403, "slot 1's cell: type spell, the spell's id")
d = last(L, "castbar")
check(d["e"] == [{"s": 1, "id": 403, "i": 130403, "c": 0}] and d.get("pick") == 12 and d.get("nopad") is None,
      "the document: slot 1, its icon and its cell; the row's pick cell 12", d)
a = last(L, "castbarack")
check(a and a["ok"] is True and a["op"] == "add" and a["s"] == 1, "answered: added on slot 1", a)
edit(L, 331, cell=True)
edit(L, 324)
check(store(L) == {1: 403, 2: 331, 3: 324}, "two more (one by the row's own pick cell 12): slots 2 and 3", store(L))
edit(L, 331)
check(store(L) == {1: 403, 3: 324}, "Healing Wave again: taken off (the slot left empty, the others stay)", store(L))
check(L.eval('WowCompanion.pad.rows.castbar.cells[1].a["type"]') is None, "its cell has no action any more")
edit(L, 331)
check(store(L) == {1: 403, 2: 331, 3: 324}, "and on again: the first free slot", store(L))
for i in range(4, 13):
    edit(L, 50000 + i)
check(len(store(L)) == 12, "12 slots full", len(store(L)))
edit(L, 50013)
a = last(L, "castbarack")
check(a["ok"] is False and a["why"] == "full" and 50013 not in store(L).values(), "a 13th: refused (full)", a)
edit(L, 9999)
check(last(L, "castbarack")["why"] == "passive", "a passive: refused (spells you cast only)")
edit(L, 123456)
check(last(L, "castbarack")["why"] == "spell", "an unknown id: refused")

# ================================================================ c taps
L.execute('ACTIONS = {}')
tap(L, 0)
acts = [dict(x) for x in L.eval('ACTIONS').values()]
check(len(acts) == 1 and acts[0]["kind"] == "spell" and acts[0]["spell"] == 403, "a tap on slot 1: one cast of Lightning Bolt", acts)
L.execute('ACTIONS = {}')
tap(L, 12)
check(len(L.eval('ACTIONS')) == 0 and L.eval('WowCompanion.castBarWants()') is True, "the pick cell: no game action, the pick armed")
L.execute('NOW = NOW + 2.5')
check(L.eval('WowCompanion.castBarWants()') is False, "the pick is stale after 2 s")

# ================================================================ d in a fight
L.execute('COMBAT = true; VIOL = {}; fire("PLAYER_REGEN_DISABLED")')
edit(L, 331)                                       # off slot 2, queued
a = last(L, "castbarack")
check(a["ok"] is True and a["op"] == "remove" and a["q"] == 1 and a["s"] == 2, "in a fight: Healing Wave off, queued", a)
check(store(L).get(2) == 331, "the store unchanged until the fight ends", store(L))
d = last(L, "castbar")
s2 = [e for e in d["e"] if e["s"] == 2]
check(s2 == [{"s": 2, "q": 1}] and d.get("fight") is True, "its slot dimmed (q), no cell to tap", s2)
check(all("c" in e for e in d["e"] if e["s"] != 2), "the other slots keep their cells (frozen, still casting)")
tap(L, 0)
check(len(L.eval('VIOL')) == 0, "no secure call in the fight (the lockdown refuses none)", list(L.eval('VIOL').values()))
L.execute('COMBAT = false; fire("PLAYER_REGEN_ENABLED")')
check(2 not in store(L) and L.eval('WowCompanion.pad.rows.castbar.cells[1].a["type"]') is None,
      "after the fight: the queue in the store, the cell rebuilt", store(L))
d = last(L, "castbar")
check(not any(e.get("q") for e in d["e"]) and d.get("fight") is None, "nothing dimmed any more", d["e"])
# a full bar in a fight: an add is refused at once (nothing queued that cannot apply)
L.execute('COMBAT = true')
edit(L, 331)
edit(L, 50013)
a = last(L, "castbarack")
check(a["ok"] is False and a["why"] == "full", "in a fight: the queued add fills slot 2, a further add is refused", a)
L.execute('COMBAT = false; fire("PLAYER_REGEN_ENABLED")')
check(store(L).get(2) == 331, "the queued add applied after the fight", store(L))

# ================================================================ e per character
L.execute('WHO = "Alt"; fire("PLAYER_ENTERING_WORLD", true, false)')
d = last(L, "castbar")
check(d["e"] == [], "another character: its own (empty) bar", d["e"])
edit(L, 324)
check(store(L, "Alt-Realm") == {1: 324} and store(L).get(3) == 324, "its edit goes to its own store", store(L, "Alt-Realm"))
L.execute('WHO = "Korvash"; fire("PLAYER_ENTERING_WORLD", true, false)')
check(len(last(L, "castbar")["e"]) == 12, "back: the first character's 12")
# cb2: a bar emptied keeps its row (TapPad has no release), every cell without action
E = boot(["safety", "tappad"], pre=PRE)
load_block(E)
E.execute('fire("PLAYER_LOGIN"); fire("PLAYER_ENTERING_WORLD", true, false); WowCompanion.pad.layout(); runTimers(NOW + 5)')
edit(E, 403)
edit(E, 403)
check(store(E) == {} and E.eval('WowCompanion.pad.rows.castbar') is not None and E.eval('WowCompanion.pad.rows.castbar.cells[0].a["type"]') is None,
      "cb2: emptied again: the row stays (until a reload), its cells have no action", store(E))
d = last(E, "castbar")
check(d.get("pick") == 12 and d["e"] == [], "its document: the row's pick cell, no slots", d)
# cb2: a login with stored slots registers the row at once
R = boot(["safety", "tappad"], pre=PRE + '\nWowCompanionSpellsDB = { castBar = { ["Korvash-Realm"] = { [1] = 403 } } }\n')
load_block(R)
R.execute('fire("PLAYER_LOGIN"); fire("PLAYER_ENTERING_WORLD", true, false); WowCompanion.pad.layout(); runTimers(NOW + 5)')
check(R.eval('WowCompanion.pad.rows.castbar') is not None and R.eval('WowCompanion.pad.rows.castbar.cells[0].a["spell"]') == 403,
      "cb2: a login with a stored spell: the row at once, slot 1 casting it")

# ================================================================ f cooldowns
L.execute('CD[403] = { isActive = true, isOnGCD = true, duration = 1.5, startTime = NOW }; fire("SPELL_UPDATE_COOLDOWN")')
c = last(L, "castcd")
check(c and c["k"]["1"]["g"] is True and abs(c["k"]["1"]["d"] - 1.5) < 1e-6 and "e" not in c["k"]["1"], "out of a fight: the GCD, its plain length", c)
L.execute('NOW = NOW + 2; CD[403] = { isActive = false, isOnGCD = false }; fire("SPELL_UPDATE_COOLDOWN")')
c = last(L, "castcd")
check(c.get("k") is None, "ran out: nothing running", c)
L.execute('CD[331] = { isActive = true, isOnGCD = false, duration = 8, startTime = NOW }; fire("SPELL_UPDATE_COOLDOWN")')
L.execute('NOW = NOW + 9; CD[331] = { isActive = false }; fire("SPELL_UPDATE_COOLDOWN")')
L.execute('COMBAT = true; CD[403] = { isActive = true, isOnGCD = true, duration = SECRETV, startTime = SECRETV }; '
          'CD[331] = { isActive = true, isOnGCD = false, duration = SECRETV, startTime = SECRETV }; fire("SPELL_UPDATE_COOLDOWN")')
c = last(L, "castcd")
check(c["k"]["1"]["e"] is True and abs(c["k"]["1"]["d"] - 1.5) < 1e-6, "in a fight: the GCD's length is the learned one, marked estimated", c)
check(c["k"]["2"]["e"] is True and abs(c["k"]["2"]["d"] - 8) < 1e-6, "a secret cooldown: the length learned out of the fight (8 s)", c)
check(len(L.eval('ERRS')) == 0, "no error (a secret value was never compared)", list(L.eval('ERRS').values()))
L.execute('CD[324] = { isActive = true, isOnGCD = false, duration = SECRETV, startTime = SECRETV }; fire("SPELL_UPDATE_COOLDOWN")')
c = last(L, "castcd")
check("3" not in c["k"], "a secret cooldown never learned: nothing drawn rather than a guess", c)
L.execute('COMBAT = false')

# ================================================================ g Book hands the spell over (b53), in a full world load
check(BOOK.index('WC.castBarWants()') < BOOK.index('WC.rankUpWants()') < BOOK.index('WC.smartYWants()'),
      "Book b53: the cast bar's pick is asked first, before rank up and Smart buffs")
W, errs = boot_world(pre=PRE)
check(not errs, "the world loads", errs)
load_block(W)
W.execute('fire("PLAYER_LOGIN"); fire("PLAYER_ENTERING_WORLD", true, false); WowCompanion.pad.layout(); runTimers(NOW + 5)')
check(W.eval('WowCompanion.pad.rows.castbar') is None, "the world: an empty bar, no castbar row")
W.execute('WowCompanion.pad.rows["padtab"].handler(nil, 39); WowCompanion.pad.rows["padtab"].handler(nil, 19); NOW = NOW + 0.3')
check(W.eval('WowCompanion.castBarWants()') is True and W.eval('WowCompanion.padTabCtl.srWants()') is False,
      "Book b55: padtab 39 then 19 = the cast bar's pick (Smart Range's pick spent)")
W.execute('WowCompanion._spellsBookKey(403)')
check(store(W) == {1: 403}, "the pick, then the spells row's tap for Lightning Bolt: on the cast bar (not a Bind)", store(W))
check(W.eval('WowCompanion.pad.rows.castbar') is not None, "the castbar row registered by that first spell")
W.execute('NOW = NOW + 3; WowCompanion.pad.rows["padtab"].handler(nil, 39); WowCompanion.pad.rows["padtab"].handler(nil, 3); NOW = NOW + 0.3')
check(W.eval('WowCompanion.castBarWants()') is False, "39 then another code: not the cast bar's pick")
W.execute('NOW = NOW + 3; local c = WowCompanion.pad.rows.castbar.cells[12]; doClick(c, "LeftButton", true); doClick(c, "LeftButton", false); NOW = NOW + 0.3')
W.execute('WowCompanion._spellsBookKey(331)')
check(store(W) == {1: 403, 2: 331}, "the row's own pick cell 12 still works once the row exists", store(W))
check(W.eval('WowCompanion._spellsBook.ctl.mode') != "bind", "no Bind box opened")
W.execute('NOW = NOW + 3; WowCompanion._spellsBookKey(403)')
check(store(W) == {1: 403, 2: 331}, "a later tap with no fresh pick: the book's own (the bar untouched)", store(W))

# ================================================================ h no trace
spy = [x for x in L.eval('SPY').values()]
check(spy == [], "no trace: no SetBinding / SaveBindings / macro / CVar / PlaceAction / window call", spy)
check(not re.search(r'\b(SetBinding|SaveBindings|CreateMacro|EditMacro|SetCVar|PlaceAction|PickupSpell|ShowUIPanel|HideUIPanel)\s*\(', BLOCK),
      "the block calls none of them")
check(AB.count('pad.secureRow, "castbar"') == 1 and AB.replace(BLOCK, '').count('secureRow, "castbar"') == 0,
      "the row is built in the block only")
CL = boot([])
check(CL.eval('function(s) local f, e = loadstring(s, "ActionBar.lua"); return e end')(AB) is None,
      "ActionBar.lua compiles as Lua 5.1 (200 locals, 60 upvalues)")

print("\n%d passed, %d failed" % (passes, fails))
sys.exit(1 if fails else 0)
