"""Abandon Pet on the pet's wheels (Quests q68; the user, 2026-11-19: "I dont have a way to permanently abandon a pet"), off-device:
the REAL TapPad + Quests files under the stubbed game (tools/wowstub.py).

    python tools/test_pet_abandon.py

Checks: a hunter's pet (the game's own test: PetCanBeAbandoned() and PetHasActionBar(), the player a HUNTER) gets Abandon Pet right
after Dismiss Pet on Party mode's pet slot and on the target wheel on your pet; a taming quest's beast / a demon / a dead pet / an NPC
get none; the row asks first (cf "Abandon <name>? Permanent.") and runs by its FIXED id 63; greyed in a fight and the run refuses there;
the run refuses another pet; the game's own greyed Abandon entry is hidden on the pet's menu; the run calls C_PetInfo.PetAbandon() once
(never Blizzard's StaticPopup / menu code, no CVar / binding / macro). Needs lupa. Exit code 1 on any failure."""
import sys

from wowstub import SRC, boot, docs, nsent

fails = 0
passes = 0


def check(ok, name, got=''):
    global fails, passes
    print(('PASS ' if ok else 'FAIL ') + name + ((' -> ' + str(got)) if got != '' else ''))
    if ok:
        passes += 1
    else:
        fails += 1


PRE = r'''
SPELLNAME[6991] = "Feed Pet"; SPELLNAME[2641] = "Dismiss Pet"; SPELLNAME[136] = "Mend Pet"; SPELLNAME[5149] = "Beast Training"
KNOWN = { [6991] = true, [2641] = true, [136] = true, [5149] = true }
function IsPlayerSpell(id) return KNOWN[id] == true end
function IsSpellKnown(id) return false end
function UnitIsUnit(a, b)
    if a == b then return true end
    local x, y = UNITS[a], UNITS[b]
    return x ~= nil and rawequal(x, y)
end
function UnitIsPlayer(u) local x = UNITS[u]; return x ~= nil and x.player == true end
CLASS = "HUNTER"
CAN_ABANDON = true; HAS_BAR = true
function PetCanBeAbandoned() return UNITS.pet ~= nil and CAN_ABANDON end
function PetHasActionBar() return UNITS.pet ~= nil and HAS_BAR end
ABANDONED = 0
C_PetInfo = { PetAbandon = function(...) ABANDONED = ABANDONED + 1; ABANDON_NARGS = select('#', ...) end }
UnitPopupManager = { GetMenu = function(_, which) MENUWHICH = which; return { GetEntries = function() return {} end } end }
'''
Q = boot(["safety", "tappad", "quests"], pre=PRE)

check("local QV = \"q70\"" in SRC["quests"] and "q70 THE WHEEL'S CONFIRMED RUNS LAND" in SRC["quests"] and "q68 ABANDON PET" in SRC["quests"] and "q67 THE 1489 TEST BUILD MERGE" in SRC["quests"],
      "Quests says q70 (q68, q67 kept below it)")


def read(unit, src):
    n0 = nsent(Q)
    Q.eval('WowCompanion.pmenu.read')(unit, src)
    pm = docs(Q, n0, 'pmenu')
    return pm[-1] if pm else {}


def rows(d):
    return [(x['t'], x.get('k')) for x in d.get('items', [])]


def ab(d):
    return next((x for x in d.get('items', []) if x.get('k') == 'PetAbandon'), None)


def run(i):
    n0 = nsent(Q)
    Q.eval('WowCompanion.pmenu.run')(i)
    acks = [a for a in docs(Q, n0, 'guestack') if a.get('op') == 'pmenurun']
    return acks[-1] if acks else {}


# 1. a hunter's pet, Party mode's pet slot: Abandon Pet right after Dismiss Pet, asking first, its fixed id 63, its icon
Q.execute('UNITS.pet = { name = "Rex", alive = true, guid = "Pet-0-1-1-1-1-AAAA" }')
d = read("pet", "party")
check([k for _, k in rows(d)] == ["PetFeed", "PetDismiss", "PetAbandon", "PetMend", "PetTrain"],
      "a hunter's pet: Abandon Pet right after Dismiss Pet", rows(d))
a = ab(d) or {}
check(a.get('t') == "Abandon Pet" and a.get('en') == 1 and a.get('i') == 63 and a.get('sc') is None,
      "the row: 'Abandon Pet', live, run id 63 (no secure cell)", a)
check(a.get('cf') == "Abandon Rex? Permanent.", "it asks first: 'Abandon Rex? Permanent.'", a.get('cf'))
check(a.get('ic') == 130000 + 1515, "its picture: Tame Beast's (the game's spell icon)", a.get('ic'))

# 2. the target wheel on your pet: the same row
Q.execute('UNITS.target = UNITS.pet')
check(ab(read("target", "radial")) is not None, "the target wheel on your pet offers Abandon Pet too")

# 3. the run: C_PetInfo.PetAbandon() once, no pet number (the summoned pet), the run answers ok
Q.execute('ABANDONED = 0')
read("pet", "party")
r = run(63)
check(r.get('ok') is True and Q.eval('ABANDONED') == 1 and Q.eval('ABANDON_NARGS') == 0,
      "the run: C_PetInfo.PetAbandon() once, for the pet out", (r, Q.eval('ABANDONED')))

# 3b. a build without C_PetInfo.PetAbandon: the global PetAbandon()
Q.execute('C_PetInfo = nil; OLDABANDON = 0; function PetAbandon() OLDABANDON = OLDABANDON + 1 end')
read("pet", "party")
check(run(63).get('ok') is True and Q.eval('OLDABANDON') == 1, "no C_PetInfo.PetAbandon: the global PetAbandon()")
Q.execute("C_PetInfo = { PetAbandon = function(...) ABANDONED = ABANDONED + 1; ABANDON_NARGS = select('#', ...) end }; PetAbandon = nil")

# 4. in a fight: greyed, and the run refuses (nothing abandoned)
Q.execute('ABANDONED = 0')
read("pet", "party")
Q.execute('COMBAT = true')
check((ab(read("pet", "party")) or {}).get('en') == 0, "in a fight Abandon Pet is greyed (en 0)")
r = run(63)
check(r.get('ok') is False and r.get('why') == "In combat" and Q.eval('ABANDONED') == 0, "in a fight the run refuses: In combat", r)
check(len(Q.eval('VIOL') and list(Q.eval('VIOL').values())) == 0, "no protected call blocked in the fight", list(Q.eval('VIOL').values()))
Q.execute('COMBAT = false')

# 5. another pet out since the menu was read: the run refuses
read("pet", "party")
Q.execute('UNITS.pet = { name = "Kitty", alive = true, guid = "Pet-0-1-1-1-2-BBBB" }')
r = run(63)
check(r.get('ok') is False and Q.eval('ABANDONED') == 0, "another pet since the read: the run refuses", r)
# the same name, another GUID (a second "Rex"): refused too
Q.execute('UNITS.pet = { name = "Rex", alive = true, guid = "Pet-0-1-1-1-1-AAAA" }')
read("pet", "party")
Q.execute('UNITS.pet.guid = "Pet-0-1-1-1-9-CCCC"')
check(run(63).get('ok') is False and Q.eval('ABANDONED') == 0, "the same name, another GUID: refused")
Q.execute('UNITS.pet.guid = "Pet-0-1-1-1-1-AAAA"')
# q70: another unit's menu read since (the Yes was for THIS pet): id 63 still abandons the pet the pet menu named, checked again
read("player", None)
r = run(63)
check(r.get('ok') is True and Q.eval('ABANDONED') == 1, "q70: another menu read since: id 63 resolves again from the pet the menu named", r)
# ... and once done, or past ABANDON_TTL, id 63 runs nothing and SAYS so ("Menu gone")
Q.execute('ABANDONED = 0')
r = run(63)
check(r.get('ok') is False and r.get('why') == "Menu gone" and Q.eval('ABANDONED') == 0, "q70: done once: a second 63 answers Menu gone", r)
read("pet", "party")
Q.execute('NOW = NOW + WowCompanion.pmenu.ABANDON_TTL + 1')
r = run(63)
check(r.get('ok') is False and r.get('why') == "Menu gone" and Q.eval('ABANDONED') == 0, "q70: past ABANDON_TTL: Menu gone, nothing abandoned", r)

# 6. not a hunter's pet: no row (a taming quest's beast / a demon: PetCanBeAbandoned false; no pet bar; another class)
Q.execute('CAN_ABANDON = false')
check(ab(read("pet", "party")) is None and ("Dismiss Pet", "PetDismiss") in rows(read("pet", "party")),
      "PetCanBeAbandoned false (a taming quest's beast, a demon): Dismiss only, no Abandon")
Q.execute('CAN_ABANDON = true; HAS_BAR = false')
check(ab(read("pet", "party")) is None, "no pet action bar: no Abandon (the game's own CanShow)")
Q.execute('HAS_BAR = true; CLASS = "WARLOCK"')
check(ab(read("pet", "party")) is None, "a warlock: no Abandon")
Q.execute('CLASS = "HUNTER"; PetCanBeAbandoned = nil')
check(ab(read("pet", "party")) is None, "a client without PetCanBeAbandoned: no Abandon")
Q.execute('function PetCanBeAbandoned() return UNITS.pet ~= nil and CAN_ABANDON end')

# 7. a dead pet, an NPC, no pet: none
Q.execute('UNITS.pet.alive = false')
check(ab(read("pet", "party")) is None, "the pet dead: no Abandon (q60: no pet rows)")
Q.execute('UNITS.pet.alive = true; UNITS.target = { name = "Kobold", alive = true }')
check(ab(read("target", "radial")) is None, "an NPC's wheel: no Abandon")
Q.execute('UNITS.pet = nil')
check(ab(read("player", None)) is None, "no pet out: your own menu has no Abandon")

# 8. the game's own Abandon entry on the pet's menu is hidden (ours leads); its Dismiss stays hidden too
Q.execute('''
UNITS.pet = { name = "Rex", alive = true, guid = "Pet-0-1-1-1-1-AAAA" }
UnitPopupPetDismissButtonMixin = { GetText = function() return "Dismiss" end }
UnitPopupPetAbandonButtonMixin = { GetText = function() return "Release Pet" end }
UnitPopupRaidTargetIcon1ButtonMixin = { GetText = function() return "Star" end }
UnitPopupManager = { GetMenu = function(_, which) MENUWHICH = which
    return { GetEntries = function() return { UnitPopupPetDismissButtonMixin, UnitPopupPetAbandonButtonMixin,
                                              UnitPopupRaidTargetIcon1ButtonMixin } end } end }
WowCompanion.pmenu.mix = nil''')
d = read("pet", "party")
names = [x['t'] for x in d['items']]
check("Release Pet" not in names and "Dismiss" not in names and names.count("Abandon Pet") == 1 and "Star" in names,
      "the game's own Abandon / Dismiss hidden on the pet's menu, ours once, the rest kept", names)
check(all(x.get('i') != 63 for x in d['items'] if x.get('k') != 'PetAbandon'), "no game entry ever takes id 63")

# 9. the code: a plain call only - no Blizzard popup / menu code, nothing saved
body = SRC["quests"][SRC["quests"].index("-- q68 ABANDON PET"):SRC["quests"].index("-- q62 THE PET'S AUTOCAST ON ITS WHEELS")]
body = "\n".join(ln.split("--")[0] for ln in body.splitlines())          # the code, not its notes
check(not any(w in body for w in ("StaticPopup", "OnClick", "SetCVar", "SetBinding", "CreateMacro", "EditMacro", "RunMacro",
                                    "SaveBindings", "CastSpell")), "Abandon calls nothing of Blizzard's UI and saves nothing")
check("C_PetInfo.PetAbandon()" in body and "InCombatLockdown" in body, "Abandon = C_PetInfo.PetAbandon(), refused in a fight")

print("%d passed, %d failed" % (passes, fails))
sys.exit(1 if fails else 0)
