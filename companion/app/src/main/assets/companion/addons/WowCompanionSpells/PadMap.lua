-- WowCompanionSpells / PadMap.lua
-- The Spellbook's CONTROLLER TAB, its data and actions: what is on every controller button, and putting a spell or a game command there.
--
-- USER RULINGS (2026-09-30, relayed by tracking): "I want to add a tab to the spell book for Controller based inputs to bind"; "Pick a button,
-- then what goes on it"; "I was actually meaning for buttons like jump or interact or any of those kinds of special commands that aren't spells";
-- commands "As small macros the companion makes". Tracking: show clearly that a macro uses one of the user's macro slots; ask before replacing.
--
-- WHAT THE GAME ALLOWS (read in its code, blizzard_gamepadactionbars): the eight controller buttons press the ACTIVE bar's button, i.e. a storage
-- SLOT holding a spell, an item or a macro. The base set's four face buttons are the game's FIXED ones ("NonBindable": X Interact, Y Inspect,
-- B Back, A Jump): shown, never changed. A command reaches a button only as a MACRO; movement, jump, camera and auto-run cannot be macros, so they
-- are not offered. The commands' names are the game's own key-binding words (BINDING_NAME_*), read at run time.
--
-- ACTIONS, each out of combat only, each the game's own cursor road (the one Book.lua's Bind has used on the Thor since 2026-09-30):
--   put a spell   ClearCursor, C_SpellBook.PickupSpellBookItem, PlaceAction(slot), ClearCursor
--   put a command CreateMacro ONCE (a General macro named "DSF ...", reused afterwards), PickupMacro, PlaceAction(slot), ClearCursor
--   clear         ClearCursor, PickupAction(slot), ClearCursor
-- The room asks first (replace / make a macro / clear); these run after the yes. No game window is opened, closed, shown or hidden.

local ADDON = ...
local WC = _G.WowCompanion
if type(WC) ~= "table" or type(WC.S) ~= "function" or type(WC.send) ~= "function" then return end
local S, SN, CALL = WC.S, WC.SN, WC.CALL

local PV = "m62"                -- bump on every change; m62: Smart buffs (the player's buff rows after food / water, OR rows by a wheel); m61: Smart Y (food / drink from the bags out of combat, nothing targeted); m60: a pole in hand, X still interacts when there is something to interact with; m59: a fishing pole in the main hand makes a bare X Fishing / Interact; m58: the forms' own In-combat spells get their state too (mana / range tint)
-- m40 S0, THE HOTFIX (the user's ruling 2026-10-09: "everything with the controller settings (outside of changing spells on the normal
-- toolbars) should kind of be an accessory to the addon and not affect peoples normal set up on their charactors"; their pick "stage it
-- now"): NOTHING here creates or edits a macro, or writes a key binding, any more. Start attack on cast and the commands / fight spells
-- that needed macros are PAUSED until the overlay (S2); what already exists stays as it is until the player's own clean-up (S1, per
-- character). Still allowed: the player's own spell / item / mount placements, Move / Clear, X / Y / A's fight spells (no macro), the
-- router's and the bindings' OVERRIDE bindings (never saved), and letting go of a macro of ours the player replaced or cleared.
local PAUSED = true
-- (the add-on's tests switch it off to keep proving the code the clean-up still needs; WC_TEST is never set in the game)
if rawget(_G, "WC_TEST") == true then WC.padMapTest = { paused = function(v) PAUSED = v and true or false end } end
local PAGES = 3                 -- the controller's standard pages
local SETS, POSITIONS = 4, 8    -- none / LT / RT / LT+RT; D-pad left, up, right, down, then face left, top, right, bottom
local FIRST_RESERVED = 5        -- the base set's four face buttons (positions 5-8 of set 1): the game's fixed commands
-- the companion's macros are named "DS " (the app, DS Forever) + a short name that fits the game's 16 characters IN FULL (UI Art 2026-09-30):
-- the game's macro window says plainly which ones the companion made. The tab and its boxes always show the game's full command name.
local BANK_PLAYER = 0

-- the fixed four, by position: the game's own words
local FIXED = { [5] = "BINDING_NAME_INTERACTTARGET", [6] = "INSPECT", [7] = "BACK", [8] = "BINDING_NAME_JUMP" }

-- the commands a macro can do, named by the game's key-binding words (`n` = the fallback when the word is missing); `mn` = the macro's own name;
-- each has its own icon, so the player can tell them apart on the bar
local COMMANDS = {
    -- m47 DS FOREVER'S OWN FIRST (the user after test17, via tracking: "put DS Forever's own actions ... at the TOP of the chooser's Extra
    -- tab, so they're easy to find"): Home's Hub.lua buttons (hub5 / hub8: a barcmd document; the app opens the spell radial (SpellRadial.lua),
    -- the consumables radial, the map, the chat room, the bar). Saved entries name the KEY, never the place, so the order is free to change
    { key = "DSSRADIAL", mn = "DS Spell Radial", body = "/click WowCompanionPadSpellRadial", icon = 133741, n = "Spell Radial" },
    { key = "DSRADIAL", mn = "DS Radial", body = "/click WowCompanionPadRadial", icon = 134829, n = "Consumables Radial" },
    { key = "DSMAP", mn = "DS Map", body = "/click WowCompanionPadMap", icon = 134269, n = "Open Map" },
    { key = "DSCHAT", mn = "DS Chat", body = "/click WowCompanionPadChat", icon = 134332, n = "Open Chat" },
    { key = "DSHOME", mn = "DS Home Bar", body = "/click WowCompanionPadHome", icon = 134414, n = "Home Bar" },
    { key = "TARGETNEARESTENEMY", mn = "DS Target Enemy", body = "/targetenemy", icon = 132212, n = "Target Nearest Enemy" },
    { key = "TARGETNEARESTFRIEND", mn = "DS Target Friend", body = "/targetfriend", icon = 135966, n = "Target Nearest Friend" },
    { key = "TARGETNEARESTENEMYPLAYER", mn = "DS Enemy Player", body = "/targetenemyplayer", icon = 132212, n = "Target Nearest Enemy Player" },
    { key = "TARGETNEARESTFRIENDPLAYER", mn = "DS Friend Player", body = "/targetfriendplayer", icon = 135966, n = "Target Nearest Friendly Player" },
    { key = "TARGETLASTTARGET", mn = "DS Last Target", body = "/targetlasttarget", icon = 132212, n = "Target Last Target" },
    { key = "ASSISTTARGET", mn = "DS Assist", body = "/assist", icon = 132333, n = "Assist Target" },
    { key = "FOCUSTARGET", mn = "DS Focus", body = "/focus", icon = 132212, n = "Focus Target" },
    { key = "FOLLOWTARGET", mn = "DS Follow", body = "/follow", icon = 132307, n = "Follow Target" },
    { key = "STARTATTACK", mn = "DS Start Attack", body = "/startattack", icon = 135641, n = "Start Attack" },
    { key = "PETATTACK", mn = "DS Pet Attack", body = "/petattack", icon = 132152, n = "Pet Attack" },
    { key = "DISMOUNT", mn = "DS Dismount", body = "/dismount", icon = 132261, n = "Dismount" },
    -- THE COMPANION'S OWN (the user 2026-09-30: "add some keybinds to the commands to basically navigate the dsforever homebar left or right"):
    -- the game's secure /click on Home's three plain buttons (Hub.lua), which write a `barcmd` document; Home's bar cursor moves / opens.
    -- Contract with Home (AGENT_LOG). Icons: UI Art's picks, the game's gold arrows (one family): left, right, down = into the room.
    { key = "DSBARLEFT", mn = "DS Bar Left", body = "/click WowCompanionBarLeft", icon = 450906, n = "Room Bar Left" },
    { key = "DSBARRIGHT", mn = "DS Bar Right", body = "/click WowCompanionBarRight", icon = 450908, n = "Room Bar Right" },
    { key = "DSBAROPEN", mn = "DS Bar Open", body = "/click WowCompanionBarOpen", icon = 450905, n = "Room Bar Open" },
    -- m33 THE EXTRA TAB (the user's picks 2026-10-07, via tracking: targeting, attack / stop casting, the DS companion, pet commands, sit /
    -- dismount / cancel form; all the game's own slash commands, read from this client's SlashCommands source). `pet` = pet classes only,
    -- `cls` = the classes that have a form to cancel. Icons: UI Art's picks (all in icons.pak; the pet ones are the game's own pet-bar pictures).
    { key = "CLEARTARGET", mn = "DS Clear Target", body = "/cleartarget", icon = 135768, n = "Clear Target" },
    { key = "CLEARFOCUS", mn = "DS Clear Focus", body = "/clearfocus", icon = 236179, n = "Clear Focus" },
    { key = "STOPATTACK", mn = "DS Stop Attack", body = "/stopattack", icon = 132343, n = "Stop Attack" },
    { key = "STOPCASTING", mn = "DS Stop Casting", body = "/stopcasting", icon = 135856, n = "Stop Casting" },
    { key = "PETFOLLOW", mn = "DS Pet Follow", body = "/petfollow", icon = 132328, n = "Pet Follow", pet = true },
    { key = "PETSTAY", mn = "DS Pet Stay", body = "/petstay", icon = 136106, n = "Pet Stay", pet = true },
    { key = "PETPASSIVE", mn = "DS Pet Passive", body = "/petpassive", icon = 132311, n = "Pet Passive", pet = true },
    { key = "PETDEFENSIVE", mn = "DS Pet Defensive", body = "/petdefensive", icon = 132110, n = "Pet Defensive", pet = true },
    { key = "PETAGGRESSIVE", mn = "DS Aggressive", body = "/petaggressive", icon = 132277, n = "Pet Aggressive", pet = true },
    { key = "PETDISMISS", mn = "DS Pet Dismiss", body = "/petdismiss", icon = 136222, n = "Pet Dismiss", pet = true },
    { key = "SIT", mn = "DS Sit", body = "/sit", icon = 136090, n = "Sit" },
    { key = "CANCELFORM", mn = "DS Cancel Form", body = "/cancelform", icon = 136116, n = "Cancel Form", cls = { DRUID = true, SHAMAN = true, PRIEST = true } },
    -- m34 camera zoom (the user's pick: "then camera zoom (prove it on the device first)"): the game's camera functions in a small macro;
    -- whether this client lets a macro call them is for the first device run to say (UI Art's icons)
    { key = "CAMZOOMIN", mn = "DS Zoom In", body = "/run CameraZoomIn(2)", icon = 134441, n = "Camera Zoom In" },
    { key = "CAMZOOMOUT", mn = "DS Zoom Out", body = "/run CameraZoomOut(2)", icon = 132172, n = "Camera Zoom Out" },
}

-- m34 THE GAME'S KEY BINDINGS ON A BUTTON (the user's pick A 2026-10-07, via tracking: "Jump / Interact / Autorun / Walk on any button ... A: Build for plain + ZL"): this client has
-- no /jump and no /interact, and no secure action for them (its SlashCommands and SecureTemplates, read 2026-10-07): they exist ONLY as key
-- bindings. So the ROUTER points the button's own key at the game's binding: a D-pad button with no trigger (1-4; the DS toolbar off: it
-- owns the bare D-pad) or ANY button with ZL (LT is the game's Shift on this device: "SHIFT-" + the key). ZR and ZL+ZR are paged by the
-- game itself: no key a binding can name, so they refuse. Per character; on every page (a binding knows no page); bound while nothing
-- holds the controller, carried into fights. Icons: UI Art's (the room draws its own fixed_jump / fixed_interact for those two).
local BINDS = {
    { key = "JUMP", n = "Jump", icon = 135992 },
    { key = "INTERACTTARGET", n = "Interact With Target", icon = 132938 },
    { key = "TOGGLEAUTORUN", n = "Auto Run", icon = 132307 },
    { key = "TOGGLERUN", n = "Walk / Run", icon = 132539 },
}
local bindSlot, bindList                -- below (they need the character's key): the doc's view of a bound button, the Extra rows
local cmdSlot                           -- m41 (below): a command on a button, as the overlay keeps it
local OV                                -- m41 / m43 (below): the overlay (commands, fight spells); the reading uses it
-- the commands THIS character sees (pet commands for the pet classes, Cancel Form for the classes with forms); the Extra tab's first group
local PET_CLASSES = { HUNTER = true, WARLOCK = true, DEATHKNIGHT = true }
local function playerClass()
    local ok, _, token = CALL(function() return UnitClass("player") end)
    return ok and WC.isPlain(token) and type(token) == "string" and token or nil
end
local function commandList()
    local cls = playerClass()
    local out = {}
    for _, c in ipairs(COMMANDS) do
        if (not c.pet or (cls and PET_CLASSES[cls])) and (not c.cls or (cls and c.cls[cls])) then out[#out + 1] = c end
    end
    return out
end

local function real(v) return v ~= nil and v ~= WC.ABSENT and v ~= WC.SECRET and v ~= WC.THREW end
local SN = WC.SN
local function TS(fn) local v = S(fn); if type(v) == "string" and real(v) then return v end end
-- the character: name-realm, the key of every per-character store (m36: moved up, the stores below need it)
local function who()
    local n = TS(function() return UnitName("player") end)
    local r = TS(function() return GetRealmName() end)
    return n, r
end
local function whoKey()
    local n, r = who()
    return n and (n .. "-" .. (r or "?")) or nil
end
local function TN(fn) local v = S(fn); if type(v) == "number" then return v end end
local function TB(fn) local v = S(fn); if type(v) == "boolean" then return v end end
local function inCombat() return TB(function() return InCombatLockdown() end) == true end
-- m48 THE FIGHT AS THE EVENTS SAY IT (D's audit after test18: InCombatLockdown() is still false INSIDE PLAYER_REGEN_DISABLED, so the
-- padmap sent there said "no fight" and the app's D-pad picture waited for a later one): true from PLAYER_REGEN_DISABLED to
-- PLAYER_REGEN_ENABLED; the documents' fight flags read [fighting], never the lockdown alone
-- m49 (the user on test20: "the spell changes when I literally enter combat instead of when the other combat spells enter"): [sw] is
-- A / X / Y's FIGHT SWITCH itself (faceWant: in combat, or an attack / auto-repeat running and not given back), set where the faces
-- switch; the documents carry it as `fight`, and the D-pad's In-combat picture follows it, not the combat flag
local fightNow = { on = false, sw = false }
local function fighting() return fightNow.on or inCombat() end

local function word(key) local v = _G[key]; if type(v) == "string" and WC.isPlain(v) then return v end end
local function commandName(c) return word("BINDING_NAME_" .. c.key) or c.n end
local function macroName(c) return c.mn end

local function storage(page, set, pos)
    -- m57: a form chosen in the room (OV.fe): the set the game's stance bar lies over is that FORM's own bar (false = not learned yet)
    if OV and OV.fe then local fs = OV.feSlot(page, set, pos); if fs ~= nil then return fs or nil end end
    if type(WC.spellsStorageFor) ~= "function" then return nil end
    local ok, s = pcall(WC.spellsStorageFor, page, set, pos)
    if ok and type(s) == "number" then return s end
end
local function fixed(set, pos) return set == 1 and pos >= FIRST_RESERVED end

local FACE, faceRef, faceState, faceSetup         -- the base face buttons in fights (defined below, used by the reading too)
local dpadState                                    -- m25: the D-pad room bar (defined below, carried by the padmap)
local startState                                   -- m37: Start on plain Home (defined below, carried by the padmap)
local tooltipCd                                    -- the fight spell tooltip's cooldown (defined with the cooldowns, used by faceSetup)

-- ---------------------------------------------------------------- reading

-- THE COMBAT BUTTONS (the user 2026-10-01: "Per button, as small character macros"): a button that changes in a fight holds a CHARACTER
-- macro the companion made, named "DS Combat <storage slot>", whose body is the game's own condition:
--     #showtooltip
--     /cast [combat] <combat spell>; <otherwise spell>          (no "; otherwise" when the button was empty)
-- m32 (the user 2026-10-07: "the bars can be used for anything and should be able to be replaced by anything"): an ITEM on the button stays
-- for outside fights as its own line, "/use [nocombat] item:<id>" after "/cast [combat] <combat spell>". A macro, a mount or a flyout cannot
-- run from inside a macro: the room asks first, naming it, and then the combat spell REPLACES it (outside fights the button does nothing).
-- The GAME picks at the press; nothing of ours runs in a fight. Made, changed and cleared only out of combat (CreateMacro / EditMacro /
-- DeleteMacro are refused in combat). Icon 134400 + #showtooltip: the game shows the spell it would cast now.
local COMBAT_PREFIX = "DS Combat "
local DYNAMIC_ICON = 134400
local function isCombatName(n) return type(n) == "string" and n:sub(1, #COMBAT_PREFIX) == COMBAT_PREFIX end
local function trim(v) return v and (v:gsub("^%s+", ""):gsub("%s+$", "")) end
-- the combat spell, the otherwise SPELL, and (m32) the otherwise ITEM's id
local function parseCombat(body)
    if type(body) ~= "string" then return nil end
    local line = body:match("/cast %[combat%]%s*([^\n]+)")
    if not line then return nil end
    local c, o = line:match("^([^;]+);(.+)$")
    if not c then c = line end
    c, o = trim(c), trim(o)
    if c == "" then return nil end
    if o == "" then o = nil end
    local oi = tonumber(body:match("/use %[nocombat%]%s*item:(%d+)") or "")
    return c, o, oi
end
-- START ATTACK ON CAST (the user 2026-10-01: "when I attack an enemy with a spell or ability, I also begin to start attack on them"; the
-- pick: "Also the attack spells on the ZL / ZR buttons and the D-pad: each becomes a small Character Macro"). The game has no option for it
-- (its settings code, camelot) and nothing can be added to its own buttons without taint, so with the setting ON each plain HARMFUL spell
-- on the trigger layers and the base D-pad (every page) becomes the companion's character macro "DS Attack <storage slot>":
--     #showtooltip
--     /target [noexists][dead] softenemy
--     /startattack [harm,nodead]
--     /cast <spell>
-- (the game shows the spell's icon, cooldown and range for it); the combat buttons and the fight spells on A / X / Y get the same line.
-- Made only out of combat; the free Character Macro slots are counted first and a button that does not fit stays a plain spell (said);
-- a macro of that name that is not the companion's is never touched. OFF puts the EXACT spell (its own id: its rank) back on every button
-- that still holds the companion's macro, and deletes the macro. The record (m36: per character): WowCompanionSpellsDB.attackBy[name-realm] = { on, slots = { [s] = {id, n} } }.
local ATTACK_PREFIX = "DS Attack "
local function isAttackName(n) return type(n) == "string" and n:sub(1, #ATTACK_PREFIX) == ATTACK_PREFIX end
-- THE SOFT TARGET (the user 2026-10-01 ~09:32: "when casting a spell my start attack does not seem to want to trigger on the next guy, so
-- im standing there"). With a controller the game soft-targets the nearest enemy and makes it the REAL target only when a harmful spell
-- actually goes out (UNIT_SPELLCAST_SENT: blizzard_gamepadtargeting/targetlogic.lua OnAbilityUsed). "/startattack [harm]" reads the real
-- target only, so on a new mob the first cast never started the attack, and with no mana (the spell never goes out) nothing ever did. So:
-- with no living target, the game's soft enemy is targeted FIRST (the same unit the game itself would pick), then the attack, then the
-- cast: the attack starts even when the cast fails (mana, range, cooldown).
-- m21: every start-attack line also clicks the router's re-arm button (the user 2026-10-01, after B gave A / X / Y back mid-fight: "Starting
-- an attack gets them back"): a secure click from the game's own macro, so it works in a fight; only on a living hostile target
local REARM = "WowCompanionFaceRearm"
local function attackLines(cond)
    if cond == "" then return "/target [noexists][dead] softenemy\n/startattack [harm,nodead]\n/click [harm,nodead] " .. REARM .. "\n" end
    return "/target [" .. cond .. ",noexists][" .. cond .. ",dead] softenemy\n/startattack [" .. cond .. ",harm,nodead]\n/click [" .. cond
        .. ",harm,nodead] " .. REARM .. "\n"
end
local function attackBody(name) return "#showtooltip\n" .. attackLines("") .. "/cast " .. name end
local function parseAttack(body)
    if type(body) ~= "string" or not body:find("/startattack", 1, true) then return nil end
    local n = body:match("\n/cast ([^\n]+)$") or body:match("\n/cast ([^\n]+)\n")
    n = trim(n)
    if n and n ~= "" then return n end
end
-- m36 PER CHARACTER (the user 2026-10-07, after other characters' fight spells replaced the Shaman's: "we just need to figure out a way to make the keybinds per charactor specific"): the record of
-- which bar slots hold our attack macro describes THIS character's bars, and the switch is the character's too (WowCompanionSpellsDB.attackBy
-- [name-realm]). A character's first record takes the user's own choice from the old shared record (on, when it was chosen); its slots are
-- adopted from the old shared record only where its own bar proves them (attackAdopt, at login). The old shared record is never written again.
local attackScratch = nil
local function attackDB()
    if type(_G.WowCompanionSpellsDB) ~= "table" then _G.WowCompanionSpellsDB = {} end
    local db = _G.WowCompanionSpellsDB
    local k = whoKey()
    if not k then                                                       -- no character yet (before login): a scratch record, never saved
        attackScratch = attackScratch or { on = true, slots = {} }
        return attackScratch
    end
    if type(db.attackBy) ~= "table" then db.attackBy = {} end
    local a = db.attackBy[k]
    if type(a) ~= "table" then
        a = { on = true, slots = {} }                                   -- m23: ON (the user's default)
        local old = db.attack
        if type(old) == "table" and old.chosen then a.on, a.chosen = old.on == true, true end
        db.attackBy[k] = a
    end
    if type(a.slots) ~= "table" then a.slots = {} end
    return a
end
-- ON BY DEFAULT (the user 2026-10-01: "Oh I want this on by default"): until the user CHOOSES in Home's Settings (`chosen`), the switch is
-- on; m13 saved a plain default `on = false` without `chosen`, which counts as not chosen. A choice is remembered.
local function attackOn()
    local a = attackDB()
    if a.chosen then return a.on == true end
    return true
end
local function harmful(spell)
    if spell == nil then return false end
    return TB(function() return C_Spell.IsSpellHarmful(spell) end) == true
end
-- `other`: nil (the button was empty, or what it held is replaced), the otherwise SPELL's name, or (m32) { item = id } for an item
local function combatBody(cName, other)
    local b = "#showtooltip\n"
    local oName = type(other) == "string" and other or nil
    if attackOn() then
        local ch, oh = harmful(cName), oName ~= nil and harmful(oName)
        if ch and oh then b = b .. attackLines("")
        elseif ch then b = b .. attackLines("combat")
        elseif oh then b = b .. attackLines("nocombat") end
    end
    if type(other) == "table" and type(other.item) == "number" then
        return b .. "/cast [combat] " .. cName .. "\n/use [nocombat] item:" .. other.item
    end
    return b .. "/cast [combat] " .. cName .. (oName and ("; " .. oName) or "")
end
-- an item named in a combat macro (m32): its id, name and icon, marked `item`
local function itemRef(id)
    if type(id) ~= "number" then return nil end
    return { id = id, item = true, n = TS(function() return C_Item.GetItemNameByID(id) end),
             i = TN(function() return C_Item.GetItemIconByID(id) end) }
end
-- a spell named in a combat macro: its id and icon, as the game has it
local function spellRef(name)
    if not name then return nil end
    local r = { n = name }
    local okI, info = CALL(function() return C_Spell.GetSpellInfo(name) end)
    if okI and type(info) == "table" then
        r.id = TN(function() return info.spellID end)
        r.i = TN(function() return info.iconID end)
    end
    return r
end

-- one slot: its kind, id, name and icon (a spell, an item, a macro, a combat button, or nothing)
local function readSlot(s)
    local ok, kind, id = CALL(function() return GetActionInfo(s) end)
    local out = { s = s }
    if not ok or not WC.isPlain(kind) or type(kind) ~= "string" then out.kind = "none" return out end
    out.id = WC.isPlain(id) and type(id) == "number" and id or nil
    out.i = TN(function() return GetActionTexture(s) end)
    if kind == "spell" then
        out.kind = "spell"
        out.n = out.id and TS(function() return C_Spell.GetSpellName(out.id) end)
    elseif kind == "item" then
        out.kind = "item"
        out.n = out.id and TS(function() return C_Item.GetItemNameByID(out.id) end)
    elseif kind == "macro" then
        -- by NAME: on this client a macro that resolves to a spell reports the SPELL's id here (Cast's finding), not its own index
        out.kind = "macro"
        local mname = TS(function() return GetActionText(s) end)
        local mi = mname and TN(function() return GetMacroIndexByName(mname) end)
        if mi and mi > 0 then out.id = mi end
        local okm, n, _, body = CALL(function() return GetMacroInfo(out.id) end)
        out.n = okm and WC.isPlain(n) and type(n) == "string" and n or mname
        if isCombatName(out.n) and okm and WC.isPlain(body) then
            local c, o, oi = parseCombat(body)
            if c then out.kind, out.c, out.o = "combat", spellRef(c), (o and spellRef(o)) or itemRef(oi) end
        elseif isAttackName(out.n) and okm and WC.isPlain(body) then
            -- the companion's attack macro: the SPELL it casts (the record's exact id when the record knows this slot), marked `sa`
            local n = parseAttack(body)
            if n then
                local rec = attackDB().slots[s]
                local ref = spellRef(n)
                out.kind, out.sa, out.mi, out.n = "spell", true, out.id, n
                out.id = (type(rec) == "table" and rec.n == n and rec.id) or (ref and ref.id) or nil
                out.i = (ref and ref.i) or out.i
            end
        end
    elseif kind == "summonmount" then
        out.kind = "other"      -- m33: a mount, named (still "other": the Replace box names it, as m32)
        out.mount = out.id
        local okm, n = CALL(function() return C_MountJournal.GetMountInfoByID(out.id) end)
        out.n = okm and WC.isPlain(n) and type(n) == "string" and n or nil
    else
        out.kind = "other"      -- a flyout, a companion ...: shown by its icon
    end
    return out
end

-- m33 THE EXTRA TAB'S MOUNTS (the user 2026-10-07: "Any Item from a bag (mount hearthstone etc)"; this client keeps mounts in its Mount
-- Journal, not the bags): the character's collected mounts it may show (shouldHideOnChar off), by name; and its ITEMS: every bag item with a
-- use (C_Item.GetItemSpell), once each, by name. Read-only; both travel in the padmap doc, so the app counts the very same list.
local MAX_MOUNTS, MAX_ITEMS = 80, 80
local function mountList()
    local out = {}
    local ok, ids = CALL(function() return C_MountJournal.GetMountIDs() end)
    if not ok or type(ids) ~= "table" then return out end
    for _, id in ipairs(ids) do
        if type(id) == "number" and WC.isPlain(id) then
            local okm, name, spellID, icon, _, _, _, _, _, _, hide, collected = CALL(function() return C_MountJournal.GetMountInfoByID(id) end)
            if okm and collected == true and hide ~= true and WC.isPlain(name) and type(name) == "string" then
                out[#out + 1] = { kind = "mount", id = id, n = name, i = WC.isPlain(icon) and type(icon) == "number" and icon or nil,
                                  sp = WC.isPlain(spellID) and type(spellID) == "number" and spellID or nil }
            end
        end
    end
    table.sort(out, function(a, b) return a.n < b.n end)
    while #out > MAX_MOUNTS do table.remove(out) end
    return out
end
local function itemList()
    local out, seen = {}, {}
    local okn, nb = CALL(function() return NUM_BAG_SLOTS end)
    local last = (okn and type(nb) == "number" and nb or 4) + 1                          -- + the reagent bag
    for bag = 0, last do
        local okc, n = CALL(function() return C_Container.GetContainerNumSlots(bag) end)
        for slot = 1, (okc and type(n) == "number" and WC.isPlain(n) and n or 0) do
            local oki, info = CALL(function() return C_Container.GetContainerItemInfo(bag, slot) end)
            local id = oki and type(info) == "table" and TN(function() return info.itemID end) or nil
            if id and not seen[id] then
                seen[id] = true
                local oks, use = CALL(function() return C_Item.GetItemSpell(id) end)
                if oks and WC.isPlain(use) and use ~= nil then
                    local name = TS(function() return C_Item.GetItemNameByID(id) end)
                    if name then out[#out + 1] = { kind = "item", id = id, n = name, i = TN(function() return info.iconFileID end) } end
                end
            end
        end
    end
    table.sort(out, function(a, b) return a.n < b.n end)
    while #out > MAX_ITEMS do table.remove(out) end
    return out
end
-- the Extra tab's whole list, in its order: the commands, the mounts, the items (PadTabCtl's rows and the "extra" pad row count it alike)
local function extraList()
    local out = {}
    for _, c in ipairs(commandList()) do out[#out + 1] = { kind = "command", key = c.key, n = commandName(c) } end
    for _, b in ipairs(bindList()) do out[#out + 1] = { kind = "bind", key = b.key, n = b.n } end          -- m34
    for _, m in ipairs(mountList()) do out[#out + 1] = m end
    for _, it in ipairs(itemList()) do out[#out + 1] = it end
    return out
end

local function macroCounts()
    local ok, general, char = CALL(function() return GetNumMacros() end)
    local m = {}
    if ok and WC.isPlain(general) and type(general) == "number" then m.general = { n = general } end
    if ok and WC.isPlain(char) and type(char) == "number" then m.char = { n = char } end
    -- this client keeps the limits in Constants.MacroConsts (its macro window: 120 General, 30 per character; seen on the Thor 2026-09-30 as
    -- "General Macros 2 / ?" while only the old global names were read); the old globals stay as the fallback
    local maxG = SN(function() return Constants.MacroConsts.MAX_ACCOUNT_MACROS end) or SN(function() return MAX_ACCOUNT_MACROS end)
    local maxC = SN(function() return Constants.MacroConsts.MAX_CHARACTER_MACROS end) or SN(function() return MAX_CHARACTER_MACROS end)
    if m.general then m.general.max = maxG end
    if m.char then m.char.max = maxC end
    return m
end

local function macroIndex(name)
    local i = TN(function() return GetMacroIndexByName(name) end)
    if i and i > 0 then return i end
end

local shownPage = nil           -- the page the tab shows (nil = the game's current one)
local function gamePage()
    local pu = _G.GamepadMainActionBarFrame and _G.GamepadMainActionBarFrame.PageUnit
    return pu and TN(function() return pu:GetCurrentPage() end) or 1
end

function WC.collectPadMap()
    local page = shownPage or gamePage()
    local doc = { page = page, pages = PAGES, combat = fighting() or nil, fight = fightNow.sw or nil, sets = {} }
    for set = 1, SETS do
        local row = { set = set, slots = {} }
        for pos = 1, POSITIONS do
            if fixed(set, pos) then
                row.slots[pos] = { pos = pos, kind = "fixed", n = word(FIXED[pos]), cf = faceRef(pos) }
            else
                local s = storage(page, set, pos)
                local slot = OV.view(set, pos, s)                     -- m43: a binding, a command, the bar; a fight spell over it
                slot.pos = pos
                row.slots[pos] = slot
            end
        end
        doc.sets[set] = row
    end
    -- m55: the character's forms (index order; `on` = the form now) and every form's own In-combat spells, as a flat list
    local forms = OV.forms()
    doc.forms = #forms > 0 and forms or nil
    -- m57: where the form bar lies, the form being edited, and each learned form's own bar (its eight places' pictures) for the views
    doc.stance = OV.stanceAt()
    doc.fe = OV.fe
    local fbl = {}
    local stp = doc.stance
    if stp then
        for _, fo in ipairs(forms) do
            local first = OV.fsdb()[fo.id]
            if type(first) == "number" then
                for pos = 1, POSITIONS do
                    local sl = readSlot(first + pos - 1)
                    if sl.kind ~= "none" then fbl[#fbl + 1] = { f = fo.id, p = pos, i = sl.i or 0, n = sl.n } end
                end
            end
        end
    end
    doc.fb = #fbl > 0 and fbl or nil
    local ffl = {}
    for fid, t in pairs(OV.ffdb()) do
        if type(t) == "table" then
            for pos, e in pairs(t) do
                if type(pos) == "number" and type(e) == "table" and type(e.n) == "string" then
                    ffl[#ffl + 1] = { f = fid, p = pos, id = e.id, n = e.n, i = e.i }
                end
            end
        end
    end
    doc.ff = #ffl > 0 and ffl or nil
    -- m59: X fishes while a fishing pole is in the main hand (the player's Fishing spell: id, name, icon); absent = X as it is
    doc.fish = OV.fish and { id = OV.fish.id, n = OV.fish.n, i = OV.fish.i } or nil
    -- m61: Smart Y's pick while it would act on a bare Y (out of combat, nothing targeted): id, name, icon, k = food / drink / both
    -- m62 SMART BUFFS: the rows ({ ids }), the self-buffs offered ({ id, n, i, k }), the first unsatisfied row (at), the cell row
    doc.sb = (OV.sbDoc and pcall(OV.sbDoc)) and OV.sbDocNow or nil
    doc.smarty = OV.smartyShown and { id = OV.smartyShown.id, n = OV.smartyShown.n, i = OV.smartyShown.i, k = OV.smartyShown.k } or nil
    doc.macros = macroCounts()
    doc.face = faceState()
    doc.dpad = dpadState()
    doc.start = startState and startState() or nil
    local a = attackDB()
    local nslots = 0
    for _ in pairs(a.slots) do nslots = nslots + 1 end
    -- m40 / m43: commands (m41) and fight spells on any button (m43) are the overlay now: only start attack on cast stays paused
    doc.paused = nil
    -- m52: start attack on cast is the ROUTER's now (no macro): never paused
    doc.sa = { on = attackOn(), chosen = a.chosen or nil, n = nslots, full = a.full, user = a.user, refused = a.refused, pending = a.pending,
               cols = WC.spellsAttackCols }
    doc.commands = {}
    for _, c in ipairs(commandList()) do
        local mi = macroIndex(macroName(c))
        -- m41: a command needs no macro any more (the overlay): always "made"
        doc.commands[#doc.commands + 1] = { key = c.key, n = commandName(c), i = c.icon, b = c.body, made = true }
    end
    -- m34: the bindings a button can take (after the commands), m33: the Extra tab's mounts and items, the order the room counts them
    doc.binds = bindList and bindList() or {}
    doc.mounts, doc.items = {}, {}
    for _, m in ipairs(mountList()) do doc.mounts[#doc.mounts + 1] = { id = m.id, n = m.n, i = m.i } end
    for _, it in ipairs(itemList()) do doc.items[#doc.items + 1] = { id = it.id, n = it.n, i = it.i } end
    return doc
end

-- ---------------------------------------------------------------- the documents

local lastKey = nil
local function emit(force)
    local doc = WC.collectPadMap()
    local key = WC.encode(doc)
    if key == lastKey and not force then return false end
    lastKey = key
    WC.send(WC.encode({ type = "padmap", v = PV, m = doc }))
    return true
end

local function ack(op, ok, why, s)
    WC.send(WC.encode({ type = "padack", v = PV, op = op, ok = ok and true or false, why = why, s = s }))
    WC.push()
    -- capture v8: a refusal because of the fight pulses Home's red flash (Book.lua's helper; a no-op when Spells does not hold)
    if why == "combat" and type(WC.spellsRefusedCombat) == "function" then WC.spellsRefusedCombat() end
end

local queued = false
local function soon()
    if queued then return end
    queued = true
    C_Timer.After(0.3, function() queued = false; if emit(false) then WC.push() end end)
end

-- ---------------------------------------------------------------- the actions

-- the checks every change makes first; the slot must be a real, changeable one
local function target(page, set, pos)
    if inCombat() then return nil, "combat" end
    if type(page) ~= "number" or page < 1 or page > PAGES or type(set) ~= "number" or set < 1 or set > SETS
        or type(pos) ~= "number" or pos < 1 or pos > POSITIONS then return nil, "bad place" end
    if fixed(set, pos) then return nil, "fixed" end
    local s = storage(page, set, pos)
    if not s then return nil, "no slot" end
    return s
end

-- a book slot's spell: its id and the name the game gives it (the combat macro names spells)
local function bookSpell(bookSlot)
    if type(bookSlot) ~= "number" then return nil end
    local okI, info = CALL(function() return C_SpellBook.GetSpellBookItemInfo(bookSlot, BANK_PLAYER) end)
    local id = okI and type(info) == "table" and TN(function() return info.spellID end)
    local name = id and TS(function() return C_Spell.GetSpellName(id) end)
    if id and name then return id, name end
end

-- the companion's combat macro is deleted once no action slot holds it (the 30 character macros are few); `except` = the slot just changed
local LAST_ACTION_SLOT = 360
local function dropIfUnused(mi, except)
    if type(mi) ~= "number" then return end
    local okN, n, _, body = CALL(function() return GetMacroInfo(mi) end)
    if not (okN and (isCombatName(n) or isAttackName(n))) then return end   -- only ever our own
    if isAttackName(n) and not (WC.isPlain(body) and parseAttack(body)) then return end   -- a user's macro of that name: never
    for a = 1, LAST_ACTION_SLOT do
        if a ~= except then
            local okA, kind = CALL(function() return GetActionInfo(a) end)
            if okA and kind == "macro" then
                local an = TS(function() return GetActionText(a) end)
                if an == n then return end
            end
        end
    end
    CALL(function() return DeleteMacro(mi) end)
end
-- m32 (MOVE): a companion macro keeps the name of the slot it was MADE for ("DS Combat 197"), and a Move can carry it onto another slot.
-- A name is therefore reused only while no OTHER slot holds that macro; otherwise the next free one ("DS Combat 197b", ...) is made.
local function heldElsewhere(name, s)
    for a = 1, LAST_ACTION_SLOT do
        if a ~= s then
            local okA, kind = CALL(function() return GetActionInfo(a) end)
            if okA and kind == "macro" and TS(function() return GetActionText(a) end) == name then return true end
        end
    end
    return false
end
local function slotName(prefix, s)
    local base = prefix .. s
    for _, suf in ipairs({ "", "b", "c", "d", "e", "f" }) do
        if not heldElsewhere(base .. suf, s) then return base .. suf end
    end
    return base .. "z"
end

-- ---------------------------------------------------------------- the base face buttons in fights
-- The user 2026-10-01: "Yes, per button": in a FIGHT the no-trigger X / Y / A cast a spell the user picked; ZL / ZR / ZL+ZR + X / Y / A keep
-- their own layer's buttons; out of a fight they are Interact / Inspect / Jump, UNTOUCHED; B stays the game's Back.
-- MEASURED (m7 on the Thor, 2026-10-01 06:22, the user: "My character jumped"): an OVERRIDE binding (a secure header's) LOSES on these keys.
-- In controller mode the game keeps PAD1 / 3 / 4 in its own binding CONTEXT (Enum.BindingContext.GamepadModeInGameCore, blizzard_gamepad
-- core.lua), wired to its scripted top-bar buttons (mainactionbarframe.lua: SetupNonBindableJump etc.); Interact measured the game re-taking
-- PAD1 37 times in 29 s (2026-09-28). GetBindingAction(key, true) cannot see that context (it read "" even out of combat).
-- HOW NOW (the user 2026-10-01: "Try the deeper route", in its fight-only form): the game's OWN key setting, in that context, only for the
-- length of each fight. PLAYER_REGEN_DISABLED fires BEFORE the combat lockdown (MEASURED on the Thor: InCombatLockdown() false inside it,
-- true 0.5 s later), so there SetBinding(key, "CLICK <our button>:LeftButton", context) points the bare key at the companion's own
-- SecureActionButton (type spell). The three trigger names (GAMEPAD_MOD_* .. "-" .. key) that have nothing of their own are pointed at the
-- key's old action first, so ZL / ZR + A keep their layers (they used to fall back to the bare key). PLAYER_REGEN_ENABLED (after the lockdown)
-- puts back every key that is still ours, as it was.
-- NEVER SAVED: no SaveBindings anywhere (the game saves only from its settings screens). What is changed is written to
-- WowCompanionSpellsDB.faceSwap BEFORE the change, so a crash / reload mid-fight is repaired at the next PLAYER_ENTERING_WORLD out of combat.
-- Every change is read back in the context (GetBindingAction(key, false, context): the form the game's keybinding screen uses);
-- `armed` = the positions whose key points at our button now, with the layer that took it ("g" the gamepad context; "n" = the gamepad
-- context refused our button, so the key was freed there for the fight and pointed at our button in the plain one: the FALLBACK). Nothing of Blizzard's is called but the game's own key-setting functions.
local function readKey(k, ctx)
    local ok, v = pcall(GetBindingAction, k, false, ctx)
    return (ok and type(v) == "string") and v or ""
end
local function setKey(k, action, ctx)
    local ok, r = pcall(SetBinding, k, action, ctx)
    return ok and r ~= false
end
local function bindCtx()
    local e = _G.Enum and _G.Enum.BindingContext
    return e and e.GamepadModeInGameCore or nil
end
-- m36 PER CHARACTER (the user 2026-10-07, after other characters' fight spells replaced the Shaman's: "we just need to figure out a way to make the keybinds per charactor specific"): the base X / Y / A
-- fight spells are the character's (WowCompanionSpellsDB.faceBy[name-realm]). The old shared record (whichever character set it last) is
-- dropped (the user: "I dont need anything restored"): every character starts with none and is offered its class's starter set (m23).
local faceScratch = {}
local function faceStore()
    if type(_G.WowCompanionSpellsDB) ~= "table" then _G.WowCompanionSpellsDB = {} end
    local db = _G.WowCompanionSpellsDB
    db.combatFace = nil
    local k = whoKey()
    if not k then return faceScratch end                                -- no character yet (before login): never saved
    if type(db.faceBy) ~= "table" then db.faceBy = {} end
    if type(db.faceBy[k]) ~= "table" then db.faceBy[k] = {} end
    return db.faceBy[k]
end
-- (m45: the m23 starter suggestion and its STARTER table are removed; the user: nothing is ever suggested)
local function bookByName()
    local out, order, gap = {}, {}, 0
    for i = 1, 400 do
        local okI, info = CALL(function() return C_SpellBook.GetSpellBookItemInfo(i, BANK_PLAYER) end)
        if okI and type(info) == "table" then
            gap = 0
            local id = TN(function() return info.spellID end)
            local passive = TB(function() return info.isPassive end) == true
            local name = id and TS(function() return C_Spell.GetSpellName(id) end)
            if name and not passive and not out[name] then out[name] = { slot = i, id = id }; order[#order + 1] = name end
        else
            gap = gap + 1
            if gap > 20 then break end
        end
    end
    return out, order
end
-- ---------------------------------------------------------------- the D-pad room bar's choice (m25), per character
-- The bars and keys are the CHARACTER's: the choice is kept per character (name-realm) in the saved file (m24's `true` = the user's yes to
-- Home's prompt, carried over as yes to the room bar)
local function dpadDB()
    if type(_G.WowCompanionSpellsDB) ~= "table" then _G.WowCompanionSpellsDB = {} end
    local db = _G.WowCompanionSpellsDB
    if type(db.dpad) ~= "table" then db.dpad = {} end
    return db.dpad
end
-- true / false / nil (never asked)
local function dpadWant()
    local k = whoKey()
    local v = k and dpadDB()[k]
    if v == true or v == false then return v end
end
-- m27 COMBAT CONTROLS (the user 2026-10-05, the Controller room's toggle: "Yes, exactly that"): OFF = A / X / Y are always the game's own
-- (Jump / Interact / Inspect), in fights too; the chosen fight spells are KEPT for when it is on again. Per character, ON unless turned off.
-- m34 the bindings' store: per character, "set:pos" -> the binding's name
local function bindsDB()
    if type(_G.WowCompanionSpellsDB) ~= "table" then _G.WowCompanionSpellsDB = {} end
    local db = _G.WowCompanionSpellsDB
    if type(db.binds) ~= "table" then db.binds = {} end
    return db.binds
end
local function bindStore()
    local k = whoKey()
    if not k then return {} end
    local all = bindsDB()
    if type(all[k]) ~= "table" then all[k] = {} end
    return all[k]
end
local function bindAt(set, pos) return bindStore()[set .. ":" .. pos] end
local function bindByKey(k) for _, b in ipairs(BINDS) do if b.key == k then return b end end end
local function bindName(b) return word("BINDING_NAME_" .. b.key) or b.n end
-- may this button take a binding? true, or false + why ("fixed" the game's four, "dpad bar" the DS toolbar's D-pad, "layer" ZR / ZL+ZR)
local function bindAllowed(set, pos)
    if set == 1 and pos >= FIRST_RESERVED then return false, "fixed" end
    if set ~= 1 and set ~= 2 then return false, "layer" end
    if set == 1 and dpadWant() == true then return false, "dpad bar" end
    return true
end
-- ---------------------------------------------------------------- m41 S2 (commands): THE OVERLAY
-- The user's ruling 2026-10-09 (no trace in the player's setup) and pick ("overlay, keep all"): a COMMAND on a button is no macro any
-- more. It is this character's overlay entry for that bar slot (WowCompanionSpellsDB.cmdBy[name-realm][slot] = { k, page, set, pos }),
-- run by the ROUTER: its own secure button on the bare key reads the live slot under the press (every layer and page, in fights too: m26's
-- per-slot macro text, "wc-mt-<slot>") and runs the command's text. The player's own bar slot underneath is never touched (what a PC
-- shows); Clear takes the overlay off. Kept only in our SavedVariables; nothing of it exists while the add-on is off.
OV = {}                                 -- (one name: the main chunk's locals are counted, Lua's 200)
function OV.db()
    if type(_G.WowCompanionSpellsDB) ~= "table" then _G.WowCompanionSpellsDB = {} end
    local db = _G.WowCompanionSpellsDB
    if type(db.cmdBy) ~= "table" then db.cmdBy = {} end
    local k = whoKey()
    if not k then return {} end
    if type(db.cmdBy[k]) ~= "table" then db.cmdBy[k] = {} end
    return db.cmdBy[k]
end
function OV.byKey(key) for _, c in ipairs(COMMANDS) do if c.key == key then return c end end end
function OV.at(s)
    local e = OV.db()[s]
    return (type(e) == "table" and OV.byKey(e.k)) and e or nil
end
-- m43 FIGHT SPELLS AS THE OVERLAY (the user 2026-10-09: "I should be able to change the combat spells while holding any trigger
-- modification"): a button's fight spell is this character's entry fightBy[name-realm][slot] = { id, n, i, page, set, pos }; nothing of
-- the player's changes (no "DS Combat" macro, no bar write). In fights the router (which carries every key from the fight's start) runs
-- "/cast <spell>" from its header ("wc-ft-<slot>") for that slot; out of combat the button is the player's own. Combat controls off = none.
function OV.ftdb()
    if type(_G.WowCompanionSpellsDB) ~= "table" then _G.WowCompanionSpellsDB = {} end
    local db = _G.WowCompanionSpellsDB
    if type(db.fightBy) ~= "table" then db.fightBy = {} end
    local k = whoKey()
    if not k then return {} end
    if type(db.fightBy[k]) ~= "table" then db.fightBy[k] = {} end
    return db.fightBy[k]
end
function OV.ftAt(s)
    local e = OV.ftdb()[s]
    return (type(e) == "table" and type(e.n) == "string") and e or nil
end
-- m55 IN-COMBAT SETS PER FORM (f-combat-forms; the user 2026-10-15: "we need to figure out a way to have dynamic in combat bars that support
-- changing forms", then the pick "1: A set per form"): a druid's Bear / Cat / ..., a warrior's stances, a rogue's Stealth each get their OWN
-- In-combat spells for the BARE set (the one the game swaps per form: the triggers' sets stay the caster's), this character's entry
-- formFightBy[name-realm][form spell id][pos] = { id, n, i } (pos 1-4 the D-pad, 5 / 6 / 8 the faces; B never). A form without its own
-- spell on a position uses the caster's (the D-pad's slot fight spell, the faces' own). Kept by spell id, so learning a new form (which
-- renumbers the forms) never moves a set; the router reads the form NOW from a state driver ([form:N], in fights too).
function OV.forms()
    local out = {}
    local ok, n = pcall(GetNumShapeshiftForms)
    n = (ok and type(n) == "number") and n or 0
    for x = 1, math.min(n, 10) do
        local okf, icon, active, _, sid = pcall(GetShapeshiftFormInfo, x)
        if okf and type(sid) == "number" and sid > 0 then
            local nm = TS(function() return C_Spell.GetSpellName(sid) end)
            -- m56 (the user on test20: "when transformed into a bear, the bear icon changes to the untransform button so its not totally
            -- clear that its the bear options"): the form's OWN picture, its spell's original icon (GetShapeshiftFormInfo gives the
            -- cancel-form icon while the form is on); the shapeshift icon only when the spell has none
            local okt, cur, orig = pcall(C_Spell.GetSpellTexture, sid)
            local fixedIcon = (okt and type(orig) == "number" and orig > 0 and orig) or (not active and type(icon) == "number" and icon)
                or (okt and type(cur) == "number" and cur > 0 and cur) or nil
            icon = fixedIcon or icon
            out[#out + 1] = { x = x, id = sid, n = nm, i = type(icon) == "number" and icon or nil, on = (active == true) or nil,
                              bar = type(OV.fsdb()[sid]) == "number" or nil }      -- m57: its own bar's slots are known
        end
    end
    return out
end
-- m57 THE FORMS' OWN BARS OUT OF COMBAT (the user's pick A on test20: "The form options need to also apply to the not in combat bars", the
-- game's own form bars): in a form the game lays its STANCE BAR (eight storage slots of the form's own) over ONE of the sets - the one its
-- Controller setting GamepadStanceBarOverride names (page 1 Left / Right / Bottom, pages 2-3 Top / Left / Right / Bottom; "None" = no form
-- bar on the controller). The game names only the ACTIVE form's first slot (C_GamepadUI ...ForActiveStance), so each form's is learned in
-- that form and kept for this character (formSlotsBy[name-realm][form spell id] = first slot). With a form chosen in the room (OV.fe) that
-- set's places ARE the form's own slots (storage()): read, put, cleared the game's own way; an empty one stays empty (the game's rule)
function OV.fsdb()
    if type(_G.WowCompanionSpellsDB) ~= "table" then _G.WowCompanionSpellsDB = {} end
    local db = _G.WowCompanionSpellsDB
    if type(db.formSlotsBy) ~= "table" then db.formSlotsBy = {} end
    local k = whoKey()
    if not k then return {} end
    if type(db.formSlotsBy[k]) ~= "table" then db.formSlotsBy[k] = {} end
    return db.formSlotsBy[k]
end
-- where the form bar lies: { pg, b } (the Cast replica's sets: 1 none, 2 LT, 3 RT, 4 LT + RT), nil = "None"
function OV.stanceAt()
    local v = tonumber(TS(function() return C_CVar.GetCVar("GamepadStanceBarOverride") end) or "")
    if not v or v <= 1 or v > 12 then return nil end
    if v <= 4 then return { pg = 1, b = v } end                       -- 2-4: page 1 Left / Right / Bottom = LT / RT / LT + RT
    return { pg = math.floor((v - 5) / 4) + 2, b = (v - 5) % 4 + 1 }   -- 5-12: pages 2-3, Top / Left / Right / Bottom
end
-- in a form now: its bar's first slot, kept
function OV.formLearn()
    local idx = TN(function() return GetShapeshiftForm() end)
    if not idx or idx <= 0 then return end
    local okf, _, _, _, sid = pcall(GetShapeshiftFormInfo, idx)
    local first = TN(function() return C_GamepadUI.GetFirstGamepadActionBarStorageSlotIndexForActiveStance() end)
    if okf and type(sid) == "number" and sid > 0 and first and first > 0 and OV.fsdb()[sid] ~= first then
        OV.fsdb()[sid] = first
        soon()
    end
end
-- the chosen form's slot for (page, set, pos): a number, false (that form's bar not learned yet), nil (not the form bar's set)
function OV.feSlot(page, set, pos)
    local st = OV.stanceAt()
    if not st or st.pg ~= page or st.b ~= set or not OV.fe then return nil end
    local first = OV.fsdb()[OV.fe]
    if type(first) ~= "number" then return false end
    return first + pos - 1
end
function OV.ffdb()
    if type(_G.WowCompanionSpellsDB) ~= "table" then _G.WowCompanionSpellsDB = {} end
    local db = _G.WowCompanionSpellsDB
    if type(db.formFightBy) ~= "table" then db.formFightBy = {} end
    local k = whoKey()
    if not k then return {} end
    if type(db.formFightBy[k]) ~= "table" then db.formFightBy[k] = {} end
    return db.formFightBy[k]
end
function OV.ffAt(form, pos)
    local t = OV.ffdb()[form]
    local e = type(t) == "table" and t[pos]
    return (type(e) == "table" and type(e.n) == "string") and e or nil
end
-- a form this character has (by spell id), or nil
function OV.formOf(id)
    for _, f in ipairs(OV.forms()) do if f.id == id then return f end end
end

-- what a button shows: a binding, else its command or its bar slot; with a fight spell it reads as a combat button ("ov": the overlay's),
-- its Otherwise being whatever the button holds
function OV.view(set, pos, s)
    local b = bindSlot and bindSlot(set, pos, s)
    if b then return b end
    local base = (s and cmdSlot and cmdSlot(s)) or (s and readSlot(s)) or { kind = "none" }
    local e = s and OV.ftAt(s)
    if not e then return base end
    local c = { n = e.n, id = e.id, i = e.i }
    if base.kind == "combat" then base.c = c; base.ov = true; return base end      -- an old "DS Combat" slot: the overlay's spell wins
    local o = nil
    if base.kind == "spell" then o = { n = base.n, id = base.id, i = base.i }
    elseif base.kind == "item" then o = { n = base.n, id = base.id, i = base.i, item = true }
    elseif base.kind ~= "none" then o = { n = base.n or base.key, i = base.i } end
    return { kind = "combat", s = s, c = c, o = o, ov = true, base = base.kind, key = base.key, sa = base.sa }
end
cmdSlot = function(s)
    local e = OV.at(s)
    if not e then return nil end
    local c = OV.byKey(e.k)
    return { kind = "command", key = c.key, n = commandName(c), i = c.icon, b = c.body, s = s, ov = true }
end
-- the positions whose bare key the router owns for the overlay (any page / set): pos -> true
-- the player's own spell / item / mount put on a slot takes the overlay command off it (it is what the button does now)
function OV.off(s)
    if OV.db()[s] == nil then return end
    OV.db()[s] = nil
    if routerMacrosSoon then pcall(routerMacrosSoon) end
    OV.rebind = true
end
function OV.positions()
    local out = {}
    for _, e in pairs(OV.db()) do if type(e) == "table" and type(e.pos) == "number" and OV.byKey(e.k) then out[e.pos] = true end end
    return out
end
bindSlot = function(set, pos, s)
    local k = bindAt(set, pos)
    local b = k and bindByKey(k)
    if not b then return nil end
    return { kind = "bind", key = b.key, n = bindName(b), i = b.icon, s = s }
end
bindList = function()
    local out = {}
    for _, b in ipairs(BINDS) do out[#out + 1] = { key = b.key, n = bindName(b), i = b.icon } end
    return out
end
local function ccDB()
    if type(_G.WowCompanionSpellsDB) ~= "table" then _G.WowCompanionSpellsDB = {} end
    local db = _G.WowCompanionSpellsDB
    if type(db.ccOff) ~= "table" then db.ccOff = {} end
    return db.ccOff
end
local function combatOn()
    local k = whoKey()
    return not (k and ccDB()[k] == true)
end
-- m29 THE D-PAD LAYOUT ("DS WoW toolbar", the user's design 2026-10-05, replacing m25's left / right room bar): the bare D-pad position ->
-- Home's plain button (Hub.lua hub5: each click one barcmd; no protected call, so the router's secure /click works in fights) and what it
-- does: right = Home's map module, left = the Chat room, down = the bar cursor on the swirl, up = the consumables radial (batch b).
-- IN AND OUT OF COMBAT (the user: "Work in fights too").
local ROOMBAR = { [1] = { btn = "WowCompanionPadChat", dir = "chat" }, [2] = { btn = "WowCompanionPadRadial", dir = "radial" },
                  [3] = { btn = "WowCompanionPadMap", dir = "map" }, [4] = { btn = "WowCompanionPadHome", dir = "home" } }
-- ---------------------------------------------------------------- THE ROUTER (m20)
-- The user 2026-10-01 "Build the router now", SPEC_COMBAT items 1-3. MEASURED: since the game's build changed at 18:15 (1.60.1.70009 ->
-- 70170) its gamepad context ignores every change of A / X / Y (m16 - m18), so the per-fight key switch (m9 - m17) can no longer work.
-- The pattern that does work on 1.60.1 (ControllerForever, the user's example; our m19 measured the press reaching an override's button):
-- A / X / Y get PERMANENT priority OVERRIDE bindings (set out of combat, never changed in a fight) to the companion's own secure buttons.
-- Each button decides PER PRESS in a secure pre-click snippet (allowed in a fight: attributes only, no binding changes):
--   * a trigger held (LT / RT / both, read from GetGamePadState) -> Forever's own button on that layer's bar (ZL / ZR combos use the bars);
--   * a bare press -> the fight spell while the fight state is on (the game's [combat] driver, or start attack / auto-repeat before the
--     fight: "wc-pre"), unless given back (B: "wc-given") -> else Forever's own button: Jump / Interact / Inspect, and whatever bar
--     Forever shows on the top anchor (a targeting bar while LB / RB is held, the possess bar...).
--   * Forever's buttons get BOTH edges: "/click <its button> LeftButton 1" on the key-down, "... 0" on the key-up (pressAndHoldAction +
--     typerelease), so holding A keeps the game's own hold behaviour (swimming up, ascending).
--   * reserved for Interact (rooms, SPEC items 6 / 7 / 9): "wc-mode" = "room" delivers the press to "wc-room-dest" (a click with the key).
-- STEPS ASIDE (out of combat, checked every 0.5 s): while a room holds the controller (capture.state().holder) or a game window is open,
-- A / X / Y are left to their owner (no fight spell on a key a room or a window uses: Cast's rule). At a fight's start (before the lockdown)
-- the router binds again unless a game window is open (rooms let go at a fight's start). Nothing is ever saved (overrides never are).
FACE = { [5] = true, [6] = true, [8] = true }
local face = { buttons = {}, pending = false, keys = {}, armed = {}, late = nil,
               aa = false, ar = false, fight = false, swapped = false, given = false, refused = nil, probe = nil }
local R = { hdr = nil, owner = nil, bound = {}, aside = nil, wrapped = {}, driven = {}, ticker = nil, lastBind = 0, give = nil,
            keys = {}, dbtn = {}, carry = {} }
local function T(fn) local ok, v = pcall(fn); if ok then return v end end      -- a value (a frame, a table) or nil
-- m30 (the rooms consistency pass, the user after b74): who holds the shared capture right now (nil = nobody), said on every press that
-- reaches the router, so Home's audit can tell "no room held" from a binding-order problem
local function capHolder()
    local ok, st = pcall(function() return WC.capture.state() end)
    return ok and type(st) == "table" and type(st.holder) == "string" and st.holder or nil
end
local ANCHORS = { top = "TopCenteredAnchor", left = "LeftCenteredAnchor", right = "RightCenteredAnchor", bottom = "BottomCenteredAnchor" }
-- the secure pre-click snippet (restricted Lua; runs before the button's own click, on the key-down AND the key-up)
local PRE = [=[
    if down then
        local lt, rt = false, false
        local st = GetGamePadState and GetGamePadState()
        local bt = st and st.buttons
        if bt then
            local li, ri = self:GetAttribute("wc-lt"), self:GetAttribute("wc-rt")
            lt = (li and bt[li]) and true or false
            rt = (ri and bt[ri]) and true or false
        end
        local anchor = "top"
        if lt and rt then anchor = "bottom" elseif lt then anchor = "left" elseif rt then anchor = "right" end
        local n = self:GetAttribute("wc-n-" .. anchor) or 0
        local dest, at = nil, nil
        for i = n, 1, -1 do
            local bar = self:GetFrameRef("wc-bar-" .. anchor .. "-" .. i)
            if bar and bar:IsShown() then dest = self:GetAttribute("wc-dest-" .. anchor .. "-" .. i); at = i; break end
        end
        if not dest then dest = self:GetAttribute("wc-dest-" .. anchor .. "-1"); at = 1 end
        local mode = self:GetAttribute("wc-mode")
        -- m26 (the user 2026-10-05: "the non combat skill bars wont use my spells"): A MACRO CANNOT RUN ANOTHER MACRO on this client
        -- (ControllerForever's author measured it too), so a "/click" of Forever's button that holds a macro (the start-attack "DS Attack"
        -- macros, the combat buttons, the player's own) does nothing. Its macro text, kept per action slot on the header out of combat,
        -- runs here instead: the button's slot is read live (its ID: Forever's paging sets it), so a page change is followed
        local mt, ft, sa = nil, nil, nil
        if dest and mode ~= "room" then
            local nb = self:GetFrameRef("wc-btn-" .. anchor .. "-" .. at)
            local hd = self:GetFrameRef("wc-hdr")
            if nb and hd and nb.GetID then
                local id = nb:GetID()
                if id and id > 0 then
                    mt = hd:GetAttribute("wc-mt-" .. id); ft = hd:GetAttribute("wc-ft-" .. id)   -- m43: its fight spell
                    sa = hd:GetAttribute("wc-sa-" .. id)                  -- m52: its start-attack lines (a harmful spell, the switch on)
                end
            end
        end
        local on = self:GetAttribute("wc-state") == "fight" or self:GetAttribute("wc-pre")
        -- m53 (p-druid-lt-lb, a druid: forms): a FORM's / stance's bar laid over the top anchor is still the no-trigger press (its
        -- buttons are the form's own): fight spells, the D-pad toolbar and B's give-back keep working in Bear / Cat / a stance
        local bare = anchor == "top" and (at == 1 or self:GetAttribute("wc-stance-top-" .. at) == true)
        local role = self:GetAttribute("wc-role")
        local rearmed = false
        if role == "back" then
            -- B (SPEC item 2, the user: "when I push b it goes back to the non combat layout even if im in combat"): a bare B in the
            -- fight state gives A / X / Y back (their attributes, through frame refs), then does Forever's own Back
            if bare and on and mode ~= "room" then
                for _, p in ipairs(newtable(1, 2, 3, 4, 5, 6, 8)) do
                    local f = self:GetFrameRef("wc-face-" .. p)
                    if f then f:SetAttribute("wc-given", true) end
                end
            end
        elseif on and self:GetAttribute("wc-given") and mode ~= "room" and SecureCmdOptionParse
            and SecureCmdOptionParse("[harm,nodead] 1") == "1" and (not bare or self:GetAttribute("wc-interact")) then
            -- "Starting an attack gets them back": X as Interact on a living hostile target, or a layer press on one, re-arms A / X / Y;
            -- this press itself stays the attack it is (Forever's own button)
            for _, p in ipairs(newtable(1, 2, 3, 4, 5, 6, 8)) do
                local f = self:GetFrameRef("wc-face-" .. p)
                if f then f:SetAttribute("wc-given", nil) end
            end
            rearmed = true
        end
        local fight = role ~= "back" and not rearmed and bare and mode ~= "room" and self:GetAttribute("wc-fight-type")
            and on and not self:GetAttribute("wc-given")
        -- m29 THE D-PAD LAYOUT: a bare D-pad press, while the toolbar is on -> Home's button, once a press, IN and out of fights
        -- (a room in room mode and a trigger / targeting / possess bar still get it first)
        local rb = self:GetAttribute("wc-roombar")
        if not bare or mode == "room" then rb = nil end
        -- m44 (the user's ruling after the fresh-install test, 2026-10-12: "in combat, it should do what ever I have in the in combat
        -- section"): in a fight a bare D-pad press whose slot has an In-combat spell (the overlay's fight spell) casts it; a D-pad slot
        -- without one keeps DS Forever's action. Old "DS Combat" macros do not count: "Clean up this character" turns them into
        -- overlay fight spells (the user's pick: "Clean them up")
        -- m49: the overlay's fight spells (the D-pad's and every slot's) switch EXACTLY as A / X / Y's: in combat, or an attack /
        -- auto-repeat running and not given back (faceWant)
        -- m50 (the user on test21, "Switch everything back"): B's give-back wins in a fight too, exactly as for A / X / Y
        local sw = (self:GetAttribute("wc-state") == "fight" or self:GetAttribute("wc-pre")) and not self:GetAttribute("wc-given")
        -- m55 IN-COMBAT SETS PER FORM: in a form (the header's "wc-form", a state driver on [form:N]) a bare press takes the FORM's own
        -- In-combat spell for this position ("wc-ff-<form>-<pos>", the faces' too); a D-pad position without one takes the caster's
        -- ("wc-ffc-<pos>": the form's bar has other slots, whose own fight spells are none); the faces without one keep their fight spell
        if bare and mode ~= "room" and role ~= "back" then
            local fh = self:GetFrameRef("wc-hdr")
            local fm = fh and fh:GetAttribute("wc-form")
            local fp = self:GetAttribute("wc-pos")
            if fm and fm ~= 0 and fp then
                local own = fh:GetAttribute("wc-ff-" .. fm .. "-" .. fp)
                if own then ft = own; fight = false
                elseif not ft and not fight then ft = fh:GetAttribute("wc-ffc-" .. fp) end
            end
        end
        if rb and sw and dest and ft then rb = nil end
        -- m59 FISHING ON X (the user: "When the fishing pole is equipped I want to change X from the auto attack / interact to be fishing /
        -- interact"): a bare X out of the fight switch with a pole in the main hand ("wc-fish" = the Fishing spell's name, set out of
        -- combat) casts Fishing; while Fishing channels (the bobber is out) it stays Forever's Interact, so the same X loots the bobber
        local fish = bare and mode ~= "room" and role ~= "back" and not sw and self:GetAttribute("wc-fish")
        if fish and SecureCmdOptionParse and SecureCmdOptionParse("[channeling] 1") == "1" then fish = nil end
        -- m60 (the user on test6: "When I walk up to vendors and stuff x doesn't work on them. But it works with the fishing"): something
        -- to interact with comes first - the game's soft-interact target (the NPC / object / bobber the controller's Interact would use,
        -- the macro conditional's own unit) - so X fishes only when there is nothing there. Read through the secure conditional parser
        -- only (no unit API in the snippet); a client without @softinteract parses it as nothing: fishing as before
        if fish and SecureCmdOptionParse and SecureCmdOptionParse("[@softinteract,exists] 1") == "1" then fish = nil end
        -- m61 SMART Y: a bare Y out of combat with nothing targeted (no target, no soft-interact target) uses the bag's pick
        -- ("wc-smarty" = "item:<id>", set out of combat); anything else and Y is exactly as before (Forever's Inspect)
        local smart = bare and mode ~= "room" and role ~= "back" and not sw and self:GetAttribute("wc-smarty")
        if smart and (not SecureCmdOptionParse or SecureCmdOptionParse("[combat] 1") == "1"
            or SecureCmdOptionParse("[@target,exists] 1") == "1" or SecureCmdOptionParse("[@softinteract,exists] 1") == "1") then
            smart = nil
        end
        local mb = "LeftButton"
        self:SetAttribute("unit", nil)                                  -- m62: only Smart Y's buff casts on yourself
        if mode == "room" and self:GetAttribute("wc-room-dest") then
            dest = self:GetAttribute("wc-room-dest"); mb = self:GetAttribute("wc-key") or mb; fight = false
        end
        self:SetAttribute("wc-mb", mb)
        if fight then
            self:SetAttribute("wc-kind", "fight")
            self:SetAttribute("pressAndHoldAction", nil)
            self:SetAttribute("typerelease", nil)
            self:SetAttribute("type", self:GetAttribute("wc-fight-type"))
            self:SetAttribute("spell", self:GetAttribute("wc-fight-spell"))
            self:SetAttribute("macrotext", self:GetAttribute("wc-fight-macro"))
        elseif fish then
            self:SetAttribute("wc-kind", "fish")
            self:SetAttribute("pressAndHoldAction", nil)
            self:SetAttribute("typerelease", nil)
            self:SetAttribute("type", "spell")
            self:SetAttribute("spell", fish)
        elseif smart then
            -- m62: food / water (an item), a buff row's one buff (a spell on yourself), or an OR row (the app's wheel, by Hub's button)
            local st = self:GetAttribute("wc-smarty-t")
            self:SetAttribute("wc-kind", "smarty")
            self:SetAttribute("pressAndHoldAction", nil)
            self:SetAttribute("typerelease", nil)
            if st == "spell" then
                self:SetAttribute("type", "spell"); self:SetAttribute("spell", smart); self:SetAttribute("unit", "player")
            elseif st == "or" then
                self:SetAttribute("type", "macro"); self:SetAttribute("macrotext", "/click WowCompanionPadSmartyOr")
            else
                self:SetAttribute("type", "item"); self:SetAttribute("item", smart)
            end
        elseif rb then
            self:SetAttribute("wc-kind", "roombar")
            self:SetAttribute("pressAndHoldAction", nil)
            self:SetAttribute("typerelease", nil)
            self:SetAttribute("type", "macro")
            self:SetAttribute("macrotext", "/click " .. rb)
        elseif dest and ft and sw and mode ~= "room" then
            -- m43: the slot's fight spell (the overlay), in fights only; out of combat the button is the player's own
            self:SetAttribute("wc-kind", "slotfight")
            self:SetAttribute("wc-at", dest)
            self:SetAttribute("pressAndHoldAction", nil)
            self:SetAttribute("typerelease", nil)
            self:SetAttribute("type", "macro")
            self:SetAttribute("macrotext", ft)
        elseif dest and mt and mode ~= "room" then
            self:SetAttribute("wc-kind", "slotmacro")
            self:SetAttribute("wc-at", dest)
            self:SetAttribute("pressAndHoldAction", nil)
            self:SetAttribute("typerelease", nil)
            self:SetAttribute("type", "macro")
            self:SetAttribute("macrotext", mt)
        elseif dest then
            self:SetAttribute("wc-kind", "native")
            self:SetAttribute("wc-at", dest)
            self:SetAttribute("type", "macro")
            self:SetAttribute("typerelease", "macro")
            self:SetAttribute("pressAndHoldAction", true)
            -- m52 START ATTACK ON CAST: the attack lines run first, then the game's own button (its spell, rank, cooldown, hold)
            self:SetAttribute("macrotext", (sa or "") .. "/click " .. dest .. " " .. mb .. " 1")
        else
            self:SetAttribute("wc-kind", "none")
            self:SetAttribute("type", nil)
            self:SetAttribute("typerelease", nil)
        end
    elseif self:GetAttribute("wc-kind") == "native" then
        self:SetAttribute("macrotext", "/click " .. self:GetAttribute("wc-at") .. " " .. (self:GetAttribute("wc-mb") or "LeftButton") .. " 0")
    end
]=]
-- the header: owns the bindings, the fight driver that clears "given back" at each fight's start, and the secure give-back button
local GIVE = [=[
    for _, pos in ipairs(newtable(1, 2, 3, 4, 5, 6, 8)) do
        local b = self:GetFrameRef("wc-face-" .. pos)
        if b then b:SetAttribute("wc-given", true) end
    end
]=]
local REARMBODY = [=[
    for _, pos in ipairs(newtable(1, 2, 3, 4, 5, 6, 8)) do
        local b = self:GetFrameRef("wc-face-" .. pos)
        if b then b:SetAttribute("wc-given", nil) end
    end
]=]
local routerAfterClick
local ONFIGHT = [=[
    if newstate == "1" then
        for _, pos in ipairs(newtable(1, 2, 3, 4, 5, 6, 8)) do
            local b = self:GetFrameRef("wc-face-" .. pos)
            if b then b:SetAttribute("wc-given", nil) end
        end
    end
]=]
local routerMacrosSoon
local function routerHeader()
    if R.hdr then return R.hdr end
    local ok = pcall(function()
        R.hdr = CreateFrame("Frame", "WowCompanionFaceRouter", UIParent, "SecureHandlerStateTemplate")
        R.hdr:SetAttribute("_onstate-wcfight", ONFIGHT)
        RegisterStateDriver(R.hdr, "wcfight", "[combat] 1; 0")
        -- m55: the shapeshift form now (0 = none), for the per-form In-combat sets; a state driver, so it follows forms in fights too
        R.hdr:SetAttribute("_onstate-wcform", [=[ self:SetAttribute("wc-form", tonumber(newstate) or 0) ]=])
        RegisterStateDriver(R.hdr, "wcform", "[form:1] 1; [form:2] 2; [form:3] 3; [form:4] 4; [form:5] 5; [form:6] 6; [form:7] 7; [form:8] 8; [form:9] 9; [form:10] 10; 0")
        -- SPEC item 2: a secure click on this button gives A / X / Y back IN a fight (Book's give-back cell clicks it)
        R.give = CreateFrame("Button", "WowCompanionFaceGiveBack", UIParent, "SecureHandlerClickTemplate")
        R.give:RegisterForClicks("AnyDown")
        R.give:SetAttribute("_onclick", GIVE)
        -- m21: the start-attack macros click this (in a fight too): A / X / Y re-armed
        R.rearm = CreateFrame("Button", REARM, UIParent, "SecureHandlerClickTemplate")
        R.rearm:RegisterForClicks("AnyDown", "AnyUp")
        R.rearm:SetAttribute("_onclick", REARMBODY)
        pcall(function() R.rearm:HookScript("PostClick", function() pcall(routerAfterClick, "rearm") end) end)
    end)
    if not ok then R.hdr = nil end
    if R.hdr and routerMacrosSoon then pcall(routerMacrosSoon) end
    return R.hdr
end
-- Forever's own bars (GamepadMainActionBarFrame.PageUnit): per anchor its base bar first, then every override bar sharing that anchor
local function routerBars()
    local out = {}
    pcall(function()
        local pu = _G.GamepadMainActionBarFrame and _G.GamepadMainActionBarFrame.PageUnit
        if not pu then return end
        for key, an in pairs(ANCHORS) do
            local anchor = pu[an]
            if anchor and anchor.Bar then
                local list = { anchor.Bar }
                for _, ob in ipairs(type(pu.overrideBars) == "table" and pu.overrideBars or {}) do
                    if ob ~= anchor.Bar and T(function() return ob:GetParent() end) == anchor then list[#list + 1] = ob end
                end
                if key == "top" then
                    for _, extra in ipairs({ pu.actionBars and pu.actionBars.possessBar, pu.actionBars and pu.actionBars.stanceBar }) do
                        local dup = false
                        for _, x in ipairs(list) do if x == extra then dup = true end end
                        if extra and not dup and T(function() return extra:GetParent() end) == anchor then list[#list + 1] = extra end
                    end
                end
                out[key] = list
            end
        end
    end)
    return out
end
local function padIndex(name)
    local ok, v = pcall(function() return C_GamePad.ButtonBindingToIndex(name) end)
    v = ok and tonumber(v) or nil
    return (v and v >= 0) and math.floor(v) + 1 or nil
end
-- wire one face button to the router (out of combat): the bars' buttons for its position, the triggers' indexes, the snippet, the driver
-- Forever's buttons for one bar position (1-4 the D-pad, 5-8 the faces), per anchor, plus the triggers' indexes and the key's name
local function routerWire(b, pos, key)
    local bars = routerBars()
    b:SetAttribute("wc-pos", pos)                                      -- m55: the per-form sets are kept by position
    -- m53: the stance / form bar, marked where it is wired (the snippet counts it as the bare press on the top anchor)
    local sb = T(function() local pu = _G.GamepadMainActionBarFrame.PageUnit; return pu.actionBars and pu.actionBars.stanceBar end)
    for k in pairs(ANCHORS) do
        local n = 0
        for _, bar in ipairs(bars[k] or {}) do
            local btn = T(function() return bar:GetActionButtonByIndex(pos) end)
            local name = btn and TS(function() return btn:GetName() end)
            if name and name ~= "" then
                n = n + 1
                b:SetAttribute("wc-stance-" .. k .. "-" .. n, (sb ~= nil and bar == sb) or nil)
                pcall(SecureHandlerSetFrameRef, b, "wc-bar-" .. k .. "-" .. n, bar)
                pcall(SecureHandlerSetFrameRef, b, "wc-btn-" .. k .. "-" .. n, btn)        -- m26: its live slot (GetID)
                b:SetAttribute("wc-dest-" .. k .. "-" .. n, name)
            end
        end
        b:SetAttribute("wc-n-" .. k, n)
    end
    if R.hdr then pcall(SecureHandlerSetFrameRef, b, "wc-hdr", R.hdr) end          -- m26: the slots' macro texts
    b:SetAttribute("wc-lt", padIndex("PADLTRIGGER"))
    b:SetAttribute("wc-rt", padIndex("PADRTRIGGER"))
    b:SetAttribute("wc-key", key)                                     -- the key's name: room mode sends it (Interact's contract)
end
local function routerKey(pos)
    local ctx = bindCtx()
    local bind = "GAMEPADACTIONBUTTON" .. pos
    return TS(function() return GetBindingKey(bind, nil, ctx) end) or TS(function() return GetBindingKey(bind) end)
end
-- m50 THE GIVE-BACK REACHES THE D-PAD (the user on test21: "B in a fight gives back A/X/Y AND the D-pad ... until you start
-- attacking again"): the D-pad's router buttons are linked under the same "wc-face-<pos>" refs (positions 1-4) as A / X / Y (5 / 6 / 8),
-- on every secure body that gives back or re-arms (the header's fight driver, the give-back and re-arm buttons, B, every router button),
-- whose loops walk 1-4 and 5 / 6 / 8. Out of combat only (refs cannot change in a fight).
R.linkAll = function()
    if inCombat() then return end
    local all = {}
    for p, o in pairs(face.buttons) do all[p] = o end
    for i = 1, 4 do if R.dbtn and R.dbtn[i] then all[i] = R.dbtn[i] end end
    local holders = { R.hdr, R.give, R.rearm, R.bbtn }
    for _, o in pairs(all) do holders[#holders + 1] = o end
    for _, h in pairs(holders) do
        if h then for p, o in pairs(all) do pcall(SecureHandlerSetFrameRef, h, "wc-face-" .. p, o) end end
    end
end
local function routerSetup(b, pos)
    if inCombat() or not b then return false end
    local hdr = routerHeader()
    if not hdr then return false end
    R.keys[pos] = R.keys[pos] or routerKey(pos)
    routerWire(b, pos, R.keys[pos])
    -- the safe default before any press (were the snippet never to run): Forever's own button, a tap
    local d = b:GetAttribute("wc-dest-top-1")
    if not R.wrapped[pos] then
        b:SetAttribute("type", d and "macro" or nil)
        b:SetAttribute("macrotext", d and ("/click " .. d .. " LeftButton 1\n/click " .. d .. " LeftButton 0") or nil)
    end
    if not R.wrapped[pos] and pcall(SecureHandlerWrapScript, b, "OnClick", hdr, PRE) then R.wrapped[pos] = true end
    if not R.driven[pos] and pcall(RegisterAttributeDriver, b, "wc-state", "[combat] fight; idle") then R.driven[pos] = true end
    pcall(SecureHandlerSetFrameRef, hdr, "wc-face-" .. pos, b)
    pcall(SecureHandlerSetFrameRef, R.give, "wc-face-" .. pos, b)
    pcall(SecureHandlerSetFrameRef, R.rearm, "wc-face-" .. pos, b)
    b:SetAttribute("wc-interact", pos == 5 or nil)                    -- X = Forever's Interact With Target (on an enemy: an attack)
    if pos == 5 then b:SetAttribute("wc-fish", OV.fish and OV.fish.n or nil) end      -- m59: Fishing while a pole is in the main hand
    if pos == 6 then b:SetAttribute("wc-smarty", OV.smartVal); b:SetAttribute("wc-smarty-t", OV.smartTyp) end   -- m61 / m62: Smart Y
    for p, o in pairs(face.buttons) do
        pcall(SecureHandlerSetFrameRef, b, "wc-face-" .. p, o)
        pcall(SecureHandlerSetFrameRef, o, "wc-face-" .. pos, b)
    end
    if R.bbtn then pcall(SecureHandlerSetFrameRef, R.bbtn, "wc-face-" .. pos, b) end
    pcall(R.linkAll)                                                -- m50
    return R.wrapped[pos] and R.driven[pos] and d ~= nil
end
-- m21: B (PAD2) through the router too, while it routes a face: its own secure button (no fight spell; role "back"), Forever's Back
-- (the bar's button 7) on a bare press, the layer's button 7 with a trigger. Same snippet, same stepping aside as A / X / Y.
-- m22: B (role "back") and the D-pad (role "dpad", Interact's room step: the D-pad is the room's cursor in a fight) on one maker. Same
-- snippet: Forever's own button for the position (layers included), never a fight spell; room mode delivers the key to wc-room-dest.
local function routerMake(pos, name, role, why)
    if inCombat() then return nil end
    local hdr = routerHeader()
    if not hdr then return nil end
    local made = nil
    local ok = pcall(function()
        local b = CreateFrame("Button", name, UIParent, "SecureActionButtonTemplate")
        b:RegisterForClicks("AnyUp", "AnyDown")
        b:SetAttribute("wc-role", role)
        R.keys[pos] = R.keys[pos] or routerKey(pos)
        routerWire(b, pos, R.keys[pos])
        local d = b:GetAttribute("wc-dest-top-1")
        b:SetAttribute("type", d and "macro" or nil)
        b:SetAttribute("macrotext", d and ("/click " .. d .. " LeftButton 1\n/click " .. d .. " LeftButton 0") or nil)
        SecureHandlerWrapScript(b, "OnClick", hdr, PRE)
        RegisterAttributeDriver(b, "wc-state", "[combat] fight; idle")
        for p, o in pairs(face.buttons) do SecureHandlerSetFrameRef(b, "wc-face-" .. p, o) end
        -- the post-click: B's padmap at once (why); m26: a press that REACHES B or the D-pad says where it went (as A / X / Y do)
        b:HookScript("PostClick", function(_, _, down)
            if why then pcall(routerAfterClick, why) end
            pcall(function()
                WC.send(WC.encode({ type = "facecast", v = PV, pos = pos, down = down and true or nil, fight = inCombat() or nil,
                                    kind = TS(function() return b:GetAttribute("wc-kind") end),
                                    at = TS(function() return b:GetAttribute("wc-at") end), holder = capHolder() }))
            end)
        end)
        made = b
    end)
    return ok and made or nil
end
local function routerB()
    if not R.bbtn then R.bbtn = routerMake(7, "WowCompanionFaceB", "back", "b-giveback"); pcall(R.linkAll) end
    return R.bbtn
end
local function routerD()
    local made = false
    for i = 1, 4 do
        if not R.dbtn[i] then R.dbtn[i] = routerMake(i, "WowCompanionFaceD" .. i, "dpad", nil); made = made or R.dbtn[i] ~= nil end
    end
    if made then pcall(R.linkAll) end                               -- m50
end
local function bKey() R.keys[7] = R.keys[7] or routerKey(7); return R.keys[7] end
-- m37 START ON PLAIN HOME (Home's ask 2026-10-08, the user's sign-off of the RADIAL PAUSE MENU, wow-companion-pulse/RADIAL_PAUSE_MENU.md):
-- the game's Start (the key of its OPENRADIAL binding, PADFORWARD) -> Hub's plain button WowCompanionPadPause (barcmd "pause": Home opens
-- OUR wheel; the game's GamepadRadial stays shut). Bound as the D-pad layout is: while nothing holds and no game window is open, out of
-- combat, carried at a fight's start; a guest that holds keeps Start (the router is aside, as today).
-- m38 (the user 2026-10-08, Q7 "(a) Follow toolbar switch"): ONLY while the character's DS WoW toolbar is on (the D-pad layout's own
-- switch: one switch for the whole layout). Off: Start is the game's own (its radial, with the Game Menu), and menuArm has nothing to arm.
-- THE GAME MENU, THE SECURE WAY (the pause row's column 10, Radial.lua): menuArm() points Start at the game's own TOGGLEGAMEMENU binding for
-- ONE press (until the Game Menu shows, or MENU_S pass): the hardware press runs Blizzard's own binding, nothing of Blizzard's is called
-- from here. Out of combat only (a binding change).
local PAUSE_BTN, MENU_S = "WowCompanionPadPause", 8
local function startKey()
    local ctx = bindCtx()
    return TS(function() return GetBindingKey("OPENRADIAL", nil, ctx) end) or TS(function() return GetBindingKey("OPENRADIAL") end)
        or "PADFORWARD"
end
local function routerStart()
    if R.sbtn or inCombat() then return R.sbtn end
    pcall(function()
        local b = CreateFrame("Button", "WowCompanionFaceStart", UIParent, "SecureActionButtonTemplate")
        b:RegisterForClicks("AnyUp", "AnyDown")                 -- the secure template acts on ONE edge (the key-down, ActionButtonUseKeyDown)
        b:SetAttribute("type", "macro")
        b:SetAttribute("macrotext", "/click " .. PAUSE_BTN)
        R.sbtn = b
    end)
    return R.sbtn
end
local function menuArmed() return R.menuAt ~= nil and GetTime() - R.menuAt <= MENU_S end
-- every router button (Interact's contract: stable names, made at login, never destroyed)
local routerAll
-- the fight state set directly (out of combat / before the lockdown only): Interact measured that [combat] state-driver SNIPPETS never
-- run on this client; the attribute driver stays as a second source
local function routerState(v)
    if inCombat() then return false end
    for _, x in ipairs(routerAll()) do x.b:SetAttribute("wc-state", v) end
    return true
end
routerAll = function()
    local out = {}
    for pos, b in pairs(face.buttons) do out[#out + 1] = { pos = pos, b = b } end
    if R.bbtn then out[#out + 1] = { pos = 7, b = R.bbtn } end
    for i = 1, 4 do if R.dbtn[i] then out[#out + 1] = { pos = i, b = R.dbtn[i] } end end
    return out
end

-- a game window is open (its own controller navigation needs A / X / Y): the controller manager's open frames, the UI panels, the
-- dialogs and boxes that answer with A / B
local WINDOWS = { "LootFrame", "GossipFrame", "QuestFrame", "MerchantFrame", "TaxiFrame", "BankFrame", "MailFrame", "TradeFrame",
                  "ClassTrainerFrame", "GameMenuFrame", "StaticPopup1", "StaticPopup2", "StaticPopup3", "StaticPopup4",
                  "ReadyCheckFrame", "GroupLootFrame1", "GamepadRadial" }
local function windowOpen()
    local hit = nil
    pcall(function()
        local m = _G.GamepadMode and _G.GamepadMode.FrameControlsManager
        for _, f in ipairs(m and type(m.shownFrames) == "table" and m.shownFrames or {}) do
            if TB(function() return f:IsShown() end) then hit = "managed"; return end
        end
        for _, slot in ipairs({ "left", "center", "right", "doublewide", "fullscreen" }) do
            local pnl = T(function() return GetUIPanel(slot) end)
            if pnl and TB(function() return pnl:IsShown() end) then hit = "panel"; return end
        end
        for _, name in ipairs(WINDOWS) do
            local f = _G[name]
            if type(f) == "table" and TB(function() return f:IsShown() end) then hit = name; return end
        end
    end)
    return hit
end
local function roomHolder()
    local ok, st = pcall(function() return WC.capture.state() end)
    return ok and type(st) == "table" and st.holder or nil
end
-- m43 (Home's ask, the user's ruling 2026-10-09: "Start opens the pause wheel from every room"): a ROOM holding keeps Start ours (the app
-- opens the wheel over the room; the binding keeps the game's own radial shut). Quests' generic GUESTS ("pad:..." holders: the wheels,
-- the map module, chat) bind Start themselves (q35): they keep it.
function R.startInRoom(h) return type(h) == "string" and h:find("^pad:") == nil end
local function routerAct(name) return "CLICK " .. name .. ":LeftButton" end
-- m26: every action slot holding a NAMED macro -> its text, as header attributes "wc-mt-<slot>" (the snippet runs it instead of a /click
-- of Forever's button). Out of combat only; a slot changed in a fight is read again when it ends. A macro whose name cannot be read (a link
-- to a deleted macro reads as macro id 0) gets nothing: the press stays a /click, as before.
R.mt = {}
R.ft = {}                                                           -- m43: the fight spells' texts set on the header
local function routerMacros()
    if inCombat() then return false end
    local hdr = R.hdr
    if not hdr then return false end
    local want = {}
    for slot = 1, LAST_ACTION_SLOT do
        local ok, kind = CALL(function() return GetActionInfo(slot) end)
        if ok and kind == "macro" then
            local mname = TS(function() return GetActionText(slot) end)
            local mi = mname and TN(function() return GetMacroIndexByName(mname) end)
            if mi and mi > 0 then
                local okm, n, _, body = CALL(function() return GetMacroInfo(mi) end)
                if okm and n == mname and WC.isPlain(body) and type(body) == "string" and body ~= "" and #body <= 1023 then want[slot] = body end
            end
        end
    end
    -- m41: the overlay's command on a slot (its text, run by the router's snippet); it wins over the slot's own macro
    for slot, e in pairs(OV.db()) do
        local c = type(e) == "table" and OV.byKey(e.k)
        if c and type(slot) == "number" then want[slot] = c.body end
    end
    local changed = false
    for slot in pairs(R.mt) do
        if want[slot] == nil then pcall(function() hdr:SetAttribute("wc-mt-" .. slot, nil) end); R.mt[slot] = nil; changed = true end
    end
    for slot, body in pairs(want) do
        if R.mt[slot] ~= body then pcall(function() hdr:SetAttribute("wc-mt-" .. slot, body) end); R.mt[slot] = body; changed = true end
    end
    -- m43: the fight spells' texts ("wc-ft-<slot>"), while Combat controls are on; m52: a harmful one starts the attack too (the switch on)
    local saOn = attackOn()
    local wantF = {}
    if combatOn() then
        for slot, e in pairs(OV.ftdb()) do
            if type(slot) == "number" and type(e) == "table" and type(e.n) == "string" and WC.isPlain(e.n) then
                wantF[slot] = ((saOn and harmful(e.id or e.n)) and attackLines("") or "") .. "/cast " .. e.n
            end
        end
    end
    -- m52 START ATTACK ON CAST AS THE OVERLAY (f-start-attack-overlay, the user 2026-10-13: "investigated and solved for", the no-trace
    -- ruling kept): every slot the setting covers that holds a plain HARMFUL spell -> "wc-sa-<slot>" = the attack lines; the router's
    -- snippet runs them before its /click of the game's own button. Nothing on the bar changes: off = the attribute is gone
    local wantS, posS = {}, {}
    if saOn then
        for page = 1, PAGES do
            for set = 1, SETS do
                for pos = 1, POSITIONS do
                    local sl = not fixed(set, pos) and storage(page, set, pos)
                    if sl and wantS[sl] == nil then
                        local now = readSlot(sl)
                        if now.kind == "spell" and now.id and harmful(now.id) then wantS[sl] = attackLines(""); posS[pos] = true end
                    end
                end
            end
        end
    end
    R.sa = R.sa or {}
    for slot in pairs(R.sa) do
        if wantS[slot] == nil then pcall(function() hdr:SetAttribute("wc-sa-" .. slot, nil) end); R.sa[slot] = nil; changed = true end
    end
    for slot, body in pairs(wantS) do
        if R.sa[slot] ~= body then pcall(function() hdr:SetAttribute("wc-sa-" .. slot, body) end); R.sa[slot] = body; changed = true end
    end
    -- the router owns those positions' keys out of combat too (the overlay's keys, below): a change binds again
    local was, now = R.saPos or {}, posS
    for k in pairs(was) do if not now[k] then OV.rebind = true end end
    for k in pairs(now) do if not was[k] then OV.rebind = true end end
    R.saPos = posS
    -- m55: the forms' own In-combat spells ("wc-ff-<form index>-<pos>") and the caster's D-pad ones for a form without its own
    -- ("wc-ffc-<pos>": the bare set's slots on the page in use); the index is the form's NOW, so a newly learned form re-sends them
    local wantFF = {}
    if combatOn() then
        local idx = {}
        for _, fo in ipairs(OV.forms()) do idx[fo.id] = fo.x end
        for fid, t in pairs(OV.ffdb()) do
            if idx[fid] and type(t) == "table" then
                for pos, e in pairs(t) do
                    if type(pos) == "number" and type(e) == "table" and type(e.n) == "string" and WC.isPlain(e.n) then
                        wantFF["wc-ff-" .. idx[fid] .. "-" .. pos] = ((saOn and harmful(e.id or e.n)) and attackLines("") or "") .. "/cast " .. e.n
                    end
                end
            end
        end
        local pg = gamePage()
        for pos = 1, 4 do
            local sl = not fixed(1, pos) and storage(pg, 1, pos)
            if sl and wantF[sl] then wantFF["wc-ffc-" .. pos] = wantF[sl] end
        end
    end
    R.ff = R.ff or {}
    for k in pairs(R.ff) do
        if wantFF[k] == nil then pcall(function() hdr:SetAttribute(k, nil) end); R.ff[k] = nil; changed = true end
    end
    for k, body in pairs(wantFF) do
        if R.ff[k] ~= body then pcall(function() hdr:SetAttribute(k, body) end); R.ff[k] = body; changed = true end
    end
    for slot in pairs(R.ft) do
        if wantF[slot] == nil then pcall(function() hdr:SetAttribute("wc-ft-" .. slot, nil) end); R.ft[slot] = nil; changed = true end
    end
    for slot, body in pairs(wantF) do
        if R.ft[slot] ~= body then pcall(function() hdr:SetAttribute("wc-ft-" .. slot, body) end); R.ft[slot] = body; changed = true end
    end
    return changed
end
local mtQueued = false
routerMacrosSoon = function()
    if mtQueued then return end
    mtQueued = true
    C_Timer.After(0.3, function() mtQueued = false; pcall(routerMacros) end)
end
-- m34 the key a binding takes for set / pos: the game's own key for that place (GAMEPADACTIONBUTTONn), with ZL: "SHIFT-" + it
local function bindChord(set, pos)
    local key = R.keys[pos] or routerKey(pos)
    if not key then return nil end
    R.keys[pos] = key
    return set == 2 and ("SHIFT-" .. key) or key
end
-- the bindings that can be bound now: chord -> binding (the D-pad's with no trigger give way to the DS toolbar)
local function bindPlan()
    local out = {}
    for at, k in pairs(bindStore()) do
        local set, pos = at:match("^(%d):(%d)$")
        set, pos = tonumber(set), tonumber(pos)
        if set and pos and bindByKey(k) and bindAllowed(set, pos) then
            local chord = bindChord(set, pos)
            if chord then out[chord] = k end
        end
    end
    return out
end
local routerSay
-- the bindings (out of combat only). `fightStart` = before the lockdown: rooms let go then, so only a game window keeps the router aside
local function routerBind(why, fightStart)
    if inCombat() then return false end
    local hdr = routerHeader()
    if not hdr then return false end
    -- m22 (Interact's ask): at a fight's start a ROOM beats the game window under it (batch 34 opens one behind every room): the router
    -- binds and the room's modes deliver; only a real game window with no room holding keeps it aside then
    local holder, win = roomHolder(), windowOpen()
    local aside
    if fightStart then aside = (win and not holder) and win or nil else aside = win or (holder and "room") or nil end
    local before = R.aside
    R.aside = aside
    pcall(ClearOverrideBindings, hdr)
    R.bound = {}
    if not aside then
        for pos, key in pairs(face.keys) do
            local b, choice = face.buttons[pos], faceStore()[pos]
            if b and type(choice) == "table" and key then
                pcall(SetOverrideBindingClick, hdr, true, key, b:GetName(), "LeftButton")
                R.bound[pos] = TS(function() return GetBindingAction(key, true) end) == routerAct(b:GetName()) or nil
            end
        end
    end
    R.bbound = nil
    R.carry = {}
    local function carry(b, key)
        pcall(SetOverrideBindingClick, hdr, true, key, b:GetName(), "LeftButton")
        local ok = TS(function() return GetBindingAction(key, true) end) == routerAct(b:GetName())
        if ok then R.carry[key] = b:GetName() end
        return ok
    end
    if not aside and (fightStart or next(R.bound) ~= nil) then
        local bb, key = routerB(), bKey()
        if bb and key then
            R.bbound = carry(bb, key) or nil
            R.bkey = key
        end
    end
    -- m29 THE D-PAD LAYOUT (m25's room bar re-made; "as long as it can be toggle off"): while this character wants it and nothing holds
    -- the controller, the four bare D-pad directions drive Home (a trigger held: the layer's own buttons), IN and out of fights (bound out
    -- of combat; carried at a fight's start like every key). Off: no D-pad override at all out of combat.
    R.rb = {}
    local rbOn = dpadWant() == true
    if rbOn then routerD() end
    for pos, x in pairs(ROOMBAR) do
        local b = R.dbtn[pos]
        if b then b:SetAttribute("wc-roombar", rbOn and x.btn or nil) end
    end
    if not aside and rbOn then
        for pos in pairs(ROOMBAR) do
            local b, key = R.dbtn[pos], R.keys[pos]
            if b and key and carry(b, key) then R.rb[pos] = true end
        end
    end
    -- m41 THE OVERLAY'S KEYS: a position that carries a command (any page / set) is the router's on its bare key: its snippet runs the
    -- command for that slot, and the game's own button for every other slot under that key (as for A / X / Y)
    R.ov = {}
    if not aside then
        local own = OV.positions()
        for pos in pairs(R.saPos or {}) do own[pos] = true end          -- m52: start attack on cast
        for pos in pairs(own) do
            local b
            if FACE[pos] then b = face.buttons[pos]; if b then routerSetup(b, pos) end
            elseif pos == 7 then b = routerB()
            else routerD(); b = R.dbtn[pos] end
            local key = R.keys[pos] or routerKey(pos)
            R.keys[pos] = key
            if b and key and carry(b, key) then R.ov[pos] = true end
        end
    end
    -- m37 START: our pause wheel (or, armed, the game's own Game Menu binding for one press)
    R.sbound, R.skey = nil, nil
    -- m38: with the DS WoW toolbar only; m43: also while a ROOM holds (aside "room", or a game window a room opened behind itself); a
    -- window the player opened with no room holding, or a guest, takes it
    if (not aside or R.startInRoom(holder)) and rbOn then
        local key = startKey()
        R.skey = key
        if menuArmed() then
            pcall(SetOverrideBinding, hdr, true, key, "TOGGLEGAMEMENU")
            R.sbound = TS(function() return GetBindingAction(key, true) end) == "TOGGLEGAMEMENU" and "menu" or nil
        else
            local sb = routerStart()
            if sb and carry(sb, key) then R.sbound = "pause" end
        end
    end
    -- m34 THE BINDINGS ON A BUTTON: the chord points at the game's binding (Jump, Interact, Auto Run, Walk / Run)
    R.cbound = {}
    if not aside then
        for chord, k in pairs(bindPlan()) do
            pcall(SetOverrideBinding, hdr, true, chord, k)
            R.cbound[chord] = TS(function() return GetBindingAction(chord, true) end) == k or nil
        end
    end
    -- the fight's start: every other key too (faces without a fight spell, the D-pad): Forever's own buttons, or the room's in room mode
    -- (m34: never over a binding the player put there)
    if not aside and fightStart then
        for pos in pairs(FACE) do
            local b = face.buttons[pos]
            if b and not R.bound[pos] and R.keys[pos] then carry(b, R.keys[pos]) end
        end
        routerD()
        for i = 1, 4 do
            local b, key = R.dbtn[i], R.keys[i]
            if b and key and not R.cbound[key] then carry(b, key) end
        end
    end
    local want = 0
    for pos in pairs(face.keys) do if face.buttons[pos] and type(faceStore()[pos]) == "table" then want = want + 1 end end
    local got = 0
    for _ in pairs(R.bound) do got = got + 1 end
    face.refused = (not aside and want > 0 and got < want) or nil
    R.lastBind = GetTime()
    if routerSay and (before ~= aside or why ~= "check") then routerSay(why) end
    return got == want
end
-- every 0.5 s out of combat: step aside / come back, and take A / X / Y back if another owner left them empty (bounded: once a second)
local function routerCheck()
    if inCombat() then return end
    if OV.rebind then OV.rebind = nil; R.lastBind = 0; pcall(routerBind, "uncommand") end      -- m41
    if not R.sbtn then routerStart() end
    local aside = windowOpen() or (roomHolder() and "room") or nil
    local lost = false
    if not aside then
        for pos, key in pairs(face.keys) do
            local b = face.buttons[pos]
            if b and type(faceStore()[pos]) == "table" and TS(function() return GetBindingAction(key, true) end) ~= routerAct(b:GetName()) then
                lost = true
            end
        end
    end
    if not aside and R.bbtn and R.bkey and next(R.bound) ~= nil
        and TS(function() return GetBindingAction(R.bkey, true) end) ~= routerAct(R.bbtn:GetName()) then lost = true end
    if not aside and dpadWant() == true then                            -- m25: the room bar's two keys
        for pos in pairs(ROOMBAR) do
            local b, key = R.dbtn[pos], R.keys[pos]
            if not (b and key and TS(function() return GetBindingAction(key, true) end) == routerAct(b:GetName())) then lost = true end
        end
    end
    if not aside then                                                   -- m41: the overlay's keys
        for pos in pairs(OV.positions()) do
            local key = R.keys[pos]
            local b = FACE[pos] and face.buttons[pos] or (pos == 7 and R.bbtn) or R.dbtn[pos]
            if not (b and key and TS(function() return GetBindingAction(key, true) end) == routerAct(b:GetName())) then lost = true end
        end
    end
    if not aside then                                                   -- m34: the bindings on buttons
        for chord, k in pairs(bindPlan()) do
            if TS(function() return GetBindingAction(chord, true) end) ~= k then lost = true end
        end
    end
    -- m37: the menu arm is spent once the Game Menu shows (the router is aside then) or MENU_S pass; Start goes back to our wheel
    if R.menuAt and (not menuArmed() or windowOpen() == "GameMenuFrame") then R.menuAt = nil; lost = true; R.lastBind = 0 end
    if (not aside or R.startInRoom(roomHolder())) and dpadWant() == true then   -- m38: the DS WoW toolbar only; m43: in rooms too
        local key = R.skey or startKey()
        local want = menuArmed() and "TOGGLEGAMEMENU" or (R.sbtn and routerAct(R.sbtn:GetName())) or nil
        if want == nil or TS(function() return GetBindingAction(key, true) end) ~= want then lost = true end
    elseif R.sbound then lost = true end                                -- m43: bound, no longer wanted (a room let go, its window stays)
    if aside ~= R.aside or (lost and GetTime() - (R.lastBind or 0) >= 1) then
        routerBind("check")
        pcall(emit, true); WC.push()
    end
end
-- m31: capture version 13 (Interact i89) calls WC.capture.onChange listeners right after any change of its holder or the fight's room
-- mode (a take, a queued take granted, a release, a loss / pre-emption): the router steps aside / comes back AT ONCE (zero gap). Joined
-- once, as soon as the capture is there (it may load after this file); the 0.1 s check stays for older captures and for the game's own
-- windows (which never become the capture's holder).
local function routerListen()
    if R.listening then return true end
    local cap = WC.capture
    if type(cap) ~= "table" or (cap.version or 0) < 13 or type(cap.onChange) ~= "function" then return false end
    local ok, got = pcall(cap.onChange, function() pcall(routerCheck) end)
    R.listening = ok and got == true
    return R.listening
end
local function routerTicker()
    if not R.listening then routerListen() end
    if R.ticker then return end
    -- m30: every 0.1 s (was 0.5): after a room lets go, the D-pad is the toolbar's again within a tenth of a second (until then it is the
    -- GAME's own slots); TapPad's sink polls the capture's holder at the same rate
    local ok, t = pcall(C_Timer.NewTicker, 0.1, function()
        if not R.listening then routerListen() end
        pcall(routerCheck)
    end)
    if ok then R.ticker = t end
end
-- the fight state's out-of-combat inputs (start attack / auto-repeat before the fight, given back): attributes, out of combat only
local function routerInputs()
    if inCombat() then return false end
    -- m21: B and the re-arm work in secure code: their attribute is the truth, except for a new start (cleared) or Home's give-back tap
    if face.givenSet then
        face.givenSet = nil
    else
        local g = false
        for _, b in pairs(face.buttons) do if TB(function() return b:GetAttribute("wc-given") end) then g = true end end
        for i = 1, 4 do local b = R.dbtn and R.dbtn[i]; if b and TB(function() return b:GetAttribute("wc-given") end) then g = true end end
        face.given = g
    end
    local pre = (face.aa or face.ar) and true or nil
    for _, b in pairs(face.buttons) do
        b:SetAttribute("wc-pre", pre)
        b:SetAttribute("wc-given", face.given and true or nil)
    end
    if R.bbtn then R.bbtn:SetAttribute("wc-pre", pre) end
    -- m49: the D-pad's router buttons switch on the SAME inputs as A / X / Y (before: only the [combat] driver, so a D-pad In-combat
    -- spell waited for the fight while A / X / Y had switched at the attack's start)
    for i = 1, 4 do
        local b = R.dbtn and R.dbtn[i]
        if b then b:SetAttribute("wc-pre", pre); b:SetAttribute("wc-given", face.given and true or nil) end
    end
    return true
end
local function swapStore()
    if type(_G.WowCompanionSpellsDB) ~= "table" then _G.WowCompanionSpellsDB = {} end
    local db = _G.WowCompanionSpellsDB
    if type(db.faceSwap) ~= "table" then db.faceSwap = {} end
    return db.faceSwap
end
faceRef = function(pos)
    local c = FACE[pos] and faceStore()[pos]
    if type(c) ~= "table" or type(c.n) ~= "string" then return nil end
    return spellRef(c.n)
end
faceState = function()
    local keys, armed = {}, {}
    for pos in pairs(FACE) do
        keys[tostring(pos)] = face.keys[pos]
        if face.armed[pos] then armed[tostring(pos)] = face.armed[pos] end
    end
    return { on = next(face.keys) ~= nil or nil, keys = next(keys) and keys or nil, pending = face.pending or nil,
             armed = next(armed) and armed or nil, late = face.late or nil, given = face.given or nil,
             gb = type(WC.spellsGiveBackCol) == "number" and WC.spellsGiveBackCol or nil,
             refused = face.refused or nil, probe = face.probe, aside = R.aside or nil, router = R.hdr ~= nil or nil,
             gbFight = R.bbound or nil,
             mt = (function() local c = 0 for _ in pairs(R.mt) do c = c + 1 end return c > 0 and c or nil end)(),
             cc = combatOn(), ccCols = WC.spellsCcCols }
end
local function bindSet() local ok, v = pcall(GetCurrentBindingSet); return (ok and type(v) == "number") and v or nil end
local function swapSay(on, why, extra)
    pcall(function()
        local k = {}
        local ctx = bindCtx()
        for pos, key in pairs(face.keys) do
            local b = face.buttons[pos]
            k[tostring(pos)] = { key = key, now = TS(function() return GetBindingAction(key, true) end) or "", ctx = readKey(key, ctx),
                                 ours = (b and R.bound[pos]) and true or nil, given = b and TB(function() return b:GetAttribute("wc-given") end) or nil }
        end
        local doc = { type = "faceswap", v = PV, on = on and true or false, why = why, k = k, ctx = ctx, aside = R.aside or nil,
                      armed = faceState().armed, late = face.late or nil, fight = inCombat() or nil, bs = bindSet(), router = true }
        if type(extra) == "table" then for x, y in pairs(extra) do doc[x] = y end end
        WC.send(WC.encode(doc))
        WC.push()
    end)
end
-- the record: one entry per changed key and layer ("g" = the game's gamepad context, "n" = the plain one), key "<layer>:<key>"
local function layerCtx(l) if l == "n" then return nil end return bindCtx() end
-- put back every key the companion changed, if it is still ours (another change since is the user's or the game's: left alone); out of
-- combat only. true when nothing changed is left
local function faceRestore(why)
    if inCombat() then return false end
    local sw = swapStore()
    if next(sw) == nil then
        face.armed = {}
        -- m16: a fight's end is said even when nothing was changed (the game took nothing): every start has its end in the log
        if why ~= "login" and face.swapped then swapSay(false, why, { back = 0, kept = 0, left = 0 }) end
        return true
    end
    local back, kept, left = 0, 0, 0
    -- m17: the PLAIN layer first ("n:" records), then the gamepad context. (Reason given in m17, DISPROVED by m18's probe on the Thor
    -- 2026-10-01 22:19: plain-layer reads do NOT answer the context's key -- A's plain entry read "" while the context held it. The order
    -- is harmless either way, so it stays.)
    for pass = 1, 2 do
        for id, e in pairs(sw) do
            if type(e) ~= "table" or type(e.ours) ~= "string" or type(e.k) ~= "string" then
                sw[id] = nil
            elseif (e.l == "n") == (pass == 1) then
                local c = layerCtx(e.l)
                if readKey(e.k, c) == e.ours then
                    if setKey(e.k, (e.old ~= nil and e.old ~= "") and e.old or nil, c) then sw[id] = nil; back = back + 1 else left = left + 1 end
                else
                    sw[id] = nil; kept = kept + 1
                end
            end
        end
    end
    face.armed = {}
    swapSay(false, why, { back = back, kept = kept, left = left })
    return left == 0
end
-- the companion's spell buttons and the keys (out of combat only; in a fight: done when it ends)
faceSetup = function()
    if inCombat() then face.pending = true return false, "combat" end
    face.pending = false
    local store = faceStore()
    local ctx = bindCtx()
    face.keys = {}
    for _, pos in ipairs({ 5, 6, 8 }) do
        local choice = store[pos]
        local b = face.buttons[pos]
        local bind = "GAMEPADACTIONBUTTON" .. pos
        local key = TS(function() return GetBindingKey(bind, nil, ctx) end) or TS(function() return GetBindingKey(bind) end)
        R.keys[pos] = key
        local chosen = type(choice) == "table" and type(choice.n) == "string" and combatOn()   -- m27: off = none chosen (kept)
        if key then
            if chosen then face.keys[pos] = key end
            do
                if not b then
                    local okB, nb = pcall(CreateFrame, "Button", "WowCompanionCombatFace" .. pos, UIParent, "SecureActionButtonTemplate")
                    if okB and type(nb) == "table" then
                        b = nb; face.buttons[pos] = b; b:RegisterForClicks("AnyUp", "AnyDown")
                        local p = pos
                        -- a press that REACHES our button says so (a post-hook on our own button: runs after the secure click, changes nothing)
                        pcall(function()
                            b:HookScript("PostClick", function(_, button, down)
                                if down then pcall(routerAfterClick, "rearm") end
                                pcall(function()
                                    WC.send(WC.encode({ type = "facecast", v = PV, pos = p, down = down and true or nil,
                                                        fight = inCombat() or nil, n = (faceStore()[p] or {}).n,
                                                        kind = TS(function() return b:GetAttribute("wc-kind") end),
                                                        at = TS(function() return b:GetAttribute("wc-at") end),
                                                        holder = capHolder() }))
                                    WC.push()
                                end)
                            end)
                        end)
                    end
                end
                if b and not chosen then
                    b:SetAttribute("wc-fight-type", nil)                    -- no fight spell: Forever's own button (room mode in a room)
                    b:SetAttribute("wc-fight-spell", nil)
                    b:SetAttribute("wc-fight-macro", nil)
                    routerSetup(b, pos)
                elseif b then
                    -- the fight action, chosen by the router's snippet per press (m20)
                    if attackOn() and harmful(choice.id or choice.n) then
                        b:SetAttribute("wc-fight-type", "macro")
                        b:SetAttribute("wc-fight-spell", nil)
                        b:SetAttribute("wc-fight-macro", attackLines("") .. "/cast " .. choice.n)
                    else
                        b:SetAttribute("wc-fight-type", "spell")
                        b:SetAttribute("wc-fight-spell", choice.n)
                        b:SetAttribute("wc-fight-macro", nil)
                    end
                    routerSetup(b, pos)
                end
                if chosen then choice.tcd = tooltipCd(choice.id) or choice.tcd end  -- the tooltip's cooldown (out of combat)
            end
        end
    end
    routerB()
    routerD()
    routerInputs()
    routerBind("setup")
    routerTicker()
    return true
end

-- ---------------------------------------------------------------- the fight faces' cooldowns
-- The user 2026-10-01: Earth Shock "does not look like it goes on cooldown at all ... the tooltip states a cooldown ... global cooldowns
-- ... by going off of successful casts and not triggering a error"; the look: "The game's dark sweep" (Cast draws, Home wires).
-- WHAT THE GAME LETS US READ (spellshareddocumentation.lua, Cast's ActionBar notes): C_Spell.GetSpellCooldown's startTime / duration are
-- SECRET in combat; isActive / isOnGCD are NeverSecret (isOnGCD trusted only inside SPELL_UPDATE_COOLDOWN). So, inside SPELL_UPDATE_COOLDOWN,
-- each fight spell's isActive / isOnGCD say WHETHER a cooldown runs and WHICH (its own or the global one); a press that fails ("not ready",
-- out of range) starts none, by itself. HOW LONG: the real duration when it reads plain (out of combat; then LEARNED per spell, and the GCD
-- too), else the learned one, else the spell tooltip's own "N sec cooldown" (the game's SPELL_RECAST_TIME_SEC / _MIN words), else 1.5 s for
-- the GCD. `facecd` on every change: k = { [pos] = { d = seconds, l = left at send, g = true (the GCD), e = true (estimated) } }; a position
-- that is not there has none running.
local cdNow = {}                -- pos -> { d, t0, g, e }
local function cdLearned()
    if type(_G.WowCompanionSpellsDB) ~= "table" then _G.WowCompanionSpellsDB = {} end
    local db = _G.WowCompanionSpellsDB
    if type(db.faceCd) ~= "table" then db.faceCd = {} end
    return db.faceCd                                                    -- { [spell name] = seconds, ["@gcd"] = seconds }
end
-- "%.3g sec cooldown" -> "^([%d%.]+) sec cooldown$": the game's format split at its number, each side matched literally
local function escape(t) return (t:gsub("([%%%^%$%(%)%.%[%]%*%+%-%?])", "%%%1")) end
local function recastPattern(g)
    local f = _G[g]
    if type(f) ~= "string" or f == "" or not WC.isPlain(f) then return nil end
    local a, b = f:find("%%[%-%d%.]*[gfds]")
    if not a then return nil end
    return "^" .. escape(f:sub(1, a - 1)) .. "([%d%.]+)" .. escape(f:sub(b + 1)) .. "$"
end
-- the spell's cooldown as its tooltip writes it (out of combat, when the spell is set); seconds or nil
tooltipCd = function(id)
    if type(id) ~= "number" or not (C_TooltipInfo and type(C_TooltipInfo.GetSpellByID) == "function") then return nil end
    local ok, data = pcall(C_TooltipInfo.GetSpellByID, id)
    if not ok or type(data) ~= "table" or type(data.lines) ~= "table" then return nil end
    local ps, pm = recastPattern("SPELL_RECAST_TIME_SEC"), recastPattern("SPELL_RECAST_TIME_MIN")
    for n, line in ipairs(data.lines) do
        if n > 12 then break end
        for _, side in ipairs({ "rightText", "leftText" }) do
            local t = TS(function() return line[side] end)
            if t then
                t = t:gsub("|c%x%x%x%x%x%x%x%x", ""):gsub("|r", "")
                local v = ps and t:match(ps)
                if v and tonumber(v) then return tonumber(v) end
                v = pm and t:match(pm)
                if v and tonumber(v) then return tonumber(v) * 60 end
            end
        end
    end
    return nil
end
local function cdSend()
    pcall(function()
        local k, now = {}, GetTime()
        for pos, c in pairs(cdNow) do
            local left = c.d - (now - c.t0)
            if left > 0 then k[tostring(pos)] = { d = c.d, l = math.floor(left * 1000 + 0.5) / 1000, g = c.g or nil, e = c.e or nil } end
        end
        WC.send(WC.encode({ type = "facecd", v = PV, k = next(k) and k or nil }))   -- no `k`: nothing running
        WC.push()
    end)
end
-- SPELL_UPDATE_COOLDOWN: read each fight spell's cooldown flags NOW (the only time isOnGCD is trusted)
local function faceCooldowns()
    local now = GetTime()
    local learned = cdLearned()
    local changed = false
    for pos in pairs(FACE) do
        local c = faceStore()[pos]
        local cur = cdNow[pos]
        if type(c) == "table" and type(c.n) == "string" then
            local ok, info = pcall(C_Spell.GetSpellCooldown, c.id or c.n)
            local active = ok and type(info) == "table" and TB(function() return info.isActive end) == true
            local gcd = ok and type(info) == "table" and TB(function() return info.isOnGCD end) == true
            if not active then
                if cur then cdNow[pos] = nil; changed = true end
            else
                local dur = SN(function() return info.duration end)
                local st = SN(function() return info.startTime end)
                local real = dur ~= nil and dur > 0
                if real then                                            -- plain (out of combat): the real one, learned
                    if gcd then learned["@gcd"] = dur else learned[c.n] = dur end
                end
                if not cur or (cur.g or false) ~= gcd or (real and math.abs((cur.t0 + cur.d) - ((st or now) + dur)) > 0.05) then
                    local d = real and dur or (gcd and (learned["@gcd"] or 1.5) or (learned[c.n] or c.tcd))
                    -- MEASURED on the Thor (08:30, m12): a cast's first SPELL_UPDATE_COOLDOWN shows the spell on the GCD, its own cooldown comes
                    -- about 0.2 s later; that own cooldown started with the cast, so it is counted from the GCD's start
                    local t0 = (real and st) or now
                    if not real and not gcd and cur and cur.g and now - cur.t0 < cur.d then t0 = cur.t0 end
                    if d and d > 0 then
                        cdNow[pos] = { d = d, t0 = t0, g = gcd or nil, e = (not real) or nil }
                    else
                        cdNow[pos] = nil                                -- running, but no length known: drawn as nothing rather than a guess
                    end
                    changed = true
                end
            end
        elseif cur then
            cdNow[pos] = nil; changed = true
        end
    end
    if changed then cdSend() end
end

-- THE TRIGGER (the user 2026-10-01, after batch 24: "follow the exact same logic as the controller expanding, using the start attack"):
-- Home's controller grows while the AUTO-ATTACK runs (PLAYER_ENTER_COMBAT / PLAYER_LEAVE_COMBAT) or an AUTO-REPEAT (START / STOP_
-- AUTOREPEAT_SPELL) or the player is IN COMBAT (PLAYER_REGEN_*). The switch is ON while any of the three is; OFF only when all are over.
-- ON comes either from an attack started out of combat (no lockdown: key changes allowed) or at PLAYER_REGEN_DISABLED (before the
-- lockdown); OFF always comes out of combat (PLAYER_REGEN_ENABLED is after the lockdown; an attack stopping out of combat has none), so the
-- keys can always be put back. ONCE per ON: a second swap-in (an attack starting in a fight) is a no-op (in the fallback layer a second one
-- would overwrite the record). Each change sends the padmap AT ONCE (Home draws the bar's faces from `armed`).
-- B GIVES BACK (the user 2026-10-01: "Out of a fight, B also gives A / X / Y back"): Home's B that shrank the big controller out of a fight
-- sends the `spells` row's "giveback" column (Book.lua); A / X / Y go back to the game's and stay so until the NEXT START (an attack or an
-- auto-repeat starting again, or a fight starting), as Home's controller grows again only then. In a fight B only shrinks (the game locks
-- the keys): refused here too.
local function faceWant() return face.fight or ((face.aa or face.ar) and not face.given) end
local faceWatchFight, bindProbe, keyProbe
-- m20: nothing changes keys here any more. ON / OFF = the router's fight state: the game's own [combat] driver in a fight; out of combat
-- the start-attack / auto-repeat input ("wc-pre") and B's give-back ("wc-given"), set as attributes. `armed` (Home's bar) = the positions
-- bound by the router while the state is on and nothing is given back.
local function faceArmed()
    local on = face.fight or face.aa or face.ar
    local a = {}
    for pos in pairs(face.keys) do
        local b = face.buttons[pos]
        local given = b and TB(function() return b:GetAttribute("wc-given") end)
        if on and R.bound[pos] and not given then a[pos] = "r" end
    end
    return a
end
local function armedKey(a) local t = {} for p, v in pairs(a) do t[#t + 1] = p .. v end table.sort(t) return table.concat(t, ",") end
-- m50: any router button given back right now (A / X / Y or the D-pad: one give-back for all, read from the secure attributes)
fightNow.givenAny = function()
    for _, b in pairs(face.buttons) do if TB(function() return b:GetAttribute("wc-given") end) then return true end end
    for i = 1, 4 do local b = R.dbtn and R.dbtn[i]; if b and TB(function() return b:GetAttribute("wc-given") end) then return true end end
    return false
end
-- the documents' `fight` (the D-pad's In-combat picture): the faces' switch, and nothing given back
fightNow.now = function() return ((face.fight or face.aa or face.ar) and not fightNow.givenAny()) and true or false end
routerAfterClick = function(why)
    local a = faceArmed()
    local sw = fightNow.now()
    if armedKey(a) == armedKey(face.armed or {}) and sw == fightNow.sw then return end
    fightNow.sw = sw
    face.armed = a
    face.given = next(a) == nil and (face.fight or face.aa or face.ar) and true or false
    swapSay(face.swapped, why)
    pcall(emit, true); WC.push()
end
local function faceUpdate(why)
    local want = faceWant()
    routerInputs()
    local changed = want ~= face.swapped
    face.swapped = want
    fightNow.sw = (want and not fightNow.givenAny()) and true or false         -- m49 / m50: the documents' `fight` = this switch, nothing given back
    face.armed = faceArmed()
    if changed then
        faceWatchFight(want)
        swapSay(want, why)
        if not want then pcall(C_Timer.After, 1, function() pcall(emit, true); WC.push() end) end
    end
    return changed
end
routerSay = function(why)
    face.armed = faceArmed()
    swapSay(face.swapped, why, { bound = (function() local n = 0 for _ in pairs(R.bound) do n = n + 1 end return n end)(),
                                  refused = face.refused or nil })
end

-- THE MEASUREMENT, read only: `faceswap` at the fight's start and end (each set key's action in the context, `armed`, `late`, what was put
-- back), `facewatch` every 0.5 s of a fight, sent only on change (does the game take a key back mid-fight?), and `facecast` (each press that
-- REACHES our button).
local watch = { last = nil, ticker = nil }
local function faceWatch()
    if next(face.keys) == nil then return end
    local ctx = bindCtx()
    local k = {}
    for pos, key in pairs(face.keys) do k[tostring(pos)] = TS(function() return GetBindingAction(key, true) end) or "" end
    local doc = WC.encode({ type = "facewatch", v = PV, fight = fightNow.sw or nil, k = k })
    if doc ~= watch.last then watch.last = doc; WC.send(doc); WC.push() end
end
faceWatchFight = function(on)
    if watch.ticker then pcall(function() watch.ticker:Cancel() end); watch.ticker = nil end
    watch.last = nil
    watch.n = 0
    if on and next(face.keys) ~= nil then
        local ok, t = pcall(C_Timer.NewTicker, 0.5, function()
            pcall(faceWatch)
            -- the padmap (with `armed`) again every 15 s while switched: a lost one corrects itself (Home's safety ask)
            watch.n = (watch.n or 0) + 1
            if watch.n % 30 == 0 then pcall(emit, true); WC.push() end
        end)
        if ok then watch.ticker = t end
    end
end

-- THE BINDING PROBE (m16; 2026-10-01 21:16-21:18 every key change of the switch answered without an error and changed nothing, while
-- Interact's OVERRIDE binding applied): out of combat, on a key nobody uses, on a button that does nothing, the game's own key setting in
-- the plain layer (n) and in the gamepad context (g), then an override binding (o): each set, read back and undone at once.
-- `bindprobe` says what took (s = the call's answer, t = it took, b = undone); both key-setting layers refused = face.refused.
-- At login / reload and after a refused fight. Never saved; the probe key is never pressed.
local PROBE_KEY = "CTRL-SHIFT-ALT-NUMPAD9"
local PROBE_ACT = "CLICK WowCompanionCombatFaceProbe:LeftButton"
local probe = {}
bindProbe = function(why)
    if inCombat() then return nil end
    if not probe.owner then
        local ok = pcall(function()
            probe.btn = CreateFrame("Button", "WowCompanionCombatFaceProbe", UIParent)
            probe.owner = CreateFrame("Frame", "WowCompanionCombatFaceProbeOwner", UIParent)
        end)
        if not ok or not probe.owner then return nil end
    end
    local function try(ctx)
        local old = readKey(PROBE_KEY, ctx)
        local r = { s = setKey(PROBE_KEY, PROBE_ACT, ctx) }
        local now = readKey(PROBE_KEY, ctx)
        r.t = now == PROBE_ACT
        if now ~= old then setKey(PROBE_KEY, old ~= "" and old or nil, ctx) end
        r.b = readKey(PROBE_KEY, ctx) == old
        return r
    end
    local doc = { type = "bindprobe", v = PV, why = why, bs = bindSet(), n = try(nil) }
    local ctx = bindCtx()
    if ctx ~= nil then doc.g = try(ctx) end
    local o = { s = pcall(SetOverrideBindingClick, probe.owner, true, PROBE_KEY, "WowCompanionCombatFaceProbe", "LeftButton") }
    o.t = TS(function() return GetBindingAction(PROBE_KEY, true) end) == PROBE_ACT
    pcall(ClearOverrideBindings, probe.owner)
    o.b = TS(function() return GetBindingAction(PROBE_KEY, true) end) ~= PROBE_ACT
    doc.o = o
    face.probe = { n = doc.n.t, o = o.t }
    if doc.g then face.probe.g = doc.g.t end          -- (false is an answer: never "x and false or nil")
    if not doc.n.t and not (doc.g and doc.g.t) then face.refused = true end
    pcall(function() WC.send(WC.encode(doc)); WC.push() end)
    return doc
end

-- THE KEY PROBE (m18; the user 2026-10-01: "Read-only probe first"). MEASURED: since the game's build changed at 18:15 (1.60.1.70009 ->
-- 70170) its gamepad context ignores every change of A / X / Y. READ ONLY -- nothing here changes a key, a binding, a context or a
-- setting: what this build offers. `keyprobe` parts: "head" (the build; every global function whose name holds "Binding" and whether
-- it is the game's own; C_KeyBindings' function names; the binding contexts and what C_KeyBindings' Get / Is ... Context functions
-- answer for each; the face keys' action in every context), "rows" (the game's binding rows that name the gamepad or the face
-- commands), "docs" i / n (the game's generated API documentation of every binding function: arguments, returns, flags; the
-- documentation add-on is pure data, loaded on demand). Once per PadMap version (WowCompanionSpellsDB.keyProbe), out of combat.
local KP_KEYS = { "PAD1", "PAD2", "PAD3", "PAD4", "PADLTRIGGER", "PADRTRIGGER", "PADLSHOULDER", "PADRSHOULDER" }
local KP_CMD = { "GAMEPAD", "JUMP", "INTERACT", "INSPECT" }
local function kpSend(doc) pcall(function() WC.send(WC.encode(doc)) end) end
-- (false is an answer: never "x and v or nil")
local function kpScalar(v) local t = type(v); if t == "string" or t == "number" or t == "boolean" then return v end return nil end
keyProbe = function(force)
    if inCombat() then return nil end
    local db = type(_G.WowCompanionSpellsDB) == "table" and _G.WowCompanionSpellsDB or nil
    if not db or (db.keyProbe == PV and not force) then return nil end
    local head = { type = "keyprobe", v = PV, part = "head" }
    pcall(function() local a, b, c, d = GetBuildInfo(); head.build = { kpScalar(a), kpScalar(b), kpScalar(c), kpScalar(d) } end)
    -- every global function with "Binding" in its name (this build's own list, nothing named by hand), the game's own or not
    local glob = {}
    pcall(function()
        for k, v in pairs(_G) do
            if type(k) == "string" and type(v) == "function" and k:find("Binding", 1, true) then
                local ok, sec = pcall(issecurevariable, k)
                glob[k] = (ok and sec) and true or false
            end
        end
    end)
    head.glob = glob
    local api = {}
    if type(_G.C_KeyBindings) == "table" then
        for k, v in pairs(_G.C_KeyBindings) do if type(v) == "function" then api[#api + 1] = k end end
        table.sort(api)
    end
    head.api = api
    local ctxs = {}
    local e = _G.Enum and _G.Enum.BindingContext
    if type(e) == "table" then for k, v in pairs(e) do if type(v) == "number" then ctxs[k] = v end end end
    head.ctx = ctxs
    -- only C_KeyBindings' readers about contexts (Get... / Is... with "Context" in the name), asked for each context
    local q = {}
    for _, name in ipairs(api) do
        if (name:find("^Get") or name:find("^Is")) and name:find("Context", 1, true) then
            local r = {}
            for cn, cv in pairs(ctxs) do
                local ok, a = pcall(_G.C_KeyBindings[name], cv)
                if not ok then r[cn] = "error" elseif kpScalar(a) == nil then r[cn] = "nil" else r[cn] = a end
            end
            q[name] = r
        end
    end
    head.ctxq = q
    -- the face keys and the triggers: the effective action, the plain layer's, each context's
    local keys = {}
    for _, k in ipairs(KP_KEYS) do
        local r = { eff = TS(function() return GetBindingAction(k, true) end) or "", plain = readKey(k, nil) }
        for cn, cv in pairs(ctxs) do r[cn] = readKey(k, cv) end
        keys[k] = r
    end
    head.keys = keys
    kpSend(head)
    -- the game's binding rows that name the gamepad or the face commands (index, command, category, keys)
    local rows = {}
    pcall(function()
        local n = GetNumBindings()
        for i = 1, (type(n) == "number" and n or 0) do
            local cmd, cat, k1, k2 = GetBinding(i)
            local hit = type(cmd) == "string" and (tostring(k1):find("PAD", 1, true) or tostring(k2):find("PAD", 1, true))
            if type(cmd) == "string" and not hit then
                for _, w in ipairs(KP_CMD) do if cmd:find(w, 1, true) then hit = true; break end end
            end
            if hit and #rows < 120 then rows[#rows + 1] = { i = i, c = cmd, g = kpScalar(cat), k1 = kpScalar(k1), k2 = kpScalar(k2) } end
        end
    end)
    kpSend({ type = "keyprobe", v = PV, part = "rows", rows = rows })
    -- the game's generated API documentation (pure data) for every function with "Binding" in its name or in C_KeyBindings
    local docs, how = {}, nil
    pcall(function()
        if type(_G.APIDocumentation) ~= "table" and _G.C_AddOns and _G.C_AddOns.LoadAddOn then
            local ok, loaded, why = pcall(_G.C_AddOns.LoadAddOn, "Blizzard_APIDocumentationGenerated")
            how = { ok = ok, loaded = kpScalar(loaded), why = kpScalar(why) }
        end
        local sys = type(_G.APIDocumentation) == "table" and _G.APIDocumentation.systems
        for _, sy in ipairs(type(sys) == "table" and sys or {}) do
            local ns = type(sy.Namespace) == "string" and sy.Namespace or nil
            for _, fn in ipairs(type(sy.Functions) == "table" and sy.Functions or {}) do
                local name = type(fn.Name) == "string" and fn.Name or ""
                if ns == "C_KeyBindings" or name:find("Binding", 1, true) then
                    local d = { ns = ns, sys = kpScalar(sy.Name), n = name, a = {}, r = {}, f = {} }
                    for _, a in ipairs(type(fn.Arguments) == "table" and fn.Arguments or {}) do
                        d.a[#d.a + 1] = tostring(a.Name) .. ":" .. tostring(a.Type) .. (a.Nilable and "?" or "")
                    end
                    for _, a in ipairs(type(fn.Returns) == "table" and fn.Returns or {}) do
                        d.r[#d.r + 1] = tostring(a.Name) .. ":" .. tostring(a.Type) .. (a.Nilable and "?" or "")
                    end
                    for k, v in pairs(fn) do
                        if k ~= "Name" and k ~= "Type" and kpScalar(v) ~= nil then d.f[k] = v end
                    end
                    docs[#docs + 1] = d
                end
            end
        end
    end)
    local per = 20
    local n = math.max(1, math.ceil(#docs / per))
    for i = 1, n do
        local part = {}
        for j = (i - 1) * per + 1, math.min(i * per, #docs) do part[#part + 1] = docs[j] end
        kpSend({ type = "keyprobe", v = PV, part = "docs", i = i, n = n, load = (i == 1) and how or nil, fns = part, total = #docs })
    end
    db.keyProbe = PV
    pcall(WC.push)
    return true
end

-- place what the cursor holds, then read the slot back: true only when it now holds what was meant (PlaceAction answers nothing itself)
local function place(s, kind, id)
    CALL(function() return PlaceAction(s) end)
    CALL(function() return ClearCursor() end)
    local now = readSlot(s)
    return now.kind == kind and now.id == id
end

-- ---------------------------------------------------------------- start attack on cast: the conversion
-- the slots the setting covers: the trigger layers (sets 2-4, all eight) and the base D-pad (set 1, 1-4), every page; never the fixed four
local function attackSlots()
    local out = {}
    for page = 1, PAGES do
        for set = 1, SETS do
            for pos = 1, POSITIONS do
                if not fixed(set, pos) then
                    local s = storage(page, set, pos)
                    if s then out[#out + 1] = s end
                end
            end
        end
    end
    return out
end
local function freeChar()
    local m = macroCounts()
    if not (m.char and m.char.max) then return 0 end
    return math.max(0, m.char.max - m.char.n)
end
-- one slot: a plain harmful spell -> our attack macro. "made" / "full" / "user" / "refused" / nil (not a candidate)
-- m21: the companion's OWN start-attack macros (named "DS Attack <slot>" AND holding our own body) are rewritten to today's lines once;
-- a macro of that name whose body is not ours is never touched
local function attackRefresh()
    if inCombat() or PAUSED then return 0 end                           -- m40: never rewrites a macro
    local n = 0
    for i = 1, 150 do
        local ok, name, _, body = CALL(function() return GetMacroInfo(i) end)
        if ok and isAttackName(name) and WC.isPlain(body) then
            local spell = parseAttack(body)
            if spell and body:find("/target [noexists][dead] softenemy\n/startattack [harm,nodead]\n", 1, true) == 14
                and body ~= attackBody(spell) and (body == "#showtooltip\n/target [noexists][dead] softenemy\n/startattack [harm,nodead]\n/cast " .. spell) then
                CALL(function() return EditMacro(i, name, DYNAMIC_ICON, attackBody(spell)) end)
                n = n + 1
            end
        end
    end
    return n
end
local function attackOne(s)
    local now = readSlot(s)
    if now.kind ~= "spell" or now.sa or not now.id or not now.n or not harmful(now.id) then return nil end
    local name = slotName(ATTACK_PREFIX, s)                            -- m32: never a macro another slot holds (a Move carried it)
    local mi = macroIndex(name)
    if mi then
        local okm, _, _, body = CALL(function() return GetMacroInfo(mi) end)
        if not (okm and WC.isPlain(body) and parseAttack(body)) then return "user" end   -- not ours: never touched
        CALL(function() return EditMacro(mi, name, DYNAMIC_ICON, attackBody(now.n)) end)
    else
        if freeChar() <= 0 then return "full" end
        local okM, idx = CALL(function() return CreateMacro(name, DYNAMIC_ICON, attackBody(now.n), true) end)
        mi = okM and WC.isPlain(idx) and type(idx) == "number" and idx > 0 and idx or macroIndex(name)
        if not mi then return "refused" end
    end
    local slots = attackDB().slots
    slots[s] = { id = now.id, n = now.n }                               -- written BEFORE the change
    CALL(function() return ClearCursor() end)
    CALL(function() return PickupMacro(mi) end)
    CALL(function() return PlaceAction(s) end)
    CALL(function() return ClearCursor() end)
    local after = readSlot(s)
    if after.sa and after.n == now.n then return "made" end
    slots[s] = nil
    dropIfUnused(mi, nil)
    return "refused"
end
-- m36: the character's first login after the change: the old shared record's slots that THIS character's bar proves (the slot holds our
-- attack macro for that very spell) become its own; the rest belonged to other characters. Once per character.
local function attackAdopt()
    local db = _G.WowCompanionSpellsDB
    local old = type(db) == "table" and type(db.attack) == "table" and type(db.attack.slots) == "table" and db.attack.slots or nil
    if not whoKey() then return 0 end
    local a = attackDB()
    if a.adopted then return 0 end
    local n = 0
    for s, rec in pairs(old or {}) do
        if a.slots[s] == nil and type(rec) == "table" and type(rec.n) == "string" then
            local now = readSlot(s)
            if now.sa and now.n == rec.n then a.slots[s] = { id = rec.id, n = rec.n }; n = n + 1 end
        end
    end
    a.adopted = true
    return n
end
-- one recorded slot back to its exact spell (only while it still holds our macro); the macro deleted once unused
local function attackBack(s)
    local slots = attackDB().slots
    local rec = slots[s]
    local now = readSlot(s)
    if now.sa and type(rec) == "table" and type(rec.id) == "number" then
        local bslot = type(WC.spellsBookSlotOf) == "function" and WC.spellsBookSlotOf(rec.id)
        CALL(function() return ClearCursor() end)
        if bslot then
            CALL(function() return C_SpellBook.PickupSpellBookItem(bslot, BANK_PLAYER) end)
        else
            CALL(function() return C_Spell.PickupSpell(rec.id) end)
        end
        CALL(function() return PlaceAction(s) end)
        CALL(function() return ClearCursor() end)
        local after = readSlot(s)
        if after.kind ~= "spell" or after.sa or after.id ~= rec.id then return false end
    end
    slots[s] = nil
    -- our macro for this slot goes once nothing holds it, also when the user had put something else on the button
    local mi = (now.sa and now.mi) or macroIndex(ATTACK_PREFIX .. s)
    if mi then dropIfUnused(mi, now.sa and s or nil) end
    return true
end
-- the combat buttons: their macros written again with / without the start-attack line
local function attackCombatBodies()
    for _, s in ipairs(attackSlots()) do
        local now = readSlot(s)
        if now.kind == "combat" and now.c and now.c.n then
            CALL(function() return EditMacro(now.id, now.n, DYNAMIC_ICON, combatBody(now.c.n, now.o and now.o.n)) end)
        end
    end
end
-- the setting applied (out of combat): ON converts every candidate it can, OFF puts every recorded one back
local function attackApply()
    local a = attackDB()
    if PAUSED then                                                     -- m40: no conversion, no rewrite (the clean-up undoes old ones)
        -- m52: the overlay instead: the router's attack lines (routerMacros) and keys (routerBind); attributes change out of combat only
        if inCombat() then a.pending = true return false, "combat" end
        a.pending = nil
        a.full, a.user, a.refused = nil, nil, nil
        pcall(faceSetup)
        pcall(routerMacros)
        OV.rebind = true
        return true
    end
    if inCombat() then a.pending = true return false, "combat" end
    a.pending = nil
    a.full, a.user, a.refused = nil, nil, nil
    if attackOn() then
        for _, s in ipairs(attackSlots()) do
            local r = attackOne(s)
            if r == "full" or r == "user" or r == "refused" then a[r] = (a[r] or 0) + 1 end
        end
    else
        local left = 0
        for s in pairs(a.slots) do if not attackBack(s) then left = left + 1 end end
        if left > 0 then a.refused = left end
    end
    attackCombatBodies()
    pcall(faceSetup)
    return true
end
-- after a Controller-tab change on slot s (`was` = what was there): our old attack macro let go, and a new harmful spell converted when ON
local function attackAfter(s, was)
    if inCombat() then return end
    if was and was.sa then
        attackDB().slots[s] = nil
        dropIfUnused(was.mi, s)
    end
    if attackOn() and not PAUSED then                                   -- m40: no new attack macro
        local r = attackOne(s)
        if r == "full" or r == "user" then attackDB()[r] = (attackDB()[r] or 0) + 1 end
    end
end

-- the command's General macro: made ONCE, then reused (putCommand and the D-pad home bar); nil + why when it cannot be had
local function commandMacro(c)
    local mi = macroIndex(macroName(c))
    if mi then return mi end
    local m = macroCounts()
    if m.general and m.general.max and m.general.n >= m.general.max then return nil, "no macro slot" end
    local okM, idx = CALL(function() return CreateMacro(macroName(c), c.icon, c.body, false) end)
    mi = okM and WC.isPlain(idx) and type(idx) == "number" and idx > 0 and idx or macroIndex(macroName(c))
    if not mi then return nil, "macro refused" end
    return mi
end

-- ---------------------------------------------------------------- THE D-PAD ROOM BAR (m25)
-- m24 put DS Bar commands into LT+RT slots; the user 2026-10-02: "I don't like this on zlzr, I want this to work just like the in combat
-- bar, and have its own group page that is on the default layer out of combat" -> the router's mode above (no slots). Home's Settings row
-- switches it by our two spells-row columns (Book b15: "dpad-on" / "dpad-off"); Home asks once per character; Cast draws the page.
local function commandBy(key) for _, c in ipairs(COMMANDS) do if c.key == key then return c end end end
-- OURS: the slot holds the macro of that exact name AND our exact body (a player's macro of the same name with another body is not ours)
local function dpadOurs(s, c)
    local r = readSlot(s)
    if r.kind ~= "macro" or r.n ~= macroName(c) or type(r.id) ~= "number" then return false end
    local ok, _, _, body = CALL(function() return GetMacroInfo(r.id) end)
    return ok and WC.isPlain(body) and type(body) == "string" and trim(body) == c.body or false
end
-- the switch: true / false for this character, remembered; refused in a fight (no binding change then; one red pulse)
local function dpadSet(on)
    local op = on and "dpad-on" or "dpad-off"
    if inCombat() then ack(op, false, "combat") return false, "combat" end
    local k = whoKey()
    if not k then ack(op, false, "no character") return false, "no character" end
    dpadDB()[k] = on and true or false
    routerBind("dpad")
    local st = dpadState()
    local ok = (not on and not st.on) or (on and (st.on or st.aside ~= nil))
    ack(op, ok, not ok and "refused" or nil)
    soon()
    return ok
end
-- THE ZL+ZR CLEANUP (the user's pick "Remove them too"): LT+RT (set 4), page 1, D-pad left / up / right lose OUR DS Bar Left / Open /
-- Right (m24's DS Bar Open of 2026-10-02 12:31 and the user's own of 09-30), ONCE per character, out of combat. A player's macro of that
-- name with another body stays; a slot filled again later is never touched again. Bars still loading (nothing on any slot): not yet.
local ZL_SIDES = { { pos = 1, side = "left", key = "DSBARLEFT" }, { pos = 2, side = "up", key = "DSBAROPEN" },
                   { pos = 3, side = "right", key = "DSBARRIGHT" } }
local function zlDB()
    if type(_G.WowCompanionSpellsDB) ~= "table" then _G.WowCompanionSpellsDB = {} end
    local db = _G.WowCompanionSpellsDB
    if type(db.dpadZL) ~= "table" then db.dpadZL = {} end
    return db.dpadZL
end
local function barsLoaded()
    for a = 1, LAST_ACTION_SLOT do
        local ok, kind = CALL(function() return GetActionInfo(a) end)
        if ok and WC.isPlain(kind) and kind ~= nil then return true end
    end
    return false
end
local function zlClean()
    if inCombat() then return false end
    local k = whoKey()
    if not k or type(zlDB()[k]) == "table" then return false end
    if not barsLoaded() then return false end
    local undone, failed = {}, false
    for _, d in ipairs(ZL_SIDES) do
        local s, c = storage(1, 4, d.pos), commandBy(d.key)
        if s and c and dpadOurs(s, c) then
            CALL(function() return ClearCursor() end)
            CALL(function() return PickupAction(s) end)
            CALL(function() return ClearCursor() end)
            if readSlot(s).kind == "none" then undone[#undone + 1] = d.side else failed = true end
        end
    end
    if not failed then zlDB()[k] = { undone = undone } end
    if #undone > 0 then soon() end
    return #undone > 0
end
-- m39 (the user's report 2026-10-09: "you seem to have hardcoded some text about shaman skills"): THE 10-05 REPAIR (m28) IS RETIRED. It
-- was a one-time fix of the user's own Shaman bar (its done record is left in the saved file, unread), the only class-specific code
-- path left, and on every NEW character it answered "state changed" once.
-- the padmap's `dpad` (Home's prompt and Settings row, Cast's page): on = the router holds the bare D-pad for the layout RIGHT NOW (this
-- character wants it, not aside, no room holding, the keys read back, the layout on the buttons) -- m29: in fights too; want (nil = never
-- asked); cols; who; d = position -> what it does (m29: "1" chat, "2" radial, "3" map, "4" home); aside; undone = the ZL+ZR cleanup
startState = function()
    return { key = R.skey, bound = R.sbound, aside = R.aside or nil, menu = menuArmed() or nil }
end
dpadState = function()
    local want = dpadWant()
    local on = false
    -- m29: in fights too; never while a room holds the controller (its room mode gets the D-pad) or the router steps aside
    if want == true and not R.aside and not roomHolder() then
        on = true
        for pos in pairs(ROOMBAR) do
            local b, key = R.dbtn[pos], R.keys[pos]
            if not (b and key and TS(function() return GetBindingAction(key, true) end) == routerAct(b:GetName())
                    and TS(function() return b:GetAttribute("wc-roombar") end)
                    and TS(function() return b:GetAttribute("wc-mode") end) ~= "room") then on = false end
        end
    end
    local d = {}
    for pos, x in pairs(ROOMBAR) do d[tostring(pos)] = x.dir end
    local n, r = who()
    local k = whoKey()
    local z = k and zlDB()[k]
    return { on = on, want = want, cols = WC.spellsDpadCols, who = n and { n = n, r = r } or nil, d = d,
             aside = (want == true and R.aside) or nil, undone = type(z) == "table" and #z.undone > 0 and z.undone or nil }
end


-- ---------------------------------------------------------------- m41 S1: THE CLEAN-UP, per character, on the player's word
-- The user's pick 2026-10-09: "a 'Clean up this character' button in the Controller room's DS tab lists exactly what goes; one yes per
-- character". What the companion put into THIS character's setup before m40, found by name AND body (a player's macro is never touched):
--   * a slot holding "DS Attack <slot>"  -> its exact spell back (the record's id, else the spell the macro casts), the macro deleted;
--   * a slot holding "DS Combat <slot>"  -> its "otherwise" spell or item back (empty when it had none), the macro deleted;
--   * a slot holding a General "DS <command>" macro -> the command becomes this character's OVERLAY entry there (m41: so the button keeps
--     doing it, e.g. "DS Spell Radial"), the slot emptied;
--   * every character macro "DS Attack" / "DS Combat" of ours, deleted.
-- The plan is a "padclean" document (sent at login and on request) so the room can name every item before the one yes; out of combat only.
-- m42 THE SHARED ONES (the user's answer 2026-10-09: "Last, separately": "Each character's cleanup moves its own buttons to the never-saved
-- layer but leaves the shared macros. Once no character still uses them, the DS tab offers "Delete shared DS macros" with its own
-- confirmation."): the account-wide General macros of ours ("DS Bar Left", "DS Spell Radial" ...: our names with our bodies) are NOT
-- deleted by a character's clean-up. Every plan notes whether THIS character still has one on ANY action slot (genUse[name-realm]); the
-- doc's "shared" names them, the characters that still hold one ("users") and the characters DS Forever knows but has not checked since
-- this update ("unchecked"); "ok" once no checked character holds one. Their own run deletes only them, refused while one is held.
local CLEAN = (function()
    local GENERAL_DS = {}
    for _, c in ipairs(COMMANDS) do GENERAL_DS[c.mn] = c end
    local MACRO_SLOTS = 150                 -- the game's macro list: 120 account-wide, 30 per character
    -- m45 (the user's ruling: "deletes ONLY its own old DS macros"): EVERY line must be one our builders write (attackLines /
    -- attackBody / combatBody, any build's); one line of the player's own (a /say, an edit) makes the macro the player's: never deleted
    local OWN_LINE = { "^#showtooltip$", "^/target %[[^%]]*%]%[[^%]]*%] softenemy$", "^/startattack$", "^/startattack %[[^%]]*%]$",
                       "^/click %[[^%]]*%] WowCompanionFaceRearm$", "^/click WowCompanionFaceRearm$", "^/cast .+$", "^/use .+$" }
    local function ownBody(body)
        for line in (body .. "\n"):gmatch("([^\n]*)\n") do
            local l = trim(line)
            if l ~= "" then
                local mine = false
                for _, pat in ipairs(OWN_LINE) do if l:find(pat) then mine = true break end end
                if not mine then return false end
            end
        end
        return true
    end
    local function ourMacro(i)
        local ok, n, _, body = CALL(function() return GetMacroInfo(i) end)
        if not ok or type(n) ~= "string" or not WC.isPlain(n) or not WC.isPlain(body) or type(body) ~= "string" then return nil end
        if isAttackName(n) and parseAttack(body) and ownBody(body) then return "char", n end
        if isCombatName(n) and parseCombat(body) and ownBody(body) then return "char", n end
        local c = GENERAL_DS[n]
        if c and trim(body) == trim(c.body) then return "general", n end
    end
    local function genDB()
        if type(_G.WowCompanionSpellsDB) ~= "table" then _G.WowCompanionSpellsDB = {} end
        local db = _G.WowCompanionSpellsDB
        if type(db.genUse) ~= "table" then db.genUse = {} end
        return db.genUse
    end
    -- m42: this character has one of the shared macros on ANY action slot (not only the pad's), by its name (this client reports a
    -- macro that resolves to a spell as the spell; GetActionText still names the macro)
    local function holdsShared()
        local function has(s)
            local n = TS(function() return GetActionText(s) end)
            return n and GENERAL_DS[n] and true or false
        end
        for s = 1, 180 do if has(s) then return true end end           -- the bars
        for page = 1, PAGES do                                          -- the gamepad's own storage slots (above 180)
            for set = 1, SETS do
                for pos = 1, POSITIONS do
                    local s = storage(page, set, pos)
                    if s and s > 180 and has(s) then return true end
                end
            end
        end
        return false
    end
    -- the characters DS Forever knows (any of its per-character records), "Name-Realm"
    local function knownChars()
        local db, out = _G.WowCompanionSpellsDB, {}
        if type(db) ~= "table" then return out end
        for _, t in ipairs({ db.faceBy, db.attackBy, db.dpad, db.dpadZL, db.binds, db.ccOff, db.cmdBy, db.genUse, db.formFightBy,
                             db.smartBuffs }) do
            if type(t) == "table" then
                for k in pairs(t) do if type(k) == "string" and k:find("-", 1, true) then out[k] = true end end
            end
        end
        return out
    end
    local function sharedState()
        local st = { names = {}, users = {}, unchecked = {} }
        for i = 1, MACRO_SLOTS do
            local where, n = ourMacro(i)
            if where == "general" then st.names[#st.names + 1] = n end
        end
        local use = genDB()
        for k in pairs(knownChars()) do
            if use[k] == true then st.users[#st.users + 1] = k elseif use[k] == nil then st.unchecked[#st.unchecked + 1] = k end
        end
        table.sort(st.users); table.sort(st.unchecked)
        st.ok = #st.names > 0 and #st.users == 0
        return st
    end
    local function cleanupPlan()
        local plan = { slots = {}, char = {}, general = {} }
        if not whoKey() then return plan end
        genDB()[whoKey()] = holdsShared()                               -- m42: checked now
        local seen = {}
        for page = 1, PAGES do
            for set = 1, SETS do
                for pos = 1, POSITIONS do
                    local s = (not fixed(set, pos)) and storage(page, set, pos)
                    if s and not seen[s] then
                        seen[s] = true
                        local now = readSlot(s)
                        local mname = (now.kind == "macro" or now.kind == "combat" or now.sa) and TS(function() return GetActionText(s) end) or nil
                        local e = { s = s, page = page, set = set, pos = pos, what = mname }
                        if now.sa then
                            local rec = attackDB().slots[s]
                            e.kind, e.back, e.id = "attack", now.n, (type(rec) == "table" and rec.n == now.n and rec.id) or now.id
                        elseif now.kind == "combat" then
                            local o = now.o
                            e.kind, e.back = "combat", o and o.n or nil
                            if now.c and now.c.n then e.fight, e.fid, e.fi = now.c.n, now.c.id, now.c.i end   -- m43: kept as the overlay
                            if o and o.item then e.item = o.id else e.id = o and o.id or nil end
                        elseif now.kind == "macro" and mname and GENERAL_DS[mname] then
                            e.kind, e.cmd = "command", GENERAL_DS[mname].key
                        end
                        if e.kind then plan.slots[#plan.slots + 1] = e end
                    end
                end
            end
        end
        for i = 1, MACRO_SLOTS do
            local where, n = ourMacro(i)
            if where then local t = plan[where]; t[#t + 1] = n end
        end
        plan.n = #plan.slots + #plan.char                                -- m42: the shared ones are their own step
        return plan
    end
    local function cleanupDoc(why, extra)
        local plan = cleanupPlan()
        local st = sharedState()
        local d = { type = "padclean", v = PV, why = why, fight = inCombat() or nil, n = plan.n, char = plan.char, slots = {},
                    shared = { names = st.names, users = st.users, unchecked = st.unchecked, ok = st.ok or nil } }
        local who = whoKey()
        d.who = who
        for _, e in ipairs(plan.slots) do
            d.slots[#d.slots + 1] = { s = e.s, page = e.page, set = e.set, pos = e.pos, kind = e.kind, what = e.what, back = e.back,
                                      cmd = e.cmd, fight = e.fight }
        end
        for k, v in pairs(extra or {}) do d[k] = v end
        WC.send(WC.encode(d))
        if type(WC.flush) == "function" then pcall(WC.flush) else WC.push() end
        return plan
    end
    local function clearSlot(s)
        CALL(function() return ClearCursor() end)
        CALL(function() return PickupAction(s) end)
        CALL(function() return ClearCursor() end)
        return readSlot(s).kind == "none"
    end
    -- the spell (its exact id first: its rank) or the item back on slot s; empty when it cannot be had
    local function putBack(s, e)
        CALL(function() return ClearCursor() end)
        if e.item then
            CALL(function() if type(PickupItem) == "function" then return PickupItem(e.item) end return C_Item.PickupItem(e.item) end)
            if place(s, "item", e.item) then return "back" end
        elseif e.id or e.back then
            local bslot = e.id and type(WC.spellsBookSlotOf) == "function" and WC.spellsBookSlotOf(e.id)
            if not bslot and e.back then local b = bookByName()[e.back]; bslot = b and b.slot end
            if bslot then
                CALL(function() return C_SpellBook.PickupSpellBookItem(bslot, BANK_PLAYER) end)
                CALL(function() return PlaceAction(s) end)
                CALL(function() return ClearCursor() end)
                local now = readSlot(s)
                if now.kind == "spell" and not now.sa then return "back" end
            end
        end
        CALL(function() return ClearCursor() end)
        return clearSlot(s) and "cleared" or "left"
    end
    local function cleanupRun()
        if inCombat() then ack("cleanup", false, "combat") return false, "combat" end
        if not whoKey() then ack("cleanup", false, "no character") return false, "no character" end
        local plan = cleanupPlan()
        local done = { back = 0, cleared = 0, moved = 0, macros = 0, left = 0 }
        for _, e in ipairs(plan.slots) do
            if e.kind == "command" then
                if not (e.set == 1 and e.pos <= 4 and dpadWant() == true) then
                    OV.db()[e.s] = { k = e.cmd, page = e.page, set = e.set, pos = e.pos }
                    done.moved = done.moved + 1
                end
                if clearSlot(e.s) then done.cleared = done.cleared + 1 else done.left = done.left + 1 end
            else
                local r = putBack(e.s, e)
                done[r] = (done[r] or 0) + 1
                -- m45 (the user after test14: "if I was someone logging in new this should be blank"): an old combat button's fight
                -- spell is NOT kept; the In-combat settings start empty
            end
        end
        -- this character's macros of ours, from the top of the list down (a deletion moves the ones after it); m42: never the shared ones
        for i = MACRO_SLOTS, 1, -1 do
            if ourMacro(i) == "char" then CALL(function() return DeleteMacro(i) end); done.macros = done.macros + 1 end
        end
        attackDB().slots = {}
        pcall(routerMacros); pcall(routerBind, "cleanup")
        local after = cleanupPlan()
        local ok = after.n == 0
        ack("cleanup", ok, not ok and "left" or nil)
        cleanupDoc("done", { done = done })
        soon()
        return ok, done
    end
    -- m45 THE AUTOMATIC REMOVAL (the user's pick after test14, "Remove silently, start blank": "No button. Out of combat, DS Forever
    -- quietly deletes ONLY its own old DS macros once, and your In-combat D-pad starts empty for you to fill. A new player never sees any
    -- of this."): this character's macros of OURS only (an exact "DS Combat ..." / "DS Attack ..." name AND a body in our own format:
    -- ourMacro), deleted where they are - no bar, binding or other macro is touched (a deleted macro's buttons empty themselves). Out of
    -- combat only; a fight defers it to its end. Nothing to remove = nothing done, nothing said. Returns how many went.
    local autoPending = false
    local function autoRun(why)
        if inCombat() then autoPending = true return 0 end
        autoPending = false
        if not whoKey() then return 0 end
        local n = 0
        for i = MACRO_SLOTS, 1, -1 do
            if ourMacro(i) == "char" then
                local ok = CALL(function() return DeleteMacro(i) end)
                if ok then n = n + 1 end
            end
        end
        -- m46 (the user's pick after test15, "Remove them silently too": "Same rule: only our exact macros, deleted once none of your
        -- characters still has one on a button. Your own General macros are never touched"): the account-wide General macros of ours
        -- (an exact name AND an exact body: ourMacro's "general"), with m42's safety check - this character recorded first; none while
        -- any known character still holds one on a button
        local shared = 0
        genDB()[whoKey()] = holdsShared()
        if sharedState().ok then
            for i = MACRO_SLOTS, 1, -1 do
                if ourMacro(i) == "general" then
                    local ok = CALL(function() return DeleteMacro(i) end)
                    if ok then shared = shared + 1 end
                end
            end
        end
        if n > 0 or shared > 0 then
            if n > 0 then attackDB().slots = {} end                      -- the old start-attack record named those macros
            pcall(routerMacros); pcall(routerBind, "autoclean")
            WC.send(WC.encode({ type = "padclean", v = PV, why = "auto", removed = n + shared, char = n, shared = shared, src = why }))
            if type(WC.flush) == "function" then pcall(WC.flush) else WC.push() end
            soon()
        end
        return n + shared
    end
    -- m42: the shared ones, their own run (out of combat; refused while a checked character still holds one)
    local function sharedRun()
        if inCombat() then ack("cleanshared", false, "combat") return false, "combat" end
        if not whoKey() then ack("cleanshared", false, "no character") return false, "no character" end
        cleanupPlan()                                                   -- this character checked now
        local st = sharedState()
        if #st.names == 0 then ack("cleanshared", false, "none") return false, "none" end
        if #st.users > 0 then ack("cleanshared", false, "in use") return false, "in use" end
        local n = 0
        for i = MACRO_SLOTS, 1, -1 do
            if ourMacro(i) == "general" then CALL(function() return DeleteMacro(i) end); n = n + 1 end
        end
        local ok = #sharedState().names == 0
        ack("cleanshared", ok, not ok and "left" or nil)
        cleanupDoc("done", { sharedDone = n })
        soon()
        return ok, n
    end
    return { plan = cleanupPlan, doc = cleanupDoc, run = cleanupRun, shared = sharedState, sharedRun = sharedRun, auto = autoRun,
             autoPending = function() return autoPending end }
end)()
-- m54 (b-combat-bar-states, the user on 1.0.8-test2: "The combat bars are not showing when an ability does not have enough mana, or if
-- it was selected for casting like the normal bars do"): the character's IN-COMBAT spells (X / Y / A's fight spells, the overlay's on
-- any button), for ActionBar.lua's state document: their ids, once each. Read only. (On WC: the main chunk is at Lua's 200 locals.)
WC.padMapFightSpells = function()
    local out, seen = {}, {}
    local function add(e) if type(e) == "table" and type(e.id) == "number" and not seen[e.id] then seen[e.id] = true; out[#out + 1] = e.id end end
    for _, e in pairs(faceStore()) do add(e) end
    if combatOn() then for _, e in pairs(OV.ftdb()) do add(e) end end
    -- m58 (the user on 1.0.10-test2, a druid: "my Wrath ability on my Y button never shows when it's too low mana to cast, like with other
    -- abilities"): a FORM's own In-combat spell (m55, formFightBy) is what Home draws on that button in the form, and it was never listed,
    -- so it had no state by spell id: no mana blue, no range red. Every form's own ones too (by spell, not by slot: the router casts them)
    for _, t in pairs(OV.ffdb()) do if type(t) == "table" then for _, e in pairs(t) do add(e) end end end
    return out
end

WC.padMap = {
    version = 1,
    emit = function(force) if emit(force) then WC.push() end end,
    showPage = function(page)
        if type(page) ~= "number" or page < 1 or page > PAGES then return false, "bad page" end
        shownPage = page
        emit(true); WC.push()
        return true
    end,
    commands = function() return commandList() end,
    extraList = function() return extraList() end,             -- m33: the Extra tab, its order
    -- read-only views for the tab's controller half (PadTabCtl.lua)
    page = function() return shownPage or gamePage() end,
    slotAt = function(page, set, pos)
        if fixed(set, pos) then return { kind = "fixed", n = word(FIXED[pos]), pos = pos, face = FACE[pos] or nil, cf = faceRef(pos) } end
        local s = storage(page, set, pos)
        return OV.view(set, pos, s)                                    -- m41 / m43: the overlay's command, its fight spell
    end,
    -- m34 a game binding onto a button (out of combat; the place must allow it): stored for the character, the router binds it
    putBind = function(page, set, pos, key)
        local s, why = target(page, set, pos)
        if not s then ack("bind", false, why) return false, why end
        if not bindByKey(key) then ack("bind", false, "unknown", s) return false, "unknown" end
        local ok, bwhy = bindAllowed(set, pos)
        if not ok then ack("bind", false, bwhy, s) return false, bwhy end
        if not bindChord(set, pos) then ack("bind", false, "no key", s) return false, "no key" end
        bindStore()[set .. ":" .. pos] = key
        OV.db()[s] = nil                                                -- m41: one thing per button: a command there goes
        pcall(routerMacros)
        pcall(routerBind, "bind")
        ack("bind", true, nil, s)
        soon()
        return true
    end,
    bindAllowed = function(set, pos) return bindAllowed(set, pos) end,
    made = function(key) return OV.byKey(key) ~= nil end,           -- m41: the overlay needs no macro
    commandName = function(key) for _, c in ipairs(COMMANDS) do if c.key == key then return commandName(c) end end end,
    macrosFull = function()
        local m = macroCounts()
        return m.general ~= nil and m.general.max ~= nil and m.general.n >= m.general.max
    end,
    -- a spell from the book (its book slot) onto a button
    putSpell = function(page, set, pos, bookSlot)
        local s, why = target(page, set, pos)
        if not s then ack("spell", false, why) return false, why end
        OV.off(s)                                                       -- m41
        if type(bookSlot) ~= "number" then ack("spell", false, "bad spell", s) return false, "bad spell" end
        local okI, info = CALL(function() return C_SpellBook.GetSpellBookItemInfo(bookSlot, BANK_PLAYER) end)
        local spellID = okI and type(info) == "table" and TN(function() return info.spellID end)
        if not spellID then ack("spell", false, "bad spell", s) return false, "bad spell" end
        local was = readSlot(s)
        CALL(function() return ClearCursor() end)
        CALL(function() return C_SpellBook.PickupSpellBookItem(bookSlot, BANK_PLAYER) end)
        local ok = place(s, "spell", spellID)
        if ok then attackAfter(s, was) end
        ack("spell", ok, not ok and "refused" or nil, s)
        soon()
        return ok
    end,
    -- m33 AN ITEM from the bags onto a button, the game's own way (no macro): pick it up by its id, place, read the slot back
    putItem = function(page, set, pos, itemID)
        local s, why = target(page, set, pos)
        if not s then ack("item", false, why) return false, why end
        OV.off(s)                                                       -- m41
        if type(itemID) ~= "number" then ack("item", false, "bad item", s) return false, "bad item" end
        local was = readSlot(s)
        CALL(function() return ClearCursor() end)
        CALL(function() if type(PickupItem) == "function" then return PickupItem(itemID) end return C_Item.PickupItem(itemID) end)
        local ok = place(s, "item", itemID)
        if ok then attackAfter(s, was) end
        ack("item", ok, not ok and "refused" or nil, s)
        soon()
        return ok
    end,
    -- m33 A MOUNT from the Mount Journal onto a button, the game's own way (the journal's own pickup by its displayed place; when the
    -- journal's filters hide it, its summon spell)
    putMount = function(page, set, pos, mountID)
        local s, why = target(page, set, pos)
        if not s then ack("mount", false, why) return false, why end
        OV.off(s)                                                       -- m41
        if type(mountID) ~= "number" then ack("mount", false, "bad mount", s) return false, "bad mount" end
        local was = readSlot(s)
        CALL(function() return ClearCursor() end)
        local at = nil
        local okn, n = CALL(function() return C_MountJournal.GetNumDisplayedMounts() end)
        for i = 1, (okn and type(n) == "number" and WC.isPlain(n) and n or 0) do
            local okd, id = CALL(function() return C_MountJournal.GetDisplayedMountID(i) end)
            if okd and id == mountID then at = i break end
        end
        if at then CALL(function() return C_MountJournal.Pickup(at) end)
        else
            local okm, _, spellID = CALL(function() return C_MountJournal.GetMountInfoByID(mountID) end)
            if okm and type(spellID) == "number" then CALL(function() return C_Spell.PickupSpell(spellID) end) end
        end
        CALL(function() return PlaceAction(s) end)
        CALL(function() return ClearCursor() end)
        local now = readSlot(s)
        local ok = now.mount == mountID
        if ok then attackAfter(s, was) end
        ack("mount", ok, not ok and "refused" or nil, s)
        soon()
        return ok
    end,
    -- a game command onto a button: its macro is made ONCE (a General macro), then reused
    -- m41: a command onto a button = this character's OVERLAY entry for that slot (no macro, the bar slot untouched)
    putCommand = function(page, set, pos, key)
        local s, why = target(page, set, pos)
        if not s then ack("command", false, why) return false, why end
        local c = OV.byKey(key)
        if not c then ack("command", false, "unknown command", s) return false, "unknown command" end
        if set == 1 and pos <= 4 and dpadWant() == true then ack("command", false, "dpad bar", s) return false, "dpad bar" end
        bindStore()[set .. ":" .. pos] = nil                             -- one thing per button: a binding there goes
        OV.db()[s] = { k = c.key, page = page, set = set, pos = pos }
        pcall(routerMacros); pcall(routerBind, "command")
        ack("command", true, nil, s)
        soon()
        return true
    end,
    -- m32 MOVE (the user 2026-10-07: "I want the ability to Move a skill or ability"; "hovering over a button with a spell swaps with A"):
    -- what is on one button goes onto another, and a taken target SWAPS. The game's own cursor road, out of combat: pick the source up,
    -- place it on the target (the game hands back what was there), place that on the source. Anything moves whole (a spell, an item, a
    -- macro, a mount, a combat or start-attack button); the start-attack records follow their buttons. Neither may be a fixed button.
    move = function(fpage, fset, fpos, tpage, tset, tpos)
        local fs, why = target(fpage, fset, fpos)
        if not fs then ack("move", false, why) return false, why end
        local ts, twhy = target(tpage, tset, tpos)
        if not ts then ack("move", false, twhy, fs) return false, twhy end
        if fs == ts then ack("move", false, "same", fs) return false, "same" end
        -- m43: the fight spells go with the move (a swap swaps them); one on an otherwise empty button moves alone
        local function moveFight()
            local db, ff, tf = OV.ftdb(), OV.ftAt(fs), OV.ftAt(ts)
            db[ts] = ff and { id = ff.id, n = ff.n, i = ff.i, page = tpage, set = tset, pos = tpos } or nil
            db[fs] = tf and { id = tf.id, n = tf.n, i = tf.i, page = fpage, set = fset, pos = fpos } or nil
            pcall(routerMacros)
        end
        if OV.ftAt(fs) and not OV.at(fs) and readSlot(fs).kind == "none" then
            if tset == 1 and tpos <= 4 and dpadWant() == true then ack("move", false, "dpad bar", ts) return false, "dpad bar" end
            moveFight()
            ack("move", true, nil, ts)
            soon()
            return true
        end
        -- m41: an overlay command moves as the overlay (a taken target swaps); the bar slots underneath stay the player's
        local fe, te = OV.at(fs), OV.at(ts)
        if fe or te then
            if not fe then ack("move", false, "command-there", ts) return false, "command-there" end
            if tset == 1 and tpos <= 4 and dpadWant() == true then ack("move", false, "dpad bar", ts) return false, "dpad bar" end
            if te and fset == 1 and fpos <= 4 and dpadWant() == true then ack("move", false, "dpad bar", fs) return false, "dpad bar" end
            local db = OV.db()
            db[fs] = te and { k = te.k, page = fpage, set = fset, pos = fpos } or nil
            db[ts] = { k = fe.k, page = tpage, set = tset, pos = tpos }
            bindStore()[tset .. ":" .. tpos] = nil
            moveFight()                                                  -- m43
            pcall(routerMacros); pcall(routerBind, "command")
            ack("move", true, nil, ts)
            soon()
            return true
        end
        local a, b = readSlot(fs), readSlot(ts)
        if a.kind == "none" then ack("move", false, "empty", fs) return false, "empty" end
        CALL(function() return ClearCursor() end)
        CALL(function() return PickupAction(fs) end)
        CALL(function() return PlaceAction(ts) end)
        local okC, held = CALL(function() return GetCursorInfo() end)
        if okC and held ~= nil then CALL(function() return PlaceAction(fs) end) end
        CALL(function() return ClearCursor() end)
        local a2, b2 = readSlot(fs), readSlot(ts)
        local function same(x, y) return x.kind == y.kind and x.id == y.id and x.n == y.n end
        local ok = same(b2, a) and ((b.kind == "none" and a2.kind == "none") or same(a2, b))
        if ok then
            local recs = attackDB().slots
            recs[fs], recs[ts] = recs[ts], recs[fs]
            moveFight()                                                  -- m43
            if routerMacrosSoon then routerMacrosSoon() end              -- the router reads slot macro texts per slot (m26)
        end
        ack("move", ok, not ok and "refused" or nil, ts)
        soon()
        return ok
    end,
    -- empty a button (the room asks first, naming what is on it); a combat button's own macro goes too when no other slot uses it
    clear = function(page, set, pos)
        local s, why = target(page, set, pos)
        if not s then ack("clear", false, why) return false, why end
        if OV.ftAt(s) then OV.ftdb()[s] = nil; pcall(routerMacros) end   -- m43: the whole button, its fight spell too
        if OV.at(s) then                                                -- m41: the overlay command comes off; the bar slot stays as it is
            OV.db()[s] = nil
            pcall(routerMacros); pcall(routerBind, "uncommand")
            ack("clear", true, nil, s)
            soon()
            return true
        end
        if bindAt(set, pos) then                                        -- m34: a binding comes off; the button's own action stays
            bindStore()[set .. ":" .. pos] = nil
            pcall(routerBind, "unbind")
            ack("clear", true, nil, s)
            soon()
            return true
        end
        local was = readSlot(s)
        CALL(function() return ClearCursor() end)
        CALL(function() return PickupAction(s) end)
        CALL(function() return ClearCursor() end)
        local ok = readSlot(s).kind == "none"
        if ok and was.kind == "combat" then dropIfUnused(was.id, s) end
        if ok then attackAfter(s, was) end
        ack("clear", ok, not ok and "refused" or nil, s)
        soon()
        return ok
    end,
    -- m55 A FORM'S OWN In-combat spell on the bare set's position (1-4 the D-pad, 5 / 6 / 8 the faces), out of combat; nothing of the
    -- player's changes (the router's header text, as every fight spell)
    putCombatForm = function(form, pos, bookSlot)
        if inCombat() then ack("cform", false, "combat") return false, "combat" end
        if not OV.formOf(form) then ack("cform", false, "no form") return false, "no form" end
        if type(pos) ~= "number" or pos < 1 or pos > 8 or pos == 7 then ack("cform", false, "reserved") return false, "reserved" end
        if not whoKey() then ack("cform", false, "no character") return false, "no character" end
        local id, name = bookSpell(bookSlot)
        if not name then ack("cform", false, "bad spell") return false, "bad spell" end
        local r = spellRef(name) or {}
        local t = OV.ffdb()
        if type(t[form]) ~= "table" then t[form] = {} end
        t[form][pos] = { id = id, n = name, i = r.i or 0 }
        pcall(routerMacros)
        ack("cform", true)
        soon()
        return true
    end,
    clearCombatForm = function(form, pos)
        if inCombat() then ack("uncform", false, "combat") return false, "combat" end
        local t = OV.ffdb()[form]
        if type(t) == "table" then t[pos] = nil; if next(t) == nil then OV.ffdb()[form] = nil end end
        pcall(routerMacros)
        ack("uncform", true)
        soon()
        return true
    end,
    -- what a form's position holds (PadTabCtl's In combat view with a form chosen): its own entry, or nil
    formFight = function(form, pos) return OV.ffAt(form, pos) end,
    forms = function() return OV.forms() end,
    -- m57: the room's chosen form for the out-of-combat bars (0 / nil = the caster's); its slot for a place (Book's bind); the form bar's set
    formEdit = function(id)
        local fe = (type(id) == "number" and id ~= 0) and id or nil
        if fe ~= OV.fe then OV.fe = fe; soon() end
    end,
    formSlot = function(form, pos)
        local first = OV.fsdb()[form]
        return type(first) == "number" and (first + pos - 1) or nil
    end,
    stance = function() return OV.stanceAt() end,
    -- THE BASE FACE BUTTONS IN FIGHTS: a spell for bare X / Y / A (B stays the game's Back); no macro, the companion's secure header
    putCombatFace = function(pos, bookSlot)
        if inCombat() then ack("cface", false, "combat") return false, "combat" end
        if not FACE[pos] then ack("cface", false, "not a face button") return false, "not a face button" end
        local id, name = bookSpell(bookSlot)
        if not name then ack("cface", false, "bad spell") return false, "bad spell" end
        faceStore()[pos] = { id = id, n = name }
        local ok, why = faceSetup()
        ack("cface", ok and true or false, not ok and (why or "refused") or nil)
        soon()
        return ok
    end,
    clearCombatFace = function(pos)
        if inCombat() then ack("uncface", false, "combat") return false, "combat" end
        if not FACE[pos] then ack("uncface", false, "not a face button") return false, "not a face button" end
        faceStore()[pos] = nil
        local ok, why = faceSetup()
        ack("uncface", ok and true or false, not ok and (why or "refused") or nil)
        soon()
        return ok
    end,
    faceSetup = function() return faceSetup() end,
    -- Home's B shrank the controller OUT of a fight: give A / X / Y back until the next start. Idempotent; refused in a fight.
    -- m25: the D-pad room bar (Home's Settings row, by our two spells-row columns): true = on, false = off (remembered per character);
    -- refused in a fight
    dpadBar = function(on) return dpadSet(on and true or false) end,
    -- m37: Start's next press opens the game's own Game Menu (the pause row's column 10); out of combat only
    menuArm = function()
        if inCombat() then return false, "combat" end
        if dpadWant() ~= true then return false, "toolbar off" end      -- m38: Start is the game's own (its radial has the Game Menu)
        R.menuAt = GetTime()
        routerBind("menu")
        pcall(emit, true); WC.push()
        if R.sbound == "menu" then return true end
        return false, R.aside and "aside" or "refused"
    end,
    startState = function() return startState() end,
    -- m40: true while the macro-based features are paused (the room asks before it opens a chooser that would need one)
    paused = function() return PAUSED end,
    -- m41 S1: the clean-up's plan (the "padclean" document) and the run (out of combat; the player's one yes per character)
    cleanupDoc = function(why) return CLEAN.doc(why or "ask") end,
    cleanupRun = function() return CLEAN.run() end,
    autoClean = function(why) return CLEAN.auto(why or "test") end,                  -- m45
    cleanupCount = function() return CLEAN.plan().n end,
    -- m42: the shared macros' own step: true while it can run (some left, no checked character holds one)
    sharedOk = function() CLEAN.plan() return CLEAN.shared().ok end,         -- this character checked now
    sharedRun = function() return CLEAN.sharedRun() end,
    dpadState = function() return dpadState() end,
    zlClean = function() return zlClean() end,
    -- m45: the m23 class starter "suggestion" is gone (the user: remove it): nothing is ever suggested; empty means empty
    -- m22, Interact's contract: the keys the router can carry and their buttons (stable names, made at login, never destroyed)
    routerButtons = function()
        local out = {}
        for _, x in ipairs(routerAll()) do
            local key = R.keys[x.pos]
            if key then out[key] = x.b:GetName() end
        end
        return out
    end,
    routerReady = function() return R.hdr ~= nil and R.bbtn ~= nil and #routerAll() == 8 end,
    giveBack = function()
        if inCombat() or face.fight then return false, "combat" end
        local was = face.given
        face.given = true
        face.givenSet = true
        local ok, changed = pcall(faceUpdate, "giveback")
        if (ok and changed) or not was then pcall(emit, true); WC.push() end
        return true
    end,
    -- START ATTACK ON CAST, from Home's Settings room (Book.lua's "sa-on" / "sa-off" columns): idempotent; in a fight it waits for the end
    setAttack = function(on)
        local a = attackDB()
        a.on = on and true or false
        a.chosen = true
        local ok, why = attackApply()
        ack("attack", true, not ok and why or nil)
        emit(true); WC.push()
        return true
    end,
    attackOn = function() return attackOn() end,
    -- m27: COMBAT CONTROLS on / off (the Controller room's toggle, Book b17's "cc-on" / "cc-off" columns), per character, remembered;
    -- refused in a fight (the bindings change); idempotent
    combatOn = function() return combatOn() end,
    setCombat = function(on)
        local op = on and "cc-on" or "cc-off"
        if inCombat() then ack(op, false, "combat") return false, "combat" end
        local k = whoKey()
        if not k then ack(op, false, "no character") return false, "no character" end
        ccDB()[k] = (not on) and true or nil
        local ok = faceSetup()
        pcall(routerMacros)                                            -- m43: the buttons' fight spells follow the switch
        ack(op, true)
        emit(true); WC.push()
        return true
    end,
    charFull = function()
        local m = macroCounts()
        return m.char ~= nil and m.char.max ~= nil and m.char.n >= m.char.max
    end,
    -- THE COMBAT SPELL of a button (a book slot): m43, the overlay (fightBy, the router's "wc-ft-<slot>"); nothing of the player's changes
    putCombat = function(page, set, pos, bookSlot)
        local s, why = target(page, set, pos)
        if not s then ack("combat", false, why) return false, why end
        local cid, cname = bookSpell(bookSlot)
        if not cname then ack("combat", false, "bad spell", s) return false, "bad spell" end
        -- m43 THE OVERLAY: stored for this character, run by the router in fights; the button itself is not touched
        if bindAt(set, pos) then ack("combat", false, "bind", s) return false, "bind" end
        -- m44: the D-pad's four slots take fight spells too (in fights they cast; out of combat the D-pad stays DS Forever's)
        if not whoKey() then ack("combat", false, "no character", s) return false, "no character" end
        local r = spellRef(cname) or {}
        OV.ftdb()[s] = { id = cid, n = cname, i = r.i or 0, page = page, set = set, pos = pos }
        pcall(routerMacros)
        ack("combat", true, nil, s)
        soon()
        return true
    end,
    -- the OTHERWISE spell of a combat button (the out-of-combat view's Change): its macro changed in place
    putOtherwise = function(page, set, pos, bookSlot)
        local s, why = target(page, set, pos)
        if not s then ack("otherwise", false, why) return false, why end
        -- m43: a button with an overlay fight spell: its Otherwise IS the player's own button (a plain toolbar edit; the fight spell stays)
        if OV.ftAt(s) and readSlot(s).kind ~= "combat" then
            local bslot = type(bookSlot) == "number" and bookSlot
            local _, oname = bookSpell(bookSlot)
            if not (bslot and oname) then ack("otherwise", false, "bad spell", s) return false, "bad spell" end
            local was = readSlot(s)
            CALL(function() return ClearCursor() end)
            CALL(function() return C_SpellBook.PickupSpellBookItem(bslot, BANK_PLAYER) end)
            CALL(function() return PlaceAction(s) end)
            CALL(function() return ClearCursor() end)
            local ok = readSlot(s).kind == "spell"
            if ok then OV.off(s); attackAfter(s, was) end                 -- a command there gives way, as a spell put does
            ack("otherwise", ok, not ok and "refused" or nil, s)
            soon()
            return ok
        end
        local now = readSlot(s)
        if now.kind ~= "combat" then ack("otherwise", false, "not a combat button", s) return false, "not a combat button" end
        if PAUSED then ack("otherwise", false, "paused", s) return false, "paused" end  -- m40: an old "DS Combat" macro: the clean-up's
        local _, oname = bookSpell(bookSlot)
        if not oname then ack("otherwise", false, "bad spell", s) return false, "bad spell" end
        local ok = CALL(function() return EditMacro(now.id, now.n, DYNAMIC_ICON, combatBody(now.c.n, oname)) end)
        ok = ok and readSlot(s).kind == "combat"
        ack("otherwise", ok and true or false, not ok and "refused" or nil, s)
        soon()
        return ok
    end,
    -- take the combat spell away: the otherwise spell goes back on the button (or it is empty), the companion's macro is deleted when no
    -- other slot uses it (the room asks first, naming it)
    clearCombat = function(page, set, pos)
        local s, why = target(page, set, pos)
        if not s then ack("uncombat", false, why) return false, why end
        if OV.ftAt(s) then                                              -- m43: the overlay's fight spell comes off; the button stays
            OV.ftdb()[s] = nil
            pcall(routerMacros)
            ack("uncombat", true, nil, s)
            soon()
            return true
        end
        local now = readSlot(s)
        if now.kind ~= "combat" then ack("uncombat", false, "not a combat button", s) return false, "not a combat button" end
        local oitem = now.o and now.o.item and now.o.id or nil                                  -- m32: the item it kept
        local oslot = not oitem and now.o and now.o.id and type(WC.spellsBookSlotOf) == "function" and WC.spellsBookSlotOf(now.o.id)
        CALL(function() return ClearCursor() end)
        if oitem then
            CALL(function() if type(PickupItem) == "function" then return PickupItem(oitem) end return C_Item.PickupItem(oitem) end)
            CALL(function() return PlaceAction(s) end)
        elseif oslot then
            CALL(function() return C_SpellBook.PickupSpellBookItem(oslot, BANK_PLAYER) end)
            CALL(function() return PlaceAction(s) end)
        else
            CALL(function() return PickupAction(s) end)
        end
        CALL(function() return ClearCursor() end)
        local after = readSlot(s)
        local ok = (oitem and after.kind == "item" and after.id == oitem) or (oslot and after.kind == "spell")
                   or (not oitem and not oslot and after.kind == "none")
        if ok then dropIfUnused(now.id, s); attackAfter(s, nil) end           -- the spell back: ON converts it again
        ack("uncombat", ok and true or false, not ok and "refused" or nil, s)
        soon()
        return ok
    end,
}

-- ---------------------------------------------------------------- events

local f = CreateFrame("Frame")
f:SetScript("OnEvent", function(_, e, a1, a2)
    local trig = nil
    if e == "PLAYER_REGEN_DISABLED" then
        -- FIRST: the lockdown begins right after. The router binds again (rooms let go now; a game window keeps it aside)
        fightNow.on = true                                                -- m48: before anything below emits
        face.fight = true; face.given = false; face.givenSet = true; trig = "fight"
        pcall(routerState, "fight")
        pcall(routerMacros)                                               -- m26: the slots' macro texts as the fight starts
        pcall(routerBind, "fight", true)
    elseif e == "PLAYER_REGEN_ENABLED" then
        fightNow.on = false
        face.fight = false; trig = "fight-end"
        pcall(routerState, "idle")
        pcall(routerBind, "fight-end")
    elseif e == "PLAYER_ENTER_COMBAT" then if not face.aa then face.given = false; face.givenSet = true end; face.aa = true; trig = "attack"   -- a NEW start
    elseif e == "PLAYER_LEAVE_COMBAT" then face.aa = false; trig = "attack-end"
    elseif e == "START_AUTOREPEAT_SPELL" then if not face.ar then face.given = false; face.givenSet = true end; face.ar = true; trig = "repeat"
    elseif e == "STOP_AUTOREPEAT_SPELL" then face.ar = false; trig = "repeat-end"
    end
    if trig and not (face.aa or face.ar or face.fight) then face.given = false; face.givenSet = true end   -- all over: nothing stays given back
    if trig then
        local ok, changed = pcall(faceUpdate, trig)
        if ok and changed then pcall(emit, true); WC.push() end                   -- the padmap at once (`armed` for Home's bar)
        if e == "PLAYER_REGEN_ENABLED" and face.pending then pcall(faceSetup) end
        if e == "PLAYER_REGEN_ENABLED" and attackDB().pending then pcall(attackApply); pcall(emit, true); WC.push() end
        -- m25: the padmap at a fight's start / end (Cast's page hides in fights); sent only if not yet. m48: ALWAYS (not only while the
        -- room bar is wanted): its `combat` is what Home's D-pad picture follows
        if e == "PLAYER_REGEN_DISABLED" or e == "PLAYER_REGEN_ENABLED" then
            local okE, sent = pcall(emit, false)
            if okE and sent then WC.push() end
        end
        if e == "PLAYER_REGEN_ENABLED" then pcall(zlClean) end             -- m25: a login in a fight does it after
        if e == "PLAYER_REGEN_ENABLED" and CLEAN.autoPending() then pcall(CLEAN.auto, "fight end") end   -- m45: deferred by a fight
    elseif e == "SPELLS_CHANGED" and not trig then
    elseif e == "SPELL_UPDATE_COOLDOWN" then
        pcall(faceCooldowns)
        return                                                          -- (no padmap work: the map does not change with a cooldown)
    elseif e == "PLAYER_ENTERING_WORLD" then
        face.aa, face.ar = false, false
        fightNow.on = inCombat()                                          -- m48: a reload inside a fight starts in it
        -- a crash / reload mid-fight left keys of ours: put back out of combat; in a fight they stay ours until it ends
        if inCombat() then
            -- a reload IN a fight: overrides do not survive a reload and cannot be set under the lockdown: nothing bound (A / X / Y the
            -- game's own) until the fight ends, then the router binds
            face.fight = true
            face.swapped = true
            fightNow.sw = true                                                -- m49
            face.pending = true
            pcall(CLEAN.auto, "login")                                        -- m45: in a fight it only marks itself for the fight's end
        else
            face.fight, face.swapped = false, false
            fightNow.sw = false                                               -- m49
            pcall(faceRestore, "login")
            pcall(attackAdopt)                                                -- m36: once per character
            pcall(attackRefresh)
            pcall(faceSetup)
            pcall(zlClean)                                                    -- m25: once per character (ZL+ZR DS Bar commands)
            pcall(C_Timer.After, 3, function() if not inCombat() then local ok, did = pcall(zlClean); if ok and did then pcall(emit, true); WC.push() end end end)
            -- m16: does the game take key changes now? (a login or a reload only, not every loading screen)
            if a1 or a2 then
                -- m40: the m16 login probe is retired (it changed a key binding for a moment: SetBinding)
            -- m45: no clean-up list any more (no button): our own old macros go by themselves, a few seconds after the login
            pcall(C_Timer.After, 6, function() pcall(CLEAN.auto, "login") end)
            pcall(C_Timer.After, 8, function() if WC.svKeep then pcall(WC.svKeep.fix181); pcall(WC.svKeep.send, true) end end)   -- m51
                -- m18: the read-only key probe, once per PadMap version
                pcall(C_Timer.After, 7, function() if not inCombat() then pcall(keyProbe) end end)
            end
            -- start attack on cast at login (ON by default): the spells placed since are converted; a fight by then waits for its end
            if attackOn() and not PAUSED then                            -- m40: no conversion at login
                pcall(C_Timer.After, 3, function() pcall(attackApply); pcall(emit, true); WC.push() end)
            end
        end
    end
    pcall(routerMacrosSoon)                                               -- m26: a bar / macro changed (out of combat)
    soon()
end)
local WANT = { "PLAYER_ENTERING_WORLD", "ACTIONBAR_SLOT_CHANGED", "UPDATE_MACROS", "ACTIONBAR_PAGE_CHANGED", "SPELLS_CHANGED",
               "PLAYER_REGEN_DISABLED", "PLAYER_REGEN_ENABLED", "PLAYER_ENTER_COMBAT", "PLAYER_LEAVE_COMBAT",
               "START_AUTOREPEAT_SPELL", "STOP_AUTOREPEAT_SPELL", "SPELL_UPDATE_COOLDOWN" }
-- m53 (p-druid-lt-lb): a form / stance changes which bars the anchors carry (the stance bar is parented when it first shows): every
-- routed button is wired again, out of combat (frame refs are attributes), a moment after the change; a fight's change waits for its end
-- (no new main-chunk locals: Lua's 200; all on R)
function R.rewireAll()
    R.rewireQueued = false
    if inCombat() then return end
    for pos, b in pairs(face.buttons) do if R.wrapped[pos] then pcall(routerWire, b, pos, R.keys[pos]) end end
    for i = 1, 4 do if R.dbtn[i] then pcall(routerWire, R.dbtn[i], i, R.keys[i]) end end
    if R.bbtn then pcall(routerWire, R.bbtn, 7, R.keys[7]) end
end
R.fs = CreateFrame("Frame")
for _, e in ipairs({ "UPDATE_SHAPESHIFT_FORM", "UPDATE_SHAPESHIFT_FORMS", "PLAYER_REGEN_ENABLED", "UPDATE_BONUS_ACTIONBAR",
                     "PLAYER_ENTERING_WORLD" }) do pcall(function() R.fs:RegisterEvent(e) end) end
R.fs:SetScript("OnEvent", function()
    C_Timer.After(0.2, function() pcall(OV.formLearn) end)              -- m57: the form now's own bar (the game names it a moment later)
    pcall(soon)                                                         -- m55: the document's active form (Home's bars show its set)
    if routerMacrosSoon then pcall(routerMacrosSoon) end                -- m55: a learned form renumbers the forms (out of combat)
    if R.rewireQueued then return end
    R.rewireQueued = true
    C_Timer.After(0.3, R.rewireAll)
end)
-- m59 FISHING ON X: the main hand's item read out of combat (a Weapon, subclass Fishing Pole: classID 2 / subclassID 20) and the player's
-- Fishing spell (any rank they know); X's router button carries its name ("wc-fish", an attribute: in memory, nothing saved, no binding,
-- no macro, no bar write). An equipment change in a fight waits for its end (attributes cannot change in the lockdown)
OV.FISH_IDS = { 131474, 110410, 158743, 88868, 51294, 33095, 18248, 7732, 7731, 7620 }
function OV.fishNow()
    local id = T(function() return GetInventoryItemID("player", 16) end)
    if not id then return nil end
    local fn = (C_Item and C_Item.GetItemInfoInstant) or _G.GetItemInfoInstant
    if not fn then return nil end
    local ok, _, _, _, _, _, cls, sub = pcall(fn, id)
    if not ok or cls ~= 2 or sub ~= 20 then return nil end
    for _, sid in ipairs(OV.FISH_IDS) do
        local known = T(function() return (IsPlayerSpell and IsPlayerSpell(sid)) or (IsSpellKnown and IsSpellKnown(sid)) end)
        if known then
            local n = T(function() return (C_Spell and C_Spell.GetSpellName and C_Spell.GetSpellName(sid)) or (GetSpellInfo(sid)) end)
            local i = T(function() return (C_Spell and C_Spell.GetSpellTexture and C_Spell.GetSpellTexture(sid)) or GetSpellTexture(sid) end)
            if type(n) == "string" and n ~= "" then return { id = sid, n = n, i = type(i) == "number" and i or 0 } end
        end
    end
    return nil                                                          -- a pole, but no Fishing known: X as it is
end
function OV.fishApply()
    if inCombat() then OV.fishPending = true; return end
    OV.fishPending = nil
    local f = OV.fishNow()
    local was = OV.fish and OV.fish.n
    OV.fish = f
    local b = face.buttons[5]
    if b then pcall(b.SetAttribute, b, "wc-fish", f and f.n or nil) end
    if (f and f.n) ~= was then
        pcall(function() WC.send(WC.encode({ type = "cap", padmap = PV, fish = f and f.n or "off" })) end)
        pcall(emit, true); WC.push()
    end
end
OV.fishF = CreateFrame("Frame")
for _, e in ipairs({ "PLAYER_EQUIPMENT_CHANGED", "PLAYER_REGEN_ENABLED", "PLAYER_ENTERING_WORLD", "SPELLS_CHANGED" }) do
    pcall(function() OV.fishF:RegisterEvent(e) end)
end
OV.fishF:SetScript("OnEvent", function(_, e)
    if e == "PLAYER_REGEN_ENABLED" and not OV.fishPending then return end
    C_Timer.After(0.2, function() pcall(OV.fishApply) end)
end)
-- m61 SMART Y (the user: "I want to add a new Feature that basically turns Y into a smart button- If I am out of combat and nothing is
-- currently targeted that I can interact with Y, if I am missing Health then it should suggest food from my bag if its available, if I am
-- missing mana it should suggest water from my bag if its available, pressing the button does the action like a quick select. (one action
-- per button press)"; picks: food first, below 80%, cheapest first, no prompt). Out of combat the bags, health and mana are read and the
-- pick is put on Y's router button as ONE attribute ("wc-smarty" = "item:<id>": in memory, nothing saved, no binding, no macro, no bar
-- write); the secure pre-click uses it only on a bare Y out of combat with no target and no soft-interact target. Read only otherwise.
-- SMARTY-PICK-BEGIN (the pure choice, tested off-device by tools/test_smarty.py)
-- s = { hp, hpMax, mp, mpMax, level, eating, drinking, items = { { id, kind = "food" | "drink" | "both", sell, minLv, n } } }
OV.smartPick = function(s)
    local function low(v, m) return type(v) == "number" and type(m) == "number" and m > 0 and v / m < 0.8 end
    local needHp = low(s.hp, s.hpMax)
    local needMp = low(s.mp, s.mpMax)                                   -- no mana pool (mpMax 0): never
    local function best(want)
        local pick
        for _, it in ipairs(s.items or {}) do
            local fits = (it.kind == want or it.kind == "both") and (it.n or 0) > 0 and (it.minLv or 0) <= (s.level or 0)
            if fits and (not pick or (it.sell or 0) < (pick.sell or 0)
                         or ((it.sell or 0) == (pick.sell or 0) and (it.id or 0) < (pick.id or 0))) then
                pick = it
            end
        end
        return pick
    end
    -- food first; once eating, the next press is the drink (one action per press)
    if needHp and not s.eating then local f = best("food"); if f then return f, "food" end end
    if needMp and not s.drinking then local d = best("drink"); if d then return d, "drink" end end
    return nil
end
-- SMARTY-PICK-END
OV.SMART_FOOD, OV.SMART_DRINK = 433, 430                                  -- the game's own "Food" / "Drink" auras: their names
function OV.smartKind(id)
    local sn = T(function() return (C_Item and C_Item.GetItemSpell and C_Item.GetItemSpell(id)) or GetItemSpell(id) end)
    if type(sn) ~= "string" then return nil end
    local function nm(sid) return T(function() return (C_Spell and C_Spell.GetSpellName and C_Spell.GetSpellName(sid)) or (GetSpellInfo(sid)) end) end
    if sn == nm(OV.SMART_FOOD) then return "food" end
    if sn == nm(OV.SMART_DRINK) then return "drink" end
    -- "food and drink" (one item restoring both): its tooltip says it restores health AND mana
    local lines = T(function() return C_TooltipInfo and C_TooltipInfo.GetItemByID and C_TooltipInfo.GetItemByID(id) end)
    if type(lines) == "table" and type(lines.lines) == "table" then
        local h, m = false, false
        for _, l in ipairs(lines.lines) do
            local t = type(l) == "table" and type(l.leftText) == "string" and l.leftText:lower() or ""
            if t:find("restores", 1, true) then h = h or t:find("health", 1, true) ~= nil; m = m or t:find("mana", 1, true) ~= nil end
        end
        if h and m then return "both" end
    end
    return nil                                                           -- buff-only and everything else: never
end
function OV.smartState()
    local s = { hp = TN(function() return UnitHealth("player") end), hpMax = TN(function() return UnitHealthMax("player") end),
                mp = TN(function() return UnitPower("player", 0) end), mpMax = TN(function() return UnitPowerMax("player", 0) end),
                level = TN(function() return UnitLevel("player") end) or 0, items = {} }
    local count, kinds = {}, {}
    local C = C_Container
    for bag = 0, 5 do
        local n = T(function() return C and C.GetContainerNumSlots(bag) or GetContainerNumSlots(bag) end) or 0
        for slot = 1, n do
            local info = T(function() return C and C.GetContainerItemInfo(bag, slot) end)
            local id = type(info) == "table" and info.itemID or nil
            if type(id) == "number" then count[id] = (count[id] or 0) + (info.stackCount or 1) end
        end
    end
    for id, n in pairs(count) do
        local kind = OV.smartKind(id)
        if kind then
            kinds[#kinds + 1] = kind
            local fn = (C_Item and C_Item.GetItemInfo) or GetItemInfo
            local ok, name, _, _, _, minLv, _, _, _, _, icon, sell = pcall(fn, id)
            if ok and name then
                s.items[#s.items + 1] = { id = id, kind = kind, sell = sell or 0, minLv = minLv or 0, n = n, name = name, icon = icon or 0 }
            end
        end
    end
    -- eating / drinking now: the player wears the game's Food / Drink aura (by its name)
    local fname = T(function() return (C_Spell and C_Spell.GetSpellName and C_Spell.GetSpellName(OV.SMART_FOOD)) or (GetSpellInfo(OV.SMART_FOOD)) end)
    local dname = T(function() return (C_Spell and C_Spell.GetSpellName and C_Spell.GetSpellName(OV.SMART_DRINK)) or (GetSpellInfo(OV.SMART_DRINK)) end)
    for i = 1, 40 do
        local a = T(function() return C_UnitAuras and C_UnitAuras.GetAuraDataByIndex("player", i, "HELPFUL") end)
        local an = type(a) == "table" and a.name or T(function() return (UnitBuff("player", i)) end)
        if type(an) ~= "string" then break end
        if an == fname then s.eating = true end
        if an == dname then s.drinking = true end
    end
    return s
end
function OV.smartApply()
    if inCombat() then OV.smartPending = true; return end
    OV.smartPending = nil
    local pick = OV.smartPick(OV.smartState())
    local val = pick and ("item:" .. pick.id) or nil
    local typ = pick and "item" or nil
    OV.smarty = pick and { id = pick.id, n = pick.name, i = pick.icon, k = pick.kind } or nil
    -- m62: food and water first; then the player's buff rows (their OR cells kept current either way)
    local okb, bv, bt, be = pcall(OV.sbStep)
    if not pick and okb and bv then
        val, typ = bv, bt
        OV.smarty = { id = be.id, n = be.n, i = be.i, k = bt == "or" and "or" or "buff" }
    end
    local b = face.buttons[6]
    if b then pcall(b.SetAttribute, b, "wc-smarty", val); pcall(b.SetAttribute, b, "wc-smarty-t", typ) end
    OV.smartTyp = typ
    local hasTarget = T(function() return UnitExists("target") end) == true
    local shown = (OV.smarty and not hasTarget) and (OV.smarty.k .. OV.smarty.id) or nil
    OV.smartyShown = shown and OV.smarty or nil
    if val ~= OV.smartVal or shown ~= OV.smartShownId then
        if val ~= OV.smartVal then
            pcall(function() WC.send(WC.encode({ type = "cap", padmap = PV, smarty = OV.smarty and (OV.smarty.k .. " " .. OV.smarty.id) or "off" })) end)
        end
        OV.smartVal, OV.smartShownId = val, shown
        pcall(emit, true); WC.push()
    end
end
OV.smartF = CreateFrame("Frame")
for _, e in ipairs({ "BAG_UPDATE_DELAYED", "UNIT_HEALTH", "UNIT_MAXHEALTH", "UNIT_POWER_UPDATE", "UNIT_MAXPOWER", "UNIT_AURA",
                     "PLAYER_REGEN_ENABLED", "PLAYER_REGEN_DISABLED", "PLAYER_TARGET_CHANGED", "PLAYER_LEVEL_UP",
                     "PLAYER_ENTERING_WORLD", "GET_ITEM_INFO_RECEIVED" }) do
    pcall(function() OV.smartF:RegisterEvent(e) end)
end
OV.smartF:SetScript("OnEvent", function(_, e, unit)
    if (e == "UNIT_HEALTH" or e == "UNIT_MAXHEALTH" or e == "UNIT_POWER_UPDATE" or e == "UNIT_MAXPOWER" or e == "UNIT_AURA")
       and unit ~= "player" then return end
    if e == "PLAYER_REGEN_DISABLED" then return end                     -- the snippet's [combat] keeps Y as it is in a fight
    if OV.smartSoon then return end                                     -- one pass per 0.3 s, however many events
    OV.smartSoon = true
    C_Timer.After(0.3, function() OV.smartSoon = nil; pcall(OV.smartApply) end)
end)
-- m62 SMART BUFFS (the user: "this setting will need its own section in the settings so taht I can also layer in smart buffs. I ant to
-- basically be able to set a buff priority following water and food ... logic rows, with the bottom row being an Add button ... on a
-- single row I need to be able to add an "or" which would basically open a radial menu for those selected buffs and as long as one is
-- up out of combat that line of logic is satisfied"; picks: an OR row with none up opens a radial to choose, food / water fixed at the
-- top, yourself only). Per character (name-realm): WowCompanionSpellsDB.smartBuffs[who].rows = { { ids = { spell id, ... } }, ... },
-- in priority order. Y (the same gate as Smart Y's food: out of combat, nothing targeted, nothing to interact with) acts on the FIRST
-- unsatisfied row after food and water: one buff -> cast it on yourself; an OR row with none up -> /click WowCompanionPadSmartyOr (Hub's
-- barcmd "smartor": the app opens its wheel of the row's buffs; a pick taps that buff's cast cell on the "gamemenu" row). A buff is up
-- when an aura of its name is on the player; a weapon imbue when the main hand has a temporary enchant (GetWeaponEnchantInfo: the game
-- does not say which). The list offered: the character's own learned self-buffs from SB_IDS, by name (any rank), cast by name (the
-- best rank known). THE PAD IS FULL (54 of 54 for a character with a reagent bag), so Smart buffs rides Spells' own secure row
-- "gamemenu" (Radial.lua: cols 0-5 the Game Menu's, as before): cols 8-23 select a row (fresh 2 s), 24 remove it, 25 / 26 move it
-- up / down, 27 add a row, 28 add an "or" to the selected row, 29 take a buff out of it (27-29: the spell follows as Book's two-tap
-- spell number, WC.smartYWants / WC.smartYSpell); cols 30-39 are the OR row's cast cells (type spell, unit player, set out of combat). Nothing saved but our own table; no macro, no binding, no bar write, no CVar.
OV.SB_IDS = {
    -- Paladin: auras, blessings (on yourself), seals, Righteous Fury
    465, 7294, 19746, 19876, 19888, 19891, 20218, 19740, 19742, 20217, 1038, 19977, 20911, 21084, 21082, 20165, 20166, 20164, 20375,
    25780,
    -- Shaman: Lightning Shield; the weapon imbues
    324, 8017, 8024, 8033, 8232,
    -- Mage: Arcane Intellect, the armors, Dampen / Amplify Magic
    1459, 168, 7302, 6117, 604, 1008,
    -- Priest: Fortitude, Inner Fire, Divine Spirit, Shadow Protection, Fear Ward, Shadowform
    1243, 588, 14752, 976, 6346, 15473,
    -- Druid: Mark of the Wild, Thorns, Omen of Clarity
    1126, 467, 16864,
    -- Warlock: Demon Skin / Armor
    687, 706,
    -- Hunter: the aspects, Trueshot Aura
    13165, 13163, 5118, 13159, 20043, 13161, 19506,
    -- Warrior: Battle Shout
    6673,
}
OV.SB_IMBUE = { [8017] = true, [8024] = true, [8033] = true, [8232] = true }
OV.SB_SEL0, OV.SB_FIRST_CAST, OV.SB_OPS = 8, 30, { remove = 24, up = 25, down = 26, add = 27, addor = 28, out = 29 }
function OV.sbName(id)
    return T(function() return (C_Spell and C_Spell.GetSpellName and C_Spell.GetSpellName(id)) or (GetSpellInfo(id)) end)
end
function OV.sbdb()
    if type(_G.WowCompanionSpellsDB) ~= "table" then _G.WowCompanionSpellsDB = {} end
    local db = _G.WowCompanionSpellsDB
    if type(db.smartBuffs) ~= "table" then db.smartBuffs = {} end
    local k = whoKey()
    if not k then return { rows = {} } end
    if type(db.smartBuffs[k]) ~= "table" then db.smartBuffs[k] = { rows = {} } end
    if type(db.smartBuffs[k].rows) ~= "table" then db.smartBuffs[k].rows = {} end
    return db.smartBuffs[k]
end
-- the self-buffs this character knows (the book's spells, by name): { id, n, i, k = "aura" | "imbue" }, SB_IDS' order; byName for checks
function OV.sbKnown()
    local names = {}
    local ok, list = pcall(function() return type(WC.spellsBindableList) == "function" and WC.spellsBindableList() or {} end)
    for _, e in ipairs(ok and list or {}) do local n = e.id and OV.sbName(e.id); if n then names[n] = true end end
    local out, byName = {}, {}
    for _, id in ipairs(OV.SB_IDS) do
        local n = OV.sbName(id)
        if n and names[n] and not byName[n] then
            local i = T(function() return (C_Spell and C_Spell.GetSpellTexture and C_Spell.GetSpellTexture(id)) or GetSpellTexture(id) end)
            local e = { id = id, n = n, i = type(i) == "number" and i or 0, k = OV.SB_IMBUE[id] and "imbue" or "aura" }
            out[#out + 1] = e; byName[n] = e
        end
    end
    return out, byName
end
-- the player's helpful aura names now
function OV.sbAuras()
    local up = {}
    for i = 1, 40 do
        local a = T(function() return C_UnitAuras and C_UnitAuras.GetAuraDataByIndex("player", i, "HELPFUL") end)
        local an = type(a) == "table" and a.name or T(function() return (UnitBuff("player", i)) end)
        if type(an) ~= "string" then break end
        up[an] = true
    end
    return up
end
-- SB-EVAL-BEGIN (pure: tools/test_smarty.py) rows = { { ids } }, byName = known buffs by name, nameOf(id), auras = names up, mh = main hand
-- enchanted; -> the first unsatisfied row (its index and its known members), or nil when every row is satisfied
OV.sbFirst = function(rows, byName, nameOf, auras, mh)
    for r, row in ipairs(rows or {}) do
        local members, up = {}, false
        for _, id in ipairs(row.ids or {}) do
            local n = nameOf(id)
            local e = n and byName[n]
            if e then
                members[#members + 1] = e
                if (e.k == "imbue" and mh) or (e.k ~= "imbue" and auras[n]) then up = true end
            end
        end
        if #members > 0 and not up then return r, members end
    end
    return nil
end
-- SB-EVAL-END
-- the OR row's cast cells (col -> spell name), as set out of combat; the doc for the app's wheel
OV.sbCells, OV.sbOrSig = {}, nil
function OV.sbCellInit(btn, col)
    local n = col >= OV.SB_FIRST_CAST and OV.sbCells[col] or nil
    btn:SetAttribute("type", n and "spell" or nil)
    btn:SetAttribute("spell", n)
    btn:SetAttribute("unit", n and "player" or nil)
end
function OV.sbOnCell(col)
    if col >= OV.SB_FIRST_CAST then
        WC.send(WC.encode({ type = "smartoruse", v = PV, col = col, n = OV.sbCells[col] })); WC.push()
        C_Timer.After(0.5, function() pcall(OV.smartApply) end)
        return
    end
    if col >= OV.SB_SEL0 then OV.sbOp(col) end
end
WC.smartyCellInit, WC.smartyCell = OV.sbCellInit, OV.sbOnCell     -- Radial.lua's "gamemenu" row hands its cols 6-39 here
function OV.sbBuild()
    if inCombat() or type(WC.gameMenuRowBuild) ~= "function" then return false end
    local ok, row = pcall(WC.gameMenuRowBuild)
    OV.sbRow = ok and type(row) == "number" and row or nil
    return OV.sbRow ~= nil
end
function OV.sbAck(op, ok, why) WC.send(WC.encode({ type = "smartyack", v = PV, op = op, ok = ok, why = why })); WC.push() end
-- an op column from the app (no game action): a row select, or an op on the selected row, or the mode for the spell that follows
function OV.sbOp(col)
    local rows, now = OV.sbdb().rows, GetTime()
    if col < OV.SB_OPS.remove then OV.sbSel = { row = col - OV.SB_SEL0 + 1, at = now } return end
    local sel = OV.sbSel and now - OV.sbSel.at <= 2 and OV.sbSel.row or nil
    OV.sbSel = nil
    if col == OV.SB_OPS.add then OV.sbPick = { op = "add", at = now } return end
    if not sel or not rows[sel] then OV.sbAck("row", false, "no row picked") return end
    if col == OV.SB_OPS.remove then table.remove(rows, sel)
    elseif col == OV.SB_OPS.up and sel > 1 then rows[sel], rows[sel - 1] = rows[sel - 1], rows[sel]
    elseif col == OV.SB_OPS.down and sel < #rows then rows[sel], rows[sel + 1] = rows[sel + 1], rows[sel]
    elseif col == OV.SB_OPS.addor or col == OV.SB_OPS.out then
        OV.sbPick = { op = col == OV.SB_OPS.addor and "or" or "out", row = sel, at = now } return
    else OV.sbAck("row", false, "nothing to do") return end
    OV.sbAck(col == OV.SB_OPS.remove and "remove" or "move", true)
    pcall(OV.smartApply); pcall(emit, true); WC.push()
end
WC.smartYWants = function() return OV.sbPick ~= nil and GetTime() - OV.sbPick.at <= 2 end
WC.smartYSpell = function(id)
    local p = OV.sbPick
    OV.sbPick = nil
    if not p then return end
    local rows = OV.sbdb().rows
    local _, byName = OV.sbKnown()
    local n = OV.sbName(id)
    local e = n and byName[n]
    if not e then OV.sbAck(p.op, false, "not a self-buff this character knows") return end
    if p.op == "add" then
        if #rows >= 16 then OV.sbAck("add", false, "16 rows at most") return end
        rows[#rows + 1] = { ids = { e.id } }
    else
        local row = rows[p.row]
        if not row then OV.sbAck(p.op, false, "the row is gone") return end
        local at
        for k, x in ipairs(row.ids) do if OV.sbName(x) == n then at = k end end
        if p.op == "or" then
            if at then OV.sbAck("or", false, "already on the row") return end
            if #row.ids >= 10 then OV.sbAck("or", false, "10 on a row at most") return end
            row.ids[#row.ids + 1] = e.id
        else
            if not at then OV.sbAck("out", false, "not on the row") return end
            table.remove(row.ids, at)
            if #row.ids == 0 then table.remove(rows, p.row) end
        end
    end
    OV.sbAck(p.op, true)
    pcall(OV.smartApply); pcall(emit, true); WC.push()
end
-- the buff step of Smart Y (food / water come first): -> value, type ("spell" / "or"), the shown entry; sets the OR cells (out of combat)
function OV.sbStep()
    local known, byName = OV.sbKnown()
    OV.sbList = known
    local mh = T(function() return (GetWeaponEnchantInfo()) end) == true
    local r, members = OV.sbFirst(OV.sbdb().rows, byName, OV.sbName, OV.sbAuras(), mh)
    OV.sbAt = r
    local cells, items, sig = {}, {}, {}
    if r and #members > 1 then
        for k, e in ipairs(members) do
            local col = OV.SB_FIRST_CAST + k - 1
            if col <= 39 then
                cells[col] = e.n
                items[#items + 1] = { id = e.id, n = e.n, i = e.i, col = col, s = k, c = 1 }
                sig[#sig + 1] = col .. "=" .. e.n
            end
        end
    end
    local s = table.concat(sig, ",")
    if s ~= OV.sbOrSig then
        OV.sbCells = cells
        if OV.sbBuild() then OV.sbOrSig = s end
        WC.send(WC.encode({ type = "smartor", v = PV, r = { row = "gamemenu", pad = OV.sbRow ~= nil, items = items } }))
    end
    if not r then return nil end
    if #members == 1 then return members[1].n, "spell", members[1] end
    return "or", "or", members[1]
end
OV.sbF = CreateFrame("Frame")
for _, e in ipairs({ "UNIT_INVENTORY_CHANGED", "SPELLS_CHANGED", "PLAYER_LOGIN" }) do pcall(function() OV.sbF:RegisterEvent(e) end) end
OV.sbF:SetScript("OnEvent", function(_, e, unit)
    if e == "UNIT_INVENTORY_CHANGED" and unit ~= "player" then return end
    if e == "PLAYER_LOGIN" then C_Timer.After(1, function() pcall(OV.sbBuild) end) end
    C_Timer.After(0.3, function() pcall(OV.smartApply) end)
end)
function OV.sbDoc()
    local list = OV.sbList or OV.sbKnown()
    local rows = {}
    for _, row in ipairs(OV.sbdb().rows) do rows[#rows + 1] = { ids = row.ids } end
    OV.sbDocNow = { rows = rows, list = list, at = OV.sbAt, row = "gamemenu", pad = OV.sbRow ~= nil,
                    sel0 = OV.SB_SEL0, ops = OV.SB_OPS }
end
-- m33: the bags and the Mount Journal change the Extra tab's lists: a doc soon (no router work for these)
local fx = CreateFrame("Frame")
for _, e in ipairs({ "BAG_UPDATE_DELAYED", "NEW_MOUNT_ADDED", "MOUNT_JOURNAL_USABILITY_CHANGED" }) do pcall(function() fx:RegisterEvent(e) end) end
fx:SetScript("OnEvent", function() soon() end)
local missing = {}
for _, e in ipairs(WANT) do
    local ok, registered = pcall(function() return f:RegisterEvent(e) end)
    if not ok or registered == false then missing[#missing + 1] = e end
end
if #missing > 0 then pcall(function() WC.send(WC.encode({ type = "cap", padmap = PV, missingEvents = missing })) end) end

-- the map on login and on a manual snapshot (not on every heartbeat: it changes only when a button does)
if type(WC.snapshot) == "function" then
    hooksecurefunc(WC, "snapshot", function(reason) if reason == "login" or reason == "manual" then emit(true) end end)
end


-- m51 THE SETTINGS' OWN SAVE FILE, KEPT BY THE APP (the user's pick after test22, "Write the add-on's save file"): this client writes
-- SavedVariables only on a clean exit, and Android kills it, so nothing the player set ever reached the disk. Every 5 s (and at once
-- at login) each of OUR saved tables is written out as Lua source - sorted keys, plain values only - and sent as `svkeep` when it
-- changed; the app keeps it in Documents/DS Forever and writes OUR OWN WowCompanion*.lua before the next game start, only when the
-- game has nothing newer. Left out: what is not a setting (crash-recovery key state, probes, learned cooldowns, chat lines).
do
    local SKIP = { WowCompanionSpellsDB = { keyProbe = true, faceSwap = true, faceCd = true, combatFace = true },
                   WowCompanionDB = { chatTop = true } }
    local VARS = { "WowCompanionSpellsDB", "WowCompanionDB" }
    local last = {}
    local function keyLess(a, b)
        local ta, tb = type(a), type(b)
        if ta ~= tb then return ta < tb end
        if ta == "number" or ta == "string" then return a < b end
        return tostring(a) < tostring(b)
    end
    local function ser(v, depth, skip)
        local t = type(v)
        if t == "number" then
            if v ~= v or v == math.huge or v == -math.huge then return nil end
            if v == math.floor(v) and math.abs(v) < 2 ^ 53 then return string.format("%d", v) end
            return string.format("%.17g", v)
        elseif t == "string" then return string.format("%q", v)
        elseif t == "boolean" then return tostring(v)
        elseif t ~= "table" or depth > 12 then return nil end
        local keys = {}
        for k in pairs(v) do
            local kt = type(k)
            if (kt == "string" or kt == "number") and not (skip and skip[k]) then keys[#keys + 1] = k end
        end
        table.sort(keys, keyLess)
        local out, n = {}, 0
        for _, k in ipairs(keys) do
            local sv, c = ser(v[k], depth + 1)
            local sk = ser(k, depth + 1)
            if sv and sk then out[#out + 1] = "[" .. sk .. "]=" .. sv; n = n + (c or 1) end
        end
        return "{" .. table.concat(out, ",") .. "}", n
    end
    local function send(force)
        for _, var in ipairs(VARS) do
            local db = _G[var]
            local src, n = ser(type(db) == "table" and db or {}, 0, SKIP[var])
            if src and (force or src ~= last[var]) then
                last[var] = src
                WC.send(WC.encode({ type = "svkeep", v = PV, var = var, n = n or 0, lua = src }))
            end
        end
    end
    -- m51: test22's restore put Lightning Shield (324) as a PLAIN spell on the game's bar slot 181, where the player's own Earthbind Totem
    -- (2484) was (test22_General.log 21:18:51-55). The user's pick: undo it automatically, once, out of combat, ONLY while 181 still holds
    -- exactly that spell for that character; anything else is left alone. Logged (`padfix`).
    local FIX = { who = "Yes-Classic Beta PvE", slot = 181, bad = 324, good = 2484 }
    local function fix181()
        if inCombat() or whoKey() ~= FIX.who then return end
        if type(_G.WowCompanionSpellsDB) ~= "table" then _G.WowCompanionSpellsDB = {} end
        local db = _G.WowCompanionSpellsDB
        if db.fix181 then return end
        local okA, kind, id = pcall(GetActionInfo, FIX.slot)
        local what = "left alone (" .. tostring(okA and kind) .. " " .. tostring(okA and id) .. ")"
        if okA and kind == "spell" and id == FIX.bad then
            local bslot = type(WC.spellsBookSlotOf) == "function" and WC.spellsBookSlotOf(FIX.good) or nil
            CALL(function() return ClearCursor() end)
            if bslot then
                CALL(function() return C_SpellBook.PickupSpellBookItem(bslot, BANK_PLAYER) end)
                CALL(function() return PlaceAction(FIX.slot) end)
                what = "Earthbind Totem put back"
            else
                CALL(function() return PickupAction(FIX.slot) end)
                what = "Lightning Shield taken off (Earthbind Totem not in the book)"
            end
            CALL(function() return ClearCursor() end)
        end
        db.fix181 = true
        WC.send(WC.encode({ type = "padfix", v = PV, slot = FIX.slot, what = what }))
        if type(WC.flush) == "function" then pcall(WC.flush) else WC.push() end
    end
    WC.svKeep = { send = send, ser = ser, fix181 = fix181, FIX = FIX }
    if type(C_Timer) == "table" and type(C_Timer.NewTicker) == "function" then
        pcall(C_Timer.NewTicker, 5, function() pcall(send, false) end)
    end
end
