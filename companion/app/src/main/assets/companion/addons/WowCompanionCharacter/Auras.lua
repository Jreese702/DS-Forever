-- WowCompanionCharacter / Auras.lua
-- THE PLAYER'S BUFFS AND DEBUFFS, for Home's small map (the user's ask, 2026-09-29 ~17:30, via Home: buffs top left,
-- debuffs bottom left, each group a button that opens a list to read an aura or remove it). Written by the character
-- session at Home's request; Home draws it.
--
-- READING is all this file does to the game, with ONE exception: REMOVING A BUFF (the user's pick, 2026-09-29 17:49,
-- relayed by Home from the tracking session: "Out of combat only": select, then ONE Remove tap). Two tap-pad rows:
--   `aurapick`   a plain row, slot key = the buff's auraInstanceID. Out of combat only, it finds that BUFF and ARMS the
--                cancel cell: our own secure button gets type "cancelaura" and spell = the buff's name. It changes
--                nothing in the game (the amended lock's no-action pick tap). Reply: {"type":"auraack","op":"pick"}.
--   `auracancel` one SECURE cell (TapPad.secureRow). A tap on it is the game's own "cancelaura" secure action
--                (CancelSpellByName on the armed name), run by the game from a real touch; never a call from this file.
--                Then the cell is disarmed again. Reply: {"type":"auraack","op":"cancel"}, ok = the buff is gone.
-- The cancel cell does NOTHING until a pick arms it, stays armed PICK_SECONDS at most, and is disarmed after its tap and
-- when a fight starts, so a stray tap on it can never remove anything. Every SetAttribute checks InCombatLockdown() first. The app sends pick + cancel for ONE touch on "Remove" (Home's side).
-- Debuffs cannot be removed (the game has no cancel for them); weapon enchants are not offered.
--
-- WHAT IT SENDS: {"type":"auras","v":AV, "b":[..], "d":[..], "e":[..], "lock":true|nil, "combat":true|nil,
--                 "now":GetTime(), "sec":{secret,threw}, "full":true|nil}
--   b  the buffs, in the game's own order (its buff frame's: C_UnitAuras index order, "HELPFUL"), at most 32
--   d  the debuffs, the game's order ("HARMFUL"), at most 16
--   e  temporary weapon enchants (poisons, stones, oils): the game shows them first in its buff row
--   one aura = { id  = auraInstanceID: plain even in combat (C_UnitAuras.GetUnitAuraInstanceIDs carries no secrecy flag)
--                x   = its index in the game's list for that filter (what a "cancelaura" cell's `index` means); out of
--                      combat only
--                n   = name;  i = icon fileDataID;  s = stacks (only when above 1);  sp = spellId
--                dur = full duration in seconds (0 = no end);  exp = when it ends, on the game's clock (GetTime);
--                      the app's time left = exp - now, counted from the log line's own time
--                t   = the dispel type ("Magic", "Curse", "Disease", "Poison"): the game colours a debuff's border by it
--                tip = the tooltip's lines after the name (out of combat, read once per aura; the "N min remaining"
--                      line is left out: the app has exp) }
--   one enchant = { slot = 16 main hand / 17 off hand / 18 ranged, i = icon (the weapon's, as the game shows it),
--                   s = charges (above 0 only), exp = its end on the game's clock }
-- Sent when something changed (UNIT_AURA for the player, coalesced), plus on every core heartbeat with "full".
--
-- SECRECY (read in the game's API docs 2026-09-29, NOT yet measured on the device): GetAuraDataByIndex /
-- GetAuraDataByAuraInstanceID and the aura tooltips carry SecretWhenUnitAuraRestricted: secret "when combat,
-- encounter, challenge mode, or PvP match addon restrictions are in effect", with NO exception for the player's
-- own auras; a spell can be flagged never- or always-secret. So while C_Secrets.ShouldAurasBeSecret() says yes
-- (or, if it is missing, in combat) the list is built from the instance IDs alone and each field goes through
-- the kernel: a secret field is the SECRET sentinel, so Home can show "?" and still count them. A never-secret
-- spell keeps its name and icon in combat.

local WC = _G.WowCompanion
if type(WC) ~= "table" or type(WC.S) ~= "function" or type(WC.send) ~= "function" then return end
local CALL, isPlain = WC.CALL, WC.isPlain
local format = string.format

local AV = "a7"     -- bump on every change; the app shows it, so a stale build is visible
local MAX = { HELPFUL = 32, HARMFUL = 16 }      -- the game's BUFF_MAX_DISPLAY / DEBUFF_MAX_DISPLAY

-- ---------------------------------------------------------------- safe reads (as Character.lua)

local tally = { secret = 0, threw = 0 }

local function P(v)
    if type(v) == "nil" then return nil end
    if not isPlain(v) then tally.secret = tally.secret + 1; return WC.SECRET end
    return v
end
-- a number, or the SECRET sentinel when the game hid it (so the app can tell "hidden" from "none")
local function PNs(v) v = P(v); if type(v) == "number" or v == WC.SECRET then return v end end
local function PSs(v) v = P(v); if type(v) == "string" then return v end end

local function strip(s)
    s = s:gsub("|c%x%x%x%x%x%x%x%x", "")
    s = s:gsub("|cn[^:]*:", "")
    s = s:gsub("|r", "")
    s = s:gsub("|T.-|t", "")
    s = s:gsub("|A.-|a", "")
    s = s:gsub("|n", "\n")
    return s
end

local function tenth(n) return math.floor(n * 10 + 0.5) / 10 end

-- One call whose SINGLE return is wanted, or false + the error text: the kernel's CALL drops the text, and the first
-- device fight (2026-09-29 20:40:46) threw twice with no reason recorded. Only for functions with one return value.
local function call1(fn, ...)
    if type(fn) ~= "function" then return false, "missing" end
    return pcall(fn, ...)
end

-- a failed call's error text, safe for the wire and short
local function errText(e)
    e = PSs(e)
    if not e or e == WC.SECRET then return e end
    return strip(e):sub(1, 140)
end

local function secretNow()
    local v = type(WC.oracle) == "function" and WC.oracle("ShouldAurasBeSecret") or nil
    if type(v) == "boolean" and isPlain(v) then return v end
    local ok, c = CALL(InCombatLockdown)
    return ok and c == true
end

-- ---------------------------------------------------------------- tooltips

-- The game's "N min remaining" lines (SPELL_TIME_REMAINING_*), as patterns: left out of `tip`, the app has `exp`.
local REMAINING = {}
for _, k in ipairs({ "SPELL_TIME_REMAINING_SEC", "SPELL_TIME_REMAINING_MIN", "SPELL_TIME_REMAINING_HOURS",
                     "SPELL_TIME_REMAINING_DAYS" }) do
    local g = _G[k]
    if type(g) == "string" then
        -- "%d sec remaining" / "%d |4sec:sec; remaining" -> a Lua pattern
        local p = g:gsub("|4[^;]*;", "\1"):gsub("[%^%$%(%)%.%[%]%*%+%-%?]", "%%%0"):gsub("%%d", "%%d+"):gsub("\1", ".-")
        REMAINING[#REMAINING + 1] = "^" .. p .. "$"
    end
end
local function isRemaining(line)
    for _, p in ipairs(REMAINING) do if line:find(p) then return true end end
    return false
end

-- Read once per aura instance: a tooltip is dozens of lines of work, and its text does not change for one aura.
local tipCache = {}
local function tipOf(id, filter)
    if tipCache[id] ~= nil then return tipCache[id] or nil end
    local fn = C_TooltipInfo and (filter == "HELPFUL" and C_TooltipInfo.GetUnitBuffByAuraInstanceID
                                  or C_TooltipInfo.GetUnitDebuffByAuraInstanceID)
    local ok, data = CALL(fn, "player", id, filter)
    if not ok or type(data) ~= "table" then tipCache[id] = false; return nil end
    local lines, out = P(data.lines), {}
    if type(lines) ~= "table" then tipCache[id] = false; return nil end
    for k = 2, math.min(#lines, 8) do                       -- line 1 is the name
        local l = lines[k]
        local t = type(l) == "table" and PSs(l.leftText) or nil
        if t and t ~= WC.SECRET then
            t = strip(t)
            if t ~= "" and not isRemaining(t) then out[#out + 1] = t end
        elseif t == WC.SECRET then
            tipCache[id] = nil                               -- hidden right now: try again later, cache nothing
            return nil
        end
    end
    tipCache[id] = #out > 0 and out or false
    return tipCache[id] or nil
end

-- ---------------------------------------------------------------- the lists

-- One aura's fields from the game's AuraData (field names as the game's own buff frame reads them).
local function entry(d, id, x, filter, locked)
    local a = { id = id, x = x }
    d = P(d)
    if type(d) ~= "table" then return a end
    a.n = PSs(d.name)
    a.i = PNs(d.icon)
    local s = PNs(d.applications)
    if s == WC.SECRET or (type(s) == "number" and s > 1) then a.s = s end
    a.sp = PNs(d.spellId)
    local dur, exp = PNs(d.duration), PNs(d.expirationTime)
    if type(dur) == "number" then a.dur = tenth(dur) elseif dur then a.dur = dur end
    if type(exp) == "number" then
        if exp > 0 then a.exp = tenth(exp) end
    elseif exp then
        a.exp = exp
    end
    a.t = PSs(d.dispelName)
    if not locked and type(id) == "number" then a.tip = tipOf(id, filter) end
    return a
end

local function list(filter, locked)
    local out = {}
    if type(C_UnitAuras) ~= "table" then tally.threw = tally.threw + 1; return out end
    if not locked then
        -- the game's own order: index 1.. for this filter (what its buff frame shows, and what "cancelaura" means by index)
        for x = 1, MAX[filter] do
            local ok, d = CALL(C_UnitAuras.GetAuraDataByIndex, "player", x, filter)
            if not ok then tally.threw = tally.threw + 1; break end
            if type(d) == "nil" then break end
            local id = type(d) == "table" and PNs(d.auraInstanceID) or nil
            out[#out + 1] = entry(d, id, x, filter, false)
        end
        return out
    end
    -- restricted (combat and the like): the instance IDs are plain; every other field as the game allows
    local why = nil
    local ok, ids = call1(C_UnitAuras.GetUnitAuraInstanceIDs, "player", filter, MAX[filter])
    if not ok then tally.threw = tally.threw + 1; why = errText(ids) end
    ids = ok and P(ids) or nil
    if type(ids) == "table" then
        for k = 1, math.min(#ids, MAX[filter]) do
            local id = PNs(ids[k])
            if type(id) == "number" then
                local okd, d = call1(C_UnitAuras.GetAuraDataByAuraInstanceID, "player", id)
                if not okd then tally.threw = tally.threw + 1; why = why or errText(d); d = nil end
                out[#out + 1] = entry(d, id, nil, filter, true)
            end
        end
    end
    if #out > 0 then return out, "ids", why end
    -- The ID list failed or came back EMPTY (the first fight on the Thor, 20:40:46, sent b = [] while a buff may still have
    -- been on): cross-check with the index loop. Its end is a nil, which type() reads safely even under secrecy; every
    -- field goes through the kernel, so a hidden id is the SECRET sentinel and the entry still counts.
    for x = 1, MAX[filter] do
        local okx, d = call1(C_UnitAuras.GetAuraDataByIndex, "player", x, filter)
        if not okx then tally.threw = tally.threw + 1; why = why or errText(d); break end
        if type(d) == "nil" then break end
        local dd = P(d)
        local id = type(dd) == "table" and PNs(dd.auraInstanceID) or WC.SECRET
        out[#out + 1] = entry(d, id, nil, filter, true)
    end
    return out, (#out > 0) and "index" or ((type(ids) == "table") and "ids" or "none"), why
end

-- Temporary weapon enchants, as the game's buff frame reads them (C_Item.GetWeaponEnchantInfo per Enum.WeaponSlot;
-- the icon is the weapon's own, slots 16 / 17 / 18).
local SLOT = { [0] = 16, [1] = 17, [2] = 18 }
local function enchants()
    local out = {}
    if not (C_Item and type(C_Item.GetWeaponEnchantInfo) == "function" and type(Enum) == "table"
            and type(Enum.WeaponSlot) == "table") then return out end
    local now = GetTime()
    for _, slotID in pairs(Enum.WeaponSlot) do
        local ok, list = CALL(C_Item.GetWeaponEnchantInfo, slotID)
        list = ok and P(list) or nil
        if type(list) == "table" then
            for _, e in pairs(list) do
                if type(e) == "table" and P(e.hasEnchant) == true then
                    local slot = SLOT[slotID]
                    local okt, tex = CALL(GetInventoryItemTexture, "player", slot)
                    local x = { slot = slot, i = okt and PNs(tex) or nil }
                    local c = PNs(e.charges)
                    if c == WC.SECRET or (type(c) == "number" and c > 0) then x.s = c end
                    local left = PNs(e.timeLeft)                      -- milliseconds
                    if type(left) == "number" then x.exp = math.floor(now + left / 1000 + 0.5) elseif left then x.exp = left end
                    out[#out + 1] = x
                end
            end
        end
    end
    table.sort(out, function(a, b) return a.slot < b.slot end)
    return out
end

-- ---------------------------------------------------------------- sending

local lastKey = nil

-- Build the document; send it when it differs from the last one (or always when forced). `now` and `full` are
-- left out of the comparison: the app needs the clock, but a tick of it is not a change.
local function emit(force)
    tally.secret, tally.threw = 0, 0
    local locked = secretNow()
    local doc = { type = "auras", v = AV }
    local vb, vd, wb, wd
    local ok = pcall(function()
        doc.b, vb, wb = list("HELPFUL", locked)
        doc.d, vd, wd = list("HARMFUL", locked)
        doc.e = enchants()
    end)
    -- while locked: which read gave each list ("ids", "index", or "none" = both failed), so a device log can tell a true
    -- empty list from a failed read
    if locked then doc.via = { b = vb, d = vd, eb = wb, ed = wd } end
    if not ok then doc.err = true end
    doc.lock = locked or nil
    local okc, c = CALL(UnitAffectingCombat, "player")
    doc.combat = (okc and c == true) or nil
    doc.sec = { secret = tally.secret, threw = tally.threw }
    -- the buffs' pad slots (out of combat only: removal is), and the grid, as Quests publishes them
    local pad = WC.pad
    if pad and not locked and doc.b then
        local present = {}
        for _, a in ipairs(doc.b) do
            if type(a.id) == "number" then a.slot = pad.slotFor("aura", a.id); present[a.id] = true end
        end
        present.open, present.close = true, true          -- the Character room's two fixed cells are never freed
        pad.retain("aura", present)
    end
    if pad then local okp, g = pcall(pad.describe); if okp then doc.pad = g end end
    -- forget the tooltips of auras that are gone
    local live = {}
    for _, l in ipairs({ doc.b or {}, doc.d or {} }) do for _, a in ipairs(l) do if a.id then live[a.id] = true end end end
    for id in pairs(tipCache) do if not live[id] then tipCache[id] = nil end end
    local okk, key = pcall(WC.encode, doc)
    if not okk then return false end
    if key == lastKey and not force then return false end
    lastKey = key
    doc.now = tenth(GetTime())
    doc.full = force or nil
    WC.send(WC.encode(doc))
    doc.now, doc.full = nil, nil
    return true
end

local queued = false
local function soon(delay)
    if queued then return end
    queued = true
    C_Timer.After(delay or 0.3, function()
        queued = false
        if emit(false) then WC.push() end
    end)
end

-- ---------------------------------------------------------------- events

local f = CreateFrame("Frame")
f:SetScript("OnEvent", function(_, e, unit)
    if e == "UNIT_AURA" or e == "UNIT_INVENTORY_CHANGED" then
        if unit ~= nil and unit ~= "player" then return end
    end
    soon()
end)
-- UNIT_AURA for the player only, where the client can filter it (as the game's buff frame registers it)
local okU = pcall(function() return f:RegisterUnitEvent("UNIT_AURA", "player") end)
if not okU then pcall(f.RegisterEvent, f, "UNIT_AURA") end
local okI = pcall(function() return f:RegisterUnitEvent("UNIT_INVENTORY_CHANGED", "player") end)
if not okI then pcall(f.RegisterEvent, f, "UNIT_INVENTORY_CHANGED") end
for _, e in ipairs({ "PLAYER_ENTERING_WORLD", "PLAYER_REGEN_DISABLED", "PLAYER_REGEN_ENABLED" }) do
    pcall(f.RegisterEvent, f, e)
end

-- ---------------------------------------------------------------- removing a buff (the user's pick 17:49)

local PICK_SECONDS = 5          -- the cancel tap must follow its pick within this
local armed = nil               -- { id, name, at } while the cancel cell holds a buff's name
local cell = nil                -- the auracancel secure button (made by TapPad, out of combat)

local function ack(op, id, ok, why, extra)
    local out = { type = "auraack", op = op, id = id, ok = ok and true or false, why = why, v = AV }
    if extra then for k, v in pairs(extra) do out[k] = v end end
    WC.send(WC.encode(out))
    soon()
    WC.push()
end

-- Our own secure button's attributes only, and only out of combat (the game forbids it in combat anyway).
local function disarm()
    armed = nil
    if cell and not InCombatLockdown() then
        pcall(cell.SetAttribute, cell, "type", nil)
        pcall(cell.SetAttribute, cell, "spell", nil)
    end
end

-- the buff with this instance ID, from the HELPFUL list (so a debuff can never be picked): its name, or nil + why
local function buffName(id)
    if type(C_UnitAuras) ~= "table" then return nil, "no aura API" end
    for x = 1, MAX.HELPFUL do
        local ok, d = CALL(C_UnitAuras.GetAuraDataByIndex, "player", x, "HELPFUL")
        if not ok then return nil, "the game refused the read" end
        d = P(d)
        if type(d) ~= "table" then break end
        if P(d.auraInstanceID) == id then
            local n = PSs(d.name)
            if not n or n == WC.SECRET then return nil, "the game hides its name" end
            return n
        end
    end
    return nil, "not a current buff"
end

local function onPick(key)
    -- the Character room's two fixed cells in this row (the pad owner's ruling 2026-09-30: the pad is full, so they share my own
    -- row): "open" / "close" take / let go of the controller for the room opened in the app (Character.lua). No game action.
    if key == "open" or key == "close" then
        local C = WC.character
        if type(C) == "table" then pcall(key == "open" and C.touchOpen or C.touchClose) end
        return
    end
    if InCombatLockdown() then return ack("pick", key, false, "in combat") end
    if type(key) ~= "number" then return ack("pick", nil, false, "unknown slot") end
    if not cell then return ack("pick", key, false, "no cancel cell") end
    local name, why = buffName(key)
    if not name then disarm(); return ack("pick", key, false, why) end
    local okT = pcall(cell.SetAttribute, cell, "type", "cancelaura")
    local okS = pcall(cell.SetAttribute, cell, "spell", name)
    if not (okT and okS) then disarm(); return ack("pick", key, false, "could not arm") end
    local mine = { id = key, name = name, at = GetTime() }
    armed = mine
    -- a pick with no cancel after it: disarm (out of combat; if combat began, at its end)
    C_Timer.After(PICK_SECONDS, function() if armed == mine and not InCombatLockdown() then disarm() end end)
    ack("pick", key, true, nil, { n = name, secs = PICK_SECONDS })
end

-- after the game's own secure action ran (or did nothing, when unarmed): say whether the buff is gone, then disarm
local checking = nil            -- the pick whose cancel is being checked: ONE answer per pick
local doneAt = -1e9             -- when the last cancel was answered: a repeat right after it gets no answer at all
local function onCancel()
    local a = armed
    -- MEASURED on the Thor (2026-09-29 20:55:08): one Remove gave TWO cancel answers (WCB1 1844 + 1845, the same ms): this
    -- hook ran twice for one tap (the secure cell's click on both press and release, or a second tap). The game's cancel
    -- itself is harmless twice; the answer must come once.
    if a and checking == a then return end
    if not a and GetTime() - doneAt < 1 then return end         -- the same tap's second call, after its answer
    if not a then return ack("cancel", nil, false, "nothing picked") end
    checking = a
    local late = GetTime() - a.at > PICK_SECONDS
    C_Timer.After(0.2, function()
        if checking == a then checking = nil end
        local still = buffName(a.id) ~= nil
        disarm()
        doneAt = GetTime()
        if late and still then return ack("cancel", a.id, false, "the pick had run out") end
        ack("cancel", a.id, not still, still and "still there" or nil, { n = a.name })
    end)
end

if WC.pad then
    -- the Character room's fixed cells FIRST (columns 0 and 1; Character.lua, loaded before this file, claims them too)
    if type(WC.pad.slotFor) == "function" then WC.pad.slotFor("aura", "open"); WC.pad.slotFor("aura", "close") end
    WC.pad.op("aurapick", "aura", onPick)
    -- THREE secure cells (the pad is full: the Character room's two share my own row, 2026-10-01): 0 = the buff cancel, no action
    -- until a pick arms it; 1 / 2 = the game's own Character window opened / closed the game's way (Character.lua owns them)
    local function initCell(btn, col)
        if col == 0 or col == nil then cell = btn; pcall(btn.SetAttribute, btn, "type", nil) return end
        local C = WC.character
        if type(C) ~= "table" then return end
        if col == 1 and type(C.initOpenCell) == "function" then pcall(C.initOpenCell, btn) end
        if col == 2 and type(C.initCloseCell) == "function" then pcall(C.initCloseCell, btn) end
    end
    local function onCell(col)
        if col == 0 or col == nil then return onCancel() end
        local C = WC.character
        if type(C) ~= "table" then return end
        if col == 1 and type(C.onOpenTap) == "function" then pcall(C.onOpenTap) end
        if col == 2 and type(C.onCloseTap) == "function" then pcall(C.onCloseTap) end
    end
    WC.pad.secureRow("auracancel", 3, initCell, onCell)
    WC.pad.onLayout(function() soon() end)
end
-- A FIGHT DISARMS THE CELL AT ITS START (the pad owner's rule, 2026-09-29): PLAYER_REGEN_DISABLED runs just before the game's
-- combat lockdown, so the attributes can still be cleared there; later they cannot (a SetAttribute on a protected frame in
-- combat is ADDON_ACTION_BLOCKED; disarm() checks InCombatLockdown() before every SetAttribute anyway). PLAYER_REGEN_ENABLED
-- stays as the safety net for a start the client did not announce in time.
local g = CreateFrame("Frame")
pcall(g.RegisterEvent, g, "PLAYER_REGEN_DISABLED")
pcall(g.RegisterEvent, g, "PLAYER_REGEN_ENABLED")
g:SetScript("OnEvent", function() if armed then disarm() end end)

-- RIDE THE CORE HEARTBEAT (as Character.lua and Quests do): the whole list again, marked "full".
local RIDE = { heartbeat = true, login = true, zone = true, manual = true }
if type(WC.snapshot) == "function" then
    hooksecurefunc(WC, "snapshot", function(reason)
        if RIDE[reason] then pcall(emit, true) end
    end)
end

WC.auras = { version = AV, emit = emit, tips = tipCache, armed = function() return armed end }     -- for tools/test_auras.py
