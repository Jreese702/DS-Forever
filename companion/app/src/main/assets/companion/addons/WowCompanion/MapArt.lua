-- WowCompanion / MapArt.lua
-- Dumps the zone MAP ART tile ids so the real map picture can be rebuilt on the bottom screen.
--
-- WHY THIS EXISTS: the MAP room drew a scatter of dots because nothing told it what the zone
-- actually looks like. The client knows: every UiMap has art layers made of BLP tiles, and it
-- will hand over their fileDataIDs. Those ids go straight into CascLib's CASC_OPEN_BY_FILEID
-- on the PC, exactly like the 30,376 icons, and the tiles stitch into one image per zone.
--
-- WHY IT DUMPS RAW FIELD NAMES INSTEAD OF READING THE ONES I EXPECT:
-- a layer record's field names are a guess until this build prints them. Guessing them and
-- reading nil would look like "this zone has no art" rather than "I asked for the wrong key" --
-- the exact failure mode that has already cost this project two wrong conclusions. So every
-- scalar on the record is emitted under its own name and the PC side picks what it needs.
-- See [[project_probe_discipline]].

local ADDON = ...
local WC = _G.WowCompanion
local S, SN, CALL = WC.S, WC.SN, WC.CALL

-- Copy every PLAIN scalar off a record, under its real key. Secret values are skipped rather
-- than stringified, because a secret in the payload poisons the whole JSON line and the sink
-- refuses it -- which reads downstream as a gap, not as a bad field.
local function scalars(t)
    if type(t) ~= "table" then return nil end
    local out, any = {}, false
    pcall(function()
        for k, v in pairs(t) do
            local tv = type(v)
            if (tv == "number" or tv == "string" or tv == "boolean") and WC.isPlain(v) then
                out[tostring(k)] = v
                any = true
            end
        end
    end)
    return any and out or nil
end

-- One map's art: the layer records verbatim, each with its tile fileDataIDs.
-- Returns nil when the map has no art at all, so a sweep emits only what is worth extracting.
function WC.collectMapArt(id)
    local okL, layers = CALL(function() return C_Map.GetMapArtLayers(id) end)
    if not okL or type(layers) ~= "table" then return nil end

    local out = {}
    for i = 1, #layers do
        local rec = scalars(layers[i]) or {}
        -- Tile ids. The layer INDEX is what the textures call takes, and it is 1-based here
        -- because that is how the layers table is indexed -- if this build disagrees the tex
        -- list comes back empty and that is visible, rather than silently off by one.
        local okT, tex = CALL(function() return C_Map.GetMapArtLayerTextures(id, i) end)
        if okT and type(tex) == "table" then
            local fids = {}
            for j = 1, #tex do
                local v = tex[j]
                if type(v) == "number" and WC.isPlain(v) then fids[#fids + 1] = v end
            end
            if #fids > 0 then rec.tex = fids end
        end
        rec.layer = i
        out[#out + 1] = rec
    end
    if #out == 0 then return nil end

    local info = nil
    local okI, mi = CALL(function() return C_Map.GetMapInfo(id) end)
    if okI then info = scalars(mi) end

    local artID = SN(function() return C_Map.GetMapArtID(id) end)

    -- The zone's WORLD rect. This is what lets the PC side work out which minimap terrain
    -- tiles (world/minimaps/<continent>/mapCC_RR.blp, one 512x512 BLP per 533.33-yard ADT)
    -- cover this zone, and where to crop them. Without it there is no way to line the tiles
    -- up with the map's 0..1 space.
    local rect = WC.zoneRect and WC.zoneRect(id) or nil
    local r = rect and { c = rect.continent, t = rect.top, b = rect.bottom,
                         l = rect.left, rr = rect.right } or nil

    -- THE DETAIL LAYER. The art layer above is only the parchment: Elwynn Forest came back as
    -- an empty sheet with mountains and neighbour labels, because in the Classic lineage the
    -- terrain itself is painted on as EXPLORATION OVERLAYS. Stormwind City looked complete only
    -- because a city map has no overlay layer at all.
    -- These are per-character: the client hands back the chunks this character has discovered,
    -- which is also exactly what the in-game map shows. An unexplored zone therefore renders on
    -- the bottom screen the same way it renders in game, rather than pretending to know more.
    local okE, expl = CALL(function() return C_MapExplorationInfo.GetExploredMapTextures(id) end)
    local overlays = nil
    if okE and type(expl) == "table" then
        overlays = {}
        for i = 1, #expl do
            local o = scalars(expl[i]) or {}
            local okF, fids = CALL(function() return expl[i].fileDataIDs end)
            if okF and type(fids) == "table" then
                local list = {}
                for j = 1, #fids do
                    local v = fids[j]
                    if type(v) == "number" and WC.isPlain(v) then list[#list + 1] = v end
                end
                if #list > 0 then o.fids = list end
            end
            if o.fids then overlays[#overlays + 1] = o end
        end
        if #overlays == 0 then overlays = nil end
    end

    return { id = id, info = info, artID = artID, layers = out, expl = overlays, rect = r }
end

-- /wcart            -- dump the map the player is standing on
-- /wcart <id>       -- dump one specific uiMapID
-- /wcart all        -- sweep every uiMapID that has art
--
-- The sweep is SPREAD ACROSS FRAMES. C_Map.GetMapArtLayers over a couple of thousand ids in
-- one go is exactly the shape of call that trips the script-timeout watchdog, and a client
-- that stops responding mid-dump loses the whole harvest.
local SWEEP_MAX   = 3000   -- generous upper bound; unused ids cost one nil GetMapInfo each
local PER_TICK    = 40
local sweeping    = false

local function say(s)
    if DEFAULT_CHAT_FRAME then DEFAULT_CHAT_FRAME:AddMessage("|cffffd100WowCompanion|r " .. s) end
end

local function sweep()
    if sweeping then say("sweep already running"); return end
    sweeping = true
    local id, found, scanned = 1, 0, 0
    say("map art sweep started (1.." .. SWEEP_MAX .. ") -- this takes about a minute")
    local ticker
    ticker = C_Timer.NewTicker(0.05, function()
        local n = 0
        while id <= SWEEP_MAX and n < PER_TICK do
            local art = WC.collectMapArt(id)
            scanned = scanned + 1
            if art then
                found = found + 1
                WC.send(WC.encode({ type = "mapart", art = art }))
            end
            id = id + 1
            n = n + 1
        end
        if id > SWEEP_MAX then
            ticker:Cancel()
            sweeping = false
            WC.send(WC.encode({ type = "mapart", done = true, scanned = scanned, found = found }))
            WC.flush()
            say("map art sweep done: |cff8fc47a" .. found .. "|r maps with art out of " .. scanned)
        else
            WC.pushThrottled()
        end
    end)
end

SLASH_WCART1 = "/wcart"
SlashCmdList["WCART"] = function(arg)
    arg = (arg or ""):lower():gsub("^%s+", ""):gsub("%s+$", "")
    if arg == "all" then return sweep() end

    local id = tonumber(arg)
    if not id then
        id = SN(function() return C_Map.GetBestMapForUnit("player") end)
    end
    if not id then say("|cffff5555no map id|r"); return end

    local art = WC.collectMapArt(id)
    if not art then
        WC.emit({ type = "mapart", art = { id = id }, none = true })
        say("uiMap " .. id .. " reports |cffff5555no art layers|r")
        return
    end
    WC.emit({ type = "mapart", art = art })
    local l1 = art.layers[1]
    local ntex = (l1 and l1.tex) and #l1.tex or 0
    say("uiMap " .. id .. "  layers=" .. #art.layers .. "  layer1 tiles=" .. ntex)
    -- Print the record's real keys, so the field names are visible in game without waiting
    -- for the log to flush.
    if l1 then
        local keys = {}
        for k, v in pairs(l1) do
            if k ~= "tex" then keys[#keys + 1] = k .. "=" .. tostring(v) end
        end
        table.sort(keys)
        say("  layer1: " .. table.concat(keys, "  "))
    end
end

-- /wcquest -- what does this build expose about a quest's OBJECTIVE AREA?
--
-- The world map highlights an AREA for the tracked objective, not just a point. That blob is
-- drawn natively (DrawBlob on a blob-capable frame) and may not be readable as geometry from
-- Lua at all. So probe rather than guess: dump every candidate's real return shape, and let
-- the app decide between a true polygon and an approximated radius.
SLASH_WCQUEST1 = "/wcquest"
SlashCmdList["WCQUEST"] = function()
    local mapID = SN(function() return C_Map.GetBestMapForUnit("player") end)
    local out = { type = "cap", probe = "questArea", mapID = mapID, fns = {}, quests = {} }

    -- which candidate entry points actually exist
    local cands = {
        ["C_QuestLog.GetQuestsOnMap"] = function() return C_QuestLog.GetQuestsOnMap end,
        ["C_QuestLog.GetNextWaypoint"] = function() return C_QuestLog.GetNextWaypoint end,
        ["C_QuestLog.GetQuestObjectives"] = function() return C_QuestLog.GetQuestObjectives end,
        ["C_QuestLog.GetQuestAdditionalHighlights"] = function() return C_QuestLog.GetQuestAdditionalHighlights end,
        ["GetQuestPOIBlobCount"] = function() return GetQuestPOIBlobCount end,
        ["QuestPOIGetIconInfo"] = function() return QuestPOIGetIconInfo end,
        ["C_TaskQuest.GetQuestLocation"] = function() return C_TaskQuest and C_TaskQuest.GetQuestLocation end,
        ["C_Map.GetMapRectOnMap"] = function() return C_Map.GetMapRectOnMap end,
    }
    for name, get in pairs(cands) do
        local ok, fn = pcall(get)
        out.fns[name] = (ok and type(fn) == "function") and true or false
    end

    -- can a blob-capable frame be made, and does it report a blob for a tracked quest?
    for _, ftype in ipairs({ "ArchaeologyDigSiteFrame", "QuestPOIFrame", "ScenarioPOIFrame" }) do
        local okf, fr = pcall(CreateFrame, ftype, nil, UIParent)
        out.fns["CreateFrame:" .. ftype] = (okf and fr ~= nil) and true or false
        if okf and fr then
            local meth = {}
            for _, m in ipairs({ "DrawBlob", "DrawNone", "GetNumBlobs", "GetBlobCount",
                                "SetFillTexture", "SetBorderTexture", "GetBlobInfo" }) do
                meth[m] = (type(fr[m]) == "function") and true or false
            end
            out.fns["methods:" .. ftype] = meth
        end
    end

    -- the actual quest records for this map, dumped whole
    local okq, list = CALL(function() return C_QuestLog.GetQuestsOnMap(mapID) end)
    if okq and type(list) == "table" then
        for i = 1, math.min(#list, 8) do
            local rec = scalars(list[i]) or {}
            local qid = rec.questID
            if qid then
                local okw, wx, wy = CALL(function() return C_QuestLog.GetNextWaypoint(qid) end)
                if okw then rec.wpx = SN(function() return wx end); rec.wpy = SN(function() return wy end) end
                local oko, objs = CALL(function() return C_QuestLog.GetQuestObjectives(qid) end)
                if oko and type(objs) == "table" then rec.nobj = #objs end
            end
            out.quests[#out.quests + 1] = rec
        end
    end
    WC.emit(out)
    if DEFAULT_CHAT_FRAME then
        local n = 0
        for _, v in pairs(out.fns) do if v then n = n + 1 end end
        DEFAULT_CHAT_FRAME:AddMessage("|cffffd100WowCompanion|r quest-area probe sent: "
            .. #out.quests .. " quests, " .. n .. " candidates present")
    end
end
