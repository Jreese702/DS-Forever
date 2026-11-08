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


# ================================================================ STATIC: every touched file compiles as Lua 5.1, under its limits
SL = lupa.LuaRuntime()
comp = SL.eval('function(s, n) local f, e = loadstring(s, n); return e end')
for k in ("tappad", "quests", "book", "padmap", "padtabctl"):
    e = comp(SRC[k], k)
    check(e is None, "Lua 5.1 compiles %s (200 locals, 60 upvalues)" % os.path.basename(F[k]), e or '')

# ================================================================ 10. TapPad pad11
check('local PADV = "pad18"' in SRC["tappad"], "TapPad says pad18 (pad11's party row + the resting cursor hidden under the software cursor)")
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
check(d['v'] == 'pad18', "describe() says pad18", d['v'])
L10, d, rows = pad_at(121.5)
check(abs(float(d['popupTop']) - 121.5) < 0.06 and rows == list(range(54)), "pad11 at popupTop 121.5 (smallest scale): rows 0..53 only",
      (d['popupTop'], rows[-3:], len(rows)))
hidden = L10.eval('(function() local r = WowCompanion.pad.rows["r54"]; local s = WowCompanion.pad.rows["s55"]; '
                  'return (r.cells[0]._shown == false) and (s.cells[0]._shown == false) end)()')
check(hidden is True, "rows 54-55 are hidden at the smallest scale (never inside a dialog)", hidden)

# ================================================================ 1. PRE + Party mode (the router's real snippet, restricted)
PRE = re.search(r'local PRE = \[=\[(.*?)\]=\]', SRC["padmap"], re.S).group(1)
# m94 (test9, 2026-11-07: every A / X / Y / B / D-pad press dead): the game's secure checker refuses the word "function" ANYWHERE in a
# snippet, comments included ("The function keyword is not permitted"); no secure snippet of any add-on may hold it
_snip_bad = []
for _k, _src in SRC.items():
    for _m in re.finditer(r'\[=*\[(.*?)\]=*\]', _src, re.S):
        if re.search(r'\bfunction\b', _m.group(1)) and ('SetAttribute' in _m.group(1) or 'self:' in _m.group(1)):
            _snip_bad.append((_k, _src[:_m.start()].count('\n') + 1))
check(not _snip_bad, "no secure snippet holds the word 'function' (the game refuses the whole snippet, comments too)", _snip_bad)
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
# m99 (the user: "pressing B in combat ... should stop the auto attack ... Keeping that enemy targeted, pressing B again would drop the target")
check(k[0] == "bstop" and k[1] == "macro" and k[2] == "/stopattack", "m99: the first B in a fight stops the attack (the target kept)", k[:3])
k = G1.eval('pressPre')(f, True)
check(k[0] == "bstop" and k[2] == "/stopattack\n/cleartarget", "m99: B again (already given back) clears the target", k[:3])
# m101 THE TARGET RADIAL (the user, 2026-11-07): a bare Y out of a fight with a target and nothing else on Y -> "tradial", no game action
G1.execute('COMBAT = false')
f = G1.eval('routerBtn')(6, "PAD4", None, False)
f.a["wc-qitem"] = None; f.a["wc-smarty"] = None; f.a["wc-tradial"] = True; f.a["wc-state"] = "idle"
k = G1.eval('pressPre')(f, True)
check(k[0] == "tradial" and k[1] is None, "m101: Y out of a fight with a target: tradial, no game action", k[:3])
f.a["wc-tradial"] = None
k = G1.eval('pressPre')(f, True)
check(k[0] != "tradial", "m101: without wc-tradial (the switch off): Y as before", k[:3])
f.a["wc-tradial"] = True; f.a["wc-qitem"] = "item:1"
k = G1.eval('pressPre')(f, True)
check(k[0] == "qitem", "m101: a quest item for the target still wins over the radial", k[:3])
f.a["wc-qitem"] = None
G1.execute('UNITS.target = nil')
k = G1.eval('pressPre')(f, True)
check(k[0] != "tradial", "m101: no target: Y as before (Forever's Inspect / Smart Y)", k[:3])
G1.execute('UNITS.target = { alive = true, foe = true }')
# m102 (the user: "go away when I /startattack, same rules as the button grow animation"): the controller grown = no radial
f.a["wc-pre"] = True
k = G1.eval('pressPre')(f, True)
check(k[0] != "tradial", "m102: an attack started (wc-pre, out of combat): no radial", k[:3])
f.a["wc-pre"] = None
G1.execute('COMBAT = true'); f.a["wc-state"] = "fight"
k = G1.eval('pressPre')(f, True)
check(k[0] != "tradial", "m102: in a fight (grown): no radial", k[:3])
f.a["wc-given"] = True
k = G1.eval('pressPre')(f, True)
check(k[0] == "tradial", "m102: in a fight after B (given back): the radial again", k[:3])
f.a["wc-given"] = None; f.a["wc-state"] = "idle"
G1.execute('COMBAT = false')
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
# the contract's base b187c3c1 (+ m78 Manners), then m101 (the user's ruling 2026-11-07: a soft-interact target no longer blocks Smart Y):
# the file is pinned by its content now; change it only on a ruling, and re-pin here
import hashlib
smarty_sha = hashlib.sha256(open(os.path.join(ROOT, 'tools', 'test_smarty.py'), 'rb').read()).hexdigest()
check(smarty_sha == "d7707cb7c1cac46fa77e722b844d3c812937c5377303e36132114b1981b3b7c2", "tools/test_smarty.py is byte for byte the pinned contract (b187c3c1 + m78 Manners + m101 soft-interact)", smarty_sha[:12])

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
-- the book (Book.lua reads the game's own spellbook, stubbed empty here): the character's spells are BOOK's
WowCompanion.spellsBindableList = function()
    local out = {}
    for i, id in ipairs(BOOK) do out[#out + 1] = { id = id, slot = i } end
    return out
end
WowCompanion.spellsBookSlotOf = function(id) for i, x in ipairs(BOOK) do if x == id then return i end end end
'''


def world(pre='', files=("safety", "tappad", "book", "padmap", "padtabctl"), ok=True):
    # m100: Party mode is an experimental switch (Hub's "uisw" 16 / 17 -> WowCompanion.partyOk); the worlds here have it ON unless ok=False
    L = boot(list(files), pre=('WowCompanion.partyOk = true; ' if ok else '') + pre)
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

# ================================================================ 4. THE BINDINGS (the binding table is a spy: only overrides on our owners)
OURKEYS = ["PAD1", "PAD2", "PAD3", "PAD4", "PADDLEFT", "PADDUP", "PADDRIGHT", "PADDDOWN", "PADBACK", "SHIFT-PAD1", "PADFORWARD"]


def keys(L):
    b = dict(L.eval('BIND'))
    return {k: v for k, v in b.items() if k in OURKEYS}


def router_on(L):
    return {k: "CLICK %s:LeftButton" % ROUTER_NAME[KEY_POS[k]] for k in KEY_POS}


W4 = world(pre='WowCompanionSpellsDB.binds = { ["Korvash-Realm"] = { ["2:8"] = "JUMP", ["1:1"] = "TOGGLEAUTORUN" } }')
k = keys(W4)
check(k.get("PADBACK") == "CLICK WowCompanionPartyToggle:LeftButton" and k.get("SHIFT-PAD1") == "JUMP" and k.get("PADDLEFT") == "TOGGLEAUTORUN"
      and "PAD1" not in k, "Party mode OFF, out of combat: Select -> the toggle; today's plan otherwise (the m34 chords bound)", k)
W4.execute('tapKey("PADBACK"); advance(0.3)')
k = keys(W4)
want = router_on(W4)
want["PADBACK"] = "CLICK WowCompanionPartyToggle:LeftButton"
check(W4.eval('PADA("wc-party")') is True and k == want, "Select ON: all 8 router keys carried + Select; NO m34 chord (SHIFT-PAD1, PADDLEFT)", k)
W4.execute('NOW = NOW + 2; tick(0.1)')
check(keys(W4) == want, "routerCheck keeps it (nothing lost)", keys(W4))
W4.execute('BIND["PAD3"] = "INTERACTTARGET"; NOW = NOW + 2; tick(0.1)')
check(keys(W4).get("PAD3") == "CLICK WowCompanionCombatFace5:LeftButton", "a key taken from us is bound again by routerCheck", keys(W4).get("PAD3"))
# the whole road by keys: D-pad Down, A, B
W4.eval('party')(W4.table_from([W4.table_from(m) for m in MEMBERS[:2]]))
setps(W4, a="Healing Wave")
W4.execute('rawget(_G, "WowCompanionPartyPad").a["wc-box"] = 1')
a0 = nact(W4)
W4.execute('tapKey("PADDDOWN"); tapKey("PAD1")')
check(acts(W4, a0) == [("spell", "Healing Wave", "party1")], "by keys: Down boxes party1, A heals party1", acts(W4, a0))
W4.execute('tapKey("PAD2"); advance(0.3)')
k = keys(W4)
check(W4.eval('PADA("wc-party")') is None and k.get("SHIFT-PAD1") == "JUMP" and k.get("PADDLEFT") == "TOGGLEAUTORUN" and "PAD1" not in k
      and k.get("PADBACK") == "CLICK WowCompanionPartyToggle:LeftButton", "B: off, today's plan back (chords bound, A the game's)", k)
# an unfocused dialog keeps Select the game's; routerCheck follows it
W4.execute('UNFOCUSED = 1; NOW = NOW + 2; tick(0.1)')
check("PADBACK" not in keys(W4), "an unfocused dialog waits: Select is left to the game", keys(W4).get("PADBACK"))
W4.execute('UNFOCUSED = 0; NOW = NOW + 2; tick(0.1)')
check(keys(W4).get("PADBACK") == "CLICK WowCompanionPartyToggle:LeftButton", "the dialog gone: Select is the toggle's again")
# THE USER'S RULINGS (INTEGRATION, PM.OFF_WHEN_ASIDE = true): ANYTHING that takes the controller ends Party mode - a room, a Home
# module full screen ("pad:home"), Home's pause wheel ("pad:radial"), the bar cursor ("pad:bar"), a game window (a vendor) with nobody
# holding
check('PM.OFF_WHEN_ASIDE = true' in SRC["padmap"], "PM.OFF_WHEN_ASIDE is true (the user's rulings: anything that takes the controller)")
for holder, win in (("spells", None), ("pad:home", None), ("pad:radial", None), ("pad:bar", None), (None, "MerchantFrame"), (None, "LootFrame")):
    what = holder or win
    W4.execute('if not PADA("wc-party") then tapKey("PADBACK"); advance(0.3) end')
    on0 = W4.eval('PADA("wc-party")')
    n0 = nsent(W4)
    if holder:
        W4.execute('HOLDER = "%s"' % holder)
    else:
        W4.execute('%s = { IsShown = function() return true end }' % win)
    W4.execute('NOW = NOW + 2; tick(0.1); advance(0.6)')
    pd = docs(W4, n0, 'party')
    check(on0 is True and party_on_all(W4) == [None] * 9 and pd and pd[-1]['p']['on'] is False
          and not any(v.startswith("CLICK WowCompanion") for v in keys(W4).values()),
          "%s takes the controller: Party mode OFF on all 8 refs and the pad, a party document says on false, nothing of ours bound" % what,
          (on0, party_on_all(W4), pd and pd[-1]['p']))
    W4.execute('HOLDER = nil; %s NOW = NOW + 2; tick(0.1)' % (('%s = nil;' % win) if win else ''))
    k = keys(W4)
    check(W4.eval('PADA("wc-party")') is None and k.get("SHIFT-PAD1") == "JUMP" and "PAD1" not in k
          and k.get("PADBACK") == "CLICK WowCompanionPartyToggle:LeftButton",
          "%s let go: Party mode is still OFF (today's plan, Select the toggle's)" % what, k)
# IN A FIGHT (secure attributes locked): the add-on cannot flip it. (a) the APP's tap on party col 11 (a secure click) ends it at once,
# with no lockdown breach; the 8 keys stay carried to the router buttons for the fight (today's fight carry), which now act as today
W4.execute('tapKey("PADBACK"); advance(0.3); fire("PLAYER_REGEN_DISABLED"); COMBAT = true; HOLDER = "spells"')
W4.execute('local c = WowCompanion.pad.rows.party.cells[11]; doClick(c, "LeftButton", true); doClick(c, "LeftButton", false); NOW = NOW + 0.3')
k = keys(W4)
check(party_on_all(W4) == [None] * 9 and W4.eval('#VIOL') == 0 and k.get("PAD1") == "CLICK WowCompanionCombatFace8:LeftButton",
      "a room takes the controller in a fight, the app taps party col 11: OFF at once (secure, no breach); A stays the fight router's", k.get("PAD1"))
W4.execute('HOLDER = nil; COMBAT = false; fire("PLAYER_REGEN_ENABLED"); advance(1); NOW = NOW + 2; tick(0.1)')
check(W4.eval('PADA("wc-party")') is None and "PAD1" not in keys(W4), "... the fight ends: still off, today's plan", keys(W4).get("PAD1"))
# (b) the app's tap lost: the add-on notes the taker (its 1 s tick / the capture's onChange) and turns Party mode off at the fight's end,
# even when the taker let go before it
W4.execute('tapKey("PADBACK"); advance(0.3); fire("PLAYER_REGEN_DISABLED"); COMBAT = true; HOLDER = "spells"; tick(1)')
check(W4.eval('PADA("wc-party")') is True and W4.eval('#VIOL') == 0, "a room takes it in a fight, no tap from the app: still set (no insecure flip)",
      list(W4.eval('VIOL').values())[:2])
W4.execute('HOLDER = nil; tick(1)')
n0 = nsent(W4)
W4.execute('COMBAT = false; fire("PLAYER_REGEN_ENABLED"); advance(1); NOW = NOW + 2; tick(0.1)')
pd = docs(W4, n0, 'party')
check(party_on_all(W4) == [None] * 9 and pd and pd[-1]['p']['on'] is False and "PAD1" not in keys(W4),
      "... the fight ends (the room already let go): OFF, the party document says so, today's plan", (party_on_all(W4), keys(W4).get("PAD1")))
# (c) a game window seen in the fight (a loot window): the same at the fight's end
W4.execute('tapKey("PADBACK"); advance(0.3); fire("PLAYER_REGEN_DISABLED"); COMBAT = true; LootFrame = { IsShown = function() return true end }; tick(1)')
W4.execute('LootFrame = nil; COMBAT = false; fire("PLAYER_REGEN_ENABLED"); advance(1); NOW = NOW + 2; tick(0.1)')
check(W4.eval('PADA("wc-party")') is None, "a loot window in a fight: OFF at the fight's end", W4.eval('PADA("wc-party")'))
# (d) nothing took the controller: a fight leaves Party mode on
W4.execute('tapKey("PADBACK"); advance(0.3); fire("PLAYER_REGEN_DISABLED"); COMBAT = true; tick(1)')
W4.execute('COMBAT = false; fire("PLAYER_REGEN_ENABLED"); advance(1); NOW = NOW + 2; tick(0.1)')
check(W4.eval('PADA("wc-party")') is True and keys(W4) == want, "a fight with no taker: Party mode stays on, its keys bound", keys(W4))
W4.execute('tapKey("PADBACK"); advance(0.3)')
# a fight's start: Select only while grouped or already in Party mode
W4.eval('party')(W4.table_from([]))
W4.execute('fire("PLAYER_REGEN_DISABLED"); COMBAT = true')
k = keys(W4)
check("PADBACK" not in k and k.get("PAD2") == "CLICK WowCompanionFaceB:LeftButton", "a fight's start, solo, Party mode off: Select stays the game's (the router carries its keys)", k)
W4.execute('COMBAT = false; fire("PLAYER_REGEN_ENABLED"); advance(1)')
W4.eval('party')(W4.table_from([W4.table_from(MEMBERS[0])]))
W4.execute('fire("PLAYER_REGEN_DISABLED"); COMBAT = true')
k = keys(W4)
check(k.get("PADBACK") == "CLICK WowCompanionPartyToggle:LeftButton", "a fight's start, grouped: Select carried to the toggle", k.get("PADBACK"))
W4.execute('tapKey("PADBACK")')
check(W4.eval('PADA("wc-party")') is True and W4.eval('#VIOL') == 0, "in the fight Select switches Party mode on (secure only)", list(W4.eval('VIOL').values()))
a0 = nact(W4)
W4.execute('rawget(_G, "WowCompanionPartyPad").a["wc-box"] = 1; tapKey("PADDDOWN"); tapKey("PAD1")')
check(acts(W4, a0) == [("spell", "Healing Wave", "party1")], "... and its presses reach the party pad mid-fight (carried keys)", acts(W4, a0))
W4.execute('COMBAT = false; fire("PLAYER_REGEN_ENABLED"); advance(1)')
W4.eval('party')(W4.table_from([]))
W4.execute('fire("PLAYER_REGEN_DISABLED"); COMBAT = true')
check(keys(W4).get("PADBACK") == "CLICK WowCompanionPartyToggle:LeftButton", "a fight's start, solo but Party mode already on: Select carried",
      keys(W4).get("PADBACK"))
W4.execute('COMBAT = false; fire("PLAYER_REGEN_ENABLED"); advance(1)')
W4.execute('UNFOCUSED = 1; fire("PLAYER_REGEN_DISABLED"); COMBAT = true')
check("PADBACK" not in keys(W4), "a fight's start with an unfocused dialog: Select stays the game's", keys(W4).get("PADBACK"))
W4.execute('COMBAT = false; UNFOCUSED = 0; fire("PLAYER_REGEN_ENABLED"); advance(1)')
check(W4.eval('#SPY') == 0 and W4.eval('#VIOL') == 0, "the spy: no SetBinding / SaveBindings / macro / CVar / bar write / Blizzard window call; no lockdown breach",
      (list(W4.eval('SPY').values())[:3], list(W4.eval('VIOL').values())[:3]))

# ================================================================ 6. THE STORE AND THE CLASS DEFAULTS
DEFAULTS = {  # PARTY_BUILD.md 3.6
    "SHAMAN": {"a": 331, "x": 8004, "zla": 526, "zlx": 2870, "zra": 2008},
    "PRIEST": {"a": 2050, "x": 17, "zla": 527, "zlx": 528, "zra": 2006, "zrx": 139},
    "DRUID": {"a": 5185, "x": 774, "zla": 8946, "zlx": 2782, "zra": 20484, "zrx": 8936},
    "PALADIN": {"a": 635, "x": 19750, "zla": 1152, "zlx": 4987, "zra": 7328, "zrx": 633},
    "MAGE": {"a": 475, "zla": 604, "zlx": 1008}, "WARLOCK": {"a": 5697}, "HUNTER": {}, "ROGUE": {}, "WARRIOR": {},
}
SLOTS = ["a", "x", "zla", "zlx", "zly", "zra", "zrx", "zry"]


def cfg(L):
    return L.eval('WowCompanion.padMap.party.cfg()')


def slot_ids(c):
    s = c['slots']
    return {k: int(s[k]['id']) for k in SLOTS if s[k] is not None}


W6 = world()
c = cfg(W6)
check(slot_ids(c) == DEFAULTS["SHAMAN"], "a level 6 Shaman with nothing chosen: the class defaults", slot_ids(c))
check(c['slots']['a']['k'] is True and c['slots']['a']['d'] is True and c['slots']['x']['k'] is False and c['slots']['zra']['res'] is True,
      "Healing Wave learned (k), Lesser Healing Wave not yet (k false, drawn dim), Ancestral Spirit res", "")
check(W6.eval('PADA("wc-ps-a")') == "Healing Wave" and W6.eval('PADA("wc-ps-x")') is None and W6.eval('PADA("wc-ps-zra")') is None,
      "the pad casts only learned spells: wc-ps-a Healing Wave; x / zra (not learned) nil", (W6.eval('PADA("wc-ps-x")'),))
bad = []
for cls, want in DEFAULTS.items():
    W6.execute('CLASS = "%s"' % cls)
    got = slot_ids(cfg(W6))
    if got != want:
        bad.append((cls, got))
check(not bad, "the class defaults for all 9 classes (the contract's table)", bad[:2])
W6.execute('CLASS = "SHAMAN"')
n0 = nsent(W6)
ok = W6.eval('WowCompanion.padMap.party.put')("x", False)
check(ok is True and W6.eval('WowCompanionSpellsDB.partyBy["Korvash-Realm"].slots.x') is False and cfg(W6)['slots']['x'] is None,
      "put x = Empty: stored as false (chosen), the document's x is null", W6.eval('WowCompanionSpellsDB.partyBy["Korvash-Realm"].slots.x'))
ack = docs(W6, n0, 'partyack')
check(len(ack) == 1 and ack[0]['op'] == 'slot' and ack[0]['ok'] is True and ack[0]['slot'] == 'x', "answered partyack op slot ok", ack)
check(W6.eval('WowCompanionSpellsDB.partyBy["Korvash-Realm"].slots.a') is None, "a not chosen stays nil (the class default)")
for sid, why in ((324, "Lightning Shield: a self-only buff"), (403, "Lightning Bolt: harmful")):
    n0 = nsent(W6)
    W6.eval('WowCompanion.padMap.party.put')("a", sid)
    ack = docs(W6, n0, 'partyack')
    check(ack and ack[0]['ok'] is False and ack[0]['why'] == 'not a party spell', "a spell not in the chooser is refused (%s)" % why, ack)
W6.execute('BOOK = { 403, 331, 324, 8004 }; fire("SPELLS_CHANGED"); advance(1)')
check(W6.eval('PADA("wc-ps-x")') is None, "x stays Empty when its default is learned later (Empty was CHOSEN)")
W6.eval('WowCompanion.padMap.party.put')("zry", 8004)
check(W6.eval('PADA("wc-ps-zry")') == "Lesser Healing Wave" and int(cfg(W6)['slots']['zry']['id']) == 8004 and cfg(W6)['slots']['zry']['d'] is False,
      "put zry = Lesser Healing Wave (learned now): the pad casts it, d false", W6.eval('PADA("wc-ps-zry")'))
W6.eval('WowCompanion.padMap.party.put')("yc", 331)
W6.eval('WowCompanion.padMap.party.put')("touch", 8004)
check(W6.eval('PADA("wc-py")') == "Healing Wave" and W6.eval('WowCompanionSpellsDB.partyBy["Korvash-Realm"].yc') == 331,
      "yc = Healing Wave: wc-py set, stored by id", W6.eval('PADA("wc-py")'))
W6.eval('WowCompanion.padMap.party.put')("yc", False)
check(W6.eval('PADA("wc-py")') is None and W6.eval('WowCompanionSpellsDB.partyBy["Korvash-Realm"].yc') is None, "yc back to Inspect: nil")
W6.execute('WHO = "Alt"')
c2 = cfg(W6)
check(slot_ids(c2) == DEFAULTS["SHAMAN"] and W6.eval('WowCompanionSpellsDB.partyBy["Alt-Realm"].slots.x') is None
      and W6.eval('WowCompanionSpellsDB.partyBy["Korvash-Realm"].slots.x') is False, "another character (name-realm) never shares: its own defaults",
      slot_ids(c2))
W6.execute('WHO = "Korvash"')
check(W6.eval('WowCompanionSpellsDB.partyBy["Korvash-Realm"].v') == 1 and len(W6.eval('WowCompanionSpellsDB.partyBy["Korvash-Realm"].buffs')) == 0,
      "the record: v = 1, Smart Y's party list starts EMPTY")

# ================================================================ 10b. THE PARTY ROW (2.2)
row = W6.eval('WowCompanion.pad.rows.party')
check(row is not None and row.secure and row.n == 40 and row.onPress is True, "the party row: secure, 40 columns, opts.onPress", row and row.n)
cell = lambda col: row.cells[col]
U = ["player", "party1", "party2", "party3", "party4"]
bad = []
for col in range(40):
    a = cell(col).a
    t, sp, un, cb = a['type'], a['spell'], a['unit'], a['clickbutton']
    if col <= 4: want = ("target", None, U[col], None)
    elif col <= 9: want = ("spell", "Lesser Healing Wave", U[col - 5], None)
    elif col == 10: want = ("click", None, None, "WowCompanionPartyOn")
    elif col == 11: want = ("click", None, None, "WowCompanionPartyOff")
    elif col <= 16: want = ("focus", None, U[col - 12], None)
    elif col == 35: want = ("click", None, None, "WowCompanionPartyMenuDone")
    elif col == 36: want = ("macro", None, None, None)          # m91: Hub's /startattack cell (WC.atkCell)
    else: want = (None, None, None, None)
    got = (t, sp, un, cb._name if cb is not None else None)
    if got != want:
        bad.append((col, got, want))
check(not bad, "every party row cell as 2.2 says (TGT 0-4, TSP 5-9 with Touch = Lesser Healing Wave, ON 10, OFF 11, FOCUS 12-16, MENU DONE 35)", bad[:3])
W6.eval('WowCompanion.padMap.party.put')("touch", False)
row = W6.eval('WowCompanion.pad.rows.party')
check(all(row.cells[c].a['type'] is None for c in range(5, 10)), "Touch = Target: the TSP cells have no action (type nil)")
d = W6.eval('WowCompanion.pad.describe()')
check(d['rows']['party'] is not None and int(d['sizes']['party']) == 40, "describe() publishes the party row (40 columns)", d['rows']['party'])
# a tap on cell 10 / 11: Party mode on / off by the secure click
W6.execute('local c = WowCompanion.pad.rows.party.cells[10]; doClick(c, "LeftButton", true); doClick(c, "LeftButton", false); advance(0.3)')
check(W6.eval('PADA("wc-party")') is True, "a tap on party col 10: Party mode ON", W6.eval('PADA("wc-party")'))
W6.execute('local c = WowCompanion.pad.rows.party.cells[11]; doClick(c, "LeftButton", true); doClick(c, "LeftButton", false); advance(0.3)')
check(W6.eval('PADA("wc-party")') is None, "a tap on party col 11: Party mode OFF")
n0 = nsent(W6)
W6.execute('local c = WowCompanion.pad.rows.party.cells[37]; doClick(c, "LeftButton", true); doClick(c, "LeftButton", false)')
ack = docs(W6, n0, 'partyack')
check(len(ack) == 1 and ack[0]['ok'] is False and ack[0]['why'] == 'unknown column', "a spare column (37) is refused: unknown column", ack)

# ================================================================ 5. THE COMBAT LOCKDOWN (secure work from insecure code in a fight errors)
W5 = world()
W5.execute('fire("PLAYER_REGEN_DISABLED"); COMBAT = true')
n0 = nsent(W5)
r = W5.eval('WowCompanion.padMap.party.put')("a", 331)
ack = docs(W5, n0, 'partyack')
check(r[0] is False and ack and ack[0]['ok'] is False and ack[0]['why'] == 'combat' and ack[0]['op'] == 'slot', "a setting in a fight: partyack ok false, why combat", ack)
for slot, op in (("yc", "yc"), ("touch", "touch")):
    n0 = nsent(W5)
    W5.eval('WowCompanion.padMap.party.put')(slot, 331)
    ack = docs(W5, n0, 'partyack')
    check(ack and ack[0]['op'] == op and ack[0]['why'] == 'combat', "... and for " + op, ack)
check(W5.eval('WowCompanionSpellsDB.partyBy["Korvash-Realm"].yc') is None, "nothing stored in the fight")
W5.execute('BOOK = { 403, 331, 324, 8004 }; fire("SPELLS_CHANGED"); advance(1)')
check(W5.eval('PADA("wc-ps-x")') is None and W5.eval('#VIOL') == 0, "a spell learned in the fight: nothing applied yet, no lockdown breach",
      list(W5.eval('VIOL').values())[:2])
W5.execute('COMBAT = false; fire("PLAYER_REGEN_ENABLED"); advance(1)')
check(W5.eval('PADA("wc-ps-x")') == "Lesser Healing Wave", "... applied at PLAYER_REGEN_ENABLED (x = Lesser Healing Wave)", W5.eval('PADA("wc-ps-x")'))
check(W5.eval('#VIOL') == 0 and W5.eval('#SPY') == 0, "no lockdown breach, no spied call", (list(W5.eval('VIOL').values())[:2], list(W5.eval('SPY').values())[:2]))

# ================================================================ 8. THE HEALTH ESTIMATE (the pure PARTY-EST block)
E = lupa.LuaRuntime(unpack_returned_tuples=True)
E.execute('OV = {}\n' + re.search(r'-- PARTY-EST-BEGIN[^\n]*\n(.*?)-- PARTY-EST-END', SRC["padmap"], re.S).group(1))
E.execute('m = OV.peNew(400, 0)')
check(E.eval('OV.pePct(m)') == 100 and E.eval('m.full') is True, "a new member (or a /reload): full, anchored")
E.execute('OV.peHit(m, "WOUND", 100, 1)')
check(E.eval('OV.pePct(m)') == 75 and E.eval('m.full') is False, "a plain WOUND of 100 / 400: 75 %, not full", E.eval('OV.pePct(m)'))
E.execute('OV.peHit(m, "HEAL", 50, 2)')
check(E.eval('OV.pePct(m)') == 88, "a plain HEAL of 50: 350 / 400 = 88 %", E.eval('OV.pePct(m)'))
E.execute('OV.peHit(m, "HEAL", 900, 3)')
check(E.eval('OV.pePct(m)') == 100 and E.eval('m.full') is True, "healed past max: clamped to 100 (full)")
E.execute('OV.peHit(m, "WOUND", 9000, 4)')
check(E.eval('OV.pePct(m)') == 0, "wounded past 0: clamped to 0")
E.execute('m = OV.peNew(400, 0); OV.peHit(m, "WOUND", 200, 10)')
check(E.eval('OV.peHit(m, "WOUND", nil, 11)') is False and E.eval('OV.pePct(m)') == 50, "a SECRET amount (not plain: nil): no change")
E.execute('OV.peTick(m, 15.9, false, false)')
check(E.eval('OV.pePct(m)') == 50, "out of combat, 5.9 s quiet: still the estimate")
E.execute('OV.peTick(m, 16, true, false)')
check(E.eval('OV.pePct(m)') == 50, "IN combat, 6 s quiet: no anchor")
E.execute('OV.pePulse(m, 16); OV.peTick(m, 21.9, false, false)')
check(E.eval('OV.pePct(m)') == 50, "a UNIT_HEALTH pulse restarts the 6 s")
E.execute('OV.peTick(m, 22, false, false)')
check(E.eval('OV.pePct(m)') == 100 and E.eval('m.full') is True, "out of combat, alive, 6 s with no pulse: full (the anchor)")
E.execute('OV.peTick(m, 30, true, true)')
check(E.eval('OV.pePct(m)') == 0 and E.eval('m.dead') is True, "dead: 0")
check(E.eval('OV.peHit(m, "HEAL", 100, 31)') is False and E.eval('OV.pePct(m)') == 0, "dead: a heal does not move it")
E.execute('OV.peTick(m, 40, false, false)')
check(E.eval('OV.pePct(m)') == 50 and E.eval('m.full') is False, "alive again after dead: 50 %")
E.execute('OV.peMax(m, 800)')
check(E.eval('m.max') == 800, "UNIT_MAXHEALTH: the new max")

# ================================================================ 7. THE DOCUMENTS (key for key with PARTY_BUILD.md section 1)
W7 = world()
W7.eval('party')(W7.table_from([W7.table_from(["Brannoc", "WARRIOR", True, "TANK", "RAGE"])]))
W7.execute('fire("GROUP_ROSTER_UPDATE")')
P_KEYS = {"on", "aside", "box", "fight", "grp", "sel", "m"}
M_KEYS = {"u", "i", "n", "c", "col", "role", "dead", "off", "pt", "hpMax", "pmMax", "hp", "full", "rng", "hs", "g", "cw", "cc", "ch", "cs"}   # m79: hs, the health source; m92: g, the GUID (combat log)
d = docs(W7, 0, 'party')[-1]
check(set(d) == {"type", "v", "rs", "p"} and d['v'] == 'm102' and set(d['p']) <= P_KEYS and {"on", "fight", "grp", "sel", "m"} <= set(d['p']),
      "party: type v rs p{on aside? box? fight grp sel m}", sorted(d['p']))
check('box' not in d['p'] and d['p']['on'] is False and d['p']['grp'] is True and d['p']['sel'] == 'bound',
      "Party mode off: no box; grp true; sel bound (Select toggles)", (d['p']['on'], d['p'].get('box'), d['p']['sel']))
m = d['p']['m']
check(len(m) == 2 and [x['u'] for x in m] == ['player', 'party1'] and [x['i'] for x in m] == [0, 1], "m[]: you first, then party1 (i = the tap offset)",
      [(x['u'], x['i']) for x in m])
check(all(set(x) <= M_KEYS for x in m) and m[1]['n'] == 'Brannoc' and m[1]['c'] == 'WARRIOR' and m[1]['col'] == 'ffc69b6d'
      and m[1]['role'] == 'TANK' and m[1]['pt'] == 'RAGE' and m[1]['hpMax'] == 400 and m[1]['hp'] == 100 and m[1]['full'] is True
      and m[1]['dead'] is False and m[1]['off'] is False, "a member: n c col role dead off pt hpMax pmMax hp full", m[1])
check(m[0]['n'] == 'Korvash' and m[0]['c'] == 'SHAMAN' and m[0]['pt'] == 'MANA', "your own cell", m[0])
check(all(not isinstance(v, (dict, list)) or k == 'm' for k, v in d['p'].items()), "no secret value can ride: plain fields only")
rs0 = d['rs']
n0, p0 = nsent(W7), int(W7.eval('PUSHES'))
W7.eval('party')(W7.table_from([W7.table_from(["Brannoc", "WARRIOR", True, "TANK", "RAGE"]), W7.table_from(["Iselle", "MAGE", True, "DAMAGER", "MANA"])]))
W7.execute('fire("GROUP_ROSTER_UPDATE")')
d = docs(W7, n0, 'party')
check(len(d) == 1 and d[0]['rs'] == rs0 + 1 and len(d[0]['p']['m']) == 3 and int(W7.eval('PUSHES')) > p0,
      "a roster change: a party document AT ONCE (no timer), rs + 1, flushed", (len(d), d and d[0]['rs'], rs0))
# health changes are coalesced: ten events, one document at most in 0.5 s
n0 = nsent(W7)
for i in range(10):
    W7.execute('fire("UNIT_COMBAT", "party1", "WOUND", "", 10, 1)')
d_now = docs(W7, n0, 'party')
W7.execute('advance(0.6)')
d_later = docs(W7, n0, 'party')
check(len(d_now) == 0 and len(d_later) == 1 and d_later[0]['p']['m'][1]['hp'] == 75 and d_later[0]['p']['m'][1]['full'] is False,
      "ten plain wounds (10 each of 400): no document per event; ONE after 0.5 s, hp 75, not full", (len(d_now), len(d_later)))
n0 = nsent(W7)
cw0 = d_later[0]['p']['m'][1].get('cw') if d_later else None
W7.execute('fire("UNIT_COMBAT", "party1", "WOUND", "", SECRETV, 1); advance(0.6)')
# m96: a hidden amount moves no health, but the hit is COUNTED (the app's calibrated estimate spends the count)
d_sec = docs(W7, n0, 'party')
check(len(d_sec) == 1 and d_sec[0]['p']['m'][1]['hp'] == 75 and cw0 is not None and d_sec[0]['p']['m'][1].get('cw') == cw0 + 1,
      "a SECRET amount: health stays (75), the wound count goes up by one (m96)", (len(d_sec), cw0, d_sec and d_sec[0]['p']['m'][1].get('cw')))
# m98: a party member's cast is counted (cs), for the app's mana estimate
n0 = nsent(W7)
W7.execute('fire("UNIT_SPELLCAST_SUCCEEDED", "party1", "Cast-1", 403); fire("UNIT_SPELLCAST_SUCCEEDED", "party1", "Cast-2", 403); advance(0.6)')
d_cs = docs(W7, n0, 'party')
check(len(d_cs) == 1 and d_cs[0]['p']['m'][1].get('cs') == 2, "two casts by party1: cs = 2 in one document (m98)",
      (len(d_cs), d_cs and d_cs[0]['p']['m'][1].get('cs')))
W7.execute('fire("PLAYER_REGEN_ENABLED"); NOW = NOW + 7; tick(1); advance(0.6)')
d = docs(W7, 0, 'party')[-1]
check(d['p']['m'][1]['hp'] == 100 and d['p']['m'][1]['full'] is True, "out of combat, 6 s quiet: back to full (the anchor)", d['p']['m'][1]['hp'])
W7.execute('UNITS.party2.alive = false; tick(1); advance(0.6)')
d = docs(W7, 0, 'party')[-1]
check(d['p']['m'][2]['dead'] is True and d['p']['m'][2]['hp'] == 0, "a dead member: dead, 0 %", d['p']['m'][2])
W7.execute('UNITS.party2.alive = true; tick(1); advance(0.6)')
d = docs(W7, 0, 'party')[-1]
check(d['p']['m'][2]['dead'] is False and d['p']['m'][2]['hp'] == 50, "alive again: 50 %", d['p']['m'][2]['hp'])
# Party mode on: box present (1-based into m)
flip(W7, "WowCompanionPartyOn")
d = docs(W7, 0, 'party')[-1]
check(d['p']['on'] is True and d['p']['box'] == 1, "Party mode on: box 1 (you, the first time)", (d['p']['on'], d['p'].get('box')))
W7.execute('hit(4); advance(0.6)')
pb = docs(W7, 0, 'partybox')[-1]
B_KEYS = {"n", "on", "box", "cnt", "u", "k", "key", "ly", "sp", "fight", "pm"}
check(set(pb) == {"type", "v", "b"} and set(pb['b']) <= B_KEYS and pb['b']['k'] == 'move' and pb['b']['box'] == 2 and pb['b']['u'] == 'party1'
      and pb['b']['cnt'] == 3 and pb['b']['key'] == 'PADDDOWN' and pb['b']['ly'] == '' and pb['b']['on'] is True and pb['b']['pm'] is False,
      "partybox: n on box cnt u k key ly sp? fight pm", pb['b'])
W7.execute('trig(true, false); hit(8); trig(false, false)')
pb2 = docs(W7, 0, 'partybox')[-1]['b']
check(pb2['n'] == pb['b']['n'] + 1 and pb2['ly'] == 'zl' and pb2['k'] == 'none' and 'sp' not in pb2,
      "the press counter goes up by one per press; ZL+A with Cure Poison not learned: ly zl, k none, no sp", pb2)
W7.execute('hit(8)')
pb3 = docs(W7, 0, 'partybox')[-1]['b']
check(pb3['k'] == 'cast' and pb3['sp'] == 'Healing Wave' and pb3['u'] == 'party1' and pb3['ly'] == '', "A: k cast, sp Healing Wave, u party1", pb3)
d = docs(W7, 0, 'party')[-1]
check(d['p']['box'] == 2, "the party document follows the box (2)", d['p'].get('box'))
# partycfg: key for key
c = docs(W7, 0, 'partycfg')[-1]
C_KEYS = {"slots", "yc", "touch", "spells", "sy", "pad"}
check(set(c) == {"type", "v", "c"} and set(c['c']) <= C_KEYS and {"slots", "spells", "sy", "pad"} <= set(c['c']), "partycfg: c{slots yc? touch? spells sy pad}",
      sorted(c['c']))
check(c['c']['pad'] == {"row": "party", "tgt": 0, "tsp": 5, "on": 10, "off": 11, "focus": 12, "menu": 17, "resend": 22, "pbsel": 23,
                        "pbops": {"remove": 31, "up": 32, "down": 33, "add": 34}, "mdone": 35,
                        "ptab": {"row": "padtab", "tab": 26, "slot0": 27, "none": 37}}, "partycfg.pad = the contract's column map", c['c']['pad'])
check(set(c['c']['sy']) <= {"on", "list", "known", "pick"} and c['c']['sy']['on'] is False and c['c']['sy']['list'] == [],
      "sy: on (Smart Y off), list (EMPTY at first), known, pick", c['c']['sy'])
check(all(set(v) <= {"id", "n", "i", "k", "d", "res"} for v in c['c']['slots'].values()) and c['c']['slots']['a']['n'] == 'Healing Wave'
      and isinstance(c['c']['slots']['a']['i'], int), "slots.<s>: id n i k d res?", c['c']['slots']['a'])
check(all(set(v) == {"id", "n", "i"} for v in c['c']['spells']) and [v['id'] for v in c['c']['spells']] == [331],
      "spells (the chooser): Healing Wave only (Lightning Bolt harmful, Lightning Shield self-only)", c['c']['spells'])
# the heartbeat ride: partycfg + party on heartbeat / login / zone / manual, no push of their own
for reason in ("heartbeat", "login", "zone", "manual", "harvest"):
    n0, p0 = nsent(W7), int(W7.eval('PUSHES'))
    W7.eval('WowCompanion.snapshot')(reason)
    t = [x['type'] for x in docs(W7, n0) if x.get('type') in ('party', 'partycfg')]
    want = ['partycfg', 'party'] if reason != "harvest" else []
    check(t == want and int(W7.eval('PUSHES')) == p0, "WC.snapshot(%s): %s, riding its push" % (reason, want or "nothing"), (t, int(W7.eval('PUSHES')) - p0))
# RESEND: party col 22
n0, p0 = nsent(W7), int(W7.eval('PUSHES'))
W7.execute('local c = WowCompanion.pad.rows.party.cells[22]; doClick(c, "LeftButton", true); doClick(c, "LeftButton", false)')
t = [x['type'] for x in docs(W7, n0)]
st = [x for x in docs(W7, n0, 'partybox')]
ack = docs(W7, n0, 'partyack')
check(t[:4] == ['partycfg', 'party', 'partybox', 'partyack'] and st[0]['b']['k'] == 'state' and ack[0]['op'] == 'resend' and ack[0]['ok'] is True
      and int(W7.eval('PUSHES')) > p0, "RESEND (col 22): partycfg + party + partybox{k state} + partyack resend, flushed", t)
A_KEYS = {"type", "v", "op", "ok", "why", "slot", "id"}
check(set(ack[0]) <= A_KEYS, "partyack: type v op ok why? slot? id?", ack[0])
check(W7.eval('#SPY') == 0 and W7.eval('#VIOL') == 0 and W7.eval('#SNIPERR') == 0, "no spied call, no lockdown breach, no snippet error")

# ================================================================ 9. SMART Y'S PARTY BUFFS: picks per unit, PB select / ops / add
W9 = world(pre='BOOK = { 403, 331, 324, 131, 546 }')
W9.eval('party')(W9.table_from([W9.table_from(["Brannoc", "WARRIOR"]), W9.table_from(["Iselle", "MAGE"])]))
W9.execute('fire("GROUP_ROSTER_UPDATE"); advance(1)')


def tapcell(L, col):
    L.execute('local c = WowCompanion.pad.rows.party.cells[%d]; doClick(c, "LeftButton", true); doClick(c, "LeftButton", false); NOW = NOW + 0.3' % col)


def picks(L):
    return {u: L.eval('PADA("wc-pb-%s")' % u) for u in ("player", "party1", "party2")}


def buffs(L):
    t = L.eval('WowCompanionSpellsDB.partyBy["Korvash-Realm"].buffs')
    return [int(t[i]) for i in range(1, len(t) + 1)]


# the list by the app's taps: col 34 (a fresh add pick) then the spell (Book's two taps -> WC.smartYSpell)
for sid in (131, 546):
    tapcell(W9, 34)
    check(W9.eval('WowCompanion.smartYWants()') is True, "col 34: a fresh add pick (Book's next spell goes to the party list)")
    n0 = nsent(W9)
    W9.eval('WowCompanion.smartYSpell')(sid)
    ack = docs(W9, n0, 'partyack')
    check(ack and ack[0]['op'] == 'pb' and ack[0]['ok'] is True and ack[0]['id'] == sid, "add %d: partyack pb ok" % sid, ack)
check(buffs(W9) == [131, 546], "the party list: Water Breathing, Water Walking (priority order)", buffs(W9))
for sid, why in ((131, "already listed"), (1243, "not a party buff this character knows"), (331, "not a party buff this character knows")):
    tapcell(W9, 34)
    n0 = nsent(W9)
    W9.eval('WowCompanion.smartYSpell')(sid)
    ack = docs(W9, n0, 'partyack')
    check(ack and ack[0]['ok'] is False and ack[0]['why'] == why, "add %d refused: %s" % (sid, why), ack)
tapcell(W9, 34)
W9.execute('NOW = NOW + 2.5')
check(W9.eval('WowCompanion.smartYWants()') is False, "an add pick older than 2 s is stale (the spell is not taken)")
# Smart Y off: nothing picked
check(picks(W9) == {"player": None, "party1": None, "party2": None}, "Smart Y OFF: no party pick at all", picks(W9))
W9.execute('WowCompanionSpellsDB.smartOn = { ["Korvash-Realm"] = true }')
W9.execute('AURAS = { player = { "Water Breathing", "Water Walking" }, party2 = { "Water Breathing" } }')
n0 = nsent(W9)
W9.execute('fire("UNIT_AURA", "party2"); advance(1)')
check(picks(W9) == {"player": None, "party1": "Water Breathing", "party2": "Water Walking"},
      "Smart Y ON: per unit the first listed buff it lacks (you have both: none; party2 has Water Breathing: Water Walking)", picks(W9))
c = docs(W9, n0, 'partycfg')
check(c and set(c[-1]['c']['sy']['pick']) == {"party1", "party2"} and c[-1]['c']['sy']['pick']['party1']['id'] == 131 and c[-1]['c']['sy']['on'] is True,
      "partycfg sy.pick per unit token, sent when the picks change", c and c[-1]['c']['sy'].get('pick'))
W9.execute('WowCompanionSpellsDB.partyBy["Korvash-Realm"].buffs = { 1243, 131 }; fire("UNIT_AURA", "party1"); advance(1)')
check(picks(W9)["party1"] == "Water Breathing", "a listed buff this character does not know (Fortitude) is skipped", picks(W9))
W9.execute('WowCompanionSpellsDB.partyBy["Korvash-Realm"].buffs = { 131, 546 }')
W9.execute('AURAS.party1 = { "x", secret = true }; fire("UNIT_AURA", "party1"); advance(1)')
check(picks(W9)["party1"] is None, "a secret aura read: no pick for that unit", picks(W9))
W9.execute('AURAS.party1 = nil; fire("UNIT_AURA", "party1"); advance(1)')
# in a fight: no new picks, sy.pick absent; Y there casts the in-combat spell (group 2)
W9.execute('fire("PLAYER_REGEN_DISABLED"); COMBAT = true; AURAS.party1 = { "Water Breathing", "Water Walking" }; fire("UNIT_AURA", "party1"); advance(1)')
check(picks(W9)["party1"] == "Water Breathing" and W9.eval('#VIOL') == 0, "in a fight the picks are not touched (no lockdown breach)", picks(W9))
n0 = nsent(W9)
W9.execute('fire("SPELLS_CHANGED"); advance(1)')
c = docs(W9, n0, 'partycfg')
check(not c or 'pick' not in c[-1]['c']['sy'], "in a fight partycfg has no sy.pick (an empty object is absent)", c and c[-1]['c']['sy'])
W9.execute('COMBAT = false; fire("PLAYER_REGEN_ENABLED"); advance(1)')
check(picks(W9)["party1"] is None, "the fight's end: picks again (party1 now has both: none)", picks(W9))
# PB select + ops, with the 2 s rule
W9.execute('WowCompanionSpellsDB.partyBy["Korvash-Realm"].buffs = { 131, 546 }')
tapcell(W9, 24); tapcell(W9, 32)                   # select row 2, up
check(buffs(W9) == [546, 131], "select row 2 (col 24) + Up (col 32): moved up", buffs(W9))
tapcell(W9, 23); tapcell(W9, 33)                   # select row 1, down
check(buffs(W9) == [131, 546], "select row 1 (col 23) + Down (col 33): moved down", buffs(W9))
tapcell(W9, 23); W9.execute('NOW = NOW + 2.5'); n0 = nsent(W9); tapcell(W9, 31)
ack = docs(W9, n0, 'partyack')
check(buffs(W9) == [131, 546] and ack and ack[0]['why'] == 'no row picked', "a select older than 2 s: Remove refused (no row picked)", ack)
tapcell(W9, 23); tapcell(W9, 23 + 5)               # a select of an empty row (6)
n0 = nsent(W9); tapcell(W9, 31)
ack = docs(W9, n0, 'partyack')
check(buffs(W9) == [131, 546] and ack and ack[0]['ok'] is False, "a select of an empty row: Remove refused", ack)
tapcell(W9, 23); n0 = nsent(W9); tapcell(W9, 31)
ack = docs(W9, n0, 'partyack')
check(buffs(W9) == [546] and ack and ack[0]['ok'] is True and ack[0]['op'] == 'pb' and ack[0]['id'] == 131, "select row 1 + Remove (col 31)", ack)
cfgs = docs(W9, n0, 'partycfg')
check(cfgs and [x['id'] for x in cfgs[-1]['c']['sy']['list']] == [546], "the list shown is partycfg.sy.list, sent after the op", cfgs and cfgs[-1]['c']['sy']['list'])
W9.execute('COMBAT = true')
n0 = nsent(W9); tapcell(W9, 23); tapcell(W9, 31)
ack = docs(W9, n0, 'partyack')
check(buffs(W9) == [546] and ack and ack[0]['why'] == 'combat', "a list op in a fight: refused (combat)", ack)
W9.execute('COMBAT = false')
# Smart Y off again: the picks go
W9.execute('WowCompanionSpellsDB.partyBy["Korvash-Realm"].buffs = { 131 }; AURAS = {}; fire("UNIT_AURA", "party1"); advance(1)')
check(picks(W9)["party1"] == "Water Breathing", "(on: party1 picked)")
W9.execute('WowCompanionSpellsDB.smartOn = {}; fire("UNIT_AURA", "party1"); advance(1)')
check(picks(W9) == {"player": None, "party1": None, "party2": None}, "Smart Y off: every pick cleared", picks(W9))
check(W9.eval('#SPY') == 0 and W9.eval('#VIOL') == 0, "no spied call, no lockdown breach")

# ================================================================ THE PARTY TAB (PadTabCtl p33 + Book b47's padtab 26-37)
check('local CV = "p33"' in SRC["padtabctl"] and 'local BV = "b48"' in SRC["book"] and 'local PV = "m102"' in SRC["padmap"],
      "the versions: PadMap m102, PadTabCtl p33, Book b48")
W8 = world(pre='BOOK = { 403, 331, 8004, 324, 131, 546 }')
W8.execute('''
local ctl = WowCompanion._spellsBook.ctl
ctl.croom, ctl.ctrlTab, ctl.onTabs = true, true, true
WowCompanion.padTabCtl.enter("tabs")
function ptab(col) WowCompanion.pad.rows.padtab.handler(nil, col); NOW = NOW + 0.05 end
function pp(key) return WowCompanion.padTabCtl.press(key) end
function spellTaps(id)                  -- Book's two taps for a spell (the spells row: vb<group> then vo<offset>)
    local v = WowCompanion.spellsPadNumbers({ id })[id]
    local cols = WowCompanion.spellsWinCols
    local on = WowCompanion.pad.rows.spells.onClick
    on(cols["vb" .. math.floor(v / 13)]); on(cols["vo" .. (v % 13)])
end
''')


def ctlDoc(L):
    return docs(L, 0, 'padtabctl')[-1]['c']


W8.execute('ptab(26)')
c = ctlDoc(W8)
check(c['view'] == 'party' and c['ppos'] == 2 and c['pfocus'] == 'rows', "padtab 26: the 5th tab (Party mode); ppos 2 (A), pfocus rows", c)
W8.execute('pp("PADDUP")')
check(ctlDoc(W8)['focus'] == 'tabs', "Up from the first row: the tab row", ctlDoc(W8)['focus'])
W8.execute('pp("PADDDOWN")')
c = ctlDoc(W8)
check(c['focus'] == 'prows' and c['ppos'] == 2, "Down from the tabs: the rows (A)", c)
moves = []
for k in ("PADDDOWN", "PADDDOWN", "PADDDOWN", "PADDDOWN", "PADDRIGHT", "PADDDOWN", "PADDLEFT", "PADDRIGHT", "PADDUP"):
    W8.execute('pp("%s")' % k)
    moves.append(ctlDoc(W8)['ppos'])
check(moves == [3, 4, 5, 5, 11, 12, 5, 11, 10], "the left page's D-pad: two columns of six (6 an empty place), across at the same row",
      moves)
W8.execute('pp("PADDLEFT"); pp("PADDUP"); pp("PADDUP"); pp("PADDUP")')
check(ctlDoc(W8)['ppos'] == 1, "the D-pad row (1)", ctlDoc(W8)['ppos'])
W8.execute('pp("PAD1")')
check(ctlDoc(W8)['note'] == 'locked' and ctlDoc(W8)['pfocus'] == 'rows', "A on the D-pad row: locked (fixed)", ctlDoc(W8)['note'])
W8.execute('pp("PADDDOWN"); pp("PAD1")')
c = ctlDoc(W8)
check(c['pfocus'] == 'right' and c['pright'] == 0, "A on the A row: the right page (pright 0-based: its first row)", c)
W8.execute('pp("PAD1")')
c = ctlDoc(W8)
check(c['chooser'] == {"list": "party", "row": 1, "id": "empty"}, "its red Change: the chooser, list party, the no-spell row first (empty)", c['chooser'])
W8.execute('pp("PADDDOWN")')
c = ctlDoc(W8)
check(c['chooser']['row'] == 2 and c['chooser']['id'] == 331, "Down: Healing Wave (partycfg.spells by id)", c['chooser'])
W8.execute('pp("PADDDOWN")')
check(ctlDoc(W8)['chooser']['id'] == 8004, "Down: Lesser Healing Wave", ctlDoc(W8)['chooser'])
n0 = nsent(W8)
W8.execute('pp("PAD1")')
ack = docs(W8, n0, 'partyack')
check(ack and ack[0]['op'] == 'slot' and ack[0]['slot'] == 'a' and ack[0]['id'] == 8004 and ack[0]['ok'] is True and ctlDoc(W8).get('chooser') is None
      and W8.eval('PADA("wc-ps-a")') == "Lesser Healing Wave", "A: A = Lesser Healing Wave (partyack slot ok; the chooser closes)", ack)
W8.execute('pp("PAD1")')
n0 = nsent(W8)
W8.execute('pp("PAD1")')
check(docs(W8, n0, 'partyack')[0]['ok'] is True and cfg(W8)['slots']['a'] is None, "the no-spell row: A = Empty", cfg(W8)['slots']['a'])
W8.execute('pp("PAD2")')
check(ctlDoc(W8)['pfocus'] == 'rows', "B on the right page: back to the rows")
# Y: Smart Y off -> one row (In combat: Change), the chooser's first row "inspect"
W8.execute('pp("PADDDOWN"); pp("PADDDOWN"); pp("PAD1"); pp("PAD1")')
check(ctlDoc(W8)['ppos'] == 4 and ctlDoc(W8)['chooser']['id'] == 'inspect', "Y (Smart Y off): Change = the In combat action, first row inspect",
      ctlDoc(W8).get('chooser'))
W8.execute('pp("PADDDOWN"); pp("PAD1")')
check(cfg(W8)['yc'] is not None and int(cfg(W8)['yc']['id']) == 331, "Y's In combat = Healing Wave", cfg(W8)['yc'])
W8.execute('pp("PAD2")')
# Y with Smart Y on: the party list's rows, Add, In combat
W8.execute('WowCompanionSpellsDB.smartOn = { ["Korvash-Realm"] = true }; WowCompanionSpellsDB.partyBy["Korvash-Realm"].buffs = { 131 }')
W8.execute('pp("PAD1")')
W8.execute('pp("PADDDOWN")')
c = ctlDoc(W8)
check(c['pfocus'] == 'right' and c['pright'] == 1, "Smart Y on: Y's right page has rows (the buff, Add, In combat): Add = pright 1 (0-based)", c)
W8.execute('pp("PAD1")')
c = ctlDoc(W8)
check(c['chooser']['list'] == 'pbuff' and c['chooser']['id'] == 546, "Add: the chooser of party buffs known and not listed (Water Walking)", c['chooser'])
W8.execute('pp("PAD1")')
check(buffs(W8) == [131, 546], "A: Water Walking added", buffs(W8))
W8.execute('pp("PADDUP"); pp("PADDUP"); pp("PADDUP")')
n0 = nsent(W8)
W8.execute('pp("PAD1")')
check(buffs(W8) == [546] and docs(W8, n0, 'partyack')[0]['op'] == 'pb', "on the first buff row the red action is Remove", buffs(W8))
W8.execute('pp("PADDDOWN"); pp("PADDDOWN"); pp("PAD1")')
check(ctlDoc(W8)['chooser']['list'] == 'party' and ctlDoc(W8)['chooser']['id'] == 'inspect', "the last row: In combat (Change)", ctlDoc(W8)['chooser'])
W8.execute('pp("PAD2"); pp("PAD2"); WowCompanionSpellsDB.smartOn = {}')
# TOUCH: padtab 27-36 picks + 37 / Book's two-tap spell
n0 = nsent(W8)
W8.execute('ptab(27 + 8); spellTaps(331)')              # slot 9 = zrx, then Healing Wave by the two taps
ack = docs(W8, n0, 'partyack')
check(ack and ack[0]['slot'] == 'zrx' and ack[0]['id'] == 331 and ack[0]['ok'] is True and W8.eval('PADA("wc-ps-zrx")') == "Healing Wave",
      "padtab 35 (slot zrx) + Book's two taps for Healing Wave: ZR+X = Healing Wave", ack)
check(ctlDoc(W8)['ppos'] == 11, "the pick moved the cursor (ppos 11)", ctlDoc(W8)['ppos'])
W8.execute('ptab(30)')
W8.execute('spellTaps(8004)')
check(int(cfg(W8)['touch']['id']) == 8004 and W8.eval('WowCompanion.pad.rows.party.cells[5].a.spell') == "Lesser Healing Wave",
      "padtab 30 (touch) + a spell: Touch = Lesser Healing Wave (the TSP cells follow)", cfg(W8)['touch'])
W8.execute('ptab(30); ptab(37)')
check(cfg(W8)['touch'] is None and W8.eval('WowCompanion.pad.rows.party.cells[5].a.type') is None, "padtab 30 + 37: Touch = Target", cfg(W8)['touch'])
W8.execute('ptab(28); ptab(37)')
check(cfg(W8)['slots']['x'] is None and W8.eval('WowCompanionSpellsDB.partyBy["Korvash-Realm"].slots.x') is False, "padtab 28 + 37: X = Empty")
n0 = nsent(W8)
W8.execute('ptab(27); NOW = NOW + 2.5; spellTaps(331)')
check(docs(W8, n0, 'partyack') == [] and ctlDoc(W8)['note'] == 'pslot-no-pick', "a slot pick older than 2 s: the spell is refused (no pick)",
      ctlDoc(W8)['note'])
W8.execute('ptab(37)')
check(ctlDoc(W8)['note'] == 'pslot-no-pick', "37 with no fresh pick: nothing", ctlDoc(W8)['note'])
n0 = nsent(W8)
W8.execute('ptab(38)')
pa = docs(W8, n0, 'padack')
check(pa and pa[0]['ok'] is False and pa[0]['why'] == 'unknown column', "padtab 38: refused (unknown column)", pa)
W8.execute('ptab(29); COMBAT = true')
n0 = nsent(W8)
W8.execute('spellTaps(331)')
ack = docs(W8, n0, 'partyack')
check(ack and ack[0]['why'] == 'combat' and ack[0]['slot'] == 'yc', "a touch put in a fight: partyack why combat", ack)
W8.execute('COMBAT = false')
W8.execute('ptab(0)')
check(ctlDoc(W8)['view'] == 'normal' and 'ppos' not in ctlDoc(W8), "padtab 0: back to WoW Defaults (no party fields)", ctlDoc(W8)['view'])
check(W8.eval('#SPY') == 0 and W8.eval('#VIOL') == 0 and list(W8.eval('ERRS').values()) == [], "no spied call, no lockdown breach, no error")

# ================================================================ 11. QUESTS: THE PLAYER MENU FOR A PARTY UNIT (q49)
check('local QV = "q51"' in SRC["quests"], "Quests says q51")
MENU = r'''
CALLS, CLICKED = {}, {}
local function mixin(name, text, enabled)
    local m = { GetText = function() return text end, CanShow = function() return true end,
                IsEnabled = function() return enabled ~= false end }
    m.OnClick = function() CLICKED[#CLICKED + 1] = name end
    rawset(_G, "UnitPopup" .. name .. "ButtonMixin", m)
    return m
end
ENTRIES = { mixin("SetFocus", "Set Focus"), mixin("Whisper", "Whisper"), mixin("Inspect", "Inspect"), mixin("Trade", "Trade"),
            mixin("Follow", "Follow"), mixin("Duel", "Duel"), mixin("PromoteToLeader", "Promote to Party Leader"),
            mixin("Uninvite", "Uninvite"), mixin("RaidTarget8", "Skull"), mixin("ClearFocus", "Clear Focus") }
UnitPopupManager = { GetMenu = function(_, which) MENUWHICH = which; return { GetEntries = function() return ENTRIES end } end }
for _, n in ipairs({ "InitiateTrade", "FollowUnit", "PromoteToLeader", "SetRaidTarget", "UninviteUnit", "StartDuel" }) do
    _G[n] = function(...) CALLS[#CALLS + 1] = { n, ... } end
end
C_PartyInfo = { UninviteUnit = function(...) CALLS[#CALLS + 1] = { "UninviteUnit", ... } end }
function GetRaidTargetIndex() return 0 end
'''
Q = boot(["safety", "tappad", "quests"], pre=MENU)
Q.execute(WORLD_HELPERS)
Q.eval('party')(Q.table_from([Q.table_from(["Brannoc", "WARRIOR"]), Q.table_from(["Iselle", "MAGE"])]))
n0 = nsent(Q)
Q.eval('WowCompanion.pmenu.read')("party2", "party")
pm = docs(Q, n0, 'pmenu')
check(len(pm) == 1 and pm[0]['unit'] == 'party2' and pm[0]['which'] == 'PARTY' and pm[0]['src'] == 'party' and pm[0]['nm'] == 'Iselle'
      and pm[0]['v'] == 'q51' and Q.eval('MENUWHICH') == 'PARTY', "read(\"party2\", \"party\"): the PARTY menu; doc unit, which PARTY, nm, src party",
      pm and {k: pm[0].get(k) for k in ('unit', 'which', 'nm', 'src')})
items = {x['t']: x for x in pm[0]['items']}
check(items['Set Focus'].get('sc') == 14 and items['Set Focus'].get('scr') == 'party', "Set Focus = the PARTY row's focus cell for party2 (col 12 + 2)",
      items['Set Focus'])
check(items['Clear Focus'].get('sc') == 1 and 'scr' not in items['Clear Focus'], "Clear Focus stays Quests' pfocus col 1", items['Clear Focus'])
check(items['Inspect']['en'] == 0 and 'i' not in items['Inspect'], "Inspect is greyed (InspectUnit taints)", items['Inspect'])
check(items['Duel']['en'] == 1 and 'i' in items['Duel'], "q51: Duel runs (StartDuel on the unit)", items['Duel'])
check(items['Whisper'].get('ap') == 'whisper' and 'i' in items['Whisper'], "Whisper: ap whisper (and runnable: it sets the reply target)", items['Whisper'])
Q.eval('WowCompanion.pmenu.run')(items['Whisper']['i'])
check(Q.eval('WowCompanion.chatLastTell') == 'Iselle', "running Whisper sets WC.chatLastTell = the member", Q.eval('WowCompanion.chatLastTell'))
# q50: this build's InitiateTrade takes the member's GUID (the unit only on a build that refuses it)
for t, want in (("Trade", ["InitiateTrade", "Player-1-Iselle"]), ("Follow", ["FollowUnit", "party2"]), ("Skull", ["SetRaidTarget", "party2", 8])):
    c0 = int(Q.eval('#CALLS'))
    Q.eval('WowCompanion.pmenu.run')(items[t]['i'])
    got = list(Q.eval('CALLS[#CALLS]').values()) if int(Q.eval('#CALLS')) > c0 else None
    check(got == want, "%s runs the game's plain call on the unit: %s" % (t, want), got)
check(items['Uninvite'].get('cf') is not None and items['Promote to Party Leader'].get('cf') is not None, "Uninvite / Promote ask first (cf)")
check(int(Q.eval('#CLICKED')) == 0, "no Blizzard menu code (an entry's OnClick) ever ran")
Q.execute('advance(1)')
pm2 = docs(Q, n0, 'pmenu')
check(len(pm2) >= 2 and pm2[-1]['unit'] == 'party2', "after a run the SAME unit's menu is read again", pm2[-1].get('unit'))
n0 = nsent(Q)
Q.eval('WowCompanion.pmenu.read')()
pm = docs(Q, n0, 'pmenu')[0]
items = {x['t']: x for x in pm['items']}
check(pm['unit'] == 'player' and pm['which'] == 'SELF' and 'src' not in pm and Q.eval('MENUWHICH') == 'SELF' and items['Set Focus'].get('sc') == 0
      and 'scr' not in items['Set Focus'], "read() as before (the map's portrait): SELF, no src, Set Focus = pfocus col 0", pm.get('which'))
n0 = nsent(Q)
Q.eval('WowCompanion.pmenu.read')("player", "party")
items = {x['t']: x for x in docs(Q, n0, 'pmenu')[0]['items']}
check(items['Set Focus'].get('sc') == 12 and items['Set Focus'].get('scr') == 'party', "your own cell's menu in Party mode: Set Focus = party col 12",
      items['Set Focus'])
# q51: the TARGET's menu for the app's radial (src "radial"): a friendly player, then an NPC
Q.execute('function UnitIsUnit(a, b) return a == b end; function UnitInParty() return false end; '
          'function UnitIsPlayer(u) return TPLAYER end; function UnitCanCooperate() return true end; TPLAYER = true')
n0 = nsent(Q)
Q.eval('WowCompanion.pmenu.read')("target", "radial")
pm = docs(Q, n0, 'pmenu')[0]
items = {x['t']: x for x in pm['items']}
check(pm['unit'] == 'target' and pm['which'] == 'PLAYER' and pm['src'] == 'radial' and Q.eval('MENUWHICH') == 'PLAYER',
      "q51: read(\"target\", \"radial\"): a friendly player = the PLAYER menu, src radial", {k: pm.get(k) for k in ('unit', 'which', 'src')})
check(items['Inspect'].get('sc') == 2 and items['Inspect']['en'] == 1, "q51: the target's Inspect = pfocus col 2 (the game's /inspect)", items['Inspect'])
check(items['Set Focus'].get('sc') == 3 and 'scr' not in items['Set Focus'], "q51: Set Focus on the target = pfocus col 3", items['Set Focus'])
c0 = int(Q.eval('#CALLS'))
Q.eval('WowCompanion.pmenu.run')(items['Duel']['i'])
got = list(Q.eval('CALLS[#CALLS]').values()) if int(Q.eval('#CALLS')) > c0 else None
check(got == ["StartDuel", "target"], "q51: Duel = StartDuel(\"target\")", got)
Q.execute('TPLAYER = false')
n0 = nsent(Q)
Q.eval('WowCompanion.pmenu.read')("target", "radial")
pm = docs(Q, n0, 'pmenu')[0]
check(pm['which'] == 'TARGET' and Q.eval('MENUWHICH') == 'TARGET', "q51: an NPC target = the TARGET menu", pm.get('which'))
check(int(Q.eval('#CLICKED')) == 0, "q51: still no Blizzard menu code ran")
# the party pad's Y and the party row's MENU cells read the menu for the unit, src "party"
W11 = world()
W11.execute('PMREADS = {}; WowCompanion.pmenu = { read = function(u, s) PMREADS[#PMREADS + 1] = { u, s } end }')
W11.eval('party')(W11.table_from([W11.table_from(m) for m in MEMBERS[:2]]))
flip(W11, "WowCompanionPartyOn")
W11.execute('rawget(_G, "WowCompanionPartyPad").a["wc-box"] = 3; hit(6)')
r = W11.eval('PMREADS[#PMREADS]')
check(r is not None and list(r.values()) == ["party2", "party"], "Y (no pick): WC.pmenu.read(\"party2\", \"party\")", r and list(r.values()))
W11.execute('local c = WowCompanion.pad.rows.party.cells[18]; doClick(c, "LeftButton", true); doClick(c, "LeftButton", false)')
r = W11.eval('PMREADS[#PMREADS]')
check(list(r.values()) == ["party1", "party"], "party row col 18 (MENU party1): WC.pmenu.read(\"party1\", \"party\")", list(r.values()))

# ================================================================ CROSS-SIDE FIXTURES (INTEGRATION): the documents the Lua REALLY sends
# The raw strings out of Safety.lua's encoder (nil = absent, {} = [] as the add-on encodes them), kept for the app's JVM test
# (dsforever/src/test/.../PartyFixturesTest.kt reads dsforever/src/test/resources/party-fixtures/*.json). The committed files must equal
# what this run emits: PARTY_FIXTURES_WRITE=1 rewrites them.
FIXDIR = os.path.join(ROOT, "dsforever", "src", "test", "resources", "party-fixtures")
FIX = {}


def raw(L, start, typ):
    out = []
    for i in range(start + 1, int(L.eval('#SENT')) + 1):
        t = L.eval('SENT[%d]' % i)
        try:
            d = json.loads(t)
        except Exception:
            continue
        if isinstance(d, dict) and d.get('type') == typ:
            out.append(t)
    return out


def keep(name, L, start, typ, which=-1):
    r = raw(L, start, typ)
    check(bool(r), "fixture %s: the add-on sent a %s" % (name, typ))
    if r:
        FIX[name] = r[which]


def tap_party(col):
    return 'local c = WowCompanion.pad.rows.party.cells[%d]; doClick(c, "LeftButton", true); doClick(c, "LeftButton", false)' % col


# a solo Shaman, Party mode off; the heartbeat ride: party + partycfg (class defaults, Smart Y's list empty, no yc / touch / pick)
FX = world()
n0 = nsent(FX)
FX.eval('WowCompanion.snapshot')("heartbeat")
keep("party_solo_off", FX, n0, 'party')
keep("partycfg_shaman_default", FX, n0, 'partycfg')
# a Warrior with nothing chosen and no class default: every slot Empty ("slots":[]), no party spell
FW = world(pre='CLASS = "WARRIOR"; BOOK = { 78 }')
n0 = nsent(FW)
FW.eval('WowCompanion.snapshot')("heartbeat")
keep("partycfg_warrior_empty", FW, n0, 'partycfg')
check('"slots":[]' in FIX.get("partycfg_warrior_empty", ""), "the Warrior's partycfg says \"slots\":[] (an empty table)",
      FIX.get("partycfg_warrior_empty", "")[:160])
# a party of three; Party mode on; the presses: Down (move), A (cast), ZL+A Empty (none), Y with no pick (menu), a press in the menu
# (key), B in the menu (key PAD2), MENU DONE, B (off)
FX.eval('party')(FX.table_from([FX.table_from(["Brannoc", "WARRIOR", True, "TANK", "RAGE"]),
                                FX.table_from(["Iselle", "MAGE", True, "DAMAGER", "MANA"])]))
FX.execute('fire("GROUP_ROSTER_UPDATE"); advance(1)')
n0 = nsent(FX)
flip(FX, "WowCompanionPartyOn")
keep("partybox_on", FX, n0, 'partybox')
keep("party_three_on", FX, n0, 'party')
for name, lua in (("partybox_move", 'hit(4)'), ("partybox_cast", 'hit(8)'), ("partybox_none_zl", 'trig(true, false); hit(8); trig(false, false)'),
                  ("partybox_menu", 'hit(6)'), ("partybox_key_down", 'hit(4)'), ("partybox_key_b", 'hit(7)')):
    n0 = nsent(FX)
    FX.execute(lua + '; advance(0.6)')
    keep(name, FX, n0, 'partybox')
FX.execute('clickName("WowCompanionPartyMenuDone", true); clickName("WowCompanionPartyMenuDone", false); advance(0.3)')
n0 = nsent(FX)
FX.execute('hit(7); advance(0.6)')
keep("partybox_off", FX, n0, 'partybox')
keep("party_three_off", FX, n0, 'party')
# the answers: RESEND, a spare column (op cell), a stale TSP tap (op touch why target), a setting in a fight (why combat), a slot set
for name, lua in (("partyack_resend", tap_party(22)), ("partyack_cell", tap_party(37)), ("partyack_touch_target", tap_party(6)),
                  ("partyack_combat", 'COMBAT = true; WowCompanion.padMap.party.put("a", 331); COMBAT = false'),
                  ("partyack_slot_ok", 'WowCompanion.padMap.party.put("a", 331)')):
    n0 = nsent(FX)
    FX.execute(lua + '; NOW = NOW + 0.3')
    keep(name, FX, n0, 'partyack', 0)
# partycfg with everything set: Smart Y on, a party list with picks, Y's In combat, Touch = a spell, a chosen Empty
FB = world(pre='BOOK = { 403, 331, 8004, 324, 131, 546 }')
FB.eval('party')(FB.table_from([FB.table_from(["Brannoc", "WARRIOR"]), FB.table_from(["Iselle", "MAGE"])]))
FB.execute('fire("GROUP_ROSTER_UPDATE"); advance(1)')
FB.execute('WowCompanion.padMap.party.put("yc", 331); WowCompanion.padMap.party.put("touch", 8004); WowCompanion.padMap.party.put("x", false)')
FB.execute('WowCompanionSpellsDB.smartOn = { ["Korvash-Realm"] = true }; WowCompanionSpellsDB.partyBy["Korvash-Realm"].buffs = { 131, 546 }')
FB.execute('AURAS = { player = { "Water Breathing", "Water Walking" }, party2 = { "Water Breathing" } }')
n0 = nsent(FB)
FB.execute('fire("UNIT_AURA", "party2"); advance(1); WowCompanion.snapshot("manual")')
keep("partycfg_full", FB, n0, 'partycfg')
n0 = nsent(FB)
FB.execute(tap_party(23) + '; NOW = NOW + 0.3')
FB.execute(tap_party(33) + '; NOW = NOW + 0.3')
keep("partyack_pb", FB, n0, 'partyack', 0)
# the Controller room's Party tab (padtabctl): the A row's right page, the chooser "party", Y's page with Smart Y on (pright = Add),
# the chooser "pbuff"
FB.execute('local ctl = WowCompanion._spellsBook.ctl; ctl.croom, ctl.ctrlTab, ctl.onTabs = true, true, true; '
           'WowCompanion.padTabCtl.enter("tabs"); WowCompanion.pad.rows.padtab.handler(nil, 26); NOW = NOW + 0.05')


def pp(k):
    FB.execute('WowCompanion.padTabCtl.press("%s")' % k)


pp("PADDUP"); pp("PADDDOWN")                                              # the tab row, then the rows (A)
n0 = nsent(FB); pp("PAD1"); keep("padtabctl_party_right", FB, n0, 'padtabctl')
n0 = nsent(FB); pp("PAD1"); pp("PADDDOWN"); keep("padtabctl_party_chooser", FB, n0, 'padtabctl')
pp("PAD2"); pp("PAD2"); pp("PADDDOWN"); pp("PADDDOWN")
n0 = nsent(FB); pp("PAD1"); pp("PADDDOWN"); pp("PADDDOWN"); keep("padtabctl_party_y_add", FB, n0, 'padtabctl')
n0 = nsent(FB); pp("PAD1"); keep("padtabctl_party_pbuff", FB, n0, 'padtabctl')
FX2 = json.loads(FIX.get("padtabctl_party_y_add", "{}")).get('c', {})
check(FX2.get('ppos') == 4 and FX2.get('pfocus') == 'right' and FX2.get('pright') == 2,
      "the fixture's Y page: ppos 4, pfocus right, pright 2 (0-based: buff, buff, ADD)", {k: FX2.get(k) for k in ('ppos', 'pfocus', 'pright')})
# the player menu for a party member (Quests q49)
n0 = nsent(Q)
Q.eval('WowCompanion.pmenu.read')("party2", "party")
keep("pmenu_party2", Q, n0, 'pmenu')

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
check(not stale, "the committed fixtures equal what the add-on sends now (%d files; PARTY_FIXTURES_WRITE=1 rewrites)" % len(FIX), stale[:4])

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
# the user's one exception (2026-11-04, "Let DS Forever set it"): Chat.lua chat27 sets the chat style to Classic once
hits = [(p, ln) for (p, ln) in hits if not (p == r'\bSetCVar\b' and ('STYLE_CVAR' in ln or 'C_CVar.SetCVar) == "function"' in ln or '"gxCursor"' in ln
                                                                  # hub22 (the user, 2026-11-07): the hunter's own Combat Auto Shot option, asked for by name
                                                                  or 'autoRangedCombat' in ln or ln == 'local set = (C_CVar and C_CVar.SetCVar) or _G.SetCVar'))]
# (and pad14's software cursor: the user's "Yes, try software cursor", 2026-11-04, gxCursor once)
check(not hits, "no trace in the new add-on code: no SetBinding / SaveBindings / macros / CVars / bar writes / Blizzard window code",
      hits[:3])
check(not any('SetOverrideBindingClick' in ln and 'hdr' not in ln and 'owner' not in ln for ln in code),
      "every new key binding is an override on our own owner frame")

# ================================================================ m100: the experimental switch (the user, 2026-11-07: "Make the party
# mode an experimental toggle that is off by default")
WX = world(ok=False)
WX.execute('NOW = NOW + 2; tick(0.1)')
sel = WX.eval('BIND["PADBACK"]')
check(sel != 'CLICK WowCompanionPartyToggle:LeftButton' and WX.eval('WowCompanion.partyOk') is None,
      "m100: switch never set (off by default): Select is NOT Party mode's toggle", sel)
check(party_on_all(WX) == [None] * 9, "m100: switch off: Party mode off")
WX.execute('WowCompanion.partyOk = true; WowCompanion.partyAllow(true); NOW = NOW + 2; tick(0.1)')
sel = WX.eval('BIND["PADBACK"]')
check(sel == 'CLICK WowCompanionPartyToggle:LeftButton', "m100: switch on: Select toggles Party mode", sel)
flip(WX, "WowCompanionPartyOn")
check(party_on_all(WX) == [True] * 9, "m100: switch on: Party mode can turn on")
WX.execute('WowCompanion.partyOk = false; WowCompanion.partyAllow(false)')
check(party_on_all(WX) == [None] * 9, "m100: switch turned off out of a fight: Party mode ends at once", party_on_all(WX))
WX.execute('NOW = NOW + 2; tick(0.1)')
check(WX.eval('BIND["PADBACK"]') != 'CLICK WowCompanionPartyToggle:LeftButton', "m100: ... and Select is the game's again",
      WX.eval('BIND["PADBACK"]'))
WX.execute('WowCompanion.partyOk = true; WowCompanion.partyAllow(true); NOW = NOW + 2; tick(0.1)')
flip(WX, "WowCompanionPartyOn")
WX.execute('COMBAT = true; WowCompanion.partyOk = false; WowCompanion.partyAllow(false)')
check(party_on_all(WX) == [True] * 9 and WX.eval('#VIOL') == 0, "m100: switched off in a fight: no secure write in the lockdown (still on)",
      list(WX.eval('VIOL').values()))
WX.execute('COMBAT = false; fire("PLAYER_REGEN_ENABLED"); NOW = NOW + 2; tick(0.1)')
check(party_on_all(WX) == [None] * 9, "m100: ... it ends at the fight's end", party_on_all(WX))

print('ALL PASS (%d checks)' % passes if fails == 0 else '%d FAILED (of %d)' % (fails, fails + passes))
sys.exit(1 if fails else 0)
