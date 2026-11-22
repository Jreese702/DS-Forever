"""CONTROLLER | TAP TO CAST (the user, 2026-11-19, the approved proposal D1-D10; ActionBar cb14, PadTabCtl p55, Book b70; the room's
navigation is Option C's since PadTabCtl p56 - tools/test_controller_situations.py has the rest), off-device:
the REAL add-on files (the cast bar block of ActionBar.lua, Book, PadMap, PadTabCtl) under the stubbed game.

    python tools/test_controller_tap_tabs.py

Proven here:
  a versions + notes; the cast bar block writes nothing of the player's (no binding, macro, bar slot or CVar);
  b THE CELLS: the castbar row grows from 9 to 17 cells (8 slots, the pick, the 8 fight cells), within TapPad's 40 columns; each slot
    cell and each fight cell carries both things ("wc-n-*" / "wc-f-*"), a fight cell's own attributes are the fight thing; the document's
    `f` (the fight slots, their cells 9-16) and `show`;
  c THE SWAP IN A FIGHT (D10, secure): out of combat a slot's cell AND its fight cell cast the out-of-combat thing; in a fight both cast
    the fight thing (the pre-click snippet of our own header, [combat] read at the press), an empty fight slot keeps the slot's own
    thing, a fight slot over an empty slot works in fights only; no protected call from insecure code in the fight, no snippet error;
  d SHOW OFF HIDES IN FIGHTS (D5): padtab 39 then 3 disarms the fight set (the slots cast their own in fights), a fight-layer put is
    refused "cb-hidden", the fight slots are KEPT (store and document) and come back armed with 39 then 2;
  e THE OVERRIDES: a fight-layer put / clear / move (CB.put / clear / move with "f"), Clear gives the slot's own thing back in fights;
    an edit made in a fight waits for its end (queued per layer);
  f THE ROOM (D1 / D9 rules): the top tabs are WoW Defaults / In combat / Spell radial / Party mode; Down from the top tabs lands on the
    sub-tabs (WoW Defaults) or the check boxes then the sub-tabs (In combat); landing on a sub-tab opens it; LB / RB step the top tabs
    only and each top tab remembers its sub-tab while the room is open (a fresh open: Controller); B one level back (chooser -> page ->
    sub-tabs -> top tabs -> Home);
  g In combat's check boxes: Combat controls, Start attack on cast, Show tap-to-cast (A counts `cbsn` and flips the add-on's copy);
  h In combat > Tap to cast: Show off = locked (A / X one red pulse, "cb-hidden", no chooser, no touch put); Show on = the chooser puts on
    the fight layer, X / A moves within it; the bar Off = A "Turn on" (`cbon`), padtab 39 then 1 opens the page by touch;
  i THE BIND BOX's four tabs (Book b70): 39 then 26 = Out of combat + Tap to cast, 39 then 4 = In combat + Tap to cast (`bcf`), its A /
    the slot pick put on the fight layer; Show off = refused with one red pulse, the box waits; bf-on / bf-off leave it; a bag item's box
    gets the Tap to cast tabs too (D8: on the slot as an item, its own slot again = off it); the bar Off = A counts `cbon`;
plus no spied call (no SetBinding, SaveBindings, macro, CVar or bar write). Needs lupa. Exit code 1 on any failure."""
import json
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
AB = open(os.path.join(ADDONS, "WowCompanion", "ActionBar.lua"), encoding="utf-8").read()
BLOCK = re.search(r'-- CASTBAR-BEGIN[^\n]*\n(.*?)-- CASTBAR-END', AB, re.S).group(1)
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from wowstub import GAME  # noqa: E402
PARTY = open(os.path.join(ROOT, "tools", "test_party.py"), encoding="utf-8").read()
WORLD_HELPERS = re.search(r"\nWORLD_HELPERS = r'''(.*?)'''", PARTY, re.S).group(1)
STICKS = open(os.path.join(ROOT, "tools", "test_stick_binds.py"), encoding="utf-8").read()
EXTRA = re.search(r"\nEXTRA = r'''(.*?)'''", STICKS, re.S).group(1)
AFTER = re.search(r"\nAFTER = r'''(.*?)'''", STICKS, re.S).group(1)
MORE = r'''
C_MountJournal = { GetMountInfoByID = function(id) if id == 6 then return "Brown Horse", 458, 132261 end end,
                   GetNumDisplayedMounts = function() return 0 end }
SPELLNAME[458] = "Brown Horse"
ITEMCOUNT = { [6948] = 1, [118] = 7 }
C_Item.GetItemNameByID = function(id) if id == 6948 then return "Hearthstone" elseif id == 118 then return "Minor Healing Potion" end end
C_Item.GetItemCount = function(id) return ITEMCOUNT[id] or 0 end
C_Item.IsConsumableItem = function(id) return id == 118 end
'''
fails = 0
passes = 0


def check(ok, name, got=''):
    global fails, passes
    print(('PASS ' if ok else 'FAIL ') + name + ((' -> ' + str(got)) if got != '' else ''))
    if ok:
        passes += 1
    else:
        fails += 1


def boot(pre=''):
    L = lupa.LuaRuntime(unpack_returned_tuples=True)
    L.execute(GAME)
    L.execute(EXTRA)
    L.execute(MORE)
    if pre:
        L.execute(pre)
    load = L.eval('function(src, name) local f, e = loadstring(src, name); if not f then error(e) end; return f end')
    for k in ("safety", "tappad", "book", "padmap", "padtabctl"):
        load(SRC[k], '@' + os.path.basename(F[k]))(os.path.basename(F[k]))
    load("local WC = _G.WowCompanion\n" + BLOCK, "@ActionBar.lua#castbar")()
    L.execute(WORLD_HELPERS)
    L.execute('fire("PLAYER_LOGIN"); fire("PLAYER_ENTERING_WORLD", true, false); WowCompanion.pad.layout(); runTimers(NOW + 10)')
    L.execute(AFTER)
    L.execute('WowCompanion.padMap.emit(true); runTimers(NOW + 1)')
    # the red pulse (capture v8's helper) counted, still run
    L.execute('REFN = 0; local orig = WowCompanion.spellsRefusedCombat; '
              'WowCompanion.spellsRefusedCombat = function(...) REFN = REFN + 1; if orig then return orig(...) end end')
    return L


def docs(L, typ):
    out = []
    for i in range(1, int(L.eval('#SENT')) + 1):
        try:
            d = json.loads(L.eval('SENT[%d]' % i))
        except Exception:
            continue
        if isinstance(d, dict) and d.get('type') == typ:
            out.append(d)
    return out


def ctl(L):
    d = docs(L, 'padtabctl')
    return d[-1]['c'] if d else {}


def sctl(L):
    d = docs(L, 'spellsctl')
    return d[-1]['ctl'] if d else {}


def bar(L):
    L.execute('WowCompanion.castBar.send(true)')
    return docs(L, 'castbar')[-1]


def store(L, field="castBar"):
    t = L.eval('(WowCompanionSpellsDB.%s or {})[WHO .. "-" .. REALM]' % field)
    out = {}
    if t is None:
        return out
    for k, v in t.items():
        if isinstance(v, (int, float)):
            out[int(k)] = ('num', int(v))
        else:
            out[int(k)] = (v['k'], v['key'] if v['k'] == 'command' else (v['n'] if v['k'] == 'macro' else int(v['id'])))
    return out


def fstore(L):
    return store(L, "castBarFight")


def cell(L, col, attr):
    return L.eval('(function() local r = WowCompanion.pad.rows.castbar; local c = r and r.cells[%d]; return c and c.a["%s"] end)()'
                  % (col, attr))


def padtab(L, *cols):
    for c in cols:
        L.execute('WowCompanion.pad.rows["padtab"].handler(nil, %d); NOW = NOW + 0.1' % c)


def tapcell(L, col):
    """a tap on castbar cell [col] (both edges, a hardware click); the action it ran, or None"""
    L.execute('local n0 = #ACTIONS; local c = WowCompanion.pad.rows.castbar.cells[%d]; doClick(c, "LeftButton", true); '
              'doClick(c, "LeftButton", false); RAN = (#ACTIONS > n0) and ACTIONS[n0 + 1] or false; NOW = NOW + 0.3' % col)
    if L.eval('RAN') is False:                      # (ACTIONS answers a stub for a missing index: count instead)
        return None
    return (L.eval('RAN.kind'), L.eval('RAN.spell') or L.eval('RAN.item') or L.eval('RAN.line'))


def clean(L, name):
    check(L.eval('#SPY') == 0 and L.eval('#VIOL') == 0 and L.eval('#SNIPERR') == 0 and list(L.eval('ERRS').values()) == [],
          name + ": no spied call (no trace), no lockdown breach, no snippet error",
          (L.eval('SPY[1]'), L.eval('VIOL[1]'), L.eval('SNIPERR[1]'), list(L.eval('ERRS').values())[:2]))


def press(L, *keys):
    for k in keys:
        L.execute('pp("%s")' % k)


# ================================================================ a. versions, no trace in the code
check('local CBV = "cb16"' in BLOCK and "cb14 THE IN-COMBAT LAYER" in AB, "a. ActionBar cb16 (cb14's in-combat layer note kept)")
check('local CV = "p58"' in SRC["padtabctl"] and "p55 CONTROLLER | TAP TO CAST" in SRC["padtabctl"], "a. PadTabCtl p56 (p55's note kept)")
check('local BV = "b76"' in SRC["book"] and "b70 CONTROLLER | TAP TO CAST" in SRC["book"], "a. Book b71 (b70's note kept)")
code = "\n".join(l.split("--", 1)[0] for l in BLOCK.splitlines())
check(not re.search(r'(?<![\w.:])SetBinding\s*\(', code) and 'SaveBindings' not in code and 'CreateMacro' not in code
      and 'EditMacro' not in code and 'PlaceAction' not in code and 'SetCVar' not in code and 'PickupAction' not in code
      and 'SetOverrideBinding' not in code, "a. the cast bar code writes nothing of the player's (no binding, macro, bar slot or CVar)")
check('SecureCmdOptionParse("[combat] 1")' in BLOCK and 'SecureHandlerWrapScript, btn, "OnClick"' in BLOCK,
      "a. the fight swap is a pre-click snippet reading [combat] at the press (our own header)")

# ================================================================ b. the cells
W = boot()
check(list(W.eval('ERRS').values()) == [], "b. the world loads", list(W.eval('ERRS').values())[:2])
padtab(W, 39, 30, 39, 2)                                       # the app: the bar on, Show in combat on
check(W.eval('WowCompanionSpellsDB.castBarShow') is True and W.eval('WowCompanion.castBar.show()') is True,
      "b. padtab 39 then 2: Show in combat on (kept, castBarShow)")
W.execute('WowCompanion.castBar.put(1, "spell", 403)')                   # slot 1: Lightning Bolt
W.execute('WowCompanion.castBar.put(1, "spell", 331, "f")')              # fight slot 1: Healing Wave
W.execute('WowCompanion.castBar.put(2, "command", "TARGETNEARESTENEMY", "f")')   # fight slot 2 over an empty slot 2
W.execute('WowCompanion.castBar.put(3, "item", 118)')                    # slot 3: a potion, no fight thing
check(store(W) == {1: ('spell', 403), 3: ('item', 118)} and fstore(W) == {1: ('spell', 331), 2: ('command', 'TARGETNEARESTENEMY')},
      "b. the two stores: castBar and castBarFight (per character)", (store(W), fstore(W)))
row = W.eval('WowCompanion.pad.rows.castbar')
check(row is not None and int(row.n) == 25 and int(row.n) <= 40, "b. the castbar row: 25 secure cells (cb15: 8 + the pick + 8 + Party mode's 8), within 40",
      row and row.n)
check(cell(W, 0, "type") == "spell" and cell(W, 0, "spell") == 403 and cell(W, 0, "wc-n-spell") == 403 and cell(W, 0, "wc-f-spell") == 331,
      "b. slot 1's cell: its own spell, both sets as attributes", (cell(W, 0, "spell"), cell(W, 0, "wc-f-spell")))
check(cell(W, 9, "type") == "spell" and cell(W, 9, "spell") == 331 and cell(W, 9, "wc-n-spell") == 403,
      "b. fight slot 1's cell (9): the fight spell as its own, the slot's as the out-of-combat set", (cell(W, 9, "spell"), cell(W, 9, "wc-n-spell")))
check(cell(W, 1, "type") is None and cell(W, 1, "wc-f-type") == "macro" and cell(W, 10, "type") == "macro"
      and cell(W, 10, "macrotext") == "/targetenemy", "b. slot 2: no action of its own, its fight cell (10) the command's line",
      (cell(W, 1, "type"), cell(W, 10, "macrotext")))
check(cell(W, 8, "type") is None and cell(W, 8, "wc-n-type") is None, "b. the pick cell (8) stays a plain no-action cell")
check(cell(W, 11, "type") is None, "b. an unset fight slot's cell (11) has no action")
d = bar(W)
fe = {x['s']: x for x in d.get('f', [])}
check(d.get('v') == 'cb16' and d.get('show') == 1 and d.get('fl') == 1 and fe.get(1, {}).get('id') == 331 and fe[1].get('c') == 9
      and fe.get(2, {}).get('k') == 'command' and fe[2].get('c') == 10, "b. the document: `f` with cells 9 / 10, show = 1", d.get('f'))
check(W.eval('WowCompanion.pad.describe().rows.castbar') is not None, "b. described (the app's TapBridge finds the row)")
clean(W, "b")

# ================================================================ c. the swap in a fight (secure)
check(tapcell(W, 0) == ("spell", 403) and tapcell(W, 9) == ("spell", 403), "c. out of combat: slot 1's cell and its fight cell cast slot 1's own",
      (tapcell(W, 0), tapcell(W, 9)))
check(tapcell(W, 1) is None and tapcell(W, 10) is None, "c. out of combat: slot 2 (empty) does nothing, its fight cell neither")
W.execute('fightOn()')
check(tapcell(W, 0) == ("spell", 331) and tapcell(W, 9) == ("spell", 331), "c. in a fight: both cast the fight spell (Healing Wave)",
      (tapcell(W, 0), tapcell(W, 9)))
check(tapcell(W, 10) == ("macroline", "/targetenemy") and tapcell(W, 1) == ("macroline", "/targetenemy"),
      "c. in a fight: fight slot 2 over an empty slot runs its command")
check(tapcell(W, 2) == ("item", "item:118"), "c. in a fight: slot 3 (no fight thing) keeps its own (the potion)")
check(W.eval('#VIOL') == 0 and W.eval('#SNIPERR') == 0, "c. no protected call from insecure code in the fight, no snippet error",
      (W.eval('VIOL[1]'), W.eval('SNIPERR[1]')))
W.execute('fightOff()')
check(tapcell(W, 0) == ("spell", 403) and tapcell(W, 9) == ("spell", 403), "c. after the fight: slot 1's own again")
clean(W, "c")

# ================================================================ d. Show off hides in fights (D5)
padtab(W, 39, 3)
check(W.eval('WowCompanionSpellsDB.castBarShow') is None, "d. padtab 39 then 3: Show in combat off")
check(cell(W, 0, "wc-f-type") is None and cell(W, 9, "type") is None and cell(W, 10, "type") is None,
      "d. the fight set disarmed (no fight attribute, the fight cells no action)", (cell(W, 0, "wc-f-type"), cell(W, 9, "type")))
d = bar(W)
fe = {x['s']: x for x in d.get('f', [])}
check(d.get('show') is None and fe.get(1, {}).get('id') == 331 and 'c' not in fe[1] and fstore(W) == {1: ('spell', 331), 2: ('command', 'TARGETNEARESTENEMY')},
      "d. the fight slots KEPT (store and document), no cells meanwhile", (d.get('show'), d.get('f')))
W.execute('fightOn()')
check(tapcell(W, 0) == ("spell", 403) and tapcell(W, 1) is None, "d. in a fight with Show off: slot 1 casts its own, slot 2 nothing")
W.execute('fightOff()')
W.execute('WowCompanion.castBar.put(4, "spell", 324, "f")')
a = docs(W, 'castbarack')[-1]
check(a.get('ok') is False and a.get('why') == 'cb-hidden' and a.get('f') == 1 and 4 not in fstore(W),
      "d. a fight-layer put with Show off: refused cb-hidden, nothing stored", a)
W.execute('WowCompanion.castBar.clear(1, "f")')
check(fstore(W).get(1) == ('spell', 331), "d. ... its Clear too (the slots are kept, not edited)")
padtab(W, 39, 2)
check(cell(W, 0, "wc-f-spell") == 331 and cell(W, 9, "spell") == 331, "d. 39 then 2: Show on again, the kept fight slots armed again")
clean(W, "d")

# ================================================================ e. the overrides (put / clear / move; a fight's queue)
W.execute('WowCompanion.castBar.move(1, 4, "f")')
check(fstore(W) == {4: ('spell', 331), 2: ('command', 'TARGETNEARESTENEMY')} and store(W).get(1) == ('spell', 403),
      "e. a fight-layer move: fight slot 1 -> 4, the bar's slot 1 untouched", (fstore(W), store(W)))
W.execute('fightOn()')
check(tapcell(W, 0) == ("spell", 403), "e. in a fight slot 1 is its own again (its fight slot empty: the out-of-combat thing)")
W.execute('WowCompanion.castBar.put(1, "spell", 324, "f")')
d = bar(W)
fe = {x['s']: x for x in d.get('f', [])}
check(fstore(W).get(1) is None and fe.get(1, {}).get('q') == 1, "e. a fight-layer put in a fight waits (dimmed, the store unchanged)", fe.get(1))
W.execute('fightOff()')
check(fstore(W).get(1) == ('spell', 324) and cell(W, 0, "wc-f-spell") == 324 and store(W).get(1) == ('spell', 403),
      "e. the fight's end: it lands on the fight layer only", (fstore(W), store(W)))
W.execute('WowCompanion.castBar.clear(1, "f")')
check(fstore(W).get(1) is None and cell(W, 0, "wc-f-type") is None, "e. Clear: the fight slot empty, slot 1 its own in fights again")
clean(W, "e")

# ================================================================ f. the room (PadTabCtl p56: situations first, no sub-tabs)
R = boot()
padtab(R, 39, 30, 39, 2)
R.execute('WowCompanion.padTabCtl.enter("tabs")')
c = ctl(R)
check(c.get('view') == 'normal' and c.get('focus') == 'tabs' and c.get('top') == 'normal', "f. the room opens on Out of combat, the cursor on the tabs", c)
seen = []
for _ in range(4):
    press(R, "PADRSHOULDER")
    seen.append(ctl(R).get('view'))
check('castbar' not in seen and 'cbfight' not in seen and seen[0] == 'combat' and seen[-1] == 'radial',
      "f. RB walks the situations only (no Cast bar tab, D1), Spell radial last", seen)
R.execute('WowCompanion.padTabCtl.enter("tabs")')
press(R, "PADDDOWN")
check(ctl(R).get('focus') == 'switches' and ctl(R).get('view') == 'normal', "f. Down from the tabs: the chips (no sub-tabs)", ctl(R))
press(R, "PADDDOWN", "PADDDOWN")
check(ctl(R).get('focus') == 'buttons', "f. then the controller's buttons", ctl(R).get('focus'))
R.execute('WowCompanion.padTabCtl.touchPos(9)')
press(R, "PADDDOWN")
check(ctl(R).get('view') == 'castbar' and ctl(R).get('focus') == 'buttons', "f. Down off the controller: the bar's 8 slots (castbar)")
press(R, "PAD1")
check(ctl(R).get('chooser') is not None, "f. A: the chooser")
press(R, "PAD2")
check(ctl(R).get('chooser') is None and ctl(R).get('focus') == 'buttons', "f. B: chooser -> the page")
press(R, "PAD2")
check(ctl(R).get('focus') == 'tabs', "f. B: the page -> the tabs")
check(R.eval('WowCompanion.padTabCtl.press("PAD2")') is False, "f. B on the tabs: not the room's (Home)")
clean(R, "f")

# ================================================================ g. In combat's check boxes (the chips)
R.execute('WowCompanion.padTabCtl.touchTab(3)')                 # In combat by touch
press(R, "PAD2")
press(R, "PADDDOWN")
c = ctl(R)
check(c.get('view') == 'combat' and c.get('focus') == 'switches' and c.get('sw') == 1, "g. In combat: Down from the tabs = the chips", c)
press(R, "PADDRIGHT", "PADDRIGHT", "PADDRIGHT")
check(ctl(R).get('sw') == 3, "g. three chips: Combat controls, Start attack on cast, Show tap-to-cast", ctl(R).get('sw'))
n0 = ctl(R).get('cbsn')
press(R, "PAD1")
c = ctl(R)
check(c.get('cbsn') == n0 + 1 and c.get('note') == 'switch-cbshow' and R.eval('WowCompanion.castBar.show()') is False and c.get('cbs') is None,
      "g. A on Show tap-to-cast: counted (cbsn, the app flips castBarCombat), the add-on's copy off at once", c)
clean(R, "g")

# ================================================================ h. In combat's bar: locked while Show is off
R.execute('WowCompanion.padTabCtl.touchPos(4)')
press(R, "PADDDOWN")
c = ctl(R)
check(c.get('view') == 'cbfight' and c.get('focus') == 'buttons', "h. down off In combat's controller: the fight layer's 8 slots", c)
r0 = R.eval('REFN')
press(R, "PAD1")
c = ctl(R)
check(c.get('chooser') is None and c.get('note') == 'cb-hidden' and R.eval('REFN') == r0 + 1,
      "h. Show off: A = one red pulse and cb-hidden, no chooser", (c.get('note'), R.eval('REFN') - r0))
press(R, "PAD3")
check(ctl(R).get('carry') is None and ctl(R).get('note') == 'cb-hidden', "h. X too: nothing carried")
R.execute('WowCompanion.padTabCtl.touchPos(2); WowCompanion.padTabCtl.touchSpell(331)')
check(fstore(R) == {} and ctl(R).get('note') == 'cb-hidden', "h. a touch put: refused (cb-hidden), nothing stored", fstore(R))
padtab(R, 39, 2)
press(R, "PAD1")
check(ctl(R).get('chooser') is not None, "h. Show on (39 then 2): A opens the chooser")
press(R, "PAD1")
check(fstore(R).get(2) == ('spell', 403) and store(R).get(2) is None, "h. a pick goes on the FIGHT layer's slot 2 only", (fstore(R), store(R)))
R.execute('WowCompanion.padTabCtl.touchPos(5); WowCompanion.padTabCtl.touchSpell(331)')
check(fstore(R).get(5) == ('spell', 331), "h. by touch too (button code 5, the spell's taps)", fstore(R))
R.execute('WowCompanion.padTabCtl.touchPos(5)')
press(R, "PAD3", "PADDRIGHT", "PAD1")
check(fstore(R).get(6) == ('spell', 331) and fstore(R).get(5) is None and ctl(R).get('note') == 'moved', "h. X then A: moved within the fight layer", fstore(R))
R.execute('WowCompanion.padTabCtl.touchPos(6); WowCompanion.padTabCtl.touchClear()')
check(fstore(R).get(6) is None, "h. Clear by touch: the fight slot empty")
padtab(R, 39, 31)
c = ctl(R)
check(c.get('cboff') == 1 and c.get('view') == 'cbfight', "h. the bar Off: the page stays (dimmed)", c)
n0 = c.get('cbon')
press(R, "PAD1")
check(ctl(R).get('cbon') == n0 + 1 and ctl(R).get('note') == 'cb-turn-on' and ctl(R).get('chooser') is None,
      "h. the bar Off: A = Turn on (cbon counted), nothing else")
padtab(R, 39, 30)
R.execute('WowCompanion.padTabCtl.touchTab(1)')
R.execute('WowCompanion._spellsBook.ctl.croom = true')
padtab(R, 39, 1)
check(ctl(R).get('view') == 'cbfight', "h. padtab 39 then 1: In combat's bar by touch", ctl(R).get('view'))
clean(R, "h")

# ================================================================ i. the Bind box's four tabs (Book b70)
B = boot()
padtab(B, 39, 30, 39, 2)
PT = 'WowCompanion.pad.rows["padtab"].handler(nil, %d); '
B.execute('BK = WowCompanion._spellsBook; BK.ctl.on = true; BK.ctl.mode = "bind"; BK.ctl.bind = { id = 403 }')
B.execute(PT % 39 + PT % 4 + 'NOW = NOW + 0.3')
c = sctl(B)
check(B.eval('BK.ctl.bind.cb') is True and B.eval('BK.ctl.bind.cbf') is True and c.get('bcb') == 1 and c.get('bcf') == 1,
      "i. padtab 39 then 4: In combat + Tap to cast (bcb, bcf)", c)
B.execute('BK.press("PADDRIGHT"); BK.press("PAD1")')
check(fstore(B).get(2) == ('spell', 403) and store(B) == {} and B.eval('BK.ctl.mode') == "book",
      "i. its A: the spell on the FIGHT layer's slot 2, the box closed", (fstore(B), store(B)))
B.execute('BK.ctl.mode = "bind"; BK.ctl.bind = { id = 331 }; NOW = NOW + 3')
B.execute(PT % 39 + PT % 4 + 'NOW = NOW + 0.3')
B.execute(PT % 39 + PT % 22 + 'NOW = NOW + 0.3')
check(fstore(B).get(3) == ('spell', 331), "i. a finger: 39 then 22 with the fight tab = the fight layer's slot 3", fstore(B))
B.execute('BK.ctl.mode = "bind"; BK.ctl.bind = { id = 324 }; NOW = NOW + 3')
B.execute(PT % 39 + PT % 4 + 'NOW = NOW + 0.3')
padtab(B, 39, 3)
r0 = B.eval('REFN')
B.execute('BK.press("PAD1")')
check(B.eval('BK.ctl.mode') == "bind" and sctl(B).get('note') == 'bind-cb-hidden' and B.eval('REFN') == r0 + 1 and fstore(B).get(1) is None,
      "i. Show off: refused with one red pulse (bind-cb-hidden), the box keeps waiting", sctl(B).get('note'))
padtab(B, 39, 2)
B.execute('WowCompanion._spellsBookKey("bf-off")')
check(B.eval('BK.ctl.bind.cb') is None and B.eval('BK.ctl.bind.cbf') is None, "i. bf-off leaves the Tap to cast tab (Out of combat + Controller)")
B.execute(PT % 39 + PT % 26 + 'NOW = NOW + 0.3')
check(B.eval('BK.ctl.bind.cb') is True and B.eval('BK.ctl.bind.cbf') is None and sctl(B).get('bcf') is None,
      "i. 39 then 26: Out of combat + Tap to cast (no bcf)")
B.execute('BK.press("PAD1")')
check(store(B).get(1) == ('spell', 324) and fstore(B).get(1) is None, "i. its A: the bar's own slot 1", (store(B), fstore(B)))
# D8: a bag item's box
B.execute('BK.ctl.mode = "bind"; BK.ctl.bind = { id = 118, item = true }; BK.ctl.boxItem = { id = 118, n = "Minor Healing Potion", i = 1 }; NOW = NOW + 3')
B.execute(PT % 39 + PT % 26 + 'NOW = NOW + 0.3')
check(sctl(B).get('bcb') == 1, "i. D8: a bag item's box takes the Tap to cast tab", sctl(B).get('bcb'))
B.execute('BK.press("PADDRIGHT"); BK.press("PADDRIGHT"); BK.press("PADDRIGHT"); BK.press("PAD1")')
check(store(B).get(4) == ('item', 118), "i. D8: A puts the item on slot 4 (an item cell)", store(B))
check(cell(B, 3, "type") == "item" and cell(B, 3, "item") == "item:118", "i. ... its cell uses the item")
B.execute('BK.ctl.mode = "bind"; BK.ctl.bind = { id = 118, item = true }; NOW = NOW + 3')
B.execute(PT % 39 + PT % 4 + 'NOW = NOW + 0.3')
B.execute(PT % 39 + PT % 27 + 'NOW = NOW + 0.3')
check(fstore(B).get(7) == ('item', 118), "i. D8: an item on the fight layer's slot 7 by a finger (39 then 4, then 39 then 27)", fstore(B))
B.execute('BK.ctl.mode = "bind"; BK.ctl.bind = { id = 118, item = true }; NOW = NOW + 3')
B.execute(PT % 39 + PT % 26 + 'NOW = NOW + 0.3')
B.execute(PT % 39 + PT % 23 + 'NOW = NOW + 0.3')
check(store(B).get(4) is None, "i. D8: its own slot again = off it (as a spell's pick)", store(B))
# the bar Off: A counts cbon, nothing put
B.execute('BK.ctl.mode = "bind"; BK.ctl.bind = { id = 403 }; NOW = NOW + 3')
padtab(B, 39, 31)
B.execute(PT % 39 + PT % 26 + 'NOW = NOW + 0.3')
before = store(B)
B.execute('BK.press("PAD1")')
check(sctl(B).get('cbon') == 1 and sctl(B).get('note') == 'bind-cb-on' and store(B) == before and B.eval('BK.ctl.mode') == "bind",
      "i. the bar Off: A counts cbon (the app turns it on), nothing put, the box waits", sctl(B))
clean(B, "i")

print("%d passed, %d failed" % (passes, fails))
sys.exit(1 if fails else 0)
