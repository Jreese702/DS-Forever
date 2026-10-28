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
