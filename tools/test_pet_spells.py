"""PET SPELLS (#234, the user: "build it"; Spells s10, Book b63, PadMap m163, Radial r21 / q5, ActionBar cb10), off-device: the REAL
add-on files under tools/wowstub.py's stubbed game, Lua 5.1 as the game's.

    python tools/test_pet_spells.py            (PARTY_FIXTURES_WRITE=1 rewrites the fixtures the app's tests read)

The game's own Pet tab (blizzard_playerspells SpellBookPetCategoryMixin: HasPetSpells() and PetHasSpellbook(), bank Pet) holds the pet's
spells, its passives and its commands. Its SPELLS get numbers and the Bind box; a passive and a command never (Attack / Follow / Stay
have their homes). The user's test character is a level 6 Shaman (no pet), so this proves it offline:
  a a Hunter with a pet: the feed's pet items say cmd / bind / pb, the spells' `pv` numbers (after the player's top ranks), the Pet tab's
    line 101; the commands and the passive have none; the names kept per character (name-realm);
  b the room's Pet tab (Book): last, reached by the D-pad, A on a command / a passive refused ("bind-not-bindable"), A on a spell opens
    the box; the report's `line` is 101 (the app's "pet" tab);
  c Normal: the game's own way (its gamepad edit frame's BindSpellBookItem): PickupSpellBookItem(slot, Pet) + PlaceAction on the
    standard slot the press names; the slot reads "spell", id, "pet"; keep / undo (B puts back what was there: another pet spell back
    from the pet's book);
  d In combat: the fight layer's entry is marked `pet`, never a rank pick; its text is "/cast Growl" (no Start attack lines, the
    setting on); a base X's fight spell is the NAME (CastSpellByName, the game's /cast), never the id;
  e L3: the stick's entry "/cast Growl";
  f Cast bar: the Bind box's Cast bar tab puts the pet spell's id on the bar; its cell casts the NAME;
  g a spell radial: the Controller room's chooser lists the pet's spells after the class's (spellsBindableList `pet`), its cell casts
    the NAME; touchSpell on a WoW Defaults button puts it the game's own way (the pet bank);
  h the spell's two taps (`pv`) open the Bind box by touch;
  i the pet dismissed: no Pet tab, the kept names still cast by name (a rebuilt radial cell);
  j a Warlock's imp whose commands come as TOKEN names (PET_ACTION_ATTACK, no spell id): commands found, Firebolt bound;
  k a Shaman (no pet): no Pet tab, nothing new; the player's spells unchanged;
  l in combat: the box refuses before any pickup; no trace anywhere: no SetBinding / SaveBindings / macro / CVar / Blizzard window call.
Needs lupa. Exit code 1 on any failure."""
import json
import os
import sys

from lupa import lua51 as lupa

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ADDONS = os.path.join(ROOT, "companion", "app", "src", "main", "assets", "companion", "addons")
F = {
    "safety": os.path.join(ADDONS, "WowCompanion", "Safety.lua"),
    "tappad": os.path.join(ADDONS, "WowCompanion", "TapPad.lua"),
    "spells": os.path.join(ADDONS, "WowCompanionSpells", "Spells.lua"),
    "book": os.path.join(ADDONS, "WowCompanionSpells", "Book.lua"),
    "padmap": os.path.join(ADDONS, "WowCompanionSpells", "PadMap.lua"),
    "padtabctl": os.path.join(ADDONS, "WowCompanionSpells", "PadTabCtl.lua"),
    "radial": os.path.join(ADDONS, "WowCompanionSpells", "Radial.lua"),
}
SRC = {k: open(p, encoding="utf-8").read() for k, p in F.items()}
AB = open(os.path.join(ADDONS, "WowCompanion", "ActionBar.lua"), encoding="utf-8").read()
import re  # noqa: E402
CASTBAR = re.search(r'-- CASTBAR-BEGIN[^\n]*\n(.*?)-- CASTBAR-END', AB, re.S).group(1)
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from wowstub import GAME  # noqa: E402
# the documents this run emits, as Safety.lua's encoder writes them: dsforever's PetSpellsTest and the app's PetSpellsShotsTest read them
# from dsforever/src/test/resources/pet-fixtures (PARTY_FIXTURES_WRITE=1 rewrites them; otherwise a stale one fails)
FIXDIR = os.path.join(ROOT, "dsforever", "src", "test", "resources", "pet-fixtures")
FIX = {}
fails = 0
passes = 0


def check(ok, name, got=''):
    global fails, passes
    print(('PASS ' if ok else 'FAIL ') + name + ((' -> ' + str(got)) if got != '' else ''))
    if ok:
        passes += 1
    else:
        fails += 1


# THE PET'S BOOK as this client's C_SpellBook gives it (bank 1 = Enum.SpellBookSpellBank.Pet). The pet bar's packing in the action id:
# the high byte 7 = a command, 6 = a stance, 0xC1 / 0x81 = a spell with auto-cast on / off, 1 = a passive
PETS = r'''
local H = 16777216
HUNTER_PET = {
    { name = "Attack", actionID = 7 * H + 2, itemType = 3, iconID = 132152 },
    { name = "Follow", actionID = 7 * H + 1, itemType = 3, iconID = 132328 },
    { name = "Stay", actionID = 7 * H + 0, itemType = 3, iconID = 136106 },
    { name = "Growl", actionID = 0xC1 * H + 2649, spellID = 2649, itemType = 3, iconID = 132270, subName = "Rank 1", ac = true, ae = true },
    { name = "Bite", actionID = 0x81 * H + 17253, spellID = 17253, itemType = 3, iconID = 132278, subName = "Rank 1", ac = true },
    { name = "Great Stamina", actionID = 1 * H + 4187, spellID = 4187, itemType = 3, iconID = 136112, subName = "Rank 1", pas = true },
    { name = "Defensive", actionID = 6 * H + 1, itemType = 3, iconID = 132110 },
}
-- this client answers some commands as TOKEN names with no spell (ActionBar b-pet-tokens): a warlock's imp
IMP = {
    { name = "PET_ACTION_ATTACK", actionID = 0, spellID = 0, itemType = 3, iconID = 132152 },
    { name = "PET_ACTION_FOLLOW", actionID = 0, itemType = 3, iconID = 132328 },
    { name = "Firebolt", actionID = 0xC1 * H + 3110, spellID = 3110, itemType = 3, iconID = 135809, subName = "Rank 1", ac = true, ae = true },
    { name = "Blood Pact", actionID = 0xC1 * H + 6307, spellID = 6307, itemType = 3, iconID = 136168, subName = "Rank 1", ac = true },
    { name = "Phase Shift", actionID = 0x81 * H + 4511, spellID = 4511, itemType = 3, iconID = 136164, ac = true },
}
-- (review, Spells s10b) a succubus: Seduction cannot auto-cast, so the server packs it with state 1 (its "ACT_PASSIVE": 0x81 / 0xC1 are
-- 1 | the auto-cast bits) - an ACTIVE spell the game's book shows and casts; Lash of Pain auto-casts; Soothing Aura a true passive
SUCCUBUS = {
    { name = "Attack", actionID = 7 * H + 2, itemType = 3, iconID = 132152 },
    { name = "Seduction", actionID = 1 * H + 6358, spellID = 6358, itemType = 3, iconID = 136175, subName = "" },
    { name = "Lash of Pain", actionID = 0xC1 * H + 7814, spellID = 7814, itemType = 3, iconID = 136136, subName = "Rank 1", ac = true, ae = true },
    { name = "Soothing Aura", actionID = 1 * H + 20000, spellID = 20000, itemType = 3, iconID = 136100, pas = true },
}
'''

EXTRA = r'''
CLASS = "HUNTER"
Enum = { SpellBookSpellBank = { Player = 0, Pet = 1 }, SpellBookItemType = { Spell = 1, FutureSpell = 2, PetAction = 3, Flyout = 4 },
         BindingContext = { GamepadModeInGameCore = 3 } }
PET = "Pet"
SPELLNAME[1978] = "Serpent Sting"; SPELLNAME[13165] = "Aspect of the Hawk"; SPELLNAME[3044] = "Arcane Shot"; SPELLNAME[2973] = "Raptor Strike"
SPELLNAME[2649] = "Growl"; SPELLNAME[17253] = "Bite"; SPELLNAME[4187] = "Great Stamina"
SPELLNAME[3110] = "Firebolt"; SPELLNAME[6307] = "Blood Pact"; SPELLNAME[4511] = "Phase Shift"
SPELLNAME[686] = "Shadow Bolt"
SPELLNAME[6358] = "Seduction"; SPELLNAME[7814] = "Lash of Pain"; SPELLNAME[20000] = "Soothing Aura"
HARMFUL = { [1978] = true, [3044] = true, [2973] = true, [17253] = true, [3110] = true, [686] = true }
C_Spell.IsSpellHarmful = function(x) local id = x; if type(x) == "string" then for k, n in pairs(SPELLNAME) do if n == x then id = k end end end return HARMFUL[id] == true end
C_Spell.GetSpellInfo = function(x)
    if type(x) == "string" then for k, n in pairs(SPELLNAME) do if n == x then return { spellID = k, name = n, iconID = 130000 + k } end end return nil end
    if SPELLNAME[x] then return { spellID = x, name = SPELLNAME[x], iconID = 130000 + x, castTime = 0 } end
end
C_Spell.GetSpellSubtext = function() return nil end
C_Spell.IsSpellPassive = function() return false end
C_Spell.GetSpellCooldown = function() return { isActive = false, isOnGCD = false } end
BOOK = { 1978, 13165, 3044, 2973 }                 -- the player's book (General: Raptor Strike's line kept simple)
LINES = { { name = "General", iconID = 1, itemIndexOffset = 0, numSpellBookItems = 1, shouldHide = false },
          { name = "Marksmanship", iconID = 2, itemIndexOffset = 1, numSpellBookItems = 3, shouldHide = false } }
PETBOOK = nil                                      -- the pet's book (nil = no pet out)
function lineOf(slot) for k, l in ipairs(LINES) do if slot > l.itemIndexOffset and slot <= l.itemIndexOffset + l.numSpellBookItems then return k end end end
PETHASBOOK = true
PICKS = {}                                          -- every PickupSpellBookItem: "slot:bank"
C_SpellBook = {
    GetNumSpellBookSkillLines = function() return #LINES end,
    GetSpellBookSkillLineInfo = function(k) return LINES[k] end,
    GetSpellBookItemInfo = function(slot, bank)
        if bank == 1 then
            local e = PETBOOK and PETBOOK[slot]
            if not e then return nil end
            return { name = e.name, actionID = e.actionID, spellID = e.spellID, itemType = e.itemType, iconID = e.iconID,
                     isPassive = false, isOffSpec = false, subName = e.subName or "" }
        end
        local id = BOOK[slot]
        return id and { spellID = id, actionID = id, name = SPELLNAME[id], itemType = 1, iconID = 130000 + id, isPassive = false,
                        subName = "", skillLineIndex = lineOf(slot) } or nil
    end,
    HasPetSpells = function() if PETBOOK then return #PETBOOK, "PET" end return nil end,
    IsSpellBookItemPassive = function(slot, bank) if bank == 1 then local e = PETBOOK and PETBOOK[slot]; return e and e.pas == true or false end return false end,
    IsSpellBookItemLowRank = function() return false end,
    IsSpellBookItemLooseFlyoutMember = function() return false end,
    GetSpellBookItemAutoCast = function(slot, bank)
        local e = bank == 1 and PETBOOK and PETBOOK[slot]
        if e then return e.ac == true, e.ae == true end
        return false, false
    end,
    PickupSpellBookItem = function(slot, bank)
        PICKS[#PICKS + 1] = slot .. ":" .. tostring(bank)
        if bank == 1 then
            local e = PETBOOK and PETBOOK[slot]
            if e then CURSOR = { "petaction", e.actionID, slot } end
            return
        end
        local id = BOOK[slot]; if id then CURSOR = { "spell", id } end
    end,
}
function PetHasSpellbook() return PETBOOK ~= nil and PETHASBOOK end
CVARB = {}
function GetCVarBool(k) return CVARB[k] == true end
CVARS = { GamepadStanceBarOverride = "3" }
C_CVar = { GetCVar = function(k) return CVARS[k] end, GetCVarBool = function(k) return CVARB[k] == true end }
-- the standard slots: a pet spell reads "spell", id, "pet" (actionbareditframe.lua)
ACTIONINFO = {}
function GetActionInfo(s) local a = ACTIONINFO[s]; if a then return a[1], a[2], a[3] end end
function GetActionTexture(s) local a = ACTIONINFO[s]; return a and 130000 + a[2] or nil end
function HasAction(s) return ACTIONINFO[s] ~= nil end
local function spellOfAction(a) for _, b in ipairs({ HUNTER_PET, IMP, SUCCUBUS }) do for _, e in ipairs(b) do if e.actionID == a then return e.spellID end end end end
C_ActionBar = { HasBonusActionBar = function() return false end,
    FindSpellActionButtons = function(id) local out = {}; for s, a in pairs(ACTIONINFO) do if a[1] == "spell" and a[2] == id and a[3] == nil then out[#out + 1] = s end end; table.sort(out); return out end,
    FindPetActionButtons = function(pa) local sid = spellOfAction(pa); local out = {}
        for s, a in pairs(ACTIONINFO) do if a[1] == "spell" and a[3] == "pet" and a[2] == sid then out[#out + 1] = s end end; table.sort(out); return out end,
    GetPetActionPetBarIndices = function(pa) local sid = spellOfAction(pa); for i, x in pairs(PETBAR) do if x == sid then return { i } end end return {} end }
PETBAR = {}                                        -- the pet bar: index -> spell id
function GetPetActionInfo(i) local sid = PETBAR[i]; if sid then return SPELLNAME[sid], 130000 + sid, false, false, true, false, sid end end
C_PetInfo = { IsPetActionPassive = function(a) for _, b in ipairs({ HUNTER_PET, IMP, SUCCUBUS }) do for _, e in ipairs(b) do if e.actionID == a and a ~= 0 then return e.pas == true end end end return false end,
              GetSpellForPetAction = function(a) return spellOfAction(a) end }
function GetPetIcon() return 132270 end
function GetNumShapeshiftForms() return 0 end
function GetShapeshiftForm() return 0 end
function GetShapeshiftFormInfo() end
C_GamepadUI = {
    GetFirstGamepadActionBarStorageSlotIndexForActiveStance = function() return 0 end,
    GetFirstGamepadActionStorageSlotIndex = function() return 181 end,
    IsValidGamepadActionStorageSlotIndex = function() return true end,
}
Constants = { GamepadActionBarConstants = { NUM_SLOTS_PER_GAMEPAD_ACTION_BAR = 8,
    NUM_PAGEABLE_SLOTS_PER_GAMEPAD_ACTION_BAR_PAGE_UNIT_STANDARD_PAGE = 28, NUM_RESERVED_SLOTS_PER_GAMEPAD_ACTION_BAR_PAGE_UNIT = 4,
    NUM_STANDARD_PAGES_PER_GAMEPAD_ACTION_BAR_PAGE_UNIT = 3 } }
PAGE = 1
GamepadMainActionBarFrame.PageUnit.GetCurrentPage = function() return PAGE end
CURSOR = nil
PLACED = {}
function GetCursorInfo() if CURSOR then return CURSOR[1], CURSOR[2], CURSOR[3] end end
function ClearCursor() CURSOR = nil end
function PlaceAction(s)
    if COMBAT then VIOL[#VIOL + 1] = "PlaceAction" end
    PLACED[#PLACED + 1] = s
    local old = ACTIONINFO[s]
    local c = CURSOR
    if c and c[1] == "petaction" then c = { "spell", spellOfAction(c[2]), "pet" } end
    ACTIONINFO[s] = c
    CURSOR = old
end
function PickupAction(s) CURSOR = ACTIONINFO[s]; ACTIONINFO[s] = nil end
C_Spell.PickupSpell = function(id) PICKS[#PICKS + 1] = "spell:" .. id; if SPELLNAME[id] and id ~= 2649 and id ~= 17253 then CURSOR = { "spell", id } end end
PADSTATE = {}
function GetGamePadState() return { buttons = PADSTATE } end
function hold(which) PADSTATE = {}; if which == "LT" or which == "both" then PADSTATE[LT_I] = true end
    if which == "RT" or which == "both" then PADSTATE[RT_I] = true end end
function toolbar(on) if type(WowCompanionSpellsDB) ~= "table" then WowCompanionSpellsDB = {} end
    WowCompanionSpellsDB.dpad = WowCompanionSpellsDB.dpad or {}; WowCompanionSpellsDB.dpad[WHO .. "-" .. REALM] = on end
WHOK = WHO .. "-" .. REALM
function FT(s) local t = (WowCompanionSpellsDB.fightBy or {})[WHOK]; return t and t[s] end
function FACE(p) local t = (WowCompanionSpellsDB.faceBy or {})[WHOK]; return t and t[p] end
function STK(k) local t = (WowCompanionSpellsDB.stickBy or {})[WHOK]; return t and t[k] end
function HDR(k) local h = rawget(_G, "WowCompanionFaceRouter"); return h and h.a[k] end
function XSPELL() local b = rawget(_G, "WowCompanionCombatFace5"); return b and b.a["wc-fight-spell"] end
function petOut(book) PETBOOK = book; fire("UNIT_PET", "player"); fire("SPELLS_CHANGED"); runTimers(NOW + 2) end
'''


def boot(pre='', pet='HUNTER_PET', who=None):
    L = lupa.LuaRuntime(unpack_returned_tuples=True)
    L.execute(GAME)
    L.execute(PETS)
    L.execute(EXTRA)
    if who:
        L.execute('WHO = "%s"; WHOK = WHO .. "-" .. REALM' % who)
    if pre:
        L.execute(pre)
    if pet:
        L.execute('PETBOOK = %s' % pet)
    load = L.eval('function(src, name) local f, e = loadstring(src, name); if not f then error(e) end; return f end')
    for k in ("safety", "tappad", "spells", "book", "padmap", "padtabctl", "radial"):
        load(SRC[k], '@' + os.path.basename(F[k]))(os.path.basename(F[k]))
    cb = L.eval('function(src) local f, e = loadstring("local WC = _G.WowCompanion\\n" .. src, "@ActionBar.lua#castbar"); '
                'if not f then error(e) end; return f end')
    cb(CASTBAR)()
    L.execute('fire("PLAYER_LOGIN"); fire("PLAYER_ENTERING_WORLD", true, false); WowCompanion.pad.layout(); runTimers(NOW + 10)')
    L.execute('BK = WowCompanion._spellsBook; BK.ctl.on = true; BK.ctl.pg = "book"; toolbar(false)')
    return L


def docs(L, typ, start=0):
    out = []
    for i in range(start + 1, int(L.eval('#SENT')) + 1):
        try:
            d = json.loads(L.eval('SENT[%d]' % i))
        except Exception:
            continue
        if isinstance(d, dict) and d.get('type') == typ:
            out.append(d)
    return out


def errs(L):
    return list(L.eval('ERRS').values()) if L.eval('ERRS') is not None else []


def feed(L):
    return json.loads(L.eval('WowCompanion.encode(WowCompanion.collectSpells())'))


def keep_spells(name, L):
    """the whole `spells` document as the feed sends it (Spells.lua emitSpells: type, v, s)"""
    FIX[name] = L.eval('WowCompanion.encode({ type = "spells", v = "s12", s = WowCompanion.collectSpells() })')


def keep_last(name, L, typ):
    for i in range(int(L.eval('#SENT')), 0, -1):
        t = L.eval('SENT[%d]' % i)
        try:
            d = json.loads(t)
        except Exception:
            continue
        if isinstance(d, dict) and d.get('type') == typ:
            FIX[name] = t
            return


def ctl(L):
    d = docs(L, 'spellsctl')
    return d[-1]['ctl'] if d else {}


def acks(L, start=0):
    return [d for d in docs(L, 'spellsack', start) if d.get('op') == 'bind']


def pet_tab(L):
    """the room's Pet tab: its index among Book's tabs, and its entries (name, bindable)"""
    T = L.eval('BK.tabs()')
    n = len(T)
    for i in range(1, n + 1):
        t = T[i]
        if t.k == 101:
            return i, [(L.eval('SPELLNAME') and t.list[j].n, t.list[j].bind is True, t.list[j].cmd is True, t.list[j].pas is True)
                       for j in range(1, len(t.list) + 1)]
    return None, []


def pick(L, name):
    """the controller's glow onto pet entry [name] (the Pet tab, its place on the page)"""
    i, entries = pet_tab(L)
    j = [e[0] for e in entries].index(name) + 1
    L.execute('BK.ctl.tab, BK.ctl.onTabs, BK.ctl.page, BK.ctl.idx, BK.ctl.mode, BK.ctl.bind = %d, false, 1, %d, "book", nil' % (i, j))


def bind(L, name, trig, key, fight=False):
    """A on pet entry [name] (the room's own press), [fight] = the In combat tab, hold [trig], press [key]: the last ack, the places,
    the mode after"""
    n0 = int(L.eval('#PLACED'))
    a0 = int(L.eval('#SENT'))
    pick(L, name)
    L.execute('BK.press("PAD1")')
    if fight:
        L.execute('BK.ctl.bind.fight = true')
    L.execute('hold(%s)' % ('nil' if trig is None else '"%s"' % trig))
    L.execute('BK.press("%s")' % key)
    L.execute('hold(nil)')
    a = acks(L, a0)
    placed = [L.eval('PLACED[%d]' % i) for i in range(n0 + 1, int(L.eval('#PLACED')) + 1)]
    return (a[-1] if a else {}), placed, L.eval('BK.ctl.mode')


def keep(L):
    L.execute('BK.press("PAD1"); BK.ctl.mode = "book"; BK.ctl.bind = nil; BK.ctl.undo = nil')


# ================================================================ the files
comp = lupa.LuaRuntime().eval('function(s, n) local f, e = loadstring(s, n); return e end')
for k in ("spells", "book", "padmap", "radial"):
    e = comp(SRC[k], k)
    check(e is None, "Lua 5.1 compiles %s (200 locals, 60 upvalues)" % os.path.basename(F[k]), e or '')
check('local SV = "s12"' in SRC["spells"] and 'local BV = "b65"' in SRC["book"] and 'local PV = "m164"' in SRC["padmap"]
      and 'local RV = "r21"' in SRC["radial"] and 'local SRV = "q5"' in SRC["radial"] and 'local CBV = "cb10"' in CASTBAR,
      "the versions: Spells s10b, Book b63a, PadMap m163a (the review), Radial r21 / q5, ActionBar cb10")
check(all('#234, the user: "build it"' in s for s in (SRC["spells"], SRC["book"], SRC["padmap"], SRC["radial"], CASTBAR)),
      "each changed file's header note quotes the user (#234: \"build it\")")

# ================================================================ a. the feed
A = boot()
check(errs(A) == [], "a. the world loads (a Hunter, his pet out)", errs(A)[:2])
d = feed(A)
pet = d.get('pet') or {}
items = {it['n']: it for it in pet.get('items', [])}
check(pet.get('k') == 101 and pet.get('book') is True and len(items) == 7, "a. the feed's pet: line 101, its book, 7 entries", (pet.get('k'), list(items)))
check(all(items[n].get('cmd') is True and not items[n].get('bind') and 'pv' not in items[n] for n in ("Attack", "Follow", "Stay", "Defensive")),
      "a. Attack / Follow / Stay / Defensive are COMMANDS: no Bind, no number", {n: (items[n].get('cmd'), items[n].get('pv')) for n in items})
check(items["Great Stamina"].get('pas') is True and not items["Great Stamina"].get('bind') and 'pv' not in items["Great Stamina"],
      "a. Great Stamina is a PASSIVE: no Bind, no number")
check(items["Growl"].get('bind') is True and items["Bite"].get('bind') is True and items["Growl"].get('id') == 2649,
      "a. Growl and Bite are SPELLS (bind)")
pvs = [items["Growl"].get('pv'), items["Bite"].get('pv')]
player_pv = [it.get('pv') for it in d['items'] if it.get('pv') is not None]
check(all(isinstance(v, int) for v in pvs) and len(set(pvs + player_pv)) == len(pvs) + len(player_pv),
      "a. each pet spell has its own number, none a player spell's", (pvs, player_pv))
check(min(pvs) > max(player_pv), "a. the pet's numbers come after the player's top ranks", (pvs, player_pv))
check(items["Growl"].get('ac') is True and items["Growl"].get('ae') is True and items["Bite"].get('ae') is None,
      "a. the auto-cast corners stay (Growl casts itself, Bite may)")
check(A.eval('WowCompanion.spellsPetName(2649)') == "Growl" and A.eval('WowCompanion.spellsPetName(1978)') is None,
      "a. WC.spellsPetName: Growl is a pet spell, Serpent Sting is not")
check(A.eval('WowCompanionSpellsDB.petSpellBy[WHOK][2649]') == "Growl" and A.eval('WowCompanionSpellsDB.petSpellBy[WHOK][4187]') is None,
      "a. the pet's spells' names kept for this character (name-realm), not its passive")
A.execute('PETBAR[1] = 2649')
d2 = feed(A)
gi = {it['n']: it for it in d2['pet']['items']}
check(gi["Growl"].get('pb') is True and gi["Bite"].get('pb') is None, "a. `pb`: Growl is on the pet bar (the game's not-missing), Bite is not")
keep_spells("spells_hunter", A)

# ================================================================ b. the room's Pet tab
i, entries = pet_tab(A)
T = A.eval('BK.tabs()')
check(i == len(T) and [e[0] for e in entries] == ["Attack", "Follow", "Stay", "Growl", "Bite", "Great Stamina", "Defensive"],
      "b. Book's Pet tab: the last tab, the pet's 7 entries in the book's order", (i, len(T), entries))
check([e[0] for e in entries if e[1]] == ["Growl", "Bite"], "b. only Growl and Bite are bindable")
A.execute('BK.ctl.tab, BK.ctl.onTabs = %d, true; BK.press("PADDRIGHT")' % (i - 1))
check(A.eval('BK.ctl.tab') == i and ctl(A).get('line') == 101, "b. D-pad Right on the tab row reaches the Pet tab; the report's line is 101",
      (A.eval('BK.ctl.tab'), ctl(A).get('line')))
pick(A, "Attack")
A.execute('BK.press("PAD1")')
check(ctl(A).get('note') == "bind-not-bindable" and A.eval('BK.ctl.mode') == "book", "b. A on Attack (a command): not bindable, no box")
pick(A, "Great Stamina")
A.execute('BK.press("PAD1")')
check(ctl(A).get('note') == "bind-not-bindable", "b. A on Great Stamina (a passive): not bindable")
pick(A, "Growl")
A.execute('BK.press("PAD1")')
c = ctl(A)
check(c.get('mode') == "bind" and c.get('bind') == 2649 and c.get('line') == 101 and c.get('sel') == 2649,
      "b. A on Growl: the Bind box waits for Growl (bind 2649, sel 2649, line 101)", c)
check(A.eval('BK.ctl.bind.slot') == 5004 and A.eval('BK.ctl.bind.pet') is True, "b. the box's slot is the pet slot as it travels (5000 + 4)")
A.execute('BK.ctl.mode = "book"; BK.ctl.bind = nil')
pick(A, "Growl")
A.execute('BK.press("PAD1")')
keep_last("spellsctl_pet_bind", A, 'spellsctl')
A.execute('BK.ctl.mode = "book"; BK.ctl.bind = nil; BK.ctl.tab, BK.ctl.onTabs, BK.ctl.page, BK.ctl.idx = %d, false, 1, 4' % i)
A.execute('BK.press("PADDDOWN"); BK.press("PADDUP")')
c = ctl(A)
check(c.get('line') == 101 and c.get('sel') == 2649 and c.get('mode') == "book", "b. the glow back on Growl in the book (the fixture)", c)
keep_last("spellsctl_pet_book", A, 'spellsctl')

# ================================================================ c. Normal: the game's own way
p0 = int(A.eval('#PICKS'))
ack, placed, mode = bind(A, "Growl", "RT", "PAD1")
want = A.eval('BK.storageFor(1, 3, 8)')
picks = [A.eval('PICKS[%d]' % k) for k in range(p0 + 1, int(A.eval('#PICKS')) + 1)]
check(ack.get('ok') is True and placed == [want] and mode == "confirm" and ack.get('pad', {}).get('s') == want,
      "c. ZR + A: Growl placed on slot %s, the box asks keep / undo" % want, (ack, placed, mode))
check(picks == ["4:1"], "c. picked up from the PET's book (slot 4, bank 1): the game's own BindSpellBookItem road", picks)
check(list(A.eval('ACTIONINFO[%d]' % want).values()) == ["spell", 2649, "pet"], "c. the slot reads spell, 2649, pet")
keep(A)
check(feed(A)['pet']['items'][3].get('pad', [{}])[0].get('s') == want, "c. the feed names Growl's button (its bars: FindPetActionButtons)")
# Bite over Growl, then B: Growl comes back from the pet's book
p0 = int(A.eval('#PICKS'))
ack, placed, mode = bind(A, "Bite", "RT", "PAD1")
check(ack.get('ok') is True and list(A.eval('ACTIONINFO[%d]' % want).values()) == ["spell", 17253, "pet"], "c. Bite over it")
A.execute('BK.press("PAD2")')
picks = [A.eval('PICKS[%d]' % k) for k in range(p0 + 1, int(A.eval('#PICKS')) + 1)]
check(list(A.eval('ACTIONINFO[%d]' % want).values()) == ["spell", 2649, "pet"] and A.eval('BK.ctl.mode') == "bind",
      "c. B undoes: Growl back on the slot (from the pet's book), the box waits again", (A.eval('ACTIONINFO[%d]' % want), picks))
check("4:1" in picks and not any(p.startswith("spell:") for p in picks), "c. ... picked up from the pet's book, never C_Spell.PickupSpell", picks)
A.execute('BK.ctl.mode = "book"; BK.ctl.bind = nil; BK.ctl.undo = nil')
check(A.eval('(WowCompanionSpellsDB.lowPickBy or {})[WHOK]') is None or A.eval('WowCompanionSpellsDB.lowPickBy[WHOK][%d]' % want) is None,
      "c. no rank pick remembered for a pet's spell")
keep_spells("spells_hunter_bound", A)

# ================================================================ d. In combat
A.execute('WowCompanion.padMap.setAttack(true); runTimers(NOW + 1)')
check(A.eval('WowCompanion.padMap.attackOn()') is True, "d. Start attack on cast is ON (a pet's spell must never take its lines)")
ack, placed, mode = bind(A, "Bite", "LT", "PADDLEFT", fight=True)
fs = A.eval('BK.storageFor(1, 2, 1)')
check(ack.get('ok') is True and ack.get('pad', {}).get('fight') is True and placed == [], "d. In combat ZL + D-pad Left: Bite on the fight layer, no bar write",
      (ack, placed))
keep(A)
A.execute('runTimers(NOW + 2)')
check(A.eval('FT(%d).pet' % fs) is True and A.eval('FT(%d).n' % fs) == "Bite" and A.eval('FT(%d).lo' % fs) is None,
      "d. the entry: Bite, marked pet, no rank pick", (A.eval('FT(%d).n' % fs), A.eval('FT(%d).pet' % fs)))
check(A.eval('HDR("wc-ft-%d")' % fs) == "/cast Bite", "d. its fight text: /cast Bite (no /startattack: the pet's attack)", A.eval('HDR("wc-ft-%d")' % fs))
ack, placed, mode = bind(A, "Growl", None, "PAD3", fight=True)
keep(A)
A.execute('runTimers(NOW + 2)')
check(ack.get('ok') is True and A.eval('FACE(5).pet') is True and A.eval('FACE(5).n') == "Growl", "d. bare X's In-combat spell: Growl (pet)", ack)
check(A.eval('XSPELL()') == "Growl", "d. X's fight spell attribute is the NAME (CastSpellByName, the game's /cast), not 2649", A.eval('XSPELL()'))

# ================================================================ e. L3
ack, placed, mode = bind(A, "Growl", None, "PADLSTICK")
keep(A)
A.execute('runTimers(NOW + 2)')
check(ack.get('ok') is True and A.eval('STK("1:9").pet') is True and A.eval('STK("1:9").n') == "Growl", "e. L3: Growl on the stick", ack)
b = A.eval('rawget(_G, "WowCompanionStickL3")')
check(b is not None and A.eval('WowCompanionStickL3.a.macrotext') == "/cast Growl", "e. the stick's button: /cast Growl",
      A.eval('rawget(_G, "WowCompanionStickL3") and WowCompanionStickL3.a.macrotext'))

# ================================================================ f. the Cast bar tab
pick(A, "Bite")
A.execute('BK.press("PAD1"); WowCompanion.pad.rows["padtab"].handler(nil, 39); WowCompanion.pad.rows["padtab"].handler(nil, 26)')
check(A.eval('BK.ctl.bind.cb') is True, "f. the Bind box's Cast bar tab (padtab 39 then 26)")
A.execute('BK.press("PAD1"); runTimers(NOW + 2)')
st = A.eval('(WowCompanionSpellsDB.castBar or {})[WHOK]')
check(st is not None and st[1] is not None and st[1].id == 17253 and st[1].k == "spell", "f. A: Bite on the cast bar's slot 1 (its spell id)",
      st and st[1] and (st[1].k, st[1].id))
row = A.eval('WowCompanion.pad.rows.castbar')
check(row is not None and A.eval('WowCompanion.pad.rows.castbar.cells[0].a["type"]') == "spell"
      and A.eval('WowCompanion.pad.rows.castbar.cells[0].a["spell"]') == "Bite", "f. its cell casts the NAME: type spell, spell Bite",
      row is not None and A.eval('WowCompanion.pad.rows.castbar.cells[0].a["spell"]'))

# ================================================================ g. the Controller room and a spell radial
bl = A.eval('WowCompanion.spellsBindableList()')
lst = [(bl[k].id, bl[k].slot, bl[k].pet) for k in range(1, len(bl) + 1)]
check(lst[-2:] == [(2649, 5004, True), (17253, 5005, True)] and all(x[2] is None for x in lst[:-2]),
      "g. the chooser's list: the pet's spells last (their travelling slots), the player's first", lst)
check(A.eval('WowCompanion.spellsBookSlotOf(17253)') == 5005, "g. spellsBookSlotOf(Bite) = its pet slot")
check(A.eval('WowCompanion.spellRadial.put(1, 1, 2649)') is True, "g. Growl onto spell radial 1")
A.execute('runTimers(NOW + 2)')
sr = docs(A, 'sradial')[-1]['r']
col = sr['list'][0]['items'][0].get('col')
check(col is not None and A.eval('WowCompanion.pad.rows.sradial.cells[%d].a["spell"]' % col) == "Growl",
      "g. the radial's cell casts the NAME (spell Growl)", (col, sr['list'][0]['items'][0]))
check(sr['list'][0]['items'][0].get('n') == "Growl", "g. the radial's document names it (Growl)")
# touchSpell on a WoW Defaults button: the Controller room's put, the game's own way
A.execute('local c = BK.ctl; c.croom, c.ctrlTab, c.onTabs = true, true, false; WowCompanion.padTabCtl.enter()')
p0 = int(A.eval('#PICKS'))
A.execute('WowCompanion.padTabCtl.touchSet(3); WowCompanion.padTabCtl.touchPos(2); WowCompanion.padTabCtl.touchSpell(17253); runTimers(NOW + 1)')
picks = [A.eval('PICKS[%d]' % k) for k in range(p0 + 1, int(A.eval('#PICKS')) + 1)]
s32 = A.eval('BK.storageFor(1, 3, 2)')
check(picks == ["5:1"] and list((A.eval('ACTIONINFO[%d]' % s32) or {}).values()) == ["spell", 17253, "pet"],
      "g. the Controller room's touch put: Bite on ZR + D-pad Up from the pet's book (5:1)", (picks, A.eval('ACTIONINFO[%d]' % s32)))
check(A.eval('#VIOL') == 0 and errs(A) == [], "a-g: no lockdown breach, no Lua error", (A.eval('VIOL[1]'), errs(A)[:3]))
check(A.eval('#SPY') == 0, "a-g: no spied call (no macro, binding, CVar, CastSpellByName or Blizzard window code)", A.eval('SPY[1]'))

# ================================================================ h. the two taps
H = boot()
d = feed(H)
bite = [it for it in d['pet']['items'] if it['n'] == "Bite"][0]
v = bite['pv']
H.execute('BK.ctl.mode = "book"; BK.ctl.bind = nil')
H.execute('WowCompanion._spellsBookKey("vb%d"); WowCompanion._spellsBookKey("vo%d")' % (v // 13, v % 13))
check(H.eval('BK.ctl.bind and BK.ctl.bind.id') == 17253 and H.eval('BK.ctl.bind.pet') is True and H.eval('BK.ctl.mode') == "bind",
      "h. Bite's two taps (pv %d: vb%d then vo%d) open the Bind box for Bite" % (v, v // 13, v % 13), H.eval('BK.ctl.mode'))
check(errs(H) == [], "h. no Lua error", errs(H)[:2])

# ================================================================ i. the pet dismissed
A.execute('petOut(nil)')
d = feed(A)
check(d.get('pet') is None and pet_tab(A)[0] is None, "i. the pet dismissed: no pet in the feed, no Pet tab in the room")
check(A.eval('WowCompanion.spellsPetName(2649)') == "Growl", "i. the kept name still says Growl is a pet spell (cast by name)")
check(A.eval('WowCompanion.spellRadial.put(1, 2, 17253)') is True, "i. Bite onto the radial too (the pet away: its id known)")
A.execute('runTimers(NOW + 2)')
sr = docs(A, 'sradial')[-1]['r']
cols = {it['id']: it.get('col') for it in sr['list'][0]['items']}
check(all(cols.get(x) is not None for x in (2649, 17253)) and A.eval('WowCompanion.pad.rows.sradial.cells[%d].a["spell"]' % cols[17253]) == "Bite",
      "i. the rebuilt radial cells cast both by name, the pet away", cols)
check(A.eval('WowCompanion.spellsBindableList()[5]') is None, "i. the chooser lists no pet spell now")
check(errs(A) == [], "i. no Lua error", errs(A)[:2])

# ================================================================ j. a Warlock's imp (token commands)
J = boot(pre='CLASS = "WARLOCK"; BOOK = { 686 }; LINES = { { name = "General", iconID = 1, itemIndexOffset = 0, numSpellBookItems = 1 } }',
         pet='IMP')
d = feed(J)
im = {it['n']: it for it in d['pet']['items']}
check(im["PET_ACTION_ATTACK"].get('cmd') is True and im["PET_ACTION_FOLLOW"].get('cmd') is True and 'pv' not in im["PET_ACTION_ATTACK"],
      "j. the imp's token-named commands (no spell id) are commands")
check(all(im[n].get('bind') is True and isinstance(im[n].get('pv'), int) for n in ("Firebolt", "Blood Pact", "Phase Shift")),
      "j. Firebolt, Blood Pact, Phase Shift are spells with numbers")
ack, placed, mode = bind(J, "Firebolt", "LT", "PAD4")
check(ack.get('ok') is True and list(J.eval('ACTIONINFO[%d]' % J.eval('BK.storageFor(1, 2, 6)')).values()) == ["spell", 3110, "pet"],
      "j. ZL + Y: Firebolt placed the game's own way", ack)
keep(J)
ack, placed, mode = bind(J, "Firebolt", "both", "PAD3", fight=True)
keep(J)
J.execute('runTimers(NOW + 2)')
s4 = J.eval('BK.storageFor(1, 4, 5)')
check(J.eval('HDR("wc-ft-%d")' % s4) == "/cast Firebolt", "j. In combat ZL + ZR + X: /cast Firebolt", J.eval('HDR("wc-ft-%d")' % s4))
check(J.eval('#VIOL') == 0 and errs(J) == [] and J.eval('#SPY') == 0, "j. no breach, no error, no spied call", errs(J)[:2])
J.execute('PETBAR[1] = 3110')
keep_spells("spells_warlock", J)

# ================================================================ k. a Shaman: no pet
K = boot(pre='CLASS = "SHAMAN"; SPELLNAME[403] = "Lightning Bolt"; SPELLNAME[331] = "Healing Wave"; BOOK = { 403, 331 }; '
             'LINES = { { name = "General", iconID = 1, itemIndexOffset = 0, numSpellBookItems = 2 } }', pet=None)
d = feed(K)
check(d.get('pet') is None and pet_tab(K)[0] is None, "k. a Shaman (no pet): no pet in the feed, no Pet tab")
check([it['n'] for it in d['items']] == ["Lightning Bolt", "Healing Wave"] and all(isinstance(it.get('pv'), int) for it in d['items']),
      "k. his own spells as before, each numbered")
bl = K.eval('WowCompanion.spellsBindableList()')
check(all(bl[k].pet is None for k in range(1, len(bl) + 1)) and len(bl) == 2, "k. the chooser's list: his two spells, no pet entry")
check(K.eval('WowCompanion.spellsPetName(403)') is None and K.eval('(WowCompanion.spellsBankSlot(1))') == 1 and K.eval('select(2, WowCompanion.spellsBankSlot(5003))') == 1,
      "k. no pet spell, a book slot stays the player's")
check(errs(K) == [], "k. no Lua error", errs(K)[:2])
keep_spells("spells_shaman", K)

# ================================================================ l. in combat; another character
Lc = boot()
Lc.execute('COMBAT = true')
pick(Lc, "Growl")
p0 = int(Lc.eval('#PICKS'))
Lc.execute('BK.press("PAD1")')
check(ctl(Lc).get('note') == "bind-combat" and int(Lc.eval('#PICKS')) == p0 and int(Lc.eval('#PLACED')) == 0,
      "l. in combat: the box refuses at once (bind-combat), nothing picked up or placed")
Lc.execute('COMBAT = false')
O = boot(who="Other")
check(O.eval('(WowCompanionSpellsDB.petSpellBy or {})["Korvash-Realm"]') is None and O.eval('WowCompanionSpellsDB.petSpellBy[WHOK][2649]') == "Growl",
      "l. the names are kept per character (Other-Realm's own)")
check(Lc.eval('#SPY') == 0 and O.eval('#SPY') == 0 and errs(Lc) == [] and errs(O) == [], "l. no spied call, no Lua error")
# PetHasSpellbook() read truthy, as the game reads it (a 1 counts); false = no Pet tab (the game's own rule)
Q = boot(pre='function PetHasSpellbook() return PETBOOK ~= nil and 1 or nil end')
check(feed(Q)['pet'].get('book') is True and pet_tab(Q)[0] is not None, "l. PetHasSpellbook() answering 1: the Pet tab shows (book true)")
Q.execute('PETHASBOOK = false; function PetHasSpellbook() return PETHASBOOK end')
check(feed(Q)['pet'].get('book') is not True and pet_tab(Q)[0] is None, "l. PetHasSpellbook() false: no Pet tab (the game's own rule)")
# hides passives: the tab stays, its passive goes
P = boot(pre='CVARB.spellBookHidePassives = true')
i, entries = pet_tab(P)
check(i is not None and "Great Stamina" not in [e[0] for e in entries] and len(entries) == 6, "l. passives hidden: the Pet tab without Great Stamina")

# ================================================================ m-p. the review (Spells s10a / s10b, Book b63a, PadMap m163a)
# p. (s10b) PASSIVE is the game's own Spellbook word, never the pet bar's state: Seduction (packed with state 1, it cannot auto-cast) is an
#    ACTIVE spell - numbered, bindable, kept by name, listed with Hide Passives on - even where IsPetActionPassive reads the state
#    (the strictest stub: state 1 = "passive"); Soothing Aura, a true passive (IsSpellBookItemPassive), stays without Bind
for hide in (False, True):
    Pp = boot(pre='CLASS = "WARLOCK"; BOOK = { 686 }; LINES = { { name = "General", iconID = 1, itemIndexOffset = 0, numSpellBookItems = 1 } }; '
                  'C_PetInfo.IsPetActionPassive = function(a) return type(a) == "number" and math.floor(a / 16777216) % 256 == 1 end'
                  + ('; CVARB.spellBookHidePassives = true' if hide else ''), pet='SUCCUBUS')
    d = feed(Pp)
    si = {it['n']: it for it in d['pet']['items']}
    i, entries = pet_tab(Pp)
    names = [e[0] for e in entries]
    tag = "Hide Passives on" if hide else "Hide Passives off"
    check(si["Seduction"].get('bind') is True and 'pas' not in si["Seduction"] and isinstance(si["Seduction"].get('pv'), int),
          "p. %s: Seduction (state 1, no auto-cast) is an active spell: bind, a number, not passive" % tag,
          {k: si["Seduction"].get(k) for k in ('bind', 'pas', 'pv')})
    check(si["Soothing Aura"].get('pas') is True and not si["Soothing Aura"].get('bind'), "p. %s: Soothing Aura (a true passive) has no Bind" % tag)
    check(("Seduction" in names) and (("Soothing Aura" in names) != hide), "p. %s: the Pet tab lists Seduction; Soothing Aura only with passives shown" % tag,
          names)
    if not hide:
        check(Pp.eval('WowCompanion.spellsPetName(6358)') == "Seduction", "p. Seduction's name is kept (our buttons cast it by name)")
        ack, placed, mode = bind(Pp, "Seduction", "LT", "PAD3")
        sx = int(Pp.eval('BK.storageFor(1, 2, 5)'))
        check(ack.get('ok') is True and list(Pp.eval('ACTIONINFO[%d]' % sx).values()) == ["spell", 6358, "pet"],
              "p. ZL + X: Seduction placed the game's own way", (ack, placed))
        keep(Pp)
        keep_spells("spells_succubus", Pp)
    check(errs(Pp) == [] and Pp.eval('#SPY') == 0 and Pp.eval('#VIOL') == 0, "p. %s: no Lua error, no spied call" % tag, errs(Pp)[:2])

# m. a charmed / possessed unit (Mind Control, Enslave): HasPetSpells() answers, PetHasSpellbook() does not - the game shows no Pet tab,
#    so its spells are never this character's pet spells (a mob's spell sharing an id with the player's own would be cast by bare name)
M = boot(pre='PETHASBOOK = false')
d = feed(M)
mi = {it['n']: it for it in (d.get('pet') or {}).get('items', [])}
check(d.get('pet', {}).get('book') is not True and pet_tab(M)[0] is None, "m. a charmed unit: no book, no Pet tab")
check(M.eval('WowCompanion.spellsPetName(2649)') is None and M.eval('(WowCompanionSpellsDB.petSpellBy or {})[WHOK]') is None,
      "m. its spells are not kept as this character's pet spells (no name, nothing stored)")
check(all('pv' not in it for it in mi.values()), "m. its spells get no numbers", {n: it.get('pv') for n, it in mi.items()})
check(M.eval('WowCompanion.spellsPetAt(5004)') is False and M.eval('WowCompanion.spellsPetSlotOf(2649)') is None,
      "m. a travelling pet slot reads nothing then (no pickup from a charmed unit's book)")
check(all(M.eval('WowCompanion.spellsBindableList()')[k].pet is None for k in range(1, len(M.eval('WowCompanion.spellsBindableList()')) + 1)),
      "m. the chooser lists none of its spells")
check(errs(M) == [] and M.eval('#SPY') == 0, "m. no Lua error, no spied call", errs(M)[:2])

# n. another pet / demon out while the Bind box waits: no route puts the new pet's spell (the box's slot 4 is the imp's Blood Pact now)
N = boot()
NFS = int(N.eval('BK.storageFor(1, 2, 1)'))
for fight, trig, key in ((True, "LT", "PADDLEFT"), (False, "RT", "PAD1"), (False, None, "PADLSTICK")):
    pick(N, "Growl")
    N.execute('BK.press("PAD1")')
    if fight:
        N.execute('BK.ctl.bind.fight = true')
    N.execute('PETBOOK = { IMP[1], IMP[2], IMP[4], IMP[4], IMP[5] }')          # slot 4 = Blood Pact (6307)
    p0, n0, a0 = int(N.eval('#PICKS')), int(N.eval('#PLACED')), int(N.eval('#SENT'))
    N.execute('hold(%s); BK.press("%s"); hold(nil)' % ('nil' if trig is None else '"%s"' % trig, key))
    a = acks(N, a0)
    route = "In combat" if fight else ("L3" if key == "PADLSTICK" else "Normal")
    check(a and a[-1].get('ok') is False and a[-1].get('why') == "unknown" and int(N.eval('#PICKS')) == p0 and int(N.eval('#PLACED')) == n0,
          "n. %s: the box's pet slot holds another spell now -> refused (unknown), nothing picked up or placed" % route, a and a[-1])
    N.execute('BK.ctl.mode = "book"; BK.ctl.bind = nil; BK.ctl.undo = nil; PETBOOK = HUNTER_PET')
check(N.eval('FT(%d)' % NFS) is None and N.eval('STK("1:9")') is None, "n. no fight-layer entry and no stick entry was stored")
check(errs(N) == [] and N.eval('#VIOL') == 0 and N.eval('#SPY') == 0, "n. no Lua error, no breach, no spied call", errs(N)[:2])

# o. a pet's In-combat spell keeps ITS id and picture with the pet away: this client's name lookup (C_Spell.GetSpellInfo by name) finds a
#    pet's spell only while that pet is out (a stub that never finds a pet's name by name: the strictest reading)
O2 = boot(pre='local gi = C_Spell.GetSpellInfo; PETNAMES = { Growl = true, Bite = true, Firebolt = true }; '
              'C_Spell.GetSpellInfo = function(x) if type(x) == "string" and PETNAMES[x] then return nil end return gi(x) end')
ack, placed, mode = bind(O2, "Growl", None, "PAD3", fight=True)
keep(O2)
ack2, placed, mode = bind(O2, "Bite", "LT", "PADDLEFT", fight=True)
keep(O2)
O2.execute('runTimers(NOW + 2)')
ofs = int(O2.eval('BK.storageFor(1, 2, 1)'))
check(ack.get('ok') is True and ack2.get('ok') is True, "o. Growl on bare X's In combat, Bite on ZL + D-pad Left's", (ack, ack2))
check(O2.eval('FT(%d).i' % ofs) == 130000 + 17253, "o. the fight layer's Bite: its own picture (by its id, not its name)", O2.eval('FT(%d).i' % ofs))
O2.execute('petOut(nil)')
cf = O2.eval('WowCompanion.padMap.slotAt(1, 1, 5)')
cf = cf and cf.cf
check(cf is not None and cf.id == 2649 and cf.n == "Growl" and cf.i == 130000 + 2649,
      "o. the pet away: bare X's `cf` is still Growl by its id and picture (the room's skip, B's undo, Home's picture)",
      cf and (cf.n, cf.id, cf.i))
check(O2.eval('XSPELL()') == "Growl", "o. X still casts Growl by name", O2.eval('XSPELL()'))
check(errs(O2) == [] and O2.eval('#SPY') == 0, "o. no Lua error, no spied call", errs(O2)[:2])

# ================================================================ render aid: PET_DUMP=<dir> writes the Controller room's documents (never committed)
# the room's WoW Defaults, ZR + D-pad Up chosen, its chooser open on the Spells tab with the glow on Growl (the class's spells, then the pet's)
if os.environ.get("PET_DUMP"):
    C = boot()
    C.execute('local c = BK.ctl; c.croom, c.ctrlTab, c.onTabs = true, true, false; WowCompanion.padTabCtl.enter()')
    C.execute('WowCompanion.padTabCtl.touchSet(3); WowCompanion.padTabCtl.touchPos(2); WowCompanion.padTabCtl.press("PAD1")')
    for _ in range(3):
        C.execute('WowCompanion.padTabCtl.press("PADDDOWN")')
    C.execute('WowCompanion.padMap.emit(true); runTimers(NOW + 1)')
    ch = docs(C, 'padtabctl')[-1]['c'].get('chooser', {})
    check(ch.get('list') == "spells" and ch.get('id') == 2649, "render aid: the chooser's Spells tab, the glow on Growl (row 4)", ch)
    dd = os.path.join(os.environ["PET_DUMP"], "docs")
    os.makedirs(dd, exist_ok=True)
    for name, typ in (("padmap_pet", "padmap"), ("padtabctl_pet_chooser", "padtabctl"), ("spellsctl_croom", "spellsctl")):
        for i in range(int(C.eval('#SENT')), 0, -1):
            t = C.eval('SENT[%d]' % i)
            try:
                dj = json.loads(t)
            except Exception:
                continue
            if isinstance(dj, dict) and dj.get('type') == typ:
                with open(os.path.join(dd, name + ".json"), 'w', encoding='utf-8', newline='\n') as fh:
                    fh.write(t)
                break
    with open(os.path.join(dd, "spells_croom.json"), 'w', encoding='utf-8', newline='\n') as fh:
        fh.write(C.eval('WowCompanion.encode({ type = "spells", v = "s12", s = WowCompanion.collectSpells() })'))

# ================================================================ the fixtures
os.makedirs(FIXDIR, exist_ok=True)
stale = []
for name, text in sorted(FIX.items()):
    fp = os.path.join(FIXDIR, name + ".json")
    old = open(fp, encoding='utf-8').read() if os.path.isfile(fp) else None
    if os.environ.get("PARTY_FIXTURES_WRITE") == "1":
        with open(fp, 'w', encoding='utf-8', newline='\n') as fh:
            fh.write(text)
    elif old != text:
        stale.append(name)
check(len(FIX) == 7 and not stale, "the committed fixtures equal what the add-on sends now (%d files; PARTY_FIXTURES_WRITE=1 rewrites)" % len(FIX),
      stale[:4])

print("%d passed, %d failed" % (passes, fails))
sys.exit(1 if fails else 0)
