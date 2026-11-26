"""SINGLE SCREEN MODE, THE ADD-ON'S HALF (the user, 2026-11-25: "a single screen mode where the game shifts to the top screen when a button
is pushed. We need to rebuild all of our menus and features to work on the top screen when single screen mode is selected"; the pick for
"While playing": the game's HUD and our Nintendo bar, in fights the Wings and our top cast bar - the cast bar out of fights too, as no
bottom screen carries it now), off-device: the REAL add-on files under the stub games the other suites use.

    python -B tools/test_single_top.py

  A THE CHANNEL (Book b86 / PadMap m199, test_edit_preview.py's world): the FOURTH page (padtab 39, 38, 0 then N) sets the app's mode -
    0 off, 1 on; WC.padMap.single() says it, the padmap's dpad state says it (`ss`, 1 / 0) and is sent again; while on, combatTopOn /
    combatCastTopOn answer true (the Wings, our top bar in fights) while the padmap's ct / cc still say the SAVED flags (0 here) and the
    saved variables never change; IN MEMORY ONLY: a lease of SS_TTL s (gone after it with no word from the app; an expired lease in a
    fight lasts until the fight's end), none after a /reload; the fourth page's pick lasts 2 s; the earlier pages still their own
    (the third page's 27 the swing, the second page's codes); no spied call (no CVar / binding / macro), Lua 5.1's 200 locals kept.
  B OUR TOP CAST BAR FOR EVERY CAST (ActionBar nbar25, test_top_castbar.py's scene): the saved flags OFF and no fight - the mode off: a
    Hearthstone shows nothing on top (two screens: the bottom band has it); the mode on: our top cast bar shows the Hearthstone at once
    (the fill part way, its name), no Wings out of the fight, gone after its finish; no cast: nothing; a fight with the mode on: the
    Wings and our bar (as a Top-screen fight); Edit Home's preview still its own; plain frames only, nothing secure or written.
  C NO TRACE (the source): the new code calls no SetCVar / SetBinding / macro API and no Alt / F-key chord.
  D HOLD TO CAST x THE DOCKS (the 1533 merge, ActionBar nbar26): the hold badge's glowing ring and the takeover's gold checked ring
    round every slot stay inside each Wings half's box (the solver's rect: SinglePlayLayout's K4) and inside the Nintendo bar's focus
    glow, which keeps clear of every OUT dock box - no hold piece under a play dock.
No device is read. Needs lupa. Exit code 1 on any failure."""
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from lupa import lua51 as lupa  # noqa: E402,F401

import test_edit_preview as tep  # noqa: E402

ROOT = os.path.dirname(HERE)
ADDONS = os.path.join(ROOT, "companion", "app", "src", "main", "assets", "companion", "addons")
fails = []
passes = 0


def chk(name, ok, got=""):
    global passes
    print(("PASS " if ok else "FAIL ") + name + ((" -> " + str(got)) if got != "" and not ok else ""))
    if ok:
        passes += 1
    else:
        fails.append(name)


def src(*p):
    return open(os.path.join(ADDONS, *p), encoding="utf-8").read()


SER = ('(function() local function ser(v, d) if type(v) ~= "table" then return tostring(v) end; if d > 8 then return "?" end; '
       'local ks = {}; for k in pairs(v) do ks[#ks + 1] = tostring(k) end; table.sort(ks); local o = {}; '
       'for _, k in ipairs(ks) do local x = v[k]; if x == nil then x = v[tonumber(k)] end; o[#o + 1] = k .. "=" .. ser(x, d + 1) end; '
       'return "{" .. table.concat(o, ",") .. "}" end; SER = ser; return ser(WowCompanionSpellsDB, 0) end)()')


# ------------------------------------------------------------------------------------------------------------ A the channel
def part_a():
    print("\n-- A the channel (Book b86 / PadMap m199)")
    pm_src = src("WowCompanionSpells", "PadMap.lua")
    bk_src = src("WowCompanionSpells", "Book.lua")
    chk("A0. PadMap m201 (the 1533 merge; m200 the lease said at its lapse, m199 the mode), Book b86 and their notes", 'local PV = "m205"' in pm_src
        and "m199 SINGLE SCREEN MODE" in pm_src and "m200 THE SINGLE SCREEN LEASE SAID" in pm_src
        and 'local BV = "b90"' in bk_src and "b86 SINGLE SCREEN MODE" in bk_src and "b86 THE FOURTH PAGE" in bk_src)
    W = tep.tct.boot()
    pm = 'WowCompanion.padMap'
    chk("A1. the API is there (single / singleSet / SS_TTL)",
        W.eval('type(%s.single) == "function" and type(%s.singleSet) == "function" and type(%s.SS_TTL) == "number"' % (pm, pm, pm)) is True)
    if W.eval('type(%s.single) ~= "function"' % pm):
        return
    W.execute(pm + '.dpadBar(true); runTimers(NOW + 1)')

    def st():
        d = W.eval(pm + '.dpadState()')
        return d.ss, d.ct, d.cc

    def on():
        return W.eval(pm + '.single()') is True

    def tops():
        return W.eval(pm + '.combatTopOn()') is True, W.eval(pm + '.combatCastTopOn()') is True

    db0 = W.eval(SER)
    chk("A2. off at first: ss 0, single() false, the Wings / top bar flags as saved (off)", st()[0] == 0 and not on() and tops() == (False, False),
        (st(), on(), tops()))
    n0 = int(W.eval('#SENT'))
    tep.tct.padtab(W, 39, 38, 0, 1)
    W.execute('runTimers(NOW + 1)')
    chk("A3. 39, 38, 0, 1: on - ss 1, single() true", st()[0] == 1 and on(), (st(), on()))
    chk("A4. ... combatTopOn / combatCastTopOn true (the Wings and our bar take the fights)", tops() == (True, True), tops())
    chk("A5. ... the padmap's ct / cc still the SAVED flags (0): the app's two-screen Home stays honest", st()[1] == 0 and st()[2] == 0, st())
    sent = []
    for i in range(n0 + 1, int(W.eval('#SENT')) + 1):
        try:
            d = json.loads(W.eval('SENT[%d]' % i))
        except Exception:      # noqa: BLE001
            continue
        if isinstance(d, dict) and d.get('type') == 'padmap':
            sent.append(((d.get('m') or {}).get('dpad') or {}).get('ss'))
    chk("A6. a padmap with ss = 1 sent (the app stops re-sending)", 1 in sent, sent)
    chk("A7. nothing saved: the saved variables as they were", W.eval('SER(WowCompanionSpellsDB, 0)') == db0)
    tep.tct.padtab(W, 39, 38, 0, 0)
    W.execute('runTimers(NOW + 1)')
    chk("A8. 39, 38, 0, 0: off (ss 0, the flags as saved again)", st()[0] == 0 and not on() and tops() == (False, False), (st(), tops()))
    # the fourth page's pick lasts 2 s
    tep.tct.padtab(W, 39, 38, 0)
    W.execute('NOW = NOW + 3; runTimers(NOW + 1)')
    tep.tct.padtab(W, 1)
    W.execute('runTimers(NOW + 1)')
    chk("A9. the fourth page's pick goes stale after 2 s (a late 1 is not the mode)", st()[0] == 0 and not on(), st())
    # the earlier pages still their own
    tep.tct.padtab(W, 39, 38, 37, 26)
    W.execute('runTimers(NOW + 1)')
    cs0 = W.eval(pm + '.dpadState().cs')
    tep.tct.padtab(W, 39, 38, 37, 27)
    W.execute('runTimers(NOW + 1)')
    chk("A10. the third page still its own (26 / 27 the swing: cs %s -> %s), the mode untouched" % (cs0, W.eval(pm + '.dpadState().cs')),
        cs0 == 0 and W.eval(pm + '.dpadState().cs') == 1 and st()[0] == 0)
    # the lease
    tep.tct.padtab(W, 39, 38, 0, 1)
    W.execute('runTimers(NOW + 1)')
    ttl = W.eval(pm + '.SS_TTL')
    chk("A11. a lease of SS_TTL %s s (the app renews every 600 s)" % ttl, ttl == 900, ttl)
    W.execute('NOW = NOW + %f; runTimers(NOW + 1)' % (ttl - 5))
    still = on()
    n1 = int(W.eval('#SENT'))
    W.execute('NOW = NOW + 10; runTimers(NOW + 1)')
    chk("A12. still on just before the lease ends, off after it (no word from the app)", still and not on() and st()[0] == 0, (still, on()))
    # m200 (the review, 2026-11-25): the lapse is said at once - a padmap with ss 0 goes, so the app sends the mode again before a fight
    W.execute('runTimers(NOW + 1)')
    lapsed = []
    for i in range(n1 + 1, int(W.eval('#SENT')) + 1):
        try:
            d = json.loads(W.eval('SENT[%d]' % i))
        except Exception:      # noqa: BLE001
            continue
        if isinstance(d, dict) and d.get('type') == 'padmap':
            lapsed.append(((d.get('m') or {}).get('dpad') or {}).get('ss'))
    chk("A12b. m200: the lapse sends a padmap with ss = 0 (the app hears it at once)", 0 in lapsed, lapsed)
    # an expired lease in a fight: kept until the fight's end
    tep.tct.padtab(W, 39, 38, 0, 1)
    W.execute('runTimers(NOW + 1)')
    W.execute('fightOn(); tick(0.5)')
    W.execute('NOW = NOW + %f' % (ttl + 30))
    inside = on() and tops() == (True, True)
    W.execute('fightOff(); tick(0.5); runTimers(NOW + 2); tick(0.5)')
    chk("A13. an expired lease in a fight: on to the fight's end (the Wings stay), off after it", inside and not on(), (inside, on()))
    # (from here: the saved variables as the swing codes 26 / 27 and the stub fight left them - dpadZL's record is the fight's own)
    db1 = W.eval('SER(WowCompanionSpellsDB, 0)')
    # renewed: a fresh 1 restarts the lease
    tep.tct.padtab(W, 39, 38, 0, 1)
    W.execute('runTimers(NOW + 1); NOW = NOW + 600')
    tep.tct.padtab(W, 39, 38, 0, 1)
    W.execute('runTimers(NOW + 1); NOW = NOW + 600; runTimers(NOW + 1)')
    chk("A14. renewed at 600 s: still on at 1200 s", on())
    chk("A15. nothing saved by the mode's renewals", W.eval('SER(WowCompanionSpellsDB, 0)') == db1)
    tep.tct.clean(W, "A15")
    W2 = tep.tct.boot()
    chk("A16. a /reload (a fresh run): off (in memory only)", W2.eval(pm + '.dpadState().ss') == 0 and W2.eval(pm + '.single()') is False)
    chk("A17. PadMap's main chunk keeps to Lua 5.1's 200 locals", len(re.findall(r'(?m)^local ', pm_src)) <= 200)
    chk("A18. Book's main chunk adds no local (the codes' table on P2)", len(re.findall(r'(?m)^local ', bk_src)) == 114,
        len(re.findall(r'(?m)^local ', bk_src)))


# ------------------------------------------------------------------------------------------------------------ B our top bar for every cast
def part_b():
    print("\n-- B our top cast bar for every cast (ActionBar nbar25)")
    import test_top_castbar as ttc
    ab = src("WowCompanion", "ActionBar.lua")
    chk("B0. ActionBar nbar26 (the 1533 merge) and nbar25's note", 'local NB = { V = "nbar29"' in ab and "nbar25 SINGLE SCREEN MODE" in ab)
    L = ttc.boot()
    G = L.globals()
    L.execute('''
local pm = WowCompanion.padMap
SS, TOPF = false, false
CCB = false
pm.single = function() return SS end
pm.combatTopOn = function() return TOPF or SS end
pm.combatCastTopOn = function() return CCB or SS end
PV = nil
pm.preview = function() return PV end
TEMPLATES = {}
local cf = CreateFrame
function CreateFrame(kind, name, parent, tmpl, ...) if tmpl then TEMPLATES[#TEMPLATES + 1] = tostring(tmpl) end; return cf(kind, name, parent, tmpl, ...) end
''')
    tick = ttc.tick_fn(L)
    NB = G.WowCompanion.nbar
    tick(60)
    L.execute('CASTSTART("Hearthstone", "Hearthstone", 10.0)')
    tick(30)
    chk("B1. the mode off, the saved flags off, no fight: a Hearthstone shows nothing on top (the bottom band has it)", not ttc.shown(G))
    L.execute('CAST = nil; CASTCUT()')
    tick(120)
    G.SS = True
    tick(60)
    chk("B2. the mode on, no cast: nothing on top", not ttc.shown(G), (ttc.shown(G),))
    chk("B3. ... no Wings out of a fight", NB.top.want is not True and not (NB.f and NB.f.shown))
    L.execute('CASTSTART("Hearthstone", "Hearthstone", 10.0)')
    tick(2)
    lk = ttc.look(G)
    chk("B4. the mode on: our top cast bar shows the Hearthstone at once (out of the takeover)", ttc.shown(G) and lk is not None
        and str(lk.name) == "Hearthstone", (ttc.shown(G), lk and lk.name))
    tick(240)
    lk = ttc.look(G)
    chk("B5. ... the fill part way at 4 s of 10 (the cast's own clock)", lk is not None and 0.3 < float(lk.p) < 0.5, lk and lk.p)
    rs = ttc.rects(G)
    hud = None
    for nm, r in ttc.twm.drawn_parts(G):
        if nm == "bars":
            hud = r
    bar = rs.get("bar")
    chk("B6. ... at its place above the HUD block (nbar18's spot)", bar is not None and hud is not None and bar[1] + bar[3] <= hud[1] + 0.01,
        (bar, hud))
    chk("B7. ... and still no Wings (no fight)", NB.top.want is not True)
    L.execute('CASTDONE()')
    tick(90)
    chk("B8. the cast done: the bar gone after its finish", not ttc.shown(G))
    L.execute('CASTSTART("Disenchant", "Disenchant", 3.0)')
    tick(30)
    chk("B9. a Disenchant too (every cast)", ttc.shown(G) and str(ttc.look(G).name) == "Disenchant")
    L.execute('CASTDONE()')
    tick(90)
    G.COMBAT = True
    tick(60)
    chk("B10. a fight with the mode on: the Wings up and our bar (a Top-screen fight)", NB.top.want is True and ttc.shown(G),
        (NB.top.want, ttc.shown(G)))
    G.COMBAT = False
    tick(120)
    L.execute('PV = { c = 33, wings = true }')
    tick(60)
    L.execute('CASTSTART("Hearthstone", "Hearthstone", 10.0)')
    tick(10)
    chk("B11. Edit Home's preview (Cast bar Bottom) is its own: no bar for a cast under it", not ttc.shown(G))
    L.execute('PV = nil; CAST = nil; CASTCUT()')
    tick(120)
    G.SS = False
    tick(60)
    L.execute('CASTSTART("Hearthstone", "Hearthstone", 10.0)')
    tick(30)
    chk("B12. the mode off again: nothing for a cast out of fights", not ttc.shown(G))
    chk("B13. plain frames only (no Secure template), never the mouse",
        all("Secure" not in str(L.eval('TEMPLATES[%d]' % i)) for i in range(1, int(L.eval('#TEMPLATES')) + 1)))
    chk("B14. no SetCVar / SetBinding / macro / state driver call", len(list(G.SPY.values())) == 0, list(G.SPY.values()))


# ------------------------------------------------------------------------------------------------------------ C no trace
def part_c():
    print("\n-- C no trace (the source)")
    bad = re.compile(r'SetCVar|SetBinding|SetOverrideBinding|CreateMacro|EditMacro|DeleteMacro|RegisterStateDriver|SaveBindings|ALT-|F1[0-2]?\b')
    pm = src("WowCompanionSpells", "PadMap.lua")
    blk = pm[pm.index("-- m199 SINGLE SCREEN MODE (the user"):pm.index("-- the switch: true / false for this character")]
    bk = src("WowCompanionSpells", "Book.lua")
    bkb = bk[bk.index("-- b86 THE FOURTH PAGE"):bk.index("if type(pad) == \"table\" and type(pad.op) == \"function\" then")]
    ab = src("WowCompanion", "ActionBar.lua")
    abb = ab[ab.index("-- nbar25 SINGLE SCREEN MODE (PadMap m199; the user, 2026-11-25: \"We need"):ab.index("-- our bar wanted on the top screen in the takeover")]
    code = "\n".join(l.split("--", 1)[0] for l in (blk + bkb + abb).splitlines())
    chk("C1. the new code calls no CVar / binding / macro API, no Alt / F-key chord", not bad.search(code), bad.findall(code)[:3])
    chk("C2. PadMap's mode is never in WowCompanionSpellsDB", "WowCompanionSpellsDB" not in code)


# ------------------------------------------------------------------------------------------------------------ D hold to cast x the docks
def part_d():
    """The 1533 merge (hold to cast's visuals x single screen mode's docks): the hold badge's glowing ring (hb.ring's glow, the
    circglw atlas at (HOLD_R + 8) x 1.7 - the largest it draws) and the takeover's gold checked ring (b.hsel, b.sz + 8) round every
    one of the eight slots, both glyph layouts, stay inside what the docks keep out of: in fights inside each Wings half's box
    (NB.halfArt: the solver's rect, so inside SinglePlayLayout's K4 envelope), out of fights inside the Nintendo bar's own focus-glow
    reach (FOCUS / 2 past a slot), which stays clear of every OUT dock box (SinglePlayLayout.kt's K3 and its boxes, 1.322 px a unit)."""
    print("\n-- D hold to cast's ring and takeover clear of the play docks (the 1533 merge)")
    import test_top_castbar as ttc
    L = ttc.boot()
    G = L.globals()
    NB = G.WowCompanion.nbar
    T = NB.TOP
    glow = (NB.HOLD_R + 8) * 1.7 / 2.0              # hb.ring's glow half (its no-atlas disc, (HOLD_R + 14) / 2, is smaller)
    chk("D0. the glow is the largest hold piece (the track ring and the fallback disc inside it)",
        glow >= (NB.HOLD_R + 8) / 2.0 and glow >= (NB.HOLD_R + 14) / 2.0, glow)
    a = NB.halfArt()
    mk = L.eval('function(p, circle, sz) return { p = p, circle = circle, sz = sz } end')

    def reach(ox, oy):
        """the hold pieces' farthest reach from the diamond's centre: |x|, up, down (units, y up)"""
        rx = up = dn = 0.0
        for lay in ("plain", "swap"):
            for p in range(1, 9):
                circle = p >= 5
                sz = NB.BC if circle else NB.B
                at = (NB.AT_SWAP[p] if (lay == "swap" and NB.AT_SWAP[p] is not None) else NB.AT[p])
                cx, cy = at[1] * ox, at[2] * oy
                hx, hy = L.eval('function(f, b, l) return f(b, l) end')(NB.holdSpot, mk(p, circle, sz), lay)
                bx, by = cx + hx, cy + hy
                ring = (sz + 8) / 2.0
                rx = max(rx, abs(bx) + glow, abs(cx) + ring)
                up = max(up, by + glow, cy + ring)
                dn = max(dn, -by + glow, -cy + ring)
        return rx, up, dn

    rx, up, dn = reach(T.OFF_X, T.OFF_Y)
    chk("D1. the Wings: the hold ring and the takeover's ring inside each half's box across (NB.halfArt inner / outer)",
        rx <= min(a.inner, a.outer) + 1e-6, (rx, a.inner, a.outer))
    chk("D2. ... and up / down (NB.halfArt up / down: the solver's rect, SinglePlayLayout's K4)", up <= a.up + 1e-6 and dn <= a.down + 1e-6,
        (up, a.up, dn, a.down))
    # the Nintendo bar (out of fights): each diamond OFF from its centre; the pieces inside its slots' own focus glow
    nx, nu, nd = reach(NB.OFF, NB.OFF)
    edge = NB.OFF + max(NB.B, NB.BC) / 2.0
    focus = NB.FOCUS / 2.0
    chk("D3. the Nintendo bar: the hold ring and the takeover's ring inside the slots' focus glow (FOCUS / 2 past the outer slots)",
        nx <= edge + focus + 1e-6 and nu <= edge + focus + 1e-6 and nd <= edge + focus + 1e-6, (nx, nu, nd, edge + focus))
    kt = open(os.path.join(ROOT, "companion", "app", "src", "main", "java", "com", "jrees", "wowcompanion", "SinglePlayLayout.kt"),
              encoding="utf-8").read()

    def box(name):
        m = re.search(r'\b%s\s*=\s*Box\((\d+),\s*(\d+),\s*(\d+),\s*(\d+)\)' % name, kt)
        return tuple(int(m.group(i)) for i in range(1, 5)) if m else None

    k3 = box("K3")
    outs = {n: box(n) for n in ("CHAT_OUT_PARTY", "CHAT_OUT_SOLO", "PARTY_OUT", "MAP_OUT_BOX")}
    chk("D4. SinglePlayLayout's K3 and OUT boxes read", k3 is not None and all(outs.values()), (k3, outs))
    if k3 and all(outs.values()):
        margin = focus * 1.322                      # past K3's slot edges, px (the bar at 100 %: k x 1.322 a unit)
        grown = (k3[0] - margin, k3[1] - margin, k3[2] + margin, k3[3] + margin)

        def apart(b):
            return b[2] <= grown[0] or grown[2] <= b[0] or b[3] <= grown[1] or grown[3] <= b[1]
        chk("D5. ... K3 grown by that reach meets no OUT dock (the chat, the party list, the map)", all(apart(b) for b in outs.values()),
            {n: apart(b) for n, b in outs.items()})


# ------------------------------------------------------------------------------------------------------------ E the hold's bump
def part_e():
    """The int/1533 review (Book b87 / PadMap m202; round 2 Book b88: codes 11-14): the fourth page's codes = the app's session bump after a SHORT hold fire, 0-3
    steps of 34 ms - PadMap's fire point (holdSecs: ActionBar's ring, the only one in single screen mode) holds it, so the ring fills when
    the launcher lets the press go. In memory only; the mode's codes 0 / 1 untouched; no trace."""
    print("\n-- E the hold's bump (Book b88 / PadMap m203)")
    pm_src = src("WowCompanionSpells", "PadMap.lua")
    bk_src = src("WowCompanionSpells", "Book.lua")
    chk("E0. PadMap m203, Book b88 (round 2: the codes 11-14) and their notes", 'local PV = "m205"' in pm_src and "m202 THE int/1533 REVIEW" in pm_src
        and 'local BV = "b90"' in bk_src and "b87 THE int/1533 REVIEW" in bk_src and "b88 THE int/1533 REVIEW, ROUND 2" in bk_src
        and 'for k = 0, 3 do P2.P4[11 + k] = function() local pm = WC.padMap; if type(pm) == "table" and type(pm.holdBump) == "function" '
            'then pcall(pm.holdBump, k) end end end' in bk_src
        and "P2.P4[2 + k]" not in bk_src)
    W = tep.tct.boot()
    pm = 'WowCompanion.padMap'
    if W.eval('type(%s.holdBump) ~= "function" or type(%s.fireSecs) ~= "function"' % (pm, pm)):
        chk("E1. the API is there (holdBump / holdSecs)", False)
        return
    db0 = W.eval(SER)
    f0 = float(W.eval(pm + '.fireSecs()'))
    ss0 = W.eval(pm + '.dpadState()').ss
    # b88: codes 11-14 (were 2-5: a swallowed pick left 39, 38, 2 / 3 = the second page's Bar in Party mode on / off, saved)
    for k, steps in ((12, 1), (14, 3), (11, 0), (13, 2)):
        tep.tct.padtab(W, 39, 38, 0, k)
        W.execute('runTimers(NOW + 1)')
        f = float(W.eval(pm + '.fireSecs()'))
        chk("E1. 39, 38, 0, %d: %d step(s) - F %.3f -> %.3f s" % (k, steps, f0, f), abs(f - (f0 + 0.034 * steps)) < 1e-9, (f0, f))
    chk("E2. the mode untouched (ss as it was)", W.eval(pm + '.dpadState()').ss == ss0, (ss0, W.eval(pm + '.dpadState()').ss))
    for k in (2, 3, 4, 5):
        tep.tct.padtab(W, 39, 38, 0, k)
        W.execute('runTimers(NOW + 1)')
    chk("E2b. the old codes 2-5 on the fourth page: nothing (still 2 steps)", abs(float(W.eval(pm + '.fireSecs()')) - (f0 + 0.068)) < 1e-9,
        float(W.eval(pm + '.fireSecs()')))
    tep.tct.padtab(W, 39, 38, 0)
    W.execute('NOW = NOW + 3; runTimers(NOW + 1)')
    tep.tct.padtab(W, 39, 38, 11)
    W.execute('runTimers(NOW + 1)')
    chk("E3. a stale pick (3 s): a late 11 is not the bump (the second page's own code; still 2 steps)",
        abs(float(W.eval(pm + '.fireSecs()')) - (f0 + 0.068)) < 1e-9)
    # E6 THE SWALLOWED PICK (round 2): TapPad's 0.25 s guard dropped the batch's 0 (a column-0 tab touch just before) - 39, 38, then
    # the code alone run the SECOND page's code: for 11-14 the Spellbook room's tab touches, nothing outside that room; Bar in Party
    # mode (the second page's 2 / 3, saved) never moves
    # (the bump's codes as Book wires them: a swallowed 0 leaves 39, 38, each of them; Bar in Party mode's setter counted while it runs)
    mb = re.search(r'for k = 0, 3 do P2\.P4\[(\d+) \+ k\] = function\(\) local pm = WC\.padMap; if type\(pm\) == "table" and '
                   r'type\(pm\.holdBump\) == "function"', bk_src)
    base = int(mb.group(1)) if mb else -1
    psh = W.eval('WowCompanionSpellsDB.castBarPShow')
    W.execute('PSHOWS = 0; PSHOW0 = WowCompanion.castBarPartyShow; WowCompanion.castBarPartyShow = function(...) PSHOWS = PSHOWS + 1; '
              'if type(PSHOW0) == "function" then return PSHOW0(...) end end')
    for k in range(4):
        tep.tct.padtab(W, 39, 38, base + k)
        W.execute('runTimers(NOW + 1)')
    seen = W.eval('PSHOWS')
    W.execute('WowCompanion.castBarPartyShow = PSHOW0; PSHOW0 = nil')
    chk("E6. a swallowed pick (39, 38, then each bump code %d-%d alone): Bar in Party mode never set (castBarPartyShow not called), "
        "the bump as it was" % (base, base + 3),
        base >= 0 and seen == 0 and W.eval('WowCompanionSpellsDB.castBarPShow') == psh
        and abs(float(W.eval(pm + '.fireSecs()')) - (f0 + 0.068)) < 1e-9, (base, psh, seen))

    chk("E4. nothing saved", W.eval('SER(WowCompanionSpellsDB, 0)') == db0)
    bad = re.compile(r'SetCVar|SetBinding|SetOverrideBinding|CreateMacro|EditMacro|DeleteMacro|RegisterStateDriver|SaveBindings|ALT-|F1[0-2]?\b')
    blk = pm_src[pm_src.index("-- m202 THE APP'S SESSION BUMP"):pm_src.index("function OV.hold.setTicks(n)")]
    bkb = bk_src[bk_src.index("-- b87 (PadMap m202"):bk_src.index("if type(pad) == \"table\" and type(pad.op) == \"function\" then")]
    code = "\n".join(l.split("--", 1)[0] for l in (blk + bkb).splitlines())
    chk("E5. the new code calls no CVar / binding / macro API, never the saved variables", not bad.search(code) and "WowCompanionSpellsDB" not in code)
    tep.tct.clean(W, "E5")


def main():
    for fn in (part_a, part_b, part_c, part_d, part_e):
        try:
            fn()
        except Exception as e:      # noqa: BLE001
            chk("%s ran without a Lua / Python error" % fn.__name__, False, repr(e)[:400])
    print("passes: %d  failed: %d" % (passes, len(fails)))
    sys.exit(1 if fails else 0)


if __name__ == "__main__":
    main()
