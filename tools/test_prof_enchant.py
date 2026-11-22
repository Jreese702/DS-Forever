"""Professions p27 (#541, the player: "cant enchant"): the Professions room refused EVERY Enchanting recipe ("needs an item to enchant")
and nothing handed over to an item pick. Now ctl ench casts the selected enchant ONCE with the game's own CraftRecipe (the call Create
makes) and the game asks for its item (its pick, SpellIsTargeting), which the Bags room's "Choose an item" answers. Loads the real
WowCompanionProfessions/Professions.lua in Lua 5.1 with stand-in game functions and drives its control and select rows:
  - an enchant recipe selected, ctl ench: CraftRecipe(rid, 1) once; the ack ok with pick = true when the game asks at once;
  - the game's ask a frame late: the ack after the 0.3 s look, ok + pick;
  - no ask and no cast: refused ("the game asked for no item"); a cast with no ask (the game chose the item): ok, no pick;
  - refused: a pick already up (nothing cast), no reagents (nothing cast), a craft running, a recipe that is no enchant;
  - Create (make:1) still refuses an enchant and never casts it; a plain recipe's Create is unchanged;
  - the new key's column comes after every old one (kbd / nokbd keep theirs), inside the row's 40; the version tag p27 with its note;
  - no trace: no binding, macro, CVar, window call, and nothing targeted or applied by this file (no Pickup / Use / SpellTargetItem).
Run: python -B tools/test_prof_enchant.py
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
    if name then _G[name] = fr end
    return fr
end
function fire(ev, ...) for _, h in ipairs(HANDLERS) do if h.fr.ev[ev] then pcall(h.fn, h.fr, ev, ...) end end end
COMBAT = false
function InCombatLockdown() return COMBAT end
function UnitAffectingCombat() return COMBAT end
function UnitName() return "Shamy" end
function GetRealmName() return "Forever" end
TRACE = {}
for _, n in ipairs({ "SetBinding", "SaveBindings", "DeleteMacro", "CreateMacro", "EditMacro", "SetCVar", "ShowUIPanel", "HideUIPanel",
                     "ToggleProfessionsBook", "CloseTradeSkill", "SetOverrideBinding", "SetOverrideBindingClick", "PickupContainerItem",
                     "PickupInventoryItem", "UseContainerItem", "SpellTargetItem", "UseInventoryItem", "SpellStopTargeting",
                     "ReplaceEnchant", "CraftEnchant" }) do
    _G[n] = function() TRACE[#TRACE + 1] = n end
end
C_Container = { PickupContainerItem = function() TRACE[#TRACE + 1] = "C_Container.PickupContainerItem" end,
                UseContainerItem = function() TRACE[#TRACE + 1] = "C_Container.UseContainerItem" end }
-- the game's pick and cast, as the tests set them
TARGETING = false          -- SpellIsTargeting()
ASK_ON_CAST = "now"        -- what CraftRecipe does: "now" the pick at once, "late" a moment later, "cast" a cast with no pick, "none"
CASTING = false
CRAFTS = {}
function SpellIsTargeting() return TARGETING end
function UnitCastingInfo() if CASTING then return "Enchant", "", 0, 0, 1000 end return nil end
CAN = { [501] = 3, [502] = 5 }
RECIPES = {
    [501] = { learned = true, name = "Enchant Bracer - Minor Health", isEnchantingRecipe = true, categoryID = 1, icon = 1, relativeDifficulty = 1,
              numSkillUps = 1, maxTrivialLevel = 70, itemLevel = 0, canCreateMultiple = false, disabled = false },
    [502] = { learned = true, name = "Runed Copper Rod", isEnchantingRecipe = false, categoryID = 1, icon = 2, relativeDifficulty = 1,
              numSkillUps = 1, maxTrivialLevel = 70, itemLevel = 5, canCreateMultiple = true, disabled = false },
}
C_TradeSkillUI = {
    GetAllRecipeIDs = function() return { 501, 502 } end,
    GetRecipeInfo = function(id) return RECIPES[id] end,
    IsRecipeInSkillLine = function() return true end,
    GetCraftableCount = function(id) return CAN[id] or 0 end,
    IsRecipeTracked = function() return false end,
    GetRecipeRequirements = function() return {} end,
    GetRecipeCooldown = function() return 0 end,
    GetRecipeDescription = function() return "" end,
    GetRecipeSchematic = function() return nil end,
    IsTradeSkillReady = function() return true end,
    IsTradeSkillLinked = function() return false end, IsTradeSkillGuild = function() return false end,
    IsNPCCrafting = function() return false end,
    GetBaseProfessionInfo = function() return { professionID = 333, professionName = "Enchanting", skillLevel = 50, maxSkillLevel = 75 } end,
    GetChildProfessionInfo = function() return { professionID = 0 } end,
    CraftRecipe = function(rid, n)
        CRAFTS[#CRAFTS + 1] = { rid, n }
        if ASK_ON_CAST == "now" then TARGETING = true
        elseif ASK_ON_CAST == "late" then C_Timer.After(0.05, function() TARGETING = true end)
        elseif ASK_ON_CAST == "cast" then CASTING = true end
    end,
}
ProfessionsFrame = { IsShown = function() return true end, IsVisible = function() return true end }
ProfessionsFrameCloseButton = { IsVisible = function() return true end }
ProfessionMicroButton = { IsVisible = function() return true end }
SENT, SPECS, ROWS, SLOTS = {}, {}, {}, {}
local nslot = {}
WowCompanion = {
    S = function(fn) local ok, v = pcall(fn); if ok then return v end end,
    SN = function(fn) local ok, v = pcall(fn); if ok and type(v) == "number" then return v end end,
    CALL = function(fn, ...) return pcall(fn, ...) end,
    send = function(t) SENT[#SENT + 1] = t end, encode = function(t) return t end, push = function() end,
    isPlain = function() return true end, ABSENT = {}, SECRET = {}, THREW = {},
    pad = { op = function(name, ns, h) ROWS[name] = h; return {} end, secureRow = function() return {} end,
            slotFor = function(ns, key)
                SLOTS[ns] = SLOTS[ns] or {}
                if SLOTS[ns][key] == nil then nslot[ns] = (nslot[ns] or -1) + 1; SLOTS[ns][key] = nslot[ns] end
                return SLOTS[ns][key]
            end,
            describe = function() return { rows = { prof = 37 } } end, onLayout = function() end, retain = function() end },
    capture = { version = 17,
                take = function(g, spec) SPECS[#SPECS + 1] = spec; return true end,
                release = function() return true end,
                state = function() return { holder = "professions" } end,
                bnext = function() end, refused = function() return true end, pass = function() return true end },
}
function lastAck(op) for i = #SENT, 1, -1 do local t = SENT[i]; if t.type == "profack" and t.op == op then return t end end end
function acks(op) local n = 0; for _, t in ipairs(SENT) do if t.type == "profack" and t.op == op then n = n + 1 end end; return n end
-- select recipe [rid] through the select row, as the app's tap does
function pick(rid)
    for c = 0, 39 do
        ROWS.profsel(c)
        local a = lastAck("select")
        if a and a.ok and a.id == rid then return c end
    end
end
function reset() TARGETING = false; CASTING = false; CRAFTS = {}; ASK_ON_CAST = "now" end
''')
load = L.eval('function(src) local f, e = loadstring(src, "@Professions.lua"); if not f then error(e) end; return f end')
ok_load = True
try:
    load(SRC)('WowCompanionProfessions')
except Exception as e:  # noqa: BLE001
    ok_load = False; print(e)
check(ok_load, "Professions.lua loads (stand-in game) with no error")
L.execute('fire("PLAYER_LOGIN"); fire("TRADE_SKILL_SHOW"); run(1); WowCompanion.collectRecipes(); run(1)')
check(L.eval('pick(501)') is not None, "the enchant selected through the select row")

# 1. the pick at once
L.execute('reset(); ROWS.prof("ench"); run(0)')
a = L.eval('lastAck("ench")')
check(L.eval('#CRAFTS') == 1 and L.eval('CRAFTS[1][1]') == 501 and L.eval('CRAFTS[1][2]') == 1, "ctl ench: CraftRecipe(501, 1) once",
      L.eval('#CRAFTS'))
check(a is not None and a['ok'] is True and a['pick'] is True and a['ench'] is True and a['n'] == 1,
      "the ack: ok, pick = true (the game asks for the item)", a and dict(a))
check(a is not None and a['name'] == "Enchant Bracer - Minor Health", "the ack names the enchant", a and a['name'])

# 2. a pick already up: refused, nothing cast
L.execute('CRAFTS = {}; N0 = acks("ench"); ROWS.prof("ench"); run(0)')
check(L.eval('#CRAFTS') == 0 and L.eval('lastAck("ench").ok') is False and L.eval('lastAck("ench").why') == "already choosing an item",
      "a pick already up: refused, nothing cast", L.eval('lastAck("ench").why'))

# 3. the ask a frame late: answered after the 0.3 s look
L.execute('reset(); ASK_ON_CAST = "late"; N0 = acks("ench"); ROWS.prof("ench"); run(0)')
check(L.eval('acks("ench")') == L.eval('N0'), "the ask not up yet: no ack before the look")
L.execute('run(0.4)')
check(L.eval('acks("ench")') == L.eval('N0') + 1 and L.eval('lastAck("ench").ok') is True and L.eval('lastAck("ench").pick') is True,
      "the ask a moment later: ok + pick after the look", L.eval('lastAck("ench") and lastAck("ench").why'))

# 4. no ask, no cast: refused; a cast with no ask: ok, no pick
L.execute('reset(); ASK_ON_CAST = "none"; ROWS.prof("ench"); run(0.4)')
check(L.eval('lastAck("ench").ok') is False and L.eval('lastAck("ench").why') == "the game asked for no item",
      "no ask and no cast: refused (the game asked for no item)", L.eval('lastAck("ench").why'))
L.execute('reset(); ASK_ON_CAST = "cast"; ROWS.prof("ench"); run(0.4)')
check(L.eval('lastAck("ench").ok') is True and L.eval('lastAck("ench").pick') is None,
      "a cast with no ask (the game chose the item): ok, no pick", L.eval('lastAck("ench").pick'))

# 5. no reagents: refused, nothing cast
L.execute('reset(); CAN[501] = 0; ROWS.prof("ench"); run(0.4); CAN[501] = 3')
check(L.eval('#CRAFTS') == 0 and L.eval('lastAck("ench").why') == "nothing can be made", "no reagents: refused, nothing cast",
      L.eval('lastAck("ench").why'))

# 6. Create still refuses an enchant (never cast by make)
L.execute('reset(); ROWS.prof("make:1"); run(0.4)')
check(L.eval('#CRAFTS') == 0 and L.eval('lastAck("make").ok') is False and L.eval('lastAck("make").why') == "needs an item to enchant",
      "make:1 on an enchant: still refused, nothing cast", L.eval('lastAck("make").why'))

# 7. a plain recipe: ench refused; its Create as before
check(L.eval('pick(502)') is not None, "the plain recipe selected")
L.execute('reset(); ROWS.prof("ench"); run(0.4)')
check(L.eval('#CRAFTS') == 0 and L.eval('lastAck("ench").why') == "not an enchant", "ench on a plain recipe: refused",
      L.eval('lastAck("ench").why'))
L.execute('reset(); ROWS.prof("make:2"); run(0.1)')
check(L.eval('#CRAFTS') == 1 and L.eval('CRAFTS[1][1]') == 502 and L.eval('CRAFTS[1][2]') == 2 and L.eval('lastAck("make").ok') is True,
      "a plain recipe's Create (make:2): CraftRecipe(502, 2), as before")

# 8. a craft running: the enchant waits
check(L.eval('pick(501)') is not None, "the enchant selected again")
L.execute('CRAFTS = {}; TARGETING = false; ROWS.prof("ench"); run(0)')
check(L.eval('#CRAFTS') == 0 and L.eval('lastAck("ench").why') == "already crafting", "a craft running: refused (already crafting)",
      L.eval('lastAck("ench").why'))

# 9. the column, the tag, no trace
cols = {k: L.eval('SLOTS.profctl["%s"]' % k) for k in ("make:1", "forget:2", "kbd", "nokbd", "ench")}
check(cols["ench"] is not None and cols["ench"] == cols["nokbd"] + 1, "ench appended after nokbd (the old keys keep their columns)", cols)
check(cols["ench"] < 40, "inside the control row's 40 columns", cols["ench"])
check('local PV = "p27"' in SRC and '#541' in SRC and '"cant enchant"' in SRC, "the version tag p27, its note quotes the player")
check(L.eval('#TRACE') == 0, "no trace: no binding, macro, CVar, window call, nothing targeted or applied",
      [L.eval('TRACE[%d]' % (i + 1)) for i in range(L.eval('#TRACE'))])

print("%d passed, %d failed" % (passes, fails))
sys.exit(1 if fails else 0)
