-- WowCompanion / Chat.lua
-- The CHAT room: mirror the chat log to the bottom screen.
--
-- Chat is the highest-volume thing the addon touches -- the General channel alone can produce
-- several lines a second -- so it is deliberately NOT emitted per message. New lines go into a
-- ring buffer and are flushed on the same throttled cadence as movement. Latency is
-- 48 KiB / rate, so emitting each line separately would cost a pad burst per line for no gain.
--
-- All text goes through S() or isPlain(), because chat carries player-authored strings and, on
-- this build, some unit identity can be secret. A secret reaching the wire would make C_Log
-- refuse the whole line silently.
--
-- THIS FILE OWNS ITS EVENTS, on its own frame, the way Bags.lua does. It used to hand Core.lua a
-- list to register, and two things went wrong there (measured on the Thor, 2026-09-27):
--   * UI_ERROR_MESSAGE never reached the room. Core routes only "CHAT_MSG_*" here and sends
--     everything else to WC.snapshot(), whose action-bar dedupe dropped it.
--   * Core passes a fixed a1..a12. Nothing here may depend on how many arguments that is.
-- WC.CHAT_EVENTS is therefore EMPTY, so Core registers nothing for chat, and WC.collectChat()
-- returns nil, so Core's own chat ticker sends nothing. No Core.lua edit is needed.

local ADDON = ...
local WC = _G.WowCompanion
local S, SN, isPlain = WC.S, WC.SN, WC.isPlain

local CHATV = "chat25"     -- chat25: "No player named ... is currently playing" kept out while QuestTrail is loaded (WC.notFound); chat24: the edit box's hiding OFF again (BOX_HIDE), its report kept; chat23: it hid the edit box that stands on its own (b-top-chat-bar)
local MAX_KEEP = 60          -- ring buffer depth; every document re-sends the whole ring
local ring, ringN = {}, 0
local dirty = false

-- The ring restarts at 1 on every /reload, so a line number alone does not identify a line.
-- The app keeps its own history and appends by (s, i); a new s tells it the numbering restarted.
local session = SN(function() return time() end) or math.floor(GetTime())

-- event -> the short channel tag the room displays
local CHANNELS = {
    CHAT_MSG_SAY = "say",
    CHAT_MSG_YELL = "yell",
    CHAT_MSG_PARTY = "party",
    CHAT_MSG_PARTY_LEADER = "party",
    CHAT_MSG_RAID = "raid",
    CHAT_MSG_RAID_LEADER = "raid",
    CHAT_MSG_RAID_WARNING = "raid",
    CHAT_MSG_INSTANCE_CHAT = "party",
    CHAT_MSG_INSTANCE_CHAT_LEADER = "party",
    CHAT_MSG_GUILD = "guild",
    CHAT_MSG_OFFICER = "officer",
    CHAT_MSG_WHISPER = "whisper",
    CHAT_MSG_WHISPER_INFORM = "whisper",
    CHAT_MSG_CHANNEL = "channel",
    CHAT_MSG_EMOTE = "emote",
    CHAT_MSG_TEXT_EMOTE = "emote",
    CHAT_MSG_SYSTEM = "system",
    CHAT_MSG_LOOT = "loot",
    CHAT_MSG_MONEY = "money",               -- chat17: money and currency in the game's own colours (UI Art: ChatTypeInfo)
    CHAT_MSG_CURRENCY = "currency",
    CHAT_MSG_SKILL = "system",
    -- what NPCs say: quest givers, bosses, the guard who answers a question
    CHAT_MSG_MONSTER_SAY = "npc",
    CHAT_MSG_MONSTER_YELL = "npc",
    CHAT_MSG_MONSTER_EMOTE = "npc",
    CHAT_MSG_MONSTER_WHISPER = "npc",
    CHAT_MSG_MONSTER_PARTY = "npc",
    -- The game's own red UI errors ("You have no target", "Not enough mana", "Out of range").
    -- On a second screen these are genuinely useful -- they are the feedback you miss when
    -- you are looking down -- AND they make the CAST room self-verifying: if a tap fires a
    -- keybind and the cast fails, the reason appears here instead of vanishing on the top
    -- screen after three seconds.
    UI_ERROR_MESSAGE = "error",
    -- the yellow ones: objective progress, "Discovered: ...", and the like
    UI_INFO_MESSAGE = "info",
    -- CHAT_MSG_TRADESKILLS deliberately NOT registered: it is other players' crafting
    -- ("X creates Linen Bandage"), which completely drowned real conversation in testing.
}

-- How the room should print a row. Default is "who: text".
--   plain = the text is already a whole sentence ("Bob waves at you.", "You receive loot: ...")
--   emote = "who text" with no colon ("Bob dances around")
local STYLE = {
    CHAT_MSG_TEXT_EMOTE = "plain", CHAT_MSG_SYSTEM = "plain", CHAT_MSG_SKILL = "plain",
    CHAT_MSG_LOOT = "plain", CHAT_MSG_MONEY = "plain", CHAT_MSG_CURRENCY = "plain", CHAT_MSG_MONSTER_EMOTE = "plain",
    UI_ERROR_MESSAGE = "plain", UI_INFO_MESSAGE = "plain",
    CHAT_MSG_EMOTE = "emote",
}

-- Core.lua reads this list to register chat events on ITS frame. Empty on purpose: see the top.
WC.CHAT_EVENTS = {}

-- Strip WoW's inline markup so the bottom screen renders readable text rather than escape
-- soup: |cAARRGGBB ... |r colour spans, |Hitem:...|h[Name]|h hyperlinks, |T...|t textures.
local function clean(s)
    if type(s) ~= "string" then return s end
    local ok, out = pcall(function()
        local t = s
        t = t:gsub("|c%x%x%x%x%x%x%x%x", "")
        -- This client also uses the NEWER named-colour form, e.g. "|cnIQ1:[Iron Shield]|r"
        -- for item-quality colours. The old 8-hex pattern does not match it, so item links in
        -- Trade chat were rendering as "WTS |cnIQ1:[Iron Shield".
        t = t:gsub("|cn[%w_]+:", "")
        t = t:gsub("|r", "")
        t = t:gsub("|H.-|h(.-)|h", "%1")
        t = t:gsub("|T.-|t", "")
        t = t:gsub("|A.-|a", "")
        t = t:gsub("|K.-|k", "")
        t = t:gsub("|n", " ")
        t = t:gsub("||", "|")
        return t
    end)
    return ok and out or s
end

-- THE LINKS IN A LINE (the user's Chat rework, 2026-10-07: "the ability to view various things linked in chat when selecting
-- a message"; first round, the user's pick: items, spells, quests and players). [clean] keeps only a link's shown name, so the
-- links are read from the raw text first: |H<kind>:<payload>|h[<name>]|h. Each becomes { k = kind, id = the first number, n =
-- the name as shown (no brackets), p = the inner payload for items and spells (the tooltip key), the name for a player }.
-- At most LINK_MAX a line (Trade spam), payloads longer than LINK_LEN dropped. Only plain strings (a secret text never gets here).
local LINK_KINDS = { item = true, spell = true, quest = true, player = true }
local LINK_MAX, LINK_LEN = 8, 160

-- THE LINKS' OWN COLOURS (chat16; the user: "the links in chat should have the different colors like they normally do"). The game
-- colours a link by the code just before it: |cAARRGGBB, or a NAMED colour |cnNAME: (an item's quality: IQ0 .. IQ8, or one of
-- the game's colour objects). A link with no code gets its kind's usual colour; one the game leaves plain stays the line's.
local KIND_HEX = { item = "ffffff", spell = "71d5ff", quest = "ffff00", achievement = "ffff00", currency = "00aaff",
                   trade = "ffd000", enchant = "ffd000", talent = "4e96f7", instancelock = "ff8000" }
local function namedHex(name)
    local q = tonumber(name:match("^IQ(%d+)$"))
    if q then
        if type(C_Item) == "table" and type(C_Item.GetItemQualityColor) == "function" then
            local ok, _, _, _, hex = pcall(C_Item.GetItemQualityColor, q)
            if ok and type(hex) == "string" and #hex >= 6 then return hex:sub(-6):lower() end
        end
        local c = type(ITEM_QUALITY_COLORS) == "table" and ITEM_QUALITY_COLORS[q]
        if type(c) == "table" and type(c.hex) == "string" then return c.hex:sub(-6):lower() end
        return nil
    end
    local g = _G[name]
    if type(g) == "table" and type(g.GenerateHexColor) == "function" then
        local ok, hex = pcall(g.GenerateHexColor, g)
        if ok and type(hex) == "string" and #hex >= 6 then return hex:sub(-6):lower() end
    end
    return nil
end

-- returns links (the ones a line's cursor can open: items, spells, quests, players; with their colour) and lc (EVERY link's
-- name and colour, in order, for drawing the line as the game draws it)
local function linksOf(raw)
    if type(raw) ~= "string" or not raw:find("|H", 1, true) then return nil, nil end
    local ok, out, lc = pcall(function()
        local list, colours = {}, {}
        local pos = 1
        while #colours < LINK_MAX do
            local s, e, payload, shown = raw:find("|H([^|]+)|h(.-)|h", pos)
            if not s then break end
            pos = e + 1
            local before = raw:sub(math.max(1, s - 64), s - 1)
            local hex = before:match("|c%x%x(%x%x%x%x%x%x)$")
            if not hex then
                local nm = before:match("|cn([%w_]+):$")
                if nm then hex = namedHex(nm) end
            end
            local kind = payload:match("^(%a+):")
            hex = hex and hex:lower() or (kind and KIND_HEX[kind]) or nil
            local name = shown:gsub("|c%x%x%x%x%x%x%x%x", ""):gsub("|cn[%w_]+:", ""):gsub("|r", "")
            name = name:match("^%[(.*)%]$") or name
            if hex and name ~= "" then colours[#colours + 1] = { n = name, c = hex } end
            if kind and LINK_KINDS[kind] and #payload <= LINK_LEN and #list < LINK_MAX then
                local l = { k = kind, n = name, c = hex }
                if kind == "player" then
                    l.p = payload:match("^player:([^:]+)")
                else
                    l.id = tonumber(payload:match("^%a+:(%-?%d+)"))
                    l.p = payload                                   -- the tooltip key (WC.tipOf "link <payload>")
                end
                if (kind == "player" and l.p) or l.id then list[#list + 1] = l end    -- an item / spell / quest needs its id
            end
        end
        return #list > 0 and list or nil, #colours > 0 and colours or nil
    end)
    if not ok then return nil, nil end
    return out, lc
end

-- Varargs with their real count. {...} loses trailing nils and #t is undefined across holes,
-- and chat events are full of nils.
local function pack(...) return { n = select("#", ...), ... } end

-- type() is safe on a secret; every other operation on one throws. So: type first, then
-- isPlain, and only then compare or index.
local function str(v) return type(v) == "string" and isPlain(v) and v or nil end
local function num(v) return type(v) == "number" and isPlain(v) and v or nil end

local function isPlayerGUID(v)
    local s = str(v)
    return s ~= nil and s:sub(1, 7) == "Player-"
end

-- CHAT_MSG_* ARGUMENT ORDER, as documented for the retail lineage:
--   1 text · 2 sender · 3 language · 4 channelName ("2. Trade - City") · 5 target · 6 flags
--   7 zoneChannelID · 8 channelIndex · 9 channelBaseName · 10 languageID · 11 lineID · 12 guid
-- The first version read the channel index from 7 and the guid from 11. It got the zone channel
-- id (LookingForGroup = 26, not 4) and a line number, so it never asked for a class: 0 of 34
-- lines on the Thor carried one. Rather than trust a position twice:
--   * the guid is whichever argument LOOKS like a player guid, 12 first;
--   * the channel number is the one printed in the channel's own name, then argument 8.
-- reportArgs() puts what the event really carried on the wire, once per event name.
local function findGuid(a)
    if isPlayerGUID(a[12]) then return a[12], 12 end
    for i = 1, a.n do
        if isPlayerGUID(a[i]) then return a[i], i end
    end
    return nil, 0
end

local function channelIndex(a, chan)
    if chan then
        local n = tonumber(chan:match("^(%d+)%."))
        if n then return n end
    end
    local n = num(a[8])
    if n and n > 0 then return n end      -- say, whisper, system: argument 8 is 0
    return nil
end

local reported = {}
local function reportArgs(event, a, guidAt)
    if reported[event] then return end
    reported[event] = true
    local types = {}
    for i = 1, a.n do
        types[i] = type(a[i]) .. (isPlain(a[i]) and "" or "!")     -- "!" = secret
    end
    -- no push: it rides out with the chat document this same event produces
    WC.send(WC.encode({ type = "cap", probe = "chatargs", v = CHATV, event = event,
                        count = a.n, types = types, guidAt = guidAt }))
end

-- One-shot raw-arg dump, by hand. /wcargs arms it; the next chat line's arguments are sent
-- with their values (first 42 characters), which reportArgs() deliberately leaves out.
local dumpNext = false
SLASH_WCARGS1 = "/wcargs"
SlashCmdList["WCARGS"] = function()
    dumpNext = true
    if DEFAULT_CHAT_FRAME then
        DEFAULT_CHAT_FRAME:AddMessage("|cffffd100WowCompanion|r armed: next chat line's raw args will be dumped")
    end
end

local function dumpArgs(event, a)
    local args = {}
    for i = 1, a.n do
        local v = a[i]
        local shown = "?"
        if isPlain(v) then shown = tostring(v):sub(1, 42) end
        args[#args + 1] = { i = i, ty = type(v), v = shown }
    end
    WC.emit({ type = "cap", probe = "chatargs", v = CHATV, event = event, count = a.n, args = args })
end

-- sender's class, for the class-coloured name. A class never changes, so ask once per guid.
local classOf, classN = {}, 0
local function classFor(guid)
    local known = classOf[guid]
    if known ~= nil then return known or nil end
    local r = pack(pcall(GetPlayerInfoByGUID, guid))
    -- r[1] = pcall ok, r[2] = localised class, r[3] = ENGLISH class ("PRIEST")
    local cls = r[1] and str(r[3]) or nil
    if cls == "" then cls = nil end
    -- The client may not know a sender yet; only a real answer is remembered.
    if cls then
        if classN >= 400 then classOf, classN = {}, 0 end
        classOf[guid] = cls
        classN = classN + 1
    end
    return cls
end

local myGuid = nil
local function isMe(guid)
    if not myGuid then
        local ok, g = pcall(UnitGUID, "player")
        myGuid = ok and str(g) or nil
    end
    return myGuid ~= nil and guid == myGuid
end

local wantTips          -- the links' tooltips, further down
local topRefresh        -- the chat on the top screen, further down
local function add(row)
    if row.links and wantTips then wantTips(row.links) end
    ringN = ringN + 1
    row.i = ringN
    row.t = GetTime()
    local ok, hm = pcall(date, "%H:%M")         -- the wall clock, for the room's time marks
    row.hm = ok and str(hm) or nil
    ring[(ringN - 1) % MAX_KEEP + 1] = row
    dirty = true
    if topRefresh then topRefresh() end
end

-- The red and yellow screen-centre messages. Holding a button down repeats "Out of range" many
-- times a second, so an identical message inside 3 s is one row, not twenty.
local lastUi = { text = nil, at = 0 }
local function onUiMessage(event, a)
    -- (messageType, message): the text is the second argument; take the first string otherwise
    local text = str(a[2])
    if not text then
        for i = 1, a.n do
            text = str(a[i])
            if text then break end
        end
    end
    if not text or text == "" then return end
    text = clean(text)
    local now = GetTime()
    if text == lastUi.text and now - lastUi.at < 3 then return end
    lastUi.text, lastUi.at = text, now
    add({ ch = CHANNELS[event], text = text, style = "plain" })
end

-- WHERE THE PLAYER CAN TALK. The room's channel row offers only what can be used right now
-- (user ruling 2026-09-27: no Guild for a player in no guild, no Party for a player alone).
local function yes(fn)
    if type(fn) ~= "function" then return false end
    local ok, v = pcall(function() return fn() == true end)
    return ok and v == true
end

-- the channels the player has joined (General, Trade, LocalDefense, LookingForGroup, custom ones): GetChannelList gives
-- id, name, disabled for each (id, name on clients that give pairs)
local function chans()
    if type(GetChannelList) ~= "function" then return nil end
    local ok, t = pcall(function() return { GetChannelList() } end)
    if not ok or type(t) ~= "table" or #t == 0 then return nil end
    local step = (type(t[3]) == "number") and 2 or 3
    local out = {}
    for k = 1, #t, step do
        local id, name = num(t[k]), str(t[k + 1])
        if id and name and not (step == 3 and t[k + 2] == true) then out[#out + 1] = { i = id, n = name } end
    end
    return #out > 0 and out or nil
end

local function talk()
    return { guild = yes(IsInGuild), party = yes(IsInGroup), raid = yes(IsInRaid), chans = chans() }
end

local lastTalk = nil
local function talkChanged()
    local key = WC.encode(talk())
    if key == lastTalk then return false end
    lastTalk = key
    return true
end

-- joining or leaving a guild or a group sends a document even when nobody has spoken
local STATE_EVENTS = { PLAYER_GUILD_UPDATE = true, GROUP_ROSTER_UPDATE = true, PLAYER_ENTERING_WORLD = true,
                       CHANNEL_UI_UPDATE = true, ZONE_CHANGED_NEW_AREA = true }      -- chat16: a city's channels come and go
local BLOCKED_EVENTS = { ADDON_ACTION_BLOCKED = true, ADDON_ACTION_FORBIDDEN = true }

-- test60 (the user: "when I log in it's randomly telling me in chat 'no player named <name> is online'"): QuestTrail's Shared Trail
-- whispers its peers (add-on messages, WHISPER); one gone offline answers with the game's ERR_CHAT_PLAYER_NOT_FOUND_S. QuestTrail keeps
-- out the ones it can tie to its own whispers, not all. While QuestTrail is loaded, those lines are kept out of both chats (this one and,
-- by a chat filter, the game's), unless the player whispered that name themselves in the last 2 minutes. Runtime only: nothing saved.
WC.notFound = { own = {} }
do
    local g = rawget(_G, "ERR_CHAT_PLAYER_NOT_FOUND_S")       -- the game's own words, localised
    if type(g) == "string" then
        WC.notFound.pat = "^" .. g:gsub("([%(%)%.%%%+%-%*%?%[%]%^%$])", "%%%1"):gsub("%%%%s", "(.+)") .. "$"
    end
end
function WC.notFound.key(name) return type(name) == "string" and (name:match("^[^-]+") or name):lower() or nil end
function WC.notFound.qt()
    local f = type(C_AddOns) == "table" and C_AddOns.IsAddOnLoaded
    if type(f) ~= "function" then f = rawget(_G, "IsAddOnLoaded") end
    if type(f) ~= "function" then return false end
    local ok, r = pcall(f, "QuestTrail")
    return ok and r and true or false
end
function WC.notFound.hide(text)
    local nf = WC.notFound
    if not nf.pat or type(text) ~= "string" then return false end
    local who = text:match(nf.pat)
    if not who or not nf.qt() then return false end
    local at = nf.own[nf.key(who)]
    if at and type(GetTime) == "function" and GetTime() - at < 120 then return false end   -- the player's own whisper: its answer kept
    return true
end
do
    local function own(_, chatType, _, target)
        if chatType == "WHISPER" and type(target) == "string" and type(GetTime) == "function" then WC.notFound.own[WC.notFound.key(target)] = GetTime() end
    end
    if type(hooksecurefunc) == "function" then
        if type(rawget(_G, "SendChatMessage")) == "function" then pcall(hooksecurefunc, "SendChatMessage", own) end
        if type(C_ChatInfo) == "table" and type(C_ChatInfo.SendChatMessage) == "function" then pcall(hooksecurefunc, C_ChatInfo, "SendChatMessage", own) end
    end
    local add = rawget(_G, "ChatFrame_AddMessageEventFilter")
    if type(add) == "function" then pcall(add, "CHAT_MSG_SYSTEM", function(_, _, text) return WC.notFound.hide(text) end) end
end

local function onChatEvent(event, ...)
    if event == "CHAT_MSG_SYSTEM" and WC.notFound.hide((...)) then return end
    if STATE_EVENTS[event] then
        if talkChanged() then dirty = true end
        return
    end
    local a = pack(...)
    if dumpNext and event:sub(1, 9) == "CHAT_MSG_" then
        dumpNext = false
        dumpArgs(event, a)
    end
    if event == "UI_ERROR_MESSAGE" or event == "UI_INFO_MESSAGE" then
        return onUiMessage(event, a)
    end

    local guid, guidAt = findGuid(a)
    reportArgs(event, a, guidAt)

    -- A row whose text is secret or missing cannot be shown at all. A secret SENDER only costs
    -- the name, so that row stays.
    local text = str(a[1])
    if not text then return end
    local who = str(a[2])
    if who == "" then who = nil end
    local chan = str(a[4])
    if chan == "" then chan = nil end

    local links, lc = linksOf(text)
    text = clean(text)
    -- NPC emotes carry the speaker as a placeholder: "%s goes into a frenzy!"
    if event == "CHAT_MSG_MONSTER_EMOTE" and who then
        local ok, out = pcall(function() return (text:gsub("%%s", function() return who end)) end)
        if ok then text = out end
    end

    local row = {
        ch = CHANNELS[event] or "other",
        who = who,
        text = text,
        chan = chan,
        idx = channelIndex(a, chan),
        style = STYLE[event],
        links = links,
        lc = lc,
    }
    if event == "CHAT_MSG_WHISPER_INFORM" then row.dir = "out" end      -- "who" is the recipient
    if event == "CHAT_MSG_WHISPER" and who then WC.chatLastTell = who end   -- quick chat's Reply goes here
    if guid then
        row.cls = classFor(guid)
        if row.dir ~= "out" and isMe(guid) then row.me = true end
    end
    add(row)
end

-- Core.lua still calls these two names. Neither is reached once WC.CHAT_EVENTS is empty, but an
-- older Core.lua may be on the device, so they stay and do the right thing.
function WC.onChat(event, ...) return onChatEvent(event, ...) end
function WC.onChatRaw(event, ...) return onChatEvent(event, ...) end

local function document()
    local out, n = {}, math.min(ringN, MAX_KEEP)
    local start = ringN - n
    for k = 1, n do
        local r = ring[(start + k - 1) % MAX_KEEP + 1]
        if r then out[#out + 1] = r end
    end
    return { type = "chat", chat = { v = CHATV, s = session, n = ringN, lines = out, can = talk() } }
end

--- Core.lua's chat ticker asks for this every second and sends whatever it returns. This file
--- sends its own documents (below), so the answer is always "nothing new".
function WC.collectChat() return nil end
function WC.chatDirty() return dirty end

-- EMIT: at most one document a second, and only when a line arrived.
-- pushThrottled() refuses when another flush happened inside the last 0.2 s and nothing re-arms
-- it, which would strand this document until the 15 s heartbeat. So a send is followed by two
-- more asks, a second apart; an ask with nothing pending is free.
-- ---------------------------------------------------------------- the world-refresh notice (chat21)
-- The user (2026-10-09): "a world notification ... the world is going to refresh in 5 minutes ... make it a notification above
-- my chat in the home". It is the game's SHARD TRANSFER (UI Art, from this build's UI source, Blizzard_StaticPopup_Game
-- GameDialogDefs.lua): the pop-up "SHARD_TRANSFER_IMMINENT_EVENT", its time GetEvictionTimeRemaining(), its words
-- SHARD_TRANSFER_COUNTDOWN_STATIC_POPUP_MESSAGE (minutes, then seconds in the last minute) and SHARD_TRANSFER_ANYTIME at 0;
-- "SHARD_TRANSFER_IMMEDIATE" says SHARD_TRANSFER_NOW. It is not a chat message. This only READS (the time, whether the game's
-- pop-up shows, the game's own strings): nothing is pressed, opened or closed, and nothing is kept (the accessory ruling).
-- Home draws the strip: {type "notice", kind "shard", id, left (s), text, urgent} when it starts, when its wording changes
-- (minutes -> seconds -> "any time" -> "now") and every NOTICE_EVERY s between; {id, ended = true} once when it is gone.
local NOTICE_EVERY = 10
local notice = nil          -- the one running: { id, phase, at }
local noticeN = 0

local function popupShown(which)
    local fn = _G.StaticPopup_Visible
    if type(fn) ~= "function" then return false end
    local ok, v = pcall(fn, which)
    return ok and v ~= nil and v ~= false
end

-- the time left, and whether it could be read at all (a value the game keeps secret is not "nothing left")
local function evictionLeft()
    local fn = _G.GetEvictionTimeRemaining
    if type(fn) ~= "function" then return nil, false end
    local ok, v = pcall(fn)
    if not ok then return nil, false end
    if v == nil then return 0, true end
    v = num(v)
    return v, v ~= nil
end

-- the pop-up's own words, as GameDialogDefs.lua builds them
local function noticeText(left, now)
    if now then return str(_G.SHARD_TRANSFER_NOW) end
    if left <= 0 then return str(_G.SHARD_TRANSFER_ANYTIME) end
    local fmt = str(_G.SHARD_TRANSFER_COUNTDOWN_STATIC_POPUP_MESSAGE)
    if not fmt then return nil end
    local ok, out
    if left < 60 then
        ok, out = pcall(string.format, fmt, math.ceil(left), str(_G.SECONDS) or "seconds")
    else
        ok, out = pcall(string.format, fmt, math.ceil(left / 60), str(_G.MINUTES) or "minutes")
    end
    return ok and str(out) or nil
end

local function noticeTick()
    local left, readable = evictionLeft()
    local now = popupShown("SHARD_TRANSFER_IMMEDIATE")
    local shown = now or popupShown("SHARD_TRANSFER_IMMINENT_EVENT")
    if not readable and not shown then return end          -- nothing to go by: the running one stays as it is
    left = (left and left > 0) and left or 0
    if not (shown or left > 0) then
        if notice then
            WC.emit({ type = "notice", v = CHATV, id = notice.id, kind = "shard", ended = true })
            notice = nil
        end
        return
    end
    local phase = now and "now" or (left <= 0 and "any") or (left < 60 and "sec") or "min"
    local t = GetTime()
    if not notice then
        noticeN = noticeN + 1
        notice = { id = tostring(session) .. "-" .. noticeN }
    end
    if phase == notice.phase and t - notice.at < NOTICE_EVERY then return end
    notice.phase, notice.at = phase, t
    WC.emit({ type = "notice", v = CHATV, id = notice.id, kind = "shard", left = math.ceil(left), urgent = phase ~= "min",
              text = noticeText(left, now) })
end

local owed = 0
local blockedDue            -- the refusal reporter's counts, further down
local linkTips              -- the links' tooltips, further down
local topCheck              -- the chat on the top screen, further down
C_Timer.NewTicker(1.0, function()
    blockedDue()
    linkTips()
    topCheck()
    pcall(noticeTick)
    if dirty then
        dirty = false
        WC.send(WC.encode(document()))
        owed = 3
    end
    if owed > 0 then
        owed = owed - 1
        WC.pushThrottled()
    end
end)

-- ---------------------------------------------------------------- events

local f = CreateFrame("Frame")
local missing = {}
for e in pairs(CHANNELS) do
    local ok, registered = pcall(function() return f:RegisterEvent(e) end)
    if not ok or registered == false then missing[#missing + 1] = e end
end
for _, set in ipairs({ STATE_EVENTS, BLOCKED_EVENTS }) do
    for e in pairs(set) do
        local ok, registered = pcall(function() return f:RegisterEvent(e) end)
        if not ok or registered == false then missing[#missing + 1] = e end
    end
end
table.sort(missing)
pcall(function() f:RegisterEvent("PLAYER_LOGIN") end)
pcall(function() f:RegisterEvent("PLAYER_REGEN_ENABLED") end)
pcall(function() f:RegisterEvent("ADDON_LOADED") end)      -- the hub is an addon of its own
pcall(function() f:RegisterEvent("PLAYER_LOGOUT") end)     -- a /reload too: the top chat's last lines are kept (chat14)

-- RIDE THE CORE HEARTBEAT, the way the Quests feed does. WC.snapshot() emits and schedules its
-- push for the end of the frame; a document sent in this post-hook lands in that same pad
-- burst, so re-sending the ring every 15 s costs nothing extra. It is what refills the room
-- after the app restarts or the log is rotated. Only on reasons that always emit.
local RIDE = { heartbeat = true, login = true, zone = true, manual = true }
local riding = false
local function ride()
    if riding or type(WC.snapshot) ~= "function" or type(hooksecurefunc) ~= "function" then return end
    riding = true
    hooksecurefunc(WC, "snapshot", function(reason)
        if RIDE[reason] then
            dirty = false
            WC.send(WC.encode(document()))
        end
    end)
end

-- WHICH MODIFIER KEYS THE GAME BELIEVES ARE HELD: 1 = Alt, 2 = Ctrl, 4 = Shift, added up. In
-- the load report. On 2026-09-27 a running game stopped taking typed letters and the controller
-- with them, and only a restart cured it; if it happens again, this is the first thing to read.
local function modifiers()
    local n = 0
    if yes(IsAltKeyDown) then n = n + 1 end
    if yes(IsControlKeyDown) then n = n + 2 end
    if yes(IsShiftKeyDown) then n = n + 4 end
    return n
end

-- ---------------------------------------------------------------- closing the box: NOT from here
-- THE GAME LEAVES ITS CHAT BOX OPEN. Measured on the Thor, 2026-09-27, in a healthy instance:
-- after a send the box kept focus for 49 s, and after Esc it emptied the box and kept focus.
-- A focused box puts the controller in "Focus/Unfocus Interface" mode.
--
-- chat5 closed it from here with ClearFocus(), 0.2 s after Esc or Enter. IT WORKED AND IT MUST NOT
-- BE DONE: the box closed and the controller went back to play, and the game at once raised
-- "WowCompanion has been blocked from an action only available to the Blizzard UI", with
-- Disable on the A button. Whatever the game runs when the box loses focus, it ran inside this
-- addon's call and was refused. An addon cannot close this box cleanly. Do not add it back.

-- ---------------------------------------------------------------- sending: NOT from here
-- The composer on the bottom screen sends by TYPED CHAT: the app types the line into the game's
-- own chat box (user ruling 2026-09-28). chat7 took letters as taps on the shared pad and sent
-- them with C_ChatInfo.SendChatMessage. On the Thor every one of those taps took the keyboard
-- away from the bottom screen, and none of them arrived here. It is gone, rows and all.

-- ---------------------------------------------------------------- the game's Chat Style: READ, NEVER WRITTEN
-- chat8-chat19 set the CVar "chatStyle" to Classic at login, so that the game's own chat box closed after the old typed-chat
-- route's Enter. That route is gone (the rework sends from this file, SendChatMessage, and never opens the box), and the
-- setting is the PLAYER'S: the game keeps it for the account, so the PC took the change too. User ruling 2026-10-09: the
-- companion must not change the player's normal setup. chat20 only reports what it is, once per login; nothing here works
-- differently under IM or Classic.
local STYLE_CVAR = "chatStyle"
local styleDone = false

local function styleInfo()
    if type(C_CVar) ~= "table" or type(C_CVar.GetCVarInfo) ~= "function" then return nil end
    -- ok, value, default, ...
    local r = pack(pcall(C_CVar.GetCVarInfo, STYLE_CVAR))
    if not r[1] then return nil end
    return { value = str(r[2]), default = str(r[3]) }
end

local function reportStyle()
    if styleDone then return end
    styleDone = true
    local out = { type = "cap", probe = "chatstyle", v = CHATV }
    local info = styleInfo()
    if not info or not info.value then
        out.why = "the game did not say what it is"
    else
        out.now, out.default = info.value, info.default
    end
    WC.emit(out)
end

-- WHAT THE GAME REFUSES. Both events name the addon and the function, so a refusal that a
-- player sees as a dialog is on the wire as well, whichever file of this addon caused it.
--
-- CAPPED, BECAUSE IT FROZE THE GAME. chat8 sent one message per refusal and pushed each one.
-- On 2026-09-28 at 11:06 Blizzard's gamepad action bar was tainted and the game refused
-- SetPreferredGamepadInteractTarget() about 20 times a second, 4,012 times in three minutes.
-- Every report asked for a flush, a flush is a ~61 KB burst of padding, and the game stalled for
-- minutes at a time until it was restarted. The reporter made a bad moment much worse.
--   * the FIRST refusal of a function is reported at once;
--   * after that it is COUNTED, and the count goes out at most every BLOCK_EVERY seconds;
--   * a count never asks for a flush. It rides out with whatever flushes next, at the latest the
--     15 s heartbeat. Even a first report asks for one at most every BLOCK_PUSH seconds, so a
--     hundred different functions refused in one frame are one flush, not a hundred;
--   * no more than BLOCK_KEYS functions are told apart. The rest are counted together.
-- A refusal now costs a table lookup and two additions.
local BLOCK_EVERY, BLOCK_PUSH, BLOCK_KEYS = 10, 2, 40
local blocked, blockedN = {}, 0
local lastBlockPush = -math.huge

local function blockedReport(b, now, first)
    WC.send(WC.encode({ type = "cap", probe = "blocked", v = CHATV, event = b.event, addon = b.addon,
                        fn = b.fn, n = b.unsent, total = b.total, combat = yes(InCombatLockdown) }))
    b.unsent, b.at = 0, now
    if first and now - lastBlockPush >= BLOCK_PUSH then
        lastBlockPush = now
        WC.push()
    end
end

local alphaRefused           -- the chat hide's off switch, further down

local function onBlocked(event, addon, fn)
    local a, f = str(addon) or "?", str(fn) or "?"
    if f:find("SetAlpha", 1, true) then alphaRefused() end
    local key = event .. "|" .. a .. "|" .. f
    local b = blocked[key]
    if not b then
        if blockedN >= BLOCK_KEYS then
            key, a, f = event .. "|*", "*", "*"         -- too many to tell apart
            b = blocked[key]
        end
        if not b then
            b = { event = event, addon = a, fn = f, total = 0, unsent = 0, at = -math.huge }
            blocked[key] = b
            blockedN = blockedN + 1
        end
    end
    b.total, b.unsent = b.total + 1, b.unsent + 1
    local now = GetTime()
    if b.total == 1 then
        blockedReport(b, now, true)
    elseif now - b.at >= BLOCK_EVERY then
        blockedReport(b, now, false)
    end
end

-- once a second, from the chat ticker: the count of a flood that has STOPPED still has to go out
blockedDue = function()
    if blockedN == 0 then return end
    local now = GetTime()
    for _, b in pairs(blocked) do
        if b.unsent > 0 and now - b.at >= BLOCK_EVERY then blockedReport(b, now, false) end
    end
end

-- ---------------------------------------------------------------- the game's own chat, hidden
-- USER RULING 2026-09-28 (the tracking session's picker): "Yes, hide them while Home shows", the
-- game's quest tracker, chat and controller bars. WHEN is the hub's business (WowCompanionHub):
-- this file registers the element "chat" there and does what the hub asks.
--
-- ALPHA ONLY, AND ONLY ON FRAMES WHOSE ALPHA BLIZZARD NEVER TOUCHES. Read from this build's own
-- UI source (chat, controller UI, Edit Mode, UIParent):
--   ChatFrame1 .. ChatFrameN   Blizzard never sets and never reads the alpha of the window itself.
--                              It fades the window's textures, scroll bar and button frame all
--                              the time; those are children, so they go invisible WITH the window
--                              and are not touched here.
--   GeneralDockManager         holds the tabs of the docked windows. The same: never set, never
--                              read. The tabs themselves are faded by Blizzard and not touched.
-- Blizzard reads back the alpha of everything it fades. An addon's number in there would be read
-- by Blizzard's code on its way to the controller UI, which is how the flood of 11:06 began. So:
--   * NEVER the edit box's focus, its text or a hook on it. Its ALPHA only in one case (b-top-chat-bar, below at `boxes`): a box
--     that still SHOWS once its window is hidden (it stands on its own) and does not have the keyboard; its own alpha is kept and
--     given back the moment it takes the keyboard. A box that goes with its window is never touched. (The 11:06 flood was a
--     `/wcpad show` typed into the chat, TapPad.lua's record, not an alpha.)
--   * never a tab, a texture, the scroll bar or the button frame.
--   * never Hide(), SetParent() or a field written into a Blizzard table. IsShown() is what the
--     controller's chat shortcut asks before it gives the window the focus.
-- A /reload brings everything back: the alpha lives in memory only.
--
-- THE BOX IS A CHILD OF THE WINDOW, so it is invisible with it. A box that is open and cannot be
-- seen holds the keyboard and the controller, and the player cannot see why. So while a chat box
-- has the keyboard the windows are SHOWN, and hidden again when it lets go. That is read with
-- GetCurrentKeyBoardFocus(), four times a second, only while the chat is hidden; the box is
-- compared, never called.
--
-- WHAT STAYS VISIBLE: the tab of a window the player dragged OUT of the dock (its parent is the
-- whole screen and Blizzard fades it).
--
-- ONE ATTEMPT PER CHANGE. Nothing here retries on a timer, so a refusal cannot repeat itself.
-- IT FAILS TOWARDS A VISIBLE CHAT. If the game ever refuses a SetAlpha to an addon, or anything in
-- here throws, the chat is given back, hiding is OFF until the next reload, and only showing is
-- still done.
local HIDE_POLL = 0.25
local chatHidden = false        -- what the hub asked for
local chatOpen = false          -- hidden was asked for, and the windows are shown for an open box
local hideOff = false           -- the game refused an alpha change: no more hiding
local lastTyping = nil          -- what the poll saw last, so that a change is tried once
local lastCount = 0

local function chatNames()
    local names, seen = {}, {}
    local function add(n)
        if type(n) == "string" and isPlain(n) and not seen[n] then
            seen[n] = true
            names[#names + 1] = n
        end
    end
    for i = 1, math.min(num(NUM_CHAT_WINDOWS) or 10, 40) do add("ChatFrame" .. i) end
    pcall(function()
        if type(CHAT_FRAMES) ~= "table" then return end     -- the game's own list: temporary windows too
        for i = 1, 40 do
            local n = CHAT_FRAMES[i]
            if n == nil then break end
            add(n)
        end
    end)
    return names
end

local function chatCount()
    local ok, n = pcall(function() return type(CHAT_FRAMES) == "table" and #CHAT_FRAMES or 0 end)
    return ok and n or 0
end

local function chatBoxHasFocus()
    if type(GetCurrentKeyBoardFocus) ~= "function" then return false end
    local ok, focus = pcall(GetCurrentKeyBoardFocus)
    if not ok or type(focus) ~= "table" then return false end
    for _, n in ipairs(chatNames()) do
        if rawequal(_G[n .. "EditBox"], focus) then return true end
    end
    return false
end

local function chatTargets()
    local out = {}
    local names = chatNames()
    names[#names + 1] = "GeneralDockManager"
    for _, n in ipairs(names) do
        local fr = _G[n]
        if type(fr) == "table" and type(fr.SetAlpha) == "function" then out[#out + 1] = fr end
    end
    return out
end

-- In combat the game locks protected frames. The chat windows are not protected in this build's
-- source; the game is asked anyway, and a frame that cannot say counts as protected.
local function locked(fr)
    if type(fr.IsProtected) ~= "function" then return true end
    local ok, p = pcall(fr.IsProtected, fr)
    return not ok or p ~= false
end

-- THE EDIT BOX THAT STAYS ON THE TOP SCREEN (b-top-chat-bar; players' screenshots, 15 Oct: "their message bars are still showing on
-- the top screen", the thin dark bar at the bottom left). Everything above hides with its window, but a chat window's edit box
-- can stand on its own: it ignores its window's alpha, or the game gave it another parent (the Edit Mode chat). Nothing is assumed
-- about which: AFTER the windows are hidden, a box that still SHOWS (its effective alpha) and does not have the keyboard is put to
-- alpha 0, its own alpha kept and given back the moment it takes the keyboard (the poll below shows the chat then) or the hiding
-- ends. Alpha only: never Hide (the controller's chat shortcut asks IsShown), never its focus, its text or a hook on it; a box that
-- already goes with its window is never touched. The game re-showing it (its own alpha) is caught by the poll. Each box's state
-- rides the `chathide` report (`boxes`), so the device says which piece it was.
-- chat24 (1.0.8-test7, a release blocker): OFF. The test build that first wrote this box's alpha also opened the game's bags over
-- the Bags room and broke that room (the user: "the Bag room now opens the bags again and bugs out"), the pattern of a tainted
-- controller UI, the risk this file's rule above was written for. Until the device's `blocked` reports say otherwise, the box is
-- never written again; the `boxes` report still says which box stands on its own. true = hide it (chat23's behaviour).
local BOX_HIDE = false
local boxes = { saved = {} }
function boxes.each(fn)
    for _, n in ipairs(chatNames()) do
        local b = _G[n .. "EditBox"]
        if type(b) == "table" and type(b.SetAlpha) == "function" then fn(n .. "EditBox", b) end
    end
end
local function boxShows(b)
    if S(function() return b:IsShown() end) ~= true then return false end
    local a = num(S(function() return b:GetEffectiveAlpha() end))
    return a ~= nil and a > 0.01
end
local function boxFocused(b)
    local ok, focus = pcall(GetCurrentKeyBoardFocus)
    return ok and rawequal(focus, b)
end
function boxes.hide()
    if not BOX_HIDE then return end
    boxes.each(function(name, b)
        if boxFocused(b) or not boxShows(b) then return end
        if boxes.saved[name] == nil then
            local ok, a = pcall(b.GetAlpha, b)
            boxes.saved[name] = (ok and num(a)) and a or 1
        end
        pcall(b.SetAlpha, b, 0)
    end)
end
function boxes.show()
    for name, a in pairs(boxes.saved) do
        local b = _G[name]
        if type(b) == "table" and type(b.SetAlpha) == "function" then pcall(b.SetAlpha, b, a) end
        boxes.saved[name] = nil
    end
end
function boxes.report()
    local out = {}
    boxes.each(function(name, b)
        local par = S(function() local pr = b:GetParent(); return pr and pr:GetName() end)
        out[#out + 1] = { n = name, a = num(S(function() return b:GetEffectiveAlpha() end)),
                          ipa = S(function() return b:IsIgnoringParentAlpha() end) == true or nil,
                          p = (type(par) == "string" and isPlain(par)) and par or nil, z = boxes.saved[name] and true or nil }
    end)
    return #out > 0 and out or nil
end

local function chatReport(n, of, why)
    pcall(function()
        WC.send(WC.encode({ type = "cap", probe = "chathide", v = CHATV, hidden = chatHidden, open = chatOpen,
                            n = n, of = of, off = hideOff or nil, why = why, boxes = boxes.report() }))
    end)
end

-- Returns ok[, why]. ALL the frames or none: in combat one locked frame refuses the whole change.
local function setChatAlpha(a)
    if a == 0 and hideOff then return false, "the game refused it" end
    local frames = chatTargets()
    if #frames == 0 then return false, "no chat window" end
    if yes(InCombatLockdown) then
        for _, fr in ipairs(frames) do
            if locked(fr) then return false, "in combat" end
        end
    end
    local done = 0
    for _, fr in ipairs(frames) do
        pcall(fr.SetAlpha, fr, a)
        local ok, now = pcall(fr.GetAlpha, fr)
        if ok and num(now) and math.abs(now - a) < 0.01 then done = done + 1 end
    end
    -- the edit boxes that do not go with their windows (see `boxes`): after the windows, the same way
    if a == 0 then pcall(boxes.hide) else pcall(boxes.show) end
    lastCount = chatCount()
    if done == 0 then return false, "the game kept its own value", 0, #frames end
    return true, nil, done, #frames
end

local function hideChat()
    local typing = chatBoxHasFocus()
    local ok, why, n, of = setChatAlpha(typing and 1 or 0)
    if not ok then
        chatReport(n or 0, of or 0, why)
        return false, why
    end
    chatHidden, chatOpen, lastTyping = true, typing, typing
    chatReport(n, of)
    return true
end

local function showChat()
    local ok, why, n, of = setChatAlpha(1)
    if not ok then
        chatReport(n or 0, of or 0, why)
        return false, why
    end
    chatHidden, chatOpen, lastTyping = false, false, nil
    chatReport(n, of)
    return true
end

-- Stop hiding and give the chat back. If even that fails the element stays "hidden", so the
-- hub's next "show" tries once more.
local function giveBack(why)
    if hideOff then return end
    hideOff = true
    pcall(boxes.show)
    if not chatHidden then return end
    local ok, res, _, n, of = pcall(setChatAlpha, 1)
    if ok and res then chatHidden, chatOpen, lastTyping = false, false, nil end
    chatReport(n or 0, of or 0, why)
end

-- the game refused a SetAlpha to an addon (from the refusal reporter)
alphaRefused = function() giveBack("the game refused it") end

local function poll()
    local typing = chatBoxHasFocus()
    if typing ~= lastTyping then
        lastTyping = typing                 -- tried once, whatever comes of it
        if typing ~= chatOpen then
            local ok, why, n, of = setChatAlpha(typing and 1 or 0)
            if ok then chatOpen = typing end
            chatReport(n or 0, of or 0, why)
        end
    elseif chatCount() ~= lastCount then    -- the game made a new chat window
        setChatAlpha(chatOpen and 1 or 0)
    elseif not chatOpen then
        boxes.hide()                        -- a box the game showed again by itself (its own alpha back)
    end
end

C_Timer.NewTicker(HIDE_POLL, function()
    if not chatHidden or hideOff then return end
    if not pcall(poll) then giveBack("the poll threw") end
end)

local hubbed = false
local function joinHub()
    if hubbed or type(WC.hub) ~= "table" or type(WC.hub.element) ~= "function" then return end
    local ok, bit = pcall(WC.hub.element, "chat", {
        hide = hideChat, show = showChat,
        hidden = function() return chatHidden end,
        combatOk = true,        -- alpha, which combat does not lock; hide and show ask the game
    })
    hubbed = ok and bit ~= nil
end

-- ---------------------------------------------------------------- the links' tooltips
-- A NEW item / spell / quest link's tooltip goes out ONCE, in its own document {"type":"chatlink", key, k, id, tip}, cached by the
-- app; the chat document itself carries only the link. The game's own tooltip lines through Interact's reader (WC.tipOf "link
-- <payload>", i100; read-only, nothing happens in the game). At most TIP_PER_TICK a second, and not in a fight (Interact asked:
-- one call per new link, out of combat when it can be); what a fight holds back goes after it. WC.send only: it rides a flush.
local TIP_PER_TICK, TIP_KEEP = 2, 400
local tipWant, tipSeen, tipSeenN = {}, {}, 0

wantTips = function(links)
    if type(links) ~= "table" then return end
    for _, l in ipairs(links) do
        if l.p and l.k ~= "player" and not tipSeen[l.p] then
            if tipSeenN >= TIP_KEEP then tipSeen, tipSeenN = {}, 0 end      -- a long session: start the cache again
            tipSeen[l.p] = true
            tipSeenN = tipSeenN + 1
            tipWant[#tipWant + 1] = l
        end
    end
end

linkTips = function()
    if #tipWant == 0 or type(WC.tipOf) ~= "function" or yes(InCombatLockdown) then return end
    for _ = 1, TIP_PER_TICK do
        local l = table.remove(tipWant, 1)
        if not l then break end
        local ok, tip = pcall(WC.tipOf, "link " .. l.p)
        if ok and type(tip) == "table" then
            WC.send(WC.encode({ type = "chatlink", v = CHATV, key = l.p, k = l.k, id = l.id, tip = tip }))
        end
    end
end

-- ---------------------------------------------------------------- THE CHAT ON THE TOP SCREEN (chat13)
-- The user (2026-10-07): "When typing, it would be really nice if the chat window kind of had an animation where it expanded up and
-- showed centered in the top screen above where I am typing, not including my typing bar, just showing the current chat", and
-- then: "can we make it part of the wow addon instead of an overlay?". So THIS add-on draws it: its own plain frame (no secure
-- template, no mouse: clicks and touches go through to the game), never the game's chat windows. UI Art's geometry (ChatTopSample):
-- centred, 62.5% of the screen wide and a third of it high, 3.7% over its bottom; FIVE lines (the chat font, its shadow), the newest at the bottom, the older
-- ones fading upward; a soft black gradient behind. The text is this file's own ring (plain strings only), drawn as the app draws
-- it (the channel tags, the class colours). The app shows it while its board (quick chat or the keyboard) is up, by the pad
-- (chs 39 / 36); it goes by itself the moment the chat module no longer holds the controller. A plain frame may show and hide in
-- a fight too.
local TOP_LINES = 12         -- chat16: ten lines high, lines wrap: as many whole ones as fit (up to 12)
local topFrame, topShown = nil, false
local TOP_COLOUR = { say = "ffffff", yell = "ff4040", party = "aaabfe", raid = "ff7f00", guild = "40ff40", officer = "40c040",
                     whisper = "ff80ff", emote = "ff8040", npc = "ffff9f", loot = "00aa00", money = "ffff00", currency = "00aa00", system = "ffff00", info = "ffff00",
                     error = "ff5050" }
local TOP_NUMBERED = { [1] = "ffc0c0", [2] = "f5a97a", [3] = "b6d7a8", [4] = "9fc5e8", [5] = "d5a6bd" }
local TOP_GROUP = { party = "Party", raid = "Raid", guild = "Guild", officer = "Officer" }

local function topOn() return topShown end

-- THE TOP CHAT REMEMBERS (chat14; the user on b119: the panel was "blank"). The game clears its chat at a /reload and this file's
-- ring starts empty, so right after one the panel had nothing to show. Its last TOP_KEEP lines are kept in the add-on's saved
-- variables (WowCompanionDB.chatTop: plain strings only; saved at PLAYER_LOGOUT, which a /reload fires too, read at PLAYER_LOGIN)
-- and fill the panel under the new ones. Only the panel uses them: the chat documents never send them again, so the app (which
-- keeps its own history) gets no second copy.
local TOP_KEEP = 12
local topSaved = {}         -- oldest first
local TOP_FIELDS = { "ch", "who", "text", "chan", "idx", "cls", "style", "dir" }

local function topRow(k)    -- k = 1: the newest
    local n = math.min(ringN, MAX_KEEP)
    if k <= n then return ring[(ringN - k) % MAX_KEEP + 1] end
    return topSaved[#topSaved - (k - n) + 1]
end

local function topSave()
    local out = {}
    for k = TOP_KEEP, 1, -1 do
        local r = topRow(k)
        if r then
            local c = {}
            for _, key in ipairs(TOP_FIELDS) do
                local v = r[key]
                if key == "idx" then c[key] = tonumber(v) else c[key] = str(v) end
            end
            if type(r.lc) == "table" then
                c.lc = {}
                for _, l in ipairs(r.lc) do if str(l.n) and str(l.c) then c.lc[#c.lc + 1] = { n = l.n, c = l.c } end end
            end
            if c.text and c.ch then out[#out + 1] = c end
        end
    end
    if type(WowCompanionDB) ~= "table" then WowCompanionDB = {} end      -- the table is everyone's: only the one key is ours
    WowCompanionDB.chatTop = out
end

local function topRestore()
    topSaved = {}
    local t = type(WowCompanionDB) == "table" and WowCompanionDB.chatTop or nil
    if type(t) ~= "table" then return end
    for _, r in ipairs(t) do
        if type(r) == "table" and str(r.text) and str(r.ch) then
            local c = {}
            for _, key in ipairs(TOP_FIELDS) do
                if key == "idx" then c[key] = tonumber(r[key]) else c[key] = str(r[key]) end
            end
            if type(r.lc) == "table" then
                c.lc = {}
                for _, l in ipairs(r.lc) do
                    if type(l) == "table" and str(l.n) and str(l.c) then c.lc[#c.lc + 1] = { n = l.n, c = l.c } end
                end
            end
            topSaved[#topSaved + 1] = c
        end
    end
    while #topSaved > TOP_KEEP do table.remove(topSaved, 1) end
end

local function classHex(cls)
    local c = cls and type(RAID_CLASS_COLORS) == "table" and RAID_CLASS_COLORS[cls] or nil
    if type(c) ~= "table" or type(c.r) ~= "number" or type(c.g) ~= "number" or type(c.b) ~= "number" then return nil end
    return string.format("%02x%02x%02x", math.floor(c.r * 255 + 0.5), math.floor(c.g * 255 + 0.5), math.floor(c.b * 255 + 0.5))
end

-- "2. Trade - City" -> "Trade"
local function tagOf(r)
    local c = r.chan or ""
    local b = c:match("^%d+%.%s*(.*)$") or c
    b = b:match("^(.-)%s+%-%s+") or b
    if b == "" then b = "ch" .. tostring(r.idx or 0) end
    return b
end

-- one line as the app draws it; every | of the text doubled, so nothing in it can be read as a code
local function topLine(r)
    local body = r.ch == "channel" and (TOP_NUMBERED[r.idx or 0] or "c5b9a0") or TOP_COLOUR[r.ch] or "d4c9b0"
    local parts = {}
    local function put(t, hex) parts[#parts + 1] = "|cff" .. hex .. (tostring(t):gsub("|", "||")) .. "|r" end
    if r.ch == "channel" then put("[" .. tagOf(r) .. "] ", body) end
    if TOP_GROUP[r.ch] then put("[" .. TOP_GROUP[r.ch] .. "] ", body) end
    if r.who and r.style ~= "plain" then
        local nameHex = classHex(r.cls) or body
        if r.dir == "out" then put("To ", body); put(r.who, nameHex); put(": ", body)
        elseif r.ch == "whisper" then put(r.who, nameHex); put(" whispers: ", body)
        elseif r.style == "emote" then put(r.who, nameHex); put(" ", body)
        else put(r.who, nameHex); put(": ", body) end
    end
    -- the text, its links in their own colours (chat16)
    local text, pos = r.text or "", 1
    if type(r.lc) == "table" then
        for _, l in ipairs(r.lc) do
            local a, b = text:find("[" .. tostring(l.n) .. "]", pos, true)
            if a then
                if a > pos then put(text:sub(pos, a - 1), body) end
                put(text:sub(a, b), (type(l.c) == "string" and l.c:match("^%x%x%x%x%x%x$")) and l.c or body)
                pos = b + 1
            end
        end
    end
    if pos <= #text then put(text:sub(pos), body) end
    return table.concat(parts)
end

-- THE PANEL (chat16; the user on b123: "I want it to look connected to the bottom screen, no gap. rather than a fade out on the
-- top, give it a border. try to make the text size match. the bottom screen shows much more"). UI Art's SHEET_chat_top_v2, in px
-- of the 1080 px screen: FLUSH, its bottom 16 px under the screen's edge so its own bottom border lies off-screen (no gap, no line
-- at the hinge); 1240 wide and centred (the bottom screen's own width); the game's TOOLTIP border tinted bronze round a solid ink
-- ground; the bottom log's text (35 px on a 65 px pitch, 30 px in, 26 px over and under the lines); TEN lines high (702 px). The
-- lines WRAP (as the game's chat does), stand one on another, the newest at the bottom; only whole lines that fit are shown. The
-- WHOLE panel rises from below as the input opens (the ground with the lines: it reads as the bottom screen carrying on upward)
-- and drops away as it closes.
local TOP_IN, TOP_OUT = 0.24, 0.20
local function px(n) return (UIParent:GetHeight() or 1080) * n / 1080 end
local topW, topH, topGap, topBase, topX, topY = 0, 0, 0, 0, 0, 0
local topAnim = nil             -- { from, to, t, dur, hide }

local function topPlace(y)
    topY = y
    local c = topFrame.content
    c:ClearAllPoints()
    c:SetPoint("BOTTOMLEFT", topFrame, "BOTTOMLEFT", 0, y)
    c:SetSize(topW, topH)
end

local function topMake()
    local f = CreateFrame("Frame", nil, UIParent)
    f:SetFrameStrata("HIGH")
    f:EnableMouse(false)
    f:Hide()
    if f.SetClipsChildren then f:SetClipsChildren(true) end
    local ok, c = pcall(CreateFrame, "Frame", nil, f, "BackdropTemplate")
    if not ok or not c then c = CreateFrame("Frame", nil, f) end
    c:EnableMouse(false)
    f.content = c
    -- the tooltip's border in bronze round the ink ground (UI Art); a build without backdrops gets the ground alone
    local framed = type(c.SetBackdrop) == "function" and pcall(c.SetBackdrop, c, {
        bgFile = "Interface\\Buttons\\WHITE8x8", edgeFile = "Interface\\Tooltips\\UI-Tooltip-Border", edgeSize = 16,
        insets = { left = 4, right = 4, top = 4, bottom = 4 } })
    if framed then
        pcall(c.SetBackdropColor, c, 0.055, 0.043, 0.031, 0.9)
        pcall(c.SetBackdropBorderColor, c, 0.78, 0.67, 0.43, 1)
    else
        c.bg = c:CreateTexture(nil, "BACKGROUND")
        c.bg:SetAllPoints()
        c.bg:SetColorTexture(0.055, 0.043, 0.031, 0.9)
    end
    f.lines = {}
    for k = 1, TOP_LINES do
        local fs = c:CreateFontString(nil, "OVERLAY")
        fs:SetJustifyH("LEFT")
        if fs.SetWordWrap then fs:SetWordWrap(true) end
        if fs.SetNonSpaceWrap then fs:SetNonSpaceWrap(true) end
        if fs.SetMaxLines then fs:SetMaxLines(4) end
        f.lines[k] = fs
    end
    f:SetScript("OnUpdate", function(self, dt)
        local a = topAnim
        if not a then return end
        a.t = a.t + (tonumber(dt) or 0)
        local k = math.min(1, a.t / a.dur)
        local e = 1 - (1 - k) ^ 3                       -- fast out, gentle in (the app's curve, near enough)
        topPlace(a.from + (a.to - a.from) * e)
        if k >= 1 then
            topAnim = nil
            if a.hide then self:Hide() end
        end
    end)
    return f
end

local function topLayout()
    local W, H = UIParent:GetWidth(), UIParent:GetHeight()
    if not (W and H and W > 0 and H > 0) then return end
    -- chat18 (UI Art, measured on the Thor): the messages close together as the game's chat sets them (6 px apart, not the
    -- bottom log's 65 px touch pitch), so the same ten messages need ~230 px less: the game shows again above the panel
    -- chat19 (UI Art, the Thor pair v3): 425 px, so its top (y >= 655) clears the game's player frame, and the empty band over
    -- the first line goes: ten one-row messages still fit (16 px under the last, ~9 over the first)
    topW, topH = W * 1240 / 1920, px(16 + 425)
    topFrame:SetSize(topW, topH)
    topFrame:ClearAllPoints()
    topFrame:SetPoint("BOTTOM", UIParent, "BOTTOM", 0, -px(16))      -- its bottom border under the screen's edge
    local font = STANDARD_TEXT_FONT
    if ChatFontNormal and ChatFontNormal.GetFont then
        local ok, f = pcall(ChatFontNormal.GetFont, ChatFontNormal)
        if ok and type(f) == "string" then font = f end
    end
    local size = px(34.6)                               -- the bottom log's text
    topGap = px(6)                                      -- 6 px between messages (a wrapped message's rows sit closer still)
    topBase = px(16 + 16)                               -- 16 px over the screen's edge
    topX = px(30)
    for k, fs in ipairs(topFrame.lines) do            -- k = 1: the newest, at the bottom
        -- the game's chat look (UI Art): ChatFontNormal's 1, -1 black shadow, no outline
        pcall(fs.SetFont, fs, font, size, "")
        if fs.SetShadowOffset then fs:SetShadowOffset(1, -1) end
        if fs.SetShadowColor then fs:SetShadowColor(0, 0, 0, 1) end
        fs:SetWidth(topW - 2 * topX)
    end
    topPlace(topY)
end

topRefresh = function()
    if not (topFrame and topShown) then return end
    local y, room, full = topBase, topH - px(8), false
    for k = 1, TOP_LINES do
        local fs = topFrame.lines[k]
        local r = not full and topRow(k) or nil
        local ok = r and pcall(fs.SetText, fs, topLine(r))
        if not ok then
            fs:SetText(""); fs:Hide(); full = true
        else
            local hh = fs.GetStringHeight and tonumber(fs:GetStringHeight()) or 0
            local at = y + (k > 1 and topGap or 0)
            if at + hh > room then fs:SetText(""); fs:Hide(); full = true      -- only whole lines: none cut at the top
            else
                fs:Show()
                fs:ClearAllPoints()
                fs:SetPoint("BOTTOMLEFT", topFrame.content, "BOTTOMLEFT", topX, at)
                y = at + hh
            end
        end
    end
end

local function setTop(on)
    if on == topShown then return end
    topShown = on
    if on then
        if not topFrame then topFrame = topMake() end
        local fresh = not topFrame:IsShown()
        topLayout()
        topFrame:Show()
        topRefresh()
        topAnim = { from = fresh and -topH or topY, to = 0, t = 0, dur = TOP_IN }
        topPlace(topAnim.from)
    elseif topFrame then
        topAnim = { from = topY, to = -topH, t = 0, dur = TOP_OUT, hide = true }
    end
end

local chatHolds          -- the quick chat block's, below
topCheck = function()
    if topShown and chatHolds and not chatHolds() then setTop(false) end
end

-- ---------------------------------------------------------------- QUICK CHAT AND TYPED CHAT, sent from here
-- The user's Chat rework (2026-10-07): quick chat (Q4 "From the add-on on the press") AND, since the Android keyboard was found
-- popping up over the companion whenever letters were typed into the game (Gboard shows itself on injected keys), the typed
-- message too ("Send from the add-on, like quick chat", 10-07; it replaces the 09-28 "typed chat" route). A line is sent by
-- THIS addon inside the tap on its pad cell: a plain cell's handler runs in the cell's OnClick on the tap's mouse-down, a real
-- hardware event, which is what SendChatMessage needs for Say / Yell / channels outdoors.
-- The rows (namespace "chat"; Quests' rules and budget, 2026-10-07):
--   chq  0-10   send preset k on the current type (11: refused, no phrase) (a game action: ONE line per tap)
--        12     typed: delete the last character      13 clear            (no game action, nor any of the picks below)
--        14     the letters now spell the WHISPER TARGET                  15 ... the message again
--        16-22  the type: SAY / PARTY / RAID / GUILD / YELL / REPLY / WHISPER (to the spelt target)
--        23     SEND the typed message on the current type                (a game action: ONE line per tap)
--        24-39  the characters 32-47 (space ! " # $ % & ' ( ) * + , - . /)
--   chr  0-39   the characters 48-87                  chs  0-38  the characters 88-126, but 36 ("|")
--   chs  36     HIDE the chat on the top screen (display only: taken whoever holds the controller)
--   chs  39     SHOW it. Both are also the SPACER: a cell swallows a second press within 0.25 s (TapPad's repeat guard) and the
--               app's taps queue, so a doubled letter ("ll") goes as one spaced batch [spacer, letter]; the app uses the one
--               that matches what shows (39 while the board is up, 36 otherwise), so a spacer changes nothing
-- "|" is not typed any more (chat13): its cell went to the top screen, and the game's escape character was never proven here.
-- Every tap is answered, and anything while the chat module does not hold the controller is refused with nothing done.
-- Presets and types answer {"type":"chatq"} (pushed); typed picks answer {"type":"chatt"} with THE ADD-ON'S BUFFERS as length
-- + hash: they are the truth (a tap can land on the sink and a lost letter is silent), the app shows and re-sends against them,
-- and Send sends only them. Letters ride the throttled flush (a push per letter would pad the log ~60 KB a key); Send and Clear
-- push at once. The presets live here AND in the app (ChatLinks.PRESETS), by index; so does the hash (ChatLinks.hash).
-- chat14, the user (2026-10-07): "Replace Good game with GG", "replace be right back with BRB", and Inviting gave its place to the
-- app's Keyboard button (the grid's bottom right): 11 phrases, chq 0-10 (11 is refused: the app's button opens its keyboard)
WC.CHAT_PRESETS = { "Hello!", "Thanks!", "On my way", "Ready", "One moment", "Need help?", "GG", "BRB", "Yes", "No", "lol" }
-- chq 11 (no phrase there) = CHANNEL (chat16): the channel's NUMBER is spelt into the target buffer (14 / letters / 15), as a
-- whisper's name is
local QTYPES = { [11] = "CHANNEL", [16] = "SAY", [17] = "PARTY", [18] = "RAID", [19] = "GUILD", [20] = "YELL", [21] = "REPLY",
                 [22] = "WHISPER" }
local TYPED_COL = { [12] = "back", [13] = "clear", [14] = "tgt", [15] = "body", [23] = "send" }
local TYPED_MAX, TARGET_MAX = 255, 48
local qtype = "SAY"
local qn, tn = 0, 0
local typed = { body = "", tgt = "", into = "body" }

-- the short hash both ends compute (Kotlin: ChatLinks.hash): every product stays far below 2^53, so a double holds it exactly
local function hashOf(s)
    local h = 0
    for k = 1, #s do h = (h * 31 + s:byte(k)) % 16777213 end
    return h
end
WC.chatHash = hashOf

local function qack(op, col, ok, why)
    qn = qn + 1
    WC.send(WC.encode({ type = "chatq", v = CHATV, op = op, col = col, ok = ok and true or false, why = why, ty = qtype, n = qn }))
    WC.push()
end

-- Letters and deletes are NOT flushed one by one: Sink's throttle (0.2 s) is faster than typing, and a flush pads the log ~60 KB.
-- They ride whatever flush comes next, and ONE flush follows a pause in typing (LETTER_REST after the last key). Anything else
-- typed (clear, send, the target switches) flushes at once: the app taps "15" as a "report now" before it sends.
local LETTER_REST = 1.5
local letterGen = 0
local field = nil          -- chat22: a typed field another add-on borrows (WC.chatField below)
local function tack(op, row, col, ok, why, now)
    tn = tn + 1
    WC.send(WC.encode({ type = "chatt", v = CHATV, op = op, row = row, col = col, ok = ok and true or false, why = why,
                        len = #typed.body, h = hashOf(typed.body), tlen = #typed.tgt, th = hashOf(typed.tgt),
                        into = typed.into, ty = field and "FIELD" or qtype, field = field and field.name or nil, top = topOn(), n = tn }))
    letterGen = letterGen + 1
    if now then return WC.push() end
    local g = letterGen
    C_Timer.After(LETTER_REST, function() if g == letterGen then WC.push() end end)
end

chatHolds = function()
    local c = WC.capture
    if type(c) ~= "table" or type(c.state) ~= "function" then return false end
    local ok, st = pcall(c.state)
    return ok and type(st) == "table" and st.holder == "pad:chat"
end

-- chat22, A BORROWED FIELD (Interact's Send Mail and auction-house search, 1.0.8): the typed rows above, the same letters, deletes,
-- report (15) and hash check, fill ONE field of another add-on while THAT add-on holds the controller, and Send (23) hands the text
-- to it instead of the chat. The chat's own buffers are kept aside meanwhile and put back after, so a half-written message survives.
-- No preset, type or whisper target while a field is open. The app's side is ChatStream(field = true).
--   WC.chatField.open{ name, text, max, holds = fn() -> bool, done = fn(text) -> ok, why }   (replaces an open field)
--   WC.chatField.close()      the field goes (done or cancelled); the chat's buffers come back
--   WC.chatField.name()       the open field's name, or nil
local function fieldHolds() return field ~= nil and type(field.holds) == "function" and field.holds() == true end
WC.chatField = {
    open = function(spec)
        if type(spec) ~= "table" or type(spec.done) ~= "function" then return false end
        if not field then field = { saved = { body = typed.body, tgt = typed.tgt, into = typed.into } } end
        field.name, field.holds, field.done = spec.name or "field", spec.holds, spec.done
        field.max = math.min(tonumber(spec.max) or TYPED_MAX, TYPED_MAX)
        typed.body, typed.tgt, typed.into = (type(spec.text) == "string" and spec.text or ""):sub(1, field.max), "", "body"
        tack("field", "chq", nil, true, nil, true)
        return true
    end,
    close = function()
        if not field then return end
        local sv = field.saved
        field = nil
        typed.body, typed.tgt, typed.into = sv.body, sv.tgt, sv.into
        tack("unfield", "chq", nil, true, nil, true)
    end,
    name = function() return field and field.name or nil end,
}

-- returns ok, why
local function sendLine(text, kind)
    local target = nil
    if kind == "REPLY" then
        target = str(WC.chatLastTell)
        if not target then return false, "nobody to reply to" end
        kind = "WHISPER"
    elseif kind == "WHISPER" then
        if typed.tgt == "" then return false, "nobody to whisper" end
        target = typed.tgt
    elseif kind == "CHANNEL" then
        local n = tonumber(typed.tgt)
        if not n then return false, "no channel" end
        local ok, id = pcall(function() return (GetChannelName(n)) end)
        if not ok or type(id) ~= "number" or id == 0 then return false, "not in that channel" end
        target = n
    end
    if kind == "PARTY" and not yes(IsInGroup) then return false, "not in a group" end
    if kind == "RAID" and not yes(IsInRaid) then return false, "not in a raid" end
    if kind == "GUILD" and not yes(IsInGuild) then return false, "not in a guild" end
    -- a lone | starts an escape code in chat; || is the character itself (UNMEASURED on this build: the harness only)
    local out = text:gsub("|", "||")
    if #out > TYPED_MAX then return false, "too long" end
    local fn = (type(C_ChatInfo) == "table" and C_ChatInfo.SendChatMessage) or SendChatMessage
    if type(fn) ~= "function" then return false, "this build cannot send" end
    local ok = pcall(fn, out, kind, nil, target)
    return ok, (not ok) and "the game refused it" or nil
end

local function charOf(row, col)
    if row == "chq" and col >= 24 and col <= 39 then return string.char(8 + col) end      -- 32..47
    if row == "chr" and col >= 0 and col <= 39 then return string.char(48 + col) end     -- 48..87
    if row == "chs" and col >= 0 and col <= 38 and col ~= 36 then return string.char(88 + col) end     -- 88..126 but |
    return nil
end

local function typeChar(row, col)
    local ch = charOf(row, col)
    if not ch then return tack("key", row, col, false, "not a letter cell") end
    local cur = typed[typed.into]
    local max = field and field.max or (typed.into == "tgt" and TARGET_MAX or TYPED_MAX)
    if #cur >= max then return tack("key", row, col, false, "full") end
    typed[typed.into] = cur .. ch
    tack("key", row, col, true)
end

local function typedPick(op, col)
    if op == "back" then
        local cur = typed[typed.into]
        if cur == "" then return tack("back", "chq", col, false, "nothing to delete") end
        typed[typed.into] = cur:sub(1, -2)
        return tack("back", "chq", col, true)
    elseif op == "clear" then
        typed[typed.into] = ""
        return tack("clear", "chq", col, true, nil, true)
    elseif op == "tgt" or op == "body" then
        typed.into = op
        return tack(op, "chq", col, true, nil, true)
    end
    -- SEND: only what THIS addon holds; a borrowed field's text goes to its owner (chat22)
    if field then
        local ok, done, why = pcall(field.done, typed.body)
        if not ok then done, why = false, "the field's owner failed" end
        return tack("send", "chq", col, done and true or false, why, true)
    end
    local body = typed.body
    if not body:find("%S") then return tack("send", "chq", col, false, "nothing to send", true) end
    if body:sub(1, 1) == "/" then return tack("send", "chq", col, false, "commands cannot be sent from here", true) end
    local ok, why = sendLine(body, qtype)
    if ok then typed.body = "" end
    tack("send", "chq", col, ok, why, true)
end

local function onQuick(_, col)
    if type(col) ~= "number" then return qack("?", nil, false, "no column") end
    local op = TYPED_COL[col]
    local isTyped = op ~= nil or (col >= 24 and col <= 39)
    if field then
        -- chat22: a borrowed field takes the typed picks only (no preset, type or target) while its owner holds the controller
        if not fieldHolds() then return tack(op or "key", "chq", col, false, "the field is not open", true) end
        if op == "tgt" then return tack("tgt", "chq", col, false, "no target in a field", true) end
        if op then return typedPick(op, col) end
        if isTyped then return typeChar("chq", col) end
        return qack("send", col, false, "a field is open")
    end
    if not chatHolds() then
        if isTyped then return tack(op or "key", "chq", col, false, "the chat is not open", true) end
        return qack("send", col, false, "the chat is not open")
    end
    if QTYPES[col] then
        qtype = QTYPES[col]
        return qack("type", col, true)
    end
    if col >= 0 and col <= 11 then
        local text = WC.CHAT_PRESETS[col + 1]
        if not text then return qack("send", col, false, "no such preset") end
        local ok, why = sendLine(text, qtype)
        return qack("send", col, ok, why)
    end
    if op then return typedPick(op, col) end
    if isTyped then return typeChar("chq", col) end
    qack("send", col, false, "no such preset")              -- 12-15 are typed picks, so only a column past 39 lands here
end

local function onLetters(row)
    return function(_, col)
        if type(col) ~= "number" then return tack("key", row, nil, false, "no column") end
        if field then
            if not fieldHolds() then return tack("key", row, col, false, "the field is not open", true) end
            -- 36 / 39 are only the field stream's spacers here: nothing typed, the chat on the top screen left as it is
            if row == "chs" and (col == 36 or col == 39) then return tack("key", row, col, true) end
            return typeChar(row, col)
        end
        if row == "chs" and col == 36 then setTop(false); return tack("topoff", row, col, true) end   -- display only, always
        if not chatHolds() then return tack("key", row, col, false, "the chat is not open", true) end
        if row == "chs" and col == 39 then setTop(true); return tack("topon", row, col, true) end
        typeChar(row, col)
    end
end

local padRows = {}
local capHooked = false
local function joinPad()
    if not capHooked and type(WC.capture) == "table" and type(WC.capture.onChange) == "function" then
        local ok, r = pcall(WC.capture.onChange, function() topCheck() end)
        capHooked = ok and r == true
    end
    if type(WC.pad) ~= "table" or type(WC.pad.op) ~= "function" then return end
    for _, r in ipairs({ { "chq", onQuick }, { "chr", onLetters("chr") }, { "chs", onLetters("chs") } }) do
        if not padRows[r[1]] then
            local ok, row = pcall(WC.pad.op, r[1], "chat", r[2])
            padRows[r[1]] = ok and row or nil
        end
    end
end
joinPad()

-- ---------------------------------------------------------------- start

f:SetScript("OnEvent", function(_, event, ...)
    if event == "ADDON_LOADED" then pcall(joinHub) return end
    if event == "PLAYER_LOGOUT" then pcall(topSave) return end
    if event == "PLAYER_LOGIN" then
        pcall(topRestore)
        pcall(joinHub)
        joinPad()
        ride()
        WC.send(WC.encode({ type = "cap", probe = "chat", v = CHATV, s = session,
                            missingEvents = missing, mods = modifiers(), hub = hubbed, chq = padRows.chq ~= nil,
                            typing = padRows.chr ~= nil and padRows.chs ~= nil }))
        reportStyle()
        talkChanged()
        return
    end
    if event == "PLAYER_REGEN_ENABLED" then
        lastTyping = nil        -- a change that combat held back is tried again, once
        return
    end
    if BLOCKED_EVENTS[event] then return onBlocked(event, ...) end
    onChatEvent(event, ...)
end)
