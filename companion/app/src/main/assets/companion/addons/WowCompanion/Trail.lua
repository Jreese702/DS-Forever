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
WC.compass = { V = "cp22" }
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
            if key == "quest" then                         -- cp17: the quest's NUMBER on the game's own POI number disc
                o.num = f:CreateFontString(nil, "OVERLAY", nil, 7)
                o.num:SetFont(C.FONT, u(15), "")
                o.num:SetTextColor(0.12, 0.08, 0.02, 1)
            end
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
              BASE = 3.146, KB_TUNE = 0.1, base = nil, ys = 1.3, inv = false, tw = 3, fol = false, gp = nil, rx = 0, acc = 0,
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
        K.lx, K.ly = nil, nil
        if okS and type(st) == "table" and type(st.sticks) == "table" then
            local n = num(st.stickCount) or #st.sticks
            local camX
            for i = 1, n do
                local okN, name = pcall(function() return C_GamePad.StickIndexToConfigName(i - 1) end)
                if okN and name == "Movement" then              -- cp15: the movement stick, for the true-up
                    local okM, mx, my = pcall(function() return st.sticks[i].x, st.sticks[i].y end)
                    if okM then K.lx, K.ly = num(mx), num(my) end
                elseif okN and name == "Camera" then
                    local okX, x, y = pcall(function() return st.sticks[i].x, st.sticks[i].y end)
                    camX = okX and num(x) or nil
                    K.ry = okX and num(y) or nil                    -- cp20: a tilt moves the pitch
                end
            end
            if camX then K.from = "pad"; return camX end
        end
        if K.extX and now - K.extAt < 0.3 then K.from = "app"; return K.extX end
        K.from = "none"
        return 0
    end

    -- cp16 (test26, the user: "doing a full 360 with the camera does not land it in the expected same spot"): the game turns its
    -- camera in its own engine (no curve, ramp or acceleration CVar exists for it; GetDeviceMappedState's stick is already past its
    -- deadzone), so the law is LEARNED: yaw rate = kb x GamePadCameraYawSpeed x sign(x) x |x|^p. Every two observations of the true
    -- yaw (an accepted marker fix, a cp15 true-up) with the stick turned between them make a lesson: the turn they show (unwrapped
    -- by the model's own prediction, so a long turn does not alias) against the stick's integral for each candidate exponent; the
    -- exponent whose least-squares fit is best wins, with its kb. Kept in OUR saved data (WowCompanionDB.compassCal) across sessions.
    -- cp18 (cal1, test32: the user's 120 s calibration, the true yaw from the quest's marker on screen): the law is MEASURED, not
    -- guessed. The game turns its camera LINEARLY with the stick: 3.146 rad/s (180 degrees a second) x GamePadCameraYawSpeed x x,
    -- no deadzone past the mapped state's, no ramp, no smoothing, no lag beyond a frame (replayed: every full circle of the
    -- recording lands within 3 degrees, 1.2 degrees rms). So the exponent is fixed at 1 and the lessons only fine-tune the rate,
    -- within KB_TUNE (10 %) of the measured one; a law saved by cp16 / cp17 (a learned curve) is not loaded.
    C.P = { 1 }
    function C.rateOf(x)
        local K = C.cam
        local a = math.abs(x)
        if a <= 0 then return 0 end
        return (x > 0 and 1 or -1) * (a ^ (K.p or 1)) * (K.kb or K.BASE) * K.ys
    end
    function C.calLoad()
        local K = C.cam
        local c = type(WowCompanionDB) == "table" and WowCompanionDB.compassCal or nil
        if type(c) == "table" and c.v == "cp18" and tonumber(c.kb) then K.kb, K.p, K.calN = C.tuned(tonumber(c.kb)), 1, tonumber(c.n) or 0 end
    end
    function C.calSave()
        local K = C.cam
        if type(WowCompanionDB) ~= "table" then WowCompanionDB = {} end
        WowCompanionDB.compassCal = { v = "cp18", kb = K.kb, p = K.p, n = K.calN or 0 }
    end
    -- the fine-tuned rate, kept within KB_TUNE of the measured one
    function C.tuned(kb)
        local K = C.cam
        local lo, hi = K.BASE * (1 - K.KB_TUNE), K.BASE * (1 + K.KB_TUNE)
        return math.max(lo, math.min(hi, kb))
    end
    -- the best (p, kb) over the kept lessons
    function C.fit()
        local K = C.cam
        local L = K.pairs
        if not L or #L < 3 then return end
        local best, bp, bk
        for _, pp in ipairs(C.P) do
            local sdi, sii, n = 0, 0, 0
            -- (every lesson counts for every exponent: an out-and-back that nets to nothing under the TRUE exponent is the best
            -- evidence for it)
            for _, e in ipairs(L) do
                local I = e.I[pp]
                if I then sdi, sii, n = sdi + e.d * I, sii + I * I, n + 1 end
            end
            if n >= 3 and sii > 0 then
                local k = sdi / sii
                local err = 0
                for _, e in ipairs(L) do
                    local I = e.I[pp]
                    if I then err = err + (e.d - k * I) ^ 2 end
                end
                err = err / n
                if k > 0 and (not best or err < best) then best, bp, bk = err, pp, k end
            end
        end
        if bp then
            K.p, K.kb = bp, C.tuned(bk / K.ys)
            K.calN = #L
            K.base = K.kb                                   -- (the line's "base", deg/s at speed 1, and "learned")
            C.calSave()
        end
    end
    -- an observation of the TRUE camera yaw (cam, CCW radians): a lesson when the stick turned enough since the last one
    function C.observe(cam)
        local K = C.cam
        K.I = K.I or {}
        local G1 = K.I.g or 0                               -- the stick's GROSS integral since (an out-and-back nets to nothing)
        if K.obsCam and G1 >= 0.3 then
            local pred = K.predSince or 0                   -- clockwise, the model's turn since the last observation
            local meas = -C.wrap(cam - K.obsCam)            -- clockwise, as seen (within one turn)
            local d = pred + C.wrap(meas - pred)            -- unwrapped by the prediction
            K.lastRes = d - pred                            -- the self-check: what the model missed by
            K.pairs = K.pairs or {}
            local I = {}
            for k, v in pairs(K.I) do if k ~= "g" then I[k] = v end end
            K.pairs[#K.pairs + 1] = { d = d, I = I }
            while #K.pairs > 16 do table.remove(K.pairs, 1) end
            C.fit()
            K.obsCam, K.I, K.predSince, K.grossSince = cam, {}, 0, 0
        elseif G1 < 0.02 then
            K.obsCam, K.I, K.predSince, K.grossSince = cam, {}, 0, 0   -- nothing turned since: the observation moves with it
        end
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
        if not K.calLoaded then K.calLoaded = true; C.calLoad() end
        if not K.abs then K.abs = facing end
        -- cp12 (test21, the user: "it seems to be trying to track the charactor direction at the same time ... While walking"): the
        -- CAMERA is the source at all times. The stick is integrated moving or not; while the character turns with the camera (the
        -- setting), the facing only ANCHORS it slowly (ANCHOR_TAU), and only once the stick has been idle IDLE_S and the character
        -- has walked straight STRAIGHT_S (then the game has turned it onto the camera); never a switch to the facing.
        if math.abs(x) > 0.05 then K.idleSince = now end
        if K.lastF and dt > 0 and math.abs(C.wrap(facing - K.lastF)) / dt > K.STRAIGHT_RATE then K.straightSince = now end
        K.lastF = facing
        if x ~= 0 then
            local sg = K.inv and -1 or 1
            local d = C.rateOf(x) * dt * sg                                    -- cp16: the learned law
            K.abs = C.wrap(K.abs - d)
            if K.ref then K.ref = C.wrap(K.ref - d) end                         -- the last fix, carried by the stick (cp13)
            K.acc = K.acc + x * dt * sg                                       -- stick-seconds since the last fix (the gate's allowance)
            K.predSince = (K.predSince or 0) + d                              -- the model's clockwise turn since the last observation
            K.grossSince = (K.grossSince or 0) + math.abs(d)                  -- and how much it turned either way (the gate's allowance)
            K.I = K.I or {}
            local a = math.abs(x)
            for _, pp in ipairs(C.P) do K.I[pp] = (K.I[pp] or 0) + (x > 0 and 1 or -1) * (a ^ pp) * dt * sg end
            K.I.g = (K.I.g or 0) + a * dt
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
        -- cp15, THE TRUE-UP (the user: "when ever the controls stick points directly up, we know the true direction that the camera is
        -- currently facing ... use that data occasionally and very gently true up the compass"): with the movement stick held straight
        -- forward, the character MOVES along the camera's yaw. Its heading is measured from the player's own positions over ~0.4 s
        -- (at least 1.5 yd; two windows agreeing within 10 degrees; not swimming, flying or on a taxi; the camera stick idle 0.5 s,
        -- the forward push held 0.5 s), and the estimate eases toward it (2.5 s; at most 3 degrees a frame). Never a snap.
        if C.me_ and C.me_ ~= K.lastMe then
            K.lastMe = C.me_
            K.hist = K.hist or {}
            K.hist[#K.hist + 1] = { t = now, x = C.me_.x, y = C.me_.y }
            while #K.hist > 0 and now - K.hist[1].t > 1.2 do table.remove(K.hist, 1) end
        end
        local fwd = K.lx and K.ly and math.abs(K.lx) < 0.15 and K.ly > 0.8
        if fwd then K.fwdSince = K.fwdSince or now else K.fwdSince = nil end
        K.ferr = nil
        if fwd and moving and now - K.fwdSince >= 0.5 and now - K.idleSince >= 0.5 and K.hist and #K.hist >= 3 then
            local okW, wet = pcall(function() return IsSwimming() or IsFlying() or UnitOnTaxi("player") end)
            if not (okW and wet) then
                local function headingOver(t0, t1)
                    local a, b
                    for _, h in ipairs(K.hist) do
                        if h.t <= t1 then b = h end
                        if not a and h.t >= t0 then a = h end
                    end
                    if not (a and b) or a == b then return nil end
                    local dx, dy = b.x - a.x, b.y - a.y
                    if math.sqrt(dx * dx + dy * dy) < 1.5 then return nil end
                    return atan2(dy, dx)
                end
                local h1 = headingOver(now - 0.9, now - 0.45)
                local h2 = headingOver(now - 0.45, now)
                if h1 and h2 and math.abs(C.wrap(h2 - h1)) < math.rad(10) then
                    local err = C.wrap(h2 - K.abs)
                    K.ferr = err
                    C.observe(h2)                                       -- cp16: the true-up teaches the law too
                    local step = err * (1 - math.exp(-dt / 2.5))
                    local cap = math.rad(3)
                    if step > cap then step = cap elseif step < -cap then step = -cap end
                    K.abs = C.wrap(K.abs + step)
                    if K.ref then K.ref = C.wrap(K.ref + step) end
                    K.src = "fwd"
                end
            end
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
                    -- 15 degrees plus 20% of what the stick turned since the last fix (cp18: the measured law is good to ~3%; it was
                    -- 40-60% while the rate was a guess); more is a mirror or junk. Never verified yet (no fix since camera mode came
                    -- on), or the follow camera on and moving: 100.
                    local tol = math.rad(15) + 0.2 * (K.grossSince or 0)
                    if (K.fol and moving) or not K.lastFix then tol = K.MAXOFF end     -- (never verified yet: the wide gate)
                    K.tol = tol
                    if math.abs(errRef) <= math.min(tol, K.MAXOFF) then
                        C.observe(cam)                                   -- cp16: the lesson (and the self-check)
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
    -- cp19 (test33, the user: "when I jump it moves up and down. It doesn't feel like its properly anchored and they don't always
    -- show where the flags are at"):
    --   THE JUMP: the pin is a map point with no height, and the engine projects it at the player's own height, so it rose and fell
    --   with every jump while the ground stood still. The marker's HEIGHT is held while the player is airborne (IsFalling, at most
    --   HOLD_S: a long fall is followed), then eases back (EASE_S); across, it stays the engine's.  (cp22 below replaces the hold.)
    --   THE FLAGS: the app's map shows the checkpoints AFTER the pin's too. The app sends them (Map.lua's uwp 38, WC.routeWp.more)
    --   and each gets our own marker, smaller and fainter the further along it is, placed across by the camera's yaw (exact from the
    --   engine's point when that is on screen, else cp18's estimate) and the FOV; up and down at the engine point's height (no
    --   camera pitch is readable: the horizon band), the middle of the screen when the engine's point is not to be seen.
    --   IN FRONT: tested against the camera's yaw, also with the bar on the facing (the estimate runs always, cp19).
    C.wm = { SIZE = 34, EASE_S = 0.12, LAND_S = 0.5, MORE_K = { 0.8, 0.68, 0.58 }, MORE_A = { 0.85, 0.65, 0.5 },
             list = {}, airAt = -100, n = 0, CAM_H = 1.9, ZOOM = 12, PITCH_S = 3, TILT = 0.1, PITCH0 = 0.3,
             JUMP_V = 7.9558, MOUNT_V = 7.9558, GRAV = 19.2911, PRESS_S = 0.3, jumpAt = -100, jumpN = 0, LOG_S = 5, logAt = -100,
             GOLD = { 1, 0.82, 0.3 }, QUEST = { 1, 0.93, 0.5 }, PIN = "Interface\\AddOns\\WowCompanion\\Media\\marker_pin" }
    -- cp22 (the user: "The world markers for the quests can go back to just being the pin icon ... when I jump they need to stay
    -- anchored"):
    --   THE LOOK: cp21's beam + rings are gone; every marker is OUR pin (Media/marker_pin.tga, tools/make_world_markers.py, tinted
    --   gold for the route, pale gold for a quest) with its TIP on the ground point (anchored by its bottom), the same sizes.
    --   THE JUMP: cp19 held the engine point's screen height in the air, but the camera rises with the character, so the ground
    --   point drops on screen while a held marker stays: the bob. Now, airborne (IsFalling; never swimming or flying), the ground
    --   height held is moved by the camera's RISE above the takeoff (C.wmLift), projected with the pitch: UnitPosition's z when it
    --   really moves, else WoW's jump arc (JUMP_V up, GRAV down: 1.64 yd at 0.41 s, down at 0.82 s; MOUNT_V mounted) from the
    --   jump press (JumpOrAscendStart, hooked: read only) - a step off a ledge has no arc. Past the arc (a long fall) the last
    --   ground height is HELD, not followed. Landing (early on a slope too) eases to the engine's point (EASE_S, for LAND_S).
    --   Each landing logs a "jump" cap line (time in the air, the press, z, the engine point's travel) to measure the arc on device.
    -- cp20 (the user: "Sometimes they are perfectly glued to the ground, other times they float in the air ... Try to ground them
    -- all"): the CAMERA'S PITCH, solved from the engine's own point. The camera orbits a pivot CAM_H above the player's feet at the
    -- zoom distance (GetCameraZoom, read), tilted down by the pitch; the engine projects the pin (a map point with no height) at the
    -- player's own height (the cp19 jump bob showed it). Its screen height then says the pitch (one unknown, a bisection). With the
    -- pitch, any point's screen height follows from its distance and its height relative to the player: the further flags (and the
    -- pin itself) are drawn ON THE GROUND from the heights the app sends (Map.lua's uwp z digits: the app's terrain, a hint; the
    -- player's climb since then taken off when UnitPosition's z is plain). A pitch older than PITCH_S, or a tilt of the camera stick
    -- since, is no pitch: the further flags hide rather than float.
    -- the screen height (UI units above the centre) of a point dfwd ahead and dz above the player's feet, or nil behind the camera
    function C.wmProj(phi, D, H, dfwd, dz, fl)
        local vf = dfwd + D * math.cos(phi)
        local vu = dz - H - D * math.sin(phi)
        local depth = vf * math.cos(phi) - vu * math.sin(phi)
        if depth < 0.5 then return nil end
        return fl * (vf * math.sin(phi) + vu * math.cos(phi)) / depth
    end
    -- the pitch that puts a point dfwd ahead at the player's height yOff above the centre (nil: none fits)
    function C.wmPitch(yOff, dfwd, D, H, fl)
        local lo, hi = -0.5, 1.45
        local glo, ghi = C.wmProj(lo, D, H, dfwd, 0, fl), C.wmProj(hi, D, H, dfwd, 0, fl)
        if not glo or not ghi or glo > yOff or ghi < yOff then return nil end
        for _ = 1, 40 do
            local mid = (lo + hi) / 2
            local g = C.wmProj(mid, D, H, dfwd, 0, fl)
            if not g then return nil end
            if g < yOff then lo = mid else hi = mid end
        end
        return (lo + hi) / 2
    end
    -- cp22: the camera's rise (yd) above where the player left the ground; 0 on the ground, past the arc, or with no arc
    function C.wmLift(now)
        local W = C.wm
        if not W.air then return 0 end
        if W.zReal and W.pz0 then
            local pz = C.wmPz()
            if pz then return pz - W.pz0 end
        end
        if W.kind ~= "jump" then return 0 end
        local v, t = W.mounted and W.MOUNT_V or W.JUMP_V, now - (W.airSince or now)
        if t >= 2 * v / W.GRAV then return 0 end
        return v * t - W.GRAV * t * t / 2
    end
    -- cp22: airborne or not this frame (not swimming, not flying); the takeoff's kind, the landing's log line
    function C.wmAir(now)
        local W = C.wm
        local okA, air = pcall(function() return IsFalling() end)
        air = okA and air == true
        local okS, sw = pcall(function() return IsSwimming() end)
        local okF, fly = pcall(function() return IsFlying() end)
        if (okS and sw == true) or (okF and fly == true) then air = false end
        local pz = C.wmPz()
        if pz then
            W.zFirst = W.zFirst or pz
            if math.abs(pz - W.zFirst) > 0.05 then W.zReal = true end      -- (z read plainly AND moving: a real height)
        end
        if air and not W.air then
            local okM, mt = pcall(function() return IsMounted() end)
            W.airSince, W.pz0, W.mounted = now, pz, okM and mt == true
            -- a jump when the press came just before (or the press was never seen: the hook may not fire), else a step off a ledge
            W.kind = (now - W.jumpAt < W.PRESS_S or W.jumpN == 0) and "jump" or "fall"
            W.jl = { ny0 = W.ny, nyLo = W.ny, nyHi = W.ny, pzHi = pz, pzLo = pz }
        elseif W.air and not air then
            local jl = W.jl
            if jl and now - W.logAt > W.LOG_S and WC.send and WC.encode then
                W.logAt = now
                pcall(WC.send, WC.encode({ type = "cap", probe = "jump", v = C.V, air = now - (W.airSince or now), kind = W.kind,
                    press = W.jumpN, mount = W.mounted or nil, zreal = W.zReal or nil, pz0 = W.pz0, pzHi = jl.pzHi, pzLo = jl.pzLo,
                    ny0 = jl.ny0, nyHi = jl.nyHi, nyLo = jl.nyLo }))
            end
            W.jl = nil
        end
        if air then
            W.airAt = now
            local jl = W.jl
            if jl then
                if pz then jl.pzHi = math.max(jl.pzHi or pz, pz); jl.pzLo = math.min(jl.pzLo or pz, pz) end
                if W.ny then jl.nyHi = math.max(jl.nyHi or W.ny, W.ny); jl.nyLo = math.min(jl.nyLo or W.ny, W.ny) end
            end
        else
            W.airSince = nil
        end
        W.air = air
        return air
    end
    if type(hooksecurefunc) == "function" and type(rawget(_G, "JumpOrAscendStart")) == "function" then
        pcall(hooksecurefunc, "JumpOrAscendStart", function()
            local W = C.wm
            W.jumpAt, W.jumpN = (type(GetTime) == "function" and GetTime()) or 0, W.jumpN + 1
        end)
    end
    -- cp22: a point's height relative to the player's feet in the air too (its height less the camera's rise; UnitPosition's z
    -- already counts it when real)
    function C.wmDzAir(p)
        local W = C.wm
        local b = C.wmDz(p)
        if W.zReal and b and type(p) == "table" and num(p.zAt) then return b end
        return (b or 0) - (W.lift or 0)
    end
    function C.wmZoom()
        local ok, z = pcall(GetCameraZoom)
        z = ok and num(z) or nil
        return (z and z >= 0 and z <= 60) and z or C.wm.ZOOM
    end
    -- the player's height when UnitPosition gives it plainly
    function C.wmPz()
        local ok, _, _, z = pcall(UnitPosition, "player")
        return ok and num(z) or nil
    end
    -- a point's height relative to the player NOW (its sent height less the climb since), nil = unknown
    function C.wmDz(p)
        if type(p) ~= "table" or not num(p.dz) then return nil end
        local pz = C.wmPz()
        if pz and num(p.zAt) then return p.dz - (pz - p.zAt) end
        return p.dz
    end
    function C.wmBuild(i)
        local W = C.wm
        i = i or 1
        if W.list[i] then return W.list[i].f end
        local k = i == 1 and 1 or (W.MORE_K[i - 1] or 0.5)
        local f = CreateFrame("Frame", nil, UIParent)
        f:SetFrameStrata("MEDIUM")
        f:EnableMouse(false)
        f:SetSize(C.u(W.SIZE * k), C.u(W.SIZE * k))
        f:Hide()
        local sh = f:CreateTexture(nil, "ARTWORK", nil, 1)
        sh:SetTexture(W.PIN)
        sh:SetPoint("CENTER", f, "CENTER", 0, 0)
        sh:SetSize(C.u(W.SIZE * k + 4), C.u(W.SIZE * k + 4))
        sh:SetVertexColor(0, 0, 0, 0.85)
        local tx = f:CreateTexture(nil, "ARTWORK", nil, 3)
        tx:SetTexture(W.PIN)
        tx:SetAllPoints(f)
        local d = f:CreateFontString(nil, "OVERLAY")
        d:SetFont(C.FONT, C.u(i == 1 and 13 or 11), "OUTLINE")
        d:SetPoint("TOP", f, "BOTTOM", 0, -C.u(2))
        W.list[i] = { f = f, sh = sh, tx = tx, d = d }
        if i == 1 then W.f, W.sh, W.tx, W.d = f, sh, tx, d end
        return f
    end
    function C.wmHide()
        for _, o in pairs(C.wm.list) do if o.f:IsShown() then o.f:Hide() end end
    end
    -- cp22: our pin, its TIP (the frame's bottom) at x, y; col its tint
    local function wmPut(i, x, y, col, txt, alpha)
        C.wmBuild(i)
        local o = C.wm.list[i]
        o.f:ClearAllPoints()
        o.f:SetPoint("BOTTOM", UIParent, "BOTTOMLEFT", x, y)
        if o.col ~= col then o.tx:SetVertexColor(col[1], col[2], col[3]); o.col = col end
        if o.text ~= txt then o.d:SetText(txt); o.text = txt end
        if o.alpha ~= alpha then pcall(function() o.f:SetAlpha(alpha) end); o.alpha = alpha end
        if not o.f:IsShown() then o.f:Show() end
        o.x, o.y = x, y
    end
    -- a pitch to place by: fresh, and the camera not tilted since
    function C.wmPitchOk(now)
        local W = C.wm
        if C.cam.ry and math.abs(C.cam.ry) > W.TILT then W.tilted = true end
        return W.pitch ~= nil and now - (W.pitchAt or -100) < W.PITCH_S and not W.tilted
    end
    function C.wmStep(facing, dt)
        local W = C.wm
        dt = num(dt) or 0
        local now = (type(GetTime) == "function" and GetTime()) or 0
        local on = {}
        if C.wanted() and C.frame and C.frame:IsShown() and C.me_ then
            local okU, us = pcall(function() return UIParent:GetEffectiveScale() end)
            local okW, uw = pcall(function() return UIParent:GetWidth() end)
            local okH, uh = pcall(function() return UIParent:GetHeight() end)
            us, uw, uh = okU and num(us) or 1, okW and num(uw) or nil, okH and num(uh) or nil
            local air = C.wmAir(now)
            W.lift = C.wmLift(now)
            local hfov = C.cam.hfov or math.rad(90)
            local yaw = C.cam.abs or facing
            local okF, nav = pcall(function() return C_Navigation.GetFrame() end)
            local okP, onPin = pcall(function() return C_SuperTrack.IsSuperTrackingUserWaypoint() end)
            onPin = okP and onPin == true
            local tg = C.tg_ or {}
            local w = onPin and tg.pin or tg.quest
            if uw and us > 0 then
                local half = uw / 2
                local fl = half / math.tan(hfov / 2)
                -- 1: the engine's own point (the pin, else the focused quest)
                if okF and nav and w then
                    local okV, vp = pcall(function() return C_Navigation.HasValidScreenPosition() end)
                    local okK, cl = pcall(function() return C_Navigation.WasClampedToScreen() end)
                    local okC, x, y = pcall(function() return nav:GetCenter() end)
                    local okS, ns = pcall(function() return nav:GetEffectiveScale() end)
                    x, y = okC and num(x) or nil, okC and num(y) or nil
                    ns = okS and num(ns) or 1
                    if okV and vp == true and okK and cl == false and x and y then
                        local b, d = C.bearing(C.me_, w)
                        if math.abs(C.wrap(b - yaw)) < hfov / 2 + math.rad(10) then
                            x, y = x * ns / us, y * ns / us
                            yaw = C.wrap(b + math.atan((x - half) / fl))         -- (exact while the point is on screen)
                            -- cp20: the pitch from this point (not in the air: it rises with the player then)
                            local dfwd = d * math.cos(math.atan((x - half) / fl))
                            W.pinFwd = dfwd
                            if not air and uh then
                                local ph = C.wmPitch(y - uh / 2, dfwd, C.wmZoom(), W.CAM_H, fl)
                                if ph then W.pitch, W.pitchAt, W.tilted = ph, now, false end
                            end
                            W.ny = y                                             -- (the engine's own, for the jump log)
                            -- cp22: in the air the engine's point rises with us: the ground's height held, then the pin's own
                            -- ground (cp20) and the camera's rise (the jump) projected with the pitch
                            local yE = (air and W.yG) or y
                            if not air then W.yG = y end
                            local R0 = WC.routeWp
                            local pdz = onPin and type(R0) == "table" and C.wmDz(R0.pinZ) or nil
                            W.pinDz = pdz
                            local usePdz = pdz ~= nil and C.wmPitchOk(now)
                            local dz = C.wmDzAir(usePdz and R0.pinZ or nil)
                            y = yE
                            if dz ~= 0 then
                                local D, phi = C.wmZoom(), W.pitch or W.PITCH0
                                local a0 = C.wmProj(phi, D, W.CAM_H, dfwd, 0, fl)
                                local a1 = C.wmProj(phi, D, W.CAM_H, dfwd, dz, fl)
                                if a0 and a1 then y = yE + (a1 - a0) end
                            end
                            if not air and W.yOut and now - W.airAt < W.LAND_S then
                                y = W.yOut + (y - W.yOut) * (1 - math.exp(-dt / W.EASE_S))   -- landed: eased, not snapped
                            end
                            W.yOut = y
                            wmPut(1, x, y, onPin and W.GOLD or W.QUEST, C.distText(d), 1)
                            on[1] = true
                        end
                    end
                end
                -- 2..: the route's further checkpoints (the app's, after the pin's)
                local R = WC.routeWp
                if onPin and type(R) == "table" and R.active == true and type(R.more) == "table" then
                    local okP = C.wmPitchOk(now) and uh
                    local D = C.wmZoom()
                    for i, c in ipairs(R.more) do
                        if i > #W.MORE_K or not okP then break end            -- cp20: no pitch, no flags (never floating)
                        if c.w == nil then c.w = C.world(c.m, c.x, c.y) or false end
                        local wk = c.w
                        if wk and not (wk.cont and C.me_.cont and wk.cont ~= C.me_.cont) then
                            local b, d = C.bearing(C.me_, wk)
                            local rel = C.wrap(b - yaw)
                            if d > 8 and math.abs(rel) < hfov / 2 then
                                local x = half - fl * math.tan(rel)
                                local yo = C.wmProj(W.pitch, D, W.CAM_H, d * math.cos(rel), C.wmDzAir(c), fl)
                                local y = yo and uh / 2 + yo or nil
                                if y and math.abs(x - half) < half * 0.95 and y > 0 and y < uh then
                                    wmPut(i + 1, x, y, W.GOLD, C.distText(d), W.MORE_A[i])
                                    on[i + 1] = true
                                end
                            end
                        end
                    end
                end
            end
        end
        local n = 0
        for i, o in pairs(W.list) do
            if on[i] then n = n + 1 elseif o.f:IsShown() then o.f:Hide() end
        end
        W.shown, W.n = on[1] == true, n
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
        -- cp19: the camera's estimate runs with the bar on the facing too (the world marker's in-front test and its flags need it)
        local camOff = C.camStep(dt, raw)
        if C.camWanted() then target = C.wrap(raw + camOff) else C.cam.off, C.cam.src = 0, "off" end
        local heading = C.ease(target, dt)
        pcall(C.wmStep, raw, dt)
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
            if o.num then
                -- the test27 ask (the user: "instead of the quest Icon, we should use the quest number so its totally clear what its
                -- tracking"): the app's number for it (its place among the watched quests, the app's own count) on the game's POI disc;
                -- no number (an unwatched focus): the "!" as before
                local n = C.questNo and C.questNo()
                local atlas = n and "UI-QuestPoi-QuestNumber" or spec.atlas
                if o.atlas ~= atlas then
                    pcall(function() o.tx:SetAtlas(atlas) end); pcall(function() o.sh:SetAtlas(atlas) end); o.atlas = atlas
                end
                if n then
                    if o.numText ~= n then o.num:SetText(tostring(n)); o.numText = n end
                    o.num:SetPoint("CENTER", f, "TOP", u(m.x), -u(S.line)); show(o.num)
                else
                    hide(o.num)
                end
            end
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
            if not drawn[key] then hide(o.sh); hide(o.tx); hide(o.d); hide(o.a); if o.num then hide(o.num) end end
        end
    end

    -- the focused quest's NUMBER as the app counts it (MapView's trackedNo): its place among the WATCHED quests, in quest-log order
    -- (headers and hidden ones skipped, as the Quests add-on's document lists them); nil when the focus is not watched. Once a second.
    C.qnAt = -10
    function C.questNo()
        local t = (type(GetTime) == "function" and GetTime()) or 0
        if t - C.qnAt < 1 then return C.qn end
        C.qnAt = t
        C.qn = nil
        local okQ, st = pcall(function() return C_SuperTrack.GetSuperTrackedQuestID() end)
        local focus = okQ and num(st) or nil
        local R = WC.routeWp
        if R and R.active and R.qid and R.qid > 0 then focus = R.qid end   -- (the route's pin stands for it, as the app's focus)
        if not focus or focus <= 0 then return nil end
        local okN, n = pcall(function() return C_QuestLog.GetNumQuestLogEntries() end)
        n = okN and num(n) or 0
        local k = 0
        for i = 1, n do
            local okI, info = pcall(function() return C_QuestLog.GetInfo(i) end)
            if okI and type(info) == "table" and info.isHeader ~= true and info.isHidden ~= true then
                local qid = num(info.questID)
                if qid then
                    local okW, wt = pcall(function() return C_QuestLog.GetQuestWatchType(qid) end)
                    if okW and wt ~= nil then
                        k = k + 1
                        if qid == focus then C.qn = k; return k end
                    end
                end
            end
        end
        return nil
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
            wm = C.wm and C.wm.shown and true or false, wmn = C.wm and C.wm.n or 0, air = C.wm and C.wm.air or nil,
            pitch = (C.wm and C.wm.pitch) and math.floor(math.deg(C.wm.pitch) * 10 + 0.5) / 10 or nil,
            pAge = (C.wm and C.wm.pitchAt) and math.floor(((type(GetTime) == "function" and GetTime()) or 0) - C.wm.pitchAt) or nil,
            tilt = C.wm and C.wm.tilted or nil, zoom = C.wmZoom and math.floor(C.wmZoom() * 10 + 0.5) / 10 or nil,
            pdz = C.wm and C.wm.pinDz or nil,
            more = type(WC.routeWp) == "table" and type(WC.routeWp.more) == "table" and #WC.routeWp.more or 0,
            prd = (type(WC.prd) == "table" and WC.prd.f) and { a = math.floor((WC.prd.alpha or 0) * 100 + 0.5), why = WC.prd.why,
                src = WC.prd.src, y = WC.prd.y, nb = WC.prd.nb, nd = WC.prd.nd, pt = WC.prd.ptok, cbad = WC.prd.curveBad, ab = WC.prd.ab, ex = WC.prd.ex, w = WC.prd.wd, s = WC.prd.sc, sel = WC.prd.sel, prest = WC.prd.prest } or nil, tol = C.cam.tol and math.floor(math.deg(C.cam.tol) + 0.5) or nil,
            lx = C.cam.lx and math.floor(C.cam.lx * 100 + 0.5) / 100 or nil, ly = C.cam.ly and math.floor(C.cam.ly * 100 + 0.5) / 100 or nil,
            ferr = C.cam.ferr and math.floor(math.deg(C.cam.ferr) + 0.5) or nil,
            gp = C.cam.gp, from = C.cam.from, rx = C.cam.rx and math.floor(C.cam.rx * 100 + 0.5) / 100 or nil,
            ys = C.cam.ys, base = math.floor(math.deg(C.cam.kb or C.cam.BASE) + 0.5), learned = C.cam.kb and true or false,
            pexp = C.cam.p or 1, lessons = C.cam.calN or 0, res = C.cam.lastRes and math.floor(math.deg(C.cam.lastRes) + 0.5) or nil,
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
            if C.wm then C.wmHide() end
        end
        return show
    end

    -- the camera experiment's switch (Interact's ACT.compass camon / camoff)
    function C.setCam(on)
        if type(WowCompanionDB) ~= "table" then WowCompanionDB = {} end
        WowCompanionDB.compassCam = on and true or false
        C.cam.off, C.cam.abs, C.cam.fixAt, C.cam.lastFix, C.cam.acc, C.cam.ref = 0, nil, -100, nil, 0, nil
        C.cam.obsCam, C.cam.I, C.cam.predSince, C.cam.grossSince = nil, {}, 0, 0   -- (the learned law itself is kept)
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
WC.live = { V = "ag8", MAX = 24, REUSE_S = 10, SCAN_S = 0.2, FULL_S = 5, STILL_S = 1, slot = {}, byTok = {}, toks = {}, freeAt = {},
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
    -- ag5: the app ROUTES (Map.lua's pin up, or its KEEP / SET within KEEP_S: a direct route or a refused pin has no pin); test45's
    -- route=false came from reading the pin alone
    function A.routing()
        local R = WC.routeWp
        if type(R) ~= "table" then return false end
        if R.active == true then return true end
        if type(R.routing) == "function" then local ok, v = pcall(R.routing); return ok and v == true end
        return false
    end
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
        for k = 1, 2 do if A.pick and A.pick[k] and A.pick[k].s == s then A.pick[k] = nil end end
        if A.qset then A.qset[s] = nil end
        A.slot[s], A.byTok[tok], A.freeAt[s] = nil, nil, now()
        if A.actOn then A.actOn[tok] = nil end
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
        -- ag3: our marker on it now (the app's pull picks, the app's quest set): the app checks its taps landed by this
        if A.pick[1] and A.pick[1].s == s and A.pick[1].tok == tok then m.mk = "p1"
        elseif A.pick[2] and A.pick[2].s == s and A.pick[2].tok == tok then m.mk = "p2"
        elseif A.qset[s] == tok then m.mk = "q" end
        if pv(UnitIsDead, tok) == true then m.dd = 1 end
        -- the pull detector (Forever Range's): the last CALM reading, then a calm -> on-me turn
        -- ag8: the bracket when the mob ENTERED combat (it may turn on us a scan or two later: the calm reading then aged past the
        -- old 0.5 s window - 9 of test52's refusals were "stale"); the pull's near end is that onset, not where it has run to since
        if m.cb == 1 and not st.cbWas then st.onset = { a = m.a, t = t } end
        st.cbWas = m.cb == 1
        local onMe = m.om ~= nil and m.om >= 2
        -- ag7 (test52: a real fight and no pull line): the threat read can be secret; then "on me" = in combat and targeting us
        if m.om == nil and m.cb == 1 then onMe = pv(UnitIsUnit, tok .. "target", "player") == true end
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
    A.actOn = {}                  -- ag8: plate token -> when the player last attacked / cast with IT as the target
    A.STALE_S = 1.2               -- ag8: the calm reading may be this old (6 scans; was 0.5)
    -- ag8: the player acted (a cast sent, auto-attack, auto-repeat): on the mob that is the player's target now, if any
    function A.noteAction(t)
        A.lastAction = t
        for tok in pairs(A.byTok) do
            if pv(UnitIsUnit, tok, "target") == true then A.actOn[tok] = t end
        end
    end
    function A.pullCheck(s, st, m, t)
        local calm = st.calm
        local why
        -- ag8: "own action" only for an attack / cast on THIS mob within 2 s (a self-heal, a buff, a cast on another mob no longer
        -- throws a real pull away)
        if t - (A.actOn[st.tok] or -100) < 2 then why = "own action"
        elseif pv(UnitAffectingCombat, "pet") == true then why = "pet"
        elseif not calm or t - calm.t > A.STALE_S then why = "stale"
        elseif not m.lv or m.lv <= 0 then why = "level"
        else
            local plv = pv(UnitLevel, "player")
            local def = plv and (20 + (m.lv - plv)) or nil
            if not def or def <= 10 or def >= 40 then why = "near clamp"
            elseif not calm.b or not m.a then why = "no bracket"
            elseif ((st.onset and t - st.onset.t <= A.STALE_S and st.onset.a) or m.a) > calm.b then why = "moving away"
            elseif calm.b - ((st.onset and t - st.onset.t <= A.STALE_S and st.onset.a) or m.a) > 15 then why = "too wide"
            else
                local lo = (st.onset and t - st.onset.t <= A.STALE_S and st.onset.a) or m.a
                A.pulls[#A.pulls + 1] = { s = s, n = st.npc, ct = st.ct, lv = m.lv, plv = plv, lo = lo, hi = calm.b,
                                          t = math.floor(t * 10 + 0.5) / 10 }
                -- ag6 (the aggro rings): every measured pull logged with the radius the rule gives, so the rule can be checked against
                -- real pulls ({"type":"cap","probe":"pull"}: the pull happened between lo and hi yd; rule = the ring's radius)
                A.pullN = (A.pullN or 0) + 1
                pcall(function()
                    WC.send(WC.encode({ type = "cap", probe = "pull", v = A.V, ok = true, n = st.npc, lv = m.lv, plv = plv, lo = lo, hi = calm.b,
                        cls = m.c, rule = (type(WC.rings) == "table" and WC.rings.radius) and WC.rings.radius(m.lv, plv) or nil, k = A.pullN }))
                end)
                return true
            end
        end
        A.lastReject = why
        -- ag7: every check is logged, a refusal too (test52 had none: a fight the player started is "own action" by design - only
        -- a mob that noticed us by itself measures its aggro range), so a session shows what the detector saw
        A.pullRej = (A.pullRej or 0) + 1
        pcall(function()
            WC.send(WC.encode({ type = "cap", probe = "pull", v = A.V, ok = false, why = why, n = st.npc, lv = m.lv, lo = m.a,
                hi = calm and calm.b or nil, k = A.pullRej }))
        end)
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
        -- ag4: or HUD mode (Hub hub14, the user's "Yes, force them on"): ONE owner here, two reasons (WC.npWant.hud); restored only when
        -- neither wants them
        local want = A.routing() or (type(WC.npWant) == "table" and WC.npWant.hud == true)
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
                if t - A.np.offSince >= A.NP_OFF_S then A.npRestore("not wanted") end
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
    -- ---------------------------------------------------------------- THE APP'S PICKS (ag3; contract taps v2)
    -- The tap pad is full (54 of 54 rows), so the picks ride Interact's own dynamic "iact" row: 12 keys ("ag 0".."ag 7", "ag p1",
    -- "ag p2", "ag clr", "ag qm"), present only while the live layer is on (the route runs, the layer not off) and no NPC window
    -- is open (Interact's buildDoc: doc.aggro.cells). An op opens a 2 s digit window:
    --   ag p1 / ag p2 + 2 octal digits (high first) = the slot 0..23: pull pick #1 / #2;
    --   ag clr + ag p1 / ag p2 = clear that pick; ag clr twice = both; ag clr with another window open = cancel it;
    --   ag qm + 8 octal digits (low first) = the 24-bit quest mask (replaces the last one).
    -- A slot younger than 0.6 s (just reassigned) is refused ("slot gone"); a pick goes with its plate. Every op answers
    -- {"type":"aggroack", op, k, s, ok, why} / {op = "qmask", mask, ok}; the next record's mk shows it.
    A.pick, A.qset, A.WIN_S, A.YOUNG_S = {}, {}, 2, 0.6
    function A.tapsWanted()
        return A.routing() and not A.off
    end
    local function ack(t)
        t.type = "aggroack"
        pcall(function() WC.send(WC.encode(t)); WC.push() end)
        A.lastAck = t
        return t.ok == 1 or t.ok == true, t.why
    end
    function A.slotOk(s)
        local st = s and A.slot[s]
        return st and (now() - st.at) >= A.YOUNG_S and st or nil
    end
    function A.tap(a)
        local t = now()
        local w = A.win
        if w and t - w.at > A.WIN_S then w, A.win = nil, nil end            -- expired
        local d = tonumber(a)
        if d and d >= 0 and d <= 7 then
            if not w or w.op == "clr" then return ack({ op = "digit", ok = 0, why = "no window" }) end
            w.d[#w.d + 1] = d
            if (w.op == "p1" or w.op == "p2") and #w.d == 2 then
                A.win = nil
                local k = w.op == "p1" and 1 or 2
                local s = w.d[1] * 8 + w.d[2]
                local st = A.slotOk(s)
                if not st then return ack({ op = "pull", k = k, s = s, ok = 0, why = "slot gone" }) end
                A.pick[k] = { s = s, tok = st.tok }
                if A.pick[3 - k] and A.pick[3 - k].s == s then A.pick[3 - k] = nil end
                return ack({ op = "pull", k = k, s = s, ok = 1 })
            elseif w.op == "qm" and #w.d == 8 then
                A.win = nil
                local mask, applied = 0, 0
                for i = 1, 8 do mask = mask + w.d[i] * 8 ^ (i - 1) end
                A.qset = {}
                for s = 0, A.MAX - 1 do
                    if math.floor(mask / 2 ^ s) % 2 == 1 then
                        local st = A.slotOk(s)
                        if st then A.qset[s] = st.tok; applied = applied + 2 ^ s end
                    end
                end
                return ack({ op = "qmask", mask = applied, ok = 1 })
            end
            return true
        end
        if a == "p1" or a == "p2" then
            local k = a == "p1" and 1 or 2
            if w and w.op == "clr" then
                A.win = nil
                A.pick[k] = nil
                return ack({ op = "pull", k = k, s = -1, ok = 1 })
            end
            A.win = { op = a, d = {}, at = t }
            return true
        elseif a == "clr" then
            if w and w.op == "clr" then
                A.win = nil
                A.pick[1], A.pick[2] = nil, nil
                ack({ op = "pull", k = 1, s = -1, ok = 1 })
                return ack({ op = "pull", k = 2, s = -1, ok = 1 })
            elseif w then
                A.win = nil                                                -- a window open: cancelled
                return ack({ op = "cancel", ok = 1 })
            end
            A.win = { op = "clr", d = {}, at = t }
            return true
        elseif a == "qm" then
            A.win = { op = "qm", d = {}, at = t }
            return true
        end
        return ack({ op = tostring(a), ok = 0, why = "unknown" })
    end

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
        -- ag3: the app's pull pick (the signed-off F spec: TargetCrosshairs 44 px, its step number at its top right)
        local pk = f:CreateTexture(nil, "ARTWORK", nil, 2)
        pk:SetSize(u(44), u(44))
        pk:SetPoint("BOTTOM", f, "BOTTOM", 0, 0)
        pcall(function() pk:SetAtlas("TargetCrosshairs") end)
        pk:Hide()
        local pn = f:CreateFontString(nil, "OVERLAY")
        pcall(function() pn:SetFont("Fonts\\FRIZQT__.TTF", u(13), "OUTLINE") end)
        pn:SetPoint("BOTTOMLEFT", pk, "TOPRIGHT", -u(8), -u(10))
        pcall(function() pn:SetTextColor(1, 0.82, 0, 1) end)
        pn:Hide()
        o = { f = f, av = av, q = q, pk = pk, pn = pn }
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
                local tok = A.slot[m.s].tok
                local av, qq = A.avoidOf(m, plv), m.qi == 1 or A.qset[m.s] == tok
                local pk = (A.pick[1] and A.pick[1].s == m.s and 1) or (A.pick[2] and A.pick[2].s == m.s and 2) or nil
                if av or qq or pk then want[m.s] = { av = av and not pk, q = qq and not pk, pk = pk, tok = tok } end
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
                if w.pk then o.pk:Show(); o.pn:SetText(tostring(w.pk)); o.pn:Show() else o.pk:Hide(); o.pn:Hide() end
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
                       picks = (A.pick[1] and 1 or 0) + (A.pick[2] and 1 or 0), win = A.win and A.win.op or nil,
                       np = A.np.state, npv = npGet(), npo = A.npSaved() and A.npSaved().orig or nil, icons = A.iconN or 0,
                       route = A.routing(), pin = type(WC.routeWp) == "table" and WC.routeWp.active == true,
                       rings = type(WC.rings) == "table" and { v = WC.rings.V, on = WC.rings.wanted(), n = WC.rings.n,
                                                                   why = WC.rings.whyLine and WC.rings.whyLine() or nil } or nil,
                       pulls = A.pullN, pullRej = A.pullRej }
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
            elseif e == "UNIT_SPELLCAST_SENT" then if a1 == "player" then A.noteAction(now()) end
            elseif e == "PLAYER_ENTER_COMBAT" or e == "START_AUTOREPEAT_SPELL" then A.noteAction(now())
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
            -- the picks' iact cells come with the layer: Interact's document rebuilt when that changes (ag3)
            local tw = A.tapsWanted()
            if tw ~= A.lastTw then
                A.lastTw = tw
                if not tw then A.pick, A.qset, A.win = {}, {}, nil end
                if type(WC.interactPoke) == "function" then pcall(WC.interactPoke) end
            end
            if A.off then A.iconsClear() else A.iconsStep(A.last and A.last.m) end
            A.still()
            A.diag()
        end)
        if not ok then A.err = tostring(err):sub(1, 80) end
    end)
end

-- ================================================================== THE CALIBRATION RECORDING (cp17 "cal1"; read only, one shot)
-- The user: "How can we really run down this compass position thing to get it as accurate as we can?" cp16's online learning does
-- not settle in play, so the camera's turn law is fitted OFFLINE from a recording. ONE SHOT per add-on set (WowCompanionDB.calProbe):
-- it starts by itself at the routine's first step -- the focused quest's marker CENTRED on screen (the nav point unclamped, within 4%
-- of the half width of the centre) with the camera stick idle for 3 s -- and records 120 s at 30 Hz, a countdown "CAL n" on the
-- compass meanwhile. Every gamepad / camera CVar is READ once at the start (C_Console's command list where the client has it, else
-- a list of names). Lines {"type":"cap","probe":"cal1"}: "start" (the CVars, the screen, the columns), "s" (10 samples a line), "end".
-- Nothing is written to the game.
WC.compass.cal = { V = "cal1", DONE = "cal1", SECS = 120, HZ = 30, buf = {}, n = 0 }
do
    local C, Q = WC.compass, WC.compass.cal
    local num = C.num
    local function now() return (type(GetTime) == "function" and GetTime()) or 0 end
    local function send(t)
        t.type, t.probe, t.v = "cap", "cal1", Q.V
        pcall(function() WC.send(WC.encode(t)) end)
    end
    Q.NAMES = { "GamePadCameraYawSpeed", "GamePadCameraPitchSpeed", "gamepadInvertYaw", "gamepadInvertPitch", "GamePadTurnWithCamera",
                "GamepadCameraFollowOnStick", "GamepadCameraFollowPitchOffset", "GamePadCameraLookMaxYaw", "GamePadCameraLookMaxPitch",
                "GamePadFaceMovementMaxAngle", "GamePadFaceMovementMaxAngleCombat", "GamePadRunThreshold", "GamePadBackPedalThreshold",
                "GamePadCursorPushCamera", "GamePadStickEnabled", "cameraFov", "cameraYawSmoothSpeed", "cameraYawMoveSpeed",
                "cameraSmoothStyle", "cameraSmoothTrackingStyle", "cameraTerrainTilt", "test_cameraDynamicPitch", "CameraKeepCharacterCentered",
                "cameraPivot", "cameraDistanceMaxZoomFactor" }
    function Q.cvars()
        local names, seen = {}, {}
        local okA, all = pcall(function() return C_Console.GetAllCommands() end)
        if okA and type(all) == "table" then
            for _, c in ipairs(all) do
                local n = type(c) == "table" and c.command
                if type(n) == "string" and (n:lower():find("gamepad") or n:lower():find("camera")) and not seen[n] then
                    seen[n] = true; names[#names + 1] = n
                end
            end
        end
        for _, n in ipairs(Q.NAMES) do if not seen[n] then seen[n] = true; names[#names + 1] = n end end
        local out = {}
        for _, n in ipairs(names) do
            local okV, v = pcall(function() return C_CVar.GetCVar(n) end)          -- READ only
            if okV and v ~= nil and not (type(issecretvalue) == "function" and issecretvalue(v)) then out[n] = tostring(v) end
        end
        return out, okA and type(all) == "table"
    end
    local function r(v, k) v = num(v); return v and math.floor(v * k + 0.5) or "" end

    function Q.sample(t, dt)
        local K = C.cam
        -- the sticks, raw from the game's mapped state (both)
        local rx, ry, lx, ly
        local okS, st = pcall(function() return C_GamePad.GetDeviceMappedState() end)
        if okS and type(st) == "table" and type(st.sticks) == "table" then
            for i = 1, (num(st.stickCount) or #st.sticks) do
                local okN, name = pcall(function() return C_GamePad.StickIndexToConfigName(i - 1) end)
                local okX, x, y = pcall(function() return st.sticks[i].x, st.sticks[i].y end)
                if okN and okX and name == "Camera" then rx, ry = x, y elseif okN and okX and name == "Movement" then lx, ly = x, y end
            end
        end
        local f = C.facing()
        -- the nav point and the target's true bearing / distance
        local nx, ncl, nvp
        local okF, nav = pcall(function() return C_Navigation.GetFrame() end)
        if okF and nav then
            local okC, x = pcall(function() return nav:GetCenter() end)
            local okSc, ns = pcall(function() return nav:GetEffectiveScale() end)
            local okU, us = pcall(function() return UIParent:GetEffectiveScale() end)
            x = okC and num(x) or nil
            ns, us = okSc and num(ns) or 1, okU and num(us) or 1
            if x and us > 0 then nx = x * ns / us end
            local okK, cl = pcall(function() return C_Navigation.WasClampedToScreen() end)
            local okV, vp = pcall(function() return C_Navigation.HasValidScreenPosition() end)
            ncl = okK and type(cl) == "boolean" and (cl and 1 or 0) or ""
            nvp = okV and type(vp) == "boolean" and (vp and 1 or 0) or ""
        end
        local me = C.me()
        local tg = C.targets(me)
        local okP, onPin = pcall(function() return C_SuperTrack.IsSuperTrackingUserWaypoint() end)
        local w = (okP and onPin == true) and tg.pin or tg.quest
        local b, d
        if me and w then b, d = C.bearing(me, w) end
        local okR, fps = pcall(GetFramerate)
        local okM, speed = pcall(GetUnitSpeed, "player")
        return table.concat({ r(t - Q.t0, 1000), r(dt, 10000), r(rx, 1000), r(ry, 1000), r(lx, 1000), r(ly, 1000), r(f, 10000),
            r(nx, 10), tostring(ncl or ""), tostring(nvp or ""), r(b, 10000), r(d, 10), me and r(me.x, 10) or "", me and r(me.y, 10) or "",
            okR and r(fps, 1) or "", okM and r(speed, 100) or "", r(K and K.abs, 10000) }, ",")
    end

    function Q.start()
        Q.on, Q.t0, Q.n, Q.buf, Q.last = true, now(), 0, {}, now()
        Q.nextAt = Q.t0                                    -- (a fixed schedule: "time since the last" against 60 fps aliases to 20 Hz)
        local cv, enumerated = Q.cvars()
        local okW, uw = pcall(function() return UIParent:GetWidth() end)
        local okH, uh = pcall(function() return UIParent:GetHeight() end)
        send({ op = "start", cv = cv, cvAll = enumerated, ui = { w = okW and num(uw) or nil, h = okH and num(uh) or nil }, hz = Q.HZ,
               cols = "ms,dt(1e-4s),rx,ry,lx,ly(1e-3),facing(1e-4rad),navX(0.1ui),clamped,valid,bearing(1e-4rad),dist(0.1yd),"
                   .. "wx,wy(0.1yd),fps,speed(0.01),estYaw(1e-4rad)" })
    end
    function Q.finish(why)
        if not Q.on then return end
        if #Q.buf > 0 then send({ op = "s", s = Q.buf }); Q.buf = {} end
        send({ op = "end", why = why, n = Q.n })
        Q.on, Q.over = false, true
        if type(WowCompanionDB) ~= "table" then WowCompanionDB = {} end
        WowCompanionDB.calProbe = Q.DONE
        if Q.label then Q.label:Hide() end
    end
    -- the trigger: the marker centred, the camera stick idle 3 s
    function Q.armed(t)
        local K = C.cam
        local okF, nav = pcall(function() return C_Navigation.GetFrame() end)
        if not (okF and nav) then Q.calmSince = nil; return false end
        local okK, cl = pcall(function() return C_Navigation.WasClampedToScreen() end)
        local okC, x = pcall(function() return nav:GetCenter() end)
        local okSc, ns = pcall(function() return nav:GetEffectiveScale() end)
        local okU, us = pcall(function() return UIParent:GetEffectiveScale() end)
        local okW, uw = pcall(function() return UIParent:GetWidth() end)
        x, uw = okC and num(x) or nil, okW and num(uw) or nil
        ns, us = okSc and num(ns) or 1, okU and num(us) or 1
        local centred = okK and cl == false and x and uw and us > 0 and math.abs(x * ns / us - uw / 2) < uw / 2 * 0.04
        local stickIdle = not (K and K.rx and math.abs(K.rx) > 0.05)
        if centred and stickIdle then Q.calmSince = Q.calmSince or t else Q.calmSince = nil end
        return Q.calmSince ~= nil and t - Q.calmSince >= 3
    end
    function Q.tick(el)
        if Q.over then return end
        local t = now()
        if not Q.on then
            if type(WowCompanionDB) == "table" and WowCompanionDB.calProbe == Q.DONE then Q.over = true; return end
            if not (C.frame and C.frame:IsShown()) then return end
            if Q.armed(t) then Q.start() else return end
        end
        if t + 1e-6 < Q.nextAt then return end
        Q.nextAt = math.max(Q.nextAt + 1 / Q.HZ, t - 0.1)
        local dt = t - Q.last
        Q.last = t
        local okS, line = pcall(Q.sample, t, dt)
        if okS and line then
            Q.n = Q.n + 1
            Q.buf[#Q.buf + 1] = line
            if #Q.buf >= 10 then send({ op = "s", s = Q.buf }); Q.buf = {} end
        end
        -- the countdown on the compass (our own font string)
        local left = math.max(0, math.ceil(Q.SECS - (t - Q.t0)))
        if C.frame and not Q.label then
            Q.label = C.frame:CreateFontString(nil, "OVERLAY")
            Q.label:SetFont(C.FONT, C.u(14), "OUTLINE")
            Q.label:SetTextColor(1, 0.4, 0.4, 1)
            Q.label:SetPoint("LEFT", C.frame, "LEFT", C.u(14), 0)
        end
        if Q.label then Q.label:SetText("CAL " .. left); Q.label:Show() end
        if t - Q.t0 >= Q.SECS then Q.finish("done") end
    end
    local qf = CreateFrame("Frame")
    pcall(function() qf:RegisterEvent("PLAYER_LOGOUT") end)
    qf:SetScript("OnEvent", function() pcall(Q.finish, "logout") end)
    qf:SetScript("OnUpdate", function(_, el) if not Q.over then pcall(Q.tick, el) end end)
end

-- ================================================================== THE PERSONAL RESOURCE BAR (prd1; HUD mode)
-- The user (2026-10-20, approved from the render: "Yes, build it"): "With the hud display we need a personal resource bar that shows
-- when in combat, starting combat, or not at full hp. When out of combat and at full hp it should fade away. It should appear below
-- the charactor similar to how the enemy one floats over top them. It should display combat buffs and debuffs like hots or dots if
-- they appear in combat with their duration displayed as a radial fade." Answers: every power type; buffs of 5 min or less from
-- anyone; a height slider.
--   ONLY in HUD mode (Hub's hmSet: WC.npWant.hud), alive. OUR OWN frames on UIParent (mouse off, no template but the game's
--   plain CooldownFrameTemplate on the icons): nothing of the game's is written, shown, hidden or hooked.
--   WHERE: we cannot project the player, so screen space: the centre, Y_DEF spec px below it (a standing character's feet at the
--   default zoom); the player's own height (the app's slider, 10 px steps, per character in OUR saved data: WowCompanionDB.prdY).
--   THE BARS: health and the current power type, StatusBars FED the game's own values (secret on Forever: SetMinMaxValues /
--   SetValue take them; no number of them is ever read here), the game's colours (PowerBarColor by the power's token).
--   SHOWN in combat (or starting it: PLAYER_REGEN_DISABLED) or while not at full health; out of combat and full: HOLD_S, then a
--   FADE_S fade to nothing; back in IN_S. "Full" without reading the secret: P.hurt() - Controller's health model (WC.hpModel:
--   est / max < FULL) when the add-on set has it; with no signal at all, the bar fades HOLD_S after the fight (never stuck on).
--   THE AURAS (in combat only): HELPFUL with a duration of BUFF_DUR or less from anyone (HoTs, short self-buffs), at most BUFF_MAX,
--   from the bar's left edge; HARMFUL with a duration, at most DEBUFF_MAX, from its right edge in the debuff type's colour (the
--   game's UI-Debuff-Overlays border); each with the game's radial swipe (a Cooldown, reversed: the time gone darkens) and the
--   seconds left under it, gold under 5. Auras are plain on Forever (ShouldAurasBeSecret false).
-- prd2 (the user's pick on the v4 renders, "N: nameplate-thin"; v3: "Still looks glued on ... they don't look like fit in the fram"):
-- separate thin bars as the game's own nameplates are, each with a crisp 1 px border ON its edge, the PlayerFrame's own health /
-- power fills (their colour and gloss: no tint), the empty part dark and see-through, a faint soft shadow under the group (our own
-- compass band texture) and no panel; the whole at REST alpha; the auras smaller with the cooldown manager's rounded mask, the game's
-- debuff type borders, and the BuffFrame's timers ("8 s", gold; white under a minute). And (test45, "buffs did not show for my own
-- heals"): the aura row shows whenever the BAR does, not only in combat; the probe counts what the scan saw and why any was left
-- out (prd.ab); an aura whose duration is secret still shows (its swipe from the secrets, no seconds).
-- prd3 (the user: "When getting hit make sure to have an animated chunk of hp chunk away ... When casting a spell visualize the cost as
-- well. Make sure you are also visualizing incoming heals"; "Yes, build them"), every piece secret-safe (no number of health or power
-- read or compared: the game's own values handed to widgets) and quietly absent where the client lacks a call (P.ex says which):
--   THE HIT CHUNK: a LAG bar under the health fill, fed the same values LAG_S late (a queue of the polled values); its move is the
--   StatusBar's own interpolation when this client has it (a shrink), else a step. Under the fill, it only shows what was LOST.
--   THE COST: while casting, the spell's cost (C_Spell.GetSpellPowerCost, plain) over the plain max power, as a bright segment
--   anchored to the power fill's own right edge (it follows the secret value without our reading it), drawn leftward.
--   INCOMING HEALS / ABSORBS: StatusBars the width of the bar, anchored at the health fill's right edge (heals) and then at the heal
--   fill's (the shield), fed the game's incoming heals and total absorbs; a clipping frame the size of the bar keeps them inside it.
-- prd4 (test48, the user: "The health bar needs to be lower down a bit and changed to a polished version of the PRD bar"): LOWER
-- (300 spec px under the centre, a standing character's feet at the default zoom; a saved height moves with it once), and the PRD's
-- own look made to sit in the world: each bar in its own frame of the PRD's bar BG (the bronze -c60 edge; nine-sliced where the client
-- slices textures, so the edge is one width on both bars; placed by the PRD template's own margins round the bar), with N's polish:
-- the soft shadow, the rest alpha, the fills' gloss and a soft spark at each fill's edge, the bars 4 px apart, small auras tucked
-- under, no panel. Everything of prd3 stays.
-- prd5 (the user on prd6: "It overlaps the border instead of looking like a 3d polished fill part of the asset"): the PRD bar BG
-- (-c60) taken apart along its OWN art (measured: a 132 x 19 texel piece, the bronze rim at column 1 / 126 and row 1 / 12, a black
-- line inside it, the OPENING columns 3..124, rows 3..10) and rebuilt as a RECESSED channel: each bar IS the opening; under it the
-- art's own dark middle (the track); OVER it the lip (the art's top rows, bottom rows and the columns beside the opening) and an
-- inner shadow at the opening's top. One texel scale for both bars (the health opening's 8 texels = HH), so the rim is one width;
-- nothing of the fill, the chunk, the cost, the heals, the shield or the spark can reach the rim (each inside the bar, the lip
-- over all). The texture's own coordinates from C_Texture.GetAtlasInfo; without it, the whole piece behind the bar as prd4 had it.
-- prd6 (the user on prd7: "it doesn't fill all the way in"; the left end dark and square, the right round): the PlayerFrame fills are
-- made to butt against the portrait - a square, darker left end, a bevelled right one - so the two ends never mirrored. The fill
-- is now the PRD's OWN (UI-HUD-CoolDownManager-Bar-c60: rounded at both ends, its gloss in grey, sized for this frame), tinted with
-- the bar's colour, edge to edge in the opening; the spark is gone (it showed at an empty bar's left end, and the value is secret).
-- prd7 (test49, the user: "The hud is gray for hp and invisible for mana"): the fill made robust - SetAtlas does NOT error on an atlas
-- this client lacks (it returns false and leaves the texture blank: mana invisible), so an atlas is used only when
-- C_Texture.GetAtlasInfo knows it AND SetAtlas says yes, else the plain UI-StatusBar; the asset is set BEFORE the texture is handed
-- to the StatusBar (prd1's order, which tinted in game; prd6 set it after); the colour goes both ways (SetStatusBarColor and the
-- texture's own vertex colour) and again at every poll; the power's colour no longer waits for a CHANGE of power type (a mana user
-- starting at type 0). The probe says which fill each bar got (prd.ex.hpFill / pwFill).
-- prd8 (test50, the user: "it should Fade when Mana is full as well"; "the HUD still feels a bit small, maybe it just needs to be a bit
-- wider? can we add some scaling settings so that I can tweak it and tell you what I like"):
--   THE POWER TOO: out of combat the bar stays until health AND power are at rest - mana full (Controller's WC.vitals: its plain or
--   modelled mana), energy / focus full (no plain reader: ENERGY_S after the last fight or spend, the time a full refill takes),
--   rage / runic power empty (RAGE_S after the fight: they decay to nothing); either one off its rest brings the bar back at once.
--   While the power is off its rest the bars' layer is plainly shown (the health curve would hide it when only the power is low).
--   WIDTH AND SCALE: two more of the app's settings beside the height, stepped on Hub's same row: Hub's column 39 SELECTS which one
--   its step columns move (height, width, scale; back to height SEL_S after the last tap, so an app that only knows the height
--   still works); per character in our saved data (prdW / prdS); the bar redrawn at once (the app's live preview). Wider by default.
-- prd8 also (the user: "a more animated Fade effect, Fading up and in when entering, and then fading down and out when leaving"): one
-- visibility value P.vis moves at IN_S / OUT_S toward shown or hidden, and both the alpha and a slide offset follow it through the
-- same eased curve (1 - (1 - v)^2: an ease-out as it comes in, an ease-in as it goes), TRAVEL spec px from below; a hide that turns
-- into a show turns round where it is. The slide is an offset on the player's height, never written to it.
WC.prd = { V = "prd8", TRAVEL = 14, OUT_S = 0.45, vis = 0, dy = 0, ENERGY_S = 10, RAGE_S = 30, SEL_S = 4, W_DEF = 260, W_MIN = 180, W_MAX = 400, W_STEP = 20,
           S_DEF = 100, S_MIN = 70, S_MAX = 160, S_STEP = 10, sel = "y", FILL_ATLAS = { "UI-HUD-CoolDownManager-Bar-c60", "UI-HUD-CoolDownManager-Bar" }, HEALTH_RGB = { 0.12, 0.86, 0.12 }, LAG_S = 0.3, HEAL_RGB = { 0.47, 1, 0.47, 0.55 }, LAG_RGB = { 1, 0.86, 0.76 }, Y_DEF = -300, Y_MIN = -470, Y_MAX = -170, STEP = 10, W = 260, HH = 15, PH = 9, BAR_GAP = 8, ICON = 26, IGAP = 4,
           ROW_GAP = 20, BG_W = 132, BG_H = 19, Y_SHIFT = -50, BG = { "UI-HUD-CoolDownManager-Bar-BG-c60", "UI-HUD-CoolDownManager-Bar-BG" }, REST = 0.92, BUFF_MAX = 6, DEBUFF_MAX = 4, BUFF_DUR = 300, HOLD_S = 2, FADE_S = 1.5, IN_S = 0.25, FULL = 0.99, POLL_S = 0.1, AURA_S = 0.5,
           alpha = 0, shown = false, why = "off" }
-- (the names Hub's hub15 row reads: y, on, min, max, step, def, nudge; hub16 adds w / s and their ranges, sel and select)
WC.prd.min, WC.prd.max, WC.prd.step, WC.prd.def = WC.prd.Y_MIN, WC.prd.Y_MAX, WC.prd.STEP, WC.prd.Y_DEF
do
    local P = WC.prd
    local function now() return (type(GetTime) == "function" and GetTime()) or 0 end
    local function num(v) if type(v) == "number" and v == v and not (type(issecretvalue) == "function" and issecretvalue(v)) then return v end end
    local function u(px)
        local C = WC.compass
        if C and type(C.u) == "function" then return C.u(px) end
        local ok, h = pcall(function() return UIParent:GetHeight() end)
        return px * ((ok and num(h) and h > 0) and h / 1080 or 768 / 1080)
    end
    -- the game's power colours, by token (the client's PowerBarColor when it is there)
    P.POWER = { MANA = { 0, 0.45, 1 }, RAGE = { 1, 0, 0 }, FOCUS = { 1, 0.5, 0.25 }, ENERGY = { 1, 1, 0 }, RUNIC_POWER = { 0, 0.82, 1 },
                COMBO_POINTS = { 1, 0.96, 0.41 }, HAPPINESS = { 0, 1, 1 } }
    P.DEBUFF = { Magic = { 0.2, 0.6, 1 }, Curse = { 0.6, 0, 1 }, Disease = { 0.6, 0.4, 0 }, Poison = { 0, 0.6, 0 }, none = { 0.8, 0, 0 } }

    -- ---------------------------------------------------------------- the place (the app's slider)
    local function who()
        local ok, n, r = pcall(function() return UnitName("player"), GetRealmName() end)
        if ok and type(n) == "string" then return n .. "-" .. tostring(r or "") end
        return "?"
    end
    function P.loadY()
        -- prd4: a height the player set on the old default moves down with it, once (prdYv 2)
        if type(WowCompanionDB) == "table" and type(WowCompanionDB.prdY) == "table" and WowCompanionDB.prdYv ~= 2 then
            for k, v in pairs(WowCompanionDB.prdY) do
                if num(v) then WowCompanionDB.prdY[k] = math.max(P.Y_MIN, math.min(P.Y_MAX, v + P.Y_SHIFT)) end
            end
            WowCompanionDB.prdYv = 2
        end
        local t = type(WowCompanionDB) == "table" and WowCompanionDB.prdY
        local y = type(t) == "table" and num(t[who()]) or nil
        P.y = y and math.max(P.Y_MIN, math.min(P.Y_MAX, y)) or P.Y_DEF
        return P.y
    end
    -- the app's slider: one step down (-1) / up (+1), or back to the default (0)
    -- prd8: the width and the scale, per character
    local function kept(key, def, lo, hi)
        local t = type(WowCompanionDB) == "table" and WowCompanionDB[key]
        local v = type(t) == "table" and num(t[who()]) or nil
        return v and math.max(lo, math.min(hi, v)) or def
    end
    local function keep(key, v, def)
        if type(WowCompanionDB) ~= "table" then WowCompanionDB = {} end
        if type(WowCompanionDB[key]) ~= "table" then WowCompanionDB[key] = {} end
        WowCompanionDB[key][who()] = v ~= def and v or nil
    end
    function P.loadSize()
        P.wd = kept("prdW", P.W_DEF, P.W_MIN, P.W_MAX)
        P.sc = kept("prdS", P.S_DEF, P.S_MIN, P.S_MAX)
        P.W = P.wd
        return P.wd, P.sc
    end
    -- Hub's column 39: the next setting the step columns move (height -> width -> scale -> height)
    function P.select()
        P.sel = ({ y = "w", w = "s", s = "y" })[P.sel or "y"] or "y"
        P.selAt = now()
        return P.sel
    end
    function P.nudge(d)
        if P.sel ~= "y" and P.selAt and now() - P.selAt <= P.SEL_S then
            P.selAt = now()
            if not P.wd then P.loadSize() end
            if P.sel == "w" then
                P.wd = d == 0 and P.W_DEF or math.max(P.W_MIN, math.min(P.W_MAX, P.wd + (d > 0 and P.W_STEP or -P.W_STEP)))
                keep("prdW", P.wd, P.W_DEF)
            else
                P.sc = d == 0 and P.S_DEF or math.max(P.S_MIN, math.min(P.S_MAX, P.sc + (d > 0 and P.S_STEP or -P.S_STEP)))
                keep("prdS", P.sc, P.S_DEF)
            end
            P.resize()
            return P.sel == "w" and P.wd or P.sc
        end
        P.sel = "y"
        P.y = P.y or P.loadY()
        if d == 0 then P.y = P.Y_DEF else P.y = math.max(P.Y_MIN, math.min(P.Y_MAX, P.y + (d > 0 and P.STEP or -P.STEP))) end
        if type(WowCompanionDB) ~= "table" then WowCompanionDB = {} end
        if type(WowCompanionDB.prdY) ~= "table" then WowCompanionDB.prdY = {} end
        WowCompanionDB.prdY[who()] = P.y ~= P.Y_DEF and P.y or nil
        WowCompanionDB.prdYv = 2                                   -- (a height on today's scale: never shifted)
        P.place()
        return P.y
    end

    -- ---------------------------------------------------------------- the frames
    -- the PlayerFrame's own fills by the power's token (their colour and gloss in the art)
    -- the bar's fill: the PRD's own (rounded at both ends), else the plain status bar; tinted by the caller either way
    -- an atlas this client really has: known to GetAtlasInfo (when the call exists) and taken by SetAtlas (false = refused)
    local function atlasOn(tex, a)
        if C_Texture and type(C_Texture.GetAtlasInfo) == "function" then
            local okI, info = pcall(C_Texture.GetAtlasInfo, a)
            if not (okI and type(info) == "table") then return false end
        end
        local ok, r = pcall(function() return tex:SetAtlas(a) end)
        return ok and r ~= false
    end
    P.atlasOn = atlasOn
    local function setFill(b)
        for _, a in ipairs(P.FILL_ATLAS) do
            if atlasOn(b.fill, a) then b.fillAtlas = a; return true end
        end
        pcall(function() b.fill:SetTexture("Interface\\TargetingFrame\\UI-StatusBar") end)
        b.fillAtlas = nil
        return false
    end
    P.setFill = setFill
    -- the colour, both ways (the bar's, and the texture's own), remembered for the polls
    function P.paint(b, r, g, bb)
        b.rgb = { r, g, bb }
        pcall(function() b:SetStatusBarColor(r, g, bb) end)
        pcall(function() b.fill:SetVertexColor(r, g, bb) end)
    end
    local function bar(parent, h)
        local b = CreateFrame("StatusBar", nil, parent)
        b:SetSize(u(P.W), u(h))
        local bg = b:CreateTexture(nil, "BACKGROUND")
        bg:SetAllPoints(b)
        bg:SetColorTexture(0, 0, 0, 0.59)                       -- the empty part: dark, the world through it a little
        local fill = b:CreateTexture(nil, "ARTWORK")
        b.fill, b.bg = fill, bg
        setFill(b)                                              -- (prd7: the asset BEFORE the bar takes the texture)
        b:SetStatusBarTexture(fill)
        -- (the bar's own texture from here on: every anchor on its edge, every colour)
        local okT, real = pcall(function() return b:GetStatusBarTexture() end)
        if okT and type(real) == "table" then b.fill = real end
        b:SetMinMaxValues(0, 1)
        b:SetValue(1)
        bg:SetColorTexture(0, 0, 0, 0)                          -- prd4: the PRD frame is the ground (P.frameFor)
        return b
    end
    -- prd4: the PRD's own bar BG round a bar, on the layer UNDER every bar (so the lag chunk shows above it): the template's margins
    -- (TOPLEFT -2,3 / BOTTOMRIGHT 6,-7 round its 10 px bar) at the health bar's scale for both, so the edge is one width
    -- prd5: the PRD frame as a channel round the bar b (b's rect is the opening); returns its pieces
    function P.frameFor(b)
        local k = u(P.HH / 8)                                   -- UI units per texel (one scale for both bars)
        local info, atlas
        for _, a in ipairs(P.BG) do
            local ok, i = pcall(function() return C_Texture.GetAtlasInfo(a) end)
            if ok and type(i) == "table" and i.file and num(i.leftTexCoord) then info, atlas = i, a; break end
        end
        local pieces = { atlas = atlas }
        if not info then
            -- no atlas coordinates: the whole piece behind the bar (prd4's way), by its measured margins
            local t = P.g:CreateTexture(nil, "BACKGROUND", nil, -4)
            local ok = false
            for _, a in ipairs(P.BG) do if pcall(function() t:SetAtlas(a) end) then ok = true; pieces.atlas = a; break end end
            if not ok then t:SetColorTexture(0, 0, 0, 0.59) end
            t:SetPoint("TOPLEFT", b, "TOPLEFT", -3 * k, 3 * k)
            t:SetPoint("BOTTOMRIGHT", b, "BOTTOMRIGHT", 7 * k, -8 * k)
            pieces.whole = t
            return pieces
        end
        local L, R, Tt, B = info.leftTexCoord, info.rightTexCoord, info.topTexCoord, info.bottomTexCoord
        local function sub(parent, layer, level, c0, r0, c1, r1)
            local t = parent:CreateTexture(nil, layer, nil, level)
            t:SetTexture(info.file)
            t:SetTexCoord(L + (R - L) * c0 / P.BG_W, L + (R - L) * c1 / P.BG_W, Tt + (B - Tt) * r0 / P.BG_H, Tt + (B - Tt) * r1 / P.BG_H)
            return t
        end
        if not P.lip then
            P.lip = CreateFrame("Frame", nil, P.g)
            P.lip:SetAllPoints(P.g)
            P.lip:EnableMouse(false)
        end
        -- the track: the art's dark middle (rows 3..11) under the bar, the full width
        local tr = sub(P.g, "BACKGROUND", -4, 0, 3, P.BG_W, 11)
        tr:SetPoint("TOPLEFT", b, "TOPLEFT", -3 * k, 0)
        tr:SetPoint("BOTTOMRIGHT", b, "BOTTOMRIGHT", 7 * k, 0)
        -- the lip over the bar: the top rows and the bottom rows IN THREE - the art's end caps (columns 0..11, 118..132) and a
        -- middle stretched from a MARK-FREE span (31..50: the art's notches at 57 / 52 / 116 would sit at the same spot on both bars:
        -- prd6, the user: "The markings on the borders being in the same spots makes them seem a bit lazy") - then the sides
        local function rim(r0, r1, h, edge, sideL, sideR)
            local l = sub(P.lip, "ARTWORK", 1, 0, r0, 11, r1)
            l:SetPoint(edge == "top" and "BOTTOMLEFT" or "TOPLEFT", b, sideL, -3 * k, 0)
            l:SetSize(11 * k, h)
            local r = sub(P.lip, "ARTWORK", 1, 118, r0, P.BG_W, r1)
            r:SetPoint(edge == "top" and "BOTTOMRIGHT" or "TOPRIGHT", b, sideR, 7 * k, 0)
            r:SetSize(14 * k, h)
            local m = sub(P.lip, "ARTWORK", 1, 31, r0, 50, r1)
            m:SetPoint(edge == "top" and "BOTTOMLEFT" or "TOPLEFT", l, edge == "top" and "BOTTOMRIGHT" or "TOPRIGHT", 0, 0)
            m:SetPoint(edge == "top" and "BOTTOMRIGHT" or "TOPRIGHT", r, edge == "top" and "BOTTOMLEFT" or "TOPLEFT", 0, 0)
            m:SetHeight(h)
            return { l = l, m = m, r = r, h = h }
        end
        local top = rim(0, 3, 3 * k, "top", "TOPLEFT", "TOPRIGHT")
        local bot = rim(11, P.BG_H, 8 * k, "bottom", "BOTTOMLEFT", "BOTTOMRIGHT")
        local lf = sub(P.lip, "ARTWORK", 1, 0, 3, 3, 11)
        lf:SetPoint("TOPRIGHT", b, "TOPLEFT", 0, 0)
        lf:SetPoint("BOTTOMRIGHT", b, "BOTTOMLEFT", 0, 0)
        lf:SetWidth(3 * k)
        local rt = sub(P.lip, "ARTWORK", 1, 125, 3, P.BG_W, 11)
        rt:SetPoint("TOPLEFT", b, "TOPRIGHT", 0, 0)
        rt:SetPoint("BOTTOMLEFT", b, "BOTTOMRIGHT", 0, 0)
        rt:SetWidth(7 * k)
        -- the inner shadow: the lip's shade over the opening's top
        local sh = P.lip:CreateTexture(nil, "ARTWORK", nil, 0)
        sh:SetColorTexture(0, 0, 0, 1)
        sh:SetPoint("TOPLEFT", b, "TOPLEFT", 0, 0)
        sh:SetPoint("TOPRIGHT", b, "TOPRIGHT", 0, 0)
        sh:SetHeight(b:GetHeight() * 0.45)
        if not pcall(function() sh:SetGradient("VERTICAL", CreateColor(0, 0, 0, 0), CreateColor(0, 0, 0, 0.47)) end) then sh:SetAlpha(0.2) end
        pieces.track, pieces.top, pieces.bot, pieces.left, pieces.right, pieces.shade = tr, top, bot, lf, rt, sh
        return pieces
    end
    local function icon(parent, i)
        local o = { f = CreateFrame("Frame", nil, parent) }
        o.f:SetSize(u(P.ICON), u(P.ICON))
        o.f:EnableMouse(false)
        o.tx = o.f:CreateTexture(nil, "ARTWORK")
        o.tx:SetAllPoints(o.f)
        o.tx:SetTexCoord(0.07, 0.93, 0.07, 0.93)
        -- the cooldown manager's rounded icon (its mask and overlay), when the client has them
        pcall(function()
            local m = o.f:CreateMaskTexture()
            m:SetAtlas("UI-HUD-CoolDownManager-Mask")
            m:SetAllPoints(o.f)
            o.tx:AddMaskTexture(m)
            o.mask = m
        end)
        pcall(function()
            local ov = o.f:CreateTexture(nil, "OVERLAY", nil, 1)
            ov:SetAtlas("UI-HUD-CoolDownManager-IconOverlay-c60")
            ov:SetPoint("CENTER", o.f, "CENTER", 0, 0)
            ov:SetSize(u(P.ICON * 86 / 64), u(P.ICON * 86 / 64))
            o.ov = ov
        end)
        o.cd = CreateFrame("Cooldown", nil, o.f, "CooldownFrameTemplate")
        o.cd:SetAllPoints(o.f)
        pcall(function() o.cd:SetReverse(true) end)                -- the time GONE darkens (the aura's look)
        pcall(function() o.cd:SetDrawEdge(true) end)
        pcall(function() o.cd:SetHideCountdownNumbers(true) end)    -- our own seconds under it
        o.bd = o.f:CreateTexture(nil, "OVERLAY", nil, 2)
        o.bd:SetTexture("Interface\\Buttons\\UI-Debuff-Overlays")
        o.bd:SetTexCoord(0.296875, 0.5703125, 0, 0.515625)
        o.bd:SetPoint("TOPLEFT", o.f, "TOPLEFT", -u(4), u(4))
        o.bd:SetPoint("BOTTOMRIGHT", o.f, "BOTTOMRIGHT", u(4), -u(4))
        o.t = o.f:CreateFontString(nil, "OVERLAY")
        o.t:SetFont("Fonts\\FRIZQT__.TTF", u(13), "OUTLINE")
        o.t:SetPoint("TOP", o.f, "BOTTOM", 0, -u(2))
        o.n = o.f:CreateFontString(nil, "OVERLAY")
        o.n:SetFont("Fonts\\FRIZQT__.TTF", u(13), "OUTLINE")
        o.n:SetPoint("BOTTOMRIGHT", o.f, "BOTTOMRIGHT", -u(1), u(1))
        o.f:Hide()
        return o
    end
    function P.build()
        if P.f then return P.f end
        P.loadSize()
        local f = CreateFrame("Frame", nil, UIParent)
        f:SetFrameStrata("MEDIUM")
        f:EnableMouse(false)
        f:SetSize(u(P.W), u(P.HH + P.BAR_GAP + P.PH))
        pcall(function() f:SetScale(P.sc / 100) end)
        f:SetAlpha(0)
        f:Hide()
        P.f = f
        -- the LAYER the curve may drive (out of combat: the engine's own alpha from the health percent); f keeps our timing
        local g = CreateFrame("Frame", nil, f)
        g:SetAllPoints(f)
        g:EnableMouse(false)
        P.g = g
        f = g
        -- a faint soft shadow under the bars (our own compass band: a black band fading to every edge), no panel
        local sh = f:CreateTexture(nil, "BACKGROUND", nil, -8)
        sh:SetTexture("Interface\\AddOns\\WowCompanion\\Media\\compass_band")
        sh:SetPoint("TOPLEFT", f, "TOPLEFT", -u(16), u(10))
        sh:SetPoint("BOTTOMRIGHT", f, "TOPRIGHT", u(16), -u(P.HH + P.BAR_GAP + P.PH + 16))
        sh:SetAlpha(0.9)
        P.shadow = sh
        P.hp = bar(f, P.HH)
        P.hp:SetPoint("TOP", f, "TOP", 0, 0)
        P.paint(P.hp, P.HEALTH_RGB[1], P.HEALTH_RGB[2], P.HEALTH_RGB[3])
        P.ptype = nil                                           -- (a new power bar: its colour at the next poll)
        P.ex.hpFill = P.hp.fillAtlas or "plain"
        P.pw = bar(f, P.PH)
        P.pw:SetPoint("TOP", P.hp, "BOTTOM", 0, -u(P.BAR_GAP))
        P.extras(f)
        P.hpFrame, P.pwFrame = P.frameFor(P.hp), P.frameFor(P.pw)
        -- the lip over every bar and overlay (the extras' clip frames are the bars' children)
        if P.lip then pcall(function() P.lip:SetFrameLevel(P.hp:GetFrameLevel() + 20) end) end
        P.buffs, P.debuffs = {}, {}
        for i = 1, P.BUFF_MAX do P.buffs[i] = icon(f, i) end
        for i = 1, P.DEBUFF_MAX do P.debuffs[i] = icon(f, i) end
        P.place()
        return f
    end
    -- prd8: the width and the scale applied to what is built (the app's live preview)
    function P.resize()
        if not P.wd then P.loadSize() end
        P.W = P.wd
        if not P.f then return end
        P.f:SetSize(u(P.W), u(P.HH + P.BAR_GAP + P.PH))
        pcall(function() P.f:SetScale(P.sc / 100) end)
        for _, b in ipairs({ P.hp, P.pw, P.lag }) do if b then b:SetWidth(u(P.W)) end end
        for _, b in ipairs({ P.heal, P.shield }) do if b then b:SetWidth(u(P.W)) end end
        if P.lag then P.lag:SetAllPoints(P.hp) end
        P.cache = nil
    end
    function P.place()
        if not P.f then return end
        P.f:ClearAllPoints()
        P.f:SetPoint("TOP", UIParent, "CENTER", 0, u((P.y or P.loadY()) + (P.dy or 0)))
    end

    -- ---------------------------------------------------------------- the values (fed, never read)
    -- prd3: the lag bar (under the health fill), the heals and the shield (past it, clipped to the bar), the cost (on the power fill)
    P.ex = {}
    function P.extras(parent)
        local function plainBar(par, rgba, tex)
            local b = CreateFrame("StatusBar", nil, par)
            local t = b:CreateTexture(nil, "ARTWORK")
            t:SetTexture(tex or "Interface\\TargetingFrame\\UI-StatusBar")
            b.fill = t
            b:SetStatusBarTexture(t)
            pcall(function() b:SetStatusBarColor(rgba[1], rgba[2], rgba[3], rgba[4] or 1) end)
            b:SetMinMaxValues(0, 1)
            b:SetValue(0)
            return b
        end
        -- the lag bar: the health bar's own ground moves under it, so the lost part shows through the empty part
        local lag = plainBar(parent, P.LAG_RGB)
        lag:SetAllPoints(P.hp)
        pcall(function()
            lag:SetFrameLevel(P.hp:GetFrameLevel())
            P.hp:SetFrameLevel(lag:GetFrameLevel() + 1)
        end)
        -- (the ground under both is the PRD frame on the layer below: prd4)
        P.lag, P.lagQ = lag, {}
        P.ex.interp = type(Enum) == "table" and type(Enum.StatusBarInterpolation) == "table"
            and Enum.StatusBarInterpolation.ExponentialEaseOut ~= nil or false
        -- heals and the shield, clipped to the bar
        local clip = CreateFrame("Frame", nil, P.hp)
        clip:SetAllPoints(P.hp)
        pcall(function() clip:SetClipsChildren(true) end)
        local heal = plainBar(clip, P.HEAL_RGB)
        heal:SetPoint("TOPLEFT", P.hp.fill, "TOPRIGHT", 0, 0)
        heal:SetPoint("BOTTOMLEFT", P.hp.fill, "BOTTOMRIGHT", 0, 0)
        heal:SetWidth(u(P.W))
        local shield = plainBar(clip, { 1, 1, 1, 0.7 }, "Interface\\RaidFrame\\Shield-Fill")
        shield:SetPoint("TOPLEFT", heal.fill, "TOPRIGHT", 0, 0)
        shield:SetPoint("BOTTOMLEFT", heal.fill, "BOTTOMRIGHT", 0, 0)
        shield:SetWidth(u(P.W))
        P.clip, P.heal, P.shield = clip, heal, shield
        -- the cost, on the power fill's right edge
        local cost = P.pw:CreateTexture(nil, "OVERLAY")
        cost:SetTexture("Interface\\TargetingFrame\\UI-StatusBar")
        pcall(function() cost:SetBlendMode("ADD") end)
        cost:SetVertexColor(0.6, 0.85, 1, 0.9)
        cost:SetPoint("TOPRIGHT", P.pw.fill, "TOPRIGHT", 0, 0)
        cost:SetPoint("BOTTOMRIGHT", P.pw.fill, "BOTTOMRIGHT", 0, 0)
        cost:Hide()
        P.cost = cost
    end
    -- the incoming heals, by the client's own path (the calculator, else UnitGetIncomingHeals); the value as the game gives it
    function P.incoming()
        if type(CreateUnitHealPredictionCalculator) == "function" and type(UnitGetDetailedHealPrediction) == "function" then
            local ok, v = pcall(function()
                P.calc = P.calc or CreateUnitHealPredictionCalculator()
                UnitGetDetailedHealPrediction("player", nil, P.calc)
                return P.calc:GetIncomingHeals()
            end)
            if ok and v ~= nil then return v, "calc" end
        end
        local ok, v = pcall(UnitGetIncomingHeals, "player")
        if ok and v ~= nil then return v, "uih" end
        return nil, "none"
    end
    -- the extras fed: the lag bar LAG_S late, the heals, the shield
    function P.feedExtras(hpMax)
        local t = now()
        local okH, hpv = pcall(UnitHealth, "player")
        if okH and hpv ~= nil then
            local q = P.lagQ
            q[#q + 1] = { t = t, v = hpv }
            local pick
            while #q > 0 and t - q[1].t >= P.LAG_S do pick = table.remove(q, 1) end
            if pick then
                pcall(function() P.lag:SetMinMaxValues(0, hpMax) end)
                if not (P.ex.interp and pcall(function() P.lag:SetValue(pick.v, Enum.StatusBarInterpolation.ExponentialEaseOut) end)) then
                    pcall(function() P.lag:SetValue(pick.v) end)
                end
            end
        end
        local inc, src = P.incoming()
        P.ex.heal = src
        pcall(function() P.heal:SetMinMaxValues(0, hpMax) end)
        if inc ~= nil then pcall(function() P.heal:SetValue(inc) end) else pcall(function() P.heal:SetValue(0) end) end
        local okA, ab = pcall(UnitGetTotalAbsorbs, "player")
        P.ex.abs = okA and ab ~= nil or false
        pcall(function() P.shield:SetMinMaxValues(0, hpMax) end)
        pcall(function() P.shield:SetValue(okA and ab ~= nil and ab or 0) end)
    end
    -- the cast's cost on the power bar: the spell's own cost (plain) of the current power over its plain max
    function P.castCost(spellID)
        if not (P.cost and P.pw) then return end
        local okM, mx = pcall(UnitPowerMax, "player", P.ptype or 0)
        mx = okM and num(mx) or nil
        local cost
        local okC, list = pcall(function() return C_Spell.GetSpellPowerCost(spellID) end)
        if okC and type(list) == "table" then
            for _, c in ipairs(list) do
                if type(c) == "table" and num(c.type) == (P.ptype or 0) and num(c.cost) then cost = c.cost; break end
            end
        end
        if cost and mx and mx > 0 and cost > 0 then
            P.cost:SetWidth(math.min(1, cost / mx) * u(P.W))
            P.cost:Show()
            P.ex.cost = cost
        else
            P.cost:Hide()
            P.ex.cost = nil
        end
    end
    function P.castEnd() if P.cost then P.cost:Hide() end; P.ex.cost = nil end
    function P.values()
        local okX, hpMax = pcall(UnitHealthMax, "player")
        pcall(function() P.hp:SetMinMaxValues(0, hpMax) end)
        pcall(function() P.hp:SetValue(UnitHealth("player")) end)
        if okX and P.lag then pcall(P.feedExtras, hpMax) end
        local okT, pt, tok = pcall(function() return UnitPowerType("player") end)
        pt = okT and num(pt) or 0
        if pt ~= P.ptype then
            P.ptype = pt
            -- (prd6: our table first - its mana the PRD's own lighter blue on this fill - then the game's, then mana's)
            local c = (type(PowerBarColor) == "table" and type(tok) == "string" and PowerBarColor[tok]) or nil
            local r, g, b
            if P.POWER[tok] then local cc = P.POWER[tok]; r, g, b = cc[1], cc[2], cc[3]
            elseif type(c) == "table" and num(c.r) then r, g, b = c.r, c.g, c.b
            else local cc = P.POWER.MANA; r, g, b = cc[1], cc[2], cc[3] end
            -- the game's own fill for this power (its colour in the art), else the plain bar in the power's colour
            P.paint(P.pw, r, g, b)
            P.ptok = tok
            P.ex.pwFill = P.pw.fillAtlas or "plain"
        end
        -- (again each poll: nothing the game does to the textures leaves them grey)
        if P.hp.rgb then pcall(function() P.hp.fill:SetVertexColor(P.hp.rgb[1], P.hp.rgb[2], P.hp.rgb[3]) end) end
        if P.pw.rgb then pcall(function() P.pw.fill:SetVertexColor(P.pw.rgb[1], P.pw.rgb[2], P.pw.rgb[3]) end) end
        pcall(function() P.pw:SetMinMaxValues(0, UnitPowerMax("player", pt)) end)
        pcall(function() P.pw:SetValue(UnitPower("player", pt)) end)
    end

    -- ---------------------------------------------------------------- the auras
    local function auraAt(i, filter)
        if C_UnitAuras and C_UnitAuras.GetAuraDataByIndex then
            local ok, a = pcall(C_UnitAuras.GetAuraDataByIndex, "player", i, filter)
            if ok then return a end
            return nil
        end
        local ok, name, ic, count, dtype, dur, exp = pcall(UnitAura, "player", i, filter)
        if ok and name then return { name = name, icon = ic, applications = count, dispelName = dtype, duration = dur, expirationTime = exp } end
    end
    -- the auras worth showing: { icon, dur, exp, n, kind (debuffs) }
    local function secret(v) return type(issecretvalue) == "function" and issecretvalue(v) or false end
    function P.auras()
        local b, d, sb, sd = {}, {}, {}, {}
        local ab = { seen = 0, sec = 0, long = 0, nodur = 0, hseen = 0 }
        for i = 1, 40 do
            local a = auraAt(i, "HELPFUL")
            if type(a) ~= "table" then break end
            ab.seen = ab.seen + 1
            local dur, exp = num(a.duration), num(a.expirationTime)
            if secret(a.duration) or secret(a.expirationTime) then
                ab.sec = ab.sec + 1                                          -- (c) shown, after the plain ones; no seconds
                sb[#sb + 1] = { icon = a.icon, sdur = a.duration, sexp = a.expirationTime, secret = true }
            elseif not (dur and exp and dur > 0) then ab.nodur = ab.nodur + 1
            elseif dur > P.BUFF_DUR then ab.long = ab.long + 1
            elseif #b < P.BUFF_MAX then
                b[#b + 1] = { icon = a.icon, dur = dur, exp = exp, n = num(a.applications) }
            end
        end
        for _, a in ipairs(sb) do if #b < P.BUFF_MAX then b[#b + 1] = a end end
        for i = 1, 40 do
            local a = auraAt(i, "HARMFUL")
            if type(a) ~= "table" then break end
            ab.hseen = ab.hseen + 1
            local dur, exp = num(a.duration), num(a.expirationTime)
            local kind = (type(a.dispelName) == "string" and not secret(a.dispelName) and a.dispelName ~= "") and a.dispelName or "none"
            if secret(a.duration) or secret(a.expirationTime) then
                sd[#sd + 1] = { icon = a.icon, sdur = a.duration, sexp = a.expirationTime, secret = true, kind = kind }
            elseif dur and exp and dur > 0 and #d < P.DEBUFF_MAX then
                d[#d + 1] = { icon = a.icon, dur = dur, exp = exp, n = num(a.applications), kind = kind }
            end
        end
        for _, a in ipairs(sd) do if #d < P.DEBUFF_MAX then d[#d + 1] = a end end
        P.ab = ab
        return b, d
    end
    -- the BuffFrame's own way: "2 m" / "8 s"
    local function secs(left)
        if left >= 60 then return math.ceil(left / 60) .. " m" end
        return math.max(0, math.ceil(left)) .. " s"
    end
    P.DEBUFF_ATLAS = { Magic = "magic", Curse = "curse", Disease = "disease", Poison = "poison", Bleed = "bleed", none = "default" }
    function P.drawAuras(show)
        local b, d = {}, {}
        if show then
            -- the aura list re-read on UNIT_AURA and at most every AURA_S; the seconds every frame
            if P.auraDirty or not P.cache or now() - (P.auraAt or -100) >= P.AURA_S then
                P.cache = { P.auras() }; P.auraAt = now(); P.auraDirty = false
            end
            b, d = P.cache[1], P.cache[2]
        end
        P.nb, P.nd = #b, #d
        local step = u(P.ICON + P.IGAP)
        local top = -u(P.HH + P.BAR_GAP + P.PH + P.ROW_GAP)
        -- one row when both fit across the bar, else the debuffs on a second row (right aligned)
        local fits = (#b + #d) * (P.ICON + P.IGAP) - P.IGAP <= P.W
        local dTop = fits and top or top - u(P.ICON + 18)
        local t = now()
        local function put(list, objs, fromRight, y)
            for i, o in ipairs(objs) do
                local a = list[i]
                if a then
                    o.f:ClearAllPoints()
                    if fromRight then o.f:SetPoint("TOPRIGHT", P.f, "TOPRIGHT", -(i - 1) * step, y)
                    else o.f:SetPoint("TOPLEFT", P.f, "TOPLEFT", (i - 1) * step, y) end
                    if o.icon ~= a.icon then pcall(function() o.tx:SetTexture(a.icon) end); o.icon = a.icon end
                    if a.secret then
                        -- (c) the secret times straight to the swipe (a widget setter takes them), no arithmetic, no seconds
                        if o.sexp ~= a.sexp then
                            if not pcall(function() o.cd:SetCooldownFromExpirationTime(a.sexp, a.sdur) end) then o.cd:Clear() end
                            o.sexp, o.exp, o.dur = a.sexp, nil, nil
                        end
                    elseif o.exp ~= a.exp or o.dur ~= a.dur then
                        pcall(function() o.cd:SetCooldown(a.exp - a.dur, a.dur) end)
                        o.exp, o.dur, o.sexp = a.exp, a.dur, nil
                    end
                    if a.kind then
                        -- the game's own type border (UI-Debuff-Border-<type>-NoIcon), else the old overlay in the type's colour
                        local atl = "UI-Debuff-Border-" .. (P.DEBUFF_ATLAS[a.kind] or "default") .. "-NoIcon"
                        if o.bdAtlas ~= atl then
                            if pcall(function() o.bd:SetAtlas(atl) end) then
                                o.bd:SetTexCoord(0, 1, 0, 1); o.bd:SetVertexColor(1, 1, 1)
                            else
                                local c = (type(DebuffTypeColor) == "table" and DebuffTypeColor[a.kind]) or nil
                                local r, g, bb
                                if type(c) == "table" and num(c.r) then r, g, bb = c.r, c.g, c.b else local cc = P.DEBUFF[a.kind] or P.DEBUFF.none; r, g, bb = cc[1], cc[2], cc[3] end
                                o.bd:SetVertexColor(r, g, bb)
                            end
                            o.bdAtlas = atl
                        end
                        o.bd:Show()
                    else
                        o.bd:Hide()
                    end
                    if a.secret then
                        o.t:SetText("")
                    else
                        local left = a.exp - t
                        o.t:SetText(secs(left))
                        -- the BuffFrame's colours: gold, white under a minute
                        if left < 60 then o.t:SetTextColor(1, 1, 1) else o.t:SetTextColor(1, 0.82, 0) end
                    end
                    o.n:SetText((a.n and a.n > 1) and tostring(a.n) or "")
                    if not o.f:IsShown() then o.f:Show() end
                elseif o.f:IsShown() then
                    o.f:Hide(); o.icon, o.exp, o.dur, o.sexp = nil, nil, nil, nil
                end
            end
        end
        put(b, P.buffs, false, top)
        put(d, P.debuffs, true, dTop)
    end

    -- ---------------------------------------------------------------- when it shows
    function P.wanted()
        if not (type(WC.npWant) == "table" and WC.npWant.hud == true) then return false, "hud off" end
        local okD, dead = pcall(UnitIsDeadOrGhost, "player")
        if okD and dead == true then return false, "dead" end
        return true
    end
    -- not at full health, read: Controller's vitals (PadMap m73 WC.vitals: a plain reader if one ever answers, else m70's model,
    -- which errs LOW on purpose: the bar may stay a little longer); nil = no signal
    function P.hurt()
        if type(WC.vitals) == "function" then
            local ok, v = pcall(WC.vitals)
            if ok and type(v) == "table" and num(v.hp) and num(v.hpMax) and v.hpMax > 0 then
                return v.hp < v.hpMax * P.FULL, type(v.hpSrc) == "string" and v.hpSrc or "vitals"
            end
        end
        return nil, "none"
    end
    -- not at full health, EXACT (Controller's rounds 3-4: no curve route gives a PLAIN number; a widget setter taking the secret
    -- was never tried): the layer's alpha = UnitHealthPercent through a step curve (1 below FULL, 0 at it), so the engine itself
    -- shows the bars while hurt. Tried in plain code under pcall; any refusal and it is off for the session (P.curveBad says why),
    -- the read above stands. WowCompanionDB.prdCurve = false switches it off for good.
    function P.curveSet()
        if P.curveBad then return false end
        if type(WowCompanionDB) == "table" and WowCompanionDB.prdCurve == false then P.curveBad = "off"; return false end
        local ok, err = pcall(function()
            if not P.curve then
                local c = C_CurveUtil.CreateCurve()
                pcall(function() c:SetType(Enum.LuaCurveType.Step) end)
                -- (the same answer as a linear curve too: flat 1 up to just under FULL, flat 0 from it)
                c:AddPoint(0, 1); c:AddPoint(P.FULL - 0.005, 1); c:AddPoint(P.FULL, 0); c:AddPoint(1, 0)
                P.curve = c
            end
            local a = UnitHealthPercent("player", false, P.curve)
            if a == nil then error("no value") end
            P.g:SetAlpha(a)
        end)
        if not ok then
            P.curveBad = tostring(err):sub(1, 80)
            pcall(function() P.g:SetAlpha(1) end)
        end
        return ok
    end
    -- prd8: is the power at rest (nil = no word: counted as at rest, so the bar is never stuck on)?
    function P.powerRest(t, fight)
        if fight then return false, "fight" end
        local tok = P.ptok
        if tok == nil or tok == "MANA" then
            if type(WC.vitals) == "function" then
                local ok, v = pcall(WC.vitals)
                if ok and type(v) == "table" then
                    if v.mpSrc == "none" then return true, "no pool" end
                    if num(v.mp) and num(v.mpMax) and v.mpMax > 0 then return v.mp >= v.mpMax * P.FULL, "mana" end
                end
            end
            return nil, "mana?"
        end
        local since = math.max(P.lastFight or -1e9, P.spentAt or -1e9)
        if tok == "ENERGY" or tok == "FOCUS" then return t - since >= P.ENERGY_S, "refill" end
        if tok == "RAGE" or tok == "RUNIC_POWER" then return t - (P.lastFight or -1e9) >= P.RAGE_S, "decay" end
        return nil, "other"
    end
    function P.fight()
        if P.regen == false then return true end                       -- PLAYER_REGEN_DISABLED: starting combat
        local ok, c = pcall(UnitAffectingCombat, "player")
        return ok and c == true
    end
    function P.tick(dt)
        local want, whyNot = P.wanted()
        P.on = false
        if not want then
            if P.f and P.f:IsShown() then P.f:SetAlpha(0); P.f:Hide() end
            P.alpha, P.shown, P.why, P.offAt, P.vis, P.dy = 0, false, whyNot, nil, 0, 0
            return
        end
        P.build()
        local t = now()
        P.on = true
        local fight = P.fight()
        local hurt, src = P.hurt()
        if fight then P.lastFight = t end
        -- out of combat, past the fight's hold: the engine's curve, when it takes (the bars' layer shows exactly while hurt)
        local settled = not fight and (not P.lastFight or t - P.lastFight >= P.HOLD_S)
        local rest, rwhy = P.powerRest(t, fight)
        P.prest = rwhy
        local powerOff = rest == false and not fight
        if powerOff then
            pcall(function() P.g:SetAlpha(1) end)                       -- (the power keeps it up: the health curve may not hide it)
        elseif settled and P.curveSet() then
            hurt, src = true, "curve"                                    -- (our timing stays on: the layer itself shows or not)
        else
            pcall(function() P.g:SetAlpha(1) end)
        end
        P.src = src
        local on = fight or hurt == true or powerOff
        if on then P.offAt = nil
        elseif not P.offAt then P.offAt = t end
        -- the target: 1 while wanted; out of combat and full (or no signal), HOLD_S then a FADE_S fade
        local target = 1
        if not on then target = (t - P.offAt >= P.HOLD_S) and 0 or 1 end
        if hurt == nil and not fight and not P.lastFight and not powerOff then target = 0 end   -- no signal and no fight yet: nothing to show
        -- the eased fade-and-slide: P.vis linear in time, the alpha and the offset on one curve (continuous both ways)
        if target > P.vis then P.vis = math.min(target, P.vis + dt / P.IN_S) else P.vis = math.max(target, P.vis - dt / P.OUT_S) end
        local e = 1 - (1 - P.vis) ^ 2
        P.alpha = e
        local dy = -P.TRAVEL * (1 - e)
        if dy ~= P.dy then P.dy = dy; P.place() end
        P.why = fight and "fight" or (powerOff and "power") or (src == "curve" and "curve") or (hurt and "hurt") or (P.alpha > 0 and "fading") or "full"
        if P.alpha > 0 then
            if not P.f:IsShown() then P.f:Show() end
            P.f:SetAlpha(P.alpha * P.REST)
            P.pollAt = P.pollAt or 0
            if t - P.pollAt >= P.POLL_S then P.pollAt = t; P.values() end
            P.drawAuras(true)                                -- (a) test45: whenever the bar shows (a HoT after the fight too)
        elseif P.f:IsShown() then
            P.f:SetAlpha(0); P.f:Hide()
        end
        P.shown = P.alpha > 0
    end

    local ev = CreateFrame("Frame")
    for _, e in ipairs({ "PLAYER_REGEN_DISABLED", "PLAYER_REGEN_ENABLED", "PLAYER_ENTERING_WORLD" }) do pcall(function() ev:RegisterEvent(e) end) end
    for _, e in ipairs({ "UNIT_HEALTH", "UNIT_MAXHEALTH", "UNIT_POWER_UPDATE", "UNIT_MAXPOWER", "UNIT_DISPLAYPOWER", "UNIT_AURA",
                         "UNIT_HEAL_PREDICTION", "UNIT_ABSORB_AMOUNT_CHANGED", "UNIT_SPELLCAST_START", "UNIT_SPELLCAST_CHANNEL_START",
                         "UNIT_SPELLCAST_STOP", "UNIT_SPELLCAST_FAILED", "UNIT_SPELLCAST_SUCCEEDED", "UNIT_SPELLCAST_INTERRUPTED", "UNIT_SPELLCAST_CHANNEL_STOP" }) do
        pcall(function() ev:RegisterUnitEvent(e, "player") end)
    end
    ev:SetScript("OnEvent", function(_, e, unit, _, spellID)
        if e == "UNIT_SPELLCAST_START" or e == "UNIT_SPELLCAST_CHANNEL_START" then
            if P.f then pcall(P.castCost, spellID) end
        elseif e == "UNIT_SPELLCAST_STOP" or e == "UNIT_SPELLCAST_FAILED" or e == "UNIT_SPELLCAST_INTERRUPTED" or e == "UNIT_SPELLCAST_CHANNEL_STOP" then
            P.castEnd()
        elseif e == "UNIT_SPELLCAST_SUCCEEDED" then P.spentAt = now()
        elseif e == "PLAYER_REGEN_DISABLED" then P.regen = false
        elseif e == "PLAYER_REGEN_ENABLED" then P.regen = true
        elseif e == "PLAYER_ENTERING_WORLD" then P.regen = nil; P.loadY(); P.loadSize(); P.place()
        elseif e == "UNIT_AURA" then P.auraDirty = true
        elseif P.f and P.shown then P.pollAt = 0 end                     -- a change: the bars fed at the next frame
    end)
    ev:SetScript("OnUpdate", function(_, elapsed) pcall(P.tick, elapsed) end)
end

-- ================================================================== HIDE THE GAME'S TOOLTIPS (tt1; Settings > Top screen)
-- The user (test50): "I need a setting to hide top screen tooltips". While the app's "Hide game tooltips" is on, the game's tooltips
-- on the top screen are INVISIBLE - alpha 0 only, held there by hooks on their OnShow and OnUpdate (the game fades them itself) -
-- never hidden, cleared or moved: whatever reads a tooltip's text still reads it (Map's minimap probe uses C_TooltipInfo, Interact's
-- own dimTip only sets alpha too). The choice lives only in our saved data (WowCompanionDB.hideTips); off, the alpha is 1 again. No CVar.
WC.tips = { V = "tt1", FRAMES = { "GameTooltip", "ItemRefTooltip", "ShoppingTooltip1", "ShoppingTooltip2", "ItemRefShoppingTooltip1",
                                  "ItemRefShoppingTooltip2", "EmbeddedItemTooltip", "NamePlateTooltip", "GameNoHeaderTooltip" },
            hooked = {}, on = false }
do
    local T = WC.tips
    function T.wanted() return type(WowCompanionDB) == "table" and WowCompanionDB.hideTips == true end
    local function zero(fr)
        if not T.on then return end
        local ok, a = pcall(fr.GetAlpha, fr)
        if not ok or type(a) ~= "number" or a > 0 then pcall(fr.SetAlpha, fr, 0) end
    end
    -- hook the tooltips the client has (once each; a frame made later is picked up at the next apply)
    function T.hook()
        for _, n in ipairs(T.FRAMES) do
            local fr = _G[n]
            if type(fr) == "table" and not T.hooked[n] and type(fr.HookScript) == "function" then
                T.hooked[n] = true
                pcall(fr.HookScript, fr, "OnShow", zero)
                pcall(fr.HookScript, fr, "OnUpdate", zero)
            end
        end
    end
    function T.apply()
        T.on = T.wanted()
        T.hook()
        for _, n in ipairs(T.FRAMES) do
            local fr = _G[n]
            if type(fr) == "table" and type(fr.SetAlpha) == "function" then pcall(fr.SetAlpha, fr, T.on and 0 or 1) end
        end
        return T.on
    end
    -- the app's switch (Interact's ACT.tips)
    function T.set(on)
        if type(WowCompanionDB) ~= "table" then WowCompanionDB = {} end
        WowCompanionDB.hideTips = on and true or nil
        return T.apply()
    end
    local f = CreateFrame("Frame")
    for _, e in ipairs({ "PLAYER_LOGIN", "PLAYER_ENTERING_WORLD" }) do pcall(function() f:RegisterEvent(e) end) end
    f:SetScript("OnEvent", function() pcall(T.apply) end)
end

-- ================================================================== THE AGGRO TINT ON NAMEPLATES (at1; the user's "Nameplate tint plus bottom map")
-- The ground rings (ar1 / ar2) are GONE: an add-on cannot draw on the terrain (E:/safepath-work/research/ground: every add-on drawing
-- is a layer over the finished 3D frame), so they floated in front of everything and through walls. Instead each unpulled hostile's
-- nameplate gets OUR tint, by how close you are to its aggro radius (the rule shared with Map's bottom-map circles: clamp(20 + (mob
-- level - yours), 5, 45) yd, elites the same, a "??" mob as yours + 10):
--   none      more than APPROACH yd outside the radius;
--   yellow    within APPROACH (10) yd of its edge;
--   orange    within NEAR (5) yd of it;
--   red       inside it.
--   Never for a mob in combat (a FADE_S fade), neutral / passive mobs (reaction 4+), dead ones, ones tapped by others, or players.
--   THE DISTANCE: the range bracket (A.bracket) - its middle, smoothed (TAU, at most VMAX yd/s) - and a state changes only past a
--   HYST yd margin, so a bracket boundary cannot make it flicker.
--   LEAVE NO TRACE: our own frame on UIParent (mouse off, our own art: tint_glow.tga, a soft glow with a crisp border), ANCHORED to
--   the plate (SetPoint to it, as ag2's icons): the plate is never read, written, hooked or recoloured (its health bar untouched).
--   Settings > Top screen "Aggro tint" (default on): WowCompanionDB.aggroRings (the key ar1 used; Interact's ACT.rings).
WC.rings = { V = "at2", BASE = 20, K_BELOW = 2.2, MIN = 5, MAX = 45, SKULL = 10, TAU = 0.4, VMAX = 10, FADE_S = 0.3, APPROACH = 10, NEAR = 5, HYST = 1,
             ALPHA = 0.95, OUTSET = 6, list = {}, est = {}, react = {}, why = {}, n = 0,
             COL = { approach = { 1, 0.84, 0.24 }, near = { 1, 0.55, 0.12 }, inside = { 0.93, 0.16, 0.12 } },
             TEX = "Interface\\AddOns\\WowCompanion\\Media\\tint_glow" }
do
    local R, A = WC.rings, WC.live
    local function now() return (type(GetTime) == "function" and GetTime()) or 0 end
    local function num(v) if type(v) == "number" and v == v and not (type(issecretvalue) == "function" and issecretvalue(v)) then return v end end

    -- the aggro radius (yd) of a mob of level lv for a player of level plv; lv < 0 (or nil) is a "??" mob
    -- at2 (the user: "Fit it"; Map's fit to test58's pulls, AGGRO_DOC_CONTRACT_v1 "The shared radius rule, fitted"): Forever's
    -- radius shrinks K_BELOW (2.2) yd per level a mob is under the player (classic: 1); at or above, 1 yd per level as before
    function R.radius(lv, plv)
        if not plv then return R.BASE end
        if not lv or lv < 0 then lv = plv + R.SKULL end
        local d = lv - plv
        return math.max(R.MIN, math.min(R.MAX, R.BASE + (d < 0 and d * R.K_BELOW or d)))
    end
    function R.wanted() return not (type(WowCompanionDB) == "table" and WowCompanionDB.aggroRings == false) end
    function R.set(on)
        if type(WowCompanionDB) ~= "table" then WowCompanionDB = {} end
        if on then WowCompanionDB.aggroRings = nil else WowCompanionDB.aggroRings = false end
        return R.wanted()
    end
    -- a record's mob may be tinted: aggressive, not dead, not tapped (the reaction last read plainly kept per plate)
    function R.eligible(m, tok)
        if type(m) ~= "table" then return false, "no record" end
        local r = num(m.r)
        if r and tok then R.react[tok] = r end
        r = r or (tok and R.react[tok])
        if not r then return false, "reaction secret" end
        if r > 3 then return false, "not aggressive (" .. r .. ")" end
        if m.dd == 1 then return false, "dead" end
        if m.td == 1 then return false, "tapped" end
        return true
    end
    -- the state for a distance d and a radius r, with the last state's margin (a state changes only HYST yd past its line)
    function R.state(d, r, last)
        local e = d - r
        local function at(st) return st == "inside" and 0 or st == "near" and R.NEAR or st == "approach" and R.APPROACH or nil end
        local raw = (e <= 0 and "inside") or (e <= R.NEAR and "near") or (e <= R.APPROACH and "approach") or "far"
        if last and last ~= raw then
            -- leaving a closer state for a farther one needs HYST more; a closer state is taken at once
            local order = { inside = 1, near = 2, approach = 3, far = 4 }
            if order[raw] > order[last] and at(last) and e <= at(last) + R.HYST then return last end
        end
        return raw
    end
    function R.whyLine()
        local parts = {}
        for s2, w in pairs(R.why) do parts[#parts + 1] = s2 .. ":" .. w end
        table.sort(parts)
        return #parts > 0 and table.concat(parts, ",") or nil
    end

    local function tint(s)
        local o = R.list[s]
        if o then return o end
        local f = CreateFrame("Frame", nil, UIParent)
        f:SetFrameStrata("BACKGROUND")
        f:EnableMouse(false)
        f:Hide()
        local t = f:CreateTexture(nil, "ARTWORK")
        t:SetTexture(R.TEX)
        t:SetAllPoints(f)
        o = { f = f, t = t, a = 0 }
        R.list[s] = o
        return o
    end

    function R.tick(dt)
        local rec = A and A.last
        local on = R.wanted() and rec and type(rec.m) == "table" and not A.off
        local seen = {}
        R.n = 0
        R.why = {}
        if on then
            local plv = A.pv and A.pv(UnitLevel, "player")
            for _, m in ipairs(rec.m) do
                local st = A.slot[m.s]
                local okE, whyE = false, "no slot"
                if st then okE, whyE = R.eligible(m, st.tok) end
                local plate
                if okE then
                    local okP, p = pcall(function() return C_NamePlate.GetNamePlateForUnit(st.tok) end)
                    plate = okP and p or nil
                    if not plate then okE, whyE = false, "no plate" end
                end
                if not okE then
                    R.why[m.s] = whyE
                else
                    -- the distance: the range bracket's middle (lo + 10 past our largest check), smoothed, a new plate starts fresh
                    local lo, hi = num(m.a) or 0, num(m.b)
                    local raw = hi and (lo + hi) / 2 or lo + 10
                    local e = R.est[m.s]
                    if not e or e.tok ~= st.tok or e.at ~= st.at then
                        e = { tok = st.tok, at = st.at, d = raw }
                        R.est[m.s] = e
                    else
                        local step = (raw - e.d) * (1 - math.exp(-dt / R.TAU))
                        local cap = R.VMAX * dt
                        e.d = e.d + math.max(-cap, math.min(cap, step))
                    end
                    local r = R.radius(num(m.lv), plv)
                    e.st = R.state(e.d, r, e.st)
                    local o = tint(m.s)
                    if o.plate ~= plate then
                        o.f:ClearAllPoints()
                        -- anchored to the plate, a little bigger than it (the plate only anchored to: never read or written)
                        o.f:SetPoint("TOPLEFT", plate, "TOPLEFT", -R.OUTSET, R.OUTSET)
                        o.f:SetPoint("BOTTOMRIGHT", plate, "BOTTOMRIGHT", R.OUTSET, -R.OUTSET)
                        o.plate = plate
                    end
                    local c = R.COL[e.st]
                    if c then o.t:SetVertexColor(c[1], c[2], c[3]); o.col = e.st end
                    -- the alpha: up in a tinted state out of combat, down far away or in combat
                    local want = (m.cb ~= 1 and c) and R.ALPHA or 0
                    local k = dt / R.FADE_S * R.ALPHA
                    if o.a < want then o.a = math.min(want, o.a + k) else o.a = math.max(want, o.a - k) end
                    o.f:SetAlpha(o.a)
                    o.state, o.d, o.r = e.st, e.d, r
                    if o.a > 0 then
                        if not o.f:IsShown() then o.f:Show() end
                        R.n = R.n + 1
                    elseif o.f:IsShown() then o.f:Hide() end
                    R.why[m.s] = (m.cb == 1) and "combat" or e.st
                    seen[m.s] = true
                end
            end
        end
        for s, o in pairs(R.list) do
            if not seen[s] then
                if o.f:IsShown() then o.f:Hide() end
                o.a, o.plate = 0, nil
                o.f:ClearAllPoints()
                R.est[s] = nil
            end
        end
    end

    local f = CreateFrame("Frame")
    f:SetScript("OnUpdate", function(_, elapsed) pcall(R.tick, elapsed) end)
end
