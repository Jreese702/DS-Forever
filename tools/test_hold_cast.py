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
ROUND 1533 (PadMap m199; the user on test 1532, 2026-11-25: "I also want this to cast right when the hold bar is filled, not when its
released"; "When the ability is casting it should show the ability with a golden ring, a great example of this is raptor strikes normal
behavior ... with instant cast abilities if I dont instantly get a [gcd] then [don't] linger on it"), section (12): the KEEP WORD (a
kept key-down says facecast hk keep - pos, lay, key, t, need, hid - and pushes, on both post-click hooks: Y and LT + D-pad Up; never a
down); the rel word's hn / need / sl; cn and ft in the padmap's hold and the holdmap, ft's buckets and its hysteresis, holdSecs = the
fire point F; a fight keeps cn the clock's; holdcast's q (a queued next-swing spell, no cut at 3 s; its end: ok with its SUCCEEDED,
else fail), gcd (gl), none (0.15 s: no GCD, no cast, not queued), a cast-time spell's wait for its START; nothing secure added.
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
-- m204 THE FIRE'S MARK: the mapped state's axes (the triggers' analog values) as the router reads them, the client's axis names (0-based:
-- LTrigger 4, RTrigger 5 -> GetGamePadState's axes 5 / 6), and the restricted environment's floor (the game's has it)
PADAXES = {}
C_GamePad.AxisIndexToConfigName = function(i) return ({ [0] = "LStickX", [1] = "LStickY", [2] = "RStickX", [3] = "RStickY", [4] = "LTrigger", [5] = "RTrigger" })[i] end
RESTRICTED.GetGamePadState = function() return { buttons = PADSTATE, axes = PADAXES } end
RESTRICTED.floor = math.floor
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
check('local PV = "m204"' in pm and 'm204 THE RING AND THE CAST AGREE' in pm and 'm203 THE int/1533 REVIEW, ROUND 2' in pm and 'm199 HOLD TO CAST ROUND 1533, THE FIRE AT THE THRESHOLD' in pm and 'm198 EDIT HOME' in pm
      and 'm197 THE 1531 MERGE OF feat/hold-cast' in pm and 'm196 THE 1530 MERGE OF TEST 1529' in pm
      and 'm195 THE HOLD SPELL TAKES THE BUTTON' in pm and 'm194 HOLD TO CAST IN THE GAME' in pm and 'm193 THE 1528b MERGE OF feat/hold-cast' in pm
      and 'm190 HOLD TO CAST' in pm and 'm189 THE 1523 TEST BUILD MERGE' in pm, '0. PadMap m199 (m198, m197, m196, m195 / m194, m193, m190, m189 kept)')
check('local CV = "p63"' in ptc and 'p63 THE 1531 MERGE OF feat/hold-cast' in ptc and 'p62 HOLD TO CAST IN THE GAME' in ptc
      and 'p61 THE 1528b MERGE OF feat/hold-cast' in ptc and 'p60 THE HOLD SPELL BUTTON' in ptc and 'p59 HOLD TO CAST' in ptc,
      '0. PadTabCtl p63 (p62, p61, p60, p59 kept)')
check('local BV = "b89"' in bk and 'b84 THE 1531 MERGE OF feat/hold-cast' in bk and 'b83 THE 1530 MERGE OF TEST 1529' in bk
      and 'b82 HOLD TO CAST IN THE GAME' in bk and 'b81 THE 1528b MERGE OF feat/hold-cast' in bk and 'b79 THE HOLD SPELL BUTTON BY TOUCH' in bk
      and 'P3[28] = function(pt) if type(pt) == "table" and type(pt.holdOpen) == "function" then pcall(pt.holdOpen) end end' in bk
      and 'for k = 0, 1 do P3[26 + k]' in bk
      and 'for k = 0, 2 do P3[29 + k] = function() local pm = WC.padMap; if type(pm) == "table" and type(pm.holdTicks) == "function" then pcall(pm.holdTicks, 2 + k) end end end' in bk,
      '0. Book b84 (b83, b82, b81 kept): the swing 26 / 27, the hold chooser 28, the hold time 29 / 30 / 31')
check('local NB = { V = "nbar28"' in ab and "nbar25 HOLD TO CAST'S VISUALS, ROUND 1533" in ab and 'nbar22 THE 1531 MERGE OF feat/hold-cast' in ab and 'nbar21 THE HOLD SPELL ON THE TOP SCREEN' in ab
      and 'nbar20 THE 1528b MERGE OF feat/hold-cast' in ab and 'nbar18 HOLD TO CAST' in ab,
      '0. ActionBar nbar25 (nbar22, nbar21, nbar20, nbar18 kept): tools/test_hold_wings.py tests it')

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
keeps = [x for x in docs(L, 0, 'facecast') if x.get('kind') == 'holdkeep']
check(keeps and all(x.get('hk') == 'keep' and x.get('down') is None for x in keeps),
      '3. m199: a kept key-down says only the keep word (hk keep, kind holdkeep), never a down (the press log ignores it)', keeps[-1:])
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
# LT + X held 0.75 s (more than Normal's fire point: 0.71 s with the game's frame unknown, m199), its release brings it to the box
check(abs(a(LB, 'WowCompanion.padMap.holdSecs()') - 0.365) < 1e-9, "8. (m204: the box's hold time is the ring's full point R, (3 - 1) x 0.2 - 0.025 - 0.010 = 0.365 s; m199's F 0.71)", a(LB, 'WowCompanion.padMap.holdSecs()'))
LB.execute('PADSTATE = { [LT_I] = true, [3] = true }; BOXRUN(0.75, 0.05); PADSTATE = { [LT_I] = true }; BK.press("PAD3"); PADSTATE = {}')
acks = [x for x in docs(LB, n0, 'spellsack') if x.get('op') == 'bind']
x = acks[-1] if acks else {}
check(x.get('ok') is True and (x.get('pad') or {}).get('hold') is True and (x.get('pad') or {}).get('b') == 2 and (x.get('pad') or {}).get('p') == 5,
      '8. LT + X HELD past the hold time: placed as LT + X\'s hold spell (the ack\'s pad says hold)', x)
check(a(LB, 'HDB()[17].n') == 'Lightning Bolt' and not any(v == 'PlaceAction' for v in lst(LB, 'SPY')), '8. ... stored as the hold, the bar untouched')
# Book b89 (fix/hold-tweaks; the user: "it doesn't have a confirmation on the hold spell"): the same Keep / Undo question as a tap bind
check(a(LB, 'BK.ctl.mode') == 'confirm' and a(LB, 'BK.ctl.undo and BK.ctl.undo.hold') is True and a(LB, 'BK.ctl.undo.prev') is None,
      '8. b89: a hold placed by a long press asks Keep / Undo (mode confirm; nothing was there before)', (a(LB, 'BK.ctl.mode'),))
LB.execute('BK.press("PAD2")')
check(a(LB, 'BK.ctl.mode') == 'bind' and a(LB, 'HDB()[17]') is None,
      '8. b89: B undoes it - the hold gone, the box waiting for a button again', (a(LB, 'BK.ctl.mode'), a(LB, 'HDB()[17] ~= nil')))
LB.execute('WowCompanion.padMap.putHold(1, 2, 5, "spell", 2); BOXRUN(0.1, 0.05)')
LB.execute('PADSTATE = { [LT_I] = true, [3] = true }; BOXRUN(0.75, 0.05); PADSTATE = { [LT_I] = true }; BK.press("PAD3"); PADSTATE = {}')
check(a(LB, 'BK.ctl.mode') == 'confirm' and a(LB, 'HDB()[17].n') == 'Lightning Bolt' and a(LB, 'BK.ctl.undo.prev.n') == 'Healing Wave',
      '8. b89: over a hold that was there (Healing Wave): asked again, the old one kept for an undo', a(LB, 'BK.ctl.mode'))
LB.execute('BK.press("PAD2")')
check(a(LB, 'HDB()[17].n') == 'Healing Wave', '8. b89: B puts the old hold back (PadMap m204 holdRestore)', a(LB, 'HDB()[17] and HDB()[17].n'))
LB.execute('BOXRUN(0.1, 0.05); PADSTATE = { [LT_I] = true, [3] = true }; BOXRUN(0.75, 0.05); PADSTATE = { [LT_I] = true }; BK.press("PAD3"); PADSTATE = {}; BK.press("PAD1")')
check(a(LB, 'BK.ctl.mode') == 'book' and a(LB, 'HDB()[17].n') == 'Lightning Bolt', '8. b89: A keeps the new hold (the box closes to the book)',
      (a(LB, 'BK.ctl.mode'), a(LB, 'HDB()[17] and HDB()[17].n')))
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

# ---------------------------------------------------------------- (12) m199: THE FIRE AT THE THRESHOLD (the add-on's half)
K = world('C_Spell.IsCurrentSpell = function(id) return CURRENT[id] == true end; CURRENT = {}; '
          'C_Spell.GetSpellInfo = function(id) return { castTime = CASTTIME[id] or 0 } end; CASTTIME = {}')
K.execute('WowCompanion.padMap.putHold(1, 2, 6, "spell", 2); WowCompanion.padMap.putHold(1, 1, 8, "spell", 1); ACTIONINFO[14] = { "spell", 403 }')
check(a(K, 'WowCompanion.padMap.putHold(1, 2, 2, "spell", 3)') is True, '12. (LT + D-pad Up: Lightning Shield as its hold)')
K.execute('advance(1)')
# 21. THE KEEP WORD, on both post-click hooks (the face buttons' and the D-pad's)
for key, lt, pos, hid, nm in (('PAD4', True, 6, 331, 'LT + Y'), ('PADDUP', True, 2, 324, 'LT + D-pad Up')):
    n0, p0 = nsent(K), a(K, 'PUSHES')
    d = edge(K, key, True, lt=lt)
    fc = docs(K, n0, 'facecast')
    kw = fc[0] if fc else {}
    check(d['kind'] == 'holdkeep' and len(fc) == 1 and kw.get('hk') == 'keep' and kw.get('kind') == 'holdkeep' and kw.get('pos') == pos
          and kw.get('lay') == 'lt' and kw.get('key') == key and isinstance(kw.get('t'), (int, float)) and kw.get('need') == 3
          and kw.get('hid') == hid and kw.get('down') is None and a(K, 'PUSHES') > p0,
          '12. %s kept: ONE facecast, the keep word (hk keep, pos %d, lay lt, key %s, t, need 3, hid %d), no down, pushed' % (nm, pos, key, hid),
          (d['kind'], fc))
    check(not d['acts'], '12. ... nothing acts on its key-down (as before)', d['acts'])
    K.execute('LOOK(3); NOW = NOW + 0.05')
    n1 = nsent(K)
    u = edge(K, key, False, lt=lt)
    rel = [x for x in docs(K, n1, 'facecast') if x.get('hk') == 'rel']
    rw = rel[-1] if rel else {}
    # 22. the rel word's hn / need / sl
    check(u['kind'] == 'slothold' and rw.get('hn') == 3 and rw.get('need') == 3 and isinstance(rw.get('sl'), (int, float)) and rw['sl'] >= 0.05 - 1e-6,
          '12. %s released after 3 looks: the rel word says hn 3, need 3, sl (the seconds since the 3rd look, >= 0.05)' % nm, rw)
# a tap: the keep word, then the rel word of the tap (no slack: the need never came)
n0 = nsent(K)
edge(K, 'PAD4', True, lt=True); K.execute('LOOK(1)'); u = edge(K, 'PAD4', False, lt=True)
fc = docs(K, n0, 'facecast')
check([x.get('hk') for x in fc] == ['keep', 'rel'] and fc[-1].get('kind') == 'native' and fc[-1].get('hn') == 1 and fc[-1].get('sl') is None,
      '12. a tap: its keep word, then its release (the tap: hn 1, no slack) - the app closes the press on it', fc)
# a press the router does not keep: a down word, never a keep
n0 = nsent(K)
edge(K, 'PAD4', True); edge(K, 'PAD4', False)
fc = docs(K, n0, 'facecast')
check(fc and not [x for x in fc if x.get('hk') == 'keep'] and fc[0].get('down') is True, '12. the bare Y (no hold): no keep word, its down as before', fc)
# 23. cn / ft: the padmap's hold and the holdmap; the frame meter; holdSecs = F
K.execute('WowCompanion.padMap.emit(true)')
hd = (last(K, 'padmap').get('m') or {}).get('hold') or {}
check(hd.get('cn') == 3 and hd.get('n') == 3 and hd.get('ft') is None, "12. the padmap's hold: cn (the clock's looks) 3; ft none measured yet", hd)
check(abs(a(K, 'WowCompanion.padMap.fireSecs()') - 0.710) < 1e-9, '12. holdSecs with ft unknown: F(3, 25) = 0.710 s', a(K, 'WowCompanion.padMap.fireSecs()'))
K.execute('WowCompanion.padMapHold().tick(true)')
ftf = 'WowCompanion.padMapHold().ftf'
check(a(K, ftf + ' ~= nil') and a(K, ftf + ':IsShown()') is True, '12. the frame meter: our own plain frame, shown while the holdmap says on', a(K, ftf + ' ~= nil'))
check(a(K, ftf + '._tpl') == '' and 'Secure' not in (a(K, ftf + '._tpl') or ''), '12. ... a plain frame (no template)')


def frames(L, ms, secs):
    L.execute('local f = WowCompanion.padMapHold().ftf; local t = 0; while t < %f - 1e-9 do NOW = NOW + %f; f.scripts.OnUpdate(f, %f); t = t + %f end'
              % (secs, ms / 1000.0, ms / 1000.0, ms / 1000.0))


frames(K, 16, 2.5)
check(a(K, 'WowCompanion.padMapHold().ft') == 17, '12. 60 fps (16 ms frames): ft 17', a(K, 'WowCompanion.padMapHold().ft'))
check(abs(a(K, 'WowCompanion.padMap.fireSecs()') - 0.678) < 1e-9, '12. fireSecs = F(3, 17) = 0.678 s (60 fps)', a(K, 'WowCompanion.padMap.fireSecs()'))
check(abs(a(K, 'WowCompanion.padMap.holdSecs()') - 0.373) < 1e-9, '12. m204: holdSecs = the ring full point R(3, 17) = 0.373 s (60 fps)', a(K, 'WowCompanion.padMap.holdSecs()'))
frames(K, 40, 0.04)
check(a(K, 'WowCompanion.padMapHold().ft') == 50, '12. one 40 ms frame: raised AT ONCE (the next bucket up: 50)', a(K, 'WowCompanion.padMapHold().ft'))
frames(K, 16, 3)
still = a(K, 'WowCompanion.padMapHold().ft')
frames(K, 16, 3)
check(still == 50 and a(K, 'WowCompanion.padMapHold().ft') == 17, '12. ... lowered only after 5 s of short frames (3 s: still 50; 6 s: 17)',
      (still, a(K, 'WowCompanion.padMapHold().ft')))
n0 = nsent(K)
K.execute('tick(0.25)')
hm = last(K, 'holdmap', n0) or last(K, 'holdmap')
K.execute('WowCompanion.padMapHold().tick(true)')
hm = last(K, 'holdmap')
check(hm.get('cn') == 3 and hm.get('ft') == 17 and hm.get('why') == 'on', '12. the holdmap says cn 3 and ft 17', hm)
check(a(K, 'WowCompanion.padMap.holdTicks(2)') is True and abs(a(K, 'WowCompanion.padMap.fireSecs()') - 0.461) < 1e-9
      and a(K, 'WowCompanion.padMap.holdTicks(4)') is True and abs(a(K, 'WowCompanion.padMap.fireSecs()') - 0.895) < 1e-9,
      '12. Short / Long at 60 fps: 0.461 / 0.895 s', a(K, 'WowCompanion.padMap.fireSecs()'))
K.execute('WowCompanion.padMap.holdTicks(3)')
# a fight with the hold time changed: cn stays the clock's (the wish waits for the fight's end)
K.execute('fire("PLAYER_REGEN_DISABLED"); COMBAT = true; driveStates(); advance(1); WowCompanion.padMap.holdTicks(2); WowCompanion.padMapHold().tick(true)')
hm = last(K, 'holdmap')
check(hm.get('cn') == 3 and a(K, 'WowCompanion.padMap.holdTicksNow()') == 2 and abs(a(K, 'WowCompanion.padMap.fireSecs()') - 0.678) < 1e-9,
      "12. in a fight a new hold time is the wish only: cn (the clock's) stays 3, holdSecs F(3)", (hm, a(K, 'WowCompanion.padMap.holdTicksNow()')))
K.execute('COMBAT = false; driveStates(); fire("PLAYER_REGEN_ENABLED"); advance(1); WowCompanion.padMapHold().tick(true)')
check(last(K, 'holdmap').get('cn') == 2, "12. ... the fight's end: the clock takes it, cn 2", last(K, 'holdmap'))
K.execute('WowCompanion.padMap.holdTicks(3); WowCompanion.padMapHold().tick(true)')
# 23b. m202 (the int/1533 review): the app's session bump after a SHORT fire (Book b87's fourth-page codes 2-5) in our fire point too, so
# the top screen's ring fills when the launcher lets the press go; in memory only, the padmap's hold.hb says it
K.execute('WowCompanion.padMap.emit(true)')
hd = (last(K, 'padmap').get('m') or {}).get('hold') or {}
check(hd.get('hb') == 0 and abs(a(K, 'WowCompanion.padMap.fireSecs()') - 0.678) < 1e-9, "12b. m202: the padmap's hold says hb 0, F 0.678 s", hd)
db0 = a(K, 'WowCompanionSpellsDB.holdBump')
check(a(K, 'WowCompanion.padMap.holdBump(2)') is True and abs(a(K, 'WowCompanion.padMap.fireSecs()') - (0.678 + 0.068)) < 1e-9,
      '12b. holdBump(2): F + 2 x 34 ms = 0.746 s', a(K, 'WowCompanion.padMap.fireSecs()'))
K.execute('WowCompanion.padMap.emit(true)')
check(((last(K, 'padmap').get('m') or {}).get('hold') or {}).get('hb') == 2, "12b. ... the padmap's hold says hb 2 (the app stops sending)",
      (last(K, 'padmap').get('m') or {}).get('hold'))
check(a(K, '(WowCompanion.padMap.holdBump(4))') is False and a(K, '(WowCompanion.padMap.holdBump(-1))') is False
      and a(K, 'WowCompanion.padMapHold().bump') == 2, '12b. only 0-3 steps (4, -1 refused)')
check(a(K, 'WowCompanion.padMap.holdTicks(4)') is True and abs(a(K, 'WowCompanion.padMap.fireSecs()') - (0.895 + 0.068)) < 1e-9,
      '12b. ... on every hold time (Long: 0.963 s)', a(K, 'WowCompanion.padMap.fireSecs()'))
K.execute('WowCompanion.padMap.holdTicks(3)')
check(a(K, 'WowCompanion.padMap.holdBump(0)') is True and abs(a(K, 'WowCompanion.padMap.fireSecs()') - 0.678) < 1e-9 and db0 is None
      and a(K, 'WowCompanionSpellsDB.holdBump') is None, '12b. holdBump(0): F again; nothing saved')
# 23c. m203 (the int/1533 review, round 2): THE BUMP IN A FIGHT TOO. The app sends its steps only out of a fight (Book b88's page-4
# codes), so a SHORT fire in a fight left our F - the ring, the only one in single screen mode - up to 0.1 s ahead of the cast until the
# fight's end. We see a SHORT ourselves: a kept press's release that ran the tap ONE look short (hn = need - 1) at or after the moment
# the need-th look was due (the keep's frozen F less one frame and 10 ms) - the launcher's early fire whose up beat a late look, as the
# app's HoldRelease.short() counts it - and step our bump as the app steps its own (+1, up to 3). In memory only; the app's steps win
SB = world()
SB.execute('WowCompanion.padMap.putHold(1, 2, 6, "spell", 2); WowCompanion.padMap.putHold(1, 2, 6, "spell", 3, "combat"); advance(1)')
SB.execute('WowCompanion.padMapHold().tick(true)')
frames(SB, 16, 2.5)
f0 = a(SB, 'WowCompanion.padMap.fireSecs()')
check(a(SB, 'WowCompanion.padMapHold().ft') == 17 and abs(f0 - 0.678) < 1e-9 and a(SB, 'WowCompanion.padMapHold().bump or 0') == 0,
      '12c. (60 fps: F(3, 17) = 0.678 s, no bump)', (a(SB, 'WowCompanion.padMapHold().ft'), f0))


def kept(L, looks, extra, fight=False):
    if fight:
        L.execute('local b = HB("WowCompanionCombatFace6"); if b then b.a["wc-state"] = "fight"; b.a["wc-given"] = nil end')
    d = edge(L, 'PAD4', True, lt=True)
    L.execute('LOOK(%d); NOW = NOW + %f' % (looks, extra))
    n = nsent(L)
    u = edge(L, 'PAD4', False, lt=True)
    rel = [x for x in docs(L, n, 'facecast') if x.get('hk') == 'rel']
    return d, u, (rel[-1] if rel else {})


def steps(L):
    return a(L, 'WowCompanion.padMapHold().bump or 0')


# released before the need-th look was due (0.5 s < 3 x 0.217 = 0.651 s): the player's own tap, never a bump
d, u, rw = kept(SB, 2, 0.1)
check(d['kind'] == 'holdkeep' and rw.get('hn') == 2 and rw.get('need') == 3 and steps(SB) == 0,
      '12c. a release at 0.5 s, 2 looks: the tap, no bump (the player let go before the fill)', (d['kind'], rw, steps(SB)))
# the launcher's fire at F (0.678 s) beat a late third look: the tap ran, one look short -> one step, F + 34 ms
d, u, rw = kept(SB, 2, 0.278)
check(rw.get('hn') == 2 and rw.get('kind') != 'slothold' and steps(SB) == 1
      and abs(a(SB, 'WowCompanion.padMap.fireSecs()') - (f0 + 0.034)) < 1e-9,
      '12c. a release at F (0.678 s) with 2 looks (the clock late): a SHORT - one step, F 0.712 s', (rw, steps(SB), a(SB, 'WowCompanion.padMap.fireSecs()')))
# a frame inside F (0.660 s >= 0.651 s): still the launcher's fire (its up read a frame early)
d, u, rw = kept(SB, 2, 0.26 + 0.034)
check(steps(SB) == 2, '12c. ... read a frame before the new F (0.694 s >= 0.685 s): a SHORT too (two steps)', steps(SB))
# the hold ran (3 looks): no bump; two looks short (hn = need - 2): not the launcher's one-look miss, no bump
d, u, rw = kept(SB, 3, 0.05)
check(u['kind'] == 'slothold' and steps(SB) == 2, '12c. the hold ran (3 looks): no step', (u['kind'], steps(SB)))
d, u, rw = kept(SB, 1, 0.7)
check(rw.get('hn') == 1 and steps(SB) == 2, '12c. one look in 0.9 s (two short): no step', (rw, steps(SB)))
SB.execute('WowCompanion.padMap.emit(true)')
check(((last(SB, 'padmap').get('m') or {}).get('hold') or {}).get('hb') == 2, "12c. the padmap's hold says hb 2 (the app sees it: nothing to send)",
      (last(SB, 'padmap').get('m') or {}).get('hold'))
# IN A FIGHT (the case the app cannot send in): In combat's hold, the same step at once; capped at 3
SB.execute('fire("PLAYER_REGEN_DISABLED"); COMBAT = true; driveStates(); advance(1)')
v0 = len(lst(SB, 'VIOL'))
d, u, rw = kept(SB, 2, 0.346, fight=True)                          # F(3, 17) + 2 steps = 0.746 s
check(d['kind'] == 'holdkeep' and rw.get('hn') == 2 and rw.get('fight') is True and steps(SB) == 3
      and abs(a(SB, 'WowCompanion.padMap.fireSecs()') - (f0 + 0.102)) < 1e-9,
      '12c. in a fight: a SHORT steps the bump at once (3 steps, F 0.780 s) - no app send needed', (d['kind'], rw, steps(SB)))
d, u, rw = kept(SB, 2, 0.6, fight=True)
check(steps(SB) == 3, '12c. ... capped at 3 steps (102 ms), as the app', steps(SB))
check(len(lst(SB, 'VIOL')) == v0 and not lst(SB, 'SNIPERR') and not lst(SB, 'HANDLEERR') and not lst(SB, 'ERRS') and not lst(SB, 'SPY'),
      '12c. nothing blocked, no error, nothing spied in the fight', clean(SB))
SB.execute('COMBAT = false; driveStates(); fire("PLAYER_REGEN_ENABLED"); advance(1)')
# the app's own steps win (out of a fight it sends them while hold.hb differs): 1 = 1
check(a(SB, 'WowCompanion.padMap.holdBump(1)') is True and steps(SB) == 1 and abs(a(SB, 'WowCompanion.padMap.fireSecs()') - (f0 + 0.034)) < 1e-9
      and a(SB, 'WowCompanionSpellsDB.holdBump') is None, "12c. the app's send (1 step) wins; nothing saved", steps(SB))
blk = pm[pm.index('-- m203 THE BUMP IN A FIGHT TOO'):pm.index('function OV.hold.relSafe(pos, b)')]
code = '\n'.join(l.split('--', 1)[0] for l in blk.splitlines())
check(not re.search(r'SetCVar|SetBinding|SetOverrideBinding|CreateMacro|EditMacro|SetAttribute|WowCompanionSpellsDB|ALT-', code),
      '12c. the new code writes no CVar / binding / macro / attribute / saved variable', code[:200])

# 24. THE FOLLOW: q / ok / fail / none / gcd / a cast's START
def fire_hold(L):
    edge(L, 'PAD4', True, lt=True); L.execute('LOOK(3)')
    n = nsent(L)
    u = edge(L, 'PAD4', False, lt=True)
    return n, u


K.execute('CURRENT[331] = true')
n0, u = fire_hold(K)
hc = docs(K, n0, 'holdcast')
check(u['kind'] == 'slothold' and [x.get('st') for x in hc] == ['q'] and hc[0].get('pos') == 6 and hc[0].get('lay') == 'lt',
      '12. a next-swing hold (Healing Wave current at the fire: queued): holdcast q at once', hc)
K.execute('advance(4); fire("UNIT_SPELLCAST_SUCCEEDED", "player", "gx", 8042)')
check([x.get('st') for x in docs(K, n0, 'holdcast')] == ['q'] and a(K, 'WowCompanion.padMapHold().fire ~= nil'),
      '12. ... 4 s queued: no none, no cut at 3 s (the follow waits for the swing)', docs(K, n0, 'holdcast'))
K.execute('CURRENT[331] = nil; fire("CURRENT_SPELL_CAST_CHANGED"); fire("UNIT_SPELLCAST_SUCCEEDED", "player", "g9", 331)')
check([x.get('st') for x in docs(K, n0, 'holdcast')] == ['q', 'none'] and a(K, 'WowCompanion.padMapHold().fire') is None,
      '12. ... the swing landed (no longer current, its SUCCEEDED) with no GCD of its own: none - back at once, as the game\'s own '
      'checked ring (the fix pass; was ok + 0.3 s), the follow ends', docs(K, n0, 'holdcast'))
K.execute('advance(1)')
check([x.get('st') for x in docs(K, n0, 'holdcast')] == ['q', 'none'], '12. ... no fail after it', docs(K, n0, 'holdcast'))
K.execute('CURRENT[331] = true')
n0, u = fire_hold(K)
K.execute('advance(1); CURRENT[331] = nil; fire("CURRENT_SPELL_CAST_CHANGED")')
mid = [x.get('st') for x in docs(K, n0, 'holdcast')]
K.execute('advance(0.3)')
check(mid == ['q'] and [x.get('st') for x in docs(K, n0, 'holdcast')] == ['q', 'fail'],
      '12. unqueued (no longer current, no SUCCEEDED within 0.2 s): fail', (mid, docs(K, n0, 'holdcast')))
# an instant with no GCD and no queue: none at 0.15 s
n0, u = fire_hold(K)
K.execute('advance(0.1)')
early = [x.get('st') for x in docs(K, n0, 'holdcast')]
K.execute('advance(0.1)')
hc = docs(K, n0, 'holdcast')
check(early == [] and [x.get('st') for x in hc] == ['none'] and a(K, 'WowCompanion.padMapHold().fire') is None,
      '12. no GCD, no cast, not queued: none 0.15 s after the fire (nothing at 0.1 s)', (early, hc))
# an instant on the GCD: gcd with gl at the first SPELL_UPDATE_COOLDOWN that shows it
n0, u = fire_hold(K)
K.execute('CD[331] = { isActive = true, isOnGCD = true, duration = 1.5, startTime = NOW }; NOW = NOW + 0.05; fire("SPELL_UPDATE_COOLDOWN")')
hc = docs(K, n0, 'holdcast')
check([x.get('st') for x in hc] == ['gcd'] and abs((hc[0].get('gl') or 0) - 1.45) < 0.01, '12. its GCD shown: holdcast gcd, gl = 1.45 s left', hc)
K.execute('advance(0.3)')
check([x.get('st') for x in docs(K, n0, 'holdcast')] == ['gcd'], '12. ... no none after a GCD', docs(K, n0, 'holdcast'))
K.execute('CD[331] = nil; NOW = NOW + 2; fire("SPELL_UPDATE_COOLDOWN"); WowCompanion.padMapHold().fire = nil')
# a spell with a cast time: waits for its START up to 0.5 s
K.execute('CASTTIME[331] = 2500')
n0, u = fire_hold(K)
K.execute('advance(0.3)')
w = [x.get('st') for x in docs(K, n0, 'holdcast')]
K.execute('CASTEND = NOW + 2.2; fire("UNIT_SPELLCAST_START", "player", "g5", 331); advance(0.5)')
hc = docs(K, n0, 'holdcast')
check(w == [] and [x.get('st') for x in hc] == ['cast'], '12. a 2.5 s cast: no none while its START is awaited (0.3 s), then cast', (w, hc))
K.execute('CASTEND = nil; WowCompanion.padMapHold().fire = nil')
n0, u = fire_hold(K)
K.execute('advance(0.45)')
w = [x.get('st') for x in docs(K, n0, 'holdcast')]
K.execute('advance(0.1)')
check(w == [] and [x.get('st') for x in docs(K, n0, 'holdcast')] == ['none'], '12. ... no START by 0.5 s: none then', (w, docs(K, n0, 'holdcast')))
K.execute('CASTTIME[331] = nil')
# 26. THE FIX PASS (two reviews of round 1533)
# (a) the fired hold's first words FOLLOW the press's rel word, on both post-click hooks (the face buttons' and the D-pad's): Home's
# takeover for a release before the fire point is started by the rel word, a q said before it was dropped (a queued Raptor Strike's
# face went at the 2 s fallback)
def order(L, n0):
    return [(x.get('type'), x.get('hk') or x.get('st')) for x in docs(L, n0) if x.get('type') in ('facecast', 'holdcast')]


K.execute('CURRENT[331] = true')
n0, u = fire_hold(K)
o = order(K, n0)
check(u['kind'] == 'slothold' and o == [('facecast', 'rel'), ('holdcast', 'q')],
      '26. LT + Y (the face buttons\' hook): its rel word, THEN the hold\'s q', o)
K.execute('CURRENT[331] = nil; fire("CURRENT_SPELL_CAST_CHANGED"); advance(0.3); WowCompanion.padMapHold().fire = nil')
K.execute('CURRENT[324] = true')
edge(K, 'PADDUP', True, lt=True); K.execute('LOOK(3)')
n0 = nsent(K)
u = edge(K, 'PADDUP', False, lt=True)
o = order(K, n0)
check(u['kind'] == 'slothold' and o == [('facecast', 'rel'), ('holdcast', 'q')],
      '26. LT + D-pad Up (the D-pad\'s hook): its rel word, THEN the hold\'s q', o)
K.execute('CURRENT[324] = nil; fire("CURRENT_SPELL_CAST_CHANGED"); advance(0.3); WowCompanion.padMapHold().fire = nil')
n0, u = fire_hold(K)
K.execute('CD[331] = { isActive = true, isOnGCD = true, duration = 1.5, startTime = NOW }')
K.execute('fire("SPELL_UPDATE_COOLDOWN")')
o = order(K, n0)
check(o[:2] == [('facecast', 'rel'), ('holdcast', 'gcd')], '26. ... an immediate gcd follows the rel word too', o)
K.execute('fire("UNIT_SPELLCAST_SUCCEEDED", "player", "gg", 331)')
check([x.get('st') for x in docs(K, n0, 'holdcast')] == ['gcd', 'ok'], '26. an instant ON the GCD that went off: gcd, then ok (its GCD keeps the face)',
      docs(K, n0, 'holdcast'))
K.execute('CD[331] = nil; NOW = NOW + 2; fire("SPELL_UPDATE_COOLDOWN"); WowCompanion.padMapHold().fire = nil')
# (b) an instant OFF the GCD whose SUCCEEDED comes fast (a normal connection: under the 0.15 s none check): none, never ok (+0.3 s)
n0, u = fire_hold(K)
K.execute('advance(0.05); fire("UNIT_SPELLCAST_SUCCEEDED", "player", "gq", 331)')
early = [x.get('st') for x in docs(K, n0, 'holdcast')]
K.execute('advance(0.15)')
hc = [x.get('st') for x in docs(K, n0, 'holdcast')]
check(early == [] and hc == ['none'] and a(K, 'WowCompanion.padMapHold().fire') is None,
      '26. an interrupt (off the GCD) that went off at 0.05 s: none at the 0.15 s check, no ok (no linger)', (early, hc))
K.execute('advance(1)')
check([x.get('st') for x in docs(K, n0, 'holdcast')] == ['none'], '26. ... nothing after it', docs(K, n0, 'holdcast'))
# ... its SUCCEEDED after the check (a slow server): none at the check, the late SUCCEEDED says nothing
n0, u = fire_hold(K)
K.execute('advance(0.3); fire("UNIT_SPELLCAST_SUCCEEDED", "player", "gr", 331)')
check([x.get('st') for x in docs(K, n0, 'holdcast')] == ['none'], '26. ... a slow server: none at 0.15 s, the late SUCCEEDED nothing', docs(K, n0, 'holdcast'))
# ... another spell's GCD that started 0.3 s BEFORE the fire (a D-pad tap) is not the interrupt's
K.execute('CD[331] = { isActive = true, isOnGCD = false, duration = 15, startTime = NOW }; WowCompanion.gcdRun = { s = NOW - 0.3, d = 1.5 }')
n0, u = fire_hold(K)
K.execute('advance(0.05); fire("UNIT_SPELLCAST_SUCCEEDED", "player", "gs", 331); advance(0.15)')
check([x.get('st') for x in docs(K, n0, 'holdcast')] == ['none'], "26. ... a D-pad spell's GCD 0.3 s before the fire: still none", docs(K, n0, 'holdcast'))
K.execute('CD[331] = nil; WowCompanion.gcdRun = nil')
# ... its SUCCEEDED before the GCD read showed (the check at 0.15 s sees it): gcd, ok
n0, u = fire_hold(K)
K.execute('advance(0.02); fire("UNIT_SPELLCAST_SUCCEEDED", "player", "gt", 331)')
K.execute('CD[331] = { isActive = true, isOnGCD = true, duration = 1.5, startTime = NOW - 0.02 }; advance(0.15)')
check([x.get('st') for x in docs(K, n0, 'holdcast')] == ['gcd', 'ok'], '26. ... SUCCEEDED before its GCD read: the 0.15 s check says gcd, ok',
      docs(K, n0, 'holdcast'))
K.execute('CD[331] = nil; NOW = NOW + 2; fire("SPELL_UPDATE_COOLDOWN"); WowCompanion.padMapHold().fire = nil')
# (c) a QUEUED on-GCD spell that goes off (the game's spell-queue window: queued in the last GCD's end, current, its GCD read in the
# SPELL_UPDATE_COOLDOWN handler): gcd, then ok - its own GCD keeps the face; another spell's GCD while a next-swing one waits is not its
K.execute('CURRENT[331] = true')
n0, u = fire_hold(K)
K.execute('CD[331] = { isActive = true, isOnGCD = true, duration = 1.5, startTime = NOW - 1.2 }; fire("SPELL_UPDATE_COOLDOWN")')
mid = [x.get('st') for x in docs(K, n0, 'holdcast')]
K.execute('advance(0.3); CD[331] = { isActive = true, isOnGCD = true, duration = 1.5, startTime = NOW }; CURRENT[331] = nil; '
          'fire("CURRENT_SPELL_CAST_CHANGED"); fire("UNIT_SPELLCAST_SUCCEEDED", "player", "gu", 331)')
hc = docs(K, n0, 'holdcast')
check(mid == ['q'] and [x.get('st') for x in hc] == ['q', 'gcd', 'ok'] and abs((hc[1].get('gl') or 0) - 1.5) < 0.01,
      '26. queued in the spell-queue window (the last GCD running: no gcd word for it), then off: gcd 1.5, ok', (mid, hc))
K.execute('CD[331] = nil; NOW = NOW + 2; fire("SPELL_UPDATE_COOLDOWN"); WowCompanion.padMapHold().fire = nil')
K.execute('CURRENT[331] = true')
n0, u = fire_hold(K)
K.execute('advance(1); WowCompanion.gcdRun = { s = NOW, d = 1.5 }; CURRENT[331] = nil; fire("CURRENT_SPELL_CAST_CHANGED"); '
          'fire("UNIT_SPELLCAST_SUCCEEDED", "player", "gv", 331)')
check([x.get('st') for x in docs(K, n0, 'holdcast')] == ['q', 'none'],
      "26. a next-swing spell landing while ANOTHER spell's GCD runs (started while it waited): none (that GCD is not its own)",
      docs(K, n0, 'holdcast'))
K.execute('WowCompanion.gcdRun = nil; advance(0.3)')
# (d) THE GAME'S FRAME: no cap at 50 ms (a sustained 12 fps took the tap: F(3, 50) 810 ms < the 3rd look's 849 ms)
frames(K, 83, 2.5)
check(a(K, 'WowCompanion.padMapHold().ft') == 84 and abs(a(K, 'WowCompanion.padMap.fireSecs()') - 0.946) < 1e-9,
      '26. a sustained 12 fps (83 ms frames): ft 84, F(3, 84) = 0.946 s (past the 3rd look\'s 3 x 283 = 0.849 s)',
      (a(K, 'WowCompanion.padMapHold().ft'), a(K, 'WowCompanion.padMap.fireSecs()')))
frames(K, 300, 0.3)
check(a(K, 'WowCompanion.padMapHold().ft') == 134, '26. ... a 300 ms frame: the last bucket, 134', a(K, 'WowCompanion.padMapHold().ft'))
frames(K, 16, 7)
check(a(K, 'WowCompanion.padMapHold().ft') == 17, '26. ... 60 fps again for 7 s: 17', a(K, 'WowCompanion.padMapHold().ft'))
# ... the meter runs whenever a hold exists on EITHER tab (holds only on In combat: "none" out of combat, the pull read a stale ft)
FT = world()
FT.execute('WowCompanion.padMap.putHold(1, 2, 6, "spell", 2, "combat"); advance(1); WowCompanion.padMapHold().tick(true)')
hm = last(FT, 'holdmap')
check(hm.get('why') == 'none' and a(FT, 'WowCompanion.padMapHold().ftf ~= nil') and a(FT, 'WowCompanion.padMapHold().ftf:IsShown()') is True,
      '26. a hold only on In combat, out of combat (why none): the frame meter runs (ft current at the pull)',
      (hm, a(FT, 'WowCompanion.padMapHold().ftf ~= nil')))
frames(FT, 33, 2.5)
FT.execute('WowCompanion.padMapHold().tick(true)')
check(last(FT, 'holdmap').get('ft') == 34, '26. ... measured out of combat: the holdmap says ft 34 before the fight', last(FT, 'holdmap'))
FT.execute('fire("LOADING_SCREEN_ENABLED")')
check(a(FT, 'WowCompanion.padMapHold().ftf:IsShown()') is False, '26. ... a loading screen: the meter stops')
FT.execute('fire("LOADING_SCREEN_DISABLED")')
check(a(FT, 'WowCompanion.padMapHold().ftf:IsShown()') is True, '26. ... after it: on again')
FT.execute('local t = WowCompanionSpellsDB.holdBy; for k, r in pairs(t or {}) do r.combat = nil; r.normal = nil end; WowCompanion.padMapHold().tick(true)')
check(a(FT, 'WowCompanion.padMapHold().ftf:IsShown()') is False, '26. no hold on either tab: the meter stops')
check(not lst(FT, 'VIOL') and not lst(FT, 'SNIPERR') and not lst(FT, 'ERRS'), '26. nothing blocked, no error', clean(FT))

# 25. nothing secure added: no blocked call, no snippet / handle error, no CVar / binding / macro write; the hook only on our buttons
check(not lst(K, 'VIOL') and not lst(K, 'SNIPERR') and not lst(K, 'HANDLEERR') and not lst(K, 'ERRS'), '12. nothing blocked, no error', clean(K))
check(not lst(K, 'SPY'), '12. no SetBinding / SetCVar / macro write', lst(K, 'SPY'))
hooked = a(K, '(function() local n = 0; for _, f in ipairs(ALLF) do if f.hooks and f.hooks.OnAttributeChanged then '
              'local nm = f._name or ""; if not (nm:match("^WowCompanionCombatFace%d$") or nm:match("^WowCompanionFaceD%d$")) then return "other " .. nm end; n = n + 1 end end; return n end)()')
check(hooked == 7, "12. OnAttributeChanged hooked on our own router buttons only (A / X / Y, the D-pad's four; never B)", hooked)
check('HookScript("OnAttributeChanged"' in pm and pm.count('HookScript("OnAttributeChanged"') == 2
      and 'if pos ~= 7 then pcall(function() b:HookScript("OnAttributeChanged"' in pm,
      '12. ... added where the buttons are made (out of combat), never on B')

# ---------------------------------------------------------------- (27) THE RING AND THE CAST AGREE (fix/hold-tweaks, 1535)
# The user on test 1533, 2026-11-26: "The hold to cast is triggering when releasing before the hold completes". The router decides at the
# release by the clock's looks, and the game's state driver may look in the very frame of the keep (its throttle is the game's, never
# reset by the press: SecureStateDriver's timer <= 0 then 0.2 again) - so the need-th look can come (need - 1) x 0.2 s after the press,
# well before F. The ring (holdSecs: ActionBar's fill point, the Bind box's hold time) must be full no later than the earliest moment
# the router can pick the hold: a release before it is ALWAYS the tap.
R27 = world()
R27.execute('WowCompanion.padMap.putHold(1, 1, 8, "spell", 1); advance(1)')
R27.execute('''function EARLY(rel)
    local t0 = NOW
    driveStates()                                   -- the keep's own frame: the throttle ran out there (the earliest look)
    local t = 0.2
    while t < rel - 1e-9 do NOW = t0 + t; driveStates(); t = t + 0.2 end
    NOW = t0 + rel
end''')
ring27 = a(R27, 'WowCompanion.padMap.holdSecs()')
d = edge(R27, 'PAD1', True)
R27.eval('EARLY')(0.417)
u = edge(R27, 'PAD1', False)
check(d['kind'] == 'holdkeep' and (u['kind'] != 'slothold' or 0.417 >= ring27 - 1e-9),
      '27. RED BEFORE THE FIX: the third look came 0.4 s after the press (the first in the keep\'s own frame), a release at 0.417 s '
      'ran the hold - the ring (holdSecs) must be full by then', (u['kind'], ring27))
d = edge(R27, 'PAD1', True)
R27.eval('EARLY')(ring27 - 0.001)
u = edge(R27, 'PAD1', False)
check(u['kind'] != 'slothold', '27. a release 1 ms before the ring is full, the looks as early as the game can make them: the TAP',
      (u['kind'], ring27))
check(ring27 <= (3 - 1) * 0.2 - 0.025 - 0.010 + 1e-9,
      '27. the ring\'s full point (Normal, 3 looks, ft unknown 25): (3 - 1) x 0.2 s - one frame - 10 ms = 0.365 s at most', ring27)

# ---------------------------------------------------------------- (28) THE FIRE'S MARK (fix/hold-tweaks; the user's pick "Cast at the fill")
# the app's launcher writes a code on a free trigger's analog axis: 3 while a press is held (armed), 6 + the control in the write that lets a
# kept press go at the ring's full point R - the router runs the hold on its own fire code (one look is all the clock has there)
M28 = world()
M28.execute('WowCompanion.padMap.putHold(1, 1, 8, "spell", 1); advance(1)')
nA = 'HB("WowCompanionCombatFace8")'
check(a(M28, nA + '.a["wc-axl"]') == 5 and a(M28, nA + '.a["wc-axr"]') == 6 and a(M28, nA + '.a["wc-hmc"]') == 6
      and a(M28, '(function() local x = HB("WowCompanionCombatFace5"); return x and x.a["wc-hmc"] end)()') in (7, None)
      and a(M28, '(function() local x = HB("WowCompanionCombatFace7"); return x and x.a["wc-hmc"] end)()') is None,
      '28. the router knows the triggers axes (5 / 6) and each button its fire code (A 6, X 7; never B)',
      (a(M28, nA + '.a["wc-axl"]'), a(M28, nA + '.a["wc-axr"]'), a(M28, nA + '.a["wc-hmc"]')))
def mark(L, l=None, r=None):
    L.execute('PADAXES = {}' + ('; PADAXES[5] = %d / 255' % l if l is not None else '') + ('; PADAXES[6] = %d / 255' % r if r is not None else ''))
n0 = nsent(M28)
mark(M28, l=3)
d = edge(M28, 'PAD1', True)
kw = [x for x in docs(M28, n0, 'facecast') if x.get('hk') == 'keep']
check(d['kind'] == 'holdkeep' and kw and kw[-1].get('mk') == 1 and a(M28, nA + '.a["wc-hm"]') is True,
      '28. the armed code (3) with the key-down: kept, the keep word says mk 1', (d['kind'], kw[-1:] if kw else None))
M28.execute('driveStates()')                                    # one look: the ring's full point comes before the third
mark(M28, l=6)
u = edge(M28, 'PAD1', False)
check(u['kind'] == 'slothold', "28. RED BEFORE: the launcher's up at R with A's fire code (6), one look: the HOLD at the fill", u['kind'])
mark(M28, l=3)
edge(M28, 'PAD1', True); M28.execute('driveStates()'); mark(M28, l=3)
u = edge(M28, 'PAD1', False)
check(u['kind'] != 'slothold', '28. the player let go before the fill (only the armed code): the TAP', u['kind'])
edge(M28, 'PAD1', True); M28.execute('driveStates()'); mark(M28, r=7)
u = edge(M28, 'PAD1', False)
check(u['kind'] != 'slothold', "28. another button's fire code (X's 7) on A's up: the TAP", u['kind'])
edge(M28, 'PAD1', True); M28.execute('driveStates(); PADAXES = { [5] = 6.6 / 255 }')
u = edge(M28, 'PAD1', False)
check(u['kind'] != 'slothold', '28. an inexact value (6.6 / 255: a trigger brushed) is no code: the TAP', u['kind'])
n1 = nsent(M28)
mark(M28)
edge(M28, 'PAD1', True)
kw = [x for x in docs(M28, n1, 'facecast') if x.get('hk') == 'keep']
check(kw and kw[-1].get('mk') is None and a(M28, nA + '.a["wc-hm"]') is None, '28. no code with the key-down: no mk (the app fires at F)', kw[-1:] if kw else None)
M28.execute('LOOK(3)')
u = edge(M28, 'PAD1', False)
check(u['kind'] == 'slothold', '28. ... and the clock still decides without a code (3 looks: the hold at F)', u['kind'])
check(not lst(M28, 'SNIPERR') and not lst(M28, 'VIOL') and not lst(M28, 'SPY'), '28. no snippet error, nothing blocked, no trace', clean(M28))

# ---------------------------------------------------------------- (9) no trace
check(not lst(L, 'SPY'), '9. no SetBinding / SetCVar / macro write / game window call', lst(L, 'SPY'))
check(not lst(L, 'SNIPERR') and not lst(L, 'HANDLEERR') and not lst(L, 'ERRS'), '9. no snippet / handle / event error',
      (lst(L, 'SNIPERR'), lst(L, 'HANDLEERR'), lst(L, 'ERRS')))
check('"castBar", "castBarFight", "castBarParty", "holdBy" }' in pm, '9. Settings > Reset skills clears the hold spells (holdBy)')

print('%d passed, %d failed' % (passes, fails))
sys.exit(1 if fails else 0)
