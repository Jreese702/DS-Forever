-- WowCompanionCalendar / Calendar.lua
-- The CALENDAR room's feed: the game's month calendar (holidays, raid resets and lockouts, the player's
-- events and invitations) read through C_Calendar, so the bottom screen can show it without the game's
-- own window. User ruling 2026-09-28: "Look and answer" (no typing, nothing created). (c7) The room's
-- taps arrive on ONE row of the shared tap pad, "cal": the controller (a guest of interact's shared
-- capture), the game's six calendar filters, and the answers to an invitation, each ONE game action.
--
-- WHY A SEPARATE ADDON FOLDER. Like Quests and Interact: its own folder means no edit to the shared
-- .toc or Core.lua, and a push of it can never overwrite another session's file. It uses the core
-- addon's safety kernel and sink (## Dependencies guarantees load order).
--
-- WHAT THIS CLIENT HAS (read from Blizzard's own source, see AGENT_LOG "Calendar ... SURVEY"): the
-- retail calendar, with a Forever-only filter list. Its one door on the top screen is the minimap's
-- date square (GameTimeFrame, a child of MinimapCluster). The server can switch the whole calendar
-- off with the game rule IngameCalendarDisabled; the document says so in `off` rather than guess.
--
-- WHAT IT SENDS: one {"type":"calendar"} document when anything in it changed, and again on the core
-- 15 s heartbeat (so the app gets it back after the log rotates). A month is sent as absolute
-- year/month with its events; a holiday that runs over several days is ONE entry with the list of
-- days it is drawn on, and its description travels once, in `hol`.
--
-- WHAT IT CHANGES IN THE GAME: only what the game's own calendar window does.
--  * C_Calendar.OpenCalendar(), the request for the calendar's data (it opens no window), and
--    C_Calendar.SetAbsMonth(today) so that month offset 0 is this month, as CalendarFrame_OnShow does.
--    SetAbsMonth is NEVER called while the game's calendar window is shown: it would move the page the
--    player is looking at.
--  * (c3) An event's page (description, creator, invite list) is only readable while the event is
--    "open", so for each player-made event in the four months the addon opens it the way a click on it
--    in the game's window does (C_Calendar.OpenEvent), reads it, and closes it again (CloseEvent). One
--    at a time, at most MAX_OPENS a load, and never again for an event whose day entry has not changed
--    (or within DETAIL_AGE). If the event that opens is not the one asked for (the player opened
--    another), it is neither read nor closed. (c6) ONLY while the game's calendar code has never been
--    loaded this session: once it is, its window reacts to an opened event, and the user's rule is that
--    no game window is opened, closed, shown or hidden because of addon code.
--  * (c7) On a TAP of the room's pad row: an answer is the game's own DAY MENU route (GenerateDayContextMenu):
--    C_Calendar.ContextMenuSelectEvent(month, day, index), then ContextMenuInviteAvailable / Tentative / Decline /
--    ContextMenuEventSignUp / ContextMenuInviteRemove, each only where that menu would offer it now; no event is
--    opened. A filter tick is C_CVar.SetCVar on one of the six calendarShow* settings (what the game's Filters
--    menu sets). Engine calls only (the pad owner's rule after bags' taint flood).
-- No game window is opened, closed, shown or hidden; no Blizzard UI Lua is called; no slash command exists.
--
-- Every value goes through S()/SN() (the T* wrappers below), so nothing raw is ever concatenated.
-- GetDayEvent goes secret in the game's chat-messaging lockdown (instances): then the document says
-- `secret` and carries no months, and the app keeps the last good ones.

local ADDON = ...
local WC = _G.WowCompanion
if type(WC) ~= "table" or type(WC.S) ~= "function" or type(WC.send) ~= "function" then return end
local S, SN, CALL = WC.S, WC.SN, WC.CALL

local CV = "c14"     -- bump on every change; the app shows it, so a stale build is visible

-- (c7) the room's row on the shared tap pad (the pad owner's YES, AGENT_LOG "Pad owner -> calendar"): row "cal",
-- namespace "cal". The eight fixed keys are claimed FIRST at load (columns 0..7), in this order; the answers come
-- after them, one column per answer the game's day menu offers now ("<op>|<event key>"), retained per document.
local FIXED_KEYS = { "h", "f", "flt1", "flt2", "flt3", "flt4", "flt5", "flt6",
    -- (c9) capture v3, as Group Finder does: "fb" = give the controller back BECAUSE OF B (release(id, "B"): the app goes
    -- Home); "bh" / "bb" = where the next B leads, "home" / "back" (capture.bnext; the Home button shows B for home)
    "fb", "bh", "bb" }
-- our name as a guest of interact's shared controller capture
local GUEST = "calendar"
-- an answer's op -> the game's own day-menu call
local ANSWER_CALLS = {
    acc = "ContextMenuInviteAvailable", ten = "ContextMenuInviteTentative", dec = "ContextMenuInviteDecline",
    sig = "ContextMenuEventSignUp", rem = "ContextMenuInviteRemove",
}
-- the statuses the game lets an invitee answer from (_CalendarFrame_CanInviteeRSVP): Invited, Available,
-- Declined, Signedup, NotSignedup, Tentative
local RSVP = { [0] = true, [1] = true, [2] = true, [6] = true, [7] = true, [8] = true }

-- months sent, as offsets from the calendar's month 0 (this month, unless the player is paging the
-- game's own window): last month, this month and the next two
local WINDOW = { -1, 0, 1, 2 }

-- the filters of THIS client's calendar (camelot/calendarfilterdata.lua); the game applies them itself
local FILTER_CVARS = {
    "calendarShowHolidays", "calendarShowDarkmoon", "calendarShowLockouts",
    "calendarShowResets", "calendarShowWeeklyHolidays", "calendarShowBattlegrounds",
}

-- the calendar types a player (or a guild / community) makes, the only ones that carry an invitation
local PLAYER_MADE = { PLAYER = true, GUILD_EVENT = true, GUILD_ANNOUNCEMENT = true, COMMUNITY_EVENT = true }

-- THE GAME'S OWN WORDS for the room's fixed labels, read from the client at runtime so the room never
-- writes its own (and follows the client's language). Keys are the game's global string names; the
-- English values read from globalstrings.db2 are in AGENT_LOG ("Calendar -> UI Art: THE HAND-OVER").
local WORDS = {
    "ACCEPT", "DECLINE", "CALENDAR_VIEW_EVENT_TENTATIVE", "CALENDAR_VIEW_EVENT_REMOVE", "CALENDAR_SIGNUP",
    "CALENDAR_FILTERS", "CALENDAR_FILTER_HOLIDAYS", "CALENDAR_FILTER_DARKMOON", "CALENDAR_FILTER_RAID_LOCKOUTS",
    "CALENDAR_FILTER_RAID_RESETS", "CALENDAR_FILTER_WEEKLY_HOLIDAYS", "CALENDAR_FILTER_BATTLEGROUND",
    "CALENDAR_VIEW_EVENT", "CALENDAR_VIEW_GUILD_EVENT", "CALENDAR_VIEW_COMMUNITY_EVENT", "CALENDAR_VIEW_ANNOUNCEMENT",
    "CALENDAR_EVENT_CREATORNAME", "CALENDAR_VIEW_EVENTTYPE", "CALENDAR_VIEW_EVENTTITLE_LOCKED", "CALENDAR_EVENT_DESCRIPTION",
    "CALENDAR_EVENT_PICKER_TITLE", "RETRIEVING_INVITE_LIST", "CALENDAR_INVITELIST_CREATORNAME",
    "CALENDAR_INVITELIST_MODERATORNAME",
    "CALENDAR_STATUS_INVITED", "CALENDAR_STATUS_ACCEPTED", "CALENDAR_STATUS_DECLINED", "CALENDAR_STATUS_CONFIRMED",
    "CALENDAR_STATUS_OUT", "CALENDAR_STATUS_STANDBY", "CALENDAR_STATUS_SIGNEDUP", "CALENDAR_STATUS_NOT_SIGNEDUP",
    "CALENDAR_STATUS_TENTATIVE",
    "CALENDAR_TYPE_RAID", "CALENDAR_TYPE_DUNGEON", "CALENDAR_TYPE_PVP", "CALENDAR_TYPE_MEETING", "CALENDAR_TYPE_OTHER",
    "CALENDAR_EVENTNAME_FORMAT_START", "CALENDAR_EVENTNAME_FORMAT_END", "CALENDAR_EVENTNAME_FORMAT_RAID_LOCKOUT",
    "CALENDAR_EVENTNAME_FORMAT_RAID_RESET", "CALENDAR_RAID_LOCKOUT_DESCRIPTION", "CALENDAR_RAID_RESET_DESCRIPTION",
    "CALENDAR_HOLIDAYFRAME_BEGINSENDS", "FULLDATE", "SHORTDATENOYEAR", "TIMEMANAGER_TICKER_12HOUR",
    "TIMEMANAGER_TICKER_24HOUR", "TIMEMANAGER_AM", "TIMEMANAGER_PM", "GAMETIME_TOOLTIP_CALENDAR_INVITES",
    -- the day note (the game's day tooltip): "Ongoing", date ranges, who invited you
    "CALENDAR_TOOLTIP_ONGOING", "CALENDAR_TOOLTIP_DATE_RANGE", "CALENDAR_EVENT_INVITEDBY_PLAYER",
    "CALENDAR_ANNOUNCEMENT_CREATEDBY_PLAYER", "CALENDAR_SIGNEDUP_FOR_GUILDEVENT_WITH_STATUS",
    "CALENDAR_EVENT_INVITEDBY_YOURSELF", "CALENDAR_GUILDEVENT_INVITEDBY_YOURSELF",
    "CALENDAR_COMMUNITYEVENT_INVITEDBY_YOURSELF", "CALENDAR_ANNOUNCEMENT_CREATEDBY_YOURSELF",
    -- (c8) an event's page: "Set your status:", "%1$s (%2$s)" for a dungeon with its difficulty; Remove's question
    "CALENDAR_VIEW_EVENT_SETSTATUS", "DUNGEON_NAME_WITH_DIFFICULTY", "YES", "NO",
}
local MONTH_WORDS = {
    "MONTH_JANUARY", "MONTH_FEBRUARY", "MONTH_MARCH", "MONTH_APRIL", "MONTH_MAY", "MONTH_JUNE",
    "MONTH_JULY", "MONTH_AUGUST", "MONTH_SEPTEMBER", "MONTH_OCTOBER", "MONTH_NOVEMBER", "MONTH_DECEMBER",
}
local WEEKDAY_WORDS = {
    "WEEKDAY_SUNDAY", "WEEKDAY_MONDAY", "WEEKDAY_TUESDAY", "WEEKDAY_WEDNESDAY",
    "WEEKDAY_THURSDAY", "WEEKDAY_FRIDAY", "WEEKDAY_SATURDAY",
}

-- an event page is read at most this many times per load, and again only when older than this
local MAX_OPENS = 40
local DETAIL_AGE = 900
-- after the calendar reports an error while one of our opens is out, leave the server alone this long
local ERROR_PAUSE = 300

local function real(v)
    return v ~= nil and v ~= WC.ABSENT and v ~= WC.SECRET and v ~= WC.THREW
end

-- S() that also counts sentinel hits, so a secret or a changed API shows up on the wire (`sec`)
-- instead of as a quietly emptier month. ABSENT is not counted: nil is a legitimate answer here.
local tally = { secret = 0, threw = 0 }
local function T(fn)
    local v = S(fn)
    if v == WC.SECRET then tally.secret = tally.secret + 1
    elseif v == WC.THREW then tally.threw = tally.threw + 1 end
    return v
end
local function TS(fn) local v = T(fn); if type(v) == "string" and real(v) then return v end end
local function TN(fn) local v = T(fn); if type(v) == "number" then return v end end
local function TB(fn) local v = T(fn); if type(v) == "boolean" then return v end end
-- a string field the game fills with "" when it has nothing to say
local function TSN(fn) local v = TS(fn); if v ~= "" then return v end end

-- a CalendarTime as {y, m, d, h, mi}; nil when it is not a table
local function ctime(fn)
    local ok, t = CALL(fn)
    if not ok or type(t) ~= "table" then return nil end
    return {
        y = TN(function() return t.year end),
        m = TN(function() return t.month end),
        d = TN(function() return t.monthDay end),
        h = TN(function() return t.hour end),
        mi = TN(function() return t.minute end),
    }
end

local function stamp(t)
    if not t then return "-" end
    return tostring(t.y) .. "." .. tostring(t.m) .. "." .. tostring(t.d) .. "." .. tostring(t.h) .. "." .. tostring(t.mi)
end

-- true = the server switched the calendar off; nil = this client cannot say
local function calendarOff()
    local ok, rule = CALL(function() return Enum.GameRule.IngameCalendarDisabled end)
    if not ok or type(rule) ~= "number" then return nil end
    return TB(function() return C_GameRules.IsGameRuleActive(rule) end)
end

-- The game's own calendar code (load-on-demand) has been loaded this session. From then on its window
-- reacts to an opened event (CalendarFrame_OnEvent shows its event page, even inside the closed window),
-- so the addon opens no event page at all: the user's rule (2026-09-29) is that no game window is opened,
-- closed, shown or hidden because of addon code. Reading a global and an engine call only.
local function gameCalendarLoaded()
    if type(_G.CalendarFrame) == "table" then return true end
    local ok, loaded = CALL(function() return C_AddOns.IsAddOnLoaded("Blizzard_Calendar") end)
    return ok and loaded == true
end

-- the game's own calendar window, open on the top screen (load-on-demand: nil until first opened)
local function frameShown()
    local f = _G.CalendarFrame
    if type(f) ~= "table" then return false end
    local ok, shown = CALL(function() return f:IsShown() end)
    return ok and shown == true
end

-- Month offset 0 = this month, as the game's window sets it on show. Only when that window is closed,
-- and only when it is not already so (the call is not repeated every collection).
local function alignToToday(now)
    if not now or not now.y or not now.m or frameShown() then return end
    local ok, mi = CALL(function() return C_Calendar.GetMonthInfo(0) end)
    if not ok or type(mi) ~= "table" then return end
    local y = TN(function() return mi.year end)
    local m = TN(function() return mi.month end)
    if y == now.y and m == now.m then return end
    CALL(function() C_Calendar.SetAbsMonth(now.m, now.y) end)
end

local function holidayInfo(off, d, i)
    local ok, h = CALL(function() return C_Calendar.GetHolidayInfo(off, d, i) end)
    if not ok or type(h) ~= "table" then return nil end
    return {
        name = TSN(function() return h.name end),
        desc = TSN(function() return h.description end),
        tex = TN(function() return h.texture end),
    }
end

-- the game's words (see WORDS); only plain strings, never a sentinel
local function word(k)
    local v = _G[k]
    if type(v) == "string" and WC.isPlain(v) then return v end
end
local function words()
    local w = {}
    for _, k in ipairs(WORDS) do w[k] = word(k) end
    local months, days = {}, {}
    for i, k in ipairs(MONTH_WORDS) do months[i] = word(k) or "" end
    -- the weekday row uses CALENDAR_WEEKDAY_NAMES (1 = Sunday); its WEEKDAY_* strings when it is not there
    local src = _G.CALENDAR_WEEKDAY_NAMES
    for i, k in ipairs(WEEKDAY_WORDS) do
        local v = type(src) == "table" and src[i] or nil
        days[i] = (type(v) == "string" and WC.isPlain(v)) and v or word(k) or ""
    end
    w.months, w.weekdays = months, days
    return w
end

-- a player-made event's key: its id and start, which is what tells two events apart
local function evKey(id, st) return tostring(id) .. ":" .. stamp(st) end

local function collectMonth(off, hol, found)
    local ok, mi = CALL(function() return C_Calendar.GetMonthInfo(off) end)
    if not ok or type(mi) ~= "table" then return nil end
    local y = TN(function() return mi.year end)
    local m = TN(function() return mi.month end)
    local n = TN(function() return mi.numDays end)
    if not (y and m and n) or n < 1 or n > 31 then return nil end
    local month = { y = y, m = m, n = n, wd1 = TN(function() return mi.firstWeekday end), ev = {} }
    local byKey = {}
    for d = 1, n do
        local cnt = TN(function() return C_Calendar.GetNumDayEvents(off, d) end) or 0
        for i = 1, math.min(cnt, 60) do
            local okE, e = CALL(function() return C_Calendar.GetDayEvent(off, d, i) end)
            if okE and type(e) == "table" then
                local ct = TS(function() return e.calendarType end)
                local id = TN(function() return e.eventID end)
                local title = TS(function() return e.title end)
                local st = ctime(function() return e.startTime end)
                -- the same holiday comes back once per day it covers, with the same start: one entry
                local key = tostring(ct) .. "|" .. tostring(id) .. "|" .. tostring(title) .. "|" .. stamp(st)
                local ev = byKey[key]
                if not ev then
                    ev = {
                        id = id, t = title, ct = ct, st = st,
                        en = ctime(function() return e.endTime end),
                        et = TN(function() return e.eventType end),
                        icon = TN(function() return e.iconTexture end),
                        diff = TSN(function() return e.difficultyName end),
                        lock = TB(function() return e.isLocked end) or nil,
                        nb = TB(function() return e.dontDisplayBanner end) or nil,
                        ne = TB(function() return e.dontDisplayEnd end) or nil,
                        on = {},
                    }
                    -- Invitation fields only where there can be an invitation. MEASURED on the Thor
                    -- (2026-09-28 21:35, c1): every HOLIDAY comes back with inviteStatus 0, which is
                    -- "Invited", so sending it would have made each holiday read as an invitation.
                    if PLAYER_MADE[ct] then
                        ev.inv = TN(function() return e.inviteStatus end)
                        ev.it = TN(function() return e.inviteType end)
                        ev.by = TSN(function() return e.invitedBy end)
                        ev.mod = TSN(function() return e.modStatus end)
                        -- `k` links the day entry to its page in `det`; `sig` is what makes a page stale
                        ev.k = evKey(id, st)
                        if id and title and st then
                            found[#found + 1] = {
                                key = ev.k, off = off, d = d, i = i, t = title, st = st, ev = ev,
                                sig = tostring(ev.inv) .. "|" .. tostring(ev.it) .. "|" .. tostring(ev.mod)
                                    .. "|" .. tostring(ev.lock) .. "|" .. title,
                            }
                        end
                    end
                    -- the game draws a holiday's banner only when it runs over MORE than two days
                    ev.nSeq = TN(function() return e.numSequenceDays end)
                    -- some titles are the NAME of a game string (raid resets): the game shows _G[title]
                    if TB(function() return e.isCustomTitle end) == false and title then
                        local shown = word(title)
                        if shown then ev.t, ev.tk = shown, title end
                    end
                    byKey[key] = ev
                    month.ev[#month.ev + 1] = ev
                    if ct == "HOLIDAY" and id and not hol[tostring(id)] then
                        hol[tostring(id)] = holidayInfo(off, d, i)
                    end
                end
                -- [day, index on that day, sequence, picture]: the order the game draws a day's events in;
                -- the sequence ("START" / "ONGOING" / "END" / "") decides the day's words ("%s Begins",
                -- nothing at all on an ONGOING day, "%s Ends"), and each day has its OWN picture (the
                -- game's start / ongoing / end banners); 0 = no picture, and then the game draws none
                ev.on[#ev.on + 1] = {
                    d, i,
                    TS(function() return e.sequenceType end) or "",
                    TN(function() return e.iconTexture end) or 0,
                }
            end
        end
    end
    return month
end

-- ---------------------------------------------------------------- event pages (c3)
-- An event's page is only readable while it is the calendar's OPEN event, and opening is answered
-- later by the server (CALENDAR_OPEN_EVENT). So: a queue, one open at a time, read on arrival, close.

local soon                  -- defined with the emission below
local det = {}              -- key -> the page as last read
local tries = {}            -- key -> { n = opens asked without a page to show for it, at = the last }
local wanted = {}           -- the player-made events of the last collection, with their day and index
local pending = nil         -- the open we are waiting for
local opens = 0             -- opens asked this load
local pausedUntil = 0
local lastErr = nil

local function readPage(info)
    local p = {
        desc = TSN(function() return info.description end),
        creator = TSN(function() return info.creator end),
        guild = TSN(function() return info.communityName end),
        et = TN(function() return info.eventType end),
        max = TN(function() return info.maxSize end),
        rep = TN(function() return info.repeatOption end),
        auto = TB(function() return info.isAutoApprove end) or nil,
        pendInv = TB(function() return info.hasPendingInvite end) or nil,
        lockT = ctime(function() return info.lockoutTime end),
    }
    -- the dungeon or raid the event is for: the game's own list for the event's kind, by index
    local texI = TN(function() return info.textureIndex end)
    if texI and p.et then
        local ok, list = CALL(function() return C_Calendar.EventGetTextures(p.et) end)
        local okE, entry = CALL(function() return list[texI] end)
        if ok and okE and type(entry) == "table" then
            p.dun = {
                t = TSN(function() return entry.title end),
                icon = TN(function() return entry.iconTexture end),
                diff = TN(function() return entry.difficultyId end),
            }
        end
    end
    -- the invite list (guild announcements have none); the room counts the classes itself
    local n = TN(function() return C_Calendar.GetNumInvites() end) or 0
    local inv = {}
    for i = 1, math.min(n, 100) do
        local ok, v = CALL(function() return C_Calendar.EventGetInvite(i) end)
        if ok and type(v) == "table" then
            inv[#inv + 1] = {
                n = TSN(function() return v.name end),
                c = TSN(function() return v.classFilename end),
                cn = TSN(function() return v.className end),
                lv = TN(function() return v.level end),
                s = TN(function() return v.inviteStatus end),
                mod = TSN(function() return v.modStatus end),
                me = TB(function() return v.inviteIsMine end) or nil,
                note = TSN(function() return v.notes end),
            }
        end
    end
    p.nInv = n
    if #inv > 0 then p.inv = inv end
    return p
end

local pump
-- the event that opened is ours only if it is the one asked for (the player may have clicked another)
local function onOpen()
    local p = pending
    if not p then return end
    pending = nil
    local ok, info = CALL(function() return C_Calendar.GetEventInfo() end)
    if ok and type(info) == "table" then
        local title = TS(function() return info.title end)
        local tm = ctime(function() return info.time end)
        if title == p.t and tm and tm.y == p.st.y and tm.m == p.st.m and tm.d == p.st.d then
            local page = readPage(info)
            page.sig, page.at = p.sig, GetTime()
            det[p.key] = page
            tries[p.key] = nil
            CALL(function() C_Calendar.CloseEvent() end)
        end
    end
    if soon then soon() end
    C_Timer.After(1.0, function() pump() end)
end

pump = function()
    if pending or GetTime() < pausedUntil or opens >= MAX_OPENS then return end
    if frameShown() or gameCalendarLoaded() then return end
    for _, w in ipairs(wanted) do
        local page = det[w.key]
        local stale = not page or page.sig ~= w.sig or GetTime() - page.at > DETAIL_AGE
        -- three opens without a page, then a minute's rest for that event (not for the rest of the load)
        local t = tries[w.key]
        if t and t.n >= 3 and GetTime() - t.at >= 60 then t = nil; tries[w.key] = nil end
        if stale and (not t or t.n < 3) then
            tries[w.key] = { n = (t and t.n or 0) + 1, at = GetTime() }
            opens = opens + 1
            local p = { key = w.key, t = w.t, st = w.st, sig = w.sig }
            pending = p
            local ok, took = CALL(function() return C_Calendar.OpenEvent(w.off, w.d, w.i) end)
            if not ok or took == false then
                pending = nil
                C_Timer.After(1.0, function() pump() end)
                return
            end
            -- no answer in 5 s: give up on this one for now, go on with the next
            C_Timer.After(5.0, function()
                if pending == p then pending = nil; pump() end
            end)
            return
        end
    end
end

-- the game reported a calendar error: if one of our opens was out, it may be ours; leave the server be
local function onError(key)
    if type(key) == "string" and WC.isPlain(key) then lastErr = key:sub(1, 60) end
    if pending then
        pending = nil
        pausedUntil = GetTime() + ERROR_PAUSE
    end
end

-- the pages to send: only those of events in the four months now
local function pagesFor(list)
    local out, any = {}, false
    for _, w in ipairs(list) do
        local page = det[w.key]
        if page then
            local copy = {}
            for k, v in pairs(page) do if k ~= "at" and k ~= "sig" then copy[k] = v end end
            out[w.key] = copy
            any = true
        end
    end
    if any then return out end
end

-- ---------------------------------------------------------------- the room's row: what may be answered (c7)

-- the controller's state as a guest of the shared capture (the guest code is with the taps, below)
local ctlr = { want = false, held = false, why = nil, n = 0, back = false, bnext = "home" }
local function capture()
    local c = WC.capture
    if type(c) == "table" and (c.version or 0) >= 1 and type(c.take) == "function" and type(c.release) == "function" then return c end
end

local function dayNum(t) return (t and t.y and t.m and t.d) and (t.y * 10000 + t.m * 100 + t.d) or nil end

-- The answers the game's own DAY MENU offers for an entry now (GenerateDayContextMenu, read in this client's
-- blizzard_calendar), plus the event page's one extra rule: a LOCKED event takes no answer. [e] = { ct, inv, it,
-- mod, lock, st }; [now], [maxD] = CalendarTime-like tables.
local function answersFor(e, now, maxD)
    local out = {}
    local ct = e.ct
    if not PLAYER_MADE[ct] or ct == "GUILD_ANNOUNCEMENT" then return out end
    local day, today, last = dayNum(e.st), dayNum(now), dayNum(maxD)
    local valid = day ~= nil and today ~= nil and day >= today and (last == nil or day <= last)
    local signUp = (ct == "GUILD_EVENT" or ct == "COMMUNITY_EVENT") and e.it == 1
    if valid and e.inv ~= nil and RSVP[e.inv] and not e.lock then
        if signUp then
            if e.inv == 7 then out[#out + 1] = "sig" end            -- Not Signed Up: the menu offers Sign Up alone
        elseif e.mod ~= "CREATOR" then
            if e.inv ~= 1 then out[#out + 1] = "acc" end
            if e.inv ~= 8 then out[#out + 1] = "ten" end
            if e.inv ~= 2 then out[#out + 1] = "dec" end
        end
    end
    -- _CalendarFrame_CanRemoveEvent: not the creator; a player's event, or a NORMAL guild / community invite
    if e.mod ~= "CREATOR" and (ct == "PLAYER" or ((ct == "GUILD_EVENT" or ct == "COMMUNITY_EVENT") and e.it == 0)) then
        out[#out + 1] = "rem"
    end
    return out
end

-- event key -> what a tap checks again before it acts (from the last collection)
local byEvent = {}
local lastSecret = false

-- every answer that is offered now gets a column of its own; the eight fixed keys always keep theirs
local function assignAnswers(found, now, maxD)
    local present = {}
    for _, k in ipairs(FIXED_KEYS) do present[k] = true end
    byEvent = {}
    for _, w in ipairs(found) do
        local ev = w.ev
        local ops = answersFor(ev, now, maxD)
        if #ops > 0 then
            local ans = {}
            for _, op in ipairs(ops) do
                local key = op .. "|" .. ev.k
                local col = WC.pad and WC.pad.slotFor("cal", key) or nil
                -- nil: every column busy (about six events' answers fit): that answer is not offered
                if col then ans[op] = col; present[key] = true end
            end
            if next(ans) ~= nil then ev.ans = ans end
            byEvent[ev.k] = { id = ev.id, t = w.t, st = ev.st }
        end
    end
    if WC.pad then WC.pad.retain("cal", present) end
end

local function keysDoc()
    local pad = WC.pad
    if not pad then return nil end
    local flt = {}
    for i, cv in ipairs(FILTER_CVARS) do flt[cv] = pad.slotFor("cal", "flt" .. i) end
    return { h = pad.slotFor("cal", "h"), f = pad.slotFor("cal", "f"), flt = flt,
             fb = pad.slotFor("cal", "fb"), bh = pad.slotFor("cal", "bh"), bb = pad.slotFor("cal", "bb") }
end

-- the pending-invitation count at the last look, so a RISE can ask the server for the new event
local lastPend = nil

function WC.collectCalendar()
    tally = { secret = 0, threw = 0 }
    local hasApi = type(C_Calendar) == "table" and type(C_Calendar.GetMonthInfo) == "function"
    local now = ctime(function() return C_DateAndTime.GetCurrentCalendarTime() end)
    local doc = {
        v = CV,
        api = hasApi,
        off = calendarOff(),
        now = now,
        shown = frameShown(),
        -- the game's calendar code is loaded: event pages are no longer read (kept as last read)
        gameCal = gameCalendarLoaded() or nil,
        cb = TB(function() return UnitAffectingCombat("player") end),
        -- (c7) the room's row and the controller
        pad = WC.pad and WC.pad.describe() or nil,
        keys = keysDoc(),
        -- (c10) `want` always written (true / false): the app taps h or f again when its tap was lost (Thor 2026-09-30 22:49)
        ctl = { held = ctlr.held or nil, want = ctlr.want and true or false, why = ctlr.why, cap = capture() ~= nil,
                -- (c11) B is the game's own close button now (the window is open and the room is at its top level)
                back = (ctlr.held and ctlr.back) or nil },
    }
    if not hasApi or doc.off == true then
        wanted = {}
        byEvent = {}
        doc.sec = { secret = tally.secret, threw = tally.threw }
        return doc
    end

    doc.str = words()
    -- the player's own name: the day note says "This is your event" when `by` is the player
    doc.me = TSN(function() return UnitName("player") end)
    local first = _G.CALENDAR_FIRST_WEEKDAY
    if type(first) == "number" and WC.isPlain(first) then doc.first = first end
    doc.mil = TB(function() return GetCVarBool("timeMgrUseMilitaryTime") end)
    doc.pend = TN(function() return C_Calendar.GetNumPendingInvites() end)
    if doc.pend then lastPend = doc.pend end
    -- an answer is out and the server has not replied: the game greys its buttons meanwhile
    doc.busy = TB(function() return C_Calendar.IsActionPending() end) or nil
    doc.err = lastErr
    doc.canAdd = TB(function() return C_Calendar.CanAddEvent() end)
    doc.minD = ctime(function() return C_Calendar.GetMinDate() end)
    doc.maxD = ctime(function() return C_Calendar.GetMaxCreateDate() end)
    local flt = {}
    for _, cv in ipairs(FILTER_CVARS) do
        flt[cv] = TB(function() return C_CVar.GetCVarBool(cv) end)
    end
    doc.flt = flt

    alignToToday(now)
    local before = tally.secret
    local months, hol, found = {}, {}, {}
    for _, off in ipairs(WINDOW) do
        local m = collectMonth(off, hol, found)
        if m then months[#months + 1] = m end
    end
    if tally.secret > before then
        -- a lockdown made some of it secret: a half-read month would show days as empty, so none; the
        -- answers' columns are kept as they were, and a tap on one is refused until the fight is over
        doc.secret = true
        wanted = {}
        lastSecret = true
    else
        lastSecret = false
        assignAnswers(found, now, doc.maxD)
        doc.months = months
        if next(hol) ~= nil then doc.hol = hol end
        wanted = found
        doc.det = pagesFor(found)
    end
    doc.sec = { secret = tally.secret, threw = tally.threw }
    return doc
end

-- ---------------------------------------------------------------- emission

local lastKey = nil

-- Collect, and send if changed (or forced). Returns true when something was written.
-- Does NOT push: callers decide, so a heartbeat ride-along costs no flush of its own.
local function emitCalendar(force)
    local doc = WC.collectCalendar()
    local key = WC.encode(doc)
    if key == lastKey and not force then return false end
    lastKey = key
    WC.send(WC.encode({ type = "calendar", c = doc }))
    return true
end

-- the calendar's events come in bursts (a whole month's list at once), so coalesce
local queued = false
local syncHeld              -- (c12) defined with the controller, below
soon = function()
    if queued then return end
    queued = true
    C_Timer.After(0.5, function()
        queued = false
        if syncHeld then syncHeld() end
        if emitCalendar(false) then WC.push() end
        pump()
    end)
end

-- Ask the server for the calendar's data: once per load when the player enters the world, and again
-- when a new invitation arrives (so its event appears on its day), at most once a minute.
local REQUEST_GAP = 60
local lastRequest = nil
local function request()
    if calendarOff() == true then return end
    local now = GetTime()
    if lastRequest and now - lastRequest < REQUEST_GAP then return end
    lastRequest = now
    CALL(function() C_Calendar.OpenCalendar() end)
end

-- ---------------------------------------------------------------- the room's taps (c7)

local function plain(v) if type(v) == "string" and WC.isPlain(v) then return v end end

local function ack(op, key, ok, why, extra)
    local out = { type = "calack", v = CV, op = op, key = plain(key), ok = ok and true or false, why = why }
    if extra then for k, v in pairs(extra) do out[k] = v end end
    WC.send(WC.encode(out))
    soon()
    WC.push()
end

-- The controller while the room is on the bottom screen (UI Art's SPEC 8): the app taps `h` when the room shows and
-- `f` when it is left. Interact's shared capture takes the eight buttons (the D-pad on the press, A / B / X / Y on the
-- release) and gives them to us; the app owns the glow, so every press is relayed as {"type":"calpad"}. Never held in
-- combat; asked for again after a fight or an NPC window, only while the room still wants it (as Group Finder does).
--
-- (c11) THE GAME'S OWN WINDOW BEHIND THE ROOM (the user, 2026-10-01 via Home: "it should act the EXACT same for all rooms";
-- picked "Every room opens the game's own window behind it, as Professions does"). The row's cells are SECURE: a tap on one
-- is a hardware click, so `h` clicks the game's own way in (the minimap's date square, GameTimeFrame) and `f` / `fb` the
-- window's own close button, in the game's own code. Addon code never opens, closes or toggles the window (the taint rule).
-- While the room holds the controller the window is invisible by ALPHA (spec.hide, the capture's work), and at the room's
-- top level B is the window's own close button (spec.back): the game closes its window, the controller goes back with "B"
-- and the app shows Home, in one press. Inside the room (a page, the filters, the day's list: the app's `bb`) B stays the
-- room's. A close we did not make with `f` / `fb` while B was the game's close counts as that B (Professions' rule).
local CLOSE = "CalendarCloseButton"
local HIDE = { "CalendarFrame" }
local CAP_SPEC = {
    press = function(k)
        -- (c12) a press proves the hold (a take in a fight is real only once Home's enter tap lands, with no word to us)
        if not ctlr.held then ctlr.held, ctlr.why = true, nil; soon() end
        ctlr.n = ctlr.n + 1
        WC.send(WC.encode({ type = "calpad", v = CV, k = plain(k), n = ctlr.n }))
        WC.push()
    end,
    lost = function(why)
        ctlr.held, ctlr.back = false, false
        ctlr.why = plain(why)
        -- (c12) "B": the player left (in a fight, Interact's leave: B or the red disc closed the window under the room)
        if ctlr.why == "B" then ctlr.want = false end
        WC.send(WC.encode({ type = "calpad", v = CV, lost = ctlr.why or "lost", n = ctlr.n }))
        soon()
    end,
}

-- the take for now: a fresh table each time (the capture keeps its own copy)
local function specNow(c)
    local v = c.version or 1
    local open = frameShown()
    local closeOK = open and type(_G[CLOSE]) == "table"
    local spec = { press = CAP_SPEC.press, lost = CAP_SPEC.lost, hide = HIDE }
    if closeOK and v >= 2 and ctlr.bnext ~= "back" then spec.back = CLOSE end
    -- capture v9 (Interact i78): its leaves (the generic one, the fight's) click `leave` first; `window` = this take is made
    -- because the game's window is open, and it pre-empts a room that still holds the controller
    if closeOK and v >= 9 then spec.leave = CLOSE end
    if open and v >= 9 then spec.window = true end
    return spec
end

local function takePad()
    local c = capture()
    if not c then ctlr.held, ctlr.why = false, "no controller capture" return false end
    local spec = specNow(c)
    local ok, why = c.take(GUEST, spec)
    if not ok and plain(why) == "bad spec" and (spec.leave or spec.window) then   -- a capture older than its version says
        spec.leave, spec.window = nil, nil
        ok, why = c.take(GUEST, spec)
    end
    if not ok and plain(why) == "bad spec" and spec.back then                    -- the close button is gone: B stays ours
        spec.back = nil
        ok, why = c.take(GUEST, spec)
    end
    ctlr.held = ok and true or false
    ctlr.back = ctlr.held and spec.back ~= nil
    ctlr.why = (not ok) and plain(why) or nil
    -- (c12) capture i81: a take in a fight answers ok but is PENDING until the app's enter tap (doc.enter) lands. (c14) capture
    -- v11 (i82): a take that finds another holder (a room or an NPC window) answers ok too, QUEUED until that holder lets go.
    -- Either way ok is not held: the capture's own word decides; the hand-over shows up later (a press, syncHeld)
    if ok then
        local okS, st = CALL(c.state)
        if okS and type(st) == "table" and st.holder ~= GUEST then
            ctlr.held, ctlr.back, ctlr.why = false, false, InCombatLockdown() and "pending" or "queued"
        end
    end
    return ctlr.held
end

local function freePad(why)
    local c = capture()
    if not c then ctlr.held, ctlr.back = false, false return end
    -- (c10) the capture's own word too: whatever this file believes, a capture still naming the calendar is given back.
    -- (c14) ALWAYS asked, held or not: a take still waiting (queued behind another holder, or pending in a fight) is given up
    -- only by our own release, else it would be handed to a room already left. A release by a non-holder is refused by the
    -- capture itself, so it can never take the controller from anyone else.
    do
        local okR, done, rwhy = CALL(c.release, GUEST, why)
        -- (c12) capture i81: in a fight only Interact's leave gives the pad back ("tap leave": Home taps it). Still held, said
        -- so; no retry of ours in the fight; at its end, a room that no longer wants the controller lets go by itself
        if okR and done == false and plain(rwhy) == "tap leave" then ctlr.why = "tap leave" return end
    end
    ctlr.held, ctlr.back = false, false
end

-- (c12) who REALLY holds, by the capture's own word: a pending take that Home's enter made real, a pending take dropped at the
-- fight's end, a leave in a fight. Returns true when it changed what this file believed.
syncHeld = function()
    local c = capture(); if not c then return false end
    local okS, st = CALL(c.state)
    if not (okS and type(st) == "table") then return false end
    local mine = st.holder == GUEST
    if mine == ctlr.held then return false end
    ctlr.held = mine
    if mine then ctlr.why = nil else ctlr.back = false end
    return true
end

-- (c9) tell the capture where the next B leads (version 3; no game action either way)
local function sayNext()
    local c = capture()
    if c and ctlr.held and (c.version or 1) >= 3 and type(c.bnext) == "function" then CALL(c.bnext, GUEST, ctlr.bnext) end
end

-- asked for again by itself only when nobody holds the capture (the capture's own rule for a guest); a fresh take starts at home
local function retake()
    if not ctlr.want or ctlr.held or InCombatLockdown() then return end
    local c = capture(); if not c then return end
    local okS, st = CALL(c.state)
    if okS and type(st) == "table" and st.holder ~= nil then return end
    ctlr.bnext = "home"
    if takePad() then soon() end
end

-- hold / free: no game action of their own (the cell's secure click, made just before, was the game's); the state rides
-- the next document. `h` runs AFTER its click, so a window it opened is open here and B is its close at once.
local closeAt = -1000           -- our own `f` / `fb` closed the window (their post-click runs in the same click)
local function hold()
    ctlr.want = true
    closeAt = -1000             -- a close of ours from before this entry is not this one's
    if not ctlr.held then ctlr.bnext = "home"; takePad()
    elseif not InCombatLockdown() and ctlr.back ~= (specNow(capture() or {}).back ~= nil) then takePad() end
    soon()
end
local function free(why) closeAt = GetTime(); ctlr.want = false; freePad(why); soon() end

-- (c9) where the next B leads, from the app: "back" while something of the room is open, "home" at its top. (c11) At the top
-- B is the game's close (taken again WITH spec.back); inside, it is the room's (taken again without it). The holder's own
-- take again: allowed by the capture, out of combat only (the room is never held in combat).
local function bnext(where)
    if where ~= "home" and where ~= "back" then return end
    ctlr.bnext = where
    if not ctlr.held or InCombatLockdown() then return end
    local c = capture(); if not c then return end
    if ctlr.back ~= (specNow(c).back ~= nil) then takePad() end
    sayNext()
    soon()
end

-- (c11) the game's window opened or closed (its own OnShow / OnHide, a frame later). Opened while we hold: B becomes its
-- close. Closed while B was its close and the room still wanted the controller: the player pressed B (the game closed its
-- own window), so the controller goes back with "B" and the app is told (`BACK`), unless our own `f` / `fb` did it.
local function winChanged(open)
    if open then
        if ctlr.held and not ctlr.back and not InCombatLockdown() and specNow(capture() or {}).back then takePad() end
        soon()
        return
    end
    if ctlr.want and ctlr.held and ctlr.back then
        ctlr.want = false
        if GetTime() - closeAt < 2 then freePad() else
            freePad("B")
            ctlr.n = ctlr.n + 1
            WC.send(WC.encode({ type = "calpad", v = CV, k = "BACK", n = ctlr.n }))
            WC.push()
        end
    end
    soon()
end

-- A filter tick: the game's own setting flips (what its Filters menu does); ONE game action.
local function filter(i, key)
    local cv = FILTER_CVARS[i]
    if not cv then return ack("filter", key, false, "no such filter") end
    local cur = TB(function() return C_CVar.GetCVarBool(cv) end)
    if cur == nil then return ack("filter", key, false, "the game has no such setting") end
    local ok = CALL(function() C_CVar.SetCVar(cv, cur and "0" or "1") end)
    local now = TB(function() return C_CVar.GetCVarBool(cv) end)
    local took = ok and now == (not cur)
    ack("filter", key, took, (not took) and "the game did not take it" or nil, { cvar = cv, on = now })
end

-- the month offset of [t] from the calendar's month 0 as it is NOW (the player may have paged the game's window)
local function offsetOf(t)
    local ok, mi = CALL(function() return C_Calendar.GetMonthInfo(0) end)
    if not ok or type(mi) ~= "table" then return nil end
    local y = SN(function() return mi.year end)
    local m = SN(function() return mi.month end)
    if not y or not m or not t or not t.y or not t.m then return nil end
    return (t.y * 12 + t.m) - (y * 12 + m)
end

-- Find the event again, by its id, title and start, on its own day (a player's event lasts one day), and read it
-- as it is now: the tap may come from a document a moment old.
local function locate(ek)
    local e = byEvent[ek]
    if not e then return nil end
    local off = offsetOf(e.st)
    if not off then return nil end
    local n = SN(function() return C_Calendar.GetNumDayEvents(off, e.st.d) end) or 0
    for i = 1, math.min(n, 60) do
        local ok, ev = CALL(function() return C_Calendar.GetDayEvent(off, e.st.d, i) end)
        if ok and type(ev) == "table" then
            local st = ctime(function() return ev.startTime end)
            if SN(function() return ev.eventID end) == e.id and S(function() return ev.title end) == e.t
               and st and st.h == e.st.h and st.mi == e.st.mi then
                return off, e.st.d, i, {
                    ct = TS(function() return ev.calendarType end),
                    inv = TN(function() return ev.inviteStatus end),
                    it = TN(function() return ev.inviteType end),
                    mod = TSN(function() return ev.modStatus end),
                    lock = TB(function() return ev.isLocked end) or nil,
                    st = st,
                }
            end
        end
    end
end

-- An answer: ONE game action, the game's own day-menu call, only when that menu would offer it now.
local function answer(op, ek, key)
    if lastSecret then return ack(op, key, false, "the game hides the calendar during this fight") end
    local off, d, i, live = locate(ek)
    if not off then return ack(op, key, false, "the event is gone") end
    local now = ctime(function() return C_DateAndTime.GetCurrentCalendarTime() end)
    local maxD = ctime(function() return C_Calendar.GetMaxCreateDate() end)
    local offered = false
    for _, o in ipairs(answersFor(live, now, maxD)) do if o == op then offered = true end end
    if not offered then return ack(op, key, false, "the game does not offer that now", { ev = ek }) end
    local okSel = CALL(function() C_Calendar.ContextMenuSelectEvent(off, d, i) end)
    local okAns = okSel and CALL(function() C_Calendar[ANSWER_CALLS[op]]() end)
    ack(op, key, okAns, (not okAns) and "the game refused" or nil, { ev = ek })
end

-- (c11) the secure cells' targets. `h` = the minimap's date square, only while the window is CLOSED (its click toggles);
-- `f` / `fb` = the window's own close button, only while it is OPEN. Attributes change out of combat only (frozen for a
-- fight; set once more at its start, while still allowed). (c12) `h` stays armed through fights: capture i81 holds a room
-- through a fight and takes one mid-fight (pending until Home's enter tap), so the window opened then is hidden by the hold
-- (SPEC_COMBAT item 9, the user's sign-off 2026-10-01). (c13) Only with a capture of version 10 or later (i81's fight rules):
-- an older one lets go in a fight, and a window opened then would show on the top screen, so the door is disarmed for it.
local ROW_COLS = 40
local DOOR = "GameTimeFrame"
local cells = {}
local cellsPending, fighting = false, false
local function fightTakes()
    local c = capture()
    return c ~= nil and (c.version or 1) >= 10
end
local function keyAt(col)
    local t = WC.pad and WC.pad.ns and WC.pad.ns["cal"]
    return t and t.byCol[col] or nil
end
local function cellTarget(col)
    local key = keyAt(col)
    local open = frameShown()
    if key == "h" then
        if open or calendarOff() == true then return nil end
        if fighting and not fightTakes() then return nil end
        local d = _G[DOOR]
        return type(d) == "table" and d or nil
    elseif key == "f" or key == "fb" then
        local x = open and _G[CLOSE] or nil
        return type(x) == "table" and x or nil
    end
    return nil
end
local function syncCell(btn, col)
    local tg = cellTarget(col)
    if btn.wcSet and btn.wcTarget == tg then return end
    if tg then
        btn:SetAttribute("type", "click")
        btn:SetAttribute("clickbutton", tg)
    else
        btn:SetAttribute("type", nil)
        btn:SetAttribute("clickbutton", nil)
    end
    btn.wcTarget, btn.wcSet = tg, true
end
local function syncCells()
    if InCombatLockdown() then cellsPending = true return end
    for col, btn in pairs(cells) do pcall(syncCell, btn, col) end
    cellsPending = false
end

-- the window's own show / hide (load-on-demand: hooked once it exists). Our hooks only look; a frame later they re-arm the
-- cells and tell the room (never inside the game's own show / hide)
local winHooked = false
local function hookWindow()
    if winHooked then return end
    local fr = _G.CalendarFrame
    if type(fr) ~= "table" or type(fr.HookScript) ~= "function" then return end
    winHooked = true
    pcall(function()
        fr:HookScript("OnShow", function() C_Timer.After(0, function() syncCells(); winChanged(true) end) end)
        fr:HookScript("OnHide", function() C_Timer.After(0, function() syncCells(); winChanged(false) end) end)
    end)
end
hookWindow()        -- already loaded (a /reload after the window was opened)

if WC.pad then
    for _, k in ipairs(FIXED_KEYS) do WC.pad.slotFor("cal", k) end      -- columns 0..7, before anything else
    local function onKey(key)
        if type(key) ~= "string" then return ack("?", nil, false, "no key at that column") end
        local ok = pcall(function()
            if key == "h" then return hold() end
            if key == "f" then return free() end
            if key == "fb" then return free("B") end
            if key == "bh" then return bnext("home") end
            if key == "bb" then return bnext("back") end
            local fi = key:match("^flt(%d)$")
            if fi then return filter(tonumber(fi), key) end
            local op, ek = key:match("^(%a+)|(.+)$")
            if op and ANSWER_CALLS[op] then return answer(op, ek, key) end
            ack("?", key, false, "unknown key")
        end)
        if not ok then ack("?", key, false, "failed") end
    end
    -- (c11) the row is SECURE (the pad is full: the room's own row, made secure, not a new one). Its cells click a game
    -- button only where cellTarget says; every other cell is a plain click whose post-click runs the room's key, once per
    -- tap, on the release (the secure template's phase: a tap right after a controller press can be lost, and the app's
    -- reconcile taps again for the hold; an answer shows its own missing reply)
    WC.pad.secureRow("cal", ROW_COLS, function(btn, col)
        cells[col] = btn
        syncCell(btn, col)
    end, function(col)
        onKey(keyAt(col))
    end)
    -- a new UI scale moves every cell: re-publish the geometry at once
    WC.pad.onLayout(function() soon() end)
end

local f = CreateFrame("Frame")
f:SetScript("OnEvent", function(_, event, arg1)
    if event == "PLAYER_REGEN_DISABLED" then
        fighting = true
        syncCells()                 -- still allowed here: the cells as the window is, frozen for the fight
        return
    elseif event == "ADDON_LOADED" then
        if arg1 ~= "Blizzard_Calendar" then return end
        C_Timer.After(0, function() hookWindow(); syncCells() end)
    elseif event == "PLAYER_REGEN_ENABLED" then
        fighting = false
        syncCells()
        -- (c12) the fight's end: who holds now (a pending take dropped, a leave made); a room that no longer wants the controller
        -- lets go (its free was refused "tap leave" in the fight); one still held gets B's meaning back from before the fight
        syncHeld()
        if not ctlr.want and ctlr.held then freePad() end
        if ctlr.held and not InCombatLockdown() then
            local c = capture()
            if c and ctlr.back ~= (specNow(c).back ~= nil) then takePad() end
            sayNext()
        end
        retake()
    elseif event == "CVAR_UPDATE" then
        -- only the calendar's own filters matter here
        local mine = false
        for _, cv in ipairs(FILTER_CVARS) do if arg1 == cv then mine = true end end
        if not mine then return end
    elseif event == "PLAYER_ENTERING_WORLD" then
        if not lastRequest then request() end
    elseif event == "CALENDAR_UPDATE_PENDING_INVITES" then
        local p = SN(function() return C_Calendar.GetNumPendingInvites() end)
        if p and lastPend and p > lastPend then request() end
        if p then lastPend = p end
    elseif event == "CALENDAR_OPEN_EVENT" then
        onOpen()
        return
    elseif event == "CALENDAR_UPDATE_ERROR" or event == "CALENDAR_UPDATE_ERROR_WITH_COUNT"
        or event == "CALENDAR_UPDATE_ERROR_WITH_PLAYER_NAME" then
        onError(arg1)
    end
    soon()
end)
local WANT = {
    "PLAYER_ENTERING_WORLD",
    "CALENDAR_UPDATE_EVENT_LIST", "CALENDAR_UPDATE_PENDING_INVITES", "CALENDAR_UPDATE_EVENT",
    "CALENDAR_UPDATE_INVITE_LIST", "CALENDAR_NEW_EVENT", "CALENDAR_UPDATE_GUILD_EVENTS",
    "CALENDAR_CLOSE_EVENT", "CALENDAR_OPEN_EVENT", "CALENDAR_ACTION_PENDING",
    "CALENDAR_UPDATE_ERROR", "CALENDAR_UPDATE_ERROR_WITH_COUNT", "CALENDAR_UPDATE_ERROR_WITH_PLAYER_NAME",
    -- (c7) the controller is asked for again after a fight; a filter changed in the game's own menu
    "PLAYER_REGEN_ENABLED", "CVAR_UPDATE",
    -- (c11) the game's window: its code loading, and the fight's start (the door cell is disarmed)
    "ADDON_LOADED", "PLAYER_REGEN_DISABLED",
}
local missing = {}
for _, e in ipairs(WANT) do
    local ok, registered = pcall(function() return f:RegisterEvent(e) end)
    if not ok or registered == false then missing[#missing + 1] = e end
end
if #missing > 0 then
    pcall(function() WC.send(WC.encode({ type = "cap", calendar = CV, missingEvents = missing })) end)
end

-- RIDE THE CORE HEARTBEAT, as Quests does: sent inside the heartbeat's own flush, so it costs no
-- flush of its own, and the day rolls over on the bottom screen within 15 s of midnight.
local RIDE = { heartbeat = true, login = true, zone = true, manual = true }
if type(WC.snapshot) == "function" then
    hooksecurefunc(WC, "snapshot", function(reason)
        if RIDE[reason] then emitCalendar(true); pump() end
        -- after an NPC window let the capture go, the room asks for it again (only while it still wants it)
        retake()
    end)
end
