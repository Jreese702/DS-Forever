"""Professions p28 (issue 87's follow-up; the player, 2026-11-23: "is there any to enable herb/ore tracking? Option seems greyed out in
my professions tab"; the developer: "Yeah I can turn that back on"): the book's Find Herbs / Find Minerals buttons were inert. A card
button whose spell is one of the game's minimap tracking types now switches that tracking. Loads the real
WowCompanionProfessions/Professions.lua in Lua 5.1 with stand-in game functions (two professions with book buttons, the game's tracking
list, a secure row that runs the file's own init and tap):
  - the doc: Find Herbs trk 0, Find Minerals trk 1, each with ton / toff columns on the secure open row; Smelting (a recipe window) none;
  - the tracking cells carry NO secure action (type nil), the open cell still casts its spell; the columns stay put on a re-collect;
  - ton: ONE C_Minimap.SetTracking(index, true); toff: (index, false); already so: nothing called, ok; the ack names the tracking;
  - in a fight: refused ("not in a fight"), nothing called; a cell not yet on the row (a fight's rebuild waits): no ton / toff published;
  - greyed when not known: no tracking of that name (an old character) or no setter in the game -> no trk, no cells;
  - by name when the game's list gives no spell id; the old multi-return GetTrackingInfo too;
  - MINIMAP_UPDATE_TRACKING registered; no new control key (ench keeps its column); the tag p28 with its note;
  - no trace: no binding, macro, CVar, window call, cast or Blizzard frame written by this file.
Run: python -B tools/test_prof_gather.py
"""
import os
import sys
from lupa import lua51

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = open(os.path.join(ROOT, 'companion', 'app', 'src', 'main', 'assets', 'companion', 'addons', 'WowCompanionProfessions',
                        'Professions.lua'), encoding='utf-8').read()
passes = fails = 0


def check(ok, what, got=None):
    global passes, fails
    if ok:
        passes += 1; print('PASS', what)
    else:
        fails += 1; print('FAIL', what, '->', got)


L = lua51.LuaRuntime(unpack_returned_tuples=True)
L.execute(r'''
local stub
stub = setmetatable({}, { __index = function() return stub end, __call = function() return stub end })
setmetatable(_G, { __index = function(_, k) return stub end })
NOW = 100
function GetTime() return NOW end
C_Timer = { NewTicker = function() return stub end, NewTimer = function() return stub end, After = function() end }
HANDLERS = {}
function CreateFrame(kind, name)
    local fr = setmetatable({ attrs = {}, name = name, ev = {} }, { __index = function() return stub end })
    function fr:SetScript(k, fn) if k == "OnEvent" then HANDLERS[#HANDLERS + 1] = { fr = fr, fn = fn } end end
    function fr:RegisterEvent(e) self.ev[e] = true; REGISTERED[e] = true; return true end
    function fr:SetAttribute(k, v) self.attrs[k] = v end
    function fr:GetName() return self.name end
    if name then _G[name] = fr end
    return fr
end
REGISTERED = {}
function fire(ev, ...) for _, h in ipairs(HANDLERS) do if h.fr.ev[ev] then pcall(h.fn, h.fr, ev, ...) end end end
COMBAT = false
function InCombatLockdown() return COMBAT end
function UnitAffectingCombat() return COMBAT end
function UnitName() return "Shamy" end
function GetRealmName() return "Forever" end
TRACE = {}
for _, n in ipairs({ "SetBinding", "SaveBindings", "DeleteMacro", "CreateMacro", "EditMacro", "SetCVar", "ShowUIPanel", "HideUIPanel",
                     "ToggleProfessionsBook", "CloseTradeSkill", "SetOverrideBinding", "SetOverrideBindingClick", "CastSpell",
                     "CastSpellByID", "CastSpellByName", "CancelTrackingBuff", "ToggleDropDownMenu" }) do
    _G[n] = function() TRACE[#TRACE + 1] = n end
end
C_CVar = { SetCVar = function() TRACE[#TRACE + 1] = "C_CVar.SetCVar" end }

-- the character: Herbalism (one button, Find Herbs) and Mining (Smelting opens its recipes; Find Minerals)
PROFS = {
    [1] = { "Herbalism", 136246, 40, 75, 1, 10, 182, 0 },
    [2] = { "Mining", 136248, 50, 75, 2, 20, 186, 0 },
}
function GetProfessions() return 1, 2 end
function GetProfessionInfo(i) return unpack(PROFS[i]) end
BOOK = { [11] = { 2383, "Find Herbs", 133939 }, [21] = { 2656, "Smelting", 135811 }, [22] = { 2580, "Find Minerals", 136025 } }
C_SpellBook = {
    GetSpellBookItemInfo = function(slot) local b = BOOK[slot]; return b and { spellID = b[1] } or nil end,
    GetSpellBookItemName = function(slot) local b = BOOK[slot]; return b and b[2] or nil end,
    GetSpellBookItemTexture = function(slot) local b = BOOK[slot]; return b and b[3] or nil end,
}
C_TradeSkillUI = setmetatable({ CanTradeSkillShowCraftingUI = function(sp) return sp == 2656 end, IsTradeSkillReady = function() return false end },
    { __index = function() return function() return nil end end })

-- the game's minimap tracking list (C_Minimap.GetTrackingInfo(i) tables), and what SetTracking was asked
TRACKING = {}
function track(list) TRACKING = list end
SETS = {}
C_Minimap = {
    GetNumTrackingTypes = function() return #TRACKING end,
    GetTrackingInfo = function(i) local t = TRACKING[i]; if not t then return nil end; return { name = t[1], active = t[2], spellID = t[3] } end,
    SetTracking = function(i, on) SETS[#SETS + 1] = { i, on }; if TRACKING[i] then TRACKING[i][2] = on end end,
}
function fullList()
    track({ { "Find Herbs", false, 2383 }, { "Find Minerals", true, 2580 }, { "Find Treasure", false, 2481 } })
end
fullList()

SENT, ROWS, SLOTS, SECURE = {}, {}, {}, {}
local nslot = {}
-- the secure row: TapPad's own contract (init(button, col) for every cell out of combat; onClick(col) once per tap)
function build(name)
    local r = SECURE[name]
    if not r or COMBAT then return false end
    r.cells = {}
    for col = 0, r.n - 1 do
        local b = CreateFrame("Button")
        pcall(r.init, b, col)
        r.cells[col] = b
    end
    return true
end
WowCompanion = {
    S = function(fn) local ok, v = pcall(fn); if ok then return v end end,
    SN = function(fn) local ok, v = pcall(fn); if ok and type(v) == "number" then return v end end,
    CALL = function(fn, ...) return pcall(fn, ...) end,
    send = function(t) SENT[#SENT + 1] = t end, encode = function(t) return t end, push = function() end,
    isPlain = function() return true end, ABSENT = {}, SECRET = {}, THREW = {},
    pad = { op = function(name, ns, h) ROWS[name] = h; return {} end,
            secureRow = function(name, n, init, onClick, opts) SECURE[name] = { n = n, init = init, onClick = onClick, opts = opts }; build(name); return {} end,
            slotFor = function(ns, key)
                SLOTS[ns] = SLOTS[ns] or {}
                if SLOTS[ns][key] == nil then nslot[ns] = (nslot[ns] or -1) + 1; SLOTS[ns][key] = nslot[ns] end
                return SLOTS[ns][key]
            end,
            describe = function() return { rows = { prof = 37, profopen = 38 } } end, onLayout = function() end, retain = function() end },
    capture = { version = 17, take = function() return true end, release = function() return true end,
                state = function() return { holder = "professions" } end, bnext = function() end, refused = function() return true end,
                pass = function() return true end },
}
function lastAck(op) for i = #SENT, 1, -1 do local t = SENT[i]; if t.type == "profack" and t.op == op then return t end end end
function doc() return WowCompanion.collectProf() end
function card(d, id) for _, e in ipairs(d.list) do if e.id == id then return e end end end
function tap(col) SECURE.profopen.onClick(col) end
function attr(col, k) local b = SECURE.profopen.cells[col]; return b and b.attrs[k] end
''')
load = L.eval('function(src) local f, e = loadstring(src, "@Professions.lua"); if not f then error(e) end; return f end')
ok_load = True
try:
    load(SRC)('WowCompanionProfessions')
except Exception as e:  # noqa: BLE001
    ok_load = False; print(e)
check(ok_load, "Professions.lua loads (stand-in game) with no error")
L.execute('fire("PLAYER_LOGIN"); D = doc(); D = doc()')

# 1. the document
herb = L.eval('card(D, 182).btn[1]')
smelt = L.eval('card(D, 186).btn[1]')
mine = L.eval('card(D, 186).btn[2]')
check(herb['n'] == "Find Herbs" and herb['trk'] == 0, "Find Herbs: a tracking the character has, off (trk 0)", herb and dict(herb))
check(mine['n'] == "Find Minerals" and mine['trk'] == 1, "Find Minerals: on (trk 1)", mine and dict(mine))
check(smelt['rec'] is True and smelt['trk'] is None and smelt['ton'] is None, "Smelting (a recipe window) is no tracking button",
      smelt and dict(smelt))
cols = [herb['ton'], herb['toff'], mine['ton'], mine['toff']]
oslot = L.eval('card(D, 186).oslot')
check(all(isinstance(c, int) for c in cols) and len(set(cols)) == 4 and oslot not in cols and 0 not in cols and 1 not in cols,
      "ON / OFF cells of their own on the open row (not the close / book cells, not Mining's open cell)", (cols, oslot))
check(all(L.eval('attr(%d, "type")' % c) is None for c in cols), "the tracking cells carry NO secure action (type nil)",
      [L.eval('attr(%d, "type")' % c) for c in cols])
check(L.eval('attr(%d, "type")' % oslot) == "spell" and L.eval('attr(%d, "spell")' % oslot) == 2656,
      "Mining's open cell still casts Smelting", (L.eval('attr(%d, "type")' % oslot), L.eval('attr(%d, "spell")' % oslot)))
L.execute('D2 = doc()')
check([L.eval('card(D2, 182).btn[1].ton'), L.eval('card(D2, 182).btn[1].toff'), L.eval('card(D2, 186).btn[2].ton'),
       L.eval('card(D2, 186).btn[2].toff')] == cols, "the columns stay put on a re-collect")

# 2. the taps
L.execute('SETS = {}; tap(%d)' % herb['ton'])
a = L.eval('lastAck("gather")')
check(L.eval('#SETS') == 1 and L.eval('SETS[1][1]') == 1 and L.eval('SETS[1][2]') is True, "Find Herbs ON: SetTracking(1, true) once",
      [tuple(L.eval('SETS[%d]' % (i + 1)).values()) for i in range(L.eval('#SETS'))])
check(a is not None and a['ok'] is True and a['on'] is True and a['name'] == "Find Herbs" and a['id'] == 2383,
      "the ack: ok, on, names Find Herbs", a and dict(a))
L.execute('SETS = {}; tap(%d)' % mine['toff'])
check(L.eval('#SETS') == 1 and L.eval('SETS[1][1]') == 2 and L.eval('SETS[1][2]') is False, "Find Minerals OFF: SetTracking(2, false) once")
check(L.eval('card(doc(), 186).btn[2].trk') == 0 and L.eval('card(doc(), 182).btn[1].trk') == 1, "the next doc: Minerals 0, Herbs 1")
L.execute('SETS = {}; tap(%d)' % herb['ton'])
check(L.eval('#SETS') == 0 and L.eval('lastAck("gather").ok') is True, "already on: nothing called, ok")

# 3. a fight
L.execute('SETS = {}; COMBAT = true; tap(%d)' % herb['toff'])
check(L.eval('#SETS') == 0 and L.eval('lastAck("gather").ok') is False and L.eval('lastAck("gather").why') == "not in a fight",
      "in a fight: refused (not in a fight), nothing called", L.eval('lastAck("gather").why'))
L.execute('COMBAT = false')

# 4. greyed when the character does not know it, or the game has no setter
L.execute('track({ { "Find Herbs", false, 2383 } }); D3 = doc()')
check(L.eval('card(D3, 186).btn[2].trk') is None and L.eval('card(D3, 186).btn[2].ton') is None,
      "no Find Minerals in the game's list: no trk, no cells (inert, greyed)")
L.execute('SETS = {}; tap(%d)' % mine['ton'])
check(L.eval('#SETS') == 0 and L.eval('lastAck("gather").why') == "not known", "a stale tap on it: refused (not known), nothing called",
      L.eval('lastAck("gather").why'))
L.execute('fullList(); SAVED = C_Minimap.SetTracking; C_Minimap.SetTracking = nil; D4 = doc(); C_Minimap.SetTracking = SAVED')
check(L.eval('card(D4, 182).btn[1].trk') is None and L.eval('card(D4, 182).btn[1].ton') is None, "no setter in the game: inert")

# 5. by name (no spell id in the list); the old multi-return GetTrackingInfo
L.execute('''fullList(); for _, t in ipairs(TRACKING) do t[3] = nil end; D5 = doc()''')
check(L.eval('card(D5, 182).btn[1].trk') == 0 and L.eval('card(D5, 182).btn[1].ton') == herb['ton'], "matched by name, same cells")
L.execute('''fullList(); OLDGET = C_Minimap.GetTrackingInfo
C_Minimap.GetTrackingInfo = function(i) local t = TRACKING[i]; if not t then return nil end; return t[1], 1, t[2], "spell", -1 end
D6 = doc(); SETS = {}''')
L.execute('tap(%d)' % herb['ton'])
check(L.eval('card(D6, 186).btn[2].trk') == 1 and L.eval('#SETS') == 1 and L.eval('SETS[1][1]') == 1,
      "the old name / texture / active returns: read and switched")
L.execute('C_Minimap.GetTrackingInfo = OLDGET; fullList()')

# 6. a cell not on the row yet (the rebuild waits for a fight's end): not published
L.execute('''TRACKING[4] = { "Find Fish", false, 43308 }; BOOK[12] = { 43308, "Find Fish", 133888 }; PROFS[1][5] = 2
COMBAT = true; D7 = doc(); COMBAT = false''')
fish = L.eval('card(D7, 182).btn[2]')
check(fish['trk'] == 0 and fish['ton'] is None and fish['toff'] is None, "a new tracking in a fight: trk shown, its cells not yet",
      fish and dict(fish))
L.execute('build("profopen"); D8 = doc()')
check(isinstance(L.eval('card(D8, 182).btn[2].ton'), int), "after the fight's rebuild: its cells published (any tracking the character has)")
L.execute('PROFS[1][5] = 1; BOOK[12] = nil; TRACKING[4] = nil')

# 7. the rest unchanged; tags; no trace
check(L.eval('SECURE.profopen.opts and SECURE.profopen.opts.onPress') is True,
      "the open row answers an action-less cell (a tracking tap) on the PRESS (TapPad pad6b: a lost release never loses the tap)")
check(L.eval('REGISTERED["MINIMAP_UPDATE_TRACKING"]') is True, "MINIMAP_UPDATE_TRACKING registered (a switch re-sends the doc)")
check(L.eval('SLOTS.profctl.ench') == 37 and L.eval('SLOTS.profctl["gather"]') is None, "no new control key: ench keeps column 37")
check('local PV = "p28"' in SRC and "Option seems greyed out in my professions tab" in SRC and "p27 ENCHANTING" in SRC,
      "the tag p28, its note quotes the player, p27's note kept")
check(L.eval('#TRACE') == 0, "no trace: no binding, macro, CVar, window call or cast by this file",
      [L.eval('TRACE[%d]' % (i + 1)) for i in range(L.eval('#TRACE'))])

print("%d passed, %d failed" % (passes, fails))
sys.exit(1 if fails else 0)
