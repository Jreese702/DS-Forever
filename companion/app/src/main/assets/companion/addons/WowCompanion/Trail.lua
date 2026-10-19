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
WC.compass = { V = "cp7" }
do
    local C = WC.compass
    local PI = math.pi
    local atan2 = math.atan2 or function(y, x) return math.atan(y, x) end     -- (the game's Lua 5.1 has math.atan2; the harness's not)
    -- the spec's sizes, in the Thor's top-screen pixels (1920 x 1080); C.k turns them into UI units
    C.SPEC = { w = 860, h = 76, top = 4, line = 32, half = 400, tick = 8, letter = 22, letter2 = 17, dist = 12, lift = 25, near = 22,
               edge = 48 }
    C.BAND = "Interface\\AddOns\\WowCompanion\\Media\\compass_band"       -- our own soft band (tools/make_compass_band.py)
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

    -- a Vector2D's x, y: its GetXY when the mixin is there, else its plain fields (test15: no markers at all; Coords.lua: "the C API
    -- hands back a table with .x/.y, and :GetXY() is FrameXML sugar that may or may not be loaded")
    function C.xy(v)
        if type(v) ~= "table" then return nil end
        local ok, x, y = pcall(function() return v:GetXY() end)
        if ok and num(x) and num(y) then return x, y end
        local ok2, x2, y2 = pcall(function() return v.x, v.y end)
        if ok2 and num(x2) and num(y2) then return x2, y2 end
        return nil
    end

    -- cp7, the user: "Try the experiment": the compass follows the CAMERA while the game's navigation point (the super-tracked quest
    -- or pin, projected by the engine) is on screen; off by default (WowCompanionDB.compassCam, the app's second Top screen row)
    function C.camWanted()
        return type(WowCompanionDB) == "table" and WowCompanionDB.compassCam == true
    end

    function C.wanted()
        return not (type(WowCompanionDB) == "table" and WowCompanionDB.compass == false)          -- default ON
    end

    -- UI units per spec pixel: the spec is drawn on a 1080-tall screen, so a spec pixel is 1/1080 of the screen's height in UIParent's
    -- own units, whatever the physical or render resolution (test13: the physical size made it 1.3x too big on the Thor)
    function C.scale()
        local okH, uh = pcall(function() return UIParent:GetHeight() end)
        uh = okH and num(uh) or nil
        C.k = (uh and uh > 0) and (uh / 1080) or (768 / 1080)
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
        local wx, wy = C.xy(v)
        if not wx then return nil end
        return { x = wx, y = wy, cont = num(cont) }
    end

    function C.me()
        local ok, m = pcall(C_Map.GetBestMapForUnit, "player")
        if not ok or not num(m) then return nil end
        local okP, p = pcall(C_Map.GetPlayerMapPosition, m, "player")
        if not okP or not p then return nil end
        local x, y = C.xy(p)
        if not x then return nil end
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
            local x, y = C.xy(wp.position)
            if x then t.pin = C.world(wp.uiMapID, x, y) end
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
                local x, y = C.xy(v)
                if x then t.corpse = C.world(me.map, x, y) end
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
                local q = math.abs(rel) / (PI / 2)
                local fade = 1 - q * q * 0.8
                local name = C.NAMES[deg]
                if name then
                    local size = (#name == 1) and S.letter or S.letter2
                    local lift = false
                    -- (an off-bar marker pinned at an end lifts the end's letter too: the spec's rule, widened after the first render)
                    for _, m in ipairs(out.marks) do
                        if math.abs(m.x - x) < S.near + (m.off and 14 or 0) then lift = true end
                    end
                    out.letters[#out.letters + 1] = { deg = deg, name = name, x = x, fade = fade, size = lift and (size - 5) or size,
                                                      y = lift and (S.line - S.lift) or S.line, lifted = lift }
                else
                    out.ticks[#out.ticks + 1] = { deg = deg, x = x, fade = fade }
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
        -- the ground: our own soft band texture, its alpha already fading smoothly to every edge
        local g = f:CreateTexture(nil, "BACKGROUND")
        g:SetTexture(C.BAND)
        g:SetAllPoints(f)
        C.ground = { g }
        -- every frame while shown (cp5, the user: "the compass scrolling is kind of choppy"): one eased heading, pieces only moved
        f:SetScript("OnUpdate", function(_, el) pcall(C.step, el or 0) end)
        -- the pieces, made ONCE: a letter or a tick per 15 degrees, and one set per marker kind (their atlas set once)
        C.slot = {}
        for deg = 0, 345, 15 do
            if C.NAMES[deg] then
                local fs = f:CreateFontString(nil, "OVERLAY")
                fs:SetFont(C.FONT, u(#C.NAMES[deg] == 1 and S.letter or S.letter2), "OUTLINE")
                fs:SetText(C.NAMES[deg])
                if deg == 0 then fs:SetTextColor(1, 0.25, 0.25, 1) else fs:SetTextColor(1, 0.82, 0, 1) end
                C.slot[deg] = { fs = fs, lifted = false }
            else
                local t = plain(f, "ARTWORK", 0)
                t:SetSize(u(2), u(S.tick))
                C.slot[deg] = { tick = t }
            end
        end
        C.mk = {}
        for key, spec in pairs(C.MARK) do
            local sz = u(spec.size)
            local o = {}
            -- its edge: the same art in black, a little larger, under it (the silhouette's dark rim, as the game outlines text)
            o.sh = f:CreateTexture(nil, "ARTWORK", nil, 1)
            pcall(function() o.sh:SetAtlas(spec.atlas) end)
            o.sh:SetVertexColor(0, 0, 0, 0.85)
            o.sh:SetSize(sz + u(3), sz + u(3))
            o.tx = f:CreateTexture(nil, "ARTWORK", nil, 3)
            pcall(function() o.tx:SetAtlas(spec.atlas) end)
            o.tx:SetSize(sz, sz)
            o.d = f:CreateFontString(nil, "OVERLAY")
            o.d:SetFont(C.FONT, u(S.dist), "OUTLINE")
            if spec.red then o.d:SetTextColor(1, 0.25, 0.25, 1) else o.d:SetTextColor(1, 1, 1, 1) end
            o.a = f:CreateTexture(nil, "ARTWORK", nil, 3)
            pcall(function() o.a:SetAtlas("Navigation-Tracked-Arrow") end)
            o.a:SetSize(u(14), u(14))
            C.mk[key] = o
        end
        return f
    end

    -- the shown heading: a critically damped spring toward the game's facing, always the short way round (no jump across 0 / 2 pi)
    C.OMEGA = 22                         -- rad/s: settles in ~0.2 s, close to the game camera's own swing behind the character
    function C.ease(raw, dt)
        if not C.h then C.h, C.hv = raw, 0; return C.h end
        dt = math.min(dt, 0.1)
        local err = C.wrap(raw - C.h)
        if math.abs(err) > 2.5 then C.h, C.hv = raw, 0; return C.h end      -- a teleport / a load: no spin across the bar
        local w = C.OMEGA
        C.hv = C.hv + (w * w * err - 2 * w * C.hv) * dt
        C.h = C.wrap(C.h + C.hv * dt)
        return C.h
    end

    -- THE CAMERA OFFSET (cp7, an experiment; read only). The engine places C_Navigation's frame on the super-tracked target's spot on
    -- the screen. While it is on screen (valid, not clamped), its x against the screen's centre is the target's angle off the camera's
    -- axis: theta = atan((x - centre) / f), f = half the width / tan(hfov / 2) (hfov from the cameraFov CVar, READ); the target's
    -- true bearing b is ours (the compass's own reads), so the camera's yaw is b + theta (counter-clockwise: right of centre = a
    -- smaller angle). The offset (camera - character) eases to that; off screen it is HELD while standing and settles back to the
    -- character's facing while moving (the game camera swings behind a moving character); without a target the same.
    C.cam = { off = 0, src = "none", fovAt = -10, hfov = math.rad(90) }
    function C.camStep(dt, facing)
        local K = C.cam
        local now = (type(GetTime) == "function" and GetTime()) or 0
        if now - K.fovAt > 1 then                          -- the field of view, read once a second (a READ: nothing written)
            K.fovAt = now
            local ok, v = pcall(function() return C_CVar.GetCVar("cameraFov") end)
            local deg = ok and tonumber(v) or nil
            if deg and deg >= 30 and deg <= 150 then K.hfov = math.rad(deg) end
        end
        K.nav, K.nx, K.cl, K.vp, K.ns = false, nil, nil, nil, nil
        local okF, nav = pcall(function() return C_Navigation.GetFrame() end)
        local okP, onPin = pcall(function() return C_SuperTrack.IsSuperTrackingUserWaypoint() end)
        local tg = C.tg_ or {}
        local w = (okP and onPin == true) and tg.pin or tg.quest
        local b, d
        if C.me_ and w then b, d = C.bearing(C.me_, w) end
        local seen = false
        if okF and nav then
            K.nav = true
            local okC, x = pcall(function() return nav:GetCenter() end)
            local okS, ns = pcall(function() return nav:GetEffectiveScale() end)
            local okU, us = pcall(function() return UIParent:GetEffectiveScale() end)
            local okW, uw = pcall(function() return UIParent:GetWidth() end)
            local okK, cl = pcall(function() return C_Navigation.WasClampedToScreen() end)
            local okV, vp = pcall(function() return C_Navigation.HasValidScreenPosition() end)
            local okT, st = pcall(function() return C_Navigation.GetTargetState() end)
            x = okC and num(x) or nil
            ns, us, uw = okS and num(ns) or 1, okU and num(us) or 1, okW and num(uw) or nil
            -- (not `ok and v or nil`: a FALSE "clamped" would turn into nil)
            if okK and type(cl) == "boolean" then K.cl = cl end
            if okV and type(vp) == "boolean" then K.vp = vp end
            K.ns = okT and num(st) or nil
            if x and uw and us > 0 then
                x = x * ns / us                            -- into UIParent's units
                K.nx = x
                local half = uw / 2
                if K.vp == true and K.cl == false and b and d and d > 8 and math.abs(x - half) < half * 0.92 then
                    local f = half / math.tan(K.hfov / 2)
                    local cam = b + math.atan((x - half) / f)
                    local want = C.wrap(cam - facing)
                    K.off = C.wrap(K.off + C.wrap(want - K.off) * (1 - math.exp(-dt / 0.08)))
                    K.src, seen = "nav", true
                end
            end
        end
        if not seen then
            local okM, speed = pcall(GetUnitSpeed, "player")
            local moving = okM and num(speed) and speed > 0
            if moving then
                K.off = K.off * math.exp(-dt / 0.8)
                K.src = (w and K.nav) and "settle" or "none"
            else
                K.src = (w and K.nav) and "hold" or "none"
            end
        end
        return K.off
    end

    -- the world reads (player, pin, quest, corpse) ten times a second; the strip itself every frame
    C.readAt = -1
    function C.read(force)
        local now = (type(GetTime) == "function" and GetTime()) or 0
        if not force and now - C.readAt < 0.1 then return false end
        C.readAt = now
        C.me_ = C.me()
        C.tg_ = C.targets(C.me_)
        return true
    end

    local function hide(o) if o:IsShown() then o:Hide() end end
    local function show(o) if not o:IsShown() then o:Show() end end

    function C.step(dt, snap)
        local f = C.frame
        if not f or not C.slot then return end
        local raw = C.facing()
        if not raw then f:Hide(); return end
        local fresh = C.read(snap)
        if snap then C.h = nil end
        local target = raw
        if C.camWanted() then target = C.wrap(raw + C.camStep(dt, raw)) else C.cam.off, C.cam.src = 0, "off" end
        local heading = C.ease(target, dt)
        local L = C.layout(heading, C.me_, C.tg_ or {})
        C.last = L
        if fresh then C.diag(raw, C.me_, C.tg_ or {}, L) end
        local u, S = C.u, C.SPEC
        local seen = {}
        for _, t in ipairs(L.ticks) do
            local o = C.slot[t.deg].tick
            seen[t.deg] = true
            o:SetVertexColor(200 / 255, 180 / 255, 120 / 255, 0.67 * t.fade)
            o:SetPoint("CENTER", f, "TOP", u(t.x), -u(S.line))
            show(o)
        end
        for _, l in ipairs(L.letters) do
            local s = C.slot[l.deg]
            seen[l.deg] = true
            if s.lifted ~= l.lifted then                    -- the font only when the size changes (a marker came / went)
                s.fs:SetFont(C.FONT, u(l.size), "OUTLINE")
                s.lifted = l.lifted
            end
            s.fs:SetAlpha(l.fade)
            s.fs:SetPoint("CENTER", f, "TOP", u(l.x), -u(l.y))
            show(s.fs)
        end
        for deg, s in pairs(C.slot) do
            if not seen[deg] then hide(s.fs or s.tick) end
        end
        local drawn = {}
        for _, m in ipairs(L.marks) do
            local o, spec = C.mk[m.key], C.MARK[m.key]
            drawn[m.key] = true
            o.sh:SetPoint("CENTER", f, "TOP", u(m.x), -u(S.line)); show(o.sh)
            o.tx:SetPoint("CENTER", f, "TOP", u(m.x), -u(S.line)); show(o.tx)
            if o.text ~= m.dist then o.d:SetText(m.dist); o.text = m.dist end
            o.d:SetPoint("TOP", f, "TOP", u(m.x), -u(S.line + spec.size / 2 + 3)); show(o.d)
            if m.off then                       -- off the bar: the game's nav arrow, pointing outward
                if o.side ~= m.side then pcall(function() o.a:SetRotation(m.side > 0 and -PI / 2 or PI / 2) end); o.side = m.side end
                o.a:SetPoint("CENTER", f, "TOP", u(m.x + m.side * (spec.size / 2 + 10)), -u(S.line)); show(o.a)
            else
                hide(o.a)
            end
        end
        for key, o in pairs(C.mk) do
            if not drawn[key] then hide(o.sh); hide(o.tx); hide(o.d); hide(o.a) end
        end
    end

    -- a full draw now (the first show, the harness): fresh reads, the heading snapped to the facing
    function C.draw() C.step(0, true) end

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
            cam = C.camWanted(), csrc = C.cam.src, coff = math.floor((C.cam.off or 0) * 1000 + 0.5) / 1000,
            nav = C.cam.nav, nx = C.cam.nx and math.floor(C.cam.nx * 10 + 0.5) / 10 or nil, ncl = C.cam.cl, nvp = C.cam.vp,
            nst = C.cam.ns, fov = math.floor(math.deg(C.cam.hfov) + 0.5),
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

    -- the camera experiment's switch (Interact's ACT.compass camon / camoff)
    function C.setCam(on)
        if type(WowCompanionDB) ~= "table" then WowCompanionDB = {} end
        WowCompanionDB.compassCam = on and true or false
        C.cam.off = 0
        return true
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
            C.frame, C.slot, C.mk, C.h = nil, nil, nil, nil
        end
        pcall(C.refresh)
    end)
end

-- ================================================================== CAMERA-YAW PROBE (design probe 4; the user: "Yes, run the probe")
-- READ ONLY, ONE SHOT: can the compass follow the CAMERA? The only handle in this client is the game's own navigation diamond
-- (C_Navigation.GetFrame: the super-tracked quest or pin, placed on screen by the engine). This logs, 4 times a second for at most
-- 90 s of samples in the first session after the add-on set arrives (WowCompanionDB.camProbe marks it done), whether the diamond's
-- screen position can be read (plain or secret), whether it is clamped, the character's facing, the target's true bearing and the
-- camera's field of view (C_CVar.GetCVar, a READ). No CVar written, no binding, no secure frame, nothing of the game's touched.
-- One {"type":"cap","probe":"camyaw"} line a second (no flush of its own), a "start" line, an "end" line.
WC.compass.probe = { V = "cy1", DONE = "cp4", MAX = 360, n = 0, buf = {} }
do
    local C, P = WC.compass, WC.compass.probe
    local num = C.num

    local function tag(v)                       -- plain number, "secret", or nil
        if type(issecretvalue) == "function" and issecretvalue(v) then return "secret" end
        return num(v)
    end
    local function send(t)
        t.type, t.probe, t.v = "cap", "camyaw", P.V
        pcall(function() WC.send(WC.encode(t)) end)
    end

    -- one sample: the diamond, the facing, the target's bearing
    function P.sample()
        local okF, nav = pcall(function() return C_Navigation.GetFrame() end)
        if not okF or not nav then return nil end
        local okC, x, y = pcall(function() return nav:GetCenter() end)
        local okS, sc = pcall(function() return nav:GetEffectiveScale() end)
        local okK, clamped = pcall(function() return C_Navigation.WasClampedToScreen() end)
        local okV, valid = pcall(function() return C_Navigation.HasValidScreenPosition() end)
        local okT, state = pcall(function() return C_Navigation.GetTargetState() end)
        local okD, dist = pcall(function() return C_Navigation.GetDistance() end)
        local f = C.facing()
        -- the target's true bearing: our pin while it is super-tracked, else the focused quest's spot (the compass's own reads)
        local me = C.me()
        local tg = C.targets(me)
        local okP, onPin = pcall(function() return C_SuperTrack.IsSuperTrackingUserWaypoint() end)
        local w = (okP and onPin == true) and tg.pin or tg.quest
        local b, d
        if me and w then b, d = C.bearing(me, w) end
        local function r3(v) v = num(v); return v and math.floor(v * 1000 + 0.5) / 1000 end
        local xs = okC and tag(x) or "err"
        local ys = okC and tag(y) or "err"
        return string.format("%.2f,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s",
            GetTime(), tostring(r3(f)),
            type(xs) == "number" and string.format("%.1f", xs) or tostring(xs),
            type(ys) == "number" and string.format("%.1f", ys) or tostring(ys),
            tostring(okS and r3(sc)), tostring(okK and clamped), tostring(okV and valid), tostring(okT and tag(state)),
            tostring(okD and r3(dist)), tostring(b and r3(b)), tostring(d and math.floor(d + 0.5)))
    end

    function P.start()
        if P.on or P.over then return end
        P.on, P.n, P.buf = true, 0, {}
        local okW, w = pcall(function() return UIParent:GetWidth() end)
        local okH, h = pcall(function() return UIParent:GetHeight() end)
        local okU, us = pcall(function() return UIParent:GetEffectiveScale() end)
        local okP, pw, ph = pcall(GetPhysicalScreenSize)
        local okV, fov = pcall(function() return C_CVar.GetCVar("cameraFov") end)
        local okZ, zoom = pcall(function() return GetCameraZoom() end)
        send({ op = "start", ui = { w = okW and num(w) or nil, h = okH and num(h) or nil, s = okU and num(us) or nil },
               phys = { w = okP and num(pw) or nil, h = okP and num(ph) or nil }, fov = okV and tostring(fov) or nil,
               zoom = okZ and num(zoom) or nil,
               cols = "t,facing,navX,navY,navScale,clamped,validPos,state,navDist,bearing,yd" })
    end

    function P.finish(why)
        if not P.on then return end
        if #P.buf > 0 then send({ op = "s", s = P.buf }); P.buf = {} end
        send({ op = "end", why = why, n = P.n })
        P.on, P.over = false, true
        if type(WowCompanionDB) ~= "table" then WowCompanionDB = {} end
        WowCompanionDB.camProbe = P.DONE                                   -- once per add-on set
    end

    function P.tick()
        if P.over then return end
        if not P.on then
            if type(WowCompanionDB) == "table" and WowCompanionDB.camProbe == P.DONE then P.over = true; return end
            if (GetTime() - (P.loginAt or GetTime())) < 20 then return end      -- let the login settle
            local okF, nav = pcall(function() return C_Navigation.GetFrame() end)
            if not okF or not nav then return end
            P.start()
        end
        local line = P.sample()
        if line then
            P.n = P.n + 1
            P.buf[#P.buf + 1] = line
            if #P.buf >= 4 then send({ op = "s", s = P.buf }); P.buf = {} end
        end
        if P.n >= P.MAX then P.finish("done") end
    end

    local pf = CreateFrame("Frame")
    local acc4 = 0
    pcall(function() pf:RegisterEvent("PLAYER_ENTERING_WORLD"); pf:RegisterEvent("PLAYER_LOGOUT") end)
    pf:SetScript("OnEvent", function(_, ev)
        if ev == "PLAYER_LOGOUT" then pcall(P.finish, "logout") else P.loginAt = P.loginAt or GetTime() end
    end)
    pf:SetScript("OnUpdate", function(_, el)
        if P.over then return end
        acc4 = acc4 + (el or 0)
        if acc4 < 0.25 then return end
        acc4 = 0
        pcall(P.tick)
    end)
end
