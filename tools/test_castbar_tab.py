"""THE CONTROLLER ROOM'S CAST BAR TAB (PadTabCtl p53, ActionBar cb9, Book b60, PadMap m159), off-device: the REAL add-on files (the
cast bar block of ActionBar.lua, Book, PadMap, PadTabCtl) under the stubbed game.

    python tools/test_castbar_tab.py

The user, 2026-11-13: "in the controller menu we need a Tab for the cast bar if its enabled, where people can assign anything from the
5 tabs". Proven here:
  1 the tab is hidden until the app says its bar setting is on (padtab 39 then 30; 31 off again, the tab on show goes back to WoW
    Defaults); LB / RB step over it while hidden and onto it while shown; padtab 39 then 29 opens it (the room open);
  2 the pad on it: Left / Right along the 8 slots (stopping at the ends), Up the tab row; A = the chooser on Spells with all five tabs,
    the game bindings left out of Extra; a spell row's A puts it on the slot (the cell: type spell, its id);
  3 by touch (the eight's own routes): button code k = slot k; the spell's taps; a command (Extra / DS) = a macrotext cell on OUR
    button, an item = type item "item:<id>", a mount = "/cast <its summon spell>"; the castbar document's kinds, names, count;
  4 Replace asks on a filled slot (the controller's box), Clear asks and empties; Move: X then A on another slot = a swap / a move;
  5 a fight: the room refuses (nothing changes); a touch put waits for the fight's end (queued), then lands;
  6 an old store of spell numbers is migrated to { k, id } at the login; the Spellbook's Bind-box pick still works on a mixed bar;
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


def bar(L):
    L.execute('WowCompanion.castBar.send(true)')
    d = docs(L, 'castbar')[-1]
    return {e['s']: e for e in d.get('e', [])}, d


def store(L):
    t = L.eval('(WowCompanionSpellsDB.castBar or {})[WHO .. "-" .. REALM]')
    out = {}
    if t is None:
        return out
    for k, v in t.items():
        if isinstance(v, (int, float)):
            out[int(k)] = ('num', int(v))
        else:
            out[int(k)] = (v['k'], v['key'] if v['k'] == 'command' else int(v['id']))
    return out


def cell(L, col, attr):
    return L.eval('(function() local r = WowCompanion.pad.rows.castbar; local c = r and r.cells[%d]; return c and c.a["%s"] end)()'
                  % (col, attr))


def padtab(L, *cols):
    for c in cols:
        L.execute('WowCompanion.pad.rows["padtab"].handler(nil, %d); NOW = NOW + 0.1' % c)


def clean(L, name):
    check(L.eval('#SPY') == 0 and L.eval('#VIOL') == 0 and L.eval('#SNIPERR') == 0 and list(L.eval('ERRS').values()) == [],
          name + ": no spied call (no trace), no lockdown breach, no snippet error",
          (L.eval('SPY[1]'), L.eval('VIOL[1]'), L.eval('SNIPERR[1]'), list(L.eval('ERRS').values())[:2]))


check('local CV = "p53"' in SRC["padtabctl"] and 'local BV = "b60"' in SRC["book"] and 'local PV = "m160"' in SRC["padmap"]
      and 'local CBV = "cb9"' in BLOCK, "the versions: PadTabCtl p53, Book b60, PadMap m159, ActionBar cb9")
code = "\n".join(l.split("--", 1)[0] for l in BLOCK.splitlines())
check(not re.search(r'(?<![\w.:])SetBinding\s*\(', code) and 'SaveBindings' not in code and 'CreateMacro' not in code
      and 'EditMacro' not in code and 'PlaceAction' not in code and 'SetCVar' not in code and 'PickupAction' not in code,
      "the cast bar code writes nothing of the player's (no binding, macro, bar slot or CVar)")

# ================================================================ 1. the tab, shown only while the app's setting is on
W = boot()
check(list(W.eval('ERRS').values()) == [], "the world loads", list(W.eval('ERRS').values())[:2])
W.execute('WowCompanion.padTabCtl.enter("tabs")')
padtab(W, 39, 29)
check(ctl(W).get('view') == 'normal', "the setting off: padtab 39 then 29 does not open the tab", ctl(W).get('view'))
W.execute('pp("PADRSHOULDER"); pp("PADRSHOULDER"); pp("PADRSHOULDER"); pp("PADRSHOULDER")')
check(ctl(W).get('view') != 'castbar', "RB never reaches it while hidden", ctl(W).get('view'))
padtab(W, 39, 30)
check(W.eval('WowCompanionSpellsDB.castBarTab') is True, "padtab 39 then 30: the setting on (kept)")
W.execute('WowCompanion.padTabCtl.touchTab(1); pp("PADRSHOULDER"); pp("PADRSHOULDER"); pp("PADRSHOULDER"); pp("PADRSHOULDER")')
check(ctl(W).get('view') == 'castbar', "with it on, RB steps onto the Cast bar tab (the last one)", ctl(W).get('view'))
padtab(W, 39, 31)
check(ctl(W).get('view') == 'normal' and W.eval('WowCompanionSpellsDB.castBarTab') is None,
      "padtab 39 then 31: off again, the tab on show goes back to WoW Defaults", ctl(W).get('view'))
padtab(W, 39, 30, 39, 29)
c = ctl(W)
check(c.get('view') == 'castbar' and c.get('focus') == 'buttons', "padtab 39 then 29: the Cast bar tab, the cursor on its slots", c)

# ================================================================ 2. the pad on the tab
W.execute('WowCompanion.padTabCtl.touchPos(1)')
W.execute('for _ = 1, 10 do pp("PADDRIGHT") end')
check(ctl(W).get('pos') == 8, "Right walks the 8 slots and stops at 8", ctl(W).get('pos'))
W.execute('for _ = 1, 10 do pp("PADDLEFT") end')
check(ctl(W).get('pos') == 1, "Left back to slot 1 and stops", ctl(W).get('pos'))
W.execute('pp("PADDUP")')
check(ctl(W).get('focus') == 'tabs', "Up = the tab row", ctl(W).get('focus'))
W.execute('pp("PADDDOWN")')
check(ctl(W).get('focus') == 'buttons', "Down = the slots again")
W.execute('pp("PAD1")')
c = ctl(W)
check(c.get('chooser') and c['chooser'].get('list') == 'spells', "A on an empty slot: the chooser on Spells", c.get('chooser'))
seen = []
for i in range(5):
    seen.append((ctl(W).get('chooser') or {}).get('list'))
    if i < 4:
        W.execute('pp("PADDRIGHT")')
check(seen == ["spells", "general", "professions", "extra", "dstool"], "all five tabs", seen)
W.execute('pp("PADDLEFT")')                                  # Extra
ids = []
for i in range(60):
    ch = ctl(W).get('chooser') or {}
    ids.append(ch.get('id'))
    W.execute('pp("PADDDOWN")')
check(not any(x in ("JUMP", "INTERACTTARGET", "TOGGLEAUTORUN", "TOGGLERUN") for x in ids) and "TARGETNEARESTENEMY" in ids,
      "Extra lists the commands, mounts and items, not the game bindings (a key is no cast)", [x for x in ids if x][:6])
W.execute('pp("PAD2"); pp("PAD1")')                       # B closes the chooser, A opens it again (Spells, row 1)
check((ctl(W).get('chooser') or {}).get('id') == 403, "Spells row 1 = Lightning Bolt", ctl(W).get('chooser'))
W.execute('pp("PAD1")')
check(store(W).get(1) == ('spell', 403) and cell(W, 0, "type") == "spell" and cell(W, 0, "spell") == 403,
      "A: Lightning Bolt on slot 1 (stored { k = spell, id }; the cell type spell)", (store(W), cell(W, 0, "type")))
check(not ctl(W).get('chooser'), "the chooser closes")

# ================================================================ 3. every kind by touch
W.execute('WowCompanion.padTabCtl.touchPos(2); WowCompanion.padTabCtl.touchSpell(331)')
check(store(W).get(2) == ('spell', 331), "by touch: slot 2 picked (button code 2), Healing Wave's taps put it there", store(W))
xl = W.eval('WowCompanion.padMap.extraList()')
lst = [dict(xl[i]) for i in range(1, len(xl) + 1)]
def idx(pred):
    for i, x in enumerate(lst):
        if pred(x):
            return i
    return None
ci = idx(lambda x: x.get('kind') == 'command' and x.get('key') == 'TARGETNEARESTENEMY')
W.execute('WowCompanion.padTabCtl.touchPos(3); WowCompanion.padTabCtl.touchExtraPage(%d)' % (ci // 40 * 40))
ok = W.eval('WowCompanion.padTabCtl.touchExtra(%d)' % (ci % 40))
check(store(W).get(3) == ('command', 'TARGETNEARESTENEMY') and cell(W, 2, "type") == "macro" and cell(W, 2, "macrotext") == "/targetenemy",
      "an Extra command on slot 3: a macrotext cell on OUR button (/targetenemy)", (ok, store(W).get(3), cell(W, 2, "macrotext")))
di = idx(lambda x: x.get('kind') == 'command' and x.get('key') == 'DSMAP')
W.execute('WowCompanion.padTabCtl.touchPos(4); WowCompanion.padTabCtl.touchExtraPage(%d)' % (di // 40 * 40))
W.eval('WowCompanion.padTabCtl.touchExtra(%d)' % (di % 40))
check(store(W).get(4) == ('command', 'DSMAP') and cell(W, 3, "macrotext") == "/click WowCompanionPadMap", "a DS command on slot 4",
      cell(W, 3, "macrotext"))
W.execute('WowCompanion.padTabCtl.touchPos(5)')
W.execute('WowCompanion.padTabCtl.touchCommand(1)')
c1 = W.eval('WowCompanion.padMap.commands()[1].key')
check(store(W).get(5) == ('command', c1), "a command by its talents code on slot 5", store(W).get(5))
# items and mounts by the Extra row's route (the world's bags may have none: the put is the same act)
W.execute('WowCompanion.padTabCtl.touchPos(6)')
W.execute('WowCompanion.castBar.put(6, "item", 118)')
check(store(W).get(6) == ('item', 118) and cell(W, 5, "type") == "item" and cell(W, 5, "item") == "item:118",
      "an item on slot 6: type item, item:118 (the Bags row's way, no macro)", (store(W).get(6), cell(W, 5, "item")))
W.execute('WowCompanion.castBar.put(7, "mount", 6)')
check(store(W).get(7) == ('mount', 6) and cell(W, 6, "type") == "macro" and cell(W, 6, "macrotext") == "/cast Brown Horse",
      "a mount on slot 7: /cast its summon spell on OUR button", cell(W, 6, "macrotext"))
mi = idx(lambda x: x.get('kind') == 'mount')
if mi is not None:
    W.execute('WowCompanion.padTabCtl.touchPos(8); WowCompanion.padTabCtl.touchExtraPage(%d)' % (mi // 40 * 40))
    W.eval('WowCompanion.padTabCtl.touchExtra(%d)' % (mi % 40))
    check(store(W).get(8, (None,))[0] == 'mount', "a mount from the Extra row on slot 8", store(W).get(8))
    W.execute('WowCompanion.castBar.clear(8)')
bi = idx(lambda x: x.get('kind') == 'bind')
if bi is not None:
    W.execute('WowCompanion.padTabCtl.touchPos(8); WowCompanion.padTabCtl.touchExtraPage(%d)' % (bi // 40 * 40))
    W.eval('WowCompanion.padTabCtl.touchExtra(%d)' % (bi % 40))
    check(store(W).get(8) is None and ctl(W).get('note') == 'cb-bind', "a game binding by touch is refused (cb-bind)", ctl(W).get('note'))
e, d = bar(W)
check(d.get('v') == 'cb9' and d.get('any') == 1, "the document: cb9, any = 1", d.get('v'))
check(e[1].get('id') == 403 and 'k' not in e[1] and e[1].get('c') == 0, "slot 1: a spell as before (no k)", e[1])
check(e[3].get('k') == 'command' and e[3].get('key') == 'TARGETNEARESTENEMY' and e[3].get('n') and e[3].get('i') == 132212 and e[3].get('c') == 2,
      "slot 3: kind command, key, name, icon, its cell", e[3])
check(e[6].get('k') == 'item' and e[6].get('id') == 118 and e[6].get('n') == "Minor Healing Potion" and e[6].get('ct') == 7
      and e[6].get('cons') == 1, "slot 6: kind item, name, count 7, consumable", e[6])
check(e[7].get('k') == 'mount' and e[7].get('n') == "Brown Horse" and e[7].get('i') == 132261 and e[7].get('sid') == 458,
      "slot 7: kind mount, name, icon, its summon spell", e[7])
W.execute('ITEMCOUNT[118] = 6; fire("BAG_UPDATE_DELAYED")')
check(docs(W, 'castbar')[-1]['e'][[x['s'] for x in docs(W, 'castbar')[-1]['e']].index(6)].get('ct') == 6,
      "BAG_UPDATE_DELAYED: the new count goes out")
W.execute('local n0 = #ACTIONS; doClick(WowCompanion.pad.rows.castbar.cells[2], "LeftButton", true); '
          'doClick(WowCompanion.pad.rows.castbar.cells[2], "LeftButton", false); RAN = ACTIONS[n0 + 1]')
check(W.eval('RAN and RAN.line') == "/targetenemy", "a tap on slot 3's cell runs its line", W.eval('RAN and RAN.kind'))
W.execute('local n0 = #ACTIONS; doClick(WowCompanion.pad.rows.castbar.cells[5], "LeftButton", true); '
          'doClick(WowCompanion.pad.rows.castbar.cells[5], "LeftButton", false); RAN = ACTIONS[n0 + 1]')
check(W.eval('RAN and RAN.kind') == "item" and W.eval('RAN and RAN.item') == "item:118", "a tap on slot 6's cell uses the item",
      W.eval('RAN and RAN.kind'))

# ================================================================ 4. Replace / Clear boxes, Move
W.execute('WowCompanion.padTabCtl.touchPos(1); pp("PAD1")')
for _ in range(3):
    if (ctl(W).get('chooser') or {}).get('id') == 331:
        break
    W.execute('pp("PADDDOWN")')
W.execute('pp("PAD1")')
check((ctl(W).get('box') or {}).get('kind') == 'replace' and store(W).get(1) == ('spell', 403),
      "a filled slot asks Replace first (nothing changed yet)", ctl(W).get('box'))
W.execute('pp("PAD1")')
check(store(W).get(1) == ('spell', 331) and store(W).get(2) is None,
      "A: Healing Wave on slot 1 (off slot 2: one copy per bar)", store(W))
W.execute('pp("PAD1")')
for _ in range(80):
    ch = ctl(W).get('chooser') or {}
    if ch.get('id') == 'clear':
        break
    W.execute('pp("PADDDOWN")')
W.execute('pp("PAD1")')
check((ctl(W).get('box') or {}).get('kind') == 'clear', "Clear asks", ctl(W).get('box'))
W.execute('pp("PAD1")')
check(store(W).get(1) is None and cell(W, 0, "type") is None, "Clear: slot 1 empty, its cell has no action", store(W).get(1))
W.execute('WowCompanion.padTabCtl.touchPos(3); pp("PAD3")')
check((ctl(W).get('carry') or {}).get('pos') == 3, "X on slot 3: carried", ctl(W).get('carry'))
W.execute('pp("PADDRIGHT"); pp("PADDRIGHT"); pp("PADDRIGHT"); pp("PAD1")')
check(store(W).get(6) == ('command', 'TARGETNEARESTENEMY') and store(W).get(3) == ('item', 118) and ctl(W).get('note') == 'swapped',
      "A on slot 6: the two swap", (store(W).get(3), store(W).get(6), ctl(W).get('note')))
W.execute('WowCompanion.padTabCtl.touchPos(6); WowCompanion.padTabCtl.touchCarry(); WowCompanion.padTabCtl.touchPos(1); '
          'WowCompanion.padTabCtl.touchPlace()')
check(store(W).get(1) == ('command', 'TARGETNEARESTENEMY') and store(W).get(6) is None and ctl(W).get('note') == 'moved',
      "by touch (padtab 3, a slot, padtab 4): moved onto the empty slot 1", (store(W).get(1), store(W).get(6)))
check(cell(W, 0, "macrotext") == "/targetenemy" and cell(W, 5, "type") is None, "the cells follow")
clean(W, "1-4")

# ================================================================ 5. a fight
W.execute('fightOn()')
before = store(W)
W.execute('WowCompanion.padTabCtl.touchPos(2); pp("PAD1")')
check(not ctl(W).get('chooser') and ctl(W).get('note') == 'combat', "in a fight A opens no chooser (refused, said)", ctl(W).get('note'))
W.execute('pp("PAD3")')
check(store(W) == before, "nothing changes in a fight")
W.execute('WowCompanion.padTabCtl.touchSpell(331)')
e, _ = bar(W)
check(store(W) == before and e.get(2, {}).get('q') == 1, "a touch put in a fight waits (the slot dimmed, the store unchanged)", e.get(2))
W.execute('fightOff()')
check(store(W).get(2) == ('spell', 331) and cell(W, 1, "spell") == 331, "the fight's end: it lands, the cell built", store(W).get(2))
clean(W, "5")

# ================================================================ 6. migration; the Spellbook's pick on a mixed bar
M = boot(pre='WowCompanionSpellsDB = { castBar = { ["Korvash-Realm"] = { 403, nil, 331 } } }')
who = M.eval('WHO .. "-" .. REALM')
if who != "Korvash-Realm":
    M = boot(pre='WowCompanionSpellsDB = { castBar = { ["%s"] = { 403, nil, 331 } } }' % who)
check(store(M) == {1: ('spell', 403), 3: ('spell', 331)}, "an old store of spell numbers: { k = spell, id } at the login", store(M))
M.execute('WowCompanion.castBar.put(2, "command", "DSMAP")')
M.execute('WowCompanion.castBarPick(); NOW = NOW + 0.1; WowCompanion.castBarSpell(324)')
check(store(M).get(4) == ('spell', 324) and store(M).get(2) == ('command', 'DSMAP'),
      "the Spellbook's pick: the first free slot (4), the command kept", store(M))
M.execute('WowCompanion.castBarPick(); NOW = NOW + 0.1; WowCompanion.castBarSpell(403)')
check(store(M).get(1) is None, "the same spell again: taken off (the toggle)", store(M))
clean(M, "6")

print("%d passed, %d failed" % (passes, fails))
sys.exit(1 if fails else 0)
