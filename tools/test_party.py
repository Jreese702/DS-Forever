"""Party mode (PARTY_BUILD.md, the ADD-ON half), off-device: the REAL add-on files under a stubbed game, Lua 5.1 as the game's.

    python tools/test_party.py

Groups (PARTY_BUILD.md 5.4): 1 the router's PRE with Party mode; 2 PARTYPAD (NAV, casts, Y, B, the menu sub-state, key-up);
3 PARTYFLIP; 4 the bindings (a spying binding table); 5 the combat lockdown; 6 the per-character store and the class defaults;
7 the documents; 8 the health estimate; 9 Smart Y's party picks and PB ops; 10 TapPad pad11; 11 Quests' player menu for a unit;
plus the Party tab (PadTabCtl p33 / Book b47) and the no-trace scan. Secure snippets run as plain Lua in a RESTRICTED environment
(only what the game's restricted environment offers that the snippets need). Needs lupa. Exit code 1 on any failure."""
import json
import os
import re
import sys

from lupa import lua51 as lupa                    # the game's Lua is 5.1

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ADDONS = os.path.join(ROOT, "companion", "app", "src", "main", "assets", "companion", "addons")
F = {
    "safety": os.path.join(ADDONS, "WowCompanion", "Safety.lua"),
    "tappad": os.path.join(ADDONS, "WowCompanion", "TapPad.lua"),
    "quests": os.path.join(ADDONS, "WowCompanionQuests", "Quests.lua"),
    "book": os.path.join(ADDONS, "WowCompanionSpells", "Book.lua"),
    "padmap": os.path.join(ADDONS, "WowCompanionSpells", "PadMap.lua"),
    "padtabctl": os.path.join(ADDONS, "WowCompanionSpells", "PadTabCtl.lua"),
}
SRC = {k: open(p, encoding="utf-8").read() for k, p in F.items()}
fails = 0
passes = 0


def check(ok, name, got=''):
    global fails, passes
    print(('PASS ' if ok else 'FAIL ') + name + ((' -> ' + str(got)) if got != '' else ''))
    if ok:
        passes += 1
    else:
        fails += 1


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
RESTRICTED = { newtable = newtable, ipairs = ipairs, pairs = pairs, tostring = tostring, tonumber = tonumber, type = type,
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
function RegisterStateDriver() end
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
WowCompanion.capture = { version = 13, state = function() return { holder = HOLDER } end, onChange = function() return false end }
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


# ================================================================ STATIC: every touched file compiles as Lua 5.1, under its limits
SL = lupa.LuaRuntime()
comp = SL.eval('function(s, n) local f, e = loadstring(s, n); return e end')
for k in ("tappad", "quests", "book", "padmap", "padtabctl"):
    e = comp(SRC[k], k)
    check(e is None, "Lua 5.1 compiles %s (200 locals, 60 upvalues)" % os.path.basename(F[k]), e or '')

# ================================================================ 10. TapPad pad11
check('local PADV = "pad11"' in SRC["tappad"], "TapPad says pad11")
check(re.search(r'local ROWS_MAX = 56\b', SRC["tappad"]) is not None, "TapPad ROWS_MAX = 56")


def pad_at(popup_top):
    L = boot(["safety", "tappad"], pre='SCREEN.uh = 1080 / (%r / 135)' % popup_top)
    L.execute('''
        for i = 0, 55 do
            if i % 2 == 0 then WowCompanion.pad.op("r" .. i, nil, function() end)
            else WowCompanion.pad.secureRow("s" .. i, 3, function(b, c) b:SetAttribute("type", nil) end, nil, { onPress = true }) end
        end
        X57 = WowCompanion.pad.op("r56", nil, function() end)
        WowCompanion.pad.layout()
    ''')
    d = L.eval('WowCompanion.pad.describe()')
    rows = sorted(int(v) for v in d['rows'].values())
    return L, d, rows


L10, d, rows = pad_at(126.6)
check(abs(float(d['popupTop']) - 126.6) < 0.06 and rows == list(range(56)), "pad11 at popupTop 126.6: rows 0..55 placed and described",
      (d['popupTop'], rows[-3:], len(rows)))
check(L10.eval('rawget(_G, "X57")') is None, "a 57th row does not register (ROWS_MAX 56)")
check(d['v'] == 'pad11', "describe() says pad11", d['v'])
L10, d, rows = pad_at(121.5)
check(abs(float(d['popupTop']) - 121.5) < 0.06 and rows == list(range(54)), "pad11 at popupTop 121.5 (smallest scale): rows 0..53 only",
      (d['popupTop'], rows[-3:], len(rows)))
hidden = L10.eval('(function() local r = WowCompanion.pad.rows["r54"]; local s = WowCompanion.pad.rows["s55"]; '
                  'return (r.cells[0]._shown == false) and (s.cells[0]._shown == false) end)()')
check(hidden is True, "rows 54-55 are hidden at the smallest scale (never inside a dialog)", hidden)

# ================================================================ 1. PRE + Party mode (the router's real snippet, restricted)
PRE = re.search(r'local PRE = \[=\[(.*?)\]=\]', SRC["padmap"], re.S).group(1)
G1 = boot([])
prefn = G1.eval('function(body) return compileSnippet(body, "self, button, down") end')(PRE)
G1.globals().PREFN = prefn
G1.execute('''
function mk(attrs)
    local f = setmetatable({ a = attrs, refs = {}, hooks = {}, scripts = {}, wraps = {}, clicks = {}, _name = "R" }, Frame)
    f.refs["wc-hdr"] = setmetatable({ a = {}, refs = {} }, Frame)
    f.refs["wc-bar-top-1"] = setmetatable({ a = {}, refs = {}, _shown = true }, Frame)
    f.refs["wc-btn-top-1"] = setmetatable({ a = {}, refs = {}, _id = 5 }, Frame)
    for _, p in ipairs({ 1, 2, 3, 4, 5, 6, 8 }) do f.refs["wc-face-" .. p] = setmetatable({ a = {}, refs = {} }, Frame) end
    return f
end
-- one router button per role, with EVERYTHING that could take a press armed: a fight spell in the fight state, fishing, Smart Y, the
-- quest item, the D-pad toolbar, a slot macro / fight spell, start attack, B's give-back
function routerBtn(pos, key, party, roomMode)
    local role = (pos == 7 and "back") or (pos <= 4 and "dpad") or nil
    local f = mk({ ["wc-n-top"] = 1, ["wc-dest-top-1"] = "GPTop" .. pos, ["wc-pos"] = pos, ["wc-key"] = key, ["wc-role"] = role,
                   ["wc-state"] = "fight", ["wc-fight-type"] = "spell", ["wc-fight-spell"] = "Lightning Bolt", ["wc-fish"] = (pos == 5) and "Fishing" or nil,
                   ["wc-smarty"] = (pos == 6) and "item:117" or nil, ["wc-qitem"] = (pos == 6) and "item:1" or nil, ["wc-qitem-u"] = "target", ["wc-roombar"] = "WowCompanionPadMap",
                   ["wc-interact"] = (pos == 5) or nil, ["wc-party"] = party, ["wc-party-dest"] = "WowCompanionPartyPad",
                   ["wc-mode"] = roomMode and "room" or nil, ["wc-room-dest"] = "WowCompanionInteractPad" })
    f.refs["wc-hdr"].a["wc-mt-5"] = "/cast Slot Macro"; f.refs["wc-hdr"].a["wc-ft-5"] = "/cast Slot Fight"; f.refs["wc-hdr"].a["wc-sa-5"] = "/startattack\\n"
    return f
end
function pressPre(f, down)
    TRUE_TARGET = true
    runSecure(PREFN, f, "LeftButton", down)
    return f.a["wc-kind"], f.a["type"], f.a["macrotext"], f.a["spell"], f.a["item"], f.a["unit"]
end
UNITS.target = { alive = true, foe = true }
''')
ROUTER8 = [(8, "PAD1"), (7, "PAD2"), (5, "PAD3"), (6, "PAD4"), (1, "PADDLEFT"), (2, "PADDUP"), (3, "PADDRIGHT"), (4, "PADDDOWN")]
for combat in (False, True):
    G1.execute('COMBAT = %s' % ('true' if combat else 'false'))
    for pos, key in ROUTER8:
        f = G1.eval('routerBtn')(pos, key, True, False)
        k = G1.eval('pressPre')(f, True)
        given = any(G1.eval('function(f) for _, p in ipairs({1,2,3,4,5,6,8}) do if f.refs["wc-face-" .. p].a["wc-given"] then return true end end return false end')(f) for _ in [0])
        check(k[0] == "native" and k[1] == "macro" and k[2] == "/click WowCompanionPartyPad %s 1" % key and k[3] is None and k[4] is None
              and k[5] is None and not given,
              "PRE, Party mode, %s%s: the key-down is /click WowCompanionPartyPad %s 1, nothing else" % (key, " in combat" if combat else "", key),
              (k[0], k[2], given))
        k = G1.eval('pressPre')(f, False)
        check(k[2] == "/click WowCompanionPartyPad %s 0" % key, "PRE, Party mode, %s: the key-up is ... %s 0" % (key, key), k[2])
G1.execute('COMBAT = true')
for pos, key in ROUTER8:
    f = G1.eval('routerBtn')(pos, key, True, True)
    k = G1.eval('pressPre')(f, True)
    check(k[2] == "/click WowCompanionInteractPad %s 1" % key, "PRE: wc-mode \"room\" beats wc-party (%s goes to the room)" % key, k[2])
# unset = today (the fight spell / the toolbar / B's give-back), and test_smarty.py's own cases below
f = G1.eval('routerBtn')(5, "PAD3", None, False)
f.a["wc-qitem"] = None
k = G1.eval('pressPre')(f, True)
check(k[0] == "fight" and k[3] == "Lightning Bolt", "PRE without wc-party: X in the fight state is its fight spell, as today", k[:4])
f = G1.eval('routerBtn')(7, "PAD2", None, False)
k = G1.eval('pressPre')(f, True)
given = G1.eval('function(f) return f.refs["wc-face-5"].a["wc-given"] end')(f)
check(given is True, "PRE without wc-party: B in a fight gives A / X / Y back, as today", given)
G1.execute('COMBAT = false')
f = G1.eval('routerBtn')(2, "PADDUP", None, False)
f.a["wc-state"] = "idle"; f.a["wc-qitem"] = None
k = G1.eval('pressPre')(f, True)
check(k[0] == "roombar", "PRE without wc-party: the bare D-pad is the DS toolbar, as today", k[0])
f = G1.eval('routerBtn')(2, "PADDUP", True, False)
f.a["wc-party-dest"] = None; f.a["wc-state"] = "idle"; f.a["wc-qitem"] = None
k = G1.eval('pressPre')(f, True)
check(k[0] == "roombar", "PRE: wc-party with no wc-party-dest changes nothing", k[0])
check(G1.eval('#SNIPERR') == 0, "PRE ran in the restricted environment without an error", G1.eval('SNIPERR[1]'))
import subprocess
r = subprocess.run([sys.executable, os.path.join(ROOT, 'tools', 'test_smarty.py')], capture_output=True, text=True)
check(r.returncode == 0 and r.stdout.strip().endswith('ALL PASS'), "tools/test_smarty.py passes UNCHANGED", r.stdout.strip()[-60:])
smarty_git = subprocess.run(['git', '-C', ROOT, 'diff', '--quiet', '5ee1ff22', '--', 'tools/test_smarty.py']).returncode
check(smarty_git == 0, "tools/test_smarty.py is byte for byte the contract's base", smarty_git)

# ================================================================ THE WORLD: the real Spells files (and TapPad) logged in
ROUTER_NAME = {8: "WowCompanionCombatFace8", 7: "WowCompanionFaceB", 5: "WowCompanionCombatFace5", 6: "WowCompanionCombatFace6",
               1: "WowCompanionFaceD1", 2: "WowCompanionFaceD2", 3: "WowCompanionFaceD3", 4: "WowCompanionFaceD4"}
KEY_POS = {"PAD1": 8, "PAD2": 7, "PAD3": 5, "PAD4": 6, "PADDLEFT": 1, "PADDUP": 2, "PADDRIGHT": 3, "PADDDOWN": 4}
WORLD_HELPERS = r'''
function party(spec)            -- spec = { {name, cls, alive, role, pt, off, foe}, ... } for party1..
    for k in pairs(UNITS) do if k:match("^party%d$") then UNITS[k] = nil end end
    for i, m in ipairs(spec or {}) do
        UNITS["party" .. i] = { name = m[1], cls = m[2] or "WARRIOR", alive = m[3] ~= false, role = m[4] or "NONE", pt = m[5] or "RAGE",
                                off = m[6], foe = m[7], guid = "Player-1-" .. m[1] }
    end
end
function router(pos) return rawget(_G, ({ [8] = "WowCompanionCombatFace8", [7] = "WowCompanionFaceB", [5] = "WowCompanionCombatFace5",
    [6] = "WowCompanionCombatFace6", [1] = "WowCompanionFaceD1", [2] = "WowCompanionFaceD2", [3] = "WowCompanionFaceD3",
    [4] = "WowCompanionFaceD4" })[pos]) end
-- a hardware press of the router button for `pos` (what its override binding delivers), both edges
function hit(pos) local b = router(pos); doClick(b, "LeftButton", true); doClick(b, "LeftButton", false) end
function trig(lt, rt) PADSTATE = {}; if lt then PADSTATE[LT_I] = true end; if rt then PADSTATE[RT_I] = true end end
function PADA(k) return rawget(_G, "WowCompanionPartyPad").a[k] end
function lastAction() return ACTIONS[#ACTIONS] end
function clickName(n, down) doClick(rawget(_G, n), "LeftButton", down) end
'''


def world(pre='', files=("safety", "tappad", "book", "padmap", "padtabctl")):
    L = boot(list(files), pre=pre)
    L.execute(WORLD_HELPERS)
    L.execute('fire("PLAYER_LOGIN"); fire("PLAYER_ENTERING_WORLD", true, false); runTimers(NOW + 10)')
    return L


def nact(L):
    return int(L.eval('#ACTIONS'))


def acts(L, start):
    out = []
    for i in range(start + 1, nact(L) + 1):
        a = L.eval('ACTIONS[%d]' % i)
        out.append((a.kind, a.spell, a.unit))
    return out


W = world()
check(list(W.eval('ERRS').values()) == [] and W.eval('#SNIPERR') == 0, "the world loads (Safety, TapPad, Book, PadMap, PadTabCtl) with no error",
      list(W.eval('ERRS').values())[:2])
check(all(W.eval('rawget(_G, "%s") ~= nil' % n) for n in ("WowCompanionPartyPad", "WowCompanionPartyToggle", "WowCompanionPartyOn",
                                                             "WowCompanionPartyOff", "WowCompanionPartyMenuDone")),
      "the five secure frames exist by their contract names")
padf = W.eval('rawget(_G, "WowCompanionPartyPad")')
refs = dict(padf.refs)
check(all(refs.get('wc-r-%d' % (i + 1)) is not None and refs['wc-r-%d' % (i + 1)]._name == ROUTER_NAME[p]
          for i, p in enumerate([8, 7, 5, 6, 1, 2, 3, 4])) and padf.a['wc-rn'] == 8 and refs.get('wc-pad') is not None,
      "the pad's refs: wc-r-1..8 in F.ROUTER's order (A B X Y, D-pad L U R D), wc-rn 8, wc-pad")
for n in ("WowCompanionPartyToggle", "WowCompanionPartyOn", "WowCompanionPartyOff", "WowCompanionPartyMenuDone"):
    fr = W.eval('rawget(_G, "%s")' % n)
    check(fr.refs['wc-pad'] is not None and fr.refs['wc-r-8'] is not None and fr.a['wc-rn'] == 8, n + ": wc-pad + wc-r-1..8 refs")
check(all(W.eval('router(%d)' % p).a['wc-party-dest'] == "WowCompanionPartyPad" for p in range(1, 9)),
      "every router button carries wc-party-dest = WowCompanionPartyPad")

# ================================================================ 3. PARTYFLIP
def flip(L, name):
    L.execute('clickName("%s", true); clickName("%s", false); advance(0.3)' % (name, name))


def party_on_all(L):
    return [L.eval('router(%d)' % p).a['wc-party'] for p in range(1, 9)] + [L.eval('PADA("wc-party")')]


W.execute('PADA = PADA; rawget(_G, "WowCompanionPartyPad").a["wc-pmenu"] = true')
flip(W, "WowCompanionPartyOn")
check(party_on_all(W) == [True] * 9 and W.eval('PADA("wc-pmenu")') is None, "ON: wc-party on all 8 router buttons and the pad; wc-pmenu cleared",
      party_on_all(W))
flip(W, "WowCompanionPartyOn")
check(party_on_all(W) == [True] * 9, "ON again: still on (absolute)")
W.execute('rawget(_G, "WowCompanionPartyPad").a["wc-pmenu"] = true')
flip(W, "WowCompanionPartyOff")
check(party_on_all(W) == [None] * 9 and W.eval('PADA("wc-pmenu")') is None, "OFF: wc-party nil everywhere; wc-pmenu cleared", party_on_all(W))
flip(W, "WowCompanionPartyOff")
check(party_on_all(W) == [None] * 9, "OFF again: still off (absolute)")
W.execute('clickName("WowCompanionPartyToggle", true); clickName("WowCompanionPartyToggle", false); advance(0.3)')
check(party_on_all(W) == [True] * 9, "TOGGLE from off: on (its key-down only: the up does not toggle back)", party_on_all(W))
W.execute('rawget(_G, "WowCompanionPartyPad").a["wc-pmenu"] = true; clickName("WowCompanionPartyToggle", true); clickName("WowCompanionPartyToggle", false); advance(0.3)')
check(party_on_all(W) == [None] * 9 and W.eval('PADA("wc-pmenu")') is None, "TOGGLE from on: off, wc-pmenu cleared", party_on_all(W))
W.execute('COMBAT = true')
W.execute('clickName("WowCompanionPartyToggle", true); NOW = NOW + 0.3')
check(party_on_all(W) == [True] * 9 and W.eval('#VIOL') == 0, "TOGGLE in a fight: on (secure snippets only, no lockdown breach)",
      list(W.eval('VIOL').values()))
W.execute('COMBAT = false')
flip(W, "WowCompanionPartyOff")
d = docs(W, 0, 'partybox')
check(len(d) >= 5 and [x['b']['k'] for x in d[-3:]] == ['off', 'on', 'off'], "every flip sends ONE partybox (k on / off)",
      [x['b']['k'] for x in d])

# ================================================================ 2. PARTYPAD
NAV = {  # PARTY_BUILD.md 3.4: (n, from) -> (Up, Down, Left, Right); None = no move
    (1, 1): (None, None, None, None), (2, 1): (None, 2, None, None), (2, 2): (1, None, None, None),
    (3, 1): (None, 2, None, None), (3, 2): (1, None, None, 3), (3, 3): (1, None, 2, None),
    (4, 1): (None, 2, None, None), (4, 2): (1, None, None, 3), (4, 3): (1, None, 2, 4), (4, 4): (1, None, 3, None),
    (5, 1): (None, 2, None, None), (5, 2): (1, 4, None, 3), (5, 3): (1, 5, 2, None), (5, 4): (2, None, None, 5), (5, 5): (3, None, 4, None),
}
DIRS = ("up", "down", "left", "right")
DPOS = {"up": 2, "down": 4, "left": 1, "right": 3}
NAVL = lupa.LuaRuntime(unpack_returned_tuples=True)
NAVL.execute('OV = {}\n' + re.search(r'-- PARTY-NAV-BEGIN[^\n]*\n(.*?)-- PARTY-NAV-END', SRC["padmap"], re.S).group(1))
nav = NAVL.eval('OV.partyNav')
bad = []
for (n, fr), want in NAV.items():
    for dname, w in zip(DIRS, want):
        got = nav(n, fr, dname)
        if got != (w if w is not None else fr):
            bad.append((n, fr, dname, got))
check(not bad, "OV.partyNav = the 3.4 table, all 15 rows x 4 directions", bad[:4])
nav_lua = re.search(r'-- PARTY-NAV-BEGIN.*?OV\.partyNav = function\(n, box, dir\)\n(.*?)\n\s*return nb\n\s*end\n\s*-- PARTY-NAV-END', SRC_PM := open(F["padmap"], encoding="utf-8").read(), re.S)
nav_snip = re.search(r'-- PARTY-NAV-BODY-BEGIN\n(.*?)\n\s*-- PARTY-NAV-BODY-END', SRC_PM, re.S)
norm = lambda t: re.sub(r'\s+', ' ', t).strip()
check(nav_lua is not None and nav_snip is not None and norm(nav_lua.group(1)) == norm(nav_snip.group(1)),
      "the PARTYPAD snippet carries the same NAV body as OV.partyNav")
# the snippet itself, member counts 1..5, every row and direction, through the router (Party mode on)
flip(W, "WowCompanionPartyOn")
MEMBERS = [["Brannoc", "WARRIOR", True, "TANK"], ["Iselle", "MAGE"], ["Doran", "ROGUE", True, "DAMAGER", "ENERGY"], ["Mira", "PRIEST", True, "HEALER", "MANA"]]
bad = []
for (n, fr), want in NAV.items():
    W.eval('party')(W.table_from([W.table_from(m) for m in MEMBERS[:n - 1]]))
    for dname, w in zip(DIRS, want):
        W.execute('rawget(_G, "WowCompanionPartyPad").a["wc-box"] = %d' % fr)
        a0 = nact(W)
        W.execute('hit(%d)' % DPOS[dname])
        got = W.eval('PADA("wc-box")')
        if got != (w if w is not None else fr) or nact(W) != a0 or W.eval('PADA("wc-k")') != "move":
            bad.append((n, fr, dname, got, W.eval('PADA("wc-k")')))
check(not bad, "PARTYPAD's D-pad = the 3.4 table for n = 1..5 (through the router; never a game action)", bad[:4])
W.execute('party({}); rawget(_G, "WowCompanionPartyPad").a["wc-box"] = 4')
W.execute('hit(2)')
check(W.eval('PADA("wc-box")') == 1, "a box past n (someone left) clamps to n", W.eval('PADA("wc-box")'))
check(W.eval('#SNIPERR') == 0, "PARTYPAD runs in the restricted environment without an error", W.eval('SNIPERR[1]'))


def setps(L, **kv):
    L.execute('local p = rawget(_G, "WowCompanionPartyPad"); for _, k in ipairs({"a","x","zla","zlx","zly","zra","zrx","zry"}) do p.a["wc-ps-" .. k] = nil end')
    for k, v in kv.items():
        L.execute('rawget(_G, "WowCompanionPartyPad").a["%s"] = %s' % ("wc-ps-" + k, json.dumps(v)))


W.eval('party')(W.table_from([W.table_from(m) for m in MEMBERS[:2]]))
setps(W, a="Healing Wave", x="Lesser Healing Wave", zla="Cure Poison", zlx="Cure Disease", zra="Ancestral Spirit")
W.execute('rawget(_G, "WowCompanionPartyPad").a["wc-box"] = 1; hit(4)')         # box 2 = party1
cases = [((False, False), 8, ("spell", "Healing Wave", "party1")), ((False, False), 5, ("spell", "Lesser Healing Wave", "party1")),
         ((True, False), 8, ("spell", "Cure Poison", "party1")), ((True, False), 5, ("spell", "Cure Disease", "party1")),
         ((False, True), 8, ("spell", "Ancestral Spirit", "party1"))]
for (lt, rt), pos, want in cases:
    W.execute('trig(%s, %s)' % ('true' if lt else 'false', 'true' if rt else 'false'))
    a0 = nact(W)
    W.execute('hit(%d)' % pos)
    got = acts(W, a0)
    check(got == [want], "%s%s: casts %s on %s, once" % ({(False, False): "", (True, False): "ZL+", (False, True): "ZR+"}[(lt, rt)],
                                                          {8: "A", 5: "X"}[pos], want[1], want[2]), got)
for lt, rt, pos, why in ((False, True, 5, "ZR+X Empty"), (True, False, 6, "ZL+Y Empty"), (True, True, 8, "ZL+ZR+A (both triggers)")):
    W.execute('trig(%s, %s)' % ('true' if lt else 'false', 'true' if rt else 'false'))
    a0 = nact(W)
    W.execute('hit(%d)' % pos)
    check(acts(W, a0) == [] and W.eval('PADA("wc-k")') == "none", why + ": nothing", (acts(W, a0), W.eval('PADA("wc-k")')))
W.execute('trig(false, false); party({}); rawget(_G, "WowCompanionPartyPad").a["wc-box"] = 1')
a0 = nact(W)
W.execute('hit(8)')
check(acts(W, a0) == [("spell", "Healing Wave", "player")], "solo: A casts on yourself (player)", acts(W, a0))
# the key-up changes nothing (whichever edge the template acts on)
W.execute('ACT_ON_DOWN = false')
a0 = nact(W)
W.execute('hit(8)')
check(acts(W, a0) == [("spell", "Healing Wave", "player")], "the template acting on the RELEASE: still one cast, the down's", acts(W, a0))
W.execute('ACT_ON_DOWN = true')
W.execute('local b = router(8); doClick(b, "LeftButton", true)')
before = dict(W.eval('rawget(_G, "WowCompanionPartyPad")').a)
W.execute('local b = router(8); doClick(b, "LeftButton", false)')
after = dict(W.eval('rawget(_G, "WowCompanionPartyPad")').a)
check(before == after, "the key-up leaves every pad attribute as the key-down set it")

# Y out of combat: Smart Y's pick on a living member; dead / no pick -> the menu
W.eval('party')(W.table_from([W.table_from(m) for m in MEMBERS[:2]]))
W.execute('local p = rawget(_G, "WowCompanionPartyPad"); p.a["wc-box"] = 2; p.a["wc-pb-party1"] = "Water Breathing"; p.a["wc-pmenu"] = nil')
a0 = nact(W)
W.execute('hit(6)')
check(acts(W, a0) == [("spell", "Water Breathing", "party1")] and W.eval('PADA("wc-k")') == "cast", "Y out of combat: Smart Y's pick on the member",
      acts(W, a0))
W.execute('UNITS.party1.alive = false')
a0 = nact(W)
W.execute('hit(6)')
check(acts(W, a0) == [] and W.eval('PADA("wc-k")') == "menu" and W.eval('PADA("wc-pmenu")') is True and W.eval('PADA("wc-u")') == "party1",
      "Y out of combat, the member dead: the player menu (wc-pmenu, wc-u)", (acts(W, a0), W.eval('PADA("wc-k")')))
W.execute('UNITS.party1.alive = true; rawget(_G, "WowCompanionPartyPad").a["wc-pmenu"] = nil; rawget(_G, "WowCompanionPartyPad").a["wc-pb-party1"] = nil')
a0 = nact(W)
W.execute('hit(6)')
check(acts(W, a0) == [] and W.eval('PADA("wc-k")') == "menu", "Y out of combat, no pick: the menu", W.eval('PADA("wc-k")'))
# the menu sub-state swallows every press as "key"
W.execute('rawget(_G, "WowCompanionPartyPad").a["wc-box"] = 2')
bad = []
for pos in (1, 2, 3, 4, 8, 5, 6, 7):
    for lt, rt in ((False, False), (True, False)):
        W.execute('trig(%s, %s)' % ('true' if lt else 'false', 'true' if rt else 'false'))
        a0 = nact(W)
        W.execute('hit(%d)' % pos)
        if acts(W, a0) != [] or W.eval('PADA("wc-k")') != "key" or W.eval('PADA("wc-box")') != 2 or W.eval('PADA("wc-party")') is not True:
            bad.append((pos, lt, W.eval('PADA("wc-k")')))
check(not bad, "the menu sub-state: D-pad, A, X, Y, B and chords are each a \"key\" press (no game action, box kept, Party mode kept)", bad[:3])
W.execute('trig(false, false)')
W.execute('hit(2)')
check(W.eval('PADA("wc-key")') == "PADDUP", "a key press says which key (wc-key PADDUP)", W.eval('PADA("wc-key")'))
d = docs(W, 0, 'partybox')[-1]['b']
check(d['k'] == 'key' and d['key'] == 'PADDUP' and d['pm'] is True and d['box'] == 2, "its partybox: k key, key PADDUP, pm true", d)
flip(W, "WowCompanionPartyMenuDone")
check(W.eval('PADA("wc-pmenu")') is None and W.eval('PADA("wc-party")') is True, "MENU DONE clears the sub-state (Party mode stays on)")
# Y in combat: wc-py on a help,nodead member, else the menu
W.execute('COMBAT = true; rawget(_G, "WowCompanionPartyPad").a["wc-py"] = "Healing Wave"; rawget(_G, "WowCompanionPartyPad").a["wc-pb-party1"] = "Water Breathing"')
a0 = nact(W)
W.execute('hit(6)')
check(acts(W, a0) == [("spell", "Healing Wave", "party1")], "Y in combat: its in-combat spell on the member (never Smart Y's buff)", acts(W, a0))
for why, setup in (("dead", 'UNITS.party1.alive = false'), ("not assistable", 'UNITS.party1.foe = true')):
    W.execute('UNITS.party1.alive = true; UNITS.party1.foe = nil; rawget(_G, "WowCompanionPartyPad").a["wc-pmenu"] = nil; ' + setup)
    a0 = nact(W)
    W.execute('hit(6)')
    check(acts(W, a0) == [] and W.eval('PADA("wc-k")') == "menu", "Y in combat, the member " + why + ": the menu", W.eval('PADA("wc-k")'))
W.execute('UNITS.party1.alive = true; UNITS.party1.foe = nil; local p = rawget(_G, "WowCompanionPartyPad"); p.a["wc-pmenu"] = nil; p.a["wc-py"] = nil')
a0 = nact(W)
W.execute('hit(6)')
check(acts(W, a0) == [] and W.eval('PADA("wc-k")') == "menu", "Y in combat, Inspect chosen (no wc-py): the menu", W.eval('PADA("wc-k")'))
W.execute('rawget(_G, "WowCompanionPartyPad").a["wc-pmenu"] = nil')
# B = Party mode off on all 8 refs, in a fight too
W.execute('hit(7)')
check(party_on_all(W) == [None] * 9 and W.eval('PADA("wc-k")') == "off" and W.eval('#VIOL') == 0, "B: Party mode off on all 8 router buttons and the pad (in a fight)",
      party_on_all(W))
a0 = nact(W)
W.execute('hit(8)')
check(W.eval('PADA("wc-k")') == "off" and all(a[0] != "spell" or a[2] is None for a in acts(W, a0)),
      "after B the next A is the router's own again (not the pad's)", acts(W, a0))
W.execute('COMBAT = false')
check(W.eval('#SNIPERR') == 0 and W.eval('#SPY') == 0, "no snippet error, no spied call", (W.eval('SNIPERR[1]'), W.eval('SPY[1]')))

# ================================================================ THE NO-TRACE SCAN (new code only: the diff against the contract's base)
import subprocess
try:
    diff = subprocess.run(['git', '-C', ROOT, 'diff', '5ee1ff22', '--', 'companion/app/src/main/assets/companion/addons'],
                          capture_output=True, text=True, encoding='utf-8').stdout
except Exception as e:                                                    # no git: scan nothing (said)
    diff = ''
    print('NOTE no git diff: ' + str(e))
added = [ln[1:] for ln in diff.splitlines() if ln.startswith('+') and not ln.startswith('+++')]
code = [re.sub(r'--.*$', '', ln) for ln in added]
BAD = [r'\bSetBinding\s*\(', r'\bSaveBindings\b', r'\bCreateMacro\b', r'\bEditMacro\b', r'\bSetCVar\b', r'\bPlaceAction\b',
       r'\bPickupAction\b', r'\bPickupMacro\b', r'\bShowUIPanel\b', r'\bHideUIPanel\b', r'\bToggleFrame\b', r'\bInspectUnit\b',
       r'\bUnitPopup_OpenMenu\b', r'\bTargetFrame_OpenMenu\b', r'\bToggleGameMenu\b', r'\bCloseAllBags\b', r'\bOpenAllBags\b',
       r'\bSetOverrideBinding\s*\(']
hits = [(p, ln.strip()) for ln in code for p in BAD if re.search(p, ln)]
check(not hits, "no trace in the new add-on code: no SetBinding / SaveBindings / macros / CVars / bar writes / Blizzard window code",
      hits[:3])
check(not any('SetOverrideBindingClick' in ln and 'hdr' not in ln and 'owner' not in ln for ln in code),
      "every new key binding is an override on our own owner frame")

print('ALL PASS (%d checks)' % passes if fails == 0 else '%d FAILED (of %d)' % (fails, fails + passes))
sys.exit(1 if fails else 0)
