"""THE ROUTER FOLLOWS THE GAME'S STANCE / POSSESS BAR SETTINGS (PadMap m187), off-device: the REAL add-on files (Safety, TapPad, Map,
Quests, Hub, Interact + Dialogs, Book, PadMap, PadTabCtl) under tools/wowstub.py's game with STRICT laid over it (the lockdown, the press
model, the restricted snippets).

    python -B tools/test_ltrt_stancebar.py

The tester, 2026-11-23: "couldn't use any macros on LT or RT with a face button, kept saying invalid target or cant cast when moving,
flicking the class wheel options around seemed to fix that". The read-only investigation: the game's Gamepad settings Stance Bar /
Possess Bar (GamepadStanceBarOverride / GamepadPossessBarOverride; Page 1 Left / Right / Bottom = the LT / RT / LT + RT layers) move
that bar to its new layer at once (gamepadoverridebarmixin.lua, on GAMEPAD_STANCE_BAR_OVERRIDE_CHANGED / ..._POSSESS_...), while the
router kept the wiring of the last form change / fight end / loading screen.

Proves: (0) the version and its note; (1) the stance bar on LT: LT + Y clicks the stance bar's button, RT + Y the RT page's; (2) the
bar MOVED to RT with no event (the old build's case): the router is stale (LT + Y still the stance bar) - the control; (3) the game's
GAMEPAD_STANCE_BAR_OVERRIDE_CHANGED: rewired a moment later, LT + Y = the LT page, RT + Y = the stance bar; (4) CVAR_UPDATE for the bar
settings and Compact rewires (any case), another setting does not; (5) the possess bar on LT + RT, its event: LT + RT + Y = the possess
bar; (6) a change in a fight: nothing secure touched in the lockdown (no blocked call), said pending, wired at PLAYER_REGEN_ENABLED;
(7) each routed key-down's facecast says the layer / the bar / the slot (bare / lt / rt / ltrt; page1 / stance / possess), key-ups
say nothing of it; (8) no trace: no SetCVar / SetBinding / macro write, no snippet / handle error.
Needs lupa. Exit code 1 on any failure."""
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


# every bar's buttons as named globals (so a /click reaches them and the press model logs them by name); the game's stance bar and
# possess bar as override bars whose parent is the anchor the game laid them on (STANCE_AT / POSSESS_AT: the anchors' keys, nil = none)
PRE = r'''
local pu = GamepadMainActionBarFrame.PageUnit
local function globalize(bar, prefix, base)
    bar.GetActionButtonByIndex = function(self, pos)
        local name = prefix .. pos
        local b = rawget(_G, name)
        if b then return b end
        b = setmetatable({ a = {}, refs = {}, hooks = {}, scripts = {}, wraps = {}, clicks = { "AnyDown", "AnyUp" }, _name = name,
                           _id = base + pos, _tpl = "" }, Frame)
        b.scripts.OnClick = function(_, _, down) ACTIONS[#ACTIONS + 1] = { kind = "bar", btn = name, down = down and true or false } end
        PROTECT(b)                                  -- the game's own protected buttons (STRICT: a snippet's handle reads them)
        rawset(_G, name, b)
        return b
    end
    PROTECT(bar)
end
globalize(pu.TopCenteredAnchor.Bar, "GPTop", 0)
globalize(pu.LeftCenteredAnchor.Bar, "GPLeft", 12)
globalize(pu.RightCenteredAnchor.Bar, "GPRight", 24)
globalize(pu.BottomCenteredAnchor.Bar, "GPBottom", 36)
STANCE_AT, POSSESS_AT = "LeftCenteredAnchor", nil
local function overBar(prefix, base, where)
    local bar = { _shown = true }
    function bar:IsShown() return _G[where]() ~= nil end
    function bar:GetParent() local k = _G[where](); return k and pu[k] or nil end
    globalize(bar, prefix, base)
    return bar
end
STANCE_WHERE = function() return STANCE_AT end
POSSESS_WHERE = function() return POSSESS_AT end
pu.actionBars.stanceBar = overBar("GPStance", 72, "STANCE_WHERE")
pu.actionBars.possessBar = overBar("GPPossess", 120, "POSSESS_WHERE")
pu.overrideBars = { pu.actionBars.possessBar, pu.actionBars.stanceBar }
'''


def lst(L, expr):
    t = L.eval(expr)
    return [] if t is None else list(t.values())


def clean(L):
    return {k: v for k, v in (("VIOL", lst(L, 'VIOL')), ("SPY", lst(L, 'SPY')), ("HANDLEERR", lst(L, 'HANDLEERR')),
                              ("SNIPERR", lst(L, 'SNIPERR')), ("ERRS", lst(L, 'ERRS')), ("LOADERRS", lst(L, 'LOADERRS'))) if v}


def settle(L):
    L.execute('NOW = NOW + 2; tick(0.1); advance(0.6); NOW = NOW + 2; tick(0.1); advance(0.6)')


L, errs = boot_world(pre=PRE)
L.execute('LOADERRS = {}')
for e in errs:
    L.eval('function(s) LOADERRS[#LOADERRS + 1] = s end')(e)
L.execute('__IN.ACT.trust(); advance(1); __IN.N.setMode(true); advance(1)')
settle(L)


# Y's router button: in a fight the game's binding presses it (hwPress); out of combat its press is the same click with the triggers'
# state read in its snippet (the binding's route out of combat - the target radial's Y - is not what is proven here)
YB = "WowCompanionCombatFace6"
CLICK_Y = L.eval('''function(lt, rt)
    PADSTATE = {}
    if lt then PADSTATE[LT_I] = true end
    if rt then PADSTATE[RT_I] = true end
    local b = rawget(_G, "%s")
    doClick(b, "LeftButton", true); doClick(b, "LeftButton", false)
    PADSTATE = {}
    NOW = NOW + 0.1
end''' % YB)


def press(key, lt=False, rt=False, hw=False):
    """one press of Y's router (hw: the game's binding, in a fight): the bar buttons it reached (their down edges) and its facecasts"""
    a0, n0 = int(L.eval('#ACTIONS')), nsent(L)
    if hw:
        L.eval('hwPress')(key, lt, rt)
    else:
        CLICK_Y(lt, rt)
    L.execute('advance(0.2)')
    acts = [dict(L.eval('ACTIONS[%d]' % i)) for i in range(a0 + 1, int(L.eval('#ACTIONS')) + 1)]
    bars = [a.get('btn') for a in acts if a.get('kind') == 'bar' and a.get('down')]
    return bars, docs(L, n0, 'facecast')


def down_cast(fc):
    d = [x for x in fc if x.get('down')]
    return d[-1] if d else {}


# the routed Y button (PadMap keeps R a main-chunk local): its own wiring attributes say what each layer carries
def yb():
    return L.eval('rawget(_G, "%s")' % YB)


def wire_of(anchor):
    b = yb()
    if b is None:
        return []
    n = int(b.a['wc-n-' + anchor] or 0)
    return [b.a['wc-dest-%s-%d' % (anchor, i)] for i in range(1, n + 1)]


# ---------------------------------------------------------------- (0) the version and its note
pv = int(SRC["padmap"].split('local PV = "m', 1)[1].split('"', 1)[0])
check(pv >= 187 and "m187 THE ROUTER FOLLOWS THE GAME'S STANCE / POSSESS BAR" in SRC["padmap"], "0. PadMap m187 and its note", pv)
check("GAMEPAD_STANCE_BAR_OVERRIDE_CHANGED" in SRC["padmap"] and "GAMEPAD_POSSESS_BAR_OVERRIDE_CHANGED" in SRC["padmap"],
      "0. the game's own two bar events (blizzard_gamepadactionbars actionbartemplates.xml) are listened to")
check(not clean(L), "0. the world loads clean", clean(L))
check(yb() is not None, "0. Y's router button is made and wired out of combat", YB)

# ---------------------------------------------------------------- (1) the stance bar on LT
check(wire_of('left') == ['GPLeft6', 'GPStance6'] and wire_of('right') == ['GPRight6'], "1. wired: LT = its page + the stance bar, RT = its page",
      (wire_of('left'), wire_of('right')))
bars, fc = press("PAD4", lt=True)
check(bars == ['GPStance6'], "1. LT + Y clicks the stance bar's button", bars)
d = down_cast(fc)
check(d.get('lay') == 'lt' and d.get('bar') == 'stance' and d.get('slot') == 78, "7. its facecast: lt / stance / slot 78", d)
bars, fc = press("PAD4", rt=True)
check(bars == ['GPRight6'], "1. RT + Y clicks the RT page's button", bars)
d = down_cast(fc)
check(d.get('lay') == 'rt' and d.get('bar') == 'page1' and d.get('slot') == 30, "7. its facecast: rt / page1 / slot 30", d)
check(all('lay' not in x and 'bar' not in x and 'slot' not in x for x in fc if not x.get('down')), "7. the key-up's facecast says none of it",
      [x for x in fc if not x.get('down')])

# ---------------------------------------------------------------- (2) the bar moved with no event: stale (the control)
L.execute('STANCE_AT = "RightCenteredAnchor"')
bars, _ = press("PAD4", lt=True)
check(bars == ['GPStance6'], "2. moved to RT, no event yet: LT + Y still the stance bar (the old build's stale wiring)", bars)

# ---------------------------------------------------------------- (3) the game's event: rewired
L.execute('fire("GAMEPAD_STANCE_BAR_OVERRIDE_CHANGED", 2, 3); advance(1)')
check(wire_of('left') == ['GPLeft6'] and wire_of('right') == ['GPRight6', 'GPStance6'], "3. after the event: LT = its page, RT = its page + the stance bar",
      (wire_of('left'), wire_of('right')))
bars, fc = press("PAD4", lt=True)
check(bars == ['GPLeft6'], "3. LT + Y clicks the LT page's button", bars)
check(down_cast(fc).get('lay') == 'lt' and down_cast(fc).get('bar') == 'page1', "3. its facecast: lt / page1", down_cast(fc))
bars, fc = press("PAD4", rt=True)
check(bars == ['GPStance6'], "3. RT + Y clicks the stance bar's button", bars)
check(down_cast(fc).get('bar') == 'stance' and down_cast(fc).get('lay') == 'rt', "3. its facecast: rt / stance", down_cast(fc))

# ---------------------------------------------------------------- (4) CVAR_UPDATE
L.execute('STANCE_AT = "LeftCenteredAnchor"; fire("CVAR_UPDATE", "SoundVolume", "0.5"); advance(1)')
check(wire_of('left') == ['GPLeft6'], "4. another setting's CVAR_UPDATE: no rewiring", wire_of('left'))
L.execute('fire("CVAR_UPDATE", "gamepadStanceBarOverride", "2"); advance(1)')
check(wire_of('left') == ['GPLeft6', 'GPStance6'] and wire_of('right') == ['GPRight6'], "4. CVAR_UPDATE GamepadStanceBarOverride (any case): rewired",
      (wire_of('left'), wire_of('right')))
L.execute('STANCE_AT = "RightCenteredAnchor"; fire("CVAR_UPDATE", "GamepadUseCompactActionBar", "1"); advance(1)')
check(wire_of('right') == ['GPRight6', 'GPStance6'], "4. CVAR_UPDATE GamepadUseCompactActionBar: rewired", wire_of('right'))
L.execute('STANCE_AT = nil; fire("CVAR_UPDATE", "GamepadPossessBarOverride", "1"); advance(1)')
check(wire_of('right') == ['GPRight6'] and wire_of('left') == ['GPLeft6'], "4. CVAR_UPDATE GamepadPossessBarOverride: rewired (no stance bar now)",
      (wire_of('left'), wire_of('right')))

# ---------------------------------------------------------------- (5) the possess bar on LT + RT
L.execute('POSSESS_AT = "BottomCenteredAnchor"; fire("GAMEPAD_POSSESS_BAR_OVERRIDE_CHANGED", 1, 4); advance(1)')
bars, fc = press("PAD4", lt=True, rt=True)
check(bars == ['GPPossess6'], "5. LT + RT + Y clicks the possess bar's button", bars)
d = down_cast(fc)
check(d.get('lay') == 'ltrt' and d.get('bar') == 'possess' and d.get('slot') == 126, "7. its facecast: ltrt / possess / slot 126", d)
L.execute('POSSESS_AT = nil; fire("GAMEPAD_POSSESS_BAR_OVERRIDE_CHANGED", 4, 1); advance(1)')
bars, _ = press("PAD4", lt=True, rt=True)
check(bars == ['GPBottom6'], "5. back to its special page: LT + RT + Y = the bottom page", bars)

# ---------------------------------------------------------------- (6) a change in a fight
v0 = len(lst(L, 'VIOL'))
L.execute('fire("PLAYER_REGEN_DISABLED"); COMBAT = true; advance(1)')          # the game's order: the event, then the lockdown
L.execute('STANCE_AT = "LeftCenteredAnchor"; fire("GAMEPAD_STANCE_BAR_OVERRIDE_CHANGED", 1, 2); advance(1)')
check(len(lst(L, 'VIOL')) == v0, "6. in a fight: nothing secure touched (no blocked call)", lst(L, 'VIOL')[v0:])
check(wire_of('left') == ['GPLeft6'], "6. in a fight: the wiring waits", wire_of('left'))
check(L.eval('BIND["PAD4"]') == "CLICK %s:LeftButton" % YB, "6. in a fight Y is the router's (the game's binding)", L.eval('BIND["PAD4"]'))
bars, fc = press("PAD4", lt=True, hw=True)
check(bars == ['GPLeft6'] and down_cast(fc).get('lay') == 'lt' and down_cast(fc).get('fight') is True,
      "6. LT + Y in the fight, before its end: the wiring the fight began with (the LT page), said as lt / fight", (bars, down_cast(fc)))
check(len(lst(L, 'VIOL')) == v0, "6. ... and that press touched nothing in the lockdown", lst(L, 'VIOL')[v0:])
L.execute('COMBAT = false; fire("PLAYER_REGEN_ENABLED"); advance(1)')
check(wire_of('left') == ['GPLeft6', 'GPStance6'], "6. at the fight's end (PLAYER_REGEN_ENABLED): wired", wire_of('left'))
settle(L)
bars, fc = press("PAD4", lt=True)
check(bars == ['GPStance6'], "6. LT + Y after the fight: the stance bar's button", bars)

# ---------------------------------------------------------------- (7) a bare press says its layer too
bars, fc = press("PAD4")
d = down_cast(fc)
check(d.get('lay') == 'bare' and d.get('bar') in ('page1', 'ours'), "7. a bare Y's facecast: bare and its bar", d)

# ---------------------------------------------------------------- (8) no trace
check(not clean(L), "8. no trace: no spied call (SetCVar / SetBinding / macros), no blocked call, no snippet / handle error", clean(L))
pm = SRC["padmap"]
seg = pm[pm.find("m187 THE ROUTER FOLLOWS THE GAME'S BAR SETTINGS"):pm.find('R.fs:SetScript("OnEvent"')]
check(seg and "SetCVar" not in seg.replace("no CVar", "") and "SetBinding" not in seg, "8. the new listener reads only (no SetCVar / SetBinding)")

print("\n%d passed, %d failed" % (passes, fails))
sys.exit(1 if fails else 0)
