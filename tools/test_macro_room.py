"""THE MACROS ROOM (Spells s10, Book b63, PadMap m163), off-device: the REAL add-on files under tools/wowstub.py's stubbed game.

    python tools/test_macro_room.py

The user, 2026-11-13: "Lets scrap the smart ranged attack and start building a macro room. The calender can be moved up to a button on
the right side of the map name make it small. Use the room slot for macros." The user's picks: D1 a macro changes only on the player's
New / Done / Delete, never in a fight, on both tabs; D2 the game's own macro window: the room closes it once with the window's own close
button (a secure click), then works; D3 A edits, X binds (the shared Bind box, hosted as Bags hosts it); D4 a typed line, a picked spell /
item's line, recipe tiles from one or two picks; D5 the game's own bar only by the slot pressed in the Bind box, B undoes it.
Proven here:
  a versions, header notes, compile; the room's code never opens / closes the game's window, never runs a macro, never binds a key,
    never writes a CVar; its writes are CreateMacro / EditMacro / DeleteMacro only, each behind the one guard;
  b the lazy pad row: no "mac" row at login; Book's padtab pick 39 then 34 makes it (36 cells, the room's keys at fixed columns); in a
    fight it waits; the document says norow when the pad is full;
  c the grid: both tabs and their counts / caps (the game's own Constants), pages of 24, "+" after the last macro (not at the cap, not
    before the macros load), the D-pad's moves, a finger's s<k> / tg / tc / pp / pn;
  d New: the name keyboard (the chat's borrowed field), the name rules (trimmed, quotes dropped, 16 letters, unique on both tabs), the
    question-mark icon, the Character tab's flag; Cancel (kx) makes nothing; the new macro is selected and its editor opens;
  e the editor: rename (re-found by name after the sort), a line edited / emptied (removed) / inserted (X) / added (+ line), 255 with the
    new lines, a "|" line locked; every write ONLY inside the keyboard's Done;
  f every refusal, with no call made: in a fight (the text kept, the keyboard up), while the game's window shows, before the macros load,
    with the game rule on (the lock); a taken name, too long, empty;
  g + Spell/Item: the book's spells and bag items with a use (/cast, /use, /equip lines); the six recipes (one or two picks), tooltip once;
  h Delete (s15: More's Delete ASKS - Cancel [B] / Delete [A] - and the ask's A within 6 s deletes; it times out after; while it
    asks only A and B do anything); the selection moves to min(count, old); our
    stored binds of it go, and a rename carries them (this character's for a Character macro, every character's for a General one);
  i the Bind box for a macro (Book b63), hosted: Normal = the game's PickupMacro + PlaceAction on the pressed slot, read back, B undoes;
    L3 = the stick's own button with the macro's TEXT (no saved-macro attribute); In combat = the router's fight text; a gone macro =
    "missing"; the box's end hands the room back on its grid;
  j D2: the game's window shown -> the document says win, the `wx` cell is the window's own close button (a secure click), the write
    guard says "window" until it closes;
  k B: one step back, then Home (the controller back with "B", macpad BACK); the room left (f) drops the keyboard / box quietly;
  l the document's size stays small (one page of names, one body);
  m per character: the tab and selection are kept per name-realm.
  r (Spells s13; the user, 2026-11-16: "also things like my pet attack are missing from the spells options."): with a pet out, + Spell/Item
    lists the pet's commands (the game's secure slash lines, its words, the pet bar's pictures; the pet bar's own modes) and the pet
    book's spells after the player's spells; picks write ONE line; a recipe takes the pet's spells, never a command; no pet = none;
  s (PadMap m165; the user, 2026-11-16: "when using show tooltip on a macro it doesn't actually show the GCD when using it"): a macro on
    our buttons (X / Y / A, the D-pad's In-combat layer, L3) carries its live look - the picture of what it casts now (GetMacroSpell /
    GetMacroItem; its own icon when it has one), `ms` / `mt`, never `id` -; its GCD and cooldown on the facecd road (the plain length
    learned, the learned one in a fight), its state by the spell's id; a [mod:shift] switch re-sends the padmap and drops the old spell's
    cooldown; a secret read keeps the last plain look; reads only.
Spells s14 THE MACRO BUILDER v2 (the user, 2026-11-16: the recipes "make no sense" and only add lines; every RECOMMENDED pick of
design_macrobuilder: D1 a line = an action + its spell, then cases; D2 whole-macro presets on a new or empty macro, a second A replaces a
macro with lines; D3 strips; D4 Select = Done; D5 the controller's targets first in On; D6 the companion's own macros view only):
  e the editor's rows (name s0, icon s1, lines s2.., + Line / More s21, s22 - s15; Presets / Delete are More's rows); s16: More's
    Insert below = a new line under the glow's last line, its Action list open (X was);
  g the builder: action, spell, cases ("+ Case"), Done = ONE write; an unchanged draft writes nothing; B keeps the draft; Revert; Remove's
    second A; a fight refuses Done and keeps the draft; Select = Done; the hard faults (pick, long) and the soft ones (book, bags, else);
  o presets: which tiles show (and in what order, at most 8, Mouseover last only with room), the preview's picks, ONE write, a second A
    to replace a macro with lines;
  r the pet's tab: orders turn the line into /petattack ..., the pet's spells (also kept by name with the pet away);
  t the one grammar: round trips, text lines, locks, merging cases, raw conditions, aliases, target=, castsequence, ranks, item:<id>;
  u commands registered on this client only (and by class); /dismount and /click stay text lines;
  v the companion's own macros: a lock, A / X refused;
  w Select is the room's (capture v4 extra PADBACK at the take; the hosted Bind box keeps it);
  x Move: Y, Up / Down, A drops (ONE write), B writes nothing;
  y On (D5): the order, the soft friend only with the game's soft-friend targeting on (the CVar read, never written), the pet only for
    pet classes;
  z ranks: the rank chip and pop-out, the exact subtext written (an es client's "Rango 2");
  aa the icon: Auto and the macro's spells' pictures, ONE write; ab the search: the keyboard filters, nothing written.
Spells s16 X = REMOVE (the user, 2026-11-18: "in the macro menu I need a button to delete a line, maybe just on the X glyph"; the pick
"X = Remove"):
  af X on the editor's focused line asks (Cancel [B] / Remove [A], the builder's Remove's ask), A removes that line only (ONE write), B
    cancels, the time out cancels, a finger elsewhere cancels; the hint's tap (`x`) the same; a '|' line refused; the last line / the
    only line; a draft held follows its line (its own line gone: the draft goes); never in a fight (no hint, X and the ask's A refused);
Spells s15 THE LOOK PASS (the user, 2026-11-16: "it needs to be a bit more visually consistent and the icon needs work for the room"; the
pick "Full match": one A button plus Y More like Quests, Cancel / Confirm for delete / replace):
  ae the editor's buttons + Line / More (s21, s22), the builder's Done / More (s19, s17); More = a one-column pop-out (Presets / Delete;
    Aa Text / Remove or Revert); Y opens More wherever Y has no work of its own (the document's ym); the asks: Delete, Remove, Replace -
    A confirms, B cancels, nothing else moves; a finger elsewhere cancels the ask; no column added (38).
Needs lupa. Exit code 1 on any failure."""
import json
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from wowstub import ADDONS, ADDON_OF, F, SRC, WORLD_FILES, boot_world, docs, nsent  # noqa: E402
from lupa import lua51 as lupa  # noqa: E402

for _k, (_d, _f) in {"chat": ("WowCompanion", "Chat.lua"), "spells": ("WowCompanionSpells", "Spells.lua")}.items():
    F[_k] = os.path.join(ADDONS, _d, _f)
    SRC[_k] = open(F[_k], encoding="utf-8").read()
    ADDON_OF[_k] = _d
FILES = tuple(list(WORLD_FILES[:3]) + ["chat"] + list(WORLD_FILES[3:7]) + ["spells"] + list(WORLD_FILES[7:]))
LOGIN = ('for _, a in ipairs({ "WowCompanion", "WowCompanionQuests", "WowCompanionHub", "WowCompanionInteract", "WowCompanionSpells" }) do '
         'fire("ADDON_LOADED", a) end; fire("PLAYER_LOGIN"); fire("PLAYER_ENTERING_WORLD", true, false); runTimers(NOW + 10)')
fails = 0
passes = 0


def check(ok, name, got=''):
    global fails, passes
    print(('PASS ' if ok else 'FAIL ') + name + ((' -> ' + str(got)) if got != '' else ''))
    if ok:
        passes += 1
    else:
        fails += 1


# the game's macro API: General 1..120, the character's 121..120+MAXC, each list sorted by name as the game keeps it; EVERY write is
# logged, refused in the lockdown, and counted as a breach unless it runs inside an explicit tap of ours (ALLOW, set by tapc below)
MACROS = r'''
MAXG, MAXC = 120, 18
GENM, CHARM, MLOG, OUTSIDE = {}, {}, {}, {}
ALLOW = false
RULE_OFF = false
Constants = setmetatable({ MacroConsts = { MAX_ACCOUNT_MACROS = MAXG, MAX_CHARACTER_MACROS = MAXC },
    GamepadActionBarConstants = { NUM_SLOTS_PER_GAMEPAD_ACTION_BAR = 8, NUM_PAGEABLE_SLOTS_PER_GAMEPAD_ACTION_BAR_PAGE_UNIT_STANDARD_PAGE = 28,
                                  NUM_RESERVED_SLOTS_PER_GAMEPAD_ACTION_BAR_PAGE_UNIT = 4 } }, { __index = function() return STUB end })
-- the game's gamepad bar slots (tools/test_bags_bindbox.py's)
C_GamepadUI = setmetatable({
    GetFirstGamepadActionBarStorageSlotIndexForActiveStance = function() return 0 end,
    GetFirstGamepadActionStorageSlotIndex = function() return 181 end,
    IsValidGamepadActionStorageSlotIndex = function() return true end,
}, { __index = function() return STUB end })
Enum = setmetatable({ GameRule = { MacrosDisabled = 77 } }, { __index = function() return STUB end })
C_GameRules = { IsGameRuleActive = function(r) return r == 77 and RULE_OFF end }
MACROS_LOADED = true
function GetNumMacros() if not MACROS_LOADED then return 0, 0 end return #GENM, #CHARM end
local function at(i)
    if type(i) ~= "number" then return nil end
    if i <= MAXG then return GENM, i end
    return CHARM, i - MAXG
end
function GetMacroInfo(i)
    if not MACROS_LOADED then return nil end
    local t, k = at(i); local m = t and t[k]
    if m then return m.name, m.icon, m.body end
end
function GetMacroIndexByName(n)
    if not MACROS_LOADED or type(n) ~= "string" then return 0 end
    for i, m in ipairs(GENM) do if m.name:lower() == n:lower() then return i end end
    for i, m in ipairs(CHARM) do if m.name:lower() == n:lower() then return MAXG + i end end
    return 0
end
MSEQ = 0
function mseq() MSEQ = MSEQ + 1; return MSEQ end
function sortT(t) table.sort(t, function(a, b) local x, y = a.name:lower(), b.name:lower(); if x ~= y then return x < y end
    return (a.seq or 0) < (b.seq or 0) end) end
local function idxOf(t, m) for k, x in ipairs(t) do if x == m then return (t == CHARM and MAXG or 0) + k end end end
local function guard(what)
    MLOG[#MLOG + 1] = what
    if COMBAT then VIOL[#VIOL + 1] = what; error("ADDON_ACTION_BLOCKED " .. what) end
    if not ALLOW then OUTSIDE[#OUTSIDE + 1] = what end
end
function CreateMacro(name, icon, body, perChar)
    guard("create " .. tostring(name) .. " " .. tostring(icon) .. " " .. tostring(perChar))
    local t = perChar and CHARM or GENM
    local m = { name = name, icon = icon, body = body or "", seq = mseq() }
    t[#t + 1] = m
    sortT(t)
    return idxOf(t, m)
end
function EditMacro(i, name, icon, body)
    guard("edit " .. tostring(i) .. " " .. tostring(name) .. " " .. tostring(body))
    local t, k = at(i); local m = t[k]
    if name then m.name = name end
    if icon then m.icon = icon end
    if body then m.body = body end
    sortT(t)
    return idxOf(t, m)
end
function DeleteMacro(i)
    guard("delete " .. tostring(i))
    local t, k = at(i)
    table.remove(t, k)
end
function mnames(t) local o = {}; for _, m in ipairs(t) do o[#o + 1] = m.name end; return table.concat(o, "|") end
function mbody(n) local i = GetMacroIndexByName(n); local _, _, b = GetMacroInfo(i); return b end
-- the bar (the game's action slots) and the cursor: the Bind box's Normal road
ACTIONINFO, BARTEXT, CURSOR = {}, {}, nil
function GetActionInfo(s) local a = ACTIONINFO[s]; if a then return a[1], a[2] end end
function GetActionText(s) return BARTEXT[s] end
function GetCursorInfo() if CURSOR then return CURSOR[1], CURSOR[2] end end
function ClearCursor() CURSOR = nil end
function PickupMacro(i) MLOG[#MLOG + 1] = "pickup " .. tostring(i); if not ALLOW then OUTSIDE[#OUTSIDE + 1] = "pickup" end
    local n = GetMacroInfo(i); if n then CURSOR = { "macro", i, n } end end
function PlaceAction(s)
    MLOG[#MLOG + 1] = "place " .. tostring(s); if not ALLOW then OUTSIDE[#OUTSIDE + 1] = "place" end
    if CURSOR and CURSOR[1] == "macro" then ACTIONINFO[s] = { "macro", CURSOR[2] }; BARTEXT[s] = CURSOR[3] end
    CURSOR = nil
end
function PickupAction(s)
    MLOG[#MLOG + 1] = "pickupaction " .. tostring(s); if not ALLOW then OUTSIDE[#OUTSIDE + 1] = "pickupaction" end
    ACTIONINFO[s] = nil; BARTEXT[s] = nil
end
-- the book's spells (Shaman), the bags (a potion with a use, a sword to equip, a rock with neither)
BAGITEMS = { [1] = { id = 118, n = "Minor Healing Potion", use = true }, [2] = { id = 25, n = "Worn Shortsword", eq = true },
             [3] = { id = 2589, n = "Linen Cloth" } }
C_Container = { GetContainerNumSlots = function(b) if b == 0 then return 16 end return 0 end,
                GetContainerItemID = function(b, s) if b == 0 and BAGITEMS[s] then return BAGITEMS[s].id end end,
                GetContainerItemInfo = function() return nil end, GetContainerItemLink = function() return nil end,
                PickupContainerItem = function() end }
local function item(id) for _, x in pairs(BAGITEMS) do if x.id == id then return x end end end
C_Item = { GetItemNameByID = function(id) local x = item(id) or WORNITEM[id]; return x and x.n end,
           GetItemIconByID = function(id) if type(id) == "number" then return 200000 + id end; for _, x in pairs(BAGITEMS) do if x.n == id then return 200000 + x.id end end end,
           GetItemSpell = function(id) local x = item(id) or WORNITEM[id]; if x and x.use then return "Healing Potion", 439 end end,
           IsEquippableItem = function(id) local x = item(id); return x and x.eq == true end,
           GetItemCount = function(k) for _, x in pairs(BAGITEMS) do if x.id == k or x.n == k then return 1 end end; return 0 end }
-- s14: the worn slots (13 a trinket with a use when TRINKET), the game's slash words and its secure list (IsSecureCmd)
WORNITEM = { [9001] = { n = "Arena Grand Master", use = true }, [9002] = { n = "Smite's Hammer" } }
WORN = {}
function GetInventoryItemID(u, sl) return WORN[sl] end
function GetInventoryItemTexture(u, sl) if WORN[sl] then return 210000 + WORN[sl] end end
SLASH_CAST1, SLASH_CAST2, SLASH_USE1, SLASH_CASTSEQUENCE1 = "/cast", "/spell", "/use", "/castsequence"
SLASH_TARGET1, SLASH_TARGET2, SLASH_FOCUS1, SLASH_ASSIST1, SLASH_STARTATTACK1 = "/target", "/tar", "/focus", "/assist", "/startattack"
SLASH_STOPATTACK1, SLASH_STOPCASTING1, SLASH_CANCELAURA1, SLASH_CANCELFORM1 = "/stopattack", "/stopcasting", "/cancelaura", "/cancelform"
SLASH_EQUIP1, SLASH_EQUIP_TO_SLOT1, SLASH_CLEARTARGET1, SLASH_CLEARFOCUS1 = "/equip", "/equipslot", "/cleartarget", "/clearfocus"
SLASH_TARGET_NEAREST_ENEMY1, SLASH_TARGET_LAST_TARGET1, SLASH_STOPMACRO1 = "/targetenemy", "/targetlasttarget", "/stopmacro"
SLASH_DISMOUNT1, SLASH_CLICK1 = "/dismount", "/click"
SECURE = {}
for _, w in ipairs({ "/cast", "/spell", "/use", "/castsequence", "/target", "/tar", "/focus", "/assist", "/startattack", "/stopattack",
                     "/stopcasting", "/cancelaura", "/cancelform", "/equip", "/equipslot", "/cleartarget", "/clearfocus", "/targetenemy",
                     "/targetlasttarget", "/stopmacro", "/click", "/petattack", "/petfollow", "/petstay", "/petpassive", "/petdefensive",
                     "/petaggressive", "/petdismiss", "/petmoveto", "/petassist", "/petdefensiveassist" }) do SECURE[w] = true end
function IsSecureCmd(w) return SECURE[string.lower(w)] == true end
SlashCmdList = { DISMOUNT = function() end }
SPELLNAME[8042], SPELLNAME[6603], SPELLNAME[1766] = "Earth Shock", "Attack", "Kick"
HARMFUL = { [403] = true, [8042] = true, [1766] = true, [3110] = true }
C_Spell.IsSpellHarmful = function(id) return HARMFUL[id] == true end
SUBTEXT = {}
C_Spell.GetSpellSubtext = function(id) return SUBTEXT[id] end
SOFTFRIEND = "0"
CVARREADS, CVARWRITES = 0, 0
function GetCVar(k) CVARREADS = CVARREADS + 1; if k == "SoftTargetFriend" then return SOFTFRIEND end end
NFORMS = 0
function GetNumShapeshiftForms() return NFORMS end
'''
# after the load: helpers
AFTER = r'''
MRx = WowCompanion.macroRoom._mr
-- the book (the game's own spellbook is stubbed empty): the character's spells are BOOK's (tools/test_party.py's WORLD_HELPERS)
WowCompanion.spellsBindableList = function()
    local out = {}
    for i, id in ipairs(BOOK) do out[#out + 1] = { id = id, slot = i } end
    for i, id in ipairs(GENBOOK or {}) do out[#out + 1] = { id = id, slot = 100 + i, gen = true } end
    return out
end
WowCompanion.spellsBookSlotOf = function(id) for i, x in ipairs(BOOK) do if x == id then return i end end end
function mdoc() local d = MRx.doc(); return WowCompanion.encode(d) end
function col(k) return MRx.COL[k] end
function scol(k) return MRx.S0 + k end
'''


def world(pre='', macros=''):
    L, errs = boot_world(files=FILES, pre=pre + MACROS + macros, login=LOGIN)
    L.execute(AFTER)
    L.execute('LOADERRS = {}')
    for e in errs:
        L.eval('function(s) LOADERRS[#LOADERRS + 1] = s end')(e)
    return L, errs


def last(L, typ, start=0):
    d = docs(L, start, typ)
    return d[-1] if d else {}


def mdoc(L):
    L.execute('WowCompanion.macroRoom.emit()')
    return (last(L, 'macros').get('m') or {})


def tap(L, key, allow=False):
    """one finger tap on the room's row: key = a room key ("h", "a", ...) or an int k for s<k>"""
    c = L.eval('col')(key) if isinstance(key, str) else L.eval('scol')(key)
    L.execute('ALLOW = %s' % ('true' if allow else 'false'))
    ok = L.eval('hwTap')("mac", c)
    L.execute('ALLOW = false; advance(0.3)')
    return ok


def press(L, k):
    """a press the capture hands the holder (the room's own spec.press)"""
    L.eval('function(k) MRx.press(k) end')(k)
    L.execute('advance(0.1)')


def ensure_row(L):
    # Book's padtab pick 39 then 34 (the app's two taps)
    L.eval('hwTap')("padtab", 39)
    L.eval('hwTap')("padtab", 34)
    L.execute('advance(0.3)')


def kbd_done(L, text, allow=True):
    """the keyboard's Done: the chat's borrowed field hands [text] to the room (Chat.lua chat22's field.done)"""
    L.execute('ALLOW = %s' % ('true' if allow else 'false'))
    r = L.eval('function(t) local ok, done, why = pcall(MRx.kbdDone, t); return tostring(done) .. ":" .. tostring(why) end')(text)
    L.execute('ALLOW = false; advance(0.3)')
    return r


def holder(L):
    return L.eval('(function() local ok, s = pcall(WowCompanion.capture.state); return ok and s and s.holder or nil end)()')


def mlog(L):
    return [L.eval('MLOG[%d]' % i) for i in range(1, int(L.eval('#MLOG')) + 1)]


def clean(L):
    out = {}
    for k in ("VIOL", "OUTSIDE", "SNIPERR", "LOADERRS"):
        n = int(L.eval('#%s' % k))
        if n:
            out[k] = [L.eval('%s[%d]' % (k, i)) for i in range(1, n + 1)][:3]
    errs = list(L.eval('ERRS').values())
    if errs:
        out['ERRS'] = errs[:3]
    spy = [L.eval('SPY[%d]' % i) for i in range(1, int(L.eval('#SPY')) + 1)]
    if spy:
        out['SPY'] = spy[:5]
    return out


def seed(L, gen=(), char=()):
    for n, b in gen:
        L.eval('function(n, b) GENM[#GENM + 1] = { name = n, icon = 134400, body = b, seq = mseq() } end')(n, b)
    for n, b in char:
        L.eval('function(n, b) CHARM[#CHARM + 1] = { name = n, icon = 134400, body = b, seq = mseq() } end')(n, b)
    L.execute('sortT(GENM); sortT(CHARM)')


def opened(gen=(), char=(), pre='', macros=''):
    L, errs = world(pre=pre, macros=macros)
    seed(L, gen, char)
    L.execute('fire("UPDATE_MACROS"); advance(1)')
    ensure_row(L)
    tap(L, "h")
    return L


# ================================================================ a. versions, notes, compile, what the room's code never calls
comp = lupa.LuaRuntime().eval('function(s, n) local f, e = loadstring(s, n); return e end')
for k in ("spells", "book", "padmap"):
    e = comp(SRC[k], k)
    check(e is None, "a. Lua 5.1 compiles %s" % os.path.basename(F[k]), e or '')
check('local SV = "s18"' in SRC["spells"] and 'local BV = "b76"' in SRC["book"] and int(SRC["padmap"].split('local PV = "m', 1)[1].split('"', 1)[0]) >= 172,
      "a. the versions: Spells s14, Book b65, PadMap m166")
check('s14 THE MACRO BUILDER v2 (the user, 2026-11-16: the Macros room\'s recipes "make no sense"' in SRC["spells"],
      "a. Spells s14's header note quotes the user")
QUOTE = '"Lets scrap the smart ranged attack and start building a macro room'
check(all(QUOTE in SRC[k] for k in ("spells", "book", "padmap")), "a. each changed file's header note quotes the user")
BLOCK = SRC["spells"][SRC["spells"].index("-- ==================================================================== s10 THE MACROS ROOM"):]
code = "\n".join(re.sub(r'--.*$', '', ln) for ln in BLOCK.splitlines())
bad = [w for w in ("ShowMacroFrame", "MacroFrame_Show", "LoadAddOn", "HideUIPanel", "ShowUIPanel", "RunMacroText", "RunMacro",
                   "SetMacroExecuteLineCallback", "SetBinding", "SaveBindings", "SetCVar", "SetOverrideBinding", "PlaceAction",
                   "PickupMacro", "ToggleFrame", "MacroFrameText", "SetAttribute(\"macro\"", "C_CVar.SetCVar", "SetCVarBitfield",
                   "SetOverrideBindingClick", "SecureCmdOptionParse", "ChatEdit_SendText", "SendChatMessage", "CastSpellByName", "UseAction")
       if re.search(r'\b' + re.escape(w) + r'\b' if w[0].isalpha() and w[-1].isalpha() else re.escape(w), code)]
check(bad == [], "a. the room's block never opens / closes / shows / hides a window, runs a macro, binds a key, writes a CVar or a bar", bad)
writes = re.findall(r'\b(CreateMacro|EditMacro|DeleteMacro)\(', code)
check(sorted(set(writes)) == ["CreateMacro", "DeleteMacro", "EditMacro"] and len(writes) == 5,
      "a. its writes: CreateMacro once, EditMacro three times (name / icon / THE one body write), DeleteMacro once", writes)
guarded = all(re.search(r'canWrite\(\)', fn) for fn in re.findall(r'function MR\.(?:writeBody|setIcon|delete)\(.*?\nend', BLOCK, re.S))
check(guarded and len(re.findall(r'function MR\.(?:writeBody|setIcon|delete)\(', BLOCK)) == 3
      and 'local why = MR.canWrite()\n    if why then MR.ack(k.name, false, why)' in BLOCK,
      "a. every write is behind the one guard (the keyboard's Done, the one body write, the icon, Delete)")
bodyCalls = re.findall(r'EditMacro\(i, nil, icon, ([a-z]+)\)', code)   # s17: the body write carries Blank's icon (nil otherwise)
check(bodyCalls == ["nb"] and code.count("MR.writeBody(") >= 6,
      "a. s14: every line change (Done, Remove, Move, a preset, s17 Blank, a typed line) goes through MR.writeBody's ONE EditMacro",
      (bodyCalls, code.count("MR.writeBody(")))
check(re.search(r'SetCVar|C_CVar\.Set', code) is None and 'GetCVar("SoftTargetFriend")' in code,
      "a. s14: the soft-friend CVar is READ only (never written)")

# ================================================================ b. the lazy row
L, errs = world()
check(errs == [], "b. the world loads with the room", errs)
check(L.eval('WowCompanion.pad.rows.mac') is None, "b. no 'mac' row at login (the pad is full at the fullest layout: lazy)")
L.execute('fire("UPDATE_MACROS"); advance(1)')
d = mdoc(L)
check('keys' not in d and d.get('ready') is True, "b. before the row: the document has no keys (the app asks for the row)", sorted(d))
COMBAT_ON = 'fire("PLAYER_REGEN_DISABLED"); COMBAT = true; advance(0.2)'
COMBAT_OFF = 'COMBAT = false; fire("PLAYER_REGEN_ENABLED"); advance(1)'
L.execute(COMBAT_ON)
ensure_row(L)
check(L.eval('WowCompanion.pad.rows.mac') is None and clean(L) == {}, "b. in a fight the row waits (a secure row is built out of combat)", clean(L))
L.execute(COMBAT_OFF)
ensure_row(L)
r = L.eval('WowCompanion.pad.rows.mac')
check(r is not None and r.n == 38, "b. padtab 39 then 34 makes the row: 38 cells (s14: + y, ok; the pad's limit is 40)", r and r.n)
d = mdoc(L)
k = d.get('keys') or {}
check(k.get('h') == 0 and k.get('f') == 1 and k.get('fb') == 2 and k.get('wx') == 11 and k.get('y') == 12 and k.get('ok') == 13
      and k.get('s0') == 14 and k.get('n') == 24
      and 'mac' in ((d.get('pad') or {}).get('rows') or {}), "b. the document carries the keys at fixed columns and the pad", k)
check(clean(L) == {}, "b. clean", clean(L))
# a full pad: the row cannot be made, the document says so
L2, _ = world()
# (TapPad pad26: rows sit on their ranked places, so a free row can be anywhere: every free one is filled)
L2.execute('for i = 0, 55 do if not WowCompanion.pad.byRow[i] then WowCompanion.pad.op("filler" .. i, nil, function() end) end end')
L2.execute('fire("UPDATE_MACROS"); advance(1)')
ensure_row(L2)
check(L2.eval('WowCompanion.pad.rows.mac') is None and mdoc(L2).get('norow') is True, "b. no free row: no row, the document says norow")

# ================================================================ c. the grid
GEN = [("Alpha", "/wave"), ("Bravo", "/dance"), ("Charlie", "#showtooltip\n/cast Lightning Bolt")]
CHAR = [("Heal", "/cast Healing Wave")]
L = opened(GEN, CHAR)
check(holder(L) == "macros", "c. h: the room holds the controller (guest macros)", holder(L))
d = mdoc(L)
check(d.get('tab') == 'g' and d.get('cnt') == {'g': {'n': 3, 'max': 120}, 'c': {'n': 1, 'max': 18}},
      "c. the General tab first; counts and the game's own caps (Constants.MacroConsts)", (d.get('tab'), d.get('cnt')))
cells = d.get('cells') or []
check([c.get('n') for c in cells[:3]] == ["Alpha", "Bravo", "Charlie"] and cells[3] == {'plus': True} and len(cells) == 4,
      "c. one page: the macros in the game's order, then '+'", cells)
check(d.get('cur') == 0 and (d.get('mac') or {}).get('n') == 'Alpha' and (d.get('mac') or {}).get('lines') == ['/wave'],
      "c. the glow on the first; its body travels (only the selected one's)", (d.get('cur'), d.get('mac')))
press(L, "PADDRIGHT"); press(L, "PADDRIGHT")
d = mdoc(L)
check(d.get('cur') == 2 and d['mac']['n'] == 'Charlie' and d['mac']['lines'] == ['#showtooltip', '/cast Lightning Bolt'],
      "c. the D-pad moves the selection", (d.get('cur'), d['mac']))
press(L, "PADDUP")
check(mdoc(L).get('cur') == -1, "c. Up from the top row: the tabs (General lit)", mdoc(L).get('cur'))
press(L, "PADDRIGHT"); press(L, "PAD1")
d = mdoc(L)
check(d.get('tab') == 'c' and [c.get('n') for c in d['cells']] == ['Heal', None] and d['cells'][1] == {'plus': True} and d.get('cur') == 0,
      "c. A on the character's tab switches it (the Professions ruling): its macros, '+'", (d.get('tab'), d.get('cells')))
tap(L, "tg")
check(mdoc(L).get('tab') == 'g', "c. a finger's tg / tc switch tabs")
tap(L, 1)
check(mdoc(L).get('cur') == 1 and mdoc(L)['mac']['n'] == 'Bravo', "c. a finger's s1 selects the second cell", mdoc(L).get('cur'))
# pages: 30 General macros = 2 pages; at the cap no '+'
L3 = opened([("M%02d" % i, "/say %d" % i) for i in range(30)])
d = mdoc(L3)
check(d.get('pages') == 2 and len(d['cells']) == 24 and not any(c.get('plus') for c in d['cells']), "c. 30 macros: 2 pages of 24", d.get('pages'))
for _ in range(4):
    press(L3, "PADDDOWN")
d = mdoc(L3)
check(d.get('page') == 2 and d['cells'][0].get('n') == 'M24' and d['cells'][-1] == {'plus': True},
      "c. Down past the last row turns the page; '+' after the last macro", (d.get('page'), d['cells'][0], d['cells'][-1]))
tap(L3, "pp")
check(mdoc(L3).get('page') == 1, "c. pp / pn: a finger's page arrows")
L4 = opened([], [("C%02d" % i, "/say %d" % i) for i in range(18)])
tap(L4, "tc")
check(not any(c.get('plus') for c in mdoc(L4)['cells']), "c. the character's tab at its cap (18): no '+'")
L5, _ = world(macros='MACROS_LOADED = false')
ensure_row(L5); tap(L5, "h")
d = mdoc(L5)
check(not d.get('ready') and 'cells' not in d, "c. before the game loads the macros: not ready, no cells ('...')", d.get('ready'))

# ================================================================ d. New
L = opened(GEN, CHAR)
for _ in range(3):
    press(L, "PADDRIGHT")
d = mdoc(L)
check(d.get('cur') == 3 and d['cells'][3] == {'plus': True}, "d. the glow reaches '+'", d.get('cur'))
press(L, "PAD1")
d = mdoc(L)
check(d.get('mode') == 'kbd' and (d.get('kbd') or {}).get('name') == 'macnew' and d['kbd']['max'] == 16
      and L.eval('WowCompanion.chatField.name()') == 'macnew', "d. A on '+': the name keyboard (the chat's borrowed field, 16 letters)", d.get('kbd'))
check(mlog(L) == [], "d. opening the keyboard writes nothing")
tap(L, "kx")
d = mdoc(L)
check(d.get('mode') == 'grid' and L.eval('WowCompanion.chatField.name()') is None and mlog(L) == [],
      "d. the keyboard closed (kx): back to the grid, nothing made", (d.get('mode'), mlog(L)))
press(L, "PAD1")
r = kbd_done(L, '  "Big Heal"  ')
d = mdoc(L)
check(r.startswith('true') and mlog(L) == ['create Big Heal 134400 false'], "d. Done: CreateMacro, trimmed, quotes dropped, the question mark, General", (r, mlog(L)))
check(d.get('mode') == 'edit' and d['mac']['n'] == 'Big Heal' and d.get('ef') == 'name', "d. the new macro's editor opens on its name", (d.get('mode'), d.get('mac')))
check(L.eval('mnames(GENM)') == 'Alpha|Big Heal|Bravo|Charlie', "d. sorted as the game keeps them", L.eval('mnames(GENM)'))
press(L, "PAD2")
check(mdoc(L).get('cur') == 1 and mdoc(L)['mac']['n'] == 'Big Heal', "d. B: the grid, the glow on the new macro (re-found by name)", mdoc(L).get('cur'))
# the name rules
for raw, why in (("alpha", "taken"), ("HEAL", "taken"), ("ThisNameIsWayTooLong", "long"), ('   ', "empty")):
    L.execute('MLOG = {}')
    L.execute('MRx.st.cur = 4; MRx.focusGrid()')
    press(L, "PAD1")
    r = kbd_done(L, raw)
    d = mdoc(L)
    check(r == 'false:' + why and mlog(L) == [] and d.get('mode') == 'kbd' and d.get('note') == why,
          "d. the name %r refused (%s): no call, the keyboard stays" % (raw, why), (r, mlog(L), d.get('note')))
    tap(L, "kx")
tap(L, "tc")
L.execute('MLOG = {}')
press(L, "PADDRIGHT"); press(L, "PAD1")
kbd_done(L, "Mine")
check(mlog(L) == ['create Mine 134400 true'] and L.eval('mnames(CHARM)') == 'Heal|Mine', "d. on the character's tab: perCharacter", mlog(L))
check(clean(L) == {}, "d. clean (every write inside a Done)", clean(L))

# ================================================================ e. the editor (s14: the name, the icon, the strips, + Line / Presets / Delete)
def bdoc(L):
    return mdoc(L).get('b') or {}


def popd(L):
    d = mdoc(L)
    return (d.get('b') or {}).get('pop') or d.get('pop') or {}


def pick_row(L, pred, allow=False):
    """a finger on the open pop-out's first row matching [pred] (its s-cell, then A)"""
    p = popd(L)
    for i, r in enumerate(p.get('rows') or []):
        if pred(r):
            tap(L, (p.get('base') or 0) + i)
            tap(L, "a", allow=allow)
            return True
    return False


def chip(L, key, allow=False):
    """a finger on builder chip [key] (its s-cell, then A)"""
    sk = (bdoc(L).get('ch') or {}).get(key)
    assert sk is not None, ("no chip", key, bdoc(L).get('ch'))
    tap(L, sk)
    tap(L, "a", allow=allow)


def more(L, o, allow=False):
    """s15: a finger on the room's More (the builder's chip, the editor's s22), then on its row [o]"""
    if mdoc(L).get('mode') == 'build':
        chip(L, 'more')
    else:
        tap(L, 22); tap(L, "a")
    assert popd(L).get('k') == 'more', ("no More", popd(L))
    assert pick_row(L, lambda r: r.get('o') == o, allow=allow), ("no row", o, popd(L))


L = opened([("Alpha", "/wave\n/say hi\n/run print(|cff)")], CHAR)
press(L, "PAD1")
d = mdoc(L)
check(d.get('mode') == 'edit' and d.get('ef') == 'name' and d['mac']['lines'] == ['/wave', '/say hi', '/run print(|cff)']
      and d['mac'].get('lock') == [3], "e. A: the editor, on the name; a line with '|' is locked", (d.get('ef'), d['mac']))
check([x.get('k') for x in d.get('ls') or []] == ['text', 'text', 'lock'],
      "e. the strips (D3): /wave and /say are text lines (kept as written), the '|' line is locked", d.get('ls'))
press(L, "PAD1")
check(mdoc(L).get('kbd', {}).get('name') == 'macname' and mdoc(L)['kbd']['text'] == 'Alpha', "e. A on the name: the rename keyboard, its text")
r = kbd_done(L, "Zulu")
d = mdoc(L)
check(r.startswith('true') and d.get('mode') == 'edit' and d['mac']['n'] == 'Zulu' and L.eval('mnames(GENM)') == 'Zulu',
      "e. rename: EditMacro(i, name), re-found by name", (r, d['mac']['n']))
press(L, "PADDRIGHT")
check(mdoc(L).get('ef') == 'icon' and mdoc(L).get('efc') == 1 and mdoc(L).get('ym') is True,
      "e. Right of the name: the Icon chip (s1); Y would open More there (ym, s15)", mdoc(L).get('ef'))
press(L, "PAD4")
check(popd(L).get('k') == 'more' and mdoc(L).get('mode') == 'edit' and 'ym' not in mdoc(L),
      "ae. Y on the Icon chip: More (Y has no work of its own there); no ym while it is open", popd(L))
press(L, "PAD2")
check(not mdoc(L).get('pop') and mdoc(L).get('ef') == 'icon', "ae. B: More closes, the glow where it was")
press(L, "PADDDOWN")
check(mdoc(L).get('ef') == 'l1' and mdoc(L).get('efc') == 2, "e. Down: the first line (s2)", (mdoc(L).get('ef'), mdoc(L).get('efc')))
check('ym' not in mdoc(L), "ae. on a line Y is its Move: no ym", mdoc(L).get('ym'))
press(L, "PAD1")
check(mdoc(L)['kbd'] == {'name': 'macline', 'text': '/wave', 'max': 255 - len("/wave\n/say hi\n/run print(|cff)") + 5, 'line': 1},
      "e. A on a TEXT line: its keyboard (the text-line fallback), the room left in 255", mdoc(L).get('kbd'))
L.execute('MLOG = {}')
kbd_done(L, "/wave hello")
check(L.eval('mbody("Zulu")') == "/wave hello\n/say hi\n/run print(|cff)" and len(mlog(L)) == 1 and mlog(L)[0].startswith('edit 1 nil'),
      "e. Done: EditMacro(i, nil, nil, body) (the body-only form)", (L.eval('mbody("Zulu")'), mlog(L)))
check(mdoc(L).get('xr') is True and mdoc(L).get('ef') == 'l1', "e. (s16) the glow on line 1: its X Remove hint (xr)", mdoc(L).get('xr'))
more(L, 'ins')
d = mdoc(L)
check(d.get('mode') == 'build' and (d.get('b') or {}).get('ln') is None and d['b'].get('at') == 2 and (d['b'].get('pop') or {}).get('k') == 'cmd',
      "e. (s16) More > Insert below (the glow was on line 1): a NEW line under it, in the builder, its Action list open", d.get('b'))
check(pick_row(L, lambda r: r.get('k') == 'chat') and mdoc(L).get('kbd', {}).get('name') == 'macline',
      "e. Action: Chat line -> the keyboard (a typed line)", mdoc(L).get('kbd'))
kbd_done(L, "/sit")
check(L.eval('mbody("Zulu")') == "/wave hello\n/sit\n/say hi\n/run print(|cff)" and mdoc(L).get('mode') == 'edit',
      "e. typed and written under line 1; back in the editor", L.eval('mbody("Zulu")'))
press(L, "PADDUP")
press(L, "PAD1")
kbd_done(L, "")
check(L.eval('mbody("Zulu")') == "/sit\n/say hi\n/run print(|cff)", "e. a line emptied goes", L.eval('mbody("Zulu")'))
press(L, "PADDDOWN"); press(L, "PADDDOWN")
L.execute('MLOG = {}')
check(mdoc(L).get('ef') == 'l3', "e. the '|' line under the glow", mdoc(L).get('ef'))
press(L, "PAD1")
check(mdoc(L).get('note') == 'locked' and mlog(L) == [] and mdoc(L).get('mode') == 'edit', "e. A on the '|' line: locked, no keyboard")
press(L, "PADDDOWN")
d = mdoc(L)
check(d.get('ef') == 'add' and d.get('efc') == 21, "e. Down past the lines: the button row (+ Line = s21)", (d.get('ef'), d.get('efc')))
press(L, "PADDRIGHT")
check(mdoc(L).get('ef') == 'more' and mdoc(L).get('efc') == 22 and mdoc(L).get('ym') is True, "ae. Right: More (s22)", mdoc(L).get('ef'))
press(L, "PADDRIGHT")
check(mdoc(L).get('ef') == 'more', "ae. ... the row's end (no Presets / Delete buttons any more)")
press(L, "PAD1")
p = popd(L)
check(p.get('k') == 'more' and p.get('cols') == 1 and [(r.get('o'), r.get('l')) for r in p.get('rows')] == [('ins', 3), ('pre', None), ('del', None)]
      and p.get('base') == 0, "ae. A on More: one column, Insert below (s16: under line 3, the glow's last line), Presets and Delete (cells s0..)", p)
press(L, "PADDDOWN"); press(L, "PADDDOWN")
check(popd(L).get('cur') == 'r3', "ae. Down in More: Delete", popd(L).get('cur'))
press(L, "PAD2")
check(not mdoc(L).get('pop') and mdoc(L).get('ef') == 'more' and mlog(L) == [], "ae. B: More closes; nothing done")
press(L, "PADDLEFT")
press(L, "PAD1")
check(mdoc(L).get('mode') == 'build' and bdoc(L).get('at') == 4 and (bdoc(L).get('pop') or {}).get('k') == 'cmd',
      "e. + Line: a new last line in the builder, its Action list open", bdoc(L).get('at'))
pick_row(L, lambda r: r.get('k') == 'chat')
kbd_done(L, "/cheer")
check(L.eval('mbody("Zulu")').endswith("\n/cheer"), "e. + Line, Chat line: added at the end", L.eval('mbody("Zulu")'))
# 255 with the new lines
L.eval('function() local i = GetMacroIndexByName("Zulu"); GENM[i].body = string.rep("x", 250) end')()
L.execute('MLOG = {}')
press(L, "PADDDOWN")
check(mdoc(L).get('ef') == 'add', "e. (the glow on + Line)", mdoc(L).get('ef'))
press(L, "PAD1")
pick_row(L, lambda r: r.get('k') == 'chat')
check(mdoc(L)['kbd']['max'] == 4, "e. + Line on a 250-letter body: 4 letters left (the new line's break counts)", mdoc(L).get('kbd'))
r = kbd_done(L, "/abcde")
check(r == 'false:long' and mlog(L) == [], "e. more than 255: refused, no call", (r, mlog(L)))
tap(L, "kx")
check(clean(L) == {}, "e. clean", clean(L))

# ================================================================ f. the refusals
L = opened(GEN, CHAR)
press(L, "PAD1"); press(L, "PADDDOWN"); press(L, "PAD1")
L.execute('MLOG = {}; ' + COMBAT_ON)
r = kbd_done(L, "/wave combat", allow=False)
d = mdoc(L)
check(r == 'false:combat' and mlog(L) == [] and d.get('mode') == 'kbd' and d.get('note') == 'combat'
      and L.eval('WowCompanion.chatField.name()') == 'macline', "f. Done in a fight: refused, no call, the keyboard and its text stay", (r, mlog(L)))
L.execute(COMBAT_OFF)
r = kbd_done(L, "/wave after")
check(r.startswith('true') and L.eval('mbody("Alpha")') == '/wave after', "f. ... and the same Done after the fight writes", r)
L.execute('mkwin("MacroFrame", true); MLOG = {}')
press(L, "PAD1")
r = kbd_done(L, "/wave win")
check(r == 'false:window' and mlog(L) == [] and mdoc(L).get('win') is True, "f. the game's window open: refused ('window')", (r, mlog(L)))
L.execute('MacroFrame:Hide()')
tap(L, "kx")
L.execute('RULE_OFF = true')
d = mdoc(L)
check(d.get('off') is True and 'cells' not in d, "f. the game rule turns macros off: the document says off (the room's lock)", d.get('off'))
L.execute('RULE_OFF = false')
L.execute('MACROS_LOADED = false; MRx.st.loaded = false')
check(L.eval('MRx.canWrite()') == 'loading', "f. before the macros load: 'loading'")
L.execute('MACROS_LOADED = true')
check(clean(L) == {}, "f. clean (no write outside a Done, none in the lockdown)", clean(L))

# ================================================================ g. (s14) THE BUILDER (D1): the action and its spell, then the cases; Done
def sel(L, allow=True):
    """the Select button (D4: Done), the capture's press (inside an allowed write)"""
    L.execute('ALLOW = %s' % ('true' if allow else 'false'))
    press(L, "PADBACK")
    L.execute('ALLOW = false')


L = opened([("Alpha", "")], CHAR)
press(L, "PAD1")
d = mdoc(L)
check(d.get('mode') == 'edit' and d.get('tiles') and d.get('ls') == [], "g. an empty macro's editor: the preset tiles, no strips", d.get('tiles'))
tap(L, 21); tap(L, "a")
b0 = bdoc(L)
check(mdoc(L).get('mode') == 'build' and b0.get('ln') is None and b0.get('at') == 1 and (b0.get('pop') or {}).get('k') == 'cmd'
      and b0.get('h') == 'pick', "g. + Line: the builder on a new line, its Action list open; no spell yet = 'pick'", b0)
cmds = [r.get('k') for r in (b0.get('pop') or {}).get('rows') or []]
check(cmds[:3] == ['cast', 'use', 'seq'] and 'chat' == cmds[-1] and not any(k.startswith('pet') for k in cmds) and 'cancelform' not in cmds,
      "g. the Action list: Cast, Use, Sequence first, Chat line last; a Shaman (no pet, no forms) has no pet order, no Leave form", cmds)
pick_row(L, lambda r: r.get('k') == 'cast')
pp = popd(L)
check(pp.get('k') == 'arg' and pp.get('tab') == 'sp' and pp.get('base') == 6 and [r.get('n') for r in pp.get('rows')] ==
      ['Lightning Bolt', 'Healing Wave', 'Lightning Shield'], "g. Cast picked: the Spell / Item pop-out opens next (D1), the book's spells", pp)
check(pp.get('tabs') == ['sp', 'gen', 'it', 'sl'], "g. its tabs for a Cast: Spells, General, Items, Slots (no Pet tab for a Shaman)", pp.get('tabs'))
pick_row(L, lambda r: r.get('n') == 'Healing Wave')
b1 = bdoc(L)
check(b1.get('t') == '/cast Healing Wave' and b1.get('dr') is True and not b1.get('h') and not b1.get('pop') and (b1.get('a') or {}).get('n') == 'Healing Wave',
      "g. Healing Wave: the line reads '/cast Healing Wave', a draft, nothing wrong", b1)
check(b1.get('rows')[0] == ['cmd', 'arg', 'keep'] and b1.get('rows')[-1] == ['done', 'more']
      and b1.get('ch', {}).get('done') == 19 and b1.get('ch', {}).get('more') == 17 and b1.get('ch', {}).get('c1u') == 4
      and 'text' not in b1.get('ch', {}) and 'rem' not in b1.get('ch', {}),
      "g. its chips as rows, their s-cells (Done = s19, s15: More = s17; Aa Text / Remove are More's)", b1.get('rows'))
check(b1.get('cur') == 'arg' and 'ym' not in b1, "ae. on the spell's chip Y clears it: no ym", b1.get('ym'))
chip(L, 'more')
p = popd(L)
check(p.get('k') == 'more' and [(r.get('o'), r.get('rv'), r.get('off')) for r in p.get('rows')] == [('text', None, None), ('rem', None, None)],
      "ae. the builder's More: Aa Text, Remove (a new line's draft: Remove, never asked)", p.get('rows'))
press(L, "PAD2")
check(bdoc(L).get('cur') == 'more' and bdoc(L).get('ym') is True, "ae. B: More closes; on More, Y opens it (ym)", bdoc(L).get('cur'))
L.execute('MLOG = {}')
sel(L)
check(mlog(L) == ['edit 1 nil /cast Healing Wave'] and mdoc(L).get('mode') == 'edit' and mdoc(L).get('ef') == 'l1',
      "g. Select = Done (D4): ONE write; the editor, the glow on the new line", (mlog(L), mdoc(L).get('ef')))
d = mdoc(L)
check((d.get('ls') or [{}])[0].get('k') == 'cmd' and d['ls'][0].get('c') == 'cast' and d['ls'][0].get('n') == 'Healing Wave'
      and d['ls'][0].get('i') == 130331, "g. its strip (D3): the Cast, Healing Wave's picture and name", d.get('ls'))
# an unchanged draft writes nothing
press(L, "PAD1")
L.execute('MLOG = {}')
check(mdoc(L).get('mode') == 'build' and bdoc(L).get('ln') == 1 and not bdoc(L).get('dr'), "g. A on the line: the builder on it, not a draft")
sel(L)
check(mlog(L) == [] and mdoc(L).get('mode') == 'edit', "g. Done with nothing changed: NOTHING written", mlog(L))
# + Case (X): the helpful spell's options
press(L, "PAD1"); press(L, "PAD3")
opts = [r.get('o') for r in popd(L).get('rows') or []]
check(opts == ['fo1', 'me', 'tg', 'sp', 'bl'], "g. X = + Case: a helpful spell's: Focus first, Else on me, Else on target, Else another spell, Blank "
      "(no Soft friend first: the game's soft-friend targeting is off)", opts)
pick_row(L, lambda r: r.get('o') == 'me')
b2 = bdoc(L)
check(b2.get('t') == '/cast [help,nodead][@player] Healing Wave' and [c.get('u') for c in b2.get('cs')] == [None, 'player']
      and b2['cs'][0].get('f') == ['help', '!dead'],
      "g. Else on me: the plain step first gets the spell's own test (a friend, alive), then '@player': ONE clause (merged)", b2.get('t'))
# B keeps the draft
L.execute('MLOG = {}')
press(L, "PAD2")
d = mdoc(L)
check(d.get('mode') == 'edit' and (d.get('dr') or {}).get('n') == 1 and mlog(L) == [] and L.eval('mbody("Alpha")') == '/cast Healing Wave',
      "g. B: the editor; the draft KEPT (marked on its line), nothing written", (d.get('dr'), mlog(L)))
press(L, "PAD1")
check(bdoc(L).get('t') == '/cast [help,nodead][@player] Healing Wave' and bdoc(L).get('dr') is True, "g. A on the line again: the draft back")
# Revert
chip(L, 'more')
check([(r.get('o'), r.get('rv')) for r in popd(L).get('rows')] == [('text', None), ('rem', True)], "ae. a stored line's draft: More says Revert (rv)",
      popd(L).get('rows'))
press(L, "PAD2")
more(L, 'rem')
check(bdoc(L).get('t') == '/cast Healing Wave' and not bdoc(L).get('dr') and mlog(L) == [], "g. Revert: the stored line again, nothing written")
# Done in a fight: refused, the draft kept; after it: ONE write
press(L, "PAD3"); pick_row(L, lambda r: r.get('o') == 'me')
L.execute(COMBAT_ON)
sel(L, allow=False)
d = mdoc(L)
check(d.get('note') == 'combat' and d.get('mode') == 'build' and bdoc(L).get('dr') is True and mlog(L) == [],
      "g. Done in a fight: refused ('combat'), no call, the draft kept", (d.get('note'), mlog(L)))
check(not bdoc(L).get('w'), "g. ... and in a fight the chips stay neutral (no soft checks)", bdoc(L).get('w'))
L.execute(COMBAT_OFF)
sel(L)
check(mlog(L) == ['edit 1 nil /cast [help,nodead][@player] Healing Wave'] and L.eval('mbody("Alpha")') == '/cast [help,nodead][@player] Healing Wave',
      "g. the fight over, Done: ONE write", mlog(L))
# Keep on, the If grid (tri-state), the On list, Y
press(L, "PAD1")
chip(L, 'keep')
check(bdoc(L).get('t') == '/cast [help,nodead][@player] !Healing Wave' and (bdoc(L).get('a') or {}).get('bang') is True,
      "g. Keep on: '!' on the spell (the game's 'never toggle off')", bdoc(L).get('t'))
chip(L, 'keep')
chip(L, 'c2f')
check(popd(L).get('k') == 'if' and popd(L).get('case') == 2, "g. case 2's If: the tri-state grid")
ifs = [(r.get('k'), r.get('s')) for r in popd(L).get('rows')]
check([k for k, _ in ifs] == ['harm', 'help', 'dead', 'exists', 'combat', 'mod:shift', 'mod:ctrl', 'mounted', 'channeling', 'group'],
      "g. a Shaman's If grid: no Alt (only when parsed), no Pet out / Stealth / Form", ifs)
pick_row(L, lambda r: r.get('k') == 'combat')
check(bdoc(L).get('t') == '/cast [help,nodead][@player,combat] Healing Wave' and popd(L).get('k') == 'if', "g. A: yes ([combat]); the grid stays open")
pick_row(L, lambda r: r.get('k') == 'combat')
check(bdoc(L).get('t') == '/cast [help,nodead][@player,nocombat] Healing Wave', "g. A again: no ([nocombat])", bdoc(L).get('t'))
pick_row(L, lambda r: r.get('k') == 'combat')
check(bdoc(L).get('t') == '/cast [help,nodead][@player] Healing Wave', "g. A again: off", bdoc(L).get('t'))
pick_row(L, lambda r: r.get('k') == 'mod:shift')
check(bdoc(L).get('t') == '/cast [help,nodead][@player,mod:shift] Healing Wave', "g. Shift (the player's ZL / LT / L2): [mod:shift]")
press(L, "PAD4")
check(bdoc(L).get('t') == '/cast [help,nodead][@player] Healing Wave' and popd(L).get('k') == 'if', "g. Y in the grid: that cell off")
press(L, "PAD2")
check(not bdoc(L).get('pop') and mdoc(L).get('mode') == 'build', "g. B: the pop-out closes (one step back)")
chip(L, 'c1u')
units = [r.get('u') for r in popd(L).get('rows')]
check(units == ['', 'softenemy', 'focus', 'player', 'targettarget', 'party1', 'party2', 'party3', 'party4', 'mouseover', 'name'],
      "g. On (D5): Target, Soft enemy, Focus, Me, Target's target, Party 1-4, Mouseover, Name (no Soft friend: its CVar off; no Pet)", units)
pick_row(L, lambda r: r.get('u') == 'focus')
check(bdoc(L).get('t') == '/cast [@focus,help,nodead][@player] Healing Wave', "g. Focus: '@focus' first in its set", bdoc(L).get('t'))
tap(L, bdoc(L)['ch']['c2u']); press(L, "PAD4")
check(bdoc(L).get('t') == '/cast [@focus,help,nodead] Healing Wave' and len(bdoc(L).get('cs')) == 1, "g. Y on case 2: it goes", bdoc(L).get('t'))
# Else another spell: the case's own spell (a clause of its own)
press(L, "PAD3")
pick_row(L, lambda r: r.get('o') == 'sp')
check(popd(L).get('k') == 'arg' and popd(L).get('case') == 2, "g. Else another spell: the Spell list for case 2 at once")
pick_row(L, lambda r: r.get('n') == 'Lightning Bolt')
check(bdoc(L).get('t') == '/cast [@focus,help,nodead] Healing Wave; Lightning Bolt' and (bdoc(L)['cs'][1].get('a') or {}).get('n') == 'Lightning Bolt',
      "g. ... '; Lightning Bolt' (its own chip on case 2)", bdoc(L).get('t'))
check(bdoc(L).get('rows')[2] == ['c2u', 'c2f', 'c2a'], "g. case 2's row: On, If, its spell")
chip(L, 'arg')
pick_row(L, lambda r: r.get('n') == 'Lightning Shield')
check(bdoc(L).get('t') == '/cast [@focus,help,nodead] Lightning Shield; Lightning Bolt', "g. the line's spell changed: case 2 keeps its own")
for o in ('tg', 'bl'):
    press(L, "PAD3"); pick_row(L, lambda r, o=o: r.get('o') == o)
    if popd(L):
        press(L, "PAD2")
check(len(bdoc(L).get('cs')) == 4 and ['add'] not in bdoc(L).get('rows'), "g. at most 4 cases: no + Case then", bdoc(L).get('rows'))
press(L, "PAD2")
check(mdoc(L).get('mode') == 'edit', "g. B: the editor (the draft kept)")
L.execute('MRx.st.draft = nil')
# hard: no spell; long
tap(L, 21); tap(L, "a")
pick_row(L, lambda r: r.get('k') == 'cast')
press(L, "PAD2")
L.execute('MLOG = {}')
sel(L)
check(mdoc(L).get('note') == 'pick' and bdoc(L).get('cur') == 'arg' and mlog(L) == [], "g. Done with no spell: refused 'pick', the glow on the spell's chip",
      (mdoc(L).get('note'), bdoc(L).get('cur')))
L.eval('function() local i = GetMacroIndexByName("Alpha"); GENM[i].body = string.rep("y", 240) end')()
chip(L, 'arg'); pick_row(L, lambda r: r.get('n') == 'Lightning Shield')
sel(L)
check(bdoc(L).get('h') == 'long' and mdoc(L).get('note') == 'long' and mlog(L) == [] and bdoc(L).get('len') > 255,
      "g. past 255 (bytes): 'long', Done refused, nothing written", (bdoc(L).get('h'), bdoc(L).get('len')))
press(L, "PAD2")
L.execute('MRx.st.draft = nil')
# soft: book, bags, else (amber; Done still writes)
L = opened([("Alpha", "/cast Frostbolt\n/use Hearthstone\n/cast [harm,nodead] Lightning Bolt\n/cast [harm,nodead][] Lightning Bolt")], CHAR)
press(L, "PAD1")
d = mdoc(L)
check([x.get('w') for x in d.get('ls')] == ['book', 'bags', 'else', None],
      "g. the strips' amber words: not in the book, not in the bags, a harmful line with no plain else (a plain else: none)", [x.get('w') for x in d.get('ls')])
press(L, "PADDDOWN"); press(L, "PAD1")
check(bdoc(L).get('w') == ['book'] and not bdoc(L).get('h'), "g. the builder says it too (soft: Done not refused)", bdoc(L).get('w'))
chip(L, 'keep')
L.execute('MLOG = {}')
sel(L)
check(mlog(L) == ['edit 1 nil /cast !Frostbolt\n/use Hearthstone\n/cast [harm,nodead] Lightning Bolt\n/cast [harm,nodead][] Lightning Bolt'],
      "g. ... and Done writes", mlog(L))
# Remove: More's Remove ASKS (s15: Cancel [B] / Remove [A]); A confirms within 6 s; B cancels; the time out cancels
press(L, "PADDDOWN"); press(L, "PAD1")
L.execute('MLOG = {}')
more(L, 'rem')
check(bdoc(L).get('rem') is True and mlog(L) == [] and not bdoc(L).get('pop'), "g. More > Remove (a stored line, unchanged): it ASKS, nothing written")
cur0 = bdoc(L).get('cur')
press(L, "PADDDOWN"); press(L, "PAD4"); press(L, "PAD3"); sel(L, allow=False)
check(bdoc(L).get('rem') is True and mlog(L) == [] and not bdoc(L).get('pop') and bdoc(L).get('cur') == cur0 and mdoc(L).get('mode') == 'build',
      "ae. while it asks: the D-pad, Y, X and Select do nothing", (bdoc(L).get('cur'), bdoc(L).get('pop')))
press(L, "PAD2")
check(not bdoc(L).get('rem') and mdoc(L).get('mode') == 'build' and mlog(L) == [], "ae. B: the ask cancelled, still in the builder")
more(L, 'rem')
L.execute('advance(6.5)')
check(not bdoc(L).get('rem'), "g. not confirmed within 6 s: the ask goes")
more(L, 'rem')
tap(L, bdoc(L)['ch']['cmd'])
check(not bdoc(L).get('rem') and bdoc(L).get('cur') == 'cmd' and mlog(L) == [], "ae. a finger elsewhere cancels the ask (then selects there)")
more(L, 'rem')
tap(L, "a", allow=True)
check(mlog(L) == ['edit 1 nil /cast !Frostbolt\n/cast [harm,nodead] Lightning Bolt\n/cast [harm,nodead][] Lightning Bolt'] and mdoc(L).get('mode') == 'edit',
      "g. the ask's A: the line removed, ONE write", mlog(L))
check(clean(L) == {}, "g. clean (every write inside a Done)", clean(L))
# Name...: a typed unit (nothing written)
press(L, "PAD1")
chip(L, 'c1u')
pick_row(L, lambda r: r.get('u') == 'name')
check(mdoc(L).get('kbd', {}).get('name') == 'macunit', "g. On: Name... opens the keyboard")
L.execute('MLOG = {}')
kbd_done(L, "Bob", allow=False)
check(bdoc(L).get('t') == '/cast [@Bob] !Frostbolt' and mlog(L) == [] and mdoc(L).get('mode') == 'build',
      "g. ... 'Bob' typed: '@Bob' on the case, NOTHING written, back in the builder", bdoc(L).get('t'))
press(L, "PAD2")
check(clean(L) == {}, "g. clean (the typed unit)", clean(L))

# ================================================================ h. Delete, and our stored binds following a rename / delete
L = opened(GEN, CHAR)
L.execute('WowCompanionSpellsDB.stickBy = { ["Korvash-Realm"] = { ["1:9"] = { t = "macro", n = "Bravo", i = 1 } }, '
          '["Other-Realm"] = { ["1:10"] = { t = "macro", n = "Bravo", i = 1 } } }; '
          'WowCompanionSpellsDB.fightBy = { ["Korvash-Realm"] = { [25] = { t = "macro", n = "Bravo" }, [26] = { t = "item", id = 5, n = "x" } } }')
press(L, "PADDRIGHT"); press(L, "PAD1")
press(L, "PAD1"); kbd_done(L, "Bravo2")
check(L.eval('WowCompanionSpellsDB.stickBy["Korvash-Realm"]["1:9"].n') == 'Bravo2' and
      L.eval('WowCompanionSpellsDB.stickBy["Other-Realm"]["1:10"].n') == 'Bravo2' and
      L.eval('WowCompanionSpellsDB.fightBy["Korvash-Realm"][25].n') == 'Bravo2',
      "h. a General macro renamed: every character's stored binds of it follow")
tap(L, 22)
check(mdoc(L).get('ef') == 'more' and mdoc(L).get('efc') == 22, "h. the More button (s22)", mdoc(L).get('ef'))
L.execute('MLOG = {}')
press(L, "PAD1"); press(L, "PADDDOWN"); press(L, "PADDDOWN"); press(L, "PAD1")   # s16: Insert below, Presets, Delete
d = mdoc(L)
check(d.get('del') is True and mlog(L) == [] and not d.get('pop') and d.get('mode') == 'edit', "h. More > Delete ASKS (nothing deleted)",
      (d.get('del'), mlog(L)))
press(L, "PADDUP"); press(L, "PAD4"); press(L, "PAD3")
check(mdoc(L).get('del') is True and mdoc(L).get('mode') == 'edit' and not mdoc(L).get('pop') and mlog(L) == [],
      "ae. while it asks: Up, Y and X do nothing")
press(L, "PAD2")
check(not mdoc(L).get('del') and mdoc(L).get('mode') == 'edit', "ae. B: the ask cancelled (still in the editor)")
press(L, "PAD1"); press(L, "PADDDOWN"); press(L, "PADDDOWN"); press(L, "PAD1")   # s16: Insert below, Presets, Delete
L.execute('advance(6.5)')
check(not mdoc(L).get('del'), "h. not confirmed within 6 s: the ask goes")
more(L, 'del')
L.execute('ALLOW = true'); press(L, "PAD1"); L.execute('ALLOW = false')
d = mdoc(L)
check(mlog(L) == ['delete 2'] and L.eval('mnames(GENM)') == 'Alpha|Charlie' and d.get('mode') == 'grid' and d.get('cur') == 1
      and d['mac']['n'] == 'Charlie', "h. the ask's A within 6 s: DeleteMacro; the grid, the selection min(count, old)", (mlog(L), d.get('cur')))
check(L.eval('WowCompanionSpellsDB.stickBy["Korvash-Realm"]["1:9"]') is None and
      L.eval('WowCompanionSpellsDB.stickBy["Other-Realm"]["1:10"]') is None and
      L.eval('WowCompanionSpellsDB.fightBy["Korvash-Realm"][25]') is None and
      L.eval('WowCompanionSpellsDB.fightBy["Korvash-Realm"][26].n') == 'x', "h. its stored binds go (only ours of that name)")
# a character macro renamed: this character's only
L.execute('WowCompanionSpellsDB.stickBy = { ["Korvash-Realm"] = { ["1:9"] = { t = "macro", n = "Heal", i = 1 } }, '
          '["Other-Realm"] = { ["1:10"] = { t = "macro", n = "Heal", i = 1 } } }')
tap(L, "tc"); press(L, "PAD1"); press(L, "PAD1"); kbd_done(L, "Heal2")
check(L.eval('WowCompanionSpellsDB.stickBy["Korvash-Realm"]["1:9"].n') == 'Heal2' and
      L.eval('WowCompanionSpellsDB.stickBy["Other-Realm"]["1:10"].n') == 'Heal', "h. a Character macro renamed: this character's binds only")
check(clean(L) == {}, "h. clean", clean(L))

# ================================================================ i. the Bind box for a macro (Book b63), hosted
BIND_PRE = r'''
WowCompanion.spellsStorageFor = function(page, set, pos) return (page - 1) * 48 + (set - 1) * 12 + pos end
'''
L = opened(GEN, CHAR)
L.execute(BIND_PRE)
press(L, "PADDRIGHT")
press(L, "PAD3")
ctl = (last(L, 'spellsctl').get('ctl') or {})
check(mdoc(L).get('mode') == 'bind' and ctl.get('via') == 'macros' and ctl.get('mode') == 'bind'
      and (ctl.get('bitem') or {}).get('n') == 'Bravo' and (ctl.get('bitem') or {}).get('mac') is True,
      "i. X: the Spellbook's Bind box for the macro, hosted by the room (via macros, bitem mac)", ctl)
check(holder(L) == "macros", "i. the room keeps the controller", holder(L))
# Normal: the game's own road on the slot the press names (no trigger, D-pad Left = page 1 set 1 pos 1 -> a refused dpad bar? use X)
L.execute('MLOG = {}; WowCompanion.padMap.dpadState = function() return { want = false } end')
L.execute('ALLOW = true'); press(L, "PADDLEFT"); L.execute('ALLOW = false')
ack = last(L, 'spellsack')
SLOT = ((ack.get('pad') or {}).get('s')) or -1
check(ack.get('ok') is True and ack.get('item') is True and L.eval('(GetActionInfo(%d))' % SLOT) == 'macro' and L.eval('GetActionText(%d)' % SLOT) == 'Bravo'
      and mlog(L) == ['pickup 2', 'place %d' % SLOT], "i. Normal: PickupMacro + PlaceAction on the pressed slot, read back", (ack, mlog(L)))
L.execute('ALLOW = true'); press(L, "PAD2"); L.execute('ALLOW = false')
check(L.eval('(GetActionInfo(%d))' % SLOT) is None, "i. B undoes it (the slot as it was: empty)", L.eval('GetActionInfo(%d)' % SLOT))
check(mdoc(L).get('mode') == 'bind', "i. ... and the box waits for another button")
# L3: the stick's own button runs the macro's TEXT
L.execute('ALLOW = true'); press(L, "PADLSTICK"); L.execute('ALLOW = false')
L.execute('advance(1)')
e = L.eval('(WowCompanionSpellsDB.stickBy or {})["Korvash-Realm"]')
st = e and e["1:9"]
check(st is not None and st.t == 'macro' and st.n == 'Bravo', "i. L3: the stick's entry is the macro by name", st and (st.t, st.n))
L.execute('COMBAT = false; WowCompanion.padMap.emit(true); advance(1)')
L3b = L.eval('rawget(_G, "WowCompanionStickL3")')
check(L3b is not None and (L3b.a['type'] == 'macro' and L3b.a['macrotext'] == '/dance' and L3b.a['macro'] is None),
      "i. ... its button: type macro, macrotext = the macro's own text, no saved-macro attribute",
      L3b and (L3b.a['type'], L3b.a['macrotext']))
L.execute('ALLOW = true'); press(L, "PAD1"); L.execute('ALLOW = false')
L.execute('advance(1)')
check(mdoc(L).get('mode') == 'grid' and holder(L) == 'macros', "i. A keeps it: the box ends, the room is back on its grid", mdoc(L).get('mode'))
# a gone macro
press(L, "PAD3")
L.eval('function() local i = GetMacroIndexByName("Bravo"); table.remove(GENM, i) end')()
L.execute('ALLOW = true'); press(L, "PADDUP"); L.execute('ALLOW = false')
check(last(L, 'spellsack').get('why') == 'missing', "i. no macro of that name any more: 'missing'", last(L, 'spellsack'))
press(L, "PAD2")
check(clean(L) == {}, "i. clean (every bar write inside a press of the box)", clean(L))
# In combat: the router's fight text
L = opened(GEN, CHAR)
L.execute(BIND_PRE)
press(L, "PADDRIGHT"); press(L, "PAD3")
check(L.eval('WowCompanion._spellsBook.ctl.bind ~= nil'), "i. the box is up again (a fresh room)")
L.execute('WowCompanion._spellsBook.ctl.bind.fight = true')
L.execute('trig(false, true)')
L.execute('ALLOW = true'); press(L, "PADDUP"); L.execute('ALLOW = false')
L.execute('advance(1)')
fb = L.eval('(WowCompanionSpellsDB.fightBy or {})["Korvash-Realm"]')
ent = None
if fb:
    for kk in list(fb.keys()):
        if fb[kk] and fb[kk].t == 'macro':
            ent = fb[kk]
check(ent is not None and ent.n == 'Bravo', "i. In combat: the button's fight thing is the macro by name", ent and ent.n)
FT = 'rawget(_G, "WowCompanionFaceRouter").a["wc-ft-2"]'
check(L.eval(FT) == '/dance', "i. ... the router's fight text for that slot = the macro's own text (no saved-macro name)", L.eval(FT))
L.eval('function() local i = GetMacroIndexByName("Bravo"); GENM[i].body = "/cheer" end')()
L.execute('fire("UPDATE_MACROS"); advance(2)')
check(L.eval(FT) == '/cheer', "i. the macro edited elsewhere: UPDATE_MACROS re-arms the router out of combat", L.eval(FT))
check(L.eval('(function() for _, f in ipairs(ALLF) do for k, v in pairs(f.a or {}) do if k == "macro" then return f._name end end end end)()') is None,
      "i. no frame carries a saved-macro attribute")
check(clean(L) == {}, "i. clean", clean(L))

# ================================================================ j. D2: the game's own macro window
L = opened(GEN, CHAR)
L.execute('mkwin("MacroFrame", false); fire("ADDON_LOADED", "Blizzard_MacroUI"); advance(0.5)')
wx = L.eval('WowCompanion.pad.rows.mac.cells[MRx.COL.wx]')
check(wx.a['type'] == 'click' and L.eval('WowCompanion.pad.rows.mac.cells[MRx.COL.wx].a.clickbutton == MacroFrameCloseButton'),
      "j. the wx cell is the window's own close button (a secure click, set out of combat)", (wx.a['type'],))
n0 = nsent(L)
L.execute('MacroFrame:Show(); for _, fn in ipairs(MacroFrame.hooks.OnShow or {}) do fn(MacroFrame) end; advance(0.5)')
d = last(L, 'macros', n0).get('m') or {}
check(d.get('win') is True, "j. the window shown: the document says win (the app brings the room up)", d.get('win'))
check(L.eval('MRx.canWrite()') == 'window', "j. writes wait ('window')")
L.execute('ACTIONS = {}')
tap(L, "wx")
check(L.eval('MacroFrame._shown') is False and L.eval('ACTIONS[1] and ACTIONS[1].kind') == 'close' and L.eval('MRx.canWrite()') is None,
      "j. the wx tap: the game's own close button closed it, once; writes go again", (L.eval('MacroFrame._shown'),))
check(clean(L) == {}, "j. clean", clean(L))

# ================================================================ k. B one step back, then Home; leaving drops what was half done
L = opened([("Alpha", "/cast Lightning Bolt")] + GEN[1:], CHAR)
press(L, "PAD1"); press(L, "PADDDOWN"); press(L, "PAD1"); chip(L, 'arg')
check(mdoc(L).get('mode') == 'build' and popd(L).get('k') == 'arg', "k. the builder with its Spell list out")
press(L, "PAD2")
check(mdoc(L).get('mode') == 'build' and not popd(L), "k. B: the pop-out goes (the builder stays)")
press(L, "PAD2")
check(mdoc(L).get('mode') == 'edit', "k. B: the editor")
press(L, "PAD2")
check(mdoc(L).get('mode') == 'grid' and holder(L) == 'macros', "k. B: the grid (still held)")
n0 = nsent(L)
press(L, "PAD2")
back = [x for x in docs(L, n0, 'macpad') if x.get('k') == 'BACK']
check(holder(L) is None and back, "k. B at the grid: the controller back (\"B\"), macpad BACK (the app goes Home)", holder(L))
check(mlog(L) == [], "k. B all the way: nothing written", mlog(L))
L = opened(GEN, CHAR)
press(L, "PAD1"); press(L, "PAD1")
check(L.eval('WowCompanion.chatField.name()') == 'macname', "k. a keyboard open")
tap(L, "f")
check(L.eval('WowCompanion.chatField.name()') is None and holder(L) is None and mdoc(L).get('mode') == 'grid' and mlog(L) == [],
      "k. the room left (f): the keyboard goes, the controller back, nothing written", (holder(L), mlog(L)))
L = opened(GEN, CHAR)
press(L, "PADDRIGHT"); press(L, "PAD3")
tap(L, "f")
check(not (last(L, 'spellsctl').get('ctl') or {}).get('on') and holder(L) is None, "k. left with the Bind box up: the box goes quietly")
check(clean(L) == {}, "k. clean", clean(L))

# ================================================================ l. the document's size
L = opened([("M%02d" % i, "/say " + "x" * 200) for i in range(40)], [("C%02d" % i, "/y hi") for i in range(18)])
L.execute('WowCompanion.macroRoom.emit()')
raw = L.eval('SENT[#SENT]')
d = json.loads(raw)['m']
d.pop('pad', None)
small = len(json.dumps(d, separators=(',', ':')))
check(small < 1800, "l. one page of names and one body: the room's own part stays under 1.8 KB (%d bytes)" % small, small)

# ================================================================ m. per character
L = opened(GEN, CHAR)
tap(L, "tc")
tap(L, "f")
check(L.eval('WowCompanionSpellsDB.macroRoom["Korvash-Realm"].tab') == 'c' and L.eval('WowCompanionSpellsDB.macroRoom["Korvash-Realm"].sel') == 'Heal',
      "m. the tab and the selection kept for this character (name-realm)")
L.execute('MRx.st.tab = "g"; MRx.st.cur = 0; MRx.st.sel = nil; MRx.st.selName = nil')
tap(L, "h")
check(mdoc(L).get('tab') == 'c' and mdoc(L)['mac']['n'] == 'Heal', "m. the room comes back where this character left it")

# ================================================================ n. (review) two macros of one name: the one under the glow, always
# the game's own popup lets two macros share a name, and GetMacroIndexByName answers only the first: A, a line's Done, Delete and the
# preview must act on the macro the glow is on; a bind of such a name is refused (our buttons find a macro by name)
TWINS = [("Heal", "/cast Healing Wave"), ("Heal", "/cast Lesser Healing Wave"), ("Zap", "/cast Lightning Bolt")]
L = opened(TWINS, CHAR)
press(L, "PADDRIGHT")
d = mdoc(L)
check(d.get('cur') == 1 and d['mac']['lines'] == ['/cast Lesser Healing Wave'],
      "n. the second 'Heal' under the glow: its own body beside the grid (not the first's)", (d.get('cur'), d.get('mac')))
press(L, "PAD1")
d = mdoc(L)
check(d.get('mode') == 'edit' and d['mac']['lines'] == ['/cast Lesser Healing Wave'], "n. A edits the second (the one selected)", d.get('mac'))
press(L, "PADDDOWN"); press(L, "PAD1"); more(L, 'text')
L.execute('MLOG = {}')
kbd_done(L, "/cast Chain Heal")
check(mlog(L) == ['edit 2 nil /cast Chain Heal'] and L.eval('GENM[1].body') == '/cast Healing Wave' and L.eval('GENM[2].body') == '/cast Chain Heal',
      "n. a line's Done writes the second (EditMacro 2), the first untouched", (mlog(L), L.eval('GENM[1].body')))
d = mdoc(L)
check(d.get('mode') == 'edit' and d['mac']['lines'] == ['/cast Chain Heal'], "n. ... and the editor stays on it (the game's own index)", d.get('mac'))
press(L, "PAD2")
check(mdoc(L).get('cur') == 1, "n. B: the grid, the glow still on the second", mdoc(L).get('cur'))
L.execute('MLOG = {}')
press(L, "PAD3")
d = mdoc(L)
check(d.get('mode') == 'grid' and d.get('note') == 'taken' and not (last(L, 'spellsctl').get('ctl') or {}).get('on') and mlog(L) == [],
      "n. X on a shared name: refused 'taken' (no Bind box: our buttons go by name)", (d.get('mode'), d.get('note')))
L.execute('WowCompanionSpellsDB.stickBy = { ["Korvash-Realm"] = { ["1:9"] = { t = "macro", n = "Heal", i = 1 } } }')
press(L, "PAD1")
more(L, 'del')
check(mdoc(L).get('del') is True, "n. More > Delete asks", mdoc(L).get('del'))
L.execute('MLOG = {}; ALLOW = true')
press(L, "PAD1")
L.execute('ALLOW = false')
d = mdoc(L)
check(mlog(L) == ['delete 2'] and L.eval('mnames(GENM)') == 'Heal|Zap' and L.eval('GENM[1].body') == '/cast Healing Wave'
      and d.get('mode') == 'grid' and not d.get('note'), "n. Delete takes the second, not the first; no false 'refused'", (mlog(L), d.get('note')))
check(L.eval('WowCompanionSpellsDB.stickBy["Korvash-Realm"]["1:9"].n') == 'Heal',
      "n. our binds of 'Heal' stay (the other 'Heal' still answers to it)")
check(clean(L) == {}, "n. clean", clean(L))
# the FIRST twin deleted: its twin slides into the same index, and that is still a delete (the tab holds one fewer)
L = opened(TWINS, CHAR)
press(L, "PAD1")
more(L, 'del')
L.execute('MLOG = {}; ALLOW = true')
press(L, "PAD1")
L.execute('ALLOW = false')
d = mdoc(L)
check(mlog(L) == ['delete 1'] and L.eval('mnames(GENM)') == 'Heal|Zap' and L.eval('GENM[1].body') == '/cast Lesser Healing Wave'
      and d.get('mode') == 'grid' and not d.get('note') and d.get('cur') == 0 and d['mac']['lines'] == ['/cast Lesser Healing Wave'],
      "n. the first twin deleted: done (not 'refused'), the glow on the twin that took its place", (mlog(L), d.get('note'), d.get('mac')))
check(clean(L) == {}, "n. clean (the first twin)", clean(L))
# the Bind box's own check at the press (Book b63 review): a twin made after the box opened, ahead of the box's macro
L = opened(GEN, CHAR)
L.execute(BIND_PRE)
tap(L, "tc")
press(L, "PAD3")
check((last(L, 'spellsctl').get('ctl') or {}).get('mode') == 'bind', "n. the box for the character's 'Heal'")
L.eval('function() GENM[#GENM + 1] = { name = "Heal", icon = 1, body = "/y", seq = mseq() }; sortT(GENM) end')()
L.execute('MLOG = {}; WowCompanion.padMap.dpadState = function() return { want = false } end')
L.execute('ALLOW = true'); press(L, "PADDLEFT"); L.execute('ALLOW = false')
check(last(L, 'spellsack').get('why') == 'taken' and mlog(L) == [],
      "n. a press once another 'Heal' comes first: refused 'taken', nothing placed", (last(L, 'spellsack'), mlog(L)))
press(L, "PAD2")
check(clean(L) == {}, "n. clean (the box)", clean(L))

# ================================================================ o. (s14) PRESETS (D2): whole macros on a new or empty macro
L = opened([("Alpha", ""), ("Beta", "/wave")], CHAR)
press(L, "PAD1")
d = mdoc(L)
tk = [t.get('k') for t in d.get('tiles') or []]
check(tk == ['blank', 'heal', 'fof', 'focus', 'atk', 'mo'],
      "o. a Shaman's tiles in the table's order: (s17) Blank FIRST, Heal, Friend or foe, Set focus, Attack + spell ...", tk)
check('int' not in tk and 'petatk' not in tk and 'form' not in tk and 'trink' not in tk,
      "o. no interrupt in the book, no pet, no forms, no trinket with a use: those tiles are not shown", tk)
check('seq' not in tk, "o. one harmful spell only: no Sequence (2+ needed)", tk)
check(tk[-1] == 'mo' and len(tk) <= 8, "o. Mouseover heal LAST (room left), at most 8", tk)
tb = d['tiles'][0]
check(tb.get('k') == 'blank' and tb.get('t') == '' and tb.get('ic') == [134400], "o. (s17) Blank: no lines, the default icon's picture", tb)
t0 = d['tiles'][tk.index('heal')]
check(t0.get('t') == '#showtooltip\n/cast [help,nodead][@player] Healing Wave' and t0.get('ic') == [130331],
      "o. Heal, else me: '#showtooltip' + '[help,nodead][@player]', the book's helpful spell picked", t0)
atk = d['tiles'][tk.index('atk')]
check(atk.get('t') == '/target [noexists][dead] softenemy\n/startattack [harm,nodead]\n/cast Lightning Bolt',
      "o. Attack + spell: the soft enemy targeted first (the 10-01 fix), then the attack, then the spell", atk.get('t'))
tap(L, 2 + tk.index('heal')); tap(L, "a")
d = mdoc(L)
ps = d.get('ps') or {}
check(d.get('mode') == 'ps' and ps.get('k') == 'heal' and [x.get('n') for x in ps.get('picks')] == ['Healing Wave'] and ps.get('cur') == 'p1'
      and [x.get('k') for x in ps.get('ls')] == ['tip', 'cmd'], "o. A on a tile: its preview (strips, its pick)", ps)
tap(L, 0); tap(L, "a")
check(popd(L).get('k') == 'arg' and popd(L).get('ps') is True, "o. A on the pick: the Spell list")
pick_row(L, lambda r: r.get('n') == 'Lightning Shield')
check((mdoc(L).get('ps') or {}).get('t') == '#showtooltip\n/cast [help,nodead][@player] Lightning Shield', "o. ... the preset follows the pick")
L.execute('MLOG = {}')
sel(L)
check(mlog(L) == ['edit 1 nil #showtooltip\n/cast [help,nodead][@player] Lightning Shield'] and mdoc(L).get('mode') == 'edit',
      "o. Done on an empty macro: ONE write of the whole macro (no second A)", mlog(L))
# a macro with lines: More > Presets; Done ASKS (Cancel / Replace), the ask's A replaces it
press(L, "PAD2"); press(L, "PADDRIGHT"); press(L, "PAD1")
check(mdoc(L).get('mac', {}).get('n') == 'Beta' and mdoc(L).get('tiles') is None, "o. Beta (a line): no tiles in its editor")
more(L, 'pre')
check(mdoc(L).get('mode') == 'pre' and len(mdoc(L).get('tiles') or []) >= 4, "o. More > Presets: the tiles view")
tap(L, [t.get('k') for t in mdoc(L).get('tiles')].index('focus')); tap(L, "a")
check(mdoc(L).get('mode') == 'ps' and mdoc(L)['ps']['k'] == 'focus', "o. Set focus's preview", mdoc(L).get('ps'))
L.execute('MLOG = {}')
sel(L)
check(mlog(L) == [] and (mdoc(L).get('ps') or {}).get('arm') is True, "o. Done on a macro with lines: it ASKS (Cancel / Replace), nothing written",
      mlog(L))
sel(L)
check(mlog(L) == [] and (mdoc(L).get('ps') or {}).get('arm') is True, "ae. Select again while it asks: nothing (only A confirms)", mlog(L))
press(L, "PAD2")
check(mlog(L) == [] and mdoc(L).get('mode') == 'ps' and not (mdoc(L).get('ps') or {}).get('arm'), "ae. B: the ask cancelled, the preview stays")
sel(L)
L.execute('advance(6.5)')
check(not (mdoc(L).get('ps') or {}).get('arm'), "o. not confirmed within 6 s: the ask goes")
sel(L)
L.execute('ALLOW = true'); press(L, "PAD1"); L.execute('ALLOW = false')
check(mlog(L) == ['edit 2 nil /focus'] and L.eval('mbody("Beta")') == '/focus', "o. the ask's A within 6 s: REPLACED, ONE write", mlog(L))
press(L, "PAD2")
check(mdoc(L).get('mode') == 'grid', "o. B: the grid")
# the picks' rules: no helpful spell = no heal tiles; an interrupt in the book; pet classes; forms; a trinket; two harmful
L = opened([("Alpha", "")], CHAR, macros='BOOK = { 403, 8042 }; WORN[13] = 9001; NFORMS = 1; CLASS = "DRUID"')
press(L, "PAD1")
tk = [t.get('k') for t in mdoc(L).get('tiles') or []]
check(tk == ['blank', 'int', 'focus', 'atk', 'seq', 'form', 'trink'], "o. (s17 Blank first) Lightning Bolt + Earth Shock, a Druid with a form, a trinket with a use: "
      "Focus interrupt (Earth Shock), Set focus, Attack, Sequence, Leave form, Trinket; no heal tiles", tk)
tiles = {t['k']: t for t in mdoc(L)['tiles']}
check(tiles['int']['t'] == '/stopcasting\n/cast [@focus,harm,nodead][] Earth Shock', "o. Focus interrupt: /stopcasting then the focus' interrupt, a plain else")
check(tiles['seq']['t'] == '/castsequence reset=target Lightning Bolt, Earth Shock', "o. Sequence: its two harmful spells, reset=target")
check(tiles['trink']['t'] == '/use [combat] 13\n/cast Lightning Bolt', "o. Trinket + spell: the worn trinket's slot 13")
check(tiles['form']['t'] == '/cancelform\n/cast Lightning Bolt', "o. Leave form + spell")
L = opened([("Alpha", "")], CHAR, pre='CLASS = "HUNTER"')
press(L, "PAD1")
tk = [t.get('k') for t in mdoc(L).get('tiles') or []]
check('petatk' in tk, "o. a Hunter: Pet attack + spell", tk)
check(clean(L) == {}, "o. clean", clean(L))

# ================================================================ o2. (s17) BLANK (the user, 2026-11-18: "I need a blank template option on
# macros to just start a fresh new blank template"): Blank first; on an empty macro A on it writes nothing and the editor goes blank (no
# tiles, the glow on + Line, More offers Presets); on a macro with lines its Done asks Replace, the A = ONE EditMacro (no lines, the default icon)
L = opened([("Alpha", ""), ("Beta", "/wave\n/cheer")], CHAR)
press(L, "PAD1")
d = mdoc(L)
check(d.get('mac', {}).get('n') == 'Alpha' and (d.get('tiles') or [{}])[0].get('k') == 'blank', "o2. Alpha (empty): Blank the first tile", d.get('tiles'))
L.execute('MLOG = {}')
L.execute(COMBAT_ON)
tap(L, 2); tap(L, "a")
check(mdoc(L).get('note') == 'combat' and mlog(L) == [] and mdoc(L).get('tiles'), "o2. Blank in a fight: refused 'combat', the tiles stay",
      mdoc(L).get('note'))
L.execute(COMBAT_OFF)
tap(L, 2); tap(L, "a")
d = mdoc(L)
check(mlog(L) == [] and d.get('mode') == 'edit' and not d.get('tiles') and d.get('ls') == [] and d.get('ef') == 'add',
      "o2. Blank on an empty macro: nothing written, no tiles, no lines, the glow on + Line", (mlog(L), d.get('mode'), d.get('ef')))
more(L, 'pre')
check(mdoc(L).get('mode') == 'pre' and (mdoc(L).get('tiles') or [{}])[0].get('k') == 'blank', "o2. a blank macro's More: Presets again")
press(L, "PAD2")
check(mdoc(L).get('mode') == 'edit' and not mdoc(L).get('tiles'), "o2. B from Presets: still blank")
# a custom icon on an empty macro: Blank puts the default back (ONE EditMacro, no body)
L.execute('GENM[1].icon = 136048')
L.execute('MLOG = {}')
more(L, 'pre')
tap(L, 0); tap(L, "a", allow=True)
check(mlog(L) == ['edit %d nil nil' % L.eval('GetMacroIndexByName("Alpha")')] and L.eval('(select(2, GetMacroInfo(GetMacroIndexByName("Alpha"))))') == 134400,
      "o2. Blank on an empty macro with its own icon: ONE write, the icon only, back to the default", mlog(L))
press(L, "PAD2")
# Beta (lines): More > Presets > Blank: its preview, Done ASKS, the ask's A empties it (default icon, the name kept)
L.execute('GENM[2].icon = 136048')
press(L, "PADDRIGHT"); press(L, "PAD1")
check(mdoc(L).get('mac', {}).get('n') == 'Beta', "o2. Beta's editor", mdoc(L).get('mac'))
more(L, 'pre')
tap(L, 0); tap(L, "a")
check(mdoc(L).get('mode') == 'ps' and mdoc(L)['ps']['k'] == 'blank' and mdoc(L)['ps'].get('t') == '', "o2. Blank's preview: no lines",
      mdoc(L).get('ps'))
L.execute('MLOG = {}')
sel(L)
check(mlog(L) == [] and (mdoc(L).get('ps') or {}).get('arm') is True, "o2. Done on a macro with lines: it asks (Cancel / Replace)", mlog(L))
L.execute('ALLOW = true'); press(L, "PAD1"); L.execute('ALLOW = false')
bi = L.eval('GetMacroIndexByName("Beta")')
d = mdoc(L)
check(mlog(L) == ['edit %d nil ' % bi] and L.eval('mbody("Beta")') == '' and L.eval('(select(2, GetMacroInfo(GetMacroIndexByName("Beta"))))') == 134400
      and d.get('mode') == 'edit' and d.get('mac', {}).get('n') == 'Beta' and not d.get('tiles') and d.get('ef') == 'add',
      "o2. the ask's A: ONE EditMacro - no lines, the default icon, the name kept; the blank editor", (mlog(L), d.get('ef')))
check(L.eval('OUTSIDE and #OUTSIDE') == 0, "o2. every write inside the player's own press")
press(L, "PAD2")
check(clean(L) == {}, "o2. clean", clean(L))

# ================================================================ p. (review) the `wx` cell before the game's macro UI loaded
L = opened(GEN, CHAR)
check(L.eval('WowCompanion.pad.rows.mac.cells[MRx.COL.wx].a.type') is None and not mdoc(L).get('wxa'),
      "p. a row made before Blizzard_MacroUI: `wx` is no close button yet, and the document does not say wxa", mdoc(L).get('wxa'))
n0 = nsent(L)
tap(L, "wx")
acks = [x for x in docs(L, n0, 'macack') if x.get('op') == 'wx']
check(acks and acks[-1].get('ok') is False, "p. its tap clicked nothing: the answer says so (not a false 'closed')", acks[-1:] if acks else None)
L.execute(COMBAT_ON)
L.execute('mkwin("MacroFrame", true); fire("ADDON_LOADED", "Blizzard_MacroUI"); advance(0.5)')
check(not mdoc(L).get('wxa') and L.eval('WowCompanion.pad.rows.mac.cells[MRx.COL.wx].a.type') is None,
      "p. the UI loaded in a fight: not armed in the lockdown (no wxa: the app does not tap)", mdoc(L).get('wxa'))
L.execute(COMBAT_OFF)
d = mdoc(L)
check(d.get('wxa') is True and L.eval('WowCompanion.pad.rows.mac.cells[MRx.COL.wx].a.type') == 'click',
      "p. the fight over: armed (the window's own close button) and the document says wxa", d.get('wxa'))
L.execute('ACTIONS = {}')
tap(L, "wx")
check(L.eval('MacroFrame._shown') is False and L.eval('ACTIONS[1] and ACTIONS[1].kind') == 'close', "p. ... and its tap closes the window")
check(clean(L) == {}, "p. clean", clean(L))

# ================================================================ q. (review) the row made but not placed, an editor whose macro went
L = opened(GEN, CHAR)
L.execute('local real = WowCompanion.pad.describe; WowCompanion.pad.describe = function() local d = real(); '
          'if d and d.rows then d.rows.mac = nil end; return d end')
check(mdoc(L).get('norow') is True, "q. the row not in the pad's description (not placed): the document says norow", mdoc(L).get('norow'))
L = opened(GEN, CHAR)
press(L, "PADDRIGHT"); press(L, "PAD1")
check(mdoc(L).get('mode') == 'edit' and mdoc(L)['mac']['n'] == 'Bravo', "q. the editor on 'Bravo'")
L.eval('function() table.remove(GENM, 2) end')()
d = mdoc(L)
check(d.get('mode') == 'grid' and d.get('view') == 'grid' and 'ef' not in d,
      "q. 'Bravo' deleted elsewhere: back to the grid, never onto its neighbour's editor", (d.get('mode'), d.get('view'), d.get('mac')))
# a finger's a / x / b proves no hold (only the capture's own press does: a take may still be queued or pending)
L, _ = world()
seed(L, GEN, CHAR)
L.execute('fire("UPDATE_MACROS"); advance(1)')
ensure_row(L)
tap(L, "a")
d = mdoc(L)
check(holder(L) is None and not (d.get('ctl') or {}).get('held') and d.get('mode') == 'edit',
      "q. a finger's A with no hold: it acts (the editor), and the document does not claim the controller", (holder(L), d.get('ctl')))
check(clean(L) == {}, "q. clean", clean(L))

# ================================================================ r. (s13) the pet's picks: its commands and spells, with a pet out
# the user, 2026-11-16: "also things like my pet attack are missing from the spells options."
PET = r'''
PETBAR = { "PET_ACTION_ATTACK", "PET_ACTION_FOLLOW", "PET_ACTION_WAIT", "Firebolt", nil, nil, nil, "PET_MODE_AGGRESSIVE", "PET_MODE_DEFENSIVE",
           "PET_MODE_PASSIVE" }
function GetPetActionInfo(i) local n = PETBAR[i]; if n then return n, (n:find("^PET_") and "PET_ATTACK_TEXTURE" or 135809), n:find("^PET_") ~= nil end end
PET_ACTION_ATTACK, PET_ACTION_FOLLOW, PET_ACTION_WAIT, PET_MODE_PASSIVE = "Attack", "Follow", "Stay", "Passive"
PET_MODE_DEFENSIVE, PET_MODE_AGGRESSIVE, PET_MODE_ASSIST, PET_ACTION_MOVE_TO = "Defensive", "Aggressive", "Assist", "Move To"
SLASH_PET_ATTACK1, SLASH_PET_FOLLOW1, SLASH_PET_STAY1, SLASH_PET_PASSIVE1 = "/petattack", "/petfollow", "/petstay", "/petpassive"
SLASH_PET_DEFENSIVE1, SLASH_PET_AGGRESSIVE1, SLASH_PET_DISMISS1 = "/petdefensive", "/petaggressive", "/petdismiss"
SLASH_PET_ASSIST1, SLASH_PET_DEFENSIVEASSIST1, SLASH_PET_MOVE_TO1 = "/petassist", "/petdefensiveassist", "/petmoveto"
'''
PET_AFTER = r'''
WowCompanion.petTok = { name = function(t) return rawget(_G, t) end,
                        icon = function(t) return ({ PET_ATTACK_TEXTURE = 132152, PET_FOLLOW_TEXTURE = 132328, PET_WAIT_TEXTURE = 136106,
                            PET_PASSIVE_TEXTURE = 132311, PET_DEFENSIVE_TEXTURE = 132110, PET_DEFENSIVEASSIST_TEXTURE = 132110,
                            PET_AGGRESSIVE_TEXTURE = 132277, PET_ASSIST_TEXTURE = 524348, PET_MOVE_TO_TEXTURE = 457329,
                            PET_DISMISS_TEXTURE = 136222 })[t] end }
PETBOOK = { { slot = 1, id = 3110, n = "Firebolt", i = 135809, bind = true }, { slot = 2, n = "Attack", cmd = true },
            { slot = 3, id = 6307, n = "Blood Pact", i = 135803, pas = true } }
WowCompanion.spellsPetBook = function() return PETBOOK, "PET", true end
'''


def to_pet(L):
    """the editor of the selected macro, + Line, Cast, the Spell / Item pop-out's Pet tab (None = no Pet tab)"""
    press(L, "PAD1")
    tap(L, 21); tap(L, "a")
    pick_row(L, lambda r: r.get('k') == 'cast')
    tabs = popd(L).get('tabs') or []
    if 'pet' not in tabs:
        return None
    tap(L, tabs.index('pet')); tap(L, "a")
    return [(r.get('t'), r.get('n'), r.get('l'), r.get('i')) for r in popd(L).get('rows') or []]


L = opened([("Alpha", "")], CHAR, pre=PET)
L.execute(PET_AFTER)
check(to_pet(L) is None, "r. a Shaman with no pet: no Pet tab in the Spell / Item pop-out", popd(L).get('tabs'))
L = opened([("Alpha", "")], CHAR, pre=PET + 'UNITS.pet = { name = "Imp" }\n')
L.execute(PET_AFTER)
rows = to_pet(L)
names = [(t, n, l) for t, n, l, _ in rows or []]
PC = [('p', 'Attack', '/petattack'), ('p', 'Follow', '/petfollow'), ('p', 'Stay', '/petstay'), ('p', 'Passive', '/petpassive'),
      ('p', 'Defensive', '/petdefensive'), ('p', 'Aggressive', '/petaggressive'), ('p', 'Dismiss', '/petdismiss')]
check(names == PC + [('s', 'Firebolt', None)],
      "r. a pet out: the Pet tab = the pet's orders (the game's words, its slash lines), then its spell (not its passive, not its command entry)", names)
icons = {n: i for t, n, _, i in rows if t == 'p'}
check(icons.get('Attack') == 132152 and icons.get('Stay') == 136106 and icons.get('Dismiss') == 136222,
      "r. the pet bar's pictures (WC.petTok)", icons)
pick_row(L, lambda r: r.get('n') == 'Attack')
check(bdoc(L).get('c') == 'petattack' and bdoc(L).get('t') == '/petattack' and not popd(L),
      "r. Attack picked: the line BECOMES the pet's order, '/petattack'", bdoc(L).get('t'))
L.execute('MLOG = {}')
sel(L)
check(L.eval('mbody("Alpha")') == '/petattack' and mdoc(L).get('mode') == 'edit' and len(mlog(L)) == 1,
      "r. Done: ONE write, '/petattack'", (L.eval('mbody("Alpha")'), mlog(L)))
check((mdoc(L).get('ls') or [{}])[0].get('c') == 'petattack' and mdoc(L)['ls'][0].get('cn') == 'Attack'
      and mdoc(L)['ls'][0].get('i') == 132152, "r. its strip: the game's own word and the pet bar's picture", mdoc(L).get('ls'))
press(L, "PAD2")
to_pet(L)
pick_row(L, lambda r: r.get('n') == 'Firebolt')
sel(L)
check(L.eval('mbody("Alpha")') == '/petattack\n/cast Firebolt', "r. the pet's spell: '/cast <its name>'", L.eval('mbody("Alpha")'))
# a castsequence entry takes the pet's spells, never an order
tap(L, 21); tap(L, "a")
pick_row(L, lambda r: r.get('k') == 'seq')
check(popd(L).get('k') == 'seq', "r. Sequence: its spells' list", popd(L))
pick_row(L, lambda r: r.get('add'))
tabs = popd(L).get('tabs') or []
tap(L, tabs.index('pet')); tap(L, "a")
rr = [(r.get('t'), r.get('n')) for r in popd(L).get('rows') or []]
check(('s', 'Firebolt') in rr and not any(t == 'p' for t, _ in rr), "r. a sequence's Pet tab: the pet's spell, no order", rr)
press(L, "PAD2"); press(L, "PAD2"); press(L, "PAD2")
L.execute('MRx.st.draft = nil')
check(clean(L) == {}, "r. clean (reads only; each write inside a Done)", clean(L))
# the pet bar's assist modes stand in for the base ones it does not show; Move To only when the bar has it
L = opened([("Alpha", "")], CHAR, pre=PET + 'UNITS.pet = { name = "Wolf" }\n'
           'PETBAR = { "PET_ACTION_ATTACK", "PET_ACTION_FOLLOW", "PET_ACTION_MOVE_TO", nil, nil, nil, nil, "PET_MODE_ASSIST", '
           '"PET_MODE_DEFENSIVEASSIST", "PET_MODE_PASSIVE" }\n')
L.execute(PET_AFTER)
L.execute('PETBOOK = {}')
pc = [(n, l) for t, n, l, _ in to_pet(L) if t == 'p']
check(pc == [('Attack', '/petattack'), ('Follow', '/petfollow'), ('Stay', '/petstay'), ('Move To', '/petmoveto'), ('Passive', '/petpassive'),
             ('Defensive', '/petdefensiveassist'), ('Assist', '/petassist'), ('Dismiss', '/petdismiss')],
      "r. the pet bar's own modes: Defensive = its defensive-assist, Assist for Aggressive, Move To shown", pc)
# not registered on this client (no slash word, not a secure command): an extra one left out; a base one falls back to English
L = opened([("Alpha", "")], CHAR, pre=PET + 'UNITS.pet = { name = "Wolf" }\n'
           'PETBAR = { "PET_ACTION_ATTACK", "PET_ACTION_MOVE_TO" }\n',
           macros='SLASH_PET_ATTACK1 = nil; SLASH_PET_MOVE_TO1 = nil; SECURE["/petattack"] = nil; SECURE["/petmoveto"] = nil')
L.execute(PET_AFTER)
pc = [(n, l) for t, n, l, _ in to_pet(L) if t == 'p']
check(('Attack', '/petattack') in pc and not any(n == 'Move To' for n, _ in pc), "r. not registered: a base order = English, an extra one left out", pc)
# a Hunter with the pet away: the base orders, and the pet spells this character keeps by name (petSpellBy[name-realm])
L = opened([("Alpha", "")], CHAR, pre=PET, macros='CLASS = "HUNTER"; WowCompanionSpellsDB.petSpellBy = { ["Korvash-Realm"] = { [17253] = "Bite" } }; '
           'SPELLNAME[17253] = "Bite"')
L.execute(PET_AFTER)
rows = to_pet(L)
check(rows is not None and ('s', 'Bite', None) in [(t, n, l) for t, n, l, _ in rows] and ('p', 'Attack', '/petattack') in [(t, n, l) for t, n, l, _ in rows],
      "r. a Hunter, the pet dismissed: the Pet tab still offers the orders and the kept pet spell", rows)
check('"also things like my pet attack are missing from the spells options."' in SRC["spells"],
      "r. Spells s13's header note (the pet's picks) quotes the user")

# ================================================================ s. (PadMap m165) a macro on our buttons: its look, cooldown and GCD
# the user, 2026-11-16: "when using show tooltip on a macro it doesn't actually show the GCD when using it"
LOOKS = r'''
MSPELL, MITEM = {}, {}
function GetMacroSpell(i) local n = GetMacroInfo(i); local v = n and MSPELL[n]; if type(v) == "function" then return v() end; return v end
function GetMacroItem(i) local n = GetMacroInfo(i); local x = n and MITEM[n]; if x then return x[1], x[2] end end
CDS = {}
C_Spell.GetSpellCooldown = function(id) return CDS[id] or { isActive = false, isOnGCD = false, startTime = 0, duration = 0 } end
MODSHIFT = false
'''
LOOK_MACROS = [("Bolt", "#showtooltip\n/cast Lightning Bolt"), ("Shift", "#showtooltip\n/cast [mod:shift] Healing Wave; Lightning Bolt"),
               ("Pot", "#showtooltip\n/use Minor Healing Potion"), ("Own", "/cast Lightning Bolt")]


def padmap(L):
    L.execute('WowCompanion.padMap.emit(true); advance(0.5)')
    return (last(L, 'padmap').get('m') or {})


def slot(m, st, pos):
    for s in m.get('sets') or []:
        if s.get('set') == st:
            for x in s.get('slots') or []:
                if x.get('pos') == pos:
                    return x
    return {}


L = opened(LOOK_MACROS, [], pre=LOOKS)
L.execute('MSPELL.Bolt = 403; MSPELL.Shift = function() return MODSHIFT and 331 or 403 end; MSPELL.Own = 403; '
          'MITEM.Pot = { "Minor Healing Potion", "|cffffffff|Hitem:118::::::::|h[Minor Healing Potion]|h|r" }; '
          'GENM[GetMacroIndexByName("Own")].icon = 136095')
L.execute('MLOG = {}')
ok = [L.eval('WowCompanion.padMap.putFight(1, 1, %d, "macro", "%s")' % (p, n)) for p, n in ((5, "Bolt"), (2, "Shift"), (6, "Pot"), (8, "Own"))]
check(all(ok), "s. four macros onto the In combat layer: X, D-pad Up, Y, A", ok)
m = padmap(L)
cf = slot(m, 1, 5).get('cf') or {}
check(cf.get('t') == 'macro' and cf.get('n') == 'Bolt' and cf.get('i') == 130403 and cf.get('ms') == 403 and 'id' not in cf,
      "s. X's #showtooltip macro: the picture of the spell it casts (GetMacroSpell), `ms` = its id, never `id`", cf)
own = slot(m, 1, 8).get('cf') or {}
check(own.get('i') == 136095 and own.get('ms') == 403, "s. a macro with an icon of its own keeps it (as the game's button), `ms` still", own)
pot = slot(m, 1, 6).get('cf') or {}
check(pot.get('i') == 200118 and pot.get('mt') == 118 and 'ms' not in pot, "s. an item's macro: its item (GetMacroItem): the item's picture, `mt`", pot)
up = slot(m, 1, 2)
check(up.get('kind') == 'combat' and (up.get('c') or {}).get('ms') == 403 and (up.get('c') or {}).get('i') == 130403,
      "s. D-pad Up's conditional macro: what it casts now (no Shift: Lightning Bolt)", up.get('c'))
fs = L.eval('(function() local t = {}; for _, id in ipairs(WowCompanion.padMapFightSpells()) do t[#t + 1] = id end; table.sort(t); return table.concat(t, ",") end)()')
check(fs == "403", "s. their state by spell id (hudS.fs): the spell the macros cast now", fs)
# the GCD on the facecd road, inside SPELL_UPDATE_COOLDOWN (out of combat: the plain length, learned)
n0 = nsent(L)
L.execute('CDS[403] = { isActive = true, isOnGCD = true, startTime = NOW, duration = 1.5 }; fire("SPELL_UPDATE_COOLDOWN")')
cd = [d for d in docs(L, n0, 'facecd') if d.get('k')]
k = cd[-1]['k'] if cd else {}
check(all(k.get(p, {}).get('g') is True and k.get(p, {}).get('d') == 1.5 for p in ('2', '5', '8')) and '6' not in k,
      "s. a cast: X, D-pad Up and A (macros of Lightning Bolt) show the GCD (facecd g, 1.5 s); the item's macro none", k)
L.execute('advance(2); CDS[403] = { isActive = true, isOnGCD = false, startTime = NOW, duration = 6 }; fire("SPELL_UPDATE_COOLDOWN")')
k = (docs(L, n0, 'facecd') or [{}])[-1].get('k') or {}
check(k.get('5', {}).get('d') == 6 and not k.get('5', {}).get('g') and not k.get('5', {}).get('e'),
      "s. its own cooldown (6 s, plain): the real length, learned", k.get('5'))
check(L.eval('WowCompanionSpellsDB.faceCd["Lightning Bolt"]') == 6, "s. ... learned by the spell's name (the fight faces' store)")
# in a fight: the values secret -> the learned length, estimated
L.execute('advance(7); fire("SPELL_UPDATE_COOLDOWN"); fire("PLAYER_REGEN_DISABLED"); COMBAT = true; advance(0.3)')
n1 = nsent(L)
L.execute('CDS[403] = { isActive = true, isOnGCD = false, startTime = SECRETV, duration = SECRETV }; fire("SPELL_UPDATE_COOLDOWN")')
k = (docs(L, n1, 'facecd') or [{}])[-1].get('k') or {}
check(k.get('5', {}).get('d') == 6 and k.get('5', {}).get('e') is True, "s. in a fight (secret values): the learned 6 s, estimated", k.get('5'))
# Shift held: D-pad Up's macro casts Healing Wave now: its look follows, Lightning Bolt's cooldown leaves that button
n2 = nsent(L)
L.execute('MODSHIFT = true; fire("MODIFIER_STATE_CHANGED", "LSHIFT", 1); advance(1)')
pm = [d for d in docs(L, n2, 'padmap')]
c2 = (slot(pm[-1]['m'], 1, 2).get('c') or {}) if pm else {}
k = (docs(L, n2, 'facecd') or [{}])[-1].get('k') if docs(L, n2, 'facecd') else None
check(c2.get('ms') == 331 and c2.get('i') == 130331, "s. Shift: the padmap again, D-pad Up's picture = Healing Wave (what it casts now)", c2)
check(k is not None and '2' not in k and '5' in k, "s. ... Lightning Bolt's cooldown off D-pad Up (X keeps its own)", k)
fs = L.eval('(function() local t = {}; for _, id in ipairs(WowCompanion.padMapFightSpells()) do t[#t + 1] = id end; table.sort(t); return table.concat(t, ",") end)()')
check(fs == "331,403", "s. ... and the state by id has Healing Wave too", fs)
# a secret read of the macro's spell keeps its last plain look (never a guess, never a compare of the secret)
L.execute('MSPELL.Bolt = SECRETV; fire("PLAYER_TARGET_CHANGED"); advance(1)')
cf = slot(padmap(L), 1, 5).get('cf') or {}
check(cf.get('ms') == 403 and cf.get('i') == 130403, "s. GetMacroSpell secret: the last plain look kept", cf)
L.execute('COMBAT = false; fire("PLAYER_REGEN_ENABLED"); advance(1)')
check(mlog(L) == [] and clean(L) == {}, "s. reads only: no macro written, no lockdown breach, no spied call", (mlog(L), clean(L)))
# L3 (a stick): its look in the Controller room's document
L = opened(LOOK_MACROS, [], pre=LOOKS)
L.execute('MSPELL.Bolt = 403')
L.eval('WowCompanion.padMap.putMacro(1, 1, 9, "Bolt")')
st = [x for x in (padmap(L).get('sticks') or []) if x.get('pos') == 9 and x.get('set') == 1]
check(st and st[0].get('kind') == 'macro' and st[0].get('i') == 130403 and st[0].get('ms') == 403, "s. L3's macro: the spell's picture", st)
# m166 (review): a /castsequence steps on the player's own cast landing; [pet] on the pet; [equipped] / a worn item's /use on a gear change
L = opened(LOOK_MACROS, [], pre=LOOKS)
L.execute('SEQ = 403; MSPELL.Bolt = function() return SEQ end')
L.eval('WowCompanion.padMap.putFight(1, 1, 5, "macro", "Bolt")')
check((slot(padmap(L), 1, 5).get('cf') or {}).get('ms') == 403, "s. m166: a castsequence macro on X: its first spell")
for ev, args, to in (("UNIT_SPELLCAST_SUCCEEDED", '"player", "Cast-1", 403', 331), ("UNIT_PET", '"player"', 8042),
                     ("PLAYER_EQUIPMENT_CHANGED", '13, false', 403)):
    n4 = nsent(L)
    L.execute('SEQ = %d; fire("%s", %s); advance(1)' % (to, ev, args))
    pm = docs(L, n4, 'padmap')
    c4 = (slot(pm[-1]['m'], 1, 5).get('cf') or {}) if pm else {}
    check(c4.get('ms') == to, "s. m166: %s re-reads the look: the padmap again, X = what the macro casts now" % ev, (ev, c4))
check(mlog(L) == [] and clean(L) == {}, "s. m166: reads only", (mlog(L), clean(L)))
# none of ours holds a macro: the watcher sends nothing
L = opened(LOOK_MACROS, [], pre=LOOKS)
n3 = nsent(L)
L.execute('fire("MODIFIER_STATE_CHANGED", "LSHIFT", 1); fire("PLAYER_TARGET_CHANGED"); advance(1)')
check(docs(L, n3, 'padmap') == [] and docs(L, n3, 'facecd') == [], "s. no macro on our buttons: nothing sent on those events",
      (len(docs(L, n3, 'padmap')), len(docs(L, n3, 'facecd'))))
check('"the macro room needs some work- for starters when using show tooltip on a macro it doesn\'t actually show the GCD when using it"'
      in SRC["padmap"] and int(SRC["padmap"].split('local PV = "m', 1)[1].split('"', 1)[0]) >= 172, "s. PadMap m165, its header note quotes the user")

# ================================================================ t. (s14) THE ONE GRAMMAR: parse -> compose, the text-line fallback
L = opened(GEN, CHAR)
PC_ = L.eval('function(t) local m = WowCompanion.macroRoom.parse(t); return m.kind, WowCompanion.macroRoom.compose(m) end')
SAME = ["/cast Lightning Bolt", "/cast [@focus,harm,nodead][] Earth Shock", "/cast [mod:shift] Healing Wave; Lightning Bolt",
        "/target [noexists][dead] softenemy", "/startattack [harm,nodead]", "#showtooltip", "#showtooltip Lightning Bolt",
        "#show [mod:shift] Healing Wave; Lightning Bolt", "/castsequence reset=target/combat Earth Shock, Lightning Bolt",
        "/use [combat] 13", "/use item:6948", "/cast !Lightning Shield", "/cast Healing Wave(Rank 2)", "/cast [target=focus,exists] Healing Wave",
        "/cast [modifier:shift] Healing Wave", "/cast [spec:2,btn:2] Healing Wave", "/cast [Harm] Lightning Bolt", "/cast [nomod] Lightning Bolt",
        "/CAST Lightning Bolt", "/spell Lightning Bolt", "/tar softenemy", "/stopcasting", "/petattack [@focus]", "/cancelaura Lightning Shield",
        "/equipslot 16 Worn Shortsword", "/cast [@mouseover,help,nodead][@player] Healing Wave", "/focus [@mouseover,exists]"]
bad = [(t, PC_(t)) for t in SAME if PC_(t) != ('cmd', t)]
check(bad == [], "t. %d builder lines round-trip exactly (cases, units, target=, aliases, raw conditions, ranks, item:<id>, sequences)" % len(SAME), bad)
k, c = PC_("/cast [@focus, harm] Earth Shock")
check(k == 'cmd' and c == "/cast [@focus,harm] Earth Shock", "t. spaces by [ ] , ; : = do not count (the game reads them alike): built, written tidy", c)
TEXT = ["/say hello", "/run print(1)", "/dismount", "/click MyButton", "/cast [harm][[x]] y", "/cast [harm X", "/cast [harm,@focus] X",
        "/cast [help] Heal; [] Heal", "/p Pull in 3", "hello there", "/cast [harm;x] y"]
bad = [(t, PC_(t)) for t in TEXT if PC_(t) != ('text', t)]
check(bad == [], "t. text lines kept exactly as written: chat, /run, /dismount and /click (no builder actions), nested or unclosed "
      "brackets, a unit after a condition, two clauses one merge would change", bad)
check(PC_("/cast X|cff") == ('lock', "/cast X|cff") and PC_("") == ('empty', ""), "t. a '|' line is locked; an empty one is empty")
M = L.eval('function(t) return WowCompanion.encode(WowCompanion.macroRoom.parse(t)) end')
m = json.loads(M("/cast [@focus,harm,nodead][] Earth Shock"))
cs = m.get('cs') or []
check(len(cs) == 2 and cs[0].get('u') == 'focus' and cs[0].get('uf') == '@' and [x.get('k') for x in cs[0]['cn']] == ['harm', 'dead']
      and cs[0]['cn'][1].get('no') is True and cs[1].get('bare') is False and not cs[1].get('cn') and cs[0]['a'] == cs[1]['a'] == 'Earth Shock',
      "t. the model: case 1 @focus [harm, no dead], case 2 a plain [] (written), one argument", m)
m = json.loads(M("/cast [modifier:shift,spec:2] Healing Wave"))
cn = (m.get('cs') or [{}])[0].get('cn') or []
check(cn[0].get('k') == 'mod:shift' and cn[0].get('nm') == 'modifier' and cn[1] == {'raw': 'spec:2'},
      "t. a known condition under the game's other name keeps it ('modifier'); an unknown one is kept raw", cn)
check(clean(L) == {}, "t. clean (the grammar reads nothing, writes nothing)", clean(L))

# the tooltip line in the builder: its spell may be left out (no 'pick'); a spell picked shows that spell's tooltip
L = opened([("Alpha", "#showtooltip\n/cast Lightning Bolt")], CHAR)
press(L, "PAD1"); press(L, "PADDDOWN"); press(L, "PAD1")
check(bdoc(L).get('c') == 'tip' and not bdoc(L).get('h'), "t. '#showtooltip' in the builder: the Tooltip line, nothing missing", bdoc(L))
chip(L, 'arg'); pick_row(L, lambda r: r.get('n') == 'Lightning Shield')
L.execute('MLOG = {}')
sel(L)
check(mlog(L) == ['edit 1 nil #showtooltip Lightning Shield\n/cast Lightning Bolt'], "t. '#showtooltip Lightning Shield': ONE write", mlog(L))
check(clean(L) == {}, "t. clean (the tooltip line)", clean(L))

# ================================================================ u. (s14) commands registered on this client only, and by class
def cmd_list(L):
    press(L, "PAD1"); tap(L, 21); tap(L, "a")
    out = [r.get('k') for r in popd(L).get('rows') or []]
    press(L, "PAD2"); press(L, "PAD2"); press(L, "PAD2")
    L.execute('MRx.st.draft = nil')
    return out


L = opened([("Alpha", "")], CHAR, macros='SLASH_STOPMACRO1 = nil; SECURE["/stopmacro"] = nil; SECURE["/clearfocus"] = nil; '
           'SlashCmdList.CLEARFOCUS = function() end; SECURE["/targetenemy"] = nil')
ks = cmd_list(L)
check('stopmacro' not in ks and 'targetenemy' not in ks, "u. not registered here (no secure command, no slash list entry): not offered", ks)
check('clearfocus' in ks, "u. a slash word with its SlashCmdList entry (not secure): registered, offered", ks)
check('dismount' not in ks and 'click' not in ks, "u. Dismount and Click are no builder actions", ks)
L = opened([("Alpha", "")], CHAR, macros='CLASS = "HUNTER"')
ks = cmd_list(L)
check([k for k in ks if k.startswith('pet')] == ['petattack', 'petfollow', 'petstay', 'petmoveto', 'petpassive', 'petdefensive',
      'petdefensiveassist', 'petaggressive', 'petassist', 'petdismiss'] and 'cancelform' not in ks,
      "u. a Hunter: the pet orders registered here (in the game's order); no Leave form", ks)
L = opened([("Alpha", "")], CHAR, macros='CLASS = "DRUID"; NFORMS = 2')
check('cancelform' in cmd_list(L), "u. a Druid with forms: Leave form")
L = opened([("Alpha", "")], CHAR, macros='CLASS = "WARRIOR"; NFORMS = 3')
check('cancelform' not in cmd_list(L), "u. a Warrior (stances): no Leave form")
check(clean(L) == {}, "u. clean", clean(L))

# ================================================================ v. (s14 D6) the companion's own macros: a lock, view only
DSM = [("DS Attack 12", "#showtooltip\n/target [noexists][dead] softenemy\n/startattack [harm,nodead]\n/click [harm,nodead] WowCompanionFaceRearm\n/cast Lightning Bolt"),
       ("DS Combat 5", "#showtooltip\n/cast [combat] Lightning Bolt; Healing Wave"), ("DS Attack 9", "/wave")]
L = opened([], DSM)
tap(L, "tc")
d = mdoc(L)
check([c.get('lk') for c in d['cells'][:3]] == [True, None, True], "v. the grid: DS Attack / DS Combat (their exact shape) carry the lock; "
      "a 'DS Attack' name with the player's own body does not", d['cells'])
check(d.get('mac', {}).get('lk') is True, "v. the preview says so too")
L.execute('MLOG = {}')
press(L, "PAD1")
check(mdoc(L).get('mode') == 'grid' and mdoc(L).get('note') == 'locked' and mlog(L) == [], "v. A: refused 'locked' (view only), nothing opened")
press(L, "PAD3")
check(mdoc(L).get('mode') == 'grid' and mdoc(L).get('note') == 'locked', "v. X (Bind): refused 'locked' too")
tap(L, 1); press(L, "PAD1")
check(mdoc(L).get('mode') == 'edit', "v. the player's own 'DS Attack 9': its editor opens")
check(clean(L) == {}, "v. clean", clean(L))

# ================================================================ w. (s14 D4) Select is the room's: Done
L = opened(GEN, CHAR)
ex = L.eval('(function() local e = __IN.G.spec and __IN.G.spec.extra; local t = {}; for k in pairs(e or {}) do t[#t + 1] = k end; table.sort(t); return table.concat(t, ",") end)()')
check(ex == 'PADBACK', "w. the take asks the capture for Select (capture v4 extra) and nothing else beyond the eight", ex)
L.execute('MLOG = {}')
press(L, "PADBACK")
check(mdoc(L).get('mode') == 'grid' and mlog(L) == [], "w. Select at the grid: nothing")
L.execute(BIND_PRE)
press(L, "PADDRIGHT"); press(L, "PAD3")
ex = L.eval('(function() local e = __IN.G.spec and __IN.G.spec.extra; local t = {}; for k in pairs(e or {}) do t[#t + 1] = k end; table.sort(t); return table.concat(t, ",") end)()')
check(ex == 'PADBACK,PADLSTICK,PADRSTICK', "w. the hosted Bind box's own extra keys asked WITH the room's Select", ex)
press(L, "PAD2")
L.execute('advance(1)')
ex = L.eval('(function() local e = __IN.G.spec and __IN.G.spec.extra; local t = {}; for k in pairs(e or {}) do t[#t + 1] = k end; table.sort(t); return table.concat(t, ",") end)()')
check(mdoc(L).get('mode') == 'grid' and ex == 'PADBACK', "w. the box gone (B): its L3 / R3 given back, the room keeps Select", (mdoc(L).get('mode'), ex))
check(clean(L) == {}, "w. clean", clean(L))

# ================================================================ x. (s14) Move: Y, Up / Down, A drops (ONE write), B writes nothing
L = opened([("Alpha", "/wave\n/dance\n/cheer")], CHAR)
press(L, "PAD1"); press(L, "PADDDOWN")
press(L, "PAD4")
d = mdoc(L)
check(d.get('mode') == 'move' and d.get('mv') == {'from': 1, 'at': 1}, "x. Y on line 1: Move", d.get('mv'))
press(L, "PADDDOWN")
d = mdoc(L)
check(d.get('mv') == {'from': 1, 'at': 2} and d['mac']['lines'] == ['/dance', '/wave', '/cheer'], "x. Down: shown one lower", d['mac']['lines'])
L.execute('MLOG = {}')
L.execute('ALLOW = true'); press(L, "PAD1"); L.execute('ALLOW = false')
check(mlog(L) == ['edit 1 nil /dance\n/wave\n/cheer'] and mdoc(L).get('mode') == 'edit' and mdoc(L).get('ef') == 'l2',
      "x. A drops it: ONE write, the glow on it where it went", (mlog(L), mdoc(L).get('ef')))
press(L, "PAD4"); press(L, "PADDDOWN")
L.execute('MLOG = {}')
press(L, "PAD2")
check(mlog(L) == [] and mdoc(L).get('mode') == 'edit' and L.eval('mbody("Alpha")') == '/dance\n/wave\n/cheer', "x. B: nothing written")
press(L, "PAD4")
tap(L, 2); tap(L, "a", allow=True)
check(L.eval('mbody("Alpha")') == '/wave\n/dance\n/cheer', "x. a finger: tap where it goes (select + A)", L.eval('mbody("Alpha")'))
press(L, "PAD4"); press(L, "PAD1")
check(mdoc(L).get('mode') == 'edit', "x. dropped where it was: nothing to write")
check(clean(L) == {}, "x. clean", clean(L))

# ================================================================ y. (s14 D5) On: the soft friend with the game's soft-friend targeting on
L = opened([("Alpha", "/cast Healing Wave")], CHAR, macros='SOFTFRIEND = "2"')
press(L, "PAD1"); press(L, "PADDDOWN"); press(L, "PAD1")
chip(L, 'c1u')
units = [r.get('u') for r in popd(L).get('rows')]
check(units[:3] == ['', 'softenemy', 'softfriend'] and units[-2:] == ['mouseover', 'name'], "y. the CVar on: Soft friend after Soft enemy; "
      "Mouseover then Name last", units)
press(L, "PAD2"); press(L, "PAD3")
check([r.get('o') for r in popd(L).get('rows')][0] == 'sf1', "y. + Case for a heal: Soft friend first leads", popd(L).get('rows'))
pick_row(L, lambda r: r.get('o') == 'sf1')
check(bdoc(L).get('t') == '/cast [@softfriend,help,nodead][] Healing Wave', "y. '[@softfriend,help,nodead]' tried first, the old step its else",
      bdoc(L).get('t'))
check(L.eval('CVARREADS') > 0 and clean(L) == {}, "y. the CVar READ (never written: SetCVar is a spied call)", clean(L))
L = opened([("Alpha", "/cast Lightning Bolt")], CHAR, macros='CLASS = "WARLOCK"')
press(L, "PAD1"); press(L, "PADDDOWN"); press(L, "PAD1"); press(L, "PAD3")
check([r.get('o') for r in popd(L).get('rows')] == ['fo1', 'se1', 'tg', 'sp', 'bl'], "y. + Case for a harmful spell: Focus first, Soft enemy "
      "first, Else on target ...", popd(L).get('rows'))
press(L, "PAD2")
chip(L, 'c1u')
check('pet' in [r.get('u') for r in popd(L).get('rows')], "y. a Warlock: Pet among the units")

# ================================================================ z. (s14) ranks: "Top" by default, the exact subtext written
RANKBOOK = r"""
RANKS = { { id = 331, n = "Healing Wave", sub = "Rango 1" }, { id = 332, n = "Healing Wave", sub = "Rango 2" },
          { id = 547, n = "Healing Wave", sub = "Rango 3" }, { id = 403, n = "Lightning Bolt", sub = "Rango 1" } }
for _, r in ipairs(RANKS) do SUBTEXT[r.id] = r.sub end
C_SpellBook = setmetatable({ GetNumSpellBookSkillLines = function() return 1 end,
    GetSpellBookSkillLineInfo = function(k) if k == 1 then return { itemIndexOffset = 0, numSpellBookItems = #RANKS } end end,
    GetSpellBookItemInfo = function(slot, bank) local r = RANKS[slot]; if r and bank == 0 then return { name = r.n, spellID = r.id } end end },
    { __index = function() return STUB end })
"""
L = opened([("Alpha", "/cast Healing Wave\n/cast Lightning Bolt")], CHAR, macros=RANKBOOK)
press(L, "PAD1"); press(L, "PADDDOWN"); press(L, "PAD1")
b = bdoc(L)
check('rank' in b['rows'][0] and (b.get('a') or {}).get('rk') == [0, 3], "z. three ranks: the Rank chip, at the top (0 of 3)", b.get('a'))
chip(L, 'rank')
check([(r.get('r'), r.get('on')) for r in popd(L).get('rows')] == [(0, True), (1, None), (2, None), (3, None)], "z. its pop-out: Top (on), 1-3")
pick_row(L, lambda r: r.get('r') == 2)
check(bdoc(L).get('t') == '/cast Healing Wave(Rango 2)' and bdoc(L)['a'].get('rk') == [2, 3],
      "z. rank 2: written with ITS OWN subtext (an es client's 'Rango 2', never an English 'Rank')", bdoc(L).get('t'))
tap(L, bdoc(L)['ch']['rank']); press(L, "PAD4")
check(bdoc(L).get('t') == '/cast Healing Wave', "z. Y on the rank: the top again (no parentheses)")
press(L, "PAD2"); press(L, "PADDDOWN"); press(L, "PAD1")
check('rank' not in bdoc(L)['rows'][0], "z. one rank only (Lightning Bolt here): no Rank chip", bdoc(L)['rows'][0])
press(L, "PAD2")
L.execute('MRx.st.draft = nil')
check(clean(L) == {}, "z. clean", clean(L))

# ================================================================ aa. (s14) the icon: Auto and the macro's spells' pictures, ONE write
L = opened([("Alpha", "/cast Lightning Bolt\n/cast Healing Wave")], CHAR)
press(L, "PAD1"); press(L, "PADDRIGHT"); press(L, "PAD1")
rows = popd(L).get('rows') or []
check(popd(L).get('k') == 'icon' and [(r.get('i'), r.get('auto'), r.get('on')) for r in rows] == [(134400, True, True), (130403, None, None), (130331, None, None)],
      "aa. A on the icon: Auto (now, the question mark) and the pictures of its spells", rows)
L.execute('MLOG = {}')
pick_row(L, lambda r: r.get('i') == 130331, allow=True)
check(mlog(L) == ['edit 1 nil nil'] and L.eval('GENM[1].icon') == 130331 and not popd(L), "aa. Healing Wave's picture: ONE write (EditMacro(i, nil, icon))",
      (mlog(L), L.eval('GENM[1].icon')))
check(clean(L) == {}, "aa. clean", clean(L))

# ================================================================ ab. (s14) the search, the General tab, slots, sequences
L = opened([("Alpha", "")], CHAR, macros='GENBOOK = { 6603 }; WORN[13] = 9001')
press(L, "PAD1"); tap(L, 21); tap(L, "a")
pick_row(L, lambda r: r.get('k') == 'cast')
tap(L, 5); tap(L, "a")
check(mdoc(L).get('kbd', {}).get('name') == 'macsearch', "ab. the search box: A = the keyboard")
L.execute('MLOG = {}')
kbd_done(L, "shield", allow=False)
check([r.get('n') for r in popd(L).get('rows')] == ['Lightning Shield'] and popd(L).get('q') == 'shield' and mlog(L) == []
      and mdoc(L).get('mode') == 'build', "ab. 'shield': the list filtered, nothing written, the pop-out still out", popd(L))
tabs = popd(L).get('tabs')
tap(L, tabs.index('gen')); tap(L, "a")
check(popd(L).get('tab') == 'gen' and [r.get('n') for r in popd(L).get('rows')] == ['Attack'], "ab. the General tab: Attack", popd(L).get('rows'))
pick_row(L, lambda r: r.get('n') == 'Attack')
check(bdoc(L).get('c') == 'startattack' and bdoc(L).get('t') == '/startattack', "ab. General's Attack: the game's own /startattack", bdoc(L).get('t'))
chip(L, 'cmd'); pick_row(L, lambda r: r.get('k') == 'use')
tabs = popd(L).get('tabs')
check(tabs == ['it', 'sl', 'sp'], "ab. Use: Items, Slots, Spells", tabs)
tap(L, tabs.index('sl')); tap(L, "a")
pick_row(L, lambda r: r.get('sl') == 13)
check(bdoc(L).get('t') == '/use 13' and (bdoc(L).get('a') or {}).get('n') == 'Arena Grand Master', "ab. Slots: '/use 13', shown by the worn item's name",
      bdoc(L).get('a'))
chip(L, 'cmd'); pick_row(L, lambda r: r.get('k') == 'seq')
check(popd(L).get('k') == 'seq', "ab. Sequence: its list")
for n in ('Lightning Bolt', 'Lightning Shield'):
    pick_row(L, lambda r: r.get('add'))
    pick_row(L, lambda r, n=n: r.get('n') == n)
check(popd(L).get('k') == 'seq' and [r.get('n') for r in popd(L).get('rows')] == ['Lightning Bolt', 'Lightning Shield', None],
      "ab. two spells added (each pick back on the list), then '+'", popd(L).get('rows'))
tap(L, 0); press(L, "PAD4")
check([r.get('n') for r in popd(L).get('rows')] == ['Lightning Shield', None], "ab. Y: an entry out")
pick_row(L, lambda r: r.get('add')); pick_row(L, lambda r: r.get('n') == 'Lightning Bolt')
press(L, "PAD2")
check(bdoc(L).get('t') == '/castsequence Lightning Shield, Lightning Bolt' and 'reset' in bdoc(L)['rows'][0], "ab. the sequence's line, its Reset chip",
      bdoc(L).get('t'))
chip(L, 'reset')
for o in ('target', 'combat', '6', '12'):
    pick_row(L, lambda r, o=o: r.get('o') == o)
check(bdoc(L).get('t') == '/castsequence reset=target/combat/12 Lightning Shield, Lightning Bolt' and popd(L).get('k') == 'reset',
      "ab. Reset toggles (one time at a time), the pop-out stays", bdoc(L).get('t'))
press(L, "PAD2")
L.execute('MLOG = {}')
sel(L)
check(mlog(L) == ['edit 1 nil /castsequence reset=target/combat/12 Lightning Shield, Lightning Bolt'], "ab. Done: ONE write", mlog(L))
check(clean(L) == {}, "ab. clean", clean(L))
# equip slot: the slot, then the item
L = opened([("Alpha", "")], CHAR, macros='WORN[16] = 9002')
press(L, "PAD1"); tap(L, 21); tap(L, "a")
pick_row(L, lambda r: r.get('k') == 'equipslot')
check(popd(L).get('tabs') == ['sl', 'it'], "ab. Equip slot: Slots, then Items", popd(L).get('tabs'))
pick_row(L, lambda r: r.get('sl') == 16)
chip(L, 'arg'); tap(L, 1); tap(L, "a")
pick_row(L, lambda r: r.get('n') == 'Worn Shortsword')
check(bdoc(L).get('t') == '/equipslot 16 Worn Shortsword', "ab. '/equipslot 16 Worn Shortsword'", bdoc(L).get('t'))

# /target's own unit: the On list without "the target" (it needs someone), a typed name as the argument
L = opened([("Alpha", "")], CHAR)
press(L, "PAD1"); tap(L, 21); tap(L, "a")
pick_row(L, lambda r: r.get('k') == 'target')
units = [r.get('u') for r in popd(L).get('rows')]
check(popd(L).get('k') == 'on' and units[0] == 'softenemy' and '' not in units, "ab. Target: its unit from the On list (not 'Target': it needs one)", units)
pick_row(L, lambda r: r.get('u') == 'softenemy')
check(bdoc(L).get('t') == '/target softenemy', "ab. '/target softenemy' (the game's own unit word)", bdoc(L).get('t'))
chip(L, 'arg'); pick_row(L, lambda r: r.get('u') == 'name')
kbd_done(L, "Bob", allow=False)
check(bdoc(L).get('t') == '/target Bob' and mlog(L) == [], "ab. Name...: '/target Bob' (the command's argument, not a case's unit), nothing written",
      bdoc(L).get('t'))
press(L, "PAD2"); L.execute('MRx.st.draft = nil')
check(clean(L) == {}, "ab. clean (/target)", clean(L))

# ================================================================ ac. (s14) the document's size with the builder and the strips
L = opened([("Alpha", "\n".join("/cast [@focus,harm,nodead][@softenemy,harm][] Lightning Bolt" if i % 2 else "/cast [mod:shift] Healing Wave; Lightning Shield"
                                  for i in range(4)))], CHAR)
press(L, "PAD1")
L.execute('WowCompanion.macroRoom.emit()')
d = json.loads(L.eval('SENT[#SENT]'))['m']; d.pop('pad', None)
ed = len(json.dumps(d, separators=(',', ':')))
press(L, "PADDDOWN"); press(L, "PAD1"); chip(L, 'arg')
L.execute('WowCompanion.macroRoom.emit()')
d = json.loads(L.eval('SENT[#SENT]'))['m']; d.pop('pad', None)
bd = len(json.dumps(d, separators=(',', ':')))
check(ed < 2600 and bd < 2600, "ac. the editor with its strips (%d bytes) and the builder with a pop-out (%d bytes): about 3 Sink lines" % (ed, bd), (ed, bd))
press(L, "PAD2"); press(L, "PAD2")
check(clean(L) == {}, "ac. clean", clean(L))

# ================================================================ ad. (s14 review) spaces the game reads alike, a fight's refusal, typed units
check('s14 REVIEW' in SRC["spells"].split('\n', 40)[35], "ad. the s14 review's header note (the same tag, as s10's review)")
# a stored line with its own spacing: opened and finished unchanged = NOTHING written, no draft, no Revert
L = opened([("Alpha", "/cast [help] Healing Wave ;Lightning Bolt\n/cast  [mod:shift]Lightning Shield")], CHAR)
press(L, "PAD1"); press(L, "PADDDOWN")
d = mdoc(L)
check([x.get('k') for x in d.get('ls') or []] == ['cmd', 'cmd'], "ad. both oddly spaced lines are builder lines (MR.norm)", d.get('ls'))
press(L, "PAD1")
b = bdoc(L)
check(b.get('t') == '/cast [help] Healing Wave; Lightning Bolt' and not b.get('dr'),
      "ad. opened: the composed text, NOT a draft (the spaces are no change)", (b.get('t'), b.get('dr')))
L.execute('MLOG = {}')
sel(L)
check(mlog(L) == [] and mdoc(L).get('mode') == 'edit' and L.eval('mbody("Alpha")').startswith('/cast [help] Healing Wave ;Lightning Bolt'),
      "ad. Done unchanged: NOTHING written, the stored spacing kept", mlog(L))
press(L, "PADDDOWN"); press(L, "PAD1")
check(not bdoc(L).get('dr'), "ad. the second line ('/cast  [mod:shift]Lightning Shield') too: not a draft", bdoc(L).get('t'))
more(L, 'rem')
check(mlog(L) == [] and bdoc(L).get('rem') is True, "ad. its Remove asks (a stored line, unchanged: Remove, not Revert)", bdoc(L))
press(L, "PAD2"); press(L, "PAD2")
# a real change still writes ONE line
press(L, "PADDUP"); press(L, "PAD1"); chip(L, 'keep')
check(bdoc(L).get('dr') is True, "ad. Keep on: a real change, a draft", bdoc(L).get('t'))
sel(L)
check(mlog(L) == ['edit 1 nil /cast [help] !Healing Wave; Lightning Bolt\n/cast  [mod:shift]Lightning Shield'],
      "ad. ... Done: ONE write, the line composed; the other line untouched", mlog(L))
# a fight: Done refused as "combat" BEFORE any fault (the design's 7), the draft kept; a preset's Done likewise
more(L, 'ins')
check(bdoc(L).get('h') == 'pick', "ad. More > Insert below (s16; was X): a new line, no spell yet ('pick')", bdoc(L).get('h'))
press(L, "PAD2")
L.execute('MLOG = {}; ' + COMBAT_ON)
sel(L, allow=False)
check(mdoc(L).get('note') == 'combat' and mdoc(L).get('mode') == 'build' and mlog(L) == [],
      "ad. Done in a fight with a missing spell: refused as 'combat' (not 'pick'), the draft kept", (mdoc(L).get('note'), mlog(L)))
L.execute(COMBAT_OFF)
sel(L, allow=False)
check(mdoc(L).get('note') == 'pick' and bdoc(L).get('cur') == 'arg' and mlog(L) == [],
      "ad. ... out of the fight: 'pick', the cursor on the spell chip", (mdoc(L).get('note'), bdoc(L).get('cur')))
press(L, "PAD2"); press(L, "PAD2"); press(L, "PAD2")
check(clean(L) == {}, "ad. clean", clean(L))
L = opened([("Alpha", "")], CHAR, macros='BOOK = { 403, 8042 }')
press(L, "PAD1")
tk = [t.get('k') for t in mdoc(L).get('tiles') or []]
tap(L, 2 + tk.index('seq')); tap(L, "a")
L.execute('MRx.st.ps.picks[2] = ""')
L.execute('MLOG = {}; ' + COMBAT_ON)
sel(L, allow=False)
check(mdoc(L).get('note') == 'combat' and mlog(L) == [], "ad. a preset's Done in a fight: 'combat' first", mdoc(L).get('note'))
L.execute(COMBAT_OFF)
sel(L, allow=False)
check(mdoc(L).get('note') == 'pick' and (mdoc(L).get('ps') or {}).get('cur') == 'p2' and mlog(L) == [],
      "ad. ... out of it: 'pick', the cursor on the EMPTY pick (p2), not the first", (mdoc(L).get('note'), (mdoc(L).get('ps') or {}).get('cur')))
check(clean(L) == {}, "ad. clean", clean(L))
# a unit typed by name with accents (an es / fr realm): written, and read back as a unit (not a text line)
L = opened([("Alpha", "/cast Healing Wave")], CHAR)
press(L, "PAD1"); press(L, "PADDDOWN"); press(L, "PAD1")
chip(L, 'c1u')
pick_row(L, lambda r: r.get('u') == 'name')
check(mdoc(L).get('kbd', {}).get('name') == 'macunit', "ad. On: Name... = the keyboard", mdoc(L).get('kbd'))
# the game's Lua reads %w as ASCII only (the "C" CTYPE); Python set this process's CTYPE to the user's (cp1252: high bytes are letters
# there), so the name goes in as UTF-8 bytes from Lua itself, under "C", as the game's client has it
CT = L.eval('os.setlocale(nil, "ctype")')
L.execute('os.setlocale("C", "ctype")')
NAME = r'"\195\141\195\177igo"'
L.eval('function(n) MRx.kbdDone(n) end')(L.eval(NAME))
check(L.eval('MRx.compose(MRx.st.draft.L) == "/cast [@" .. %s .. "] Healing Wave"' % NAME) is True,
      "ad. the typed accented name (UTF-8 bytes) is the case's unit")
L.execute('MLOG = {}')
sel(L)
check(L.eval('#MLOG == 1 and MLOG[1] == "edit 1 nil /cast [@" .. %s .. "] Healing Wave"' % NAME) is True and L.eval('MRx.st.mode') == 'edit',
      "ad. Done: written ONCE")
check(L.eval('MRx.parse(mbody("Alpha")).kind') == 'cmd' and L.eval('MRx.strip(MRx.parse(mbody("Alpha"))).cs[1].u == %s' % NAME) is True,
      "ad. ... and read back under the game's ASCII %w: a unit on a builder line (its strip), not a text line",
      L.eval('MRx.parse(mbody("Alpha")).kind'))
L.execute('GENM[1].body = "/cast [help] Healing Wave"; MRx.st.draft = nil')
L.eval('function(c) os.setlocale(c, "ctype") end')(CT)
# a STORED line's Chat line: its keyboard starts from the line's own text (an empty Done would remove it with no second A)
L.execute('MRx.st.mode = "edit"; MRx.st.efk = "l1"')
press(L, "PAD1")
chip(L, 'cmd')
pick_row(L, lambda r: r.get('k') == 'chat')
k = mdoc(L).get('kbd') or {}
check(k.get('name') == 'macline' and k.get('text') == '/cast [help] Healing Wave' and k.get('line') == 1,
      "ad. Chat line on a stored line: the keyboard holds that line's text (not empty)", k)
L.execute('MLOG = {}')
tap(L, "kx")
check(mlog(L) == [] and L.eval('mbody("Alpha")') == '/cast [help] Healing Wave', "ad. ... its keyboard closed: nothing written", mlog(L))
press(L, "PAD2"); press(L, "PAD2")
check(L.eval('MRx.parse("/cast [@Ä,harm|x] Bolt").kind') == 'lock' and L.eval('MRx.parse("/cast [@a b,nodead] X").kind') == 'cmd',
      "ad. ('|' still locks a line; a spaced name still reads)")
check(clean(L) == {}, "ad. clean", clean(L))

# ================================================================ af. (s16) X = Remove in the editor's line list (the user, 2026-11-18:
# "in the macro menu I need a button to delete a line, maybe just on the X glyph"; the pick "X = Remove"): X on the focused line ASKS
# (the builder's Remove's ask: Cancel [B] / Remove [A]), A removes that line only (ONE write), B cancels; never in a fight


def pa(L):
    """the ask's A (the capture's press, inside an allowed write)"""
    L.execute('ALLOW = true'); press(L, "PAD1"); L.execute('ALLOW = false')


L = opened([("Alpha", "/wave\n/dance\n/cheer\n/run print(|cff)")], CHAR)
press(L, "PAD1")
check('xr' not in mdoc(L) and mdoc(L).get('ef') == 'name', "af. on the name: no X Remove hint", mdoc(L).get('xr'))
press(L, "PADDDOWN"); press(L, "PADDDOWN")
check(mdoc(L).get('ef') == 'l2' and mdoc(L).get('xr') is True, "af. the glow on line 2: its X Remove hint (xr)", (mdoc(L).get('ef'), mdoc(L).get('xr')))
L.execute('MLOG = {}')
press(L, "PAD3")
d = mdoc(L)
check(d.get('rem') == 2 and mlog(L) == [] and d.get('mode') == 'edit' and not d.get('pop') and 'xr' not in d and not d.get('del')
      and L.eval('mbody("Alpha")') == "/wave\n/dance\n/cheer\n/run print(|cff)",
      "af. X on line 2: it ASKS (rem = 2), nothing written, no hint while it asks", (d.get('rem'), mlog(L)))
press(L, "PADDDOWN"); press(L, "PAD4"); press(L, "PAD3"); sel(L, allow=False)
d = mdoc(L)
check(d.get('rem') == 2 and d.get('ef') == 'l2' and d.get('mode') == 'edit' and not d.get('pop') and mlog(L) == [],
      "af. while it asks: the D-pad, Y, X and Select do nothing", (d.get('rem'), d.get('ef'), d.get('mode')))
press(L, "PAD2")
d = mdoc(L)
check('rem' not in d and d.get('mode') == 'edit' and d.get('ef') == 'l2' and mlog(L) == [] and d.get('xr') is True
      and L.eval('mbody("Alpha")') == "/wave\n/dance\n/cheer\n/run print(|cff)",
      "af. B: the ask cancelled, nothing written, still in the editor on line 2", (d.get('rem'), d.get('mode'), d.get('ef')))
press(L, "PAD3")
L.execute('advance(6.5)')
check('rem' not in mdoc(L) and mlog(L) == [], "af. not confirmed within 6 s: the ask goes")
press(L, "PAD3")
tap(L, 2)
check('rem' not in mdoc(L) and mdoc(L).get('ef') == 'l1' and mlog(L) == [], "af. a finger elsewhere cancels the ask (then selects there)",
      mdoc(L).get('ef'))
press(L, "PADDDOWN"); press(L, "PAD3"); pa(L)
d = mdoc(L)
check(mlog(L) == ['edit 1 nil /wave\n/cheer\n/run print(|cff)'] and d.get('mode') == 'edit' and 'rem' not in d,
      "af. the ask's A: that line ONLY removed, ONE write", mlog(L))
check(d.get('ef') == 'l2' and d['mac']['lines'][1] == '/cheer', "af. the glow stays in its place (the next line, /cheer)", d.get('ef'))
L.execute('MLOG = {}')
tap(L, "x")
check(mdoc(L).get('rem') == 2 and mlog(L) == [], "af. a finger on the hint (the row's x): the same ask", mdoc(L).get('rem'))
tap(L, "a", allow=True)
check(mlog(L) == ['edit 1 nil /wave\n/run print(|cff)'] and mdoc(L).get('ef') == 'l2', "af. ... the ask's Remove tapped: ONE write", mlog(L))
L.execute('MLOG = {}')
d = mdoc(L)
check('xr' not in d and d.get('ef') == 'l2', "af. a '|' line under the glow: no X Remove hint", d.get('xr'))
press(L, "PAD3")
check(mdoc(L).get('note') == 'locked' and 'rem' not in mdoc(L) and mlog(L) == [], "af. X on the '|' line: refused 'locked', no ask",
      mdoc(L).get('note'))
check(clean(L) == {}, "af. clean", clean(L))

# the last line of the list, then the macro's only line
L = opened([("Alpha", "/wave\n/dance")], CHAR)
press(L, "PAD1"); press(L, "PADDDOWN"); press(L, "PADDDOWN")
L.execute('MLOG = {}')
press(L, "PAD3"); pa(L)
check(mlog(L) == ['edit 1 nil /wave'] and mdoc(L).get('ef') == 'l1', "af. the last line removed: the glow on the new last line",
      (mlog(L), mdoc(L).get('ef')))
L.execute('MLOG = {}')
press(L, "PAD3"); pa(L)
d = mdoc(L)
check(mlog(L) == ['edit 1 nil '] and L.eval('mbody("Alpha")') == '' and d.get('mode') == 'edit' and d.get('ef') == 'add'
      and len(d.get('tiles') or []) > 0 and 'xr' not in d,
      "af. the only line removed: an empty macro (its preset tiles), the glow on + Line", (mlog(L), d.get('ef')))
check(clean(L) == {}, "af. clean (the last lines)", clean(L))

# an unsaved line (the builder's draft, kept by B) follows the lines
L = opened([("Alpha", "/cast Lightning Bolt\n/cast Healing Wave\n/cast Lightning Bolt\n/wave")], CHAR, macros='BOOK = { 403, 331 }')
press(L, "PAD1")
for _ in range(5):
    press(L, "PADDDOWN")
check(mdoc(L).get('ef') == 'add', "af. (+ Line)", mdoc(L).get('ef'))
press(L, "PAD1"); press(L, "PAD2"); press(L, "PAD2")
check(mdoc(L).get('dr') == {'at': 5} and mdoc(L).get('mode') == 'edit', "af. a new line's draft held (B twice: at 5)", mdoc(L).get('dr'))
for _ in range(4):
    press(L, "PADDUP")
check(mdoc(L).get('ef') == 'l1', "af. (line 1)", mdoc(L).get('ef'))
L.execute('MLOG = {}')
press(L, "PAD3"); pa(L)
check(mlog(L) == ['edit 1 nil /cast Healing Wave\n/cast Lightning Bolt\n/wave'] and mdoc(L).get('dr') == {'at': 4},
      "af. a line above a new line's draft removed: the draft still goes at the end (at 4)", mdoc(L).get('dr'))
for _ in range(3):
    press(L, "PADDDOWN")
press(L, "PAD1")
check(mdoc(L).get('mode') == 'build' and bdoc(L).get('at') == 4 and not bdoc(L).get('pop'), "af. ... + Line (Draft): that draft again",
      bdoc(L).get('at'))
L.execute('MRx.st.draft = nil'); press(L, "PAD2")
press(L, "PADDUP"); press(L, "PADDUP"); press(L, "PAD1"); chip(L, 'keep')
check(bdoc(L).get('ln') == 2 and bdoc(L).get('dr') is True, "af. line 2 changed in the builder (a draft)", bdoc(L).get('t'))
press(L, "PAD2")
check(mdoc(L).get('dr') == {'n': 2} and mdoc(L).get('ef') == 'l2', "af. B: line 2's draft held", mdoc(L).get('dr'))
press(L, "PADDUP")
L.execute('MLOG = {}')
press(L, "PAD3"); pa(L)
check(mlog(L) == ['edit 1 nil /cast Lightning Bolt\n/wave'] and mdoc(L).get('dr') == {'n': 1}, "af. the line above it removed: the draft follows (line 1)",
      mdoc(L).get('dr'))
press(L, "PAD1")
check(bdoc(L).get('ln') == 1 and bdoc(L).get('dr') is True, "af. ... A on it: the same draft, still changed", bdoc(L).get('t'))
press(L, "PAD2")
L.execute('MLOG = {}')
press(L, "PAD3"); pa(L)
check(mlog(L) == ['edit 1 nil /wave'] and 'dr' not in mdoc(L), "af. the draft's own line removed: the draft goes with it", mdoc(L).get('dr'))
check(clean(L) == {}, "af. clean (the drafts)", clean(L))

# never in a fight
L = opened([("Alpha", "/wave\n/dance")], CHAR)
press(L, "PAD1"); press(L, "PADDDOWN")
L.execute('MLOG = {}; ' + COMBAT_ON)
check('xr' not in mdoc(L) and mdoc(L).get('ef') == 'l1', "af. in a fight: no X Remove hint", mdoc(L).get('xr'))
press(L, "PAD3")
check(mdoc(L).get('note') == 'combat' and 'rem' not in mdoc(L) and mlog(L) == [], "af. X in a fight: refused 'combat', no ask",
      mdoc(L).get('note'))
L.execute(COMBAT_OFF)
press(L, "PAD3")
check(mdoc(L).get('rem') == 1, "af. out of it: X asks", mdoc(L).get('rem'))
L.execute(COMBAT_ON)
pa(L)
check(mdoc(L).get('note') == 'combat' and mlog(L) == [] and L.eval('mbody("Alpha")') == '/wave\n/dance' and 'rem' not in mdoc(L),
      "af. a fight started while it asked: the A refused 'combat', nothing written", (mdoc(L).get('note'), mlog(L)))
L.execute(COMBAT_OFF)
check(clean(L) == {}, "af. clean (the fight)", clean(L))

# ================================================================ the documents for the room's off-device render (MacrosShotsTest)
DUMP = os.environ.get("MACRO_DUMP")
if DUMP:
    os.makedirs(DUMP, exist_ok=True)

    def dump(L, name):
        L.execute('WowCompanion.macroRoom.emit()')
        d = json.loads(L.eval('SENT[#SENT]'))
        d['m'].pop('pad', None)
        open(os.path.join(DUMP, name + ".json"), "w", encoding="utf-8").write(json.dumps(d))

    SHOT = [("Bandage", "#showtooltip\n/use Linen Bandage"), ("Bolt", "#showtooltip\n/cast Lightning Bolt"),
            ("Focus Heal", "#showtooltip\n/cast [@focus,exists,nodead][] Healing Wave"), ("Mount", "/cast Ghost Wolf"),
            ("Party Ping", "/p Pull in 3"), ("Rez", "/cast Ancestral Spirit"), ("Shield", "/cast Lightning Shield"),
            ("Totems", "/castsequence reset=combat Searing Totem, Stoneskin Totem")]
    # the game's own icons for the picture (the stub's are made-up numbers): spells, items, and the icon each macro was given
    ICONS = r'''
SPELLICON = { [403] = 136048, [331] = 136052, [324] = 136051, [8042] = 136026, [6603] = 135641, [3110] = 135809 }
C_Spell.GetSpellTexture = function(id) return SPELLICON[id] or 134400 end
C_Item.GetItemIconByID = function(id) return ({ [118] = 134830, [25] = 135274 })[id] or 134400 end
MICON = { Bandage = 133685, Bolt = 136048, ["Focus Heal"] = 136052, Mount = 136095, Rez = 136077, Shield = 136051, Totems = 135825,
          Heal = 136052 }
function iconize() for _, t in ipairs({ GENM, CHARM }) do for _, m in ipairs(t) do m.icon = MICON[m.name] or m.icon end end end
'''
    L = opened(SHOT, [("Heal", "#showtooltip\n/cast [@mouseover,help,nodead][] Healing Wave\n/say Healing %t")])
    L.execute(ICONS); L.execute('iconize()')
    press(L, "PADDRIGHT"); press(L, "PADDRIGHT")
    dump(L, "grid")
    # s14: the editor's strips (a macro of builder lines), its builder, each pop-out, the keyboard
    BUILT = [("Smart Heal", "#showtooltip\n/cast [@focus,help,nodead][@mouseover,help,nodead][@player] Healing Wave\n"
                            "/castsequence reset=target Lightning Bolt, Earth Shock\n/cast [mod:shift] !Lightning Shield; Healing Wave\n"
                            "/startattack [harm,nodead]\n/cast Frostbolt\n/say %t")]
    LB = opened(BUILT, [], macros='BOOK = { 403, 331, 324, 8042 }; SOFTFRIEND = "1"')
    LB.execute(ICONS); LB.execute('iconize()')
    press(LB, "PAD1"); press(LB, "PADDDOWN"); press(LB, "PADDDOWN")
    dump(LB, "edit")
    press(LB, "PAD1")
    dump(LB, "build")
    chip(LB, 'more'); dump(LB, "build_more"); press(LB, "PAD2")
    tap(LB, bdoc(LB)['ch']['c2u']); dump(LB, "build_case_focus")
    chip(LB, 'c1f'); dump(LB, "build_if"); press(LB, "PAD2")
    chip(LB, 'c2u'); dump(LB, "build_on"); press(LB, "PAD2")
    chip(LB, 'arg'); dump(LB, "build_arg"); press(LB, "PAD2")
    chip(LB, 'cmd'); dump(LB, "build_cmd"); press(LB, "PAD2")
    press(LB, "PAD3"); dump(LB, "build_case"); press(LB, "PAD2")
    chip(LB, 'c1u'); pick_row(LB, lambda r: r.get('u') == 'softenemy')
    LB.execute(COMBAT_ON); sel(LB, allow=False); dump(LB, "build_combat"); LB.execute(COMBAT_OFF)
    press(LB, "PAD2"); press(LB, "PADDDOWN"); press(LB, "PAD1")
    chip(LB, 'reset'); dump(LB, "build_reset"); press(LB, "PAD2")
    press(LB, "PAD2"); press(LB, "PADDDOWN"); press(LB, "PADDDOWN"); press(LB, "PADDDOWN"); press(LB, "PAD1")
    dump(LB, "build_warn")
    press(LB, "PAD2"); press(LB, "PADDUP"); press(LB, "PAD4"); press(LB, "PADDDOWN")
    dump(LB, "move")
    # (s14 review) a Move past the lines on show: the editor scrolls with the moved line
    LONG = ["#showtooltip"] + [["/cast Lightning Bolt", "/cast [mod:shift] Healing Wave", "/say %d" % i, "/startattack"][i % 4]
                               for i in range(11)]     # 12 lines inside the game's 255
    LM = opened([("Long", "\n".join(LONG))], [], macros='BOOK = { 403, 331, 324, 8042 }')
    LM.execute(ICONS); LM.execute('iconize()')
    press(LM, "PAD1"); press(LM, "PADDDOWN"); press(LM, "PADDDOWN"); press(LM, "PAD4")
    for _ in range(8):
        press(LM, "PADDDOWN")
    dump(LM, "move_far")
    press(LB, "PAD2")
    press(LB, "PADDDOWN"); press(LB, "PADDDOWN"); press(LB, "PAD1")
    dump(LB, "kbd")
    tap(LB, "kx")
    # s16: the focused line's X Remove hint, its ask (Cancel / Remove); More with Insert below; a new line (Insert below), its Action list
    dump(LB, "edit_xremove")
    press(LB, "PAD3"); dump(LB, "edit_remove_ask"); press(LB, "PAD2")
    tap(LB, 22); tap(LB, "a"); dump(LB, "edit_more_insert")
    tap(LB, "a"); dump(LB, "build_new"); press(LB, "PAD2"); press(LB, "PAD2")
    LB.execute('MRx.st.draft = nil')
    # s15: the editor's More open, Delete's ask; a stored line's Remove asking in the builder
    tap(LB, 22); tap(LB, "a"); dump(LB, "edit_more")
    press(LB, "PADDDOWN"); press(LB, "PADDDOWN"); press(LB, "PAD1"); dump(LB, "edit_delete"); press(LB, "PAD2")
    press(LB, "PADDUP"); press(LB, "PADDUP"); press(LB, "PAD1"); more(LB, 'rem'); dump(LB, "build_remove"); press(LB, "PAD2"); press(LB, "PAD2")
    # an empty macro's preset tiles, a preset's preview, the presets view, the rank pop-out, the icon
    LE = opened([("New Macro", "")], [], macros='BOOK = { 403, 331, 324, 8042 }; WORN[13] = 9001')
    LE.execute(ICONS); LE.execute('iconize()')
    press(LE, "PAD1"); press(LE, "PADDDOWN")
    dump(LE, "edit_empty")
    press(LE, "PADDRIGHT"); press(LE, "PAD1")                          # (s17: Blank is the first tile; Heal the second)
    dump(LE, "preset")
    press(LE, "PAD2"); press(LE, "PADDUP"); press(LE, "PADDRIGHT"); press(LE, "PAD1")
    dump(LE, "icon")
    # s17 BLANK: A on the first tile of an empty macro - the blank editor (no tiles, the glow on + Line)
    LK = opened([("New Macro", "")], [], macros='BOOK = { 403, 331, 324, 8042 }; WORN[13] = 9001')
    LK.execute(ICONS); LK.execute('iconize()')
    press(LK, "PAD1"); press(LK, "PADDDOWN")
    dump(LK, "edit_empty_blank_focus")
    press(LK, "PAD1")
    dump(LK, "edit_blank")
    LR = opened([("Alpha", "/cast Healing Wave(Rank 2)")], [], macros=RANKBOOK.replace("Rango", "Rank"))
    LR.execute(ICONS)
    press(LR, "PAD1"); press(LR, "PADDDOWN"); press(LR, "PAD1"); chip(LR, 'rank')
    dump(LR, "build_rank")
    LP = opened([("Alpha", "/cast Lightning Bolt")], [], macros='BOOK = { 403, 331, 324 }')
    LP.execute(ICONS)
    press(LP, "PAD1"); more(LP, 'pre')
    dump(LP, "presets")
    tap(LP, 1); tap(LP, "a"); sel(LP)
    dump(LP, "preset_replace")
    press(LP, "PAD2"); press(LP, "PAD2")
    tap(LP, 0); tap(LP, "a"); dump(LP, "preset_blank")               # s17: Blank's preview on a macro with lines, then its ask
    sel(LP); dump(LP, "preset_blank_replace")
    L2 = opened(SHOT, [])
    L2.execute(ICONS); L2.execute('iconize()')
    press(L2, "PADDRIGHT"); press(L2, "PAD3")
    dump(L2, "bind")
    open(os.path.join(DUMP, "bind_ctl.json"), "w", encoding="utf-8").write(json.dumps(last(L2, 'spellsctl')))
    L3 = opened(SHOT, [])
    L3.execute('RULE_OFF = true')
    dump(L3, "off")
    # (review) a bind of a name two macros share, refused; the row made but not placed
    L4 = opened(SHOT + [("Rez", "/cast Ancestral Spirit\n/s Rezzing %t")], [])
    L4.execute(ICONS); L4.execute('iconize()')
    for _ in range(6):
        press(L4, "PADDRIGHT")
    press(L4, "PAD3")
    dump(L4, "taken")
    L5 = opened(SHOT, [])
    L5.execute(ICONS); L5.execute('iconize()')
    L5.execute('local real = WowCompanion.pad.describe; WowCompanion.pad.describe = function() local d = real(); '
               'if d and d.rows then d.rows.mac = nil end; return d end')
    dump(L5, "norow")
    # s14 D6: the companion's own macros, locked
    L8 = opened([], DSM)
    L8.execute(ICONS)
    tap(L8, "tc")
    dump(L8, "locked")
    # (s13 / s14) the Pet tab with a pet out: the pet's orders, then its spell
    L6 = opened(SHOT, [], pre=PET + 'UNITS.pet = { name = "Imp" }\n')
    L6.execute(ICONS); L6.execute('iconize()'); L6.execute(PET_AFTER)
    press(L6, "PADDRIGHT")
    to_pet(L6)
    dump(L6, "pick_pet")
    # (m165) Home's fight faces with macros on them: the padmap and a GCD's facecd, as the add-on sends them in a fight
    L7 = opened(LOOK_MACROS, [], pre=LOOKS)
    L7.execute(ICONS)
    L7.execute('SPELLICON[8042] = 136026; MSPELL.Bolt = 403; MSPELL.Shift = function() return MODSHIFT and 331 or 403 end; MSPELL.Own = 8042; '
               'MITEM.Pot = { "Minor Healing Potion", "|cffffffff|Hitem:118::::::::|h[Minor Healing Potion]|h|r" }; '
               'GENM[GetMacroIndexByName("Own")].icon = 136095; SPELLNAME[8042] = "Earth Shock"')
    for p, n in ((5, "Bolt"), (2, "Shift"), (6, "Pot"), (8, "Own")):
        L7.eval('WowCompanion.padMap.putFight(1, 1, %d, "macro", "%s")' % (p, n))
    L7.execute('fire("PLAYER_REGEN_DISABLED"); COMBAT = true; advance(1)')
    n7 = nsent(L7)
    L7.execute('CDS[403] = { isActive = true, isOnGCD = true, startTime = SECRETV, duration = SECRETV }; '
               'CDS[8042] = { isActive = true, isOnGCD = true, startTime = SECRETV, duration = SECRETV }; fire("SPELL_UPDATE_COOLDOWN")')
    open(os.path.join(DUMP, "looks_padmap.json"), "w", encoding="utf-8").write(json.dumps({"type": "padmap", "v": "m165", "m": padmap(L7)}))
    open(os.path.join(DUMP, "looks_facecd.json"), "w", encoding="utf-8").write(json.dumps(docs(L7, n7, 'facecd')[-1]))
    print("dumped to", DUMP)

print("\n%s (%d pass, %d fail)" % ("ALL PASS" if fails == 0 else "FAILURES", passes, fails))
sys.exit(1 if fails else 0)
