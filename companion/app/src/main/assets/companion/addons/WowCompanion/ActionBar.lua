-- WowCompanion / ActionBar.lua
-- The CAST room's data. Two feeds live here:
--
--  1. LEGACY keyboard-slot rows (`slots`, slots 1-12 + their ACTIONBUTTONn keys). Unchanged; the app
--     falls back to them until a `hud` arrives.
--  2. The Controller UI replica (spec "Cast room = Controller UI replica v1"): what WoW: Forever's
--     gamepad HUD (GamepadMainActionBarFrame) shows on each layer of the CURRENT page, read from
--     Blizzard's own frames READ-ONLY, so the bottom screen can replace the top-screen HUD.
--       actionbar.hud   LAYOUT, slow-changing. Built inside collectActionBar(), so it rides every
--                       Core.lua full snapshot and Core dedupes it. NO fast state in here: nothing
--                       that follows the target / soft target / auras (see LIVE). Our own layout
--                       snapshots are capped at one per LAYOUT_WINDOW (trailing edge kept).
--       actionbar.hudS  STATE (usable/mana/range/cooldown flags, and F = the live look of the
--                       target-driven scripted buttons). Sent by THIS file in its own small full
--                       doc, diffed, at most once per 0.5 s (trailing edge kept).
--
-- MEASURED on this build (probe v9, in and out of combat):
--   ALWAYS PLAIN:  GetActionInfo -> "spell"/"item" · GetActionTexture -> fileID
--                  IsUsableAction -> boolean · IsActionInRange -> boolean/nil
--   SECRET IN COMBAT (plain out of combat):
--                  GetActionCooldown · GetActionCharges
--                  (C_Secrets.ShouldActionCooldownBeSecret flips false -> true on entering combat)
-- From Blizzard's API docs for this build: C_ActionBar.GetActionCooldown(slot) returns a TABLE whose
-- isActive / isOnGCD / isEnabled are NeverSecret; startTime / duration are secret when cooldowns are
-- restricted. So the on/off cooldown flag survives combat and the sweep numbers do not.
--
-- SECRECY DISCIPLINE: every value on the wire came out of S()/SN() or is a literal. Booleans derived
-- from API values are compared AFTER S() (`S(...) == true`), never tested raw: a secret boolean would
-- poison the line. Multi-returns use pack() below, never Safety's CALL, whose unpack(r, 2) stops at
-- #r and truncates a return list with a nil hole (GetBindingKey(cmd, true) can produce one).
--
-- TAINT DISCIPLINE (the pad casts THROUGH these frames; see the hide-security findings):
--   on Blizzard frames/mixins we only READ fields, call read-only getters, SetAlpha, HookScript and
--   hooksecurefunc. We never assign a field, never Show/Hide/SetParent/EnableMouse, never call a
--   Blizzard method that changes state, never SetCVar. Every method call is guarded (it may not exist).

local ADDON = (...) or "WowCompanion"
local WC = _G.WowCompanion
local S, SN, CALL, oracle = WC.S, WC.SN, WC.CALL, WC.oracle

local NUM_SLOTS = 12          -- the primary bar; page 1 only for v1

-- Slot -> the binding name WoW uses, so we can ask what key the player has bound.
local function bindingFor(slot)
    return "ACTIONBUTTON" .. slot
end

local function slotInfo(slot)
    local ok, has = CALL(HasAction, slot)
    if not ok or not has then return nil end

    local row = {
        slot = slot,
        kind = S(GetActionInfo, slot),        -- "spell" | "item" | "macro" | ...
        icon = SN(GetActionTexture, slot),    -- texture fileID; art still lives in CASC
        usable = S(IsUsableAction, slot),
        -- IsActionInRange returns nil when there is NO TARGET, which is not the same as
        -- "out of range". Keep them distinct so the UI can grey a tile for one and not the other.
        range = S(IsActionInRange, slot),
        key = S(GetBindingKey, bindingFor(slot)),
    }

    -- name: resolve through the right namespace for the action's kind
    local _, kind, id = CALL(GetActionInfo, slot)
    if kind == "spell" and id then
        local okn, info = CALL(function() return C_Spell.GetSpellInfo(id) end)
        if okn and type(info) == "table" then
            row.name = S(function() return info.name end)
        end
        row.id = SN(function() return id end)
    elseif kind == "item" and id then
        local okn, nm = CALL(function() return C_Item.GetItemNameByID(id) end)
        if okn then row.name = S(function() return nm end) end
        row.id = SN(function() return id end)
    end

    -- cooldown: guarded by its own oracle, because this flips to secret in combat.
    -- Asking first means we send the SECRET sentinel deliberately rather than discovering a refusal later.
    local cdSecret = oracle("ShouldActionCooldownBeSecret", slot)
    if cdSecret then
        row.cd = WC.SECRET
        row.charges = WC.SECRET
    else
        local okc, start, dur = CALL(GetActionCooldown, slot)
        if okc then
            row.cdStart = SN(function() return start end)
            row.cdDur = SN(function() return dur end)
        end
        row.charges = SN(GetActionCharges, slot)
    end

    return row
end

---------------------------------------------------------------------------------------------------
-- Controller UI replica: small helpers
---------------------------------------------------------------------------------------------------

local SENTINEL = { [WC.SECRET] = true, [WC.ABSENT] = true, [WC.THREW] = true }

-- Every return, nil holes included (select('#') counts them; {...} keeps them positionally).
local function pack(...) return { n = select("#", ...), ... } end

-- pcall that keeps ALL returns: r[1] = ok, r[2..r.n] = the function's returns.
local function PCALL(fn, ...)
    if type(fn) ~= "function" then return { n = 1, false } end
    return pack(pcall(fn, ...))
end

-- A value already in hand, made wire-safe: the plain value or a sentinel.
local function SV(v) return S(function() return v end) end

-- A usable string out of S(): non-empty and not a sentinel.
local function realStr(v)
    if type(v) == "string" and v ~= "" and not SENTINEL[v] then return v end
    return nil
end

local function num(v)
    if type(v) == "number" then return v end
    return nil
end

local function bit01(b)
    if b then return 1 end
    return 0
end

-- Raw read of a field (frame references only; NEVER put the result on the wire unsanitised).
local function field(obj, key)
    if obj == nil then return nil end
    local ok, v = pcall(function() return obj[key] end)
    if ok then return v end
    return nil
end

-- A read-only method call through S(): never throws, returns a plain value or a sentinel.
local function MS(obj, method, ...)
    if obj == nil then return WC.ABSENT end
    local args = pack(...)
    return S(function() return obj[method](obj, unpack(args, 1, args.n)) end)
end

-- Resolve an API function at CALL time (namespaces may load late or not exist on this build).
local function api(ns, name)
    local t = _G[ns]
    if type(t) ~= "table" then return nil end
    local fn = t[name]
    if type(fn) == "function" then return fn end
    return nil
end

-- C_ActionBar.<name>, falling back to the old global of the same name.
local function A(name)
    local fn = api("C_ActionBar", name)
    if fn then return fn end
    fn = _G[name]
    if type(fn) == "function" then return fn end
    return nil
end

-- GetActionInfo is a global on this build (actionbutton.lua calls it bare).
local function actionInfoFn()
    local fn = _G.GetActionInfo
    if type(fn) == "function" then return fn end
    return api("C_ActionBar", "GetActionInfo")
end

-- Constants.GamepadActionBarConstants.<name>, with the value the extracted source implies.
local function K(name, default)
    local C = field(_G.Constants, "GamepadActionBarConstants")
    return num(S(function() return C[name] end)) or default
end

local function mainFrame() return _G.GamepadMainActionBarFrame end
local function pageUnit() return field(mainFrame(), "PageUnit") end

local function currentPage(pu)
    if pu == nil then return nil end
    return num(MS(pu, "GetCurrentPage"))
end

local function setAlpha(frame, a)
    local fn = field(frame, "SetAlpha")
    if type(fn) == "function" then return (pcall(fn, frame, a)) end
    return false
end

local function say(s)
    if DEFAULT_CHAT_FRAME then
        pcall(DEFAULT_CHAT_FRAME.AddMessage, DEFAULT_CHAT_FRAME, "|cffffd100WowCompanion|r " .. s)
    end
end

WC.hudHidden = false    -- OUR alpha-hide flag (never stored on a Blizzard frame)
WC.hudErrors = 0

local function noteError(msg)
    WC.hudErrors = WC.hudErrors + 1
    WC.hudLastError = msg        -- local diagnostics only; never put on the wire
end

---------------------------------------------------------------------------------------------------
-- Layers: which bar the pad shows for each layer, resolved read-only
---------------------------------------------------------------------------------------------------

local LAYERS = { "n", "lt", "rt", "lr", "fr", "ho", "sc" }
local STATE_LAYERS = { "n", "lt", "rt", "lr" }
local BASE_BAR = {
    lt = "leftBar", rt = "rightBar", lr = "bottomBar",
    fr = "friendlyTargetingBar", ho = "hostileTargetingBar", sc = "shortcutsBar",
}
local STATIC = { fr = true, ho = true, sc = true }
-- Top bar positions 5-8 are RESERVED scripted buttons (mainactionbarframe.lua SetupNonbindableActions):
-- Right.ActionButton1 Interact, 2 Inspect, 3 Back, 4 Jump.
local FX = { [5] = "interact", [6] = "inspect", [7] = "back", [8] = "jump" }
-- LIVE positions: scripted buttons whose LOOK follows the target, the soft target or the player's
-- auras, per Blizzard's source:
--   n5  Interact icon      UpdateInteractIcons on PLAYER_TARGET / SOFT_INTERACT / SOFT_ENEMY_CHANGED
--   n6  Inspect atlas      crosshair_inspect_32 / crosshair_unableinspect_32 on PLAYER_TARGET_CHANGED
--                          (mainactionbarframe.lua UpdateInspectIcon; the marker sheet while LB/RB held)
--   fr6/ho6 raid marker    enabled = UnitExists("target") (targetactionbars/shared.lua UpdateState)
--   fr7/ho7 assist         targettarget portrait + enabled (Shared.RefreshAssistButton)
--   sc6 buffs              enabled only with an active aura (shortcutsactionbar.lua RefreshBuffsButton)
-- The LAYOUT carries them as fixed buttons with e=true and no live icon ("e" = enabled is live,
-- "i" = the icon is live); their live look rides the STATE as hudS.F["<layer><p>"] = {e, i}. Otherwise
-- every tab-target would change the layout, and a layout change costs a full Core snapshot + a flush.
local LIVE = {
    n = { [5] = "ei", [6] = "ei" },
    fr = { [6] = "e", [7] = "ei" },
    ho = { [6] = "e", [7] = "ei" },
    sc = { [6] = "e" },
}

-- bar.actionButtons[1..8] = Left.ActionButton1-4 (D-pad L/U/R/D) then Right.ActionButton1-4
-- (face left/top/right/bottom) -- actionbar.lua InitActionButtons. Index = pad position.
local function barButtons(bar)
    local out = {}
    if bar == nil then return out end
    local list = field(bar, "actionButtons")
    for p = 1, 8 do
        local b = nil
        if type(list) == "table" then b = field(list, p) end
        if b == nil then
            local grp = field(bar, (p <= 4) and "Left" or "Right")
            b = field(grp, "ActionButton" .. ((p <= 4) and p or (p - 4)))
        end
        out[p] = b
    end
    return out
end

local function spellIdOf(d)
    if d.k == "spell" or (d.k == "macro" and d.sub == "spell") then return d.id end
    return nil
end

-- Internal descriptor of one button. Holds only sanitised values (+ the frame reference).
-- mode: "act" (action button), "pet" (possess bar), "static" (targeting / shortcuts bars),
--       "fx" (reserved top-bar face buttons).
local function describe(btn, p, mode)
    local d = { btn = btn, p = p, s = 0, k = "", e = false }
    if btn == nil then return d end
    d.e = MS(btn, "IsEnabled") == true
    if mode == "fx" then
        d.k, d.fx = "fixed", FX[p]
        return d
    elseif mode == "static" then
        -- scripted buttons; a disabled one (solo friendly D-pad, no pet...) is an empty slot
        if d.e then d.k = "fixed" end
        return d
    elseif mode == "pet" then
        local id = num(MS(btn, "GetID"))
        d.s, d.pet = id or 0, true
        if id and id > 0 then
            local r = PCALL(_G.GetPetActionInfo, id)
            if r[1] and realStr(SV(r[2])) then d.k = "pet" end
        end
        return d
    end
    -- .action is the slot the button actually fires (SecureActionButton CalculateAction);
    -- GetID() is the storage slot it was pointed at. Prefer .action (see data-feasibility F02).
    local s = num(S(function() return btn.action end))
    if not s then s = num(MS(btn, "GetID")) end
    d.s = s or 0
    if not s or s <= 0 then return d end
    if S(A("HasAction"), s) == false then return d end
    local r = PCALL(actionInfoFn(), s)
    if not r[1] then return d end
    local kind = realStr(SV(r[2]))
    if not kind then return d end
    d.k = kind
    d.id = num(SV(r[3]))
    d.sub = realStr(SV(r[4]))
    return d
end

local function emptyLayer()
    local d = {}
    for p = 1, 8 do d[p] = { p = p, s = 0, k = "", e = false } end
    return { d = d }
end

-- Mirrors GamepadActionBarPageUnitMixin:ActionBarModKeyDownStateCheck (pageunit.lua) READ-ONLY:
-- the base bar per modifier (none = topBar, or possessBar on the special page; LT = leftBar;
-- RT = rightBar; LT+RT = bottomBar), then the first override bar in pageUnit.overrideBars order
-- (possess, stance) whose IsBarActivelyOverridingActionBar(base) is true replaces it. The
-- targeting / shortcuts bars also sit in overrideBars, but here they are their own layers
-- (fr / ho / sc), picked on the app from LB / RB.
local function resolveAll(pu)
    local out = {}
    for _, key in ipairs(LAYERS) do out[key] = emptyLayer() end
    local bars = field(pu, "actionBars")
    if bars == nil then return out end
    local possess, stance, top = field(bars, "possessBar"), field(bars, "stanceBar"), field(bars, "topBar")
    local page = currentPage(pu)
    local special = K("GAMEPAD_ACTION_BAR_PAGE_UNIT_SPECIAL_PAGE_INDEX", 4)

    local function resolve(base)
        if base == nil then return nil, nil end
        if possess ~= nil and MS(possess, "IsBarActivelyOverridingActionBar", base) == true then
            return possess, "possess"
        end
        if stance ~= nil and MS(stance, "IsBarActivelyOverridingActionBar", base) == true then
            return stance, "stance"
        end
        return base, nil
    end

    for _, key in ipairs(LAYERS) do
        local bar, ov = nil, nil
        if key == "n" then
            local base = top
            if page ~= nil and page == special then base = possess end
            bar, ov = resolve(base)
        elseif STATIC[key] then
            bar = field(bars, BASE_BAR[key])
        else
            bar, ov = resolve(field(bars, BASE_BAR[key]))
        end
        if bar ~= nil then
            local btns = barButtons(bar)
            local d = {}
            for p = 1, 8 do
                local mode = "act"
                if STATIC[key] then
                    mode = "static"
                elseif possess ~= nil and bar == possess then
                    mode = "pet"
                elseif top ~= nil and bar == top and p >= 5 then
                    mode = "fx"
                end
                local dd = describe(btns[p], p, mode)
                local lv = LIVE[key] and LIVE[key][p]
                if lv and dd.btn ~= nil and (mode == "fx" or mode == "static") then
                    dd.live = lv                  -- structural in the layout; live look in hudS.F
                    dd.k, dd.e = "fixed", true
                end
                d[p] = dd
            end
            out[key] = { d = d, ov = ov }
        end
    end
    return out
end

---------------------------------------------------------------------------------------------------
-- LAYOUT (actionbar.hud)
---------------------------------------------------------------------------------------------------

-- F12 = map session Reload; never bind a key that has a character. GameNative delivers a numpad key
-- as its CHARACTER (NUMPAD_DIVIDE arrived as "/"), so a numpad binding never fires and the character
-- can land on a default binding instead ("-" is ACTIONBUTTON11). F-keys arrive as themselves.
local KEY_HIDE, KEY_SHOW = "F10", "F11"
local keysBound = false

local function texVal(t)
    if type(t) == "number" then return t end
    t = realStr(t)
    if t then return "tex:" .. t end
    return nil
end

-- The texture a scripted button is CURRENTLY showing, read-only: SpecialActionIcon (atlas first,
-- then texture) while it is shown, else the ordinary icon.
local function iconOf(btn)
    local sai = field(btn, "SpecialActionIcon")
    if sai ~= nil and MS(sai, "IsShown") == true then
        local atlas = realStr(MS(sai, "GetAtlas"))
        if atlas then return "atlas:" .. atlas end
        local t = texVal(MS(sai, "GetTexture"))
        if t then return t end
    end
    local ic = field(btn, "icon")
    if ic ~= nil then return texVal(MS(ic, "GetTexture")) end
    return nil
end

local function chargesOf(s)
    local ok, t = CALL(api("C_ActionBar", "GetActionCharges"), s)
    if ok and type(t) == "table" then return t end
    return nil
end

local function cooldownOf(s)
    local ok, t = CALL(api("C_ActionBar", "GetActionCooldown"), s)
    if ok and type(t) == "table" then return t end
    return nil
end

local function tfield(t, k) return S(function() return t[k] end) end

local function actionName(d)
    local id = d.id
    if d.k == "spell" and id then
        local n = realStr(S(api("C_Spell", "GetSpellName"), id))
        if n then return n end
        local ok, info = CALL(api("C_Spell", "GetSpellInfo"), id)
        if ok and type(info) == "table" then return realStr(tfield(info, "name")) end
        return nil
    elseif d.k == "item" and id then
        return realStr(S(api("C_Item", "GetItemNameByID"), id))
    elseif d.k == "flyout" and id then
        return realStr(S(_G.GetFlyoutInfo, id))
    end
    return realStr(S(A("GetActionText"), d.s))      -- macros (and anything else with a label)
end

-- b-cast-bar-late: a spell's cast time in ms (0 = instant), for the app's predictive cast bar; nil when the game says nothing
local function castMs(sid)
    if not sid then return nil end
    local ok, si = CALL(function() return C_Spell.GetSpellInfo(sid) end)
    if not ok or type(si) ~= "table" then return nil end
    local ct = num(tfield(si, "castTime"))
    return ct and ct >= 0 and math.floor(ct + 0.5) or nil
end

local function layoutButton(d)
    local b = { p = d.p, f = "", s = d.s or 0, k = d.k or "", e = d.e == true }
    local btn = d.btn
    if btn == nil then return b end
    b.f = realStr(MS(btn, "GetName")) or ""
    b.fx = d.fx
    if b.k == "fixed" then
        if not (d.live and d.live:find("i", 1, true)) then b.i = iconOf(btn) end   -- live icons: hudS.F
        return b
    elseif b.k == "" then
        return b
    elseif b.k == "pet" then
        -- GetPetActionInfo: name, texture, isToken, isActive, autoCastAllowed, autoCastEnabled,
        -- spellID, checksRange, inRange (possessbar.lua / petactionflyout.lua)
        local r = PCALL(_G.GetPetActionInfo, d.s)
        if r[1] then
            local name, tex = realStr(SV(r[2])), SV(r[3])
            if SV(r[4]) == true then      -- token: both are keys into _G (petactionflyout.lua)
                if name then name = realStr(S(function() return _G[name] end)) or name end
                local key = realStr(tex)
                if key then tex = S(function() return _G[key] end) end
            end
            b.n, b.i, b.id = name, texVal(tex), num(SV(r[8]))
        end
        return b
    end
    b.id = d.id
    b.i = texVal(S(A("GetActionTexture"), d.s))
    b.n = actionName(d)
    b.rg = S(A("HasRangeRequirements"), d.s) == true
    local ch = chargesOf(d.s)
    if ch then
        local mc = num(tfield(ch, "maxCharges"))             -- NeverSecret
        if mc and mc > 1 then b.mc = mc end
    end
    local sid = spellIdOf(d)
    if sid then b.cs = num(S(api("C_Secrets", "GetSpellCooldownSecrecy"), sid)); b.ct = castMs(sid) end   -- b-cast-bar-late: cast ms
    return b
end

local function readCV(name)
    local fn = api("C_CVar", "GetCVarBool") or _G.GetCVarBool
    return S(fn, name) == true
end
-- f-class-radial: the two bar placements, read only (the game's GamepadStanceBarOverride / GamepadPossessBarOverride: 1 = none / the
-- special page, 2-4 page 1 Left / Right / Bottom, 5-12 pages 2-3 Top / Left / Right / Bottom), with the game's own words
local BAR_WORDS = { [2] = "GAMEPAD_BAR_PAGE1_LEFT", [3] = "GAMEPAD_BAR_PAGE1_RIGHT", [4] = "GAMEPAD_BAR_PAGE1_BOTTOM",
    [5] = "GAMEPAD_BAR_PAGE2_TOP", [6] = "GAMEPAD_BAR_PAGE2_LEFT", [7] = "GAMEPAD_BAR_PAGE2_RIGHT", [8] = "GAMEPAD_BAR_PAGE2_BOTTOM",
    [9] = "GAMEPAD_BAR_PAGE3_TOP", [10] = "GAMEPAD_BAR_PAGE3_LEFT", [11] = "GAMEPAD_BAR_PAGE3_RIGHT", [12] = "GAMEPAD_BAR_PAGE3_BOTTOM" }
local function barPlacement()
    local fn = api("C_CVar", "GetCVar") or _G.GetCVar
    local function one(cv, first, title)
        local v = num(tonumber(S(fn, cv) or ""))
        if not v then return nil end
        local word = realStr(rawget(_G, v == 1 and first or (BAR_WORDS[v] or "")))
        return { v = v, n = word, t = realStr(rawget(_G, title)) }
    end
    return { st = one("GamepadStanceBarOverride", "GAMEPAD_STANCE_BAR_NONE", "GAMEPAD_STANCE_BAR_OVERRIDE"),
             ps = one("GamepadPossessBarOverride", "GAMEPAD_POSSESS_BAR_SPECIAL_PAGE", "GAMEPAD_POSSESS_BAR_OVERRIDE") }
end

-- Pages 1..3 that hold any action in a bindable slot (the page chips).
local function pagesWithActions()
    local out = { false, false, false }
    local util = _G.GamepadActionBarBindingUtil
    local slotFn = field(util, "GetGamepadStorageSlotIndexFromPageAndPageUnitSlotID")
    if type(slotFn) ~= "function" then slotFn = nil end
    local first = SN(api("C_GamepadUI", "GetFirstGamepadActionStorageSlotIndex"))
    local perPage = K("NUM_PAGEABLE_SLOTS_PER_GAMEPAD_ACTION_BAR_PAGE_UNIT_STANDARD_PAGE", 32)
    local reserved = K("NUM_RESERVED_SLOTS_PER_GAMEPAD_ACTION_BAR_PAGE_UNIT", 4)
    local has = A("HasAction")
    for page = 1, 3 do
        for id = 1, perPage do
            if id < 5 or id >= 5 + reserved then
                local slot = slotFn and num(S(slotFn, page, id)) or nil
                if not slot and first then
                    -- actionbindingutil.lua GetGamepadStorageSlotIndexFromPageAndPageUnitSlotID
                    slot = first + perPage * (page - 1) + (id - 1) - reserved * (page - 1)
                    if id >= 5 then slot = slot - reserved end
                end
                if slot and S(has, slot) == true then
                    out[page] = true
                    break
                end
            end
        end
    end
    return out
end

---------------------------------------------------------------------------------------------------
-- CLASS ACTIONS (user ruling 2026-09-28: cover class-only things, hide them from the rest). Blizzard's
-- camelot/*.lua: LB+LT = PageUnit.LeftClassAction, RB+RT = RightClassAction, both opening a radial.
--   DRUID  l = flyout 262   PALADIN l = flyout 270   WARRIOR r = flyout 269   (classspellflyout.lua)
--   HUNTER / WARLOCK  l = pet COMMANDS (token pet actions, petactionflyout.lua)
--                     r = pet SPELLS (non-token pet actions with a spell id, petspellflyout.lua)
-- Every other class: hud.ca is absent. Contents change rarely (pet summoned, spell learned): layout.
---------------------------------------------------------------------------------------------------
local CLASS_FLYOUT = { DRUID = { 262, "l" }, PALADIN = { 270, "l" }, WARRIOR = { 269, "r" } }

local function iconOfPath(p)
    p = realStr(p)
    if not p then return nil end
    local ok, fid = CALL(GetFileIDFromPath, p)
    fid = ok and num(fid) or nil
    return fid or ("tex:" .. p)
end

local function flyoutEntries(id)
    local ok, _, _, numSlots = CALL(GetFlyoutInfo, id)
    local list = {}
    -- f-chord-icons: the form / stance / aura on now (the game's shapeshift bar), so the app can show ITS icon on the chord's buttons
    local on = {}
    local okn, nf = CALL(GetNumShapeshiftForms)
    for f = 1, (okn and num(nf) or 0) do
        local fr = pack(CALL(GetShapeshiftFormInfo, f))
        if fr[1] and fr[3] == true and num(fr[5]) then on[num(fr[5])] = true end
    end
    numSlots = ok and num(numSlots) or 0
    for i = 1, numSlots do
        local r = pack(CALL(GetFlyoutSlotInfo, id, i))
        local spellID, known, name = num(r[2]), r[4] == true, realStr(r[5])
        if r[1] and spellID and known and #list < 8 then
            local okt, tex = CALL(function() return C_Spell.GetSpellTexture(spellID) end)
            -- `p`: the radial's place (1 = top, clockwise), the known spells in the flyout's order as the game lays them
            list[#list + 1] = { k = "spell", id = spellID, n = name, i = okt and num(tex) or nil, e = true, p = #list + 1,
                                act = on[spellID] and 1 or nil }
        end
    end
    return list
end
-- f-class-radial: the game's pet COMMAND radial keeps fixed places (camelot/petclassflyouts.xml): Attack 1, Follow 2, Move To 3,
-- (4 left empty on purpose), Passive 5, Defensive 6, Assist 7; any other command (Stay, Aggressive) is not on it
local PET_CMD_PLACE = { PET_ACTION_ATTACK = 1, PET_ACTION_FOLLOW = 2, PET_ACTION_MOVE_TO = 3, PET_MODE_PASSIVE = 5,
                        PET_MODE_DEFENSIVE = 6, PET_MODE_ASSIST = 7 }

local function petEntries(tokens)
    local list = {}
    local n = num(rawget(_G, "NUM_PET_ACTION_SLOTS")) or 10
    for i = 1, n do
        local r = pack(CALL(GetPetActionInfo, i))
        -- name, texture, isToken, isActive, autoCastAllowed, autoCastEnabled, spellID
        local name, tex, isToken, active = r[2], r[3], r[4] == true, r[5] == true
        local place = tokens and PET_CMD_PLACE[realStr(name) or ""] or (not tokens and #list < 8 and #list + 1) or nil
        if r[1] and name ~= nil and isToken == tokens and place then
            local e = { k = tokens and "petcmd" or "petspell", slot = i, act = bit01(active), p = place }
            if tokens then
                -- a token's name and texture are GLOBAL STRING NAMES (PET_ACTION_ATTACK -> "Attack")
                e.n = realStr(rawget(_G, realStr(name) or "")) or realStr(name)
                e.i = iconOfPath(rawget(_G, realStr(tex) or ""))
            else
                e.n = realStr(name)
                e.id = num(r[8])
                e.i = num(tex) or iconOfPath(tex)
                e.auto = bit01(r[7] == true)
            end
            list[#list + 1] = e
        end
    end
    return list
end

-- f-class-radial (the user's pick: "Mirror the game's radials"): THE TOUCH PICK. One secure pad row "classr" (24 cells, set OUT of
-- combat; frozen in a fight, as every secure cell): 0-7 the LEFT radial's places 1-8, 8-15 the RIGHT radial's, 16-23 the right
-- radial's pet spells' AUTOCAST toggle (a long-press in the app). A spell is the game's own cast (type spell), a pet command / pet
-- spell the game's pet action (type pet: CastPetAction), autocast in-memory macro text (/petautocasttoggle, never saved). Nothing is
-- saved; the cells hold what the radial shows. Each entry carries its cell (`c`, `ac`) only while the built cells match it.
local CR = { want = {}, frozen = {}, row = nil, sig = nil }
local function crCellOf(side, e) return (side == "l" and 0 or 8) + e.p - 1 end
local function crInit(btn, col)
    local w = CR.want[col]
    btn:SetAttribute("type", w and w.type or nil)
    btn:SetAttribute("spell", w and w.spell or nil)
    btn:SetAttribute("action", w and w.action or nil)
    btn:SetAttribute("macrotext", w and w.macrotext or nil)
    CR.frozen[col] = w and w.sig or nil
end
local function crClick(col)
    WC.send(WC.encode({ type = "classuse", v = 1, col = col, fight = (S(InCombatLockdown) == true) or nil }))
    if type(WC.flush) == "function" then pcall(WC.flush) else WC.push() end
end
-- the cells for the radials on show; built again only when they change, out of combat
local function crSync(ca)
    local want, parts = {}, {}
    for _, side in ipairs({ "l", "r" }) do
        local rad = ca and ca[side]
        for _, e in ipairs(rad and rad.e or {}) do
            local col = crCellOf(side, e)
            local w
            if e.k == "spell" and e.id then w = { type = "spell", spell = e.id, sig = "s" .. e.id }
            elseif (e.k == "petcmd" or e.k == "petspell") and e.slot then w = { type = "pet", action = e.slot, sig = "p" .. e.slot } end
            if w then want[col] = w; parts[#parts + 1] = col .. "=" .. w.sig end
            if e.k == "petspell" and e.n and side == "r" then
                local acol = 16 + e.p - 1
                want[acol] = { type = "macro", macrotext = "/petautocasttoggle " .. e.n, sig = "a" .. e.n }
                parts[#parts + 1] = acol .. "=a" .. e.n
            end
        end
    end
    local sig = table.concat(parts, ",")
    local pad = WC.pad
    if sig ~= CR.sig and S(InCombatLockdown) == false and type(pad) == "table" and type(pad.secureRow) == "function" then
        CR.want = want
        local ok, row = pcall(pad.secureRow, "classr", 24, crInit, crClick)
        CR.row = (ok and type(row) == "number") and row or nil
        CR.sig = CR.row and sig or nil
        if not CR.row then CR.frozen = {} end
    end
    -- each entry's cells, only where the built cell is what it shows (a fight's change waits for its end)
    for _, side in ipairs({ "l", "r" }) do
        local rad = ca and ca[side]
        for _, e in ipairs(rad and rad.e or {}) do
            local col = crCellOf(side, e)
            if want[col] and CR.frozen[col] == want[col].sig then e.c = col end
            local acol = 16 + e.p - 1
            if e.k == "petspell" and want[acol] and CR.frozen[acol] == want[acol].sig then e.ac = acol end
        end
    end
    if ca and not CR.row then ca.nopad = 1 end
end

local function classActions()
    local okc, cls = CALL(UnitClassBase, "player")
    cls = okc and realStr(cls) or nil
    if not cls then return nil end
    local ca = {}
    local fl = CLASS_FLYOUT[cls]
    if fl then
        ca[fl[2]] = { kind = "flyout", id = fl[1], e = flyoutEntries(fl[1]) }
    elseif cls == "HUNTER" or cls == "WARLOCK" then
        ca.l = { kind = "petcmd", e = petEntries(true) }
        ca.r = { kind = "petspell", e = petEntries(false) }
    else
        return nil
    end
    pcall(crSync, ca)
    return ca
end

local function buildHud()
    local mf, pu = mainFrame(), pageUnit()
    local hud = {
        v = 1,
        -- p-bars-grey (a druid: "always grey"): the controller HUD is live when its main frame shows OR its page unit has an active bar
        -- (a form's / stance's bar can stand in for the main frame); the app greys the bar only for a dead stream or no bar at all
        ok = (mf ~= nil and MS(mf, "IsShown") == true) or MS(pu, "GetActiveBar") ~= nil,
        -- OUR alpha-hide is APPLIED right now: the flag alone is only the intent. A SetAlpha the client
        -- blocked (ADDON_ACTION_BLOCKED, not a Lua error) or a frame that does not exist yet must read
        -- hidden=false, so the app's "re-send hide while visible" recovery fires.
        hidden = WC.hudHidden == true and mf ~= nil and num(MS(mf, "GetAlpha")) == 0,
        page = currentPage(pu),
        pages = pagesWithActions(),
        cv = {
            swapT = readCV("GamepadSwapTargetModifiers"),
            hudToggle = readCV("GamepadHudModifierUsesToggle"),
            compact = readCV("GamepadUseCompactActionBar"),
            party = readCV("GamepadUsePartyTargeting"),
        },
        -- f-class-radial: where the game puts the STANCE / FORM bar and the PET (possess) bar (its Controller settings; read only,
        -- never written): the value and the game's own label for it
        bars = barPlacement(),
        L = {},
    }
    if keysBound then hud.keys = { hide = KEY_HIDE, show = KEY_SHOW } end
    local okca, ca = pcall(classActions)            -- class-only radials; absent for every other class
    if okca and ca then hud.ca = ca end
    local layers = resolveAll(pu)
    local ov = {}
    for _, key in ipairs(LAYERS) do
        local lay = layers[key]
        local list = {}
        for p = 1, 8 do list[p] = layoutButton(lay.d[p]) end
        hud.L[key] = list
        if lay.ov then ov[key] = lay.ov end
    end
    if next(ov) ~= nil then hud.ov = ov end   -- omitted when nothing overrides (an empty table would encode as [])
    return hud
end

---------------------------------------------------------------------------------------------------
-- STATE (actionbar.hudS)
---------------------------------------------------------------------------------------------------

local STATE_WINDOW = 0.5      -- at most one state emit (= one flush) per window; trailing edge kept
local autoAttack = false      -- PLAYER_ENTER_COMBAT / PLAYER_LEAVE_COMBAT (the Interact button's flash)
local autoRepeat = false      -- START_ / STOP_AUTOREPEAT_SPELL: Auto Shot, Shoot, wands (hudS.ar; Home's big controller)
local glowing = {}            -- spellID -> true, from SPELL_ACTIVATION_OVERLAY_GLOW_SHOW / _HIDE
local stateSlots = {}         -- action slots of the 4 state layers, for the range poll and the GCD sample
local loggedIn = false

-- The GCD flag is SAMPLED INSIDE the SPELL_UPDATE_COOLDOWN handler. isOnGCD is Nilable and documented
-- "do not trust this field unless responding to a SPELL_UPDATE_COOLDOWN event"
-- (spellshareddocumentation.lua:30), and buildState only ever runs later, from a C_Timer. So the state
-- never reads the flag itself for a sampled slot; a nil read is UNKNOWN and never means "not the GCD"
-- (that turned every GCD into a false cooldown: cd=1, 50% dim, a dashed estimate sweep).
local gcdOf = {}              -- slot -> true / false as sampled in the handler (current state slots only)
local gcdKnown, gcdRunning = false, false   -- whether the last trusted sample saw ANY slot on the GCD

local function sampleGCD()
    local fresh, anyBool, anyTrue = {}, false, false
    for _, s in ipairs(stateSlots) do
        local cd = cooldownOf(s)
        local v = WC.ABSENT
        if cd then v = tfield(cd, "isOnGCD") end
        if v == true or v == false then
            fresh[s] = v
            anyBool = true
            if v then anyTrue = true end
        elseif gcdOf[s] ~= nil then
            fresh[s] = gcdOf[s]           -- nil even inside the handler: unknown, keep the last sample
        end
    end
    gcdOf = fresh                         -- slots out of view are forgotten, never left stale
    if anyBool then gcdKnown, gcdRunning = true, anyTrue end
end

-- Is this ACTIVE cooldown only the GCD?
local function onGCD(s, cd)
    local g = gcdOf[s]
    if g ~= nil then return g end
    -- Never sampled (a page or override bar that just came into view): if the last trusted sample saw
    -- no GCD running, an active cooldown is a real one. Otherwise only an explicit false counts as
    -- "not the GCD"; nil / a sentinel is unknown, and unknown never claims a cooldown.
    if gcdKnown and not gcdRunning then return false end
    return tfield(cd, "isOnGCD") ~= false
end

-- The live look of a LIVE scripted button (see LIVE): the icon it is showing right now.
local function liveIcon(d, hasTarget)
    if d.fx == "inspect" then
        local sai = field(d.btn, "SpecialActionIcon")
        local a = realStr(MS(sai, "GetAtlas"))
        if a and a:sub(1, 10) == "crosshair_" then return "atlas:" .. a end
        -- the raid-marker sheet shows only while LB/RB is held, when the app shows another layer:
        -- report Blizzard's own no-modifier formula (mainactionbarframe.lua UpdateInspectIcon)
        return hasTarget and "atlas:crosshair_inspect_32" or "atlas:crosshair_unableinspect_32"
    end
    return iconOf(d.btn)
end

local function buttonState(d, combat, hasTarget)
    local o = { p = d.p }       -- p keeps every entry a JSON object (an empty table encodes as [])
    if d.k == "" or d.k == "fixed" then return o end
    if d.k == "pet" then
        local id = d.s
        local u = S(_G.GetPetActionSlotUsable, id)
        if u == true or u == false then o.u = bit01(u) end
        local r = PCALL(_G.GetPetActionInfo, id)
        if r[1] then
            o.chk = bit01(SV(r[5]) == true)
            if hasTarget and SV(r[9]) == true then o.r = bit01(SV(r[10]) == true) end
        end
        local c = PCALL(_G.GetPetActionCooldown, id)
        if c[1] then
            local a, b = num(SV(c[2])), num(SV(c[3]))
            if b then
                o.cd = bit01(b > 0)
                if b > 0 and a and not combat then o.st, o.du = a, b end
            end
        end
        return o
    end
    local s = d.s
    local r = PCALL(A("IsUsableAction"), s)            -- isUsable, isLackingResources
    if r[1] then
        o.u = bit01(SV(r[2]) == true)
        o.m = bit01(SV(r[3]) == true)
    end
    if hasTarget then
        local v = S(A("IsActionInRange"), s)
        if v == true then o.r = 1 elseif v == false then o.r = 0 end
    end
    local cd = cooldownOf(s)
    if cd then
        local on = tfield(cd, "isActive") == true and not onGCD(s, cd)
        o.cd = bit01(on)
        if on and not combat then
            local a, b = num(tfield(cd, "startTime")), num(tfield(cd, "duration"))
            if a and b then o.st, o.du = a, b end
        end
    end
    local ch = chargesOf(s)
    if ch then
        local mc = num(tfield(ch, "maxCharges"))
        if mc and mc > 1 then o.ch = num(tfield(ch, "currentCharges")) end
    end
    if d.k == "item" and d.id then
        local cf, sf = A("IsConsumableAction"), A("IsStackableAction")
        if (cf == nil and sf == nil) or S(cf, s) == true or S(sf, s) == true then
            o.cnt = num(S(api("C_Item", "GetItemCount"), d.id))
        end
    end
    local sid = spellIdOf(d)
    local glow = false
    if sid then
        glow = glowing[sid] == true
            or S(api("C_SpellActivationOverlay", "IsSpellOverlayed"), sid) == true
    end
    o.gl = bit01(glow)
    o.chk = bit01(S(A("IsCurrentAction"), s) == true or S(A("IsAutoRepeatAction"), s) == true)
    o.eq = bit01(S(A("IsEquippedAction"), s) == true)
    return o
end

-- BARS USABLE: false while a window owns the controller (an NPC dialog, the bags, any menu). The HUD then
-- collapses (smaller buttons, no prompts) and LT/RT stop switching layers (mainactionbarframe.lua
-- AreActionBarsUsable, holdbindings.lua IsLeft/RightModifierDown). Blizzard wires the change through a
-- closure made at init (BindToCoreBindingActive), so a hook on the frame method would never fire, and
-- adding to its listener list would run us inside its secure path. So: read it, and poll it (below).
local function ibmOf()
    local gsu = rawget(_G, "GamepadSharedUtility")
    if type(gsu) ~= "table" then return nil end
    local ibm = gsu.InputBindingManager                      -- mainactionbarframe.lua's path
    if type(ibm) ~= "table" and type(gsu.BindingStack) == "table" then
        ibm = gsu.BindingStack.InputBindingManager           -- the legend's path
    end
    if type(ibm) ~= "table" or type(ibm.IsOnlyCoreBindingSetActive) ~= "function" then return nil end
    return ibm
end

WC.hudBuSource = nil
local function barsUsable()
    local ibm = ibmOf()
    if ibm then
        local v = S(function() return ibm:IsOnlyCoreBindingSetActive() end)
        if v == true or v == false then WC.hudBuSource = "ibm"; return v end
    end
    -- Fallback, what the player sees: Blizzard collapses the ACTIVE bar's buttons to 32 units while a
    -- window owns the pad (40 expanded; actionbarstyles.lua). Read-only getters only.
    local bar = MS(pageUnit(), "GetActiveBar")
    local btns = field(bar, "actionButtons")
    local b1 = type(btns) == "table" and btns[1] or nil
    local w = num(MS(b1, "GetWidth"))
    if w then WC.hudBuSource = "width"; return w > 36 end
    WC.hudBuSource = "none"
    return nil
end

-- The swing timer (user, 2026-09-29): PLAYER_SWING(duration, type 0 main / 1 off / 2 ranged) is Forever's
-- own swing signal (Blizzard_SwingTimer uses it) and carries no secret flags. The last swing of each type
-- is kept; hudS.sw names ONE: the most recent, Auto Shot (ranged) whenever it is running (user: one line).
-- s = GetTime() the swing started (the app maps the game clock), d = its duration. s changes every swing,
-- so each swing is one state doc, batched in the 0.5 s window like everything else.
local swings = {}

local function pickSwing(now)
    local r = swings[2]
    if r and now < r.s + r.d + 0.3 then return 2, r end
    local best, bk
    for k, v in pairs(swings) do
        if not best or v.s > best.s then best, bk = v, k end
    end
    return bk, best
end

local function noteSwing(dur, kind)
    local d, k = SN(function() return tonumber(dur) end), SN(function() return tonumber(kind) end)
    if not d or not k or d <= 0 then return end
    swings[k] = { s = SN(GetTime) or 0, d = d }
end

-- The cast bar (user 2026-09-30, LATER item 21): the player's own cast / channel, for the app's one bottom bar
-- (grey = swing, gold = cast). UnitCastingInfo / UnitChannelInfo are secret only for units that are NOT the
-- player or pet (SecretWhenUnitSpellCastRestricted), so "player" should be plain in combat: MEASURED here --
-- the first unreadable value sends ONE cap doc (hudCast first = dropped), the first good one (first = ok).
-- hudS.cs = { k = "c" cast | "h" channel, s = start, e = end } (GetTime seconds) while it runs;
-- hudS.ce = { x = 1 done / 0 interrupted or failed, t = when } for 2 s after it ends.
local castNow, castEnd = nil, nil
WC.hudCastSeen, WC.hudCastDropped = 0, 0

local function castTimes(fn)
    local st = SN(function() return select(4, fn("player")) end)
    local en = SN(function() return select(5, fn("player")) end)
    return st, en
end

local function noteCast(event)
    local now = SN(GetTime) or 0
    if event == "UNIT_SPELLCAST_START" or event == "UNIT_SPELLCAST_DELAYED"
        or event == "UNIT_SPELLCAST_CHANNEL_START" or event == "UNIT_SPELLCAST_CHANNEL_UPDATE" then
        local ch = event:find("CHANNEL", 1, true) ~= nil
        local fn = ch and _G.UnitChannelInfo or _G.UnitCastingInfo
        if type(fn) ~= "function" then return end
        WC.hudCastSeen = WC.hudCastSeen + 1
        local st, en = castTimes(fn)
        if not st or not en or en <= st then
            WC.hudCastDropped = WC.hudCastDropped + 1
            if WC.hudCastDropped == 1 then WC.emit({ type = "cap", hudCast = { first = "dropped", ev = event } }) end
            return
        end
        if WC.hudCastSeen - WC.hudCastDropped == 1 then
            WC.emit({ type = "cap", hudCast = { first = "ok", ev = event, d = (en - st) / 1000 } })
        end
        castNow = { k = ch and "h" or "c", s = math.floor(st + 0.5) / 1000, e = math.floor(en + 0.5) / 1000 }
        castEnd = nil
    elseif not castNow then
        return
    elseif event == "UNIT_SPELLCAST_INTERRUPTED" or event == "UNIT_SPELLCAST_FAILED" then
        castNow, castEnd = nil, { x = 0, t = now }
    elseif event == "UNIT_SPELLCAST_SUCCEEDED" and castNow.k == "c" then
        castNow, castEnd = nil, { x = 1, t = now }
    elseif event == "UNIT_SPELLCAST_STOP" or event == "UNIT_SPELLCAST_CHANNEL_STOP" then
        -- a channel ends with STOP whether it ran out or was cut; a cast's STOP after SUCCEEDED never gets here
        local done = now >= castNow.e - 0.15
        castNow, castEnd = nil, { x = done and 1 or 0, t = now }
    end
end

-- b-combat-bar-states: one SPELL's state, by id (an In-combat spell has no bar slot): the same fields a bar slot's state carries
local function spellState(id, hasTarget)
    local o = { i = id, ct = castMs(id) }
    local r = PCALL(api("C_Spell", "IsSpellUsable") or _G.IsUsableSpell, id)          -- isUsable, insufficientPower
    if r[1] then
        o.u = bit01(SV(r[2]) == true)
        o.m = bit01(SV(r[3]) == true)
    end
    if hasTarget then
        local v = S(api("C_Spell", "IsSpellInRange") or _G.IsSpellInRange, id, "target")
        if v == true or v == 1 then o.r = 1 elseif v == false or v == 0 then o.r = 0 end
    end
    o.chk = bit01(S(api("C_Spell", "IsCurrentSpell") or _G.IsCurrentSpell, id) == true
        or S(api("C_Spell", "IsAutoRepeatSpell") or _G.IsAutoRepeatSpell, id) == true)
    return o
end

local function buildState()
    local combat = S(InCombatLockdown) == true
    local hasTarget = S(UnitExists, "target") == true
    local layers = resolveAll(pageUnit())
    local L, slots = {}, {}
    for _, key in ipairs(STATE_LAYERS) do
        local list = {}
        for p = 1, 8 do
            local d = layers[key].d[p]
            list[p] = buttonState(d, combat, hasTarget)
            if d.k ~= "" and d.k ~= "fixed" and d.k ~= "pet" and (d.s or 0) > 0 then
                slots[#slots + 1] = d.s
            end
        end
        L[key] = list
    end
    stateSlots = slots
    local st = { c = combat, aa = autoAttack == true, ar = autoRepeat == true, L = L }
    if castNow then st.cs = castNow
    elseif castEnd and (SN(GetTime) or 0) - castEnd.t < 2 then
        st.ce = { x = castEnd.x, t = math.floor(castEnd.t * 1000 + 0.5) / 1000 }
    end
    if combat then
        local sk, sv = pickSwing(SN(GetTime) or 0)
        if sv then st.sw = { k = sk, s = math.floor(sv.s * 1000 + 0.5) / 1000, d = math.floor(sv.d * 100 + 0.5) / 100 } end
    end
    local bu = barsUsable()
    if bu ~= nil then st.bu = bit01(bu) end   -- 0 = a window owns the pad: draw collapsed, base layer only
    -- b-combat-bar-states (the user on 1.0.8-test2): the IN-COMBAT spells drawn over Home's bars in a fight are not bar slots, so the
    -- game's per-slot state never covered them. hudS.fs = their state by spell id, the bars' own fields: u usable, m not enough mana
    -- (the blue tint), r in range (with a target), chk selected for casting (waiting for a target / auto-repeat / next swing)
    local okf, fspells = pcall(function() return type(WC.padMapFightSpells) == "function" and WC.padMapFightSpells() or nil end)
    if okf and type(fspells) == "table" and #fspells > 0 then
        local fs = {}
        for _, id in ipairs(fspells) do fs[#fs + 1] = spellState(id, hasTarget) end
        st.fs = fs
    end
    -- hudS.F: the live look of the LIVE scripted buttons, keyed "<layer><p>" (n5, n6, fr6, fr7, ho6, ho7,
    -- sc6): e = enabled 0/1 (0 = draw it as the disabled / empty slot), i = the icon it shows now.
    local F = {}
    for _, key in ipairs(LAYERS) do
        for p = 1, 8 do
            local d = layers[key].d[p]
            if d.live then
                local o = { e = bit01(MS(d.btn, "IsEnabled") == true) }
                if d.live:find("i", 1, true) then o.i = liveIcon(d, hasTarget) end
                F[key .. p] = o
            elseif d.k == "macro" and (d.s or 0) > 0 then
                -- a MACRO's icon follows its conditionals ([combat], [mod], targets): #showtooltip macros flip
                -- when a fight starts (Spellbook's combat buttons, 2026-10-01). The layout's icon is the one at
                -- snapshot time, so the live one rides the state doc and is re-read on REGEN / UPDATE_MACROS.
                local ic = texVal(S(A("GetActionTexture"), d.s))
                if ic then F[key .. p] = { e = 1, i = ic } end
            end
        end
    end
    if next(F) ~= nil then st.F = F end
    return st
end

local lastStateKey, lastStateAt = nil, -1e9
local trailingArmed, forcePending, stateQueued = false, false, false
WC.hudStateEmits = 0

-- Build, diff and send the state doc. force = send even if unchanged (heartbeat recovery).
local function emitState(force)
    if not loggedIn then return false end
    local now = SN(GetTime) or 0
    local since = now - lastStateAt
    if since < STATE_WINDOW - 1e-6 then
        -- inside the window: never drop, never send early -- deliver at the window's end
        if force then forcePending = true end
        if not trailingArmed then
            trailingArmed = true
            C_Timer.After(STATE_WINDOW - since, function()
                trailingArmed = false
                local f = forcePending
                forcePending = false
                emitState(f)
            end)
        end
        return false
    end
    local ok, st = pcall(buildState)
    if not ok then noteError(st); return false end
    local key = WC.encode(st)                 -- g is added after, so the diff ignores the clock
    if key == lastStateKey and not force then return false end
    st.g = now
    lastStateKey, lastStateAt = key, now
    WC.hudStateEmits = WC.hudStateEmits + 1
    WC.send(WC.encode({ type = "full", v = WC.VERSION, actionbar = { hudS = st } }))
    WC.push()
    return true
end

-- Many events in one frame -> one build.
-- b-cast-bar-late (the user on 1.0.8-test2: "The cast bar starts really delayed at like 25%"): a cast's or channel's START goes out AT
-- ONCE, outside the 0.5 s state window (that window alone was a 2 s cast's first 25%); the bar's position is the game's own clock
-- (UnitCastingInfo's start / end), so it is right however late it lands. At most one extra send per cast.
local function emitCastNow()
    lastStateAt = -1e9
    emitState(true)
end

local function requestState()
    if stateQueued or not loggedIn then return end
    stateQueued = true
    C_Timer.After(0, function()
        stateQueued = false
        emitState(false)
    end)
end

---------------------------------------------------------------------------------------------------
-- collectActionBar: legacy rows + the hud layout (Core.lua's WC.snapshot calls this)
---------------------------------------------------------------------------------------------------

local lastHudJson = nil

function WC.collectActionBar()
    local slots = {}
    for i = 1, NUM_SLOTS do
        local r = slotInfo(i)
        if r then slots[#slots + 1] = r end
    end
    local ok, hud = pcall(buildHud)
    if not ok then
        noteError(hud)
        hud = { v = 1, ok = false }
    end
    local json = WC.encode(hud)
    if json ~= lastHudJson then
        lastHudJson = json
        requestState()          -- new layout -> the state must follow it
    end
    return {
        slots = slots,
        combat = S(InCombatLockdown),
        page = SN(GetActionBarPage),
        hud = hud,
    }
end

-- A layout event Core.lua does not know about: re-snapshot only if OUR layout changed, so that
-- legacy-row churn is not flushed on these events.
-- FLUSH BUDGET: WC.snapshot is a FULL Core snapshot (action bar + player + map + bags + equipped, then
-- a push), so at most ONE per LAYOUT_WINDOW from here; a change inside the window is re-checked when
-- the window ends (trailing edge: delivered late, never dropped).
local LAYOUT_WINDOW = 1.0
local lastLayoutSnapAt, layoutTrailing = -1e9, false
WC.hudLayoutSnaps = 0

local function layoutCheck()
    local ok, hud = pcall(buildHud)
    if not ok then noteError(hud); return end
    if WC.encode(hud) ~= lastHudJson and type(WC.snapshot) == "function" then
        local now = SN(GetTime) or 0
        local since = now - lastLayoutSnapAt
        if since < LAYOUT_WINDOW - 1e-6 then
            if not layoutTrailing then
                layoutTrailing = true
                C_Timer.After(LAYOUT_WINDOW - since, function()
                    layoutTrailing = false
                    layoutCheck()             -- rebuilt then: skipped if Core already carried it
                end)
            end
            return
        end
        lastLayoutSnapAt = now
        WC.hudLayoutSnaps = WC.hudLayoutSnaps + 1
        WC.snapshot("hud")      -- Core re-collects through collectActionBar, which records it
    end
    requestState()
end

local layoutQueued = false
-- Default delay 0.1 s: Blizzard updates the Interact icon with RunNextFrame and applies override
-- bars in its own handlers for the same events, so read a couple of frames later.
local function layoutSoon(delay)
    if layoutQueued or not loggedIn then return end
    layoutQueued = true
    C_Timer.After(delay or 0.1, function()
        layoutQueued = false
        layoutCheck()
    end)
end

-- The LIVE face looks (hudS.F) follow the target and the soft target. They are STATE (0.5 s budget),
-- never layout. Blizzard refreshes them a frame later (RunNextFrame in UpdateInteractIcons; the
-- targeting bars in their own handlers), so the state is read 0.1 s after the event.
local faceQueued = false
local function faceSoon()
    if faceQueued or not loggedIn then return end
    faceQueued = true
    C_Timer.After(0.1, function()
        faceQueued = false
        requestState()
    end)
end

---------------------------------------------------------------------------------------------------
-- HIDE / SHOW (the minimap-style contract: alpha only, our own flag, fail open on /reload)
---------------------------------------------------------------------------------------------------

local hookedShow, hookedPage, wrapped = false, false, false
local lastHookPage = nil

local function onShowHook(frame)
    if WC.hudHidden then setAlpha(frame, 0) end
end

local function onPageHook()
    local p = currentPage(pageUnit())
    if p ~= lastHookPage then          -- the special-page skip recurses: dedupe on the real page
        lastHookPage = p
        layoutSoon(0)
    end
end

local function installHooks()
    local mf = mainFrame()
    if mf == nil then return end
    if not hookedShow and S(InCombatLockdown) == false then
        hookedShow = MS(mf, "HookScript", "OnShow", onShowHook) ~= WC.THREW
    end
    if not hookedPage and type(hooksecurefunc) == "function" then
        local pu = pageUnit()
        if pu ~= nil and type(field(pu, "SetCurrentPage")) == "function" then
            -- never AddPageChangeCallback: Blizzard runs those inline and would run tainted
            hookedPage = (pcall(hooksecurefunc, pu, "SetCurrentPage", onPageHook))
            lastHookPage = currentPage(pu)
        end
    end
end

function WC.hudHide()
    WC.hudHidden = true                       -- flag FIRST: the OnShow hook reads it
    setAlpha(mainFrame(), 0)
    setAlpha(_G.GamepadPersistentInputLegend, 0)
    layoutSoon(0)
end

function WC.hudShow()
    WC.hudHidden = false                      -- flag FIRST, then restore
    setAlpha(mainFrame(), 1)
    setAlpha(_G.GamepadPersistentInputLegend, 1)
    layoutSoon(0)
end

local function reapplyAlpha()
    if WC.hudHidden then
        setAlpha(mainFrame(), 0)
        setAlpha(_G.GamepadPersistentInputLegend, 0)
    end
end

---------------------------------------------------------------------------------------------------
-- Keys: two plain addon buttons + override CLICK bindings, out of combat only
---------------------------------------------------------------------------------------------------

local ev = CreateFrame("Frame")
local hideBtn, showBtn = nil, nil

-- OFF unless WC.HUD_USE_KEYS is true. User ruling 2026-09-27 11:05: the shared TAP PAD is the one
-- app->addon channel for every room, so HUD hide/show will ride pad cells once the pad lives in the
-- core addon. The key route stays here, switched off, as the fallback. With it off hud.keys is absent
-- and the app sends nothing; /wchud hide|show still work by hand.
local function trySetupKeys()
    if WC.HUD_USE_KEYS ~= true then return end
    if keysBound then return end
    if S(InCombatLockdown) ~= false then return end        -- retried on PLAYER_REGEN_ENABLED
    if type(SetOverrideBindingClick) ~= "function" then return end
    if not hideBtn then
        hideBtn = CreateFrame("Button", "WowCompanionHudHide", UIParent)
        hideBtn:SetScript("OnClick", function() WC.hudHide() end)
        showBtn = CreateFrame("Button", "WowCompanionHudShow", UIParent)
        showBtn:SetScript("OnClick", function() WC.hudShow() end)
    end
    local ok1 = pcall(SetOverrideBindingClick, ev, true, KEY_HIDE, "WowCompanionHudHide")
    local ok2 = pcall(SetOverrideBindingClick, ev, true, KEY_SHOW, "WowCompanionHudShow")
    keysBound = ok1 and ok2
    if keysBound then layoutSoon(0) end
end

---------------------------------------------------------------------------------------------------
-- Range poll: only while a target exists; feeds the state diff
---------------------------------------------------------------------------------------------------

local rangeOn, rangeTicker, rangeSeen = false, nil, {}

local function stopRange()
    rangeOn = false
    if rangeTicker and type(rangeTicker.Cancel) == "function" then pcall(rangeTicker.Cancel, rangeTicker) end
    rangeTicker = nil
end

local function pollRange()
    if not rangeOn then return end
    if S(UnitExists, "target") ~= true then stopRange(); return end
    local fn = A("IsActionInRange")
    local changed = false
    for _, s in ipairs(stateSlots) do
        local v = S(fn, s)
        local r = (v == true and 1) or (v == false and 0) or -1
        if rangeSeen[s] ~= r then
            rangeSeen[s] = r
            changed = true
        end
    end
    if changed then requestState() end
end

local function updateRangePoll()
    local has = S(UnitExists, "target") == true
    if has and not rangeOn then
        rangeOn, rangeSeen = true, {}
        rangeTicker = C_Timer.NewTicker(0.25, pollRange)
    elseif not has and rangeOn then
        stopRange()
    end
end

---------------------------------------------------------------------------------------------------
-- Probe: one read-only cap that settles the numbers the replica depends on
---------------------------------------------------------------------------------------------------

local PROBE_CMDS = {
    "GAMEPADACTIONBUTTON1", "GAMEPADACTIONBUTTON2", "GAMEPADACTIONBUTTON3", "GAMEPADACTIONBUTTON4",
    "GAMEPADACTIONBUTTON5", "GAMEPADACTIONBUTTON6", "GAMEPADACTIONBUTTON7", "GAMEPADACTIONBUTTON8",
    "GAMEPADLEFTMOD", "GAMEPADRIGHTMOD", "GAMEPADLEFTTARGETMODIFIER", "GAMEPADRIGHTTARGETMODIFIER",
}

local function protOf(obj)
    local r = PCALL(field(obj, "IsProtected"), obj)
    return { ok = r[1] == true, p = SV(r[2]) == true, x = SV(r[3]) == true }
end

-- isPlain of every field of an API-returned table
local function plainFields(t)
    local out = {}
    pcall(function()
        for k, v in pairs(t) do
            local ks = realStr(SV(k))
            if ks then out[ks] = (WC.isPlain(v)) == true end
        end
    end)
    return out
end

function WC.hudProbe()
    local p = { v = 1 }
    local C = field(_G.Constants, "GamepadActionBarConstants")
    if type(C) == "table" then
        local c = {}
        pcall(function()
            for k, v in pairs(C) do
                local ks = realStr(SV(k))
                if ks then c[ks] = SV(v) end
            end
        end)
        p.const = c
    end
    p.first = SN(api("C_GamepadUI", "GetFirstGamepadActionStorageSlotIndex"))
    p.firstPet = SN(api("C_GamepadUI", "GetFirstGamepadPetActionStorageSlotIndex"))
    p.firstStance = SN(api("C_GamepadUI", "GetFirstGamepadActionBarStorageSlotIndexForActiveStance"))
    p.gpUI = S(function() return InputUtil.IsGamepadUIEnabled() end)

    local mf = mainFrame()
    p.prot = protOf(mf)
    local pu = pageUnit()
    local layers = resolveAll(pu)
    local b1 = layers.n.d[1].btn
    p.protBtn = protOf(b1)
    -- .action vs GetID vs the storage formula, for the top D-pad-left button (F24 item 14)
    if b1 ~= nil then
        local page = currentPage(pu)
        local util = _G.GamepadActionBarBindingUtil
        p.act = {
            a = num(S(function() return b1.action end)),
            id = num(MS(b1, "GetID")),
            fm = page and num(S(field(util, "GetGamepadStorageSlotIndexFromPageAndPageUnitSlotID"), page, 1)) or nil,
        }
    end

    -- ALL returns of GetBindingKey(cmd, true), holes kept: n = count, kI = the I-th key
    p.binds = {}
    for _, cmd in ipairs(PROBE_CMDS) do
        local r = PCALL(_G.GetBindingKey, cmd, true)
        local b = { ok = r[1] == true }
        if r[1] then
            b.n = r.n - 1
            for i = 2, r.n do
                local k = realStr(SV(r[i]))
                if k then b["k" .. (i - 1)] = k end
            end
        end
        p.binds[cmd] = b
    end

    -- three filled slots: which cooldown / charge fields and item counts are plain right now
    p.slots = {}
    p.combat = S(InCombatLockdown) == true
    for _, key in ipairs(STATE_LAYERS) do
        for i = 1, 8 do
            local d = layers[key].d[i]
            if #p.slots < 3 and d.k ~= "" and d.k ~= "fixed" and d.k ~= "pet" and (d.s or 0) > 0 then
                local e = { s = d.s, k = d.k, layer = key, pos = i }
                local cd = cooldownOf(d.s)
                if cd then
                    e.cd = plainFields(cd)
                    -- device check: does isOnGCD read nil OUTSIDE SPELL_UPDATE_COOLDOWN on this build?
                    e.gcdNil = tfield(cd, "isOnGCD") == WC.ABSENT
                end
                local ch = chargesOf(d.s)
                if ch then e.ch = plainFields(ch) end
                if d.k == "item" and d.id then
                    local r = PCALL(api("C_Item", "GetItemCount"), d.id)
                    e.cnt = r[1] == true and (WC.isPlain(r[2])) == true
                end
                p.slots[#p.slots + 1] = e
            end
        end
    end
    p.bu = barsUsable(); p.buSrc = WC.hudBuSource        -- which signal drives the collapse
    WC.emit({ type = "cap", hudProbe = p })
    return p
end

---------------------------------------------------------------------------------------------------
-- Events
---------------------------------------------------------------------------------------------------

local STATE_EVENTS = {
    "ACTION_USABLE_CHANGED", "ACTIONBAR_UPDATE_USABLE", "ACTIONBAR_UPDATE_COOLDOWN",
    "SPELL_UPDATE_COOLDOWN", "SPELL_UPDATE_CHARGES",
    "SPELL_ACTIVATION_OVERLAY_GLOW_SHOW", "SPELL_ACTIVATION_OVERLAY_GLOW_HIDE",
    "ACTION_RANGE_CHECK_UPDATE", "PLAYER_TARGET_CHANGED",
    "PLAYER_ENTER_COMBAT", "PLAYER_LEAVE_COMBAT", "PLAYER_REGEN_DISABLED", "PLAYER_REGEN_ENABLED",
    "BAG_UPDATE_DELAYED", "START_AUTOREPEAT_SPELL", "STOP_AUTOREPEAT_SPELL", "UPDATE_MACROS", "PLAYER_SWING",
    -- b-combat-bar-states: a spell waiting for its target (selected for casting) and the In-combat spells' usable / mana
    "CURRENT_SPELL_CAST_CHANGED", "SPELL_UPDATE_USABLE",
}
local UNIT_EVENTS = { "UNIT_SPELLCAST_START", "UNIT_SPELLCAST_STOP", "UNIT_SPELLCAST_SUCCEEDED",
    "UNIT_SPELLCAST_INTERRUPTED", "UNIT_SPELLCAST_FAILED", "UNIT_SPELLCAST_DELAYED",
    "UNIT_SPELLCAST_CHANNEL_START", "UNIT_SPELLCAST_CHANNEL_UPDATE", "UNIT_SPELLCAST_CHANNEL_STOP" }
local LAYOUT_EVENTS = {
    "UPDATE_BONUS_ACTIONBAR", "GAMEPAD_STANCE_BAR_OVERRIDE_CHANGED", "GAMEPAD_POSSESS_BAR_OVERRIDE_CHANGED",
    "PET_BAR_UPDATE", "CVAR_UPDATE", "SPELLS_CHANGED",
    "UNIT_PET", "SPELL_FLYOUT_UPDATE", "LEARNED_SPELL_IN_SKILL_LINE",   -- class-action radials (hud.ca)
    "UPDATE_SHAPESHIFT_FORM",                                            -- f-chord-icons: the form / stance / aura on now
}
-- face icons (hudS.F): state, read a frame after Blizzard updates them. PLAYER_TARGET_CHANGED (registered
-- in STATE_EVENTS) takes the same faceSoon path; it is NOT a layout trigger.
local FACE_EVENTS = { "PLAYER_SOFT_INTERACT_CHANGED", "PLAYER_SOFT_ENEMY_CHANGED" }
local OTHER_EVENTS = { "PLAYER_LOGIN", "PLAYER_ENTERING_WORLD", "ADDON_ACTION_BLOCKED", "ADDON_ACTION_FORBIDDEN" }

local IS_STATE, IS_LAYOUT, IS_UNIT, IS_FACE = {}, {}, {}, {}
local missing = {}

-- RegisterEvent returns a boolean on this client: a name this build lacks is reported, not guessed.
local function reg(e)
    local ok, r = pcall(function() return ev:RegisterEvent(e) end)
    if not ok or r == false then
        missing[#missing + 1] = e
        return false
    end
    return true
end

for _, e in ipairs(STATE_EVENTS) do IS_STATE[e] = true; reg(e) end
for _, e in ipairs(LAYOUT_EVENTS) do IS_LAYOUT[e] = true; reg(e) end
for _, e in ipairs(FACE_EVENTS) do IS_FACE[e] = true; reg(e) end
for _, e in ipairs(OTHER_EVENTS) do reg(e) end
for _, e in ipairs(UNIT_EVENTS) do
    IS_UNIT[e] = true
    local ok, r = pcall(function() return ev:RegisterUnitEvent(e, "player") end)
    if not ok or r == false then reg(e) end     -- plain registration; the handler filters the unit
end

local blockedN = 0
local function onBlocked(event, who, fn)
    if SV(who) ~= ADDON then return end
    blockedN = blockedN + 1
    if blockedN > 20 then return end            -- a storm must not become a flush storm
    WC.emit({ type = "cap", hudBlocked = {
        ev = (event == "ADDON_ACTION_BLOCKED") and "blocked" or "forbidden",
        fn = realStr(SV(fn)),
        n = blockedN,
    } })
end

-- Core.lua's WC.snapshot is defined AFTER this file loads: wrap it at login, once, so the 15 s
-- heartbeat also re-sends the full state (recovery for an app that lost the stream).
local function wrapHeartbeat()
    if wrapped or type(WC.snapshot) ~= "function" then return end
    wrapped = true
    local orig = WC.snapshot
    WC.snapshot = function(reason, ...)
        local r = orig(reason, ...)
        if reason == "heartbeat" or reason == "manual" then emitState(true) end
        return r
    end
end

-- Poll barsUsable (one read-only method call) every 0.1 s; a change asks for a state doc, which the 0.5 s
-- budget still paces. Opening and closing a window are rare, so this costs flushes only when it happens.
local lastBU, buTicker = nil, nil
local function startBarsPoll()
    if buTicker or type(C_Timer.NewTicker) ~= "function" then return end
    lastBU = barsUsable()
    buTicker = C_Timer.NewTicker(0.1, function()
        local bu = barsUsable()
        if bu ~= lastBU then lastBU = bu; requestState() end
    end)
end

local function onLogin()
    if loggedIn then return end
    loggedIn = true
    startBarsPoll()
    wrapHeartbeat()
    installHooks()
    trySetupKeys()
    updateRangePoll()
    if #missing > 0 then WC.emit({ type = "cap", hudMissingEvents = missing }) end
    C_Timer.After(2, function() pcall(WC.hudProbe) end)   -- the login probe
    requestState()
end

ev:SetScript("OnEvent", function(_, event, a1, a2)
    if event == "PLAYER_LOGIN" then
        onLogin()
    elseif event == "PLAYER_ENTERING_WORLD" then
        onLogin()
        reapplyAlpha()                  -- /reload and relog recreate the frame at alpha 1
        installHooks()
        trySetupKeys()
        updateRangePoll()
        layoutSoon()
        requestState()
    elseif event == "ADDON_ACTION_BLOCKED" or event == "ADDON_ACTION_FORBIDDEN" then
        onBlocked(event, a1, a2)
    elseif event == "PLAYER_REGEN_ENABLED" then
        installHooks()
        trySetupKeys()
        requestState()
        layoutSoon()                    -- f-class-radial: a radial that changed in the fight gets its touch cells now
    elseif event == "START_AUTOREPEAT_SPELL" then
        autoRepeat = true
        requestState()
    elseif event == "STOP_AUTOREPEAT_SPELL" then
        autoRepeat = false
        requestState()
    elseif event == "PLAYER_SWING" then
        noteSwing(a1, a2)
        requestState()
    elseif event == "PLAYER_ENTER_COMBAT" then
        autoAttack = true
        requestState()
    elseif event == "PLAYER_LEAVE_COMBAT" then
        autoAttack = false
        requestState()
    elseif event == "SPELL_UPDATE_COOLDOWN" then
        local ok, err = pcall(sampleGCD)    -- isOnGCD is only trustworthy HERE, synchronously
        if not ok then noteError(err) end
        requestState()
    elseif event == "PLAYER_TARGET_CHANGED" then
        updateRangePoll()
        faceSoon()                      -- range + the face icons (hudS.F), one read a frame later
    elseif IS_FACE[event] then
        faceSoon()
    elseif event == "SPELL_ACTIVATION_OVERLAY_GLOW_SHOW" or event == "SPELL_ACTIVATION_OVERLAY_GLOW_HIDE" then
        local id = SN(function() return a1 end)
        if id then
            if event == "SPELL_ACTIVATION_OVERLAY_GLOW_SHOW" then glowing[id] = true else glowing[id] = nil end
        end
        requestState()
    elseif event == "CVAR_UPDATE" then
        local name = realStr(SV(a1))
        if name and name:sub(1, 7):lower() == "gamepad" then layoutSoon() end
    elseif IS_UNIT[event] then
        if SV(a1) == "player" then
            local hadEnd = castEnd
            local ok, err = pcall(noteCast, event)
            if not ok then noteError(err) end
            if castNow and (event == "UNIT_SPELLCAST_START" or event == "UNIT_SPELLCAST_CHANNEL_START") then emitCastNow()
            else requestState() end
            -- the end mark (hudS.ce) lives 2 s: one more state doc then drops it
            if castEnd and castEnd ~= hadEnd then C_Timer.After(2.05, requestState) end
        end
    elseif IS_LAYOUT[event] then
        layoutSoon()
    elseif IS_STATE[event] then
        requestState()
    end
end)

---------------------------------------------------------------------------------------------------
-- /wchud  -- report | hide | show | probe
---------------------------------------------------------------------------------------------------

SLASH_WCHUD1 = "/wchud"
SlashCmdList["WCHUD"] = function(arg)
    arg = (arg or ""):lower():gsub("^%s+", ""):gsub("%s+$", "")
    if arg == "hide" then
        WC.hudHide()
        say("controller HUD hidden (alpha 0)")
        return
    elseif arg == "show" then
        WC.hudShow()
        say("controller HUD shown")
        return
    elseif arg == "probe" then
        local ok = pcall(WC.hudProbe)
        say(ok and "hud probe sent" or "|cffff5555hud probe failed|r")
        return
    end
    local mf = mainFrame()
    local rep = {
        -- p-bars-grey (a druid: "always grey"): the controller HUD is live when its main frame shows OR its page unit has an active bar
        -- (a form's / stance's bar can stand in for the main frame); the app greys the bar only for a dead stream or no bar at all
        ok = (mf ~= nil and MS(mf, "IsShown") == true) or MS(pageUnit(), "GetActiveBar") ~= nil,
        exists = mf ~= nil,
        alpha = num(MS(mf, "GetAlpha")),
        hidden = WC.hudHidden == true,
        page = currentPage(pageUnit()),
        keys = keysBound,
        showHook = hookedShow,
        pageHook = hookedPage,
        beat = wrapped,
        range = rangeOn,
        missing = missing,
        blocked = blockedN,
        states = WC.hudStateEmits,
        layouts = WC.hudLayoutSnaps,
        errors = WC.hudErrors,
    }
    WC.emit({ type = "cap", hudReport = rep })
    say("hud ok=" .. tostring(rep.ok) .. " hidden=" .. tostring(rep.hidden) .. " page=" .. tostring(rep.page)
        .. " keys=" .. tostring(rep.keys) .. " hooks=" .. tostring(rep.showHook) .. "/" .. tostring(rep.pageHook)
        .. " states=" .. tostring(rep.states) .. " errors=" .. tostring(rep.errors))
    say("|cffffd100/wchud hide|r  |cffffd100/wchud show|r  |cffffd100/wchud probe|r")
end

---------------------------------------------------------------------------------------------------

function WC.collectPlayer()
    local _, class = CALL(UnitClass, "player")
    return {
        name  = S(UnitName, "player"),
        level = SN(UnitLevel, "player"),
        class = S(UnitClass, "player"),
        -- current health/power are PERMANENTLY secret on this build, in and out of combat.
        -- They are deliberately NOT on the wire: sending a constant "?" would put a permanent
        -- nonzero floor in the secret counter and hide a real change if the map ever shifts.
        hpMax = SN(UnitHealthMax, "player"),
        powerMax = SN(UnitPowerMax, "player"),
        zone = S(GetZoneText),
        subzone = S(GetSubZoneText),
        realm = S(GetRealmName),
        money = SN(GetMoney),
    }
end
