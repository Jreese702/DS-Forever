-- WowCompanionSpells / Spells.lua
-- The SPELLS room's feed: the spellbook the way this client's own window (PlayerSpellsFrame, camelot
-- flavour) organises it, so the bottom screen can show it without the game's window.
--
-- READ-ONLY. This file reads the book and writes documents. It touches no game frame, hides nothing,
-- takes no controller button and sends nothing to the game. Acting (a spell onto a controller button,
-- casting from the book) and hiding the game's window come later, each after the user's ruling.
-- (s10) EXCEPT THE MACROS ROOM's block at the end of this file (the user, 2026-11-13: "Lets scrap the smart ranged attack and start
-- building a macro room"): it holds the controller while the room shows and changes the player's own macros, ONLY on the player's own
-- New / Done / Delete (CreateMacro / EditMacro / DeleteMacro), never in a fight; see that block.
--
-- WHAT THE GAME'S BOOK HOLDS IN THIS CLIENT (read from blizzard_playerspells/camelot, 2026-09-28):
--   * icon tabs along the top: one per skill line (General, then the class's schools), then Pet;
--   * per spell: icon, name, sub-name (the rank, or "Passive"); a spell not yet known is shown grey
--     with "Available at level N" (level above the player's) or "Trainable";
--   * two of the player's settings filter it: hide passives (CVar spellBookHidePassives) and show all
--     ranks (CVar ShowAllSpellRanks; off = lower ranks hidden). Both travel, so the room filters alike.
--
-- WHAT IT SENDS: one {"type":"spells"} document, only when the book changed (a spell learned, a rank,
-- a spell put on or taken off a bar, the pet, a setting). Cooldowns are deliberately NOT in it: they
-- change with every cast and are secret in combat; the Cast room already draws them on the bars.
-- It also rides the core's 15 s heartbeat for free, like the Quests feed.
--
-- NEW SPELLS: LEARNED_SPELL_IN_SKILL_LINE is recorded in `news`, and SPELL_PUSHED_TO_ACTIONBAR (the
-- game's own fly-in onto a free controller button) in `pushed`, with the book's own count `nid`, so
-- the app can tell a new one from one it has seen. Whether Forever's server switches the fly-in off
-- (game rule ActionbarIconIntroDisabled) travels as `introOff`: that answers an open question on the wire.
--
-- Every value goes through S()/SN() (the T* wrappers), so nothing raw is ever concatenated.

local ADDON = ...
local WC = _G.WowCompanion
if type(WC) ~= "table" or type(WC.S) ~= "function" or type(WC.send) ~= "function" then return end
local S, SN, CALL = WC.S, WC.SN, WC.CALL

local SV = "s18"     -- bump on every change; s18 NO PARTIAL CHARACTER KEY (the 11-20 hunter, test build 1513: an empty GetRealmName() keyed the pet spells and the macro room under "Name-?" / nothing): PB.db and the macro room's who() are Safety k6's WC.charKey() - nil until the realm is known; s17 BLANK FIRST IN THE PRESETS (the user, 2026-11-18: "I need a blank template option on macros to just start a fresh new blank template"): MR.PRESETS' first tile is "blank" (no picks, no lines, the default icon's picture); on an empty macro (a New's editor) A on it writes nothing (only the icon back to the default when it is not, ONE EditMacro) and the editor goes blank - no tiles for this macro (st.blank = its name), the glow on + Line, More offers Presets again; on a macro with lines (More > Presets) its preview's Done asks Replace (Cancel [B] / Replace [A] within DEL_S) and the A is ONE EditMacro(i, nil, default icon, "") - the macro's name kept; never in a fight ("combat"), the other refusals as a preset's Done; the 8 tiles include it (the "last" Mouseover heal drops first); no row and no cell added; s16 X = REMOVE IN THE MACROS ROOM'S EDITOR (the user, 2026-11-18: "in the macro menu I need a button to delete a line, maybe just on the X glyph"; the pick "X = Remove"): in the editor's line list X on the focused line REMOVES it through the same ask as the builder's Remove (Cancel [B] / Remove [A] within DEL_S, ONE EditMacro on the A; the document's `rem` = the line asking, `xr` = the focused line shows the X Remove hint); a "|" line is refused ("locked"), a fight refuses the ask ("combat", no hint then) and the A; the line gone, the glow stays in its place (the next line, else the last, else + Line) and a draft held for this macro follows (its own line gone: the draft goes); the old hidden X "a line below" is More's first row "Insert below" (`o` "ins", `l` = the line the glow was last on); A on + Line still adds at the end; the builder's X (+ Case) and the grid's X (Bind) unchanged; no row and no cell added (a finger's X hint taps the row's `x`); s15 THE MACROS ROOM'S LOOK PASS (the user, 2026-11-16: "it needs to be a bit more visually consistent and the icon needs work for the room"; the pick "Full match": the rooms' flat header with no X, normal tabs with gold arrows, Morpheus headings, one A button plus Y More like Quests, Cancel / Confirm for delete / replace, the builder's pieces in theme colours, the game's tooltip): the editor's buttons are + Line and MORE (s21, s22), the builder's Done and MORE (s19, s17); More is a one-column pop-out (`k` "more": Presets / Delete in the editor, Aa Text / Remove or Revert in the builder; a row the fight refuses says `off`); Y opens More wherever Y has no work of its own (a line's Move, a case's Remove, a chip's reset keep theirs: the document's `ym`); Delete, a stored line's Remove and a preset's Replace ASK (the app draws Cancel [B] / Confirm [A]): while one asks, A confirms, B cancels and every other press does nothing, for DEL_S = 6 s (Quests' confirm) - no relabel arm, no row and no cell added (38 columns, the Calendar row's guest still); s14 REVIEW (same branch, same tag, as s10's; the user, 2026-11-16: the recipes "make no sense"): a stored line opened and finished unchanged writes NOTHING even when its spaces differ from the composed text (MR.dirty by MR.norm, the parser's own rule: no Revert, no Draft); a fight refuses Done and a preset's Done as "combat" before any fault (the design's 7: the chips neutral); a preset's missing pick puts the cursor on THAT pick; a unit typed by name with accents (es / fr realms) reads back as a unit, not a text line; a stored line's Chat line opens its keyboard on the line's own text (an empty Done would remove it without Remove's second A); s14 THE MACRO BUILDER v2 (the user, 2026-11-16: the Macros room's recipes "make no sense" and only add lines; the picks: every RECOMMENDED option of design_macrobuilder): the block at the end of this file builds each LINE - an action and its spell, then "first try on ... if ..." / "else ..." cases (MR.parse / MR.compose / MR.check, the one grammar; the app has none) - shown as strips of icons and short tags (`ls`); PRESETS (whole macros) on a new or empty macro, replacing one with lines after a second A; Select = Done (capture v4 extra PADBACK; Start stays the pause wheel); "On" lists the controller's targets first (soft enemy / soft friend, the CVar read only), Mouseover last; the companion's own "DS Combat" / "DS Attack" macros are view only (a lock); the writes: the builder's Done, a line's Remove, a Move's drop, a preset's Done, the icon - ONE EditMacro each behind the one guard; the row's keys gain y / ok (38 columns, the Calendar row's guest still); s13 THE PET'S PICKS IN THE MACROS ROOM (the user, 2026-11-16: "the macro room needs some work- ... also things like my pet attack are missing from the spells options."): with a pet out, + Spell/Item lists the pet's commands (the game's own secure slash commands: /petattack, /petfollow, /petstay, /petpassive, /petdefensive, /petaggressive, /petdismiss, and /petmoveto / /petassist / /petdefensiveassist only when the pet bar has that button; the game's words and pet-bar pictures; row kind "p" with its line `l`) and the pet book's spells ("/cast <name>", Spells s10p's bindable ones), after the player's spells, before the bag items (MR.petPicks); a recipe takes the pet's spells, never a command; read only, nothing else changed; s12 THE MACROS ROOM'S ROW SHARED WITH THE CALENDAR'S (the 1.0.32 test build, code 1472: with six bags, the cast bar and both radials the 56-row tap pad had no row left for the lazy "mac" row, and the room said "No pad row"): "mac" is the GUEST of the Calendar room's row "cal" (TapPad pad21 opts.share; the two rooms are never on the bottom screen together), leased to the room on each ask (Book's padtab 39 then 34; the app asks on every showing: the document's `sh`), its keys only while it is on the row (MR.onRow); the game's macro window makes / takes the row only while the room wants the controller; with no Calendar row, a row of its own as before; s11 THE 1.0.32 TEST BUILD MERGE (code 1472): feat/macro-room and feat/pet-spell-binding both tagged s10, both kept (The Macros room = s10, Pet spells = s10p, its reviews = s10a / s10b; the pet branch's Book b63 / PadMap m163 are b63p / m163p in its notes); s10 THE MACROS ROOM (the user, 2026-11-13: "Lets scrap the smart ranged attack and start building a macro room. The calender can be moved up to a button on the right side of the map name make it small. Use the room slot for macros."): the block at the end of this file (WC.macroRoom: the "macros" document, the controller guest "macros", the lazy pad row "mac", the player's own New / Done / Delete, the Bind box for a macro); s10 REVIEW (same branch, same tag, as eaa3d23c did; the user's "start building a macro room"): the selection is the macro's INDEX while it still holds its name (the game lets two macros share a name: A / Delete / the preview hit the one under the glow), a write re-finds its macro by the game's own returned index; a bind of a name another macro shares is refused ("taken": our buttons find a macro by name); "#showtooltip" goes on the macro's FIRST line; `wxa` = the `wx` cell really is the window's close button (its ack says so); `norow` also when the row exists but is not placed (the smallest UI scale); our stored binds follow a rename / go with a delete only when no other macro keeps that name; a finger's a / x / b no longer claims the controller (only the capture's own press proves a hold); s10b (review of feat/pet-spell-binding, #234, the user: "build it"): a pet spell is PASSIVE as the game's own Spellbook says (its book item's isPassive / IsSpellBookItemPassive, the spell's own IsSpellPassive), never by the pet bar's state - the server packs every spell that cannot auto-cast with state 1, so the high-byte-1 / IsPetActionPassive readings made Seduction, Spell Lock and Sacrifice "passive": no number, no Bind, hidden with Hide Passives; s10a (review of feat/pet-spell-binding, #234, the user: "build it"): only a pet WITH A BOOK (the game's own Pet tab: HasPetSpells() and PetHasSpellbook()) has spells of ours - a charmed / possessed unit (Mind Control, Enslave) has pet spells but no book, so its spells are never kept as this character's pet spells (WC.spellsPetName), never numbered, and a travelling pet slot reads nothing then (WC.spellsPetAt / WC.spellsPetSlotOf): a mob's spell sharing an id with one of the player's own never turns that spell into a "pet spell" cast by bare name; s10p PET SPELLS (#234, the user: "build it"): the pet book's SPELLS get numbers (`pv`, after the player's top ranks, before the lower ranks), never its passives or its commands (Attack / Follow / Stay ... have their homes); each pet item says `cmd` (a command), `bind` (a spell that can go on a button) and `pb` (on the pet bar), its bars are the game's own FindPetActionButtons; the Pet tab is line WC.SPELLS_PET_LINE (101); a pet book slot k travels as WC.SPELLS_PET_SLOT0 + k (5000 + k) through the Bind box and the Controller room's puts (WC.spellsPetAt / WC.spellsBankSlot read it back); WC.spellsPetName(id) = the name our own secure buttons cast a pet spell by (CastSpellByName, the game's /cast), remembered per character (WowCompanionSpellsDB.petSpellBy[name-realm]); s9 RANK UP REVIEW (the user, 2026-11-12: "I know we have something in place to rank up abilities on login, but maybe for skills that are already on a bar a prompt to allow them to be ranked up when a new rank is learned is available?"): WC.spellsNewsHas(id) = that spell was learned this load (PadMap m144: rankSync leaves an entry for the rank-up prompt only then); s8 RANK UP (the user, 2026-11-12: "I know we have something in place to rank up abilities on login, but maybe for skills that are already on a bar a prompt to allow them to be ranked up when a new rank is learned is available?"): `rk` = for each just-learned spell that is the top rank now, how many lower copies are on buttons (PadMap m143 WC.rankUpList: bars, radial, In-combat), so Home can ask; s7 s7: loose fly-out members (aspects) numbered; the app shows it, so a stale build is visible

local MAX_LINES = 24        -- skill lines read (the game has General + class + a few)
local MAX_ITEMS = 400       -- book entries read, all lines together
local MAX_PET = 40          -- pet book entries
local MAX_BARS = 6          -- bar slots listed per spell
local MAX_DESC = 600        -- characters of a spell's description
local KEEP_NEWS = 12        -- learned / pushed entries kept

local BANK_PLAYER, BANK_PET = 0, 1   -- Enum.SpellBookSpellBank (read from the enum when it exists)
local T_SPELL, T_FUTURE, T_PETACTION, T_FLYOUT = 1, 2, 3, 4   -- Enum.SpellBookItemType

local function real(v)
    return v ~= nil and v ~= WC.ABSENT and v ~= WC.SECRET and v ~= WC.THREW
end

-- S() that also counts sentinel hits, so a shift in the secrecy map under a patch shows up on the
-- wire (`sec`) instead of as a quietly emptier room. ABSENT is not counted: nil is a normal answer.
local tally = { secret = 0, threw = 0 }
local function T(fn)
    local v = S(fn)
    if v == WC.SECRET then tally.secret = tally.secret + 1
    elseif v == WC.THREW then tally.threw = tally.threw + 1 end
    return v
end
local function TS(fn) local v = T(fn); if type(v) == "string" and real(v) then return v end end
local function TN(fn) local v = T(fn); if type(v) == "number" then return v end end
local function TB(fn) local v = T(fn); if type(v) == "boolean" then return v end end
local function yes(b) if b then return true end end    -- a false flag is left out of the document

do  -- the enums, when the client has them (it does on 1.60.1; the numbers above are the fallback)
    local ok, bank = CALL(function() return Enum.SpellBookSpellBank end)
    if ok and type(bank) == "table" then
        BANK_PLAYER = SN(function() return bank.Player end) or BANK_PLAYER
        BANK_PET = SN(function() return bank.Pet end) or BANK_PET
    end
    local okt, kinds = CALL(function() return Enum.SpellBookItemType end)
    if okt and type(kinds) == "table" then
        T_SPELL = SN(function() return kinds.Spell end) or T_SPELL
        T_FUTURE = SN(function() return kinds.FutureSpell end) or T_FUTURE
        T_PETACTION = SN(function() return kinds.PetAction end) or T_PETACTION
        T_FLYOUT = SN(function() return kinds.Flyout end) or T_FLYOUT
    end
end

-- The game's own spellbook reads these with the global GetCVarBool; C_CVar is the fallback.
local function cvarBool(name)
    if type(GetCVarBool) == "function" then return TB(function() return GetCVarBool(name) end) end
    return TB(function() return C_CVar.GetCVarBool(name) end)
end

-- The controller-bar slots that hold this spell (base spell id, as the API asks).
local function barsFor(actionID)
    if not actionID then return nil end
    local ok, slots = CALL(function() return C_ActionBar.FindSpellActionButtons(actionID) end)
    if not ok or type(slots) ~= "table" then return nil end
    local out = {}
    for _, s in ipairs(slots) do
        local n = SN(function() return s end)
        if n then out[#out + 1] = n end
        if #out >= MAX_BARS then break end
    end
    if #out > 0 then return out end
end

-- WHERE a storage slot sits on the controller bars, the inverse of the game's own
-- GamepadActionBarBindingUtil.GetGamepadStorageSlotIndexFromPageAndPageUnitSlotID (actionbindingutil.lua):
-- page 1..3, bar 1..4 (1 = no trigger, 2 = LT, 3 = RT, 4 = LT + RT, as the Cast replica draws them), pos 1..8
-- (D-pad left / up / right / down, then the face buttons left / top / right / bottom by POSITION). The base bar's
-- four face buttons are reserved (Interact, Inspect, Back, Jump) and own no storage, so each page stores 28.
-- The constants are read from the client, never assumed; nil = not a gamepad slot (e.g. a keyboard bar slot).
-- THE FORMS' BARS (class-exclusive: a warrior's stances, a druid's forms, a rogue's stealth). In a form the controller's bar can be a STANCE
-- bar laid over one of the four sets, with storage slots of its own; the game names only the ACTIVE form's first slot
-- (C_GamepadUI.GetFirstGamepadActionBarStorageSlotIndexForActiveStance). So each form's bar is learned while the player is in it, and kept
-- for this session (SavedVariables are not read back on this beta): first slot -> the set it lies over, the page, the form's name.
local stanceSeen = {}
local function learnStance()
    if not (C_GamepadUI and type(C_GamepadUI.GetFirstGamepadActionBarStorageSlotIndexForActiveStance) == "function") then return end
    local first = TN(function() return C_GamepadUI.GetFirstGamepadActionBarStorageSlotIndexForActiveStance() end)
    if not first then return end
    local idx = TN(function() return GetShapeshiftForm() end)
    local form
    if idx and idx > 0 then
        local okf, _, _, _, sid = CALL(function() return GetShapeshiftFormInfo(idx) end)
        local spellID = okf and SN(function() return sid end)
        if spellID then
            form = TS(function() return C_Spell.GetSpellName(spellID) end)
                or TS(function() return C_Spell.GetSpellInfo(spellID).name end)
        end
    end
    local pu = _G.GamepadMainActionBarFrame and _G.GamepadMainActionBarFrame.PageUnit
    local stanceBar = pu and pu.actionBars and pu.actionBars.stanceBar
    local okb, bars = CALL(function() return pu:GetPageableActionBarsInIndexOrder() end)
    if not (stanceBar and okb and type(bars) == "table") then return end
    for i, bar in ipairs(bars) do
        local ok, yes = CALL(function() return stanceBar:IsBarActivelyOverridingActionBar(bar) end)
        if ok and yes == true then
            stanceSeen[first] = { set = i, page = TN(function() return pu:GetCurrentPage() end) or 1, form = form }
            return
        end
    end
end

-- WHERE a storage slot sits on the controller bars, the inverse of the game's own
-- GamepadActionBarBindingUtil.GetGamepadStorageSlotIndexFromPageAndPageUnitSlotID (actionbindingutil.lua):
-- page 1..3, bar 1..4 (1 = no trigger, 2 = LT, 3 = RT, 4 = LT + RT, as the Cast replica draws them), pos 1..8
-- (D-pad left / up / right / down, then the face buttons left / top / right / bottom by POSITION). The base bar's
-- four face buttons are reserved (Interact, Inspect, Back, Jump) and own no storage, so each page stores 28.
-- A form's bar slot is named from what was learned in that form, with the form's name.
-- The constants are read from the client, never assumed; nil = not a gamepad slot (e.g. a keyboard bar slot).
local function padWhere(slot)
    local K = Constants and Constants.GamepadActionBarConstants
    local perBar = K and SN(function() return K.NUM_SLOTS_PER_GAMEPAD_ACTION_BAR end)
    if not perBar or perBar < 1 then return nil end
    for first, st in pairs(stanceSeen) do
        if slot >= first and slot < first + perBar then
            return { s = slot, pg = st.page, b = st.set, p = slot - first + 1, form = st.form }
        end
    end
    local valid = TB(function() return C_GamepadUI.IsValidGamepadActionStorageSlotIndex(slot) end)
    if not valid then return nil end
    local first = TN(function() return C_GamepadUI.GetFirstGamepadActionStorageSlotIndex() end)
    local perPage = K and SN(function() return K.NUM_PAGEABLE_SLOTS_PER_GAMEPAD_ACTION_BAR_PAGE_UNIT_STANDARD_PAGE end)
    local reserved = K and SN(function() return K.NUM_RESERVED_SLOTS_PER_GAMEPAD_ACTION_BAR_PAGE_UNIT end)
    local pages = K and SN(function() return K.NUM_STANDARD_PAGES_PER_GAMEPAD_ACTION_BAR_PAGE_UNIT end)
    if not (first and perPage and reserved and pages) then return nil end
    local stored = perPage - reserved                 -- storage slots per page
    local off = slot - first
    if off < 0 or stored < 1 then return nil end
    local page = math.floor(off / stored) + 1
    if page > pages then return nil end               -- a stance or possess slot not learned: not on the standard pages
    local r = off % stored
    local firstReserved = 5                           -- FIRST_RESERVED_PAGE_UNIT_SLOT_INDEX, a local in the game's file
    local id = r + 1
    if id >= firstReserved then id = id + reserved end
    return { s = slot, pg = page, b = math.floor((id - 1) / perBar) + 1, p = (id - 1) % perBar + 1 }
end

-- REAGENTS AND TOOLS (class-exclusive: a warlock's soul shards, a shaman's totems, a mage's runes, a rogue's poisons, a priest's candles), as
-- the game's own tooltip writes them: the line that starts with its own words SPELL_REAGENTS ("Reagents: ") or SPELL_TOTEMS ("Tools: "), read
-- at run time so another language works too. The game writes it red when the character has none. This build's API has no reagent call.
local function prefixOf(g)
    local v = _G[g]
    if type(v) ~= "string" or v == "" then return nil end
    return (v:gsub("%%s.*$", ""))
end
local function plain(t) return (t:gsub("|c%x%x%x%x%x%x%x%x", ""):gsub("|r", "")) end
local function needsOf(spellID)
    if not (C_TooltipInfo and type(C_TooltipInfo.GetSpellByID) == "function") then return nil end
    local okt, data = CALL(function() return C_TooltipInfo.GetSpellByID(spellID) end)
    if not okt or type(data) ~= "table" or type(data.lines) ~= "table" then return nil end
    local pr, pt = prefixOf("SPELL_REAGENTS"), prefixOf("SPELL_TOTEMS")
    local out
    for n, line in ipairs(data.lines) do
        if n > 40 then break end
        local raw = TS(function() return line.leftText end)
        if raw then
            local text = plain(raw)
            local kind = (pr and text:sub(1, #pr) == pr and "rg") or (pt and text:sub(1, #pt) == pt and "tl") or nil
            if kind then
                local r = SN(function() return line.leftColor.r end)
                local g = SN(function() return line.leftColor.g end)
                local red = (r and g and r > 0.9 and g < 0.3) or raw:lower():find("|cffff2020", 1, true) ~= nil
                out = out or {}
                out[kind] = { t = text:sub(1, 160), miss = yes(red) }
            end
        end
    end
    return out
end

local function padOf(bars)
    if not bars then return nil end
    local out = {}
    for _, slot in ipairs(bars) do
        local w = padWhere(slot)
        if w then out[#out + 1] = w end
    end
    if #out > 0 then return out end
end

-- What the spell costs, as the book's own call gives it: one entry per power type (rarely more than one).
-- A cost that needs an aura the player does not have is left out, as the game's tooltip leaves it out.
local function costOf(slot, bank)
    local ok, list = CALL(function() return C_SpellBook.GetSpellBookItemPowerCost(slot, bank) end)
    if not ok or type(list) ~= "table" then return nil end
    local out = {}
    for _, c in ipairs(list) do
        local need = TN(function() return c.requiredAuraID end)
        local has = TB(function() return c.hasRequiredAura end)
        if not (need and need > 0 and has == false) then
            local e = {
                c = TN(function() return c.cost end),
                m = TN(function() return c.minCost end),
                pct = TN(function() return c.costPercent end),
                ps = TN(function() return c.costPerSec end),
                t = TN(function() return c.type end),
                tok = TS(function() return c.name end),
            }
            if e.m == e.c then e.m = nil end
            if e.pct == 0 then e.pct = nil end
            if e.ps == 0 then e.ps = nil end
            if e.c or e.pct or e.ps then out[#out + 1] = e end
        end
        if #out >= 3 then break end
    end
    if #out > 0 then return out end
end

local function clip(s, n)
    if type(s) == "string" and #s > n then return s:sub(1, n) end
    return s
end

-- ---------------------------------------------------------------- s10 THE PET'S BOOK (#234, the user: "build it")
-- The game's own Pet tab (blizzard_playerspells SpellBookPetCategoryMixin: shown while C_SpellBook.HasPetSpells() and PetHasSpellbook(),
-- bank Enum.SpellBookSpellBank.Pet), a hunter's pet and a warlock's demon alike. Its entries are the pet's SPELLS (Growl, Bite, Firebolt
-- ...), its passives and its COMMANDS (Attack, Follow, Stay, the stances: the pet bar's tokens). A SPELL gets a number and Bind; a
-- passive and a command get neither (the commands have their homes: the Controller room's Extra tab, the class radial). How each is told
-- apart, every reading the client offers (any one is enough):
--   command: no spell behind it (spellID / C_PetInfo.GetSpellForPetAction nil or 0), a token name (PET_ACTION_* / PET_MODE_*, as this
--            client answers GetPetActionInfo: ActionBar b-pet-tokens), the game's own word for one (_G.PET_ACTION_ATTACK ...), the pet bar's
--            packing (the action id's high byte 7 = a command, 6 = a stance), or a spell id the client cannot name;
--   passive: info.isPassive, C_SpellBook.IsSpellBookItemPassive(slot, Pet) (the game's own Spellbook: its "Passive" line and its Hide
--            Passives filter), C_Spell.IsSpellPassive. s10b: NOT the packing's high byte 1 nor C_PetInfo.IsPetActionPassive (both read the
--            pet bar's STATE): the server packs every spell that cannot auto-cast with state 1 (its "ACT_PASSIVE": 0x81 / 0xC1 are 1 | the
--            auto-cast bits), so Seduction, Spell Lock or Sacrifice read "passive" there although the game's book shows and casts them.
-- READ ONLY, like the rest of this file; Book.lua lists the very same entries (WC.spellsPetBook), so the room and the controller agree.
WC.SPELLS_PET_LINE = 101
WC.SPELLS_PET_SLOT0 = 5000
local PB = { WORDS = { "PET_ACTION_ATTACK", "PET_ACTION_FOLLOW", "PET_ACTION_WAIT", "PET_ACTION_MOVE_TO", "PET_ACTION_DISMISS",
                        "PET_MODE_AGGRESSIVE", "PET_MODE_DEFENSIVE", "PET_MODE_DEFENSIVEASSIST", "PET_MODE_PASSIVE", "PET_MODE_ASSIST",
                        "PET_DISMISS" }, live = {} }
function PB.high(a) return (type(a) == "number" and a > 16777215) and (math.floor(a / 16777216) % 256) or nil end
function PB.command(name, id, a)
    if not id or id <= 0 then return true end
    if name:find("^PET_ACTION_") or name:find("^PET_MODE_") then return true end
    local hi = PB.high(a)
    if hi == 6 or hi == 7 then return true end
    for _, g in ipairs(PB.WORDS) do
        local w = rawget(_G, g)
        if type(w) == "string" and w ~= "" and w == name then return true end
    end
    return TS(function() return C_Spell.GetSpellName(id) end) == nil
end
-- one entry of the pet's book: { slot, vs, t, a, id, n, i, pas, low, cmd, bind, pet = true }, or nil
function PB.entry(slot)
    local ok, info = CALL(function() return C_SpellBook.GetSpellBookItemInfo(slot, BANK_PET) end)
    if not ok or type(info) ~= "table" then return nil end
    local n = TS(function() return info.name end)
    if not n then return nil end
    local a = TN(function() return info.actionID end)
    local id = TN(function() return info.spellID end)
    if (not id or id <= 0) and a and C_PetInfo and type(C_PetInfo.GetSpellForPetAction) == "function" then
        id = TN(function() return C_PetInfo.GetSpellForPetAction(a) end)
    end
    if id and id <= 0 then id = nil end
    local t = TN(function() return info.itemType end)
    local pas = TB(function() return info.isPassive end) == true
        or TB(function() return C_SpellBook.IsSpellBookItemPassive(slot, BANK_PET) end) == true
        or (id and C_Spell and type(C_Spell.IsSpellPassive) == "function" and TB(function() return C_Spell.IsSpellPassive(id) end) == true)
        or false
    local cmd = PB.command(n, id, a)
    local e = { slot = slot, vs = WC.SPELLS_PET_SLOT0 + slot, t = t, a = a, id = (not cmd) and id or nil, n = n,
                i = TN(function() return info.iconID end), pas = yes(pas), cmd = yes(cmd), pet = true,
                low = yes(TB(function() return C_SpellBook.IsSpellBookItemLowRank(slot, BANK_PET) end)) }
    e.bind = yes(not cmd and not pas and e.id ~= nil and t ~= T_FUTURE and t ~= T_FLYOUT)
    return e
end
-- the standard bar slots that hold a pet spell: the game's own C_ActionBar.FindPetActionButtons (its action id), else the spell's own
-- lookup (such a slot reads "spell", id, "pet": actionbareditframe.lua)
function PB.bars(e)
    local out, seen = {}, {}
    local function add(slots)
        if type(slots) ~= "table" then return end
        for _, s in ipairs(slots) do
            local n = SN(function() return s end)
            if n and not seen[n] and #out < MAX_BARS then seen[n] = true; out[#out + 1] = n end
        end
    end
    if e.a and C_ActionBar and type(C_ActionBar.FindPetActionButtons) == "function" then
        local ok, slots = CALL(function() return C_ActionBar.FindPetActionButtons(e.a) end)
        if ok then add(slots) end
    end
    if #out == 0 then add(barsFor(e.id)) end
    if #out > 0 then return out end
end
-- the pet spell is on the pet bar (the game's own check, actionbuttonutil.lua GetPetActionPetBarIndices; else the bar's own slots)
function PB.onPetBar(e)
    if e.a and C_ActionBar and type(C_ActionBar.GetPetActionPetBarIndices) == "function" then
        local ok, slots = CALL(function() return C_ActionBar.GetPetActionPetBarIndices(e.a) end)
        if ok and type(slots) == "table" and #slots > 0 then return true end
    end
    if type(GetPetActionInfo) ~= "function" then return nil end
    for i = 1, 10 do
        local ok, name, _, _, _, _, _, sid = CALL(function() return GetPetActionInfo(i) end)
        if ok and WC.isPlain(sid) and sid == e.id then return true end
        if ok and WC.isPlain(name) and name == e.n then return true end
    end
    return nil
end
-- PetHasSpellbook() as the game reads it (its spellbook category and the vanilla / cata frames test it TRUTHY: a 1 counts as true)
function PB.hasBook()
    local v = S(function() return PetHasSpellbook() end)
    return v ~= nil and v ~= false and v ~= WC.ABSENT and v ~= WC.SECRET and v ~= WC.THREW
end
-- the pet's book as the game's Pet tab holds it (nil when it has none): its entries, the pet's name token, PetHasSpellbook()
function WC.spellsPetBook()
    local okp, nPet, token = CALL(function() return C_SpellBook.HasPetSpells() end)
    if not (okp and type(nPet) == "number" and WC.isPlain(nPet) and nPet > 0) then PB.live = {} return nil end
    local hasBook = PB.hasBook()                      -- s10a: no book (a charmed unit) = no live pet spells
    local list, live = {}, {}
    for slot = 1, math.min(nPet, MAX_PET) do
        local e = PB.entry(slot)
        if e then
            list[#list + 1] = e
            if e.bind and hasBook then live[e.id] = e end
        end
    end
    PB.live = live
    return list, (WC.isPlain(token) and type(token) == "string") and token or nil, hasBook
end
-- this character's pet spells by id -> name, kept (a warlock's demon or a hunter's pet that is not out keeps its spells' names)
function PB.db(create)
    local who = WC.charKey()                                            -- s18: nil until the realm is known (never "Name-?")
    if not who then return nil end
    local db = rawget(_G, "WowCompanionSpellsDB")
    if type(db) ~= "table" then
        if not create then return nil end
        db = {}
        _G.WowCompanionSpellsDB = db
    end
    if type(db.petSpellBy) ~= "table" then
        if not create then return nil end
        db.petSpellBy = {}
    end
    if type(db.petSpellBy[who]) ~= "table" then
        if not create then return nil end
        db.petSpellBy[who] = {}
    end
    return db.petSpellBy[who]
end
-- the name our own secure buttons cast pet spell [id] by: type "spell" with the NAME is CastSpellByName, the very call the game's own /cast
-- makes (slashcommands.lua), and the pet's best rank of it; a number there would be CastSpellByID. nil = not a pet spell of this character
function WC.spellsPetName(id)
    if type(id) ~= "number" then return nil end
    local e = PB.live[id]
    if e then return e.n end
    local db = PB.db(false)
    local n = db and rawget(db, id)
    return type(n) == "string" and n or nil
end
-- a slot as it travels (the Bind box, the Controller room's rows): the pet's book slot k is PET_SLOT0 + k -> (k, the pet bank)
function WC.spellsBankSlot(vs)
    if type(vs) == "number" and vs > WC.SPELLS_PET_SLOT0 and vs <= WC.SPELLS_PET_SLOT0 + MAX_PET then
        return vs - WC.SPELLS_PET_SLOT0, BANK_PET
    end
    return vs, BANK_PLAYER
end
-- what a travelling pet slot holds now: (spell id, name) of a spell that can go on a button; false = a pet slot without one; nil = not a
-- pet slot (the player's book)
function WC.spellsPetAt(vs)
    local slot, bank = WC.spellsBankSlot(vs)
    if bank ~= BANK_PET then return nil end
    if not PB.hasBook() then return false end         -- s10a: only the game's Pet tab hands out pet slots
    local e = PB.entry(slot)
    if not (e and e.bind) then return false end
    return e.id, e.n
end
-- the travelling slot of pet spell [id] in the pet's book now (nil = the pet does not have it)
function WC.spellsPetSlotOf(id)
    if not PB.hasBook() then return nil end            -- s10a
    local e = type(id) == "number" and PB.live[id]
    if e then local now = PB.entry(e.slot); if now and now.id == id then return e.vs end end
    local list = WC.spellsPetBook()
    for _, x in ipairs(list or {}) do if x.bind and x.id == id then return x.vs end end
end

-- One book entry. `bank` is the player's or the pet's book.
local function readItem(slot, bank)
    local ok, info = CALL(function() return C_SpellBook.GetSpellBookItemInfo(slot, bank) end)
    if not ok or type(info) ~= "table" then return nil end
    local it = {
        sl = slot,
        t = TN(function() return info.itemType end),
        a = TN(function() return info.actionID end),
        id = TN(function() return info.spellID end),
        n = TS(function() return info.name end),
        sub = TS(function() return info.subName end),
        i = TN(function() return info.iconID end),
        pas = yes(TB(function() return info.isPassive end)),
        off = yes(TB(function() return info.isOffSpec end)),
        ln = TN(function() return info.skillLineIndex end),
    }
    if not it.n then return nil end
    -- s10: a pet entry's spell as PB.entry reads it (a command has none: its facts, rank and description are not a spell's)
    local pe = bank == BANK_PET and PB.entry(slot) or nil
    if pe then it.id, it.pas = pe.id, pe.pas end
    -- The rank often arrives after the name (SPELL_TEXT_UPDATE re-reads it). The game shows
    -- "Passive" for a passive spell with no sub-name; that word is the app's to write.
    if (not it.sub or it.sub == "") and it.id then
        it.sub = TS(function() return C_Spell.GetSpellSubtext(it.id) end)
    end
    if it.sub == "" then it.sub = nil end
    it.low = yes(TB(function() return C_SpellBook.IsSpellBookItemLowRank(slot, bank) end))
    -- A spell that also sits inside a flyout: with a controller the game shows the flyout, not the spell.
    it.fm = yes(TB(function() return C_SpellBook.IsSpellBookItemLooseFlyoutMember(slot, bank) end))
    if it.t == T_FUTURE then
        local lvl = TN(function() return C_SpellBook.GetSpellBookItemLevelLearned(slot, bank) end)
        if lvl and lvl > 0 then it.lvl = lvl end
    end
    if it.id then
        it.d = clip(TS(function() return C_Spell.GetSpellDescription(it.id) end), MAX_DESC)
        if it.d == "" then it.d = nil end
        -- The tooltip's first line in the game ("15 Mana   100 yd range   Instant"): cast time in ms
        -- (0 = instant), range in yards, and the cost. The words are the room's to write.
        local oks, si = CALL(function() return C_Spell.GetSpellInfo(it.id) end)
        if oks and type(si) == "table" then
            it.ct = TN(function() return si.castTime end)
            it.rmin = TN(function() return si.minRange end)
            it.rmax = TN(function() return si.maxRange end)
            if it.rmin == 0 then it.rmin = nil end
            if it.rmax == 0 then it.rmax = nil end
        end
        it.cost = costOf(slot, bank)
        if it.t == T_SPELL and not it.pas and bank == BANK_PLAYER then
            local need = needsOf(it.id)
            if need then it.rg = need.rg; it.tl = need.tl end
        end
    end
    if bank == BANK_PET then
        local okc, allowed, enabled = CALL(function() return C_SpellBook.GetSpellBookItemAutoCast(slot, bank) end)
        if okc and WC.isPlain(allowed) and WC.isPlain(enabled) then
            it.ac = yes(allowed == true)
            it.ae = yes(enabled == true)
        end
        -- s10: the pet's entry as Book lists it: a command or a passive has no number and no Bind; a spell's bars are the game's own pet
        -- action lookup (the standard slots holding it) and `pb` = it is on the pet bar (the game's "not missing from the bars")
        if pe then
            it.id, it.pas, it.cmd, it.bind = pe.id, pe.pas, pe.cmd, pe.bind
            if pe.bind then
                it.bars = PB.bars(pe)
                it.pad = padOf(it.bars)
                it.pb = PB.onPetBar(pe)
            end
        end
        return it
    end
    -- a flyout's actionID is a flyout id, not a spell: no bar lookup for it (nor for a future spell or a passive)
    if it.t ~= T_FUTURE and it.t ~= T_FLYOUT and not it.pas then
        it.bars = barsFor(it.a)
        it.pad = padOf(it.bars)     -- the same slots, named as controller buttons
    end
    return it
end

local news, pushed = {}, {}   -- newest last; `nid` counts every one since this load
local nid = 0
-- s9 (PadMap m144): learned this load (the news kept for the prompt). Read only
WC.spellsNewsHas = function(id)
    for _, n in ipairs(news) do if n.id == id then return true end end
    return false
end

-- s3 PROFESSIONS (the user 2026-10-07: "I have no way to bind profession skills. they should appear in a section of the general tab of the spell book"): the game keeps them out
-- of the book's lines, in its own Professions book (blizzard_professionsbook, camelot: GetProfessions -> prof1, prof2, first aid, fishing,
-- cooking; GetProfessionInfo -> numSpells spells from spellOffset, in the player's book). Read-only. { { n, i, o, c }, ... } in the game's
-- order; Book.lua lists the very same slots, so the room and the controller agree.
-- s5 the Professions tab's line number (no skill line of the game's goes this high: MAX_LINES 24) and its icon (UI Art's pick)
WC.SPELLS_PROF_LINE = 100
local PROF_ICON = 134400
function WC.spellsProfessions()
    local out = {}
    if type(GetProfessions) ~= "function" or type(GetProfessionInfo) ~= "function" then return out end
    local ok, p1, p2, p3, p4, p5 = CALL(function() return GetProfessions() end)
    if not ok then return out end
    local list = { p1, p2, p3, p4, p5 }
    for j = 1, 5 do
        local idx = list[j]
        if type(idx) == "number" and WC.isPlain(idx) then
            local okp, name, icon, _, _, num, off = CALL(function() return GetProfessionInfo(idx) end)
            if okp and type(num) == "number" and type(off) == "number" and WC.isPlain(num) and WC.isPlain(off) and num > 0 then
                out[#out + 1] = { n = (type(name) == "string" and WC.isPlain(name)) and name or nil,
                                  i = (type(icon) == "number" and WC.isPlain(icon)) and icon or nil, o = off, c = math.min(num, 8) }
            end
        end
    end
    return out
end

-- The whole book as one document body. Pure read: callable in combat.
function WC.collectSpells()
    tally.secret, tally.threw = 0, 0
    learnStance()
    local level = TN(function() return UnitLevel("player") end)
    local doc = {
        lvl = level,
        pas = yes(cvarBool("spellBookHidePassives")),     -- true = the player hides passives
        ranks = yes(cvarBool("ShowAllSpellRanks")),       -- true = lower ranks shown too
        combat = yes(TB(function() return InCombatLockdown() end)),
        lines = {},
        items = {},
    }
    -- true = the server switched the fly-in off, false = it is on, absent = the client cannot say
    local okr, rule = CALL(function() return Enum.GameRule.ActionbarIconIntroDisabled end)
    if okr and type(rule) == "number" then
        doc.introOff = TB(function() return C_GameRules.IsGameRuleActive(rule) end)
    end

    local nLines = TN(function() return C_SpellBook.GetNumSpellBookSkillLines() end) or 0
    local count = 0
    for k = 1, math.min(nLines, MAX_LINES) do
        local ok, li = CALL(function() return C_SpellBook.GetSpellBookSkillLineInfo(k) end)
        if ok and type(li) == "table" then
            local line = {
                k = k,
                n = TS(function() return li.name end),
                i = TN(function() return li.iconID end),
                o = TN(function() return li.itemIndexOffset end),
                c = TN(function() return li.numSpellBookItems end),
                hide = yes(TB(function() return li.shouldHide end)),
                spec = TN(function() return li.specID end),
                offspec = TN(function() return li.offSpecID end),
            }
            -- s4 (the user 2026-10-07: "the General tab needs art"): the game's own spellbook gives the General tab the CLASS icon
            -- (Blizzard_SpellBookCategory: "Use Class Icon for General tab", C_SpellBook.GetClassSkillLineInfo().iconID; UI Art's find)
            if k == 1 then
                local okc, cl = CALL(function() return C_SpellBook.GetClassSkillLineInfo() end)
                local ci = okc and type(cl) == "table" and TN(function() return cl.iconID end) or nil
                if ci and ci > 0 then line.i = ci end
            end
            doc.lines[#doc.lines + 1] = line
            -- The game makes no tab for a hidden line, so its spells are not read either.
            if not line.hide and line.o and line.c then
                for slot = line.o + 1, line.o + line.c do
                    if count >= MAX_ITEMS then doc.cut = true; break end
                    local it = readItem(slot, BANK_PLAYER)
                    if it then
                        it.ln = it.ln or k
                        doc.items[#doc.items + 1] = it
                        count = count + 1
                    end
                end
            end
        end
    end

    -- s5 PROFESSIONS, A TAB OF THEIR OWN (the user 2026-10-07: "instead of putting professions on a second page of general, give those buttons their own tab"): a line of the
    -- room's own, k = PROF_LINE, after the class's lines (`prof` true; its icon UI Art's pick); each spell `pf` = its profession. A slot a
    -- shown line already holds is not read twice (Book.lua skips the same).
    do
        local inLines = {}
        for _, line in ipairs(doc.lines) do
            if not line.hide and line.o and line.c then for s = line.o + 1, line.o + line.c do inLines[s] = true end end
        end
        local n0 = count
        for _, p in ipairs(WC.spellsProfessions()) do
            for slot = p.o + 1, p.o + p.c do
                if count >= MAX_ITEMS then doc.cut = true; break end
                if not inLines[slot] then
                    inLines[slot] = true
                    local it = readItem(slot, BANK_PLAYER)
                    if it then
                        it.ln, it.pf = WC.SPELLS_PROF_LINE, p.n or "Professions"
                        doc.items[#doc.items + 1] = it
                        count = count + 1
                    end
                end
            end
        end
        if count > n0 then
            local word = _G.PROFESSIONS_BUTTON
            doc.lines[#doc.lines + 1] = { k = WC.SPELLS_PROF_LINE, n = (type(word) == "string" and WC.isPlain(word)) and word or "Professions",
                                          i = PROF_ICON, c = count - n0, prof = true }
        end
    end

    -- THE PET'S BOOK: the game's Pet tab exists only while a pet with a book is out.
    -- s10: read before the numbers (its spells get some); k = the Pet tab's line (the room's controller reports it as `line`)
    local okp, nPet, token = CALL(function() return C_SpellBook.HasPetSpells() end)
    if okp and type(nPet) == "number" and WC.isPlain(nPet) and nPet > 0 then
        local hasBook = PB.hasBook()                     -- s10: truthy, as the game reads it (the app needs a true)
        -- the game's Pet tab: its name is the global PET, its icon GetPetIcon()
        doc.pet = { tok = WC.isPlain(token) and type(token) == "string" and token or nil, book = hasBook, k = WC.SPELLS_PET_LINE,
                    n = TS(function() return PET end), i = TN(function() return GetPetIcon() end), items = {} }
        local names = {}
        for slot = 1, math.min(nPet, MAX_PET) do
            local it = readItem(slot, BANK_PET)
            if it then
                doc.pet.items[#doc.pet.items + 1] = it
                if it.bind and it.id then names[it.id] = it.n end
            end
        end
        -- s10: the pet's spells' names kept for this character (WC.spellsPetName: our buttons cast them by name, the pet out or not)
        -- s10a: a pet WITH A BOOK only (a charmed unit's spells are never this character's pet spells)
        local db = (hasBook and next(names)) and PB.db(true) or nil
        if db then for id, n in pairs(names) do if rawget(db, id) ~= n then db[id] = n end end end
        pcall(WC.spellsPetBook)                           -- PB.live follows the book just read
    else
        PB.live = {}
    end

    -- THE PAD COLUMNS for Bind by touch (Book.lua owns the row): one stable column per spell that can go on a button
    -- b38 (Book): every spell has a NUMBER (`pv`): a touch is two taps, its group then its offset (the columns ride `win`)
    if type(WC.spellsPadNumbers) == "function" then
        -- the spells as the book shows them first (each one's highest rank), then the lower ranks: the numbers run out last on those
        local ids = {}
        for pass = 1, 2 do
            for _, it in ipairs(doc.items) do
                -- s7 (the user, 2026-11-11: "Smart Y ... doesn't actually pick them"): the loose fly-out members (the Hunter aspects) get
                -- numbers too - Book b48 already lists them, so Smart buffs offered them with no tap way and Pick sent nothing
                if it.t == T_SPELL and not it.pas and it.a and (it.low and 2 or 1) == pass then ids[#ids + 1] = it.a end
            end
            -- s10 (#234): the pet's SPELLS after the player's top ranks, before the lower ranks; by their spell id (a pet action id
            -- carries the auto-cast state in its high byte, so it changes when auto-cast is toggled)
            if pass == 1 and doc.pet and doc.pet.book == true then                -- s10a: the game's Pet tab only
                for _, it in ipairs(doc.pet.items) do if it.bind and it.id then ids[#ids + 1] = it.id end end
            end
        end
        local okc, nums = pcall(WC.spellsPadNumbers, ids)
        if okc and type(nums) == "table" then
            for _, it in ipairs(doc.items) do
                if it.a and nums[it.a] then it.pv = nums[it.a] end
            end
            if doc.pet and doc.pet.book == true then
                for _, it in ipairs(doc.pet.items) do if it.bind and it.id and nums[it.id] then it.pv = nums[it.id] end end
            end
        end
    end

    -- the tap pad's geometry, so the app can aim a tap at row `spells` (Quests' feed carries it the same way)
    if type(WC.pad) == "table" and type(WC.pad.describe) == "function" then
        local okd, d = pcall(WC.pad.describe)
        if okd and type(d) == "table" then doc.pad = d end
        -- the room's way in and out on the `spells` row (Book.lua b12): open / close / topen (the Talents face)
        if type(WC.spellsWinCols) == "table" then doc.win = WC.spellsWinCols end
    end

    doc.news = (#news > 0) and news or nil
    -- s8: the rank-up candidates among the news (PadMap m143; read only)
    if #news > 0 and type(WC.rankUpList) == "function" then
        local ids = {}
        for _, n in ipairs(news) do ids[#ids + 1] = n.id end
        local okr, rk = pcall(WC.rankUpList, ids)
        if okr and type(rk) == "table" then doc.rk = rk end
    end
    doc.pushed = (#pushed > 0) and pushed or nil
    if tally.secret > 0 or tally.threw > 0 then doc.sec = { s = tally.secret, t = tally.threw } end
    return doc
end

-- ---------------------------------------------------------------- emission

local lastKey = nil

-- Collect, and send if changed (or forced). Returns true when something was written.
-- Does NOT push: callers decide, so a heartbeat ride-along costs no flush of its own.
local function emitSpells(force)
    local doc = WC.collectSpells()
    local key = WC.encode(doc)
    if key == lastKey and not force then return false end
    lastKey = key
    WC.send(WC.encode({ type = "spells", v = SV, s = doc }))
    return true
end

-- SPELLS_CHANGED and SPELL_TEXT_UPDATE come in bursts (a trainer visit learns several spells, and
-- the login loads every rank text one by one), so coalesce.
local queued = false
local function soon()
    if queued then return end
    queued = true
    C_Timer.After(0.5, function()
        queued = false
        if emitSpells(false) then WC.push() end
    end)
end

local function remember(list, entry)
    nid = nid + 1
    entry.nid = nid
    list[#list + 1] = entry
    while #list > KEEP_NEWS do table.remove(list, 1) end
end

local f = CreateFrame("Frame")
f:SetScript("OnEvent", function(_, event, a1, a2, a3)
    if event == "LEARNED_SPELL_IN_SKILL_LINE" then
        local id = WC.isPlain(a1) and type(a1) == "number" and a1 or nil
        if id then
            remember(news, { id = id, ln = WC.isPlain(a2) and type(a2) == "number" and a2 or nil,
                             at = SN(function() return GetTime() end) })
        end
    elseif event == "SPELL_PUSHED_TO_ACTIONBAR" then
        local id = WC.isPlain(a1) and type(a1) == "number" and a1 or nil
        if id then
            remember(pushed, { id = id,
                               slot = WC.isPlain(a2) and type(a2) == "number" and a2 or nil,
                               page = WC.isPlain(a3) and type(a3) == "number" and a3 or nil,
                               at = SN(function() return GetTime() end) })
        end
    elseif event == "UNIT_PET" and a1 ~= "player" then
        return
    end
    soon()
end)

local WANT = {
    "PLAYER_ENTERING_WORLD", "SPELLS_CHANGED", "LEARNED_SPELL_IN_SKILL_LINE", "SPELL_PUSHED_TO_ACTIONBAR",
    "SPELL_TEXT_UPDATE", "ACTIONBAR_SLOT_CHANGED", "PLAYER_LEVEL_UP", "UNIT_PET", "PET_BAR_UPDATE",
    "CVAR_UPDATE",
    -- a form or stance changes icons, costs (a druid's energy / rage / mana) and the bars (the stance bar)
    "UPDATE_SHAPESHIFT_FORM", "SPELL_UPDATE_ICON", "UPDATE_BONUS_ACTIONBAR",
    -- a reagent used up or looted: the "Reagents:" line turns red or back
    "BAG_UPDATE_DELAYED",
    -- combat state travels in the document (later actions are refused in combat)
    "PLAYER_REGEN_DISABLED", "PLAYER_REGEN_ENABLED",
}
local missing = {}
for _, e in ipairs(WANT) do
    local ok, registered = pcall(function() return f:RegisterEvent(e) end)
    if not ok or registered == false then missing[#missing + 1] = e end
end
if #missing > 0 then
    pcall(function() WC.send(WC.encode({ type = "cap", spells = SV, missingEvents = missing })) end)
end

-- the pad re-laid out (UI scale, a secure row built after combat): the app needs the new geometry at once
if type(WC.pad) == "table" and type(WC.pad.onLayout) == "function" then
    pcall(WC.pad.onLayout, function() if emitSpells(false) then WC.push() end end)
end

-- RIDE THE CORE HEARTBEAT, as the Quests feed does: WC.snapshot() schedules its push for the end of
-- the frame, so a document sent in this post-hook lands in that same pad burst and costs nothing.
-- Only on reasons that always emit; ACTIONBAR_* snapshots are usually deduped.
local RIDE = { heartbeat = true, login = true, zone = true, manual = true }
if type(WC.snapshot) == "function" then
    hooksecurefunc(WC, "snapshot", function(reason)
        if RIDE[reason] then emitSpells(true) end
    end)
end
-- ==================================================================== s10 THE MACROS ROOM =====
-- THE USER (2026-11-13): "Lets scrap the smart ranged attack and start building a macro room. The calender can be moved up to a button
-- on the right side of the map name make it small. Use the room slot for macros." THE USER'S PICKS (the merged design):
--   D1 a macro changes ONLY when the player presses New, Done or Delete, never in a fight, on both tabs (General and the character's),
--      as the game's own window does;
--   D2 the game's own macro window opening brings the room up (the app); the room then closes that window ONCE with the window's own
--      close button - a SECURE CLICK of cell `wx` (MacroFrameCloseButton), never a call of ours - and works normally;
--   D3 A opens a macro to edit, X puts it on a button: the Spellbook's Bind box (Book b63 WC.spellsBindMacro), HOSTED here the way the
--      Bags room hosts it (this room keeps the controller and hands every press to the box);
--   D4 (s10; replaced by s14's builder below) a line typed (the chat keyboard's borrowed field, Chat.lua chat22: one line at a time, the
--      keyboard has no "|" and no new line), or a spell / bag item picked and its line written;
--   D5 the game's own action bar only by the slot the player presses in the Bind box's Normal tab; B undoes it (Book's keep / undo).
-- THE ONE GUARD (canWrite) runs right before every write and refuses, with its reason and no call: in the lockdown ("combat"); while the
-- game's own macro window shows ("window": its close saves its text over ours, blizzard_macroui.lua SaveMacro); before the game has
-- loaded the macros ("loading": every read finds none until UPDATE_MACROS, PadMap m160's lesson); while the game rule turns macros off
-- ("off"). New = CreateMacro(name, 134400, "", tab == the character's) (iconselector.lua's own call); a name's Done = EditMacro(i, name);
-- every body change = ONE EditMacro(i, nil, nil, body) (the game's body-only form, blizzard_macroui.lua:366, MR.writeBody); Delete = More's
-- Delete, then the ask's A within DEL_S (DeleteMacro; s15). Nothing is written on B, on leaving, or by itself. The game's macro window is never opened; no
-- Blizzard UI Lua runs.
-- THE STATE IS THE ADD-ON'S, as the Spellbook's and Bags' are: the capture hands the presses here and the app draws the "macros"
-- document; a finger taps the k-th thing on show (s<k>) or A / X / B / Y / Done (a / x / b / y / ok). THE PAD ROW "mac" IS LAZY (the pad
-- is full at the fullest layout): made when the room first opens (Book's padtab pick 39 then 34, or the game's window opening), out of
-- combat; with no free row the document says `norow`. Every value goes through S()/SN(); a macro's name and body travel only when plain.
-- Per character (the user's per-character rule): WowCompanionSpellsDB.macroRoom[name-realm] = { tab, sel } (where the room was).
--
-- ==================================================================== s14 THE MACRO BUILDER v2 =====
-- THE USER (2026-11-16, the weekend list): the Macros room's recipes "make no sense" and only add lines; v2 = more intuitive, each line
-- built with all its options and modifiers. THE USER'S PICKS (design_macrobuilder, every RECOMMENDED option, 2026-11-16):
--   D1 a line = an ACTION and its spell, then stacked "first try on ... if ..." / "else ..." steps (cases, read top-down);
--   D2 PRESETS = whole ready-made macros offered on a new or empty macro; on a macro with lines a preset REPLACES it after a second A;
--   D3 the editor shows each line as a STRIP of icons and short tags (the spell's icon, "on Focus", "if Enemy", "else Me"), the code small
--      under it (the document's `ls`: the add-on's own chips, the app has NO grammar);
--   D4 the SELECT button = Done (capture v4 `extra` PADBACK, taken with the hold), plus a Done button on screen; Start stays the pause wheel;
--   D5 the controller's targets first in "On": Target, Soft enemy, Soft friend (only while the game's soft-friend targeting is on: the CVar
--      is READ, never written), Focus, Me, Target's target, Pet, Party 1-4, then Mouseover (the PC's), then a typed name;
--   D6 the companion's own macros ("DS Combat n" / "DS Attack n", PadMap's: parsed by their exact body) are shown with a LOCK, view only.
-- ONE GRAMMAR, here (MR.parse / MR.compose / MR.check): a line parses into { c = command, w = its word as written, cs = cases { u = unit,
-- uf = "@" or "target=", cn = conditions { n, no, v | raw }, a = argument, bare = no brackets were written } }; a line whose composed text
-- is not the same as the written one (spaces next to [ ] , ; : = aside) is a TEXT line, kept and shown as written (Aa Text types it); a
-- line holding "|" stays LOCKED. Commands only "registered on this client" (IsSecureCmd, or a SLASH_<KEY>1 word with a SlashCmdList entry;
-- /dismount is not secure: slashcommands.lua 1241) and by class (pet orders: pet classes; Leave form: classes with forms, not warriors).
-- Click and Dismount are no builder actions (parsed back as text lines). Ranks are written with the spell id's OWN subtext
-- (C_Spell.GetSpellSubtext: "Rango 2" / "Rang 2" on es / fr clients), the top rank by default (no parentheses). Checks out of a fight only:
-- HARD (Done refused, the cursor on the bad chip): "long" (past 255 bytes, the room counts bytes), "pick" (no spell), "empty"; SOFT (amber,
-- Done still writes): "book" (not in this character's book), "bags" (not in the bags or worn), "else" (a harmful Cast / Sequence with no
-- plain else: [harm] reads the HARD target and a pad's soft enemy becomes one only after a harmful spell goes out, targetlogic.lua 86-95).
-- THE WRITES are the player's own and ONE EditMacro each, behind the one guard: the builder's Done (an unchanged draft writes nothing), a
-- line's Remove (More's Remove, then the ask's A within DEL_S), a Move's drop, a preset's Done (a macro with lines: the ask's A), the
-- icon picked. In a fight
-- Done is refused ("combat") and the draft is kept; B keeps the draft too. No RunMacroText, no SetBinding, no CVar write, no Blizzard
-- open / close. MR.CMDS / MR.CONDS / MR.PRESETS replace s10's six recipes; the row's keys gain `y` (Y) and `ok` (Done): 38 of 40 columns,
-- no new row (the Calendar row's guest still).
do
local MR = { GUEST = "macros", ROW = "mac", DEL_S = 6, ICON = 134400, NAME_MAX = 16, BODY_MAX = 255, PER_PAGE = 24,
             LINE_ROWS = 19, CLOSE = "MacroFrameCloseButton",
             -- s12 THE SHARED ROW (TapPad pad21): "mac" is the GUEST of the Calendar room's row "cal" (the two rooms are never on the
             -- bottom screen together: the Calendar lost its bar slot to this room), so the room needs no row of its own at the fullest
             -- layout; with no Calendar row (its add-on off) a row of its own as before
             SHARE = "cal",
             -- s14: at most 4 cases a line, 8 preset tiles, 18 cells in the Spell / Item grid (6 x 3), 24 in the other pop-outs
             CASES = 4, TILES = 8, GRID = 18, POP = 24 }
-- the row's columns, FIXED: 0-13 the room's keys, then s0..s23 = the k-th thing on show (a grid cell, an editor row, a chip, a pop-out cell)
MR.KEYS = { "h", "f", "fb", "a", "x", "b", "tg", "tc", "pp", "pn", "kx", "wx", "y", "ok" }
MR.COL, MR.KEY = {}, {}
for i, k in ipairs(MR.KEYS) do MR.COL[k] = i - 1; MR.KEY[i - 1] = k end
MR.S0 = #MR.KEYS
MR.NCOLS = MR.S0 + MR.PER_PAGE
local st = { tab = "g", page = 1, cur = 0, mode = "grid", sel = nil, selName = nil, ef = 0, efk = "name", delAt = nil,
             note = nil, kbd = nil, n = 0, loaded = false, winAt = -100, ackN = 0,
             draft = nil, bc = "cmd", pop = nil, remAt = nil, mv = nil, ps = nil, pcur = 1 }
local ctlr = { want = false, held = false, why = nil, n = 0, bnext = "home", told = nil }
MR.st, MR.ctlr = st, ctlr

local function T(fn) local v = S(fn); if v ~= WC.ABSENT and v ~= WC.SECRET and v ~= WC.THREW then return v end end
local function TNn(fn) local v = T(fn); if type(v) == "number" then return v end end
local function TSs(fn) local v = T(fn); if type(v) == "string" and WC.isPlain(v) then return v end end
local function plain(v) if type(v) == "string" and WC.isPlain(v) then return v end end
local function combat() return S(function() return InCombatLockdown() end) == true end
local function who()
    return (WC.charKey())                                               -- s18: nil until the realm is known (never "Name-")
end
function MR.trim(s) return ((type(s) == "string" and s or ""):gsub("^%s+", ""):gsub("%s+$", "")) end

-- ---------------------------------------------------------------- s14 THE GRAMMAR: commands, conditions, units
-- k = our id; key = the game's SLASH_COMMAND key (its SLASH_<key>N words, its SlashCmdList / SecureCmdList entry); en = the English words
-- (the first is written when the client names none); arg = what its argument is; opt = the argument may be left out; pet = the game's
-- word for a pet order (PET_ACTION_* / PET_MODE_*, PB.WORDS'); forms = classes with forms only; more = the "More" part of the list
MR.CMDS = {
    { k = "cast", key = "CAST", en = { "/cast", "/spell" }, arg = "spell", rank = true, keep = true },
    { k = "use", key = "USE", en = { "/use" }, arg = "item" },
    { k = "seq", key = "CASTSEQUENCE", en = { "/castsequence" }, arg = "seq" },
    { k = "target", key = "TARGET", en = { "/target", "/tar" }, arg = "unit" },
    { k = "focus", key = "FOCUS", en = { "/focus" }, arg = "unit", opt = true },
    { k = "assist", key = "ASSIST", en = { "/assist", "/a" }, arg = "unit", opt = true },
    { k = "startattack", key = "STARTATTACK", en = { "/startattack" }, arg = "unit", opt = true },
    { k = "stopattack", key = "STOPATTACK", en = { "/stopattack" } },
    { k = "stopcasting", key = "STOPCASTING", en = { "/stopcasting" } },
    { k = "petattack", key = "PET_ATTACK", en = { "/petattack" }, arg = "unit", opt = true, pet = "PET_ACTION_ATTACK" },
    { k = "petfollow", key = "PET_FOLLOW", en = { "/petfollow" }, pet = "PET_ACTION_FOLLOW" },
    { k = "petstay", key = "PET_STAY", en = { "/petstay" }, pet = "PET_ACTION_WAIT" },
    { k = "petmoveto", key = "PET_MOVE_TO", en = { "/petmoveto" }, pet = "PET_ACTION_MOVE_TO" },
    { k = "petpassive", key = "PET_PASSIVE", en = { "/petpassive" }, pet = "PET_MODE_PASSIVE" },
    { k = "petdefensive", key = "PET_DEFENSIVE", en = { "/petdefensive" }, pet = "PET_MODE_DEFENSIVE" },
    { k = "petdefensiveassist", key = "PET_DEFENSIVEASSIST", en = { "/petdefensiveassist" }, pet = "PET_MODE_DEFENSIVEASSIST" },
    { k = "petaggressive", key = "PET_AGGRESSIVE", en = { "/petaggressive" }, pet = "PET_MODE_AGGRESSIVE" },
    { k = "petassist", key = "PET_ASSIST", en = { "/petassist" }, pet = "PET_MODE_ASSIST" },
    { k = "petdismiss", key = "PET_DISMISS", en = { "/petdismiss" }, pet = "PET_ACTION_DISMISS" },
    { k = "cancelaura", key = "CANCELAURA", en = { "/cancelaura" }, arg = "aura" },
    { k = "cancelform", key = "CANCELFORM", en = { "/cancelform" }, forms = true },
    { k = "equip", key = "EQUIP", en = { "/equip" }, arg = "equip" },
    { k = "equipslot", key = "EQUIP_TO_SLOT", en = { "/equipslot" }, arg = "eslot" },
    { k = "cleartarget", key = "CLEARTARGET", en = { "/cleartarget" }, more = true },
    { k = "clearfocus", key = "CLEARFOCUS", en = { "/clearfocus" }, more = true },
    { k = "targetenemy", key = "TARGET_NEAREST_ENEMY", en = { "/targetenemy" }, more = true },
    { k = "lasttarget", key = "TARGET_LAST_TARGET", en = { "/targetlasttarget" }, more = true },
    { k = "stopmacro", key = "STOPMACRO", en = { "/stopmacro" }, more = true },
    { k = "chat", chat = true, more = true },                            -- a chat line (or any line), typed
    -- parsed only (never offered): the tooltip line; its cases work as a /cast's (#show [mod] A; B)
    { k = "tip", en = { "#showtooltip", "#show" }, arg = "spell", opt = true, meta = true },      -- its spell may be left out
}
MR.CMD = {}
for _, c in ipairs(MR.CMDS) do MR.CMD[c.k] = c end
-- the If grid's conditions (tri-state: off, yes, no); n = the game's name, v = its one value; pet / stealth / forms = shown for those
-- classes (and always when a line has it already); alt = only when parsed
MR.CONDS = {
    { k = "harm", n = "harm" }, { k = "help", n = "help" }, { k = "dead", n = "dead" }, { k = "exists", n = "exists" },
    { k = "combat", n = "combat" }, { k = "mod:shift", n = "mod", v = "shift" }, { k = "mod:ctrl", n = "mod", v = "ctrl" },
    { k = "mod:alt", n = "mod", v = "alt", parsedOnly = true }, { k = "pet", n = "pet", pet = true },
    { k = "stealth", n = "stealth", stealth = true }, { k = "mounted", n = "mounted" }, { k = "channeling", n = "channeling" },
    { k = "group", n = "group" }, { k = "form", n = "form", forms = true },
}
MR.COND = {}
for _, c in ipairs(MR.CONDS) do MR.COND[c.k] = c end
-- the names the game also takes for one of ours (kept as written)
MR.ALIAS = { modifier = "mod", stance = "form" }
-- "On" (D5): the controller's targets first, Mouseover last (the PC's), then a typed name; "" = no unit (the command's own target)
MR.UNITS = { "", "softenemy", "softfriend", "focus", "player", "targettarget", "pet", "party1", "party2", "party3", "party4", "mouseover",
             "name" }

-- the word to WRITE for a command on this client: its SLASH_<key>1 when registered (IsSecureCmd, or a SlashCmdList entry), else an
-- English word the client's secure list knows; nil = not registered here (never offered)
function MR.wordFor(c)
    if type(c) ~= "table" or not c.key then return nil end
    local function secure(w) return S(function() return IsSecureCmd(w) end) == true end
    local g = plain(rawget(_G, "SLASH_" .. c.key .. "1"))
    if g and g:find("^/%S+$") then
        local list = rawget(_G, "SlashCmdList")
        if secure(g) or (type(list) == "table" and type(rawget(list, c.key)) == "function") then return g end
    end
    for _, w in ipairs(c.en) do if secure(w) then return w end end
    return nil
end
-- a written command word (any case) -> our command, from the client's own SLASH_<key>N words and the English ones
function MR.cmdOf(word)
    if type(word) ~= "string" then return nil end
    local lw = word:lower()
    for _, c in ipairs(MR.CMDS) do
        for _, w in ipairs(c.en or {}) do if w == lw then return c end end
        if c.key then
            for n = 1, 8 do
                local g = plain(rawget(_G, "SLASH_" .. c.key .. n))
                if not g then break end
                if g:lower() == lw then return c end
            end
        end
    end
    return nil
end

-- [s] split at [sep] outside brackets; nil when the brackets nest or do not close
function MR.splitTop(s, sep)
    local out, depth, from = {}, 0, 1
    for i = 1, #s do
        local ch = s:sub(i, i)
        if ch == "[" then depth = depth + 1; if depth > 1 then return nil end
        elseif ch == "]" then depth = depth - 1; if depth < 0 then return nil end
        elseif ch == sep and depth == 0 then out[#out + 1] = s:sub(from, i - 1); from = i + 1 end
    end
    if depth ~= 0 then return nil end
    out[#out + 1] = s:sub(from)
    return out
end
-- one condition set's inside ("@focus,harm,nodead") -> { u, uf, cn }; nil = not one of ours to keep (a text line)
function MR.condSet(inner)
    local set = { cn = {} }
    if MR.trim(inner) == "" then return set end
    for part in (inner .. ","):gmatch("(.-),") do
        local p = MR.trim(part)
        -- (s14 review) a unit typed by name may be an es / fr realm's accented one ("@Íñigo"): UTF-8's bytes are letters here too,
        -- else the line the builder itself wrote would read back as a text line
        if p == "" or not p:find("^[%w_@=:/%.%-' \128-\255]+$") then return nil end
        local u = p:match("^@(.+)$")
        local tu = p:match("^target%s*=%s*(.+)$")
        if u or tu then
            if set.u or #set.cn > 0 then return nil end                 -- a unit after a condition, or two: written as text
            set.u, set.uf = MR.trim(u or tu), u and "@" or "target="
        else
            local name, v = p:match("^([%a_]+)(.*)$")
            local val = v and v:match("^:(.+)$")
            if not name or (v ~= "" and not val) then return nil end
            local low = name:lower()
            local no = false
            local canon = MR.ALIAS[low] or low
            local known = MR.CONDK(canon, val)
            if not known and low:sub(1, 2) == "no" then
                canon = MR.ALIAS[low:sub(3)] or low:sub(3)
                known = MR.CONDK(canon, val)
                no = known ~= nil
            end
            if known and name == name:lower() then
                local nm = no and name:sub(3) or name
                set.cn[#set.cn + 1] = { n = canon, no = no, v = val, nm = (nm ~= canon) and nm or nil, k = known }
            else
                set.cn[#set.cn + 1] = { raw = p }                       -- kept exactly as written (shown as written)
            end
        end
    end
    return set
end
-- our condition key for a game name + value ("mod", "shift" -> "mod:shift"), or nil
function MR.CONDK(n, v)
    for _, c in ipairs(MR.CONDS) do
        if c.n == n and c.v == v then return c.k end
    end
    return nil
end
function MR.plainSet(s) return s.u == nil and #s.cn == 0 end
function MR.setText(s)
    local parts = {}
    if s.u then parts[1] = (s.uf == "target=" and "target=" or "@") .. s.u end
    for _, c in ipairs(s.cn) do
        if c.raw then parts[#parts + 1] = c.raw
        else parts[#parts + 1] = (c.no and "no" or "") .. (c.nm or c.n) .. (c.v and (":" .. c.v) or "") end
    end
    return "[" .. table.concat(parts, ",") .. "]"
end
-- a model -> its line. Cases next to each other with the SAME argument share one clause ("[help,nodead][@player] Heal"); a plain case
-- alone in its clause is written bare ("; Bolt") unless it was written "[]"
function MR.compose(L)
    if type(L) ~= "table" then return "" end
    if L.kind ~= "cmd" then return L.raw or "" end
    local parts, i, cs = {}, 1, L.cs or {}
    while i <= #cs do
        local a = cs[i].a or ""
        local j = i
        while j < #cs and (cs[j + 1].a or "") == a do j = j + 1 end
        local br = ""
        if not (i == j and MR.plainSet(cs[i]) and cs[i].bare ~= false) then
            local b = {}
            for x = i, j do b[#b + 1] = MR.setText(cs[x]) end
            br = table.concat(b)
        end
        parts[#parts + 1] = (br ~= "" and a ~= "") and (br .. " " .. a) or (br .. a)
        i = j + 1
    end
    local rest = table.concat(parts, "; ")
    return (L.w or "") .. (MR.trim(rest) ~= "" and (" " .. rest) or "")
end
-- spaces next to [ ] , ; : = do not count (the game reads them alike)
function MR.norm(s)
    s = (s or ""):gsub("%s+", " "):gsub("^ ", ""):gsub(" $", "")
    return (s:gsub(" ?([%[%],;:=]) ?", "%1"))
end
function MR.parse(text)
    text = type(text) == "string" and text or ""
    if text:find("|", 1, true) then return { kind = "lock", raw = text } end
    local t = MR.trim(text)
    if t == "" then return { kind = "empty", raw = text } end
    local word, rest = t:match("^(%S+)%s*(.-)$")
    local c = MR.cmdOf(word)
    if not c or c.chat then return { kind = "text", raw = text } end
    local L = { kind = "cmd", c = c.k, w = word, cs = {}, raw = text }
    local cls = (rest == "") and { "" } or MR.splitTop(rest, ";")
    if not cls then return { kind = "text", raw = text, c = c.k } end
    for _, cl in ipairs(cls) do
        local s = MR.trim(cl)
        local sets = {}
        while s:sub(1, 1) == "[" do
            local close = s:find("]", 1, true)
            local set = close and MR.condSet(s:sub(2, close - 1))
            if not set then return { kind = "text", raw = text, c = c.k } end
            set.bare = false
            sets[#sets + 1] = set
            s = MR.trim(s:sub(close + 1))
        end
        if #sets == 0 then sets[1] = { cn = {}, bare = true } end
        for _, set in ipairs(sets) do set.a = s; L.cs[#L.cs + 1] = set end
    end
    if MR.norm(MR.compose(L)) ~= MR.norm(text) then return { kind = "text", raw = text, c = c.k } end
    return L
end
function MR.copy(t)
    if type(t) ~= "table" then return t end
    local o = {}
    for k, v in pairs(t) do o[k] = MR.copy(v) end
    return o
end

-- ---------------------------------------------------------------- the argument's parts (per command kind)
-- cast: "!Name(Rank 2)" -> keep-on, name, the rank's subtext
function MR.castParts(a)
    a = MR.trim(a)
    local bang = a:sub(1, 1) == "!"
    local s = bang and MR.trim(a:sub(2)) or a
    local n, sub = s:match("^(.-)%((.-)%)$")
    if n and MR.trim(n) ~= "" then return bang, MR.trim(n), sub end
    return bang, s, nil
end
function MR.castJoin(bang, n, sub)
    if not n or n == "" then return "" end
    return (bang and "!" or "") .. n .. ((sub and sub ~= "") and ("(" .. sub .. ")") or "")
end
-- castsequence: "reset=target/combat A, B, C" -> the reset, the spells
function MR.seqParts(a)
    a = MR.trim(a)
    local reset, rest = a:match("^reset=(%S+)%s*(.*)$")
    rest = rest or a
    local list = {}
    if MR.trim(rest) ~= "" then for part in (rest .. ","):gmatch("(.-),") do list[#list + 1] = MR.trim(part) end end
    return reset, list
end
function MR.seqJoin(reset, list)
    local s = table.concat(list or {}, ", ")
    if reset and reset ~= "" then return "reset=" .. reset .. (s ~= "" and (" " .. s) or "") end
    return s
end
-- equipslot: "13 Item" -> the slot, the item
function MR.eslotParts(a)
    a = MR.trim(a)
    local sl, it = a:match("^(%d+)%s*(.*)$")
    if sl then return tonumber(sl), MR.trim(it) end
    return nil, a
end

-- ---------------------------------------------------------------- the game's macros (read only)
function MR.maxes()
    local g = SN(function() return Constants.MacroConsts.MAX_ACCOUNT_MACROS end) or SN(function() return MAX_ACCOUNT_MACROS end) or 120
    local c = SN(function() return Constants.MacroConsts.MAX_CHARACTER_MACROS end) or SN(function() return MAX_CHARACTER_MACROS end) or 18
    return g, c
end
function MR.counts()
    local ok, g, c = CALL(function() return GetNumMacros() end)
    local mg, mc = MR.maxes()
    g = (ok and type(g) == "number" and WC.isPlain(g)) and g or 0
    c = (ok and type(c) == "number" and WC.isPlain(c)) and c or 0
    return { g = { n = g, max = mg }, c = { n = c, max = mc } }
end
function MR.base(tab) if tab == "c" then return (MR.maxes()) end return 0 end
-- absolute index -> name, icon, body (each plain or nil)
function MR.info(i)
    local ok, n, ic, b = CALL(function() return GetMacroInfo(i) end)
    if not ok then return nil end
    return plain(n), (type(ic) == "number" and WC.isPlain(ic)) and ic or nil, plain(b)
end
function MR.indexOf(name)
    if type(name) ~= "string" then return nil end
    local i = TNn(function() return GetMacroIndexByName(name) end)
    if i and i > 0 then return i end
end
function MR.ready()
    if st.loaded then return true end
    local c = MR.counts()
    if c.g.n > 0 or c.c.n > 0 then st.loaded = true end
    return st.loaded
end
function MR.off()
    return S(function() return C_GameRules.IsGameRuleActive(Enum.GameRule.MacrosDisabled) end) == true
end
function MR.winShown()
    local fr = rawget(_G, "MacroFrame")
    if type(fr) ~= "table" then return false end
    local ok, shown = CALL(function() return fr:IsShown() end)
    return ok and shown == true
end
-- THE ONE GUARD (D1): nil = a write may go now, else why not
function MR.canWrite()
    if combat() then return "combat" end
    if MR.off() then return "off" end
    if MR.winShown() then return "window" end
    if not MR.ready() then return "loading" end
    return nil
end
-- a name as the game's own popup takes it: trimmed, its quotes dropped (iconselector.lua), 1-16 letters, not used on either tab
function MR.cleanName(raw, except)
    if type(raw) ~= "string" then return nil, "empty" end
    local n = raw:gsub('"', ""):gsub("^%s+", ""):gsub("%s+$", "")
    if n == "" then return nil, "empty" end
    if #n > MR.NAME_MAX then return nil, "long" end
    if not WC.isPlain(n) or n:find("|", 1, true) then return nil, "bad" end
    local c = MR.counts()
    local low = n:lower()
    for _, tab in ipairs({ "g", "c" }) do
        local base = MR.base(tab)
        for k = 1, c[tab].n do
            local i = base + k
            if i ~= except then
                local other = MR.info(i)
                if other and other:lower() == low then return nil, "taken" end
            end
        end
    end
    return n
end
function MR.lines(body)
    local out = {}
    if type(body) ~= "string" or body == "" then return out end
    for ln in (body .. "\n"):gmatch("(.-)\n") do out[#out + 1] = ln end
    return out
end
function MR.join(lines) return table.concat(lines, "\n") end
-- s14 D6: THE COMPANION'S OWN MACROS (PadMap's "DS Combat <slot>" / "DS Attack <slot>", recognised there by their exact body): view only
-- here - the Controller room keeps managing them (an edit would make PadMap stop knowing it, and Start attack off would not restore)
function MR.isDS(name, body)
    if type(name) ~= "string" or type(body) ~= "string" then return false end
    if name:sub(1, 10) == "DS Combat " and body:find("/cast [combat]", 1, true) then return true end
    if name:sub(1, 10) == "DS Attack " and body:find("/startattack", 1, true) then return true end
    return false
end
function MR.locked(i)
    local n, _, b = MR.info(i)
    return MR.isDS(n, b)
end

-- ---------------------------------------------------------------- the selection (its index while that index still holds its name)
-- (s10 review) THE GAME LETS TWO MACROS SHARE A NAME (its own popup checks nothing; GetMacroIndexByName answers the first), so the
-- selection is the INDEX under the glow, kept while the macro there still has the selected name; only when it does not (a rename or a
-- new macro sorted it elsewhere) is it looked for by name, on this tab. A name gone from the tab = no selection (never the neighbour).
function MR.tabCount() return MR.counts()[st.tab].n end
function MR.selIndex()
    local base, c = MR.base(st.tab), MR.tabCount()
    local function onTab(i) return type(i) == "number" and i > base and i <= base + c end
    if onTab(st.sel) then
        local n = MR.info(st.sel)
        if not st.selName or n == st.selName then st.selName = n; return st.sel end
    end
    if st.selName then
        for k = 1, c do
            if MR.info(base + k) == st.selName then st.sel = base + k; return st.sel end
        end
    end
    return nil
end
-- (s10 review) how many macros on either tab are called [name] (case aside, as the name rules compare: the cautious count)
function MR.named(name)
    if type(name) ~= "string" then return 0 end
    local c, low, n = MR.counts(), name:lower(), 0
    for _, tab in ipairs({ "g", "c" }) do
        local base = MR.base(tab)
        for k = 1, c[tab].n do
            local other = MR.info(base + k)
            if other and other:lower() == low then n = n + 1 end
        end
    end
    return n
end
-- the page's things: the macros on it, then "+" after the last macro (not at the cap, not before the macros load)
function MR.pageItems()
    local c = MR.counts()[st.tab]
    local plus = MR.ready() and c.n < (c.max or 0)
    local total = c.n + (plus and 1 or 0)
    local pages = math.max(1, math.ceil(total / MR.PER_PAGE))
    st.page = math.max(1, math.min(st.page, pages))
    local first = (st.page - 1) * MR.PER_PAGE
    local items = {}
    for k = 1, MR.PER_PAGE do
        local pos = first + k
        if pos <= c.n then items[k] = { abs = MR.base(st.tab) + pos }
        elseif plus and pos == c.n + 1 then items[k] = { plus = true }
        else break end
    end
    return items, pages, c
end
function MR.focusGrid()
    local items, pages, c = MR.pageItems()
    if type(st.cur) == "number" and st.cur >= 0 then
        if #items == 0 then st.cur = -1 elseif st.cur > #items - 1 then st.cur = #items - 1 end
    end
    local it = type(st.cur) == "number" and st.cur >= 0 and items[st.cur + 1] or nil
    if it and it.abs then st.sel = it.abs; st.selName = (MR.info(it.abs)) end
    return items, pages, c
end
-- put the glow on the macro named [name] (after a write: its page and cell); [idx] (s10 review) = the index the game's own write
-- answered (CreateMacro / EditMacro return where the macro sits now) or the one just edited, used while it holds that name
function MR.findAndFocus(name, idx)
    local i = nil
    if type(idx) == "number" and WC.isPlain(idx) and idx > 0 and MR.info(idx) == name then i = idx end
    i = i or MR.indexOf(name)
    if not i then return false end
    local mg = MR.maxes()
    st.tab = i > mg and "c" or "g"
    local pos = i - MR.base(st.tab)
    st.page = math.floor((pos - 1) / MR.PER_PAGE) + 1
    st.cur = (pos - 1) % MR.PER_PAGE
    st.sel, st.selName = i, name
    return true
end

-- ---------------------------------------------------------------- the per-character place (tab, selection)
function MR.store()
    local k = who()
    if not k then return nil end
    if type(_G.WowCompanionSpellsDB) ~= "table" then _G.WowCompanionSpellsDB = {} end
    local db = _G.WowCompanionSpellsDB
    if type(db.macroRoom) ~= "table" then db.macroRoom = {} end
    if type(db.macroRoom[k]) ~= "table" then db.macroRoom[k] = {} end
    return db.macroRoom[k]
end
function MR.remember()
    local s = MR.store()
    if s then s.tab = st.tab; s.sel = st.selName end
end
function MR.recall()
    local s = MR.store()
    if not s then return end
    if s.tab == "c" or s.tab == "g" then st.tab = s.tab end
    if type(s.sel) == "string" and not MR.findAndFocus(s.sel) then st.page, st.cur = 1, 0 end
end

-- ---------------------------------------------------------------- the class's facts (read only)
function MR.class() return TSs(function() return select(2, UnitClass("player")) end) end
function MR.petClass()
    local c = MR.class()
    if c == "HUNTER" or c == "WARLOCK" then return true end
    if S(function() return UnitExists("pet") end) == true then return true end
    return next(MR.petRemembered()) ~= nil
end
function MR.forms()
    if MR.class() == "WARRIOR" then return false end
    local n = TNn(function() return GetNumShapeshiftForms() end)
    return (n or 0) > 0
end
function MR.stealthClass() local c = MR.class(); return c == "ROGUE" or c == "DRUID" end
-- the game's soft-friend targeting (READ only: never written): "0" or none = off
function MR.softFriend()
    local v = T(function() return GetCVar("SoftTargetFriend") end)
    if v == nil then v = T(function() return C_CVar.GetCVar("SoftTargetFriend") end) end
    return v ~= nil and tostring(v) ~= "0" and tostring(v) ~= ""
end
-- the pet's spells this character keeps by name (Spells s10p: WowCompanionSpellsDB.petSpellBy[name-realm]; the pet may be dismissed)
function MR.petRemembered()
    local db = rawget(_G, "WowCompanionSpellsDB")
    local k = who()
    local t = type(db) == "table" and type(db.petSpellBy) == "table" and k and db.petSpellBy[k]
    return type(t) == "table" and t or {}
end

-- ---------------------------------------------------------------- s14 what can be picked (read only): the book, the pet, the bags, the worn
-- the player's book as the Controller room lists it (Book's WC.spellsBindableList: top ranks, known, not passive): { id, n, i, gen, pet }
function MR.book()
    -- read once a frame (a document's strips ask it for every line); a book change reads it again (SPELLS_CHANGED)
    local now = GetTime()
    if st.bk and st.bk.at == now then return st.bk.list end
    local out, seen = {}, {}
    st.bk = { at = now, list = out }
    if type(WC.spellsBindableList) ~= "function" then return out end
    local ok, list = pcall(WC.spellsBindableList)
    for _, e in ipairs(ok and type(list) == "table" and list or {}) do
        local id = e.id
        local n = type(id) == "number" and TSs(function() return C_Spell.GetSpellName(id) end)
        if n and not seen[n] and not n:find("[|;,%[%]]") then
            seen[n] = true
            out[#out + 1] = { id = id, n = n, i = TNn(function() return C_Spell.GetSpellTexture(id) end), gen = e.gen, pet = e.pet,
                              prof = e.prof }
        end
        if #out >= 160 then break end
    end
    return out
end
-- a spell named [n] in this character's book -> its id (the book's top rank), else nil
function MR.bookId(n)
    if type(n) ~= "string" or n == "" then return nil end
    for _, e in ipairs(MR.book()) do if e.n == n then return e.id end end
    return nil
end
-- a spell's kind: "help" / "harm" / nil (by the book's id; the game's own C_Spell.IsSpellHelpful / IsSpellHarmful)
function MR.kindOf(n)
    local id = MR.bookId(n)
    local key = id or n
    if not key then return nil end
    if S(function() return C_Spell.IsSpellHelpful(key) end) == true then return "help" end
    if S(function() return C_Spell.IsSpellHarmful(key) end) == true then return "harm" end
    return nil
end
-- the ranks of a book spell (the book's own entries of that name, lowest first): { { id, sub } }; one rank or none = no rank chip
function MR.ranks(n)
    st.rk = st.rk or {}
    if st.rk[n] then return st.rk[n] end
    local out = {}
    local nLines = TNn(function() return C_SpellBook.GetNumSpellBookSkillLines() end) or 0
    for k = 1, math.min(nLines, 24) do
        local ok, li = CALL(function() return C_SpellBook.GetSpellBookSkillLineInfo(k) end)
        local o = ok and type(li) == "table" and TNn(function() return li.itemIndexOffset end)
        local c = ok and type(li) == "table" and TNn(function() return li.numSpellBookItems end)
        if o and c then
            for slot = o + 1, math.min(o + c, o + 400) do
                local oki, info = CALL(function() return C_SpellBook.GetSpellBookItemInfo(slot, 0) end)
                if oki and type(info) == "table" and plain(info.name) == n then
                    local id = TNn(function() return info.spellID end)
                    local sub = id and TSs(function() return C_Spell.GetSpellSubtext(id) end)
                    if id and sub and sub ~= "" then out[#out + 1] = { id = id, sub = sub, r = tonumber(sub:match("(%d+)")) or 0 } end
                end
            end
        end
    end
    table.sort(out, function(a, b) return a.r < b.r end)
    st.rk[n] = out
    return out
end
-- the pet's orders as the Pet tab and the Action list offer them: registered on this client, the game's own word for each
function MR.petOrders()
    local out = {}
    for _, c in ipairs(MR.CMDS) do
        if c.pet and MR.wordFor(c) then
            local tok = type(WC.petTok) == "table" and WC.petTok or nil
            local okN, nm = false, nil
            if tok then okN, nm = pcall(tok.name, c.pet) end
            out[#out + 1] = { c = c.k, n = (okN and plain(nm)) or plain(rawget(_G, c.pet)) }
        end
    end
    return out
end
-- the icon of a pet order (the pet bar's pictures, WC.petTok, as s13's picks)
MR.PET_TEX = { petattack = "PET_ATTACK_TEXTURE", petfollow = "PET_FOLLOW_TEXTURE", petstay = "PET_WAIT_TEXTURE",
               petmoveto = "PET_MOVE_TO_TEXTURE", petpassive = "PET_PASSIVE_TEXTURE", petdefensive = "PET_DEFENSIVE_TEXTURE",
               petdefensiveassist = "PET_DEFENSIVEASSIST_TEXTURE", petaggressive = "PET_AGGRESSIVE_TEXTURE", petassist = "PET_ASSIST_TEXTURE",
               petdismiss = "PET_DISMISS_TEXTURE" }
function MR.petIcon(k)
    local tok = type(WC.petTok) == "table" and WC.petTok or nil
    local tex = MR.PET_TEX[k]
    if not (tok and tex) then return nil end
    local ok, ic = pcall(tok.icon, tex)
    return (ok and type(ic) == "number" and ic > 0) and ic or nil
end
-- the bag items with a use or to wear (s10's reading): { t = "u" | "e", n, i, id }
function MR.bagItems(equipOnly)
    local out, seen = {}, {}
    for bag = 0, 4 do
        local slots = TNn(function() return C_Container.GetContainerNumSlots(bag) end) or 0
        for slot = 1, math.min(slots, 40) do
            local id = TNn(function() return C_Container.GetContainerItemID(bag, slot) end)
            if id and not seen[id] then
                seen[id] = true
                local n = TSs(function() return C_Item.GetItemNameByID(id) end)
                local use = S(function() return (C_Item.GetItemSpell(id)) end)
                local kind = (use ~= nil and use ~= WC.ABSENT and use ~= WC.THREW and use ~= WC.SECRET) and "u" or nil
                if (equipOnly or not kind) and S(function() return C_Item.IsEquippableItem(id) end) == true then kind = "e" end
                if equipOnly and kind ~= "e" then kind = nil end
                if n and kind and not n:find("[|;,%[%]]") then
                    out[#out + 1] = { t = kind, n = n, id = id, i = TNn(function() return C_Item.GetItemIconByID(id) end) }
                end
            end
            if #out >= 120 then return out end
        end
    end
    return out
end
-- the worn slots (the game's 1-19): { t = "o", sl, n (the worn item's name), i, use }; the trinkets (13, 14) always
function MR.slots(useOnly)
    local out = {}
    for sl = 1, 19 do
        local id = TNn(function() return GetInventoryItemID("player", sl) end)
        local use = id and S(function() return (C_Item.GetItemSpell(id)) end)
        use = use ~= nil and use ~= WC.ABSENT and use ~= WC.THREW and use ~= WC.SECRET and use ~= false
        if (id and (use or not useOnly)) or sl == 13 or sl == 14 then
            out[#out + 1] = { t = "o", sl = sl, n = id and TSs(function() return C_Item.GetItemNameByID(id) end) or nil,
                              i = TNn(function() return GetInventoryItemTexture("player", sl) end), use = use or nil }
        end
    end
    return out
end
-- an argument's icon and the name to show (an item:<id> argument is kept as written and shown by name; a slot by its worn item)
function MR.look(kind, a)
    a = MR.trim(a)
    if a == "" then return nil, nil end
    if kind == "unit" then return nil, a end
    local sl = tonumber(a)
    if sl and (kind == "item" or kind == "spell") then
        local id = TNn(function() return GetInventoryItemID("player", sl) end)
        return TNn(function() return GetInventoryItemTexture("player", sl) end), id and TSs(function() return C_Item.GetItemNameByID(id) end) or a
    end
    local iid = tonumber(a:match("^item:(%d+)$") or "")
    if iid then return TNn(function() return C_Item.GetItemIconByID(iid) end), TSs(function() return C_Item.GetItemNameByID(iid) end) or a end
    local _, n = MR.castParts(a)
    local id = MR.bookId(n)
    local ic = id and TNn(function() return C_Spell.GetSpellTexture(id) end)
    if not ic then
        for _, e in ipairs(MR.petSpells()) do if e.n == n then ic = e.i end end
    end
    if not ic then ic = TNn(function() return C_Item.GetItemIconByID(n) end) end
    return ic, n
end
-- the pet's spells: the live book's (s10p: not passive, not a command; a pet with a book) and the names this character keeps
function MR.petSpells()
    local out, seen = {}, {}
    if type(WC.spellsPetBook) == "function" then
        local ok, list, _, hasBook = pcall(WC.spellsPetBook)
        if ok and type(list) == "table" and hasBook then
            for _, e in ipairs(list) do
                local n = e.bind and type(e.id) == "number" and plain((type(WC.spellsPetName) == "function" and WC.spellsPetName(e.id)) or e.n)
                if n and not seen[n] and not n:find("[|;,%[%]]") then
                    seen[n] = true
                    out[#out + 1] = { t = "s", n = n, id = e.id, i = (type(e.i) == "number" and e.i > 0) and e.i or nil, pet = true }
                end
            end
        end
    end
    local kept = {}
    for id, n in pairs(MR.petRemembered()) do
        n = plain(n)
        if n and type(id) == "number" and not seen[n] and not n:find("[|;,%[%]]") then
            seen[n] = true
            kept[#kept + 1] = { t = "s", n = n, id = id, i = TNn(function() return C_Spell.GetSpellTexture(id) end), pet = true }
        end
    end
    table.sort(kept, function(a, b) return a.n < b.n end)                  -- the kept names after the live book's, by name
    for _, e in ipairs(kept) do out[#out + 1] = e end
    return out
end

-- s13 THE PET'S PICKS (the user, 2026-11-16: "also things like my pet attack are missing from the spells options."): with a pet out
-- (UnitExists("pet")) the pet's COMMANDS as the game's own secure slash commands (slashcommands.lua: /petattack PetAttack, /petfollow,
-- /petstay PetWait, /petpassive, /petdefensive, /petaggressive, /petdismiss; /petmoveto, /petassist, /petdefensiveassist only when the pet
-- bar has that button; a base mode the pet bar shows only in its assist form is that one), each by the game's own word and pet-bar picture
-- (WC.petTok), its line the game's SLASH_<command>1; then the pet book's SPELLS (Spells s10p: not passive, not a command; only a pet with a
-- book) as "/cast <name>", the name the game's /cast takes (WC.spellsPetName). s14: they are the Spell / Item pop-out's PET tab (picking an
-- order turns the line into that order); with no pet out the tab offers the base orders registered here and the pet spells this character
-- keeps by name. Read only.
MR.PET = {
    { tok = "PET_ACTION_ATTACK", cmd = "PET_ATTACK", tex = "PET_ATTACK_TEXTURE", en = "/petattack", w = "Attack", base = true },
    { tok = "PET_ACTION_FOLLOW", cmd = "PET_FOLLOW", tex = "PET_FOLLOW_TEXTURE", en = "/petfollow", w = "Follow", base = true },
    { tok = "PET_ACTION_WAIT", cmd = "PET_STAY", tex = "PET_WAIT_TEXTURE", en = "/petstay", w = "Stay", base = true },
    { tok = "PET_ACTION_MOVE_TO", cmd = "PET_MOVE_TO", tex = "PET_MOVE_TO_TEXTURE", en = "/petmoveto", w = "Move To" },
    { tok = "PET_MODE_PASSIVE", cmd = "PET_PASSIVE", tex = "PET_PASSIVE_TEXTURE", en = "/petpassive", w = "Passive", base = true },
    { tok = "PET_MODE_DEFENSIVE", cmd = "PET_DEFENSIVE", tex = "PET_DEFENSIVE_TEXTURE", en = "/petdefensive", w = "Defensive", base = true,
      alt = "PET_MODE_DEFENSIVEASSIST" },
    { tok = "PET_MODE_DEFENSIVEASSIST", cmd = "PET_DEFENSIVEASSIST", tex = "PET_DEFENSIVEASSIST_TEXTURE", en = "/petdefensiveassist", w = "Defensive" },
    { tok = "PET_MODE_AGGRESSIVE", cmd = "PET_AGGRESSIVE", tex = "PET_AGGRESSIVE_TEXTURE", en = "/petaggressive", w = "Aggressive", base = true,
      alt = "PET_MODE_ASSIST" },
    { tok = "PET_MODE_ASSIST", cmd = "PET_ASSIST", tex = "PET_ASSIST_TEXTURE", en = "/petassist", w = "Assist" },
    { tok = "PET_ACTION_DISMISS", cmd = "PET_DISMISS", tex = "PET_DISMISS_TEXTURE", en = "/petdismiss", w = "Dismiss", base = true },
}
-- the pet bar's command buttons now (GetPetActionInfo's names: PET_ACTION_* / PET_MODE_* tokens) -> true
function MR.petBar()
    local on = {}
    for i = 1, 10 do
        local ok, name = CALL(function() return GetPetActionInfo(i) end)
        if ok and type(name) == "string" and WC.isPlain(name) then on[name] = true end
    end
    return on
end
-- the game's slash word for a pet command (its SLASH_<cmd>1: plain, one word starting "/"), else the English one for a base command
function MR.petSlash(p)
    local g = plain(rawget(_G, "SLASH_" .. p.cmd .. "1"))
    if g and g:find("^/%a+$") then return g end
    return p.base and p.en or nil
end
-- our command for a pet entry (its SLASH key)
function MR.petCmd(p)
    for _, c in ipairs(MR.CMDS) do if c.key == p.cmd then return c end end
end
-- the Pet tab's rows: the orders ({ t = "p", n, l = its line, c = our command, i }), then the pet's spells ({ t = "s", n, i, id, pet })
function MR.petRows()
    local out, seen = {}, {}
    local out1 = S(function() return UnitExists("pet") end) == true
    local bar = out1 and MR.petBar() or {}
    local tok = type(WC.petTok) == "table" and WC.petTok or nil
    for _, p in ipairs(MR.PET) do
        local show
        if out1 then show = bar[p.tok] or (p.base and not (p.alt and bar[p.alt] and not bar[p.tok]))
        else show = p.base end
        local c = MR.petCmd(p)
        local line = show and c and (MR.wordFor(c) or MR.petSlash(p))
        if line and not seen[line] then
            seen[line] = true
            local okN, nm, okI, ic = false, nil, false, nil
            if tok then okN, nm = pcall(tok.name, p.tok); okI, ic = pcall(tok.icon, p.tex) end
            out[#out + 1] = { t = "p", n = (okN and plain(nm)) or plain(rawget(_G, p.tok)) or p.w, l = line, c = c.k,
                              i = (okI and type(ic) == "number" and ic > 0) and ic or nil }
        end
    end
    for _, e in ipairs(MR.petSpells()) do out[#out + 1] = e end
    return out
end

-- ---------------------------------------------------------------- s14 the checks (MR.check: hard refuses Done, soft is amber)
function MR.condKeys(set)
    local out = {}
    for _, c in ipairs(set.cn or {}) do
        if c.raw then out[#out + 1] = "~" .. c.raw else out[#out + 1] = (c.no and "!" or "") .. c.k end
    end
    return out
end
-- an item named / numbered [a] is in the bags or worn (a slot number is the player's own choice: never questioned)
function MR.hasItem(a)
    a = MR.trim(a)
    if a == "" or tonumber(a) then return true end
    local key = tonumber(a:match("^item:(%d+)$") or "") or a
    local n = TNn(function() return C_Item.GetItemCount(key) end) or TNn(function() return GetItemCount(key) end) or 0
    if n > 0 then return true end
    for sl = 1, 19 do
        local id = TNn(function() return GetInventoryItemID("player", sl) end)
        if id and (id == key or TSs(function() return C_Item.GetItemNameByID(id) end) == key) then return true end
    end
    return false
end
-- a spell (or an item /cast takes) this character can cast by name
function MR.hasSpell(n)
    if not n or n == "" then return true end
    if MR.bookId(n) then return true end
    for _, e in ipairs(MR.petSpells()) do if e.n == n then return true end end
    return MR.hasItem(n)
end
-- the HARD faults: nil, or the fault and the chip it is on ("pick" a missing spell, "empty", "long")
function MR.hard(L, len)
    if type(L) ~= "table" or L.kind ~= "cmd" then return "empty", "cmd" end
    local c = MR.CMD[L.c]
    if c and c.arg and not c.opt then
        for k, set in ipairs(L.cs) do
            local a = MR.trim(set.a)
            if c.arg == "seq" then local _, list = MR.seqParts(a); if #list == 0 then return "pick", k == 1 and "arg" or ("c" .. k .. "a") end
            elseif a == "" then return "pick", k == 1 and "arg" or ("c" .. k .. "a") end
        end
    end
    if (len or 0) > MR.BODY_MAX then return "long", "text" end
    return nil
end
-- the SOFT warnings (out of a fight only; in one the chips stay neutral): "book", "bags", "else"
function MR.soft(L)
    local out = {}
    if type(L) ~= "table" or L.kind ~= "cmd" or combat() then return out end
    local c = MR.CMD[L.c]
    if not c then return out end
    local seen = {}
    local function add(w) if not seen[w] then seen[w] = true; out[#out + 1] = w end end
    local harm = false
    for _, set in ipairs(L.cs) do
        local a = MR.trim(set.a)
        if a ~= "" then
            if c.arg == "spell" then
                local _, n = MR.castParts(a)
                if not MR.hasSpell(n) then add("book") end
                if MR.kindOf(n) == "harm" then harm = true end
            elseif c.arg == "seq" then
                local _, list = MR.seqParts(a)
                for _, s in ipairs(list) do
                    local _, n = MR.castParts(s)
                    if not MR.hasSpell(n) then add("book") end
                    if MR.kindOf(n) == "harm" then harm = true end
                end
            elseif c.arg == "item" or c.arg == "equip" then
                if not MR.hasItem(a) then add("bags") end
            elseif c.arg == "eslot" then
                local _, it = MR.eslotParts(a)
                if it ~= "" and not MR.hasItem(it) then add("bags") end
            end
        end
    end
    local last = L.cs[#L.cs]
    if harm and (c.k == "cast" or c.k == "seq") and last and not MR.plainSet(last) then add("else") end
    return out
end
function MR.check(L, len) local h, hc = MR.hard(L, len); return h, hc, MR.soft(L) end

-- ---------------------------------------------------------------- s14 the editor's strips (D3: the add-on's chips, the app's pictures)
function MR.strip(L)
    if type(L) ~= "table" or L.kind ~= "cmd" then return { k = L and L.kind or "empty", c = L and L.c or nil } end
    local c = MR.CMD[L.c]
    local s = { k = c.meta and "tip" or "cmd", c = L.c }
    if c.pet then s.cn = plain(rawget(_G, c.pet)); s.i = MR.petIcon(c.k) end
    local a1 = L.cs[1] and L.cs[1].a or ""
    if c.arg == "seq" then
        local reset, list = MR.seqParts(a1)
        s.sq = {}
        for k, e in ipairs(list) do
            if k > 4 then break end
            local ic, n = MR.look("spell", e)
            s.sq[k] = { i = ic, n = n }
        end
        s.rs = reset
        s.i, s.n = s.sq[1] and s.sq[1].i, s.sq[1] and s.sq[1].n
    elseif c.arg == "eslot" then
        local sl, it = MR.eslotParts(a1)
        s.sl = sl
        s.i, s.n = MR.look("item", it)
    elseif c.arg then
        local ic, n = MR.look(c.arg, a1)
        s.i, s.n = ic or s.i, n
        if c.arg == "spell" then local bang, _, sub = MR.castParts(a1); s.bang = bang or nil; s.sub = sub end
    end
    s.cs = {}
    for k, set in ipairs(L.cs) do
        local e = { x = k, u = set.u, f = MR.condKeys(set) }
        if #e.f == 0 then e.f = nil end
        if k > 1 and c.arg and (set.a or "") ~= a1 then local ic, n = MR.look(c.arg, set.a); e.a = { i = ic, n = n or "" } end
        s.cs[k] = e
    end
    local w = MR.soft(L)
    s.w = w[1]
    return s
end

-- ---------------------------------------------------------------- s14 PRESETS (D2): whole ready-made macros on a new or empty macro
-- picks: "help" a helpful spell, "harm" a harmful one (harm2 / harm3 the next ones), "int" an interrupt from the table below, "any" any
-- spell; need = when shown: "help" / "harm" / "int" known, "harm2" two harmful, pet = pet classes, forms = classes with forms, trink = an
-- equipped trinket with a use; last = only when a tile is left (the PC's Mouseover heal)
MR.INTERRUPTS = { 8042, 8044, 8045, 8046, 10412, 10413, 10414, 1766, 1767, 1768, 1769, 6552, 6554, 72, 1671, 1672, 2139, 15487, 19647 }
MR.PRESETS = {
    -- s17 BLANK (the user, 2026-11-18: "a blank template option on macros to just start a fresh new blank template"): nothing filled in
    { k = "blank", picks = {}, need = {}, cmds = {}, blank = true, lines = function() return {} end },
    { k = "heal", picks = { "help" }, need = { "help" }, cmds = { "cast" },
      lines = function(w, p) return { "#showtooltip", w.cast .. " [help,nodead][@player] " .. p[1] } end },
    { k = "fof", picks = { "help", "harm" }, need = { "help", "harm" }, cmds = { "cast" },
      lines = function(w, p) return { w.cast .. " [help,nodead] " .. p[1] .. "; " .. p[2] } end },
    { k = "int", picks = { "int" }, need = { "int" }, cmds = { "stopcasting", "cast" },
      lines = function(w, p) return { w.stopcasting, w.cast .. " [@focus,harm,nodead][] " .. p[1] } end },
    { k = "focus", picks = {}, need = {}, cmds = { "focus" }, lines = function(w) return { w.focus } end },
    { k = "atk", picks = { "harm" }, need = {}, cmds = { "target", "startattack", "cast" },
      lines = function(w, p) return { w.target .. " [noexists][dead] softenemy", w.startattack .. " [harm,nodead]", w.cast .. " " .. p[1] } end },
    { k = "petatk", picks = { "harm" }, need = {}, pet = true, cmds = { "target", "petattack", "cast" },
      lines = function(w, p) return { w.target .. " [noexists][dead] softenemy", w.petattack, w.cast .. " " .. p[1] } end },
    { k = "seq", picks = { "harm", "harm2", "harm3" }, need = { "harm2" }, cmds = { "seq" },
      lines = function(w, p)
          local list = {}
          for k = 1, 3 do if p[k] and p[k] ~= "" then list[#list + 1] = p[k] end end
          return { w.seq .. " reset=target " .. table.concat(list, ", ") }
      end },
    { k = "form", picks = { "any" }, need = {}, forms = true, cmds = { "cancelform", "cast" },
      lines = function(w, p) return { w.cancelform, w.cast .. " " .. p[1] } end },
    { k = "trink", picks = { "any" }, need = {}, trink = true, cmds = { "use", "cast" },
      lines = function(w, p, ctx) return { w.use .. " [combat] " .. (ctx.trink or 13), w.cast .. " " .. p[1] } end },
    { k = "mo", picks = { "help" }, need = { "help" }, last = true, cmds = { "cast" },
      lines = function(w, p) return { w.cast .. " [@mouseover,help,nodead][] " .. p[1] } end },
}
-- the book's spells by kind, for the presets' picks
function MR.byKind()
    local out = { help = {}, harm = {}, any = {}, int = {} }
    local ints = {}
    for _, id in ipairs(MR.INTERRUPTS) do
        local n = TSs(function() return C_Spell.GetSpellName(id) end)
        if n then ints[n] = true end
    end
    for _, e in ipairs(MR.book()) do
        if not e.gen and not e.prof and not e.pet then
            local h = S(function() return C_Spell.IsSpellHelpful(e.id) end) == true
            local x = not h and S(function() return C_Spell.IsSpellHarmful(e.id) end) == true
            if h then out.help[#out.help + 1] = e.n end
            if x then out.harm[#out.harm + 1] = e.n end
            if ints[e.n] then out.int[#out.int + 1] = e.n end
            out.any[#out.any + 1] = e.n
        end
    end
    for _, e in ipairs(MR.petSpells()) do if ints[e.n] then out.int[#out.int + 1] = e.n end end
    return out
end
-- the equipped trinket with a use (13 first), or nil
function MR.trinket()
    for _, sl in ipairs({ 13, 14 }) do
        local id = TNn(function() return GetInventoryItemID("player", sl) end)
        local use = id and S(function() return (C_Item.GetItemSpell(id)) end)
        if use ~= nil and use ~= false and use ~= WC.ABSENT and use ~= WC.THREW and use ~= WC.SECRET then return sl end
    end
end
-- the words every preset writes (this client's registered ones); nil when one is not registered
function MR.words(list)
    local w = {}
    for _, k in ipairs(list) do
        local c = MR.CMD[k]
        local word = c and MR.wordFor(c)
        if not word then return nil end
        w[k] = word
    end
    return w
end
-- the tiles shown now (at most TILES, the table's order; "last" only when a tile is left), each with its default picks
function MR.tiles()
    local now = GetTime()
    if st.tc and st.tc.at == now then return st.tc.list end
    local kinds = MR.byKind()
    local ctx = { trink = MR.trinket() }
    local out = {}
    local function default(kind)
        if kind == "help" then return kinds.help[1] elseif kind == "harm" then return kinds.harm[1]
        elseif kind == "harm2" then return kinds.harm[2] elseif kind == "harm3" then return kinds.harm[3]
        elseif kind == "int" then return kinds.int[1] else return kinds.any[1] end
    end
    for _, p in ipairs(MR.PRESETS) do
        local ok = MR.words(p.cmds) ~= nil
        for _, n in ipairs(p.need) do
            if n == "help" and not kinds.help[1] then ok = false end
            if n == "harm" and not kinds.harm[1] then ok = false end
            if n == "harm2" and not kinds.harm[2] then ok = false end
            if n == "int" and not kinds.int[1] then ok = false end
        end
        if p.pet and not MR.petClass() then ok = false end
        if p.forms and not MR.forms() then ok = false end
        if p.trink and not ctx.trink then ok = false end
        if p.last and #out >= MR.TILES then ok = false end
        if ok and #out < MR.TILES then
            local picks = {}
            for k, kind in ipairs(p.picks) do picks[k] = default(kind) or "" end
            out[#out + 1] = { p = p, picks = picks, ctx = ctx }
        end
    end
    st.tc = { at = now, list = out }
    return out
end
function MR.presetLines(t, picks)
    local w = MR.words(t.p.cmds)
    if not w then return {} end
    local p = {}
    for k = 1, #t.p.picks do p[k] = (picks and picks[k]) or "" end
    return t.p.lines(w, p, t.ctx)
end

-- ---------------------------------------------------------------- s14 the editor's rows: the name, the icon, the lines (or the preset
-- tiles of an empty macro), then + Line and More (s15: Presets and Delete are More's rows)
function MR.editRows()
    local i = MR.selIndex()
    if not i then return nil end
    local name, icon, body = MR.info(i)
    local lines = MR.lines(body)
    local rows = { "name", "icon" }
    if #lines == 0 and not MR.isBlank(name) then
        for k = 1, #MR.tiles() do rows[#rows + 1] = "t" .. k end
        rows[#rows + 1] = "add"; rows[#rows + 1] = "more"
    else
        for k = 1, math.min(#lines, MR.LINE_ROWS) do rows[#rows + 1] = "l" .. k end
        rows[#rows + 1] = "add"; rows[#rows + 1] = "more"
    end
    return rows, lines, name, icon, body or "", i
end
-- the s<k> cell of a thing in edit mode: s0 the name, s1 the icon, s2..s20 the lines (or tiles), s21 + Line, s22 More (s15)
function MR.editCell(row)
    if row == "name" then return 0 elseif row == "icon" then return 1 end
    local l = type(row) == "string" and tonumber(row:match("^[lt](%d+)$"))
    if l then return l + 1 end
    if row == "add" then return 21 elseif row == "more" then return 22 end
end
-- the editor's rows for the D-pad: the name and icon, one line a row (the tiles four a row), the buttons
function MR.editNav(rows)
    local out, cur = { { "name", "icon" } }, nil
    local btn = {}
    for _, r in ipairs(rows) do
        if r:match("^l%d+$") then out[#out + 1] = { r }
        elseif r:match("^t%d+$") then
            if not cur or #cur >= 4 then cur = {}; out[#out + 1] = cur end
            cur[#cur + 1] = r
        elseif r == "add" or r == "more" then btn[#btn + 1] = r end
    end
    out[#out + 1] = btn
    return out
end
-- the editor's glow is a ROW KEY ("name", "l3", "add", ...): a write that adds or drops lines keeps it on the same thing (a line gone:
-- the last line, or + Line); st.ef is its place in [rows]
function MR.efSync(rows)
    local k = st.efk or "name"
    for i, r in ipairs(rows) do
        if r == k then
            st.ef = i - 1
            local n = tonumber(k:match("^l(%d+)$") or "")
            if n then st.eln = n end                                     -- s16: More's Insert below goes under this line
            return k
        end
    end
    if k:match("^[lt]%d+$") then
        local last = nil
        for i, r in ipairs(rows) do if r:match("^[lt]%d+$") then last = i end end
        st.efk = last and rows[last] or "add"
    else
        st.efk = "name"
    end
    for i, r in ipairs(rows) do if r == st.efk then st.ef = i - 1 end end
    return st.efk
end
function MR.editFromCell(k, rows)
    for _, r in ipairs(rows) do if MR.editCell(r) == k then return r end end
end
-- one step on a grid of keys (rows of keys): left / right in the row, up / down to the row's neighbour (its column kept where it can be)
function MR.nav(rows, cur, key)
    local r, c = 1, 1
    for ri, row in ipairs(rows) do for ci, k in ipairs(row) do if k == cur then r, c = ri, ci end end end
    if not rows[r] then return cur end
    if key == "PADDLEFT" then c = math.max(1, c - 1)
    elseif key == "PADDRIGHT" then c = math.min(#rows[r], c + 1)
    elseif key == "PADDUP" then if r > 1 then r = r - 1; c = math.min(c, #rows[r]) end
    elseif key == "PADDDOWN" then if r < #rows then r = r + 1; c = math.min(c, #rows[r]) end end
    return rows[r] and rows[r][c] or cur
end

-- ---------------------------------------------------------------- s14 THE BUILDER: a DRAFT of one line (kept on B; one at a time)
-- st.draft = { mac = the macro's name, i = its index, idx = the line (nil = a new one), at = where a new one goes, orig = the line as
-- stored, L = the model being built }
function MR.newLine()
    local c = MR.CMD.cast
    return { kind = "cmd", c = "cast", w = MR.wordFor(c) or c.en[1], cs = { { cn = {}, a = "" } } }
end
-- (s14 review) a line the parser took with its own spacing ("/cast [help] Heal ;Bolt") composes with the game's ("; "): spaces the
-- game reads alike (MR.norm, the parser's own fidelity rule) are no change - opening it and pressing Done writes nothing, shows no Revert
function MR.dirty(d) return d ~= nil and (d.idx == nil or MR.norm(MR.compose(d.L)) ~= MR.norm(d.orig)) end
-- the body the draft's Done would write
function MR.bodyWith(d, lines)
    local out = {}
    for k, ln in ipairs(lines) do out[k] = ln end
    local text = MR.compose(d.L)
    if d.idx then out[d.idx] = text
    else table.insert(out, math.max(1, math.min(d.at or (#out + 1), #out + 1)), text) end
    return MR.join(out)
end
-- open the builder on line [idx] (its draft resumed while it still matches the stored line), or on a NEW line going at [at]
function MR.openDraft(idx, at)
    local i = MR.selIndex()
    if not i then return false end
    local name, _, body = MR.info(i)
    local lines = MR.lines(body)
    local d = st.draft
    local same = d and d.mac == name and d.idx == idx and (idx == nil and d.at == at or (idx and d.orig == lines[idx]))
    if not same then
        if idx then
            local L = MR.parse(lines[idx])
            if L.kind ~= "cmd" then return false, L.kind end
            d = { mac = name, i = i, idx = idx, orig = lines[idx], L = L }
        else
            d = { mac = name, i = i, idx = nil, at = at, L = MR.newLine() }
        end
        st.draft = d
    end
    st.mode, st.pop, st.remAt = "build", nil, nil
    st.bc = "cmd"
    if not idx and not same then st.pop = { k = "cmd", cur = "r1", page = 1 } end   -- a new line: its Action list open (X / + Line)
    return true
end
-- the builder's chips as rows (the D-pad's and the app's layout) and their s-cells
-- s15: the footer is Done and More (Aa Text and Remove / Revert are More's rows): More takes Aa Text's s17, Done keeps s19
MR.BCELL = { cmd = 0, arg = 1, rank = 2, reset = 2, keep = 3, add = 16, more = 17, done = 19 }
function MR.bcell(k)
    if MR.BCELL[k] then return MR.BCELL[k] end
    local n, part = tostring(k):match("^c(%d)([ufa])$")
    if n then return 4 + 3 * (tonumber(n) - 1) + ((part == "u" and 0) or (part == "f" and 1) or 2) end
end
function MR.bRows(d)
    local L = d.L
    local c = MR.CMD[L.c] or MR.CMD.cast
    local r1 = { "cmd" }
    local a1 = L.cs[1] and L.cs[1].a or ""
    if c.arg then r1[#r1 + 1] = "arg" end
    if c.rank then
        local _, n = MR.castParts(a1)
        if n ~= "" and #MR.ranks(n) > 1 then r1[#r1 + 1] = "rank" end
    end
    if c.arg == "seq" then r1[#r1 + 1] = "reset" end
    if c.keep and MR.trim(a1) ~= "" then r1[#r1 + 1] = "keep" end
    local rows = { r1 }
    for k, set in ipairs(L.cs) do
        local row = { "c" .. k .. "u", "c" .. k .. "f" }
        if k > 1 and c.arg and (set.own or (set.a or "") ~= a1) then row[#row + 1] = "c" .. k .. "a" end
        rows[#rows + 1] = row
    end
    if #L.cs < MR.CASES then rows[#rows + 1] = { "add" } end
    rows[#rows + 1] = { "done", "more" }
    return rows
end
function MR.bHas(d, key)
    for _, row in ipairs(MR.bRows(d)) do for _, k in ipairs(row) do if k == key then return true end end end
    return false
end

-- ---------------------------------------------------------------- the draft's changes (no write: the draft only)
-- the command changes; the cases' units and conditions stay; an argument of another kind goes
function MR.setCmd(d, k)
    local c, old = MR.CMD[k], MR.CMD[d.L.c]
    if not c then return end
    d.L.c, d.L.w = k, MR.wordFor(c) or (c.en and c.en[1]) or d.L.w
    if not c.arg or not old or c.arg ~= old.arg then
        if not ((c.arg == "spell" and old and old.arg == "item") or (c.arg == "item" and old and old.arg == "spell")) then
            for _, set in ipairs(d.L.cs) do set.a = ""; set.own = nil end
        end
    end
end
-- case [k]'s argument; case 1's carries the cases that had the same one (they follow the line's spell)
function MR.setArg(d, k, a)
    local cs = d.L.cs
    if k == 1 or not cs[k] then
        local old = cs[1].a or ""
        for x = 2, #cs do if (cs[x].a or "") == old and not cs[x].own then cs[x].a = a end end
        cs[1].a = a
    else
        cs[k].a, cs[k].own = a, true
    end
end
-- the line's spell keeps its keep-on mark; its rank goes back to the top
function MR.pickSpell(d, k, n)
    local c = MR.CMD[d.L.c]
    if c and c.arg == "spell" then
        local bang = MR.castParts(d.L.cs[math.max(1, k)] and d.L.cs[math.max(1, k)].a or "")
        MR.setArg(d, k, MR.castJoin(bang, n, nil))
    else
        MR.setArg(d, k, n)
    end
end
-- a case: { u, uf, cn, a }; the "+ Case" options (helpful / harmful / always)
function MR.caseOpts(d)
    local c = MR.CMD[d.L.c]
    local out = {}
    if c and c.arg == "spell" then
        local _, n = MR.castParts(d.L.cs[1].a or "")
        local kind = MR.kindOf(n)
        if kind == "help" then
            if MR.softFriend() then out[#out + 1] = "sf1" end
            out[#out + 1] = "fo1"; out[#out + 1] = "me"; out[#out + 1] = "tg"
        elseif kind == "harm" then
            out[#out + 1] = "fo1"; out[#out + 1] = "se1"; out[#out + 1] = "tg"
        else
            out[#out + 1] = "tg"
        end
        out[#out + 1] = "sp"
    else
        out[#out + 1] = "tg"
    end
    out[#out + 1] = "bl"
    return out
end
-- one "+ Case" option onto the draft: "first" ones go on top (a step tried first), "else" ones at the end
function MR.addCase(d, opt)
    local cs = d.L.cs
    if #cs >= MR.CASES then return nil end
    local a1 = cs[1].a or ""
    local _, n = MR.castParts(a1)
    local kind = MR.kindOf(n)
    local nc
    if opt == "sf1" then nc = { u = "softfriend", uf = "@", cn = { { n = "help", k = "help", no = false }, { n = "dead", k = "dead", no = true } } }
    elseif opt == "se1" then nc = { u = "softenemy", uf = "@", cn = { { n = "harm", k = "harm", no = false }, { n = "dead", k = "dead", no = true } } }
    elseif opt == "fo1" then
        local h = (kind == "help") and "help" or "harm"
        nc = { u = "focus", uf = "@", cn = { { n = h, k = h, no = false }, { n = "dead", k = "dead", no = true } } }
    end
    if nc then
        nc.a = a1
        table.insert(cs, 1, nc)
        -- a plain line ("/cast Heal") had its one case bare: it is now the else of the new first one
        if #cs == 2 and MR.plainSet(cs[2]) then cs[2].bare = nil end
        return 1
    end
    -- an else after a PLAIN last case would never be reached: that case first gets the spell's own test (a friend / an enemy, alive;
    -- anything else: someone there), the step the "else" is for
    local last = cs[#cs]
    if (opt == "me" or opt == "tg" or opt == "sp") and last and MR.plainSet(last) then
        if kind == "help" or kind == "harm" then
            last.cn = { { n = kind, k = kind, no = false }, { n = "dead", k = "dead", no = true } }
        else
            last.cn = { { n = "exists", k = "exists", no = false } }
        end
        last.bare = nil
    end
    if opt == "me" then nc = { u = "player", uf = "@", cn = {}, a = a1 }
    elseif opt == "tg" then nc = { cn = {}, a = a1 }
    elseif opt == "sp" then nc = { cn = {}, a = "", own = true }
    else nc = { cn = {}, a = a1 } end
    cs[#cs + 1] = nc
    return #cs
end
function MR.removeCase(d, k)
    local cs = d.L.cs
    if #cs <= 1 then
        cs[1].u, cs[1].uf, cs[1].cn = nil, nil, {}
        return
    end
    local wasFirst = k == 1
    table.remove(cs, k)
    if wasFirst and cs[1] then cs[1].own = nil end
end
-- the If grid's tri-state: off -> yes -> no -> off (a condition keeps its place; a new one goes last)
function MR.cycleCond(set, key)
    local def = MR.COND[key]
    if not def then return end
    for i, c in ipairs(set.cn) do
        if c.k == key then
            if not c.no then c.no = true else table.remove(set.cn, i) end
            return
        end
    end
    set.cn[#set.cn + 1] = { n = def.n, v = def.v, k = key, no = false }
end
function MR.condState(set, key)
    for _, c in ipairs(set.cn or {}) do if c.k == key then return c.no and 2 or 1 end end
    return 0
end
-- castsequence's reset options (toggles; a time is one of them)
MR.RESETS = { "target", "combat", "shift", "ctrl", "alt", "6", "12", "30" }
function MR.resetToggle(reset, opt)
    local have, out = {}, {}
    for part in ((reset or "") .. "/"):gmatch("(.-)/") do if part ~= "" then have[#have + 1] = part end end
    local on = false
    for _, p in ipairs(have) do if p == opt then on = true end end
    for _, p in ipairs(have) do
        if p ~= opt and not (tonumber(opt) and tonumber(p)) then out[#out + 1] = p end
    end
    if not on then out[#out + 1] = opt end
    return table.concat(out, "/")
end
function MR.resetHas(reset, opt)
    for part in ((reset or "") .. "/"):gmatch("(.-)/") do if part == opt then return true end end
    return false
end

-- ---------------------------------------------------------------- s14 THE POP-OUTS (each slides out of its chip; the app draws them)
-- st.pop = { k = kind, cur = its cell ("r3", a tab "t2", the search "q"), page, tab, q (the search), case (the case it is for), at (a
-- sequence entry), back (the pop-out under it) }. Kinds: cmd (the Action list), arg (Spell / Item: tabs, search, a 6 x 3 grid), on (the
-- unit), if (the tri-state grid), rank, case (+ Case), reset (castsequence), seq (castsequence's spells), icon (the editor's icon),
-- more (s15: the room's More, one column: the editor's Presets / Delete, the builder's Aa Text / Remove or Revert)
MR.TABS = { spell = { "sp", "pet", "gen", "it", "sl" }, item = { "it", "sl", "sp" }, seq = { "sp", "pet", "gen", "it" }, aura = { "sp", "pet" },
            equip = { "it" }, eslot = { "sl", "it" }, pick = { "sp", "pet", "gen" } }
function MR.cmdList()
    local out = {}
    local pet, forms = MR.petClass(), MR.forms()
    for _, c in ipairs(MR.CMDS) do
        if not c.meta and (c.chat or MR.wordFor(c)) and (not c.pet or pet) and (not c.forms or forms) then
            out[#out + 1] = { k = c.k, n = c.pet and plain(rawget(_G, c.pet)) or nil, i = c.pet and MR.petIcon(c.k) or nil }
        end
    end
    return out
end
-- the kind of thing the open arg pop-out picks: the command's, a sequence entry's, a preset's pick
function MR.argKind(pop)
    if pop.ps then return "pick" end
    local d = st.draft
    if not d then return "spell" end
    if pop.at then return "seq" end
    local c = MR.CMD[d.L.c]
    return c and c.arg or "spell"
end
function MR.tabsFor(pop)
    local kind = MR.argKind(pop)
    local base = MR.TABS[kind] or MR.TABS.spell
    local out = {}
    for _, t in ipairs(base) do
        if t ~= "pet" or MR.petClass() then out[#out + 1] = t end
    end
    return out
end
-- one tab's rows: { t = "s" | "p" | "u" | "e" | "o", n, i, id, l, c, sl }
function MR.tabRows(tab, kind)
    local out = {}
    if tab == "sp" or tab == "gen" then
        for _, e in ipairs(MR.book()) do
            if not e.pet and ((tab == "gen") == (e.gen == true)) then out[#out + 1] = { t = "s", n = e.n, i = e.i, id = e.id } end
        end
    elseif tab == "pet" then
        for _, e in ipairs(MR.petRows()) do
            if e.t ~= "p" or (kind ~= "seq" and kind ~= "pick" and kind ~= "aura" and kind ~= "item") then out[#out + 1] = e end
        end
    elseif tab == "it" then
        out = MR.bagItems(kind == "equip" or kind == "eslot")
    elseif tab == "sl" then
        for _, s in ipairs(MR.slots(kind ~= "eslot")) do out[#out + 1] = s end
    end
    return out
end
function MR.unitRows(forArg)
    local out = {}
    local d = st.draft
    local used = {}
    if d then for _, s in ipairs(d.L.cs) do if s.u then used[s.u] = true end end end
    local c = d and MR.CMD[d.L.c]
    for _, u in ipairs(MR.UNITS) do
        local ok = true
        if u == "softfriend" and not (MR.softFriend() or used[u]) then ok = false end
        if u == "pet" and not (MR.petClass() or used[u]) then ok = false end
        if u == "" and forArg and c and not c.opt then ok = false end      -- /target needs someone
        if ok then out[#out + 1] = { u = u } end
    end
    return out
end
function MR.condRows(set)
    local out = {}
    local pet, forms, stealth = MR.petClass(), MR.forms(), MR.stealthClass()
    for _, c in ipairs(MR.CONDS) do
        local have = MR.condState(set, c.k) > 0
        local ok = have or not ((c.parsedOnly) or (c.pet and not pet) or (c.forms and not forms) or (c.stealth and not stealth))
        if ok then out[#out + 1] = { k = c.k, s = MR.condState(set, c.k) } end
    end
    return out
end
-- the open pop-out's cells now: { rows, cols, base (the s-cell of r1), tabs, pages, page }
function MR.popView(pop)
    local d = st.draft
    local v = { rows = {}, cols = 1, base = 0, page = pop.page or 1, pages = 1 }
    local function paged(list, per)
        v.pages = math.max(1, math.ceil(#list / per))
        pop.page = math.max(1, math.min(pop.page or 1, v.pages))
        v.page = pop.page
        for k = 1, per do
            local e = list[(pop.page - 1) * per + k]
            if not e then break end
            v.rows[k] = e
        end
    end
    if pop.k == "cmd" then v.cols = 3; paged(MR.cmdList(), MR.POP)
    elseif pop.k == "arg" then
        v.cols, v.base = 6, 6
        v.tabs = MR.tabsFor(pop)
        pop.tab = math.max(1, math.min(pop.tab or 1, #v.tabs))
        local list = MR.tabRows(v.tabs[pop.tab], MR.argKind(pop))
        local q = pop.q and pop.q:lower() or nil
        if q and q ~= "" then
            local f = {}
            for _, e in ipairs(list) do if (e.n or ""):lower():find(q, 1, true) then f[#f + 1] = e end end
            list = f
        end
        paged(list, MR.GRID)
    elseif pop.k == "on" then
        v.cols = 2; paged(MR.unitRows(pop.forArg), MR.POP)
    elseif pop.k == "if" then
        v.cols = 3
        local set = d and d.L.cs[pop.case or 1]
        paged(set and MR.condRows(set) or {}, MR.POP)
    elseif pop.k == "rank" then
        local _, n, sub = MR.castParts(d and d.L.cs[1].a or "")
        local list = { { r = 0, on = (sub == nil) or nil } }
        for k, r in ipairs(MR.ranks(n)) do list[#list + 1] = { r = k, sub = r.sub, on = (sub == r.sub) or nil } end
        v.m = #list - 1
        paged(list, MR.POP)
    elseif pop.k == "case" then
        local list = {}
        for _, o in ipairs(d and MR.caseOpts(d) or {}) do list[#list + 1] = { o = o } end
        paged(list, MR.POP)
    elseif pop.k == "reset" then
        v.cols = 2
        local reset = d and (MR.seqParts(d.L.cs[1].a or ""))
        local list = {}
        for _, o in ipairs(MR.RESETS) do list[#list + 1] = { o = o, on = MR.resetHas(reset, o) or nil } end
        paged(list, MR.POP)
    elseif pop.k == "seq" then
        local _, list = MR.seqParts(d and d.L.cs[1].a or "")
        local rows = {}
        for k, e in ipairs(list) do local ic, n = MR.look("spell", e); rows[k] = { n = n, i = ic } end
        rows[#rows + 1] = { add = true }
        paged(rows, MR.POP)
    elseif pop.k == "icon" then
        v.cols = 6
        local i = MR.selIndex()
        local _, icon, body = MR.info(i or 0)
        local list, seen = { { i = MR.ICON, auto = true, on = (icon == MR.ICON) or nil } }, { [MR.ICON] = true }
        for _, ln in ipairs(MR.lines(body)) do
            local s = MR.strip(MR.parse(ln))
            for _, ic in ipairs({ s.i, s.cs and s.cs[2] and s.cs[2].a and s.cs[2].a.i }) do
                if type(ic) == "number" and not seen[ic] then seen[ic] = true; list[#list + 1] = { i = ic, on = (icon == ic) or nil } end
            end
        end
        paged(list, MR.POP)
    elseif pop.k == "more" then
        -- s15: { o = "pre" | "del" | "text" | "rem", rv = Remove reads Revert, off = the fight refuses it (its write) }
        local list = {}
        local d = st.draft
        if st.mode == "build" and d then
            list[1] = { o = "text" }
            local rv = d.idx ~= nil and MR.dirty(d)
            list[2] = { o = "rem", rv = rv or nil, off = (combat() and d.idx ~= nil and not rv) or nil }
        else
            local _, lines, mname = MR.editRows()
            -- s16: Insert below (the old X): a new line under the line the glow was last on (a write only at the builder's Done)
            if lines and #lines > 0 then list[#list + 1] = { o = "ins", l = math.max(1, math.min(st.eln or #lines, #lines)) } end
            if lines and (#lines > 0 or MR.isBlank(mname)) then list[#list + 1] = { o = "pre" } end
            list[#list + 1] = { o = "del", off = combat() or nil }
        end
        paged(list, MR.POP)
    end
    return v
end
-- the s-cell of a pop-out cell key
function MR.popCell(pop, key, v)
    if key == "q" then return 5 end
    local t = tostring(key):match("^t(%d)$")
    if t then return tonumber(t) - 1 end
    local r = tonumber(tostring(key):match("^r(%d+)$") or "")
    if r then return (v and v.base or (pop.k == "arg" and 6 or 0)) + r - 1 end
end
function MR.popFromCell(pop, sk, v)
    if pop.k == "arg" then
        if sk <= 4 then return (v.tabs and v.tabs[sk + 1]) and ("t" .. (sk + 1)) or nil end
        if sk == 5 then return "q" end
    end
    local r = sk - v.base + 1
    if r >= 1 and v.rows[r] then return "r" .. r end
end
-- the pop-out's D-pad: its cells as rows (the arg pop-out: tabs, then the search, then the grid)
function MR.popNav(pop, v)
    local rows = {}
    if pop.k == "arg" then
        local tr = {}
        for k = 1, #(v.tabs or {}) do tr[#tr + 1] = "t" .. k end
        rows[1] = tr
        rows[2] = { "q" }
    end
    local row
    for k = 1, #v.rows do
        if not row or #row >= v.cols then row = {}; rows[#rows + 1] = row end
        row[#row + 1] = "r" .. k
    end
    return rows
end

-- ---------------------------------------------------------------- the document
local capture = function()
    local c = WC.capture
    if type(c) == "table" and (c.version or 0) >= 1 and type(c.take) == "function" and type(c.release) == "function" then return c end
end
-- s12: the room's row is on its place now (made, and - shared with the Calendar's - leased to this room: TapPad pad21)
function MR.onRow()
    local pad = WC.pad
    local r = pad and pad.rows and pad.rows[MR.ROW]
    if not r then return false end
    return type(pad.holds) ~= "function" or pad.holds(MR.ROW) == true
end
-- s12: the row is the Calendar's guest (the app asks for it on every showing, before its hold)
function MR.shared()
    local pad = WC.pad
    local r = pad and pad.rows and pad.rows[MR.ROW]
    return type(r) == "table" and r.guestOf ~= nil
end
function MR.keys()
    local pad = WC.pad
    local r = pad and pad.rows and pad.rows[MR.ROW]
    if not r or not MR.onRow() then return nil end             -- s12: the Calendar's on the shared row: no keys (the app asks again)
    local k = { s0 = MR.S0, n = MR.PER_PAGE }
    for name, col in pairs(MR.COL) do k[name] = col end
    return k
end
-- s14: a preset tile as the document carries it: its id, the pictures of its picks, its text (the app draws the label and the strip)
function MR.tileDoc(t)
    local ic = {}
    for _, n in ipairs(t.picks) do if MR.trim(n) ~= "" then ic[#ic + 1] = (MR.look("spell", n)) or 0 end end   -- an empty pick: no picture
    if t.p.blank then ic[1] = MR.ICON end                                -- s17: Blank shows the default icon
    return { k = t.p.k, ic = ic, t = MR.join(MR.presetLines(t, t.picks)) }
end
-- s14: the open pop-out as the document carries it
function MR.popDoc(pop)
    local v = MR.popView(pop)
    local p = { k = pop.k, cur = pop.cur, page = v.page, pages = v.pages, cols = v.cols, base = v.base, case = pop.case, q = pop.q,
                m = v.m, rows = v.rows, seq = pop.at and true or nil, ps = pop.ps and true or nil }
    if v.tabs then p.tabs = v.tabs; p.tab = v.tabs[pop.tab or 1] end
    if pop.k == "arg" then
        local d = st.draft
        local c = d and MR.CMD[d.L.c]
        p.forc = c and c.k or nil
    end
    return p
end
-- s14: the builder's document (the draft: its text, its chips as rows, their s-cells, its checks)
function MR.buildDoc(d, lines)
    local L = d.L
    local c = MR.CMD[L.c] or MR.CMD.cast
    local nb = MR.bodyWith(d, lines)
    local a1 = L.cs[1] and L.cs[1].a or ""
    local b = { ln = d.idx, at = d.at, t = MR.compose(L), len = #nb, c = L.c, cn = c.pet and plain(rawget(_G, c.pet)) or nil,
                cur = st.bc, dr = MR.dirty(d) or nil, rem = (st.remAt and GetTime() - st.remAt <= MR.DEL_S) or nil, cs = {} }
    local s = MR.strip(L)
    if s.n or s.i then b.a = { i = s.i, n = s.n, bang = s.bang, sub = s.sub } end
    if c.rank and s.n and b.a then
        local rk = MR.ranks(s.n)
        if #rk > 1 then
            local at = 0
            for k, r in ipairs(rk) do if r.sub == s.sub then at = k end end
            b.a.rk = { at, #rk }                                         -- 0 = the top rank (no parentheses)
        end
    end
    if c.arg == "seq" then b.sq, b.rs = s.sq, s.rs end
    if c.arg == "eslot" then b.sl = s.sl end
    for k, set in ipairs(L.cs) do
        local e = { x = k, u = set.u, f = MR.condKeys(set) }
        if #e.f == 0 then e.f = nil end
        if k > 1 and c.arg and (set.own or (set.a or "") ~= a1) then local ic, n = MR.look(c.arg, set.a); e.a = { i = ic, n = n or "" } end
        b.cs[k] = e
    end
    b.rows = MR.bRows(d)
    b.ch = {}
    for _, row in ipairs(b.rows) do for _, k in ipairs(row) do b.ch[k] = MR.bcell(k) end end
    if not st.pop and not MR.bYOwn(st.bc) then b.ym = true end            -- s15: Y opens More now
    local h, hc, soft = MR.check(L, #nb)
    b.h, b.hc = h, hc
    if #soft > 0 then b.w = soft end
    if st.pop then b.pop = MR.popDoc(st.pop) end
    return b
end
function MR.doc()
    -- (s10 review) an editor whose macro is gone (deleted or renamed elsewhere): back to the grid, never onto a neighbour
    local inMac = st.mode == "edit" or st.mode == "build" or st.mode == "move" or st.mode == "pre" or st.mode == "ps"
    if inMac and MR.ready() and not MR.off() and not MR.selIndex() then
        st.mode, st.delAt, st.pop, st.mv, st.ps, st.remLn = "grid", nil, nil, nil, nil, nil
    end
    local c = MR.counts()
    local d = { v = SV, ready = MR.ready() or nil, off = MR.off() or nil, win = MR.winShown() or nil, cb = combat() or nil,
                norow = st.norow or nil, sh = MR.shared() or nil, tab = st.tab, cnt = c, me = TSs(function() return UnitName("player") end),
                mode = st.mode, note = st.note,
                ctl = { held = ctlr.held or nil, want = ctlr.want and true or false, why = ctlr.why, cap = capture() ~= nil } }
    local keys = MR.keys()
    if keys then
        d.keys = keys; d.pad = WC.pad.describe()
        -- (s10 review) the row made but not placed (it ends inside the pop-up dialogs at the smallest UI scale, or a fight re-laid the
        -- screen): no cell of it can be tapped, so the room says "No pad row" rather than looking alive
        if not (type(d.pad) == "table" and type(d.pad.rows) == "table" and d.pad.rows[MR.ROW] ~= nil) then d.norow = true end
        -- the `wx` cell really is the game's window close now (set out of combat once Blizzard_MacroUI has loaded): the app taps it then
        local wx = MR.cells and MR.cells[MR.COL.wx]
        d.wxa = (wx and wx.wcArmed) and true or nil
    end
    if not d.ready or d.off then return d end
    -- what shows under an open keyboard: the place it was opened from
    local view = (st.mode == "kbd" and st.kbd and st.kbd.back) or st.mode
    d.view = view
    if view == "grid" or view == "bind" then
        local items, pages = MR.focusGrid()
        d.page, d.pages, d.cur = st.page, pages, st.cur
        local cells = {}
        for k, it in ipairs(items) do
            if it.plus then cells[k] = { plus = true }
            else
                local n, ic, b = MR.info(it.abs)
                cells[k] = { n = n or "", i = ic, lk = MR.isDS(n, b) or nil }
            end
        end
        d.cells = cells
    end
    local i = MR.selIndex()
    local lines = {}
    if i then
        local n, ic, b = MR.info(i)
        lines = MR.lines(b)
        local lock = {}
        for k, ln in ipairs(lines) do if ln:find("|", 1, true) then lock[#lock + 1] = k end end
        d.mac = { n = n, i = ic, lines = lines, len = #(b or ""), lock = (#lock > 0) and lock or nil, more = (#lines > MR.LINE_ROWS) or nil,
                  lk = MR.isDS(n, b) or nil }
    end
    if view == "edit" or view == "move" then
        local rows = MR.editRows()
        if rows then MR.efSync(rows); d.ef = st.efk; d.efc = MR.editCell(st.efk) end
        d.del = (st.delAt and GetTime() - st.delAt <= MR.DEL_S) and true or nil
        -- s14 D3: the strips (only here: the grid's preview has the code)
        local order = {}
        for k = 1, math.min(#lines, MR.LINE_ROWS) do order[k] = k end
        if view == "move" and st.mv then
            table.remove(order, st.mv.from)
            table.insert(order, st.mv.at, st.mv.from)
            d.mv = { from = st.mv.from, at = st.mv.at }
            local moved = {}
            for k, o in ipairs(order) do moved[k] = lines[o] end
            for k = #order + 1, #lines do moved[k] = lines[k] end
            d.mac.lines = moved
        end
        local ls = {}
        for k, o in ipairs(order) do ls[k] = MR.strip(MR.parse(lines[o])) end
        d.ls = ls
        if #lines == 0 and not MR.isBlank(d.mac and d.mac.n) then
            local tiles = {}
            for k, t in ipairs(MR.tiles()) do tiles[k] = MR.tileDoc(t) end
            d.tiles = tiles
        end
        local dr = st.draft
        if dr and dr.mac == (d.mac and d.mac.n) then d.dr = { n = dr.idx, at = dr.at } end
        if st.pop then d.pop = MR.popDoc(st.pop) end
        -- s15: Y opens More now (the app's More button carries the Y glyph only then): not on a line (its Move), not in Move
        if view == "edit" and not st.pop and not (st.efk or ""):match("^l%d+$") then d.ym = true end
        -- s16: the line asking (Cancel [B] / Remove [A]); `xr` = the focused line shows X Remove (never in a fight, never on a "|" line)
        if view == "edit" and MR.asking() == "rem" then d.rem = st.remLn end
        local fl = tonumber((st.efk or ""):match("^l(%d+)$") or "")
        if view == "edit" and not st.pop and not MR.asking() and fl and lines[fl] and not lines[fl]:find("|", 1, true) and not combat() then
            d.xr = true
        end
    elseif view == "pre" then
        local tiles = {}
        for k, t in ipairs(MR.tiles()) do tiles[k] = MR.tileDoc(t) end
        d.tiles, d.pcur = tiles, st.pcur
    elseif view == "ps" and st.ps then
        local ps = st.ps
        local plines = MR.presetLines(ps.t, ps.picks)
        local ls = {}
        for k, ln in ipairs(plines) do ls[k] = MR.strip(MR.parse(ln)) end
        local picks = {}
        for k, n in ipairs(ps.picks) do local ic = MR.look("spell", n); picks[k] = { n = n, i = ic, kind = ps.t.p.picks[k] } end
        local body = MR.join(plines)
        d.ps = { k = ps.t.p.k, ls = ls, t = body, len = #body, picks = picks, cur = ps.cur, back = ps.back,
                 arm = (ps.armAt and GetTime() - ps.armAt <= MR.DEL_S) or nil, h = MR.psHard(ps) }
        if st.pop then d.pop = MR.popDoc(st.pop) end
    elseif view == "build" and st.draft then
        d.b = MR.buildDoc(st.draft, lines)
    end
    if st.kbd then d.kbd = { name = st.kbd.name, text = st.kbd.text, max = st.kbd.max, line = st.kbd.line } end
    return d
end
-- a press / tap's answer goes at once; an event's (soon) only when the document changed
function MR.emit(onlyChanged)
    local ok, d = pcall(MR.doc)
    if not ok or type(d) ~= "table" then
        if not ok then st.docErr = tostring(d) end
        return
    end
    local okK, key = pcall(WC.encode, d)
    if onlyChanged and okK and key == MR.lastKey then return end
    MR.lastKey = okK and key or nil
    st.n = st.n + 1
    d.n = st.n
    pcall(function() WC.send(WC.encode({ type = "macros", v = SV, m = d })) end)
    WC.push()
end
MR.queued = false
function MR.soon()
    if MR.queued then return end
    MR.queued = true
    C_Timer.After(0.2, function() MR.queued = false; MR.emit(true) end)
end
function MR.ack(op, ok, why)
    st.ackN = st.ackN + 1
    st.note = (not ok) and why or nil
    pcall(function() WC.send(WC.encode({ type = "macack", v = SV, op = op, ok = ok and true or false, why = why, n = st.ackN })) end)
end

-- ---------------------------------------------------------------- the controller: a guest of the shared capture (Calendar's c14 rules)
function MR.bnextNow()
    if st.mode == "grid" and not st.kbd then return "home" end
    return "back"
end
function MR.sayNext()
    local c = capture()
    local want = MR.bnextNow()
    if c and ctlr.held and (c.version or 1) >= 3 and type(c.bnext) == "function" and ctlr.told ~= want then
        local ok = pcall(c.bnext, MR.GUEST, want)
        if ok then ctlr.told = want end
    end
end
MR.spec = {
    press = function(k) MR.press(k) end,
    lost = function(why)
        ctlr.held = false; ctlr.told = nil
        ctlr.why = plain(why)
        if ctlr.why == "B" or ctlr.why == "taken" then ctlr.want = false end
        if st.mode == "bind" and type(WC.spellsBindHosted) == "table" then pcall(WC.spellsBindHosted.abort, "lost") end
        if st.kbd then MR.kbdClose() end
        if st.mode ~= "grid" then st.mode = "grid" end
        st.pop, st.mv, st.ps = nil, nil, nil
        ctlr.n = ctlr.n + 1
        pcall(function() WC.send(WC.encode({ type = "macpad", v = SV, lost = ctlr.why or "lost", n = ctlr.n })) end)
        MR.soon()
    end,
}
-- s14 D4: Select is the builder's Done: kept from the game while the room holds (capture v4 `extra`; delivered as a press)
MR.EXTRA = { "PADBACK" }
function MR.takePad()
    local c = capture()
    if not c then ctlr.held, ctlr.why = false, "no controller capture" return false end
    -- D2: while the game's own macro window shows, it is hidden by alpha under the room (the capture's work) and this take pre-empts
    -- (capture v9 `window`) until the `wx` cell's secure click closes it
    local spec = { press = MR.spec.press, lost = MR.spec.lost }
    if (c.version or 1) >= 4 then spec.extra = MR.EXTRA end
    if MR.winShown() then spec.hide = { "MacroFrame" }; if (c.version or 1) >= 9 then spec.window = true end end
    local ok, why = c.take(MR.GUEST, spec)
    if not ok and plain(why) == "bad spec" and (spec.hide or spec.window or spec.extra) then
        spec.hide, spec.window, spec.extra = nil, nil, nil
        ok, why = c.take(MR.GUEST, spec)
    end
    ctlr.held = ok and true or false
    ctlr.why = (not ok) and plain(why) or nil
    ctlr.told = nil
    if ok then
        local okS, s = CALL(c.state)
        if okS and type(s) == "table" and s.holder ~= MR.GUEST then
            ctlr.held, ctlr.why = false, combat() and "pending" or "queued"
        end
    end
    if ctlr.held then MR.sayNext() end
    return ctlr.held
end
function MR.freePad(why)
    local c = capture()
    if not c then ctlr.held = false return end
    local okR, done, rwhy = CALL(c.release, MR.GUEST, why)
    if okR and done == false and plain(rwhy) == "tap leave" then ctlr.why = "tap leave" return end
    ctlr.held, ctlr.told = false, nil
end
function MR.syncHeld()
    local c = capture(); if not c then return false end
    local okS, s = CALL(c.state)
    if not (okS and type(s) == "table") then return false end
    local mine = s.holder == MR.GUEST
    if mine == ctlr.held then return false end
    ctlr.held = mine
    if mine then ctlr.why = nil; MR.sayNext() end
    return true
end
function MR.retake()
    if not ctlr.want or ctlr.held or combat() then return end
    local c = capture(); if not c then return end
    local okS, s = CALL(c.state)
    if okS and type(s) == "table" and s.holder ~= nil then return end
    if MR.takePad() then MR.soon() end
end
function MR.hold()
    ctlr.want = true
    if not ctlr.held then
        if st.mode ~= "grid" then st.mode = "grid" end
        st.pop, st.mv, st.ps = nil, nil, nil
        MR.recall()
        MR.takePad()
    end
    MR.emit()
end
-- the room left (the app's `f`, or `fb` for B): whatever was half done goes quietly; nothing is written (the builder's draft is kept)
function MR.free(why)
    ctlr.want = false
    if st.mode == "bind" and type(WC.spellsBindHosted) == "table" then pcall(WC.spellsBindHosted.abort, "left") end
    if st.kbd then MR.kbdClose() end
    st.mode, st.delAt, st.pop, st.mv, st.ps, st.remAt, st.remLn = "grid", nil, nil, nil, nil, nil, nil
    MR.remember()
    MR.freePad(why)
    MR.emit()
end

-- ---------------------------------------------------------------- the keyboard: the chat keyboard's borrowed field
function MR.kbdClose()
    st.kbd = nil
    if type(WC.chatField) == "table" and type(WC.chatField.close) == "function" and WC.chatField.name() and
        WC.chatField.name():sub(1, 3) == "mac" then pcall(WC.chatField.close) end
end
-- name: "macnew" (New), "macname" (rename), "macline" (a typed line: [line] its number, or [at] = a new line there), s14: "macsearch"
-- (the Spell / Item search: nothing written), "macunit" (a unit typed by name for case [line]: nothing written)
function MR.kbdOpen(name, text, max, line, at)
    if type(WC.chatField) ~= "table" or type(WC.chatField.open) ~= "function" then MR.ack(name, false, "no keyboard") return false end
    st.kbd = { name = name, text = text or "", max = max, line = line, at = at, back = st.mode }
    local ok = pcall(WC.chatField.open, { name = name, text = text or "", max = max,
        holds = function() return ctlr.held == true end,
        done = function(typed) return MR.kbdDone(typed) end })
    if not ok then st.kbd = nil; MR.ack(name, false, "no keyboard") return false end
    st.mode = "kbd"
    MR.sayNext()
    return true
end
-- the keyboard's Done (its Send / Start): ONE write, refused (the text kept, the keyboard up) while the guard says no; the search and a
-- typed unit write nothing
function MR.kbdDone(typed)
    local k = st.kbd
    if not k then return false, "no field" end
    if k.name == "macsearch" or k.name == "macunit" then
        local t = MR.trim(type(typed) == "string" and typed:gsub("[\r\n|]", " ") or "")
        if k.name == "macsearch" and st.pop then st.pop.q, st.pop.page, st.pop.cur = (t ~= "" and t or nil), 1, (t ~= "" and "r1" or "q")
        elseif k.name == "macunit" and st.draft and t ~= "" and not t:find("[%[%];,]") then
            local set = st.draft.L.cs[k.line or 1]
            if k.forArg then MR.setArg(st.draft, k.line or 1, t)            -- /target Bob: the command's own unit
            elseif set then set.u = t; set.uf = set.uf or "@"; set.bare = nil end
            st.pop = nil
        end
        st.kbd = nil
        st.mode = (k.back == "kbd") and "build" or k.back
        C_Timer.After(0, function()
            if type(WC.chatField) == "table" and WC.chatField.name() == k.name then pcall(WC.chatField.close) end
            MR.sayNext(); MR.emit()
        end)
        return true
    end
    local why = MR.canWrite()
    if why then MR.ack(k.name, false, why); MR.emit() return false, why end
    local ok, err
    if k.name == "macnew" then ok, err = MR.create(typed)
    elseif k.name == "macname" then ok, err = MR.rename(typed)
    else ok, err = MR.setLine(k.line, k.at, typed) end
    if not ok then MR.ack(k.name, false, err); MR.emit() return false, err end
    MR.ack(k.name, true)
    st.kbd = nil
    st.mode = "edit"                                                     -- New opens the new macro's editor (the game's own order)
    if k.name == "macnew" then st.efk = "name" end
    if k.name == "macline" then
        st.draft, st.pop = nil, nil                                      -- the line is written: its draft (Aa Text's) is done
        local n = k.line or k.at
        if n and #(MR.lines(select(3, MR.info(MR.selIndex() or 0)))) >= n then st.efk = "l" .. n end
    end
    C_Timer.After(0, function()
        if type(WC.chatField) == "table" and WC.chatField.name() == k.name then pcall(WC.chatField.close) end
        MR.sayNext(); MR.emit()
    end)
    return true
end

-- ---------------------------------------------------------------- the writes (each after the guard; each re-finds its macro by name)
function MR.create(raw)
    local name, why = MR.cleanName(raw)
    if not name then return false, why end
    local c = MR.counts()[st.tab]
    if c.n >= (c.max or 0) then return false, "full" end
    local ok, ni = CALL(function() return CreateMacro(name, MR.ICON, "", st.tab == "c") end)
    if not ok or not MR.findAndFocus(name, ni) then return false, "refused" end
    MR.remember()
    return true
end
function MR.rename(raw)
    local i = MR.selIndex()
    if not i then return false, "missing" end
    local old = MR.info(i)
    local name, why = MR.cleanName(raw, i)
    if not name then return false, why end
    if name == old then return true end
    local ok, ni = CALL(function() return EditMacro(i, name) end)
    if not ok or not MR.findAndFocus(name, ni) then return false, "refused" end
    -- our own stored binds follow the name (PadMap m163): this character's for a Character macro, every one for a General macro;
    -- (s10 review) not while another macro still answers to the old name (those binds run THAT one now, as they always found the first)
    if old and MR.named(old) == 0 and type(WC.padMap) == "table" and type(WC.padMap.macroRenamed) == "function" then
        pcall(WC.padMap.macroRenamed, old, name, st.tab == "g")
    end
    MR.remember()
    return true
end
-- s14 THE ONE BODY WRITE: every line change is ONE EditMacro(i, nil, nil, body) (the game's body-only form: the macro stays at [i])
function MR.writeBody(i, name, nb, icon)                                -- s17: [icon] = Blank's default icon in the same write
    local why = MR.canWrite()
    if why then return false, why end
    if type(nb) ~= "string" or #nb > MR.BODY_MAX then return false, "long" end
    local ok = CALL(function() return EditMacro(i, nil, icon, nb) end)
    if not ok or not MR.findAndFocus(name, i) then return false, "refused" end
    return true
end
-- line [line] (1..L) becomes [text] (an emptied line goes); [at] = a NEW line at that place (past the end: the end)
function MR.setLine(line, at, text)
    local i = MR.selIndex()
    if not i then return false, "missing" end
    local name, _, body = MR.info(i)
    local lines = MR.lines(body)
    text = type(text) == "string" and text:gsub("[\r\n]", " ") or ""
    if text:find("|", 1, true) then return false, "bad" end
    if at ~= nil then
        if text == "" then return true end
        table.insert(lines, math.max(1, math.min(at, #lines + 1)), text)
    else
        if not lines[line] then return false, "missing" end
        if lines[line]:find("|", 1, true) then return false, "locked" end
        if text == "" then table.remove(lines, line) else lines[line] = text end
    end
    return MR.writeBody(i, name, MR.join(lines))
end
-- s14: the icon picked (Auto = the question mark: the game shows what the macro casts)
function MR.setIcon(icon)
    local why = MR.canWrite()
    if why then return false, why end
    local i = MR.selIndex()
    if not i then return false, "missing" end
    local name = MR.info(i)
    local ok = CALL(function() return EditMacro(i, nil, icon) end)
    if not ok or not MR.findAndFocus(name, i) then return false, "refused" end
    return true
end
function MR.delete()
    local why = MR.canWrite()
    if why then return false, why end
    local i = MR.selIndex()
    if not i then return false, "missing" end
    local name = MR.info(i)
    local pos = i - MR.base(st.tab)
    local before = MR.tabCount()
    local ok = CALL(function() return DeleteMacro(i) end)
    -- (s10 review) gone = the tab holds one fewer (a twin of the same name may now sit at [i]: that is not a refusal)
    if not ok or MR.tabCount() >= before then return false, "refused" end
    -- our stored binds of it go, unless another macro still answers to that name (they find the first by name: they run that one now)
    if name and MR.named(name) == 0 and type(WC.padMap) == "table" and type(WC.padMap.macroDeleted) == "function" then
        pcall(WC.padMap.macroDeleted, name, st.tab == "g")
    end
    -- the game's own rule (blizzard_macroui.lua DeleteMacro): the selection moves to min(count, old)
    local n = MR.tabCount()
    local np = math.max(1, math.min(n, pos))
    st.page = math.floor((np - 1) / MR.PER_PAGE) + 1
    st.cur = (np - 1) % MR.PER_PAGE
    st.sel = (n > 0) and (MR.base(st.tab) + np) or nil
    st.selName = st.sel and (MR.info(st.sel)) or nil
    if st.draft and st.draft.mac == name then st.draft = nil end
    MR.remember()
    return true
end

-- ---------------------------------------------------------------- s14 the builder's finishing presses
-- Done (Select, the Done chip, a finger's `ok`): the hard faults refuse it (the cursor on the bad chip); a fight refuses it (the draft
-- kept); an unchanged draft writes nothing; else ONE write
function MR.bDone()
    local d = st.draft
    if not d then st.mode = "edit" return end
    local i = MR.selIndex()
    if not i then MR.ack("done", false, "missing") return end
    -- (s14 review) the design's 7: in a fight the chips stay neutral and Done is refused as "combat" (the draft kept), never by a fault
    if combat() then MR.ack("done", false, "combat") return end
    local name, _, body = MR.info(i)
    local lines = MR.lines(body)
    local nb = MR.bodyWith(d, lines)
    local h, hc = MR.hard(d.L, #nb)
    if h then st.bc = hc or st.bc; MR.ack("done", false, h) return end
    local why = MR.canWrite()
    if why then MR.ack("done", false, why) return end
    if d.idx and lines[d.idx] ~= d.orig then MR.ack("done", false, "changed") return end   -- the macro changed elsewhere meanwhile
    if not MR.dirty(d) then
        st.draft, st.pop, st.mode = nil, nil, "edit"
        st.efk = "l" .. d.idx
        MR.ack("done", true)
        return
    end
    local ok, err = MR.writeBody(i, name, nb)
    MR.ack("done", ok, err)
    if not ok then return end
    st.efk = "l" .. (d.idx or math.max(1, math.min(d.at or (#lines + 1), #lines + 1)))
    st.draft, st.pop, st.mode = nil, nil, "edit"
end
-- Remove: a new line's draft goes (nothing written); an edited line's draft is REVERTED to the stored line; an unchanged stored line
-- is removed from the macro by a second A within DEL_S (ONE write)
function MR.bRemove()
    local d = st.draft
    if not d then return end
    if not d.idx then st.draft, st.pop, st.mode = nil, nil, "edit"; st.efk = "add" return end
    if MR.dirty(d) then
        d.L = MR.parse(d.orig)
        st.bc, st.remAt = "more", nil
        return
    end
    if st.remAt and GetTime() - st.remAt <= MR.DEL_S then
        st.remAt = nil
        local i = MR.selIndex()
        if not i then return end
        local name, _, body = MR.info(i)
        local lines = MR.lines(body)
        if lines[d.idx] ~= d.orig then MR.ack("remove", false, "changed") return end
        table.remove(lines, d.idx)
        local ok, err = MR.writeBody(i, name, MR.join(lines))
        MR.ack("remove", ok, err)
        if ok then st.draft, st.pop, st.mode = nil, nil, "edit"; st.efk = "l" .. math.max(1, d.idx - 1) end
        return
    end
    local why = MR.canWrite()
    if why then MR.ack("remove", false, why) return end
    st.remAt = GetTime()
    C_Timer.After(MR.DEL_S + 0.1, function() MR.emit() end)
end
-- s15: More's Aa Text (the line's own text on the keyboard)
function MR.bText()
    local d = st.draft
    if not d then return end
    local i = MR.selIndex()
    local _, _, body = MR.info(i or 0)
    local room = MR.BODY_MAX - #(body or "") + (d.idx and #(d.orig or "") or -1)
    if room < 1 then MR.ack("line", false, "long") return end
    MR.kbdOpen("macline", MR.compose(d.L), math.min(room, 255), d.idx, (not d.idx) and (d.at or 0) or nil)
end
-- s15: the room's More (Y wherever Y has no work of its own, or A on the More button): a one-column pop-out
function MR.moreOpen() st.pop = { k = "more", cur = "r1", page = 1 } end
-- the builder's A on its chip
function MR.bA()
    local d = st.draft
    if not d then st.mode = "edit" return end
    local k = st.bc
    if k ~= "rem" then st.remAt = nil end
    local c = MR.CMD[d.L.c] or MR.CMD.cast
    if k == "cmd" then st.pop = { k = "cmd", cur = "r1", page = 1 }
    elseif k == "arg" then
        if c.arg == "unit" then st.pop = { k = "on", cur = "r1", page = 1, case = 1, forArg = true }
        elseif c.arg == "seq" then st.pop = { k = "seq", cur = "r1", page = 1 }
        else st.pop = { k = "arg", cur = "r1", page = 1, tab = 1, case = 1 } end
    elseif k == "rank" then st.pop = { k = "rank", cur = "r1", page = 1 }
    elseif k == "reset" then st.pop = { k = "reset", cur = "r1", page = 1 }
    elseif k == "keep" then
        local bang, n, sub = MR.castParts(d.L.cs[1].a or "")
        MR.setArg(d, 1, MR.castJoin(not bang, n, sub))
    elseif k == "add" then st.pop = { k = "case", cur = "r1", page = 1 }
    elseif k == "more" then MR.moreOpen()
    elseif k == "text" then MR.bText()
    elseif k == "rem" then MR.bRemove()
    elseif k == "done" then MR.bDone()
    else
        local n, part = tostring(k):match("^c(%d)([ufa])$")
        n = tonumber(n)
        if n and d.L.cs[n] then
            if part == "u" then st.pop = { k = "on", cur = "r1", page = 1, case = n }
            elseif part == "f" then st.pop = { k = "if", cur = "r1", page = 1, case = n }
            elseif c.arg == "unit" then st.pop = { k = "on", cur = "r1", page = 1, case = n, forArg = true }
            else st.pop = { k = "arg", cur = "r1", page = 1, tab = 1, case = n } end
        end
    end
end
-- Y: a case's chip takes its case away (the only one: cleared); the rank goes back to the top, keep-on off, the reset none, the spell gone;
-- s15: anywhere else (the action, + Case, Done, More) Y opens More (Quests' Y)
function MR.bYOwn(k) return tostring(k):match("^c%d[ufa]$") ~= nil or k == "rank" or k == "keep" or k == "reset" or k == "arg" end
function MR.bY()
    local d = st.draft
    if not d then return end
    local k = st.bc
    local n = tonumber(tostring(k):match("^c(%d)[ufa]$") or "")
    if n then
        MR.removeCase(d, n)
        if not MR.bHas(d, st.bc) then st.bc = MR.bHas(d, "c" .. math.max(1, n - 1) .. "u") and ("c" .. math.max(1, n - 1) .. "u") or "cmd" end
    elseif k == "rank" then local bang, nm = MR.castParts(d.L.cs[1].a or ""); MR.setArg(d, 1, MR.castJoin(bang, nm, nil))
    elseif k == "keep" then local _, nm, sub = MR.castParts(d.L.cs[1].a or ""); MR.setArg(d, 1, MR.castJoin(false, nm, sub))
    elseif k == "reset" then local _, list = MR.seqParts(d.L.cs[1].a or ""); MR.setArg(d, 1, MR.seqJoin(nil, list))
    elseif k == "arg" then MR.setArg(d, 1, "")
    else MR.moreOpen() end
end

-- ---------------------------------------------------------------- s14 the pop-outs' presses
function MR.popClose()
    local p = st.pop
    st.pop = p and p.back or nil
end
function MR.popA()
    local p = st.pop
    if not p then return end
    local v = MR.popView(p)
    if p.cur == "q" then MR.kbdOpen("macsearch", p.q or "", 24) return end
    local t = tostring(p.cur):match("^t(%d)$")
    if t then                                                            -- another tab: its whole list (the search starts over)
        p.tab, p.page, p.cur, p.q = tonumber(t), 1, "r1", nil
        if not MR.popView(p).rows[1] then p.cur = "q" end
        return
    end
    local r = tonumber(tostring(p.cur):match("^r(%d+)$") or "")
    local e = r and v.rows[r]
    if not e then return end
    local d = st.draft
    if p.k == "more" then                                                -- s15: the room's More
        if e.off then MR.ack(e.o == "del" and "delete" or "remove", false, "combat") return end
        st.pop = nil
        if e.o == "pre" then st.mode, st.pcur = "pre", 1
        elseif e.o == "ins" then MR.insBelow(e.l)
        elseif e.o == "del" then MR.askDel()
        elseif e.o == "text" then MR.bText()
        elseif e.o == "rem" then MR.bRemove() end
        return
    end
    if p.k == "icon" then
        local ok, why = MR.setIcon(e.i)
        MR.ack("icon", ok, why)
        if ok then st.pop = nil end
        return
    end
    if p.ps then                                                         -- a preset's pick
        if e.t == "s" and st.ps then st.ps.picks[p.ps] = e.n; st.tc = nil end
        st.pop = nil
        return
    end
    if not d then st.pop = nil return end
    if p.k == "cmd" then
        if e.k == "chat" then
            st.pop = nil
            local i = MR.selIndex()
            local _, _, body = MR.info(i or 0)
            local room = MR.BODY_MAX - #(body or "") + (d.idx and #(d.orig or "") or -1)
            -- (s14 review) a stored line's Chat line starts from its own text: an empty keyboard's Done would REMOVE the line ("a line
            -- emptied goes") without Remove's second A
            MR.kbdOpen("macline", d.idx and (d.orig or "") or "", math.max(1, math.min(room, 255)), d.idx, (not d.idx) and (d.at or 0) or nil)
            return
        end
        MR.setCmd(d, e.k)
        st.pop = nil
        local c = MR.CMD[e.k]
        st.bc = c.arg and "arg" or "done"
        if c.arg and not c.opt and MR.trim(d.L.cs[1].a) == "" then MR.bA() end     -- the spell next (D1: the action, then its spell)
        return
    end
    if p.k == "arg" then
        if p.at then                                                     -- a castsequence entry
            local reset, list = MR.seqParts(d.L.cs[1].a or "")
            if e.t == "s" or e.t == "u" or e.t == "e" then list[p.at] = e.n end
            MR.setArg(d, 1, MR.seqJoin(reset, list))
            MR.popClose()
            return
        end
        local c = MR.CMD[d.L.c]
        if e.t == "p" then                                               -- a pet order: the line becomes that order
            MR.setCmd(d, e.c)
            st.pop = nil
            st.bc = "cmd"
            return
        end
        if e.t == "s" and e.id == 6603 then                              -- General's Attack: the game's own /startattack
            MR.setCmd(d, "startattack")
            st.pop, st.bc = nil, "cmd"
            return
        end
        if e.t == "o" then
            if c and c.arg == "eslot" then
                local _, it = MR.eslotParts(d.L.cs[p.case or 1].a or "")
                MR.setArg(d, p.case or 1, MR.trim(e.sl .. " " .. it))
            else MR.setArg(d, p.case or 1, tostring(e.sl)) end
        elseif c and c.arg == "eslot" then
            local sl = MR.eslotParts(d.L.cs[p.case or 1].a or "")
            MR.setArg(d, p.case or 1, MR.trim((sl and (sl .. " ") or "") .. e.n))
        else
            MR.pickSpell(d, p.case or 1, e.n)
        end
        st.pop = nil
        st.bc = (p.case and p.case > 1) and ("c" .. p.case .. "a") or "arg"
        if not MR.bHas(d, st.bc) then st.bc = "done" end
        return
    end
    if p.k == "on" then
        local set = d.L.cs[p.case or 1]
        if not set then st.pop = nil return end
        if e.u == "name" then
            if MR.kbdOpen("macunit", "", 24, p.case or 1) and st.kbd then st.kbd.forArg = p.forArg end   -- /target's own unit, or a case's
            return
        end
        if p.forArg then MR.setArg(d, p.case or 1, e.u)
        elseif e.u == "" then set.u, set.uf = nil, nil
        else set.u = e.u; set.uf = set.uf or "@" end
        if set.u then set.bare = nil end
        st.pop = nil
        return
    end
    if p.k == "if" then                                                  -- cycles and stays open
        local set = d.L.cs[p.case or 1]
        if set then MR.cycleCond(set, e.k); if #set.cn > 0 then set.bare = nil end end
        return
    end
    if p.k == "rank" then
        local bang, n = MR.castParts(d.L.cs[1].a or "")
        MR.setArg(d, 1, MR.castJoin(bang, n, e.sub))
        st.pop = nil
        return
    end
    if p.k == "case" then
        local at = MR.addCase(d, e.o)
        st.pop = nil
        if at then
            st.bc = "c" .. at .. ((e.o == "sp") and "a" or (e.o == "bl" and "f" or "u"))
            if e.o == "sp" then MR.bA() elseif e.o == "bl" then MR.bA() end
        end
        return
    end
    if p.k == "reset" then                                               -- toggles and stays open
        local reset, list = MR.seqParts(d.L.cs[1].a or "")
        MR.setArg(d, 1, MR.seqJoin(MR.resetToggle(reset, e.o), list))
        return
    end
    if p.k == "seq" then
        local _, list = MR.seqParts(d.L.cs[1].a or "")
        st.pop = { k = "arg", cur = "r1", page = 1, tab = 1, at = e.add and (#list + 1) or r, back = p }
        return
    end
end
-- Y in a pop-out: the If grid's cell off; a sequence entry out
function MR.popY()
    local p = st.pop
    local d = st.draft
    if not (p and d) then return end
    local v = MR.popView(p)
    local r = tonumber(tostring(p.cur):match("^r(%d+)$") or "")
    local e = r and v.rows[r]
    if not e then return end
    if p.k == "if" then
        local set = d.L.cs[p.case or 1]
        if set then for i, c in ipairs(set.cn) do if c.k == e.k then table.remove(set.cn, i) break end end end
    elseif p.k == "seq" and not e.add then
        local reset, list = MR.seqParts(d.L.cs[1].a or "")
        table.remove(list, r)
        MR.setArg(d, 1, MR.seqJoin(reset, list))
    end
end
function MR.popMove(key)
    local p = st.pop
    local v = MR.popView(p)
    local rows = MR.popNav(p, v)
    if not p.cur or not MR.popCell(p, p.cur, v) then p.cur = (rows[1] and rows[1][1]) or "r1" end
    local nxt = MR.nav(rows, p.cur, key)
    local r = tonumber(tostring(p.cur):match("^r(%d+)$") or "")
    -- a page's edge: Down past the last row = the next page, Up past the first = the one before
    if nxt == p.cur and r then
        if key == "PADDDOWN" and v.page < v.pages then p.page = v.page + 1; p.cur = "r" .. (((r - 1) % v.cols) + 1); return end
        if key == "PADDUP" and v.page > 1 then p.page = v.page - 1; p.cur = "r1"; return end
    end
    p.cur = nxt
end

-- ---------------------------------------------------------------- s14 presets: the tiles (an empty macro's editor, or Presets), a preview
function MR.psHard(ps)
    if not ps then return nil end
    for k, kind in ipairs(ps.t.p.picks) do
        if kind ~= "harm3" and MR.trim(ps.picks[k]) == "" then return "pick" end
    end
    if #MR.join(MR.presetLines(ps.t, ps.picks)) > MR.BODY_MAX then return "long" end
    return nil
end
-- s17: the macro [name] was started blank (its editor shows no tiles)
function MR.isBlank(name) return name ~= nil and st.blank == name end
-- s17: Blank on an EMPTY macro: nothing to replace, so no preview and no ask; no body write (the icon back to the default when it is not)
function MR.blankNow()
    if combat() then MR.ack("preset", false, "combat") return end
    local why = MR.canWrite()
    if why then MR.ack("preset", false, why) return end
    local i = MR.selIndex()
    if not i then MR.ack("preset", false, "missing") return end
    local name, icon = MR.info(i)
    local ok, err = true, nil
    if icon ~= MR.ICON then ok, err = MR.setIcon(MR.ICON) end
    MR.ack("preset", ok, err)
    if ok then st.blank = name; st.ps, st.pop, st.mode, st.efk, st.draft = nil, nil, "edit", "add", nil end
end
function MR.openPreset(k, back)
    local t = MR.tiles()[k]
    if not t then return end
    if t.p.blank then
        local i = MR.selIndex()
        local body = i and select(3, MR.info(i))
        if MR.trim(body or "") == "" then MR.blankNow() return end
    end
    local picks = {}
    for x, n in ipairs(t.picks) do picks[x] = n end
    st.ps = { t = t, picks = picks, cur = (#t.picks > 0) and "p1" or "done", back = back }
    st.mode, st.pop = "ps", nil
end
function MR.psRows(ps)
    local r1 = {}
    for k = 1, #ps.t.p.picks do r1[#r1 + 1] = "p" .. k end
    if #r1 == 0 then return { { "done" } } end
    return { r1, { "done" } }
end
-- the preset's Done: ONE write of the whole body; a macro with lines is REPLACED only by a second A within DEL_S (D2)
function MR.psDone()
    local ps = st.ps
    if not ps then return end
    if combat() then MR.ack("preset", false, "combat") return end           -- (s14 review) a fight's refusal says so, as Done's
    local h = MR.psHard(ps)
    if h then
        -- (s14 review) the cursor on the FIRST pick still empty (the one that refuses it), not always the first
        local at = 1
        for k, kind in ipairs(ps.t.p.picks) do if kind ~= "harm3" and MR.trim(ps.picks[k]) == "" then at = k break end end
        ps.cur = (h == "pick") and ("p" .. at) or ps.cur
        MR.ack("preset", false, h) return
    end
    local why = MR.canWrite()
    if why then MR.ack("preset", false, why) return end
    local i = MR.selIndex()
    if not i then MR.ack("preset", false, "missing") return end
    local name, _, body = MR.info(i)
    if MR.trim(body) ~= "" and not (ps.armAt and GetTime() - ps.armAt <= MR.DEL_S) then
        ps.armAt = GetTime()
        C_Timer.After(MR.DEL_S + 0.1, function() MR.emit() end)
        return
    end
    ps.armAt = nil
    if ps.t.p.blank then
        -- s17: Blank replaces the lines with none and the icon with the default: ONE EditMacro, the name kept
        local ok, err = MR.writeBody(i, name, "", MR.ICON)
        MR.ack("preset", ok, err)
        if ok then st.blank = name; st.ps, st.pop, st.mode, st.efk, st.draft = nil, nil, "edit", "add", nil end
        return
    end
    local ok, err = MR.writeBody(i, name, MR.join(MR.presetLines(ps.t, ps.picks)))
    MR.ack("preset", ok, err)
    if ok then st.blank = nil; st.ps, st.pop, st.mode, st.efk, st.draft = nil, nil, "edit", "l1", nil end
end
function MR.psA()
    local ps = st.ps
    if not ps then return end
    if ps.cur == "done" then MR.psDone() return end
    local n = tonumber(tostring(ps.cur):match("^p(%d)$") or "")
    if n then ps.armAt = nil; st.pop = { k = "arg", cur = "r1", page = 1, tab = 1, ps = n } end
end

-- ---------------------------------------------------------------- s15 THE ASKS (the user's pick: Cancel / Confirm for delete / replace)
-- More's Delete asks (nothing deleted yet); the ask's A within DEL_S deletes (ONE DeleteMacro), its B or the time out cancels
function MR.askDel()
    local why = MR.canWrite()
    if why then MR.ack("delete", false, why) return end
    st.delAt = GetTime()
    C_Timer.After(MR.DEL_S + 0.1, function() MR.emit() end)
end
function MR.delNow()
    st.delAt = nil
    local ok, why = MR.delete()
    MR.ack("delete", ok, why)
    if ok then st.mode = "grid" end
end
-- s16: the editor's X on line [l] (the user's "X = Remove"): it ASKS - the builder's Remove's ask (Cancel [B] / Remove [A]); nothing written yet
function MR.eAskRem(l, lines)
    if not (l and lines and lines[l]) then return end
    if lines[l]:find("|", 1, true) then MR.ack("remove", false, "locked") return end
    local why = MR.canWrite()
    if why then MR.ack("remove", false, why) return end
    st.remAt, st.remLn, st.remOrig = GetTime(), l, lines[l]
    C_Timer.After(MR.DEL_S + 0.1, function() MR.emit() end)
end
-- the ask's A: ONE EditMacro without that line (the macro changed elsewhere meanwhile: refused); the glow stays in the line's place
function MR.eRemove()
    local l, orig = st.remLn, st.remOrig
    st.remAt, st.remLn, st.remOrig = nil, nil, nil
    local i = MR.selIndex()
    if not (i and l) then return end
    local name, _, body = MR.info(i)
    local lines = MR.lines(body)
    if lines[l] ~= orig then MR.ack("remove", false, "changed") return end
    table.remove(lines, l)
    local ok, err = MR.writeBody(i, name, MR.join(lines))
    MR.ack("remove", ok, err)
    if not ok then return end
    local d = st.draft                                                   -- a draft held for this macro follows its line
    if d and d.mac == name then
        if d.idx == l then st.draft = nil
        elseif d.idx and d.idx > l then d.idx = d.idx - 1
        elseif not d.idx and d.at and d.at > l then d.at = d.at - 1 end
    end
    st.efk = (#lines > 0) and ("l" .. math.min(l, #lines)) or "add"
end
-- s16: More's Insert below (the old X): the builder on a new line under line [l], its Action list open
function MR.insBelow(l)
    local _, lines, _, _, body = MR.editRows()
    if not lines then return end
    if #body + 1 > MR.BODY_MAX then MR.ack("line", false, "long") return end
    MR.openDraft(nil, math.max(1, math.min(tonumber(l) or #lines, #lines)) + 1)
end
-- what is asking now: "del" (the editor's Delete), "rem" (the builder's Remove of a stored line, s16: the editor's X on a line), "rep" (a
-- preset replacing a macro)
function MR.asking()
    local now = GetTime()
    if st.mode == "edit" and st.delAt and now - st.delAt <= MR.DEL_S then return "del" end
    if st.mode == "edit" and st.remLn and st.remAt and now - st.remAt <= MR.DEL_S then return "rem" end
    if st.mode == "build" and st.remAt and now - st.remAt <= MR.DEL_S then return "rem" end
    if st.mode == "ps" and st.ps and st.ps.armAt and now - st.ps.armAt <= MR.DEL_S then return "rep" end
end
function MR.askNo() st.delAt, st.remAt, st.remLn, st.remOrig = nil, nil, nil, nil; if st.ps then st.ps.armAt = nil end end

-- ---------------------------------------------------------------- the actions (a press or a finger's a / x / b / y / ok)
function MR.enterEdit()
    local i = MR.selIndex()
    if not i then MR.ack("edit", false, "missing") return end
    if MR.locked(i) then MR.ack("edit", false, "locked") return end           -- s14 D6: the companion's own: view only
    st.mode, st.efk, st.delAt, st.pop, st.eln, st.remLn = "edit", "name", nil, nil, nil, nil
end
function MR.bind()
    local i = MR.selIndex()
    if not i then MR.ack("bind", false, "missing") return end
    if MR.locked(i) then MR.ack("bind", false, "locked") return end
    if combat() then MR.ack("bind", false, "combat") return end
    if type(WC.spellsBindMacro) ~= "function" then MR.ack("bind", false, "not ready") return end
    local name = MR.info(i)
    if not name then MR.ack("bind", false, "missing") return end
    -- (s10 review) our buttons (the bar's read back, a stick, the fight layer) find a macro BY NAME, the first of it: a name two macros
    -- share would put the wrong one there, so such a bind is refused until one is renamed
    if MR.named(name) > 1 then MR.ack("bind", false, "taken") return end
    st.mode = "bind"
    local ok, opened, why = pcall(WC.spellsBindMacro, name, MR.host, i)
    if not (ok and opened) then st.mode = "grid"; MR.ack("bind", false, (ok and plain(why)) or "not ready") return end
    MR.sayNext()
end
MR.host = {
    name = "macros",
    -- s14: the box's own extra keys (Select / L3 / R3 while it is up) and the room's Select (the builder's Done) together
    extra = function(list)
        local c = capture()
        if not (c and type(c.extra) == "function" and (tonumber(c.version) or 1) >= 4) then return false end
        local all, seen = {}, {}
        for _, k in ipairs(list or {}) do if not seen[k] then seen[k] = true; all[#all + 1] = k end end
        for _, k in ipairs(MR.EXTRA) do if not seen[k] then seen[k] = true; all[#all + 1] = k end end
        local ok, got = pcall(c.extra, MR.GUEST, all)
        return ok and got and true or false
    end,
    done = function(note)
        if st.mode == "bind" then st.mode = "grid" end
        MR.sayNext()
        MR.emit()
    end,
}
function MR.gridA()
    local items = MR.focusGrid()
    if st.cur == -1 or st.cur == -2 then
        local t = st.cur == -1 and "g" or "c"
        if t ~= st.tab then st.tab, st.page, st.cur, st.sel, st.selName = t, 1, 0, nil, nil end
        st.cur = 0
        MR.remember()
        return
    end
    local it = items[st.cur + 1]
    if not it then return end
    if it.plus then
        local why = MR.canWrite()
        if why == "off" or why == "loading" then MR.ack("new", false, why) return end
        MR.kbdOpen("macnew", "", MR.NAME_MAX)
        return
    end
    MR.enterEdit()
end
-- the editor's A (on a line: the builder; a text line: its keyboard), X (s16: Remove that line, after the ask), Y (Move)
function MR.editA(x, y)
    local rows, lines, name, _, body = MR.editRows()
    if not rows then st.mode = "grid" return end
    local r = MR.efSync(rows)
    if r ~= "del" then st.delAt = nil end
    local l = r and tonumber(r:match("^l(%d+)$"))
    local t = r and tonumber(r:match("^t(%d+)$"))
    if y then
        if l and lines[l] then st.mv = { from = l, at = l }; st.mode = "move"
        else MR.moreOpen() end                                           -- s15: Y = More where Y has no work of its own
        return
    end
    if x then                                                            -- s16: X = Remove (a line only: its hint shows there)
        if l and lines[l] then MR.eAskRem(l, lines) end
        return
    end
    if r == "name" then MR.kbdOpen("macname", name or "", MR.NAME_MAX)
    elseif r == "icon" then st.pop = { k = "icon", cur = "r1", page = 1 }
    elseif l then
        if lines[l]:find("|", 1, true) then MR.ack("line", false, "locked") return end
        local ok, kind = MR.openDraft(l)
        if not ok and (kind == "text" or kind == "empty") then
            MR.kbdOpen("macline", lines[l], math.min(255, MR.BODY_MAX - #body + #lines[l]), l, nil)
        end
    elseif t then MR.openPreset(t, "edit")
    elseif r == "add" then
        if #body + ((#body > 0) and 1 or 0) >= MR.BODY_MAX then MR.ack("line", false, "long") return end
        MR.openDraft(nil, #lines + 1)
    elseif r == "more" then MR.moreOpen() end
end
-- Move's drop: ONE write when the line went somewhere else
function MR.moveDrop()
    local mv = st.mv
    st.mode, st.mv = "edit", nil
    if not mv or mv.at == mv.from then return end
    local i = MR.selIndex()
    if not i then return end
    local name, _, body = MR.info(i)
    local lines = MR.lines(body)
    local ln = table.remove(lines, mv.from)
    if not ln then return end
    table.insert(lines, mv.at, ln)
    local ok, err = MR.writeBody(i, name, MR.join(lines))
    MR.ack("move", ok, err)
    if ok then st.efk = "l" .. mv.at; st.draft = nil else st.mode, st.mv = "move", mv end
end
function MR.back()
    if st.pop then MR.popClose() return end
    if st.mode == "build" then st.mode, st.remAt = "edit", nil                -- the draft is KEPT (B in the builder)
        if st.draft then st.efk = st.draft.idx and ("l" .. st.draft.idx) or "add" end
    elseif st.mode == "move" then st.mode, st.mv = "edit", nil              -- nothing written
    elseif st.mode == "ps" then st.mode = (st.ps and st.ps.back == "pre") and "pre" or "edit"; st.ps = nil
    elseif st.mode == "pre" then st.mode = "edit"
    elseif st.mode == "edit" then st.mode, st.delAt, st.remLn = "grid", nil, nil
    elseif st.mode == "grid" then
        -- the room's top: B goes Home (the app's route); the controller goes back with "B"
        ctlr.want = false
        MR.remember()
        MR.freePad("B")
        ctlr.n = ctlr.n + 1
        pcall(function() WC.send(WC.encode({ type = "macpad", v = SV, k = "BACK", n = ctlr.n })) end)
    end
end

-- ---------------------------------------------------------------- the presses (the capture's eight and Select; the Bind box's own while it is up)
function MR.move(key)
    if st.pop then MR.popMove(key) return end
    if st.mode == "grid" then
        local items = MR.focusGrid()
        local c = st.cur
        local n = #items
        if c == -1 or c == -2 then
            if key == "PADDLEFT" then st.cur = -1 elseif key == "PADDRIGHT" then st.cur = -2
            elseif key == "PADDDOWN" and n > 0 then st.cur = 0 end
            return
        end
        if key == "PADDLEFT" then if c % 6 > 0 then st.cur = c - 1 end
        elseif key == "PADDRIGHT" then if c % 6 < 5 and c + 1 < n then st.cur = c + 1 end
        elseif key == "PADDUP" then
            if c >= 6 then st.cur = c - 6
            elseif st.page > 1 then st.page = st.page - 1; st.cur = 18 + c
            else st.cur = (st.tab == "g") and -1 or -2 end
        elseif key == "PADDDOWN" then
            if c + 6 < n then st.cur = c + 6
            elseif c < 18 and math.floor(c / 6) < math.floor((n - 1) / 6) then st.cur = n - 1
            else
                local _, pages = MR.pageItems()
                if st.page < pages then st.page = st.page + 1; st.cur = c % 6; MR.focusGrid() end
            end
        end
        MR.focusGrid()
    elseif st.mode == "edit" then
        local rows = MR.editRows()
        if not rows then return end
        st.efk = MR.nav(MR.editNav(rows), MR.efSync(rows), key)
        MR.efSync(rows)
        if st.efk ~= "del" then st.delAt = nil end
    elseif st.mode == "move" and st.mv then
        local _, lines = MR.editRows()
        local n = math.min(#(lines or {}), MR.LINE_ROWS)
        if key == "PADDUP" then st.mv.at = math.max(1, st.mv.at - 1)
        elseif key == "PADDDOWN" then st.mv.at = math.min(n, st.mv.at + 1) end
    elseif st.mode == "pre" then
        local rows, row = {}, nil
        for k = 1, #MR.tiles() do
            if not row or #row >= 4 then row = {}; rows[#rows + 1] = row end
            row[#row + 1] = k
        end
        local cur = math.max(1, math.min(st.pcur or 1, #MR.tiles()))
        st.pcur = MR.nav(rows, cur, key)
    elseif st.mode == "ps" and st.ps then
        st.ps.cur = MR.nav(MR.psRows(st.ps), st.ps.cur, key)
        st.ps.armAt = nil
    elseif st.mode == "build" and st.draft then
        local rows = MR.bRows(st.draft)
        if not MR.bHas(st.draft, st.bc) then st.bc = "cmd" end
        st.bc = MR.nav(rows, st.bc, key)
        if st.bc ~= "rem" then st.remAt = nil end
    end
end
function MR.press(k)
    if not ctlr.held then ctlr.held, ctlr.why = true, nil end            -- a press proves the hold (a take made real by Home's ENTER)
    return MR.act(k)
end
-- (s10 review) the press's work, for the capture's press AND a finger's a / x / b / y / ok: a TAP proves no hold (the take may still be
-- queued behind an NPC window, or pending in a fight), so only the capture's own press says held
function MR.act(k)
    k = plain(k)
    if not k then return end
    if st.mode == "bind" then
        local hb = WC.spellsBindHosted
        local ok, took = false, false
        if type(hb) == "table" and type(hb.press) == "function" then ok, took = pcall(hb.press, k) end
        if not (ok and took) then st.mode = "grid"; MR.emit() end
        return
    end
    if st.mode == "kbd" then return end                                  -- the app's own reader drives the keyboard meanwhile
    st.note = nil
    local ask = MR.asking()
    if ask then                                                          -- s15: Cancel [B] / Confirm [A] (Quests' confirm)
        if k == "PAD1" then
            if ask == "del" then MR.delNow()
            elseif ask == "rem" then if st.mode == "edit" then MR.eRemove() else MR.bRemove() end
            else MR.psDone() end
        elseif k == "PAD2" then MR.askNo() end
        MR.sayNext()
        MR.emit()
        return
    end
    if k == "PADDUP" or k == "PADDDOWN" or k == "PADDLEFT" or k == "PADDRIGHT" then MR.move(k)
    elseif k == "PAD1" then
        if st.pop then MR.popA()
        elseif st.mode == "grid" then MR.gridA() elseif st.mode == "edit" then MR.editA(false)
        elseif st.mode == "build" then MR.bA() elseif st.mode == "move" then MR.moveDrop()
        elseif st.mode == "pre" then MR.openPreset(st.pcur or 1, "pre") elseif st.mode == "ps" then MR.psA() end
    elseif k == "PAD3" then
        if st.pop then
            -- nothing: X adds in the builder itself
        elseif st.mode == "grid" then
            local items = MR.focusGrid()
            local it = type(st.cur) == "number" and st.cur >= 0 and items[st.cur + 1] or nil
            if it and it.abs then MR.bind() end
        elseif st.mode == "edit" then MR.editA(true)
        elseif st.mode == "build" and st.draft and MR.bHas(st.draft, "add") then st.bc = "add"; MR.bA() end
    elseif k == "PAD4" then
        if st.pop then MR.popY()
        elseif st.mode == "edit" then MR.editA(false, true)
        elseif st.mode == "build" then MR.bY() end
    elseif k == "PADBACK" then                                           -- s14 D4: Select = Done
        if st.mode == "build" then st.pop = nil; MR.bDone()
        elseif st.mode == "ps" then st.pop = nil; MR.psDone() end
    elseif k == "PAD2" then MR.back() end
    MR.sayNext()
    MR.emit()
end

-- ---------------------------------------------------------------- the row's taps (a finger; no game action of their own but `wx`)
-- s14: the s<k> of a builder chip
function MR.bFromCell(sk)
    local d = st.draft
    if not d then return nil end
    for _, row in ipairs(MR.bRows(d)) do for _, k in ipairs(row) do if MR.bcell(k) == sk then return k end end end
end
function MR.onCell(col)
    local key = MR.KEY[col]
    local sk = (col >= MR.S0 and col < MR.S0 + MR.PER_PAGE) and (col - MR.S0) or nil
    local ok = pcall(function()
        if key == "h" then return MR.hold() end
        if key == "f" then return MR.free() end
        if key == "fb" then return MR.free("B") end
        if key == "wx" then                                              -- the secure click closed the game's window
            -- (s10 review) a cell not armed yet (no close button when it was made) clicked nothing: its ack says so
            local btn = MR.cells and MR.cells[MR.COL.wx]
            st.winAt = GetTime(); MR.ack("wx", (btn and btn.wcArmed) and true or false, (not (btn and btn.wcArmed)) and "not ready" or nil)
            return MR.emit()
        end
        if key == "kx" then                                              -- the app's keyboard closed (B / its close): nothing written
            local back = st.kbd and st.kbd.back or "grid"
            MR.kbdClose()
            st.mode = (back == "kbd") and "grid" or back
            MR.sayNext()
            return MR.emit()
        end
        if st.mode == "kbd" then return end
        if key == "a" then return MR.act("PAD1") end
        if key == "x" then return MR.act("PAD3") end
        if key == "b" then return MR.act("PAD2") end
        if key == "y" then return MR.act("PAD4") end
        if key == "ok" then return MR.act("PADBACK") end
        if key == "tg" or key == "tc" then
            if st.mode ~= "grid" then return end
            local t = key == "tg" and "g" or "c"
            if t ~= st.tab then st.tab, st.page, st.cur, st.sel, st.selName = t, 1, 0, nil, nil; MR.remember() end
            return MR.emit()
        end
        if key == "pp" or key == "pn" then
            local d = key == "pp" and -1 or 1
            if st.pop then
                local v = MR.popView(st.pop)
                st.pop.page = math.max(1, math.min(v.pages, v.page + d)); st.pop.cur = "r1"
            elseif st.mode == "grid" then
                local _, pages = MR.pageItems()
                st.page = math.max(1, math.min(pages, st.page + d)); st.cur = 0; MR.focusGrid()
            end
            return MR.emit()
        end
        if sk then
            st.note = nil
            if MR.asking() then MR.askNo() end                           -- s15: a finger elsewhere cancels the ask (then acts there)
            if st.pop then
                local v = MR.popView(st.pop)
                local c = MR.popFromCell(st.pop, sk, v)
                if c then st.pop.cur = c end
            elseif st.mode == "grid" then
                local items = MR.pageItems()
                if items[sk + 1] then st.cur = sk; MR.focusGrid() end
            elseif st.mode == "edit" then
                local rows = MR.editRows()
                local r = rows and MR.editFromCell(sk, rows)
                if r then st.efk = r; MR.efSync(rows) end
                if r ~= "del" then st.delAt = nil end
            elseif st.mode == "move" and st.mv then
                local _, lines = MR.editRows()
                if sk >= 2 and sk - 1 <= math.min(#(lines or {}), MR.LINE_ROWS) then st.mv.at = sk - 1 end
            elseif st.mode == "pre" then
                if MR.tiles()[sk + 1] then st.pcur = sk + 1 end
            elseif st.mode == "ps" and st.ps then
                if sk == 3 then st.ps.cur = "done" elseif st.ps.t.p.picks[sk + 1] then st.ps.cur = "p" .. (sk + 1) end
            elseif st.mode == "build" then
                local c = MR.bFromCell(sk)
                if c then st.bc = c end
                if c ~= "rem" then st.remAt = nil end
            end
            return MR.emit()
        end
    end)
    if not ok then MR.ack(key or "?", false, "failed") end
end

-- the `wx` cell: the game's own close button while its window exists (static: set out of combat; a click on a hidden window does nothing)
function MR.cellInit(btn, col)
    MR.cells = MR.cells or {}
    MR.cells[col] = btn
    if col == MR.COL.wx then
        local cb = rawget(_G, MR.CLOSE)
        if type(cb) == "table" then btn:SetAttribute("type", "click"); btn:SetAttribute("clickbutton", cb)
        else btn:SetAttribute("type", nil) end
        btn.wcArmed = type(cb) == "table"
    else
        btn:SetAttribute("type", nil)
    end
end
function MR.armClose()
    local btn = MR.cells and MR.cells[MR.COL.wx]
    if not btn or btn.wcArmed or combat() then return end
    pcall(MR.cellInit, btn, MR.COL.wx)
end
-- THE LAZY ROW (out of combat; with none free the room says so): Book's padtab pick 39 then 34, or the game's window opening while
-- the room wants the controller. s12: made as the Calendar row's GUEST (MR.SHARE, TapPad pad21) and LEASED to this room on each ask -
-- the app asks on every showing (the document's `sh`) - so the room's row costs no row of the pad; the Calendar takes it back by its
-- own ask (padtab 39 then 35, Book b65). In a fight a made row's lease waits for the fight's end; a row not made yet waits as before.
function MR.ensure()
    local pad = WC.pad
    if not (pad and type(pad.secureRow) == "function") then st.norow = true return false end
    local made = pad.rows and pad.rows[MR.ROW]
    if made and MR.onRow() then st.norow = nil; MR.emit() return true end
    if combat() then
        if made and type(pad.lease) == "function" then pcall(pad.lease, MR.ROW) end
        MR.emit() return false
    end
    if not made then
        local ok, row = pcall(pad.secureRow, MR.ROW, MR.NCOLS, MR.cellInit, MR.onCell, { onPress = true, share = MR.SHARE })
        if not (ok and type(row) == "number") then st.norow = true; MR.emit() return false end
    end
    if type(pad.lease) == "function" then pcall(pad.lease, MR.ROW) end
    st.norow = (not MR.onRow()) or nil
    MR.emit()
    return not st.norow
end

-- ---------------------------------------------------------------- the game's own macro window (D2): a post-hook only looks
MR.hooked = false
function MR.hookWindow()
    if MR.hooked then return end
    local fr = rawget(_G, "MacroFrame")
    if type(fr) ~= "table" or type(fr.HookScript) ~= "function" then return end
    MR.hooked = true
    pcall(function()
        -- s12: the row only while the room wants the controller (a shared row is the Calendar's otherwise: never taken by the game's
        -- window alone)
        fr:HookScript("OnShow", function() C_Timer.After(0, function() MR.armClose(); if ctlr.want then MR.ensure() end; MR.emit() end) end)
        fr:HookScript("OnHide", function() C_Timer.After(0, function() MR.emit() end) end)
    end)
end

WC.macroRoom = {
    version = SV,
    ensure = function() return MR.ensure() end,
    doc = function() return MR.doc() end,
    emit = function() return MR.emit() end,
    parse = function(t) return MR.parse(t) end,                          -- s14: the one grammar (read only)
    compose = function(L) return MR.compose(L) end,
    _mr = MR,                                                            -- the harness
}

local ev = CreateFrame("Frame")
for _, e in ipairs({ "UPDATE_MACROS", "PLAYER_ENTERING_WORLD", "PLAYER_REGEN_ENABLED", "PLAYER_REGEN_DISABLED", "ADDON_LOADED",
                     "SPELLS_CHANGED", "LEARNED_SPELL_IN_SKILL_LINE" }) do
    pcall(function() ev:RegisterEvent(e) end)
end
ev:SetScript("OnEvent", function(_, e, a1)
    if e == "UPDATE_MACROS" then st.loaded = true
    elseif e == "SPELLS_CHANGED" or e == "LEARNED_SPELL_IN_SKILL_LINE" then st.rk, st.tc, st.bk = nil, nil, nil   -- s14: read again
    elseif e == "ADDON_LOADED" then
        if a1 ~= "Blizzard_MacroUI" then return end
        C_Timer.After(0, function() MR.hookWindow(); MR.armClose() end)
    elseif e == "PLAYER_REGEN_ENABLED" then
        MR.armClose()
        MR.syncHeld()
        if not ctlr.want and ctlr.held then MR.freePad() end
        MR.retake()
    elseif e == "PLAYER_ENTERING_WORLD" then
        MR.hookWindow()
    end
    if e ~= "SPELLS_CHANGED" or st.mode ~= "grid" then MR.soon() end
end)
MR.hookWindow()
if type(WC.pad) == "table" and type(WC.pad.onLayout) == "function" then pcall(WC.pad.onLayout, function() if WC.pad.rows[MR.ROW] then MR.soon() end end) end
if type(WC.snapshot) == "function" then
    hooksecurefunc(WC, "snapshot", function(reason)
        if reason == "heartbeat" or reason == "login" then MR.soon() end
        MR.retake()
    end)
end
end
-- ==================================================================== end of the Macros room =====
