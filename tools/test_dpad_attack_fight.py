"""THE D-PAD'S IN-COMBAT SPELLS FROM THE ATTACK'S START (PadMap m207), off-device: the REAL add-on files under tools/wowstub.py's game
with STRICT laid over it (the lockdown, the press model, the restricted snippets).

    python -B tools/test_dpad_attack_fight.py

The player's report on 1.0.40: starting the attack with X before the fight begins switches Home's D-pad picture and X / Y to the In
combat spells, but a D-pad press still casts the Out of combat spell until the game puts the player in combat. The cause: a D-pad
position whose only thing of ours is an In-combat spell had its key left on the game's own binding out of combat, so the router (which
switches on the attack's start, "wc-pre") never saw the press. The D-pad must use the In combat set as soon as the attack starts.

Proves: (1) out of combat, no attack: the D-pad press is the slot's own (Out of combat) spell, not the In-combat one; (2) the attack
started (PLAYER_ENTER_COMBAT, no lockdown yet): the D-pad press casts the In-combat spell; (3) auto-repeat (START_AUTOREPEAT_SPELL)
too; (4) the attack over: the slot's own spell again; (5) the fight itself: the In-combat spell (as before); (6) a trigger layer's
D-pad In-combat spell (ZL + D-pad) too; (7) a form's own In-combat D-pad spell too; (8) the fight spell removed: the key is the game's
again; (9) a room holding: the router steps aside (the key is not ours); (10) nothing protected from insecure code in the fight, no
spied call, no snippet error. Needs lupa. Exit code 1 on any failure."""
import sys

from wowstub import boot_world

fails = passes = 0


def check(ok, name, got=''):
    global fails, passes
    print(('PASS ' if ok else 'FAIL ') + name + ((' -> ' + str(got)) if got != '' and not ok else ''))
    if ok:
        passes += 1
    else:
        fails += 1


# the bar's own spells: what the game's D-pad Up button casts out of combat (its action slot holds a spell)
BAR = r'''
ACTIONINFO = {}
function GetActionInfo(s) local a = ACTIONINFO[s]; if a then return a[1], a[2] end end
UNITS.target = { name = "Kobold", alive = true, foe = true }
-- Forever's top bar buttons as real global buttons that log their press (as test_b_pet_follow's BACK), so the game's own D-pad press shows
local top = GamepadMainActionBarFrame.PageUnit.TopCenteredAnchor.Bar
local get = top.GetActionButtonByIndex
top.GetActionButtonByIndex = function(self, pos)
    local name = "GPTop" .. pos
    local b = rawget(_G, name)
    if b then return b end
    b = get(self, pos)
    b._tpl = ""
    b.clicks = { "AnyDown", "AnyUp" }
    b.scripts.OnClick = function(_, _, down) ACTIONS[#ACTIONS + 1] = { kind = "bar", btn = name, down = down and true or false } end
    rawset(_G, name, b)
    return b
end
'''


def world(pre=''):
    L, errs = boot_world(pre=BAR + pre)
    L.execute('LOADERRS = {}')
    for e in errs:
        L.eval('function(s) LOADERRS[#LOADERRS + 1] = s end')(e)
    L.execute('__IN.ACT.trust(); advance(1); __IN.N.setMode(true); advance(1)')
    # the game's storage formula with the stub's bar ids (top 1-12, ZL 13-24, ZR 25-36, ZL + ZR 37-48), as test_ltrt_macro_over
    L.execute('WowCompanion.spellsStorageFor = function(page, set, pos) return (page - 1) * 48 + (set - 1) * 12 + pos end')
    settle(L)
    return L


def settle(L):
    L.execute('NOW = NOW + 2; tick(0.1); advance(0.6); NOW = NOW + 2; tick(0.1); advance(0.6)')


def lst(L, expr):
    t = L.eval(expr)
    return [] if t is None else list(t.values())


def clean(L):
    return {k: v for k, v in (("VIOL", lst(L, 'VIOL')), ("SPY", lst(L, 'SPY')), ("HANDLEERR", lst(L, 'HANDLEERR')),
                              ("SNIPERR", lst(L, 'SNIPERR')), ("ERRS", lst(L, 'ERRS')), ("LOADERRS", lst(L, 'LOADERRS'))) if v}


def press(L, key, lt=False, rt=False):
    a0 = int(L.eval('#ACTIONS'))
    how, log = L.eval('hwPress')(key, lt, rt)
    L.execute('advance(0.2)')
    acts = [dict(L.eval('ACTIONS[%d]' % i)) for i in range(a0 + 1, int(L.eval('#ACTIONS')) + 1)]
    return {"how": how, "acts": acts}


def casts(r):
    return [a.get('line') for a in r['acts'] if a.get('kind') == 'macroline']


def own(r, pos):
    """the press reached the game's own D-pad button (Forever's top bar button at `pos`: its spell) or the game's own binding"""
    bar = [a.get('btn') for a in r['acts'] if a.get('kind') == 'bar']
    return r['how'] == 'game' or ('GPTop%d' % pos) in bar


FIGHT = "/cast Healing Wave"


def put_fight(L, set_, pos, spell="Healing Wave", sid=331):
    """an In-combat spell on page 1's place (set, pos) (this character's fightBy, as the In combat tab stores it)"""
    s = int(L.eval('WowCompanion.spellsStorageFor(1, %d, %d)' % (set_, pos)))
    L.execute('''local k = WowCompanion.charKey()
WowCompanionSpellsDB.fightBy = WowCompanionSpellsDB.fightBy or {}
WowCompanionSpellsDB.fightBy[k] = WowCompanionSpellsDB.fightBy[k] or {}
WowCompanionSpellsDB.fightBy[k][%d] = { id = %d, n = "%s", i = 136052, page = 1, set = %d, pos = %d }
fire("ACTIONBAR_SLOT_CHANGED", %d)''' % (s, sid, spell, set_, pos, s))
    settle(L)
    return s


def attack(L, on, ev="PLAYER_ENTER_COMBAT"):
    if on:
        L.execute('fire("%s"); advance(0.3)' % ev)
    else:
        L.execute('fire("%s"); advance(0.3)' % ("PLAYER_LEAVE_COMBAT" if ev == "PLAYER_ENTER_COMBAT" else "STOP_AUTOREPEAT_SPELL"))
    settle(L)


# ---------------------------------------------------------------- (1)-(5) the bare D-pad Up (position 2) with an In-combat spell
W = world()
s = put_fight(W, 1, 2)
check(W.eval('BIND["PADDUP"]') is None or 'WowCompanion' in str(W.eval('BIND["PADDUP"]')),
      "setup: D-pad Up is unbound or ours", W.eval('BIND["PADDUP"]'))
r = press(W, "PADDUP")
check(FIGHT not in casts(r) and own(r, 2), "(1) out of combat, no attack: D-pad Up is the game's own button (its Out of combat spell)", r)
attack(W, True)
check(W.eval('rawget(_G, "WowCompanionFaceX") == nil or WowCompanionFaceX.a["wc-pre"] == true') is True,
      "(2) setup: the attack's start set wc-pre (X / Y switch)")
r = press(W, "PADDUP")
check(casts(r) == [FIGHT], "(2) the attack started (no lockdown yet): D-pad Up casts the In-combat spell", (r['how'], casts(r)))
r = press(W, "PADDUP")
check(casts(r) == [FIGHT], "(2) and the next press too", (r['how'], casts(r)))
attack(W, False)
r = press(W, "PADDUP")
check(FIGHT not in casts(r) and own(r, 2), "(4) the attack over: D-pad Up is the slot's own spell again", r)
attack(W, True, "START_AUTOREPEAT_SPELL")
r = press(W, "PADDUP")
check(casts(r) == [FIGHT], "(3) auto-repeat started: D-pad Up casts the In-combat spell", (r['how'], casts(r)))
attack(W, False, "START_AUTOREPEAT_SPELL")
W.execute('fire("PLAYER_REGEN_DISABLED"); COMBAT = true; advance(0.5)')
r = press(W, "PADDUP")
check(casts(r) == [FIGHT], "(5) the fight: D-pad Up casts the In-combat spell (as before)", (r['how'], casts(r)))
W.execute('COMBAT = false; fire("PLAYER_REGEN_ENABLED"); advance(1)')
settle(W)
r = press(W, "PADDUP")
check(FIGHT not in casts(r) and own(r, 2), "(5) after the fight: the slot's own spell", r)
check(clean(W) == {}, "(10) the bare D-pad: no lockdown breach, no spied call, no snippet error", clean(W))

# ---------------------------------------------------------------- (6) a trigger layer's D-pad (ZL + D-pad Right) In-combat spell
T = world()
put_fight(T, 2, 3, "Lesser Healing Wave", 8004)
r = press(T, "PADDRIGHT", lt=True)
check("/cast Lesser Healing Wave" not in casts(r), "(6) ZL + D-pad Right out of combat, no attack: not the In-combat spell", casts(r))
attack(T, True)
r = press(T, "PADDRIGHT", lt=True)
check(casts(r) == ["/cast Lesser Healing Wave"], "(6) ZL + D-pad Right after the attack's start: the In-combat spell", (r['how'], casts(r)))
check(clean(T) == {}, "(6) clean", clean(T))

# ---------------------------------------------------------------- (7) a form's own In-combat D-pad spell (Cat Form, D-pad Left)
FORMS = r'''
CLASS = "DRUID"
SPELLNAME = SPELLNAME or {}
SPELLNAME[5487] = "Bear Form"; SPELLNAME[768] = "Cat Form"; SPELLNAME[5221] = "Shred"
FORMS = { { id = 5487, icon = 132276 }, { id = 768, icon = 132115 } }
FORM = 0
function GetNumShapeshiftForms() return #FORMS end
function GetShapeshiftForm() return FORM end
function GetShapeshiftFormInfo(i) local f = FORMS[i]; if f then return f.icon, i == FORM, true, f.id end end
local baseParse = SecureCmdOptionParse
function SecureCmdOptionParse(s)
    s = s:gsub("^%s+", "")
    local cond, rest = s:match("^%[(.-)%]%s*(.*)$")
    if cond then
        local n = cond:match("^form:(%d+)$")
        if n then if tonumber(n) == FORM then return rest end return nil end
    end
    return baseParse(s)
end
-- the attribute drivers (the header's "wc-form" on [form:N]), driven by hand as test_form_fight's
ADRV = {}
function RegisterAttributeDriver(f, attr, cond) ADRV[#ADRV + 1] = { f = f, a = attr, c = cond } end
function driveAll()
    for _, d in ipairs(ADRV) do
        for clause in (d.c .. ";"):gmatch("([^;]*);") do
            local r = SecureCmdOptionParse((clause:gsub("^%s+", "")))
            if r ~= nil and r ~= "" then d.f.a[d.a] = r break end
        end
    end
    driveStates()
end
'''
D = world(FORMS)
D.execute('''local k = WowCompanion.charKey()
WowCompanionSpellsDB.formFightBy = WowCompanionSpellsDB.formFightBy or {}
WowCompanionSpellsDB.formFightBy[k] = { [768] = { [1] = { id = 5221, n = "Shred", i = 132152, set = 1, pos = 1 } } }
fire("UPDATE_SHAPESHIFT_FORMS"); fire("ACTIONBAR_SLOT_CHANGED", 1)''')
settle(D)
check(D.eval('BIND["PADDLEFT"]') == 'CLICK WowCompanionFaceD1:LeftButton',
      "(7) a form's own In-combat spell on D-pad Left: its key is the router's out of combat", D.eval('BIND["PADDLEFT"]'))
D.execute('FORM = 2; driveAll(); fire("UPDATE_SHAPESHIFT_FORM"); advance(0.3)')
attack(D, True)
r = press(D, "PADDLEFT")
check(casts(r) == ["/cast Shred"], "(7) in Cat Form, the attack started: D-pad Left casts the form's In-combat spell", (r['how'], casts(r)))
check(clean(D) == {}, "(7) clean", clean(D))

# ---------------------------------------------------------------- (8) the fight spell removed: the key is the game's again
W.execute('local k = WowCompanion.charKey(); WowCompanionSpellsDB.fightBy[k][%d] = nil; fire("ACTIONBAR_SLOT_CHANGED", %d)' % (s, s))
settle(W)
check(W.eval('BIND["PADDUP"]') is None, "(8) the In-combat spell removed: D-pad Up is the game's own again", W.eval('BIND["PADDUP"]'))

# ---------------------------------------------------------------- (9) a room holding: the router steps aside
R = world()
put_fight(R, 1, 4)
check(R.eval('BIND["PADDDOWN"]') is not None, "(9) setup: D-pad Down is the router's with an In-combat spell on it", R.eval('BIND["PADDDOWN"]'))
R.eval('hwTap')("qact", 8)                     # the Quests room (test_b_pet_follow's QACT)
R.execute('advance(0.3)')
settle(R)
check(R.eval('WowCompanion.capture.state().holder') == 'pad:quests' and 'WowCompanionFaceD4' not in str(R.eval('BIND["PADDDOWN"]')),
      "(9) a room holds: the router steps aside, D-pad Down is not the router's", (R.eval('WowCompanion.capture.state().holder'), R.eval('BIND["PADDDOWN"]')))
R.eval('hwTap')("qact", 9)
R.execute('advance(0.3)')
settle(R)
check(R.eval('WowCompanion.capture.state().holder') is None and R.eval('BIND["PADDDOWN"]') == 'CLICK WowCompanionFaceD4:LeftButton',
      "(9) the room lets go: D-pad Down is the router's again", R.eval('BIND["PADDDOWN"]'))
check(clean(R) == {}, "(9) clean", clean(R))

print("\n%d passed, %d failed" % (passes, fails))
sys.exit(1 if fails else 0)
