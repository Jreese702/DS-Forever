"""HOLD TO CAST (PadMap m190, PadTabCtl p59, Book b78, ActionBar nbar18), off-device: the REAL add-on files under tools/wowstub.py's game
with STRICT laid over it (the lockdown, the restricted snippets, the press model) and test_ltrt_stancebar.py's bars.

    python -B tools/test_hold_cast.py

The user, 2026-11-24: "I want to give another option in the controller menu to "hold to cast" on a button so that we can have 2 spells
per button ... We need to find an intuitive way to show two cooldowns on one button". The picks: "A: corner badge", "At the hold time,
Settings: 0.3/0.4/0.55 s", "Every button incl. B", "Yes, a quick test first". The APP times the press (HoldGate.kt) and sends a held press
WITH ITS MARKER (the virtual pad's Guide button held at the press); the add-on's router reads the marker and runs the place's hold text.

Proves: (0) the tags and their notes; (1) THE PROBE: a few seconds after the login, out of combat, the bindings on the Guide's names are
READ first - "ready" (nothing bound); the app's pulse is SEEN by the game's event, add-on code's controller state and the router's own
secure read; "done" names it; hold to cast on (db.holdGuide, every router button's "wc-gd"); nothing pulsed -> nothing changed; a binding
on PADSYSTEM (another add-on's override) or the player's emulated modifier on it -> "ready" says bound and NO watch opens, hold to cast
stays off; the game's menu opening at the pulse -> "acted", off; in a fight the probe waits for its end (no secure call in the lockdown);
(2) THE STORE: putHold per place (LT + Y's slot 18, the bare A / B / X by position), per character, out of combat only, never a binding,
refused while unavailable; the header's hold texts; the router carries the keys out of combat; (3) THE ROUTER: LT + Y held (the marker)
= Healing Wave (slothold), the same press without the marker = the slot's own button; the marker on a place with no hold = the press as
always; the bare A's hold (Lightning Bolt) and its tap (the game's own Jump button); B's hold in a fight = the hold spell alone (no
give-back); the marker in room mode = the room's press; in a fight no blocked call; LT released before the face button is the app's
(the trigger it sends is the one held at the press); (4) THE APP'S MAP (`holdmap`): the routed places with a hold now; the bare A left
out while mounted (Jump / ascend), the bare X while something is there to interact with (out of combat), everything in a loading
screen, the Bind box's every place while it waits; (5) the padmap's `h` per place and `hold.on`; (6) the hold spells' cooldowns
(`holdcd`, real out of combat, learned in fights); (7) THE CONTROLLER ROOM: Y on a button = its hold chooser, A puts the spell as
the hold, its Clear asks, "hold-off" while unavailable, a finger on the hold chooser puts the hold too; (8) THE BIND BOX: the button held
(the marker at the press) places the spell as that button's hold spell (the ack's pad says hold), a short press binds as always; a
binding put on the Guide's name mid-session turns hold to cast off at once;
(9) no trace: no SetBinding / SetCVar / macro write, no blocked call, no snippet / handle error; the reset clears holdBy.
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
# the controller as this client numbers it (0-based, as C_GamePad gives it): the triggers 10 / 11, the Guide button (PADSYSTEM) 16
PADS = r'''
STANCE_AT = nil
PADNUM = { PADLTRIGGER = 10, PADRTRIGGER = 11, PADSYSTEM = 16, PAD1 = 0, PAD2 = 1, PAD3 = 2, PAD4 = 3 }
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
SPELLNAME[8042] = "Earth Shock"
-- the player's own bar (the slots that HAVE something: LT + X 17, LT + Y 18, LT + A 20; LT + B 19 and the rest empty)
ACTIONINFO = { [17] = { "spell", 403 }, [18] = { "spell", 331 }, [20] = { "spell", 403 } }
function GetActionInfo(s) local a = ACTIONINFO[s]; if a then return a[1], a[2] end end
function GetActionTexture(s) local a = ACTIONINFO[s]; return a and 130000 + a[2] or nil end
'''
AFTER = r'''
WowCompanion.spellsStorageFor = function(page, set, pos) return (page - 1) * 48 + (set - 1) * 12 + pos end
function HDR(k) return rawget(_G, "WowCompanionFaceRouter").a[k] end
function HB(name) return rawget(_G, name) end
-- a hardware press of [key] with the triggers / the marker held at it (the binding -> the router's click -> PRE), both edges; what the
-- router button decided at the key-down
function MPRESS(key, lt, rt, marker)
    PADSTATE = {}
    if lt then PADSTATE[LT_I] = true end
    if rt then PADSTATE[RT_I] = true end
    if marker then PADSTATE[PADNUM.PADSYSTEM + 1] = true end
    local a = BIND[key]
    local name = a and a:match("^CLICK ([%w_]+):")
    local b = name and rawget(_G, name)
    local a0 = #ACTIONS
    CLICKS = {}
    hwEdge(key, true)
    local kind, mt = b and b.a["wc-kind"], b and b.a["macrotext"]
    hwEdge(key, false)
    local log = CLICKS
    CLICKS = nil
    PADSTATE = {}
    NOW = NOW + 0.1
    local acts = {}
    for i = a0 + 1, #ACTIONS do acts[#acts + 1] = ACTIONS[i] end
    return name, kind, mt, acts, log
end
-- the probe's watch, frame by frame (its OnUpdate, as the game runs it)
function WATCH(s, step)
    local H = WowCompanion.padMapHold()
    local f = H.f
    local t = 0
    while t < s do
        if f and f.scripts.OnUpdate then f.scripts.OnUpdate(f, step) end
        NOW = NOW + step; t = t + step
    end
end
function HDB() local t = (WowCompanionSpellsDB.holdBy or {})[WHO .. "-" .. REALM]; return t end
-- the book the room's chooser lists (book order: Lightning Bolt, Healing Wave, Lightning Shield)
WowCompanion.spellsBindableList = function() local out = {}; for i, id in ipairs(BOOK) do out[#out + 1] = { id = id, slot = i } end; return out end
WowCompanion.spellsBookSlotOf = function(id) for i, x in ipairs(BOOK) do if x == id then return i end end end
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
    L.execute('NOW = NOW + 2; tick(0.1); advance(0.6)')
    return L


def last(L, typ, n0=0):
    d = docs(L, n0, typ)
    return d[-1] if d else {}


def press(L, key, lt=False, rt=False, marker=False):
    r = L.eval('MPRESS')(key, lt, rt, marker)
    name, kind, mt, acts, log = r
    acts = [dict(a) for a in (acts.values() if acts is not None else [])]
    return {'name': name, 'kind': kind, 'mt': mt, 'acts': acts, 'clicks': list(log.values()) if log is not None else []}


def macrolines(p):
    return [a.get('line') for a in p['acts'] if a.get('kind') == 'macroline']


# ---------------------------------------------------------------- (0) the tags
pm, ptc, bk = SRC['padmap'], SRC['padtabctl'], SRC['book']
ab = open(os.path.join(os.path.dirname(HERE), 'companion', 'app', 'src', 'main', 'assets', 'companion', 'addons', 'WowCompanion',
                       'ActionBar.lua'), encoding='utf-8').read()
check('local PV = "m192"' in pm and 'm190 HOLD TO CAST' in pm and 'm189 THE 1523 TEST BUILD MERGE' in pm, '0. PadMap m190 and its note (m189 kept)')
check('local CV = "p60"' in ptc and 'p60 THE HOLD SPELL BUTTON' in ptc and 'p59 HOLD TO CAST' in ptc and 'p58 THE CHOOSER AS A GRID' in ptc, '0. PadTabCtl p59 and its note')
check('local BV = "b80"' in bk and 'b80 THE 1529 TEST BUILD MERGE' in bk and 'b79 THE HOLD SPELL BUTTON BY TOUCH' in bk and 'P3[28] = function(pt) if type(pt) == "table" and type(pt.holdOpen) == "function" then pcall(pt.holdOpen) end end' in bk and 'b78 HOLD TO CAST IN THE BIND BOX' in bk and 'b77 THE CAST BAR' in bk, '0. Book b78 and its note')
check('local NB = { V = "nbar19"' in ab and 'nbar18 HOLD TO CAST' in ab, '0. ActionBar nbar18 and its note')

# ---------------------------------------------------------------- (1) THE PROBE
L = world()
check(not clean(L), '1. the world loads clean', clean(L))
ready = last(L, 'holdprobe')
check(ready.get('st') == 'ready' and ready.get('bound') is None and ready.get('v') == 'm192',
      '1. a few seconds after the login: "ready", nothing bound on the Guide\'s names (READ first)', ready)
H = L.eval('WowCompanion.padMapHold()')
check(H.w is not None and H.f is not None, '1. the watch is open (its frame polls the controller)')
check(L.eval('WowCompanionSpellsDB.holdGuide') is None and L.eval('HB("WowCompanionCombatFace6").a["wc-gd"]') is None,
      '1. before the pulse hold to cast is off (no marker number on the router)')
n0 = nsent(L)
# the app's pulse: the Guide button alone for 200 ms (its press event, the controller state)
L.execute('PADSTATE = { [17] = true }; fire("GAME_PAD_BUTTON_DOWN", "PADSYSTEM"); WATCH(0.2, 0.05); PADSTATE = {}; WATCH(2, 0.05)')
done = last(L, 'holdprobe', n0)
check(done.get('st') == 'done' and done.get('ev') == 'PADSYSTEM' and done.get('sn') == 'PADSYSTEM' and done.get('rn') == 'PADSYSTEM'
      and done.get('gb') is None and done.get('acted') is None,
      '1. "done": SEEN by the game\'s event, add-on code\'s state and the ROUTER\'s secure read; unbound; the game did nothing', done)
check(L.eval('WowCompanionSpellsDB.holdGuide') == 'PADSYSTEM', '1. hold to cast on: the Guide\'s name kept (db.holdGuide)')
check(L.eval('HB("WowCompanionCombatFace6").a["wc-gd"]') == 17 and L.eval('HB("WowCompanionCombatFace8").a["wc-gd"]') == 17,
      '1. every router button has the marker\'s number (16 + 1)', L.eval('HB("WowCompanionCombatFace6").a["wc-gd"]'))
check(L.eval('WowCompanion.padMapHold().w') is None and L.eval('WowCompanion.padMapHold().f.scripts.OnUpdate') is None,
      '1. the watch closed (no poll left running)')
check(len(lst(L, 'SPY')) == 0 and 'PADSYSTEM' not in str(lst(L, 'BIND')), '1. nothing cast, bound or set by the probe')

# a second world: nothing pulsed -> nothing changes (the app did not pulse: a /reload in a session already probed)
L2 = world('WowCompanionSpellsDB = { holdGuide = "PADSYSTEM" }')
n2 = nsent(L2)
L2.execute('WATCH(13, 0.1)')
d2 = last(L2, 'holdprobe', n2)
check(d2.get('st') == 'done' and d2.get('rn') is None and L2.eval('WowCompanionSpellsDB.holdGuide') == 'PADSYSTEM',
      '1. nothing seen in the watch: "done" says nothing, the kept answer stays', d2)

# a binding on the Guide's name (another add-on's override): never pulsed (no watch), hold to cast off
L3 = world('OTHER = {}; SetOverrideBinding(OTHER, false, "PADSYSTEM", "TOGGLEGAMEMENU"); WowCompanionSpellsDB = { holdGuide = "PADSYSTEM" }')
r3 = last(L3, 'holdprobe')
check(r3.get('st') == 'ready' and r3.get('bound') is True and r3.get('bn') == 'PADSYSTEM' and r3.get('ba') == 'TOGGLEGAMEMENU',
      '1. a binding on PADSYSTEM: "ready" says bound (the name, the binding\'s word)', r3)
check(L3.eval('WowCompanion.padMapHold().w') is None, '1. ... and NO watch opens (the app pulses nothing: HoldCast never arms)')
check(L3.eval('WowCompanionSpellsDB.holdGuide') is None, '1. ... hold to cast off (the kept answer dropped)')
check(L3.eval('(WowCompanion.padMap.putHold(1, 2, 6, "spell", 2))') is False, '1. ... a hold cannot be set (unavailable)')
check(last(L3, 'padack').get('why') == 'unavailable', '1. ... said: unavailable', last(L3, 'padack'))
# the player's emulated modifier on it
L4 = world('CVARS.GamePadEmulateShift = "PADSYSTEM"')
check(last(L4, 'holdprobe').get('bound') is True and last(L4, 'holdprobe').get('ba') == 'EMULATE',
      '1. the player\'s controller modifier on PADSYSTEM: bound (EMULATE), never pulsed', last(L4, 'holdprobe'))
# the game opening its menu at the pulse: "acted", off
L5 = world('mkwin("GameMenuFrame", false)')
n5 = nsent(L5)
L5.execute('PADSTATE = { [17] = true }; WATCH(0.1, 0.05); GameMenuFrame:Show(); WATCH(0.1, 0.05); PADSTATE = {}; WATCH(2, 0.05)')
d5 = last(L5, 'holdprobe', n5)
check(d5.get('acted') == 'menu' and L5.eval('WowCompanionSpellsDB.holdGuide') is None,
      '1. the game\'s menu opened at the pulse: "acted" (nothing undone), hold to cast off', d5)
# a login in a fight: the probe waits for its end; no secure call in the lockdown
L6 = world('COMBAT = true')
check(not any(d.get('type') == 'holdprobe' for d in docs(L6, 0)), '1. a login in a fight: no "ready" yet')
L6.execute('COMBAT = false; fire("PLAYER_REGEN_ENABLED"); runTimers(NOW + 6)')
check(last(L6, 'holdprobe').get('st') == 'ready' and not lst(L6, 'VIOL'), '1. ... said after the fight, nothing blocked', lst(L6, 'VIOL'))

# ---------------------------------------------------------------- (2) THE STORE
n0 = nsent(L)
check(L.eval('WowCompanion.padMap.putHold(1, 2, 6, "spell", 2)') is True, '2. LT + Y: Healing Wave (book slot 2) as the hold spell')
check(L.eval('WowCompanion.padMap.putHold(1, 1, 8, "spell", 1)') is True, '2. the bare A: Lightning Bolt')
check(L.eval('WowCompanion.padMap.putHold(1, 1, 7, "spell", 3)') is True, '2. B: Lightning Shield (every button incl. B)')
hdb = L.eval('HDB()')
check(hdb is not None and hdb[18] is not None and hdb[18].n == 'Healing Wave' and hdb['f8'].n == 'Lightning Bolt'
      and hdb['f7'].n == 'Lightning Shield', '2. stored per character: slot 18, the base faces by position (f8 / f7)')
acks = [d for d in docs(L, n0, 'padack') if d.get('op') == 'hold']
check(len(acks) == 3 and all(a.get('ok') for a in acks) and acks[0].get('s') == 18, '2. each answered (padack hold, its slot)', acks)
check((L.eval('HDR("wc-ht-18")') or '').endswith('/cast Healing Wave') and (L.eval('HDR("wc-hf-8")') or '').endswith('/cast Lightning Bolt')
      and (L.eval('HDR("wc-hf-7")') or '').endswith('/cast Lightning Shield'),
      '2. the header\'s hold texts (wc-ht-<slot>, wc-hf-<pos>)', (L.eval('HDR("wc-ht-18")'), L.eval('HDR("wc-hf-8")')))
check(L.eval('BIND.PAD1') == 'CLICK WowCompanionCombatFace8:LeftButton' and L.eval('BIND.PAD4') == 'CLICK WowCompanionCombatFace6:LeftButton'
      and L.eval('BIND.PAD2') == 'CLICK WowCompanionFaceB:LeftButton',
      '2. out of combat the router carries A, Y and B (their places have a hold spell)', (L.eval('BIND.PAD1'), L.eval('BIND.PAD4'), L.eval('BIND.PAD2')))
check(L.eval('(WowCompanion.padMap.putHold(1, 2, 5, "bind", "JUMP"))') is False and last(L, 'padack').get('why') == 'bind',
      '2. a game binding is no hold (refused: bind)', last(L, 'padack'))
L.execute('COMBAT = true')
check(L.eval('(WowCompanion.padMap.putHold(1, 2, 5, "spell", 1))') is False and last(L, 'padack').get('why') == 'combat',
      '2. in a fight: refused, nothing changed', last(L, 'padack'))
L.execute('COMBAT = false')
other = world('WowCompanionSpellsDB = { holdGuide = "PADSYSTEM" }; WHO = "Brannoc"')
check(other.eval('HDB()') is None or other.eval('HDB()[18]') is None, '2. another character has none of them')

# ---------------------------------------------------------------- (3) THE ROUTER
p = press(L, 'PAD4', lt=True, marker=True)
check(p['kind'] == 'slothold' and p['mt'].endswith('/cast Healing Wave') and '/cast Healing Wave' in macrolines(p)
      and not any(c.startswith('GPLeft6') for c in p['clicks']),
      '3. LT + Y held (the marker at the press): the hold spell, Healing Wave - the slot\'s own button not clicked', p)
p = press(L, 'PAD4', lt=True)
check(p['kind'] != 'slothold' and 'GPLeft6+' in p['clicks'], '3. LT + Y tapped (no marker): the slot\'s own button (GPLeft6)', p)
p = press(L, 'PAD4', marker=True)
check(p['kind'] != 'slothold', '3. the marker on a place with no hold spell (the bare Y): the press as always', p)
p = press(L, 'PAD1', marker=True)
check(p['kind'] == 'slothold' and p['mt'].endswith('/cast Lightning Bolt'), '3. the bare A held: its hold spell (Lightning Bolt)', p)
p = press(L, 'PAD1')
check(p['kind'] == 'native' and 'GPTop8+' in p['clicks'] and 'GPTop8-' in p['clicks'],
      '3. the bare A tapped: the game\'s own A button (Jump), down and up', p)
fc = [d for d in docs(L, 0, 'facecast') if d.get('kind') == 'slothold' and d.get('down')]
check(fc and fc[-1].get('path') == 'slothold', '3. the press log says slothold', fc[-1:] if fc else None)
# room mode: the room's press
L.execute('local b = HB("WowCompanionCombatFace6"); b.a["wc-mode"] = "room"; b.a["wc-room-dest"] = "GPTop5"')
p = press(L, 'PAD4', lt=True, marker=True)
check(p['kind'] != 'slothold', '3. room mode: the marker is ignored (the room\'s press)', p)
L.execute('local b = HB("WowCompanionCombatFace6"); b.a["wc-mode"] = nil; b.a["wc-room-dest"] = nil')
# in a fight: the hardware presses (the fight's carried keys), B's hold without the give-back
v0 = len(lst(L, 'VIOL'))
L.execute('fire("PLAYER_REGEN_DISABLED"); COMBAT = true; driveStates(); advance(1)')
L.execute('for _, n in ipairs({ "WowCompanionCombatFace5", "WowCompanionCombatFace6", "WowCompanionCombatFace8", "WowCompanionFaceB" }) do local b = HB(n); if b then b.a["wc-state"] = "fight" end end')
p = press(L, 'PAD4', lt=True, marker=True)
check(p['kind'] == 'slothold' and p['mt'].endswith('/cast Healing Wave'), '3. in a fight: LT + Y held = Healing Wave', p)
given0 = L.eval('HB("WowCompanionCombatFace8").a["wc-given"]')
p = press(L, 'PAD2', marker=True)
check(p['kind'] == 'slothold' and p['mt'].endswith('/cast Lightning Shield') and L.eval('HB("WowCompanionCombatFace8").a["wc-given"]') == given0,
      '3. B held in a fight: Lightning Shield alone (A / X / Y not given back)', p)
p = press(L, 'PAD2')
check(p['kind'] != 'slothold', '3. B tapped in a fight: B as always (the give-back)', p)
check(len(lst(L, 'VIOL')) == v0 and not lst(L, 'SNIPERR') and not lst(L, 'HANDLEERR'),
      '3. no blocked call, no snippet / handle error in the fight', (lst(L, 'VIOL')[v0:], lst(L, 'SNIPERR'), lst(L, 'HANDLEERR')))
L.execute('COMBAT = false; driveStates(); fire("PLAYER_REGEN_ENABLED"); advance(1)')
L.execute('for _, n in ipairs({ "WowCompanionCombatFace5", "WowCompanionCombatFace6", "WowCompanionCombatFace8", "WowCompanionFaceB" }) do local b = HB(n); if b then b.a["wc-state"] = "idle"; b.a["wc-given"] = nil end end')
check('trs = "L" .. (li and (lt and "1" or "0") or "-")' in pm and 'if gd and bt and bt[gd] and dest and mode ~= "room" and not pdest then' in pm,
      '3. the layer is the triggers at the press (the app sends them as held then: HoldGate), the marker read the same way')

# ---------------------------------------------------------------- (4) THE APP'S MAP
L.execute('WowCompanion.padMapHold().tick(true)')
m = last(L, 'holdmap')
places = set((m.get('b') or '').split(','))
check(m.get('on') is True and {'lt:6', 'bare:8', 'bare:7'} <= places and 'bare:6' not in places and 'lt:8' not in places,
      '4. the places the router turns into a hold now (LT + Y, the bare A and B), none other', m)
L.execute('MOUNTED = true; WowCompanion.padMapHold().tick(true)')
check('bare:8' not in (last(L, 'holdmap').get('b') or ''), '4. mounted: the bare A left out (Jump / ascend is held there)', last(L, 'holdmap'))
L.execute('MOUNTED = false')
check(L.eval('WowCompanion.padMap.putHold(1, 1, 5, "spell", 2)') is True, '4. (the bare X: Healing Wave)')
L.execute('UNITS.softinteract = { name = "Mailbox", alive = true }; WowCompanion.padMapHold().tick(true)')
check('bare:5' not in (last(L, 'holdmap').get('b') or '') and 'lt:6' in (last(L, 'holdmap').get('b') or ''),
      '4. out of combat with something to interact with: the bare X left out (the game\'s Interact and its hold)', last(L, 'holdmap'))
L.execute('UNITS.softinteract = nil; WowCompanion.padMapHold().tick(true)')
check('bare:5' in (last(L, 'holdmap').get('b') or ''), '4. ... nothing there: the bare X held back again', last(L, 'holdmap'))
L.execute('fire("LOADING_SCREEN_ENABLED")')
check(last(L, 'holdmap').get('on') is None and last(L, 'holdmap').get('why') == 'loading', '4. a loading screen: nothing held back', last(L, 'holdmap'))
L.execute('fire("LOADING_SCREEN_DISABLED")')
check(last(L, 'holdmap').get('on') is True, '4. ... back after it')
n0 = nsent(L)
L.execute('WowCompanion.padMapHold().tick(); WowCompanion.padMapHold().tick()')
check(not docs(L, n0, 'holdmap'), '4. sent only when it changes')

# ---------------------------------------------------------------- (5) the padmap
L.execute('WowCompanion.padMap.emit(true)')
doc = last(L, 'padmap')['m']
s26 = doc['sets'][1]['slots'][5]
s18 = doc['sets'][0]['slots'][7]
check(s26.get('h', {}).get('n') == 'Healing Wave' and s26['h'].get('id') == 331 and s18.get('h', {}).get('n') == 'Lightning Bolt'
      and doc.get('hold', {}).get('on') is True, '5. each place\'s hold (h: its name, id, icon), the base A\'s too; hold.on', (s26.get('h'), s18.get('h'), doc.get('hold')))
check(L.eval('WowCompanion.padMap.slotAt(1, 2, 6).h.n') == 'Healing Wave', '5. slotAt carries it (the room, the top bar)')

# ---------------------------------------------------------------- (6) the hold spells' cooldowns
n0 = nsent(L)
L.execute('CD[331] = { isActive = true, isOnGCD = false, duration = 8, startTime = NOW }; fire("SPELL_UPDATE_COOLDOWN")')
cd = last(L, 'holdcd', n0)
k = (cd.get('k') or {}).get('lt:6') or {}
check(k.get('d') == 8 and 7.9 <= k.get('l', 0) <= 8 and not k.get('e'), '6. out of combat: the real length (8 s) on LT + Y\'s hold', cd)
check(L.eval('WowCompanionSpellsDB.faceCd["Healing Wave"]') == 8, '6. ... learned (the fight faces\' table)')
L.execute('CD[331] = nil; NOW = NOW + 9; fire("SPELL_UPDATE_COOLDOWN")')
check(not last(L, 'holdcd').get('k'), '6. run out: none', last(L, 'holdcd'))
L.execute('COMBAT = true; CD[331] = { isActive = true, isOnGCD = false, duration = SECRETV, startTime = SECRETV }; fire("SPELL_UPDATE_COOLDOWN")')
k = (last(L, 'holdcd').get('k') or {}).get('lt:6') or {}
check(k.get('d') == 8 and k.get('e') is True, '6. in a fight (secret times): the learned 8 s, said as an estimate', last(L, 'holdcd'))
L.execute('COMBAT = false; CD[331] = nil; NOW = NOW + 9; fire("SPELL_UPDATE_COOLDOWN")')

# ---------------------------------------------------------------- (7) THE CONTROLLER ROOM
L.execute('WowCompanion.padMap.clearHold(1, 1, 8)')
check(L.eval('HDB()["f8"]') is None, '7. (the bare A has no hold now)')
L.execute('local ctl = WowCompanion._spellsBook.ctl; ctl.croom, ctl.ctrlTab, ctl.onTabs = true, true, true; WowCompanion.padTabCtl.enter()')


def ctl(L):
    return last(L, 'padtabctl').get('c') or {}


for _ in range(6):
    if (ctl(L).get('pos') or 0) == 8:
        break
    L.execute('WowCompanion.padTabCtl.press("PADDRIGHT")')
c = ctl(L)
check(c.get('on') and c.get('focus') == 'buttons' and c.get('pos') == 8, '7. the room open, the cursor walked to the base A', c)
L.execute('WowCompanion.padTabCtl.press("PAD4")')
c = ctl(L)
check((c.get('chooser') or {}).get('hold') is True and (c.get('chooser') or {}).get('id') == 403, "7. Y on a button: its HOLD chooser (the book's spells)", c.get('chooser'))
L.execute('WowCompanion.padTabCtl.press("PAD1")')                 # the first spell row (Lightning Bolt) as the A's hold
check(L.eval('HDB()["f8"].n') == 'Lightning Bolt' and last(L, 'padack').get('op') == 'hold', '7. A on a row: put as the hold spell (putHold)')
check(ctl(L).get('chooser') is None, '7. ... the chooser closed')
L.execute('WowCompanion.padTabCtl.press("PAD4")')
rows = L.eval('(function() local n = 0; for i = 1, 40 do n = i end; return n end)()')
# walk to the pinned Clear (the last row) and press it: the Clear box asks first
L.execute('for i = 1, 12 do WowCompanion.padTabCtl.press("PADDDOWN") end')
L.execute('WowCompanion.padTabCtl.press("PAD1")')
c = ctl(L)
check((c.get('box') or {}).get('kind') == 'clear' and (c.get('box') or {}).get('thing') == 'unhold', '7. its Clear asks first', c.get('box'))
L.execute('WowCompanion.padTabCtl.press("PAD1")')
check(L.eval('HDB()["f8"]') is None and last(L, 'padack').get('op') == 'unhold', '7. ... yes: the hold taken off (clearHold)')
# a finger on the hold chooser puts the hold too
L.execute('WowCompanion.padTabCtl.press("PAD4"); WowCompanion.padTabCtl.touchSpell(403)')
check(L.eval('HDB()["f8"].n') == 'Lightning Bolt', '7. a finger on a spell of the hold chooser: the hold (never the button\'s own)')
# p60: the app's "Hold spell" button by touch (Book b79's code 26) = Y on the button the cursor is on
L.execute('WowCompanion.padMapHold(); WowCompanion.padTabCtl.press("PAD2")')
L.execute('WowCompanion.padTabCtl.holdOpen()')
check((ctl(L).get('chooser') or {}).get('hold') is True, '7. the Hold spell button by touch (holdOpen): the same hold chooser', ctl(L).get('chooser'))
L.execute('WowCompanion.padTabCtl.press("PAD2"); WowCompanion.padTabCtl.leave()')
L3.execute('local ctl = WowCompanion._spellsBook.ctl; ctl.croom, ctl.ctrlTab, ctl.onTabs = true, true, true; WowCompanion.padTabCtl.enter(); WowCompanion.padTabCtl.press("PAD4")')
check(ctl(L3).get('note') == 'hold-off' and ctl(L3).get('chooser') is None, '7. unavailable: Y says hold-off, nothing opens', ctl(L3))

# ---------------------------------------------------------------- (8) THE BIND BOX
L.execute('BK = WowCompanion._spellsBook; BK.ctl.on = true; BK.ctl.pg = "book"')
L.execute('C_GamepadUI = { GetFirstGamepadActionBarStorageSlotIndexForActiveStance = function() return 0 end, IsValidGamepadActionStorageSlotIndex = function() return true end }')
n0 = nsent(L)
L.execute('BK.startBind({ t = 1, slot = 1, id = 403 }); WowCompanion.padMapHold().tick(true)')
mb = last(L, 'holdmap', n0)
check(mb.get('why') == 'box' and 'ltrt:6' in (mb.get('b') or '') and 'bare:7' not in (mb.get('b') or ''),
      '8. the Bind box waiting: every place held back (the plain B is its way out)', mb)
L.execute('PADSTATE = { [LT_I] = true, [17] = true }; BK.press("PAD3"); PADSTATE = {}')
acks = [d for d in docs(L, n0, 'spellsack') if d.get('op') == 'bind']
a = acks[-1] if acks else {}
check(a.get('ok') is True and (a.get('pad') or {}).get('hold') is True and (a.get('pad') or {}).get('b') == 2 and (a.get('pad') or {}).get('p') == 5,
      '8. LT + X HELD (the marker at the press): placed as LT + X\'s hold spell (the ack\'s pad says hold)', a)
check(L.eval('HDB()[17].n') == 'Lightning Bolt', '8. ... stored as the hold (slot 17), the bar untouched')
check(not any(x == 'PlaceAction' for x in lst(L, 'SPY')), '8. ... no PlaceAction (nothing of the game\'s bar changed)')
L.execute('BK.ctl.mode = "book"; BK.ctl.bind = nil')

# a binding put on the Guide's name later in the session (the player, another add-on): hold to cast off at once
L7 = world('WowCompanionSpellsDB = { holdGuide = "PADSYSTEM" }')
L7.execute('WowCompanion.padMap.putHold(1, 2, 6, "spell", 2); WowCompanion.padMapHold().tick(true)')
check(last(L7, 'holdmap').get('on') is True, '8b. (a hold set, the map on)')
L7.execute('OTHER = {}; SetOverrideBinding(OTHER, false, "PADSYSTEM", "TOGGLEGAMEMENU"); WowCompanion.padMapHold().tick()')
check(last(L7, 'holdmap').get('why') == 'bound' and last(L7, 'holdmap').get('on') is None and L7.eval('WowCompanionSpellsDB.holdGuide') is None
      and L7.eval('HB("WowCompanionCombatFace6").a["wc-gd"]') is None,
      '8b. a binding put on PADSYSTEM mid-session: the map off at once ("bound"), the kept answer and the marker number dropped', last(L7, 'holdmap'))

# ---------------------------------------------------------------- (10) A HOLD ONLY ON A BUTTON THAT HAS SOMETHING (the user, 2026-11-24:
# "You should only be able to bind a hold on a button that has something already")
check(L.eval('(WowCompanion.padMap.putHold(1, 2, 7, "spell", 2))') is False and last(L, 'padack').get('why') == 'empty',
      '10. an empty button (LT + B, slot 19): no hold (refused: empty)', last(L, 'padack'))
check(L.eval('(WowCompanion.padMap.putHold(1, 1, 7, "spell", 3))') is True, '10. a base face (B: the game own Back) counts as something')
check('if not (pm and type(pm.holdOk) == "function" and pm.holdOk(pm.page(), shownSet(), tc.pos)) then report("hold-empty") return true end' in ptc,
      '10. the room: Y on an empty button says hold-empty, nothing opens')
check(L.eval('WowCompanion.padMap.holdOk(1, 2, 7)') is False and L.eval('WowCompanion.padMap.holdOk(1, 2, 6)') is True
      and L.eval('WowCompanion.padMap.holdOk(1, 1, 8)') is True, '10. holdOk: empty no, a spell yes, a base face yes')
# the press taken off (here: the game's own bar emptied; Clear / Move do the same): its hold goes too
check(L.eval('WowCompanion.padMap.holdAt(1, 2, 6) ~= nil'), '10. (LT + Y has its hold)')
L.execute('ACTIONINFO[18] = nil; fire("ACTIONBAR_SLOT_CHANGED", 18); WowCompanion.padMapHold().tick(); advance(1)')
check(L.eval('HDB()[18]') is None and L.eval('HDR("wc-ht-18")') is None, '10. the press gone: its hold dropped (store and router)',
      (L.eval('HDB()[18]'), L.eval('HDR("wc-ht-18")')))
L.execute('ACTIONINFO[18] = { "spell", 331 }')
# an orphan saved before this rule (a hold on an empty slot): dropped at the login
L9 = world('WowCompanionSpellsDB = { holdGuide = "PADSYSTEM", holdBy = { ["Korvash-Realm"] = { [19] = { id = 331, n = "Healing Wave", i = 1, page = 1, set = 2, pos = 7 }, [18] = { id = 1064, n = "Chain Heal", i = 1, page = 1, set = 2, pos = 6 } } } }')
L9.execute('WowCompanion.padMapHold().tick(); advance(1)')
check(L9.eval('HDB()[19]') is None and L9.eval('HDB()[18] ~= nil'), '10. a saved orphan (empty slot 19) dropped at the login; slot 18 (a spell) kept')
# the Bind box: HOLDING an empty button places the spell as its normal press (never a hold on nothing)
LB = world('WowCompanionSpellsDB = { holdGuide = "PADSYSTEM" }; C_GamepadUI = { GetFirstGamepadActionBarStorageSlotIndexForActiveStance = function() return 0 end, IsValidGamepadActionStorageSlotIndex = function() return true end }; CURSOR = nil; function GetCursorInfo() if CURSOR then return CURSOR[1], CURSOR[2] end end; function ClearCursor() CURSOR = nil end; C_SpellBook.PickupSpellBookItem = function(slot) local id = BOOK[slot]; if id then CURSOR = { "spell", id } end end')
LB.execute('BK = WowCompanion._spellsBook; BK.ctl.on = true; BK.ctl.pg = "book"; BK.startBind({ t = 1, slot = 1, id = 403 })')
nb = nsent(LB)
LB.execute('PADSTATE = { [LT_I] = true, [17] = true }; BK.press("PADDLEFT"); PADSTATE = {}')
ab = [d for d in docs(LB, nb, 'spellsack') if d.get('op') == 'bind']
ab = ab[-1] if ab else {}
# (this stub game has no cursor road: the normal bind's own pick-up answers "failed" - what matters is the road it took)
check(ab.get('op') == 'bind' and not (ab.get('pad') or {}).get('hold') and (ab.get('probe') or {}).get('slotSrc') == 'formula'
      and (ab.get('probe') or {}).get('pad') == 2 and LB.eval('HDB() == nil or HDB()[13] == nil'),
      '10. Bind box: LT + D-pad Left (empty) HELD = the normal bind road (the bar slot), never a hold', ab)

# ---------------------------------------------------------------- (9) no trace
check(not lst(L, 'SPY'), '9. no SetBinding / SetCVar / macro write / game window call', lst(L, 'SPY'))
check(not lst(L, 'SNIPERR') and not lst(L, 'HANDLEERR') and not lst(L, 'ERRS'), '9. no snippet / handle / event error',
      (lst(L, 'SNIPERR'), lst(L, 'HANDLEERR'), lst(L, 'ERRS')))
check('"castBar", "castBarFight", "castBarParty", "holdBy" }' in pm, '9. Settings > Reset skills clears the hold spells (holdBy)')
check('SetBinding(' not in pm.split('m190 HOLD TO CAST, THE PROBE', 1)[1].split('WC.padMapHold', 1)[0],
      '9. the probe and the map write no binding (read only)')

print('%d passed, %d failed' % (passes, fails))
sys.exit(1 if fails else 0)
