"""EDIT HOME'S TOP-SCREEN PREVIEW, THE ADD-ON'S HALF (the user on test 1530, 2026-11-25: "When choosing the top screen options in combat it
should also display the top screen ui so that its easy to visualize. Likewise the party mode should show a full party on the top screen
so its easy to visualize"), off-device: the REAL add-on files under the stub games the other suites use.

    python -B tools/test_edit_preview.py

  A THE CHANNEL (Book b85 / PadMap m198, test_combat_top_flag.py's world): the page-3 codes 32-39 (padtab 39, 38, 37 then N) set the
    preview the app's Edit Home asks for - 32 off; 33-35 In combat (the Wings; + our top cast bar; + its swing); 36-39 Party mode (a
    full party; + the Wings; + the cast bar; + the swing). WC.padMap.preview() decodes it, the padmap's dpad state says it (`pv`, 0 =
    none) and is sent again; refused in a fight (pv stays 0); a fight starting drops it at once and it never comes back by itself
    (the app sends it again); it expires after PV_TTL s with no word from the app; a /reload starts with none; nothing saved (the
    saved variables unchanged), nothing bound, no spied call; the earlier page-3 codes still their own (27 the swing, 31 the hold time).
  B THE WINGS AND OUR TOP BAR SHOW IT (ActionBar nbar23, test_wings_match.py's scene): with the saved Top-screen flag OFF and no fight,
    a combat preview brings the Wings up as a Top fight lays them; Cast bar = Top screen adds our top cast bar (a sample cast: the fill
    part way, the game's spark, a name on its plate) and, with the swing, a swing line over it; Bottom screen: no top bar. The preview
    gone (Edit closed, the tab changed): the Wings and the bar leave. A fight: the preview is never shown (only the real fight's own).
    Party mode's preview (Party mode really OFF, solo): the Wings carry the Party layer round a FIVE-member block (you + four rows),
    centred as Party mode's Top fight centres it, no Wings half on any drawn part of the block on any frame, HUD_MARGIN clear once
    settled. Plain frames only (no secure template), never the mouse.
  C A FULL PARTY ON THE TOP SCREEN (Hub hub79, test_party_top.py's stub): solo with Party mode off, the party preview draws four rows
    under the HUD - "Party 1".."Party 4", each its own class colour, the Select tab lit - with no unit read for them (never a real
    name); a real party of two is replaced by the four while it shows and is back the moment it ends; a fight drops the dummies.
  D THE HUD HOLDS FOR IT (Trail prd25, test_prd.py's world): the preview keeps the HUD up out of combat (why "preview"), Party mode's
    preview lays it as Party mode does (P.partyModeOn); gone or in a fight: as before.
No device is read. Needs lupa. Exit code 1 on any failure."""
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from lupa import lua51 as lupa  # noqa: E402

from wowstub import GAME  # noqa: E402

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


# test_combat_top_flag.py's world (its module body runs its own checks, so its boot is repeated here)
class tct:
    F = {k: os.path.join(ADDONS, *p) for k, p in (("safety", ("WowCompanion", "Safety.lua")), ("tappad", ("WowCompanion", "TapPad.lua")),
         ("book", ("WowCompanionSpells", "Book.lua")), ("padmap", ("WowCompanionSpells", "PadMap.lua")),
         ("padtabctl", ("WowCompanionSpells", "PadTabCtl.lua")))}

    @staticmethod
    def boot():
        party = open(os.path.join(HERE, "test_party.py"), encoding="utf-8").read()
        fight = open(os.path.join(HERE, "test_fight_layer.py"), encoding="utf-8").read()
        helpers = re.search(r"\nWORLD_HELPERS = r'''(.*?)'''", party, re.S).group(1)
        extra = re.search(r"\nEXTRA = r'''(.*?)'''", fight, re.S).group(1)
        after = re.search(r"\nAFTER = r'''(.*?)'''", fight, re.S).group(1)
        L = lupa.LuaRuntime(unpack_returned_tuples=True)
        L.execute(GAME)
        L.execute(extra)
        load = L.eval('function(src, name) local f, e = loadstring(src, name); if not f then error(e) end; return f end')
        for k in ("safety", "tappad", "book", "padmap", "padtabctl"):
            load(open(tct.F[k], encoding="utf-8").read(), '@' + os.path.basename(tct.F[k]))(os.path.basename(tct.F[k]))
        L.execute(helpers)
        L.execute('fire("PLAYER_LOGIN"); fire("PLAYER_ENTERING_WORLD", true, false); WowCompanion.pad.layout(); runTimers(NOW + 10)')
        L.execute(after)
        L.execute('WowCompanion.padMap.emit(true); runTimers(NOW + 1)')
        return L

    @staticmethod
    def padtab(L, *cols):
        for c in cols:
            L.execute('WowCompanion.pad.rows["padtab"].handler(nil, %d); NOW = NOW + 0.1' % c)

    @staticmethod
    def clean(L, name):
        chk(name + ": no spied call (no trace), no lockdown breach, no snippet error",
            L.eval('#SPY') == 0 and L.eval('#VIOL') == 0 and L.eval('#SNIPERR') == 0 and list(L.eval('ERRS').values()) == [],
            (L.eval('SPY[1]'), L.eval('VIOL[1]'), L.eval('SNIPERR[1]'), list(L.eval('ERRS').values())[:2]))


# ------------------------------------------------------------------------------------------------------------ A the channel
def part_a():
    print("\n-- A the channel (Book / PadMap)")
    src_pm = open(os.path.join(ADDONS, "WowCompanionSpells", "PadMap.lua"), encoding="utf-8").read()
    src_bk = open(os.path.join(ADDONS, "WowCompanionSpells", "Book.lua"), encoding="utf-8").read()
    chk("A0. PadMap m198, Book b85 and their notes", 'local PV = "m203"' in src_pm and "m198 EDIT HOME'S TOP-SCREEN PREVIEW" in src_pm
        and 'local BV = "b88"' in src_bk and "b85 EDIT HOME'S TOP-SCREEN PREVIEW" in src_bk)
    W = tct.boot()
    pm = 'WowCompanion.padMap'
    chk("A1. the preview API is there (editPreview / preview)", W.eval('type(%s.editPreview) == "function" and type(%s.preview) == "function"' % (pm, pm)) is True)
    if W.eval('type(%s.preview) ~= "function"' % pm):
        return
    W.execute(pm + '.dpadBar(true); runTimers(NOW + 1)')

    def pv():
        return W.eval(pm + '.dpadState().pv')

    def prev():
        t = W.eval(pm + '.preview()')
        if t is None:
            return None
        return {k: bool(t[k]) for k in ("party", "wings", "cast", "swing")}

    chk("A2. none at first: pv = 0, preview() nil", pv() == 0 and prev() is None, (pv(), prev()))
    want = {33: dict(party=False, wings=True, cast=False, swing=False), 34: dict(party=False, wings=True, cast=True, swing=False),
            35: dict(party=False, wings=True, cast=True, swing=True), 36: dict(party=True, wings=False, cast=False, swing=False),
            37: dict(party=True, wings=True, cast=False, swing=False), 38: dict(party=True, wings=True, cast=True, swing=False),
            39: dict(party=True, wings=True, cast=True, swing=True)}
    ok = True
    for code, w in want.items():
        tct.padtab(W, 39, 38, 37, code)
        W.execute('runTimers(NOW + 1)')
        if pv() != code or prev() != w:
            ok = False
            print("   code %d -> pv %s %s" % (code, pv(), prev()))
    chk("A3. codes 33-39 decode as the app sends them (In combat: Wings / + cast bar / + swing; Party mode: party / + Wings / ...)", ok)
    n0 = int(W.eval('#SENT'))
    tct.padtab(W, 39, 38, 37, 35)
    W.execute('runTimers(NOW + 1)')
    sent = []
    for i in range(n0 + 1, int(W.eval('#SENT')) + 1):
        try:
            d = json.loads(W.eval('SENT[%d]' % i))
        except Exception:      # noqa: BLE001
            continue
        if isinstance(d, dict) and d.get('type') == 'padmap':
            sent.append(((d.get('m') or {}).get('dpad') or {}).get('pv'))
    chk("A4. a padmap with pv = 35 sent (the app stops re-sending)", 35 in sent, sent)
    tct.padtab(W, 39, 38, 37, 32)
    W.execute('runTimers(NOW + 1)')
    chk("A5. code 32: off (pv 0, preview nil)", pv() == 0 and prev() is None, (pv(), prev()))
    # a fight: refused; a fight starting drops it at once, never back by itself
    W.execute('fightOn()')
    tct.padtab(W, 39, 38, 37, 33)
    W.execute('tick(0.5)')
    chk("A6. in a fight: refused (pv 0, nothing shown)", pv() == 0 and prev() is None, (pv(), prev()))
    W.execute('fightOff(); tick(0.5); runTimers(NOW + 2); tick(0.5)')
    tct.padtab(W, 39, 38, 37, 37)
    W.execute('runTimers(NOW + 1)')
    chk("A7. out of the fight again: taken (pv 37)", pv() == 37 and prev() is not None, pv())
    W.execute('fire("PLAYER_REGEN_DISABLED")')
    chk("A8. PLAYER_REGEN_DISABLED (before the lockdown reads true): dropped at once", prev() is None and pv() == 0, (pv(), prev()))
    W.execute('fightOn(); tick(0.5); fightOff(); tick(0.5); runTimers(NOW + 2); tick(0.5)')
    chk("A9. ... and not back after the fight (the app sends it again)", prev() is None and pv() == 0, (pv(), prev()))
    db0 =  W.eval('(function() local function ser(v, d) if type(v) ~= "table" then return tostring(v) end; if d > 8 then return "?" end; '
                 'local ks = {}; for k in pairs(v) do ks[#ks + 1] = tostring(k) end; table.sort(ks); local o = {}; '
                 'for _, k in ipairs(ks) do local x = v[k]; if x == nil then x = v[tonumber(k)] end; o[#o + 1] = k .. "=" .. ser(x, d + 1) end; '
                 'return "{" .. table.concat(o, ",") .. "}" end; SER = ser; return ser(WowCompanionSpellsDB, 0) end)()')
    # the lease: no word for PV_TTL s
    tct.padtab(W, 39, 38, 37, 34)
    W.execute('runTimers(NOW + 1)')
    ttl = W.eval(pm + '.PV_TTL')
    chk("A10. a lease of a few minutes (PV_TTL %s s)" % ttl, isinstance(ttl, (int, float)) and 300 <= ttl <= 1800, ttl)
    W.execute('NOW = NOW + %f; runTimers(NOW + 1)' % ((ttl or 300) - 5))
    still = prev() is not None
    W.execute('NOW = NOW + 10; runTimers(NOW + 1)')
    chk("A11. still there just before its lease ends, gone after it (pv 0)", still and prev() is None and pv() == 0, (still, pv()))
    # leave no trace
    tct.padtab(W, 39, 38, 37, 39)
    W.execute('runTimers(NOW + 1)')
    db1 = W.eval('SER(WowCompanionSpellsDB, 0)')
    chk("A12. nothing saved: the saved variables are as they were", db0 == db1, (len(db0), len(db1)))
    tct.clean(W, "A12")
    # the earlier codes
    tct.padtab(W, 39, 38, 37, 26)
    W.execute('runTimers(NOW + 1)')
    cs = W.eval(pm + '.dpadState().cs')
    tct.padtab(W, 39, 38, 37, 27)
    W.execute('runTimers(NOW + 1)')
    chk("A13. code 26 / 27 still the swing's (cs %s -> %s), the preview untouched" % (cs, W.eval(pm + '.dpadState().cs')),
        cs == 0 and W.eval(pm + '.dpadState().cs') == 1 and pv() == 39)
    # the top layout editor open: the real pieces (hidden, not dropped: pv still says it, so the app sends nothing more)
    W.execute('LAYWAS = rawget(WowCompanion, "lay"); WowCompanion.lay = { editing = true }')
    hid = prev() is None and pv() == 39
    W.execute('WowCompanion.lay = LAYWAS')
    chk("A13b. the top layout editor open: hidden (pv still 39), back when it closes", hid and prev() is not None, (hid, prev()))
    # a /reload: none
    W2 = tct.boot()
    chk("A14. a /reload (a fresh run): none", W2.eval(pm + '.dpadState().pv') == 0 and W2.eval(pm + '.preview()') is None)
    chk("A15. PadMap's main chunk keeps to Lua 5.1's 200 locals", len(re.findall(r'(?m)^local ', src_pm)) <= 200)


# ------------------------------------------------------------------------------------------------------------ B the Wings
def part_b():
    print("\n-- B the Wings and our top cast bar (ActionBar)")
    import test_top_castbar as ttc
    import test_wings_match as twm
    src = open(os.path.join(ADDONS, "WowCompanion", "ActionBar.lua"), encoding="utf-8").read()
    chk("B0. ActionBar nbar23 and its note", 'local NB = { V = "nbar27"' in src and "nbar23 EDIT HOME'S TOP-SCREEN PREVIEW" in src)
    L = ttc.boot()
    G = L.globals()
    # the saved flags all off, no fight: whatever shows is the preview's
    L.execute('''
local pm = WowCompanion.padMap
TOPF = false
pm.combatTopOn = function() return TOPF end
CCB, FSW, CSW = false, false, false
PV = nil
pm.preview = function() return PV end
TEMPLATES = {}
local cf = CreateFrame
function CreateFrame(kind, name, parent, tmpl, ...) if tmpl then TEMPLATES[#TEMPLATES + 1] = tostring(tmpl) end; return cf(kind, name, parent, tmpl, ...) end
''')
    tick = ttc.tick_fn(L)
    NB = G.WowCompanion.nbar
    tick(120)
    chk("B1. no preview, the flag off, no fight: no Wings", not NB.top.want and not (NB.f and NB.f.shown), NB.top.want)
    L.execute('PV = { c = 33, wings = true }')
    tick(90)
    chk("B2. the In combat preview (Combat bars = Top screen): the Wings up, no fight", NB.top.want is True and NB.f is not None and NB.f.shown,
        NB.top.want)
    chk("B3. ... Cast bar = Bottom screen: no top cast bar", not ttc.shown(G))
    L.execute('PV = { c = 35, wings = true, cast = true, swing = true }')
    tick(30)
    lk = ttc.look(G)
    chk("B4. + Cast bar = Top screen and the swing: our top bar shows a sample cast (part way, a name)",
        ttc.shown(G) and lk is not None and 0.2 < float(lk.p) < 0.9 and str(lk.name or "") != "", lk and (lk.p, lk.name))
    sw = NB.cb.sw[1]
    chk("B5. ... the swing line over it", bool(sw.on) and sw.k is not None and 0 < float(sw.k) < 1, (sw.on, sw.k))
    L.execute('PV = { c = 34, wings = true, cast = true }')
    tick(10)
    chk("B6. the swing off: no swing line (the bar stays)", ttc.shown(G) and not NB.cb.sw[1].on and not NB.cb.sw[2].on)
    L.execute('PV = nil')
    tick(90)
    chk("B7. the preview gone (Edit closed): the Wings and the bar leave", not NB.top.want and not ttc.shown(G) and not (NB.f and NB.f.shown),
        (NB.top.want, ttc.shown(G)))
    # a fight: the preview is never what shows (the real PadMap drops it; ActionBar's own guard reads the lockdown too)
    L.execute('PV = { c = 35, wings = true, cast = true, swing = true }')
    tick(60)
    L.execute('COMBAT = true')
    tick(1)
    chk("B8. a fight with the preview still asked: dropped on the frame it starts (the flag off: no Wings in this fight)",
        NB.top.want is not True and NB.pv() is None, NB.top.want)
    tick(60)
    chk("B9. ... gone by the slide's end", not ttc.shown(G) and not (NB.f and NB.f.shown))
    L.execute('COMBAT = false; PV = nil')
    tick(60)
    # Party mode's preview: solo, Party mode really off - the block as a full party (you + four), the Wings clear of it
    L.execute('''
PARTY = false
HUD.members = 5
local PF = WowCompanion.uf.pf
local d0 = PF.draw
function PF.draw() local p = PARTY; if PV and PV.party then PARTY = true end; d0(); PARTY = p end
PF.draw()
''')
    G.HUD.x0 = 980                                      # (the block right of the centre: the centring has a way to go)
    G.WowCompanion.prd.place()
    G.WowCompanion.uf.pf.draw()
    tick(30)
    G.PLACED = L.table()
    L.execute('PV = { c = 37, party = true, wings = true }')
    hits, n = [], 0
    for i in range(150):
        tick(1)
        wb = twm.wings_boxes(G, L)
        if wb:
            n += 1
            for nm, r in twm.drawn_parts(G):
                for h in wb:
                    if twm.over(h, r):
                        hits.append((i, nm))
    chk("B10. Party mode's preview: NB.partyOn() true (the Party layer) with Party mode off", NB.partyOn() is True)
    chk("B11. ... the block centred as Party mode's Top fight centres it (T.centre)", NB.top.centre is True, NB.top.centre)
    rows = sum(1 for i in range(1, 5) if G.WowCompanion.uf.pf.frames[i].shown)
    chk("B12. ... a FIVE-member block: you (the HUD's bars) and four rows", rows == 4, rows)
    chk("B13. ... no Wings half on a drawn part of the block on any frame (%d frames laid)" % n, n > 0 and not hits, hits[:5])
    block = None
    for nm, r in twm.drawn_parts(G):
        if nm.startswith("row"):
            r = (r[0] - 12, r[1] - 12, r[2] + 24, r[3] + 24)          # (the rows' drawn rim: NB.TOP.HUB_RIM)
        block = twm.union(block, r)
    wb = twm.wings_boxes(G, L)
    ok = block is not None and len(wb) == 2 and abs(block[0] + block[2] / 2 - 960) <= 1.0
    ok = ok and wb[0][0] + wb[0][2] <= block[0] - twm.HUD_MARGIN + 0.01 and wb[1][0] >= block[0] + block[2] + twm.HUD_MARGIN - 0.01
    chk("B14. ... settled: the block centred, the Wings >= %d px either side of it" % twm.HUD_MARGIN, ok, (block, wb))
    L.execute('PV = nil')
    tick(90)
    chk("B15. the party preview gone: the Party layer off again, no Wings", NB.partyOn() is not True and not NB.top.want)
    chk("B16. plain frames only (no template), never the mouse", L.eval('#TEMPLATES') == 0 or all(
        "Secure" not in str(L.eval('TEMPLATES[%d]' % i)) for i in range(1, int(L.eval('#TEMPLATES')) + 1)),
        [L.eval('TEMPLATES[%d]' % i) for i in range(1, int(L.eval('#TEMPLATES')) + 1)][:4])


# ------------------------------------------------------------------------------------------------------------ C the full party
def part_c():
    print("\n-- C a full party (Hub)")
    import test_party_top as tpt
    HUB = tpt.HUB
    src = open(HUB, encoding="utf-8").read()
    chk("C0. Hub hub79 and its note", re.search(r'local HV = "hub79"', src) is not None and "hub79 EDIT HOME'S TOP-SCREEN PREVIEW" in src)
    L = lupa.LuaRuntime()
    L.execute(tpt.STUB)
    L.execute('''
PV = nil
WowCompanion.padMap.preview = function() return PV end
NAMEREADS = {}
local un = UnitName
function UnitName(u) NAMEREADS[#NAMEREADS + 1] = u; return un(u) end
COMBAT = false
function InCombatLockdown() return COMBAT end
function UnitAffectingCombat() return COMBAT end
RAID_CLASS_COLORS.PRIEST = { r = 1, g = 1, b = 1 }; RAID_CLASS_COLORS.ROGUE = { r = 1, g = 0.96, b = 0.41 }
RAID_CLASS_COLORS.HUNTER = { r = 0.67, g = 0.83, b = 0.45 }; RAID_CLASS_COLORS.PALADIN = { r = 0.96, g = 0.55, b = 0.73 }
RAID_CLASS_COLORS.DRUID = { r = 1, g = 0.49, b = 0.04 }
''')
    L.execute(open(HUB, encoding="utf-8").read().replace("local ADDON = ...", "local ADDON = 'WowCompanionHub'", 1))
    G = L.globals()
    UF = G.WowCompanion.uf
    PF = UF.pf
    G.WowCompanion.partyOk = True

    def settle():
        UF.partyStep()
        for _ in range(40):
            PF.fadeTick(0.05)
        UF.partyStep()

    def shown():
        return {i: PF.frames[i] for i in range(1, 5) if PF.frames[i] is not None and PF.frames[i].shown}

    settle()
    chk("C1. solo, Party mode off, no preview: no row", shown() == {})
    L.execute('PV = { c = 36, party = true }; NAMEREADS = {}')
    settle()
    f = shown()
    names = [str(f[i].name.text) for i in sorted(f)]
    chk("C2. the party preview: four rows under the HUD (Party mode's place)", sorted(f) == [1, 2, 3, 4] and PF.at == "hud", (sorted(f), PF.at))
    chk("C3. ... named Party 1 .. Party 4 (placeholders, never a real name)", names == ["Party 1", "Party 2", "Party 3", "Party 4"], names)
    cols = {tuple(round(float(f[i].name.tc[k]), 3) for k in (1, 2, 3)) for i in f}
    chk("C4. ... each its own class colour", len(cols) == 4, cols)
    chk("C5. ... no unit read for them (UnitName never asked for party1..4)",
        not any(str(G.NAMEREADS[i]).startswith("party") for i in range(1, len(G.NAMEREADS) + 1)), list(G.NAMEREADS.values())[:6])
    chk("C6. ... Party mode's grid: [1][2] / [3][4]", {i: (f[i].look.row, f[i].look.side) for i in f} == tpt.grid(4),
        {i: (f[i].look.row, f[i].look.side) for i in f})
    chk("C7. ... the Select tab lit beside it", PF.tab is not None and PF.tab.shown and PF.tabLook == "on", PF.tabLook)
    chk("C8. ... every value plain (no secret into a bar for a dummy)", all(not G.rawequal(f[i].hp.val, G.SECRET) for i in f))
    L.execute('PV = nil')
    settle()
    chk("C9. the preview gone: no row, no tab (solo, Party mode off)", shown() == {} and not (PF.tab and PF.tab.shown))
    # a real party of two: replaced while the preview shows, back after
    L.execute('UNITS.party1 = { name = "Brannoc", cls = "WARRIOR", lv = 21 }; UNITS.party2 = { name = "Iselle", cls = "MAGE", lv = 19 }')
    settle()
    real = [str(shown()[i].name.text) for i in sorted(shown())]
    L.execute('PV = { c = 37, party = true, wings = true }')
    settle()
    pvn = [str(shown()[i].name.text) for i in sorted(shown())]
    L.execute('PV = nil')
    settle()
    back = [str(shown()[i].name.text) for i in sorted(shown())]
    chk("C10. a real party of two: the four placeholders while it shows, the real rows back after", real == ["Brannoc", "Iselle"]
        and pvn == ["Party 1", "Party 2", "Party 3", "Party 4"] and back == real, (real, pvn, back))
    # a fight drops the dummies at the next step
    L.execute('UNITS.party1 = nil; UNITS.party2 = nil; PV = { c = 36, party = true }')
    settle()
    L.execute('COMBAT = true')
    UF.partyStep()
    chk("C11. a fight with the preview still asked: no dummy row", all(str(x.name.text).find("Party ") != 0 for x in shown().values()),
        [str(x.name.text) for x in shown().values()])


# ------------------------------------------------------------------------------------------------------------ D the HUD
def part_d():
    print("\n-- D the HUD holds for it (Trail)")
    import test_compass as tc
    import test_prd as tp
    src = open(tc.TRAIL, encoding="utf-8").read()
    chk("D0. Trail prd25 and its note", 'V = "prd25"' in src and "prd25 EDIT HOME'S TOP-SCREEN PREVIEW" in src)
    L = tc.lupa.LuaRuntime()
    L.execute(tc.STUB)
    L.execute(tp.GAMEPLUS)
    L.execute(src.replace("local ADDON = ...", "local ADDON = 'WowCompanion'", 1))
    G, Lg = L.globals().GAME, L.globals()
    P = Lg.WowCompanion.prd
    frames = [Lg.FRAMES[i] for i in range(1, len(Lg.FRAMES) + 1)]
    ev = [f for f in frames if f.events and f.events.UNIT_AURA][0]

    def run(secs, dt=1 / 30):
        for _ in range(int(round(secs / dt))):
            G.t = G.t + dt
            ev.scripts.OnUpdate(ev, dt)

    def fire(e, a=None):
        ev.scripts.OnEvent(ev, e, a)

    G.t = 1000.0
    fire("PLAYER_ENTERING_WORLD")
    Lg.WowCompanion.npWant = L.table_from({"hud": True})
    L.execute('PV = nil; PMP = { on = false }; WowCompanion.padMap = { preview = function() return PV end, '
              'party = { on = function() return PMP.on end, box = function() return { u = nil } end } }')
    run(1)
    Lg.ME.hp = 1000
    run(5)
    chk("D1. no preview, no fight yet, full: nothing shown (%s)" % P.why, P.shown is False)
    L.execute('PV = { c = 33, wings = true }')
    run(0.5)
    chk("D2. the In combat preview: the HUD held up (why %s, alpha %.2f)" % (P.why, P.alpha), P.shown is True and P.why == "preview")
    chk("D3. ... not Party mode's lay (P.partyModeOn false)", P.partyModeOn() is False)
    L.execute('PV = { c = 36, party = true }')
    run(0.3)
    chk("D4. Party mode's preview: laid as Party mode (P.partyModeOn true) with Party mode off", P.partyModeOn() is True and P.shown is True)
    L.execute('PV = nil')
    run(5)
    chk("D5. the preview gone: Party mode's lay off, the HUD fades as before (%s)" % P.why, P.partyModeOn() is False and P.why != "preview"
        and P.shown is False)
    L.execute('PV = { c = 36, party = true }')
    Lg.ME.fight = True
    run(0.2)
    chk("D6. a fight: never the preview (why %s, P.partyModeOn %s)" % (P.why, P.partyModeOn()), P.why != "preview" and P.partyModeOn() is False)


def main():
    # (test_combat_top_flag ran its own checks on import; only ours count here)
    for p in (part_a, part_b, part_c, part_d):
        try:
            p()
        except Exception as e:      # noqa: BLE001
            import traceback
            traceback.print_exc()
            chk("%s ran" % p.__name__, False, repr(e)[:300])
    print("\n%d passed, %d failed" % (passes, len(fails)))
    for f in fails:
        print("  FAIL " + f)
    sys.exit(1 if fails else 0)


if __name__ == "__main__":
    main()
