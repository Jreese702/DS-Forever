-- WowCompanionQuests / Quests.lua
-- The QUESTS room: the whole quest log, with objective progress, on the bottom screen.
--
-- WHY A SEPARATE ADDON FOLDER. Several sessions work on WowCompanion in parallel and each pushes
-- its own files to the Thor. Living in its own folder means this feed never has to touch the
-- shared .toc or Core.lua, and a push of it can never overwrite anyone else's file. It uses the
-- core addon's safety kernel and sink (## Dependencies guarantees load order).
--
-- WHAT IT SENDS: one {"type":"quests"} document, only when the log actually changed. Killing a
-- kobold costs one flush; standing still costs nothing. It also rides the core addon's 15 s
-- heartbeat for free (see the snapshot hook at the bottom), which is how the app gets the list
-- back after the log rotates.
--
-- DISTANCE IS DELIBERATELY NOT IN THE CHANGE TEST. It changes with every step, so including it
-- would turn an event-driven feed into a per-step one. The app computes live distance itself
-- from the position stream it already gets; `dist` here is only a fallback for quests whose
-- objective is on another map.
--
-- Every value goes through S()/SN() (the T* wrappers below), so nothing raw is ever
-- concatenated, and secrecy on this build cannot silently kill the whole line.

local ADDON = ...
local WC = _G.WowCompanion
if type(WC) ~= "table" or type(WC.S) ~= "function" or type(WC.send) ~= "function" then return end
local S, SN, CALL = WC.S, WC.SN, WC.CALL

local QV = "q42"    -- bump on every change; the app shows it, so a stale build is visible

local function say(s)
    if DEFAULT_CHAT_FRAME then DEFAULT_CHAT_FRAME:AddMessage("|cffffd100WowCompanion|r " .. s) end
end

local function real(v)
    return v ~= nil and v ~= WC.ABSENT and v ~= WC.SECRET and v ~= WC.THREW
end

-- S() that also counts sentinel hits, so a shift in the secrecy map under a patch shows up on
-- the wire (`sec`) instead of as a quietly emptier room. ABSENT is not counted: nil is a
-- legitimate answer for most of these.
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

local function superTracked()
    local id = TN(function() return C_SuperTrack.GetSuperTrackedQuestID() end)
    if id and id > 0 then return id end
end

-- Objective positions on the player's current map, keyed by questID. Only quests with a POI
-- here get one; the rest carry waypoint text instead.
local function onMapPositions(mapID)
    local pos = {}
    if not mapID then return pos end
    local ok, list = CALL(function() return C_QuestLog.GetQuestsOnMap(mapID) end)
    if ok and type(list) == "table" then
        for _, q in ipairs(list) do
            local id = SN(function() return q.questID end)
            local x = SN(function() return q.x end)
            local y = SN(function() return q.y end)
            if id and x and y then pos[id] = { x, y } end
        end
    end
    return pos
end

local function objectives(qid)
    local out = {}
    local ok, objs = CALL(function() return C_QuestLog.GetQuestObjectives(qid) end)
    if ok and type(objs) == "table" then
        for _, o in ipairs(objs) do
            out[#out + 1] = {
                t = TS(function() return o.text end),
                k = TS(function() return o.type end),
                f = TB(function() return o.finished end),
                have = TN(function() return o.numFulfilled end),
                need = TN(function() return o.numRequired end),
            }
        end
    end
    return out
end

-- q38: the words of the quest-log headers that are the player's own class or professions (the class's name, the professions' names)
function WC.ownHeaders()
    local out = {}
    local cl = TS(function() return (UnitClass("player")) end)
    if cl then out[#out + 1] = cl end
    pcall(function()
        local list = { GetProfessions() }
        for k = 1, 7 do
            local ix = list[k]
            if type(ix) == "number" then
                local name = GetProfessionInfo(ix)
                if type(name) == "string" and WC.isPlain(name) and name ~= "" then out[#out + 1] = name end
            end
        end
    end)
    return out
end

-- Returns the document and, separately, the distances (kept out of the change test).
function WC.collectQuests()
    tally = { secret = 0, threw = 0 }
    local mapID = SN(function() return C_Map.GetBestMapForUnit("player") end)
    local here = onMapPositions(mapID)
    local focus = superTracked()
    local list, dist = {}, {}
    local zone

    local okN, nEntries = CALL(function() return C_QuestLog.GetNumQuestLogEntries() end)
    if okN and type(nEntries) == "number" then
        for i = 1, nEntries do
            local ok, info = CALL(function() return C_QuestLog.GetInfo(i) end)
            if ok and type(info) == "table" then
                if TB(function() return info.isHeader end) then
                    zone = TS(function() return info.title end)
                elseif not TB(function() return info.isHidden end) then
                    local qid = TN(function() return info.questID end)
                    if qid then
                        local q = {
                            id = qid, i = i, zone = zone,
                            title = TS(function() return info.title end)
                                or TS(function() return C_QuestLog.GetTitleForQuestID(qid) end),
                            lv = TN(function() return C_QuestLog.GetQuestDifficultyLevel(qid) end)
                                or TN(function() return info.level end),
                            grp = TN(function() return info.suggestedGroup end),
                            freq = TN(function() return info.frequency end),
                            done = TB(function() return C_QuestLog.IsComplete(qid) end),
                            ready = TB(function() return C_QuestLog.ReadyForTurnIn(qid) end),
                            failed = TB(function() return C_QuestLog.IsFailed(qid) end),
                            trivial = TB(function() return C_QuestLog.IsQuestTrivial(qid) end),
                            watch = TN(function() return C_QuestLog.GetQuestWatchType(qid) end),
                            focus = (focus == qid) or nil,
                            canAbandon = TB(function() return C_QuestLog.CanAbandonQuest(qid) end),
                            -- the game's own "can be shared" (QuestMapFrame greys Share Quest without it); with `party` below
                            push = (TB(function() return C_QuestLog.IsPushableQuest(qid) end) == true) or nil,
                            wp = TS(function() return C_QuestLog.GetNextWaypointText(qid) end),
                            obj = objectives(qid),
                        }
                        local p = here[qid]
                        if p then q.x, q.y = p[1], p[2] end
                        -- the quest's column on the tap pad (stable while it is in the log)
                        if WC.pad then q.slot = WC.pad.slotFor("quest", qid) end
                        -- Description + objective summary BY LOG INDEX, so the player's quest-log
                        -- selection is never changed behind their back. If this build ignores
                        -- the index argument, every quest comes back with the same text; that is
                        -- detected below and the texts are dropped rather than shown wrongly.
                        local okT, desc, objText = CALL(function() return GetQuestLogQuestText(i) end)
                        if okT then
                            if type(desc) == "string" and WC.isPlain(desc) and desc ~= "" then q.desc = desc end
                            if type(objText) == "string" and WC.isPlain(objText) and objText ~= "" then q.otext = objText end
                        end
                        local okd, d2 = CALL(function() return C_QuestLog.GetDistanceSqToQuest(qid) end)
                        if okd and type(d2) == "number" and WC.isPlain(d2) and d2 >= 0 then
                            dist[qid] = math.floor(math.sqrt(d2))
                        end
                        list[#list + 1] = q
                    end
                end
            end
        end
    end

    -- the index-ignored check (see above)
    local descMode = "index"
    if #list > 1 then
        local first, same = list[1].desc, true
        for k = 2, #list do if list[k].desc ~= first then same = false break end end
        if same and first ~= nil then
            descMode = "ignored"
            for _, q in ipairs(list) do q.desc = nil; q.otext = nil end
        end
    end

    -- free the pad columns of quests that left the log (quarantined, never reused at once)
    if WC.pad then
        local present = {}
        for _, q in ipairs(list) do present[q.id] = true end
        WC.pad.retain("quest", present)
    end

    local okR, range = CALL(function() return UnitQuestTrivialLevelRange("player") end)
    local doc = {
        pad = WC.pad and WC.pad.describe() or nil,
        v = QV,
        n = #list,
        list = list,
        focus = focus,
        mapID = mapID,
        plv = SN(function() return UnitLevel("player") end),
        -- q38: the map module's portrait (name, class file, the class's own word) and the log headers that are the player's class or
        -- professions (its untracked tabs sort them after the zone's)
        me = { n = TS(function() return (UnitName("player")) end), c = TS(function() return (select(2, UnitClass("player"))) end),
               cl = TS(function() return (UnitClass("player")) end) },
        own = WC.ownHeaders(),
        trivRange = (okR and type(range) == "number" and WC.isPlain(range)) and range or nil,
        cb = TB(function() return UnitAffectingCombat("player") end),
        -- in a party or raid: the other half of the game's rule for Share Quest (IsPushableQuest AND IsInGroup)
        party = TB(function() return IsInGroup() end),
        descMode = descMode,
        maxQ = SN(function() return C_QuestLog.GetMaxNumQuestsCanAccept() end),
        sec = { secret = tally.secret, threw = tally.threw },
    }
    return doc, dist
end

-- ---------------------------------------------------------------- QuestieDB's spots (q19)
-- THE READER (the user's sign-off 2026-10-06 on the look: "Looks good, build it"). QuestieDB (Questie's data-only add-on, OPTIONAL:
-- read only when the user installed it) fills in where the GAME shows nothing. Each quest of the list gets
--   qdb = { src = "qdb", turnin = {{m, x, y, n}}, obj = {{m, x, y, i}, ...} }   and the document  qdbMaps = { ["<uiMapID>"] = name }
-- turnin: the finisher's name and its spot nearest the player. obj (only for a quest the game shows no point for): per GAME
-- objective number i (matched to QuestieDB's objectives by name), the spots on the player's map nearest first (up to QDB_TRACKED
-- for a tracked quest, 1 otherwise), or else ONE spot elsewhere (the room says "in <zone>"). Spawns are QuestieDB's Era zone
-- percentages,
-- converted with its EraToForever and mapped to uiMaps with its own areaId -> uiMapID table. QuestieDB's static data is read once
-- per quest per session; the order is recomputed on each emit (kept OUT of the change test, like the distances, so walking never
-- re-sends the document). Every call is guarded: a missing or odd QuestieDB just means no qdb fields.
local QDB_TRACKED = 8           -- spots per objective on the player's map, for a tracked quest (the map's layer)
local QDB_MAX_SPOTS = 400       -- spots read per objective at most (a common mob can have hundreds)
local QDB_MAX_DROPPERS = 8      -- NPCs / objects that drop an item objective, at most
local qdb = { ok = nil, cache = {} }

local function qdbReady()
    if qdb.ok ~= nil then return qdb.ok end
    qdb.ok = false
    local L = _G.LibQuestieDB
    if type(L) ~= "table" or type(L.RequireContract) ~= "function" then return false end
    local okC, okR = pcall(L.RequireContract, 1)
    if not (okC and okR == true) then return false end
    qdb.L = L
    qdb.Q = type(L.QuestDB) == "table" and L.QuestDB or _G.QuestDB
    qdb.N = type(L.NpcDB) == "table" and L.NpcDB or _G.NpcDB
    qdb.O = type(L.ObjectDB) == "table" and L.ObjectDB or _G.ObjectDB
    qdb.I = type(L.ItemDB) == "table" and L.ItemDB or _G.ItemDB
    -- areaId -> uiMapID: QuestieDB's support data, a Lua chunk in a string (as Questie's own ZoneDB loads it); the override wins
    local area2map = {}
    local okZ, zdb = pcall(function() return L.Support.Get("ZoneDB") end)
    for _, key in ipairs({ "areaIdToUiMapId", "areaIdToUiMapIdOverride" }) do
        local src = okZ and type(zdb) == "table" and type(zdb.private) == "table" and zdb.private[key] or nil
        local t = nil
        if type(src) == "string" then
            local f = (loadstring or load)(src)
            if f then local okT, tt = pcall(f); if okT and type(tt) == "table" then t = tt end end
        elseif type(src) == "table" then
            t = src
        end
        if t then for k, v in pairs(t) do if type(k) == "number" and type(v) == "number" then area2map[k] = v end end end
    end
    qdb.area2map = area2map
    qdb.ok = type(qdb.Q) == "table" and type(qdb.N) == "table" and next(area2map) ~= nil
    return qdb.ok
end

local function qdbCall(db, fn, id)
    if type(db) ~= "table" or type(db[fn]) ~= "function" then return nil end
    local ok, v = pcall(db[fn], id)
    if ok then return v end
end

-- a spawns table ({[areaId] = {{x, y}, ...}}) as { m = uiMapID, x, y (0..1) }, converted to Forever
local function spawnsFrom(sp, into)
    if type(sp) ~= "table" then return end
    for area, coords in pairs(sp) do
        local m = qdb.area2map[area]
        if type(m) == "number" and m > 0 and type(coords) == "table" then
            for _, c in ipairs(coords) do
                if #into >= QDB_MAX_SPOTS then return end
                local x, y = type(c) == "table" and c[1], type(c) == "table" and c[2]
                if type(x) == "number" and type(y) == "number" and x >= 0 and y >= 0 then
                    local okE, fx, fy = pcall(qdb.L.EraToForever, area, x, y)
                    if okE and type(fx) == "number" and type(fy) == "number" and fx >= 0 and fy >= 0 then
                        into[#into + 1] = { m = m, x = fx / 100, y = fy / 100 }
                    end
                end
            end
        end
    end
end
-- one NPC's / object's spawns
local function spawnsOf(db, id, into) spawnsFrom(qdbCall(db, "spawns", id), into) end

local function lower(v) return type(v) == "string" and v ~= "" and v:lower() or nil end

-- QuestieDB's objectives, matched to the GAME's objective numbers by name (the game's text names the mob / item / object)
local function matchObjectives(qid, gameObjs)
    local qo = qdbCall(qdb.Q, "objectives", qid)
    if type(qo) ~= "table" then qo = {} end
    local cands = {}
    for _, c in ipairs(type(qo[1]) == "table" and qo[1] or {}) do
        cands[#cands + 1] = { kind = "npc", ids = { c[1] }, name = lower(c[2]) or lower(qdbCall(qdb.N, "name", c[1])) }
    end
    for _, c in ipairs(type(qo[2]) == "table" and qo[2] or {}) do
        cands[#cands + 1] = { kind = "obj", ids = { c[1] }, name = lower(c[2]) or lower(qdbCall(qdb.O, "name", c[1])) }
    end
    for _, c in ipairs(type(qo[3]) == "table" and qo[3] or {}) do
        cands[#cands + 1] = { kind = "item", ids = { c[1] }, name = lower(c[2]) or lower(qdbCall(qdb.I, "name", c[1])) }
    end
    for _, c in ipairs(type(qo[5]) == "table" and qo[5] or {}) do          -- kill credit: several NPCs count as one
        local ids = {}
        if type(c[1]) == "table" then for _, n in ipairs(c[1]) do ids[#ids + 1] = n end end
        if c[2] then ids[#ids + 1] = c[2] end
        cands[#cands + 1] = { kind = "npc", ids = ids, name = lower(c[3]) or lower(qdbCall(qdb.N, "name", c[2])) }
    end
    -- q33: a "scout" objective (Questie's triggerEnd: its text and its spot(s) per zone, e.g. "Scout through the Fargodeep Mine")
    local te = qdbCall(qdb.Q, "triggerEnd", qid)
    if type(te) == "table" and type(te[2]) == "table" then
        cands[#cands + 1] = { kind = "spot", ids = {}, sp = te[2], name = lower(te[1]) }
    end
    local out, used, open = {}, {}, {}
    for i, g in ipairs(gameObjs) do
        local t = lower(g.t)
        local hit = false
        if t then
            for k, c in ipairs(cands) do
                if not used[k] and c.name and t:find(c.name, 1, true) then out[i] = c; used[k] = true; hit = true; break end
            end
        end
        if not hit then open[#open + 1] = i end
    end
    -- one game objective and one QuestieDB objective left over (a renamed item / mob): they belong together
    local rest = {}
    for k = 1, #cands do if not used[k] then rest[#rest + 1] = cands[k] end end
    if #open == 1 and #rest == 1 then out[open[1]] = rest[1] end
    return out
end

local function spotsFor(c)
    local pts = {}
    if c.kind == "npc" then
        for _, id in ipairs(c.ids) do spawnsOf(qdb.N, id, pts) end
    elseif c.kind == "obj" then
        for _, id in ipairs(c.ids) do spawnsOf(qdb.O, id, pts) end
    elseif c.kind == "spot" then
        spawnsFrom(c.sp, pts)
    else                                                                -- an item: whoever drops it
        local npcs = qdbCall(qdb.I, "npcDrops", c.ids[1])
        if type(npcs) == "table" then for k, n in ipairs(npcs) do if k > QDB_MAX_DROPPERS then break end; spawnsOf(qdb.N, n, pts) end end
        local objs = qdbCall(qdb.I, "objectDrops", c.ids[1])
        if type(objs) == "table" then for k, o in ipairs(objs) do if k > QDB_MAX_DROPPERS then break end; spawnsOf(qdb.O, o, pts) end end
    end
    return pts
end

-- the static part, once per quest per session
-- q39 THE OBJECTIVES' WORDS AS THE GAME GIVES THEM NOW (device logs test15 / test18 / qdb: right after login the game's objective
-- texts come WITHOUT the creature's name - "4/4   slain" - until its name cache fills; the name match then found nothing and was kept
-- for the whole session, so no quest ever got its QuestieDB objective areas). The match is made again whenever the words change.
local function objSig(q)
    local t = {}
    for i, g in ipairs(q.obj or {}) do t[i] = g.t or "" end
    return table.concat(t, "|")
end
local function qdbQuest(q)
    local c = qdb.cache[q.id]
    local sig = objSig(q)
    if c and c.sig ~= sig then
        c.obj, c.cand, c.areas, c.sig = {}, {}, nil, sig
        for i, cand in pairs(matchObjectives(q.id, q.obj or {})) do c.obj[i] = spotsFor(cand); c.cand[i] = cand end
    end
    if c then return c end
    c = { obj = {}, cand = {}, sig = sig }
    local fin = qdbCall(qdb.Q, "finishedBy", q.id)
    if type(fin) == "table" then
        local npc = type(fin[1]) == "table" and fin[1][1] or nil
        local obj = type(fin[2]) == "table" and fin[2][1] or nil
        local pts = {}
        if npc then spawnsOf(qdb.N, npc, pts); c.turnName = qdbCall(qdb.N, "name", npc)
        elseif obj then spawnsOf(qdb.O, obj, pts); c.turnName = qdbCall(qdb.O, "name", obj) end
        if type(c.turnName) ~= "string" then c.turnName = nil end
        c.turnPts = pts
    end
    for i, cand in pairs(matchObjectives(q.id, q.obj or {})) do c.obj[i] = spotsFor(cand); c.cand[i] = cand end
    qdb.cache[q.id] = c
    return c
end

-- a map's size in yards (C_Map.GetMapWorldSize), once per map: on ONE map, distance = the normalized offset scaled by it, so the
-- spots never need a world conversion each (a common mob has hundreds); 1 x 1 if the client has no such call
local mapSize = {}
local function sizeOf(m)
    local sz = mapSize[m]
    if not sz then
        local ok, w, h = pcall(function() return C_Map.GetMapWorldSize(m) end)
        sz = (ok and type(w) == "number" and type(h) == "number" and w > 0 and h > 0) and { w, h } or { 1, 1 }
        mapSize[m] = sz
    end
    return sz[1], sz[2]
end

local function r4(v) return math.floor(v * 10000 + 0.5) / 10000 end

-- QuestieDB's blocks onto the document's quests (after the change test: the order follows the player)
local function attachQdb(doc)
    if not qdbReady() then return end
    local pm = doc.mapID
    local px, py = nil, nil
    if pm then
        local ok, pos = pcall(function() return C_Map.GetPlayerMapPosition(pm, "player") end)
        if ok and pos then
            local okXY, x, y = pcall(function() return pos:GetXY() end)
            if okXY and type(x) == "number" and type(y) == "number" and WC.isPlain(x) and WC.isPlain(y) then px, py = x, y end
        end
    end
    local w, h = 1, 1
    if pm then w, h = sizeOf(pm) end
    -- squared yards to a spot ON THE PLAYER'S MAP (anything else is "elsewhere")
    local function d2(pt)
        if pt.m ~= pm or not px then return math.huge end
        return ((pt.x - px) * w) ^ 2 + ((pt.y - py) * h) ^ 2
    end
    local maps = {}
    local function out(pt, extra)
        maps[pt.m] = true
        local e = { m = pt.m, x = r4(pt.x), y = r4(pt.y) }
        if extra then for k, v in pairs(extra) do e[k] = v end end
        return e
    end
    -- the nearest spot on the player's map, or else the first one (elsewhere: only its map's name is shown)
    local function nearest(pts)
        local best, bd = nil, math.huge
        for _, pt in ipairs(pts) do local d = d2(pt); if not best or d < bd then best, bd = pt, d end end
        return best
    end
    -- q38 THE GAME'S POINT IS THE QUEST'S OWN ONLY WHEN IT IS IN THIS ZONE (the user after test20, D's diagnosis: in Elwynn the game
    -- gives Westfall's quests a point CLAMPED to Elwynn's border, e.g. 92744 "Murloc Gills" at 0.018, 0.729, so QuestieDB's real spots
    -- were never attached): own = the quest log's zone is this map, or QuestieDB puts one of its objectives on this map, or QuestieDB
    -- knows none of its objectives. Otherwise the point moves aside (`ex` / `ey`, `edge`: the direction hint) and the quest gets
    -- QuestieDB's spots as if the game showed none, so the room and the map show them "in Westfall" / on their own map
    local pmName = pm and TS(function() local i = C_Map.GetMapInfo(pm); return i and i.name end) or nil
    local function ownPoint(q, c)
        if q.zone and pmName and q.zone == pmName then return true end
        local any = false
        for _, pts in pairs(c.obj) do
            for _, pt in ipairs(pts) do any = true; if pt.m == pm then return true end end
        end
        if any then return false end
        -- q39 (D's real-data check: 92742 "Testing the Wells", log zone Westfall, clamped at 0.144, 0.819, QuestieDB knows no spot): with
        -- nothing from QuestieDB, a point of ANOTHER zone's quest in this map's border band is the clamp, not a place here
        local edge = math.min(q.x, 1 - q.x, q.y, 1 - q.y) < 0.2
        return not (q.zone and pmName and q.zone ~= pmName and edge)
    end
    for _, q in ipairs(doc.list) do
        local c = qdbQuest(q)
        if q.x and q.y and not ownPoint(q, c) then
            q.ex, q.ey, q.edge = q.x, q.y, true
            q.x, q.y = nil, nil
        end
        local block = { src = "qdb" }
        if c.turnName and c.turnPts and #c.turnPts > 0 then
            local pt = nearest(c.turnPts)
            if pt then block.turnin = { out(pt, { n = c.turnName }) } end
        end
        -- q39 (the user, a GENERAL rule for every quest: "it points to the right place but the quest area is undefined"): the game gives
        -- a point at most, never an area, so EVERY quest gets QuestieDB's objective spots; a game point stays the arrow's target
        do
            local objs, cap = {}, (q.watch ~= nil) and QDB_TRACKED or 1
            for i, pts in pairs(c.obj) do
                local here = {}
                for _, pt in ipairs(pts) do if pt.m == pm then here[#here + 1] = pt end end
                if #here > 0 then
                    table.sort(here, function(a, b) return d2(a) < d2(b) end)
                    for k = 1, math.min(cap, #here) do objs[#objs + 1] = out(here[k], { i = i }) end
                elseif #pts > 0 then
                    local pt = nearest(pts)
                    if pt then objs[#objs + 1] = out(pt, { i = i }) end
                end
            end
            if #objs > 0 then block.obj = objs end
        end
        if block.turnin or block.obj then q.qdb = block end
    end
    local names = {}
    for m in pairs(maps) do
        local ok, info = pcall(function() return C_Map.GetMapInfo(m) end)
        local n = ok and type(info) == "table" and TS(function() return info.name end) or nil
        if n then names[tostring(m)] = n end
    end
    if next(names) then doc.qdbMaps = names end
end
WC.questieAttach = attachQdb        -- for the harness
WC.questieReset = function() qdb.ok = nil; qdb.cache = {}; for k in pairs(mapSize) do mapSize[k] = nil end end

-- ---------------------------------------------------------------- emission

local lastKey = nil
local areasSend                         -- q33 (below): QuestieDB's objective areas, after each quests document

-- Collect, and send if changed (or forced). Returns true when something was written.
-- Does NOT push: callers decide, so a heartbeat ride-along costs no flush of its own.
local function emitQuests(force)
    local doc, dist = WC.collectQuests()
    local key = WC.encode(doc)
    if key == lastKey and not force then return false end
    lastKey = key
    for _, q in ipairs(doc.list) do q.dist = dist[q.id] end
    pcall(attachQdb, doc)               -- q19: QuestieDB's spots, outside the change test (they follow the player)
    WC.send(WC.encode({ type = "quests", q = doc }))
    if areasSend then pcall(areasSend, doc) end      -- q33: sent only when the map or the unfinished objectives changed
    return true
end

-- QUEST_LOG_UPDATE arrives in bursts (several per frame on a kill), so coalesce.
local queued = false
local function soon()
    if queued then return end
    queued = true
    C_Timer.After(0.25, function()
        queued = false
        if emitQuests(false) then WC.push() end
    end)
end

local f = CreateFrame("Frame")
f:SetScript("OnEvent", function() soon() end)
local WANT = {
    "PLAYER_ENTERING_WORLD", "QUEST_LOG_UPDATE", "QUEST_ACCEPTED", "QUEST_REMOVED",
    "QUEST_TURNED_IN", "QUEST_WATCH_LIST_CHANGED", "SUPER_TRACKING_CHANGED",
    "ZONE_CHANGED_NEW_AREA", "PLAYER_LEVEL_UP",
    -- joining or leaving a group changes whether Share Quest can be used
    "GROUP_ROSTER_UPDATE",
    -- combat state gates the app's buttons (no injected commands in combat)
    "PLAYER_REGEN_DISABLED", "PLAYER_REGEN_ENABLED",
}
local missing = {}
for _, e in ipairs(WANT) do
    local ok, registered = pcall(function() return f:RegisterEvent(e) end)
    if not ok or registered == false then missing[#missing + 1] = e end
end
if #missing > 0 then
    pcall(function() WC.send(WC.encode({ type = "cap", quests = QV, missingEvents = missing })) end)
end

-- QUESTIEDB (q18; the user's picks 2026-10-06 via tracking: "Read Questie in the game", "QuestieDB only, nothing of Questie's on
-- top"). An OPTIONAL data source: the data-only add-on behind Questie (github.com/Questie/QuestieDB, GPLv3), read only when the user
-- has installed it; `## OptionalDeps: QuestieDB` loads it before us. Nothing of it is bundled, and nothing of Questie is drawn.
-- This version only PROBES it, once, read-only, a few seconds into the world: is it there, its data contract, its memory, how long
-- a lookup takes, and how many quests of the player's own log it knows (its docs: the original quests with Forever coordinates,
-- plus some Forever-new zones). One "questiedb" document; without it, present = false. Nothing is shown from it yet.
local QDB_PROBE_DELAY = 5
local function questieProbe()
    local L = _G.LibQuestieDB
    local out = { type = "questiedb", v = QV, present = type(L) == "table" }
    if out.present then
        out.contract = TN(function() return L.contractVersion end)
        local okC, okReq, msg = pcall(function() return L.RequireContract(1) end)
        out.ok = (okC and okReq == true) and true or false
        if not out.ok then out.why = tostring(okC and msg or okReq) end
        pcall(function() UpdateAddOnMemoryUsage() end)
        out.memKB = TN(function() return math.floor(GetAddOnMemoryUsage("QuestieDB") + 0.5) end)
        local Q = type(L.QuestDB) == "table" and L.QuestDB or _G.QuestDB
        if out.ok and type(Q) == "table" and type(Q.name) == "function" then
            local function knows(id)
                local okN, name = pcall(Q.name, id)
                return okN and type(name) == "string" and name ~= ""
            end
            -- the player's own log: how much of it the database knows
            local known, total, unknown = 0, 0, {}
            local n = TN(function() return C_QuestLog.GetNumQuestLogEntries() end) or 0
            for i = 1, n do
                local okI, info = CALL(function() return C_QuestLog.GetInfo(i) end)
                if okI and type(info) == "table" and not info.isHeader and type(info.questID) == "number" then
                    total = total + 1
                    if knows(info.questID) then known = known + 1 elseif #unknown < 8 then unknown[#unknown + 1] = info.questID end
                end
            end
            out.log = { known = known, total = total, unknown = unknown }
            -- two samples: an original quest, and a Forever-new id seen in this player's log on 2026-10-06
            out.sample = { [1] = knows(15), [2] = knows(94466) }
            -- one lookup's cost
            if type(Q.startedBy) == "function" then
                local t0 = debugprofilestop()
                pcall(Q.startedBy, 15)
                out.lookupMs = math.floor((debugprofilestop() - t0) * 100 + 0.5) / 100
            end
        end
    end
    pcall(function() WC.send(WC.encode(out)); WC.push() end)
    return out
end
WC.questieProbe = questieProbe          -- for the harness

-- QUESTIEDB'S MAP DATA, MEASURED FOR MAP (q20; the user's pick 2026-10-06 via tracking: "Try QuestieDB's NPC + node data first";
-- this add-on stays the ONE QuestieDB reader). A second probe document, "questiedbmap", once per session right after the first,
-- never on the regular feed. On the player's current uiMap: NPC services per type (count + QDB_SAMPLES samples), rares, and a
-- list of named objects (mailbox, herbs, ore, chests), plus what the full passes cost. Core's coordinate self test comes first.
-- It runs as a coroutine with QDB_SCAN_MS of work per frame, so the one-time pass over every NPC never hitches the game; nothing
-- that can yield sits inside a pcall (Lua 5.1 cannot yield across one): the coroutine itself is the guard, and an error still
-- sends what was gathered. Afterwards QuestieDB's field caches are dropped (InvalidateCache), so the pass leaves nothing behind.
local QDB_SCAN_MS = 4          -- work per frame, ms
local QDB_SAMPLES = 3
-- + BATTLEMASTER, PETITIONER (the guild master), TABARD_DESIGNER (Map, 2026-10-12, approved via tracking: the map's NPC icons give
-- them the game's own icons, and a seen one is classified by these flags)
local QDB_SERVICES = { "VENDOR", "REPAIR", "TRAINER", "FLIGHT_MASTER", "INNKEEPER", "BANKER", "AUCTIONEER", "STABLEMASTER",
    "SPIRIT_HEALER", "BATTLEMASTER", "PETITIONER", "TABARD_DESIGNER" }
-- Classic's npcFlags, used only when QuestieDB's own enum is unreadable (Forever's flavour falls back to Classic's rules)
local QDB_FLAGS_CLASSIC = { VENDOR = 4, FLIGHT_MASTER = 8, TRAINER = 16, SPIRIT_HEALER = 32, INNKEEPER = 128, BANKER = 256,
    AUCTIONEER = 4096, STABLEMASTER = 8192, REPAIR = 16384, PETITIONER = 512, TABARD_DESIGNER = 1024, BATTLEMASTER = 2048 }
local QDB_OBJECTS = { "Mailbox", "Peacebloom", "Silverleaf", "Earthroot", "Mageroyal", "Briarthorn", "Copper Vein", "Tin Vein",
    "Silver Vein", "Battered Chest", "Solid Chest" }
-- Classic's flags have ONE trainer bit; the subname ("Warrior Trainer") tells a class trainer from the rest
local QDB_CLASSES = { Warrior = true, Paladin = true, Hunter = true, Rogue = true, Priest = true, Shaman = true, Mage = true,
    Warlock = true, Druid = true }

local function hasBit(v, b) return type(b) == "number" and b > 0 and v % (2 * b) >= b end
local function r4(v) return math.floor(v * 10000 + 0.5) / 10000 end

-- the service flag values: QuestieDB's own enum for this flavour (its rules' table when the flavour has none), else Classic's
local function flagBits()
    local L = qdb.L
    local bits, from = {}, "builtin"
    local okF, t, src = pcall(function()
        local E, fl = L.Enum.byExpansion, L.flavor
        local e = E[fl.expansion] and E[fl.expansion].npcFlags
        if type(e) == "table" then return e, fl.expansion end
        return fl.rules and E[fl.rules] and E[fl.rules].npcFlags, fl.rules
    end)
    if okF and type(t) == "table" then from = tostring(src) end
    for _, k in ipairs(QDB_SERVICES) do
        local b = okF and type(t) == "table" and t[k]
        bits[k] = type(b) == "number" and b or QDB_FLAGS_CLASSIC[k]
    end
    return bits, from
end

local function isService(f, bits)
    if type(f) ~= "number" or f <= 0 then return false end
    for _, k in ipairs(QDB_SERVICES) do if hasBit(f, bits[k]) then return true end end
    return false
end

-- runs body(st) as a coroutine, QDB_SCAN_MS of work per frame (body yields through st.pause / st.phase), and calls
-- finish(result, err) once. With st.calm it waits, not resuming, while the player is in a fight. Nothing that yields may sit
-- inside a pcall (Lua 5.1): the coroutine is the guard.
local function sliced(st, body, finish)
    st.phase, st.slice, st.cpu, st.frames = st.phase or "start", 0, {}, {}
    st.pause = function() if debugprofilestop() - st.slice > QDB_SCAN_MS then coroutine.yield() end end
    st.phaseTo = function(p) coroutine.yield(); st.phase = p end
    local co = coroutine.create(function() return body(st) end)
    local function step()
        if st.calm then
            local okC, fight = pcall(InCombatLockdown)
            if okC and fight then C_Timer.After(0.5, step); return end
        end
        st.slice = debugprofilestop()
        local ok, res = coroutine.resume(co)
        local p = st.phase
        st.cpu[p] = (st.cpu[p] or 0) + (debugprofilestop() - st.slice)
        st.frames[p] = (st.frames[p] or 0) + 1
        if not ok then finish(nil, res)
        elseif coroutine.status(co) == "dead" then finish(res)
        else C_Timer.After(0, step) end
    end
    C_Timer.After(0, step)
end

-- one NPC's / object's spawns on the areas of ONE uiMap: the count, and the first as a converted 0..1 spot
local function spotHere(db, id, here)
    local sp = qdbCall(db, "spawns", id)
    if type(sp) ~= "table" then return nil, 0 end
    local first, n = nil, 0
    for area, coords in pairs(sp) do
        if here[area] and type(coords) == "table" then
            for _, c in ipairs(coords) do
                local x, y = type(c) == "table" and c[1], type(c) == "table" and c[2]
                if type(x) == "number" and type(y) == "number" and x >= 0 and y >= 0 then
                    n = n + 1
                    if not first then
                        local okE, fx, fy = pcall(qdb.L.EraToForever, area, x, y)
                        if okE and type(fx) == "number" and type(fy) == "number" then first = { x = r4(fx / 100), y = r4(fy / 100) } end
                    end
                end
            end
        end
    end
    return first, n
end

local function mapProbeBody(st)
    local N, O = qdb.N, qdb.O
    local out = { type = "questiedbmap", v = QV }
    st.out = out
    local pause, phase = st.pause, st.phaseTo
    -- 5 (first): Core's coordinate self test, the one Map.lua uses (the player's map position vs UnitPosition)
    local okG, g = pcall(function() return WC.checkCoords() end)
    out.guard = (okG and type(g) == "table") and { ok = g.ok == true, errYards = g.errYards, why = g.why, uiMapID = g.uiMapID,
        continent = g.continent } or { ok = false, why = "no checkCoords" }
    local pm = TN(function() return C_Map.GetBestMapForUnit("player") end)
    out.uiMapID = pm
    out.mapName = TS(function() return C_Map.GetMapInfo(pm).name end)
    local here, nAreas = {}, 0
    for a, m in pairs(qdb.area2map) do if m == pm then here[a] = true; nAreas = nAreas + 1 end end
    out.areas = nAreas
    local bits, from = flagBits()
    out.flags, out.flagsFrom = bits, from
    local cost = {}
    out.cost = cost
    pcall(function() UpdateAddOnMemoryUsage() end)
    cost.memKB0 = TN(function() return math.floor(GetAddOnMemoryUsage("QuestieDB") + 0.5) end)
    cost.luaKB0 = math.floor(collectgarbage("count") + 0.5)

    -- 1 + 2: every NPC's flags and rank; spawns only for the ones that matter
    phase("npcIds")
    local ids = N.GetAllIds()
    cost.npcIds = #ids
    phase("npcScan")
    local svc, rares, trainers = {}, { n = 0, rare = 0, rareElite = 0, s = {} }, { class = 0, other = 0 }
    for _, k in ipairs(QDB_SERVICES) do svc[k] = { n = 0, s = {} } end
    for i = 1, #ids do
        local id = ids[i]
        local f, r = qdbCall(N, "npcFlags", id), qdbCall(N, "rank", id)
        local rare = r == 2 or r == 4          -- cmangos ranks: 2 rare elite, 4 rare
        local hit = nil
        if type(f) == "number" and f > 0 then
            for _, k in ipairs(QDB_SERVICES) do if hasBit(f, bits[k]) then hit = hit or {}; hit[#hit + 1] = k end end
        end
        if hit or rare then
            local spot = spotHere(N, id, here)
            if spot then
                local sample = { id = id, name = qdbCall(N, "name", id), subName = qdbCall(N, "subName", id), x = spot.x, y = spot.y }
                for _, k in ipairs(hit or {}) do
                    local b = svc[k]
                    b.n = b.n + 1
                    if #b.s < QDB_SAMPLES then b.s[#b.s + 1] = sample end
                    if k == "TRAINER" then
                        local cls = type(sample.subName) == "string" and sample.subName:match("^(%a+) Trainer$")
                        if cls and QDB_CLASSES[cls] then trainers.class = trainers.class + 1 else trainers.other = trainers.other + 1 end
                    end
                end
                if rare then
                    rares.n = rares.n + 1
                    if r == 2 then rares.rareElite = rares.rareElite + 1 else rares.rare = rares.rare + 1 end
                    if #rares.s < QDB_SAMPLES then
                        rares.s[#rares.s + 1] = { id = id, name = sample.name, subName = sample.subName, x = spot.x, y = spot.y, rank = r }
                    end
                end
            end
        end
        pause()
    end
    out.services, out.trainers, out.rares = svc, trainers, rares

    -- 3: named objects through the name index (built here, in batches, so its cost shows)
    phase("objIndex")
    cost.objIds = #O.GetAllIds()
    if type(O.BuildNameIndexAsync) == "function" then O.BuildNameIndexAsync(500) end
    phase("objects")
    local objs = {}
    for _, name in ipairs(QDB_OBJECTS) do
        local oids = qdbCall(O, "IdsByName", name)
        local row = { name = name, ids = type(oids) == "table" and #oids or 0, spawns = 0 }
        if type(oids) == "table" then
            for _, id in ipairs(oids) do local _, n = spotHere(O, id, here); row.spawns = row.spawns + n; pause() end
        end
        objs[#objs + 1] = row
    end
    out.objects = objs

    -- 4: memory with everything the passes cached, then drop QuestieDB's caches again
    pcall(function() UpdateAddOnMemoryUsage() end)
    cost.memKB1 = TN(function() return math.floor(GetAddOnMemoryUsage("QuestieDB") + 0.5) end)
    cost.luaKB1 = math.floor(collectgarbage("count") + 0.5)
    phase("release")
    pcall(N.InvalidateCache)
    pcall(O.InvalidateCache)
    return out
end

-- runs the pass to its end, one slice per frame; sends ONE "questiedbmap" document (with err when the pass broke). Since q21 it
-- runs only when called (Map has its numbers); the login runs the pins' index instead.
local function questieMapProbe()
    if not qdbReady() then return false end
    local st = {}
    local w0 = debugprofilestop()
    sliced(st, mapProbeBody, function(out, err)
        out = out or st.out or { type = "questiedbmap", v = QV }
        if err then out.err = tostring(err):sub(1, 200); out.errPhase = st.phase end
        local c = out.cost or {}
        out.cost = c
        local function ms(p) return st.cpu[p] and math.floor(st.cpu[p] * 100 + 0.5) / 100 end
        c.npcIdsMs, c.npcScanMs, c.npcScanFrames = ms("npcIds"), ms("npcScan"), st.frames.npcScan
        c.objIndexMs, c.objIndexFrames, c.objectsMs = ms("objIndex"), st.frames.objIndex, ms("objects")
        c.wallMs = math.floor(debugprofilestop() - w0 + 0.5)
        pcall(function() WC.send(WC.encode(out)); WC.push() end)
    end)
    return true
end
WC.questieMapProbe = questieMapProbe    -- for the harness

-- ---------------------------------------------------------------- QuestieDB's pins + nodes for Map (q21)
-- The shape agreed with Map 2026-10-06 (after the user's pick "Try QuestieDB's NPC + node data first"; Map draws, this add-on
-- reads). For the uiMap the player is on, TWO documents paired by "set" (one serial per map build):
--   {"type":"qdbpins","m":uiMapID,"set":n,"svc":[{id,n,s,f,fa,x,y}],"rares":[{id,n,r,p=[[x,y],...]}],
--    "obj":{"mail":[[x,y]],"forge":[[x,y]],"anvil":[[x,y]]}}
--   {"type":"qdbnodes","m":uiMapID,"set":n,"kinds":["Peacebloom",...],"herb":[[x,y,k]],"ore":[[x,y,k]],"chest":[[x,y,k]]}
-- svc: every NPC with a service flag (the probe's nine, Classic bits) and a spawn on this map; f its whole npcFlags, fa its
-- friendlyToFaction ("A" / "H" / "AH"; no faction filter here, Map decides the look). rares: rank 2 / 4 with ALL their points
-- here (rares rotate). Nodes: k is a bitmask over the document's kinds (bit i-1 = kinds[i], only names on this map); points of
-- one group on the same spot are ONE entry (ore pools share spots). Coordinates 0..1 at 4 decimals, Forever-converted.
-- WHEN: the session's INDEX is built once, a few seconds after login (every NPC's flags + rank, every node name's ids; only
-- {uiMap -> ids} is kept and QuestieDB's caches are dropped, so its memory is transient), then the player's map is sent, and
-- again on every uiMap change. Each map's pair is built in slices too and kept for the session (the last QDB_PIN_MAPS maps): a
-- return re-sends the same set, and a loading screen re-sends the current one. All of it waits while the player is in a fight;
-- a map left before its build ends is kept but not sent. No quest givers ("!" needs the user's own yes); no other maps.
-- AND EVERY QDB_PIN_BEATS-th CORE HEARTBEAT the current map's pair is sent again from the cache (q22; Map's report 2026-10-06
-- 23:08: an app restart, b90's install, and a truncated General.log left the app with no pins for the map the player stood on,
-- and nothing re-sent them while the player stayed there). The same set, so the app sees nothing new; inside the heartbeat's
-- own flush, so it costs no pad of its own.
local QDB_PIN_BEATS = 2         -- core heartbeats (15 s) between re-sends: ~30 s
-- NEAR POINTS ARE ONE (q24; measured offline on the real QuestieDB 1.0.5 data, 2026-10-07: Stormwind's 55 mailbox points had
-- 163 pairs closer than 3 yd -- one mailbox listed up to 4 times -- and Dun Morogh's 33 anvils were mostly one smithy's). On the
-- map that is a heap of pins on one spot, so points of one group closer than these are ONE (the first kept; a node's bits are
-- OR'd, as on an exact shared spot). Yards from the map's size (C_Map.GetMapWorldSize); without it, exact spots only, as before.
local QDB_MERGE_TOWN_YD = 8     -- mailbox / forge / anvil
local QDB_MERGE_NODE_YD = 3     -- herb / ore / chest
local QDB_PIN_MAPS = 4
local QDB_MAX_NODES = 1500      -- points per group per map at most (a guard: Westfall measured ~200 herb, ~170 ore)
local QDB_MAX_RARE_PTS = 12
local QDB_MAX_KINDS = 30        -- bits per document (the encoder's %d trap starts at 2^31)
local QDB_NODE_GROUPS = { "herb", "ore", "chest" }
local QDB_NODES = {
    herb = { "Peacebloom", "Silverleaf", "Earthroot", "Mageroyal", "Briarthorn", "Stranglekelp", "Bruiseweed", "Wild Steelbloom",
        "Grave Moss", "Kingsblood", "Liferoot", "Fadeleaf", "Goldthorn", "Khadgar's Whisker", "Wintersbite", "Firebloom",
        "Purple Lotus", "Arthas' Tears", "Sungrass", "Blindweed", "Ghost Mushroom", "Gromsblood", "Golden Sansam", "Dreamfoil",
        "Mountain Silversage", "Plaguebloom", "Icecap", "Black Lotus" },
    ore = { "Copper Vein", "Tin Vein", "Silver Vein", "Iron Deposit", "Gold Vein", "Mithril Deposit", "Truesilver Deposit",
        "Dark Iron Deposit", "Small Thorium Vein", "Rich Thorium Vein", "Hakkari Thorium Vein", "Ooze Covered Silver Vein",
        "Ooze Covered Gold Vein", "Ooze Covered Iron Deposit", "Ooze Covered Mithril Deposit", "Ooze Covered Truesilver Deposit",
        "Ooze Covered Thorium Vein", "Ooze Covered Rich Thorium Vein", "Incendicite Mineral Vein", "Lesser Bloodstone Deposit",
        "Indurium Mineral Vein", "Small Obsidian Chunk", "Large Obsidian Chunk" },
    chest = { "Battered Chest", "Tattered Chest", "Solid Chest", "Large Battered Chest", "Large Iron Bound Chest",
        "Large Solid Chest", "Large Mithril Bound Chest", "Mithril Bound Chest", "Battered Footlocker", "Waterlogged Footlocker",
        "Dented Footlocker", "Mossy Footlocker", "Scarlet Footlocker" },
}
local QDB_TOWN = { { "mail", "Mailbox" }, { "forge", "Forge" }, { "anvil", "Anvil" } }

local pins = {}
local function pinsReset()
    pins = { state = nil, npcBy = {}, nodeBy = {}, kinds = {}, cache = {}, lru = {}, set = 0, busy = nil, sent = nil,
             availBy = {}, alast = {}, alru = {}, aset = 0 }
end
pinsReset()

local function validSpot(coords)
    if type(coords) ~= "table" then return false end
    for _, c in ipairs(coords) do
        if type(c) == "table" and type(c[1]) == "number" and type(c[2]) == "number" and c[1] >= 0 and c[2] >= 0 then return true end
    end
    return false
end

-- the uiMaps one NPC / object has a spawn on, each once
local function mapsOf(db, id)
    local sp, out, seen = qdbCall(db, "spawns", id), {}, {}
    if type(sp) ~= "table" then return out end
    for area, coords in pairs(sp) do
        local m = qdb.area2map[area]
        if type(m) == "number" and m > 0 and not seen[m] and validSpot(coords) then seen[m] = true; out[#out + 1] = m end
    end
    return out
end

-- every spot of a spawns table on the areas of ONE uiMap, as converted {x, y} (0..1), at most cap
local function spotsIn(sp, here, cap)
    local out = {}
    if type(sp) ~= "table" then return out end
    for area, coords in pairs(sp) do
        if here[area] and type(coords) == "table" then
            for _, c in ipairs(coords) do
                if #out >= cap then return out end
                local x, y = type(c) == "table" and c[1], type(c) == "table" and c[2]
                if type(x) == "number" and type(y) == "number" and x >= 0 and y >= 0 then
                    local okE, fx, fy = pcall(qdb.L.EraToForever, area, x, y)
                    if okE and type(fx) == "number" and type(fy) == "number" and fx >= 0 and fy >= 0 then
                        out[#out + 1] = { r4(fx / 100), r4(fy / 100) }
                    end
                end
            end
        end
    end
    return out
end

-- every spawn of one NPC / object there
local function spotsHere(db, id, here, cap) return spotsIn(qdbCall(db, "spawns", id), here, cap) end

local function areasOf(m)
    local here = {}
    for a, mm in pairs(qdb.area2map) do if mm == m then here[a] = true end end
    return here
end

-- THE INDEX: {uiMap -> service / rare NPC ids} and {uiMap -> {node id, kind}}; QuestieDB's caches dropped afterwards
local function pinsIndexBody(st)
    local N, O, pause = qdb.N, qdb.O, st.pause
    local bits = flagBits()
    local npcBy, nodeBy, kinds = {}, {}, {}
    st.phaseTo("npcs")
    local ids = N.GetAllIds()
    for i = 1, #ids do
        local id = ids[i]
        local r = qdbCall(N, "rank", id)
        if r == 2 or r == 4 or isService(qdbCall(N, "npcFlags", id), bits) then
            for _, m in ipairs(mapsOf(N, id)) do
                local t = npcBy[m]
                if not t then t = {}; npcBy[m] = t end
                t[#t + 1] = id
            end
        end
        pause()
    end
    st.phaseTo("nodes")
    if type(O.BuildNameIndexAsync) == "function" then O.BuildNameIndexAsync(500) end
    local function addKind(g, name)
        local oids = qdbCall(O, "IdsByName", name)
        if type(oids) ~= "table" or #oids == 0 then return end
        kinds[#kinds + 1] = { g = g, name = name }
        local k = #kinds
        for _, oid in ipairs(oids) do
            for _, m in ipairs(mapsOf(O, oid)) do
                local t = nodeBy[m]
                if not t then t = {}; nodeBy[m] = t end
                t[#t + 1] = { oid, k }
            end
            pause()
        end
    end
    for _, g in ipairs(QDB_NODE_GROUPS) do for _, name in ipairs(QDB_NODES[g]) do addKind(g, name) end end
    for _, t in ipairs(QDB_TOWN) do addKind(t[1], t[2]) end
    -- q31: {uiMap -> {quest, giver, is object}} for every quest whose starter (an NPC or an object; an item has no place) stands there
    st.phaseTo("quests")
    local Q = qdb.Q
    local availBy, gMaps, DBS = {}, {}, { N, O }
    local qids = (type(Q) == "table" and type(Q.GetAllIds) == "function") and Q.GetAllIds() or {}
    for i = 1, #qids do
        local qid = qids[i]
        local sb = qdbCall(Q, "startedBy", qid)
        if type(sb) == "table" then
            local seen = {}
            for kind = 1, 2 do
                for _, gid in ipairs(type(sb[kind]) == "table" and sb[kind] or {}) do
                    local key = kind .. ":" .. tostring(gid)
                    local ms = gMaps[key]
                    if not ms then ms = mapsOf(DBS[kind], gid); gMaps[key] = ms end
                    for _, m in ipairs(ms) do
                        if not seen[m .. key] then
                            seen[m .. key] = true
                            local t = availBy[m]
                            if not t then t = {}; availBy[m] = t end
                            t[#t + 1] = { qid, gid, kind == 2 }
                        end
                    end
                end
            end
        end
        pause()
    end
    st.phaseTo("release")
    pcall(N.InvalidateCache)
    pcall(O.InvalidateCache)
    return { npcBy = npcBy, nodeBy = nodeBy, kinds = kinds, bits = bits, availBy = availBy }
end

-- ONE map's pair of documents
local function pinsMapBody(st, m)
    local N, O, pause, bits = qdb.N, qdb.O, st.pause, pins.bits
    local here = areasOf(m)
    local svc, rares = {}, {}
    for _, id in ipairs(pins.npcBy[m] or {}) do
        local pts = spotsHere(N, id, here, QDB_MAX_RARE_PTS)
        if pts[1] then
            local f, r, name = qdbCall(N, "npcFlags", id), qdbCall(N, "rank", id), qdbCall(N, "name", id)
            if isService(f, bits) then
                svc[#svc + 1] = { id = id, n = name, s = qdbCall(N, "subName", id), f = f, fa = qdbCall(N, "friendlyToFaction", id),
                    x = pts[1][1], y = pts[1][2] }
            end
            if r == 2 or r == 4 then rares[#rares + 1] = { id = id, n = name, r = r, p = pts } end
        end
        pause()
    end
    local obj, groups = { mail = {}, forge = {}, anvil = {} }, { herb = {}, ore = {}, chest = {} }
    local docKinds, bitOf, seen = {}, {}, {}
    -- the merge (QDB_MERGE_*_YD): per group a grid of r-yard cells, so a point is only compared with the 3 x 3 cells around it
    local w, h = sizeOf(m)
    local yards = not (w == 1 and h == 1)
    local grids = {}
    local function cellOf(p, r) return math.floor(p[1] * w / r) * 100000 + math.floor(p[2] * h / r) end
    local function near(g, p, r)
        if not yards then return nil end
        local grid = grids[g]
        if not grid then return nil end
        local cx, cy = math.floor(p[1] * w / r), math.floor(p[2] * h / r)
        for gx = cx - 1, cx + 1 do
            for gy = cy - 1, cy + 1 do
                for _, q in ipairs(grid[gx * 100000 + gy] or {}) do
                    if ((q[1] - p[1]) * w) ^ 2 + ((q[2] - p[2]) * h) ^ 2 < r * r then return q end
                end
            end
        end
    end
    local function keep(g, p, r)
        if not yards then return end
        local grid = grids[g]
        if not grid then grid = {}; grids[g] = grid end
        local c = cellOf(p, r)
        local cell = grid[c]
        if not cell then cell = {}; grid[c] = cell end
        cell[#cell + 1] = p
    end
    for _, e in ipairs(pins.nodeBy[m] or {}) do
        local kind = pins.kinds[e[2]]
        local town, b = obj[kind.g], nil
        local into = town or groups[kind.g]
        if into then
            if not town then
                b = bitOf[kind.name]
                if not b and #docKinds < QDB_MAX_KINDS then
                    docKinds[#docKinds + 1] = kind.name
                    b = 2 ^ (#docKinds - 1)
                    bitOf[kind.name] = b
                end
            end
            local r = town and QDB_MERGE_TOWN_YD or QDB_MERGE_NODE_YD
            for _, p in ipairs(spotsHere(O, e[1], here, QDB_MAX_NODES)) do
                local key = kind.g .. ":" .. math.floor(p[1] * 10000 + 0.5) .. ":" .. math.floor(p[2] * 10000 + 0.5)
                local prev = seen[key] or near(kind.g, p, r)
                if prev then
                    if b and not hasBit(prev[3], b) then prev[3] = prev[3] + b end
                elseif #into < QDB_MAX_NODES then
                    local pt = town and { p[1], p[2] } or { p[1], p[2], b or 0 }
                    into[#into + 1] = pt
                    seen[key] = pt
                    keep(kind.g, pt, r)
                end
            end
        end
        pause()
    end
    pins.set = pins.set + 1
    return {
        pins = { type = "qdbpins", v = QV, m = m, set = pins.set, svc = svc, rares = rares, obj = obj },
        nodes = { type = "qdbnodes", v = QV, m = m, set = pins.set, kinds = docKinds, herb = groups.herb, ore = groups.ore,
            chest = groups.chest },
    }
end

local function playerMap() return TN(function() return C_Map.GetBestMapForUnit("player") end) end

local function pinsSend(m, noPush)
    local c = pins.cache[m]
    if not c then return false end
    pcall(function()
        WC.send(WC.encode(c.pins)); WC.send(WC.encode(c.nodes))
        if not noPush then WC.push() end
    end)
    pins.sent = m
    return true
end

-- ---------------------------------------------------------------- QuestieDB's AVAILABLE quests for Map (q31)
-- The user 2026-10-07 (tracking): "The mini map is missing quests that I haven't accepted yet. Can this be populated from questie
-- db?" -- the yes the pins waited for ("!" givers only with the user's own yes). The app's "!" came from a Wrath-era bundle that cannot
-- know Forever's own quests (721 ids 80000+) or its new start zone Zephras Isle; QuestieDB has them. For the player's map:
--   {"type":"qdbavail","m":uiMapID,"set":n,"q":[{i, n, lv, x, y, f, g, o}, ...]}   (shape agreed with Map; Map draws the "!")
-- i quest, n its title, lv its level, x/y the giver's spot on this map nearest the player, g the giver, o 1 for an object;
-- f bits: 1 trivial (grey for this level), 2 repeatable / daily, 4 "soon" (all fits but requiredLevel is 1-2 above), 8 a holiday /
-- world event's quest (q32; only offered while it runs: Map hides it unless asked): every kind is
-- sent and MAP filters per the user's pick. AVAILABLE = Questie's core checks on QuestieDB's fields: not on the player and not done,
-- the race / class masks (QuestieDB's raceMaskById: Forever's races too), requiredLevel / requiredMaxLevel, preQuestSingle (one
-- done) / preQuestGroup (all done), exclusiveTo (none done or on), disabledByQuest, the giver friendly to the player's faction.
-- q32 also checks requiredSkill (the player's professions) and leaves out quests > QDB_AVAIL_FAR levels above. NOT checked:
-- reputation / spell -- such quests show. Sent when the list for the map changes (a map change,
-- a quest taken / turned in / dropped, a level), and again with the pins' heartbeat re-send.
local QDB_AVAIL_SOON = 2         -- levels above the player a "soon" quest may need
-- q32 (measured on the real QuestieDB data offline: a level-1 Human in Elwynn got Carnival Boots, Winter's Presents, Runetotem the
-- Elder): the quest sorts of the HOLIDAYS / world events -- such a quest is only offered while its event runs, so it is flagged 8 and
-- Map hides it unless asked: Seasonal, Day of the Dead, Darkmoon Faire, the AQ War Effort, Lunar Festival, the Scourge Invasion,
-- Midsummer, Brewfest, Children's Week, Hallow's End, Harvest Festival, Love is in the Air
local QDB_SEASONAL = { [-22] = true, [-41] = true, [-364] = true, [-365] = true, [-366] = true, [-368] = true, [-369] = true,
    [-370] = true, [-1002] = true, [-1003] = true, [-1005] = true, [-1006] = true }
local QDB_AVAIL_FAR = 10         -- a quest this many levels above the player is left out (the turn-ins with requiredLevel 1)
local QDB_AVAIL_MAPS = 6         -- maps whose last list is kept for the heartbeat
local function onQuest(id) return SN(function() return C_QuestLog.GetLogIndexForQuestID(id) end) ~= nil end
local function questDone(id) return TB(function() return C_QuestLog.IsQuestFlaggedCompleted(id) end) == true end
local function playerFacts()
    local L = qdb.L
    local race = TN(function() return (select(3, UnitRace("player"))) end)
    local cls = TN(function() return (select(3, UnitClass("player"))) end)
    local rbit = nil
    if race then
        local okM, b = pcall(function() return L.Enum.raceMaskById[race] end)
        rbit = (okM and type(b) == "number") and b or (race >= 1 and race <= 26 and 2 ^ (race - 1) or nil)
    end
    local fac = TS(function() return (UnitFactionGroup("player")) end)
    -- q32: the player's professions (skill line -> rank) for requiredSkill; nil when the API is missing (such quests are left out)
    local skills = nil
    pcall(function()
        local list = { GetProfessions() }
        skills = {}
        for k = 1, 5 do
            local ix = list[k]
            if type(ix) == "number" then
                local _, _, rank, _, _, _, line = GetProfessionInfo(ix)
                if type(line) == "number" and type(rank) == "number" and WC.isPlain(line) and WC.isPlain(rank) then skills[line] = rank end
            end
        end
    end)
    return { lvl = TN(function() return UnitLevel("player") end) or 0, rbit = rbit, skills = skills,
             cbit = (cls and cls >= 1) and 2 ^ (cls - 1) or nil,
             fac = (fac == "Alliance" and "A") or (fac == "Horde" and "H") or nil,
             triv = TN(function() return UnitQuestTrivialLevelRange("player") end) or 5 }
end
local function idList(v)
    if type(v) == "number" then return v > 0 and { v } or {} end
    return type(v) == "table" and v or {}
end
-- the quest's flags when the player can take it (or soon), else nil
local function availFlags(qid, me)
    local Q = qdb.Q
    if onQuest(qid) or questDone(qid) then return nil end
    local races = qdbCall(Q, "requiredRaces", qid)
    if type(races) == "number" and races > 0 and me.rbit and not hasBit(races, me.rbit) then return nil end
    local classes = qdbCall(Q, "requiredClasses", qid)
    if type(classes) == "number" and classes > 0 and me.cbit and not hasBit(classes, me.cbit) then return nil end
    local maxL = qdbCall(Q, "requiredMaxLevel", qid)
    if type(maxL) == "number" and maxL > 0 and me.lvl > maxL then return nil end
    local f = 0
    local rl = qdbCall(Q, "requiredLevel", qid)
    if type(rl) == "number" and rl > me.lvl then
        if rl > me.lvl + QDB_AVAIL_SOON then return nil end
        f = f + 4
    end
    local single = idList(qdbCall(Q, "preQuestSingle", qid))
    if #single > 0 then
        local any = false
        for _, p in ipairs(single) do if questDone(p) then any = true; break end end
        if not any then return nil end
    end
    for _, p in ipairs(idList(qdbCall(Q, "preQuestGroup", qid))) do if not questDone(p) then return nil end end
    for _, p in ipairs(idList(qdbCall(Q, "exclusiveTo", qid))) do if questDone(p) or onQuest(p) then return nil end end
    for _, p in ipairs(idList(qdbCall(Q, "disabledByQuest", qid))) do if questDone(p) then return nil end end
    local ql = qdbCall(Q, "questLevel", qid)
    if type(ql) ~= "number" or ql < 0 then ql = nil end                    -- -1: the player's own level
    if ql and ql > me.lvl + QDB_AVAIL_FAR then return nil end              -- q32: far above (signet / AQ turn-ins, rl 1)
    local skill = qdbCall(Q, "requiredSkill", qid)                          -- q32: {skill line, rank}
    if type(skill) == "table" and type(skill[1]) == "number" then
        local have = me.skills and me.skills[skill[1]]
        if not have or have < (tonumber(skill[2]) or 0) then return nil end
    end
    if ql and ql > 0 and me.lvl - ql > me.triv then f = f + 1 end
    local sort = qdbCall(Q, "zoneOrSort", qid)
    if type(sort) == "number" and QDB_SEASONAL[sort] then f = f + 8 end   -- q32: a holiday's quest
    local sf, qf = qdbCall(Q, "specialFlags", qid), qdbCall(Q, "questFlags", qid)
    if (type(sf) == "number" and sf % 2 == 1) or (type(qf) == "number" and hasBit(qf, 4096)) then f = f + 2 end
    return f, ql
end
local function availBuild(m)
    local me = playerFacts()
    if me.lvl <= 0 then return nil end
    local here = areasOf(m)
    local w, h = sizeOf(m)
    local px, py = nil, nil
    local okP, pos = pcall(function() return C_Map.GetPlayerMapPosition(m, "player") end)
    if okP and pos then
        local okXY, x, y = pcall(function() return pos:GetXY() end)
        if okXY and type(x) == "number" and type(y) == "number" and WC.isPlain(x) and WC.isPlain(y) then px, py = x, y end
    end
    local out, verdict = {}, {}
    for _, e in ipairs(pins.availBy[m] or {}) do
        local qid, gid, isObj = e[1], e[2], e[3]
        if verdict[qid] == nil then verdict[qid] = { availFlags(qid, me) } end
        local v = verdict[qid]
        if v[1] and not v.shown then
            local fa = (not isObj) and qdbCall(qdb.N, "friendlyToFaction", gid) or nil
            if not (type(fa) == "string" and me.fac and not fa:find(me.fac, 1, true)) then
                local pts = spotsHere(isObj and qdb.O or qdb.N, gid, here, 12)
                local best, bd = nil, math.huge
                for _, p in ipairs(pts) do
                    local d = px and (((p[1] - px) * w) ^ 2 + ((p[2] - py) * h) ^ 2) or 0
                    if not best or d < bd then best, bd = p, d end
                end
                if best then
                    v.shown = true
                    out[#out + 1] = { i = qid, n = qdbCall(qdb.Q, "name", qid), lv = v[2], x = best[1], y = best[2], f = v[1], g = gid,
                                      o = isObj and 1 or nil }
                end
            end
        end
    end
    return out
end
-- build the player's map's list; send it when it differs from the last one sent for that map (or [again]: a loading screen)
local function availSend(again)
    if pins.state ~= "ready" then return false end
    local m = playerMap()
    if not m then return false end
    local ok, out = pcall(availBuild, m)
    if not ok or type(out) ~= "table" then return false end
    local sig = {}
    for _, q in ipairs(out) do sig[#sig + 1] = q.i .. ":" .. q.f end
    sig = table.concat(sig, ",")
    local last = pins.alast[m]
    if last and last.sig == sig and not again then return false end
    if not last or last.sig ~= sig then
        pins.aset = pins.aset + 1                  -- its own counter (seeded as the pins' set: unique across sessions too)
        last = { sig = sig, doc = { type = "qdbavail", v = QV, m = m, set = pins.aset, q = out } }
        pins.alast[m] = last
        for i = #pins.alru, 1, -1 do if pins.alru[i] == m then table.remove(pins.alru, i) end end
        pins.alru[#pins.alru + 1] = m
        while #pins.alru > QDB_AVAIL_MAPS do pins.alast[table.remove(pins.alru, 1)] = nil end
    end
    pcall(function() WC.send(WC.encode(last.doc)); WC.push() end)
    return true
end
WC.questieAvail = { send = availSend, flags = function(qid) return availFlags(qid, playerFacts()) end }   -- for the harness

-- ---------------------------------------------------------------- QuestieDB's objective AREAS for Map (q33)
-- The user's idea 2026-10-07 (via tracking): "a better job of making the quest objective area with the questie db" -- the game's
-- own areas are often missing or coarse for Forever's quests (Home's map falls back to a 50 yd circle). Their picks: "Soft area
-- per group" and "QuestieDB first". The shape is Map's (maproom e90e1aac; Map clusters and draws, this add-on reads):
--   {"type":"qdbareas","m":uiMapID,"set":n,"q":[{"i":questID,"o":objectiveIndex,"p":[[x,y],...]}]}
-- For the uiMap the player is on: the focused and the TRACKED quests (Map draws areas for those), each UNFINISHED game objective
-- o (the game's own number, matched to QuestieDB's by name as in q19) with every QuestieDB spot of it ON THIS MAP -- the creatures
-- to kill (kill credit too), the objects to use, every dropper of an item (not only q19's first few: Murloc Eye has 14+ murloc
-- kinds, the map's own among them), a "scout" objective's point. Spots closer than QDB_AREA_YD are one (the first kept); an
-- objective with more than QDB_AREA_MAX left is thinned on a coarser grid (doubling), so its whole spread survives. 3 decimals
-- (~3.5 yd on a zone). The document holds QDB_AREA_BUDGET spots at most: past it EVERY objective is thinned to an equal share (at
-- least QDB_AREA_SHARE_MIN; measured on the real data: 5 tracked Westfall quests reached 486 spots and cut Westfall Stew's other
-- three objectives), and only then the last ones are left out. Sent when the map or the set of (quest, objective) changes --
-- from the quests feed (accept / abandon / track / an objective done / a new map) and on a uiMap change of its own -- and again
-- every QDB_PIN_BEATS-th heartbeat (the same set: the app redraws nothing). Each quest's spots are read once per map per session.
local QDB_AREA_YD = 10           -- yards: near spots of one objective are one
local QDB_AREA_MAX = 150         -- spots per objective at most
local QDB_AREA_BUDGET = 500      -- spots per document at most (~7 KB)
local QDB_AREA_SHARE_MIN = 20    -- an objective's share is never thinned below this
local QDB_AREA_DROPPERS = 40     -- NPCs / objects read per item objective
local areas = { set = 0, last = nil, doc = nil, beats = 0 }

local function r3(v) return math.floor(v * 1000 + 0.5) / 1000 end

-- near spots are one; too many: a coarser grid until they fit (yards from the map's size; without it, Map's 0.004 cells)
local function areaThin(pts, w, h, max)
    max = max or QDB_AREA_MAX
    local r = QDB_AREA_YD
    while true do
        local sx = (w > 1) and r / w or 0.0004 * r
        local sy = (h > 1) and r / h or 0.0004 * r
        local out, seen = {}, {}
        for _, p in ipairs(pts) do
            local key = math.floor(p[1] / sx) .. ":" .. math.floor(p[2] / sy)
            if not seen[key] then seen[key] = true; out[#out + 1] = { r3(p[1]), r3(p[2]) } end
        end
        if #out <= max then return out end
        if r >= 5000 then while #out > max do out[#out] = nil end; return out end
        r = r * 2
    end
end

-- one matched objective's spots on the areas of ONE uiMap (all of them; thinned by the caller)
local function areaSpots(c, here)
    local pts = {}
    local function add(sp)
        for _, p in ipairs(spotsIn(sp, here, QDB_MAX_SPOTS)) do
            if #pts >= QDB_MAX_SPOTS * 4 then return end
            pts[#pts + 1] = p
        end
    end
    if c.kind == "npc" then
        for _, id in ipairs(c.ids) do add(qdbCall(qdb.N, "spawns", id)) end
    elseif c.kind == "obj" then
        for _, id in ipairs(c.ids) do add(qdbCall(qdb.O, "spawns", id)) end
    elseif c.kind == "spot" then
        add(c.sp)
    else                                                                -- an item: every NPC / object that drops it
        for _, k in ipairs({ { "npcDrops", qdb.N }, { "objectDrops", qdb.O } }) do
            local list = qdbCall(qdb.I, k[1], c.ids[1])
            if type(list) == "table" then
                for n, id in ipairs(list) do
                    if n > QDB_AREA_DROPPERS then break end
                    add(qdbCall(k[2], "spawns", id))
                end
            end
        end
    end
    return pts
end

local function areasBuild(doc, m)
    if not qdbReady() then return nil end
    local here, w, h = nil, sizeOf(m)
    local order = {}
    for _, q in ipairs(doc.list or {}) do
        if (q.focus or q.watch ~= nil) and not q.done and not q.ready and type(q.obj) == "table" then
            order[#order + 1] = { q = q, rank = q.focus and 1 or 2, n = #order }
        end
    end
    table.sort(order, function(a, b) if a.rank ~= b.rank then return a.rank < b.rank end return a.n < b.n end)
    local list, total = {}, 0
    for _, e in ipairs(order) do
        local q = e.q
        local c = qdbQuest(q)
        c.areas = c.areas or {}
        local byMap = c.areas[m]
        if not byMap then byMap = {}; c.areas[m] = byMap end
        for i, g in ipairs(q.obj) do
            local cand = c.cand and c.cand[i]
            if cand and g.f ~= true then
                local pts = byMap[i]
                if not pts then
                    here = here or areasOf(m)
                    pts = areaThin(areaSpots(cand, here), w, h)
                    byMap[i] = pts
                end
                if #pts > 0 then list[#list + 1] = { i = q.id, o = i, p = pts }; total = total + #pts end
            end
        end
    end
    -- too many: every objective down to an equal share first, then the last ones out
    local share = (total > QDB_AREA_BUDGET) and math.max(QDB_AREA_SHARE_MIN, math.floor(QDB_AREA_BUDGET / #list)) or nil
    local out, used = {}, 0
    for _, e in ipairs(list) do
        if share and #e.p > share then e.p = areaThin(e.p, w, h, share) end
        if used + #e.p <= QDB_AREA_BUDGET then out[#out + 1] = e; used = used + #e.p end
    end
    return out
end

-- [doc]: the quests document just built (nil: the last one, on a uiMap change of its own); [again]: send even if unchanged
areasSend = function(doc, again)
    if doc then areas.doc = doc end
    if not areas.doc then return false end
    local m = playerMap()
    if not m then return false end
    local ok, out = pcall(areasBuild, areas.doc, m)
    if not ok or type(out) ~= "table" then return false end
    local sig = { m }
    for _, e in ipairs(out) do sig[#sig + 1] = e.i .. "." .. e.o end
    sig = table.concat(sig, ",")
    local last = areas.last
    if last and last.sig == sig and not again then return false end
    if not last or last.sig ~= sig then
        if areas.set == 0 then                         -- unique across sessions, as the pins' set (q23)
            local okT, t = pcall(GetServerTime)
            if not (okT and type(t) == "number") then okT, t = pcall(time) end
            areas.set = (okT and type(t) == "number") and (math.floor(t) % 1000000) * 1000 or 0
        end
        areas.set = areas.set + 1
        last = { sig = sig, m = m, doc = { type = "qdbareas", v = QV, m = m, set = areas.set, q = out } }
        areas.last = last
    end
    pcall(function() WC.send(WC.encode(last.doc)) end)
    return true
end

-- the heartbeat's re-send: the last document, while the player is still on its map
local function areasBeat()
    areas.beats = areas.beats + 1
    if areas.beats < QDB_PIN_BEATS then return end
    areas.beats = 0
    local last = areas.last
    if last and last.m == playerMap() then pcall(function() WC.send(WC.encode(last.doc)) end) end
end
WC.questieAreas = { send = function(doc, again) return areasSend(doc, again) end, thin = areaThin,
                    budget = function(n) QDB_AREA_BUDGET = n or 500 end,
                    reset = function() areas = { set = 0, last = nil, doc = nil, beats = 0 } end }   -- for the harness

-- the heartbeat's re-send (see QDB_PIN_BEATS): only the pair already sent for the map the player is on still
local pinBeats = 0
local function pinsBeat()
    if pins.state ~= "ready" or not pins.sent then return end
    pinBeats = pinBeats + 1
    if pinBeats < QDB_PIN_BEATS then return end
    pinBeats = 0
    if playerMap() == pins.sent then pinsSend(pins.sent, true) end
    -- q31: the available quests' last list for the map the player is on, too (the same flush)
    local a = pins.alast[playerMap() or -1]
    if a then pcall(function() WC.send(WC.encode(a.doc)) end) end
end

local function pinsKeep(m, res)
    pins.cache[m] = res
    for i = #pins.lru, 1, -1 do if pins.lru[i] == m then table.remove(pins.lru, i) end end
    pins.lru[#pins.lru + 1] = m
    while #pins.lru > QDB_PIN_MAPS do pins.cache[table.remove(pins.lru, 1)] = nil end
end

-- the player's map, from the cache or built; one build at a time, the newest map wins
local function pinsNext()
    if pins.state ~= "ready" or pins.busy then return end
    local m = playerMap()
    if not m or m == pins.sent then return end
    if pins.cache[m] then pinsKeep(m, pins.cache[m]); pinsSend(m); return end
    pins.busy = m
    local gen = pins
    sliced({ calm = true }, function(st) return pinsMapBody(st, m) end, function(res, err)
        if gen ~= pins then return end        -- a reset since (the harness)
        pins.busy = nil
        if res then
            pinsKeep(m, res)
            if playerMap() == m then pinsSend(m) end
        else
            pins.sent = m                      -- not retried until the player's map changes
            pcall(function() WC.send(WC.encode({ type = "qdbpins", v = QV, m = m, err = tostring(err):sub(1, 200) })); WC.push() end)
        end
        pinsNext()
    end)
end

local function pinsStart()
    if pins.state then return pins.state end
    if not qdbReady() then pins.state = "off"; return "off" end
    pins.state = "indexing"
    -- "set" is unique ACROSS sessions too (q23, Map: its cache outlives a game reload and is keyed by m + set, so a fresh build
    -- numbered from 1 again could be taken for the old one): this session's numbers start at the server's second, so a later
    -- session always counts from higher up. (seconds mod 10^6) x 1000 stays below 2^31, so the app reads it as a plain int.
    local okT, t = pcall(GetServerTime)
    if not (okT and type(t) == "number") then okT, t = pcall(time) end
    pins.set = (okT and type(t) == "number") and (math.floor(t) % 1000000) * 1000 or 0
    pins.aset = pins.set
    local gen = pins
    sliced({ calm = true }, pinsIndexBody, function(res, err)
        if gen ~= pins then return end
        if not res then
            pins.state = "off"
            pcall(function() WC.send(WC.encode({ type = "qdbpins", v = QV, err = tostring(err):sub(1, 200) })); WC.push() end)
            return
        end
        pins.npcBy, pins.nodeBy, pins.kinds, pins.bits = res.npcBy, res.nodeBy, res.kinds, res.bits
        pins.availBy = res.availBy or {}
        pins.state = "ready"
        pinsNext()
        availSend()
    end)
    return "indexing"
end
WC.questiePins = { start = pinsStart, reset = pinsReset, state = function() return pins.state end }   -- for the harness

local pf = CreateFrame("Frame")
pf:SetScript("OnEvent", function(_, ev)
    if ev == "PLAYER_ENTERING_WORLD" then pins.sent = nil end     -- a loading screen: send the current map again
    pinsNext()
    availSend(ev == "PLAYER_ENTERING_WORLD")                      -- q31: the map's available quests (sent when they changed)
    local okA, sentA = pcall(areasSend, nil, ev == "PLAYER_ENTERING_WORLD")   -- q33: the objective areas of the new map
    if okA and sentA then pcall(WC.push) end
end)
for _, e in ipairs({ "PLAYER_ENTERING_WORLD", "ZONE_CHANGED_NEW_AREA", "ZONE_CHANGED", "ZONE_CHANGED_INDOORS" }) do
    pcall(function() pf:RegisterEvent(e) end)
end
-- q31: what the player can take changes with the quest log and the level -- the map's list again, once things settle (0.5 s)
local availQueued = false
local af = CreateFrame("Frame")
af:SetScript("OnEvent", function()
    if availQueued then return end
    availQueued = true
    C_Timer.After(0.5, function() availQueued = false; availSend() end)
end)
for _, e in ipairs({ "QUEST_ACCEPTED", "QUEST_TURNED_IN", "QUEST_REMOVED", "PLAYER_LEVEL_UP" }) do
    pcall(function() af:RegisterEvent(e) end)
end

local qdbProbed = false
local qf = CreateFrame("Frame")
qf:SetScript("OnEvent", function()
    if qdbProbed then return end
    qdbProbed = true
    C_Timer.After(QDB_PROBE_DELAY, function() pcall(questieProbe); pcall(pinsStart) end)
end)
pcall(function() qf:RegisterEvent("PLAYER_ENTERING_WORLD") end)

-- RIDE THE CORE HEARTBEAT. WC.snapshot() emits and schedules its push for the end of the
-- frame; anything sent in this post-hook lands in that same pad burst, so re-sending the whole
-- list every 15 s costs nothing extra. Only on reasons that always emit -- ACTIONBAR_* events
-- also call snapshot() and are usually deduped, and riding those would buy a flush each.
local RIDE = { heartbeat = true, login = true, zone = true, manual = true }
if type(WC.snapshot) == "function" then
    hooksecurefunc(WC, "snapshot", function(reason)
        if RIDE[reason] then emitQuests(true) end
        if reason == "heartbeat" then pcall(pinsBeat); pcall(areasBeat) end   -- q22 / q33: the current map's documents again
    end)
end

-- ---------------------------------------------------------------- commands from the app
-- The app drives these through the tap pad (TapPad.lua) with a PAIR of taps: `qpick` takes a quest's
-- column and only remembers the quest, `qact` takes an action's column and acts on it (see the pad
-- rows below). IN A FIGHT TOO (q13, SPEC_COMBAT item 4, the user's agreed spec: "everything the game itself allows in combat"):
-- these are engine calls, not protected ones, so nothing here refuses because of the fight any more; what the GAME refuses is
-- reported as before, and in a fight it also flashes Home red (refused, below). The /wcq
-- slash commands remain for manual use and testing only -- typed chat from the app is broken on the
-- device (Android's keyboard swallows the characters).
--   /wcq                 force a quest push and print a summary
--   /wcq watch <id> / unwatch <id>   add to / remove from the objective tracker -- this is
--                        what the game's own "Track" button does, and what the room's TRACK uses
--   /wcq track <id>      make it the super-tracked ("focused") quest, the on-screen waypoint
--   /wcq untrack         clear super-tracking (no button uses these two yet)
--   /wcq abandon <id>    abandon, after checking the game's own abandon selection matches

-- Pad taps answer on the wire only; the app shows the result. Slash commands also answer in chat.
local quiet = false

local function ack(op, id, ok, why, extra)
    local out = { type = "questack", op = op, id = id, ok = ok and true or false, why = why, v = QV }
    if extra then for k, v in pairs(extra) do out[k] = v end end
    WC.send(WC.encode(out))
    soon()
    WC.push()
    if not quiet then
        say("quest " .. op .. (id and (" " .. tostring(id)) or "") .. ": " ..
            (ok and "|cff8fc47aok|r" or ("|cffff5555refused|r " .. tostring(why or ""))))
    end
end

-- (defined with the guests, below) a result the GAME refused, in a fight: the holder's capture.refused (Home's red flash)
local refusedInFight

-- the answer to an action the game carried out or refused; a refusal in a fight also flashes Home red
local function result(op, id, ok, why, extra)
    if not ok and InCombatLockdown() and refusedInFight then refusedInFight() end
    ack(op, id, ok, why, extra)
end

local function inLog(id)
    return SN(function() return C_QuestLog.GetLogIndexForQuestID(id) end) ~= nil
end

local OPS = {}

function OPS.track(id)
    if not id or not inLog(id) then return ack("track", id, false, "not in log") end
    CALL(function() C_SuperTrack.SetSuperTrackedQuestID(id) end)
    local now = superTracked()
    result("track", id, now == id, now ~= id and "did not take" or nil)
end

function OPS.untrack()
    CALL(function() C_SuperTrack.SetSuperTrackedQuestID(0) end)
    if superTracked() then CALL(function() C_SuperTrack.ClearAllSuperTracked() end) end
    local now = superTracked()
    result("untrack", nil, now == nil, now and "still tracked" or nil)
end

-- SHARE one quest with the group: the game's own Share Quest (QuestMapQuestOptions_ShareQuest: QuestLogPushQuest(logIndex)),
-- only under the game's rule (the quest can be shared AND the player is in a group); the reason otherwise, in the game's words
-- where it has them. The game answers the party itself (its chat lines); the reply here only says the share was sent.
function OPS.share(id)
    if not id or not inLog(id) then return ack("share", id, false, "not in log") end
    if TB(function() return IsInGroup() end) ~= true then return ack("share", id, false, "You are not in a party.") end   -- ERR_QUEST_PUSH_NOT_IN_PARTY_S
    if TB(function() return C_QuestLog.IsPushableQuest(id) end) ~= true then return ack("share", id, false, "That quest cannot be shared.") end   -- ERR_QUEST_PUSH_NOT_ALLOWED_S
    local idx = SN(function() return C_QuestLog.GetLogIndexForQuestID(id) end)
    if not idx then return ack("share", id, false, "not in log") end
    local ok = CALL(function() QuestLogPushQuest(idx) end)
    result("share", id, ok and true or false, (not ok) and "the game refused" or nil)
end

-- UNFOCUS one quest (the Map room's A on the focused quest, the user's quest-list feedback): clears the game's focus, but
-- ONLY while that quest is the focused one -- a tap made from a slightly stale document must not drop a newer focus.
function OPS.unfocus(id)
    if not id or not inLog(id) then return ack("untrack", id, false, "not in log") end
    if superTracked() ~= id then return ack("untrack", id, false, "not the focused quest") end
    CALL(function() C_SuperTrack.SetSuperTrackedQuestID(0) end)
    if superTracked() then CALL(function() C_SuperTrack.ClearAllSuperTracked() end) end
    local now = superTracked()
    result("untrack", id, now == nil, now and "still focused" or nil)
end

function OPS.watch(id)
    if not id or not inLog(id) then return ack("watch", id, false, "not in log") end
    CALL(function() C_QuestLog.AddQuestWatch(id) end)
    result("watch", id, TN(function() return C_QuestLog.GetQuestWatchType(id) end) ~= nil)
end

function OPS.unwatch(id)
    if not id or not inLog(id) then return ack("unwatch", id, false, "not in log") end
    CALL(function() C_QuestLog.RemoveQuestWatch(id) end)
    result("unwatch", id, TN(function() return C_QuestLog.GetQuestWatchType(id) end) == nil)
end

function OPS.abandon(id)
    if not id or not inLog(id) then return ack("abandon", id, false, "not in log") end
    if S(function() return C_QuestLog.CanAbandonQuest(id) end) ~= true then
        return ack("abandon", id, false, "game says it cannot be abandoned")
    end
    CALL(function() C_QuestLog.SetSelectedQuest(id) end)
    CALL(function() C_QuestLog.SetAbandonQuest() end)
    -- The game abandons whatever its abandon slot holds. Refuse unless that is exactly the
    -- quest the app asked for, so a selection quirk can never drop the wrong quest.
    local pending = SN(function() return C_QuestLog.GetAbandonQuest() end)
    if pending ~= id then
        return ack("abandon", id, false, "abandon slot holds " .. tostring(pending or "nothing"))
    end
    CALL(function() C_QuestLog.AbandonQuest() end)
    -- The removal is server-confirmed, so check a moment later rather than trusting the call.
    local wasQuiet = quiet
    C_Timer.After(1.0, function()
        local gone = not inLog(id)
        local q0 = quiet; quiet = wasQuiet
        result("abandon", id, gone, (not gone) and "still in log after 1 s" or nil)
        quiet = q0
    end)
end

-- ABANDON IS TWO TAPS. The first (arm) only makes the addon NAME the quest it would drop; the app
-- shows that name in its confirm bar, and only the second tap (confirm) abandons -- whatever is
-- armed, if it was armed in the last ARM_SECONDS. So the confirm the player reads is the addon's
-- own understanding of the target, not the app's.
local ARM_SECONDS = 10
local armed = nil

function OPS.arm(id)
    if not id or not inLog(id) then return ack("arm", id, false, "not in log") end
    if S(function() return C_QuestLog.CanAbandonQuest(id) end) ~= true then
        return ack("arm", id, false, "game says it cannot be abandoned")
    end
    armed = { id = id, at = GetTime() }
    ack("arm", id, true, nil, { title = TS(function() return C_QuestLog.GetTitleForQuestID(id) end),
                                secs = ARM_SECONDS })
end

function OPS.confirm()
    local a = armed
    armed = nil
    if not a then return ack("abandon", nil, false, "nothing armed") end
    if GetTime() - a.at > ARM_SECONDS then return ack("abandon", a.id, false, "confirm came too late") end
    OPS.abandon(a.id)
end

-- ---------------------------------------------------------------- the tap pad rows
-- TWO ROWS since q5 (the user's yes 2026-09-30: the shared pad was nearly full; q4 used five rows).
--   `qpick` (namespace "quest"):    a quest's column. It only REMEMBERS the quest: no game action, no reply.
--   `qact`  (namespace "questact"): the column is the ACTION, done to the picked quest:
--                                   0 watch, 1 unwatch, 2 focus (replies "track"), 3 arm, 4 confirm (replies "abandon")
-- The app sends the pair back to back. A pick serves ONE action and only for PICK_SECONDS, so an action
-- whose own pick was lost is refused ("no quest picked") instead of landing on an older pick. The reply
-- names the quest that was acted on, and the app checks it against the one it meant.
-- Confirm needs no pick: it abandons what `arm` named (see ABANDON IS TWO TAPS above).
-- "questact" is never keyed (no slotFor), so the handler reads the column.
local PICK_SECONDS = 2
local picked = nil

local ACTS = {
    [0] = { op = "watch", fn = OPS.watch },
    [1] = { op = "unwatch", fn = OPS.unwatch },
    -- FOCUS (home's request, 2026-09-28): make this quest the game's focused ("super-tracked") quest, the one the
    -- game draws the waypoint for. Home does not tap the quest that is already focused (user: "do nothing"); if it
    -- ever does, setting the same focus again is harmless.
    [2] = { op = "track", fn = OPS.track },
    [3] = { op = "arm", fn = OPS.arm },
    [4] = { op = "abandon", fn = OPS.confirm, nopick = true },
    -- QUEST ACTIONS GROW DOWN FROM 39, the guests grow UP from 8 (new guest ids go on the END), so neither ever lands on
    -- the other's columns. UNFOCUS (Home's request for the Map room, 2026-09-30): replies "untrack" with the quest's id.
    [39] = { op = "untrack", fn = OPS.unfocus },
    -- SHARE (the user's Quests rework, 2026-09-30: the game's More menu): replies "share" with the quest's id
    [38] = { op = "share", fn = OPS.share },
}

-- ---------------------------------------------------------------- the generic controller guests (Home's item-5 decision)
-- LATER item 5, the user's pick 2026-09-30: every room holds the controller while it shows, and B goes Home unless it has a
-- nearer job. Rooms with an addon of their own are guests of interact's shared capture (WC.capture) themselves (Social,
-- Calendar, ...). Rooms WITHOUT one (Quests, Map, Settings, Chat until its session joins) share THIS facility, on the spare
-- columns of `qact`, so the pad needs no new row and the combat / NPC rules live in one place:
--   5 leave with B      the holder lets go with why "B" (the capture's document tells the app, whose route shows Home)
--   6 / 7 B leads home / back   capture.bnext for the holder: the Home button shows B when it leads home
--   8 + 2i hold, 9 + 2i free   for GUESTS[i + 1], in this FIXED order (Home's spec): quests, map, settings, chat, bar, home, radial
--                              (new ids only ever go on the END, so no guest's columns move)
-- A guest's presses come back as {"type":"guestpad", id, k, n} (the D-pad on the press, A / B / X / Y on the release, as the
-- capture gives them), and a lost hold as {"type":"guestpad", id, lost}. No game action anywhere here.
-- IN A FIGHT (q13, SPEC_COMBAT items 4 / 7 / 9 with interact's capture i81): a room that holds KEEPS the controller; a take
-- answers ok and stays PENDING (guestack hold ok, why "pending") until Home's tap lands on the ENTER cell (then "entered");
-- a pending take the fight's end drops is taken again for real; the holder's free / leave answers "tap leave" (only interact's
-- secure leave gives the pad back in a fight: Home taps it) and is NEVER retried here. A leave by the player (lost "B" in a
-- fight, "left" out of one) means the room is no longer wanted. An older capture still answers "combat": wanted, taken later.
-- THE TAKE QUEUE (q14, interact's i82 = capture v11, Home 2026-10-02): a take that finds another holder (a room or an NPC
-- window) answers ok and WAITS; the capture grants it once the holder lets go (in a fight as the pending ENTER). So ANY ok take
-- that does not hold yet is pending here, in or out of a fight, and turns into held when the capture names us (or at our first
-- press: only the holder gets presses). A newer waiter wins: we are told lost("taken") and the room is no longer wanted.
local GUESTS = { "quests", "map", "settings", "chat", "bar", "home", "radial", "tool" }   -- home: Home's buff / debuff list (the user, 2026-09-30)
-- tool (the user, 2026-10-16: "instead of A and X as left and right click, can we use ZL and ZR"): Home's Track Pad & Keyboard, its own
-- guest (hold 22 / free 23) so ITS extras can keep ZL / ZR (the clicks) from the game without Home's other modules taking them
-- radial (q15, 2026-10-06): the user's consumables radial (Home's D-pad up, the signed bronze wheel v4); hold 20 / free 21
-- buttons beyond the eight a guest keeps from the game (capture v4 `extra`; delivered on the release like the face
-- buttons). The Map room zooms with the shoulders (Home's item-5 plan, tracking's call 2026-09-30); the bar selector
-- (LATER item 7, the user: "Yes, both as drawn") keeps Select, so Select closes it while it holds, and the bumpers and
-- triggers (LATER item 23, the user: "I want the L and R bumpers and triggers to move it as well"; LB / LT = left, RB / RT = right).
-- q35 (the user's rulings 2026-10-09 via Home): START OPENS THE APP'S PAUSE WHEEL FROM EVERY ROOM while the DS WoW toolbar is on, so
-- EVERY guest keeps Start (PADFORWARD) from the game -- whose own radial would open over the room -- and the app reads Start itself
-- (it ignores the relayed press). With the toolbar off Start stays the game's own wheel: the app taps G_START_GAME and the guests
-- let it through (startFor). The rooms' old Start actions moved to Select (PADBACK): Home's map Filter and Chat's filter menu.
local EXTRA = {
    quests = { "PADFORWARD" },
    -- the Settings room's tabs (board f-settings-tabs, the user's approved plan 2026-10-15): L / R switch them, as in every tabbed room
    settings = { "PADLSHOULDER", "PADRSHOULDER", "PADFORWARD" },
    map = { "PADLSHOULDER", "PADRSHOULDER", "PADFORWARD" },
    bar = { "PADBACK", "PADLSHOULDER", "PADRSHOULDER", "PADLTRIGGER", "PADRTRIGGER", "PADFORWARD" },
    -- Home's chat module cycles its filters with LB / RB, as the Chat room does (the user: "I want to have LB and RB as
    -- shortcuts in here to basically cycle filters on the top"); Home holds "home" for the module and its buff list.
    -- q30 (the user's answer via Home, 2026-10-07): Start opens the Filter of Home's open map module
    home = { "PADLSHOULDER", "PADRSHOULDER", "PADFORWARD", "PADBACK" },          -- q35: Select = the map module's Filter
    -- the Chat room, the same shortcut (the chat session's request, AGENT_LOG 659ff04)
    -- q28 (Chat's rework, Home's pick 2026-10-07: "chat" stays the chat module's guest): Start opens its filter menu;
    -- q29 (the user's pick via tracking: LT = the keyboard's Backspace, RT = its Shift)
    chat = { "PADLSHOULDER", "PADRSHOULDER", "PADFORWARD", "PADLTRIGGER", "PADRTRIGGER", "PADBACK" },   -- q35: Select = the filter menu
    -- the consumables radial pages with LB / RB (the user's signed design, tracking 2026-10-06); q34 (Home's ask 2026-10-08, the user's
    -- signed radial PAUSE menu, which reuses this guest): Start while either wheel is open closes it -- the capture keeps Start from
    -- the game (whose own radial would open over the wheel) and hands it to the app as a press
    radial = { "PADLSHOULDER", "PADRSHOULDER", "PADFORWARD" },
    -- the Track Pad & Keyboard: LB / RB its tabs, ZL / ZR the left / right click (never a game action while the tool holds)
    tool = { "PADLSHOULDER", "PADRSHOULDER", "PADLTRIGGER", "PADRTRIGGER", "PADFORWARD" },
}
-- B FREES IN-GAME (q17): the guests whose B, while their bnext is "home", lets go of the controller right here (see leaveInGame)
local BFREE = { radial = true }

-- THE GAME'S QUEST LOG BEHIND THE QUESTS ROOM (q25; the user's answer 2026-10-07 via tracking, "The Quests room (Recommended)"): this
-- game's quest-log key opens the SAME window as the map (WorldMapFrame with its quest list), and on THAT route the Quests room comes up
-- (M brings the Map room: Map.lua's). Map owns WorldMapFrame's hooks and its open / close cells (its mr1). Here:
--   * the ROUTE STAMP: a post-hook on ToggleQuestLog (what the quest-log key's binding and QuestLogMicroButton's OnClick run) sets
--     WC.questLogAt; Map.lua's one-frame-late OnShow reads it to tell the quest-log route from M;
--   * the quests guest's take carries the window, as every room's does (the user's ruling: "every room opens the game's window behind
--     it"): spec.hide WorldMapFrame (alpha 0 while held), spec.leave = Map's WowCompanionMapClose (the world map's own close, by
--     reference), spec.back the same while the room's B leads Home -- re-taken when the app moves bnext (Social's pattern: the
--     capture's back does not look at bnext) -- and spec.window while the game's window shows out of a fight (a window the game
--     opened pre-empts the holder, capture v9);
--   * B that closed the game's window through spec.back (capture v16 says so as passed "PAD2") = the player left the room: let go
--     with "B" right here (the app's route Home), as the radial's B does (q17).
-- Nothing here opens or closes a window by a call (the taint rule, 2026-09-28): a post-hook, the capture's alpha, secure clicks.
local MAP_CLOSE = "WowCompanionMapClose"
local function worldMapShown()
    local ok, shown = CALL(function() return WorldMapFrame:IsShown() end)
    return ok and shown == true
end
if type(hooksecurefunc) == "function" and type(_G.ToggleQuestLog) == "function" then
    pcall(hooksecurefunc, "ToggleQuestLog", function() WC.questLogAt = GetTime() end)
end
-- q26 (the user's answer 2026-10-07 via tracking: the radial's map entry -> "The Map room"): that entry is the SAME game button as the
-- quest log, QuestLogMicroButton, whose OnClick runs ToggleQuestLog. A post-hook on the button's OnClick runs after ToggleQuestLog's
-- stamp, in the same frame and before Map.lua's one-frame-late decision, and takes the stamp away: a click on the button (the radial,
-- or Map's own open cell, whose own mark wins anyway) falls to the Map room. The quest-log KEY (its binding calls ToggleQuestLog
-- itself, no button) keeps the stamp: the Quests room.
local hookedMicro = false
local function hookMicro()
    local b = _G.QuestLogMicroButton
    if hookedMicro or type(b) ~= "table" or type(b.HookScript) ~= "function" then return end
    hookedMicro = true
    pcall(b.HookScript, b, "OnClick", function() WC.questLogAt = nil end)
end
hookMicro()
local G_LEAVE_B, G_BNEXT_HOME, G_BNEXT_BACK, G_FIRST = 5, 6, 7, 8
local G_REFUSED = 37        -- quest-side codes grow DOWN from 39 (39 unfocus, 38 share, 37 this), the guests UP from 8
-- q35: where Start goes while a room holds: 36 = the rooms keep it (the app's pause wheel; the default), 35 = the game (the toolbar off)
local G_START_ROOMS, G_START_GAME = 36, 35
-- q38 / q41 (the map session, 2026-10-16; the user: the map module's portrait, "selecting it opens the menu options as if I was to
-- normally open my character portrait menu", then "no not the menu on the top screen, I want a replication of the menu on the bottom
-- screen basically that the controller still drives"): 34 READS the game's own player menu (the portrait's) and sends it as a
-- {"type":"pmenu"} document; 26..33 are the two base-8 digits of the entry to run (see WC.pmenu below). Below Start's 35 / 36.
local G_PLAYER_MENU = 34
local startToRooms = true
local startDirty = false      -- changed in a fight: the holding room is re-taken with its new keys when the fight ends
local guest = {}
for _, id in ipairs(GUESTS) do guest[id] = { want = false, held = false, pending = false, n = 0, bnext = "home" } end

local function capture()
    local c = WC.capture
    if type(c) == "table" and (c.version or 0) >= 3 and type(c.take) == "function" and type(c.release) == "function" then return c end
end
local function capId(id) return "pad:" .. id end
local function gack(id, op, ok, why, to)
    WC.send(WC.encode({ type = "guestack", id = id, op = op, ok = ok and true or false, why = why, to = to, v = QV }))
    WC.push()
end
-- the guest the CAPTURE names as its holder (q36: with v20's overlay a room can be suspended under the wheel, still "held" here, and
-- must never answer the wheel's leave / bnext / refusal); without a readable capture, the first held guest as before
local function guestOf(capHolder)
    for _, id in ipairs(GUESTS) do if capId(id) == capHolder then return id end end
end
local function holder()
    local c = capture()
    if c then
        local okS, st = pcall(c.state)
        if okS and type(st) == "table" then return guestOf(st.holder) end
    end
    for _, id in ipairs(GUESTS) do if guest[id].held then return id end end
end
-- after a release: the room the capture handed back to, if it is one of ours (v20 resumes a suspended room under the wheel)
local function resumedTo()
    local c = capture()
    if not c then return nil end
    local okS, st = pcall(c.state)
    return okS and type(st) == "table" and guestOf(st.holder) or nil
end
-- a guest starts holding: a fresh hold's B leads home (the capture's own default after a take)
local function nowHeld(g)
    if not g.held then g.bnext = "home" end
    g.pending, g.held = false, true
end

-- B FREES IN-GAME (q17, Home 2026-10-06: the user found the radial's close "SUPER delayed": 3 of 21 closes needed re-sent free taps,
-- the worst held the controller 2 s). For a guest in BFREE, B while its bnext is "home" lets go of the controller HERE, at the B
-- itself (the capture hands a guest B on its release), instead of the app's free tap afterwards. The press is relayed first, so the
-- app closes as before; the capture's own report says "B" (the app's route Home), and the answer is guestack leave "B in-game".
-- The app keeps bnext right with the existing columns: 6 (home) where B closes, 7 (back) where B is the room's own (the radial's
-- slot chooser: back to the picker).
-- q37 CLOSE FIRST (RC3, the user: "sometimes when closing the quests room the map opens on the top screen"): letting the quests guest
-- go restores the alpha of the game's world map (the capture's unhide), so a let-go while WorldMapFrame still SHOWS puts the open map
-- on the top screen -- a close tap that strayed and a later free, a free from an app that missed "shown". While the map shows, the
-- quests guest is NOT let go: the answer is ok false, why "close first" (the map stays invisible, the keys stay captured) and the app
-- taps the map's close cell; the window's OnHide then lets go (questWinHidden), the alpha coming back on a CLOSED map. Not in a fight:
-- there only interact's secure leave gives the pad back anyway.
local function closeFirst(id)
    return id == "quests" and guest[id].held and worldMapShown() and not InCombatLockdown()
end

-- (not leaveInGame below: it runs on B's passed("PAD2"), i.e. after the game's own close button was clicked through spec.back)
local function leaveInGame(id)
    local g = guest[id]
    local c = capture()
    g.want = false
    local okC, ok, why = false, false, nil
    if c then okC, ok, why = pcall(c.release, capId(id), "B") end
    if okC and ok then g.held = false; return gack(id, "leave", true, "B in-game", resumedTo()) end
    gack(id, "leave", false, (okC and type(why) == "string") and why or "refused")
end

-- a guest's extra buttons: Start only while it goes to the rooms (q35)
local function extraFor(id)
    local ex = EXTRA[id]
    if not ex or startToRooms then return ex end
    local out = {}
    for _, k in ipairs(ex) do if k ~= "PADFORWARD" then out[#out + 1] = k end end
    return #out > 0 and out or nil
end
local function specFor(id)
    local spec = {
        extra = extraFor(id),
        -- q36 (capture v20, Interact i107; the user's ruling 2026-10-09: Start opens the pause wheel over EVERY room): the wheels open OVER
        -- a room that holds -- the room is suspended (no lost, no leave, its hidden window stays hidden) and resumed exactly as it was when
        -- the wheel lets go. An older capture just queues the take, as before
        overlay = (id == "radial") or nil,
        press = function(k)
            local g = guest[id]
            nowHeld(g)                               -- only the holder gets presses: the ENTER landed / the queue granted us
            g.n = g.n + 1
            WC.send(WC.encode({ type = "guestpad", id = id, k = type(k) == "string" and k or nil, n = g.n, v = QV }))
            WC.push()
            if k == "PAD2" and BFREE[id] and g.bnext == "home" then leaveInGame(id) end
        end,
        lost = function(why)
            local g = guest[id]
            g.held, g.pending = false, false
            -- the player left the room (B, the generic leave), or another room took it / queued after us: never taken again
            if why == "B" or why == "left" or why == "taken" then g.want = false end
            WC.send(WC.encode({ type = "guestpad", id = id, lost = type(why) == "string" and why or "lost", n = g.n, v = QV }))
            WC.push()
        end,
    }
    if id == "quests" then                                  -- q25: the game's quest log behind the room (see MAP_CLOSE above)
        spec.hide = { "WorldMapFrame" }
        local close = type(_G[MAP_CLOSE]) == "table" and MAP_CLOSE or nil
        spec.leave = close
        if close and guest[id].bnext == "home" then spec.back = close end
        if worldMapShown() and not InCombatLockdown() then spec.window = true end
        spec.passed = function(k) if k == "PAD2" and guest[id].held then leaveInGame(id) end end
    end
    return spec
end
local function holdsNow(c, id)
    local okS, st = pcall(c.state)
    return okS and type(st) == "table" and st.holder == capId(id)
end
local function takeFor(id)
    local c = capture()
    if not c then return false, "no controller capture" end
    local g = guest[id]
    local okC, ok, why = pcall(c.take, capId(id), specFor(id))
    if not okC then g.held, g.pending = false, false; return false, "capture failed" end
    if not ok then g.held, g.pending = false, false; return false, type(why) == "string" and why or "refused" end
    -- ok: held now, or pending: in a fight until Home's tap lands on the ENTER cell (i81), or queued behind the holder (i82)
    if not holdsNow(c, id) then
        g.held, g.pending = false, true
        return true, "pending"
    end
    nowHeld(g)
    return true
end
local function nobodyHolds(c)
    local okS, st = pcall(c.state)
    return okS and type(st) == "table" and st.holder == nil
end
-- a pending take turns into a hold when the capture names us (the ENTER landed, or the queue granted us). Out of a fight a
-- pending take with NOBODY holding is let go here and taken again at once by retake: the fight's end dropped the pending ENTER,
-- or the queue has not granted it yet -- taking now is safe either way (the capture's own wait for us is then cleared).
local function sync()
    local c = capture()
    if not c then return end
    for _, id in ipairs(GUESTS) do
        local g = guest[id]
        if g.pending then
            if holdsNow(c, id) then
                nowHeld(g)
                gack(id, "hold", true, "entered")
            elseif not InCombatLockdown() and nobodyHolds(c) then
                g.pending = false
            end
        end
    end
end

-- one guest's hold / free: the qact columns' code and (q16) the in-game call below; each answers with ONE guestack and returns
-- what it answered (ok, why)
local function answer(id, op, ok, why) gack(id, op, ok, why); return ok and true or false, why end
local function holdGuest(id)
    local g = guest[id]
    g.want = true
    sync()
    if g.held then return answer(id, "hold", true, "already held") end
    if g.pending then return answer(id, "hold", true, "pending") end
    local ok, why = takeFor(id)
    return answer(id, "hold", ok, why)  -- refused (busy, an older capture in combat): still WANTED, so taken again later
end
local function freeGuest(id)
    local g = guest[id]
    g.want = false
    sync()
    if closeFirst(id) then return answer(id, "free", false, "close first") end     -- q37: the map first
    local c = capture()
    if g.pending then
        if c then pcall(c.release, capId(id)) end              -- gives the pending ENTER up
        g.pending = false
        return answer(id, "free", true, "pending given up")
    end
    if g.held then
        local okC, ok, why = false, false, nil
        if c then okC, ok, why = pcall(c.release, capId(id)) end
        -- in a fight only interact's secure leave gives the pad back (Home taps it): the room keeps holding until it lands
        if okC and not ok and why == "tap leave" then return answer(id, "free", false, "tap leave") end
        g.held = false
        if okC and ok then gack(id, "free", true, nil, resumedTo()); return true end   -- q36: to = the room the wheel handed back to
    end
    return answer(id, "free", true)
end
local GACT = {}
for i, id in ipairs(GUESTS) do
    GACT[G_FIRST + 2 * (i - 1)] = function() holdGuest(id) end          -- hold
    GACT[G_FIRST + 2 * (i - 1) + 1] = function() freeGuest(id) end      -- free
end
GACT[G_LEAVE_B] = function()
    sync()
    local id = holder()
    if not id then return gack(nil, "leave", false, "no guest holds the controller") end
    if closeFirst(id) then guest[id].want = false; return gack(id, "leave", false, "close first") end     -- q37
    local c = capture()
    guest[id].want = false
    local okC, ok, why = false, false, nil
    if c then okC, ok, why = pcall(c.release, capId(id), "B") end
    if okC and not ok and why == "tap leave" then return gack(id, "leave", false, "tap leave") end    -- in a fight: never retried
    guest[id].held = false
    gack(id, "leave", true, nil, resumedTo())          -- q36: to = the room a wheel handed back to
end
local function bnext(where)
    local id = holder()
    if not id then return gack(nil, "bnext", false, "no guest holds the controller") end
    local c = capture()
    local okC, ok = false, false
    -- q25: the quests guest's B closes the game's quest log only while it leads Home: re-taken with or without spec.back first
    -- (out of a fight; a take in one would turn pending)
    if c and id == "quests" and not InCombatLockdown() then guest[id].bnext = where; takeFor(id) end
    if c and type(c.bnext) == "function" then okC, ok = pcall(c.bnext, capId(id), where) end
    if okC and ok then guest[id].bnext = where end          -- q17: B frees in-game only while it leads home
    gack(id, "bnext", okC and ok, (not (okC and ok)) and "refused" or nil)
end
GACT[G_BNEXT_HOME] = function() bnext("home") end
GACT[G_BNEXT_BACK] = function() bnext("back") end

-- REFUSED IN A FIGHT (capture contract v8, interact's i74: a room keeps the controller through a fight; when it refuses something
-- because of the fight it says so, and Home pulses the red flash). The app taps this when its room turns a press down for combat;
-- the quest actions below do it themselves when a tap reaches them in combat. No answer of its own: the flash is the answer.
refusedInFight = function()
    local id = holder()
    if not id then return end
    local c = capture()
    if c and type(c.refused) == "function" then pcall(c.refused, capId(id)) end
end
GACT[G_REFUSED] = refusedInFight

-- q35 START: to the rooms (the app's pause wheel) or to the game (its own wheel). The room holding now is re-taken with its new keys at
-- once (out of a fight; in one, when it ends); every later take follows. One guestack: op "start", why "rooms" | "game".
local function startTo(rooms)
    startToRooms = rooms
    local id = holder()
    if id then
        if InCombatLockdown() then startDirty = true else takeFor(id) end
    end
    gack(nil, "start", true, rooms and "rooms" or "game")
end
-- THE PLAYER MENU, MIRRORED (q41). The game's unit popup menu for "SELF" (the menu the player's portrait opens) is Blizzard Lua, so
-- the binary cannot name its API: whichever this build has is tried (UnitPopupManager:GetMenu, else UnitPopupMenus.SELF; the entries
-- through GetEntries or GetMenuButtons). Each entry the game would show (CanShow) goes out with its own words (GetText), enabled
-- (IsEnabled) and checked (IsChecked) state, and its sub-entries (IsMenu); an entry that can be run gets an id (at most 64). The app
-- draws it on the bottom screen and sends an id back as two digit taps; the entry's own OnClick runs it, as the game's menu would.
-- Never run: the focus entries (FocusUnit is protected: an add-on's call is blocked). No CVar, no macro, no binding is written.
-- {"type":"pmenu", v, shape, items = {{t, i?, en, ck?, ttl?, sub?}, ...}} ; a run answers guestack op "pmenurun" (why = the words).
WC.pmenu = { list = {}, d1 = nil, d1at = 0 }
function WC.pmenu.ctx()
    return { unit = "player", which = "SELF", name = TS(function() return (UnitName("player")) end),
             guid = TS(function() return UnitGUID("player") end) }
end
function WC.pmenu.entries(obj, ctx)
    for _, fn in ipairs({ "GetEntries", "GetMenuButtons" }) do
        if type(obj) == "table" and type(obj[fn]) == "function" then
            local ok, r = pcall(obj[fn], obj, ctx)
            if ok and type(r) == "table" then return r, fn end
        end
    end
    return nil
end
function WC.pmenu.blocked(e)
    for _, g in ipairs({ "UnitPopupSetFocusButtonMixin", "UnitPopupClearFocusButtonMixin" }) do if _G[g] ~= nil and e == _G[g] then return true end end
    local okT, t = pcall(e.GetText, e, WC.pmenu.ctx())
    return okT and (t == _G.SET_FOCUS or t == _G.CLEAR_FOCUS)
end
function WC.pmenu.read()
    local P = WC.pmenu
    P.list = {}; P.d1 = nil
    local ctx = P.ctx()
    local menu, shape = nil, "none"
    local mgr = _G.UnitPopupManager
    if type(mgr) == "table" and type(mgr.GetMenu) == "function" then
        local ok, m = pcall(mgr.GetMenu, mgr, "SELF"); if ok and type(m) == "table" then menu = m end
    end
    if not menu and type(_G.UnitPopupMenus) == "table" and type(_G.UnitPopupMenus.SELF) == "table" then menu = _G.UnitPopupMenus.SELF end
    local function call(e, fn, dflt)
        if type(e[fn]) ~= "function" then return dflt end
        local ok, v = pcall(e[fn], e, ctx)
        if not ok then return nil end
        return v
    end
    local function walk(es, depth)
        local out = {}
        for _, e in ipairs(es) do
            if type(e) == "table" and #P.list < 64 then
                local show = call(e, "CanShow", true)
                local t = call(e, "GetText", nil)
                if show == true and type(t) == "string" and WC.isPlain(t) and t ~= "" then
                    local row = { t = t }
                    if call(e, "IsTitle", false) == true then row.ttl = 1
                    else
                        local sub = (depth < 2 and call(e, "IsMenu", false) == true) and P.entries(e, ctx) or nil
                        if sub then row.sub = walk(sub, depth + 1) end
                        row.en = (call(e, "IsEnabled", true) == true and not P.blocked(e)) and 1 or 0
                        local ck = call(e, "IsChecked", nil)
                        if type(ck) == "boolean" then row.ck = ck and 1 or 0 end
                        if not row.sub then P.list[#P.list + 1] = e; row.i = #P.list - 1 end
                    end
                    out[#out + 1] = row
                end
            end
        end
        return out
    end
    local items = {}
    if menu then
        local es, how = P.entries(menu, ctx)
        if es then shape = how; items = walk(es, 0) end
    end
    pcall(function() WC.send(WC.encode({ type = "pmenu", v = QV, shape = shape, items = items })); WC.push() end)
end
-- q42: NOTHING IS RUN (test10 on the Thor: an entry's OnClick from add-on code ran tainted; Reset all instances' confirm then had the
-- game's own gamepad code call SetPreferredGamepadInteractTarget() from that stack: ADDON_ACTION_FORBIDDEN blamed on this add-on; the
-- toggles did nothing). The menu stays readable; a run is refused until each entry has a taint-free way (the plan with tracking).
function WC.pmenu.run(id)
    gack(nil, "pmenurun", false, "not available yet")
end
GACT[G_PLAYER_MENU] = function() WC.pmenu.read() end
for d = 0, 7 do
    GACT[33 - d] = function()
        local P = WC.pmenu
        if P.d1 ~= nil and GetTime() - P.d1at < 2 then local id = P.d1 * 8 + d; P.d1 = nil; P.run(id)
        else P.d1 = d; P.d1at = GetTime() end
    end
end
GACT[G_START_ROOMS] = function() startTo(true) end
GACT[G_START_GAME] = function() startTo(false) end

-- a guest that still wants the controller gets it again once nobody holds it (after a fight, or when an NPC window closes)
local function retake()
    local c = capture(); if not c then return end
    sync()
    if startDirty and not InCombatLockdown() then       -- q35: Start changed in a fight
        startDirty = false
        local id = holder()
        if id then takeFor(id) end
    end
    for _, id in ipairs(GUESTS) do
        local g = guest[id]
        if g.want and not g.held and not g.pending then
            local okS, st = pcall(c.state)
            if okS and type(st) == "table" and st.holder == nil then
                local ok, why = takeFor(id)
                if ok then gack(id, "hold", true, why == "pending" and "pending" or "taken again") end
            end
            return                          -- one guest at a time: only one room shows
        end
    end
end
if type(C_Timer) == "table" and type(C_Timer.NewTicker) == "function" then C_Timer.NewTicker(1, retake) end
-- THE IN-GAME CALL (q16, DirectPad step A, Home + Spells 2026-10-06): Spells' router takes a room's guest capture at the SAME click that
-- sends the room command (from that click's post-hook: add-on code, never a secure snippet), instead of the app's hold tap ~460 ms
-- later (Home's measured median). The same code and the same answers (one guestack per call, the capture's holder report) as the
-- qact columns, so the app cannot tell the two apart; a hold tap that arrives later answers "already held". Returns ok, why.
local function known(id) return type(id) == "string" and guest[id] ~= nil end
WC.questGuests = { list = GUESTS, state = guest, retake = retake,     -- list / state / retake: for the harness
    hold = function(id) if not known(id) then return false, "unknown guest" end; return holdGuest(id) end,
    free = function(id) if not known(id) then return false, "unknown guest" end; return freeGuest(id) end,
}

-- q25 THE QUEST LOG'S WAY IN AND OUT (with Map's mr2: Map.lua decides every WorldMapFrame open one frame after its OnShow, and hands
-- the Quests room's to WC.questsWindow.opened):
--   opened(false) = the GAME opened its quest log (the quest-log key, or the radial's QuestLogMicroButton): the Quests room takes the
--     controller here at once (q16's in-game call: the window never sits unheld on the top screen; a window take pre-empts the holder)
--     and a "questwin" document {n, own, shown} brings the room up on the bottom screen (the app: current documents only, n grew);
--   opened(true)  = the room's OWN open (Map's shared mapw open cell, clicked while "pad:quests" held): already held, nothing to take.
--   The window HIDING while the room holds -- not by B, which passed("PAD2") already handled -- is the room's way out too (the
--   app's close cell when it leaves, or the game's own close): let go here, as Map's room does on its OnHide.
local qwin = { n = 0, told = false }
local function sendWin(own, shown)
    qwin.told = shown and true or false                    -- q27: whether the app last heard "shown"
    WC.send(WC.encode({ type = "questwin", v = QV, n = qwin.n, own = own and true or false, shown = shown and true or false }))
    WC.push()
end
WC.questsWindow = {
    opened = function(own)
        if own then return sendWin(true, true) end
        -- n grows ACROSS sessions too (q23's rule for the pins' set): the app keeps its last n through a game reload, so a new
        -- session counts on from the server's second instead of from 1 again
        if qwin.n == 0 then
            local okT, t = pcall(GetServerTime)
            if not (okT and type(t) == "number") then okT, t = pcall(time) end
            qwin.n = (okT and type(t) == "number") and (math.floor(t) % 1000000) * 1000 or 0
        end
        qwin.n = qwin.n + 1
        local g = guest.quests
        if not (g.held or g.pending) then holdGuest("quests") end
        sendWin(false, true)
    end,
    state = qwin,                                          -- for the harness
}
local function questWinHidden()
    if worldMapShown() then return end                     -- shown again since (a toggle and back)
    -- q37: let go FIRST, then say "hidden": the app hears the free (held = false) before the window's close, so it can never take
    -- "closed while I hold" for a reason to open the log again
    local g = guest.quests
    if g.held or g.pending then freeGuest("quests") end
    -- q27: "hidden" only after we said "shown" (Map's logs, b107 / b108: every Map-room close sent an empty questwin)
    if qwin.told then sendWin(false, false) end
end
local hookedHide = false
local function hookWorldMap()
    if hookedHide then return end
    local fr = _G.WorldMapFrame
    if type(fr) ~= "table" or type(fr.HookScript) ~= "function" then return end
    hookedHide = true
    -- one frame later, as Map's own hooks: the click that hid it (B's passed, the app's close cell) has run its own code by then
    pcall(fr.HookScript, fr, "OnHide", function() C_Timer.After(0, function() pcall(questWinHidden) end) end)
end
hookWorldMap()
local hf = CreateFrame("Frame")
hf:SetScript("OnEvent", function() hookWorldMap(); hookMicro() end)
pcall(function() hf:RegisterEvent("PLAYER_LOGIN") end)

if WC.pad then
    WC.pad.op("qpick", "quest", function(key)
        -- a column no quest holds (a tap made from a stale document) picks nothing, and clears an older pick
        picked = key ~= nil and { id = key, at = GetTime() } or nil
    end)
    WC.pad.op("qact", "questact", function(_, col)
        -- the generic guests' columns: no quest pick is read or spent here
        if GACT[col] then return GACT[col]() end
        local a = ACTS[col]
        local p = picked
        picked = nil
        quiet = true
        if not a then
            ack("qact", nil, false, "unknown action")
        elseif a.nopick then
            pcall(a.fn)
        elseif not p then
            ack(a.op, nil, false, "no quest picked")
        elseif GetTime() - p.at > PICK_SECONDS then
            ack(a.op, p.id, false, "the pick was too old")
        else
            pcall(a.fn, p.id)
        end
        quiet = false
    end)
    -- a new UI scale moves every cell: re-publish the geometry at once, not at the next change
    WC.pad.onLayout(function() soon() end)
end

SLASH_WCQ1 = "/wcq"
SlashCmdList["WCQ"] = function(arg)
    arg = (arg or ""):lower():gsub("^%s+", ""):gsub("%s+$", "")
    local cmd, rest = arg:match("^(%S*)%s*(.*)$")
    local id = tonumber(rest)
    if cmd == nil or cmd == "" then
        emitQuests(true); WC.push()
        local doc = WC.collectQuests()
        say("quests " .. QV .. ": " .. tostring(doc.n) .. " in log, focus " ..
            tostring(doc.focus or "none") .. ", text " .. doc.descMode ..
            ", secret " .. doc.sec.secret .. ", threw " .. doc.sec.threw)
        return
    end
    local op = OPS[cmd]
    if not op then say("/wcq [track <id> | untrack | watch <id> | unwatch <id> | abandon <id>]") return end
    op(id)
end
