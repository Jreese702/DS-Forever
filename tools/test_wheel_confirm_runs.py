"""The wheel's confirmed runs LAND (Quests q70 + the app's TapSpacing; the user on test 1505 / 1.0.37: Abandon Pet "Nothing after Yes"),
off-device: the REAL TapPad + Quests + PadMap (Party mode's row) under the stubbed game (tools/wowstub.py), taps as the Thor's taps.log.

    python tools/test_wheel_confirm_runs.py

THE DEVICE'S SEQUENCE (logcat 16:38:26-28): the pet slot held in Party mode (party:6 MENU HOLD, party:7 the pet's menu read), pick
Release, ask, Yes, the wheel closes (party:35 MENU DONE), then the run's two digit taps qact:26 qact:26 (id 63 = 7,7: 33 - digit).
The cause: TapPad's REPEAT guard (0.25 s, pad5b) takes a second press of the SAME cell for the tap's own second press and drops it,
so the run never started and nothing answered. Checks:
  1. the old timing (the batch's taps ~0.1 s apart, unspaced) reproduces it: nothing abandoned - and now q70 SAYS so ("Tap lost");
  2. MENU DONE is not the cause: it reads no menu and leaves the pet's run alone;
  3. the app's spacing (TapSpacing.ROWS holds Quests' "qact" row, MIN_MS apart - read from the Kotlin source) lands both digits and
     the pet is abandoned once, the run answering ok;
  4. a pair split by a new menu read answers "Menu changed" (never another entry), the fixed id 63 still runs;
  5. the other same-digit runs: a party member's Uninvite at id 9 (1,1) and the pet's Rename at id 0 (0,0) - lost unspaced, run spaced;
  6. every refusal answers (In combat / Another pet / No pet / Cannot abandon / Menu gone): never silence.
Needs lupa. Exit code 1 on any failure."""
import os
import re
import sys

from wowstub import LOGIN, SRC, boot, docs, nsent

fails = 0
passes = 0


def check(ok, name, got=''):
    global fails, passes
    print(('PASS ' if ok else 'FAIL ') + name + ((' -> ' + str(got)) if got != '' else ''))
    if ok:
        passes += 1
    else:
        fails += 1


ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
KT = os.path.join(ROOT, 'companion', 'app', 'src', 'main', 'java', 'com', 'jrees', 'wowcompanion')
spacing = open(os.path.join(KT, 'TapSpacing.kt'), encoding='utf-8').read()
guestpad = open(os.path.join(KT, 'GuestPad.kt'), encoding='utf-8').read()
playermenu = open(os.path.join(KT, 'PlayerMenu.kt'), encoding='utf-8').read()
MIN_MS = int(re.search(r'const val MIN_MS = (\d+)L', spacing).group(1))
rows_decl = spacing[spacing.index('val ROWS: Set<String> = setOf('):]
rows_decl = rows_decl[:rows_decl.index('\n\n')]
check('GuestPad.ROW' in rows_decl and re.search(r'const val ROW = "qact"', guestpad) is not None,
      "the app spaces Quests' qact row (TapSpacing.ROWS holds GuestPad.ROW = \"qact\")")
check(MIN_MS >= 300, "the spacing clears TapPad's 0.25 s REPEAT with a frame's margin", MIN_MS)
check('listOf(33 - (id / 8) % 8, 33 - id % 8)' in playermenu, "the run id's digits as the app taps them (33 - digit)")


def digit_cols(i):
    return [33 - (i // 8) % 8, 33 - i % 8]


check(digit_cols(63) == [26, 26], "id 63 (Abandon Pet) = qact:26 qact:26, the device's taps.log", digit_cols(63))

PRE = r'''
WowCompanion.partyOk = true
SPELLNAME[6991] = "Feed Pet"; SPELLNAME[2641] = "Dismiss Pet"; SPELLNAME[136] = "Mend Pet"; SPELLNAME[5149] = "Beast Training"
KNOWN = { [2641] = true }
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
C_PetInfo = { PetAbandon = function() ABANDONED = ABANDONED + 1 end }
PET_ABANDON = "Release"
UNINVITED = {}
function UninviteUnit(n) UNINVITED[#UNINVITED + 1] = n end
function C_PartyInfo_Uninvite(n) UNINVITED[#UNINVITED + 1] = n end
MENUS = {}
UnitPopupManager = { GetMenu = function(_, which) MENUWHICH = which
    return { GetEntries = function() return MENUS[which] or {} end } end }
'''
L = boot(["safety", "tappad", "quests", "book", "padmap", "padtabctl"], pre=PRE)
L.execute(LOGIN)
check(L.eval('WowCompanion.pad.rows.party ~= nil and WowCompanion.pad.rows.qact ~= nil'),
      "the world loads with Party mode's row and Quests' qact row")
check(L.eval('WowCompanion.pad.rows.qact.cells[26] ~= nil and WowCompanion.pad.rows.party.cells[35] ~= nil'), "their cells are laid out")


L.execute(r'''
function TAP(row, col)        -- a tap of the app on a TapPad cell: both edges of the click, then the batch's ~0.1 s to the next tap
    local c = WowCompanion.pad.rows[row].cells[col]
    if not c then return false end
    doClick(c, "LeftButton", true); doClick(c, "LeftButton", false)
    NOW = NOW + 0.1
    return true
end''')


def tap(row, col):
    return L.eval('TAP')(row, col)


def acks(n0):
    return [a for a in docs(L, n0, 'guestack') if a.get('op') == 'pmenurun']


def pmdocs(n0):
    return docs(L, n0, 'pmenu')


def run_taps(i, gap):
    """the app's two digit taps for id [i]: hwTap itself moves the clock 0.1 s per tap (the batch's own rhythm); [gap] = the
    extra wait the app's TapSpacing puts in front of a repeated cell (0 = the old, unspaced batch)"""
    cols = digit_cols(i)
    tap("qact", cols[0])
    if cols[1] == cols[0] and gap > 0:
        L.execute('NOW = NOW + %f' % max(0.0, gap - 0.1))
    tap("qact", cols[1])


def gap_for(i):
    cols = digit_cols(i)
    return MIN_MS / 1000.0 if cols[0] == cols[1] else 0.0


def rest():
    """between scenarios: the app's cross-batch spacing (TapSpacing.lead) and any answer still due (a lost tap's)"""
    L.execute('advance(3)')


def open_pet_slot():
    """Party mode on, the pet slot held: MENU HOLD (6) then the pet's menu read (7) - as the hold's taps on the Thor"""
    n0 = nsent(L)
    tap("party", 6)
    tap("party", 7)
    L.execute('advance(0.05)')
    d = pmdocs(n0)
    return d[-1] if d else {}


L.execute('UNITS.pet = { name = "Crocolisk", alive = true, guid = "Pet-0-1-1-1-1-CROC" }')
tap("party", 10)                                                     # Party mode on (the Select tab)
check(L.eval('rawget(_G, "WowCompanionPartyPad").a["wc-party"]') is True, "Party mode is on")

# ---- 1. THE BUG, AS THE DEVICE DID IT: open, pick, ask, Yes, close (party:35), qact:26 qact:26 unspaced
d = open_pet_slot()
ab = next((x for x in d.get('items', []) if x.get('k') == 'PetAbandon'), None)
check(d.get('unit') == 'pet' and d.get('src') == 'party' and ab is not None and ab.get('i') == 63 and ab.get('t') == 'Release'
      and ab.get('cf') == 'Abandon Crocolisk? Permanent.',
      "the party pet slot reads the PET's menu: 'Release' (PET_ABANDON), id 63, asks first", (d.get('unit'), ab))
check(L.eval('rawget(_G, "WowCompanionPartyPad").a["wc-pmenu"]') is True, "the hold put the pad in the menu sub-state")
L.execute('advance(1.8)')                                            # 26.343 OPEN -> 28.146 Yes
n0 = nsent(L)
tap("party", 35)                                                     # the wheel closes: MENU DONE first
check(not pmdocs(n0) and L.eval('rawget(_G, "WowCompanionPartyPad").a["wc-pmenu"]') is None,
      "MENU DONE (party:35) ends the sub-state and reads no menu (not the cause)")
L.execute('ABANDONED = 0')
n0 = nsent(L)
run_taps(63, 0)                                                      # the 1.0.37 batch: qact:26 qact:26, ~0.1 s apart
check(L.eval('ABANDONED') == 0 and acks(n0) == [], "1505's timing reproduced: the second 26 falls in TapPad's REPEAT guard, nothing runs, nothing answers")
L.execute('advance(2.5)')
a = acks(n0)
check(L.eval('ABANDONED') == 0 and len(a) == 1 and a[0]['ok'] is False and a[0]['why'] == 'Tap lost',
      "q70: the lone first digit now answers 'Tap lost' (never silence)", a)

# ---- 3. THE FIX: the same sequence, the second 26 spaced by the app (TapSpacing MIN_MS)
d = open_pet_slot()
L.execute('advance(1.8)')
tap("party", 35)
n0 = nsent(L)
run_taps(63, gap_for(63))
a = acks(n0)
check(L.eval('ABANDONED') == 1 and len(a) == 1 and a[0]['ok'] is True,
      "spaced: both digits land, the pet is abandoned once, the run answers ok", (L.eval('ABANDONED'), a))
L.execute('advance(3)')
check(len(acks(n0)) == 1, "no 'Tap lost' after a run that landed", acks(n0))

# ---- 4. a re-read between the two digits: positions answer "Menu changed"; the fixed 63 still runs (it names no position)
L.execute('UNITS.pet = { name = "Crocolisk", alive = true, guid = "Pet-0-1-1-1-1-CROC" }; ABANDONED = 0')
open_pet_slot()
n0 = nsent(L)
tap("qact", 26)
L.eval('WowCompanion.pmenu.read')("pet", "party", True)             # the 0.3 s re-read after an earlier run, say
L.execute('NOW = NOW + 0.4')
tap("qact", 26)
a = acks(n0)
check(L.eval('ABANDONED') == 1 and a and a[-1]['ok'] is True, "a re-read between the digits: the fixed id 63 still runs", a)

# ---- 6. every refusal answers, through the real taps (open, Yes, then the state changes before the run)
def refused(setup, why, name):
    L.execute('UNITS.pet = { name = "Crocolisk", alive = true, guid = "Pet-0-1-1-1-1-CROC" }; ABANDONED = 0; COMBAT = false; CAN_ABANDON = true')
    open_pet_slot()
    tap("party", 35)
    L.execute(setup)
    rest()
    n0 = nsent(L)
    run_taps(63, gap_for(63))
    a = acks(n0)
    check(L.eval('ABANDONED') == 0 and len(a) == 1 and a[0]['ok'] is False and a[0]['why'] == why, name, a)


refused('COMBAT = true', 'In combat', "a fight since the Yes: 'In combat'")
refused('UNITS.pet = { name = "Kitty", alive = true, guid = "Pet-0-1-1-1-2-KITT" }', 'Another pet', "another pet out: 'Another pet'")
refused('UNITS.pet.guid = "Pet-0-1-1-1-9-OTHR"', 'Another pet', "the same name, another GUID: 'Another pet'")
refused('UNITS.pet = nil', 'No pet', "the pet gone: 'No pet'")
refused('CAN_ABANDON = false', 'Cannot abandon', "the game no longer lets it go: 'Cannot abandon'")
L.execute('COMBAT = false; CAN_ABANDON = true; UNITS.pet = { name = "Crocolisk", alive = true, guid = "Pet-0-1-1-1-1-CROC" }')
rest()
L.execute('WowCompanion.pmenu.abandonFor = nil')                     # (the re-read after the last run named it again)
n0 = nsent(L)
run_taps(63, gap_for(63))
a = acks(n0)
check(L.eval('ABANDONED') == 0 and len(a) == 1 and a[0]['why'] == 'Menu gone', "no pet menu named a pet: 'Menu gone'", a)
check(L.eval('#VIOL') == 0 and L.eval('#SNIPERR') == 0, "no protected call blocked, no snippet error")

# ---- 4b. a POSITIONAL id split by a read answers "Menu changed", never another entry
L.execute(r'''
UNITS.party1 = { name = "Brannoc", alive = true, player = true, guid = "Player-1-Brannoc" }
local filler = {}
for i = 1, 9 do
    local m = { GetText = function() return "Star" .. i end }
    _G["UnitPopupRaidTargetIcon" .. i .. "ButtonMixin"] = nil
    filler[i] = m
end
UnitPopupRaidTarget1ButtonMixin = { GetText = function() return "Star" end }
UnitPopupUninviteButtonMixin = { GetText = function() return "Uninvite" end }
local list = {}
for i = 1, 9 do list[i] = UnitPopupRaidTarget1ButtonMixin end
list[10] = UnitPopupUninviteButtonMixin
MENUS.PARTY = list
WowCompanion.pmenu.mix = nil
''')
n0 = nsent(L)
L.eval('WowCompanion.pmenu.read')("party1", "party")
pd = pmdocs(n0)[-1]
un = next((x for x in pd['items'] if x['t'] == 'Uninvite'), {})
check(un.get('i') == 9 and un.get('cf') == 'Uninvite?', "a party member's Uninvite at id 9 (digits 1,1), asking first", un)
L.execute('UNINVITED = {}')
rest()
n0 = nsent(L)
run_taps(9, 0)
L.execute('advance(2.5)')
a = acks(n0)
check(L.eval('#UNINVITED') == 0 and a and a[-1]['why'] == 'Tap lost', "Uninvite (id 9) unspaced: lost before the fix, now 'Tap lost'", a)
rest()
n0 = nsent(L)
run_taps(9, gap_for(9))
a = acks(n0)
check(L.eval('#UNINVITED') == 1 and a and a[-1]['ok'] is True, "Uninvite (id 9) spaced: runs", (L.eval('#UNINVITED'), a))
L.execute('UNINVITED = {}')
rest()
n0 = nsent(L)
tap("qact", 32)
L.eval('WowCompanion.pmenu.read')("party1", "party")
L.execute('NOW = NOW + 0.4')
tap("qact", 32)
a = acks(n0)
check(L.eval('#UNINVITED') == 0 and a and a[-1]['ok'] is False and a[-1]['why'] == 'Menu changed',
      "a positional id split by a new read: 'Menu changed', nothing run", a)

# ---- 5. the pet's Rename at id 0 (digits 0,0): lost unspaced, opens spaced
L.execute(r'''
UNITS.party1 = nil
UnitPopupPetRenameButtonMixin = { GetText = function() return "Rename Pet" end }
MENUS.PET = { UnitPopupPetRenameButtonMixin }
WowCompanion.pmenu.mix = nil
UNITS.pet = { name = "Crocolisk", alive = true, guid = "Pet-0-1-1-1-1-CROC" }
OPENED = 0
WowCompanion.chatField = { open = function(o) OPENED = OPENED + 1; FIELDNAME = o.name; return true end, name = function() return FIELDNAME end,
                           close = function() FIELDNAME = nil end }
''')
d = open_pet_slot()
rn = next((x for x in d.get('items', []) if x.get('ap') == 'rename'), {})
check(rn.get('i') == 0, "the pet's Rename is run id 0 here (digits 0,0)", rn)
rest()
n0 = nsent(L)
run_taps(0, 0)
L.execute('advance(2.5)')
check(L.eval('OPENED') == 0 and acks(n0) and acks(n0)[-1]['why'] == 'Tap lost', "Rename (id 0) unspaced: lost, now 'Tap lost'", acks(n0))
d = open_pet_slot()
rest()
n0 = nsent(L)
run_taps(0, gap_for(0))
check(L.eval('OPENED') == 1 and acks(n0) and acks(n0)[-1]['ok'] is True, "Rename (id 0) spaced: the keyboard field opens", acks(n0))
check(L.eval('#VIOL') == 0 and L.eval('#SNIPERR') == 0 and list(L.eval('ERRS').values()) == [], "no protected call, no snippet error, no Lua error",
      list(L.eval('ERRS').values())[:2])

print("%d passed, %d failed" % (passes, fails))
sys.exit(1 if fails else 0)
