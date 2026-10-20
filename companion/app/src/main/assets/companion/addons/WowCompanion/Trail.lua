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
WC.compass = { V = "cp14" }
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

    -- THE CAMERA (cp11, the user: "it needs to follow the camera not the char"; read only). No add-on API reads the game camera, but
    -- this client gives add-ons the CONTROLLER: C_GamePad.GetDeviceMappedState() (Book.lua's padProbe reads it too), and the game's
    -- own camera settings (C_CVar.GetCVar, READ): GamePadCameraYawSpeed, gamepadInvertYaw, GamePadTurnWithCamera (0 = the character
    -- turns with the camera while moving; 1 = + in combat; 3 = + casting (the default); 2 = always). So the camera's world yaw is:
    --   * while the character turns WITH the camera (moving, or the setting's other cases): the character's facing;
    --   * otherwise (standing: a right-stick orbit leaves the character alone): dead-reckoned, yaw -= stickX x rate x dt (stick
    --     right = turning clockwise = a smaller counter-clockwise angle; inverted with gamepadInvertYaw);
    --   * corrected by a FIX: the engine's navigation point on screen (valid, unclamped, inside 92% of the half width, the target
    --     more than 8 yd away) gives the yaw exactly; a fix more than 100 degrees from the PREDICTED yaw is the engine's mirrored
    --     behind-the-camera projection (cp10) and never counts. Two fixes with stick turning between them LEARN the true rate.
    -- The app can plug in its own stick reading (C.stickIn) where C_GamePad gives none.
    -- (MAXOFF 100: a mirror is ~180 degrees off the true camera, so it is rejected while the dead reckoning is within 80 of it; an
    -- honest fix up to 100 off still corrects a poor rate guess, and teaches the rate)
    C.cam = { off = 0, src = "none", fovAt = -10, hfov = math.rad(90), abs = nil, fixAt = -100, MAXOFF = math.rad(100),
              BASE = math.rad(120), base = nil, ys = 1.3, inv = false, tw = 3, fol = false, gp = nil, rx = 0, acc = 0,
              extX = nil, extAt = -100, ANCHOR_TAU = 4, IDLE_S = 0.5, STRAIGHT_S = 1, STRAIGHT_RATE = 0.35,
              idleSince = -100, straightSince = -100, lastF = nil }

    -- the app's own stick reading, when the game's gamepad API gives none (x: -1 .. 1, right positive)
    function C.stickIn(x)
        x = num(x)
        if x then C.cam.extX, C.cam.extAt = math.max(-1, math.min(1, x)), ((type(GetTime) == "function" and GetTime()) or 0) end
    end

    -- the camera stick's x: the game's mapped state, its "Camera" stick by the game's own names (inputaxisbinding.lua)
    function C.stickX(now)
        local K = C.cam
        local okE, en = pcall(function() return C_GamePad.IsEnabled() end)
        K.gp = okE and en == true or false
        local okS, st = pcall(function() return C_GamePad.GetDeviceMappedState() end)
        if okS and type(st) == "table" and type(st.sticks) == "table" then
            local n = num(st.stickCount) or #st.sticks
            for i = 1, n do
                local okN, name = pcall(function() return C_GamePad.StickIndexToConfigName(i - 1) end)
                if okN and name == "Camera" then
                    local okX, x = pcall(function() return st.sticks[i].x end)
                    x = okX and num(x) or nil
                    if x then K.from = "pad"; return x end
                end
            end
        end
        if K.extX and now - K.extAt < 0.3 then K.from = "app"; return K.extX end
        K.from = "none"
        return 0
    end

    -- the character turns with the camera now? (GamePadTurnWithCamera, the player's own setting)
    function C.turnsWith(moving)
        local tw = C.cam.tw
        if tw == 2 or moving then return true end
        local okC, fight = pcall(InCombatLockdown)
        if (tw == 1 or tw == 3) and okC and fight == true then return true end
        if tw == 3 then
            local okK, casting = pcall(function() return UnitCastingInfo("player") ~= nil or UnitChannelInfo("player") ~= nil end)
            if okK and casting == true then return true end
        end
        return false
    end

    function C.camStep(dt, facing)
        local K = C.cam
        local now = (type(GetTime) == "function" and GetTime()) or 0
        if now - K.fovAt > 1 then                          -- the settings, read once a second (READS: nothing written)
            K.fovAt = now
            local function cv(name) local ok, v = pcall(function() return C_CVar.GetCVar(name) end); return ok and v or nil end
            local deg = tonumber(cv("cameraFov") or "")
            if deg and deg >= 30 and deg <= 150 then K.hfov = math.rad(deg) end
            K.ys = tonumber(cv("GamePadCameraYawSpeed") or "") or K.ys
            local inv = cv("gamepadInvertYaw")
            K.inv = inv == "1" or inv == true
            K.tw = tonumber(cv("GamePadTurnWithCamera") or "") or K.tw
            local fol = cv("GamepadCameraFollowOnStick")
            K.fol = fol == "1" or fol == true
        end
        local okM, speed = pcall(GetUnitSpeed, "player")
        local moving = okM and num(speed) and speed > 0 or false
        local x = C.stickX(now)
        K.rx = x
        local rate = (K.base or K.BASE) * K.ys
        if not K.abs then K.abs = facing end
        -- cp12 (test21, the user: "it seems to be trying to track the charactor direction at the same time ... While walking"): the
        -- CAMERA is the source at all times. The stick is integrated moving or not; while the character turns with the camera (the
        -- setting), the facing only ANCHORS it slowly (ANCHOR_TAU), and only once the stick has been idle IDLE_S and the character
        -- has walked straight STRAIGHT_S (then the game has turned it onto the camera); never a switch to the facing.
        if math.abs(x) > 0.05 then K.idleSince = now end
        if K.lastF and dt > 0 and math.abs(C.wrap(facing - K.lastF)) / dt > K.STRAIGHT_RATE then K.straightSince = now end
        K.lastF = facing
        if x ~= 0 then
            local d = x * rate * dt * (K.inv and -1 or 1)
            K.abs = C.wrap(K.abs - d)
            if K.ref then K.ref = C.wrap(K.ref - d) end                         -- the last fix, carried by the stick (cp13)
            K.acc = K.acc + x * dt * (K.inv and -1 or 1)                     -- stick-seconds since the last fix (the rate's lesson)
            K.src = "stick"
        -- cp14 (test23, the user: "if I face north and begin walking south while still facing camera north, then the compass flips to
        -- south after a delay"): with a controller, walking TOWARD the camera turns the character, not the camera. So no facing anchor
        -- at all unless the game's follow camera is on (GamepadCameraFollowOnStick: the camera then swings behind a moving character),
        -- and even then only while walking FORWARD relative to the camera (the facing within 45 degrees of it)
        elseif K.fol and moving and math.abs(C.wrap(facing - K.abs)) <= math.rad(45)
            and now - K.idleSince >= K.IDLE_S and now - K.straightSince >= K.STRAIGHT_S then
            -- (the follow camera swings behind at its own pace: gently, 4 s)
            local gap = C.wrap(facing - K.abs)
            local tau = K.ANCHOR_TAU
            local step = gap * (1 - math.exp(-dt / tau))
            K.abs = C.wrap(K.abs + step)
            if K.ref then K.ref = C.wrap(K.ref + step) end
            K.src = "anchor"
        else
            K.src = "still"
        end
        -- the FIX: the engine's navigation point on screen
        K.nav, K.nx, K.cl, K.vp, K.ns = false, nil, nil, nil, nil
        local okF, nav = pcall(function() return C_Navigation.GetFrame() end)
        local okP, onPin = pcall(function() return C_SuperTrack.IsSuperTrackingUserWaypoint() end)
        local tg = C.tg_ or {}
        local w = (okP and onPin == true) and tg.pin or tg.quest
        local b, dd
        if C.me_ and w then b, dd = C.bearing(C.me_, w) end
        if okF and nav then
            K.nav = true
            local okC, nx = pcall(function() return nav:GetCenter() end)
            local okS, ns = pcall(function() return nav:GetEffectiveScale() end)
            local okU, us = pcall(function() return UIParent:GetEffectiveScale() end)
            local okW, uw = pcall(function() return UIParent:GetWidth() end)
            local okK, cl = pcall(function() return C_Navigation.WasClampedToScreen() end)
            local okV, vp = pcall(function() return C_Navigation.HasValidScreenPosition() end)
            local okT, stt = pcall(function() return C_Navigation.GetTargetState() end)
            nx = okC and num(nx) or nil
            ns, us, uw = okS and num(ns) or 1, okU and num(us) or 1, okW and num(uw) or nil
            -- (not `ok and v or nil`: a FALSE "clamped" would turn into nil)
            if okK and type(cl) == "boolean" then K.cl = cl end
            if okV and type(vp) == "boolean" then K.vp = vp end
            K.ns = okT and num(stt) or nil
            if nx and uw and us > 0 then
                nx = nx * ns / us
                K.nx = nx
                local half = uw / 2
                if K.vp == true and K.cl == false and b and dd and dd > 8 and math.abs(nx - half) < half * 0.92 then
                    local f = half / math.tan(K.hfov / 2)
                    local cam = b + math.atan((nx - half) / f)
                    local err = C.wrap(cam - K.abs)
                    local errRef = K.ref and C.wrap(cam - K.ref) or err   -- (the gate: against the last fix carried forward)
                    -- cp13 (test22: a chain of small "fixes" walked the yaw 120 degrees with the stick idle, then a mirror held it 180
                    -- off): with the follow camera off, the camera moves ONLY with the stick, so a fix may differ from the estimate by
                    -- 15 degrees plus 40% (60% while the rate is still the guess) of what the stick turned since the last fix; more is a
                    -- mirror or junk. Never verified yet (no fix since camera mode came on), or the follow camera on and moving: 100.
                    local tol = math.rad(15) + (K.base and 0.4 or 0.6) * math.abs(K.acc) * K.ys * (K.base or K.BASE)
                    if (K.fol and moving) or not K.lastFix then tol = K.MAXOFF end     -- (never verified yet: the wide gate)
                    K.tol = tol
                    if math.abs(errRef) <= math.min(tol, K.MAXOFF) then
                        -- the rate's lesson: the stick turned since the last fix, and the fix says by how much
                        if K.lastFix and math.abs(K.acc) > 0.25 then
                            local turned = -C.wrap(cam - K.lastFix)
                            local learned = turned / (K.acc * K.ys)
                            if learned > math.rad(20) and learned < math.rad(720) then
                                K.base = (K.base or K.BASE) + (learned - (K.base or K.BASE)) * 0.5
                            end
                        end
                        K.abs = C.wrap(K.abs + err * (1 - math.exp(-dt / 0.08)))
                        K.lastFix, K.acc, K.fixAt, K.ref = cam, 0, now, cam
                        K.src = "nav"
                    else
                        K.src = "mirror"                    -- a mirrored (behind-the-camera) projection: never a fix
                    end
                end
            end
        end
        K.off = C.wrap(K.abs - facing)
        return K.off
    end

    -- THE WORLD MARKER (test22, the user: "I still don't have in world markers" ... "Track the checkpoint"). This client never shows
    -- the game's own diamond: GetTargetState reads Invalid (0) in every sample, and the game's SuperTrackedFrame sets its alpha to
    -- 0 for Invalid. The engine still projects the super-tracked spot (the route's pin, else the focused quest) onto the screen,
    -- and that point is readable (cy1, cp7). So our OWN marker (a texture on our own frame, the game's atlas) sits there: only
    -- while the point is valid, unclamped, and the target truly IN FRONT of the camera (its bearing within the half FOV + 10
    -- degrees of the camera's yaw: a point behind the camera is projected mirrored, cp10), with the distance under it.
    -- Shown with the compass bar (the same switch); read only.
    C.wm = { SIZE = 34 }
    function C.wmBuild()
        if C.wm.f then return C.wm.f end
        local f = CreateFrame("Frame", nil, UIParent)
        f:SetFrameStrata("MEDIUM")
        f:EnableMouse(false)
        f:SetSize(C.u(C.wm.SIZE), C.u(C.wm.SIZE))
        f:Hide()
        local sh = f:CreateTexture(nil, "ARTWORK", nil, 1)
        sh:SetPoint("CENTER", f, "CENTER", 0, 0)
        sh:SetSize(C.u(C.wm.SIZE + 4), C.u(C.wm.SIZE + 4))
        sh:SetVertexColor(0, 0, 0, 0.85)
        local tx = f:CreateTexture(nil, "ARTWORK", nil, 3)
        tx:SetAllPoints(f)
        local d = f:CreateFontString(nil, "OVERLAY")
        d:SetFont(C.FONT, C.u(13), "OUTLINE")
        d:SetPoint("TOP", f, "BOTTOM", 0, -C.u(2))
        C.wm.f, C.wm.sh, C.wm.tx, C.wm.d = f, sh, tx, d
        return f
    end
    function C.wmStep(facing)
        local W = C.wm
        local show = false
        if C.wanted() and C.frame and C.frame:IsShown() then
            local okF, nav = pcall(function() return C_Navigation.GetFrame() end)
            local okP, onPin = pcall(function() return C_SuperTrack.IsSuperTrackingUserWaypoint() end)
            onPin = okP and onPin == true
            local tg = C.tg_ or {}
            local w = onPin and tg.pin or tg.quest
            if okF and nav and w and C.me_ then
                local okV, vp = pcall(function() return C_Navigation.HasValidScreenPosition() end)
                local okK, cl = pcall(function() return C_Navigation.WasClampedToScreen() end)
                local okC, x, y = pcall(function() return nav:GetCenter() end)
                local okS, ns = pcall(function() return nav:GetEffectiveScale() end)
                local okU, us = pcall(function() return UIParent:GetEffectiveScale() end)
                local okW, uw = pcall(function() return UIParent:GetWidth() end)
                x, y = okC and num(x) or nil, okC and num(y) or nil
                ns, us, uw = okS and num(ns) or 1, okU and num(us) or 1, okW and num(uw) or nil
                if okV and vp == true and okK and cl == false and x and y and uw and us > 0 then
                    local b, d = C.bearing(C.me_, w)
                    local yaw = (C.camWanted() and C.cam.abs) or facing
                    local hfov = C.cam.hfov or math.rad(90)
                    if math.abs(C.wrap(b - yaw)) < hfov / 2 + math.rad(10) then
                        local f = C.wmBuild()
                        f:ClearAllPoints()
                        f:SetPoint("CENTER", UIParent, "BOTTOMLEFT", x * ns / us, y * ns / us)
                        local atlas = onPin and "Waypoint-MapPin-Tracked" or "Navigation-Tracked-Icon"
                        if W.atlas ~= atlas then
                            pcall(function() W.tx:SetAtlas(atlas) end)
                            pcall(function() W.sh:SetAtlas(atlas) end)
                            W.atlas = atlas
                        end
                        local txt = C.distText(d)
                        if W.text ~= txt then W.d:SetText(txt); W.text = txt end
                        if not f:IsShown() then f:Show() end
                        show = true
                    end
                end
            end
        end
        W.shown = show
        if not show and W.f and W.f:IsShown() then W.f:Hide() end
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
        if C.camWanted() then target = C.wrap(raw + C.camStep(dt, raw)) else C.cam.off, C.cam.src, C.cam.abs, C.cam.ref, C.cam.lastFix = 0, "off", nil, nil, nil end
        local heading = C.ease(target, dt)
        pcall(C.wmStep, raw)
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
            cyaw = C.cam.abs and math.floor(C.cam.abs * 1000 + 0.5) / 1000 or nil,
            wm = C.wm and C.wm.shown and true or false, tol = C.cam.tol and math.floor(math.deg(C.cam.tol) + 0.5) or nil,
            gp = C.cam.gp, from = C.cam.from, rx = C.cam.rx and math.floor(C.cam.rx * 100 + 0.5) / 100 or nil,
            ys = C.cam.ys, base = math.floor(math.deg(C.cam.base or C.cam.BASE) + 0.5), learned = C.cam.base and true or false,
            tw = C.cam.tw, fol = C.cam.fol, inv = C.cam.inv,
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
            if C.wm and C.wm.f then C.wm.f:Hide() end
        end
        return show
    end

    -- the camera experiment's switch (Interact's ACT.compass camon / camoff)
    function C.setCam(on)
        if type(WowCompanionDB) ~= "table" then WowCompanionDB = {} end
        WowCompanionDB.compassCam = on and true or false
        C.cam.off, C.cam.abs, C.cam.fixAt, C.cam.lastFix, C.cam.acc, C.cam.ref = 0, nil, -100, nil, 0, nil
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

-- ================================================================== THE LIVE AGGRO LAYER, first slice (ag1)
-- The user: "My thing didn't account for the mobs that are randomly between me and where I am going" ... "Start now". The add-on's
-- side of safepath-work/docs/AGGRO_DOC_CONTRACT_v1.md: every hostile nameplate in view (UnitCanAttack, reaction <= 4, not a player),
-- at most 24, in stable slots; its level, class, reaction, combat and threat, a DISTANCE BRACKET from our own range checks (spells,
-- then items, then CheckInteractDistance), its npc id and creature type read out of combat, dead / tapped flags; and, to help the
-- router place it, the plate's screen x turned into a world BEARING with the compass's camera yaw (cp11). One record every 0.2 s:
-- riding the pos doc while moving (Core.lua: pos.ag), a standalone {"type":"aggro"} while still (at most 1 a second, on a change).
-- Off in instances, encounters and PvP. READ ONLY: no CVar written, no secure frame, no raid mark, no plate touched.
--
-- Adapted from Forever Range by Tarolg (the spell / item range-bracket builder, its secret checks, its pull detector):
--   Copyright (c) 2026 Tarolg
--   Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files
--   (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify,
--   merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is
--   furnished to do so, subject to the following conditions: The above copyright notice and this permission notice shall be included
--   in all copies or substantial portions of the Software. THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
--   IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN
--   NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF
--   CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
WC.live = { V = "ag2", MAX = 24, REUSE_S = 10, SCAN_S = 0.2, FULL_S = 5, STILL_S = 1, slot = {}, byTok = {}, toks = {}, freeAt = {},
            q = 0, bands = {}, ghosts = {}, pulls = {}, sent = { full = 0, same = 0, still = 0, off = 0 } }
do
    local A = WC.live
    local C = WC.compass
    local function now() return (type(GetTime) == "function" and GetTime()) or 0 end
    -- a plain value or nil (a secret, an error, a NaN)
    local function pv(fn, ...)
        local ok, v = pcall(fn, ...)
        if not ok or v == nil then return nil end
        if type(issecretvalue) == "function" and issecretvalue(v) then return nil end
        if type(v) == "number" and v ~= v then return nil end
        return v
    end
    A.pv = pv
    local function b01(v) if v == true then return 1 elseif v == false then return 0 end end

    -- ---------------------------------------------------------------- the range bands (Forever Range's builder, adapted)
    -- every distinct max range from 0 we can check: our harmful spells' (the spellbook's own metadata), then the candidate items'
    -- (their ranges read at runtime: nothing hard-coded), then CheckInteractDistance's (28 / 8 yd) where nothing else covers them
    A.ITEMS = { 8149, 22432, 9606, 10699, 4559, 1191, 4388, 13289, 835, 18904, 4945 }       -- Forever Range's enemy candidates
    A.CI = { [1] = 28, [2] = 8, [3] = 7, [4] = 28 }
    function A.buildBands()
        local list, have = {}, {}
        local function add(max, kind, id, check)
            max = math.floor(max + 0.5)
            if max <= 0 or have[max] then return end
            have[max] = true
            list[#list + 1] = { max = max, kind = kind, id = id, check = check }
        end
        -- spells
        local okN, nLines = pcall(function() return C_SpellBook.GetNumSpellBookSkillLines() end)
        local bank = (type(Enum) == "table" and Enum.SpellBookSpellBank and Enum.SpellBookSpellBank.Player) or 0
        for i = 1, (okN and tonumber(nLines) or 0) do
            local okL, li = pcall(function() return C_SpellBook.GetSpellBookSkillLineInfo(i) end)
            if okL and type(li) == "table" and not li.offSpecID and not li.isOffSpec then
                local off, cnt = tonumber(li.itemIndexOffset) or 0, tonumber(li.numSpellBookItems) or 0
                for slot = off + 1, off + cnt do
                    local okI, it = pcall(function() return C_SpellBook.GetSpellBookItemInfo(slot, bank) end)
                    local id = okI and type(it) == "table" and pv(function() return it.spellID end)
                    if id and not it.isPassive and not it.isOffSpec then
                        local okS, si = pcall(function() return C_Spell.GetSpellInfo(id) end)
                        local okH, harm = pcall(function() return C_Spell.IsSpellHarmful(id) end)
                        local mx = okS and type(si) == "table" and pv(function() return si.maxRange end)
                        local mn = okS and type(si) == "table" and pv(function() return si.minRange end) or 0
                        if mx and mx > 0 and mn == 0 and okH and harm == true then
                            add(mx, "s", id, function(tok) return C_Spell.IsSpellInRange(id, tok) end)
                        end
                    end
                end
            end
        end
        -- items (their range from their own spell; loaded on demand: ITEM_DATA_LOAD_RESULT rebuilds)
        for _, id in ipairs(A.ITEMS) do
            local okC, cached = pcall(function() return C_Item.IsItemDataCachedByID(id) end)
            if okC and cached == false then
                pcall(function() C_Item.RequestLoadItemDataByID(id) end)
            else
                local okG, _, sid = pcall(function() return C_Item.GetItemSpell(id) end)
                sid = okG and pv(function() return sid end)
                local okS, si = pcall(function() return C_Spell.GetSpellInfo(sid) end)
                local mx = sid and okS and type(si) == "table" and pv(function() return si.maxRange end)
                local mn = sid and okS and type(si) == "table" and pv(function() return si.minRange end) or 0
                if mx and mx > 0 and mn == 0 then add(mx, "i", id, function(tok) return C_Item.IsItemInRange(id, tok) end) end
            end
        end
        -- CheckInteractDistance, the last fallback (8 and 28 yd), only where no spell or item band sits within a yard
        for i, yd in pairs({ [2] = 8, [4] = 28 }) do
            local near = false
            for _, b in ipairs(list) do if math.abs(b.max - yd) <= 1 then near = true end end
            if not near then add(yd, "d", i, function(tok) return CheckInteractDistance(tok, i) end) end
        end
        table.sort(list, function(x, y) return x.max < y.max end)
        A.bands = list
        A.covDirty = true
        return list
    end

    -- the bracket: the smallest band that says "in range" is b; the largest one below it that says "out" is a (FSR's reading)
    function A.bracket(tok)
        local a, kind = 0, nil
        for _, band in ipairs(A.bands) do
            local ok, inr = pcall(band.check, tok)
            if ok and not (type(issecretvalue) == "function" and issecretvalue(inr)) then
                if inr == true then return a, band.max, band.kind end
                if inr == false then a, kind = band.max, band.kind end
            end
        end
        return a, nil, kind
    end

    -- ---------------------------------------------------------------- the plates and their slots
    function A.eligible(tok)
        if pv(UnitExists, tok) ~= true then return false end
        if pv(UnitIsPlayer, tok) == true then return false end
        if pv(UnitCanAttack, "player", tok) ~= true then return false end
        local r = pv(UnitReaction, tok, "player")
        return r ~= nil and r <= 4
    end

    -- the lowest slot free for 10 s, else the lowest free one; nil when all 24 are taken
    function A.alloc(tok)
        local t, best, anyFree = now(), nil, nil
        for s = 0, A.MAX - 1 do
            if not A.slot[s] then
                anyFree = anyFree or s
                if not best and (A.freeAt[s] == nil or t - A.freeAt[s] >= A.REUSE_S) then best = s end
            end
        end
        local s = best or anyFree
        if s == nil then return nil end
        A.slot[s] = { tok = tok, at = t }
        A.byTok[tok] = s
        return s
    end
    function A.free(tok)
        local s = A.byTok[tok]
        if s == nil then return end
        A.slot[s], A.byTok[tok], A.freeAt[s] = nil, nil, now()
    end

    function A.onAdded(tok)
        A.toks[tok] = true
        if A.byTok[tok] == nil and A.eligible(tok) then A.alloc(tok) end
    end
    function A.onRemoved(tok)
        A.toks[tok] = nil
        local s = A.byTok[tok]
        if s == nil then return end
        if A.icon and A.icon[s] then A.icon[s].f:Hide(); A.icon[s].tok = nil end
        if pv(UnitIsDead, tok) == true then                       -- died: one record with dd = 1, then the slot goes
            A.ghosts[#A.ghosts + 1] = { s = s, n = A.slot[s].npc, dd = 1 }
        end
        A.free(tok)
    end

    -- the npc id from the GUID ("Creature-0-x-x-x-<npc>-<spawn>"), out of combat only (the GUID is secret in combat)
    function A.npcOf(tok)
        local g = pv(UnitGUID, tok)
        if type(g) ~= "string" then return nil end
        local kind, id = g:match("^(%a+)%-%d+%-%d+%-%d+%-%d+%-(%d+)")
        if kind == "Creature" or kind == "Vehicle" then return tonumber(id) end
        return nil
    end

    -- the camera's world yaw for a bearing: the compass's dead-reckoned camera when camera mode runs, else the character's facing
    function A.camYaw()
        if C and C.camWanted and C.camWanted() and C.cam and C.cam.abs then return C.cam.abs, "c" end
        local f = C and C.facing and C.facing()
        if f then return f, "f" end
        return nil
    end

    -- the plate's screen x as -1..1 of the half width, and clamped (at an edge); nil when the read is secret / missing
    function A.screenX(tok)
        local okP, plate = pcall(function() return C_NamePlate.GetNamePlateForUnit(tok) end)
        if not okP or not plate then return nil end
        local x = pv(function() return (plate:GetCenter()) end)
        local ps = pv(function() return plate:GetEffectiveScale() end) or 1
        local us = pv(function() return UIParent:GetEffectiveScale() end) or 1
        local uw = pv(function() return UIParent:GetWidth() end)
        if not (x and uw and us > 0) then return nil end
        local half = uw / 2
        local sx = (x * ps / us - half) / half
        local cl = math.abs(sx) > 0.97
        return math.max(-1, math.min(1, sx)), cl
    end

    local CLS = { normal = "n", elite = "e", rare = "r", rareelite = "R", worldboss = "b", trivial = "t", minus = "m" }

    -- one plate's record (and its calm reading for the pull detector)
    function A.read(s, st, t, playerFight)
        local tok = st.tok
        local m = { s = s }
        if not playerFight and not st.npc then st.npc = A.npcOf(tok) end
        if not playerFight and not st.ct then
            local okT, _, ct = pcall(UnitCreatureType, tok)
            if okT and type(ct) == "number" and not (type(issecretvalue) == "function" and issecretvalue(ct)) then st.ct = ct end
        end
        m.n, m.ct = st.npc, st.ct
        m.lv = pv(UnitLevel, tok)
        m.c = CLS[pv(UnitClassification, tok) or ""]
        m.r = pv(UnitReaction, tok, "player")
        m.cb = b01(pv(UnitAffectingCombat, tok))
        m.om = pv(UnitThreatSituation, "player", tok)
        local a, b, rk = A.bracket(tok)
        m.a, m.b, m.rk = a, b, rk
        if C_QuestLog and C_QuestLog.UnitIsRelatedToActiveQuest then m.qi = b01(pv(C_QuestLog.UnitIsRelatedToActiveQuest, tok)) end
        if pv(UnitIsTapDenied, tok) == true then m.td = 1 end
        local sx, cl = A.screenX(tok)
        if sx then
            m.sx = math.floor(sx * 1000 + 0.5) / 1000
            if cl then m.cl = 1 end
            A.sxPlain = true
            -- the BEARING (additive to v1): the camera's yaw, less the plate's angle off the screen's centre (the compass's FOV)
            local yaw, src = A.camYaw()
            if yaw and not cl then
                local hfov = (C and C.cam and C.cam.hfov) or math.rad(90)
                m.br = math.floor(C.wrap(yaw - math.atan(sx * math.tan(hfov / 2))) * 1000 + 0.5) / 1000
                m.bs = src
            end
        end
        m.age = math.floor(t - st.at)
        if pv(UnitIsDead, tok) == true then m.dd = 1 end
        -- the pull detector (Forever Range's): the last CALM reading, then a calm -> on-me turn
        local onMe = m.om ~= nil and m.om >= 2
        if not onMe and m.cb ~= 1 then
            st.calm = { b = b, t = t }
        elseif onMe and not st.engaged then
            A.pullCheck(s, st, m, t)
        end
        st.engaged = onMe
        return m
    end

    -- a pull (the mob noticed us by itself): the interval [lower now, upper of the last calm reading], Forever Range's rejects
    A.lastAction = -100
    function A.pullCheck(s, st, m, t)
        local calm = st.calm
        local why
        if t - A.lastAction < 2 then why = "own action"
        elseif pv(UnitAffectingCombat, "pet") == true then why = "pet"
        elseif not calm or t - calm.t > 0.5 then why = "stale"
        elseif not m.lv or m.lv <= 0 then why = "level"
        else
            local plv = pv(UnitLevel, "player")
            local def = plv and (20 + (m.lv - plv)) or nil
            if not def or def <= 10 or def >= 40 then why = "near clamp"
            elseif not calm.b or not m.a then why = "no bracket"
            elseif m.a > calm.b then why = "moving away"
            elseif calm.b - m.a > 15 then why = "too wide"
            else
                A.pulls[#A.pulls + 1] = { s = s, n = st.npc, ct = st.ct, lv = m.lv, plv = plv, lo = m.a, hi = calm.b,
                                          t = math.floor(t * 10 + 0.5) / 10 }
                return true
            end
        end
        A.lastReject = why
        return false
    end

    -- ---------------------------------------------------------------- the off states
    A.enc = false
    function A.offWhy()
        local okI, inInst, kind = pcall(IsInInstance)
        if okI and inInst == true then
            if kind == "pvp" or kind == "arena" then return "pvp" end
            return "inst"
        end
        if A.enc then return "enc" end
        return nil
    end

    -- ---------------------------------------------------------------- the record
    local function hashOf(rec)
        local parts = { tostring(rec.comb), tostring(rec.me and rec.me.lv), tostring(rec.me and rec.me.map) }
        for _, m in ipairs(rec.m) do
            parts[#parts + 1] = table.concat({ m.s, tostring(m.n), tostring(m.ct), tostring(m.lv), tostring(m.c), tostring(m.r),
                tostring(m.cb), tostring(m.om), tostring(m.a), tostring(m.b), tostring(m.rk), tostring(m.qi), tostring(m.dd),
                tostring(m.td), tostring(m.sx), tostring(m.cl), tostring(m.br), tostring(m.mk) }, ",")
        end
        for _, p in ipairs(rec.pl or {}) do parts[#parts + 1] = "pl" .. p.s .. "," .. p.t end
        return table.concat(parts, ";")
    end

    function A.scan()
        local t = now()
        local off = A.offWhy()
        if off then
            if A.off ~= off then                                   -- one record, then nothing until the layer is back
                A.off = off
                for tok in pairs(A.byTok) do A.free(tok) end
                A.q = A.q + 1
                A.pending = { v = 1, q = A.q, off = off, t = math.floor(t * 10 + 0.5) / 10 }
                A.pendingFull = true
            end
            return
        end
        if A.off then A.off = nil; A.hash = nil end
        local playerFight = pv(InCombatLockdown) == true
        -- slots for the plates in view (eligibility can change: a plate turning friendly lets its slot go)
        for tok in pairs(A.toks) do
            local el = A.eligible(tok)
            if el and A.byTok[tok] == nil then A.alloc(tok)
            elseif not el and A.byTok[tok] ~= nil then A.free(tok) end
        end
        local list = {}
        for s = 0, A.MAX - 1 do
            local st = A.slot[s]
            if st then list[#list + 1] = A.read(s, st, t, playerFight) end
        end
        for _, g in ipairs(A.ghosts) do list[#list + 1] = g end
        A.ghosts = {}
        local me = C and C.me and C.me()
        local rec = { v = 1, t = math.floor(t * 10 + 0.5) / 10, comb = b01(playerFight), m = list }
        if me then
            rec.me = { lv = pv(UnitLevel, "player"), map = me.map, x = math.floor(me.x * 10 + 0.5) / 10, y = math.floor(me.y * 10 + 0.5) / 10 }
            local f = C.facing()
            if f then rec.me.f = math.floor(f * 1000 + 0.5) / 1000 end
        end
        if #A.pulls > 0 then rec.pl = A.pulls; A.pulls = {} end
        local h = hashOf(rec)
        if h ~= A.hash or not A.fullAt or t - A.fullAt >= A.FULL_S then
            A.hash, A.fullAt = h, t
            A.q = A.q + 1
            rec.q = A.q
            A.pending, A.pendingFull = rec, true
        else
            A.pending, A.pendingFull = { v = 1, q = A.q, same = 1, t = rec.t }, false
        end
        A.last = rec
        A.scanAt = t
    end

    -- Core.lua's pos send while MOVING: the pending record rides it (its bytes take the place of padding)
    A.drainAt = -100
    function WC.drainAggro()
        A.drainAt = now()
        local r = A.pending
        A.pending = nil
        if r then
            if A.pendingFull then A.sent.full = A.sent.full + 1 else A.sent.same = A.sent.same + 1 end
            if r.off then A.sent.off = A.sent.off + 1 end
        end
        return r
    end

    -- STILL (no pos send drained it): a standalone record, at most once a second, and only a FULL one (a change)
    A.stillAt = -100
    function A.still()
        local t = now()
        if not A.pending or t - A.drainAt < 0.3 then return end
        if not A.pendingFull then A.pending = nil; return end
        if t - A.stillAt < A.STILL_S then return end
        A.stillAt = t
        local r = A.pending
        A.pending = nil
        r.type = "aggro"
        A.sent.still = A.sent.still + 1
        if r.off then A.sent.off = A.sent.off + 1 end
        pcall(function() WC.send(WC.encode(r)) end)
    end

    -- ---------------------------------------------------------------- the coverage record
    A.CV = { maxd = "nameplateMaxDistance", off = "nameplateShowOffscreen", ena = "nameplateShowEnemies",
             vmax = "nameplateMaxVerticalDistance" }
    function A.coverage()
        local cv = {}
        for k, name in pairs(A.CV) do
            local okV, v = pcall(function() return C_CVar.GetCVar(name) end)       -- READ only
            if okV and v ~= nil and not (type(issecretvalue) == "function" and issecretvalue(v)) then cv[k] = tostring(v) end
        end
        local sp, it = {}, {}
        for _, b in ipairs(A.bands) do
            if b.kind == "s" then sp[#sp + 1] = { b.id, b.max } elseif b.kind == "i" then it[#it + 1] = { b.id, b.max } end
        end
        local rec = { type = "aggrocov", v = 1, cv = cv, sp = sp, it = it, ci = { 28, 8, 7, 28 } }
        A.covDirty = false
        A.lastCov = rec
        pcall(function() WC.send(WC.encode(rec)) end)
        return rec
    end

    -- ---------------------------------------------------------------- ENEMY NAMEPLATES ON (ag2; the ONE game-setting exception)
    -- test22: every probe read plates 0, and the coverage line "enemies false": the game's enemy nameplates were OFF, so the layer saw
    -- nothing. The user's ruling ("Add-on turns them on"): while SafePath routes (Map.lua's route pin, WC.routeWp.active), the add-on
    -- sets nameplateShowEnemies to 1, remembers the player's own value in OUR saved data (WowCompanionDB.npEnemies), and puts it back
    -- when the route has been off NP_OFF_S, at log out, and at a login that finds it left from before. Out of combat only (the
    -- nameplate CVars are refused in a fight). Nothing else is ever written. The player switching them off meanwhile is their
    -- choice: kept, and not undone again this session.
    A.NP_OFF_S = 15
    A.np = { state = "idle", offSince = nil, declined = false }
    local function npGet()
        local ok, v = pcall(function() return C_CVar.GetCVar("nameplateShowEnemies") end)
        if ok and v ~= nil and not (type(issecretvalue) == "function" and issecretvalue(v)) then return tostring(v) end
    end
    local function npSet(v)
        local ok = pcall(function()
            if C_CVar and C_CVar.SetCVar then C_CVar.SetCVar("nameplateShowEnemies", v) else SetCVar("nameplateShowEnemies", v) end
        end)
        return ok
    end
    A.npGet, A.npSet = npGet, npSet
    function A.npSaved() return type(WowCompanionDB) == "table" and type(WowCompanionDB.npEnemies) == "table" and WowCompanionDB.npEnemies or nil end
    -- give the player's value back (when ours is still in place: a change of theirs stands)
    function A.npRestore(why)
        local saved = A.npSaved()
        if not saved then return end
        if pv(InCombatLockdown) == true and why ~= "logout" then return end
        if npGet() == "1" and saved.orig ~= "1" then npSet(saved.orig) end
        WowCompanionDB.npEnemies = nil
        A.np.state, A.np.why = "restored", why
    end
    function A.npStep()
        local t = now()
        local want = type(WC.routeWp) == "table" and WC.routeWp.active == true
        local saved = A.npSaved()
        if want then
            A.np.offSince = nil
            if A.np.declined then A.np.state = "declined"; return end
            local cur = npGet()
            if saved and cur ~= "1" then                       -- the player switched them off meanwhile: their choice stands
                WowCompanionDB.npEnemies = nil
                A.np.declined, A.np.state = true, "declined"
                return
            end
            if not saved and cur and cur ~= "1" then
                if pv(InCombatLockdown) == true then A.np.state = "waiting (combat)"; return end
                if type(WowCompanionDB) ~= "table" then WowCompanionDB = {} end
                WowCompanionDB.npEnemies = { orig = cur, at = math.floor(t) }
                if npSet("1") then A.np.state = "on (ours)" else A.np.state = "refused"; WowCompanionDB.npEnemies = nil end
            elseif saved then
                A.np.state = "on (ours)"
            else
                A.np.state = "on (already)"
            end
        else
            if saved then
                A.np.offSince = A.np.offSince or t
                if t - A.np.offSince >= A.NP_OFF_S then A.npRestore("route off") end
            else
                A.np.state = A.np.state == "restored" and "restored" or "idle"
            end
        end
    end

    -- ---------------------------------------------------------------- THE PLATE ICONS (ag2; the user: "Build now", icons only)
    -- Our own small frames on UIParent, ANCHORED above each slotted plate (never parented into it, nothing of the plate written),
    -- the game's atlases at runtime, cleared when the plate goes:
    --   AVOID (dungeonskull): an elite / rare elite / boss, a "??" (skull) level, a level >= ours + 3, or a mob in a fight that is
    --         not on us within 30 yd (it may turn on us);
    --   QUEST (smallquestbang, the signed-off F spec): a quest objective (qi);
    --   the pull picks p1 / p2 (TargetCrosshairs) come with the app's tap row, later.
    A.icon = {}
    function A.iconFor(s)
        local o = A.icon[s]
        if o then return o end
        local u = (C and C.u) or function(px) return px * 768 / 1080 end
        local f = CreateFrame("Frame", nil, UIParent)
        f:SetFrameStrata("MEDIUM")
        f:EnableMouse(false)
        f:SetSize(u(60), u(30))
        f:Hide()
        local av = f:CreateTexture(nil, "ARTWORK")
        av:SetSize(u(26), u(26))
        av:SetPoint("RIGHT", f, "CENTER", u(14), 0)
        pcall(function() av:SetAtlas("dungeonskull") end)
        local q = f:CreateTexture(nil, "ARTWORK")
        q:SetSize(u(30), u(30))
        q:SetPoint("LEFT", f, "CENTER", -u(14), 0)
        pcall(function() q:SetAtlas("smallquestbang") end)
        o = { f = f, av = av, q = q }
        A.icon[s] = o
        return o
    end
    function A.avoidOf(m, plv)
        if m.c == "e" or m.c == "R" or m.c == "b" then return true end
        if m.lv and (m.lv < 0 or (plv and m.lv >= plv + 3)) then return true end
        if m.cb == 1 and not (m.om and m.om >= 2) and m.b and m.b <= 30 then return true end
        return false
    end
    function A.iconsStep(list)
        local plv = pv(UnitLevel, "player")
        local want = {}
        for _, m in ipairs(list or {}) do
            if not m.dd and A.slot[m.s] then
                local av, qq = A.avoidOf(m, plv), m.qi == 1
                if av or qq then want[m.s] = { av = av, q = qq, tok = A.slot[m.s].tok } end
            end
        end
        A.iconN = 0
        for s, o in pairs(A.icon) do
            local w = want[s]
            if not w then
                if o.f:IsShown() then o.f:Hide() end
                o.tok = nil
            end
        end
        for s, w in pairs(want) do
            local okP, plate = pcall(function() return C_NamePlate.GetNamePlateForUnit(w.tok) end)
            if okP and plate then
                local o = A.iconFor(s)
                if o.tok ~= w.tok then
                    o.f:ClearAllPoints()
                    pcall(function() o.f:SetPoint("BOTTOM", plate, "TOP", 0, 2) end)
                    o.tok = w.tok
                end
                if w.av then o.av:Show() else o.av:Hide() end
                if w.q then o.q:Show() else o.q:Hide() end
                if not o.f:IsShown() then o.f:Show() end
                A.iconN = A.iconN + 1
            end
        end
    end
    function A.iconsClear()
        for _, o in pairs(A.icon) do o.f:Hide(); o.tok = nil end
        A.iconN = 0
    end

    -- ---------------------------------------------------------------- the probe line (as the compass's), every 5 s
    A.diagAt = -100
    function A.diag()
        local t = now()
        if t - A.diagAt < 5 then return end
        A.diagAt = t
        local nTok, nSlot = 0, 0
        for _ in pairs(A.toks) do nTok = nTok + 1 end
        for s = 0, A.MAX - 1 do if A.slot[s] then nSlot = nSlot + 1 end end
        local nb = { s = 0, i = 0, d = 0 }
        for _, b in ipairs(A.bands) do nb[b.kind] = nb[b.kind] + 1 end
        local line = { type = "cap", probe = "aggro", v = A.V, plates = nTok, slots = nSlot, bs = nb.s, bi = nb.i, bd = nb.d,
                       sx = A.sxPlain and true or false, off = A.off, q = A.q, full = A.sent.full, same = A.sent.same,
                       still = A.sent.still, rej = A.lastReject, err = A.err,
                       np = A.np.state, npv = npGet(), npo = A.npSaved() and A.npSaved().orig or nil, icons = A.iconN or 0,
                       route = type(WC.routeWp) == "table" and WC.routeWp.active == true }
        A.lastDiag = line
        pcall(function() WC.send(WC.encode(line)) end)
    end

    -- ---------------------------------------------------------------- events and the ticker
    local ev = CreateFrame("Frame")
    for _, e in ipairs({ "PLAYER_ENTERING_WORLD", "NAME_PLATE_UNIT_ADDED", "NAME_PLATE_UNIT_REMOVED", "SPELLS_CHANGED",
                         "ITEM_DATA_LOAD_RESULT", "CVAR_UPDATE", "ENCOUNTER_START", "ENCOUNTER_END", "UNIT_SPELLCAST_SENT",
                         "PLAYER_ENTER_COMBAT", "START_AUTOREPEAT_SPELL", "UNIT_THREAT_LIST_UPDATE", "PLAYER_LOGOUT" }) do
        pcall(function() ev:RegisterEvent(e) end)
    end
    ev:SetScript("OnEvent", function(_, e, a1)
        local ok, err = pcall(function()
            if e == "NAME_PLATE_UNIT_ADDED" then A.onAdded(a1)
            elseif e == "NAME_PLATE_UNIT_REMOVED" then A.onRemoved(a1)
            elseif e == "PLAYER_LOGOUT" then A.npRestore("logout")
            elseif e == "PLAYER_ENTERING_WORLD" then
                -- a value of ours left from before (a crash): back to the player's once the route is not running (npStep's grace)
                if A.npSaved() then A.np.offSince = now() - A.NP_OFF_S + 3 end
                -- a reload / login: every slot cleared, q from 1 (the contract)
                A.slot, A.byTok, A.toks, A.freeAt, A.q, A.hash, A.ghosts = {}, {}, {}, {}, 0, nil, {}
                A.buildBands()
                A.covAt = now() + 2
            elseif e == "SPELLS_CHANGED" or e == "ITEM_DATA_LOAD_RESULT" then A.rebuildAt = now() + 0.5
            elseif e == "CVAR_UPDATE" then
                for _, name in pairs(A.CV) do if a1 == name then A.covAt = now() end end
            elseif e == "ENCOUNTER_START" then A.enc = true
            elseif e == "ENCOUNTER_END" then A.enc = false
            elseif e == "UNIT_SPELLCAST_SENT" then if a1 == "player" then A.lastAction = now() end
            elseif e == "PLAYER_ENTER_COMBAT" or e == "START_AUTOREPEAT_SPELL" then A.lastAction = now()
            elseif e == "UNIT_THREAT_LIST_UPDATE" then
                -- a plate's threat changed: read it now (the calm reading is at most one poll old, FSR's re-read)
                local s = a1 and A.byTok[a1]
                if s ~= nil and A.slot[s] then A.read(s, A.slot[s], now(), pv(InCombatLockdown) == true) end
            end
        end)
        if not ok then A.err = tostring(err):sub(1, 80) end
    end)
    local acc = 0
    ev:SetScript("OnUpdate", function(_, el)
        acc = acc + (el or 0)
        if acc < A.SCAN_S then return end
        acc = 0
        local ok, err = pcall(function()
            local t = now()
            if A.rebuildAt and t >= A.rebuildAt then A.rebuildAt = nil; A.buildBands() end
            if A.covAt and t >= A.covAt then A.covAt = nil; A.coverage() end
            if A.covDirty and not A.covAt then A.covAt = t + 0.5 end
            A.npStep()
            A.scan()
            if A.off then A.iconsClear() else A.iconsStep(A.last and A.last.m) end
            A.still()
            A.diag()
        end)
        if not ok then A.err = tostring(err):sub(1, 80) end
    end)
end
