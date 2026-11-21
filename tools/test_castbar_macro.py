"""A PLAYER'S MACRO ON THE TAP-TO-CAST BAR (GitHub #553, a player: "I cannot assign macros to the bottom screen cast bar"; ActionBar cb13,
Book b69), off-device: the REAL add-on files of the Macros room's world (tools/test_macro_room.py's: Safety, TapPad, Chat, Book, PadMap,
PadTabCtl, Spells ...) plus the REAL cast bar block of ActionBar.lua (CASTBAR-BEGIN .. CASTBAR-END), under tools/wowstub.py's stubbed game.

    python tools/test_castbar_macro.py

Proven here:
  a versions + notes: ActionBar cb13, Book b69; the cast bar block never sets a saved-macro attribute, never writes a macro / binding / CVar;
  b the Macros room's Bind box (hosted, Book b63) for a macro shows the Cast bar tab (padtab 39 then 26: `bcb`), its D-pad walks the slots
    and A puts the macro BY NAME on the lit slot ({ k = "macro", n }), the box ends and the room is back on its grid;
  c the cell is OUR button: type macro, macrotext = the macro's own text (no saved-macro attribute); a tap runs that text;
  d the castbar document: k = "macro", n = its name, i = its icon, c = the cell; `mac` = 1 (the bar takes macros);
  e the macro edited elsewhere (UPDATE_MACROS out of combat): the cell runs the new text; renamed / deleted: `miss` = 1, no cell;
  f padtab 39 then 20-25 / 27-28 with the macro's box waiting: on that slot at once (the touch route);
  g a gone macro at the pick: "missing", nothing put; a name two macros share: "taken" (Book b63's checks);
  h in a fight the room's put waits (queued, dimmed) and lands at the fight's end, the cells untouched meanwhile;
  i an add-on without cb13 (no WC.castBarMacro): the macro's box shows no Cast bar tab and 39 then 26 does nothing (as b63);
  j no frame anywhere carries a saved-macro attribute;
plus clean: no lockdown breach, no macro write outside a tap, no snippet error. Needs lupa. Exit code 1 on any failure."""
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from wowstub import ADDONS, ADDON_OF, F, SRC, WORLD_FILES, boot_world, docs  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
for _k, (_d, _f) in {"chat": ("WowCompanion", "Chat.lua"), "spells": ("WowCompanionSpells", "Spells.lua")}.items():
    F[_k] = os.path.join(ADDONS, _d, _f)
    SRC[_k] = open(F[_k], encoding="utf-8").read()
    ADDON_OF[_k] = _d
FILES = tuple(list(WORLD_FILES[:3]) + ["chat"] + list(WORLD_FILES[3:7]) + ["spells"] + list(WORLD_FILES[7:]))
LOGIN = ('for _, a in ipairs({ "WowCompanion", "WowCompanionQuests", "WowCompanionHub", "WowCompanionInteract", "WowCompanionSpells" }) do '
         'fire("ADDON_LOADED", a) end; fire("PLAYER_LOGIN"); fire("PLAYER_ENTERING_WORLD", true, false); runTimers(NOW + 10)')
AB = open(os.path.join(ADDONS, "WowCompanion", "ActionBar.lua"), encoding="utf-8").read()
BLOCK = re.search(r'-- CASTBAR-BEGIN[^\n]*\n(.*?)-- CASTBAR-END', AB, re.S).group(1)
MR_SRC = open(os.path.join(ROOT, "tools", "test_macro_room.py"), encoding="utf-8").read()
MACROS = re.search(r"\nMACROS = r'''(.*?)'''", MR_SRC, re.S).group(1)
AFTER = re.search(r"\nAFTER = r'''(.*?)'''", MR_SRC, re.S).group(1)
BIND_PRE = re.search(r"\nBIND_PRE = r'''(.*?)'''", MR_SRC, re.S).group(1)
GEN = [("Alpha", "/wave"), ("Bravo", "/dance"), ("Charlie", "#showtooltip\n/cast Lightning Bolt")]
CHAR = [("Heal", "/cast Healing Wave")]
WHO = "Korvash-Realm"

fails = 0
passes = 0


def check(ok, name, got=''):
    global fails, passes
    print(('PASS ' if ok else 'FAIL ') + name + ((' -> ' + str(got)) if got != '' else ''))
    if ok:
        passes += 1
    else:
        fails += 1


def last(L, typ):
    d = docs(L, 0, typ)
    return d[-1] if d else {}


def press(L, k):
    L.eval('function(k) MRx.press(k) end')(k)
    L.execute('advance(0.1)')


def padtab(L, *cols):
    for c in cols:
        L.eval('hwTap')("padtab", c)
    L.execute('advance(0.3)')


def ctl(L):
    return last(L, 'spellsctl').get('ctl') or {}


def mdoc(L):
    L.execute('WowCompanion.macroRoom.emit()')
    return last(L, 'macros').get('m') or {}


def store(L):
    t = L.eval('(WowCompanionSpellsDB.castBar or {})["%s"]' % WHO)
    out = {}
    if t is None:
        return out
    for k, v in t.items():
        if v is None:
            continue
        out[int(k)] = (v['k'], v['n'] if v['k'] == 'macro' else (v['key'] if v['k'] == 'command' else int(v['id'])))
    return out


def cell(L, col, attr):
    return L.eval('(function() local r = WowCompanion.pad.rows.castbar; local c = r and r.cells[%d]; return c and c.a["%s"] end)()'
                  % (col, attr))


def bar(L):
    L.execute('WowCompanion.castBar.send(true)')
    d = last(L, 'castbar')
    return {e['s']: e for e in d.get('e', [])}, d


def clean(L):
    out = {}
    for k in ("VIOL", "OUTSIDE", "SNIPERR", "LOADERRS"):
        n = int(L.eval('#%s' % k))
        if n:
            out[k] = [L.eval('%s[%d]' % (k, i)) for i in range(1, n + 1)][:3]
    errs = list(L.eval('ERRS').values())
    if errs:
        out['ERRS'] = errs[:3]
    spy = [L.eval('SPY[%d]' % i) for i in range(1, int(L.eval('#SPY')) + 1)]
    if spy:
        out['SPY'] = spy[:5]
    return out


def seed(L, gen=(), char=()):
    for n, b in gen:
        L.eval('function(n, b) GENM[#GENM + 1] = { name = n, icon = 134400, body = b, seq = mseq() } end')(n, b)
    for n, b in char:
        L.eval('function(n, b) CHARM[#CHARM + 1] = { name = n, icon = 136243, body = b, seq = mseq() } end')(n, b)
    L.execute('sortT(GENM); sortT(CHARM)')


def world(gen=GEN, char=CHAR, cb13=True):
    L, errs = boot_world(files=FILES, pre=MACROS, login=LOGIN)
    L.execute(AFTER)
    L.execute('LOADERRS = {}')
    for e in errs:
        L.eval('function(s) LOADERRS[#LOADERRS + 1] = s end')(e)
    load = L.eval('function(src) local f, e = loadstring("local WC = _G.WowCompanion\\n" .. src, "@ActionBar.lua#castbar"); '
                  'if not f then error(e) end; return f end')
    load(BLOCK)()
    if not cb13:
        L.execute('WowCompanion.castBarMacro = nil')
    L.execute('fire("PLAYER_ENTERING_WORLD", false, false); runTimers(NOW + 4)')
    L.execute(BIND_PRE)
    seed(L, gen, char)
    L.execute('fire("UPDATE_MACROS"); advance(1)')
    padtab(L, 39, 34)                            # Book b63: the Macros room's lazy row
    L.eval('hwTap')("mac", L.eval('col')("h"))   # the room on show (its Home tap)
    L.execute('advance(0.3)')
    return L


def open_box(L, rights=1):
    """the grid's cursor [rights] to the right (Alpha -> Bravo), then X: the macro's Bind box"""
    for _ in range(rights):
        press(L, "PADDRIGHT")
    press(L, "PAD3")


# ================================================================ a. versions, notes, the block's code
check('local CBV = "cb16"' in BLOCK and "cb13 A PLAYER'S MACRO ON THE BAR (GitHub #553" in AB, "a. ActionBar cb13 and its header note")
check('local BV = "b76"' in SRC["book"] and 'b69 A MACRO ONTO THE CAST BAR (GitHub #553' in SRC["book"], "a. Book b69 and its note")
code = '\n'.join(l.split('--', 1)[0] for l in BLOCK.split('\n'))
bad = [w for w in ('SetAttribute("macro"', "SetAttribute('macro'", 'CreateMacro', 'EditMacro', 'DeleteMacro', 'SetBinding', 'SaveBindings',
                   'SetCVar', 'PickupMacro', 'PlaceAction', 'RunMacro') if w in code]
check(bad == [], "a. the cast bar block: no saved-macro attribute, no macro / binding / CVar / bar write", bad)

# ================================================================ b. the Macros room's Bind box -> its Cast bar tab
L = world()
check(clean(L) == {}, "b. the world loads clean", clean(L))
open_box(L)
c = ctl(L)
check(c.get('mode') == 'bind' and c.get('via') == 'macros' and (c.get('bitem') or {}).get('n') == 'Bravo',
      "b. X on Bravo: the macro's Bind box, hosted by the room", c)
check(c.get('bcb') is None, "b. ... on its Normal tab (no bcb yet)", c.get('bcb'))
padtab(L, 39, 26)
check(ctl(L).get('bcb') == 1, "b. padtab 39 then 26: the box's Cast bar tab, its cursor on slot 1", ctl(L).get('bcb'))
press(L, "PADDRIGHT")
check(ctl(L).get('bcb') == 2, "b. the D-pad Right: slot 2", ctl(L).get('bcb'))
press(L, "PAD1")
check(store(L).get(2) == ('macro', 'Bravo') and store(L).get(1) is None, "b. A: Bravo on slot 2, by name, stored per character", store(L))
ack = last(L, 'castbarack')
check(ack.get('ok') is True and ack.get('op') == 'put' and ack.get('s') == 2, "b. the bar's answer: put on slot 2", ack)
check(ctl(L).get('mode') != 'bind' and mdoc(L).get('mode') == 'grid', "b. the box ends; the room is back on its grid",
      (ctl(L).get('mode'), mdoc(L).get('mode')))

# ================================================================ c. the cell: OUR button, the macro's own text
check(cell(L, 1, "type") == "macro" and cell(L, 1, "macrotext") == "/dance" and cell(L, 1, "macro") is None,
      "c. slot 2's cell: type macro, macrotext = the macro's text, no saved-macro attribute",
      (cell(L, 1, "type"), cell(L, 1, "macrotext"), cell(L, 1, "macro")))
L.execute('local n0 = #ACTIONS; local c = WowCompanion.pad.rows.castbar.cells[1]; doClick(c, "LeftButton", true); '
          'doClick(c, "LeftButton", false); RAN = ACTIONS[n0 + 1]')
check(L.eval('RAN and RAN.line') == "/dance", "c. a tap on the cell runs the macro's text", L.eval('RAN and RAN.kind'))

# ================================================================ d. the document
e, d = bar(L)
check(d.get('v') == 'cb16' and d.get('mac') == 1 and d.get('any') == 1, "d. the document: cb13, mac = 1", (d.get('v'), d.get('mac')))
check(e[2].get('k') == 'macro' and e[2].get('n') == 'Bravo' and e[2].get('i') == 134400 and e[2].get('c') == 1 and 'miss' not in e[2]
      and 'id' not in e[2], "d. slot 2: k macro, its name, its icon, its cell", e[2])

# ================================================================ e. edited / renamed / deleted elsewhere
L.eval('function() local i = GetMacroIndexByName("Bravo"); GENM[i].body = "/cheer" end')()
L.execute('fire("UPDATE_MACROS"); advance(1)')
check(cell(L, 1, "macrotext") == "/cheer", "e. edited elsewhere: UPDATE_MACROS re-arms the cell with the new text", cell(L, 1, "macrotext"))
L.eval('function() local i = GetMacroIndexByName("Bravo"); GENM[i].name = "Zulu"; sortT(GENM) end')()
L.execute('fire("UPDATE_MACROS"); advance(1)')
e, d = bar(L)
check(e[2].get('miss') == 1 and e[2].get('c') is None and e[2].get('n') == 'Bravo' and cell(L, 1, "type") is None,
      "e. renamed away: the slot keeps the name, says miss, has no cell", (e[2], cell(L, 1, "type")))
L.eval('function() local i = GetMacroIndexByName("Zulu"); GENM[i].name = "Bravo"; sortT(GENM) end')()
L.execute('fire("UPDATE_MACROS"); advance(1)')
check(cell(L, 1, "macrotext") == "/cheer" and 'miss' not in bar(L)[0][2], "e. named back: the cell again", cell(L, 1, "macrotext"))
check(clean(L) == {}, "b-e. clean", clean(L))

# ================================================================ f. the touch route: padtab 39 then a slot code, the box waiting
L = world()
open_box(L, rights=2)                             # Alpha, Bravo, Charlie: Charlie's box
c = ctl(L)
name = (c.get('bitem') or {}).get('n')
check(c.get('mode') == 'bind' and name == 'Charlie', "f. Charlie's box is up", name)
padtab(L, 39, 26)
padtab(L, 39, 28)                                 # slot 8 (b57: 27-28 = slots 7-8)
check(store(L).get(8) == ('macro', name), "f. padtab 39 then 28: on slot 8 at once", store(L))
check(cell(L, 7, "type") == "macro" and cell(L, 7, "macrotext") == "#showtooltip\n/cast Lightning Bolt",
      "f. ... its cell runs that macro's whole text (its #showtooltip line too)", cell(L, 7, "macrotext"))
# a second macro onto slot 1, then the same macro onto slot 3 moves it (a put takes it off its other slot)
check(clean(L) == {}, "f. clean", clean(L))
L = world()
open_box(L, rights=1)                             # Bravo's box
nm2 = (ctl(L).get('bitem') or {}).get('n')
check(nm2 == 'Bravo', "f. Bravo's box", nm2)
padtab(L, 39, 26)
padtab(L, 39, 20)
check(store(L).get(1) == ('macro', nm2), "f. padtab 39 then 20: another macro on slot 1", store(L))
open_box(L, rights=0)                             # the room's grid keeps its cursor on Bravo
check((ctl(L).get('bitem') or {}).get('n') == 'Bravo', "f. Bravo's box again", ctl(L).get('bitem'))
padtab(L, 39, 26)
padtab(L, 39, 22)
check(store(L).get(3) == ('macro', nm2) and store(L).get(1) is None, "f. the same macro onto slot 3: moved off slot 1", store(L))
open_box(L, rights=0)
padtab(L, 39, 26)
padtab(L, 39, 22)
check(store(L).get(3) is None and last(L, 'castbarack').get('op') == 'clear',
      "f. picked again on its own slot: off it (as a spell's pick)", (store(L), last(L, 'castbarack')))
check(clean(L) == {}, "f. clean", clean(L))

# ================================================================ g. Book b63's checks at the pick
L = world()
open_box(L)
padtab(L, 39, 26)
L.eval('function() local i = GetMacroIndexByName("Bravo"); table.remove(GENM, i) end')()
press(L, "PAD1")
check(store(L) == {} and last(L, 'spellsack').get('why') == 'missing', "g. the macro gone at the pick: 'missing', nothing put",
      (store(L), last(L, 'spellsack')))
check(ctl(L).get('mode') != 'bind', "g. ... and the box ends", ctl(L).get('mode'))
L = world(gen=GEN + [("Bravo", "/flex")])
open_box(L)
padtab(L, 39, 26)
press(L, "PAD1")
taken = last(L, 'spellsack').get('why')
check(store(L) == {} and taken in ('taken', None), "g. a name two macros share: nothing put", (store(L), taken))
check(clean(L) == {}, "g. clean", clean(L))

# ================================================================ h. a fight
L = world()
L.execute('fire("PLAYER_REGEN_DISABLED"); COMBAT = true; advance(0.3)')   # the game's order: the event, then the lockdown
r = L.eval('WowCompanion.castBar.put(4, "macro", "Alpha")')
check(r is True and store(L) == {}, "h. in a fight: the put is queued, the store untouched", store(L))
e, d = bar(L)
check(e.get(4, {}).get('q') == 1 and e.get(4, {}).get('c') is None, "h. ... its slot dimmed, no cell", e.get(4))
L.execute('COMBAT = false; fire("PLAYER_REGEN_ENABLED"); advance(1)')
check(store(L).get(4) == ('macro', 'Alpha') and cell(L, 3, "macrotext") == "/wave", "h. the fight ends: it lands, the cell built",
      (store(L), cell(L, 3, "macrotext")))
check(L.eval('WowCompanion.castBar.put(5, "macro", "NoSuch")') is False and last(L, 'castbarack').get('why') == 'missing',
      "h. a put of no such macro is refused: missing", last(L, 'castbarack'))
check(clean(L) == {}, "h. clean", clean(L))

# ================================================================ i. an add-on without cb13
L = world(cb13=False)
open_box(L)
padtab(L, 39, 26)
check(ctl(L).get('bcb') is None and ctl(L).get('mode') == 'bind', "i. no WC.castBarMacro: no Cast bar tab for a macro (as b63)", ctl(L))
padtab(L, 39, 21)
check(store(L) == {}, "i. ... and a slot code puts nothing", store(L))

# ================================================================ j. no frame anywhere carries a saved-macro attribute
L = world()
open_box(L)
padtab(L, 39, 26)
press(L, "PAD1")
check(L.eval('(function() for _, f in ipairs(ALLF) do for k, v in pairs(f.a or {}) do if k == "macro" then return f._name or "?" end end end end)()') is None,
      "j. no frame carries a saved-macro attribute")
check(clean(L) == {}, "j. clean", clean(L))

print('%s (%d pass, %d fail)' % ('ALL PASS' if not fails else 'FAILURES', passes, fails))
sys.exit(1 if fails else 0)
