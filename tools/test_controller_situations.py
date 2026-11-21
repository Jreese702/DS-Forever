"""SITUATIONS FIRST (the user, 2026-11-20, the Controller room's Option C, with Party mode's tap-to-cast bar; ActionBar cb15, PadTabCtl p56,
Book b71), off-device: the REAL add-on files (the cast bar block of ActionBar.lua, Book, PadMap, PadTabCtl) under the stubbed game.

    python tools/test_controller_situations.py

Proven here:
  a versions + notes; the cast bar block still writes nothing of the player's; the swap reads Party mode's pad by a frame ref;
  b THE CELLS: 25 secure cells (8 slots, the pick, 8 fight cells, 8 party cells), within TapPad's 40; each slot / fight / party cell
    carries the three things ("wc-n-*" / "wc-f-*" / "wc-p-*") and the frame ref "wc-pad" (Party mode's own pad); a party cell's own
    attributes are the party thing; the document's `p` (cells 17-24), `ps`, `pl`; the store castBarParty (per character);
  c THE LAYER RULE AT THE PRESS (secure): Party (Party mode on) > In combat (a fight, Show on) > the slot's own, per slot, an empty slot
    falling through - all four cases of layers_party_rule.png, out of combat and in a fight, on the slot's cell AND its fight / party
    cells; no protected call from insecure code in the fight, no snippet error;
  d PARTY TARGETING: a party slot's spell casts on Party mode's pick (the pad's wc-u: you with no pick, party2 after a move, in a fight
    too), a fall-through slot keeps no unit, an item on a party slot is used as normal (no unit);
  e THE LOCKS: Bar in Party mode off (padtab 39, 38 then 3) disarms the party set (no wc-p-type, the slots cast their own), a party put /
    clear / move is refused "cb-phidden", the slots are KEPT, 39, 38 then 2 arms them again; Show in combat off still hides the fight set;
  f THE ROOM: one row of situations in the user's order (Out of combat, In combat, Party mode, Spell radial); no sub-tabs; Down from the
    tabs: the chips, then the controller; Down off the controller's bottom row = the bar's slot under it, Up from a slot = the controller
    place over it (sticks first on Out of combat); Left / Right along the 8 slots; LB / RB keep the device (the bar's slot on the next
    situation's bar); B one step back (chooser -> page -> tabs -> Home); a fresh open = Out of combat, the tabs;
  g Party mode's page: the chip "Bar in Party mode" (A counts `cbpn`, the add-on's copy flips), the diamonds, then the party bar
    (Down from the face diamond's bottom), its chooser puts on the PARTY layer, locked "cb-phidden" (one red pulse) while it is off;
    padtab 39, 38 then 1 opens it by touch; a finger's put (button code + the spell's taps) lands on the party layer;
  h THE BIND BOX's Party mode situation (Book b71): 39, 38 then 4 = its controller: A / X / Y (ZL / ZR held) put the spell on Party
    mode's slot (partyBy), the D-pad / ZL + ZR locked (one red pulse), an item refused "spells"; 39, 38 then 5 = its bar: A puts on the
    party layer, locked while Bar in Party mode is off; bf-off leaves it; 39 alone + 38 is still the rank-up pick;
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
-- Book's addon-code read of the controller (C_GamePad's mapped state, b18): the same buttons the secure snippets see (trig)
C_GamePad.GetActiveDeviceID = function() return 1 end
C_GamePad.GetDeviceMappedState = function() return { buttons = PADSTATE } end
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
    L.execute('WowCompanion = WowCompanion or {}')
    if pre:
        L.execute(pre)
    load = L.eval('function(src, name) local f, e = loadstring(src, name); if not f then error(e) end; return f end')
    for k in ("safety", "tappad", "book", "padmap", "padtabctl"):
        load(SRC[k], '@' + os.path.basename(F[k]))(os.path.basename(F[k]))
    load("local WC = _G.WowCompanion\n" + BLOCK, "@ActionBar.lua#castbar")()
    L.execute('WowCompanion.partyOk = true')                     # Party mode allowed (Hub's experimental switch)
    L.execute(WORLD_HELPERS)
    L.execute('fire("PLAYER_LOGIN"); fire("PLAYER_ENTERING_WORLD", true, false); WowCompanion.pad.layout(); runTimers(NOW + 10)')
    L.execute(AFTER)
    L.execute('WowCompanion.padMap.emit(true); runTimers(NOW + 1)')
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
        out[int(k)] = (v['k'], v['key'] if v['k'] == 'command' else (v['n'] if v['k'] == 'macro' else int(v['id'])))
    return out


def pstore(L):
    return store(L, "castBarParty")


def fstore(L):
    return store(L, "castBarFight")


def cell(L, col, attr):
    return L.eval('(function() local r = WowCompanion.pad.rows.castbar; local c = r and r.cells[%d]; return c and c.a["%s"] end)()'
                  % (col, attr))


def cellref(L, col, name):
    return L.eval('(function() local r = WowCompanion.pad.rows.castbar; local c = r and r.cells[%d]; '
                  'return c and c.refs and c.refs["%s"] and c.refs["%s"]._name end)()' % (col, name, name))


def padtab(L, *cols):
    for c in cols:
        L.execute('WowCompanion.pad.rows["padtab"].handler(nil, %d); NOW = NOW + 0.1' % c)


def tapcell(L, col):
    """a tap on castbar cell [col] (both edges): (kind, spell / item / line, unit) of the action it ran, or None"""
    L.execute('local n0 = #ACTIONS; local c = WowCompanion.pad.rows.castbar.cells[%d]; doClick(c, "LeftButton", true); '
              'doClick(c, "LeftButton", false); RAN = (#ACTIONS > n0) and ACTIONS[n0 + 1] or false; NOW = NOW + 0.3' % col)
    if L.eval('RAN') is False:
        return None
    return (L.eval('RAN.kind'), L.eval('RAN.spell') or L.eval('RAN.item') or L.eval('RAN.line'), L.eval('RAN.unit'))


def clean(L, name):
    check(L.eval('#SPY') == 0 and L.eval('#VIOL') == 0 and L.eval('#SNIPERR') == 0 and list(L.eval('ERRS').values()) == [],
          name + ": no spied call (no trace), no lockdown breach, no snippet error",
          (L.eval('SPY[1]'), L.eval('VIOL[1]'), L.eval('SNIPERR[1]'), list(L.eval('ERRS').values())[:2]))


def press(L, *keys):
    for k in keys:
        L.execute('pp("%s")' % k)


def party_on(L, on=True):
    n = "WowCompanionPartyOn" if on else "WowCompanionPartyOff"
    L.execute('clickName("%s", true); clickName("%s", false); advance(0.3)' % (n, n))


# ================================================================ a. versions, the note, no trace
check('local CBV = "cb16"' in BLOCK and "cb15 THE PARTY MODE LAYER" in AB, "a. ActionBar cb16 (cb15's header note kept)")
check('local CV = "p58"' in SRC["padtabctl"] and "p56 SITUATIONS FIRST" in SRC["padtabctl"], "a. PadTabCtl p56 and its note")
check('local BV = "b76"' in SRC["book"] and "b71 SITUATIONS FIRST" in SRC["book"], "a. Book b71 and its note")
code = "\n".join(l.split("--", 1)[0] for l in BLOCK.splitlines())
check(not re.search(r'(?<![\w.:])SetBinding\s*\(', code) and 'SaveBindings' not in code and 'CreateMacro' not in code
      and 'EditMacro' not in code and 'PlaceAction' not in code and 'SetCVar' not in code and 'PickupAction' not in code
      and 'SetOverrideBinding' not in code, "a. the cast bar code writes nothing of the player's (no binding, macro, bar slot or CVar)")
swap = re.search(r'CB\.SWAP = \[=\[(.*?)\]=\]', BLOCK, re.S).group(1)
check('GetFrameRef("wc-pad")' in swap and 'pad:GetAttribute("wc-party")' in swap and 'pad:GetAttribute("wc-u")' in swap
      and 'SecureCmdOptionParse("[combat] 1")' in swap and 'SetAttribute("unit"' in swap,
      "a. the swap reads Party mode's pad (wc-party, wc-u) by a frame ref at the press, then [combat]; sets the unit")
check(swap.index('wc-p-') < swap.index('[combat]'), "a. Party is decided before In combat (Party > In combat > own)")

# ================================================================ b. the cells
W = boot()
check(list(W.eval('ERRS').values()) == [], "b. the world loads", list(W.eval('ERRS').values())[:2])
check(W.eval('rawget(_G, "WowCompanionPartyPad") ~= nil'), "b. Party mode's pad exists (PadMap m77)")
padtab(W, 39, 30, 39, 2)                                       # the bar on, Show in combat on
check(W.eval('WowCompanion.castBar.pshow()') is True, "b. Bar in Party mode: on by default (never told)")
# layers_party_rule.png: slot 1 all three, slot 2 Party empty / combat + own, slot 3 own only, slot 4 party over an empty slot; slot 5 an item
W.execute('WowCompanion.castBar.put(1, "spell", 403)')                    # own: Lightning Bolt
W.execute('WowCompanion.castBar.put(1, "spell", 8004, "f")')              # fight: Lesser Healing Wave
W.execute('WowCompanion.castBar.put(1, "spell", 331, "p")')               # party: Healing Wave
W.execute('WowCompanion.castBar.put(2, "spell", 331)')                    # own: Healing Wave
W.execute('WowCompanion.castBar.put(2, "spell", 324, "f")')               # fight: Lightning Shield
W.execute('WowCompanion.castBar.put(3, "command", "TARGETNEARESTENEMY")')  # own only
W.execute('WowCompanion.castBar.put(4, "spell", 8017, "p")')              # party over an empty slot
W.execute('WowCompanion.castBar.put(5, "item", 118, "p")')                # an item on a party slot
check(store(W) == {1: ('spell', 403), 2: ('spell', 331), 3: ('command', 'TARGETNEARESTENEMY')}
      and fstore(W) == {1: ('spell', 8004), 2: ('spell', 324)}
      and pstore(W) == {1: ('spell', 331), 4: ('spell', 8017), 5: ('item', 118)},
      "b. three per-character stores: castBar, castBarFight, castBarParty", (store(W), fstore(W), pstore(W)))
row = W.eval('WowCompanion.pad.rows.castbar')
check(row is not None and int(row.n) == 25 and int(row.n) <= 40, "b. the castbar row: 25 secure cells (8 + the pick + 8 + 8), within 40",
      row and row.n)
check(cell(W, 0, "wc-n-spell") == 403 and cell(W, 0, "wc-f-spell") == 8004 and cell(W, 0, "wc-p-spell") == 331
      and cell(W, 0, "wc-p-type") == "spell", "b. slot 1's cell: all three things as attributes",
      (cell(W, 0, "wc-n-spell"), cell(W, 0, "wc-f-spell"), cell(W, 0, "wc-p-spell")))
check(cell(W, 17, "type") == "spell" and cell(W, 17, "spell") == 331 and cell(W, 17, "wc-n-spell") == 403,
      "b. party slot 1's cell (17): the party spell as its own, the others as attributes", (cell(W, 17, "spell"),))
check(cellref(W, 0, "wc-pad") == "WowCompanionPartyPad" and cellref(W, 17, "wc-pad") == "WowCompanionPartyPad",
      "b. the frame ref wc-pad = Party mode's pad (set out of combat)", (cellref(W, 0, "wc-pad"), cellref(W, 17, "wc-pad")))
check(cell(W, 3, "type") is None and cell(W, 3, "wc-p-spell") == 8017 and cell(W, 20, "spell") == 8017,
      "b. slot 4: no own action, the party spell on its party cell (20)")
check(cell(W, 18, "type") is None and cell(W, 8, "type") is None, "b. an unset party slot's cell (18) and the pick cell have no action")
d = bar(W)
pe = {x['s']: x for x in d.get('p', [])}
check(d.get('v') == 'cb16' and d.get('ps') == 1 and d.get('pl') == 1 and pe.get(1, {}).get('c') == 17 and pe.get(4, {}).get('c') == 20
      and pe.get(5, {}).get('k') == 'item', "b. the document: `p` with cells 17 / 20, ps = 1, pl = 1", d.get('p'))
clean(W, "b")

# ================================================================ c. the rule at the press (layers_party_rule.png)
W.execute('party({ { "Thrall", "SHAMAN" }, { "Elyse", "PRIEST" } })')
W.execute('advance(0.5)')


def row4(L):
    return [(tapcell(L, i) or (None, None, None))[1] for i in range(0, 4)]


check(row4(W) == [403, 331, "/targetenemy", None], "c. Out of combat: the slots' own (slot 4: nothing)", row4(W))
W.execute('fightOn()')
check(row4(W) == [8004, 324, "/targetenemy", None], "c. In a fight: Lesser Healing Wave, Lightning Shield (fight), slot 3 its own", row4(W))
W.execute('fightOff()')
party_on(W)
check(W.eval('PADA("wc-party")') is True, "c. Party mode on (the pad's wc-party)")
check(row4(W) == [331, 331, "/targetenemy", 8017], "c. Party mode: Healing Wave on 1 (party), slot 2 its own, slot 4 the party spell",
      row4(W))
W.execute('fightOn()')
check(row4(W) == [331, 324, "/targetenemy", 8017], "c. Party mode in a fight: party 1, FIGHT 2 (falls through to In combat), own 3, party 4",
      row4(W))
check(tapcell(W, 17)[1] == 331 and tapcell(W, 9)[1] == 331 and tapcell(W, 10)[1] == 324,
      "c. the party / fight cells decide the same way (whichever the app taps)", (tapcell(W, 17), tapcell(W, 9), tapcell(W, 10)))
check(W.eval('#VIOL') == 0 and W.eval('#SNIPERR') == 0, "c. no protected call from insecure code in the fight, no snippet error",
      (W.eval('VIOL[1]'), W.eval('SNIPERR[1]')))
lv = W.eval('WowCompanion.castBar.liveView()')
check(lv[1]['id'] == 331 and lv[2]['id'] == 324 and lv[4]['id'] == 8017, "c. the live view (cooldowns) follows the same rule",
      [lv[i]['id'] if lv[i] else None for i in (1, 2, 3, 4)])
W.execute('fightOff()')
clean(W, "c")

# ================================================================ d. party targeting
check(tapcell(W, 0) == ("spell", 331, "player"), "d. no pick moved: a party spell casts on you (wc-u player)", tapcell(W, 0))
W.execute('hit(4); advance(0.3)')                            # the pad's D-pad down: the pick moves to party1
W.execute('hit(4); advance(0.3)')                            # ... to party2
u = W.eval('PADA("wc-u")')
check(u == "party2", "d. the pick moved by Party mode's own pad (wc-u)", u)
check(tapcell(W, 0) == ("spell", 331, "party2"), "d. slot 1's party spell casts on the pick (party2)", tapcell(W, 0))
check(tapcell(W, 1) == ("spell", 331, None), "d. slot 2 (no party thing) keeps no unit", tapcell(W, 1))
r5 = tapcell(W, 4)
check(r5 is not None and r5[0] == "item" and r5[2] is None, "d. an item on a party slot is used as normal (no unit)", r5)
W.execute('fightOn()')
check(tapcell(W, 0) == ("spell", 331, "party2"), "d. in a fight too (the pick read at the press)", tapcell(W, 0))
check(tapcell(W, 1) == ("spell", 324, None), "d. a fall-through fight slot: no unit", tapcell(W, 1))
W.execute('fightOff()')
party_on(W, False)
check(tapcell(W, 0) == ("spell", 403, None), "d. Party mode off: slot 1 its own again, no unit", tapcell(W, 0))
clean(W, "d")

# ================================================================ e. the locks
padtab(W, 39, 38, 3)
check(W.eval('WowCompanionSpellsDB.castBarPShow') is False and W.eval('WowCompanion.castBar.pshow()') is False,
      "e. padtab 39, 38 then 3: Bar in Party mode off (kept: castBarPShow false)")
check(cell(W, 0, "wc-p-type") is None and cell(W, 17, "type") is None, "e. the party set disarmed")
party_on(W)
check(tapcell(W, 0)[1] == 403 and tapcell(W, 3) is None, "e. Party mode on with it off: slot 1 its own, slot 4 nothing", (tapcell(W, 0), tapcell(W, 3)))
party_on(W, False)
W.execute('WowCompanion.castBar.put(6, "spell", 324, "p")')
a = docs(W, 'castbarack')[-1]
check(a.get('ok') is False and a.get('why') == 'cb-phidden' and a.get('p') == 1 and 6 not in pstore(W),
      "e. a party put with it off: refused cb-phidden", a)
W.execute('WowCompanion.castBar.clear(1, "p")')
check(pstore(W).get(1) == ('spell', 331), "e. ... its Clear too (kept, not edited)")
d = bar(W)
check(d.get('ps') is None and {x['s'] for x in d.get('p', [])} == {1, 4, 5} and all('c' not in x for x in d.get('p', [])),
      "e. the party slots KEPT in the document, no cells meanwhile", d.get('p'))
padtab(W, 39, 38, 2)
check(cell(W, 0, "wc-p-spell") == 331 and cell(W, 17, "spell") == 331, "e. 39, 38 then 2: on again, the kept slots armed again")
W.execute('WowCompanion.castBar.move(4, 6, "p")')
check(pstore(W).get(6) == ('spell', 8017) and pstore(W).get(4) is None, "e. a party-layer move", pstore(W))
W.execute('fightOn(); WowCompanion.castBar.put(7, "spell", 324, "p")')
check(pstore(W).get(7) is None, "e. a party put in a fight waits for its end")
W.execute('fightOff()')
check(pstore(W).get(7) == ('spell', 324), "e. ... and lands on the party layer at the fight's end", pstore(W))
padtab(W, 39, 3)
check(cell(W, 0, "wc-f-type") is None and cell(W, 0, "wc-p-spell") == 331, "e. Show in combat off disarms the fight set only")
padtab(W, 39, 2)
check(W.eval('WowCompanion.rankUpPickN or 0') is not None, "e. (the world still answers)")
clean(W, "e")

# ================================================================ f. the room: situations, one cursor across both devices
R = boot()
padtab(R, 39, 30, 39, 2)
R.execute('WowCompanion.castBar.put(1, "spell", 403)')
R.execute('WowCompanion.padTabCtl.enter("tabs")')
c = ctl(R)
check(c.get('view') == 'normal' and c.get('focus') == 'tabs' and c.get('top') == 'normal', "f. the room opens on Out of combat, the cursor on the tabs", c)
seen = []
for _ in range(4):
    press(R, "PADRSHOULDER")
    seen.append(ctl(R).get('view'))
check(seen == ['combat', 'party', 'radial', 'radial'], "f. RB: In combat, Party mode, Spell radial (the user's order), no wrap", seen)
press(R, "PADLSHOULDER", "PADLSHOULDER", "PADLSHOULDER")
check(ctl(R).get('view') == 'normal', "f. LB back to Out of combat")
press(R, "PADRIGHT") if False else None
press(R, "PADDDOWN")
c = ctl(R)
check(c.get('focus') == 'switches' and c.get('view') == 'normal', "f. Down from the tabs: the chips (DS WoW toolbar), no sub-tabs", c)
press(R, "PADDDOWN")
check(ctl(R).get('focus') in ('sets', 'forms'), "f. Down: the controller's set row", ctl(R).get('focus'))
press(R, "PADDDOWN")
c = ctl(R)
check(c.get('focus') == 'buttons' and c.get('view') == 'normal', "f. Down: the controller's buttons", c)
R.execute('WowCompanion.padTabCtl.touchPos(9)')               # L3 (the sticks, Out of combat's bottom row)
press(R, "PADDDOWN")
c = ctl(R)
check(c.get('view') == 'castbar' and c.get('focus') == 'buttons' and c.get('pos') == 4 and c.get('top') == 'normal',
      "f. Down from L3: the bar's slot 4 (under its column), the same situation", c)
press(R, "PADDDOWN")
check(ctl(R).get('view') == 'castbar' and ctl(R).get('pos') == 4, "f. Down on the bar: nothing (its last row)")
press(R, "PADDLEFT", "PADDLEFT", "PADDLEFT", "PADDLEFT")
check(ctl(R).get('pos') == 1, "f. Left along the 8 slots, no wrap", ctl(R).get('pos'))
press(R, "PADDUP")
c = ctl(R)
check(c.get('view') == 'normal' and c.get('focus') == 'buttons' and c.get('pos') == 4,
      "f. Up from slot 1: the controller place over it (D-pad Down)", c)
R.execute('WowCompanion.padTabCtl.touchPos(10)')
press(R, "PADDDOWN")
check(ctl(R).get('pos') == 5 and ctl(R).get('view') == 'castbar', "f. Down from R3: slot 5", ctl(R))
press(R, "PADDRIGHT", "PADDRIGHT", "PADDRIGHT")
press(R, "PADDUP")
c = ctl(R)
place8 = R.eval('WowCompanion.padTabCtl.place.over(8, 10)')
check(c.get('view') == 'normal' and c.get('pos') == place8 and place8 not in (9, 10), "f. Up from slot 8: the face diamond's lower right", (c.get('pos'), place8))
# LB / RB keep the device
R.execute('WowCompanion.padTabCtl.touchPos(9)')
press(R, "PADDDOWN", "PADDRIGHT")
check(ctl(R).get('view') == 'castbar' and ctl(R).get('pos') == 5, "f. on the bar's slot 5")
press(R, "PADRSHOULDER")
c = ctl(R)
check(c.get('view') == 'cbfight' and c.get('pos') == 5 and c.get('focus') == 'buttons', "f. RB on the bar: In combat's bar, the same slot", c)
press(R, "PADRSHOULDER")
c = ctl(R)
check(c.get('view') == 'pbar' and c.get('pos') == 5 and c.get('top') == 'party', "f. RB again: Party mode's bar, the same slot", c)
press(R, "PADRSHOULDER")
check(ctl(R).get('view') == 'radial', "f. RB: Spell radial (no bar: its own place)")
press(R, "PADLSHOULDER")
check(ctl(R).get('view') == 'party' and ctl(R).get('focus') == 'pdiam', "f. LB from the radial: Party mode's diamonds")
# B one step back
press(R, "PADDDOWN")
check(ctl(R).get('view') == 'party' and ctl(R).get('ppos') == 7, "f. Down from A: the face diamond's bottom (B) first", ctl(R).get('ppos'))
press(R, "PADDDOWN")
check(ctl(R).get('view') == 'pbar', "f. Down from the party diamonds' bottom: Party mode's bar", ctl(R))
press(R, "PAD1")
check(ctl(R).get('chooser') is not None, "f. A on a slot: the chooser")
press(R, "PAD2")
check(ctl(R).get('chooser') is None and ctl(R).get('focus') == 'buttons', "f. B: chooser -> the page")
press(R, "PAD2")
check(ctl(R).get('focus') == 'tabs', "f. B: the page -> the tabs (one step)")
check(R.eval('WowCompanion.padTabCtl.press("PAD2")') is False, "f. B on the tabs: not the room's (Home)")
check(R.eval('WowCompanion.padTabCtl.boxOpen()') is False, "f. boxOpen() false only on the tabs")
press(R, "PADDDOWN")
check(ctl(R).get('view') == 'party' and ctl(R).get('focus') == 'switches', "f. Down from the tabs on Party mode: its chip (the controller view)", ctl(R))
R.execute('WowCompanion.padTabCtl.leave(); WowCompanion.padTabCtl.enter("tabs")')
check(ctl(R).get('view') == 'normal' and ctl(R).get('focus') == 'tabs', "f. a fresh open: Out of combat, the tabs")
# In combat: no sticks - Down from the D-pad's bottom = the bar
R.execute('WowCompanion.padTabCtl.touchTab(3); WowCompanion.padTabCtl.touchPos(4)')
press(R, "PADDDOWN")
check(ctl(R).get('view') == 'cbfight' and ctl(R).get('pos') == 2, "f. In combat: Down from D-pad Down = its bar's slot 2", ctl(R))
check(R.eval('WowCompanion.padTabCtl.place.under(0)') == 1 and R.eval('WowCompanion.padTabCtl.place.under(5)') == 8,
      "f. the columns: the grid's 0..5 spread over slots 1..8")
clean(R, "f")

# ================================================================ g. Party mode's page and bar
G = boot()
padtab(G, 39, 30, 39, 2)
G.execute('WowCompanion.padTabCtl.enter("tabs")')
press(G, "PADRSHOULDER", "PADRSHOULDER")
check(ctl(G).get('view') == 'party', "g. Party mode")
press(G, "PADDDOWN")
c = ctl(G)
check(c.get('focus') == 'switches' and c.get('pfocus') == 'sw' and c.get('cbps') == 1, "g. its chip: Bar in Party mode (on)", c)
n0 = c.get('cbpn')
press(G, "PAD1")
c = ctl(G)
check(c.get('cbpn') == n0 + 1 and c.get('note') == 'switch-cbpshow' and c.get('cbps') is None and G.eval('WowCompanion.castBar.pshow()') is False,
      "g. A: counted (cbpn: the app flips castBarPartyShow), the add-on's copy off at once", c)
press(G, "PADDDOWN", "PADDDOWN", "PADDDOWN")
c = ctl(G)
check(c.get('focus') == 'pdiam', "g. Down: the set row, the Touch row, the diamonds", c.get('focus'))
G.execute('WowCompanion.padTabCtl.touchPartySlot(1)')       # A (position 8)
press(G, "PADDDOWN", "PADDDOWN")
c = ctl(G)
check(c.get('view') == 'pbar' and c.get('focus') == 'buttons' and c.get('pbar') is not None and c.get('pos') == 7,
      "g. Down from A, then from B: Party mode's bar, slot 7 (the page keeps its left side: `pbar`)", c)
r0 = G.eval('REFN')
press(G, "PAD1")
c = ctl(G)
check(c.get('chooser') is None and c.get('note') == 'cb-phidden' and G.eval('REFN') == r0 + 1,
      "g. Bar in Party mode off: A = one red pulse, cb-phidden, no chooser", (c.get('note'), G.eval('REFN') - r0))
padtab(G, 39, 38, 2)
check(ctl(G).get('note') == 'cb-pshow' and ctl(G).get('cbps') == 1, "g. 39, 38 then 2: on again (the page says it)", ctl(G).get('note'))
press(G, "PAD1")
check(ctl(G).get('chooser') is not None, "g. A: the chooser (all five tabs)")
press(G, "PAD1")
check(pstore(G) and list(pstore(G).values())[0][0] == 'spell' and store(G) == {} and fstore(G) == {},
      "g. a pick goes on the PARTY layer only", (pstore(G), store(G)))
s = ctl(G).get('pos')
G.execute('WowCompanion.padTabCtl.touchPos(3); WowCompanion.padTabCtl.touchSpell(331)')
check(pstore(G).get(3) == ('spell', 331), "g. a finger's put (button code 3, the spell's taps) on the party layer", pstore(G))
G.execute('WowCompanion.padTabCtl.touchTab(1)')
G.execute('WowCompanion._spellsBook.ctl.croom = true')
padtab(G, 39, 38, 1)
check(ctl(G).get('view') == 'pbar', "g. padtab 39, 38 then 1: Party mode's bar by touch", ctl(G).get('view'))
padtab(G, 39, 38)
G.execute('NOW = NOW + 3')
padtab(G, 1)
check(ctl(G).get('view') == 'normal', "g. a stale second-page pick (past 2 s) spends nothing: column 1 alone is its own (tab 1)", ctl(G).get('view'))
clean(G, "g")

# ================================================================ h. the Bind box's Party mode situation (Book b71)
B = boot()
padtab(B, 39, 30, 39, 2)
B.execute('BK = WowCompanion._spellsBook; BK.ctl.on = true; BK.ctl.mode = "bind"; BK.ctl.bind = { id = 331 }')
B.execute('WowCompanion.pad.rows["padtab"].handler(nil, 39); WowCompanion.pad.rows["padtab"].handler(nil, 38); '
          'WowCompanion.pad.rows["padtab"].handler(nil, 4); NOW = NOW + 0.3')
c = sctl(B)
check(B.eval('BK.ctl.bind.party') is True and c.get('bpm') == 1 and c.get('bcb') is None, "h. 39, 38 then 4: the Party mode situation, its controller (bpm)", c)
r0 = B.eval('REFN')
B.execute('BK.press("PADDLEFT")')
check(B.eval('BK.ctl.mode') == "bind" and sctl(B).get('note') == 'bind-party-locked' and B.eval('REFN') == r0 + 1,
      "h. the D-pad: locked, one red pulse, the box waits", sctl(B).get('note'))
B.execute('trig(false, false); BK.press("PAD1")')
by = B.eval('(WowCompanionSpellsDB.partyBy or {})[WHO .. "-" .. REALM]')
check(by is not None and by.slots is not None and by.slots['a'] == 331 and B.eval('BK.ctl.mode') == "book",
      "h. A: Healing Wave on Party mode's A (partyBy), the box closed", by and dict(by.slots or {}))
B.execute('BK.ctl.mode = "bind"; BK.ctl.bind = { id = 331, party = true }; trig(true, false); BK.press("PAD3"); trig(false, false)')
by = B.eval('(WowCompanionSpellsDB.partyBy or {})[WHO .. "-" .. REALM]')
check(by.slots['zlx'] == 331, "h. ZL held + X: Party mode's ZL X", dict(by.slots))
B.execute('BK.ctl.mode = "bind"; BK.ctl.bind = { id = 331, party = true }; trig(true, true); BK.press("PAD1"); trig(false, false)')
check(B.eval('BK.ctl.mode') == "bind" and sctl(B).get('note') == 'bind-party-locked', "h. ZL + ZR: locked")
B.execute('BK.ctl.bind = { id = 118, item = true, party = true }; BK.press("PAD1")')
check(sctl(B).get('note') == 'bind-party-spells', "h. an item: refused (Party mode's slots take spells)", sctl(B).get('note'))
B.execute('BK.ctl.bind = { id = 331 }')
B.execute('WowCompanion.pad.rows["padtab"].handler(nil, 39); WowCompanion.pad.rows["padtab"].handler(nil, 38); '
          'WowCompanion.pad.rows["padtab"].handler(nil, 5); NOW = NOW + 0.3')
c = sctl(B)
check(c.get('bcb') == 1 and c.get('bcp') == 1 and c.get('bpm') is None, "h. 39, 38 then 5: its bar (bcb, bcp)", c)
B.execute('BK.press("PADDRIGHT"); BK.press("PAD1")')
check(pstore(B).get(2) == ('spell', 331) and store(B) == {} and B.eval('BK.ctl.mode') == "book",
      "h. A: on the bar's PARTY layer, slot 2", (pstore(B), store(B)))
B.execute('BK.ctl.mode = "bind"; BK.ctl.bind = { id = 403, cb = true, cbp = true, party = true }; NOW = NOW + 3')
padtab(B, 39, 38, 3)
r0 = B.eval('REFN')
B.execute('BK.press("PAD1")')
check(B.eval('BK.ctl.mode') == "bind" and sctl(B).get('note') == 'bind-cb-phidden' and B.eval('REFN') == r0 + 1 and pstore(B).get(1) is None,
      "h. Bar in Party mode off: refused, one red pulse, the box waits", sctl(B).get('note'))
padtab(B, 39, 38, 2)
B.execute('WowCompanion._spellsBookKey("bf-off")')
check(B.eval('BK.ctl.bind.party') is None and B.eval('BK.ctl.bind.cbp') is None and B.eval('BK.ctl.bind.cb') is None,
      "h. bf-off leaves Party mode (Out of combat, the controller)")
n0 = B.eval('WowCompanion.rankUpN or 0')
B.execute('WowCompanion.pad.rows["padtab"].handler(nil, 38)')
check(sctl(B).get('mode') == 'bind', "h. 38 alone (no pick before it): still the rank-up pick, nothing of the box")
clean(B, "h")

print("%d passed, %d failed" % (passes, fails))
sys.exit(1 if fails else 0)
