-- WowCompanion / Harvest.lua
-- Rebuilds the minimap's missing "tracking blip" layer -- vendors, trainers, bankers,
-- innkeepers, mailboxes -- by RECORDING THEM AS YOU MEET THEM, rather than bundling a dump.
--
-- WHY HARVEST INSTEAD OF SHIPPING A DATABASE:
--  * WoW: Forever is Classic+ -- a NEW game (launch 2026-11-04) on Classic-Era geometry, with
--    ~1,000 new quests, new zones, a new race, and capital trainers deliberately reorganised
--    by race. A 2010-era server dump would show trainers that are not there and miss the ones
--    that are, in exactly the city where this feature is supposed to help. It also could never
--    show Forever's new NPCs, and it is patched weekly during beta.
--  * The service type comes from the EVENT, which is authoritative: a merchant frame opening
--    means that NPC is a vendor. No flag-bit guessing.
--  * NO COORDINATE TRANSFORM. C_Map.GetPlayerMapPosition already returns the app's own
--    normalised space, so what we capture is directly drawable.
--  * Accuracy is fine: interact range is ~5 yd against a city map ~1000 yd across, i.e.
--    sub-pixel on a 538x444 dp canvas. Repeat visits average out the approach angle (the app
--    does the averaging; see PoiHarvest.kt).
--  * No licence entanglement. AzerothCore data is AGPL-family and Questie/pfQuest are GPL;
--    data you recorded from your own play is yours.
--
-- PERSISTENCE: SavedVariables are written but NOT read back on this build, so the addon cannot
-- keep the harvest itself. It emits each capture and the ANDROID APP stores it. That is also
-- the right place for it -- the app is what draws the map.
--
-- TWO ROUTES OUT, deliberately redundant:
--  * one {"type":"poi"} line the moment a service frame opens, so the pin appears while the
--    player is still standing there;
--  * this session's finds again on every full snapshot, via WC.collectServices (Map.lua puts
--    the result in map.svc). A poi line the app never saw -- it was restarting, or the log was
--    rotated under it -- is healed by the next 15 s heartbeat. The bytes are free: payload
--    displaces pad one for one (see Sink.lua).
--
-- NOT RECORDED, on the user's ruling (2026-09-27): NPCs the player merely talks to
-- (GOSSIP_SHOW) and quest givers (QUEST_GREETING). They are not services, the map has no pin
-- for them, and each one cost a log flush.

local ADDON = ...
local WC = _G.WowCompanion
local S, SN, CALL = WC.S, WC.SN, WC.CALL

-- event -> service label. The event IS the evidence, so no npcflag inference is needed.
-- Core.lua registers these and calls WC.onInteract(event).
local SERVICE = {
    MERCHANT_SHOW = "vendor",
    TRAINER_SHOW = "trainer",
    BANKFRAME_OPENED = "bank",
    GUILDBANKFRAME_OPENED = "guildbank",
    MAIL_SHOW = "mailbox",
    AUCTION_HOUSE_SHOW = "auction",
    TAXIMAP_OPENED = "flightmaster",
    CONFIRM_BINDER = "innkeeper",
}

WC.HARVEST_EVENTS = {}
for k in pairs(SERVICE) do WC.HARVEST_EVENTS[#WC.HARVEST_EVENTS + 1] = k end
table.sort(WC.HARVEST_EVENTS)

-- THE MODERN UMBRELLA EVENT. PLAYER_INTERACTION_MANAGER_FRAME_SHOW carries the interaction
-- TYPE as its first argument, and it is the only route to services that have no dedicated
-- event on this client (stable master). Core.lua passes WC.onInteract only the event NAME, so
-- this one gets its own frame here instead of an edit to Core.lua.
--
-- The type numbers are NOT hard-coded. Retail documentation lists them, but this is a
-- Classic+ fork of that code and a renumbered enum would silently file every bank as a
-- vendor. Resolve by NAME from the live Enum table, and report which names resolved.
local PIM_NAMES = {
    Merchant = "vendor", Vendor = "vendor",
    Trainer = "trainer",
    Banker = "bank",
    GuildBanker = "guildbank",
    MailInfo = "mailbox",
    Auctioneer = "auction",
    TaxiNode = "flightmaster",
    Binder = "innkeeper",
    StableMaster = "stable",
    GuildTabardVendor = "tabard",
}
local PIM = {}
local pimResolved, pimMissing = {}, {}
do
    local E = type(Enum) == "table" and Enum.PlayerInteractionType or nil
    for name, svc in pairs(PIM_NAMES) do
        local v = type(E) == "table" and SN(function() return E[name] end) or nil
        if v then PIM[v] = svc; pimResolved[#pimResolved + 1] = name
        else pimMissing[#pimMissing + 1] = name end
    end
    table.sort(pimResolved); table.sort(pimMissing)
end

-- ---------------------------------------------------------------- the capture
-- Recently emitted, so standing at a vendor does not re-send on every frame open, and so the
-- dedicated event and the umbrella event for the same frame produce ONE line, not two.
local seen = {}
-- This session's finds, for WC.collectServices. [key] = row. The app is the permanent store.
local found = {}
local foundN = 0
local MAX_FOUND = 200       -- a whole capital is ~60 services; this only guards a runaway

local function creatureIdOf(guid)
    if type(guid) ~= "string" then return nil end
    -- "Creature-0-6782-1-279-30-0000ABCDEF" -> the 6th dash-separated field is the creature id.
    -- Only creatures and vehicles: a Player- or GameObject- GUID has a different layout, and its
    -- 6th field is not a creature id -- filing it as one would mark a random bundled pin visited.
    local kind = guid:match("^(%a+)-")
    if kind ~= "Creature" and kind ~= "Vehicle" then return nil end
    local ok, id = pcall(function()
        local parts = {}
        for p in guid:gmatch("[^-]+") do parts[#parts + 1] = p end
        return tonumber(parts[6])
    end)
    return ok and id or nil
end

local function plainNum(v)
    if type(v) == "number" and WC.isPlain(v) then return v end
    return nil
end

-- Round to 1e-5 of the zone (~0.03 yd in Elwynn) and to a tenth of a yard: enough precision
-- for averaging, and it keeps a re-emitted row byte-identical so it cannot look like a change.
local function r5(v) return v and math.floor(v * 100000 + 0.5) / 100000 end
local function r1(v) return v and math.floor(v * 10 + 0.5) / 10 end

-- The player's uiMap id and normalised position, or nil. At a service or quest dialog the player
-- stands within interact range of the NPC, so this IS the NPC's position to a few yards.
local function here()
    local mapID = SN(function() return C_Map.GetBestMapForUnit("player") end)
    if not mapID then return nil end
    local x, y
    local okp, p = CALL(function() return C_Map.GetPlayerMapPosition(mapID, "player") end)
    if okp and p then
        local okxy, px, py = CALL(function() return p:GetXY() end)
        if okxy then x, y = plainNum(px), plainNum(py) else
            x = SN(function() return p.x end); y = SN(function() return p.y end)
        end
    end
    if type(x) ~= "number" or type(y) ~= "number" then return nil end
    return mapID, x, y
end

-- "npc" is the unit you are interacting with; fall back to target. Returns guid (a plain string
-- or a sentinel) and a plain name or nil.
local function npcUnit()
    local guid = S(function() return UnitGUID("npc") end)
    if guid == WC.ABSENT or guid == WC.SECRET or guid == WC.THREW then
        guid = S(function() return UnitGUID("target") end)
    end
    local name = S(function() return UnitName("npc") end)
    if name == WC.ABSENT or name == WC.SECRET or name == WC.THREW then
        name = S(function() return UnitName("target") end)
    end
    if type(name) ~= "string" or name == WC.ABSENT or name == WC.SECRET or name == WC.THREW then
        name = nil
    end
    return guid, name
end

-- WORLD position and continent. The app uses them to check a capture against the bundled
-- seed: a seed pin is only marked "visited" when the harvested NPC stands where the bundle says
-- it does. Forever MOVES capital trainers, and a solid pin in the old spot would be wrong.
local function placeWorld(row, mapID)
    local okw, wx, wy = CALL(UnitPosition, "player")
    if okw then row.wx = r1(plainNum(wx)); row.wy = r1(plainNum(wy)) end
    local rect = WC.zoneRect and WC.zoneRect(mapID)
    if rect then row.c = plainNum(rect.continent) end
end

function WC.harvest(svc, source)
    if not svc then return end

    local mapID, x, y = here()
    if not mapID then return end
    local guid, name = npcUnit()

    local cid = creatureIdOf(guid)
    if svc == "mailbox" then cid = nil; name = nil end   -- an object; "npc" may be stale
    -- Objects (mailboxes) have no unit at all; key those by rounded position instead.
    local key = svc .. "/" .. tostring(cid or (math.floor(x * 1000) .. "_" .. math.floor(y * 1000)))
    if seen[key] and (GetTime() - seen[key]) < 300 then return end
    seen[key] = GetTime()

    local row = {
        svc = svc,
        mapID = mapID,
        x = r5(x), y = r5(y),
        id = cid,
        name = name,
        t = GetTime(),
        src = source,
    }

    placeWorld(row, mapID)

    -- More specific roles, when the frame can tell us.
    if svc == "trainer" then
        local okt, isProf = CALL(IsTradeskillTrainer)
        if okt and type(isProf) == "boolean" then row.kind = isProf and "prof" or "class" end
    elseif svc == "vendor" then
        local okr, canRepair = CALL(CanMerchantRepair)
        if okr and canRepair == true then row.repair = true end
    end

    if not found[key] then
        if foundN >= MAX_FOUND then return end
        foundN = foundN + 1
    end
    found[key] = row

    WC.send(WC.encode({ type = "poi", poi = row }))
    WC.pushThrottled()
end

-- Core.lua's entry point for the dedicated events.
function WC.onInteract(event)
    WC.harvest(SERVICE[event], event)
end

-- Map.lua calls this for every full snapshot's map.svc.
function WC.collectServices(mapID)
    local out = {}
    for _, row in pairs(found) do
        if row.mapID == mapID then out[#out + 1] = row end
    end
    if #out == 0 then return nil end
    table.sort(out, function(a, b)
        if a.svc ~= b.svc then return a.svc < b.svc end
        return (a.id or 0) < (b.id or 0)
    end)
    return out
end

-- ---------------------------------------------------------------- quest givers
-- RULING (user, confirmed directly in this session 2026-09-28, amending 2026-09-27): quest
-- dialogs ARE recorded -- the NPC, where it stands, the quest ids it offers and the ones it takes
-- back. Service pins are never touched by this. Why: C_QuestLine.GetAvailableQuestLines returns
-- nothing on Forever (0 in 59 of 59 snapshots), so the map's "!" layer comes from the bundled
-- quest database, and what the player actually meets corrects it -- including a database giver
-- that offers nothing, which is still sent (empty lists) so its wrong "!" can go.
--
-- ONE ROW PER DIALOG, SENT ONLY WHEN THE NPC'S LISTS CHANGE. Accepting a quest and reopening the
-- NPC re-sends it without that quest; opening the same dialog twice sends nothing new.
--   full = true   gossip / greeting: the NPC's COMPLETE lists (the app replaces what it had)
--   full = false  detail / progress / complete: one quest (the app adds it)
-- The wire shape is in AGENT_LOG ("Quest-giver recording: WIRE SHAPE").

local QG_STAGE = {
    GOSSIP_SHOW = "gossip", QUEST_GREETING = "greeting",
    QUEST_DETAIL = "detail", QUEST_PROGRESS = "progress", QUEST_COMPLETE = "complete",
}
local qgRows = {}          -- this session: [key] = the latest row, re-sent on the heartbeat
local qgSig = {}           -- [key] = the lists last sent, so an unchanged dialog sends nothing
local qgInfoSent = false

-- entry of the unit you are talking to: "e" for a creature, "o" for a game object (wanted
-- posters, books). Same 6th-field layout for both GUID kinds.
local function giverOf(guid)
    if type(guid) ~= "string" then return nil end
    local kind = guid:match("^(%a+)-")
    local tag = (kind == "Creature" or kind == "Vehicle") and "e" or (kind == "GameObject" and "o") or nil
    if not tag then return nil end
    local ok, id = pcall(function()
        local parts = {}
        for p in guid:gmatch("[^-]+") do parts[#parts + 1] = p end
        return tonumber(parts[6])
    end)
    if ok and id then return tag, id end
end

local function addId(list, v)
    v = plainNum(v)
    if v and v > 0 then list[#list + 1] = v end
end

local function plainStr(v)
    if type(v) == "string" and v ~= WC.ABSENT and v ~= WC.SECRET and v ~= WC.THREW and v ~= "" then return v end
end

-- The lists for the dialog on screen now.
local function questLists(stage)
    local offer, turnin, done, offerT = {}, {}, {}, {}
    if stage == "gossip" then
        local oka, av = CALL(function() return C_GossipInfo.GetAvailableQuests() end)
        if oka and type(av) == "table" then
            for _, q in ipairs(av) do addId(offer, SN(function() return q.questID end)) end
        end
        local okc, ac = CALL(function() return C_GossipInfo.GetActiveQuests() end)
        if okc and type(ac) == "table" then
            for _, q in ipairs(ac) do
                local id = SN(function() return q.questID end)
                addId(turnin, id)
                if S(function() return q.isComplete end) == true then addId(done, id) end
            end
        end
    elseif stage == "greeting" then
        -- An old-style giver: indexed lists. GetAvailableQuestInfo(i) returns the quest id as its
        -- 5th value in retail; that position is UNVERIFIED here, so the raw returns go out once
        -- as a cap, and the title rides along whenever no id is found.
        for i = 1, SN(function() return GetNumAvailableQuests() end) or 0 do
            local r = { CALL(GetAvailableQuestInfo, i) }
            if not qgInfoSent then
                qgInfoSent = true
                local raw = {}
                for k = 2, 8 do
                    local v = r[k]
                    local plain = v ~= nil and type(v) ~= "table" and WC.isPlain(v)
                    raw[#raw + 1] = { t = type(v), v = plain and v or nil }
                end
                WC.send(WC.encode({ type = "cap", qgInfo = { ok = r[1] and true or false, raw = raw } }))
            end
            local id = r[1] and plainNum(r[6]) or nil
            if id and id > 0 then offer[#offer + 1] = id
            else
                local t = plainStr(S(function() return GetAvailableTitle(i) end))
                if t then offerT[#offerT + 1] = t end
            end
        end
        for i = 1, SN(function() return GetNumActiveQuests() end) or 0 do
            local id = SN(function() return GetActiveQuestID(i) end)
            addId(turnin, id)
            local okt, _, isComplete = CALL(GetActiveTitle, i)
            if okt and isComplete == true then addId(done, id) end
        end
    else
        local id = SN(function() return GetQuestID() end)
        if stage == "detail" then addId(offer, id)
        else
            addId(turnin, id)
            if stage == "complete" then addId(done, id)
            elseif S(function() return IsQuestCompletable() end) == true then addId(done, id) end
        end
    end
    return offer, turnin, done, offerT
end

function WC.recordQuestGiver(stage)
    local mapID, x, y = here()
    if not mapID then return end
    local guid, name = npcUnit()
    local tag, gid = giverOf(guid)
    local offer, turnin, done, offerT = questLists(stage)
    local full = (stage == "gossip" or stage == "greeting")
    -- An unidentifiable unit with nothing to say is noise; an identifiable one is kept even when
    -- empty, because "this database giver offers nothing" is the point.
    if not tag and #offer == 0 and #turnin == 0 and #offerT == 0 then return end
    table.sort(offer); table.sort(turnin); table.sort(done)

    local key = (tag or "p") .. tostring(gid or (math.floor(x * 1000) .. "_" .. math.floor(y * 1000)))
    local sig = WC.encode({ stage = full and "full" or stage, offer = offer, turnin = turnin,
                            done = done, offerT = offerT })
    if qgSig[key] == sig then return end
    qgSig[key] = sig

    local row = {
        mapID = mapID, x = r5(x), y = r5(y), name = name, full = full, src = stage, t = GetTime(),
        offer = offer, turnin = turnin, done = done,
    }
    if tag then row[tag] = gid end
    if #offerT > 0 then row.offerT = offerT end
    placeWorld(row, mapID)
    qgRows[key] = row

    WC.send(WC.encode({ type = "qg", qg = row }))
    WC.pushThrottled()
end

-- The session's rows again on every core snapshot (the 15 s heartbeat included), so a "qg" line
-- the app missed heals. WC.snapshot lives in Core.lua, which loads AFTER this file, so the hook is
-- placed at login (or at the first quest dialog, since a /reload fires no PLAYER_LOGIN).
local qgHooked = false
local function hookHeartbeat()
    if qgHooked or type(WC.snapshot) ~= "function" or not hooksecurefunc then return end
    qgHooked = pcall(hooksecurefunc, WC, "snapshot", function()
        if next(qgRows) == nil then return end
        local all = {}
        for _, row in pairs(qgRows) do all[#all + 1] = row end
        table.sort(all, function(a, b) return a.t < b.t end)
        WC.send(WC.encode({ type = "qg", all = all }))
    end)
end

-- ---------------------------------------------------------------- own events
-- The umbrella event (needs its argument), and the guard-directory probe.
local ev = CreateFrame("Frame")
local evMissing = {}
for _, e in ipairs({ "PLAYER_INTERACTION_MANAGER_FRAME_SHOW", "GOSSIP_SHOW",
                     "DYNAMIC_GOSSIP_POI_UPDATED", "QUEST_GREETING", "QUEST_DETAIL",
                     "QUEST_PROGRESS", "QUEST_COMPLETE", "PLAYER_LOGIN" }) do
    local ok, registered = pcall(function() return ev:RegisterEvent(e) end)
    if not ok or registered == false then evMissing[#evMissing + 1] = e end
end

-- ---- the guard-directory probe
-- In the Classic lineage, asking a city guard for "the bank" makes the SERVER send a map POI.
-- If Forever kept guard directories, one conversation fills a whole city at once -- and the
-- position is server-authoritative. What is not known yet is whether the server still answers,
-- and what the answer looks like. So for now this only RECORDS each answer as a `cap` line,
-- together with the gossip option the player picked, which is what will say which service it
-- is. Nothing is drawn from it until a real answer has been read.
--
-- MEASURED FROM THE BINARY: GetPoiForUiMapID returns ONE id ("local gossipPoiID = ..."), not a
-- list. The first /wcpoi walked it with ipairs and would have reported 0 found every time.
local gossipOpts = {}        -- [gossipOptionID] = option text, from the window on screen now
local lastPick = nil         -- text of the option the player picked most recently

local function cacheGossipOptions()
    gossipOpts = {}
    local ok, opts = CALL(function() return C_GossipInfo.GetOptions() end)
    if ok and type(opts) == "table" then
        for _, o in ipairs(opts) do
            local id = SN(function() return o.gossipOptionID end)
            local nm = S(function() return o.name end)
            if id and type(nm) == "string" and nm ~= WC.SECRET and nm ~= WC.ABSENT and nm ~= WC.THREW then
                gossipOpts[id] = nm
            end
        end
    end
end

if C_GossipInfo and type(C_GossipInfo.SelectOption) == "function" and hooksecurefunc then
    pcall(hooksecurefunc, C_GossipInfo, "SelectOption", function(optionID)
        local id = plainNum(optionID)
        if id and gossipOpts[id] then lastPick = gossipOpts[id] end
    end)
end

local function readGossipPoi(mapID)
    local ok, res = CALL(function() return C_GossipInfo.GetPoiForUiMapID(mapID) end)
    local ids = {}
    if ok and type(res) == "number" then ids[1] = res
    elseif ok and type(res) == "table" then for _, v in ipairs(res) do ids[#ids + 1] = v end end
    local pois = {}
    for _, poiID in ipairs(ids) do
        local oki, info = CALL(function() return C_GossipInfo.GetPoiInfo(mapID, poiID) end)
        if oki and type(info) == "table" then
            local px, py
            local okxy, a, b = CALL(function() return info.position:GetXY() end)
            if okxy then px, py = plainNum(a), plainNum(b) else
                px = SN(function() return info.position and info.position.x end)
                py = SN(function() return info.position and info.position.y end)
            end
            pois[#pois + 1] = {
                id = SN(function() return poiID end),
                name = S(function() return info.name end),
                x = r5(px), y = r5(py),
                tex = SN(function() return info.textureIndex end),
            }
        end
    end
    return ok, res, pois
end

local function reportGossipPoi(trigger)
    local mapID = SN(function() return C_Map.GetBestMapForUnit("player") end)
    local ok, res, pois = readGossipPoi(mapID)
    WC.emit({
        type = "cap", probe = "gossipPoi", trigger = trigger, mapID = mapID,
        callOK = ok, ret = type(res), pick = lastPick, pois = pois,
    })
    return ok, pois
end

ev:SetScript("OnEvent", function(_, event, a1)
    if event == "PLAYER_INTERACTION_MANAGER_FRAME_SHOW" then
        local t = plainNum(a1)
        if t and PIM[t] then WC.harvest(PIM[t], "PIM") end
    elseif event == "DYNAMIC_GOSSIP_POI_UPDATED" then
        reportGossipPoi("event")
    elseif event == "PLAYER_LOGIN" then
        hookHeartbeat()
    elseif QG_STAGE[event] then
        if event == "GOSSIP_SHOW" then cacheGossipOptions() end   -- option text, for the guard probe
        hookHeartbeat()                -- a /reload fires no PLAYER_LOGIN
        WC.recordQuestGiver(QG_STAGE[event])
    end
end)

-- What this build actually gave us, once at load, so the device answers these questions
-- instead of retail documentation: which interaction types resolved, which events exist.
pcall(function()
    WC.send(WC.encode({ type = "cap", harvest = {
        pimResolved = pimResolved, pimMissing = pimMissing, missingEvents = evMissing,
        tradeskillFn = type(IsTradeskillTrainer) == "function",
        repairFn = type(CanMerchantRepair) == "function",
    } }))
end)

-- /wcpoi -- read the guard-directory answer on demand (the event above normally does it).
-- Talk to a city guard, pick a destination, then run it.
SLASH_WCPOI1 = "/wcpoi"
SlashCmdList["WCPOI"] = function()
    local ok, pois = reportGossipPoi("slash")
    if DEFAULT_CHAT_FRAME then
        DEFAULT_CHAT_FRAME:AddMessage("|cffffd100WowCompanion|r gossip POI probe: callOK=" ..
            tostring(ok) .. " found=" .. #pois .. " (ask a city guard for directions first)")
    end
end
