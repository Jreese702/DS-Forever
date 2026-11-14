-- WowCompanionSpells / Spells.lua
-- The SPELLS room's feed: the spellbook the way this client's own window (PlayerSpellsFrame, camelot
-- flavour) organises it, so the bottom screen can show it without the game's window.
--
-- READ-ONLY. This file reads the book and writes documents. It touches no game frame, hides nothing,
-- takes no controller button and sends nothing to the game. Acting (a spell onto a controller button,
-- casting from the book) and hiding the game's window come later, each after the user's ruling.
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

local SV = "s10b"   -- bump on every change; s10b (review of feat/pet-spell-binding, #234, the user: "build it"): a pet spell is PASSIVE as the game's own Spellbook says (its book item's isPassive / IsSpellBookItemPassive, the spell's own IsSpellPassive), never by the pet bar's state - the server packs every spell that cannot auto-cast with state 1, so the high-byte-1 / IsPetActionPassive readings made Seduction, Spell Lock and Sacrifice "passive": no number, no Bind, hidden with Hide Passives; s10a (review of feat/pet-spell-binding, #234, the user: "build it"): only a pet WITH A BOOK (the game's own Pet tab: HasPetSpells() and PetHasSpellbook()) has spells of ours - a charmed / possessed unit (Mind Control, Enslave) has pet spells but no book, so its spells are never kept as this character's pet spells (WC.spellsPetName), never numbered, and a travelling pet slot reads nothing then (WC.spellsPetAt / WC.spellsPetSlotOf): a mob's spell sharing an id with one of the player's own never turns that spell into a "pet spell" cast by bare name; s10 PET SPELLS (#234, the user: "build it"): the pet book's SPELLS get numbers (`pv`, after the player's top ranks, before the lower ranks), never its passives or its commands (Attack / Follow / Stay ... have their homes); each pet item says `cmd` (a command), `bind` (a spell that can go on a button) and `pb` (on the pet bar), its bars are the game's own FindPetActionButtons; the Pet tab is line WC.SPELLS_PET_LINE (101); a pet book slot k travels as WC.SPELLS_PET_SLOT0 + k (5000 + k) through the Bind box and the Controller room's puts (WC.spellsPetAt / WC.spellsBankSlot read it back); WC.spellsPetName(id) = the name our own secure buttons cast a pet spell by (CastSpellByName, the game's /cast), remembered per character (WowCompanionSpellsDB.petSpellBy[name-realm]); s9 RANK UP REVIEW (the user, 2026-11-12: "I know we have something in place to rank up abilities on login, but maybe for skills that are already on a bar a prompt to allow them to be ranked up when a new rank is learned is available?"): WC.spellsNewsHas(id) = that spell was learned this load (PadMap m144: rankSync leaves an entry for the rank-up prompt only then); s8 RANK UP (the user, 2026-11-12: "I know we have something in place to rank up abilities on login, but maybe for skills that are already on a bar a prompt to allow them to be ranked up when a new rank is learned is available?"): `rk` = for each just-learned spell that is the top rank now, how many lower copies are on buttons (PadMap m143 WC.rankUpList: bars, radial, In-combat), so Home can ask; s7 s7: loose fly-out members (aspects) numbered; the app shows it, so a stale build is visible

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
