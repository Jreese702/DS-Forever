"""THE LT / RT MACRO FIXES (PadMap m188, ActionBar nbar13), off-device: the REAL add-on files (Safety, TapPad, Map, Quests, Hub,
Interact + Dialogs, Book, PadMap, PadTabCtl) under tools/wowstub.py's game with STRICT laid over it, and test_ltrt_stancebar.py's
bars (every gamepad bar's buttons as named globals; the stance bar laid nowhere here).

    python -B tools/test_ltrt_macro_over.py

The tester, 2026-11-23: "couldn't use any macros on LT or RT with a face button, kept saying invalid target or cant cast when
moving, flicking the class wheel options around seemed to fix that". The user's picks: "Re-read the controller", "Say what was
pressed", "Show what's on top" (none change behaviour), and "In combat wins" (the fight order is kept: just shown).

Proves: (0) the tags and their notes; (1) RE-READ THE CONTROLLER: the triggers' button numbers change (another pad) - with no event
the router still reads the old ones (LT + Y is the bare press: the control), GAME_PAD_ACTIVE_CHANGED wires them again (LT + Y = the
LT page), the same numbers again wire nothing, GAME_PAD_CONNECTED always wires, GAME_PAD_CONFIGS_CHANGED too; a trigger the game has
no number for says "-"; Party mode's pad takes the new numbers too; in a fight nothing secure is touched and it is wired at
PLAYER_REGEN_ENABLED; ActionBar's held-layer numbers (NB.idx) are dropped on those events; (2) SAY WHAT WAS PRESSED: each routed
key-down's facecast carries tr / path / om - out of combat the player's macro on LT + Y is "slotmacro", in a fight the In combat
entry over it is "slotfight" at the LT page's slot with om (over the macro), its order unchanged; our command over a macro on RT + Y
is path "command" with om; a spell slot with an In combat entry: om absent; (3) SHOW WHAT'S ON TOP: the padmap's combat view says
um over a macro (not over a spell), the command view says um over a macro; (4) no trace: no SetCVar / SetBinding / macro write, no
blocked call, no snippet / handle error.
Needs lupa. Exit code 1 on any failure."""
import re
import sys

from wowstub import SRC, boot_world, docs, nsent

fails = passes = 0


def check(ok, name, got=''):
    global fails, passes
    print(('PASS ' if ok else 'FAIL ') + name + ((' -> ' + str(got)) if got != '' and not ok else ''))
    if ok:
        passes += 1
    else:
        fails += 1


BASE = open(__file__.replace('test_ltrt_macro_over.py', 'test_ltrt_stancebar.py'), encoding='utf-8').read()
BARS = re.search(r"\nPRE = r'''(.*?)'''", BASE, re.S).group(1)
# the bar's slots: 18 = LT + Y (GPLeft6), 30 = RT + Y (GPRight6), 42 = LT + RT + Y (GPBottom6); the player's own macros by name
MAC = r'''
STANCE_AT = nil
ACTIONINFO, BARTEXT = {}, {}
MACS = { [121] = { "MyHeal", 136243, "/cast [@mouseover,help] Heal" }, [122] = { "MyBuff", 136244, "/cast Power Word: Fortitude" } }
function GetActionInfo(s) local a = ACTIONINFO[s]; if a then return a[1], a[2] end end
function GetActionText(s) return BARTEXT[s] end
function GetActionTexture(s) local a = ACTIONINFO[s]; return a and 130000 + (a[2] or 0) or nil end
function GetMacroIndexByName(n) for i, m in pairs(MACS) do if m[1] == n then return i end end return 0 end
function GetMacroInfo(i) local m = MACS[i]; if m then return m[1], m[2], m[3] end end
ACTIONINFO[18] = { "macro", 121 }; BARTEXT[18] = "MyHeal"
ACTIONINFO[30] = { "macro", 122 }; BARTEXT[30] = "MyBuff"
ACTIONINFO[42] = { "spell", 331 }
-- the triggers' button numbers as the game gives them (C_GamePad.ButtonBindingToIndex, 0-based); PADNUM changes them
PADNUM = { PADLTRIGGER = 10, PADRTRIGGER = 11 }
C_GamePad.ButtonBindingToIndex = function(n) return PADNUM[n] end
'''
AFTER = r'''
WowCompanion.spellsStorageFor = function(page, set, pos) return (page - 1) * 48 + (set - 1) * 12 + pos end
local k = WowCompanion.charKey()
WowCompanionSpellsDB.fightBy = WowCompanionSpellsDB.fightBy or {}
WowCompanionSpellsDB.fightBy[k] = { [18] = { id = 331, n = "Healing Wave", i = 136052, page = 1, set = 2, pos = 6 },
                                    [42] = { id = 8004, n = "Lesser Healing Wave", i = 136043, page = 1, set = 4, pos = 6 } }
WowCompanionSpellsDB.cmdBy = WowCompanionSpellsDB.cmdBy or {}
WowCompanionSpellsDB.cmdBy[k] = { [30] = { k = "TARGETNEARESTENEMY", pos = 6 } }
fire("ACTIONBAR_SLOT_CHANGED", 18); fire("UPDATE_MACROS")
'''

L, errs = boot_world(pre=BARS + MAC)
L.execute('LOADERRS = {}')
for e in errs:
    L.eval('function(s) LOADERRS[#LOADERRS + 1] = s end')(e)
L.execute('__IN.ACT.trust(); advance(1); __IN.N.setMode(true); advance(1)')
L.execute(AFTER)


def settle():
    L.execute('NOW = NOW + 2; tick(0.1); advance(0.6); NOW = NOW + 2; tick(0.1); advance(0.6)')


settle()


def lst(expr):
    t = L.eval(expr)
    return [] if t is None else list(t.values())


def clean():
    return {k: v for k, v in (("VIOL", lst('VIOL')), ("SPY", lst('SPY')), ("HANDLEERR", lst('HANDLEERR')),
                              ("SNIPERR", lst('SNIPERR')), ("ERRS", lst('ERRS')), ("LOADERRS", lst('LOADERRS'))) if v}


YB = "WowCompanionCombatFace6"
# a press with the controller's state as the game now numbers it: LT / RT held at the CURRENT numbers (PADNUM + 1)
CLICK_Y = L.eval('''function(lt, rt)
    PADSTATE = {}
    if lt and PADNUM.PADLTRIGGER then PADSTATE[PADNUM.PADLTRIGGER + 1] = true end
    if rt and PADNUM.PADRTRIGGER then PADSTATE[PADNUM.PADRTRIGGER + 1] = true end
    local b = rawget(_G, "%s")
    doClick(b, "LeftButton", true); doClick(b, "LeftButton", false)
    PADSTATE = {}
    NOW = NOW + 0.1
end''' % YB)
HW_Y = L.eval('''function(lt, rt)
    LT_I = (PADNUM.PADLTRIGGER or -9) + 1; RT_I = (PADNUM.PADRTRIGGER or -9) + 1
    return hwPress("PAD4", lt, rt)
end''')


def press(lt=False, rt=False, hw=False):
    a0, n0 = int(L.eval('#ACTIONS')), nsent(L)
    (HW_Y if hw else CLICK_Y)(lt, rt)
    L.execute('advance(0.2)')
    acts = [dict(L.eval('ACTIONS[%d]' % i)) for i in range(a0 + 1, int(L.eval('#ACTIONS')) + 1)]
    bars = [a.get('btn') for a in acts if a.get('kind') == 'bar' and a.get('down')]
    fc = [x for x in docs(L, n0, 'facecast') if x.get('down')]
    return bars, (fc[-1] if fc else {})


def attr(k):
    b = L.eval('rawget(_G, "%s")' % YB)
    return None if b is None else b.a[k]


def slot_doc(set_, pos):
    n0 = nsent(L)
    L.execute('WowCompanion.padMap.emit(true); advance(1)')
    m = docs(L, n0, 'padmap')[-1]['m']
    return m['sets'][set_ - 1]['slots'][pos - 1]


# ---------------------------------------------------------------- (0) the tags
import os
pm = SRC["padmap"]
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ab = open(os.path.join(ROOT, "companion", "app", "src", "main", "assets", "companion", "addons", "WowCompanion", "ActionBar.lua"), encoding="utf-8").read()
pv = int(pm.split('local PV = "m', 1)[1].split('"', 1)[0])
check(pv >= 188 and "m188 THE LT / RT MACRO FIXES" in pm and "m187 THE ROUTER FOLLOWS THE GAME'S STANCE / POSSESS BAR" in pm,
      "0. PadMap m188 and its note (m187's kept)", pv)
check('V = "nbar22"' in ab and "nbar13 THE CONTROLLER CHANGED" in ab and "nbar12 THE WINGS' PLATES AND PARTY MODE" in ab, "0. ActionBar nbar13 and its note")
check(all(e in pm for e in ("GAME_PAD_CONNECTED", "GAME_PAD_DISCONNECTED", "GAME_PAD_ACTIVE_CHANGED", "GAME_PAD_CONFIGS_CHANGED")),
      "0. the game's controller events (GamePadDocumentation.lua's names) are listened to")
check(not clean(), "0. the world loads clean", clean())
check(attr('wc-lt') == 11 and attr('wc-rt') == 12, "0. wired with the game's numbers (0-based 10 / 11 -> 11 / 12)", (attr('wc-lt'), attr('wc-rt')))

# ---------------------------------------------------------------- (1) re-read the controller
bars, d = press(lt=True)
check(bars == ['GPLeft6'] or d.get('kind') == 'slotmacro', "1. LT + Y with the numbers wired: the LT page's slot (its macro)", (bars, d))
check(d.get('lay') == 'lt' and d.get('tr') == 'L1R0', "1. said: lt, the triggers L1R0", d)

L.execute('PADNUM = { PADLTRIGGER = 14, PADRTRIGGER = 15 }')        # another pad is the active one: other numbers
bars, d = press(lt=True)
check(d.get('lay') == 'bare' and d.get('tr') == 'L0R0', "1. the numbers changed, no event: LT read as not held - the bare press (the old build's case)", d)

L.execute('fire("GAME_PAD_ACTIVE_CHANGED", true); advance(1)')
check(attr('wc-lt') == 15 and attr('wc-rt') == 16, "1. GAME_PAD_ACTIVE_CHANGED with new numbers: wired again (15 / 16)", (attr('wc-lt'), attr('wc-rt')))
bars, d = press(lt=True)
check(d.get('lay') == 'lt' and d.get('tr') == 'L1R0' and d.get('slot') == 18, "1. LT + Y now: the LT page's slot 18, L1R0", d)

b = L.eval('rawget(_G, "%s")' % YB)
b.a['wc-lt'] = 99                                                    # a marker: does the next event wire again?
L.execute('fire("GAME_PAD_ACTIVE_CHANGED", false); advance(1)')
check(attr('wc-lt') == 99, "1. GAME_PAD_ACTIVE_CHANGED with the same numbers (every mouse <-> pad switch): nothing wired", attr('wc-lt'))
# m188 review: before the first wiring (R.trWired nil) a mouse <-> pad switch runs no re-wire / Party link either (R is a chunk local)
check('if e == "GAME_PAD_ACTIVE_CHANGED" and (R.trWired == nil or R.trWired == R.trNow()) then return false end' in pm,
      "1. GAME_PAD_ACTIVE_CHANGED before anything was wired: no re-wire (source)")
L.execute('fire("GAME_PAD_CONNECTED"); advance(1)')
check(attr('wc-lt') == 15, "1. GAME_PAD_CONNECTED: always wired again", attr('wc-lt'))
b.a['wc-lt'] = 99
L.execute('fire("GAME_PAD_CONFIGS_CHANGED"); advance(1)')
check(attr('wc-lt') == 15, "1. GAME_PAD_CONFIGS_CHANGED: wired again", attr('wc-lt'))

pad = L.eval('(function() for k, v in pairs(_G) do end; return rawget(_G, "WowCompanionPartyPad") end)()')
padname = None
m = re.search(r'PM\.PAD = "([A-Za-z0-9_]+)"', pm)
if m:
    padname = m.group(1)
    pad = L.eval('rawget(_G, "%s")' % padname)
check(pad is None or pad.a['wc-lt'] == 15, "1. Party mode's pad has the new numbers too (PM.link)", None if pad is None else pad.a['wc-lt'])

L.execute('PADNUM = { PADRTRIGGER = 15 }; fire("GAME_PAD_DISCONNECTED"); advance(1)')
bars, d = press(lt=True)
check(attr('wc-lt') is None and d.get('tr') == 'L-R0' and d.get('lay') == 'bare', "1. no number for LT: said 'L-' (the press is the bare one)", (attr('wc-lt'), d))
L.execute('PADNUM = { PADLTRIGGER = 14, PADRTRIGGER = 15 }; fire("GAME_PAD_CONNECTED"); advance(1)')

# in a fight: queued
v0 = len(lst('VIOL'))
L.execute('fire("PLAYER_REGEN_DISABLED"); COMBAT = true; advance(1)')
L.execute('PADNUM = { PADLTRIGGER = 10, PADRTRIGGER = 11 }; fire("GAME_PAD_CONNECTED"); advance(1)')
check(len(lst('VIOL')) == v0, "1. in a fight: nothing secure touched (no blocked call)", lst('VIOL')[v0:])
check(attr('wc-lt') == 15, "1. in a fight: the wiring waits", attr('wc-lt'))
L.execute('COMBAT = false; fire("PLAYER_REGEN_ENABLED"); advance(1)')
check(attr('wc-lt') == 11 and attr('wc-rt') == 12, "1. at the fight's end (PLAYER_REGEN_ENABLED): wired with the new numbers", (attr('wc-lt'), attr('wc-rt')))
settle()

# ActionBar's held-layer numbers
nb = L.eval('WowCompanion.nbar')
if nb is not None:
    L.execute('if WowCompanion.nbar.idx == nil then WowCompanion.nbar.idx = {} end; WowCompanion.nbar.idx.PADLTRIGGER = 77')
    f = nb.f
    if f is not None:
        L.execute('fire("GAME_PAD_ACTIVE_CHANGED", true); advance(0.2)')
        check(nb.idx is None, "1. ActionBar: NB.idx dropped on the controller event (looked up again)", nb.idx)
    else:
        L.execute('WowCompanion.nbar.idx = nil')
        check('if NB.PAD_SET[e] then NB.idx = nil; return end' in ab, "1. ActionBar: NB.idx dropped on the controller events (source; the bar is not built here)")
else:
    check('if NB.PAD_SET[e] then NB.idx = nil; return end' in ab, "1. ActionBar: NB.idx dropped on the controller events (source)")

# ---------------------------------------------------------------- (2) say what was pressed
bars, d = press(lt=True)
check(d.get('kind') == 'slotmacro' and d.get('path') == 'slotmacro' and d.get('slot') == 18 and d.get('om') is None,
      "2. out of combat LT + Y: the player's macro (slotmacro, slot 18), no om", d)
check(d.get('bar') == 'page1' and d.get('lay') == 'lt', "2. ... on the LT page (lt / page1)", d)
bars, d = press(rt=True)
check(d.get('kind') == 'slotmacro' and d.get('path') == 'command' and d.get('slot') == 30 and d.get('om') is True and d.get('tr') == 'L0R1',
      "2. RT + Y: our command over the player's macro - path command, om, R1", d)

L.execute('fire("PLAYER_REGEN_DISABLED"); COMBAT = true; advance(1)')
bars, d = press(lt=True, hw=True)
check(d.get('kind') == 'slotfight' and d.get('path') == 'slotfight' and d.get('om') is True and d.get('fight') is True,
      "2. in a fight LT + Y: the In combat entry wins (slotfight) over the macro - om (the user: \"In combat wins\")", d)
check(d.get('bar') == 'page1' and d.get('slot') == 18 and d.get('lay') == 'lt' and d.get('tr') == 'L1R0',
      "2. ... said where: lt / page1 / slot 18 / L1R0 (m188: a slotfight names its bar)", d)
bars, d = press(lt=True, rt=True, hw=True)
check(d.get('kind') == 'slotfight' and d.get('slot') == 42 and d.get('om') is None, "2. LT + RT + Y: an In combat entry over a SPELL - no om", d)
L.execute('COMBAT = false; fire("PLAYER_REGEN_ENABLED"); advance(1)')
settle()
check(all(not isinstance(v, str) or len(v) <= 40 for v in d.values()), "2. the press's words are plain codes", d)
check(not any(k in d for k in ('macro', 'mn')) and 'MyHeal' not in str(d) and 'Healing Wave' not in str(d),
      "2. nameless: no macro or spell name in a routed facecast", d)

# ---------------------------------------------------------------- (3) show what's on top
s = slot_doc(2, 6)
check(s.get('kind') == 'combat' and s.get('ov') is True and s.get('um') is True and s.get('base') == 'macro',
      "3. LT + Y's view: the In combat entry over the player's macro - um", s)
s = slot_doc(3, 6)
check(s.get('kind') == 'command' and s.get('um') is True, "3. RT + Y's view: our command over the player's macro - um", s)
s = slot_doc(4, 6)
check(s.get('kind') == 'combat' and 'um' not in s, "3. LT + RT + Y's view: the In combat entry over a spell - no um", s)
s = slot_doc(2, 5)
check('um' not in s, "3. an empty slot - no um", s)

# ---------------------------------------------------------------- (4) no trace
check(not clean(), "4. no trace: no spied call (SetCVar / SetBinding / macros), no blocked call, no snippet / handle error", clean())
seg = pm[pm.find("m188 THE CONTROLLER CHANGED"):pm.find('R.fs = CreateFrame("Frame")')]
check(seg and "SetCVar" not in seg and "SetBinding" not in seg and "EditMacro" not in seg, "4. the controller listener reads only")

print("\n%d passed, %d failed" % (passes, fails))
sys.exit(1 if fails else 0)
