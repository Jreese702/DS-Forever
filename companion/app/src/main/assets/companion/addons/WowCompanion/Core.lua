-- WowCompanion / Core.lua  -- wiring: collect on change, emit, push.
local ADDON = ...
local WC = _G.WowCompanion

local function say(s)
    if DEFAULT_CHAT_FRAME then DEFAULT_CHAT_FRAME:AddMessage("|cffffd100WowCompanion|r " .. s) end
end

local lastBar = nil

-- Emit a full snapshot. Called on login, on any action-bar change, and on /wc.
local lastEquip = nil
function WC.snapshot(reason)
    local bar = WC.collectActionBar()
    local json = WC.encode(bar)
    -- EQUIPMENT HAS ITS OWN KEY. The bags session found PLAYER_EQUIPMENT_CHANGED snapshots, and
    -- the GET_ITEM_INFO_RECEIVED that follows once a new item's info arrives, both being dropped
    -- as duplicates because the action bar had not changed. Only those two events pay for
    -- collecting the equipment here: the bar events fire many times a second in combat.
    local equipped, equipJson
    if reason == "PLAYER_EQUIPMENT_CHANGED" or reason == "GET_ITEM_INFO_RECEIVED" then
        equipped = WC.collectEquipped()
        equipJson = WC.encode(equipped or {})
    end
    -- Skip an identical re-emit; the flush is what costs bytes, not the collect.
    -- The dedup key is the ACTION BAR (plus equipment, above), so anything that changes the
    -- MAP and not the bar must be listed here or it is silently swallowed -- which is exactly
    -- what happened to a freshly harvested vendor: the bar had not changed, so the snapshot
    -- carrying the new pin was dropped and the pin appeared only on the next unrelated change.
    -- Map.lua's "questlines" (the available quests changed) and "turnin" (a quest on this map
    -- became ready to hand in, or stopped being) are the same case: map-only changes. So is
    -- "supertrack": the player focused another quest, and the map's arrow must follow it. So is
    -- "tracking": Find Herbs / Minerals switched, and the map's node spots must follow it. So is
    -- "waypoint": the map pin was placed, moved or cleared (map.uwp).
    if json == lastBar and (equipJson == nil or equipJson == lastEquip)
       and reason ~= "manual" and reason ~= "zone" and reason ~= "harvest"
       and reason ~= "heartbeat" and reason ~= "questlines" and reason ~= "turnin"
       and reason ~= "supertrack" and reason ~= "tracking" and reason ~= "waypoint" then
        return false
    end
    lastBar = json
    equipped = equipped or WC.collectEquipped()
    lastEquip = equipJson or WC.encode(equipped or {})
    WC.emit({
        type = "full",
        v = WC.VERSION,
        reason = reason,
        actionbar = bar,
        player = WC.collectPlayer(),
        map = WC.collectMapSafe(),
        bags = WC.collectBags(),
        equipped = equipped,
    })
    return true
end

-- THE MAP NEVER STOPS THE BARS (test29: an error in the map's build - a key clash, Map.lua:1004 - threw out the whole "full"
-- document every 15 s, the action bar and bags with it, and the user's bars went grey). The map is built under pcall: an error
-- sends { ok = false, err } in its place and everything else goes out as always.
function WC.collectMapSafe()
    local ok, m = pcall(WC.collectMap)
    if ok then return m end
    return { ok = false, err = tostring(m):sub(1, 200) }
end

-- A forced snapshot, coalesced. The harvester fires on MERCHANT_SHOW etc., and the player is
-- standing at the thing when it does, so the map must include it on the very next push -- but
-- three events can land in one frame (a merchant that also repairs and trains) and each must not
-- pay its own 48 KiB pad burst.
local soonQueued = false
function WC.snapshotSoon(reason)
    if soonQueued then return end
    soonQueued = true
    C_Timer.After(0, function()
        soonQueued = false
        WC.snapshot(reason or "harvest")
    end)
end

local f = CreateFrame("Frame")
local started = false

local function start()
    if started then return end
    started = true
    WC.emit({ type = "hello", v = WC.VERSION, proto = WC.PROTO })
    WC.probeLineLengths()      -- one-shot; see Sink.lua


    -- MOVEMENT. Nothing in the event list fires when the player walks, which is why the map
    -- sat still. Poll position instead, emit only on actual change, and let the throttled
    -- flush bound the cost.
    local lastKey = ""
    local lastMapID = nil
    local moving = false
    -- 0.1 s. The map is a minimap replacement, so the sample rate is what makes the arrow
    -- glide rather than hop; 0.25 quantised movement into four steps a second.
    C_Timer.NewTicker(0.1, function()
        local okP, pos = pcall(WC.collectPos)          -- an error in the tick's build never breaks the ticker (test29)
        if not okP or not pos then return end
        WC.trailMap(pos.mapID)
        -- A NEW MAP means new quests, POIs and flight points. A position-only update would
        -- leave the old zone's markers on screen over the new zone's coordinates, which is
        -- exactly what happened walking from Elwynn Forest into Stormwind City.
        if pos.mapID ~= lastMapID then
            lastMapID = pos.mapID
            lastKey = ""
            WC.trailMap(pos.mapID)
            WC.snapshot("zone")
            return
        end
        -- indoors is in the key: stepping through a doorway changes nothing about x/y but it
        -- does change how the map should be framed, and without it that emit is deduped away.
        -- the soft-interact target too (Map.lua collectSoft): a new node in front of the player,
        -- or stepping into its range, must reach the map while standing still.
        local key = string.format("%.4f|%.4f|%.2f|%s|%s", pos.x or 0, pos.y or 0,
                                  pos.facing or 0, tostring(pos.indoors),
                                  WC.softKey and WC.softKey(pos.soft) or "")
        -- Unchanged means stopped. Two things are owed to the app at that moment:
        --  * ONE MORE UPDATE whose trail shows the stop. The app projects the arrow ahead
        --    along the current velocity to hide latency, and a stop otherwise reaches it only
        --    as silence, which it has to wait out before it believes it. A trail of standing
        --    samples is zero velocity it can act on immediately.
        --  * a flush. The last update may have been written inside the throttle window, and
        --    nothing else flushes until the 15 s heartbeat.
        -- pushThrottled is a no-op when nothing is pending, so standing still stays free.
        if key == lastKey then
            if moving then
                moving = false
                pos.tr = WC.drainTrail()
                WC.send(WC.encode({ type = "pos", map = pos }))
            end
            WC.pushThrottled()
            return
        end
        lastKey = key
        moving = true
        -- The 30 Hz sample ring rides along. It costs nothing -- see Trail.lua: payload
        -- bytes displace pad bytes one for one -- and it is what lets the app draw a smooth
        -- arrow from a slow flush instead of a stepped one from a fast flush.
        pos.tr = WC.drainTrail()
        pos.fms = math.floor(WC.flushMs * 100 + 0.5) / 100     -- what the last pad cost, ms
        pos.fmax = math.floor(WC.flushMax * 100 + 0.5) / 100
        WC.send(WC.encode({ type = "pos", map = pos }))
        WC.pushThrottled()
    end)

    -- HEARTBEAT. A full snapshot on a slow timer, emitted even when nothing changed.
    -- Without it the app has no way back to a drawn map after it loses the stream, and it loses
    -- the stream routinely: the log is ROTATED once it passes 48 MB (a flush is ~60 KB whatever
    -- it carries, so walking fills it in minutes), and a cold app start on an already-large log
    -- rotates immediately. Position updates alone cannot rebuild the map -- they carry no
    -- quests, POIs or services -- so the room sat on "Waiting for the game" until the player
    -- happened to change zone. 15 s costs one flush; the alternative is a blank map.
    C_Timer.NewTicker(15.0, function() WC.snapshot("heartbeat") end)

    -- Chat: buffered, not per-message. The General channel alone can produce several lines a
    -- second and each emit would otherwise cost its own pad burst.
    C_Timer.NewTicker(1.0, function()
        local ch = WC.collectChat()
        if not ch then return end
        WC.send(WC.encode({ type = "chat", chat = ch }))
        WC.pushThrottled()
    end)
    say("v" .. WC.VERSION .. " ready  |cff9a8b6e/wc|r to force a snapshot")
    WC.snapshot("login")
end

-- Every event Chat.lua asked for goes to the chat buffer, whatever its name. The router used to
-- test only for the "CHAT_MSG_" prefix, so UI_ERROR_MESSAGE, registered through the same list,
-- fell through to WC.snapshot, whose action-bar dedupe dropped it: the red error text never
-- reached the Chat room. Built ONCE, at load (Chat.lua is earlier in the .toc, so the list
-- exists by now); the router pays one table lookup per event, nothing more.
local CHAT_EVENT = {}
for _, e in ipairs(WC.CHAT_EVENTS or {}) do CHAT_EVENT[e] = true end

f:SetScript("OnEvent", function(_, event, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12)
    if event == "PLAYER_LOGIN" or event == "PLAYER_ENTERING_WORLD" then
        start()
    elseif WC.SERVICE_EVENT and WC.SERVICE_EVENT[event] then
        -- A service interaction. Emitted on its own rather than folded into the generic
        -- snapshot path, because WC.snapshot() suppresses an identical re-emit and a newly
        -- discovered vendor must never be the thing that gets suppressed.
        WC.onInteract(event)
    elseif CHAT_EVENT[event] then
        WC.onChatRaw(event, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12)  -- buffered
    elseif started then
        WC.snapshot(event)
    end
end)

-- RegisterEvent RETURNS a boolean on this client, so an event name this build does not have
-- is detectable instead of silently never firing. Report the misses rather than guessing.
local WANT = {
    "PLAYER_LOGIN", "PLAYER_ENTERING_WORLD",
    "BAG_UPDATE_DELAYED", "PLAYER_EQUIPMENT_CHANGED", "GET_ITEM_INFO_RECEIVED",
    "ACTIONBAR_SLOT_CHANGED", "ACTIONBAR_PAGE_CHANGED",
    "ACTIONBAR_UPDATE_USABLE", "ACTIONBAR_UPDATE_STATE",
    "UPDATE_BINDINGS", "PLAYER_REGEN_DISABLED", "PLAYER_REGEN_ENABLED",
}
local missing = {}
for _, e in ipairs(WC.CHAT_EVENTS or {}) do WANT[#WANT + 1] = e end
WC.SERVICE_EVENT = {}
for _, e in ipairs(WC.HARVEST_EVENTS or {}) do
    WANT[#WANT + 1] = e
    WC.SERVICE_EVENT[e] = true
end
for _, e in ipairs(WANT) do
    local ok, registered = pcall(function() return f:RegisterEvent(e) end)
    if not ok or registered == false then missing[#missing + 1] = e end
end
if #missing > 0 then
    pcall(function() WC.send(WC.encode({ type = "cap", missingEvents = missing })) end)
end

-- RELOAD KEY. The companion's Settings > Reload sends ONE key press, and this binds that key
-- to ReloadUI(). Typing "/reload" into chat does not work reliably here. MEASURED 2026-09-27:
-- the moment the game's chat box opens, Android's keyboard system takes the injected
-- characters, so the box opened and none of the seven characters ever arrived. A single key
-- never opens a text box.
-- It is an OVERRIDE binding: nothing is written to the player's own key bindings, and it
-- disappears with the addon. Bindings cannot change in combat, so it is applied out of combat.
-- The binding is reported as a `cap` so the app side can be checked without firing a reload.
-- NOT a character key. The first try was numpad "/": GameNative delivers Android's
-- KEYCODE_NUMPAD_DIVIDE as a plain "/" CHARACTER, so WoW saw its own "/" key and opened chat
-- with a slash in it (measured 2026-09-27, binding confirmed active at the time). F12 has no
-- character, so it can only arrive as itself.
local RELOAD_KEY = "F12"                   -- Android KEYCODE_F12 (142)
local reloadBtn = CreateFrame("Button", "WowCompanionReloadButton", UIParent)
reloadBtn:SetScript("OnClick", function() ReloadUI() end)
local rk = CreateFrame("Frame")
rk:RegisterEvent("PLAYER_LOGIN")
rk:RegisterEvent("PLAYER_REGEN_ENABLED")
rk:SetScript("OnEvent", function()
    if InCombatLockdown() then return end
    SetOverrideBindingClick(rk, true, RELOAD_KEY, "WowCompanionReloadButton")
    rk:UnregisterEvent("PLAYER_REGEN_ENABLED")
    pcall(function()
        WC.send(WC.encode({ type = "cap", reloadKey = RELOAD_KEY,
                            reloadBound = GetBindingAction(RELOAD_KEY, true) or "" }))
        WC.push()
    end)
end)

SLASH_WOWCOMPANION1 = "/wc"
SlashCmdList["WOWCOMPANION"] = function()
    WC.snapshot("manual")
    say("snapshot pushed (seq " .. WC.seqNow() .. ")")
end

-- /wcmap  -- report which minimap frames exist and whether they are hideable
-- /wcmap hide | show  -- actually do it. Reversible, refused in combat.
SLASH_WCMAP1 = "/wcmap"
SlashCmdList["WCMAP"] = function(arg)
    arg = (arg or ""):lower():gsub("^%s+", ""):gsub("%s+$", "")
    if arg == "hide" then
        local ok, which = WC.hideMinimap()
        say(ok and ("minimap hidden via " .. tostring(which)) or ("could not hide: " .. tostring(which)))
        WC.snapshot("manual")
        return
    elseif arg == "show" then
        local ok, which = WC.showMinimap()
        say(ok and ("minimap restored via " .. tostring(which)) or ("could not show: " .. tostring(which)))
        WC.snapshot("manual")
        return
    end
    local found = WC.probeMinimapFrames()
    WC.emit({ type = "cap", minimapFrames = found, map = WC.collectMapSafe() })
    if #found == 0 then
        say("|cffff5555no minimap frame found|r")
    else
        for _, f in ipairs(found) do
            say(f.name .. "  shown=" .. tostring(f.shown) .. "  hide()=" .. tostring(f.hasHide) .. "  protected=" .. tostring(f.protected))
        end
    end
    say("try |cffffd100/wcmap hide|r then |cffffd100/wcmap show|r")
end

-- /wccheck -- the coordinate self test, on demand and in chat.
-- This is the whole on-device validation: stand anywhere, run it, read errYards. Under about
-- 5 yards means the bundled world->map transform agrees with the client's own map position, so
-- the axes are right and this build's map rect matches the one the dataset was built against.
-- A failure here invalidates every bundled pin, which is why the app hides them rather than
-- drawing them dimmer.
SLASH_WCCHECK1 = "/wccheck"
SlashCmdList["WCCHECK"] = function()
    local chk = WC.checkCoords()
    local line
    if not chk or chk.ok == nil then
        line = "|cffff5555self test unavailable|r"
    elseif not chk.ok then
        line = "|cffff5555FAIL|r " .. tostring(chk.why or "") ..
               "  err " .. tostring(chk.errYards and math.floor(chk.errYards + 0.5) or "?") .. " yd"
    else
        line = "|cff8fc47aPASS|r  err " .. math.floor((chk.errYards or 0) + 0.5) .. " yd" ..
               "  uiMap " .. tostring(chk.uiMapID) .. "  continent " .. tostring(chk.continent)
    end
    if DEFAULT_CHAT_FRAME then DEFAULT_CHAT_FRAME:AddMessage("|cffffd100WowCompanion|r " .. line) end
    WC.emit({ type = "cap", coordCheck = chk })
end
