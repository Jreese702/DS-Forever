"""THE SHARED ROW (TapPad pad21, Spells s12, Book b65; the 1.0.32 test build, code 1472), off-device: the REAL add-on files under
tools/wowstub.py's stubbed game.

    python tools/test_shared_row.py

The problem: with six bags (5 + the reagent bag), the cast bar and both radials, the 56-row tap pad was full at the Thor's UI scale, so
the Macros room's lazy row "mac" had no row and the room said "No pad row". The fix: "mac" is the GUEST of the Calendar room's row "cal"
(the two rooms are never on the bottom screen together; the Calendar lost its bar slot to the Macros room) - the same row index, its own
cells, one of the two on the row at a time, the Calendar by default. Each room asks for the row when it comes on the screen, by a tap on
another row (Book's padtab one-shot pick: 39 then 34 = the Macros room's, 39 then 35 = the Calendar's).
Proven here:
  a versions, compile, the header notes;
  b THE FULLEST LAYOUT (every add-on, six full bags, the cast bar, the consumables radial, the class radial, then the Macros room opened):
    every row is registered and every one is placed and described at the Thor's UI scale (popupTop 126.6) - mac, castbar, radial,
    classr among them; at the smallest scale (121.5) only rows 54-55 drop (classr and Bags' pinned drag row, as before the change), mac
    stays; without the shared row the same layout leaves mac without a row (the document says norow);
  c the lease: at login the Calendar holds the row (cal described, mac not made); 39 then 34 makes mac as cal's guest ON the row (mac
    described, cal not; cal's cells hidden, mac's shown; the macros document has its keys and `sh`); a tap there reaches the Macros room,
    never the Calendar; 39 then 35 puts the Calendar back (cal described, mac not, mac's cells hidden, the macros document without keys,
    the Calendar's own document lists its row) and a tap reaches the Calendar again;
  d in a fight a lease waits for the fight's end (the row and both documents unchanged until then), then happens;
  e the game's own macro window opening while the room does not want the controller never takes the Calendar's row;
  f with no Calendar row (its add-on off) the room takes a row of its own, as before;
  g clean: no Lua error, no breach of the strict world's lockdown.
Needs lupa. Exit code 1 on any failure."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import wowstub  # noqa: E402
from wowstub import ADDONS, ADDON_OF, F, SRC, boot_world  # noqa: E402
from lupa import lua51 as lupa  # noqa: E402

fails = 0
passes = 0


def check(ok, name, got=''):
    global fails, passes
    print(('PASS ' if ok else 'FAIL ') + name + ((' -> ' + str(got)) if got != '' else ''))
    if ok:
        passes += 1
    else:
        fails += 1


# every add-on file in the game's load order (WowCompanion first, then the others alphabetically as the game loads them)
ORDER = [("WowCompanion", ["Safety", "Sink", "TapPad", "Harvest", "ActionBar", "Coords", "Map", "Trail", "MapArt", "Chat", "Bags", "Core"]),
         ("WowCompanionQuests", ["Quests", "Tracker"]),
         ("WowCompanionHub", ["Hub"]),
         ("WowCompanionInteract", ["Interact", "Dialogs"]),
         ("WowCompanionSpells", ["Spells", "Book", "Talents", "TalentsCtl", "PadMap", "PadTabCtl", "Radial"]),
         ("WowCompanionCharacter", ["Character", "Auras"]),
         ("WowCompanionSocial", ["Social"]), ("WowCompanionCalendar", ["Calendar"]),
         ("WowCompanionGroupFinder", ["GroupFinder"]), ("WowCompanionProfessions", ["Professions"]), ("WowCompanionPvp", ["Pvp"])]
ALL = []
for d, fs in ORDER:
    for f in fs:
        k = "x_" + d + "_" + f
        p = os.path.join(ADDONS, d, f + ".lua")
        if not os.path.exists(p):
            continue
        F[k] = p
        SRC[k] = open(p, encoding="utf-8").read()
        ADDON_OF[k] = d
        ALL.append(k)
NOCAL = [k for k in ALL if "WowCompanionCalendar" not in k]
NAMES = ("WowCompanion", "WowCompanionQuests", "WowCompanionHub", "WowCompanionInteract", "WowCompanionSpells", "WowCompanionCharacter",
         "WowCompanionSocial", "WowCompanionCalendar", "WowCompanionGroupFinder", "WowCompanionProfessions", "WowCompanionPvp")
LOGIN = ('for _, a in ipairs({ %s }) do fire("ADDON_LOADED", a) end; fire("PLAYER_LOGIN"); fire("PLAYER_ENTERING_WORLD", true, false); '
         'runTimers(NOW + 10)\n'
         'for b = 0, 5 do pcall(fire, "BAG_UPDATE", b) end; pcall(fire, "BAG_UPDATE_DELAYED"); runTimers(NOW + 20)\n'
         'pcall(WowCompanion.collectBags); runTimers(NOW + 40); pcall(function() WowCompanion.pad.layout() end); runTimers(NOW + 50)'
         % ", ".join('"%s"' % n for n in NAMES))
# six full bags (0-4 + the reagent bag 5), as the row count probe has them
BAGS = ('C_Container = setmetatable({}, { __index = function() return STUB end }); C_Container.GetContainerNumSlots = function(b) '
        'if b >= 0 and b < 6 then return 20 end return 0 end\n'
        'C_Container.GetContainerItemID = function(b, s) if s == 1 then return 2589 end end\n'
        'C_Container.GetContainerItemInfo = function(b, s) if s == 1 then return { itemID = 2589, stackCount = 2, quality = 1, iconFileID = 1 } end end\n'
        'C_Container.GetContainerItemLink = function() return nil end; C_Container.PickupContainerItem = function() end\n'
        'C_Container.GetContainerNumFreeSlots = function(b) return 10, 0 end\n'
        'Enum = setmetatable({ BagIndex = {} }, { __index = function() return STUB end }); Enum.BagIndex.ReagentBag = 5\n'
        'WowCompanion = WowCompanion or {}; WowCompanion.partyOk = true\n')


def world(popup_top=126.6, files=None, full=True):
    pre = 'SCREEN.uh = 1080 / (%r / 135)\n' % popup_top + BAGS
    L, errs = boot_world(files=tuple(files or ALL), pre=pre, strict=True, login=LOGIN, expose=False)
    L.execute('WowCompanion.collectBags(); runTimers(NOW + 60); WowCompanion.collectBags(); runTimers(NOW + 70)')
    if full:
        # the lazy rows as they come on the device: a cast bar with a spell (CB.sync), the consumables radial, the class radial
        who = L.eval('(UnitName("player") or "?") .. "-" .. (GetRealmName() or "?")')
        L.execute('WowCompanionSpellsDB = WowCompanionSpellsDB or {}; WowCompanionSpellsDB.castBar = WowCompanionSpellsDB.castBar or {}')
        L.execute('WowCompanionSpellsDB.castBar["%s"] = { [1] = 403 }' % who)
        L.execute('pcall(function() return WowCompanion.castBar and WowCompanion.castBar.sync() end)')
        for nm, n in (("radial", 9), ("classr", 25), ("castbar", 13)):
            if not L.eval('WowCompanion.pad.rows["%s"] ~= nil' % nm):
                L.execute('WowCompanion.pad.secureRow("%s", %d, function() end, function() end)' % (nm, n))
        L.execute('pcall(function() WowCompanion.pad.layout() end)')
    return L, errs


def tap(L, row, col):
    """a finger's tap where the cell IS (hwTap clicks a cell whatever its state; a hidden cell takes no click on the device)"""
    shown = L.eval('(function(r, c) local x = WowCompanion.pad.rows[r]; local b = x and x.cells and x.cells[c]; '
                   'return b ~= nil and b:IsShown() end)')(row, col)
    if not shown:
        return False
    ok = L.eval('hwTap')(row, col)
    L.execute('advance(0.3)')
    return bool(ok)


def padtab(L, code):
    """Book's padtab one-shot pick (39) then its code: the app's two taps"""
    L.eval('hwTap')("padtab", 39)
    L.eval('hwTap')("padtab", code)
    L.execute('advance(0.3)')


def described(L):
    d = L.eval('WowCompanion.pad.describe()')
    return dict((k, int(v)) for k, v in d['rows'].items()) if d else {}


def pyof(v, depth=3):
    """a Lua table as Python (dicts; the documents' shapes)"""
    if depth <= 0 or not hasattr(v, 'items') or isinstance(v, (str, bytes)):
        return v
    try:
        return dict((k, pyof(x, depth - 1)) for k, x in v.items())
    except Exception:
        return v


def mdoc(L):
    """the Macros room's document as the room builds it (with every add-on loaded, Sink.lua carries the sends: not SENT)"""
    return pyof(L.eval('WowCompanion.macroRoom._mr.doc()')) or {}


def cdoc(L):
    """the Calendar's own document as it builds it"""
    return pyof(L.eval('WowCompanion.collectCalendar()')) or {}


def holder(L):
    return L.eval('(function() local ok, s = pcall(WowCompanion.capture.state); return ok and s and s.holder or nil end)()')


def shown_cells(L, name):
    return L.eval('(function(r) local x = WowCompanion.pad.rows[r]; if not x then return -1 end; local n = 0; '
                  'for _, b in pairs(x.cells) do if b:IsShown() then n = n + 1 end end; return n end)')(name)


def fits(row, popup_top):
    return 12 + (row + 1) * 2 <= popup_top - 1


def lua_errors(L, errs):
    """load errors, Lua errors, and the strict world's breaches (a protected call from insecure code in a fight: VIOL / OUTSIDE)"""
    out = list(errs)
    try:
        out += list(L.eval('ERRS').values())
    except Exception:
        pass
    for k in ("VIOL", "OUTSIDE", "SNIPERR"):
        try:
            n = int(L.eval('#%s' % k))
        except Exception:
            continue
        out += ["%s: %s" % (k, L.eval('%s[%d]' % (k, i))) for i in range(1, n + 1)]
    return out


# ================================================================ a. versions, compile, notes
comp = lupa.LuaRuntime().eval('function(s, n) local f, e = loadstring(s, n); return e end')
for d, f in (("WowCompanion", "TapPad"), ("WowCompanionSpells", "Spells"), ("WowCompanionSpells", "Book")):
    e = comp(SRC["x_%s_%s" % (d, f)], f)
    check(e is None, "a. Lua 5.1 compiles %s.lua" % f, e or '')
TP, SP, BK = SRC["x_WowCompanion_TapPad"], SRC["x_WowCompanionSpells_Spells"], SRC["x_WowCompanionSpells_Book"]
check('local PADV = "pad21"' in TP and 'local SV = "s12"' in SP and 'local BV = "b65"' in BK, "a. the versions: TapPad pad21, Spells s12, Book b65")
check("pad21 THE SHARED ROW" in TP and "s12 THE MACROS ROOM'S ROW SHARED" in SP and "b65 THE SHARED ROW" in BK,
      "a. each changed file's header note names the shared row")
check('SHARE = "cal"' in SP and 'share = MR.SHARE' in SP, "a. the Macros room's row is the Calendar row's guest")

# ================================================================ b. the fullest layout
L, errs = world(126.6)
check(L.eval('WowCompanion.pad.rows.mac') is None and L.eval('WowCompanion.pad.rows.cal') is not None,
      "b. at login: the Calendar's row, no Macros row yet (lazy)")
padtab(L, 34)
names = dict((k, int(v)) for k, v in L.eval('(function() local t = {} for n, r in pairs(WowCompanion.pad.rows) do t[n] = r.row end '
                                          'return t end)()').items())
want = ["bag%d" % b for b in range(6)] + ["bmvq", "bmv", "radial", "classr", "castbar", "mac", "cal", "spells", "padtab", "party"]
check(all(w in names for w in want), "b. every row is registered, the lazy ones too", [w for w in want if w not in names])
check(len(set(names.values())) <= 56 and max(names.values()) <= 55, "b. %d rows on %d of the pad's 56 places" %
      (len(names), len(set(names.values()))), (len(names), len(set(names.values()))))
check(names.get("mac") == names.get("cal") and L.eval('WowCompanion.pad.rows.mac.guestOf') == "cal",
      "b. mac is the guest of cal: the same row", (names.get("mac"), names.get("cal")))
off = [n for n, r in names.items() if not fits(r, 126.6)]
check(off == [], "b. Thor scale (popupTop 126.6): every row fits above the dialogs", off)
rows = described(L)
expect = set(names) - {"cal"}
check(set(rows) == expect, "b. Thor scale: every row is described (the shared one as its tenant, mac)",
      (sorted(expect - set(rows)), sorted(set(rows) - expect)))
check(all(x in rows for x in ("mac", "castbar", "radial", "classr")), "b. mac, castbar, radial and classr all placed at the Thor's scale",
      dict((x, rows.get(x)) for x in ("mac", "castbar", "radial", "classr")))
m = mdoc(L)
check(m.get('keys') is not None and not m.get('norow') and m.get('sh') is True and 'mac' in ((m.get('pad') or {}).get('rows') or {}),
      "b. the Macros room's document: its keys, no norow, sh (shared)", (bool(m.get('keys')), m.get('norow'), m.get('sh')))
check(lua_errors(L, errs) == [], "b. clean", lua_errors(L, errs)[:3])
THOR_PLACED = len(rows)
# the smallest UI scale the game allows
Ls, errs_s = world(121.5)
padtab(Ls, 34)
names_s = dict((k, int(v)) for k, v in Ls.eval('(function() local t = {} for n, r in pairs(WowCompanion.pad.rows) do t[n] = r.row end '
                                             'return t end)()').items())
rows_s = described(Ls)
dropped = sorted(n for n in names_s if n != "cal" and n not in rows_s)
check(dropped == ["bmv", "classr"] and "mac" in rows_s, "b. smallest scale (121.5): only rows 54-55 drop (classr, bmv: as before), mac stays",
      (dropped, rows_s.get("mac")))
print("INFO rows: Thor scale %d names on %d places, %d described; smallest scale %d described, dropped %s"
      % (len(names), len(set(names.values())), THOR_PLACED, len(rows_s), dropped))

# ================================================================ c. the lease
Lc, errs_c = world(126.6)
check(L.eval('WowCompanion.pad.holds("cal")') is not None, "c. pad.holds exists")
r0 = described(Lc)
check("cal" in r0 and "mac" not in r0 and Lc.eval('WowCompanion.pad.holds("cal")') is True, "c. at login the Calendar holds its row")
padtab(Lc, 34)
r1 = described(Lc)
cal_row = int(Lc.eval('WowCompanion.pad.rows.cal.row'))
check("mac" in r1 and "cal" not in r1 and r1["mac"] == cal_row, "c. 39 then 34: mac on the Calendar's row, cal left out", (r1.get("mac"), cal_row))
check(shown_cells(Lc, "cal") == 0 and shown_cells(Lc, "mac") == 36, "c. cal's cells hidden, mac's 36 shown",
      (shown_cells(Lc, "cal"), shown_cells(Lc, "mac")))
m = mdoc(Lc)
k = m.get('keys') or {}
ok = tap(Lc, "mac", k.get('h', 0))
check(ok and holder(Lc) == "macros", "c. a tap of the room's h on the row: the Macros room holds the controller", (ok, holder(Lc)))
check(holder(Lc) != "calendar" and not (cdoc(Lc).get('ctl') or {}).get('want'), "c. ... and the Calendar heard nothing")
tap(Lc, "mac", k.get('f', 1))
check(holder(Lc) is None, "c. the room's f: the controller back", holder(Lc))
padtab(Lc, 35)
Lc.execute('runTimers(NOW + 2)')
r2 = described(Lc)
check("cal" in r2 and "mac" not in r2 and r2["cal"] == cal_row, "c. 39 then 35: the Calendar's row back, mac left out", r2.get("cal"))
check(shown_cells(Lc, "mac") == 0 and shown_cells(Lc, "cal") == 40, "c. mac's cells hidden, cal's 40 shown",
      (shown_cells(Lc, "mac"), shown_cells(Lc, "cal")))
m = mdoc(Lc)
check(m.get('keys') is None and not m.get('norow'), "c. the macros document: no keys (the app asks again), not norow", sorted(m))
check('cal' in ((cdoc(Lc).get('pad') or {}).get('rows') or {}), "c. the Calendar's own document lists its row again (the room's hold can go)")
ok = tap(Lc, "cal", 0)
check(ok and holder(Lc) == "calendar", "c. a tap of the Calendar's h: the Calendar holds", (ok, holder(Lc)))
check(not tap(Lc, "mac", k.get('h', 0)), "c. the Macros room's cells take no tap while the Calendar has the row")
tap(Lc, "cal", 1)
padtab(Lc, 34)
check("mac" in described(Lc) and mdoc(Lc).get('keys') is not None, "c. 39 then 34 again: the Macros room's again")
check(lua_errors(Lc, errs_c) == [], "c. clean", lua_errors(Lc, errs_c)[:3])

# ================================================================ d. in a fight the lease waits
Ld, errs_d = world(126.6)
padtab(Ld, 34)
padtab(Ld, 35)
check("cal" in described(Ld), "d. the Calendar's row (made mac first, then cal back)")
Ld.execute('fire("PLAYER_REGEN_DISABLED"); COMBAT = true; advance(0.2)')
padtab(Ld, 34)
check("cal" in described(Ld) and "mac" not in described(Ld) and shown_cells(Ld, "cal") == 40,
      "d. in a fight 39 then 34 waits: the Calendar's row stays", sorted(k for k in described(Ld) if k in ("cal", "mac")))
check(mdoc(Ld).get('keys') is None, "d. ... and the macros document has no keys yet")
Ld.execute('COMBAT = false; fire("PLAYER_REGEN_ENABLED"); advance(1); runTimers(NOW + 2)')
check("mac" in described(Ld) and "cal" not in described(Ld) and shown_cells(Ld, "mac") == 36,
      "d. the fight's end: the lease happens, mac on the row")
check(mdoc(Ld).get('keys') is not None, "d. ... and the macros document has its keys")
check(lua_errors(Ld, errs_d) == [], "d. clean", lua_errors(Ld, errs_d)[:3])

# ================================================================ e. the game's macro window alone never takes the row
Le, errs_e = world(126.6)
padtab(Le, 34)
padtab(Le, 35)
Le.execute('mkwin("MacroFrame", false); fire("ADDON_LOADED", "Blizzard_MacroUI"); advance(0.5)')
Le.execute('MacroFrame:Show(); for _, fn in ipairs(MacroFrame.hooks and MacroFrame.hooks.OnShow or {}) do fn(MacroFrame) end; advance(0.5)')
check("cal" in described(Le) and "mac" not in described(Le), "e. the game's macro window opened, the room not wanted: the Calendar keeps its row")
check(lua_errors(Le, errs_e) == [], "e. clean", lua_errors(Le, errs_e)[:3])

# ================================================================ f. no Calendar: a row of its own
Lf, errs_f = world(126.6, files=NOCAL, full=False)
check(Lf.eval('WowCompanion.pad.rows.cal') is None, "f. the Calendar add-on off: no cal row")
padtab(Lf, 34)
r = Lf.eval('WowCompanion.pad.rows.mac')
check(r is not None and r.guestOf is None and "mac" in described(Lf) and mdoc(Lf).get('keys') is not None and not mdoc(Lf).get('sh'),
      "f. ... the Macros room takes a row of its own (not shared), its keys, no sh")
check(lua_errors(Lf, errs_f) == [], "f. clean", lua_errors(Lf, errs_f)[:3])

print()
print(("ALL PASS" if fails == 0 else "FAILURES") + " (%d pass, %d fail)" % (passes, fails))
sys.exit(1 if fails else 0)
