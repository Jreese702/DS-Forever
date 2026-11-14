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
  h Delete: armed by the first A, confirmed by a second within 3 s, timed out after; the selection moves to min(count, old); our
    stored binds of it go, and a rename carries them (this character's for a Character macro, every character's for a General one);
  i the Bind box for a macro (Book b63), hosted: Normal = the game's PickupMacro + PlaceAction on the pressed slot, read back, B undoes;
    L3 = the stick's own button with the macro's TEXT (no saved-macro attribute); In combat = the router's fight text; a gone macro =
    "missing"; the box's end hands the room back on its grid;
  j D2: the game's window shown -> the document says win, the `wx` cell is the window's own close button (a secure click), the write
    guard says "window" until it closes;
  k B: one step back, then Home (the controller back with "B", macpad BACK); the room left (f) drops the keyboard / box quietly;
  l the document's size stays small (one page of names, one body);
  m per character: the tab and selection are kept per name-realm.
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
C_Item = { GetItemNameByID = function(id) local x = item(id); return x and x.n end, GetItemIconByID = function(id) return 200000 + id end,
           GetItemSpell = function(id) local x = item(id); if x and x.use then return "Healing Potion", 439 end end,
           IsEquippableItem = function(id) local x = item(id); return x and x.eq == true end }
'''
# after the load: helpers
AFTER = r'''
MRx = WowCompanion.macroRoom._mr
-- the book (the game's own spellbook is stubbed empty): the character's spells are BOOK's (tools/test_party.py's WORLD_HELPERS)
WowCompanion.spellsBindableList = function()
    local out = {}
    for i, id in ipairs(BOOK) do out[#out + 1] = { id = id, slot = i } end
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


def opened(gen=(), char=(), pre=''):
    L, errs = world(pre=pre)
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
check('local SV = "s10"' in SRC["spells"] and 'local BV = "b63"' in SRC["book"] and 'local PV = "m163"' in SRC["padmap"],
      "a. the versions: Spells s10, Book b63, PadMap m163")
QUOTE = '"Lets scrap the smart ranged attack and start building a macro room'
check(all(QUOTE in SRC[k] for k in ("spells", "book", "padmap")), "a. each changed file's header note quotes the user")
BLOCK = SRC["spells"][SRC["spells"].index("-- ==================================================================== s10 THE MACROS ROOM"):]
code = "\n".join(re.sub(r'--.*$', '', ln) for ln in BLOCK.splitlines())
bad = [w for w in ("ShowMacroFrame", "MacroFrame_Show", "LoadAddOn", "HideUIPanel", "ShowUIPanel", "RunMacroText", "RunMacro",
                   "SetMacroExecuteLineCallback", "SetBinding", "SaveBindings", "SetCVar", "SetOverrideBinding", "PlaceAction",
                   "PickupMacro", "ToggleFrame", "MacroFrameText", "SetAttribute(\"macro\"")
       if re.search(r'\b' + re.escape(w) + r'\b' if w[0].isalpha() and w[-1].isalpha() else re.escape(w), code)]
check(bad == [], "a. the room's block never opens / closes / shows / hides a window, runs a macro, binds a key, writes a CVar or a bar", bad)
writes = re.findall(r'\b(CreateMacro|EditMacro|DeleteMacro)\(', code)
check(sorted(set(writes)) == ["CreateMacro", "DeleteMacro", "EditMacro"] and len(writes) == 5,
      "a. its writes: CreateMacro once, EditMacro three times (name / line / added lines), DeleteMacro once", writes)
guarded = all(re.search(r'canWrite\(\)', fn) for fn in re.findall(r'function MR\.(?:addLines|delete)\(.*?\nend', BLOCK, re.S))
check(guarded and 'local why = MR.canWrite()\n    if why then MR.ack(k.name, false, why)' in BLOCK,
      "a. every write is behind the one guard (the keyboard's Done, an added line, Delete)")

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
check(r is not None and r.n == 36, "b. padtab 39 then 34 makes the row: 36 cells", r and r.n)
d = mdoc(L)
k = d.get('keys') or {}
check(k.get('h') == 0 and k.get('f') == 1 and k.get('fb') == 2 and k.get('wx') == 11 and k.get('s0') == 12 and k.get('n') == 24
      and 'mac' in ((d.get('pad') or {}).get('rows') or {}), "b. the document carries the keys at fixed columns and the pad", k)
check(clean(L) == {}, "b. clean", clean(L))
# a full pad: the row cannot be made, the document says so
L2, _ = world()
L2.execute('for i = WowCompanion.pad.nrows, 55 do WowCompanion.pad.op("filler" .. i, nil, function() end) end')
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

# ================================================================ e. the editor
L = opened([("Alpha", "/wave\n/say hi\n/run print(|cff)")], CHAR)
press(L, "PAD1")
d = mdoc(L)
check(d.get('mode') == 'edit' and d.get('ef') == 'name' and d['mac']['lines'] == ['/wave', '/say hi', '/run print(|cff)']
      and d['mac'].get('lock') == [3], "e. A: the editor, on the name; a line with '|' is locked", (d.get('ef'), d['mac']))
press(L, "PAD1")
check(mdoc(L).get('kbd', {}).get('name') == 'macname' and mdoc(L)['kbd']['text'] == 'Alpha', "e. A on the name: the rename keyboard, its text")
r = kbd_done(L, "Zulu")
d = mdoc(L)
check(r.startswith('true') and d.get('mode') == 'edit' and d['mac']['n'] == 'Zulu' and L.eval('mnames(GENM)') == 'Zulu',
      "e. rename: EditMacro(i, name), re-found by name", (r, d['mac']['n']))
press(L, "PADDDOWN")
check(mdoc(L).get('ef') == 'l1' and mdoc(L).get('efc') == 1, "e. Down: the first line (s1)")
press(L, "PAD1")
check(mdoc(L)['kbd'] == {'name': 'macline', 'text': '/wave', 'max': 255 - len("/wave\n/say hi\n/run print(|cff)") + 5, 'line': 1},
      "e. A on a line: its keyboard, the room left in 255", mdoc(L).get('kbd'))
L.execute('MLOG = {}')
kbd_done(L, "/wave hello")
check(L.eval('mbody("Zulu")') == "/wave hello\n/say hi\n/run print(|cff)" and len(mlog(L)) == 1 and mlog(L)[0].startswith('edit 1 nil'),
      "e. Done: EditMacro(i, nil, nil, body) (the body-only form)", (L.eval('mbody("Zulu")'), mlog(L)))
press(L, "PAD3")
check(mdoc(L)['kbd']['name'] == 'macline' and mdoc(L)['kbd'].get('line') is None, "e. X on a line: a new line under it")
kbd_done(L, "/sit")
check(L.eval('mbody("Zulu")') == "/wave hello\n/sit\n/say hi\n/run print(|cff)", "e. inserted under line 1", L.eval('mbody("Zulu")'))
press(L, "PAD1")
kbd_done(L, "")
check(L.eval('mbody("Zulu")') == "/sit\n/say hi\n/run print(|cff)", "e. a line emptied goes", L.eval('mbody("Zulu")'))
press(L, "PADDDOWN"); press(L, "PADDDOWN")
L.execute('MLOG = {}')
press(L, "PAD1")
check(mdoc(L).get('note') == 'locked' and mlog(L) == [] and mdoc(L).get('mode') == 'edit', "e. A on the '|' line: locked, no keyboard")
press(L, "PADDDOWN")
d = mdoc(L)
check(d.get('ef') == 'add' and d.get('efc') == 20, "e. Down past the lines: the button row (+ line = s20)", (d.get('ef'), d.get('efc')))
press(L, "PADDRIGHT"); press(L, "PADDRIGHT"); press(L, "PADDRIGHT")
check(mdoc(L).get('ef') == 'del' and mdoc(L).get('efc') == 23, "e. Right along the row: Delete (s23)")
press(L, "PADDLEFT"); press(L, "PADDLEFT"); press(L, "PADDLEFT")
press(L, "PAD1")
kbd_done(L, "/cheer")
check(L.eval('mbody("Zulu")').endswith("\n/cheer"), "e. + line: added at the end", L.eval('mbody("Zulu")'))
# 255 with the new lines
L.eval('function() local i = GetMacroIndexByName("Zulu"); GENM[i].body = string.rep("x", 250) end')()
L.execute('MLOG = {}')
press(L, "PAD1")
check(mdoc(L)['kbd']['max'] == 4, "e. + line on a 250-letter body: 4 letters left (the new line's break counts)", mdoc(L).get('kbd'))
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

# ================================================================ g. + Spell/Item and the recipes
L = opened([("Alpha", "")], CHAR)
press(L, "PAD1")
for _ in range(2):
    press(L, "PADDDOWN")
press(L, "PADDRIGHT")
check(mdoc(L).get('ef') == 'pick', "g. the Spell/Item button", mdoc(L).get('ef'))
press(L, "PAD1")
d = mdoc(L)
rows = (d.get('pick') or {}).get('rows') or []
check(d.get('mode') == 'pick' and [(x['t'], x['n']) for x in rows] == [('s', 'Lightning Bolt'), ('s', 'Healing Wave'), ('s', 'Lightning Shield'),
                                                                       ('u', 'Minor Healing Potion'), ('e', 'Worn Shortsword')],
      "g. the list: the book's spells, then bag items with a use (/use) or to equip (/equip); cloth left out", rows)
L.execute('MLOG = {}')
tap(L, 3)
tap(L, "a", allow=True)
d = mdoc(L)
check(L.eval('mbody("Alpha")') == '/use Minor Healing Potion' and d.get('mode') == 'edit' and len(mlog(L)) == 1,
      "g. a finger picks the potion: ONE write, '/use <item>', back to the editor", (L.eval('mbody("Alpha")'), mlog(L)))
check(mdoc(L).get('ef') == 'pick', "g. back in the editor the glow is still on Spell/Item", mdoc(L).get('ef'))
press(L, "PAD1"); tap(L, 0); tap(L, "a", allow=True)
check(L.eval('mbody("Alpha")') == '/use Minor Healing Potion\n/cast Lightning Bolt', "g. a spell: '/cast <spell>'", L.eval('mbody("Alpha")'))
press(L, "PADDRIGHT")
check(mdoc(L).get('ef') == 'rec', "g. the Recipes button")
press(L, "PAD1")
d = mdoc(L)
check(d.get('mode') == 'rec' and d['rec']['ids'] == ['mo', 'fo', 'me', 'sh', 'at', 'tr'], "g. six recipe tiles", d.get('rec'))
L.eval('function() local i = GetMacroIndexByName("Alpha"); GENM[i].body = "" end')()
press(L, "PAD1")
d = mdoc(L)
check(d.get('mode') == 'pick' and d['pick']['rec'] == 'mo' and d['pick']['step'] == 1 and d['pick']['of'] == 1
      and all(x['t'] == 's' for x in d['pick']['rows']), "g. mouseover heal: one spell pick (spells only)", d.get('pick'))
press(L, "PADDDOWN")
L.execute('ALLOW = true'); press(L, "PAD1"); L.execute('ALLOW = false')
check(L.eval('mbody("Alpha")') == '#showtooltip\n/cast [@mouseover,help,nodead][] Healing Wave' and mdoc(L).get('mode') == 'edit',
      "g. mouseover heal written: #showtooltip + the conditional cast", L.eval('mbody("Alpha")'))
press(L, "PAD1"); tap(L, 3); tap(L, "a")
check(mdoc(L)['pick']['step'] == 1 and mdoc(L)['pick']['of'] == 2, "g. Shift pair: two picks (1 of 2)", mdoc(L).get('pick'))
tap(L, 0); tap(L, "a")
check(mdoc(L)['pick']['step'] == 2, "g. ... then 2 of 2", mdoc(L).get('pick'))
L.execute('MLOG = {}')
tap(L, 1); tap(L, "a", allow=True)
check(L.eval('mbody("Alpha")') == '#showtooltip\n/cast [@mouseover,help,nodead][] Healing Wave\n/cast [mod:shift] Healing Wave; Lightning Bolt'
      and len(mlog(L)) == 1, "g. Shift pair: '[mod:shift] second; first', the tooltip line not twice, ONE write", (L.eval('mbody("Alpha")'), mlog(L)))
for rid, want in (("fo", "/cast [@focus,exists,nodead][] Lightning Bolt"), ("me", "/cast [@player] Lightning Bolt"),
                  ("at", "/startattack\n/cast Lightning Bolt"), ("tr", "/use 13\n/use 14\n/cast Lightning Bolt")):
    L.eval('function() local i = GetMacroIndexByName("Alpha"); GENM[i].body = "" end')()
    press(L, "PAD1")
    tap(L, ['mo', 'fo', 'me', 'sh', 'at', 'tr'].index(rid)); tap(L, "a"); tap(L, 0); tap(L, "a", allow=True)
    check(L.eval('mbody("Alpha")') == '#showtooltip\n' + want, "g. recipe %s" % rid, L.eval('mbody("Alpha")'))
press(L, "PAD1"); press(L, "PAD2")
check(mdoc(L).get('mode') == 'edit', "g. B in the recipes: the editor")
check(clean(L) == {}, "g. clean", clean(L))

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
for _ in range(5):
    press(L, "PADDDOWN")
press(L, "PADDRIGHT"); press(L, "PADDRIGHT"); press(L, "PADDRIGHT")
check(mdoc(L).get('ef') == 'del', "h. the Delete button", mdoc(L).get('ef'))
L.execute('MLOG = {}')
press(L, "PAD1")
d = mdoc(L)
check(d.get('del') is True and mlog(L) == [], "h. the first A arms Delete (nothing deleted)", (d.get('del'), mlog(L)))
L.execute('advance(3.5)')
check(not mdoc(L).get('del'), "h. not confirmed within 3 s: disarmed")
press(L, "PAD1")
L.execute('ALLOW = true'); press(L, "PAD1"); L.execute('ALLOW = false')
d = mdoc(L)
check(mlog(L) == ['delete 2'] and L.eval('mnames(GENM)') == 'Alpha|Charlie' and d.get('mode') == 'grid' and d.get('cur') == 1
      and d['mac']['n'] == 'Charlie', "h. the second A within 3 s: DeleteMacro; the grid, the selection min(count, old)", (mlog(L), d.get('cur')))
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
L = opened(GEN, CHAR)
press(L, "PAD1"); press(L, "PADDDOWN"); press(L, "PADDDOWN"); press(L, "PADDRIGHT"); press(L, "PAD1")
check(mdoc(L).get('mode') == 'pick', "k. the pick list")
press(L, "PAD2")
check(mdoc(L).get('mode') == 'edit', "k. B: the editor")
press(L, "PAD2")
check(mdoc(L).get('mode') == 'grid' and holder(L) == 'macros', "k. B: the grid (still held)")
n0 = nsent(L)
press(L, "PAD2")
back = [x for x in docs(L, n0, 'macpad') if x.get('k') == 'BACK']
check(holder(L) is None and back, "k. B at the grid: the controller back (\"B\"), macpad BACK (the app goes Home)", holder(L))
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
press(L, "PADDDOWN"); press(L, "PAD1")
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
for _ in range(2):
    press(L, "PADDDOWN")
for _ in range(3):
    press(L, "PADDRIGHT")
check(mdoc(L).get('ef') == 'del', "n. the Delete button", mdoc(L).get('ef'))
L.execute('MLOG = {}; ALLOW = true')
press(L, "PAD1"); press(L, "PAD1")
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
for _ in range(2):
    press(L, "PADDDOWN")
for _ in range(3):
    press(L, "PADDRIGHT")
L.execute('MLOG = {}; ALLOW = true')
press(L, "PAD1"); press(L, "PAD1")
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

# ================================================================ o. (review) a recipe's "#showtooltip" goes on the macro's first line
L = opened([("Alpha", "/wave\n/say hi")], CHAR)
press(L, "PAD1")
for _ in range(3):
    press(L, "PADDDOWN")
press(L, "PADDRIGHT"); press(L, "PADDRIGHT")
check(mdoc(L).get('ef') == 'rec', "o. the Recipes button", mdoc(L).get('ef'))
press(L, "PAD1"); press(L, "PAD1")
L.execute('ALLOW = true'); press(L, "PAD1"); L.execute('ALLOW = false')
check(L.eval('mbody("Alpha")') == '#showtooltip\n/wave\n/say hi\n/cast [@mouseover,help,nodead][] Lightning Bolt',
      "o. on a macro with lines: '#showtooltip' FIRST (the only line the game reads it on), the recipe at the end", L.eval('mbody("Alpha")'))
L.eval('function() local i = GetMacroIndexByName("Alpha"); GENM[i].body = "#show Lightning Shield\\n/wave" end')()
press(L, "PAD1"); press(L, "PAD1")
L.execute('ALLOW = true'); press(L, "PAD1"); L.execute('ALLOW = false')
check(L.eval('mbody("Alpha")') == '#show Lightning Shield\n/wave\n/cast [@mouseover,help,nodead][] Lightning Bolt',
      "o. a '#show' line leading already: no second tooltip line", L.eval('mbody("Alpha")'))
check(clean(L) == {}, "o. clean", clean(L))

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
SPELLICON = { [403] = 136048, [331] = 136052, [324] = 136051 }
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
    press(L, "PAD1"); press(L, "PADDDOWN")
    dump(L, "edit")
    press(L, "PADDDOWN"); press(L, "PADDDOWN"); press(L, "PADDRIGHT"); press(L, "PAD1")
    dump(L, "pick")
    press(L, "PAD2"); press(L, "PADDRIGHT"); press(L, "PAD1"); press(L, "PADDRIGHT")
    dump(L, "recipes")
    press(L, "PAD2"); press(L, "PADDUP"); press(L, "PADDUP"); press(L, "PADDUP"); press(L, "PAD1")
    dump(L, "kbd")
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
    print("dumped to", DUMP)

print("\n%s (%d pass, %d fail)" % ("ALL PASS" if fails == 0 else "FAILURES", passes, fails))
sys.exit(1 if fails else 0)
