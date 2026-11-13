"""The pet's wheel carries its actions (Quests q56; the user, 2026-11-13: "The pet inspect radial should include some of the pet
actions like dismiss or feed pet."), off-device: the REAL TapPad + Quests files under the stubbed game (tools/wowstub.py).

    python tools/test_pet_radial.py

Checks: the pet rows lead the pet's menu (Party mode's pet cell, the target wheel on your pet), only known spells, Mend alive / Revive
dead, Revive alone on your own menu with no pet and on your dead pet's body, Feed greyed in a fight, each row's icon and pfocus column,
the secure cells' /cast lines, and the re-read after a cell saying again = 1. Needs lupa. Exit code 1 on any failure."""
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
SPELLNAME[6991] = "Feed Pet"; SPELLNAME[2641] = "Dismiss Pet"; SPELLNAME[982] = "Revive Pet"; SPELLNAME[136] = "Mend Pet"
SPELLNAME[3111] = "Mend Pet"; SPELLNAME[5149] = "Beast Training"
KNOWN = { [6991] = true, [2641] = true, [982] = true, [3111] = true, [5149] = true }     -- Mend Pet known by its rank 2 only
function IsPlayerSpell(id) return KNOWN[id] == true end
function IsSpellKnown(id) return false end
function UnitIsUnit(a, b)
    if a == b then return true end
    local x, y = UNITS[a], UNITS[b]
    return x ~= nil and rawequal(x, y)
end
function UnitIsPlayer(u) local x = UNITS[u]; return x ~= nil and x.player == true end
UnitPopupManager = { GetMenu = function(_, which) MENUWHICH = which; return { GetEntries = function() return {} end } end }
'''
Q = boot(["safety", "tappad", "quests"], pre=PRE)

check('local QV = "q57"' in SRC["quests"], "Quests says q57")


def read(unit, src):
    n0 = nsent(Q)
    Q.eval('WowCompanion.pmenu.read')(unit, src)
    pm = docs(Q, n0, 'pmenu')
    return pm[-1] if pm else {}


def rows(d):
    return [(x['t'], x.get('sc'), x.get('en')) for x in d.get('items', [])]


# 1. Party mode's pet cell, the pet alive: Feed, Dismiss, Mend (rank 2 counts), Training - in that order, cols 5, 6, 8, 9
Q.execute('UNITS.pet = { name = "Wolf", alive = true }')
d = read("pet", "party")
check(rows(d) == [("Feed Pet", 5, 1), ("Dismiss Pet", 6, 1), ("Mend Pet", 8, 1), ("Beast Training", 9, 1)],
      "the pet alive: Feed, Dismiss, Mend, Beast Training lead its menu (pfocus 5 / 6 / 8 / 9)", rows(d))
check(all(x.get('ic') == 130000 + i for x, i in zip(d['items'], (6991, 2641, 136, 5149))), "each row carries its spell's icon (ic)",
      [x.get('ic') for x in d['items']])
check(all(x.get('k', '').startswith('Pet') for x in d['items']), "the rows' keys are Pet* (no game menu icon mix-up)", [x.get('k') for x in d['items']])

# 2. the target wheel on your pet: the same rows (UnitIsUnit target / pet)
Q.execute('UNITS.target = UNITS.pet')
d = read("target", "radial")
check([r[0] for r in rows(d)] == ["Feed Pet", "Dismiss Pet", "Mend Pet", "Beast Training"], "the target wheel on your pet: the same rows", rows(d))

# 3. another unit (an NPC, alive): none
Q.execute('UNITS.target = { name = "Kobold", alive = true }')
check(rows(read("target", "radial")) == [], "an NPC's wheel: no pet rows")

# 4. a spell not known: no row
Q.execute('KNOWN[5149] = nil; UNITS.target = UNITS.pet')
check("Beast Training" not in [r[0] for r in rows(read("target", "radial"))], "Beast Training not known: no row")
Q.execute('KNOWN[5149] = true')

# 5. in a fight: Feed greyed, the rest live
Q.execute('COMBAT = true')
check(rows(read("pet", "party"))[0] == ("Feed Pet", 5, 0), "in a fight Feed Pet is greyed (en 0)", rows(read("pet", "party"))[:1])
Q.execute('COMBAT = false')

# 6. the pet dead (still out): Revive alone
Q.execute('UNITS.pet.alive = false')
check(rows(read("pet", "party")) == [("Revive Pet", 7, 1)], "the pet dead: Revive Pet alone (pfocus 7)", rows(read("pet", "party")))

# 7. the pet gone: your own menu offers Revive; its body (dead, its last name, not a player) too; another dead NPC not
Q.execute('UNITS.pet = nil; UNITS.target = { name = "Wolf", alive = false }')
check(rows(read("player", None)) == [("Revive Pet", 7, 1)], "no pet out: your own menu offers Revive Pet", rows(read("player", None)))
check(rows(read("target", "radial")) == [("Revive Pet", 7, 1)], "your dead pet's body (its last name): Revive Pet", rows(read("target", "radial")))
Q.execute('UNITS.target = { name = "Kobold", alive = false }')
check(rows(read("target", "radial")) == [], "another dead NPC: no pet rows")
Q.execute('UNITS.target = { name = "Wolf", alive = false, player = true }')
check(rows(read("target", "radial")) == [], "a dead PLAYER of the pet's name: no pet rows")
Q.execute('KNOWN[982] = nil')
check(rows(read("player", None)) == [], "Revive Pet not known: nothing on your own menu")
Q.execute('KNOWN[982] = true')

# 7b. q57 (review): YOURSELF as the target, no pet out: the target wheel offers Revive too (ctx keeps unit "target" for SELF)
Q.execute('UNITS.target = UNITS.player')
check(rows(read("target", "radial")) == [("Revive Pet", 7, 1)], "yourself as the target, no pet out: Revive Pet", rows(read("target", "radial")))

# 7c. q57 (review): a fresh session (no name kept), the pet seen only by the game's pet event, dies with NO menu read while it
# lived (the Classic client drops the pet unit): its body still offers Revive
Q.execute('WowCompanion.pmenu.petLast = nil; UNITS.pet = { name = "Fang", alive = true }; fire("UNIT_PET", "player")')
check(Q.eval('WowCompanion.pmenu.petLast') == "Fang", "UNIT_PET keeps the pet's name (no menu read)", Q.eval('WowCompanion.pmenu.petLast'))
Q.execute('UNITS.pet = nil; fire("UNIT_PET", "player"); UNITS.target = { name = "Fang", alive = false }')
check(rows(read("target", "radial")) == [("Revive Pet", 7, 1)], "the body of a pet never menu-read: Revive Pet", rows(read("target", "radial")))
Q.execute('WowCompanion.pmenu.petLast = nil; UNITS.pet = { name = "Claw", alive = true }; fire("UNIT_PET", "party1")')
check(Q.eval('WowCompanion.pmenu.petLast') is None, "another unit's UNIT_PET is ignored")
Q.execute('fire("PLAYER_ENTERING_WORLD")')
check(Q.eval('WowCompanion.pmenu.petLast') == "Claw", "PLAYER_ENTERING_WORLD keeps the pet's name (after a login / reload)")
Q.execute('UNITS.pet = nil; UNITS.target = { name = "Wolf", alive = false }; WowCompanion.pmenu.petLast = "Wolf"')

# 8. a pet out: your own menu has no pet rows
Q.execute('UNITS.pet = { name = "Wolf", alive = true }')
check(rows(read("player", None)) == [], "a pet out: your own menu has no pet rows")
Q.execute('UNITS.target = UNITS.player')
check(rows(read("target", "radial")) == [], "a pet out: yourself as the target has no pet rows")

# 9. the secure cells: /cast by the spell's name (top rank: no rank id), set by the row's init (out of combat, TapPad's build)
Q.execute('''
CELLS = {}
for col = 0, 9 do
    local b = { a = {} }
    function b:SetAttribute(k, v) self.a[k] = v end
    WowCompanion.pad.rows.pfocus.init(b, col)
    CELLS[col] = b.a
end''')
got = {c: (Q.eval('CELLS[%d].type' % c), Q.eval('CELLS[%d].macrotext' % c)) for c in range(5, 10)}
check(got == {5: ("macro", "/cast Feed Pet"), 6: ("macro", "/cast Dismiss Pet"), 7: ("macro", "/cast Revive Pet"),
              8: ("macro", "/cast Mend Pet"), 9: ("macro", "/cast Beast Training")}, "pfocus cells 5-9: the game's /cast of each spell", got)
check(Q.eval('WowCompanion.pad.rows.pfocus.n') == 10, "the pfocus row has 10 cells")
check(Q.eval('CELLS[2].macrotext') == "/inspect" and Q.eval('CELLS[4].macrotext') == "/trade" and Q.eval('CELLS[3].type') == "focus",
      "cols 2 / 3 / 4 unchanged (/inspect, focus, /trade)")

# 10. the re-read after a cell's click says again = 1 (the wheel never reopens by it)
read("pet", "party")
n0 = nsent(Q)
Q.eval('WowCompanion.pad.rows.pfocus.onClick')(5)
Q.execute('advance(1)')
pm = docs(Q, n0, 'pmenu')
check(len(pm) >= 1 and pm[-1].get('again') == 1 and pm[-1].get('unit') == 'pet', "the re-read after a cell: the pet's menu, again = 1",
      pm and {k: pm[-1].get(k) for k in ('unit', 'again')})

# 11. no Blizzard menu code / open call in the pet rows' code
body = SRC["quests"][SRC["quests"].index("WC.pmenu.PET = {"):SRC["quests"].index("function WC.pmenu.read(")]
check(not any(w in body for w in ("CastSpell(", "CastSpellByName", "PetDismiss(", "ShowUIPanel", "ToggleFrame", "SetBinding", "CreateMacro",
                                    "SetCVar")), "the pet rows call nothing protected or Blizzard's (only reads)")

print("%d passed, %d failed" % (passes, fails))
sys.exit(1 if fails else 0)
