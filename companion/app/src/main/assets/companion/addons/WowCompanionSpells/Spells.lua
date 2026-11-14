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

local SV = "s11"     -- bump on every change; s11 THE 1.0.32 TEST BUILD MERGE (code 1472): feat/macro-room and feat/pet-spell-binding both tagged s10, both kept (The Macros room = s10, Pet spells = s10p, its reviews = s10a / s10b; the pet branch's Book b63 / PadMap m163 are b63p / m163p in its notes); s10 THE MACROS ROOM (the user, 2026-11-13: "Lets scrap the smart ranged attack and start building a macro room. The calender can be moved up to a button on the right side of the map name make it small. Use the room slot for macros."): the block at the end of this file (WC.macroRoom: the "macros" document, the controller guest "macros", the lazy pad row "mac", the player's own New / Done / Delete, the Bind box for a macro); s10 REVIEW (same branch, same tag, as eaa3d23c did; the user's "start building a macro room"): the selection is the macro's INDEX while it still holds its name (the game lets two macros share a name: A / Delete / the preview hit the one under the glow), a write re-finds its macro by the game's own returned index; a bind of a name another macro shares is refused ("taken": our buttons find a macro by name); "#showtooltip" goes on the macro's FIRST line; `wxa` = the `wx` cell really is the window's close button (its ack says so); `norow` also when the row exists but is not placed (the smallest UI scale); our stored binds follow a rename / go with a delete only when no other macro keeps that name; a finger's a / x / b no longer claims the controller (only the capture's own press proves a hold); s10b (review of feat/pet-spell-binding, #234, the user: "build it"): a pet spell is PASSIVE as the game's own Spellbook says (its book item's isPassive / IsSpellBookItemPassive, the spell's own IsSpellPassive), never by the pet bar's state - the server packs every spell that cannot auto-cast with state 1, so the high-byte-1 / IsPetActionPassive readings made Seduction, Spell Lock and Sacrifice "passive": no number, no Bind, hidden with Hide Passives; s10a (review of feat/pet-spell-binding, #234, the user: "build it"): only a pet WITH A BOOK (the game's own Pet tab: HasPetSpells() and PetHasSpellbook()) has spells of ours - a charmed / possessed unit (Mind Control, Enslave) has pet spells but no book, so its spells are never kept as this character's pet spells (WC.spellsPetName), never numbered, and a travelling pet slot reads nothing then (WC.spellsPetAt / WC.spellsPetSlotOf): a mob's spell sharing an id with one of the player's own never turns that spell into a "pet spell" cast by bare name; s10p PET SPELLS (#234, the user: "build it"): the pet book's SPELLS get numbers (`pv`, after the player's top ranks, before the lower ranks), never its passives or its commands (Attack / Follow / Stay ... have their homes); each pet item says `cmd` (a command), `bind` (a spell that can go on a button) and `pb` (on the pet bar), its bars are the game's own FindPetActionButtons; the Pet tab is line WC.SPELLS_PET_LINE (101); a pet book slot k travels as WC.SPELLS_PET_SLOT0 + k (5000 + k) through the Bind box and the Controller room's puts (WC.spellsPetAt / WC.spellsBankSlot read it back); WC.spellsPetName(id) = the name our own secure buttons cast a pet spell by (CastSpellByName, the game's /cast), remembered per character (WowCompanionSpellsDB.petSpellBy[name-realm]); s9 RANK UP REVIEW (the user, 2026-11-12: "I know we have something in place to rank up abilities on login, but maybe for skills that are already on a bar a prompt to allow them to be ranked up when a new rank is learned is available?"): WC.spellsNewsHas(id) = that spell was learned this load (PadMap m144: rankSync leaves an entry for the rank-up prompt only then); s8 RANK UP (the user, 2026-11-12: "I know we have something in place to rank up abilities on login, but maybe for skills that are already on a bar a prompt to allow them to be ranked up when a new rank is learned is available?"): `rk` = for each just-learned spell that is the top rank now, how many lower copies are on buttons (PadMap m143 WC.rankUpList: bars, radial, In-combat), so Home can ask; s7 s7: loose fly-out members (aspects) numbered; the app shows it, so a stale build is visible

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
    local who = TS(function() return UnitName("player") end)
    if not who then return nil end
    who = who .. "-" .. (TS(function() return GetRealmName() end) or "?")
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
--   D4 a line typed (the chat keyboard's borrowed field, Chat.lua chat22: one line at a time, the keyboard has no "|" and no new line),
--      or a spell / bag item picked and its line written (/cast, /use, /equip: chatframeutiloverrides.lua), plus six RECIPE tiles made
--      from one or two picks (mouseover heal, focus, self cast, Shift pair, start attack, trinket); a recipe ADDS its lines;
--   D5 the game's own action bar only by the slot the player presses in the Bind box's Normal tab; B undoes it (Book's keep / undo).
-- THE ONE GUARD (canWrite) runs right before every write and refuses, with its reason and no call: in the lockdown ("combat"); while the
-- game's own macro window shows ("window": its close saves its text over ours, blizzard_macroui.lua SaveMacro); before the game has
-- loaded the macros ("loading": every read finds none until UPDATE_MACROS, PadMap m160's lesson); while the game rule turns macros off
-- ("off"). New = CreateMacro(name, 134400, "", tab == the character's) (iconselector.lua's own call); a name's Done = EditMacro(i, name);
-- a line's Done = EditMacro(i, nil, nil, body) (the game's body-only form, blizzard_macroui.lua:366); Delete = a second A within DEL_S
-- (DeleteMacro). Nothing is written on B, on leaving, or by itself. The game's macro window is never opened; no Blizzard UI Lua runs.
-- THE STATE IS THE ADD-ON'S, as the Spellbook's and Bags' are: the capture hands the presses here and the app draws the "macros"
-- document; a finger taps the k-th thing on show (s<k>) or A / X / B (a / x / b). THE PAD ROW "mac" IS LAZY (the pad is full at the
-- fullest layout): made when the room first opens (Book's padtab pick 39 then 34, or the game's window opening), out of combat; with
-- no free row the document says `norow`. Every value goes through S()/SN(); a macro's name and body travel only when plain.
-- Per character (the user's per-character rule): WowCompanionSpellsDB.macroRoom[name-realm] = { tab, sel } (where the room was).
do
local MR = { GUEST = "macros", ROW = "mac", DEL_S = 3, ICON = 134400, NAME_MAX = 16, BODY_MAX = 255, PER_PAGE = 24, PICK_PAGE = 10,
             LINE_ROWS = 19, CLOSE = "MacroFrameCloseButton" }
-- the row's columns, FIXED: 0-11 the room's keys, then s0..s23 = the k-th thing on show (a grid cell, an editor row, a pick row, a tile)
MR.KEYS = { "h", "f", "fb", "a", "x", "b", "tg", "tc", "pp", "pn", "kx", "wx" }
MR.COL, MR.KEY = {}, {}
for i, k in ipairs(MR.KEYS) do MR.COL[k] = i - 1; MR.KEY[i - 1] = k end
MR.S0 = #MR.KEYS
MR.NCOLS = MR.S0 + MR.PER_PAGE
-- the recipes (D4): id, how many spell picks, the lines from the picks (a, b); "#showtooltip" first unless the body has one already
MR.RECIPES = {
    { id = "mo", n = 1, f = function(a) return { "/cast [@mouseover,help,nodead][] " .. a } end },
    { id = "fo", n = 1, f = function(a) return { "/cast [@focus,exists,nodead][] " .. a } end },
    { id = "me", n = 1, f = function(a) return { "/cast [@player] " .. a } end },
    { id = "sh", n = 2, f = function(a, b) return { "/cast [mod:shift] " .. b .. "; " .. a } end },
    { id = "at", n = 1, f = function(a) return { "/startattack", "/cast " .. a } end },
    { id = "tr", n = 1, f = function(a) return { "/use 13", "/use 14", "/cast " .. a } end },
}
local st = { tab = "g", page = 1, cur = 0, mode = "grid", sel = nil, selName = nil, ef = 0, efk = "name", pick = nil, rec = nil, delAt = nil,
             note = nil, kbd = nil, n = 0, loaded = false, winAt = -100, ackN = 0 }
local ctlr = { want = false, held = false, why = nil, n = 0, bnext = "home", told = nil }
MR.st, MR.ctlr = st, ctlr

local function T(fn) local v = S(fn); if v ~= WC.ABSENT and v ~= WC.SECRET and v ~= WC.THREW then return v end end
local function TNn(fn) local v = T(fn); if type(v) == "number" then return v end end
local function TSs(fn) local v = T(fn); if type(v) == "string" and WC.isPlain(v) then return v end end
local function plain(v) if type(v) == "string" and WC.isPlain(v) then return v end end
local function combat() return S(function() return InCombatLockdown() end) == true end
local function who()
    local n, r = TSs(function() return UnitName("player") end), TSs(function() return GetRealmName() end)
    if n and r then return n .. "-" .. r end
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

-- ---------------------------------------------------------------- the editor's rows: 0 the name, 1..L the lines, then the four buttons
MR.BTN = { "add", "pick", "rec", "del" }
function MR.editRows()
    local i = MR.selIndex()
    if not i then return nil end
    local name, icon, body = MR.info(i)
    local lines = MR.lines(body)
    local rows = { "name" }
    for k = 1, math.min(#lines, MR.LINE_ROWS) do rows[#rows + 1] = "l" .. k end
    for _, b in ipairs(MR.BTN) do rows[#rows + 1] = b end
    return rows, lines, name, icon, body or "", i
end
-- the s<k> cell of a thing in edit mode: s0 = the name, s1..s19 = the lines, s20..s23 = the buttons
function MR.editCell(row)
    if row == "name" then return 0 end
    local l = type(row) == "string" and tonumber(row:match("^l(%d+)$"))
    if l then return l end
    for k, b in ipairs(MR.BTN) do if b == row then return MR.LINE_ROWS + k end end
end
-- the editor's glow is a ROW KEY ("name", "l3", "add", ...): a write that adds or drops lines keeps it on the same thing (a line gone:
-- the last line, or + line); st.ef is its place in [rows]
function MR.efSync(rows)
    local k = st.efk or "name"
    for i, r in ipairs(rows) do if r == k then st.ef = i - 1 return k end end
    if k:match("^l%d+$") then
        local last = nil
        for i, r in ipairs(rows) do if r:match("^l%d+$") then last = i end end
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

-- ---------------------------------------------------------------- the pick list (D4): the book's spells, then bag items with a use
function MR.pickList(spellsOnly)
    local out, seen = {}, {}
    if type(WC.spellsBindableList) == "function" then
        local ok, list = pcall(WC.spellsBindableList)
        for _, e in ipairs(ok and type(list) == "table" and list or {}) do
            local id = e.id
            local n = type(id) == "number" and TSs(function() return C_Spell.GetSpellName(id) end)
            if n and not seen["s" .. n] and not n:find("[|;]") then
                seen["s" .. n] = true
                out[#out + 1] = { t = "s", n = n, i = TNn(function() return C_Spell.GetSpellTexture(id) end) }
            end
            if #out >= 120 then break end
        end
    end
    if spellsOnly then return out end
    for bag = 0, 4 do
        local slots = TNn(function() return C_Container.GetContainerNumSlots(bag) end) or 0
        for slot = 1, math.min(slots, 40) do
            local id = TNn(function() return C_Container.GetContainerItemID(bag, slot) end)
            if id and not seen["i" .. id] then
                seen["i" .. id] = true
                local n = TSs(function() return C_Item.GetItemNameByID(id) end)
                local use = S(function() return (C_Item.GetItemSpell(id)) end)
                local kind = (use ~= nil and use ~= WC.ABSENT and use ~= WC.THREW and use ~= WC.SECRET) and "u" or nil
                if not kind and S(function() return C_Item.IsEquippableItem(id) end) == true then kind = "e" end
                if n and kind and not n:find("[|;]") then
                    out[#out + 1] = { t = kind, n = n, i = TNn(function() return C_Item.GetItemIconByID(id) end) }
                end
            end
            if #out >= 160 then return out end
        end
    end
    return out
end
function MR.lineFor(p)
    if p.t == "s" then return "/cast " .. p.n elseif p.t == "u" then return "/use " .. p.n end
    return "/equip " .. p.n
end

-- ---------------------------------------------------------------- the document
local capture = function()
    local c = WC.capture
    if type(c) == "table" and (c.version or 0) >= 1 and type(c.take) == "function" and type(c.release) == "function" then return c end
end
function MR.keys()
    local pad = WC.pad
    local r = pad and pad.rows and pad.rows[MR.ROW]
    if not r then return nil end
    local k = { s0 = MR.S0, n = MR.PER_PAGE }
    for name, col in pairs(MR.COL) do k[name] = col end
    return k
end
function MR.doc()
    -- (s10 review) an editor whose macro is gone (deleted or renamed elsewhere): back to the grid, never onto a neighbour
    if st.mode == "edit" and MR.ready() and not MR.off() and not MR.selIndex() then st.mode, st.delAt = "grid", nil end
    local c = MR.counts()
    local d = { v = SV, ready = MR.ready() or nil, off = MR.off() or nil, win = MR.winShown() or nil, cb = combat() or nil,
                norow = st.norow or nil, tab = st.tab, cnt = c, me = TSs(function() return UnitName("player") end),
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
                local n, ic = MR.info(it.abs)
                cells[k] = { n = n or "", i = ic }
            end
        end
        d.cells = cells
    end
    local i = MR.selIndex()
    if i then
        local n, ic, b = MR.info(i)
        local lines = MR.lines(b)
        local lock = {}
        for k, ln in ipairs(lines) do if ln:find("|", 1, true) then lock[#lock + 1] = k end end
        d.mac = { n = n, i = ic, lines = lines, len = #(b or ""), lock = (#lock > 0) and lock or nil, more = (#lines > MR.LINE_ROWS) or nil }
    end
    if view == "edit" then
        local rows = MR.editRows()
        if rows then MR.efSync(rows); d.ef = st.efk; d.efc = MR.editCell(st.efk) end
        d.del = (st.delAt and GetTime() - st.delAt <= MR.DEL_S) and true or nil
    elseif st.mode == "pick" and st.pick then
        local p = st.pick
        local pages = math.max(1, math.ceil(#p.list / MR.PICK_PAGE))
        p.page = math.max(1, math.min(p.page, pages))
        local rows = {}
        for k = 1, MR.PICK_PAGE do
            local e = p.list[(p.page - 1) * MR.PICK_PAGE + k]
            if not e then break end
            rows[k] = { t = e.t, n = e.n, i = e.i }
        end
        d.pick = { rows = rows, page = p.page, pages = pages, cur = p.cur, rec = st.rec and st.rec.id or nil,
                   step = st.rec and (#st.rec.picks + 1) or nil, of = st.rec and st.rec.need or nil }
    elseif st.mode == "rec" then
        local ids = {}
        for k, r in ipairs(MR.RECIPES) do ids[k] = r.id end
        d.rec = { ids = ids, cur = st.rcur or 0 }
    end
    if st.kbd then d.kbd = { name = st.kbd.name, text = st.kbd.text, max = st.kbd.max, line = st.kbd.line } end
    return d
end
-- a press / tap's answer goes at once; an event's (soon) only when the document changed
function MR.emit(onlyChanged)
    local ok, d = pcall(MR.doc)
    if not ok or type(d) ~= "table" then return end
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
        ctlr.n = ctlr.n + 1
        pcall(function() WC.send(WC.encode({ type = "macpad", v = SV, lost = ctlr.why or "lost", n = ctlr.n })) end)
        MR.soon()
    end,
}
function MR.takePad()
    local c = capture()
    if not c then ctlr.held, ctlr.why = false, "no controller capture" return false end
    -- D2: while the game's own macro window shows, it is hidden by alpha under the room (the capture's work) and this take pre-empts
    -- (capture v9 `window`) until the `wx` cell's secure click closes it
    local spec = { press = MR.spec.press, lost = MR.spec.lost }
    if MR.winShown() then spec.hide = { "MacroFrame" }; if (c.version or 1) >= 9 then spec.window = true end end
    local ok, why = c.take(MR.GUEST, spec)
    if not ok and plain(why) == "bad spec" and (spec.hide or spec.window) then
        spec.hide, spec.window = nil, nil
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
        MR.recall()
        MR.takePad()
    end
    MR.emit()
end
-- the room left (the app's `f`, or `fb` for B): whatever was half done goes quietly; nothing is written
function MR.free(why)
    ctlr.want = false
    if st.mode == "bind" and type(WC.spellsBindHosted) == "table" then pcall(WC.spellsBindHosted.abort, "left") end
    if st.kbd then MR.kbdClose() end
    st.mode, st.pick, st.rec, st.delAt = "grid", nil, nil, nil
    MR.remember()
    MR.freePad(why)
    MR.emit()
end

-- ---------------------------------------------------------------- the keyboard (D4): the chat keyboard's borrowed field
function MR.kbdClose()
    st.kbd = nil
    if type(WC.chatField) == "table" and type(WC.chatField.close) == "function" and WC.chatField.name() and
        WC.chatField.name():sub(1, 3) == "mac" then pcall(WC.chatField.close) end
end
-- name: "macnew" (New), "macname" (rename), "macline" (a line: [line] its number, [ins] = a new line after it, 0 = at the end)
function MR.kbdOpen(name, text, max, line, ins)
    if type(WC.chatField) ~= "table" or type(WC.chatField.open) ~= "function" then MR.ack(name, false, "no keyboard") return false end
    st.kbd = { name = name, text = text or "", max = max, line = line, ins = ins, back = st.mode }
    local ok = pcall(WC.chatField.open, { name = name, text = text or "", max = max,
        holds = function() return ctlr.held == true end,
        done = function(typed) return MR.kbdDone(typed) end })
    if not ok then st.kbd = nil; MR.ack(name, false, "no keyboard") return false end
    st.mode = "kbd"
    MR.sayNext()
    return true
end
-- the keyboard's Done (its Send / Start): ONE write, refused (the text kept, the keyboard up) while the guard says no
function MR.kbdDone(typed)
    local k = st.kbd
    if not k then return false, "no field" end
    local why = MR.canWrite()
    if why then MR.ack(k.name, false, why); MR.emit() return false, why end
    local ok, err
    if k.name == "macnew" then ok, err = MR.create(typed)
    elseif k.name == "macname" then ok, err = MR.rename(typed)
    else ok, err = MR.setLine(k.line, k.ins, typed) end
    if not ok then MR.ack(k.name, false, err); MR.emit() return false, err end
    MR.ack(k.name, true)
    st.kbd = nil
    st.mode = "edit"                                                     -- New opens the new macro's editor (the game's own order)
    if k.name == "macnew" then st.efk = "name" end
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
-- line [line] (1..L) becomes [text] (an emptied line goes); [ins] = a NEW line after line [ins] (0 = at the end)
function MR.setLine(line, ins, text)
    local i = MR.selIndex()
    if not i then return false, "missing" end
    local name, _, body = MR.info(i)
    local lines = MR.lines(body)
    text = type(text) == "string" and text:gsub("[\r\n]", " ") or ""
    if text:find("|", 1, true) then return false, "bad" end
    if ins ~= nil then
        if text == "" then return true end
        local at = (ins == 0 or ins > #lines) and (#lines + 1) or (ins + 1)
        table.insert(lines, at, text)
    else
        if not lines[line] then return false, "missing" end
        if lines[line]:find("|", 1, true) then return false, "locked" end
        if text == "" then table.remove(lines, line) else lines[line] = text end
    end
    local nb = MR.join(lines)
    if #nb > MR.BODY_MAX then return false, "long" end
    local ok = CALL(function() return EditMacro(i, nil, nil, nb) end)
    if not ok or not MR.findAndFocus(name, i) then return false, "refused" end      -- the body-only form: the macro stays at [i]
    return true
end
-- lines added at the end (a pick's line, a recipe's lines): ONE write. (s10 review) A recipe's "#showtooltip" goes on the macro's
-- FIRST line - the only place the game reads it - unless a "#show" line leads already; the recipe's own lines still go at the end
function MR.addLines(add, tooltip)
    local why = MR.canWrite()
    if why then return false, why end
    local i = MR.selIndex()
    if not i then return false, "missing" end
    local name, _, body = MR.info(i)
    local lines = MR.lines(body)
    if tooltip and not (lines[1] and lines[1]:lower():find("^#show")) then table.insert(lines, 1, "#showtooltip") end
    for _, ln in ipairs(add) do lines[#lines + 1] = ln end
    local nb = MR.join(lines)
    if #nb > MR.BODY_MAX then return false, "long" end
    local ok = CALL(function() return EditMacro(i, nil, nil, nb) end)
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
    MR.remember()
    return true
end

-- ---------------------------------------------------------------- the actions (a press or a finger's a / x / b)
function MR.enterEdit()
    if not MR.selIndex() then MR.ack("edit", false, "missing") return end
    st.mode, st.efk, st.delAt = "edit", "name", nil
end
function MR.bind()
    local i = MR.selIndex()
    if not i then MR.ack("bind", false, "missing") return end
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
    extra = function(list)
        local c = capture()
        if not (c and type(c.extra) == "function" and (tonumber(c.version) or 1) >= 4) then return false end
        local ok, got = pcall(c.extra, MR.GUEST, list)
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
function MR.editA(x)
    local rows, lines, name, _, body = MR.editRows()
    if not rows then st.mode = "grid" return end
    local r = MR.efSync(rows)
    if r ~= "del" then st.delAt = nil end
    local l = r and tonumber(r:match("^l(%d+)$"))
    if x then
        -- X on a line: a new line under it; on the name or a button: a new last line
        local room = MR.BODY_MAX - #body - 1
        if room < 1 then MR.ack("line", false, "long") return end
        MR.kbdOpen("macline", "", math.min(room, 255), nil, l or 0)
        return
    end
    if r == "name" then MR.kbdOpen("macname", name or "", MR.NAME_MAX)
    elseif l then
        if lines[l]:find("|", 1, true) then MR.ack("line", false, "locked") return end
        MR.kbdOpen("macline", lines[l], math.min(255, MR.BODY_MAX - #body + #lines[l]), l, nil)
    elseif r == "add" then
        local room = MR.BODY_MAX - #body - ((#body > 0) and 1 or 0)
        if room < 1 then MR.ack("line", false, "long") return end
        MR.kbdOpen("macline", "", math.min(room, 255), nil, 0)
    elseif r == "pick" then
        st.rec = nil
        st.pick = { list = MR.pickList(false), page = 1, cur = 0 }
        st.mode = "pick"
    elseif r == "rec" then
        st.mode, st.rcur = "rec", 0
    elseif r == "del" then
        if st.delAt and GetTime() - st.delAt <= MR.DEL_S then
            st.delAt = nil
            local ok, why = MR.delete()
            MR.ack("delete", ok, why)
            if ok then st.mode = "grid" end
        else
            local why = MR.canWrite()
            if why then MR.ack("delete", false, why) return end
            st.delAt = GetTime()
            C_Timer.After(MR.DEL_S + 0.1, function() MR.emit() end)
        end
    end
end
function MR.pickA()
    local p = st.pick
    if not p then st.mode = "edit" return end
    local e = p.list[(p.page - 1) * MR.PICK_PAGE + p.cur + 1]
    if not e then return end
    if st.rec then
        local r = st.rec
        r.picks[#r.picks + 1] = e.n
        if #r.picks < r.need then p.cur = 0 return end
        local ok, why = MR.addLines(r.f(r.picks[1], r.picks[2]), true)
        MR.ack("recipe", ok, why)
        if not ok and why ~= "long" then table.remove(r.picks) return end    -- refused (a fight, the window): the last pick again
        st.rec, st.pick, st.mode = nil, nil, "edit"
        return
    end
    local ok, why = MR.addLines({ MR.lineFor(e) }, false)
    MR.ack("pick", ok, why)
    if ok then st.pick, st.mode = nil, "edit" end
end
function MR.recA()
    local r = MR.RECIPES[(st.rcur or 0) + 1]
    if not r then return end
    st.rec = { id = r.id, need = r.n, f = r.f, picks = {} }
    st.pick = { list = MR.pickList(true), page = 1, cur = 0 }
    st.mode = "pick"
end
function MR.back()
    if st.mode == "edit" then st.mode, st.delAt = "grid", nil
    elseif st.mode == "pick" then
        st.pick, st.mode = nil, st.rec and "rec" or "edit"
        st.rec = nil
    elseif st.mode == "rec" then st.mode = "edit"
    elseif st.mode == "grid" then
        -- the room's top: B goes Home (the app's route); the controller goes back with "B"
        ctlr.want = false
        MR.remember()
        MR.freePad("B")
        ctlr.n = ctlr.n + 1
        pcall(function() WC.send(WC.encode({ type = "macpad", v = SV, k = "BACK", n = ctlr.n })) end)
    end
end

-- ---------------------------------------------------------------- the presses (the capture's eight; the Bind box's own while it is up)
function MR.move(key)
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
        MR.efSync(rows)
        local nb = #MR.BTN
        local firstBtn = #rows - nb
        if key == "PADDUP" then
            if st.ef >= firstBtn then st.ef = firstBtn - 1 elseif st.ef > 0 then st.ef = st.ef - 1 end
        elseif key == "PADDDOWN" then
            if st.ef < firstBtn - 1 then st.ef = st.ef + 1 elseif st.ef < firstBtn then st.ef = firstBtn end
        elseif key == "PADDLEFT" then if st.ef > firstBtn then st.ef = st.ef - 1 end
        elseif key == "PADDRIGHT" then if st.ef >= firstBtn and st.ef < #rows - 1 then st.ef = st.ef + 1 end end
        st.efk = rows[st.ef + 1] or "name"
        if st.efk ~= "del" then st.delAt = nil end
    elseif st.mode == "pick" and st.pick then
        local p = st.pick
        local pages = math.max(1, math.ceil(#p.list / MR.PICK_PAGE))
        local onPage = math.min(MR.PICK_PAGE, #p.list - (p.page - 1) * MR.PICK_PAGE)
        if key == "PADDUP" then p.cur = math.max(0, p.cur - 1)
        elseif key == "PADDDOWN" then p.cur = math.min(math.max(onPage - 1, 0), p.cur + 1)
        elseif key == "PADDLEFT" then if p.page > 1 then p.page, p.cur = p.page - 1, 0 end
        elseif key == "PADDRIGHT" then if p.page < pages then p.page, p.cur = p.page + 1, 0 end end
    elseif st.mode == "rec" then
        local c = st.rcur or 0
        if key == "PADDLEFT" and c % 3 > 0 then c = c - 1
        elseif key == "PADDRIGHT" and c % 3 < 2 then c = c + 1
        elseif key == "PADDUP" and c >= 3 then c = c - 3
        elseif key == "PADDDOWN" and c < 3 then c = c + 3 end
        st.rcur = c
    end
end
function MR.press(k)
    if not ctlr.held then ctlr.held, ctlr.why = true, nil end            -- a press proves the hold (a take made real by Home's ENTER)
    return MR.act(k)
end
-- (s10 review) the press's work, for the capture's press AND a finger's a / x / b: a TAP proves no hold (the take may still be queued
-- behind an NPC window, or pending in a fight), so only the capture's own press says held
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
    if k == "PADDUP" or k == "PADDDOWN" or k == "PADDLEFT" or k == "PADDRIGHT" then MR.move(k)
    elseif k == "PAD1" then
        if st.mode == "grid" then MR.gridA() elseif st.mode == "edit" then MR.editA(false)
        elseif st.mode == "pick" then MR.pickA() elseif st.mode == "rec" then MR.recA() end
    elseif k == "PAD3" then
        if st.mode == "grid" then
            local items = MR.focusGrid()
            local it = type(st.cur) == "number" and st.cur >= 0 and items[st.cur + 1] or nil
            if it and it.abs then MR.bind() end
        elseif st.mode == "edit" then MR.editA(true) end
    elseif k == "PAD2" then MR.back() end
    MR.sayNext()
    MR.emit()
end

-- ---------------------------------------------------------------- the row's taps (a finger; no game action of their own but `wx`)
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
        if key == "tg" or key == "tc" then
            if st.mode ~= "grid" then return end
            local t = key == "tg" and "g" or "c"
            if t ~= st.tab then st.tab, st.page, st.cur, st.sel, st.selName = t, 1, 0, nil, nil; MR.remember() end
            return MR.emit()
        end
        if key == "pp" or key == "pn" then
            local d = key == "pp" and -1 or 1
            if st.mode == "grid" then
                local _, pages = MR.pageItems()
                st.page = math.max(1, math.min(pages, st.page + d)); st.cur = 0; MR.focusGrid()
            elseif st.mode == "pick" and st.pick then
                local pages = math.max(1, math.ceil(#st.pick.list / MR.PICK_PAGE))
                st.pick.page = math.max(1, math.min(pages, st.pick.page + d)); st.pick.cur = 0
            end
            return MR.emit()
        end
        if sk then
            st.note = nil
            if st.mode == "grid" then
                local items = MR.pageItems()
                if items[sk + 1] then st.cur = sk; MR.focusGrid() end
            elseif st.mode == "edit" then
                local rows = MR.editRows()
                local r = rows and MR.editFromCell(sk, rows)
                if r then st.efk = r; MR.efSync(rows) end
                if r ~= "del" then st.delAt = nil end
            elseif st.mode == "pick" and st.pick then
                local e = st.pick.list[(st.pick.page - 1) * MR.PICK_PAGE + sk + 1]
                if e then st.pick.cur = sk end
            elseif st.mode == "rec" then
                if MR.RECIPES[sk + 1] then st.rcur = sk end
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
-- THE LAZY ROW (out of combat; with none free the room says so): Book's padtab pick 39 then 34, or the game's window opening
function MR.ensure()
    local pad = WC.pad
    if not (pad and type(pad.secureRow) == "function") then st.norow = true return false end
    if pad.rows and pad.rows[MR.ROW] then st.norow = nil; MR.emit() return true end
    if combat() then MR.emit() return false end
    local ok, row = pcall(pad.secureRow, MR.ROW, MR.NCOLS, MR.cellInit, MR.onCell, { onPress = true })
    st.norow = not (ok and type(row) == "number") or nil
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
        fr:HookScript("OnShow", function() C_Timer.After(0, function() MR.armClose(); MR.ensure(); MR.emit() end) end)
        fr:HookScript("OnHide", function() C_Timer.After(0, function() MR.emit() end) end)
    end)
end

WC.macroRoom = {
    version = SV,
    ensure = function() return MR.ensure() end,
    doc = function() return MR.doc() end,
    emit = function() return MR.emit() end,
    _mr = MR,                                                            -- the harness
}

local ev = CreateFrame("Frame")
for _, e in ipairs({ "UPDATE_MACROS", "PLAYER_ENTERING_WORLD", "PLAYER_REGEN_ENABLED", "PLAYER_REGEN_DISABLED", "ADDON_LOADED" }) do
    pcall(function() ev:RegisterEvent(e) end)
end
ev:SetScript("OnEvent", function(_, e, a1)
    if e == "UPDATE_MACROS" then st.loaded = true
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
    MR.soon()
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
