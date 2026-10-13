-- WowCompanionPvp / Pvp.lua
-- The PVP feed: the PvP rank, the honor counts, the battleground list and the battleground queues.
--
-- WHAT THE GAME HAS (read from this client's own UI source, 2026-09-28): no PvP window at all.
-- Blizzard_PVPUI and Blizzard_GroupFinder say ExcludeLoadGameType: camelot. The PvP RANK is tab 4 of
-- the Character window (PVPRankFrame, blizzard_uipanels_game/camelot/pvprankframe.lua), fed by a renown
-- track, faction 2800. Queueing is at a battlemaster NPC: the game joins at once, with no list. While
-- queued, the minimap "eye" (QueueStatusButton) shows the queue. The user's answers: the rank is a tab
-- of the Character room, whose contents this feed supplies; the queue shows as status + Leave.
--
-- READ-ONLY, ON PURPOSE. This file registers events and reads. It creates no button, no pad row and
-- no slash command, hooks no game frame, and calls nothing that changes the game. Leave Queue is NOT
-- here: whether an addon may call AcceptBattlefieldPort from a tap is not documented in this install,
-- and a wrong guess raises "action blocked". It is tried once, on the device, after the user's yes.
--
-- WHAT IT SENDS: one {"type":"pvp"} document, only when something the rooms show changed, and again
-- on the core's heartbeat (riding its flush, so that costs nothing). Waiting times and the season
-- countdown are NOT in the change test: they move every second, and the app counts them on itself
-- from the values in the last document.
--
-- Every value is checked one at a time (clean() below) before it is stored or formatted, so a
-- secret on this build becomes a sentinel instead of costing the whole line.

local ADDON = ...
local WC = _G.WowCompanion
if type(WC) ~= "table" or type(WC.S) ~= "function" or type(WC.send) ~= "function" then return end
local CALL, isPlain = WC.CALL, WC.isPlain

local PV = "p3"     -- bump on every change; the app shows it, so a stale build is visible

-- PVP_RANK_POINTS_FACTION_ID in the game's pvprankframe.lua
local RANK_FACTION = 2800
-- the retail Honor currency; whether Forever uses this id is one of the things the device check answers
local HONOR_CURRENCY = 1792
local MAX_BG, MAX_REWARDS, MAX_TEXT = 20, 4, 300

-- ---------------------------------------------------------------- safe reads
local tally = { secret = 0, threw = 0 }

local function real(v)
    return v ~= nil and v ~= WC.ABSENT and v ~= WC.SECRET and v ~= WC.THREW
end

-- One value -> itself when plain, the SECRET sentinel when not. type() is the one operation measured
-- safe on a secret, so nothing else touches the value before isPlain() has passed it.
local function clean(v)
    if type(v) == "nil" then return nil end
    if not isPlain(v) then
        tally.secret = tally.secret + 1
        return WC.SECRET
    end
    return v
end
local function num(v) v = clean(v); if type(v) == "number" then return v end end
local function bool(v) v = clean(v); if type(v) == "boolean" then return v end end

-- The game's own strings carry its colour codes (MEASURED on the Thor 2026-09-30: "Rank Points:
-- |cnHIGHLIGHT_FONT_COLOR:0 / 750|r"). The NAMED form is turned into the plain hex form (|cffffffff...|r)
-- with the game's own colour object, so the app reads one form and never guesses a colour. A name the
-- game does not know is dropped: its text keeps the colour of the line around it.
local hexCache = {}
local function hexOf(name)
    local h = hexCache[name]
    if h == nil then
        h = false
        local c = _G[name]
        if type(c) == "table" and type(c.GenerateHexColor) == "function" then
            local ok, v = pcall(c.GenerateHexColor, c)
            if ok and type(v) == "string" and isPlain(v) and v:match("^%x%x%x%x%x%x%x%x$") then h = v end
        end
        hexCache[name] = h
    end
    return h
end
local function colours(s)
    if not s:find("|cn", 1, true) then return s end
    local ok, out = pcall(string.gsub, s, "|cn([%w_]+):", function(name)
        local h = hexOf(name)
        return h and ("|c" .. h) or ""
    end)
    if ok and type(out) == "string" then return out end
    return s
end

local function str(v)
    v = clean(v)
    if type(v) == "string" and real(v) then
        v = colours(v)
        if #v > MAX_TEXT then v = v:sub(1, MAX_TEXT) end
        return v
    end
end

-- A game function by name, or nil when this client does not have it. Indexing a missing C_ table
-- would throw, so every lookup goes through here.
local function api(ns, name)
    local t = _G
    if ns then t = _G[ns] end      -- not `ns and _G[ns] or _G`: a missing table would fall back to _G
    if type(t) ~= "table" then return nil end
    local f = t[name]
    if type(f) == "function" then return f end
end

-- Every return of one call, each cleaned on its own (never stored raw). nil when the call threw.
-- select("#") and explicit indices, not unpack/#: see CALL in Safety.lua for why.
local function pick(ok, ...)
    if not ok then
        tally.threw = tally.threw + 1
        return nil
    end
    local n = select("#", ...)
    local t = { n = n }
    for i = 1, n do
        local v = (select(i, ...))
        t[i] = clean(v)
    end
    return t
end
local function M(fn, ...)
    if type(fn) ~= "function" then return nil end
    return pick(CALL(fn, ...))
end
-- the first return, cleaned
local function one(fn, ...)
    local r = M(fn, ...)
    return r and r[1]
end

-- The game's own text, as the game formats it. Only plain numbers and strings go in.
local function fmt(name, ...)
    local f = _G[name]
    if type(f) ~= "string" or not isPlain(f) then return nil end
    for i = 1, select("#", ...) do
        local a = (select(i, ...))
        if type(a) ~= "number" and type(a) ~= "string" then return nil end
    end
    local ok, s = pcall(string.format, f, ...)
    if ok and type(s) == "string" and isPlain(s) then return colours(s) end
end
local function gstr(name) return str(_G[name]) end

-- The season countdown in the game's own words ("Season ends in 40 Days"), made with the game's own
-- formatter exactly as pvprankframe.lua sets it up: two units, whole days while more than a day is left.
-- The formatter is an object of ours built from Blizzard's mixin, so nothing of the game's is touched.
local formatter
local function countdown(sec)
    if type(sec) ~= "number" or sec <= 0 then return nil end
    local ok, s = pcall(function()
        if not formatter then
            formatter = CreateFromMixins(SecondsFormatterMixin)
            formatter:Init(SecondsFormatterConstants.ZeroApproximationThreshold, SecondsFormatter.Abbreviation.None,
                SecondsFormatterConstants.DontRoundUpLastUnit, SecondsFormatterConstants.DontConvertToLower)
            formatter:SetDesiredUnitCount(2)
        end
        local day = (type(SECONDS_PER_DAY) == "number") and SECONDS_PER_DAY or 86400
        formatter:SetMinInterval((sec > day) and SecondsFormatter.Interval.Days or SecondsFormatter.Interval.Seconds)
        return formatter:Format(sec)
    end)
    if ok and type(s) == "string" and isPlain(s) then return fmt("SEASON_ENDS_IN_TIME", s) end
end

-- ---------------------------------------------------------------- the rank (Character window, tab 4)
local function faction()
    local f = str(one(UnitFactionGroup, "player"))
    if f == "Alliance" or f == "Horde" then return f end
end

-- The game's GetPVPRankText: a title per rank, by faction and sex.
local function rankTitle(level, fac)
    if not level or level <= 0 then return gstr("PVP_RANK_0_NAME") end
    local first = (Enum and Enum.PvPRanks and num(Enum.PvPRanks.Rank_1)) or 5
    local key = "PVP_RANK_" .. (first + level - 1) .. "_" .. ((fac == "Alliance") and 1 or 0)
    local sex = num(one(UnitSex, "player"))
    return str(one(GetText, key, sex))
end

-- Scalar fields of a reward table, cleaned; the game's own reward pane uses icon + description.
local function reward(t)
    if type(t) ~= "table" then return nil end
    local out, n = {}, 0
    for k, v in pairs(t) do
        if type(k) == "string" and n < 12 then
            local tv = type(v)
            local c
            if tv == "string" then c = str(v)
            elseif tv == "number" or tv == "boolean" then c = clean(v) end
            if c ~= nil and isPlain(k) then out[k] = c; n = n + 1 end
        end
    end
    return out
end

function WC.collectPvpRank()
    local fac = faction()
    local out = { faction = fac }
    local info = one(api("C_MajorFactions", "GetMajorFactionProgressionInfo"), RANK_FACTION)
    if type(info) ~= "table" then
        -- the game's own detail pane says PVP_RANK_DETAIL_UNAVAILABLE here and its tab shows nothing
        out.ok = false
        out.why = api("C_MajorFactions", "GetMajorFactionProgressionInfo") and "no data" or "no api"
        out.unavailable = gstr("PVP_RANK_DETAIL_UNAVAILABLE")
        return out
    end
    out.ok = true
    local level = num(info.renownLevel) or 0
    local earned = num(info.renownReputationEarned)
    local threshold = num(info.renownLevelThreshold)
    local maxLevel = num(info.maxLevel)
    local weekMax = num(info.currentWeekProgressiveMaxLevel)
    local prevWeekMax = num(info.previousWeekProgressiveMaxLevel)
    out.level, out.earned, out.threshold, out.max = level, earned, threshold, maxLevel
    out.weekMax, out.prevWeekMax, out.week = weekMax, prevWeekMax, num(info.weekNumber)
    out.title = rankTitle(level, fac)

    -- season (the game uses the arena season counter for it) and its end, in seconds; 0 = unknown
    local season = num(one(GetCurrentArenaSeason))
    out.season = season
    out.seasonEnds = num(one(api("C_SeasonInfo", "GetTimeUntilCurrentPVPSeasonEnd")))
    out.seasonEndsText = countdown(out.seasonEnds)

    -- the same totals the game's detail pane computes
    local total = api("C_MajorFactions", "GetTotalReputationForRenownLevel")
    local function tot(l) if l then return num(one(total, RANK_FACTION, l)) end end
    local atRank, atWeek, atPrev, atSeason = tot(level), tot(weekMax), tot(prevWeekMax), tot(maxLevel)
    local mine = (atRank and earned) and (atRank + earned) or nil
    out.total = { rank = atRank, weekMax = atWeek, prevWeekMax = atPrev, seasonMax = atSeason, mine = mine }

    -- the game's own lines, formatted with its own strings (main pane, then the detail pane)
    local L = {}
    if season and season > 0 then L.season = fmt("EXPANSION_SEASON_NAME", "", season) end
    if level == 0 then L.rank = gstr("PVP_RANK_0_NAME")
    elseif out.title then L.rank = fmt("PVP_RANK_NUMBER_AND_TITLE", level, out.title) end
    if earned and threshold then L.progress = fmt("PVP_RANK_CURRENT_PROGRESS", earned, threshold) end
    if level > 0 then L.number = fmt("PVP_RANK_NUMBER", level) end
    if atSeason and maxLevel then L.desc = fmt("PVP_RANK_SEASON_RANKUP_DESCRIPTION", atSeason, maxLevel) end
    if mine and mine > 0 and atWeek == 0 then L.seasonProgress = fmt("PVP_RANK_SEASON_PROGRESS_NO_MAX", mine)
    elseif mine and mine > 0 and atWeek and atWeek > 0 then L.seasonProgress = fmt("PVP_RANK_SEASON_PROGRESS", mine, atWeek) end
    if atWeek and atPrev and atWeek - atPrev > 0 then L.weeklyCap = fmt("PVP_RANK_WEEKLY_CAP_INCREASE", atWeek - atPrev) end
    if next(L) then out.lines = L end     -- an empty table would go out as [] and not {}

    -- the next rank that carries a reward, and what it gives
    local rewardsFor = api("C_MajorFactions", "GetRenownRewardsForLevel")
    if rewardsFor and maxLevel then
        for r = level + 1, maxLevel do
            local list = one(rewardsFor, RANK_FACTION, r)
            if type(list) == "table" and type(list[1]) ~= "nil" then
                local items = {}
                for i = 1, MAX_REWARDS do
                    local it = reward(list[i])
                    if not it then break end
                    items[#items + 1] = it
                end
                out.next = { rank = r, rewards = items, header = fmt("PVP_RANK_NEXT_REWARD", r) }
                break
            end
        end
    end
    out.vendor = gstr((fac == "Horde") and "PVP_RANK_REWARDS_VENDOR_HORDE" or "PVP_RANK_REWARDS_VENDOR_ALLIANCE")
    return out
end

-- ---------------------------------------------------------------- honor and kills
function WC.collectPvpHonor()
    local out = {}
    local s = M(GetPVPSessionStats)
    if s then out.hkToday, out.dkToday = num(s[1]), num(s[2]) end
    local l = M(GetPVPLifetimeStats)
    if l then out.hkLife, out.maxRank = num(l[1]), num(l[2]) end
    out.unitHonor = num(one(UnitHonor, "player"))
    out.unitHonorMax = num(one(UnitHonorMax, "player"))
    local c = one(api("C_CurrencyInfo", "GetCurrencyInfo"), HONOR_CURRENCY)
    if type(c) == "table" then
        out.currency = { id = HONOR_CURRENCY, name = str(c.name), have = num(c.quantity),
                         max = num(c.maxQuantity), icon = num(c.iconFileID) }
    end
    out.none = next(out) == nil or nil     -- keeps it an object on the wire when nothing answered
    return out
end

-- ---------------------------------------------------------------- the battleground list
-- The game shows no such list (the battlemaster joins at once). This is here so the device check can
-- say whether the battlegrounds are switched on for this character, and at which levels.
function WC.collectPvpBattlegrounds()
    local out = {}
    local n = num(one(GetNumBattlegroundTypes)) or 0
    local info = api("C_PvP", "GetBattlegroundInfo")
    for i = 1, math.min(n, MAX_BG) do
        local b = one(info, i)
        if type(b) == "table" then
            out[#out + 1] = {
                name = str(b.name), id = num(b.battlegroundID), map = num(b.mapID),
                canEnter = bool(b.canEnter), random = bool(b.isRandom), holiday = bool(b.isHoliday),
                max = num(b.maxPlayers), kind = str(b.gameType), short = str(b.shortDescription),
                icon = num(b.icon),
            }
        end
    end
    local r = one(api("C_PvP", "GetRandomBGInfo"))
    local random
    if type(r) == "table" then
        random = { name = str(r.name), canQueue = bool(r.canQueue), id = num(r.bgID),
                   min = num(r.minLevel), max = num(r.maxLevel) }
    end
    return out, n, random
end

-- ---------------------------------------------------------------- the queues (the minimap eye)
-- The same reads the game's QueueStatusEntry_SetUpBattlefield makes. Times are in milliseconds and
-- relative to the moment of reading; the app counts on from there.
function WC.collectPvpQueue()
    local out = {}
    local n = num(one(GetMaxBattlefieldID)) or 0
    for i = 1, math.min(n, 5) do
        local r = M(GetBattlefieldStatus, i)
        local status = r and str(r[1])
        if status and status ~= "none" then
            local q = { i = i, status = status, map = str(r[2]), size = num(r[3]),
                        rated = bool(r[4]), suspended = bool(r[5]), kind = str(r[6]),
                        game = str(r[7]), role = str(r[8]), group = bool(r[9]) }
            if status == "queued" then
                q.waited = num(one(GetBattlefieldTimeWaited, i))
                q.estimate = num(one(GetBattlefieldEstimatedWaitTime, i))
            elseif status == "confirm" then
                q.expires = num(one(GetBattlefieldPortExpiration, i))
            elseif status == "active" then
                q.runTime = num(one(GetBattlefieldInstanceRunTime))
            end
            out[#out + 1] = q
        end
    end
    return out
end

-- ---------------------------------------------------------------- emission
local lastKey = nil
local battlemaster = nil    -- the last BATTLEFIELDS_SHOW: proves the queue path during the device check

local function build()
    tally.secret, tally.threw = 0, 0
    local bgs, nbg, random = WC.collectPvpBattlegrounds()
    local doc = {
        v = PV,
        rank = WC.collectPvpRank(),
        honor = WC.collectPvpHonor(),
        bg = bgs, nbg = nbg, random = random,
        queue = WC.collectPvpQueue(),
        battlemaster = battlemaster,
        inBattle = bool(one(api("C_PvP", "IsActiveBattlefield"))),
    }
    doc.sec = { secret = tally.secret, threw = tally.threw }
    return doc
end

-- the change test leaves out what moves by itself every second
local function keyOf(doc)
    local r, q = doc.rank, {}
    local ends, text = r.seasonEnds, r.seasonEndsText
    r.seasonEnds, r.seasonEndsText = nil, nil
    for i, e in ipairs(doc.queue) do
        q[i] = { e.i, e.status, e.map }
    end
    local saved = doc.queue
    doc.queue = q
    local key = WC.encode(doc)
    doc.queue, r.seasonEnds, r.seasonEndsText = saved, ends, text
    return key
end

-- Collect, and send if changed (or forced). Does NOT push: the caller decides, so a heartbeat
-- ride-along costs no flush of its own.
local function emitPvp(force)
    local doc = build()
    local key = keyOf(doc)
    if key == lastKey and not force then return false end
    lastKey = key
    WC.send(WC.encode({ type = "pvp", pvp = doc }))
    return true
end
WC.emitPvp = emitPvp

-- rank and faction events arrive in bursts (a kill in a battleground fires several), so coalesce
local queued = false
local function soon()
    if queued then return end
    queued = true
    C_Timer.After(0.25, function()
        queued = false
        if emitPvp(false) then WC.push() end
    end)
end

local f = CreateFrame("Frame")
f:SetScript("OnEvent", function(_, event, a1, a2)
    if event == "BATTLEFIELDS_SHOW" then
        battlemaster = { arena = bool(a1), id = num(a2), at = num(one(GetTime)) }
    elseif event == "BATTLEFIELDS_CLOSED" and battlemaster then
        battlemaster.closed = true
    end
    soon()
end)
local WANT = {
    "PLAYER_ENTERING_WORLD", "UPDATE_BATTLEFIELD_STATUS", "BATTLEFIELDS_SHOW", "BATTLEFIELDS_CLOSED",
    "UPDATE_FACTION", "MAJOR_FACTION_RENOWN_LEVEL_CHANGED", "PLAYER_PVP_RANK_CHANGED",
    "PLAYER_PVP_KILLS_CHANGED", "HONOR_XP_UPDATE", "CURRENCY_DISPLAY_UPDATE", "ZONE_CHANGED_NEW_AREA",
}
local missing = {}
for _, e in ipairs(WANT) do
    local ok, registered = pcall(function() return f:RegisterEvent(e) end)
    if not ok or registered == false then missing[#missing + 1] = e end
end
if #missing > 0 then
    pcall(function() WC.send(WC.encode({ type = "cap", pvp = PV, missingEvents = missing })) end)
end

-- RIDE THE CORE HEARTBEAT, as Quests does: sent inside the snapshot's own pad burst, so re-sending
-- every 15 s costs no flush. Only on reasons that always emit.
local RIDE = { heartbeat = true, login = true, zone = true, manual = true }
if type(WC.snapshot) == "function" then
    hooksecurefunc(WC, "snapshot", function(reason)
        if RIDE[reason] then pcall(emitPvp, true) end
    end)
end
