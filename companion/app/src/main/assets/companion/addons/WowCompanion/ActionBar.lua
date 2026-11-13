-- WowCompanion / ActionBar.lua
-- nbar1 (issue #195 "Adaptative layout for action bars port to main screen", the user 2026-11-12: a "Nintendo" choice next to On / Off /
-- Dynamic for the top screen's action bar, DS Forever's Nintendo-layout bar REPLACING the stock one in the same spot, the live layer
-- only, cooldown + charge sweeps by the duration-object route with a dim fallback): WC.nbar / WC.nbarSet, at "THE NINTENDO BAR" below.
-- nbar2 (review of nbar1, issue #195: "exactly what a press casts"): a form's own In-combat spell first in a fight (PadMap m55 formFight);
-- the fight pictures only while Combat controls are on (WC.padMap.combatOn) and on the ROUTER's own switch (WC.tgGrown: the fight or an
-- attack / auto-repeat armed, not given back by B - PadMap's "sw"); commands and bindings on the bare D-pad drawn; the glyphs by the
-- button's own letter (PadMap m119 / m120, the user: "the inspect glyph is Y not X"; the app's rev_* art) in ONE helper Hub shares
-- (WC.padGlyphAtlas), the faces at the ABXY location (swap: Y left, X top, A right, B bottom, as CastView); an atlas this client refuses
-- falls to the next / the letter (WC.prd.atlasOn); ours follows the stock bar's visibility and scale (UI_SCALE_CHANGED /
-- DISPLAY_SIZE_CHANGED, a window taking the pad) and keeps its last spot when a fight hides the centre.
-- cb1 (GitHub #43, the tap-to-cast bar): one secure pad row "castbar" (12 spell cells + the Spellbook's pick), the character's own
-- slots (WowCompanionSpellsDB.castBar[name-realm]), a fight's edits queued to its end; documents "castbar" / "castcd". At "THE TAP-TO-CAST BAR".
-- cb2 (1.0.30 code 1454): the row "castbar" only once the character's bar has a spell (bmv4 kept on a full pad); the Spellbook's pick on padtab 39 + 19.
-- cb2b (review, code 1455): a castbar row registered mid-session asks for the quests document again (WC.questsSoon, Quests q59): Home's pad.
-- cb3 (the user, 2026-11-13: "the buttons inside of it are too small ... the bar itself should have 6 slots for abilities ... to assign to
-- the bar, in the bind menu, where there is the L and R tabs, we will add a third tab"): SIX slots (the row: 6 cells + the pick cell 6),
-- an old 12-slot store keeps its first 6 spells; the pick may name a slot (padtab 39 then 20-25, Book b56): that spell goes THERE.
-- cb6 EIGHT SLOTS (the user, 2026-11-13: "only see 6 slots instead of 8 when using both, I think that you misunderstood and kept 6 total
-- slots on both side even with the slide for more."; the pick: "8 total, 4 per side"): the row is 8 cells + the pick cell 8; a 6-slot
-- store keeps its spells as they are (slots 7-8 empty), an old 12-slot one its first 8; slots 7-8 by padtab 39 then 27-28 (Book b57).
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

-- m75 (Spells' PadMap; the user: "Some spells on my bar are still behaving weird in combat, not showing when I don't have enough mana"):
-- the game's own "not enough mana" can read SECRET in a fight; then the mana estimate (WC.vitals: plain or Smart Y's model) against
-- the spell's mana cost decides. -> true / false, or nil (no answer)
local function manaShort(id)
    if type(id) ~= "number" or type(WC.vitals) ~= "function" then return nil end
    local okv, v = pcall(WC.vitals)
    if not okv or type(v) ~= "table" or type(v.mp) ~= "number" or v.mpSrc == "none" then return nil end
    local okc, costs = pcall(function() return C_Spell.GetSpellPowerCost(id) end)
    if not okc or type(costs) ~= "table" then return nil end
    for _, c in ipairs(costs) do
        local okt, ty = pcall(function() return c.type end)
        local okn, cost = pcall(function() return c.cost end)
        if okt and ty == 0 and okn and type(cost) == "number" and WC.isPlain(cost) then return cost > v.mp end
    end
    return nil
end
-- the game's usable / not-enough-mana pair, each plain or not: a secret "usable" counts as usable, a secret mana flag asks [manaShort]
local function usableMana(o, u, m, id)
    if u == true or u == false then o.u = bit01(u) else o.u = bit01(u == WC.SECRET) end      -- nil / absent: unusable, as before
    if m == true or m == false then o.m = bit01(m)
    elseif m == WC.SECRET or m == nil then o.m = bit01(manaShort(id) == true)
    else o.m = 0 end
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
            if type(WC.petTok) == "table" and WC.petTok.isToken(SV(r[4]), name) then   -- token: both are keys into _G (b-pet-tokens)
                b.n, b.i, b.id = WC.petTok.name(name), WC.petTok.icon(tex) or texVal(tex), nil
            else
                b.n, b.i, b.id = name, texVal(tex), num(SV(r[8]))
            end
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
                        PET_MODE_DEFENSIVE = 6, PET_MODE_DEFENSIVEASSIST = 6, PET_MODE_ASSIST = 7 }
-- b-pet-tokens (the user's hunter, 2026-11-10: "The class bars for both triggers are both blank ... the right one has lit circles with
-- like syntax"; the radial document: every command came as a pet SPELL named "PET_ACTION_ATTACK", icon "tex:PET_ATTACK_TEXTURE"):
-- this client does not answer GetPetActionInfo's isToken as true, so a command is known by its NAME too - a PET_ACTION_ / PET_MODE_
-- key. Its icon: the game's texture global (blizzard_actionbar/shared/petactionbar.lua), as a file ID from the listfile (the class
-- radial draws file IDs only), its name the game's string for the key
local PET_TEX_FID = { PET_ATTACK_TEXTURE = 132152, PET_FOLLOW_TEXTURE = 132328, PET_MOVE_TO_TEXTURE = 457329,
                      PET_PASSIVE_TEXTURE = 132311, PET_DEFENSIVE_TEXTURE = 132110, PET_DEFENSIVEASSIST_TEXTURE = 132110,
                      PET_AGGRESSIVE_TEXTURE = 132277, PET_ASSIST_TEXTURE = 524348, PET_WAIT_TEXTURE = 136106,
                      PET_DISMISS_TEXTURE = 136222 }
local PET_TOK_NAME = { PET_ACTION_ATTACK = "Attack", PET_ACTION_FOLLOW = "Follow", PET_ACTION_MOVE_TO = "Move To",
                       PET_ACTION_WAIT = "Stay", PET_ACTION_DISMISS = "Dismiss", PET_MODE_PASSIVE = "Passive",
                       PET_MODE_DEFENSIVE = "Defensive", PET_MODE_DEFENSIVEASSIST = "Defensive", PET_MODE_AGGRESSIVE = "Aggressive",
                       PET_MODE_ASSIST = "Assist" }
local function petIsToken(flag, name)
    if flag == true then return true end
    local n = realStr(name)
    return n ~= nil and (n:find("^PET_ACTION_") ~= nil or n:find("^PET_MODE_") ~= nil)
end
local function petTokName(name)
    local n = realStr(name)
    if not n then return nil end
    return realStr(S(function() return _G[n] end)) or PET_TOK_NAME[n] or n
end
local function petTokIcon(tex)
    local k = realStr(tex)
    if not k then return nil end
    if PET_TEX_FID[k] then return PET_TEX_FID[k] end
    local path = realStr(S(function() return _G[k] end))
    return path and iconOfPath(path) or nil
end
WC.petTok = { isToken = petIsToken, name = petTokName, icon = petTokIcon }

local function petEntries(tokens)
    local list = {}
    local n = num(rawget(_G, "NUM_PET_ACTION_SLOTS")) or 10
    for i = 1, n do
        local r = pack(CALL(GetPetActionInfo, i))
        -- name, texture, isToken, isActive, autoCastAllowed, autoCastEnabled, spellID
        local name, tex, active = r[2], r[3], r[5] == true
        local isToken = petIsToken(SV(r[4]), name)       -- b-pet-tokens
        local place = tokens and PET_CMD_PLACE[realStr(name) or ""] or (not tokens and #list < 8 and #list + 1) or nil
        if r[1] and name ~= nil and isToken == tokens and place then
            local e = { k = tokens and "petcmd" or "petspell", slot = i, act = bit01(active), p = place }
            if tokens then
                -- a token's name and texture are GLOBAL STRING NAMES (PET_ACTION_ATTACK -> "Attack")
                e.n = petTokName(name)
                e.i = petTokIcon(tex)
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

-- b-game-wheel-shut (the user's hunter, 2026-11-10: "lb+lt is still taking my control away from my right stick", then "I dont want to
-- compromise. do what we need to do so that it works how I want"): the chord that opens OUR class wheel also opens the GAME'S own
-- (blizzard_gamepadactionbars classactionflyout.lua: the class button's OnUpdate clicks itself while shoulder + trigger are down), and
-- that one is a TOGGLE (petactionflyout.lua HandleFlyoutClickOverride: TogglePopup on every press): it stays open after the chord is let
-- go and its binding group (flyoutradial.lua: GAMEPAD_STICK_RIGHT) keeps the right stick until the chord is pressed again. Closing it
-- from add-on code would move the game's protected buttons (blocked in a fight, a taint outside one). Hiding the wheel's frame IS the
-- game's own supported route (flyoutradial.lua: the class button, parented to the wheel while it is open, gets OnHide -> ClosePopup ->
-- the button back where it was, the stick binding off), and a SECURE SNIPPET may hide a protected frame in a fight too. So: one secure
-- handler of ours whose click hides the game's three gamepad wheels, reached by a "click" cell on this row (col GF_COL), which the app
-- taps when its class wheel opens and when it closes. While a class chord is held the game's wheel is also drawn at alpha 0 (a
-- post-hook on its Show: alpha only), so it never shows on the top screen in the moment before the tap lands.
local GF_COL = 24
local GF_WHEELS = { "GamepadPetActionFlyout", "GamepadPetSpellFlyout", "GamepadSpellFlyout" }
local GF = { btn = nil, refs = 0, hooked = {} }
local function gfChordHeld()
    local function down(k) return type(k) == "string" and S(function() return IsKeyDown(k) end) == true end
    local L, LT = rawget(_G, "GAMEPAD_SHOULDER_LEFT") or "PADLSHOULDER", rawget(_G, "GAMEPAD_TRIGGER_LEFT") or "PADLTRIGGER"
    local R, RT = rawget(_G, "GAMEPAD_SHOULDER_RIGHT") or "PADRSHOULDER", rawget(_G, "GAMEPAD_TRIGGER_RIGHT") or "PADRTRIGGER"
    return (down(L) and down(LT)) or (down(R) and down(RT))
end
local function gfSetup()
    if S(InCombatLockdown) ~= false then return GF.btn end
    if not GF.btn then
        local ok, b = pcall(CreateFrame, "Button", "WowCompanionGameWheelShut", UIParent, "SecureHandlerClickTemplate")
        if not ok or not b then return nil end
        -- no f-u-n-c-t-i-o-n word in the snippet (the secure checker refuses it, even in a comment: PadMap m94)
        b:SetAttribute("_onclick", [=[
            for i = 1, 3 do
                local w = self:GetFrameRef("wheel" .. i)
                if w and w:IsShown() then w:Hide() end
            end
        ]=])
        GF.btn = b
    end
    for i, n in ipairs(GF_WHEELS) do
        local w = rawget(_G, n)
        if type(w) == "table" and GF.refs < 3 and not GF.hooked[n] then
            local setRef = rawget(_G, "SecureHandlerSetFrameRef")
            local okRef = (type(setRef) == "function" and pcall(setRef, GF.btn, "wheel" .. i, w))
                or (type(GF.btn.SetFrameRef) == "function" and pcall(GF.btn.SetFrameRef, GF.btn, "wheel" .. i, w))
            if okRef then GF.refs = GF.refs + 1 end
            GF.hooked[n] = pcall(hooksecurefunc, w, "Show", function(self)
                if WC.classWheelOn and gfChordHeld() then pcall(self.SetAlpha, self, 0) else pcall(self.SetAlpha, self, 1) end
            end)
        end
    end
    return GF.btn
end
WC.gameWheelShut = { col = GF_COL, setup = gfSetup }
local function crInit(btn, col)
    local w = CR.want[col]
    btn:SetAttribute("type", w and w.type or nil)
    btn:SetAttribute("spell", w and w.spell or nil)
    btn:SetAttribute("action", w and w.action or nil)
    btn:SetAttribute("macrotext", w and w.macrotext or nil)
    btn:SetAttribute("clickbutton", w and w.clickbutton or nil)
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
    -- b-game-wheel-shut: the cell that hides the game's own wheels (only while a class wheel of ours exists)
    WC.classWheelOn = (ca and (ca.l or ca.r)) and true or false
    local gfb = WC.classWheelOn and gfSetup() or nil
    if gfb then want[GF_COL] = { type = "click", clickbutton = gfb, sig = "gf" .. GF.refs }; parts[#parts + 1] = GF_COL .. "=gf" .. GF.refs end
    local sig = table.concat(parts, ",")
    local pad = WC.pad
    if sig ~= CR.sig and S(InCombatLockdown) == false and type(pad) == "table" and type(pad.secureRow) == "function" then
        CR.want = want
        local ok, row = pcall(pad.secureRow, "classr", 25, crInit, crClick)
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
    -- b-game-wheel-shut: the app's tap cell, only while it is built as the shut
    if ca and want[GF_COL] and CR.frozen[GF_COL] == want[GF_COL].sig then ca.gf = GF_COL end
end

-- CASTBAR-BEGIN (cb1; tools/test_cast_bar.py runs this block on its own: it uses only WC's readers and the game's globals)
-- cb1 THE TAP-TO-CAST BAR (GitHub #43, "a dedicated cast bar on the companion (bottom) screen"; E:/wow-build/feature_notes_1113.txt, the
-- recommended picks: on Home above the bottom bar, 12 slots in 2 rows of 6, spells only, a fight's edits queued and the slot dimmed).
-- ONE secure pad row "castbar", 13 cells, next to "classr" and built the same way: cells 0-11 = slots 1-12, each the game's own cast
-- (type spell, the spell's id) on OUR OWN button - the player's bars, macros and bindings are never touched (leave no trace), no game
-- window is opened or closed. Set out of combat only, frozen in a fight as every secure cell. Cell 12 = the Spellbook's "Cast bar" pick
-- (no action, fires on the press): the spell's two taps on the spells row that follow within CB_PICK_S put that spell on the first free
-- slot, or take it off when it is already there (Book b53 hands the spell to WC.castBarSpell).
-- THE STORE is the character's (the per-character ruling): WowCompanionSpellsDB.castBar[name-realm] = { [slot] = spellID }. An edit made
-- in a fight waits in CB.queue until PLAYER_REGEN_ENABLED; the slot it changes is sent dimmed (q) and with no cell meanwhile.
-- THE DOCUMENTS: "castbar" (the slots: s, id, i icon, c the cell while it is built to that spell, q dimmed; pick = cell 12) on every
-- change; "castcd" (facecd's shape: k = { [slot] = { d, l, g, e } }) for the running cooldowns. The cooldown reads ONLY what the game
-- leaves plain: C_Spell.GetSpellCooldown's isActive / isOnGCD (never secret), inside SPELL_UPDATE_COOLDOWN (the one time isOnGCD is
-- trusted); its length only when it reads plainly (out of a fight, then learned), else the learned one, else 1.5 s for the GCD. A secret
-- value is never compared, indexed or kept (S / SN answer a sentinel / nil for it).
-- cb2 THE LAZY ROW (1.0.30 code 1454): TapPad's 56 rows are over-subscribed, and cb1's row (registered at the login, even empty) pushed
-- the 4th bag's drag row bmv4 off the pad for a player with every bag and the reagent bag. So the row "castbar" is registered only
-- once this character's bar has a spell (the login with stored slots, or the first edit, out of combat); an empty bar holds no row,
-- and the row allocation is cb1's predecessor's. TapPad has no way to give a row back, so a bar emptied later keeps its (empty) row
-- until the next /reload or login. THE SPELLBOOK'S PICK no longer needs the row: Book b55 takes it on the padtab row, Smart Range's
-- one-shot pick (padtab 39) then code 19 (WC.castBarPick); the document says so (pp = { 39, 19 }). The row's own pick cell stays.
-- cb3 SIX SLOTS (the user, 2026-11-13: "the buttons inside of it are too small, so I want to propose this- for options to turn it on,
-- left right or both ... the bar itself should have 6 slots for abilities"; the pick: 6 abilities in all): cells 0-5 = slots 1-6, cell 6
-- the pick. A store from cb1 / cb2 (12 slots) keeps its first 6 spells, in slot order, on slots 1-6 (cbMigrate, at the login, once).
-- THE SLOT PICK (the Bind box's third tab, Book b56): padtab 39 then 20-25 = WC.castBarPick(1-6); the spell then goes on THAT slot
-- (moved off another slot it was on; the same spell on that slot comes off), the pick without a slot (19) keeps the first-free toggle.
-- cb6 EIGHT SLOTS (the user, 2026-11-13: "only see 6 slots instead of 8 when using both ..."; the pick: "8 total, 4 per side"): cells
-- 0-7 = slots 1-8, cell 8 the pick. A cb3 store (6 slots) needs nothing (slots 7-8 start empty); a cb1 / cb2 store (12) keeps its first
-- 8 spells (cbMigrate). The slot pick: padtab 39 then 20-25 = slots 1-6, then 27-28 = slots 7-8 (26 is the Bind box's tab; Book b57).
do
local CBV = "cb6"
local CB_PADTAB_PICK = { 39, 19 }        -- cb2: padtab 39 (the one-shot pick) then 19 (its cast bar code): Book b55 -> WC.castBarPick
local CB_N, CB_PICK_COL, CB_PICK_S = 8, 8, 2   -- cb6: 8 slots (cells 0-7), the pick cell 8
local CB_OLD_N = 12                      -- cb3: the slots a cb1 / cb2 store may hold (cb6: migrated to the first 8)
local S, SN, CALL = WC.S, WC.SN, WC.CALL
local CB = { want = {}, frozen = {}, row = nil, sig = nil, pick = nil, queue = {}, ready = false, cd = {}, learned = {}, last = nil }
WC.castBar = CB
local function cbReal(v) return v ~= nil and v ~= WC.ABSENT and v ~= WC.SECRET and v ~= WC.THREW end
local function cbStr(fn) local v = S(fn); if type(v) == "string" and cbReal(v) and v ~= "" then return v end end
local function cbFight() return S(InCombatLockdown) ~= false end           -- unknown counts as a fight (nothing secure is touched)
local function cbNow() return SN(GetTime) or 0 end
local function cbSpellApi(name) local t = rawget(_G, "C_Spell"); return type(t) == "table" and t[name] or nil end

-- the character: name-realm (every per-character store's key)
local function cbWho()
    local n = cbStr(function() return UnitName("player") end)
    if not n then return nil end
    return n .. "-" .. (cbStr(function() return GetRealmName() end) or "?")
end
-- this character's slots table; create = make it (only after the login: the saved table is loaded by then)
local function cbStore(create)
    local key = cbWho()
    if not key then return nil end
    local db = rawget(_G, "WowCompanionSpellsDB")
    if type(db) ~= "table" then
        if not (create and CB.ready) then return nil end
        db = {}
        _G.WowCompanionSpellsDB = db
    end
    if type(db.castBar) ~= "table" then
        if not create then return nil end
        db.castBar = {}
    end
    local s = db.castBar[key]
    if type(s) ~= "table" then
        if not create then return nil end
        s = {}
        db.castBar[key] = s
    end
    return s
end
-- cb3: an old store (slots past CB_N) keeps its first CB_N spells on slots 1-CB_N, in slot order (a repeat dropped); every slot past
-- CB_N cleared. cb6: CB_N = 8 (a cb3 store of 6 has nothing past 8: left as it is)
local function cbMigrate()
    local s = cbStore(false)
    if not s then return false end
    local past = false
    for i = CB_N + 1, CB_OLD_N do if rawget(s, i) ~= nil then past = true end end
    if not past then return false end
    local keep, seen = {}, {}
    for i = 1, CB_OLD_N do
        local id = rawget(s, i)
        if type(id) == "number" and id > 0 and id == math.floor(id) and not seen[id] and #keep < CB_N then keep[#keep + 1] = id; seen[id] = true end
    end
    for i = 1, CB_OLD_N do s[i] = keep[i] end
    return true
end
CB.migrate = cbMigrate
-- the stored slots, 1..CB_N (a bad entry reads as empty)
local function cbSlots()
    local out, s = {}, cbStore(false)
    if s then
        for i = 1, CB_N do
            local id = rawget(s, i)
            if type(id) == "number" and id > 0 and id == math.floor(id) then out[i] = id end
        end
    end
    return out
end
-- the toggle on a copy: the spell off its slot when it is on the bar, else on the first free slot.
-- cb3: an edit { id, s } puts the spell on slot s (off any other slot it was on; already there = off it), whatever was on s replaced
local function cbApply(slots, e)
    if type(e) == "table" then
        local id, at = e.id, e.s
        if slots[at] == id then slots[at] = nil; return "remove", at end
        for i = 1, CB_N do if slots[i] == id then slots[i] = nil end end
        slots[at] = id
        return "add", at
    end
    local id = e
    for i = 1, CB_N do if slots[i] == id then slots[i] = nil; return "remove", i end end
    for i = 1, CB_N do if slots[i] == nil then slots[i] = id; return "add", i end end
    return nil, nil, "full"
end
-- the slots as they will be once the queue is applied, and the slots the queue changes (q)
local function cbView()
    local v, q = {}, {}
    local base = cbSlots()
    for i = 1, CB_N do v[i] = base[i] end
    for _, ed in ipairs(CB.queue) do
        if type(ed) == "table" then for i = 1, CB_N do if v[i] == ed.id then q[i] = true end end end   -- cb3: the slot it leaves too
        local _, i = cbApply(v, ed)
        if i then q[i] = true end
    end
    return v, q
end

-- the cells: 0-7 the slots' casts, 8 the pick (no action: it runs on the press, as a plain cell)
local function cbInit(btn, col)
    local w = CB.want[col]
    btn:SetAttribute("type", w and "spell" or nil)
    btn:SetAttribute("spell", w and w.id or nil)
    CB.frozen[col] = w and w.sig or nil
    -- review: TapPad runs init only once the pad has its place, which may be long after CB.sync (a late layout, a fight's queue):
    -- the document's cells follow the cells really built, so it goes out again just after (once per build, CB.send drops a repeat)
    if not CB.resend and C_Timer and type(C_Timer.After) == "function" then
        CB.resend = true
        C_Timer.After(0, function() CB.resend = nil; if type(CB.send) == "function" then pcall(CB.send) end end)
    end
end
local function cbClick(col)
    if col == CB_PICK_COL then CB.pick = cbNow() end
end
-- (re)build the row when the stored slots changed; out of combat only. cb2: no row is registered for an empty bar (the row once
-- registered stays, emptied: TapPad cannot give it back); the Spellbook's pick works without it (padtab 39 then 19).
function CB.sync()
    if cbFight() then return false end
    local pad = WC.pad
    if type(pad) ~= "table" or type(pad.secureRow) ~= "function" then return false end
    local slots = cbSlots()
    local want, parts = {}, {}
    for i = 1, CB_N do
        local id = slots[i]
        if id then want[i - 1] = { id = id, sig = "s" .. id }; parts[#parts + 1] = (i - 1) .. "=" .. id end
    end
    local sig = table.concat(parts, ",")
    if CB.row and sig == CB.sig then return true end
    if not CB.row and #parts == 0 then                   -- cb2: an empty bar takes no pad row
        CB.want, CB.sig, CB.frozen = {}, nil, {}
        return false
    end
    CB.want = want
    local fresh = CB.row == nil
    local ok, row = pcall(pad.secureRow, "castbar", CB_N + 1, cbInit, cbClick, { onPress = true })
    CB.row = (ok and type(row) == "number") and row or nil
    -- cb2b (review): a row registered mid-session is not in Home's pad copy (it rides on the quests document) until that is sent again
    if fresh and CB.row and type(WC.questsSoon) == "function" then pcall(WC.questsSoon) end
    CB.sig = CB.row and sig or nil
    if not CB.row then CB.frozen = {} end
    return CB.row ~= nil
end

function CB.doc()
    local v, q = cbView()
    local e = {}
    for i = 1, CB_N do
        local id = v[i]
        local col = i - 1
        local w = CB.want[col]
        local built = id ~= nil and w ~= nil and w.id == id and CB.frozen[col] == w.sig
        if id then
            local icon = SN(function() return cbSpellApi("GetSpellTexture")(id) end)
            e[#e + 1] = { s = i, id = id, i = icon, c = (built and not q[i]) and col or nil, q = (q[i] or not built) and 1 or nil }
        elseif q[i] then
            e[#e + 1] = { s = i, q = 1 }                 -- emptied by a queued edit: dimmed until the fight ends
        end
    end
    return { type = "castbar", v = CBV, n = CB_N, pick = CB.row and CB_PICK_COL or nil, fight = cbFight() or nil,
             nopad = (not CB.row) and 1 or nil, pp = CB_PADTAB_PICK, e = e }
end
function CB.send(force)
    if type(WC.encode) ~= "function" or type(WC.send) ~= "function" then return end
    local ok, line = pcall(WC.encode, CB.doc())
    if not ok or type(line) ~= "string" then return end
    if not force and line == CB.last then return end
    CB.last = line
    WC.send(line)
    if type(WC.push) == "function" then WC.push() end
end
local function cbAck(ok, why, op, slot, queued)
    if type(WC.encode) ~= "function" or type(WC.send) ~= "function" then return ok end
    WC.send(WC.encode({ type = "castbarack", v = CBV, ok = ok and true or false, why = why, op = op, s = slot, q = queued and 1 or nil }))
    return ok
end

-- ONE edit: the spell on the bar or off it. Spells only (a known, active spell's id); in a fight it waits for the fight's end.
-- cb3: [slot] 1-CB_N = on that slot (cbApply's { id, s }); nil = the first free slot / off again
function CB.toggle(id, slot)
    if type(id) ~= "number" or id <= 0 then return cbAck(false, "spell") end
    if slot ~= nil and not (type(slot) == "number" and slot >= 1 and slot <= CB_N and slot == math.floor(slot)) then return cbAck(false, "slot") end
    if not cbStr(function() return cbSpellApi("GetSpellName")(id) end) then return cbAck(false, "spell") end
    local passive = cbSpellApi("IsSpellPassive") or rawget(_G, "IsPassiveSpell")
    if type(passive) == "function" and S(passive, id) == true then return cbAck(false, "passive") end
    if not cbWho() then return cbAck(false, "character") end
    local ed = slot and { id = id, s = slot } or id
    if cbFight() then
        local v = cbView()
        local op, i, why = cbApply(v, ed)
        if not op then return cbAck(false, why) end
        CB.queue[#CB.queue + 1] = ed
        cbAck(true, nil, op, i, true)
        CB.send()
        return true
    end
    local s = cbStore(true)
    if not s then return cbAck(false, "character") end
    local v = cbSlots()
    local op, i, why = cbApply(v, ed)
    if not op then return cbAck(false, why) end
    for k = 1, CB_N do s[k] = v[k] end                   -- cb3: a slot pick may move the spell off another slot too
    CB.sync()
    cbAck(true, nil, op, i)
    CB.send()
    return true
end
-- the fight is over: the queued edits go into the store in their order, the cells are built again
function CB.flushQueue()
    if cbFight() or #CB.queue == 0 then return false end
    local s = cbStore(true)
    local q = CB.queue
    CB.queue = {}
    if s then
        local v = cbSlots()
        for _, ed in ipairs(q) do cbApply(v, ed) end
        for i = 1, CB_N do s[i] = v[i] end
    end
    return true
end

-- Book b53: the Spellbook's pick was tapped just before (fresh CB_PICK_S): the spell's two taps that follow are ours
-- cb2: the same pick by the padtab row (Book b55: padtab 39 then 19), the castbar row not needed
-- cb3: [slot] 1-CB_N = the Bind box's third tab picked that slot (Book b56 / b57: padtab 39 then 20-25, 27-28); nil = the first free slot
function WC.castBarPick(slot)
    CB.pick = cbNow()
    CB.pickSlot = (type(slot) == "number" and slot >= 1 and slot <= CB_N and slot == math.floor(slot)) and slot or nil
end
function WC.castBarWants()
    local p = CB.pick
    return p ~= nil and cbNow() - p <= CB_PICK_S
end
function WC.castBarSpell(id)
    local slot = CB.pickSlot
    CB.pick, CB.pickSlot = nil, nil
    return CB.toggle(id, slot)
end
WC.castBarSlots = CB_N

-- ---------------------------------------------------------------- the cooldowns ("castcd")
local function cbLearned(id)
    local v = CB.learned[id]
    if v then return v end
    local n = cbStr(function() return cbSpellApi("GetSpellName")(id) end)
    local db = rawget(_G, "WowCompanionSpellsDB")
    local fc = type(db) == "table" and type(db.faceCd) == "table" and n and rawget(db.faceCd, n)   -- PadMap's learned lengths, read only
    if type(fc) == "number" and fc > 0 then return fc end
    return nil
end
local function cbCdSend()
    if type(WC.encode) ~= "function" or type(WC.send) ~= "function" then return end
    local k, now = {}, cbNow()
    for i, c in pairs(CB.cd) do
        local left = c.d - (now - c.t0)
        if left > 0 then k[tostring(i)] = { d = c.d, l = math.floor(left * 1000 + 0.5) / 1000, g = c.g, e = c.e } end
    end
    WC.send(WC.encode({ type = "castcd", v = CBV, k = next(k) and k or nil }))
    if type(WC.push) == "function" then WC.push() end
end
-- SPELL_UPDATE_COOLDOWN: each slot's spell, its flags now
function CB.cooldowns()
    local now, changed = cbNow(), false
    local v = cbView()
    local get = cbSpellApi("GetSpellCooldown")
    for i = 1, CB_N do
        local id, cur = v[i], CB.cd[i]
        if cur and (cur.id ~= id or now >= cur.t0 + cur.d) then CB.cd[i] = nil; cur = nil; changed = true end
        if id then
            local ok, t = CALL(get, id)
            local tab = ok and type(t) == "table"
            local active = tab and S(function() return t.isActive end) == true
            local gcd = tab and S(function() return t.isOnGCD end) == true
            local held = cur and cur.cast and now < cur.t0 + cur.d - 0.2 and (not active or gcd)
            if held then
            elseif not active then
                if cur then CB.cd[i] = nil; changed = true end
            else
                local dur = SN(function() return t.duration end)
                local st = SN(function() return t.startTime end)
                local plain = dur ~= nil and dur > 0
                if plain then if gcd then CB.learned["@gcd"] = dur else CB.learned[id] = dur end end
                if not cur or (cur.g or false) ~= gcd or (plain and math.abs((cur.t0 + cur.d) - ((st or now) + dur)) > 0.05) then
                    local d = plain and dur or (gcd and (CB.learned["@gcd"] or 1.5) or cbLearned(id))
                    local t0 = (plain and st) or now
                    if not plain and not gcd and cur and cur.g and now - cur.t0 < cur.d then t0 = cur.t0 end
                    if d and d > 0 then CB.cd[i] = { id = id, d = d, t0 = t0, g = gcd or nil, e = (not plain) or nil }
                    else CB.cd[i] = nil end                  -- running, length unknown: nothing drawn rather than a guess
                    changed = true
                end
            end
        elseif cur then
            CB.cd[i] = nil; changed = true
        end
    end
    if changed then cbCdSend() end
end
-- a cast that landed starts its slot's own cooldown (a next-swing spell's lands late), with its learned length, over 1.5 s only
function CB.castLanded(spellID)
    local id = SN(function() return spellID end)
    if not id then return end
    local now, hit = cbNow(), false
    local v = cbView()
    for i = 1, CB_N do
        if v[i] == id then
            local d = cbLearned(id)
            if d and d > 1.5 then CB.cd[i] = { id = id, d = d, t0 = now, e = true, cast = true }; hit = true end
        end
    end
    if hit then cbCdSend() end
end

local cbF = CreateFrame("Frame")
for _, ev in ipairs({ "PLAYER_LOGIN", "PLAYER_ENTERING_WORLD", "PLAYER_REGEN_ENABLED", "PLAYER_REGEN_DISABLED",
                      "SPELL_UPDATE_COOLDOWN", "SPELLS_CHANGED" }) do pcall(cbF.RegisterEvent, cbF, ev) end
pcall(function() cbF:RegisterUnitEvent("UNIT_SPELLCAST_SUCCEEDED", "player") end)
cbF:SetScript("OnEvent", function(_, ev, a1, a2, a3)
    pcall(function()
        if ev == "PLAYER_LOGIN" or ev == "PLAYER_ENTERING_WORLD" then
            CB.ready = true
            cbMigrate()                                  -- cb3 / cb6: an old 12-slot store keeps its first 8
            CB.sync()
            CB.send(true)
            -- the pad may get its place a moment later (its cells are built then): the document again, once it can say so
            if C_Timer and type(C_Timer.After) == "function" then C_Timer.After(3, function() pcall(CB.sync); pcall(CB.send) end) end
        elseif ev == "PLAYER_REGEN_ENABLED" then
            CB.flushQueue()
            CB.sync()
            CB.send()
            CB.cooldowns()
        elseif ev == "PLAYER_REGEN_DISABLED" then
            CB.send()
        elseif ev == "SPELL_UPDATE_COOLDOWN" then
            CB.cooldowns()
        elseif ev == "SPELLS_CHANGED" then
            if not cbFight() then CB.sync() end
            CB.send()
        elseif ev == "UNIT_SPELLCAST_SUCCEEDED" then
            CB.castLanded(a3)
        end
    end)
end)
end
-- CASTBAR-END

-- 1.0.19 TOUCH CAST (the user, 2026-11-09: "When touching the top controller stuff I don't want it to open the old controller room.
-- Its time to retire that. Instead the buttons should touch cast the skills"): one secure pad row "tcast" (32 cells = the 4 state
-- layers n / lt / rt / lr x the 8 places; set OUT of combat, frozen in a fight, as every secure cell). A place holding an action is the
-- game's own action (type action: the slot the button fires); a pet-bar place the game's pet action (type pet). Scripted buttons (jump,
-- interact, the targeting bars) get no cell. Each layout button carries its cell (`c`) only while the built cell is what it shows.
local TC = { want = {}, frozen = {}, row = nil, sig = nil }
local TC_LAYER = { n = 1, lt = 2, rt = 3, lr = 4 }
local function tcInit(btn, col)
    local w = TC.want[col]
    btn:SetAttribute("type", w and w.type or nil)
    btn:SetAttribute("action", w and w.action or nil)
    TC.frozen[col] = w and w.sig or nil
end
local function tcSync(layers)
    local want, parts = {}, {}
    for key, li in pairs(TC_LAYER) do
        local lay = layers[key]
        for p = 1, 8 do
            local d = lay and lay.d and lay.d[p]
            local s = d and num(d.s)
            if d and s and s > 0 and d.k ~= "" and d.k ~= "fixed" then
                local col = (li - 1) * 8 + p - 1
                local w = (d.k == "pet") and { type = "pet", action = s, sig = "p" .. s } or { type = "action", action = s, sig = "a" .. s }
                want[col] = w
            end
        end
    end
    for col = 0, 31 do if want[col] then parts[#parts + 1] = col .. "=" .. want[col].sig end end
    local sig = table.concat(parts, ",")
    local pad = WC.pad
    if sig ~= TC.sig and S(InCombatLockdown) == false and type(pad) == "table" and type(pad.secureRow) == "function" then
        TC.want = want
        local ok, row = pcall(pad.secureRow, "tcast", 32, tcInit, nil)
        TC.row = (ok and type(row) == "number") and row or nil
        TC.sig = TC.row and sig or nil
        if not TC.row then TC.frozen = {} end
    end
    return want
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
    local okT, tcw = pcall(tcSync, layers)           -- 1.0.19: the touch-cast cells
    if not okT then tcw = {} end
    local ov = {}
    for _, key in ipairs(LAYERS) do
        local lay = layers[key]
        local list = {}
        for p = 1, 8 do
            list[p] = layoutButton(lay.d[p])
            local li = TC_LAYER[key]
            if li then
                local col = (li - 1) * 8 + p - 1
                local w = tcw[col]
                if w and TC.frozen[col] == w.sig then list[p].c = col end
            end
        end
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
local statePets = {}          -- pet-bar slots of the state layers, for the range poll (the user, 2026-11-08: "some of my spells disregard the range")
local stateFight = {}         -- the in-combat spells' ids (hudS.fs), for the range poll
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
    local gs, gd = nil, nil                                     -- b-bar-gcd: a plain start / length (out of combat)
    for _, s in ipairs(stateSlots) do
        local cd = cooldownOf(s)
        local v = WC.ABSENT
        if cd then v = tfield(cd, "isOnGCD") end
        if v == true or v == false then
            fresh[s] = v
            anyBool = true
            if v then
                anyTrue = true
                if not gd then
                    local a, b = num(tfield(cd, "startTime")), num(tfield(cd, "duration"))
                    if a and b and b > 0 and b <= 3 then gs, gd = a, b end
                end
            end
        elseif gcdOf[s] ~= nil then
            fresh[s] = gcdOf[s]           -- nil even inside the handler: unknown, keep the last sample
        end
    end
    gcdOf = fresh                         -- slots out of view are forgotten, never left stale
    if anyBool then gcdKnown, gcdRunning = true, anyTrue end
    -- b-bar-gcd: a GCD seen while none runs (the last one has RUN OUT by its time, no "not active" read needed) starts a new one
    if anyTrue then
        local now = SN(GetTime) or 0
        if gd then WC.gcdLearn = gd end
        local g = WC.gcdRun
        if gs and gd then WC.gcdRun = { s = gs, d = gd }
        elseif not g or now >= g.s + g.d - 0.05 then WC.gcdRun = { s = now, d = WC.gcdLearn or 1.5 } end
    end
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
    if r[1] then usableMana(o, SV(r[2]), SV(r[3]), spellIdOf(d)) end
    if hasTarget then
        -- b-melee-range on the bars too (the user, 2026-11-10: "Yeah"): a melee spell with no range of its own reads the close check
        local msid = spellIdOf(d)
        local v
        if msid and type(WC.isMeleeNoRange) == "function" and WC.isMeleeNoRange(msid) then v = WC.spellRange(msid, "target")
        else v = S(A("IsActionInRange"), s) end
        if v == true then o.r = 1 elseif v == false then o.r = 0 end
    end
    local cd = cooldownOf(s)
    if cd then
        local act = tfield(cd, "isActive") == true
        local gcdNow = act and onGCD(s, cd)
        local on = act and not gcdNow
        o.cd = bit01(on)
        -- b-bar-gcd (the user, 2026-11-10: the GCD on every button, not only the In-combat ones): this slot is on the GCD now; the
        -- sweep's start / length ride hudS.gd (one for all)
        if gcdNow then o.g = 1 end
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
-- f-cast-bar-art (the game's own bar look): cs.n = 1 when the cast cannot be interrupted (UnitCastingInfo's 8th /
-- UnitChannelInfo's 7th return, read through S() and compared after it), ce.h = 1 when a CHANNEL ended (its own finish).
-- f-cast-name: cs.nm = the cast's name, cs.i = its icon (texture file ID), for the bar's centred name and its icon.
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
        if S(function() return select(ch and 7 or 8, fn("player")) end) == true then castNow.n = 1 end
        -- f-cast-name: the cast's name and icon (UnitCastingInfo / UnitChannelInfo's 1st and 3rd returns; plain for "player")
        castNow.nm = realStr(S(function() return (fn("player")) end))
        castNow.i = SN(function() return select(3, fn("player")) end)
        castEnd = nil
    elseif not castNow then
        return
    elseif event == "UNIT_SPELLCAST_INTERRUPTED" or event == "UNIT_SPELLCAST_FAILED" then
        castNow, castEnd = nil, { x = 0, t = now, h = castNow.k == "h" and 1 or nil }
    elseif event == "UNIT_SPELLCAST_SUCCEEDED" and castNow.k == "c" then
        castNow, castEnd = nil, { x = 1, t = now }
    elseif event == "UNIT_SPELLCAST_STOP" or event == "UNIT_SPELLCAST_CHANNEL_STOP" then
        -- a channel ends with STOP whether it ran out or was cut; a cast's STOP after SUCCEEDED never gets here
        local done = now >= castNow.e - 0.15
        castNow, castEnd = nil, { x = done and 1 or 0, t = now, h = castNow.k == "h" and 1 or nil }
    end
end

-- b-melee-range (the user, 2026-11-10: Raptor Strike "shouldn't be showing in range when im 40 yards away"; the Thor's log: its state
-- never once said out of range, Arcane Shot's did): a spell with NO range of its own (a next-swing melee attack: maxRange 0) is answered
-- "in range" by the game at any distance. For a harmful one the game's close-distance check stands in (CheckInteractDistance index 3, the
-- duel distance: a few yards, as Forever Range uses it); a spell with a range, or no plain answer, keeps the game's own reading.
WC.meleeNoRange = WC.meleeNoRange or {}
function WC.isMeleeNoRange(id)
    local m = WC.meleeNoRange[id]
    if m == nil then
        local ok, si = CALL(function() return C_Spell.GetSpellInfo(id) end)
        local mx = ok and type(si) == "table" and num(tfield(si, "maxRange")) or nil
        local harm = S(api("C_Spell", "IsSpellHarmful"), id) == true
        m = (mx ~= nil and mx <= 0 and harm) and true or false
        WC.meleeNoRange[id] = m
    end
    return m
end
function WC.spellRange(id, unit)
    local fr = api("C_Spell", "IsSpellInRange") or _G.IsSpellInRange
    local m = WC.isMeleeNoRange(id)
    if m and type(_G.CheckInteractDistance) == "function" then
        local v = S(_G.CheckInteractDistance, unit, 3)
        if v == true or v == false then return v end
    end
    return S(fr, id, unit)
end
-- b-combat-bar-states: one SPELL's state, by id (an In-combat spell has no bar slot): the same fields a bar slot's state carries
local function spellState(id, hasTarget)
    local o = { i = id, ct = castMs(id) }
    local r = PCALL(api("C_Spell", "IsSpellUsable") or _G.IsUsableSpell, id)          -- isUsable, insufficientPower
    if r[1] then usableMana(o, SV(r[2]), SV(r[3]), id) end
    if hasTarget then
        local v = WC.spellRange(id, "target")                  -- b-melee-range
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
    local L, slots, pets, sids = {}, {}, {}, {}
    for _, key in ipairs(STATE_LAYERS) do
        local list = {}
        for p = 1, 8 do
            local d = layers[key].d[p]
            list[p] = buttonState(d, combat, hasTarget)
            if d.k ~= "" and d.k ~= "fixed" and d.k ~= "pet" and (d.s or 0) > 0 then
                slots[#slots + 1] = d.s
                sids[d.s] = spellIdOf(d)                         -- b-melee-range: the poll's spell per slot
            elseif d.k == "pet" and (d.s or 0) > 0 then
                pets[#pets + 1] = d.s
            end
        end
        L[key] = list
    end
    stateSlots = slots
    statePets = pets
    WC.slotSid = sids
    local st = { c = combat, aa = autoAttack == true, ar = autoRepeat == true, L = L }
    do                                                          -- b-bar-gcd: the running GCD (game time) for the flagged slots
        local g, now = WC.gcdRun, SN(GetTime) or 0
        if g and now < g.s + g.d then st.gd = { s = math.floor(g.s * 1000 + 0.5) / 1000, d = math.floor(g.d * 1000 + 0.5) / 1000 } end
    end
    if castNow then st.cs = castNow
    elseif castEnd and (SN(GetTime) or 0) - castEnd.t < 2 then
        st.ce = { x = castEnd.x, t = math.floor(castEnd.t * 1000 + 0.5) / 1000, h = castEnd.h }
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
        stateFight = fspells
    else
        stateFight = {}
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
    if WC.hudHidden or WC.nbarOn then setAlpha(frame, 0) end      -- nbar1: the Nintendo bar keeps the stock bar at 0 too
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
    -- nbar1: while the Nintendo bar stands in, the stock bar stays at alpha 0 (its own switch gives it back)
    setAlpha(mainFrame(), WC.nbarOn and 0 or 1)
    setAlpha(_G.GamepadPersistentInputLegend, WC.nbarOn and 0 or 1)
    layoutSoon(0)
end

local function reapplyAlpha()
    if WC.hudHidden or WC.nbarOn then         -- nbar1: the Nintendo bar's stand-in too
        setAlpha(mainFrame(), 0)
        setAlpha(_G.GamepadPersistentInputLegend, 0)
    end
end

---------------------------------------------------------------------------------------------------
-- nbar1 THE NINTENDO BAR ON THE TOP SCREEN (issue #195, "Adaptative layout for action bars port to main screen": the top screen's
-- action bar set On shows the stock gamepad bar with the stock glyphs; the player wants DS Forever's Nintendo-layout bar there). The
-- user's picks (2026-11-12): a new choice "Nintendo" beside On / Off / Dynamic for the top screen's controller bars (the app's "hud"
-- element; Hub.lua taps "uisw" NBAR_OFF / NBAR_ON -> WC.nbarSet); it REPLACES the stock bar in the same spot - the stock bar at alpha 0
-- (SetAlpha only: the controller keeps casting through it exactly as before), ours shown; the LIVE LAYER only (plain / ZL / ZR /
-- ZL+ZR, switching as the triggers are held, read from the controller's own state as Book.lua's padSet does); each button resolved as
-- a press resolves it (the stock bar's own layer, so a stance / form bar's override is the picture; PadMap's overlay over it where the
-- router casts: the D-pad's DS toolbar, the fight layer's In-combat spells, the faces' fight spells, commands and bindings); display
-- only: plain frames, EnableMouse(false), no secure template, nothing of Blizzard's changed. Each button: its icon, its Nintendo glyph
-- (the game's own Gamepad_Rev_* atlas for the swap layout, Ltr_* for the plain one: the layout setting applies, as Hub's party glyphs),
-- the cooldown and charge sweeps, and the tints of the bars' own state (buttonState / spellState): unusable grey, not enough mana blue
-- (never red), out of range = the GLYPH red (the user's ruling: red is range only).
-- COOLDOWNS IN A FIGHT: GetActionCooldown / GetActionCharges are SecretWhenCooldownsRestricted and Cooldown:SetCooldown /
-- SetCooldownFromExpirationTime are AllowedWhenUntainted (an add-on's call fails); this client's docs give duration OBJECTS for both
-- (C_ActionBar.GetActionCooldownDuration / GetActionChargeDuration, C_Spell.GetSpellCooldownDuration / GetSpellChargeDuration ->
-- Cooldown:SetCooldownFromDurationObject, as Blizzard's own aura buttons do). Not proven on the device: every function feature-detected
-- and pcall'd; without them (or refused) the button is dimmed while its NeverSecret isActive says it cools (no sweep). The route that
-- worked rides one "cap" line (probe "nbarcd") out of a fight and one in a fight per login. A secret is never compared or computed.
---------------------------------------------------------------------------------------------------
local NB = { V = "nbar2", on = false, built = false, B = 46, OFF = 50, DX = 160, GLYPH = 22, Y_DEF = 64,
             POLL_S = 0.05, STATE_S = 0.2, set = 1, acc = 0, accS = 0, probed = {}, stats = nil,
             LAYER_OF = { "n", "lt", "rt", "lr" }, LAYER_WORD = { "", "ZL", "ZR", "ZL+ZR" },
             -- the button a position is, by the game's binding (bar.actionButtons order: D-pad L / U / R / D, face left / top / right / bottom)
             KEY_OF = { "PADDLEFT", "PADDUP", "PADDRIGHT", "PADDDOWN", "PAD3", "PAD4", "PAD2", "PAD1" },
             -- the place of each position in its diamond (D-pad diamond left, face diamond right)
             AT = { { -1, 0 }, { 0, 1 }, { 1, 0 }, { 0, -1 }, { -1, 0 }, { 0, 1 }, { 1, 0 }, { 0, -1 } },
             -- nbar2: the ABXY location "swap" (the Nintendo arrangement, CastView's NINTENDO_FACE_SWAP): X (PAD3) on top, Y (PAD4) on
             -- the left, B (PAD2) at the bottom, A (PAD1) on the right; the D-pad unchanged
             AT_SWAP = { [5] = { 0, 1 }, [6] = { -1, 0 }, [7] = { 0, -1 }, [8] = { 1, 0 } },
             -- PadMap m29's DS toolbar on the bare D-pad (left chat, up radial, right map, down Home's bar): the commands' own icons
             TOOLBAR = { 134332, 134829, 134269, "tex:Interface\\AddOns\\WowCompanion\\Media\\ds_forever_icon" } }
WC.nbar = NB
WC.nbarOn = false

-- nbar2 THE ONE GLYPH RULE (NB and Hub share it; PadMap m119 / m120 draw the same): a face button wears ITS OWN letter (PAD1 A, PAD2 B,
-- PAD3 X, PAD4 Y - the user, m119: "the inspect glyph is Y not X"; the app's harvested rev_a / rev_b / rev_x / rev_y art is keyed the
-- same, Back = rev_b); the layout setting picks the set: "plain" the lettered Ltr_*, else the white Nintendo Rev_* (m120). Where the
-- letter is drawn is the ABXY location (NB.AT_SWAP), never a relabel. The D-pad: the generic arrows.
function WC.padGlyphAtlas(key)
    local db = rawget(_G, "WowCompanionSpellsDB")
    local set = (type(db) == "table" and db.padLayout == "plain") and "Ltr_" or "Rev_"
    local letter = ({ PAD1 = "A", PAD2 = "B", PAD3 = "X", PAD4 = "Y" })[key]
    if letter then return { "Gamepad_" .. set .. letter .. "_64" } end
    local d = ({ PADDUP = "Up", PADDDOWN = "Down", PADDLEFT = "Left", PADDRIGHT = "Right" })[key]
    if d then return { "Gamepad_Gen_" .. d .. "_64", "Gamepad_Gen_DPad" .. d .. "_64", "Gamepad_Ltr_" .. d .. "_64" } end
    return {}
end
NB.glyphAtlas = WC.padGlyphAtlas
NB.GLYPH_WORD = { PAD1 = "A", PAD2 = "B", PAD3 = "X", PAD4 = "Y", PADDUP = "^", PADDDOWN = "v", PADDLEFT = "<", PADDRIGHT = ">" }
-- nbar2: an atlas this client really has (Trail's PRD.atlasOn: GetAtlasInfo knows it and SetAtlas did not return false; a pcall alone
-- counts a refused atlas as drawn)
function NB.atlasOn(tex, a)
    local prd = rawget(WC, "prd")
    if type(prd) == "table" and type(prd.atlasOn) == "function" then
        local ok, r = pcall(prd.atlasOn, tex, a)
        return ok and r == true
    end
    local CT = rawget(_G, "C_Texture")
    if type(CT) == "table" and type(CT.GetAtlasInfo) == "function" then
        local okI, info = pcall(CT.GetAtlasInfo, a)
        if not (okI and type(info) == "table") then return false end
    end
    local ok, r = pcall(function() return tex:SetAtlas(a) end)
    return ok and r ~= false
end
function NB.layout()
    local db = rawget(_G, "WowCompanionSpellsDB")
    return (type(db) == "table" and db.padLayout == "plain") and "plain" or "swap"
end
-- a button's place in its diamond for the layout (the faces move with the ABXY location)
function NB.anchor(b, lay)
    local p = b.p
    local a = (lay == "swap" and NB.AT_SWAP[p]) or NB.AT[p]
    local cx = (p <= 4) and -NB.DX or NB.DX
    pcall(b.ClearAllPoints, b)
    b:SetPoint("CENTER", b.nbParent, "CENTER", cx + a[1] * NB.OFF, a[2] * NB.OFF)
    b.alay = lay
end

-- THE LIVE LAYER: 1 none, 2 ZL, 3 ZR, 4 both, from the controller's own state (addon code reads C_GamePad.GetDeviceMappedState; the
-- buttons indexed as the router's, ButtonBindingToIndex + 1); while a window owns the pad the game draws the base bar alone
function NB.padIdx(name)
    local gp = rawget(_G, "C_GamePad")
    if type(gp) ~= "table" or type(gp.ButtonBindingToIndex) ~= "function" then return nil end
    local v = num(S(gp.ButtonBindingToIndex, name))
    return (v and v >= 0) and math.floor(v) + 1 or nil
end
function NB.heldSet()
    local gp = rawget(_G, "C_GamePad")
    if type(gp) ~= "table" or type(gp.GetDeviceMappedState) ~= "function" then return 1 end
    local dev = nil
    if type(gp.GetActiveDeviceID) == "function" then dev = num(S(gp.GetActiveDeviceID)) end
    if dev == nil and type(gp.GetCombinedDeviceID) == "function" then dev = num(S(gp.GetCombinedDeviceID)) end
    local ok, st = pcall(gp.GetDeviceMappedState, dev)
    if not ok or type(st) ~= "table" or not WC.isPlain(st) then return 1 end
    local bt = field(st, "buttons")
    if type(bt) ~= "table" or not WC.isPlain(bt) then return 1 end
    local li, ri = NB.padIdx("PADLTRIGGER"), NB.padIdx("PADRTRIGGER")
    if not li or not ri then return 1 end
    local lt = S(function() return bt[li] end) == true
    local rt = S(function() return bt[ri] end) == true
    if lt and rt then return 4 elseif lt then return 2 elseif rt then return 3 end
    return 1
end

-- a texture value (a file id, "atlas:<name>", "tex:<path>") onto a texture; false = nothing to show
function NB.paint(tx, v)
    if v == nil or v == false then pcall(tx.SetTexture, tx, nil); return false end
    if type(v) == "number" then return (pcall(tx.SetTexture, tx, v)) end
    if type(v) == "string" then
        if v:sub(1, 6) == "atlas:" then return (pcall(tx.SetAtlas, tx, v:sub(7))) end
        if v:sub(1, 4) == "tex:" then return (pcall(tx.SetTexture, tx, v:sub(5))) end
        return (pcall(tx.SetTexture, tx, v))
    end
    return false
end

-- one button of our bar: plain frames only (no template but the game's plain cooldown swipe), never the mouse
function NB.button(parent, p)
    local b = CreateFrame("Frame", nil, parent)
    b:SetSize(NB.B, NB.B)
    pcall(b.EnableMouse, b, false)
    b.p, b.nbParent = p, parent
    b.bg = b:CreateTexture(nil, "BACKGROUND")
    b.bg:SetAllPoints(b)
    pcall(b.bg.SetColorTexture, b.bg, 0, 0, 0, 0.45)
    b.icon = b:CreateTexture(nil, "ARTWORK")
    b.icon:SetPoint("TOPLEFT", b, "TOPLEFT", 2, -2)
    b.icon:SetPoint("BOTTOMRIGHT", b, "BOTTOMRIGHT", -2, 2)
    b.cd = CreateFrame("Cooldown", nil, b, "CooldownFrameTemplate")
    b.cd:SetAllPoints(b.icon)
    pcall(b.cd.EnableMouse, b.cd, false)
    pcall(b.cd.SetDrawEdge, b.cd, false)
    -- the charge sweep: the edge alone, as the game's own buttons draw a recharge
    b.ch = CreateFrame("Cooldown", nil, b, "CooldownFrameTemplate")
    b.ch:SetAllPoints(b.icon)
    pcall(b.ch.EnableMouse, b.ch, false)
    pcall(b.ch.SetDrawSwipe, b.ch, false)
    pcall(b.ch.SetDrawEdge, b.ch, true)
    -- the fallback's "cooling" dim (no sweep: the times are secret in a fight)
    b.dim = b:CreateTexture(nil, "OVERLAY")
    b.dim:SetAllPoints(b.icon)
    pcall(b.dim.SetColorTexture, b.dim, 0, 0, 0, 0.6)
    b.dim:Hide()
    b.glyph = b:CreateTexture(nil, "OVERLAY", nil, 2)
    b.glyph:SetSize(NB.GLYPH, NB.GLYPH)
    b.glyph:SetPoint("CENTER", b, "BOTTOMRIGHT", -3, 3)
    b.word = b:CreateFontString(nil, "OVERLAY", "GameFontNormalSmall")
    b.word:SetPoint("CENTER", b.glyph, "CENTER", 0, 0)
    b.count = b:CreateFontString(nil, "OVERLAY", "NumberFontNormalSmall")
    b.count:SetPoint("BOTTOMLEFT", b, "BOTTOMLEFT", 3, 3)
    return b
end

function NB.build()
    if NB.built then return NB.f end
    local f = CreateFrame("Frame", "WowCompanionNintendoBar", UIParent)
    pcall(f.EnableMouse, f, false)
    pcall(f.SetFrameStrata, f, "MEDIUM")
    f:SetSize(2 * NB.DX + 2 * NB.OFF + NB.B, 2 * NB.OFF + NB.B)
    f.btn = {}
    for p = 1, 8 do
        local b = NB.button(f, p)
        NB.anchor(b, NB.layout())                 -- nbar2: the faces at the layout's ABXY location
        f.btn[p] = b
    end
    -- the layer held now, over the bar's middle (none for the bare layer)
    f.layer = f:CreateFontString(nil, "OVERLAY", "GameFontNormal")
    f.layer:SetPoint("CENTER", f, "CENTER", 0, 0)
    f:Hide()
    f:SetScript("OnUpdate", function(_, el) NB.tick(el) end)
    -- nbar2: the stock bar's spot / scale again when the UI scale or the screen changes
    pcall(f.RegisterEvent, f, "UI_SCALE_CHANGED")
    pcall(f.RegisterEvent, f, "DISPLAY_SIZE_CHANGED")
    f:SetScript("OnEvent", function() if NB.on then pcall(NB.place) end end)
    NB.f, NB.built = f, true
    NB.place()
    return f
end

-- the stock bar's spot: its centre, read only (a plain number out of a fight), else bottom centre
function NB.place()
    local f = NB.f
    if not f then return end
    local mf = mainFrame()
    local cx, cy = nil, nil
    if mf ~= nil and type(field(mf, "GetCenter")) == "function" then
        local r = PCALL(mf.GetCenter, mf)
        if r[1] then cx, cy = num(SV(r[2])), num(SV(r[3])) end
    end
    local es = num(MS(mf, "GetEffectiveScale"))
    local us = num(MS(UIParent, "GetEffectiveScale"))
    local k = (es and us and us > 0 and es > 0) and es / us or nil
    if not (cx and cy and cx > 0 and cy > 0) and NB.lastC then
        -- nbar2: a fight hides the centre (secret): the last spot read stays (never moved to the default mid-fight)
        cx, cy, k = NB.lastC[1], NB.lastC[2], k or NB.lastC[3]
    end
    k = k or 1
    -- nbar2: the stock bar's own scale on ours (its buttons the stock size); the offsets then in its own units
    pcall(f.SetScale, f, k)
    NB.scale = k
    pcall(f.ClearAllPoints, f)
    if cx and cy and cx > 0 and cy > 0 then
        NB.lastC = { cx, cy, k }
        f:SetPoint("CENTER", UIParent, "BOTTOMLEFT", cx, cy)
        NB.at = "stock"
    else
        f:SetPoint("BOTTOM", UIParent, "BOTTOM", 0, NB.Y_DEF)
        NB.at = "default"
    end
end

-- a ref from PadMap's views ({ n, id, i } a spell; { n, i, t, key } a whole-button thing) -> an entry
local function refEntry(ref)
    if type(ref) ~= "table" then return { kind = "empty" } end
    if ref.t ~= nil then return { kind = "icon", icon = ref.i } end
    local id = num(ref.id)
    local ic = ref.i
    if ic == nil and id then ic = num(S(api("C_Spell", "GetSpellTexture"), id)) end
    return { kind = "spell", id = id, icon = ic }
end
-- the stock bar's own button at this place (its layer follows a stance / form / possess bar's override)
local function slotEntry(d, hasTarget)
    if d == nil or d.k == "" then return { kind = "empty" } end
    if d.k == "fixed" then
        local ic = d.live and liveIcon(d, hasTarget) or iconOf(d.btn)
        return { kind = "icon", icon = ic }
    end
    if d.k == "pet" then
        local r = PCALL(_G.GetPetActionInfo, d.s)
        local ic = nil
        if r[1] then
            local name, tex = realStr(SV(r[2])), SV(r[3])
            if type(WC.petTok) == "table" and WC.petTok.isToken(SV(r[4]), name) then ic = WC.petTok.icon(tex) or texVal(tex)
            else ic = texVal(tex) end
        end
        return { kind = "slot", d = d, icon = ic or iconOf(d.btn) }
    end
    return { kind = "slot", d = d, icon = texVal(S(A("GetActionTexture"), d.s)) or iconOf(d.btn) }
end
NB.refEntry, NB.slotEntry = refEntry, slotEntry

-- WHAT A PRESS DOES at (set, p) now -> an entry { kind = "slot" (d) / "spell" (id) / "icon" / "empty", icon }
-- `fight` = the ROUTER's fight switch with Combat controls on (NB.fightCtx: off, no fight text is armed and a press casts the slot);
-- `form` = the active form's spell id (its own In-combat set on the bare layer, PadMap m55: the router's "wc-ff-<form>-<pos>")
function NB.resolve(set, p, lay, page, fight, dpadOn, hasTarget, form)
    local d = lay and lay.d and lay.d[p] or nil
    local v = nil
    local pm = rawget(WC, "padMap")
    if type(pm) == "table" and type(pm.slotAt) == "function" then
        local ok, r = pcall(pm.slotAt, page, set, p)
        if ok and type(r) == "table" then v = r end
    end
    -- nbar2: what the button holds outside the fight pictures: a command / binding (also under a fight-layer entry: its Otherwise)
    local function base()
        if v then
            if v.kind == "command" or v.kind == "bind" then return { kind = "icon", icon = v.i } end
            if v.kind == "combat" and v.ov and (v.base == "bind" or v.base == "command") and type(v.o) == "table" then
                return { kind = "icon", icon = v.o.i }
            end
        end
        return slotEntry(d, hasTarget)
    end
    if set == 1 and fight and form and type(pm) == "table" and type(pm.formFight) == "function" then
        -- nbar2: in a form, its own In-combat spell (or whole-button thing) wins on the bare layer, as the router and Home (OV.fightAt)
        local ok, e = pcall(pm.formFight, form, p)
        if ok and type(e) == "table" then return refEntry(e) end
    end
    if set == 1 and p <= 4 then
        -- the bare D-pad: the fight layer's In-combat spell in a fight, else DS Forever's toolbar while the router holds it
        if fight and v and v.kind == "combat" and v.ov and type(v.c) == "table" then return refEntry(v.c) end
        if dpadOn then return { kind = "icon", icon = NB.TOOLBAR[p] } end
        return base()                                   -- nbar2: a command / binding on the bare D-pad (no toolbar) too
    end
    if set == 1 then
        -- the bare faces: the game's fixed four; a face's own fight spell (PadMap's FACE: X / Y / A) in a fight
        if fight and v and type(v.cf) == "table" then return refEntry(v.cf) end
        return slotEntry(d, hasTarget)
    end
    if fight and v and v.kind == "combat" and v.ov and type(v.c) == "table" then return refEntry(v.c) end
    return base()
end

-- nbar2: the fight switch as the ROUTER has it, and the form now. The router's own switch (PadMap's sw: the fight or an attack /
-- auto-repeat armed, not given back by B) is WC.tgGrown (the Y router button's attributes: true / false, nil = not routed: then the
-- fight itself); nothing of it while Combat controls are off (no fight text armed: a press casts the slot)
function NB.fightCtx(fight)
    local pm = rawget(WC, "padMap")
    if type(pm) == "table" and type(pm.combatOn) == "function" then
        local ok, on = pcall(pm.combatOn)
        if ok and on == false then return false, nil end
    end
    local sw = fight
    if type(WC.tgGrown) == "function" then
        local ok, g = pcall(WC.tgGrown)
        if ok and (g == true or g == false) then sw = g end
    end
    if not sw then return false, nil end
    local form = nil
    if type(pm) == "table" and type(pm.forms) == "function" then
        local ok, fs = pcall(pm.forms)
        if ok and type(fs) == "table" then
            for _, fo in ipairs(fs) do if type(fo) == "table" and fo.on == true and type(fo.id) == "number" then form = fo.id end end
        end
    end
    return true, form
end

-- the spell's own NeverSecret "active" flag (the fallback's dim), not the GCD's
local function spellCooling(id)
    local ok, t = CALL(api("C_Spell", "GetSpellCooldown"), id)
    if not ok or type(t) ~= "table" or not WC.isPlain(t) then return nil end
    if tfield(t, "isActive") ~= true then return false end
    return tfield(t, "isOnGCD") ~= true
end

-- THE SWEEPS: a duration object straight to the swipe (never read, compared or computed); else the dim. -> the route used
function NB.cool(b, e, st)
    local cdf, chf = b.cd, b.ch
    local setDur = type(field(cdf, "SetCooldownFromDurationObject")) == "function"
    local getCd, getCh, arg = nil, nil, nil
    if e.kind == "slot" and e.d and e.d.k ~= "pet" and (e.d.s or 0) > 0 then
        getCd, getCh, arg = api("C_ActionBar", "GetActionCooldownDuration"), api("C_ActionBar", "GetActionChargeDuration"), e.d.s
    elseif e.kind == "spell" and e.id then
        getCd, getCh, arg = api("C_Spell", "GetSpellCooldownDuration"), api("C_Spell", "GetSpellChargeDuration"), e.id
    end
    local stats = NB.stats
    if arg == nil then
        pcall(cdf.Clear, cdf); pcall(chf.Clear, chf); b.dim:Hide()
        return nil
    end
    local route = nil
    if setDur and getCd then
        local ok, obj = pcall(getCd, arg, false)
        if ok and type(obj) ~= "nil" and pcall(cdf.SetCooldownFromDurationObject, cdf, obj, true) then
            route = "dur"
            if stats then stats.ok = stats.ok + 1 end
        elseif stats then stats.fail = stats.fail + 1 end
    end
    local chOk = false
    if setDur and getCh then
        local ok, obj = pcall(getCh, arg)
        if ok and type(obj) ~= "nil" and pcall(chf.SetCooldownFromDurationObject, chf, obj, true) then chOk = true end
    end
    if not chOk then pcall(chf.Clear, chf) end
    if stats and chOk then stats.ch = stats.ch + 1 end
    if route == "dur" then b.dim:Hide(); return "dur" end
    pcall(cdf.Clear, cdf)
    -- the fallback: dimmed while the game's NeverSecret flag says it cools (the bars' own cd, the GCD left out)
    local cooling
    if e.kind == "slot" then cooling = st ~= nil and st.cd == 1 else cooling = spellCooling(arg) end
    if cooling then b.dim:Show() else b.dim:Hide() end
    return "dim"
end

-- the tints: unusable grey, not enough mana blue (never red); out of range = the glyph red (range only, the user's ruling)
function NB.tint(b, st)
    local u, m, r = st and st.u, st and st.m, st and st.r
    if m == 1 then
        pcall(b.icon.SetDesaturated, b.icon, false); b.icon:SetVertexColor(0.45, 0.55, 1)
        b.look = "mana"
    elseif u == 0 then
        pcall(b.icon.SetDesaturated, b.icon, true); b.icon:SetVertexColor(0.45, 0.45, 0.45)
        b.look = "unusable"
    else
        pcall(b.icon.SetDesaturated, b.icon, false); b.icon:SetVertexColor(1, 1, 1)
        b.look = "ok"
    end
    if r == 0 then b.glyph:SetVertexColor(1, 0.2, 0.2); b.word:SetTextColor(1, 0.2, 0.2); b.range = false
    else b.glyph:SetVertexColor(1, 1, 1); b.word:SetTextColor(1, 1, 1); b.range = true end
end

function NB.glyph(b)
    local key = NB.KEY_OF[b.p]
    local lay = NB.layout()
    if b.alay ~= lay then NB.anchor(b, lay) end          -- nbar2: the faces follow the ABXY location
    if b.gkey == key and b.glay == lay then return end
    b.gkey, b.glay = key, lay
    for _, a in ipairs(NB.glyphAtlas(key)) do
        -- nbar2: only an atlas the client really has (a refused one falls to the next, then the letter)
        if NB.atlasOn(b.glyph, a) then b.glyph:Show(); b.word:SetText(""); b.atlas = a; return end
    end
    b.glyph:Hide(); b.atlas = nil
    b.word:SetText(NB.GLYPH_WORD[key] or "")
end

-- one full redraw of the live layer
function NB.refresh()
    local f = NB.f
    if not f or not NB.on then return end
    local combat = S(InCombatLockdown) == true
    local fight = combat or S(UnitAffectingCombat, "player") == true
    local hasTarget = S(UnitExists, "target") == true
    -- nbar2: hidden with the stock bar (a pet battle, a cinematic, out of gamepad mode: the game's own IsVisible, a plain read);
    -- our own frame's alpha, so the tick keeps watching
    local mf = mainFrame()
    local vis = mf ~= nil and MS(mf, "IsVisible") ~= false
    pcall(f.SetAlpha, f, vis and 1 or 0)
    NB.vis = vis
    local fightSw, form = NB.fightCtx(fight)
    local bu = barsUsable()
    local set = (bu == false) and 1 or NB.heldSet()
    NB.set = set
    local pu = pageUnit()
    local layers = resolveAll(pu)
    local lay = layers[NB.LAYER_OF[set]]
    local page = currentPage(pu) or 1
    local dpadOn = false
    local pm = rawget(WC, "padMap")
    if type(pm) == "table" and type(pm.dpadState) == "function" then
        local ok, ds = pcall(pm.dpadState)
        dpadOn = ok and type(ds) == "table" and ds.on == true
    end
    f.layer:SetText(NB.LAYER_WORD[set] or "")
    NB.stats = { ok = 0, fail = 0, ch = 0, dim = 0, n = 0 }
    NB.shown = {}
    for p = 1, 8 do
        local b = f.btn[p]
        local e = NB.resolve(set, p, lay, page, fightSw, dpadOn, hasTarget, form)
        NB.glyph(b)
        local st = nil
        if e.kind == "slot" then st = buttonState(e.d, combat, hasTarget)
        elseif e.kind == "spell" and e.id then st = spellState(e.id, hasTarget) end
        if e.kind == "empty" or not NB.paint(b.icon, e.icon) then
            b.icon:Hide()
        else
            b.icon:Show()
        end
        NB.tint(b, st)
        local route = NB.cool(b, e, st)
        if route then NB.stats.n = NB.stats.n + 1 end
        if route == "dim" then NB.stats.dim = NB.stats.dim + 1 end
        -- an item's count (consumables), else a multi-charge spell's charges (both plain or absent: buttonState's own reads)
        local cnt = st and (num(st.cnt) or num(st.ch)) or nil
        b.count:SetText(cnt and tostring(cnt) or "")
        b.kind = e.kind
        NB.shown[p] = { kind = e.kind, icon = e.icon, id = e.id, s = e.d and e.d.s or nil, route = route }
    end
    NB.probe(fight)
end

-- probe "nbarcd": which cooldown route is live, once out of a fight and once in one per login (plain values only)
function NB.probe(fight)
    local key = fight and "fight" or "calm"
    local s = NB.stats
    if NB.probed[key] or not s or s.n == 0 then return end
    NB.probed[key] = true
    local cdf = NB.f and NB.f.btn[1] and NB.f.btn[1].cd
    local have = { acd = api("C_ActionBar", "GetActionCooldownDuration") ~= nil, ach = api("C_ActionBar", "GetActionChargeDuration") ~= nil,
                   scd = api("C_Spell", "GetSpellCooldownDuration") ~= nil, sch = api("C_Spell", "GetSpellChargeDuration") ~= nil,
                   set = type(field(cdf, "SetCooldownFromDurationObject")) == "function" }
    local route = (s.ok > 0 and s.fail == 0 and "dur") or (s.ok > 0 and "mixed") or "dim"
    NB.route = route
    pcall(function() WC.emit({ type = "cap", probe = "nbarcd", v = NB.V, fight = fight, route = route, n = s.n, ok = s.ok, fail = s.fail,
                               dim = s.dim, ch = s.ch, have = have, at = NB.at }) end)
end

function NB.tick(el)
    if not NB.on then return end
    NB.acc, NB.accS = NB.acc + (el or 0), NB.accS + (el or 0)
    if NB.acc < NB.POLL_S then return end
    NB.acc = 0
    local bu = barsUsable()
    if bu ~= NB.bu then                                 -- nbar2: a window taking / giving back the pad collapses the stock bar
        NB.bu = bu
        pcall(NB.place)
    end
    local set = (bu == false) and 1 or NB.heldSet()
    if set ~= NB.set or NB.accS >= NB.STATE_S then
        NB.accS = 0
        local ok, err = pcall(NB.refresh)
        if not ok then noteError(err) end
    end
end

-- Hub.lua's switch (the app's "Nintendo" choice): our bar shown and the stock bar at alpha 0, or the stock bar back (unless the
-- controller bars are hidden by their own element)
function WC.nbarSet(on)
    on = on == true
    NB.on, WC.nbarOn = on, on
    if on then
        setAlpha(mainFrame(), 0)
        setAlpha(_G.GamepadPersistentInputLegend, 0)
        NB.build()
        NB.place()
        NB.f:Show()
        NB.set = 0                                -- the first tick redraws
        local ok, err = pcall(NB.refresh)
        if not ok then noteError(err) end
    else
        if not WC.hudHidden then
            setAlpha(mainFrame(), 1)
            setAlpha(_G.GamepadPersistentInputLegend, 1)
        end
        if NB.f then NB.f:Hide() end
    end
    return true
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
        local msid = WC.slotSid and WC.slotSid[s]
        local v
        if msid and WC.isMeleeNoRange(msid) then v = WC.spellRange(msid, "target") else v = S(fn, s) end   -- b-melee-range
        local r = (v == true and 1) or (v == false and 0) or -1
        if rangeSeen[s] ~= r then
            rangeSeen[s] = r
            changed = true
        end
    end
    -- the pet bar's slots and the in-combat spells drawn over the bars in a fight: their range moves with you too
    for _, s in ipairs(statePets) do
        local r = PCALL(_G.GetPetActionInfo, s)
        local v = -1
        if r[1] and SV(r[9]) == true then v = (SV(r[10]) == true) and 1 or 0 end
        if rangeSeen["p" .. s] ~= v then rangeSeen["p" .. s] = v; changed = true end
    end
    for _, id in ipairs(stateFight) do
        local v = WC.spellRange(id, "target")                         -- b-melee-range
        local r = ((v == true or v == 1) and 1) or ((v == false or v == 0) and 0) or -1
        if rangeSeen["f" .. id] ~= r then rangeSeen["f" .. id] = r; changed = true end
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
    -- every DS Forever add-on's (2026-11-04: "WowCompanionSpells has been blocked" named no function): the app logs it (WCBLOCK)
    local w = SV(who)
    if type(w) ~= "string" or w:sub(1, #ADDON) ~= ADDON then return end
    blockedN = blockedN + 1
    if blockedN > 20 then return end            -- a storm must not become a flush storm
    WC.emit({ type = "cap", hudBlocked = {
        ev = (event == "ADDON_ACTION_BLOCKED") and "blocked" or "forbidden",
        fn = realStr(SV(fn)),
        who = w,
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
        if WC.nbar and WC.nbar.on then pcall(WC.nbar.place) end      -- nbar1: the stock bar's spot again
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
