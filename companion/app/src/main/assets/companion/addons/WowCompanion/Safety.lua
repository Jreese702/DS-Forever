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
-- k4 (the game's taint log on 1.0.18-test9, 2026-11-08): on this client a secret value's index is a BLOCKED action, logged and put on
-- the chat frame AS OURS ("An attempt to index a secret value was blocked because of taint from WowCompanion"), hundreds a minute; the
-- chat frame's own fields then read tainted. The client's own test answers first, with no blocked action; the old probe only where the
-- client has none.
local hasIsSecret = type(issecretvalue) == "function"
local function isPlain(v)
    if hasIsSecret then
        local ok, q = pcall(issecretvalue, v)
        if ok and q == true then return false end
        if ok and q == false then return true end
    end
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
-- k5 (issue #358, 2026-11-15: "Character names: Cyrillic characters are displayed as empty squares instead of actual letters.
-- Quest descriptions: Some Cyrillic characters are replaced with question marks"): the class was [%c"\\]. %c is the C library's
-- iscntrl, which follows the C library's code page once a program sets a locale: MEASURED in Lua 5.1 on the PC under code page 1252
-- it also matches 0x81 0x8D 0x8F 0x90 0x9D 0xAD (code page 1251: 0x98 0xAD), all of them UTF-8 continuation bytes. Whether the game's
-- client sets one is NOT measured yet: the self-test's cc says (the app's "add-on kernel" line). Where it does, such a byte went out as
-- \u0081 and the like: the letter's lead byte was left alone (the app read it as U+FFFD, a "?") and the byte became a C1 control (an
-- empty square). Russian U+0410 U+041D U+0401 U+042D U+0441 U+044F U+044D, and accented i / A / I in Spanish and French names. Now
-- only the ASCII controls (0-31, 127), spelled out; on an ASCII-only document the output is byte for byte what it was.
local function escStr(s)
    s = s:gsub('[%z\1-\31\127"\\]', function(c) return ESC[c] or string.format('\\u%04x', c:byte()) end)
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
-- k6 THE CHARACTER'S KEY (the 11-20 hunter, test build 1513: "what happened to my combat bars on my hunter? they are gone?"): the game
-- answered GetRealmName() with "" for a whole session, so every per-character store was read and written under "Name-" / "Name-?"
-- (empty stores: the bars looked gone; the real "Name-Realm" ones were never touched). WC.charKey() at the end of this file is now the
-- ONE builder of the name-realm key: nil until the realm is known (never a partial key); the realm the game gives, else the one this
-- character already holds settings under; WC.realmFix folds the orphan stores away once, out of combat (the real key always wins).
-- k7 THE KEPT REALM IS THIS CHARACTER'S (the k6 review): a same-name character on another realm, the realm empty and no hint, resolved to
-- the hunter's "Name-Realm" and wrote into its stores. A kept realm now needs evidence: WowCompanionDB.realmOf[name][realm] = { s = the
-- home server's id (UnitGUID), c = the class token }, recorded at every live session; the server must match (another = refused), else
-- the class (and the one realm left), nothing recorded = no key. A kept realm found before PLAYER_LOGIN is asked again then (both
-- tables loaded); the fold is skipped while the name holds settings under two realms (the app's rule); its backup holds COPIES.
-- k8 (the k6 review, round 2): the class alone ("maybe": no home server id to compare) picks a kept realm only AFTER PLAYER_LOGIN (before
-- it, a same-class, same-name character's only recorded realm would take its writes until the login's re-check: no key until then);
-- the fold's backup keeps the 2 newest folds PER NAME (one character's folds never push another's dropped orphans out).
-- k9 (the k6 review, round 3): THE CHARACTER'S OWN GUID. A "yes" assumed the home server id in UnitGUID ("Player-<server>-<id>") is
-- another on every realm; a private server that shares one id over realms let a same-name alt (no record of its own) match the hunter's
-- realm. realmOf[name][realm].g now keeps the whole GUID (a realm's characters have their own; never logged or sent): the GUID decides
-- when both are known (another = "no"); a record from before k9 (no g) needs the server AND the class (the same server, another class =
-- "no"). The orphans' fold (WC.realmFix) needs that evidence too: a key resolved by the class alone ("maybe") leaves the orphans as they
-- are, and a live or kept key whose record holds another class folds nothing.
WC.KERNEL = "k9"

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

    -- k5 (#358): how many bytes 128-255 this client's %c calls a control (0 = ASCII rules; 1252 = 6), and whether a UTF-8 letter
    -- (Cyrillic A, 0xD0 0x90) leaves the encoder whole. Numbers and booleans only
    local okc, cc = pcall(function()
        local n = 0
        for i = 128, 255 do if string.char(i):find("%c") then n = n + 1 end end
        return n
    end)
    r.cc = okc and cc or nil
    local oku, u = pcall(encode, "\208\144")
    r.utf8 = (oku and u == '"\208\144"') and true or false
    r.sink = (type(WC.SINKV) == "string" and isPlain(WC.SINKV)) and WC.SINKV or nil

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
    -- k4: the client says which values are secret: no blocked operation is tried on one to find out (each is a blocked action, ours)
    if r.hasIsSecret then return end

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


-- ---------------------------------------------------------------- k6 THE CHARACTER'S KEY, "Name-Realm" (WC.charKey) and the orphans' fold
-- The rule (the user's per-character ruling, PadMap m36, kept): every per-character store is keyed "Name-Realm". A key is built ONLY from
-- a plain, non-empty name and a plain, non-empty realm; anything else ("", nil, a secret, a failed call, "?", "_") is NOT a realm and the
-- answer is nil ("no character yet": every store's own pre-login branch). Where the realm comes from, in order:
--   1. GetRealmName() (the game's own answer, "live"); remembered per name in WowCompanionDB.realmOf.
--   2. the realm this name already holds settings under ("kept"): the remembered ones and the "Name-<realm>" keys of our two saved tables,
--      when EXACTLY ONE realm fits (GetNormalizedRealmName / UnitFullName's realm, when the game gives one, picks among several); never a
--      guess between two characters of one name. k7: without the game's hint, only a realm with EVIDENCE that it is THIS character's
--      (realmOf[name][realm] = { s = home server id, c = class }, recorded at the live sessions): the server id when both are known (one
--      that matches picks it even among several; another one refuses it), else the class with that realm the only one left. A realm with
--      no evidence (only our tables' keys) is no key: a same-name character on another realm never writes into this one's stores.
--      k9: the record also keeps g = the whole GUID; g decides when both are known, a recorded class that is not this one's refuses, and
--      the server id alone (a record from before k9) needs the class to agree too.
--   3. none: nil (our tables looked through again at most every 2 s for the first 10 s - the saved tables load meanwhile -, then every
--      30 s; GetRealmName itself is asked every call: a later live answer always wins).
-- WC.realmFix: once per character and table, out of combat, the orphan keys of THIS character ("Name-", "Name-?", "Name-_", a marker) are
-- taken out of every store (our tables' keys and two levels down): the real key's store stays as it is (never overwritten); a store the
-- real key lacks (none, or only empty tables) takes the orphan's when it holds a setting (made in the realm-less session, the newest
-- record there is); every taken orphan is kept in WowCompanionDB.realmFixBak (the 2 newest folds per name, k8). Never a game API that writes.
do
    local function str(v)
        if type(v) ~= "string" or not isPlain(v) then return nil end
        if v == S_SECRET or v == S_ABSENT or v == S_THREW or not v:find("%S") then return nil end
        return v
    end
    local function realmOk(r)
        local s = str(r)
        if s and s ~= "?" and s ~= "_" and not s:find("%c") then return s end
    end
    WC.realmOk = realmOk
    local function norm(r) return (r:gsub("[%s%-'%.]", "")):lower() end
    local function now() local ok, t = CALL(GetTime); return (ok and type(t) == "number") and t or 0 end
    local function myName() return str(S(function() return UnitName("player") end)) end
    local CK = { n = nil, r = nil, src = nil, early = false, told = nil, missN = nil, missAt = nil, misses = 0, loggedIn = false }
    WC.ck = CK

    -- k7 the character's own evidence (never logged): its home server's id (UnitGUID "Player-<server>-<id>") and its class token; nil =
    -- not known now
    local function myServer()
        local g = str(S(function() return UnitGUID("player") end))
        return g and g:match("^Player%-(%d+)%-") or nil
    end
    local function myClass()
        local ok, _, token = CALL(UnitClass, "player")
        if ok then return str(token) end
    end
    -- k9: the whole GUID (a "Player-" one only), nil = not known now
    local function myGuid()
        local g = str(S(function() return UnitGUID("player") end))
        return (g and g:match("^Player%-%d+%-%S+$")) and g or nil
    end
    WC.myServer, WC.myClass, WC.myGuid = myServer, myClass, myGuid

    -- the game's realm [r] for [n]: realmOf[n][r] = { s = server id, c = class } (k6's `true` = no evidence yet), filled in as the game
    -- gives them (the latest live answer: a deleted and remade character of that name and realm is the one there now)
    local function remember(n, r)
        local db = rawget(_G, "WowCompanionDB")
        if type(db) ~= "table" then return end
        local tag = n .. "-" .. r
        if CK.told == tag and type(db.realmOf) == "table" and type(db.realmOf[n]) == "table" and type(db.realmOf[n][r]) == "table" then return end
        if type(db.realmOf) ~= "table" then db.realmOf = {} end
        local m = db.realmOf[n]
        if type(m) ~= "table" then m = {}; db.realmOf[n] = m end
        local e = m[r]
        if type(e) ~= "table" then e = {}; m[r] = e end
        local sid, cls, gid = myServer(), myClass(), myGuid()
        if sid and e.s ~= sid then e.s = sid end
        if cls and e.c ~= cls then e.c = cls end
        if gid and e.g ~= gid then e.g = gid end                            -- k9
        if e.s and e.c and (e.g or not gid) then CK.told = tag end          -- k9: done once the GUID is in (or not readable now)
    end

    -- every realm name [n] holds settings under: the remembered ones and our tables' "n-<realm>" keys (two levels down)
    local function realmsOf(n)
        local pre, set, list = n .. "-", {}, {}
        local function add(r) r = realmOk(r); if r and not set[r] then set[r] = true; list[#list + 1] = r end end
        local function look(t)
            for k in pairs(t) do if type(k) == "string" and #k > #pre and k:sub(1, #pre) == pre then add(k:sub(#pre + 1)) end end
        end
        local core = rawget(_G, "WowCompanionDB")
        if type(core) == "table" and type(core.realmOf) == "table" and type(core.realmOf[n]) == "table" then
            for r in pairs(core.realmOf[n]) do add(r) end
        end
        for _, db in ipairs({ core, rawget(_G, "WowCompanionSpellsDB") }) do
            if type(db) == "table" then
                look(db)
                for k1, v in pairs(db) do
                    if type(v) == "table" and k1 ~= "realmFixBak" then
                        look(v)
                        for _, w in pairs(v) do if type(w) == "table" then look(w) end end
                    end
                end
            end
        end
        return list
    end
    WC.realmsOf = realmsOf

    -- the game's own realm hint when GetRealmName is empty (its normalized spelling: no spaces), or nil
    local function hint()
        local h = realmOk(S(function() return GetNormalizedRealmName() end))
        if h then return h end
        local ok, _, r = CALL(UnitFullName, "player")
        if ok then return realmOk(r) end
    end

    WC.charKeyHint = function() return hint() and "present" or "none" end

    -- k7: [r] against this character: "yes" (the recorded home server is this one's), "maybe" (no server to compare, the class matches),
    -- "no" (another server or another class: a different character), nil (nothing recorded to compare)
    -- k9: [gid] the whole GUID decides first (another = "no"); then the server, which needs the class to agree too
    local function evidence(n, r, sid, cls, gid)
        local db = rawget(_G, "WowCompanionDB")
        local m = type(db) == "table" and type(db.realmOf) == "table" and db.realmOf[n] or nil
        local e = type(m) == "table" and m[r] or nil
        if type(e) ~= "table" then return nil end
        if cls and type(e.c) == "string" and e.c ~= cls then return "no" end
        if gid and type(e.g) == "string" then return e.g == gid and "yes" or "no" end
        if sid and type(e.s) == "string" then
            if e.s ~= sid then return "no" end
            return (cls and type(e.c) == "string") and "yes" or "maybe"
        end
        if cls and type(e.c) == "string" then return "maybe" end
        return nil
    end
    WC.realmEvidence = evidence

    local function resolve(n)
        local list = realmsOf(n)
        if #list == 0 then return nil end
        local h = hint()
        if h then
            -- the game's own (normalized) realm: the one stored spelling that matches it IS this character's realm
            local hit
            for _, r in ipairs(list) do
                if norm(r) == norm(h) then
                    if hit then return nil end
                    hit = r
                end
            end
            return hit
        end
        local sid, cls, gid = myServer(), myClass(), myGuid()
        local yes, left = {}, {}
        for _, r in ipairs(list) do
            local ev = evidence(n, r, sid, cls, gid)
            if ev == "yes" then yes[#yes + 1] = r end
            if ev ~= "no" then left[#left + 1] = { r, ev } end
        end
        if #yes == 1 then return yes[1] end
        -- k8: the class alone only once logged in (the server id is known then; before it, no key: asked again at PLAYER_LOGIN)
        if #yes == 0 and #left == 1 and left[1][2] == "maybe" and CK.loggedIn then return left[1][1] end
        return nil                                                          -- no evidence, or two: never a guess
    end

    -- the character's key "Name-Realm", its name, its realm and where the realm came from ("live" / "kept"); nil = not known (yet)
    function WC.charKey()
        local n = myName()
        if not n then return nil end
        local live = realmOk(S(function() return GetRealmName() end))
        if live then
            if CK.n ~= n or CK.r ~= live or CK.src ~= "live" then CK.n, CK.r, CK.src = n, live, "live" end
            remember(n, live)
            return n .. "-" .. live, n, live, "live"
        end
        if CK.n == n and CK.r then return n .. "-" .. CK.r, n, CK.r, CK.src end
        local t = now()
        if CK.missN == n and CK.missAt and t - CK.missAt < ((CK.misses or 0) < 5 and 2 or 30) then return nil end
        local r = resolve(n)
        if r then
            -- k7: found before PLAYER_LOGIN (WowCompanionSpellsDB may not be loaded yet, nor the character's server id): asked again then
            CK.n, CK.r, CK.src, CK.early, CK.missN, CK.missAt, CK.misses = n, r, "kept", not CK.loggedIn, nil, nil, 0
            return n .. "-" .. r, n, r, "kept"
        end
        if CK.missN ~= n then CK.misses = 0 end
        CK.missN, CK.missAt, CK.misses = n, t, (CK.misses or 0) + 1
        return nil
    end
    -- the realm's source now, for the padmap's diagnostics: "live", "kept" or "none" (never a name)
    function WC.charKeySource()
        local k, _, _, src = WC.charKey()
        return k and src or "none"
    end
    -- k7 PLAYER_LOGIN: both saved tables are loaded and the character's server id is known; a kept realm found before is asked again
    function WC.charKeyLogin()
        CK.loggedIn = true
        if CK.src == "kept" and CK.early then CK.n, CK.r, CK.src, CK.early = nil, nil, nil, false end
        CK.missN, CK.missAt, CK.misses = nil, nil, 0
    end
    -- a key string that is one of [n]'s orphans ("n-", "n-?", "n-_", "n-" .. a marker)
    function WC.isOrphanKey(k, n)
        if type(k) ~= "string" or type(n) ~= "string" or k:sub(1, #n + 1) ~= n .. "-" then return false end
        local r = k:sub(#n + 2)
        return r == "" or r == "?" or r == "_" or r == S_SECRET or r == S_ABSENT or r == S_THREW
    end

    -- ------------------------------------------------------------ the orphans' fold (WC.realmFix)
    local RF = { done = {}, tries = 0, pending = false, last = nil }
    WC.realmFix = RF
    local SUFFIX = { "", "?", "_", S_SECRET, S_ABSENT, S_THREW }
    local NAMED = { [""] = "-", ["?"] = "-?", ["_"] = "-_" }
    -- holds a setting: a leaf that is not an empty string, somewhere in it (depth-limited)
    local function holds(v, d)
        d = d or 0
        if type(v) == "table" then
            if d > 12 then return true end
            for _, x in pairs(v) do if holds(x, d + 1) then return true end end
            return false
        end
        return v ~= nil and v ~= ""
    end
    RF.holds = holds
    -- k7: a deep COPY (the backup must not share a table the live store keeps editing)
    local function copy(v, d)
        if type(v) ~= "table" then return v end
        d = d or 0
        if d > 20 then return nil end
        local o = {}
        for k, x in pairs(v) do o[k] = copy(x, d + 1) end
        return o
    end
    RF.copy = copy
    local function fight() local ok, v = CALL(InCombatLockdown); return ok and v == true end

    -- one table [t] (at [path] in [var]): this character's orphans out, the real key kept or given the orphan's store; each into [items]
    local function fixIn(t, key, n, var, path, items)
        for _, suf in ipairs(SUFFIX) do
            local o = n .. "-" .. suf
            local ov = rawget(t, o)
            if ov ~= nil then
                local rv = rawget(t, key)
                local act = "dropped"
                if not holds(rv) and holds(ov) then
                    if type(rv) == "table" and type(ov) == "table" then
                        for k2, v2 in pairs(ov) do rv[k2] = v2 end            -- into the real store's own table (a module may hold it)
                    else
                        t[key] = ov
                    end
                    act = "adopted"
                end
                t[o] = nil
                items[#items + 1] = { var = var, at = path, k = NAMED[suf] or "-marker", act = act, v = copy(ov) }
            end
        end
    end

    -- the fold for [var] (one of our two tables); true = looked through (it is loaded)
    local function fixVar(var, key, n, items)
        local db = rawget(_G, var)
        if type(db) ~= "table" then return false end
        fixIn(db, key, n, var, "", items)
        local l1 = {}
        for k1, v in pairs(db) do if type(v) == "table" and k1 ~= "realmFixBak" then l1[#l1 + 1] = { k1, v } end end
        for _, e in ipairs(l1) do
            local k1, v = e[1], e[2]
            fixIn(v, key, n, var, tostring(k1), items)
            local l2 = {}
            for k2, w in pairs(v) do if type(w) == "table" then l2[#l2 + 1] = { k2, w } end end
            for _, f in ipairs(l2) do fixIn(f[2], key, n, var, tostring(k1) .. "." .. tostring(f[1]), items) end
        end
        return true
    end

    -- at login and the world's entry, out of combat (else at the fight's end), again a few times while the realm is not known.
    -- true = both tables folded for this character
    function RF.run()
        if fight() then RF.pending = true; return false end
        RF.pending = false
        local key, n, kr, src = WC.charKey()
        if not key then return false end
        -- k9: the orphans fold only into a key with this character's own evidence: the game's realm (just recorded), or a kept one whose
        -- record matches this GUID / server AND class ("yes"); the class alone ("maybe") or a record of another class leaves them as they
        -- are (nothing reads them; a later session may fold them)
        local ev = evidence(n, kr, myServer(), myClass(), myGuid())
        if ev ~= "yes" and not (src == "live" and ev ~= "no") then
            RF.skipped = "evidence"
            for _, var in ipairs({ "WowCompanionDB", "WowCompanionSpellsDB" }) do if type(rawget(_G, var)) == "table" then RF.done[var] = key end end
            return true
        end
        -- k7: never a guess (the app's RealmFix.clean, the same rule): a name holding settings under two realms keeps its orphans as they
        -- are (nothing reads them); done for this character
        if #realmsOf(n) > 1 then
            RF.skipped = true
            for _, var in ipairs({ "WowCompanionDB", "WowCompanionSpellsDB" }) do if type(rawget(_G, var)) == "table" then RF.done[var] = key end end
            return true
        end
        local items, all = {}, true
        for _, var in ipairs({ "WowCompanionDB", "WowCompanionSpellsDB" }) do
            if RF.done[var] ~= key then
                if fixVar(var, key, n, items) then RF.done[var] = key else all = false end
            end
        end
        if #items > 0 then
            local adopted, dropped = 0, 0
            for _, it in ipairs(items) do if it.act == "adopted" then adopted = adopted + 1 else dropped = dropped + 1 end end
            RF.last = { adopted = adopted, dropped = dropped }
            local core = rawget(_G, "WowCompanionDB")
            if type(core) == "table" then
                if type(core.realmFixBak) ~= "table" then core.realmFixBak = {} end
                local ok, t = CALL(GetServerTime)
                table.insert(core.realmFixBak, 1, { at = (ok and type(t) == "number") and t or 0, who = n, items = items })
                -- k8: the 2 newest folds PER NAME (an older record with no name counts as one name of its own)
                local per, i = {}, 1
                while i <= #core.realmFixBak do
                    local rec = core.realmFixBak[i]
                    local w = type(rec) == "table" and type(rec.who) == "string" and rec.who or ""
                    per[w] = (per[w] or 0) + 1
                    if per[w] > 2 then table.remove(core.realmFixBak, i) else i = i + 1 end
                end
            end
        end
        return all
    end

    if type(CreateFrame) == "function" then
        pcall(function()
            local f = CreateFrame("Frame")
            f:RegisterEvent("PLAYER_LOGIN")
            f:RegisterEvent("PLAYER_ENTERING_WORLD")
            f:RegisterEvent("PLAYER_REGEN_ENABLED")
            local function again()
                if RF.tries >= 20 or type(C_Timer) ~= "table" or type(C_Timer.After) ~= "function" then return end
                RF.tries = RF.tries + 1
                C_Timer.After(5, function()
                    local ok, d = pcall(RF.run)
                    if not (ok and d) and not RF.pending then again() end
                end)
            end
            f:SetScript("OnEvent", function(_, e)
                if e == "PLAYER_LOGIN" then pcall(WC.charKeyLogin) end
                if e == "PLAYER_REGEN_ENABLED" and not RF.pending then return end
                local ok, d = pcall(RF.run)
                if not (ok and d) and not RF.pending then again() end
            end)
        end)
    end
end
