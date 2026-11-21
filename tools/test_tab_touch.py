"""A FINGER ON A TAB IS THE CURSOR'S TAB (the user, 2026-11-20: "when touching a tab in the controller menu and pressing down after it goes
back to the last tab that was open and goes down"; PadTabCtl p57, Book b72), off-device: the REAL add-on files under the stubbed game
(tools/test_controller_situations.py's boot: Safety, TapPad, Book, PadMap, PadTabCtl and the cast bar block of ActionBar).

    python tools/test_tab_touch.py

Proven here, each as "touch tab X, press Down: X's first row":
  a the Controller room's situation tabs (padtab 0 / 2 / 26 / 10): from the tab row, the chips, the controller, the bar - Down lands
    on the touched situation (its first row), never back on the tab the cursor was on;
  b the Controller room's CHOOSER tabs (Spells / General / Professions / Extra / Macros (p58): padtab 39, 38 then 6-10): the chooser's list is the
    touched one, the cursor on its tab, Down = its first row (the bug: Down went on in the list the cursor held); a tab a spells-only
    chooser does not show takes nothing; no chooser open: nothing;
  c the Spellbook room's tabs (padtab 39, 38 then 10 + line, 35 Professions, 36 Pet): ctl.tab on the touched line, its first page, the
    cursor on the tab row, Down = its first entry; nothing while the Bind box waits or the Controller room holds the controller;
  d the tags: PadTabCtl p57, Book b72.
Needs lupa. Exit code 1 on any failure."""
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
SIT = os.path.join(HERE, "test_controller_situations.py")
_src = open(SIT, encoding="utf-8").read()
# the situations test's helpers only (everything before its first section): boot, padtab, press, ctl, sctl, check
_g = {"__file__": SIT, "__name__": "test_controller_situations_helpers"}
exec(compile(_src[:_src.index("# ================================================================ a.")], SIT, "exec"), _g)
boot, padtab, press, ctl, sctl, SRC = _g["boot"], _g["padtab"], _g["press"], _g["ctl"], _g["sctl"], _g["SRC"]
fails = 0
passes = 0


def check(ok, name, got=''):
    global fails, passes
    print(('PASS ' if ok else 'FAIL ') + name + ((' -> ' + str(got)) if got != '' else ''))
    if ok:
        passes += 1
    else:
        fails += 1


TOP = {'normal': 'normal', 'castbar': 'normal', 'combat': 'combat', 'cbfight': 'combat', 'party': 'party', 'pbar': 'party',
       'radial': 'radial'}
TAB_COL = {'normal': 0, 'combat': 2, 'party': 26, 'radial': 10}


def room():
    R = boot()
    padtab(R, 39, 30, 39, 2)                                     # the bar on, Show in combat on (the app's room open)
    R.execute('WowCompanion.castBar.put(1, "spell", 403)')
    R.execute('WowCompanion.padTabCtl.enter("tabs")')
    return R


# ================================================================ a. the situation tabs
bad = []
for start, rb in (('normal', 0), ('combat', 1), ('party', 2), ('radial', 3)):
    for downs in range(0, 6):
        for tgt, col in TAB_COL.items():
            R = room()
            press(R, *(["PADRSHOULDER"] * rb + ["PADDDOWN"] * downs))
            was = ctl(R)
            padtab(R, col)
            touched = ctl(R)
            press(R, "PADDDOWN")
            d = ctl(R)
            if TOP.get(touched.get('view')) != tgt or TOP.get(d.get('view')) != tgt or touched.get('tcur') != d.get('tcur'):
                bad.append((was.get('view'), was.get('focus'), tgt, touched.get('view'), d.get('view'), d.get('focus')))
check(not bad, "a. touch a situation, press Down: the touched situation's rows (every start: tabs, chips, controller, bar; all four)",
      bad[:3])
R = room()
padtab(R, 2)
press(R, "PADDDOWN")
c = ctl(R)
check(c.get('view') == 'combat' and c.get('focus') == 'switches', "a. from the tab row: touch In combat, Down = In combat's first row "
      "(its chips)", (c.get('view'), c.get('focus')))

# ================================================================ b. the chooser's tabs
R = room()
press(R, "PADDDOWN", "PADDDOWN", "PADDDOWN")                  # Out of combat's buttons
R.execute('WowCompanion.padTabCtl.touchPos(4)')
press(R, "PAD1")                                              # A: the chooser, on Spells
c = ctl(R)
check((c.get('chooser') or {}).get('list') == 'spells', "b. A opens the chooser on Spells", c.get('chooser'))
press(R, "PADDRIGHT", "PADDRIGHT")                            # p58: the grid - the cursor on Spells' third cell
check((ctl(R).get('chooser') or {}).get('row') == 3 and (ctl(R).get('chooser') or {}).get('list') == 'spells',
      "b. ... the cursor on Spells' third cell", ctl(R).get('chooser'))
padtab(R, 39, 38, 9)                                          # a finger on Extra (CTABS 4)
c = ctl(R).get('chooser') or {}
check(c.get('list') == 'extra' and c.get('row') == 1 and c.get('tabs') is True,
      "b. padtab 39, 38 then 9: the chooser on Extra, its first row, the cursor on its tab", c)
press(R, "PADDDOWN")
c = ctl(R).get('chooser') or {}
check(c.get('list') == 'extra' and c.get('row') == 1 and not c.get('tabs'),
      "b. Down: Extra's FIRST row (not Spells' fourth: the bug)", c)
press(R, "PADDDOWN")
check((ctl(R).get('chooser') or {}).get('list') == 'extra' and (ctl(R).get('chooser') or {}).get('row') == 2,
      "b. Down again: Extra's second row", ctl(R).get('chooser'))
for k, key in enumerate(("spells", "general", "professions", "extra", "macros"), start=1):
    padtab(R, 39, 38, 5 + k)
    press(R, "PADDDOWN")
    c = ctl(R).get('chooser') or {}
    check(c.get('list') == key and c.get('row') == 1, "b. touch %s, Down: %s's first row" % (key, key), c)
press(R, "PADDUP")                                            # back on the tab row
press(R, "PADDLEFT")
check((ctl(R).get('chooser') or {}).get('list') == 'extra', "b. the controller's Left from the touched tab: its neighbour (Extra)",
      ctl(R).get('chooser'))
# no chooser open: nothing
R = room()
press(R, "PADDDOWN", "PADDDOWN", "PADDDOWN")
before = ctl(R)
padtab(R, 39, 38, 7)
after = ctl(R)
check(after.get('chooser') is None and after.get('view') == before.get('view') and after.get('focus') == before.get('focus'),
      "b. no chooser open: a chooser tab code changes nothing", (after.get('chooser'), after.get('focus')))

# ================================================================ c. the Spellbook room's tabs
BOOKSTUB = r'''
SPELLNAME[133] = SPELLNAME[133] or "Fireball"; SPELLNAME[116] = SPELLNAME[116] or "Frostbolt"; SPELLNAME[2136] = SPELLNAME[2136] or "Fire Blast"
TT_BOOK = { 133, 2136, 116 }
TT_LINES = { { name = "General", iconID = 1, itemIndexOffset = 0, numSpellBookItems = 1, shouldHide = false },
             { name = "Fire", iconID = 2, itemIndexOffset = 1, numSpellBookItems = 1, shouldHide = false },
             { name = "Frost", iconID = 3, itemIndexOffset = 2, numSpellBookItems = 1, shouldHide = false } }
C_SpellBook = C_SpellBook or {}
C_SpellBook.GetNumSpellBookSkillLines = function() return #TT_LINES end
C_SpellBook.GetSpellBookSkillLineInfo = function(k) return TT_LINES[k] end
local tt_info = C_SpellBook.GetSpellBookItemInfo
C_SpellBook.GetSpellBookItemInfo = function(slot, bank)
    if bank == 1 then return tt_info and tt_info(slot, bank) or nil end
    local id = TT_BOOK[slot]
    return id and { spellID = id, actionID = id, name = SPELLNAME[id], itemType = 1, iconID = 130000 + id, isPassive = false, subName = "" } or nil
end
C_SpellBook.IsSpellBookItemLowRank = C_SpellBook.IsSpellBookItemLowRank or function() return false end
C_SpellBook.IsSpellBookItemLooseFlyoutMember = C_SpellBook.IsSpellBookItemLooseFlyoutMember or function() return false end
'''
B = boot(BOOKSTUB)
B.execute('BK = WowCompanion._spellsBook; BK.ctl.on = true; BK.ctl.pg = "book"; BK.ctl.croom = false; BK.ctl.ctrlTab = false; '
          'BK.ctl.mode = "book"')
T = [t for t in B.eval('(function() local out = {}; for i, t in ipairs(BK.tabs()) do out[i] = t.k end; return out end)()').values()]
check(len(T) >= 2, "c. the stubbed book has two tabs or more", T)
if len(T) >= 2:
    first, last = T[0], T[-1]
    B.execute('BK.ctl.onTabs = false; BK.ctl.tab = 1; BK.ctl.page = 1; BK.ctl.idx = 1')
    B.execute('BK.press("PADDDOWN")')                         # the cursor down the first tab
    col = 10 + last if last <= 24 else (35 if last == 100 else 36)
    padtab(B, 39, 38, col)
    c = sctl(B)
    check(c.get('line') == last and c.get('tabs') is True and c.get('page') == 1 and c.get('idx') == 1,
          "c. padtab 39, 38 then %d: the book on line %s, page 1, the cursor on the tab row" % (col, last), c)
    B.execute('BK.press("PADDDOWN")')
    c = sctl(B)
    check(c.get('line') == last and not c.get('tabs') and c.get('idx') == 1, "c. Down: line %s's FIRST entry (not back on line %s)"
          % (last, first), c)
    col1 = 10 + first if first <= 24 else (35 if first == 100 else 36)
    t0 = B.eval('BK.ctl.tab')
    B.execute('BK.ctl.mode = "bind"')
    padtab(B, 39, 38, col1)
    check(B.eval('BK.ctl.tab') == t0, "c. the Bind box waiting: a tab code changes nothing", B.eval('BK.ctl.tab'))
    B.execute('BK.ctl.mode = "book"; BK.ctl.croom = true')
    t0 = B.eval('BK.ctl.tab')
    padtab(B, 39, 38, col1)
    check(B.eval('BK.ctl.tab') == t0, "c. the Controller room holding the controller: a book tab code changes nothing", B.eval('BK.ctl.tab'))

# ================================================================ d. the tags
check('local CV = "p58"' in SRC["padtabctl"] and "p57 A FINGER ON A CHOOSER TAB" in SRC["padtabctl"], "d. PadTabCtl p57 and its note")
check('local BV = "b76"' in SRC["book"] and "b72 A FINGER ON A TAB MOVES THE CURSOR'S TAB TOO" in SRC["book"], "d. Book b72 and its note")

print("\n%d passed, %d failed" % (passes, fails))
sys.exit(1 if fails else 0)
