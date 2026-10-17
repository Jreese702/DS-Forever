-- WowCompanion / Map.lua
-- The MAP room: hide the minimap on the top screen, and rebuild a navigation display from
-- real data on the bottom screen.
--
-- WHAT IS AVAILABLE (verified present in the client binary):
--   GetPlayerFacing · C_Map.GetPlayerMapPosition/GetBestMapForUnit/GetMapInfo
--   C_AreaPoiInfo.GetAreaPOIForMap/GetAreaPOIInfo · C_TaxiMap.GetTaxiNodesForMap
--   C_QuestLog.GetQuestsOnMap/GetNextWaypoint/GetDistanceSqToQuest
--   C_QuestLog.ReadyForTurnIn/GetNextWaypointForMap (turn-in markers)
--   C_QuestLine.RequestQuestLinesForMap/GetAvailableQuestLines + QUESTLINE_UPDATE (available
--   quests; present in the string table, UNPROVEN on the device -- map.availN is the probe)
--   C_SuperTrack.GetSuperTrackedQuestID (C_SuperTrack's, not C_QuestLog's, on this build)
--   C_VignetteInfo.GetVignettes/GetVignetteInfo/GetVignettePosition
--
-- WHAT IS NOT, AND CANNOT BE REBUILT:
--   Minimap tracking blips (herbs, ore, vendors, trainers). GetMinimapBlip,
--   GetMapLandmarkInfo and the gathering-node APIs do not exist. The client draws those
--   straight onto the minimap texture and never exposes them. Hiding the minimap loses them.
--   Say so in the UI rather than pretending the radar is a full replacement.

local ADDON = ...
local WC = _G.WowCompanion
local S, SN, CALL = WC.S, WC.SN, WC.CALL
-- a string the game really gave, else nil (S hands back WC.ABSENT / THREW / SECRET as marker strings: never sent as data)
function WC.plainStr(v)
    if type(v) == "string" and v ~= WC.ABSENT and v ~= WC.THREW and v ~= WC.SECRET then return v end
end

-- Candidate frames, most-specific first. MinimapCluster is the modern wrapper; plain Minimap
-- is the fallback. Both are probed at runtime because frame names live in FrameXML (inside
-- CASC), NOT in the binary -- so the string table cannot tell us which exist.
local CANDIDATES = { "MinimapCluster", "MinimapBackdrop", "Minimap" }

function WC.probeMinimapFrames()
    local found = {}
    for _, name in ipairs(CANDIDATES) do
        local fr = _G[name]
        if fr then
            found[#found + 1] = {
                name = name,
                shown = S(function() return fr:IsShown() end),
                hasHide = type(fr.Hide) == "function",
                protected = S(function() return fr:IsProtected() end),
            }
        end
    end
    return found
end

-- HIDE = ALPHA ONLY. These used to call fr:Hide() / fr:Show(). Hide() runs Blizzard's OnHide
-- handlers in the addon's taint, and MinimapCluster is an Edit Mode managed frame whose layout also
-- places the quest tracker: the same kind of call (bags' CloseAllBags / OpenAllBags from addon code)
-- was MEASURED at 2,049 ADDON_ACTION_FORBIDDEN in under a minute on 2026-09-28. So the frame stays
-- shown and laid out, and only its alpha changes -- the same contract as WC.hudHide. No Hide, no
-- Show, no field written on a Blizzard frame; the state lives in this file.
-- The cost: an invisible minimap still takes a click on the top screen, like every alpha hide.
-- Refused in combat, as before, so nothing here can ever be a blocked action.
local hiddenFrame = nil
-- THE DEATH PEEK (see "THE CORPSE" below): while dead with no corpse position at all, the minimap is DRAWN although the hub
-- wants it hidden. hiddenFrame keeps saying what the hub asked; only the alpha, icons and shading follow the peek, so the
-- hub's layout is never touched or fought, and the hide comes back at revive.
local peek = false

local function minimapFrame()
    for _, name in ipairs(CANDIDATES) do
        local fr = _G[name]
        if fr and type(fr.SetAlpha) == "function" then return fr, name end
    end
end

--- true while the minimap is hidden by WC.hideMinimap. The frame itself stays SHOWN (alpha 0), so
--- IsShown() no longer says anything: ask this instead.
function WC.minimapIsHidden() return hiddenFrame ~= nil end

-- THE MINIMAP'S OWN MARKERS (the user, 2026-09-30, LATER.md 20: "the minimap itself is hidden but all of its content like
-- the player and quests and markers are all showing with an invisible background"). The engine draws the blips (quest
-- marks, tracking, the player) on the Minimap widget itself, and the cluster's alpha does not reach them (measured
-- 2026-09-29). The widget has a setter for their size, Minimap:SetIconScale (MinimapFrameAPI; the game's own Edit Mode
-- calls it on mainline, camelot never does, so here it sits at the engine's 1). Hiding sets it to 0, showing puts back
-- the game's value. A property setter like SetAlpha: no Hide, no Show, no field written, refused in combat as before.
local ICONS_OFF = 0
local function minimapWidget()
    local mm = _G.Minimap
    if mm and type(mm.SetIconScale) == "function" then return mm end
end
--- the game's own icon scale: Edit Mode's setting where the game has one (mainline), else the engine's 1
local function gameIconScale()
    local v = SN(function()
        local mc = _G.MinimapCluster
        local en = Enum and Enum.EditModeMinimapSetting and Enum.EditModeMinimapSetting.IconScale
        if mc and en and type(mc.GetSettingValue) == "function" then return mc:GetSettingValue(en) end
    end)
    if type(v) == "number" and v > 0 then return v / 100 end
    return 1
end
local function setIcons(scale)
    local mm = minimapWidget()
    if not mm then return false end
    if pcall(function() mm:SetIconScale(scale) end) then return true end
    -- a client that refuses 0: the smallest it may take
    return scale == ICONS_OFF and pcall(function() mm:SetIconScale(0.01) end) or false
end
WC.minimapIconsOff = false     -- what the last hide / show did to the markers (for the probe line and the tests)

-- THE QUEST-AREA LINES (b-quest-area-minimap; the user, 2026-09-30 and again 2026-10-13 with Game UI Off: "the blue bubbles from
-- quests is still appearing in the minimap"). The engine draws the quest / bonus-objective / dig-site area borders on the Minimap
-- widget, and neither the cluster's alpha nor the icon scale reaches them. Measured on the Thor (v1.0.4's rounds): the nine blob
-- alpha setters, the three ring scalars, the nine blob textures and the widget's own alpha all TAKE and change nothing visible;
-- the line the user saw is the focused quest's outline (interface/minimap/ui-questblobminimap-outsideselected, which no setter
-- reaches). What works is the MASK, which clips everything the widget draws: while hidden it is MASK_OFF, the client's own fully
-- transparent minimap picture (533894, ui-questblobminimap-inside: alpha 0 on every pixel); shown again it is MASK_ON, the atlas
-- camelot's Skin.lua sets in both rotate modes. With the markers' icon scale beside it. A property setter, runtime only.
-- KEEPING IT: the hub hides the minimap ONCE (Hub.lua's apply never asks again while it says hidden), and Skin.lua puts its own
-- mask back on a rotateMinimap change, so while the hub's hide stands (not the death peek, not in combat) both are said again on
-- the quest / zone / POI / loading events, at combat's end and every REASSERT_S on the probe's frame. The map document carries
-- mmk / mmi (the mask / icon scale taken) and mmf (the frame the hub hid) while hidden. In WC (Map.lua's main chunk is at Lua
-- 5.1's 200-local limit).
-- THE RIM ARC (b-minimap-edge-effect; the user, 1.0.8-test1, at a quest area's edge: "a subtle white, lattice-looking cloud arc"
-- on the hidden minimap's rim). It is the blob RING, the client's interface/minimap/ui-questblob-minimapring (533893, a grey-white
-- diamond lattice; bonus objectives 1117898, dig sites 533890), which the engine draws on the rim where an area runs past the
-- minimap, so the mask does not clip it. While hidden each ring's picture is the client's fully transparent 533894; shown again it
-- is the client's own again (exact: no scalar or alpha is touched). The pictures change on a hide / show / the events, not on the
-- 1 s tick. mmg = the ring pictures taken (0..3).
WC.mmHide = { REASSERT_S = 1.0, at = -1000, i = false, k = false, g = 0,
              MASK_OFF = 533894, MASK_ON = "ui-hud-minimap-frame-generic-mask",
              RINGS = { { "SetQuestBlobRingTexture", 533893 }, { "SetTaskBlobRingTexture", 1117898 },
                        { "SetArchBlobRingTexture", 533890 } } }

-- the minimap's engine-drawn layers off ([off]) or back to the game's own; [rings]: the ring pictures too (not on the 1 s tick)
function WC.mmHide.layers(off, rings)
    local H, mm = WC.mmHide, _G.Minimap
    H.i = setIcons(off and ICONS_OFF or gameIconScale())
    H.k = false
    if mm and type(mm.SetMaskTexture) == "function" then
        H.k = pcall(mm.SetMaskTexture, mm, off and H.MASK_OFF or H.MASK_ON)
    end
    if rings ~= false and mm then
        local n = 0
        for _, e in ipairs(H.RINGS) do
            local fn = mm[e[1]]
            if type(fn) == "function" and pcall(fn, mm, off and H.MASK_OFF or e[2]) then n = n + 1 end
        end
        H.g = n
    end
    if off then WC.minimapIconsOff = H.i elseif H.i then WC.minimapIconsOff = false end
end

function WC.mmHide.reassert(force)
    local H = WC.mmHide
    if not hiddenFrame or peek then return false end
    if type(InCombatLockdown) == "function" and InCombatLockdown() then return false end
    local t = (type(GetTime) == "function") and GetTime() or 0
    if not force and t - H.at < H.REASSERT_S then return false end
    H.at = t
    H.layers(true, force == true)
    return true
end

function WC.hideMinimap()
    if InCombatLockdown() then return false, "in combat" end
    local fr, name = minimapFrame()
    if not fr then return false, "no minimap frame found" end
    -- dead with no corpse to show: the hub's hide is kept as its state, and the minimap stays drawn until the peek ends
    if peek then hiddenFrame = name; return true, name end
    local ok = pcall(function() fr:SetAlpha(0) end)
    if not ok then return false, "could not hide" end
    hiddenFrame = name
    WC.mmHide.layers(true)                          -- the engine-drawn markers and the quest-area lines too
    WC.mmHide.at = (type(GetTime) == "function") and GetTime() or 0
    return true, name
end

function WC.showMinimap()
    if InCombatLockdown() then return false, "in combat" end
    local fr, name = nil, hiddenFrame
    if name then fr = _G[name] end
    if not (fr and type(fr.SetAlpha) == "function") then fr, name = minimapFrame() end
    if not fr then return false, "could not show" end
    local ok = pcall(function() fr:SetAlpha(1) end)
    if not ok then return false, "could not show" end
    hiddenFrame = nil
    WC.mmHide.layers(false)
    return true, name
end

-- ---------------------------------------------------------------- the navigation payload
local function mapID()
    local ok, id = CALL(function() return C_Map.GetBestMapForUnit("player") end)
    if ok then return id end
end

local function playerXY(id)
    local ok, p = CALL(function() return C_Map.GetPlayerMapPosition(id, "player") end)
    if not ok or not p then return nil, nil end
    local okx, x, y = CALL(function() return p:GetXY() end)
    if okx then return x, y end
    return SN(function() return p.x end), SN(function() return p.y end)
end

-- The super-tracked quest (the one the player chose to follow), or nil.
-- On this build the call lives in C_SuperTrack. Reading C_QuestLog.GetSuperTrackedQuestID alone
-- made superTracked null on every snapshot; it stays only as a fallback for a client that has
-- that one and not the other. The lookup is INSIDE SN(), so a missing namespace or a throwing
-- call is just nil, and a secret answer never reaches the line. 0 is the API's "none", and it IS sent (mp1, the user on test24:
-- "after unfocusing a quest on the minimap, I cant re-focus it": with the key left out for "none" the app kept the old focus, so A
-- kept offering Unfocus); nil means unknown.
local function superTrackedQuest()
    -- while the route's pin stands (b-route-waypoint) the game super-tracks the pin; the quest it stands for is still the focus
    local R = WC.routeWp
    if R and R.active and R.qid and R.qid > 0 then return R.qid end
    local id = SN(function()
        local get = C_SuperTrack and C_SuperTrack.GetSuperTrackedQuestID
        if type(get) ~= "function" then get = C_QuestLog and C_QuestLog.GetSuperTrackedQuestID end
        return get()
    end)
    if id and id > 0 then return id end
    if id == 0 then return 0 end
end

-- ---------------------------------------------------------------- quest markers
-- Two layers besides the objective blobs (user ruling 2026-09-27: "we are missing the nearby
-- available quests and completed quests"):
--  * TURN-INS. A map.quests row gains done = true when C_QuestLog.ReadyForTurnIn says so, and
--    tx / ty, the turn-in spot ON THIS MAP from C_QuestLog.GetNextWaypointForMap. The app draws
--    "?" there, or at x / y when tx / ty are absent.
--  * AVAILABLE quests, "!" at the giver: map.avail, from C_QuestLine.GetAvailableQuestLines.
--    That call only has an answer after RequestQuestLinesForMap and a QUESTLINE_UPDATE, so it
--    cannot be read inside a snapshot: the answers are cached here (the last AVAIL_MAPS maps)
--    and the snapshot copies this map's. The rows are sorted (id, then x, then y), so neither
--    the wire nor the change key depends on the order the client answers in.
--    map.availN is the raw count before filtering, or -1 until an answer for THIS map arrived
--    (an EMPTY answer counts only once a second one, to a later request, confirms it), when the
--    map has fallen out of the cache, and forever when the API is missing. It is the probe for
--    whether Forever's Classic-era quests belong to quest lines at all.
--
-- FRAME BUDGET. Nothing here runs per frame. QUEST_LOG_UPDATE (every kill credit) costs one flag
-- test; at most once a second it buys a readiness check, and a snapshot only when the set of
-- ready quests on this map really changed. Any number of QUESTLINE_UPDATEs in one frame cost one
-- read, on the next frame. Quest lines are requested on a map change, on accept / turn-in /
-- abandon, on level-up, and otherwise at most once a minute (riding the heartbeat); the two
-- re-requests the CLIENT's own answers trigger are capped (see CAPS). And no snapshot is forced
-- for a map Core's ticker is about to snapshot anyway (see WHO FLUSHES A NEW MAP).
local QL_REFRESH = 60       -- s: re-ask this often at most when nothing else asked
local QL_EVENT_DELAY = 0.5  -- s: a turn-in fires QUEST_TURNED_IN and QUEST_REMOVED; ask once
local RR_GAP = 5            -- s: QUESTLINE_UPDATE(requestRequired) re-asks at most this often
local READY_GAP = 1.0       -- s: at most one readiness check per this, however many events
local AVAIL_MAX = 50        -- available quests on the wire; availN still says how many there were
local AVAIL_SCAN = 200      -- QuestLineInfo rows read per answer at most: bounds one read's cost
local AVAIL_MAPS = 4        -- maps whose answer is kept, most recently used first

local function plainNum(v)
    if type(v) == "number" and WC.isPlain(v) then return v end
end

-- THE GAME'S OWN MINIMAP TRACKING (Map, 2026-10-06): QuestieDB's node spots (Quests' "qdbnodes") show on the companion's map
-- only while the matching tracking is ON, as the game's own minimap shows its herb / ore / treasure blips. This build has
-- C_Minimap.GetTrackingInfo(i) but no count getter, so the index is read until it answers nothing (TRACK_SCAN at most). The
-- spell ids are classic's: Find Herbs 2383, Find Minerals 2580, Find Treasure 2481. -> {h=1|0, m=1|0, t=1|0}, a key only for
-- a tracking the character has; a secret / missing field counts as nothing.
local TRACK_SPELLS = { [2383] = "h", [2580] = "m", [2481] = "t" }
local TRACK_SCAN = 40
local function trackingState()
    local t = {}
    for i = 1, TRACK_SCAN do
        local ok, info = CALL(function() return C_Minimap.GetTrackingInfo(i) end)
        if not ok or type(info) ~= "table" then break end
        local sid = SN(function() return info.spellID end)
        local key = sid and TRACK_SPELLS[sid]
        if key then
            local oka, a = CALL(function() return info.active end)
            t[key] = (oka and type(a) == "boolean" and WC.isPlain(a) and a == true) and 1 or 0
        end
    end
    return t
end
WC.trackingState = trackingState

local function onMap01(x, y)
    return x ~= nil and y ~= nil and x >= 0 and x <= 1 and y >= 0 and y <= 1
end

-- The player's map as a PLAIN number, or nil. These ids get compared, so a secret must never
-- get this far.
local function currentMap()
    return SN(function() return C_Map.GetBestMapForUnit("player") end)
end

-- Missing, throwing and secret all read as "not ready". S() hands back a plain value or a
-- sentinel string, so the comparison can never touch a secret.
local function readyForTurnIn(qid)
    return S(function() return C_QuestLog.ReadyForTurnIn(qid) end) == true
end

local function after(delay, fn)
    local T = C_Timer
    if type(T) ~= "table" or type(T.After) ~= "function" then return false end
    return (pcall(T.After, delay, fn))
end

local function snapshotSoon(reason)
    if type(WC.snapshotSoon) == "function" then WC.snapshotSoon(reason) end
end

-- ---------------------------------------------------------------- THE CORPSE
-- The user, 2026-10-01: "when dying the Gravestone and marker back to it needs to show up on the minimap. I think this can be
-- done by marking it on the map when releasing, but if I happen to log into a dead charactor, then if we cant parse this then
-- we will need to temporarily show the minimap while dead (only in the scenario that we dont have it stored)".
-- While the player is a GHOST the map document carries `ghost` and `corpse` = the corpse on THIS map {x, y, src}; x / y may
-- lie outside 0..1 when the corpse is on another map, which is all the rim arrow needs. Sources, in order:
--   "api"   C_DeathInfo.GetCorpseMapPosition -- the game's own world map (DeathMapDataProvider) draws the corpse from this
--           alone, with nothing stored, so it covers logging in dead. Its documentation marks no secret return; the values
--           still go through plainNum. Not on this map: the parent maps are asked, and a hit is carried to this map through
--           world space (C_Map.GetWorldPosFromMapPos / GetMapPosFromWorldPos).
--   "died"  where the player stood at PLAYER_DEAD, this session (the corpse is where you fell).
--   none    the minimap PEEKS: drawn on the top screen while dead (alpha only), hidden again at revive.
local died = nil            -- {map, x, y}: the player's spot at PLAYER_DEAD; cleared when alive again
local peekWant = false      -- a peek change that combat held back (applied at PLAYER_REGEN_ENABLED)

local function isGhost()
    local ok, g = CALL(UnitIsGhost, "player")
    return ok and type(g) == "boolean" and WC.isPlain(g) and g == true
end
local function isDeadOrGhost()
    local ok, g = CALL(UnitIsDeadOrGhost, "player")
    return ok and type(g) == "boolean" and WC.isPlain(g) and g == true
end

-- a Vector2D's x, y as plain numbers, or nil
local function vecXY(fn)
    local ok, x, y = CALL(function()
        local v = fn()
        if type(v) == "table" then return v.x, v.y end
    end)
    if not ok then return end
    x, y = plainNum(x), plainNum(y)
    if x and y then return x, y end
end

local function parentMap(id)
    return SN(function() local i = C_Map.GetMapInfo(id); return i and i.parentMapID end)
end

-- (x, y) on map [from] carried to map [to] through world space; nil when the two do not share a continent
local function mapToMap(from, x, y, to)
    if from == to then return x, y end
    if type(CreateVector2D) ~= "function" then return end
    local ok, cont, wpos = CALL(function() return C_Map.GetWorldPosFromMapPos(from, CreateVector2D(x, y)) end)
    if not ok or type(wpos) ~= "table" then return end
    local cid = plainNum(cont)
    if not cid then return end
    return vecXY(function() local _, mp = C_Map.GetMapPosFromWorldPos(cid, wpos, to); return mp end)
end

-- the corpse on map [id] from the game: on it, else on a parent map carried here
local function corpseFromApi(id)
    local x, y = vecXY(function() return C_DeathInfo.GetCorpseMapPosition(id) end)
    if x then return x, y end
    local p = id
    for _ = 1, 6 do
        p = parentMap(p)
        if not p or p == 0 then return end
        local px, py = vecXY(function() return C_DeathInfo.GetCorpseMapPosition(p) end)
        if px then return mapToMap(p, px, py, id) end
    end
end

--- the corpse on map [id]: x, y, source ("api" / "died"), or nil
local function corpseOn(id)
    local x, y = corpseFromApi(id)
    if x then return x, y, "api" end
    if died then
        x, y = mapToMap(died.map, died.x, died.y, id)
        if x then return x, y, "died" end
    end
end
WC.corpseOn = corpseOn          -- for the tests

-- the peek's visuals: drawn while peeking, back to the hub's hide when it ends (nothing to do when the hub shows it anyway)
local function setPeek(on)
    peekWant = on
    if on == peek then return true end
    if InCombatLockdown() then return false end       -- PLAYER_REGEN_ENABLED applies peekWant
    peek = on
    local fr = hiddenFrame and _G[hiddenFrame]
    if not (fr and type(fr.SetAlpha) == "function") then return true end
    if on then
        pcall(function() fr:SetAlpha(1) end)
        WC.mmHide.layers(false)
    else
        pcall(function() fr:SetAlpha(0) end)
        WC.mmHide.layers(true)
    end
    return true
end
WC.minimapPeeking = function() return peek end

--- the map document's death fields for map [id] (collectMap): ghost, corpse; and the peek decided here
local function deathFields(id, out)
    if not isGhost() then
        if peek or peekWant then setPeek(false) end
        return
    end
    out.ghost = true
    local x, y, src = corpseOn(id)
    if x then out.corpse = { x = x, y = y, src = src } end
    setPeek(x == nil)
    if peek then out.minimapPeek = true end
end
WC.deathFields = deathFields    -- for the tests

-- WHO FLUSHES A NEW MAP. Core's 0.1 s position ticker takes a "zone" snapshot as soon as it sees a
-- map it did not see last -- but only where the player has a position on it: collectPos answers
-- nil where GetPlayerMapPosition does (an instance), and the ticker skips that tick. A "turnin" or
-- "questlines" snapshot forced for a map no full snapshot has carried yet, while the ticker can see
-- it, is a second full 48 KiB flush right behind the "zone" one, with the same data: walking over
-- a border would cost two. So there the state (the readiness baseline, the cache) is updated and
-- the flush left to the ticker. Still forced where the ticker is blind, and once the map has had
-- its full snapshot: then nothing else would carry the change before the 15 s heartbeat.
-- tickMap is the case fullMap alone gets wrong: back out of an instance onto the map the ticker
-- last saw. That map is not new TO THE TICKER, so no "zone" snapshot comes; waiting for one would
-- leave the change to the heartbeat.
local fullMap = nil     -- the map the last full snapshot carried (collectMap); nil: none
local tickMap = nil     -- the map the ticker last saw WITH a position (collectPos)
local function zoneSnapshotDue(id)
    if id == fullMap or id == tickMap then return false end
    if playerXY(id) then return true end    -- a truth test, as the ticker makes: never a compare
    return false
end

-- AVAILABLE QUESTS. The answers of the last AVAIL_MAPS maps are kept, so walking back into one
-- ships its "!" markers in the very "zone" snapshot that notices the change: no blink, and no
-- second full snapshot when the fresh answer turns out the same. A map not cached ships -1.
local reqMap = nil                  -- the map the last RequestQuestLinesForMap was for
local reqAt = -math.huge            -- and when
local availCache = {}               -- uiMapID -> { n =, list =, key = } (key: the encoded list)
local availOrder = {}               -- the cached uiMapIDs, most recently used first
local heldEmpty = nil               -- the map whose first EMPTY answer is held (see readLines)
local reqN = 0                      -- requests made so far
local evReqN = 0                    -- reqN when the last QUESTLINE_UPDATE ARRIVED (not when read)
local heldReqN = 0                  -- reqN when the empty answer being held was READ

-- Most recently used first; the one past AVAIL_MAPS is dropped with its answer.
local function cacheTouch(id)
    local at
    for i = 1, #availOrder do
        if availOrder[i] == id then at = i break end
    end
    if at == 1 then return end
    if at then table.remove(availOrder, at) end
    table.insert(availOrder, 1, id)
    while #availOrder > AVAIL_MAPS do
        availCache[table.remove(availOrder)] = nil
    end
end

local function cacheStore(id, n, list, key)
    local e = availCache[id]
    if not e then
        e = {}
        availCache[id] = e
    end
    e.n, e.list, e.key = n, list, key
    cacheTouch(id)
end

-- CAPS. Accept / turn-in / abandon, a level-up and a map change ask whenever they happen: the
-- player causes those, a few a minute at most. The other two re-requests are caused by the
-- CLIENT's answers -- a QUESTLINE_UPDATE(requestRequired), and a held empty answer -- and a client
-- that answered every request that way would have this ask about twice a second for ever, each
-- ask buying a read. So, per map: requestRequired re-asks at most once per RR_GAP (one that comes
-- sooner is put off to then, not dropped), a held empty answer at most once per QL_REFRESH (after
-- that the minute's refresh is the re-ask). Both caps start over on accept / turn-in / abandon, a
-- level-up and a map change.
local capMap = nil                          -- the map the caps are counting for
local rrLast, heldLast = -math.huge, -math.huge
local rrOwed = false                        -- a requestRequired re-ask the cap put off

local function capsFor(id)
    if capMap ~= id then capMap, rrLast, heldLast = id, -math.huge, -math.huge end
end
local function resetCaps() capMap = nil end

local function heldMayAsk(id)
    capsFor(id)
    local now = GetTime()
    if now - heldLast < QL_REFRESH then return false end
    heldLast = now
    return true
end

local function requestLines(id)
    if id ~= reqMap then
        heldEmpty = nil                         -- a held empty answer was about the old request
        -- A map change starts the caps over. One already counting for THIS map was counted by
        -- the very re-ask going out now (a requestRequired on a map not noticed yet): kept.
        if capMap ~= id then capMap = nil end
    end
    rrOwed = false                              -- any request answers a requestRequired
    reqMap, reqAt, reqN = id, GetTime(), reqN + 1
    CALL(function() return C_QuestLine.RequestQuestLinesForMap(id) end)
end

-- A new map, or nothing asked for QL_REFRESH: ask. Called by collectMap, which is how a map
-- change is noticed (Core's position ticker takes a "zone" snapshot within 0.1 s of one), and
-- by the zone events below for the places that ticker does not reach.
local function noteMap(id)
    if id ~= reqMap or GetTime() - reqAt >= QL_REFRESH then requestLines(id) end
end

local reqQueued = false
local function requestFromEvent()
    reqQueued = false
    local id = currentMap()
    if id then requestLines(id) end
end
local function requestSoon()
    if reqQueued then return end
    reqQueued = after(QL_EVENT_DELAY, requestFromEvent)
end

-- QUESTLINE_UPDATE(requestRequired): the client dropped what it held and must be asked again (its
-- own world map re-asks here). A request already on its way answers it for free; otherwise one
-- re-ask per RR_GAP per map, and one that comes sooner is owed until then (rrDue, made once: a
-- burst of these arms at most one timer). Any request that goes out meanwhile pays it
-- (requestLines clears rrOwed).
-- rrDue pays WITHOUT measuring the cap again: it was armed for exactly when the cap allows, and a
-- clock read against rrLast + RR_GAP can land a hair short and re-arm, again and again, at no
-- wait. (A reset and a fresh re-ask while it is armed can make it pay early: once per reset.)
local rrQueued = false
local function rrDue()
    rrQueued = false
    if not rrOwed then return end
    rrOwed = false
    rrLast = GetTime()
    requestSoon()
end
local function onRequestRequired()
    if reqQueued then return end
    local id = currentMap()
    if not id then return end
    capsFor(id)
    local wait = rrLast + RR_GAP - GetTime()
    if wait > 0 then
        rrOwed = true
        if not rrQueued then rrQueued = after(wait, rrDue) end
        return
    end
    rrLast = GetTime()
    requestSoon()
end

-- Wire order: id (a row whose id could not be read first), then x, then y. x / y are always
-- plain numbers in 0..1 here (onMap01), and a NaN id never gets this far, so the order is total
-- and table.sort cannot throw "invalid order function".
local function availLess(a, b)
    local ai, bi = a.id, b.id
    if ai ~= bi then
        if ai == nil then return true end
        if bi == nil then return false end
        return ai < bi
    end
    if a.x ~= b.x then return a.x < b.x end
    return a.y < b.y
end

-- QuestLineInfo rows -> raw count, wire rows. Fields go through S() / SN() one by one: a secret
-- field drops that field (or, for x / y, the row), never the line. isHidden is the client's
-- trivial / grey flag; one that cannot be read does not hide the quest.
-- Sorted BEFORE the AVAIL_MAX cut, so which rows make the wire does not depend on order either.
local function buildAvail(lines)
    local n = #lines
    local list = {}
    for i = 1, (n < AVAIL_SCAN and n or AVAIL_SCAN) do
        local info = lines[i]
        if type(info) == "table" and S(function() return info.isHidden end) ~= true then
            local x = SN(function() return info.x end)
            local y = SN(function() return info.y end)
            if onMap01(x, y) then
                local qid = SN(function() return info.questID end)
                if qid ~= qid then qid = nil end            -- NaN: unsortable, and no id anyway
                local e = { id = qid, x = x, y = y }
                local name = S(function() return info.questName end)
                if type(name) == "string" and name ~= "" and name ~= WC.SECRET
                   and name ~= WC.ABSENT and name ~= WC.THREW then
                    e.name = name
                end
                if S(function() return info.isDaily end) == true then e.daily = true end
                if S(function() return info.isCampaign end) == true then e.campaign = true end
                list[#list + 1] = e
            end
        end
    end
    table.sort(list, availLess)
    for i = #list, AVAIL_MAX + 1, -1 do list[i] = nil end
    return n, list
end

-- QUESTLINE_UPDATE carries no map id, so an answer is only taken while the player is still on
-- the map it was asked for. One that lands after they left is dropped, and the map they are on
-- now is asked for instead.
-- AN EMPTY ANSWER IS BELIEVED THE SECOND TIME. The client answers {} for a map it holds nothing
-- for yet, so a stray or early QUESTLINE_UPDATE (the last map's answer landing just after a map
-- change) reads as "no quest lines here". Taken at its word it would blank a cached map's "!"
-- markers, and on a map not answered yet set availN 0, the probe's "Forever's quests are not in
-- quest lines". So an empty answer that would change what is cached is held and the map asked
-- again (see CAPS); the next answer brings the quests, or, empty again, confirms it. "The next"
-- means an answer that can be to a request made AFTER the hold: a second {} before that re-ask
-- went out is just another stray, so it confirms nothing (and asks nothing more). Without that,
-- two strays in a row blanked the markers and the re-ask put them back: two snapshots for nothing.
-- It is judged by when the QUESTLINE_UPDATE ARRIVED (evReqN), not by when it is read: the read
-- waits a frame, and a stray that arrived just BEFORE a request (the minute's refresh, riding the
-- heartbeat in the same frame) is read just after it -- by the read's time, "an answer".
-- ONE snapshot when what the map SHOWS differs from what THIS map last shipped (its cached list;
-- "[]" for a map not cached) -- unless the ticker is about to snapshot this map anyway
-- (zoneSnapshotDue): that snapshot ships the cache. availN alone (a confirmed empty answer, a
-- hidden quest appearing) rides the next heartbeat instead: it is a diagnostic, and a forced
-- snapshot costs a whole 48 KiB flush.
local qlQueued = false
local function readLines()
    qlQueued = false
    local id = currentMap()
    if not id then return end
    if id ~= reqMap then noteMap(id) return end
    local ok, lines = CALL(function() return C_QuestLine.GetAvailableQuestLines(id) end)
    if not ok or type(lines) ~= "table" then return end        -- no usable answer: keep the last
    local okB, n, list = pcall(buildAvail, lines)
    if not okB then return end
    local cached = availCache[id]
    if n == 0 and not (cached and cached.n == 0) then
        if heldEmpty ~= id then
            -- the baseline is the request count at the READ, not at the stray's arrival: a request for
            -- this map can go out between the two, and an older answer for the OTHER map still in flight
            -- would otherwise count as its answer (a border flicker blanked the markers, round-3 review)
            heldEmpty, heldReqN = id, reqN
            -- Ask again, unless a request is on its way already (it goes out after this hold, so
            -- its answer counts) or the cap leaves the re-ask to the minute's refresh.
            if not reqQueued and heldMayAsk(id) then requestSoon() end
            return
        end
        if evReqN <= heldReqN then return end   -- not an answer to a later ask: still held
    end
    heldEmpty = nil
    local key = WC.encode(list)
    local shipped = cached and cached.key or "[]"
    cacheStore(id, n, list, key)
    if key ~= shipped and not zoneSnapshotDue(id) then snapshotSoon("questlines") end
end

-- The callback is made once, here: a QUESTLINE_UPDATE burst allocates nothing and arms one timer.
-- Every event notes how many requests had gone out when it ARRIVED (see readLines): one upvalue
-- write. A burst read once carries its last event's count: if any came after a request, that did.
local function onQuestLines()
    evReqN = reqN
    if qlQueued then return end
    qlQueued = after(0, readLines)
end

-- TURN-INS. What the last full snapshot (or check) carried: which quests on which map were
-- ready, as a count, a sum and a sum of squares of their ids -- order-free and allocation-free.
local rdy = { map = nil, n = 0, sum = 0, sq = 0 }

local function readySignature(id)
    local n, sum, sq = 0, 0, 0
    local ok, qs = CALL(function() return C_QuestLog.GetQuestsOnMap(id) end)
    if ok and type(qs) == "table" then
        for _, q in ipairs(qs) do
            local qid = SN(function() return q.questID end)
            if qid and readyForTurnIn(qid) then
                n, sum, sq = n + 1, sum + qid, sq + qid * qid
            end
        end
    end
    return n, sum, sq
end

-- A quest becoming ready changes nothing on the action bar, so without this it would wait for
-- the 15 s heartbeat. The callbacks are made once, here: a QUEST_LOG_UPDATE storm allocates
-- nothing, and arms at most one timer per READY_GAP. The first event after a quiet spell is
-- checked on the next frame, so a turn-in shows up promptly.
local rdyQueued, rdyAt = false, -math.huge
local function checkReady()
    rdyQueued = false
    rdyAt = GetTime()
    local id = currentMap()
    if not id then return end
    local ok, n, sum, sq = pcall(readySignature, id)
    if not ok then return end
    if id == rdy.map and n == rdy.n and sum == rdy.sum and sq == rdy.sq then return end
    local sameMap = (id == rdy.map)
    rdy.map, rdy.n, rdy.sum, rdy.sq = id, n, sum, sq
    -- A MAP CHANGE this check saw before any snapshot of the new map did. With nothing ready on
    -- it there is no "?" to draw that the ticker's "zone" snapshot (or, where the ticker is
    -- blind, the heartbeat) will not carry anyway: adopt it as the baseline and flush nothing.
    if not sameMap and n == 0 then return end
    -- A map the ticker is about to snapshot: its "zone" snapshot carries these "?" (collectMap
    -- reads them again), so forcing one here would flush the same 48 KiB twice. The baseline is
    -- adopted all the same.
    if zoneSnapshotDue(id) then return end
    snapshotSoon("turnin")
end
local function onQuestLog()
    if rdyQueued then return end
    local wait = READY_GAP - (GetTime() - rdyAt)
    rdyQueued = after(wait > 0 and wait or 0, checkReady)
end

-- ---- WHO IS ASKING (map.me). The "!" layer comes from the app's bundled quest database, because
-- C_QuestLine.GetAvailableQuestLines answers 0 on this client (59 of 59 snapshots, 2026-09-28) and
-- the game exposes no "this NPC has a quest for you". The database needs to know who is asking:
-- level, race, class, what is in the quest log and what is already done. PROVEN on the device
-- 2026-09-28: IsQuestFlaggedCompleted / IsOnQuest / GetAllCompletedQuestIDs return real data.
--   me = { lvl, race, cls, g = the character's GUID, on = {ids}, done = {ids}? }
-- `done` can run to thousands of ids, so it is read and shipped only when it may have changed
-- (login, a turn-in) and otherwise every DONE_REFRESH seconds, so an app that started late still
-- gets it. The app keeps the last list it saw. `on` is at most 25 ids and rides every full snapshot.
local DONE_REFRESH = 300
local doneDirty, doneAt = true, -1e9
local function collectMe()
    local me = {
        lvl = SN(function() return UnitLevel("player") end),
        race = SN(function() return (select(3, UnitRace("player"))) end),
        cls = SN(function() return (select(3, UnitClass("player"))) end),
        -- the character (unique per character and realm): the app keeps the completed list PER CHARACTER by it (Quests' pick,
        -- 2026-10-07: a new character ran on the old one's list and lost its starting quests' "!")
        g = S(function() return UnitGUID("player") end),
    }
    local on = {}
    pcall(function()
        for i = 1, 60 do
            local q = C_QuestLog.GetQuestIDForLogIndex(i)
            if q == nil then break end
            if WC.isPlain(q) and type(q) == "number" and q > 0 then on[#on + 1] = q end
        end
    end)
    me.on = on
    local now = SN(function() return GetTime() end) or 0
    if doneDirty or now - doneAt >= DONE_REFRESH then
        local ok, t = pcall(function() return C_QuestLog.GetAllCompletedQuestIDs() end)
        if ok and type(t) == "table" then
            local done = {}
            for i = 1, #t do
                local q = t[i]
                if WC.isPlain(q) and type(q) == "number" then done[#done + 1] = q end
            end
            me.done = done
            doneDirty, doneAt = false, now
        end
    end
    return me
end
function WC.questDoneDirty() doneDirty = true end

-- ---------------------------------------------------------------- the quest-area tracer
-- Forever's OWN quests (ids 90000 and up) are in no offline source for their objective areas: the
-- server sends the shape to the client and no call hands it over. But the client will say whether
-- the player is INSIDE it: C_Minimap.IsInsideQuestBlob(questID), a plain boolean (minimap docs; the
-- game's own objective tracker asks it). Sampled with the player's position while walking, the
-- answers trace the real area from the user's own play (user 2026-09-29 14:40: "that sounds really
-- really good actually"); the app keeps the traced shape. Budget: at most one sample every
-- TRACE_EVERY s and only after TRACE_STEP yd of walking, only for Forever quests on THIS map that
-- are not ready to hand in, never in combat. Read-only: one getter per quest per sample.
local TRACE_MIN_ID = 90000       -- the classic quests ship with baked shapes (the app's quest_areas.json)
local TRACE_EVERY = 0.5          -- s between samples at least
local TRACE_STEP = 4             -- yd walked between samples at least
local TRACE_MAX = 25             -- quests asked per sample at most
local traceIds, traceMap = {}, nil
local traceAt, traceX, traceY = -1e9, nil, nil

-- collectMap hands over this map's quests (plain ids only)
local function traceNote(mapId, rows)
    local ids = {}
    for _, r in ipairs(rows) do
        local q = r.questID
        if type(q) == "number" and q >= TRACE_MIN_ID and not r.done and #ids < TRACE_MAX then
            ids[#ids + 1] = q
        end
    end
    traceIds, traceMap = ids, mapId
end

-- one sample, or nil: { {questID, 1|0}, ... } for the position update that carries wx / wy
local function traceSample(mapId, wx, wy)
    if not mapId or mapId ~= traceMap or #traceIds == 0 then return nil end
    if type(wx) ~= "number" or type(wy) ~= "number" then return nil end
    if InCombatLockdown() then return nil end
    local okc, fighting = CALL(UnitAffectingCombat, "player")
    if okc and fighting == true then return nil end
    local now = SN(GetTime) or 0
    if now - traceAt < TRACE_EVERY then return nil end
    if traceX and (wx - traceX) ^ 2 + (wy - traceY) ^ 2 < TRACE_STEP * TRACE_STEP then return nil end
    local isIn = C_Minimap and C_Minimap.IsInsideQuestBlob
    if type(isIn) ~= "function" then return nil end
    local out = {}
    for i = 1, #traceIds do
        local ok, v = CALL(isIn, traceIds[i])
        if ok and type(v) == "boolean" and WC.isPlain(v) then out[#out + 1] = { traceIds[i], v and 1 or 0 } end
    end
    traceAt, traceX, traceY = now, wx, wy
    if #out > 0 then return out end
end
WC.traceSample = traceSample          -- for the test harness only

function WC.collectMap()
    local id = mapID()
    if not id then
        fullMap = nil                   -- this snapshot carried no map (see zoneSnapshotDue)
        return { ok = false }
    end

    local x, y = playerXY(id)
    local out = {
        mapID = id,
        x = x, y = y,
        facing = SN(GetPlayerFacing),          -- radians; the radar rotates with this
        zone = S(GetZoneText),
        subzone = S(GetSubZoneText),
        viewRadius = SN(function() return C_Minimap.GetViewRadius() end),
        superTracked = superTrackedQuest(),
        me = collectMe(),
        minimapHidden = (hiddenFrame ~= nil),
        quests = {}, pois = {}, taxi = {}, vignettes = {},
    }
    deathFields(plainNum(id) or id, out)        -- THE CORPSE: ghost, corpse {x, y, src}, minimapPeek
    -- indoors rides on the POSITION tick too, but that tick emits only when the position key
    -- changes -- so a player standing still inside a building never sends it, and the app,
    -- which had only the heartbeat snapshot to go on, framed as if they were outdoors. Put it
    -- on the full snapshot as well so standing still is not a blind spot.
    local okI2, ind2 = CALL(IsIndoors)
    if okI2 then out.indoors = ind2 and true or false end
    local okN2, inst2 = CALL(IsInInstance)
    if okN2 then out.inst = inst2 and true or false end

    -- THE ZONE RECT, and the player's WORLD position.
    -- This is what lets the app place the bundled service-POI dataset, which ships WORLD
    -- coordinates. Five numbers replace a 3.3.5a-areaId-to-uiMapID mapping table that does not
    -- exist anywhere on this machine: a POI belongs on this map exactly when its world position
    -- is inside this map's world rect, so containment IS the zone test.
    -- Read from the LIVE client, so it is correct for whatever map art this build ships -- an
    -- offline-baked rect would silently drift if Blizzard ever re-cut the art.
    local rect = WC.zoneRect(id)
    if rect then
        out.rect = { c = rect.continent, t = rect.top, b = rect.bottom,
                     l = rect.left, r = rect.right }
    end
    -- World position too: distances belong in world space. Zone maps are exactly 3:2, so a
    -- distance measured in 0..1 is wrong by up to 50% depending on bearing, and a range ring
    -- drawn there is an ellipse on the ground.
    local okw, wx, wy, wz, wcont = CALL(UnitPosition, "player")
    if okw then
        out.cont = (type(wcont) == "number" and WC.isPlain(wcont)) and wcont or nil     -- the continent (UnitPosition's instance id)
        out.wx = (type(wx) == "number" and WC.isPlain(wx)) and wx or nil
        out.wy = (type(wy) == "number" and WC.isPlain(wy)) and wy or nil
        out.wz = (type(wz) == "number" and WC.isPlain(wz)) and wz or nil
    end
    -- on a flight (b-route-city-exits): a map crossed in the air is no way out on foot, so the app learns no exit from it
    local okt, onTaxi = CALL(UnitOnTaxi, "player")
    out.onTaxi = (okt and onTaxi == true) and 1 or 0     -- NOT `taxi`: that key is this document's list of flight nodes
    -- Services the player has actually interacted with here. Authoritative, unlike the bundle.
    out.svc = WC.collectServices and WC.collectServices(id) or nil

    -- THE SELF TEST, carried on every full snapshot.
    -- Round-trips the player's own UnitPosition through the rect and compares against the
    -- client's own GetPlayerMapPosition. It catches a mirrored or transposed map the moment the
    -- player enters the zone, which eyeballing a landmark cannot: Innkeeper Farley transposed is
    -- 65.8/43.8 instead of 43.8/65.8 and is still inside Elwynn, still next to a road.
    -- The app SUPPRESSES the bundled layer when this fails, because a displaced pin that looks
    -- confident is worse than no pin.
    if WC.checkCoords then
        local chk = WC.checkCoords()
        if chk then
            out.chk = { ok = chk.ok and 1 or 0,
                        e = type(chk.errYards) == "number" and math.floor(chk.errYards + 0.5) or nil,
                        why = chk.why }
        end
    end

    -- quest objectives on this map, with live distance
    local plainId = plainNum(id)
    local rn, rsum, rsq = 0, 0, 0
    local okq, qs = CALL(function() return C_QuestLog.GetQuestsOnMap(id) end)
    if okq and type(qs) == "table" then
        for _, q in ipairs(qs) do
            local qid = SN(function() return q.questID end)
            local row = {
                questID = qid,
                x = SN(function() return q.x end),
                y = SN(function() return q.y end),
            }
            if qid then
                row.title = S(function() return C_QuestLog.GetTitleForQuestID(qid) end)
                local okd, d = CALL(function() return C_QuestLog.GetDistanceSqToQuest(qid) end)
                if okd and type(d) == "number" then row.dist = math.floor(math.sqrt(d)) end
                -- READY TO HAND IN: done, and where on THIS map. The waypoint is asked only for
                -- a ready quest, and kept only when it is on the map (0..1); else the app uses x/y.
                if readyForTurnIn(qid) then
                    row.done = true
                    rn, rsum, rsq = rn + 1, rsum + qid, rsq + qid * qid
                    local okw, wx, wy = CALL(function()
                        return C_QuestLog.GetNextWaypointForMap(qid, id)
                    end)
                    if okw then
                        wx, wy = plainNum(wx), plainNum(wy)
                        if onMap01(wx, wy) then row.tx, row.ty = wx, wy end
                    end
                end
            end
            out.quests[#out.quests + 1] = row
        end
    end
    -- THE FOCUSED QUEST'S WAY when it is not on this map (the user, 2026-09-29: "the focused quest
    -- arrow on the mini map"). On this map the app points at the quest's own row; off it, the
    -- game's next waypoint FOR this map (a road out, a portal) is what its own minimap arrow
    -- follows. Kept even outside 0..1: an arrow needs only the direction.
    if out.superTracked and out.superTracked > 0 and plainId then
        local st, onThis = out.superTracked, false
        for _, r in ipairs(out.quests) do if r.questID == st then onThis = true; break end end
        if not onThis then
            local okw, wx, wy = CALL(function() return C_QuestLog.GetNextWaypointForMap(st, id) end)
            if okw then
                wx, wy = plainNum(wx), plainNum(wy)
                if wx and wy then out.stWay = { x = wx, y = wy } end
            end
        end
    end
    -- QuestTrail's planned path, when its API gives one (b-route-questtrail; the arrow itself rides every position tick)
    local okQP, qpath = pcall(WC.qtrailPath)
    if okQP and qpath then out.qtrailPath = qpath end
    -- THE MAP PIN (the Map room's waypoint, 2026-10-07): the game's own user waypoint, wherever it was placed (the game's map,
    -- the Map room), so the room and Home draw the pin the game holds. uwp = {m = its map, x, y, st = 1 while super-tracked}.
    local okU, wp = CALL(function() return C_Map.GetUserWaypoint() end)
    if okU and type(wp) == "table" then
        local okP, wm, wx, wy = CALL(function() return wp.uiMapID, wp.position:GetXY() end)
        wm, wx, wy = plainNum(okP and wm or nil), plainNum(wx), plainNum(wy)
        if wm and wx and wy then
            out.uwp = { m = wm, x = wx, y = wy }
            if WC.routeWp and WC.routeWp.active then out.uwp.ours = 1 end      -- the route's pin, not the player's own
            local okS, st = CALL(function() return C_SuperTrack.IsSuperTrackingUserWaypoint() end)
            if okS and type(st) == "boolean" and WC.isPlain(st) and st then out.uwp.st = 1 end   -- proven plain first
        end
    end
    if plainId then traceNote(plainId, out.quests) end   -- the quest-area tracer's list
    -- This snapshot now carries this readiness, so the QUEST_LOG_UPDATE check need not.
    if plainId then rdy.map, rdy.n, rdy.sum, rdy.sq = plainId, rn, rsum, rsq end

    -- AVAILABLE quests: whatever the cache holds for THIS map, shipped at once (so the "zone"
    -- snapshot of a map walked back into already has its markers). Seeing a new map (or a stale
    -- answer) still asks; the answer arrives as QUESTLINE_UPDATE and forces its own snapshot
    -- only when it differs from what this map last shipped.
    out.availN = -1
    if plainId then
        noteMap(plainId)
        local cached = availCache[plainId]
        if cached then
            cacheTouch(plainId)
            out.availN = cached.n
            if #cached.list > 0 then out.avail = cached.list end
        end
    end

    -- area points of interest
    local okp, ps = CALL(function() return C_AreaPoiInfo.GetAreaPOIForMap(id) end)
    if okp and type(ps) == "table" then
        for _, poiID in ipairs(ps) do
            local oki, info = CALL(function() return C_AreaPoiInfo.GetAreaPOIInfo(id, poiID) end)
            if oki and type(info) == "table" then
                out.pois[#out.pois + 1] = {
                    id = SN(function() return poiID end),
                    name = S(function() return info.name end),
                    x = SN(function() return info.position and info.position.x end),
                    y = SN(function() return info.position and info.position.y end),
                    atlas = S(function() return info.atlasName end),
                }
            end
        end
    end

    -- Flight points.
    -- MEASURED: GetTaxiNodesForMap returns CONTINENT-WIDE nodes, not just this map's. In
    -- Elwynn Forest it returned ~40 nodes spanning all of the Eastern Kingdoms, with
    -- coordinates well outside 0..1 (y = -4.41, x = 1.98) -- which bloated one snapshot to
    -- five fragments. Only nodes that actually fall on THIS map belong on the radar.
    local okt, ts = CALL(function() return C_TaxiMap.GetTaxiNodesForMap(id) end)
    if okt and type(ts) == "table" then
        -- THE KNOWN FLIGHT POINTS of the whole continent, in THIS map's coordinates (outside 0..1 for the other zones' nodes), for
        -- the app's flight suggestion (the user, 2026-10-07: "if the quest was far or another zone ... a marker to a flight master";
        -- their picks: the edge arrow wears the step's icon, a flight only when clearly faster). Known = isUndiscovered is a plain
        -- false (MapTaxiNodeInfo on this build; the rows carry no state); the player's own faction or neutral when it can tell.
        -- -> fly = {{x, y}, ...} (4 decimals), at most 60; a node with a secret or missing field is left out.
        local okf, fg = CALL(function() return UnitFactionGroup("player") end)
        local mine = (okf and type(fg) == "string" and WC.isPlain(fg)) and ({ Alliance = 2, Horde = 1 })[fg] or nil
        out.fly = {}
        for _, n in ipairs(ts) do
            local fx = SN(function() return n.position and n.position.x end)
            local fy = SN(function() return n.position and n.position.y end)
            local oku, und = CALL(function() return n.isUndiscovered end)
            local fac = SN(function() return n.faction end)
            if fx and fy and oku and type(und) == "boolean" and WC.isPlain(und) and und == false
                and (mine == nil or fac == nil or fac == 0 or fac == mine) and #out.fly < 60 then
                -- id: the node's own id (b-route-unknown-taxi: the app flies only between nodes a flight master showed connected)
                -- name: the node's own words, for the cursor line's "Flight to <name>" (the agreed route design, 2026-10-16)
                out.fly[#out.fly + 1] = { x = math.floor(fx * 10000 + 0.5) / 10000, y = math.floor(fy * 10000 + 0.5) / 10000,
                                          id = SN(function() return n.nodeID end),
                                          name = (function() local v = S(function() return n.name end)
                                              if type(v) == "string" and v ~= WC.ABSENT and v ~= WC.THREW then return v end end)() }
            end
        end
        for _, n in ipairs(ts) do
            local nx = SN(function() return n.position and n.position.x end)
            local ny = SN(function() return n.position and n.position.y end)
            if nx and ny and nx >= 0 and nx <= 1 and ny >= 0 and ny <= 1 then
                out.taxi[#out.taxi + 1] = {
                    name = S(function() return n.name end),
                    x = nx, y = ny,
                    state = SN(function() return n.state end),
                    -- probe (b-route-transports, 2026-10-16): does the game mark a boat / zeppelin / tram node? (read only)
                    kit = WC.plainStr(S(function() return n.textureKit end)),   -- absent: left out (test20 sent the absent marker)
                    sp = SN(function() return n.useSpecialIcon and 1 or 0 end),
                }
            end
        end
    end

    -- vignettes: rares, treasure, events
    local okv, vs = CALL(function() return C_VignetteInfo.GetVignettes() end)
    if okv and type(vs) == "table" then
        for _, guid in ipairs(vs) do
            local oki, info = CALL(function() return C_VignetteInfo.GetVignetteInfo(guid) end)
            local okp2, pos = CALL(function() return C_VignetteInfo.GetVignettePosition(guid, id) end)
            local row = { name = oki and type(info) == "table" and S(function() return info.name end) or nil }
            if okp2 and pos then
                row.x = SN(function() return pos.x end)
                row.y = SN(function() return pos.y end)
            end
            out.vignettes[#out.vignettes + 1] = row
        end
    end

    -- probe (b-route-transports; the user, 2026-10-16: "Read them from the game"): the map's links to other maps
    -- (C_Map.GetMapLinksForMap, in this client's binary; boats / zeppelins / the tram are expected here if anywhere).
    -- Reads only; at most 20 rows; a missing API leaves links absent.
    local okl, ls = CALL(function() return C_Map.GetMapLinksForMap(id) end)
    if okl and type(ls) == "table" then
        out.links = {}
        for _, l in ipairs(ls) do
            if #out.links >= 20 then break end
            out.links[#out.links + 1] = {
                name = WC.plainStr(S(function() return l.name end)),
                atlas = WC.plainStr(S(function() return l.atlasName end)),
                to = SN(function() return l.linkedUiMapID end),
                x = SN(function() return l.position and l.position.x end),
                y = SN(function() return l.position and l.position.y end),
            }
        end
    end

    -- DUNGEON AND RAID ENTRANCES on this map, the game's own (b-dungeon-entrances; the user, 2026-10-17, from OpenMap's list: the
    -- game files are the source): C_Map.GetDungeonEntrancesForMap, read only. dent = {{n, x, y, j}} (j the journal instance), <= 30
    local okD, ents = CALL(function() return C_Map.GetDungeonEntrancesForMap(id) end)
    if okD and type(ents) == "table" then
        out.dent = {}
        for _, e in ipairs(ents) do
            if #out.dent >= 30 then break end
            local ex = SN(function() return e.position and e.position.x end)
            local ey = SN(function() return e.position and e.position.y end)
            if ex and ey then
                out.dent[#out.dent + 1] = { n = WC.plainStr(S(function() return e.name end)), x = math.floor(ex * 10000 + 0.5) / 10000,
                                            y = math.floor(ey * 10000 + 0.5) / 10000, j = SN(function() return e.journalInstanceID end) }
            end
        end
    end

    out.track = trackingState()          -- Find Herbs / Minerals / Treasure on or off (see trackingState)
    -- the hidden minimap at the last re-assert (WC.mmHide; absent = shown): the mask and the icon scale taken (1 / 0), the frame
    if hiddenFrame and not peek then
        out.mmk, out.mmi, out.mmf, out.mmg = WC.mmHide.k and 1 or 0, WC.mmHide.i and 1 or 0, hiddenFrame, WC.mmHide.g
    end

    out.ok = true
    fullMap = plainId                   -- the map this snapshot carries (see zoneSnapshotDue)
    return out
end

-- ---------------------------------------------------------------- the object beside the player
-- The user's pick (2026-09-29 13:58): the minimap's ore / herb / chest dots cannot be read (they
-- are drawn by the engine, measured with the minimap probe), so the map marks the node the player
-- is STANDING NEXT TO. The game names the object in front of the player: its "soft interact"
-- target, unit token "softinteract". This build has it (unitdocumentation: UnitTokenType
-- SoftInteract, event PLAYER_SOFT_INTERACT_CHANGED) and the Controller UI turns it on by itself:
-- Blizzard_Gamepad sets the temp CVars SoftTargetInteract 1, SoftTargetInteractRange 20 and
-- SoftTargetIconGameObject 1 whenever the gamepad is the input. With keyboard and mouse it is
-- Options > Controls > "Enable Interact Key". THIS FILE NEVER SETS A CVAR.
-- Read-only: Unit* getters, plus SetUnitCursorTexture on a texture of our own -- the very call
-- the Controller UI makes to choose its Interact button's icon. The icon it picks IS the kind:
-- the mining pick, the herb glove, the loot bag... so no database says what a node is.
-- Position: the game gives none for an object (UnitPosition answers for group members; it is
-- tried and reported anyway). "In interact range" means the player is beside it, so the app
-- puts the mark where the player stands.
-- Identity rules (secretpredicates): name and GUID go secret when "the unit isn't player-
-- controlled or in the party/raid" -- MEASURED plain for a creature target out of combat on
-- 2026-09-25, unmeasured for an object. Every value passes S()/isPlain; a secret is dropped and
-- flagged (sec = true), never sent.
local CURSOR_KIND = {
    mine = "ore", gatherherbs = "herb", lootall = "loot", pickup = "loot", picklock = "lock",
    questinteract = "quest", interact = "use", fishing = "fish", fishingcursor = "fish",
    skin = "skin", skinalliance = "skin", skinhorde = "skin", engineerskin = "skin",
}
-- the same files by fileDataID (GetTexture answers with an id once a texture is set by id)
local CURSOR_FID = {
    [131025] = "mine", [131052] = "unablemine", [131018] = "gatherherbs", [131045] = "unablegatherherbs",
    [131023] = "lootall", [131050] = "unablelootall", [131027] = "pickup", [131054] = "unablepickup",
    [131026] = "picklock", [131053] = "unablepicklock", [1006596] = "questinteract",
    [1006597] = "unablequestinteract", [131021] = "interact", [131048] = "unableinteract",
    [303902] = "fishing", [303903] = "unablefishing", [252300] = "fishingcursor",
    [131035] = "skin", [131062] = "unableskin", [131036] = "skinalliance", [131063] = "unableskinalliance",
    [131037] = "skinhorde", [131064] = "unableskinhorde", [235498] = "engineerskin",
    [235501] = "unableengineerskin",
}

local softTex                           -- our own texture, never shown: SetUnitCursorTexture's target
local function softTexture()
    if softTex == nil then
        softTex = false
        local okF, f = CALL(CreateFrame, "Frame")
        if okF and f then
            local okT, t = CALL(function() return f:CreateTexture() end)
            if okT and t then softTex = t end
        end
    end
    return softTex or nil
end

-- a plain boolean or nil (a secret boolean must never reach an `if`)
local function plainBool(fn, ...)
    local ok, v = CALL(fn, ...)
    if ok and type(v) == "boolean" and WC.isPlain(v) then return v end
end
-- a plain non-sentinel string or nil, and whether it was secret
local function plainStr(fn, ...)
    local v = S(fn, ...)
    if v == WC.SECRET then return nil, true end
    if type(v) ~= "string" or v == WC.ABSENT or v == WC.THREW or v == "" then return nil end
    return v
end

-- the cursor the game would show for the soft-interact target -> "mine", "unablegatherherbs"...
local function softCursor()
    local t = softTexture()
    if not t then return nil end
    local ok, has = CALL(SetUnitCursorTexture, t, "softinteract", nil, nil, false)
    if not ok or has ~= true then return nil end
    local v = S(function() return t:GetTexture() end)
    if type(v) == "number" then return CURSOR_FID[v] or ("fid" .. v) end
    if type(v) == "string" and v ~= WC.SECRET and v ~= WC.ABSENT and v ~= WC.THREW then
        return (v:lower():gsub("\\", "/"):match("([^/]+)$") or v):gsub("%.blp$", "")
    end
end

-- The soft-interact target, or nil when there is none. Called from collectPos (10 Hz).
function WC.collectSoft()
    local exists = plainBool(UnitExists, "softinteract")
    local isGO = plainBool(UnitIsGameObject, "softinteract")
    if not exists and not isGO then return nil end
    local out = { go = isGO and true or false }
    local guid, gSec = plainStr(UnitGUID, "softinteract")
    if guid then
        local kind, entry, spawn = guid:match("^(%a+)%-%d+%-%d+%-%d+%-%d+%-(%d+)%-(%x+)$")
        out.t, out.e, out.s = kind, tonumber(entry), spawn
    end
    local name, nSec = plainStr(UnitName, "softinteract")
    out.n = name
    if gSec or nSec then out.sec = true end
    local cur = softCursor()
    if cur then
        out.cur = cur
        local base = cur:gsub("^unable", "")
        out.k = CURSOR_KIND[base]
        if base ~= cur then out.u = true end        -- the game's "Unable..." icon: too far, or no skill
    end
    out.r = plainBool(UnitIsInInteractRange, "softinteract")
    out.l = plainBool(UnitHasLootInteraction, "softinteract")
    local okw, wx, wy = CALL(UnitPosition, "softinteract")
    if okw and type(wx) == "number" and type(wy) == "number" and WC.isPlain(wx) and WC.isPlain(wy) then
        out.wx, out.wy = wx, wy
    end
    return out
end

-- what the movement ticker compares, so a new target or a step into range is sent while standing
function WC.softKey(s)
    if not s then return "" end
    return tostring(s.s or s.n or "?") .. "|" .. tostring(s.cur) .. "|" .. tostring(s.r)
end

-- Cheap position-only payload for the movement ticker. Deliberately does NOT re-walk quests,
-- POIs or taxi nodes: those change rarely, and re-sending them at 4 Hz would be most of the
-- byte budget for no benefit.
function WC.collectPos()
    local id = mapID()
    if not id then return nil end
    local x, y = playerXY(id)
    if not x then return nil end
    tickMap = plainNum(id)              -- the ticker takes this tick: it has seen this map
    local out = {
        mapID = id, x = x, y = y,
        facing = SN(GetPlayerFacing),
        zone = S(GetZoneText),
        subzone = S(GetSubZoneText),
    }
    -- the object beside the player (see collectSoft); pcall'd so it can never cost the position
    local okS, soft = pcall(WC.collectSoft)
    if okS and soft then out.soft = soft end
    -- INDOORS drives the app's auto-framing: inside a cave or a building the useful view is a
    -- tight local one, not the whole zone. Both calls are present in this build's binary.
    -- IsIndoors is the client's own roof test, the same one that governs mounting, so it is
    -- true in buildings AND in most caves; it is cheap enough for the 0.1 s ticker.
    local okI, ind = CALL(IsIndoors)
    if okI then out.indoors = ind and true or false end
    local okN, inInst = CALL(IsInInstance)
    if okN then out.inst = inInst and true or false end
    -- World position on the cheap update too. The app needs it to measure distance to bundled
    -- POIs in YARDS; it can invert the normalised position through the rect instead, but sending
    -- the real numbers is exact and, per Sink.lua, extra bytes make the push FASTER not slower.
    -- wz (height) too: indoors it is the ONLY thing that says which FLOOR you are on, and a
    -- two-storey building drawn on the wrong floor is worse than no floorplan at all.
    local okw, wx, wy, wz, wcont = CALL(UnitPosition, "player")
    if okw then
        if type(wx) == "number" and WC.isPlain(wx) then out.wx = wx end
        if type(wy) == "number" and WC.isPlain(wy) then out.wy = wy end
        if type(wcont) == "number" and WC.isPlain(wcont) then out.cont = wcont end
    end
    -- the learner's two other words on the cheap tick too (b-learn-exits): on a flight, and (above) the continent
    local okt2, onTaxi2 = CALL(UnitOnTaxi, "player")
    out.onTaxi = (okt2 and onTaxi2 == true) and 1 or 0   -- NOT `taxi` (the full document's flight-node list)
    -- the quest-area tracer rides the position it was taken at (see traceSample)
    local okQ, qb = pcall(traceSample, plainNum(id), out.wx, out.wy)
    if okQ and qb then out.qb = qb end
    -- QuestTrail's arrow, read (b-route-questtrail; absent = QuestTrail not there or its arrow hidden)
    local okT, qt = pcall(WC.qtrailRead)
    if okT and qt then out.qtrail = qt end
    -- NO HEIGHT IS AVAILABLE. Measured: UnitPosition returns 4 values here and the third
    -- (posZ) is 0 while the player stands ~56 yd up -- Blizzard zeroed it years ago. There is
    -- no other Lua source for player height, so the app CANNOT pick a floor by altitude; it
    -- matches the building footprint and lets the player cycle floors.
    return out
end

-- ---------------------------------------------------------------- the map-menu probe (read-only, once a session)
-- The user, 2026-10-07: "We also really need to make the actual in game map menu for the map room." Before the room mirrors the
-- game's world map window, this says what THIS client's map window really has. It only READS: which of the game's "Show:" map
-- filters exist here (their CVars, read, never set), the game rules that switch the pin / filter / maximize buttons off, whether the
-- window's parts and its open / close buttons exist (Interact: the game's controller radial opens the map with QuestLogMicroButton;
-- the close is WorldMapFrame.BorderFrame.CloseButton), and whether a waypoint may be set on the player's map. Nothing is shown,
-- hidden, clicked or called on a Blizzard frame. One {"type":"mapmenuprobe"} ~10 s after the first world entry of a session.
local MAPMENU_CVARS = { "questPOI", "showQuestLevel", "showQuestDifficultyColor", "showDungeonEntrancesOnMap", "trivialQuests",
    "contentTrackingFilter", "digSites", "showTamers", "showDelveEntrancesOnMap", "questPOIWQ", "questPOILocalStory",
    "showAccountCompletedQuests", "dragonRidingRacesFilter" }
local MAPMENU_RULES = { "WorldMapTrackingPinDisabled", "WorldMapTrackingOptionsDisabled", "MaximizeWorldMapDisabled" }

local function plainFlag(ok, v)        -- 1 / 0 for a plain boolean, -1 for anything else (missing, secret, an error)
    if ok and type(v) == "boolean" and WC.isPlain(v) then return v and 1 or 0 end
    return -1
end

local function hasField(t, ...)       -- does t.a.b... exist? (a read only: indexing a Blizzard table taints nothing)
    local keys = { ... }                -- Lua 5.1: the varargs cannot be used inside the closure below
    local ok, v = CALL(function()
        local cur = t
        for _, k in ipairs(keys) do
            if type(cur) ~= "table" then return nil end
            cur = cur[k]
        end
        return cur
    end)
    return (ok and v ~= nil) and 1 or 0
end

local function mapMenuProbe()
    local out = { type = "mapmenuprobe", v = "mm1", cvars = {}, rules = {} }
    for _, name in ipairs(MAPMENU_CVARS) do
        local ok, info = CALL(function() return C_CVar.GetCVarInfo(name) end)
        if ok and info ~= nil then out.cvars[name] = plainFlag(CALL(function() return C_CVar.GetCVarBool(name) end)) end
    end
    for _, r in ipairs(MAPMENU_RULES) do
        out.rules[r] = plainFlag(CALL(function() return C_GameRules.IsGameRuleActive(Enum.GameRule[r]) end))
    end
    local wm = _G.WorldMapFrame
    out.frames = {
        worldMap = wm ~= nil and 1 or 0,
        navBar = hasField(wm, "NavBar"),
        pinButton = hasField(wm, "WorldMapTrackingPinButton"),
        filterButton = hasField(wm, "WorldMapTrackingOptionsButton"),
        sidePanelToggle = hasField(wm, "SidePanelToggle"),
        closeButton = hasField(wm, "BorderFrame", "CloseButton"),
        questLogMicroButton = _G.QuestLogMicroButton ~= nil and 1 or 0,
        miniMapWorldMapButton = _G.MiniMapWorldMapButton ~= nil and 1 or 0,
    }
    out.maximized = plainFlag(CALL(function() return wm and wm.isMaximized end))
    local id = currentMap()
    if id then
        out.mapID = id
        out.waypointHere = plainFlag(CALL(function() return C_Map.CanSetUserWaypointOnMap(id) end))
    end
    return out
end
WC.mapMenuProbe = mapMenuProbe

local mapMenuProbed = false
local function mapMenuProbeOnce()
    if mapMenuProbed then return end
    mapMenuProbed = true
    CALL(function()
        C_Timer.After(10, function() pcall(function() WC.send(WC.encode(mapMenuProbe())) end) end)
    end)
end

-- ---------------------------------------------------------------- the Map room's world map: exploration, zone rects, levels
-- The user, 2026-10-07: the Map room works like the game's own world map, on "the normal zone maps", and unexplored areas
-- "Like the game: only what I've explored". The app holds the painted art (a base parchment + one picture per WorldMapOverlay)
-- and draws an overlay only when THIS character has explored it: C_MapExplorationInfo.GetExploredMapTextures(map) answers
-- with the explored overlays' tile fileDataIDs, and the app matches an overlay by its FIRST tile fdid. For the continent view
-- the doc also carries each map's rect on its parent (C_Map.GetMapRectOnMap) and its level range (C_Map.GetMapLevels).
-- {"type":"mapexpl", all = true, maps = {[id] = {e = {fid...}, r = {minX, maxX, minY, maxY}, p = parent, lv = {min, max}}}}
-- once a session ~15 s after the first world entry (MAPEXPL_PER_TICK maps a tick, so no frame does the whole sweep), and
-- {all = false, maps = {[current] = ...}} again on MAP_EXPLORATION_UPDATED. Reads only; no Blizzard frame is touched.
local MAPEXPL_MAPS = { 947, 1411, 1412, 1413, 1414, 1415, 1416, 1417, 1418, 1419, 1420, 1421, 1422, 1423, 1424, 1425, 1426,
    1427, 1428, 1429, 1430, 1431, 1432, 1433, 1434, 1435, 1436, 1437, 1438, 1439, 1440, 1441, 1442, 1443, 1444, 1445, 1446, 1447,
    1448, 1449, 1450, 1451, 1452, 1453, 1454, 1455, 1456, 1457, 1458, 1459, 1460, 1461, 1463, 1464, 2482, 2521, 2524, 2548, 2652,
    2665 }        -- the maps the app's art set has (60; built from the client's own map art)
local MAPEXPL_PER_TICK = 10

local function mapExplOne(id)
    local row = {}
    local okE, expl = CALL(function() return C_MapExplorationInfo.GetExploredMapTextures(id) end)
    if okE and type(expl) == "table" then
        local e = {}
        for i = 1, #expl do
            local okF, first = CALL(function() return expl[i].fileDataIDs[1] end)
            local v = plainNum(okF and first or nil)
            if v then e[#e + 1] = v end
        end
        row.e = e
    end
    local okI, parent = CALL(function() return C_Map.GetMapInfo(id).parentMapID end)
    local par = plainNum(okI and parent or nil)
    if par and par > 0 then
        row.p = par
        local okR, a, b, c, d = CALL(function() return C_Map.GetMapRectOnMap(id, par) end)
        a, b, c, d = plainNum(a), plainNum(b), plainNum(c), plainNum(d)
        if okR and a and b and c and d then row.r = { a, b, c, d } end
    end
    local okL, lo, hi = CALL(function() return C_Map.GetMapLevels(id) end)
    lo, hi = plainNum(lo), plainNum(hi)
    if okL and lo and hi then row.lv = { lo, hi } end
    return row
end
WC.mapExplOne = mapExplOne

local function sendMapExpl(all, maps)
    pcall(function() WC.send(WC.encode({ type = "mapexpl", v = "x1", all = all, maps = maps })) end)
end

-- THE ZONE HIGHLIGHTS (the Map room's continent view; UI Art's review + the user's yes to the pictures, 2026-10-07): on the world
-- map and a continent the game lights the zone under the cursor with that zone art's own highlight picture, drawn ADD at the box
-- C_Map.GetMapHighlightInfoAtPosition answers (the game's MapHighlightDataProvider: texCoord 0..texPct, size texture x/y and
-- top-left scrollChild x/y, all fractions of the map). The app has the pictures (by fileDataID); the BOX is the game's: a grid of
-- MAPHL_COLS x MAPHL_ROWS points over each map in MAPHL_MAPS, one grid row a tick, right after the mapexpl sweep, each picture's
-- placement kept once. {"type":"maphl", v = "h1", maps = {[id] = {{fid, atlas, texPctX, texPctY, texW, texH, x, y}, ...}}}
-- (atlas 0 when the game answers a file; a fid 0 + atlas name when it answers an atlas). Reads only; no Blizzard frame is touched.
local MAPHL_MAPS = { 947, 1414, 1415 }
local MAPHL_COLS, MAPHL_ROWS = 40, 27
local function r4(v)
    v = plainNum(v)
    return v and math.floor(v * 10000 + 0.5) / 10000
end
local function mapHlRow(id, j, seen, out)
    local y = (j - 0.5) / MAPHL_ROWS
    for i = 1, MAPHL_COLS do
        local x = (i - 0.5) / MAPHL_COLS
        local ok, fid, atlas, tpx, tpy, tx, ty, sx, sy = CALL(function() return C_Map.GetMapHighlightInfoAtPosition(id, x, y) end)
        if ok then
            fid = plainNum(fid) or 0
            local a = (type(atlas) == "string" and WC.isPlain(atlas) and atlas ~= "" and atlas) or plainNum(atlas) or 0
            local key = (fid > 0 and fid) or (a ~= 0 and ("a" .. tostring(a))) or nil
            if key and not seen[key] then
                local p = { fid, a, r4(tpx), r4(tpy), r4(tx), r4(ty), r4(sx), r4(sy) }
                if p[3] and p[4] and p[5] and p[6] and p[7] and p[8] then
                    seen[key] = true
                    out[#out + 1] = p
                end
            end
        end
    end
end
WC.mapHlRow = mapHlRow

local function mapHlSweep()
    local maps, m, j = {}, 1, 0
    local seen, out = {}, {}
    local function step()
        j = j + 1
        if j > MAPHL_ROWS then
            maps[tostring(MAPHL_MAPS[m])] = out
            m, j, seen, out = m + 1, 1, {}, {}
            if not MAPHL_MAPS[m] then
                pcall(function() WC.send(WC.encode({ type = "maphl", v = "h1", maps = maps })) end)
                return
            end
        end
        mapHlRow(MAPHL_MAPS[m], j, seen, out)
        CALL(function() C_Timer.After(0.05, step) end)
    end
    step()
end

-- THE QUESTS OF EVERY ZONE (the user, b101: "I dont really like that I cant see the quests on the map"). The map document carries the
-- quest markers of the map the player STANDS on only; the game's own map asks C_QuestLog.GetQuestsOnMap for whatever map it shows.
-- So the room gets them for every map of its art set but the world / continents: {"type":"mapquests", v = "q1",
-- maps = {[id] = {{q, x, y, t title, d = 1 ready, tx, ty its turn-in on this map}}}} (only maps with a quest; each doc is the whole set).
-- Once after the exploration sweep, then on the quest log's changes, coalesced (MQ_DELAY) and at most every MQ_EVERY seconds,
-- MQ_PER_TICK maps a tick. Reads only.
local MQ_SKIP = { [947] = true, [1414] = true, [1415] = true }
local MQ_PER_TICK, MQ_DELAY, MQ_EVERY = 10, 1.0, 5.0
local function mapQuestsOne(id)
    local out = {}
    local okq, qs = CALL(function() return C_QuestLog.GetQuestsOnMap(id) end)
    if not okq or type(qs) ~= "table" then return out end
    for _, q in ipairs(qs) do
        local qid = plainNum(SN(function() return q.questID end))
        local x, y = plainNum(SN(function() return q.x end)), plainNum(SN(function() return q.y end))
        if qid and x and y then
            local row = { q = qid, x = x, y = y }
            -- its title, so the room's quest panel has a row for every marker (UI Art: the panel and the markers are ONE list)
            local okT, t = CALL(function() return C_QuestLog.GetTitleForQuestID(qid) end)
            if okT and type(t) == "string" and WC.isPlain(t) and t ~= "" then row.t = t:sub(1, 60) end
            if readyForTurnIn(qid) then
                row.d = 1
                local okw, wx, wy = CALL(function() return C_QuestLog.GetNextWaypointForMap(qid, id) end)
                if okw then
                    wx, wy = plainNum(wx), plainNum(wy)
                    if onMap01(wx, wy) then row.tx, row.ty = wx, wy end
                end
            end
            out[#out + 1] = row
        end
    end
    return out
end
WC.mapQuestsOne = mapQuestsOne

local mq = { running = false, queued = false, lastAt = -1000 }
local mapQuestsSoon
local function mapQuestsSweep()
    if mq.running then mq.queued = true; return end
    mq.running, mq.queued = true, false
    mq.lastAt = (type(GetTime) == "function") and GetTime() or 0
    local maps, i = {}, 0
    local function step()
        for _ = 1, MQ_PER_TICK do
            i = i + 1
            local id = MAPEXPL_MAPS[i]
            if not id then
                pcall(function() WC.send(WC.encode({ type = "mapquests", v = "q1", maps = maps })) end)
                mq.running = false
                if mq.queued then mapQuestsSoon() end
                return
            end
            if not MQ_SKIP[id] then
                local rows = mapQuestsOne(id)
                if #rows > 0 then maps[tostring(id)] = rows end
            end
        end
        CALL(function() C_Timer.After(0.05, step) end)
    end
    step()
end
-- coalesced: one sweep MQ_DELAY after the first change, never sooner than MQ_EVERY after the last one
local mqTimer = false
mapQuestsSoon = function()
    if mqTimer then return end
    mqTimer = true
    local now = (type(GetTime) == "function") and GetTime() or 0
    local wait = math.max(MQ_DELAY, MQ_EVERY - (now - mq.lastAt))
    CALL(function() C_Timer.After(wait, function() mqTimer = false; mapQuestsSweep() end) end)
end
WC.mapQuestsSoon = mapQuestsSoon

local mapExplSwept = false
local function mapExplSweep()
    if mapExplSwept then return end
    mapExplSwept = true
    local maps, i = {}, 0
    local function step()
        for _ = 1, MAPEXPL_PER_TICK do
            i = i + 1
            local id = MAPEXPL_MAPS[i]
            if not id then
                sendMapExpl(true, maps)
                CALL(function() C_Timer.After(0.05, mapHlSweep) end)      -- then the zone highlights' boxes
                mapQuestsSoon()                                            -- and every zone's quests
                return
            end
            maps[tostring(id)] = mapExplOne(id)
        end
        CALL(function() C_Timer.After(0.05, step) end)
    end
    CALL(function() C_Timer.After(15, step) end)
end
WC.mapExplSweep = mapExplSweep

local function onExplorationUpdated()
    local id = currentMap()
    if id then sendMapExpl(false, { [tostring(id)] = mapExplOne(id) }) end
end

-- ---------------------------------------------------------------- the flight network (b-route-unknown-taxi)
-- The user, 2026-10-16: "my current quest is telling me to take a flight path, however I dont have any flight paths connected to where
-- I am at". Which known flight points CONNECT is readable only at a flight master: its map (C_TaxiMap.GetAllTaxiNodes, the client's
-- own FlightMapFrame source) gives each node's state - Current (here), Reachable (a flight from here, known and connected) or
-- Unreachable. At TAXIMAP_OPENED this sends {"type":"taxinet", cur = the node here, reach = {ids}}; the app keeps, per character,
-- the groups of nodes that connect, and suggests a flight only inside one. Reads only. In WC (the main chunk's 200-local limit).
function WC.taxiNet()
    local okm, mid = CALL(function() return (type(GetTaxiMapID) == "function") and GetTaxiMapID() or nil end)
    if not okm or type(mid) ~= "number" or not WC.isPlain(mid) then return end
    local okn, nodes = CALL(function() return C_TaxiMap.GetAllTaxiNodes(mid) end)
    if not okn or type(nodes) ~= "table" then return end
    local cur, reach = nil, {}
    local CURRENT = Enum and Enum.FlightPathState and Enum.FlightPathState.Current or 0
    local REACHABLE = Enum and Enum.FlightPathState and Enum.FlightPathState.Reachable or 1
    for _, n in ipairs(nodes) do
        local id = SN(function() return n.nodeID end)
        local st = SN(function() return n.state end)
        if id and st then
            if st == CURRENT then cur = id elseif st == REACHABLE and #reach < 80 then reach[#reach + 1] = id end
        end
    end
    if not cur then return end
    WC.send(WC.encode({ type = "taxinet", v = "tn1", m = mid, cur = cur, reach = reach }))
    pcall(WC.push)
end

-- ---------------------------------------------------------------- quest-marker events
-- Map.lua's OWN frame, not Core's WANT list: Core's router hands every event it does not know
-- to WC.snapshot, and QUEST_LOG_UPDATE must never cost a snapshot per kill credit.
-- Registered under pcall; a name this build lacks is reported as a `cap` line, like Core does.
-- The zone events only make sure a map change is noticed where Core's position ticker is blind
-- (no map position, e.g. an instance); everywhere else the "zone" snapshot has already asked.
local QUEST_MARKER_EVENTS = {
    "QUEST_LOG_UPDATE", "QUESTLINE_UPDATE",
    "QUEST_ACCEPTED", "QUEST_TURNED_IN", "QUEST_REMOVED", "PLAYER_LEVEL_UP",
    "PLAYER_ENTERING_WORLD", "ZONE_CHANGED_NEW_AREA",
    "SUPER_TRACKING_CHANGED",   -- the focused quest changed: the map's arrow follows at once
    "MINIMAP_UPDATE_TRACKING",  -- Find Herbs / Minerals switched: the node spots follow at once
    "MAP_EXPLORATION_UPDATED",  -- a new area discovered: the Map room's painted overlays follow (mapexpl)
    "USER_WAYPOINT_UPDATED",    -- the map pin placed / moved / cleared: uwp rides the map snapshot
    "TAXIMAP_OPENED",           -- a flight master's map: which known nodes connect (taxinet; b-route-unknown-taxi)
}

local function onQuestMarkerEvent(_, event, a1)
    -- the hidden minimap's mask may have been put back: said again (throttled; at once on a world / zone entry)
    pcall(WC.mmHide.reassert, event == "PLAYER_ENTERING_WORLD" or event == "ZONE_CHANGED_NEW_AREA")
    if event == "QUEST_LOG_UPDATE" then
        onQuestLog()                -- the hot one: a flag test until a check is due
        if mapExplSwept then mapQuestsSoon() end    -- every zone's quests: coalesced, at most every MQ_EVERY s
    elseif event == "QUESTLINE_UPDATE" then
        -- a1 is requestRequired: the client dropped what it held and must be asked again (its
        -- own world map re-requests here). A read now would only get the {} it holds meanwhile.
        -- Proven plain before it is compared: a secret must never be.
        if type(a1) == "boolean" and WC.isPlain(a1) and a1 == true then
            onRequestRequired()     -- capped per map: see CAPS
        else
            onQuestLines()          -- coalesced: one read next frame, however many events
        end
    elseif event == "QUEST_ACCEPTED" or event == "QUEST_TURNED_IN" or event == "QUEST_REMOVED" then
        if event == "QUEST_TURNED_IN" then doneDirty = true end   -- map.me.done ships again
        if mapExplSwept then mapQuestsSoon() end
        resetCaps()                 -- the player's doing: the caps start over
        requestSoon()
        onQuestLog()
    elseif event == "SUPER_TRACKING_CHANGED" then
        snapshotSoon("supertrack")  -- superTracked (and stWay) ride the map snapshot
    elseif event == "MINIMAP_UPDATE_TRACKING" then
        snapshotSoon("tracking")    -- map.track rides the map snapshot
    elseif event == "MAP_EXPLORATION_UPDATED" then
        onExplorationUpdated()      -- the current map's explored overlays again
    elseif event == "TAXIMAP_OPENED" then
        pcall(WC.taxiNet)           -- read only: the flight master's own answer on what connects
    elseif event == "USER_WAYPOINT_UPDATED" then
        snapshotSoon("waypoint")    -- map.uwp rides the map snapshot
    elseif event == "PLAYER_LEVEL_UP" then
        resetCaps()
        requestSoon()               -- new givers appear on level-up (and low ones turn grey)
    else                            -- PLAYER_ENTERING_WORLD, ZONE_CHANGED_NEW_AREA
        if event == "PLAYER_ENTERING_WORLD" then mapMenuProbeOnce(); mapExplSweep() end
        local id = currentMap()
        if id then noteMap(id) end
    end
end

-- THE CORPSE's events: a death records where it happened; a release, a revive or the corpse coming into range re-sends the
-- map document (the ghost / corpse fields); combat's end applies a peek change it held back
local DEATH_EVENTS = { "PLAYER_DEAD", "PLAYER_ALIVE", "PLAYER_UNGHOST", "CORPSE_IN_RANGE", "CORPSE_OUT_OF_RANGE",
                       "PLAYER_REGEN_ENABLED" }
local function onDeathEvent(_, event)
    if event == "PLAYER_REGEN_ENABLED" then
        if peekWant ~= peek then setPeek(peekWant) end
        return
    end
    if event == "PLAYER_DEAD" then
        local id = currentMap()
        if id then
            local x, y = playerXY(id)
            x, y = plainNum(x), plainNum(y)
            if x and y then died = { map = id, x = x, y = y } end
        end
    elseif (event == "PLAYER_ALIVE" or event == "PLAYER_UNGHOST") and not isDeadOrGhost() then
        died = nil                  -- alive again (a resurrection, the corpse, the spirit healer)
    end
    snapshotSoon("death")
end
WC.onDeathEvent = onDeathEvent  -- for the tests

if type(CreateFrame) == "function" then
    local okD, df = pcall(CreateFrame, "Frame")
    if okD and df then
        pcall(function() df:SetScript("OnEvent", onDeathEvent) end)
        local missing = {}
        for _, e in ipairs(DEATH_EVENTS) do
            local ok, registered = pcall(function() return df:RegisterEvent(e) end)
            if not ok or registered == false then missing[#missing + 1] = e end
        end
        if #missing > 0 then
            pcall(function() WC.send(WC.encode({ type = "cap", corpse = true, missingEvents = missing })) end)
        end
    end
end

if type(CreateFrame) == "function" then
    local okF, qf = pcall(CreateFrame, "Frame")
    if okF and qf then
        pcall(function() qf:SetScript("OnEvent", onQuestMarkerEvent) end)
        local missing = {}
        for _, e in ipairs(QUEST_MARKER_EVENTS) do
            local ok, registered = pcall(function() return qf:RegisterEvent(e) end)
            if not ok or registered == false then missing[#missing + 1] = e end
        end
        if #missing > 0 then
            pcall(function()
                WC.send(WC.encode({ type = "cap", questMarkers = true, missingEvents = missing }))
            end)
        end
    end
end

-- ---------------------------------------------------------------- THE MAP ROOM: the controller and the game's world map window
-- The user, 2026-10-07: the Map room "should function like wows in game maps"; and (2026-10-01) every room opens the game's own
-- window behind it and acts "the EXACT same" however it was entered. Like Character and Social, the room holds the shared capture
-- (Interact's WC.capture) under ONE id of its own, "map", for both ways in:
--   "touch"   the app's `hold` cell (the bar, a touch): the controller; the app then taps the `open` cell (a secure click of the
--             game's QuestLogMicroButton, its controller radial's map entry) once the hold is real, and the window's OnShow
--             takes again as "window" (a second take with the same id replaces the spec in place)
--   "window"  WorldMapFrame shown (M, the radial, the open cell): the window made invisible (spec.hide, alpha), the take pre-empts
--             a room that holds (spec.window, capture v9); a game-made open counts in `openN`, so the app shows the Map room
-- B: while the room's next B leads Home (`bh`) and the window shows, spec.back = WowCompanionMapClose (the game's own close button,
-- by reference): the player's own B clicks it through the capture's binding, the game closes its window, and the OnHide lets go
-- with "B" and tells the app (mappad k "BACK"). While B leads back (`bb`: the room's own browse history), B is relayed as a key.
-- THIS FILE NEVER OPENS OR CLOSES THE MAP ITSELF: secure clicks of the game's own buttons (the pad's cells, B's binding) and alpha
-- only; never ToggleWorldMap / ShowUIPanel / HideUIPanel, and C_Map.OpenWorldMap is RESTRICTED. The pin calls only engine
-- functions (C_Map.SetUserWaypoint / ClearUserWaypoint, C_SuperTrack), exactly what the game's own map runs for its pin.
-- Documents: mapwin (the room's state, at once on every change), mappad {k, n | lost} (the presses), mapack {op, ok, why}.
local MR_V = "mr1"
local MR_ID = "map"
local MR_ROW = "mapw"
local MR_CLOSE = "WowCompanionMapClose"
local MR_COLS = 40
local MRC = { hold = 0, free = 1, bh = 2, bb = 3, open = 4, close = 5 }
-- the pin's digits: two lanes of 17 cells, a pin sent in alternating lanes A, B, A, ... so two taps in a row never land on the same
-- cell (the pad drops a repeat within 0.25 s): op (A: 0 = place or remove at a spot, the game's GamepadTryPlaceWaypoint; 1 = clear),
-- then the uiMapID as 3 base-17 digits and x, y as 2 each (1/289 steps). A gap over WP_GAP or a wrong lane starts again.
local WP_A, WP_B, WP_BASE, WP_GAP = 6, 23, 17, 3
-- the keys beyond the eight the capture gives a guest: the shoulders zoom; L3 = the game's own map pin; Select / View = its
-- filters (Show:; Start until the user's 2026-10-09 ruling: Start is now the app's pause wheel in every room, still HELD here so
-- the game behind the room ignores it). Held by the room, the invisible game map behind it never places a pin at its own hidden
-- cursor.
local MR_EXTRA = { "PADLSHOULDER", "PADRSHOULDER", "PADLSTICK", "PADFORWARD", "PADBACK" }
local MR_LEFT = { B = true, left = true, taken = true }   -- lost for these = the room was left: never taken again by itself
local MR_REMOVE_NEAR = 0.03                               -- the game's own: a pin this near the old one removes it

local mr = { want = false, held = false, via = nil, pend = false, backOn = false, leaveOn = false, bnext = "home", n = 0,
             open = false, stayOff = false, note = nil, why = nil, openN = 0, ownOpenAt = -1000, ownOpenFor = nil,
             closeTapAt = -1000, openArmed = false, cells = {}, wp = { buf = {}, at = -1000 } }
-- THE QUEST LOG IS THE QUESTS ROOM'S (the user, 2026-10-07: the quest-log key brings up the Quests room, M the Map room). Both open
-- this same WorldMapFrame. The Quests session stamps WC.questLogAt in a post-hook on ToggleQuestLog (the quest-log key and
-- QuestLogMicroButton's OnClick both run it), right AFTER the OnShow inside it, so a game-made open is decided one frame later:
-- the room's own open cell first (whoever held when it was clicked), then a quest-log stamp (-> WC.questsWindow.opened), else the
-- Map room. The open / close cells also serve the Quests room's touch route while "pad:quests" holds (no second row: 42/48).
local QUESTS_HOLDER = "pad:quests"
local QUESTLOG_WINDOW = 0.3
WC.mapRoom = mr                                           -- for tools/test_map_room.py

local function mrCap()
    local c = WC.capture                                  -- looked up when called: Interact loads after this file
    if type(c) == "table" and type(c.take) == "function" and type(c.release) == "function" then return c end
end
local function mrVer(c) return tonumber(c and c.version) or 1 end
local function mrIsHolder()
    local c = mrCap()
    if not c or type(c.state) ~= "function" then return false end
    local ok, st = CALL(c.state)
    return (ok and type(st) == "table" and st.holder == MR_ID) and true or false
end
local function holderNow()
    local c = mrCap()
    if not c or type(c.state) ~= "function" then return nil end
    local ok, st = CALL(c.state)
    if ok and type(st) == "table" and type(st.holder) == "string" and WC.isPlain(st.holder) then return st.holder end
end
local function mapShown()
    local f = _G.WorldMapFrame
    if type(f) ~= "table" then return false end
    local ok, s = pcall(f.IsShown, f)
    return ok and s == true
end
local function now() return (type(GetTime) == "function") and GetTime() or 0 end

local function mrSend()
    pcall(function()
        WC.send(WC.encode({ type = "mapwin", v = MR_V, held = mr.held or nil, via = mr.via, pend = mr.pend or nil,
            note = mr.note, why = mr.why, open = mr.open or nil, openN = mr.openN, armed = mr.openArmed or nil,
            bnext = mr.bnext, back = mr.backOn or nil, row = MR_ROW, fade = mr.fadeN, stick = mr.stickShown or nil }))
        WC.push()
    end)
end
local function mrNote(why) mr.note = why; mrSend() end
local function mrAck(op, ok, why, extra)
    pcall(function()
        local d = { type = "mapack", v = MR_V, op = op, ok = ok and true or false, why = why }
        if type(extra) == "table" then for k, v in pairs(extra) do d[k] = v end end
        WC.send(WC.encode(d))
        WC.push()
    end)
end

-- the game's own close button, by reference (its global name is not known offline: Social so11's route), behind a named secure
-- button the capture can click (spec.back / spec.leave must name a global button). Made and set out of a fight only.
local mrClose
local function mapCloseButton()
    local ok, cb = CALL(function() return WorldMapFrame.BorderFrame.CloseButton end)
    if ok and type(cb) == "table" then return cb end
end
local function mrArmClose()
    if type(InCombatLockdown) == "function" and InCombatLockdown() then return end
    if not mrClose then
        local ok, b = pcall(CreateFrame, "Button", MR_CLOSE, UIParent, "SecureActionButtonTemplate")
        if not ok or type(b) ~= "table" then return end
        pcall(b.RegisterForClicks, b, "AnyDown", "AnyUp")    -- a binding click fires it once per press
        mrClose = b
    end
    local cb = mapCloseButton()
    pcall(mrClose.SetAttribute, mrClose, "type", cb and "click" or nil)
    pcall(mrClose.SetAttribute, mrClose, "clickbutton", cb)
end
local function closeReady() return mrClose ~= nil and mapCloseButton() ~= nil end

-- the row's cells (the pad makes them and re-runs this on every refresh; attributes are set out of a fight only)
local function mrInitCell(btn, col)
    mr.cells[col] = btn
    if col == MRC.open or col == MRC.close then
        if not btn.wcMapHooked and type(btn.HookScript) == "function" then
            btn.wcMapHooked = true
            -- PreClick runs before the secure action, so the window's OnShow / OnHide in the same click sees the mark
            pcall(btn.HookScript, btn, "PreClick", function()
                if col == MRC.open and mr.openArmed then mr.ownOpenAt = now(); mr.ownOpenFor = holderNow() end
                if col == MRC.close then mr.closeTapAt = now() end
            end)
        end
    end
    local cb = nil
    if col == MRC.open then
        -- the game's button TOGGLES (and one tap is two presses): armed only while the room really holds and the map is closed;
        -- the window's OnShow re-arms it at once (inside the same click), so the second press clicks nothing
        local b = _G.QuestLogMicroButton
        local h = holderNow()
        local forUs = (mr.held and h == MR_ID) or h == QUESTS_HOLDER      -- the Quests room's touch route uses it too
        if forUs and type(b) == "table" and not mapShown() then cb = b end
        mr.openArmed = cb ~= nil
    elseif col == MRC.close then
        if mapShown() then cb = mapCloseButton() end
    end
    pcall(btn.SetAttribute, btn, "type", cb and "click" or nil)
    pcall(btn.SetAttribute, btn, "clickbutton", cb)
end

local mrOnCell                                             -- below
local mrRegistered = false
-- mr7 THE STICK CATCH (the user on RC4, 2026-10-09: "the control stick with the map open is letting me walk around"; MEASURED in
-- Home's 19:30 read: 19:26:14-16 the character walked and turned all the while the Map room held). The capture never takes the sticks
-- for a room (the user's 09-30 ruling: in the rooms the stick still moves the character), but in the Map room the left stick IS the
-- map's cursor (the app reads it on the Android side), as on the game's own map. So the radial's pattern (Spells' Radial r3 / r4): an
-- invisible frame of ours with EnableGamePadStick(true) whose handler answers FALSE (handled: the game does not get the stick), SHOWN
-- while the Map room holds (not under the pause wheel: the wheel's own catch holds then), hidden otherwise. EnableGamePadStick is a
-- protected call: made once, out of a fight; Show / Hide work in fights too. No CVar. A field of `mr`: the main chunk is at Lua's
-- 200-local limit. Run from mrRefresh (every take / let-go / holder change), before its fight return.
mr.stickSync = function()
    local f = mr.stickFrame
    if not f then
        if type(CreateFrame) ~= "function" then return end
        local ok, nf = pcall(CreateFrame, "Frame", "WowCompanionMapStickCatch", UIParent)
        if not ok or type(nf) ~= "table" then return end
        f = nf
        mr.stickFrame = f
        pcall(f.Hide, f)
        pcall(f.SetAllPoints, f, UIParent)
        pcall(f.EnableMouse, f, false)
        pcall(f.SetScript, f, "OnGamePadStick", function() return false end)
    end
    if not mr.stickOn and not (type(InCombatLockdown) == "function" and InCombatLockdown()) then
        local ok = pcall(f.EnableGamePadStick, f, true)
        local okE, en = pcall(f.IsGamePadStickEnabled, f)
        mr.stickOn = (ok and okE and en == true) and true or false
    end
    local want = (mr.stickOn and holderNow() == MR_ID) and true or false
    if want ~= (mr.stickShown and true or false) then
        if want then pcall(f.Show, f) else pcall(f.Hide, f) end
        mr.stickShown = want
        mrSend()
    end
end

local function mrRefresh()
    pcall(mr.stickSync)
    local pad = WC.pad
    if not mrRegistered or type(pad) ~= "table" or type(pad.secureRow) ~= "function" then return end
    if type(InCombatLockdown) == "function" and InCombatLockdown() then return end    -- the pad queues it; ours re-runs after
    local was = mr.openArmed
    pcall(pad.secureRow, MR_ROW, MR_COLS, mrInitCell, function(col) mrOnCell(col) end, { onPress = true })
    -- the open cell armed or not changes with the holder (the Quests room's touch route taps it too): the apps read `armed`
    if mr.openArmed ~= was then mrSend() end
end

local mrTake                                               -- below (the spec's lost asks again through it)
local function mrPress(k)
    if mr.pend then mr.pend = false; mrNote("taken") end   -- only the holder gets presses: the queue / the fight's ENTER granted us
    mr.n = mr.n + 1
    pcall(function()
        WC.send(WC.encode({ type = "mappad", v = MR_V, k = (type(k) == "string" and WC.isPlain(k)) and k or nil, n = mr.n }))
        WC.push()
    end)
end
local function mrLost(why)
    why = (type(why) == "string" and WC.isPlain(why)) and why or "lost"
    if MR_LEFT[why] then
        mr.want = false
        if mr.open then mr.stayOff = true end              -- stay off until the window shows again (Character's LEFT rule)
    end
    mr.held, mr.via, mr.pend, mr.backOn, mr.leaveOn = false, nil, false, false, false
    pcall(function()
        WC.send(WC.encode({ type = "mappad", v = MR_V, lost = why, n = mr.n }))
        WC.push()
    end)
    mrNote("lost-" .. why)
    if C_Timer and C_Timer.After then C_Timer.After(0, mrRefresh) end
    -- capture v11: the room still wants it (an NPC window or a fight took it): queue again, one frame later
    if mr.want and mrVer(mrCap()) >= 11 and C_Timer and C_Timer.After then
        C_Timer.After(0, function() if mr.want and not mr.held then mrTake(mr.open and "window" or "touch") end end)
    end
end

local function mrSpec(via, cap)
    local spec = { press = mrPress, lost = mrLost, extra = MR_EXTRA }
    if via == "window" then
        spec.hide = { "WorldMapFrame" }
        mrArmClose()
        local v = mrVer(cap)
        local fight = type(InCombatLockdown) == "function" and InCombatLockdown()
        if v >= 9 and closeReady() then spec.leave = MR_CLOSE end
        if v >= 9 and not fight then spec.window = true end
        if v >= 2 and closeReady() and mr.bnext == "home" then spec.back = MR_CLOSE end
    end
    return spec
end

-- [force] = take again even while held with this via (a new spec: B's close switched on / off)
mrTake = function(via, force)
    via = via or "touch"
    if mr.held and not force and (mr.via == via or via == "touch") then return true end   -- a touch hold changes no window hold
    local c = mrCap()
    if not c then mr.why = "no controller capture"; mrNote("take-skip-nocapture"); return false, mr.why end
    local spec = mrSpec(via, c)
    local okC, ok, why = pcall(c.take, MR_ID, spec)
    if okC and not ok and why == "bad spec" and (spec.leave or spec.window or spec.back) then
        spec.leave, spec.window, spec.back = nil, nil, nil
        okC, ok, why = pcall(c.take, MR_ID, spec)
    end
    if okC and ok then
        mr.held, mr.via, mr.why = true, via, nil
        mr.pend = not mrIsHolder()                         -- ok but not the holder = queued behind another (v11) or a fight's ENTER
        mr.backOn, mr.leaveOn = spec.back ~= nil, spec.leave ~= nil
        if mrVer(c) >= 3 and type(c.bnext) == "function" then CALL(c.bnext, MR_ID, mr.bnext) end
        mrNote(mr.pend and "taken-pending" or (via == "touch" and "taken-touch" or "taken"))
    else
        why = (okC and type(why) == "string" and WC.isPlain(why)) and why or "error"
        mr.why = why
        mrNote("take-refused-" .. why)
        if why == "combat" and type(c.refused) == "function" then CALL(c.refused, MR_ID) end
    end
    if C_Timer and C_Timer.After then C_Timer.After(0, mrRefresh) else mrRefresh() end
    return mr.held, mr.why
end

-- let go through the capture. False only when it answers "tap leave" (a fight: only Interact's secure leave gives the pad back)
local function mrRelease(why)
    local c = mrCap()
    if c then
        local okC, ok, rwhy = pcall(c.release, MR_ID, why)
        if okC and ok == false and rwhy == "tap leave" then return false end
    end
    mr.held, mr.via, mr.pend, mr.backOn, mr.leaveOn = false, nil, false, false, false
    if C_Timer and C_Timer.After then C_Timer.After(0, mrRefresh) end
    return true
end

-- THE GAME'S WINDOW (hooks only, after the game's own code)
local function questsOpened(own)
    local q = WC.questsWindow
    if type(q) ~= "table" or type(q.opened) ~= "function" then return false end      -- an older Quests.lua: the Map room keeps it
    CALL(q.opened, own and true or false)
    return true
end
local function mapOpen()
    mr.want = true
    mrTake("window", mr.held and mr.via ~= "window")
    mrSend()
end
local function onMapShown()
    mr.open, mr.stayOff = true, false
    local own = now() - mr.ownOpenAt < 0.5
    local ownFor = mr.ownOpenFor
    mr.ownOpenAt, mr.ownOpenFor = -1000, nil
    -- the open cell disarms at once (this may run inside its own click); the row is refreshed one frame later
    local o = mr.cells[MRC.open]
    if o and not (type(InCombatLockdown) == "function" and InCombatLockdown()) then pcall(o.SetAttribute, o, "type", nil) end
    mr.openArmed = false
    if own then
        if ownFor == QUESTS_HOLDER and questsOpened(true) then mrSend(); return end    -- the Quests room's own open
        return mapOpen()
    end
    -- the GAME opened it: which key decides one frame later (the quest-log stamp lands after this OnShow)
    local function decide()
        if not mapShown() then return end
        local ql = tonumber(WC.questLogAt)
        if ql and now() - ql < QUESTLOG_WINDOW and questsOpened(false) then mrSend(); return end
        mr.openN = mr.openN + 1                            -- M, the radial's map: the app brings the Map room up
        mapOpen()
    end
    if C_Timer and C_Timer.After then C_Timer.After(0, decide) else decide() end
end
local function onMapHidden()
    mr.open = false
    local ours = now() - mr.closeTapAt < 1.0               -- the app's own close cell (its PreClick mark), used once
    mr.closeTapAt = -1000
    if C_Timer and C_Timer.After then C_Timer.After(0, mrRefresh) else mrRefresh() end   -- the open cell armed again
    if not mr.held or mr.via ~= "window" then mrSend(); return end
    if ours then
        if not mrRelease("closed") then mrNote("closed-in-fight"); return end
        mr.want = false
        mrNote("closed-app")
        return
    end
    -- closed by the player's own B (spec.back) or the game itself: let go "B" and tell the app to go Home
    if not mrRelease("B") then mrNote("closed-in-fight"); return end
    mr.want = false
    mr.n = mr.n + 1
    pcall(function()
        WC.send(WC.encode({ type = "mappad", v = MR_V, k = "BACK", n = mr.n }))
        WC.push()
    end)
    mrNote("released-B")
end

-- where the room's next B leads (the app says): "home" = B closes the game's window and leaves; "back" = B is the room's own
local function setBNext(where)
    mr.bnext = where
    local c = mrCap()
    if mr.held and not (type(InCombatLockdown) == "function" and InCombatLockdown()) and mr.via == "window" then
        local wantBack = (where == "home" and closeReady() and mapShown()) and true or false
        if wantBack ~= mr.backOn then mrTake("window", true); return end
    end
    if mr.held and mrVer(c) >= 3 and type(c.bnext) == "function" then CALL(c.bnext, MR_ID, where) end
    mrSend()
end

-- THE PIN: the game's own gamepad pin (Blizzard_WorldMap GamepadTryPlaceWaypoint): place it at the spot, or remove it when the old
-- one is within MR_REMOVE_NEAR there; refused where the game refuses a pin. NEVER super-tracked (the game's own gamepad call sets
-- false; and the game has ONE super-track, so tracking the pin would take the focused quest's: Quests' review). Every outcome is
-- answered (mapack op "pin": placed / removed / cleared / restarted / refused), so the app shows it and may retry once.
-- the game has ONE super-track: a pin must never take the focused quest's (Quests' review). If this client super-tracks a placed
-- pin by itself, the pin is let go and the quest tracked before is tracked again (the same engine call the Quests focus uses);
-- a remove / clear touches the super-track only while the PIN holds it.
local function stQuest()
    local ok, q = CALL(function() return C_SuperTrack.GetSuperTrackedQuestID() end)
    return plainNum(ok and q or nil) or 0
end
local function stPin()
    local ok, v = CALL(function() return C_SuperTrack.IsSuperTrackingUserWaypoint() end)
    return (ok and type(v) == "boolean" and WC.isPlain(v) and v) and true or false
end
local function pinUntracked()
    if stPin() then CALL(function() C_SuperTrack.SetSuperTrackedUserWaypoint(false) end) end
end
local function pinClear()
    pinUntracked()
    CALL(function() C_Map.ClearUserWaypoint() end)
end

local function pinAt(m, x, y)
    local okR, off = CALL(function() return C_GameRules.IsGameRuleActive(Enum.GameRule.WorldMapTrackingPinDisabled) end)
    if okR and off == true then return mrAck("pin", false, "pins are off in this game") end
    local okC, can = CALL(function() return C_Map.CanSetUserWaypointOnMap(m) end)
    if not okC or can ~= true then return mrAck("pin", false, "no pin on this map", { m = m }) end
    local remove = false
    local okP, px, py = CALL(function() return C_Map.GetUserWaypointPositionForMap(m):GetXY() end)
    px, py = plainNum(okP and px or nil), plainNum(py)
    if px and py and math.abs(px - x) <= MR_REMOVE_NEAR and math.abs(py - y) <= MR_REMOVE_NEAR then remove = true end
    if remove then
        pinClear()
        CALL(function() PlaySound(SOUNDKIT.UI_MAP_WAYPOINT_REMOVE) end)
        return mrAck("pin", true, nil, { m = m, removed = true })
    end
    local prev = stQuest()
    local okS = CALL(function()
        local pt = (UiMapPoint and UiMapPoint.CreateFromCoordinates) and UiMapPoint.CreateFromCoordinates(m, x, y)
            or { uiMapID = m, position = CreateVector2D(x, y) }
        C_Map.SetUserWaypoint(pt)
    end)
    if not okS then return mrAck("pin", false, "the game refused the pin", { m = m }) end
    pinUntracked()
    if prev ~= 0 and stQuest() ~= prev then CALL(function() C_SuperTrack.SetSuperTrackedQuestID(prev) end) end
    CALL(function() PlaySound(SOUNDKIT.UI_MAP_WAYPOINT_CLICK_TO_PLACE) end)
    mrAck("pin", true, nil, { m = m, x = x, y = y })
end
local function onDigit(col)
    local w = mr.wp
    local lane = (col < WP_B) and 0 or 1
    local d = col - ((lane == 0) and WP_A or WP_B)
    if now() - w.at > WP_GAP and #w.buf > 0 then w.buf = {}; mrAck("pin", false, "restarted") end
    w.at = now()
    if #w.buf % 2 ~= lane then                             -- a wrong lane: a new pin starts only on lane A
        local had = #w.buf > 0
        w.buf = {}
        if had then mrAck("pin", false, "restarted") end
        if lane ~= 0 then return end
    end
    w.buf[#w.buf + 1] = d
    local b = w.buf
    if b[1] == 1 then
        w.buf = {}
        pinClear()
        return mrAck("pin", true, nil, { cleared = true })
    end
    if b[1] == 2 then                                      -- mp1: the map's minimap options (WC.mmp), the next digit their bits
        if #b < 2 then return end
        w.buf = {}
        return WC.mmp.setOpt(b[2])
    end
    if b[1] ~= 0 then w.buf = {}; return mrAck("pin", false, "unknown pin op") end
    if #b < 8 then return end
    w.buf = {}
    local m = (b[2] * WP_BASE + b[3]) * WP_BASE + b[4]
    local n2 = WP_BASE * WP_BASE
    pinAt(m, (b[5] * WP_BASE + b[6] + 0.5) / n2, (b[7] * WP_BASE + b[8] + 0.5) / n2)
end

-- the cells' taps (after the game's own click ran, once per tap)
mrOnCell = function(col)
    if col == MRC.hold then
        mr.want = true
        local ok, why = mrTake(mr.open and "window" or "touch")
        mrAck("hold", ok, why, { waits = (ok and not mrIsHolder()) or nil })
    elseif col == MRC.free then
        local named = mrIsHolder()
        if not mr.held and not named then return mrAck("free", true, "not holding") end
        mr.want = false
        if mr.via == "window" or mr.open then mr.stayOff = true end
        if not mrRelease("closed") then mrNote("close-fight-leave"); return mrAck("free", false, "tap leave") end
        mrNote("closed-touch")
        mrAck("free", true)
    elseif col == MRC.bh then
        setBNext("home")
    elseif col == MRC.bb then
        setBNext("back")
    elseif col == MRC.open then
        -- Quests' review: disarm right after the click, in the cell's own post-hook (out of a fight): the tap's DUPLICATE press, up
        -- to ~0.24 s later, would click the toggle again and shut the map. Re-armed on the map's OnHide / after a fight.
        local o = mr.cells[MRC.open]
        if o and not (type(InCombatLockdown) == "function" and InCombatLockdown()) then pcall(o.SetAttribute, o, "type", nil) end
        mr.openArmed = false
        -- a tap that opened nothing (a fight froze the cell, or the click failed): the room keeps the controller without the window
        if not mapShown() then mrNote("open-tap-none") end
    elseif col == MRC.close then
        if C_Timer and C_Timer.After then
            C_Timer.After(0.2, function()
                if mr.held and not mapShown() then
                    mr.want = false
                    if mrRelease("closed") then mrNote("closed-touch") end
                end
            end)
        end
    elseif col >= WP_A and col < WP_B + WP_BASE then
        onDigit(col)
    end
end

-- the 1 s tick: a queued / pending take that was granted; the window open, nobody holding and the room not left: take it again
local function mrTick()
    if mr.pend and mrIsHolder() then mr.pend = false; mrNote("taken") end
    if type(InCombatLockdown) == "function" and InCombatLockdown() then return end
    -- the window is shared with the Quests room's quest log: only a map the Map room wanted is taken again
    if not mr.want or not mr.open or mr.stayOff or (mr.held and mr.via == "window") then return end
    local c = mrCap()
    local okS, st = CALL(function() return c and c.state() end)
    if okS and type(st) == "table" and (st.holder == nil or st.holder == MR_ID) then mrTake("window", mr.held) end
end

-- a fight: the open cell goes idle before the lockdown; after it, the row is armed again and the room squared with the capture
local function mrAfterFight()
    if type(InCombatLockdown) == "function" and InCombatLockdown() then return end
    mrArmClose()
    mrRefresh()
    if mr.held and not mrIsHolder() and not mr.pend then
        mr.held = false
        mrTake(mr.open and "window" or "touch")
    elseif mr.held and mr.via == "window" and not mr.open then
        mrTake("touch", true)                              -- the window closed in the fight: B is the room's own again
    end
    if mr.want and mr.open and not mr.held and not mr.stayOff then mrTake("window") end
end

local mrHooked = false
local function mrHookMap()
    if mrHooked then return end
    local f = _G.WorldMapFrame
    if type(f) ~= "table" or type(f.HookScript) ~= "function" then return end
    mrHooked = true
    pcall(f.HookScript, f, "OnShow", onMapShown)
    pcall(f.HookScript, f, "OnHide", onMapHidden)
    -- THE MOVEMENT FADER (measured on the Thor 2026-10-09; the user: "opening the map room is opening the mapm on the top screen").
    -- The game's map joins PlayerMovementFrameFader on every show (Blizzard_WorldMap OnShow: AddDeferredFrame, alpha .5 moving / 1
    -- still): from the player's next start or stop of moving, the fader's OnUpdate moves WorldMapFrame's alpha toward that EVERY
    -- frame, over the capture's one alpha 0 (spec.hide), and the map stood on the top screen. It is the only frame the fader drives.
    -- So while a room that hides the game's map holds the controller (the Map room through the game's window; the Quests room, whose
    -- quest log IS this window) and the capture hides (proven or trusted: its own rule), every alpha the game sets on WorldMapFrame
    -- is put back to 0 at once: a post-hook on the frame's own SetAlpha. Nothing of the game's is called and no CVar is touched. The
    -- capture's let-go clears its holder BEFORE it puts the alpha back, so its restore is never undone. mapwin's `fade` counts the
    -- alphas put back. (Inside this function: the file's main chunk is at Lua's 200-local limit.)
    -- mr6 (Quests, 2026-10-09; capture v20, Interact i107): the pause wheel opens OVER a holding room as an overlay; the room is
    -- SUSPENDED (state().under) and its windows stay hidden, so it counts while the overlay holds. Only while something holds:
    -- the capture's guestLost clears the holder, puts the alpha back and only then drops `under`.
    if type(hooksecurefunc) == "function" and type(f.SetAlpha) == "function" then
        pcall(hooksecurefunc, f, "SetAlpha", function(fr, a)
            if type(a) ~= "number" or a <= 0 then return end
            local c = mrCap()
            if not c or type(c.state) ~= "function" then return end
            local ok, st = CALL(c.state)
            if not (ok and type(st) == "table") or not (st.proven or st.trusted) then return end
            local h, u = st.holder, st.under
            local hides = (h == MR_ID and mr.via == "window") or h == QUESTS_HOLDER
                or (h ~= nil and ((u == MR_ID and mr.via == "window") or u == QUESTS_HOLDER))
            if hides and pcall(fr.SetAlpha, fr, 0) then mr.fadeN = (mr.fadeN or 0) + 1 end
        end)
    end
    if mapShown() then onMapShown() end
end

if type(CreateFrame) == "function" then
    local okF, rf = pcall(CreateFrame, "Frame")
    if okF and rf then
        for _, e in ipairs({ "ADDON_LOADED", "PLAYER_LOGIN", "PLAYER_REGEN_DISABLED", "PLAYER_REGEN_ENABLED" }) do
            pcall(function() rf:RegisterEvent(e) end)
        end
        pcall(function()
            rf:SetScript("OnEvent", function(_, event)
                if event == "PLAYER_REGEN_DISABLED" then
                    -- before the lockdown: the toggle cell carries no action through the fight (one tap = two presses)
                    local o = mr.cells[MRC.open]
                    if o then pcall(o.SetAttribute, o, "type", nil) end
                    mr.openArmed = false
                    return
                end
                if event == "PLAYER_REGEN_ENABLED" then
                    if C_Timer and C_Timer.After then C_Timer.After(0, mrAfterFight) else mrAfterFight() end
                    return
                end
                mrHookMap()
                mrArmClose()
                -- the row: after login, so every row the add-ons made at load keeps its place (rows 0-37 never move)
                if event == "PLAYER_LOGIN" and not mrRegistered and type(WC.pad) == "table" and type(WC.pad.secureRow) == "function" then
                    mrRegistered = true
                    local c = mrCap()
                    if c and type(c.onChange) == "function" then
                        CALL(c.onChange, function() if C_Timer and C_Timer.After then C_Timer.After(0, mrRefresh) end end)
                    end
                    mrRefresh()
                    if type(WC.pad.onLayout) == "function" then pcall(WC.pad.onLayout, function() mrSend() end) end
                    mrSend()
                end
            end)
        end)
        if C_Timer and C_Timer.NewTicker then pcall(C_Timer.NewTicker, 1.0, mrTick) end
    end
end
WC.mapRoomCell = function(col) return mrOnCell(col) end   -- for tools/test_map_room.py


-- ================================================================ THE MINIMAP PROBE (mp1; "mmprobe" documents)
-- The user, 2026-10-12 (via tracking): "review and add the node logic from the tracking addon ... maybe it will have some logic
-- that we can learn from on seeing other things on the mini map". THE TECHNIQUE (learned from the Node Sight add-on; this is our
-- own code): the minimap answers a PRETEND mouse at any point - Minimap:UpdateMouseoverAtPoint, the controller cursor's own call,
-- live only while the minimap's soft cursor is on - with what its tooltip would say there (C_TooltipInfo.GetMinimapMouseover): the
-- NAMES of the dots under that point. Those are the herbs / ore of the player's own Find tracking and the town NPCs of the
-- minimap's tracking (repair, innkeeper, flight master...). A grid of points over the minimap is tried, a batch a frame; points
-- that name the same thing and touch are one dot; its offset from the minimap's middle, through C_Minimap.GetViewRadius (the yards
-- from the middle to the edge), is how far north / west of the player it stands. The app gets the names and the yards: the NPCs
-- it has SEEN (the user's "only NPCs actually seen") and the live herb / ore dots.
-- RULES: never in a fight; never while the minimap is hidden or the game itself is using its soft cursor; the soft cursor goes off
-- again in the SAME frame; every value must be plain (WC.isPlain), a secret one is dropped. Paced: a scan every PACE_MOVING s while
-- the player moves, slower standing still or finding nothing. The cost of each scan (debugprofilestop) rides in the document.
-- THE OPTIONS (the app's map filter; op digit 2 on the pin lane, onDigit): 1 the probe, 2 the Find Herbs / Find Minerals
-- AUTO-SWITCH, 4 SPARKLES (the soft-target icons over nearby herbs / ore / chests). The user, 2026-10-12: "its fine to change game
-- settings" FOR THIS FEATURE: each game setting changed is REMEMBERED and PUT BACK when the option goes off and at logout (so a
-- disabled add-on leaves the game as it was), unless the player changed it since; the Controller UI's own SoftTargetInteract is
-- left alone when it is on. Bars, bindings and macros are never touched.
-- All of it lives in WC.mmp: Map.lua's main chunk is at Lua 5.1's 200-local limit (no new top-level local).
WC.mmp = {
    V = "mp1",
    STEP = 8,                 -- minimap units between the points tried (a dot answers within ~15)
    PER_FRAME = 60,           -- points tried in one frame
    PACE_MOVING = 0.5, PACE_STILL = 2.0, PACE_EMPTY = 4.0, PACE_OFF = 5.0,
    MOVED_YD = 2,             -- the player moved since the last scan
    SEND_MIN = 2.0,           -- seconds between two documents (unless the dots changed a lot or the options did)
    HEARTBEAT = 10.0,
    MAX_DOTS = 60,
    SWITCH_YD = 40,           -- yards walked between two Find switches
    SWITCH_WAIT = 1.0,        -- the dots take a moment to change after a switch
    FINDERS = { [2383] = "herbs", [2580] = "ore" },          -- Find Herbs, Find Minerals
    FINDER_NAMES = { ["Find Herbs"] = "herbs", ["Find Minerals"] = "ore" },
    SPARKLES = { { "SoftTargetIconGameObject", "1" }, { "SoftTargetInteractArc", "2" }, { "SoftTargetInteractRange", "30" } },
    opt = 1,                  -- probe on, auto-switch off, sparkles off until the app says otherwise
    wait = 3.0, still = 0, empty = 0, sentAt = -1000, sig = "", saved = {}, applied = {}, pend = false,
}

function WC.mmp.plain(v) return v ~= nil and WC.isPlain(v) end

function WC.mmp.clean(t)
    if type(t) ~= "string" or not WC.isPlain(t) then return nil end
    t = t:gsub("|c%x%x%x%x%x%x%x%x", ""):gsub("|r", ""):gsub("|T.-|t", ""):gsub("^%s+", ""):gsub("%s+$", "")
    if t == "" then return nil end
    return t
end

-- the minimap can be probed now: false + why
function WC.mmp.can()
    if type(InCombatLockdown) == "function" and InCombatLockdown() then return false, "combat" end
    local M = _G.Minimap
    if type(M) ~= "table" or type(M.UpdateMouseoverAtPoint) ~= "function" or type(M.SetUsingSoftCursor) ~= "function" then
        return false, "api"
    end
    if type(C_TooltipInfo) ~= "table" or type(C_TooltipInfo.GetMinimapMouseover) ~= "function" then return false, "api" end
    local okV, vis = pcall(M.IsVisible, M)
    if not okV or vis ~= true then return false, "hidden" end
    if type(M.IsUsingSoftCursor) == "function" then
        local okU, using = pcall(M.IsUsingSoftCursor, M)
        if okU and using == true then return false, "busy" end              -- the game's own cursor is on the minimap
    end
    return true
end

function WC.mmp.player()
    local ok, wx, wy = CALL(UnitPosition, "player")
    if ok and type(wx) == "number" and type(wy) == "number" and WC.isPlain(wx) and WC.isPlain(wy) then return wx, wy end
end

-- the grid of points (minimap units from the bottom left) inside the minimap's circle, for this width
function WC.mmp.grid(width)
    local P = WC.mmp
    if P.gridW == width and P.gridPts then return P.gridPts end
    local pts, n = {}, math.floor(width / P.STEP)
    for gx = 0, n do
        for gy = 0, n do
            local dx, dy = gx * P.STEP - width / 2, gy * P.STEP - width / 2
            if dx * dx + dy * dy <= width * width / 4 then pts[#pts + 1] = gx * 1000 + gy end
        end
    end
    P.gridW, P.gridPts = width, pts
    return pts
end

function WC.mmp.start()
    local P = WC.mmp
    local ok, why = P.can()
    if not ok then
        P.why = why
        P.wait = P.PACE_OFF
        if why ~= P.lastWhy then P.lastWhy = why; P.send(true) end
        return
    end
    P.why, P.lastWhy = nil, nil
    if P.opt % 4 >= 2 and P.switch() then P.wait = P.SWITCH_WAIT; return end
    local M = _G.Minimap
    local okG, width, left, bottom, scale = pcall(function() return M:GetWidth(), M:GetLeft(), M:GetBottom(), M:GetEffectiveScale() end)
    if not okG or type(width) ~= "number" or width <= 0 or type(left) ~= "number" or type(bottom) ~= "number" or type(scale) ~= "number" then
        P.wait = P.PACE_OFF; return
    end
    local okR, radius = CALL(function() return C_Minimap.GetViewRadius() end)
    if not okR or type(radius) ~= "number" or not WC.isPlain(radius) or radius <= 0 then P.wait = P.PACE_OFF; return end
    local wx, wy = P.player()
    local rot = false
    local okC, r = CALL(function() return C_CVar.GetCVar("rotateMinimap") end)      -- a READ
    if okC and (r == "1" or r == 1) then rot = true end
    local facing = rot and SN(GetPlayerFacing) or 0
    P.scan = { pts = P.grid(width), i = 1, hits = {}, width = width, left = left, bottom = bottom, scale = scale, radius = radius,
               rot = rot, facing = facing or 0, wx = wx, wy = wy, m = plainNum(mapID()), cost = 0 }
end

-- one batch of points: the soft cursor on, the points, the soft cursor off - in this frame, whatever happens
function WC.mmp.step()
    local P, sc = WC.mmp, WC.mmp.scan
    if type(InCombatLockdown) == "function" and InCombatLockdown() then P.scan = nil; P.wait = P.PACE_OFF; return end
    local M = _G.Minimap
    local t0 = (type(debugprofilestop) == "function") and debugprofilestop() or 0
    pcall(M.SetUsingSoftCursor, M, true)
    pcall(function()
        local last = math.min(sc.i + P.PER_FRAME - 1, #sc.pts)
        for k = sc.i, last do
            local key = sc.pts[k]
            local x, y = math.floor(key / 1000) * P.STEP, key % 1000 * P.STEP
            M:UpdateMouseoverAtPoint((sc.left + x) * sc.scale, (sc.bottom + y) * sc.scale)
            local data = C_TooltipInfo.GetMinimapMouseover()
            local lines = (type(data) == "table" and WC.isPlain(data)) and data.lines or nil
            if type(lines) == "table" and WC.isPlain(lines) then
                for _, line in ipairs(lines) do
                    local name = P.clean(type(line) == "table" and line.leftText or nil)
                    if name then
                        local h = sc.hits[name]
                        if not h then h = {}; sc.hits[name] = h end
                        h[key] = true
                    end
                end
            end
        end
        sc.i = last + 1
    end)
    pcall(M.SetUsingSoftCursor, M, false)
    if type(debugprofilestop) == "function" then sc.cost = sc.cost + (debugprofilestop() - t0) end
    if sc.i > #sc.pts then P.finish() end
end

-- the points of one name -> dots: touching points (8 around) are one dot, at their middle
function WC.mmp.finish()
    local P, sc = WC.mmp, WC.mmp.scan
    P.scan = nil
    local dots = {}
    local half = sc.width / 2
    local ypu = sc.radius / half                                  -- yards per minimap unit
    local f = sc.facing or 0
    for name, set in pairs(sc.hits) do
        local seen = {}
        for key in pairs(set) do
            if not seen[key] then
                local queue, n, sx, sy = { key }, 0, 0, 0
                seen[key] = true
                while #queue > 0 do
                    local k = table.remove(queue)
                    n = n + 1
                    sx, sy = sx + math.floor(k / 1000), sy + k % 1000
                    for _, d in ipairs({ -1001, -1000, -999, -1, 1, 999, 1000, 1001 }) do
                        local o = k + d
                        if set[o] and not seen[o] then seen[o] = true; queue[#queue + 1] = o end
                    end
                end
                -- the dot's middle from the minimap's middle (x right, y up), in yards; up is north, or the facing when it rotates
                local ux, uy = (sx / n) * P.STEP - half, (sy / n) * P.STEP - half
                local fwdN, fwdW, rgtN, rgtW = 1, 0, 0, -1
                if sc.rot then fwdN, fwdW, rgtN, rgtW = math.cos(f), math.sin(f), math.sin(f), -math.cos(f) end
                local north = (uy * fwdN + ux * rgtN) * ypu
                local west = (uy * fwdW + ux * rgtW) * ypu
                dots[#dots + 1] = { t = name, n = math.floor(north * 10 + 0.5) / 10, w = math.floor(west * 10 + 0.5) / 10, c = n,
                                    d2 = north * north + west * west }
            end
        end
    end
    table.sort(dots, function(a, b) return a.d2 < b.d2 end)
    while #dots > P.MAX_DOTS do table.remove(dots) end
    for _, d in ipairs(dots) do d.d2 = nil end
    -- the pace: moving -> fast; standing still or finding nothing -> slower
    local moved = true
    if sc.wx and P.lastX then moved = (sc.wx - P.lastX) ^ 2 + (sc.wy - P.lastY) ^ 2 > P.MOVED_YD ^ 2 end
    P.lastX, P.lastY = sc.wx, sc.wy
    P.empty = (#dots == 0) and math.min(P.empty + 1, 10) or 0
    P.wait = (#dots == 0 and P.empty >= 3) and P.PACE_EMPTY or (moved and P.PACE_MOVING or P.PACE_STILL)
    P.switched = false                                             -- a scan has run since the last switch
    P.dots, P.cost, P.np, P.m, P.r = dots, sc.cost, #sc.pts, sc.m, sc.radius
    P.send(false)
end

-- the document: the dots (name, yards north / west of the player), the options, the cost; on a change, else every HEARTBEAT s
function WC.mmp.send(force)
    local P = WC.mmp
    local t = (type(GetTime) == "function") and GetTime() or 0
    local parts = {}
    for _, d in ipairs(P.dots or {}) do parts[#parts + 1] = d.t .. math.floor(d.n / 3) .. ":" .. math.floor(d.w / 3) end
    table.sort(parts)
    local sig = P.opt .. "|" .. tostring(P.why) .. "|" .. table.concat(parts, ",")
    if not force and sig == P.sig and t - P.sentAt < P.HEARTBEAT then return end
    if not force and t - P.sentAt < P.SEND_MIN then return end
    P.sig, P.sentAt = sig, t
    pcall(function()
        WC.send(WC.encode({ type = "mmprobe", v = P.V, m = P.m, r = P.r and math.floor(P.r + 0.5) or nil, d = P.dots or {},
            o = P.opt, why = P.why, ms = P.cost and math.floor(P.cost * 10 + 0.5) / 10 or nil, np = P.np, ta = P.tracking() }))
        WC.push()
    end)
end

-- the minimap's tracking types that are on (names), for the report "what the probe can see"
function WC.mmp.tracking()
    local out = {}
    if type(C_Minimap) ~= "table" or type(C_Minimap.GetNumTrackingTypes) ~= "function" then return out end
    local okN, n = CALL(C_Minimap.GetNumTrackingTypes)
    if not okN or type(n) ~= "number" or not WC.isPlain(n) then return out end
    for i = 1, math.min(n, 40) do
        local okI, a, b, c = CALL(C_Minimap.GetTrackingInfo, i)
        local name, active
        if okI and type(a) == "table" then name, active = a.name, a.active else name, active = a, c end
        if active == true and type(name) == "string" and WC.isPlain(name) then out[#out + 1] = name end
    end
    return out
end

-- ---------------------------------------------------------------- the Find Herbs / Find Minerals AUTO-SWITCH (option 2)
-- the active finder's kind, the other finder's index; nil unless exactly one of the two is on and the other is listed
function WC.mmp.finders()
    local P = WC.mmp
    if type(C_Minimap) ~= "table" or type(C_Minimap.GetNumTrackingTypes) ~= "function" then return nil end
    local okN, n = CALL(C_Minimap.GetNumTrackingTypes)
    if not okN or type(n) ~= "number" or not WC.isPlain(n) then return nil end
    local onKind, onIdx, offIdx, active = nil, nil, nil, 0
    for i = 1, math.min(n, 40) do
        local okI, a, b, c = CALL(C_Minimap.GetTrackingInfo, i)
        local name, act, spell
        if okI and type(a) == "table" then name, act, spell = a.name, a.active, a.spellID else name, act = a, c end
        local kind = (WC.isPlain(spell) and P.FINDERS[spell]) or (type(name) == "string" and WC.isPlain(name) and P.FINDER_NAMES[name])
        if kind then
            if not WC.isPlain(act) then return nil end
            if act == true then onKind, onIdx, active = kind, i, active + 1 else offIdx = i end
        end
    end
    if active ~= 1 or not offIdx then return nil end
    return onKind, onIdx, offIdx
end

-- the other finder on, after SWITCH_YD walked and a scan since the last switch; true = it switched
function WC.mmp.switch()
    local P = WC.mmp
    if P.switched then return false end
    local kind, onIdx, offIdx = P.finders()
    if not kind or type(C_Minimap.SetTracking) ~= "function" then return false end
    if not P.switchFrom then P.switchFrom = onIdx end              -- remembered: put back when the option goes off
    local wx, wy = P.player()
    if not wx then return false end
    if P.swX and (wx - P.swX) ^ 2 + (wy - P.swY) ^ 2 < P.SWITCH_YD ^ 2 then return false end
    P.swX, P.swY = wx, wy
    if not CALL(C_Minimap.SetTracking, offIdx, true) then return false end
    P.switched = true
    return true
end

function WC.mmp.switchBack()
    local P = WC.mmp
    local from = P.switchFrom
    P.switchFrom, P.swX, P.swY = nil, nil, nil
    if not from or (type(InCombatLockdown) == "function" and InCombatLockdown()) then return end
    if type(C_Minimap) == "table" and type(C_Minimap.SetTracking) == "function" then CALL(C_Minimap.SetTracking, from, true) end
end

-- ---------------------------------------------------------------- SPARKLES (option 4): the soft-target settings, remembered
function WC.mmp.same(a, b) return a == b or (tonumber(a) ~= nil and tonumber(a) == tonumber(b)) end

function WC.mmp.cvar(name)
    local ok, v = CALL(function() return C_CVar.GetCVar(name) end)
    if ok and v ~= nil and WC.isPlain(v) then return v end
end

function WC.mmp.sparkles(on)
    local P = WC.mmp
    if type(C_CVar) ~= "table" or type(C_CVar.SetCVar) ~= "function" then return end
    if type(InCombatLockdown) == "function" and InCombatLockdown() then P.pend = true; return end
    P.pend = false
    if on then
        -- the Controller UI turns SoftTargetInteract on itself: only when it is off is it turned on (and remembered)
        local list = { unpack(P.SPARKLES) }
        local si = P.cvar("SoftTargetInteract")
        if si ~= nil and P.same(si, "0") then list[#list + 1] = { "SoftTargetInteract", "1" } end
        for _, e in ipairs(list) do
            local name, want = e[1], e[2]
            local old = P.cvar(name)
            if old ~= nil and not P.same(old, want) then
                CALL(function() C_CVar.SetCVar(name, want) end)
                if P.same(P.cvar(name), want) then
                    if P.saved[name] == nil then P.saved[name] = old end
                    P.applied[name] = want
                end
            elseif old ~= nil and P.saved[name] == nil and P.applied[name] == nil then
                -- already ours from an earlier session (no memory across a reload): put back the game's default later
                local okD, def = CALL(function() return C_CVar.GetCVarDefault(name) end)
                if okD and def ~= nil and WC.isPlain(def) and not P.same(def, want) and name ~= "SoftTargetInteract" then
                    P.saved[name], P.applied[name] = def, want
                end
            end
        end
    else
        for name, old in pairs(P.saved) do
            local mine = P.applied[name]
            if mine ~= nil and P.same(P.cvar(name), mine) then CALL(function() C_CVar.SetCVar(name, old) end) end
        end
        P.saved, P.applied = {}, {}
    end
end

-- the options from the app (op digit 2 on the pin lane): bits 1 probe, 2 auto-switch, 4 sparkles
function WC.mmp.setOpt(bits)
    local P = WC.mmp
    if type(bits) ~= "number" or bits < 0 or bits > 7 then return mrAck("opt", false, "bad options") end
    local was = P.opt
    P.opt = bits
    if was % 4 >= 2 and bits % 4 < 2 then P.switchBack() end
    if (was >= 4) ~= (bits >= 4) then P.sparkles(bits >= 4) end
    if bits % 2 == 0 then P.scan = nil; P.dots = {} end
    P.wait = 0.2
    mrAck("opt", true, nil, { o = bits })
    P.send(true)
end

function WC.mmp.tick(elapsed)
    local P = WC.mmp
    pcall(WC.mmHide.reassert, false)                               -- the hidden minimap's mask stays on (see WC.mmHide)
    if P.opt % 2 == 0 then return end                              -- the probe is off (the auto-switch rides its scans)
    if P.scan then return P.step() end
    P.wait = P.wait - (type(elapsed) == "number" and elapsed or 0)
    if P.wait <= 0 then P.start() end
end

function WC.mmp.init()
    local P = WC.mmp
    if P.frame or type(CreateFrame) ~= "function" then return end
    local ok, f = pcall(CreateFrame, "Frame")
    if not ok or not f then return end
    P.frame = f
    pcall(function()
        f:SetScript("OnUpdate", function(_, el) pcall(WC.mmp.tick, el) end)
        f:RegisterEvent("PLAYER_REGEN_ENABLED")
        f:RegisterEvent("PLAYER_LOGOUT")
        -- a quest's area changed: the hidden minimap's layers off again at once (WC.mmHide); a name this build lacks is skipped
        for _, ev in ipairs({ "QUEST_POI_UPDATE", "QUEST_WATCH_LIST_CHANGED", "AREA_POIS_UPDATED", "LOADING_SCREEN_DISABLED" }) do
            pcall(f.RegisterEvent, f, ev)
        end
        f:SetScript("OnEvent", function(_, event)
            if event ~= "PLAYER_REGEN_ENABLED" and event ~= "PLAYER_LOGOUT" then
                pcall(WC.mmHide.reassert, true)
            elseif event == "PLAYER_REGEN_ENABLED" then
                pcall(WC.mmHide.reassert, true)                    -- combat held the re-assert back
                if WC.mmp.pend then WC.mmp.sparkles(WC.mmp.opt >= 4) end
            elseif event == "PLAYER_LOGOUT" then
                -- the game as it was: a disabled add-on (or a changed option) leaves nothing behind for the next session
                WC.mmp.sparkles(false)
                WC.mmp.switchBack()
            end
        end)
    end)
end
WC.mmp.init()

-- ---------------------------------------------------------------- THE ROUTE'S PIN (b-route-waypoint)
-- The user's pick (2026-10-17, via tracking: "(b), the game user waypoint"; an explicit exception to leave-no-trace for this only): the
-- GAME's own arrow (the top screen's minimap / navigation) points at the route's next way - the tram, a gate, a flight master - while
-- the focused quest's route goes through one. The app works the route out and taps the pad row "uwp":
--   36, xh, xl, yh, yl   SET the pin at (x, y) on the player's map (base-36 digits: 1296 steps across); super-track it
--   37                   KEEP: the app is still routing (without one in KEEP_S the pin is cleared: an app that went away leaves none)
--   39                   CLEAR: the pin goes and the quest's own super-track comes back
-- Plain engine calls only (C_Map / C_SuperTrack, not protected; no Blizzard frame touched). NEVER STALE: cleared on the CLEAR tap, on
-- the KEEP timeout, when the player focuses something else in the game, at log out; and a pin of ours found at log in (the game kept
-- it through a crash) is cleared then (WowCompanionDB.routeWp names it). Never takes over the player's OWN pin.
-- While it stands, the map / quests documents keep reporting the quest it stands for as the focus (WC.routeWp.qid), and map.uwp
-- carries ours = 1. Every tap answers {"type":"routewp", op, ok, why}.
WC.routeWp = { active = false, qid = nil, m = nil, x = nil, y = nil, keepAt = 0, digits = nil, digitsAt = 0, ours = false }
local RWP = WC.routeWp
RWP.KEEP_S, RWP.DIGITS_S = 60, 2

function RWP.say(op, ok, why)
    pcall(function() WC.send(WC.encode({ type = "routewp", v = "rw1", op = op, ok = ok and true or false, why = why })); WC.push() end)
end

local function pinHere(x, y)
    local okH, has = CALL(function() return C_Map.HasUserWaypoint() end)
    if not okH or has ~= true then return false end
    local okG, p = CALL(function() return C_Map.GetUserWaypoint() end)
    if not okG or type(p) ~= "table" then return false end
    local okP, px, py = pcall(function() return p.position.x, p.position.y end)
    return okP and type(px) == "number" and math.abs(px - x) < 0.002 and math.abs(py - y) < 0.002
end

function RWP.set(x, y)
    local m = currentMap()
    if not m then return RWP.say("set", false, "no map") end
    -- the player's own pin is theirs: never moved or replaced
    local okH, has = CALL(function() return C_Map.HasUserWaypoint() end)
    if okH and has == true and not (RWP.active and pinHere(RWP.x, RWP.y)) then return RWP.say("set", false, "the player's own pin") end
    local okC, can = CALL(function() return C_Map.CanSetUserWaypointOnMap(m) end)
    if okC and can == false then return RWP.say("set", false, "no pins on this map") end
    if not RWP.active then RWP.qid = superTrackedQuest() end              -- the quest it stands for (given back at the end)
    RWP.ours = true
    local ok = pcall(function()
        local pt = (UiMapPoint and UiMapPoint.CreateFromCoordinates) and UiMapPoint.CreateFromCoordinates(m, x, y)
            or { uiMapID = m, position = CreateVector2D(x, y) }
        C_Map.SetUserWaypoint(pt)
        C_SuperTrack.SetSuperTrackedUserWaypoint(true)
    end)
    RWP.ours = false
    if not ok then return RWP.say("set", false, "the game refused") end
    RWP.active, RWP.m, RWP.x, RWP.y, RWP.keepAt = true, m, x, y, GetTime()
    if type(WowCompanionDB) ~= "table" then WowCompanionDB = {} end
    WowCompanionDB.routeWp = { m = m, x = x, y = y, qid = RWP.qid }
    RWP.say("set", true)
    snapshotSoon("waypoint")
end

-- [restore]: give the quest its super-track back (not when the player has just focused something else)
function RWP.clear(restore, why)
    if not RWP.active then
        if type(WowCompanionDB) == "table" then WowCompanionDB.routeWp = nil end
        return
    end
    RWP.ours = true
    pcall(function()
        if pinHere(RWP.x, RWP.y) then C_Map.ClearUserWaypoint() end
        C_SuperTrack.SetSuperTrackedUserWaypoint(false)
        if restore and RWP.qid and RWP.qid > 0 then C_SuperTrack.SetSuperTrackedQuestID(RWP.qid) end
    end)
    RWP.ours = false
    RWP.active, RWP.qid, RWP.m, RWP.x, RWP.y = false, nil, nil, nil, nil
    if type(WowCompanionDB) == "table" then WowCompanionDB.routeWp = nil end
    RWP.say("clear", true, why)
    snapshotSoon("waypoint")
end

-- a pin of ours the game kept through a crash or a kill: cleared at log in, the quest's super-track given back
function RWP.stale()
    local s = type(WowCompanionDB) == "table" and WowCompanionDB.routeWp or nil
    if type(s) ~= "table" then return end
    if type(s.x) == "number" and type(s.y) == "number" and pinHere(s.x, s.y) then
        RWP.active, RWP.qid, RWP.m, RWP.x, RWP.y = true, s.qid, s.m, s.x, s.y
        RWP.clear(true, "left from before")
    else
        WowCompanionDB.routeWp = nil
    end
end

if WC.pad and type(WC.pad.op) == "function" then
    WC.pad.op("uwp", "uwp", function(_, col)
        if type(col) ~= "number" then return end
        if col == 39 then RWP.digits = nil; return RWP.clear(true, "asked") end
        if col == 37 then RWP.keepAt = GetTime(); return end
        if col == 36 then RWP.digits = {}; RWP.digitsAt = GetTime(); return end
        if col <= 35 and RWP.digits and GetTime() - RWP.digitsAt <= RWP.DIGITS_S then
            local d = RWP.digits
            d[#d + 1] = col
            if #d == 4 then
                RWP.digits = nil
                RWP.set((d[1] * 36 + d[2]) / 1295, (d[3] * 36 + d[4]) / 1295)
            end
        end
    end)
end

do
    local f = CreateFrame("Frame")
    for _, e in ipairs({ "PLAYER_ENTERING_WORLD", "PLAYER_LOGOUT", "SUPER_TRACKING_CHANGED" }) do pcall(function() f:RegisterEvent(e) end) end
    f:SetScript("OnEvent", function(_, e)
        if e == "PLAYER_ENTERING_WORLD" then
            C_Timer.After(1, function() pcall(RWP.stale) end)
        elseif e == "PLAYER_LOGOUT" then
            pcall(RWP.clear, true, "log out")
        elseif e == "SUPER_TRACKING_CHANGED" and RWP.active and not RWP.ours then
            -- the player focused something else in the game (or cleared the pin): ours goes, their choice stands
            local okU, onPin = CALL(function() return C_SuperTrack.IsSuperTrackingUserWaypoint() end)
            if not (okU and onPin == true) then pcall(RWP.clear, false, "the player chose another") end
        end
    end)
    -- the KEEP timeout: an app that stopped routing (closed, killed) leaves no pin
    C_Timer.NewTicker(10, function()
        if RWP.active and GetTime() - RWP.keepAt > RWP.KEEP_S then pcall(RWP.clear, true, "no word from the app") end
    end)
end

-- ---------------------------------------------------------------- POLESTAR AS THE ROUTER (b-route-polestar)
-- The user (2026-10-17): "Quest tracking: Vanilla | QuestTrail | Polestar", the setting deciding what the map's line and arrows follow;
-- with Polestar (downloaded on opt-in, never bundled; GPL-3) the focused quest's goal is handed to Polestar's PUBLIC Nav API
-- (docs/ARCHITECTURE.md "Nav API", version 1: Navigate(owner, uiMapID, x, y, title) / Cancel(owner) / Active()) and its walking route
-- read back (Polestar.Nav.GetRouteGeometry(): each leg's kind and points {c, wx, wy}) for the app to draw. Polestar draws its own
-- arrow as always; nothing of the game is changed by this. The app taps the pad row "pnav":
--   36, m1, m2, m3, x1, x2, y1, y2   GO: the target (uiMapID in three base-36 digits, x / y in two each: 1296 steps)
--   37                                KEEP: the app still wants it (none for KEEP_S: cancelled)
--   39                                CANCEL
-- Answers {"type":"pnav", op, ok, why, have} (have = Polestar's API is there); the route as {"type":"pnavroute", legs = {{k, c, p}}}
-- (p = wx, wy pairs, at most PTS points in all), sent when it changes.
WC.pnav = { active = false, keepAt = 0, digits = nil, digitsAt = 0, sig = nil, sentAt = 0, KEEP_S = 60, PTS = 240, OWNER = "DSForever" }
function WC.pnav.api()
    local P = _G.Polestar
    if type(P) == "table" and type(P.Nav) == "table" and type(P.Nav.API) == "table" and type(P.Nav.API.Navigate) == "function" then
        return P.Nav.API
    end
end
function WC.pnav.say(op, ok, why)
    pcall(function()
        WC.send(WC.encode({ type = "pnav", v = "pn1", op = op, ok = ok and true or false, why = why, have = WC.pnav.api() ~= nil }))
        WC.push()
    end)
end
function WC.pnav.go(m, x, y)
    local api = WC.pnav.api()
    if not api then return WC.pnav.say("go", false, "no Polestar") end
    local okN, res = pcall(api.Navigate, WC.pnav.OWNER, m, x, y, "DS Forever")
    if not okN or res == false then return WC.pnav.say("go", false, okN and "refused" or tostring(res):sub(1, 120)) end
    WC.pnav.active, WC.pnav.keepAt, WC.pnav.sig = true, GetTime(), nil
    WC.pnav.say("go", true)
end
function WC.pnav.cancel(why)
    if not WC.pnav.active then return end
    local api = WC.pnav.api()
    if api and type(api.Cancel) == "function" then pcall(api.Cancel, WC.pnav.OWNER) end
    WC.pnav.active, WC.pnav.sig = false, nil
    WC.pnav.say("cancel", true, why)
end
-- the route Polestar is guiding now, as legs of world points (only while the guidance is ours)
function WC.pnav.route()
    local P = _G.Polestar
    local api = WC.pnav.api()
    if not (P and api and type(P.Nav.GetRouteGeometry) == "function") then return nil end
    if type(api.Active) == "function" then
        local okA, act, owner = pcall(api.Active)
        if okA and (not act or owner ~= WC.pnav.OWNER) then return nil end
    end
    local okG, legs = pcall(P.Nav.GetRouteGeometry)
    if not okG or type(legs) ~= "table" then return nil end
    local n = 0
    for _, leg in ipairs(legs) do if type(leg) == "table" and type(leg.pts) == "table" then n = n + #leg.pts end end
    local step = math.max(1, math.ceil(n / WC.pnav.PTS))
    local out, sig = {}, {}
    for _, leg in ipairs(legs) do
        if type(leg) == "table" and type(leg.pts) == "table" then
            local p, c = {}, nil
            local cnt = #leg.pts
            for i = 1, cnt do
                if i == 1 or i == cnt or (i - 1) % step == 0 then
                    local pt = leg.pts[i]
                    if type(pt) == "table" then
                        local wx, wy = pt.wx or pt[2], pt.wy or pt[3]
                        c = c or pt.c or pt[1]
                        if type(wx) == "number" and type(wy) == "number" then
                            p[#p + 1] = math.floor(wx * 10 + 0.5) / 10; p[#p + 1] = math.floor(wy * 10 + 0.5) / 10
                        end
                    end
                end
            end
            local k = type(leg.kind) == "string" and leg.kind or "walk"
            if #p >= 4 then
                out[#out + 1] = { k = k, c = type(c) == "number" and c or nil, p = p }
                sig[#sig + 1] = k .. #p .. ":" .. p[1] .. "," .. p[#p]
            end
        end
    end
    return out, table.concat(sig, "|")
end

if WC.pad and type(WC.pad.op) == "function" then
    WC.pad.op("pnav", "pnav", function(_, col)
        if type(col) ~= "number" then return end
        local P = WC.pnav
        if col == 39 then P.digits = nil; return P.cancel("asked") end
        if col == 37 then P.keepAt = GetTime(); return end
        if col == 36 then P.digits = {}; P.digitsAt = GetTime(); return end
        if col <= 35 and P.digits and GetTime() - P.digitsAt <= 2 then
            local d = P.digits
            d[#d + 1] = col
            if #d == 7 then
                P.digits = nil
                P.go((d[1] * 36 + d[2]) * 36 + d[3], (d[4] * 36 + d[5]) / 1295, (d[6] * 36 + d[7]) / 1295)
            end
        end
    end)
end

do
    local f = CreateFrame("Frame")
    pcall(function() f:RegisterEvent("PLAYER_LOGOUT") end)
    f:SetScript("OnEvent", function() pcall(WC.pnav.cancel, "log out") end)
    -- once a second while ours: the route when it changed (and every 10 s anyway); no word from the app for KEEP_S: cancelled
    C_Timer.NewTicker(1, function()
        local P = WC.pnav
        if not P.active then return end
        if GetTime() - P.keepAt > P.KEEP_S then return pcall(P.cancel, "no word from the app") end
        local okR, legs, sig = pcall(P.route)
        if not okR or not legs then return end
        if sig ~= P.sig or GetTime() - P.sentAt > 10 then
            P.sig, P.sentAt = sig, GetTime()
            pcall(function() WC.send(WC.encode({ type = "pnavroute", v = "pn1", legs = legs })); WC.push() end)
        end
    end)
end

-- ---------------------------------------------------------------- QUESTTRAIL'S ARROW, READ (b-route-questtrail)
-- The user (2026-10-17): "(a) + (b)" - with Settings' "Quest tracking: QuestTrail" the map's line and arrow follow QuestTrail's own
-- guidance arrow (QuestTrail by Krom, MIT; player-chosen). QuestTrail has no public API (3.1.0: everything in its private
-- namespace), so this READS its compass frame: QuestTrailArrowFrame.bearing (its own, exact: 0 = north, counter-clockwise, like
-- GetPlayerFacing; toward the target or a point of its golden path ahead), the label's words and the distance's ("%d m", metres).
-- Should QuestTrail ever offer a global QuestTrail_GetTarget() (the request the user may post), its answer is taken first:
-- {kind, questID, uiMapID, x, y, text, path = {{uiMapID, x, y}, ...}}. Nothing of QuestTrail or the game is changed; read only.
-- On every position tick: {src, b, yd, t, d, api = {m, x, y, q, k, t}}; the full map document adds qtrailPath (the API's path).
WC.QTRAIL_PTS = 60
function WC.qtrailText(fs)
    if type(fs) ~= "table" or type(fs.GetText) ~= "function" then return nil end
    local ok, t = pcall(fs.GetText, fs)
    if not ok or type(t) ~= "string" or (WC.isPlain and not WC.isPlain(t)) then return nil end
    t = t:gsub("|c%x%x%x%x%x%x%x%x", ""):gsub("|r", "")
    if t == "" then return nil end
    return t:sub(1, 80)
end
function WC.qtrailApi()
    local api = _G.QuestTrail_GetTarget
    if type(api) ~= "function" then return nil end
    local ok, t = pcall(api)
    if not ok or type(t) ~= "table" then return nil end
    local m, x, y = t.uiMapID, t.x, t.y
    if type(m) ~= "number" or type(x) ~= "number" or type(y) ~= "number" then return nil end
    if WC.isPlain and not (WC.isPlain(m) and WC.isPlain(x) and WC.isPlain(y)) then return nil end
    return {
        m = m, x = math.floor(x * 10000 + 0.5) / 10000, y = math.floor(y * 10000 + 0.5) / 10000,
        q = type(t.questID) == "number" and t.questID or nil,
        k = type(t.kind) == "string" and t.kind:sub(1, 16) or nil,
        t = type(t.text) == "string" and t.text:sub(1, 80) or nil,
    }, t.path
end
function WC.qtrailRead()
    local out = nil
    local api = WC.qtrailApi()
    if api then out = { src = "api", api = api } end
    local f = _G.QuestTrailArrowFrame
    if type(f) == "table" and type(f.IsShown) == "function" and f:IsShown() then
        out = out or { src = "arrow" }
        local b = f.bearing
        local arrowOn = type(f.arrow) == "table" and type(f.arrow.IsShown) == "function" and f.arrow:IsShown()
        if arrowOn and type(b) == "number" and (not WC.isPlain or WC.isPlain(b)) then out.b = math.floor(b * 1000 + 0.5) / 1000 end
        out.t = WC.qtrailText(f.label)
        local d = WC.qtrailText(f.dist)
        out.d = d
        local metres = d and tonumber(d:match("(%d+)%s*m$") or d:match("(%d+)%s*m%s"))
        if metres then out.yd = math.floor(metres / 0.9144 + 0.5) end
    end
    return out
end
-- the API's path for the full map document (only when the API gives one)
function WC.qtrailPath()
    local _, path = WC.qtrailApi()
    if type(path) ~= "table" then return nil end
    local n = #path
    if n < 2 then return nil end
    local step = math.max(1, math.ceil(n / WC.QTRAIL_PTS))
    local out = {}
    for i = 1, n, step do
        local p = path[i]
        if type(p) == "table" and type(p.uiMapID or p[1]) == "number" then
            local m, x, y = p.uiMapID or p[1], p.x or p[2], p.y or p[3]
            if type(x) == "number" and type(y) == "number" then
                out[#out + 1] = { m, math.floor(x * 10000 + 0.5) / 10000, math.floor(y * 10000 + 0.5) / 10000 }
            end
        end
    end
    return #out >= 2 and out or nil
end
