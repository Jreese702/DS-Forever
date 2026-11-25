"""HOLD TO CAST IN THE GAME (PadMap m194, Book b82, PadTabCtl p62; m190's store, per-tab holds and drawing kept), off-device: the REAL
add-on files under tools/wowstub.py's game with STRICT laid over it (the lockdown, the restricted snippets, the press model, the
game's state drivers) and test_ltrt_stancebar.py's bars.

    python -B tools/test_hold_cast.py

The user on test 1529, 2026-11-24: "this addon has hold to use spells capabilities. When I use ours it feels baggy because of how its
delayed for the normal spell buttons. Can you look at how they do it"; the pick: "Yes, rebuild it that way" - all inside the game:
real-time taps on release, no app delay, no Guide check; the hold spell fires when you let go after the hold time; Short / Normal /
Long = 2 / 3 / 4 looks of the game's 0.2 s state-driver clock.

Proves: (0) the tags and their notes; (1) NO PROBE: the world loads clean, no holdprobe document, test 1528's kept Guide answer dropped
from our saved data, no marker number on the router, hold to cast simply on; the hold clock (a secure state-driver frame, "idle")
knows the router's buttons (never B) and each knows it; (2) THE STORE (per character, per tab, out of combat, never a binding, never
B); (3) THE KEEP: a press of a place whose CURRENT tab has a hold text does nothing on its key-down; on its release the click acts
then - fewer looks than the hold time = the press the snippet worked out (the slot's own button pressed and let go), that many or more
= the hold text; released before the first look = the tap; the clock runs only while a press is kept and stops once every kept press
has its looks; two buttons at once; a press whose release never came starts clean at the next press; the button acts on its key-down
again after a kept release; a place with no hold (or the bare Y) acts on the key-down exactly as before; Short / Normal / Long = 2 / 3
/ 4 looks; in a fight (no blocked call) In combat's hold, Out of combat's never; B never kept (its give-back on the press); room
mode never kept; the bare A mounted and the bare X with something to interact with (out of combat) act on the press; the release's
facecast (hk, path slothold); (4) the app's map (`holdmap`: display only now); (5) the padmap's hold (on, n, lk); (6) the hold spells'
cooldowns; (7) the Controller room (Y = the hold chooser; a game binding on the button: hold-bind, no hold, its old hold dropped);
(8) THE BIND BOX times its own press: a face button held for the hold time (its release brings it) = its hold spell, a short one the
normal bind; a D-pad press waits for its release / the hold time; (9) no trace.
Needs lupa. Exit code 1 on any failure."""
import json
import os
import re
import sys

from wowstub import GAME, SRC, boot_world, docs, nsent

fails = passes = 0


def check(ok, name, got=''):
    global fails, passes
    print(('PASS ' if ok else 'FAIL ') + name + ((' -> ' + str(got)) if got != '' and not ok else ''))
    if ok:
        passes += 1
    else:
        fails += 1


HERE = os.path.dirname(os.path.abspath(__file__))
BASE = open(os.path.join(HERE, 'test_ltrt_stancebar.py'), encoding='utf-8').read()
BARS = re.search(r"\nPRE = r'''(.*?)'''", BASE, re.S).group(1)
# the controller as this client numbers it (0-based, as C_GamePad gives it): the triggers 10 / 11, the faces 0-3, the D-pad 12-15
PADS = r'''
STANCE_AT = nil
PADNUM = { PADLTRIGGER = 10, PADRTRIGGER = 11, PADSYSTEM = 16, PAD1 = 0, PAD2 = 1, PAD3 = 2, PAD4 = 3, PADDUP = 12, PADDDOWN = 13,
           PADDLEFT = 14, PADDRIGHT = 15 }
C_GamePad.ButtonBindingToIndex = function(n) return PADNUM[n] end
C_GamePad.ButtonIndexToBinding = function(i) for n, v in pairs(PADNUM) do if v == i then return n end end end
C_GamePad.GetActiveDeviceID = function() return 1 end
C_GamePad.GetDeviceMappedState = function() return { buttons = PADSTATE } end
C_SpellBook = { GetSpellBookItemInfo = function(slot) local id = BOOK[slot]; return id and { spellID = id } or nil end }
CVARS = {}
C_CVar = { GetCVar = function(k) return CVARS[k] end }
CD = {}
C_Spell.GetSpellCooldown = function(id) return CD[id] end
MOUNTED = false
function IsMounted() return MOUNTED end
function IsFlying() return false end
function IsSwimming() return false end
-- the macro conditionals the hold's two exceptions read: [mounted] / [flying] / [swimming] (the stub's parser knows the others)
local rawParse = SecureCmdOptionParse
function SecureCmdOptionParse(s)
    if s == "[mounted] 1" then return MOUNTED and "1" or nil end
    if s == "[flying] 1" or s == "[swimming] 1" then return nil end
    return rawParse(s)
end
SPELLNAME[8042] = "Earth Shock"
-- the player's cast (UnitCastingInfo's 5th return: its end, ms)
CASTEND = nil
function UnitCastingInfo(u) if CASTEND then return "Healing Wave", "", 136052, NOW * 1000, CASTEND * 1000 end end
function UnitChannelInfo(u) return nil end
-- the player's own bar (the slots that HAVE something: LT + X 17, LT + Y 18, LT + A 20; LT + B 19 and the rest empty)
ACTIONINFO = { [17] = { "spell", 403 }, [18] = { "spell", 331 }, [20] = { "spell", 403 } }
function GetActionInfo(s) local a = ACTIONINFO[s]; if a then return a[1], a[2] end end
function GetActionTexture(s) local a = ACTIONINFO[s]; return a and 130000 + a[2] or nil end
'''
AFTER = r'''
WowCompanion.spellsStorageFor = function(page, set, pos) return (page - 1) * 48 + (set - 1) * 12 + pos end
function HDR(k) return rawget(_G, "WowCompanionFaceRouter").a[k] end
function HB(name) return rawget(_G, name) end
function CLOCK() return rawget(_G, "WowCompanionHoldClock") end
-- ONE EDGE of a hardware press of [key] with the triggers held as given (the binding -> the router's click -> PRE): the button's name,
-- what it decided, the game's actions and the clicks of this edge
function EDGE(key, down, lt, rt)
    PADSTATE = {}
    if lt then PADSTATE[LT_I] = true end
    if rt then PADSTATE[RT_I] = true end
    local a = BIND[key]
    local name = a and a:match("^CLICK ([%w_]+):")
    local b = name and rawget(_G, name)
    local a0 = #ACTIONS
    CLICKS = {}
    hwEdge(key, down)
    local log = CLICKS
    CLICKS = nil
    local acts = {}
    for i = a0 + 1, #ACTIONS do acts[#acts + 1] = ACTIONS[i] end
    return name, b and b.a["wc-kind"], acts, log
end
-- the game's state drivers looked at [n] times (its 0.2 s clock), the time moving with them
function LOOK(n) for i = 1, n do NOW = NOW + 0.2; driveStates() end end
-- PER TAB: Out of combat's holds (HDB) and In combat's (HDBC); HROOT the character's whole table
function HROOT() return (WowCompanionSpellsDB.holdBy or {})[WHO .. "-" .. REALM] end
function HDB() local r = HROOT(); return r and r.normal end
function HDBC() local r = HROOT(); return r and r.combat end
-- the book the room's chooser lists (book order: Lightning Bolt, Healing Wave, Lightning Shield)
WowCompanion.spellsBindableList = function() local out = {}; for i, id in ipairs(BOOK) do out[#out + 1] = { id = id, slot = i } end; return out end
WowCompanion.spellsBookSlotOf = function(id) for i, x in ipairs(BOOK) do if x == id then return i end end end
-- the Bind box's watch, frame by frame (its OnUpdate, as the game runs it)
function BOXRUN(s, step)
    local f = WowCompanion._spellsBook.hw.f
    local t = 0
    while t < s - 1e-9 do
        if f and f.scripts.OnUpdate then f.scripts.OnUpdate(f, step) end
        NOW = NOW + step; t = t + step
    end
end
'''


def lst(L, expr):
    t = L.eval(expr)
    return [] if t is None else list(t.values())


def clean(L):
    return {k: v for k, v in (("VIOL", lst(L, 'VIOL')), ("SPY", lst(L, 'SPY')), ("HANDLEERR", lst(L, 'HANDLEERR')),
                              ("SNIPERR", lst(L, 'SNIPERR')), ("ERRS", lst(L, 'ERRS')), ("LOADERRS", lst(L, 'LOADERRS'))) if v}


def world(pre=''):
    L, errs = boot_world(pre=BARS + PADS + pre)
    L.execute('LOADERRS = {}')
    for e in errs:
        L.eval('function(s) LOADERRS[#LOADERRS + 1] = s end')(e)
    L.execute('__IN.ACT.trust(); advance(1); __IN.N.setMode(true); advance(1)')
    L.execute(AFTER)
    # the game evaluates a state driver once when it is registered (the clock's first "idle"); then every 0.2 s
    L.execute('NOW = NOW + 2; tick(0.1); advance(0.6); driveStates()')
    return L


def last(L, typ, n0=0):
    d = docs(L, n0, typ)
    return d[-1] if d else {}


def edge(L, key, down, lt=False, rt=False):
    name, kind, acts, log = L.eval('EDGE')(key, down, lt, rt)
    acts = [dict(a) for a in (acts.values() if acts is not None else [])]
    return {'name': name, 'kind': kind, 'acts': acts, 'clicks': list(log.values()) if log is not None else []}


def macrolines(e):
    return [a.get('line') for a in e['acts'] if a.get('kind') == 'macroline']


def bars(e):
    """the game's bar buttons this edge pressed / let go: ("GPLeft6", True) ..."""
    return [(a.get('btn'), a.get('down')) for a in e['acts'] if a.get('kind') == 'bar']


def hold(L, key, looks, lt=False, rt=False):
    d = edge(L, key, True, lt, rt)
    if looks:
        L.eval('LOOK')(looks)
    u = edge(L, key, False, lt, rt)
    return d, u


def a(L, expr):
    return L.eval(expr)


def tapped(u, btn):
    return bars(u) == [(btn, True), (btn, False)] and not macrolines(u)


# ---------------------------------------------------------------- (0) the tags
pm, ptc, bk = SRC['padmap'], SRC['padtabctl'], SRC['book']
ab = open(os.path.join(os.path.dirname(HERE), 'companion', 'app', 'src', 'main', 'assets', 'companion', 'addons', 'WowCompanion',
                       'ActionBar.lua'), encoding='utf-8').read()
check('local PV = "m198"' in pm and 'm197 THE 1531 MERGE OF feat/hold-cast' in pm and 'm196 THE 1530 MERGE OF TEST 1529' in pm
      and 'm195 THE HOLD SPELL TAKES THE BUTTON' in pm and 'm194 HOLD TO CAST IN THE GAME' in pm and 'm193 THE 1528b MERGE OF feat/hold-cast' in pm
      and 'm190 HOLD TO CAST' in pm and 'm189 THE 1523 TEST BUILD MERGE' in pm, '0. PadMap m197 above both sides (m196, m195 / m194, m193, m190, m189 kept)')
check('local CV = "p63"' in ptc and 'p63 THE 1531 MERGE OF feat/hold-cast' in ptc and 'p62 HOLD TO CAST IN THE GAME' in ptc
      and 'p61 THE 1528b MERGE OF feat/hold-cast' in ptc and 'p60 THE HOLD SPELL BUTTON' in ptc and 'p59 HOLD TO CAST' in ptc,
      '0. PadTabCtl p63 (p62, p61, p60, p59 kept)')
check('local BV = "b85"' in bk and 'b84 THE 1531 MERGE OF feat/hold-cast' in bk and 'b83 THE 1530 MERGE OF TEST 1529' in bk
      and 'b82 HOLD TO CAST IN THE GAME' in bk and 'b81 THE 1528b MERGE OF feat/hold-cast' in bk and 'b79 THE HOLD SPELL BUTTON BY TOUCH' in bk
      and 'P3[28] = function(pt) if type(pt) == "table" and type(pt.holdOpen) == "function" then pcall(pt.holdOpen) end end' in bk
      and 'for k = 0, 1 do P3[26 + k]' in bk
      and 'for k = 0, 2 do P3[29 + k] = function() local pm = WC.padMap; if type(pm) == "table" and type(pm.holdTicks) == "function" then pcall(pm.holdTicks, 2 + k) end end end' in bk,
      '0. Book b84 (b83, b82, b81 kept): the swing 26 / 27, the hold chooser 28, the hold time 29 / 30 / 31')
check('local NB = { V = "nbar23"' in ab and 'nbar22 THE 1531 MERGE OF feat/hold-cast' in ab and 'nbar21 THE HOLD SPELL ON THE TOP SCREEN' in ab
      and 'nbar20 THE 1528b MERGE OF feat/hold-cast' in ab and 'nbar18 HOLD TO CAST' in ab,
      '0. ActionBar nbar22 (nbar21, nbar20, nbar18 kept): tools/test_hold_wings.py tests it')

# ---------------------------------------------------------------- (1) NO PROBE, THE CLOCK
L = world('WowCompanionSpellsDB = { holdGuide = "PADSYSTEM" }')
check(not clean(L), '1. the world loads clean', clean(L))
L.execute('runTimers(NOW + 15)')
check(not docs(L, 0, 'holdprobe'), '1. no probe: no holdprobe document, ever')
check(a(L, 'WowCompanionSpellsDB.holdGuide') is None, '1. test 1528\'s kept Guide answer dropped from our saved data')
check(a(L, 'HB("WowCompanionCombatFace6").a["wc-gd"]') is None and 'wc-gd' not in pm and 'holdMarked' not in pm and 'GAME_PAD_BUTTON_DOWN' not in pm,
      '1. no marker: no Guide number on the router, no marker read anywhere in PadMap')
check(a(L, 'WowCompanion.padMap.holdOn()') is True, '1. hold to cast is simply on')
ck = a(L, 'CLOCK()')
check(ck is not None and a(L, 'CLOCK()._tpl') == 'SecureHandlerStateTemplate' and a(L, 'CLOCK().a["wc-hn"]') == 3
      and a(L, '(function() for _, d in ipairs(DRIVERS) do if d.f == CLOCK() then return d.st .. "=" .. d.cond end end end)()') == 'wchold=idle',
      '1. THE HOLD CLOCK: a secure state-driver frame, its driver always "idle", the hold time 3 looks (Normal)')
check(a(L, 'CLOCK().a["_onstate-wchold"] ~= nil') and a(L, 'CLOCK().a["state-wchold"]') == 'idle', '1. ... its snippet; settled on idle')

# ---------------------------------------------------------------- (2) THE STORE
n0 = nsent(L)
check(a(L, 'WowCompanion.padMap.putHold(1, 2, 6, "spell", 2)') is True, '2. LT + Y: Healing Wave (book slot 2) as the hold spell')
check(a(L, 'WowCompanion.padMap.putHold(1, 1, 8, "spell", 1)') is True, '2. the bare A: Lightning Bolt')
check(a(L, '(WowCompanion.padMap.putHold(1, 1, 7, "spell", 3))') is False and last(L, 'padack').get('why') == 'b',
      '2. B: NEVER a hold: refused, b', last(L, 'padack'))
check(a(L, 'WowCompanion.padMap.putHold(1, 2, 6, "spell", 3, "combat")') is True, '2. LT + Y on the IN COMBAT tab: Lightning Shield (its own)')
check(a(L, 'HDB()[18].n') == 'Healing Wave' and a(L, 'HDB()["f8"].n') == 'Lightning Bolt' and a(L, 'HDBC()[18].n') == 'Lightning Shield',
      '2. stored per character PER TAB')
check((a(L, 'HDR("wc-ht-18")') or '').endswith('/cast Healing Wave') and (a(L, 'HDR("wc-hf-8")') or '').endswith('/cast Lightning Bolt')
      and (a(L, 'HDR("wc-htc-18")') or '').endswith('/cast Lightning Shield') and a(L, 'HDR("wc-hcc")') is True,
      '2. the header\'s hold texts per tab (armed out of combat)')
check(a(L, 'BIND.PAD1') == 'CLICK WowCompanionCombatFace8:LeftButton' and a(L, 'BIND.PAD4') == 'CLICK WowCompanionCombatFace6:LeftButton',
      '2. out of combat the router carries A and Y (their places have a hold spell)')
check(a(L, 'CLOCK().refs["wc-hb-6"] == HB("WowCompanionCombatFace6")') and a(L, 'CLOCK().refs["wc-hb-8"] == HB("WowCompanionCombatFace8")')
      and a(L, 'HB("WowCompanionCombatFace6").refs["wc-hclk"] == CLOCK()') and a(L, 'CLOCK().refs["wc-hb-7"]') is None,
      '2. the clock knows the router\'s buttons and each knows the clock (never B)')
check(a(L, '(WowCompanion.padMap.putHold(1, 2, 5, "bind", "JUMP"))') is False and last(L, 'padack').get('why') == 'bind',
      '2. a game binding is no hold (refused: bind)', last(L, 'padack'))

# ---------------------------------------------------------------- (3) THE KEEP, THE CLOCK, THE RELEASE (Normal: 3 looks)
hl0 = a(L, 'CLOCK().a["wc-hl"] or 0')
L.execute('LOOK(5)')
check(a(L, 'CLOCK().a["wc-hl"] or 0') == hl0, '3. nothing runs while no press is kept (5 looks: the clock\'s snippet never ran)')
# a TAP: released before the first look
d, u = hold(L, 'PAD4', 0, lt=True)
check(d['kind'] == 'holdkeep' and not d['acts'] and d['clicks'] == ['WowCompanionCombatFace6+'],
      '3. LT + Y pressed: KEPT - nothing acts on the key-down (no bar button, no cast)', d)
check(u['kind'] == 'native' and tapped(u, 'GPLeft6'),
      '3. ... let go before the first look: the TAP at the release - the slot\'s own button pressed and let go (GPLeft6)', u)
check(a(L, 'HB("WowCompanionCombatFace6").a["wc-hk"]') is None, '3. ... nothing kept after it')
# fewer looks than the hold time
d, u = hold(L, 'PAD4', 2, lt=True)
check(tapped(u, 'GPLeft6'), '3. LT + Y held 2 looks (below 3): the tap at the release', u)
# exactly the hold time
d, u = hold(L, 'PAD4', 3, lt=True)
check(u['kind'] == 'slothold' and (macrolines(u) or [''])[-1] == '/cast Healing Wave' and not bars(u),
      '3. LT + Y held 3 looks: the HOLD at the release - Healing Wave, the slot\'s own button never pressed', u)
check(not d['acts'], '3. ... nothing on its key-down either')
# above it; and the clock stops once the kept press has its looks
d = edge(L, 'PAD4', True, lt=True)
hl1 = a(L, 'CLOCK().a["wc-hl"] or 0')
L.execute('LOOK(3)')
hl2 = a(L, 'CLOCK().a["wc-hl"] or 0')
L.execute('LOOK(4)')
hl3 = a(L, 'CLOCK().a["wc-hl"] or 0')
check(hl2 - hl1 == 3 and hl3 == hl2 and a(L, 'HB("WowCompanionCombatFace6").a["wc-hk-t"]') == 3 and a(L, 'CLOCK().a["state-wchold"]') == 'idle',
      '3. held on: the clock counted its 3 looks and STOPPED (4 more looks ran nothing)', (hl1, hl2, hl3))
u = edge(L, 'PAD4', False, lt=True)
check(u['kind'] == 'slothold' and (macrolines(u) or [''])[-1] == '/cast Healing Wave', '3. ... let go after 7 looks: the hold, once', u)
fc = [x for x in docs(L, 0, 'facecast') if x.get('hk') == 'rel']
check(fc and fc[-1].get('path') == 'slothold' and fc[-1].get('down') is True and fc[-1].get('kind') == 'slothold',
      '3. the release\'s facecast: the press that acted (down, hk rel, path slothold)', fc[-1:] if fc else None)
check(not [x for x in docs(L, 0, 'facecast') if x.get('kind') == 'holdkeep'], '3. a kept key-down says nothing to the press log')
# the bare A: a tap is the game's own Jump button, pressed and let go at the release
d, u = hold(L, 'PAD1', 1)
check(d['kind'] == 'holdkeep' and not d['acts'] and tapped(u, 'GPTop8'), '3. the bare A tapped (1 look): the game\'s own A (Jump) at the release', u)
d, u = hold(L, 'PAD1', 3)
check(u['kind'] == 'slothold' and (macrolines(u) or [''])[-1] == '/cast Lightning Bolt', '3. the bare A held: Lightning Bolt at the release', u)
# the button acts on its key-down again after a kept release; a place without a hold is exactly as before
check(a(L, 'HB("WowCompanionCombatFace6").a["useOnKeyDown"]') is False, '3. (after a kept release the button acted on that release)')
d = edge(L, 'PAD4', True)
check(d['kind'] == 'native' and bars(d) == [('GPTop6', True)] and a(L, 'HB("WowCompanionCombatFace6").a["useOnKeyDown"]') is None,
      '3. the bare Y (no hold on it): acts on its KEY-DOWN as always (GPTop6 down at the press), the button back on the key-down', d)
u = edge(L, 'PAD4', False)
check(bars(u) == [('GPTop6', False)], '3. ... and its key-up lets the game\'s button go (its own hold kept)', u)
# two buttons at once
d1 = edge(L, 'PAD4', True, lt=True)
L.execute('LOOK(1)')
d2 = edge(L, 'PAD1', True)
L.execute('LOOK(2)')
u1 = edge(L, 'PAD4', False, lt=True)
u2 = edge(L, 'PAD1', False)
check(d1['kind'] == 'holdkeep' and d2['kind'] == 'holdkeep' and u1['kind'] == 'slothold' and tapped(u2, 'GPTop8'),
      '3. two buttons: LT + Y (3 looks) = its hold, the bare A pressed in between (2 looks) = its tap', (u1, u2))
# a press whose release never reached the button: the next press starts clean
edge(L, 'PAD4', True, lt=True)
L.execute('LOOK(5)')
d = edge(L, 'PAD4', True, lt=True)
check(d['kind'] == 'holdkeep' and a(L, 'HB("WowCompanionCombatFace6").a["wc-hk-t"]') == 0,
      '3. a press lost (no release): the next press starts clean (0 looks)', a(L, 'HB("WowCompanionCombatFace6").a["wc-hk-t"]'))
u = edge(L, 'PAD4', False, lt=True)
check(tapped(u, 'GPLeft6'), '3. ... let go at once: a tap, not the lost press\'s hold', u)
L.execute('fire("LOADING_SCREEN_ENABLED"); fire("LOADING_SCREEN_DISABLED")')
edge(L, 'PAD4', True, lt=True)
L.execute('fire("LOADING_SCREEN_ENABLED")')
check(a(L, 'HB("WowCompanionCombatFace6").a["wc-hk"]') is None, '3. a loading screen out of combat lets a kept press go (no release will come)')
L.execute('fire("LOADING_SCREEN_DISABLED")')
# the hold time: Short 2 / Long 4
check(a(L, 'WowCompanion.padMap.holdTicks(2)') is True and a(L, 'CLOCK().a["wc-hn"]') == 2 and a(L, 'WowCompanionSpellsDB.holdN') == 2,
      '3. Short: 2 looks (the clock and our saved data)')
d, u = hold(L, 'PAD4', 1, lt=True)
check(tapped(u, 'GPLeft6'), '3. Short: 1 look = the tap', u)
d, u = hold(L, 'PAD4', 2, lt=True)
check(u['kind'] == 'slothold', '3. Short: 2 looks = the hold', u)
L.execute('WowCompanion.padMap.holdTicks(4)')
d, u = hold(L, 'PAD4', 3, lt=True)
check(tapped(u, 'GPLeft6'), '3. Long: 3 looks = the tap', u)
d, u = hold(L, 'PAD4', 4, lt=True)
check(u['kind'] == 'slothold', '3. Long: 4 looks = the hold', u)
check(a(L, 'WowCompanion.padMap.holdTicks(5)') is False and a(L, 'WowCompanion.padMap.holdTicks(3)') is True
      and a(L, 'WowCompanionSpellsDB.holdN') is None and a(L, 'CLOCK().a["wc-hn"]') == 3,
      '3. only 2 / 3 / 4; Normal stores nothing (the default)')
# room mode: the room's press (never kept)
L.execute('local b = HB("WowCompanionCombatFace6"); b.a["wc-mode"] = "room"; b.a["wc-room-dest"] = "GPTop5"')
d = edge(L, 'PAD4', True, lt=True)
check(d['kind'] != 'holdkeep', '3. room mode: never kept (the room\'s press, on the key-down)', d)
edge(L, 'PAD4', False, lt=True)
L.execute('local b = HB("WowCompanionCombatFace6"); b.a["wc-mode"] = nil; b.a["wc-room-dest"] = nil')
# the game's own holds keep their button: the bare A mounted, the bare X with something to interact with
L.execute('MOUNTED = true')
d = edge(L, 'PAD1', True)
check(d['kind'] == 'native' and bars(d) == [('GPTop8', True)], '3. mounted: the bare A acts on its press (Jump / ascend held as the game does)', d)
edge(L, 'PAD1', False)
L.execute('MOUNTED = false')
check(a(L, 'WowCompanion.padMap.putHold(1, 1, 5, "spell", 2)') is True, '3. (the bare X: Healing Wave)')
L.execute('UNITS.softinteract = { name = "Mailbox", alive = true }')
d = edge(L, 'PAD3', True)
check(d['kind'] != 'holdkeep' and d['acts'], '3. out of combat with something to interact with: the bare X acts on its press (Interact, its hold)', d)
edge(L, 'PAD3', False)
L.execute('UNITS.softinteract = nil')
d, u = hold(L, 'PAD3', 3)
check(d['kind'] == 'holdkeep' and u['kind'] == 'slothold', '3. ... nothing there: the bare X keeps its press and holds', u)
L.execute('WowCompanion.padMap.clearHold(1, 1, 5)')
# IN A FIGHT: In combat's holds, B never kept, nothing blocked
v0 = len(lst(L, 'VIOL'))
L.execute('fire("PLAYER_REGEN_DISABLED"); COMBAT = true; driveStates(); advance(1)')
L.execute('for _, n in ipairs({ "WowCompanionCombatFace5", "WowCompanionCombatFace6", "WowCompanionCombatFace8", "WowCompanionFaceB" }) do local b = HB(n); if b then b.a["wc-state"] = "fight" end end')
d, u = hold(L, 'PAD4', 3, lt=True)
check(d['kind'] == 'holdkeep' and u['kind'] == 'slothold' and (macrolines(u) or [''])[-1] == '/cast Lightning Shield',
      '3. in a fight: LT + Y held = IN COMBAT\'s hold (Lightning Shield), at the release', u)
d, u = hold(L, 'PAD4', 1, lt=True)
check(tapped(u, 'GPLeft6'), '3. in a fight: LT + Y tapped = the slot\'s own button at the release', u)
d = edge(L, 'PAD1', True)
check(d['kind'] != 'holdkeep' and d['acts'], '3. in a fight: the bare A has no In combat hold: it acts on its press (Out of combat\'s never fires)', d)
edge(L, 'PAD1', False)
L.execute('HDR_SET = rawget(_G, "WowCompanionFaceRouter").a; HDR_SET["wc-hf-7"] = "/cast Lightning Shield"; HDR_SET["wc-hfc-7"] = "/cast Lightning Shield"')
d = edge(L, 'PAD2', True)
check(d['kind'] != 'holdkeep' and a(L, 'HB("WowCompanionCombatFace8").a["wc-given"]') is True,
      '3. B in a fight (even a stray hold text): never kept - its give-back on the press', d)
edge(L, 'PAD2', False)
L.execute('HDR_SET["wc-hf-7"] = nil; HDR_SET["wc-hfc-7"] = nil; for _, n in ipairs({ "WowCompanionCombatFace5", "WowCompanionCombatFace6", "WowCompanionCombatFace8" }) do local b = HB(n); if b then b.a["wc-given"] = nil end end')
# the 1531 audit: SMART Y's IN-FIGHT BUFF (m93 / m99: a bare Y given back in a fight casts the Smart buff list's top buff, once a fight)
# on a Y that has a hold: a TAP casts the buff at the release (the keep put it aside), a HOLD runs the hold spell and leaves the buff
# for the next press (1530's guard `not htxt` was the old gate's "the hold fires" - with the keep it dropped the buff on every tap)
L.execute('COMBAT = false; driveStates(); fire("PLAYER_REGEN_ENABLED"); advance(1)')
check(a(L, 'WowCompanion.padMap.putHold(1, 1, 6, "spell", 2)') is True, "3. (the bare Y: Healing Wave, Out of combat's)")
L.execute('fire("PLAYER_REGEN_DISABLED"); COMBAT = true; driveStates(); advance(1)')
L.execute('local b = HB("WowCompanionCombatFace6"); b.a["wc-state"] = "fight"; b.a["wc-given"] = true; b.a["wc-smarty-cb"] = "Lightning Shield"')
d, u = hold(L, 'PAD4', 1)
cast = [x for x in u['acts'] if x.get('kind') in ('spell', 'cast')]
check(d['kind'] == 'holdkeep' and a(L, 'HB("WowCompanionCombatFace6").a["wc-smarty-cb"]') is None and u['kind'] == 'smarty'
      and cast and 'Lightning Shield' in str(cast[-1]),
      "3. in a fight, Y given back and tapped: Smart Y's buff (Lightning Shield) at the release, used once", (d['kind'], u))
L.execute('HB("WowCompanionCombatFace6").a["wc-smarty-cb"] = "Lightning Shield"')
d, u = hold(L, 'PAD4', 3)
check(u['kind'] == 'slothold' and (macrolines(u) or [''])[-1] == '/cast Healing Wave'
      and a(L, 'HB("WowCompanionCombatFace6").a["wc-smarty-cb"]') == 'Lightning Shield',
      '3. ... held: the hold spell (Healing Wave), the buff left for the next press', u)
L.execute('HB("WowCompanionCombatFace6").a["wc-smarty-cb"] = nil; COMBAT = false; driveStates(); fire("PLAYER_REGEN_ENABLED"); advance(1)')
# the 1531 audit: Y's own SIT (1530's m190 sit by the fire) on a Y that has a hold - the seat tracker hears the tap that sits (its
# release), never the kept key-down, nor a hold that ran its hold spell (1530's hook read the raw key-down: a kept Y never told it)
L.execute('''
SEATS = {}
local b = HB("WowCompanionCombatFace6")
for _, fn in ipairs(b.hooks.PostClick or {}) do
    for i = 1, 60 do local n, v = debug.getupvalue(fn, i); if not n then break end; if n == "OV" and type(v) == "table" then SEATOV = v end end
end
local orig = SEATOV.seatOn
SEATOV.seatOn = function(ev) SEATS[#SEATS + 1] = ev; return orig(ev) end
b.a["wc-smarty"] = "/sit"; b.a["wc-smarty-t"] = "sit"; b.a["wc-state"] = "idle"; b.a["wc-given"] = nil
''')
d, u = hold(L, 'PAD4', 1)
check(d['kind'] == 'holdkeep' and u['kind'] == 'smarty' and lst(L, 'SEATS') == ['sit'],
      "3. Y's sit with a hold on Y, tapped: the sit at the release, the seat tracker told then (once)", (d['kind'], u['kind'], lst(L, 'SEATS')))
d, u = hold(L, 'PAD4', 3)
check(u['kind'] == 'slothold' and lst(L, 'SEATS') == ['sit'], "3. ... held: the hold spell, no sit told", lst(L, 'SEATS'))
L.execute('local b = HB("WowCompanionCombatFace6"); b.a["wc-smarty"] = nil; b.a["wc-smarty-t"] = nil')
L.execute('WowCompanion.padMap.clearHold(1, 1, 6); fire("PLAYER_REGEN_DISABLED"); COMBAT = true; driveStates(); advance(1)')
L.execute('for _, n in ipairs({ "WowCompanionCombatFace5", "WowCompanionCombatFace6", "WowCompanionCombatFace8", "WowCompanionFaceB" }) do local b = HB(n); if b then b.a["wc-state"] = "fight"; b.a["wc-given"] = nil end end')
check(a(L, 'WowCompanion.padMap.holdTicks(2)') is True and a(L, 'CLOCK().a["wc-hn"]') == 3,
      '3. a new hold time in a fight: kept, the clock untouched in the lockdown')
check(len(lst(L, 'VIOL')) == v0 and not lst(L, 'SNIPERR') and not lst(L, 'HANDLEERR'),
      '3. no blocked call, no snippet / handle error in the fight', (lst(L, 'VIOL')[v0:], lst(L, 'SNIPERR'), lst(L, 'HANDLEERR')))
L.execute('COMBAT = false; driveStates(); fire("PLAYER_REGEN_ENABLED"); advance(1)')
check(a(L, 'CLOCK().a["wc-hn"]') == 2, '3. ... onto the clock at the fight\'s end')
L.execute('WowCompanion.padMap.holdTicks(3)')
L.execute('for _, n in ipairs({ "WowCompanionCombatFace5", "WowCompanionCombatFace6", "WowCompanionCombatFace8", "WowCompanionFaceB" }) do local b = HB(n); if b then b.a["wc-state"] = "idle"; b.a["wc-given"] = nil end end')
check('trs = "L" .. (li and (lt and "1" or "0") or "-")' in pm and 'if down == true and dest and mode ~= "room" and not pdest and self:GetAttribute("wc-role") ~= "back" then' in pm,
      '3. the layer is the triggers read at the PRESS; only the real key-down is kept')

# ---------------------------------------------------------------- (4) THE APP'S MAP (its drawing only)
L.execute('WowCompanion.padMapHold().tick(true)')
m = last(L, 'holdmap')
places = set((m.get('b') or '').split(','))
check(m.get('on') is True and {'lt:6', 'bare:8'} <= places and 'bare:7' not in places and 'bare:6' not in places,
      '4. the places whose press is kept now (LT + Y, the bare A), never B', m)
L.execute('MOUNTED = true; WowCompanion.padMapHold().tick(true)')
check('bare:8' not in (last(L, 'holdmap').get('b') or ''), '4. mounted: the bare A left out (as the snippet)', last(L, 'holdmap'))
L.execute('MOUNTED = false')
L.execute('fire("LOADING_SCREEN_ENABLED")')
check(last(L, 'holdmap').get('on') is None and last(L, 'holdmap').get('why') == 'loading', '4. a loading screen: none', last(L, 'holdmap'))
L.execute('fire("LOADING_SCREEN_DISABLED")')
check(last(L, 'holdmap').get('on') is True, '4. ... back after it')

# ---------------------------------------------------------------- (5) the padmap
L.execute('WowCompanion.padMap.emit(true)')
doc = last(L, 'padmap')['m']
s26 = doc['sets'][1]['slots'][5]
hd = doc.get('hold') or {}
check(s26.get('h', {}).get('n') == 'Healing Wave' and s26.get('hc', {}).get('n') == 'Lightning Shield' and hd.get('on') is True and hd.get('n') == 3
      and isinstance(hd.get('lk'), int) and hd.get('lk') > 0,
      '5. each place\'s holds (h, hc); hold = { on, n = 3 looks, lk = the looks the clock was given }', hd)

# ---------------------------------------------------------------- (6) the hold spells' cooldowns
n0 = nsent(L)
L.execute('CD[331] = { isActive = true, isOnGCD = false, duration = 8, startTime = NOW }; fire("SPELL_UPDATE_COOLDOWN")')
k = ((last(L, 'holdcd', n0).get('k') or {}).get('lt:6')) or {}
check(k.get('d') == 8, '6. the hold spell\'s own cooldown (8 s on LT + Y)', last(L, 'holdcd', n0))
L.execute('CD[331] = nil; NOW = NOW + 9; fire("SPELL_UPDATE_COOLDOWN")')

# ---------------------------------------------------------------- (7) THE CONTROLLER ROOM
L.execute('WowCompanion.padMap.clearHold(1, 1, 8)')
L.execute('local ctl = WowCompanion._spellsBook.ctl; ctl.croom, ctl.ctrlTab, ctl.onTabs = true, true, true; WowCompanion.padTabCtl.enter()')


def ctl(L):
    return last(L, 'padtabctl').get('c') or {}


for _ in range(6):
    if (ctl(L).get('pos') or 0) == 8:
        break
    L.execute('WowCompanion.padTabCtl.press("PADDRIGHT")')
L.execute('WowCompanion.padTabCtl.press("PAD4")')
c = ctl(L)
check((c.get('chooser') or {}).get('hold') is True, "7. Y on a button: its HOLD chooser (no probe in the way)", c)
L.execute('WowCompanion.padTabCtl.press("PAD1")')
check(a(L, 'HDB()["f8"].n') == 'Lightning Bolt', '7. A on a row: the hold spell put')
L.execute('WowCompanion.padTabCtl.press("PAD2"); WowCompanion.padTabCtl.leave()')
# a game binding on a button: its press is the game's own command - no hold there, an old one dropped
L.execute('ACTIONINFO[14] = { "spell", 403 }')
check(a(L, 'WowCompanion.padMap.putHold(1, 2, 2, "spell", 2)') is True, '7. (LT + D-pad Up has a spell and a hold)')
ok_bind = a(L, 'WowCompanion.padMap.putBind(1, 2, 2, "JUMP")')
L.execute('WowCompanion.padMapHold().tick(); advance(1)')
check(ok_bind is True and a(L, 'WowCompanion.padMap.holdOk(1, 2, 2)') is False and a(L, 'HDB()[14]') is None,
      '7. a game binding put on it: no hold there (holdOk false) and its hold dropped', (ok_bind, a(L, 'HDB()[14]')))
check('if type(hv) == "table" and hv.kind == "bind" then report("hold-bind") return true end' in ptc,
      '7. the room: Y on a game binding says hold-bind, nothing opens')

# ---------------------------------------------------------------- (8) THE BIND BOX: its own timing
LB = world('C_GamepadUI = { GetFirstGamepadActionBarStorageSlotIndexForActiveStance = function() return 0 end, IsValidGamepadActionStorageSlotIndex = function() return true end }')
LB.execute('ACTIONINFO[14] = { "spell", 403 }')
LB.execute('BK = WowCompanion._spellsBook; BK.ctl.on = true; BK.ctl.pg = "book"')
n0 = nsent(LB)
LB.execute('BK.startBind({ t = 1, slot = 1, id = 403 }); WowCompanion.padMapHold().tick(true)')
mb = last(LB, 'holdmap', n0)
check(mb.get('why') == 'box' and 'ltrt:6' in (mb.get('b') or '') and 'bare:7' not in (mb.get('b') or ''),
      '8. the Bind box waiting: every place may be held (the plain B is its way out)', mb)
# LT + X held 0.7 s (more than Normal's 0.6 s), its release brings it to the box
LB.execute('PADSTATE = { [LT_I] = true, [3] = true }; BOXRUN(0.7, 0.05); PADSTATE = { [LT_I] = true }; BK.press("PAD3"); PADSTATE = {}')
acks = [x for x in docs(LB, n0, 'spellsack') if x.get('op') == 'bind']
x = acks[-1] if acks else {}
check(x.get('ok') is True and (x.get('pad') or {}).get('hold') is True and (x.get('pad') or {}).get('b') == 2 and (x.get('pad') or {}).get('p') == 5,
      '8. LT + X HELD past the hold time: placed as LT + X\'s hold spell (the ack\'s pad says hold)', x)
check(a(LB, 'HDB()[17].n') == 'Lightning Bolt' and not any(v == 'PlaceAction' for v in lst(LB, 'SPY')), '8. ... stored as the hold, the bar untouched')
# a short press of LT + Y: the normal bind's road (never a hold)
LB.execute('BK.ctl.mode = "book"; BK.ctl.bind = nil; BOXRUN(0.1, 0.05); BK.startBind({ t = 1, slot = 1, id = 403 })')
n1 = nsent(LB)
LB.execute('BOXRUN(0.1, 0.05); PADSTATE = { [LT_I] = true, [4] = true }; BOXRUN(0.2, 0.05); PADSTATE = { [LT_I] = true }; BK.press("PAD4"); PADSTATE = {}')
acks = [x for x in docs(LB, n1, 'spellsack') if x.get('op') == 'bind']
x = acks[-1] if acks else {}
check(x and not (x.get('pad') or {}).get('hold') and a(LB, 'HDB()[18]') is None, '8. LT + Y tapped (0.2 s): the normal bind, never a hold', x)
# the D-pad comes on its press: the box waits for its release or the hold time
LB.execute('BK.ctl.mode = "book"; BK.ctl.bind = nil; BOXRUN(0.1, 0.05); BK.startBind({ t = 1, slot = 1, id = 403 })')
n2 = nsent(LB)
LB.execute('BOXRUN(0.1, 0.05); PADSTATE = { [LT_I] = true, [13] = true }; BOXRUN(0.05, 0.05); BK.press("PADDUP")')
waiting = [x for x in docs(LB, n2, 'spellsack') if x.get('op') == 'bind']
LB.execute('BOXRUN(0.75, 0.05); PADSTATE = {}; BOXRUN(0.1, 0.05)')
acks = [x for x in docs(LB, n2, 'spellsack') if x.get('op') == 'bind']
x = acks[-1] if acks else {}
check(not waiting and x.get('ok') is True and (x.get('pad') or {}).get('hold') is True and (x.get('pad') or {}).get('p') == 2
      and a(LB, 'HDB()[14].n') == 'Lightning Bolt',
      '8. LT + D-pad Up held: nothing at its press, its hold spell at the hold time', (waiting, x))
LB.execute('BK.ctl.mode = "book"; BK.ctl.bind = nil; BOXRUN(0.1, 0.05); WowCompanion.padMap.clearHold(1, 2, 2); BK.startBind({ t = 1, slot = 1, id = 403 })')
n3 = nsent(LB)
LB.execute('BOXRUN(0.1, 0.05); PADSTATE = { [LT_I] = true, [13] = true }; BOXRUN(0.05, 0.05); BK.press("PADDUP"); BOXRUN(0.15, 0.05); PADSTATE = { [LT_I] = true }; BOXRUN(0.1, 0.05); PADSTATE = {}')
acks = [x for x in docs(LB, n3, 'spellsack') if x.get('op') == 'bind']
x = acks[-1] if acks else {}
check(x and not (x.get('pad') or {}).get('hold') and (x.get('probe') or {}).get('pad') == 2 and a(LB, 'HDB() == nil or HDB()[14] == nil'),
      '8. LT + D-pad Up tapped: the normal bind at its release, on the layer read at its PRESS', x)
# an EMPTY button held: the normal bind road (never a hold on nothing)
LB.execute('BK.ctl.mode = "book"; BK.ctl.bind = nil; BOXRUN(0.1, 0.05); BK.startBind({ t = 1, slot = 1, id = 403 })')
n4 = nsent(LB)
LB.execute('BOXRUN(0.1, 0.05); PADSTATE = { [LT_I] = true, [15] = true }; BOXRUN(0.05, 0.05); BK.press("PADDLEFT"); BOXRUN(0.8, 0.05); PADSTATE = {}')
acks = [x for x in docs(LB, n4, 'spellsack') if x.get('op') == 'bind']
x = acks[-1] if acks else {}
check(x and not (x.get('pad') or {}).get('hold') and (x.get('probe') or {}).get('pad') == 2 and a(LB, 'HDB() == nil or HDB()[13] == nil'),
      '8. LT + D-pad Left (empty) held: the normal bind road at once, never a hold', x)
check(not clean(LB), '8. no error in the box\'s watch', clean(LB))

# ---------------------------------------------------------------- (11) m195: THE HOLD SPELL TAKES THE BUTTON (the add-on's half)
LT_ = world()
LT_.execute('WowCompanion.padMap.putHold(1, 2, 6, "spell", 2); WowCompanion.padMap.putHold(1, 2, 6, "spell", 3, "combat")')
fsp = sorted(int(v) for v in (LT_.eval('WowCompanion.padMapFightSpells()') or {}).values())
check(331 in fsp and 324 in fsp, '11. the hold spells of both tabs ride padMapFightSpells (hudS.fs: their usable / mana / range by spell id)', fsp)
# the kept press: WC.padMapKept says it while it is kept (the top bar's charge ring reads it)
edge(LT_, 'PAD4', True, lt=True)
kept = a(LT_, 'WowCompanion.padMapKept(6)')
LT_.execute('LOOK(3)')
n0 = nsent(LT_)
u = edge(LT_, 'PAD4', False, lt=True)
check(kept is True and a(LT_, 'WowCompanion.padMapKept(6)') is False and u['kind'] == 'slothold',
      '11. padMapKept: true while LT + Y is kept, false after its release (the hold ran)', (kept, u['kind']))
check(not docs(LT_, n0, 'holdcast'), '11. the release itself says nothing yet (its cast events do)')
LT_.execute('CASTEND = NOW + 2.5; fire("UNIT_SPELLCAST_START", "player", "g1", 331)')
hc = last(LT_, 'holdcast', n0)
check(hc.get('st') == 'cast' and hc.get('pos') == 6 and hc.get('lay') == 'lt' and abs((hc.get('d') or 0) - 2.5) < 0.01,
      '11. its cast began: holdcast cast, the seconds left (2.5)', hc)
LT_.execute('fire("UNIT_SPELLCAST_SUCCEEDED", "player", "g2", 8042)')
check(last(LT_, 'holdcast', n0).get('st') == 'cast', '11. another spell\'s event: nothing')
LT_.execute('NOW = NOW + 2.5; CASTEND = nil; fire("UNIT_SPELLCAST_SUCCEEDED", "player", "g1", 331)')
check(last(LT_, 'holdcast', n0).get('st') == 'ok' and a(LT_, 'WowCompanion.padMapHold().fire') is None,
      '11. it went off: holdcast ok, the follow ends', last(LT_, 'holdcast', n0))
# refused (no mana / out of range): fail at once
edge(LT_, 'PAD4', True, lt=True); LT_.execute('LOOK(3)'); edge(LT_, 'PAD4', False, lt=True)
n1 = nsent(LT_)
LT_.execute('fire("UNIT_SPELLCAST_FAILED", "player", "g3", 331)')
check(last(LT_, 'holdcast', n1).get('st') == 'fail', '11. the game refused it: holdcast fail', last(LT_, 'holdcast', n1))
# nothing within 3 s: the follow drops by itself (no word: the app's / the top bar's 2 s fallback)
edge(LT_, 'PAD4', True, lt=True); LT_.execute('LOOK(3)'); edge(LT_, 'PAD4', False, lt=True)
n2 = nsent(LT_)
LT_.execute('NOW = NOW + 4; fire("UNIT_SPELLCAST_SUCCEEDED", "player", "g4", 331)')
check(not docs(LT_, n2, 'holdcast') and a(LT_, 'WowCompanion.padMapHold().fire') is None, '11. an event 4 s later: not ours (dropped)')
# a tap: no follow at all
LT_.execute('WowCompanion.padMapHold().fire = nil')
edge(LT_, 'PAD4', True, lt=True); edge(LT_, 'PAD4', False, lt=True)
check(a(LT_, 'WowCompanion.padMapHold().fire') is None, '11. a tap follows nothing')
check(not lst(LT_, 'VIOL') and not lst(LT_, 'SNIPERR') and not lst(LT_, 'ERRS'), '11. nothing blocked, no error', clean(LT_))

# ---------------------------------------------------------------- (9) no trace
check(not lst(L, 'SPY'), '9. no SetBinding / SetCVar / macro write / game window call', lst(L, 'SPY'))
check(not lst(L, 'SNIPERR') and not lst(L, 'HANDLEERR') and not lst(L, 'ERRS'), '9. no snippet / handle / event error',
      (lst(L, 'SNIPERR'), lst(L, 'HANDLEERR'), lst(L, 'ERRS')))
check('"castBar", "castBarFight", "castBarParty", "holdBy" }' in pm, '9. Settings > Reset skills clears the hold spells (holdBy)')

print('%d passed, %d failed' % (passes, fails))
sys.exit(1 if fails else 0)
