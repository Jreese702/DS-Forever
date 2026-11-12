"""RANK UP ON THE BUTTONS (PadMap m143, Book b51, Spells s8), off-device: the REAL add-on files under tools/wowstub.py's stubbed game.

    python tools/test_rankup.py

The user, 2026-11-12: "I know we have something in place to rank up abilities on login, but maybe for skills that are already on a bar a
prompt to allow them to be ranked up when a new rank is learned is available?" The picks: "Prompt, A upgrades all" (Home's one-at-a-time
prompt; A = every lower copy at once: the game's controller slots AND the keyboard bars, the spell radial, the In-combat entries), "Done
at once" (no Keep / Undo: one padack op "rankup" with the count), "Skip this rank" (the app's store: dsforever NewSpellsTest), "Never
touch those" (a rank Book's Bind placed BELOW the top on purpose is remembered and never offered nor upgraded) and "Keep the exact rank"
(the In-combat spells cast "Name(Rank N)"; rankSync no longer repoints an entry to the top rank).
  a a Mage with Frostbolt Rank 2 on two controller slots, a keyboard bar slot, a spell-radial place, a fight-layer In-combat entry and
    Y's In-combat spell; Rank 1 bound ON PURPOSE (Book's Bind of the rank picker's lower rank) on a slot and as an In-combat entry;
  b In-combat texts cast the exact rank ("/cast Frostbolt(Rank 2)", Y's "Frostbolt(Rank 2)"), the deliberate one "(Rank 1)";
  c Rank 3 learned: WC.rankUpList names 6 copies (2 kept on purpose); NOTHING written yet (no PlaceAction, the entries the same);
    SPELLS_CHANGED's rankSync does not repoint the entries (they keep casting Rank 2 until the player's A);
  d in combat: A is refused ("combat"), nothing changes (deferred);
  e A (padtab 38 + the spell's key, Book's route): all 6 become Rank 3 at once, the padack says 6, the deliberate Rank 1 slot / entry
    untouched; the texts cast Rank 3; a key WITHOUT the pick is Bind by touch as before (no rankup);
  f Rank 4 learned: asked again (6 copies);
  g every lower copy deliberate: no candidate (no prompt).
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
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from wowstub import GAME  # noqa: E402
fails = 0
passes = 0


def check(ok, name, got=''):
    global fails, passes
    print(('PASS ' if ok else 'FAIL ') + name + ((' -> ' + str(got)) if got != '' else ''))
    if ok:
        passes += 1
    else:
        fails += 1


# a Mage (test_book_bind.py's world) with Frostbolt's ranks, the bar slots, the cursor road and the spells' rank texts
EXTRA = r'''
CLASS = "MAGE"
SPELLNAME[133] = "Fireball"; SPELLNAME[116] = "Frostbolt"; SPELLNAME[205] = "Frostbolt"; SPELLNAME[837] = "Frostbolt"; SPELLNAME[7322] = "Frostbolt"
SUB = { [133] = "Rank 1", [116] = "Rank 1", [205] = "Rank 2", [837] = "Rank 3", [7322] = "Rank 4" }
BOOK = { 133, 116, 205 }
C_Spell.GetSpellSubtext = function(id) return SUB[id] end
-- by name: the top rank known (the book's highest); by id: that spell
C_Spell.GetSpellInfo = function(x)
    if type(x) == "string" then
        local best, bn
        for _, id in ipairs(BOOK) do
            if SPELLNAME[id] == x then local r = tonumber((SUB[id] or ""):match("%d+")) or 0; if not bn or r > bn then best, bn = id, r end end
        end
        if best then return { spellID = best, name = x, iconID = 130000 + best } end
        return nil
    end
    if SPELLNAME[x] then return { spellID = x, name = SPELLNAME[x], iconID = 130000 + x } end
end
CVARS = { GamepadStanceBarOverride = "3" }
C_CVar = { GetCVar = function(k) return CVARS[k] end }
C_ActionBar = { HasBonusActionBar = function() return false end }
function GetNumShapeshiftForms() return 0 end
function GetShapeshiftForm() return 0 end
function GetShapeshiftFormInfo() end
C_GamepadUI = {
    GetFirstGamepadActionBarStorageSlotIndexForActiveStance = function() return 0 end,
    GetFirstGamepadActionStorageSlotIndex = function() return 181 end,
    IsValidGamepadActionStorageSlotIndex = function() return true end,
}
Constants = { GamepadActionBarConstants = { NUM_SLOTS_PER_GAMEPAD_ACTION_BAR = 8,
    NUM_PAGEABLE_SLOTS_PER_GAMEPAD_ACTION_BAR_PAGE_UNIT_STANDARD_PAGE = 28, NUM_RESERVED_SLOTS_PER_GAMEPAD_ACTION_BAR_PAGE_UNIT = 4 } }
PAGE = 1
GamepadMainActionBarFrame.PageUnit.GetCurrentPage = function() return PAGE end
ACTIONINFO = {}
function GetActionInfo(s) local a = ACTIONINFO[s]; if a then return a[1], a[2] end end
function GetActionTexture(s) local a = ACTIONINFO[s]; return a and 130000 + a[2] or nil end
CURSOR = nil
PLACED = {}
function GetCursorInfo() if CURSOR then return CURSOR[1], CURSOR[2] end end
function ClearCursor() CURSOR = nil end
function PlaceAction(s) if COMBAT then VIOL[#VIOL + 1] = "PlaceAction" end; PLACED[#PLACED + 1] = s; local old = ACTIONINFO[s]; ACTIONINFO[s] = CURSOR; CURSOR = old end
function PickupAction(s) CURSOR = ACTIONINFO[s]; ACTIONINFO[s] = nil end
C_Spell.PickupSpell = function(id) CURSOR = { "spell", id } end
C_SpellBook = { GetSpellBookItemInfo = function(slot) local id = BOOK[slot]; return id and { spellID = id } or nil end,
                PickupSpellBookItem = function(slot) local id = BOOK[slot]; if id then CURSOR = { "spell", id } end end }
PADSTATE = {}
function GetGamePadState() return { buttons = PADSTATE } end
function hold(which) PADSTATE = {}; if which == "LT" or which == "both" then PADSTATE[LT_I] = true end
    if which == "RT" or which == "both" then PADSTATE[RT_I] = true end end
function toolbar(on) if type(WowCompanionSpellsDB) ~= "table" then WowCompanionSpellsDB = {} end
    WowCompanionSpellsDB.dpad = WowCompanionSpellsDB.dpad or {}; WowCompanionSpellsDB.dpad[WHO .. "-" .. REALM] = on end
function learn(id) BOOK[#BOOK + 1] = id; fire("LEARNED_SPELL_IN_SKILL_LINE", id, 2); fire("SPELLS_CHANGED"); runTimers(NOW + 2) end
function bookSlot(id) for i, x in ipairs(BOOK) do if x == id then return i end end end
WHOK = WHO .. "-" .. REALM
function FT(s) local t = (WowCompanionSpellsDB.fightBy or {})[WHOK]; return t and t[s] end
function FACE(p) local t = (WowCompanionSpellsDB.faceBy or {})[WHOK]; return t and t[p] end
function HDR(k) local h = rawget(_G, "WowCompanionFaceRouter"); return h and h.a[k] end
function YSPELL() local b = rawget(_G, "WowCompanionCombatFace6"); return b and b.a["wc-fight-spell"] end
'''


def boot(pre=''):
    L = lupa.LuaRuntime(unpack_returned_tuples=True)
    L.execute(GAME)
    L.execute(EXTRA)
    if pre:
        L.execute(pre)
    load = L.eval('function(src, name) local f, e = loadstring(src, name); if not f then error(e) end; return f end')
    for k in ("safety", "tappad", "spells", "book", "padmap", "padtabctl", "radial"):
        load(SRC[k], '@' + os.path.basename(F[k]))(os.path.basename(F[k]))
    L.execute('fire("PLAYER_LOGIN"); fire("PLAYER_ENTERING_WORLD", true, false); runTimers(NOW + 10)')
    # the book's place of a spell (Book's own list reads skill lines this stub has none of: the game's, by the book above)
    L.execute('BK = WowCompanion._spellsBook; BK.ctl.on = true; BK.ctl.pg = "book"; toolbar(false)\n'
              'WowCompanion.spellsBookSlotOf = function(id) return bookSlot(id) end')
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


def rk(L, ids):
    r = L.eval('WowCompanion.rankUpList({%s})' % ','.join(str(i) for i in ids))
    return [dict(x) for x in r.values()] if r is not None else None


def deliberate(L, slot_pos, book_id):
    """Book's Bind of [book_id] (the rank picker's lower rank) with LT held on [slot_pos]: the place it took"""
    n0 = int(L.eval('#PLACED'))
    L.execute('BK.startBind({ t = 1, slot = bookSlot(%d), id = %d }); hold("LT"); BK.press("%s"); hold(nil)' % (book_id, book_id, slot_pos))
    L.execute('BK.ctl.mode = "book"; BK.ctl.bind = nil; BK.ctl.undo = nil')
    return [L.eval('PLACED[%d]' % i) for i in range(n0 + 1, int(L.eval('#PLACED')) + 1)]


def rankacks(L, start=0):
    return [d for d in docs(L, 'padack', start) if d.get('op') == 'rankup']


comp = lupa.LuaRuntime().eval('function(s, n) local f, e = loadstring(s, n); return e end')
for k in ("padmap", "book", "spells"):
    e = comp(SRC[k], k)
    check(e is None, "Lua 5.1 compiles %s (200 locals, 60 upvalues)" % os.path.basename(F[k]), e or '')
check('local PV = "m143"' in SRC["padmap"] and 'local BV = "b51"' in SRC["book"] and 'local SV = "s8"' in SRC["spells"],
      "the versions: PadMap m143, Book b51, Spells s8")
QUOTE = "I know we have something in place to rank up abilities on login"
check(all(QUOTE in SRC[k] for k in ("padmap", "book", "spells")), "each changed file's header note quotes the user")
check('WC.rankUpList' in SRC["spells"] and 'doc.rk = rk' in SRC["spells"], "Spells s8: the feed's `rk` comes from WC.rankUpList (read only)")

# ================================================================ a. the world
A = boot()
check(errs(A) == [], "a. the world loads (a Mage: Frostbolt Rank 1 and 2 known)", errs(A)[:2])
S1, S2, KB = A.eval('BK.storageFor(1, 3, 1)'), A.eval('BK.storageFor(1, 4, 6)'), 7
A.execute('ACTIONINFO[%d] = { "spell", 205 }; ACTIONINFO[%d] = { "spell", 205 }; ACTIONINFO[%d] = { "spell", 205 }' % (S1, S2, KB))
A.execute('ACTIONINFO[%d] = { "spell", 133 }' % (KB + 1))                    # another spell: never touched
check(A.eval('WowCompanion.spellRadial.put(3, 205)') is True, "a. Frostbolt Rank 2 on the spell radial's place 3")
FTS = A.eval('BK.storageFor(1, 2, 3)')
check(A.eval('WowCompanion.padMap.putCombat(1, 2, 3, bookSlot(205))') is True and A.eval('FT(%d).lo' % FTS) is None,
      "a. Frostbolt Rank 2 (the top) as ZL + D-pad Right's In-combat spell: not marked deliberate")
check(A.eval('WowCompanion.padMap.putCombatFace(6, bookSlot(205))') is True and A.eval('FACE(6).id') == 205, "a. ... and Y's In-combat spell")
LOS = A.eval('BK.storageFor(1, 3, 2)')
check(A.eval('WowCompanion.padMap.putCombat(1, 3, 2, bookSlot(116))') is True and A.eval('FT(%d).lo' % LOS) is True,
      "a. Rank 1 picked on purpose as ZR + D-pad Up's In-combat spell: marked `lo` (never touched)")
placed = deliberate(A, "PADDUP", 116)
DEL = A.eval('BK.storageFor(1, 2, 2)')
check(placed == [DEL] and A.eval('ACTIONINFO[%d][2]' % DEL) == 116 and A.eval('WowCompanionSpellsDB.lowPickBy[WHOK][%d]' % DEL) == 116,
      "a. Book's Bind of the rank picker's Rank 1 on ZL + D-pad Up: placed and remembered as picked on purpose (per character)", placed)
TOPB = A.eval('BK.storageFor(1, 2, 4)')
A.execute('BK.startBind({ t = 1, slot = bookSlot(205), id = 205 }); hold("LT"); BK.press("PADDDOWN"); hold(nil); BK.ctl.mode = "book"; BK.ctl.bind = nil')
check(A.eval('ACTIONINFO[%d][2]' % TOPB) == 205 and A.eval('WowCompanionSpellsDB.lowPickBy[WHOK][%d]' % TOPB) is None,
      "a. Book's Bind of the TOP rank (Rank 2) on ZL + D-pad Down: not remembered (it joins the prompt)")

# ================================================================ b. the exact rank
check(A.eval('HDR("wc-ft-%d")' % FTS) == "/cast Frostbolt(Rank 2)", "b. the fight layer casts the exact rank: /cast Frostbolt(Rank 2)",
      A.eval('HDR("wc-ft-%d")' % FTS))
check(A.eval('HDR("wc-ft-%d")' % LOS) == "/cast Frostbolt(Rank 1)", "b. the deliberate one: /cast Frostbolt(Rank 1)", A.eval('HDR("wc-ft-%d")' % LOS))
check(A.eval('YSPELL()') == "Frostbolt(Rank 2)", "b. Y's In-combat spell: Frostbolt(Rank 2) (the router's spell attribute)", A.eval('YSPELL()'))

# ================================================================ c. Rank 3 learned
n0 = int(A.eval('#PLACED'))
A.execute('learn(837)')
got = rk(A, [837])
check(got == [{'id': 837, 'n': 7, 'kept': 2}], "c. Rank 3 learned: 7 lower copies (2 controller + 1 keyboard + ZL + D-pad Down, the radial, "
      "the fight layer, Y), 2 kept on purpose", got)
check(int(A.eval('#PLACED')) == n0 and A.eval('FT(%d).id' % FTS) == 205 and A.eval('FACE(6).id') == 205
      and A.eval('WowCompanion.spellRadial.get(3)') == 205, "c. NOTHING written before the player's A (no PlaceAction, the entries as they were)")
check(A.eval('HDR("wc-ft-%d")' % FTS) == "/cast Frostbolt(Rank 2)" and A.eval('YSPELL()') == "Frostbolt(Rank 2)",
      "c. SPELLS_CHANGED's rankSync no longer repoints them: still Rank 2 exactly", (A.eval('HDR("wc-ft-%d")' % FTS), A.eval('YSPELL()')))
check(rk(A, [205]) is None and rk(A, [133]) is None, "c. a rank that is not the top now / a spell with no lower copy: no candidate")
doc = A.eval('WowCompanion.collectSpells()')
feed = [dict(x) for x in doc.rk.values()] if doc is not None and doc.rk is not None else None
check(feed == [{'id': 837, 'n': 7, 'kept': 2}], "c. Spells s8's document carries it as `rk` (the news' candidates)", feed)

# ================================================================ d. in combat: deferred
a0 = int(A.eval('#SENT'))
A.execute('COMBAT = true')
A.execute('WowCompanion.pad.rows["padtab"].handler(nil, 38); WowCompanion._spellsBookKey(837)')
acks = rankacks(A, a0)
check(len(acks) == 1 and acks[0]['ok'] is False and acks[0].get('why') == "combat", "d. in combat: refused 'combat'", acks)
check(int(A.eval('#PLACED')) == n0 and A.eval('FT(%d).id' % FTS) == 205 and A.eval('#VIOL') == 0, "d. ... nothing placed, nothing changed")
A.execute('COMBAT = false')

# ================================================================ e. A upgrades all, at once
a0 = int(A.eval('#SENT'))
A.execute('WowCompanion._spellsBookKey(837); runTimers(NOW + 1)')
check(rankacks(A, a0) == [], "e. a spell's key with no fresh pick is not a rank up (Bind by touch, as before)")
A.execute('BK.ctl.mode = "book"; BK.ctl.bind = nil; NOW = NOW + 3')
a0 = int(A.eval('#SENT'))
A.execute('WowCompanion.pad.rows["padtab"].handler(nil, 38); WowCompanion._spellsBookKey(837); runTimers(NOW + 1)')
acks = rankacks(A, a0)
check(len(acks) == 1 and acks[0]['ok'] is True and acks[0].get('idx') == 7 and acks[0].get('what') == "837",
      "e. A (padtab 38 + the spell's key): one padack op rankup, idx 7 (\"Upgraded on 7 buttons\")", acks)
check(all(A.eval('ACTIONINFO[%d][2]' % s) == 837 for s in (S1, S2, KB, TOPB)), "e. the controller slots, the keyboard bar slot: Rank 3")
check(A.eval('ACTIONINFO[%d][2]' % (KB + 1)) == 133, "e. another spell's slot untouched")
check(A.eval('WowCompanion.spellRadial.get(3)') == 837 and A.eval('FT(%d).id' % FTS) == 837 and A.eval('FACE(6).id') == 837,
      "e. the radial place, the fight layer, Y: Rank 3")
check(A.eval('ACTIONINFO[%d][2]' % DEL) == 116 and A.eval('FT(%d).id' % LOS) == 116, "e. the deliberate Rank 1 slot and In-combat entry: untouched")
check(A.eval('HDR("wc-ft-%d")' % FTS) == "/cast Frostbolt(Rank 3)" and A.eval('YSPELL()') == "Frostbolt(Rank 3)"
      and A.eval('HDR("wc-ft-%d")' % LOS) == "/cast Frostbolt(Rank 1)", "e. the texts cast Rank 3 now; the deliberate one Rank 1",
      (A.eval('HDR("wc-ft-%d")' % FTS), A.eval('YSPELL()')))
check(rk(A, [837]) is None, "e. nothing left to ask about Rank 3")
check(A.eval('#VIOL') == 0 and errs(A) == [], "e. no lockdown breach, no Lua error", (A.eval('VIOL[1]'), errs(A)[:2]))
check(A.eval('#SPY') == 0, "e. no spied call (no macro, binding, CVar or Blizzard window code)", A.eval('SPY[1]'))

# ================================================================ f. the next rank asks again
A.execute('learn(7322)')
got = rk(A, [7322])
check(got == [{'id': 7322, 'n': 7, 'kept': 2}], "f. Rank 4 learned: asked again (7 copies of Rank 3), the deliberate two still kept", got)

# ================================================================ g. every lower copy deliberate: no prompt
G = boot()
G.execute('WowCompanion.padMap.putCombat(1, 3, 2, bookSlot(116))')
deliberate(G, "PADDUP", 116)
G.execute('learn(837)')
check(rk(G, [837]) is None, "g. every lower copy picked on purpose: no candidate (no prompt)", rk(G, [837]))
G.execute('WHO = "Other"')
got = rk(G, [837])
check(got == [{'id': 837, 'n': 1}], "g. the deliberate record is this character's (name-realm): another character's view counts that slot", got)
check(errs(G) == [], "g. no Lua error", errs(G)[:2])

print("%d passed, %d failed" % (passes, fails))
sys.exit(1 if fails else 0)
