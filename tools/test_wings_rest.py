"""nbar28 THE SINGLE SCREEN POLISH, THE ADD-ON'S HALF (test build 1535; the user on test 1533, 2026-11-26: "the Nintendo bar is BAD and I
hate it. I love the top screen bar that we just built"; "We need to hide the top screen xp bar so that we can recreate our home screen row
buttons. These hide in combat and out of combat it just shifts up the controller buttons a bit and moves them closer together and makes
them smaller when the Healthbars hide and slides up the home row from the bottom"; "In general the top screen icons all look worse then
the ones we made for the bottom screen because they have like squares in them"), off-device: the REAL Safety.lua + ActionBar.lua under
test_wings_match.py's scene (the stub game, a Trail-like HUD block, Hub-like Party rows), driven frame by frame through the add-on's own
driver, PadMap's single() / combatTopOn / combatCastTopOn answering the app's mode as PadMap m199 does (the saved flags OFF).

    python -B tools/test_wings_rest.py

  A THE TAGS: ActionBar nbar28 and its note; NB.TOP's REST_* the app's SinglePlayLayout.WINGS_REST_* (the docks' K3 is read off them).
  B AT REST: single screen mode on, no fight - the Wings UP (no takeover: the cast bar's word stays off), at the fight spot x REST_K,
    each half REST_IN closer, REST_UP higher, their feet over REST_FLOOR (the app's room row), inside the app's K3.
  C INTO A FIGHT AND OUT: the fight - the Wings glide (never hidden, never a slide) to the fight spot at the full size within REST_IN_S;
    the fight's end - back to rest within REST_OUT_S; every frame shown.
  D TWO SCREENS UNCHANGED: the mode off - no Wings out of the fight, the fight's Wings at the fight spot (no rest), the mode turned off
    at rest - the Wings leave.
  E THE CAST BAR: at rest with no cast - our top cast bar hidden; a Hearthstone at rest - shown (nbar25's every cast).
  F THE ICONS CROPPED: every icon we paint samples 0.07..0.93 (the bottom band's ICON_CROP) - a file id, an icon path; an atlas keeps its
    own coordinates; our own media is drawn whole; the Wings' face icons after a refresh.
  G NO TRACE: nbar28's code calls nothing secure, no SetCVar / SetBinding / macro API.
No device is read. Needs lupa. Exit code 1 on any failure."""
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import test_wings_match as twm  # noqa: E402

ROOT = twm.ROOT
AB = os.path.join(twm.ADDONS, "WowCompanion", "ActionBar.lua")
SPL = os.path.join(ROOT, "companion", "app", "src", "main", "java", "com", "jrees", "wowcompanion", "SinglePlayLayout.kt")
fails = []
passes = 0


def chk(name, ok, got=""):
    global passes
    print(("PASS " if ok else "FAIL ") + name + ((" -> " + str(got)) if got != "" and not ok else ""))
    if ok:
        passes += 1
    else:
        fails.append(name)


DT = 1 / 60.0


def scene(single=True):
    L = twm.boot()
    G = L.globals()
    L.execute('''
local pm = WowCompanion.padMap
SS = %s
pm.single = function() return SS end
pm.combatTopOn = function() return SS end
pm.combatCastTopOn = function() return SS end
''' % ("true" if single else "false"))
    tick = twm.ticker(L, DT)
    tick(40)
    G.PLACED = L.table()
    tick(2)
    return L, G, tick


def last(G):
    """where the Wings stand now: the last laid frame (NB.topPlace), else the spot the settled bar stands at (NB.top.cur)"""
    if len(G.PLACED) > 0:
        e = G.PLACED[len(G.PLACED)]
        return float(e.lx), float(e.ly), float(e.rx), float(e.ry), float(e.s)
    T = G.WowCompanion.nbar.top
    c = T.cur
    return float(c.l.x), float(c.l.y), float(c.r.x), float(c.r.y), float(c.s) * float(T.m or 1)


def spot(G):
    sp = G.WowCompanion.nbar.top.spot
    return float(sp.l.x), float(sp.l.y), float(sp.r.x), float(sp.r.y), float(sp.s)


def kt_const(name):
    m = re.search(r"const val %s = ([0-9.]+)f?" % name, open(SPL, encoding="utf-8").read())
    return float(m.group(1)) if m else None


# ------------------------------------------------------------------------------------------------------------------------ A the tags
def part_a(G):
    src = open(AB, encoding="utf-8").read()
    chk("A. ActionBar nbar28 and its note", 'local NB = { V = "nbar31"' in src and "nbar28 THE SINGLE SCREEN POLISH" in src)
    TT = G.WowCompanion.nbar.TOP
    for lua, kt in (("REST_K", "WINGS_REST_K"), ("REST_IN", "WINGS_REST_IN"), ("REST_UP", "WINGS_REST_UP")):
        chk("A. NB.TOP.%s = SinglePlayLayout.%s" % (lua, kt), abs(float(TT[lua]) - (kt_const(kt) or -1)) < 1e-6, (TT[lua], kt_const(kt)))
    chk("A. the row's room: REST_FLOOR = 1080 - the row's top + the docks' GAP (114)", float(TT.REST_FLOOR) == 114.0, TT.REST_FLOOR)


# ------------------------------------------------------------------------------------------------------------------------ B at rest
def rest_expect(G, sp):
    NB = G.WowCompanion.nbar
    TT = NB.TOP
    lx, ly, rx, ry, s = sp
    sR = s * float(TT.REST_K)
    hw, hh, ax, ay = NB.halfBox()
    floor = 1080 - float(TT.REST_FLOOR) - (float(hh) - float(ay)) * sR
    return lx + float(TT.REST_IN), min(ly - float(TT.REST_UP), floor), rx - float(TT.REST_IN), min(ry - float(TT.REST_UP), floor), sR


def boxes_of(G, pl):
    hw, hh, ax, ay = [float(v) for v in G.WowCompanion.nbar.halfBox()]
    lx, ly, rx, ry, s = pl
    return [(lx - ax * s, ly - ay * s, hw * s, hh * s), (rx - (hw - ax) * s, ry - ay * s, hw * s, hh * s)]


def part_b():
    L, G, tick = scene(True)
    T = G.WowCompanion.nbar.top
    chk("B. the mode on, no fight: the Wings up", bool(T.shown) and bool(T.want), (T.shown, T.want))
    chk("B. no takeover (the cast bar's word off)", not bool(T.takeover), T.takeover)
    chk("B. at rest (rk 1)", float(T.rk or 0) == 1.0, T.rk)
    sp, pl = spot(G), last(G)
    ex = rest_expect(G, sp)
    chk("B. the size x REST_K", abs(pl[4] - ex[4]) < 1e-6, (pl[4], ex[4]))
    chk("B. each half REST_IN closer, REST_UP higher (or its feet over the floor)",
        all(abs(a - b) < 0.01 for a, b in zip(pl[:4], ex[:4])), (pl, ex))
    chk("B. closer together than in the fight", (pl[2] - pl[0]) < (sp[2] - sp[0]) and pl[1] < sp[1], (pl, sp))
    bx = boxes_of(G, pl)
    feet = max(b[1] + b[3] for b in bx)
    chk("B. the feet over the room row (1080 - REST_FLOOR)", feet <= 1080 - 114 + 0.01, feet)
    K3 = (290, 776, 1630, 966)
    inside = all(b[0] >= K3[0] and b[1] >= K3[1] - 0.01 and b[0] + b[2] <= K3[2] and b[1] + b[3] <= K3[3] + 0.01 for b in bx)
    chk("B. inside the app's K3 (the docks keep clear of it)", inside, [tuple(round(v) for v in b) for b in bx])
    return L, G, tick


# ------------------------------------------------------------------------------------------------------------------------ C a fight
def part_c():
    L, G, tick = scene(True)
    T = G.WowCompanion.nbar.top
    TT = G.WowCompanion.nbar.TOP
    rest = last(G)
    G.PLACED = L.table()
    G.COMBAT = True
    shown_all = True
    n = int(float(TT.REST_IN_S) / DT) + 4
    for _ in range(n):
        tick(1)
        shown_all = shown_all and bool(T.shown)
    sp = spot(G)
    pl = last(G)
    chk("C. the fight: the takeover", bool(T.takeover), T.takeover)
    chk("C. at the fight spot, full size, within REST_IN_S", all(abs(a - b) < 0.01 for a, b in zip(pl, sp)), (pl, sp))
    chk("C. shown on every frame of the glide (no slide out and in)", shown_all and float(T.t) >= 1, T.t)
    mids = [float(G.PLACED[i].s) for i in range(1, len(G.PLACED) + 1)]
    chk("C. it grows on the way (between the rest size and the fight's)", any(rest[4] + 0.01 < m < sp[4] - 0.01 for m in mids), mids[:6])
    G.PLACED = L.table()
    G.COMBAT = False
    n = int(float(TT.REST_OUT_S) / DT) + 4
    shown_all = True
    for _ in range(n):
        tick(1)
        shown_all = shown_all and bool(T.shown)
    pl = last(G)
    ex = rest_expect(G, spot(G))
    chk("C. the fight's end: back at rest within REST_OUT_S", all(abs(a - b) < 0.01 for a, b in zip(pl, ex)), (pl, ex))
    chk("C. shown all the way", shown_all)


# ------------------------------------------------------------------------------------------------------------------------ D two screens
def part_d():
    L, G, tick = scene(False)
    T = G.WowCompanion.nbar.top
    # two screens with the player's own "In fights: Top screen" (the saved flag on): the Wings in fights only, as before
    L.execute("WowCompanion.padMap.combatTopOn = function() return true end")
    tick(40)
    chk("D. the mode off, no fight: no Wings (two screens unchanged)", not bool(T.shown) and not bool(T.want), (T.shown, T.want))
    G.COMBAT = True
    tick(60)
    chk("D. the mode off, a fight: the Wings at the fight spot, no rest", bool(T.shown) and float(T.rk or 0) == 0.0 and
        all(abs(a - b) < 0.01 for a, b in zip(last(G), spot(G))), (T.rk, last(G), spot(G)))
    L, G, tick = scene(True)
    T = G.WowCompanion.nbar.top
    G.SS = False
    tick(60)
    chk("D. the mode turned off at rest: the Wings leave", not bool(T.shown), T.shown)


# ------------------------------------------------------------------------------------------------------------------------ E the cast bar
def part_e():
    import test_top_castbar as ttc
    L = ttc.boot()
    G = L.globals()
    L.execute('''
local pm = WowCompanion.padMap
pm.single = function() return true end
pm.combatTopOn = function() return true end
pm.combatCastTopOn = function() return true end
CCB = true
''')
    tick = ttc.tick_fn(L)
    tick(60)
    T = G.WowCompanion.nbar.top
    chk("E. at rest with no cast: the Wings up, our top cast bar hidden", bool(T.shown) and not ttc.shown(G), (T.shown, ttc.shown(G)))
    L.execute('CASTSTART("Hearthstone", "Hearthstone", 10.0)')
    tick(30)
    chk("E. a Hearthstone at rest: our top cast bar shows it (nbar25)", ttc.shown(G))


# ------------------------------------------------------------------------------------------------------------------------ F the icons
def part_f():
    L, G, tick = scene(True)
    NB = G.WowCompanion.nbar
    t = L.eval('function() return CreateFrame("Frame"):CreateTexture() end')()

    def tc():
        return [round(float(t.tcoord[i]), 4) for i in range(1, 5)] if t.tcoord else None

    NB.paint(t, 136058)
    chk("F. a file id: cropped to the bottom band's 0.07..0.93", tc() == [0.07, 0.93, 0.07, 0.93], tc())
    t.tcoord = None
    NB.paint(t, "tex:Interface\\Icons\\INV_Misc_Rune_01")
    chk("F. an icon path: cropped", tc() == [0.07, 0.93, 0.07, 0.93], tc())
    NB.paint(t, "tex:Interface\\AddOns\\WowCompanion\\Media\\ds_forever_icon")
    chk("F. our own media: whole", tc() == [0.0, 1.0, 0.0, 1.0], tc())
    t.tcoord = None
    NB.paint(t, "atlas:crosshair_inspect_32")
    chk("F. an atlas: its own coordinates (no crop of ours)", tc() is None, tc())
    f = NB.f
    seen, bad = 0, []
    for p in range(1, 9):
        b = f.btn[p]
        if b is None or b.icon is None or b.icon.tex is None:
            continue
        if isinstance(b.icon.tex, (int, float)) or str(b.icon.tex).lower().startswith("interface\\icons"):
            seen += 1
            got = [round(float(b.icon.tcoord[i]), 4) for i in range(1, 5)] if b.icon.tcoord else None
            if got != [0.07, 0.93, 0.07, 0.93]:
                bad.append((p, got))
    chk("F. the Wings' face icons cropped after a refresh (%d icons)" % seen, seen > 0 and not bad, bad or seen)


# ------------------------------------------------------------------------------------------------------------------------ G no trace
def part_g():
    src = open(AB, encoding="utf-8").read()
    a = src.index("function NB.restSpot(")
    b = src.index("function NB.topApply()")
    code = src[a:b] + src[src.index("-- nbar28: an icon's picture"):src.index("-- nbar11 THE RING LOOK's art")]
    tick = src[src.index("local restOn = T.single"):src.index("if want ~= T.want then")]
    bad = [w for w in ("SetCVar", "SetBinding", "SetOverrideBinding", "CreateMacro", "EditMacro", "SecureHandler", "RegisterStateDriver",
                       "SetAttribute", "RunMacro") if w in code + tick]
    chk("G. nbar28's code: nothing secure, no CVar / binding / macro", not bad, bad)


def main():
    L, G, tick = scene(True)
    part_a(G)
    part_b()
    part_c()
    part_d()
    part_e()
    part_f()
    part_g()
    print("%d passed, %d failed" % (passes, len(fails)))
    if fails:
        print("FAILED: " + "; ".join(fails))
        sys.exit(1)


if __name__ == "__main__":
    main()
