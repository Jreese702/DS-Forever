-- WowCompanion / Sink.lua
-- The export channel: a JSON line out through C_Log.LogMessage -> Logs/General.log,
-- which the Android app tails.
--
-- MEASURED FACTS this file exists to handle:
--   * General.log is BLOCK-BUFFERED at ~48 KiB (49152 B). NOT line-flushed. Nothing reaches
--     disk until the buffer fills, a /reload happens, or the player logs out.
--     Measured: a 150 s run at 10 Hz produced exactly ONE growth event, +49191 B at t=73 s.
--   * There is NO flush trigger. 129 [F] FATAL writes over 25 min produced 2 flushes, both
--     exactly 48 KiB. Severity does not matter.
--   * Therefore: latency = 49152 / emit_rate. THE CONSEQUENCE IS INVERTED FROM INTUITION —
--     compressing or shrinking the payload makes the companion SLOWER. Do not "optimise" bytes.
--   * Writing is effectively free: 124 lines x 500 chars (~64 KB) measured dt = 0.0000 s.
--     So we force a flush on demand by padding, and it costs disk bytes, never frame time.
--   * General.log is TRUNCATED at every client launch, so it never accumulates across sessions.
--   * The engine's own [N][Lua] chatter shares this file, hence the magic prefix.

local ADDON = ...
local WC = _G.WowCompanion

WC.PROTO   = "WCB1"
WC.VERSION = "0.1.0"

local BUFFER_BYTES = 49152          -- measured
local PAD_TARGET   = 61440          -- 1.25x: we estimate our own bytes and cannot see the file,
                                    -- so overshoot. An undershoot costs the ENTIRE push.
local LOG_PREFIX_BYTES = 29         -- "9/25 21:30:45.728  [N][Lua] "
local MAX_LINE = 900                -- C_Log max message length is UNMEASURED; stay conservative
local PADLINE  = string.rep("x", 500)

-- Pad-length probe, run once at load. C_Log's maximum message length has never been
-- measured, so the pad is 500 chars and a flush costs ~118 separate calls. If longer lines
-- survive intact, the same 48 KiB costs a tenth of the calls. The reader checks which of
-- these arrive whole; until then nothing depends on the answer.
function WC.probeLineLengths()
    for _, n in ipairs({ 900, 2000, 4000, 8000, 16000 }) do
        pcall(C_Log.LogMessage, WC.PROTO .. "|probe" .. n .. "|" .. string.rep("p", n) .. "|END")
    end
end

local seq = 0
local pendingBytes = 0

local function raw(s)
    -- seq is incremented BEFORE the call so that a refused line (a secret leaked into the
    -- string) shows up to the reader as a GAP. Otherwise a refusal is invisible on both sides.
    seq = seq + 1
    local line = WC.PROTO .. "|" .. seq .. "|" .. s
    local ok = pcall(C_Log.LogMessage, line)
    pendingBytes = pendingBytes + #line + LOG_PREFIX_BYTES
    return ok
end

-- Send one JSON document, fragmenting if it exceeds the safe line length.
-- Fragments carry part=i/n and the reader reassembles before parsing.
function WC.send(json)
    if #json <= MAX_LINE then
        return raw("1/1|" .. json)
    end
    local n = math.ceil(#json / MAX_LINE)
    for i = 1, n do
        raw(i .. "/" .. n .. "|" .. json:sub((i - 1) * MAX_LINE + 1, i * MAX_LINE))
    end
    return true
end

-- Force everything written so far out to disk by filling the engine's buffer.
-- Costs ~48-60 KB of log and, measurably, no time. Call this after anything the player
-- is actually waiting to see; never on a timer, and never when nothing is pending.
--
-- COALESCED: several emits in the same frame share ONE flush. The first version paid three
-- separate 60 KB pads for hello + login + manual, two of them 4 ms apart -- 296 pad lines to
-- carry 5 lines of data. A flush scheduled at the end of the frame fixes that for free.
WC.flushMs, WC.flushMax, WC.flushes = 0, 0, 0

function WC.flush()
    if pendingBytes <= 0 then return end
    -- debugprofilestop() has millisecond resolution; GetTime() only advances once a frame,
    -- which is why an earlier measurement of this loop read 0.0000 s and was believed.
    local t0 = debugprofilestop()
    local need = PAD_TARGET - pendingBytes
    if need > 0 then
        local lines = math.ceil(need / (500 + LOG_PREFIX_BYTES))
        for i = 1, lines do
            pcall(C_Log.LogMessage, WC.PROTO .. "|pad|" .. PADLINE)
        end
    end
    pendingBytes = 0
    WC.flushMs = debugprofilestop() - t0
    if WC.flushMs > WC.flushMax then WC.flushMax = WC.flushMs end
    WC.flushes = WC.flushes + 1
end

local lastFlushAt = 0
-- Seconds between flushes. THIS NUMBER IS THE MAP'S REFRESH RATE -- nothing reaches the bottom
-- screen until a flush pushes it out of the engine's 48 KiB buffer.
--
-- It was 2.0, chosen to bound log volume. That is fine for a quest list and hopeless for a map
-- you are trying to walk by: the arrow moved in two-second jumps. 0.3 s puts it just under the
-- position ticker (0.25 s), so in practice every position change flushes, and end-to-end
-- latency is one flush plus one app poll -- about half a second.
--
-- The cost is real and worth stating: a flush is ~60 KB whatever it carries, so moving costs
-- ~200 KB/s of log. It is only paid WHILE MOVING -- collectPos emits nothing when the position
-- key is unchanged, so standing still writes nothing at all. The app truncates the log when it
-- gets large (see LogTail.MAX_LOG_BYTES) so the file cannot grow without bound.
-- 0.15 s. Was 2.0 (a quest list's cadence), then 0.3, and the map still read as laggy when
-- walking: at 0.3 the arrow lands ~3 frames behind where you are. This sits under the 0.1 s
-- position ticker so every sampled step gets its own flush, and end-to-end (flush + the app's
-- 100 ms poll) is about a quarter second.
-- It is paid ONLY WHILE MOVING -- collectPos emits nothing when the position key is unchanged,
-- so standing still still writes zero bytes.
-- 0.2 s. It went 2.0 -> 0.3 -> 0.15 chasing a smooth arrow, and that was the wrong lever:
-- MEASURED at 0.15 it ran at 9.4 flushes/s, 292 KB/s, 2741 pad lines per 56 lines of real
-- data, and cost the game about one dropped frame in ten. Smoothness now comes from the
-- 30 Hz sample ring in Trail.lua, which rides along for free, so the flush only has to be
-- often enough to keep END-TO-END LATENCY down -- and the app hides the rest of that in its
-- playback buffer. Going the other way from here costs frames in the game, not just bytes.
local FLUSH_INTERVAL = 0.2

-- Throttled push: call it as often as you like. It flushes at most every FLUSH_INTERVAL,
-- which bounds both the latency AND the log volume (~24 KB/s at 2 s).
--
-- NOTHING PENDING, NO CLOCK RESET. It used to stamp lastFlushAt even when flush() had nothing
-- to write, so polling it while idle would push the first flush of the next walk back by up
-- to a whole interval -- the worst possible moment to be late, because a START is the one
-- thing the app cannot predict.
--
-- A REFUSAL IS NO LONGER LOST. A push inside the interval used to be dropped outright, and the
-- bytes it was for sat in the buffer until some unrelated push came along. Outdoors that is the
-- next 0.1 s position tick, so nobody noticed; in an INSTANCE there is no map position, the
-- ticker returns early, and the next push was the 15 s heartbeat (found by the cast session).
-- So a refusal with bytes pending arms ONE retry, a backstop for SILENCE:
--  * never more than one outstanding (retryQueued), however many pushes are refused;
--  * it flushes only after a whole FLUSH_INTERVAL with no refused push. While a caller is still
--    pushing, that caller's own next push flushes, exactly as before. MEASURED in the harness
--    (tools/test_sink_rearm.py): a retry fired simply "at the end of the interval" lands on the
--    same instant as the next position tick, flushes the older sample a moment before the fresh
--    one is written, and so raised the walking flush rate from 3.35/s to 4.6/s while making the
--    arrow's latency WORSE. This way the walking flushes are identical to the old ones;
--  * it goes back through this same check, so the cap stays one flush per FLUSH_INTERVAL;
--  * if something else flushed meanwhile it finds nothing pending and does nothing.
-- The callback is made once, here, so a refusal allocates nothing.
local lastRefusedAt = 0
local retryQueued = false
local function retry()
    retryQueued = false
    if pendingBytes <= 0 then return end
    local quiet = GetTime() - lastRefusedAt
    if quiet < FLUSH_INTERVAL then
        retryQueued = true
        C_Timer.After(FLUSH_INTERVAL - quiet, retry)
        return
    end
    WC.pushThrottled()
end

function WC.pushThrottled()
    if pendingBytes <= 0 then return end
    local now = GetTime()
    if now - lastFlushAt < FLUSH_INTERVAL then
        lastRefusedAt = now
        if not retryQueued then
            retryQueued = true
            C_Timer.After(FLUSH_INTERVAL, retry)
        end
        return
    end
    lastFlushAt = now
    WC.flush()
end

local flushQueued = false
-- Ask for a push. Many callers in one frame produce exactly one pad burst.
--
-- THIS GOES THROUGH THE THROTTLE TOO. It did not, and that is why the measured flush rate was
-- 9.4/s against a 0.15 s interval that should have capped it at 6.7: every harvest, chat
-- batch and zone change added its own unthrottled 48 KiB on top of the position stream.
function WC.push()
    if flushQueued then return end
    flushQueued = true
    C_Timer.After(0, function()
        flushQueued = false
        WC.pushThrottled()
    end)
end

-- Send now, push at end of frame.
function WC.emit(tbl)
    WC.send(WC.encode(tbl))
    WC.push()
end

function WC.seqNow() return seq end
