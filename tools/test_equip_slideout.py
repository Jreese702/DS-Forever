"""bags-gear1 / Character c19, THE EQUIP SLIDE-OUT (the user, 2026-11-16: "Choose which ring or weapon slot to replace"; "X opens a
slide-out of ARMOR (item icons) next to the item. It lists the options for that slot, plus a blank entry that unequips."; "The Y text
menu stays for enchant and bind (use-effect items)."; the picks D1a-D6a). The add-on's half, off-device: the REAL add-on files in
Lua 5.1 with stand-in game functions.

PART 1 (WowCompanion/Bags.lua alone, a stand-in game: bags, worn gear, one cursor, the game's pop-ups):
  - X on a RING (two places to go), out of a fight, no window: the bag bar's chooser in its gear kind (mode "equip", eq.gear, the two
    worn slots as targets); the start is the empty slot, else the lower item level; the D-pad left / right walks it, up / down nothing;
  - A equips into THE CHOSEN slot (EquipCursorItem(slot), the drag's own way: equipTo) and the room is back on its grid;
  - B = back, nothing moved; a finger: bmvq 26 then column slot - 1 picks the target;
  - the game's "bind it to you?": mode "bindask", A / B passed to the game, then its answer read off the slot (equipped / cancelled);
  - ONE place to go (a chest, a two-hander, a one-hander for a class that can't dual wield), a FIGHT, a game WINDOW open: X is the
    slot's secure cell as before (ctl.use); a dual wielder's one-hander has both hands; a piece the game won't let this character
    wear (nouse): nothing opens ("equip-nouse");
  - the tooltip rides with the cursor's own message while the slide-out is up (tipKey);
  - C10: a worn-slot column (0..18) while the room holds outside its grid with no fresh 26 is swallowed (never a pick of the worn
    piece); on the grid it is still the drag's pick;
  - the worn rows say their equip location (`equip`);
  - the Unequip arm's "bind" kind: the next column goes to WC.character.bindWorn (never an unequip); late = said late.
PART 2 (Bags.lua + Character.lua + the REAL Spellbook files, tools/wowstub.py's world, as tools/test_bags_bindbox.py):
  - Character c19: wbind (the arm), then the worn slot's bmvq column, opens the Spellbook's Bind box for THAT piece, hosted by the
    Character room (the box's document: via "character", bitem = the worn piece); eqs box = true; its extra keys asked under the
    room's name; every press goes to the box (ZL + D-pad Right binds it, A keeps), the room is back (eqs box false); a finger's
    boxa / boxb are the box's A / B; B in the box backs out without leaving the room; the controller lost closes the box quietly;
  - the arm's cells are kept by the sets' retain; in a fight nothing opens; the eqs part carries wb / ba / bb.
  - no trace: no SetBinding / macro / CVar / Blizzard open-close / StaticPopup call, no lockdown breach.
Run: python -B tools/test_equip_slideout.py
"""
import json
import os
import re
import sys
from lupa import lua51

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ADD = os.path.join(ROOT, 'companion', 'app', 'src', 'main', 'assets', 'companion', 'addons')
BAGS = open(os.path.join(ADD, 'WowCompanion', 'Bags.lua'), encoding='utf-8').read()
CHAR = open(os.path.join(ADD, 'WowCompanionCharacter', 'Character.lua'), encoding='utf-8').read()
passes = fails = 0


def check(ok, what, got=None):
    global passes, fails
    if ok:
        passes += 1; print('PASS', what)
    else:
        fails += 1; print('FAIL', what, '->', got)


# ====================================================================================== PART 1: Bags.lua, the stand-in game
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
EMITS = {}
OPS, SECURE = {}, {}
PAD = { ns = {} }
function PAD.op(name, ns, fn) OPS[name] = fn end
function PAD.secureRow(name, n, init, onClick) SECURE[name] = { n = n, init = init, onClick = onClick }; return 1 end
function PAD.describe() return { rows = {} } end
function PAD.onLayout() end
function PAD.slotFor(ns, key)
    if key == nil then return nil end
    local t = PAD.ns[ns]
    if not t then t = { byKey = {}, byCol = {} }; PAD.ns[ns] = t end
    if t.byKey[key] then return t.byKey[key] end
    for col = 0, 39 do if t.byCol[col] == nil then t.byKey[key] = col; t.byCol[col] = key; return col end end
end
function PAD.retain() end
WowCompanion = {
    S = function(fn) local ok, v = pcall(fn); if ok then return v end end,
    SN = function(fn) local ok, v = pcall(fn); if ok and type(v) == "number" then return v end end,
    CALL = function(fn) return pcall(fn) end,
    emit = function(t) EMITS[#EMITS + 1] = t end,
    send = function() end, encode = function() return "" end, push = function() end, isPlain = function() return true end,
    pad = PAD, VERSION = "test",
}
COMBAT = false
function InCombatLockdown() return COMBAT end
function UnitAffectingCombat() return COMBAT end
function UnitName() return "Shamy" end
function GetRealmName() return "Forever" end
LEVEL = 20
function UnitLevel() return LEVEL end
TRACE = {}
for _, n in ipairs({ "SetBinding", "SaveBindings", "DeleteMacro", "CreateMacro", "EditMacro", "SetCVar", "ShowUIPanel", "HideUIPanel",
                     "ToggleCharacter", "CloseAllBags", "OpenAllBags", "ToggleBackpack", "StaticPopup_Show", "StaticPopup_Hide",
                     "SpellStopTargeting", "UseContainerItem", "EquipItemByName", "PickupInventoryItem" }) do
    _G[n] = function() TRACE[#TRACE + 1] = n end
end
function GetNumMacros() return 0, 0 end
function SetOverrideBindingClick() end
function SetOverrideBinding() end
function GetBindingAction() return "" end
bit = { band = function(a, b)
    local r, p = 0, 1
    while a > 0 and b > 0 do
        local x, y = a % 2, b % 2
        if x == 1 and y == 1 then r = r + p end
        a, b, p = (a - x) / 2, (b - y) / 2, p * 2
    end
    return r
end }
-- THE GAME: the backpack (8 slots), worn gear, ONE cursor, the game's pop-ups
NSLOTS = { [0] = 8 }
-- 7001 a ring, 7002 a ring this character can't wear, 7101 a trinket, 7201 a one-hander, 2581 a chest, 7301 a two-hander
BAG = { [0] = { [1] = 7001, [2] = 7002, [3] = 7101, [4] = 7201, [5] = 2581, [6] = 7301 } }
WORN = { [5] = 2580, [11] = 7050, [13] = 7150, [14] = 7151, [16] = 7250, [17] = 7260 }
EQUIP = { [7001] = "INVTYPE_FINGER", [7002] = "INVTYPE_FINGER", [7050] = "INVTYPE_FINGER", [7101] = "INVTYPE_TRINKET",
          [7150] = "INVTYPE_TRINKET", [7151] = "INVTYPE_TRINKET", [7201] = "INVTYPE_WEAPON", [7250] = "INVTYPE_WEAPON",
          [7260] = "INVTYPE_SHIELD", [2580] = "INVTYPE_CHEST", [2581] = "INVTYPE_CHEST", [7301] = "INVTYPE_2HWEAPON" }
ILVL = { [7050] = 20, [7150] = 10, [7151] = 25, [7001] = 22, [7101] = 18 }
CANUSE = { [7002] = false }
DW = false
function CanDualWield() return DW end
ST = { cursor = nil }
POPUP = nil          -- the game's StaticPopup1: nil = none, else its `which`
StaticPopup1 = { IsShown = function() return POPUP ~= nil end }
setmetatable(StaticPopup1, { __index = function(_, k) if k == "which" then return POPUP end end })
for i = 2, 4 do _G["StaticPopup" .. i] = { IsShown = function() return false end } end
BINDS = {}           -- item ids the game asks "bind it to you?" for when equipped
EQUIPS = {}          -- every EquipCursorItem(slot) the add-on made
function EquipCursorItem(slot)
    EQUIPS[#EQUIPS + 1] = slot
    if not ST.cursor then return end
    if BINDS[ST.cursor.id] then POPUP = "EQUIP_BIND"; ST.pending = slot; return end          -- the game asks; the piece stays on the cursor
    local was = WORN[slot]; WORN[slot] = ST.cursor.id; BAG[ST.cursor.bag][ST.cursor.slot] = was; ST.cursor = nil
end
-- the player answers the game's own question (its own A / B, passed to it)
function answer(yes)
    if yes and ST.cursor and ST.pending then
        local slot = ST.pending
        local was = WORN[slot]; WORN[slot] = ST.cursor.id; BAG[ST.cursor.bag][ST.cursor.slot] = was; ST.cursor = nil
    end
    ST.pending = nil; POPUP = nil
end
function CursorHasItem() return ST.cursor ~= nil end
function GetCursorInfo() if ST.cursor then return "item", ST.cursor.id end end
function ClearCursor() ST.cursor = nil end
C_Container = {
    GetContainerItemID = function(b, s) return BAG[b] and BAG[b][s] end,
    GetContainerNumSlots = function(b) return NSLOTS[b] or 0 end,
    GetContainerNumFreeSlots = function(b) local n = 0; for s = 1, NSLOTS[b] or 0 do if not BAG[b][s] then n = n + 1 end end; return n, 0 end,
    GetContainerItemInfo = function(b, s) local id = BAG[b] and BAG[b][s]; if id then return { itemID = id, iconFileID = 1, quality = 2, stackCount = 1 } end end,
    GetContainerItemLink = function(b, s) local id = BAG[b] and BAG[b][s]; if id then return "|Hitem:" .. id .. "|h" end end,
    PickupContainerItem = function(b, s) if not ST.cursor and BAG[b][s] then ST.cursor = { bag = b, slot = s, id = BAG[b][s] } end end,
}
local function idOf(link) return tonumber(tostring(link):match("item:(%d+)")) end
C_Item = { GetItemNameByID = function(id) return "Item " .. id end, GetItemIconByID = function() return 1 end,
           GetItemInfoInstant = function(id) return id, "Armor", "Misc", EQUIP[id] or "", 1, 4, 0 end,
           GetItemFamily = function() return 0 end, GetItemSpell = function() return nil end, RequestLoadItemDataByID = function() end,
           GetDetailedItemLevelInfo = function(link) return ILVL[idOf(link)] or 5 end }
function GetItemInfo(id) return "Item " .. id, "|Hitem:" .. id .. "|h", 2, 10, 1 end
C_PlayerInfo = { CanUseItem = function(id) if CANUSE[id] == nil then return true end; return CANUSE[id] end }
function GetInventoryItemID(unit, slot) return WORN[slot] end
function GetInventoryItemLink(unit, slot) if WORN[slot] then return "|Hitem:" .. WORN[slot] .. "|h" end end
function GetInventoryItemTexture(unit, slot) if WORN[slot] then return 100 + slot end end
function GetInventoryItemQuality(unit, slot) if WORN[slot] then return 2 end end
for _, n in ipairs({ "MerchantFrame", "GossipFrame", "QuestFrame", "BankFrame", "MailFrame", "TradeFrame", "AuctionHouseFrame",
                     "GuildBankFrame", "ClassTrainerFrame", "CharacterFrame" }) do _G[n] = { IsShown = function() return false end } end
PASSES, SPEC = {}, nil
WowCompanion.capture = { version = 11, take = function(id, spec) if id == "bags" then SPEC = spec end; return true end,
    pass = function(id, keys) PASSES[#PASSES + 1] = table.concat(keys, " "); return true end, release = function() return true end,
    state = function() return { holder = "bags" } end, refused = function() return true end }
-- the game's own tooltip (Interact's WC.tipOf): one line per item
TIPS = 0
WowCompanion.tipOf = function(key) TIPS = TIPS + 1; return { key = key, lines = { { t = "Item " .. (key:match("(%d+)$") or "?") } } } end
-- the Character room's half of the Bind arm (Character c19's bindWorn), recorded
BINDWORN = {}
WowCompanion.character = { bindWorn = function(slot, late) BINDWORN[#BINDWORN + 1] = tostring(slot) .. (late and " late" or "") end }
function doc() return WowCompanion._bagsCtlDoc() end
function press(k) SPEC.press(k) end
function lastMove() for i = #EMITS, 1, -1 do local e = EMITS[i]; if e.bagsMove then return e.bagsMove end end end
''')
load = L.eval('function(src, name, addon) local f, e = loadstring(src, "@" .. name); if not f then error(e) end; return f(addon) end')
try:
    load(BAGS, 'Bags.lua', 'WowCompanion')
    ok_load = True
except Exception as e:  # noqa: BLE001
    ok_load = False; print(e)
check(ok_load, "Bags.lua loads (stand-in game) with no error")
L.execute('WowCompanionDB = {}; fire("PLAYER_LOGIN"); WowCompanion._bagsLayout = { { bag = 0, slots = 8 } }; WowCompanion.bagsPadRows({ { bag = 0, slots = 8 } })')
check('bags-gear1' in BAGS and '"Choose which ring or weapon slot to replace"' in BAGS, "Bags.lua's header note (bags-gear1) quotes the user")


def cursor(slot):
    """the controller's cursor onto backpack slot [slot] (a finger: bmvq 36, the bag's name, the bmv cell)"""
    L.execute('NOW = NOW + 0.5; OPS.bmvq(nil, 36); OPS.bagsort(nil, 10); OPS.bmv(nil, %d)' % (slot - 1))


L.execute('NOW = 200; OPS.bmvq(nil, 29)')                    # the Bags room entered by touch: the controller
check(L.eval('doc().on') is True and L.eval('SPEC ~= nil'), "the Bags room holds the controller")

# ---- a ring: two places to go
cursor(1)
check(L.eval('doc().cur.slot') == 1, "the cursor on the ring (slot 1)")
L.execute('run(0.3)')                                        # the cursor rests: the ring's tooltip is read and kept (TIP.REST)
L.execute('press("PAD3")')
d = L.eval('doc()')
eq = d.eq
check(d.mode == 'equip' and eq is not None and eq.gear is True and eq.bag == 0 and eq.slot == 1 and eq.id == 7001,
      "X on a ring: the slide-out (mode equip, the gear kind, the ring's own bag slot)", (d.mode, eq and eq.gear))
tg = [eq.targets[i] for i in range(1, len(eq.targets) + 1)] if eq is not None and eq.targets is not None else []
check([t.inv for t in tg] == [11, 12] and tg[0].id == 7050 and tg[1].id is None and tg[0].ok is True and tg[1].ok is True,
      "...its targets: Finger 1 (the worn ring) and Finger 2 (empty)", [(t.inv, t.id) for t in tg])
check(eq.at == 2, "...the cursor starts on the EMPTY ring slot (the bag bar's rule)", eq.at)
check(L.eval('ST.cursor') is None and len(list(L.eval('EQUIPS').values())) == 0, "...nothing in the hand, nothing equipped yet")
check(L.eval('doc().tip ~= nil') and L.eval('doc().tip.key') == 'bag 0 1 7001', "...the ring's tooltip rides with the slide-out (tipKey)",
      L.eval('doc().tip and doc().tip.key'))
L.execute('press("PADDLEFT")')
check(L.eval('doc().eq.at') == 1, "D-pad left: Finger 1")
L.execute('press("PADDLEFT")')
check(L.eval('doc().eq.at') == 1, "...clamped at the first")
L.execute('press("PADDDOWN")')
check(L.eval('doc().eq.at') == 2 and L.eval('doc().mode') == 'equip', "bags-gear2: the slot menu's rows: D-pad down, Finger 2")
L.execute('press("PADDUP"); press("PADDUP")')
check(L.eval('doc().eq.at') == 1 and L.eval('doc().mode') == 'equip', "...up: Finger 1, clamped at the first")
L.execute('press("PADDRIGHT")')
check(L.eval('doc().eq.at') == 2, "D-pad right: Finger 2")
L.execute('NOW = NOW + 1; press("PAD1")')
d = L.eval('doc()')
check(list(L.eval('EQUIPS').values()) == [12] and L.eval('WORN[12]') == 7001 and L.eval('BAG[0][1]') is None and L.eval('ST.cursor') is None,
      "A: the ring into THE CHOSEN slot (EquipCursorItem(12)), nothing left in the hand", (list(L.eval('EQUIPS').values()), L.eval('WORN[12]')))
check(d.mode == 'grid' and d.note == 'equipped' and d.cur.slot == 1 and d.eq is None, "...and the room is back on its grid, on that slot",
      (d.mode, d.note, d.cur.slot))
check(L.eval('WORN[11]') == 7050, "...the other ring stays where it was")

# ---- a trinket: both worn, the lower item level first; a finger picks the target; B = back
cursor(3)
L.execute('press("PAD3")')
eq = L.eval('doc().eq')
check(eq.gear is True and eq.at == 1 and [eq.targets[i].inv for i in (1, 2)] == [13, 14],
      "X on a trinket with both worn: starts on the lower item level (Trinket 1, 10 < 25)", (eq.at,))
L.execute('NOW = NOW + 0.2; OPS.bmvq(nil, 26); OPS.bmvq(nil, 13)')
check(L.eval('doc().eq.at') == 2 and L.eval('doc().mode') == 'equip', "a finger: bmvq 26, then column 13 = Trinket 2 (slot 14)",
      L.eval('doc().eq.at'))
L.execute('NOW = NOW + 0.2; OPS.bmvq(nil, 26); OPS.bmvq(nil, 30 - 30 + 4)')
check(L.eval('doc().eq.at') == 2, "...a column that is no target changes nothing")
# C10: a worn-slot column with no fresh 26 while the room is not on its grid: swallowed, never a pick
L.execute('NOW = NOW + 5; OPS.bmvq(nil, 12)')
mv = L.eval('lastMove()')
check(mv.why == 'swallowed' and L.eval('doc().mode') == 'equip' and L.eval('doc().eq.at') == 2,
      "C10: column 12 with no fresh 26 (the slide-out up): swallowed, the target unchanged", (mv.why, mv.picked))
L.execute('NOW = NOW + 0.2; OPS.bmvq(nil, 26); NOW = NOW + 3; OPS.bmvq(nil, 12)')
check(L.eval('lastMove().why') == 'swallowed' and L.eval('doc().eq.at') == 2, "C10: a 26 more than 2 s before its column: swallowed too")
n_eq = len(list(L.eval('EQUIPS').values()))
L.execute('press("PAD2")')
d = L.eval('doc()')
check(d.mode == 'grid' and d.note == 'cancelled' and d.cur.slot == 3 and len(list(L.eval('EQUIPS').values())) == n_eq
      and L.eval('BAG[0][3]') == 7101, "B: back to the grid on the trinket, nothing moved", (d.mode, d.note))
# on the grid a worn-slot column is still the drag's pick (as before)
L.execute('NOW = NOW + 1; OPS.bmvq(nil, 12)')
check((L.eval('lastMove().picked') or {}) and L.eval('lastMove().picked.inv') == 13, "on the grid: column 12 is still the drag's pick of slot 13",
      L.eval('lastMove().why'))
L.execute('NOW = NOW + 20')        # the pick goes stale

# ---- the game's "bind it to you?"
cursor(3)
L.execute('BINDS[7101] = true; press("PAD3"); press("PADDRIGHT"); NOW = NOW + 1; press("PAD1")')
d = L.eval('doc()')
check(d.mode == 'bindask' and L.eval('POPUP') == 'EQUIP_BIND' and L.eval('ST.cursor and ST.cursor.id') == 7101,
      "the game asks 'bind it to you?': mode bindask, the piece still in the hand", (d.mode, L.eval('POPUP')))
check(L.eval('PASSES[#PASSES]') == 'PAD1 PAD2', "...A and B go to the game (its own question)", L.eval('PASSES[#PASSES]'))
L.execute('run(1)')
check(L.eval('doc().mode') == 'bindask', "...still asking a second later: waiting")
L.execute('answer(true); run(1.5)')
d = L.eval('doc()')
check(d.mode == 'grid' and d.note == 'equipped' and L.eval('WORN[14]') == 7101 and L.eval('PASSES[#PASSES]') != 'PAD1 PAD2',
      "a Yes: the trinket in Trinket 2, equipped, A / B the room's again", (d.mode, d.note, L.eval('WORN[14]')))
cursor(3)                                                    # the old trinket (7151) now lies in slot 3
L.execute('BINDS[7151] = true; press("PAD3"); NOW = NOW + 1; press("PAD1")')
check(L.eval('doc().mode') == 'bindask', "again, the old trinket: the game asks")
L.execute('answer(false); run(1.5)')
d = L.eval('doc()')
check(d.mode == 'grid' and d.note == 'equip-cancelled' and L.eval('BAG[0][3]') == 7151 and L.eval('ST.cursor') is None,
      "a No: nothing equipped, the piece back in its slot (equip-cancelled)", (d.mode, d.note, L.eval('BAG[0][3]')))
L.execute('BINDS = {}')

# ---- one place to go, a fight, a window, nouse: no slide-out
cursor(5)
L.execute('press("PAD3")')
d = L.eval('doc()')
check(d.mode == 'grid' and d.use is not None and d.use.slot == 5 and d.eq is None, "a chest (one place to go): X is the secure cell as before",
      (d.mode, d.use and d.use.slot))
cursor(6)
L.execute('press("PAD3")')
check(L.eval('doc().mode') == 'grid' and L.eval('doc().use.slot') == 6, "a two-hander (the main hand only): the secure cell")
cursor(4)
L.execute('press("PAD3")')
check(L.eval('doc().mode') == 'grid' and L.eval('doc().use.slot') == 4, "a one-hander for a class that can't dual wield: the secure cell")
L.execute('DW = true; press("PAD3")')
eq = L.eval('doc().eq')
check(L.eval('doc().mode') == 'equip' and eq.gear is True and [eq.targets[i].inv for i in (1, 2)] == [16, 17],
      "a dual wielder's one-hander: the slide-out, main hand and off hand", L.eval('doc().mode'))
L.execute('press("PAD2"); DW = false')
cursor(2)
use0 = L.eval('doc().use and doc().use.seq')
L.execute('press("PAD3")')
d = L.eval('doc()')
check(d.mode == 'grid' and d.note == 'equip-nouse' and (d.use.seq if d.use is not None else None) == use0,
      "a ring this character can't wear (nouse): nothing opens (equip-nouse), no secure tap", (d.mode, d.note))
L.execute('BAG[0][1] = 7050')                                 # a ring again in slot 1 (the one taken off)
cursor(1)
L.execute('COMBAT = true; press("PAD3")')
d = L.eval('doc()')
check(d.mode == 'grid' and d.use is not None and d.use.slot == 1, "in a fight: a ring's X is the secure cell (the game's rules)",
      (d.mode, d.use and d.use.slot))
L.execute('COMBAT = false; fire("MERCHANT_SHOW"); press("PAD3")')
d = L.eval('doc()')
check(d.mode == 'grid' and d.use is not None and d.use.slot == 1, "a vendor open: X stays the game's right-click (Sell)", d.mode)
L.execute('fire("MERCHANT_CLOSED")')
# the fight starts while the slide-out is up: A is refused, nothing moves
L.execute('press("PAD3")')
check(L.eval('doc().mode') == 'equip', "the slide-out again (no window)")
L.execute('COMBAT = true; NOW = NOW + 1; press("PAD1")')
check(L.eval('doc().mode') == 'equip' and L.eval('doc().note') == 'equip-combat' and L.eval('BAG[0][1]') == 7050,
      "a fight started meanwhile: A refused (equip-combat), nothing moved", L.eval('doc().note'))
L.execute('COMBAT = false; press("PAD2")')
# the piece moved away under the slide-out: A says so, nothing equipped
L.execute('press("PAD3"); BAG[0][1] = nil; BAG[0][7] = 7050; NOW = NOW + 1; press("PAD1")')
check(L.eval('doc().mode') == 'grid' and L.eval('doc().note') == 'moved' and L.eval('WORN[12]') == 7001,
      "the ring moved meanwhile: 'moved', nothing equipped", L.eval('doc().note'))

# ---- the worn rows say their equip location
eqd = L.eval('WowCompanion.collectEquipped()')
rows = {eqd.slots[i].slot: eqd.slots[i] for i in range(1, len(eqd.slots) + 1)}
check(rows[16].equip == 'INVTYPE_WEAPON' and rows[17].equip == 'INVTYPE_SHIELD' and rows[11].equip == 'INVTYPE_FINGER' and rows[3].equip is None,
      "the worn rows carry their equip location (the Character room's two-hand rule)", (rows[16].equip, rows[17].equip))

# ---- the Unequip arm's "bind" kind (Character c19): the slot goes to bindWorn, never an unequip
L.execute('NOW = NOW + 5; WowCompanion._bagsUnequipArm("bind"); NOW = NOW + 0.2; OPS.bmvq(nil, 12)')
check(list(L.eval('BINDWORN').values()) == ['13'] and L.eval('WORN[13]') == 7150, "the bind arm, then column 12: bindWorn(13), nothing taken off",
      list(L.eval('BINDWORN').values()))
L.execute('NOW = NOW + 0.1; OPS.bmvq(nil, 12)')
check(len(list(L.eval('BINDWORN').values())) == 1 and L.eval('doc().mode') == 'grid', "...the doubled press is swallowed")
L.execute('NOW = NOW + 5; WowCompanion._bagsUnequipArm("bind"); NOW = NOW + 3; OPS.bmvq(nil, 10)')
check(list(L.eval('BINDWORN').values())[-1] == '11 late', "a column after the arm's 2 s: said late, nothing opens", list(L.eval('BINDWORN').values()))
L.execute('NOW = NOW + 5; WowCompanion._bagsUnequipArm(); NOW = NOW + 0.2')
check(L.eval('WowCompanion._bagsBindWait') is not None, "the plain arm still exists (unequip)")

check(L.eval('#TRACE') == 0, "part 1, no trace: no SetBinding / macro / CVar / Blizzard open-close / StaticPopup / EquipItemByName call",
      list(L.eval('TRACE').values()))

# ====================================================================================== PART 2: Character c19 hosts the REAL Bind box
F = {
    "safety": os.path.join(ADD, "WowCompanion", "Safety.lua"),
    "tappad": os.path.join(ADD, "WowCompanion", "TapPad.lua"),
    "book": os.path.join(ADD, "WowCompanionSpells", "Book.lua"),
    "padmap": os.path.join(ADD, "WowCompanionSpells", "PadMap.lua"),
    "padtabctl": os.path.join(ADD, "WowCompanionSpells", "PadTabCtl.lua"),
    "bags": os.path.join(ADD, "WowCompanion", "Bags.lua"),
    "character": os.path.join(ADD, "WowCompanionCharacter", "Character.lua"),
}
SRC = {k: open(p, encoding="utf-8").read() for k, p in F.items()}
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from wowstub import GAME  # noqa: E402
PARTY = open(os.path.join(ROOT, "tools", "test_party.py"), encoding="utf-8").read()
WORLD_HELPERS = re.search(r"\nWORLD_HELPERS = r'''(.*?)'''", PARTY, re.S).group(1)
STICKS = open(os.path.join(ROOT, "tools", "test_stick_binds.py"), encoding="utf-8").read()
EXTRA = re.search(r"\nEXTRA = r'''(.*?)'''", STICKS, re.S).group(1)
AFTER = re.search(r"\nAFTER = r'''(.*?)'''", STICKS, re.S).group(1)
BINDBOX = open(os.path.join(ROOT, "tools", "test_bags_bindbox.py"), encoding="utf-8").read()
MORE = re.search(r"\nMORE = r'''(.*?)'''", BINDBOX, re.S).group(1)
WORLD = r'''
BAG = { [0] = { [1] = 6948 } }
C_Container = { GetContainerItemID = function(b, s) return BAG[b] and BAG[b][s] end,
                GetContainerNumSlots = function(b) return b == 0 and 4 or 0 end,
                GetContainerNumFreeSlots = function(b) return b == 0 and 3 or 0, 0 end,
                GetContainerItemInfo = function(b, s) if BAG[b] and BAG[b][s] then return { iconFileID = 1, quality = 1, stackCount = 1 } end end }
for _, n in ipairs({ "MerchantFrame", "GossipFrame", "QuestFrame", "BankFrame", "MailFrame", "TradeFrame", "AuctionHouseFrame",
                     "GuildBankFrame", "ClassTrainerFrame", "CharacterFrame" }) do _G[n] = { IsShown = function() return false end } end
-- a worn trinket with a use effect (Insignia of the Alliance, say: item 18854) in slot 13
WORNC = { [13] = 18854 }
function GetInventoryItemID(unit, slot) return WORNC[slot] end
local nameOf = C_Item.GetItemNameByID
C_Item.GetItemNameByID = function(id) if id == 18854 then return "Insignia of the Alliance" end return nameOf(id) end
C_Item.GetItemIconByID = function(id) return 133452 end
-- the capture takes the Character room too (one holder: the last take)
local take0 = WowCompanion.capture.take
WowCompanion.capture.take = function(guest, spec) if guest == "character" then CSPEC = spec end; return take0(guest, spec) end
WowCompanion.capture.state = function() return { holder = HOLDER } end
WowCompanion.capture.version = 11
'''
fails2 = 0


def boot():
    W = lua51.LuaRuntime(unpack_returned_tuples=True)
    W.execute(GAME)
    W.execute(EXTRA)
    W.execute(MORE)
    W.execute(WORLD)
    ld = W.eval('function(src, name) local f, e = loadstring(src, name); if not f then error(e) end; return f end')
    for k in ("safety", "tappad", "book", "padmap", "padtabctl", "bags", "character"):
        ld(SRC[k], '@' + os.path.basename(F[k]))(os.path.basename(F[k]))
    W.execute('WowCompanion._bagsLayout = { { bag = 0, slots = 4 } }')
    W.execute(WORLD_HELPERS)
    W.execute('fire("PLAYER_LOGIN"); fire("PLAYER_ENTERING_WORLD", true, false); runTimers(NOW + 10)')
    W.execute(AFTER)
    W.execute('local c = WowCompanion._spellsBook.ctl; c.croom, c.ctrlTab, c.onTabs = false, false, false')
    W.execute('BK = WowCompanion._spellsBook; HB = WowCompanion.spellsBindHosted; CH = WowCompanion.character')
    W.execute('WowCompanion.padMap.emit(true); runTimers(NOW + 1)')
    W.execute('function tapSet(key) CH.onSetCell(CH.eqFirst + WowCompanion.pad.slotFor("eqset", key)) end')
    W.execute('function eqs() return CH.collect.eqs() end')
    W.execute('function bmvq(col) WowCompanion.pad.rows.bmvq.handler(nil, col) end')
    return W


def docs(W, typ):
    out = []
    for i in range(1, int(W.eval('#SENT')) + 1):
        try:
            d = json.loads(W.eval('SENT[%d]' % i))
        except Exception:
            continue
        if isinstance(d, dict) and d.get('type') == typ:
            out.append(d)
    return out


def sctl(W):
    d = docs(W, 'spellsctl')
    return d[-1]['ctl'] if d else {}


def cpress(W, key, trig=None):
    W.execute('hold(%s)' % ('nil' if trig is None else '"%s"' % trig))
    W.execute('CSPEC.press("%s")' % key)
    W.execute('hold(nil)')


W = None
try:
    W = boot()
    booted = True
except Exception as e:  # noqa: BLE001
    booted = False; print(e)
check(booted, "part 2: the Spellbook, Bags and Character files load in the stub world")
if W is not None:
    check(W.eval('CH.version') == 'c21', "Character's version is c21 (the hands' swap in a fight, on top of c20's arm)", W.eval('CH.version'))
    W.execute('NOW = NOW + 1; CH.touchOpen()')
    check(W.eval('HOLDER') == 'character' and W.eval('CH.ctl.on') is True, "the Character room holds the controller (touch)", W.eval('HOLDER'))
    e = W.eval('eqs()')
    check(e.wb is not None and e.ba is not None and e.bb is not None and len({e.wb, e.ba, e.bb, e.uq, e.mon, e.moff, e.msb, e.ws}) == 8
          and e.box is False, "eqs: the c19 cells (wbind / boxa / boxb), each its own, box false", (e.wb, e.ba, e.bb))
    W.execute('CH.collect.sets()')
    check(W.eval('WowCompanion.pad.ns.eqset.byKey.wbind') is not None and W.eval('WowCompanion.pad.ns.eqset.byKey.boxb') is not None,
          "the sets' retain keeps the c19 cells")
    # the Y menu's Bind: the arm, then Trinket 1's column (12)
    W.execute('tapSet("moreon")')
    check(W.eval('eqs().more') is True, "the Y menu opened (moreon)")
    W.execute('tapSet("wbind"); NOW = NOW + 0.2; bmvq(12)')
    c = sctl(W)
    e = W.eval('eqs()')
    check(c.get('on') is True and c.get('via') == 'character' and c.get('mode') == 'bind' and c.get('bitem', {}).get('id') == 18854,
          "wbind, then column 12: the Spellbook's Bind box for the worn trinket, hosted by the Character room (via character)",
          (c.get('via'), c.get('bitem')))
    check(e.box is True and e.more is False, "...eqs box true, the menu closed under it", (e.box, e.more))
    check(W.eval('EXTRA_BY') == 'character' and 'PADLSTICK' in (W.eval('EXTRA_LIST') or ''),
          "...its extra keys (Select / L3 / R3) asked of the capture under the room's name", (W.eval('EXTRA_BY'), W.eval('EXTRA_LIST')))
    s23 = W.eval('BK.storageFor(1, 2, 3)')
    cpress(W, "PADDRIGHT", "LT")
    check(W.eval('ACTIONINFO[%d] and ACTIONINFO[%d][2]' % (s23, s23)) == 18854 and W.eval('BK.ctl.mode') == 'confirm',
          "the room hands the press to the box: the trinket on ZL + D-pad Right, Keep / Undo", W.eval('BK.ctl.mode'))
    cpress(W, "PAD1")
    check(W.eval('eqs().box') is False and sctl(W).get('on') is False and W.eval('HOLDER') == 'character' and W.eval('CH.ctl.on') is True,
          "A keeps it: the box ends, the Character room is back and still holds", (W.eval('eqs().box'), W.eval('HOLDER')))
    check(W.eval('EXTRA_LIST') == '', "...the extra keys given back", W.eval('EXTRA_LIST'))
    # a finger: the box's own A / B (boxa / boxb); B backs out without leaving the room
    W.execute('NOW = NOW + 1; tapSet("wbind"); NOW = NOW + 0.2; bmvq(12)')
    check(W.eval('eqs().box') is True, "the box again")
    W.execute('tapSet("boxb")')
    check(W.eval('eqs().box') is False and sctl(W).get('on') is False and W.eval('CH.ctl.on') is True and 'back' not in str(W.eval('CH.ctl.note')),
          "a finger's Back (boxb): the box backs out, the room stays", W.eval('CH.ctl.note'))
    W.execute('NOW = NOW + 1; tapSet("wbind"); NOW = NOW + 0.2; bmvq(12)')
    cpress(W, "PAD2")
    check(W.eval('eqs().box') is False and W.eval('CH.ctl.on') is True and 'back' not in str(W.eval('CH.ctl.note')),
          "B in the box: one step back (the box closes), never a leave", W.eval('CH.ctl.note'))
    W.execute('NOW = NOW + 1; tapSet("wbind"); NOW = NOW + 0.2; bmvq(12)')
    cpress(W, "PADDRIGHT", "LT")
    W.execute('tapSet("boxa")')
    check(W.eval('eqs().box') is False and W.eval('ACTIONINFO[%d] and ACTIONINFO[%d][2]' % (s23, s23)) is None,
          "a finger's Keep (boxa) after pressing the trinket's own button again: cleared for good, the box ends")
    # the controller lost with the box up: it closes quietly
    W.execute('NOW = NOW + 1; tapSet("wbind"); NOW = NOW + 0.2; bmvq(12)')
    check(W.eval('HB.up()') is True, "the box up again")
    W.execute('CSPEC.lost("taken")')
    check(W.eval('HB.up()') is False and W.eval('eqs().box') is False and sctl(W).get('on') is False,
          "the controller lost (another room): the box closes with it", sctl(W).get('note'))
    # in a fight nothing opens; an empty slot nothing either
    W.execute('NOW = NOW + 1; CH.touchOpen(); COMBAT = true; tapSet("wbind"); NOW = NOW + 0.2; bmvq(12)')
    check(W.eval('HB.up()') is False and W.eval('eqs().box') is False and W.eval('eqs().ack.op') == 'bind' and W.eval('eqs().ack.why') == 'combat',
          "in a fight: no box (the ack says combat)", (W.eval('eqs().ack.op'), W.eval('eqs().ack.why')))
    W.execute('COMBAT = false; NOW = NOW + 1; tapSet("wbind"); NOW = NOW + 0.2; bmvq(4)')
    check(W.eval('HB.up()') is False and W.eval('eqs().ack.why') == 'empty', "an empty slot: no box (empty)", W.eval('eqs().ack.why'))
    check(W.eval('#SPY') == 0 and W.eval('#VIOL') == 0 and list(W.eval('ERRS').values()) == [],
          "part 2: no spied call (no trace), no lockdown breach, no Lua error",
          (W.eval('SPY[1]'), W.eval('VIOL[1]'), list(W.eval('ERRS').values())[:2]))

print(f'\n{passes} passed, {fails} failed')
sys.exit(1 if fails else 0)
