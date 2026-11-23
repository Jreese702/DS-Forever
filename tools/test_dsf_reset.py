"""RESET TO DEFAULTS, THE ADD-ON'S HALF (PadMap m175, Radial pr3), off-device: the REAL add-on files under tools/wowstub.py's game.

    python -B tools/test_dsf_reset.py

The user, 2026-11-19: "DS Forever needs reset to defaults buttons, split into four" (Settings > General > Reset; Layouts and Skills =
the current character only; Everything asks This character / All characters). The app taps the pause row's column 20 + code (0
settings, 1 layouts, 2 skills, 3 everything; + 4 every character); WC.dsfReset clears the stores and answers {type = "dsfreset"}.

Proves: (0) the versions (m175, r26 / pr3) and the pause row routing 20-27 to WC.dsfReset; (1) Layouts: this character's layout stores
only (another character's and the skills untouched), the toolbar back to ON; (2) Skills: this character's skills only (layouts, other
characters, the never-reset keys - learned cooldowns, Polestar, one-time marks - untouched); (3) Settings: the account-wide Top screen
switches only, no character's store; (4) Everything + every character; (5) a fight refuses and changes nothing; (6) the saved tables are
sent at once (svkeep) after a reset; (7) the add-on's key lists equal the app's ResetPlan (the inventory in one place).
Needs lupa. Exit code 1 on any failure."""
import json
import os
import re
import sys

from wowstub import ROOT, SRC, boot_world, docs, nsent

fails = passes = 0


def check(ok, name, got=''):
    global fails, passes
    print(('PASS ' if ok else 'FAIL ') + name + ((' -> ' + str(got)) if got != '' else ''))
    if ok:
        passes += 1
    else:
        fails += 1


ME = "Shammy-Fakerealm"
OTHER = "Huntress-Fakerealm"
SEED = r'''
local ME, OTHER = "%s", "%s"
local function both(v1, v2) return { [ME] = v1, [OTHER] = v2 } end
WowCompanionSpellsDB = {
    faceBy = both({ [1] = 403 }, { [1] = 3044 }), fightBy = both({ [1] = { id = 403 } }, { [1] = { id = 3044 } }),
    cmdBy = both({ [5] = { k = "DSSRADIAL" } }, { [5] = { k = "DSSRADIAL" } }), binds = both({ ["1:2"] = "JUMP" }, { ["1:2"] = "JUMP" }),
    smartOn = both(true, true), sradials = both({ { ids = { 403 } } }, { { ids = { 3044 } } }), sradial = both({ 403 }, { 3044 }),
    castBar = both({ [1] = { k = "s", id = 403 } }, { [1] = { k = "s", id = 3044 } }),
    castBarFight = both({ [1] = { k = "spell", id = 331 } }, { [1] = { k = "spell", id = 331 } }),
    castBarParty = both({ [1] = { k = "spell", id = 331 } }, { [1] = { k = "spell", id = 331 } }),
    dpad = both(false, false), cdLearn = both({ s403 = 1.5 }, { s3044 = 6 }), dpadZL = both({ undone = {} }, { undone = {} }),
    padLayout = "plain", keyProbe = "m100",
}
WowCompanionDB = {
    lay = both({ hud = { dx = 5, dy = 6, s = 110 } }, { hud = { dx = 1, dy = 1, s = 90 } }), hubSw = both({ np = 1 }, { np = 2 }),
    prdY = both(-300, -310), meters = both({ d = "off" }, { d = "on" }), bagBinds = both({ zl1 = 6948 }, { zl1 = 6948 }),
    pqol = both(7, 7), pskip = both({ g1 = { 1 } }, {}), compass = false, hideTips = true, aggroRings = false, lootTop = true,
    layV = 1, compassCal = { v = "cp18" }, qtDefaults = 2,
}
''' % (ME, OTHER)
PRE = 'function UnitName(u) if u == "player" then return "Shammy" end end; function GetRealmName() return "Fakerealm" end\n'


def world():
    L, errs = boot_world(pre=PRE)
    check(not errs, 'the add-on files load', errs)
    L.execute(SEED)
    return L


def val(L, expr):
    v = L.eval(expr)
    return v


def reset(L, col):
    n0 = nsent(L)
    L.execute('WowCompanion.dsfReset(%d)' % col)
    ds = docs(L, n0)
    ans = [d for d in ds if d.get('type') == 'dsfreset']
    return (ans[-1] if ans else None), ds


pm = SRC["padmap"]
rad = open(os.path.join(ROOT, "companion", "app", "src", "main", "assets", "companion", "addons", "WowCompanionSpells", "Radial.lua"),
           encoding="utf-8").read()
# (0) the versions and the route
check('local PV = "m185"' in pm and 'm176 THE TAP-TO-CAST BAR' in pm, '(0) PadMap tag m182 (the m176 note kept)')
check('local RV = "r27"' in rad and 'local PRV = "pr3"' in rad, '(0) Radial tags r27 / pr3')
check(re.search(r'elseif col >= 20 and col <= 27 then\s*\n\s*--[^\n]*\n\s*if type\(WC\.dsfReset\) == "function" then WC\.dsfReset\(col\)', rad)
      is not None, '(0) the pause row hands columns 20-27 to WC.dsfReset')

# (1) Layouts, this character
L = world()
a, ds = reset(L, 21)
check(a and a['ok'] is True and a['col'] == 21 and a['v'] == 'm185', '(1) layouts answered ok', a)
check(val(L, 'WowCompanionDB.lay["%s"]' % ME) is None and val(L, 'WowCompanionDB.hubSw["%s"]' % ME) is None and
      val(L, 'WowCompanionDB.prdY["%s"]' % ME) is None and val(L, 'WowCompanionDB.meters["%s"]' % ME) is None, '(1) my layout stores gone')
check(val(L, 'WowCompanionSpellsDB.dpad["%s"]' % ME) is True, '(1) my toolbar back to its default ON')
check(val(L, 'WowCompanionDB.lay["%s"].hud.s' % OTHER) == 90 and val(L, 'WowCompanionSpellsDB.dpad["%s"]' % OTHER) is False,
      "(1) the other character's layouts untouched")
check(val(L, 'WowCompanionSpellsDB.faceBy["%s"][1]' % ME) == 403 and val(L, 'WowCompanionDB.bagBinds["%s"].zl1' % ME) == 6948,
      '(1) my skills untouched')
check(val(L, 'WowCompanionDB.compass') is False and val(L, 'WowCompanionDB.pqol["%s"]' % ME) == 7, '(1) settings and Polestar untouched')
check(any(d.get('type') == 'svkeep' for d in ds), '(6) the saved tables sent at once')

# (2) Skills, this character
L = world()
a, _ = reset(L, 22)
check(a and a['ok'] is True and a['n'] >= 8, '(2) skills answered ok', a)
for k in ("faceBy", "fightBy", "cmdBy", "binds", "smartOn", "sradials", "sradial", "castBar", "castBarFight", "castBarParty"):   # m176
    check(val(L, 'WowCompanionSpellsDB.%s["%s"]' % (k, ME)) is None, '(2) my %s gone' % k)
    check(val(L, 'WowCompanionSpellsDB.%s["%s"]' % (k, OTHER)) is not None, "(2) the other character's %s kept" % k)
check(val(L, 'WowCompanionDB.bagBinds["%s"]' % ME) is None, '(2) my item binds gone')
check(val(L, 'WowCompanionSpellsDB.cdLearn["%s"].s403' % ME) == 1.5 and val(L, 'WowCompanionSpellsDB.dpadZL["%s"]' % ME) is not None and
      val(L, 'WowCompanionSpellsDB.keyProbe') == 'm100' and val(L, 'WowCompanionSpellsDB.padLayout') == 'plain',
      '(2) learned cooldowns, one-time marks, probes and the app-mirrored keys untouched')
check(val(L, 'WowCompanionDB.lay["%s"].hud.s' % ME) == 110 and val(L, 'WowCompanionSpellsDB.dpad["%s"]' % ME) is False,
      '(2) my layouts untouched')

# (3) Settings: account-wide only
L = world()
a, _ = reset(L, 20)
check(a and a['ok'] is True and a['n'] == 4, '(3) settings answered ok, four switches', a)
check(all(val(L, 'WowCompanionDB.%s' % k) is None for k in ("compass", "hideTips", "aggroRings", "lootTop")), '(3) the Top screen switches back')
check(val(L, 'WowCompanionDB.lay["%s"].hud.s' % ME) == 110 and val(L, 'WowCompanionSpellsDB.faceBy["%s"][1]' % ME) == 403 and
      val(L, 'WowCompanionDB.layV') == 1 and val(L, 'WowCompanionDB.qtDefaults') == 2 and val(L, 'WowCompanionDB.compassCal.v') == 'cp18',
      "(3) no character's store, no one-time mark")

# (4) Everything, every character
L = world()
a, _ = reset(L, 27)
check(a and a['ok'] is True, '(4) everything + every character answered ok', a)
for who in (ME, OTHER):
    check(val(L, 'WowCompanionSpellsDB.faceBy["%s"]' % who) is None and val(L, 'WowCompanionDB.lay["%s"]' % who) is None and
          val(L, 'WowCompanionSpellsDB.dpad["%s"]' % who) is True, '(4) %s reset' % who)
    check(val(L, 'WowCompanionDB.pqol["%s"]' % who) == 7 and val(L, 'WowCompanionSpellsDB.cdLearn["%s"]' % who) is not None,
          '(4) %s: Polestar and learned facts kept' % who)
check(val(L, 'WowCompanionDB.compass') is None, '(4) the switches back too')

# (5) a fight refuses, nothing changed
L = world()
L.execute('function InCombatLockdown() return true end')
a, ds = reset(L, 23)
check(a and a['ok'] is False and a['why'] == 'combat', '(5) a fight refuses', a)
check(val(L, 'WowCompanionDB.lay["%s"].hud.s' % ME) == 110 and val(L, 'WowCompanionSpellsDB.dpad["%s"]' % OTHER) is False,
      '(5) nothing changed')
check(not any(d.get('type') == 'svkeep' for d in ds), '(5) nothing sent')

# (7) the add-on's lists = the app's ResetPlan
plan = open(os.path.join(ROOT, "companion", "app", "src", "main", "java", "com", "jrees", "wowcompanion", "ResetPlan.kt"), encoding="utf-8").read()
L = world()
for var, kinds in (("spells", ("layouts", "skills")), ("db", ("layouts", "skills", "settings"))):
    for kind in kinds:
        keys = L.eval('WowCompanion.dsfResetKeys.%s.%s' % (var, kind))
        lua = sorted(keys[i] for i in range(1, len(keys) + 1)) if keys is not None else []
        for k in lua:
            check(('"%s"' % k) in plan, '(7) %s.%s %s is in ResetPlan' % (var, kind, k))

print('%d passed, %d failed' % (passes, fails))
sys.exit(1 if fails else 0)
