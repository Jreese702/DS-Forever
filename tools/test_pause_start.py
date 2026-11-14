"""START AND THE TWO RADIALS (PadMap m160), off-device: the REAL Spells files (Safety, TapPad, Book, PadMap, PadTabCtl) under the
stubbed game (tools/wowstub.py), Lua 5.1 as the game's.

    python tools/test_pause_start.py

The player report (the user relaying it, 2026-11-13): "the pause radial is appearing on the top and bottom. They have some ui elements
showing on the top and I think that the problem is linked". The game's Start is OPENRADIAL in its Core binding set (always live:
blizzard_gamepadsharedutility bindingsetfactory.lua, gameMenu = GAMEPAD_MENU_RIGHT = PADFORWARD), so whenever PadMap's router does NOT
hold Start, a press opens the game's own radial on the top screen. Up to m159 the router let go of Start for ANY game window it saw (a
vendor, a pop-up, a panel, a frame of the controller manager) while the app opened Home's wheel on the same press: both radials.

Proves: (1) THE CAUSE at m159's rule, re-run here as the old predicate: a game window with nobody holding = Start NOT ours; (2) m160:
Start stays ours over every game window, managed frames and UI panels too, and the padmap says so (start.bound "pause"); (3) the Game
Menu and the game's own radial keep Start the game's, with start.why "menu" / "radial"; a guest keeps its own (why "guest"); the toolbar
off = the game's ("toolbar"); (4) routerCheck follows a window opening and closing with no flapping; (5) nothing of ours is bound with
the toolbar off; no spied call. Needs lupa. Exit code 1 on any failure."""
import sys

from wowstub import boot, docs, nsent, SRC

fails = 0
passes = 0


def check(ok, name, got=''):
    global fails, passes
    print(('PASS ' if ok else 'FAIL ') + name + ((' -> ' + str(got)) if got != '' else ''))
    if ok:
        passes += 1
    else:
        fails += 1


def world(toolbar=True):
    pre = 'WowCompanionSpellsDB.dpad = { ["Korvash-Realm"] = %s }' % ('true' if toolbar else 'false')
    L = boot(["safety", "tappad", "book", "padmap", "padtabctl"], pre=pre)
    L.execute('fire("PLAYER_LOGIN"); fire("PLAYER_ENTERING_WORLD", true, false); runTimers(NOW + 10)')
    return L


def start_bind(L):
    return L.eval('BIND["PADFORWARD"]')


def last_start(L, n0=0):
    pm = docs(L, n0, 'padmap')
    return pm[-1]['m'].get('start') if pm else None


def settle(L):
    L.execute('NOW = NOW + 2; tick(0.1); advance(0.6); NOW = NOW + 2; tick(0.1); advance(0.6)')


OURS = "CLICK WowCompanionFaceStart:LeftButton"

check('local PV = "m164"' in SRC["padmap"], "PadMap says m162 (m161 + the 1.0.32 merge)")
check('function R.startWanted(aside, holder)' in SRC["padmap"] and SRC["padmap"].count('R.startWanted(aside, holder) and rbOn') == 1
      and SRC["padmap"].count('R.startWanted(aside, roomHolder())') == 1,
      "routerBind and routerCheck both ask R.startWanted (one rule for the bind and its check)")

# ---------------------------------------------------------------- (1) THE CAUSE: m159's rule, as it was
W = world()
check(list(W.eval('ERRS').values()) == [] and W.eval('#SNIPERR') == 0, "the world loads with the toolbar on, no error",
      list(W.eval('ERRS').values())[:2])
check(start_bind(W) == OURS, "plain Home, nothing open: Start is ours (WowCompanionFaceStart)", start_bind(W))
st = last_start(W)
check(st is not None and st.get('bound') == 'pause' and st.get('why') is None, "the padmap's start: bound pause, no why", st)
# m159's predicate, restated: `not aside or R.startInRoom(holder)` (a window with nobody holding = aside "<name>", holder nil)
W.execute('function OLD_RULE(aside, holder) return (not aside) or (type(holder) == "string" and holder:find("^pad:") == nil) end')
check(W.eval('OLD_RULE("MerchantFrame", nil)') is False and W.eval('OLD_RULE("managed", nil)') is False
      and W.eval('OLD_RULE("panel", nil)') is False,
      "THE CAUSE (m159's rule): a vendor / a managed frame / a UI panel with nobody holding = Start left to the game's OPENRADIAL "
      "(Core set, always live) while the app opened Home's wheel: both radials")

# ---------------------------------------------------------------- (2) m160: ours over every game window
for name, managed, panel in (("MerchantFrame", False, False), ("StaticPopup1", False, False), ("LootFrame", False, False),
                             ("ReadyCheckFrame", False, False), ("SomeManagedFrame", True, False), ("CharacterFrame", False, True)):
    if managed:
        W.execute('%s = { IsShown = function() return true end }; GamepadMode = { FrameControlsManager = { shownFrames = { %s } } }' % (name, name))
    elif panel:
        W.execute('%s = { IsShown = function() return true end }; function GetUIPanel(s) if s == "left" then return %s end end' % (name, name))
    else:
        W.execute('%s = { IsShown = function() return true end }' % name)
    n0 = nsent(W)
    settle(W)
    st = last_start(W, n0)
    check(start_bind(W) == OURS and (st is None or (st.get('bound') == 'pause' and st.get('aside'))),
          "%s open, nobody holding: the router is aside for it, but Start stays OURS (no game radial beside Home's)" % name,
          (start_bind(W), st))
    W.execute('%s = nil; GamepadMode = nil; GetUIPanel = nil' % name)
    settle(W)
    check(start_bind(W) == OURS, "%s closed: Start still ours" % name, start_bind(W))

# ---------------------------------------------------------------- (3) the game's own: the Game Menu, the game's radial, a guest
for name, why in (("GameMenuFrame", "menu"), ("GamepadRadial", "radial")):
    W.execute('%s = { IsShown = function() return true end }' % name)
    n0 = nsent(W)
    settle(W)
    st = last_start(W, n0)
    check(start_bind(W) is None and st is not None and st.get('bound') is None and st.get('why') == why,
          "%s open: Start is the game's (its own close / toggle), start.why %r" % (name, why), (start_bind(W), st))
    W.execute('%s = nil' % name)
    n0 = nsent(W)
    settle(W)
    st = last_start(W, n0)
    check(start_bind(W) == OURS and st is not None and st.get('bound') == 'pause' and st.get('why') is None,
          "%s closed: Start is ours again (bound pause)" % name, (start_bind(W), st))
# the Game Menu as one of the controller manager's frames (windowOpen says "managed", not its name): still the game's
W.execute('GameMenuFrame = { IsShown = function() return true end }; GamepadMode = { FrameControlsManager = { shownFrames = { GameMenuFrame } } }')
settle(W)
check(start_bind(W) is None, "the Game Menu seen as a managed frame: Start is still the game's", start_bind(W))
W.execute('GameMenuFrame = nil; GamepadMode = nil')
settle(W)
check(start_bind(W) == OURS, "the Game Menu gone: ours again", start_bind(W))
# a guest (Quests' generic guests bind Start themselves, q35): the router leaves it
W.execute('HOLDER = "pad:map"')
n0 = nsent(W)
settle(W)
st = last_start(W, n0)
check(start_bind(W) != OURS and st is not None and st.get('why') == 'guest', "a guest holds (pad:map): the router leaves Start to it, why guest",
      (start_bind(W), st))
W.execute('HOLDER = "pad:map"; MerchantFrame = { IsShown = function() return true end }')
settle(W)
check(start_bind(W) != OURS, "a guest holds over a game window: still the guest's", start_bind(W))
W.execute('HOLDER = "bags"')
settle(W)
check(start_bind(W) == OURS, "a ROOM holds over a game window: ours (m43, as before)", start_bind(W))
W.execute('HOLDER = nil; MerchantFrame = nil')
settle(W)
check(start_bind(W) == OURS, "all let go: ours", start_bind(W))

# ---------------------------------------------------------------- (4) no flapping: a steady window keeps one bind
W.execute('MerchantFrame = { IsShown = function() return true end }')
settle(W)
n0 = nsent(W)
W.execute('for i = 1, 30 do NOW = NOW + 0.1; tick(0.1) end')
pm = docs(W, n0, 'padmap')
check(start_bind(W) == OURS and len(pm) <= 1, "a vendor left open for 3 s: Start stays ours, no re-bind loop (%d padmaps)" % len(pm), len(pm))
W.execute('MerchantFrame = nil')
settle(W)

# ---------------------------------------------------------------- (5) the toolbar off: Start is the game's, nothing of ours
T = world(toolbar=False)
st = last_start(T)
check(T.eval('BIND["PADFORWARD"]') is None and st is not None and st.get('why') == 'toolbar' and st.get('bound') is None,
      "the DS WoW toolbar off: Start is the game's own wheel (nothing of ours bound), why toolbar", (T.eval('BIND["PADFORWARD"]'), st))
T.execute('MerchantFrame = { IsShown = function() return true end }')
settle(T)
check(T.eval('BIND["PADFORWARD"]') is None, "the toolbar off over a vendor: still the game's", T.eval('BIND["PADFORWARD"]'))

# ---------------------------------------------------------------- the fight: carried at the pull over a game window, the Game Menu not
F = world()
F.execute('MerchantFrame = { IsShown = function() return true end }')
settle(F)
F.execute('fire("PLAYER_REGEN_DISABLED"); COMBAT = true; advance(0.3)')
check(F.eval('BIND["PADFORWARD"]') == OURS, "a fight starts with a vendor open: Start carried ours", F.eval('BIND["PADFORWARD"]'))
F.execute('COMBAT = false; fire("PLAYER_REGEN_ENABLED"); advance(0.3); MerchantFrame = nil')
settle(F)

# ---------------------------------------------------------------- m161: the setting (the user: "Make it a setting toggle defaulted to our wheel")
check('local BV = "b65"' in SRC["book"] and 'local PV = "m164"' in SRC["padmap"], "Book b62, PadMap m162 (b61 / m161 + the 1.0.32 merge)")


def padtab(L, *cols):
    for c in cols:
        L.execute('WowCompanion.pad.rows["padtab"].handler(nil, %d); NOW = NOW + 0.1' % c)


G = world()
st = last_start(G)
check(st is not None and st.get('over') is None and G.eval('WowCompanionSpellsDB.startOver') is None,
      "the default is Our wheel (nothing stored, start.over absent)", st)
for value, cols, over in (("Game's wheel", (39, 33), 'game'), ("Our wheel", (39, 32), None)):
    n0 = nsent(G)
    padtab(G, *cols)
    st = last_start(G, n0)
    check(G.eval('WowCompanionSpellsDB.startOver') == over and st is not None and st.get('over') == over,
          "padtab 39 then %d = %s: kept account-wide, the padmap says over=%r" % (cols[1], value, over), (G.eval('WowCompanionSpellsDB.startOver'), st))
    # over a game window, nobody holding
    G.execute('MerchantFrame = { IsShown = function() return true end }')
    n0 = nsent(G)
    settle(G)
    st = last_start(G, n0) or last_start(G)
    if over == 'game':
        check(start_bind(G) is None and st.get('bound') is None and st.get('why') == 'window',
              "Game's wheel, a vendor open: Start is the GAME's (its own radial), start.why window (the app opens none)", (start_bind(G), st))
    else:
        check(start_bind(G) == OURS and st.get('bound') == 'pause', "Our wheel, a vendor open: Start is ours", (start_bind(G), st))
    # plain Home and a room holding are ours in both values
    G.execute('MerchantFrame = nil')
    settle(G)
    check(start_bind(G) == OURS, "%s, nothing open: Start is ours (plain Home)" % value, start_bind(G))
    G.execute('MerchantFrame = { IsShown = function() return true end }; HOLDER = "bags"')
    settle(G)
    check(start_bind(G) == OURS, "%s, a ROOM holds over a game window: ours (m43)" % value, start_bind(G))
    G.execute('MerchantFrame = nil; HOLDER = nil')
    settle(G)
# the pick alone (no code after it) changes nothing; a code without the pick is its plain self (never the setting)
padtab(G, 32 + 1)
check(G.eval('WowCompanionSpellsDB.startOver') is None, "padtab 33 without the pick: not the setting", G.eval('WowCompanionSpellsDB.startOver'))
# in a fight: kept, the binding follows at the fight's end
G.execute('fire("PLAYER_REGEN_DISABLED"); COMBAT = true; advance(0.3)')
padtab(G, 39, 33)
check(G.eval('WowCompanionSpellsDB.startOver') == 'game' and G.eval('#VIOL') == 0, "set in a fight: kept, no protected call",
      (G.eval('WowCompanionSpellsDB.startOver'), G.eval('#VIOL')))
G.execute('COMBAT = false; fire("PLAYER_REGEN_ENABLED"); advance(0.3); MerchantFrame = { IsShown = function() return true end }')
settle(G)
check(start_bind(G) is None, "after the fight, a vendor open: the game's (Game's wheel)", start_bind(G))
G.execute('MerchantFrame = nil'); settle(G)
padtab(G, 39, 32)

for L in (W, T, F, G):
    check(L.eval('#SPY') == 0 and L.eval('#SNIPERR') == 0, "no spied call, no snippet error", (L.eval('SPY[1]'), L.eval('SNIPERR[1]')))

# ---------------------------------------------------------------- issue #278: Spells DISABLED, pad15 never switched it on (TapPad pad20)
# the report: "our add-ons: 9 loaded, NOT loaded: WowCompanionSpells:DISABLED" and NO "addonsoff" probe that run. The game's own reason
# (GetAddOnInfo, what the probe reads) said DISABLED while pad15's one test, GetAddOnEnableState(name, character) == 0, did not hold.
check('local PADV = "pad21"' in SRC["tappad"], "TapPad says pad21 (pad20 + the shared row)")
ADDONS_STUB = r'''
OFF = { WowCompanionSpells = true }
ENABLED_CALLS = {}
C_AddOns = {
    GetAddOnInfo = function(a) return a, a, "", false, OFF[a] and "DISABLED" or nil end,
    EnableAddOn = function(a, who) ENABLED_CALLS[#ENABLED_CALLS + 1] = a .. "/" .. tostring(who); OFF[a] = nil end,
    SaveAddOns = function() SAVED = true end,
}
'''
ENABLE_STATES = {
    # what pad15 asked (name, character): the client's answers in three shapes
    "a per-character state the client does not report (2 = all)": 'C_AddOns.GetAddOnEnableState = function(a, who) return 2 end',
    "the call refused (an argument order the client does not take)": 'C_AddOns.GetAddOnEnableState = function(a, who) error("Usage: GetAddOnEnableState(character, name)") end',
    "no such call on the client": 'C_AddOns.GetAddOnEnableState = nil',
}
for shape, stub in ENABLE_STATES.items():
    # pad15's old test, restated: only GetAddOnEnableState(a, me) == 0 counted as off
    P = boot(["safety", "tappad"], pre=ADDONS_STUB + stub)
    old = P.eval('''(function() local ok, st = pcall(C_AddOns.GetAddOnEnableState, "WowCompanionSpells", "Korvash"); return ok and st == 0 end)()''')
    n0 = nsent(P)
    P.execute('fire("PLAYER_ENTERING_WORLD", true, false)')
    sent = [d for d in docs(P, n0) if d.get('probe') == 'addonsoff']
    check(old is False and sent and sent[-1]['list'] == ['WowCompanionSpells'] and sent[-1]['how'] == ['reason'] and sent[-1]['after'] == ['']
          and "WowCompanionSpells/nil" in list(P.eval('ENABLED_CALLS').values()) and "WowCompanionSpells/Korvash" in list(P.eval('ENABLED_CALLS').values())
          and P.eval('SAVED') is True,
          "#278, %s: pad15's old test missed it; pad20 finds Spells off by the game's reason, switches it on (all + this character), "
          "saves, and the probe says so (after: loadable)" % shape, (old, sent, list(P.eval('ENABLED_CALLS').values())))
# the state that DOES say 0 still counts (pad15's own case), and nothing is touched when all are on
P = boot(["safety", "tappad"], pre=ADDONS_STUB + 'OFF = {}; C_AddOns.GetAddOnEnableState = function(a, who) if a == "WowCompanionHub" then return 0 end return 2 end')
n0 = nsent(P)
P.execute('fire("PLAYER_ENTERING_WORLD", true, false)')
sent = [d for d in docs(P, n0) if d.get('probe') == 'addonsoff']
check(sent and sent[-1]['list'] == ['WowCompanionHub'] and sent[-1]['how'] == ['state'], "a state of 0 (pad15's case) still switches it on", sent)
P = boot(["safety", "tappad"], pre=ADDONS_STUB + 'OFF = {}; C_AddOns.GetAddOnEnableState = function() return 2 end')
n0 = nsent(P)
P.execute('fire("PLAYER_ENTERING_WORLD", true, false)')
check(not [d for d in docs(P, n0) if d.get('probe') == 'addonsoff'] and len(P.eval('ENABLED_CALLS')) == 0,
      "every one of ours on: nothing switched, no probe", list(P.eval('ENABLED_CALLS').values()))
# never another add-on's state
P = boot(["safety", "tappad"], pre=ADDONS_STUB + 'OFF = { SomeoneElse = true, WowCompanionSpells = true }; C_AddOns.GetAddOnEnableState = nil')
P.execute('fire("PLAYER_ENTERING_WORLD", true, false)')
check(all(c.startswith("WowCompanion") for c in P.eval('ENABLED_CALLS').values()), "only our own add-ons are ever switched on",
      list(P.eval('ENABLED_CALLS').values()))

print("%d passed, %d failed" % (passes, fails))
print("ALL PASS" if fails == 0 else "FAILURES")
sys.exit(1 if fails else 0)
