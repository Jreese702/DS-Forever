"""The stubbed game shared by the off-device add-on tests (moved out of test_party.py unchanged, 2026-11-11, for the combat-input
work: E:/wow-build/combat_nav_design.md "Harness additions"). The REAL add-on files run under it, Lua 5.1 as the game's.

    from wowstub import GAME, STRICT, boot, boot_world, docs, nsent, F, SRC

GAME is test_party.py's stub, byte for byte (its results must not move); boot() is test_party.py's loader. STRICT is laid over GAME only by
boot_world(strict=True) (tools/test_combat_pad.py): the lockdown on every protected call, the game's restricted frame handles, the press and tap models and
the ADDON_ACTION_BLOCKED / handle-error ledgers. Needs lupa."""
import json
import os

from lupa import lua51 as lupa                    # the game's Lua is 5.1

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ADDONS = os.path.join(ROOT, "companion", "app", "src", "main", "assets", "companion", "addons")
F = {
    "safety": os.path.join(ADDONS, "WowCompanion", "Safety.lua"),
    "tappad": os.path.join(ADDONS, "WowCompanion", "TapPad.lua"),
    "maproom": os.path.join(ADDONS, "WowCompanion", "Map.lua"),
    "quests": os.path.join(ADDONS, "WowCompanionQuests", "Quests.lua"),
    "book": os.path.join(ADDONS, "WowCompanionSpells", "Book.lua"),
    "padmap": os.path.join(ADDONS, "WowCompanionSpells", "PadMap.lua"),
    "padtabctl": os.path.join(ADDONS, "WowCompanionSpells", "PadTabCtl.lua"),
    "hub": os.path.join(ADDONS, "WowCompanionHub", "Hub.lua"),
    "interact": os.path.join(ADDONS, "WowCompanionInteract", "Interact.lua"),
    "dialogs": os.path.join(ADDONS, "WowCompanionInteract", "Dialogs.lua"),
}
SRC = {k: open(p, encoding="utf-8").read() for k, p in F.items()}
# the add-on (folder) each file belongs to: what `...` is at its load in the game
ADDON_OF = {k: os.path.basename(os.path.dirname(p)) for k, p in F.items()}


# ================================================================ THE STUBBED GAME (shared by every full load)
GAME = r'''
local STUBMT = {}
STUB = setmetatable({}, STUBMT)
STUBMT.__index = function() return STUB end
STUBMT.__call = function() return nil end
STUBMT.__newindex = function() end
setmetatable(_G, { __index = function(_, k) return STUB end })

NOW = 1000
COMBAT = false
function GetTime() return NOW end
function GetTimePreciseSec() return NOW end
function debugprofilestop() return NOW * 1000 end
function GetServerTime() return 1700000000 + NOW end
function InCombatLockdown() return COMBAT end
function UnitAffectingCombat() return COMBAT end
WowCompanionSpellsDB, WowCompanionDB = {}, {}
WHO, REALM, CLASS = "Korvash", "Realm", "SHAMAN"
ACT_ON_DOWN = true                 -- the game's ActionButtonUseKeyDown

-- the spies: none of these may ever be called (no trace: feedback_wow_no_trace; no Blizzard window code: taint)
SPY = {}
for _, n in ipairs({ "SetBinding", "SaveBindings", "CreateMacro", "EditMacro", "SetCVar", "PlaceAction", "PickupAction",
                     "PickupSpell", "PickupMacro", "ShowUIPanel", "HideUIPanel", "ToggleFrame", "CloseAllBags", "OpenAllBags",
                     "ToggleSpellBook", "InspectUnit", "TargetFrame_OpenMenu", "UnitPopup_OpenMenu", "ToggleGameMenu",
                     "ToggleCharacter", "ToggleFriendsFrame", "CastSpellByName", "TargetUnit", "FocusUnit" }) do
    _G[n] = function(...) SPY[#SPY + 1] = n end
end
VIOL = {}                         -- secure work from insecure code during the lockdown
IN_SECURE = false
local function blocked(what)
    if COMBAT and not IN_SECURE then VIOL[#VIOL + 1] = what; error("ADDON_ACTION_BLOCKED " .. what, 2) end
end

-- ---------------------------------------------------------------- timers
TIMERS, TICKERS = {}, {}
C_Timer = {
    After = function(s, fn) TIMERS[#TIMERS + 1] = { at = NOW + s, fn = fn } end,
    NewTicker = function(s, fn) local t = { s = s, fn = fn, Cancel = function() end }; TICKERS[#TICKERS + 1] = t; return t end,
}
function runTimers(upto)
    local guard = 0
    local again = true
    while again and guard < 10000 do
        again = false
        guard = guard + 1
        local best, bi = nil, nil
        for i, t in ipairs(TIMERS) do if t.at <= upto and (not best or t.at < best.at) then best, bi = t, i end end
        if best then table.remove(TIMERS, bi); local was = NOW; if best.at > NOW then NOW = best.at end; best.fn(); again = true end
    end
    NOW = math.max(NOW, upto)
end
function advance(s) runTimers(NOW + s) end
function tick(s) for _, t in ipairs(TICKERS) do if t.s == s then pcall(t.fn) end end end
function hooksecurefunc(a, b, c)
    local t, k, fn = a, b, c
    if type(a) == "string" then t, k, fn = _G, a, b end
    local orig = t[k]
    t[k] = function(...) local r = { orig(...) }; fn(...); return unpack(r) end
end

-- ---------------------------------------------------------------- the screen
SCREEN = { pw = 1920, ph = 1080, uh = 1080 / (126.6 / 135) }
function GetPhysicalScreenSize() return SCREEN.pw, SCREEN.ph end

-- ---------------------------------------------------------------- frames
EVT, ALLF = {}, {}
Frame = {}
Frame.__index = function(t, k)
    if Frame[k] then return Frame[k] end
    if type(k) == "string" and k:match("^[A-Z]") then return function() return nil end end
    return nil
end
function CreateFrame(kind, name, parent, tpl)
    local f = setmetatable({ _kind = kind, _name = name, _tpl = tpl or "", a = {}, refs = {}, hooks = {}, scripts = {}, wraps = {},
                             clicks = {} }, Frame)
    f._secure = (tpl or ""):find("Secure") ~= nil
    if f._secure then blocked("CreateFrame " .. tostring(name)) end
    if name then rawset(_G, name, f) end
    ALLF[#ALLF + 1] = f
    return f
end
function Frame:GetName() return self._name end
function Frame:SetAttribute(k, v) if self._secure then blocked("SetAttribute " .. tostring(self._name) .. " " .. tostring(k)) end; self.a[k] = v end
function Frame:GetAttribute(k) return self.a[k] end
function Frame:GetFrameRef(k) return self.refs[k] end
function Frame:RegisterEvent(e) EVT[e] = EVT[e] or {}; table.insert(EVT[e], self); return true end
function Frame:RegisterUnitEvent(e) return self:RegisterEvent(e) end
function Frame:UnregisterEvent(e) end
function Frame:SetScript(k, fn) self.scripts[k] = fn end
function Frame:GetScript(k) return self.scripts[k] end
function Frame:HookScript(k, fn) self.hooks[k] = self.hooks[k] or {}; table.insert(self.hooks[k], fn) end
function Frame:RegisterForClicks(...) self.clicks = { ... } end
function Frame:IsShown() return self._shown ~= false end
function Frame:IsVisible() return self._shown ~= false end
function Frame:Show() self._shown = true end
function Frame:Hide() self._shown = false end
function Frame:GetID() return self._id or 0 end
function Frame:IsForbidden() return false end
function Frame:GetWidth() return SCREEN.pw end
function Frame:GetHeight() return SCREEN.uh end
function Frame:CreateTexture() return setmetatable({}, Frame) end
function Frame:CreateFontString() return setmetatable({}, Frame) end
function Frame:Click(button, down) doClick(self, button, down) end
UIParent = setmetatable({ a = {}, refs = {}, hooks = {}, scripts = {}, wraps = {}, clicks = {} }, Frame)
function UIParent:GetWidth() return SCREEN.pw / (SCREEN.ph / SCREEN.uh) end
function UIParent:GetHeight() return SCREEN.uh end
function fire(e, ...)
    for _, f in ipairs(EVT[e] or {}) do
        local fn = f.scripts.OnEvent
        if fn then local ok, err = pcall(fn, f, e, ...); if not ok then ERRS[#ERRS + 1] = e .. ": " .. tostring(err) end end
    end
end
ERRS = {}

-- ---------------------------------------------------------------- the restricted environment (only what the snippets may use)
local function newtable(...) return { ... } end
RESTRICTED = { newtable = newtable, ipairs = ipairs, pairs = pairs, tonumber = tonumber, type = type,
               select = select, GetGamePadState = function() return { buttons = PADSTATE } end,
               SecureCmdOptionParse = function(s) return SecureCmdOptionParse(s) end }
PADSTATE = {}
function compileSnippet(body, args)
    local fn, err = loadstring("local " .. args .. " = ...\n" .. body, "=snippet")
    if not fn then error("snippet does not compile: " .. tostring(err)) end
    setfenv(fn, setmetatable({}, { __index = function(_, k)
        local v = RESTRICTED[k]
        if v == nil then error("the restricted environment has no '" .. tostring(k) .. "'", 2) end
        return v
    end, __newindex = function(_, k) error("snippet global write '" .. tostring(k) .. "'", 2) end }))
    return fn
end
SNIPERR = {}
function runSecure(fn, ...)
    local was = IN_SECURE
    IN_SECURE = true
    local ok, r = pcall(fn, ...)
    IN_SECURE = was
    if not ok then SNIPERR[#SNIPERR + 1] = tostring(r); return nil end
    return r
end
function SecureHandlerWrapScript(frame, script, header, pre, post)
    blocked("WrapScript")
    frame.wraps[script] = frame.wraps[script] or {}
    table.insert(frame.wraps[script], { pre = compileSnippet(pre, "self, button, down"), header = header })
    return true
end
function SecureHandlerSetFrameRef(frame, name, ref) blocked("SetFrameRef"); frame.refs[name] = ref end
-- the header's Execute (insecure code, out of combat only): the body runs in the restricted environment with self = the header (ctl-sets m133)
function SecureHandlerExecute(frame, body) blocked("Execute"); runSecure(compileSnippet(body, "self"), frame) end
DRIVERS = {}
function RegisterStateDriver(f, st, cond) blocked("RegisterStateDriver"); DRIVERS[#DRIVERS + 1] = { f = f, st = st, cond = cond } end
-- the game's state drivers (they run in fights too): the first clause whose conditions hold; a change runs the frame's _onstate-<st>
function driveStates()
    for _, d in ipairs(DRIVERS) do
        local v = nil
        for clause in (d.cond .. ";"):gmatch("([^;]*);") do
            local r = SecureCmdOptionParse(clause)
            if r ~= nil and r ~= "" then v = r break end
        end
        if v ~= d.f.a["state-" .. d.st] then
            d.f.a["state-" .. d.st] = v
            local body = d.f.a["_onstate-" .. d.st]
            if body then runSecure(compileSnippet(body, "self, stateid, newstate"), d.f, d.st, v) end
        end
    end
end
function RegisterAttributeDriver() end

-- the secure macro conditionals the snippets read: [@unit,exists] [@unit,dead|nodead] [@unit,help] [combat] ...
UNITS = { player = { alive = true } }
function SecureCmdOptionParse(s)
    local cond, rest = s:match("^%[(.-)%]%s*(.*)$")
    if not cond then return s end
    local unit = "target"
    local ok = true
    for c in cond:gmatch("[^,]+") do
        c = c:gsub("^%s+", ""):gsub("%s+$", "")
        if c:sub(1, 1) == "@" then unit = c:sub(2)
        elseif c == "combat" then ok = ok and COMBAT
        elseif c == "nocombat" then ok = ok and not COMBAT
        elseif c == "exists" then ok = ok and UNITS[unit] ~= nil
        elseif c == "dead" then ok = ok and UNITS[unit] ~= nil and not UNITS[unit].alive
        elseif c == "nodead" then ok = ok and UNITS[unit] ~= nil and UNITS[unit].alive
        elseif c == "help" then ok = ok and UNITS[unit] ~= nil and not UNITS[unit].foe
        elseif c == "harm" then ok = ok and UNITS[unit] ~= nil and UNITS[unit].foe == true
        else ok = false end
    end
    if ok then return rest end
    return nil
end

-- ---------------------------------------------------------------- clicks and the secure action button
ACTIONS = {}
local function runMacro(text)
    for line in (text or ""):gmatch("[^\n]+") do
        local name, btn, d = line:match("^/click%s+([%w_]+)%s*(%S*)%s*(%S*)")
        if name then
            local t = rawget(_G, name)
            if type(t) == "table" and t.a then doClick(t, btn ~= "" and btn or "LeftButton", d == "1" or (d == "" and true) or false) end
        else
            ACTIONS[#ACTIONS + 1] = { kind = "macroline", line = line }
        end
    end
end
local function secureAct(f, button, down)
    local t = f.a["type"]
    local hold = f.a["pressAndHoldAction"]
    local onDown = ACT_ON_DOWN or hold
    local act
    if (down and onDown) or ((not down) and not onDown) then act = t
    elseif (not down) and hold and f.a["typerelease"] then act = f.a["typerelease"] end
    if not act then return end
    if act == "macro" then runMacro(f.a["macrotext"])
    elseif act == "click" then local cb = f.a["clickbutton"]; if type(cb) == "table" and cb.a then doClick(cb, button, down) end
    else ACTIONS[#ACTIONS + 1] = { kind = act, frame = f._name, spell = f.a["spell"], unit = f.a["unit"], item = f.a["item"] } end
end
function doClick(f, button, down)
    local reg = false
    for _, c in ipairs(f.clicks) do
        if c == "AnyDown" and down then reg = true end
        if c == "AnyUp" and not down then reg = true end
        if c == "LeftButtonUp" and not down and button == "LeftButton" then reg = true end
    end
    if #f.clicks == 0 and f._tpl:find("SecureActionButtonTemplate") then reg = not down end
    if not reg then return end
    for _, w in ipairs(f.wraps.OnClick or {}) do
        local r = runSecure(w.pre, f, button, down)
        if r == false then return end
    end
    if f._tpl:find("SecureHandlerClickTemplate") and f.a["_onclick"] then
        runSecure(compileSnippet(f.a["_onclick"], "self, button, down"), f, button, down)
    end
    if f.scripts.OnClick then f.scripts.OnClick(f, button, down) end
    if f._tpl:find("SecureActionButtonTemplate") then secureAct(f, button, down) end
    for _, fn in ipairs(f.hooks.OnClick or {}) do fn(f, button, down) end
    for _, fn in ipairs(f.hooks.PostClick or {}) do fn(f, button, down) end
end

-- ---------------------------------------------------------------- bindings (override only; a spy table)
BIND, BOWNER = {}, {}
local function setb(owner, key, action) blocked("SetOverrideBinding " .. tostring(key)); BIND[key] = action; BOWNER[key] = owner end
function SetOverrideBindingClick(owner, prio, key, name, mb) setb(owner, key, "CLICK " .. name .. ":" .. (mb or "LeftButton")) end
function SetOverrideBinding(owner, prio, key, action) setb(owner, key, action) end
function ClearOverrideBindings(owner)
    blocked("ClearOverrideBindings")
    for k, o in pairs(BOWNER) do if o == owner then BIND[k] = nil; BOWNER[k] = nil end end
end
function GetBindingAction(key) return BIND[key] or "" end
KEYOF = { GAMEPADACTIONBUTTON1 = "PADDLEFT", GAMEPADACTIONBUTTON2 = "PADDUP", GAMEPADACTIONBUTTON3 = "PADDRIGHT",
          GAMEPADACTIONBUTTON4 = "PADDDOWN", GAMEPADACTIONBUTTON5 = "PAD3", GAMEPADACTIONBUTTON6 = "PAD4", GAMEPADACTIONBUTTON7 = "PAD2",
          GAMEPADACTIONBUTTON8 = "PAD1", OPENRADIAL = "PADFORWARD", TOGGLEUIFOCUS = "PADBACK" }
function GetBindingKey(b) return KEYOF[b] end
function press(key, down)
    local a = BIND[key]
    if type(a) ~= "string" then return false end
    local name, mb = a:match("^CLICK ([%w_]+):(%S+)$")
    if not name then return false end
    doClick(rawget(_G, name), mb, down)
    return true
end
function tapKey(key) local ok = press(key, true); press(key, false); return ok end
C_GamePad = { ButtonBindingToIndex = function(n) if n == "PADLTRIGGER" then return 10 elseif n == "PADRTRIGGER" then return 11 end end }
LT_I, RT_I = 11, 12

-- ---------------------------------------------------------------- the game's own gamepad bar (four anchors, one bar each)
local function mkbar(prefix, base)
    local bar = { _shown = true }
    function bar:IsShown() return true end
    function bar:GetParent() return nil end
    function bar:GetActionButtonByIndex(pos)
        local b = setmetatable({ a = {}, refs = {}, hooks = {}, scripts = {}, wraps = {}, clicks = {}, _name = prefix .. pos, _id = base + pos }, Frame)
        return b
    end
    return bar
end
GamepadMainActionBarFrame = { PageUnit = { TopCenteredAnchor = { Bar = mkbar("GPTop", 0) }, LeftCenteredAnchor = { Bar = mkbar("GPLeft", 12) },
    RightCenteredAnchor = { Bar = mkbar("GPRight", 24) }, BottomCenteredAnchor = { Bar = mkbar("GPBottom", 36) }, overrideBars = {}, actionBars = {} } }

-- ---------------------------------------------------------------- the player, the party, the book
function UnitName(u) local x = UNITS[u]; if u == "player" then return WHO end; return x and x.name or nil end
function GetRealmName() return REALM end
function UnitClass(u) if u == "player" then return CLASS, CLASS end; local x = UNITS[u]; if x then return x.cls, x.cls end end
function UnitExists(u) return UNITS[u] ~= nil end
function GetPetIcon() local x = UNITS.pet; return x and x.icon or nil end     -- m134 (party-combat): the pet's portrait icon
function UnitIsDeadOrGhost(u) local x = UNITS[u]; return x ~= nil and not x.alive end
function UnitIsConnected(u) local x = UNITS[u]; return x ~= nil and not x.off end
function UnitGUID(u) local x = UNITS[u]; if not x then return nil end; return x.guid or ("Player-1-" .. (x.name or WHO)) end
function UnitGroupRolesAssigned(u) local x = UNITS[u]; return x and x.role or "NONE" end
function UnitPowerType(u) local x = UNITS[u]; local t = x and x.pt or "MANA"; return ({ MANA = 0, RAGE = 1, ENERGY = 3, FOCUS = 2 })[t] or 0, t end
function UnitHealthMax(u) local x = UNITS[u]; return x and (x.hpMax or 400) or 0 end
function UnitPowerMax(u) local x = UNITS[u]; return x and (x.pmMax or 300) or 0 end
function UnitHealth(u) return SECRETV end
function UnitPower(u) return SECRETV end
function UnitLevel() return 20 end
function IsInGroup() local n = 0; for k in pairs(UNITS) do if k:match("^party%d$") then n = n + 1 end end; return n > 0 end
function IsInRaid() return false end
function GetNumGroupMembers() local n = 1; for k in pairs(UNITS) do if k:match("^party%d$") then n = n + 1 end end; return n end
SECRETV = setmetatable({}, { __tostring = function() error("secret") end })
function issecretvalue(v) return rawequal(v, SECRETV) end
RAID_CLASS_COLORS = { SHAMAN = { colorStr = "ff0070dd" }, WARRIOR = { colorStr = "ffc69b6d" }, PRIEST = { colorStr = "ffffffff" },
                      MAGE = { colorStr = "ff3fc7eb" }, DRUID = { colorStr = "ffff7c0a" }, PALADIN = { colorStr = "fff48cba" },
                      ROGUE = { colorStr = "fffff468" }, HUNTER = { colorStr = "ffaad372" }, WARLOCK = { colorStr = "ff8788ee" } }
SPELLNAME = { [331] = "Healing Wave", [8004] = "Lesser Healing Wave", [526] = "Cure Poison", [2870] = "Cure Disease",
              [2008] = "Ancestral Spirit", [131] = "Water Breathing", [546] = "Water Walking", [2050] = "Lesser Heal", [17] = "Power Word: Shield",
              [527] = "Dispel Magic", [528] = "Cure Disease", [2006] = "Resurrection", [139] = "Renew", [5185] = "Healing Touch",
              [774] = "Rejuvenation", [8946] = "Cure Poison", [2782] = "Remove Curse", [20484] = "Rebirth", [8936] = "Regrowth",
              [635] = "Holy Light", [19750] = "Flash of Light", [1152] = "Purify", [4987] = "Cleanse", [7328] = "Redemption",
              [633] = "Lay on Hands", [475] = "Remove Lesser Curse", [604] = "Dampen Magic", [1008] = "Amplify Magic",
              [5697] = "Unending Breath", [1243] = "Power Word: Fortitude", [1459] = "Arcane Intellect", [403] = "Lightning Bolt",
              [324] = "Lightning Shield", [1126] = "Mark of the Wild", [467] = "Thorns", [8017] = "Rockbiter Weapon" }
HELPFUL = { [331] = true, [8004] = true, [526] = true, [2870] = true, [2008] = true, [131] = true, [546] = true, [324] = true,
            [2050] = true, [17] = true, [1243] = true, [1459] = true, [8017] = true }
BOOK = { 403, 331, 324 }                        -- the character's bindable spells, book order (a level 6 Shaman)
C_Spell = {
    GetSpellName = function(id) return SPELLNAME[id] end,
    GetSpellTexture = function(id) return 130000 + (id or 0) end,
    IsSpellHelpful = function(id) return HELPFUL[id] == true end,
    IsSpellInRange = function(s, u) return RANGE[u] end,
    GetSpellPowerCost = function() return {} end,
    IsSpellUsable = function() return true, false end,
}
function GetSpellInfo(id) return SPELLNAME[id] end
RANGE = {}
AURAS = {}                                     -- unit -> { names }
C_UnitAuras = { GetAuraDataByIndex = function(u, i)
    local l = AURAS[u]; if not l or not l[i] then return nil end
    if l.secret then return { name = SECRETV } end
    return { name = l[i] } end }
function UnitBuff() return nil end

-- the add-on core's sink: every line in order; WC.push / pushThrottled counted
SENT, PUSHES, TPUSHES = {}, 0, 0
WowCompanion = { push = function() PUSHES = PUSHES + 1 end, pushThrottled = function() TPUSHES = TPUSHES + 1 end,
                 snapshotSoon = function() end, VERSION = "test" }
WowCompanion.send = function(s) SENT[#SENT + 1] = s end
WowCompanion.emit = function(t) SENT[#SENT + 1] = WowCompanion.encode(t) end
function WowCompanion.snapshot(reason) SNAPS[#SNAPS + 1] = reason end
SNAPS = {}
WowCompanion.spellsBindableList = function()
    local out = {}
    for i, id in ipairs(BOOK) do out[#out + 1] = { id = id, slot = i } end
    return out
end
WowCompanion.spellsBookSlotOf = function(id) for i, x in ipairs(BOOK) do if x == id then return i end end end
-- the shared capture (Interact): its holder (nil = nobody)
HOLDER = nil
WowCompanion.capture = { version = 13, state = function() return { holder = rawget(_G, "HOLDER") } end, onChange = function() return false end }
UNFOCUSED = 0
WowCompanion.dialogs = { unfocused = function() return UNFOCUSED end }
'''


def boot(files, pre=''):
    L = lupa.LuaRuntime(unpack_returned_tuples=True)
    L.execute(GAME)
    if pre:
        L.execute(pre)
    load = L.eval('function(src, name) local f, e = loadstring(src, name); if not f then error(e) end; return f end')
    for k in files:
        load(SRC[k], '@' + os.path.basename(F[k]))(os.path.basename(F[k]))
    return L


def docs(L, start=0, typ=None):
    out = []
    for i in range(start + 1, int(L.eval('#SENT')) + 1):
        s = L.eval('SENT[%d]' % i)
        try:
            d = json.loads(s)
        except Exception:
            continue
        if typ is None or (isinstance(d, dict) and d.get('type') == typ):
            out.append(d)
    return out


def nsent(L):
    return int(L.eval('#SENT'))





# ================================================================ STRICT: the game's lockdown, exactly where it bites (combat_nav_design.md,
# "Harness additions"; the user, 2026-11-11: "everything on the bottom screen breaks in combat"). Laid over GAME by boot_world(strict=True)
# only, so test_party.py's GAME world is exactly as before.
STRICT = r'''
-- ---------------------------------------------------------------- more spies: the game windows' own open / close calls (taint: i140)
for _, n in ipairs({ "CloseLoot", "CloseGossip", "CloseMerchant", "CloseQuest", "CloseTrainer", "CloseMail", "CloseTaxiMap",
                     "CloseBankFrame", "ToggleWorldMap", "OpenWorldMap", "ToggleQuestLog", "CloseWindows", "CloseAllWindows" }) do
    _G[n] = function(...) SPY[#SPY + 1] = n end
end
C_GossipInfo = { CloseGossip = function() SPY[#SPY + 1] = "C_GossipInfo.CloseGossip" end }
C_Map = setmetatable({ OpenWorldMap = function() SPY[#SPY + 1] = "C_Map.OpenWorldMap" end }, { __index = function() return STUB end })

-- ---------------------------------------------------------------- the lockdown on every protected call (insecure code, COMBAT on)
-- (GAME's blocked() is a local of its chunk; this is the same rule, reachable from here)
function blocked(what)
    if COMBAT and not IN_SECURE then VIOL[#VIOL + 1] = what; error("ADDON_ACTION_BLOCKED " .. what, 2) end
end
-- protected = made from a Secure template, or one of the game's own protected frames (Forever's bars and their buttons: PROTECT)
function isProtected(f) return type(f) == "table" and (rawget(f, "_secure") or rawget(f, "_protected")) and true or false end
function PROTECT(f) if type(f) == "table" then rawset(f, "_protected", true) end; return f end
local function unwrap(v) if type(v) == "table" and rawget(v, "__frame") then return rawget(v, "__frame") end; return v end
function Frame:SetAttribute(k, v)
    if isProtected(self) then blocked("SetAttribute " .. tostring(self._name) .. " " .. tostring(k)) end
    self.a[k] = unwrap(v)
end
local rawShow, rawHide = Frame.Show, Frame.Hide
local function fireHooks(f, ev)
    local fn = f.scripts and f.scripts[ev]
    if fn then pcall(fn, f) end
    for _, h in ipairs(f.hooks and f.hooks[ev] or {}) do pcall(h, f) end
end
function Frame:Show()
    if isProtected(self) then blocked("Show " .. tostring(self._name)) end
    local was = self._shown ~= false
    rawShow(self)
    if not was then fireHooks(self, "OnShow") end
end
function Frame:Hide()
    if isProtected(self) then blocked("Hide " .. tostring(self._name)) end
    local was = self._shown ~= false
    rawHide(self)
    if was then fireHooks(self, "OnHide") end
end
function Frame:SetShown(on) if on then self:Show() else self:Hide() end end
function Frame:SetPoint(...) if isProtected(self) then blocked("SetPoint " .. tostring(self._name)) end; self._pts = { ... } end
function Frame:ClearAllPoints() if isProtected(self) then blocked("ClearAllPoints " .. tostring(self._name)) end; self._pts = nil end
function Frame:SetAlpha(x) self._alpha = x end
function Frame:GetAlpha() return self._alpha or 1 end
function Frame:GetParent() return rawget(self, "_parent") end
function Frame:IsVisible()
    if self._shown == false then return false end
    local p = rawget(self, "_parent")
    if type(p) == "table" and type(p.IsVisible) == "function" then return p:IsVisible() end
    return true
end
local rawCreate = CreateFrame
function CreateFrame(kind, name, parent, tpl)
    local f = rawCreate(kind, name, parent, tpl)
    if parent ~= nil and parent ~= UIParent then f._parent = parent end
    return f
end
-- the game's override bindings: one layer per owner, priority first, then the latest; BIND = what each key does now (the spy table)
BLAYER, BSEQ = {}, 0
local function rebind()
    for k in pairs(BIND) do BIND[k] = nil end
    for k in pairs(BOWNER) do BOWNER[k] = nil end
    local best = {}
    for owner, keys in pairs(BLAYER) do
        for key, e in pairs(keys) do
            local b = best[key]
            if not b or (e.prio and not b.prio) or ((e.prio == b.prio) and e.seq > b.seq) then best[key] = e; e.owner = owner end
        end
    end
    for key, e in pairs(best) do BIND[key] = e.action; BOWNER[key] = e.owner end
end
local function setb(owner, prio, key, action)
    blocked("SetOverrideBinding " .. tostring(key))
    BLAYER[owner] = BLAYER[owner] or {}
    if action == nil then BLAYER[owner][key] = nil
    else BSEQ = BSEQ + 1; BLAYER[owner][key] = { action = action, prio = prio and true or false, seq = BSEQ } end
    rebind()
end
function SetOverrideBindingClick(owner, prio, key, name, mb) setb(owner, prio, key, name and ("CLICK " .. name .. ":" .. (mb or "LeftButton")) or nil) end
function SetOverrideBinding(owner, prio, key, action) setb(owner, prio, key, action) end
function SetOverrideBindingSpell(owner, prio, key, s) setb(owner, prio, key, s and ("SPELL " .. tostring(s)) or nil) end
function SetOverrideBindingMacro(owner, prio, key, m) setb(owner, prio, key, m and ("MACRO " .. tostring(m)) or nil) end
function SetOverrideBindingItem(owner, prio, key, i) setb(owner, prio, key, i and ("ITEM " .. tostring(i)) or nil) end
function ClearOverrideBindings(owner) blocked("ClearOverrideBindings"); BLAYER[owner] = nil; rebind() end
function RegisterAttributeDriver(f) blocked("RegisterAttributeDriver") end
function UnregisterStateDriver(f) blocked("UnregisterStateDriver") end

-- ---------------------------------------------------------------- the game's restricted frame handles (restrictedframes.lua 79-117)
-- A snippet sees frame refs as HANDLES. While InCombatLockdown(), every method of an UNPROTECTED frame's handle (LootFrame,
-- WorldMapFrame, our own plain buttons ...) raises "Invalid frame handle"; a protected frame's handle works.
HANDLEERR = {}
local HCACHE = setmetatable({}, { __mode = "k" })
local HMT = {}
function handle(f)
    if type(f) ~= "table" or rawget(f, "__frame") then return f end
    local h = HCACHE[f]
    if not h then h = setmetatable({ __frame = f }, HMT); HCACHE[f] = h end
    return h
end
HMT.__index = function(h, k)
    local f = rawget(h, "__frame")
    local m = f[k]
    if type(m) ~= "function" then return nil end
    return function(_, ...)
        if COMBAT and not isProtected(f) then
            local msg = "Invalid frame handle " .. tostring(rawget(f, "_name")) .. ":" .. tostring(k)
            HANDLEERR[#HANDLEERR + 1] = msg
            error(msg, 2)
        end
        local n = select("#", ...)
        local args = { ... }
        for i = 1, n do args[i] = unwrap(args[i]) end
        local r = { m(f, unpack(args, 1, n)) }
        for i = 1, #r do if type(r[i]) == "table" and getmetatable(r[i]) == Frame then r[i] = handle(r[i]) end end
        return unpack(r)
    end
end
local rawRef = Frame.GetFrameRef
function Frame:GetFrameRef(k) local r = rawRef(self, k); if IN_SECURE then return handle(r) end; return r end
-- every snippet body compiled (the static scan reads these too: bodies built at run time)
SNIPPETS = {}
local rawCompile = compileSnippet
function compileSnippet(body, args) SNIPPETS[body] = true; return rawCompile(body, args) end
-- Forever's bars and their buttons are the game's protected frames
pcall(function()
    for _, an in pairs(GamepadMainActionBarFrame.PageUnit) do
        if type(an) == "table" and type(an.Bar) == "table" then
            local bar = PROTECT(an.Bar)
            local get = bar.GetActionButtonByIndex
            bar.GetActionButtonByIndex = function(self, pos) return PROTECT(get(self, pos)) end
        end
    end
end)

-- ---------------------------------------------------------------- the press and tap models
-- CLICKS: every click while one press / tap runs ("<frame>+" down, "<frame>-" up), the /click and "click" chains included
CLICKS = nil
local rawClick = doClick
function doClick(f, button, down)
    if CLICKS then CLICKS[#CLICKS + 1] = tostring(type(f) == "table" and rawget(f, "_name") or "?") .. (down and "+" or "-") end
    if type(f) ~= "table" then return end
    return rawClick(f, button, down)
end
-- GAMEKEYS: the presses the GAME got (a key with no override of ours: the game's own binding / its window's navigation)
GAMEKEYS = {}
-- one edge of a hardware press: the binding table -> the click it names (-> PRE -> what PRE resolves it to) / the game
function hwEdge(key, down)
    local a = BIND[key]
    if a == nil then GAMEKEYS[#GAMEKEYS + 1] = key .. (down and "+" or "-"); return "game" end
    local name, mb = a:match("^CLICK ([%w_]+):(%S+)$")
    if not name then GAMEKEYS[#GAMEKEYS + 1] = a .. (down and "+" or "-"); return "binding" end
    doClick(rawget(_G, name), mb, down)
    return "click"
end
-- a whole press (both edges); returns how it went ("click" / "binding" / "game") and the click chain
function hwPress(key, lt, rt)
    PADSTATE = {}
    if lt then PADSTATE[LT_I] = true end
    if rt then PADSTATE[RT_I] = true end
    CLICKS = {}
    local how = hwEdge(key, true)
    hwEdge(key, false)
    local log = CLICKS
    CLICKS = nil
    PADSTATE = {}
    NOW = NOW + 0.1
    return how, log
end
-- a tap of the app on a TapPad cell (the bottom screen's touch, a hardware click): both edges; false = no such cell
function hwTap(row, col)
    local r = WowCompanion.pad and WowCompanion.pad.rows and WowCompanion.pad.rows[row]
    local c = r and r.cells and r.cells[col]
    if not c then return false end
    CLICKS = {}
    doClick(c, "LeftButton", true)
    doClick(c, "LeftButton", false)
    local log = CLICKS
    CLICKS = nil
    NOW = NOW + 0.1
    return true, log
end

-- ---------------------------------------------------------------- the game's windows (unprotected frames, a close button each)
function mkwin(name, shown)
    local f = rawget(_G, name)
    if type(f) ~= "table" or getmetatable(f) ~= Frame then f = CreateFrame("Frame", name, UIParent) end
    f._shown = shown and true or false
    if not rawget(f, "CloseButton") then
        local cb = CreateFrame("Button", name .. "CloseButton", f)
        -- (a secure "click" hands the press on with Click(), which the game fires whatever the button registered: either edge)
        cb:RegisterForClicks("AnyDown", "AnyUp")
        cb:SetScript("OnClick", function()
            if f._shown == false then return end
            ACTIONS[#ACTIONS + 1] = { kind = "close", frame = name }
            f:Hide()
        end)
        f.CloseButton = cb
        f.BorderFrame = { CloseButton = cb }           -- WorldMapFrame's (Map.lua reads it there)
    end
    return f
end
'''

# the game's load order: WowCompanion, then (alphabetical, optional deps first) Quests, Hub, Interact (+ Dialogs), Spells
WORLD_FILES = ("safety", "tappad", "maproom", "quests", "hub", "interact", "dialogs", "book", "padmap", "padtabctl")
LOGIN = ('for _, a in ipairs({ "WowCompanion", "WowCompanionQuests", "WowCompanionHub", "WowCompanionInteract", "WowCompanionSpells" }) do '
         'fire("ADDON_LOADED", a) end; fire("PLAYER_LOGIN"); fire("PLAYER_ENTERING_WORLD", true, false); runTimers(NOW + 10)')


def boot_world(files=WORLD_FILES, pre='', strict=True, login=LOGIN, expose=True):
    """The add-on files in the game's load order, each with its add-on's name as `...`; strict = STRICT laid over GAME.
    expose: Interact's N / G / F / ACT / LOOT as __IN (one statement appended to the in-memory copy: no main-chunk local).
    Returns (L, load_errors)."""
    L = lupa.LuaRuntime(unpack_returned_tuples=True)
    L.execute(GAME)
    if strict:
        L.execute(STRICT)
    if pre:
        L.execute(pre)
    load = L.eval('function(src, name) local f, e = loadstring(src, name); if not f then error(e) end; return f end')
    errs = []
    for k in files:
        s = SRC[k]
        if k == "interact" and expose:
            s += "\n_G.__IN = { N = N, G = G, F = F, ACT = ACT, LOOT = LOOT }\n"
        try:
            load(s, '@' + os.path.basename(F[k]))(ADDON_OF[k])
        except Exception as e:                       # a load error is the game's red error box: the caller asserts on it
            errs.append('%s: %s' % (k, str(e)[:300]))
    if login:
        L.execute(login)
    return L, errs
