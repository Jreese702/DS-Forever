-- WowCompanionSocial / Social.lua
-- The SOCIAL room's feed: the lists of other players (the party or raid, friends, the ignore list, the
-- guild roster), so the bottom screen can show them without the game's windows,
-- and (so2) the ONE game action the room asks for on a player it names.
--
-- WHERE THE LISTS LIVE IN THIS CLIENT (read from Blizzard's source for camelot, 2026-09-28):
--   * friends + ignore + a Raid tab: FriendsFrame (camelot override), or the newer SocialUIFrame when
--     C_SocialUI.IsSystemEnabled() is true (a server switch; `me.sys.socialUI` answers it on the wire);
--   * guild: CommunitiesFrame (the old Blizzard_GuildUI is not installed);
--   * Who: the third tab of the Group Finder (LFGParentFrame). NOT this room's since so6: Who lives only in the
--     Group Finder room, as in the game (the user's answer 2026-09-30).
--
-- WHAT IT SENDS: {"type":"social","k":<part>,"s":{...}} documents, one per PART, each only when that part
-- changed: `me`, `group`, `friends`, `ignore`, `guild`. Parts keep a guildmate walking into a new zone
-- from re-sending the friends list, and the guild (up to hundreds of rows) from riding every change.
-- Small parts ride the core's 15 s heartbeat; the guild rides every GUILD_RIDE-th one.
-- Answers to the room's taps are {"type":"socialack"} lines.
--
-- WHAT THE GAME HIDES, and how it shows: anyone's health is secret (never read here). The GUILD's member
-- data is secret while the game's "chat messaging lockdown" is on (Blizzard's docs flag the roster with
-- SecretInChatMessagingLockdown); `guild.lock` carries C_ChatInfo.InChatMessagingLockdown(), and a secret
-- name makes the part say `hidden` instead of sending an empty list that looks real. A Battle.net friend's
-- real name is protected text: never read, the BattleTag is used instead.
--
-- ACTING (so2), through TWO rows of the shared tap pad (granted by the pad owner, AGENT_LOG 800a003):
--   * `sact` column = a fixed CODE (the table CODES below, published as `me.codes`). Codes starting with `v`
--     change which list the room SHOWS (no game action); the rest are the one game action.
--   * `spick` column = a player in the shown list: the rows of the shown part carry `slot` 0..39 (the first
--     40 of that list, in the document's order). A pick is NO game action: it only makes the addon NAME the
--     player it would act on. The reply carries that name, so the room's confirmation and its button show
--     the addon's own understanding of the target, never the app's guess (the quests abandon pattern).
--   * One touch = one tap: a tab touch sends one view tap, a row touch one pick tap, an action button one
--     act tap. Nothing is chained or timed here; the game's own answer (a system line or an error) is
--     reported back once, if it comes within ANSWER_SECONDS.
--   * The game may refuse in combat or for rank reasons; this file does not second-guess it, except that
--     an action must fit the list its player was picked from (an Uninvite on a friend is refused here).
--   * READ-ONLY UNTIL TAPPED: nothing in this file changes the game except inside an act tap's click. The
--     one call outside that is C_GuildInfo.GuildRoster(), a request for fresh guild data, the same one the
--     game's own guild window makes each time it opens.
--
-- so4 (UI Art's SPEC, the user's picks of 2026-09-28): THE CONTROLLER while the room shows (a guest of interact's shared
-- WowCompanion.capture: `hold` / `free`, every press relayed as {"type":"socialpad"}; the game's own social windows go to
-- alpha 0 BY NAME through the capture, this file touches no frame); THE WHO FILTER as drawn (My level, My zone, Class,
-- Race; while it is open `spick` columns toggle its options, no game action; closing it is the search, ONE `sact` tap);
-- and a read-only note when the GAME opens its own Friends / Guild / Who window (`me.open`, `me.openN`), so the room can
-- come up by itself (the end-goal ruling). The hooks are HookScript on OnShow / OnHide only; nothing is written to a
-- Blizzard frame.
--
-- so5 (the user's report 2026-09-30, "I tried to add a friend from the who menu and it didnt work"): MEASURED on the Thor,
-- this server switches the old character-friend list OFF (`me.sys.legacyFriends` false), so C_FriendList.AddFriend is
-- ignored without a word. Add Friend now does what the game's own Add Friend does (AddFriendFrame_Accept, addfriendtemplates):
-- a friend REQUEST through Battle.net's "title friends" when C_BattleNet.AreTitleFriendsEnabled(), else the old list, else it is
-- refused with the reason. A friend made that way is a Battle.net row; one without a BattleTag is named by its character.
--
-- so6 (the user's answer 2026-09-30, after Home's touch test showed a Who search from this room opening the game's LFG
-- window and the app jumping to Group Finder): "Only in Group Finder, like the game -- Remove Social's Who tab; Who is
-- Group Finder's tab 3 as in the game. Social keeps Friends, Guild and its other lists. No jumping between rooms."
-- The `who` part, the Who filter, the searches and the hook on the game's Who page are gone; LFGParentFrame is no longer
-- this room's to hide. The Who codes keep their `sact` columns (append only) and are refused with the reason (RETIRED).
--
-- so7 (THE USER'S ANSWER 2026-10-01, via Home: "Every room opens the game's own window behind it, as Professions does"): the
-- `sact` row is SECURE; `wopen` opens the game's own Friends window by a secure click of the game's own button (armed only while
-- the room holds and no Social window is shown; frozen in a fight, so8), `free` closes it by a secure macro of the game's own close
-- buttons and then lets the controller go, and B closes it through the capture's binding and goes Home. See "the controller".
--
-- so8 (SPEC_COMBAT, signed off by the user 2026-10-01, items 4-12: a room keeps the controller in a fight, opening one mid-fight
-- takes it): no gate of this file's own in a fight beyond the game's rules. A take runs in a fight (capture v10: ok + PENDING
-- until Home taps the capture's enter); a release the capture refuses in a fight ("tap leave": only its secure leave gives the
-- pad back there) keeps the controller ours; `wopen` keeps whatever arming it had when the fight began (secure attributes
-- cannot change in a fight; it is no longer switched off by this file); after the fight B's close follows the room again.
--
-- so9 (capture v11, Interact i82, the take queue): a take that finds another holder (a room or an NPC window) answers ok and
-- WAITS, granted when the holder lets go; in a fight a take is pending until Home's enter. So "ok" is not "holding": the
-- `wopen` cell is armed only while the capture's own state names Social as the holder (a window opened for a room that only
-- waits would stand on the top screen), and the room looks every 0.5 s until it is granted. After an NPC window took the
-- controller the room queues again at once (v11), so it is granted the moment the window closes.
--
-- so10 (MEASURED on the Thor 2026-10-02 09:28:44, Social's first touch check): the `free` cell's secure macro ran on the tap's
-- PRESS, while its post-hook (the room's "own close" mark) runs on the RELEASE a frame later; the window-closed check in between
-- took the close for B and released as "B" (a socialpad BACK 22 ms before free's answer). The own-open / own-close marks are now
-- set by a post-hook on the cells' OnClick for the press too (it runs in the same click as the secure action, before any later
-- frame), and the B decision waits 0.3 s.
--
-- Every value goes through S()/SN() or pl() (which tests plainness), so nothing raw is ever concatenated.

local ADDON = ...
local WC = _G.WowCompanion
if type(WC) ~= "table" or type(WC.S) ~= "function" or type(WC.send) ~= "function" then return end
local S, SN, CALL = WC.S, WC.SN, WC.CALL

local SOV = "so12"   -- bump on every change; the app shows it, so a stale build is visible

local MAX_GROUP = 40        -- a raid
local MAX_FRIENDS = 100     -- the game's cap on character friends
local MAX_BN = 200          -- Battle.net friends read
local MAX_IGNORE = 100
local MAX_GUILD = 600       -- guild rows sent, online first (the game allows 1,000 members)
local MAX_NOTE = 60         -- characters of a friend's or guildmate's note
local MAX_MOTD = 255
local SLOTS = 40            -- pad columns: the first 40 rows of the shown list can be acted on
local GUILD_MIN_GAP = 5     -- seconds between two guild documents (GUILD_ROSTER_UPDATE is chatty)
local GUILD_RIDE = 4        -- the guild rides every 4th heartbeat (60 s)
local ROSTER_ASK_GAP = 60   -- seconds between two requests for fresh guild data
local PICK_SECONDS = 120    -- a pick is good for this long
local PICK_SECONDS_HARD = 60 -- ... and for this long for the actions that need a confirmation
local ANSWER_SECONDS = 2    -- the game's answer to an action is looked for this long
local GUEST = "social"      -- our name as a guest of the shared controller capture

local function real(v)
    return v ~= nil and v ~= WC.ABSENT and v ~= WC.SECRET and v ~= WC.THREW
end

-- S() that also counts sentinel hits, so a shift in the secrecy map shows up on the wire (`sec`)
-- instead of as a quietly emptier room. ABSENT is not counted: nil is a normal answer.
local tally = { secret = 0, threw = 0 }
local function T(fn)
    local v = S(fn)
    if v == WC.SECRET then tally.secret = tally.secret + 1
    elseif v == WC.THREW then tally.threw = tally.threw + 1 end
    return v
end
local function TS(fn) local v = T(fn); if type(v) == "string" and real(v) and v ~= "" then return v end end
local function TN(fn) local v = T(fn); if type(v) == "number" then return v end end
local function TB(fn) local v = T(fn); if type(v) == "boolean" then return v end end

-- One value out of a multi-return CALL: kept only when plain and of the wanted type. A secret is
-- counted and dropped. The caller never concatenates anything that did not come out of here.
local function pl(v, kind)
    if type(v) == "nil" then return nil end
    if not WC.isPlain(v) then tally.secret = tally.secret + 1 return nil end
    if kind and type(v) ~= kind then return nil end
    if kind == "string" and v == "" then return nil end
    return v
end

-- CALL for multi-return APIs that also counts a throw, so a changed signature shows up in `sec`
-- instead of as a quietly empty list.
local function CT(fn)
    return (function(ok, ...)
        if not ok then tally.threw = tally.threw + 1 end
        return ok, ...
    end)(CALL(fn))
end

local function cut(s, n)
    if type(s) ~= "string" then return nil end
    if #s > n then return s:sub(1, n) end
    return s
end

-- ---------------------------------------------------------------- the codes of the `sact` row
-- The column is the position here, minus one. Append only: a code never changes column within a version.
local CODES = {
    -- one game action on the picked player
    "invite", "uninvite", "leader", "assist", "demote",
    "addfriend", "unfriend", "ignore", "unignore",
    "ginvite", "gremove", "gpromote", "gdemote",
    "group1", "group2", "group3", "group4", "group5", "group6", "group7", "group8",
    -- one game action on the room (no pick)
    "toraid", "toparty", "allassist", "whozone", "wholevel", "whoboth",
    -- which list the room shows (no game action)
    "vgroup", "vfriends", "vignore", "vguild", "vwho",
    -- so4: the controller while the room shows (no game action), the Who filter (its view: no game action; the search: ONE)
    "hold", "free", "vwhof", "whosearch",
    -- so7: open the game's own Friends window (a secure click, armed only while holding), where the room's next B leads
    "wopen", "bh", "bb",
}
-- so6: Who is the Group Finder room's (the user's answer 2026-09-30). These codes keep their columns and are refused.
local RETIRED = { whozone = true, wholevel = true, whoboth = true, vwho = true, vwhof = true, whosearch = true }
local CODE_COL = {}
for i, c in ipairs(CODES) do CODE_COL[c] = i - 1 end
local VIEW_OF = { vgroup = "group", vfriends = "friends", vignore = "ignore", vguild = "guild" }
-- which list a player must have been picked from for each player action
local FITS = {
    invite = { friends = true, guild = true },
    uninvite = { group = true }, leader = { group = true }, assist = { group = true }, demote = { group = true },
    addfriend = { group = true, guild = true },
    unfriend = { friends = true },
    ignore = { group = true, friends = true, guild = true },
    unignore = { ignore = true },
    ginvite = { group = true, friends = true },
    gremove = { guild = true }, gpromote = { guild = true }, gdemote = { guild = true },
}
for k = 1, 8 do FITS["group" .. k] = { group = true } end
-- the actions the room confirms first: their pick must be fresher
local HARD = { uninvite = true, leader = true, unfriend = true, ignore = true, gremove = true }

local view = "group"        -- the list the room shows; its rows carry slots
local slotted = {}          -- [col] = the context of the row at that slot in the shown list
local picked = nil          -- { part, ctx, at }
local rowsOK = nil          -- whether the pad granted both rows at load
local ctlr = { want = false, held = false, why = nil, n = 0,     -- the controller (a guest of the shared capture)
               backOn = false, leaveOn = false, bnext = "home" }  -- so7: B closes the game's window; where the next B leads
local opened = { kind = nil, n = 0 }                              -- the game's own window, when it opens one
-- so7: the game's social windows, READ only (is one shown?), and whether the `wopen` cell is armed
local WINDOWS = { "FriendsFrame", "CommunitiesFrame", "SocialUIFrame" }
local openArmed = false
local function anyWinShown()
    for _, n in ipairs(WINDOWS) do
        local fr = _G[n]
        if type(fr) == "table" then
            local ok, s = CALL(function() return fr:IsShown() end)
            if ok and s == true then return true end
        end
    end
    return false
end

-- ---------------------------------------------------------------- class files
-- Friends and Battle.net give the class as a LOCALISED name; the app colours by class FILE ("MAGE").
-- The game's own table answers it: GetClassInfo(id) -> name, file.
local classByName = nil
local function classFile(localized)
    if type(localized) ~= "string" then return nil end
    if not classByName then
        classByName = {}
        local okN, n = CALL(function() return GetNumClasses() end)
        n = (okN and type(n) == "number" and WC.isPlain(n)) and n or 13
        for id = 1, math.min(n, 30) do
            local ok, name, file = CALL(function() return GetClassInfo(id) end)
            name, file = ok and pl(name, "string"), ok and pl(file, "string")
            if name and file then classByName[name] = file end
        end
        for _, t in ipairs({ _G.LOCALIZED_CLASS_NAMES_MALE, _G.LOCALIZED_CLASS_NAMES_FEMALE }) do
            if type(t) == "table" then
                for file, name in pairs(t) do
                    if type(file) == "string" and type(name) == "string" and WC.isPlain(file) and WC.isPlain(name) then
                        classByName[name] = classByName[name] or file
                    end
                end
            end
        end
    end
    return classByName[localized]
end

-- ---------------------------------------------------------------- me
local function rule(name)
    local e = type(Enum) == "table" and type(Enum.GameRule) == "table" and Enum.GameRule[name] or nil
    if type(e) ~= "number" then return nil end
    return TB(function() return C_GameRules.IsGameRuleActive(e) end)
end

local function collectMe()
    local okG, gname, grank, gri = CT(function() return GetGuildInfo("player") end)
    local okC, _, cls = CT(function() return UnitClass("player") end)
    local inRaid = TB(function() return IsInRaid() end)
    local inGroup = TB(function() return IsInGroup() end)
    local me = {
        n = TS(function() return UnitName("player") end),
        realm = TS(function() return GetRealmName() end),
        lvl = TN(function() return UnitLevel("player") end),
        cls = okC and pl(cls, "string") or nil,
        guild = okG and pl(gname, "string") or nil,
        grank = okG and pl(grank, "string") or nil,
        gri = okG and pl(gri, "number") or nil,
        lead = (inGroup and TB(function() return UnitIsGroupLeader("player") end)) or nil,
        assist = (inRaid and TB(function() return UnitIsGroupAssistant("player") end)) or nil,
        canInvite = TB(function() return C_PartyInfo.CanInvite() end),
        cb = TB(function() return UnitAffectingCombat("player") end) or nil,
        lock = TB(function() return C_ChatInfo.InChatMessagingLockdown() end) or nil,
        -- the switches the source could not settle offline, answered on the wire
        sys = {
            socialUI = TB(function() return C_SocialUI.IsSystemEnabled() end),
            bnet = TB(function() return BNConnected() end),
            legacyFriends = TB(function() return C_FriendList.IsLegacyFriendSystemEnabled() end),
            titleFriends = TB(function() return C_BattleNet.AreTitleFriendsEnabled() end),
            recentAllies = TB(function() return C_RecentAllies.IsSystemEnabled() end),
            noRaidGroups = rule("DisableRaidGroups"),
            noQuickJoin = rule("DisableQuickJoin"),
            noGuildPanel = rule("CommunitiesPanelDisabled"),
            noFriendsList = rule("IngameFriendsListDisabled"),
        },
        -- acting: the shown list, the codes of the `sact` row, and whether the pad granted the rows
        view = view,
        codes = CODE_COL,
        rows = rowsOK,
        held = ctlr.held or nil,
        heldWhy = ctlr.why,
        cap = (type(WC.capture) == "table" and type(WC.capture.take) == "function") or false,
        open = opened.kind,
        openN = opened.n,
        win = anyWinShown() or nil,       -- so7: a Social window of the game is shown (behind the room while it holds)
        -- so11: what this client's SocialUIFrame offers ("cb" = its CloseButton field; g = the type of a global SocialUIFrameCloseButton)
        sui = (type(_G.SocialUIFrame) == "table") and { cb = type(_G.SocialUIFrame.CloseButton) == "table",
              g = type(_G.SocialUIFrameCloseButton) } or nil,
        back = ctlr.backOn or nil,        -- B closes it now (spec.back)
        bnext = ctlr.bnext,
        armed = openArmed or nil,         -- the `wopen` cell would click the game's button
        pad = WC.pad and WC.pad.describe and WC.pad.describe() or nil,
    }
    if inGroup and not inRaid then
        me.canRaid = TB(function() return C_PartyInfo.AllowedToDoPartyConversion(true) end)
    end
    if inRaid then
        me.canParty = TB(function() return C_PartyInfo.AllowedToDoPartyConversion(false) end)
        me.allAssist = TB(function() return IsEveryoneAssistant() end)
    end
    return me
end

-- ---------------------------------------------------------------- group
local function unitName(unit)
    local ok, name, realm = CT(function() return UnitName(unit) end)
    name, realm = ok and pl(name, "string"), ok and pl(realm, "string")
    if not name then return nil end
    if realm then return name .. "-" .. realm end
    return name
end

local function readyState(unit)
    local v = TS(function() return GetReadyCheckStatus(unit) end)
    if v == "ready" or v == "notready" or v == "waiting" then return v end
end

local function roleOf(r)
    if r == "TANK" or r == "HEALER" or r == "DAMAGER" then return r end
end

-- What the game's raid window also shows about a member (so3): race and guild (the game has them for
-- any group member), and the PET a hunter or warlock has out (the raid window counts pets on its own
-- class button; a pet's health, like anyone's, is secret and never read). Returns the number of pets.
local function extras(m, unit, petUnit)
    local okR, race = CT(function() return UnitRace(unit) end)
    m.race = okR and pl(race, "string") or nil
    local okG, guild = CT(function() return GetGuildInfo(unit) end)
    m.guild = okG and pl(guild, "string") or nil
    if TB(function() return UnitExists(petUnit) end) then
        m.pet = unitName(petUnit) or true
        return 1
    end
    return 0
end

local function collectGroup()
    local inRaid = TB(function() return IsInRaid() end)
    local inGroup = TB(function() return IsInGroup() end)
    if not inGroup then return { kind = "none" } end
    local n = TN(function() return GetNumGroupMembers() end) or 0
    local out = { kind = inRaid and "raid" or "party", n = n, list = {} }
    local pets = 0
    if inRaid then
        for i = 1, math.min(n, MAX_GROUP) do
            local ok, name, rank, sub, lvl, _, file, zone, online, dead, role, isML, combatRole =
                CT(function() return GetRaidRosterInfo(i) end)
            name = ok and pl(name, "string") or nil
            if name then
                local unit = "raid" .. i
                local m = {
                    n = name, rank = pl(rank, "number"), sub = pl(sub, "number"), lvl = pl(lvl, "number"),
                    cls = pl(file, "string"), zone = pl(zone, "string"), on = pl(online, "boolean") or false,
                    dead = pl(dead, "boolean") or nil, ml = pl(isML, "boolean") or nil,
                    role = roleOf(pl(combatRole, "string")) or roleOf(pl(role, "string")),
                    me = TB(function() return UnitIsUnit(unit, "player") end) or nil,
                    rc = readyState(unit),
                }
                -- the raid's own marks (the Main Tank / Main Assist buttons of the game's raid window)
                local rr = pl(role, "string")
                m.mt = (rr == "MAINTANK") or nil
                m.ma = (rr == "MAINASSIST") or nil
                pets = pets + extras(m, unit, "raidpet" .. i)
                m._c = { n = name, ri = i, me = m.me }
                if m.rank == 2 then out.lead = name end
                out.list[#out.list + 1] = m
            end
        end
    else
        -- a party: the player and party1..4. The zone comes from the raid roster call when this
        -- client answers it in a party; otherwise it stays empty rather than guessed.
        local units = { "player", "party1", "party2", "party3", "party4" }
        local petUnits = { "pet", "partypet1", "partypet2", "partypet3", "partypet4" }
        for i, unit in ipairs(units) do
            local name = unitName(unit)
            if name then
                local okC, _, file = CT(function() return UnitClass(unit) end)
                local okR, rname, _, _, _, _, _, zone = CT(function() return GetRaidRosterInfo(i) end)
                local lead = TB(function() return UnitIsGroupLeader(unit) end)
                local m = {
                    n = name, lvl = TN(function() return UnitLevel(unit) end),
                    cls = okC and pl(file, "string") or nil,
                    on = TB(function() return UnitIsConnected(unit) end) or false,
                    dead = TB(function() return UnitIsDeadOrGhost(unit) end) or nil,
                    rank = lead and 2 or 0,
                    role = roleOf(TS(function() return UnitGroupRolesAssigned(unit) end)),
                    me = (unit == "player") or nil,
                    rc = readyState(unit),
                }
                -- only trust the roster's zone when that roster line names the same person
                if okR and pl(rname, "string") and (pl(rname, "string") == name
                        or pl(rname, "string") == (TS(function() return UnitName(unit) end))) then
                    m.zone = pl(zone, "string")
                end
                pets = pets + extras(m, unit, petUnits[i])
                m._c = { n = name, me = m.me }
                if lead then out.lead = name end
                out.list[#out.list + 1] = m
            end
        end
    end
    if pets > 0 then out.pets = pets end
    return out
end

-- ---------------------------------------------------------------- friends
local function friendRow(info)
    local r = {
        n = TS(function() return info.name end),
        lvl = TN(function() return info.level end),
        cls = classFile(TS(function() return info.className end)),
        area = TS(function() return info.area end),
        on = TB(function() return info.connected end) or false,
        afk = TB(function() return info.afk end) or nil,
        dnd = TB(function() return info.dnd end) or nil,
        note = cut(TS(function() return info.notes end), MAX_NOTE),
    }
    if r.n then r._c = { n = r.n, char = true } end
    return r
end

local function bnRow(a)
    -- accountName is protected text in the modern client and is deliberately never read
    local r = {
        tag = TS(function() return a.battleTag end),
        afk = TB(function() return a.isAFK end) or nil,
        dnd = TB(function() return a.isDND end) or nil,
        fav = TB(function() return a.isFavorite end) or nil,
        note = cut(TS(function() return a.note end), MAX_NOTE),
        last = TN(function() return a.lastOnlineTime end),
    }
    -- a "title friend" (a friend request accepted in this game, not a BattleTag friend) may come without a BattleTag
    local lvl = TN(function() return a.friendLevel end)
    local titleLvl = type(Enum) == "table" and type(Enum.BattleNetFriendLevel) == "table" and Enum.BattleNetFriendLevel.Title or nil
    if lvl ~= nil and titleLvl ~= nil and lvl == titleLvl then r.title = true end
    local g = T(function() return a.gameAccountInfo end)
    if type(g) == "table" then
        r.on = TB(function() return g.isOnline end) or false
        r.char = TS(function() return g.characterName end)
        r.realm = TS(function() return g.realmName end)
        r.lvl = TN(function() return g.characterLevel end)
        if r.lvl == 0 then r.lvl = nil end
        r.cls = TS(function() return g.classFilename end) or classFile(TS(function() return g.className end))
        r.race = TS(function() return g.raceName end)
        r.area = TS(function() return g.areaName end) or TS(function() return g.richPresence end)
        r.game = TS(function() return g.clientProgram end)
    else
        r.on = false
    end
    if not r.tag and r.char then r.tag = r.char end
    -- a Battle.net friend can be invited or ignored only through the character they are playing
    if r.tag then
        local who = r.char and ((r.realm and (r.char .. "-" .. r.realm)) or r.char) or nil
        r._c = { n = who, tag = r.tag, bn = true }
    end
    return r
end

local function collectFriends()
    local out = { list = {}, bn = {} }
    local n = TN(function() return C_FriendList.GetNumFriends() end) or 0
    out.n = n
    out.on = TN(function() return C_FriendList.GetNumOnlineFriends() end)
    local on, off = {}, {}
    for i = 1, math.min(n, MAX_FRIENDS) do
        local info = T(function() return C_FriendList.GetFriendInfoByIndex(i) end)
        if type(info) == "table" then
            local r = friendRow(info)
            if r.n then
                if r.on then on[#on + 1] = r else off[#off + 1] = r end
            end
        end
    end
    for _, r in ipairs(on) do out.list[#out.list + 1] = r end
    for _, r in ipairs(off) do out.list[#out.list + 1] = r end

    local okB, total, bnOn = CT(function() return BNGetNumFriends() end)
    total = okB and pl(total, "number") or 0
    out.bnN, out.bnOn = total, okB and pl(bnOn, "number") or nil
    local bon, boff = {}, {}
    for i = 1, math.min(total, MAX_BN) do
        local a = T(function() return C_BattleNet.GetFriendAccountInfo(i) end)
        if type(a) == "table" then
            local r = bnRow(a)
            if r.tag then
                if r.on then bon[#bon + 1] = r else boff[#boff + 1] = r end
            end
        end
    end
    -- the game puts favourites first, then online, then offline
    local function add(list, favFirst)
        for _, r in ipairs(list) do if (r.fav == true) == favFirst then out.bn[#out.bn + 1] = r end end
    end
    add(bon, true); add(boff, true); add(bon, false); add(boff, false)
    out.req = TN(function() return BNGetNumFriendInvites() end)
    return out
end

-- ---------------------------------------------------------------- ignore
local function collectIgnore()
    local out = { list = {} }
    local n = TN(function() return C_FriendList.GetNumIgnores() end) or 0
    out.n = n
    for i = 1, math.min(n, MAX_IGNORE) do
        local name = TS(function() return C_FriendList.GetIgnoreName(i) end)
        -- rows are small tables so a slot can ride on them
        if name then out.list[#out.list + 1] = { n = name, _c = { n = name } } end
    end
    out.blocked = TN(function() return BNGetNumBlocked() end)
    return out
end

-- ---------------------------------------------------------------- guild
local lastRosterAsk = -math.huge
local function askRoster()
    local now = GetTime()
    if now - lastRosterAsk < ROSTER_ASK_GAP then return end
    lastRosterAsk = now
    CALL(function() C_GuildInfo.GuildRoster() end)
end

-- hours since last online, from the game's (years, months, days, hours); a month counted as 30 days
local function agoHours(i)
    local ok, y, mo, d, h = CT(function() return GetGuildRosterLastOnline(i) end)
    if not ok then return nil end
    y, mo, d, h = pl(y, "number") or 0, pl(mo, "number") or 0, pl(d, "number") or 0, pl(h, "number") or 0
    return ((y * 12 + mo) * 30 + d) * 24 + h
end

local function collectGuild()
    if not TB(function() return IsInGuild() end) then return { inGuild = false } end
    local okG, gname, grank = CT(function() return GetGuildInfo("player") end)
    local out = {
        inGuild = true,
        name = okG and pl(gname, "string") or nil,
        rank = okG and pl(grank, "string") or nil,
        lock = TB(function() return C_ChatInfo.InChatMessagingLockdown() end) or nil,
        motd = cut(TS(function() return C_GuildInfo.GetMOTD() end), MAX_MOTD),
        can = {
            invite = TB(function() return CanGuildInvite() end),
            remove = TB(function() return CanGuildRemove() end),
            promote = TB(function() return CanGuildPromote() end),
            demote = TB(function() return CanGuildDemote() end),
            note = TB(function() return CanEditPublicNote() end),
            onote = TB(function() return C_GuildInfo.CanEditOfficerNote() end),
            view = TB(function() return C_GuildInfo.CanViewOfficerNote() end),
        },
    }
    local okN, total, online = CT(function() return GetNumGuildMembers() end)
    out.n, out.on = okN and pl(total, "number") or 0, okN and pl(online, "number") or nil
    local viewOfficer = out.can.view == true
    local on, off, hidden = {}, {}, 0
    for i = 1, math.min(out.n, 1000) do
        local ok, name, rankName, rankIndex, lvl, _, zone, note, onote, isOnline, status, file, _, _, isMobile, _, _, guid =
            CT(function() return GetGuildRosterInfo(i) end)
        if ok and type(name) ~= "nil" then
            name = pl(name, "string")
            if not name then
                hidden = hidden + 1
            else
                local r = {
                    n = name, rank = pl(rankName, "string"), ri = pl(rankIndex, "number"), lvl = pl(lvl, "number"),
                    cls = pl(file, "string"), zone = pl(zone, "string"), note = cut(pl(note, "string"), MAX_NOTE),
                    onote = viewOfficer and cut(pl(onote, "string"), MAX_NOTE) or nil,
                    on = pl(isOnline, "boolean") or false, st = pl(status, "number"),
                    mob = pl(isMobile, "boolean") or nil,
                }
                if r.st == 0 then r.st = nil end
                r._c = { n = name, guid = pl(guid, "string") }
                if r.on then on[#on + 1] = r else r.ago = agoHours(i); off[#off + 1] = r end
            end
        end
    end
    -- a secret name = the game is hiding the roster right now: say so, never send a list that looks real
    if hidden > 0 then
        out.hidden = true
        return out
    end
    out.list = {}
    for _, r in ipairs(on) do if #out.list < MAX_GUILD then out.list[#out.list + 1] = r end end
    for _, r in ipairs(off) do if #out.list < MAX_GUILD then out.list[#out.list + 1] = r end end
    if #on + #off > #out.list then out.cut = #on + #off - #out.list end
    return out
end

-- ---------------------------------------------------------------- slots
-- The rows of the SHOWN list get slot 0..39 in the document's order (friends: Battle.net rows first,
-- then character friends, the two arrays the document carries). Their contexts are kept here, out of
-- the document, so a pick resolves to exactly what the room was shown. Every other part loses its `_c`.
local function rowsOf(part, doc)
    if part == "friends" then
        local all = {}
        for _, r in ipairs(doc.bn or {}) do all[#all + 1] = r end
        for _, r in ipairs(doc.list or {}) do all[#all + 1] = r end
        return all
    end
    return doc.list or {}
end

local function finish(part, doc)
    local rows = rowsOf(part, doc)
    local shown = (part == view)
    if shown then slotted = {} end
    local col = 0
    for _, r in ipairs(rows) do
        local c = r._c
        r._c = nil
        -- the player's own line and a Battle.net friend who is not in a game cannot be acted on
        if shown and c and not c.me and (c.n or c.bn) and col < SLOTS then
            r.slot = col
            slotted[col] = c
            col = col + 1
        end
    end
    return doc
end

-- ---------------------------------------------------------------- emission
local PARTS = { "me", "group", "friends", "ignore", "guild" }
local COLLECT = { me = collectMe, group = collectGroup, friends = collectFriends, ignore = collectIgnore,
                  guild = collectGuild }
local lastKey, lastSent = {}, {}

function WC.collectSocial(part)
    tally = { secret = 0, threw = 0 }
    local ok, doc = pcall(COLLECT[part])
    if not ok or type(doc) ~= "table" then
        doc = { failed = true }
        tally.threw = tally.threw + 1
    end
    doc = finish(part, doc)
    if tally.secret > 0 or tally.threw > 0 then doc.sec = { s = tally.secret, t = tally.threw } end
    return doc
end

-- Collect one part and send it if it changed (or when forced). Does NOT push: callers decide, so a
-- heartbeat ride-along costs no flush of its own. Returns true when something was written.
local function emitPart(part, force)
    local doc = WC.collectSocial(part)
    local key = WC.encode(doc)
    if key == lastKey[part] and not force then return false end
    lastKey[part] = key
    lastSent[part] = GetTime()
    WC.send(WC.encode({ type = "social", v = SOV, k = part, s = doc }))
    return true
end

-- events mark parts dirty; one coalesced pass sends what changed. The guild keeps a minimum gap.
local dirty = {}
local queued = false
local function flushDirty()
    queued = false
    local wrote, later = false, false
    for _, part in ipairs(PARTS) do
        if dirty[part] then
            local gap = (part == "guild") and GUILD_MIN_GAP or 0
            if lastSent[part] and GetTime() - lastSent[part] < gap then
                later = true
            else
                dirty[part] = nil
                if emitPart(part, false) then wrote = true end
            end
        end
    end
    if wrote then WC.push() end
    if later and not queued then
        queued = true
        C_Timer.After(GUILD_MIN_GAP, flushDirty)
    end
end
local function mark(...)
    for i = 1, select("#", ...) do dirty[select(i, ...)] = true end
    if queued then return end
    queued = true
    C_Timer.After(0.5, flushDirty)
end

-- ---------------------------------------------------------------- acting
-- The game's answer to the last action: its next system line or error, once, within ANSWER_SECONDS.
local listening = nil       -- { op, n, until }

local function ack(op, n, ok, why, extra)
    local out = { type = "socialack", v = SOV, op = op, n = n, ok = ok and true or false, why = why }
    if extra then for k, v in pairs(extra) do out[k] = v end end
    WC.send(WC.encode(out))
    WC.push()
end

local function roleName(c) return c and (c.n or c.tag) or nil end

-- the raid index of a name, looked up now (a roster can reorder between the document and the tap)
local function raidIndexOf(name)
    local n = TN(function() return GetNumGroupMembers() end) or 0
    for i = 1, math.min(n, MAX_GROUP) do
        local ok, rname = CALL(function() return GetRaidRosterInfo(i) end)
        if ok and pl(rname, "string") == name then return i end
    end
end

-- player actions: fn(ctx) -> ok, why. `ok` = the call went through; the game answers on its own.
local ACT = {}
ACT.invite = function(c) CALL(function() C_PartyInfo.InviteUnit(c.n) end) return true end
ACT.uninvite = function(c) CALL(function() C_PartyInfo.UninviteUnit(c.n) end) return true end
ACT.leader = function(c) CALL(function() C_PartyInfo.PromoteToLeader(c.n) end) return true end
ACT.assist = function(c) CALL(function() C_PartyInfo.PromoteToAssistant(c.n) end) return true end
ACT.demote = function(c) CALL(function() C_PartyInfo.DemoteAssistant(c.n) end) return true end
ACT.addfriend = function(c)
    -- the game's own order (AddFriendFrame_Accept): a friend request through "title friends" when this server has them, else
    -- the old character-friend list, else refused. On Forever the old list is OFF (measured 2026-09-30): AddFriend did nothing
    if TB(function() return C_BattleNet.AreTitleFriendsEnabled() end) then
        CALL(function() C_BattleNet.SendTitleFriendInviteByName(c.n) end)
        return true, nil, { via = "title" }
    end
    if TB(function() return C_FriendList.IsLegacyFriendSystemEnabled() end) then
        CALL(function() C_FriendList.AddFriend(c.n) end)
        return true, nil, { via = "legacy" }
    end
    return false, "the game's friend list is off"
end
ACT.unfriend = function(c)
    if not c.char then return false, "a Battle.net friend is removed in the game" end
    CALL(function() C_FriendList.RemoveFriend(c.n) end) return true
end
ACT.ignore = function(c) CALL(function() C_FriendList.AddIgnore(c.n) end) return true end
ACT.unignore = function(c) CALL(function() C_FriendList.DelIgnore(c.n) end) return true end
ACT.ginvite = function(c) CALL(function() C_GuildInfo.Invite(c.n) end) return true end
ACT.gremove = function(c)
    if not c.guid then return false, "no guid for that member" end
    CALL(function() C_GuildInfo.RemoveFromGuild(c.guid) end) return true
end
ACT.gpromote = function(c) CALL(function() C_GuildInfo.Promote(c.n) end) return true end
ACT.gdemote = function(c) CALL(function() C_GuildInfo.Demote(c.n) end) return true end
for k = 1, 8 do
    ACT["group" .. k] = function(c)
        if not TB(function() return IsInRaid() end) then return false, "not in a raid" end
        local i = raidIndexOf(c.n)
        if not i then return false, "not in the raid any more" end
        CALL(function() SetRaidSubgroup(i, k) end) return true
    end
end

-- room actions: fn() -> ok, why, extra
local ROOM = {}
ROOM.toraid = function() CALL(function() C_PartyInfo.ConvertToRaid() end) return true end
ROOM.toparty = function() CALL(function() C_PartyInfo.ConvertToParty() end) return true end
ROOM.allassist = function()
    local now = TB(function() return IsEveryoneAssistant() end)
    CALL(function() C_PartyInfo.SetEveryoneIsAssistant(not now) end)
    return true, nil, { to = not now }
end
-- ---------------------------------------------------------------- the controller (a guest of the shared capture)
-- While the room is on the bottom screen the controller belongs to it (UI Art's SPEC 7): the app taps `hold` when the room
-- shows and `free` when it is left. The capture (interact's) takes the eight buttons from the game, gives them to us (the
-- D-pad on the press, A / B / X / Y on the release) and puts the game's own social windows to alpha 0 by NAME (spec.hide)
-- once it is proven. The app owns the glow, so every press is relayed as {"type":"socialpad"}. After an NPC window it is
-- asked for again, only while the room still wants it.
--
-- so7 (THE USER'S ANSWER 2026-10-01, via Home: "Every room opens the game's own window behind it, as Professions does"; the
-- user's words before that: "it should act the EXACT same for all rooms and be no different"): on every route (a touch, the
-- bar, the game's own key) the room ends with the game's own Friends window open BEHIND it (alpha 0, the capture's spec.hide),
-- and leaving closes it the game's way. NOTHING HERE OPENS OR CLOSES A WINDOW BY A FUNCTION CALL (the taint lesson of
-- 2026-09-28): the `sact` row is SECURE (the pad is full: the room's own row is reused, the pad owner's rule), and
--   * `wopen` is a secure CLICK on the game's own social button (QuickJoinToastButton, whose OnClick is ToggleFriendsFrame).
--     It is ARMED only while the room holds the controller and no Social window is shown (so8: a fight freezes it as it was), so a second or a
--     stray tap cannot toggle the window shut, and the window never opens on the top screen (the take comes first: the app
--     sends `wopen` once the `hold` answer says held, as Professions waits for `held`).
--   * `free` is a secure MACRO of the game's own close buttons (/click FriendsFrameCloseButton, CommunitiesFrameCloseButton),
--     armed only while the room holds; its post-hook then lets the capture go by id. When Social does not hold, it does nothing.
--   * B: while the room's next B leads Home (`bh`; `bb` = B steps back inside the room; the app says which) and a Social window
--     is shown, the take's spec.back is WowCompanionSocialClose, the same macro: B CLICKS it through the capture's binding (the
--     player's own press, no tap), the game closes its window, and the room lets go with "B" and tells the app (socialpad
--     BACK), which shows Home. Otherwise B is relayed to the room as before.
--   * capture v9: spec.leave = WowCompanionSocialClose (Interact's generic and fight leaves click it), spec.window = true on every
--     take out of a fight. A capture that refuses them ("bad spec") is taken without them.
--   * lost "B" / "left" / "taken" = the room was left (a fight's B, the generic leave, another room's window): it does not ask for
--     the controller again by itself. "npc" / "combat" keep the old rule (asked again while the room still wants it).
local function capture()
    local c = WC.capture
    if type(c) == "table" and (c.version or 0) >= 1 and type(c.take) == "function" and type(c.release) == "function" then return c end
end

local OPEN_BUTTON = "QuickJoinToastButton"          -- the game's own social button (blizzard_quickjoin: ToggleFriendsFrame)
local CLOSE_NAME = "WowCompanionSocialClose"        -- our secure macro button: B's binding click and the capture's leave
local CLOSE_MACRO = "/click FriendsFrameCloseButton\n/click CommunitiesFrameCloseButton"
-- so11 (Home, the user's go 2026-10-06; client 1.60.1.70245): the game's social window is now SocialUIFrame, which the macro above never
-- closed - B clicked nothing and the room never let go ("The back on social is not working"). Its close button is reached by
-- REFERENCE (SocialUIFrame.CloseButton; its global name is not known offline): a secure "click" button, the route the capture's
-- own leave / enter cells use. B, the leave and the `free` cell use it while SocialUIFrame shows; the macro otherwise
local CLOSE_UI_NAME = "WowCompanionSocialCloseUI"
local LEFT = { B = true, left = true, taken = true }    -- lost for these = the room was left: never asked for again by itself

-- the macro button, made once out of a fight (a secure button's attributes are set out of combat only)
local closeBtn
local function buildClose()
    if closeBtn then return true end
    if InCombatLockdown() then return false end
    local ok, b = pcall(CreateFrame, "Button", CLOSE_NAME, UIParent, "SecureActionButtonTemplate")
    if not ok or type(b) ~= "table" then return false end
    pcall(b.RegisterForClicks, b, "AnyDown", "AnyUp")    -- a binding click fires it once per press (Professions' stop button)
    pcall(b.SetAttribute, b, "type", "macro")
    pcall(b.SetAttribute, b, "macrotext", CLOSE_MACRO)
    closeBtn = b
    return true
end
buildClose()

-- so11: the SocialUIFrame close button (made once, out of a fight; its target set whenever the frame exists, out of a fight)
local closeUiBtn
local function suiClose()
    local f = _G.SocialUIFrame
    if type(f) ~= "table" then return nil end
    local cb = f.CloseButton
    if type(cb) ~= "table" then return nil end
    return cb
end
local function armCloseUi()
    if InCombatLockdown() then return end
    if not closeUiBtn then
        local ok, b = pcall(CreateFrame, "Button", CLOSE_UI_NAME, UIParent, "SecureActionButtonTemplate")
        if not ok or type(b) ~= "table" then return end
        pcall(b.RegisterForClicks, b, "AnyDown", "AnyUp")
        closeUiBtn = b
    end
    local cb = suiClose()
    pcall(closeUiBtn.SetAttribute, closeUiBtn, "type", cb and "click" or nil)
    pcall(closeUiBtn.SetAttribute, closeUiBtn, "clickbutton", cb)
end
armCloseUi()
-- which close B / the leave use NOW: SocialUIFrame's while it shows (and its button is armed), else the old windows' macro
local function suiShown()
    local f = _G.SocialUIFrame
    if type(f) ~= "table" then return false end
    local ok, s = pcall(function() return f:IsShown() end)
    return ok and s == true
end
local function closeTarget()
    if closeUiBtn and suiShown() and suiClose() then return CLOSE_UI_NAME end
    return closeBtn and CLOSE_NAME or nil
end

-- the secure `sact` cells (the pad makes them; this file only sets their attributes, out of combat)
local actCells = {}
local freeArmed = false
-- the room's own open / own close, marked in the click itself (so10: the secure action may run on the press or on the release)
local closeTapAt, ownOpenAt = -1000, -1000
local hookedCell = {}
-- so9: the capture's own word: does it hold for Social right now (not queued behind another holder, not pending in a fight)?
local function isHolder()
    local c = WC.capture
    if type(c) ~= "table" or type(c.state) ~= "function" then return false end
    local ok, st = CALL(c.state)
    return (ok and type(st) == "table" and st.holder == GUEST) and true or false
end
local function armCells()
    if InCombatLockdown() then return end
    local o, fc = actCells[CODE_COL.wopen], actCells[CODE_COL.free]
    local b = _G[OPEN_BUTTON]
    -- `wopen` only while Social really holds (so9); `free` while it holds OR waits (its release then gives the wait up)
    local arm = (ctlr.held and isHolder() and type(b) == "table" and not anyWinShown()) and true or false
    if o then
        pcall(o.SetAttribute, o, "type", arm and "click" or nil)
        pcall(o.SetAttribute, o, "clickbutton", arm and b or nil)
    end
    openArmed = (arm and o ~= nil) and true or false
    local fa = ctlr.held and true or false
    armCloseUi()
    local sui = fa and suiShown() and suiClose() or nil
    if fc then
        -- so11: SocialUIFrame shown = a secure click of its own close button; else the old windows' macro
        pcall(fc.SetAttribute, fc, "type", fa and (sui and "click" or "macro") or nil)
        pcall(fc.SetAttribute, fc, "clickbutton", sui)
        pcall(fc.SetAttribute, fc, "macrotext", (fa and not sui) and CLOSE_MACRO or nil)
    end
    freeArmed = (fa and fc ~= nil) and true or false
end
-- one frame later: never inside the click of the cell being changed
local function armSoon() C_Timer.After(0, armCells) end
local function initAct(btn, col)
    actCells[col] = btn
    -- so10: a post-hook on the cell's own click (never SetScript: that would replace the secure action). It runs right after the
    -- secure action of THAT press or release, so the mark is there before the window's deferred OnShow / OnHide check.
    if (col == CODE_COL.wopen or col == CODE_COL.free) and not hookedCell[btn] and type(btn.HookScript) == "function" then
        hookedCell[btn] = true
        pcall(btn.HookScript, btn, "OnClick", function()
            if col == CODE_COL.wopen and openArmed then ownOpenAt = GetTime() end
            if col == CODE_COL.free and freeArmed then closeTapAt = GetTime() end
        end)
    end
    pcall(btn.SetAttribute, btn, "type", nil)            -- every other code: no secure action, its post-hook is the code
    if col == CODE_COL.wopen or col == CODE_COL.free then armCells() end
end

-- capture version 3: where the next B leads, so Home shows B on its button for "home"
local function sayNext()
    local c = capture()
    if ctlr.held and c and (c.version or 1) >= 3 and type(c.bnext) == "function" then CALL(c.bnext, GUEST, ctlr.bnext) end
end

local takePad             -- below (so9: the spec's `lost` queues again through it)
local CAP_SPEC = {
    hide = { "FriendsFrame", "CommunitiesFrame", "SocialUIFrame" },
    press = function(k)
        ctlr.n = ctlr.n + 1
        WC.send(WC.encode({ type = "socialpad", v = SOV, k = pl(k, "string"), n = ctlr.n }))
        WC.push()
    end,
    lost = function(why)
        why = pl(why, "string")
        ctlr.held, ctlr.backOn, ctlr.leaveOn, ctlr.why = false, false, false, why
        if why and LEFT[why] then ctlr.want = false end
        WC.send(WC.encode({ type = "socialpad", v = SOV, lost = ctlr.why, n = ctlr.n }))
        WC.push()
        mark("me")
        armSoon()
        -- so9 (capture v11): the room still wants it: queue again now (one frame later, outside the capture's own call)
        local c = capture()
        if ctlr.want and c and (c.version or 1) >= 11 then C_Timer.After(0, function() if ctlr.want and not ctlr.held then takePad() end end) end
    end,
}

local watchGrant          -- so9: below, after padWindow
local SHOULDERS = { "PADLSHOULDER", "PADRSHOULDER" }     -- so12: LB / RB, the room's while it holds
takePad = function()
    local c = capture()
    if not c then ctlr.held, ctlr.why = false, "no controller capture" armSoon() return false, ctlr.why end
    local v = c.version or 1
    local spec = { hide = CAP_SPEC.hide, press = CAP_SPEC.press, lost = CAP_SPEC.lost }
    if not InCombatLockdown() then armCloseUi() end
    local closeNow = closeTarget()
    if v >= 9 and closeNow then spec.leave = closeNow end
    if v >= 9 and not InCombatLockdown() then spec.window = true end
    if v >= 2 and closeNow and ctlr.bnext == "home" and anyWinShown() then spec.back = closeNow end
    -- so12 (Home's rooms consistency pass, the user 2026-10-06: a room holds the D-pad, A / B / X / Y AND LB / RB while it shows): the
    -- shoulders are the room's too (capture v4 `extra`), relayed like the eight; the app turns them into the previous / next tab, as the
    -- Group Finder and Professions rooms do (the game's own windows page their tabs with the shoulders)
    if v >= 4 then spec.extra = SHOULDERS end
    local ok, why = c.take(GUEST, spec)
    if not ok and pl(why, "string") == "bad spec" and (spec.leave or spec.window or spec.back or spec.extra) then
        spec.leave, spec.window, spec.back, spec.extra = nil, nil, nil, nil
        ok, why = c.take(GUEST, spec)
    end
    ctlr.held = ok and true or false
    ctlr.backOn = (ok and spec.back ~= nil) and true or false
    ctlr.leaveOn = (ok and spec.leave ~= nil) and true or false
    ctlr.why = (not ok) and pl(why, "string") or nil
    if ok then sayNext() end
    if ok and not isHolder() then watchGrant() end      -- so9: queued (v11) or pending (a fight): look until it is granted
    armSoon()
    return ctlr.held, ctlr.why
end

local function freePad(why)
    local c = capture()
    if c and ctlr.held then
        local okC, ok, rwhy = CALL(c.release, GUEST, why)
        -- so8 (capture v10): in a fight only the capture's own secure leave gives the pad back; its refusal leaves it ours, and the
        -- leave (Home taps it) tells us with lost("left") / lost("B")
        if okC and ok == false and pl(rwhy, "string") == "tap leave" then armSoon() return false, "tap leave" end
    end
    ctlr.held, ctlr.backOn, ctlr.leaveOn = false, false, false
    armSoon()
    return true
end

-- asked for again by itself only when nobody holds the capture (the capture's own rule for a guest)
local function retake()
    -- so8: in a fight too (capture v10: the take is pending until Home taps the capture's enter)
    if not ctlr.want or ctlr.held then return end
    local c = capture(); if not c then return end
    local okS, state = CALL(c.state)
    if okS and type(state) == "table" and state.holder ~= nil then return end
    if takePad() then mark("me") end
end

-- A Social window opened or closed (the hooks below, one frame after the game's own OnShow / OnHide). Opened while the room
-- holds: B may now close it (when it leads Home). Closed while B closed it (spec.back): the player pressed B, the game closed its
-- window, so the room lets go with "B" and the app goes Home. Closed by the room's own `free` cell: its post-hook already let go.
-- It reads what is shown NOW (never a remembered state), so a missed hook cannot leave B without its close.
local function padWindow()
    local now = anyWinShown()
    armCells()
    if not ctlr.want or not ctlr.held then return end
    if now then
        if not ctlr.backOn and ctlr.bnext == "home" and closeBtn and not InCombatLockdown() then takePad() end
        return
    end
    if not ctlr.backOn then return end
    -- so10: decided a moment later (the room's own close is marked by now, whichever phase its secure action ran on)
    C_Timer.After(0.3, function()
        if not ctlr.want or not ctlr.held or not ctlr.backOn or anyWinShown() then return end
        if GetTime() - closeTapAt < 2 then return end
        ctlr.want = false
        freePad("B")
        ctlr.n = ctlr.n + 1
        WC.send(WC.encode({ type = "socialpad", v = SOV, k = "BACK", n = ctlr.n }))
        WC.push()
        mark("me")
    end)
end

-- so9: asked again later while an NPC window holds (v11 queues it, granted when the window closes)
watchGrant = function()
    if ctlr.watching then return end
    ctlr.watching = true
    local was = false
    local function tick()
        if not ctlr.held then ctlr.watching = false return end
        local h = isHolder()
        if h ~= was then was = h; padWindow(); mark("me") end
        if h then ctlr.watching = false return end
        C_Timer.After(0.5, tick)
    end
    C_Timer.After(0.5, tick)
end

ROOM.hold = function()
    ctlr.want = true
    if ctlr.held then return true, "already held", { waits = (not isHolder()) or nil } end
    local ok, why = takePad()
    return ok, why, ok and { waits = (not isHolder()) or nil } or nil
end
-- the post-hook of the secure `free` cell: the game's own close buttons have been clicked (when it was armed)
ROOM.free = function()
    closeTapAt = GetTime()
    local closed = freeArmed
    ctlr.want = false
    local ok, why = freePad()
    return ok, why, { closed = closed }
end
-- the post-hook of the secure `wopen` cell: the game's own social button has been clicked (when it was armed)
ROOM.wopen = function()
    local armed = openArmed
    if armed then ownOpenAt = GetTime() end
    armSoon()
    return armed, (not armed) and (ctlr.held and "the window is open already (or a fight froze the cell)" or "not holding the controller") or nil
end
-- where the room's next B leads (no game action): "home" = B closes the game's window and leaves; "back" = B is the room's
-- the take's B close as the room wants it now; out of a fight only (a holder's re-take in a fight is refused "combat")
local function syncBack()
    if not ctlr.held or InCombatLockdown() then return end
    local wantBack = (closeBtn ~= nil and ctlr.bnext == "home" and anyWinShown()) and true or false
    if wantBack ~= ctlr.backOn then takePad() end
end
local function setBNext(where)
    ctlr.bnext = where
    if ctlr.held and not InCombatLockdown() then
        local wantBack = (closeBtn ~= nil and where == "home" and anyWinShown()) and true or false
        if wantBack ~= ctlr.backOn then takePad() return true end
    end
    sayNext()
    return true
end
ROOM.bh = function() return setBNext("home") end
ROOM.bb = function() return setBNext("back") end
-- none of these waits for the game's answer in chat
local NO_ANSWER = { hold = true, free = true, wopen = true, bh = true, bb = true }

local function onPick(_, col)
    local c = type(col) == "number" and slotted[col] or nil
    if not c then return ack("pick", nil, false, "no one at that column", { col = col, k = view }) end
    picked = { part = view, ctx = c, at = GetTime() }
    ack("pick", roleName(c), true, nil, { col = col, k = view, tag = c.tag })
end

local function onAct(_, col)
    local code = type(col) == "number" and CODES[col + 1] or nil
    if not code then return ack("act", nil, false, "unknown code", { col = col }) end
    if RETIRED[code] then return ack(code, nil, false, "Who is in Group Finder") end
    -- which list the room shows: no game action, the two parts are re-sent with their slots
    local v = VIEW_OF[code]
    if v then
        local old = view
        view = v
        picked = nil
        mark(old, v, "me")
        return ack("view", nil, true, nil, { k = v })
    end
    if ROOM[code] then
        local okR, ok, why, extra = pcall(ROOM[code])
        if not okR then ok, why = false, "threw" end
        if not NO_ANSWER[code] then listening = ok and { op = code, at = GetTime() } or nil end
        mark("group", "me")
        return ack(code, nil, ok, why, extra)
    end
    local fn = ACT[code]
    if not fn then return ack(code, nil, false, "unknown code") end
    local p = picked
    if not p then return ack(code, nil, false, "no one picked") end
    local age = GetTime() - p.at
    if age > (HARD[code] and PICK_SECONDS_HARD or PICK_SECONDS) then
        picked = nil
        return ack(code, roleName(p.ctx), false, "picked too long ago")
    end
    if not (FITS[code] and FITS[code][p.part]) then
        return ack(code, roleName(p.ctx), false, "not for someone on that list", { k = p.part })
    end
    if not p.ctx.n then
        return ack(code, roleName(p.ctx), false, "not playing a character now")
    end
    local okA, ok, why, extra = pcall(fn, p.ctx)
    if not okA then ok, why, extra = false, "threw", nil end
    -- a confirmed action uses its pick up; an invite can be followed by a whisper to the same person
    if HARD[code] then picked = nil end
    listening = ok and { op = code, n = p.ctx.n, at = GetTime() } or nil
    mark(p.part, "me")
    local out = { k = p.part }
    if type(extra) == "table" then for k, v in pairs(extra) do out[k] = v end end
    ack(code, roleName(p.ctx), ok, why, out)
end

-- The game's own answer (e.g. "Arthas is already in a group.", "Player not found."), reported once.
local function onAnswer(text)
    local l = listening
    if not l then return end
    if GetTime() - l.at > ANSWER_SECONDS then listening = nil return end
    text = pl(text, "string")
    if not text then return end
    listening = nil
    local out = { type = "socialack", v = SOV, op = l.op, n = l.n, said = cut(text, 200) }
    WC.send(WC.encode(out))
    WC.push()
end

if WC.pad and type(WC.pad.op) == "function" then
    local a = WC.pad.op("spick", "social", onPick)
    -- so7: `sact` is a SECURE row (TapPad.secureRow): `wopen` / `free` click the game's own buttons in the game's own code;
    -- every other code has no secure action, and the cell's post-hook (on the release, once per tap) is the code. A pad
    -- without secure rows gets the old plain row (no window opened or closed).
    local b
    if type(WC.pad.secureRow) == "function" then
        b = WC.pad.secureRow("sact", #CODES, initAct, function(col) onAct(nil, col) end)
    else
        b = WC.pad.op("sact", "socialcode", onAct)
    end
    rowsOK = (a ~= nil and b ~= nil)
    if type(WC.pad.onLayout) == "function" then WC.pad.onLayout(function() mark("me") end) end
else
    rowsOK = false
end

-- ---------------------------------------------------------------- the game's own windows
-- When the GAME opens its Friends or Guild window, the room should come up at that page (the end-goal ruling:
-- every window on the bottom screen). A post-hook on the frame's OnShow / OnHide notes it; nothing else is done here.
-- Hiding the window is the capture's (spec.hide), while the room holds the controller.
-- so7: a window the ROOM opened (`wopen`) is not counted (`openN` stays): only the game's own key or button counts.
local hooked = {}
local function note(kind)
    opened.kind = kind
    if kind then opened.n = opened.n + 1 end
    mark("me")
end
local function hookFrame(name, kindOf)
    if hooked[name] then return end
    local fr = _G[name]
    if type(fr) ~= "table" or type(fr.HookScript) ~= "function" then return end
    hooked[name] = true
    -- so7: one frame later, so the `wopen` cell's post-hook (it runs after the game's OnShow, in the same click) has marked
    -- the room's OWN open: that one is no "the game opened its window" (the app would jump to a page the player did not ask for)
    pcall(fr.HookScript, fr, "OnShow", function()
        C_Timer.After(0, function()
            local k = kindOf()
            -- the room's own open is used up by the one window it opened, and only right after its click
            if GetTime() - ownOpenAt < 0.5 then ownOpenAt = -1000; opened.kind = k; mark("me") else note(k) end
            padWindow()
        end)
    end)
    pcall(fr.HookScript, fr, "OnHide", function() C_Timer.After(0, function() note(nil); padWindow() end) end)
end
local function friendsKind()
    -- the camelot Friends window's tabs: 1 Friends, 2 Raid, 3 Quick Join (read only)
    local ok, tab = CALL(function() return FriendsFrame.selectedTab end)
    tab = ok and pl(tab, "number") or 1
    return tab == 2 and "raid" or "friends"
end
local function hookAll()
    hookFrame("FriendsFrame", friendsKind)
    hookFrame("SocialUIFrame", function() return "friends" end)
    hookFrame("CommunitiesFrame", function() return "guild" end)
end
hookAll()

-- ---------------------------------------------------------------- events
local EVENTS = {
    PLAYER_ENTERING_WORLD = PARTS,
    GROUP_ROSTER_UPDATE = { "group", "me" }, PARTY_LEADER_CHANGED = { "group", "me" },
    UNIT_CONNECTION = { "group" }, PLAYER_ROLES_ASSIGNED = { "group" }, UNIT_PET = { "group" },
    READY_CHECK = { "group" }, READY_CHECK_CONFIRM = { "group" }, READY_CHECK_FINISHED = { "group" },
    ZONE_CHANGED_NEW_AREA = { "group", "me" },
    FRIENDLIST_UPDATE = { "friends" }, IGNORELIST_UPDATE = { "ignore" },
    BN_FRIEND_INFO_CHANGED = { "friends" }, BN_FRIEND_LIST_SIZE_CHANGED = { "friends" },
    BN_FRIEND_ACCOUNT_ONLINE = { "friends" }, BN_FRIEND_ACCOUNT_OFFLINE = { "friends" },
    BN_CONNECTED = { "friends", "me" }, BN_DISCONNECTED = { "friends", "me" },
    BN_FRIEND_INVITE_ADDED = { "friends" }, BN_FRIEND_INVITE_REMOVED = { "friends" },
    BN_FRIEND_INVITE_LIST_INITIALIZED = { "friends" }, BN_BLOCK_LIST_UPDATED = { "ignore" },
    GUILD_ROSTER_UPDATE = { "guild" }, PLAYER_GUILD_UPDATE = { "guild", "me" },
    GUILD_MOTD = { "guild" }, GUILD_RANKS_UPDATE = { "guild", "me" },
    PLAYER_REGEN_DISABLED = { "me" }, PLAYER_REGEN_ENABLED = { "me" }, PLAYER_LEVEL_UP = { "me" },
    -- the game's answer to an action (only read while one is expected)
    CHAT_MSG_SYSTEM = false, UI_ERROR_MESSAGE = false,
    -- load-on-demand windows (Guild) are hooked when they load
    ADDON_LOADED = false,
}

local f = CreateFrame("Frame")
f:SetScript("OnEvent", function(_, event, a1, a2)
    if event == "CHAT_MSG_SYSTEM" then return onAnswer(a1) end
    if event == "UI_ERROR_MESSAGE" then return onAnswer(a2) end
    if event == "ADDON_LOADED" then hookAll(); armSoon() return end
    -- so8: nothing is switched off at the fight's start (the room keeps what it had); its end: the cells and B follow the room again
    if event == "PLAYER_REGEN_ENABLED" then buildClose(); armCells(); syncBack(); retake() end
    if event == "PLAYER_ENTERING_WORLD" or event == "PLAYER_GUILD_UPDATE" then askRoster() end
    local parts = EVENTS[event]
    if parts then mark(unpack(parts)) end
end)
local missing = {}
for e in pairs(EVENTS) do
    local ok, registered = pcall(function() return f:RegisterEvent(e) end)
    if not ok or registered == false then missing[#missing + 1] = e end
end
if #missing > 0 then
    table.sort(missing)
    pcall(function() WC.send(WC.encode({ type = "cap", social = SOV, missingEvents = missing })) end)
end

-- RIDE THE CORE HEARTBEAT (as Quests and Spells do): anything sent inside WC.snapshot's post-hook
-- lands in the push that snapshot already scheduled, so re-sending costs no flush of its own. The
-- small parts ride every heartbeat; the guild every GUILD_RIDE-th, with a request for fresh data.
local RIDE = { heartbeat = true, login = true, zone = true, manual = true }
local beats = 0
if type(WC.snapshot) == "function" then
    hooksecurefunc(WC, "snapshot", function(reason)
        if not RIDE[reason] then return end
        local withGuild = reason ~= "heartbeat"
        if reason == "heartbeat" then
            beats = beats + 1
            withGuild = (beats % GUILD_RIDE) == 0
        end
        for _, part in ipairs(PARTS) do
            if part ~= "guild" or withGuild then
                dirty[part] = nil
                emitPart(part, true)
            end
        end
        if withGuild then askRoster() end
        -- after an NPC window let the capture go, the room asks for it again (only while it still wants it)
        retake()
    end)
end
