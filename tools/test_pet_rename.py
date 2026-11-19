"""The pet's Rename on its wheels (Quests q69; the user on test 1502: "renaming my pet is still grayed out"), off-device: the REAL
TapPad + Quests files under the stubbed game (tools/wowstub.py), Chat.lua's borrowed field (WC.chatField, chat22) stubbed.

    python tools/test_pet_rename.py

Checks: the game's own Rename entry on the pet's menu is LIVE (it was greyed: WC.pmenu.actFor had no run for it) with ap "rename" for a
hunter's pet the game renames, out of combat; greyed with its reason ("Can't rename this pet": PetCanBeRenamed() false, not a hunter;
"Not in combat": a fight); its run opens Chat's field with the pet's name, max 12 (never StaticPopup_Show / the RENAME_PET popup); Done
(the field's done) calls C_PetInfo.PetRename(name) once with the name alone, as PETRENAMECONFIRM's OnAccept does (PetRename() on a
build without it), the checks again first (a fight, another pet, an empty name refused); run id 62 closes the field. Needs lupa."""
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
SPELLNAME[6991] = "Feed Pet"; SPELLNAME[2641] = "Dismiss Pet"
KNOWN = { [6991] = true, [2641] = true }
function IsPlayerSpell(id) return KNOWN[id] == true end
function IsSpellKnown(id) return false end
function UnitIsUnit(a, b) if a == b then return true end local x, y = UNITS[a], UNITS[b]; return x ~= nil and rawequal(x, y) end
function UnitIsPlayer(u) local x = UNITS[u]; return x ~= nil and x.player == true end
CLASS = "HUNTER"
CAN_ABANDON = true; HAS_BAR = true; CAN_RENAME = true
function PetCanBeAbandoned() return UNITS.pet ~= nil and CAN_ABANDON end
function PetHasActionBar() return UNITS.pet ~= nil and HAS_BAR end
function PetCanBeRenamed() return UNITS.pet ~= nil and CAN_RENAME end
RENAMED = {}
C_PetInfo = { PetAbandon = function() end, PetRename = function(...) RENAMED[#RENAMED + 1] = { n = select('#', ...), name = (...) } end }
POPUPS = 0
function StaticPopup_Show() POPUPS = POPUPS + 1 end
-- the game's PET menu: its own Rename entry (UnitPopupPetRenameButtonMixin), live as the game says (CanShow = PetCanBeAbandoned())
UnitPopupPetRenameButtonMixin = { GetText = function() return "Rename Pet" end, CanShow = function() return PetCanBeAbandoned() end,
                                  IsEnabled = function() return true end }
UnitPopupManager = { GetMenu = function(_, which) MENUWHICH = which
    return { GetEntries = function() return { UnitPopupPetRenameButtonMixin } end } end }
'''
Q = boot(["safety", "tappad", "quests"], pre=PRE)
# Chat.lua's borrowed field (chat22), as far as the rename needs it
Q.execute('''
FIELD = nil; FIELDS = 0
WowCompanion.chatField = {
  open = function(spec) FIELD = spec; FIELDS = FIELDS + 1; return true end,
  close = function() FIELD = nil end,
  name = function() return FIELD and FIELD.name or nil end,
}''')

check('local QV = "q70"' in SRC["quests"] and "q69 THE PET'S RENAME" in SRC["quests"] and "q68 ABANDON PET" in SRC["quests"],
      "Quests says q70 (q68 kept below it)")


def read(unit, src):
    n0 = nsent(Q)
    Q.eval('WowCompanion.pmenu.read')(unit, src)
    pm = docs(Q, n0, 'pmenu')
    return pm[-1] if pm else {}


def ren(d):
    return next((x for x in d.get('items', []) if x.get('ap') == 'rename'), None)


def run(i):
    n0 = nsent(Q)
    Q.eval('WowCompanion.pmenu.run')(i)
    acks = [a for a in docs(Q, n0, 'guestack') if a.get('op') == 'pmenurun']
    return acks[-1] if acks else {}


def done(text):
    r = Q.eval('function(t) local ok, why = FIELD.done(t); return ok, why end')(text)
    return r


# 1. a hunter's pet: the game's Rename is live, runs by its menu id, ap "rename"
Q.execute('UNITS.pet = { name = "Rex", alive = true, guid = "Pet-0-1-1-1-1-AAAA" }')
d = read("pet", "party")
r = ren(d) or {}
check(r.get('t') == "Rename Pet" and r.get('en') == 1 and isinstance(r.get('i'), int) and r.get('i') < 62,
      "the game's Rename: live (it was greyed), a menu run id", r)
check(r.get('wy') is None and r.get('cf') is None, "no reason, no ask", r)
Q.execute('UNITS.target = UNITS.pet')
check((ren(read("target", "radial")) or {}).get('en') == 1, "the target wheel on your pet: Rename live too")

# 2. its run opens Chat's field with the pet's name, max 12; never the game's popup
read("pet", "party")
a = run(r['i'])
check(a.get('ok') is True and Q.eval('FIELDS') == 1, "the run opens the borrowed field", a)
check(Q.eval('FIELD.name') == "petrename" and Q.eval('FIELD.text') == "Rex" and Q.eval('FIELD.max') == 12,
      "the field: 'petrename', the pet's name, at most 12 (RENAME_PET's maxLetters)")
check(Q.eval('FIELD.holds()') is True, "the field holds while the rename is open")
check(Q.eval('POPUPS') == 0, "never StaticPopup_Show (RENAME_PET)")

# 3. Done: an empty name refused, the field kept; a name: C_PetInfo.PetRename(name) once, the name alone; the field closes
ok = done("   ")
check(ok[0] is False and Q.eval('#RENAMED') == 0 and Q.eval('rawget(_G, "FIELD") ~= nil'), "an empty name: refused, the field kept", ok)
ok = done("Fang")
check(ok[0] is True and Q.eval('#RENAMED') == 1 and Q.eval('RENAMED[1].name') == "Fang" and Q.eval('RENAMED[1].n') == 1,
      "Done: C_PetInfo.PetRename('Fang') once, the name alone (PETRENAMECONFIRM's OnAccept)", ok)
check(Q.eval('rawget(_G, "FIELD") == nil'), "renamed: the field closes")

# 4. a fight at Done: refused, nothing renamed, the field kept (out of the fight it can go)
read("pet", "party")
run(ren(read("pet", "party"))['i'])
Q.execute('COMBAT = true')
ok = done("Fang")
check(ok[0] is False and ok[1] == "Not in combat" and Q.eval('#RENAMED') == 1 and Q.eval('rawget(_G, "FIELD") ~= nil'),
      "a fight at Done: 'Not in combat', nothing renamed", ok)
check((ren(read("pet", "party")) or {}).get('en') == 0 and (ren(read("pet", "party")) or {}).get('wy') == "Not in combat",
      "in a fight the row is greyed with 'Not in combat'")
Q.execute('COMBAT = false')

# 5. another pet out since the pick: refused and the field closes
read("pet", "party")
run(ren(read("pet", "party"))['i'])
Q.execute('UNITS.pet.guid = "Pet-0-1-1-1-9-CCCC"')
ok = done("Fang")
check(ok[0] is False and ok[1] == "Another pet" and Q.eval('#RENAMED') == 1 and Q.eval('rawget(_G, "FIELD") == nil'),
      "another pet: refused, the field closes", ok)
Q.execute('UNITS.pet.guid = "Pet-0-1-1-1-1-AAAA"')

# 6. B: run id 62 closes the field (no re-read, nothing renamed)
read("pet", "party")
run(ren(read("pet", "party"))['i'])
a = run(62)
check(a.get('ok') is True and Q.eval('rawget(_G, "FIELD") == nil') and Q.eval('#RENAMED') == 1, "run 62 (the app's B): the field closes", a)

# 7. the game will not rename: greyed with its reason; the run refuses
Q.execute('CAN_RENAME = false')
r = ren(read("pet", "party")) or {}
check(r.get('en') == 0 and r.get('wy') == "Can't rename this pet", "already renamed (PetCanBeRenamed false): greyed, its reason", r)
a = run(r['i'])
check(a.get('ok') is False and Q.eval('rawget(_G, "FIELD") == nil'), "and its run refuses", a)
Q.execute('CAN_RENAME = true; CLASS = "WARLOCK"')
check((ren(read("pet", "party")) or {}).get('en') == 0, "a warlock's demon: greyed")
Q.execute('CLASS = "HUNTER"; PetCanBeRenamed = nil')
check((ren(read("pet", "party")) or {}).get('en') == 1, "a client without PetCanBeRenamed: the game's CanShow decides (live)")

# 8. a build without C_PetInfo.PetRename: the global PetRename(name)
Q.execute('C_PetInfo = { PetAbandon = function() end }; OLDREN = nil; function PetRename(n) OLDREN = n end')
run(ren(read("pet", "party"))['i'])
ok = done("Bolt")
check(ok[0] is True and Q.eval('OLDREN') == "Bolt", "no C_PetInfo.PetRename: PetRename(name)", ok)

# 8b. the menu's own entries stop at 61: 62 (the rename's close) and 63 (Abandon Pet) are never one of them
Q.execute('''
local many = {}
for n = 1, 70 do many[n] = { GetText = function() return "E" .. n end, CanShow = function() return true end } end
UnitPopupManager = { GetMenu = function() return { GetEntries = function() return many end } end }
WowCompanion.pmenu.mix = nil''')
Q.execute('''WowCompanion.pmenu.actFor = (function(orig) return function(k, t, u, s) if t and t:match("^E%d+$") then return { fn = function() end } end
    return orig(k, t, u, s) end end)(WowCompanion.pmenu.actFor)''')
read("pet", "party")
n = Q.eval('#WowCompanion.pmenu.list')
check(n == 62, "a long menu: its runs fill ids 0..61 only (62 the rename's close, 63 Abandon's)", n)

# 9. the code: no Blizzard UI, nothing saved
body = SRC["quests"][SRC["quests"].index("-- q69 THE PET'S RENAME"):SRC["quests"].index("-- q62 THE PET'S AUTOCAST ON ITS WHEELS")]
body = "\n".join(ln.split("--")[0] for ln in body.splitlines())
check(not any(w in body for w in ("StaticPopup", "OnClick", "SetCVar", "SetBinding", "CreateMacro", "EditMacro", "SaveBindings")),
      "Rename calls nothing of Blizzard's UI and saves nothing")
check(len(list(Q.eval('VIOL').values())) == 0, "no protected call blocked", list(Q.eval('VIOL').values()))

print("%d passed, %d failed" % (passes, fails))
sys.exit(1 if fails else 0)
