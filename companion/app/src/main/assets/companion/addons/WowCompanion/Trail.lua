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
-- cp36 THE PATH MARKER (the user, 2026-11-11: "The polestar doted line needs its own kind of marker to point on the compass to keep
-- players on path so that they don't resort to walking at the final objective"): while Polestar guides for us (Map.lua WC.pnav: the
-- app's GO; its route read back each second as WC.pnav.legs, the same legs the bottom map draws dotted), a light blue double chevron
-- on the band points at the route a little AHEAD of the player (C.pathAhead: AHEAD yd along the route past the player's nearest
-- point on it, passed segments never looked at again; a flight / boat / portal leg: that leg's start, with its distance), and yields
-- to the objective marker over the last HAND yd of the route. The objective marker is unchanged. Read only: Polestar's own route.
-- cp37 (the user, 2026-11-11: "Okay now I actually do have a polestar objective that is not in my bag and not telling me where to
-- go", then: Polestar's own arrow stays hidden, DS Forever's compass leads): while the app's light blue Polestar row holds (Map.lua
-- pn4 WC.pnav.psOn: the app says so) the compass shows Polestar's waypoint (and the path marker, Polestar's route) whether or not a
-- quest is focused, in the log, or our GO is the one Polestar follows; cp35's guess from the GO alone stays for an app that never says.
-- cp39 (int/1429): ps-compass cp36-cp38 (the Polestar path marker, its goal whenever the row holds) on the one "row holds" word
-- (Map.lua WC.pnav.psOn, shared with ps-noloc-guide's starter item)
-- cp40 THE JITTER (the user, 2026-11-13: "For some reason the compass has been spazzing out jittering right and left all crazy for
-- the last few updates"): cp5's eased heading (C.ease) stepped its spring with plain Euler steps, which only holds while a frame is
-- shorter than ~37 ms (OMEGA x dt under 0.83). Under ~27 fps, or on any slow frame (a hitch up to the 0.1 s cap), every step
-- overshot the facing by more than it corrected, so the bar swung left and right a little wider each frame, up to whole-bar
-- swings and the 2.5 rad reset. Now each step is the spring's EXACT solution over dt (closed form, the same 0.2 s settle):
-- stable at any frame time, never overshooting, the letters still placed at sub-pixel offsets (no snapping of a moving offset).
-- cp43 THE ROUTE CONVEYOR (the user, 2026-11-17: "on the compass the blue arows arent clear what they are, I want to make them
-- animate scrolling upwards with a yellow dot between them"): the path marker (cp36's light blue double chevron) is now a
-- conveyor: three single chevrons pointing up with a small gold dot (C.GOLD on C.DISC_TEX, the quest disc's own gold) between
-- each pair, all drifting upward on a loop (C.PA.SPEED spec px/s, one chevron step C.PA.P a second) and fading out at both ends of
-- its window (C.PA.SPAN), so the wrap never pops. C.paConveyor(t) is the pure place/alpha of every piece at time t (GetTime(),
-- no stored offset: no drift, any frame time); the pieces are made once and only moved vertically, so cp40's eased x (no jitter)
-- is untouched. Still drawn from C.step (no second OnUpdate), still hidden with no route. The Home map's route dots carry the same
-- blue chevron (app side, RouteArrows.kt: four dots, then the chevron in the 5th slot; cp44 gives the compass that rhythm too).
-- Cheap per frame: no table made per frame (C.paConveyor fills the marker's own reused table), the drift steps at most C.PA.TICK (30 a second), and the pieces are re-placed only when the drift stepped or
-- the eased x / the scale moved (so a steady facing costs nothing; while turning they follow the eased x each frame, as cp36's
-- marker did, or they would lag the letters).
-- cp44 FOUR DOTS, THEN AN ARROW (the user, 2026-11-17: "go back to 4, I was meaning on the compass scroll I just miscounted"): the
-- conveyor takes the Home map's rhythm: going up, four gold dots, then one light blue chevron in the 5th slot, repeating. It is a row
-- of C.PA.M slots C.PA.S spec px apart (the old chevron-to-dot step, 7), all drifting up at the same C.PA.SPEED (14 spec px a second);
-- what stands in a slot follows the slot's place in the endless row (every C.PA.K-th a chevron), so the window need not hold whole
-- sets. The window grew from 42 to 49 spec px (7 slots) so that, with the 7 px fade at each end, at least five slots are always at
-- full alpha: one whole set of four dots and a chevron. Same costs as cp43: C.paConveyor fills its reused table, the drift steps at
-- most C.PA.TICK, nothing is re-placed unchanged; the pooled chevrons / dots a frame does not use are hidden.
-- cp42 THE 11-16 TEST BUILD MERGE: issue #358's cp41 (the font family, WC.font) and feat/big-layout's cp41 (the compass in the
-- layout editor) both kept; both notes below
-- cp41 ISSUE #358 (a player: "Character names: Cyrillic characters are displayed as empty squares instead of actual letters"; the
-- user's pick, 2026-11-16: the font family, "CreateFontFamily with the game's per-alphabet members, like the game's own frames"):
-- WC.font(fs, height, flags) - OUR TOP-SCREEN TEXT IN A FONT FAMILY, the way the game's own frames draw theirs. SetFont with
-- Fonts\FRIZQT__.TTF picks ONE file, and that file has no Cyrillic letters (253 glyphs, none in U+0400-04FF; its placeholder glyph
-- is a hollow box), so every Cyrillic letter drew as an empty square. The game's fonts are families (blizzard_fonts_shared
-- fonts.xml): one member font per alphabet, each letter drawn from its own alphabet's member. WC.font makes one family per
-- (height, flags) with CreateFontFamily and the game's own members (roman FRIZQT__, russian FRIZQT___CYR, korean 2002, simplified
-- Chinese ARKai_T, traditional Chinese blei00d; roman + russian only on a client that refuses that set), so Latin keeps today's
-- look (the same FRIZQT__ file at the same size) and the other alphabets draw. SetFontObject hands the string the object's colour,
-- shadow, justification and spacing, so the string's own go back on after it (plain values only: nothing secret is read back, a
-- string's text is never read). No family on this client (no CreateFontFamily, or it fails): SetFont(FRIZQT__) as before.
-- Runtime objects only (WowCompanionFont<n>, never saved). Hub's name strings (uf23) and our other top-screen text use it; here the
-- compass's letters, distances and numbers (C.FONT stays the roman member, the fallback).
do
    local ROMAN = "Fonts\\FRIZQT__.TTF"
    local ALL = { { "roman", ROMAN }, { "korean", "Fonts\\2002.ttf" }, { "simplifiedchinese", "Fonts\\ARKai_T.ttf" },
                  { "traditionalchinese", "Fonts\\blei00d.TTF" }, { "russian", "Fonts\\FRIZQT___CYR.TTF" } }
    local F = { V = "cp41", ROMAN = ROMAN, SETS = { ALL, { ALL[1], ALL[5] } }, set = 1, n = 0, fams = {} }
    WC.fontFamily = F
    local function plain(...)
        local isPlain = WC.isPlain
        if type(isPlain) ~= "function" then return true end
        for i = 1, select("#", ...) do if not isPlain((select(i, ...))) then return false end end
        return true
    end
    -- a new family of the current member set; a set the client refuses is dropped for this one and every later family
    local function make(h, flags)
        while F.set <= #F.SETS do
            local m = {}
            for i, a in ipairs(F.SETS[F.set]) do m[i] = { alphabet = a[1], file = a[2], height = h, flags = flags } end
            F.n = F.n + 1
            local ok, obj = pcall(CreateFontFamily, "WowCompanionFont" .. F.n, m)
            if ok and obj ~= nil then
                local okF, file = pcall(function() return obj:GetFont() end)
                if okF and type(file) == "string" and file ~= "" then return obj end
            end
            F.set = F.set + 1
        end
        return false
    end
    local function family(h, flags)
        local key = string.format("%.3f|%s", h, flags)
        local obj = F.fams[key]
        if obj == nil then
            obj = type(CreateFontFamily) == "function" and make(h, flags) or false
            F.fams[key] = obj
        end
        return obj
    end
    -- the string's own looks that SetFontObject replaces with the object's (only values that test plain FIRST are kept)
    local function keep(fs)
        local k = {}
        local ok, r, g, b, a = pcall(fs.GetTextColor, fs)
        if ok and plain(r, g, b, a) and type(r) == "number" then k.tc = { r, g, b, a or 1 } end
        local okO, x, y = pcall(fs.GetShadowOffset, fs)
        if okO and plain(x, y) and type(x) == "number" and type(y) == "number" then k.so = { x, y } end
        local okC, sr, sg, sb, sa = pcall(fs.GetShadowColor, fs)
        if okC and plain(sr, sg, sb, sa) and type(sr) == "number" then k.sc = { sr, sg, sb, sa or 1 } end
        local okH, jh = pcall(fs.GetJustifyH, fs)
        if okH and plain(jh) and type(jh) == "string" then k.jh = jh end
        local okV, jv = pcall(fs.GetJustifyV, fs)
        if okV and plain(jv) and type(jv) == "string" then k.jv = jv end
        local okS, sp = pcall(fs.GetSpacing, fs)
        if okS and plain(sp) and type(sp) == "number" then k.sp = sp end
        return k
    end
    local function put(fs, k)
        if k.tc then pcall(fs.SetTextColor, fs, k.tc[1], k.tc[2], k.tc[3], k.tc[4]) end
        if k.so then pcall(fs.SetShadowOffset, fs, k.so[1], k.so[2]) end
        if k.sc then pcall(fs.SetShadowColor, fs, k.sc[1], k.sc[2], k.sc[3], k.sc[4]) end
        if k.jh then pcall(fs.SetJustifyH, fs, k.jh) end
        if k.jv then pcall(fs.SetJustifyV, fs, k.jv) end
        if k.sp then pcall(fs.SetSpacing, fs, k.sp) end
    end
    -- in place of fs:SetFont(FRIZQT__, h, flags): true = the family, false = the plain file (no family on this client)
    function WC.font(fs, h, flags)
        flags = flags or ""
        local obj = type(h) == "number" and h > 0 and family(h, flags)
        if obj then
            local k = keep(fs)
            if pcall(fs.SetFontObject, fs, obj) then put(fs, k); return true end
        end
        fs:SetFont(ROMAN, h, flags)
        return false
    end
end
-- cp41 THE COMPASS IN THE LAYOUT EDITOR (the user, 2026-11-16:
-- "we need to make a way to basically edit the UI locations on the top screen from our UI menu"): the bar's place and size are the layout's (TapPad pad22 WC.lay.get("compass"): its top-centre at (dx, 4 + dy)
-- 1080-line pixels from the screen's top-centre, scale s % of the frame: C.place); C.lay is the piece Hub's editor gathers. The target
-- bar follows it until the target bar is moved itself (Hub uf23).
WC.compass = { V = "cp44" }
do
    local C = WC.compass
    local PI = math.pi
    local atan2 = math.atan2 or function(y, x) return math.atan(y, x) end     -- (the game's Lua 5.1 has math.atan2; the harness's not)
    -- the spec's sizes, in the Thor's top-screen pixels (1920 x 1080); C.k turns them into UI units
    -- cp23 (test60, the user: "the north east west south should be light gray like the tick marks and smaller"): the letters 14 / 12
    -- (were 22 / 17), a lifted one 2 smaller, all in the ticks' own colour (C.TICK; N no longer red), LETTER_A over their fade
    C.SPEC = { w = 860, h = 76, top = 4, line = 32, half = 400, tick = 8, letter = 14, letter2 = 12, dist = 12, lift = 25, near = 22,
               edge = 48, liftK = 2 }
    C.TICK = { 200 / 255, 180 / 255, 120 / 255 }
    C.LETTER_A = 0.9
    C.BAND = "Interface\\AddOns\\WowCompanion\\Media\\compass_band"       -- our own soft band (tools/make_compass_band.py)
    -- cp27 (test65, the user: "Can we make the checkpoint markers consistent on all areas?" ... "does not have the yellow fill"):
    -- a CHECKPOINT is our pin everywhere (the world's marker_pin, gold, on the band too; the player's own pin likewise); the QUEST
    -- NUMBER DISC is the quest's objective only: our own gold-filled disc (quest_disc.tga) with the number dark on it, the classic look
    C.PIN_TEX = "Interface\\AddOns\\WowCompanion\\Media\\marker_pin"
    C.DISC_TEX = "Interface\\AddOns\\WowCompanion\\Media\\quest_disc"
    -- cp32 (test72, the user: "the current checkpoint should be the old diamond, the next checkpoint should be the current icon ...
    -- Everywhere"): the CURRENT (nearer) checkpoint our gold diamond (marker_diamond.tga), the NEXT our gold pin, cross-faded by the
    -- eased rank as it moves up (the band and the world); the objective the disc as before
    C.DIA_TEX = "Interface\\AddOns\\WowCompanion\\Media\\marker_diamond"
    C.GOLD = { 1, 0.82, 0.3 }
    C.NUM_RGB = { 0.16, 0.09, 0.0 }
    C.MARK = {
        pin    = { atlas = "Waypoint-MapPin-Tracked",   size = 28, red = false },
        quest  = { atlas = "questnormal",               size = 32, red = false },
        corpse = { atlas = "Navigation-Tombstone-Icon", size = 26, red = false },
    }
    C.ORDER = { "corpse", "pin", "path", "quest" }       -- drawn back to front (the quest in front; cp36: the path marker under it)
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

    -- cp41: the bar's place and scale from the layout (no entry: the spec's place, scale 1)
    C.S_MIN, C.S_MAX, C.S_STEP = 70, 150, 10
    function C.place()
        local f = C.frame
        if not f then return end
        local L = WC.lay
        local e = type(L) == "table" and type(L.get) == "function" and L.get("compass") or nil
        local dx, dy = e and num(e.dx) or 0, e and num(e.dy) or 0
        local sc = math.max(C.S_MIN, math.min(C.S_MAX, e and num(e.s) or 100)) / 100
        pcall(f.SetScale, f, sc)
        f:ClearAllPoints()
        -- (the offsets are in the frame's own scaled units)
        f:SetPoint("TOP", UIParent, "TOP", C.u(dx) / sc, -C.u(C.SPEC.top + dy) / sc)
    end
    C.lay = {
        id = "compass", sn = C.S_MIN, sx = C.S_MAX, ss = C.S_STEP,
        base = function()
            local W = type(WC.lay) == "table" and WC.lay.screen() or 1920
            return W / 2, C.SPEC.top
        end,
        size = function(e)
            local sc = ((e and num(e.s)) or 100) / 100
            return C.SPEC.w * sc, C.SPEC.h * sc
        end,
        off = function(w) return -w / 2, 0 end,
        on = function() return C.wanted() end,
        apply = function()
            C.place()
            -- the target bar follows the compass (Hub's uf23 reads the compass frame's own rect)
            if type(WC.uf) == "table" and type(WC.uf.placeTarget) == "function" then pcall(WC.uf.placeTarget) end
        end,
    }

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
        -- cp26: during a route the game super-tracks our pin (no quest): the route's quest, whose objective ends the route's markers
        local R = WC.routeWp
        local routeQ = type(R) == "table" and R.active == true and num(R.qid) or nil
        if routeQ and routeQ <= 0 then routeQ = nil end
        if (not qid or qid <= 0) and routeQ then qid = routeQ end
        -- (test13: GetNextWaypoint answers only for a quest in ANOTHER zone, and this client has no QuestPOIGetIconInfo: the
        -- quest's spot on the player's own map comes first, through the same reads Map.lua uses for its quest markers)
        C.qsrc = nil
        if qid and qid > 0 and (not (okS and onPin == true) or routeQ) then
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
        -- cp35 (ps3; the user, 2026-11-11: "also the compass is still tracking my focused wow quest"): while the app's Polestar row
        -- holds - the GO it sent IS Polestar's own step (Map.lua WC.pnav, WC.pstep.last) - the compass follows Polestar's waypoint, held
        -- 5 s over a moving goal's re-sent GO and dropped at once by a newer GO for something else. t.stq keeps the game's own
        -- super-tracked spot: the engine's point (the yaw / pitch reads) is that one, whatever the compass shows.
        t.stq = t.quest
        local PN, PS = WC.pnav, type(WC.pstep) == "table" and WC.pstep.last or nil
        local psLoc = type(PS) == "table" and not PS.none and num(PS.m) and num(PS.x) and num(PS.y) and true or false
        -- cp37: the app's word first (Map.lua pn4: 38, 8 / 9). cp35's guess only stood while the GO matched Polestar's step within
        -- 0.002 - a step whose point moved less than the app's own re-send threshold (PolestarNav.MOVE 0.004) lost the goal after
        -- 5 s for good, and a GO Polestar dropped at a step change (its Nav.lua stepMoved) left nothing to match
        local told = type(PN) == "table" and type(PN.psOn) == "function" and PN.psSaid == true
        local psHold = false
        if told then
            local okH, h = pcall(PN.psOn)
            psHold = okH and h == true
            C.psAt = nil
        else
            local psOn = type(PN) == "table" and PN.active and num(PN.m) and num(PN.x) and num(PN.y) and psLoc
            if psOn and PS.m == PN.m and math.abs(PS.x - PN.x) < C.PS_TOL and math.abs(PS.y - PN.y) < C.PS_TOL then C.psAt = GetTime()
            elseif C.psAt and (not psOn or (num(PN.goAt) and PN.goAt > C.psAt)) then C.psAt = nil end
            psHold = psOn and C.psAt and GetTime() - C.psAt < 5 or false
        end
        C.psHold = psHold and true or false
        local psW
        if psHold and psLoc then
            psW = C.world(PS.m, PS.x, PS.y)
            if psW then t.quest, C.qsrc, C.qid = psW, "polestar", num(PS.q) end  -- (an accept / go-to step: no quest, C.qid nil)
        end
        -- cp38 (the user, 2026-11-11: "Okay now I actually do have a polestar objective that is not in my bag and not telling me where to
        -- go"; the device's missing goal was not reproduced off-device): what the Polestar goal stood on, for the diag's psd -
        -- the step read (nil / none / nl / loc, its map and frame), the world spot (w: 1 found, 0 none, - not asked), its continent
        -- against the player's (c: 1 same, 0 other: dropped below, - unknown), the game super-tracking a user waypoint (st: the
        -- route list then shows the pin, not the goal), Map's route pin (rw), and the GO (go: its map, and whether it matches the step)
        local goM = type(PN) == "table" and PN.active and num(PN.m) and num(PN.x) and num(PN.y) and PN.m or nil
        C.psd = table.concat({
            PS == nil and "nil" or (type(PS) ~= "table" and "?") or (PS.none and "none") or (PS.nl and "nl") or (psLoc and "loc") or "?",
            "m" .. tostring(type(PS) == "table" and num(PS.m) or "-"), "f" .. tostring(type(PS) == "table" and PS.fr or "-"),
            "w" .. ((psHold and psLoc) and (psW and "1" or "0") or "-"),
            "c" .. ((psW and me and psW.cont ~= nil and me.cont ~= nil) and (psW.cont == me.cont and "1" or "0") or "-"),
            "st" .. ((okS and onPin == true) and "1" or "0"), "rw" .. (routeQ and "1" or "0"),
            "go" .. tostring(goM or "-") .. ((goM and psLoc and PS.m == goM and math.abs(PS.x - PN.x) < C.PS_TOL
                and math.abs(PS.y - PN.y) < C.PS_TOL) and "=" or ""),
            "me" .. ((me and me.map) and tostring(me.map) or "-"),
        }, ",")
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
    function C.layout(facing, me, targets, shown)
        local S = C.SPEC
        local out = { ticks = {}, letters = {}, marks = {} }
        -- cp26: the route's shown ones (C.RT.st by id), further first (drawn back to front), as key "rt"
        local order = {}
        for _, key in ipairs(C.ORDER) do order[#order + 1] = { key = key, w = targets[key] } end
        local rts = {}
        for id, st in pairs(shown or {}) do rts[#rts + 1] = { key = "rt", id = id, w = st.w, st = st } end
        table.sort(rts, function(p, q) return (p.st.r or 9) > (q.st.r or 9) end)
        for _, e in ipairs(rts) do order[#order + 1] = e end
        for _, e in ipairs(order) do
            local key, w = e.key, e.w
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
                out.marks[#out.marks + 1] = { key = key, x = x, off = off, side = (rel > 0) and -1 or 1, dist = C.distText(d), rel = rel,
                                              id = e.id, st = e.st }
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
                    out.letters[#out.letters + 1] = { deg = deg, name = name, x = x, fade = fade, size = lift and (size - S.liftK) or size,
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
        C.place()
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
                WC.font(fs, u(#C.NAMES[deg] == 1 and S.letter or S.letter2), "OUTLINE")
                fs:SetText(C.NAMES[deg])
                fs:SetTextColor(C.TICK[1], C.TICK[2], C.TICK[3], 1)
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
            o.tx = f:CreateTexture(nil, "ARTWORK", nil, 3)
            if key == "pin" then                           -- cp27: a pin is our pin (the world's), gold
                o.sh:SetTexture(C.PIN_TEX); o.tx:SetTexture(C.PIN_TEX)
                o.tx:SetVertexColor(C.GOLD[1], C.GOLD[2], C.GOLD[3])
            else
                pcall(function() o.sh:SetAtlas(spec.atlas) end)
                pcall(function() o.tx:SetAtlas(spec.atlas) end)
            end
            o.sh:SetVertexColor(0, 0, 0, 0.85)
            o.sh:SetSize(sz + u(3), sz + u(3))
            o.tx:SetSize(sz, sz)
            o.d = f:CreateFontString(nil, "OVERLAY")
            WC.font(o.d, u(S.dist), "OUTLINE")
            if spec.red then o.d:SetTextColor(1, 0.25, 0.25, 1) else o.d:SetTextColor(1, 1, 1, 1) end
            o.a = f:CreateTexture(nil, "ARTWORK", nil, 3)
            pcall(function() o.a:SetAtlas("Navigation-Tracked-Arrow") end)
            o.a:SetSize(u(14), u(14))
            if key == "quest" then                         -- cp17: the quest's NUMBER on the game's own POI number disc
                -- cp23 (test60, the user: "missing the number in the circle"): this client's UI-QuestPoi-QuestNumber is a DARK
                -- brown disc (read from its atlas: the modern style, numbers drawn light on it); cp17's dark brown number was there
                -- but invisible on it. Now light, outlined, over the disc (OVERLAY over its ARTWORK)
                o.num = f:CreateFontString(nil, "OVERLAY", nil, 7)
                WC.font(o.num, u(15), "")
                o.num:SetTextColor(C.NUM_RGB[1], C.NUM_RGB[2], C.NUM_RGB[3], 1)   -- cp27: dark on our gold disc
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
        -- cp40: the critically damped spring's exact step (x = shown - target, held for this frame): x(t) = (x0 + (v0 + w x0) t) e^-wt,
        -- v(t) = (v0 - w (v0 + w x0) t) e^-wt. Stable for any dt (the old Euler step blew up past w dt = 0.83, under ~27 fps).
        local w = C.OMEGA
        local x0, v0 = -err, C.hv
        local c, e = v0 + w * x0, math.exp(-w * dt)
        C.hv = (v0 - w * c * dt) * e
        C.h = C.wrap(raw + (x0 + c * dt) * e)
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

    -- cp34 (the user: "The compass is spinning if I use my right stick even when my camera is locked in place" - a room of ours held
    -- the stick): while one of OUR stick catches is up (the Map room's, the radials', an NPC window's grab: EnableGamePadStick frames
    -- that keep the stick from the game) the camera cannot turn, so the stick counts as idle. Read only.
    C.CATCH = { "WowCompanionMapStickCatch", "WowCompanionRadialStickCatch", "WowCompanionInteractGrab" }
    function C.stickCaught()
        for _, n in ipairs(C.CATCH) do
            local f = _G[n]
            if type(f) == "table" then
                local ok, on = pcall(function() return f:IsVisible() and f:IsGamePadStickEnabled() end)
                if ok and on == true then return true end
            end
        end
        return false
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
            if camX and C.stickCaught() then K.from = "caught"; return 0 end
            if camX then K.from = "pad"; return camX end
        end
        if C.stickCaught() then K.from = "caught"; return 0 end
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
        local w = (okP and onPin == true) and tg.pin or tg.stq       -- (cp35: the engine's own point, not Polestar's)
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
    C.wm = { SIZE = 34, EASE_S = 0.12, LAND_S = 0.5,
             list = {}, obj = {}, free = {}, raw = {}, airAt = -100, n = 0, CAM_H = 1.9, ZOOM = 12, PITCH_S = 60, MK_S = 1, mkAt = -100, TILT = 0.1, PITCH0 = 0.3,
             JUMP_V = 7.9558, MOUNT_V = 7.9558, GRAV = 19.2911, PRESS_S = 0.3, jumpAt = -100, jumpN = 0, LOG_S = 5, logAt = -100,
             GOLD = { 1, 0.82, 0.3 }, QUEST = { 1, 0.93, 0.5 }, PIN = "Interface\\AddOns\\WowCompanion\\Media\\marker_pin",
             HOLD_S = 0.6, IN_S = 0.15, OUT_S = 0.25, FRONT_IN = 10, FRONT_OUT = 16, VIS_S = 10, visAt = -100,
             vis = { show = 0, hide = 0, held = 0, why = {} } }
    -- cp24 (test60, the user: "a little glitchy and temperamental, sometimes flashing in and out of existence"): a marker was shown
    -- only on frames when every read agreed, and hidden at once on any other - so ONE bad frame was a blink. The causes found:
    --   the engine's point read for a frame invalid, clamped, or not there (C_Navigation); the in-front gate (the camera estimate's
    --   yaw at the FOV's edge, no margin); the super-track flipping off our pin for a moment (the marker jumping to the quest's
    --   point and back); a tilt of the camera stick hiding every flag each frame though the pitch was solved that same frame; the
    --   flags' FOV / distance / screen-edge gates without margin; Map's pin advancing (the further flags' list emptied and resent).
    -- Now: a marker that loses its reading for a reason that can be a DROPOUT (no point, invalid, clamped, behind, a super-track
    -- flip, no pitch, a tilt, out of the FOV) is HELD where it was for HOLD_S, then fades (OUT_S); a deliberate end (no target: Map's
    -- "arrived" clears the pin; the route gone; the compass off) fades at once; a show fades in (IN_S). The gates keep a margin
    -- once shown (in front: FRONT_IN degrees past the half FOV to show, FRONT_OUT to keep; the flags' FOV, distance and edge alike).
    -- A marker changing what it marks (our pin <-> the quest) fades out where it was, then in at the new point: never a blink.
    -- A super-track off our pin while the route is active is a flip (cp25: the goal stays the checkpoint, placed by the estimate,
    -- else held). One "wmvis" cap line per
    -- VIS_S with any change: shows, hides, held frames, and the hides' and holds' reasons.
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
    -- cp26: one marker object per shown one (by its id), from a pool
    function C.wmBuild(id)
        local W = C.wm
        local o = W.obj[id]
        if o then return o end
        o = table.remove(W.free)
        if not o then
            local f = CreateFrame("Frame", nil, UIParent)
            f:SetFrameStrata("MEDIUM")
            f:EnableMouse(false)
            f:Hide()
            local sh = f:CreateTexture(nil, "ARTWORK", nil, 1)
            sh:SetTexture(W.PIN)
            sh:SetPoint("CENTER", f, "CENTER", 0, 0)
            sh:SetVertexColor(0, 0, 0, 0.85)
            local tx = f:CreateTexture(nil, "ARTWORK", nil, 3)
            tx:SetTexture(W.PIN)
            tx:SetAllPoints(f)
            -- cp32: the current checkpoint's diamond over the pin, cross-faded by the eased rank
            local ish = f:CreateTexture(nil, "ARTWORK", nil, 2)
            ish:SetTexture(C.DIA_TEX)
            ish:SetPoint("CENTER", f, "CENTER", 0, 0)
            ish:SetVertexColor(0, 0, 0, 0.85)
            ish:Hide()
            local dia = f:CreateTexture(nil, "ARTWORK", nil, 4)
            dia:SetTexture(C.DIA_TEX)
            dia:SetAllPoints(f)
            dia:SetVertexColor(C.GOLD[1], C.GOLD[2], C.GOLD[3])
            dia:Hide()
            local d = f:CreateFontString(nil, "OVERLAY")
            WC.font(d, C.u(13), "OUTLINE")
            d:SetPoint("TOP", f, "BOTTOM", 0, -C.u(2))
            local nm = f:CreateFontString(nil, "OVERLAY", nil, 7)            -- cp27: the objective's number on its disc
            WC.font(nm, C.u(16), "")
            nm:SetTextColor(C.NUM_RGB[1], C.NUM_RGB[2], C.NUM_RGB[3], 1)
            nm:SetPoint("CENTER", f, "CENTER", 0, 0)
            nm:Hide()
            o = { f = f, sh = sh, tx = tx, d = d, num = nm, dia = dia, ish = ish }
        end
        o.a, o.pend, o.goodAt, o.key, o.scale, o.alpha, o.col, o.text, o.holdWhy, o.lostWhy, o.look, o.iw = 0, nil, nil, id, nil, nil, nil, nil, nil, nil, nil, nil
        W.obj[id] = o
        return o
    end
    function C.wmHide()
        for _, o in pairs(C.wm.obj) do
            if o.f:IsShown() then o.f:Hide() end
            o.a, o.goodAt = 0, nil
        end
    end
    -- cp22: our pin, its TIP (the frame's bottom) at x, y; col its tint; cp26: its size by its rank. cp27: the quest's objective is
    -- the gold number disc (as on the band), its bottom on the point, the number (or "!") dark on it; a checkpoint the gold pin
    local function wmPut(id, x, y, col, txt, scale)
        local o = C.wmBuild(id)
        local st = C.RT.st[id]
        local look = (st and st.kind == "goal") and "disc" or "pin"
        if o.look ~= look then
            local tex = look == "disc" and C.DISC_TEX or C.wm.PIN
            o.tx:SetTexture(tex); o.sh:SetTexture(tex)
            o.sh:SetVertexColor(0, 0, 0, look == "disc" and 0.6 or 0.85)
            if look == "disc" then o.tx:SetVertexColor(1, 1, 1); o.col = nil; o.num:Show() else o.num:Hide() end
            o.look, o.scale = look, nil
        end
        if look == "disc" then
            col = nil
            local n = tostring((C.questNo and C.questNo()) or "!")
            if o.numText ~= n then o.num:SetText(n); o.numText = n end
            scale = scale * 0.85
        end
        if o.scale ~= scale then
            local W = C.wm
            o.f:SetSize(C.u(W.SIZE * scale), C.u(W.SIZE * scale))
            o.sh:SetSize(C.u(W.SIZE * scale + 4), C.u(W.SIZE * scale + 4))
            o.ish:SetSize(C.u(W.SIZE * scale + 4), C.u(W.SIZE * scale + 4))
            o.scale = scale
        end
        -- cp32: a checkpoint's diamond share (1 the current, 0 the next), the pin's the rest; the disc (the objective) none
        local iw = (look == "pin" and st and st.kind == "cp") and math.max(0, math.min(1, 2 - (st.r or 1))) or 0
        if o.iw ~= iw then
            o.dia:SetAlpha(iw); o.ish:SetAlpha(iw); o.tx:SetAlpha(1 - iw); o.sh:SetAlpha(1 - iw)
            if iw > 0 then o.dia:Show(); o.ish:Show() else o.dia:Hide(); o.ish:Hide() end
            if iw < 1 then o.tx:Show(); o.sh:Show() else o.tx:Hide(); o.sh:Hide() end
            o.iw = iw
        end
        o.f:ClearAllPoints()
        o.f:SetPoint("BOTTOM", UIParent, "BOTTOMLEFT", x, y)
        if col and o.col ~= col then o.tx:SetVertexColor(col[1], col[2], col[3]); o.col = col end
        if o.text ~= txt then o.d:SetText(txt); o.text = txt end
        o.x, o.y = x, y
        return true
    end
    -- cp24: the reasons a marker may only be dropping out for a moment (held HOLD_S); any other fades at once
    C.wmDROP = { nonav = true, novp = true, clamp = true, behind = true, flip = true, pitch = true, tilt = true, fov = true,
                 edge = true, near = true }
    -- cp24: every marker's visibility (o.a) toward shown or not, held through a dropout, the transitions counted; cp26: its alpha
    -- the visibility times the shared fade and its rank's (C.RT)
    function C.wmVis(on, lost, now, dt)
        local W = C.wm
        local V = W.vis
        local S = C.RT.st
        local n = 0
        for id, o in pairs(W.obj) do
            local st = S[id]
            local want = 0
            if on[id] then
                want, o.goodAt, o.holdWhy = 1, now, nil
            else
                local why = (st and lost[id]) or (st and "gone") or "left"          -- (left: no longer one of C.RT's shown)
                if st and C.wmDROP[why] and o.goodAt and now - o.goodAt < W.HOLD_S and (o.a or 0) > 0 then
                    want = 1
                    V.held = V.held + 1
                    if o.holdWhy ~= why then V.why["hold:" .. why] = (V.why["hold:" .. why] or 0) + 1; o.holdWhy = why end
                elseif st and why == "gone" then
                    want = o.a or 0                     -- (leaving the pair: where it was, its own fade - C.RT - takes it out)
                end
                o.lostWhy = why
            end
            local a0 = o.a or 0
            if want > a0 then o.a = math.min(1, a0 + dt / W.IN_S) elseif want < a0 then o.a = math.max(0, a0 - dt / W.OUT_S) end
            local _, af = 0, 1
            if st then _, af = C.rtLook(st) end
            if st and st.d then                                              -- (its distance the shared one, held or not)
                local t = C.distText(st.d)
                if o.text ~= t then o.d:SetText(t); o.text = t end
            end
            local al = st and (o.a * st.a * af) or 0
            if o.alpha ~= al then pcall(function() o.f:SetAlpha(al) end); o.alpha = al end
            if al > 0 then
                if not o.f:IsShown() then o.f:Show(); V.show = V.show + 1 end
                n = n + 1
            elseif o.f:IsShown() then
                o.f:Hide()
                V.hide = V.hide + 1
                local why = o.lostWhy or "faded"
                V.why[why] = (V.why[why] or 0) + 1
            end
            if not st and not o.f:IsShown() then                            -- gone from the list and faded: back to the pool
                W.obj[id] = nil
                W.free[#W.free + 1] = o
            end
        end
        W.vn = n
        if now - W.visAt >= W.VIS_S then
            if (V.show + V.hide + V.held) > 0 and WC.send and WC.encode then
                pcall(WC.send, WC.encode({ type = "cap", probe = "wmvis", v = C.V, show = V.show, hide = V.hide, held = V.held, why = V.why,
                                           n = n }))
            end
            W.visAt, W.vis = now, { show = 0, hide = 0, held = 0, why = {} }
        end
    end
    -- a pitch to place by: fresh, and the camera not tilted since
    function C.wmPitchOk(now)
        local W = C.wm
        if W.pitch ~= nil and W.pitchAt == now then return true end   -- cp24: solved THIS frame: good through a tilt
        if C.cam.ry and math.abs(C.cam.ry) > W.TILT then W.tilted = true end
        return W.pitch ~= nil and now - (W.pitchAt or -100) < W.PITCH_S and not W.tilted
    end
    -- cp33 (the user: "the world markers should be a toggle, off by default"): the in-world markers have their own switch, apart from
    -- the compass bar: Hub's row "uisw" 8 / 9 (WC.npWant.wm), the app's choice per character (default off). Off: nothing is drawn or
    -- placed in the world (the compass's own reads and markers go on)
    function C.wmWanted() return type(WC.npWant) == "table" and WC.npWant.wm == true end
    function C.wmStep(facing, dt)
        local W = C.wm
        if not C.wmWanted() then
            if W.wasOn then C.wmHide(); W.wasOn = false end
            W.shown, W.n = false, 0
            return
        end
        W.wasOn = true
        dt = num(dt) or 0
        local now = (type(GetTime) == "function" and GetTime()) or 0
        local on, lost = {}, {}
        local S = C.RT.st
        -- cp29: the player's place read THIS frame (the engine's point is this frame's; with the 10 Hz read the solved pitch
        -- wobbled at every read and the markers placed by it bobbed 15-30 px)
        local me = (C.wanted() and C.frame and C.frame:IsShown() and C.me_) and (C.me() or C.me_) or nil
        if me then
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
            -- cp26: the one the engine marks (our pin while super-tracked, else the quest's objective): placed on the engine's own
            -- point; every other shown one by the camera's yaw and pitch.
            -- cp28 (test66, the user: "most times I can't see anything, and they fade in only as I get close to one"; the log: st
            -- false, n 0-1): the camera's exact yaw and its PITCH were read from the engine's point only while the engine's target was
            -- one of the two shown - with the game super-tracking the quest (not our pin) mid-route it was neither, the pitch went stale
            -- in PITCH_S (3 s) and every marker placed by the estimate hid ("pitch"). Now the engine's point is READ whatever it marks
            -- (our pin, else the super-tracked quest's spot - the compass's own reads), for the yaw and the pitch, and a marker is put
            -- on it only when it is one of the two. The pitch keeps until the camera stick tilts (PITCH_S 60: only a tilt moves it).
            local tg = C.tg_ or {}
            local engW = (onPin and tg.pin) or ((not onPin) and tg.stq) or nil        -- (cp35: the engine's own, not Polestar's)
            if onPin then                                  -- (our pin read NOW: the engine's point moves with it the same frame)
                local okW, wp = pcall(C_Map.GetUserWaypoint)
                if okW and type(wp) == "table" and wp.position then
                    local px, py = C.xy(wp.position)
                    if px then engW = C.world(wp.uiMapID, px, py) or engW end
                end
            end
            -- the shown one the engine marks: ITS spot the engine's (not a list a read behind: the old pin's marker never put on
            -- the new pin's point)
            local engId
            for id, st in pairs(S) do
                if st.rank and st.it and engW and ((onPin and st.it.pin) or (not onPin and st.kind == "goal"))
                    and math.abs(st.w.x - engW.x) < 2 and math.abs(st.w.y - engW.y) < 2 then engId = id end
            end
            W.engId = engId
            W.engKind = (engW and (onPin and "pin" or "quest")) or nil
            if uw and us > 0 then
                local half = uw / 2
                local fl = half / math.tan(hfov / 2)
                -- the engine's point: read, judged, the camera's yaw and pitch from it
                local eng, engWhy = nil, (not engW and "notarget") or (not (okF and nav) and "nonav") or nil
                if engW and okF and nav then
                    local okV, vp = pcall(function() return C_Navigation.HasValidScreenPosition() end)
                    local okK, cl = pcall(function() return C_Navigation.WasClampedToScreen() end)
                    local okC, x, y = pcall(function() return nav:GetCenter() end)
                    local okS, ns = pcall(function() return nav:GetEffectiveScale() end)
                    x, y = okC and num(x) or nil, okC and num(y) or nil
                    ns = okS and num(ns) or 1
                    engWhy = (not (okV and vp == true) and "novp") or (not (okK and cl == false) and "clamp") or (not (x and y) and "novp")
                        or nil
                    if not engWhy then
                        local b, d = C.bearing(me, engW)
                        -- cp24: a margin once shown (the camera estimate's yaw at the FOV's edge)
                        local o1 = engId and W.obj[engId]
                        local gate = ((o1 and (o1.a or 0) > 0) and W.FRONT_OUT or W.FRONT_IN)
                        if math.abs(C.wrap(b - yaw)) >= hfov / 2 + math.rad(gate) then
                            engWhy = "behind"
                        else
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
                            eng = { x = x, y = y, d = d, dfwd = dfwd }
                        end
                    end
                end
                W.engWhy = engWhy
                if engId then
                    local st = S[engId]
                    lost[engId] = engWhy
                    if eng then
                        local x, y, d, dfwd = eng.x, eng.y, eng.d, eng.dfwd
                        -- cp22: in the air the engine's point rises with us: the ground's height held, then its own
                        -- ground (cp20) and the camera's rise (the jump) projected with the pitch
                        local yE = (air and W.yG) or y
                        if not air then W.yG = y end
                        local pz = st.it and st.it.p
                        local pdz = pz and C.wmDz(pz) or nil
                        W.pinDz = pdz
                        local usePdz = pdz ~= nil and C.wmPitchOk(now)
                        local dz = C.wmDzAir(usePdz and pz or nil)
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
                        on[engId] = wmPut(engId, x, y, st.kind == "goal" and W.QUEST or W.GOLD, C.distText(d), (C.rtLook(st)))
                    end
                end
                -- the others: by the camera's yaw and pitch (cp20: no pitch, none - held a moment, then a fade; never floating)
                local okPitch = C.wmPitchOk(now) and uh
                local D = C.wmZoom()
                for id, st in pairs(S) do
                    if id ~= engId and st.rank then
                        local w = st.w
                        if not okPitch then
                            lost[id] = W.tilted and "tilt" or "pitch"
                        elseif not (w.cont and me.cont and w.cont ~= me.cont) then
                            local b, d = C.bearing(me, w)
                            local rel = C.wrap(b - yaw)
                            -- cp24: the gates with a margin once shown
                            local oi = W.obj[id]
                            local seen = oi and (oi.a or 0) > 0
                            if d <= (seen and 6 or 8) then lost[id] = "near"
                            elseif math.abs(rel) >= hfov / 2 + (seen and math.rad(4) or 0) then lost[id] = "fov"
                            else
                                local x = half - fl * math.tan(rel)
                                -- cp29 (test68: the second marker "EDGE" ~95 times, standing still at 113 yd with a fresh 19.6 deg
                                -- pitch - which puts a point on its own ground there well on screen, near the horizon): the app's
                                -- height for a checkpoint (Map.lua's uwp z digits: its terrain cell less the player's z) can be far off
                                -- and lifted it over the screen's top. A height that puts the point off screen is not used: the point
                                -- is placed at the player's own ground (less only a jump's rise), and the bad height counted (dzBad)
                                local dzp = C.wmDzAir(st.it and st.it.p or nil)
                                local yo = C.wmProj(W.pitch, D, W.CAM_H, d * math.cos(rel), dzp, fl)
                                local y = yo and uh / 2 + yo or nil
                                local raw = { x = x, y = y, dz = dzp, rel = rel }
                                if not (y and y > 0 and y < uh) and dzp ~= -(W.lift or 0) then
                                    local yo0 = C.wmProj(W.pitch, D, W.CAM_H, d * math.cos(rel), -(W.lift or 0), fl)
                                    if yo0 then y, raw.dzBad = uh / 2 + yo0, true end
                                end
                                W.raw[id] = raw
                                local m = seen and 0.99 or 0.95
                                if y and math.abs(x - half) < half * m and y > 0 and y < uh then
                                    on[id] = wmPut(id, x, y, st.kind == "goal" and W.QUEST or W.GOLD, C.distText(d), (C.rtLook(st)))
                                else
                                    lost[id] = "edge"
                                end
                            end
                        end
                    end
                end
            end
        end
        C.wmVis(on, lost, now, dt)
        -- cp28: one "wmk" cap line a second while a route runs (or anything is listed): each of C.RT's shown ones with its distance,
        -- its shared alpha (a) and the world's (wa), where it was put (x, y) and how (eng / est) or why not
        local R0 = WC.routeWp
        if now - W.mkAt >= W.MK_S and ((type(R0) == "table" and R0.active == true) or next(S) ~= nil) and WC.send and WC.encode then
            W.mkAt = now
            local m = {}
            for id in pairs(W.raw) do if not S[id] then W.raw[id] = nil end end
            for id, st in pairs(S) do
                local o = W.obj[id]
                m[#m + 1] = { id = id, d = st.d and math.floor(st.d + 0.5) or nil, a = math.floor(st.a * 100 + 0.5), r = st.rank,
                              wa = o and math.floor((o.alpha or 0) * 100 + 0.5) or 0,
                              x = (o and on[id] and o.x) and math.floor(o.x + 0.5) or nil, y = (o and on[id] and o.y) and math.floor(o.y + 0.5) or nil,
                              how = on[id] and ((id == W.engId) and "eng" or "est") or (lost[id] or (st.rank and "?" or "fading")) }
                local rw = W.raw[id]                                         -- cp29: the estimate's own numbers, shown or not
                if rw and id ~= W.engId then
                    local e = m[#m]
                    e.rx, e.ry = rw.x and math.floor(rw.x + 0.5) or nil, rw.y and math.floor(rw.y + 0.5) or nil
                    e.dz = rw.dz and math.floor(rw.dz * 10 + 0.5) / 10 or nil
                    e.rel = rw.rel and math.floor(math.deg(rw.rel) + 0.5) or nil
                    e.dzBad = rw.dzBad or nil
                end
            end
            pcall(WC.send, WC.encode({ type = "cap", probe = "wmk", v = C.V, m = m, eng = W.engKind, ew = W.engWhy,
                bad = (#C.RT.bad > 0) and table.concat(C.RT.bad, ",") or nil,
                st = W.engKind == "pin", route = type(R0) == "table" and R0.active == true or nil,
                pAge = W.pitchAt and math.floor(now - W.pitchAt + 0.5) or nil, tilt = W.tilted or nil,
                pitch = W.pitch and math.floor(math.deg(W.pitch) * 10 + 0.5) / 10 or nil }))
        end
        -- (by rank, for the diag and the harness: list[1] the nearer one's marker, list[2] the second's; kept when none)
        local n = 0
        for _ in pairs(on) do n = n + 1 end
        for r, it in ipairs(C.RT.pair or {}) do
            local o = W.obj[it.id]
            if o then W.list[r] = o end
        end
        local o1 = W.list[1]
        if o1 then W.f, W.sh, W.tx, W.d = o1.f, o1.sh, o1.tx, o1.d end
        local p1 = (C.RT.pair or {})[1]
        W.shown, W.n = (p1 and on[p1.id]) == true, n
    end

    -- the world reads (player, pin, quest, corpse) ten times a second; the strip itself every frame
    C.readAt = -1
    function C.read(force)
        local now = (type(GetTime) == "function" and GetTime()) or 0
        if not force and now - C.readAt < 0.1 then return false end
        C.readAt = now
        C.me_ = C.me()
        C.tg_ = C.targets(C.me_)
        C.rt_ = C.route(C.tg_)
        local okA, pa = pcall(C.pathTarget, C.me_)                          -- cp36: the path marker's point (nil: none)
        C.pa_ = okA and pa or nil
        if not okA then C.paWhy = "err" end
        return true
    end

    -- cp26 (test63, the user: "I always want the world and the compass to show the next two markers. Instead of making the closest
    -- one just vanish I want it to fade as I get closer to it and then once it vanishes fully the next one after begins to fade in
    -- beyond the 2nd marker"). ONE list for the compass and the world (C.route, with the reads), ONE state for both (C.rtStep, each
    -- frame), so they always show the same markers at the same distances:
    --   THE LIST: during a route, the game's pin (the next checkpoint) and the app's further ones (Map.lua's RWP.more; while Map
    --   swaps them on an advance, its moreOld for MORE_OLD_S: never emptied for a moment), then the quest's objective once the app
    --   sent every checkpoint left (fewer than its most). No route: the player's own super-tracked pin, else the quest's objective.
    --   Each by its spot (id), so a marker keeps its identity as the list shifts.
    --   THE PAIR: the first two not passed. A checkpoint is passed at NEAR (the app's own DangerGrid.PASS_YD, when it moves the pin
    --   on: cp31 10 yd). The nearer one FADES BY DISTANCE (full at FAR, 0 at NEAR: gone exactly when it is passed); the second is full.
    --   When the first has gone, the second is the nearer (no jump: its rank eases over RANK_S, a little larger and stronger) and the
    --   next fades in behind it (IN_S) - only once nothing else is still fading out (never three, k+2 only after k is gone).
    --   Anything leaving the pair (passed, or dropped by the app earlier) fades out over OUT_S where it was.
    --   The objective is never faded by distance (arrived: the quest's marker as before); the further flags beyond the two are gone.
    -- cp29 (test68, the user: "the fade out should be 25 to 0 yards"): full to FAR 25 yd, gone at NEAR - the app's pass radius
    -- (DangerGrid.PASS_YD), which Map moves down to match (4 yd): keep the two the same. cp31 (test70, the user: "from 25-0 to 25-10"):
    -- gone at 10 yd, Map's pass radius 10 to match
    C.RT = { FAR = 25, NEAR = 10, IN_S = 0.4, OUT_S = 0.25, DOWN1_S = 0.6, RANK_S = 0.4, K2 = 0.8, A2 = 0.75, MORE_OLD_S = 2,
             SAME_YD = 4, GOAL_NEAR = 8, GOAL_ON = 40, MORE_MAX = 2, STEP_YD = 300, st = {}, bad = {} }
    local function rtAt(w) return math.floor(w.x + 0.5) .. ":" .. math.floor(w.y + 0.5) end
    function C.route(tg)
        tg = tg or {}
        local RT, R = C.RT, WC.routeWp
        local now = (type(GetTime) == "function" and GetTime()) or 0
        local items = {}
        RT.bad = {}
        local function dup(w, yd)
            for _, it in ipairs(items) do
                if math.sqrt((it.w.x - w.x) ^ 2 + (it.w.y - w.y) ^ 2) < yd then return true end
            end
            return false
        end
        -- cp27 (test65, the user: "Sometimes I see the marker all the way at the very end, like 1000 yards away. I should always see
        -- the closest two checkpoints"): cp26 let the objective join when the app's list was SHORT - but it is short on every leg
        -- that ends at a way (a flight master, a tram, the map's edge: the app's MapView.routePin sends that way as the last
        -- checkpoint, the objective far beyond it), and while Map swaps it. Now the objective joins a route's list ONLY where the
        -- app's own list reaches it: a checkpoint of the list standing on it (within GOAL_ON) - it stands for that one. Every item
        -- carries why it is there (the diag's rt).
        if type(R) == "table" and R.active == true and tg.pin then
            items[1] = { id = "cp:" .. rtAt(tg.pin), w = tg.pin, kind = "cp", pin = true, p = R.pinZ, why = "pin" }
            local more, src = (type(R.more) == "table" and #R.more > 0) and R.more or nil, "more"
            if not more and type(R.moreOld) == "table" and now - (R.moreOldAt or -100) < RT.MORE_OLD_S then more, src = R.moreOld, "old" end
            more = more or {}
            for i, c in ipairs(more) do
                if c.w == nil then c.w = C.world(c.m, c.x, c.y) or false end
                -- cp30 (test69: a further point 1555 yd off on a 541 yd route): a step over STEP_YD from the last good point before it
                -- is implausible - that one is skipped (the next judged from the last good one) and logged (the diag's rtbad)
                local prev = items[#items].w
                local step = c.w and math.sqrt((c.w.x - prev.x) ^ 2 + (c.w.y - prev.y) ^ 2) or 0
                if c.w and step > RT.STEP_YD then
                    RT.bad[#RT.bad + 1] = "cp:" .. rtAt(c.w) .. "@" .. math.floor(step + 0.5) .. ":" .. src .. i
                    c = { w = false }
                end
                if c.w and tg.quest and math.sqrt((c.w.x - tg.quest.x) ^ 2 + (c.w.y - tg.quest.y) ^ 2) < RT.GOAL_ON then
                    if not dup(tg.quest, RT.GOAL_NEAR) then
                        items[#items + 1] = { id = C.goalId(), w = tg.quest, kind = "goal", why = "on" .. src .. i }
                    end
                    break                                                    -- (the objective ends the route)
                elseif c.w and not dup(c.w, RT.SAME_YD) then
                    items[#items + 1] = { id = "cp:" .. rtAt(c.w), w = c.w, kind = "cp", p = c, why = src .. i }
                end
            end
        else
            local okP, onPin = pcall(function() return C_SuperTrack.IsSuperTrackingUserWaypoint() end)
            -- cp38 (the user, 2026-11-11: "Okay now I actually do have a polestar objective that is not in my bag and not telling me where
            -- to go", and Polestar's own arrow stays hidden): while Polestar leads (C.qsrc "polestar": its row holds) its goal comes
            -- FIRST even when the game super-tracks a user waypoint - cp37 showed only that pin then (a pin left from before, or the one
            -- the game falls back to once "polestar unfocus" clears the quest focus); the pin stays, second
            local psLead = C.qsrc == "polestar" and tg.quest ~= nil
            if okP and onPin == true and tg.pin and not psLead then
                items[1] = { id = "cp:" .. rtAt(tg.pin), w = tg.pin, kind = "pin", pin = true, why = "ownpin" }   -- (the same id as in a route)
            elseif tg.quest then
                items[1] = { id = C.goalId(), w = tg.quest, kind = "goal", why = psLead and "polestar" or "noroute" }
                if psLead and okP and onPin == true and tg.pin and not dup(tg.pin, RT.GOAL_NEAR) then
                    items[2] = { id = "cp:" .. rtAt(tg.pin), w = tg.pin, kind = "pin", pin = true, why = "ownpin2" }
                end
            end
        end
        return items
    end
    -- each frame: the pair, every shown one's alpha (a), eased rank (r) and distance (d), in C.RT.st by id
    function C.rtStep(dt, snap)
        local RT = C.RT
        local S, me = RT.st, C.me_
        local inList, pair, rank = {}, {}, {}
        for _, it in ipairs(C.rt_ or {}) do
            inList[it.id] = true
            local d
            if me then local _, dd = C.bearing(me, it.w); d = dd end
            it.d = d
            -- cp30 (test69: the pin 7 yd away missing, a further point ranked first): no pass of our own - a checkpoint leaves only
            -- when the app moves the pin on (Map's list); it is faded out by then (0 at NEAR, the app's pass radius). The pair is the
            -- list's first two, in ROUTE order: a further point never ahead of the pin
            if #pair < 2 then pair[#pair + 1] = it; rank[it.id] = #pair end
        end
        local busy = false                                           -- something still fading out: nothing new fades in yet
        for id, st in pairs(S) do if not rank[id] and st.a > 0 then busy = true end end
        for i, it in ipairs(pair) do
            local st = S[it.id]
            if not st then st = { a = 0, r = i }; S[it.id] = st end
            st.it, st.w, st.d, st.kind, st.rank = it, it.w, it.d or st.d, it.kind, i
            local want = 1
            if i == 1 and it.kind == "cp" and it.d then want = math.max(0, math.min(1, (it.d - RT.NEAR) / (RT.FAR - RT.NEAR))) end
            if snap then
                st.a, st.r = want, i
            else
                if want > st.a then
                    if not (busy and st.a <= 0) then st.a = math.min(want, st.a + dt / RT.IN_S) end
                else
                    st.a = math.max(want, st.a - dt / RT.DOWN1_S)
                end
                if st.r < i then st.r = math.min(i, st.r + dt / RT.RANK_S) else st.r = math.max(i, st.r - dt / RT.RANK_S) end
            end
        end
        for id, st in pairs(S) do
            if not rank[id] then
                st.rank = nil
                st.a = snap and 0 or math.max(0, st.a - dt / RT.OUT_S)
                if st.a <= 0 then S[id] = nil end
            end
        end
        RT.pair = pair
    end
    -- a shown one's look by its eased rank: { scale, alpha factor }
    function C.rtLook(st)
        local RT, k = C.RT, math.max(0, math.min(1, (st.r or 1) - 1))
        return 1 + (RT.K2 - 1) * k, 1 + (RT.A2 - 1) * k
    end
    -- the compass's own targets beside the list: the player's own pin when it is not in the list, the corpse
    function C.marks()
        local tg = C.tg_ or {}
        local t = { corpse = tg.corpse }
        local listed = false
        for _, it in ipairs(C.rt_ or {}) do if it.pin then listed = true end end
        if tg.pin and not listed then t.pin = tg.pin end
        t.path = C.pa_                                                      -- cp36
        return t
    end

    -- ---------------------------------------------------------------- cp36: THE PATH MARKER (the user, 2026-11-11)
    -- Polestar's route (Map.lua WC.pnav.legs: { k, c, p = { wx, wy, ... } }, world yards as C.world's, the current leg first) ->
    -- where the path marker points. Modelled on Polestar's own arrow (Nav/Walk.lua Walk.Aim: the nearest segment from the current
    -- one to a few ahead, the index only ever moving on):
    --   * the first leg a walk: the walk legs in a row from it are ONE line; the player's nearest point on it, searched from the
    --     segment reached so far (st.idx) over WIN segments (all further ones when that is over STRAY yd off) - the segments behind
    --     are passed and never looked at again; the point AHEAD yd further along the line from there (clamped at its end). That end
    --     is the next leg's start (a flight master, a dock, a portal: the marker then shows its distance) or the route's end: within
    --     HAND yd of the end ALONG the route, the marker yields to the objective (the route's length, never the straight line: a
    --     goal close by over a cliff keeps the path marker).
    --   * the first leg a flight / boat / zeppelin / tram / portal / hearth / lift: that leg's start, with its distance; gone within
    --     REACH yd of it, and for the rest of that leg (aboard: the start is behind; on a taxi), until Polestar moves the route on.
    --   * a new route (its signature, Map's legSig) starts again at its first segment.
    -- cp37: cp35's GO match (an app that never says pn4's 38, 8): the GO's own rounding (1/2590) and the app's re-send threshold
    -- (PolestarNav.MOVE 0.004) inside it
    C.PS_TOL = 0.006
    C.PA = { AHEAD = 30, HAND = 35, REACH = 8, WIN = 12, STRAY = 25, STALE_S = 3, RGB = { 120 / 255, 200 / 255, 1 }, st = {} }
    function C.pathAhead(me, legs, st, sig)
        local PA = C.PA
        st = st or {}
        if not me or type(legs) ~= "table" or #legs == 0 then return nil, "none" end
        if st.sig ~= sig then st.sig, st.idx, st.boarded = sig, 1, nil end
        local function sameCont(leg) return not (num(leg.c) and num(me.cont) and leg.c ~= me.cont) end
        local function dist(ax, ay, bx, by) return math.sqrt((bx - ax) ^ 2 + (by - ay) ^ 2) end
        local L1 = legs[1]
        if type(L1) ~= "table" or type(L1.p) ~= "table" or not sameCont(L1) then return nil, "cont" end
        if L1.k ~= "walk" then
            local sx, sy = num(L1.p[1]), num(L1.p[2])
            if not (sx and sy) then return nil, "none" end
            local okT, taxi = pcall(UnitOnTaxi, "player")
            local d = dist(me.x, me.y, sx, sy)
            local key = tostring(L1.k) .. ":" .. math.floor(sx + 0.5) .. ":" .. math.floor(sy + 0.5)
            if d <= PA.REACH then st.boarded = key end
            if okT and taxi == true then return nil, "taxi" end
            if st.boarded == key then return nil, (d <= PA.REACH) and "at" or "aboard" end
            return { x = sx, y = sy, cont = me.cont, kind = L1.k, d = d }, "leg"
        end
        -- the walk legs in a row from the first: one line
        local xs, ys, nextK, last = {}, {}, nil, 0
        for i, leg in ipairs(legs) do
            if type(leg) ~= "table" or type(leg.p) ~= "table" then break end
            if leg.k ~= "walk" or not sameCont(leg) then nextK = leg.k or "walk"; break end
            local p = leg.p
            for j = 1, #p - 1, 2 do
                local x, y = num(p[j]), num(p[j + 1])
                if x and y and not (#xs > 0 and math.abs(x - xs[#xs]) < 0.05 and math.abs(y - ys[#ys]) < 0.05) then
                    xs[#xs + 1], ys[#ys + 1] = x, y
                end
            end
            last = i
        end
        local n = #xs
        if n == 0 then return nil, "none" end
        local isLast = last == #legs
        local qx, qy, a
        if n == 1 then
            qx, qy, a = xs[1], ys[1], 1
        else
            local i0 = math.max(1, math.min(st.idx or 1, n - 1))
            local function scan(a0, a1)
                local off, ba, bx, by = math.huge, a0, xs[a0], ys[a0]
                for k = a0, a1 do
                    local ax, ay, ex, ey = xs[k], ys[k], xs[k + 1], ys[k + 1]
                    local dx, dy = ex - ax, ey - ay
                    local ll = dx * dx + dy * dy
                    local t = (ll > 0) and (((me.x - ax) * dx + (me.y - ay) * dy) / ll) or 0
                    t = math.max(0, math.min(1, t))
                    local px, py = ax + dx * t, ay + dy * t
                    local o = dist(me.x, me.y, px, py)
                    if o < off then off, ba, bx, by = o, k, px, py end
                end
                return off, ba, bx, by
            end
            local off, ba, bx, by = scan(i0, math.min(n - 1, i0 + PA.WIN))
            if off > PA.STRAY and i0 + PA.WIN < n - 1 then
                local o2, a2, x2, y2 = scan(i0, n - 1)
                if o2 < off then off, ba, bx, by = o2, a2, x2, y2 end
            end
            st.idx, a, qx, qy = ba, ba, bx, by
            st.off = off
        end
        -- the yards left along the line from there, and the point AHEAD yd on
        local left = (a < n) and dist(qx, qy, xs[a + 1], ys[a + 1]) or 0
        for k = a + 1, n - 1 do left = left + dist(xs[k], ys[k], xs[k + 1], ys[k + 1]) end
        st.left = left
        if isLast and left <= PA.HAND then return nil, "hand" end
        local rem, cx, cy, k = PA.AHEAD, qx, qy, a
        local tx, ty, atEnd
        while true do
            if k >= n then tx, ty, atEnd = xs[n], ys[n], true; break end
            local ex, ey = xs[k + 1], ys[k + 1]
            local sl = dist(cx, cy, ex, ey)
            if sl >= rem and sl > 0 then tx, ty = cx + (ex - cx) * rem / sl, cy + (ey - cy) * rem / sl; break end
            rem, cx, cy, k = rem - sl, ex, ey, k + 1
        end
        if atEnd and not isLast then
            local d = dist(me.x, me.y, tx, ty)
            if d <= PA.REACH then return nil, "at" end
            return { x = tx, y = ty, cont = me.cont, kind = nextK, d = d }, "end"
        end
        return { x = tx, y = ty, cont = me.cont, kind = "walk" }, "walk"
    end
    -- the path marker's point now: only while Polestar guides for us (the app's GO) with a fresh route read
    function C.pathTarget(me)
        local PN, PA = WC.pnav, C.PA
        local now = (type(GetTime) == "function" and GetTime()) or 0
        if type(PN) ~= "table" or not (PN.active or C.psHold) or type(PN.legs) ~= "table" or not num(PN.legsAt) or now - PN.legsAt > PA.STALE_S then
            PA.st, C.paWhy = {}, "off"
            return nil
        end
        local w, why = C.pathAhead(me, PN.legs, PA.st, PN.legSig)
        C.paWhy = why
        return w
    end
    -- its look (cp43, cp44): a light blue CONVEYOR pointing up (go on this way): going up, four gold dots then a single chevron,
    -- repeating, scrolling upward on a loop, a dark rim under each piece; drawn on our frame, made once.
    -- ARM: one chevron's arms in spec px from its centre, y up (12 wide, 4 tall); S the step from slot to slot; K slots a set (K - 1
    -- dots, then the chevron), P = K * S the step from chevron to chevron; M slots in the window, SPAN = M * S; FADE the soft ends;
    -- SPEED spec px a second; NCH / NDOT the most chevrons / dots M slots in a row can hold (the pools)
    C.PA.ARM = { { 0, 2, -6, -2 }, { 0, 2, 6, -2 } }
    C.PA.K, C.PA.S, C.PA.M, C.PA.SPEED, C.PA.FADE, C.PA.DOT = 5, 7, 7, 14, 7, 4.5
    C.PA.P, C.PA.SPAN = C.PA.K * C.PA.S, C.PA.M * C.PA.S
    C.PA.NCH = math.ceil(C.PA.M / C.PA.K)
    C.PA.NDOT = C.PA.M - math.floor(C.PA.M / C.PA.K)
    C.PA.W, C.PA.RIM, C.PA.SIZE = 2.5, 4.5, 18
    C.PA.TICK = 1 / 30                                                 -- the drift's step at most (s): 30 places a second
    -- every slot at time t, bottom to top: { kind = "chev" | "dot", y (spec px from the band line, up), a (0..1) }; M slots S apart,
    -- the lowest at -SPAN / 2 + (the drift mod S); a slot's kind from its place in the endless row (the drift's whole steps taken
    -- off), every K-th a chevron; alpha 0 at both window ends. [out]: a table to fill in place (the draw passes its own, made once:
    -- nothing made per frame); none: a new one (the checks)
    function C.paConveyor(t, out)
        local PA = C.PA
        local S, K, half = PA.S, PA.K, PA.SPAN / 2
        local off = (tonumber(t) or 0) * PA.SPEED
        local base = math.floor(off / S)
        local frac = off - base * S
        out = out or {}
        for j = 0, PA.M - 1 do
            local y = -half + j * S + frac
            local a = (half - math.abs(y)) / PA.FADE
            if a > 1 then a = 1 elseif a < 0 then a = 0 end
            local p = out[j + 1]
            if not p then p = {}; out[j + 1] = p end
            p.kind, p.y, p.a = ((j - base) % K == K - 1) and "chev" or "dot", y, a
        end
        return out
    end
    function C.paBuild()
        if C.pa then return C.pa end
        local f = C.frame
        local o = { rim = {}, ln = {}, dsh = {}, dot = {}, all = {}, cv = {} }
        local R, G = C.PA.RGB, C.GOLD
        for i = 1, C.PA.NCH * #C.PA.ARM do
            o.rim[i] = f:CreateLine(nil, "ARTWORK", nil, 0); o.rim[i]:SetColorTexture(0, 0, 0, 0.85)
            o.ln[i] = f:CreateLine(nil, "ARTWORK", nil, 2); o.ln[i]:SetColorTexture(R[1], R[2], R[3], 1)
            o.all[#o.all + 1] = o.rim[i]; o.all[#o.all + 1] = o.ln[i]
        end
        for i = 1, C.PA.NDOT do
            o.dsh[i] = f:CreateTexture(nil, "ARTWORK", nil, 0); o.dsh[i]:SetTexture(C.DISC_TEX); o.dsh[i]:SetVertexColor(0, 0, 0, 0.85)
            o.dsh[i]:SetSize(C.u(C.PA.DOT + 2), C.u(C.PA.DOT + 2))
            o.dot[i] = f:CreateTexture(nil, "ARTWORK", nil, 2); o.dot[i]:SetTexture(C.DISC_TEX); o.dot[i]:SetVertexColor(G[1], G[2], G[3], 1)
            o.dot[i]:SetSize(C.u(C.PA.DOT), C.u(C.PA.DOT))
            o.all[#o.all + 1] = o.dsh[i]; o.all[#o.all + 1] = o.dot[i]
        end
        o.d = f:CreateFontString(nil, "OVERLAY")
        WC.font(o.d, C.u(C.SPEC.dist), "OUTLINE")
        o.d:SetTextColor(R[1], R[2], R[3], 1)
        o.a = f:CreateTexture(nil, "ARTWORK", nil, 3)
        pcall(function() o.a:SetAtlas("Navigation-Tracked-Arrow") end)
        o.a:SetVertexColor(R[1], R[2], R[3])
        o.a:SetSize(C.u(14), C.u(14))
        o.all[#o.all + 1] = o.d; o.all[#o.all + 1] = o.a
        C.pa = o
        return o
    end
    function C.paVis(o, on)                                          -- (no new locals: show / hide come later)
        if on then if not o:IsShown() then o:Show() end elseif o:IsShown() then o:Hide() end
    end
    function C.paDraw(m, w)
        local f, u, S, PA = C.frame, C.u, C.SPEC, C.PA
        local o = C.paBuild()
        local cx, cy = m.x, -S.line
        local now = (type(GetTime) == "function" and GetTime()) or 0
        -- the drift's clock steps at most every TICK (a clock that went back: re-started)
        if not o.tt or now - o.tt >= PA.TICK or now < o.tt then o.tt = now end
        local us = u(1)
        -- re-placed only when the drift stepped, the eased x or the scale moved, or the pieces were hidden
        if o.shown and o.pt == o.tt and o.px == cx and o.pu == us then
            C.paText(o, f, u, S, PA, m, w)
            o.shown = true
            return
        end
        o.pt, o.px, o.pu = o.tt, cx, us
        local ci, di = 0, 0
        local cv = C.paConveyor(o.tt, o.cv)
        for q = 1, #cv do
            local p = cv[q]
            local py = cy + p.y
            if p.kind == "chev" then
                ci = ci + 1
                for j, arm in ipairs(PA.ARM) do
                    local x1, y1, x2, y2 = arm[1], arm[2], arm[3], arm[4]
                    local dx, dy = x2 - x1, y2 - y1
                    local l = math.sqrt(dx * dx + dy * dy)
                    local ex, ey = dx / l, dy / l                          -- (the rim a pixel longer at both ends)
                    local i = (ci - 1) * #PA.ARM + j
                    local r, ln = o.rim[i], o.ln[i]
                    r:SetThickness(u(PA.RIM)); r:SetAlpha(p.a)
                    r:SetStartPoint("TOP", f, u(cx + x1 - ex), u(py + y1 - ey)); r:SetEndPoint("TOP", f, u(cx + x2 + ex), u(py + y2 + ey))
                    ln:SetThickness(u(PA.W)); ln:SetAlpha(p.a)
                    ln:SetStartPoint("TOP", f, u(cx + x1), u(py + y1)); ln:SetEndPoint("TOP", f, u(cx + x2), u(py + y2))
                    C.paVis(r, p.a > 0); C.paVis(ln, p.a > 0)
                end
            else
                di = di + 1
                local sh, d = o.dsh[di], o.dot[di]
                sh:SetAlpha(p.a); d:SetAlpha(p.a)
                sh:SetPoint("CENTER", f, "TOP", u(cx), u(py)); d:SetPoint("CENTER", f, "TOP", u(cx), u(py))
                C.paVis(sh, p.a > 0); C.paVis(d, p.a > 0)
            end
        end
        for i = ci * #PA.ARM + 1, #o.ln do C.paVis(o.rim[i], false); C.paVis(o.ln[i], false) end      -- cp44: the pool's unused
        for i = di + 1, #o.dot do C.paVis(o.dsh[i], false); C.paVis(o.dot[i], false) end
        C.paText(o, f, u, S, PA, m, w)
        o.shown = true
    end
    -- the distance text and the off-band side arrow (every frame: their own checks keep them cheap)
    function C.paText(o, f, u, S, PA, m, w)
        -- terse: a distance only for a leg's start (where the walk ends: a flight master, a dock, a portal), under the window
        local txt = (type(w) == "table" and w.kind ~= "walk" and num(w.d)) and C.distText(w.d) or nil
        if txt then
            if o.text ~= txt then o.d:SetText(txt); o.text = txt end
            o.d:SetPoint("TOP", f, "TOP", u(m.x), -u(S.line + PA.SPAN / 2 + 2)); C.paVis(o.d, true)
        else
            C.paVis(o.d, false)
        end
        if m.off then
            if o.side ~= m.side then pcall(function() o.a:SetRotation(m.side > 0 and -PI / 2 or PI / 2) end); o.side = m.side end
            o.a:SetPoint("CENTER", f, "TOP", u(m.x + m.side * (PA.SIZE / 2 + 10)), -u(S.line)); C.paVis(o.a, true)
        else
            C.paVis(o.a, false)
        end
    end
    function C.paHide()
        local o = C.pa
        if not o or not o.shown then return end
        for _, p in ipairs(o.all) do C.paVis(p, false) end
        o.shown = false
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
        C.rtStep(dt, snap)
        pcall(C.wmStep, raw, dt)
        local L = C.layout(heading, C.me_, C.marks(), C.RT.st)
        C.last = L
        if fresh then C.diag(raw, C.me_, C.tg_ or {}, L) end
        local u, S = C.u, C.SPEC
        local seen = {}
        for _, t in ipairs(L.ticks) do
            local o = C.slot[t.deg].tick
            seen[t.deg] = true
            o:SetVertexColor(C.TICK[1], C.TICK[2], C.TICK[3], 0.67 * t.fade)
            o:SetPoint("CENTER", f, "TOP", u(t.x), -u(S.line))
            show(o)
        end
        for _, l in ipairs(L.letters) do
            local s = C.slot[l.deg]
            seen[l.deg] = true
            if s.lifted ~= l.lifted then                    -- the font only when the size changes (a marker came / went)
                WC.font(s.fs, u(l.size), "OUTLINE")
                s.lifted = l.lifted
            end
            s.fs:SetAlpha(C.LETTER_A * l.fade)
            s.fs:SetPoint("CENTER", f, "TOP", u(l.x), -u(l.y))
            show(s.fs)
        end
        for deg, s in pairs(C.slot) do
            if not seen[deg] then hide(s.fs or s.tick) end
        end
        local drawn = {}
        local rdrawn = {}
        for _, m in ipairs(L.marks) do
          if m.key == "rt" then
            C.rkDraw(m)
            rdrawn[m.id] = true
          elseif m.key == "path" then
            C.paDraw(m, C.pa_)                                              -- cp36: the path marker, its own look
            drawn.path = true
          else
            local o, spec = C.mk[m.key], C.MARK[m.key]
            drawn[m.key] = true
            o.sh:SetPoint("CENTER", f, "TOP", u(m.x), -u(S.line)); show(o.sh)
            o.tx:SetPoint("CENTER", f, "TOP", u(m.x), -u(S.line)); show(o.tx)
            if o.num then
                -- the test27 ask (the user: "instead of the quest Icon, we should use the quest number so its totally clear what its
                -- tracking"): the app's number for it (its place among the watched quests, the app's own count) on the game's POI disc;
                -- no number (an unwatched focus): the "!" as before
                local n = (C.questNo and C.questNo()) or "!"
                if o.atlas ~= C.DISC_TEX then                               -- cp27: our gold disc, the number (or "!") on it
                    o.tx:SetTexture(C.DISC_TEX); o.sh:SetTexture(C.DISC_TEX); o.atlas = C.DISC_TEX
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
        end
        if not drawn.path then C.paHide() end
        for key, o in pairs(C.mk) do
            if not drawn[key] then hide(o.sh); hide(o.tx); hide(o.d); hide(o.a); if o.num then hide(o.num) end end
        end
        for id, o in pairs(C.rk) do
            if not rdrawn[id] then
                for _, p in ipairs(o.all) do hide(p) end
                C.rk[id] = nil
                C.rkFree[#C.rkFree + 1] = o
            end
        end
    end

    -- cp26: a route marker on the band, alpha the shared fade (C.RT), the second a little smaller and dimmer. cp27: a checkpoint
    -- (and the player's own pin) our gold pin, as in the world; the objective our gold disc with the quest's number (a "!" with none)
    C.rk, C.rkFree = {}, {}
    function C.rkGet(id)
        local o = C.rk[id]
        if o then return o end
        o = table.remove(C.rkFree)
        if not o then
            local f, u = C.frame, C.u
            o = {}
            o.psh = f:CreateTexture(nil, "ARTWORK", nil, 1); o.psh:SetTexture(C.PIN_TEX)
            o.psh:SetVertexColor(0, 0, 0, 0.85)
            o.pin = f:CreateTexture(nil, "ARTWORK", nil, 3); o.pin:SetTexture(C.PIN_TEX)
            o.pin:SetVertexColor(C.GOLD[1], C.GOLD[2], C.GOLD[3])
            o.ish = f:CreateTexture(nil, "ARTWORK", nil, 1); o.ish:SetTexture(C.DIA_TEX); o.ish:SetVertexColor(0, 0, 0, 0.85)
            o.dia = f:CreateTexture(nil, "ARTWORK", nil, 3); o.dia:SetTexture(C.DIA_TEX)
            o.dia:SetVertexColor(C.GOLD[1], C.GOLD[2], C.GOLD[3])
            o.dsh = f:CreateTexture(nil, "ARTWORK", nil, 4); o.dsh:SetTexture(C.DISC_TEX); o.dsh:SetVertexColor(0, 0, 0, 0.6)
            o.disc = f:CreateTexture(nil, "ARTWORK", nil, 5); o.disc:SetTexture(C.DISC_TEX)
            o.num = f:CreateFontString(nil, "OVERLAY", nil, 7)
            WC.font(o.num, u(15), "")
            o.num:SetTextColor(C.NUM_RGB[1], C.NUM_RGB[2], C.NUM_RGB[3], 1)
            o.d = f:CreateFontString(nil, "OVERLAY")
            WC.font(o.d, u(C.SPEC.dist), "OUTLINE")
            o.d:SetTextColor(1, 1, 1, 1)
            o.ar = f:CreateTexture(nil, "ARTWORK", nil, 3); pcall(function() o.ar:SetAtlas("Navigation-Tracked-Arrow") end)
            o.ar:SetSize(u(14), u(14))
            o.all = { o.psh, o.pin, o.ish, o.dia, o.dsh, o.disc, o.num, o.d, o.ar }
        end
        C.rk[id] = o
        return o
    end
    function C.rkDraw(m)
        local f, u, S = C.frame, C.u, C.SPEC
        local st = m.st
        local o = C.rkGet(m.id)
        local scale, af = C.rtLook(st)
        local a = st.a * af
        local n = C.questNo and C.questNo()
        local dw = (st.kind == "goal") and 1 or 0                            -- cp27: the disc is the objective's only
        n = n or (dw == 1 and "!") or nil
        local ps, ds = u(30 * scale), u(28 * scale)
        o.psh:SetSize(ps + u(3), ps + u(3)); o.pin:SetSize(ps, ps)
        o.dsh:SetSize(ds + u(3), ds + u(3)); o.disc:SetSize(ds, ds)
        o.ish:SetSize(ps + u(3), ps + u(3)); o.dia:SetSize(ps, ps)
        local x, y = u(m.x), -u(S.line)
        for _, p in ipairs({ o.psh, o.pin, o.ish, o.dia, o.dsh, o.disc, o.num }) do p:SetPoint("CENTER", f, "TOP", x, y) end
        -- cp32: a checkpoint's diamond share by its eased rank (1 the current, 0 the next)
        local iw = (st.kind == "cp") and math.max(0, math.min(1, 2 - (st.r or 1))) or 0
        local pa, da, ia = a * (1 - dw) * (1 - iw), a * dw, a * (1 - dw) * iw
        o.pin:SetAlpha(pa); o.psh:SetAlpha(pa); o.disc:SetAlpha(da); o.dsh:SetAlpha(da); o.dia:SetAlpha(ia); o.ish:SetAlpha(ia)
        if pa > 0 then show(o.pin); show(o.psh) else hide(o.pin); hide(o.psh) end
        if ia > 0 then show(o.dia); show(o.ish) else hide(o.dia); hide(o.ish) end
        if da > 0 then show(o.disc); show(o.dsh) else hide(o.disc); hide(o.dsh) end
        if n and da > 0 then
            if o.numText ~= n then o.num:SetText(tostring(n)); o.numText = n end
            o.num:SetAlpha(da); show(o.num)
        else
            hide(o.num)
        end
        if o.text ~= m.dist then o.d:SetText(m.dist); o.text = m.dist end
        o.d:SetAlpha(a)
        o.d:SetPoint("TOP", f, "TOP", x, -u(S.line + 16 * scale + 3))
        if a > 0 then show(o.d) else hide(o.d) end
        if m.off and a > 0 then
            if o.side ~= m.side then pcall(function() o.ar:SetRotation(m.side > 0 and -PI / 2 or PI / 2) end); o.side = m.side end
            o.ar:SetAlpha(a)
            o.ar:SetPoint("CENTER", f, "TOP", u(m.x + m.side * (16 * scale + 10)), -u(S.line)); show(o.ar)
        else
            hide(o.ar)
        end
        o.st, o.kind = st, st.kind
    end

    -- the goal marker's id: its quest's, or "goal:ps" for a Polestar step with no quest (cp35: it keeps its identity)
    function C.goalId()
        if C.qsrc == "polestar" and not C.qid then return "goal:ps" end
        return "goal:" .. tostring(C.qid)
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
        if C.qsrc == "polestar" then focus = C.qid end                     -- (cp35: the Polestar step's quest; none for an accept step)
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
            ps = C.psHold,                                                                          -- cp37: the Polestar row holds
            psd = C.psd,                                                         -- cp38: what Polestar's goal stood on (C.targets)
            rt1 = (C.rt_ and C.rt_[1]) and (tostring(C.rt_[1].id) .. ":" .. tostring(C.rt_[1].why)) or nil,     -- cp38: the list's first
            pa = C.paWhy, pai = C.PA.st.idx, pal = C.PA.st.left and math.floor(C.PA.st.left + 0.5) or nil,     -- cp36
            rt = (function()
                local p = {}
                for _, it in ipairs(C.RT.pair or {}) do
                    local st = C.RT.st[it.id]
                    p[#p + 1] = it.id .. "@" .. (st and math.floor(st.a * 100 + 0.5) or 0) .. ":" .. tostring(it.why)
                end
                return #p > 0 and table.concat(p, ",") or nil
            end)(), rtn = C.rt_ and #C.rt_ or 0, rtbad = (#C.RT.bad > 0) and table.concat(C.RT.bad, ",") or nil,
            cam = C.camWanted(), csrc = C.cam.src, coff = math.floor((C.cam.off or 0) * 1000 + 0.5) / 1000,
            cyaw = C.cam.abs and math.floor(C.cam.abs * 1000 + 0.5) / 1000 or nil,
            wm = C.wm and C.wm.shown and true or false, wmn = C.wm and C.wm.n or 0, wmon = C.wmWanted and C.wmWanted() or false, air = C.wm and C.wm.air or nil,
            pitch = (C.wm and C.wm.pitch) and math.floor(math.deg(C.wm.pitch) * 10 + 0.5) / 10 or nil,
            pAge = (C.wm and C.wm.pitchAt) and math.floor(((type(GetTime) == "function" and GetTime()) or 0) - C.wm.pitchAt) or nil,
            tilt = C.wm and C.wm.tilted or nil, zoom = C.wmZoom and math.floor(C.wmZoom() * 10 + 0.5) / 10 or nil,
            pdz = C.wm and C.wm.pinDz or nil,
            more = type(WC.routeWp) == "table" and type(WC.routeWp.more) == "table" and #WC.routeWp.more or 0,
            prd = (type(WC.prd) == "table" and WC.prd.f) and { a = math.floor((WC.prd.alpha or 0) * 100 + 0.5), why = WC.prd.why,
                src = WC.prd.src, y = WC.prd.y, nb = WC.prd.nb, nd = WC.prd.nd, pt = WC.prd.ptok, cbad = WC.prd.curveBad, ab = WC.prd.ab, ex = WC.prd.ex, w = WC.prd.wd, s = WC.prd.sc, sel = WC.prd.sel, prest = WC.prd.prest, pet = WC.prd.pex } or nil, tol = C.cam.tol and math.floor(math.deg(C.cam.tol) + 0.5) or nil,
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
        local w = (okP and onPin == true) and tg.pin or tg.stq
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
-- ag13 THE 1.0.33 TEST BUILD MERGE (code 1475): fix/t16-singles (#358) and feat/wk-npf both tagged ag12, both kept (the font
-- family = ag12f, Friendly plates Off for the session = ag12n).
-- ag12f ISSUE #358 (a player: "Cyrillic characters are displayed as empty squares instead of actual letters"; the user's pick,
-- 2026-11-16: the font family, "like the game's own frames"): the pull pick's step number (A.iconFor) takes cp41's WC.font
-- instead of SetFont(FRIZQT__); the same look in Latin.
-- ag12n (the user, 2026-11-16: "Sometimes while playing the friendly name plates will turn themselves back on even though they are
-- turned off in the menu."): Friendly plates Off stays off for the session; only the player's own change through the game's own
-- controls stands (A.friendsOffStep below).
-- ag14 THE CLASSIFICATION DRAGONS (the user, 2026-11-17: "the Skull above mobs is confusing, I think we need to scrap that, we do however
-- need to add the dragon Icon for an Elite and a silver one for rare spawns"): ag2's AVOID skull (dungeonskull) over a plate is gone,
-- and with it its rule (elite, "??", ours + 3, a fight not on us). In its place the game's own plate dragons by UnitClassification
-- (plain): elite / worldboss nameplates-icon-elite-gold, rare / rareelite nameplates-icon-elite-silver (Blizzard_NamePlates'
-- GetClassificationAtlasElement gives a rareelite the silver dragon; a rare its star, but the Classic target frame's
-- ShouldUseSilverUnitFrame puts every rare in silver, and the user asked for silver; this game's plates draw no rarity icon at all:
-- camelot NameplatesOverrides.ShowRarityIconInfoDisplayOption). As the game's classification frame, none while the unit wears a raid
-- marker (GetRaidTargetIndex). The dragon and the quest '!' side by side when both show. A.tgtIcons = { cl, q, tok }.
WC.live = { V = "ag14", MAX = 24, REUSE_S = 10, SCAN_S = 0.2, FULL_S = 5, STILL_S = 1, slot = {}, byTok = {}, toks = {}, freeAt = {},
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
    -- ---------------------------------------------------------------- FRIENDLY NAMEPLATES WHILE A FRIEND IS TARGETED (the user, 2026-11-07: the
    -- target radial's Y glyph "on the right side of my screen instead of over my target" -> "Turn on friendly nameplates", then "Is it
    -- possible to only show friendly name plates when I am targeting someone?"): the game gives an add-on a unit's place on screen only
    -- through its nameplate, and friendly players / NPCs have none unless the game's friendly nameplates are on. So, out of combat (the
    -- nameplate options are refused in a fight): a FRIENDLY target -> nameplateShowFriends / nameplateShowFriendlyNPCs 1, the player's
    -- own values kept in OUR saved data (WowCompanionDB.npFriends.orig); no friendly target -> put back. No option shows only the
    -- target's plate, so (the user, same day: "I was meaning a name plate for ONLY my target") nameplateNotSelectedAlpha goes to 0 with
    -- them: every plate but the target's is drawn invisible (still there: our Y glyph and the layers keep their anchors). Put back at log
    -- out too. A value of theirs stands.
    -- the user on test31: "there's no name plates on players": this client has no nameplateShowFriends; its friendly PLAYERS' option is
    -- nameplateShowFriendlyPlayers (the game's own strings). An option the client does not have reads nil and is skipped.
    A.NP_FRIEND_CVARS = { { "nameplateShowFriendlyPlayers", "1" }, { "nameplateShowFriends", "1" }, { "nameplateShowFriendlyNPCs", "1" },
                          { "nameplateNotSelectedAlpha", "0" } }
    local function cvGet(name)
        local ok, v = pcall(function() return C_CVar.GetCVar(name) end)
        if ok and v ~= nil and not (type(issecretvalue) == "function" and issecretvalue(v)) then return tostring(v) end
    end
    local function cvSet(name, v)
        return pcall(function()
            if C_CVar and C_CVar.SetCVar then C_CVar.SetCVar(name, v) else SetCVar(name, v) end    -- npFriends (the user's ruling)
        end)
    end
    function A.friendsRestore(why)
        local saved = type(WowCompanionDB) == "table" and WowCompanionDB.npFriends or nil
        if type(saved) ~= "table" then return end
        -- "combat": PLAYER_REGEN_DISABLED, the last moment before the fight's lock (the user, 2026-11-08: "the enemy plates turned off on
        -- me": a fight begun with a friend targeted kept nameplateNotSelectedAlpha 0, every enemy plate but the target's invisible)
        if pv(InCombatLockdown) == true and why ~= "logout" and why ~= "combat" then return end
        for _, cv in ipairs(A.NP_FRIEND_CVARS) do
            local name, want = cv[1], cv[2]
            local orig = (saved.orig or {})[name]
            if orig ~= nil and tonumber(cvGet(name)) == tonumber(want) and tonumber(orig) ~= tonumber(want) then cvSet(name, orig) end
        end
        WowCompanionDB.npFriends = nil
    end
    -- ag9 FRIENDLY PLATES OFF AGAIN (the user, 2026-11-13: "I dont like the friendly Healthcare plates I want to turn those back off even
    -- if it means losing our floating Y glyph"): a friendly target no longer switches anything on. What is left is the put-back: a
    -- player who had them on from ag2..ag8 (WowCompanionDB.npFriends: the values ours replaced, test30's npFriendsSet too) gets the
    -- own values back at the first moment out of a fight, and never again after. PadMap's Y glyph over a friend falls back to its
    -- other places (beside the target bar / frame) with no plate to stand on.
    -- ag10 FRIENDLY PLATES, A SWITCH (the user, 2026-11-13: "friendly plates off ... maybe we should set this as a toggle defaulted to
    -- off"): Hub's "uisw" NPF_OFF / NPF_ON (Settings > Top > Target, "Friendly plates", per character, Off by default) -> A.friendsSet.
    -- Off (the default, and with an app that never sends it) = ag9 above: nothing switched on, a value of ours put back once out of a
    -- fight. On = the behaviour before ag9 (ag2..ag8): out of a fight a FRIENDLY target turns A.NP_FRIEND_CVARS on (the player's own
    -- values kept in WowCompanionDB.npFriends.orig), no friendly target puts them back; a fight and log out put them back too (the
    -- events below). Turning it Off puts ours back at the first moment out of a fight.
    A.FRIENDS_ON = false
    function A.friendsSet(on)
        on = on == true
        A.friendsChosen = true
        if on == A.FRIENDS_ON then return end
        A.FRIENDS_ON = on
        A.friendWas = nil                       -- re-decided on the next step
        A.offDeclined = {}                      -- ag11: a new choice: the player's own on (declined) is re-decided too
        A.offSeen, A.offPlayer, A.offAt = {}, {}, {}   -- ag12: the next reading is the game's value again (a press before it: re-decided)
    end
    -- ag11 OFF HIDES THEM (the user on build 1462: "friendly name plates still show with the setting off"). THE CAUSE: ag9 / ag10's Off
    -- only stopped US turning the friendly plates on; it never turned off what was already on in the player's game - the game's own
    -- Friendly Player / Friendly NPC nameplate options (on in the game's menu or its FRIENDNAMEPLATES key, or a 1 an older build of
    -- ours left behind whose saved record a killed game never wrote: SavedVariables are written only at a clean log out / reload).
    -- Off now MEANS off while DS Forever runs: out of a fight (the options are refused in one; the next moment out of it otherwise)
    -- every friendly-plate option in A.NP_FRIEND_OFF that is not 0 goes to 0, the player's own value kept in OUR saved data
    -- (WowCompanionDB.npFriendsOff.orig, the npFriends / npEnemies / error-sound pattern). Given back exactly when: the switch goes On
    -- (before On's own rule runs), and at log out (a disabled add-on or a closed game leaves the game as it was). A change of the
    -- player's own in the game meanwhile (an option of ours back on) stands for this session: dropped from the record, not fought.
    -- An option the client does not have reads nil and is skipped. Fights keep the 0 (nothing is put back at PLAYER_REGEN_DISABLED).
    -- ag12 OFF STAYS OFF (the user, 2026-11-16: "Sometimes while playing the friendly name plates will turn themselves back on even
    -- though they are turned off in the menu."). THE CAUSE: ag11 took EVERY 1 found where ours was 0 for the player's own choice and
    -- gave Off up for the rest of the session (offDeclined + the record dropped), but it could not tell who wrote that 1, and the game
    -- writes it too: the Off step ran from the first frame of the load (this file's ticker), before the game had put the character's
    -- own saved options in place (C_CVar.AreCVarsLoaded; the game's own UI waits for VARIABLES_LOADED before it trusts a CVar), so the
    -- game's load wrote the player's stored 1 over our 0 and ag11 read that as "the player turned it back on" - friendly plates for the
    -- whole session, on the logins where the load landed after our first step (a /reload finds the options loaded: so "sometimes").
    -- Any other writer that is not the player (another add-on, a game write we do not know of) did the same. NOW:
    --   - the Off step waits for the game's options: VARIABLES_LOADED seen (PLAYER_ENTERING_WORLD + CV_GRACE_S without it) and
    --     C_CVar.AreCVarsLoaded() not false; nothing is read as the player's value or written before;
    --   - the PLAYER's own change is the one the game's own controls make: the Interface options' Friendly Players / Friendly NPCs
    --     boxes (setting:SetValue(v)), their Defaults (setting:SetValueToDefault()) and the game's FRIENDNAMEPLATES / ALLNAMEPLATES
    --     keys (Settings.SetValue(var, v) -> setting:SetValue(v)), all on the game's own setting OBJECT for the option
    --     (Blizzard_Settings_Shared, Settings.GetSetting; the game registers it after VARIABLES_LOADED + PLAYER_ENTERING_WORLD). Off
    --     post-hooks those two methods on the two objects (hooksecurefunc: the game's call runs first and as it was, ours only sets a
    --     flag; read only) and only such a call marks a change as the player's. NOT the value-changed event
    --     (Settings.SetOnValueChangedCallback, ag12's first cut): every script-API / console CVar write fires CVAR_UPDATE, and
    --     SettingsPanel:OnCVarChanged answers it with setting:SetValue(value, true) - the same event - so an add-on's SetCVar (ours
    --     too) read as the player's; that forced call (force true) is the one SetValue we do not count. A key press counts: it is the
    --     game's own control, the game prints its own message for it (NAMEPLATES_MESSAGE_FRIENDLY_ON / _OFF), and a second press turns
    --     them off again. Their on stands until the next load (as ag11); their off hands it back to Off;
    --   - any other change (none of ours, no such call) is NOT the player's: Off puts the 0 back out of a fight (at most once each
    --     OFF_MIN_S, so a writer that keeps fighting is not raced) and the record keeps the game's value to give back at log out;
    --   - Off waits (up to OFF_HOOK_N steps) for the game's setting objects; an option the client has no object for keeps ag11's
    --     reading (a change after ours is the player's), as does a client without Settings / hooksecurefunc.
    A.NP_FRIEND_OFF = { "nameplateShowFriendlyPlayers", "nameplateShowFriends", "nameplateShowFriendlyNPCs" }
    -- the game's Settings variables (case matters there, not in a CVar name) -> our option names
    A.NP_FRIEND_SETTINGS = { nameplateShowFriendlyPlayers = "nameplateShowFriendlyPlayers", nameplateShowFriends = "nameplateShowFriends",
                             nameplateShowFriendlyNpcs = "nameplateShowFriendlyNPCs", nameplateShowFriendlyNPCs = "nameplateShowFriendlyNPCs" }
    A.CV_GRACE_S = 3
    A.OFF_MIN_S = 1
    A.OFF_HOOK_N = 50                           -- steps (SCAN_S each, out of a fight) to find the game's setting objects
    A.offDeclined = {}
    A.offSeen = {}                              -- ag12: each option as Off last read / wrote it (nil: the next reading is the game's)
    A.offPlayer = {}                            -- ag12: the game's own controls changed it since the last step
    A.offAt = {}                                -- ag12: Off's last write of each
    A.offFix = 0                                -- ag12: 0s put back after one of ours (a change that was not the player's; the probe line)
    A.offHooked = {}                            -- ag12: our option name -> its game setting object is hooked (never unhooked)
    A.offHookN = 0
    function A.cvReady()
        if not (A.varsLoaded or (A.pewAt ~= nil and now() - A.pewAt >= A.CV_GRACE_S)) then return false end
        if type(C_CVar) == "table" and type(C_CVar.AreCVarsLoaded) == "function" and pv(C_CVar.AreCVarsLoaded) == false then return false end
        return true
    end
    -- true once every option the client has is hooked, or the looking is over (no Settings / hooksecurefunc, or OFF_HOOK_N steps)
    function A.offHookTry()
        if A.offHookDone then return true end
        local api = type(Settings) == "table" and type(Settings.GetSetting) == "function" and type(hooksecurefunc) == "function"
        local want = false
        if api then
            for variable, name in pairs(A.NP_FRIEND_SETTINGS) do
                if not A.offHooked[name] and cvGet(name) ~= nil then
                    local ok, setting = pcall(Settings.GetSetting, variable)
                    if ok and type(setting) == "table" and type(setting.SetValue) == "function" then
                        -- flags only: the step reads the option itself, out of a fight. force true = SettingsPanel:OnCVarChanged
                        -- answering a CVar write that did not come through the setting (an add-on's, ours, the console)
                        if pcall(hooksecurefunc, setting, "SetValue", function(_, _, force)
                            if force ~= true then A.offPlayer[name] = true end
                        end) then
                            A.offHooked[name] = true
                            if type(setting.SetValueToDefault) == "function" then
                                pcall(hooksecurefunc, setting, "SetValueToDefault", function() A.offPlayer[name] = true end)
                            end
                        end
                    end
                end
            end
            for _, name in ipairs(A.NP_FRIEND_OFF) do
                if not A.offHooked[name] and cvGet(name) ~= nil then want = true end
            end
        end
        A.offHookN = A.offHookN + 1
        if not want or A.offHookN >= A.OFF_HOOK_N then A.offHookDone = true end
        A.offHook = next(A.offHooked) ~= nil
        return A.offHookDone == true
    end
    function A.friendsOffRestore(why)
        local saved = type(WowCompanionDB) == "table" and WowCompanionDB.npFriendsOff or nil
        if type(saved) ~= "table" then return end
        if pv(InCombatLockdown) == true and why ~= "logout" then return end
        -- a write the game refuses (a /reload in a fight) keeps its entry: given back at the next chance (On, the next log out)
        local left = {}
        for name, orig in pairs(saved.orig or {}) do
            local cur = cvGet(name)
            if cur ~= nil and tonumber(cur) == 0 and tonumber(orig) ~= 0 then
                cvSet(name, orig)
                if tonumber(cvGet(name)) == 0 then left[name] = orig end
            end
        end
        WowCompanionDB.npFriendsOff = next(left) ~= nil and { orig = left } or nil
        A.offWhy = why
        A.offSeen, A.offAt = {}, {}             -- ag12: the values given back are the game's: the next reading is the game's value
    end
    function A.friendsOffStep()
        if pv(InCombatLockdown) == true then return end
        -- ag12: not before the game's own saved options are in place (its load would write over our 0)
        if not A.cvReady() then A.offWait = true; return end
        -- ag12: nor before the game's setting objects are hooked (the player's own change could not be told from another's)
        if not A.offHookTry() then A.offWait = true; return end
        A.offWait = nil
        local saved = WowCompanionDB.npFriendsOff
        if type(saved) == "table" and type(saved.orig) ~= "table" then saved.orig = {} end
        local t = now()
        for _, name in ipairs(A.NP_FRIEND_OFF) do
            local cur = cvGet(name)
            local player = A.offPlayer[name] == true
            A.offPlayer[name] = nil
            if cur ~= nil then
                local seen = A.offSeen[name]
                local on = tonumber(cur) ~= 0
                if seen == nil or tonumber(seen) ~= tonumber(cur) then
                    -- a change that is not a write of ours, or the first reading of this load: the game's value now
                    if player or (not A.offHooked[name] and seen ~= nil) then
                        -- the player's own (the game's options / key; without its setting object, ag11's reading): an on stands
                        -- for this session, an off gives the option back to Off
                        A.offDeclined[name] = on or nil
                    end
                    local keep = seen == nil and not on and type(saved) == "table" and saved.orig[name] ~= nil
                    if not keep then
                        -- keep: a first reading of a 0 of ours left from before (a refused give-back); else the record holds the
                        -- game's value exactly while Off holds a 0 over it
                        if on and not A.offDeclined[name] then
                            saved = saved or { orig = {} }
                            saved.orig[name] = cur
                            WowCompanionDB.npFriendsOff = saved
                        elseif type(saved) == "table" then
                            saved.orig[name] = nil
                        end
                    end
                end
                if on and not A.offDeclined[name] and not (A.offAt[name] ~= nil and t - A.offAt[name] < A.OFF_MIN_S) then
                    if A.offAt[name] ~= nil then A.offFix = A.offFix + 1 end    -- a put-back after a 0 of ours
                    A.offAt[name] = t
                    cvSet(name, "0")
                end
                A.offSeen[name] = cvGet(name) or cur
            end
        end
        if type(saved) == "table" and next(saved.orig) == nil then WowCompanionDB.npFriendsOff = nil end
    end
    -- ag12: the probe line's word on Off (plain values only)
    function A.npfDiag()
        local dec = 0
        for _ in pairs(A.offDeclined) do dec = dec + 1 end
        return { on = A.FRIENDS_ON == true, hook = A.offHook == true, wait = A.offWait == true or nil, fix = A.offFix,
                 dec = dec > 0 and dec or nil, pl = cvGet("nameplateShowFriendlyPlayers"), npc = cvGet("nameplateShowFriendlyNPCs") }
    end
    function A.friendsStep()
        if type(WowCompanionDB) ~= "table" then WowCompanionDB = {} end
        -- test30 set them on once (npFriendsSet): that on is ours to put back (off)
        if WowCompanionDB.npFriendsSet then
            WowCompanionDB.npFriendsSet = nil
            WowCompanionDB.npFriends = WowCompanionDB.npFriends or { orig = { nameplateShowFriends = "0", nameplateShowFriendlyNPCs = "0" } }
        end
        if not A.FRIENDS_ON then
            A.friendWas = nil
            if pv(InCombatLockdown) == true then return end
            -- ON's values of ours first (the player's own back), then ag11: whatever is still on goes off
            if type(WowCompanionDB.npFriends) == "table" then A.friendsRestore("friendly plates off") end
            A.friendsOffStep()
            return
        end
        -- ag10 On: the ag8 rule, as it was
        if pv(InCombatLockdown) == true then return end
        -- ag11: Off's 0s go back to the player's own values first (On starts from the game as the player left it)
        if type(WowCompanionDB.npFriendsOff) == "table" then A.friendsOffRestore("friendly plates on") end
        local friend = pv(function() return UnitExists("target") end) == true and pv(function() return UnitIsFriend("player", "target") end) == true
            and pv(function() return UnitIsUnit("target", "player") end) ~= true
        if friend == A.friendWas then return end
        A.friendWas = friend
        if friend then
            local saved = WowCompanionDB.npFriends
            for _, cv in ipairs(A.NP_FRIEND_CVARS) do
                local name, want = cv[1], cv[2]
                local cur = cvGet(name)
                if cur and tonumber(cur) ~= tonumber(want) then
                    saved = saved or { orig = {} }
                    saved.orig = saved.orig or {}
                    if saved.orig[name] == nil then saved.orig[name] = cur end
                    cvSet(name, want)
                end
            end
            WowCompanionDB.npFriends = saved
        else
            A.friendsRestore("no friendly target")
        end
    end

    function A.npStep()
        local t = now()
        -- ag4: or HUD mode (Hub hub14, the user's "Yes, force them on"): ONE owner here, two reasons (WC.npWant.hud); restored only when
        -- neither wants them
        -- hub17: the enemy nameplates' own switch (npWant.np) once set, else HUD mode's bundle (npWant.hud)
        local npw = type(WC.npWant) == "table" and WC.npWant or {}
        local npOn = npw.np
        if npOn == nil then npOn = npw.hud == true end
        local want = A.routing() or npOn == true
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
    --   ag14: the CLASSIFICATION DRAGON (the game's nameplates-icon-elite-gold / -silver, A.classOf) in place of ag2's AVOID skull;
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
        -- ag14: the dragon (its atlas set per mob in A.iconsStep), square as the game's 20 x 20 ClassificationFrame; placed with the
        -- '!' by A.iconsPlace
        local cl = f:CreateTexture(nil, "ARTWORK")
        cl:SetSize(u(28), u(28))
        cl:Hide()
        local q = f:CreateTexture(nil, "ARTWORK")
        q:SetSize(u(30), u(30))
        pcall(function() q:SetAtlas("smallquestbang") end)
        -- ag3: the app's pull pick (the signed-off F spec: TargetCrosshairs 44 px, its step number at its top right)
        local pk = f:CreateTexture(nil, "ARTWORK", nil, 2)
        pk:SetSize(u(44), u(44))
        pk:SetPoint("BOTTOM", f, "BOTTOM", 0, 0)
        pcall(function() pk:SetAtlas("TargetCrosshairs") end)
        pk:Hide()
        local pn = f:CreateFontString(nil, "OVERLAY")
        pcall(function() WC.font(pn, u(13), "OUTLINE") end)               -- ag12 (#358): the family (cp41 WC.font)
        pn:SetPoint("BOTTOMLEFT", pk, "TOPRIGHT", -u(8), -u(10))
        pcall(function() pn:SetTextColor(1, 0.82, 0, 1) end)
        pn:Hide()
        o = { f = f, cl = cl, q = q, pk = pk, pn = pn }
        A.icon[s] = o
        return o
    end
    -- ag14: the mob's dragon by its classification (m.c, from UnitClassification): elite / worldboss gold, rare / rareelite silver
    A.DRAGON = { e = "nameplates-icon-elite-gold", b = "nameplates-icon-elite-gold", r = "nameplates-icon-elite-silver",
                 R = "nameplates-icon-elite-silver" }
    function A.classOf(m, tok)
        local a = m and A.DRAGON[m.c or ""]
        if not a then return nil end
        -- as the game's own classification frame: none while the unit wears a raid marker
        if tok and type(GetRaidTargetIndex) == "function" and type(pv(GetRaidTargetIndex, tok)) == "number" then return nil end
        return a
    end
    -- ag14: the dragon and the '!' side by side when both show, each centred when alone (ag2's skull and '!' sat on top of each other)
    function A.iconsPlace(o, cl, q)
        local u = (C and C.u) or function(px) return px * 768 / 1080 end
        local k = (cl and 1 or 0) + (q and 2 or 0)
        if o.place == k then return end
        o.place = k
        o.cl:ClearAllPoints(); o.q:ClearAllPoints()
        if k == 3 then
            o.cl:SetPoint("RIGHT", o.f, "CENTER", -u(1), 0)
            o.q:SetPoint("LEFT", o.f, "CENTER", u(1), 0)
        else
            o.cl:SetPoint("CENTER", o.f, "CENTER", 0, 0)
            o.q:SetPoint("CENTER", o.f, "CENTER", 0, 0)
        end
    end
    -- ag9 (the user, 2026-11-13: "the skull and !should be moving to the top as well"): what the TARGET's plate wears is kept for Hub's
    -- target bar (A.tgtIcons = { cl, q, tok }; ag14: cl the dragon's atlas, Hub draws its own from the target's classification), and
    -- while Hub shows that target (WC.uf.ownsPlate: the plate itself hidden for it) the plate's icons are at alpha 0 - on the bar now
    function A.iconsStep(list)
        local want = {}
        local tgt = nil
        for _, m in ipairs(list or {}) do
            if not m.dd and A.slot[m.s] then
                local tok = A.slot[m.s].tok
                local cl, qq = A.classOf(m, tok), m.qi == 1 or A.qset[m.s] == tok
                local pk = (A.pick[1] and A.pick[1].s == m.s and 1) or (A.pick[2] and A.pick[2].s == m.s and 2) or nil
                if cl or qq or pk then want[m.s] = { cl = (not pk) and cl or nil, q = qq and not pk, pk = pk, tok = tok } end
                if (cl or qq) and pv(function() return UnitIsUnit(tok, "target") end) == true then tgt = { cl = cl, q = qq, tok = tok } end
            end
        end
        A.tgtIcons = tgt
        local uf = WC.uf
        local owned = tgt and type(uf) == "table" and type(uf.ownsPlate) == "function" and pv(uf.ownsPlate) == true
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
                if w.cl then
                    if o.clA ~= w.cl then o.clA = w.cl; pcall(function() o.cl:SetAtlas(w.cl) end) end
                    o.cl:Show()
                else o.cl:Hide() end
                if w.q then o.q:Show() else o.q:Hide() end
                A.iconsPlace(o, w.cl ~= nil, w.q == true)
                if w.pk then o.pk:Show(); o.pn:SetText(tostring(w.pk)); o.pn:Show() else o.pk:Hide(); o.pn:Hide() end
                local a = (owned and tgt.tok == w.tok) and 0 or 1
                if o.alpha ~= a then o.f:SetAlpha(a); o.alpha = a end
                if not o.f:IsShown() then o.f:Show() end
                A.iconN = A.iconN + 1
            end
        end
    end
    function A.iconsClear()
        for _, o in pairs(A.icon) do o.f:Hide(); o.tok = nil end
        A.iconN = 0
        A.tgtIcons = nil
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
                       npf = A.npfDiag(),
                       route = A.routing(), pin = type(WC.routeWp) == "table" and WC.routeWp.active == true,
                       rings = type(WC.rings) == "table" and { v = WC.rings.V, on = WC.rings.wanted(), n = WC.rings.n,
                                                                   why = WC.rings.whyLine and WC.rings.whyLine() or nil } or nil,
                       pulls = A.pullN, pullRej = A.pullRej }
        A.lastDiag = line
        pcall(function() WC.send(WC.encode(line)) end)
    end

    -- ---------------------------------------------------------------- events and the ticker
    local ev = CreateFrame("Frame")
    for _, e in ipairs({ "PLAYER_ENTERING_WORLD", "VARIABLES_LOADED", "NAME_PLATE_UNIT_ADDED", "NAME_PLATE_UNIT_REMOVED", "SPELLS_CHANGED",
                         "ITEM_DATA_LOAD_RESULT", "CVAR_UPDATE", "ENCOUNTER_START", "ENCOUNTER_END", "UNIT_SPELLCAST_SENT",
                         "PLAYER_ENTER_COMBAT", "START_AUTOREPEAT_SPELL", "UNIT_THREAT_LIST_UPDATE", "PLAYER_LOGOUT", "PLAYER_REGEN_DISABLED" }) do
        pcall(function() ev:RegisterEvent(e) end)
    end
    ev:SetScript("OnEvent", function(_, e, a1)
        local ok, err = pcall(function()
            if e == "NAME_PLATE_UNIT_ADDED" then A.onAdded(a1)
            elseif e == "NAME_PLATE_UNIT_REMOVED" then A.onRemoved(a1)
            elseif e == "PLAYER_LOGOUT" then A.npRestore("logout"); pcall(A.friendsRestore, "logout"); pcall(A.friendsOffRestore, "logout")
            elseif e == "PLAYER_REGEN_DISABLED" then
                -- a fight: the player's plate values back now (no friendly-target change sticks through a fight); re-decided after it
                pcall(A.friendsRestore, "combat"); A.friendWas = nil
            elseif e == "VARIABLES_LOADED" then
                A.varsLoaded = true                 -- ag12: the game's own saved options are in place (Off may read / write them)
            elseif e == "PLAYER_ENTERING_WORLD" then
                A.pewAt = A.pewAt or now()          -- ag12: Off's fallback wait when VARIABLES_LOADED never comes
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
            A.friendsStep()
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
        local w = (okP and onPin == true) and tg.pin or tg.stq
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
-- prd9 (the user's new defaults, for anyone who never changed them - a saved value stays): the height Y_DEF -350 (was -300), the
-- width W_DEF 280 (was 260); the scale 100 % as before
-- prd10 (issue #120, a Rogue: "I can't see the combo points that are accumulated during combat"; 2026-11-11): a Rogue's (and a cat's)
-- combo points as a thin pip strip right under the power bar (CPH high, CP_GAP below it, inside the aura rows' ROW_GAP), the read fed
-- straight to the bar like every value here; on the player's power events (UNIT_POWER_FREQUENT too) at the next frame
-- prd11 PET HEALTH IN THE HUD (the user, 2026-11-11: "find a way to include Pet HP in the HUD, and better distinguish the player HUD vs
-- Pet HUD"; their pick, mock-up A "Sub-strip under"): under the player's bars (and under the combo pips when they show) a thinner,
-- shorter strip, PET_W of the bar's width: a round portrait at its left (GetPetIcon's plain fileDataID, else the game's own portrait
-- setter), the pet's health in GOLD (never the player's green) PET_HH high and its power PET_PH high (FOCUS for a hunter's pet, MANA for a
-- demon: P.POWER by the pet's own token), the pet's name in small text, the happiness face only while C_PetInfo.GetPetHappiness is plain.
--   The strip is its own LAYER (P.petL), a child of P.f beside the player's P.g: the player's curve never hides it. Out of combat on the
--   curve route its alpha is UnitHealthPercent("pet") through the same step curve, with its OWN bad flag (P.petCurveBad: a refused pet
--   call never turns the player's curve off); without the curve it fades with the HUD (no pet model exists to say "hurt").
--   Each bar in the PRD frame cut as the player's (P.frameFor with the strip as its host and its own lip) at the player's texel scale:
--   the rims one thickness on every bar. Fed as the player's: the game's values straight to the setters, nothing read back, no lag chunk.
--   Presence from plain reads only (UnitExists, else a plain UnitName, else HasPetUI); a plainly dead pet greyed. The pet's health and
--   power events are heard with the player's; its casts never are (the cost and the spend are the player's). Its own switch (Hub's
--   "uisw" 32 / 33: WC.npWant.pet, on unless turned off); the game's pet frame stays with the game's player frame (Hub hub33).
--   Review fixes (2026-11-11, before any device run): the pet's curve asked only while a pet plainly exists (a dismissal never latches
--   P.petCurveBad; UNIT_PET hides the strip at once); a refusal with a pet out: the strip on the PLAYER's curve (fades with the HUD);
--   PET_BAR_GAP / PET_GAP = the shared scale's 8-texel bottom lip (15) + 1, so no lip lies over a pet bar's opening (P.petRow).
-- prd12 THE PET'S STRIP REWORKED (the user on the device, 2026-11-11: "The pet hp should be green. It needs to be moved down a bit and
-- the gap between the bars should be minimal as possible. The portrait of the pet is way too small as well as the happiness. The name
-- on the right should be removed"; their pick "Same green as mine"): the pet's health in the player's own HEALTH_RGB; the health and
-- the power ONE framed unit (o.unit: one PRD frame round both, no lip between them, the power PET_BAR_GAP = 1 under the health); the
-- round portrait PET_ICON = 36 (the unit's height and a little), a thin dark rim, left of the unit and centred on its bars; the
-- happiness face PET_HAPPY = 20 past the unit's right rim (never on the portrait); no name; portrait + unit centred under the player's
-- bars; the strip PET_GAP = 15 under the power bar (its top at the player's lip: the bars 10.5 spec px lower than prd11's; P.petGeo).
-- prd13 THE PET'S GREEN MATCHES THE PLAYER'S (the user's pick "Same green as mine", 2026-11-11): P.frameFor's inner shade covered 45 %
-- of its OPENING, and the pet's opening is the whole 13 px unit, so 5.85 of the 8 px health bar lay under it (the player's: 6.75 of 15)
-- and the pet's green read darker; now frameFor takes an optional shade height and the pet's unit passes 0.45 * PET_HH (the player's
-- and the target's bars unchanged). Review nits: the portrait 2 spec px clear of the player's power-bar lip (PET_GAP 17); the
-- happiness face counted in the centring width while it can show (P.petHappyOn), the pet bars narrowed to fit at W_MIN = 180.
-- prd14 PARTY MODE'S PICK ON YOU / YOUR PET (the user, 2026-11-11: "When selecting myself or my pet it just highlights my HUD bar"; the
-- adaptive rows, PadMap m131): while Party mode is on and its pick (WC.padMap.party.box().u, our own attribute: plain) is "player", a
-- gold box (Hub's ring gold 1 / 0.82 / 0, PICK_PAD spec px outside the frame) stands round the player's two bars; on "pet", round the
-- pet's strip (portrait to bars). Hidden whenever Party mode is off. While one of them is the pick the HUD stays shown at full alpha
-- (why "party": the box never frames nothing); the box lives on P.f itself, never on the curve's layer. Read every PICK_S, nothing written.
-- prd15 [ME][PET] IN PARTY MODE (the user, 2026-11-12: "In Party mode the PET strip moves from under the HUD bar to the RIGHT of the
-- player's HP bar"; the members "fade back in UNDER the player's HUD bar"): while Party mode is on (PadMap's on, plain; never in a raid)
-- the pet strip fades out (PET_FADE_S 0.2), moves beside the HP bar (PET_SIDE_GAP past its right rim, centred on the two bars; P.petSide
-- "beside", its portrait from the strip's own left) and fades in; the reverse when it goes off. The pick box rides the strip (it waits
-- for the fade). Hub's party grid hangs under the bars (P.partyGeo: the HP bar's left, the room's top PET_GAP + PARTY_GAP under the
-- power bar, from the HUD's own height / width / scale, never the slide) and says its height (P.partyRoom): that room is P.petDrop then
-- (the shadow stays under the bars). Party mode holds the HUD up (why "party"): it is the block's [me] row (the user, 2026-11-12: "HUD
-- bar ALWAYS shown in Party mode"). The user's answers the same day: in Party mode the player's buff / debuff row moves ABOVE the HUD
-- bars (P.auraUp: its timers AURA_UP clear of the health bar's top rim, a second row above the first), out of it it stays under them;
-- it fades out, moves and fades in with the pet strip (one fade: P.petMoveStep). THE ON-SCREEN GUARD (the user, the same day: "KEEP IT
-- ON SCREEN"): in Party mode, when the block (the bars, the combo pips, Hub's rows and their rings) would end closer than LIFT_MARGIN to
-- the screen's bottom, the HUD (and with it the pet strip, the auras and Hub's rows: P.partyGeo carries it) is lifted by just enough
-- (P.liftFor -> P.lift, added in P.place); re-read every frame (the roster's rows, the scale, the combo pips), applied only at the fade's
-- bottom (the bars fade too while it changes: P.hudFade), never a visible jump; out of Party mode no lift. Our own plain frames and alpha
-- only (allowed in a fight).
-- prd16 (issue #181, a player on the top screen: "Buffs and debuffs wouldn't show on the DS frames in the top screen during combat
-- for both player and target"): in a fight the game's aura reads are SECRET for add-ons (C_UnitAuras.GetAuraDataByIndex /
-- ByAuraInstanceID carry SecretWhenUnitAuraRestricted in this client's own API docs), and the row's reader compared those values
-- (num: v == v before the secret test; the icon cache: o.icon ~= a.icon), which a secret refuses. Now ONE reader for the HUD's row
-- and Hub's target bar (P.auraRead): every value tested with issecretvalue before any compare or index, a secret table never
-- indexed, a secret icon handed straight to SetTexture (it takes one), and when the index loop throws or gives only secret tables,
-- the instance-ID route (GetUnitAuraInstanceIDs, plain in a fight) as Auras.lua reads them. What a fight's reads gave is reported
-- once per kind (probe "aurasec": the app logs it under WCLOG, so the next issue report carries it).
-- prd17 (issue #195, the user 2026-11-12, "Yes, fix them too"): in a fight the aura times are secret and the swipe's
-- SetCooldownFromExpirationTime is AllowedWhenUntainted (an add-on's call with a secret fails: a blank swipe). This client's docs give
-- the aura's DURATION OBJECT (C_UnitAuras.GetAuraDuration(unit, auraInstanceID)) for Cooldown:SetCooldownFromDurationObject, as
-- Blizzard's own aura buttons do: P.auraSweep hands it straight over (never read or compared) for the HUD's row and Hub's target bar;
-- without the functions, or refused, the old route as before. Feature-detected; the route that worked rides one "cap" line (probe
-- "aurasweep") per route a login.
-- prd19 THE 11-16 TEST BUILD MERGE: issue #358's prd18 (the font family) and feat/big-layout's prd18 (the HUD in the layout
-- editor) both kept; both notes below
-- prd18 ISSUE #358 (a player: "Cyrillic characters are displayed as empty squares instead of actual letters"; the user's pick,
-- 2026-11-16: the font family, "like the game's own frames"): the HUD row's aura seconds and stack counts take cp41's WC.font
-- instead of SetFont(FRIZQT__); the same look in Latin.
-- prd18 THE HUD IN THE LAYOUT EDITOR (the user, 2026-11-16:
-- "we need to make a way to basically edit the UI locations on the top screen from our UI menu";
-- the design's pick: HUD height and scale move into the editor, the width stays a row): the HUD's PLACE and SIZE are
-- the layout's (TapPad pad22 WC.lay, per character: WowCompanionDB.lay[name-realm].hud = { dx, dy, s }): its top-centre at the screen's
-- centre plus (dx, 350 + dy) 1080-line pixels, its scale s % (S_MIN..S_MAX in S_STEP, as before), the place independent of the scale
-- (the scale grows the bars round their top-centre). P.y / P.x stay the bar's own frame-unit offsets (P.fromLay), so the pet strip, the
-- auras, the pick boxes, Party mode's lift and Hub's party grid (P.partyGeo, now with the HUD's x) follow it as before. ONCE (layV 1,
-- every character at once) the old height / scale (prdY / prdS, the app's slider) become that character's layout entry, so nothing
-- moves; the hub document then says the layout owns the two (prd.lay: the app drops them from its tap loop). Hub's old columns 36-39
-- still step them for an older app (into the saved entry: the player's press). While the editor is open the HUD is held up at its rest
-- alpha (why "layout"), so the piece being moved is seen. P.lay is the piece Hub's editor gathers.
-- prd20 / ab1 (GitHub #558 / #476, the user's rulings 2026-11-19): THE TOP SCREEN'S AURAS ARE THE GAME'S OWN AURA CONTAINERS, in and
-- out of fights, so the look never changes. In a fight every aura read an add-on makes throws ("Auras cannot be accessed when secret
-- while tainted": GetAuraDataByIndex, the instance-ID route, GetUnitAuraInstanceIDs; UNIT_AURA's payload is secret too), so prd16's
-- reader had nothing to draw. This client ships Blizzard_AuraContainer for external code (its .toc: "XML files need to be loaded into
-- the global environment in order to allow intrinsics and templates to be instantiated by external code"): an AuraContainer made with
-- CustomAuraContainerTemplate (allowUntaintedCreation) runs its methods as SECURE DELEGATES and the GAME fills its buttons
-- (CustomAuraButtonTemplate) - the icon, the stacks, the swipe and its seconds (a duration object), the dispel-type border, the
-- pandemic glow, the game's own tooltip. Our Lua never reads anything back (the buttons lock: DenyTaintedAccessWhenAurasAreSecret).
-- WC.abox makes ONE such container per piece (WC.abox.make: a host frame of ours, the container anchored inside it), each set up ONCE:
--   SetEditModePreviewEnabled(false) (no fake Edit Mode auras), the flow layout (anchor, growth, the line's width), SetUnit(unit) once,
--   then its groups (AddAuraGroup: a filter string, a max count, a sort, candidate filters, a layout). Every button is dressed in the
--   group's initializeFrame, which the game calls as it makes each batch (before it applies the lock): our textures / font strings /
--   Cooldown on the button, handed over with SetIcon, SetDurationCooldown, SetDurationText, SetApplicationCount, AddDispelTypeTexture,
--   AddPandemicRegion; SetHideTooltipInCombat(true) (the game's tooltip never in a fight). No XML, no new file (a new .toc = restart).
-- After that only two calls are ever made: UpdateAllAuras (a target change: the game's own TargetFrame does the same) and, on a Party
-- mode change of the HUD, the flow's anchor / growth. Showing / hiding is OUR host frame (alpha / Show / Hide on our own plain frame,
-- allowed in a fight), so the HUD switches that hid the old rows hide the containers the same way. Where the client has no container
-- (no CustomAuraContainerGroupDefaultOptions in _G, CreateFrame refuses the type, the template lacks AddAuraGroup, or no group took)
-- the old rows (prd16 / uf16) stay, as before.
-- Tooltips: in a fight the game hides them itself (SetHideTooltipInCombat); out of one they follow "Hide game tooltips" - the button's
-- tooltip is the FORBIDDEN AuraButtonTooltip, so tt6 lays its motion-only shield over each container's host (WC.tips.addBox).
-- What the setup did rides one "cap" line per piece (probe "aurabox": made / refused, the steps that failed and their error text).
WC.abox = { V = "ab1", boxes = {}, sent = {}, inits = 0, wired = {} }
do
    local A = WC.abox
    local function secret(v) return type(issecretvalue) == "function" and issecretvalue(v) or false end
    local function errText(e)
        if type(e) == "string" and not secret(e) then return (e:gsub("[%c\"\\]", " "):sub(1, 120)) end
        return "?"
    end
    -- an enum value from the client's own (re-exported) table, else the number its source gives
    local function enumv(get, def)
        local ok, v = pcall(get)
        if ok and type(v) == "number" and not secret(v) then return v end
        return def
    end
    A.SORT = { Default = 0, Expiration = 4 }
    function A.sort(k) return enumv(function() return AuraContainerSortMethod[k] end, A.SORT[k] or 0) end
    function A.dirNormal() return enumv(function() return AuraContainerSortDirection.Normal end, 0) end
    A.FLOW = { Left = -1, Right = 1, Up = 1, Down = -1 }
    function A.flow(k) return enumv(function() return AnchorUtil.FlowDirection[k] end, A.FLOW[k]) end
    function A.border() return enumv(function() return Enum.CustomAuraButtonDispelTypeTextureStyle.Border end, 0) end
    -- the seconds' colours the HUD row had (the BuffFrame's): white under a minute, gold from it - a step colour curve on the remaining
    -- time, evaluated by the game; nil where the client has no curve (the font string's own colour then)
    function A.textColor()
        local ok, o = pcall(function()
            local c = C_CurveUtil.CreateColorCurve()
            pcall(function() c:SetType(Enum.LuaCurveType.Step) end)
            c:AddPoint(0, CreateColor(1, 1, 1, 1))
            c:AddPoint(60, CreateColor(1, 0.82, 0, 1))
            return { curve = c, property = Enum.DurationTextBindingProperty.RemainingDuration }
        end)
        if ok and type(o) == "table" then return o end
    end
    local function wire(k, ok) A.wired[k] = A.wired[k] or { ok = 0, no = 0 }; local w = A.wired[k]; if ok then w.ok = w.ok + 1 else w.no = w.no + 1 end end
    -- look: { size (UI units), font (px, UI units), mask (the cooldown manager's rounded icon), timer = "text" (our seconds under the icon,
    -- the game's text binding) or "cd" (the Cooldown's own numbers in the icon's corner), bd (the border's outset), harmful }
    function A.initFor(look)
        return function(b)
            A.inits = A.inits + 1
            local sz = look.size
            pcall(b.SetSize, b, sz, sz)
            wire("tipcombat", pcall(b.SetHideTooltipInCombat, b, true))
            -- a touch on an icon still reaches the world under it (as our old rows: no mouse); the hover keeps the game's tooltip
            pcall(b.SetMouseClickEnabled, b, false)
            local tx = b:CreateTexture(nil, "ARTWORK")
            tx:SetAllPoints(b)
            tx:SetTexCoord(0.07, 0.93, 0.07, 0.93)
            if look.mask then
                pcall(function()
                    local m = b:CreateMaskTexture()
                    m:SetAtlas("UI-HUD-CoolDownManager-Mask")
                    m:SetAllPoints(b)
                    tx:AddMaskTexture(m)
                end)
                pcall(function()
                    local ov = b:CreateTexture(nil, "OVERLAY", nil, 1)
                    ov:SetAtlas("UI-HUD-CoolDownManager-IconOverlay-c60")
                    ov:SetPoint("CENTER", b, "CENTER", 0, 0)
                    ov:SetSize(sz * 86 / 64, sz * 86 / 64)
                end)
            end
            local cd = CreateFrame("Cooldown", nil, b, "CooldownFrameTemplate")
            cd:SetAllPoints(b)
            pcall(function() cd:SetReverse(true) end)                      -- the time GONE darkens (the aura's look)
            pcall(function() cd:SetDrawEdge(look.mask == true) end)
            pcall(function() cd:SetHideCountdownNumbers(look.timer ~= "cd") end)
            if look.timer == "cd" then
                -- the target bar's look (uf16): the game's own seconds, small, in the icon's lower right corner
                pcall(function()
                    for _, rg in ipairs({ cd:GetRegions() }) do
                        if rg and rg.GetObjectType and rg:GetObjectType() == "FontString" then
                            WC.font(rg, look.font, "OUTLINE"); rg:ClearAllPoints(); rg:SetPoint("BOTTOMRIGHT", b, "BOTTOMRIGHT", look.bd, -look.bd)
                        end
                    end
                end)
            end
            local bd = b:CreateTexture(nil, "OVERLAY", nil, 2)
            bd:SetPoint("TOPLEFT", b, "TOPLEFT", -look.bd, look.bd)
            bd:SetPoint("BOTTOMRIGHT", b, "BOTTOMRIGHT", look.bd, -look.bd)
            bd:Hide()
            local pd = b:CreateTexture(nil, "OVERLAY", nil, 3)
            pcall(function() pd:SetAtlas("UI-CooldownManager-PandemicBorder") end)
            pd:SetAllPoints(b)
            pd:Hide()
            local n = b:CreateFontString(nil, "OVERLAY")
            WC.font(n, look.font, "OUTLINE")
            if look.timer == "cd" then n:SetPoint("TOPLEFT", b, "TOPLEFT", -look.bd, look.bd) else n:SetPoint("BOTTOMRIGHT", b, "BOTTOMRIGHT", -1, 1) end
            -- handed to the game ONCE (the button locks while auras are secret); what took is counted for the probe
            wire("icon", pcall(b.SetIcon, b, tx))
            wire("swipe", pcall(b.SetDurationCooldown, b, cd))
            wire("stacks", pcall(b.SetApplicationCount, b, n))
            if look.timer ~= "cd" then
                local t = b:CreateFontString(nil, "OVERLAY")
                WC.font(t, look.font, "OUTLINE")
                t:SetPoint("TOP", b, "BOTTOM", 0, -2)
                local col = A.textColor()
                local okT = col and pcall(b.SetDurationText, b, t, { textColor = col })
                if not okT then okT = pcall(b.SetDurationText, b, t, {}) end
                wire("seconds", okT)
            end
            wire("border", pcall(b.AddDispelTypeTexture, b, bd, { style = A.border(), showWhenHarmful = true, showWhenHelpful = false,
                                                                    showWithoutDispelType = look.harmful == true }))
            wire("pandemic", pcall(b.AddPandemicRegion, b, pd))
        end
    end
    -- one "cap" line per piece and outcome (plain values only)
    function A.probe(key, made, steps, groups)
        local k = key .. ":" .. tostring(made)
        if A.sent[k] then return end
        A.sent[k] = true
        if WC.send and WC.encode then
            local okC, c = pcall(InCombatLockdown)
            pcall(function() WC.send(WC.encode({ type = "cap", probe = "aurabox", v = A.V, key = key, made = made, groups = groups,
                                                 steps = steps, fight = okC and c == true })) end)
        end
    end
    -- the piece's container: s = { key, parent, unit, w, h (the host's size), anchor ("TOPLEFT" / "TOPRIGHT" / "BOTTOMLEFT"), grow
    -- ("Right" / "Left"), vgrow ("Down" / "Up"), line (the flow's line width), look, groups = { { key, filter, max, sort, cand, layout } } }
    -- -> the box, or nil (no container on this client / refused: the caller keeps its old row)
    function A.make(s)
        if A.boxes[s.key] then return A.boxes[s.key] end
        if A.boxes[s.key] == false then return nil end
        -- the client's Blizzard_AuraContainer: its defaults, re-exported to the global environment (Blizzard_AuraContainerShared.lua)
        if type(_G.CustomAuraContainerGroupDefaultOptions) ~= "table" then
            A.boxes[s.key] = false
            A.probe(s.key, false, { create = "no Blizzard_AuraContainer" }, 0)
            return nil
        end
        local host = CreateFrame("Frame", nil, s.parent)
        host:EnableMouse(false)
        host:SetSize(s.w, s.h)
        local ok, c = pcall(CreateFrame, "AuraContainer", nil, host, "CustomAuraContainerTemplate")
        if not ok or type(c) ~= "table" or type(c.AddAuraGroup) ~= "function" or type(c.SetUnit) ~= "function" then
            pcall(host.Hide, host)
            A.boxes[s.key] = false
            A.probe(s.key, false, { create = ok and "no AddAuraGroup" or errText(c) }, 0)
            return nil
        end
        local steps = {}
        local function try(name, ...)
            local f = c[name]
            if type(f) ~= "function" then steps[name] = "missing"; return false end
            local okc, e = pcall(f, c, ...)
            if not okc then steps[name] = errText(e) end
            return okc
        end
        try("SetEditModePreviewEnabled", false)                             -- the user: no Edit Mode preview auras
        local box = { key = s.key, host = host, c = c, unit = s.unit, steps = steps, up = false }
        A.layout(box, s.anchor, s.grow, s.vgrow or "Down")
        try("SetFlowLayoutMaximumLineSize", s.line)
        try("SetUnit", s.unit)                                              -- ONCE: one container per unit token
        local groups = 0
        for _, g in ipairs(s.groups) do
            local okg = try("AddAuraGroup", g.key, g.filter, { maxFrameCount = g.max, sortMethod = A.sort(g.sort or "Default"),
                sortDirection = A.dirNormal(), candidateFilters = g.cand, layout = g.layout, initializeFrame = A.initFor(s.look) })
            if okg then groups = groups + 1 end
        end
        if groups == 0 then
            pcall(host.Hide, host)
            A.boxes[s.key] = false
            A.probe(s.key, false, steps, 0)
            return nil
        end
        box.groups = groups
        A.boxes[s.key] = box
        A.probe(s.key, true, steps, groups)
        if type(WC.tips) == "table" and type(WC.tips.addBox) == "function" then pcall(WC.tips.addBox, s.key, host) end
        return box
    end
    -- the flow's anchor and growth (setup, and the HUD's Party mode move), the container anchored at that corner of its host
    function A.layout(box, anchor, grow, vgrow)
        local c = box.c
        if box.anchor == anchor and box.grow == grow and box.vgrow == vgrow then return end
        box.anchor, box.grow, box.vgrow = anchor, grow, vgrow
        pcall(function() c:ClearAllPoints(); c:SetPoint(anchor, box.host, anchor, 0, 0) end)
        local okA, eA = pcall(c.SetFlowLayoutAnchorPoint, c, anchor)
        if not okA then box.steps.SetFlowLayoutAnchorPoint = errText(eA) end
        local okG, eG = pcall(c.SetFlowLayoutGrowthDirection, c, A.flow(grow), A.flow(vgrow))
        if not okG then box.steps.SetFlowLayoutGrowthDirection = errText(eG) end
    end
    -- a full refresh (a target change): the game's own TargetFrame calls the same on PLAYER_TARGET_CHANGED
    function A.refresh(box)
        if not box then return false end
        local ok, e = pcall(box.c.UpdateAllAuras, box.c)
        if not ok then box.steps.UpdateAllAuras = errText(e) end
        return ok
    end
    -- shown / hidden: our own host frame only
    function A.show(box, on)
        if not box then return end
        local h = box.host
        if on then if not h:IsShown() then h:Show() end elseif h:IsShown() then h:Hide() end
    end
    -- the hub document's word: which pieces have a container, and what any step said
    function A.word()
        local out = {}
        for k, b in pairs(A.boxes) do
            if b == false then out[k] = "none"
            else
                local bad = nil
                for s, e in pairs(b.steps) do bad = bad or (s .. ": " .. e) end
                out[k] = bad or "ok"
            end
        end
        return { v = A.V, b = out, n = A.inits }
    end
end

WC.prd = { V = "prd21", PET_FADE_S = 0.2, PET_SIDE_GAP = 12, PARTY_GAP = 8, AURA_UP = 22, LIFT_MARGIN = 8, lift = 0, petSide = "under", auraUp = false, partyRoomPx = 0, PICK_S = 0.1, PICK_PAD = 4, PICK_PX = 2, PICK_GOLD = { 1, 0.82, 0 }, PET_W = 0.6, PET_HH = 8, PET_PH = 4, PET_BAR_GAP = 1, PET_GAP = 17, PET_ICON = 36, PET_IGAP = 10, PET_HAPPY = 20,
           PET_DEAD_RGB = { 0.5, 0.5, 0.5 }, PET_HAPPY_TEX = "Interface\\PetPaperDollFrame\\UI-PetHappiness",
           PET_HAPPY_U = { { 0.375, 0.5625 }, { 0.1875, 0.375 }, { 0, 0.1875 } }, PET_HAPPY_V = 0.359375, petDrop = 0, CPH = 5, CP_GAP = 4, TRAVEL = 14, OUT_S = 0.45, vis = 0, dy = 0, ENERGY_S = 10, RAGE_S = 30, SEL_S = 4, W_DEF = 280, W_MIN = 180, W_MAX = 400, W_STEP = 20,
           S_DEF = 100, S_MIN = 70, S_MAX = 160, S_STEP = 10, sel = "y", FILL_ATLAS = { "UI-HUD-CoolDownManager-Bar-c60", "UI-HUD-CoolDownManager-Bar" }, HEALTH_RGB = { 0.12, 0.86, 0.12 }, LAG_S = 0.3, HEAL_RGB = { 0.47, 1, 0.47, 0.55 }, LAG_RGB = { 1, 0.86, 0.76 }, Y_DEF = -350, Y_MIN = -470, Y_MAX = -170, STEP = 10, W = 280, HH = 15, PH = 9, BAR_GAP = 8, ICON = 26, IGAP = 4,
           ROW_GAP = 20, BG_W = 132, BG_H = 19, Y_SHIFT = -50, BG = { "UI-HUD-CoolDownManager-Bar-BG-c60", "UI-HUD-CoolDownManager-Bar-BG" }, REST = 0.92, BUFF_MAX = 6, DEBUFF_MAX = 4, BUFF_DUR = 300, HOLD_S = 2, FADE_S = 1.5, IN_S = 0.25, FULL = 0.99, POLL_S = 0.1, AURA_S = 0.5,
           alpha = 0, shown = false, why = "off" }
-- (the names Hub's hub15 row reads: y, on, min, max, step, def, nudge; hub16 adds w / s and their ranges, sel and select)
WC.prd.min, WC.prd.max, WC.prd.step, WC.prd.def = WC.prd.Y_MIN, WC.prd.Y_MAX, WC.prd.STEP, WC.prd.Y_DEF
do
    local P = WC.prd
    local function now() return (type(GetTime) == "function" and GetTime()) or 0 end
    -- prd16 (issue #181): the secret test FIRST - a secret refuses the v == v compare
    local function num(v) if type(v) == "number" and not (type(issecretvalue) == "function" and issecretvalue(v)) and v == v then return v end end
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
    -- prd21 NO PARTIAL CHARACTER KEY (the 11-20 hunter, test build 1513): Safety k6's WC.charKey(), nil until the realm is known (it
    -- was "Name-" with an empty realm, "?" with no name): nothing is read or kept per character without the whole key
    local function who()
        return ((function() if type(WC.charKey) == "function" then return (WC.charKey()) end; local ok, k = pcall(function() local n, r = UnitName("player"), GetRealmName(); if type(n) == "string" and type(r) == "string" and n:find("%S") and r:find("%S") and r ~= "?" and r ~= "_" then return n .. "-" .. r end end); return ok and k or nil end)())
    end
    -- the OLD height (prdY, frame units; the prd4 shift once) of [name], the default when none
    local function oldY(name)
        -- prd4: a height the player set on the old default moves down with it, once (prdYv 2)
        if type(WowCompanionDB) == "table" and type(WowCompanionDB.prdY) == "table" and WowCompanionDB.prdYv ~= 2 then
            for k, v in pairs(WowCompanionDB.prdY) do
                if num(v) then WowCompanionDB.prdY[k] = math.max(P.Y_MIN, math.min(P.Y_MAX, v + P.Y_SHIFT)) end
            end
            WowCompanionDB.prdYv = 2
        end
        local t = type(WowCompanionDB) == "table" and WowCompanionDB.prdY
        local y = type(t) == "table" and num(t[name]) or nil
        return y and math.max(P.Y_MIN, math.min(P.Y_MAX, y)) or P.Y_DEF
    end
    P.oldY = oldY
    -- prd18 THE ONE-TIME MOVE (layV 1): every character's old height / scale -> its layout entry { dx 0, dy, s } (the top where it was:
    -- the old top stood y * s spec px from the centre, the new one at Y_DEF - dy), never over an entry the layout already has
    function P.layMigrate()
        if type(WowCompanionDB) ~= "table" or WowCompanionDB.layV == 1 then return false end
        local names = {}
        for _, key in ipairs({ "prdY", "prdS" }) do
            local t = WowCompanionDB[key]
            if type(t) == "table" then for k in pairs(t) do if type(k) == "string" then names[k] = true end end end
        end
        local moved = 0
        for name in pairs(names) do
            local y = oldY(name)
            local st = type(WowCompanionDB.prdS) == "table" and num(WowCompanionDB.prdS[name]) or nil
            local sc = st and math.max(P.S_MIN, math.min(P.S_MAX, st)) or P.S_DEF
            if y ~= P.Y_DEF or sc ~= P.S_DEF then
                if type(WowCompanionDB.lay) ~= "table" then WowCompanionDB.lay = {} end
                if type(WowCompanionDB.lay[name]) ~= "table" then WowCompanionDB.lay[name] = {} end
                if WowCompanionDB.lay[name].hud == nil then
                    WowCompanionDB.lay[name].hud = { dx = 0, dy = math.floor(P.Y_DEF - y * sc / 100 + 0.5), s = sc }
                    moved = moved + 1
                end
            end
        end
        WowCompanionDB.layV = 1
        P.layMoved = moved
        return true
    end
    -- the HUD's layout entry: TapPad's WC.lay (the editor's view while it is open, else the saved one); without it (an older TapPad)
    -- our saved table read directly
    local function layHud()
        local L = WC.lay
        if type(L) == "table" and type(L.get) == "function" then return L.get("hud") end
        local db = type(WowCompanionDB) == "table" and WowCompanionDB.lay or nil
        local t = type(db) == "table" and db[who()] or nil
        local e = type(t) == "table" and t.hud or nil
        return type(e) == "table" and e or nil
    end
    -- the HUD's entry now -> P.sc, P.y, P.x (frame units: the offsets over the scale)
    P.layOwns = true
    function P.fromLay()
        pcall(P.layMigrate)
        local e = layHud()
        local dx = e and num(e.dx) or 0
        local dy = e and num(e.dy) or 0
        local sc = e and num(e.s) or P.S_DEF
        sc = math.max(P.S_MIN, math.min(P.S_MAX, sc))
        dx, dy = math.max(-1920, math.min(1920, dx)), math.max(-1080, math.min(1080, dy))
        P.sc = sc
        P.y = (P.Y_DEF - dy) / (sc / 100)
        P.x = dx / (sc / 100)
        return P.y
    end
    function P.loadY() return P.fromLay() end
    -- the app's slider: one step down (-1) / up (+1), or back to the default (0)
    -- prd8: the width and the scale, per character
    local function kept(key, def, lo, hi)
        local t = type(WowCompanionDB) == "table" and WowCompanionDB[key]
        local w = who()
        local v = w and type(t) == "table" and num(t[w]) or nil
        return v and math.max(lo, math.min(hi, v)) or def
    end
    local function keep(key, v, def)
        local w = who()
        if not w then return end                                        -- prd21: no character key yet: nothing kept
        if type(WowCompanionDB) ~= "table" then WowCompanionDB = {} end
        if type(WowCompanionDB[key]) ~= "table" then WowCompanionDB[key] = {} end
        WowCompanionDB[key][w] = v ~= def and v or nil
    end
    -- prd18: the layout's scale alone (P.fromLay sets the place with it; the place is left as it is here)
    function P.layScale()
        pcall(P.layMigrate)
        local e = layHud()
        return math.max(P.S_MIN, math.min(P.S_MAX, (e and num(e.s)) or P.S_DEF))
    end
    function P.loadSize()
        P.wd = kept("prdW", P.W_DEF, P.W_MIN, P.W_MAX)
        P.sc = P.layScale()                                              -- prd18: the scale is the layout's
        P.W = P.wd
        return P.wd, P.sc
    end
    -- prd18: an older app's step of the height / the scale (Hub's columns 36-38, the player's press) into the saved layout entry
    local function layStep(ddy, ds, reset)
        local L = WC.lay
        local name = type(L) == "table" and type(L.who) == "function" and L.who() or who()
        if not name then return end                                     -- prd21: no character key yet: nothing kept
        if type(WowCompanionDB) ~= "table" then WowCompanionDB = {} end
        if type(WowCompanionDB.lay) ~= "table" then WowCompanionDB.lay = {} end
        if type(WowCompanionDB.lay[name]) ~= "table" then WowCompanionDB.lay[name] = {} end
        local t = WowCompanionDB.lay[name]
        local e = type(t.hud) == "table" and t.hud or {}
        local dx, dy, sc = num(e.dx) or 0, num(e.dy) or 0, num(e.s) or P.S_DEF
        if reset == "y" then dy = 0 elseif reset == "s" then sc = P.S_DEF end
        -- (the old slider's range: the height Y_MIN..Y_MAX at 100 %)
        dy = math.max(P.Y_DEF - P.Y_MAX, math.min(P.Y_DEF - P.Y_MIN, dy + (ddy or 0)))
        sc = math.max(P.S_MIN, math.min(P.S_MAX, sc + (ds or 0)))
        t.hud = (dx ~= 0 or dy ~= 0 or sc ~= P.S_DEF) and { dx = dx, dy = dy, s = sc } or nil
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
                -- prd18: the scale is the layout's (the saved entry; the player's press)
                if d == 0 then layStep(0, 0, "s") else layStep(0, d > 0 and P.S_STEP or -P.S_STEP) end
                P.fromLay()
            end
            P.resize()
            P.place()
            return P.sel == "w" and P.wd or P.sc
        end
        P.sel = "y"
        -- prd18: the height is the layout's (the saved entry; one STEP of screen pixels a press, up = d > 0)
        if d == 0 then layStep(0, 0, "y") else layStep(d > 0 and -P.STEP or P.STEP, 0) end
        P.fromLay()
        P.resize()
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
    -- prd11: the host layer, its lip frame and the texel scale given (the pet's strip: its own layer and lip, the player's scale);
    -- left out: the player's layer P.g, its lip P.lip, the health opening's 8 texels = HH
    -- prd13 (the user's "Same green as mine", 2026-11-11): shadeH, the inner shade's height in UI units (left out: 45 % of b's height)
    function P.frameFor(b, host, lip, k, shadeH)
        host = host or P.g
        k = k or u(P.HH / 8)                                    -- UI units per texel (one scale for every bar)
        local info, atlas
        for _, a in ipairs(P.BG) do
            local ok, i = pcall(function() return C_Texture.GetAtlasInfo(a) end)
            if ok and type(i) == "table" and i.file and num(i.leftTexCoord) then info, atlas = i, a; break end
        end
        local pieces = { atlas = atlas }
        if not info then
            -- no atlas coordinates: the whole piece behind the bar (prd4's way), by its measured margins
            local t = host:CreateTexture(nil, "BACKGROUND", nil, -4)
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
        if not lip then
            if not P.lip then
                P.lip = CreateFrame("Frame", nil, P.g)
                P.lip:SetAllPoints(P.g)
                P.lip:EnableMouse(false)
            end
            lip = P.lip
        end
        -- the track: the art's dark middle (rows 3..11) under the bar, the full width
        local tr = sub(host, "BACKGROUND", -4, 0, 3, P.BG_W, 11)
        tr:SetPoint("TOPLEFT", b, "TOPLEFT", -3 * k, 0)
        tr:SetPoint("BOTTOMRIGHT", b, "BOTTOMRIGHT", 7 * k, 0)
        -- the lip over the bar: the top rows and the bottom rows IN THREE - the art's end caps (columns 0..11, 118..132) and a
        -- middle stretched from a MARK-FREE span (31..50: the art's notches at 57 / 52 / 116 would sit at the same spot on both bars:
        -- prd6, the user: "The markings on the borders being in the same spots makes them seem a bit lazy") - then the sides
        local function rim(r0, r1, h, edge, sideL, sideR)
            local l = sub(lip, "ARTWORK", 1, 0, r0, 11, r1)
            l:SetPoint(edge == "top" and "BOTTOMLEFT" or "TOPLEFT", b, sideL, -3 * k, 0)
            l:SetSize(11 * k, h)
            local r = sub(lip, "ARTWORK", 1, 118, r0, P.BG_W, r1)
            r:SetPoint(edge == "top" and "BOTTOMRIGHT" or "TOPRIGHT", b, sideR, 7 * k, 0)
            r:SetSize(14 * k, h)
            local m = sub(lip, "ARTWORK", 1, 31, r0, 50, r1)
            m:SetPoint(edge == "top" and "BOTTOMLEFT" or "TOPLEFT", l, edge == "top" and "BOTTOMRIGHT" or "TOPRIGHT", 0, 0)
            m:SetPoint(edge == "top" and "BOTTOMRIGHT" or "TOPRIGHT", r, edge == "top" and "BOTTOMLEFT" or "TOPLEFT", 0, 0)
            m:SetHeight(h)
            return { l = l, m = m, r = r, h = h }
        end
        local top = rim(0, 3, 3 * k, "top", "TOPLEFT", "TOPRIGHT")
        local bot = rim(11, P.BG_H, 8 * k, "bottom", "BOTTOMLEFT", "BOTTOMRIGHT")
        local lf = sub(lip, "ARTWORK", 1, 0, 3, 3, 11)
        lf:SetPoint("TOPRIGHT", b, "TOPLEFT", 0, 0)
        lf:SetPoint("BOTTOMRIGHT", b, "BOTTOMLEFT", 0, 0)
        lf:SetWidth(3 * k)
        local rt = sub(lip, "ARTWORK", 1, 125, 3, P.BG_W, 11)
        rt:SetPoint("TOPLEFT", b, "TOPRIGHT", 0, 0)
        rt:SetPoint("BOTTOMLEFT", b, "BOTTOMRIGHT", 0, 0)
        rt:SetWidth(7 * k)
        -- the inner shadow: the lip's shade over the opening's top
        local sh = lip:CreateTexture(nil, "ARTWORK", nil, 0)
        sh:SetColorTexture(0, 0, 0, 1)
        sh:SetPoint("TOPLEFT", b, "TOPLEFT", 0, 0)
        sh:SetPoint("TOPRIGHT", b, "TOPRIGHT", 0, 0)
        sh:SetHeight(shadeH or (b:GetHeight() * 0.45))
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
        WC.font(o.t, u(13), "OUTLINE")                                      -- prd18 (#358): the family (cp41 WC.font)
        o.t:SetPoint("TOP", o.f, "BOTTOM", 0, -u(2))
        o.n = o.f:CreateFontString(nil, "OVERLAY")
        WC.font(o.n, u(13), "OUTLINE")
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
        sh:SetPoint("BOTTOMRIGHT", f, "TOPRIGHT", u(16), -u(P.HH + P.BAR_GAP + P.PH + 16 + (P.petDrop or 0)))
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
        -- prd10: the combo pips under the power bar (shown for a Rogue / a cat only: P.combo)
        P.cp = bar(f, P.CPH)
        P.cp:SetPoint("TOP", P.pw, "BOTTOM", 0, -u(P.CP_GAP))
        P.paint(P.cp, P.POWER.COMBO_POINTS[1], P.POWER.COMBO_POINTS[2], P.POWER.COMBO_POINTS[3])
        P.cp.gaps = {}
        P.cp:Hide()
        -- the lip over every bar and overlay (the extras' clip frames are the bars' children)
        if P.lip then pcall(function() P.lip:SetFrameLevel(P.hp:GetFrameLevel() + 20) end) end
        P.buffs, P.debuffs = {}, {}
        for i = 1, P.BUFF_MAX do P.buffs[i] = icon(f, i) end
        for i = 1, P.DEBUFF_MAX do P.debuffs[i] = icon(f, i) end
        P.box = P.boxMake(f)                                           -- prd20: the game's own aura container in their place
        P.petL, P.pet = nil, nil
        P.petBuild()                                                   -- prd11: the pet's strip (its own layer on P.f)
        P.pick = nil
        P.pickBuild()                                                  -- prd14: Party mode's pick boxes (you / your pet), with these bars
        P.place()
        return f
    end
    -- prd8: the width and the scale applied to what is built (the app's live preview)
    function P.resize()
        if not P.wd then P.loadSize() end
        P.W = P.wd
        if not P.f then return end
        P.f:SetSize(u(P.W), u(P.HH + P.BAR_GAP + P.PH + (P.petDrop or 0)))
        pcall(function() P.f:SetScale(P.sc / 100) end)
        for _, b in ipairs({ P.hp, P.pw, P.lag, P.cp }) do if b then b:SetWidth(u(P.W)) end end
        P.petLayout(P.petDrop or 0, true)                                -- prd11: the pet's strip at the new width
        if P.cp then P.cp.max = nil end                                  -- prd10: the pips' gaps laid out again at the new width
        for _, b in ipairs({ P.heal, P.shield }) do if b then b:SetWidth(u(P.W)) end end
        if P.lag then P.lag:SetAllPoints(P.hp) end
        -- prd20: the container's line follows the bar's width (its host too); a refusal stays at the old width
        if P.box then
            pcall(function() P.box.host:SetWidth(u(P.W)) end)
            pcall(P.box.c.SetFlowLayoutMaximumLineSize, P.box.c, u(P.W))
        end
        P.cache = nil
    end
    function P.place()
        if not P.f then return end
        P.f:ClearAllPoints()
        local y = P.y or P.loadY()
        P.f:SetPoint("TOP", UIParent, "CENTER", u(num(P.x) or 0), u(y + (P.dy or 0) + (num(P.lift) or 0)))   -- prd15: the guard's lift
    end
    -- prd18 THE HUD AS A LAYOUT PIECE (Hub's editor gathers it): its anchor is the bars' top-centre, 1080-line pixels from the top-left
    P.lay = {
        id = "hud", sn = P.S_MIN, sx = P.S_MAX, ss = P.S_STEP,
        -- the default anchor (no entry): the screen's centre, 350 below it
        base = function()
            local W = type(WC.lay) == "table" and WC.lay.screen() or 1920
            return W / 2, 540 - P.Y_DEF
        end,
        -- the rect's size at entry [e] (its scale) and its top-left from the anchor
        size = function(e)
            local sc = ((e and num(e.s)) or P.S_DEF) / 100
            return (num(P.W) or num(P.wd) or P.W_DEF) * sc, (P.HH + P.BAR_GAP + P.PH) * sc
        end,
        off = function(w) return -w / 2, 0 end,
        on = function() local ok, w = pcall(P.wanted); return ok and w == true end,
        apply = function() P.fromLay(); P.resize(); P.place() end,
    }
    -- THE HUD BLOCK (prd21 + ActionBar nbar11, the top-screen combat bar's "never over my HUD"; the user 2026-11-22): every part the HUD can
    -- show in a fight, from its geometry alone (a pure function of the fields: a HUD faded out of combat, its auras not up yet, still
    -- counts) - the bars, the combo pips (P.petDrop has them while shown), the pet strip under (petDrop) or beside the HP bar
    -- (PET_SIDE_GAP past its rim, P.petGeo's row), Party mode's rows under the bars (petDrop = PET_GAP + PARTY_GAP + their room), the aura
    -- host's two rows under them (ROW_GAP past the strip) or above them (AURA_UP, Party mode), Party mode's lift - at the HUD's scale.
    -- [e] the layout entry (its dx / dy / s; nil = the default spot). -> { x, y, w, h } in 1080-line px from the top-left
    P.lay.block = function(e)
        local sc = ((e and num(e.s)) or num(P.sc) or P.S_DEF) / 100
        local bx, by = P.lay.base()
        local ax = bx + ((e and num(e.dx)) or 0)
        local ay = by + ((e and num(e.dy)) or 0) - (num(P.lift) or 0) * sc
        local w = num(P.W) or num(P.wd) or P.W_DEF
        local base = P.HH + P.BAR_GAP + P.PH
        local drop = math.max(0, num(P.petDrop) or 0)
        local auraH = 2 * (P.ICON + 18)
        -- spec px from the bars' top-left (y down)
        local x0, x1, y0, y1 = 0, w, 0, base + drop
        if P.auraUp then y0 = -(P.AURA_UP + auraH)
        else y1 = base + P.ROW_GAP + drop + auraH end
        local pl = P.petL
        local petOn = pl ~= nil and type(pl.IsShown) == "function" and pl:IsShown() == true
        if petOn and P.petSide == "beside" then
            local okG, g = pcall(P.petGeo)
            local row = (okG and type(g) == "table" and num(g.row)) or 0
            local px = w + 7 * P.HH / 8 + P.PET_SIDE_GAP
            local py = -(row - base) / 2
            x1 = math.max(x1, px + w)
            y0, y1 = math.min(y0, py), math.max(y1, py + row)
        end
        return { x = ax - (w / 2) * sc + x0 * sc, y = ay + y0 * sc, w = (x1 - x0) * sc, h = (y1 - y0) * sc }
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
        pcall(P.combo, pt)
        pcall(P.petValues)                                               -- prd11: the pet's strip
    end
    -- prd10 (issue #120): a Rogue's / a cat's combo points into the pip strip; nothing read back (a secret goes straight to the setter)
    function P.combo(pt)
        local cp = P.cp
        if not cp then return end
        local okC, _, cls = pcall(UnitClass, "player")
        local energy = (Enum and Enum.PowerType and Enum.PowerType.Energy) or 3
        if not (okC and (cls == "ROGUE" or (cls == "DRUID" and pt == energy))) then cp:Hide(); P.ex.cp = nil; return end
        local CP = (Enum and Enum.PowerType and Enum.PowerType.ComboPoints) or 4
        local okM, max = pcall(UnitPowerMax, "player", CP)
        max = (okM and num(max) and max > 0) and max or 5
        if cp.max ~= max then
            cp.max = max
            for _, g in ipairs(cp.gaps) do g:Hide() end
            for k = 1, max - 1 do
                local g = cp.gaps[k] or cp:CreateTexture(nil, "OVERLAY")
                cp.gaps[k] = g
                g:SetColorTexture(0, 0, 0, 1); g:SetSize(u(2), u(P.CPH))
                g:ClearAllPoints(); g:SetPoint("LEFT", cp, "LEFT", u(P.W) * k / max - u(1), 0); g:Show()
            end
        end
        pcall(function() cp:SetMinMaxValues(0, max) end)
        local v = nil
        if type(GetComboPoints) == "function" then local okG, g = pcall(GetComboPoints, "player", "target"); if okG then v = g end end
        if type(v) == "nil" then local okP, p = pcall(UnitPower, "player", CP); if okP then v = p end end
        if type(v) == "nil" then v = 0 end
        if not pcall(cp.SetValue, cp, v) then pcall(cp.SetValue, cp, 0) end
        cp:Show()
        P.ex.cp = max
    end

    -- ---------------------------------------------------------------- the auras
    -- prd16 (issue #181): ONE secret-safe reader (the HUD's row here, Hub's target bar through WC.prd.auraRead). A value is tested with
    -- issecretvalue before any compare; a table that is itself secret is never indexed (on this client that is a blocked action); a
    -- nil is told by type(). An entry: { id, icon, sicon (the icon is secret: straight to SetTexture, never compared), dur / exp (plain)
    -- or secret = true with sdur / sexp (straight to the widget setter), n (plain stacks), kind (a plain dispel type, else "none") }.
    local function secret(v) return type(issecretvalue) == "function" and issecretvalue(v) or false end
    local function plainTable(t)
        if type(t) ~= "table" or secret(t) then return false end
        if type(canaccesstable) == "function" then
            local ok, can = pcall(canaccesstable, t)
            if ok and can == false then return false end
        end
        return true
    end
    local function errText(e)
        if type(e) == "string" and not secret(e) then return e:gsub("[%c\"\\]", " "):sub(1, 120) end
        return "?"
    end
    local function entryOf(a, id, dg)
        if type(a) ~= "table" then return nil end
        if not plainTable(a) then dg.st = dg.st + 1; return nil end
        local e = { id = id }
        local aid = a.auraInstanceID
        if type(aid) == "number" and not secret(aid) then e.id = aid end
        local ic = a.icon
        if type(ic) == "nil" then return nil end
        if secret(ic) then e.sicon = true; dg.sf = dg.sf + 1 end
        e.icon = ic
        local dur, exp = a.duration, a.expirationTime
        if secret(dur) or secret(exp) then
            e.secret, e.sdur, e.sexp = true, dur, exp; dg.sf = dg.sf + 1
        else
            e.dur, e.exp = num(dur), num(exp)
        end
        local n = a.applications
        if secret(n) then dg.sf = dg.sf + 1 else e.n = num(n) end
        local k = a.dispelName
        if secret(k) then dg.sf = dg.sf + 1; e.kind = "none"
        elseif type(k) == "string" and k ~= "" then e.kind = k
        else e.kind = "none" end
        return e
    end
    -- unit, filter ("HELPFUL", "HARMFUL", "HARMFUL|PLAYER"), max entries (40 at most) -> list, dg { via, seen, st, sf, th, err }
    function P.auraRead(unit, filter, max)
        max = math.min(max or 40, 40)
        local out, dg = {}, { via = "index", seen = 0, st = 0, sf = 0, th = 0 }
        local CU = type(C_UnitAuras) == "table" and C_UnitAuras or nil
        for i = 1, 40 do
            if #out >= max then break end
            local ok, a
            if CU and type(CU.GetAuraDataByIndex) == "function" then
                ok, a = pcall(CU.GetAuraDataByIndex, unit, i, filter)
            elseif type(UnitAura) == "function" then
                local okU, name, ic, count, dtype, dur, exp = pcall(UnitAura, unit, i, filter)
                ok = okU
                if okU and type(name) ~= "nil" then a = { name = name, icon = ic, applications = count, dispelName = dtype, duration = dur, expirationTime = exp }
                elseif not okU then a = name end
            else dg.via = "none"; break end
            if not ok then dg.th = dg.th + 1; dg.err = dg.err or errText(a); break end
            if type(a) == "nil" then break end
            dg.seen = dg.seen + 1
            local e = entryOf(a, nil, dg)
            if e then out[#out + 1] = e end
        end
        -- the index loop threw or gave only tables we may not read: the instance IDs (plain in a fight) and each aura by its ID
        if #out == 0 and (dg.th > 0 or dg.st > 0) and CU and type(CU.GetUnitAuraInstanceIDs) == "function"
                and type(CU.GetAuraDataByAuraInstanceID) == "function" then
            dg.via = "ids"
            local ok, ids = pcall(CU.GetUnitAuraInstanceIDs, unit, filter, max)
            if not ok then dg.th = dg.th + 1; dg.err = dg.err or errText(ids)
            elseif plainTable(ids) then
                for k = 1, math.min(#ids, max) do
                    local id = ids[k]
                    if type(id) == "number" and not secret(id) then
                        local okd, a = pcall(CU.GetAuraDataByAuraInstanceID, unit, id)
                        if not okd then dg.th = dg.th + 1; dg.err = dg.err or errText(a)
                        else
                            dg.seen = dg.seen + 1
                            local e = entryOf(a, id, dg)
                            if e then out[#out + 1] = e end
                        end
                    end
                end
            end
        end
        return out, dg
    end
    -- prd16: what a fight's reads gave, once per kind and unit (at most PROBE_MAX a login): the app logs it (WCLOG), so a report
    -- shows whether the reads threw, came back secret, or came back empty
    P.PROBE_MAX, P.probeN, P.probeSeen = 8, 0, {}
    function P.auraProbe(unit, list, dgs)
        if P.probeN >= P.PROBE_MAX then return end
        local okC, c = pcall(InCombatLockdown)
        local fight = okC and c == true
        local th, st, sf, seen, via, err = 0, 0, 0, 0, {}, nil
        for _, dg in ipairs(dgs) do
            th, st, sf, seen = th + dg.th, st + dg.st, sf + dg.sf, seen + dg.seen
            via[#via + 1] = dg.via; err = err or dg.err
        end
        if not fight and th == 0 and st == 0 and sf == 0 then return end
        local okS, sec = pcall(function() return C_Secrets and C_Secrets.ShouldAurasBeSecret and C_Secrets.ShouldAurasBeSecret() end)
        local lock = (okS and type(sec) == "boolean" and not secret(sec)) and sec or nil
        local key = table.concat({ unit, tostring(fight), tostring(lock), table.concat(via, "/"), th > 0 and "t" or "", st > 0 and "s" or "",
                                   sf > 0 and "f" or "", (seen == 0) and "0" or "n", (#list == 0) and "e" or "l" }, ",")
        if P.probeSeen[key] then return end
        P.probeSeen[key] = true
        P.probeN = P.probeN + 1
        if WC.send and WC.encode then
            pcall(function() WC.send(WC.encode({ type = "cap", probe = "aurasec", v = P.V, u = unit, fight = fight, lock = lock,
                via = table.concat(via, "/"), seen = seen, shown = #list, st = st, sf = sf, th = th, err = err })) end)
        end
    end
    -- the auras worth showing: { icon, dur, exp, n, kind (debuffs) }
    function P.auras()
        local b, d, sb, sd = {}, {}, {}, {}
        local ab = { seen = 0, sec = 0, long = 0, nodur = 0, hseen = 0 }
        local hl, dgh = P.auraRead("player", "HELPFUL", 40)
        ab.seen = dgh.seen
        for _, a in ipairs(hl) do
            local dur, exp = a.dur, a.exp
            if a.secret then
                ab.sec = ab.sec + 1                                          -- (c) shown, after the plain ones; no seconds
                sb[#sb + 1] = { id = a.id, icon = a.icon, sicon = a.sicon, sdur = a.sdur, sexp = a.sexp, secret = true }
            elseif not (dur and exp and dur > 0) then ab.nodur = ab.nodur + 1
            elseif dur > P.BUFF_DUR then ab.long = ab.long + 1
            elseif #b < P.BUFF_MAX then
                b[#b + 1] = { icon = a.icon, sicon = a.sicon, dur = dur, exp = exp, n = a.n }
            end
        end
        for _, a in ipairs(sb) do if #b < P.BUFF_MAX then b[#b + 1] = a end end
        local dl, dgd = P.auraRead("player", "HARMFUL", 40)
        ab.hseen = dgd.seen
        for _, a in ipairs(dl) do
            local dur, exp = a.dur, a.exp
            if a.secret then
                sd[#sd + 1] = { id = a.id, icon = a.icon, sicon = a.sicon, sdur = a.sdur, sexp = a.sexp, secret = true, kind = a.kind }
            elseif dur and exp and dur > 0 and #d < P.DEBUFF_MAX then
                d[#d + 1] = { icon = a.icon, sicon = a.sicon, dur = dur, exp = exp, n = a.n, kind = a.kind }
            end
        end
        for _, a in ipairs(sd) do if #d < P.DEBUFF_MAX then d[#d + 1] = a end end
        -- prd16: the read's own word (via, secret tables / fields, throws) rides on the probe's ab
        ab.via, ab.st, ab.sf, ab.th, ab.err = dgh.via .. "/" .. dgd.via, dgh.st + dgd.st, dgh.sf + dgd.sf, dgh.th + dgd.th, dgh.err or dgd.err
        P.ab = ab
        pcall(P.auraProbe, "player", hl, { dgh, dgd })
        return b, d
    end
    -- prd17: an aura's swipe from its duration object (unit + a PLAIN auraInstanceID): true = set; false = no route here (the caller
    -- falls back). The object is never read, compared or computed: straight to the widget's setter
    P.sweepSeen = {}
    function P.auraSweep(cd, unit, a)
        local CU = type(C_UnitAuras) == "table" and C_UnitAuras or nil
        local get = CU and CU.GetAuraDuration
        local route
        if type(get) ~= "function" or type(cd) ~= "table" or type(cd.SetCooldownFromDurationObject) ~= "function" then route = "none"
        elseif type(a) ~= "table" or type(a.id) ~= "number" or secret(a.id) then route = "noid"
        else
            local ok, obj = pcall(get, unit, a.id)
            if ok and type(obj) ~= "nil" and pcall(cd.SetCooldownFromDurationObject, cd, obj, true) then route = "dur"
            else route = "refused" end
        end
        if not P.sweepSeen[route] then
            P.sweepSeen[route] = true
            if WC.send and WC.encode then
                local okC, c = pcall(InCombatLockdown)
                pcall(function() WC.send(WC.encode({ type = "cap", probe = "aurasweep", v = P.V, route = route, u = unit,
                                                     fight = okC and c == true })) end)
            end
        end
        return route == "dur"
    end
    -- the BuffFrame's own way: "2 m" / "8 s"
    local function secs(left)
        if left >= 60 then return math.ceil(left / 60) .. " m" end
        return math.max(0, math.ceil(left)) .. " s"
    end
    P.DEBUFF_ATLAS = { Magic = "magic", Curse = "curse", Disease = "disease", Poison = "poison", Bleed = "bleed", none = "default" }
    -- prd20 (#558 / #476): the row is ONE aura container on "player" (WC.abox): the buffs (HELPFUL lasting BUFF_DUR or less, at most
    -- BUFF_MAX), then the debuffs (HARMFUL with a duration, at most DEBUFF_MAX, the game's dispel-type border), flowing from the bar's
    -- left edge and wrapping at its width; the seconds under each icon (the game's text binding), the swipe, the stacks, the pandemic
    -- glow. Built with the bars (P.build); nil on a client without the container (the old row then, as before)
    function P.boxMake(parent)
        if type(WC.abox) ~= "table" then return nil end
        local step = u(P.IGAP)
        return WC.abox.make({
            key = "hud", parent = parent, unit = "player", w = u(P.W), h = u(2 * (P.ICON + 18)), anchor = "TOPLEFT", grow = "Right",
            line = u(P.W), look = { size = u(P.ICON), font = u(13), mask = true, timer = "text", bd = u(4), harmful = true },
            groups = {
                { key = "buffs", filter = "HELPFUL", max = P.BUFF_MAX, cand = { maxDuration = P.BUFF_DUR },
                  layout = { elementSpacing = step, lineSpacing = u(18) } },
                { key = "debuffs", filter = "HARMFUL", max = P.DEBUFF_MAX, cand = { maxDuration = math.huge },
                  layout = { elementSpacing = step, lineSpacing = u(18), groupSpacing = u(P.IGAP * 2) } },
            },
        })
    end
    -- prd20: the container's place (our host frame: allowed in a fight) under the bars, or in Party mode above them (prd15: growing up)
    function P.boxDraw(show)
        local box = P.box
        for _, list in ipairs({ P.buffs or {}, P.debuffs or {} }) do      -- (the old row's icons never show beside it)
            for _, o in ipairs(list) do if o.f:IsShown() then o.f:Hide() end end
        end
        P.nb, P.nd = nil, nil
        if not P.abAt or math.abs(now() - P.abAt) >= 2 then P.abAt = now(); P.ab = WC.abox.word() end   -- (the hub document's word)
        local h = box.host
        local y = P.auraUp and u(P.AURA_UP) or -u(P.HH + P.BAR_GAP + P.PH + P.ROW_GAP + (P.petDrop or 0))
        if box.y ~= y or box.yUp ~= P.auraUp then
            box.y, box.yUp = y, P.auraUp
            h:ClearAllPoints()
            if P.auraUp then
                h:SetPoint("BOTTOMLEFT", P.f, "TOPLEFT", 0, y)
                WC.abox.layout(box, "BOTTOMLEFT", "Right", "Up")
            else
                h:SetPoint("TOPLEFT", P.f, "TOPLEFT", 0, y)
                WC.abox.layout(box, "TOPLEFT", "Right", "Down")
            end
        end
        WC.abox.show(box, show == true)
    end
    function P.drawAuras(show)
        -- prd20: with the container nothing is read here, in a fight or out of one
        if P.box then return P.boxDraw(show) end
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
        local top = -u(P.HH + P.BAR_GAP + P.PH + P.ROW_GAP + (P.petDrop or 0))    -- prd11: under the pet's strip
        -- one row when both fit across the bar, else the debuffs on a second row (right aligned)
        local fits = (#b + #d) * (P.ICON + P.IGAP) - P.IGAP <= P.W
        local dTop = fits and top or top - u(P.ICON + 18)
        if P.auraUp then
            -- prd15 (the user, 2026-11-12: in Party mode the buffs / debuffs "move ABOVE the HUD bars"): the row's timers AURA_UP clear
            -- of the health bar's top rim; a second row (the debuffs) above the first
            top = u(P.AURA_UP + P.ICON)
            dTop = fits and top or top + u(P.ICON + 18)
        end
        local t = now()
        local function put(list, objs, fromRight, y)
            for i, o in ipairs(objs) do
                local a = list[i]
                if a then
                    o.f:ClearAllPoints()
                    if fromRight then o.f:SetPoint("TOPRIGHT", P.f, "TOPRIGHT", -(i - 1) * step, y)
                    else o.f:SetPoint("TOPLEFT", P.f, "TOPLEFT", (i - 1) * step, y) end
                    -- prd16: a secret icon straight to SetTexture (it takes one), never compared or kept
                    if a.sicon then pcall(function() o.tx:SetTexture(a.icon) end); o.icon = nil
                    elseif o.icon ~= a.icon then pcall(function() o.tx:SetTexture(a.icon) end); o.icon = a.icon end
                    if a.secret then
                        -- (c) the secret times straight to the swipe (a widget setter takes them), no arithmetic, no seconds
                        -- prd16: a secret is never compared: handed again once per re-read of the list (P.auraAt, plain)
                        if o.sAt ~= P.auraAt or not o.sexp then
                            -- prd17: the duration object first (issue #195's "Yes, fix them too"), the secret times as before without it
                            if not P.auraSweep(o.cd, "player", a) and not pcall(function() o.cd:SetCooldownFromExpirationTime(a.sexp, a.sdur) end) then
                                pcall(function() o.cd:Clear() end)
                            end
                            o.sexp, o.exp, o.dur, o.sAt = true, nil, nil, P.auraAt
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

    -- ---------------------------------------------------------------- prd11: the pet's strip
    -- (the user, 2026-11-11: "find a way to include Pet HP in the HUD, and better distinguish the player HUD vs Pet HUD"; mock-up A)
    -- its switch: Hub's "uisw" 32 / 33 (WC.npWant.pet), on unless turned off, and only while the resource bar's own switch is on
    function P.petOn()
        local npw = type(WC.npWant) == "table" and WC.npWant or {}
        if npw.pet == false then return false end
        local on = npw.prd
        if on == nil then on = npw.hud == true end
        return on == true
    end
    function P.wantedPet() return P.petOn() and (P.wanted()) == true end
    -- a pet out, from PLAIN reads only (UnitExists can be secret: then a plain name, then HasPetUI); how it was known
    function P.petHas()
        local okE, e = pcall(UnitExists, "pet")
        if okE and not secret(e) then return e == true or e == 1, "exists" end
        local okN, n = pcall(UnitName, "pet")
        if okN and type(n) == "string" and not secret(n) and n ~= "" then return true, "name" end
        local okP, h = pcall(HasPetUI)
        if okP and not secret(h) then return h == true or h == 1, "petui" end
        return false, "none"
    end
    -- the power type's token by its number, when the client gives no plain token
    P.PTOK = { [0] = "MANA", [1] = "RAGE", [2] = "FOCUS", [3] = "ENERGY" }
    -- the strip's row: the portrait or the two bars with their gap, the taller
    -- (review of prd11, 2026-11-11: the user's "Sub-strip under" at the player's texel scale draws an 8-texel bottom lip, HH = 15 spec
    -- px, under every bar; PET_BAR_GAP and PET_GAP are that lip + 1, so no rim piece of the health bar (or of the player's power bar
    -- above) lies over the pet's power / health opening; the rims may overlap each other, the openings stay clear)
    -- prd12: the strip's geometry in spec px (the player's texel scale k = HH / 8: a 3-texel top rim, an 8-texel bottom lip, a 7-texel
    -- right rim): the bars' unit (health, PET_BAR_GAP, power) its centre level with the portrait's; mid = that centre from the strip's top;
    -- the row = the portrait or the unit with its rims, the taller; x0 = the portrait's left, so portrait + unit are centred on the bar
    function P.petGeo()
        local k = P.HH / 8
        local unit = P.PET_HH + P.PET_BAR_GAP + P.PET_PH
        local mid = math.max(P.PET_ICON / 2, 3 * k + unit / 2)
        local g = { k = k, unit = unit, unitTop = mid - unit / 2, iconTop = mid - P.PET_ICON / 2 }
        g.row = math.max(g.iconTop + P.PET_ICON, g.unitTop + unit + 8 * k)
        -- prd13 (review, 2026-11-11): the happiness face (2 + PET_HAPPY past the right rim) counted while it can show; the bars narrowed
        -- when the whole group would not fit (W_MIN = 180), so the face stays inside the HUD frame
        local fixed = P.PET_ICON + P.PET_IGAP + 7 * k + (P.petHappyOn and (2 + P.PET_HAPPY) or 0)
        g.barW = math.max(0, math.min(P.W * P.PET_W, P.W - fixed))
        g.x0 = math.max(0, (P.W - (fixed + g.barW)) / 2)
        if P.petSide == "beside" then g.x0 = 0 end                   -- prd15: beside the HP bar, from its own left
        return g
    end
    function P.petRow() return P.petGeo().row end
    -- built with the bars, hidden until a pet is out: the round portrait, the green health, the power (one frame), the happiness face
    function P.petBuild()
        if P.petL or not P.f then return P.petL end
        local L = CreateFrame("Frame", nil, P.f)                    -- beside P.g, never under it (the player's curve would hide it)
        L:EnableMouse(false)
        L:SetSize(u(P.W), u(P.petRow()))
        L:Hide()
        P.petL = L
        local o = {}
        P.pet = o
        -- the portrait: a dark ring under it, both through the game's round portrait mask
        o.ring = L:CreateTexture(nil, "ARTWORK", nil, 1)
        o.ring:SetColorTexture(0, 0, 0, 0.85)
        o.ring:SetSize(u(P.PET_ICON), u(P.PET_ICON))                 -- (placed by P.petLayout: centred on the bars' unit)
        o.face = L:CreateTexture(nil, "ARTWORK", nil, 2)
        o.face:SetPoint("CENTER", o.ring, "CENTER", 0, 0)
        o.face:SetSize(u(P.PET_ICON - 4), u(P.PET_ICON - 4))         -- prd12: a thin rim (2 spec px)
        for _, t in ipairs({ o.ring, o.face }) do
            pcall(function()
                local m = L:CreateMaskTexture()
                m:SetTexture("Interface\\CharacterFrame\\TempPortraitAlphaMask", "CLAMPTOBLACKADDITIVE", "CLAMPTOBLACKADDITIVE")
                m:SetAllPoints(t)
                t:AddMaskTexture(m)
            end)
        end
        -- the bars, past the portrait (PET_IGAP clears the frame's left rim), PET_W of the player's width (P.petLayout); prd12: the
        -- player's own green, the power PET_BAR_GAP under the health
        o.hp = bar(L, P.PET_HH)
        P.paint(o.hp, P.HEALTH_RGB[1], P.HEALTH_RGB[2], P.HEALTH_RGB[3])
        o.pw = bar(L, P.PET_PH)
        o.pw:SetPoint("TOPLEFT", o.hp, "BOTTOMLEFT", 0, -u(P.PET_BAR_GAP))
        -- prd12: ONE PRD frame round the pair (the unit: the health's top to the power's bottom, a mouse-less frame), its own lip over
        -- both, the PLAYER's texel scale: the rim one thickness with the player's bars, no lip between the pet's two
        o.unit = CreateFrame("Frame", nil, L)
        o.unit:EnableMouse(false)
        o.unit:SetPoint("TOPLEFT", o.hp, "TOPLEFT", 0, 0)
        o.unit:SetSize(u(P.W * P.PET_W), u(P.PET_HH + P.PET_BAR_GAP + P.PET_PH))
        o.lip = CreateFrame("Frame", nil, L)
        o.lip:SetAllPoints(L)
        o.lip:EnableMouse(false)
        o.k = u(P.HH / 8)
        -- prd13: the shade over the pet's HEALTH as the player's over theirs (45 % of the health bar, not of the whole unit)
        o.frame = P.frameFor(o.unit, L, o.lip, o.k, u(P.PET_HH) * 0.45)
        pcall(function() o.lip:SetFrameLevel(o.hp:GetFrameLevel() + 20) end)
        -- a hunter's pet's happiness: the game's own faces (PetFrame's texture), PET_HAPPY big, past the unit's right rim (7 texels),
        -- level with the bars (prd12: never over the portrait)
        o.happy = o.lip:CreateTexture(nil, "OVERLAY", nil, 3)
        o.happy:SetTexture(P.PET_HAPPY_TEX)
        o.happy:SetSize(u(P.PET_HAPPY), u(P.PET_HAPPY))
        o.happy:SetPoint("LEFT", o.unit, "RIGHT", 7 * o.k + u(2), 0)
        o.happy:Hide()
        P.petLayout(0, true)
        return L
    end
    -- the strip's place (under the power bar, under the pips when they show) and P.petDrop, the room it takes: P.f's height, the
    -- shadow and the aura row move down by it; 0 with no pet
    -- prd15 (the user, 2026-11-12): beside the HP bar in Party mode (P.petSide "beside": no room under the bars), and Hub's party grid
    -- under the bars takes the room instead (P.partyRoomPx): P.dropFor says what the room is in those two cases
    function P.dropFor()
        local cpx = (P.cp and P.cp:IsShown()) and (P.CP_GAP + P.CPH) or 0
        local room = num(P.partyRoomPx) or 0
        if room > 0 then return cpx + P.PET_GAP + P.PARTY_GAP + room end
        if P.petSide ~= "beside" and P.petL and P.petL:IsShown() then return cpx + P.PET_GAP + P.petGeo().row end
        return 0
    end
    function P.petLayout(drop, force)
        if (num(P.partyRoomPx) or 0) > 0 or P.petSide == "beside" then drop = P.dropFor() end
        if drop == P.petDrop and not force then return end
        P.petDrop = drop
        local base = P.HH + P.BAR_GAP + P.PH
        if P.f then pcall(function() P.f:SetHeight(u(base + drop)) end) end
        -- (prd15: the shadow stays under the bars alone while the room is Hub's grid or the pet stands beside)
        local sdrop = ((num(P.partyRoomPx) or 0) > 0 or P.petSide == "beside") and 0 or drop
        if P.shadow and P.g then P.shadow:SetPoint("BOTTOMRIGHT", P.g, "TOPRIGHT", u(16), -u(base + 16 + sdrop)) end
        P.cache = nil
        local L, o = P.petL, P.pet
        if not (L and o) then return end
        local cpx = (P.cp and P.cp:IsShown()) and (P.CP_GAP + P.CPH) or 0
        local g = P.petGeo()
        L:ClearAllPoints()
        if P.petSide == "beside" and P.hp then
            -- prd15: [me][pet] - right of the HP bar's right rim (7 texels) by PET_SIDE_GAP, the row centred on the player's two bars
            L:SetPoint("TOPLEFT", P.hp, "TOPRIGHT", u(7 * P.HH / 8 + P.PET_SIDE_GAP), u((g.row - base) / 2))
        else
            L:SetPoint("TOPLEFT", P.f, "TOPLEFT", 0, -u(base + cpx + P.PET_GAP))
        end
        L:SetSize(u(P.W), u(g.row))
        -- prd12: portrait + unit centred under the player's bars, the portrait level with the bars' unit
        o.ring:ClearAllPoints()
        o.ring:SetPoint("TOPLEFT", L, "TOPLEFT", u(g.x0), -u(g.iconTop))
        o.hp:ClearAllPoints()
        o.hp:SetPoint("TOPLEFT", L, "TOPLEFT", u(g.x0 + P.PET_ICON + P.PET_IGAP), -u(g.unitTop))
        o.hp:SetWidth(u(g.barW)); o.pw:SetWidth(u(g.barW)); o.unit:SetWidth(u(g.barW))
    end
    -- fed at every poll as the player's bars are: the game's values straight to the setters, nothing read back (no lag chunk: its queue
    -- would keep a secret in a table)
    function P.petValues()
        local L, o = P.petL, P.pet
        if not (L and o) then return end
        local has, how = false, "off"
        if P.wantedPet() then has, how = P.petHas() end
        local x = { has = has, src = how, cbad = P.petCurveBad, a = P.petSrc }
        P.pex = x
        if not has then
            if L:IsShown() then L:Hide() end
            P.petLayout(0)
            return
        end
        P.petLayout(((P.cp and P.cp:IsShown()) and (P.CP_GAP + P.CPH) or 0) + P.PET_GAP + P.petGeo().row)
        if not L:IsShown() then L:Show() end
        -- the portrait: GetPetIcon's plain file, else the game's own portrait setter (a widget setter: nothing read back)
        local okI, ic = pcall(GetPetIcon)
        ic = (okI and (type(ic) == "number" or type(ic) == "string") and not secret(ic)) and ic or nil
        if ic then
            if o.icon ~= ic then pcall(function() o.face:SetTexture(ic) end); o.icon = ic end
        elseif o.icon ~= "portrait" and type(SetPortraitTexture) == "function" and pcall(SetPortraitTexture, o.face, "pet") then
            o.icon = "portrait"
        end
        -- dead (plainly so): greyed (prd12: no name, no word)
        local okD, d = pcall(UnitIsDead, "pet")
        local dead = okD and not secret(d) and (d == true or d == 1) or false
        if o.dead ~= dead then
            o.dead = dead
            local c = dead and P.PET_DEAD_RGB or P.HEALTH_RGB
            P.paint(o.hp, c[1], c[2], c[3])
            pcall(function() o.face:SetDesaturated(dead) end)
        end
        -- health: the player's green (prd12, the user's pick "Same green as mine"; the strip's own place and frame tell it apart)
        pcall(function() o.hp:SetMinMaxValues(0, UnitHealthMax("pet")) end)
        pcall(function() o.hp:SetValue(UnitHealth("pet")) end)
        -- power by the pet's own type: a hunter's pet FOCUS, a demon MANA
        local okT, pt, tok = pcall(UnitPowerType, "pet")
        pt = okT and num(pt) or 0
        tok = (okT and type(tok) == "string" and not secret(tok)) and tok or P.PTOK[pt]
        if tok ~= o.ptok or not o.pw.rgb then
            local cc = (tok and P.POWER[tok]) or P.POWER.MANA
            P.paint(o.pw, cc[1], cc[2], cc[3])
            o.ptok = tok
        end
        -- (again each poll: nothing the game does to the textures leaves them grey)
        for _, b in ipairs({ o.hp, o.pw }) do
            if b.rgb then pcall(function() b.fill:SetVertexColor(b.rgb[1], b.rgb[2], b.rgb[3]) end) end
        end
        pcall(function() o.pw:SetMinMaxValues(0, UnitPowerMax("pet", pt)) end)
        pcall(function() o.pw:SetValue(UnitPower("pet", pt)) end)
        -- happiness: only a plain answer (1 unhappy, 2 content, 3 happy); a secret or none: no face
        local okH, hv = pcall(function()
            if type(C_PetInfo) == "table" and type(C_PetInfo.GetPetHappiness) == "function" then return C_PetInfo.GetPetHappiness() end
            return GetPetHappiness()
        end)
        local h = okH and num(hv) or nil
        local uv = h and P.PET_HAPPY_U[h]
        if uv then o.happy:SetTexCoord(uv[1], uv[2], 0, P.PET_HAPPY_V); o.happy:Show() else o.happy:Hide() end
        if (uv ~= nil) ~= (P.petHappyOn == true) then P.petHappyOn = uv ~= nil; P.petLayout(P.petDrop, true) end   -- prd13: re-centred
        x.dead, x.ptok, x.happy, x.icon = dead or nil, tok, uv and h or nil, o.icon ~= nil or nil
    end
    -- out of combat on the curve route: the strip's alpha the pet's own UnitHealthPercent through the same curve; its OWN bad flag (a
    -- refused pet call - no pet, a client without - never turns the player's curve off); otherwise plainly shown (it fades with the HUD)
    -- (review of prd11, 2026-11-11: the tick runs this BEFORE the poll that hides the strip, so a pet dismissed / despawned had its
    -- curve asked with no pet and the refusal latched for the session - the strip then held at 1 out of combat on the curve route.
    -- Now: presence again first (no pet: hidden, no room, NOTHING latched); a refusal latches only while a pet plainly exists; once
    -- latched, the strip takes the PLAYER's curve (the bars' own layer: it fades with the HUD), else plainly 1)
    function P.petHide()
        local L = P.petL
        if L and L:IsShown() then L:Hide() end
        P.petLayout(0)
    end
    function P.petCurve(on)
        local L = P.petL
        if not (L and L:IsShown()) then return end
        if on and P.curve then
            local has = false
            if P.wantedPet() then has = P.petHas() end
            if not has then P.petHide(); P.petSrc = "gone"; return end
            if not P.petCurveBad then
                local ok, err = pcall(function()
                    local a = UnitHealthPercent("pet", false, P.curve)
                    if a == nil then error("no value") end
                    L:SetAlpha(a)
                end)
                if ok then P.petSrc = "curve"; return end
                if P.petHas() then P.petCurveBad = tostring(err):sub(1, 80) end
            end
            if P.petCurveBad and not P.curveBad and pcall(function()
                local a = UnitHealthPercent("player", false, P.curve)
                if a == nil then error("no value") end
                L:SetAlpha(a)
            end) then P.petSrc = "player"; return end
        end
        pcall(function() L:SetAlpha(1) end)
        P.petSrc = "plain"
    end

    -- ---------------------------------------------------------------- prd14: Party mode's pick on you / your pet
    -- Party mode's pick when it is you or your pet (PadMap's export: on, box.u - its own secure attributes, plain), else nil
    function P.partyPick()
        local p = type(WC.padMap) == "table" and WC.padMap.party or nil
        if type(p) ~= "table" or type(p.on) ~= "function" or type(p.box) ~= "function" then return nil end
        local okO, on = pcall(p.on)
        if not okO or on ~= true then return nil end
        local okB, b = pcall(p.box)
        local who = okB and type(b) == "table" and b.u or nil
        if who == "player" or who == "pet" then return who end
        return nil
    end
    -- a gold box of four edges on P.f's own top layer (never on the curve's P.g, never under the pet strip's alpha). prd14 review (the
    -- user's ask, 2026-11-11: the box round the bars / the strip): ONE mouse-less area frame per box spans what is boxed (its two corners
    -- may come from two regions: a frame's TOPLEFT and BOTTOMRIGHT are independent), and all four edges hang on that one area - no edge
    -- takes its two points from two regions (those clashed on the pet: the portrait's top against the bars' top, 46 px apart in x)
    function P.pickRing(host)
        local r = { t = {} }
        r.area = CreateFrame("Frame", nil, host)
        r.area:EnableMouse(false)
        for i = 1, 4 do
            r.t[i] = host:CreateTexture(nil, "OVERLAY", nil, 7)
            r.t[i]:SetColorTexture(P.PICK_GOLD[1], P.PICK_GOLD[2], P.PICK_GOLD[3], 0.95)
            r.t[i]:Hide()
        end
        -- the area: TOPLEFT on tl (+ offsets), BOTTOMRIGHT on br (+ offsets); the edges on the area alone
        function r.place(tl, tlx, tly, br, brx, bry)
            local t, a, px = r.t, r.area, u(P.PICK_PX)
            a:ClearAllPoints()
            a:SetPoint("TOPLEFT", tl, "TOPLEFT", tlx, tly)
            a:SetPoint("BOTTOMRIGHT", br, "BOTTOMRIGHT", brx, bry)
            for i = 1, 4 do t[i]:ClearAllPoints() end
            t[1]:SetPoint("TOPLEFT", a, "TOPLEFT", 0, 0); t[1]:SetPoint("TOPRIGHT", a, "TOPRIGHT", 0, 0); t[1]:SetHeight(px)
            t[2]:SetPoint("BOTTOMLEFT", a, "BOTTOMLEFT", 0, 0); t[2]:SetPoint("BOTTOMRIGHT", a, "BOTTOMRIGHT", 0, 0); t[2]:SetHeight(px)
            t[3]:SetPoint("TOPLEFT", a, "TOPLEFT", 0, 0); t[3]:SetPoint("BOTTOMLEFT", a, "BOTTOMLEFT", 0, 0); t[3]:SetWidth(px)
            t[4]:SetPoint("TOPRIGHT", a, "TOPRIGHT", 0, 0); t[4]:SetPoint("BOTTOMRIGHT", a, "BOTTOMRIGHT", 0, 0); t[4]:SetWidth(px)
        end
        function r.show(on)
            for i = 1, 4 do if on then r.t[i]:Show() else r.t[i]:Hide() end end
            r.on = on
        end
        return r
    end
    -- built with the bars: one box round the player's health + power (their PRD frames: 3 texels up / left, 7 right, 8 down; the two bars
    -- share x: both TOP-centred, P.W wide), one round the pet's whole strip row: the portrait's left to the bars' right rim (7 texels at
    -- the PLAYER's scale, the pet's frame uses it: P.petBuild o.k), and in y the row itself (P.petGeo: the row's top and bottom are the
    -- taller of the portrait and the bars' unit with its rims, whichever that is)
    function P.pickBuild()
        if P.pick or not P.f then return P.pick end
        local host = CreateFrame("Frame", nil, P.f)
        host:SetAllPoints(P.f)
        host:EnableMouse(false)
        pcall(function() host:SetFrameLevel(P.hp:GetFrameLevel() + 30) end)
        local k = u(P.HH / 8)
        local pad = u(P.PICK_PAD)
        local o = { host = host, you = P.pickRing(host), pet = P.pickRing(host) }
        o.you.place(P.hp, -(3 * k + pad), 3 * k + pad, P.pw, 7 * k + pad, -(8 * u(P.PH / 8) + pad))
        if P.pet and P.pet.ring and P.pet.unit then
            local g = P.petGeo()
            -- the ring sits g.iconTop under the row's top, the unit (health's top) g.unitTop; the row is g.row tall
            o.pet.place(P.pet.ring, -pad, u(g.iconTop) + pad, P.pet.unit, 7 * k + pad, -(u(g.row - g.unitTop - g.unit) + pad))
        end
        P.pick = o
        return o
    end
    -- ---------------------------------------------------------------- prd15: Party mode's [me][pet] row and Hub's grid under it
    -- Party mode on (PadMap's on, plainly true) outside a raid (raids: unchanged)
    function P.partyModeOn()
        local p = type(WC.padMap) == "table" and WC.padMap.party or nil
        if type(p) ~= "table" or type(p.on) ~= "function" then return false end
        local okO, on = pcall(p.on)
        if not okO or on ~= true then return false end
        local okR, r = pcall(IsInRaid)
        if okR and r == true then return false end
        return true
    end
    -- the HUD's place for Hub's party grid, in UI units from UIParent's CENTER (the HUD's own height and width settings and its scale;
    -- never its show / hide slide): x = the HP bar's left, top = the bars' top, y = the room's top under the bars (and the combo pips),
    -- PET_GAP + PARTY_GAP under the power bar (the first row's name clear of the power bar's lip)
    function P.partyGeo()
        if not P.wd then pcall(P.loadSize) end
        local s = (num(P.sc) or P.S_DEF) / 100
        local y = num(P.y) or P.loadY()
        local cpx = (P.cp and P.cp:IsShown()) and (P.CP_GAP + P.CPH) or 0
        local w = num(P.W) or P.W_DEF
        y = y + P.liftFor(P.partyModeOn())                              -- (the guard's lift the block is going to: Hub fades its rows to it)
        -- (h: the two bars' height, for the solo tab beside the [me][pet] row)
        return { x = (u(num(P.x) or 0) - u(w) / 2) * s, top = u(y) * s, h = u(P.HH + P.BAR_GAP + P.PH) * s,        -- prd18: the HUD's x
                 y = u(y - (P.HH + P.BAR_GAP + P.PH + cpx + P.PET_GAP + P.PARTY_GAP)) * s, s = s }
    end
    -- prd15 THE ON-SCREEN GUARD (the user, 2026-11-12: "KEEP IT ON SCREEN"): in Party mode, the lift (spec px, up) that keeps the block -
    -- the HUD's bars (the combo pips included), and Hub's rows under them (P.partyRoomPx, their rings in it) or the bars' lip while solo -
    -- LIFT_MARGIN px (scaled) clear of the screen's bottom edge; 0 when it fits and always out of Party mode (nothing moves then)
    function P.liftFor(on)
        if not on then return 0 end
        local s = (num(P.sc) or P.S_DEF) / 100
        local y = num(P.y) or P.loadY()
        local okH, hh = pcall(function() return UIParent:GetHeight() end)
        hh = (okH and num(hh) and hh > 0) and hh or 768
        local cpx = (P.cp and P.cp:IsShown()) and (P.CP_GAP + P.CPH) or 0
        local room = num(P.partyRoomPx) or 0
        local block = P.HH + P.BAR_GAP + P.PH + cpx + ((room > 0) and (P.PET_GAP + P.PARTY_GAP + room) or P.HH)
        local one = u(1) * s
        if one <= 0 then return 0 end
        local bottom = (y - block) * one                                -- UI units from UIParent's CENTER
        local need = (-hh / 2 + u(P.LIFT_MARGIN)) - bottom
        if need <= 0 then return 0 end
        return math.floor(need / one * 100 + 0.999) / 100
    end
    -- Hub's grid's height (UI units; 0 = none): the room under the bars (P.f's height; the aura row stands above the bars in Party mode)
    function P.partyRoom(ui)
        local s = (num(P.sc) or P.S_DEF) / 100
        local one = u(1) * s
        local px = (num(ui) and ui > 0 and one > 0) and ui / one or 0
        if px == P.partyRoomPx then return end
        P.partyRoomPx = px
        if P.f then P.petLayout(P.dropFor(), true) end
    end
    -- the move of the pet strip AND the aura row (prd15: both follow Party mode): fade out (PET_FADE_S), move while unseen, fade in; with
    -- neither shown they move at once. Plain alpha on our own frames (the strip's layer holds over the curve's alpha for those 0.4 s; the
    -- aura icons back at 1 after); the pick box waits for the strip (P.pickStep)
    function P.aurasShown()
        -- prd20: the container's host (its buttons are the game's: never read)
        if P.box then return P.box.host:IsShown() == true end
        for _, list in ipairs({ P.buffs or {}, P.debuffs or {} }) do
            for _, o in ipairs(list) do if o.f and o.f:IsShown() then return true end end
        end
        return false
    end
    function P.moveAlpha(a)
        local L = P.petL
        if L and L:IsShown() then pcall(function() L:SetAlpha(a) end) end
        if P.box then pcall(function() P.box.host:SetAlpha(a) end) end      -- prd20: the container fades with the move too
        for _, list in ipairs({ P.buffs or {}, P.debuffs or {} }) do
            for _, o in ipairs(list) do if o.f then pcall(function() o.f:SetAlpha(a) end) end end
        end
    end
    function P.petMoveStep(dt)
        local on = P.partyOn == true
        local want = on and "beside" or "under"
        local L = P.petL
        local lift = P.liftFor(on)
        local lifts = math.abs((num(P.lift) or 0) - lift) > 0.01
        local moved = P.petSide ~= want or P.auraUp ~= on or lifts
        if lifts then P.hudFade = true end                               -- (the bars themselves move: they fade too)
        local function swap()
            P.petSide, P.auraUp = want, on
            if P.f then P.petLayout(P.dropFor(), true) end
            P.cache = nil                                                -- (the aura row drawn again at its new place)
            if math.abs((num(P.lift) or 0) - lift) > 0.01 then P.lift = lift; P.place() end
        end
        local hudShown = P.hudFade and P.f ~= nil and P.f:IsShown() and (num(P.alpha) or 0) > 0
        if not ((L and L:IsShown()) or P.aurasShown() or hudShown) then
            if P.petFadeA then P.petFadeA = nil; P.moveAlpha(1) end
            P.hudFade = nil
            if moved then swap() end
            return
        end
        local a = P.petFadeA or 1
        if moved then
            a = a - (num(dt) or 0) / P.PET_FADE_S
            if a <= 0 then
                a = 0
                swap()
                pcall(P.drawAuras, true)
            end
        elseif a < 1 then
            a = math.min(1, a + (num(dt) or 0) / P.PET_FADE_S)
        end
        if a >= 1 then
            if P.petFadeA then P.petFadeA = nil; P.moveAlpha(1) end
            P.hudFade = nil
        else
            P.petFadeA = a
            P.moveAlpha(a)
        end
    end
    -- the boxes follow the pick (the pet's only while its strip shows); P.pickWho = what is boxed, for the tests
    function P.pickStep(who)
        local o = P.pick
        if not o then return end
        local petShown = P.petL ~= nil and P.petL:IsShown()
        o.you.show(who == "player")
        o.pet.show(who == "pet" and petShown and P.petFadeA == nil)      -- prd15: back with the strip, at its new place
        P.pickWho = (who == "player" and "player") or ((who == "pet" and petShown) and "pet") or nil
    end

    -- ---------------------------------------------------------------- when it shows
    function P.wanted()
        -- hub17: the bar's own switch (npWant.prd) once set, else HUD mode's bundle (npWant.hud)
        local npw = type(WC.npWant) == "table" and WC.npWant or {}
        local on = npw.prd
        if on == nil then on = npw.hud == true end
        if not on then return false, "hud off" end
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
        -- prd14: Party mode's pick on you / your pet (read every PICK_S)
        if not P.pickAt or t - P.pickAt >= P.PICK_S or t < P.pickAt then P.pickAt = t; P.partyWho = P.partyPick() end
        P.partyOn = P.partyModeOn()                                     -- prd15: every frame (the lift and Hub's grid read it live)
        -- prd15: Party mode on holds the HUD up too (it is the block's [me] row: the grid hangs under it)
        local picked = P.partyWho ~= nil or P.partyOn == true
        -- prd18: while the layout editor is open the HUD is held up too (why "layout": the piece being moved is seen)
        local editing = type(WC.lay) == "table" and WC.lay.editing == true
        picked = picked or editing
        local fight = P.fight()
        local hurt, src = P.hurt()
        if fight then P.lastFight = t end
        -- out of combat, past the fight's hold: the engine's curve, when it takes (the bars' layer shows exactly while hurt)
        local settled = not fight and (not P.lastFight or t - P.lastFight >= P.HOLD_S)
        local rest, rwhy = P.powerRest(t, fight)
        P.prest = rwhy
        local powerOff = rest == false and not fight
        if picked then
            pcall(function() P.g:SetAlpha(1) end)                       -- prd14: the boxed bars plainly shown
            if P.petL then pcall(function() P.petL:SetAlpha(1) end) end
            P.petSrc = "party"
        elseif powerOff then
            pcall(function() P.g:SetAlpha(1) end)                       -- (the power keeps it up: the health curve may not hide it)
            P.petCurve(false)
        elseif settled and P.curveSet() then
            hurt, src = true, "curve"                                    -- (our timing stays on: the layer itself shows or not)
            P.petCurve(true)                                             -- prd11: the pet's strip by its own health, its own flag
        else
            pcall(function() P.g:SetAlpha(1) end)
            P.petCurve(false)
        end
        P.src = src
        local on = fight or hurt == true or powerOff or picked
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
        P.why = fight and "fight" or (editing and "layout") or (picked and "party") or (powerOff and "power") or (src == "curve" and "curve") or (hurt and "hurt") or (P.alpha > 0 and "fading") or "full"
        if P.alpha > 0 then
            if not P.f:IsShown() then P.f:Show() end
            P.f:SetAlpha(P.alpha * P.REST * ((P.hudFade and P.petFadeA) or 1))   -- prd15: the HUD fades too while its lift changes
            P.pollAt = P.pollAt or 0
            if t - P.pollAt >= P.POLL_S then P.pollAt = t; P.values() end
            P.drawAuras(true)                                -- (a) test45: whenever the bar shows (a HoT after the fight too)
        elseif P.f:IsShown() then
            P.f:SetAlpha(0); P.f:Hide()
        end
        P.petMoveStep(dt)                                               -- prd15: the pet strip's move (after its alpha for this frame)
        P.pickStep(P.partyWho)                                          -- prd14: after the poll (the pet strip's shown state is fresh)
        P.shown = P.alpha > 0
    end

    local ev = CreateFrame("Frame")
    for _, e in ipairs({ "PLAYER_REGEN_DISABLED", "PLAYER_REGEN_ENABLED", "PLAYER_ENTERING_WORLD" }) do pcall(function() ev:RegisterEvent(e) end) end
    -- prd11: the bars' values heard for the player AND the pet; the casts (the cost, the spend) and the auras (the row is the player's)
    -- for the player only
    for _, e in ipairs({ "UNIT_HEALTH", "UNIT_MAXHEALTH", "UNIT_POWER_UPDATE", "UNIT_POWER_FREQUENT", "UNIT_MAXPOWER", "UNIT_DISPLAYPOWER",
                         "UNIT_HEAL_PREDICTION", "UNIT_ABSORB_AMOUNT_CHANGED" }) do
        pcall(function() ev:RegisterUnitEvent(e, "player", "pet") end)
    end
    for _, e in ipairs({ "UNIT_AURA", "UNIT_SPELLCAST_START", "UNIT_SPELLCAST_CHANNEL_START", "UNIT_SPELLCAST_STOP", "UNIT_SPELLCAST_FAILED",
                         "UNIT_SPELLCAST_SUCCEEDED", "UNIT_SPELLCAST_INTERRUPTED", "UNIT_SPELLCAST_CHANNEL_STOP", "UNIT_PET" }) do
        pcall(function() ev:RegisterUnitEvent(e, "player") end)
    end
    for _, e in ipairs({ "PET_UI_UPDATE", "UNIT_HAPPINESS" }) do pcall(function() ev:RegisterEvent(e) end) end
    ev:SetScript("OnEvent", function(_, e, unit, _, spellID)
        -- prd11: only the player's own casts and auras are ours (a pet's never sets the cost or the spend)
        if unit ~= nil and unit ~= "player" and (e == "UNIT_AURA" or e:sub(1, 15) == "UNIT_SPELLCAST_") then return end
        if e == "UNIT_PET" or e == "PET_UI_UPDATE" then
            if P.pet then P.pet.icon = nil end                          -- (a new pet: its portrait again)
            -- (review of prd11: gone at once, before any curve is asked of it)
            if P.petL and P.petL:IsShown() and not (P.wantedPet() and (P.petHas())) then P.petHide() end
            if P.f and P.shown then P.pollAt = 0 end
        elseif e == "UNIT_SPELLCAST_START" or e == "UNIT_SPELLCAST_CHANNEL_START" then
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
-- never hidden, cleared or moved: whatever reads a tooltip's text still reads it (Interact's
-- own dimTip only sets alpha too). The choice lives only in our saved data (WowCompanionDB.hideTips); off, the alpha is 1 again. No CVar.
-- tt2 (the user, 2026-11-10: "when tooltips are off, there are some places that they noticably still appear ... when talking to a vendor the
-- 'Equiped Item' tooltip kind of flashes and rehides ... when getting a buff, the tooltip for it opens breifly"): the named list's OnShow /
-- OnUpdate zeroing let a tooltip the game set visible AGAIN (SetAlpha 1 after its show: the comparison's "Equipped") draw for a frame, and a
-- tooltip not in the list (the buff's) drew in full. Now EVERY tooltip of the game's tooltip type is held at 0: post-hooks on the type's
-- shared Show and SetAlpha (hooksecurefunc: the game's call runs as it was, then ours), each tooltip zeroed remembered so the switch's off
-- gives it its alpha back.
-- tt3 (the user on tt2: "equiped tooltip is flashing"; a 60 fps recording of the top screen at a vendor: the "Equipped" comparison drew for
-- 1-2 frames about once a second): the game refreshes a tooltip by filling it again (SetOwner, SetMerchantItem, SetUnitAura ...), and that
-- puts its alpha back INSIDE the engine - no SetAlpha call to hook - so only the next frame's OnUpdate caught it. Now every filler is
-- post-hooked too: every method of the tooltip type named Set... (not SetAlpha: that one has its own rule), Show, ClearLines, AddLine,
-- AddDoubleLine, AppendText - each tooltip is held at 0 the moment it is (re)filled, before the frame draws.
-- tt5 (#476, a player on 1.0.33: "im also seeing a tooltip for buffs appear in the top right corner when a buff is activated, and can
-- make it reappear when hovering over it with mouse"; the user approved the fix 2026-11-18 for test build 1489): with the game's
-- tooltips ON, Hub's riders hide the game's buffs by ALPHA only (BuffFrame / DebuffFrame at 0, top right), so the controller-mode auto
-- tooltip at every buff gained (BuffFrameTooltip, anchored to the invisible buff) and the hover on the invisible buff (GameTooltip owned by
-- its button) still drew there - tt4 held them only with tooltips off. Now, WHILE Hub hides the game's buffs (hub66 WC.gameBuffsHidden),
-- the tooltips OF those buffs are held at 0 whatever the setting: BuffFrameTooltip, the consolidated list, and any tooltip whose owner
-- lies inside BuffFrame / DebuffFrame / DeadlyDebuffFrame (GetOwner / GetParent reads). The game's buffs shown: nothing changes; a frame
-- held this way gets its alpha back at its next show. Every other tooltip follows the setting as before. Alpha only, no CVar.
-- tt4 (the user, 2026-11-16: "tooltips for buffs are still popping up on the top screen even though they are off"). Two holes, read in
-- this client's own UI source (Blizzard_BuffFrame, Blizzard_AuraContainer, Blizzard_UnitFrame):
--   1. THE BUFF'S OWN TOOLTIP FRAME. In controller mode the game pops a tooltip up for 4 s at every buff gained or refreshed
--      (BuffFrameMixin:ShowNextAuraForTooltip, while GamepadShowAutoAuraTooltip is on) on BuffFrameTooltip: a tooltip frame of its own,
--      not GameTooltip, anchored to the buff's icon (invisible here: Hub's riders). It was not in the named list, so nothing held it at
--      its show or at each frame - only tt2 / tt3's hooks on the type's shared method table, which run after a Lua call and so miss an
--      alpha the engine puts back with none (tt3's own measurement); GameTooltip's per-frame hold caught that a frame later, nothing
--      caught it on BuffFrameTooltip, so the buff's tooltip drew for its whole 4 s. Now every tooltip frame we reach is ADOPTED:
--      BuffFrameTooltip and GameSmallHeaderTooltip join the list, and any GameTooltip-type frame a type hook meets is adopted the next
--      frame. An adopted frame is held at its OnShow, at EVERY frame (OnUpdate), at its layout (OnSizeChanged) and after each of its
--      own fillers (post-hooks on the frame itself: they do not depend on the shared table reaching it).
--   2. A FORBIDDEN TOOLTIP. The target / focus / boss frames' buffs and debuffs (TargetFrameAuraButtonTemplate, an aura container in the
--      secure environment) show their tooltip on AuraButtonTooltip, which is forbidden and hidden from every add-on
--      (Blizzard_AuraButtonTooltip.xml): no alpha of ours can reach it. And Hub hides the target frame by alpha only, so its invisible
--      buffs still take the cursor's hover. While tooltips are off, a frame of OUR OWN lies over each shown aura container: MOTION only
--      (SetMouseMotionEnabled true, SetMouseClickEnabled false - TapPad's sink is the same split the other way round), so the hover
--      stops on it and the buff never gets its OnEnter, while a click still goes to whatever is under it. MEDIUM strata, level 1:
--      above the unit frames (LOW), under every window. Nothing of the game's is touched; the aura container is only read.
--   A hold never compares a value read back from the tooltip (on this client a frame's alpha can be a secret aspect). WC.tips.stats()
--   counts what each hold caught (Interact's proof.tipc.st), so a device run says which path a tooltip came back on.
-- The bottom screen's tooltips are read through C_TooltipInfo (Interact, Bags, Auras.lua), never from a tooltip frame: unchanged.
-- Review of tt4 (the same report): two more buff tooltips that are NOT of the tooltip type, so no type hook ever met them:
--   PartyMemberBuffTooltip (Blizzard_UnitFrame partyframe.xml: a plain frame, TOOLTIP strata, no parent): the pet frame's and every
--   party member's buff / debuff icons, shown at its OnEnter (PetFrameMixin / PartyMemberFrameMixin:OnEnter -> UpdateTooltip). Hub
--   hides those frames by alpha only (they still take the cursor where they stand; Hub's own unit frames take no mouse), so the cursor
--   there drew it in full. And the consolidated buffs' list (BuffFrame.ConsolidatedBuffs.Tooltip, a plain frame, TOOLTIP
--   strata: hovering the consolidation button while the game's buffs are shown). Both are adopted by name / path like the rest, and
--   an adopted frame is held by its own hooks whatever its type (the type check guards the SHARED table only). stats() also says how
--   the target frame's shield lies (tm: "anchor" / "rect"), since that container's size is a secret value (secretwrap at its layout).
-- tt6 (#558 / #476, the user 2026-11-19: "the tt4 shield over aura containers applies"): the top screen's auras are now the game's own
-- aura containers (Trail prd20 / Hub uf26, WC.abox), whose buttons show the FORBIDDEN AuraButtonTooltip as the target frame's did. In a
-- fight the game hides it itself (SetHideTooltipInCombat); out of one, while tooltips are off, the same motion-only shield lies over each
-- container's host (WC.tips.addBox: OUR plain frame, so it is anchored to it, never the container, whose size is a secret), in the
-- host's strata and above its frame level (the buttons are its descendants), and comes down when tooltips are on or the host hides.
WC.tips = { V = "tt6", FRAMES = { "GameTooltip", "ItemRefTooltip", "ShoppingTooltip1", "ShoppingTooltip2", "ItemRefShoppingTooltip1",
                                  "ItemRefShoppingTooltip2", "EmbeddedItemTooltip", "NamePlateTooltip", "GameNoHeaderTooltip",
                                  "BuffFrameTooltip", "GameSmallHeaderTooltip", "PartyMemberBuffTooltip" },
            -- review of tt4: tooltips with no global name, by their path from one
            PATHS = { { "BuffFrame", "ConsolidatedBuffs", "Tooltip" } },
            -- tt4: a tooltip's own fillers, post-hooked on each adopted frame (SetAlpha has its own rule)
            FILL = { "Show", "SetOwner", "ClearLines", "AddLine", "AddDoubleLine", "AppendText", "SetText", "SetShown", "SetFrameLevel",
                     "SetPoint" },
            -- tt4: the unit frames whose auras show the forbidden AuraButtonTooltip (TargetFrameTemplate's aura container)
            UNITS = { "TargetFrame", "FocusFrame", "Boss1TargetFrame", "Boss2TargetFrame", "Boss3TargetFrame", "Boss4TargetFrame",
                      "Boss5TargetFrame" },
            hooked = {}, on = false, zeroed = setmetatable({}, { __mode = "k" }), busy = false, typeHooked = false,
            adopted = {}, list = {}, named = {}, queue = {}, shields = {}, ticking = false, boxes = {}, boxShields = {},
            st = { show = 0, upd = 0, size = 0, fill = 0, type = 0, shield = 0 } }
do
    local T = WC.tips
    function T.wanted() return type(WowCompanionDB) == "table" and WowCompanionDB.hideTips == true end
    local function plain(v)
        if type(WC.isPlain) == "function" then return WC.isPlain(v) end
        if type(issecretvalue) == "function" then local ok, s = pcall(issecretvalue, v); return ok and s == false end
        return true
    end
    local function nameOf(fr)
        local ok, n = pcall(fr.GetName, fr)
        if ok and type(n) == "string" and plain(n) then return n end
        return "?"
    end
    -- tt5 (#476): a tooltip of the game's buffs while Hub hides them (alpha 0): BuffFrameTooltip, the consolidated list, or any tooltip
    -- whose owner lies inside the buff frames (reads only: GetOwner / GetParent, a few steps up)
    T.BUFF_ROOTS = { "BuffFrame", "DebuffFrame", "DeadlyDebuffFrame" }
    function T.buffTip(fr)
        if not (type(WC.gameBuffsHidden) == "function" and WC.gameBuffsHidden() == true) then return false end
        if rawequal(fr, _G.BuffFrameTooltip) then return true end
        local bf = _G.BuffFrame
        local cb = type(bf) == "table" and type(bf.ConsolidatedBuffs) == "table" and bf.ConsolidatedBuffs.Tooltip or nil
        if cb ~= nil and rawequal(fr, cb) then return true end
        if type(fr.GetOwner) ~= "function" then return false end
        local okO, o = pcall(fr.GetOwner, fr)
        for _ = 1, 8 do
            if not okO or type(o) ~= "table" then return false end
            for _, n in ipairs(T.BUFF_ROOTS) do if _G[n] ~= nil and rawequal(o, _G[n]) then return true end end
            if type(o.GetParent) ~= "function" then return false end
            okO, o = pcall(o.GetParent, o)
        end
        return false
    end
    -- held now: the tooltips setting, or (tt5) a tooltip of the hidden game buffs
    function T.holds(fr)
        if T.on then return true end
        local ok, r = pcall(T.buffTip, fr)
        return ok and r == true
    end
    -- the hold itself: alpha 0, nothing else. `why` counts a catch (the tooltip was drawn above 0 when the hold came)
    local function zero(fr, why)
        if T.busy then return end
        if not T.holds(fr) then
            -- tt5: a frame held for the hidden buffs gets its alpha back at its next show once they are shown (tooltips on)
            if T.buffHeld and T.buffHeld[fr] and not T.on then
                T.buffHeld[fr] = nil
                T.busy = true; pcall(fr.SetAlpha, fr, 1); T.busy = false
            end
            return
        end
        if not T.on then T.buffHeld = T.buffHeld or setmetatable({}, { __mode = "k" }); T.buffHeld[fr] = true end
        if why then
            local ok, a = pcall(fr.GetAlpha, fr)
            if ok and type(a) == "number" and plain(a) and a > 0 then
                T.st[why] = (T.st[why] or 0) + 1
                if why == "upd" or why == "size" then T.st.last = nameOf(fr) end
            end
        end
        T.busy = true
        if pcall(fr.SetAlpha, fr, 0) then T.zeroed[fr] = true end
        T.busy = false
    end
    -- tt2: a GameTooltip-type frame, at a filler or at any SetAlpha above 0 (a guard: our own SetAlpha(0) comes back through the hook)
    local function hold(fr, a, why)
        if T.busy or not T.holds(fr) then return end
        if type(a) == "number" and plain(a) and a <= 0 then return end   -- (a secret alpha is held: never compared)
        if not T.adopted[fr] then
            -- only a TOOLTIP (the hooked methods are its type's; a shared table must never dim any other frame). An adopted frame
            -- (review of tt4) is held whatever its type: only its own hooks reach here for it
            local okT, ty = pcall(fr.GetObjectType, fr)
            if not okT or ty ~= "GameTooltip" then return end
            T.queue[fr] = true; T.wake()                                 -- tt4: adopted at the next frame, never inside the game's call
        end
        zero(fr, why)
    end
    -- tt4: ADOPT a tooltip frame: held at its show, every frame, its layout and after its own fillers. Once per frame; never a forbidden one
    function T.adopt(fr, named)
        if type(fr) ~= "table" or T.adopted[fr] then return end
        local okF, forb = pcall(fr.IsForbidden, fr)
        if okF and forb == true then return end
        if not named then
            local okT, ty = pcall(fr.GetObjectType, fr)
            if not okT or ty ~= "GameTooltip" then return end
        end
        T.adopted[fr] = true
        T.list[#T.list + 1] = fr
        if type(fr.HookScript) == "function" then
            pcall(fr.HookScript, fr, "OnShow", function(self) zero(self, "show") end)
            pcall(fr.HookScript, fr, "OnUpdate", function(self) zero(self, "upd") end)
            pcall(fr.HookScript, fr, "OnSizeChanged", function(self) zero(self, "size") end)
        end
        if type(hooksecurefunc) == "function" then
            if type(fr.SetAlpha) == "function" then pcall(hooksecurefunc, fr, "SetAlpha", function(self, a) hold(self, a, "fill") end) end
            for _, k in ipairs(T.FILL) do
                if type(fr[k]) == "function" then pcall(hooksecurefunc, fr, k, function(self) hold(self, nil, "fill") end) end
            end
        end
        if T.on then
            local ok, s = pcall(fr.IsShown, fr)
            if ok and s == true then zero(fr) end
        end
    end
    -- hook the tooltips the client has (once each; a frame made later is picked up at the next apply)
    function T.hook()
        for _, n in ipairs(T.FRAMES) do
            local fr = _G[n]
            if type(fr) == "table" and not T.hooked[n] then
                T.hooked[n] = true
                T.named[#T.named + 1] = fr
                T.adopt(fr, true)
            end
        end
        -- review of tt4: the unnamed ones, read down their path (only reads)
        for _, p in ipairs(T.PATHS) do
            local key = table.concat(p, ".")
            if not T.hooked[key] then
                local ok, fr = pcall(function()
                    local o = _G[p[1]]
                    for i = 2, #p do if type(o) ~= "table" then return nil end; o = o[p[i]] end
                    return o
                end)
                if ok and type(fr) == "table" and type(fr.SetAlpha) == "function" then
                    T.hooked[key] = true
                    T.named[#T.named + 1] = fr
                    T.adopt(fr, true)
                end
            end
        end
    end
    function T.hookType()
        if T.typeHooked or type(GameTooltip) ~= "table" then return end
        local mt = getmetatable(GameTooltip)
        local idx = type(mt) == "table" and mt.__index
        if type(idx) ~= "table" or type(hooksecurefunc) ~= "function" then return end
        T.typeHooked = true
        pcall(hooksecurefunc, idx, "SetAlpha", function(fr, a) hold(fr, a, "type") end)
        local names = { Show = true, ClearLines = true, AddLine = true, AddDoubleLine = true, AppendText = true }
        for k, v in pairs(idx) do
            if type(k) == "string" and type(v) == "function" and k:sub(1, 3) == "Set" and k ~= "SetAlpha" then names[k] = true end
        end
        for k in pairs(names) do
            if type(idx[k]) == "function" then pcall(hooksecurefunc, idx, k, function(fr) hold(fr, nil, "type") end) end
        end
    end

    -- ---------------------------------------------------------------- tt4: the hover shields over the forbidden tooltip's buffs
    -- the unit frame's aura container (TargetFrameMixin:GetAuraContainer's path), read once and kept; a miss is tried again after 2 s
    local boxOf, boxTry = {}, {}
    local function auraBox(n)
        local c = boxOf[n]
        if c then return c end
        local now = type(GetTime) == "function" and GetTime() or 0
        if boxTry[n] and now - boxTry[n] < 2 then return nil end
        boxTry[n] = now
        local uf = _G[n]
        if type(uf) ~= "table" then return nil end
        local ok, v = pcall(function() return uf.TargetFrameContent.TargetFrameContentContextual.Auras end)
        if ok and type(v) == "table" then boxOf[n] = v; return v end
    end
    local function newShield()
        local f = CreateFrame("Frame", nil, UIParent)
        -- motion only, or nothing: a frame that takes clicks would eat a touch meant for the frame under it
        if type(f.SetMouseMotionEnabled) ~= "function" or type(f.SetMouseClickEnabled) ~= "function" then return false end
        local ok = pcall(function()
            f:SetFrameStrata("MEDIUM")
            f:SetFrameLevel(1)
            f:SetMouseClickEnabled(false)
            f:SetMouseMotionEnabled(true)
            f:Hide()
        end)
        return ok and f or false
    end
    -- lay [f] over [c]: anchored to it (it follows by itself), else its rectangle copied in our parent's units each tick
    local function place(e, c)
        if e.c == c and e.mode == "anchor" then return true end
        local f = e.f
        if pcall(function() f:ClearAllPoints(); f:SetAllPoints(c) end) then e.c, e.mode = c, "anchor"; return true end
        local ok, l, b, w, h = pcall(c.GetRect, c)
        local okS, sc = pcall(c.GetEffectiveScale, c)
        local okU, us = pcall(UIParent.GetEffectiveScale, UIParent)
        if not (ok and okS and okU) then return false end
        for _, v in ipairs({ l, b, w, h, sc, us }) do
            if type(v) ~= "number" or not plain(v) then return false end
        end
        if w <= 0 or h <= 0 or us <= 0 then return false end
        local k = sc / us
        local placed = pcall(function()
            f:ClearAllPoints()
            f:SetPoint("BOTTOMLEFT", UIParent, "BOTTOMLEFT", l * k, b * k)
            f:SetSize(w * k, h * k)
        end)
        if placed then e.c, e.mode = c, "rect" end
        return placed
    end
    local function shieldUp(e, up)
        if (e.up == true) == up or not e.f then return end              -- (a new one is down: its first "down" counts nothing)
        e.up = up
        pcall(up and e.f.Show or e.f.Hide, e.f)
        T.st.shield = T.st.shield + (up and 1 or -1)
    end
    function T.shieldTick()
        for _, n in ipairs(T.UNITS) do
            local e = T.shields[n]
            local c = T.on and auraBox(n) or nil
            local want = false
            if c then
                local ok, v = pcall(c.IsVisible, c)
                if ok then
                    if plain(v) then want = (v == true) else want = true end   -- a secret answer: the hover stays blocked
                end
            end
            if want then
                if not e then e = { f = newShield() }; T.shields[n] = e end
                if e.f and place(e, c) then shieldUp(e, true) else shieldUp(e, false) end
            elseif e then
                shieldUp(e, false)
            end
        end
        -- tt6: the shields over our aura containers' hosts (plain frames of ours: anchored, raised over the game's buttons in them)
        for key, host in pairs(T.boxes) do
            local e = T.boxShields[key]
            local want = false
            if T.on then
                local ok, v = pcall(host.IsVisible, host)
                want = ok and v == true
            end
            if want then
                if not e then e = { f = newShield() }; T.boxShields[key] = e end
                if e.f and e.c ~= host then
                    local placed = pcall(function()
                        e.f:SetFrameStrata(host:GetFrameStrata())
                        e.f:SetFrameLevel(host:GetFrameLevel() + T.BOX_LIFT)
                        e.f:ClearAllPoints(); e.f:SetAllPoints(host)
                    end)
                    if placed then e.c, e.mode = host, "box" end
                end
                shieldUp(e, e.f ~= false and e.c == host)
            elseif e then
                shieldUp(e, false)
            end
        end
    end
    -- tt6: a container's host to shield while tooltips are off (WC.abox.make), once per key
    T.BOX_LIFT = 50
    function T.addBox(key, host)
        if type(key) ~= "string" or type(host) ~= "table" then return false end
        T.boxes[key] = host
        pcall(T.shieldTick)
        return true
    end

    -- ---------------------------------------------------------------- tt4: one frame of ours runs while tooltips are off
    local drv = CreateFrame("Frame")
    local function tick()
        if not T.on then T.wake(); return end
        if next(T.queue) ~= nil then                                     -- (no new table each frame)
            local q = T.queue
            T.queue = {}
            for fr in pairs(q) do T.adopt(fr) end
        end
        T.shieldTick()
    end
    function T.wake()
        if T.on then
            if not T.ticking then T.ticking = true; pcall(drv.SetScript, drv, "OnUpdate", function() pcall(tick) end) end
        else
            pcall(T.shieldTick)                                          -- every shield down
            if T.ticking then T.ticking = false; pcall(drv.SetScript, drv, "OnUpdate", nil) end
        end
    end
    -- what each hold caught, for the interact document (Interact's proof.tipc.st)
    function T.stats()
        local s = T.st
        local te = T.shields.TargetFrame
        return { v = T.V, ad = #T.list, th = T.typeHooked and 1 or 0, show = s.show, upd = s.upd, size = s.size, fill = s.fill,
                 type = s.type, sh = s.shield, last = s.last, tm = te and te.up == true and te.mode or nil }
    end

    function T.apply()
        T.on = T.wanted()
        T.hook()
        T.hookType()
        if not T.on then                                                 -- tt2: off - every tooltip zeroed gets its alpha back
            for fr in pairs(T.zeroed) do pcall(fr.SetAlpha, fr, 1) end
            T.zeroed = setmetatable({}, { __mode = "k" })
        end
        for _, fr in ipairs(T.named) do
            if type(fr.SetAlpha) == "function" then pcall(fr.SetAlpha, fr, T.on and 0 or 1) end
        end
        T.wake()
        pcall(T.shieldTick)
        return T.on
    end
    -- the app's switch (Interact's ACT.tips)
    function T.set(on)
        if type(WowCompanionDB) ~= "table" then WowCompanionDB = {} end
        WowCompanionDB.hideTips = on and true or nil
        return T.apply()
    end
    local f = CreateFrame("Frame")
    for _, e in ipairs({ "PLAYER_LOGIN", "PLAYER_ENTERING_WORLD", "PLAYER_TARGET_CHANGED", "PLAYER_FOCUS_CHANGED",
                         "INSTANCE_ENCOUNTER_ENGAGE_UNIT" }) do pcall(function() f:RegisterEvent(e) end) end
    f:SetScript("OnEvent", function(_, e)
        if e == "PLAYER_LOGIN" or e == "PLAYER_ENTERING_WORLD" then pcall(T.apply) else pcall(T.shieldTick) end
    end)
end

-- ================================================================== THE AGGRO TINT ON NAMEPLATES (at1; the user's "Nameplate tint plus bottom map")
-- The ground rings (ar1 / ar2) are GONE: an add-on cannot draw on the terrain (E:/safepath-work/research/ground: every add-on drawing
-- is a layer over the finished 3D frame), so they floated in front of everything and through walls. Instead each unpulled hostile's
-- nameplate gets OUR tint, by how close you are to its aggro radius (the rule shared with Map's bottom-map circles: clamp(20 + (mob
-- level - yours), 5, 45) yd, elites the same, a "??" mob as yours + 10):
--   none      more than APPROACH yd outside the radius (at3: green);
--   yellow    within APPROACH (10) yd of its edge;
--   orange    within NEAR (5) yd of it;
--   red       inside it.
--   Never for a mob in combat (a FADE_S fade), neutral / passive mobs (reaction 4+), dead ones, ones tapped by others, or players.
--   THE DISTANCE: the range bracket (A.bracket) - its middle, smoothed (TAU, at most VMAX yd/s) - and a state changes only past a
--   HYST yd margin, so a bracket boundary cannot make it flicker.
--   LEAVE NO TRACE: our own frame on UIParent (mouse off, our own art: tint_glow.tga, a soft glow with a crisp border), ANCHORED to
--   the plate (SetPoint to it, as ag2's icons): the plate is never read, written, hooked or recoloured (its health bar untouched).
--   Settings > Top screen "Aggro tint" (default on): WowCompanionDB.aggroRings (the key ar1 used; Interact's ACT.rings).
-- at3 (test72, the user: "I don't like its shape around the bar, it should be more conformed to the whole shape" ... "If the enemy
-- bar is visible then it should have a colour, even if it's green for being too far"):
--   THE SHAPE: our frame is anchored to the plate's own HEALTH BAR (its UnitFrame.HealthBarsContainer: only anchored to, never read
--   or written; the plate itself when it has none), PAD out on every side, and our art (tint_glow.tga, tools/make_world_markers.py)
--   is that bar's outline as Forever draws it - the chamfered frame of UI-HUD-CoolDownManager-Bar-BG, ~3 px out (4 on top), measured
--   from the client - with a soft glow outside; drawn at the bar's own aspect (TEX_V of the texture's height used).
--   THE COLOUR: every eligible plate has one: GREEN beyond APPROACH yd past the edge, then yellow, orange, red as before. In combat
--   still none (the game's own threat colours the plate then).
-- at4 (test74, the user: "our aggro highlight is in front of the mob text, it should be behind it"): our frame was on UIParent,
-- which draws over the nameplates (they live under WorldFrame). Now it is WorldFrame's child (the plates' own parent: also their
-- scale, so the 9 out matches the bar's units) in the plate's own strata, one frame level UNDER the plate's UnitFrame - so under its
-- name, level and health bar fill. Only our own frame is parented / levelled (legal in combat); the plate's level and strata are
-- READ (no taint), nothing of it is written; forbidden plates (enemies in instances) never reach us (GetNamePlateForUnit).
-- at8 (the user, 2026-11-12, on 1.0.26: "the aggro tint looks like its floating because its trying to go around the cast bar too (which
-- is not there)"): at7's frame round Hub's target bar was cut for the HUD art's whole PIECES (3 texels left / top, 7 right, 8 bottom),
-- but most of the right and bottom pieces are clear (the c60 art's visible rim is 3 texels on every side: rows 11..13, cols 125..127,
-- one faint texel past them); the frame stood 31 px under the bar, so the outline floated ~20 px below it as if round a cast bar.
-- Now R.barAnchor puts the art's outline ON the rim's visible edge (3 texels out on every side) and only round the health bar (the
-- cast bar, pips and auras under it are never inside): the frame's outsets solved from the art's own geometry (its outline PAD - 3 in
-- from the frame's sides and bottom, PAD - 4 from its top, of a 166 x 20 bar's frame), so the glow beyond it is the nameplate's.
-- at10 (the user, 2026-11-17: "when first walking up on aggressive mobs the name plate has a low opacity and is smaller and so the aggro
-- tint looks a little bit weird until it grows. part of it is the shape is wrong at first and then the other part is I think that the
-- opacity clashes with the low opacity of the wow plates. I wonder if we should only show the aggro tint as the opacity of the wow
-- plates turns up?"): the game (C++, not FrameXML: Blizzard_NamePlates only sets the plates' fixed SIZE, C_NamePlate.SetNamePlateSize)
-- SCALES each plate's base frame by distance (nameplateMinScale .. nameplateMaxScale) and fades it (nameplateMinAlpha ..
-- nameplateMaxAlpha; nameplateOccludedAlphaMult behind walls). Our frame is WorldFrame's child, not the plate's, so it got neither:
--   THE SHAPE: our frame's scale is the anchor's (its health bar's) effective scale over WorldFrame's, re-read each tick (a SetScale
--   on our own frame only when it moved), so the PAD / TOP_IN outsets and the art's outline shrink and grow with the bar itself.
--   THE OPACITY: shown alpha = our fade (o.a, as before) x the plate's effective alpha (its UnitFrame's GetEffectiveAlpha) x a gate:
--   0 below SHOW_LO (0.9) of the plate's full alpha (nameplateMaxAlpha, x nameplateNotSelectedAlpha while another unit is targeted:
--   CVars READ only), a smoothstep to 1 at SHOW_HI (0.97). A plain multiply alone would still show a faint, smaller copy far out;
--   the gate keeps it off until the plate is (nearly) fully in, as the user asked, and the multiply keeps it matched after.
--   Plain reads only (no taint); a secret or missing value counts as 1 (as before). The plate is still never written or hooked.
WC.rings = { V = "at10", SHOW_LO = 0.9, SHOW_HI = 0.97, BASE = 20, K_BELOW = 2.2, MIN = 5, MAX = 45, SKULL = 10, TAU = 0.4, VMAX = 10, FADE_S = 0.3, APPROACH = 10, NEAR = 5, HYST = 1,
             ALPHA = 0.95, OUTSET = 6, PAD = 9, TOP_IN = 4, TEX_V = 53 / 64, list = {}, est = {}, react = {}, why = {}, n = 0,
             COL = { far = { 0.32, 0.86, 0.36 }, approach = { 1, 0.84, 0.24 }, near = { 1, 0.55, 0.12 }, inside = { 0.93, 0.16, 0.12 } },
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
        local f = CreateFrame("Frame", nil, (type(WorldFrame) == "table" and WorldFrame) or UIParent)
        f:SetFrameStrata("BACKGROUND")
        f:EnableMouse(false)
        f:Hide()
        local t = f:CreateTexture(nil, "BACKGROUND")
        t:SetTexture(R.TEX)
        t:SetAllPoints(f)
        t:SetTexCoord(0, 1, 0, R.TEX_V)                  -- at3: the art's used rows (the bar's own aspect)
        o = { f = f, t = t, a = 0 }
        R.list[s] = o
        return o
    end

    -- at8: the target bar's tint frame round its health bar [hp] (w x h: its size, else read): the art's outline on the HUD rim's
    -- visible edge, 3 texels (k = h / 8) out on every side; returns the four outsets (left, top, right, bottom)
    function R.barAnchor(f, hp, w, h)
        h = tonumber(h) or (hp.GetHeight and hp:GetHeight()) or 17
        w = tonumber(w) or (hp.GetWidth and hp:GetWidth()) or 0
        if not (type(h) == "number" and h > 0) then h = 17 end
        if type(w) ~= "number" then w = 0 end
        local rim = 3 * h / 8
        local W0, H0 = 166 + 2 * R.PAD, 20 + 2 * R.PAD                  -- the art's frame (tools/make_world_markers.py)
        local inS, inT = R.PAD - 3, R.PAD - 4                            -- its outline in from the frame's sides / bottom, top
        local l = (rim + inS * w / W0) / (1 - 2 * inS / W0)              -- l = rim + inS * (w + 2l) / W0
        local s = (h + 2 * rim) / (H0 - inT - inS)                       -- the art's vertical stretch: (h + t + b) / H0
        local t, b = rim + inT * s, rim + inS * s
        f:SetPoint("TOPLEFT", hp, "TOPLEFT", -l, t)
        f:SetPoint("BOTTOMRIGHT", hp, "BOTTOMRIGHT", l, -b)
        return l, t, l, b
    end

    -- at10: our frame's scale = the anchor's effective scale over our parent's (to: nil = 1, the target bar on UIParent); a SetScale
    -- on OUR frame only, and only when it moved (the outsets are in our frame's units, so they follow the bar)
    function R.fitScale(o, to)
        local sc = 1
        if to then
            local par = o.f:GetParent()                              -- (method-form pcalls: no closure a frame)
            local okA, a = pcall(to.GetEffectiveScale, to)
            local okP, p = false, nil
            if type(par) == "table" then okP, p = pcall(par.GetEffectiveScale, par) end
            a, p = okA and num(a), okP and num(p)
            if a and p and a > 0 and p > 0 then sc = a / p end
        end
        if not o.sc or math.abs(o.sc - sc) > 0.002 then o.f:SetScale(sc); o.sc = sc end
        return sc
    end
    -- at10: the plate's part of the tint's alpha: its effective alpha (pa) x the gate (0 below SHOW_LO of its full, smoothstep to 1
    -- at SHOW_HI). Its full: nameplateMaxAlpha, x nameplateNotSelectedAlpha while another unit is the target (READ only; 1 when
    -- unreadable). A secret / missing alpha = 1 (the tint as before at9)
    local function cvn(k)
        local ok, v = pcall(function() return C_CVar.GetCVar(k) end)
        v = ok and type(v) == "string" and tonumber(v) or nil
        return (v and v > 0) and v or nil
    end
    function R.plateFactor(ref, isTarget)
        if type(ref) ~= "table" then return 1 end
        local ok, pa = pcall(ref.GetEffectiveAlpha, ref)
        pa = ok and num(pa) or nil
        if not pa then return 1 end
        if pa <= 0 then return 0 end
        local c = R.full
        if not c then                                                -- read once a tick (R.tick clears it)
            local okT, hasT = pcall(function() return UnitExists("target") == true end)
            c = { max = cvn("nameplateMaxAlpha") or 1, ns = (okT and hasT) and (cvn("nameplateNotSelectedAlpha") or 1) or 1 }
            R.full = c
        end
        local full = isTarget and c.max or c.max * c.ns
        local x = (pa / math.min(1, full) - R.SHOW_LO) / (R.SHOW_HI - R.SHOW_LO)
        if x <= 0 then return 0 end
        if x >= 1 then return math.min(1, pa) end
        return math.min(1, pa) * x * x * (3 - 2 * x)
    end

    function R.tick(dt)
        local rec = A and A.last
        R.full = nil
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
                    -- at7 (the user: "The aggro tint is still showing hovering over targeted mobs instead of on the target frame"): the
                    -- TARGET's tint goes round Hub's target bar while it shows (the same HUD bar outline), never over its plate
                    local okT, isT = pcall(function() return UnitIsUnit(st.tok, "target") == true end)
                    local tbHp = nil
                    -- at9 (the user, 2026-11-13: the target's plate "transitions ... to the top of the screen"): while Hub's copy flies up
                    -- (WC.uf.flying) the target's tint is down - on neither the hidden plate nor a bar not shown yet - and fades in on
                    -- the bar once it lands
                    local okF, fly = pcall(function() return okT and isT and type(WC.uf) == "table" and WC.uf.flying and WC.uf.flying() end)
                    local held = okF and fly == true
                    if okT and isT and not held then
                        local okH, hp = pcall(function()
                            local uf = WC.uf
                            return type(uf) == "table" and uf.target and uf.tb and uf.tb.f and uf.tb.f:IsShown() and uf.tb.f.hp or nil
                        end)
                        tbHp = okH and type(hp) == "table" and hp or nil
                    end
                    if tbHp then
                        if o.plate ~= tbHp then
                            o.f:ClearAllPoints()
                            o.f:SetParent(UIParent)
                            -- at8: on the HUD rim's visible edge, round the health bar only (never the cast bar under it)
                            R.barAnchor(o.f, tbHp)
                            o.plate, o.bar, o.onBar = tbHp, nil, true
                            o.strata, o.lvl = nil, nil
                        end
                        plate = nil
                    elseif o.onBar then
                        o.f:ClearAllPoints()
                        o.f:SetParent((type(WorldFrame) == "table" and WorldFrame) or UIParent)
                        o.plate, o.onBar = nil, nil
                        o.strata, o.lvl = nil, nil
                    end
                    if plate and o.plate ~= plate then
                        o.f:ClearAllPoints()
                        -- at3: anchored to the plate's health bar, PAD out (only anchored to: never read or written); a plate
                        -- without one (another style, another add-on's plates): the plate itself, OUTSET out, as before
                        local okB, bar = pcall(function() return plate.UnitFrame and plate.UnitFrame.HealthBarsContainer end)
                        bar = okB and type(bar) == "table" and bar or nil
                        local to, pad = bar or plate, bar and R.PAD or R.OUTSET
                        -- at6: the top TOP_IN in, clear of the name's letters (at5's layering below the plate's strata is GONE: the
                        -- user's bisect 2026-11-08 found it behind test6's ADDON_ACTION_FORBIDDEN errors; at4's layering is back)
                        o.f:SetPoint("TOPLEFT", to, "TOPLEFT", -pad, bar and (pad - R.TOP_IN) or pad)
                        o.f:SetPoint("BOTTOMRIGHT", to, "BOTTOMRIGHT", pad, -pad)
                        o.plate, o.bar, o.to = plate, bar, to
                    end
                    -- at10: the anchor's own scale (the game's distance scale on the plate), so the outsets grow with the bar
                    pcall(R.fitScale, o, (not o.onBar) and o.to or nil)
                    -- at4: under the plate's UnitFrame (its name, level, bar fill): its strata, one level below (re-read each
                    -- tick: a targeted plate is raised)
                    -- at7: on the target bar, under the bar's own frame (its strata, one level below)
                    local okU, uf = pcall(function() return plate and plate.UnitFrame end)
                    local ref = (o.onBar and tbHp:GetParent()) or (okU and type(uf) == "table" and uf) or plate
                    local okS, strata = pcall(function() return ref:GetFrameStrata() end)
                    local okL, lvl = pcall(function() return ref:GetFrameLevel() end)
                    strata = okS and type(strata) == "string" and strata or "BACKGROUND"
                    lvl = okL and num(lvl) or 1
                    if o.strata ~= strata then o.f:SetFrameStrata(strata); o.strata = strata end
                    local want = math.max(0, lvl - 1)
                    if o.lvl ~= want then o.f:SetFrameLevel(want); o.lvl = want end
                    local c = R.COL[e.st]
                    if c then o.t:SetVertexColor(c[1], c[2], c[3]); o.col = e.st end
                    -- the alpha: up in a tinted state out of combat, down far away or in combat
                    local want = (m.cb ~= 1 and c and not held) and R.ALPHA or 0
                    local k = dt / R.FADE_S * R.ALPHA
                    if o.a < want then o.a = math.min(want, o.a + k) else o.a = math.max(want, o.a - k) end
                    -- at10: x the plate's own opacity, gated near its full (none on the target bar: Hub's bar is ours)
                    o.pf = o.onBar and 1 or R.plateFactor((okU and type(uf) == "table" and uf) or plate, isT == true)
                    local shown = o.a * o.pf
                    if o.shownA ~= shown then o.f:SetAlpha(shown); o.shownA = shown end
                    o.state, o.d, o.r = e.st, e.d, r
                    if shown > 0 then
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
