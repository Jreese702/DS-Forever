-- WowCompanion / ActionBar.lua
-- nbar1 (issue #195 "Adaptative layout for action bars port to main screen", the user 2026-11-12: a "Nintendo" choice next to On / Off /
-- Dynamic for the top screen's action bar, DS Forever's Nintendo-layout bar REPLACING the stock one in the same spot, the live layer
-- only, cooldown + charge sweeps by the duration-object route with a dim fallback): WC.nbar / WC.nbarSet, at "THE NINTENDO BAR" below.
-- nbar12 THE WINGS' PLATES AND PARTY MODE (the user's rulings 2026-11-22, after the shots): (A) the band's L / R corner plates on
-- the Wings, the ZL / ZR chips under them; (C) Party mode keeps the Top screen in its fights - the Wings carry the Party layer.
-- The full note at "nbar12 THE PLATES AND THE PARTY LAYER" (the Wings' section). nbar12 review (11-22): B / pre-cfg Y draw the TOP
-- bar's own Back / Inspect (topFixed, never a form or vehicle bar's slot); Hub's rows under the HUD's place kept clear with our HUD bars
-- off ("partyhud"); the re-fit follows Hub's and Trail's party move on the tick it shows (NB.topMoved).
-- nbar11 THE WINGS REWORK (the user, 2026-11-22, after trying Top screen: "The square icons don't look anything like ours on the bottom
-- screen in the circles and it overlaps with my hud ... It should be a slick animation looking like it slides up to the top screen"):
-- (a) THE RING LOOK - every button wears the bottom band's controller look (CastView.drawSlot's layers, the game's own gamepad atlases
-- the band harvests on the device, so nothing is shipped): the focus glow, the dark well, the icon masked round (faces) / rounded square
-- (D-pad: the band's D-pad slots are square), the slot ring (normal / pressed / disabled), the empty slot's own art, the glyph badge at
-- the ring's top-right (the Nintendo face lettering, the plain layout's xbox letters, the D-pad's cross glyphs; red = range only), the
-- count bottom-right; the ZL / ZR chips are the trigger glyphs over a soft gold disc when held. Atlas refused -> today's letter.
-- (b) NEVER OVER THE HUD - the HUD block comes from its geometry (Trail P.lay.block: bars, pips, aura rows under or above, the pet strip
-- under or beside, Party mode's rows, the lift), unioned with the live frames read at ANY alpha (a HUD faded out of combat still counts);
-- the fight-only game frames (cast bars, Cooldown Manager, mirror timers, extra ability, boss frames, swing timers) are RESERVED once
-- seen; the solver shrinks before it moves (k 1 / 0.9 / 0.8, raised, joined, a grid scan at 0.7, the stock spot last).
-- (c) THE SLIDE - with a clear lane under both halves the bar slides IN UPWARD from below the top screen's bottom edge (60 ms hold,
-- 0.26 s ease-out) as the app's band slides up off the bottom screen, and back down (0.20 s ease-in); no lane = today's fade + rise.
-- The fight's edges (REGEN / auto-attack / auto-repeat) send the state at once (the app's slide is not 0.5 s late). Party mode: no Wings
-- (the band stays below, ruling R0's default). Display only, as before (the LIMITS note at the Wings).
-- nbar10 (movable Home fix round 2): the joined bar (placed, or case 4's stock fallback) is clamped to the screen BEFORE the tap pad
-- band test, and that test keeps MARGIN (24 px) as fits() does: a top-left spot the clamp moved right never lands on the band.
-- nbar9 (movable Home fix round 1): the Wings' frames are built in a fight too (a /reload or a login inside combat, or the character
-- key arriving mid-fight, left the opted-in player with no bar for the whole fight: NB.build is display-only, legal in combat); the
-- driver reads the flag at its first tick and then every FLAG_S (0.5 s), not every frame, and does nothing more while it is off.
-- nbar8 (the fix pass on movable Home): a "Combat bar" entry counts as PLACED only when it moves the piece (dx / dy not 0) - a size
-- alone (the editor's size step) keeps the Wings at their auto spot, scaled; in a fight (InCombatLockdown / the player in combat) the
-- Wings stay for the WHOLE fight whatever B gave back (SPEC section 11: tgGrown only decides out of combat - an attack armed before the
-- pull, B's give-back); a settled frame of the motion does no work (no spot, no string rebuilt per frame).
-- nbar7 THE COMBAT BAR ON THE TOP SCREEN (movable Home phase 3, SPEC.txt section 11; the user's rulings 2026-11-20: D1 opt-in, D7 Wings):
-- with this character's flag on (PadMap m179 ctop, the app's "In fights: Top screen") the Nintendo bar shows in fights as two halves around
-- our HUD at a spot worked out out of combat (NB.fightSolve), frozen in the fight and re-fit only against our own frames; display only.
-- At "THE COMBAT BAR ON THE TOP SCREEN".
-- nbar5 SPLIT SWING TIMERS (the user, 2026-11-16: "Dual weilding needs split swing timers for each weapon"): with a weapon in the off
-- hand (UnitAttackSpeed's 2nd return, the game's own HasOffHandWeapon) hudS.sw is the MAIN hand's swing and hudS.so the OFF hand's,
-- each from its own PLAYER_SWING (type 0 / 1); one hand = one swing as before; Auto Shot unchanged. A haste change (UNIT_ATTACK_SPEED)
-- rescales a running swing; a hand swapped mid-fight (WEAPON_SLOT_CHANGED / PLAYER_EQUIPMENT_CHANGED 16 / 17) restarts its swing at
-- the weapon now held, an off hand left with no weapon drops its swing. Plain reads only. At "SWING-BEGIN".
-- cb12 PET RECALL ONLY (the user, 2026-11-18: "I like the pet recall but I think we need to remove the pet smart attack, its obnoxious"): cb11's Smart pet
-- casts are gone - the tap-to-cast bar, the class wheels and the touch-cast cells cast a harmful spell as the plain spell again (no
-- /petattack text, no WC.padMap.smartPetCast / smartPetCmd, no WC.spResync); exactly cb10's cells. Nothing was ever saved.
-- nbar4 THE 11-16 TEST BUILD MERGE: the macro-GCD nbar3 (PadMap m165) and feat/big-layout's nbar3 (the bar in the layout editor) both kept.
-- nbar3 (PadMap m165; the user, 2026-11-16: "when using show tooltip on a macro it doesn't actually show the GCD when using it"): a
-- player's macro on the In combat layer is drawn as the spell it would cast now (its picture, its cooldown / GCD sweep by that spell's
-- duration object, its tints; an item's macro its picture): refEntry, from PadMap's look (`ms`, WC.padMap.macLook).
-- nbar2 (review of nbar1, issue #195: "exactly what a press casts"): a form's own In-combat spell first in a fight (PadMap m55 formFight);
-- the fight pictures only while Combat controls are on (WC.padMap.combatOn) and on the ROUTER's own switch (WC.tgGrown: the fight or an
-- attack / auto-repeat armed, not given back by B - PadMap's "sw"); commands and bindings on the bare D-pad drawn; the glyphs by the
-- button's own letter (PadMap m119 / m120, the user: "the inspect glyph is Y not X"; the app's rev_* art) in ONE helper Hub shares
-- (WC.padGlyphAtlas), the faces at the ABXY location (swap: Y left, X top, A right, B bottom, as CastView); an atlas this client refuses
-- falls to the next / the letter (WC.prd.atlasOn); ours follows the stock bar's visibility and scale (UI_SCALE_CHANGED /
-- DISPLAY_SIZE_CHANGED, a window taking the pad) and keeps its last spot when a fight hides the centre.
-- nbar3 THE NINTENDO BAR IN THE LAYOUT EDITOR (the user, 2026-11-16:
-- "we need to make a way to basically edit the UI locations on the top screen from our UI menu"): the bar stands at the stock bar's centre and scale as before, PLUS the layout's offset (dx, dy 1080-line
-- pixels) and size (s %) - TapPad pad22 WC.lay.get("nbar"), per character; no entry = the stock spot. NB.lay is the piece Hub's editor
-- gathers (its anchor the bar's centre). The stock bar itself is the game's and is never moved.
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
-- cb9 ANY CHOOSER KIND (the user, 2026-11-13: "in the controller menu we need a Tab for the cast bar if its enabled, where people can
-- assign anything from the 5 tabs"): a slot holds a spell, an item, a mount or a command ({ k, id / key } per slot, an old number = a
-- spell, migrated at the login), cast by our own cell (spell / item / a one-line macrotext on OUR button); the Controller room's Cast
-- bar tab edits it (CB.put / CB.clear / CB.move, PadTabCtl p53). At "cb9 ANY CHOOSER KIND".
-- cb13 A PLAYER'S MACRO ON THE BAR (GitHub #553, a player: "I cannot assign macros to the bottom screen cast bar"): a slot holds one of
-- the player's own saved macros, BY NAME ({ k = "macro", n }), put there from the Macros room's Bind box (Book b69: its Cast bar tab,
-- WC.castBarMacro); the cell is our own button with the macro's own text read live (type macro, macrotext: PadMap m163's way, no
-- saved-macro attribute), re-armed on UPDATE_MACROS out of combat; a gone macro = no cell (`miss`). Nothing of the player's written.
-- gws2 (issue #306, 1.0.32: the pet wheels' stick "dont respond ... or require bashing B"; the user's pick 2026-11-16: "if the game's own
-- pet/spell flyout ... is still shown ~0.5 s after our class wheel lets go and no room holds the pad, make it visible (alpha 1) and close
-- it once more the safe way"): a game wheel WE drew at alpha 0 that outlives the chord is drawn again and shut once more by our secure
-- cell (the app's tap, document "gamewheel"). At "b-game-wheel-shut2".
-- pa1 THE PET'S AUTOCAST ON THE CLASS WHEEL (the user, 2026-11-16: "hunters need to be able to visually see when their pets abilities
-- are active like growl, and a way to turn it off from those radials"): a pet spell's entry on the right class radial (hud.ca.r) also
-- says whether it MAY cast itself (`aa`, GetPetActionInfo's autoCastAllowed) beside `auto` (it does), both read through S() now; the
-- app draws the game's autocast look (corners; the marching gold border while on) and X on the pointed spell taps its classr autocast
-- cell (16-23, unchanged: the game's own /petautocasttoggle on our button), as a long press does.
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

-- nbar12 (the review, 11-22): the plain top bar's OWN fixed button at [p] (5-8: Interact / Inspect / Back / Jump) as resolveAll describes
-- it, whatever bar overrides the base layer now (a form's stance bar, a vehicle's possess bar, the special page): Party mode's B (Back)
-- and its pre-cfg Y (Inspect) are these on every layer. nil past the top bar's four or without one. Reads only
local function topFixed(pu, p)
    if not FX[p] then return nil end
    local top = field(field(pu, "actionBars"), "topBar")
    if top == nil then return nil end
    local d = describe(barButtons(top)[p], p, "fx")
    local lv = LIVE.n[p]
    if lv and d.btn ~= nil then
        d.live = lv
        d.k, d.e = "fixed", true
    end
    return d
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
                e.auto = bit01(SV(r[7]) == true)
                -- pa1: it MAY cast itself (the game's autoCastAllowed): the app draws the game's autocast corners, plain while off
                e.aa = bit01(SV(r[6]) == true)
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
-- GAMEWHEEL-BEGIN (tools/test_game_wheel.py runs this block on its own: it uses only WC's readers, CR and the game's globals)
local GF_COL = 24
local GF_WHEELS = { "GamepadPetActionFlyout", "GamepadPetSpellFlyout", "GamepadSpellFlyout" }
local GF = { btn = nil, refs = 0, hooked = {}, watch = {}, polling = false, n = 0 }
local function gfChordHeld()
    local function down(k) return type(k) == "string" and S(function() return IsKeyDown(k) end) == true end
    local L, LT = rawget(_G, "GAMEPAD_SHOULDER_LEFT") or "PADLSHOULDER", rawget(_G, "GAMEPAD_TRIGGER_LEFT") or "PADLTRIGGER"
    local R, RT = rawget(_G, "GAMEPAD_SHOULDER_RIGHT") or "PADRSHOULDER", rawget(_G, "GAMEPAD_TRIGGER_RIGHT") or "PADRTRIGGER"
    return (down(L) and down(LT)) or (down(R) and down(RT))
end

-- b-game-wheel-shut2 (gws2; issue #306, 1.0.32: the pet wheels' stick "dont respond ... or require bashing B"; the user's pick 2026-11-16:
-- "if the game's own pet/spell flyout ... is still shown ~0.5 s after our class wheel lets go and no room holds the pad, make it visible
-- (alpha 1) and close it once more the safe way"). A game wheel WE drew at alpha 0 (it showed while a class chord was held) and that is
-- still up GF_QUIET_S after the chord is let go, with no room holding the pad (the shared capture's holder: our class wheel's own guest
-- "pad:class" holds too), would keep the right stick unseen (its binding group) until a button press. So it is drawn again (alpha 1,
-- SetAlpha only) and the app is asked ONCE per opening to tap the shut cell again (document "gamewheel": `w` the wheel 1-3, `col` the
-- shut cell only while it is built as the shut). The shut stays the ruled route: our secure snippet's Hide reached by the app's tap (a
-- hardware click; fights too) - no Blizzard open / close call, no pad press faked; if that tap is lost too, the wheel is in sight and the
-- game's own close (any pad button) ends it. A wheel the player opened without a chord (a bar button's flyout) is never watched.
local GWV = "gws2"
local GF_QUIET_S, GF_POLL_S = 0.5, 0.1
local function gfHolder()
    local cap = WC.capture
    if type(cap) ~= "table" or type(cap.state) ~= "function" then return nil end
    local ok, st = pcall(cap.state)
    return ok and type(st) == "table" and st.holder or nil
end
local gfPoll
local function gfPollSoon()
    if GF.polling then return end
    local t = rawget(_G, "C_Timer")
    if type(t) ~= "table" or type(t.After) ~= "function" then return end
    GF.polling = pcall(t.After, GF_POLL_S, function() pcall(gfPoll) end) and true or false
end
gfPoll = function()
    GF.polling = false
    local now = SN(GetTime) or 0
    local more = false
    for w, st in pairs(GF.watch) do
        if S(function() return w:IsShown() end) ~= true then
            GF.watch[w] = nil                                    -- gone (the shut landed, or the game closed it): nothing to do
        else
            if gfChordHeld() or gfHolder() ~= nil then
                st.quiet = nil                                   -- the chord again, or a room / our class wheel holds: not yet
            else
                st.quiet = st.quiet or now
                if now - st.quiet >= GF_QUIET_S then
                    GF.watch[w] = nil                            -- once per opening: only the next Show watches again
                    pcall(w.SetAlpha, w, 1)
                    local cw = CR.want[GF_COL]
                    GF.n = GF.n + 1
                    WC.send(WC.encode({ type = "gamewheel", v = GWV, w = st.i, n = GF.n,
                                        col = (cw and CR.frozen[GF_COL] == cw.sig) and GF_COL or nil,
                                        fight = (S(InCombatLockdown) == true) or nil }))
                    if type(WC.flush) == "function" then pcall(WC.flush) else WC.push() end
                end
            end
            if GF.watch[w] then more = true end
        end
    end
    if more then gfPollSoon() end
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
                if WC.classWheelOn and gfChordHeld() then
                    pcall(self.SetAlpha, self, 0)
                    GF.watch[self] = { i = i }                     -- gws2: drawn unseen by us: watched until it goes
                    gfPollSoon()
                else
                    GF.watch[self] = nil                             -- the player's own (a bar button's flyout): never watched
                    pcall(self.SetAlpha, self, 1)
                end
            end)
        end
    end
    return GF.btn
end
WC.gameWheelShut = { col = GF_COL, setup = gfSetup, v = GWV }
-- GAMEWHEEL-END
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
-- cb9 ANY CHOOSER KIND ON THE BAR (the user, 2026-11-13: "in the controller menu we need a Tab for the cast bar if its enabled, where
-- people can assign anything from the 5 tabs"): a slot holds what the Controller room's chooser offers on its five tabs - a spell
-- (Spells, General, Professions), an Extra entry (a command, a mount, a bag item) or a DS command - the game bindings excepted (a key,
-- not a cast). THE STORE: WowCompanionSpellsDB.castBar[name-realm][slot] = { k = "spell" | "item" | "mount" | "command", id = the
-- spell / item / mount id, key = the command's key }; a cb1-cb8 number (a spell id) becomes { k = "spell", id } at the login
-- (cbMigrate), and reads as one before that. THE CELL, our own secure button as the sticks' (PadMap m157), set out of combat only: a
-- spell type = "spell" (its id, as before); an item type = "item", item = "item:<id>" (the Bags row's way); a mount type = "macro",
-- "/cast <its summon spell>" and a command type = "macro", the command's own line (PadMap's list: WC.padMap.castBarCmd) - macrotext on
-- OUR button only, never a saved macro, nothing of the player's touched. THE ROOM'S EDITS (PadTabCtl p53): CB.put(slot, kind, v),
-- CB.clear(slot), CB.move(from, to) (a swap when both hold something); out of combat they go on at once, in a fight they wait in the
-- queue as the Spellbook's toggles always did. The document says each slot's kind (`k`, none = a spell, as before), its name (`n`, not
-- for a spell: the app has the book), an item's count (`ct`, `cons` = 1 for a consumable) and `any` = 1 (this bar takes every kind).
-- cb14 THE IN-COMBAT LAYER (the user, 2026-11-19, the Controller / Tap to cast reorganisation, D10: "per-slot overrides"): a second
-- store of 8, WowCompanionSpellsDB.castBarFight[name-realm] (the same entries as castBar), edited from the Controller room's In combat >
-- Tap to cast and the Bind box's In combat + Tap to cast (CB.put / clear / move / at with the layer "f"). An empty fight slot keeps
-- the out-of-combat thing; a set one replaces it in fights. THE SWITCH IN A FIGHT IS SECURE: the row grows from 9 cells to 17 (slots
-- 0-7, the pick 8, the fight cells 9-16 = fight slot 1-8: CB_N + 1 + CB_N, within TapPad's 40 columns, no new row); every slot cell
-- and every fight cell carries BOTH things as attributes set out of combat ("wc-n-*" the out-of-combat thing, "wc-f-*" the fight
-- thing, armed only while the app's "Show in combat" is on), and a pre-click snippet of OUR OWN header (SecureHandlerWrapScript, the
-- router's way, PadMap m20) copies the fight set onto type / spell / item / macrotext while [combat] holds (SecureCmdOptionParse, read
-- at the press), else the out-of-combat set: no attribute is written from insecure code in a fight, no binding, macro or CVar. A
-- fight cell's own attributes are the fight thing (the app taps cell 8 + i while Home shows a fight and fight slot i is set: the
-- proposal's route; the snippet makes either cell right whichever the app taps). SHOW IN COMBAT (the app's Settings / the room's
-- check box, HomeSettings.castBarCombat, told by Book b70's padtab 39 then 2 / 3, kept in WowCompanionSpellsDB.castBarShow): while
-- it is off the fight set is never armed (the bar is hidden in fights anyway) and a fight-layer edit is refused "cb-hidden"; the
-- fight slots are KEPT, unused, and come back armed when it is on again (D5). The document says `show` = 1 and the fight slots `f`
-- (the same entries as `e`; `c` = the fight cell while it is built).
-- cb15 THE PARTY MODE LAYER (the user, 2026-11-20, the Controller room's Option C "Situations first" with Party mode's tap-to-cast bar):
-- a third store of 8, WowCompanionSpellsDB.castBarParty[name-realm], edited from the room's Party mode page and the Bind box's Party
-- mode situation (CB.put / clear / move / at with the layer "p"). THE RULE, PER SLOT: Party (while Party mode is on) > In combat (in a
-- fight, Show in combat on) > the slot's own; an empty slot falls through. A Party slot's SPELL casts on Party mode's pick (the soft
-- target), or on yourself with no pick; an item, a mount, a command or a macro there is used as normal. DECIDED AT THE PRESS, SECURELY:
-- the row grows to 25 cells (the slots 0-7, the pick 8, the fight cells 9-16, the party cells 17-24: CB_N + 1 + 2 x CB_N, within
-- TapPad's 40); every slot / fight / party cell carries the party thing too ("wc-p-*", armed while the app's "Bar in Party mode" is
-- on) and a frame ref to Party mode's own pad (WowCompanionPartyPad, PadMap m77: its "wc-party" is Party mode on / off and "wc-u" the
-- pick, both written only by Party mode's restricted snippets and its out-of-combat set, in fights too). The pre-click snippet reads
-- them at the press in the restricted environment (GetFrameRef + GetAttribute) and copies the party set with unit = the pick, else
-- the cb14 [combat] rule: no insecure read or write in a fight. BAR IN PARTY MODE (the app's HomeSettings.castBarPartyShow, on by
-- default; Book b71's padtab 39, 38 then 2 / 3, kept in WowCompanionSpellsDB.castBarPShow: false = off, never told = on): off = the
-- party set is never armed and a party-layer edit is refused "cb-phidden"; the party slots are KEPT (the D5 rule). The document says
-- `p` (the party slots, their cells 17-24 while built), `ps` = 1 while Bar in Party mode is on, `pl` = 1 (this add-on has the layer).
do
local CBV = "cb16"     -- cb16 NO PARTIAL CHARACTER KEY (the 11-20 hunter, test build 1513: an empty GetRealmName() put castBar / castBarFight / castBarParty under "Name-?"): cbWho() is Safety k6's WC.charKey(), nil until the realm is known; cb15 THE PARTY MODE LAYER (header: castBarParty, 25 cells, the secure Party > In combat > own swap, unit = Party mode's pick, `p` / `ps` / `pl`); cb14 THE IN-COMBAT LAYER (header: castBarFight, 17 cells, the secure [combat] swap, `f` / `show`); cb13 A PLAYER'S MACRO (#553): { k = "macro", n } by name, its own text as our cell's macrotext (Book b69 puts it, WC.castBarMacro); cb12 PET RECALL ONLY (header: cb11's Smart pet casts removed, cb10's cells again); cb10 PET SPELLS (#234, the user: "build it"): a pet's spell on the bar (the Bind box's Cast bar tab, the Controller room's chooser) is cast by its NAME (CastSpellByName, the game's own /cast; WC.spellsPetName from Spells s10), never CastSpellByID
local CB_PADTAB_PICK = { 39, 19 }        -- cb2: padtab 39 (the one-shot pick) then 19 (its cast bar code): Book b55 -> WC.castBarPick
local CB_N, CB_PICK_COL, CB_PICK_S = 8, 8, 2   -- cb6: 8 slots (cells 0-7), the pick cell 8
local CB_OLD_N = 12                      -- cb3: the slots a cb1 / cb2 store may hold (cb6: migrated to the first 8)
local CB_F0 = CB_N                       -- cb14: fight slot i's cell = CB_F0 + i (9-16)
local CB_P0 = CB_N + CB_N                -- cb15: party slot i's cell = CB_P0 + i (17-24)
local CB_CELLS = CB_N + 1 + CB_N + CB_N  -- cb15: 25 cells (the slots, the pick, the fight slots, the party slots); cb14's 17
local CB_FIELD = { f = "castBarFight", p = "castBarParty" }  -- cb14 / cb15: the layer's store (nil = the out-of-combat bar, castBar)
local CB_PAD = "WowCompanionPartyPad"    -- cb15: Party mode's pad (PadMap m77): "wc-party" on / off, "wc-u" the pick
local S, SN, CALL = WC.S, WC.SN, WC.CALL
local CB = { want = {}, frozen = {}, row = nil, sig = nil, pick = nil, queue = {}, ready = false, cd = {}, learned = {}, last = nil,
             wrapped = {}, hdr = nil }
WC.castBar = CB
local function cbReal(v) return v ~= nil and v ~= WC.ABSENT and v ~= WC.SECRET and v ~= WC.THREW end
local function cbStr(fn) local v = S(fn); if type(v) == "string" and cbReal(v) and v ~= "" then return v end end
local function cbFight() return S(InCombatLockdown) ~= false end           -- unknown counts as a fight (nothing secure is touched)
local function cbNow() return SN(GetTime) or 0 end
local function cbSpellApi(name) local t = rawget(_G, "C_Spell"); return type(t) == "table" and t[name] or nil end
local function cbApi(t, name) local x = rawget(_G, t); return type(x) == "table" and x[name] or nil end
local function cbId(v) return type(v) == "number" and v > 0 and v == math.floor(v) end

-- cb9: a slot's thing from what is stored (a cb1-cb8 number = a spell); nil = empty or unreadable
local CB_KINDS = { spell = true, item = true, mount = true }
local function cbEntry(v)
    if cbId(v) then return { k = "spell", id = v } end
    if type(v) ~= "table" then return nil end
    local k = rawget(v, "k")
    if k == "command" then
        local key = rawget(v, "key")
        if type(key) == "string" and key ~= "" then return { k = "command", key = key } end
        return nil
    end
    if k == "macro" then                                                 -- cb13: a player's macro, by its name
        local n = rawget(v, "n")
        if type(n) == "string" and n ~= "" then return { k = "macro", n = n } end
        return nil
    end
    if CB_KINDS[k] and cbId(rawget(v, "id")) then return { k = k, id = rawget(v, "id") } end
    return nil
end
local function cbSame(a, b) return a ~= nil and b ~= nil and a.k == b.k and a.id == b.id and a.key == b.key and a.n == b.n end
local function cbSpell(e) return e ~= nil and e.k == "spell" and e.id or nil end
local function cbKeep(e) return e and { k = e.k, id = e.id, key = e.key, n = e.n } or nil end    -- what the store keeps (cb13: a macro's name only)

-- the character: name-realm (every per-character store's key)
local function cbWho()
    return (WC.charKey())                                               -- cb16: nil until the realm is known (never "Name-?")
end
-- this character's slots table; create = make it (only after the login: the saved table is loaded by then); cb14: [layer] "f" = the
-- in-combat layer's (castBarFight), nil = the bar's own
local function cbStore(create, layer)
    local field = CB_FIELD[layer] or "castBar"
    local key = cbWho()
    if not key then return nil end
    local db = rawget(_G, "WowCompanionSpellsDB")
    if type(db) ~= "table" then
        if not (create and CB.ready) then return nil end
        db = {}
        _G.WowCompanionSpellsDB = db
    end
    if type(db[field]) ~= "table" then
        if not create then return nil end
        db[field] = {}
    end
    local s = db[field][key]
    if type(s) ~= "table" then
        if not create then return nil end
        s = {}
        db[field][key] = s
    end
    return s
end
-- cb14: the app's "Show in combat" (Book b70: padtab 39 then 2 on / 3 off), kept with the add-on's copy of the app's settings
local function cbShow()
    local db = rawget(_G, "WowCompanionSpellsDB")
    return type(db) == "table" and rawget(db, "castBarShow") == true
end
CB.show = cbShow
-- cb15: the app's "Bar in Party mode" (Book b71: padtab 39, 38 then 2 on / 3 off): on unless told off (castBarPShow = false)
local function cbPShow()
    local db = rawget(_G, "WowCompanionSpellsDB")
    return not (type(db) == "table" and rawget(db, "castBarPShow") == false)
end
CB.pshow = cbPShow
-- cb15: Party mode's pad frame (made by PadMap out of combat; nil until then) and whether Party mode is on now (a plain READ of its
-- attribute: never written here)
local function cbPad() local f = rawget(_G, CB_PAD); return type(f) == "table" and f or nil end
local function cbPartyOn()
    local f = cbPad()
    return f ~= nil and S(function() return f:GetAttribute("wc-party") end) == true
end
CB.partyOn = cbPartyOn
-- cb3: an old store (slots past CB_N) keeps its first CB_N things on slots 1-CB_N, in slot order (a repeat dropped); every slot past
-- CB_N cleared. cb6: CB_N = 8 (a cb3 store of 6 has nothing past 8: left as it is). cb9: a spell id kept as a number becomes { k, id }
local function cbMigrate()
    local s = cbStore(false)
    if not s then return false end
    local changed = false
    local past = false
    for i = CB_N + 1, CB_OLD_N do if rawget(s, i) ~= nil then past = true end end
    if past then
        local keep = {}
        for i = 1, CB_OLD_N do
            local e = cbEntry(rawget(s, i))
            local dup = false
            for _, k in ipairs(keep) do if cbSame(k, e) then dup = true end end
            if e and not dup and #keep < CB_N then keep[#keep + 1] = e end
        end
        for i = 1, CB_OLD_N do s[i] = cbKeep(keep[i]) end
        changed = true
    end
    for i = 1, CB_N do
        local v = rawget(s, i)
        if v ~= nil and type(v) ~= "table" then s[i] = cbKeep(cbEntry(v)); changed = true end
    end
    return changed
end
CB.migrate = cbMigrate
-- the stored slots, 1..CB_N (a bad entry reads as empty); cb14: [layer] as cbStore's
local function cbSlots(layer)
    local out, s = {}, cbStore(false, layer)
    if s then for i = 1, CB_N do out[i] = cbEntry(rawget(s, i)) end end
    return out
end
-- ONE edit on a copy of the slots. The Spellbook's (cb1-cb6, spells): a spell id = the toggle (off its slot when it is on the bar, else
-- on the first free slot); { id, s } = cb3's slot pick (on slot s, off any other slot; already there = off it). The room's (cb9):
-- { op = "put", s, e } (e on slot s, off any other slot it was on), { op = "clear", s }, { op = "move", a, b } (a swap when b holds one)
local function cbApply(slots, ed)
    if type(ed) == "table" and ed.op == "put" then
        for i = 1, CB_N do if cbSame(slots[i], ed.e) then slots[i] = nil end end
        slots[ed.s] = ed.e
        return "put", ed.s
    elseif type(ed) == "table" and ed.op == "clear" then
        if slots[ed.s] == nil then return nil, nil, "empty" end
        slots[ed.s] = nil
        return "clear", ed.s
    elseif type(ed) == "table" and ed.op == "move" then
        if ed.a == ed.b then return nil, nil, "same" end
        if slots[ed.a] == nil then return nil, nil, "empty" end
        local swap = slots[ed.b] ~= nil
        slots[ed.a], slots[ed.b] = slots[ed.b], slots[ed.a]
        return swap and "swap" or "move", ed.b
    elseif type(ed) == "table" then
        local e, at = { k = "spell", id = ed.id }, ed.s
        if cbSame(slots[at], e) then slots[at] = nil; return "remove", at end
        for i = 1, CB_N do if cbSame(slots[i], e) then slots[i] = nil end end
        slots[at] = e
        return "add", at
    end
    local e = { k = "spell", id = ed }
    for i = 1, CB_N do if cbSame(slots[i], e) then slots[i] = nil; return "remove", i end end
    for i = 1, CB_N do if slots[i] == nil then slots[i] = e; return "add", i end end
    return nil, nil, "full"
end
-- the slots as they will be once the queue is applied, and the slots the queue changes (q: what differs from the store now)
local function edLayer(ed) return type(ed) == "table" and ed.layer or nil end      -- cb14: an edit's layer (a toggle's number: the bar)
local function cbView(layer)
    local v, q = {}, {}
    local base = cbSlots(layer)
    for i = 1, CB_N do v[i] = base[i] end
    for _, ed in ipairs(CB.queue) do if edLayer(ed) == layer then cbApply(v, ed) end end
    if #CB.queue > 0 then
        for i = 1, CB_N do
            if not ((v[i] == nil and base[i] == nil) or cbSame(v[i], base[i])) then q[i] = true end
        end
    end
    return v, q
end
CB.at = function(slot, layer) local v = cbView(layer); return cbKeep(v[slot]) end     -- PadTabCtl p53: the room's slot under the cursor (cb14: [layer])
-- cb14: what each slot does NOW: in a fight with Show on, a set fight slot's thing, else the slot's own (the cooldowns follow it)
-- cb15: Party mode on (Bar in Party mode on): a set party slot's thing first (Party > In combat > own)
local function cbLiveView()
    local v = cbView()
    if cbFight() and cbShow() then
        local f = cbView("f")
        for i = 1, CB_N do if f[i] then v[i] = f[i] end end
    end
    if cbPShow() and cbPartyOn() then
        local p = cbView("p")
        for i = 1, CB_N do if p[i] then v[i] = p[i] end end
    end
    return v
end
CB.liveView = function() local v = cbLiveView(); local out = {}; for i = 1, CB_N do out[i] = cbKeep(v[i]) end; return out end

-- cb9: what a thing is called and looks like (read live; nil when the game does not say it plainly)
local function cbNum(fn) local v = SN(fn); if cbId(v) then return v end end
local function cbCmd(key)
    local pm = WC.padMap
    if type(pm) ~= "table" or type(pm.castBarCmd) ~= "function" then return nil end
    local ok, c = pcall(pm.castBarCmd, key)
    return (ok and type(c) == "table" and type(c.body) == "string" and c.body ~= "") and c or nil
end
local function cbMount(id)
    local get = cbApi("C_MountJournal", "GetMountInfoByID")
    if type(get) ~= "function" then return nil end
    local ok, name, sid, icon = CALL(get, id)
    if not ok then return nil end
    local n = cbStr(function() return name end)
    local sp = cbNum(function() return sid end)
    if not (n and sp) then return nil end
    return { n = n, sid = sp, sp = cbStr(function() return cbSpellApi("GetSpellName")(sp) end), i = cbNum(function() return icon end) }
end
local function cbItemName(id) return cbStr(function() return cbApi("C_Item", "GetItemNameByID")(id) end) end
local function cbItemIcon(id) return cbNum(function() return cbApi("C_Item", "GetItemIconByID")(id) end) end
local function cbItemCount(id)
    local v = SN(function()
        local f = cbApi("C_Item", "GetItemCount")
        if type(f) == "function" then return f(id) end
        return GetItemCount(id)
    end)
    return type(v) == "number" and v >= 0 and math.floor(v) or nil
end
-- cb13: the player's macro by NAME, read only: { n, i icon, body } (the body plain and not empty), or nil (no such macro, unreadable)
local function cbMacro(name)
    if type(name) ~= "string" or name == "" then return nil end
    local mi = SN(function() return GetMacroIndexByName(name) end)
    if not (cbId(mi)) then return nil end
    local ok, n, icon, body = CALL(GetMacroInfo, mi)
    if not ok then return nil end
    body = cbStr(function() return body end)
    if not (body and body:find("%S")) then return nil end
    local i = cbNum(function() return icon end)
    local pm = WC.padMap                                                 -- PadMap m165: what a #showtooltip macro would cast now
    if type(pm) == "table" and type(pm.macLook) == "function" then
        local okl, lk = pcall(pm.macLook, name)
        if okl and type(lk) == "table" and cbId(lk.i) then i = lk.i end
    end
    return { n = cbStr(function() return n end) or name, i = i, body = body }
end
-- the cell's line for a macro kind (a mount's /cast, a command's own line; cb13 a player's macro's own text); nil = none can be made now
local function cbLine(e)
    if e.k == "macro" then
        local m = cbMacro(e.n)
        return m and m.body or nil
    elseif e.k == "mount" then
        local m = cbMount(e.id)
        return m and m.sp and ("/cast " .. m.sp) or nil
    elseif e.k == "command" then
        local c = cbCmd(e.key)
        return c and c.body or nil
    end
    return nil
end

-- the cells: 0-7 the slots' things, 8 the pick (no action: it runs on the press, as a plain cell); cb14: 9-16 the fight slots
-- a thing's secure attributes: type, spell, item, macrotext (all nil = no action)
local function cbAttrs(e, line)
    if e and e.k == "spell" then
        local spell = e.id
        local fn = rawget(WC, "spellsPetName")                         -- cb10: a pet's spell, by its name
        local okn, pn = false, nil
        if type(fn) == "function" then okn, pn = pcall(fn, e.id) end
        if okn and type(pn) == "string" then spell = pn end
        return "spell", spell, nil, nil
    elseif e and e.k == "item" then return "item", nil, "item:" .. e.id, nil
    elseif e and line then return "macro", nil, nil, line end
    return nil, nil, nil, nil
end
-- cb14 THE SECURE SWAP (OUR OWN header; the router's way, PadMap m20): at every press the cell copies its fight set while the game says
-- [combat] (read at the press, in the restricted environment), else its out-of-combat set. Attributes only: allowed in a fight
-- cb15: FIRST the party set, while Party mode's pad (the frame ref "wc-pad") says "wc-party": a spell there casts on the pad's pick
-- ("wc-u": you, your pet or party1-4; you with no pick); an item / a mount / a command / a macro has no unit. An empty party slot
-- (no "wc-p-type") falls through to the cb14 rule, then the slot's own
CB.SWAP = [=[
    local p = "wc-n-"
    local u = nil
    if self:GetAttribute("wc-p-type") then
        local pad = self:GetFrameRef("wc-pad")
        if pad and pad:GetAttribute("wc-party") then
            p = "wc-p-"
            u = pad:GetAttribute("wc-u") or "player"
        end
    end
    if p == "wc-n-" and self:GetAttribute("wc-f-type") and SecureCmdOptionParse("[combat] 1") == "1" then p = "wc-f-" end
    self:SetAttribute("type", self:GetAttribute(p .. "type"))
    self:SetAttribute("spell", self:GetAttribute(p .. "spell"))
    self:SetAttribute("item", self:GetAttribute(p .. "item"))
    self:SetAttribute("macrotext", self:GetAttribute(p .. "macrotext"))
    if p == "wc-p-" and self:GetAttribute("wc-p-type") == "spell" then self:SetAttribute("unit", u) else self:SetAttribute("unit", nil) end
]=]
local function cbHeader()
    if CB.hdr then return CB.hdr end
    if cbFight() then return nil end
    local ok, h = pcall(CreateFrame, "Frame", "WowCompanionCastBarHdr", UIParent, "SecureHandlerBaseTemplate")
    CB.hdr = ok and h or nil
    return CB.hdr
end
local function cbWrap(btn)
    if CB.wrapped[btn] or cbFight() or type(rawget(_G, "SecureHandlerWrapScript")) ~= "function" then return end
    local h = cbHeader()
    if h and pcall(SecureHandlerWrapScript, btn, "OnClick", h, CB.SWAP) then CB.wrapped[btn] = true end
end
local function cbInit(btn, col)
    local w = CB.want[col]
    local nt, ns, ni, nm = cbAttrs(w and w.e, w and w.line)
    local ft, fs, fi, fm = cbAttrs(w and w.fe, w and w.fline)
    local pt, ps, pi, pm = cbAttrs(w and w.pe, w and w.pline)          -- cb15: the party thing
    local fightCell = col > CB_F0 and col <= CB_P0
    local partyCell = col > CB_P0                                      -- cb15: a party cell's own: the party thing
    local typ = partyCell and pt or fightCell and ft or nt             -- a fight cell's own: the fight thing
    btn:SetAttribute("type", typ)
    btn:SetAttribute("spell", partyCell and ps or fightCell and fs or ns)
    btn:SetAttribute("item", partyCell and pi or fightCell and fi or ni)
    btn:SetAttribute("macrotext", partyCell and pm or fightCell and fm or nm)
    btn:SetAttribute("unit", nil)
    if col ~= CB_PICK_COL then
        btn:SetAttribute("wc-n-type", nt); btn:SetAttribute("wc-n-spell", ns); btn:SetAttribute("wc-n-item", ni); btn:SetAttribute("wc-n-macrotext", nm)
        btn:SetAttribute("wc-f-type", ft); btn:SetAttribute("wc-f-spell", fs); btn:SetAttribute("wc-f-item", fi); btn:SetAttribute("wc-f-macrotext", fm)
        btn:SetAttribute("wc-p-type", pt); btn:SetAttribute("wc-p-spell", ps); btn:SetAttribute("wc-p-item", pi); btn:SetAttribute("wc-p-macrotext", pm)
        -- cb15: Party mode's pad by a frame ref (set out of combat, here only), read by the snippet at the press
        local pad, ref = cbPad(), rawget(_G, "SecureHandlerSetFrameRef")
        if pad and type(ref) == "function" then pcall(ref, btn, "wc-pad", pad) end
        cbWrap(btn)
    end
    CB.frozen[col] = (typ or ft or pt) and w.sig or nil
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
-- the thing's signature on its cell (a macro kind's line in it: a changed line builds again)
local function cbSig(e, line)
    if e.k == "command" then return "c" .. e.key .. "|" .. (line or "") end
    if e.k == "mount" then return "m" .. e.id .. "|" .. (line or "") end
    if e.k == "macro" then return "M" .. e.n .. "|" .. (line or "") end      -- cb13: an edited macro builds again
    return e.k:sub(1, 1) .. e.id
end
-- (re)build the row when the stored slots changed; out of combat only. cb2: no row is registered for an empty bar (the row once
-- registered stays, emptied: TapPad cannot give it back); the Spellbook's pick works without it (padtab 39 then 19).
function CB.sync()
    if cbFight() then return false end
    local pad = WC.pad
    if type(pad) ~= "table" or type(pad.secureRow) ~= "function" then return false end
    local slots = cbSlots()
    local fslots = cbShow() and cbSlots("f") or {}                      -- cb14: the fight set only while Show in combat is on
    local pslots = cbPShow() and cbSlots("p") or {}                     -- cb15: the party set only while Bar in Party mode is on
    local want, parts = {}, {}
    local anyP = false
    local function lineOf(e) return e and (e.k == "mount" or e.k == "command" or e.k == "macro") and cbLine(e) or nil end
    for i = 1, CB_N do
        local e, fe, pe = slots[i], fslots[i], pslots[i]
        if e or fe or pe then
            local line, fline, pline = lineOf(e), lineOf(fe), lineOf(pe)
            local sig = (e and cbSig(e, line) or "-") .. (fe and ("/" .. cbSig(fe, fline)) or "") .. (pe and ("+" .. cbSig(pe, pline)) or "")
            local w = { e = e, line = line, fe = fe, fline = fline, pe = pe, pline = pline, sig = sig }
            want[i - 1] = w
            parts[#parts + 1] = (i - 1) .. "=" .. sig
            if fe then want[CB_F0 + i] = w end                          -- cb14: the fight cell, the same things
            if pe then want[CB_P0 + i] = w; anyP = true end             -- cb15: the party cell, the same things
        end
    end
    -- cb15: Party mode's pad made since the last build (PadMap makes it out of combat, maybe after this row): built again for its ref
    local sig = table.concat(parts, ",") .. ((anyP and cbPad()) and "|P" or "")
    if CB.row and sig == CB.sig then return true end
    if not CB.row and #parts == 0 then                   -- cb2: an empty bar takes no pad row
        CB.want, CB.sig, CB.frozen = {}, nil, {}
        return false
    end
    CB.want = want
    local fresh = CB.row == nil
    local ok, row = pcall(pad.secureRow, "castbar", CB_CELLS, cbInit, cbClick, { onPress = true })
    CB.row = (ok and type(row) == "number") and row or nil
    -- cb2b (review): a row registered mid-session is not in Home's pad copy (it rides on the quests document) until that is sent again
    if fresh and CB.row and type(WC.questsSoon) == "function" then pcall(WC.questsSoon) end
    CB.sig = CB.row and sig or nil
    if not CB.row then CB.frozen = {} end
    return CB.row ~= nil
end

local function cbEntries(layer)
    local v, q = cbView(layer)
    local e = {}
    for i = 1, CB_N do
        local x = v[i]
        local col = layer == "f" and (CB_F0 + i) or layer == "p" and (CB_P0 + i) or (i - 1)        -- cb15: the party cells 17-24
        local w = CB.want[col]
        local built = x ~= nil and w ~= nil and cbSame(layer == "f" and w.fe or layer == "p" and w.pe or w.e, x) and CB.frozen[col] == w.sig
        if x then
            local r = { s = i, c = (built and not q[i]) and col or nil, q = (q[i] or not built) and 1 or nil }
            if x.k == "spell" then
                r.id, r.i = x.id, SN(function() return cbSpellApi("GetSpellTexture")(x.id) end)
            elseif x.k == "item" then
                r.k, r.id, r.n, r.i, r.ct = "item", x.id, cbItemName(x.id), cbItemIcon(x.id), cbItemCount(x.id)
                local cons = cbApi("C_Item", "IsConsumableItem")
                if type(cons) == "function" and S(cons, x.id) == true then r.cons = 1 end
            elseif x.k == "mount" then
                local m = cbMount(x.id)
                r.k, r.id, r.n, r.i, r.sid = "mount", x.id, m and m.n, m and m.i, m and m.sid
            elseif x.k == "macro" then                   -- cb13: by name; a gone (or empty) macro has no cell (`miss`)
                local m = cbMacro(x.n)
                r.k, r.n, r.i, r.miss = "macro", x.n, m and m.i or nil, (not m) and 1 or nil
            else
                local c = cbCmd(x.key)
                r.k, r.key, r.n, r.i = "command", x.key, c and type(c.n) == "string" and c.n or nil, c and cbId(c.i) and c.i or nil
            end
            e[#e + 1] = r
        elseif q[i] then
            e[#e + 1] = { s = i, q = 1 }                 -- emptied by a queued edit: dimmed until the fight ends
        end
    end
    return e
end
function CB.doc()
    -- cb14: `f` the fight slots (their cells 9-16 while built: armed only with Show on), `show` = 1 while Show in combat is on,
    -- `fl` = 1 this add-on has the in-combat layer; cb15: `p` the party slots (cells 17-24 while built: armed only with Bar in Party
    -- mode on), `ps` = 1 while it is on, `pl` = 1 this add-on has the party layer
    return { type = "castbar", v = CBV, n = CB_N, any = 1, mac = 1, pick = CB.row and CB_PICK_COL or nil, fight = cbFight() or nil,
             nopad = (not CB.row) and 1 or nil, pp = CB_PADTAB_PICK, e = cbEntries(nil), f = cbEntries("f"), show = cbShow() and 1 or nil,
             fl = 1, p = cbEntries("p"), ps = cbPShow() and 1 or nil, pl = 1 }
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
local function cbAck(ok, why, op, slot, queued, layer)
    if type(WC.encode) ~= "function" or type(WC.send) ~= "function" then return ok end
    WC.send(WC.encode({ type = "castbarack", v = CBV, ok = ok and true or false, why = why, op = op, s = slot, q = queued and 1 or nil,
                        f = layer == "f" and 1 or nil, p = layer == "p" and 1 or nil }))
    return ok
end

-- ONE edit into the store (or, in a fight, the queue): the cells built again, answered, the document sent
local function cbEdit(ed)
    local layer = edLayer(ed)                             -- cb14: nil = the bar, "f" = the in-combat layer
    if cbFight() then
        local v = cbView(layer)
        local op, i, why = cbApply(v, ed)
        if not op then return cbAck(false, why, nil, nil, nil, layer) end
        CB.queue[#CB.queue + 1] = ed
        cbAck(true, nil, op, i, true, layer)
        CB.send()
        return true
    end
    local s = cbStore(true, layer)
    if not s then return cbAck(false, "character", nil, nil, nil, layer) end
    local v = cbSlots(layer)
    local op, i, why = cbApply(v, ed)
    if not op then return cbAck(false, why, nil, nil, nil, layer) end
    for k = 1, CB_N do s[k] = cbKeep(v[k]) end           -- cb3: a slot pick may move the spell off another slot too
    CB.sync()
    cbAck(true, nil, op, i, nil, layer)
    CB.send()
    return true
end
local function cbSlotOk(slot) return type(slot) == "number" and slot >= 1 and slot <= CB_N and slot == math.floor(slot) end
-- a spell the bar can cast: a known, active spell's id
local function cbSpellOk(id)
    if not cbId(id) then return false, "spell" end
    if not cbStr(function() return cbSpellApi("GetSpellName")(id) end) then return false, "spell" end
    local passive = cbSpellApi("IsSpellPassive") or rawget(_G, "IsPassiveSpell")
    if type(passive) == "function" and S(passive, id) == true then return false, "passive" end
    return true
end

-- ONE edit: the spell on the bar or off it (the Spellbook's: spells only). In a fight it waits for the fight's end.
-- cb3: [slot] 1-CB_N = on that slot (cbApply's { id, s }); nil = the first free slot / off again
function CB.toggle(id, slot)
    if type(id) ~= "number" or id <= 0 then return cbAck(false, "spell") end
    if slot ~= nil and not cbSlotOk(slot) then return cbAck(false, "slot") end
    local okS, why = cbSpellOk(id)
    if not okS then return cbAck(false, why) end
    if not cbWho() then return cbAck(false, "character") end
    return cbEdit(slot and { id = id, s = slot } or id)
end
-- cb9 THE ROOM'S EDITS (PadTabCtl p53, the Controller room's Cast bar tab): [kind] "spell" (a spell id) / "item" (an item id) /
-- "mount" (a Mount Journal id) / "command" (a command's key) onto [slot]; Clear; Move (a swap when the target holds something)
-- cb14: [layer] "f" = the in-combat layer (refused "cb-hidden" while Show in combat is off: its slots are kept, not edited)
-- cb15: [layer] "p" = the Party mode layer (refused "cb-phidden" while Bar in Party mode is off: kept, not edited)
local function cbLocked(layer)
    if layer == "f" and not cbShow() then return "cb-hidden" end
    if layer == "p" and not cbPShow() then return "cb-phidden" end
    return nil
end
local CB_LAYERS = { f = true, p = true }
function CB.put(slot, kind, v, layer)
    if layer ~= nil and not CB_LAYERS[layer] then layer = nil end
    if not cbSlotOk(slot) then return cbAck(false, "slot", "put", nil, nil, layer) end
    if cbLocked(layer) then return cbAck(false, cbLocked(layer), "put", slot, nil, layer) end
    local e
    if kind == "spell" then
        local okS, why = cbSpellOk(v)
        if not okS then return cbAck(false, why, "put", slot, nil, layer) end
        e = { k = "spell", id = v }
    elseif kind == "item" then
        if not (cbId(v) and cbItemName(v)) then return cbAck(false, "item", "put", slot, nil, layer) end
        e = { k = "item", id = v }
    elseif kind == "mount" then
        if not (cbId(v) and cbMount(v)) then return cbAck(false, "mount", "put", slot, nil, layer) end
        e = { k = "mount", id = v }
    elseif kind == "command" then
        if not (type(v) == "string" and cbCmd(v)) then return cbAck(false, "command", "put", slot, nil, layer) end
        e = { k = "command", key = v }
    elseif kind == "macro" then                                          -- cb13: the player's macro by name (it must exist, with text)
        if not cbMacro(v) then return cbAck(false, "missing", "put", slot, nil, layer) end
        e = { k = "macro", n = v }
    else
        return cbAck(false, "kind", "put", slot, nil, layer)
    end
    if not cbWho() then return cbAck(false, "character", "put", slot, nil, layer) end
    return cbEdit({ op = "put", s = slot, e = e, layer = layer })
end
function CB.clear(slot, layer)
    if layer ~= nil and not CB_LAYERS[layer] then layer = nil end
    if not cbSlotOk(slot) then return cbAck(false, "slot", "clear", nil, nil, layer) end
    if cbLocked(layer) then return cbAck(false, cbLocked(layer), "clear", slot, nil, layer) end
    if not cbWho() then return cbAck(false, "character", "clear", slot, nil, layer) end
    return cbEdit({ op = "clear", s = slot, layer = layer })
end
function CB.move(a, b, layer)
    if layer ~= nil and not CB_LAYERS[layer] then layer = nil end
    if not (cbSlotOk(a) and cbSlotOk(b)) then return cbAck(false, "slot", "move", nil, nil, layer) end
    if cbLocked(layer) then return cbAck(false, cbLocked(layer), "move", b, nil, layer) end
    if not cbWho() then return cbAck(false, "character", "move", b, nil, layer) end
    return cbEdit({ op = "move", a = a, b = b, layer = layer })
end
-- cb14 (Book b70): the app's Show in combat, any time (no room needed). Out of combat the cells are armed / disarmed at once; in a
-- fight it waits for the fight's end (secure attributes; CB.sync refuses in a fight), the bar is hidden in fights while it is off
function CB.setShow(on)
    local db = rawget(_G, "WowCompanionSpellsDB")
    if type(db) ~= "table" then
        if not CB.ready then return end
        db = {}
        _G.WowCompanionSpellsDB = db
    end
    local was = db.castBarShow == true
    db.castBarShow = on and true or nil
    if was ~= (on and true or false) then
        CB.sync()
        CB.send()
    end
end
WC.castBarShow = CB.setShow
-- cb15 (Book b71): the app's Bar in Party mode, any time (no room needed); out of combat the party set is armed / disarmed at once, in a
-- fight at its end (CB.sync refuses in a fight)
function CB.setPShow(on)
    local db = rawget(_G, "WowCompanionSpellsDB")
    if type(db) ~= "table" then
        if not CB.ready then return end
        db = {}
        _G.WowCompanionSpellsDB = db
    end
    local was = cbPShow()
    if on then db.castBarPShow = nil else db.castBarPShow = false end      -- (false = told off; nil = on, the default)
    if was ~= (on and true or false) then
        CB.sync()
        CB.send()
    end
end
WC.castBarPartyShow = CB.setPShow
-- the fight is over: the queued edits go into the store in their order, the cells are built again
function CB.flushQueue()
    if cbFight() or #CB.queue == 0 then return false end
    local s = cbStore(true)
    local q = CB.queue
    CB.queue = {}
    if s then
        local v = cbSlots()
        for _, ed in ipairs(q) do if edLayer(ed) == nil then cbApply(v, ed) end end
        for i = 1, CB_N do s[i] = cbKeep(v[i]) end
    end
    for _, L in ipairs({ "f", "p" }) do                   -- cb14 / cb15: the in-combat and the Party mode layers' queued edits
        local lq = {}
        for _, ed in ipairs(q) do if edLayer(ed) == L then lq[#lq + 1] = ed end end
        local ls = #lq > 0 and cbStore(true, L) or nil
        if ls then
            local v = cbSlots(L)
            for _, ed in ipairs(lq) do cbApply(v, ed) end
            for i = 1, CB_N do ls[i] = cbKeep(v[i]) end
        end
    end
    return true
end

-- Book b53: the Spellbook's pick was tapped just before (fresh CB_PICK_S): the spell's two taps that follow are ours
-- cb2: the same pick by the padtab row (Book b55: padtab 39 then 19), the castbar row not needed
-- cb3: [slot] 1-CB_N = the Bind box's third tab picked that slot (Book b56 / b57: padtab 39 then 20-25, 27-28); nil = the first free slot
function WC.castBarPick(slot)
    CB.pick = cbNow()
    CB.pickSlot = cbSlotOk(slot) and slot or nil
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
-- cb13 (Book b69): the Macros room's Bind box puts the player's macro [name] on slot [slot] (a put: off any other slot it was on)
function WC.castBarMacro(slot, name) return CB.put(slot, "macro", name) end

-- ---------------------------------------------------------------- the cooldowns ("castcd": the spells' slots; an item's is `itemcd`)
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
-- SPELL_UPDATE_COOLDOWN: each spell slot's spell, its flags now
function CB.cooldowns()
    local now, changed = cbNow(), false
    local v = cbLiveView()                               -- cb14: a fight's set slot casts its fight spell
    local get = cbSpellApi("GetSpellCooldown")
    for i = 1, CB_N do
        local id, cur = cbSpell(v[i]), CB.cd[i]
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
    local v = cbLiveView()
    for i = 1, CB_N do
        if cbSpell(v[i]) == id then
            local d = cbLearned(id)
            if d and d > 1.5 then CB.cd[i] = { id = id, d = d, t0 = now, e = true, cast = true }; hit = true end
        end
    end
    if hit then cbCdSend() end
end

local cbF = CreateFrame("Frame")
for _, ev in ipairs({ "PLAYER_LOGIN", "PLAYER_ENTERING_WORLD", "PLAYER_REGEN_ENABLED", "PLAYER_REGEN_DISABLED",
                      "SPELL_UPDATE_COOLDOWN", "SPELLS_CHANGED", "BAG_UPDATE_DELAYED", "UPDATE_MACROS" }) do pcall(cbF.RegisterEvent, cbF, ev) end
pcall(function() cbF:RegisterUnitEvent("UNIT_SPELLCAST_SUCCEEDED", "player") end)
cbF:SetScript("OnEvent", function(_, ev, a1, a2, a3)
    pcall(function()
        if ev == "PLAYER_LOGIN" or ev == "PLAYER_ENTERING_WORLD" then
            CB.ready = true
            cbMigrate()                                  -- cb3 / cb6: an old 12-slot store keeps its first 8; cb9: numbers -> { k, id }
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
            CB.cooldowns()                               -- cb14: the fight slots' cooldowns now
        elseif ev == "SPELL_UPDATE_COOLDOWN" then
            CB.cooldowns()
        elseif ev == "SPELLS_CHANGED" or ev == "UPDATE_MACROS" then   -- cb13: a macro edited / renamed / deleted: its cell again
            if not cbFight() then CB.sync() end
            CB.send()
        elseif ev == "BAG_UPDATE_DELAYED" then
            CB.send()                                    -- cb9: an item's count (a repeat of the same document is dropped)
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

-- SWING-BEGIN (nbar5; tools/test_swing_dual.py runs this block on its own: it uses only WC's readers and the game's globals)
-- The swing timer (user, 2026-09-29): PLAYER_SWING(duration, type 0 main / 1 off / 2 ranged) is Forever's
-- own swing signal (Blizzard_SwingTimer uses it) and carries no secret flags. The last swing of each type
-- is kept; hudS.sw names ONE: the most recent, Auto Shot (ranged) whenever it is running (user: one line).
-- s = GetTime() the swing started (the app maps the game clock), d = its duration. s changes every swing,
-- so each swing is one state doc, batched in the 0.5 s window like everything else.
-- nbar5 SPLIT SWING TIMERS (the user, 2026-11-16: "Dual weilding needs split swing timers for each weapon"): with a weapon in the off
-- hand, hudS.sw = the MAIN hand's swing (k 0) and hudS.so = the OFF hand's (k 1), each reset by its own PLAYER_SWING with its own
-- length. The off hand counts as held when UnitAttackSpeed("player")'s 2nd return is over 0 (Blizzard_SwingTimer's HasOffHandWeapon);
-- when that read is not plain, an off-hand swing still running (or just due) stands in. Auto Shot running = the one ranged line, as before.
-- Haste: a running swing is rescaled when the weapon's speed changes (UNIT_ATTACK_SPEED; its share done kept, its length times
-- new / old speed) - the game's own bar waits for the next swing; the next PLAYER_SWING carries the hasted length either way.
-- A hand swapped mid-fight (WEAPON_SLOT_CHANGED, as Blizzard_SwingTimer's ResetSwingTimerForEquippedWeapon; PLAYER_EQUIPMENT_CHANGED on
-- slot 16 / 17): its RUNNING swing starts again now at the weapon now held; an off hand with no weapon now drops its swing.
do
    local swings = {}            -- [0 main / 1 off / 2 ranged] = { s = start (GetTime), d = its length, sp = UnitAttackSpeed's then }

    -- UnitAttackSpeed("player")'s [n]th return (1 main, 2 off): a number; 0 = none (absent: no off-hand weapon); nil = not plain
    local function speed(n)
        local v = S(function() return (select(n, UnitAttackSpeed("player"))) end)
        if type(v) == "number" then return v end
        if v == WC.ABSENT then return 0 end
        return nil
    end

    -- a weapon in the off hand now (true / false)
    local function offHeld(now)
        local o = speed(2)
        if o ~= nil then return o > 0 end
        local r = swings[1]
        return r ~= nil and now < r.s + 2 * r.d
    end

    local function pickSwing(now)
        local r = swings[2]
        if r and now < r.s + r.d + 0.3 then return 2, r end
        local best, bk
        for k, v in pairs(swings) do
            if not best or v.s > best.s then best, bk = v, k end
        end
        return bk, best
    end

    function WC.swingNote(dur, kind)
        local d, k = SN(function() return tonumber(dur) end), SN(function() return tonumber(kind) end)
        if not d or not k or d <= 0 then return end
        swings[k] = { s = SN(GetTime) or 0, d = d, sp = (k == 0 or k == 1) and speed(k + 1) or nil }
    end

    -- UNIT_ATTACK_SPEED (player): a running hand's swing follows its weapon's new speed
    function WC.swingSpeed()
        local now = SN(GetTime) or 0
        for k = 0, 1 do
            local r = swings[k]
            local sp = speed(k + 1)
            if r and r.sp and r.sp > 0 and sp and sp > 0 and math.abs(sp - r.sp) > 0.001 and now < r.s + r.d then
                local f = (now - r.s) / r.d
                local d = r.d * sp / r.sp
                swings[k] = { s = now - f * d, d = d, sp = sp }
            end
        end
    end

    -- a weapon swapped (WEAPON_SLOT_CHANGED, or PLAYER_EQUIPMENT_CHANGED with [slot] 16 / 17; nil = both hands)
    function WC.swingWeapons(slot)
        local now = SN(GetTime) or 0
        for k = 0, 1 do
            local r = swings[k]
            if r and (slot == nil or slot == 16 + k) then
                local sp = speed(k + 1)
                if k == 1 and sp == 0 then swings[1] = nil
                elseif sp and sp > 0 and now < r.s + r.d then swings[k] = { s = now, d = sp, sp = sp } end
            end
        end
    end

    local function pack(k, r)
        return { k = k, s = math.floor(r.s * 1000 + 0.5) / 1000, d = math.floor(r.d * 100 + 0.5) / 100 }
    end

    -- hudS.sw, hudS.so (nil = none) at [now]
    function WC.swingDoc(now)
        local r, m, o = swings[2], swings[0], swings[1]
        local ranged = r ~= nil and now < r.s + r.d + 0.3
        if not ranged and (m or o) and offHeld(now) then
            return m and pack(0, m) or nil, o and pack(1, o) or nil
        end
        local k, v = pickSwing(now)
        if not v then return nil end
        return pack(k, v), nil
    end
end
-- SWING-END

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
        -- the bar shows the cast's DISPLAY text (2nd return), as the game's own cast bar does: a quest container's
        -- "Opening - No Text" has an empty display text, so the bar stays blank (the user, 2026-11-19); the
        -- internal name is only the fallback when no display text is given at all (nil)
        local tx = S(function() return select(2, fn("player")) end)
        -- 1504 merge: S answers its ABSENT / THREW sentinel (a one-byte string) for a nil or a throw, never nil itself, so those
        -- count as "no display text" here (the name fallback); an empty "" (and a secret) stay blank
        if tx == WC.ABSENT or tx == WC.THREW then tx = nil end
        if type(tx) == "string" then castNow.nm = realStr(tx)
        else castNow.nm = realStr(S(function() return (fn("player")) end)) end
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
    local edgeIn = WC.regenEdge == "in" and ((SN(GetTime) or 0) - (WC.regenEdgeAt or 0)) < 1
    local st = { c = combat or edgeIn, aa = autoAttack == true, ar = autoRepeat == true, L = L }
    do                                                          -- b-bar-gcd: the running GCD (game time) for the flagged slots
        local g, now = WC.gcdRun, SN(GetTime) or 0
        if g and now < g.s + g.d then st.gd = { s = math.floor(g.s * 1000 + 0.5) / 1000, d = math.floor(g.d * 1000 + 0.5) / 1000 } end
    end
    if castNow then st.cs = castNow
    elseif castEnd and (SN(GetTime) or 0) - castEnd.t < 2 then
        st.ce = { x = castEnd.x, t = math.floor(castEnd.t * 1000 + 0.5) / 1000, h = castEnd.h }
    end
    if combat then
        st.sw, st.so = WC.swingDoc(SN(GetTime) or 0)          -- nbar5: so = the off hand's swing while dual wielding
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
-- nbar11 THE FIGHT'S EDGES AT ONCE (REGEN, auto-attack, auto-repeat): the next frame's build goes out outside the 0.5 s window, so the
-- app's band slides up with the add-on's Wings (about 40-90 ms, not up to 0.6 s). WC.regenEdge "in" from PLAYER_REGEN_DISABLED until
-- ENABLED: the state says the fight even where the handler ran before the lockdown (hudS.c) - for 1 s at most (it only bridges the
-- frames before InCombatLockdown reads true; a missed ENABLED never leaves a fight standing). One extra send per edge at most
function WC.stateEdge(ev)
    if ev == "PLAYER_REGEN_DISABLED" then WC.regenEdge, WC.regenEdgeAt = "in", SN(GetTime) or 0
    elseif ev == "PLAYER_REGEN_ENABLED" then WC.regenEdge = nil end
    WC.edgeDue = true
    if not loggedIn then return end
    C_Timer.After(0, function()
        if not WC.edgeDue then return end
        WC.edgeDue = false
        lastStateAt = -1e9
        emitState(false)
    end)
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
-- nbar6 MOVABLE HOME'S SWAP (the user's rulings 2026-11-20, SPEC.txt section 6 item 5; PadMap m178): the DS toolbar's icons on the bare
-- D-pad are picked by the router's WORD (padmap dpad `d`: after a swap Left is "map"), never by position (NB.TOOLBAR_BY).
-- nbar7: the fight form "Wings" (NB.TOP / NB.top, after WC.nbarSet), the halves placed by NB.hx / NB.hy (the joined bar: -DX / DX)
-- nbar11: B the square (D-pad) slot, BC the round (face) slot - the band's 40 / 38 units x 1.15; INSET the icon inside its ring; GLYPH the
-- badge at the ring's top-right (GLYPH_AT: its centre from the slot's, circles lower as drawSlot's), FOCUS the glow past the slot
local NB = { V = "nbar12", on = false, built = false, B = 46, BC = 44, INSET = 3.5, GLYPH_AT = { 13.2, 13.2, 9.8 }, FOCUS = 30,
             OFF = 50, DX = 160, GLYPH = 17, Y_DEF = 64, S_MIN = 70, S_MAX = 150, S_STEP = 10,
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
             TOOLBAR = { 134332, 134829, 134269, "tex:Interface\\AddOns\\WowCompanion\\Media\\ds_forever_icon" },
             -- nbar6: the same icons by the router's word (PadMap's `d`)
             TOOLBAR_BY = { chat = 134332, radial = 134829, map = 134269, home = "tex:Interface\\AddOns\\WowCompanion\\Media\\ds_forever_icon" },
             dirs = nil }
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
-- nbar11 THE RING GLYPHS (the bottom band's: CastView's Nintendo preset wears the Switch face lettering and the xbox CROSS D-pad - the
-- Thor's D-pad is a cross; the plain layout the xbox letters): first, then the one glyph rule's atlases (WC.padGlyphAtlas, Hub's too:
-- unchanged), then the letter. [pressed] the held look (the xbox set's is "-down", the Switch set's "-pressed")
NB.RING_FACE = { PAD1 = "a", PAD2 = "b", PAD3 = "x", PAD4 = "y" }
NB.RING_DIR = { PADDLEFT = "left", PADDUP = "up", PADDRIGHT = "right", PADDDOWN = "down" }
function NB.RING_GLYPH(key, pressed)
    local db = rawget(_G, "WowCompanionSpellsDB")
    local plain = type(db) == "table" and db.padLayout == "plain"
    local l = NB.RING_FACE[key]
    if l then
        if plain then return "gamepad-xbox1-button" .. l .. (pressed and "-down" or "-normal") end
        return "gamepad-switch-128x-face-" .. l .. (pressed and "-pressed" or "-normal")
    end
    local d = NB.RING_DIR[key]
    if d then return "gamepad-xbox1-dpad" .. d .. (pressed and "-down" or "-normal") end
    return nil
end
function NB.glyphAtlas(key, pressed)
    local out = {}
    local r = NB.RING_GLYPH(key, pressed == true)
    if r then out[1] = r end
    if pressed == true then local n = NB.RING_GLYPH(key, false); if n then out[#out + 1] = n end end
    for _, a in ipairs(WC.padGlyphAtlas(key)) do out[#out + 1] = a end
    return out
end
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
    local h = (p <= 4) and 1 or 2
    local cx = (NB.hx or { -NB.DX, NB.DX })[h]                       -- nbar7: each half's centre (the Wings move them apart)
    local cy = (NB.hy or { 0, 0 })[h]
    pcall(b.ClearAllPoints, b)
    -- nbar11: a held button sits 2 units lower (the band's press)
    b:SetPoint("CENTER", b.nbParent, "CENTER", cx + a[1] * NB.OFF, cy + a[2] * NB.OFF - (b.down and 2 or 0))
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
    -- nbar11: the eight buttons' own held state too (the ring's pressed look; the same plain read, indices looked up once)
    local held = 0
    NB.idx = NB.idx or {}
    for p = 1, 8 do
        local key = NB.KEY_OF[p]
        if NB.idx[key] == nil then NB.idx[key] = NB.padIdx(key) end
        local i = NB.idx[key]
        if i and S(function() return bt[i] end) == true then held = held + 2 ^ (p - 1) end
    end
    NB.held = held
    -- nbar12: the bumpers' own held state (the L / R plates: their pressed look and the targeting pictures they open; the same plain read)
    for _, key in ipairs({ "PADLSHOULDER", "PADRSHOULDER" }) do
        if NB.idx[key] == nil then NB.idx[key] = NB.padIdx(key) or false end
    end
    local lbI, rbI = NB.idx.PADLSHOULDER, NB.idx.PADRSHOULDER
    NB.sh = { l = lbI ~= false and S(function() return bt[lbI] end) == true, r = rbI ~= false and S(function() return bt[rbI] end) == true }
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

-- nbar11 THE RING LOOK's art (the game's own atlases, as the bottom band draws them; CastView.drawSlot): the slot ring by shape and
-- state, the empty slot's own art by position, the focus glow, the masks
NB.MASK_FILE = "Interface\\CharacterFrame\\TempPortraitAlphaMask"
NB.WELL = { 10 / 255, 8 / 255, 6 / 255 }                     -- CastView's WELL (0xFF0A0806)
NB.SLOT_DIR = { "left", "up", "right", "down" }
function NB.ringAtlas(circle, state)
    return "gamepad-actionbar-" .. (circle and "circleslot" or "squareslot") .. "-border-" .. (state or "normal")
end
function NB.slotAtlas(p, pressed)
    local sfx = pressed and "-pressed" or "-normal"
    if p <= 4 then return "gamepad-actionbar-squareslot-generic-dpad" .. NB.SLOT_DIR[p] .. sfx end
    return "gamepad-actionbar-circleslot-xbox-" .. (NB.RING_FACE[NB.KEY_OF[p]] or "a") .. sfx
end
-- a mask over [t] (the round / rounded-square atlas, else the portrait mask file for a circle); nil = none (the square's crop instead)
function NB.maskFor(b, t, circle)
    local okM, m = pcall(function() return b:CreateMaskTexture() end)
    if not (okM and type(m) == "table") then return nil end
    pcall(m.SetAllPoints, m, t)
    local on = NB.atlasOn(m, circle and "CircleMask" or "SquareMask")
    if not on and circle then
        on = pcall(m.SetTexture, m, NB.MASK_FILE, "CLAMPTOBLACKADDITIVE", "CLAMPTOBLACKADDITIVE")
    end
    if not on then return nil end
    return m
end
-- the look built once per button (out of combat or in: plain textures on our own frame)
function NB.skin(b)
    local circle = b.circle
    b.mask = NB.maskFor(b, b.icon, circle)                    -- the icon's (and the dim's) shape
    b.wmask = NB.maskFor(b, b.bg, circle)                     -- the well's
    if b.mask then
        pcall(b.icon.AddMaskTexture, b.icon, b.mask)
        pcall(b.dim.AddMaskTexture, b.dim, b.mask)
    else
        pcall(b.icon.SetTexCoord, b.icon, 0.08, 0.92, 0.08, 0.92)      -- no mask: the icon's own border cropped
    end
    if b.wmask then pcall(b.bg.AddMaskTexture, b.bg, b.wmask) end
    if circle then
        for _, c in ipairs({ b.cd, b.ch }) do
            pcall(c.SetSwipeTexture, c, NB.MASK_FILE)
            pcall(c.SetUseCircularEdge, c, true)
        end
    end
    b.ringOn = NB.atlasOn(b.ring, NB.ringAtlas(circle, "normal"))
    b.rs = b.ringOn and "normal" or nil
    if b.ringOn then b.ring:Show() else b.ring:Hide() end
    b.focusOn = NB.atlasOn(b.focus, "gamepad-actionbar-focus-bg-" .. (circle and "circ" or "square"))
    if b.focusOn then pcall(b.focus.SetAlpha, b.focus, 0.4); b.focus:Show() else b.focus:Hide() end
    b.wellKind = nil
    NB.well(b, false)
    -- the glyph's backing disc (shown only for the letter fallback)
    b.gmask = NB.maskFor(b, b.gdisc, true)
    if b.gmask then pcall(b.gdisc.AddMaskTexture, b.gdisc, b.gmask) end
    pcall(b.gdisc.SetColorTexture, b.gdisc, 0.05, 0.04, 0.03, 0.85)
    b.gdisc:Hide()
end
-- the well: the dark disc under the icon, or an empty slot's own art (the D-pad arrow / the face letter's slot); without the ring art
-- (a client refusing the atlases) today's plain dark square
function NB.well(b, empty)
    local kind = (empty and b.ringOn) and ("slot" .. (b.down and "P" or "")) or "well"
    if kind == b.wellKind then return end
    b.wellKind = kind
    if kind ~= "well" and NB.atlasOn(b.bg, NB.slotAtlas(b.p, b.down)) then
        pcall(b.bg.SetVertexColor, b.bg, 1, 1, 1)
        return
    end
    if b.ringOn then pcall(b.bg.SetColorTexture, b.bg, NB.WELL[1], NB.WELL[2], NB.WELL[3], 1)
    else pcall(b.bg.SetColorTexture, b.bg, 0, 0, 0, 0.45) end
end
-- the ring's state: pressed while held, disabled when empty or unusable, else normal (set only on a change)
function NB.ring(b)
    if not b.ringOn then return end
    local st = (b.down and "pressed") or ((b.kind == "empty" or b.look == "unusable") and "disabled") or "normal"
    if st == b.rs then return end
    if NB.atlasOn(b.ring, NB.ringAtlas(b.circle, st)) then b.rs = st
    else NB.atlasOn(b.ring, NB.ringAtlas(b.circle, "normal")); b.rs = "normal" end
end

-- one button of our bar: plain frames only (no template but the game's plain cooldown swipe), never the mouse. nbar11: the ring look's
-- layers bottom to top as CastView.drawSlot's - focus glow, well (or the empty slot's art), icon (masked), sweeps, dim, ring, glyph
function NB.button(parent, p)
    local circle = p >= 5
    local sz = circle and NB.BC or NB.B
    local b = CreateFrame("Frame", nil, parent)
    b:SetSize(sz, sz)
    pcall(b.EnableMouse, b, false)
    b.p, b.nbParent, b.circle, b.sz = p, parent, circle, sz
    b.focus = b:CreateTexture(nil, "BACKGROUND", nil, -8)
    b.focus:SetPoint("CENTER", b, "CENTER", 0, 0)
    b.focus:SetSize(sz + NB.FOCUS, sz + NB.FOCUS)
    b.bg = b:CreateTexture(nil, "BACKGROUND")
    b.bg:SetAllPoints(b)
    b.icon = b:CreateTexture(nil, "ARTWORK")
    b.icon:SetPoint("TOPLEFT", b, "TOPLEFT", NB.INSET, -NB.INSET)
    b.icon:SetPoint("BOTTOMRIGHT", b, "BOTTOMRIGHT", -NB.INSET, NB.INSET)
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
    -- nbar11: the ring, the glyph badge and the count over the sweeps (a plain child frame a few levels up, never the mouse)
    local top = CreateFrame("Frame", nil, b)
    pcall(top.EnableMouse, top, false)
    top:SetAllPoints(b)
    pcall(function() top:SetFrameLevel(b:GetFrameLevel() + 3) end)
    b.top = top
    b.ring = top:CreateTexture(nil, "OVERLAY", nil, 1)
    b.ring:SetAllPoints(b)
    local gx, gy = NB.GLYPH_AT[1], circle and NB.GLYPH_AT[3] or NB.GLYPH_AT[2]
    b.gdisc = top:CreateTexture(nil, "OVERLAY", nil, 1)
    b.gdisc:SetSize(NB.GLYPH, NB.GLYPH)
    b.gdisc:SetPoint("CENTER", b, "CENTER", gx, gy)
    b.glyph = top:CreateTexture(nil, "OVERLAY", nil, 2)
    b.glyph:SetSize(NB.GLYPH, NB.GLYPH)
    b.glyph:SetPoint("CENTER", b, "CENTER", gx, gy)
    b.word = top:CreateFontString(nil, "OVERLAY", "GameFontNormalSmall")
    b.word:SetPoint("CENTER", b.glyph, "CENTER", 0, 0)
    b.count = top:CreateFontString(nil, "OVERLAY", "NumberFontNormalSmall")
    pcall(function() local fn, sz2 = b.count:GetFont(); if fn then b.count:SetFont(fn, sz2 or 12, "OUTLINE") end end)
    b.count:SetPoint("BOTTOMRIGHT", b, "BOTTOMRIGHT", -3, 3)
    NB.skin(b)
    return b
end
-- nbar11: the held buttons' pressed look (ring, glyph, the 2-unit drop) at once, between two redraws (the tick's poll)
function NB.pressLook(mask)
    local f = NB.f
    if not (f and f.btn) then return end
    local lay = NB.layout()
    for p = 1, 8 do
        local b = f.btn[p]
        local down = (math.floor((mask or 0) / 2 ^ (p - 1)) % 2) == 1
        if b and down ~= (b.down == true) then
            b.down = down
            NB.anchor(b, lay)
            NB.ring(b)
            NB.well(b, b.kind == "empty")
            NB.glyph(b)
        end
    end
end
-- nbar11: the focus glow's flash on a layer change (1 -> 0.4 over 0.33 s, the band's FOCUS_MS)
function NB.focusTick()
    local f = NB.f
    if not (f and f.btn and NB.focusAt) then return end
    local t = ((SN(GetTime) or 0) - NB.focusAt) / 0.33
    local a = 0.4 + 0.6 * (1 - math.max(0, math.min(1, t)))
    for p = 1, 8 do local b = f.btn[p]; if b and b.focusOn then pcall(b.focus.SetAlpha, b.focus, a) end end
    if t >= 1 then NB.focusAt = nil end
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
    NB.topParts(f)                                -- nbar7: the Wings' ZL / ZR chips and swing lines (hidden out of a fight)
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
-- nbar3: the stock centre and scale (cx, cy in the stock bar's own units, k its scale over UIParent's), or the last one read
function NB.stock()
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
    if cx and cy and cx > 0 and cy > 0 then return cx, cy, k end
    return nil, nil, k
end
-- nbar3: the layout's entry for the bar now (offset in UIParent units, scale as a factor)
function NB.layEntry()
    local L = WC.lay
    local e = type(L) == "table" and type(L.get) == "function" and L.get("nbar") or nil
    local sc = math.max(NB.S_MIN, math.min(NB.S_MAX, (e and num(e.s)) or 100)) / 100
    local ox, oy = 0, 0
    if e and type(L.u) == "function" then ox, oy = L.u(num(e.dx) or 0), L.u(num(e.dy) or 0) end
    return ox, oy, sc
end
function NB.place()
    local f = NB.f
    if not f then return end
    local cx, cy, k = NB.stock()
    -- nbar7: while the Wings show they hold the frame (the stock spot is still remembered; the next solve re-fits)
    if NB.top and NB.top.shown then
        if cx and cy then NB.lastC = { cx, cy, k } end
        NB.top.key, NB.top.dirty = nil, true
        return
    end
    local ox, oy, sc = NB.layEntry()
    -- nbar2: the stock bar's own scale on ours (its buttons the stock size); nbar3: times the layout's size, the offsets in our own units
    pcall(f.SetScale, f, k * sc)
    NB.scale, NB.lsc = k, sc
    pcall(f.ClearAllPoints, f)
    if cx and cy then
        NB.lastC = { cx, cy, k }
        -- (the stock centre in UIParent units is cx * k; ours = that plus the layout's offset, in our frame's units)
        f:SetPoint("CENTER", UIParent, "BOTTOMLEFT", cx / sc + ox / (k * sc), cy / sc - oy / (k * sc))
        NB.at = "stock"
    else
        f:SetPoint("BOTTOM", UIParent, "BOTTOM", ox / (k * sc), NB.Y_DEF / sc - oy / (k * sc))
        NB.at = "default"
    end
end
-- nbar3 THE NINTENDO BAR AS A LAYOUT PIECE (Hub's editor gathers it): its anchor is the bar's centre, 1080-line pixels from the top-left
local function layK()
    local L = WC.lay
    if type(L) == "table" and type(L.screen) == "function" then local W, _, kk = L.screen(); return W, kk end
    return 1920, 1
end
local function barSize()
    -- nbar12: while the top-screen flag is on, the piece is the Wings' joined bar: the diamonds' spread plus each half's outer reach (the
    -- L / R plates, the chips, Party mode's chord row) - the editor's box the size of what shows in the fight
    if type(NB.topFlag) == "function" and NB.topFlag() and type(NB.halfBox) == "function" then
        local _, _, ax = NB.halfBox()
        return 2 * (NB.DX + ax), 2 * NB.OFF + NB.B
    end
    return 2 * NB.DX + 2 * NB.OFF + NB.B, 2 * NB.OFF + NB.B
end
NB.lay = {
    id = "nbar", sn = NB.S_MIN, sx = NB.S_MAX, ss = NB.S_STEP,
    -- the stock bar's centre (no entry), else the default spot's
    base = function()
        local W, kk = layK()
        local cx, cy, k = NB.stock()
        local uh = 1080 / kk
        if cx and cy then return cx * k * kk, (uh - cy * k) * kk end
        local _, bh = barSize()
        return W / 2, (uh - NB.Y_DEF * k - bh * k / 2) * kk
    end,
    size = function(e)
        local _, kk = layK()
        local _, _, k = NB.stock()
        local sc = ((e and num(e.s)) or 100) / 100
        local bw, bh = barSize()
        return bw * k * sc * kk, bh * k * sc * kk
    end,
    off = function(w, h) return -w / 2, -h / 2 end,
    on = function() return NB.on == true end,
    -- nbar7 ("Combat bar", SPEC section 11): listed while the top-screen combat bar is on too, so it can be placed (an entry = Auto off)
    want = function() return NB.on == true or NB.topFlag() end,
    apply = function() pcall(NB.place); if NB.top then NB.top.dirty = true end end,
}

-- a ref from PadMap's views ({ n, id, i } a spell; { n, i, t, key } a whole-button thing) -> an entry. nbar3: a player's MACRO (t =
-- "macro") is the spell it would cast now (PadMap m165's look: `ms`, or WC.padMap.macLook for a form's raw entry): that spell's
-- picture, cooldown / GCD sweep and tints, as the game's own macro button; a macro using an item: its picture
local function refEntry(ref)
    if type(ref) ~= "table" then return { kind = "empty" } end
    if ref.t == "macro" then
        local lk = ref
        local pm = rawget(WC, "padMap")
        if ref.ms == nil and ref.mt == nil and type(pm) == "table" and type(pm.macLook) == "function" then
            local ok, l = pcall(pm.macLook, ref.n)
            if ok and type(l) == "table" then lk = l end
        end
        local sid = num(lk.ms)
        if sid then return { kind = "spell", id = sid, icon = lk.i or ref.i } end
        return { kind = "icon", icon = lk.i or ref.i }
    end
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
        if dpadOn then return { kind = "icon", icon = NB.TOOLBAR_BY[(NB.dirs or {})[tostring(p)] or ""] or NB.TOOLBAR[p] } end
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
    if b.noBadge then                                    -- nbar12: Party mode's pick arrows wear no badge (the band's noBadge)
        if b.gkey ~= false then b.gkey = false; b.glyph:Hide(); b.word:SetText(""); if b.gdisc then b.gdisc:Hide() end end
        return
    end
    local down = b.down == true
    if b.gkey == key and b.glay == lay and b.gdown == down then return end
    b.gkey, b.glay, b.gdown = key, lay, down
    for _, a in ipairs(NB.glyphAtlas(key, down)) do
        -- nbar2: only an atlas the client really has (a refused one falls to the next, then the letter)
        if NB.atlasOn(b.glyph, a) then b.glyph:Show(); b.word:SetText(""); b.atlas = a; if b.gdisc then b.gdisc:Hide() end; return end
    end
    b.glyph:Hide(); b.atlas = nil
    b.word:SetText(NB.GLYPH_WORD[key] or "")
    if b.gdisc then b.gdisc:Show() end                      -- nbar11: the letter on a dark disc
end

-- one full redraw of the live layer
function NB.refresh()
    local f = NB.f
    if not f or not (NB.on or NB.top.shown or NB.top.edit) then return end
    local combat = S(InCombatLockdown) == true
    local fight = combat or S(UnitAffectingCombat, "player") == true
    local hasTarget = S(UnitExists, "target") == true
    -- nbar2: hidden with the stock bar (a pet battle, a cinematic, out of gamepad mode: the game's own IsVisible, a plain read);
    -- our own frame's alpha, so the tick keeps watching
    local mf = mainFrame()
    local vis = mf ~= nil and MS(mf, "IsVisible") ~= false
    pcall(f.SetAlpha, f, (vis and 1 or 0) * ((NB.top.shown and NB.top.alpha) or 1))     -- nbar7: the Wings' fade / dip
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
        NB.dirs = ok and type(ds) == "table" and type(ds.d) == "table" and ds.d or nil   -- nbar6: the words of the bare D-pad
    end
    f.layer:SetText((NB.top.shown or NB.top.edit) and "" or (NB.LAYER_WORD[set] or ""))  -- nbar7: the chips say it in the Wings
    if NB.top.shown or NB.top.edit then NB.topChips(set) end
    NB.swapT = readCV("GamepadSwapTargetModifiers")                                    -- nbar12: the plates' pair (a read only)
    -- nbar12 THE PARTY LAYER (ruling C): the Wings shown with Party mode on carry what the band would show in Party mode
    local party = (NB.top.shown and NB.partyOn()) and NB.partyCtx() or nil
    NB.party = party
    NB.topChords(party)
    if NB.setWas ~= nil and NB.setWas ~= set then NB.focusAt = SN(GetTime) end   -- nbar11: the layer changed: the focus glow flashes
    NB.setWas = set
    NB.stats = { ok = 0, fail = 0, ch = 0, dim = 0, n = 0 }
    NB.shown = {}
    for p = 1, 8 do
        local b = f.btn[p]
        local pic = party and NB.partyPic(p, set, party) or nil
        local e
        if pic and pic.kind == "fixed" then
            -- the game's own button there on EVERY layer (B's Back; Y's Inspect before a cfg), as the router's party pad keeps it whatever
            -- trigger is held: the plain TOP bar's own fixed button (the review, 11-22: never the base layer's slot - a form's stance bar
            -- or a vehicle's possess bar over it puts an action there, while the pad's B still turns Party mode off)
            e = slotEntry(topFixed(pu, p), hasTarget)
        elseif pic then e = pic
        else e = NB.resolve(set, p, lay, page, fightSw, dpadOn, hasTarget, form) end
        b.noBadge = pic ~= nil and pic.noBadge == true
        NB.glyph(b)
        local st = nil
        if pic and pic.kind == "spell" and e.id then
            -- the party spell on the PICKED member: its own usable / mana; dim by PartyStrip.lit (grey, the ring disabled); range = the
            -- picked member's (PadMap's rng), never the target's
            local o = spellState(e.id, false) or {}
            st = { u = pic.dim and 0 or o.u, m = (not pic.dim) and o.m or nil, r = (party.rng == false) and 0 or nil, ch = o.ch, cnt = o.cnt }
        elseif e.kind == "slot" then st = buttonState(e.d, combat, hasTarget)
        elseif e.kind == "spell" and e.id then st = spellState(e.id, hasTarget) end
        if e.kind == "empty" or not NB.paint(b.icon, e.icon) then
            b.icon:Hide()
        else
            b.icon:Show()
        end
        -- nbar12: an arrow's turn (up / down from the forward arrow) and the dimmed moves; set only on a change
        local rot, ia = (pic and pic.rot) or 0, (pic and pic.alpha) or 1
        if b.rot ~= rot then b.rot = rot; pcall(b.icon.SetRotation, b.icon, rot) end
        if b.ia ~= ia then b.ia = ia; pcall(b.icon.SetAlpha, b.icon, ia) end
        NB.tint(b, st)
        local route = NB.cool(b, e, st)
        if route then NB.stats.n = NB.stats.n + 1 end
        if route == "dim" then NB.stats.dim = NB.stats.dim + 1 end
        -- an item's count (consumables), else a multi-charge spell's charges (both plain or absent: buttonState's own reads)
        local cnt = st and (num(st.cnt) or num(st.ch)) or nil
        b.count:SetText(cnt and tostring(cnt) or "")
        b.kind = e.kind
        NB.well(b, e.kind == "empty")                     -- nbar11: an empty slot wears its own art, its ring disabled
        NB.ring(b)
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
    if not (NB.on or NB.top.shown or NB.top.edit) then return end
    NB.acc, NB.accS = NB.acc + (el or 0), NB.accS + (el or 0)
    if NB.acc < NB.POLL_S then return end
    NB.acc = 0
    local bu = barsUsable()
    if bu ~= NB.bu then                                 -- nbar2: a window taking / giving back the pad collapses the stock bar
        NB.bu = bu
        pcall(NB.place)
    end
    local set = (bu == false) and 1 or NB.heldSet()
    local held = (bu == false) and 0 or (NB.held or 0)
    if held ~= (NB.heldWas or 0) then NB.heldWas = held; pcall(NB.pressLook, held) end     -- nbar11: the pressed rings at once
    if NB.top.shown or NB.top.edit then pcall(NB.topPlates, (bu ~= false) and NB.sh or nil, set ~= 1) end  -- nbar12: the L / R plates
    if NB.focusAt then pcall(NB.focusTick) end
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
        if NB.f and not NB.top.shown then NB.f:Hide() end       -- nbar7: the Wings keep it to their own end (then gone)
        NB.top.form = "fade"
    end
    return true
end

---------------------------------------------------------------------------------------------------
-- nbar7 THE COMBAT BAR ON THE TOP SCREEN: "WINGS" (movable Home phase 3; SPEC.txt section 11, final_07 / final_07b; the user's rulings
-- 2026-11-20: D1 "In fights: Top screen" is OPT-IN - the bottom band's Grow stays the default; D7 Wings: the D-pad diamond left of our
-- HUD, the faces right of it, an auto spot unless the player placed the "Combat bar" piece in the top layout editor). While this
-- character's flag is on (PadMap m179: WowCompanionSpellsDB.ctop, the app's "In fights: Top screen"; NB.topFlag) the Nintendo bar shows in
-- a fight - on the router's own switch, WC.tgGrown, the signal the bottom band grows on - as two halves around our HUD, the ZL / ZR
-- chips at the outer ends (lit while held), the split swing lines under the faces. Each button is the same live picture as the bar's
-- (NB.refresh: the live layer, the fight layer, the faces' fight spells, the DS toolbar by word, duration-object sweeps, blue = mana,
-- the red glyph = range only). The bottom band keeps its rest size (the app's half).
-- THE SPOT (NB.fightSolve, pure: tools/test_combat_bar_spot.py, tools/test_wings_geometry.py): worked out OUT OF COMBAT against our own
-- pieces (nbar11: the HUD BLOCK from its geometry, Trail's P.lay.block - the bars, the combo pips, the aura rows under or above, the pet
-- strip under or beside, Party mode's rows, the lift - unioned with its live frames read at any alpha, so a HUD faded out of combat
-- still counts; the compass; the target bar with its cast strip, ToT, Y prompt and aura host; the left party column; Polestar's arrow
-- and guide), the game's frames shown then and the fight-only game frames RESERVED (NB.RESERVE: the cast bars always, the Cooldown
-- Manager, mirror timers, extra ability, boss frames and swing timers once seen this session) - WC.gameLay.rectNow, a plain read, OUT OF
-- COMBAT ONLY - never over the tap pad's band (WC.lay.forbidden's "pad" zone with x 720-1200, y 24-178 at least); margin 24 px.
-- nbar11 SHRINK FIRST, THEN MOVE - the first candidate that fits wins:
--   1 wings beside the HUD block, level with its middle, 34 px gap each side (clamped 16 px above the bottom), at k 1, then 0.9, 0.8
--   2 wings raised in 24 px steps (up to 300 px) at k 0.8 until both halves clear, staying level
--   3 the halves joined into one bar, centred above the HUD block, at k 1, then 0.8
--   4 a grid scan at k 0.7 over the lower 60 % of the screen (24 px steps, bottom rows first, centre outward), then at 0.55 there,
--     then at 0.55 over the whole screen (a big bar on a small UI): the first joined spot clear
--   5 the Combat bar piece's own spot (the stock bar's) - the last resort, always accepted (pushed under the tap pad's band if it would
--     cover it; the geometry test fails any HUD state that lands here)
-- The result's k scales the bar (NB.topSolve); its `lane` says the column under each half down to the screen's bottom edge is clear
-- (the slide's path, below).
-- A spot the player placed (an entry for "nbar" in WowCompanionDB.lay = Auto off; the editor's Reset = Auto again) ALWAYS wins: the
-- joined bar there. FROZEN at PLAYER_REGEN_DISABLED: the game's rects stay the last ones read out of combat; in a fight the spot is
-- re-fit only against OUR frames (Party mode lifting the HUD: the wings re-hug the taller block). Re-solved out of combat at login, on a
-- layout save (the piece's apply), UI_SCALE_CHANGED / DISPLAY_SIZE_CHANGED, every REFIT_S against ours and every SOLVE_S with the game's
-- frames read again.
-- MOTION (nbar11, the user 2026-11-22: "a slick animation looking like it slides up to the top screen"): with a clear lane the bar
-- SLIDES IN UPWARD from fully below the top screen's bottom edge - a HOLD_IN (60 ms) wait, then IN_S (0.26 s) on the ease-out cubic
-- (0, 0, 0.2, 1), alpha 1 all the way - as the app's band slides up off the bottom screen's top edge, so it reads as one object crossing
-- the seam; out it slides back down past the edge over OUT_S (0.20 s) on the ease-in (0.4, 0, 1, 1) (the app's Motion.curveOut), then
-- hides; a reversal mid-way goes on from where it is. No lane (the joined bar above the HUD, a placed spot over something): today's fade
-- in + rise 24 px. With the Nintendo bar already shown out of combat ("Controller UI = Nintendo") the bar GLIDES from its spot to the
-- wings and back; a re-fit glides IN_S. The top chat strip shown: the bar dips to 35 % alpha instead of moving. In the top layout editor
-- (out of combat) the bar shows at its piece's spot so it can be placed. Party mode (nbar12): the Wings in its fights only, the Party
-- layer on them (NB.topSwitch, NB.partyPic).
-- LIMITS: display only - plain frames, EnableMouse(false), no secure template, anchored to UIParent and our own frames only; nothing of
-- the game's moved, shown or hidden (the stock bar keeps whatever its own switch says, and is an obstacle when shown); no SetBinding /
-- CVar / macro / action-slot write; no secret compared (every rect read through WC.isPlain; the game's never in a fight).
-- nbar12 THE PLATES AND THE PARTY LAYER (the user's rulings 2026-11-22, after the shots):
-- (A) "ADD L/R PLATES to the top-screen bar, like the bottom band's L/R corner plates (with ZL/ZR under them)": each half wears the
-- band's shoulder plate at its outer top (the game's gamepad-targeting-left / -right-shoulder-bg, the -pressed overlay while held, the
-- targeting picture the held bumpers open - friendly / hostile / shortcuts, swapped with GamepadSwapTargetModifiers: the band's
-- drawPlate / Blizzard's SetShoulderIcons - and the bumper glyph at its inner bottom corner), the ZL / ZR chip moved under it, Party
-- mode's chord row (the trigger's three party spells) under that. Atlas refused -> a dark plate with "L" / "R", gold while held. The
-- half box grows 16 units outward (221 x 185); the solver is unchanged (it shrinks before it moves), so the wider bar never covers the
-- HUD or a reserved game frame. LB / RB keep the game's targeting (their pictures say so) in Party mode too: the party spells ride ZL /
-- ZR, as on the band.
-- (C) "It should stay at the top in party mode but the party bars should fill the area": Party mode no longer keeps the Wings off -
-- in a FIGHT (the lockdown or the player in combat; never an attack armed before the pull: Party mode has no takeover, so the app does
-- not move then) they show as in any fight and carry the PARTY layer (NB.partyPic, PartyStrip's rules): the D-pad = the pick's moves
-- (the game's arrows, dimmed where the pick cannot go, no badge), A / X / Y = the Controller room's Party spells on the held trigger's
-- layer (Y in a fight: its In combat spell, else Smart Y's pick for the picked member, else Target), B = the game's Back, both triggers =
-- nothing; lit / dim as the band (not learned, dead = res only, alive = no res), range red off the picked member's own range. The cfg is
-- the LAST one PadMap sent (m185 cfgLast: never the spellbook walk in a fight). A Select press mid-fight swaps the layer in place and
-- re-fits against our frames (Hub's rows and its Select tab join the HUD block while Party mode is on). Display only, as ever: every
-- cast, nav and toggle stays PadMap's secure party pad.
-- nbar11 COMBAT SAFETY OF THE SLIDE: the Wings are insecure, display-only frames, so their motion in a fight is a per-frame SetPoint /
-- SetScale / SetAlpha on WowCompanionNintendoBar (our own frame, parented and anchored to UIParent only - never to a protected frame,
-- so nothing protected joins its anchor family) and SetAtlas / SetVertexColor on our own textures; no AnimationGroup (nothing on the
-- device proves one in a fight, and none is needed), no secure template, no state driver. Proven on the device (1518): the Wings built,
-- shown, moved, scaled and faded in fights (topApply -> topPlace per frame, the re-fit glide), SetAtlas on our textures in a fight
-- (NB.glyph inside NB.refresh), mask textures on our frames (Trail's pet portrait), NB.build in a fight (nbar9). New and guarded: the
-- round sweeps' SetSwipeTexture / SetUseCircularEdge run at build, pcall'd. Nothing depends on PLAYER_REGEN_DISABLED firing before the
-- lockdown (the reserved rects are read only while InCombatLockdown() is false).
-- The app sends nothing in combat: the add-on switches by itself.
---------------------------------------------------------------------------------------------------
NB.TOP = { HOLD_IN = 0.06, IN_S = 0.26, OUT_S = 0.20, RISE = 24, KS = { 1, 0.9, 0.8 }, KJ = { 1, 0.8 }, KG = { { 0.7, 0.4 }, { 0.55, 0.4 }, { 0.55, 0 } }, GAP = 34, MARGIN = 24, STEP = 24, RAISE_MAX = 300, FLOOR = 16, DIP = 0.35,
           REFIT_S = 0.5, SOLVE_S = 5, FLAG_S = 0.5, BAND = { x = 720, y = 24, w = 480, h = 154 },
           -- one half, in the bar's own units: the diamond (2 OFF + B), the ZL / ZR chip outside it, the two swing lines under it
           CHIP_W = 40, CHIP_H = 28, CHIP_GAP = 4, SW_GAP = 8, SW_H = 6, SW_SEP = 4,
           -- nbar12: the outer column (OUTER_W wide, PLATE_GAP past the diamond): the L / R plate (the game atlas's own 50 x 45) at the top,
           -- the chip CHIP_GAP under it, Party mode's chord row (three round icons CHORD across, CHORD_GAP apart) CHORD_SEP under that; the
           -- plate's bumper glyph at its inner bottom corner (PLATE_GX in, PLATE_GY down from its centre); Hub's party rows' drawn rim (the
           -- rings and the chevron past each row's frame) while Party mode is on
           PLATE_W = 50, PLATE_H = 45, PLATE_GAP = 6, OUTER_W = 54, CHORD = 16, CHORD_GAP = 3, CHORD_SEP = 4, PLATE_GX = 15, PLATE_GY = -13.5,
           HUB_RIM = 12 }
NB.top = { want = false, shown = false, edit = false, t = 0, dir = 0, form = "fade", solveAcc = 0, gameAcc = 0, game = {}, gameAt = nil,
           frozen = false, dirty = true, gameDirty = true, spot = nil, cur = nil, from = nil, mt = 1, alpha = 1, key = nil, n = 0, reads = 0, gameReads = 0 }
NB.hx, NB.hy = { -NB.DX, NB.DX }, { 0, 0 }

-- a cubic bezier (x1, y1, x2, y2) at [u] in 0..1 (as Android's PathInterpolator): x(t) = u by bisection, then y(t)
function NB.cubic(u, x1, y1, x2, y2)
    if u <= 0 then return 0 elseif u >= 1 then return 1 end
    local function bz(t, a, b) local m = 1 - t; return 3 * m * m * t * a + 3 * m * t * t * b + t * t * t end
    local lo, hi = 0, 1
    for _ = 1, 30 do
        local mid = (lo + hi) / 2
        if bz(mid, x1, x2) < u then lo = mid else hi = mid end
    end
    return bz((lo + hi) / 2, y1, y2)
end
-- the app's Motion.curve (0.4, 0, 0.2, 1): the glides and the fade
function NB.ease(u) return NB.cubic(u, 0.4, 0, 0.2, 1) end
-- nbar11 the slide's curves: in = ease-out (0, 0, 0.2, 1: Motion.curveEnter, the band coming down), out = ease-in (0.4, 0, 1, 1:
-- Motion.curveOut, what leaves the screen)
function NB.easeOut(u) return NB.cubic(u, 0, 0, 0.2, 1) end
function NB.easeIn(u) return NB.cubic(u, 0.4, 0, 1, 1) end
-- the slide's progress (0 below the edge .. 1 at the spot) at T.t for the way it goes ([dir] 1 in, -1 out: out runs T.t 1 -> 0)
function NB.slideK(t, dir)
    if dir == -1 then return 1 - NB.easeIn(1 - t) end
    return NB.easeOut(t)
end
-- the T.t on the other way's curve that gives the same progress [k] (a reversal mid-way goes on from where the bar is: no jump)
function NB.slideT(k, dir)
    local lo, hi = 0, 1
    for _ = 1, 30 do
        local mid = (lo + hi) / 2
        if NB.slideK(mid, dir) < k then lo = mid else hi = mid end
    end
    return (lo + hi) / 2
end
-- how far below its spot the slide starts (px): both halves wholly under the screen's bottom edge [H] (+2)
function NB.slideDrop(to, sp)
    local _, hh, _, ay = NB.halfBox()
    local s = sp.s or 1
    local H = sp.H or 1080
    return H + ay * s - math.min(to.l.y, to.r.y) + 2, hh * s
end

-- one half's box in the bar's units: w, h and the diamond's centre from its top-left (the D-pad half; the faces' half mirrored).
-- nbar11 review: the box holds everything a half DRAWS, not only the diamond's slots: each slot's focus glow (FOCUS past the slot, at
-- alpha 0.4 at rest and 1 on every layer flash) reached 15 units past the diamond on its top and its inner side, and the held ZL / ZR
-- disc (CHIP_H + 8 round the chip) 4 above it - the solver kept only the diamond clear of the HUD, so the glow could flash over it.
-- NB.halfArt gives the drawn reach on each side; the box is the larger of it and the diamond / chip / swing-line layout
-- nbar12 THE OUTER COLUMN (one half, from its diamond's centre, x outward, y up): the plate's, the chip's and the chord row's centres
function NB.outerGeo()
    local T = NB.TOP
    local d2 = NB.OFF + NB.B / 2
    local x = d2 + T.PLATE_GAP + T.OUTER_W / 2                                  -- 106
    local plateY = d2 - T.PLATE_H / 2                                            -- +50.5: its top level with the diamond's top slot
    local chipY = plateY - T.PLATE_H / 2 - T.CHIP_GAP - T.CHIP_H / 2             -- +10
    local chordY = chipY - T.CHIP_H / 2 - T.CHORD_SEP - T.CHORD / 2              -- -16
    return { x = x, plateY = plateY, chipY = chipY, chordY = chordY, chordDX = T.CHORD + T.CHORD_GAP }
end
function NB.halfArt()
    local T = NB.TOP
    -- the outermost slot's glow (a slot sits OFF from the centre on each axis; a held one 2 lower)
    local glow = NB.OFF + math.max(NB.B, NB.BC) / 2 + NB.FOCUS / 2
    local disc = (T.CHIP_H + 8) / 2
    local g = NB.outerGeo()                                                      -- NB.topDeco's places (outward, up)
    return {
        up = math.max(glow, g.plateY + T.PLATE_H / 2, g.chipY + math.max(T.CHIP_H / 2, disc)),
        down = math.max(glow + 2, -(g.chordY - T.CHORD / 2)),
        inner = glow,
        -- (the plate, the chip and its held disc, the chord row's outer icon: always counted, so a Party toggle never resizes the bar)
        outer = math.max(glow, g.x + T.PLATE_W / 2, g.x + math.max(T.CHIP_W / 2, disc), g.x + g.chordDX + T.CHORD / 2),
    }
end
function NB.halfBox()
    local T = NB.TOP
    local d = 2 * NB.OFF + NB.B
    local a = NB.halfArt()
    local outer = math.max(d + T.CHIP_GAP + T.CHIP_W - d / 2, a.outer)
    local inner = math.max(d / 2, a.inner)
    local up = math.max(d / 2, a.up)
    local down = math.max(d / 2 + T.SW_GAP + 2 * T.SW_H + T.SW_SEP, a.down)
    return outer + inner, up + down, outer, up
end

-- two rects overlap, [m] px apart at least
function NB.overlaps(a, b, m)
    m = m or 0
    return a.x - m < b.x + b.w and b.x < a.x + a.w + m and a.y - m < b.y + b.h and b.y < a.y + a.h + m
end
function NB.union(a, b)
    if not a then return b end
    if not b then return a end
    local x0, y0 = math.min(a.x, b.x), math.min(a.y, b.y)
    local x1, y1 = math.max(a.x + a.w, b.x + b.w), math.max(a.y + a.h, b.y + b.h)
    return { x = x0, y = y0, w = x1 - x0, h = y1 - y0 }
end

-- THE SOLVER (pure; 1080-line pixels from the top-left). [inp]: W, H, s (px per bar unit), hud (the HUD block's rect, nil = none),
-- ours / game (rects to keep clear of), band (the tap pad's), placed (the player's own centre for the joined bar, nil = Auto), stock (the
-- piece's default centre). -> { case = 1 / 2 / 3 / 4 / 5 / "placed", form = "wings" / "joined", l / r = the two diamonds' centres,
-- rects = the two halves' boxes, raise = px raised (case 2), k = the shrink (the bar's scale times it), lane = the column under each
-- half to the bottom edge clear (the slide's path) }
function NB.fightSolve(inp)
    local T = NB.TOP
    local W, H, s0 = inp.W or 1920, inp.H or 1080, inp.s or 1
    local band = inp.band or T.BAND
    local obst = {}
    for _, r in ipairs(inp.ours or {}) do obst[#obst + 1] = r end
    for _, r in ipairs(inp.game or {}) do obst[#obst + 1] = r end
    -- one size's half box (px) and the diamonds' half spread
    local function geo(k)
        local hw, hh, ax, ay = NB.halfBox()
        local s = s0 * k
        return { s = s, hw = hw * s, hh = hh * s, ax = ax * s, ay = ay * s, dx = NB.DX * s }
    end
    local function lrect(g, cx, cy) return { x = cx - g.ax, y = cy - g.ay, w = g.hw, h = g.hh } end
    local function rrect(g, cx, cy) return { x = cx - (g.hw - g.ax), y = cy - g.ay, w = g.hw, h = g.hh } end
    local function fits(r)
        if r.x < 0 or r.y < 0 or r.x + r.w > W or r.y + r.h > H then return false end
        if NB.overlaps(r, band, T.MARGIN) then return false end
        for _, o in ipairs(obst) do if NB.overlaps(r, o, T.MARGIN) then return false end end
        return true
    end
    -- the slide's lane: nothing of ours or the game's in the column under a half, down to the screen's bottom edge
    local function clearUnder(r)
        local col = { x = r.x, y = r.y + r.h, w = r.w, h = math.max(0, H - (r.y + r.h)) + 1 }
        for _, o in ipairs(obst) do if NB.overlaps(col, o, 0) then return false end end
        return true
    end
    local function out(case, form, g, k, lx, ly, rx, ry, raise)
        local rects = { lrect(g, lx, ly), rrect(g, rx, ry) }
        return { case = case, form = form, l = { x = lx, y = ly }, r = { x = rx, y = ry }, raise = raise or 0, k = k,
                 rects = rects, lane = clearUnder(rects[1]) and clearUnder(rects[2]) }
    end
    -- the joined bar centred on [c], kept on the screen FIRST, then moved under the tap pad's band if it would come within MARGIN of it
    -- (nbar10: the clamp ran after the band test, so a left-edge spot that cleared the band was pushed onto it by the clamp)
    local function keep(g, x, y)
        return math.max(g.dx + g.ax, math.min(W - g.dx - g.ax, x)), math.max(g.ay, math.min(H - (g.hh - g.ay), y))
    end
    local function joined(case, c, k)
        local g = geo(k or 1)
        local x, y = keep(g, c.x, c.y)
        local r = { x = x - g.dx - g.ax, y = y - g.ay, w = 2 * (g.dx + g.ax), h = g.hh }
        if NB.overlaps(r, band, T.MARGIN) then x, y = keep(g, x, band.y + band.h + T.MARGIN + g.ay) end
        return out(case, "joined", g, k or 1, x - g.dx, y, x + g.dx, y)
    end
    if inp.placed then return joined("placed", inp.placed) end
    local hud = inp.hud
    if hud then
        local function level(g) return math.min(hud.y + hud.h / 2, H - T.FLOOR - (g.hh - g.ay)) end
        -- 1: level beside the block, the full size first, then a little smaller
        for _, k in ipairs(T.KS) do
            local g = geo(k)
            local lx, rx = hud.x - T.GAP - (g.hw - g.ax), hud.x + hud.w + T.GAP + (g.hw - g.ax)
            local cy = level(g)
            if fits(lrect(g, lx, cy)) and fits(rrect(g, rx, cy)) then return out(1, "wings", g, k, lx, cy, rx, cy) end
        end
        -- 2: raised at the smallest of those sizes
        do
            local k = T.KS[#T.KS]
            local g = geo(k)
            local lx, rx = hud.x - T.GAP - (g.hw - g.ax), hud.x + hud.w + T.GAP + (g.hw - g.ax)
            local cy = level(g)
            local d = T.STEP
            while d <= T.RAISE_MAX do
                if fits(lrect(g, lx, cy - d)) and fits(rrect(g, rx, cy - d)) then return out(2, "wings", g, k, lx, cy - d, rx, cy - d, d) end
                d = d + T.STEP
            end
        end
        -- 3: joined above the block
        for _, k in ipairs(T.KJ) do
            local g = geo(k)
            local jx, jy = hud.x + hud.w / 2, hud.y - T.GAP - (g.hh - g.ay)
            if fits(lrect(g, jx - g.dx, jy)) and fits(rrect(g, jx + g.dx, jy)) then return out(3, "joined", g, k, jx - g.dx, jy, jx + g.dx, jy) end
        end
    end
    -- 4: a grid scan of the lower 60 % at k 0.7, then 0.55, then the whole screen at 0.55 (bottom rows first, from the middle outward):
    -- the first clear joined spot
    for _, kt in ipairs(T.KG) do
        local k = kt[1]
        local g = geo(k)
        local x0, x1 = g.dx + g.ax, W - g.dx - g.ax
        local yBottom, yTop = H - T.FLOOR - (g.hh - g.ay), H * kt[2] + g.ay
        local mid = (hud and (hud.x + hud.w / 2)) or W / 2
        local y = yBottom
        while y >= yTop do
            local i = 0
            while true do
                local tried = false
                for _, sgn in ipairs(i == 0 and { 1 } or { -1, 1 }) do
                    local x = mid + sgn * i * T.STEP
                    if x >= x0 and x <= x1 then
                        tried = true
                        if fits(lrect(g, x - g.dx, y)) and fits(rrect(g, x + g.dx, y)) then return out(4, "joined", g, k, x - g.dx, y, x + g.dx, y) end
                    end
                end
                if not tried and (mid - i * T.STEP < x0) and (mid + i * T.STEP > x1) then break end
                i = i + 1
            end
            y = y - T.STEP
        end
    end
    local g1 = geo(1)
    return joined(5, inp.stock or { x = W / 2, y = H - 64 * s0 - g1.hh / 2 })
end

-- ---------------------------------------------------------------- the inputs (our frames any time; the game's out of combat only)
function NB.topScreen()
    local L = WC.lay
    if type(L) == "table" and type(L.screen) == "function" then
        local ok, W, H, k = pcall(L.screen)
        if ok and type(W) == "number" and type(H) == "number" and type(k) == "number" and k > 0 then return W, H, k end
    end
    return 1920, 1080, 1
end
-- a plain frame of OURS: its rect in 1080-line pixels while it shows (nil = hidden, at alpha 0, not laid out, or a value not plain).
-- nbar11 [anyAlpha]: a frame faded to alpha 0 still counts (the HUD fades out of combat, where the spot is solved; it is back in the fight)
function NB.frameRect(f, anyAlpha)
    if type(f) ~= "table" then return nil end
    local function n(m)
        local fn = f[m]
        if type(fn) ~= "function" then return nil end
        local ok, v = pcall(fn, f)
        if ok and type(v) == "number" and WC.isPlain(v) and v == v and v ~= math.huge and v ~= -math.huge then return v end
        return nil
    end
    local okV, vis = pcall(function() return f:IsVisible() end)
    if not (okV and vis == true) then return nil end
    if not anyAlpha and (n("GetEffectiveAlpha") or n("GetAlpha") or 1) <= 0.01 then return nil end
    local l, t, w, h = n("GetLeft"), n("GetTop"), n("GetWidth"), n("GetHeight")
    local es = n("GetEffectiveScale") or 1
    local okU, ues = pcall(function() return UIParent:GetEffectiveScale() end)
    ues = (okU and type(ues) == "number" and WC.isPlain(ues) and ues > 0) and ues or 1
    if not (l and t and w and h) or es <= 0 or w <= 0 or h <= 0 then return nil end
    local _, H, k = NB.topScreen()
    local r = es / ues
    NB.top.reads = NB.top.reads + 1
    return { x = l * r * k, y = (H / k - t * r) * k, w = w * r * k, h = h * r * k }
end
-- a layout piece's rect at its entry now (Hub's LAY.rect's arithmetic; nil = none)
function NB.pieceRect(p, id)
    if type(p) ~= "table" or type(p.base) ~= "function" or type(p.size) ~= "function" then return nil end
    local ok, r = pcall(function()
        local L = WC.lay
        local e = (type(L) == "table" and type(L.get) == "function") and L.get(id) or nil
        local ax, ay
        if e == nil and type(p.follow) == "function" then ax, ay = p.follow() else
            local bx, by = p.base()
            ax, ay = (bx or 0) + ((e and e.dx) or 0), (by or 0) + ((e and e.dy) or 0)
        end
        local w, h = p.size(e)
        local ox, oy = 0, 0
        if type(p.off) == "function" then ox, oy = p.off(w, h) end
        return { x = ax + (ox or 0), y = ay + (oy or 0), w = w, h = h }
    end)
    if ok and type(r) == "table" and type(r.x) == "number" and type(r.y) == "number" and type(r.w) == "number" and type(r.h) == "number"
            and r.w > 0 and r.h > 0 then return r end
    return nil
end
function NB.pieceOn(p)
    if type(p) ~= "table" or type(p.on) ~= "function" then return false end
    local ok, v = pcall(p.on)
    return ok and v == true
end
-- OUR pieces' rects now (live frames first, else the piece's own rect) -> list, the HUD block
function NB.topOurs()
    local out, hud = {}, nil
    local function add(r, id) if r then r.id = id; out[#out + 1] = r end end
    local uf, prd, ab = rawget(WC, "uf"), rawget(WC, "prd"), rawget(WC, "abox")
    local pf = type(uf) == "table" and type(uf.pf) == "table" and uf.pf or nil
    local boxes = type(ab) == "table" and type(ab.boxes) == "table" and ab.boxes or {}
    local function host(key) local b = boxes[key]; return type(b) == "table" and b.host or nil end
    -- nbar12: Party mode's rows under the HUD's place (the Wings show in Party mode now): each row with its drawn rim (the rings past its
    -- frame), and Hub's Select tab at the grid's left edge - read at any alpha, the rows can reach past Trail's block. Hub lays them there
    -- whenever Party mode and our party frames are on (PF.want, PF.geo from P.partyGeo), our HUD bars on or off (the review, 11-22)
    local rows = nil
    if pf and pf.at == "hud" and type(pf.frames) == "table" then
        local rim = NB.TOP.HUB_RIM
        for _, f in pairs(pf.frames) do
            local r = NB.frameRect(f, true)
            if r then rows = NB.union(rows, { x = r.x - rim, y = r.y - rim, w = r.w + 2 * rim, h = r.h + 2 * rim }) end
        end
        if type(pf.tab) == "table" then rows = NB.union(rows, NB.frameRect(pf.tab, true)) end
    end
    -- the HUD block: the bars, the pet strip, the aura host, and Party mode's rows hanging under it. nbar11: read at any alpha, and
    -- unioned with the block's own geometry (Trail P.lay.block: every part the HUD can show in a fight, wherever it is now)
    if type(prd) == "table" and NB.pieceOn(prd.lay) then
        local live = rows
        local fs = { prd.f, prd.petL, type(prd.box) == "table" and prd.box.host or nil, host("hud") }
        for i = 1, 4 do live = NB.union(live, NB.frameRect(fs[i], true)) end        -- (holes: not ipairs)
        local geo = nil
        if type(prd.lay.block) == "function" then
            local L = WC.lay
            local e = (type(L) == "table" and type(L.get) == "function") and L.get("hud") or nil
            local okB, r = pcall(prd.lay.block, e)
            if okB and type(r) == "table" and type(r.x) == "number" and type(r.w) == "number" and r.w > 0 and r.h > 0 then geo = r end
        end
        hud = NB.union(live, geo) or NB.pieceRect(prd.lay, "hud")
        if hud then add({ x = hud.x, y = hud.y, w = hud.w, h = hud.h }, "hud") end
    elseif rows then
        add(rows, "partyhud")                                           -- our HUD bars off: the rows still stand there, kept clear
    end
    local cp = rawget(WC, "compass")
    if type(cp) == "table" and NB.pieceOn(cp.lay) then add(NB.pieceRect(cp.lay, "compass"), "compass") end
    -- the target bar with its cast strip, ToT, Y prompt and its aura host
    if type(uf) == "table" and NB.pieceOn(uf.layTarget) then
        local tf = type(uf.tb) == "table" and uf.tb.f or nil
        local live = nil
        if type(tf) == "table" then
            local fs = { tf, tf.cast, tf.tot, tf.prompt }
            for i = 1, 4 do live = NB.union(live, NB.frameRect(fs[i])) end
        end
        for key, b in pairs(boxes) do
            if type(key) == "string" and key:find("target", 1, true) and type(b) == "table" then live = NB.union(live, NB.frameRect(b.host)) end
        end
        add(live or NB.pieceRect(uf.layTarget, "target"), "target")
    end
    -- the party column on the left (out of Party mode)
    if type(uf) == "table" and NB.pieceOn(uf.layParty) then
        local live = nil
        if pf and pf.at ~= "hud" and type(pf.frames) == "table" then
            for _, f in pairs(pf.frames) do live = NB.union(live, NB.frameRect(f)) end
        end
        add(live or NB.pieceRect(uf.layParty, "party"), "party")
    end
    local ps = rawget(WC, "psLay")
    if type(ps) == "table" and type(ps.pieces) == "table" then
        for _, id in ipairs({ "psarrow", "psguide" }) do
            if NB.pieceOn(ps.pieces[id]) then add(NB.pieceRect(ps.pieces[id], id), id) end
        end
    end
    return out, hud
end
-- nbar11 THE RESERVED GAME FRAMES: the ones that show only in a fight (all in Hub's G.REG). Read out of combat even while hidden (an
-- anchored frame has a rect); each good rect kept for the session. A cast bar always counts (every class casts); the others once they
-- have shown this session (a Cooldown Manager switched off takes no room)
NB.RESERVE = { "PlayerCastingBarFrame", "GamepadPlayerCastingBarFrame", "EssentialCooldownViewer", "UtilityCooldownViewer",
               "BuffIconCooldownViewer", "BuffBarCooldownViewer", "MirrorTimerContainer", "ExtraAbilityContainer", "BossTargetFrameContainer",
               "SwingTimerMainHandFrame", "SwingTimerOffHandFrame", "SwingTimerRangedFrame" }
NB.RESERVE_ALWAYS = { PlayerCastingBarFrame = true, GamepadPlayerCastingBarFrame = true }
-- the game's frames shown NOW and the reserved ones (out of combat only: nil in a fight, nothing read)
function NB.topGame()
    if S(InCombatLockdown) ~= false then return nil end
    local G = rawget(WC, "gameLay")
    local out = {}
    if type(G) ~= "table" or type(G.shown) ~= "function" or type(G.rectNow) ~= "function" then return out end
    pcall(G.find)
    local T = NB.top
    T.res, T.seen = T.res or {}, T.seen or {}
    local have = {}
    for _, id in ipairs(type(G.ids) == "table" and G.ids or {}) do
        local okS, sh = pcall(G.shown, id)
        if okS and sh == true then
            local okR, r = pcall(G.rectNow, G.frame(id))
            T.gameReads = T.gameReads + 1
            if okR and type(r) == "table" and type(r.w) == "number" and r.w > 0 and r.h > 0 then
                out[#out + 1] = { x = r.x, y = r.y, w = r.w, h = r.h, id = id }
                have[id] = true
                T.seen[id] = true
                T.res[id] = { x = r.x, y = r.y, w = r.w, h = r.h, id = id }
            end
        end
    end
    for _, name in ipairs(NB.RESERVE) do
        local id = "g." .. name
        if not have[id] then
            local fr = type(G.frame) == "function" and G.frame(id) or nil
            if fr ~= nil then
                local okR, r = pcall(G.rectNow, fr)
                T.gameReads = T.gameReads + 1
                if okR and type(r) == "table" and type(r.x) == "number" and type(r.y) == "number" and type(r.w) == "number"
                        and type(r.h) == "number" and r.w > 0 and r.h > 0 then
                    T.res[id] = { x = r.x, y = r.y, w = r.w, h = r.h, id = id }
                end
            end
            -- (GetLeft nil on a hidden frame: the last rect seen stays)
            local keep = T.res[id]
            if keep and (NB.RESERVE_ALWAYS[name] or T.seen[id]) then
                out[#out + 1] = { x = keep.x, y = keep.y, w = keep.w, h = keep.h, id = id, reserved = true }
            end
        end
    end
    return out
end
-- the tap pad's band (TapPad's own zone, never smaller than the spec's x 720-1200, y 24-178)
function NB.topBand()
    local L = WC.lay
    if type(L) == "table" and type(L.forbidden) == "function" then
        local ok, zs = pcall(L.forbidden)
        if ok and type(zs) == "table" then
            for _, z in ipairs(zs) do
                if type(z) == "table" and z.why == "pad" and type(z.x) == "number" then return NB.union(z, NB.TOP.BAND) end
            end
        end
    end
    return NB.TOP.BAND
end
function NB.topInputs()
    local W, H, kk = NB.topScreen()
    local _, _, k = NB.stock()
    local L = WC.lay
    local e = (type(L) == "table" and type(L.get) == "function") and L.get("nbar") or nil
    local sc = math.max(NB.S_MIN, math.min(NB.S_MAX, (e and num(e.s)) or 100)) / 100
    local ours, hud = NB.topOurs()
    local bx, by = NB.lay.base()
    -- nbar8: placed = the entry MOVES the piece (a size alone is the Wings' scale, Auto stays on)
    local dx, dy = (e and num(e.dx)) or 0, (e and num(e.dy)) or 0
    local placed = (e ~= nil and (dx ~= 0 or dy ~= 0)) and { x = bx + dx, y = by + dy } or nil
    return { W = W, H = H, s = k * sc * kk, fsc = k * sc, kk = kk, hud = hud, ours = ours, game = NB.top.game or {}, band = NB.topBand(),
             placed = placed, stock = { x = bx, y = by } }
end
-- the solve's inputs as one string (nil = not keyable)
function NB.inputKey(inp)
    local ok, k = pcall(function()
        local out = { string.format("%.4f %.4f %.1f %.1f|", inp.s, inp.fsc, inp.W, inp.H) }
        local function rk(r) out[#out + 1] = r and string.format("%.1f,%.1f,%.1f,%.1f;", r.x, r.y, r.w or 0, r.h or 0) or "-;" end
        rk(inp.hud); rk(inp.band); rk(inp.placed); rk(inp.stock)
        for _, r in ipairs(inp.ours or {}) do rk(r) end
        out[#out + 1] = "|"
        for _, r in ipairs(inp.game or {}) do rk(r) end
        return table.concat(out)
    end)
    return ok and k or nil
end
-- [fresh]: the game's frames read again (out of combat only); then the spot, and a glide to it if it moved while shown
function NB.topSolve(fresh)
    local T = NB.top
    if fresh and not T.frozen and S(InCombatLockdown) == false then T.game = NB.topGame() or T.game; T.gameAt = SN(GetTime) end
    local ok, inp = pcall(NB.topInputs)
    if not ok or type(inp) ~= "table" then return nil end
    -- nbar11: the same inputs as the last solve -> the same spot (the grid scan is never redone every REFIT_S for nothing)
    local key = NB.inputKey(inp)
    if key and key == T.inKey and T.spot then return T.spot end
    T.inKey = key
    local r = NB.fightSolve(inp)
    local k = r.k or 1                                                  -- nbar11: the solver's shrink on the bar's scale
    r.fsc, r.s, r.kk, r.H = inp.fsc * k, inp.s * k, inp.kk, inp.H
    T.n = T.n + 1
    local old = T.spot
    T.spot = r
    if T.shown and old and T.cur and (math.abs(old.l.x - r.l.x) + math.abs(old.l.y - r.l.y) + math.abs(old.r.x - r.r.x)
            + math.abs(old.r.y - r.r.y)) > 1 then
        T.from, T.mt = { l = { x = T.cur.l.x, y = T.cur.l.y }, r = { x = T.cur.r.x, y = T.cur.r.y } }, 0
    end
    return r
end

-- ---------------------------------------------------------------- the switch, the motion, the frames
function NB.topFlag()
    local pm = rawget(WC, "padMap")
    if type(pm) == "table" and type(pm.combatTopOn) == "function" then
        local ok, v = pcall(pm.combatTopOn)
        return ok and v == true
    end
    return false
end
-- the router's own fight switch (the fight or an attack / auto-repeat armed, not given back by B), else the fight itself
-- nbar11: Party mode on - the bottom band keeps the controls (ruling R0: no takeover in Party mode), so the Wings stay off: the bar is
-- never in both places. nbar11 review: the SAME rule the app reads off the party document (PartyModel.onLive = on && (aside == null ||
-- fight)), from PadMap's own export (WC.padMap.party.live: PM.on() and (R.aside == nil or fighting())) - no raid exclusion (Trail's
-- P.partyModeOn drops raids for the HUD's lift; the app never did, so in a raid the Wings showed while the band stayed below), and an
-- out-of-combat aside counts as off on both sides. An older PadMap without `live`: its plain on()
function NB.partyOn()
    local pm = rawget(WC, "padMap")
    local p = type(pm) == "table" and pm.party or nil
    if type(p) ~= "table" then return false end
    local fn = (type(p.live) == "function" and p.live) or (type(p.on) == "function" and p.on) or nil
    if not fn then return false end
    local ok, v = pcall(fn)
    return ok and v == true
end
-- nbar12 (the user's ruling C, 11-22: "It should stay at the top in party mode"): Party mode on -> the fight alone (the lockdown or the
-- player in combat): the Wings carry the Party layer up top while the app's band slides away on the same fight word (TopCombatBar.
-- partyTop); out of a fight nothing (no takeover in Party mode: an attack armed before the pull keeps the band below, so no Wings)
function NB.topSwitch(party)
    if party == nil then party = NB.partyOn() end
    if party then return S(InCombatLockdown) == true or S(UnitAffectingCombat, "player") == true end
    -- nbar8: in a fight the Wings stay to its end (B's give-back drops the bottom grow's word, never the top screen's bar)
    if S(InCombatLockdown) == true or S(UnitAffectingCombat, "player") == true then return true end
    if type(WC.tgGrown) == "function" then
        local ok, g = pcall(WC.tgGrown)
        if ok and (g == true or g == false) then return g end
    end
    -- nbar11 review: the router's Y not bound (tgGrown nil): the same armed attack the app's grow follows (hudS.aa / hudS.ar, this
    -- file's autoAttack / autoRepeat), so an attack armed before the pull shows the Wings as the band slides away
    if autoAttack == true or autoRepeat == true then return true end
    return S(InCombatLockdown) == true or S(UnitAffectingCombat, "player") == true
end
function NB.chatUp()
    local ct = rawget(WC, "chatTop")
    return type(ct) == "table" and NB.pieceOn(ct.lay)
end
function NB.lerpSpot(a, b, k)
    return { l = { x = a.l.x + (b.l.x - a.l.x) * k, y = a.l.y + (b.l.y - a.l.y) * k },
             r = { x = a.r.x + (b.r.x - a.r.x) * k, y = a.r.y + (b.r.y - a.r.y) * k } }
end
-- the joined bar where it stands out of a fight (the Nintendo bar's spot), at the fight's scale
function NB.joinedNow(sp)
    local bx, by = NB.lay.base()
    local L = WC.lay
    local e = (type(L) == "table" and type(L.get) == "function") and L.get("nbar") or nil
    local cx, cy = bx + ((e and num(e.dx)) or 0), by + ((e and num(e.dy)) or 0)
    local dx = NB.DX * (sp.s or 1)
    return { l = { x = cx - dx, y = cy }, r = { x = cx + dx, y = cy } }
end
-- the chips and the swing lines (built with the bar, out of combat)
function NB.topParts(f)
    local T = NB.TOP
    f.chip, f.sw = {}, {}
    for i, w in ipairs({ "ZL", "ZR" }) do
        local c = CreateFrame("Frame", nil, f)
        pcall(c.EnableMouse, c, false)
        c:SetSize(T.CHIP_W, T.CHIP_H)
        c.key = (i == 1) and "zl" or "zr"
        -- nbar11: the trigger glyph (the band's UiKit.triggerChip look): dim at rest, lit over a soft gold disc while held; the old dark
        -- chip with its word only where the client refuses the glyph
        c.disc = c:CreateTexture(nil, "BACKGROUND")
        c.disc:SetPoint("CENTER", c, "CENTER", 0, 0)
        c.disc:SetSize(T.CHIP_H + 8, T.CHIP_H + 8)
        c.dmask = NB.maskFor(c, c.disc, true)
        if c.dmask then pcall(c.disc.AddMaskTexture, c.disc, c.dmask) end
        pcall(c.disc.SetColorTexture, c.disc, 1, 0.78, 0.3, 0.38)
        c.disc:Hide()
        c.bg = c:CreateTexture(nil, "BACKGROUND", nil, -1)
        c.bg:SetAllPoints(c)
        pcall(c.bg.SetColorTexture, c.bg, 0.08, 0.07, 0.05, 0.8)
        c.g = c:CreateTexture(nil, "ARTWORK")
        c.g:SetAllPoints(c)
        c.word = c:CreateFontString(nil, "OVERLAY", "GameFontNormalSmall")
        c.word:SetPoint("CENTER", c, "CENTER", 0, 0)
        c.word:SetText(w)
        c.gOn = NB.atlasOn(c.g, "gamepad-switch-128x-" .. c.key .. "-normal")
        if c.gOn then c.bg:Hide(); c.word:Hide(); c.g:Show(); pcall(c.g.SetAlpha, c.g, 0.55) else c.g:Hide() end
        c:Hide()
        f.chip[i] = c
    end
    -- nbar12 THE L / R PLATES (ruling A: "like the bottom band's L/R corner plates (with ZL/ZR under them)"): one per half at its outer
    -- top - the game's shoulder plate, its -pressed overlay while held, the targeting picture, the bumper glyph; refused: a dark plate
    -- with "L" / "R", gold while held. Under the chip: Party mode's chord row (three round icons, masked like the faces)
    f.plate, f.chord = {}, {}
    for i = 1, 2 do
        local p = CreateFrame("Frame", nil, f)
        pcall(p.EnableMouse, p, false)
        p:SetSize(T.PLATE_W, T.PLATE_H)
        p.side = (i == 1) and "left" or "right"
        p.bg = p:CreateTexture(nil, "BACKGROUND")
        p.bg:SetAllPoints(p)
        p.bgOn = NB.atlasOn(p.bg, "gamepad-targeting-" .. p.side .. "-shoulder-bg")
        if not p.bgOn then pcall(p.bg.SetColorTexture, p.bg, 0.08, 0.07, 0.05, 0.8) end
        p.held = p:CreateTexture(nil, "BORDER")
        p.held:SetAllPoints(p)
        p.heldOn = p.bgOn and NB.atlasOn(p.held, "gamepad-targeting-" .. p.side .. "-shoulder-pressed")
        p.held:Hide()
        p.icon = p:CreateTexture(nil, "ARTWORK")
        p.icon:SetAllPoints(p)
        p.icon:Hide()
        p.glyph = p:CreateTexture(nil, "OVERLAY")
        p.glyph:SetSize(NB.GLYPH, NB.GLYPH)
        p.glyph:SetPoint("CENTER", p, "CENTER", (i == 1) and T.PLATE_GX or -T.PLATE_GX, T.PLATE_GY)     -- the inner bottom corner
        p.glyph:Hide()
        p.word = p:CreateFontString(nil, "OVERLAY", "GameFontNormalSmall")
        p.word:SetPoint("CENTER", p.glyph, "CENTER", 0, 0)
        p.word:SetText("")
        p:Hide()
        f.plate[i] = p
        f.chord[i] = {}
        for j = 1, 3 do
            local t = f:CreateTexture(nil, "ARTWORK")
            t:SetSize(T.CHORD, T.CHORD)
            local m = NB.maskFor(f, t, true)
            if m then pcall(t.AddMaskTexture, t, m) else pcall(t.SetTexCoord, t, 0.08, 0.92, 0.08, 0.92) end
            t:Hide()
            f.chord[i][j] = t
        end
    end
    local dw = 2 * NB.OFF + NB.B
    for i = 1, 2 do
        local b = CreateFrame("Frame", nil, f)
        pcall(b.EnableMouse, b, false)
        b:SetSize(dw, T.SW_H)
        b.w = dw
        b.bg = b:CreateTexture(nil, "BACKGROUND")
        b.bg:SetAllPoints(b)
        pcall(b.bg.SetColorTexture, b.bg, 0, 0, 0, 0.6)
        b.fill = b:CreateTexture(nil, "ARTWORK")
        b.fill:SetPoint("TOPLEFT", b, "TOPLEFT", 0, 0)
        b.fill:SetPoint("BOTTOMLEFT", b, "BOTTOMLEFT", 0, 0)
        b.fill:SetWidth(1)
        pcall(b.fill.SetColorTexture, b.fill, 0.95, 0.78, 0.2, 1)
        b:Hide()
        f.sw[i] = b
    end
end
-- the chips and the swing lines at the halves' places (offsets in the bar's units)
function NB.topDeco()
    local f, T = NB.f, NB.TOP
    if not (f and f.chip) then return end
    local d2 = NB.OFF + NB.B / 2
    -- nbar12: the outer column - the plate at the top, the chip under it, the chord row under that (NB.outerGeo)
    local g = NB.outerGeo()
    for i = 1, 2 do
        local sgn = (i == 1) and -1 or 1
        local x, y = NB.hx[i] + sgn * g.x, NB.hy[i]
        pcall(f.chip[i].ClearAllPoints, f.chip[i]); f.chip[i]:SetPoint("CENTER", f, "CENTER", x, y + g.chipY)
        local p = f.plate and f.plate[i]
        if p then pcall(p.ClearAllPoints, p); p:SetPoint("CENTER", f, "CENTER", x, y + g.plateY) end
        for j = 1, 3 do
            local t = f.chord and f.chord[i] and f.chord[i][j]
            if t then pcall(t.ClearAllPoints, t); t:SetPoint("CENTER", f, "CENTER", x + (j - 2) * g.chordDX, y + g.chordY) end
        end
    end
    for i = 1, 2 do
        local b = f.sw[i]
        pcall(b.ClearAllPoints, b)
        b:SetPoint("TOP", f, "CENTER", NB.hx[2], NB.hy[2] - d2 - T.SW_GAP - (i - 1) * (T.SW_H + T.SW_SEP))
    end
end
-- the held layer on the chips (set 2 ZL, 3 ZR, 4 both)
function NB.topChips(set)
    local f = NB.f
    if not (f and f.chip) then return end
    local lit = { set == 2 or set == 4, set == 3 or set == 4 }
    for i = 1, 2 do
        local c = f.chip[i]
        local was = c.lit
        c.lit = lit[i]
        if lit[i] then c.word:SetTextColor(1, 0.82, 0) else c.word:SetTextColor(0.75, 0.75, 0.75) end
        if c.gOn and was ~= c.lit then
            if not NB.atlasOn(c.g, "gamepad-switch-128x-" .. c.key .. (c.lit and "-pressed" or "-normal")) then
                NB.atlasOn(c.g, "gamepad-switch-128x-" .. c.key .. "-normal")
            end
            pcall(c.g.SetAlpha, c.g, c.lit and 1 or 0.55)
            if c.lit then c.disc:Show() else c.disc:Hide() end
        end
    end
end
-- nbar12 THE PLATES' PICTURES (the band's drawPlate, Blizzard's SetShoulderIcons): the shoulder layer the held bumpers open - both =
-- shortcuts, LB = friendly, RB = hostile (the other way round with GamepadSwapTargetModifiers) - and its pair, swapped too; a trigger
-- held: the base pair (the triggers' layers win the bar). Pure. -> the left plate's picture, the right one's
function NB.plateKinds(lb, rb, swap, trig)
    local layer = "n"
    if not trig then
        if lb and rb then layer = "sc"
        elseif lb then layer = swap and "ho" or "fr"
        elseif rb then layer = swap and "fr" or "ho" end
    end
    local l, r = "friendly", "hostile"
    if layer == "fr" then l, r = "friendly", "shortcuts"
    elseif layer == "ho" then l, r = "shortcuts", "hostile"
    elseif layer == "sc" then l, r = "shortcuts", "shortcuts" end
    if swap then l, r = r, l end
    return l, r
end
NB.PLATE_ICON = { friendly = "gamepad-targeting-friendly", hostile = "gamepad-targeting-hostile", shortcuts = "gamepad-targeting-shortcuts" }
-- the bumper's glyph: the Switch shoulder letters (the Nintendo layout), the xbox bumpers (plain); [pressed] the held look
function NB.plateGlyph(i, pressed, lay)
    if (lay or NB.layout()) == "plain" then return "gamepad-xbox1-trigger-" .. ((i == 1) and "lb" or "rb") .. (pressed and "-down" or "-normal") end
    return "gamepad-switch-128x-shoulder-" .. ((i == 1) and "l" or "r") .. (pressed and "-pressed" or "-normal")
end
-- the plates now: [sh] the held bumpers ({ l, r }; nil = none: a window owns the pad), [trig] a trigger held. SetAtlas only on a change
function NB.topPlates(sh, trig)
    local f = NB.f
    if not (f and f.plate) then return end
    sh = sh or {}
    local kl, kr = NB.plateKinds(sh.l == true, sh.r == true, NB.swapT == true, trig == true)
    local lay = NB.layout()
    for i = 1, 2 do
        local p = f.plate[i]
        local held = (i == 1 and sh.l == true) or (i == 2 and sh.r == true)
        local kind = (i == 1) and kl or kr
        local key = kind .. (held and "+" or "-") .. lay
        if p.key ~= key then
            p.key, p.kind, p.down = key, kind, held
            p.iconOn = NB.atlasOn(p.icon, NB.PLATE_ICON[kind])
            if p.iconOn then p.icon:Show() else p.icon:Hide() end                -- (refused: the plate and its glyph alone)
            if p.heldOn then
                if held then p.held:Show() else p.held:Hide() end
            elseif not p.bgOn then
                if held then pcall(p.bg.SetColorTexture, p.bg, 0.45, 0.34, 0.08, 0.9) else pcall(p.bg.SetColorTexture, p.bg, 0.08, 0.07, 0.05, 0.8) end
            end
            local g = nil
            for _, a in ipairs({ NB.plateGlyph(i, held, lay), held and NB.plateGlyph(i, false, lay) or false }) do
                if a and NB.atlasOn(p.glyph, a) then g = a; break end
            end
            p.gatlas = g
            if g then p.glyph:Show(); p.word:SetText("") else p.glyph:Hide(); p.word:SetText((i == 1) and "L" or "R") end
            if held then p.word:SetTextColor(1, 0.82, 0) else p.word:SetTextColor(0.85, 0.85, 0.85) end
        end
    end
end

-- nbar12 THE PARTY LAYER'S CONTEXT (reads only, in fights too): the cfg PadMap SENT last (m185 cfgLast - never cfg() / PM.resolve in a
-- fight: that walks the spellbook), the entries in pick order (you, your pet while out, party1..4), the pick, its unit, dead (a plain
-- true only; anything else = alive), its range (PadMap's rng: true / false / nil), the moves. nil = no PadMap party export
function NB.partyCtx()
    local pm = rawget(WC, "padMap")
    local p = type(pm) == "table" and pm.party or nil
    if type(p) ~= "table" then return nil end
    local function call(fn, ...)
        if type(fn) ~= "function" then return nil end
        local ok, v = pcall(fn, ...)
        if ok then return v end
        return nil
    end
    local cfg = call(p.cfgLast)
    local units = call(p.units)
    if type(units) ~= "table" or #units == 0 then units = { "player" } end
    local bx = call(p.box)
    local n = #units
    local box = math.floor(num(type(bx) == "table" and bx.box or nil) or 1)
    box = math.max(1, math.min(n, box))
    local u = units[box]
    local dead = type(u) == "string" and S(UnitIsDeadOrGhost, u) == true or false
    local rng = nil
    if type(u) == "string" then rng = call(p.rng, u) end
    if rng ~= true and rng ~= false then rng = nil end                  -- (only a plain answer counts)
    return { cfg = type(cfg) == "table" and cfg or nil, units = units, n = n, box = box, u = u, pet = units[2] == "pet", dead = dead,
             rng = rng, nav = p.nav }
end
-- the pick's move [dir] ("left" / "up" / "right" / "down") goes somewhere (PadMap's nav, the PARTYPAD snippet's own body)
local function partyMoves(ctx, dir)
    if type(ctx.nav) ~= "function" then return false end
    local ok, nb = pcall(ctx.nav, ctx.n, ctx.box, dir, ctx.pet)
    return ok and type(nb) == "number" and nb ~= ctx.box
end
NB.PARTY_DIR = { "left", "up", "right", "down" }
NB.PARTY_FACE = { [5] = "x", [6] = "y", [7] = "b", [8] = "a" }
NB.PARTY_PRE = { "", "zl", "zr", "" }
-- a party spell's lit / dim on the picked member (PartyStrip.lit): not learned dim; dead: only a res lit; alive: a res dim
function NB.partyLit(sp, dead)
    if type(sp) ~= "table" or sp.k == false then return false end
    if dead then return sp.res == true end
    return sp.res ~= true
end
-- WHAT POSITION [p] SHOWS IN PARTY MODE on layer [set] (1 none, 2 ZL, 3 ZR, 4 both) for [ctx] (NB.partyCtx) - PartyStrip.face /
-- arrows. Pure. -> an entry: "icon" (the arrows: rot / alpha / noBadge; Target), "spell" (id, icon, dim), "empty", or "fixed" (the
-- game's own button there: B's Back, Y's Inspect with no cfg)
function NB.partyPic(p, set, ctx)
    if p <= 4 then
        local dir = NB.PARTY_DIR[p]
        local moves = partyMoves(ctx, dir)
        local icon = (dir == "left") and "atlas:common-icon-backarrow" or "atlas:common-icon-forwardarrow"
        local rot = (dir == "up" and math.pi / 2) or (dir == "down" and -math.pi / 2) or 0
        return { kind = "icon", icon = icon, rot = rot, alpha = moves and 1 or 0.28, noBadge = true, dir = dir, lit = moves }
    end
    local face = NB.PARTY_FACE[p]
    if face == "b" then return { kind = "fixed" } end                       -- Back, on every layer
    if set == 4 then return { kind = "empty" } end                          -- both triggers: nothing
    local cfg = ctx.cfg
    if cfg == nil then
        if face == "y" and set == 1 then return { kind = "fixed" } end    -- the game's own Y
        return { kind = "empty" }
    end
    local function spell(sp)
        if type(sp) ~= "table" or sp.id == nil then return { kind = "empty" } end
        return { kind = "spell", id = num(sp.id), icon = sp.i, dim = not NB.partyLit(sp, ctx.dead), res = sp.res == true }
    end
    if face == "y" and set == 1 then
        -- in a fight (the Wings show in Party mode only then): the In combat spell, else Smart Y's pick for the picked member, else Target
        if type(cfg.yc) == "table" and cfg.yc.id ~= nil then return { kind = "spell", id = num(cfg.yc.id), icon = cfg.yc.i, dim = false } end
        local sy = type(cfg.sy) == "table" and cfg.sy or nil
        local pick = (sy and sy.on == true and type(sy.pick) == "table" and type(ctx.u) == "string") and sy.pick[ctx.u] or nil
        if type(pick) == "table" and pick.id ~= nil then return { kind = "spell", id = num(pick.id), icon = pick.i, dim = false, smart = true } end
        return { kind = "icon", icon = "atlas:gamepad-targeting-friendly", target = true }
    end
    local slots = type(cfg.slots) == "table" and cfg.slots or {}
    return spell(slots[NB.PARTY_PRE[set] .. face])
end
-- the ZL / ZR chord rows: in Party mode the trigger's three party spells (zla / zlx / zly, zra / zrx / zry; an Empty place not drawn),
-- not learned at the band's 130 / 255; out of Party mode hidden
function NB.topChords(ctx)
    local f = NB.f
    if not (f and f.chord) then return end
    local slots = (ctx and type(ctx.cfg) == "table" and type(ctx.cfg.slots) == "table") and ctx.cfg.slots or nil
    for i = 1, 2 do
        local pre = (i == 1) and "zl" or "zr"
        for j, face in ipairs({ "a", "x", "y" }) do
            local t = f.chord[i][j]
            local sp = slots and slots[pre .. face] or nil
            if NB.top.shown and type(sp) == "table" and sp.i ~= nil and NB.paint(t, sp.i) then
                pcall(t.SetAlpha, t, (sp.k == false) and (130 / 255) or 1)
                t:Show()
            else
                t:Hide()
            end
        end
    end
end
-- the split swing lines under the faces (WC.swingDoc: plain numbers of our own; a line shows while its swing runs)
function NB.topSwing()
    local f = NB.f
    if not (f and f.sw) then return end
    local now = SN(GetTime) or 0
    local ok, sw, so = pcall(WC.swingDoc, now)
    for i, r in ipairs({ ok and sw or false, ok and so or false }) do
        local b = f.sw[i]
        if type(r) == "table" and type(r.s) == "number" and type(r.d) == "number" and r.d > 0 and now < r.s + r.d + 0.3 then
            b.k = math.max(0, math.min(1, (now - r.s) / r.d))
            b.fill:SetWidth(math.max(0.01, b.k * b.w))
            b:Show()
        else
            b.k = nil
            b:Hide()
        end
    end
end
-- the halves at [at] (two centres, px): the frame at their middle, each half's buttons round its own centre
function NB.topPlace(at, sp)
    local f, T = NB.f, NB.top
    local s, fsc, kk, H = sp.s, sp.fsc, sp.kk, sp.H
    if not (f and s and s > 0 and fsc and fsc > 0 and kk and kk > 0) then return end
    local mx, my = (at.l.x + at.r.x) / 2, (at.l.y + at.r.y) / 2
    local key = string.format("%.1f %.1f %.1f %.1f %.4f", at.l.x, at.l.y, at.r.x, at.r.y, fsc)
    if key == T.key then return end
    T.key = key
    pcall(f.SetScale, f, fsc)
    pcall(f.ClearAllPoints, f)
    f:SetPoint("CENTER", UIParent, "BOTTOMLEFT", mx / kk / fsc, ((H or 1080) - my) / kk / fsc)
    NB.hx = { (at.l.x - mx) / s, (at.r.x - mx) / s }
    NB.hy = { -(at.l.y - my) / s, -(at.r.y - my) / s }
    local lay = NB.layout()
    for p = 1, 8 do if f.btn[p] then NB.anchor(f.btn[p], lay) end end
    NB.topDeco()
    NB.at = "top:" .. tostring(sp.case)
end
-- the wings start showing / are gone
function NB.topShow()
    local f = NB.f
    for i = 1, 2 do f.chip[i]:Show(); if f.plate then f.plate[i]:Show(); f.plate[i].key = nil end end       -- nbar12: the plates too
    f.layer:SetText("")
    f:Show()
    NB.set = 0
    pcall(NB.refresh)
end
function NB.topHide()
    local f, T = NB.f, NB.top
    T.shown, T.key, T.cur, T.from, T.mt = false, nil, nil, nil, 1
    NB.hx, NB.hy = { -NB.DX, NB.DX }, { 0, 0 }
    for i = 1, 2 do
        f.chip[i]:Hide(); f.sw[i]:Hide()
        if f.plate then f.plate[i]:Hide(); for j = 1, 3 do f.chord[i][j]:Hide() end end                 -- nbar12
    end
    local lay = NB.layout()
    NB.party = nil
    for p = 1, 8 do
        local b = f.btn[p]
        if b then
            -- nbar12: out of the Wings no Party layer: the arrows' turn, alpha and the missing badge back to the bar's own
            if b.noBadge then b.noBadge = false; b.gkey = nil end
            if b.rot and b.rot ~= 0 then b.rot = 0; pcall(b.icon.SetRotation, b.icon, 0) end
            if b.ia and b.ia ~= 1 then b.ia = 1; pcall(b.icon.SetAlpha, b.icon, 1) end
            NB.anchor(b, lay)
        end
    end
    if NB.on or T.edit then
        NB.place()
        pcall(f.SetAlpha, f, 1)
        f:Show()
        NB.set = 0
        pcall(NB.refresh)
    else
        f:Hide()
    end
end
-- one frame of the motion: in / out, a re-fit's glide, the dip
function NB.topApply()
    local T, f = NB.top, NB.f
    if not f or not f.chip then return end
    if T.t <= 0 and T.dir == 0 then
        if T.shown then NB.topHide() end
        return
    end
    local sp = T.spot
    if not sp then return end
    -- nbar8: settled (in, no glide, nothing re-solved, the dip as it was): nothing to place this frame
    local dip = NB.chatUp()
    if T.shown and T.done and T.t >= 1 and T.dir == 0 and T.mt >= 1 and T.key ~= nil and T.cur == sp and T.dipWas == dip
            and T.visWas == NB.vis then
        NB.topSwing()
        return
    end
    T.dipWas, T.visWas = dip, NB.vis
    if not T.shown then T.shown = true; NB.topShow() end
    local to = sp
    if T.mt < 1 and T.from then to = NB.lerpSpot(T.from, sp, NB.ease(T.mt)) end
    T.cur = to
    local e = NB.ease(T.t)
    local from
    if T.form == "glide" then from = NB.joinedNow(sp)
    elseif T.form == "slide" then
        -- nbar11: from wholly below the top screen's bottom edge, straight up (in: ease-out; out: ease-in back down)
        local d = NB.slideDrop(to, sp)
        from = { l = { x = to.l.x, y = to.l.y + d }, r = { x = to.r.x, y = to.r.y + d } }
        e = NB.slideK(T.t, T.sd or 1)
    else from = { l = { x = to.l.x, y = to.l.y + NB.TOP.RISE }, r = { x = to.r.x, y = to.r.y + NB.TOP.RISE } } end
    T.k = e
    NB.topPlace(NB.lerpSpot(from, to, e), sp)
    local a = (T.form == "fade") and e or 1
    if NB.chatUp() then a = a * NB.TOP.DIP end
    T.alpha = a
    pcall(f.SetAlpha, f, (NB.vis == false and 0 or 1) * a)
    T.done = T.t >= 1 and T.dir == 0 and T.mt >= 1                      -- nbar8: this frame laid the settled state itself
    NB.topSwing()
end
-- the top layout editor open (out of combat) with the flag on: the bar at its piece's spot, to be placed (Nintendo mode shows it anyway)
function NB.topEdit(flag, combat)
    local T, f, L = NB.top, NB.f, WC.lay
    local edit = flag and not NB.on and not combat and not T.shown and f ~= nil and type(L) == "table" and L.editing == true
    if edit == T.edit then return end
    T.edit = edit
    if edit then
        NB.place(); pcall(f.SetAlpha, f, 1); f:Show(); NB.set = 0; pcall(NB.refresh)
        -- nbar12: the plates at the halves' places (the joined bar), so the piece shows the Wings' size
        if f.plate then pcall(NB.topDeco); for i = 1, 2 do f.plate[i]:Show(); f.plate[i].key = nil end end
    elseif not NB.on and not T.shown and f then
        f:Hide()
    end
    if not edit and f and f.plate and not T.shown then for i = 1, 2 do f.plate[i]:Hide() end end
end
-- nbar12 (the review, 11-22): Party mode's move as it stands - Hub's rows (PF.at / want / room) and Trail's block (its room for them,
-- the lift, the pet's side, the aura row) - plain fields of our own tables (rawget, no frame read). -> true when one changed since the
-- last tick (never on the first read); no table made per tick
NB.MOVE_KEYS = { { "uf", "at" }, { "uf", "want" }, { "uf", "room" }, { "prd", "partyRoomPx" }, { "prd", "lift" }, { "prd", "petSide" },
                 { "prd", "auraUp" } }
function NB.topMoved()
    local T = NB.top
    local uf, prd = rawget(WC, "uf"), rawget(WC, "prd")
    local pf = type(uf) == "table" and type(rawget(uf, "pf")) == "table" and rawget(uf, "pf") or nil
    local first = T.mv == nil
    if first then T.mv = {} end
    local moved = false
    for i, k in ipairs(NB.MOVE_KEYS) do
        local t = (k[1] == "uf") and pf or (type(prd) == "table" and prd or nil)
        local v = t and rawget(t, k[2]) or nil
        if type(v) == "number" and not WC.isPlain(v) then v = nil end
        if T.mv[i] ~= v then T.mv[i] = v; moved = true end
    end
    return moved and not first
end
-- the driver's tick (every frame): the solve's cadence, the switch, the motion
function NB.topTick(el)
    local T = NB.top
    el = el or 0
    -- nbar9: the flag (PadMap's ctop under the character key) read at the first tick, then every FLAG_S, never every frame (it
    -- changes only on a padtab code or a key that arrives late); with it off and nothing shown, fading or previewed: nothing more
    T.flagAcc = (T.flagAcc or 0) + el
    if T.flag == nil or T.flagAcc >= NB.TOP.FLAG_S then T.flagAcc = 0; T.flag = NB.topFlag() end
    local flag = T.flag
    if not flag and not T.want and not T.shown and not T.edit and T.dir == 0 and T.t <= 0 then return end
    T.solveAcc, T.gameAcc = T.solveAcc + el, T.gameAcc + el
    local combat = S(InCombatLockdown) == true
    -- nbar9: built in a fight too (a /reload or a login inside combat, the key arriving mid-fight): NB.build makes only plain frames,
    -- textures, fontstrings and the game's plain cooldown swipe, EnableMouse(false), anchored to UIParent - legal in combat
    if flag and not NB.built then pcall(NB.build) end
    -- nbar11: while the bar leaves (shown, the switch off) the spot stays as it was - it slides straight down from where it stands; the
    -- fresh solve (the fight's end marks the game's frames dirty) waits until it is gone
    -- nbar12: Party mode on / off (a Select press, in a fight too): re-fit against our frames (Hub's rows join the HUD block), the
    -- layer redrawn this tick, the focus glow's flash
    local party = NB.partyOn()
    if T.party ~= nil and party ~= T.party then
        T.dirty, T.inKey, NB.set = true, nil, 0
        NB.focusAt = SN(GetTime)
    end
    T.party = party
    -- nbar12 (the review, 11-22): the toggle comes first, the rows move later (Hub's 0.1 s step: the room under the HUD and Trail's
    -- block; the fade-out's end: the rows laid under the HUD at alpha 0, then faded in) - a re-fit on the tick each move shows
    if NB.topMoved() then T.dirty = true end
    local sw = NB.topSwitch(party)
    local leaving = T.shown and not sw
    if flag and NB.built and not leaving then
        -- the game's frames read again only out of combat, on their events or every SOLVE_S; ours on a change (a layout save, the
        -- editor's view) or every REFIT_S (in a fight: our frames only, the game's as frozen)
        if not combat and (T.gameDirty or T.gameAcc >= NB.TOP.SOLVE_S) then
            T.gameDirty, T.dirty, T.gameAcc, T.solveAcc = false, false, 0, 0
            NB.topSolve(true)
        elseif T.dirty or T.spot == nil or T.solveAcc >= NB.TOP.REFIT_S then
            T.dirty, T.solveAcc = false, 0
            NB.topSolve(false)
        end
    end
    local want = flag and NB.built and T.spot ~= nil and sw
    if want ~= T.want then
        T.want = want
        local was = T.dir
        T.dir = want and 1 or -1
        if want then
            if not combat then NB.topSolve(true) end                       -- attack armed before the pull: the freshest spot
            if not T.shown then
                -- nbar11: the slide when the lane under both halves is clear, else the fade + rise; the glide in Nintendo mode
                T.form = NB.on and "glide" or ((T.spot and T.spot.lane) and "slide" or "fade"); T.mt, T.from = 1, nil
            end
            if T.form == "slide" and T.t <= 0 and not T.shown then
                T.dir, T.hold, T.sd = 0, NB.TOP.HOLD_IN, 1               -- the band's first frames go first: a 60 ms hold
            end
        else
            T.hold = nil
            -- the way down: a settled slide whose lane closed since (a lifted HUD) leaves by the fade instead (no jump at t = 1)
            if T.form == "slide" and T.t >= 1 and was == 0 and not (T.spot and T.spot.lane) then T.form = "fade" end
        end
        -- a slide reversed mid-way goes on from where it is (the other curve's t at the same progress)
        if T.form == "slide" and T.dir ~= 0 and T.dir ~= (T.sd or 1) then
            local k = NB.slideK(T.t, T.sd or 1)
            T.sd = T.dir
            if T.t > 0 and T.t < 1 then T.t = NB.slideT(k, T.sd) end
        elseif T.dir ~= 0 then
            T.sd = T.dir
        end
    end
    if T.hold then
        T.hold = T.hold - el
        if T.hold <= 0 then T.hold, T.dir, T.sd = nil, 1, 1 end
    end
    if T.dir ~= 0 then
        T.t = T.t + T.dir * el / (T.dir > 0 and NB.TOP.IN_S or NB.TOP.OUT_S)
        if T.t >= 1 then T.t, T.dir = 1, 0 elseif T.t <= 0 then T.t, T.dir = 0, 0 end
    end
    if T.mt < 1 then T.mt = math.min(1, T.mt + el / NB.TOP.IN_S) end
    NB.topEdit(flag, combat)
    NB.topApply()
end
function NB.topEvent(e)
    local T = NB.top
    if e == "PLAYER_REGEN_DISABLED" then T.frozen = true                -- the game's rects stay as last read out of combat
    elseif e == "PLAYER_REGEN_ENABLED" then T.frozen = false; T.gameDirty = true
    else T.gameDirty = true end
end
do
    local drv = CreateFrame("Frame", nil, UIParent)
    pcall(drv.EnableMouse, drv, false)
    drv:SetScript("OnUpdate", function(_, el) local ok, err = pcall(NB.topTick, el); if not ok then noteError(err) end end)
    for _, e in ipairs({ "PLAYER_ENTERING_WORLD", "PLAYER_REGEN_DISABLED", "PLAYER_REGEN_ENABLED", "UI_SCALE_CHANGED", "DISPLAY_SIZE_CHANGED" }) do
        pcall(drv.RegisterEvent, drv, e)
    end
    drv:SetScript("OnEvent", function(_, e) pcall(NB.topEvent, e) end)
    NB.drv = drv
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
    "WEAPON_SLOT_CHANGED", "PLAYER_EQUIPMENT_CHANGED",                 -- nbar5: a hand swapped mid-fight
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
do                                                  -- nbar5: the player's weapon speed (haste) for the swing lines
    local ok, r = pcall(function() return ev:RegisterUnitEvent("UNIT_ATTACK_SPEED", "player") end)
    if not ok or r == false then reg("UNIT_ATTACK_SPEED") end
end
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
        WC.stateEdge(event)             -- nbar11: the fight's end at once
        layoutSoon()                    -- f-class-radial: a radial that changed in the fight gets its touch cells now
    elseif event == "PLAYER_REGEN_DISABLED" then
        WC.stateEdge(event)             -- nbar11: the fight's start at once
    elseif event == "START_AUTOREPEAT_SPELL" then
        autoRepeat = true
        WC.stateEdge(event)
    elseif event == "STOP_AUTOREPEAT_SPELL" then
        autoRepeat = false
        WC.stateEdge(event)
    elseif event == "PLAYER_SWING" then
        WC.swingNote(a1, a2)
        requestState()
    elseif event == "UNIT_ATTACK_SPEED" then            -- nbar5: a haste change rescales a running swing
        if SV(a1) == "player" then WC.swingSpeed(); requestState() end
    elseif event == "WEAPON_SLOT_CHANGED" then          -- nbar5: a hand swapped mid-fight
        WC.swingWeapons(nil)
        requestState()
    elseif event == "PLAYER_EQUIPMENT_CHANGED" then
        local slot = SN(function() return tonumber(a1) end)
        if slot == 16 or slot == 17 then WC.swingWeapons(slot); requestState() end
    elseif event == "PLAYER_ENTER_COMBAT" then
        autoAttack = true
        WC.stateEdge(event)
    elseif event == "PLAYER_LEAVE_COMBAT" then
        autoAttack = false
        WC.stateEdge(event)
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
