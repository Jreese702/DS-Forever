"""Several spell radials (Radial.lua q4 (q3 + learned lengths), PadTabCtl p49, Book b53, PadMap m150, Hub hub47; first q2 / p40 / b49 / m105 / hub26 in 1.0.18),
off-device: the REAL add-on files under tools/wowstub.py's stubbed game and test_party.py's WORLD_HELPERS, Lua 5.1 as the game's.
The radial ops by touch: padtab 39 (the one-shot pick) then code 5 + op (Book b53; 38 is rank up now).

    python tools/test_sradials.py

The user's picks 2026-11-08: radials made on the Controller room's Spell radial tab and picked from a list; each its own bar command; up
to 8 spells, no gaps (evenly spread by the app); A on a radial's plate = its settings; Delete = A, then A again. Exit code 1 on a failure."""
import json
import os
import re
import sys

from lupa import lua51 as lupa

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ADDONS = os.path.join(ROOT, "companion", "app", "src", "main", "assets", "companion", "addons")
PARTY = open(os.path.join(ROOT, "tools", "test_party.py"), encoding="utf-8").read()
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from wowstub import GAME  # noqa: E402  (the stubbed game moved to tools/wowstub.py 2026-11-11)
HELPERS = re.search(r"^WORLD_HELPERS = r'''\n(.*?)^'''", PARTY, re.S | re.M).group(1)
FILES = [("WowCompanion", "Safety.lua"), ("WowCompanion", "TapPad.lua"), ("WowCompanionSpells", "Book.lua"),
         ("WowCompanionSpells", "PadMap.lua"), ("WowCompanionSpells", "PadTabCtl.lua"), ("WowCompanionSpells", "Radial.lua"),
         ("WowCompanionHub", "Hub.lua")]
fails = passes = 0


def check(ok, name, got=''):
    global fails, passes
    if ok:
        passes += 1; print("PASS", name)
    else:
        fails += 1; print("FAIL", name, got)


def world(pre=''):
    L = lupa.LuaRuntime(unpack_returned_tuples=True)
    L.execute(GAME)
    L.execute('WowCompanion.partyOk = true; ' + pre)
    load = L.eval('function(src, name) local f, e = loadstring(src, name); if not f then error(e) end; return f end')
    for d, f in FILES:
        path = os.path.join(ADDONS, d, f)
        if not os.path.exists(path):
            continue
        try:
            load(open(path, encoding="utf-8").read(), '@' + f)(f)
        except Exception as e:                      # a file the stub world cannot run (Hub's frames) is skipped, said once
            print("note: %s did not load in the stub world: %s" % (f, str(e)[:120]))
    L.execute(HELPERS)
    L.execute('fire("PLAYER_LOGIN"); fire("PLAYER_ENTERING_WORLD", true, false); runTimers(NOW + 10)')
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


def last(L, typ):
    d = docs(L, typ)
    return d[-1] if d else None


def ctl(L):
    d = last(L, 'padtabctl')
    return d and d.get('c') or {}


def lua_list(t):
    return [t[i] for i in range(1, len(t) + 1)] if t is not None else []


# ---------------------------------------------------------------- 1. migration from q1's single radial (places 1-24, 3 pages)
W = world(pre='WowCompanionSpellsDB.sradial = { ["Korvash-Realm"] = { [1] = 403, [3] = 331, [9] = 324 } }')
SR = 'WowCompanion.spellRadial'
check(W.eval(SR + '.version') == 2, "q2's API is there")
slots = lua_list(W.eval(SR + '.slots()'))
check(slots == [1, 2], "q1's two pages with spells became radials 1 and 2", slots)
check(lua_list(W.eval(SR + '.ids(1)')) == [403, 331] and lua_list(W.eval(SR + '.ids(2)')) == [324],
      "each page's spells in place order, the gap closed", (lua_list(W.eval(SR + '.ids(1)')), lua_list(W.eval(SR + '.ids(2)'))))

# ---------------------------------------------------------------- 2. the API: put / clear / move / new / drop
W = world()
check(lua_list(W.eval(SR + '.slots()')) == [1] and W.eval(SR + '.count(1)') == 0, "a fresh character: one empty radial")
W.execute(SR + '.put(1, 1, 403); ' + SR + '.put(1, 2, 331); ' + SR + '.put(1, 3, 324)')
check(lua_list(W.eval(SR + '.ids(1)')) == [403, 331, 324], "put at count + 1 adds at the end")
ok, why = W.eval(SR + '.put(1, 5, 8042)')
check(ok is False and why == "place", "a put past the end + 1 is refused (no gaps)", (ok, why))
W.execute(SR + '.put(1, 3, 403)')
check(lua_list(W.eval(SR + '.ids(1)')) == [324, 331, 403], "a spell already on the radial swaps places (one place per spell)",
      lua_list(W.eval(SR + '.ids(1)')))
W.execute(SR + '.clear(1, 1)')
check(lua_list(W.eval(SR + '.ids(1)')) == [331, 403], "clear closes the gap")
W.execute(SR + '.move(1, 1, 2)')
check(lua_list(W.eval(SR + '.ids(1)')) == [403, 331], "move swaps")
r2 = W.eval(SR + '.new()')
check(r2 == 2 and W.eval(SR + '.has(2)') is True, "new: the first free slot", r2)
for _ in range(4):
    W.eval(SR + '.new()')
r7, why = W.eval(SR + '.new()')
check(r7 is None and why == "max", "a 7th radial is refused", (r7, why))
W.execute('COMBAT = true')
ok, why = W.eval(SR + '.put(2, 1, 403)')
check(ok is False and why == "combat", "no change in a fight (the cells freeze)", (ok, why))
W.execute('COMBAT = false')
W.execute(SR + '.drop(3)')
check(lua_list(W.eval(SR + '.slots()')) == [1, 2, 4, 5, 6], "drop frees its slot; the others keep theirs (their bar commands stay)")
check(W.eval(SR + '.new()') == 3, "the next new radial takes the freed slot")
# 40 shared cells: a 41st DIFFERENT spell is refused, the same spell on another radial is not
W2 = world()
W2.execute('for r = 2, 6 do %s.new() end' % SR)
W2.execute('for i = 1, 40 do local r = math.floor((i - 1) / 8) + 1; %s.put(r, ((i - 1) %% 8) + 1, 1000 + i) end' % SR)
ok, why = W2.eval(SR + '.put(6, 1, 9999)')
check(ok is False and why == "full", "a 41st different spell is refused (40 cells)", (ok, why))
ok = W2.eval(SR + '.put(6, 1, 1001)')
check(ok is True, "the same spell on a second radial shares its cell", ok)

# ---------------------------------------------------------------- 3. the document
W.execute('runTimers(NOW + 5); %s.emit(true)' % SR)
d = last(W, 'sradial')
check(d is not None and d.get('v') == 'q4' and isinstance(d['r'].get('list'), list), "the sradial document has the list", d and d.get('v'))
lst = d['r']['list']
check([x['u'] for x in lst] == [1, 2, 3, 4, 5, 6] and [i['id'] for i in lst[0]['items']] == [403, 331],
      "each radial by slot, its items in order", [x['u'] for x in lst])
check(d['r'].get('cells') == 40 and d['r'].get('max') == 6 and d['r'].get('per') == 8, "40 cells, 6 radials, 8 each")

# ---------------------------------------------------------------- 4. the commands (PadMap m105): one per radial, while it exists
W3 = world()
pm = last(W3, 'padmap')
keys = [c.get('key') for c in ((pm or {}).get('m') or {}).get('commands', [])] if pm else []
check('DSSRADIAL' in keys and 'DSSRADIAL2' not in keys, "one radial: only the first's command is listed", [k for k in keys if k and k.startswith('DSSR')])
W3.execute(SR + '.new(); runTimers(NOW + 5)')
pm = last(W3, 'padmap')
keys = [c.get('key') for c in ((pm or {}).get('m') or {}).get('commands', [])] if pm else []
check('DSSRADIAL2' in keys, "a new radial's command is listed at once (the padmap is sent again)", [k for k in keys if k and k.startswith('DSSR')])

# ---------------------------------------------------------------- 5. the Controller tab (PadTabCtl p40)
W4 = world()
W4.execute(SR + '.put(1, 1, 403); ' + SR + '.put(1, 2, 331)')
W4.execute('local ctl = WowCompanion._spellsBook.ctl; ctl.croom, ctl.ctrlTab, ctl.onTabs = true, true, true; '
           'WowCompanion.padTabCtl.enter("tabs")')
pp = lambda k: W4.execute('WowCompanion.padTabCtl.press("%s")' % k)
W4.execute('WowCompanion.padTabCtl.touchTab(4)')
pp('PADDDOWN')
c = ctl(W4)
check(c.get('view') == 'radial' and c.get('focus') == 'rpages' and c.get('rslots') == [1] and c.get('rpage') == 1,
      "the radial tab opens on the plates, radial 1 shown", (c.get('view'), c.get('focus'), c.get('rslots'), c.get('rpage')))
check(c.get('rad') == [403, 331], "its spells in order", c.get('rad'))
pp('PADDRIGHT')
c = ctl(W4)
check(c.get('rplate') == 2 and c.get('rpage') == 1, "Right lands on New (nothing made, radial 1 still shown)", (c.get('rplate'), c.get('rpage')))
pp('PAD1')
c = ctl(W4)
check(c.get('rslots') == [1, 2] and c.get('rpage') == 2 and c.get('focus') == 'buttons' and c.get('note') == 'rnew',
      "A on New makes radial 2, shows it, the cursor on its wheel", (c.get('rslots'), c.get('rpage'), c.get('focus'), c.get('note')))
pp('PADDUP'); pp('PADDLEFT')
c = ctl(W4)
check(c.get('focus') == 'rpages' and c.get('rpage') == 1, "Up to the plates, Left lands on radial 1 (shown)", (c.get('focus'), c.get('rpage')))
pp('PADDDOWN')
c = ctl(W4)
check(c.get('focus') == 'buttons' and c.get('pos') == 1, "Down = its wheel", (c.get('focus'), c.get('pos')))
pp('PADDRIGHT'); pp('PADDRIGHT')
c = ctl(W4)
check(c.get('pos') == 3, "Right round the wheel: 2 spells + the \"+\" = 3 places", c.get('pos'))
pp('PADDRIGHT')
check(ctl(W4).get('pos') == 1, "it wraps (3 places)")
pp('PADDUP'); pp('PAD1')
c = ctl(W4)
check(c.get('focus') == 'rset' and c.get('rrow') == 1, "A on a radial's plate = its settings, row 1 (Name)", (c.get('focus'), c.get('rrow')))
n0 = c.get('revn')
pp('PAD1')
c = ctl(W4)
check(c.get('rbox') == 'rname' and c.get('rev') == 'name' and c.get('revn') == n0 + 1, "A on Name opens the app's keyboard box (an event)",
      (c.get('rbox'), c.get('rev'), c.get('revn')))
pp('PADDUP')
c = ctl(W4)
check(c.get('rev') == 'key:PADDUP' and c.get('focus') == 'rset' and c.get('rbox') == 'rname', "in the box every press is the app's",
      (c.get('rev'), c.get('focus')))
W4.execute('WowCompanion.pad.rows.padtab.handler(nil, 39); WowCompanion.pad.rows.padtab.handler(nil, 18)')
c = ctl(W4)
check(c.get('rbox') is None and c.get('note') == 'rbox-done', "the app's \"done\" (padtab 39 then 18) closes the box", (c.get('rbox'), c.get('note')))
pp('PADDDOWN'); pp('PADDDOWN'); n1 = ctl(W4).get('revn'); pp('PAD1')
c = ctl(W4)
check(c.get('rrow') == 3 and c.get('rev') == 'mode' and c.get('revn') == n1 + 1, "Opening: A is an event the app acts on", (c.get('rrow'), c.get('rev')))
pp('PADDDOWN'); pp('PAD1')
c = ctl(W4)
check(c.get('rdel') is True and c.get('rslots') == [1, 2], "Delete: the first A only asks", (c.get('rdel'), c.get('rslots')))
pp('PAD1')
c = ctl(W4)
check(c.get('rslots') == [2] and c.get('rpage') == 2 and c.get('focus') == 'rpages' and c.get('note') == 'rdeleted',
      "A again within 3 s deletes it; radial 2 is shown", (c.get('rslots'), c.get('rpage'), c.get('focus')))
# by touch: padtab 39 then 11 = New; 39 then 5 = show radial 1 (if it exists)
W4.execute('WowCompanion.pad.rows.padtab.handler(nil, 39); WowCompanion.pad.rows.padtab.handler(nil, 11)')
check(ctl(W4).get('rslots') == [1, 2], "touch New (39, 11) remakes slot 1", ctl(W4).get('rslots'))
W4.execute('WowCompanion.pad.rows.padtab.handler(nil, 39); WowCompanion.pad.rows.padtab.handler(nil, 5)')
check(ctl(W4).get('rpage') == 1, "touch 39, 5 shows radial 1", ctl(W4).get('rpage'))
W4.execute('WowCompanion.pad.rows.padtab.handler(nil, 0)')
check(ctl(W4).get('view') == 'normal', "without the prefix column 0 is its plain self (the WoW Defaults tab)", ctl(W4).get('view'))
# the chooser puts a spell onto the "+"
W5 = world()
W5.execute('local ctl = WowCompanion._spellsBook.ctl; ctl.croom, ctl.ctrlTab, ctl.onTabs = true, true, true; '
           'WowCompanion.padTabCtl.enter("tabs"); WowCompanion.padTabCtl.touchTab(4)')
W5.execute('WowCompanion.padTabCtl.press("PADDDOWN"); WowCompanion.padTabCtl.press("PADDDOWN"); WowCompanion.padTabCtl.touchSpell(331)')
check(lua_list(W5.eval(SR + '.ids(1)')) == [331], "a spell picked on the \"+\" is added", lua_list(W5.eval(SR + '.ids(1)')))

# ---------------------------------------------------------------- 7. the touch codes: the app's and Book's agree (b53 / p49)
PTP = open(os.path.join(ROOT, "companion", "app", "src", "main", "java", "com", "jrees", "wowcompanion", "PadTabPage.kt"), encoding="utf-8").read()
BOOK = open(os.path.join(ADDONS, "WowCompanionSpells", "Book.lua"), encoding="utf-8").read()
check("const val RADIAL_PREFIX = 39" in PTP and "const val RADIAL_CODE = 5" in PTP and "col >= 5 and col <= 18" in BOOK
      and "touchRadialPrefix" not in BOOK,
      "the app sends padtab 39 + (5 + op), Book reads codes 5-18 after the pick; 38 stays rank up's")
W6 = world()
W6.execute('local ctl = WowCompanion._spellsBook.ctl; ctl.croom, ctl.ctrlTab, ctl.onTabs = true, true, true; '
           'WowCompanion.padTabCtl.enter("tabs"); WowCompanion.padTabCtl.touchTab(4)')
n_slots = lua_list(W6.eval(SR + '.slots()'))
W6.execute('WowCompanion.pad.rows.padtab.handler(nil, 39); WowCompanion.pad.rows.padtab.handler(nil, 2)')
check(W6.eval('WowCompanion.padTabCtl.srWants()') is not True and lua_list(W6.eval(SR + '.slots()')) == n_slots,
      "39 then 0-4 (Smart Range's old codes, unused since Book b61): the pick spent, no radial op")
W6.execute('WowCompanion.pad.rows.padtab.handler(nil, 11)')
check(lua_list(W6.eval(SR + '.slots()')) == n_slots, "a lone code 11 with no fresh pick is NOT a radial New", lua_list(W6.eval(SR + '.slots()')))
W6.execute('WowCompanion.pad.rows.padtab.handler(nil, 39); NOW = NOW + 3; WowCompanion.pad.rows.padtab.handler(nil, 11)')
check(lua_list(W6.eval(SR + '.slots()')) == n_slots, "a pick older than 2 s: the code is not a radial op", lua_list(W6.eval(SR + '.slots()')))
W6.execute('WowCompanion.pad.rows.padtab.handler(nil, 39); WowCompanion.pad.rows.padtab.handler(nil, 11)')
check(len(lua_list(W6.eval(SR + '.slots()'))) == len(n_slots) + 1, "39 then 11 (fresh): New", lua_list(W6.eval(SR + '.slots()')))

print("\n%d passed, %d failed" % (passes, fails))
sys.exit(1 if fails else 0)
