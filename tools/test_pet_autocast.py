"""The pet's autocast on its wheels (Quests q62; the user, 2026-11-16: "hunters need to be able to visually see when their pets
abilities are active like growl, and a way to turn it off from those radials"), off-device: the REAL TapPad + Quests files under the
stubbed game (tools/wowstub.py).

    python tools/test_pet_autocast.py

Checks: only the pet bar's abilities that may cast itself (GetPetActionInfo autoCastAllowed; never a command token) reach the
{"type":"petauto"} document and the pet's wheel (Party mode's pet slot, the target wheel on your pet) after the q56 rows, with their
state (au), slot (as) and cells (sc = pfocus 10-19, the game's pet action; ac = pfocus 20-29, the game's own /petautocasttoggle on our
button); a document only when something changed (PET_BAR_UPDATE / PET_BAR_UPDATE_COOLDOWN / UNIT_PET / the pet's health); a toggle cell's
click asks for a scan, never the menu again; IN A FIGHT nothing secure is written (a new pet's toggle not offered until the fight ends,
then built again); no pet / a dead pet; a secret value keeps the last state; a warlock's demon; nothing protected or saved anywhere
(no SetBinding / CreateMacro / SetCVar / TogglePetAutocast from add-on code). Needs lupa. Exit code 1 on any failure."""
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
UnitPopupManager = { GetMenu = function(_, which) return { GetEntries = function() return {} end } end }
-- the game's pet bar: slot -> name, texture, isToken, isActive, autoCastAllowed, autoCastEnabled, spellID
PETBAR = {}
function GetPetActionInfo(i) local x = PETBAR[i]; if not x then return nil end; return x[1], x[2], x[3], x[4], x[5], x[6], x[7] end
NUM_PET_ACTION_SLOTS = 10
TOGGLED = {}
function TogglePetAutocast(i) TOGGLED[#TOGGLED + 1] = i end
function hunterPet()
    UNITS.pet = { name = "Wolf", alive = true }
    PETBAR = {
        [1] = { "PET_ACTION_ATTACK", "PET_ATTACK_TEXTURE", false, false, false, false, nil },
        [2] = { "PET_ACTION_FOLLOW", "PET_FOLLOW_TEXTURE", false, true, false, false, nil },
        [4] = { "Growl", 132270, false, false, true, true, 2649 },
        [5] = { "Bite", 132278, false, false, true, false, 17253 },
        [6] = { "Dash", 132120, false, false, false, false, 23099 },      -- may not cast itself: never ours
        [8] = { "PET_MODE_PASSIVE", "PET_PASSIVE_TEXTURE", false, false, false, false, nil },
    }
end
'''
Q = boot(["safety", "tappad", "quests"], pre=PRE)

check('local QV = "q70"' in SRC["quests"], "Quests says q70 (q65 kept below it)")
check('the user, 2026-11-16: "hunters need to be able to visually see when their pets abilities are active like growl' in SRC["quests"],
      "the q62 header quotes the user")


def pump(script=''):
    n0 = nsent(Q)
    if script:
        Q.execute(script)
    Q.execute('advance(1)')
    return docs(Q, n0, 'petauto')


def read(unit, src):
    n0 = nsent(Q)
    Q.eval('WowCompanion.pmenu.read')(unit, src)
    pm = docs(Q, n0, 'pmenu')
    return pm[-1] if pm else {}


def cell(col):
    return {k: Q.eval('(function() local b = WowCompanion.pad.rows.pfocus.cells[%d]; return b and b.a["%s"] end)()' % (col, k))
            for k in ('type', 'action', 'macrotext')}


# 0. THE USER'S CHARACTER (a level 6 Shaman, no pet): one quiet document at the login (pet 0), nothing after; its own menu has no pet
#    rows; the pfocus cells it uses (0-4) are as before
pa = pump('fire("PLAYER_ENTERING_WORLD", true, false)')
check(len(pa) == 1 and pa[0].get('pet') == 0 and pa[0].get('s') in ([], {}, None), "the Shaman's login: one petauto, pet 0", pa)
check(pump('fire("PET_BAR_UPDATE"); fire("PLAYER_REGEN_DISABLED"); fire("PLAYER_REGEN_ENABLED"); fire("SPELLS_CHANGED")') == [],
      "the Shaman: no further petauto documents (nothing changed)")
check(pump('COMBAT = true; fire("PLAYER_REGEN_DISABLED")') == [] and pump('COMBAT = false; fire("PLAYER_REGEN_ENABLED")') == [],
      "the Shaman: a real fight's start and end send nothing either (no pet: no fight flag)")
check([x for x in read("player", None).get('items', []) if x.get('k') in ("PetAuto", "PetFeed")] == [], "the Shaman's own menu: no pet rows")
check(cell(0).get('type') == 'focus' and cell(1).get('type') == 'clearfocus' and cell(2).get('macrotext') == '/inspect'
      and cell(4).get('macrotext') == '/trade' and all(cell(c).get('type') is None for c in range(10, 30)),
      "the Shaman's pfocus: Set / Clear Focus, /inspect, /trade as before; the pet cells 10-29 empty")

# 1. a hunter's pet out: Growl (on) and Bite (off) only - no command token, no ability that cannot cast itself
pa = pump('hunterPet(); fire("UNIT_PET", "player")')
last = pa[-1] if pa else {}
check(last.get('pet') == 1 and [(x['s'], x['n'], x['on']) for x in last.get('s', [])] == [(4, "Growl", 1), (5, "Bite", 0)],
      "petauto: Growl (slot 4, on) and Bite (slot 5, off) only", last)
check([(x.get('sc'), x.get('ac')) for x in last.get('s', [])] == [(13, 23), (14, 24)],
      "petauto: their cells once built (cast pfocus 13 / 14, toggle 23 / 24)", [(x.get('sc'), x.get('ac')) for x in last.get('s', [])])
check(Q.eval('WowCompanion.pad.rows.pfocus.n') == 31, "the pfocus row has 31 cells (q65: col 30 the quest item; no new pad row)", Q.eval('WowCompanion.pad.rows.pfocus.n'))

# 2. the cells: the game's pet action for the slot (CastPetAction), the game's own /petautocasttoggle by the ability's name
check(cell(13) == {'type': 'pet', 'action': 4, 'macrotext': None}, "pfocus 13 = the pet bar's slot 4 (type pet, action 4)", cell(13))
check(cell(23) == {'type': 'macro', 'action': None, 'macrotext': '/petautocasttoggle Growl'}, "pfocus 23 = /petautocasttoggle Growl", cell(23))
check(cell(24).get('macrotext') == '/petautocasttoggle Bite', "pfocus 24 = /petautocasttoggle Bite", cell(24))
check(all(cell(c).get('type') is None for c in (10, 11, 15, 17, 20, 21, 25, 27)),
      "no cell for a command token or an ability that cannot cast itself", [cell(c).get('type') for c in (10, 11, 15, 17, 20, 21, 25, 27)])
check(cell(2).get('macrotext') == '/inspect' and cell(4).get('macrotext') == '/trade' and cell(5).get('macrotext') == '/cast Feed Pet',
      "pfocus 0-9 unchanged (/inspect, /trade, the q56 /casts)")

# 3. the pet's wheel (Party mode's pet slot): the q56 rows, then the abilities with their state, slot, cells and icons
d = read("pet", "party")
rows = [(x['t'], x.get('k'), x.get('sc'), x.get('ac'), x.get('au'), x.get('as'), x.get('ic')) for x in d.get('items', [])]
check(rows == [("Feed Pet", "PetFeed", 5, None, None, None, 136991), ("Dismiss Pet", "PetDismiss", 6, None, None, None, 132641),
               ("Mend Pet", "PetMend", 8, None, None, None, 130136), ("Beast Training", "PetTrain", 9, None, None, None, 135149),
               ("Growl", "PetAuto", 13, 23, 1, 4, 132270), ("Bite", "PetAuto", 14, 24, 0, 5, 132278)],
      "the pet slot's wheel: Feed, Dismiss, Mend, Training, then Growl (on) and Bite (off)", rows)

# 4. the target wheel on your pet: the same abilities
Q.execute('UNITS.target = UNITS.pet')
d = read("target", "radial")
check([(x['t'], x.get('au')) for x in d.get('items', []) if x.get('k') == "PetAuto"] == [("Growl", 1), ("Bite", 0)],
      "the target wheel on your pet: Growl on, Bite off")
Q.execute('UNITS.target = { name = "Kobold", alive = true }')
check([x for x in read("target", "radial").get('items', []) if x.get('k') == "PetAuto"] == [], "an NPC's wheel: no pet abilities")
check([x for x in read("player", None).get('items', []) if x.get('k') == "PetAuto"] == [], "your own menu: no pet abilities")

# 5. the game turns Growl off (PET_BAR_UPDATE): one document saying so; nothing changed: no document
pa = pump('PETBAR[4][6] = false; fire("PET_BAR_UPDATE"); fire("PET_BAR_UPDATE"); fire("PET_BAR_UPDATE_COOLDOWN")')
check(len(pa) == 1 and [(x['n'], x['on']) for x in pa[0]['s']] == [("Growl", 0), ("Bite", 0)],
      "Growl off: ONE petauto document (the three events coalesced) saying so", pa)
check(pump('fire("PET_BAR_UPDATE_COOLDOWN"); fire("PET_BAR_UPDATE")') == [], "nothing changed: no document")

# 6. the toggle cell clicked (the app's tap): the game's /petautocasttoggle runs (secure macro text on our button), a scan follows,
#    the menu is NOT read again (the wheel stays open)
n0 = nsent(Q)
Q.execute('ACTIONS = {}; doClick(WowCompanion.pad.rows.pfocus.cells[23], "LeftButton", true); '
          'doClick(WowCompanion.pad.rows.pfocus.cells[23], "LeftButton", false); PETBAR[4][6] = true')
acts = [Q.eval('ACTIONS[%d].line or ACTIONS[%d].kind' % (i, i)) for i in range(1, int(Q.eval('#ACTIONS')) + 1)]
check(acts == ["/petautocasttoggle Growl"], "the toggle cell runs the game's /petautocasttoggle Growl", acts)
Q.execute('advance(2)')
check(docs(Q, n0, 'pmenu') == [], "no menu re-read after a toggle (the open wheel follows the petauto document)")
pa = docs(Q, n0, 'petauto')
check(len(pa) == 1 and pa[0]['s'][0]['on'] == 1, "the scan after the click: Growl on again", pa)

# 7. the cast cell: the game's pet action (CastPetAction of slot 4)
Q.execute('ACTIONS = {}; doClick(WowCompanion.pad.rows.pfocus.cells[13], "LeftButton", true); '
          'doClick(WowCompanion.pad.rows.pfocus.cells[13], "LeftButton", false)')
check(int(Q.eval('#ACTIONS')) == 1 and Q.eval('ACTIONS[1].kind') == "pet", "the cast cell = the game's pet action (type pet)",
      Q.eval('ACTIONS[1] and ACTIONS[1].kind'))

# 8. IN A FIGHT: the pet unchanged - its cells still offered (a secure click works in a fight); nothing secure written
Q.execute('COMBAT = true; VIOL = {}')
pa = pump('PETBAR[5][6] = true; fire("PET_BAR_UPDATE")')
check(pa and pa[-1].get('fight') == 1 and [(x['n'], x['on'], x.get('ac')) for x in pa[-1]['s']] == [("Growl", 1, 23), ("Bite", 1, 24)],
      "in a fight: Bite on, both toggles still offered", pa and pa[-1])

# 9. ... a NEW pet in the fight (a warlock's demon summoned mid-fight): its abilities shown, but no cell names them - none offered
pa = pump('UNITS.pet = { name = "Imp", alive = true }; PETBAR = { [4] = { "Firebolt", 135809, false, false, true, true, 3110 } }; '
          'fire("UNIT_PET", "player")')
f = pa[-1]['s'] if pa else []
check([(x['n'], x['on'], x.get('sc'), x.get('ac')) for x in f] == [("Firebolt", 1, None, None)],
      "a new pet in a fight: Firebolt shown (on), its cast / toggle NOT offered (the cells name Growl)", f)
d = read("pet", "party")
check([(x['t'], x.get('sc'), x.get('ac')) for x in d.get('items', []) if x.get('k') == "PetAuto"] == [("Firebolt", None, None)],
      "its wheel in the fight: Firebolt with no cells", [x for x in d.get('items', []) if x.get('k') == "PetAuto"])
check(cell(23).get('macrotext') == '/petautocasttoggle Growl', "the toggle cell is untouched in the fight", cell(23))
check(int(Q.eval('#VIOL')) == 0, "nothing secure written from add-on code in the fight (VIOL empty)",
      [Q.eval('VIOL[%d]' % i) for i in range(1, int(Q.eval('#VIOL')) + 1)])

# 10. the fight ends: the cells built again for the demon, the document names them
Q.execute('COMBAT = false')
pa = pump('fire("PLAYER_REGEN_ENABLED")')
check(pa and [(x['n'], x.get('sc'), x.get('ac')) for x in pa[-1]['s']] == [("Firebolt", 13, 23)] and pa[-1].get('fight') is None,
      "after the fight: Firebolt's cells built (13 / 23)", pa and pa[-1])
check(cell(23).get('macrotext') == '/petautocasttoggle Firebolt' and cell(24).get('type') is None,
      "pfocus 23 = /petautocasttoggle Firebolt, 24 empty again", (cell(23), cell(24)))

# 11. a warlock's imp, four abilities and no hunter spells: q66's Dismiss Pet (any live pet, the game's own /petdismiss), then the abilities
Q.execute('KNOWN = {}; PETBAR = { [1] = { "PET_ACTION_ATTACK", "PET_ATTACK_TEXTURE", false, false, false, false, nil }, '
          '[4] = { "Firebolt", 135809, false, false, true, true, 3110 }, [5] = { "Blood Pact", 136168, false, false, true, true, 6307 }, '
          '[6] = { "Fire Shield", 135806, false, false, true, false, 2947 }, [7] = { "Phase Shift", 136164, false, false, true, false, 4511 } }')
pump('fire("PET_BAR_UPDATE")')
d = read("pet", "party")
check([(x['t'], x.get('au'), x.get('as'), x.get('sc'), x.get('ac')) for x in d.get('items', [])] ==
      [("Dismiss Pet", None, None, 6, None), ("Firebolt", 1, 4, 13, 23), ("Blood Pact", 1, 5, 14, 24), ("Fire Shield", 0, 6, 15, 25),
       ("Phase Shift", 0, 7, 16, 26)],
      "a warlock's imp: Firebolt / Blood Pact on, Fire Shield / Phase Shift off, each with its cells", d.get('items'))

# 12. a secret value keeps the slot's last known state (never read through)
pa = pump('PETBAR[4][6] = SECRETV; fire("PET_BAR_UPDATE")')
check(pa == [] and int(Q.eval('#ERRS')) == 0, "a secret autoCastEnabled: Firebolt stays on (no new document, no error)", pa)
pa = pump('PETBAR[4][1] = SECRETV; fire("PET_BAR_UPDATE")')
check(pa and [x['n'] for x in pa[-1]['s']] == ["Blood Pact", "Fire Shield", "Phase Shift"], "a secret name: that slot left out", pa and pa[-1])
Q.execute('PETBAR[4][1] = "Firebolt"; PETBAR[4][6] = true')
# review: the last known state is the ABILITY's - another ability in that slot (another pet) with a secret state starts off, never
# inheriting Firebolt's "on"
pa = pump('PETBAR[4] = { "Torment", 136160, false, false, true, SECRETV, 3716 }; fire("PET_BAR_UPDATE")')
check(pa and [(x['n'], x['on']) for x in pa[-1]['s'] if x['s'] == 4] == [("Torment", 0)],
      "a secret state on a NEW ability in the slot: not the old ability's (Firebolt was on)", pa and pa[-1])
Q.execute('PETBAR[4] = { "Firebolt", 135809, false, false, true, true, 3110 }')

# 13. the pet dead: the document says so, its wheel has no rows (q60)
pa = pump('UNITS.pet.alive = false; fire("UNIT_HEALTH", "pet")')
check(pa and pa[-1].get('dead') == 1, "the pet dead: petauto dead 1", pa and pa[-1])
check(read("pet", "party").get('items', []) == [], "a dead pet's wheel: no rows")
check(pump('fire("UNIT_HEALTH", "party1")') == [], "another unit's health: no scan / document")

# 14. no pet out: pet 0, nothing
pa = pump('UNITS.pet = nil; fire("UNIT_PET", "player")')
check(pa and pa[-1].get('pet') == 0 and pa[-1].get('s') in ([], {}, None), "no pet out: petauto pet 0, no abilities", pa and pa[-1])
check(read("pet", "party").get('items', []) == [], "no pet out: no wheel rows")

# 15. the events are the game's (registered), and nothing protected or saved is ever called
evs = Q.eval('(function() local t = {} for e in pairs(EVT) do t[#t + 1] = e end table.sort(t) return table.concat(t, " ") end)()')
check(all(e in evs.split() for e in ("PET_BAR_UPDATE", "PET_BAR_UPDATE_COOLDOWN", "UNIT_PET", "PLAYER_REGEN_ENABLED", "UNIT_HEALTH")),
      "PET_BAR_UPDATE / PET_BAR_UPDATE_COOLDOWN / UNIT_PET / PLAYER_REGEN_ENABLED / UNIT_HEALTH registered")
check(int(Q.eval('#SPY')) == 0 and int(Q.eval('#TOGGLED')) == 0,
      "no SetBinding / CreateMacro / SetCVar / Blizzard window call, no insecure TogglePetAutocast",
      [Q.eval('SPY[%d]' % i) for i in range(1, int(Q.eval('#SPY')) + 1)])
body = SRC["quests"][SRC["quests"].index("-- q62 THE PET'S AUTOCAST ON ITS WHEELS"):SRC["quests"].index("function WC.pmenu.read(")]
check(not any(w in body for w in ("TogglePetAutocast(", "CastPetAction(", "ToggleSpellAutoCast(", "SetSpellAutoCastEnabled(", "SetBinding",
                                    "CreateMacro", "SetCVar", "ShowUIPanel", "PetActionButton")),
      "the q62 code calls nothing protected or Blizzard's (reads + our own cells only)")
check(int(Q.eval('#ERRS')) == 0, "no event handler error", [Q.eval('ERRS[%d]' % i) for i in range(1, int(Q.eval('#ERRS')) + 1)])

print("%d passed, %d failed" % (passes, fails))
sys.exit(1 if fails else 0)
