-- WowCompanionCharacter / Character.lua
-- The CHARACTER room's feed: what the game's Character window (CharacterFrame, the camelot build)
-- shows, as data. Six tabs in the game: Character (paper doll + stats), Reputation, Skills, PvP,
-- Currency, Statistics. The paper doll's worn gear is Bags' (`bags.equipped`) and the PvP tab is the
-- PVP session's (`addon/WowCompanionPvp/`), so neither is read here.
--
-- READ-ONLY. Nothing in this file changes the game: no fold / unfold, no selection, no check box, no
-- secure frame, no slash command, no pad row. It reads, and it hooks the Character window's show / hide
-- and tab switch to tell the app which tab the game has open. The one exception is the controller (below):
-- while the game's window is open the room borrows the controller through interact's shared capture; B gives
-- it back. No game window is ever opened or closed from this file. (The game's buttons come in a second
-- step, after the user has signed off the look: user ruling 2026-09-28.)
--
-- WHAT IT SENDS: {"type":"character","v":CV,"c":{<parts>}}. The parts are
--   who   name, rank title, level, class, spec, race, faction, the game's level line
--   st    the stats pane, in the game's own sections, order and TEXT
--   pet   the pet's stats (false when there is no pet UI)
--   petinfo  the pet page's left pane: name, family, level line, pet XP, loyalty; hunters: happiness, diet (or false)
--   sets  the gear sets (Equipment Manager)
--   rep   the reputation list;  sk the skills list;  cur the currency list;  sx the statistics tree
--   win   the game's Character window: open, which tab, which side pane
--   tabs  the fileDataIDs of the side tabs' icons (constant; for the room's art)
--   xp    the XP bar: shown or not (the game's rule), current / max, rested, rest state
--   watch the reputation bar: the faction shown as the experience bar, or false
--   str   the game's own words for the room's fixed labels (constant)
-- Only the parts that changed go out (each part is deduped on its encoded form), so a kill that moves
-- a statistic does not re-send the reputations. The core's heartbeat carries every part (`full`).
--
-- THE STATS ARE THE GAME'S OWN TEXT. For each row the game's own PAPERDOLL_STATINFO[..].updateFunc is
-- run against a private stand-in frame (a plain table), exactly as the game's stats pane runs it
-- against its row frame. So the labels, numbers, percentages, damage ranges, red / green and the
-- explanation lines (tooltip2) are 1:1 with the top screen, and a patch that changes a formula
-- changes it here too. Read in the source (2026-09-28): those functions write only into the frame
-- they are given; nothing of the game's is written. A row whose function throws goes out as the THREW
-- sentinel, never as a broken document.
--
-- SECRECY: every value goes through the safety kernel (S / CALL / isPlain) before it is used, so a
-- value the game makes secret (the binary has `ShouldUnitStatsBeSecret`: stats MAY go secret in combat;
-- not measured) becomes the SECRET sentinel on the wire, and nothing else is lost.

local ADDON = ...
local WC = _G.WowCompanion
if type(WC) ~= "table" or type(WC.S) ~= "function" or type(WC.send) ~= "function" then return end
local CALL, isPlain = WC.CALL, WC.isPlain
local format = string.format

-- BARS ONLY (2026-09-29; the user on the Thor, 16:35: "the XP bar is not tracking XP like it should"): while the
-- Character room is in no installed build, this addon sends ONLY what the bottom bar draws: the XP bar (`xp`) and the
-- watched faction (`watch`). No stats, no lists, no hook on the game's Character window, no controller: plain reads
-- of UnitXP / UnitXPMax / GetXPExhaustion / GetRestState and C_Reputation.GetWatchedFactionData, on their own events
-- and the core's heartbeat. Set to false when the Character room itself goes to the device.
local BARS_ONLY = false

local CV = BARS_ONLY and "c12b" or "c12"     -- bump on every change; the app shows it, so a stale build is visible

-- ---------------------------------------------------------------- safe reads

-- Counted per document, so a shift in the secrecy map under a patch shows up on the wire (`sec`).
local tally = { secret = 0, threw = 0 }

-- A value that came back from the game (inside a table, or as a later return): plain, or a sentinel.
local function P(v)
    if type(v) == "nil" then return nil end
    if not isPlain(v) then tally.secret = tally.secret + 1; return WC.SECRET end
    return v
end
local function PN(v) v = P(v); if type(v) == "number" then return v end end
local function PS(v) v = P(v); if type(v) == "string" and v ~= WC.SECRET then return v end end
local function PB(v) v = P(v); if type(v) == "boolean" then return v end end

-- One call, its first value, made safe.
local function one(fn, ...)
    local ok, v = CALL(fn, ...)
    if not ok then tally.threw = tally.threw + 1; return nil end
    return P(v)
end

-- The game's markup off a text: colours (both forms), textures, atlases; |n becomes a new line.
local function strip(s)
    s = s:gsub("|c%x%x%x%x%x%x%x%x", "")
    s = s:gsub("|cn[^:]*:", "")
    s = s:gsub("|r", "")
    s = s:gsub("|T.-|t", "")
    s = s:gsub("|A.-|a", "")
    s = s:gsub("|n", "\n")
    return s
end

-- 1 = shown green by the game, 2 = red, 0 = plain. From the first colour code in a text.
local function colourOf(s)
    local r, g = s:match("|c%x%x(%x%x)(%x%x)%x%x")
    if not r then return 0 end
    r, g = tonumber(r, 16), tonumber(g, 16)
    if r >= 0xc0 and g < 0x80 then return 2 end
    if g >= 0xc0 and r < 0x80 then return 1 end
    return 0
end

-- A game text for the wire: markup stripped; a sentinel stays a sentinel; nil stays nil.
local function text(v)
    v = P(v)
    if type(v) == "number" then return tostring(v) end
    if type(v) ~= "string" or v == WC.SECRET then return v end
    return strip(v)
end

-- format() with the game's strings, which a patch can remove: nil instead of an error.
local function fmt(pattern, ...)
    if type(pattern) ~= "string" then return nil end
    local ok, s = pcall(format, pattern, ...)
    if ok then return s end
end

local function big(n)
    if type(n) ~= "number" then return nil end
    if type(BreakUpLargeNumbers) == "function" then
        local ok, s = pcall(BreakUpLargeNumbers, n)
        if ok and isPlain(s) then return s end
    end
    return tostring(n)
end

local function inCombat()
    local v = one(UnitAffectingCombat, "player")
    return v == true
end

-- ---------------------------------------------------------------- who

local function collectWho()
    local w = {}
    w.name = PS(one(UnitName, "player"))
    w.title = PS(text(one(UnitPVPName, "player")))          -- e.g. "Sergeant Ada Wren", as the title bar
    w.lvl = PN(one(UnitLevel, "player"))
    local el = PN(one(UnitEffectiveLevel, "player"))
    if el and w.lvl and el ~= w.lvl then w.elvl = el end
    local ok, cls, file, classID = CALL(UnitClass, "player")
    if ok then w.cls, w.clsFile, w.classID = PS(cls), PS(file), PN(classID) end
    local sex = PN(one(UnitSex, "player"))
    local spec = PN(one(function() return C_SpecializationInfo.GetSpecialization() end))
    if spec and w.classID then
        local on = PB(one(function() return C_SpecializationInfo.IsSpecSelectionEnabled(w.classID) end))
        if on then
            local ok2, _, name = CALL(function()
                return C_SpecializationInfo.GetSpecializationInfo(spec, false, false, nil, sex)
            end)
            if ok2 then w.spec = PS(name) end
            if w.spec == "" then w.spec = nil end
        end
    end
    w.race = PS(one(UnitRace, "player"))
    w.faction = PS(one(UnitFactionGroup, "player"))
    -- The game's level line (PaperDollFrame_SetLevel), with its class colour stripped: the app
    -- colours the class part itself from clsFile.
    if w.lvl and w.cls then
        local lvlText = w.lvl
        if w.elvl then lvlText = fmt(_G.EFFECTIVE_LEVEL_FORMAT, w.elvl, w.lvl) or w.lvl end
        local line
        if w.spec then line = fmt(_G.PLAYER_LEVEL, lvlText, "ffffffff", w.spec, w.cls)
        else line = fmt(_G.PLAYER_LEVEL_NO_SPEC, lvlText, "ffffffff", w.cls) end
        if line then w.line = strip(line) end
    end
    return w
end

-- ---------------------------------------------------------------- stats (the game's own functions)

local function fontString()
    local fs = {}
    function fs:SetText(s) self.text = s end
    function fs:GetText() return self.text end
    function fs:SetFormattedText(f, ...) self.text = fmt(f, ...) end
    function fs:SetTextColor() end
    function fs:Show() end
    function fs:Hide() end
    function fs:SetShown() end
    return fs
end
local NOOP = function() end
local DUMMY = setmetatable({}, { __index = function() return NOOP end })

-- The private stand-in for one of the stats pane's row frames.
local function standIn()
    local fr = { Label = fontString(), Value = fontString(), shown = true }
    function fr:Show() self.shown = true end
    function fr:Hide() self.shown = false end
    function fr:SetShown(v) self.shown = v and true or false end
    function fr:IsShown() return self.shown end
    function fr:GetParent() return DUMMY end
    function fr:SetScript() end
    function fr:GetScript() end
    return fr
end

-- The explanation lines the game shows when the pointer rests on a row (tooltip, tooltip2, tooltip3).
local function tipOf(fr)
    local t = {}
    for _, k in ipairs({ "tooltip", "tooltip2", "tooltip3" }) do
        local v = text(fr[k])
        if type(v) == "string" and v ~= WC.SECRET and v ~= "" then t[#t + 1] = v end
    end
    if #t > 0 then return t end
end

local function row(key, fr)
    local r = { k = key }
    local l = text(fr.Label.text)
    if l then r.l = l end
    local raw = P(fr.Value.text)
    if type(raw) == "string" and raw ~= WC.SECRET then
        local c = colourOf(raw)
        if c ~= 0 then r.c = c end
        r.v = strip(raw)
    elseif type(raw) == "number" then
        r.v = tostring(raw)
    else
        r.v = raw or WC.ABSENT
    end
    r.t = tipOf(fr)
    return r
end

-- The game drops a row whose number equals its hideAt (0). A secret number cannot be compared: keep it.
local function hides(num, hideAt)
    if hideAt == nil or type(num) == "nil" or not isPlain(num) then return false end
    return num == hideAt
end

-- characterframe.lua's RESISTANCE_STAT_ENTRIES (a local there), in its order.
local RESIST = {
    { "DAMAGE_SCHOOL7", "Arcane", "arcane" }, { "DAMAGE_SCHOOL3", "Fire", "fire" },
    { "DAMAGE_SCHOOL5", "Frost", "frost" }, { "DAMAGE_SCHOOL4", "Nature", "nature" },
    { "DAMAGE_SCHOOL6", "Shadow", "shadow" },
}

-- CharacterStatsPaneScrollBoxMixin:UpdateStats, as data.
local function collectStats(unit)
    local cats, info = _G.PAPERDOLL_STATCATEGORIES, _G.PAPERDOLL_STATINFO
    if type(cats) ~= "table" or type(info) ~= "table" then return false end
    local out = {}
    for _, cat in ipairs(cats) do
        if not cat.unit or cat.unit == unit then
            local pick = {}
            for _, st in ipairs(cat.stats or {}) do
                -- `primary` / `roles` need the game's spec logic; camelot's table uses neither (read
                -- 2026-09-28). Leave such a row out rather than guess.
                local show = not st.primary and not st.roles
                if show and st.unit and st.unit ~= unit then show = false end
                if show and st.showFunc then
                    local ok, r = pcall(st.showFunc)
                    show = ok and isPlain(r) and r and true or false
                end
                local entry = info[st.stat]
                if show and type(entry) == "table" and type(entry.updateFunc) == "function" then
                    pick[#pick + 1] = st
                end
            end
            if #pick > 0 then
                local sec = { h = text(cat.categoryName) or "", r = {} }
                for _, st in ipairs(pick) do
                    local fr = standIn()
                    local ok, num = pcall(info[st.stat].updateFunc, fr, unit, st.id)
                    if not ok then
                        tally.threw = tally.threw + 1
                        sec.r[#sec.r + 1] = { k = st.stat, v = WC.THREW }
                    elseif fr.shown ~= false and not hides(num, st.hideAt) then
                        sec.r[#sec.r + 1] = row(st.stat, fr)
                    end
                end
                out[#out + 1] = sec
            end
        end
    end
    -- Resistances: characterframe.lua adds them after the table, for the player and the pet.
    local dc = type(Enum) == "table" and Enum.Damageclass
    local sec = { h = text(_G.STAT_CATEGORY_RESISTANCE) or "Resistance", r = {} }
    for _, e in ipairs(RESIST) do
        local cls = type(dc) == "table" and dc[e[2]]
        if cls then
            local name = PS(_G[e[1]]) or e[2]
            local r = { k = e[3], l = fmt(_G.STAT_FORMAT, name) or name }
            local ok, _, eff = CALL(UnitResistance, unit, cls)
            eff = P(eff)
            if not ok then
                tally.threw = tally.threw + 1
                r.v = WC.THREW
            elseif type(eff) == "number" then
                r.v = big(eff)
                if type(_G.PaperDollFrame_SetResistanceTooltips) == "function" then
                    local tip = {}
                    if pcall(_G.PaperDollFrame_SetResistanceTooltips, tip, name, eff, unit, cls) then r.t = tipOf(tip) end
                end
            else
                r.v = eff or WC.ABSENT
            end
            sec.r[#sec.r + 1] = r
        end
    end
    if #sec.r > 0 then out[#out + 1] = sec end
    return out
end

local function collectPet()
    local has = PB(one(HasPetUI))
    if not has then return false end
    return collectStats("pet")
end

-- THE PET PAGE (user ruling ~22:15: "the pet page for hunters should be under the charactor tab"). In this client it is
-- the Character tab's third small tab: the left pane swaps the doll for the pet (paperdollframe.lua PaperDollFrame_ShowSidebar,
-- PaperDollFrame_SetPetLevel; blizzard_framexml pethappiness.lua), the right pane shows the pet's stats (part `pet`).
-- Read-only: the page has no button in the game. Happiness, loyalty and diet are the hunter's (HasPetUI's second return);
-- a warlock's demon has level, family and stats only. The texts are the game's own words.
local function collectPetInfo()
    local ok, has, hunter = CALL(HasPetUI)
    if not ok or PB(has) ~= true then return false end
    local p = { hunter = PB(hunter) or nil }
    p.name = PS(one(UnitName, "pet"))
    p.family = PS(one(UnitCreatureFamily, "pet"))
    p.lvl = PN(one(UnitLevel, "pet"))
    if p.lvl then p.line = fmt(_G.UNIT_TYPE_LEVEL_TEMPLATE, p.lvl, p.family or "") end
    p.icon = PN(one(GetPetIcon))                        -- the pet's icon (fileDataID): the room cannot draw its 3D model
    local okx, xp, need = CALL(GetPetExperience)
    xp, need = PN(xp), PN(need)
    if okx and need and need > 0 and xp then
        p.xp, p.xpMax = xp, need
        p.xpText = format("XP %s/%s", xp, need)       -- PetExpColoredProgressBarMixin:OnUpdate, the game's literal
    end
    p.loyalty = PS(text(one(function() return C_PetInfo.GetPetLoyalty() end)))
    -- PET TALENTS (user 23:00: "There is also pet talent trees ... in wow forever"). This client's UI source shows no pet talent
    -- window and no door to one (AGENT_LOG), but the API has both reads: the device says what the game really keeps.
    local tree = PS(one(function() return C_PetInfo.GetPetTalentTree() end))
    if tree and tree ~= "" then p.tree = tree end
    local okt, total, used = CALL(function() return C_PetInfo.GetPetTrainingPoints() end)
    total, used = PN(total), PN(used)
    if okt and total then p.tp, p.tpUsed = total, used end
    if p.hunter then
        local okh, happy, dmg, rate = CALL(function() return C_PetInfo.GetPetHappiness() end)
        happy, dmg, rate = PN(happy), PN(dmg), PN(rate)
        if okh and happy then
            p.happy = happy                             -- 1 unhappy, 2 content, 3 happy (the game's atlas by it)
            p.happyText = PS(_G["PET_HAPPINESS" .. happy])
            if dmg then p.dmgText = fmt(_G.PET_DAMAGE_PERCENTAGE, dmg) end
            if rate and rate < 0 then p.loyaltyNote = PS(_G.LOSING_LOYALTY)
            elseif rate and rate > 0 then p.loyaltyNote = PS(_G.GAINING_LOYALTY) end
        end
        local okd, diet = CALL(function() return C_PetInfo.GetPetFoodTypes() end)
        if okd and type(diet) == "table" then
            local foods = {}
            for _, d in ipairs(diet) do local v = PS(d); if v then foods[#foods + 1] = v end end
            local list = #foods > 0 and table.concat(foods, PS(_G.PET_FOOD_DELIMIT) or ", ") or (PS(_G.NONE) or "")
            p.diet = fmt(_G.PET_DIET_TEMPLATE, list)
        end
    end
    for _, k in ipairs({ "line", "dmgText", "diet" }) do if p[k] then p[k] = strip(p[k]) end end
    return p
end

-- ---------------------------------------------------------------- gear sets

-- THE GEAR SETS' CELLS (f-equipment-manager) live on Auras.lua's own SECURE row "auracancel", in its cells EQ_FIRST.. (cells 0..2 are
-- the buff cancel and the game window's open / close), NOT on a row of their own: the pad has 54 rows and every one counts (1.0.8:
-- the dialogs' row, asked for last, got none). Those cells carry no secure action, so a tap only runs Auras' onClick, which hands it
-- here (WC.character.onSetCell). Each key keeps its cell as a plain row's would (pad.slotFor's "eqset" namespace, its quarantine).
local EQ_ROW, EQ_FIRST, EQ_CELLS = "auracancel", 3, 40
local function eqCell(key)
    if not (WC.pad and type(WC.pad.slotFor) == "function") then return nil end
    local s = WC.pad.slotFor("eqset", key)
    if type(s) == "number" and EQ_FIRST + s < EQ_CELLS then return EQ_FIRST + s end
end

local function collectSets()
    local ok, ids = CALL(function() return C_EquipmentSet.GetEquipmentSetIDs() end)
    if not ok or type(ids) ~= "table" then return false end
    local out = {}
    for _, id in ipairs(ids) do
        id = PN(id)
        if id then
            -- name, iconFileID, setID, isEquipped, numItems, numEquipped, numInInventory, numLost
            local ok2, name, icon, _, eq, _, _, _, lost = CALL(function() return C_EquipmentSet.GetEquipmentSetInfo(id) end)
            if ok2 then
                local row = { id = id, n = PS(name), icon = PN(icon), eq = PB(eq) or false, lost = PN(lost) }
                -- the set's two cells on the `eqset` row (f-equipment-manager): Equip and Save
                if WC.pad and type(WC.pad.slotFor) == "function" then
                    row.ce = eqCell("equip:" .. id)
                    row.cs = eqCell("save:" .. id)
                end
                out[#out + 1] = row
            end
        end
    end
    -- a deleted set's cells go back to the row (after the pad's quarantine, so a stale tap can never reach another set)
    if WC.pad and type(WC.pad.retain) == "function" then
        local keep = { yes = true, no = true }
        for _, r in ipairs(out) do keep["equip:" .. r.id] = true; keep["save:" .. r.id] = true end
        pcall(WC.pad.retain, "eqset", keep)
    end
    return out
end

-- ---------------------------------------------------------------- reputation

local MAX_REACTION = 8
local REPKEYS = {
    "name", "factionID", "isHeader", "isChild", "isCollapsed", "isHeaderWithRep", "reaction",
    "currentReactionThreshold", "nextReactionThreshold", "currentStanding", "hasBonusRepGain",
    "atWarWith", "canToggleAtWar", "canSetInactive", "isWatched", "description",
}

local function progress(cur, max)
    local s = fmt(_G.REPUTATION_PROGRESS_FORMAT, big(cur), big(max))
    return s and strip(s)
end

local function standing(reaction, sex)
    if type(GetText) ~= "function" or type(reaction) ~= "number" then return nil end
    local ok, s = pcall(GetText, "FACTION_STANDING_LABEL" .. reaction, sex)
    if ok then return PS(s) end
end

-- reputationframe.lua's three bar initialisers: friendship first, then renown, then standard.
-- `col` = FACTION_BAR_COLORS index (1..8), or "blue" for renown. Values are already normalised to 0..max.
local function repBar(f, sex)
    local id = PN(f.factionID)
    if id then
        local okf, fr = CALL(function() return C_GossipInfo.GetFriendshipReputation(id) end)
        if okf and type(fr) == "table" and (PN(fr.friendshipFactionID) or 0) > 0 then
            local lo, st, nt = PN(fr.reactionThreshold) or 0, PN(fr.standing) or 0, PN(fr.nextThreshold)
            local b = { kind = "friend", st = PS(text(fr.reaction)), col = 5, cur = 1, max = 1 }
            if nt then b.cur, b.max = st - lo, nt - lo; b.p = progress(b.cur, b.max) end
            return b
        end
        if PB(one(function() return C_Reputation.IsMajorFaction(id) end)) then
            local okm, m = CALL(function() return C_MajorFactions.GetMajorFactionData(id) end)
            local maxed = PB(one(function() return C_MajorFactions.HasMaximumRenown(id) end))
            local lvl = okm and type(m) == "table" and PN(m.renownLevel) or 0
            local b = { kind = "major", ren = lvl, st = fmt(_G.RENOWN_LEVEL_LABEL, lvl), col = "blue", cur = 0, max = 0 }
            if maxed then b.cur, b.max = 1, 1
            elseif okm and type(m) == "table" then
                b.cur, b.max = PN(m.renownReputationEarned) or 0, PN(m.renownLevelThreshold) or 0
            end
            if not maxed then b.p = progress(b.cur, b.max) end
            return b
        end
    end
    local r = PN(f.reaction)
    local b = { st = standing(r, sex), col = r }
    if r == MAX_REACTION then b.cur, b.max = 1, 1; return b end
    local lo, hi, cur = PN(f.currentReactionThreshold), PN(f.nextReactionThreshold), PN(f.currentStanding)
    if lo and hi and cur then
        b.cur, b.max = cur - lo, hi - lo
        b.p = progress(b.cur, b.max)
    end
    return b
end

-- The list as the game's Reputation tab has it. A header the player folded IN THE GAME comes without
-- its factions (the game's list does not return them); this addon never unfolds one (read-only).
local function collectRep()
    local n = PN(one(function() return C_Reputation.GetNumFactions() end))
    if not n then return false end
    local sex = PN(one(UnitSex, "player"))
    local out = {}
    for i = 1, n do
        local ok, d = CALL(function() return C_Reputation.GetFactionDataByIndex(i) end)
        if ok and type(d) == "table" then
            local f = {}
            for _, k in ipairs(REPKEYS) do f[k] = P(d[k]) end
            local e = {
                n = PS(f.name), id = PN(f.factionID),
                h = f.isHeader == true or nil, ch = f.isChild == true or nil, hb = f.isHeaderWithRep == true or nil,
                fold = f.isCollapsed == true or nil, war = f.atWarWith == true or nil,
                cw = f.canToggleAtWar == true or nil, ci = f.canSetInactive == true or nil,
                watch = f.isWatched == true or nil, bonus = f.hasBonusRepGain == true or nil,
                desc = PS(text(f.description)),
            }
            if e.desc == "" then e.desc = nil end
            if e.id then
                e.acct = PB(one(function() return C_Reputation.IsAccountWideReputation(e.id) end)) or nil
            end
            if PB(one(function() return C_Reputation.IsFactionActive(i) end)) == false then e.inact = true end
            if not e.h or e.hb then e.bar = repBar(f, sex) end
            out[#out + 1] = e
        end
    end
    return out
end

-- ---------------------------------------------------------------- the XP and reputation bars

-- For the companion's bottom bar (user request 2026-09-28 ~20:40, relayed: "the xp bar and reputation bar
-- live ... at the bottom of the page, behind the home button"; UI Art draws it, Home places it). The same
-- reads the game's own status bars make (blizzard_statustrackingbar shared/expbar.lua, reputationbar.lua)
-- and the same rule for which bar shows (statustrackingmanageroverrides.lua CanShowBar): the XP bar unless
-- the player is at the level cap, has XP switched off or a game rule hides it; the reputation bar while a
-- faction is set to "Show as Experience Bar". In the game's own API documentation neither UnitXP, UnitXPMax,
-- PLAYER_XP_UPDATE nor FactionData carries a secrecy flag (UnitHealth, MEASURED secret, carries
-- SecretReturns; the stat functions carry SecretWhenUnitStatsRestricted). Not yet measured on the device.
local function collectXp()
    local x = {}
    local ok, v = pcall(function() return GameRulesUtil.CanShowExperienceBar() end)
    local show = nil
    if ok then show = PB(v) end
    if show == nil then                         -- the game's helper is missing: its rule, by hand
        local lvl, cap = PN(one(UnitLevel, "player")), PN(one(GetMaxLevelForPlayerExpansion))
        show = PB(one(IsXPUserDisabled)) ~= true and not (lvl and cap and lvl >= cap)
    end
    x.show = show and true or false
    x.lvl = PN(one(UnitLevel, "player"))
    x.cur, x.max = PN(one(UnitXP, "player")), PN(one(UnitXPMax, "player"))
    x.rest = PN(one(GetXPExhaustion)) or 0     -- rested XP still to earn; 0 when none (UI Art's shape)
    x.state = PN(one(GetRestState))            -- 1 rested, 2 normal: the game colours the bar by it
    if PB(one(IsResting)) then x.resting = true end
    -- The numbers the Character room shows (user 22:05: no numbers on the bars, "Numbers can be seen in the charactor
    -- page"), in the game's own words: the
    -- bar's text (ExpBarOverrides UpdateCurrentText) and the lines of its rested tooltip (ExhaustionToolTipText).
    if x.cur and x.max then
        x.text = fmt(_G.XP_STATUS_BAR_TEXT, x.cur, x.max)
        local lines = {}
        local pct = x.max > 0 and math.ceil(x.cur / x.max * 100) or 0
        lines[#lines + 1] = fmt(_G.XP_TEXT, big(x.cur), big(x.max), pct)
        local okr, _, stateName, mult = CALL(GetRestState)
        stateName, mult = PS(stateName), PN(mult)
        if okr and stateName and mult then lines[#lines + 1] = fmt(_G.EXHAUST_TOOLTIP1, stateName, mult * 100) end
        if not x.resting and (x.state == 4 or x.state == 5) then lines[#lines + 1] = PS(_G.EXHAUST_TOOLTIP2) end
        local clean = {}
        for _, l in ipairs(lines) do if type(l) == "string" then clean[#clean + 1] = strip(l) end end
        if #clean > 0 then x.lines = clean end
        if x.text then x.text = strip(x.text) end
    end
    return x
end

-- The faction the player set to "Show as Experience Bar" (the game's watched faction), or false.
local function collectWatch()
    local ok, d = CALL(function() return C_Reputation.GetWatchedFactionData() end)
    if not ok or type(d) ~= "table" then return false end
    local f = {}
    for _, k in ipairs(REPKEYS) do f[k] = P(d[k]) end
    local name, id = PS(f.name), PN(f.factionID)
    if not name or name == "" or not id or id == 0 then return false end
    -- r = the standing 1..8 (Hated .. Exalted), as UI Art asked; the bar is the Reputation tab's
    local w = { n = name, id = id, r = PN(f.reaction), bar = repBar(f, PN(one(UnitSex, "player"))) }
    -- The bar's text as the game writes it (reputationbar.lua Update): "Name 1200 / 6000", the name alone at the top
    -- of the track, with the account-wide label when the reputation is shared.
    local label = name
    if PB(one(function() return C_Reputation.IsAccountWideReputation(id) end)) then
        local acct = PS(_G.REPUTATION_STATUS_BAR_LABEL_ACCOUNT_WIDE)
        if acct then label = label .. " " .. acct end
    end
    local b = w.bar
    if b and b.p and type(b.cur) == "number" and type(b.max) == "number" then
        w.text = format("%s %d / %d", label, b.cur, b.max)
    else
        w.text = label
    end
    return w
end

-- ---------------------------------------------------------------- skills

-- skillsframe.lua's WEAPON_SKILL_LINES (true = ranged) and HIDDEN_SKILL_LINE_CATEGORIES.
local WEAPON = {
    [43] = false, [44] = false, [45] = true, [46] = true, [54] = false, [55] = false, [136] = false,
    [160] = false, [172] = false, [173] = false, [176] = true, [226] = true, [228] = true, [229] = false,
    [162] = false, [3014] = false,
}
local HIDDEN = { [7] = true }       -- Class Skills
local DEFENSE = 95
local SKKEYS = {
    "name", "skillID", "isHeader", "isCollapsed", "parentSkillLineID", "skillLineCategoryID",
    "rank", "maxRank", "modifier", "description",
}

-- The skill details' weapon rows (skillsframe.lua, AddWeaponSkillRows and the local helpers above it).
local function weaponRows(rank, mod, ranged)
    local lvl = PN(one(UnitLevel, "player"))
    if not lvl then return nil end
    local skill = rank + mod
    local function diff(off, s) return (lvl + off) * 5 - s end
    local function hit(off) return diff(off, skill) * -0.04 end
    local function signed(v)
        if v == 0 then v = 0.0 end                           -- no "-0.00%"
        local t = format("%.2f%%", v)
        if v > 0 then t = "+" .. t end
        return t
    end
    local function pct(v) return format("%.0f%%", v) end
    local function glanceChance(off)
        local s = math.min(lvl * 5, skill)
        return math.max(-100.0, math.min(100.0, (0.02 * diff(off, s) + 0.1) * 100.0))
    end
    local function glancePenalty(off)
        local d = diff(off, skill)
        local low = 1.30 - 0.05 * d
        if d > 10 then low = low + 0.1 end
        low = math.max(math.min(low, 0.91), 0.01)
        local high = 1.20 - 0.03 * d
        if d > 10 then high = high + 0.1 end
        high = math.min(math.max(high, 0.20), 0.99)
        return 100.0 - math.max(0.0, math.min(1.0, (low + high) / 2.0)) * 100.0
    end
    local w = {}
    w.h1 = PS(_G.WEAPON_SKILL_DETAIL_SAME_LEVEL_HEADER)
    w.same = fmt(ranged and _G.WEAPON_SKILL_DETAIL_SAME_LEVEL_RANGED or _G.WEAPON_SKILL_DETAIL_SAME_LEVEL,
        signed(hit(0)), signed(hit(0)))
    w.h2 = PS(_G.WEAPON_SKILL_DETAIL_BOSS_HEADER)
    if ranged then
        w.boss = fmt(_G.WEAPON_SKILL_DETAIL_BOSS_RANGED, signed(hit(3)), signed(hit(3)))
    else
        w.boss = fmt(_G.WEAPON_SKILL_DETAIL_BOSS, signed(hit(3)), signed(hit(3)), pct(glanceChance(3)), pct(glancePenalty(3)))
    end
    if w.same then w.same = strip(w.same) end
    if w.boss then w.boss = strip(w.boss) end
    return w
end

local function collectSkills()
    local n = PN(one(function() return C_SkillInfo.GetNumSkillLines() end))
    if not n then return false end
    local out = {}
    for i = 1, n do
        local ok, d = CALL(function() return C_SkillInfo.GetSkillLineInfo(i) end)
        if ok and type(d) == "table" then
            local f = {}
            for _, k in ipairs(SKKEYS) do f[k] = P(d[k]) end
            local isH = f.isHeader == true
            local show = f.parentSkillLineID == 0 and
                ((not isH and not HIDDEN[f.skillLineCategoryID]) or (isH and not HIDDEN[f.skillID]))
            if show then
                local e = { n = PS(f.name), id = PN(f.skillID), h = isH or nil, fold = f.isCollapsed == true or nil,
                            cat = PN(f.skillLineCategoryID), desc = PS(text(f.description)) }
                if e.desc == "" then e.desc = nil end
                if not isH then
                    e.rank, e.max, e.mod = PN(f.rank), PN(f.maxRank), PN(f.modifier)
                    if e.id == DEFENSE then
                        local okd, _, m = CALL(UnitDefenseSkill, "player")
                        m = PN(m)
                        if okd and m then e.mod = m end
                    end
                    if e.rank and e.max then
                        -- the bar's text, as SkillsEntryMixin:InitializeBarForStandardSkill writes it
                        if (e.mod or 0) == 0 then e.bt = e.rank .. " / " .. e.max
                        else e.bt = e.rank .. " (+" .. e.mod .. ") / " .. e.max end
                    end
                    if e.id and WEAPON[e.id] ~= nil and e.rank then e.w = weaponRows(e.rank, e.mod or 0, WEAPON[e.id]) end
                end
                out[#out + 1] = e
            end
        end
    end
    return out
end

-- ---------------------------------------------------------------- currency

local CURKEYS = {
    "name", "isHeader", "isHeaderExpanded", "currencyListDepth", "currencyID", "quantity", "iconFileID",
    "maxQuantity", "canEarnPerWeek", "quantityEarnedThisWeek", "maxWeeklyQuantity", "isShowInBackpack",
    "isTypeUnused", "isAccountWide", "discovered", "isAccountTransferable",
}

local function collectCur()
    local n = PN(one(function() return C_CurrencyInfo.GetCurrencyListSize() end))
    if not n then return false end
    local out = {}
    for i = 1, n do
        local ok, d = CALL(function() return C_CurrencyInfo.GetCurrencyListInfo(i) end)
        if ok and type(d) == "table" then
            local f = {}
            for _, k in ipairs(CURKEYS) do f[k] = P(d[k]) end
            local e = { n = PS(f.name), d = PN(f.currencyListDepth) }
            if f.isHeader == true then
                e.h = true
                if f.isHeaderExpanded == false then e.fold = true end
            else
                e.id, e.q, e.icon, e.max = PN(f.currencyID), PN(f.quantity), PN(f.iconFileID), PN(f.maxQuantity)
                e.wk, e.wkMax = PN(f.quantityEarnedThisWeek), PN(f.maxWeeklyQuantity)
                e.cew = f.canEarnPerWeek == true or nil
                e.bp = f.isShowInBackpack == true or nil
                e.unused = f.isTypeUnused == true or nil
                e.acct = f.isAccountWide == true or nil
                e.disc = f.discovered == true or nil
                if e.id then
                    e.desc = PS(text(one(function() return C_CurrencyInfo.GetCurrencyDescription(e.id) end)))
                    if e.desc == "" then e.desc = nil end
                end
                -- the detail pane as TokenDetailFrameMixin:Refresh writes it: the count, then label / value rows
                if e.q then e.qt = big(e.q) end
                local rows = {}
                if e.max and e.max > 0 then rows[#rows + 1] = { l = PS(_G.CURRENCY_DETAIL_TOTAL_CAP_LABEL) or "", v = big(e.max) } end
                if e.cew and e.wkMax and e.wkMax > 0 and e.wk then
                    rows[#rows + 1] = { l = PS(_G.CURRENCY_DETAIL_WEEKLY_CAP_LABEL) or "", v = big(e.wk) .. " / " .. big(e.wkMax) }
                end
                if #rows > 0 then e.rows = rows end
                if e.acct and not (f.isAccountTransferable == true) then e.note = PS(_G.ACCOUNT_LEVEL_CURRENCY) end
            end
            out[#out + 1] = e
        end
    end
    return out
end

-- ---------------------------------------------------------------- statistics

-- statisticsframe.lua (camelot), flattened: a category row, its statistics, then its sub-categories,
-- which is the order the game's tree shows. `d` is the depth. The value text is the game's: the number,
-- or "--" when the game's tonumber() gives nothing above 0.
local function collectStatistics()
    local ok, list = CALL(GetStatisticsCategoryList)
    if not ok or type(list) ~= "table" then return false end
    local keys = {}
    for k, v in pairs(list) do if type(k) == "number" and v then keys[#keys + 1] = k end end
    table.sort(keys)
    local roots, kids = {}, {}
    for _, k in ipairs(keys) do
        local id = PN(list[k])
        if id then
            local okc, _, parent = CALL(GetCategoryInfo, id)
            parent = okc and PN(parent) or nil
            if parent == nil or parent == -1 then
                roots[#roots + 1] = id
            else
                kids[parent] = kids[parent] or {}
                table.insert(kids[parent], id)
            end
        end
    end
    local out, done = {}, {}
    local function add(id, depth)
        if done[id] then return end
        done[id] = true
        out[#out + 1] = { n = PS(one(GetCategoryInfo, id)) or "?", h = true, d = depth, id = id }
        local num = PN(one(GetCategoryNumAchievements, id)) or 0
        for i = 1, num do
            local oks, q, skip, sid = CALL(GetStatistic, id, i)
            q, skip, sid = P(q), P(skip), PN(sid)
            if oks and skip ~= true and sid then
                local _, _, sname = CALL(GetAchievementInfo, sid)
                local v = (type(q) == "string" or type(q) == "number") and q ~= WC.SECRET and tonumber(q) or nil
                out[#out + 1] = { n = PS(sname) or "?", v = (v and v > 0) and tostring(v) or "--", d = depth + 1 }
            end
        end
        for _, c in ipairs(kids[id] or {}) do add(c, depth + 1) end
    end
    for _, id in ipairs(roots) do add(id, 0) end
    return out
end

-- ---------------------------------------------------------------- the game's window

-- the controller's state (see "the controller" below); read by collectWin
local ctl = { on = false, open = false, seq = 0, note = nil }

local TABKEY = {
    PaperDollFrame = "char", ReputationFrame = "rep", SkillsFrame = "skills",
    PVPRankFrame = "pvp", TokenFrame = "cur", StatisticsFrame = "stats",
}

local function shown(fr)
    if type(fr) ~= "table" then return false end
    return PB(one(function() return fr:IsShown() end)) == true
end

local function collectWin()
    local cf = _G.CharacterFrame
    if type(cf) ~= "table" then return false end
    local w = { open = shown(cf) }
    w.tab = TABKEY[PS(cf.activeSubframe) or ""]
    if ctl.on then w.ctl = true; w.via = ctl.via; w.pend = ctl.pend end
    if ctl.note then w.note = ctl.note end
    local pd = _G.PaperDollFrame
    if shown(_G.CharacterStatsPaneScrollBox) then w.side = "stats"
    elseif type(pd) == "table" and shown(pd.EquipmentManagerPane) then w.side = "sets"
    elseif shown(_G.CharacterStatsPanePetScrollBox) then w.side = "pet" end
    return w
end

-- The six side tabs' icons as fileDataIDs (characterframe.lua CHARACTER_MODE_TAB_ICONS; the Character tab is
-- the player's portrait, PvP the faction's crest). The _c60 icons are new in this game and not in the community
-- listfile, so the PC cannot name their IDs; the client can. For the room's art (UI Art).
local TAB_ICONS = {
    rep = "Interface/ICONS/INV_SideTab_Reputation2_c60", skills = "Interface/ICONS/Ability_Racial_JackofAllTrades",
    pvpA = "Interface/ICONS/INV_SideTab_Honor_Alliance_c60", pvpH = "Interface/ICONS/INV_SideTab_Honor_Horde_c60",
    cur = "Interface/ICONS/INV_SideTab_Currency_c60", stats = "Interface/ICONS/INV_SideTab_Stats_c60",
}
local function collectTabs()
    local t = {}
    for k, path in pairs(TAB_ICONS) do t[k] = PN(one(GetFileIDFromPath, path)) end
    return t
end

-- The game's own words for the room's fixed labels (titles, tab names, check boxes, the detail panes' prompts
-- and rows), read from the client so the room says what the top screen says, in the player's language. Constant.
local STRINGS = {
    "CHARACTER", "REPUTATION", "SKILLS", "PVP", "CURRENCY", "STATISTICS",
    "CHARACTER_FRAME_TAB_CHARACTER", "CHARACTER_FRAME_TAB_REPUTATION", "CHARACTER_FRAME_TAB_SKILLS",
    "CHARACTER_FRAME_TAB_PVP", "CHARACTER_FRAME_TAB_CURRENCY", "CHARACTER_FRAME_TAB_STATISTICS",
    "PAPERDOLL_SIDEBAR_STATS", "PAPERDOLL_EQUIPMENTMANAGER", "PET",
    "AT_WAR", "MOVE_TO_INACTIVE", "SHOW_FACTION_ON_MAINSCREEN", "REPUTATION_DETAIL_SELECT_PROMPT",
    "REPUTATION_TOOLTIP_ACCOUNT_WIDE_LABEL", "SKILL_DETAIL_SELECT_PROMPT", "TOKEN_DETAIL_SELECT_PROMPT",
    "UNUSED", "SHOW_ON_BACKPACK", "CURRENCY_DETAIL_TOTAL_CAP_LABEL", "CURRENCY_DETAIL_WEEKLY_CAP_LABEL",
    "ACCOUNT_LEVEL_CURRENCY",
    -- the gear sets' buttons and question (f-equipment-manager): the game's Equipment Manager pane and its save dialog
    "EQUIPSET_EQUIP", "SAVE", "YES", "NO", "CONFIRM_SAVE_EQUIPMENT_SET", "ERR_NOT_IN_COMBAT",
}
-- where the app taps to open / close the room's controller: the pad's published grid and the two fixed columns of `aurapick`
local function collectGrid()
    local pad = WC.pad
    if type(pad) ~= "table" or type(pad.describe) ~= "function" then return false end
    local ok, d = pcall(pad.describe)
    if not ok or type(d) ~= "table" then return false end
    return { row = "aurapick", open = pad.slotFor("aura", "open"), close = pad.slotFor("aura", "close"), pad = d,
             -- the game's own window, opened / closed the game's way: SECURE cells 1 / 2 of my own `auracancel` row (below)
             secure = { row = "auracancel", open = 1, close = 2 } }
end

local function collectStrings()
    local t = {}
    for _, k in ipairs(STRINGS) do
        local v = PS(_G[k])
        if v then t[k] = strip(v) end
    end
    return t
end

-- ---------------------------------------------------------------- emission

local PARTS = BARS_ONLY and { "xp", "watch" }
    or { "win", "xp", "watch", "who", "st", "pet", "petinfo", "sets", "rep", "sk", "cur", "sx", "tabs", "str", "grid" }
local COLLECT = {
    win = collectWin, who = collectWho, st = function() return collectStats("player") end,
    pet = collectPet, petinfo = collectPetInfo, sets = collectSets, rep = collectRep, sk = collectSkills, cur = collectCur,
    sx = collectStatistics, tabs = collectTabs, xp = collectXp, watch = collectWatch, str = collectStrings, grid = collectGrid,
}

local last, cache = {}, {}          -- per part: the encoded form last sent, and the value
local dirty = {}
for _, p in ipairs(PARTS) do dirty[p] = true end

-- The statistics tree is hundreds of calls and CRITERIA_UPDATE fires on every kill: at most one
-- collection per SX_EVERY seconds; a change inside that window is picked up at its end.
local SX_EVERY = 10
local sxAt, sxLater = -1e9, false

-- Collect the dirty parts, send those that changed (all known parts when forced). Does NOT push:
-- callers decide, so a heartbeat ride-along costs no flush of its own. True when something was sent.
local function emit(force)
    tally.secret, tally.threw = 0, 0
    local c, any, errs = {}, false, nil
    for _, p in ipairs(PARTS) do
        if dirty[p] or cache[p] == nil then
            dirty[p] = nil
            if p == "sx" then sxAt = GetTime() end
            local ok, v = pcall(COLLECT[p])
            if ok then
                if v == nil then v = false end
                local okk, key = pcall(WC.encode, v)
                if okk then
                    cache[p] = v
                    if key ~= last[p] then last[p] = key; c[p] = v; any = true end
                end
            else
                errs = errs or {}
                errs[#errs + 1] = p
                if cache[p] == nil then cache[p] = false end
            end
        end
        local again = force == true or (type(force) == "table" and force[p])
        if again and c[p] == nil and cache[p] ~= nil then c[p] = cache[p]; any = true end
    end
    if not any and not errs then return false end
    WC.send(WC.encode({
        type = "character", v = CV, c = c, full = (force == true) or nil, combat = inCombat() or nil,
        sec = { secret = tally.secret, threw = tally.threw }, err = errs,
    }))
    return true
end

local queued = false
local function soon(delay)
    if queued then return end
    queued = true
    C_Timer.After(delay or 0.5, function()
        queued = false
        if emit(false) then WC.push() end
    end)
end

local function mark(parts)
    for _, p in ipairs(parts) do
        if p == "sx" then
            local wait = SX_EVERY - (GetTime() - sxAt)
            if wait > 0 then
                if not sxLater then
                    sxLater = true
                    C_Timer.After(wait, function() sxLater = false; dirty.sx = true; soon() end)
                end
            else
                dirty.sx = true
            end
        else
            dirty[p] = true
        end
    end
    soon()
end

-- ---------------------------------------------------------------- events

local ALL = PARTS
local STATS = { "st" }
-- event -> parts. Unit events are filtered to the player (and the pet for `pet`) below.
local EVENTS = {
    PLAYER_ENTERING_WORLD = ALL,
    PLAYER_LEVEL_UP = { "who", "st", "pet", "sk", "xp" },
    UNIT_LEVEL = { "who", "st", "sk", "xp" },
    PLAYER_XP_UPDATE = { "xp" }, UPDATE_EXHAUSTION = { "xp" }, PLAYER_UPDATE_RESTING = { "xp" },
    ENABLE_XP_GAIN = { "xp" }, DISABLE_XP_GAIN = { "xp" }, PLAYER_MAX_LEVEL_UPDATE = { "xp" },
    UNIT_NAME_UPDATE = { "who" },
    PLAYER_PVP_RANK_CHANGED = { "who" },
    PLAYER_TALENT_UPDATE = { "who", "st" },
    ACTIVE_TALENT_GROUP_CHANGED = { "who", "st" },
    -- the paper doll's own list (paperdollframe.lua OnLoad), less the model / portrait / title ones
    UNIT_STATS = STATS, UNIT_RANGEDDAMAGE = STATS, UNIT_RANGED_ATTACK_POWER = STATS, UNIT_ATTACK = STATS,
    UNIT_SPELL_HASTE = STATS, UNIT_RESISTANCES = STATS, UNIT_DAMAGE = STATS, UNIT_ATTACK_SPEED = STATS,
    UNIT_MAXHEALTH = STATS, UNIT_AURA = STATS, COMBAT_RATING_UPDATE = STATS, SPEED_UPDATE = STATS,
    SPELL_POWER_CHANGED = STATS, PLAYER_DAMAGE_DONE_MODS = STATS, UNIT_MAXPOWER = STATS,
    UNIT_DISPLAYPOWER = STATS, PLAYER_EQUIPMENT_CHANGED = { "st", "pet", "sets" },
    SKILL_LINES_CHANGED = { "st", "sk" },
    PLAYER_REGEN_DISABLED = { "st", "pet", "eqs" }, PLAYER_REGEN_ENABLED = { "st", "pet" },
    UNIT_PET = { "pet", "petinfo" }, PET_STATS_UPDATE = { "pet" }, PET_UI_UPDATE = { "pet", "petinfo" },
    UNIT_PET_EXPERIENCE = { "petinfo" }, UNIT_HAPPINESS = { "petinfo" }, PET_BAR_UPDATE = { "petinfo" },
    EQUIPMENT_SETS_CHANGED = { "sets" }, EQUIPMENT_SWAP_FINISHED = { "sets" },
    UPDATE_FACTION = { "rep", "watch" }, MAJOR_FACTION_RENOWN_LEVEL_CHANGED = { "rep", "watch" },
    CURRENCY_DISPLAY_UPDATE = { "cur" }, ACCOUNT_CHARACTER_CURRENCY_DATA_RECEIVED = { "cur" },
    CRITERIA_UPDATE = { "sx" }, ACHIEVEMENT_EARNED = { "sx" },
}
-- bars only: keep just the events that move the XP bar or the watched faction, and just those two parts
if BARS_ONLY then
    local BAR = { xp = true, watch = true }
    for e, parts in pairs(EVENTS) do
        local only = {}
        for _, p in ipairs(parts) do if BAR[p] then only[#only + 1] = p end end
        EVENTS[e] = #only > 0 and only or nil
    end
end
-- unit events that name a unit as their first argument
local UNIT_EVENT = {
    UNIT_LEVEL = true, UNIT_NAME_UPDATE = true, UNIT_STATS = true, UNIT_RANGEDDAMAGE = true,
    UNIT_RANGED_ATTACK_POWER = true, UNIT_ATTACK = true, UNIT_SPELL_HASTE = true, UNIT_RESISTANCES = true,
    UNIT_DAMAGE = true, UNIT_ATTACK_SPEED = true, UNIT_MAXHEALTH = true, UNIT_AURA = true,
    UNIT_MAXPOWER = true, UNIT_DISPLAYPOWER = true, UNIT_PET = true, PLAYER_XP_UPDATE = true,
}

local f = CreateFrame("Frame")
f:SetScript("OnEvent", function(_, e, unit)
    local parts = EVENTS[e]
    if not parts then return end
    if UNIT_EVENT[e] and e ~= "UNIT_PET" then
        if unit == "pet" then
            if BARS_ONLY then return end
            parts = { "pet", "petinfo" }
        elseif unit ~= "player" then return end
    end
    mark(parts)
end)
local missing = {}
for e in pairs(EVENTS) do
    local ok, registered = pcall(function() return f:RegisterEvent(e) end)
    if not ok or registered == false then missing[#missing + 1] = e end
end
table.sort(missing)
if #missing > 0 then
    pcall(function() WC.send(WC.encode({ type = "cap", character = CV, missingEvents = missing })) end)
end

-- ---------------------------------------------------------------- the controller (SPEC 8)
-- While the game's Character window is open, the controller moves to the bottom screen: the room takes it through the
-- interact session's SHARED CAPTURE (WowCompanion.capture, one holder at a time; it never takes in combat, lets go on
-- PLAYER_REGEN_DISABLED, and an NPC window takes it over), exactly as Bags does for the bag windows. While held, the capture
-- puts the game's window to alpha 0 (only once the capture is proven or trusted: interact's rule).
-- The room's LAYOUT is the app's (two columns, the doll, the side tabs), so the presses go to the app as they are: the
-- D-pad and A / X / Y in a `pad` part, one document per press. B (user ruling: B leaves the cursor and goes Home):
--   capture version 2 (interact, AGENT_LOG 2c60f6c): B is PASSED to the game (`pass = {"PAD2"}`), the game's own B closes its
--   own window in its own code, and the window's OnHide gives the controller back with `release("character", "B")`: interact's
--   shared "B let go -> Home" takes the bottom screen Home. Not yet proven on the device: that the game's B closes the window
--   while it is invisible and the capture holds the other keys.
--   older captures: B is ours; it lets the controller go (note "back": the room's own way Home), and the game's window stays
--   open until the game's own B (two presses: the fallback).
-- THIS FILE NEVER OPENS OR CLOSES A GAME WINDOW (tracking session's safety fact, 2026-09-28 23:05: Bags' CloseAllBags() /
-- OpenAllBags() called from addon code tainted the game's bag code and its controller action bar hit a protected function
-- 2,049 times in a minute). So B does NOT call HideUIPanel: the capture's release gives the window its alpha back and the
-- keys back to the game, and the game's own B closes its own window, untainted.
local function capture() return WC.capture end        -- set by WowCompanionInteract when it loads

-- an NPC or interaction window owns the controller (the same list as Bags'): the room does not take it then
local NPC_FRAMES = { "MerchantFrame", "GossipFrame", "QuestFrame", "BankFrame", "MailFrame", "TradeFrame",
                     "AuctionHouseFrame", "GuildBankFrame", "ClassTrainerFrame" }
local function npcWindowOpen()
    for _, n in ipairs(NPC_FRAMES) do
        if PB(one(function() local f = _G[n]; return f and f:IsShown() end)) then return true end
    end
    return false
end

-- tell the app the controller's state at once (the `win` part)
local function note(why) ctl.note = why; dirty.win = true; soon(0) end

local PAD_KEYS = { PADDUP = true, PADDDOWN = true, PADDLEFT = true, PADDRIGHT = true, PAD1 = true, PAD3 = true, PAD4 = true }
local function sendPad(key)
    ctl.seq = ctl.seq + 1
    WC.send(WC.encode({ type = "character", v = CV, c = { pad = { seq = ctl.seq, k = key } } }))
    WC.push()
end

-- B: the user's ruling (2026-09-28 ~23:14, every room): "Pressing be to leave the cursor should take me back to the home by
-- default for everything". The note "back" (not "closed", which any other close gives) tells the app to go to Home.
-- Let go through the capture. False ONLY when the capture answers "tap leave" (capture v10, interact i81: a room holding through
-- a fight is let go by Interact's leave alone, which the app taps; nothing of ours retries it). A take still PENDING in a fight is
-- given up by the same release. True otherwise (let go, or not held at all).
local function release(why)
    local cap = capture()
    if not cap then return true end
    local ok, done, msg = pcall(cap.release, "character", why)
    return not (ok and done == false and msg == "tap leave")
end
local function holderIsMe(cap)
    cap = cap or capture()
    local st = cap and cap.state and select(2, pcall(cap.state))
    return type(st) == "table" and st.holder == "character"
end

local function letGo()
    if not release("B") then note("back-fight-leave"); return end
    ctl.on, ctl.via, ctl.pend = false, nil, nil
    note("back")
end

-- ---------------------------------------------------------------- THE GEAR SETS' BUTTONS (f-equipment-manager)
-- The user's pick (15 Oct, "Equip + Save"): the game's Equipment Manager pane has Equip and Save under its list of sets; the room
-- gets the same two for the sets the character already has. New / rename / delete stay in the game's own window for now.
--   equip:<id>  C_EquipmentSet.UseEquipmentSet(id): what the game's Equip button does (EquipmentManager_EquipSet), called on the
--               game's engine directly, never through the game's UI code (taint: TapPad's rule).
--   save:<id>   the game ASKS first (CONFIRM_SAVE_EQUIPMENT_SET, a yes / no); so does the room: the question is ours (`eqs.ask`),
--               the app draws it, `yes` saves (C_EquipmentSet.SaveEquipmentSet(id): the set takes the gear worn now, its icon kept),
--               `no` or B forgets it. While it asks, B is the room's (the capture's pass is lifted), so B is one step back.
-- Both refused in a fight, as the game refuses changing armour then (its words: ERR_NOT_IN_COMBAT). Every tap is answered
-- (`eqs.ack`, numbered) so the app can say why nothing happened.
local eqs = { ask = nil, ack = nil, seq = 0 }
local function eqWords(g, fallback) local v = PS(_G[g]); return (v and v ~= "") and v or fallback end
local function eqAck(op, id, ok, why)
    eqs.seq = eqs.seq + 1
    eqs.ack = { seq = eqs.seq, op = op, id = id, ok = ok and true or false, why = why }
    dirty.eqs = true
    soon(0)
end
local function setName(id)
    local ok, name = CALL(function() return C_EquipmentSet.GetEquipmentSetInfo(id) end)
    return ok and PS(name) or nil
end
-- B is ours while the question is up (capture v2+ passes it to the game while the game's window is open)
local function passB(on)
    local cap = capture()
    if not (cap and type(cap.pass) == "function" and holderIsMe(cap) and ctl.via == "window") then return end
    if (tonumber(cap.version) or 1) < 2 then return end
    pcall(cap.pass, "character", on and { "PAD2" } or {})
end
local function eqForget()
    if not eqs.ask then return false end
    eqs.ask = nil
    passB(true)
    dirty.eqs = true
    soon(0)
    return true
end
local function onSetTap(key)
    if type(key) ~= "string" then return eqAck("?", nil, false, "nothing on that cell") end
    local op, sid = key:match("^(%a+):(%d+)$")
    sid = tonumber(sid)
    if key == "no" then if not eqForget() then eqAck("no", nil, false, "nothing asked") end return end
    if inCombat() then eqForget(); return eqAck(op or key, sid, false, eqWords("ERR_NOT_IN_COMBAT", "You can't do that while in combat")) end
    if key == "yes" then
        local a = eqs.ask
        if not a then return eqAck("yes", nil, false, "nothing asked") end
        eqForget()
        local ok, done = CALL(function() return C_EquipmentSet.SaveEquipmentSet(a.id) end)
        dirty.sets = true
        return eqAck("save", a.id, ok and done ~= false, (not ok) and "the game refused" or nil)
    end
    if not sid or not setName(sid) then return eqAck(op or key, sid, false, "no such set") end
    if op == "equip" then
        eqForget()
        local ok, done = CALL(function() return C_EquipmentSet.UseEquipmentSet(sid) end)
        dirty.sets = true
        return eqAck("equip", sid, ok and done ~= false, (not ok) and "the game refused" or nil)
    elseif op == "save" then
        local name = setName(sid)
        local fmtd = fmt(_G.CONFIRM_SAVE_EQUIPMENT_SET, name)
        eqs.ask = { id = sid, n = name, text = fmtd and strip(fmtd) or ('Save "' .. name .. '"?') }
        passB(false)
        dirty.eqs = true
        soon(0)
        return
    end
    eqAck(op or key, sid, false, "unknown")
end
local function collectEqs()
    local pad = WC.pad
    if eqs.ask and inCombat() then eqs.ask = nil end        -- a fight drops the question (the game refuses the save then)
    local row = { ask = eqs.ask or false, ack = eqs.ack }
    if pad and type(pad.slotFor) == "function" then
        row.row = EQ_ROW; row.yes = eqCell("yes"); row.no = eqCell("no")
    end
    return row
end
COLLECT.eqs = collectEqs
if not BARS_ONLY then PARTS[#PARTS + 1] = "eqs"; dirty.eqs = true end
-- a tap on one of the gear sets' cells of "auracancel" (Auras.lua's onClick hands it here): the key that cell holds
local function onSetCell(col)
    local t = WC.pad and WC.pad.ns and WC.pad.ns.eqset
    local key = t and type(col) == "number" and t.byCol[col - EQ_FIRST] or nil
    onSetTap(key)
end

local function onPress(key)
    if ctl.pend then ctl.pend = nil; note("taken") end      -- a press reaches us: the app's ENTER landed
    if key == "PAD2" then
        if eqForget() then return end                       -- the question first: B is one step back
        letGo(); return
    end
    if PAD_KEYS[key] then sendPad(key) end
end
-- capture v8 (interact, the user's ruling: a room KEEPS the controller through a fight; leaving it gives the next press to the game):
-- a B left to the game becomes the capture's own leave in a fight, lost("B"), and the game's window stays open. The player LEFT:
-- stay off until that window closes, or the 1 s retake would pull the controller (and the app) back after the fight.
-- c12 (Home's consistency pass; Social's LEFT rule, Group Finder g10): lost for "left" (Interact's generic leave) or "taken" (another
-- room's or window's take) is the room LEFT as well: never taken again by itself. Before, only "B" stayed off, so with the game's
-- window still open the 1 s tick re-took the controller once the other holder let go, and the app jumped back into this room
-- (MainActivity shows CHARACTER on a new hold by the window). The window's next show, or the app's next way in (touchOpen), clears it.
local LEFT = { B = true, left = true, taken = true }
local function onLost(why)
    eqForget()
    if LEFT[why] and ctl.open then ctl.stayOff = true end
    ctl.on, ctl.via, ctl.pend = false, nil, nil
    note("lost-" .. tostring(why))
end

-- WHAT THE CAPTURE MAKES INVISIBLE (alpha only, given back on release): the game's Character window, and the bag windows,
-- because the game opens the BAGS WITH the Character window (measured on the Thor 2026-09-30 12:17:29). The user's ruling
-- (12:2x, via tracking): the Character room gets the controller then, the bags stay reachable by touch in the Bags room; so
-- they leave the top screen too. The same names as Bags' own list (Bags.lua BAG_FRAMES).
local HIDE = { "CharacterFrame", "ContainerFrameCombinedBags" }
for i = 1, 13 do HIDE[#HIDE + 1] = "ContainerFrame" .. i end

-- THE GAME'S OWN WINDOW, OPENED AND CLOSED THE GAME'S WAY (the user, 2026-10-01 via Home: "Every room opens the game's own window
-- behind it, as Professions does"; "it should act the EXACT same for all rooms"). Two SECURE cells of my own `auracancel` row
-- (columns 1 and 2; column 0 stays the buff cancel; Auras.lua owns the row and hands these two here):
--   open  = a secure CLICK on the game's own Character button (CharacterMicroButton). It TOGGLES the window, so the cell carries no
--           action while the window is open (set out of combat; the app also taps it only while the window is closed).
--   close = a secure CLICK on the window's own close button (CharacterFrameCloseButton: hidden in controller mode, a click by name
--           still reaches it; on a closed window it does nothing). A PreClick marks it, so the OnHide that follows lets go "closed".
-- This file only hands the game's buttons to the template: it never calls anything on them (no Toggle / Show / Hide from here).
local openCell, closeCell = nil, nil
local closingAt = -1e9
local function armOpen()
    if not openCell or InCombatLockdown() then return end
    local b = _G.CharacterMicroButton
    local on = type(b) == "table" and not shown(_G.CharacterFrame)
    pcall(openCell.SetAttribute, openCell, "type", on and "click" or nil)
    pcall(openCell.SetAttribute, openCell, "clickbutton", on and b or nil)
end
local function initOpenCell(btn) openCell = btn; armOpen() end
local function initCloseCell(btn)
    closeCell = btn
    local b = _G.CharacterFrameCloseButton
    local on = type(b) == "table"
    pcall(btn.SetAttribute, btn, "type", on and "click" or nil)
    pcall(btn.SetAttribute, btn, "clickbutton", on and b or nil)
    pcall(btn.HookScript, btn, "PreClick", function() closingAt = GetTime() end)
end

-- TWO WAYS TO HOLD THE CONTROLLER (the user, 2026-09-30 via tracking: "On the charactor sheet the dpad doesn't do anything,
-- its using my normal dpad stuff on the home"):
--   "window"  the GAME's Character window is open: its window and the bags are made invisible (alpha), and B goes to the game,
--             which closes its own window (capture v2+); our OnHide then lets go with "B" -> Home.
--   "touch"   the room was opened in the APP (the bar): the app taps the `open` cell (Spellbook's pattern; the cell is a fixed
--             key in my own `aurapick` row, the pad owner's ruling: the pad is full). Nothing to hide; B reaches us and lets go
--             with "B" -> Home. The `close` cell (the app leaving the room) lets go without "B".
-- The game's window opening while the room holds by touch takes again with the window's spec (the holder may take again).
local function specFor(via, cap)
    local spec = { press = onPress, lost = onLost }
    if via == "window" then
        local hide = {}
        for _, n in ipairs(HIDE) do if _G[n] then hide[#hide + 1] = n end end
        spec.hide = hide
        if (tonumber(cap.version) or 1) >= 2 then spec.pass = { "PAD2" } end     -- B goes to the game untouched
        -- capture v9 (interact i78): `leave` = the window's own close, which interact's generic leave and fight leave click first
        -- (validated: it must name an existing button, so only once the game has made it); `window` = this take is because the
        -- GAME's window opened: it pre-empts a room that still holds (that room gets lost("taken")); never in a fight
        if (tonumber(cap.version) or 1) >= 9 then
            if type(_G.CharacterFrameCloseButton) == "table" then spec.leave = "CharacterFrameCloseButton" end
            spec.window = true
        end
    end
    return spec
end

-- THE ROOM IN A FIGHT (SPEC_COMBAT items 4-12, the user's sign-off 2026-10-01; capture v10 = interact i81): no combat gate of
-- our own. A take in a fight is ok + PENDING until the app taps the capture's ENTER cell (doc.enter); the room counts as held
-- meanwhile (the app shows it; a leave gives the pending take up) and becomes real when the capture names us. A capture that
-- cannot hold in a fight refuses "combat": its `refused` pulses Home's red flash (capture v8), so the player sees why.
-- Capture v11 (interact i82, the TAKE QUEUE): a take that finds another holder (a room or an NPC window) answers ok and WAITS
-- (out of a fight too), granted when that holder lets go; a later requester wins (we get lost("taken")); our release gives the wait
-- up. So any ok that does not make us the holder is a wait: the same `pend` as a fight's pending ENTER.
-- [force] = take again even while held (the holder may: a new spec), e.g. a window closed in a fight -> the touch spec after it.
local function tryTake(via, force)
    via = via or "window"
    if ctl.on and not force and (ctl.via == via or via == "touch") then return end    -- held already; a touch-open changes nothing
    local cap = capture()
    if not cap then note("take-skip-nocapture"); return end       -- interact's addon absent or older
    if npcWindowOpen() then note("take-skip-window"); return end
    local ok, got, why = pcall(cap.take, "character", specFor(via, cap))
    if ok and got then
        ctl.on, ctl.via = true, via
        ctl.pend = (not holderIsMe(cap)) or nil
        note(ctl.pend and "taken-pending" or (via == "touch" and "taken-touch" or "taken"))
    else
        why = ok and why or "error"
        note("take-refused-" .. tostring(why))
        if why == "combat" and type(cap.refused) == "function" then pcall(cap.refused, "character") end
    end
end

-- The game opens the bags WITH this window, and Bags may win the capture by a few ms (the Thor 2026-09-30 12:17:29); Bags then
-- hands it back on this window's OnShow (its rule since the user's ruling). Whichever hook runs first, one retry on the NEXT
-- frame takes it, so the bags given back their alpha by Bags' release are hidden again within one frame, not at the 1 s tick.
local function onWindowShown()
    ctl.open, ctl.stayOff = true, nil
    closingAt = -1e9
    armOpen()
    tryTake("window")
    if ctl.via ~= "window" and not InCombatLockdown() and C_Timer and C_Timer.After then
        C_Timer.After(0, function() if ctl.open and ctl.via ~= "window" and not InCombatLockdown() then tryTake("window") end end)
    end
end
local function onWindowHidden()
    ctl.open, ctl.stayOff = false, nil
    -- the app's close cell (its PreClick marks the time) = the app left the room; the mark is used once, so a later B is a B
    local ours = GetTime() - closingAt < 1.0
    closingAt = -1e9
    armOpen()
    if not ctl.on or ctl.via ~= "window" then return end    -- B already let go, or the room holds by touch (it keeps it)
    local cap = capture()
    local v2 = cap and (tonumber(cap.version) or 1) >= 2
    -- ours: "closed", so Home does not take the bottom screen; otherwise, version 2: while held, only the game's own B (passed
    -- through) can close its window from the controller: "B". In a fight (capture v10) the hold stays: Interact's leave lets it
    -- go (its lost("B") follows this OnHide in the same click), and a window closed any other way leaves the room usable
    if not release(ours and "closed" or (v2 and "B" or nil)) then note("closed-in-fight"); return end
    ctl.on, ctl.via, ctl.pend = false, nil, nil
    note(ours and "closed-app" or (v2 and "released-B" or "closed"))
end

-- an NPC or a fight took the keys: take them back once the capture is free and the window is still open
if not BARS_ONLY and C_Timer and C_Timer.NewTicker then
    C_Timer.NewTicker(1.0, function()
        if ctl.pend then
            if holderIsMe() then ctl.pend = nil; note("taken") end
            return
        end
        if InCombatLockdown() then return end
        if not ctl.open or ctl.via == "window" or ctl.stayOff then return end
        local cap = capture()
        local st = cap and cap.state and select(2, pcall(cap.state))
        if type(st) == "table" and (st.holder == nil or st.holder == "character") then tryTake("window") end
    end)
end

local function touchOpen()
    -- the game's window is open already (the player left the room in the app and it stayed off since): coming back takes it the
    -- window's way (its frames hidden, B passed), so the room is the same however it was entered
    if ctl.open then ctl.stayOff = nil; tryTake("window"); return ctl.on end
    tryTake("touch")
    return ctl.on
end
local function touchClose()
    -- Home's ONE RULE (2026-09-30): the app's `close` must free the capture whenever it names us, even if this file's own flag
    -- says otherwise (a guest once kept the controller after its room was left), and never by a game action
    local cap0 = capture()
    local st = cap0 and cap0.state and select(2, pcall(cap0.state))
    local named = type(st) == "table" and st.holder == "character"
    if not ctl.on and not named then return false end
    -- not "B": the app changed room itself. In a fight a real hold is Interact's leave's to end (the app taps it): no retry here
    if not release("closed") then note("close-fight-leave"); return false end
    -- the player left the room in the APP while the game's window is still open: stay off until that window closes (else the
    -- 1 s retake would pull the controller, and the app with it, straight back into the room)
    if ctl.via == "window" then ctl.stayOff = true end
    ctl.on, ctl.via, ctl.pend = false, nil, nil
    note("closed-touch")
    return true
end
-- the secure cells' taps (TapPad's post-hook, after the game's own click ran): the window's OnShow takes the controller, as for the
-- game's own key; the window's OnHide lets go. A room still held without the window (an older touch hold) lets go on close too.
-- A tap that changed nothing says so (the window's own notes say what a tap that worked did).
-- The open cell carries NO action in a fight (disarmed before the lockdown, below): one Thor tap fires a secure cell twice (Auras
-- a4, measured), and with its attribute frozen a click of the game's TOGGLE would open the window and shut it again. So a tap
-- that opened nothing (in a fight, or a click that failed) gives the room the controller without the game's window: the room is
-- usable all the same, never a dead button (SPEC_COMBAT 11).
local function onOpenTap() if not ctl.open then note("open-tap-none"); touchOpen() end end
local function onCloseTap()
    if ctl.open then note("close-tap-none") end
    C_Timer.After(0.2, function() if ctl.on and not ctl.open then touchClose() end end)
end
-- A FIGHT STARTS (before the lockdown): the open cell drops its action (see onOpenTap). IT ENDS: the cell is set right again, and
-- one frame later (after the capture's own fight end) the room's state is squared with the capture's:
--   * a take still waiting (a fight's pending ENTER, which the capture drops at the fight's end; or a v11 queued wait, which it
--     keeps): the room was not left (a leave lets go), so it asks again: held at once, queued again (the same id: no loss), or
--     refused (then not held)
--   * held "window" but the window closed in the fight: take again as "touch", so B is the room's own way Home (a B passed to
--     the game would close nothing)
--   * the window open and nobody holding (and the player did not leave it): take it, as the 1 s tick would
local function afterFight()
    if InCombatLockdown() then return end
    armOpen()
    if ctl.on then
        if not holderIsMe() then
            local via = ctl.via
            if via ~= "window" or not ctl.open then via = "touch" end
            ctl.on, ctl.via, ctl.pend = false, nil, nil
            tryTake(via)
            if not ctl.on then note("lost-fight-end") end
        elseif ctl.via == "window" and not ctl.open then
            tryTake("touch", true)
        end
    end
    if ctl.open and not ctl.on and not ctl.stayOff then tryTake("window") end
end
if not BARS_ONLY then
    local fr = CreateFrame("Frame")
    pcall(fr.RegisterEvent, fr, "PLAYER_REGEN_DISABLED")
    pcall(fr.RegisterEvent, fr, "PLAYER_REGEN_ENABLED")
    fr:SetScript("OnEvent", function(_, event)
        if event == "PLAYER_REGEN_DISABLED" then
            if openCell and not InCombatLockdown() then pcall(openCell.SetAttribute, openCell, "type", nil) end
        elseif C_Timer and C_Timer.After then
            C_Timer.After(0, afterFight)
        else
            afterFight()
        end
    end)
end

if not BARS_ONLY and type(WC.pad) == "table" and type(WC.pad.slotFor) == "function" then
    WC.pad.slotFor("aura", "open")
    WC.pad.slotFor("aura", "close")
    -- a new UI scale moves every cell: the app must tap the new place
    if type(WC.pad.onLayout) == "function" then WC.pad.onLayout(function() dirty.grid = true; soon() end) end
end

-- THE GAME'S WINDOW: which tab is open goes out at once (the app follows it), not after the 0.5 s
-- coalescing. Hooks only: HookScript and hooksecurefunc run after the game's own code and change nothing.
local hooked = false
local function hookWindow()
    if hooked then return end
    local cf = _G.CharacterFrame
    if type(cf) ~= "table" or type(cf.HookScript) ~= "function" then return end
    hooked = true
    local function now() dirty.win = true; soon(0) end
    pcall(cf.HookScript, cf, "OnShow", function() dirty.win = true; dirty.st = true; onWindowShown(); soon(0) end)
    pcall(cf.HookScript, cf, "OnHide", function() onWindowHidden(); now() end)
    if type(cf.ShowSubFrame) == "function" then pcall(hooksecurefunc, cf, "ShowSubFrame", now) end
    if type(_G.PaperDollFrame_SetSidebar) == "function" then pcall(hooksecurefunc, "PaperDollFrame_SetSidebar", now) end
end
if not BARS_ONLY then                       -- bars only: the game's Character window is not touched at all
    hookWindow()
    if not hooked then
        local w = CreateFrame("Frame")
        w:RegisterEvent("ADDON_LOADED")
        w:RegisterEvent("PLAYER_LOGIN")
        w:SetScript("OnEvent", function(self)
            hookWindow()
            if hooked then self:UnregisterAllEvents() end
        end)
    end
end

-- RIDE THE CORE HEARTBEAT (as Quests does): anything sent in this post-hook lands in the snapshot's own
-- pad burst, so re-sending every part every 15 s costs no flush of its own.
local RIDE = { heartbeat = true, login = true, zone = true, manual = true }
local HEAVY_EVERY = 4
local beats = 0
local LIGHT = { win = true, xp = true, watch = true, who = true, st = true, pet = true, petinfo = true, sets = true,
                tabs = true, str = true, grid = true, eqs = true }
if type(WC.snapshot) == "function" then
    hooksecurefunc(WC, "snapshot", function(reason)
        if not RIDE[reason] then return end
        -- the heartbeat heals a restarted app: the small parts every time; the long lists (statistics, reputations,
        -- skills, currency) every HEAVY_EVERY-th beat only (a real statistics tree is hundreds of rows), and all of it
        -- on login / zone / manual. A change still goes out at once, whatever the beat.
        if reason == "heartbeat" then
            beats = beats + 1
            if beats % HEAVY_EVERY ~= 0 then pcall(emit, LIGHT) return end
        end
        pcall(emit, true)
    end)
end

WC.character = { version = CV, barsOnly = BARS_ONLY, emit = emit, collect = COLLECT, standIn = standIn, ctl = ctl,
                  touchOpen = touchOpen, touchClose = touchClose,
                  initOpenCell = initOpenCell, initCloseCell = initCloseCell, onOpenTap = onOpenTap, onCloseTap = onCloseTap,
                  onSetCell = onSetCell, eqFirst = EQ_FIRST, eqCells = EQ_CELLS }   -- standIn / ctl for tools/test_character.py; the touch pair for Auras.lua
