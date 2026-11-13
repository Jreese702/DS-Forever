"""bags-onerow1 (the user, 2026-11-13: "Bags share one row"). Loads the REAL WowCompanion/Bags.lua in Lua 5.1 with stand-in game
functions (the harness of test_unequip.py: bags, a cursor, timers) and checks the ONE shared drag row:
  - the rows: "bmvq" and the shared "bmv" (pinned to the pad's last row: opts.last), no per-bag bmv0..bmv5; "bagsort" is 40 columns
    (column 0 = the game's sort as before, 1..9 nothing, 10 + bag = the bag's name, never a sort);
  - a drag = name + slot, name + slot: within one bag, across two bags, TWO QUICK TAPS ON DIFFERENT BAGS (each slot tap uses its own name);
  - WRONG-BAG PROTECTION: a slot tap with no name is refused ("nobag"), nothing picked up, and a held pick is void (never a drop into a
    guessed bag); a name is used by one slot tap only; a STALE name (older than MV.TTL) is refused; a name that is not a player bag
    (a bank bag) names nothing; the latest name wins;
  - a nameless tap after the cursor prefix (bmvq 36) voids that prefix too;
  - in a fight: a named bag-to-bag move still works (plain rows, no protected call), a nameless one is refused the same;
  - the bags doc says the route (mv = { r = "bmv", n = "bagsort", b = 10 }); no trace (no bindings / macros / CVars / open-close);
  - the WHOLE pad (tools/wowstub.py, every add-on file, all bags + the reagent bag, a non-empty cast bar, the radials' rows): every row
    placed at the Thor's UI scale (popupTop 126.6), "bmv" on the last row; at the smallest scale (121.5) "bmv" is a row that drops.
Run: python -B tools/test_bags_onerow.py
"""
import os
import sys
from lupa import lua51

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ADD = os.path.join(ROOT, 'companion', 'app', 'src', 'main', 'assets', 'companion', 'addons')
BAGS = open(os.path.join(ADD, 'WowCompanion', 'Bags.lua'), encoding='utf-8').read()
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
TIMERS = {}
C_Timer = { NewTicker = function() return stub end, NewTimer = function() return stub end,
            After = function(s, fn) TIMERS[#TIMERS + 1] = { at = NOW + s, fn = fn } end }
function run(dt)
    local upto = NOW + (dt or 0)
    for _ = 1, 10000 do
        local bi, best = nil, nil
        for i, t in ipairs(TIMERS) do if t.at <= upto and (not best or t.at < best.at) then bi, best = i, t end end
        if not bi then break end
        table.remove(TIMERS, bi)
        if best.at > NOW then NOW = best.at end
        pcall(best.fn)
    end
    NOW = upto
end
HANDLERS = {}
function CreateFrame(kind, name)
    local fr = setmetatable({ attrs = {}, name = name, ev = {} }, { __index = function() return stub end })
    function fr:SetScript(k, fn) if k == "OnEvent" then HANDLERS[#HANDLERS + 1] = { fr = fr, fn = fn } end end
    function fr:RegisterEvent(e) self.ev[e] = true; return true end
    function fr:SetAttribute(k, v) self.attrs[k] = v end
    function fr:GetName() return self.name end
    return fr
end
function fire(ev, ...) for _, h in ipairs(HANDLERS) do if h.fr.ev[ev] then pcall(h.fn, h.fr, ev, ...) end end end
EMITS, SENT = {}, {}
local function enc(v)
    if type(v) ~= "table" then return tostring(v) end
    local keys = {}
    for k in pairs(v) do keys[#keys + 1] = tostring(k) end
    table.sort(keys)
    local parts = {}
    for _, k in ipairs(keys) do local x = v[k]; if x == nil then x = v[tonumber(k)] end; parts[#parts + 1] = k .. "=" .. enc(x) end
    return "{" .. table.concat(parts, ",") .. "}"
end
-- the pad: plain ops by name, and the namespaces' slotFor / retain as TapPad has them
OPS = {}
PAD = { ns = {} }
OPTS = {}
function PAD.op(name, ns, fn, opts) OPS[name] = fn; OPTS[name] = opts or false end
function PAD.secureRow() return 1 end
function PAD.describe() return { rows = {} } end
function PAD.onLayout() end
function PAD.slotFor(ns, key)
    if key == nil then return nil end
    local t = PAD.ns[ns]
    if not t then t = { byKey = {}, byCol = {} }; PAD.ns[ns] = t end
    if t.byKey[key] then return t.byKey[key] end
    for col = 0, 39 do if t.byCol[col] == nil then t.byKey[key] = col; t.byCol[col] = key; return col end end
end
function PAD.retain(ns, present)
    local t = PAD.ns[ns]; if not t then return end
    for key, col in pairs(t.byKey) do if not present[key] then t.byKey[key] = nil; t.byCol[col] = nil end end
end
WowCompanion = {
    S = function(fn) local ok, v = pcall(fn); if ok then return v end end,
    SN = function(fn) local ok, v = pcall(fn); if ok and type(v) == "number" then return v end end,
    CALL = function(fn) return pcall(fn) end,
    emit = function(t) EMITS[#EMITS + 1] = t end,
    send = function(s) SENT[#SENT + 1] = s end, encode = enc, push = function() end, isPlain = function() return true end,
    pad = PAD, VERSION = "test",
}
COMBAT = false
function InCombatLockdown() return COMBAT end
function UnitAffectingCombat() return COMBAT end
function UnitName() return "Shamy" end
function GetRealmName() return "Forever" end
TRACE = {}
for _, n in ipairs({ "SetBinding", "SaveBindings", "DeleteMacro", "CreateMacro", "EditMacro", "SetCVar", "ShowUIPanel", "HideUIPanel",
                     "ToggleCharacter", "CloseAllBags", "OpenAllBags", "ToggleBackpack", "StaticPopup_Show", "StaticPopup_Hide" }) do
    _G[n] = function() TRACE[#TRACE + 1] = n end
end
function GetNumMacros() return 0, 0 end
function SetOverrideBindingClick() end
function SetOverrideBinding() end
function GetBindingAction() return "" end
-- the game's bit library (the stand-in _G would answer a table for it, so every family would "fit")
bit = { band = function(a, b)
    local r, p = 0, 1
    while a > 0 and b > 0 do
        local x, y = a % 2, b % 2
        if x == 1 and y == 1 then r = r + p end
        a, b, p = (a - x) / 2, (b - y) / 2, p * 2
    end
    return r
end }
-- THE GAME: bags (0 backpack, 1 a quiver = family 1, 2 a plain bag), worn gear, ONE cursor
NSLOTS = { [0] = 4, [1] = 2, [2] = 2 }
FAM = { [0] = 0, [1] = 1, [2] = 0 }
BAG = { [0] = { [1] = 117, [2] = 118 }, [1] = {}, [2] = {} }
WORN = { [1] = 801, [5] = 900, [16] = 901, [17] = 902, [18] = 903 }
ST = { cursor = nil, block = nil }
CALLS = { pickInv = 0, equip = 0 }
-- ST.block: "pick": the game blocks PickupInventoryItem; "put": it blocks the put-down; in a fight only
local function blocked(what)
    if COMBAT and ST.block and ST.block.what == what then fire("ADDON_ACTION_BLOCKED", "WowCompanion", ST.block.fn); return true end
end
function PickupInventoryItem(slot)
    CALLS.pickInv = CALLS.pickInv + 1
    if blocked("pick") then return end
    if ST.cursor then return end
    if WORN[slot] then ST.cursor = { inv = slot, id = WORN[slot] } end
end
function EquipCursorItem(slot) CALLS.equip = CALLS.equip + 1; if ST.cursor then WORN[slot] = ST.cursor.id; if ST.cursor.bag then BAG[ST.cursor.bag][ST.cursor.slot] = nil end; ST.cursor = nil end end
function CursorHasItem() return ST.cursor ~= nil end
function ClearCursor() ST.cursor = nil end
function DeleteCursorItem() ST.cursor = nil end
C_Container = {
    GetContainerItemID = function(b, s) return BAG[b] and BAG[b][s] end,
    GetContainerNumSlots = function(b) return NSLOTS[b] or 0 end,
    GetContainerNumFreeSlots = function(b)
        local n = 0; for s = 1, NSLOTS[b] or 0 do if not BAG[b][s] then n = n + 1 end end; return n, FAM[b] or 0 end,
    GetContainerItemInfo = function(b, s) local id = BAG[b] and BAG[b][s]; if id then return { itemID = id, iconFileID = 1, quality = 1, stackCount = 1 } end end,
    GetContainerItemLink = function(b, s) local id = BAG[b] and BAG[b][s]; if id then return "|Hitem:" .. id .. "|h" end end,
    PickupContainerItem = function(b, s)
        if blocked("put") then return end
        if ST.cursor then
            if BAG[b][s] then return end                -- a swap is not needed here
            BAG[b][s] = ST.cursor.id
            if ST.cursor.inv then WORN[ST.cursor.inv] = nil end
            if ST.cursor.bag then BAG[ST.cursor.bag][ST.cursor.slot] = nil end
            ST.cursor = nil
        elseif BAG[b][s] then ST.cursor = { bag = b, slot = s, id = BAG[b][s] } end
    end,
}
C_Item = { GetItemNameByID = function(id) return "Item " .. id end, GetItemInfoInstant = function(id) return id, "Armor", "Mail", "", 1, 4, 0 end,
           GetItemFamily = function() return 0 end, GetItemSpell = function() return nil end, RequestLoadItemDataByID = function() end }
function GetInventoryItemID(unit, slot) return WORN[slot] end
function GetInventoryItemLink(unit, slot) if WORN[slot] then return "|Hitem:" .. WORN[slot] .. "|h" end end
C_EquipmentSet = { GetEquipmentSetIDs = function() return { 7 } end,
                   GetEquipmentSetInfo = function(id) return "Tank", 1, id, false, 1, 1, 0, 0 end }
for _, n in ipairs({ "MerchantFrame", "GossipFrame", "QuestFrame", "BankFrame", "MailFrame", "TradeFrame", "AuctionHouseFrame",
                     "GuildBankFrame", "ClassTrainerFrame", "CharacterFrame" }) do _G[n] = { IsShown = function() return false end } end
-- interact's capture: Character holds it by touch; pass is recorded
REFUSED, PASSES, SPEC = 0, {}, nil
WowCompanion.capture = { version = 11, take = function(id, spec) if id == "character" then SPEC = spec end; return true end,
    pass = function(id, keys) PASSES[#PASSES + 1] = #keys; return true end, release = function() return true end,
    state = function() return { holder = "character" } end, refused = function() REFUSED = REFUSED + 1; return true end }
-- helpers
function lastMove() for i = #EMITS, 1, -1 do local e = EMITS[i]; if e.bagsMove then return e.bagsMove end end end
function movesSince(n) local out = {}; for i = n + 1, #EMITS do if EMITS[i].bagsMove then out[#out + 1] = EMITS[i].bagsMove end end; return out end
function nEmits() return #EMITS end
function bmvq(col) OPS.bmvq(nil, col) end
-- bags-onerow1: a bag slot's drag tap = the bag's name on bagsort (10 + bag), then the shared row bmv
function bmvAt(bag, col) OPS.bagsort(nil, 10 + bag); OPS.bmv(nil, col) end
SORTS = 0
C_Container.SortBags = function() SORTS = SORTS + 1 end
PICKS = 0
do local real = C_Container.PickupContainerItem; C_Container.PickupContainerItem = function(b, s) PICKS = PICKS + 1; return real(b, s) end end
function name(bag) OPS.bagsort(nil, 10 + bag) end
function slotTap(col) OPS.bmv(nil, col) end
function at(bag, slot) name(bag); slotTap(slot - 1) end
''')
load = L.eval('function(src, name, addon) local f, e = loadstring(src, "@" .. name); if not f then error(e) end; return f(addon) end')
ok_load = True
try:
    load(BAGS, 'Bags.lua', 'WowCompanion')
except Exception as e:  # noqa: BLE001
    ok_load = False; print(e)
check(ok_load, "Bags.lua loads (stand-in game) with no error")
L.execute('WowCompanionDB = {}; fire("PLAYER_LOGIN"); fire("PLAYER_ENTERING_WORLD"); run(6)')


def mv():
    return L.eval('lastMove()')


# 1. the rows
check(L.eval('type(OPS.bmvq)') == 'function' and L.eval('type(OPS.bmv)') == 'function', "the rows bmvq and bmv are there (registered at load)")
L.execute('WowCompanion.bagsMoveRows({ { bag = 0 }, { bag = 1 }, { bag = 2 } })')
check(all(L.eval('OPS["bmv%d"] == nil' % b) for b in range(6)), "no per-bag drag rows bmv0..bmv5")
check(L.eval('OPTS.bmv ~= false and OPTS.bmv.last == true'), "bmv is pinned to the pad's last row (opts.last)")
check(L.eval('OPTS.bmvq == false or OPTS.bmvq.last ~= true'), "bmvq takes the next row (not pinned)")
L.execute('OPS.bagsort(nil, 0)')
check(L.eval('SORTS') == 1, "bagsort column 0 still runs the game's sort")
L.execute('for c = 1, 39 do OPS.bagsort(nil, c) end; MV0 = WowCompanion._bagsMV; MV0.bag, MV0.at = nil, nil')
check(L.eval('SORTS') == 1, "bagsort columns 1..39 never sort")

# 2. a drag within one bag: name 0 + slot 1, name 0 + slot 3
L.execute('NOW = 200; at(0, 1)')
check(mv().picked is not None and mv().picked.bag == 0 and mv().picked.slot == 1, "name 0 + slot 1: picked 0:1")
L.execute('NOW = 200.3; at(0, 3)')
check(mv().ok is True and L.eval('BAG[0][3]') == 117 and L.eval('BAG[0][1]') is None, "name 0 + slot 3: moved 0:1 -> 0:3", (L.eval('BAG[0][3]'),))

# 3. TWO QUICK TAPS ON DIFFERENT BAGS: each slot tap uses its own name
L.execute('NOW = 210; at(0, 3); NOW = 210.01; at(2, 1)')
check(mv().ok is True and L.eval('BAG[2][1]') == 117 and L.eval('BAG[0][3]') is None, "two quick taps on bags 0 and 2: moved 0:3 -> 2:1",
      (L.eval('BAG[2][1]'), L.eval('BAG[0][3]')))
L.execute('NOW = 211; at(2, 1); NOW = 211.01; at(0, 1)')
check(mv().ok is True and L.eval('BAG[0][1]') == 117, "and back, 2:1 -> 0:1")

# 4. WRONG-BAG PROTECTION: a slot tap with no name
L.execute('NOW = 220; P0 = PICKS; slotTap(0)')
check(mv().ok is False and mv().why == 'nobag' and L.eval('PICKS - P0') == 0, "a slot tap with no name: refused 'nobag', nothing picked up")
L.execute('NOW = 221; at(0, 1)')
check(mv().picked is not None, "a held pick ...")
L.execute('NOW = 221.1; P0 = PICKS; slotTap(2)')
check(mv().why == 'nobag' and L.eval('PICKS - P0') == 0, "... then a nameless drop tap: refused, nothing moved")
L.execute('NOW = 221.2; at(0, 2)')
check(mv().picked is not None and mv().picked.slot == 2 and L.eval('BAG[0][1]') == 117 and L.eval('BAG[0][2]') == 118,
      "the pick was void: the next named tap is a new pick, never a drop")
L.execute('NOW = 240')

# 5. a name is used by ONE slot tap
L.execute('NOW = 250; name(0); slotTap(0)')
check(mv().picked is not None and mv().picked.bag == 0, "name 0 + slot: a pick")
L.execute('NOW = 250.05; P0 = PICKS; slotTap(1)')
check(mv().why == 'nobag' and L.eval('PICKS - P0') == 0 and L.eval('BAG[0][2]') == 118, "a second slot tap on the same name: refused, nothing moved")
L.execute('NOW = 270')

# 6. a STALE name
L.execute('NOW = 280; name(0); NOW = NOW + WowCompanion._bagsMV.TTL + 0.1; P0 = PICKS; slotTap(0)')
check(mv().why == 'nobag' and L.eval('PICKS - P0') == 0, "a stale name (older than TTL): refused, nothing picked")
L.execute('NOW = 290; name(0); NOW = NOW + WowCompanion._bagsMV.TTL - 0.1; slotTap(0)')
check(mv().picked is not None, "a name just inside TTL: used")
L.execute('NOW = 300')

# 7. a name that is not a player bag; the latest name wins
L.execute('NOW = 310; name(7); slotTap(0)')
check(mv().why == 'nobag', "bag 7 (a bank bag) names nothing: the slot tap is refused")
L.execute('NOW = 320; BAG[2][2] = 119; name(0); name(2); slotTap(1)')
check(mv().picked is not None and mv().picked.bag == 2 and mv().picked.slot == 2, "two names: the latest (bag 2) is the one used", mv().picked)
L.execute('NOW = 340')

# 8. the cursor prefix (bmvq 36) is void after a nameless tap
L.execute('NOW = 350; bmvq(36); slotTap(0); NOW = 350.1; at(0, 1)')
check(mv().picked is not None and mv().picked.bag == 0,
      "bmvq 36 then a nameless tap: the prefix is void (the next named tap is a pick, no cursor move)")
L.execute('NOW = 370')

# 9. in a fight: a named move works, a nameless one is refused the same
L.execute('COMBAT = true; NOW = 380; at(0, 1); NOW = 380.3; at(2, 1)')
check(mv().ok is True and L.eval('BAG[2][1]') == 117, "in a fight: name + slot, name + slot = moved (plain rows only)")
L.execute('NOW = 381; P0 = PICKS; slotTap(0)')
check(mv().why == 'nobag' and L.eval('PICKS - P0') == 0, "in a fight: a nameless tap refused the same")
L.execute('COMBAT = false; NOW = 400')

# 10. the bags doc says the route; no trace
d = L.eval('(function() local ok, b = pcall(WowCompanion.collectBags); return ok and b or nil end)()')
check(d is not None and d.mv is not None and d.mv.r == 'bmv' and d.mv.n == 'bagsort' and d.mv.b == 10, "bags doc: mv = { r bmv, n bagsort, b 10 }",
      d and d.mv)
check(L.eval('#TRACE') == 0, "no trace: no binding / macro / CVar / Blizzard open-close / popup call", L.eval('table.concat(TRACE, ",")'))

# 11. THE WHOLE PAD: every add-on file (tools/wowstub.py), all bags + the reagent bag, a non-empty cast bar, the radials' rows
sys.path.insert(0, os.path.join(ROOT, 'tools'))
from wowstub import F, SRC, ADDON_OF, ADDONS, boot_world  # noqa: E402
ORDER = [("WowCompanion", ["Safety", "Sink", "TapPad", "Harvest", "ActionBar", "Coords", "Map", "Trail", "MapArt", "Chat", "Bags", "Core"]),
         ("WowCompanionQuests", ["Quests", "Tracker"]), ("WowCompanionHub", ["Hub"]), ("WowCompanionInteract", ["Interact", "Dialogs"]),
         ("WowCompanionSpells", ["Spells", "Book", "Talents", "TalentsCtl", "PadMap", "PadTabCtl", "Radial"]),
         ("WowCompanionCharacter", ["Character", "Auras"]), ("WowCompanionSocial", ["Social"]), ("WowCompanionCalendar", ["Calendar"]),
         ("WowCompanionGroupFinder", ["GroupFinder"]), ("WowCompanionProfessions", ["Professions"]), ("WowCompanionPvp", ["Pvp"])]
files = []
for dname, fs in ORDER:
    for f in fs:
        k = "x_" + dname + "_" + f
        p = os.path.join(ADDONS, dname, f + ".lua")
        if not os.path.exists(p):
            continue
        F[k] = p; SRC[k] = open(p, encoding="utf-8").read(); ADDON_OF[k] = dname; files.append(k)
PRE = ('C_Container = setmetatable({}, { __index = function() return STUB end }); '
       'C_Container.GetContainerNumSlots = function(b) if b >= 0 and b <= 5 then return 20 end return 0 end\n'
       'C_Container.GetContainerItemID = function(b, s) if s == 1 then return 2589 end end\n'
       'C_Container.GetContainerItemInfo = function(b, s) if s == 1 then return { itemID = 2589, stackCount = 2, quality = 1, iconFileID = 1 } end end\n'
       'C_Container.GetContainerItemLink = function() return nil end; C_Container.PickupContainerItem = function() end\n'
       'C_Container.GetContainerNumFreeSlots = function(b) return 10, 0 end\n'
       'Enum = setmetatable({ BagIndex = {} }, { __index = function() return STUB end }); Enum.BagIndex.ReagentBag = 5\n'
       'WowCompanion = WowCompanion or {}; WowCompanion.partyOk = true\n')
LOGIN = ('for _, a in ipairs({ "WowCompanion", "WowCompanionQuests", "WowCompanionHub", "WowCompanionInteract", "WowCompanionSpells", '
         '"WowCompanionCharacter", "WowCompanionSocial", "WowCompanionCalendar", "WowCompanionGroupFinder", "WowCompanionProfessions", '
         '"WowCompanionPvp" }) do fire("ADDON_LOADED", a) end; fire("PLAYER_LOGIN"); fire("PLAYER_ENTERING_WORLD", true, false); runTimers(NOW + 10)\n'
         'for b = 0, 5 do pcall(fire, "BAG_UPDATE", b) end; pcall(fire, "BAG_UPDATE_DELAYED"); runTimers(NOW + 20)\n'
         'pcall(WowCompanion.collectBags); runTimers(NOW + 40); pcall(function() WowCompanion.pad.layout() end); runTimers(NOW + 50)')
W, errs = boot_world(files=tuple(files), pre=PRE, strict=False, login=LOGIN, expose=False)
who = W.eval('(UnitName("player") or "?") .. "-" .. (GetRealmName() or "?")')
W.execute('WowCompanionSpellsDB = WowCompanionSpellsDB or {}; WowCompanionSpellsDB.castBar = WowCompanionSpellsDB.castBar or {}; '
          'WowCompanionSpellsDB.castBar["%s"] = { [1] = 403 }; pcall(function() WowCompanion.castBar.sync() end)' % who)
check(W.eval('WowCompanion.pad.rows.castbar ~= nil'), "the cast bar (one spell) registers its row")
for nm, n in (("radial", 9), ("classr", 25)):          # the radials' lazy secure rows, as they register on the device
    if not W.eval('WowCompanion.pad.rows["%s"] ~= nil' % nm):
        W.execute('WowCompanion.pad.secureRow("%s", %d, function() end, function() end)' % (nm, n))
names = {r: W.eval('(function() local x = WowCompanion.pad.byRow[%d]; return x and x.name or false end)()' % r) for r in range(56)}
used = {r: n for r, n in names.items() if n}
print('rows:', ' '.join('%d:%s' % (r, n) for r, n in sorted(used.items())))
want = ['bag%d' % b for b in range(6)] + ['bagsort', 'bmvq', 'bmv', 'radial', 'classr', 'castbar']
check(all(w in used.values() for w in want), "every bag row, bmvq, bmv, radial, classr, castbar has a row", [w for w in want if w not in used.values()])
check(not any(str(n).startswith('bmv') and n not in ('bmv', 'bmvq') for n in used.values()), "no per-bag drag row on the pad")
check(names[55] == 'bmv', "bmv is on the last row (55)", names[55])
check(W.eval('WowCompanion.pad.describe ~= nil'), "the pad describes itself")


def fit(row, top):
    return 12 + (row + 1) * 2 <= top - 1


thor = [n for r, n in used.items() if not fit(r, 126.6)]
small = [n for r, n in used.items() if not fit(r, 121.5)]
check(len(used) <= 56 and thor == [], "Thor UI scale (popupTop 126.6): every row placed", (len(used), thor))
check('bmv' in small and 'bmvq' in [n for r, n in used.items() if fit(r, 121.5)], "smallest scale (121.5): bmv drops, bmvq stays", small)
print('rows used:', len(used), '| smallest scale drops:', small)

print('\n%d passed, %d failed' % (passes, fails))
sys.exit(1 if fails else 0)
