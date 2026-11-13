"""Issue #195 ("Adaptative layout for action bars port to main screen"), off-device: DS Forever's Nintendo bar on the top screen
(WowCompanion/ActionBar.lua nbar1: WC.nbar / WC.nbarSet) and its switch (WowCompanionHub/Hub.lua hub45: "uisw" NBAR_OFF 34 / NBAR_ON 35),
plus the aura sweeps by the duration object (Trail.lua prd17 P.auraSweep, Hub.lua uf17).

    python tools/test_nintendo_bar.py [--shots DIR]

The REAL Safety.lua + ActionBar.lua + Hub.lua run under a stub game (Lua 5.1, lupa): the game's gamepad bar (GamepadMainActionBarFrame,
its PageUnit's top / left / right / bottom / stance / possess bars, eight buttons each, every Blizzard frame a PROXY that logs any method
but the read-only getters, SetAlpha and HookScript), the action APIs, the controller's mapped state (the triggers), PadMap's views
(WC.padMap.slotAt / dpadState). In a fight the cooldown times and charges are STRICT secrets: a compare, an index, arithmetic, a
concatenation, a length or tostring raises (and is counted). Checks:
  - the Nintendo choice: the stock bar (and its input legend) at alpha 0 by SetAlpha alone, ours shown; Off gives alpha 1 back; the
    controller-bars element's own hide still wins; our frames plain (no Secure template, EnableMouse false), nothing of Blizzard's
    moved / shown / hidden, no SetCVar / SetBinding / macro;
  - the live layer: plain / ZL / ZR / ZL+ZR follow the held triggers (the tick picks a change up in one poll), a window owning the pad
    = the base layer; the stance bar's override (ov "stance") is the picture of the layer it lies over; PadMap's overlay: the DS
    toolbar on the bare D-pad, the faces' fight spells and the fight layer's In-combat spells in a fight, commands and bindings;
  - the glyphs: the layout setting's atlases (Rev_* swap / Ltr_* plain), range = the glyph red, low mana = the icon blue (never red),
    unusable = grey;
  - the cooldowns: the duration-object route when the client has it (each button's object straight to SetCooldownFromDurationObject,
    the charge sweep too), the dim fallback when it has not (stubbed absent, and present-but-refused), never a secret compared (the
    strict secrets raise nothing), the probe "nbarcd" once out of a fight and once in one, plain values;
  - the switch round trip: Hub's "uisw" 35 / 34 -> WC.nbarSet, the hub document's sw.nbar / set.nbar / cols;
  - the aura sweeps: Trail's HUD row and Hub's target bar hand each secret aura's duration object (C_UnitAuras.GetAuraDuration) to
    the swipe; absent -> the old setter route; refused -> the old route; the probe "aurasweep" once per route.
--shots renders the bar (plain, ZR held) as PNGs. Needs lupa (PIL for --shots). Exit code 1 on any failure."""
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from lupa import lua51 as lupa  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ADDONS = os.path.join(ROOT, "companion", "app", "src", "main", "assets", "companion", "addons")
SAFETY = os.path.join(ADDONS, "WowCompanion", "Safety.lua")
AB = os.path.join(ADDONS, "WowCompanion", "ActionBar.lua")
HUB = os.path.join(ADDONS, "WowCompanionHub", "Hub.lua")
SHOTS = "C:/Users/Jrees/AppData/Local/Temp/claude/6c5148a90427b4bc/789b5138-341f-4ef8-8102-099551ca3a8a/scratchpad/nintendo-bar-shots"

STUB = r'''
-- ---------------------------------------------------------------- strict secrets
RAISED = 0
local function E() RAISED = RAISED + 1; error("a secret refused (compare / index / arithmetic)") end
SMT = { secret = true, __eq = E, __lt = E, __le = E, __index = E, __newindex = E, __concat = E, __len = E, __call = E,
        __add = E, __sub = E, __mul = E, __div = E, __unm = E, __tostring = E }
function SECRET() return setmetatable({}, SMT) end
function issecretvalue(v) return type(v) == "table" and rawequal(getmetatable(v), SMT) end

COMBAT, TARGET, NOW = false, true, 100
function InCombatLockdown() return COMBAT end
function UnitAffectingCombat() return COMBAT end
function UnitExists(u) if u == "target" then return TARGET end return u == "player" end
function GetTime() return NOW end
function GetCurrentKeyBoardFocus() return nil end
function UnitClass() return "Shaman", "SHAMAN" end
function UnitName() return "Korvash" end
function GetRealmName() return "Realm" end
SlashCmdList = {}
SPY = {}
for _, n in ipairs({ "SetCVar", "SetBinding", "SaveBindings", "CreateMacro", "EditMacro", "PlaceAction", "PickupAction",
                     "SetOverrideBinding", "SetOverrideBindingClick", "ClearOverrideBindings", "SecureHandlerWrapScript",
                     "RegisterStateDriver" }) do
    _G[n] = function() SPY[#SPY + 1] = n end
end
C_CVar = { SetCVar = function() SPY[#SPY + 1] = "C_CVar.SetCVar" end, GetCVar = function() return nil end,
           GetCVarBool = function() return false end }

-- ---------------------------------------------------------------- timers
TIMERS = {}
C_Timer = { After = function(s, fn) TIMERS[#TIMERS + 1] = fn end,
            NewTicker = function(s, fn) return { Cancel = function() end } end }
function hooksecurefunc(a, b, c)
    local t, k, fn = a, b, c
    if type(a) == "string" then t, k, fn = _G, a, b end
    local orig = rawget(t, k) or t[k]
    if type(orig) ~= "function" then return end
    t[k] = function(...) local r = { orig(...) }; fn(...); return unpack(r) end
end

-- ---------------------------------------------------------------- OUR frames (plain; everything recorded)
OURS, BADMOUSE, BADTPL, EVENTS = {}, 0, {}, {}
local F = {}
F.__index = function(t, k)
    local v = rawget(F, k)
    if v ~= nil then return v end
    if type(k) == "string" and k:match("^[A-Z]") then return function() end end
end
local function new(kind, parent)
    local o = setmetatable({ kind = kind, parent = parent, shown = true, alpha = 1, pts = {}, kids = {} }, F)
    if parent then parent.kids[#parent.kids + 1] = o end
    OURS[#OURS + 1] = o
    return o
end
function CreateFrame(kind, name, parent, tpl)
    if tpl and tostring(tpl):find("Secure") then BADTPL[#BADTPL + 1] = tostring(tpl) end
    local f = new(kind, parent)
    f.tpl, f.name = tpl, name
    if name then _G[name] = f end
    return f
end
function F:CreateTexture(name, layer) local t = new("tex", self); t.layer = layer; return t end
function F:CreateFontString(name, layer, tpl) local t = new("fs", self); t.text = ""; return t end
function F:SetSize(w, h) self.w, self.h = w, h end
function F:SetWidth(w) self.w = w end
function F:SetHeight(h) self.h = h end
function F:SetPoint(...) self.pts[#self.pts + 1] = { ... } end
function F:ClearAllPoints() self.pts = {} end
function F:SetAllPoints(t) self.pts = { { "ALL", t } } end
function F:Show() self.shown = true end
function F:Hide() self.shown = false end
function F:IsShown() return self.shown end
function F:SetShown(v) self.shown = v and true or false end
function F:SetAlpha(a) self.alpha = a end
function F:GetAlpha() return self.alpha end
function F:EnableMouse(v) self.mouse = v; if v then BADMOUSE = BADMOUSE + 1 end end
function F:SetScript(k, fn) self["on" .. k] = fn end
function F:HookScript(k, fn) end
function F:RegisterEvent(e) EVENTS[e] = EVENTS[e] or {}; table.insert(EVENTS[e], self); return true end
function F:RegisterUnitEvent(e) return self:RegisterEvent(e) end
function F:UnregisterEvent() end
function F:GetEffectiveScale() return 1 end
function F:SetTexture(t) self.tex = t; self.atlas = nil end
function F:SetAtlas(a)
    if NOATLAS then error("no atlas") end
    if REFUSED and REFUSED[a] then return false end       -- the real client: an unknown atlas is refused WITHOUT raising
    self.atlas = a; self.tex = nil
    return true
end
function F:SetScale(k) self.scale = k end
function F:SetColorTexture(r, g, b, a) self.color = { r, g, b, a } end
function F:SetVertexColor(r, g, b) self.vc = { r, g, b } end
function F:SetDesaturated(d) self.desat = d end
function F:SetText(t) self.text = t end
function F:SetTextColor(r, g, b) self.tc = { r, g, b } end
function F:SetFormattedText(fmt, ...) self.text = string.format(fmt, ...) end
function F:Clear() self.cleared = (self.cleared or 0) + 1; self.dur = nil end
function F:SetCooldown(s, d) SECRETSET = (SECRETSET or 0) + ((issecretvalue(s) or issecretvalue(d)) and 1 or 0); self.cdStart = s end
function F:SetCooldownFromExpirationTime(e, d)
    if issecretvalue(e) or issecretvalue(d) then error("AllowedWhenUntainted") end
    self.cdExp = e
end
function F:SetCooldownFromDurationObject(obj, clear)
    if DUR_REFUSE then error("refused") end
    self.durN = (self.durN or 0) + 1
    if clear ~= false and type(obj) == "table" and obj.zero then self.dur = nil; self.durZero = (self.durZero or 0) + 1 else self.dur = obj end
end
UIParent = new("Frame", nil)
UIParent.name = "UIParent"
DEFAULT_CHAT_FRAME = nil

-- ---------------------------------------------------------------- the game's gamepad bar: proxies (any method but the allowed ones is logged)
BLIZ = {}
local ALLOW = { SetAlpha = true, GetAlpha = true, HookScript = true, IsShown = true, IsEnabled = true, GetID = true, GetName = true,
                GetCurrentPage = true, GetActiveBar = true, IsBarActivelyOverridingActionBar = true, GetCenter = true,
                GetEffectiveScale = true, GetTexture = true, GetAtlas = true, GetWidth = true, SetCurrentPage = true, IsVisible = true }
local function proxy(name, t)
    t._name = name
    local mt = {}
    mt.__index = function(_, k)
        local v = rawget(t, "_m_" .. k)
        if v ~= nil then
            if not ALLOW[k] then BLIZ[#BLIZ + 1] = name .. ":" .. k end
            return v
        end
        if type(k) == "string" and k:match("^[A-Z]") then
            return function() BLIZ[#BLIZ + 1] = name .. ":" .. k end
        end
        return nil
    end
    return setmetatable(t, mt)
end
local function meth(t, k, fn) rawset(t, "_m_" .. k, fn) end
-- one bar: eight buttons on slots first .. first + 7 (D-pad L / U / R / D, face L / T / R / B)
BARS = {}
local function mkbar(key, first)
    local bar = proxy(key, { actionButtons = {} })
    for p = 1, 8 do
        local ic = proxy(key .. "Icon" .. p, {})
        meth(ic, "GetTexture", function() return FXICON[key .. p] end)
        local b = proxy(key .. "Button" .. p, { action = first + p - 1, icon = ic })
        meth(b, "IsEnabled", function() return true end)
        meth(b, "GetID", function() return first + p - 1 end)
        meth(b, "GetName", function() return key .. "Button" .. p end)
        bar.actionButtons[p] = b
    end
    meth(bar, "IsBarActivelyOverridingActionBar", function(self, base)
        return OVERRIDE[key] ~= nil and rawequal(BARS[OVERRIDE[key]], base)
    end)
    BARS[key] = bar
    return bar
end
FXICON = { topBar5 = 136243, topBar6 = 136244, topBar7 = 136245, topBar8 = 136246 }
OVERRIDE = {}                                           -- stanceBar = "rightBar": the stance bar lies over the ZR layer
local bars = { topBar = mkbar("topBar", 1), leftBar = mkbar("leftBar", 13), rightBar = mkbar("rightBar", 25),
               bottomBar = mkbar("bottomBar", 37), stanceBar = mkbar("stanceBar", 73), possessBar = mkbar("possessBar", 121),
               friendlyTargetingBar = mkbar("friendlyTargetingBar", 200), hostileTargetingBar = mkbar("hostileTargetingBar", 210),
               shortcutsBar = mkbar("shortcutsBar", 220) }
local pu = proxy("PageUnit", { actionBars = bars })
meth(pu, "GetCurrentPage", function() return 1 end)
meth(pu, "GetActiveBar", function() return bars.topBar end)
GamepadMainActionBarFrame = proxy("GamepadMainActionBarFrame", { PageUnit = pu, alpha = 1 })
meth(GamepadMainActionBarFrame, "SetAlpha", function(self, a) rawset(self, "alpha", a) end)
meth(GamepadMainActionBarFrame, "GetAlpha", function(self) return rawget(self, "alpha") end)
meth(GamepadMainActionBarFrame, "IsShown", function() return true end)
meth(GamepadMainActionBarFrame, "HookScript", function() end)
MFX, MFY, MFSCALE, MFVIS = 960, 96, 1, true
meth(GamepadMainActionBarFrame, "GetCenter", function() if COMBAT then return SECRET(), SECRET() end return MFX, MFY end)
meth(GamepadMainActionBarFrame, "GetEffectiveScale", function() return MFSCALE end)
meth(GamepadMainActionBarFrame, "IsVisible", function() return MFVIS end)
GamepadPersistentInputLegend = proxy("GamepadPersistentInputLegend", { alpha = 1 })
meth(GamepadPersistentInputLegend, "SetAlpha", function(self, a) rawset(self, "alpha", a) end)
meth(GamepadPersistentInputLegend, "GetAlpha", function(self) return rawget(self, "alpha") end)

-- ---------------------------------------------------------------- the actions (slot -> spell / item), their state
ACT = {}                 -- slot -> { k = "spell" / "item", id, icon, usable, nomana, range, cd (active), charges }
function HasAction(s) return ACT[s] ~= nil end
function GetActionInfo(s) local a = ACT[s]; if not a then return nil end; return a.k, a.id end
function GetActionTexture(s) local a = ACT[s]; return a and a.icon or nil end
function IsUsableAction(s) local a = ACT[s]; if not a then return false, false end; return a.usable ~= false, a.nomana == true end
function IsActionInRange(s) local a = ACT[s]; if not a or a.range == nil then return nil end; return a.range end
function IsCurrentAction() return false end
function IsAutoRepeatAction() return false end
function IsEquippedAction() return false end
function IsConsumableAction(s) local a = ACT[s]; return a ~= nil and a.k == "item" end
function IsStackableAction() return false end
function GetActionText() return nil end
local function cdInfo(active)
    if COMBAT then return { isActive = active == true, isOnGCD = false, isEnabled = true, startTime = SECRET(), duration = SECRET() } end
    return { isActive = active == true, isOnGCD = false, isEnabled = true, startTime = active and 90 or 0, duration = active and 20 or 0 }
end
DURCALLS = { act = 0, ach = 0, spell = 0, sch = 0 }
C_ActionBar = {
    GetActionCooldown = function(s) local a = ACT[s]; return cdInfo(a and a.cd) end,
    GetActionCharges = function(s)
        local a = ACT[s]
        local mc = a and a.charges or 0
        return { maxCharges = mc, currentCharges = COMBAT and SECRET() or math.max(0, mc - 1) }
    end,
    -- a duration object: opaque to the add-on (zero = nothing cooling: the swipe's clearIfZero clears it)
    GetActionCooldownDuration = function(s, ignoreGCD) DURCALLS.act = DURCALLS.act + 1; local a = ACT[s]; return { dur = "action", slot = s, gcd = ignoreGCD, zero = not (a and a.cd) } end,
    GetActionChargeDuration = function(s) DURCALLS.ach = DURCALLS.ach + 1; local a = ACT[s]; return { dur = "charge", slot = s, zero = not (a and a.charges) } end,
}
SPELLS = { [403] = { n = "Lightning Bolt", i = 136048 }, [8042] = { n = "Earth Shock", i = 136026, cd = true },
           [331] = { n = "Healing Wave", i = 136052 }, [324] = { n = "Lightning Shield", i = 136051 },
           [8017] = { n = "Rockbiter Weapon", i = 136086 }, [8050] = { n = "Flame Shock", i = 135813, range = false },
           [370] = { n = "Purge", i = 136075, nomana = true }, [3599] = { n = "Searing Totem", i = 135825, usable = false },
           [768] = { n = "Cat Form", i = 132115 }, [5221] = { n = "Shred", i = 136231 }, [1082] = { n = "Claw", i = 132140 } }
C_Spell = {
    GetSpellName = function(id) return SPELLS[id] and SPELLS[id].n end,
    GetSpellTexture = function(id) return SPELLS[id] and SPELLS[id].i end,
    GetSpellInfo = function(id) local s = SPELLS[id]; if s then return { name = s.n, iconID = s.i, spellID = id, castTime = 0, maxRange = 30 } end end,
    IsSpellUsable = function(id) local s = SPELLS[id] or {}; return s.usable ~= false, s.nomana == true end,
    IsSpellInRange = function(id) local s = SPELLS[id] or {}; if s.range == false then return false end; return true end,
    IsSpellHarmful = function() return true end,
    IsCurrentSpell = function() return false end,
    IsAutoRepeatSpell = function() return false end,
    GetSpellPowerCost = function() return {} end,
    GetSpellCooldown = function(id) local s = SPELLS[id] or {}; return cdInfo(s.cd) end,
    GetSpellCooldownDuration = function(id) DURCALLS.spell = DURCALLS.spell + 1; return { dur = "spell", id = id, zero = not (SPELLS[id] or {}).cd } end,
    GetSpellChargeDuration = function(id) DURCALLS.sch = DURCALLS.sch + 1; return { dur = "scharge", id = id, zero = true } end,
}
C_Item = { GetItemCount = function(id) return 3 end, GetItemNameByID = function() return "Healing Potion" end }
C_SpellActivationOverlay = { IsSpellOverlayed = function() return false end }

-- ---------------------------------------------------------------- the controller's mapped state: LT = button 11, RT = 12 (index + 1)
LT, RT = false, false
C_GamePad = {
    ButtonBindingToIndex = function(n) if n == "PADLTRIGGER" then return 10 elseif n == "PADRTRIGGER" then return 11 end end,
    GetActiveDeviceID = function() return 1 end,
    GetDeviceMappedState = function(dev) return { buttons = { [11] = LT, [12] = RT } } end,
}
-- a window owning the pad (Blizzard's IsOnlyCoreBindingSetActive false)
CORE_ONLY = true
GamepadSharedUtility = { InputBindingManager = { IsOnlyCoreBindingSetActive = function() return CORE_ONLY end } }

-- ---------------------------------------------------------------- the add-on core (Safety.lua is loaded for real after this)
SENT, CAPS = {}, {}
WowCompanion = {
    send = function(j) SENT[#SENT + 1] = j; if type(j) == "table" and j.type == "cap" then CAPS[#CAPS + 1] = j end end,
    encode = function(t) return t end, push = function() end, flush = function() end,
    snapshot = function() end, VERSION = "test",
}
WowCompanion.emit = function(t) WowCompanion.send(t) end
ROWS = {}
WowCompanion.pad = { op = function(name, ns, fn) ROWS[name] = fn; return name end, describe = function() return {} end,
                     onLayout = function() end }
-- PadMap's views (WowCompanionSpells): set -> pos -> view
PADMAP, DPAD_ON, CC_ON, FORMS, FORMFIGHT = {}, true, true, {}, {}
function PADMAP_ON()
    WowCompanion.padMap = {
        slotAt = function(page, set, pos) local s = PADMAP[set]; local v = s and s[pos]; if v then return v end; return { kind = "spell" } end,
        dpadState = function() return { on = DPAD_ON } end,
        combatOn = function() return CC_ON end,
        forms = function() return FORMS end,
        formFight = function(form, pos) local t = FORMFIGHT[form]; return t and t[pos] or nil end,
    }
end
'''

# the scene: a Shaman's bars
SCENE = r'''
for s = 1, 4 do ACT[s] = { k = "spell", id = 999, icon = 1000 + s } end           -- the bare D-pad's own slots (under the toolbar)
ACT[13] = { k = "spell", id = 331, icon = 136052 }                                  -- ZL: Healing Wave, Lightning Shield, ...
ACT[14] = { k = "spell", id = 324, icon = 136051 }
ACT[15] = { k = "spell", id = 8017, icon = 136086 }
ACT[16] = { k = "item", id = 929, icon = 134830 }                                   -- a Healing Potion (count 3)
ACT[17] = { k = "spell", id = 403, icon = 136048 }
ACT[25] = { k = "spell", id = 8042, icon = 136026, cd = true }                       -- ZR: Earth Shock cooling
ACT[26] = { k = "spell", id = 8050, icon = 135813, range = false }                   -- Flame Shock out of range
ACT[27] = { k = "spell", id = 370, icon = 136075, nomana = true }                    -- Purge without the mana
ACT[28] = { k = "spell", id = 3599, icon = 135825, usable = false }                  -- Searing Totem unusable
ACT[29] = { k = "spell", id = 403, icon = 136048, charges = 2 }                      -- two charges
ACT[30] = { k = "spell", id = 331, icon = 136052 }
ACT[37] = { k = "spell", id = 324, icon = 136051 }                                   -- ZL+ZR
for s = 73, 80 do ACT[s] = { k = "spell", id = (s % 2 == 0) and 5221 or 1082, icon = (s % 2 == 0) and 136231 or 132140 } end   -- the cat bar
-- PadMap: the faces' fight spells (X / Y / A = positions 5 / 6 / 8), a fight-layer In-combat spell on the bare D-pad's up and on ZL's 3,
-- a command on ZL+ZR's 2
PADMAP[1] = { [1] = { kind = "spell" }, [2] = { kind = "combat", ov = true, c = { n = "Lightning Bolt", id = 403, i = 136048 } },
              [5] = { kind = "fixed", cf = { n = "Earth Shock", id = 8042, i = 136026 } }, [6] = { kind = "fixed", cf = { n = "Healing Wave", id = 331, i = 136052 } },
              [7] = { kind = "fixed" }, [8] = { kind = "fixed", cf = { n = "Lightning Bolt", id = 403, i = 136048 } } }
PADMAP[2] = { [3] = { kind = "combat", ov = true, base = "spell", c = { n = "Flame Shock", id = 8050, i = 135813 }, o = { n = "Rockbiter Weapon", id = 8017, i = 136086 } } }
PADMAP[4] = { [2] = { kind = "command", key = "DSMAP", i = 134269 } }
'''


def boot(with_hub=True, dur=True, padmap=True, pre=""):
    L = lupa.LuaRuntime(unpack_returned_tuples=True)
    L.execute(STUB)
    L.execute(SCENE)
    if not dur:
        L.execute("C_ActionBar.GetActionCooldownDuration = nil; C_ActionBar.GetActionChargeDuration = nil; "
                  "C_Spell.GetSpellCooldownDuration = nil; C_Spell.GetSpellChargeDuration = nil; F_NODUR = true")
        L.execute("getmetatable(UIParent).__index = (function(old) return function(t, k) if k == 'SetCooldownFromDurationObject' then return nil end; return old(t, k) end end)(getmetatable(UIParent).__index)")
    if padmap:
        L.execute("PADMAP_ON()")
    if pre:
        L.execute(pre)
    load = L.eval('function(src, name, a) local f, e = loadstring(src, name); if not f then error(e) end; return f(a) end')
    load(open(SAFETY, encoding="utf-8").read(), "@Safety.lua", "WowCompanion")
    load(open(AB, encoding="utf-8").read(), "@ActionBar.lua", "WowCompanion")
    if with_hub:
        load(open(HUB, encoding="utf-8").read(), "@Hub.lua", "WowCompanionHub")
        # the first event: Hub's setup() registers its rows
        L.execute('for _, f in ipairs(EVENTS["PLAYER_LOGIN"] or {}) do if f.onOnEvent then pcall(f.onOnEvent, f, "PLAYER_LOGIN") end end')
    return L


class Doc(dict):
    """a sent document as attributes (from a Lua table or the JSON text the real encoder made)"""
    def __getattr__(self, k):
        v = self.get(k)
        return Doc(v) if isinstance(v, dict) else v


def todict(v):
    if lupa.lua_type(v) == "table":
        return {k: todict(x) for k, x in v.items()}
    return v


def sent(G, typ):
    out = []
    for x in G.SENT.values():
        if lupa.lua_type(x) == "table":
            d = todict(x)
        else:
            try:
                d = json.loads(x)
            except Exception:
                continue
        if isinstance(d, dict) and d.get("type") == typ:
            out.append(Doc(d))
    return out


def shown(L):
    """the bar's eight positions as drawn: kind, icon, route, look, range, glyph atlas / word, dim"""
    out = []
    f = L.eval("WowCompanion.nbar.f")
    for p in range(1, 9):
        b = f.btn[p]
        sh = L.eval("WowCompanion.nbar.shown")[p]
        out.append(dict(kind=sh.kind, icon=sh.icon, id=sh.id, s=sh.s, route=sh.route, look=b.look, range=b.range,
                        atlas=b.glyph.atlas, word=b.word.text, dim=bool(b.dim.shown), iconShown=bool(b.icon.shown),
                        vc=tuple(b.icon.vc.values()) if b.icon.vc else None, gvc=tuple(b.glyph.vc.values()) if b.glyph.vc else None,
                        count=b.count.text, dur=b.cd.dur, ch=b.ch.dur, desat=b.icon.desat))
    return out


def main():
    fails = []
    shots = SHOTS
    if "--shots" in sys.argv:
        shots = sys.argv[sys.argv.index("--shots") + 1]

    def chk(name, ok, got=''):
        print(("PASS " if ok else "FAIL ") + name + ((" -> " + str(got)) if got != '' and not ok else ''))
        if not ok:
            fails.append(name)

    L = boot()
    G = L.globals()
    WC = G.WowCompanion
    chk("nbar2: the version tag", WC.nbar is not None and WC.nbar.V == "nbar2")
    abs_src = open(AB, encoding="utf-8").read()
    hub_src = open(HUB, encoding="utf-8").read()
    chk("nbar2 / hub46 / uf17: the header notes quote the issue", "issue #195" in abs_src.split("\n", 6)[1] + abs_src.split("\n", 6)[2]
        and "nbar2 (review of nbar1, issue #195" in abs_src and 'local HV = "hub52"' in hub_src and "hub49:" in hub_src and "hub48:" in hub_src and "hub47b: SMART RES" in hub_src and "hub47:" in hub_src and "hub45: THE NINTENDO BAR" in hub_src
        and "hub46: ONE GLYPH RULE (issue #195" in hub_src and 'V = "uf22"' in hub_src)
    # ------------------------------------------------------------ the switch round trip (Hub's "uisw")
    sw_row = G.ROWS["uisw"]
    chk("hub45: Hub registered its switch row", sw_row is not None)
    chk("hub45: the columns NBAR_OFF 34 / NBAR_ON 35 beside the pet's 32 / 33", WC.hub is not None)
    G.SENT = L.table()
    sw_row(None, 35)
    hubdocs = sent(G, "hub")
    sw = hubdocs[-1].hub.sw if hubdocs else None
    chk("hub45: the tap on 35 -> the hub document's sw.nbar true, set.nbar true, its columns", sw is not None and sw.nbar is True
        and sw.set.nbar is True and sw.cols.NBAR_ON == 35 and sw.cols.NBAR_OFF == 34 and sw.cols.PET_ON == 33)
    mf, lg = G.GamepadMainActionBarFrame, G.GamepadPersistentInputLegend
    chk("Nintendo on: the stock bar and its legend at alpha 0, ours shown", mf.alpha == 0 and lg.alpha == 0 and WC.nbar.f.shown is True
        and WC.nbarOn is True)
    chk("Nintendo on: the hud element still reads shown (its riders stay: the layout bit is On's)", WC.hudHidden is False)
    els = [Doc(e) for e in hubdocs[-1].hub.els if e["id"] == "hud"] if hubdocs else []
    chk("Nintendo on: the hub's hud element hidden=false", els and els[0].hidden is False, els)
    # our frames plain
    tpls = set(str(o.tpl) for o in G.OURS.values() if o.tpl is not None)
    chk("our frames: no Secure template (only the game's plain cooldown swipe)", len(G.BADTPL) == 0 and tpls <= {"CooldownFrameTemplate"}, tpls)
    f = WC.nbar.f
    btn_mouse = [f.mouse] + [f.btn[p].mouse for p in range(1, 9)] + [f.btn[p].cd.mouse for p in range(1, 9)]
    chk("our frames: EnableMouse(false) on the bar, its buttons and swipes; never true", G.BADMOUSE == 0 and all(m is False for m in btn_mouse), btn_mouse)
    chk("our bar sits in the stock bar's spot (its centre, read only)", WC.nbar.at == "stock" and f.pts[1][4] == 960 and f.pts[1][5] == 96,
        (WC.nbar.at, list(f.pts[1].values()) if f.pts[1] else None))
    # ------------------------------------------------------------ the live layer
    G.COMBAT = False
    WC.nbar.refresh()
    s = shown(L)
    chk("plain layer: the DS toolbar on the bare D-pad (chat / radial / map / Home) out of a fight",
        [x["icon"] for x in s[:4]] == [134332, 134829, 134269, "tex:Interface\\AddOns\\WowCompanion\\Media\\ds_forever_icon"], [x["icon"] for x in s[:4]])
    chk("plain layer: the faces the game's fixed four out of a fight (Interact / Inspect - its live crosshair with a target - / Back / Jump)",
        [x["icon"] for x in s[4:]] == [136243, "atlas:crosshair_inspect_32", 136245, 136246], [x["icon"] for x in s[4:]])
    chk("glyphs (nbar2): the Nintendo layout (swap): D-pad arrows, each face its own letter (PAD3 X / PAD4 Y / PAD2 B / PAD1 A, m119)",
        [x["atlas"] for x in s] == ["Gamepad_Gen_Left_64", "Gamepad_Gen_Up_64", "Gamepad_Gen_Right_64", "Gamepad_Gen_Down_64",
                                    "Gamepad_Rev_X_64", "Gamepad_Rev_Y_64", "Gamepad_Rev_B_64", "Gamepad_Rev_A_64"], [x["atlas"] for x in s])
    O, D = WC.nbar.OFF, WC.nbar.DX
    at = [(f.btn[p].pts[1][4], f.btn[p].pts[1][5]) for p in range(5, 9)]
    chk("ABXY location swap (nbar2, CastView's Nintendo arrangement): X top, Y left, B bottom, A right",
        at == [(D, O), (D - O, 0), (D, -O), (D + O, 0)], at)
    chk("one glyph rule: Hub's glyphs take WC.padGlyphAtlas (Back = Rev_B)", list(WC.padGlyphAtlas("PAD2").values()) == ["Gamepad_Rev_B_64"]
        and list(WC.padGlyphAtlas("PAD4").values()) == ["Gamepad_Rev_Y_64"])
    G.RT = True
    WC.nbar.tick(0.06)
    chk("ZR held: the tick picks it up in one poll (set 3) and the layer word says ZR", WC.nbar.set == 3 and f.layer.text == "ZR",
        (WC.nbar.set, f.layer.text))
    s = shown(L)
    chk("ZR: the right bar's slots 25-32", [x["s"] for x in s[:6]] == [25, 26, 27, 28, 29, 30], [x["s"] for x in s])
    chk("ZR: Flame Shock out of range -> the GLYPH red, the icon untouched", s[1]["range"] is False and s[1]["gvc"] == (1, 0.2, 0.2)
        and s[1]["look"] == "ok", s[1])
    chk("ZR: Purge without the mana -> the icon blue, the glyph NOT red", s[2]["look"] == "mana" and s[2]["vc"] == (0.45, 0.55, 1)
        and s[2]["gvc"] == (1, 1, 1), s[2])
    chk("ZR: Searing Totem unusable -> grey (desaturated)", s[3]["look"] == "unusable" and s[3]["desat"] is True, s[3])
    chk("ZR: every action's swipe from its duration object (cooling slot 25's object on the swipe, slot 29's charge object on the edge; a zero object clears)",
        all(x["route"] == "dur" for x in s if x["kind"] == "slot") and s[0]["dur"].slot == 25 and s[0]["dur"].dur == "action"
        and s[0]["dim"] is False and s[4]["ch"].dur == "charge" and s[4]["ch"].slot == 29 and s[1]["dur"] is None, [x["route"] for x in s])
    G.LT = True
    WC.nbar.tick(0.06)
    s = shown(L)
    chk("ZL+ZR held: set 4, the bottom bar's slot 37, ZL+ZR's command (Open Map) from PadMap", WC.nbar.set == 4 and s[0]["s"] == 37
        and s[1]["kind"] == "icon" and s[1]["icon"] == 134269 and f.layer.text == "ZL+ZR", (WC.nbar.set, s[0], s[1]))
    G.RT = False
    WC.nbar.tick(0.06)
    s = shown(L)
    chk("ZL held: set 2, the left bar (Healing Wave 13 ...), the potion's count 3", WC.nbar.set == 2 and s[0]["s"] == 13 and s[3]["count"] == "3",
        (WC.nbar.set, s[0]["s"], s[3]["count"]))
    chk("ZL out of a fight: the In-combat spell's place shows its Otherwise (the bar's own slot 15)", s[2]["kind"] == "slot" and s[2]["s"] == 15, s[2])
    G.LT = False
    WC.nbar.tick(0.01)
    chk("a held trigger let go is picked up only at the next poll (0.05 s)", WC.nbar.set == 2)
    WC.nbar.tick(0.05)
    chk("... and then the plain layer", WC.nbar.set == 1)
    # a window owning the pad: the base layer even with ZR held
    G.CORE_ONLY, G.RT = False, True
    WC.nbar.tick(0.06)
    chk("a window owns the pad: the base layer only (ZR held)", WC.nbar.set == 1)
    G.CORE_ONLY, G.RT = True, False
    WC.nbar.tick(0.06)
    # ------------------------------------------------------------ a fight: strict secrets, the fight's own pictures
    G.COMBAT = True
    G.RAISED = 0
    WC.hudErrors = 0
    WC.nbar.refresh()
    s = shown(L)
    chk("fight, plain layer: the D-pad's In-combat spell on up (Lightning Bolt), the toolbar elsewhere", s[1]["kind"] == "spell" and s[1]["id"] == 403
        and s[0]["icon"] == 134332, (s[0], s[1]))
    chk("fight, plain layer: the faces' fight spells X / Y / A (Earth Shock / Healing Wave / Lightning Bolt), Back stays",
        [s[4]["id"], s[5]["id"], s[7]["id"]] == [8042, 331, 403] and s[6]["icon"] == 136245, [x["id"] for x in s[4:]])
    chk("fight: a spell's swipe from C_Spell's duration object", s[4]["route"] == "dur" and s[4]["dur"].dur == "spell" and s[4]["dur"].id == 8042, s[4])
    G.LT = True
    WC.nbar.tick(0.06)
    s = shown(L)
    chk("fight, ZL: the In-combat spell over slot 15 (Flame Shock, its range red)", s[2]["kind"] == "spell" and s[2]["id"] == 8050 and s[2]["range"] is False, s[2])
    G.LT, G.RT = False, True
    WC.nbar.tick(0.06)
    s = shown(L)
    chk("fight, ZR: the secret cooldown times never touched; the duration objects still on every swipe",
        G.RAISED == 0 and WC.hudErrors == 0 and all(x["route"] == "dur" for x in s if x["kind"] == "slot"), (G.RAISED, WC.hudErrors, WC.hudLastError))
    chk("fight: the charges secret -> no count drawn (never compared)", s[4]["count"] == "", s[4]["count"])
    caps = [c for c in sent(G, "cap") if c.probe == "nbarcd"]
    chk("probe nbarcd: once out of a fight and once in one, route dur, plain values", len(caps) == 2 and {c["fight"] for c in caps} == {True, False}
        and all(c.route == "dur" and c.v == "nbar2" and c.have.acd and c.have.set for c in caps), caps)
    for _ in range(5):
        WC.nbar.refresh()
    chk("probe nbarcd: never again in the same state", len([c for c in sent(G, "cap") if c.probe == "nbarcd"]) == 2)
    # the stance bar's override: the cat bar lies over ZR
    G.COMBAT = False
    G.OVERRIDE.stanceBar = "rightBar"
    WC.nbar.refresh()
    s = shown(L)
    chk("stance override (ov stance over ZR): ZR shows the cat bar's slots 73-80 (Shred / Claw), what a press fires",
        [x["s"] for x in s] == list(range(73, 81)) and s[0]["icon"] == 132140 and s[1]["icon"] == 136231, [x["s"] for x in s])
    G.OVERRIDE.stanceBar = None
    G.RT = False
    WC.nbar.refresh()
    # the plain layout setting: lettered glyphs
    G.WowCompanionSpellsDB = L.table_from({"padLayout": "plain"})
    WC.nbar.refresh()
    s = shown(L)
    chk("the plain layout: Ltr_X / Ltr_Y / Ltr_B / Ltr_A on the faces", [x["atlas"] for x in s[4:]] == ["Gamepad_Ltr_X_64", "Gamepad_Ltr_Y_64",
        "Gamepad_Ltr_B_64", "Gamepad_Ltr_A_64"], [x["atlas"] for x in s[4:]])
    at = [(f.btn[p].pts[1][4], f.btn[p].pts[1][5]) for p in range(5, 9)]
    chk("the plain ABXY location: X left, Y top, B right, A bottom", at == [(D - O, 0), (D, O), (D + O, 0), (D, -O)], at)
    G.WowCompanionSpellsDB = None
    # off again
    G.SENT = L.table()
    sw_row(None, 34)
    hubdocs = sent(G, "hub")
    chk("hub45: the tap on 34 -> stock alpha 1 again, ours hidden, sw.nbar false", mf.alpha == 1 and lg.alpha == 1 and f.shown is False
        and hubdocs and hubdocs[-1].hub.sw.nbar is False)
    # the controller-bars element's own Off still wins over Nintendo off
    sw_row(None, 35)
    WC.hudHide()
    sw_row(None, 34)
    chk("the element's own hide wins: Nintendo off while the bars are Off keeps the stock bar at 0", mf.alpha == 0)
    WC.hudShow()
    chk("... and its show gives alpha 1 (Nintendo off)", mf.alpha == 1)
    sw_row(None, 35)
    WC.hudShow()
    chk("the element's show while Nintendo is on keeps the stock bar at 0", mf.alpha == 0)
    G.GamepadMainActionBarFrame.alpha = 1
    L.execute('for _, f in ipairs(EVENTS["PLAYER_ENTERING_WORLD"] or {}) do if f.onOnEvent then pcall(f.onOnEvent, f, "PLAYER_ENTERING_WORLD") end end')
    chk("a /reload's frame at alpha 1: PLAYER_ENTERING_WORLD puts the 0 back while Nintendo is on", mf.alpha == 0)
    # nothing of Blizzard's moved, no trace
    bliz = sorted(set(G.BLIZ.values()))
    chk("the stock bar: only read and SetAlpha (no Show / Hide / SetPoint / EnableMouse / SetAttribute on a Blizzard frame)", bliz == [], bliz)
    chk("leave no trace: no SetCVar / SetBinding / macro / override binding", len(G.SPY) == 0, list(G.SPY.values()))
    # ------------------------------------------------------------ the fallback: no duration route on this client
    L2 = boot(dur=False)
    G2 = L2.globals()
    W2 = G2.WowCompanion
    G2.ROWS["uisw"](None, 35)
    G2.RT = True
    G2.COMBAT = True
    W2.nbar.tick(0.06)
    s = shown(L2)
    chk("no duration API: Earth Shock (cooling) dimmed, the others not, no swipe set", s[0]["dim"] is True and s[1]["dim"] is False
        and all(x["dur"] is None for x in s) and all(x["route"] in ("dim", None) for x in s), [(x["route"], x["dim"]) for x in s])
    chk("no duration API, in a fight: never a secret handed to SetCooldown, nothing raised", (G2.SECRETSET or 0) == 0 and G2.RAISED == 0
        and W2.hudErrors == 0, (G2.SECRETSET, G2.RAISED, W2.hudLastError))
    caps = [c for c in sent(G2, "cap") if c.probe == "nbarcd"]
    chk("probe nbarcd: route dim, have acd false / set false", len(caps) == 1 and caps[0].route == "dim" and caps[0].have.acd is False
        and caps[0].have.set is False, caps)
    # present but refused: the dim again, the probe says it
    L3 = boot(pre="DUR_REFUSE = true")
    G3 = L3.globals()
    W3 = G3.WowCompanion
    G3.ROWS["uisw"](None, 35)
    G3.RT, G3.COMBAT = True, True
    W3.nbar.tick(0.06)
    s = shown(L3)
    caps = [c for c in sent(G3, "cap") if c.probe == "nbarcd"]
    chk("duration route refused: the dim fallback (Earth Shock dimmed), the probe route dim with fails counted", s[0]["dim"] is True
        and caps and caps[0].route == "dim" and caps[0].fail > 0 and G3.RAISED == 0, caps)
    # no PadMap (the Spells add-on off): the stock layers alone
    L4 = boot(padmap=False)
    G4 = L4.globals()
    W4 = G4.WowCompanion
    G4.ROWS["uisw"](None, 35)
    W4.nbar.refresh()
    s = shown(L4)
    chk("without PadMap: the bare D-pad is the stock bar's own slots 1-4", [x["s"] for x in s[:4]] == [1, 2, 3, 4], [x["s"] for x in s[:4]])
    # a client without atlases: the letters
    L5 = boot(pre="NOATLAS = true")
    G5 = L5.globals()
    G5.ROWS["uisw"](None, 35)
    s = shown(L5)
    chk("no atlas on the client: each button's own letter (X / Y / B / A) as words", [x["word"] for x in s[4:]] == ["X", "Y", "B", "A"], [x["word"] for x in s[4:]])
    review_checks(chk)
    # ------------------------------------------------------------ the renders
    if "--shots" in sys.argv or os.environ.get("NBAR_SHOTS") == "1":
        render(shots)
    # ------------------------------------------------------------ the aura sweeps (Trail.lua prd17 / Hub.lua uf17)
    aura_checks(chk)
    print("failed: %d" % len(fails))
    sys.exit(1 if fails else 0)


def review_checks(chk):
    """nbar2: the review of nbar1 (issue #195): a form's own fight spells, Combat controls off, the bare D-pad's commands / bindings,
    the router's own switch, a refused atlas, the stock bar's visibility / scale / spot"""
    def on(L):
        G = L.globals()
        G.ROWS["uisw"](None, 35)
        return G, G.WowCompanion
    # a form's own In-combat spells (PadMap m55): Cat Form on, its D-pad up = Shred, its X (pos 5) = Claw; its Y none -> the face's own
    L = boot(pre="FORMS = { { x = 1, id = 768, on = true } }; FORMFIGHT[768] = { [2] = { n = 'Shred', id = 5221, i = 136231 }, "
                 "[5] = { n = 'Claw', id = 1082, i = 132140 } }")
    G, W = on(L)
    G.COMBAT = True
    W.nbar.refresh()
    s = shown(L)
    chk("nbar2 form: in a fight the form's own D-pad up (Shred) and X (Claw); Y keeps the face's own (Healing Wave)",
        s[1]["id"] == 5221 and s[4]["id"] == 1082 and s[5]["id"] == 331, [x["id"] for x in s])
    G.FORMS = L.eval("{ { x = 1, id = 768, on = false } }")
    W.nbar.refresh()
    s = shown(L)
    chk("nbar2 form: not in the form -> the caster's (Lightning Bolt up, Earth Shock on X)", s[1]["id"] == 403 and s[4]["id"] == 8042,
        [x["id"] for x in s])
    G.FORMS = L.eval("{ { x = 1, id = 768, on = true } }")
    G.RT = True
    W.nbar.tick(0.06)
    s = shown(L)
    chk("nbar2 form: a trigger layer never takes the form's bare set (ZR = slots 25-)", s[1]["s"] == 26 and s[1]["kind"] == "slot", s[1])
    G.RT = False
    G.COMBAT = False
    W.nbar.tick(0.06)
    W.nbar.refresh()
    s = shown(L)
    chk("nbar2 form: out of a fight no fight pictures (the toolbar, the fixed faces)", s[1]["icon"] == 134829 and s[4]["icon"] == 136243,
        [x["icon"] for x in s])
    # Combat controls off: the router arms no fight text, a press casts the slot
    L = boot(pre="CC_ON = false")
    G, W = on(L)
    G.COMBAT = True
    W.nbar.refresh()
    s = shown(L)
    chk("nbar2 Combat controls off: in a fight the D-pad keeps the toolbar, the faces the game's four (no fight spells)",
        s[1]["icon"] == 134829 and s[4]["icon"] == 136243 and s[5]["kind"] != "spell" and s[7]["icon"] == 136246, [x["icon"] for x in s])
    G.LT = True
    W.nbar.tick(0.06)
    s = shown(L)
    chk("nbar2 Combat controls off: ZL's In-combat place is the bar's own slot 15", s[2]["kind"] == "slot" and s[2]["s"] == 15, s[2])
    # the bare D-pad without the DS toolbar: a command and a binding there are drawn (bindAllowed lets them on)
    L = boot(pre="DPAD_ON = false")
    L.execute("PADMAP[1][3] = { kind = 'command', key = 'DSMAP', i = 134269 }; PADMAP[1][4] = { kind = 'bind', key = 'TOGGLEWORLDMAP', i = 137176 }; "
              "PADMAP[1][1] = { kind = 'combat', ov = true, base = 'bind', c = { n = 'Purge', id = 370, i = 136075 }, o = { n = 'Bags', i = 133633 } }")
    G, W = on(L)
    W.nbar.refresh()
    s = shown(L)
    chk("nbar2 bare D-pad (no toolbar): the command's and the binding's icons; a fight-layer entry's Otherwise binding out of a fight",
        s[2]["icon"] == 134269 and s[3]["icon"] == 137176 and s[0]["icon"] == 133633 and s[1]["s"] == 2, [x["icon"] for x in s[:4]])
    G.COMBAT = True
    W.nbar.refresh()
    s = shown(L)
    chk("nbar2 bare D-pad in a fight: the fight-layer spell (Purge) over the binding, the In-combat spell up", s[0]["id"] == 370 and s[1]["id"] == 403,
        [x["id"] for x in s[:4]])
    # the router's own switch (WC.tgGrown = PadMap's sw): an attack armed before the lockdown arms the faces; B's give-back drops them
    L = boot()
    G, W = on(L)
    L.execute("WowCompanion.tgGrown = function() return GROWN end; GROWN = true")
    W.nbar.refresh()
    s = shown(L)
    chk("nbar2 router switch: an auto-attack armed out of the lockdown -> the faces' fight spells, as a press casts",
        [s[4]["id"], s[5]["id"], s[7]["id"]] == [8042, 331, 403], [x["id"] for x in s[4:]])
    G.GROWN, G.COMBAT = False, True
    W.nbar.refresh()
    s = shown(L)
    chk("nbar2 router switch: given back by B in a fight -> the game's four again, the toolbar up", s[4]["icon"] == 136243 and s[1]["icon"] == 134829,
        [x["icon"] for x in s])
    L.execute("GROWN = nil")
    W.nbar.refresh()
    s = shown(L)
    chk("nbar2 router switch: not routed (nil) -> the fight itself", s[4]["id"] == 8042, s[4])
    # a refused atlas (SetAtlas returns false, no error): the next candidate, then the letter
    L = boot(pre="REFUSED = { Gamepad_Gen_Left_64 = true, Gamepad_Rev_X_64 = true }")
    G, W = on(L)
    s = shown(L)
    chk("nbar2 atlas refused (no raise): D-pad left takes the next candidate (Gamepad_Gen_DPadLeft_64)", s[0]["atlas"] == "Gamepad_Gen_DPadLeft_64", s[0])
    chk("nbar2 atlas refused: X's only atlas refused -> its letter X", s[4]["atlas"] is None and s[4]["word"] == "X", s[4])
    L = boot(pre="REFUSED = {}")
    L.execute("WowCompanion.prd = { atlasOn = function(t, a) PRDN = (PRDN or 0) + 1; if a == 'Gamepad_Rev_A_64' then return false end; t:SetAtlas(a); return true end }")
    G, W = on(L)
    s = shown(L)
    chk("nbar2 atlas: Trail's PRD.atlasOn decides when present (its refusal -> the letter A)", (G.PRDN or 0) >= 8 and s[7]["word"] == "A"
        and s[6]["atlas"] == "Gamepad_Rev_B_64", (G.PRDN, s[7]))
    # the stock bar's visibility, scale and spot
    L = boot(pre="MFSCALE = 0.8")
    G, W = on(L)
    f = W.nbar.f
    chk("nbar2 scale: ours at the stock bar's scale (0.8), its centre in its own units", f.scale == 0.8 and f.pts[1][4] == 960 and f.pts[1][5] == 96,
        (f.scale, list(f.pts[1].values())))
    G.MFSCALE = 1.25
    L.execute('for _, x in ipairs(EVENTS["UI_SCALE_CHANGED"] or {}) do if x.onOnEvent then x.onOnEvent(x, "UI_SCALE_CHANGED") end end')
    chk("nbar2 scale: UI_SCALE_CHANGED places it again (1.25)", f.scale == 1.25, f.scale)
    G.MFVIS = False
    W.nbar.refresh()
    chk("nbar2 visibility: the stock bar not visible (pet battle / cinematic) -> ours at alpha 0, still ticking", f.alpha == 0 and f.shown is True)
    G.MFVIS = True
    W.nbar.refresh()
    chk("nbar2 visibility: back -> alpha 1", f.alpha == 1)
    G.COMBAT = True
    W.nbar.place()
    chk("nbar2 spot: a fight's secret centre keeps the last spot (never the default mid-fight)", W.nbar.at == "stock" and f.pts[1][4] == 960,
        list(f.pts[1].values()))
    G.COMBAT = False
    G.MFX, G.CORE_ONLY = 700, False
    W.nbar.tick(0.06)
    chk("nbar2 spot: a window taking the pad (barsUsable flips) places it again", f.pts[1][4] == 700, list(f.pts[1].values()))
    G.CORE_ONLY = True
    bliz = sorted(set(G.BLIZ.values()))
    chk("nbar2: still only reads and SetAlpha on Blizzard frames, nothing raised", bliz == [] and G.RAISED == 0 and len(G.SPY) == 0, (bliz, G.RAISED))


def aura_checks(chk):
    import test_aura_combat as ta
    SWEEP = r'''
    SWEEPS = {}
    WowCompanion.send = function(j) if type(j) == "table" and j.probe == "aurasweep" then SWEEPS[#SWEEPS + 1] = j end end
    local Region = getmetatable(CreateFrame("Frame"))
    function Region:SetCooldownFromDurationObject(o, clear) if AURDUR_REFUSE then error("refused") end; self.cdObj = o end
    C_UnitAuras.GetAuraDuration = function(u, id) if AURDUR_THROW then error("no access") end; return { aura = id, u = u } end
    '''
    # the duration route: every secret aura's own object, never its secret times
    rig = ta.make(mode="plain")
    rig.L.execute(SWEEP)
    ta.go(rig, "fields", True, 1.0)
    G, P = rig.G, rig.P
    hb = [o for o in P.buffs.values() if o.f.shown]
    tb = [o for o in rig.UF.tb.f.deb.values() if o.f.shown]
    chk("aura sweeps (prd17): the HUD row's secret buffs each get their duration object (by auraInstanceID), no secret times",
        hb and all(o.cd.cdObj is not None and o.cd.cdObj.u == "player" and o.cd.cdExp is None for o in hb), [(o.cd.cdObj, o.cd.cdExp) for o in hb])
    chk("aura sweeps (uf17): the target bar's secret debuffs each get theirs (unit target)",
        tb and all(o.cd.cdObj is not None and o.cd.cdObj.u == "target" for o in tb), [o.cd.cdObj for o in tb])
    ids = sorted(o.cd.cdObj.aura for o in hb)
    chk("aura sweeps: the objects are the auras' own (ids 11 / 12 / 13)", ids == [11, 12, 13], ids)
    sw = [dict(x) for x in G.SWEEPS.values()]
    chk("probe aurasweep: route dur, once (both units share the route), plain", len(sw) == 1 and sw[0]["route"] == "dur" and sw[0]["v"] == "prd17", sw)
    # absent: the old route as before (the secrets straight to the old setter)
    rig2 = ta.make(mode="plain")
    rig2.L.execute("SWEEPS = {}; WowCompanion.send = function(j) if type(j) == 'table' and j.probe == 'aurasweep' then SWEEPS[#SWEEPS + 1] = j end end")
    ta.go(rig2, "fields", True, 1.0)
    o = rig2.P.buffs[1]
    chk("no GetAuraDuration: the HUD row falls back to SetCooldownFromExpirationTime (the secrets as they came)",
        rig2.G.issecretvalue(o.cd.cdExp), o.cd.cdExp)
    sw = [dict(x) for x in rig2.G.SWEEPS.values()]
    chk("probe aurasweep: route none", len(sw) == 1 and sw[0]["route"] == "none", sw)
    # refused: the old route again, the probe says refused
    rig3 = ta.make(mode="plain")
    rig3.L.execute(SWEEP)
    rig3.L.execute("AURDUR_REFUSE = true")
    ta.go(rig3, "fields", True, 1.0)
    o = rig3.P.buffs[1]
    sw = [dict(x) for x in rig3.G.SWEEPS.values()]
    chk("duration object refused: the old setter route, the probe route refused", rig3.G.issecretvalue(o.cd.cdExp) and o.cd.cdObj is None
        and any(x["route"] == "refused" for x in sw), (o.cd.cdExp, sw))
    c = ta.counts(rig3)
    chk("refused: every aura still shown (%s)" % c, c["hb"] == 3 and c["td"] == 3, c)
    # out of a fight: plain times, the plain setter as before (no object needed)
    ta.go(rig, "plain", False, 1.0)
    o = rig.P.buffs[1]
    chk("out of a fight: the plain times on SetCooldown as before", o.cd.cdStart is not None and not rig.G.issecretvalue(o.cd.cdStart))


# ================================================================ the renders (PIL): the bar as the client would place it
def render(out):
    from PIL import Image, ImageDraw, ImageFont
    os.makedirs(out, exist_ok=True)
    paths = []
    for name, lt, rt, fight, title in (("nbar_plain", False, False, False, "Nintendo bar - plain layer, out of a fight"),
                                       ("nbar_zr_held", False, True, True, "Nintendo bar - ZR held, in a fight (duration-object sweeps)"),
                                       ("nbar_plain_fight", False, False, True, "Nintendo bar - plain layer in a fight (fight spells)")):
        L = boot()
        G = L.globals()
        WC = G.WowCompanion
        G.ROWS["uisw"](None, 35)
        G.LT, G.RT, G.COMBAT = lt, rt, fight
        WC.nbar.set = 0
        WC.nbar.tick(0.25)
        f = WC.nbar.f
        W, H = 1920, 1080
        img = Image.new("RGB", (W, H), (34, 44, 38))
        d = ImageDraw.Draw(img)
        for y in range(0, H, 6):                                  # a game-like ground
            d.line([(0, y), (W, y)], fill=(34 + (y * 13) // H, 44 + (y * 9) // H, 38))
        try:
            font = ImageFont.truetype("arialbd.ttf", 15)
            big = ImageFont.truetype("arialbd.ttf", 22)
            small = ImageFont.truetype("arial.ttf", 11)
        except Exception:
            font = big = small = ImageFont.load_default()
        cx, cy = f.pts[1][4], H - f.pts[1][5]                     # CENTER at BOTTOMLEFT + (x, y): y up
        B = WC.nbar.B
        for p in range(1, 9):
            b = f.btn[p]
            pt = b.pts[1]
            bx, by = cx + pt[4], cy - pt[5]
            x0, y0, x1, y1 = bx - B / 2, by - B / 2, bx + B / 2, by + B / 2
            d.rectangle([x0, y0, x1, y1], fill=(10, 10, 10), outline=(70, 62, 40), width=2)
            sh = WC.nbar.shown[p]
            if b.icon.shown:
                ic = sh.icon
                seed = hash(str(ic)) & 0xffffff
                base = ((seed >> 16) % 150 + 60, (seed >> 8) % 150 + 60, seed % 150 + 60)
                vc = b.icon.vc
                col = tuple(int(c * v) for c, v in zip(base, (vc[1], vc[2], vc[3]))) if vc else base
                if b.icon.desat:
                    g = sum(col) // 3
                    col = (g, g, g)
                d.rectangle([x0 + 3, y0 + 3, x1 - 3, y1 - 3], fill=col)
                lab = str(ic).split("\\")[-1][:8]
                d.text((x0 + 5, y0 + 4), lab, fill=(255, 255, 255), font=small)
                if sh.kind == "spell":
                    d.text((x0 + 5, y0 + 16), "fight", fill=(255, 230, 120), font=small)
            if b.cd.dur is not None:                              # a duration object on the swipe: drawn as a 40 % sweep
                d.pieslice([x0 + 3, y0 + 3, x1 - 3, y1 - 3], -90, -90 + 216, fill=(0, 0, 0))
            if b.dim.shown:
                d.rectangle([x0 + 3, y0 + 3, x1 - 3, y1 - 3], fill=(20, 20, 20))
            if b.count.text:
                d.text((x0 + 5, y1 - 16), b.count.text, fill=(255, 255, 255), font=font)
            # the glyph: the atlas's letter / arrow in the game's look, red when out of range
            gx, gy = x1 - 3, y1 - 3
            red = b.glyph.vc and b.glyph.vc[2] < 0.5
            atl = b.glyph.atlas or ""
            letter = atl.replace("Gamepad_", "").replace("_64", "").split("_")[-1]
            letter = {"Left": "<", "Right": ">", "Up": "^", "Down": "v"}.get(letter, letter)
            nint = "Rev_" in atl
            fill = (230, 40, 40) if red else ((245, 245, 245) if nint else (60, 60, 60))
            d.ellipse([gx - 12, gy - 12, gx + 12, gy + 12], fill=fill, outline=(20, 20, 20), width=2)
            d.text((gx - 5, gy - 9), letter, fill=(20, 20, 20) if not red else (255, 255, 255), font=font)
        if f.layer.text:
            d.text((cx - 22, cy - 12), f.layer.text, fill=(255, 210, 0), font=big)
        d.text((20, 20), title, fill=(255, 230, 160), font=big)
        d.text((20, 50), "route=%s  layer=%s  at=%s (stock bar alpha %s)" % (WC.nbar.route, WC.nbar.set, WC.nbar.at,
                                                                         G.GamepadMainActionBarFrame.alpha), fill=(220, 220, 220), font=font)
        img = img.crop((cx - 330, cy - 150, cx + 330, cy + 110)).resize((1320, 520))
        p = os.path.join(out, name + ".png")
        img.save(p)
        paths.append(p)
    print("renders: " + ", ".join(paths))
    return paths


if __name__ == "__main__":
    main()
