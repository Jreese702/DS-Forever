"""TapPad pad26 THE ROW ORDER (GitHub #550, with #564 / #570 / #249 / #260: "top screen ui doesnt update when editing in settings"; the
reporter: the switches only failed with the game at about 960x540), off-device: the REAL add-on files under tools/wowstub.py's game.

    python tools/test_pad_order.py        (PARTY_FIXTURES_WRITE=1 rewrites dsforever/src/test/resources/pad-fixtures, PadFitTest's)

Proven here, with every add-on, six full bags, the cast bar, both radials and the Macros room's lazy row (the fullest layout):
  a the tag and its note; ORDER ranks 56 distinct names; every row the add-ons register is ranked (a new row must be added to ORDER);
  b every ranked row sits on its own row (its place in ORDER), whatever order the add-ons load in;
  c 1920x1080 at the Thor's UI scale (popupTop 126.6): all 56 rows placed and described, no `drop` (the row budget, as rowcount3);
  d 1920x1080 at the smallest UI scale (121.5): the switches placed; only keytest and bmv dropped;
  e 1280x720 with a lowered UI scale (#249, 107.6): the switches placed; rows 47-55 dropped (Professions, the Group Finder, keytest, bmv);
  f 960x540 (the reporter's, 94.9): the switches placed; rows 40-55 dropped; a tap on "uisw" and on "ui" reaches the Hub;
  g 960x540 at the smallest UI scale (60.75): the switches placed (rows 0-22 kept);
  h `drop` names each registered row this layout does not place (a shared row once, by its host), and only those;
  i a name nobody ranked takes the lowest free row (as every row did before); pad19's opts.last still pins one to the last row;
  j the calibration strip still covers exactly the rows that fit (pad24: the stream's row y logic unchanged);
  k the fixtures the app's PadFitTest reads equal what the add-on publishes now;
  l 1280x720 at the game's own UI scale (render size 720p, 126.6): the switches placed, nothing dropped;
  m 1280x720 with 1080p's UI scale kept (84.4): rows 35-55 dropped (mapw onward), the switches placed.
Needs lupa. Exit code 1 on any failure."""
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from wowstub import ADDONS, ADDON_OF, F, SRC, boot_world  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FIXDIR = os.path.join(ROOT, "dsforever", "src", "test", "resources", "pad-fixtures")
fails = 0
passes = 0


def check(ok, name, got=''):
    global fails, passes
    print(('PASS ' if ok else 'FAIL ') + name + ((' -> ' + str(got)) if got != '' else ''))
    if ok:
        passes += 1
    else:
        fails += 1


# every add-on file in the game's load order (as test_shared_row.py)
ORDER = [("WowCompanion", ["Safety", "Sink", "TapPad", "Harvest", "ActionBar", "Coords", "Map", "Trail", "MapArt", "Chat", "Bags", "Core"]),
         ("WowCompanionQuests", ["Quests", "Tracker"]),
         ("WowCompanionHub", ["Hub"]),
         ("WowCompanionInteract", ["Interact", "Dialogs"]),
         ("WowCompanionSpells", ["Spells", "Book", "Talents", "TalentsCtl", "PadMap", "PadTabCtl", "Radial"]),
         ("WowCompanionCharacter", ["Character", "Auras"]),
         ("WowCompanionSocial", ["Social"]), ("WowCompanionCalendar", ["Calendar"]),
         ("WowCompanionGroupFinder", ["GroupFinder"]), ("WowCompanionProfessions", ["Professions"]), ("WowCompanionPvp", ["Pvp"])]
BLOCKS = []
for d, fs in ORDER:
    ks = []
    for f in fs:
        k = "x_" + d + "_" + f
        p = os.path.join(ADDONS, d, f + ".lua")
        if not os.path.exists(p):
            continue
        F[k] = p
        SRC[k] = open(p, encoding="utf-8").read()
        ADDON_OF[k] = d
        ks.append(k)
    BLOCKS.append(ks)
ALL = [k for b in BLOCKS for k in b]
# the add-ons after WowCompanion in the reverse order (the core must load first: it makes WC and the pad)
REVERSED = BLOCKS[0] + [k for b in reversed(BLOCKS[1:]) for k in b]
NAMES = [d for d, _ in ORDER]
LOGIN = ('for _, a in ipairs({ %s }) do fire("ADDON_LOADED", a) end; fire("PLAYER_LOGIN"); fire("PLAYER_ENTERING_WORLD", true, false); '
         'runTimers(NOW + 10)\n'
         'for b = 0, 5 do pcall(fire, "BAG_UPDATE", b) end; pcall(fire, "BAG_UPDATE_DELAYED"); runTimers(NOW + 20)\n'
         'pcall(WowCompanion.collectBags); runTimers(NOW + 40); pcall(function() WowCompanion.pad.layout() end); runTimers(NOW + 50)'
         % ", ".join('"%s"' % n for n in NAMES))
BAGS = ('C_Container = setmetatable({}, { __index = function() return STUB end }); C_Container.GetContainerNumSlots = function(b) '
        'if b >= 0 and b < 6 then return 20 end return 0 end\n'
        'C_Container.GetContainerItemID = function(b, s) if s == 1 then return 2589 end end\n'
        'C_Container.GetContainerItemInfo = function(b, s) if s == 1 then return { itemID = 2589, stackCount = 2, quality = 1, iconFileID = 1 } end end\n'
        'C_Container.GetContainerItemLink = function() return nil end; C_Container.PickupContainerItem = function() end\n'
        'C_Container.GetContainerNumFreeSlots = function(b) return 10, 0 end\n'
        'Enum = setmetatable({ BagIndex = {} }, { __index = function() return STUB end }); Enum.BagIndex.ReagentBag = 5\n'
        'WowCompanion = WowCompanion or {}; WowCompanion.partyOk = true\n')


def world(pw, ph, uh, files=None):
    """the fullest layout on a pw x ph game screen whose UIParent is uh units tall (popupTop = 135 x ph / uh)"""
    pre = 'SCREEN = { pw = %r, ph = %r, uh = %r }\n' % (pw, ph, uh) + BAGS
    L, errs = boot_world(files=tuple(files or ALL), pre=pre, strict=True, login=LOGIN, expose=False)
    L.execute('WowCompanion.collectBags(); runTimers(NOW + 60); WowCompanion.collectBags(); runTimers(NOW + 70)')
    who = L.eval('(UnitName("player") or "?") .. "-" .. (GetRealmName() or "?")')
    L.execute('WowCompanionSpellsDB = WowCompanionSpellsDB or {}; WowCompanionSpellsDB.castBar = WowCompanionSpellsDB.castBar or {}')
    L.execute('WowCompanionSpellsDB.castBar["%s"] = { [1] = 403 }' % who)
    L.execute('pcall(function() return WowCompanion.castBar and WowCompanion.castBar.sync() end)')
    for nm, n in (("radial", 9), ("classr", 25), ("castbar", 13)):
        if not L.eval('WowCompanion.pad.rows["%s"] ~= nil' % nm):
            L.execute('WowCompanion.pad.secureRow("%s", %d, function() end, function() end)' % (nm, n))
    # the Macros room's lazy row: Book's padtab one-shot pick 39, then 34
    L.eval('hwTap')("padtab", 39)
    L.eval('hwTap')("padtab", 34)
    L.execute('advance(0.3)')
    L.execute('pcall(function() WowCompanion.pad.layout() end)')
    return L, errs


def uh_for(ph, popup_top):
    return ph / (popup_top / 135.0)


def rows_of(L):
    return dict((k, int(v)) for k, v in L.eval('(function() local t = {} for n, r in pairs(WowCompanion.pad.rows) do t[n] = r.row end '
                                              'return t end)()').items())


def doc(L):
    return json.loads(L.eval('WowCompanion.encode(WowCompanion.pad.describe())'))


def lua_errors(L, errs):
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


def fits(row, popup_top):
    return 12 + (row + 1) * 2 <= popup_top - 1


def tap(L, row, col):
    shown = L.eval('(function(r, c) local x = WowCompanion.pad.rows[r]; local b = x and x.cells and x.cells[c]; '
                   'return b ~= nil and b:IsShown() end)')(row, col)
    if not shown:
        return False
    ok = L.eval('hwTap')(row, col)
    L.execute('advance(0.3)')
    return bool(ok)


FIX = {}
# ================================================================ a. the tag, ORDER
TP = SRC["x_WowCompanion_TapPad"]
check('local PADV = "pad27"' in TP and "pad26 THE ROW ORDER (GitHub #550" in TP and "top screen ui doesnt update when editing in settings" in TP,
      "a. TapPad pad27 (pad26's note names #550 and quotes the report)")
L, errs = world(1920, 1080, uh_for(1080, 126.6))
RANK = [L.eval('WowCompanion.pad.ORDER[%d]' % i) for i in range(1, 57)]
check(len(RANK) == 56 and None not in RANK and len(set(RANK)) == 56 and L.eval('WowCompanion.pad.ORDER[57]') is None,
      "a. ORDER ranks 56 distinct names (ROWS_MAX)", len(set(x for x in RANK if x)))
check(RANK[:2] == ["uisw", "ui"], "a. the top screen's switches and its restore / layout row FIRST (rows 0-1)", RANK[:2])
names = rows_of(L)
unranked = sorted(n for n in names if n not in RANK and not L.eval('WowCompanion.pad.rows["%s"].guestOf' % n))
check(unranked == [], "a. every row the add-ons register is ranked in ORDER (a new row: add it there)", unranked)

# ================================================================ b. its own row, whatever the load order
want = dict((n, i) for i, n in enumerate(RANK))
hosts = dict((n, r) for n, r in names.items() if not L.eval('WowCompanion.pad.rows["%s"].guestOf' % n))
check(all(hosts[n] == want[n] for n in hosts), "b. every ranked row sits on its place in ORDER",
      [(n, hosts[n], want[n]) for n in hosts if hosts[n] != want[n]][:4])
check(names.get("mac") == names.get("cal") == want["cal"] and names.get("lay") == want["cal"],
      "b. the shared row: mac and lay are cal's guests, on cal's row", (names.get("mac"), names.get("lay"), names.get("cal")))
Lr, errs_r = world(1920, 1080, uh_for(1080, 126.6), files=REVERSED)
check(rows_of(Lr) == names, "b. the add-ons loaded in the reverse order: the same rows",
      sorted(set(rows_of(Lr).items()) ^ set(names.items()))[:4])
check(lua_errors(Lr, errs_r) == [], "b. clean (reverse order)", lua_errors(Lr, errs_r)[:3])

# ================================================================ c. 1920x1080, the Thor's scale: the row budget
d = doc(L)
check(abs(d["popupTop"] - 126.6) < 0.05, "c. popupTop 126.6", d["popupTop"])
check(len(set(names.values())) == 56 and max(names.values()) == 55, "c. 56 rows in use (rowcount3: 56 places)", len(set(names.values())))
expect = set(names) - {"cal", "lay"}
check(set(d["rows"]) == expect, "c. every row placed and described (the shared row as its tenant, mac)",
      (sorted(expect - set(d["rows"])), sorted(set(d["rows"]) - expect)))
check("drop" not in d, "c. no `drop`", d.get("drop"))
check(lua_errors(L, errs) == [], "c. clean", lua_errors(L, errs)[:3])
FIX["pad_1920x1080"] = L.eval('WowCompanion.encode(WowCompanion.pad.describe())')


def small(label, pw, ph, popup_top, key, expect_drop=None, fixture=None):
    Ls, es = world(pw, ph, uh_for(ph, popup_top))
    ds = doc(Ls)
    nm = rows_of(Ls)
    check(abs(ds["popupTop"] - popup_top) < 0.05 and ds["pw"] == pw and ds["ph"] == ph, "%s. %dx%d, popupTop %s" % (key, pw, ph, popup_top),
          (ds["pw"], ds["ph"], ds["popupTop"]))
    check(ds["rows"].get("uisw") == 0 and ds["rows"].get("ui") == 1 and ds["sizes"].get("uisw") == 40 and ds["sizes"].get("ui") == 40,
          "%s. the switches' rows are placed: uisw 0, ui 1 (%s)" % (key, label), (ds["rows"].get("uisw"), ds["rows"].get("ui")))
    placed = sorted(r for n, r in nm.items() if n in ds["rows"])
    drop = ds.get("drop") or {}
    want_drop = dict((n, r) for n, r in nm.items() if not fits(r, popup_top) and not Ls.eval('WowCompanion.pad.rows["%s"].guestOf' % n))
    check(drop == want_drop and all(n not in ds["rows"] for n in drop),
          "%s. `drop` = exactly the registered rows that do not fit (%d), none described" % (key, len(drop)),
          sorted(set(drop) ^ set(want_drop)))
    if expect_drop is not None:
        check(sorted(drop, key=lambda n: drop[n]) == expect_drop, "%s. dropped by impact: %s" % (key, " ".join(expect_drop)),
              sorted(drop, key=lambda n: drop[n]))
    check(all(fits(r, popup_top) for r in placed), "%s. every placed row ends above the dialogs" % key)
    shown = Ls.eval('(function() local n = 0 for name, r in pairs(WowCompanion.pad.rows) do '
                    'if not WowCompanion.pad.fits(r.row) then for _, b in pairs(r.cells or {}) do if b:IsShown() then n = n + 1 end end end '
                    'end return n end)()')
    check(shown == 0,"%s. no cell of a dropped row is shown (never inside a dialog)" % key, shown)
    cal = ds.get("calib") or {}
    nfit = 0
    while nfit < 56 and fits(nfit, popup_top):
        nfit += 1
    check(cal.get("y") == ds["y"] and cal.get("h") == nfit * 2, "%s. the calibration strip covers the %d rows that fit (pad24 unchanged)" % (key, nfit),
          (cal.get("y"), cal.get("h")))
    check(lua_errors(Ls, es) == [], "%s. clean" % key, lua_errors(Ls, es)[:3])
    if fixture:
        FIX[fixture] = Ls.eval('WowCompanion.encode(WowCompanion.pad.describe())')
    return Ls, ds


# ================================================================ d-g. the smaller layouts
small("1920x1080, smallest UI scale", 1920, 1080, 121.5, "d", ["keytest", "bmv"], "pad_1920x1080_min")
small("1280x720, UI scale lowered (#249)", 1280, 720, 107.6, "e",
      ["prof", "profsel", "profopen", "gf", "gfl", "gfw", "gfclose", "keytest", "bmv"], "pad_1280x720_low")
Lf, df = small("960x540, the reporter's", 960, 540, 135 * 540 / 768.0, "f", RANK[40:], "pad_960x540")
small("960x540, smallest UI scale", 960, 540, 135 * 540 / 1200.0, "g", RANK[23:], "pad_960x540_min")
# l / m. RENDER SIZE 720p (the default on every device since 2026-11-27, PR 615): the pad's cells are game px, so the rows that fit depend
# on where the dialogs start in game px (135 UI units x ph / UIParent's height). l: the game's own UI scale at 720p (UIParent 768 units
# tall, the same as 960x540's: popupTop 126.6) - every row placed, nothing dropped. m: a UI scale slider that keeps 1080p's UIParent
# (1151.7 units: the Thor's 126.6 at 1080p, so 84.4 at 720p) - rows 35-55 dropped (the Map room's mapw onward); the app's taps say
# "No pad row" for those rooms there. Which one a Thor gets is a device check (the pad document's popupTop / drop at 720p).
small("1280x720, the game's own UI scale (render size 720p)", 1280, 720, 135 * 720 / 768.0, "l", [])
small("1280x720, the 1080p UI scale kept (render size 720p)", 1280, 720, 126.6 * 720 / 1080.0, "m", RANK[35:])
# f: the switches work there: a tap on "uisw" (the world markers on) and on "ui" reach the Hub
n0 = Lf.eval('WowCompanion.hub.taps')
check(tap(Lf, "uisw", 9), "f. a tap on uisw:9 lands on a shown cell")
check(Lf.eval('WowCompanion.hub.taps') == n0 + 1 and Lf.eval('WowCompanion.npWant and WowCompanion.npWant.wm') is True,
      "f. the Hub took it: the world markers' switch is on", (Lf.eval('WowCompanion.hub.taps'), Lf.eval('WowCompanion.npWant and WowCompanion.npWant.wm')))
rx = Lf.eval('(WowCompanion.pad.rxDoc() or {}).last') or ""
check(rx.split()[-1:] == ["uisw:9"], "f. the press ring says uisw:9", rx)
check(tap(Lf, "ui", 34), "f. a tap on ui:34 (the one-tap restore) lands on a shown cell")
check((Lf.eval('(WowCompanion.pad.rxDoc() or {}).last') or "").split()[-1:] == ["ui:34"], "f. the press ring says ui:34")
check(not tap(Lf, "bmv", 0) and not tap(Lf, "gf", 0), "f. a dropped row's cells take no tap (hidden)")

# ================================================================ i. a name nobody ranked
Li, errs_i = boot_world(files=tuple(BLOCKS[0]), pre=BAGS, strict=True, login=LOGIN.replace(", ".join('"%s"' % n for n in NAMES), '"WowCompanion"'),
                        expose=False)
used = rows_of(Li)
r_new = Li.eval('WowCompanion.pad.op("zz550", "zz550", function() end)')
free_low = min(i for i in range(56) if i not in used.values())
check(r_new == free_low, "i. an unranked row takes the lowest free row (%s), as before" % free_low, r_new)
r_ranked = Li.eval('WowCompanion.pad.op("gf", "gf", function() end)')
check(r_ranked == want["gf"], "i. a ranked row still finds its own row", r_ranked)
used = rows_of(Li)
r_last = Li.eval('WowCompanion.pad.op("zz550b", "zz550b", function() end, { last = true })')
check(r_last == (55 if 55 not in used.values() else min(i for i in range(56) if i not in used.values())),
      "i. opts.last (pad19): the last row when it is free, else the lowest free", r_last)
check(lua_errors(Li, errs_i) == [], "i. clean", lua_errors(Li, errs_i)[:3])

# ================================================================ k. the fixtures
os.makedirs(FIXDIR, exist_ok=True)
stale = []
for name, text in sorted(FIX.items()):
    fp = os.path.join(FIXDIR, name + ".json")
    old = open(fp, encoding='utf-8').read() if os.path.isfile(fp) else None
    if os.environ.get("PARTY_FIXTURES_WRITE") == "1":
        with open(fp, 'w', encoding='utf-8', newline='\n') as fh:
            fh.write(text)
    elif old != text:
        stale.append(name)
check(len(FIX) == 5 and not stale, "k. the committed fixtures equal what the add-on publishes now (%d files; PARTY_FIXTURES_WRITE=1 rewrites)"
      % len(FIX), stale)

print("\n%d passed, %d failed" % (passes, fails))
sys.exit(1 if fails else 0)
