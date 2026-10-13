-- WowCompanionGroupFinder / GroupFinder.lua
-- The GROUP FINDER room: the game's "Looking for Group" board on the bottom screen.
--
-- WHAT THE GAME HAS (read from Blizzard's source for this build, 2026-09-28): NOT the retail Dungeon
-- Finder. Blizzard_GroupFinder says ExcludeLoadGameType: camelot, and the camelot queue-status override
-- says "Camelot uses the Vanilla-style group finder". That is Blizzard_GroupFinder_VanillaStyle, the
-- Classic Era board: players LIST themselves for activities, others BROWSE the list and whisper them.
-- Its window is LFGParentFrame (tabs List / Browse / Who). The server switches it on
-- (C_LFGInfo.IsGroupFinderEnabled). None of that is needed here: the C_LFGList calls work with the
-- game's window closed, so the room can search on its own.
--
-- USER RULING (2026-09-28): BROWSE FIRST. Pick a category, see who is looking, select one, then ONE
-- action: whisper the leader (the Chat room's composer, app side) or invite a solo player. Listing
-- yourself is step two, so this file only READS the player's own listing (the minimap eye's row, which
-- the pvp session draws) and offers the one delist the eye's menu has.
--
-- WHAT IT SENDS: two documents, each only when it changed, each riding the core's 15 s heartbeat.
--   {"type":"gfcat"}  the catalogue: categories and their activities, as the game's own list filters
--                      them (the suggested-level filter, unless the player's setting turns it off).
--   {"type":"gf"}     the state: the switch, the player's group standing, the search and its results,
--                      the player's own listing.
-- A per-result update (LFG_LIST_SEARCH_RESULT_UPDATED) is written but asks for NO flush of its own: it
-- leaves with the next flush anyone makes. Only a search's arrival, a failure, a tap's answer and a
-- change of the player's own listing push. Browsing costs no flush rate (see Sink.lua).
--
-- WHAT THE GAME HIDES, and how it is kept off the wire:
--   * a listing's title and description are PROTECTED TEXT (kstringLfgListSearch): the game can draw
--     them, an addon cannot read them. Only "has a description" travels (`note`), never the text.
--   * every search-result call is SecretInChatMessagingLockdown: in an instance fight the values turn
--     secret. They go through S() like everything else, are counted in `sec`, and `lock` says why.
--
-- THE THREE TAPS, one pad row "gf" (namespace "gf"), keys by kind:
--   "s<categoryID>"  search that category, over the activities the game's own list shows for it.
--                    C_LFGList.Search carries HasRestrictions (it wants a real press). A tap-pad click
--                    IS a real mouse click, so it is called inside the tap. UNPROVEN ON THE DEVICE.
--   "i<name>"        invite that solo player (C_PartyInfo.InviteUnit), re-checked at the tap against the
--                    current results. Keyed by the PLAYER, not the search result: a result's id is new at
--                    every search, a name is not, so a player keeps one column from search to search.
--   "d"              delist the player's own listing (C_LFGList.RemoveListing), as the eye's menu does.
--   "sm"             "Your Listing": search the category of my first listed activity over my own activities in it
--                    (LFGBrowseMixin:SearchActiveEntry, the game's first Category entry while I am listed).
--   "fb"             free after the player LEFT WITH B: capture.release(id, "B"), so the app shows Home (interact's contract)
--   "bh" / "bb"      where the next B leads, "home" / "back" (capture v3's bnext; the app shows B on the Home button for
--                    "home"); ignored by a version 2 capture. None of these five is a game action.
--   "h" / "f"        hold / free the controller while the room shows (a guest of interact's shared capture; no game
--                    action): the eight buttons come to the room, every press relayed as {"type":"gfpad"}; the game's
--                    own LFGParentFrame goes to alpha 0 THROUGH THE CAPTURE (spec.hide) while held. Nothing here
--                    opens, closes or hides a window itself.
-- COLUMNS (the pad owner's rules, AGENT_LOG b5d7b5d): the six fixed keys are claimed FIRST, at load, so they
-- sit in columns 0..5 (the five vanilla categories of lfgvanilla_constants.lua, then delist); they are always
-- in the set given to retain(); players take the columns after them, behind the pad's 30 s quarantine; the
-- handler switches on the KEY, never the column, and refuses a column with no key.
-- Search is refused in combat; invite and delist are allowed where the game allows them.
--
-- CREATE LISTING (step two; user 2026-09-30: the game's own window, the description-only categories greyed with the reason,
-- Back = Unlist while listed, as the game). A SECOND pad row "gfl" (the pad owner's yes, AGENT_LOG f1a3976). The addon keeps a
-- DRAFT (category, ticked activities, New Players Welcome) built by taps that are no game action, then ONE Post tap:
--   "c<categoryID>"  the draft's category (the game's category buttons); while listed in it, the listing's activities load
--   "t<activityID>"  tick / untick an activity of the draft's category (not for an auto-choose category: those take every one)
--   "rt" "rh" "rd"   tank / healer / damage on or off, solo: C_LFGListRoles.SetRoles (engine; the game's own role boxes do the same)
--   "ln"             New Players Welcome on or off in the draft (read from the game's CVar lfgNewPlayerFriendly, never written)
--   "lp"             Post / Update: C_LFGList.CreateListing / UpdateListing, the game's five rules checked first; HasRestrictions
--                    (a real press; a pad tap should qualify, UNPROVEN). One at a time: refused while one waits for the game.
-- WHO, the game's tab 3 (user 2026-09-30: "Only in Group Finder, like the game"; Social's measured code moved here). A third row
-- "gfw" (the quests pad owner's yes, 2026-09-30): fixed keys claimed first, in this order: "ws" search, "wl" my level, "wz" my zone,
-- "wca" / "wcu" / "wra" / "wru" the game's Check All / Uncheck All of the classes and of the races (UI Art's WHO_SPEC 4: one tap does
-- it), "wc<classID>" the classes, "wr<raceID>" the races (all but the search are filter toggles, no game action); then "wi<name>" to
-- invite a result (the columns left: a result without one is shown as not invitable). The classes and races tick as the game's do:
-- all ticked at first, a search leaves out the unticked ones (its classIDs / raceIDs), so Uncheck All finds nobody, as in the game. The search = C_FriendList.SendWho with
-- the game's own filter table (LFGWhoListFilterUtil.GetWhoFilters' shape); HasRestrictions, one tap, UNPROVEN on the device.
-- The game re-opens its LFG window on EVERY Who result (wholist.lua, when C_SocialUI.IsSystemEnabled() is false, as on Forever): a
-- fourth, SECURE one-cell row "gfclose" clicks the game's own close button (LFGParentFrameCloseButton) when the app leaves the room
-- by touch; set up once the game has loaded its window, re-run after ADDON_LOADED (out of combat, the pad queues it).
-- THE DESCRIPTION IS NOT OURS: the game's box is a secure reference (SetText and paste disabled for addons); the engine reads it
-- when the listing is posted. Quests & Zones and Custom make it mandatory, so a post for them is refused here ("needs a
-- description"), and the room greys them. Whether the box already holds typed text is read (never its text) as `desc`.
--
-- Every value goes through S()/SN() (the T* wrappers), so nothing raw is ever concatenated.

local ADDON = ...
local WC = _G.WowCompanion
if type(WC) ~= "table" or type(WC.S) ~= "function" or type(WC.send) ~= "function" then return end
local S, SN, CALL = WC.S, WC.SN, WC.CALL

local GV = "g10"    -- bump on every change; the app shows it, so a stale build is visible
local MAX_RES = 40  -- results sent; the pad row has 40 columns and the invite key takes one each
local MAX_MEM = 10  -- the game lists the members of a group of up to ten, and none of a larger one (UI Art's SPEC 5)
local SEARCH_TIMEOUT = 15
-- the vanilla board's categories (lfgvanilla_constants.lua: Dungeons, Raids, Quests & Zones, PvP, Custom), then delist,
-- "Your Listing" (search my own listing's activities, the game's first Category entry while listed), and the controller's
-- hold / free (no game action: the room takes the shared capture while it shows)
local FIXED_KEYS = { "s2", "s114", "s116", "s118", "s120", "d", "sm", "h", "f", "fb", "bh", "bb" }
local GUEST = "groupfinder"     -- our name as a guest of the shared controller capture (interact's)

local function real(v)
    return v ~= nil and v ~= WC.ABSENT and v ~= WC.SECRET and v ~= WC.THREW
end

-- S() that counts sentinel hits, so a secret shows up on the wire (`sec`) instead of as a quietly
-- emptier list. ABSENT is not counted: nil is a legitimate answer for most of these.
local tally = { s = 0, t = 0 }
local function T(fn)
    local v = S(fn)
    if v == WC.SECRET then tally.s = tally.s + 1
    elseif v == WC.THREW then tally.t = tally.t + 1 end
    return v
end
local function TS(fn) local v = T(fn); if type(v) == "string" and real(v) then return v end end
local function TN(fn) local v = T(fn); if type(v) == "number" then return v end end
local function TB(fn) local v = T(fn); if type(v) == "boolean" then return v end end
local function secTally()
    if tally.s > 0 or tally.t > 0 then return { s = tally.s, t = tally.t } end
end

-- a numeric array, value by value (a secret element is dropped and counted)
local function numbers(fn)
    local out = {}
    local ok, t = CALL(fn)
    if ok and type(t) == "table" then
        local n = SN(function() return #t end) or 0
        for i = 1, n do
            local v = TN(function() return t[i] end)
            if v then out[#out + 1] = v end
        end
    end
    return out
end

-- protected text is a string that starts with the |K escape: never shipped as text
local function kstring(v)
    return type(v) == "string" and v:sub(1, 2) == "|K"
end

local function roles(fn)
    local ok, r = CALL(fn)
    if not ok or type(r) ~= "table" then return nil end
    local t = TB(function() return r.tank end)
    local h = TB(function() return r.healer end)
    local d = TB(function() return r.dps end)
    if t == nil and h == nil and d == nil then return nil end
    return { t = t or nil, h = h or nil, d = d or nil }
end

-- ---------------------------------------------------------------- the catalogue

local function activityInfo(id)
    local ok, a = CALL(function() return C_LFGList.GetActivityInfoTable(id) end)
    if ok and type(a) == "table" then return a end
end

local function listedActivities()
    local set = {}
    for _, id in ipairs(numbers(function()
        local e = C_LFGList.GetActiveEntryInfo()
        return e and e.activityIDs
    end)) do set[id] = true end
    return set
end

-- The game's own list, as LFGUtil_GetFilteredActivities (blizzard_lfgvanilla_parentframe.lua) builds it:
-- the category's activities, minus those outside the player's suggested levels unless the player's
-- setting says otherwise, keeping any activity the player is listed for.
local function filteredActivities(cid, lvl, noFilter, listed)
    local out = {}
    for _, aid in ipairs(numbers(function() return C_LFGList.GetAvailableActivities(cid) end)) do
        local a = activityInfo(aid)
        if a then
            local smin = TN(function() return a.minLevelSuggestion end)
            local smax = TN(function() return a.maxLevelSuggestion end)
            local outside = lvl and ((smin and smin > 0 and smin > lvl) or (smax and smax > 0 and smax < lvl))
            if noFilter or listed[aid] or not outside then
                out[#out + 1] = { aid = aid, a = a, smin = smin, smax = smax }
            end
        end
    end
    return out
end

local catIDs = {}   -- the last catalogue's categories, in order: what a search key may name

-- The instances the character is saved to, as the game's listing reads them (LFGListingMixin:FetchSavedInstances): engine
-- calls only. The game asks the server for fresh ones when its own listing opens (RequestRaidInfo); we only read what is there.
local function savedInstances()
    local out = {}
    local n = SN(function() return GetNumSavedInstances() end) or 0
    for i = 1, n do
        local ok, _, _, _, difficultyID, _, _, _, _, _, _, total, done, _, mapID = CALL(function() return GetSavedInstanceInfo(i) end)
        if ok and type(mapID) == "number" and WC.isPlain(mapID) and type(difficultyID) == "number" and WC.isPlain(difficultyID) then
            out[#out + 1] = { map = mapID, dif = difficultyID,
                red = SN(function() return C_RaidLocks.GetRedirectedDifficultyID(mapID, difficultyID) end),
                total = (type(total) == "number" and WC.isPlain(total)) and total or 0,
                done = (type(done) == "number" and WC.isPlain(done)) and done or 0 }
        end
    end
    return out
end

-- the game's row rule (LFGListingActivityView_InitActivityButton): the same map, the same difficulty or a shared lock, in progress or done
local function isSaved(a, saved)
    local map = TN(function() return a.mapID end)
    local dif = TN(function() return a.difficultyID end)
    if not map or not dif then return false end
    local red = TN(function() return a.redirectedDifficultyID end)
    for _, inst in ipairs(saved) do
        if inst.map == map and inst.done > 0 and inst.total > 0 and (inst.dif == dif or (red ~= nil and inst.red == red)) then
            return true
        end
    end
    return false
end
-- Create Listing: the tickable activities of each category that is not auto-choose (the game's own list), and their union. The
-- pad owner's rule: while the union fits (LTICK_MAX columns) every category's tick keys stay slotted, so a column never moves
local tickable, tickUnion = {}, {}          -- tickUnion: the KEYS ("t<activityID>", "g<groupID>") of every tickable category
local tickGroups = {}                        -- [categoryID] = { [groupID] = { activityIDs } }: one tick for a whole activity group
local LTICK_MAX = 30
local LFIXED = { "c2", "c114", "c116", "c118", "c120", "rt", "rh", "rd", "ln", "lp" }

function WC.collectGfCatalogue()
    tally = { s = 0, t = 0 }
    local lvl = SN(function() return UnitLevel("player") end)
    local noFilter = TB(function() return GetCVarBool("disableSuggestedLevelActivityFilter") end) or false
    local listed = listedActivities()
    local cats, ids = {}, {}
    local newTickable, union, newGroups = {}, {}, {}
    local saved = savedInstances()
    for _, cid in ipairs(numbers(function() return C_LFGList.GetAvailableCategories() end)) do
        local okI, ci = CALL(function() return C_LFGList.GetLfgCategoryInfo(cid) end)
        if not okI or type(ci) ~= "table" then ci = {} end
        local acts, groups, seen = {}, {}, {}
        for _, f in ipairs(filteredActivities(cid, lvl, noFilter, listed)) do
            local a = f.a
            local full = TS(function() return a.fullName end)
            local short = TS(function() return a.shortName end)
            local g = TN(function() return a.groupFinderActivityGroupID end)
            acts[#acts + 1] = {
                id = f.aid,
                n = (short and short ~= "") and short or full,     -- the game prefers the short name
                full = (short and short ~= "" and full ~= short) and full or nil,
                g = (g and g > 0) and g or nil,
                smin = (f.smin and f.smin > 0) and f.smin or nil,
                smax = (f.smax and f.smax > 0) and f.smax or nil,
                min = TN(function() return a.minLevel end),
                max = TN(function() return a.maxNumPlayers end),
                dt = TN(function() return a.displayType end),
                o = TN(function() return a.orderIndex end),
                map = TN(function() return a.mapID end),
                sv = isSaved(a, saved) or nil,                -- the character is saved to it (the game's warning mark)
            }
            if g and g > 0 and not seen[g] then
                seen[g] = true
                local okG, gname, gorder = CALL(function() return C_LFGList.GetActivityGroupInfo(g) end)
                if okG and type(gname) == "string" and WC.isPlain(gname) then
                    groups[#groups + 1] = { id = g, n = gname,
                        o = (type(gorder) == "number" and WC.isPlain(gorder)) and gorder or nil }
                end
            end
        end
        ids[#ids + 1] = cid
        local tl = {}
        if TB(function() return ci.autoChooseActivity end) ~= true then
            local gs = {}
            for _, a in ipairs(acts) do
                tl[#tl + 1] = a.id; union[#union + 1] = "t" .. a.id
                if a.g then
                    if not gs[a.g] then gs[a.g] = {}; union[#union + 1] = "g" .. a.g end
                    table.insert(gs[a.g], a.id)
                end
            end
            newGroups[cid] = gs
        end
        newTickable[cid] = tl
        cats[#cats + 1] = {
            id = cid,
            n = TS(function() return ci.name end),
            auto = TB(function() return ci.autoChooseActivity end) or nil,
            acts = acts,
            grp = (#groups > 0) and groups or nil,
            slot = WC.pad and WC.pad.slotFor("gf", "s" .. cid) or nil,
        }
    end
    catIDs = ids
    tickable, tickUnion, tickGroups = newTickable, union, newGroups
    if WC.pad and #union <= LTICK_MAX then
        for _, k in ipairs(union) do WC.pad.slotFor("gfl", k) end
    end
    return {
        v = GV,
        lvl = lvl,
        nofilter = noFilter or nil,
        cats = cats,
        sec = secTally(),
    }
end

-- ---------------------------------------------------------------- the state

local search = { st = "idle", cat = nil, t0 = nil, why = nil, mine = false }
-- Who: the filter (toggled by taps, no game action), when the last result list came, what was asked
local filt = { lvl = false, zone = false, classes = {}, races = {} }     -- classes / races: the UNticked ones (the game's default: all ticked)
local whoAt, whoAsked = nil, nil
local MAX_WHO = 50
local FILTER_SPAN = 2       -- "My level": my level - 2 .. + 2 (Social's, UI Art's SPEC)
local whoOpts = nil         -- the filter's options (classes, races), cached once the game has given both lists
local whoOptsGiveUp = nil   -- when to cache without races after all (a client that never gives any): 30 s after the first ask

-- Create Listing's draft (app taps that are no game action) and a post waiting for the game's answer
local draft = { cat = nil, acts = {}, npf = nil, dirty = false, why = nil }
local posting = nil
local POST_TIMEOUT = 10
local closeReady = false    -- the secure close cell has its click target (the game's window is loaded)
local openArmed = false     -- the secure open cell (gfclose column 1) clicks the game's button now (only while the window is shut)
-- the controller's state as a guest of the shared capture (the guest code is further down, with the taps)
local ctlr = { want = false, held = false, why = nil, n = 0, back = false, lr = false,
               roomB = false,
               wait = nil,      -- capture v11: the take answered true, "queued" / "pending": asked for, not held yet
               tn = 0 }         -- how many times the hold became REAL (the app opens the game's window only after a new one)   -- roomB: a menu of the room is open, so B is the room's (it closes the menu), not the game's

local function capture()
    local c = WC.capture
    if type(c) == "table" and (c.version or 0) >= 1 and type(c.take) == "function" and type(c.release) == "function" then return c end
end

local function inGroupStanding()
    local inGrp = TB(function() return IsInGroup() end) or false
    local lead = TB(function() return UnitIsGroupLeader("player") end) or false
    local asst = TB(function() return UnitIsGroupAssistant("player") end) or false
    return inGrp, lead, asst, (not inGrp) or lead or asst
end

local function listing(inGrp, lead)
    local has = TB(function() return C_LFGList.HasActiveEntryInfo() end)
    if not has then return { on = false } end
    local ok, e = CALL(function() return C_LFGList.GetActiveEntryInfo() end)
    local out = { on = true, lead = (not inGrp) or lead }
    if ok and type(e) == "table" then
        out.acts = numbers(function() return e.activityIDs end)
        out.npf = TB(function() return e.newPlayerFriendly end) or nil
        out.dur = TN(function() return e.duration end)
        out.note = TB(function() return e.comment ~= "" end) or nil     -- protected text: presence only
    end
    return out
end

local function members(id, n)
    local out = {}
    n = n or 0          -- nil when the game hides the count (lockdown)
    if n > MAX_MEM then return out end
    for i = 1, n do
        local ok, p = CALL(function() return C_LFGList.GetSearchResultPlayerInfo(id, i) end)
        if ok and type(p) == "table" then
            out[#out + 1] = {
                n = TS(function() return p.name end),
                lvl = TN(function() return p.level end),
                cls = TS(function() return p.classFilename end),
                cn = TS(function() return p.className end),        -- the game's own word for the class ("Paladin")
                role = TS(function() return p.assignedRole end),
                lead = TB(function() return p.isLeader end) or nil,
                r = roles(function() return p.lfgRoles end),
            }
        end
    end
    return out
end

local function counts(id)
    local ok, c = CALL(function() return C_LFGList.GetSearchResultMemberCounts(id) end)
    if not ok or type(c) ~= "table" then return nil, nil end
    local function n(k) return TN(function() return c[k] end) end
    return { t = n("TANK_REMAINING"), h = n("HEALER_REMAINING"), d = n("DAMAGER_REMAINING") },
           { t = n("TANK"), h = n("HEALER"), d = n("DAMAGER"), x = n("NOROLE") }   -- x: the game's player count adds it
end

-- My party's assigned roles, as the game's GetGroupMemberCounts() counts them; nil when solo. Engine calls only.
local function partyRoleCounts()
    if TB(function() return IsInGroup() end) ~= true then return nil end
    local c = { TANK = 0, HEALER = 0, DAMAGER = 0, NONE = 0 }
    local raid = TB(function() return IsInRaid() end) == true
    local n = SN(function() return GetNumGroupMembers() end) or 0
    local units = {}
    if raid then
        for i = 1, n do units[#units + 1] = "raid" .. i end
    else
        units[1] = "player"
        for i = 1, math.max(0, n - 1) do units[#units + 1] = "party" .. i end
    end
    for _, u in ipairs(units) do
        local r = TS(function() return UnitGroupRolesAssigned(u) end)
        if r and c[r] then c[r] = c[r] + 1 end
    end
    return c
end

-- THE GAME'S ORDER, copied (LFGBrowseUtil_SortSearchResults, blizzard_lfgvanilla_browse.lua). The game's own
-- function is NOT called: it is Blizzard UI Lua, and the pad owner's rule (AGENT_LOG, after bags' taint
-- flood) keeps addon code out of it. Its rules, in its order:
--   your own listing first; then listings that share an activity with yours; then, when you are solo, groups
--   before players (in a group, players first); players by the first role of tank > healer > damage that
--   one can do and the other cannot (roles your party has already filled move to the end, for a dungeon
--   listing); groups with a free place for one of your roles first, then larger groups, then those that
--   need a tank, then a healer; then the older listing; then the lower id.
local DUNGEON_EXPECTED = { TANK = 1, HEALER = 1, DAMAGER = 3 }     -- LFGBROWSE_DUNGEON_NUM_*_EXPECTED
local function sortResults(list)
    local mine, useDungeonRoles = {}, false
    local okE, entry = CALL(function() return C_LFGList.GetActiveEntryInfo() end)
    if okE and type(entry) == "table" then
        for _, aid in ipairs(numbers(function() return entry.activityIDs end)) do
            mine[aid] = true
            local a = activityInfo(aid)
            if a and TB(function() return a.useDungeonRoleExpectations end) then useDungeonRoles = true end
        end
    end
    local hasMine = next(mine) ~= nil
    local inGroup = TB(function() return IsInGroup() end) == true
    local party = partyRoleCounts()
    local my = roles(function() return C_LFGListRoles.GetRoles() end) or {}
    local order = { "TANK", "HEALER", "DAMAGER" }
    if useDungeonRoles and party then
        for _, r in ipairs({ "TANK", "HEALER", "DAMAGER" }) do
            if party[r] >= DUNGEON_EXPECTED[r] then
                for i, x in ipairs(order) do if x == r then table.remove(order, i) break end end
                order[#order + 1] = r
            end
        end
    end
    -- every value is read once, before sorting (the comparator never calls the game)
    local k = {}
    for _, id in ipairs(list) do
        local ok, r = CALL(function() return C_LFGList.GetSearchResultInfo(id) end)
        local okC, c = CALL(function() return C_LFGList.GetSearchResultMemberCounts(id) end)
        if not ok or type(r) ~= "table" then r = {} end
        if not okC or type(c) ~= "table" then c = {} end
        local function cn(key) return TN(function() return c[key] end) or 0 end
        local function cb(key) return TB(function() return c[key] end) == true end
        local match = false
        if hasMine then
            for _, aid in ipairs(numbers(function() return r.activityIDs end)) do
                if mine[aid] then match = true break end
            end
        end
        local canT = cn("TANK_REMAINING") > 0 and my.t == true
        local canH = cn("HEALER_REMAINING") > 0 and my.h == true
        local canD = cn("DAMAGER_REMAINING") > 0 and my.d == true
        k[id] = {
            self = TB(function() return r.hasSelf end) == true,
            match = match,
            n = TN(function() return r.numMembers end) or 0,
            age = TN(function() return r.age end) or 0,
            lead = { TANK = cb("LEADER_ROLE_TANK"), HEALER = cb("LEADER_ROLE_HEALER"), DAMAGER = cb("LEADER_ROLE_DAMAGER") },
            slot = canT or canH or canD, canT = canT, canH = canH,
        }
    end
    table.sort(list, function(a, b)
        local x, y = k[a], k[b]
        if x.self ~= y.self then return x.self end
        if hasMine and x.match ~= y.match then return x.match end
        local soloX, soloY = x.n == 1, y.n == 1
        if soloX ~= soloY then
            if inGroup then return soloX else return soloY end
        end
        if soloX then
            for _, role in ipairs(order) do
                if x.lead[role] ~= y.lead[role] then return x.lead[role] end
                if x.lead[role] then break end
            end
        else
            if x.slot ~= y.slot then return x.slot end
            if x.n ~= y.n then return x.n > y.n end
            if x.canT ~= y.canT then return x.canT end
            if x.canH ~= y.canH then return x.canH end
        end
        if x.age ~= y.age then return x.age > y.age end
        return a < b
    end)
end

-- the result list, in the game's order
local function orderedResults()
    local okR, total, ids = CALL(function() return C_LFGList.GetFilteredSearchResults() end)
    local list = {}
    if okR and type(ids) == "table" then
        local n = SN(function() return #ids end) or 0
        for i = 1, n do
            local id = SN(function() return ids[i] end)
            if id then list[#list + 1] = id end
        end
    end
    local sorted = pcall(sortResults, list) and "game" or "none"
    total = (type(total) == "number" and WC.isPlain(total)) and total or #list
    return list, total, sorted
end

local function results(canInvite)
    local ids, total, sorted = orderedResults()
    local out, keep = {}, {}
    for k = 1, math.min(#ids, MAX_RES) do
        local id = ids[k]
        local ok, r = CALL(function() return C_LFGList.GetSearchResultInfo(id) end)
        if ok and type(r) == "table" then
            local n = TN(function() return r.numMembers end)
            local mem = members(id, n)
            local need, have = counts(id)
            local leaderName = TS(function() return r.leaderName end)
            if kstring(leaderName) then leaderName = nil end
            local e = {
                id = id,
                leader = leaderName,
                n = n,
                acts = numbers(function() return r.activityIDs end),
                self = TB(function() return r.hasSelf end) or nil,
                del = TB(function() return r.isDelisted end) or nil,
                npf = TB(function() return r.newPlayerFriendly end) or nil,
                note = TB(function() return r.comment ~= "" end) or nil,  -- protected text: presence only
                fr = ((TN(function() return r.numCharFriends end) or 0)
                      + (TN(function() return r.numBNetFriends end) or 0)),
                gm = TN(function() return r.numGuildMates end),
                need = need, have = have,
                mem = (#mem > 0) and mem or nil,
            }
            if e.fr == 0 then e.fr = nil end
            if e.gm == 0 then e.gm = nil end
            -- the leader's class and level (a solo player is the leader)
            for _, m in ipairs(mem) do
                if m.lead or n == 1 then e.cls, e.cn, e.lvl = m.cls, m.cn, m.lvl break end
            end
            if not e.cls then
                local okL, L = CALL(function() return C_LFGList.GetSearchResultLeaderInfo(id) end)
                if okL and type(L) == "table" then
                    e.cls = TS(function() return L.classFilename end)
                    e.cn = TS(function() return L.className end)
                    e.lvl = TN(function() return L.level end)
                end
            end
            if n == 1 and mem[1] then e.r = mem[1].r end          -- a solo player's roles
            -- the game's invite rule (LFGBrowseUtil_GetInviteActionForResult): a solo listing, and
            -- the player not in a group or its leader / assistant
            if n == 1 and canInvite and not e.self and not e.del and leaderName then
                e.inv = true
                e.slot = WC.pad and WC.pad.slotFor("gf", "i" .. leaderName) or nil
                keep["i" .. leaderName] = true
            end
            out[#out + 1] = e
        end
    end
    return out, total, sorted, keep
end

local function windowShown()
    local fr = _G.LFGParentFrame
    if type(fr) ~= "table" then return nil end
    return TB(function() return fr:IsShown() end) or false
end

-- WHICH TAB of the game's window shows: "listing", "browse" or "who" (read from the tab frames' own IsShown; nothing written).
-- The app needs it because the Who list lives in this window: a Who search from the Social room makes the game open it on its
-- Who tab, and that stays Social's (user 2026-09-30: "Stay in Social, keep the game's window hidden")
local TAB_FRAMES = { { "LFGWhoListFrame", "who" }, { "LFGBrowseFrame", "browse" }, { "LFGListingFrame", "listing" } }
local function windowTab()
    if windowShown() ~= true then return nil end
    for _, t in ipairs(TAB_FRAMES) do
        local fr = _G[t[1]]
        if type(fr) == "table" and TB(function() return fr:IsShown() end) == true then return t[2] end
    end
end

-- the category of a search this addon did not make (the game's own window searched)
local function categoryOfResults(list)
    for _, e in ipairs(list) do
        local aid = e.acts and e.acts[1]
        if aid then
            local a = activityInfo(aid)
            if a then return TN(function() return a.categoryID end) end
        end
    end
end

-- ---------------------------------------------------------------- Who: the filter's options and the results (Social's code)
local WHO_KEYS = { "ws", "wl", "wz", "wca", "wcu", "wra", "wru" }
-- THE CLASSES THIS CLIENT KNOWS. GetClassInfo takes a CLASS ID here, while GetNumClasses returns only how many there are (9 on
-- Forever): the game's own Who filter (wholist.lua, `for index = 1, GetNumClasses()`) therefore stops at id 9 and never lists the
-- Druid (id 11). MEASURED on the Thor 2026-09-30 (g6): ids 1 2 3 4 5 7 8 9 came back, a Druid stood in the results. So every id up
-- to MAX_CLASS_ID is asked, and the ones the client does not know (it answers nothing, as for id 6) are skipped.
local MAX_CLASS_ID = 20
local classList = nil
local function knownClasses()
    if classList then return classList end
    local out = {}
    for id = 1, MAX_CLASS_ID do
        local ok, name, file, cid = CALL(function() return GetClassInfo(id) end)
        if ok and type(name) == "string" and WC.isPlain(name) and type(cid) == "number" and WC.isPlain(cid) then
            out[#out + 1] = { id = cid, n = name, cls = (type(file) == "string" and WC.isPlain(file)) and file or nil }
        end
    end
    if #out > 0 then classList = out end         -- cached once the client answers
    return out
end

local function classFileOf(localized)
    if type(localized) ~= "string" then return nil end
    for _, e in ipairs(knownClasses()) do if e.n == localized then return e.cls end end
    return nil
end

local function whoOptions()
    if whoOpts then return whoOpts end
    local o = { classes = {}, races = {} }
    for _, e in ipairs(knownClasses()) do o.classes[#o.classes + 1] = e end
    local okR, races = CALL(function() return C_FriendList.GetWhoRaceFilters() end)
    if okR and type(races) == "table" then
        for _, r in ipairs(races) do
            local name = TS(function() return r.name end)
            local id = TN(function() return r.ID end)
            if name and id then o.races[#o.races + 1] = { id = id, n = name } end
        end
    end
    -- the fixed keys of row gfw, claimed FIRST and in this order (the pad owner's rule). Cached only once the game has given BOTH
    -- lists: at load the classes answer before the race list is filled (MEASURED on the Thor, g6b 17:25: nine classes, no races),
    -- and a cache taken then kept the race filter empty for the session. A client that never gives races is cached after 30 s.
    local now = GetTime()
    whoOptsGiveUp = whoOptsGiveUp or (now + 30)
    if #o.classes > 0 and (#o.races > 0 or now >= whoOptsGiveUp) then
        whoOpts = o
        if WC.pad then
            for _, k in ipairs(WHO_KEYS) do WC.pad.slotFor("gfw", k) end
            for _, e in ipairs(o.classes) do WC.pad.slotFor("gfw", "wc" .. e.id) end
            for _, e in ipairs(o.races) do WC.pad.slotFor("gfw", "wr" .. e.id) end
        end
    end
    return o
end

local function whoFixedKeys()
    local keys = {}
    for _, k in ipairs(WHO_KEYS) do keys[#keys + 1] = k end
    local o = whoOpts
    if o then
        for _, e in ipairs(o.classes) do keys[#keys + 1] = "wc" .. e.id end
        for _, e in ipairs(o.races) do keys[#keys + 1] = "wr" .. e.id end
    end
    return keys
end

local function whoDoc()
    local o = whoOptions()
    local slot = function(k) return WC.pad and WC.pad.slotFor("gfw", k) or nil end
    local keys = nil
    if WC.pad then keys = {}; for _, k in ipairs(WHO_KEYS) do keys[k] = slot(k) end end
    -- what "My level" and "My zone" would ask now, for the drop-down's words before any search
    local lvl = SN(function() return UnitLevel("player") end)
    local f = { lvl = filt.lvl or nil, zone = filt.zone or nil, classes = {}, races = {}, keys = keys,
                lr = lvl and (math.max(1, lvl - FILTER_SPAN) .. "-" .. (lvl + FILTER_SPAN)) or nil,
                zn = TS(function() return GetZoneText() end) }
    for _, e in ipairs(o.classes) do
        f.classes[#f.classes + 1] = { id = e.id, n = e.n, cls = e.cls, on = (not filt.classes[e.id]) or nil,
                                      slot = whoOpts and slot("wc" .. e.id) or nil }
    end
    for _, e in ipairs(o.races) do
        f.races[#f.races + 1] = { id = e.id, n = e.n, on = (not filt.races[e.id]) or nil,
                                  slot = whoOpts and slot("wr" .. e.id) or nil }
    end
    local out = { f = f, asked = whoAsked, at = whoAt }     -- at: when the last answer came (a new one = a new stamp, same list or not)
    if not whoAt then out.none = true return out, {} end
    local ok, num, total = CALL(function() return C_FriendList.GetNumWhoResults() end)
    num = (ok and type(num) == "number" and WC.isPlain(num)) and num or 0
    out.n = num
    out.total = (ok and type(total) == "number" and WC.isPlain(total)) and total or nil
    out.list = {}
    local keep = {}
    for i = 1, math.min(num, MAX_WHO) do
        local okW, w = CALL(function() return C_FriendList.GetWhoInfo(i) end)
        if okW and type(w) == "table" then
            local r = {
                n = TS(function() return w.fullName end),
                guild = TS(function() return w.fullGuildName end),
                lvl = TN(function() return w.level end),
                race = TS(function() return w.raceStr end),
                cls = TS(function() return w.filename end) or classFileOf(TS(function() return w.classStr end)),
                cn = TS(function() return w.classStr end),
                zone = TS(function() return w.area end),
            }
            if r.guild == "" then r.guild = nil end
            if r.n then
                -- an invite column while one is free (the fixed keys hold theirs); none = not invitable from the room
                r.slot = (WC.pad and whoOpts) and WC.pad.slotFor("gfw", "wi" .. r.n) or nil
                if r.slot then keep["wi" .. r.n] = true end
                out.list[#out.list + 1] = r
            end
        end
    end
    return out, keep
end

local function npfDefault()
    return TB(function() return GetCVarBool("lfgNewPlayerFriendly") end) or false
end

local function draftNpf()
    if draft.npf ~= nil then return draft.npf end
    local ok, e = CALL(function() return C_LFGList.GetActiveEntryInfo() end)
    if ok and type(e) == "table" then
        local v = TB(function() return e.newPlayerFriendly end)
        if v ~= nil then return v end
    end
    return npfDefault()
end

local function maxActs()
    return SN(function() return Constants.LFGConstsExposed.GROUP_FINDER_MAX_ACTIVITY_CAPACITY end)
end

-- whether the game's description box holds typed text: its text is never read into the wire, only "empty or not"
local function descTyped()
    local fr = _G.LFGListingComment
    if type(fr) ~= "table" or type(fr.EditBox) ~= "table" then return nil end
    return TB(function() return fr.EditBox:GetText() ~= "" end)
end

local function isTickable(cid, id)
    for _, x in ipairs(tickable[cid] or {}) do if x == id then return true end end
    return false
end

-- the category of my listing's FIRST activity, as the game reads it (SetSelectedSearchActivities)
local function listingCategory()
    for _, aid in ipairs(numbers(function()
        local e = C_LFGList.GetActiveEntryInfo()
        return e and e.activityIDs
    end)) do
        local a = activityInfo(aid)
        local c = a and TN(function() return a.categoryID end)
        if c then return c end
    end
end

local function draftDoc()
    if draft.cat == nil and S(function() return C_LFGList.HasActiveEntryInfo() end) == true then
        local lc = listingCategory()
        if lc then
            draft.cat, draft.acts, draft.dirty = lc, {}, false
            for id in pairs(listedActivities()) do draft.acts[id] = true end
        end
    end
    local ticks = {}
    for _, id in ipairs(tickable[draft.cat] or {}) do
        ticks[#ticks + 1] = { id = id, slot = WC.pad and WC.pad.slotFor("gfl", "t" .. id) or nil }
    end
    -- one tick for a whole activity group (the game's group line): its own column, so one touch is one tap
    local gticks = {}
    for gid in pairs(tickGroups[draft.cat] or {}) do
        gticks[#gticks + 1] = { g = gid, slot = WC.pad and WC.pad.slotFor("gfl", "g" .. gid) or nil }
    end
    table.sort(gticks, function(a, b) return a.g < b.g end)
    local acts = {}
    for id, on in pairs(draft.acts) do if on then acts[#acts + 1] = id end end
    table.sort(acts)
    local lk
    if WC.pad then
        lk = {}
        for _, k in ipairs(LFIXED) do lk[k] = WC.pad.slotFor("gfl", k) end
    end
    return {
        cat = draft.cat, acts = acts, npf = draftNpf(), dirty = draft.dirty or nil, why = draft.why,
        posting = posting and true or nil, ticks = ticks, gticks = (#gticks > 0) and gticks or nil, keys = lk,
        max = maxActs(), npfDef = npfDefault(), desc = descTyped(),
    }
end

function WC.collectGf()
    tally = { s = 0, t = 0 }
    local inGrp, lead, asst, canInvite = inGroupStanding()
    local okU, canUse, why = CALL(function() return C_LFGInfo.CanPlayerUsePremadeGroup() end)
    local can = nil     -- written out: `x and false or nil` would turn a real false into nil
    if okU and type(canUse) == "boolean" and WC.isPlain(canUse) then can = canUse end
    local res, total, sorted, keep = results(canInvite)
    -- free the invite columns of results that left; the search columns always stay
    if WC.pad then
        for _, k in ipairs(FIXED_KEYS) do keep[k] = true end
        for _, cid in ipairs(catIDs) do keep["s" .. cid] = true end
        WC.pad.retain("gf", keep)
        -- the second row: its ten fixed keys always; the ticks of EVERY category while they fit, else the draft's only
        local lkeep = {}
        for _, k in ipairs(LFIXED) do lkeep[k] = true end
        if #tickUnion <= LTICK_MAX then
            for _, k in ipairs(tickUnion) do lkeep[k] = true end
        else
            for _, id in ipairs(tickable[draft.cat] or {}) do lkeep["t" .. id] = true end
            for gid in pairs(tickGroups[draft.cat] or {}) do lkeep["g" .. gid] = true end
        end
        WC.pad.retain("gfl", lkeep)
    end
    -- results already there at load (a search made before a /reload): name their category from them
    local cat = search.cat
    if cat == nil and #res > 0 then cat = categoryOfResults(res) end
    local who, wkeep = whoDoc()
    if WC.pad then
        for _, k in ipairs(whoFixedKeys()) do wkeep[k] = true end
        WC.pad.retain("gfw", wkeep)
    end
    return {
        pad = WC.pad and WC.pad.describe() or nil,
        v = GV,
        on = TB(function() return C_LFGInfo.IsGroupFinderEnabled() end),
        prem = TB(function() return C_LFGList.IsPremadeGroupFinderEnabled() end),
        style = TN(function() return C_LFGList.GetPremadeGroupFinderStyle() end),
        can = can,
        cannot = (okU and canUse == false and type(why) == "string" and WC.isPlain(why) and why ~= "") and why or nil,
        lock = TB(function() return C_ChatInfo.InChatMessagingLockdown() end) or nil,
        cb = TB(function() return UnitAffectingCombat("player") end) or nil,
        grp = inGrp or nil, lead = lead or nil, asst = asst or nil,
        gsize = inGrp and SN(function() return GetNumGroupMembers() end) or nil,
        inv = canInvite,
        roles = roles(function() return C_LFGListRoles.GetRoles() end),
        win = windowShown(),
        wtab = windowTab(),
        me = listing(inGrp, lead),
        dslot = WC.pad and WC.pad.slotFor("gf", "d") or nil,
        keys = WC.pad and { d = WC.pad.slotFor("gf", "d"), sm = WC.pad.slotFor("gf", "sm"),
                            h = WC.pad.slotFor("gf", "h"), f = WC.pad.slotFor("gf", "f"),
                            fb = WC.pad.slotFor("gf", "fb"), bh = WC.pad.slotFor("gf", "bh"),
                            bb = WC.pad.slotFor("gf", "bb") } or nil,
        ctl = { held = (ctlr.held and not ctlr.wait) or nil, why = ctlr.why, cap = capture() ~= nil,
                wait = ctlr.wait,                               -- capture v11: "queued" (behind a holder) / "pending" (a fight's enter)
                tn = ctlr.tn,                                   -- real holds so far: the app opens the window only after a new one
                back = (ctlr.held and ctlr.back) or nil,        -- B is the game's own close (then Home); off while a menu of the room is open
                lr = (ctlr.held and ctlr.lr) or nil },          -- LB / RB come to the room (capture v4)
        draft = draftDoc(),
        who = who,
        cslot = (WC.pad and closeReady) and 0 or nil,       -- the secure close cell (row gfclose, column 0) is set up
        oslot = (WC.pad and closeReady and openArmed) and 1 or nil,   -- the secure OPEN cell (column 1), armed while the window is shut
        search = {
            st = search.st, cat = cat, why = search.why, mine = search.mine or nil,
            total = total, n = #res, sorted = sorted, res = res,
        },
        sec = secTally(),
    }
end

-- ---------------------------------------------------------------- emission

local lastCat, lastGf = nil, nil

local function emitCatalogue(force)
    local doc = WC.collectGfCatalogue()
    local key = WC.encode(doc)
    if key == lastCat and not force then return false end
    lastCat = key
    WC.send(WC.encode({ type = "gfcat", c = doc }))
    return true
end

-- The age of each listing changes every second, so it is kept out of the change test (as Quests keeps
-- distance out): it travels with a document that is sent anyway.
local function emitState(force)
    local doc = WC.collectGf()
    local ages = {}
    for _, e in ipairs(doc.search.res) do
        ages[e.id] = SN(function() return C_LFGList.GetSearchResultInfo(e.id).age end)
    end
    local key = WC.encode(doc)
    if key == lastGf and not force then return false end
    lastGf = key
    for _, e in ipairs(doc.search.res) do e.age = ages[e.id] end
    WC.send(WC.encode({ type = "gf", g = doc }))
    return true
end

-- Events come in bursts. `push` = whether the player is waiting to see this.
local queued, queuedPush, catDirty = false, false, false
local function soon(push, cat)
    if push then queuedPush = true end
    if cat then catDirty = true end
    if queued then return end
    queued = true
    C_Timer.After(0.25, function()
        queued = false
        local p, c = queuedPush, catDirty
        queuedPush, catDirty = false, false
        local wrote = false
        if c and emitCatalogue(false) then wrote = true end
        if emitState(false) then wrote = true end
        if wrote and p then WC.push() end
    end)
end

-- ---------------------------------------------------------------- the window (read-only)
-- LFGParentFrame is load-on-demand: hook its show / hide once it exists, only to say whether the game's
-- own window is open. Nothing here hides, moves or changes it.
local backFor       -- B's meaning: the game's close, or the room's while its menu is open (defined with the capture guest, below)
local releaseRefused -- a release the capture refused in a fight (defined with the capture guest, below)
local rearmOpenRef  -- the secure open cell armed again at every show / hide of the window (defined with the secure row, below)
local hooked = false
local function hookWindow()
    if hooked then return end
    local fr = _G.LFGParentFrame
    if type(fr) ~= "table" or type(fr.HookScript) ~= "function" then return end
    hooked = true
    pcall(function()
        fr:HookScript("OnShow", function() if rearmOpenRef then rearmOpenRef() end soon(true) end)
        fr:HookScript("OnHide", function() if rearmOpenRef then rearmOpenRef() end soon(true) end)
    end)
    -- a tab switch inside the open window shows / hides a tab frame without the window's own OnShow: watched the same way
    for _, t in ipairs(TAB_FRAMES) do
        local tf = _G[t[1]]
        if type(tf) == "table" and type(tf.HookScript) == "function" then
            pcall(function()
                tf:HookScript("OnShow", function() soon(true) end)
                tf:HookScript("OnHide", function() soon(true) end)
            end)
        end
    end
end

-- ---------------------------------------------------------------- events

local retake        -- the controller's re-take (defined with the capture guest, below); used by the handlers here
local setupClose    -- the secure close cell's setup (defined with the Who taps, below); re-run after ADDON_LOADED

local f = CreateFrame("Frame")
local CAT_EVENTS = {
    PLAYER_ENTERING_WORLD = true, LFG_LIST_AVAILABILITY_UPDATE = true, PLAYER_LEVEL_UP = true, UPDATE_INSTANCE_INFO = true,
    LFG_ENABLED_STATE_CHANGED = true, LFG_LIST_ACTIVE_ENTRY_UPDATE = true,
}
f:SetScript("OnEvent", function(_, event, ...)
    if event == "ADDON_LOADED" then
        hookWindow()
        if setupClose then setupClose() end
        return
    elseif event == "CVAR_UPDATE" then
        if ... ~= "disableSuggestedLevelActivityFilter" then return end
        return soon(true, true)
    elseif event == "LFG_LIST_SEARCH_RESULTS_RECEIVED" then
        -- ours if we were waiting for one; otherwise the game's own window searched, and the category is
        -- read from the results (collectGf)
        if search.st ~= "searching" or not search.mine then search.mine, search.cat = false, nil end
        search.st, search.why = "done", nil
        return soon(true)
    elseif event == "LFG_LIST_SEARCH_FAILED" then
        local reason = ...
        if search.st ~= "searching" or not search.mine then search.mine, search.cat = false, nil end
        search.st = "failed"
        search.why = (type(reason) == "string" and WC.isPlain(reason) and reason ~= "") and reason or nil
        return soon(true)
    elseif event == "LFG_LIST_SEARCH_RESULT_UPDATED" then
        return soon(false)          -- rides the next flush; costs none of its own
    elseif event == "LFG_LIST_ACTIVE_ENTRY_UPDATE" then
        -- the game's answer to a post, or a listing that changed or ended (its own handler): listed, the draft is the listing
        -- and clean; the listing gone, the ticks stay and count as a change if there are any (SetDirty(IsAnyActivitySelected))
        posting = nil
        draft.why = nil
        if S(function() return C_LFGList.HasActiveEntryInfo() end) == true then
            draft.dirty = false
        else
            draft.dirty = next(draft.acts) ~= nil
        end
        return soon(true, true)
    elseif event == "WHO_LIST_UPDATE" then
        whoAt = GetTime()
        return soon(true)
    elseif event == "ZONE_CHANGED_NEW_AREA" then
        return soon(false)
    elseif event == "LFG_LIST_ENTRY_CREATION_FAILED" then
        posting = nil
        draft.why = "the game did not create the listing"
        return soon(true)
    end
    if event == "PLAYER_ENTERING_WORLD" then hookWindow() end
    -- the fight is over: take the controller again if it was lost, and (capture v8 keeps it through the fight, but a take is refused
    -- in combat) bring B's meaning in line with the game's window if that opened or closed during the fight
    if event == "PLAYER_REGEN_ENABLED" then retake(); if backFor then backFor() end end
    soon(true, CAT_EVENTS[event])
end)
local WANT = {
    "PLAYER_ENTERING_WORLD", "ADDON_LOADED", "CVAR_UPDATE", "PLAYER_LEVEL_UP",
    "LFG_ENABLED_STATE_CHANGED", "LFG_LIST_AVAILABILITY_UPDATE",
    "LFG_LIST_SEARCH_RESULTS_RECEIVED", "LFG_LIST_SEARCH_RESULT_UPDATED", "LFG_LIST_SEARCH_FAILED",
    "LFG_LIST_ACTIVE_ENTRY_UPDATE", "LFG_LIST_ROLE_UPDATE", "LFG_LIST_ENTRY_CREATION_FAILED", "UPDATE_INSTANCE_INFO", "WHO_LIST_UPDATE", "ZONE_CHANGED_NEW_AREA",
    "GROUP_ROSTER_UPDATE", "PARTY_LEADER_CHANGED",
    -- combat gates the search button
    "PLAYER_REGEN_DISABLED", "PLAYER_REGEN_ENABLED",
}
local missing = {}
for _, e in ipairs(WANT) do
    local ok, registered = pcall(function() return f:RegisterEvent(e) end)
    if not ok or registered == false then missing[#missing + 1] = e end
end
if #missing > 0 then
    pcall(function() WC.send(WC.encode({ type = "cap", groupfinder = GV, missingEvents = missing })) end)
end

-- RIDE THE CORE HEARTBEAT (as Quests does): re-sending both documents every 15 s costs no flush of its
-- own, and is how the app gets them back after the log rotates.
local RIDE = { heartbeat = true, login = true, zone = true, manual = true }
if type(WC.snapshot) == "function" then
    hooksecurefunc(WC, "snapshot", function(reason)
        if RIDE[reason] then emitCatalogue(true); emitState(true) end
        -- after an NPC window let the capture go, the room asks for it again (only while it still wants it)
        if retake then retake() end
    end)
end

-- ---------------------------------------------------------------- the search's timeout
-- (There was a secure post-hook on C_LFGList.Search here, to see the game's own window search too. It is gone:
-- after bags' taint flood the rule is to touch as little of the game's code as possible, and a search made in
-- the game's window still shows up, as results whose category is read from them.)
local function armTimeout(t0)
    C_Timer.After(SEARCH_TIMEOUT, function()
        if search.st == "searching" and search.t0 == t0 then
            search.st = "timeout"
            soon(true)
        end
    end)
end

-- ---------------------------------------------------------------- the taps

local function ack(op, key, ok, why, extra)
    local out = { type = "gfack", op = op, key = key, ok = ok and true or false, why = why, v = GV }
    if extra then for k, v in pairs(extra) do out[k] = v end end
    WC.send(WC.encode(out))
    soon(true)
    WC.push()
end

-- ---------------------------------------------------------------- the controller (a guest of the shared capture)
-- While the room is on the bottom screen the controller belongs to it (UI Art's SPEC 6): the app taps `h` when the room
-- shows and `f` when it is left. The capture (interact's) takes the eight buttons (D-pad on the press, A / B / X / Y on the
-- release) and gives them to us; the app owns the glow, so every press is relayed as {"type":"gfpad"}. The capture is never
-- held in combat; after a fight or an NPC window it is asked for again, only while the room still wants it (as Social does).
-- EVERY ROOM ALIKE (the user, 2026-10-01, via Home: "Every room opens the game's own window behind it, as Professions does"; "it
-- should act the EXACT same for all rooms and be no different"): however the room is opened (touch, the bar, the game's own key), the
-- game's window is open behind it (the app taps the secure OPEN cell, gfclose column 1, when it opens the room by touch), and ONE B
-- is the game's own close button pressed through the capture's binding (spec.back, capture v2+), then the room lets go with "B" and the
-- app shows Home. The capture tells us on B's release (spec.passed "PAD2"), so the release is ours, never a guess from a hide. Only
-- while one of the room's own menus is open is B the room's (the app's "bb"), so B closes the menu; "bh" gives B back to the game.
-- capture v9: spec.leave = the same close button (Interact's generic and fight leave click it); spec.window = true on a take made
-- while the game's window is open (it pre-empts a room that still holds). LB / RB (capture v4 `extra`) switch the room's tabs.
local CLOSE_BUTTON = "LFGParentFrameCloseButton"
local OPEN_BUTTON = "LFDMicroButton"    -- the game's own Group Finder micro button (its OnClick is the game's toggle, in its own code)
local LR = { "PADLSHOULDER", "PADRSHOULDER" }
local CAP_SPEC = {
    hide = { "LFGParentFrame" },
    press = function(k)
        ctlr.n = ctlr.n + 1
        WC.send(WC.encode({ type = "gfpad", v = GV, k = (type(k) == "string" and WC.isPlain(k)) and k or nil, n = ctlr.n }))
        WC.push()
    end,
    lost = function(why)
        ctlr.held, ctlr.back, ctlr.lr, ctlr.roomB, ctlr.wait = false, false, false, false, nil
        ctlr.why = (type(why) == "string" and WC.isPlain(why)) and why or nil
        -- g10 (Home as integrator, the user's go 2026-10-06; Social's LEFT rule): lost for "left" / "B" / "taken" = the room was LEFT
        -- (Home's generic leave, a fight's B, another room's window): it is not asked for again by itself. b87 22:33:44-58: after the
        -- app's generic leave freed it, the heartbeat's retake held the controller again on Home (the disc kept showing B)
        if why == "left" or why == "B" or why == "taken" then ctlr.want = false end
        WC.send(WC.encode({ type = "gfpad", v = GV, lost = ctlr.why or "lost", n = ctlr.n }))
        soon(true)
    end,
}

local function windowShown()
    local fr = _G.LFGParentFrame
    return type(fr) == "table" and S(function() return fr:IsShown() end) == true
end

-- B, the game's (its close button pressed through the capture's binding), on every route; the room's only while its menu is open
local function wantBack(c)
    return (c.version or 0) >= 2 and type(_G[CLOSE_BUTTON]) == "table" and not ctlr.roomB
end

-- B was pressed and left to the game (its close button): the capture tells us on the release. The room lets go with "B" (the capture
-- and the app then show Home), after the capture's own handler is done
local function onPassed(k)
    if k ~= "PAD2" then return end
    C_Timer.After(0, function()
        if not ctlr.held or not ctlr.back then return end
        ctlr.want = false
        local c = capture()
        if c then CALL(c.release, GUEST, "B") end
        ctlr.held, ctlr.back, ctlr.lr, ctlr.roomB, ctlr.wait = false, false, false, false, nil
        ctlr.why = "B"
        WC.send(WC.encode({ type = "gfpad", v = GV, lost = "B", n = ctlr.n }))
        soon(true)
        WC.push()
    end)
end

-- the spec for now: a fresh table each time (the capture keeps its own copy)
local function specNow(c)
    local v = c.version or 0
    local spec = { hide = CAP_SPEC.hide, press = CAP_SPEC.press, lost = CAP_SPEC.lost, passed = onPassed }
    if wantBack(c) then spec.back = CLOSE_BUTTON end
    if v >= 9 and type(_G[CLOSE_BUTTON]) == "table" then spec.leave = CLOSE_BUTTON end
    if v >= 9 and windowShown() then spec.window = true end
    if v >= 4 then spec.extra = LR end
    return spec
end

-- capture v11 (Interact i82b, "THE ONE-LINE CONTRACT"): take = true (held now) / true, "queued" (behind another holder) / true,
-- "pending" (in a fight, until Home's enter tap) / false, why (refused). A wait becomes a real hold with no call to us: it is watched
-- every half second (bounded) through the capture's own state() until it names us, and then said at once.
local WAIT_LIMIT = 120          -- half-second checks: one minute
local waitWatch = 0
local function watchWait()
    waitWatch = waitWatch + 1
    local my = waitWatch
    local n = 0
    local function tick()
        if my ~= waitWatch or not ctlr.wait or not ctlr.held then return end
        n = n + 1
        local c = capture()
        local okS, st = CALL(function() return c and c.state() end)
        if okS and type(st) == "table" and st.holder == GUEST then
            ctlr.wait = nil
            ctlr.tn = ctlr.tn + 1
            soon(true)
            return
        end
        if n < WAIT_LIMIT then C_Timer.After(0.5, tick) end
    end
    C_Timer.After(0.5, tick)
end

local function takePad()
    local c = capture()
    if not c then ctlr.held, ctlr.why = false, "no controller capture" return false end
    local spec = specNow(c)
    local ok, why = c.take(GUEST, spec)
    ctlr.held = ok and true or false
    local wasReal = false
    if ok and (why == "queued" or why == "pending") then
        ctlr.wait = why
        watchWait()
    else
        wasReal = ok and true or false
        ctlr.wait = nil
    end
    if wasReal then ctlr.tn = ctlr.tn + 1 end
    ctlr.why = (not ok and type(why) == "string" and WC.isPlain(why)) and why or nil
    ctlr.back = ctlr.held and spec.back ~= nil
    ctlr.lr = ctlr.held and spec.extra ~= nil
    return ctlr.held
end

-- B's meaning changed (a menu of the room opened or closed; the game's close button came into being): take again (the holder may).
-- No longer on the window's show / hide: B is the game's close on every route (the take-again without back is gone, Home 2026-10-01)
backFor = function()
    if not ctlr.held or InCombatLockdown() then return end
    local c = capture(); if not c then return end
    if wantBack(c) == ctlr.back then return end
    takePad()
end

-- capture i81: in a fight a release is refused ("tap leave": Interact's leave lets the room go). Then the room still holds: its flags
-- stay, nothing is retried, and the capture's own lost() clears them when Interact's leave runs. True when refused
function releaseRefused(c, why)
    local okR, rel, reason = CALL(c.release, GUEST, why)
    if okR and rel == false then
        ctlr.why = (type(reason) == "string" and WC.isPlain(reason)) and reason or ctlr.why
        return true
    end
    return false
end

-- let go of the shared capture: also when our own flag says we do not hold it but the capture still names us (Home's self-heal,
-- one rule for every room 2026-09-30: no room may keep the controller after it is left). A release by a non-holder is refused by
-- the capture itself, so this can never take the controller from anyone else. No game action.
local function freePad()
    local c = capture()
    if c then
        local mine = ctlr.held
        if not mine and type(c.state) == "function" then
            local okS, st = CALL(c.state)
            mine = okS and type(st) == "table" and st.holder == GUEST
        end
        if mine and releaseRefused(c, nil) then return end
    end
    ctlr.held, ctlr.back, ctlr.lr, ctlr.roomB, ctlr.wait = false, false, false, false, nil
end

-- IN A FIGHT (the user's combat spec, SPEC_COMBAT 4, signed off 2026-10-01: "everything the game itself allows in combat"): the room
-- refuses nothing because of the fight. The game's own LFG code has no combat check, and Search / CreateListing / UpdateListing /
-- SendWho only want a real press. The one limit is the game's: in an instance fight the chat-messaging lockdown makes the results
-- secret (the doc says `lock` and the room shows its words).

-- asked for again by itself only when nobody holds the capture (the capture's own rule for a guest)
retake = function()
    if not ctlr.want or ctlr.held or InCombatLockdown() then return end
    local c = capture(); if not c then return end
    local okS, st = CALL(c.state)
    if okS and type(st) == "table" and st.holder ~= nil then return end
    if takePad() then soon(true) end
end

local OPS = {}

function OPS.search(cid, key)
    local known = false
    for _, c in ipairs(catIDs) do if c == cid then known = true break end end
    if not known then return ack("search", key, false, "not a category the game offers now") end
    local now = GetTime()
    if search.st == "searching" and search.t0 and now - search.t0 < 3 then
        return ack("search", key, false, "already searching")
    end
    -- the activities the game's own list shows for this category (LFGBrowse_DoSearch sends exactly these
    -- when no activity is picked); the app narrows by activity itself, on the results
    local lvl = SN(function() return UnitLevel("player") end)
    local noFilter = TB(function() return GetCVarBool("disableSuggestedLevelActivityFilter") end) or false
    local acts = {}
    for _, a in ipairs(filteredActivities(cid, lvl, noFilter, listedActivities())) do acts[#acts + 1] = a.aid end
    local ok = CALL(function() C_LFGList.Search(cid, 0, 0, nil, false, nil, acts) end)
    search.cat, search.t0, search.why, search.mine = cid, now, nil, true
    search.st = ok and "searching" or "error"
    if ok then armTimeout(now) end
    ack("search", key, ok, (not ok) and "the game refused the search" or nil, { cat = cid, nacts = #acts })
end

-- "Your Listing": the game's SearchActiveEntry (the category of my first listed activity, my activities in it)
function OPS.mine(_, key)
    local listed = numbers(function()
        local e = C_LFGList.GetActiveEntryInfo()
        return e and e.activityIDs
    end)
    if #listed == 0 then return ack("search", key, false, "not listed") end
    local cid, acts = nil, {}
    for _, aid in ipairs(listed) do
        local a = activityInfo(aid)
        local c = a and TN(function() return a.categoryID end)
        if c then
            if not cid then cid = c end
            if c == cid then acts[#acts + 1] = aid end
        end
    end
    if not cid then return ack("search", key, false, "my listing's category is unknown") end
    local now = GetTime()
    local ok = CALL(function() C_LFGList.Search(cid, 0, 0, nil, false, nil, acts) end)
    search.cat, search.t0, search.why, search.mine = cid, now, nil, true
    search.st = ok and "searching" or "error"
    if ok then armTimeout(now) end
    ack("search", key, ok, (not ok) and "the game refused the search" or nil, { cat = cid, nacts = #acts, acts = acts })
end

-- hold / free: no game action and no answer awaited (the state rides the next document)
function OPS.hold()
    ctlr.want = true
    if not ctlr.held then takePad() end
    soon(true)
end
function OPS.free()
    ctlr.want = false
    freePad()
    soon(true)
end
-- the player left the room with B: the capture is told why, so the app shows Home (interact's contract, version 2 and up)
function OPS.freeB()
    ctlr.want = false
    local c = capture()
    if c and ctlr.held and releaseRefused(c, "B") then return soon(true) end
    ctlr.held, ctlr.back, ctlr.lr, ctlr.roomB, ctlr.wait = false, false, false, false, nil
    soon(true)
end
-- where the next B leads: "home" = the game's close and Home (B is the game's), "back" = one of the room's menus is open (B is the
-- room's, it closes the menu). Told to a capture v3 (bnext) and, from g7, B's binding follows it (a take again with / without back)
local function bnext(where)
    local c = capture()
    if c and ctlr.held and type(c.bnext) == "function" then CALL(c.bnext, GUEST, where) end
end
function OPS.bnextHome() ctlr.roomB = false; bnext("home"); backFor(); soon(true) end
function OPS.bnextBack() ctlr.roomB = true; bnext("back"); backFor(); soon(true) end

-- the current solo listing led by [who], or nil (the key names a player; the listing must still be there)
local function soloListingOf(who)
    local okR, _, ids = CALL(function() return C_LFGList.GetFilteredSearchResults() end)
    if not okR or type(ids) ~= "table" then return nil end
    local n = SN(function() return #ids end) or 0
    for i = 1, n do
        local id = SN(function() return ids[i] end)
        if id then
            -- not `id and CALL(...)`: an `and` keeps only CALL's first return, the `ok`
            local ok, r = CALL(function() return C_LFGList.GetSearchResultInfo(id) end)
            if ok and type(r) == "table" and S(function() return r.leaderName end) == who then return r end
        end
    end
end

function OPS.invite(who, key)
    if type(who) ~= "string" or who == "" then return ack("invite", key, false, "no name in the key") end
    local r = soloListingOf(who)
    if not r then return ack("invite", key, false, "the listing is gone") end
    local n = SN(function() return r.numMembers end)
    local name = who
    if n ~= 1 then return ack("invite", key, false, "not a solo player") end
    if S(function() return r.hasSelf end) == true then return ack("invite", key, false, "that is you") end
    if S(function() return r.isDelisted end) == true then return ack("invite", key, false, "delisted") end
    if kstring(name) then return ack("invite", key, false, "the game hides the name") end
    local _, _, _, canInvite = inGroupStanding()
    if not canInvite then return ack("invite", key, false, "only the leader or an assistant can invite") end
    local okI = CALL(function() C_PartyInfo.InviteUnit(name) end)
    ack("invite", key, okI, (not okI) and "the game refused the invite" or nil, { name = name })
end

function OPS.delist(_, key)
    if S(function() return C_LFGList.HasActiveEntryInfo() end) ~= true then
        return ack("delist", key, false, "not listed")
    end
    local inGrp, lead = inGroupStanding()
    if inGrp and not lead then return ack("delist", key, false, "only the leader can delist") end
    local ok = CALL(function() C_LFGList.RemoveListing() end)
    ack("delist", key, ok, (not ok) and "the game refused" or nil)
end

-- ---------------------------------------------------------------- Create Listing: the draft and the post
local function isAutoCat(cid)
    local ok, ci = CALL(function() return C_LFGList.GetLfgCategoryInfo(cid) end)
    return ok and type(ci) == "table" and TB(function() return ci.autoChooseActivity end) == true
end


local LOPS = {}

-- the game's category button: the draft's category; while I am listed in it, the listing's activities load (LoadActiveEntry)
function LOPS.cat(cid, key)
    local known = false
    for _, c in ipairs(catIDs) do if c == cid then known = true break end end
    if not known then return ack("lcat", key, false, "not a category the game offers now") end
    -- a category button is only reachable after the game's Back (ClearUI), so it always starts a fresh draft; listed in this
    -- category, the listing's own activities load (LoadActiveEntry)
    draft.cat, draft.acts, draft.why, draft.dirty = cid, {}, nil, false
    if S(function() return C_LFGList.HasActiveEntryInfo() end) == true and listingCategory() == cid then
        for id in pairs(listedActivities()) do draft.acts[id] = true end
    end
    soon(true)
end

-- the game's group line: one tick for all of the group's activities (all on -> all off, else all on); no game action
function LOPS.group(gid, key)
    if not gid or not draft.cat then return ack("lgroup", key, false, "no category chosen") end
    local ids = (tickGroups[draft.cat] or {})[gid]
    if not ids then return ack("lgroup", key, false, "not a group of this category") end
    local inGrp, lead = inGroupStanding()
    if inGrp and not lead then return ack("lgroup", key, false, "only the leader can change the listing") end
    local all = true
    for _, id in ipairs(ids) do if not draft.acts[id] then all = false break end end
    for _, id in ipairs(ids) do draft.acts[id] = (not all) or nil end
    draft.dirty, draft.why = true, nil
    soon(true)
end

function LOPS.tick(id, key)
    if not id or not draft.cat then return ack("ltick", key, false, "no category chosen") end
    if not isTickable(draft.cat, id) then return ack("ltick", key, false, "not an activity of this category") end
    local inGrp, lead = inGroupStanding()
    if inGrp and not lead then return ack("ltick", key, false, "only the leader can change the listing") end
    draft.acts[id] = (not draft.acts[id]) or nil
    draft.dirty, draft.why = true, nil
    soon(true)
end

-- the game's role boxes, solo (in a group the role is chosen the game's own way)
function LOPS.role(which, key)
    if TB(function() return IsInGroup() end) == true then return ack("lrole", key, false, "in a group, the role is set in the game") end
    local ok, r = CALL(function() return C_LFGListRoles.GetRoles() end)
    local cur = { tank = false, healer = false, dps = false }
    if ok and type(r) == "table" then
        cur.tank = TB(function() return r.tank end) or false
        cur.healer = TB(function() return r.healer end) or false
        cur.dps = TB(function() return r.dps end) or false
    end
    cur[which] = not cur[which]
    local okS = CALL(function() C_LFGListRoles.SetRoles(cur) end)
    if not okS then return ack("lrole", key, false, "the game refused") end
    if S(function() return C_LFGList.HasActiveEntryInfo() end) == true then draft.dirty = true end
    soon(true)
end

function LOPS.npf(_, key)
    draft.npf = not draftNpf()
    draft.dirty, draft.why = true, nil
    soon(true)
end

-- Post / Update: the game's rules (LFGListingMixin:UpdatePostButtonEnableState), then ONE game action
function LOPS.post(_, key)
    local now = GetTime()
    if posting and now - posting.at < POST_TIMEOUT then return ack("post", key, false, "a post is already waiting for the game") end
    posting = nil
    local inGrp, lead = inGroupStanding()
    if inGrp and not lead then return ack("post", key, false, "only the leader can create or update a listing") end
    local listed = S(function() return C_LFGList.HasActiveEntryInfo() end) == true
    local cid = draft.cat
    if not cid then return ack("post", key, false, "choose a category") end
    if isAutoCat(cid) then return ack("post", key, false, "needs a description, which only the game's window can take") end
    -- the draft re-checked against the game's current list
    local acts = {}
    for id, on in pairs(draft.acts) do if on and isTickable(cid, id) then acts[#acts + 1] = id end end
    table.sort(acts)
    local mx = maxActs()
    if mx and #acts > mx then return ack("post", key, false, "too many activities") end
    if not draft.dirty then return ack("post", key, false, "nothing changed") end
    if not listed and #acts == 0 then return ack("post", key, false, "choose at least one activity") end
    if inGrp and #acts > 0 then
        local n = SN(function() return GetNumGroupMembers() end) or 0
        for _, id in ipairs(acts) do
            local a = activityInfo(id)
            local m = a and TN(function() return a.maxNumPlayers end) or 0
            if m ~= 0 and m <= n then return ack("post", key, false, "the group is too big for an activity", { max = m }) end
        end
    end
    local npf = draftNpf()
    local op, ok
    if listed and #acts == 0 then
        op = "unlist"                -- the game's Update with nothing ticked removes the listing
        ok = CALL(function() C_LFGList.RemoveListing() end)
    elseif listed then
        op = "update"
        ok = CALL(function() C_LFGList.UpdateListing({ activityIDs = acts, newPlayerFriendly = npf }) end)
    else
        op = "create"
        ok = CALL(function() C_LFGList.CreateListing({ activityIDs = acts, newPlayerFriendly = npf }) end)
    end
    if ok then
        posting = { at = now }
        C_Timer.After(POST_TIMEOUT, function()
            if posting and posting.at == now then posting = nil; draft.why = "no answer from the game"; soon(true) end
        end)
    end
    ack("post", key, ok, (not ok) and "the game refused" or nil, { what = op, n = #acts })
end

-- ---------------------------------------------------------------- Who: the search, the filter toggles, the invite
local WOPS = {}

-- THE FILTER'S SEARCH (Social's, UI Art's SPEC): the level range goes in the text; the zone, the classes and the races go in the
-- game's own filter table exactly as its Who page builds it: classIDs / raceIDs are the ones to LEAVE OUT, uiMapIDs the maps.
function WOPS.search(_, key)
    local text, lvlQ, zoneQ = "", nil, nil
    if filt.lvl then
        local lvl = SN(function() return UnitLevel("player") end)
        if lvl then lvlQ = math.max(1, lvl - FILTER_SPAN) .. "-" .. (lvl + FILTER_SPAN); text = lvlQ end
    end
    local filters = { classIDs = {}, raceIDs = {}, uiMapIDs = {} }
    if filt.zone then
        local map = SN(function() return C_Map.GetBestMapForUnit("player") end)
        if map then filters.uiMapIDs[1] = map end
        zoneQ = TS(function() return GetZoneText() end)
    end
    -- the unticked classes and races are left out, as the game's own filter table does (LFGWhoListFilterUtil.GetWhoFilters)
    local o = whoOptions()
    for _, e in ipairs(o.classes) do if filt.classes[e.id] then filters.classIDs[#filters.classIDs + 1] = e.id end end
    for _, e in ipairs(o.races) do if filt.races[e.id] then filters.raceIDs[#filters.raceIDs + 1] = e.id end end
    whoAsked = { lvl = lvlQ, zone = zoneQ }
    local origin = type(Enum) == "table" and type(Enum.SocialWhoOrigin) == "table" and Enum.SocialWhoOrigin.Social or nil
    CALL(function() C_FriendList.SetWhoToUi(true) end)          -- the results go to the list, not to the chat window
    local ok = CALL(function() C_FriendList.SendWho(text, origin, filters) end)
    ack("who", key, ok, (not ok) and "the game refused the search" or nil,
        { q = text, nc = #filters.classIDs, nr = #filters.raceIDs, nm = #filters.uiMapIDs })
end

function WOPS.toggle(kind, id, key)
    if kind == "l" then filt.lvl = not filt.lvl
    elseif kind == "z" then filt.zone = not filt.zone
    elseif kind == "c" and id then filt.classes[id] = (not filt.classes[id]) or nil
    elseif kind == "r" and id then filt.races[id] = (not filt.races[id]) or nil
    elseif kind == "ca" then filt.classes = {}
    elseif kind == "ra" then filt.races = {}
    elseif kind == "cu" then filt.classes = {}; for _, e in ipairs(whoOptions().classes) do filt.classes[e.id] = true end
    elseif kind == "ru" then filt.races = {}; for _, e in ipairs(whoOptions().races) do filt.races[e.id] = true end
    else return ack("wfilter", key, false, "unknown option") end
    soon(true)
end

-- invite a Who result by name (re-found in the current list, as the LFG invite is)
function WOPS.invite(name, key)
    if type(name) ~= "string" or name == "" then return ack("winvite", key, false, "no name in the key") end
    local found = false
    local n = SN(function() return C_FriendList.GetNumWhoResults() end) or 0
    for i = 1, math.min(n, MAX_WHO) do
        local ok, w = CALL(function() return C_FriendList.GetWhoInfo(i) end)
        if ok and type(w) == "table" and S(function() return w.fullName end) == name then found = true break end
    end
    if not found then return ack("winvite", key, false, "not in the Who list any more") end
    local _, _, _, canInvite = inGroupStanding()
    if not canInvite then return ack("winvite", key, false, "only the leader or an assistant can invite") end
    local ok = CALL(function() C_PartyInfo.InviteUnit(name) end)
    ack("winvite", key, ok, (not ok) and "the game refused the invite" or nil, { name = name })
end

-- THE SECURE ROW "gfclose" (the pad owner's grant; no new row: column 1 added to our own): column 0 clicks the game's own close
-- button, column 1 clicks the game's own Group Finder micro button (the game's toggle, in its own code). Column 1 is ARMED ONLY WHILE
-- THE WINDOW IS SHUT (Home 2026-10-01: "if the frame is already open, it must not toggle it shut"): its attributes are set again, out of
-- combat, at every show / hide of the window (TapPad re-runs init; in a fight it queues the work until the fight ends). This file never
-- opens or closes a window by a call: the secure template clicks the game's button.
local function initCells(btn, col)
    if col == 0 then
        btn:SetAttribute("type", "click")
        btn:SetAttribute("clickbutton", _G[CLOSE_BUTTON])
        return
    end
    local b = _G[OPEN_BUTTON]
    openArmed = type(b) == "table" and not windowShown()
    btn:SetAttribute("type", openArmed and "click" or nil)
    btn:SetAttribute("clickbutton", openArmed and b or nil)
end
local function onCell(col)
    -- after the secure click (the game opened or closed its own window): the answer, once
    WC.send(WC.encode({ type = "gfack", op = (col == 1) and "open" or "close", ok = true, v = GV }))
    soon(true)
    WC.push()
end
function setupClose()
    if closeReady or not WC.pad or type(WC.pad.secureRow) ~= "function" then return end
    if type(_G[CLOSE_BUTTON]) ~= "table" then return end
    WC.pad.secureRow("gfclose", 2, initCells, onCell)
    closeReady = true
end
-- the window opened or closed: the open cell armed again (or made inert), out of combat
local function rearmOpen()
    if closeReady and WC.pad and type(WC.pad.secureRow) == "function" then WC.pad.secureRow("gfclose", 2, initCells, onCell) end
end
rearmOpenRef = rearmOpen

if WC.pad then
    for _, k in ipairs(FIXED_KEYS) do WC.pad.slotFor("gf", k) end     -- columns 0..5, before anything else
    WC.pad.op("gf", "gf", function(key)
        if type(key) ~= "string" then return ack("?", nil, false, "no key at that column") end
        local kind, rest = key:sub(1, 1), key:sub(2)
        local fn
        if key == "sm" then fn = OPS.mine
        elseif key == "h" then fn = OPS.hold
        elseif key == "f" then fn = OPS.free
        elseif key == "fb" then fn = OPS.freeB
        elseif key == "bh" then fn = OPS.bnextHome
        elseif key == "bb" then fn = OPS.bnextBack
        elseif kind == "s" then fn, rest = OPS.search, tonumber(rest)
        elseif kind == "i" then fn = OPS.invite
        elseif key == "d" then fn = OPS.delist end
        if not fn then return ack("?", key, false, "unknown key") end
        local ok = pcall(fn, rest, key)
        if not ok then ack("?", key, false, "failed") end
    end)
    -- the second row, Create Listing: its ten fixed keys first, then the ticks
    for _, k in ipairs(LFIXED) do WC.pad.slotFor("gfl", k) end
    WC.pad.op("gfl", "gfl", function(key)
        if type(key) ~= "string" then return ack("?", nil, false, "no key at that column") end
        local kind, rest = key:sub(1, 1), key:sub(2)
        local fn, arg
        if key == "rt" then fn, arg = LOPS.role, "tank"
        elseif key == "rh" then fn, arg = LOPS.role, "healer"
        elseif key == "rd" then fn, arg = LOPS.role, "dps"
        elseif key == "ln" then fn = LOPS.npf
        elseif key == "lp" then fn = LOPS.post
        elseif kind == "c" then fn, arg = LOPS.cat, tonumber(rest)
        elseif kind == "t" then fn, arg = LOPS.tick, tonumber(rest)
        elseif kind == "g" then fn, arg = LOPS.group, tonumber(rest) end
        if not fn then return ack("?", key, false, "unknown key") end
        local ok = pcall(fn, arg, key)
        if not ok then ack("?", key, false, "failed") end
    end)
    -- the third row, Who: its fixed keys first (the classes and races as soon as the game gives the class list)
    for _, k in ipairs(WHO_KEYS) do WC.pad.slotFor("gfw", k) end
    WC.pad.op("gfw", "gfw", function(key)
        if type(key) ~= "string" then return ack("?", nil, false, "no key at that column") end
        local ok
        if key == "ws" then ok = pcall(WOPS.search, nil, key)
        elseif key == "wl" then ok = pcall(WOPS.toggle, "l", nil, key)
        elseif key == "wz" then ok = pcall(WOPS.toggle, "z", nil, key)
        elseif key == "wca" or key == "wcu" or key == "wra" or key == "wru" then ok = pcall(WOPS.toggle, key:sub(2, 3), nil, key)
        elseif key:sub(1, 2) == "wc" then ok = pcall(WOPS.toggle, "c", tonumber(key:sub(3)), key)
        elseif key:sub(1, 2) == "wr" then ok = pcall(WOPS.toggle, "r", tonumber(key:sub(3)), key)
        elseif key:sub(1, 2) == "wi" then ok = pcall(WOPS.invite, key:sub(3), key)
        else return ack("?", key, false, "unknown key") end
        if not ok then ack("?", key, false, "failed") end
    end)
    whoOptions()
    setupClose()
    -- a new UI scale moves every cell: re-publish the geometry at once
    WC.pad.onLayout(function() soon(true) end)
end

hookWindow()
