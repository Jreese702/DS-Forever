-- WowCompanion / Safety.lua
-- Structural secrecy safety.
--
-- MEASURED FACTS this file exists to handle (WoW: Forever 1.60.1.70009):
--   * Some API returns are "secret" values. type(v) is SAFE and returns the true type.
--     tostring(v) SUCCEEDS but returns a SECRET STRING. Arithmetic on a secret THROWS.
--     Indexing a secret string THROWS.
--   * Secrecy is VIRAL THROUGH CONCATENATION: building a string from a secret poisons the
--     whole string, and C_Log.LogMessage then REFUSES the entire line (verified false).
--     A refused line is invisible on both sides, so this must be impossible by construction,
--     not merely checked for.
--   * A naive "does v + 0 work" test gives FALSE POSITIVES for every plain string and boolean.
--
-- THE RULE: no raw API return value is ever concatenated. Everything goes through S(),
-- which returns either a proven-safe Lua value or a sentinel. This is grep-enforceable:
-- no collector may concatenate or string.format anything that did not come out of S().

local ADDON = ...
local WC = _G.WowCompanion or {}
_G.WowCompanion = WC

-- Control characters, NOT "?" / "-" / "!". A real keybind of "-" or "=" exists on the action
-- bar, and "?" is a plausible string anywhere, so printable sentinels collide with real data.
-- The first build reported a genuine "-" keybind as "no bind" for exactly this reason.
local S_SECRET  = ""   -- readable in principle, but secret on this build right now
local S_ABSENT  = ""   -- API returned nil
local S_THREW   = ""   -- the call itself errored (signature changed?)

WC.SECRET, WC.ABSENT, WC.THREW = S_SECRET, S_ABSENT, S_THREW

-- Is this value safe to put in an output string?
-- tostring(secret) -> secret string; indexing a secret string throws. So "can I index its
-- tostring" is the one universal test that works for numbers, strings and booleans alike.
local function isPlain(v)
    return pcall(function()
        local s = tostring(v)
        return s:sub(1, 1)
    end)
end
WC.isPlain = isPlain

-- CALL(fn, ...) -> ok, ...  multi-return correct.
-- A single-value `local ok, v = pcall(fn, ...)` silently DROPS 17 of C_Item.GetItemInfo's
-- 18 return values. This is the only sanctioned way to call a game API here.
--
-- The returns travel as varargs and are never packed into a table. The first version did
-- `unpack({ pcall(fn, ...) }, 2)`, and unpack stops at #t, which is NOT the count once a nil
-- is involved. MEASURED under Lua 5.1: `return nil, "x", nil` came back as nothing at all,
-- and `"n", nil, nil, 4, nil` as just "n". It only bit when the LAST return was nil and an
-- earlier one was too, which is exactly the shape of an API with optional values.
local function pass(ok, ...)
    if not ok then return false end
    return true, ...
end
local function CALL(fn, ...)
    if type(fn) ~= "function" then return false end
    return pass(pcall(fn, ...))
end
WC.CALL = CALL

-- S(fn, ...) -> a value that is ALWAYS safe to concatenate.
-- Returns the real value when plain, else one of the three sentinels.
local function S(fn, ...)
    local ok, v = CALL(fn, ...)
    if not ok then return S_THREW end
    -- type(), not `v == nil`. type() is the one operation MEASURED safe on a secret, and the
    -- client's own message table lists "compare" among the operations it blocks on one.
    if type(v) == "nil" then return S_ABSENT end
    if not isPlain(v) then return S_SECRET end
    return v
end
WC.S = S

-- SN(fn, ...) -> a NUMBER or nil. For fields the wire wants numeric.
local function SN(fn, ...)
    local v = S(fn, ...)
    if type(v) == "number" then return v end
    return nil
end
WC.SN = SN

-- A C_Secrets oracle, always safe (predicates return plain booleans).
local function oracle(name, ...)
    local fn = C_Secrets and C_Secrets[name]
    if type(fn) ~= "function" then return nil end
    local ok, r = pcall(fn, ...)
    if not ok then return nil end
    return r
end
WC.oracle = oracle

-- ---------------------------------------------------------------- JSON encode
-- Small, dependency-free, and it only ever sees values that came out of S()/SN().
local ESC = {
    ['"'] = '\\"', ['\\'] = '\\\\', ['\b'] = '\\b',
    ['\f'] = '\\f', ['\n'] = '\\n', ['\r'] = '\\r', ['\t'] = '\\t',
}
local function escStr(s)
    s = s:gsub('[%c"\\]', function(c) return ESC[c] or string.format('\\u%04x', c:byte()) end)
    return '"' .. s .. '"'
end

-- THE FAST PATH. This is the encoder as it has always been, with ONE change (whole numbers
-- from 2^31 up, see below). Every document made of plain values goes through it and nothing
-- else, so the wire stays byte-identical (tools/test_safety.py encodes random documents with
-- this file and with the original and compares the bytes). Do not "tidy" it: the dedupe keys
-- in Core, Bags and Quests are its output.
local encFast
encFast = function(v)
    local t = type(v)
    if v == nil then
        return "null"
    elseif t == "boolean" then
        return v and "true" or "false"
    elseif t == "number" then
        if v ~= v or v == math.huge or v == -math.huge then return "null" end
        if v == math.floor(v) then
            -- %d does not survive 2^31. Stock Lua 5.1 on Windows sends it through a 32-bit
            -- long: MEASURED, format("%d", 999999999999999) = "-2147483648". The client's
            -- own message table has "integer overflow attempting to store %f", so there it
            -- throws instead, which cost the whole document. Money is counted in copper, so
            -- this is any purse over 214,748 gold. Below 2^31 the output is what it always
            -- was; from there up to 1e15, %.0f writes the digits %d should have written.
            local a = math.abs(v)
            if a < 2147483648 then return string.format("%d", v) end
            if a < 1e15 then return string.format("%.0f", v) end
        end
        return string.format("%.4f", v)
    elseif t == "string" then
        return escStr(v)
    elseif t == "table" then
        -- array if it has [1]; object otherwise
        if v[1] ~= nil or next(v) == nil then
            local out = {}
            for i = 1, #v do out[#out + 1] = encFast(v[i]) end
            return "[" .. table.concat(out, ",") .. "]"
        end
        local keys = {}
        for k in pairs(v) do if type(k) == "string" then keys[#keys + 1] = k end end
        table.sort(keys)  -- stable output so a delta hash is meaningful
        local out = {}
        for _, k in ipairs(keys) do out[#out + 1] = escStr(k) .. ":" .. encFast(v[k]) end
        return "{" .. table.concat(out, ",") .. "}"
    end
    return "null"
end

-- THE GUARDED PATH. It runs only when the fast path threw, or handed back a string that is
-- itself secret (secrecy is viral through concatenation). Either way a value reached the
-- encoder that never went through S(). Without this, that one value cost the WHOLE document:
-- the error escaped through WC.emit, and because Sink only counts a line it was asked to
-- write, the reader saw no gap either. For a `full` that is the action bar, the player, the
-- map, the bags and the gear, all gone for one bad field.
--
-- Here the same walk is done one value at a time, touching an unproven value with type()
-- and isPlain() only. A secret is written as the SECRET sentinel (user ruling 2026-09-27),
-- a value that cannot be written for any other reason as THREW, and everything else comes
-- out exactly as the fast path writes it.
local J_SECRET, J_THREW = escStr(S_SECRET), escStr(S_THREW)

-- How often the guard had to step in: documents, secret fields, fields that threw.
-- All zero in a healthy session.
local stats = { guarded = 0, secret = 0, threw = 0 }
WC.encStats = stats

local encSafe
local function walk(v)
    if type(v[1]) ~= "nil" or type((next(v))) == "nil" then
        local out = {}
        for i = 1, #v do out[#out + 1] = encSafe(v[i]) end
        return "[" .. table.concat(out, ",") .. "]"
    end
    local keys = {}
    for k in pairs(v) do
        if type(k) == "string" and isPlain(k) then keys[#keys + 1] = k end
    end
    table.sort(keys)
    local out = {}
    for _, k in ipairs(keys) do out[#out + 1] = escStr(k) .. ":" .. encSafe(v[k]) end
    return "{" .. table.concat(out, ",") .. "}"
end
encSafe = function(v)
    local t = type(v)
    if t == "table" then
        local ok, s = pcall(walk, v)
        if ok then return s end
        stats.threw = stats.threw + 1
        return J_THREW
    elseif t == "boolean" or t == "number" or t == "string" then
        if not isPlain(v) then
            stats.secret = stats.secret + 1
            return J_SECRET
        end
        local ok, s = pcall(encFast, v)
        if ok and isPlain(s) then return s end
        stats.threw = stats.threw + 1
        return J_THREW
    end
    return "null"
end

local function encode(v)
    local ok, s = pcall(encFast, v)
    if ok and isPlain(s) then return s end
    stats.guarded = stats.guarded + 1
    return encSafe(v)
end
WC.encode = encode

-- ---------------------------------------------------------------- self-test
-- Nothing on the wire today is secret (no collector reads a secret API), so the secret
-- branches of S() and encode() had never run on the live client. This runs them once per
-- load against a value MEASURED secret in and out of combat, UnitHealth("player"), and
-- reports what happened as a `cap` line. Every field is a literal or a boolean that came out
-- of pcall, so the report cannot itself carry the secret.
--
-- It is also the early warning for a patch that moves the secrecy map: `specimen` turns
-- "plain", or `S` / `encode` turn false.
WC.KERNEL = "k3"

local function blocked(f)
    return not pcall(f)
end

-- An error message fit for the wire, or nil.
local function plainText(v)
    if type(v) ~= "string" or not isPlain(v) then return nil end
    return v:sub(1, 120)
end

-- Every probe runs under its own pcall, and the caller sends whatever `r` holds even when
-- this function throws. k2 did neither: its first probe was format("%d", 2^31), bare, the
-- client threw on it, and the line that was meant to report the fact never left.
local function selfTest(r)
    r.hasIsSecret = type(issecretvalue) == "function"

    -- what this client's %d does at 2^31 (see the fast path): "ok", "wraps" or "throws"
    local okf, s31 = pcall(string.format, "%d", 2147483648)
    if not okf then
        r.int31, r.int31err = "throws", plainText(s31)
    elseif s31 == "2147483648" then
        r.int31 = "ok"
    else
        r.int31 = "wraps"
    end
    local ok3, e3 = pcall(encode, 3000000000)
    r.enc31 = (ok3 and e3 == "3000000000") and true or false

    local ok, hp = CALL(UnitHealth, "player")
    if not ok then
        r.specimen = "none"
        return
    end
    r.typeOf = type(hp)
    if r.typeOf == "nil" then
        r.specimen = "absent"
        return
    end
    local plain = isPlain(hp) and true or false
    r.specimen = plain and "plain" or "secret"
    if r.hasIsSecret then
        local okq, q = pcall(issecretvalue, hp)
        if okq and type(q) == "boolean" and isPlain(q) then
            r.clientSays = q and "secret" or "plain"
        end
    end
    if plain then return end

    -- which operations the client really blocks on it (true = it threw)
    r.eqNil   = blocked(function() return hp == nil end)
    r.boolean = blocked(function() if hp then return 1 end return 2 end)
    r.arith   = blocked(function() return hp + 0 end)
    r.concat  = blocked(function() return "x" .. hp end)
    r.tostr   = blocked(function() return tostring(hp) end)
    r.store   = blocked(function() return { hp = hp } end)

    -- and the two promises this file makes. The counters are put back afterwards, so that
    -- encStats only ever counts real documents.
    local g, sc, th = stats.guarded, stats.secret, stats.threw
    local okS, s = pcall(S, UnitHealth, "player")
    r.S = (okS and type(s) == "string" and isPlain(s) and s == S_SECRET) and true or false
    local okE, e = pcall(function() return encode({ hp = hp, n = 1 }) end)
    r.encode = (okE and type(e) == "string" and isPlain(e)
        and e == '{"hp":' .. J_SECRET .. ',"n":1}') and true or false
    stats.guarded, stats.secret, stats.threw = g, sc, th
    r.guarded, r.secret, r.threw = g, sc, th
end

function WC.kernelSelfTest()
    local r = { v = WC.KERNEL }
    local ok, err = pcall(selfTest, r)
    if not ok then
        r.failed = true
        r.err = plainText(err)
    end
    return r
end

-- Sent once per load (a /reload is a load). No push of its own: it leaves with the next
-- flush anyone asks for, so it costs no pad burst.
if type(CreateFrame) == "function" then
    pcall(function()
        local f = CreateFrame("Frame")
        f:RegisterEvent("PLAYER_LOGIN")
        f:SetScript("OnEvent", function()
            pcall(function()
                if type(WC.send) ~= "function" then return end
                WC.send(encode({ type = "cap", kernel = WC.kernelSelfTest() }))
            end)
        end)
    end)
end
