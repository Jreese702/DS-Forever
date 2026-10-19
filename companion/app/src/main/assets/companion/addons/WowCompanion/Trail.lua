-- WowCompanion / Trail.lua
-- A 30 Hz ring of player positions, drained into whatever the next push carries.
--
-- WHY THIS EXISTS. The export channel costs ~48 KiB PER FLUSH whatever it carries (Sink.lua
-- explains the mechanism). To make the arrow glide, the flush rate had been pushed to 9.4/s
-- -- 292 KB/s, and MEASURED at 2,741 padding lines to carry 56 lines of real data, about a
-- gigabyte an hour. It also cost the game roughly one dropped frame in ten, which is what the
-- player felt as jumping being laggy while movement was not: a stutter is invisible on an
-- analog stick and obvious on a discrete button.
--
-- That was the wrong lever. Smoothness does not need a fast flush, it needs closely spaced
-- SAMPLES -- and samples are free here, because payload bytes DISPLACE pad bytes one for one.
-- So: sample at 30 Hz, ship the batch inside a slow flush, and let the app interpolate at the
-- screen's own rate. Sample rate sets smoothness, flush rate sets cost, and they are now
-- independent. The flush rate went DOWN and the map got smoother.
--
-- Latency is the one thing this does not fix; the app hides that separately by predicting the
-- arrow forward from the newest samples (MapView.smoothPos) rather than snapping to them.
local ADDON = ...
local WC = _G.WowCompanion

local SAMPLE_DT = 1 / 30
local MAX_SAMPLES = 90              -- 3 s of history; a drain normally takes 3

local ring, count = {}, 0
local acc = 0
local lastT = nil
local mapID = nil

local f = CreateFrame("Frame")
f:SetScript("OnUpdate", function(_, elapsed)
    acc = acc + elapsed
    if acc < SAMPLE_DT then return end
    local dt = acc
    acc = 0
    if not mapID then return end
    local p = C_Map.GetPlayerMapPosition(mapID, "player")
    if not p then return end
    local x, y = p:GetXY()
    if not x then return end
    if count >= MAX_SAMPLES then
        -- Drop the OLDEST, never the newest: if the app has stopped reading we would rather
        -- hand it a short recent history than a long stale one.
        table.remove(ring, 1)
        count = count - 1
    end
    count = count + 1
    ring[count] = { dt = dt, x = x, y = y, f = GetPlayerFacing() or 0 }
end)

--- The map the sampler should ask about. Set from the position ticker, which already knows.
function WC.trailMap(id)
    mapID = id
end

--- Everything sampled since the last call, as one compact string, and clear the ring.
--- Format: "dtms,x10000,y10000,facing1000;..." oldest first. Integers only: the app parses
--- this on its own thread and a JSON array of objects would cost ten times the characters
--- for no extra information.
function WC.drainTrail()
    if count == 0 then return nil end
    local parts = {}
    for i = 1, count do
        local s = ring[i]
        parts[i] = string.format("%d,%d,%d,%d",
            math.floor(s.dt * 1000 + 0.5),
            math.floor(s.x * 10000 + 0.5),
            math.floor(s.y * 10000 + 0.5),
            math.floor((s.f or 0) * 1000 + 0.5))
        ring[i] = nil
    end
    count = 0
    return table.concat(parts, ";")
end

-- ================================================================== THE COMPASS BAR (live layer, part 1)
-- The user signed off "compass F" (2026-10-19; spec: safepath-work/design/art-pass/COMPASS_SPEC_F.md): a slim dark shadow band at
-- the top of the game screen, the heading letters on one line over +-90 degrees of the CHARACTER's facing (GetPlayerFacing: there is
-- no camera API), and markers sitting right over the letters with their distance beneath: the next checkpoint (our pin), the
-- focused quest, the corpse. A letter a marker covers lifts above it, a size smaller (both read; the marker keeps its bearing).
-- Our own plain frame on UIParent: textures and font strings only, the game's own atlases by name at runtime (nothing of the game
-- ships), no secure template, nothing of the game's written, no CVar, no binding. Hidden in instances, when the facing is unknown,
-- while the world map is open, and when the player turned it off (WowCompanionDB.compass == false; the app's Settings > Top screen >
-- "Compass bar", through Interact's two iact cells, ACT.compass).
WC.compass = { V = "cp2", pool = {}, used = {} }
do
    local C = WC.compass
    local PI = math.pi
    local atan2 = math.atan2 or function(y, x) return math.atan(y, x) end     -- (the game's Lua 5.1 has math.atan2; the harness's not)
    -- the spec's sizes, in the Thor's top-screen pixels (1920 x 1080); C.k turns them into UI units
    C.SPEC = { w = 900, h = 76, top = 16, line = 32, half = 420, tick = 8, letter = 22, letter2 = 17, dist = 12, lift = 25, near = 22,
               edge = 48 }
    C.MARK = {
        pin    = { atlas = "Waypoint-MapPin-Tracked",   size = 28, red = false },
        quest  = { atlas = "questnormal",               size = 32, red = false },
        corpse = { atlas = "Navigation-Tombstone-Icon", size = 26, red = false },
    }
    C.ORDER = { "corpse", "pin", "quest" }               -- drawn back to front (the quest in front)
    -- the letters by their angle COUNTER-CLOCKWISE from north (the game's facing convention): W is a quarter turn left of N
    C.NAMES = { [0] = "N", [45] = "NW", [90] = "W", [135] = "SW", [180] = "S", [225] = "SE", [270] = "E", [315] = "NE" }
    C.FONT = "Fonts\\FRIZQT__.TTF"

    local function num(v)
        if type(v) ~= "number" then return nil end
        if type(issecretvalue) == "function" and issecretvalue(v) then return nil end
        if v ~= v then return nil end
        return v
    end
    C.num = num

    function C.wanted()
        return not (type(WowCompanionDB) == "table" and WowCompanionDB.compass == false)          -- default ON
    end

    -- UI units per top-screen pixel
    function C.scale()
        local ok, _, ph = pcall(GetPhysicalScreenSize)
        local okH, uh = pcall(function() return UIParent:GetHeight() end)
        ph = ok and num(ph) or nil
        uh = okH and num(uh) or nil
        C.k = (ph and uh and ph > 0) and (uh / ph) or (768 / 1080)
        return C.k
    end
    function C.u(px) return px * (C.k or C.scale()) end

    -- the player's facing as counter-clockwise radians from north (the game's own convention), or nil
    function C.facing()
        local ok, f = pcall(GetPlayerFacing)
        return ok and num(f) or nil
    end

    -- a map point -> world yards { x = north, y = west } and its continent, through the game's own conversion
    function C.world(uiMapID, x, y)
        if not (num(uiMapID) and num(x) and num(y)) then return nil end
        local ok, cont, v = pcall(function() return C_Map.GetWorldPosFromMapPos(uiMapID, CreateVector2D(x, y)) end)
        if not ok or not v then return nil end
        local okX, wx, wy = pcall(function() return v:GetXY() end)
        if not okX or not num(wx) or not num(wy) then return nil end
        return { x = wx, y = wy, cont = cont }
    end

    function C.me()
        local ok, m = pcall(C_Map.GetBestMapForUnit, "player")
        if not ok or not num(m) then return nil end
        local okP, p = pcall(C_Map.GetPlayerMapPosition, m, "player")
        if not okP or not p then return nil end
        local okX, x, y = pcall(function() return p:GetXY() end)
        if not okX then return nil end
        local w = C.world(m, x, y)
        if w then w.map = m end
        return w
    end

    -- the markers' world spots (each nil when the game has none)
    function C.targets(me)
        local t = {}
        -- the next checkpoint: our pin (Map.lua places it), read back from the game
        local okW, wp = pcall(C_Map.GetUserWaypoint)
        if okW and type(wp) == "table" and wp.position then
            local okX, x, y = pcall(function() return wp.position:GetXY() end)
            if okX then t.pin = C.world(wp.uiMapID, x, y) end
        end
        -- the focused quest's goal (not while the game super-tracks our pin: that one IS the pin)
        local okS, onPin = pcall(function() return C_SuperTrack.IsSuperTrackingUserWaypoint() end)
        local okQ, qid = pcall(function() return C_SuperTrack.GetSuperTrackedQuestID() end)
        qid = okQ and num(qid) or nil
        -- (test13: GetNextWaypoint answers only for a quest in ANOTHER zone, and this client has no QuestPOIGetIconInfo: the
        -- quest's spot on the player's own map comes first, through the same reads Map.lua uses for its quest markers)
        C.qsrc = nil
        if qid and qid > 0 and not (okS and onPin == true) then
            if me and me.map then
                local okF, fx, fy = pcall(C_QuestLog.GetNextWaypointForMap, qid, me.map)
                if okF and num(fx) and num(fy) then
                    t.quest, C.qsrc = C.world(me.map, fx, fy), "wpmap"
                else
                    local okL, qs = pcall(C_QuestLog.GetQuestsOnMap, me.map)
                    if okL and type(qs) == "table" then
                        for _, q in ipairs(qs) do
                            local okQ2, id, qx, qy = pcall(function() return q.questID, q.x, q.y end)
                            if okQ2 and id == qid and num(qx) and num(qy) then
                                t.quest, C.qsrc = C.world(me.map, qx, qy), "onmap"
                                break
                            end
                        end
                    end
                end
            end
            if not t.quest then
                local okN, m, x, y = pcall(C_QuestLog.GetNextWaypoint, qid)
                if okN and num(m) and num(x) then t.quest, C.qsrc = C.world(m, x, y), "wp" end
            end
            C.qsrc = C.qsrc or "none"
        end
        C.qid = qid
        -- the corpse, while dead or a ghost
        local okD, dead = pcall(UnitIsDeadOrGhost, "player")
        if okD and dead == true and me and me.map then
            local okC, v = pcall(C_DeathInfo.GetCorpseMapPosition, me.map)
            if okC and v then
                local okX, x, y = pcall(function() return v:GetXY() end)
                if okX then t.corpse = C.world(me.map, x, y) end
            end
        end
        -- only spots on the player's own continent
        for k, w in pairs(t) do
            if not me or (w.cont ~= nil and me.cont ~= nil and w.cont ~= me.cont) then t[k] = nil end
        end
        return t
    end

    -- angles: counter-clockwise radians, as the game's facing; rel > 0 = to the LEFT of where the character faces
    function C.wrap(a)
        a = (a + PI) % (2 * PI)
        return a - PI
    end
    function C.bearing(me, w)                      -- from the player to w, CCW from north (world x north, y west)
        local dx, dy = w.x - me.x, w.y - me.y
        return atan2(dy, dx), math.sqrt(dx * dx + dy * dy)
    end
    function C.distText(d)
        d = math.floor(d + 0.5)
        if d > 50 then d = math.floor(d / 5 + 0.5) * 5 end
        return d .. " yd"
    end

    -- the layout, pure (the harness checks it): facing + me + targets -> what goes where, in spec pixels from the band's centre
    function C.layout(facing, me, targets)
        local S = C.SPEC
        local out = { ticks = {}, letters = {}, marks = {} }
        for _, key in ipairs(C.ORDER) do
            local w = targets[key]
            if w and me then
                local b, d = C.bearing(me, w)
                local rel = C.wrap(b - facing)
                local off = math.abs(rel) > PI / 2
                local x
                if off then
                    x = (rel > 0) and -(S.w / 2 - S.edge) or (S.w / 2 - S.edge)
                else
                    x = -rel / (PI / 2) * S.half
                end
                out.marks[#out.marks + 1] = { key = key, x = x, off = off, side = (rel > 0) and -1 or 1, dist = C.distText(d), rel = rel }
            end
        end
        for deg = 0, 345, 15 do
            local rel = C.wrap(math.rad(deg) - facing)
            if math.abs(rel) <= PI / 2 then
                local x = -rel / (PI / 2) * S.half
                local fade = 1 - (math.abs(rel) / (PI / 2)) ^ 2 * 0.65
                local name = C.NAMES[deg]
                if name then
                    local size = (#name == 1) and S.letter or S.letter2
                    local lift = false
                    -- (an off-bar marker pinned at an end lifts the end's letter too: the spec's rule, widened after the first render)
                    for _, m in ipairs(out.marks) do
                        if math.abs(m.x - x) < S.near + (m.off and 14 or 0) then lift = true end
                    end
                    out.letters[#out.letters + 1] = { name = name, x = x, fade = fade, size = lift and (size - 5) or size,
                                                      y = lift and (S.line - S.lift) or S.line, lifted = lift }
                else
                    out.ticks[#out.ticks + 1] = { x = x, fade = fade }
                end
            end
        end
        return out
    end

    -- ---------------------------------------------------------------- the frames (built on the first show)
    local function plain(parent, layer, sub)
        local t = parent:CreateTexture(nil, layer or "BACKGROUND", nil, sub)
        t:SetTexture("Interface\\Buttons\\WHITE8X8")
        return t
    end
    local function grad(t, orient, a1, a2)
        pcall(function() t:SetGradient(orient, CreateColor(0, 0, 0, a1), CreateColor(0, 0, 0, a2)) end)
    end

    function C.build()
        if C.frame then return C.frame end
        C.scale()
        local f = CreateFrame("Frame", nil, UIParent)
        f:SetFrameStrata("MEDIUM")
        f:EnableMouse(false)
        f:Hide()
        C.frame = f
        local S, u = C.SPEC, C.u
        f:SetSize(u(S.w), u(S.h))
        f:SetPoint("TOP", UIParent, "TOP", 0, -u(S.top))
        -- the ground: a soft black band; its two halves fade to the top and bottom edges, its two ends fade out
        local A, END = 0.69, 0.14
        C.ground = {}
        for row = 1, 2 do
            for col = 1, 3 do
                local t = plain(f)
                local x0 = (col == 1) and 0 or (col == 2) and END or (1 - END)
                local x1 = (col == 1) and END or (col == 2) and (1 - END) or 1
                t:SetPoint("TOPLEFT", f, "TOPLEFT", u(S.w) * x0, -(row - 1) * u(S.h) / 2)
                t:SetPoint("BOTTOMRIGHT", f, "TOPLEFT", u(S.w) * x1, -row * u(S.h) / 2)
                if col == 2 then
                    if row == 1 then grad(t, "VERTICAL", A, 0) else grad(t, "VERTICAL", 0, A) end     -- (bottom, top)
                elseif col == 1 then
                    grad(t, "HORIZONTAL", 0, A * 0.55)
                else
                    grad(t, "HORIZONTAL", A * 0.55, 0)
                end
                C.ground[#C.ground + 1] = t
            end
        end
        -- the 30 Hz redraw, only while shown
        local acc2 = 0
        f:SetScript("OnUpdate", function(_, el)
            acc2 = acc2 + (el or 0)
            if acc2 < 1 / 30 then return end
            acc2 = 0
            pcall(C.draw)
        end)
        return f
    end

    -- pooled pieces, handed out per draw
    local function take(kind)
        C.pool[kind] = C.pool[kind] or {}
        local list = C.pool[kind]
        C.used[kind] = (C.used[kind] or 0) + 1
        local i = C.used[kind]
        if not list[i] then
            local f = C.frame
            if kind == "font" or kind == "dist" then
                list[i] = f:CreateFontString(nil, "OVERLAY")
            elseif kind == "shadow" then
                list[i] = f:CreateTexture(nil, "ARTWORK", nil, 1)
            elseif kind == "mark" or kind == "arrow" then
                list[i] = f:CreateTexture(nil, "ARTWORK", nil, 3)
            else
                list[i] = plain(f, "ARTWORK", 0)
            end
        end
        local o = list[i]
        o:ClearAllPoints()
        o:Show()
        return o
    end
    local function finish()
        for kind, list in pairs(C.pool) do
            for i = (C.used[kind] or 0) + 1, #list do list[i]:Hide() end
        end
        C.used = {}
    end

    function C.draw()
        local f = C.frame
        if not f then return end
        local facing = C.facing()
        if not facing then f:Hide(); return end
        local me = C.me()
        local tg = C.targets(me)
        local L = C.layout(facing, me, tg)
        C.last = L
        C.diag(facing, me, tg, L)
        local u, S = C.u, C.SPEC
        C.used = {}
        for _, t in ipairs(L.ticks) do
            local tx = take("tick")
            tx:SetVertexColor(200 / 255, 180 / 255, 120 / 255, 0.67 * t.fade)
            tx:SetSize(u(2), u(S.tick))
            tx:SetPoint("CENTER", f, "TOP", u(t.x), -u(S.line))
        end
        for _, l in ipairs(L.letters) do
            local fs = take("font")
            fs:SetFont(C.FONT, u(l.size), "OUTLINE")
            if l.name == "N" then fs:SetTextColor(l.fade, 0.25 * l.fade, 0.25 * l.fade, 1) else fs:SetTextColor(l.fade, 0.82 * l.fade, 0, 1) end
            fs:SetText(l.name)
            fs:SetPoint("CENTER", f, "TOP", u(l.x), -u(l.y))
        end
        for _, m in ipairs(L.marks) do
            local spec = C.MARK[m.key]
            local sz = u(spec.size)
            -- its edge: the same art in black, a little larger, under it (the silhouette's dark rim, as the game outlines text)
            local sh = take("shadow")
            pcall(function() sh:SetAtlas(spec.atlas) end)
            sh:SetVertexColor(0, 0, 0, 0.85)
            sh:SetSize(sz + u(3), sz + u(3))
            sh:SetPoint("CENTER", f, "TOP", u(m.x), -u(S.line))
            local tx = take("mark")
            pcall(function() tx:SetAtlas(spec.atlas) end)
            tx:SetVertexColor(1, 1, 1, 1)
            tx:SetSize(sz, sz)
            tx:SetPoint("CENTER", f, "TOP", u(m.x), -u(S.line))
            local d = take("dist")
            d:SetFont(C.FONT, u(S.dist), "OUTLINE")
            if spec.red then d:SetTextColor(1, 0.25, 0.25, 1) else d:SetTextColor(1, 1, 1, 1) end
            d:SetText(m.dist)
            d:SetPoint("TOP", f, "TOP", u(m.x), -u(S.line + spec.size / 2 + 3))
            if m.off then                       -- off the bar: the game's nav arrow, pointing outward
                local a = take("arrow")
                pcall(function() a:SetAtlas("Navigation-Tracked-Arrow") end)
                a:SetVertexColor(1, 1, 1, 1)
                a:SetSize(u(14), u(14))
                pcall(function() a:SetRotation(m.side > 0 and -PI / 2 or PI / 2) end)
                a:SetPoint("CENTER", f, "TOP", u(m.x + m.side * (spec.size / 2 + 10)), -u(S.line))
            end
        end
        finish()
    end

    -- test13: one line every 5 s while shown, so a short log read tells what the compass has: the pin (and whether the game has a
    -- user waypoint at all, and Map's route pin), the focused quest and where its spot came from, how many markers it drew.
    -- It rides the next flush: no push of its own.
    C.diagAt = -100
    function C.diag(facing, me, tg, L)
        local now = (type(GetTime) == "function" and GetTime()) or 0
        if now - C.diagAt < 5 then return end
        C.diagAt = now
        local okH, has = pcall(function() return C_Map.HasUserWaypoint() end)
        local okS, onPin = pcall(function() return C_SuperTrack.IsSuperTrackingUserWaypoint() end)
        local route = type(WC.routeWp) == "table" and WC.routeWp.active and true or false
        local line = {
            type = "cap", probe = "compass", v = C.V,
            f = math.floor(facing * 1000 + 0.5) / 1000, map = me and me.map or nil, me = me and true or false,
            uwp = (okH and has == true) and true or false, st = (okS and onPin == true) and true or false, route = route,
            pin = tg.pin and true or false, qid = C.qid, qsrc = C.qsrc, quest = tg.quest and true or false,
            corpse = tg.corpse and true or false, n = #L.marks,
        }
        C.lastDiag = line
        pcall(function() WC.send(WC.encode(line)) end)
    end

    -- shown or not: the player's choice, outdoors, a known facing, the world map closed
    function C.shouldShow()
        if not C.wanted() then return false, "off" end
        local okI, inInst = pcall(IsInInstance)
        if okI and inInst == true then return false, "instance" end
        if not C.facing() then return false, "no facing" end
        local okM, mapUp = pcall(function() return WorldMapFrame and WorldMapFrame:IsShown() end)
        if okM and mapUp == true then return false, "world map" end
        return true
    end

    function C.refresh()
        local show, why = C.shouldShow()
        C.why = why
        if show then
            C.build()
            if not C.frame:IsShown() then
                C.frame:Show()
                pcall(C.draw)
            end
        elseif C.frame and C.frame:IsShown() then
            C.frame:Hide()
        end
        return show
    end

    -- the player's choice (Interact's ACT.compass, from the app's Settings row)
    function C.set(on)
        if type(WowCompanionDB) ~= "table" then WowCompanionDB = {} end      -- the table is everyone's: only the one key is ours
        WowCompanionDB.compass = on and true or false
        C.refresh()
        return true
    end

    -- the checker: twice a second (a few reads), and at once on the events that change it
    local ck = CreateFrame("Frame")
    local acc3 = 0
    ck:SetScript("OnUpdate", function(_, el)
        acc3 = acc3 + (el or 0)
        if acc3 < 0.5 then return end
        acc3 = 0
        pcall(C.refresh)
    end)
    for _, ev in ipairs({ "PLAYER_ENTERING_WORLD", "ZONE_CHANGED_NEW_AREA", "UI_SCALE_CHANGED", "DISPLAY_SIZE_CHANGED" }) do
        pcall(function() ck:RegisterEvent(ev) end)
    end
    ck:SetScript("OnEvent", function(_, ev)
        if ev == "UI_SCALE_CHANGED" or ev == "DISPLAY_SIZE_CHANGED" then
            -- the sizes are in UI units: the frame is rebuilt at the new scale on the next show
            if C.frame then C.frame:Hide() end
            C.frame, C.pool, C.used = nil, {}, {}
        end
        pcall(C.refresh)
    end)
end
