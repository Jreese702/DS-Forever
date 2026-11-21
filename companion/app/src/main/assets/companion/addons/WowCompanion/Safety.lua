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
-- k10 THE STICKY REALM (the user, 2026-11-20, test build 1514: "Realms work different in wow forever. There isnt a dedicated realm like
-- normal servers."; then: the realm is a ruleset pool anyone of those rules plays on, "but like no one can have those names"): GetRealmName()
-- answered "" and the hint named another realm than the one the hunter's settings live under, with no evidence recorded, so k9 gave no key
-- (blank bars, no top-screen switches). A character's name is unique: the realm part of a key is only where its settings already live.
-- WC.charKey now resolves at PLAYER_LOGIN through this GUID's own record (WowCompanionDB.who), the ONE realm the name holds settings
-- under ("sticky"), or a one-time question on the bottom screen when it is ambiguous (WC.charKeyPick); a new character gets
-- "Name-<realm>" ("Name-Forever" when the game names none), recorded so it never flaps. A resolved key never changes in the session.
-- k11 (the k10 review, round 1): a realm counts as a place the settings live only when its key HOLDS settings (a remembered realm or an
-- empty-table key never pulls the key off the real settings); a same-name record of this class under another GUID never blocks the
-- sticky key (a name is one character: the record is re-pointed to this GUID, asked only when 2+ realms hold settings); the counts in
-- the diagnostics on every path; a "new" key prefers a realm the name is already known under; the login's stamp (ls) for the app's
-- "Later" (asked again at the next login, a /reload included).
-- k12 (the k10 review, round 2): a key is RECORDED (WowCompanionDB.who / realmOf) only while our Spells add-on's table can be seen (the
-- game's blocked-action pop-up can turn that add-on off for one session: the hunter's settings were then invisible and a one-off "new"
-- key was recorded for good); a new character's key waits for it (no key, nothing written); a record whose key holds NO settings while
-- exactly one other realm of the name does follows the settings (never a player's pick: p = 1); the padmap's who.ad = the app may put
-- this name's In-combat backup under another realm into the key (only a fresh key nobody chose: never after the question).
WC.KERNEL = "k12"

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
-- a plain, non-empty name and a plain, non-empty realm; anything else ("", nil, a secret, a failed call, "?", "_", a control character)
-- is NOT a realm and the answer is nil ("no character yet": every store's own pre-login branch).
-- k10 THE STICKY REALM (the user's rulings, 2026-11-20, test build 1514: "Realms work different in wow forever. There isnt a dedicated
-- realm like normal servers."; "my 1 guy is <First> <Last> and my 2 guy is <First> <Last> ... but like no one can have those names"): on
-- WoW Forever the "realm" is a shared ruleset pool - the realm the game reports can be empty or another one between sessions - and a
-- character's whole name is GLOBALLY UNIQUE. The name is the identity; the realm part of a key is only where its settings already live.
-- Nothing is ever moved or renamed. The key, at / after PLAYER_LOGIN (both saved tables loaded, the GUID known):
--   a) "guid": this GUID's own record, WowCompanionDB.who[GUID] = { k = the key its settings live under, c = class, i = the GUID's id
--      segment } (its name must be this one's and its class not another), whatever realm the game reports now;
--   b) "guid-id": a record with the same id segment AND class (the GUID's server part changed) - the record moves to the new GUID;
--   c) "sticky": the ONE realm this name holds settings under (WowCompanionDB.realmOf + our two tables' "Name-<realm>" keys; k11: only
--      a key that HOLDS settings counts - a remembered realm or an empty-table key is not where the settings live) ("live" when it is
--      the game's own realm); of several, the one the game's realm or hint names exactly ("live" / "hint"), else the one a same-name
--      record of this class holds; a key another GUID of another class claims is never this character's. k11: a same-name record of
--      THIS class under another GUID never blocks it (a name is one character: the record is re-pointed to this GUID, gs "id-changed");
--   d) ambiguous (two or more realms holding settings left, nothing naming one): no key yet; the bottom screen asks ONCE
--      (WC.charKeyInfo().pk: each realm and how many settings it holds, plus "a new character"); the pick is this GUID's record
--      (WC.charKeyPick, the padtab's third page, Book b74); "later" = no key until the next login asks again (nothing is written);
--   e) "new": nothing to claim: "Name-<the game's realm>", else a realm the name is already known under (k11: the one the hint names
--      after norm(), else the only one), else "Name-<its hint>", else "Name-Forever" (never a key another one holds). The app's In-combat
--      backup of this name under another realm is put back into it by the app (CombatKeep.adopt: the name is the character; k12: only
--      while the padmap's who.ad says so - this fresh key, nobody chose it - never after the question's answer).
-- Once a key is resolved in a session it never changes in that session (a later GetRealmName answer is ignored). Before PLAYER_LOGIN:
-- this GUID's record, or the game's realm when the name holds settings under it or under none (asked again at the login). The
-- record is the account's own saved table; a GUID is never logged or sent.
-- WC.realmFix: once per character and table, out of combat, the orphan keys of THIS character ("Name-", "Name-?", "Name-_", a marker) are
-- taken out of every store (our tables' keys and two levels down): the real key's store stays as it is (never overwritten); a store the
-- real key lacks (none, or only empty tables) takes the orphan's when it holds a setting (made in the realm-less session, the newest
-- record there is); every taken orphan is kept in WowCompanionDB.realmFixBak (the 2 newest folds per name, k8). Never a game API that writes.
-- k10: no fold while the key is not resolved (the ask pending); a resolved key folds (a name is one character: no evidence gate).
-- k12: no record and no fold while our Spells add-on's table cannot be seen (the game turned that add-on off): a key found then is
-- this session's only ("new" waits: no key); a record (not a pick) whose key holds no settings follows the ONE realm that does.
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
    local function fight() local ok, v = CALL(InCombatLockdown); return ok and v == true end
    local CK = { n = nil, r = nil, key = nil, src = nil, early = false, told = nil, missN = nil, missAt = nil, misses = 0, loggedIn = false,
                 diag = nil, pk = nil, pkN = nil, temp = false, ad = false }
    WC.ck = CK
    -- k12: our Spells add-on's saved table can be seen now (its table is there, or the game says the add-on is loaded: a nil table is
    -- then simply empty); false only when the game says that add-on is NOT loaded (turned off: its settings are not seen this session)
    local function spellsSeen()
        if type(rawget(_G, "WowCompanionSpellsDB")) == "table" then return true end
        local A = rawget(_G, "C_AddOns")
        local f = type(A) == "table" and rawget(A, "IsAddOnLoaded") or nil
        if type(f) ~= "function" then f = rawget(_G, "IsAddOnLoaded") end
        if type(f) ~= "function" then return true end                    -- no way to ask: as before
        local ok, v = CALL(f, "WowCompanionSpells")
        if not ok then return true end
        return v == true or v == 1
    end
    WC.charKeySpellsSeen = spellsSeen
    -- the tables our scans never look into for character keys: the fold's backup, the remembered realms, the GUID records (GUID keys)
    local SKIP = { realmFixBak = true, realmOf = true, who = true }

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
    -- k10: the GUID's id segment ("Player-<server>-<id>")
    local function idOf(g) return type(g) == "string" and g:match("^Player%-%d+%-(%S+)$") or nil end

    -- the realm [r] of [n]'s key, remembered: realmOf[n][r] = { s = server id, c = class, g = GUID } (k10: only the RESOLVED key's realm,
    -- so a reported realm the settings do not live under never becomes a second realm of the name)
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
                    if type(v) == "table" and not SKIP[k1] then
                        look(v)
                        for _, w in pairs(v) do if type(w) == "table" then look(w) end end
                    end
                end
            end
        end
        table.sort(list)
        return list
    end
    WC.realmsOf = realmsOf

    -- k10: how many settings [key] holds in our two tables (its leaves that are not "", the stores two levels down): the picker's count
    local function uses(key)
        local c = 0
        local function leaves(v, d)
            if type(v) == "table" then
                if d > 12 then return end
                for _, x in pairs(v) do leaves(x, d + 1) end
            elseif v ~= nil and v ~= "" then c = c + 1 end
        end
        local function look(t) local v = rawget(t, key); if v ~= nil then leaves(v, 0) end end
        for _, db in ipairs({ rawget(_G, "WowCompanionDB"), rawget(_G, "WowCompanionSpellsDB") }) do
            if type(db) == "table" then
                look(db)
                for k1, v in pairs(db) do
                    if type(v) == "table" and not SKIP[k1] then
                        look(v)
                        for _, w in pairs(v) do if type(w) == "table" then look(w) end end
                    end
                end
            end
        end
        return c
    end
    WC.charKeyUses = uses

    -- the game's own realm hint when GetRealmName is empty (its normalized spelling: no spaces), or nil
    local function hint()
        local h = realmOk(S(function() return GetNormalizedRealmName() end))
        if h then return h end
        local ok, _, r = CALL(UnitFullName, "player")
        if ok then return realmOk(r) end
    end

    WC.charKeyHint = function() return hint() and "present" or "none" end

    -- k10: a stored key's name and realm ("Name-Realm": a name never holds a '-'), or nil
    local function split(k)
        if type(k) ~= "string" then return nil end
        local n, r = k:match("^([^%-]+)%-(.+)$")
        r = realmOk(r)
        if n and r and str(n) and not n:find("%c") then return n, r end
    end
    local function whoTab()
        local db = rawget(_G, "WowCompanionDB")
        return (type(db) == "table" and type(db.who) == "table") and db.who or nil
    end
    -- k10: this GUID's record of [key] (only once logged in: the saved table is the game's then); k12: [picked] = the player's own
    -- answer to the question (p = 1: never moved by itself afterwards)
    local function record(gid, key, cls, picked)
        if not gid or not CK.loggedIn then return end
        if type(rawget(_G, "WowCompanionDB")) ~= "table" then _G.WowCompanionDB = {} end
        local db = _G.WowCompanionDB
        if type(db.who) ~= "table" then db.who = {} end
        local e = db.who[gid]
        if type(e) ~= "table" then e = {}; db.who[gid] = e end
        if e.k ~= key then e.k = key; e.p = nil end
        if picked then e.p = 1 end
        if cls and e.c ~= cls then e.c = cls end
        local id = idOf(gid)
        if id and e.i ~= id then e.i = id end
    end

    -- k10: the other GUIDs' claims on [n]'s keys: claims[key] = "same" (a record of this class: maybe this character under a changed
    -- GUID - asked, never taken by itself) / "other" (another class: never this character); [same] = a record of this name and class
    -- holds another GUID ("id-changed" for the diagnostics); [other] = a record of this name holds another class (the orphans may be
    -- that character's: no fold). [gid] nil (not readable): only another class's records claim
    local function claimsOf(n, gid, cls)
        local out, same, other = {}, false, false
        local function put(k, c, g)
            local w = (cls and type(c) == "string" and c ~= cls) and "other" or "same"
            if w == "same" and not gid then return end
            if w == "same" and type(g) == "string" then same = true end
            if w == "other" then other = true end
            if out[k] ~= "same" then out[k] = w end
        end
        local who = whoTab()
        if who then
            for g, e in pairs(who) do
                if g ~= gid and type(e) == "table" then
                    local kn, kr = split(e.k)
                    if kn == n then put(kn .. "-" .. kr, e.c, g) end
                end
            end
        end
        local db = rawget(_G, "WowCompanionDB")
        local m = type(db) == "table" and type(db.realmOf) == "table" and db.realmOf[n] or nil
        if type(m) == "table" then
            for r, e in pairs(m) do
                local rr = realmOk(r)
                if rr and type(e) == "table" then
                    if type(e.g) == "string" and e.g ~= gid then put(n .. "-" .. rr, e.c, e.g)
                    elseif cls and type(e.c) == "string" and e.c ~= cls then put(n .. "-" .. rr, e.c, nil) end
                end
            end
        end
        return out, same, other
    end

    -- k10 e): a new character's realm: the game's, else its hint, else "Forever" - never one [n] holds settings under or another claims
    -- k11 (the review, round 1): [known] = every realm the name is known under (one with no settings included): before a spelling of
    -- our own (the hint's no-space one, "Forever"), the one of them the hint names after norm(), else the only one
    local function newRealm(n, live, h, list, claims, known)
        local held = {}
        for _, r in ipairs(list) do held[r] = true end
        local function ok(r) return r and not held[r] and claims[n .. "-" .. r] ~= "other" end
        if live and ok(live) then return live end
        local spare = {}
        for _, r in ipairs(known or {}) do if ok(r) then spare[#spare + 1] = r end end
        if h then for _, r in ipairs(spare) do if norm(r) == norm(h) then return r end end end
        if #spare == 1 then return spare[1] end
        for _, r in ipairs({ h or false, "Forever" }) do if r and ok(r) then return r end end
        for i = 2, 9 do if ok("Forever " .. i) then return "Forever " .. i end end
        return nil
    end

    -- k10 diagnostics only (no names, no realms): other names' remembered realms of this class that no GUID record claims
    local function unclaimedSameClass(n, cls)
        local db = rawget(_G, "WowCompanionDB")
        if not cls or type(db) ~= "table" or type(db.realmOf) ~= "table" then return 0 end
        local claimed = {}
        local who = whoTab()
        if who then for _, e in pairs(who) do if type(e) == "table" and type(e.k) == "string" then claimed[e.k] = true end end end
        local c = 0
        for name, m in pairs(db.realmOf) do
            if name ~= n and type(m) == "table" then
                for r, e in pairs(m) do
                    if type(e) == "table" and e.c == cls and type(name) == "string" and realmOk(r) and not claimed[name .. "-" .. r] then c = c + 1 end
                end
            end
        end
        return c
    end

    -- k10: [n]'s realm and its source, the diagnostics, and (no key: the ask) the candidates { { r = realm, n = settings } }
    local function resolve(n)
        local gid, cls = myGuid(), myClass()
        local live = realmOk(S(function() return GetRealmName() end))
        local h = hint()
        local diag = { rr = live and "reported" or "empty", rh = h and "present" or "none", nc = 0, uc = 0, gs = gid and "new" or "unknown" }
        local who = whoTab()
        local claims, same, other = claimsOf(n, gid, cls)
        diag.oc = other
        -- k11 (the review, round 1): every realm the name is known under ([known]: a remembered realm, an empty-table key included) and
        -- the ones that HOLD settings ([list]): only those are where the settings live. The counts on every path (a) too)
        local known = realmsOf(n)
        local list = {}
        for _, r in ipairs(known) do if uses(n .. "-" .. r) > 0 then list[#list + 1] = r end end
        diag.nc = #list
        diag.uc = unclaimedSameClass(n, cls)
        -- c)'s realms: the ones holding settings that no other GUID of ANOTHER class claims (k11: a same-name record of this class is
        -- this character under another GUID - a name is one character -, never a block; [mine] = the ones such a record holds)
        local free, mine = {}, {}
        for _, r in ipairs(list) do
            local c = claims[n .. "-" .. r]
            if c ~= "other" then
                free[#free + 1] = r
                if c == "same" then mine[#mine + 1] = r end
            end
        end
        -- a) this GUID's own record; k12 (the review, round 2): a record that is not the player's pick whose key holds NO settings
        -- while exactly ONE other realm of this name does (a key recorded while those settings could not be seen) follows the settings
        if gid and who and type(who[gid]) == "table" then
            local e = who[gid]
            local kn, kr = split(e.k)
            if kn == n and not (cls and type(e.c) == "string" and e.c ~= cls) then
                diag.gs = "same"
                if e.p ~= 1 and CK.loggedIn and spellsSeen() and #free == 1 and free[1] ~= kr and uses(e.k) == 0 then
                    return free[1], (live == free[1]) and "live" or "sticky", diag
                end
                return kr, "guid", diag
            end
        end
        -- b) the same id segment and class: the GUID's server part changed (only once logged in: the record moves to this GUID)
        local id = idOf(gid)
        if id and cls and who and CK.loggedIn then
            local gs = {}
            for g in pairs(who) do if type(g) == "string" then gs[#gs + 1] = g end end
            table.sort(gs)
            for _, g in ipairs(gs) do
                local e = who[g]
                if g ~= gid and type(e) == "table" and e.i == id and e.c == cls then
                    local kn, kr = split(e.k)
                    if kn == n then
                        who[gid], who[g] = e, nil
                        diag.gs = "server-changed"
                        return kr, "guid-id", diag
                    end
                end
            end
        end
        if same and diag.gs == "new" then diag.gs = "id-changed" end
        local function has(r) for _, x in ipairs(list) do if x == r then return true end end return false end
        if not CK.loggedIn then
            -- before PLAYER_LOGIN: only the game's realm, when the name holds settings under it (no other claims it) or under none
            if live and claims[n .. "-" .. live] ~= "other" and (#list == 0 or has(live)) then return live, "live", diag end
            return nil, nil, diag
        end
        -- c) the realms holding settings ([free], above)
        if #free == 1 then return free[1], (live == free[1]) and "live" or "sticky", diag end
        if #free > 1 then
            if live and has(live) and claims[n .. "-" .. live] ~= "other" then return live, "live", diag end
            if h then
                local hit, two
                for _, r in ipairs(free) do if norm(r) == norm(h) then if hit then two = true end; hit = r end end
                if hit and not two then return hit, "hint", diag end
            end
            if #mine == 1 then return mine[1], "sticky", diag end
            -- d) ambiguous: asked once on the bottom screen (2+ realms hold this name's settings, nothing names one)
            local pk = {}
            for i, r in ipairs(free) do if i <= 10 then pk[#pk + 1] = { r = r, n = uses(n .. "-" .. r) } end end
            return nil, "ask", diag, pk
        end
        -- e) a new character; k12: only while our Spells add-on's table can be seen (turned off, its settings may be this name's:
        -- no key this session - nothing written - rather than a guess recorded for good)
        if not spellsSeen() then diag.sp = "unseen"; return nil, nil, diag end
        local r = newRealm(n, live, h, list, claims, known)
        if r then return r, "new", diag end
        return nil, nil, diag
    end

    -- the key resolved: kept for the session (k10: never another one this session), recorded under this GUID once logged in
    -- k12: [picked] = the player's answer to the question (recorded as such); a key found while our Spells add-on's table cannot be seen
    -- is this session's only (CK.temp: no record, no remembered realm, no fold); CK.ad = a fresh key nobody chose (the app may put this
    -- name's In-combat backup under another realm into it: CombatKeep.adopt)
    local function lock(n, r, src, picked)
        CK.n, CK.r, CK.key, CK.src, CK.early, CK.pk, CK.pkN = n, r, n .. "-" .. r, src, not CK.loggedIn, nil, nil
        CK.missN, CK.missAt, CK.misses = nil, nil, 0
        CK.temp = CK.loggedIn and not picked and not spellsSeen()
        CK.ad = CK.loggedIn and src == "new" and not picked and not CK.temp
        if CK.loggedIn and not CK.temp then
            local gid, cls = myGuid(), myClass()
            record(gid, CK.key, cls, picked)
            remember(n, r)
            -- k11: a same-name record of this class under ANOTHER GUID holding this key is this character's (a name is one character):
            -- re-pointed to this GUID (the record above), the old one dropped
            local who = whoTab()
            if gid and who then
                for g, e in pairs(who) do
                    if g ~= gid and type(e) == "table" and e.k == CK.key and not (cls and type(e.c) == "string" and e.c ~= cls) then who[g] = nil end
                end
            end
        end
        return CK.key, n, r, src
    end

    -- the character's key "Name-Realm", its name and the realm of the STORED key (the settings' own), and the source ("guid", "guid-id",
    -- "sticky", "live", "hint", "picked", "new"); nil = not known (yet)
    function WC.charKey()
        local n = myName()
        if not n then return nil end
        if CK.n == n and CK.key then return CK.key, n, CK.r, CK.src end
        local t = now()
        local live = realmOk(S(function() return GetRealmName() end)) or ""
        if CK.missN == n and CK.missAt and CK.missLive == live and t - CK.missAt < ((CK.misses or 0) < 5 and 2 or 30) then return nil end
        local r, src, diag, pk = resolve(n)
        CK.diag = diag
        if r then return lock(n, r, src) end
        if CK.n ~= n then CK.n, CK.r, CK.key, CK.src = nil, nil, nil, nil end   -- another name: never the last one's key
        CK.pk, CK.pkN = (src == "ask") and pk or nil, n
        if CK.missN ~= n then CK.misses = 0 end
        CK.missN, CK.missAt, CK.misses, CK.missLive = n, t, (CK.misses or 0) + 1, live
        return nil
    end
    -- the key's source now, for the padmap's diagnostics: one of charKey's words, or "ask" (the bottom screen's question) / "none"
    function WC.charKeySource()
        local k, _, _, src = WC.charKey()
        if k then return src end
        return CK.pk and "ask" or "none"
    end
    -- k10 the padmap's who: the source, the diagnostics' words and counts, and (the ask) the candidates; never a name or GUID but the
    -- candidates' realms (the player's own bottom screen only)
    function WC.charKeyInfo()
        local src = WC.charKeySource()
        local d = CK.diag or {}
        return { rs = src, rh = d.rh or WC.charKeyHint(), rr = d.rr, nc = d.nc, uc = d.uc, gs = d.gs, pk = (not CK.key) and CK.pk or nil,
                 ls = CK.ls, ad = (CK.key and CK.ad) and 1 or nil }
    end
    -- k10 THE ASK'S ANSWER (the padtab's third page, Book b74): [i] = the candidate's place in WC.charKeyInfo().pk (1-based), 0 = a new
    -- character. Recorded as this GUID's; only while asked, once logged in, out of combat (the stores re-read their key then)
    function WC.charKeyPick(i)
        local n = myName()
        if not n or not CK.loggedIn or fight() or CK.key or CK.pkN ~= n or type(CK.pk) ~= "table" or type(i) ~= "number" then return false end
        local r, src
        if i == 0 then
            local gid, cls = myGuid(), myClass()
            local claims = claimsOf(n, gid, cls)
            r = newRealm(n, realmOk(S(function() return GetRealmName() end)), hint(), realmsOf(n), claims)
            src = "new"
        else
            local c = CK.pk[i]
            r = type(c) == "table" and realmOk(c.r) or nil
            src = "picked"
        end
        if not r then return false end
        lock(n, r, src, true)
        if type(WC.realmFix) == "table" and type(WC.realmFix.run) == "function" then pcall(WC.realmFix.run) end
        if type(WC.padMap) == "table" and type(WC.padMap.emit) == "function" then pcall(WC.padMap.emit, true) end
        return true
    end
    -- k7 PLAYER_LOGIN: both saved tables are loaded and the GUID is known; a key found before is asked again (k10: then kept)
    -- k11: [ls] = this login's stamp (the client's clock, tenths of a second: every PLAYER_LOGIN, a /reload's included, gets its own),
    -- the app's "Later" lasts until it changes (asked again at the next login). A number only: no name, no realm, no GUID
    function WC.charKeyLogin()
        CK.loggedIn = true
        local okT, t = CALL(GetTime)
        CK.ls = (okT and type(t) == "number" and t > 0) and math.floor(t * 10) or nil
        if CK.key and CK.early then CK.n, CK.r, CK.key, CK.src, CK.early = nil, nil, nil, nil, false end
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
        for k1, v in pairs(db) do if type(v) == "table" and not SKIP[k1] then l1[#l1 + 1] = { k1, v } end end
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
        -- k10: never while the key is not resolved (the ask pending: no key); a resolved key is this character's (a name is one
        -- character on WoW Forever: the user's ruling), so its orphans fold into it - the real key's stores never overwritten
        local key, n = WC.charKey()
        if not key then return false end
        -- k12: a key of this session only (our Spells add-on's table not seen): its orphans stay for a session that sees both tables
        if CK.temp then RF.skipped = "session"; return true end
        -- a record of this name holds another class (a deleted and remade character): its orphans may be that one's, left as they are
        if CK.diag and CK.diag.oc then
            RF.skipped = "other"
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
