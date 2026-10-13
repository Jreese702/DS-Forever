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

local SV = "s5"     -- bump on every change; the app shows it, so a stale build is visible

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

    -- THE PAD COLUMNS for Bind by touch (Book.lua owns the row): one stable column per spell that can go on a button
    if type(WC.spellsPadColumns) == "function" then
        local ids = {}
        for _, it in ipairs(doc.items) do
            if it.t == T_SPELL and not it.pas and not it.fm and it.a then ids[#ids + 1] = it.a end
        end
        local okc, cols = pcall(WC.spellsPadColumns, ids)
        if okc and type(cols) == "table" then
            for _, it in ipairs(doc.items) do
                if it.a and cols[it.a] then it.pc = cols[it.a] end
            end
        end
    end

    -- THE PET'S BOOK: the game's Pet tab exists only while a pet with a book is out.
    local okp, nPet, token = CALL(function() return C_SpellBook.HasPetSpells() end)
    if okp and type(nPet) == "number" and WC.isPlain(nPet) and nPet > 0 then
        local hasBook = TB(function() return PetHasSpellbook() end)
        -- the game's Pet tab: its name is the global PET, its icon GetPetIcon()
        doc.pet = { tok = WC.isPlain(token) and type(token) == "string" and token or nil, book = hasBook,
                    n = TS(function() return PET end), i = TN(function() return GetPetIcon() end), items = {} }
        for slot = 1, math.min(nPet, MAX_PET) do
            local it = readItem(slot, BANK_PET)
            if it then doc.pet.items[#doc.pet.items + 1] = it end
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
