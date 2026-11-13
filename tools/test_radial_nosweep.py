"""The spell radial WITHOUT a cooldown sweep (Radial.lua r18 / q1b and on; the user's pick 2026-11-13 after the 1.0.19 bisect: the
re-send at every SPELL_UPDATE_COOLDOWN was the line behind the loot window's ADDON_ACTION_FORBIDDEN SetPreferredGamepadInteractTarget),
off-device: the REAL Safety / TapPad / Radial files under tools/wowstub.py's stubbed game, Lua 5.1 as the game's.

    python tools/test_radial_nosweep.py

Checks: no Radial.lua frame listens to SPELL_UPDATE_COOLDOWN; that event sends nothing; a cast of a spell on a radial sends ONE
`sradialcd` (a moment later) with its cooldown; a secret value sends no `cd`; a spell on no radial sends nothing; the list still comes
on its events (login, an edit). Works on the single radial (q1b) and on the several radials (q3). Exit code 1 on a failure."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from lupa import lua51 as lupa                    # noqa: E402
from wowstub import ADDONS, GAME, docs, nsent     # noqa: E402

RADIAL = os.path.join(ADDONS, "WowCompanionSpells", "Radial.lua")
FILES = [os.path.join(ADDONS, "WowCompanion", "Safety.lua"), os.path.join(ADDONS, "WowCompanion", "TapPad.lua"), RADIAL]
SRC = open(RADIAL, encoding="utf-8").read()
fails = passes = 0


def check(ok, name, got=''):
    global fails, passes
    if ok:
        passes += 1; print("PASS", name)
    else:
        fails += 1; print("FAIL", name, got)


def world():
    L = lupa.LuaRuntime(unpack_returned_tuples=True)
    L.execute(GAME)
    L.execute('''
CDS = {}
C_Spell.GetSpellCooldown = function(id) local c = CDS[id]; if c == "secret" then return { startTime = SECRETV, duration = SECRETV } end
    return c or { startTime = 0, duration = 0 } end
C_Spell.GetSpellInfo = function(id) return { name = SPELLNAME[id], iconID = 130000 + id, spellID = id } end
''')
    load = L.eval('function(src, name) local f, e = loadstring(src, name); if not f then error(e) end; return f end')
    for p in FILES:
        load(open(p, encoding="utf-8").read(), '@' + os.path.basename(p))(os.path.basename(p))
    L.execute('fire("PLAYER_LOGIN"); fire("PLAYER_ENTERING_WORLD", true, false); runTimers(NOW + 10)')
    return L


SEVERAL = 'local SRV = "q3"' in SRC


def put(L, id):
    # one spell onto the radial, through its own API (the Controller room's way)
    if SEVERAL:
        return L.eval('(function() local sr = WowCompanion.spellRadial; local r = sr.slots()[1] or sr.new(); '
                      'return sr.put(r, sr.count(r) + 1, %d) end)()' % id)
    return L.eval('WowCompanion.spellRadial.put(1, %d)' % id)


# ---------------------------------------------------------------- 1. the source: no sweep left
code = "\n".join(l.split("--", 1)[0] for l in SRC.splitlines())       # comments out: only code counts
check("SPELL_UPDATE_COOLDOWN" not in code, "Radial.lua's code names no SPELL_UPDATE_COOLDOWN (comments may)")
check('RegisterUnitEvent("UNIT_SPELLCAST_SUCCEEDED", "player")' in code, "the cast event is the player's only (RegisterUnitEvent)")

L = world()
spell_frames = L.eval('(function() local n = 0; for _ in ipairs(EVT["SPELL_UPDATE_COOLDOWN"] or {}) do n = n + 1 end return n end)()')
check(spell_frames == 0, "SPELL_UPDATE_COOLDOWN has no listener at all in this world (Safety + TapPad + Radial)", spell_frames)
check(L.eval('ERRS[1]') is None, "the files load and log in with no error", L.eval('ERRS[1]'))

# ---------------------------------------------------------------- 2. the list on its events
check(put(L, 403) is True, "a spell goes onto the radial (out of combat)")
L.execute('runTimers(NOW + 1)')
lists = docs(L, 0, 'sradial')
check(lists, "the edit sends the list", len(lists))

# ---------------------------------------------------------------- 3. SPELL_UPDATE_COOLDOWN sends nothing
n0 = nsent(L)
L.execute('CDS[403] = { startTime = NOW, duration = 6 }')
for _ in range(20):
    L.execute('fire("SPELL_UPDATE_COOLDOWN"); runTimers(NOW + 0.5)')
check(nsent(L) == n0, "20 SPELL_UPDATE_COOLDOWNs (a running cooldown) send nothing at all", nsent(L) - n0)

# ---------------------------------------------------------------- 4. one cast = one sradialcd with the cooldown
n0 = nsent(L)
L.execute('CDS[403] = { startTime = NOW, duration = 6 }')
L.execute('fire("UNIT_SPELLCAST_SUCCEEDED", "player", "Cast-1", 403)')
check(nsent(L) == n0, "nothing at the event itself (the read comes a moment later)", nsent(L) - n0)
L.execute('runTimers(NOW + 1)')
cds = docs(L, n0, 'sradialcd')
check(len(cds) == 1 and cds[0]['id'] == 403 and cds[0].get('cd', {}).get('d') == 6 and 5 <= cds[0]['cd'].get('l', 0) <= 6,
      "ONE sradialcd: id 403, cd { d = 6, l ~ 6 }", cds)
check(not docs(L, n0, 'sradial'), "a cast does not re-send the list", docs(L, n0, 'sradial'))

# two casts in the same moment: one read each, one flush
n0 = nsent(L)
L.execute('fire("UNIT_SPELLCAST_SUCCEEDED", "player", "Cast-2", 403); fire("UNIT_SPELLCAST_SUCCEEDED", "player", "Cast-3", 403); runTimers(NOW + 1)')
check(len(docs(L, n0, 'sradialcd')) == 1, "the same spell twice in a moment: one sradialcd", docs(L, n0, 'sradialcd'))

# ---------------------------------------------------------------- 5. a secret value: no cd, never a guess
n0 = nsent(L)
L.execute('COMBAT = true; CDS[403] = "secret"; fire("UNIT_SPELLCAST_SUCCEEDED", "player", "Cast-4", 403); runTimers(NOW + 1); COMBAT = false')
cds = docs(L, n0, 'sradialcd')
check(len(cds) == 1 and 'cd' not in cds[0] and cds[0].get('fight') is True, "a secret cooldown in a fight: sradialcd with NO cd (fight)", cds)
check(L.eval('ERRS[1]') is None, "no error reading a secret", L.eval('ERRS[1]'))

# ---------------------------------------------------------------- 6. other casts: nothing
n0 = nsent(L)
L.execute('fire("UNIT_SPELLCAST_SUCCEEDED", "player", "Cast-5", 331); fire("UNIT_SPELLCAST_SUCCEEDED", "target", "Cast-6", 403); runTimers(NOW + 1)')
check(not docs(L, n0, 'sradialcd'), "a spell on no radial, or another unit's cast: nothing", docs(L, n0, 'sradialcd'))
L.execute('fire("UNIT_SPELLCAST_SUCCEEDED", "player", "Cast-7", SECRETV); runTimers(NOW + 1)')
check(not docs(L, n0, 'sradialcd') and L.eval('ERRS[1]') is None, "a secret spell id: nothing, no error", L.eval('ERRS[1]'))

# ---------------------------------------------------------------- 7. the GCD alone is not a cooldown
n0 = nsent(L)
L.execute('CDS[403] = { startTime = NOW, duration = 1.5 }; fire("UNIT_SPELLCAST_SUCCEEDED", "player", "Cast-8", 403); runTimers(NOW + 1)')
cds = docs(L, n0, 'sradialcd')
check(len(cds) == 1 and 'cd' not in cds[0], "the global cooldown only: sradialcd with no cd (the app clears it)", cds)

print("\n%d passed, %d failed" % (passes, fails))
if fails == 0:
    print("ALL PASS")
sys.exit(1 if fails else 0)
