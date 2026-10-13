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
