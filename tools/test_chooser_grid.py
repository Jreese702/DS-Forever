"""THE CHOOSER AS A GRID (the user, 2026-11-20: "when assigning skills in the controller mode I dont like the list for the skills, can we
make it better? maybe a grid similar to the AH ... the 5th tab could easily be a category in tab 4, we also need to add macros here as an
option"; PadTabCtl p58, Book b73, PadMap m177), off-device: the REAL add-on files (the cast bar block of ActionBar.lua, Book, PadMap,
PadTabCtl) under the stubbed game.

    python tools/test_chooser_grid.py

Proven here:
  a the tags and notes; the chooser's tabs are Spells, General, Professions, Extra, Macros (no "dstool"); the radial's / a combat
    button's chooser keeps the three spell tabs (no Macros);
  b GRID NAVIGATION (4 a row, as the app draws it): Right / Left a cell, Down / Up a row (the same column; a short row below = its last
    cell), Up off the first row = the tab row, Right past a row's end / Left past its start = the neighbour tab, Down off the last row =
    the pinned Clear, Up from Clear = the last row (the column it left);
  c CATEGORY FOLDING (Extra, the AH's): Commands, DS Forever, Controller, Mounts, Items headers, all folded at first; A on a header opens
    it (the doc's `xo`, the cursor stays on it, A again folds it); by touch padtab 39, 38, 37 then 3 + k folds / opens category k;
    the list's Up / Down and Left / Right (the tabs) as before;
  d THE DS COMMANDS UNDER EXTRA: the old 5th tab's DS Forever commands are Extra's "DS Forever" category, chosen there they go on the
    button as before (putCommand); padtab 39, 38 then 6-10 (the chooser tab touch, b72) maps tab 5 to Macros;
  e MACROS: the player's own saved macros (General then the character's; ours - "DS Combat ", "DS Attack ", "DSF ..." - and empty ones
    left out) in the padmap doc's `macs` and the Macros grid; A on one puts it by name - a WoW Defaults button by PadMap.putMacro, an
    In combat button by putFight "macro", the tap-to-cast bar by ActionBar's "macro" kind; by touch padtab 39, 38, 37 then 0-3 (the
    macro page) + the "extra" row's column; PadMap.putMacro onto a bar slot is the game's own cursor road (PickupMacro + PlaceAction,
    read back), never a macro written;
  f TAB TOUCH STATE: a finger on the Macros tab (39, 38 then 10) moves the controller's chooser there, its cursor on the tab row, Down =
    its first cell;
plus no spied call outside e's own bar-slot proof (no SetBinding, SaveBindings, macro, CVar or bar write). Needs lupa."""
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
from chooser_nav import Nav  # noqa: E402
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
-- the player's macros (General 1..n, the character's from 121): two of OURS and an empty one, which the chooser leaves out
MAXG = 120
GENM = { { name = "Bravo", icon = 134400, body = "/cheer" }, { name = "DSF Up", icon = 1, body = "/say mine" },
         { name = "Totems", icon = 135825, body = "/cast Searing Totem" }, { name = "Empty", icon = 1, body = "  " },
         { name = "Pull", icon = 132212, body = "/cast Lightning Bolt" }, { name = "Mount", icon = 132261, body = "/cast Brown Horse" },
         { name = "Wolf", icon = 136095, body = "/cast Ghost Wolf" }, { name = "Purge", icon = 136075, body = "/cast Purge" } }
CHARM = { { name = "Heal Me", icon = 136052, body = "/cast [@player] Healing Wave" }, { name = "DS Attack 12", icon = 1, body = "/startattack" } }
function GetNumMacros() return #GENM, #CHARM end
function GetMacroInfo(i)
    local m = (type(i) == "number") and ((i <= MAXG) and GENM[i] or CHARM[i - MAXG]) or nil
    if m then return m.name, m.icon, m.body end
end
function GetMacroIndexByName(n)
    for i, m in ipairs(GENM) do if m.name == n then return i end end
    for i, m in ipairs(CHARM) do if m.name == n then return MAXG + i end end
    return 0
end
function GetMacroSpell() return nil end
function GetMacroItem() return nil end
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


def ch(L):
    return ctl(L).get('chooser') or {}


def padtab(L, *cols):
    for c in cols:
        L.execute('WowCompanion.pad.rows["padtab"].handler(nil, %d); NOW = NOW + 0.1' % c)


def extra_tap(L, col):
    L.execute('WowCompanion.pad.rows["extra"].handler(nil, %d); NOW = NOW + 0.1' % col)


def spy_puts(L):
    L.execute('PUTS = {}; local pm = WowCompanion.padMap; '
              'for _, n in ipairs({ "putSpell", "putCommand", "putMacro", "putFight", "putItem", "putMount", "putBind" }) do '
              '  local f = pm[n]; pm[n] = function(...) local a = { ... }; local t = {}; for i = 1, select("#", ...) do t[#t + 1] = tostring(a[i]) end; '
              '  PUTS[#PUTS + 1] = n .. "(" .. table.concat(t, ",") .. ")"; return true end end')


def puts(L):
    return [L.eval('PUTS[%d]' % (i + 1)) for i in range(int(L.eval('#PUTS')))]


def clean(L, name):
    check(L.eval('#SPY') == 0 and L.eval('#VIOL') == 0 and list(L.eval('ERRS').values()) == [],
          name + ": no spied call (no trace), no lockdown breach, no error",
          (L.eval('SPY[1]'), L.eval('VIOL[1]'), list(L.eval('ERRS').values())[:2]))


# ================================================================ a. tags
check('local CV = "p58"' in SRC["padtabctl"] and "p58 THE CHOOSER AS A GRID" in SRC["padtabctl"] and "p57 A FINGER ON A CHOOSER TAB" in SRC["padtabctl"],
      "a. PadTabCtl p58 and its note (p57's kept)")
check('local BV = "b76"' in SRC["book"] and "b73 THE CHOOSER AS A GRID" in SRC["book"] and "b72 A FINGER ON A TAB" in SRC["book"],
      "a. Book b73 and its note (b72's kept)")
check('local PV = "m185"' in SRC["padmap"] and "m177 MACROS IN THE CONTROLLER ROOM'S CHOOSER" in SRC["padmap"], "a. PadMap m182 (m177's note kept)")
check('"dstool"' not in SRC["padtabctl"].split("local CTABS", 1)[1].split("--", 1)[0], "a. CTABS has no dstool")

W = boot()
check(list(W.eval('ERRS').values()) == [], "a. the world loads", list(W.eval('ERRS').values())[:2])
NAV = Nav(W, ctl)
W.execute('WowCompanion.padTabCtl.enter("tabs"); WowCompanion.padTabCtl.touchTab(1); WowCompanion.padTabCtl.touchPos(2)')
W.execute('pp("PAD1")')
check(ch(W).get('list') == 'spells' and ch(W).get('row') == 1, "a. A on an empty button: the chooser on Spells, its first cell", ch(W))
NAV.to_tabs()
seen = []
for i in range(6):
    seen.append(ch(W).get('list'))
    W.execute('pp("PADDRIGHT")')
seen = list(dict.fromkeys(seen))
check(seen == ["spells", "general", "professions", "extra", "macros"], "a. the tabs: Spells, General, Professions, Extra, Macros", seen)

# ================================================================ b. grid navigation
NAV.tab("spells")
rows = NAV.rows()
cells = [r for r in rows if r not in ('clear', 'uncombat', 'uncface')]
check(len(cells) == 3, "b. this world's Spells tab: three spells (one row)", rows)
NAV.tab("macros")                                         # the grid walk on Macros: 7 macros = a row of 4 and a short row of 3
cells = NAV.rows()
check(len(cells) == 7, "b. Macros: seven cells (two rows)", cells)
n = len(cells)


def at():
    return ch(W).get('row')


W.execute('pp("PADDRIGHT")')
check(at() == 2 and ch(W).get('list') == 'macros', "b. Right: the next cell", ch(W))
W.execute('pp("PADDLEFT")')
check(at() == 1, "b. Left: back", ch(W))
W.execute('pp("PADDDOWN")')
check(at() == 5 and ch(W).get('id') == cells[4], "b. Down: the cell under it (4 a row)", ch(W))
W.execute('pp("PADDUP")')
check(at() == 1, "b. Up: the row over it", ch(W))
W.execute('pp("PADDUP")')
check(ch(W).get('tabs') is True and ch(W).get('list') == 'macros', "b. Up off the first row: the tab row", ch(W))
W.execute('pp("PADDDOWN"); pp("PADDRIGHT"); pp("PADDRIGHT"); pp("PADDRIGHT")')
check(at() == 4, "b. Right x3: the row's last cell", ch(W))
W.execute('pp("PADDRIGHT")')
check(ch(W).get('list') == 'macros' and ch(W).get('row') == 4, "b. Right past the last tab's row end: stays (Macros is the last tab)", ch(W))
W.execute('pp("PADDLEFT"); pp("PADDLEFT"); pp("PADDLEFT"); pp("PADDLEFT")')
check(ch(W).get('list') == 'extra' and ch(W).get('row') == 1, "b. Left past a row's start: the neighbour tab (Extra), its first row", ch(W))
NAV.tab("spells")
W.execute('pp("PADDRIGHT"); pp("PADDRIGHT"); pp("PADDRIGHT")')
check(ch(W).get('list') == 'general' and ch(W).get('row') == 1, "b. Right past a short row's end (Spells' 3 cells): the neighbour tab (General)", ch(W))
NAV.tab("macros")
# a short last row: Down from the row above lands on its last cell
if n % 4 != 0 and n > 4:
    last0 = (n - 1) // 4 * 4                                  # the last row's first index (0-based)
    above = last0 - 4 + 4                                     # the row above's last cell (1-based)
    NAV.goto(cells[last0 - 1])
    W.execute('pp("PADDDOWN")')
    check(at() == n, "b. Down onto a short row: its last cell", (ch(W), n))
# the pinned Clear (on a filled button): put a spell first
W.execute('pp("PAD2")')
W.execute('WowCompanion.padTabCtl.touchPos(2)')
spy_puts(W)
W.execute('WowCompanion.padMap.slotAt0 = WowCompanion.padMap.slotAt; local pm = WowCompanion.padMap; local f = pm.slotAt; '
          'pm.slotAt = function(pg, st, ps) if st == 1 and ps == 2 then return { kind = "spell", id = 403, n = "Lightning Bolt", i = 1 } end '
          'return f(pg, st, ps) end')
W.execute('pp("PAD1")')
rows = NAV.rows()
check(rows[-1] == 'clear', "b. a filled button: Clear pinned under the grid", rows[-3:])
NAV.goto(cells[-1] if cells[-1] in rows else rows[-2])
W.execute('pp("PADDDOWN")')
check(ch(W).get('id') == 'clear', "b. Down off the last row: the pinned Clear", ch(W))
W.execute('pp("PADDUP")')
check(ch(W).get('id') != 'clear' and ch(W).get('row') >= len(rows) - 1 - 3, "b. Up from Clear: the grid's last row", ch(W))
W.execute('pp("PAD2")')
W.execute('WowCompanion.padMap.slotAt = WowCompanion.padMap.slotAt0')
check(puts(W) == [], "b. walking puts nothing", puts(W))

# ================================================================ c. category folding
W.execute('pp("PAD1")')
NAV.tab("extra")
rows = NAV.rows()
heads = [r for r in rows if isinstance(r, str) and r.startswith('h:')]
check(heads == rows and heads[:2] == ['h:cmd', 'h:ds'] and 'h:bind' in heads and ctl(W).get('xo') is None,
      "c. Extra at first: only the folded category headers (Commands, DS Forever, Controller, ...)", rows)
check(ch(W).get('id') == 'h:cmd', "c. the cursor on Commands' header", ch(W))
W.execute('pp("PAD1")')
rows = NAV.rows()
check(ctl(W).get('xo') == 'cmd' and ch(W).get('id') == 'h:cmd' and rows[1] == 'TARGETNEARESTENEMY' and puts(W) == [],
      "c. A on a header: it opens (xo = cmd), the cursor stays on it, its commands under it, nothing put", (ctl(W).get('xo'), rows[:3]))
W.execute('pp("PADDDOWN")')
check(ch(W).get('id') == 'TARGETNEARESTENEMY', "c. Down: its first command", ch(W))
W.execute('pp("PADDUP"); pp("PAD1")')
check(ctl(W).get('xo') is None and NAV.rows() == heads, "c. A again: folded", (ctl(W).get('xo'), NAV.rows()))
padtab(W, 39, 38, 37, 5)                                       # the third page: code 3 + k, k = 2 (DS Forever)
check((ctl(W).get('xo') or '') == 'ds', "c. by touch: padtab 39, 38, 37 then 5 opens DS Forever (category 2)", ctl(W).get('xo'))
padtab(W, 39, 38, 37, 4)
check((ctl(W).get('xo') or '') == 'cmd,ds', "c. then 4: Commands too (category 1)", ctl(W).get('xo'))
padtab(W, 39, 38, 37, 5)
padtab(W, 39, 38, 37, 4)
check(ctl(W).get('xo') is None, "c. by touch again: both folded", ctl(W).get('xo'))
W.execute('pp("PADDLEFT")')
check(ch(W).get('list') == 'professions', "c. the list's Left: the neighbour tab (as before)", ch(W))
W.execute('pp("PAD2")')

# ================================================================ d. the DS commands under Extra
W.execute('pp("PAD1")')
NAV.tab("extra")
ok = NAV.goto('DSMAP')
check(ok and 'ds' in (ctl(W).get('xo') or '').split(','), "d. Open Map is under Extra's DS Forever category", (ch(W), ctl(W).get('xo')))
W.execute('pp("PAD1")')
for _ in range(3):
    if ctl(W).get('box'):
        W.execute('pp("PAD1")')
check(puts(W) == ['putCommand(1,1,2,DSMAP)'], "d. chosen there: putCommand on the button, as the old DS tab did", puts(W))
W.execute('pp("PAD1")')
padtab(W, 39, 38, 10)
c = ch(W)
check(c.get('list') == 'macros' and c.get('tabs') is True and c.get('row') == 1, "d / f. padtab 39, 38 then 10 (tab 5): Macros, the cursor on its tab", c)

# ================================================================ e. macros
macs = docs(W, 'padmap')[-1]['m'].get('macs')
check([m['n'] for m in macs] == ['Bravo', 'Totems', 'Pull', 'Mount', 'Wolf', 'Purge', 'Heal Me'] and macs[1]['i'] == 135825,
      "e. the padmap doc's macs: the player's macros, General then the character's; ours and the empty one left out", macs)
W.execute('pp("PADDDOWN")')
rows = NAV.rows()
check(rows[:2] == ['mc:Bravo', 'mc:Totems'] and rows[-1] == 'mc:Heal Me' and ch(W).get('id') == 'mc:Bravo', "e. f. Down: Macros' first cell; the grid by name", (rows, ch(W)))
W.execute('pp("PADDRIGHT"); PUTS = {}; pp("PAD1")')
for _ in range(3):
    if ctl(W).get('box'):
        W.execute('pp("PAD1")')
check(puts(W) == ['putMacro(1,1,2,Totems)'] and not ch(W), "e. A on Totems: PadMap.putMacro on the WoW Defaults button, by name", puts(W))
# In combat: the fight layer
W.execute('WowCompanion.padTabCtl.touchTab(3); WowCompanion.padTabCtl.touchPos(2); PUTS = {}; pp("PAD1")')
NAV.tab("macros")
NAV.goto('mc:Heal Me')
W.execute('pp("PAD1")')
for _ in range(3):
    if ctl(W).get('box'):
        W.execute('pp("PAD1")')
check(puts(W) == ['putFight(1,1,2,macro,Heal Me)'], "e. In combat: the macro goes on the fight layer (putFight \"macro\")", puts(W))
# by touch: padtab 39, 38, 37 then 0 (the macro page) + the extra row's column 0 = Bravo, on In combat (the combat pick first)
W.execute('PUTS = {}')
W.execute('WowCompanion.padTabCtl.touchPos(3)')
padtab(W, 39, 38, 37, 0)
extra_tap(W, 0)
ack = docs(W, 'padack')[-1]
check(puts(W) == ['putFight(1,1,3,macro,Bravo)'] and ack.get('op') == 'extra' and ack.get('ok') and ack.get('kind') == 'macro'
      and ack.get('what') == 'Bravo', "e. by touch (39, 38, 37, 0 + extra 0) on In combat: Bravo on the fight layer; the answer names it", (puts(W), ack))
W.execute('PUTS = {}')
extra_tap(W, 1)
check(puts(W) == [], "e. an extra tap without a fresh page pick puts nothing", puts(W))
# the radial: no Macros tab
W.execute('WowCompanion.padTabCtl.touchTab(4)')
check('"macros"' not in W.eval('(function() local t = WowCompanion.padTabCtl.state(); return "" end)()'), "e. (radial)")
# the tap-to-cast bar: the macro kind on the slot
L2 = boot()
L2.execute('WowCompanion.padTabCtl.enter("tabs")')
padtab(L2, 39, 30)
padtab(L2, 39, 29)
L2.execute('WowCompanion.padTabCtl.touchPos(5); pp("PAD1")')
n2 = Nav(L2, ctl)
n2.tab("macros"); n2.goto('mc:Totems')
L2.execute('pp("PAD1")')
for _ in range(3):
    if ctl(L2).get('box'):
        L2.execute('pp("PAD1")')
st = L2.eval('(WowCompanionSpellsDB.castBar or {})[WHO .. "-" .. REALM]')
e5 = st and st[5]
check(e5 is not None and e5.k == 'macro' and e5.n == 'Totems', "e. the tap-to-cast bar: Totems on slot 5 as the macro kind (by name)",
      dict(e5) if e5 else None)
clean(L2, "e (bar)")
clean(W, "a-f")

# PadMap.putMacro onto a BAR SLOT: the game's own cursor road (its own stubs: a cursor, the slots)
B = boot(r'''
SLOTS, CURSOR = {}, nil
''')
B.execute(r'''
PickupMacro = function(i) CURSOR = { "macro", i } end
ClearCursor = function() CURSOR = nil end
PlaceAction = function(s) if CURSOR then SLOTS[s] = CURSOR end; CURSOR = nil end
GetActionInfo = function(s) local c = SLOTS[s]; if c then return c[1], c[2] end end
GetActionText = function(s) local c = SLOTS[s]; if c and c[1] == "macro" then return (GetMacroInfo(c[2])) end end
OKP, WHY = WowCompanion.padMap.putMacro(1, 2, 3, "Totems")
''')
slot = B.eval('(function() for s, c in pairs(SLOTS) do return s end end)()')
check(B.eval('OKP') is True and slot is not None and B.eval('SLOTS[%d][2]' % slot) == 3,
      "e. putMacro on a WoW Defaults button: PickupMacro(3) + PlaceAction on its bar slot, read back", (B.eval('OKP'), B.eval('WHY'), slot))
B.execute('OKP, WHY = WowCompanion.padMap.putMacro(1, 2, 3, "Nope")')
check(B.eval('OKP') is False and B.eval('WHY') == 'missing', "e. a name no macro has: refused \"missing\"", B.eval('WHY'))
mlog = [n for n in ('CreateMacro', 'EditMacro', 'DeleteMacro', 'SetBinding', 'SaveBindings', 'SetCVar') if n in [B.eval('SPY[%d]' % (i + 1)) for i in range(int(B.eval('#SPY')))]]
check(mlog == [], "e. no macro written, no binding, no CVar", mlog)

print("\n%d passed, %d failed" % (passes, fails))
sys.exit(1 if fails else 0)
