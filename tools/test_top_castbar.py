"""nbar18 OUR OWN CAST BAR ABOVE THE HUD (the user, 2026-11-24: "I have changed my mind on using the normal in game cast bar, I want to
build our own that appears right above the HUD. It can look similar to the normal in game one with the text below the cast bar, the main
thing is that I only want to see it on the top screen when everything else is also on the top screen during or engaging in combat. Not
with stuff like dienchanting or opening shit, that can stay on the bottom screen"; picks: "Keep it, Top = our new bar" and the swing
timer "With the cast bar on top"; then "I kind of want the option for the cast bar and swing timer to appear on the bottom screen if
everything else is on top" = the Bottom screen choice, kept exactly), off-device: the REAL Safety.lua + ActionBar.lua under
test_wings_match.py's scene (test_nintendo_bar.py's stub game, a Trail-like HUD block and Hub-like Party rows that fade and move as
prd22 / hub42 do), driven frame by frame through the add-on's own driver, the player's casts fired as the game fires them
(UNIT_SPELLCAST_* for "player", UnitCastingInfo / UnitChannelInfo answering).

    python -B tools/test_top_castbar.py

  0 THE TAGS: ActionBar nbar18 (nbar13 / nbar14 / nbar17 notes kept); the 1523 game's-bar path GONE from ActionBar (no NB.castFight,
    no WC.hubCastFight call, no gameSwingOn read, no `gsw`).
  A WHERE IT STANDS: NB.cbGeo (pure) - centred on the HUD block, its bottom CB.GAP px over the block's top, the game's own size (208 x 11
    units at the UI scale: kk px a unit) with the swing lines' room over it and the name plate under it; the screen's edges kept; no
    block = the game's own spot (centred, CB.FLOOR over the bottom). Live: the solver's HUD obstacle holds the bar (the Wings >=
    HUD_MARGIN from it, frame by frame), the Wings' level unchanged by it (they stand where they stood), a crowded screen's joined bar
    above it; Party mode's centring carries it with the block (centred on the screen once landed, never over a Wings half on any frame).
  B WHEN IT SHOWS: only with Cast bar = Top screen (PadMap's combatCastTopOn) AND the Wings' switch on (the takeover: the fight, or an
    attack armed before it) - a cast out of the takeover (Disenchant, Opening, Hearthstone) never shows on top; Bottom screen never
    shows it (the bottom strip carries cast and swing); a cast starting in the takeover's first frame shows at once (no wait for the
    Wings' hold), a cast started before the takeover shows from the takeover's first frame at its own progress; B's give-back / the
    fight's end mid-cast or mid-channel: gone within CB.OUT_S; a pet's or a vehicle's cast never shows.
  C THE LOOK: the game's own casting bar art (UI-CastingBar-* atlases: the standard fill growing, the channel fill draining, the
    uninterruptible fill, the interrupted bar held 1 s and faded 0.3 s with the game's INTERRUPTED / FAILED word, the success's full bar
    with its glow in over 0.2 s, faded by 0.5 s), the spark on the fill's edge, the cast's display text BELOW the bar on the TextBox plate
    (a blank display text: no plate), the remaining time only when the game's own bar shows it (showCastTimeSetting), a pushback
    (DELAYED) moves the end; a FAILED for another spell while the cast runs ("Another action is in progress") leaves the cast alone.
  D THE SWING WITH IT: Cast bar = Top screen in the takeover - the swing line(s) just OVER our bar (the main hand's on top, the off hand
    thinner under it, hugging the bar), as wide as the bar; the Wings' own swing lines never (one place); the app's Swing timer Off
    (PadMap m190 combatCastSwingOn false) = no top swing; Bottom screen = none on top (the bottom strip has both); out of the takeover none.
  E LEAVE NO TRACE: plain frames only (no Secure template, EnableMouse never true), no SetCVar / SetBinding / macro call, nothing of
    Blizzard's called but reads, built in a fight too (a /reload mid-fight), every anchor UIParent or ours.
No device is read. Needs lupa. Exit code 1 on any failure."""
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import test_wings_match as twm  # noqa: E402

ROOT = twm.ROOT
AB = os.path.join(twm.ADDONS, "WowCompanion", "ActionBar.lua")
fails = []
passes = 0


def chk(name, ok, got=""):
    global passes
    print(("PASS " if ok else "FAIL ") + name + ((" -> " + str(got)) if got != "" and not ok else ""))
    if ok:
        passes += 1
    else:
        fails.append(name)


# the player's casts as the game reports them (UnitCastingInfo: name, text, icon, startMS, endMS, tradeskill, castID, notInterruptible,
# spellID; UnitChannelInfo: name, text, icon, startMS, endMS, tradeskill, notInterruptible, spellID), the events through every frame
# registered for them, and the knobs the bar reads (PadMap's flags, the router's switch, the game's own bar's cast-time option)
CASTS = r'''
CAST, CHAN = nil, nil
function UnitCastingInfo(u) if u == "player" and CAST then return CAST.name, CAST.text, 136058, CAST.s * 1000, CAST.e * 1000, false, 7, CAST.ni, 133 end end
function UnitChannelInfo(u) if u == "player" and CHAN then return CHAN.name, CHAN.text, 136059, CHAN.s * 1000, CHAN.e * 1000, false, CHAN.ni, 5143 end end
function FIRE(e, unit)
    for _, f in ipairs(EVENTS[e] or {}) do if f.onOnEvent then f.onOnEvent(f, e, unit or "player") end end
end
function CASTSTART(name, text, dur, ni) CHAN = nil; CAST = { name = name, text = text, s = NOW, e = NOW + dur, ni = ni and true or false }; FIRE("UNIT_SPELLCAST_START") end
function CHANSTART(name, text, dur, ni) CAST = nil; CHAN = { name = name, text = text, s = NOW, e = NOW + dur, ni = ni and true or false }; FIRE("UNIT_SPELLCAST_CHANNEL_START") end
function CASTDONE() CAST = nil; FIRE("UNIT_SPELLCAST_SUCCEEDED"); FIRE("UNIT_SPELLCAST_STOP") end
function CASTCUT() CAST = nil; FIRE("UNIT_SPELLCAST_INTERRUPTED"); FIRE("UNIT_SPELLCAST_STOP") end
function CHANEND() CHAN = nil; FIRE("UNIT_SPELLCAST_CHANNEL_STOP") end
local pm = WowCompanion.padMap
CCB, FSW, CSW = true, nil, true
pm.combatCastTopOn = function() return CCB end
pm.fightSw = function() return FSW end
pm.combatCastSwingOn = function() return CSW end
PlayerCastingBarFrame = { showCastTimeSetting = false }
'''


def boot():
    L = twm.boot()
    L.execute(CASTS)
    return L


def tick_fn(L, dt=1 / 60):
    G = L.globals()
    NB = G.WowCompanion.nbar

    def tick(n=1):
        for _ in range(n):
            G.NOW = G.NOW + dt
            NB.topTick(dt)
            NB.tick(dt)
            G.MOVES(dt)
    return tick


def cb_of(G):
    return G.WowCompanion.nbar.cb


def shown(G):
    cb = cb_of(G)
    return cb is not None and cb.root is not None and bool(cb.root.shown) and float(cb.a or 0) > 0.001


def look(G):
    cb = cb_of(G)
    return cb.look if cb is not None else None


def rects(G):
    """the bar's drawn parts as the add-on lays them (NB.cbRects: px, from the top-left)"""
    rs = G.WowCompanion.nbar.cbRects()
    out = {}
    if rs is None:
        return out
    for k, v in rs.items():
        out[str(k)] = (float(v.x), float(v.y), float(v.w), float(v.h))
    return out


def over(a, b, m=0.0):
    return twm.over(a, b, m)


def bar_union(G):
    u = None
    for k, r in rects(G).items():
        u = twm.union(u, r)
    return u


# ------------------------------------------------------------------------------------------------------------------------ 0 the tags
def part_0():
    src = open(AB, encoding="utf-8").read()
    chk("0. ActionBar nbar18 and its note (nbar13 / nbar14 / nbar17 notes kept)", 'local NB = { V = "nbar25"' in src
        and "nbar18 OUR OWN CAST BAR ABOVE THE HUD" in src and "nbar13 THE CAST BAR IN A TOP-SCREEN FIGHT" in src
        and "nbar14 THE WINGS MATCH THE BAND" in src and "nbar17 THE WINGS AS THE BAND ON THE DEVICE" in src)
    code = "\n".join(l.split("--", 1)[0] for l in src.splitlines())
    chk("0. the 1523 game's-bar path is gone: no NB.castFight, no WC.hubCastFight, no gameSwingOn read, no T.gsw",
        "function NB.castFight" not in code and "hubCastFight" not in code and "gameSwingOn" not in code and "T.gsw" not in code
        and "NB.top.gsw" not in code, [w for w in ("function NB.castFight", "hubCastFight", "gameSwingOn", "T.gsw") if w in code])
    chk("0. the cast bar's pieces are the game's own casting bar atlases (no game pixels shipped)",
        all(a in src for a in ("UI-CastingBar-Filling-Standard", "UI-CastingBar-Filling-Channel", "UI-CastingBar-Uninterruptable",
                               "UI-CastingBar-Interrupted", "UI-CastingBar-Full-Standard", "UI-CastingBar-Full-Glow-Standard",
                               "UI-CastingBar-Pip", "UI-CastingBar-TextBox", "UI-CastingBar-Background", "UI-CastingBar-Frame")))


# ------------------------------------------------------------------------------------------------------------------------ A geometry
def part_a():
    L = boot()
    G = L.globals()
    NB = G.WowCompanion.nbar
    C = NB.CB
    geo = NB.cbGeo
    tbl = L.eval("function(x, y, w, h) return { x = x, y = y, w = w, h = h } end")
    bad = []
    for kk in (1.0, 1080 / 817.0, 1080 / 768.0, 1080 / 1200.0):
        for bx, by, bw, bh in ((820, 890, 280, 140), (300, 700, 420, 220), (1500, 500, 180, 60), (840, 80, 240, 100), (10, 600, 200, 80)):
            g = geo(tbl(bx, by, bw, bh), 1920, 1080, kk, False)
            w_units = float(C.W) + 2 * float(C.BORDER)
            want_w = w_units * kk
            cx = float(g.cx)
            r = (float(g.x), float(g.y), float(g.w), float(g.h))
            ok = abs(r[2] - want_w) < 0.01 and r[0] >= -0.01 and r[1] >= -0.01 and r[0] + r[2] <= 1920.01
            if by - float(C.GAP) - r[3] >= 0:          # room above: its bottom GAP over the block, centred on it unless an edge holds it
                ok = ok and abs((r[1] + r[3]) - (by - float(C.GAP))) < 0.01
                if bx + bw / 2 - want_w / 2 >= 0 and bx + bw / 2 + want_w / 2 <= 1920:
                    ok = ok and abs(cx - (bx + bw / 2)) < 0.01
            if not ok:
                bad.append((kk, (bx, by, bw, bh), r, cx))
    chk("A. NB.cbGeo: the game's bar width (208 + 2 x 2 border units) x the UI scale, centred on the block, its bottom CB.GAP px over "
        "the block's top, inside the screen (4 UI scales x 5 blocks)", not bad, bad[:3])
    g = geo(None, 1920, 1080, 1.0, False)
    chk("A. no HUD block: the game's own spot - centred, CB.FLOOR px over the bottom", abs(float(g.cx) - 960) < 0.01
        and abs(float(g.y) + float(g.h) - (1080 - float(C.FLOOR))) < 0.01, (float(g.cx), float(g.y), float(g.h)))
    g1 = geo(tbl(820, 890, 280, 140), 1920, 1080, 1.0, True)
    g0 = geo(tbl(820, 890, 280, 140), 1920, 1080, 1.0, False)
    chk("A. the game's cast-time text shown: the box reaches past the bar's right end (room for it), the bar still centred",
        float(g1.w) > float(g0.w) and abs(float(g1.cx) - float(g0.cx)) < 0.01 and abs(float(g1.x) - float(g0.x)) < 0.01)

    # live: the Wings with the bar's room and without it
    def spot_for(ccb):
        L2 = boot()
        G2 = L2.globals()
        G2.CCB = ccb
        t = tick_fn(L2)
        t(40)
        sp = G2.WowCompanion.nbar.top.spot
        return L2, G2, (float(sp.l.x), float(sp.l.y), float(sp.r.x), float(sp.r.y), sp.case)
    _, _, s_off = spot_for(False)
    L3, G3, s_on = spot_for(True)
    chk("A. the Wings stand where they stood: the bar's room above the HUD does not move them (level with the HUD block's own middle)",
        s_on == s_off, (s_on, s_off))
    NB3 = G3.WowCompanion.nbar
    at = NB3.top.cbAt
    chk("A. live: the bar's spot over the scene's default HUD block (820, 890, 280 wide): centred at 960, its bottom at 890 - GAP",
        at is not None and abs(float(at.cx) - 960) < 0.01 and abs(float(at.y) + float(at.h) - (890 - float(NB3.CB.GAP))) < 0.01,
        at and (float(at.cx), float(at.y), float(at.h)))
    inp = NB3.topInputs()
    hud = inp.hud
    chk("A. the solver's HUD obstacle holds the bar (the block unioned with the bar's box; the Wings' level from the block alone)",
        hud is not None and float(hud.y) <= float(at.y) + 0.01 and inp.hudMid is not None
        and abs(float(inp.hudMid) - (890 + 140 / 2)) < 0.01, (hud and float(hud.y), at and float(at.y), inp.hudMid))

    # a crowded screen: walls beside the HUD -> the Wings joined above (case 3): above the bar too, 60 px clear
    L4 = boot()
    G4 = L4.globals()
    NB4 = G4.WowCompanion.nbar
    G4.WowCompanion.nbar.top.game = L4.eval('{ { x = 0, y = 600, w = 760, h = 480, id = "g.ChatFrame1" }, '
                                           '{ x = 1160, y = 600, w = 760, h = 480, id = "g.ObjectiveTrackerFrame" } }')
    L4.execute('WowCompanion.nbar.topGame = function() return WowCompanion.nbar.top.game end')
    t4 = tick_fn(L4)
    t4(40)
    sp = NB4.top.spot
    at4 = NB4.top.cbAt
    br = (float(at4.x), float(at4.y), float(at4.w), float(at4.h))
    halves = [(float(sp.rects[i].x), float(sp.rects[i].y), float(sp.rects[i].w), float(sp.rects[i].h)) for i in (1, 2)]
    chk("A. crowded (walls either side): the Wings joined ABOVE the HUD block and the bar (case 3 or the grid), >= HUD_MARGIN from the bar",
        sp.case in (3, 4) and all(not over(h, br, 60 - 0.01) for h in halves), (sp.case, halves, br))

    # frame by frame through a fight with a cast: no Wings half box ever over a drawn part of the bar; and Party mode's centring
    for party, x0 in ((False, 820), (True, 1100), (True, 520)):
        L5 = boot()
        G5 = L5.globals()
        G5.PARTY = party
        G5.HUD.members = 3 if party else 1
        G5.HUD.x0 = x0
        G5.WowCompanion.prd.place()
        G5.WowCompanion.uf.pf.draw()
        t5 = tick_fn(L5, 1 / 30)
        t5(30)
        G5.PLACED = L5.table()
        G5.COMBAT = True
        hits, seen = [], 0
        L5.execute('CASTSTART("Frostbolt", "Frostbolt", 3.0)')
        for i in range(int(3.0 * 30)):
            t5(1)
            if shown(G5):
                seen += 1
                bu = bar_union(G5)
                for b in twm.wings_boxes(G5, L5):
                    if bu and over(b, bu):
                        hits.append(round(float(G5.NOW), 2))
                for nm, r in twm.drawn_parts(G5):
                    if bu and over(r, bu):
                        hits.append((round(float(G5.NOW), 2), nm))
        at5 = G5.WowCompanion.nbar.top.cbAt
        blk = None
        for nm, r in twm.drawn_parts(G5):
            if nm.startswith("row"):
                r = (r[0] - 12, r[1] - 12, r[2] + 24, r[3] + 24)
            blk = twm.union(blk, r)
        tag = "Party mode, the block at x %d" % x0 if party else "solo"
        chk("A. %s: the bar shown through the cast, never over a Wings half or a drawn HUD / row part on any frame" % tag,
            seen > 60 and not hits, (seen, hits[:4]))
        if party:
            cb = cb_of(G5)
            chk("A. %s: centred with the block once it has landed (the block centred on the screen, the bar's centre on the block's)" % tag,
                at5 is not None and abs(float(at5.cx) - 960) < 1.0 and abs(float(cb.x) - 960) < 1.0 and blk is not None
                and abs(blk[0] + blk[2] / 2 - 960) < 30, (at5 and float(at5.cx), float(cb.x), blk))


# ------------------------------------------------------------------------------------------------------------------------ B when
def part_b():
    L = boot()
    G = L.globals()
    NB = G.WowCompanion.nbar
    tick = tick_fn(L)
    tick(40)
    # out of the takeover: a Disenchant / an Opening / a Hearthstone - the bottom screen's (never on top)
    vis = 0
    for nm, d in (("Disenchant", 1.0), ("Opening", 0.8), ("Hearthstone", 1.2)):
        L.execute('CASTSTART(%r, %r, %f)' % (nm, nm, d))
        for _ in range(int(d * 60) + 10):
            tick(1)
            vis += 1 if shown(G) else 0
        L.execute('CASTDONE()')
        tick(40)
        vis += 1 if shown(G) else 0
    chk("B. Cast bar = Top screen, OUT of the takeover: Disenchant / Opening / Hearthstone never on the top screen (the bottom's)",
        vis == 0 and not NB.top.want, (vis, NB.top.want))
    # the takeover and a cast in the same frame: on top from its first frame (no wait for the Wings' hold)
    G.COMBAT = True
    L.execute('CASTSTART("Frostbolt", "Frostbolt", 2.5)')
    tick(1)
    lk = look(G)
    chk("B. the takeover and a cast starting in the same frame: the bar on top at once (alpha 1, the cast in it) while the Wings still hold",
        shown(G) and abs(float(cb_of(G).a) - 1) < 1e-6 and lk is not None and lk.kind == "c" and not NB.top.shown,
        (shown(G), lk and lk.kind, NB.top.shown))
    tick(30)
    # B's give-back mid-cast (the fight goes on): gone within OUT_S
    G.FSW = False
    tick(1)
    a1 = float(cb_of(G).a)
    tick(int(float(NB.CB.OUT_S) * 60) + 2)
    chk("B. B's give-back mid-cast: the bar fades with the Wings (alpha < 1 at once) and is gone within CB.OUT_S, the cast still running",
        a1 < 1 and not shown(G) and G.CAST is not None, (a1, shown(G)))
    # re-armed: back at the cast's own progress
    G.FSW = None
    tick(1)
    lk = look(G)
    chk("B. re-armed mid-cast: on top again from the switch's first frame, at the cast's own progress (not restarted)",
        shown(G) and lk is not None and lk.kind == "c" and float(lk.p) > 0.3, (shown(G), lk and float(lk.p)))
    L.execute('CASTDONE()')
    tick(60)
    # a cast started BEFORE the takeover: shows from the takeover's first frame
    G.COMBAT = False
    L.execute('EV = nil')
    tick(60)
    L.execute('CASTSTART("Hearthstone", "Hearthstone", 10.0)')
    tick(120)
    chk("B. a non-combat cast running out of the takeover: not on top", not shown(G))
    G.COMBAT = True
    tick(1)
    lk = look(G)
    chk("B. ... the fight starts mid-cast (the takeover): on top from its first frame, at its own progress (~2 s of 10)",
        shown(G) and lk is not None and abs(float(lk.p) - 0.2) < 0.03, (shown(G), lk and float(lk.p)))
    # the fight's end mid-channel: gone within OUT_S
    L.execute('CHANSTART("Arcane Missiles", "Arcane Missiles", 5.0)')
    tick(30)
    chk("B. a channel in the fight: on top, draining", shown(G) and look(G).kind == "h")
    G.COMBAT = False
    tick(int(float(NB.CB.OUT_S) * 60) + 2)
    chk("B. the fight's end mid-channel: gone within CB.OUT_S (the bottom screen's band carries it down)", not shown(G) and G.CHAN is not None)
    L.execute('CHANEND()')
    tick(60)
    # Bottom screen: never on top, in the takeover too
    G.CCB = False
    tick(40)
    G.COMBAT = True
    L.execute('CASTSTART("Frostbolt", "Frostbolt", 2.0)')
    seen = 0
    for _ in range(100):
        tick(1)
        seen += 1 if shown(G) else 0
    chk("B. Cast bar = Bottom screen: never on top, in the takeover too (the bottom strip carries the cast)", seen == 0 and NB.top.want, seen)
    L.execute('CASTDONE()')
    G.CCB = True
    tick(40)
    # a pet's / a vehicle's cast: not the player's
    L.execute('CAST = nil; CHAN = nil')
    tick(120)
    L.execute('CAST = { name = "Firebolt", text = "Firebolt", s = NOW, e = NOW + 2 }; FIRE("UNIT_SPELLCAST_START", "pet"); CAST = nil')
    tick(5)
    lk = look(G)
    chk("B. a pet's cast never shows on our bar (the player's only)", lk is None or lk.kind is None, lk and lk.kind)
    L.execute('CAST = { name = "Cannon", text = "Cannon", s = NOW, e = NOW + 2 }; FIRE("UNIT_SPELLCAST_START", "vehicle"); CAST = nil')
    tick(5)
    lk = look(G)
    chk("B. a vehicle's cast never shows on our bar", lk is None or lk.kind is None, lk and lk.kind)
    chk("B. the game's own casting bar never asked for (no WC.hubCastFight)", G.WowCompanion.hubCastFight is None)


# ------------------------------------------------------------------------------------------------------------------------ C look
def part_c():
    L = boot()
    G = L.globals()
    NB = G.WowCompanion.nbar
    C = NB.CB
    tick = tick_fn(L)
    tick(40)
    G.COMBAT = True
    tick(40)
    L.execute('CASTSTART("Frostbolt", "Frostbolt", 2.0)')
    ps = []
    for _ in range(20):
        tick(3)
        ps.append(float(look(G).p))
    lk = look(G)
    chk("C. a cast: the standard fill growing every frame (the game's UI-CastingBar-Filling-Standard)",
        all(b > a for a, b in zip(ps, ps[1:])) and lk.atlas == "UI-CastingBar-Filling-Standard", (ps[:4], lk.atlas))
    cb = cb_of(G)
    rs = rects(G)
    bar, plate = rs.get("bar"), rs.get("plate")
    sx = float(cb.sparkX) if cb.sparkX is not None else None
    chk("C. ... the spark on the fill's edge", bool(cb.spark.shown) and sx is not None and abs(sx - (bar[0] + float(lk.p) * bar[2])) < 0.6,
        (sx, bar, float(lk.p)))
    chk("C. ... the cast's display text BELOW the bar on the game's TextBox plate (its top at the bar's bottom border)",
        str(cb.name.text) == "Frostbolt" and bool(cb.plate.shown) and plate is not None and plate[1] >= bar[1] + bar[3] - 0.01
        and cb.plate.atlas == "UI-CastingBar-TextBox", (cb.name.text, plate, bar, cb.plate.atlas))
    chk("C. ... no remaining time while the game's own bar does not show it", not cb.time.shown)
    L.execute('PlayerCastingBarFrame.showCastTimeSetting = true')
    tick(2)
    chk("C. ... the remaining time beside it when the game's bar shows its cast time (showCastTimeSetting)", bool(cb.time.shown)
        and re.match(r"^\d+\.\d$", str(cb.time.text or "")) is not None, cb.time.text)
    L.execute('PlayerCastingBarFrame.showCastTimeSetting = false')
    # pushback
    e0 = float(G.CAST.e)
    L.execute('CAST.e = CAST.e + 0.5; FIRE("UNIT_SPELLCAST_DELAYED")')
    tick(1)
    p_after = float(look(G).p)
    tick(1)
    chk("C. a pushback (DELAYED): the end moves, the fill steps back", p_after < ps[-1] and float(G.CAST.e) > e0, (p_after, ps[-1]))
    # another spell's FAILED while this cast runs ("Another action is in progress")
    L.execute('FIRE("UNIT_SPELLCAST_FAILED")')
    tick(2)
    chk("C. a FAILED for another press while the cast runs: the cast goes on (not shown interrupted)", look(G).kind == "c", look(G).kind)
    # success: the full bar, the glow in over 0.2 s, faded out by 0.5 s
    L.execute('CASTDONE()')
    tick(1)
    lk = look(G)
    g0 = float(lk.glow)
    tick(6)
    g1 = float(look(G).glow)
    chk("C. the success: the full bar (UI-CastingBar-Full-Standard), its glow rising", lk.kind == "ok" and lk.atlas == "UI-CastingBar-Full-Standard"
        and g1 > g0, (lk.kind, lk.atlas, g0, g1))
    tick(12)
    a_hold = float(look(G).alpha)
    tick(20)
    lk = look(G)
    chk("C. ... held, then faded out by 0.5 s", a_hold > 0.5 and (lk is None or float(lk.alpha) < 0.05), (a_hold, lk and lk.kind))
    # channel: the channel fill draining
    L.execute('CHANSTART("Mind Flay", "Mind Flay", 3.0)')
    ps = []
    for _ in range(10):
        tick(4)
        ps.append(float(look(G).p))
    chk("C. a channel: the channel fill DRAINING (UI-CastingBar-Filling-Channel)", all(b < a for a, b in zip(ps, ps[1:]))
        and look(G).atlas == "UI-CastingBar-Filling-Channel", (ps[:3], look(G).atlas))
    L.execute('CHANEND()')
    tick(60)
    # uninterruptible
    L.execute('CASTSTART("Polymorph", "Polymorph", 1.5, true)')
    tick(3)
    chk("C. an uninterruptible cast: the game's UI-CastingBar-Uninterruptable fill", look(G).atlas == "UI-CastingBar-Uninterruptable", look(G).atlas)
    # interrupted: the red bar with the game's word, held 1 s, faded by 1.3 s
    L.execute('CASTCUT()')
    tick(1)
    lk = look(G)
    chk("C. interrupted: the game's UI-CastingBar-Interrupted bar, full, with its INTERRUPTED word (no spark)", lk.kind == "fail"
        and lk.atlas == "UI-CastingBar-Interrupted" and abs(float(lk.p) - 1) < 1e-6 and str(cb.name.text) == "Interrupted"
        and not cb.spark.shown, (lk.kind, lk.atlas, cb.name.text))
    tick(50)
    a_hold = float(look(G).alpha)
    tick(36)
    lk = look(G)
    chk("C. ... held 1 s, then faded by 1.3 s", a_hold > 0.99 and (lk is None or float(lk.alpha) < 0.05), (a_hold, lk and lk.kind))
    # a blank display text (Opening - No Text): no plate
    L.execute('CASTSTART("Opening", "", 2.0)')
    tick(3)
    chk("C. a blank display text: the bar alone, no name plate", not cb.plate.shown and str(cb.name.text or "") == "", (cb.plate.shown, cb.name.text))
    L.execute('CASTDONE()')
    tick(60)


# ------------------------------------------------------------------------------------------------------------------------ D swing
def part_d():
    L = boot()
    G = L.globals()
    NB = G.WowCompanion.nbar
    tick = tick_fn(L)
    tick(40)
    W = G.WowCompanion
    W.swingNote(2.0, 0)
    tick(5)
    chk("D. out of the takeover: no swing line on top", not shown(G) or not cb_of(G).sw[1].shown)
    G.COMBAT = True
    tick(40)
    W.swingNote(2.0, 0)
    tick(5)
    cb = cb_of(G)
    rs = rects(G)
    f = NB.f
    chk("D. Cast bar = Top screen in the takeover: the main hand's swing line shown with our bar (no cast running)",
        shown(G) and bool(cb.sw[1].shown) and not cb.sw[2].shown and 0 < float(cb.sw[1].k) < 1, (shown(G), cb.sw[1].shown))
    bar, sw1 = rs.get("bar"), rs.get("sw1")
    chk("D. ... just OVER the bar (its bottom a few px over the bar's top border), as wide as the bar",
        sw1 is not None and sw1[1] + sw1[3] <= bar[1] + 0.01 and bar[1] - (sw1[1] + sw1[3]) < 8 and abs(sw1[2] - bar[2]) < 0.01
        and abs(sw1[0] - bar[0]) < 0.01, (sw1, bar))
    chk("D. ... the Wings' own swing lines never (one place)", not f.sw[1].shown and not f.sw[2].shown)
    W.swingNote(2.4, 0)
    W.swingNote(1.8, 1)
    L.execute('UnitAttackSpeed = function() return 2.4, 1.8 end')
    tick(5)
    rs = rects(G)
    sw1, sw2, bar = rs.get("sw1"), rs.get("sw2"), rs.get("bar")
    chk("D. dual wield: two lines - the main hand's on top, the off hand's thinner under it, hugging the bar",
        bool(cb.sw[1].shown) and bool(cb.sw[2].shown) and sw1 is not None and sw2 is not None and sw1[1] + sw1[3] <= sw2[1] + 0.01
        and sw2[3] < sw1[3] and sw2[1] + sw2[3] <= bar[1] + 0.01 and bar[1] - (sw2[1] + sw2[3]) < 8, (sw1, sw2, bar))
    L.execute('CASTSTART("Frostbolt", "Frostbolt", 2.0)')
    tick(5)
    chk("D. a cast and the swing together: both with our bar (the swing over it, the cast in it)", bool(cb.sw[1].shown) and look(G).kind == "c")
    L.execute('CASTDONE()')
    G.CSW = False
    tick(40)
    W.swingNote(2.0, 0)
    tick(5)
    chk("D. the app's Swing timer Off (PadMap m190 combatCastSwingOn false): no swing line on top", not cb.sw[1].shown and not cb.sw[2].shown)
    G.CSW = True
    G.CCB = False
    tick(40)
    W.swingNote(2.0, 0)
    tick(5)
    chk("D. Cast bar = Bottom screen: no swing on top (ours nor the Wings': the bottom strip has it - one place)",
        (not shown(G) or not cb.sw[1].shown) and not f.sw[1].shown and not f.sw[2].shown)


# ------------------------------------------------------------------------------------------------------------------------ E no trace
def part_e():
    L = boot()
    G = L.globals()
    NB = G.WowCompanion.nbar
    G.COMBAT = True                                         # a /reload mid-fight: the bar built in the lockdown
    tick = tick_fn(L)
    tick(5)
    L.execute('CASTSTART("Frostbolt", "Frostbolt", 2.0)')
    tick(30)
    G.WowCompanion.swingNote(2.0, 0)
    tick(30)
    L.execute('CASTCUT()')
    tick(30)
    chk("E. built and shown in a fight (plain frames: legal in the lockdown)", cb_of(G) is not None and cb_of(G).root is not None)
    chk("E. no Secure template, EnableMouse never true", len(list(G.BADTPL.values())) == 0 and G.BADMOUSE == 0,
        (list(G.BADTPL.values()), G.BADMOUSE))
    chk("E. no SetCVar / SetBinding / macro / state driver call", len(list(G.SPY.values())) == 0, list(G.SPY.values()))
    chk("E. nothing of Blizzard's called but reads", len([x for x in G.BLIZ.values()]) == 0, list(G.BLIZ.values())[:5])
    # every anchor of the bar's frames: UIParent or one of our own frames (the stub's OURS); the root on UIParent, never the mouse
    bad = L.eval('''function()
        local mine = {}
        for _, o in ipairs(OURS) do mine[o] = true end
        local cb, out = WowCompanion.nbar.cb, {}
        local function walk(o)
            for _, p in ipairs(o.pts or {}) do
                local rel = (p[1] == "ALL") and p[2] or p[2]
                if type(rel) == "table" and rel ~= UIParent and not mine[rel] then out[#out + 1] = tostring(p[1]) end
            end
            for _, k in ipairs(o.kids or {}) do walk(k) end
        end
        walk(cb.root)
        return #out, cb.root.parent == UIParent and cb.root.mouse ~= true
    end''')()
    chk("E. every anchor of the bar's frames UIParent or one of our own frames", bad[0] == 0, bad[0])
    chk("E. the root parented to UIParent, never the mouse", bad[1] is True)


def lupa_type(v):
    return twm.lupa.lua_type(v)


def main():
    for fn in (part_0, part_a, part_b, part_c, part_d, part_e):
        try:
            fn()
        except Exception as e:      # noqa: BLE001
            chk("%s ran without a Lua / Python error" % fn.__name__, False, repr(e)[:400])
    print("passes: %d  failed: %d" % (passes, len(fails)))
    sys.exit(1 if fails else 0)


if __name__ == "__main__":
    main()
