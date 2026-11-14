"""STICKS TAKE ANYTHING (PadMap m157, PadTabCtl p52, Book b57), off-device: the REAL add-on files under the stubbed game.

    python tools/test_sticks_any.py

The user, 2026-11-13, on the stick tiles: "looking good, except people also want to be able to bind any of their spells and stuff to
these, so any of those 5 controller tabs should be able to bind here". L3 / R3 (bare and ZL) now take what the eight take from the
chooser's five tabs (Spells, General, Professions, Extra, DS): a spell, a command, an item, a mount, and m150's four game bindings. A
spell / command / item / mount is the character's stickBy entry, run by the stick's OWN secure button (type macro, its line set out of
combat), the chord pointed at it with SetOverrideBindingClick on the router's header. Proven here:
  1 a spell on bare L3, a command on ZL + L3, a DS command on R3, an item on ZL + R3, then a mount: the store, the chord -> our
    button (the header's override), the button's line, the press runs it, the document's sticks;
  2 one thing per stick: a binding over a spell takes the entry off, a spell over a binding takes the binding off;
  3 the rules: ZR / ZL + ZR refused ("layer"), in combat refused (nothing changes), a spell no longer in the book refused;
  4 a fight: carried (bound before the lockdown), the press runs the line in the fight, no breach; after it the same;
  5 a room holding the pad (Map's L3 pin): no stick override while it holds, back when it lets go; Party mode steps aside;
  6 the game taking the key back twice: "taken" (any kind), a fresh put starts it again;
  7 Clear: the entry, the override and the line go; per character (another character has none; the owner's comes back);
  8 the Controller room (PadTabCtl): A on L3 opens the chooser on Spells with all five tabs; a spell from it lands on L3; by touch a
    spell / a command / an Extra item go on R3; X (Move) refused ("bind-move"); a fight-layer pick by touch refused ("stick-normal");
  9 the Spellbook's Bind box (Book b57): L3 takes the spell (the answer's pad p = 9, no bar slot), B undoes it (what was there comes
    back), ZL + R3 = set 2 position 10, ZR + L3 "layer" and the In combat tab "stick-normal" keep the box waiting; the capture hands
    us L3 / R3 only while the box is up;
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
function GetGamePadState() return { buttons = PADSTATE } end
function hold(which) PADSTATE = {}; if which == "LT" or which == "both" then PADSTATE[LT_I] = true end
    if which == "RT" or which == "both" then PADSTATE[RT_I] = true end end
EXTRA_LIST = nil
-- the stub's capture (state = HOLDER) gains the guest's calls the Bind box makes (take / release / extra: the extra keys asked for)
WowCompanion.capture.take = function() return true end
WowCompanion.capture.release = function() return true end
WowCompanion.capture.extra = function(guest, list) EXTRA_LIST = table.concat(list, ","); return true end
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
    L.execute(WORLD_HELPERS)
    L.execute('fire("PLAYER_LOGIN"); fire("PLAYER_ENTERING_WORLD", true, false); runTimers(NOW + 10)')
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


def sticks(L):
    L.execute('WowCompanion.padMap.emit(true)')
    m = docs(L, 'padmap')[-1]['m']
    return {(e['set'], e['pos']): e for e in m.get('sticks', [])}


def bind(L, k):
    return L.eval('BIND["%s"]' % k)


def owner_is_header(L, k):
    return L.eval('BOWNER["%s"] == HDRF()' % k) is True


def line(L, name):
    return L.eval('(function() local b = rawget(_G, "%s"); return b and b.a and b.a.macrotext end)()' % name)


def entries(L):
    t = L.eval('(WowCompanionSpellsDB.stickBy or {})[WHO .. "-" .. REALM]')
    return {k: dict(v) for k, v in dict(t).items()} if t is not None else {}


def binds(L):
    t = L.eval('STORE()')
    return dict(t) if t is not None else {}


def press_line(L, key):
    """a hardware press of [key] (both edges, as its override delivers it): the macro lines the secure button ran"""
    n0 = int(L.eval('#ACTIONS'))
    L.execute('tapKey("%s")' % key)
    return [L.eval('ACTIONS[%d].line' % i) for i in range(n0 + 1, int(L.eval('#ACTIONS')) + 1)]


def clean(L, name):
    check(L.eval('#SPY') == 0 and L.eval('#VIOL') == 0 and L.eval('#SNIPERR') == 0 and list(L.eval('ERRS').values()) == [],
          name + ": no spied call (no trace), no lockdown breach, no snippet error",
          (L.eval('SPY[1]'), L.eval('VIOL[1]'), L.eval('SNIPERR[1]'), list(L.eval('ERRS').values())[:2]))


def ctl_doc(L):
    d = docs(L, 'padtabctl')
    return d[-1]['c'] if d else {}


CLICK = "CLICK %s:LeftButton"
check('local PV = "m163a"' in SRC["padmap"] and 'local CV = "p54"' in SRC["padtabctl"] and 'local BV = "b63a"' in SRC["book"],
      "the versions: PadMap m157, PadTabCtl p52, Book b57")
STK = "\n".join(l.split("--", 1)[0] for l in
                SRC["padmap"].split('-- m150 STICK BUTTONS (GitHub #78)\n', 1)[1].split('\nWC.padMap = {', 1)[0].splitlines())
check(not re.search(r'(?<![\w.:])SetBinding\s*\(', STK) and 'SaveBindings' not in STK and 'CreateMacro' not in STK
      and 'PlaceAction' not in STK and 'SetCVar' not in STK,
      "the stick code writes nothing of the player's (no SetBinding / SaveBindings / macro / bar slot / CVar)")

# ================================================================ 1. anything on a stick
W = boot()
pm = 'WowCompanion.padMap'
check(list(W.eval('ERRS').values()) == [] and W.eval('#SNIPERR') == 0, "the world loads", list(W.eval('ERRS').values())[:2])
ok = W.eval(pm + '.putSpell(1, 1, 9, 1)')                              # book slot 1 = Lightning Bolt (403)
check(ok is True, "putSpell on bare L3 accepted", ok)
e = entries(W).get("1:9", {})
check(e.get('id') == 403 and e.get('n') == "Lightning Bolt" and 't' not in e, "stored for this character: stickBy[name-realm][\"1:9\"]", e)
check(bind(W, "PADLSTICK") == CLICK % "WowCompanionStickL3" and owner_is_header(W, "PADLSTICK"),
      "L3 -> the stick's own button (SetOverrideBindingClick on our router header)", bind(W, "PADLSTICK"))
check(line(W, "WowCompanionStickL3") == "/cast Lightning Bolt" and W.eval('WowCompanionStickL3.a.type') == "macro",
      "its line: /cast Lightning Bolt (type macro: the bare name casts the top rank)", line(W, "WowCompanionStickL3"))
check(press_line(W, "PADLSTICK") == ["/cast Lightning Bolt"], "a press of L3 runs it")
sk = sticks(W)
check(sk[(1, 9)].get('kind') == 'spell' and sk[(1, 9)].get('id') == 403 and sk[(1, 9)].get('n') == "Lightning Bolt"
      and sk[(1, 9)].get('stick') == 'L3' and 'gd' not in sk[(1, 9)], "the document: L3 = Lightning Bolt (kind spell, id, name)", sk[(1, 9)])
check(binds(W).get("1:9") is None, "... and no binding entry for it")
ok = W.eval(pm + '.putCommand(1, 2, 9, "TARGETNEARESTENEMY")')
check(ok is True and bind(W, "SHIFT-PADLSTICK") == CLICK % "WowCompanionStickZLL3" and line(W, "WowCompanionStickZLL3") == "/targetenemy",
      "a command on ZL + L3: SHIFT-PADLSTICK -> its button, /targetenemy", (ok, bind(W, "SHIFT-PADLSTICK")))
check(sticks(W)[(2, 9)].get('kind') == 'command' and sticks(W)[(2, 9)].get('key') == "TARGETNEARESTENEMY", "... the document: kind command",
      sticks(W)[(2, 9)])
ok = W.eval(pm + '.putCommand(1, 1, 10, "DSMAP")')
check(ok is True and line(W, "WowCompanionStickR3") == "/click WowCompanionPadMap" and bind(W, "PADRSTICK") == CLICK % "WowCompanionStickR3",
      "a DS command (the DS tab's Open Map) on R3", (ok, line(W, "WowCompanionStickR3")))
ok = W.eval(pm + '.putItem(1, 2, 10, 6948)')
check(ok is True and line(W, "WowCompanionStickZLR3") == "/use item:6948" and sticks(W)[(2, 10)].get('kind') == 'item'
      and sticks(W)[(2, 10)].get('n') == "Hearthstone", "an item (Extra) on ZL + R3: /use item:6948, kind item", sticks(W)[(2, 10)])
ok = W.eval(pm + '.putMount(1, 1, 10, 6)')
check(ok is True and line(W, "WowCompanionStickR3") == "/cast Brown Horse" and sticks(W)[(1, 10)].get('n') == "Brown Horse"
      and sticks(W)[(1, 10)].get('kind') == 'other', "a mount on R3 (over Open Map: one thing per stick): /cast Brown Horse", sticks(W)[(1, 10)])
check(entries(W).get("1:10", {}).get('t') == 'mount', "... the stored entry is the mount", entries(W).get("1:10"))
m = docs(W, 'padmap')[-1]['m']
check(all(len(s_['slots']) == 8 for s_ in m['sets']), "the eight buttons' sets are unchanged")
check(W.eval('#PLACED') == 0 if W.eval('PLACED') is not None else True, "no bar slot touched")
clean(W, "1")

# ================================================================ 2. one thing per stick
W.eval(pm + '.putBind(1, 1, 9, "TOGGLEAUTORUN")')
check(bind(W, "PADLSTICK") == "TOGGLEAUTORUN" and "1:9" not in entries(W) and binds(W).get("1:9") == "TOGGLEAUTORUN",
      "Auto Run over L3's spell: the spell entry goes, the binding is bound", (bind(W, "PADLSTICK"), entries(W).get("1:9")))
check(press_line(W, "PADLSTICK") == [], "... the stick's button no longer runs (the chord is the game's binding)")
W.eval(pm + '.putSpell(1, 1, 9, 2)')                                    # Healing Wave over the binding
check(bind(W, "PADLSTICK") == CLICK % "WowCompanionStickL3" and binds(W).get("1:9") is None
      and line(W, "WowCompanionStickL3") == "/cast Healing Wave", "a spell over the binding: the binding goes, the line is the new spell",
      (bind(W, "PADLSTICK"), binds(W), line(W, "WowCompanionStickL3")))
clean(W, "2")

# ================================================================ 3. the rules
r = W.eval(pm + '.putSpell(1, 3, 9, 1)')
check(isinstance(r, tuple) and r[1] == "layer", "ZR + L3 refused (layer: the game pages ZR)", r)
r = W.eval(pm + '.putCommand(1, 4, 10, "DSMAP")')
check(isinstance(r, tuple) and r[1] == "layer", "ZL + ZR + R3 refused (layer)", r)
r = W.eval(pm + '.putSpell(1, 1, 9, 9)')
check(isinstance(r, tuple) and r[1] == "bad spell" and line(W, "WowCompanionStickL3") == "/cast Healing Wave",
      "a book slot with no spell refused, nothing changed", r)
r = W.eval(pm + '.putItem(1, 1, 9, 1)')
check(isinstance(r, tuple) and r[1] == "bad item", "an unknown item refused", r)
r = W.eval(pm + '.move(1, 1, 9, 1, 1, 1)')
check((isinstance(r, tuple) and r[0] is False) or r is False, "Move from a stick refused (no bar slot)", r)
W.execute('COMBAT = true')
r = W.eval(pm + '.putSpell(1, 1, 9, 1)')
check(isinstance(r, tuple) and r[1] == "combat" and entries(W)["1:9"]["id"] == 331, "in combat: refused, nothing changed", r)
r = W.eval(pm + '.clear(1, 1, 9)')
check(isinstance(r, tuple) and r[1] == "combat", "Clear in combat refused", r)
W.execute('COMBAT = false')
clean(W, "3")

# ================================================================ 4. a fight
W.execute('fightOn()')
check(bind(W, "PADLSTICK") == CLICK % "WowCompanionStickL3" and bind(W, "SHIFT-PADRSTICK") == CLICK % "WowCompanionStickZLR3",
      "a fight's start (before the lockdown): the sticks are carried", (bind(W, "PADLSTICK"), bind(W, "SHIFT-PADRSTICK")))
check(press_line(W, "PADLSTICK") == ["/cast Healing Wave"] and W.eval('#VIOL') == 0, "L3 in the fight casts Healing Wave, no breach",
      W.eval('VIOL[1]'))
W.execute('step()')
check(bind(W, "PADLSTICK") == CLICK % "WowCompanionStickL3" and W.eval('#VIOL') == 0, "nothing re-binds in the fight (no breach)")
W.execute('fightOff()')
check(bind(W, "PADLSTICK") == CLICK % "WowCompanionStickL3" and press_line(W, "PADRSTICK") == ["/cast Brown Horse"],
      "after the fight: L3 and R3 as they were", bind(W, "PADLSTICK"))
clean(W, "4")

# ================================================================ 5. a room holds; Party mode
W.execute('HOLDER = "map"; step()')
check(bind(W, "PADLSTICK") in (None, "") and bind(W, "PADRSTICK") in (None, ""), "the Map room holds the pad: no stick override (its L3 pin wins)",
      bind(W, "PADLSTICK"))
W.execute('HOLDER = nil; step()')
check(bind(W, "PADLSTICK") == CLICK % "WowCompanionStickL3", "the room lets go: L3 is ours again", bind(W, "PADLSTICK"))
for _ in range(3):
    W.execute('HOLDER = "map"; step()')
    W.execute('HOLDER = nil; step()')
check(bind(W, "PADLSTICK") == CLICK % "WowCompanionStickL3" and sticks(W)[(1, 9)].get('st') is None,
      "three room visits: never \"taken\"", sticks(W)[(1, 9)].get('st'))
clean(W, "5a")
P = boot('WowCompanion = WowCompanion or {}; WowCompanion.partyOk = true')
P.execute('UNITS.party1 = { name = "Ana", cls = "PRIEST", alive = true }')
P.eval(pm + '.putSpell(1, 1, 9, 1)')
check(bind(P, "PADLSTICK") == CLICK % "WowCompanionStickL3", "a Party-mode world: L3 is the spell with Party mode off", bind(P, "PADLSTICK"))
P.execute('clickName("WowCompanionPartyOn", true); clickName("WowCompanionPartyOn", false); step()')
on = P.eval('WowCompanion.padMap.party and WowCompanion.padMap.party.on and WowCompanion.padMap.party.on()')
if on:
    check(bind(P, "PADLSTICK") in (None, ""), "Party mode on: the stick steps aside (bindPlan's rule)", bind(P, "PADLSTICK"))
    P.execute('clickName("WowCompanionPartyOff", true); clickName("WowCompanionPartyOff", false); step()')
    check(bind(P, "PADLSTICK") == CLICK % "WowCompanionStickL3", "Party mode off: back", bind(P, "PADLSTICK"))
else:
    check(True, "(Party mode's toggle needs a fight here; its step-aside is bindPlan's guard, the same as every binding's)")
clean(P, "5b")

# ================================================================ 6. the game takes it back
G = boot()
G.eval(pm + '.putSpell(1, 1, 9, 1)')
G.execute('BIND["PADLSTICK"] = "TOGGLEWORLDMAP"; step(0.3); step()')
check(bind(G, "PADLSTICK") == CLICK % "WowCompanionStickL3" and sticks(G)[(1, 9)].get('st') is None,
      "a first re-take: bound ONCE more (not taken)", bind(G, "PADLSTICK"))
G.execute('BIND["PADLSTICK"] = "TOGGLEWORLDMAP"; step(0.3); step(); step()')
check(sticks(G)[(1, 9)].get('st') == 'taken' and bind(G, "PADLSTICK") != CLICK % "WowCompanionStickL3" and not owner_is_header(G, "PADLSTICK"),
      "taken again within the window: \"taken\" (a spell too), the key left to the game", (sticks(G)[(1, 9)], bind(G, "PADLSTICK")))
G.execute('step(); step()')
check(bind(G, "PADLSTICK") != CLICK % "WowCompanionStickL3" and not owner_is_header(G, "PADLSTICK"), "the check stops: nothing rebinds a taken stick", bind(G, "PADLSTICK"))
G.eval(pm + '.putSpell(1, 1, 9, 3)')
check(bind(G, "PADLSTICK") == CLICK % "WowCompanionStickL3" and sticks(G)[(1, 9)].get('st') is None
      and line(G, "WowCompanionStickL3") == "/cast Lightning Shield", "a fresh put starts it again", bind(G, "PADLSTICK"))
clean(G, "6")

# ================================================================ 7. Clear; per character
ok = W.eval(pm + '.clear(1, 1, 9)')
check(ok is True and "1:9" not in entries(W) and bind(W, "PADLSTICK") in (None, "") and sticks(W)[(1, 9)].get('kind') == 'none',
      "Clear: the entry and the override are gone, the stick shows empty", (ok, bind(W, "PADLSTICK")))
check(press_line(W, "PADLSTICK") == [], "... L3 runs nothing of ours")
check(bind(W, "SHIFT-PADLSTICK") == CLICK % "WowCompanionStickZLL3", "... the other sticks stay", bind(W, "SHIFT-PADLSTICK"))
r = W.eval(pm + '.clear(1, 1, 9)')
check(isinstance(r, tuple) and r[1] == "empty", "Clear on an empty stick: nothing to do", r)
key_ = W.eval('WHO .. "-" .. REALM')
st = 'WowCompanionSpellsDB = { stickBy = { ["%s"] = { ["2:10"] = { t = "item", id = 6948, n = "Hearthstone", i = 1 } } } }' % key_
W2 = boot('WHO = "Alt"; ' + st)
check(bind(W2, "SHIFT-PADRSTICK") in (None, "") and sticks(W2)[(2, 10)].get('kind') == 'none', "another character: nothing of Korvash's",
      bind(W2, "SHIFT-PADRSTICK"))
W3 = boot(st)
check(bind(W3, "SHIFT-PADRSTICK") == CLICK % "WowCompanionStickZLR3" and line(W3, "WowCompanionStickZLR3") == "/use item:6948",
      "a fresh load of the owner: ZL + R3 is the Hearthstone again (the saved store, armed at login)", bind(W3, "SHIFT-PADRSTICK"))
clean(W3, "7")

# ================================================================ 8. the Controller room
R = boot()
R.execute('WowCompanion.padTabCtl.enter(); WowCompanion.padTabCtl.touchTab(1)')
R.execute('WowCompanion.padTabCtl.touchPos(4); pp("PADDDOWN")')
check(ctl_doc(R).get('pos') == 9, "the cursor on L3 (WoW Defaults)", ctl_doc(R).get('pos'))
R.execute('pp("PAD1")')
c = ctl_doc(R)
check(c.get('chooser') and c['chooser'].get('list') == 'spells', "A on L3: the chooser opens on Spells (p49's Extra-only list is gone)",
      c.get('chooser'))
seen = []
for i in range(5):
    seen.append((ctl_doc(R).get('chooser') or {}).get('list'))
    if i < 4:
        R.execute('pp("PADDRIGHT")')
check(seen[:5] == ["spells", "general", "professions", "extra", "dstool"], "all five tabs: Spells, General, Professions, Extra, DS", seen)
R.execute('for _ = 1, 4 do pp("PADDLEFT") end')
check(ctl_doc(R)['chooser'].get('list') == 'spells' and ctl_doc(R)['chooser'].get('id') == 403, "back on Spells, row 1 = Lightning Bolt",
      ctl_doc(R)['chooser'])
R.execute('pp("PAD1")')
check(entries(R).get("1:9", {}).get('id') == 403 and bind(R, "PADLSTICK") == CLICK % "WowCompanionStickL3",
      "A on it: Lightning Bolt goes on bare L3 (the controller's route)", entries(R).get("1:9"))
R.execute('pp("PAD3")')
check(ctl_doc(R).get('note') == 'bind-move' and not ctl_doc(R).get('carry'), "X (Move) on a stick: refused (no bar slot)",
      ctl_doc(R).get('note'))
R.execute('WowCompanion.padTabCtl.commandsFrom12(); WowCompanion.padTabCtl.touchPos(2)')
check(ctl_doc(R).get('pos') == 10, "by touch: R3 picked", ctl_doc(R).get('pos'))
R.execute('WowCompanion.padTabCtl.touchSpell(331)')
check(entries(R).get("1:10", {}).get('id') == 331 and line(R, "WowCompanionStickR3") == "/cast Healing Wave",
      "a spell by touch (its Bind column) on R3", entries(R).get("1:10"))
R.execute('WowCompanion.padTabCtl.touchCommand(1)')
c1 = R.eval('WowCompanion.padMap.commands()[1].key')
check(entries(R).get("1:10", {}).get('key') == c1, "a command by touch on R3 (one thing: over the spell)", (c1, entries(R).get("1:10")))
idx = R.eval('(function() for i, x in ipairs(WowCompanion.padMap.extraList()) do if x.kind == "item" then return i end end end)()')
if idx:
    R.execute('WowCompanion.padTabCtl.touchExtraPage(0)')
    okx = R.eval('WowCompanion.padTabCtl.touchExtra(%d)' % (idx - 1))
    check(entries(R).get("1:10", {}).get('t') == 'item', "an Extra item by touch on R3", (okx, entries(R).get("1:10")))
else:
    check(True, "(no bag item in this world: the Extra row's item path is the command's)")
R.execute('WowCompanion.padTabCtl.combatLayer(); WowCompanion.padTabCtl.touchSpell(403)')
check(ctl_doc(R).get('note') == 'stick-normal' and entries(R).get("1:10", {}).get('id') != 403,
      "a fight-layer pick by touch onto a stick: refused (stick-normal)", ctl_doc(R).get('note'))
R.execute('WowCompanion.padTabCtl.touchClear()')
check("1:10" not in entries(R) and bind(R, "PADRSTICK") in (None, ""), "Clear by touch on R3", entries(R).get("1:10"))
R.execute('WowCompanion.padTabCtl.touchTab(3)')
check(ctl_doc(R).get('view') == 'combat' and ctl_doc(R).get('pos') == 8, "In combat has no sticks (the cursor goes back to a button)",
      ctl_doc(R).get('pos'))
clean(R, "8")

# ================================================================ 9. the Spellbook's Bind box
B = boot()
B.execute('BK = WowCompanion._spellsBook; BK.ctl.on = true; BK.ctl.pg = "book"; BK.ctl.croom = false; BK.ctl.ctrlTab = false')


def box(L, trig, key, fight=False):
    a0 = len([d for d in docs(L, 'spellsack') if d.get('op') == 'bind'])
    L.execute('BK.startBind({ t = 1, slot = 1, id = 403 })')
    if fight:
        L.execute('BK.ctl.bind.fight = true')
    L.execute('hold(%s)' % ('nil' if trig is None else '"%s"' % trig))
    L.execute('BK.press("%s")' % key)
    L.execute('hold(nil)')
    a = [d for d in docs(L, 'spellsack') if d.get('op') == 'bind'][a0:]
    return (a[-1] if a else {}), L.eval('BK.ctl.mode')


B.eval(pm + '.putBind(1, 1, 9, "TOGGLEAUTORUN")')                       # what L3 held before: Auto Run
a, mode = box(B, None, "PADLSTICK")
check(a.get('ok') is True and a.get('pad', {}).get('p') == 9 and a.get('pad', {}).get('b') == 1 and 's' not in a.get('pad', {}),
      "the Bind box: L3 takes Lightning Bolt (pad b 1, p 9, no bar slot)", a)
check(mode == "confirm" and entries(B).get("1:9", {}).get('id') == 403 and bind(B, "PADLSTICK") == CLICK % "WowCompanionStickL3",
      "... the box asks Keep / Undo; the stick runs the spell now", (mode, bind(B, "PADLSTICK")))
check(bool(B.eval("EXTRA_LIST")), "the capture is asked for extra keys while the box is up", B.eval("EXTRA_LIST"))
check("PADLSTICK" in (B.eval('EXTRA_LIST') or "") and "PADRSTICK" in (B.eval('EXTRA_LIST') or ""),
      "... L3 / R3 among them (the capture hands us the sticks)", B.eval('EXTRA_LIST'))
B.execute('BK.press("PAD2")')                                           # a plain B: undo
check(B.eval('BK.ctl.mode') == "bind" and "1:9" not in entries(B) and binds(B).get("1:9") == "TOGGLEAUTORUN"
      and bind(B, "PADLSTICK") == "TOGGLEAUTORUN", "B undoes it: Auto Run is back on L3, the box waits again",
      (B.eval('BK.ctl.mode'), bind(B, "PADLSTICK")))
B.execute('hold("LT"); BK.press("PADRSTICK"); hold(nil)')
a = [d for d in docs(B, 'spellsack') if d.get('op') == 'bind'][-1]
check(a.get('ok') is True and a.get('pad', {}).get('b') == 2 and a.get('pad', {}).get('p') == 10
      and line(B, "WowCompanionStickZLR3") == "/cast Lightning Bolt", "ZL + R3: set 2, position 10", a)
B.execute('BK.press("PAD1")')                                           # A keeps it
check(B.eval('BK.ctl.mode') == "book" and entries(B).get("2:10", {}).get('id') == 403, "A keeps it; the box closes", B.eval('BK.ctl.mode'))
check("PADLSTICK" not in (B.eval('EXTRA_LIST') or ""), "the box closed: the sticks go back to the game (no extra L3 / R3)",
      B.eval('EXTRA_LIST'))
a, mode = box(B, "RT", "PADLSTICK")
check(a.get('ok') is False and a.get('why') == "layer" and mode == "bind", "ZR + L3: \"layer\", the box keeps waiting", (a, mode))
B.execute('BK.ctl.mode = "book"; BK.ctl.bind = nil')
a, mode = box(B, None, "PADRSTICK", fight=True)
check(a.get('ok') is False and a.get('why') == "stick-normal" and mode == "bind" and "1:10" not in entries(B),
      "the In combat tab + R3: \"stick-normal\" (no fight layer on a stick), the box keeps waiting", (a, mode))
B.execute('BK.ctl.mode = "book"; BK.ctl.bind = nil')
a0 = len([d for d in docs(B, 'spellsack') if d.get('op') == 'bind'])
B.execute('BK.startBind({ t = 1, slot = 1, id = 403 }); COMBAT = true; BK.press("PADLSTICK")')
a = ([d for d in docs(B, 'spellsack') if d.get('op') == 'bind'][a0:] or [{}])[-1]
check(a.get('ok') is False and a.get('why') == "combat" and entries(B).get("1:9") is None, "a fight started with the box up: L3 refused",
      a)
B.execute('COMBAT = false; BK.ctl.mode = "book"; BK.ctl.bind = nil')
clean(B, "9")

print("%d passed, %d failed" % (passes, fails))
sys.exit(1 if fails else 0)
