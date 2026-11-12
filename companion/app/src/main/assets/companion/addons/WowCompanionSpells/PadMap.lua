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

local PV = "m132"               -- bump on every change; m132: PARTY MODE USABLE IN FIGHTS (the user, 2026-11-11, a healer: "Party mode needs to be usable in combat for healers. Locking it is retarded"): a fight's start with Party mode allowed never steps the router aside for a game window (a loot roll / popup at the pull: every key and Select carried), binds Select solo and with an invite waiting too (PM.selWant), and keeps the player's own m34 chords off the party layers while grouped or on (PM.fightKeys); nothing in a fight turns Party mode off (the fight-taker note and its PLAYER_REGEN_ENABLED switch-off gone); out of combat a prompt (GroupLootFrame1, StaticPopup1-4, ReadyCheckFrame: PM.PROMPTS) only suspends it; in a fight Y never opens the player menu: the In combat action, else Smart Y's party buff armed before the fight (wc-pb-<unit>), else Target; partycfg's Smart Y picks in fights too; m131: PARTY MODE'S ADAPTIVE ROWS AND THE PET (the user, 2026-11-11: "when one two or three full sized, when 4, bottom 2 consolidate, when 5 the next 2 consolidate", "WHEN NO PET MAKE THE PLAYER FULL SIZED", "MAKE THE PET A BIT SMALLER"): the pick runs over you, your pet (only while one plainly exists; [@pet,exists] in the secure code) and party1..4 (PM.units, the pick index, the roster's count and its new pet driver, every snippet's unit walk); the D-pad moves the pick GEOMETRICALLY over min(n, 3) rows whose bottom max(0, n - 3) rows hold two cells (Up / Down between rows, Left / Right inside a shared row, no wrap; OV.partyNav = the PARTY-NAV snippet body = the app's PartyModel.nav); the pet's own pick cell (party row col 5), casts / Target / Touch on unit "pet"; the roster's driver keeps the picked unit when it is still there (a pet coming out no longer moves the pick to another person), else clamps; m130: A PLATE'S TAP DECIDES ONCE A PRESS, ON THE CELL ITSELF (review, 2026-11-11): the PARTYPICK snippet is wrapped on the party row's own cells 0-4 (no WowCompanionPartyPick buttons behind a "click" relay, whose own Target / spell could miss its edge), the first edge of a press decides and the later ones re-use it (wc-pdec), one partybox a press (wc-pseq); m129: PARTY MODE, ONE PICK AND ONE COLUMN (party_design.md and the user's decisions 2026-11-11): wc-box is the only pick and every writer sets wc-box + wc-u together (PARTYFLIP on clamps it and sets wc-u; a state driver re-clamps it after a roster change, in fights too; the bottom's plates pick through the hidden WowCompanionPartyPick1..5 buttons, party cols 0-4); the D-pad's Up / Down move the pick in a straight line, Left / Right do nothing (the user: "this will need to support raids at some point" - kept for raid navigation, never assignable); range from ONE place (UnitInRange, checked plainly true: WC.padMap.party.rng, Hub reads it too); NO HEALTH OR MANA FOR THE BOTTOM: the drawn-fill read (m86), the estimate and the hit / cast counts (m79 / m96 / m98), the party UNIT_HEALTH / UNIT_COMBAT registrations and the combat log (m92 / m95-97 / m112) retired - only the give-back of the Advanced Combat Logging we turned on stays (aclOurs / aclReset1); m128: THE MODEL'S EATING / DRINKING RATE FOLLOWS WHAT Y USES (the user's 2026-11-11 order rule, review): OV.smartItem came from the old fixed food-then-drink pick, so with Water above Food Y drank while the drink aura took the FOOD item's (absent) mana rate and an early-stopped drink gave no mana; now each kind keeps the item Y last armed for it (OV.smartEat), and the why line names the step the walk chose; m127: SMART Y v2 (the user, 2026-11-11): TRACKING NEVER ON Y ("I dont think that I want to have the tracking abilities to be selectable here": OV.isTracking, in Manners' pick and press, the Add list and the add, Party mode's chooser); MANNERS A ROW OF ITS OWN that only buffs OTHER players (never you, a bare /use or tracking; at the pick and at the press); ONE ORDERED LIST ("it shouldnt hardcode food and water as first and second, let people move spell priorities above them": typed rows food / drink / manners / { ids }, walked top to bottom by OV.smartWalk; the v2 saved-variable migration in sbdb); m126: THE HIT RULE LOWERS THE MODEL, NOT A 90 S FLOOR (the user, 2026-11-11: "I am consistently getting a food recommendation long after sitting at full hp for a long long while"; the log: "hp 50% via hit rule ... picked food" for 90 s after every fight with a hit): a fight's end with hits takes about 6 % a hit (at most half) off the model's health once; the model's own regen brings it back, and food stops at the 80 % line (plain facts only: the hit count, the class / Spirit regen); m125: A COOLDOWN THAT HAS RUN OUT IS GONE (the user, 2026-11-10: "about 50% of the time when I press a skill and I know it should show a gcd its still not showing a gcd"): a held GCD / cooldown was dropped only by a SPELL_UPDATE_COOLDOWN saying "not active", which the game often skips at the GCD's end; the stale entry then matched the next GCD (same kind, and in a fight no plain time to compare) and the new one was never sent. An entry past its end now counts as none, so every press starts a fresh one; m124: THE IN-COMBAT SPELLS FOLLOW THE RANK THAT CASTS (the user, 2026-11-10: Serpent Sting "its casting but not triggering a gcd or looking like it cast visually and also it doesn't show range properly which seems to be an issue on my shaman as well"; the Thor's log: the entry kept Rank 1's id 1978 while "/cast Serpent Sting" fires Rank 2, 13549): at login and on every SPELLS_CHANGED each entry's id / icon is set to the spell its NAME casts, so the cooldown, cast and range pictures read the rank that fires; m123: Y opens the target radial in a fight too (the user, 2026-11-10: "pressing y in combat is opening the context menu on the top screen. this should open on the bottom screen and hide on top"): the GROW no longer sends a bare Y to the game's Inspect menu; a fight spell / Smart Y on Y still wins, the Y glyph keeps the GROW rule; m122: the "soft" document retired (the LB picture shows no line over you: the user, "Scrap the soft target bit" ... "remove the L target"); m121: the LB picture's top line - what LB ALONE is bound to (lbmap tap / tapn, read from the game) and what you soft-target now (the "soft" document: soft enemy / friend / interact names, on change); m120: the Y in the layout's own glyph set (Nintendo = the white Gamepad_Rev_Y; the user: "why is the Y yellow when im using the nintendo glyphs?"); the game's X over an NPC trumps the Y also when the game will not say it is the same unit (secret reads count as the same); m119: the inspect glyph is the game's Y on every layout (the user: "the inspect glyph is Y not X"); m118: the target bar wears Hub's OWN prompt right of the bar (the user: "it needs to be to the right with our custom Icon"): Spells only says the letter (WC.tgPrompt), no glyph of ours on the bar; m117: the Y glyph over the nameplate again AND a smaller one beside the target bar's name (the user: "the glyphs still need to appear over the name plates"); the GROW rule alone for Hub's nameplate hiding (WC.tgGrown; "the hide name plate needs to have the same rules as the hide glyph"); m116 is the hunt build's; m115: the Y glyph beside the name on Hub's target bar while the bar shows (the user: "it should have the button glyph"); m112: the combat log (m92 / m95-97) ONLY while Party mode is allowed (WC.partyOk; the user, 2026-11-09: "Only while Party mode is on"); our Advanced Combat Logging given back when it is not (aclOurs; once for 1.0.17 / 1.0.18, aclReset1); m111 is the hunt build's; m110: Party mode's picked unit and the LB map for Hub's own party frames (WC.padMap.party.box / .lb, read only); m108: the several spell radials (m105) taken out of 1.0.18 (bisect test21: with them the loot window's close ran SetPreferredGamepadInteractTarget tainted by this add-on); one radial as in 1.0.17; m107: Manners' PreClick / PostClick are never run from our code (its code ran as ours: the taint log's buff frame / gamepad binding stack blamed on WowCompanionSpells); its armed prompt is only read; m106: the Y glyph over the target is OUR texture with the game's atlas (no Blizzard InputIconTextureFrameTemplate: its code ran tainted and tainted the gamepad UI -> SetPreferredGamepadInteractTarget forbidden; the taint log, 2026-11-08); m105: a command per spell radial (DSSRADIAL2..6, shown while that radial exists; WC.padMapDropCommand); m104: the LB party-target map ("lbmap": which LB + button targets you / party1..4, read from the game's own bindings) for the app's LB view; m103: the game's X (interact) glyph over an NPC target trumps our Y glyph (the user, 2026-11-07); Trail's friendly nameplates now follow a friendly target; m102: the target radial and its Y glyph follow the controller's GROW (the user: "I want it to go away when I /startattack, same rules as the button grow animation"): gone while a fight or an attack is on and not given back by B, back after B; m101: THE TARGET RADIAL (the user, 2026-11-07): out of a fight, a bare Y with a target opens the app's radial (wc-kind "tradial", no game action; Quests q51 reads the target's menu) and the game's own Y glyph shows over the target (its nameplate, else beside the target frame); m100: Party mode EXPERIMENTAL, off by default (Hub hub25 WC.partyOk): Select stays the game's key and Party mode ends (out of combat) while it is not allowed; m99: B in a fight - the first press gives the bars back AND stops the attack (the target kept), the next one clears the target; Smart Y's fight buff only when it was missing at the fight's start, once a fight; m98: each member's live CAST count (cs, UNIT_SPELLCAST_SUCCEEDED) for the app's mana estimate (the user: "The mana bars always show 100%"); m97: the combat log's stop and start a moment APART (in the same instant the game wrote nothing - test12 measured 2.5 min late again), a "clog" tick report every 12 stops; m96: each member's live HIT COUNTS (cw wounds, cc crits, ch heals: UNIT_COMBAT, amounts hidden) for the app's calibrated estimate; the combat log written out every 5 s OUT of a fight (in a fight the game ignores it - measured); m95: in a fight the combat log is stopped and started every 3 s (the game writes it only every ~5 min otherwise; the user picked "Try the restart trick"), its chat lines hidden; m94: m93's snippet comment held the word f-u-n-c-t-i-o-n, which the game's secure checker refuses even in a comment - every A / X / Y / B / D-pad press did nothing (the user, test9); m93: SMART Y IN A FIGHT - with no fight spell on Y (or given back by B), Y casts the top buff of your Smart buff list (wc-smarty-cb, armed out of combat); m92: each party member's GUID (g) for the app's combat-log health; Advanced Combat Logging and the combat log switched on at login (the user's ruling 2026-11-07); m91: the party row's column 36 is a secure /startattack cell (WC.atkCell: Hub's Start attack on hostile target); m90: after a fight with hits taken, food is due (90 s, until eating) - the game's eating rule; m89: a hit with a hidden amount counts as 6 % of the max health (Smart Y food by hits taken); m88: UNIT_COMBAT seen / hidden-amount counts in the why line (uc); m87: Y is routed while Smart Y is on, even with no In-combat spell on Y (Smart Y never heard a bare Y before); m86: health read from the DRAWN bar fill (texture width / bar width) when the value is secret; your own cell from the player frame first; m85: a party member's error no longer drops the party document (reported once, probe partyerr); Manners' prompt always hidden; m84: your own party cell takes a plain reading before the vitals' estimate (hs vit:<src>); m83: party members found by the party count / name too (UnitExists may be secret); m82: the party roster looked at again 2 / 5 / 10 s after a login (tokens come late); m81: a fight spell's cooldown starts when its cast lands (UNIT_SPELLCAST_SUCCEEDED: Raptor Strike's swing) and stands against GCD / inactive reads; a failed tooltip cooldown read is tried again; m80: a party member out of range by the game's UnitInRange when no spell answers (rng); m79: party health read through any plain reader (the unit, its percent, the game's party bars) before the estimate, its source in the document (hs); m78: MANNERS ON SMART Y (after food / water and your buffs; its prompt hidden); m77: PARTY MODE (PARTY_BUILD.md: the party pad, Select, the party row and documents); m76: Smart Y On / Off per character (off by default: Y as before Smart Y); m75: Smart Y's buff before food / drink when the mana covers it; every fight spell's cooldown; m74: Smart Y uses a quest's item on its objective target; m73: WC.vitals() for other add-ons (HUD mode's resource bar); m72: the vitals diagnostic removed (its secure snippet errored on every load); m71: the not-enough-mana set for the app's spell pictures; m70: Smart Y's health / mana MODEL where every reader is secret (UNIT_COMBAT, cast costs, regen); m69: the diagnostic round 5 (events: regen rate, UNIT_COMBAT, the combat log, food auras); m68: the diagnostic round 4 (GUID percent, the calculator by name, the secure env); m67: the diagnostic round 3 (heal prediction, curves, a status bar); m66: the diagnostic in short lines, the queries with arguments; m65: the vitals DIAGNOSTIC (one read-only cap line per load: what is secret and why); m64: Smart Y reads health / mana through whatever this client leaves plain (secret values); m63: Smart Y's food / drink found by the item's class too, and why nothing was picked; m62: Smart buffs (the player's buff rows after food / water, OR rows by a wheel); m61: Smart Y (food / drink from the bags out of combat, nothing targeted); m60: a pole in hand, X still interacts when there is something to interact with; m59: a fishing pole in the main hand makes a bare X Fishing / Interact; m58: the forms' own In-combat spells get their state too (mana / range tint)
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
    -- m74: the quest item armed on Y (Home's Y shows it): id, name, icon, the quest
    doc.qitem = OV.qiNow and { id = OV.qiNow.item, i = OV.qiNow.icon, q = OV.qiNow.q, n = OV.qiNow.name } or nil
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
-- m101 THE TARGET RADIAL (the user, 2026-11-07: "When targeting something while not in the combat state, I want the Y glyph to appear over
-- them for the inspect menu. Instead of that menu opening a drop down I want to design a radial for it"; picks: the bottom screen, tap Y /
-- aim / A, the 8 most used + More, the glyph on the nameplate else beside the target frame). Y's own button carries "wc-tradial" (out of
-- combat, routerBind); the snippet turns a bare Y with a target into "tradial" (no game action), the post-click reads the target's menu
-- (Quests q51, src "radial") and the app opens its wheel. THE GLYPH: the game's own input icon (InputIconTextureFrameTemplate, PAD4) on
-- an add-on frame of ours, anchored over the target's nameplate (read only), else beside TargetFrame; shown only while Y would open it.
-- m104 THE LB PARTY-TARGET MAP (the user, 2026-11-08: "When holding LB i want the bottom screen to shift around ... the players bar with the
-- A button glyph, and below that ... a cross layout of the other 4 party members with the dpad glyphs ... the default controller behavior
-- for selecting a party member to target. We are just visualizing it"; which direction is which: "Not sure: read it from the game").
-- LB is one of the game's emulated modifiers (GamePadEmulateCtrl / Shift / Alt = "PADLSHOULDER"); the keys of TARGETSELF and
-- TARGETPARTYMEMBER1..4 in the controller's binding context name the buttons. READ ONLY (GetCVar / GetBindingKey); the document "lbmap"
-- says { mod, self, p1..p4 = "PAD1" / "PADDUP" ... or nil, keys = every key found } at login and when the bindings change.
OV.lbmap = {}
OV.lbmap.ACTS = { self = "TARGETSELF", p1 = "TARGETPARTYMEMBER1", p2 = "TARGETPARTYMEMBER2", p3 = "TARGETPARTYMEMBER3", p4 = "TARGETPARTYMEMBER4" }
OV.lbmap.modOf = function()
    for _, m in ipairs({ "Ctrl", "Shift", "Alt" }) do
        if TS(function() return GetCVar("GamePadEmulate" .. m) end) == "PADLSHOULDER" then return m:upper() end
    end
    return nil
end
-- the keys bound to [act] (the controller's context first, then the plain one), every one
OV.lbmap.keysOf = function(act)
    local out, seen = {}, {}
    for _, ctx in ipairs({ bindCtx() or false, false }) do
        local r = { pcall(GetBindingKey, act, nil, ctx or nil) }
        if r[1] then
            for i = 2, #r do
                local k = r[i]
                if type(k) == "string" and WC.isPlain(k) and not seen[k] then seen[k] = true; out[#out + 1] = k end
            end
        end
    end
    return out
end
-- the button under the LB modifier ("CTRL-PADDUP" -> "PADDUP"), or nil
OV.lbmap.button = function(keys, mod)
    if not mod then return nil end
    local pre = mod .. "-"
    for _, k in ipairs(keys) do
        if k:sub(1, #pre) == pre then
            local b = k:sub(#pre + 1)
            if b:match("^PAD") and not b:find("-") then return b end
        end
    end
    return nil
end
OV.lbmap.send = function()
    local mod = OV.lbmap.modOf()
    local doc = { type = "lbmap", v = PV, mod = mod, keys = {} }
    local last = {}
    for name, act in pairs(OV.lbmap.ACTS) do
        local keys = OV.lbmap.keysOf(act)
        doc.keys[name] = #keys > 0 and keys or nil
        doc[name] = OV.lbmap.button(keys, mod)
        last[name] = doc[name]
    end
    OV.lbmap.last = last                                -- m110: Hub's party frames read it (no binding read of their own)
    -- m121 (the user: "what LB will target if I release with no buttons"): a tap of the modifier button alone fires its own binding -
    -- its action and the game's name for it (read only)
    for _, ctx in ipairs({ bindCtx() or false, false }) do
        local ok, a = pcall(GetBindingAction, "PADLSHOULDER", true, ctx or nil)
        if ok and type(a) == "string" and a ~= "" and WC.isPlain(a) then doc.tap = a; break end
    end
    if doc.tap then
        local n = _G["BINDING_NAME_" .. doc.tap]
        if type(n) == "string" and WC.isPlain(n) then doc.tapn = n end
    end
    pcall(function() WC.send(WC.encode(doc)); WC.push() end)
end
do
    local f = CreateFrame("Frame")
    for _, e in ipairs({ "PLAYER_ENTERING_WORLD", "UPDATE_BINDINGS", "CVAR_UPDATE" }) do pcall(f.RegisterEvent, f, e) end
    f:SetScript("OnEvent", function(_, e, a1)
        if e == "CVAR_UPDATE" and not (type(a1) == "string" and a1:find("GamePadEmulate")) then return end
        pcall(C_Timer.After, 2, function() pcall(OV.lbmap.send) end)
    end)
end

OV.tg = { f = nil, at = nil }
OV.tradialOn = function() return true end
OV.tg.read = function()
    if type(WC.pmenu) == "table" and type(WC.pmenu.read) == "function" then WC.pmenu.read("target", "radial") end
end
-- m106 (the game's taint log, 2026-11-08, after the user's "a ton of lua errors"): the glyph was Blizzard's InputIconTextureFrameTemplate,
-- and its SetInputKey ran Blizzard's input-icon code (RefreshIconTextures / ApplyTextureLayout) as OUR code: the shared input-icon state
-- went tainted, then the gamepad UI (the buff frame's prompts, the prompted-binding footer, the loot / quest windows' binding stack) and
-- SetPreferredGamepadInteractTarget was forbidden from then on. Now it is OUR frame and OUR texture, with the game's own glyph atlas
-- (Gamepad_Ltr_Y_64; the Nintendo diamond's top button = Gamepad_Rev_X_64, the layout the room keeps): no Blizzard code runs.
OV.tg.atlasOf = function()
    -- m119 (the user, 2026-11-09: "the inspect glyph is Y not X"): the button that opens the wheel is Y; m120: in the layout's own set -
    -- the Nintendo glyphs (swap) the white Rev_Y, the plain layout the lettered Ltr_Y
    local db = _G.WowCompanionSpellsDB
    local plain = type(db) == "table" and db.padLayout == "plain"
    return plain and "Gamepad_Ltr_Y_64" or "Gamepad_Rev_Y_64"
end
OV.tg.make = function()
    if OV.tg.f then return OV.tg.f end
    local f = CreateFrame("Frame", "WowCompanionTargetY", UIParent)
    local t = f:CreateTexture(nil, "OVERLAY")
    t:SetAllPoints(f)
    f.wcTex = t
    f:SetSize(36, 36); f:SetFrameStrata("HIGH"); f:EnableMouse(false); f:Hide()
    OV.tg.f = f
    return f
end
-- the atlas for the layout now (set again only when it changes); no atlas on this client = the letter
OV.tg.look = function(f)
    local a = OV.tg.atlasOf()
    if f.wcAtlas == a then return end
    f.wcAtlas = a
    local ok = pcall(function() f.wcTex:SetAtlas(a) end) and pcall(function() return f.wcTex:GetAtlas() end)
    if ok then
        if f.wcText then f.wcText:Hide() end
        f.wcTex:Show()
    else
        f.wcTex:Hide()
        if not f.wcText then
            f.wcText = f:CreateFontString(nil, "OVERLAY", "GameFontNormalLarge"); f.wcText:SetPoint("CENTER")
        end
        f.wcText:SetText("Y"); f.wcText:Show()
    end
end
-- the glyph's place now: "plate" (the target's nameplate), "frame" (beside TargetFrame), "top" (no frame either), nil = not shown
OV.tg.want = function()
    if not OV.tradialOn() or T(function() return UnitExists("target") end) ~= true then return nil end
    local yb = face.buttons[6]
    if not yb or not (R.bound and R.bound[6]) or R.aside then return nil end
    -- m102: the controller's GROW (Home's rule, PRE's `sw`): a fight or an attack on, not given back by B -> no glyph
    if OV.tg.grown() then return nil end
    -- m103 (the user, 2026-11-07: "when there is an x interactable glyph that should trump the Y glyph for NPCs"): an NPC target that
    -- is also the game's soft-interact unit wears the game's own X; no Y over it (Y still opens the wheel)
    -- m120 (the user, 2026-11-09: "on the NPCs that have an X interact should trump the Y"): the readings may be secret - the comparisons
    -- run inside the pcall, and an answer the game will not give counts as "the same unit" (only a plain "not the same" keeps the Y)
    if T(function() return UnitIsPlayer("target") == true end) ~= true
        and T(function() return UnitExists("softinteract") == false end) ~= true
        and T(function() return UnitIsUnit("target", "softinteract") == false end) ~= true then
        return nil
    end
    -- a quest item for this target is Y's press (m74), not the radial
    if TS(function() return yb:GetAttribute("wc-qitem") end) and TS(function() return yb:GetAttribute("wc-qitem-u") end) ~= "soft" then return nil end
    -- m115 (the user, 2026-11-09: the target bar "should have the button glyph"): beside the name on Hub's target bar while it shows
    local okB, barName = pcall(function()
        local uf = WC.uf
        return type(uf) == "table" and uf.target and uf.tb and uf.tb.f and uf.tb.f:IsShown() and uf.tb.f.name or nil
    end)
    if not okB then barName = nil end
    -- m117: over the nameplate first (the bar's own small glyph rides along: the third answer)
    local okP, plate = pcall(function() return C_NamePlate.GetNamePlateForUnit("target") end)
    if okP and plate then return "plate", plate, barName end
    if barName then return "bar", barName end
    if _G.TargetFrame then return "frame", _G.TargetFrame end
    return "top", UIParent
end
-- m102 / m117: the GROW alone - a fight or an attack on, not given back by B (true / false; nil = no Y binding to read). Hub's target bar
-- hides the target's nameplate by this same rule (WC.tgGrown): the plate goes when the glyph goes, and comes back with it after B
OV.tg.grown = function()
    local yb = face.buttons[6]
    if not yb or not (R.bound and R.bound[6]) then return nil end
    return ((TS(function() return yb:GetAttribute("wc-state") end) == "fight" or TB(function() return yb:GetAttribute("wc-pre") end))
        and not TB(function() return yb:GetAttribute("wc-given") end)) and true or false
end
WC.tgGrown = function() local ok, v = pcall(OV.tg.grown); if ok then return v end end
OV.tg.update = function()
    local f = OV.tg.make()
    local where, rel, bar2 = OV.tg.want()
    -- no nameplate: the bar's small glyph alone (never the big one beside the bar's name)
    if where == "bar" then bar2 = rel; where = nil end
    -- m118: Hub draws its own prompt right of the target bar while the button would open the wheel; only the face letter is said here
    -- m120: the glyph's atlas itself (Hub draws the same one)
    WC.tgPrompt = bar2 and OV.tg.atlasOf() or nil
    if not where then f:Hide(); OV.tg.at = nil; return end
    OV.tg.look(f)
    if OV.tg.at ~= rel then
        f:ClearAllPoints()
        if where == "bar" then f:SetPoint("LEFT", rel, "RIGHT", 6, 0)
        elseif where == "plate" then f:SetPoint("BOTTOM", rel, "TOP", 0, 2)
        elseif where == "frame" then f:SetPoint("LEFT", rel, "RIGHT", 4, 0)
        else f:SetPoint("TOP", rel, "TOP", 0, -140) end
        OV.tg.at = rel
    end
    f:Show()
end
do
    local ev = CreateFrame("Frame")
    for _, e in ipairs({ "PLAYER_TARGET_CHANGED", "PLAYER_REGEN_DISABLED", "PLAYER_REGEN_ENABLED", "NAME_PLATE_UNIT_ADDED",
                         "NAME_PLATE_UNIT_REMOVED", "PLAYER_ENTERING_WORLD" }) do pcall(ev.RegisterEvent, ev, e) end
    ev:SetScript("OnEvent", function(_, e) OV.tg.at = nil; pcall(OV.tg.update) end)
    -- the binding, a room taking the controller, a quest item armed, an attack starting / B: a light re-check four times a second
    pcall(C_Timer.NewTicker, 0.25, function() pcall(OV.tg.update) end)
end
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
        -- m77 PARTY MODE: while "wc-party" is set (and no room holds: room mode first) every press goes to the party pad, as room mode delivers
        local pdest = mode ~= "room" and self:GetAttribute("wc-party") and self:GetAttribute("wc-party-dest") or nil
        if pdest then mode = "room" end
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
        local bmacro = nil
        if role == "back" then
            -- B (SPEC item 2, the user: "when I push b it goes back to the non combat layout even if im in combat"): a bare B in the
            -- fight state gives A / X / Y back (their attributes, through frame refs), then does Forever's own Back
            -- m99 (the user, 2026-11-07: "when pressing B in combat the auto attack is still on. It should stop the auto attack as well
            -- as leaving combat mode. Keeping that enemy targeted, pressing B again would drop the target"): the first B in a fight
            -- gives back AND stops the attack (the target stays); a B with the bars already given back clears the target
            if bare and on and mode ~= "room" then
                local f1 = self:GetFrameRef("wc-face-8") or self:GetFrameRef("wc-face-5")
                local already = f1 and f1:GetAttribute("wc-given")
                for _, p in ipairs(newtable(1, 2, 3, 4, 5, 6, 8)) do
                    local f = self:GetFrameRef("wc-face-" .. p)
                    if f then f:SetAttribute("wc-given", true) end
                end
                if already then
                    if SecureCmdOptionParse and SecureCmdOptionParse("[@target,exists] 1") == "1" then bmacro = "/stopattack\n/cleartarget" end
                else
                    bmacro = "/stopattack"
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
        -- m74 QUEST ITEM ON Y (the user: "if there is a quest Item that I am supposed to use on them and map that to the smart y
        -- automatically"; "Any time it matches", fights too): armed OUT of combat for the target that matched ("wc-qitem" = the item,
        -- "wc-qitem-u" = "target" for an NPC / "soft" for an object); used on a bare Y while such a target exists, before anything else
        local qi = bare and mode ~= "room" and role ~= "back" and self:GetAttribute("wc-qitem")
        if qi and SecureCmdOptionParse then
            local qu = self:GetAttribute("wc-qitem-u")
            if not ((qu == "soft" and SecureCmdOptionParse("[@softinteract,exists] 1") == "1")
                or (qu ~= "soft" and SecureCmdOptionParse("[@target,exists,nodead] 1") == "1")) then qi = nil end
        elseif qi then qi = nil end
        if qi then fight = false end
        local smart = bare and mode ~= "room" and role ~= "back" and not sw and not qi and self:GetAttribute("wc-smarty")
        -- m101 (the user, 2026-11-07: "When there is something soft targeted to loot, smart y does not work. I press the button and
        -- nothing happens"): a soft-interact target (a corpse to loot, a node) is X's (Interact), not Y's - it no longer blocks Smart Y;
        -- a real target still does (the target radial's), and a quest item for an object still wins (qi, above)
        if smart and (not SecureCmdOptionParse or SecureCmdOptionParse("[combat] 1") == "1"
            or SecureCmdOptionParse("[@target,exists] 1") == "1") then
            smart = nil
        end
        -- m93 (the user, 2026-11-07: "smart Y for buffs does not work in combat ... I dont currently have a Y spell in combat, plus if I
        -- press B and drop the combat bars ... it should be able to [work]"): in a fight, a bare Y that reaches here (no fight spell,
        -- or given back) casts the top buff of the Smart buff list on yourself. The snippet cannot see auras, so it casts it each press
        local cbuff = nil
        if not smart and bare and mode ~= "room" and role ~= "back" and not sw and not qi and SecureCmdOptionParse
            and SecureCmdOptionParse("[combat] 1") == "1" then
            cbuff = self:GetAttribute("wc-smarty-cb")
            if cbuff then
                smart = cbuff
                self:SetAttribute("wc-smarty-cb", nil)          -- m99: once a fight (the buff is up after it)
            end
        end
        -- m101 THE TARGET RADIAL: a bare Y with a target, nothing else on it (no quest item, no Smart Y, no fishing, no fight spell):
        -- no game action; the post-click says "tradial" and the app opens its radial of the target's menu. m102 (the user: "go away when
        -- I /startattack, same rules as the button grow animation"): not while the controller is GROWN (sw: a fight or an attack on,
        -- not given back by B) - in a fight after B it opens again
        -- m123 (the user, 2026-11-10: "pressing y in combat is opening the context menu on the top screen. this should open on the bottom
        -- screen and hide on top"): GROWN too - in a fight a bare Y with no fight spell on it opens the radial, never the game's top menu
        local trad = bare and mode ~= "room" and role ~= "back" and role ~= "dpad" and not qi and not smart and not fish
            and not fight and self:GetAttribute("wc-tradial") and SecureCmdOptionParse
            and SecureCmdOptionParse("[@target,exists] 1") == "1"
        local mb = "LeftButton"
        self:SetAttribute("unit", nil)                                  -- m62: only Smart Y's buff casts on yourself
        if mode == "room" and (pdest or self:GetAttribute("wc-room-dest")) then
            dest = pdest or self:GetAttribute("wc-room-dest"); mb = self:GetAttribute("wc-key") or mb; fight = false
        end
        self:SetAttribute("wc-mb", mb)
        if bmacro then
            self:SetAttribute("wc-kind", "bstop")
            self:SetAttribute("pressAndHoldAction", nil)
            self:SetAttribute("typerelease", nil)
            self:SetAttribute("type", "macro")
            self:SetAttribute("macrotext", bmacro)
        elseif trad then
            self:SetAttribute("wc-kind", "tradial")
            self:SetAttribute("pressAndHoldAction", nil)
            self:SetAttribute("typerelease", nil)
            self:SetAttribute("type", nil)
        elseif qi then
            self:SetAttribute("wc-kind", "qitem")
            self:SetAttribute("pressAndHoldAction", nil)
            self:SetAttribute("typerelease", nil)
            self:SetAttribute("type", "item"); self:SetAttribute("item", qi)
            if self:GetAttribute("wc-qitem-u") ~= "soft" then self:SetAttribute("unit", "target") end
        elseif fight then
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
            local st = cbuff and "spell" or self:GetAttribute("wc-smarty-t")
            self:SetAttribute("wc-kind", "smarty")
            self:SetAttribute("pressAndHoldAction", nil)
            self:SetAttribute("typerelease", nil)
            if st == "spell" then
                self:SetAttribute("type", "spell"); self:SetAttribute("spell", smart); self:SetAttribute("unit", "player")
            elseif st == "or" then
                self:SetAttribute("type", "macro"); self:SetAttribute("macrotext", "/click WowCompanionPadSmartyOr")
            elseif st == "manners" then
                -- m78: Manners' own lines for this press (OV.mannersPre copied them off its prompt, after its own PreClick armed them);
                -- none = nothing to cast this press (Manners says why in its chat line)
                local mt = self:GetAttribute("wc-manners-text")
                self:SetAttribute("type", mt and "macro" or nil); self:SetAttribute("macrotext", mt)
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
    if OV.pm and OV.pm.link then pcall(OV.pm.link) end            -- m77: Party mode's pad and flips refer to every router button
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
    if pos == 6 then b:SetAttribute("wc-smarty-cb", OV.combatBuff) end                                             -- m93: in a fight
    if pos == 6 then b:SetAttribute("wc-qitem", OV.qiVal); b:SetAttribute("wc-qitem-u", OV.qiUnit) end             -- m74: the quest item
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
-- m132: `skip` = a set of names (WINDOWS' own) that do not count, also as a managed frame (Party mode's prompts: PM.PROMPTS)
local function windowOpen(skip)
    local hit = nil
    pcall(function()
        local m = _G.GamepadMode and _G.GamepadMode.FrameControlsManager
        local skipF = {}
        for name in pairs(skip or {}) do if type(_G[name]) == "table" then skipF[_G[name]] = true end end
        for _, f in ipairs(m and type(m.shownFrames) == "table" and m.shownFrames or {}) do
            if not skipF[f] and TB(function() return f:IsShown() end) then hit = "managed"; return end
        end
        for _, slot in ipairs({ "left", "center", "right", "doublewide", "fullscreen" }) do
            local pnl = T(function() return GetUIPanel(slot) end)
            if pnl and TB(function() return pnl:IsShown() end) then hit = "panel"; return end
        end
        for _, name in ipairs(WINDOWS) do
            local f = _G[name]
            if not (skip and skip[name]) and type(f) == "table" and TB(function() return f:IsShown() end) then hit = name; return end
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
    -- m132 (the user, 2026-11-11, a healer: "Party mode needs to be usable in combat for healers. Locking it is retarded"): while Party
    -- mode is allowed, a fight's start NEVER steps aside for a game window (a loot roll, a popup left open at the pull): every key and
    -- Select are carried for the whole fight (the window is answered by touch); a room holding gets it first through room mode
    local pmFight = fightStart and OV.pm and OV.pm.allowed and OV.pm.allowed() or false
    if fightStart then aside = (win and not holder and not pmFight) and win or nil else aside = win or (holder and "room") or nil end
    local before = R.aside
    R.aside = aside
    pcall(ClearOverrideBindings, hdr)
    R.bound = {}
    if not aside then
        for pos, key in pairs(face.keys) do
            local b, choice = face.buttons[pos], faceStore()[pos]
            if pos == 6 and b then b:SetAttribute("wc-tradial", OV.tradialOn() or nil) end                          -- m101
            if b and key and (type(choice) == "table" or (pos == 6 and ((OV.smartOn and OV.smartOn() == true) or OV.tradialOn()))) then      -- m87 / m101
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
    -- m77: not while Party mode is on (a ZL+A binding would beat the chord the party pad reads); m132: nor for a fight that starts
    -- with Party mode allowed and grouped (it may be turned on mid-fight, when no binding can change: the party layers win)
    if not aside and not (OV.pm and OV.pm.on()) and not (pmFight and OV.pm.fightKeys and OV.pm.fightKeys()) then
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
    -- m77 PARTY MODE: on = all 8 router keys carried; Select to the party toggle (PARTY_BUILD.md 3.5)
    if OV.pm and OV.pm.bind then pcall(OV.pm.bind, aside, fightStart, carry) end
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
    if not aside and not (OV.pm and OV.pm.on()) then                   -- m34: the bindings on buttons (m77: not in Party mode)
        for chord, k in pairs(bindPlan()) do
            if TS(function() return GetBindingAction(chord, true) end) ~= k then lost = true end
        end
    end
    if not aside and OV.pm and OV.pm.lost and OV.pm.lost() then lost = true end   -- m77: Party mode's keys and Select
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
            -- m87 (the Thor's log, 2026-11-05: Smart Y armed a drink, Y did nothing): Y is routed while Smart Y is on even with no
            -- In-combat spell on it (its bare press is the game's own Y as before, Smart Y's pick out of combat)
            if chosen or (pos == 6 and OV.smartOn and OV.smartOn() == true) then face.keys[pos] = key end
            do
                if not b then
                    local okB, nb = pcall(CreateFrame, "Button", "WowCompanionCombatFace" .. pos, UIParent, "SecureActionButtonTemplate")
                    if okB and type(nb) == "table" then
                        b = nb; face.buttons[pos] = b; b:RegisterForClicks("AnyUp", "AnyDown")
                        local p = pos
                        -- m78: a Smart Y press of Manners runs Manners' own PreClick first (it arms its prompt for the press) and
                        -- its PostClick after (its ledger, its hand-back); insecure, out of combat only, on the key-down edge
                        if p == 6 then
                            pcall(function()
                                b:HookScript("PreClick", function(self, _, down) if down then pcall(OV.mannersPre, self) end end)
                                b:HookScript("PostClick", function(self, _, down) if down then pcall(OV.mannersPost, self) end end)
                                -- m101: the radial's press: the target's menu for it (Quests q51, src "radial")
                                b:HookScript("PostClick", function(self, _, down)
                                    if down and TS(function() return self:GetAttribute("wc-kind") end) == "tradial" then pcall(OV.tg.read) end
                                end)
                            end)
                        end
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
-- m75: the fight spell Home draws at base position [pos] now: in a form its own In-combat spell (m55); else X / Y / A's fight spell;
-- else the D-pad's In-combat spell (the overlay, m43) -> { n, id, i, tcd? } or nil
function OV.fightAt(pos)
    local f
    for _, fo in ipairs(OV.forms()) do if fo.on then f = fo end end
    if f then local e = OV.ffAt(f.id, pos); if e then return e end end
    if FACE[pos] then
        local c = faceStore()[pos]
        return (type(c) == "table" and type(c.n) == "string") and c or nil
    end
    if pos >= 1 and pos <= 4 then
        local s = storage(gamePage(), 1, pos)
        local e = s and OV.ftAt(s)
        return (type(e) == "table" and type(e.n) == "string") and e or nil
    end
    return nil
end
-- a spell's tooltip cooldown, read once per spell (the fight faces keep theirs as tcd)
OV.tcd = {}
OV.tcdAt = {}
function OV.tcdOf(id)
    if type(id) ~= "number" then return nil end
    -- m81: a failed read is tried again after 30 s (it was kept as "none" for good: a first read before the tooltip loaded hid a
    -- spell's cooldown for the session)
    if OV.tcd[id] == nil or (OV.tcd[id] == false and GetTime() - (OV.tcdAt[id] or 0) > 30) then
        OV.tcd[id] = (tooltipCd and tooltipCd(id)) or false
        OV.tcdAt[id] = GetTime()
    end
    return OV.tcd[id] or nil
end
-- SPELL_UPDATE_COOLDOWN: read each fight spell's cooldown flags NOW (the only time isOnGCD is trusted)
local function faceCooldowns()
    local now = GetTime()
    local learned = cdLearned()
    local changed = false
    -- m75 (the user: "Some spells on my bar are ... not showing their cooldown if they have one"): every position Home draws a fight
    -- spell on - X / Y / A and the D-pad's In-combat spells - and the spell it really draws there (a form's own one in a form, m55)
    for _, pos in ipairs({ 1, 2, 3, 4, 5, 6, 8 }) do
        local c = OV.fightAt(pos)
        local cur = cdNow[pos]
        if cur and now >= cur.t0 + cur.d then cdNow[pos] = nil; cur = nil end    -- m125: run out = gone (no "not active" read needed)
        if cur and type(c) == "table" and cur.n ~= c.n then cdNow[pos] = nil; cur = nil; changed = true end    -- another spell there now
        if type(c) == "table" and type(c.n) == "string" then
            local ok, info = pcall(C_Spell.GetSpellCooldown, c.id or c.n)
            local active = ok and type(info) == "table" and TB(function() return info.isActive end) == true
            local gcd = ok and type(info) == "table" and TB(function() return info.isOnGCD end) == true
            -- m81: a cooldown started by its cast landing (OV.cdCast: Raptor Strike's swing) stands until it runs out; a read that says
            -- "not active" (the server not there yet) or "the GCD" in the meantime does not wipe or shorten it
            local held = cur and cur.cast and now < cur.t0 + cur.d - 0.2 and (not active or gcd)
            if held then
            elseif not active then
                if cur then cdNow[pos] = nil; changed = true end
            else
                local dur = SN(function() return info.duration end)
                local st = SN(function() return info.startTime end)
                local real = dur ~= nil and dur > 0
                if real then                                            -- plain (out of combat): the real one, learned
                    if gcd then learned["@gcd"] = dur else learned[c.n] = dur end
                end
                if not cur or (cur.g or false) ~= gcd or (real and math.abs((cur.t0 + cur.d) - ((st or now) + dur)) > 0.05) then
                    local d = real and dur or (gcd and (learned["@gcd"] or 1.5) or (learned[c.n] or c.tcd or OV.tcdOf(c.id)))
                    -- MEASURED on the Thor (08:30, m12): a cast's first SPELL_UPDATE_COOLDOWN shows the spell on the GCD, its own cooldown comes
                    -- about 0.2 s later; that own cooldown started with the cast, so it is counted from the GCD's start
                    local t0 = (real and st) or now
                    if not real and not gcd and cur and cur.g and now - cur.t0 < cur.d then t0 = cur.t0 end
                    if d and d > 0 then
                        cdNow[pos] = { d = d, t0 = t0, g = gcd or nil, e = (not real) or nil, n = c.n }
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
-- m81 (the user, 2026-11-04: "about 60% of the time my CD on raptor strike is not showing"): an on-next-swing spell's cooldown starts
-- when the swing LANDS, often inside another spell's GCD, so the one SPELL_UPDATE_COOLDOWN look above missed it or took it for the GCD.
-- The cast landing (UNIT_SPELLCAST_SUCCEEDED, the player) starts the face's cooldown itself, by the spell's NAME (any rank), with its
-- learned / tooltip length; only a real cooldown (over 1.5 s), so a spell without one never gets a made-up timer.
OV.cdCast = CreateFrame("Frame")
pcall(function() OV.cdCast:RegisterUnitEvent("UNIT_SPELLCAST_SUCCEEDED", "player") end)
OV.cdCast:SetScript("OnEvent", function(_, _, _, _, spellID)
    pcall(function()
        local id = SN(function() return spellID end)
        local n = id and TS(function() return C_Spell.GetSpellName(id) end)
        if not n then return end
        local now, learned, hit = GetTime(), cdLearned(), false
        for _, pos in ipairs({ 1, 2, 3, 4, 5, 6, 8 }) do
            local c = OV.fightAt(pos)
            if type(c) == "table" and c.n == n then
                local d = learned[n] or c.tcd or OV.tcdOf(c.id)
                if d and d > 1.5 then cdNow[pos] = { d = d, t0 = now, e = true, n = n, cast = true }; hit = true end
            end
        end
        if hit then cdSend() end
    end)
end)

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
-- m124 THE RANK THAT CASTS: an In-combat spell is cast by its NAME (the best rank known), but its id was the rank on show when it was
-- chosen; a rank learned since left the pictures (cooldown, GCD, cast, range: all read by id) on the old rank, which the game treats as
-- superseded. Each entry's id / icon follows what its name resolves to now (a name with no plain answer is left alone). True = changed.
function OV.rankSync()
    local changed = false
    local function fix(e)
        if type(e) ~= "table" or type(e.n) ~= "string" or not WC.isPlain(e.n) then return end
        local ok, info = pcall(C_Spell.GetSpellInfo, e.n)
        if not ok or type(info) ~= "table" then return end
        local id = TN(function() return info.spellID end)
        local nm = TS(function() return info.name end)
        if not id or nm ~= e.n or id == e.id then return end
        e.id = id
        local ic = TN(function() return info.iconID end)
        if ic then e.i = ic end
        changed = true
    end
    for _, e in pairs(faceStore()) do fix(e) end
    for _, e in pairs(OV.ftdb()) do fix(e) end
    for _, t in pairs(OV.ffdb()) do if type(t) == "table" then for _, e in pairs(t) do fix(e) end end end
    return changed
end
OV.rkF = CreateFrame("Frame")
for _, e in ipairs({ "PLAYER_ENTERING_WORLD", "SPELLS_CHANGED" }) do pcall(function() OV.rkF:RegisterEvent(e) end) end
OV.rkF:SetScript("OnEvent", function()
    local ok, changed = pcall(OV.rankSync)
    if ok and changed then pcall(function() if emit(true) then WC.push() end end) end
end)
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
-- s = { hp, hpMax, mp, mpMax, level, eating, drinking, items = { { id, kind = "food" | "drink" | "both", sell, minLv, n } } }; [want]
-- (m127) "food" / "drink" = that kind alone (its item or nil), nil = the old food-then-drink choice (-> item, kind)
OV.smartPick = function(s, want)
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
    if want == "food" then if needHp and not s.eating then return best("food") end return nil end
    if want == "drink" then if needMp and not s.drinking then return best("drink") end return nil end
    -- the old fixed order (the why line's): food first; once eating, the next press is the drink (one action per press)
    if needHp and not s.eating then local f = best("food"); if f then return f, "food" end end
    if needMp and not s.drinking then local d = best("drink"); if d then return d, "drink" end end
    return nil
end
-- m127: one kind alone, for the ordered list's Food / Water rows (smartWalk walks them wherever the player put them)
OV.smartPickKind = function(s, want) return OV.smartPick(s, want) end
-- SMARTY-PICK-END
-- SMARTY-ORDER-BEGIN (pure: tools/test_smarty.py) m75: may a due buff go before food / drink? [food] = food or drink is due; [cost] = the
-- buff's mana cost (nil = none / not mana: it never waits), [mana] = the mana now (nil = unknown: the buff goes, as it would without food)
OV.smartOrder = function(food, cost, mana)
    if not food then return true end
    if not cost or cost <= 0 then return true end
    if not mana then return true end
    return mana >= cost
end
-- SMARTY-ORDER-END
-- SMARTY-WALK-BEGIN (pure: tools/test_smarty.py) m127 ANY ORDER (the user, 2026-11-11: "it shouldnt hardcode food and water as first and
-- second, let people move spell priorities above them"): ONE ordered list, walked top to bottom; the first row with something due wins.
-- [rows] = the player's list ({ k = "food" | "drink" | "manners" } or a buff row { ids }); [due] = what each kind would do now (a step,
-- or nil): due.food / due.drink / due.manners, and due.buff for the row due.buffAt (the first unsatisfied buff row: sbFirst's); a
-- step's [cost] = its mana cost (nil = none). m75's rule KEPT in the one place it still means something with an explicit order: a buff
-- the mana does not cover gives way to Food / Water FURTHER DOWN (Y would only say "not enough mana"; the drink brings the mana back).
-- A buff below Food is never lifted above it any more: the order is the player's. -> the step, its kind ("food" / "drink" / "manners"
-- / "buff"); nil = nothing due
OV.smartWalk = function(rows, due, mana)
    local list = {}
    for r, row in ipairs(rows or {}) do
        local k = type(row) == "table" and row.k or nil
        local st
        if k == "food" or k == "drink" or k == "manners" then st = due[k]
        elseif k == nil and r == due.buffAt then st = due.buff end
        if st then list[#list + 1] = { k = k or "buff", st = st } end
    end
    for i, e in ipairs(list) do
        local later = false
        for j = i + 1, #list do if list[j].k == "food" or list[j].k == "drink" then later = true end end
        if e.k ~= "buff" or OV.smartOrder(later, e.st.cost, mana) then return e.st, e.k end
    end
    return nil
end
-- m128 (review of m127): the item Y last armed for each kind ({ food = id, drink = id }): kept while the aura runs (once eating, the
-- walk no longer offers that kind), replaced when Y arms another; a "both" item counts for both. [st] = smartWalk's step (or nil)
OV.smartEatFrom = function(prev, st)
    local out = { food = prev and prev.food or nil, drink = prev and prev.drink or nil }
    if type(st) == "table" and st.typ == "item" and type(st.shown) == "table" and type(st.shown.id) == "number" then
        local k = st.shown.k
        if st.ik == "both" then out.food, out.drink = st.shown.id, st.shown.id
        elseif k == "food" or k == "drink" then out[k] = st.shown.id end
    end
    return out
end
-- the why line's "picked" from the walk's step: { id, kind } (the kind = the row: food / drink / manners / buff), nil = nothing due
OV.smartWhyPick = function(st, k)
    if type(st) ~= "table" or type(st.shown) ~= "table" then return nil end
    return { id = st.shown.id or 0, kind = k or st.shown.k or "?" }
end
-- SMARTY-WALK-END
-- the mana now (plain or modelled), nil = unknown; a character without a mana pool: nil
function OV.smartMana()
    local okv, v = pcall(OV.smartVitals)
    if not okv or type(v) ~= "table" or v.mpSrc == "none" then return nil end
    return v.mp
end
-- a buff step's mana cost: one buff's, or an OR row's cheapest member's (nil = no mana cost)
function OV.sbCost(kind, e)
    if type(e) ~= "table" then return nil end
    if kind == "or" and OV.sbAt then
        local row = OV.sbdb().rows[OV.sbAt]
        local best
        for _, id in ipairs(row and row.ids or {}) do
            local c = OV.nmCost and OV.nmCost(id)
            if c and (not best or c < best) then best = c end
        end
        return best
    end
    return OV.nmCost and OV.nmCost(e.id) or nil
end
-- m64 (test32's why line: "hp ? | mana none" on a druid): this client makes UnitHealth("player") a SECRET value, in and out of combat
-- (Safety.lua's self-test measured it), and its power readers likewise, so the plain numbers were never there and food / drink never
-- came. Each bar is now read through the first reader this client leaves PLAIN (never compared or added while secret: issecretvalue /
-- WC.isPlain first): the unit's own numbers, its percent reader, then the player frame's own status bar (its value and range). Mana by
-- its own type (0) whatever the form. Nothing plain = UNKNOWN: never taken as full, said in the why line, and no item used for it.
-- -> { hp, hpMax, hpSrc, mp, mpMax, mpSrc }; mpMax 0 (plain) = no mana pool
function OV.smartVitals()
    -- a secret is never compared, added or put in a table (Safety's measured rules): asked first, kept in locals only
    local function plain(v)
        if type(issecretvalue) == "function" then local ok, q = pcall(issecretvalue, v); if not ok or q then return nil end end
        if type(WC.isPlain) == "function" and not WC.isPlain(v) then return nil end
        if type(v) == "number" then return v end
        return nil
    end
    local function call(f) local ok, a, b = pcall(f); if ok then return a, b end end
    local function pair(f1, f2) local a, b = plain(call(f1)), plain(call(f2)); if a and b and b > 0 then return a, b end end
    local function pct(f)
        local p = plain(call(f))
        if not p then return nil end
        if p <= 1 then p = p * 100 end                                 -- a fraction (0-1) or a percentage (0-100)
        return p, 100
    end
    local function bar(fr)
        if type(fr) ~= "table" or type(fr.GetValue) ~= "function" then return nil end
        local val = plain(call(function() return fr:GetValue() end))
        local lo, hi = call(function() return fr:GetMinMaxValues() end)
        lo, hi = plain(lo), plain(hi)
        if val and lo and hi and hi > lo then return val - lo, hi - lo end
    end
    local function path(...)
        local t = _G
        for _, k in ipairs({ ... }) do if type(t) ~= "table" then return nil end; t = rawget(t, k) or t[k] end
        return t
    end
    -- the frames that exist on this client, in order (a missing one leaves no hole)
    local function frames(list)
        local out = {}
        for _, names in ipairs(list) do local fr = path((unpack or table.unpack)(names)); if type(fr) == "table" then out[#out + 1] = fr end end
        return out
    end
    local v = {}
    -- health
    v.hp, v.hpMax = pair(function() return UnitHealth("player") end, function() return UnitHealthMax("player") end)
    if v.hp then v.hpSrc = "unit" end
    if not v.hp and type(UnitHealthPercent) == "function" then
        v.hp, v.hpMax = pct(function() return UnitHealthPercent("player") end); if v.hp then v.hpSrc = "pct" end
    end
    if not v.hp then
        for _, fr in ipairs(frames({ { "PlayerFrameHealthBar" }, { "PlayerFrame", "healthbar" },
                                     { "PlayerFrame", "PlayerFrameContent", "PlayerFrameContentMain", "HealthBarsContainer", "HealthBar" } })) do
            v.hp, v.hpMax = bar(fr); if v.hp then v.hpSrc = "frame"; break end
        end
    end
    -- mana, by its own type (0) whatever the form
    v.mp, v.mpMax = pair(function() return UnitPower("player", 0) end, function() return UnitPowerMax("player", 0) end)
    if v.mp then v.mpSrc = "unit" end
    if not v.mp then
        local mx = plain(call(function() return UnitPowerMax("player", 0) end))
        if mx == 0 then v.mp, v.mpMax, v.mpSrc = 0, 0, "none" end       -- a plain 0: no mana pool
    end
    if not v.mpSrc and type(UnitPowerPercent) == "function" then
        v.mp, v.mpMax = pct(function() return UnitPowerPercent("player", 0) end); if v.mp then v.mpSrc = "pct" end
    end
    if not v.mpSrc then
        -- the player frame's mana bar shows mana only while mana is the power shown (a druid out of form); its alternate bar in a form
        local ptype = plain(call(function() return UnitPowerType("player") end))
        local names = {}
        if ptype == 0 then
            names = { { "PlayerFrameManaBar" }, { "PlayerFrame", "manabar" },
                      { "PlayerFrame", "PlayerFrameContent", "PlayerFrameContentMain", "ManaBarArea", "ManaBar" } }
        end
        names[#names + 1] = { "PlayerFrameAlternateManaBar" }
        names[#names + 1] = { "PlayerFrame", "PlayerFrameContent", "PlayerFrameContentMain", "AlternatePowerBar" }
        for _, fr in ipairs(frames(names)) do
            v.mp, v.mpMax = bar(fr); if v.mp then v.mpSrc = "frame"; break end
        end
    end
    -- m70: nothing plain - the model (UNIT_COMBAT's plain amounts, cast costs, regen, anchors)
    local m = OV.HM
    if m and not v.hp and m.hpMax > 0 then v.hp, v.hpMax, v.hpSrc = m.hp, m.hpMax, "model" end
    if m and not v.mpSrc and m.mpMax > 0 then v.mp, v.mpMax, v.mpSrc = m.mp, m.mpMax, "model" end
    return v
end
-- m63: WHY food / drink picked nothing (tracking: "add a cap line saying WHY nothing was picked"): health / mana now, the food and drinks
-- found, what an unmatched consumable's use spell read; one cap line per change
function OV.smartWhy(s, pick)
    local function pct(v, m) return (type(v) == "number" and type(m) == "number" and m > 0) and math.floor(v / m * 100 + 0.5) or nil end
    local hp, mp = pct(s.hp, s.hpMax), pct(s.mp, s.mpMax)
    local nf, nd = 0, 0
    for _, it in ipairs(s.items or {}) do
        if it.kind == "food" or it.kind == "both" then nf = nf + 1 end
        if it.kind == "drink" or it.kind == "both" then nd = nd + 1 end
    end
    local mpNone = s.mpSrc == "none"
    if mpNone then mp = nil end
    local parts = { "hp " .. (hp and (hp .. "% via " .. (s.hpSrc or "?")) or "unreadable"),
                    "mana " .. (mpNone and "none" or (mp and (mp .. "% via " .. (s.mpSrc or "?")) or "unreadable")) }
    if pick then parts[#parts + 1] = "picked " .. pick.kind .. " " .. pick.id
    else
        if hp and hp < 80 then parts[#parts + 1] = s.eating and "eating already" or (nf == 0 and "no food matched" or "food above your level") end
        if mp and mp < 80 then parts[#parts + 1] = s.drinking and "drinking already" or (nd == 0 and "no drink matched" or "drinks above your level") end
        if hp and hp >= 80 and (mpNone or (mp and mp >= 80)) then parts[#parts + 1] = "both at or above 80%" end
        if not hp then parts[#parts + 1] = "health unknown: no food" end
        if not mp and not mpNone then parts[#parts + 1] = "mana unknown: no drink" end
    end
    parts[#parts + 1] = "food " .. nf .. ", drinks " .. nd
    local hm = OV.HM
    if hm and (s.hpSrc == "model" or s.mpSrc == "model") then
        parts[#parts + 1] = "model: wounds " .. math.floor(hm.wounds) .. " (uc " .. tostring(OV.ucN or 0) .. ", hidden " .. tostring(OV.ucSecret or 0) .. ", counted as hits " .. tostring(OV.ucGuess or 0) .. "), anchor " .. tostring(hm.anchor) .. " "
            .. math.floor(GetTime() - (hm.anchorAt or GetTime())) .. "s ago"
    end
    if s.skipped and #s.skipped > 0 then parts[#parts + 1] = "unmatched " .. table.concat(s.skipped, "; ") end
    return table.concat(parts, " | ")
end
function OV.smartSayWhy(s, pick)
    -- the percentages rounded to 10 so regeneration does not repeat the line every tick
    local w = OV.smartWhy(s, pick)
    local key = w:gsub("(%d+)%%", function(d) return tostring(math.floor(tonumber(d) / 10) * 10) .. "%" end):gsub("%d+s ago", "")   -- m126: not every second
    if key == OV.smartWhyKey then return end
    OV.smartWhyKey = key
    WC.send(WC.encode({ type = "cap", padmap = PV, smartwhy = w }))
end
OV.SMART_FOOD, OV.SMART_DRINK = 433, 430                                  -- the game's own "Food" / "Drink" auras: their names
-- m63 (the user on test29, a druid: "the smart buffs work, the food and water, not so much ... Nothing happens"; no "food" / "drink" pick
-- ever): the use spell's name alone matched nothing on this client. Now an item is food / drink by, in order: its use spell's name (the
-- game's Food 433 / Drink 430, or a name holding "food" / "drink" / "refreshment"), its tooltip (restores health / mana), and for the
-- game's own Food & Drink consumables (class 0 / subclass 5, Bags' and the item wheel's group 1) by the tooltip alone. -> kind, and
-- what was read (for the "why" line)
function OV.smartKind(id)
    local sn = T(function() return (C_Item and C_Item.GetItemSpell and C_Item.GetItemSpell(id)) or GetItemSpell(id) end)
    sn = type(sn) == "string" and sn or nil
    local ok, _, _, _, _, _, cls, sub = pcall(function() return C_Item.GetItemInfoInstant(id) end)
    local fd = ok and cls == 0 and sub == 5
    local function nm(sid) return T(function() return (C_Spell and C_Spell.GetSpellName and C_Spell.GetSpellName(sid)) or (GetSpellInfo(sid)) end) end
    if sn then
        if sn == nm(OV.SMART_FOOD) then return "food", sn end
        if sn == nm(OV.SMART_DRINK) then return "drink", sn end
        local l = sn:lower()
        if l:find("refreshment", 1, true) or (l:find("food", 1, true) and l:find("drink", 1, true)) then return "both", sn end
        if l:find("drink", 1, true) then return "drink", sn end
        if l:find("food", 1, true) then return "food", sn end
    end
    -- the tooltip only for the game's Food & Drink class: a potion's "Restores N health" is never food
    if not fd then return nil, sn end
    -- the tooltip: "Restores N health ..." / "... mana ..." (both = food and drink)
    local lines = T(function() return C_TooltipInfo and C_TooltipInfo.GetItemByID and C_TooltipInfo.GetItemByID(id) end)
    local h, m = false, false
    if type(lines) == "table" and type(lines.lines) == "table" then
        for _, ln in ipairs(lines.lines) do
            local t = type(ln) == "table" and type(ln.leftText) == "string" and ln.leftText:lower() or ""
            if t:find("restore", 1, true) then h = h or t:find("health", 1, true) ~= nil; m = m or t:find("mana", 1, true) ~= nil end
        end
    end
    if h and m then return "both", sn end
    if h then return "food", sn end
    if m then return "drink", sn end
    return nil, (sn or "no spell") .. (fd and " (food & drink class, no restore line)" or "")      -- buff-only and the rest: never
end
function OV.smartState()
    local v = OV.smartVitals()
    local s = { hp = v.hp, hpMax = v.hpMax, hpSrc = v.hpSrc, mp = v.mp, mpMax = v.mpMax, mpSrc = v.mpSrc,
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
    s.skipped = {}
    for id, n in pairs(count) do
        local kind, read = OV.smartKind(id)
        if not kind and read and #s.skipped < 4 then s.skipped[#s.skipped + 1] = id .. "=" .. read end
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
    -- m90 (the user's pick 2026-11-05, the game's eating rule: the estimate refilled between hits and never fell under 80 %): after a
    -- fight in which a hit landed, the food is due until eating starts, the next fight, or FOOD_AFTER_S; the health counts as low then
    -- m126: no floor here any more: the fight's hits went into the model once (OV.hitDrop, at the fight's end)
    return s
end
-- m126: a fight's hits off the model's health, once at its end: about 6 % a hit (m89's own figure), at most half; never above what the
-- model already says (a model that saw the wounds keeps its lower figure)
OV.HIT_PCT, OV.HIT_CAP = 0.06, 0.5
function OV.hitDrop(hits)
    local m = OV.HM
    if not m or type(m.hpMax) ~= "number" or m.hpMax <= 0 or type(hits) ~= "number" or hits <= 0 then return end
    local want = m.hpMax * (1 - math.min(OV.HIT_CAP, hits * OV.HIT_PCT))
    if m.hp > want then m.hp = want end
    m.anchor, m.anchorAt = "hits", GetTime()
end
-- m76 SMART Y ON / OFF (the user: "The Smart Y button should be a toggle, OFF by default"): per character in WowCompanionSpellsDB.smartOn;
-- OFF = nothing of Smart Y is armed (no food / drink, no buff, no quest item): Y is Forever's own Inspect out of a fight and its fight spell
-- in one, exactly as before Smart Y. Set from Settings by the gamemenu row's spare cells 6 (on) / 7 (off), absolute (a repeat is harmless)
OV.SMART_ON_COL, OV.SMART_OFF_COL = 6, 7
function OV.smartOn()
    local db = _G.WowCompanionSpellsDB
    local k = whoKey()
    return k ~= nil and type(db) == "table" and type(db.smartOn) == "table" and db.smartOn[k] == true
end
function OV.setSmartOn(on)
    local k = whoKey()
    if not k then return end
    if type(_G.WowCompanionSpellsDB) ~= "table" then _G.WowCompanionSpellsDB = {} end
    local db = _G.WowCompanionSpellsDB
    if type(db.smartOn) ~= "table" then db.smartOn = {} end
    db.smartOn[k] = on and true or nil
    pcall(function() WC.send(WC.encode({ type = "smartyack", v = PV, op = on and "on" or "off", ok = true })) end)
    pcall(OV.smartApply); pcall(OV.qiApply); pcall(emit, true); WC.push()
    if OV.pm and OV.pm.smartChanged then pcall(OV.pm.smartChanged) end   -- m77: Smart Y's party picks follow the switch
    pcall(faceSetup)                                                      -- m87: Y routed (or given back) with the switch
end
function OV.smartApply()
    if inCombat() then OV.smartPending = true; return end
    OV.smartPending = nil
    local state = OV.smartState()
    -- m127 THE ORDERED LIST (the user, 2026-11-11): Food, Water, Manners and the buff rows in the player's own order, walked top to
    -- bottom (OV.smartWalk); m62's fixed "food and water first" and m78's fixed "Manners last" are now just the default order
    local rows = OV.sbdb().rows
    local due = {}
    local function item(it, k) return it and { val = "item:" .. it.id, typ = "item", ik = it.kind, shown = { id = it.id, n = it.name, i = it.icon, k = k } } or nil end
    due.food, due.drink = item(OV.smartPickKind(state, "food"), "food"), item(OV.smartPickKind(state, "drink"), "drink")
    local okb, bv, bt, be = pcall(OV.sbStep)                              -- the OR cells kept current either way
    if okb and bv then
        due.buffAt = OV.sbAt
        due.buff = { val = bv, typ = bt, cost = OV.sbCost(bt, be), shown = { id = be.id, n = be.n, i = be.i, k = bt == "or" and "or" or "buff" } }
    end
    -- m78 MANNERS (the user: "add this addon to the default smart Y stack"): its row in the list; m127: only ever a buff for ANOTHER
    -- player (OV.mannersArmed's filter: never you, never a bare /use, never a tracking spell)
    if OV.sbHas(rows, "manners") then
        local ms, mw = OV.mannersArmed()
        if ms then due.manners = { val = "manners", typ = "manners", shown = { id = 0, n = mw and (ms .. " on " .. mw) or ms, i = OV.mannersIcon(ms), k = "manners" } } end
    end
    local st, sk = OV.smartWalk(rows, due, OV.smartMana())
    -- m128 (review of m127): the why line and the model's eating / drinking rate from the step the walk CHOSE (m70's rate per kind)
    OV.smartEat = OV.smartEatFrom(OV.smartEat, st)
    pcall(OV.smartSayWhy, state, OV.smartWhyPick(st, sk))
    local val, typ = st and st.val or nil, st and st.typ or nil
    OV.smarty = st and st.shown or nil
    if not OV.smartOn() then val, typ, OV.smarty = nil, nil, nil end    -- m76: off = nothing armed, Y as before Smart Y
    pcall(OV.mannersLook)
    local b = face.buttons[6]
    if b then pcall(b.SetAttribute, b, "wc-smarty", val); pcall(b.SetAttribute, b, "wc-smarty-t", typ) end
    OV.smartTyp = typ
    -- m93 / m99 (the user, 2026-11-07: Y "casts my first spell, in this case earthbound weapon, when I dont even require it"): the
    -- fight-time buff = the first buff of the list that is MISSING now (the real auras and the weapon's enchant); every one up = none,
    -- and Y in the fight stays what it shows
    local cb = nil
    if OV.smartOn() then
        local okc, r2, m2 = pcall(function()
            local _, byName = OV.sbKnown()
            local mh = T(function() return (GetWeaponEnchantInfo()) end) == true
            return OV.sbFirst(OV.sbdb().rows, byName, OV.sbName, OV.sbAuras(), mh)
        end)
        if okc and r2 and type(m2) == "table" and m2[1] and type(m2[1].n) == "string" then cb = m2[1].n end
    end
    OV.combatBuff = cb
    if b then pcall(b.SetAttribute, b, "wc-smarty-cb", cb) end
    local hasTarget = T(function() return UnitExists("target") end) == true
    local shown = (OV.smarty and not hasTarget) and (OV.smarty.k .. tostring(OV.smarty.id) .. (OV.smarty.k == "manners" and OV.smarty.n or "")) or nil
    OV.smartyShown = shown and OV.smarty or nil
    if val ~= OV.smartVal or shown ~= OV.smartShownId then
        if val ~= OV.smartVal then
            pcall(function() WC.send(WC.encode({ type = "cap", padmap = PV, smarty = OV.smarty and (OV.smarty.k .. " " .. tostring(OV.smarty.id)) or "off" })) end)
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
    -- m90: a fight's hits (OV.fightHits, counted by the model's UNIT_COMBAT) decide the food after it
    if e == "PLAYER_REGEN_DISABLED" then OV.fightHits = 0; return end   -- the snippet's [combat] keeps Y as it is in a fight
    if e == "PLAYER_REGEN_ENABLED" and (OV.fightHits or 0) > 0 then
        pcall(OV.hitDrop, OV.fightHits); OV.fightHits = 0                  -- m126: once, into the model; its regen does the rest
    end
    if OV.smartSoon then return end                                     -- one pass per 0.3 s, however many events
    OV.smartSoon = true
    C_Timer.After(0.3, function() OV.smartSoon = nil; pcall(OV.smartApply) end)
end)
-- m127 TRACKING NEVER ON Y (the user, 2026-11-11: "I dont think that I want to have the tracking abilities to be selectable here"): the
-- tracking spells by name (any rank): the game's tracking ids (Find Herbs / Minerals / Treasure / Fish, Sense Undead / Demons, every
-- Track ..., Find ...), and whatever the minimap's own tracking list names (C_Minimap.GetTrackingInfo(i) read until it answers
-- nothing, 40 at most: this build has no count getter; a table or several values; a secret name = nothing). Built at login and on
-- SPELLS_CHANGED / MINIMAP_UPDATE_TRACKING. Used wherever a spell could reach Smart Y: Manners' pick and press, the Add list, the add
-- itself, and Party mode's chooser / add (defensive: none offers one today).
OV.TRACK_IDS = { 2383, 8387, 2580, 8388, 2481, 1494, 19883, 5225, 19884, 19885, 19880, 19878, 19882, 19879, 5502, 5500, 43308 }
-- TRACK-BEGIN (pure: tools/test_smarty.py) a name without its rank, lower case; the set from [ids] by [nameOf] and the list [info](i)
-- (-> a name, false = an entry with no plain name, nil = the end)
OV.trackBase = function(n)
    if type(n) ~= "string" then return nil end
    n = n:gsub("%s*%(.-%)%s*$", ""):gsub("^%s+", ""):gsub("%s+$", "")
    return n ~= "" and n:lower() or nil
end
OV.trackBuild = function(ids, nameOf, info)
    local set = {}
    for _, id in ipairs(ids or {}) do local b = OV.trackBase(nameOf(id)); if b then set[b] = true end end
    if info then
        for i = 1, 40 do
            local n = info(i)
            if n == nil then break end
            local b = n and OV.trackBase(n)
            if b then set[b] = true end
        end
    end
    return set
end
OV.isTracking = function(name)
    local b = OV.trackBase(name)
    if not b then return false end
    if not OV.trackSet then pcall(OV.trackRebuild) end
    return (OV.trackSet or {})[b] == true
end
-- TRACK-END
function OV.trackInfo(i)
    local f = (type(C_Minimap) == "table" and C_Minimap.GetTrackingInfo) or _G.GetTrackingInfo
    if type(f) ~= "function" then return nil end
    local ok, a = CALL(f, i)
    if not ok or type(a) == "nil" then return nil end
    local n = a
    if type(a) == "table" then local okn, v = CALL(function() return a.name end); n = okn and v or nil end
    if type(n) == "string" and WC.isPlain(n) then return n end
    return false
end
function OV.trackRebuild() OV.trackSet = OV.trackBuild(OV.TRACK_IDS, OV.sbName, OV.trackInfo) end
OV.trackF = CreateFrame("Frame")
for _, e in ipairs({ "PLAYER_LOGIN", "SPELLS_CHANGED", "MINIMAP_UPDATE_TRACKING" }) do pcall(function() OV.trackF:RegisterEvent(e) end) end
OV.trackF:SetScript("OnEvent", function() pcall(OV.trackRebuild) end)
-- m78 MANNERS ON SMART Y (the user: "I want to add this addon to the default smart Y stack", Manners by Sacha, MIT, an optional
-- download in Settings > Add-ons; picks: after your own food / water and buffs, its prompt hidden while Smart Y is on). Manners decides
-- who and what (its own scan, never-list, cooldowns, speech); its prompt is the secure button MannersPrompt, armed out of combat with a
-- macro. Y cannot /click it (the client refuses a macro that clicks a macro button) and a "click" delegate would fire on the wrong edge
-- (its pressAndHoldAction), so a Y press does what a press of the prompt does: Manners' PreClick (it arms), its macrotext copied onto
-- Y for this press ("wc-manners-text"), Manners' PostClick (its ledger, its hand-back). Out of combat only, like all of Smart Y. Nothing
-- of Manners is changed but its prompt's alpha and mouse while Smart Y is on (hidden: Y is the way in); its binding, bar macro and
-- options stay its own. No Manners installed: nothing here runs.
-- MANNERS-READ-BEGIN (pure: tools/test_smarty.py) the prompt's macrotext -> the spell (or item use), the player it targets; nil = nothing
OV.mannersRead = function(text)
    if type(text) ~= "string" or text == "" then return nil end
    local spell = text:match("/cast%s+([^\n]+)") or text:match("/use%s+([^\n]+)")
    if not spell then return nil end
    spell = spell:gsub("^%[[^%]]*%]%s*", ""):gsub("%s+$", "")
    if spell == "" then return nil end
    local who = text:match("/target%s+([^\n]+)")
    if who then who = who:gsub("%s+$", ""); if who == "" then who = nil end end
    return spell, who
end
-- m127 OTHER PLAYERS ONLY (the user, 2026-11-11, Track Humanoids on Y: "I dont think that I want to have the tracking abilities to be
-- selectable here"; their pick: Manners as its own row that only ever buffs OTHER players). Manners also reminds you of your OWN buffs
-- and tracking ("/target <you>" + "/cast Track Humanoids", its "self" strategy; a scroll is a bare "/use item:<id>"): your own come only
-- from your rows. -> the spell and the player as mannersRead, or nil when the lines target you (the name before any "-" / space, any
-- case; "[@player]" too), are a bare /use with no /target, or cast a tracking spell on ANY /cast line. [me] = UnitName("player"),
-- [isTracking](name) = OV.isTracking. A /cast with no /target (Manners' group-buff "selfcast") is kept: it buffs the party.
OV.mannersBase = function(n)
    if type(n) ~= "string" then return nil end
    local b = n:match("^%s*([^%-%s]+)")
    return b and b:lower() or nil
end
OV.mannersOk = function(text, me, isTracking)
    local spell, who = OV.mannersRead(text)
    if not spell then return nil end
    for line in text:gmatch("[^\n]+") do
        local c = line:match("^%s*/cast%s+(.+)$")
        if c then
            if c:find("^%[[^%]]*@player") or c:find("^%[[^%]]*target=player") then return nil end
            c = c:gsub("^%[[^%]]*%]%s*", ""):gsub("%s+$", "")
            if isTracking and isTracking(c) then return nil end
        end
    end
    if isTracking and isTracking(spell) then return nil end
    if not who then
        if not text:find("/cast%s") then return nil end                 -- a bare /use: your own item
        return spell, nil
    end
    local wb, mb = OV.mannersBase(who), OV.mannersBase(me)
    if wb == "player" or (mb and wb == mb) then return nil end
    return spell, who
end
-- MANNERS-READ-END
function OV.mannersLater()
    if OV.smartSoon then return end
    OV.smartSoon = true
    C_Timer.After(0.3, function() OV.smartSoon = nil; pcall(OV.smartApply) end)
end
-- once per session: Smart Y hears the prompt arm, show and hide; Manners' own alpha is remembered (it fades the prompt itself)
function OV.mannersHook(mp)
    if OV.mannersHooked then return end
    OV.mannersHooked = true
    hooksecurefunc(mp, "SetAttribute", function(_, k) if k == "macrotext" or k == "type" then OV.mannersLater() end end)
    mp:HookScript("OnShow", OV.mannersLater)
    mp:HookScript("OnHide", OV.mannersLater)
    hooksecurefunc(mp, "SetAlpha", function(self, a)
        if OV.mannersOwnAlpha then return end
        OV.mannersAlpha = a
        if OV.mannersHidden and a ~= 0 then OV.mannersOwnAlpha = true; pcall(self.SetAlpha, self, 0); OV.mannersOwnAlpha = nil end
    end)
end
-- the prompt hidden while Smart Y is on (Y is the way in), Manners' own look while it is off; its mouse follows, out of combat
function OV.mannersLook()
    local mp = _G.MannersPrompt
    if not mp then return end
    pcall(OV.mannersHook, mp)
    -- m85 (the user, 2026-11-04: "when smart Y is off it has some ugly UI in the top screen ... that should be hidden"): always hidden
    -- while DS Forever runs; Y with Smart Y on is the way in, Manners' own key binding still works with it off
    local hide = true
    if hide == OV.mannersHidden and (inCombat() or OV.mannersMouse == not hide) then return end
    OV.mannersHidden = hide
    OV.mannersOwnAlpha = true
    pcall(mp.SetAlpha, mp, hide and 0 or (OV.mannersAlpha or 1))
    OV.mannersOwnAlpha = nil
    if not inCombat() and pcall(mp.EnableMouse, mp, not hide) then OV.mannersMouse = not hide end
end
-- what the prompt would cast now: the spell and the player (nil = nobody to buff, no Manners)
function OV.mannersArmed()
    local mp = _G.MannersPrompt
    if not mp then return nil end
    pcall(OV.mannersHook, mp)
    if T(function() return mp:IsShown() end) ~= true then return nil end
    if T(function() return mp:GetAttribute("type") end) ~= "macro" then return nil end
    return OV.mannersOk(T(function() return mp:GetAttribute("macrotext") end), OV.myName(), OV.isTracking)   -- m127: others only
end
function OV.myName()
    local n = T(function() return (UnitName("player")) end)
    return (type(n) == "string" and WC.isPlain(n)) and n or nil
end
-- m127: Manners is loaded (its prompt, else the game's add-on list); its row shows only then
function OV.mannersOn()
    if _G.MannersPrompt then return true end
    local f = (C_AddOns and C_AddOns.IsAddOnLoaded) or _G.IsAddOnLoaded
    return type(f) == "function" and T(function() return (f("Manners")) end) == true
end
function OV.mannersIcon(spell)
    local n = type(spell) == "string" and spell:gsub("%s*%(.-%)$", "") or nil
    if not n then return 0 end
    return T(function() return C_Spell and C_Spell.GetSpellTexture and C_Spell.GetSpellTexture(n) end)
        or T(function() return GetSpellTexture and GetSpellTexture(n) end) or 0
end
-- Y's PreClick (key-down, before the secure snippet): a Manners press gets Manners' own PreClick, then its lines for this press
function OV.mannersPre(b)
    OV.mannersPressed = nil
    if inCombat() then return end
    local mp = _G.MannersPrompt
    if b:GetAttribute("wc-smarty-t") ~= "manners" or not mp then b:SetAttribute("wc-manners-text", nil); return end
    -- the snippet's own gate (a target or a soft-interact target: Y is not Smart Y's), so Manners hears only its own presses
    if T(function() return UnitExists("target") end) == true
        or (SecureCmdOptionParse and SecureCmdOptionParse("[@softinteract,exists] 1") == "1") then
        b:SetAttribute("wc-manners-text", nil); return
    end
    -- m107 (the game's taint log, 2026-11-08: the buff frame and the gamepad binding stack read values WowCompanionSpells wrote,
    -- then SetPreferredGamepadInteractTarget was forbidden): Manners' own PreClick, run from here, ran ALL of Manners' code as ours
    -- (its buff scan, its prompt, its binding). It is never run now: only the prompt Manners has armed by itself is read
    local mt = mp:IsShown() and mp:GetAttribute("type") == "macro" and mp:GetAttribute("macrotext") or nil
    -- m127 (the verified report: the prompt re-arms on its own clock, so what mannersArmed approved 0.3 s ago is not what is copied
    -- now): the SAME filter at press time - you, a bare /use or a tracking spell = no lines, Y casts nothing
    if mt and not OV.mannersOk(mt, OV.myName(), OV.isTracking) then mt = nil end
    b:SetAttribute("wc-manners-text", mt)
    OV.mannersPressed = mt ~= nil
end
-- Y's PostClick: Manners' own, for the press it armed (its ledger, its hand-back); then Smart Y looks again
function OV.mannersPost(b)
    if not OV.mannersPressed then return end
    OV.mannersPressed = nil
    -- m107: Manners' own PostClick is not run from here either (see mannersPre); Smart Y looks again
    OV.mannersLater()
end
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
-- m127 (the user, 2026-11-11): Food, Water and Manners are rows of the same list now ({ k = ... }, OV.sbMigrate), moved and removed by
-- the same cells; Add (27) then a select cell adds one back (sel0 + 0 / 1 / 2); 16 rows shown at most (the 16 selects)
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
    if not k then return OV.sbMigrate({ rows = {} }, false) end
    if type(db.smartBuffs[k]) ~= "table" then db.smartBuffs[k] = { rows = {} } end
    return OV.sbMigrate(db.smartBuffs[k], OV.mannersOn())
end
-- SB-MIGRATE-BEGIN (pure: tools/test_smarty.py) m127 THE TYPED LIST, v2 (the user, 2026-11-11: food and water movable; Manners a row of
-- its own): rows = { { k = "food" }, { k = "drink" }, { k = "manners" }, { ids = { spell id, ... } } } in the player's order. v1 (rows
-- of { ids } only, food / water fixed above them, Manners fixed after them) -> Food, Water, the old buff rows, then Manners when it is
-- installed. Manners' row is added (at the end) whenever Manners is installed and the list has none, unless the player removed it
-- (mnOff); it stays in the list while Manners is not installed, hidden (OV.sbVis). Every call drops a malformed row and a second row
-- of the same kind.
OV.SB_KINDS = { "food", "drink", "manners" }                        -- after Add, select cell sel0 + 0 / 1 / 2 adds that kind
OV.sbMigrate = function(e, mannersOn)
    if type(e.rows) ~= "table" then e.rows = {} end
    if e.v ~= 2 then
        local rows = { { k = "food" }, { k = "drink" } }
        for _, row in ipairs(e.rows) do rows[#rows + 1] = row end
        e.rows, e.v = rows, 2
    end
    local out, seen = {}, {}
    for _, row in ipairs(e.rows) do
        local k = type(row) == "table" and row.k or nil
        if k == "food" or k == "drink" or k == "manners" then
            if not seen[k] then seen[k] = true; out[#out + 1] = { k = k } end
        elseif type(row) == "table" and k == nil and type(row.ids) == "table" and #row.ids > 0 then
            out[#out + 1] = row
        end
    end
    if mannersOn and not seen.manners and not e.mnOff then out[#out + 1] = { k = "manners" } end
    -- in place: a caller holding e.rows keeps the live table
    for i = #e.rows, 1, -1 do e.rows[i] = nil end
    for i, row in ipairs(out) do e.rows[i] = row end
    return e
end
-- the rows shown (indexes into rows): every row, less Manners' while Manners is not installed
OV.sbVis = function(rows, mannersOn)
    local v = {}
    for r, row in ipairs(rows or {}) do if row.k ~= "manners" or mannersOn then v[#v + 1] = r end end
    return v
end
OV.sbHas = function(rows, kind)
    for _, row in ipairs(rows or {}) do if row.k == kind then return true end end
    return false
end
-- SB-MIGRATE-END
-- the self-buffs this character knows (the book's spells, by name): { id, n, i, k = "aura" | "imbue" }, SB_IDS' order; byName for checks
function OV.sbKnown()
    local names = {}
    local ok, list = pcall(function() return type(WC.spellsBindableList) == "function" and WC.spellsBindableList() or {} end)
    for _, e in ipairs(ok and list or {}) do local n = e.id and OV.sbName(e.id); if n then names[n] = true end end
    local out, byName = {}, {}
    for _, id in ipairs(OV.SB_IDS) do
        local n = OV.sbName(id)
        if n and names[n] and not byName[n] and not OV.isTracking(n) then            -- m127: never a tracking spell
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
    if col == OV.SMART_ON_COL or col == OV.SMART_OFF_COL then OV.setSmartOn(col == OV.SMART_ON_COL) return end   -- m76
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
-- an op column from the app (no game action): a row select, or an op on the selected row, or the mode for the spell that follows.
-- m127: the selects and the ops address the rows SHOWN (OV.sbVis: Manners' hidden while it is not installed); right after Add (27) a
-- select cell names a kind instead (sel0 + 0 Food, + 1 Water, + 2 Manners: OV.SB_KINDS); Or / Take out are for buff rows only
function OV.sbOp(col)
    local e, now = OV.sbdb(), GetTime()
    local rows = e.rows
    local vis = OV.sbVis(rows, OV.mannersOn())
    if col < OV.SB_OPS.remove then
        local p = OV.sbPick
        if p and p.op == "add" and now - p.at <= 2 then OV.sbPick = nil; OV.sbAddKind(OV.SB_KINDS[col - OV.SB_SEL0 + 1]) return end
        OV.sbSel = { row = col - OV.SB_SEL0 + 1, at = now } return
    end
    local sv = OV.sbSel and now - OV.sbSel.at <= 2 and OV.sbSel.row or nil
    local sel = sv and vis[sv]
    OV.sbSel = nil
    if col == OV.SB_OPS.add then OV.sbPick = { op = "add", at = now } return end
    if not sel or not rows[sel] then OV.sbAck("row", false, "no row picked") return end
    if col == OV.SB_OPS.remove then
        if rows[sel].k == "manners" then e.mnOff = true end             -- removed by the player: not added back by itself
        table.remove(rows, sel)
    elseif col == OV.SB_OPS.up and sv > 1 then rows[sel], rows[vis[sv - 1]] = rows[vis[sv - 1]], rows[sel]
    elseif col == OV.SB_OPS.down and sv < #vis then rows[sel], rows[vis[sv + 1]] = rows[vis[sv + 1]], rows[sel]
    elseif col == OV.SB_OPS.addor or col == OV.SB_OPS.out then
        if rows[sel].k then OV.sbAck(col == OV.SB_OPS.addor and "or" or "out", false, "not a buff row") return end
        OV.sbPick = { op = col == OV.SB_OPS.addor and "or" or "out", row = sel, at = now } return
    else OV.sbAck("row", false, "nothing to do") return end
    OV.sbAck(col == OV.SB_OPS.remove and "remove" or "move", true)
    pcall(OV.smartApply); pcall(emit, true); WC.push()
end
OV.SB_MAX = 16                                                          -- the rows shown, the selects' 16 cells (8-23)
-- m127: Add Food / Water / Manners (a kind the list does not have) at the end
function OV.sbAddKind(kind)
    local e = OV.sbdb()
    local mn = OV.mannersOn()
    if not kind then OV.sbAck("add", false, "nothing to add") return end
    if kind == "manners" and not mn then OV.sbAck("add", false, "Manners is not installed") return end
    if OV.sbHas(e.rows, kind) then OV.sbAck("add", false, "already on the list") return end
    if #OV.sbVis(e.rows, mn) >= OV.SB_MAX then OV.sbAck("add", false, OV.SB_MAX .. " rows at most") return end
    e.rows[#e.rows + 1] = { k = kind }
    if kind == "manners" then e.mnOff = nil end
    OV.sbAck("add", true)
    pcall(OV.smartApply); pcall(emit, true); WC.push()
end
WC.smartYWants = function() return OV.sbPick ~= nil and GetTime() - OV.sbPick.at <= 2 end
WC.smartYSpell = function(id)
    local p = OV.sbPick
    OV.sbPick = nil
    if not p then return end
    if p.op == "padd" then if OV.pm and OV.pm.padd then OV.pm.padd(id) end return end    -- m77: Party mode's party buff list (col 34)
    local rows = OV.sbdb().rows
    local _, byName = OV.sbKnown()
    local n = OV.sbName(id)
    if n and OV.isTracking(n) then OV.sbAck(p.op, false, "tracking is never on Y") return end        -- m127
    local e = n and byName[n]
    if not e then OV.sbAck(p.op, false, "not a self-buff this character knows") return end
    if p.op == "add" then
        if #OV.sbVis(rows, OV.mannersOn()) >= OV.SB_MAX then OV.sbAck("add", false, OV.SB_MAX .. " rows at most") return end
        rows[#rows + 1] = { ids = { e.id } }
    else
        local row = rows[p.row]
        if not row then OV.sbAck(p.op, false, "the row is gone") return end
        if type(row.ids) ~= "table" then OV.sbAck(p.op, false, "not a buff row") return end      -- m127: Food / Water / Manners
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
-- m127: the rows SHOWN, typed ({ k = "food" | "drink" | "manners" | "buff", ids }: ids always sent, empty for a kind), "at" their index;
-- mn = Manners installed; the kinds' select cells after Add (kinds) and their icons (food / water: the game's bread and water items,
-- Manners: what it would cast now, else the Mark of the Wild picture); max = the rows at most
function OV.sbItemIcon(id)
    return T(function() return C_Item and C_Item.GetItemIconByID and C_Item.GetItemIconByID(id) end)
        or T(function() return (select(5, C_Item.GetItemInfoInstant(id))) end) or 0
end
function OV.sbDoc()
    local list = OV.sbList or OV.sbKnown()
    local e, mn = OV.sbdb(), OV.mannersOn()
    local rows, at = {}, nil
    for v, r in ipairs(OV.sbVis(e.rows, mn)) do
        local row = e.rows[r]
        rows[#rows + 1] = row.k and { k = row.k, ids = {} } or { k = "buff", ids = row.ids or {} }
        if r == OV.sbAt then at = v end
    end
    local mi = (OV.smarty and OV.smarty.k == "manners" and OV.smarty.i) or T(function() return C_Spell.GetSpellTexture(1126) end) or 0
    local kinds = {}
    for k, kind in ipairs(OV.SB_KINDS) do kinds[kind] = OV.SB_SEL0 + k - 1 end
    OV.sbDocNow = { v = 2, rows = rows, list = list, at = at, row = "gamemenu", pad = OV.sbRow ~= nil,
                    sel0 = OV.SB_SEL0, ops = OV.SB_OPS, on = OV.smartOn(), onCol = OV.SMART_ON_COL, offCol = OV.SMART_OFF_COL,
                    mn = mn, kinds = kinds, max = OV.SB_MAX,
                    icons = { food = OV.sbItemIcon(4540), drink = OV.sbItemIcon(159), manners = type(mi) == "number" and mi or 0 } }
end
-- m72: the vitals DIAGNOSTIC (m65-m69, rounds 2-5) is GONE (the user on test41: "I keep getting lua errors from wow companion": round 4's
-- secure snippet called UnitHealth, which the restricted environment does not have). What it found built m70's model below.
-- m70 SMART Y'S HEALTH / MANA MODEL (tracking after round 5, test38: current health and mana are secret by the client's base rule, every
-- direct and indirect reader too, the combat log cannot be registered - but UNIT_COMBAT's amounts are PLAIN, and the max values are
-- plain; the user: "we will find a way to make this work"). When no reader is plain, Smart Y's health and mana are ESTIMATED from what
-- is: est = max - every WOUND + every HEAL (UNIT_COMBAT) + regen out of combat (the class's spirit regen, at 80 % to stay on the safe
-- side) + food while eating; mana = max - every cast's mana cost (C_Spell.GetSpellPowerCost, plain) + spirit regen outside the
-- 5-second rule + drink while drinking. Anchored to FULL: 3 minutes out of combat with no wound (health) / no cast (mana); a food or
-- drink aura that ran its whole time with no rate known; a start with nothing kept = full. Death = empty, a revival = half. Kept per
-- character (name-realm) for 10 minutes across a /reload. The 80 % rule stands on the estimate; the why line says "via model".
-- SMARTY-MODEL-BEGIN (pure: tools/test_smarty.py)
-- per 2 s tick out of combat: health = a * spirit + b; mana (outside the 5-second rule) = c * spirit + e (the classic class rules)
OV.HM_REGEN = { DRUID = { 0.09, 6.5 }, HUNTER = { 0.25, 6 }, MAGE = { 0.10, 6 }, PALADIN = { 0.25, 6 }, PRIEST = { 0.10, 6 },
                ROGUE = { 0.50, 2 }, SHAMAN = { 0.11, 7 }, WARLOCK = { 0.07, 6 }, WARRIOR = { 0.80, 6 } }
OV.HM_MANA = { DRUID = { 0.20, 15 }, HUNTER = { 0.20, 15 }, PALADIN = { 0.20, 15 }, SHAMAN = { 0.20, 15 }, MAGE = { 0.25, 12.5 },
               PRIEST = { 0.25, 12.5 }, WARLOCK = { 0.25, 12.5 } }
OV.HM_SAFE, OV.HM_QUIET = 0.8, 180
OV.hmNew = function(hpMax, mpMax, now)
    return { hp = hpMax, hpMax = hpMax, mp = mpMax, mpMax = mpMax, at = now, quietFrom = now, castAt = nil, wounds = 0,
             anchor = "start", anchorAt = now }
end
OV.hmMax = function(m, hpMax, mpMax)
    if hpMax and hpMax > 0 then m.hpMax = hpMax; if m.hp > hpMax then m.hp = hpMax end end
    if mpMax and mpMax >= 0 then m.mpMax = mpMax; if m.mp > mpMax then m.mp = mpMax end end
end
OV.hmWound = function(m, amt, now) m.hp = math.max(0, m.hp - amt); m.wounds = m.wounds + amt; m.quietFrom = now end
OV.hmHeal = function(m, amt) m.hp = math.min(m.hpMax, m.hp + amt) end
OV.hmCast = function(m, cost, now) if cost > 0 then m.mp = math.max(0, m.mp - cost); m.castAt = now end end
OV.hmGain = function(m, amt) m.mp = math.min(m.mpMax, m.mp + amt) end
OV.hmFull = function(m, why, now, hp, mp)
    if hp then m.hp = m.hpMax end
    if mp then m.mp = m.mpMax end
    m.anchor, m.anchorAt = why, now
end
-- o = { combat, class, spirit, eatHp (per s), drinkMp (per s) }; the time since the last tick, at most 10 minutes
OV.hmTick = function(m, now, o)
    local dt = math.min(600, now - (m.at or now))
    m.at = now
    if dt <= 0 then return end
    if o.combat then m.quietFrom = now
    else
        local r = OV.HM_REGEN[o.class] or { 0.10, 6 }
        m.hp = math.min(m.hpMax, m.hp + (r[1] * (o.spirit or 0) + r[2]) / 2 * OV.HM_SAFE * dt)
        if m.hp < m.hpMax and now - m.quietFrom >= OV.HM_QUIET then OV.hmFull(m, "quiet", now, true, false) end
    end
    if o.eatHp then m.hp = math.min(m.hpMax, m.hp + o.eatHp * dt) end
    local c = OV.HM_MANA[o.class]
    if m.mpMax > 0 then
        if c and (not m.castAt or now - m.castAt >= 5) then
            m.mp = math.min(m.mpMax, m.mp + (c[1] * (o.spirit or 0) + c[2]) / 2 * OV.HM_SAFE * dt)
        end
        if o.drinkMp then m.mp = math.min(m.mpMax, m.mp + o.drinkMp * dt) end
        if m.mp < m.mpMax and not o.combat and now - (m.castAt or m.anchorAt) >= OV.HM_QUIET and now - m.quietFrom >= OV.HM_QUIET then
            OV.hmFull(m, "quiet", now, false, true)
        end
    end
end
-- "Restores N health over S sec" / "... mana ..." -> per second (nil = not said)
OV.hmRate = function(text, what)
    if type(text) ~= "string" then return nil end
    local n, s = text:lower():match("([%d,]+) " .. what .. " over (%d+) sec")
    n = n and tonumber((n:gsub(",", ""))); s = tonumber(s)
    if n and s and s > 0 then return n / s end
    -- "Restores N health and M mana over S sec" (food and drink)
    local a, b, s2 = text:lower():match("([%d,]+) health and ([%d,]+) mana over (%d+) sec")
    s2 = tonumber(s2)
    if a and s2 and s2 > 0 then return tonumber(((what == "health") and a or b):gsub(",", "")) / s2 end
    return nil
end
-- SMARTY-MODEL-END
function OV.hmPlain(v)
    if type(issecretvalue) == "function" then local ok, q = pcall(issecretvalue, v); if not ok or q then return nil end end
    if type(WC.isPlain) == "function" and not WC.isPlain(v) then return nil end
    if type(v) == "number" then return v end
    return nil
end
function OV.hmDB()
    if type(_G.WowCompanionSpellsDB) ~= "table" then _G.WowCompanionSpellsDB = {} end
    local db = _G.WowCompanionSpellsDB
    if type(db.smartModel) ~= "table" then db.smartModel = {} end
    return db.smartModel
end
function OV.hmStart()
    local P = OV.hmPlain
    local hx = P(T(function() return UnitHealthMax("player") end)) or 0
    local mx = P(T(function() return UnitPowerMax("player", 0) end)) or 0
    if hx <= 0 then return nil end
    local now = GetTime()
    local m = OV.hmNew(hx, mx, now)
    -- kept from before a /reload (at most 10 minutes old): the estimate goes on from there
    local k, kept = whoKey(), nil
    if k then kept = OV.hmDB()[k] end
    local st = T(function() return GetServerTime() end)
    if type(kept) == "table" and type(kept.t) == "number" and st and st - kept.t <= 600 then
        m.hp = math.min(hx, kept.hp or hx); m.mp = math.min(mx, kept.mp or mx); m.anchor, m.anchorAt = "kept", now
    end
    local okc, _, file = pcall(UnitClass, "player")
    m.class = okc and type(file) == "string" and file or nil
    return m
end
function OV.hmSpirit()
    local ok, _, eff = pcall(UnitStat, "player", 5)
    return ok and OV.hmPlain(eff) or nil
end
-- the eating / drinking auras now: { food = { endAt, rate }, drink = { ... } } by the game's Food / Drink names (plus "Refreshment")
function OV.hmAuras()
    local fname = OV.sbName and OV.sbName(OV.SMART_FOOD) or "Food"
    local dname = OV.sbName and OV.sbName(OV.SMART_DRINK) or "Drink"
    local out = {}
    for i = 1, 40 do
        local a = T(function() return C_UnitAuras.GetAuraDataByIndex("player", i, "HELPFUL") end)
        if type(a) ~= "table" then break end
        local nm = T(function() return a.name end)
        if type(nm) == "string" then
            local e = OV.hmPlain(T(function() return a.expirationTime end))
            local both = nm:find("Refreshment") ~= nil
            if nm == fname or both or nm:find("Food") then out.food = { endAt = e } end
            if nm == dname or both or nm:find("Drink") then out.drink = { endAt = e } end
        end
    end
    return out
end
-- one second's work: regen, eating / drinking, the anchors; Smart Y told when the 80 % line is crossed
function OV.hmStep()
    local m = OV.HM
    if not m then OV.HM = OV.hmStart(); m = OV.HM; if not m then return end end
    local now = GetTime()
    local au = OV.hmAuras()
    -- an aura that ended: ran its whole time with no rate known -> full
    for _, k in ipairs({ "food", "drink" }) do
        local was = OV.hmEat and OV.hmEat[k]
        if was and not au[k] then
            if not was.rate and was.endAt and now >= was.endAt - 1.5 then OV.hmFull(m, k, now, k == "food", k == "drink") end
        end
        if au[k] then
            local id = OV.smartEat and OV.smartEat[k]                     -- m128: this kind's own item (Water above Food: the drink's)
            au[k].rate = (was and was.rate) or (id and OV.hmItemRate(id, k == "food" and "health" or "mana"))
        end
    end
    OV.hmEat = au
    OV.hmTick(m, now, { combat = inCombat(), class = m.class, spirit = OV.hmSpirit(),
                        eatHp = au.food and au.food.rate or nil, drinkMp = au.drink and au.drink.rate or nil })
    -- kept for a /reload, every 10 s
    if not OV.hmSavedAt or now - OV.hmSavedAt >= 10 then
        OV.hmSavedAt = now
        local k, st = whoKey(), T(function() return GetServerTime() end)
        if k and st then OV.hmDB()[k] = { hp = math.floor(m.hp), mp = math.floor(m.mp), t = st } end
    end
    pcall(OV.nmCheck)                                                   -- m71: the spells out of mana, once a second
    local low = (m.hp < m.hpMax * 0.8) or (m.mpMax > 0 and m.mp < m.mpMax * 0.8)
    if low ~= OV.hmLow then OV.hmLow = low; C_Timer.After(0.1, function() pcall(OV.smartApply) end) end
end
function OV.hmItemRate(id, what)
    local tip = T(function() return C_TooltipInfo and C_TooltipInfo.GetItemByID and C_TooltipInfo.GetItemByID(id) end)
    if type(tip) ~= "table" or type(tip.lines) ~= "table" then return nil end
    for _, ln in ipairs(tip.lines) do
        local r = OV.hmRate(type(ln) == "table" and ln.leftText or nil, what)
        if r then return r end
    end
    return nil
end
OV.hmF = CreateFrame("Frame")
for _, e in ipairs({ "PLAYER_DEAD", "PLAYER_ALIVE", "PLAYER_UNGHOST", "UNIT_MAXHEALTH", "UNIT_MAXPOWER", "PLAYER_ENTERING_WORLD" }) do
    pcall(function() OV.hmF:RegisterEvent(e) end)
end
pcall(function() OV.hmF:RegisterUnitEvent("UNIT_COMBAT", "player") end)
pcall(function() OV.hmF:RegisterUnitEvent("UNIT_SPELLCAST_SUCCEEDED", "player") end)
-- m72: every event's work under pcall (an error here would show the game's Lua error window)
OV.hmF:SetScript("OnEvent", function(_, e, a1, a2, a3, a4) pcall(OV.hmOnEvent, e, a1, a2, a3, a4) end)
OV.HIT_SHARE = 0.06                                                    -- m89: a hidden hit = 6 % of the max health
function OV.hmOnEvent(e, a1, a2, a3, a4)
    local m = OV.HM
    if e == "PLAYER_ENTERING_WORLD" then
        if not OV.hmTicker and C_Timer.NewTicker then OV.hmTicker = C_Timer.NewTicker(1, function() pcall(OV.hmStep) end) end
        return
    end
    if not m then return end
    local now = GetTime()
    if e == "UNIT_COMBAT" then
        if a1 ~= "player" then return end
        -- m88 (the user: Smart Y's health from damage taken "worked", the Thor's log now says "wounds 0"): every UNIT_COMBAT counted,
        -- and how many came with a hidden amount, in the why line (uc), so one read says whether the game still sends plain amounts
        OV.ucN = (OV.ucN or 0) + 1
        if a2 == "WOUND" then OV.fightHits = (OV.fightHits or 0) + 1 end            -- m90: hits in this fight
        if OV.hmPlain(a4) == nil then OV.ucSecret = (OV.ucSecret or 0) + 1 end
        local amt = OV.hmPlain(a4)
        -- m89 (the user's ToS-safe pick 2026-11-05, "Smart Y food by hits taken"): since the game's 70790 update every hit's amount is
        -- hidden; that a hit landed is still plain, so a WOUND with no amount counts as HIT_SHARE of the max health (a rule, not a read)
        if not amt and a2 == "WOUND" and m.hpMax > 0 then amt = math.floor(m.hpMax * OV.HIT_SHARE + 0.5); OV.ucGuess = (OV.ucGuess or 0) + 1 end
        if not amt or type(a2) ~= "string" then return end
        if a2 == "WOUND" then OV.hmWound(m, amt, now)
        elseif a2 == "HEAL" then OV.hmHeal(m, amt)
        elseif a2 == "ENERGIZE" and m.mpMax > 0 then OV.hmGain(m, amt) end
        pcall(OV.hmStep)
    elseif e == "UNIT_SPELLCAST_SUCCEEDED" then
        local costs = T(function() return C_Spell.GetSpellPowerCost(a3) end)
        if type(costs) == "table" then
            for _, c in ipairs(costs) do
                local ty, cost = T(function() return c.type end), OV.hmPlain(T(function() return c.cost end))
                if ty == 0 and cost then OV.hmCast(m, cost, now) end
            end
        end
    elseif e == "PLAYER_DEAD" then m.hp, m.mp = 0, 0; m.anchor, m.anchorAt = "dead", now
    elseif e == "PLAYER_ALIVE" or e == "PLAYER_UNGHOST" then
        m.hp, m.mp = math.floor(m.hpMax * 0.5), math.floor(m.mpMax * 0.5); m.anchor, m.anchorAt = "revived", now; m.quietFrom = now
    elseif e == "UNIT_MAXHEALTH" or e == "UNIT_MAXPOWER" then
        if a1 ~= "player" then return end
        OV.hmMax(m, OV.hmPlain(T(function() return UnitHealthMax("player") end)), OV.hmPlain(T(function() return UnitPowerMax("player", 0) end)))
    end
end
-- m73 (tracking: Interact's HUD resource bar fades out of combat at full and may use the model): Smart Y's health / mana for any add-on of
-- ours - the first plain reader, else m70's model - as { hp, hpMax, hpSrc ("unit" / "pct" / "frame" / "model"), mp, mpMax, mpSrc }
-- (nil fields = unknown; mpSrc "none" = no mana pool). Read only, cheap enough for a few calls a second.
WC.vitals = function()
    local ok, v = pcall(OV.smartVitals)
    if not ok or type(v) ~= "table" then return nil end
    return { hp = v.hp, hpMax = v.hpMax, hpSrc = v.hpSrc, mp = v.mp, mpMax = v.mpMax, mpSrc = v.mpSrc }
end
-- m74 QUEST ITEM ON Y: the target (an NPC by the npc id in its GUID; else the soft-interact object by its object id) matched against the
-- active quests that carry a usable item (Quests' WC.questItemTargets: QuestieDB's objective ids, or the objectives' own words naming the
-- target); the focused quest first. Armed on Y's router button OUT of combat only (a secure button cannot change in a fight): a target
-- that first matches in a fight is not armed until it ends; what was armed stays through the fight - also for another target of the
-- fight (its use then fails, "Invalid target", and Y does nothing for that press). Read only; one attribute pair of our own.
-- QI-MATCH-BEGIN (pure: tools/test_smarty.py) cands = WC.questItemTargets(); t = { npc, obj, name (lower) }; -> the entry, "target" / "soft"
OV.qiMatch = function(cands, t)
    local best, how
    for _, e in ipairs(cands or {}) do
        local hit
        if t.npc and e.npc and e.npc[t.npc] then hit = "target"
        elseif t.obj and e.obj and e.obj[t.obj] then hit = "soft"
        elseif t.name and t.name ~= "" then
            -- the whole name, else its stem without the last letter ("infected wolf" in "infected wolves"; 5 letters or more)
            local stem = #t.name >= 5 and t.name:sub(1, -2) or nil
            for _, w in ipairs(e.words or {}) do
                if w:find(t.name, 1, true) or (stem and w:find(stem, 1, true)) then hit = t.npc and "target" or (t.obj and "soft" or "target"); break end
            end
        end
        if hit and (not best or (e.focus and not best.focus)) then best, how = e, hit end
    end
    return best, how
end
-- QI-MATCH-END
function OV.qiTarget()
    local function guidId(unit)
        local g = T(function() return UnitGUID(unit) end)
        if type(g) ~= "string" or (type(WC.isPlain) == "function" and not WC.isPlain(g)) then return nil end
        local kind, id = g:match("^(%a+)%-%d+%-%d+%-%d+%-%d+%-(%d+)%-")
        return kind, tonumber(id)
    end
    local t = {}
    local k, id = guidId("target")
    if k == "Creature" or k == "Vehicle" then
        t.npc = id
        local n = T(function() return UnitName("target") end)
        if type(n) == "string" and (type(WC.isPlain) ~= "function" or WC.isPlain(n)) then t.name = n:lower() end
    end
    local sk, sid = guidId("softinteract")
    if sk == "GameObject" then t.obj = sid end
    return t
end
function OV.qiApply()
    if inCombat() then return end
    local ok, cands = pcall(function() return type(WC.questItemTargets) == "function" and WC.questItemTargets() or {} end)
    local t = OV.qiTarget()
    local e, how = nil, nil
    if ok and (t.npc or t.obj) then e, how = OV.qiMatch(cands, t) end
    if e and not OV.smartOn() then e = nil end                             -- m76: the quest item is Smart Y's too
    if e then
        local cnt = T(function() return (C_Item and C_Item.GetItemCount or GetItemCount)(e.item) end)
        if type(cnt) == "number" and cnt <= 0 then e = nil end
    end
    local val = e and ("item:" .. e.item) or nil
    local unit = e and how or nil
    local b = face.buttons[6]
    if b then pcall(b.SetAttribute, b, "wc-qitem", val); pcall(b.SetAttribute, b, "wc-qitem-u", unit) end
    if val ~= OV.qiVal or unit ~= OV.qiUnit then
        OV.qiVal, OV.qiUnit = val, unit
        if e then
            e.name = T(function() return (C_Item and C_Item.GetItemNameByID and C_Item.GetItemNameByID(e.item)) or GetItemInfo(e.item) end)
        end
        OV.qiNow = e
        pcall(function() WC.send(WC.encode({ type = "cap", padmap = PV,
            smarty = e and ("qitem " .. e.item .. " on " .. tostring(how == "soft" and ("obj " .. tostring(t.obj)) or t.npc) .. " for q" .. e.q) or "qitem off" })) end)
        pcall(emit, true); WC.push()
    end
end
OV.qiF = CreateFrame("Frame")
for _, e in ipairs({ "PLAYER_TARGET_CHANGED", "PLAYER_SOFT_INTERACT_CHANGED", "QUEST_LOG_UPDATE", "BAG_UPDATE_DELAYED",
                     "PLAYER_REGEN_ENABLED", "PLAYER_ENTERING_WORLD", "SUPER_TRACKING_CHANGED" }) do
    pcall(function() OV.qiF:RegisterEvent(e) end)
end
OV.qiF:SetScript("OnEvent", function()
    if OV.qiSoon then return end
    OV.qiSoon = true
    C_Timer.After(0.15, function() OV.qiSoon = nil; pcall(OV.qiApply) end)
end)
-- m71 NOT ENOUGH MANA, MARKED (the user: "Now that we have an Idea of my mana, can we better track when I am too low on mama for spells?",
-- then "Mark spells"). The app's own spell pictures (the spell wheel, the Spellbook, the class radials, Smart buffs' rows) get the
-- game's blue out-of-mana look; the bars already have it (the game's own IsUsableAction notEnoughMana, plain on this client). Here:
-- every spell those surfaces show - the book's spells, Smart buffs' list - checked once a second and on SPELL_UPDATE_USABLE:
-- C_Spell.IsSpellUsable's noMana when it is plain (the game's own answer), else m70's mana (plain or modelled) against the spell's
-- mana cost (C_Spell.GetSpellPowerCost). ONE `nomana` document { ids, src } only when the set changes. Read only.
function OV.nmBool(v)
    if type(issecretvalue) == "function" then local ok, q = pcall(issecretvalue, v); if not ok or q then return nil end end
    if type(WC.isPlain) == "function" and not WC.isPlain(v) then return nil end
    if type(v) == "boolean" then return v end
    return nil
end
function OV.nmCost(id)
    local costs = T(function() return C_Spell.GetSpellPowerCost(id) end)
    if type(costs) ~= "table" then return nil end
    for _, c in ipairs(costs) do
        local ty, cost = T(function() return c.type end), OV.hmPlain(T(function() return c.cost end))
        if ty == 0 and cost then return cost end
    end
    return nil
end
function OV.nmCheck()
    local ids, seen = {}, {}
    local ok, list = pcall(function() return type(WC.spellsBindableList) == "function" and WC.spellsBindableList() or {} end)
    for _, e in ipairs(ok and type(list) == "table" and list or {}) do if e.id and not seen[e.id] then seen[e.id] = true; ids[#ids + 1] = e.id end end
    for _, e in ipairs(OV.sbList or {}) do if e.id and not seen[e.id] then seen[e.id] = true; ids[#ids + 1] = e.id end end
    local v = OV.smartVitals()
    local mana = (v.mpSrc and v.mpSrc ~= "none") and v.mp or nil
    local usable = (C_Spell and C_Spell.IsSpellUsable) or _G.IsUsableSpell
    local out, game, model = {}, false, false
    for _, id in ipairs(ids) do
        local nm
        if usable then
            local okU, _, noMana = pcall(usable, id)
            if okU then nm = OV.nmBool(noMana) end
        end
        if nm ~= nil then
            game = true
            if nm then out[#out + 1] = id end
        elseif mana then
            local cost = OV.nmCost(id)
            if cost and cost > mana then out[#out + 1] = id; model = true end
        end
    end
    table.sort(out)
    local sig = table.concat(out, ",")
    if sig == OV.nmSig then return end
    OV.nmSig = sig
    WC.send(WC.encode({ type = "nomana", v = PV, ids = out, src = game and (model and "mixed" or "game") or (model and "model" or "none") }))
    WC.push()
end
OV.nmF = CreateFrame("Frame")
for _, e in ipairs({ "SPELL_UPDATE_USABLE", "ACTIONBAR_UPDATE_USABLE", "SPELLS_CHANGED" }) do pcall(function() OV.nmF:RegisterEvent(e) end) end
OV.nmF:SetScript("OnEvent", function()
    if OV.nmSoon then return end
    OV.nmSoon = true
    C_Timer.After(0.3, function() OV.nmSoon = nil; pcall(OV.nmCheck) end)
end)
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

-- ---------------------------------------------------------------- m77 PARTY MODE (the lead's contract PARTY_BUILD.md; the ADD-ON half)
-- The user's rulings (party_mode/SPEC_DRAFT.md, v10 / v13 / v17 / v18; FEASIBILITY.md "DESIGN CHANGES FORCED"): Select toggles Party
-- mode; while it is on every press of the 8 router keys goes to OUR secure button WowCompanionPartyPad (the router's PRE, room mode
-- first): the D-pad moves the box over the party (you first, then party1..4), A / X and the ZL / ZR chords cast the slot's spell ON the
-- boxed member (CastSpellByName(spell, unit): the game's target never changes), Y opens the member's player menu (the app's mirror) or
-- casts its in-combat spell / Smart Y's party buff, B turns Party mode off. No trace: our own secure buttons, override bindings on our
-- own owner (the router's header), data only in WowCompanionSpellsDB.partyBy[name-realm]; nothing of Blizzard's window code is run.
-- One main-chunk local (the chunk is at Lua's 200): everything lives in partyMode()'s own scope and on OV.pm.
local function partyMode()
    local PM = { n = 0, rs = 0 }
    OV.pm = PM
    -- F.ROUTER's order (Interact.lua): the 8 router buttons by position; the pad's frame refs wc-r-1..8 follow it
    PM.ORDER = { 8, 7, 5, 6, 1, 2, 3, 4 }
    PM.KEYNAME = { [1] = "PADDLEFT", [2] = "PADDUP", [3] = "PADDRIGHT", [4] = "PADDDOWN", [5] = "PAD3", [6] = "PAD4", [7] = "PAD2", [8] = "PAD1" }
    PM.PAD, PM.TOGGLE, PM.ON, PM.OFF, PM.MDONE = "WowCompanionPartyPad", "WowCompanionPartyToggle", "WowCompanionPartyOn",
        "WowCompanionPartyOff", "WowCompanionPartyMenuDone"
    PM.CNT = "WowCompanionPartyCount"                                            -- m129: the roster's driver
    PM.pickW, PM.pickSent = {}, {}                                                -- m130: the wrapped pick cells, their last sent press
    PM.U = { [0] = "player", "party1", "party2", "party3", "party4" }
    PM.PET_COL = 5                                                               -- m131: the pet's pick cell (party col 5; i 5 in the docs)
    PM.U[PM.PET_COL] = "pet"
    -- THE ONE SWITCH (THE USER'S RULINGS 2026-10-28: Party mode turns OFF when you leave Home - a room, a Home module full screen -
    -- and ANYTHING that takes the controller ends it: the pause wheel, the bar cursor, a game window such as a vendor or loot).
    -- true = OUT OF COMBAT, whenever the router steps aside for a capture holder or a game window, the add-on turns Party mode off
    -- (PM.set(nil): the 8 router buttons and the pad lose wc-party; the party document says on false; the router binds today's plan
    -- when the taker lets go). false = suspended only (`aside`).
    -- m132 (the user, 2026-11-11, a healer: "Party mode needs to be usable in combat for healers. Locking it is retarded"): NEVER IN A
    -- FIGHT. A fight's start carries every key and Select (no game window steps the router aside then, routerBind), nothing taken in
    -- the fight is noted and nothing turns it off at its end (m77's fight "taker" route is gone, the app's off taps too): a room that
    -- holds in a fight only SUSPENDS it (room mode first, PRE). And a PROMPT (a loot roll, a static popup, a ready check) only suspends
    -- it out of combat as well: the router steps aside so A answers it, and Party mode is back when it closes.
    PM.OFF_WHEN_ASIDE = true
    PM.PROMPTS = { GroupLootFrame1 = true, StaticPopup1 = true, StaticPopup2 = true, StaticPopup3 = true, StaticPopup4 = true,
                   ReadyCheckFrame = true }
    -- a taker (out of combat): a capture holder, or a game window that is not one of PM.PROMPTS
    function PM.takerNow()
        local ok, st = pcall(function() return WC.capture.state() end)
        local h = ok and type(st) == "table" and st.holder or nil
        return (type(h) == "string" and h ~= "") or windowOpen(PM.PROMPTS) ~= nil
    end

    -- PARTY-NAV-BEGIN (pure; tools/test_party.py checks it against its NAV table and against the PARTYPAD snippet's copy)
    -- m131 ADAPTIVE ROWS (the user, 2026-11-11: "when one two or three full sized, when 4, bottom 2 consolidate, when 5 the next 2
    -- consolidate"): the n entries (you, your pet while one exists, party1..4) stand in min(n, 3) rows; the bottom max(0, n - 3) rows
    -- hold two cells each, the rows above one full-width cell. So the first f entries are full rows (f = n up to 3, else 6 - n) and the
    -- rest come in pairs, left then right. Up / Down go a row (from a shared row into a full one: that cell; from a full row into a
    -- shared one: its LEFT cell; shared to shared: the same side), Left / Right only inside a shared row; no wrap. The same body runs in
    -- the PARTYPAD snippet (no closures there) and in the app's PartyModel.nav.
    -- n entries, box = the picked index (1 = you), dir = "up" / "down" / "left" / "right"; -> the new index (= box: no move)
    OV.partyNav = function(n, box, dir)
        local nb = box
        local f = n
        if n > 3 then f = 6 - n end
        if box <= f then
            if dir == "up" and box > 1 then nb = box - 1
            elseif dir == "down" and box < n then nb = box + 1 end
        else
            local right = (box - f) % 2 == 0
            if dir == "left" and right then nb = box - 1
            elseif dir == "right" and not right then nb = box + 1
            elseif dir == "up" then
                if box - 2 > f then nb = box - 2 elseif f >= 1 then nb = f end
            elseif dir == "down" and box + 2 <= n then nb = box + 2 end
        end
        return nb
    end
    -- PARTY-NAV-END

    -- THE PARTY PAD (restricted Lua; the router's /click delivers the key's name as the button, "1" / "0" as the edge). The key-down only:
    -- the key-up changes nothing, so whichever edge the secure template acts on, it acts ONCE.
    -- m131: the entries are you, the pet ([@pet,exists]) and party1..4; every snippet walks them in that order (box -> wc-u)
    PM.PARTYPAD = [=[
    if not down then return end
    self:SetAttribute("type", nil)
    self:SetAttribute("spell", nil)
    self:SetAttribute("unit", nil)
    self:SetAttribute("wc-k", "none")
    local pos = self:GetAttribute("wc-kp-" .. (button or "")) or 0
    self:SetAttribute("wc-key", self:GetAttribute("wc-kn-" .. pos))
    local lt, rt = false, false
    local st = GetGamePadState and GetGamePadState()
    local bt = st and st.buttons
    if bt then
        local li, ri = self:GetAttribute("wc-lt"), self:GetAttribute("wc-rt")
        lt = (li and bt[li]) and true or false
        rt = (ri and bt[ri]) and true or false
    end
    local ly = ""
    if lt and not rt then ly = "zl" elseif rt and not lt then ly = "zr" end
    self:SetAttribute("wc-ly", ly)
    local pet = SecureCmdOptionParse("[@pet,exists] 1") == "1"
    local n = 1
    if pet then n = n + 1 end
    for i = 1, 4 do
        if SecureCmdOptionParse("[@party" .. i .. ",exists] 1") == "1" then n = n + 1 end
    end
    local box = self:GetAttribute("wc-box") or 1
    if box > n then box = n end
    if box < 1 then box = 1 end
    local menu = self:GetAttribute("wc-pmenu")
    local kind = nil
    if pos >= 1 and pos <= 4 then
        if menu then
            kind = "key"
        else
            local dir = (pos == 1 and "left") or (pos == 2 and "up") or (pos == 3 and "right") or "down"
            -- PARTY-NAV-BODY-BEGIN
        local nb = box
        local f = n
        if n > 3 then f = 6 - n end
        if box <= f then
            if dir == "up" and box > 1 then nb = box - 1
            elseif dir == "down" and box < n then nb = box + 1 end
        else
            local right = (box - f) % 2 == 0
            if dir == "left" and right then nb = box - 1
            elseif dir == "right" and not right then nb = box + 1
            elseif dir == "up" then
                if box - 2 > f then nb = box - 2 elseif f >= 1 then nb = f end
            elseif dir == "down" and box + 2 <= n then nb = box + 2 end
        end
            -- PARTY-NAV-BODY-END
            box = nb
            kind = "move"
        end
    end
    self:SetAttribute("wc-box", box)
    self:SetAttribute("wc-cnt", n)
    local u, c = "player", 1
    if pet then
        c = c + 1
        if c == box then u = "pet" end
    end
    for i = 1, 4 do
        if SecureCmdOptionParse("[@party" .. i .. ",exists] 1") == "1" then
            c = c + 1
            if c == box then u = "party" .. i end
        end
    end
    self:SetAttribute("wc-u", u)
    if kind then
        self:SetAttribute("wc-k", kind)
        return
    end
    if pos == 7 then
        if menu then self:SetAttribute("wc-k", "key") return end
        local rn = self:GetAttribute("wc-rn") or 0
        for i = 1, rn do
            local r = self:GetFrameRef("wc-r-" .. i)
            if r then r:SetAttribute("wc-party", nil) end
        end
        self:SetAttribute("wc-party", nil)
        self:SetAttribute("wc-k", "off")
        return
    end
    local b = (pos == 8 and "a") or (pos == 5 and "x") or (pos == 6 and "y") or nil
    if not b then return end
    if lt and rt then return end
    if menu then self:SetAttribute("wc-k", "key") return end
    local spell = nil
    if b == "y" and ly == "" then
        if SecureCmdOptionParse("[combat] 1") == "1" then
            -- m132: in a fight Y never opens the player menu (its sub-state would hold every press until MENU DONE): the In combat
            -- action, else Smart Y's party buff armed for this member before the fight, else Target
            local py = self:GetAttribute("wc-py")
            if not py then py = self:GetAttribute("wc-pb-" .. u) end
            if py and SecureCmdOptionParse("[@" .. u .. ",help,nodead] 1") == "1" then spell = py end
            if not spell then
                self:SetAttribute("type", "target")
                self:SetAttribute("unit", u)
                self:SetAttribute("wc-u", u)
                self:SetAttribute("wc-k", "target")
                return
            end
        else
            local pb = self:GetAttribute("wc-pb-" .. u)
            if pb and SecureCmdOptionParse("[@" .. u .. ",nodead] 1") == "1" then spell = pb end
        end
        if not spell then
            -- m131: the pet has no player menu of ours (the game's own pet menu is not mirrored): nothing happens
            if u == "pet" then return end
            self:SetAttribute("wc-pmenu", true)
            self:SetAttribute("wc-u", u)
            self:SetAttribute("wc-k", "menu")
            return
        end
    else
        spell = self:GetAttribute("wc-ps-" .. ly .. b)
    end
    self:SetAttribute("wc-u", u)
    if spell then
        self:SetAttribute("type", "spell")
        self:SetAttribute("spell", spell)
        self:SetAttribute("unit", u)
        self:SetAttribute("wc-k", "cast")
    end
]=]
    -- Select's toggle and the party row's ON / OFF cells: "wc-party" on the 8 router buttons and on the pad (toggle reads the PAD's)
    PM.PARTYFLIP = [=[
    local set = self:GetAttribute("wc-set")
    local pad = self:GetFrameRef("wc-pad")
    local v = nil
    if set == "on" then v = true
    elseif set == "toggle" then
        if not (pad and pad:GetAttribute("wc-party")) then v = true end
    end
    local rn = self:GetAttribute("wc-rn") or 0
    for i = 1, rn do
        local r = self:GetFrameRef("wc-r-" .. i)
        if r then r:SetAttribute("wc-party", v) end
    end
    if pad then
        pad:SetAttribute("wc-party", v)
        pad:SetAttribute("wc-pmenu", nil)
        pad:SetAttribute("wc-k", v and "on" or "off")
        if v then
            local pet = SecureCmdOptionParse("[@pet,exists] 1") == "1"
            local n, u, c = 1, "player", 1
            if pet then n = n + 1 end
            for i = 1, 4 do
                if SecureCmdOptionParse("[@party" .. i .. ",exists] 1") == "1" then n = n + 1 end
            end
            local box = pad:GetAttribute("wc-box") or 1
            if box > n then box = n end
            if box < 1 then box = 1 end
            if pet then
                c = c + 1
                if c == box then u = "pet" end
            end
            for i = 1, 4 do
                if SecureCmdOptionParse("[@party" .. i .. ",exists] 1") == "1" then
                    c = c + 1
                    if c == box then u = "party" .. i end
                end
            end
            pad:SetAttribute("wc-box", box)
            pad:SetAttribute("wc-cnt", n)
            pad:SetAttribute("wc-u", u)
        end
    end
]=]
    -- m129 ONE PICK (party_design.md A.2): the roster changed (a state driver, in fights too): the pick re-clamped over the members
    -- there now and wc-u set from it, so the bottom's box and the top's ring never point at two people
    -- m131: a second driver on the pet (out / gone); the picked unit is KEPT while it is still there (its index moves with the pet),
    -- else the box clamps as before
    PM.CNT_DRIVER = "[@party4,exists]5;[@party3,exists]4;[@party2,exists]3;[@party1,exists]2;1"
    PM.PET_DRIVER = "[@pet,exists]1;0"
    PM.PARTYCNT = [=[
    local pad = self:GetFrameRef("wc-pad")
    if not pad then return end
    local pet = SecureCmdOptionParse("[@pet,exists] 1") == "1"
    local was = pad:GetAttribute("wc-u")
    local n, keep = 1, nil
    if was == "player" then keep = 1 end
    if pet then
        n = n + 1
        if was == "pet" then keep = n end
    end
    for i = 1, 4 do
        if SecureCmdOptionParse("[@party" .. i .. ",exists] 1") == "1" then
            n = n + 1
            if was == "party" .. i then keep = n end
        end
    end
    local box = keep or pad:GetAttribute("wc-box") or 1
    if box > n then box = n end
    if box < 1 then box = 1 end
    local u, c = "player", 1
    if pet then
        c = c + 1
        if c == box then u = "pet" end
    end
    for i = 1, 4 do
        if SecureCmdOptionParse("[@party" .. i .. ",exists] 1") == "1" then
            c = c + 1
            if c == box then u = "party" .. i end
        end
    end
    pad:SetAttribute("wc-box", box)
    pad:SetAttribute("wc-cnt", n)
    pad:SetAttribute("wc-u", u)
]=]
    -- m129 A PLATE'S TAP (party_design.md C, Touch): Party mode off = Target the member; the picked member = the Touch action (Target,
    -- or the Touch spell); another member = the pick moves there (wc-box + wc-u on the pad through its frame ref, the write PARTYFLIP
    -- already makes), nothing cast. Decided here, so a late document never turns a pick into a Target or the other way round.
    -- m130 (review, 2026-11-11): the snippet sits on the party row's OWN cells 0-4 (a wrap), never behind a "click" relay: a relayed
    -- Click(button) may come without its down flag, and with ActionButtonUseKeyDown on the delegate's own Target / spell then fired on
    -- neither edge; the cell's own type target / spell is the path the old cells proved. And ONE DECISION A PRESS: the cell hears its
    -- down AND its up (and on this device sometimes two downs, pad5), so the first edge decides and keeps it (wc-pdec), the later edges
    -- re-use it, the up clears it. Without that the down moved the pick and the up saw it picked and cast the Touch action.
    -- m131: the pet's own cell (party col 5, wc-pu "pet") in the same walk: you, the pet, party1..4
    PM.PARTYPICK = [=[
    if self:GetAttribute("wc-pdec") then
        if not down then self:SetAttribute("wc-pdec", nil) end
        return
    end
    self:SetAttribute("type", nil)
    self:SetAttribute("spell", nil)
    self:SetAttribute("unit", nil)
    local pad = self:GetFrameRef("wc-pad")
    local u = self:GetAttribute("wc-pu")
    if not (pad and u) then return end
    local k = "none"
    if not pad:GetAttribute("wc-party") then
        self:SetAttribute("type", "target")
        self:SetAttribute("unit", u)
        k = "target"
    else
        local n, idx = 1, nil
        if u == "player" then idx = 1 end
        if SecureCmdOptionParse("[@pet,exists] 1") == "1" then
            n = n + 1
            if u == "pet" then idx = n end
        end
        for i = 1, 4 do
            if SecureCmdOptionParse("[@party" .. i .. ",exists] 1") == "1" then
                n = n + 1
                if u == "party" .. i then idx = n end
            end
        end
        if idx and pad:GetAttribute("wc-box") == idx and pad:GetAttribute("wc-u") == u then
            local t = self:GetAttribute("wc-touch")
            if t then
                self:SetAttribute("type", "spell")
                self:SetAttribute("spell", t)
            else
                self:SetAttribute("type", "target")
            end
            self:SetAttribute("unit", u)
            k = "touch"
        elseif idx then
            pad:SetAttribute("wc-box", idx)
            pad:SetAttribute("wc-cnt", n)
            pad:SetAttribute("wc-u", u)
            pad:SetAttribute("wc-k", "pick")
            k = "pick"
        end
    end
    self:SetAttribute("wc-pk", k)
    self:SetAttribute("wc-pseq", (self:GetAttribute("wc-pseq") or 0) + 1)
    if down then self:SetAttribute("wc-pdec", k) end
]=]
    PM.MENUDONE = [=[ local pad = self:GetFrameRef("wc-pad"); if pad then pad:SetAttribute("wc-pmenu", nil) end ]=]

    local function A(f, k) return f and T(function() return f:GetAttribute(k) end) or nil end
    PM.A = A
    function PM.pad() return PM.f and PM.f.pad or nil end
    function PM.on() return A(PM.pad(), "wc-party") == true end
    -- m100 (the user, 2026-11-07: "Make the party mode an experimental toggle that is off by default"): the app's QOL > Experimental
    -- switch, through Hub's "uisw" 16 / 17 (WC.partyOk); absent = not allowed
    function PM.allowed() return WC.partyOk == true end
    WC.partyAllow = function(v)
        if v or not PM.on() or inCombat() then return end
        if PM.set(nil) then pcall(PM.flipped) end
    end

    -- the frames: made once, out of combat (a load inside a fight makes them at its end), never destroyed
    function PM.make()
        if PM.f then return true end
        if inCombat() then PM.makePending = true return false end
        local hdr = routerHeader()
        if not hdr then return false end
        local ok = pcall(function()
            local f = {}
            f.pad = CreateFrame("Button", PM.PAD, UIParent, "SecureActionButtonTemplate")
            f.pad:RegisterForClicks("AnyDown", "AnyUp")
            f.pad:SetAttribute("wc-box", 1)
            SecureHandlerWrapScript(f.pad, "OnClick", hdr, PM.PARTYPAD)
            f.pad:HookScript("PostClick", function(_, button, down) pcall(PM.afterPad, button, down) end)
            for _, x in ipairs({ { "toggle", PM.TOGGLE, false }, { "on", PM.ON, true }, { "off", PM.OFF, true } }) do
                local b = CreateFrame("Button", x[2], UIParent, "SecureActionButtonTemplate")
                -- Select's key: its down only (a toggle must not run twice); the cells' clicks (type "click"): both edges, the snippet is
                -- absolute there (a repeat is harmless)
                if x[3] then b:RegisterForClicks("AnyDown", "AnyUp") else b:RegisterForClicks("AnyDown") end
                b:SetAttribute("wc-set", x[1])
                SecureHandlerWrapScript(b, "OnClick", hdr, PM.PARTYFLIP)
                local set = x[1]
                b:HookScript("PostClick", function(_, _, down) pcall(PM.afterFlip, set, down) end)
                f[x[1]] = b
            end
            f.mdone = CreateFrame("Button", PM.MDONE, UIParent, "SecureActionButtonTemplate")
            f.mdone:RegisterForClicks("AnyDown", "AnyUp")
            SecureHandlerWrapScript(f.mdone, "OnClick", hdr, PM.MENUDONE)
            -- m129: the roster's driver (m130: the plates' picks are the party row's own cells, PM.pickCell)
            f.cnt = CreateFrame("Frame", PM.CNT, UIParent, "SecureHandlerStateTemplate")
            f.cnt:SetAttribute("_onstate-cnt", PM.PARTYCNT)
            f.cnt:SetAttribute("_onstate-pet", PM.PARTYCNT)                     -- m131: the pet out / gone, in fights too
            PM.f = f
        end)
        PM.makePending = nil
        if ok and PM.f then pcall(PM.link) end
        if ok and PM.f and PM.row then pcall(PM.buildRow) end              -- m129: the pick cells need the pad's ref
        return ok and PM.f ~= nil
    end

    -- frame refs and the static attributes (out of combat; again whenever a router button is made: R.linkAll calls this)
    function PM.link()
        if inCombat() or not PM.f then return false end
        local f = PM.f
        local byPos = {}
        for _, x in ipairs(routerAll()) do byPos[x.pos] = x.b end
        local on = PM.on() or nil
        for _, h in ipairs({ f.pad, f.toggle, f.on, f.off, f.mdone }) do
            for i, pos in ipairs(PM.ORDER) do
                if byPos[pos] then pcall(SecureHandlerSetFrameRef, h, "wc-r-" .. i, byPos[pos]) end
            end
            h:SetAttribute("wc-rn", #PM.ORDER)
            pcall(SecureHandlerSetFrameRef, h, "wc-pad", f.pad)
        end
        -- m129: the picks (m130: the party row's cells 0-4) and the roster's driver write the pad through this ref
        PM.eachPick(PM.pickCell)
        if f.cnt then
            pcall(SecureHandlerSetFrameRef, f.cnt, "wc-pad", f.pad)
            if not PM.cntDriven then PM.cntDriven = pcall(RegisterStateDriver, f.cnt, "cnt", PM.CNT_DRIVER) end
            if not PM.petDriven then PM.petDriven = pcall(RegisterStateDriver, f.cnt, "pet", PM.PET_DRIVER) end
        end
        -- the key's name -> its position (the player's own keys first, the game's names as the fallback), and each position's own name
        for pos = 1, 8 do
            f.pad:SetAttribute("wc-kp-" .. PM.KEYNAME[pos], pos)
            f.pad:SetAttribute("wc-kn-" .. pos, PM.KEYNAME[pos])
        end
        for pos = 1, 8 do if R.keys[pos] then f.pad:SetAttribute("wc-kp-" .. R.keys[pos], pos) end end
        f.pad:SetAttribute("wc-lt", padIndex("PADLTRIGGER"))
        f.pad:SetAttribute("wc-rt", padIndex("PADRTRIGGER"))
        for _, b in pairs(byPos) do
            b:SetAttribute("wc-party-dest", PM.PAD)
            b:SetAttribute("wc-party", on)
        end
        return true
    end

    -- Party mode set from insecure code (out of combat only: PM.OFF_WHEN_ASIDE)
    function PM.set(v)
        if inCombat() or not PM.f then return false end
        v = v and true or nil
        for _, x in ipairs(routerAll()) do x.b:SetAttribute("wc-party", v) end
        PM.f.pad:SetAttribute("wc-party", v)
        PM.f.pad:SetAttribute("wc-pmenu", nil)
        return true
    end

    -- ---------------------------------------------------------------- partybox: every press Party mode takes, every on / off
    function PM.count()
        local n = PM.petHas() and 2 or 1                                -- m131: the pet counts
        for i = 1, 4 do if T(function() return UnitExists("party" .. i) end) == true then n = n + 1 end end
        return n
    end
    function PM.box()
        local pad = PM.pad()
        return {
            n = PM.n, on = PM.on(), box = A(pad, "wc-box") or 1, cnt = A(pad, "wc-cnt") or PM.count(), u = A(pad, "wc-u"),
            k = A(pad, "wc-k") or "none", key = A(pad, "wc-key"), ly = A(pad, "wc-ly") or "",
            sp = (A(pad, "type") == "spell") and A(pad, "spell") or nil, fight = fighting(), pm = A(pad, "wc-pmenu") == true,
        }
    end
    function PM.sendBox(kind)
        PM.n = PM.n + 1
        local b = PM.box()
        if kind then b.k = kind end
        WC.send(WC.encode({ type = "partybox", v = PV, b = b }))
        WC.push()
        return b
    end
    -- the pad's post-click (insecure, plain, in fights too): the press (down) only
    function PM.afterPad(button, down)
        if not down then return end
        local b = PM.sendBox()
        if b.k == "menu" and b.u and type(WC.pmenu) == "table" and type(WC.pmenu.read) == "function" then
            pcall(WC.pmenu.read, b.u, "party")
        end
        if b.k == "off" then PM.flipped() end
    end
    function PM.afterFlip(set, down)
        -- a cell's click may come as one edge or two: one partybox per flip (the toggle hears its down only)
        local now = GetTime()
        if PM.flipAt and now - PM.flipAt < 0.25 and PM.flipSet == set then return end
        PM.flipAt, PM.flipSet = now, set
        PM.sendBox()
        PM.flipped()
    end
    -- m129: a plate's tap went through its pick cell (m130: the cell itself): the box document says what it did (pick / touch / target; "none" = not in
    -- the group now)
    -- m130: once a press (the snippet counts its decisions in wc-pseq; the press's later edges re-use it and send nothing)
    function PM.afterPick(b)
        local q = A(b, "wc-pseq")
        if q == nil or PM.pickSent[b] == q then return end
        PM.pickSent[b] = q
        PM.sendBox(A(b, "wc-pk") or "none")
    end
    -- Party mode turned on or off: the bindings follow (out of combat), the documents say it
    function PM.flipped()
        if not inCombat() then pcall(routerBind, "party") end
        if PM.changed then pcall(PM.changed, "onoff") end
    end

    -- ---------------------------------------------------------------- the bindings (routerBind calls this, out of combat / before the lockdown)
    -- Select = the key of the game's TOGGLEUIFOCUS (PADBACK on the Thor)
    function PM.selKey()
        local ctx = bindCtx()
        return TS(function() return GetBindingKey("TOGGLEUIFOCUS", nil, ctx) end) or TS(function() return GetBindingKey("TOGGLEUIFOCUS") end)
            or "PADBACK"
    end
    function PM.unfocused()
        if type(WC.dialogs) == "table" and type(WC.dialogs.unfocused) == "function" then
            local ok, n = pcall(WC.dialogs.unfocused)
            if ok and type(n) == "number" then return n end
        end
        return 0
    end
    function PM.grouped() return T(function() return IsInGroup() end) == true end
    -- what Select should be now: "bound" (Select toggles Party mode), "dialog" (an unfocused invite waits: the game's key), "aside",
    -- "off" (the experimental switch off)
    -- m132: a fight's start binds Select whenever Party mode is allowed (solo, with a pet, an invite waiting: the binding cannot change
    -- in the fight, so the toggle always wins it; the invite is answered by touch or after the fight)
    function PM.selWant(aside, fightStart)
        if aside then return "aside" end
        if not PM.allowed() then return "off" end                       -- m100: the experimental switch off: Select is the game's
        if fightStart then return "bound" end
        if PM.unfocused() > 0 then return "dialog" end
        return "bound"
    end
    -- m132: a fight's start keeps the player's own button bindings (m34) off the party layers (ZL + a face, the bare D-pad), as Party mode
    -- on does, while Party mode is allowed and may be wanted in it: grouped, or on already
    function PM.fightKeys() return PM.allowed() and (PM.on() or PM.grouped()) end
    function PM.bind(aside, fightStart, carry)
        PM.bind0(aside, fightStart, carry)
        if PM.changed then pcall(PM.changed, "bind") end                -- `sel` / `aside` in the party document
    end
    function PM.bind0(aside, fightStart, carry)
        PM.sel = PM.selWant(aside, fightStart)
        PM.fightBound = fightStart and true or nil
        -- m100: the experimental switch off: a Party mode that is on ends (out of combat; PM.set refuses in a fight, the fight's end re-binds)
        if not PM.allowed() and PM.on() and PM.set(nil) then
            if PM.changed then pcall(PM.changed, "onoff") end
        end
        if aside then
            -- THE ONE SWITCH (the user's rulings: on): anything that takes the controller ends Party mode - out of combat only, and a
            -- prompt (PM.PROMPTS) only suspends it (m132; a fight's start never gets here while Party mode is allowed)
            if PM.OFF_WHEN_ASIDE and not fightStart and PM.on() and PM.takerNow() and PM.set(nil) and PM.changed then
                pcall(PM.changed, "aside")
            end
            return
        end
        if PM.on() then
            -- all 8 router keys, whatever their fight spells or the toolbar: faces 5 / 6 / 8, B, the D-pad
            for pos in pairs(FACE) do local b = face.buttons[pos]; if b then pcall(routerSetup, b, pos) end end
            pcall(routerB); pcall(routerD)
            for _, x in ipairs(routerAll()) do
                local key = R.keys[x.pos] or routerKey(x.pos)
                R.keys[x.pos] = key
                if key then carry(x.b, key) end
            end
        end
        if PM.sel == "bound" then
            local key = PM.selKey()
            PM.selBound = (PM.f and key and carry(PM.f.toggle, key)) and key or nil
            if not PM.selBound then PM.sel = "off" end
        else
            PM.selBound = nil
        end
    end
    -- routerCheck (every 0.1 s out of combat, nobody holding): a key of ours lost, or Select's state to change -> bind again (once a second)
    function PM.lost()
        if PM.on() and not PM.allowed() then return true end           -- m100: switched off while on
        if PM.on() then
            for _, x in ipairs(routerAll()) do
                local key = R.keys[x.pos]
                if key and TS(function() return GetBindingAction(key, true) end) ~= routerAct(x.b:GetName()) then return true end
            end
        end
        local want = PM.selWant(nil, false)
        if want ~= PM.sel then return true end
        if want == "bound" and PM.selBound and TS(function() return GetBindingAction(PM.selBound, true) end) ~= routerAct(PM.TOGGLE) then
            return true
        end
        return false
    end

    -- ---------------------------------------------------------------- the per-character store and the class defaults (3.6)
    -- WowCompanionSpellsDB.partyBy[name-realm] = { v = 1, slots = { a = id | false (Empty, chosen) | nil (the class default) },
    -- yc = id | nil (Inspect), touch = id | nil (Target), buffs = { id, ... } (Smart Y's party list: EMPTY at first, max 8) }
    PM.SLOTS = { "a", "x", "zla", "zlx", "zly", "zra", "zrx", "zry" }
    OV.PARTY_DEFAULTS = {
        SHAMAN = { a = 331, x = 8004, zla = 526, zlx = 2870, zra = 2008 },
        PRIEST = { a = 2050, x = 17, zla = 527, zlx = 528, zra = 2006, zrx = 139 },
        DRUID = { a = 5185, x = 774, zla = 8946, zlx = 2782, zra = 20484, zrx = 8936 },
        PALADIN = { a = 635, x = 19750, zla = 1152, zlx = 4987, zra = 7328, zrx = 633 },
        MAGE = { a = 475, zla = 604, zlx = 1008 },
        WARLOCK = { a = 5697 },
        HUNTER = {}, ROGUE = {}, WARRIOR = {},
    }
    OV.PARTY_RES = { 2008, 2006, 7328, 20484 }
    OV.PARTY_BUFFS = { 1243, 14752, 976, 6346, 1126, 467, 1459, 604, 1008, 19740, 19742, 20217, 19977, 1038, 20911, 131, 546, 5697, 132 }
    PM.BUFFS_MAX = 8
    PM.scratch = nil
    function PM.store()
        if type(_G.WowCompanionSpellsDB) ~= "table" then _G.WowCompanionSpellsDB = {} end
        local db = _G.WowCompanionSpellsDB
        local k = whoKey()
        local st
        if not k then                                                   -- no character yet: a scratch record, never saved
            PM.scratch = PM.scratch or {}
            st = PM.scratch
        else
            if type(db.partyBy) ~= "table" then db.partyBy = {} end
            if type(db.partyBy[k]) ~= "table" then db.partyBy[k] = {} end
            st = db.partyBy[k]
        end
        st.v = 1
        if type(st.slots) ~= "table" then st.slots = {} end
        if type(st.buffs) ~= "table" then st.buffs = {} end
        return st
    end
    function PM.class()
        local ok, _, token = CALL(function() return UnitClass("player") end)
        return ok and WC.isPlain(token) and type(token) == "string" and token or nil
    end
    function PM.icon(id)
        local i = T(function() return (C_Spell and C_Spell.GetSpellTexture and C_Spell.GetSpellTexture(id)) or GetSpellTexture(id) end)
        return type(i) == "number" and WC.isPlain(i) and i or 0
    end
    -- the book's spells by name (any rank is the spell: cast by name = the best rank known), book order
    function PM.book()
        local names, order = {}, {}
        local ok, list = pcall(function() return type(WC.spellsBindableList) == "function" and WC.spellsBindableList() or {} end)
        for _, e in ipairs(ok and type(list) == "table" and list or {}) do
            local n = e.id and OV.sbName(e.id)
            if n and WC.isPlain(n) and not names[n] then names[n] = e.id; order[#order + 1] = { id = e.id, n = n } end
        end
        return names, order
    end
    function PM.nameSet(ids)
        local out = {}
        for _, id in ipairs(ids) do local n = OV.sbName(id); if n then out[n] = id end end
        return out
    end
    -- a spell id -> the document's spell object { id, n, i, k = learned now, res }
    function PM.spellObj(id, names, extra)
        if type(id) ~= "number" then return nil end
        local n = OV.sbName(id)
        local o = { id = id, n = n or "?", i = PM.icon(id), k = (n and names[n]) and true or false }
        if PM.resNames[n or ""] or PM.resIds[id] then o.res = true end
        if extra then for k, v in pairs(extra) do o[k] = v end end
        return o
    end
    PM.resIds = {}
    for _, id in ipairs(OV.PARTY_RES) do PM.resIds[id] = true end
    PM.resNames = {}
    -- the chooser: the book's bindable spells the game calls helpful (plain), less Smart buffs' self-only ones, plus every known
    -- class default and party buff; book order
    function PM.chooser(names, order)
        local selfOnly, party, defs = {}, PM.nameSet(OV.PARTY_BUFFS), {}
        for _, id in ipairs(OV.SB_IDS) do local n = OV.sbName(id); if n and not party[n] then selfOnly[n] = true end end
        for _, t in pairs(OV.PARTY_DEFAULTS) do for _, id in pairs(t) do local n = OV.sbName(id); if n then defs[n] = true end end end
        local out = {}
        for _, e in ipairs(order) do
            local helpful = T(function() return C_Spell.IsSpellHelpful(e.id) end)
            local plainHelp = helpful == true and WC.isPlain(helpful)
            -- m127 (the user, 2026-11-11: no tracking selectable for Smart Y): never a tracking spell (defensive: the game's "helpful" flag)
            if ((plainHelp and not selfOnly[e.n]) or defs[e.n] or party[e.n]) and not OV.isTracking(e.n) then out[#out + 1] = { id = e.id, n = e.n, i = PM.icon(e.id) } end
        end
        return out
    end
    -- everything the document and the apply need, resolved now
    function PM.resolve()
        PM.resNames = PM.nameSet(OV.PARTY_RES)
        local st = PM.store()
        local names, order = PM.book()
        local def = OV.PARTY_DEFAULTS[PM.class() or ""] or {}
        local c = { slots = {}, spells = PM.chooser(names, order) }
        for _, s in ipairs(PM.SLOTS) do
            local v = st.slots[s]
            if v == nil then c.slots[s] = PM.spellObj(def[s], names, { d = true })
            elseif type(v) == "number" then c.slots[s] = PM.spellObj(v, names, { d = false }) end      -- false: Empty, chosen
        end
        c.yc = type(st.yc) == "number" and PM.spellObj(st.yc, names) or nil
        c.touch = type(st.touch) == "number" and PM.spellObj(st.touch, names) or nil
        return c, st, names
    end

    -- the pad's spells and the party row's touch cells from the store (out of combat; in a fight: at its end)
    function PM.apply(why)
        if inCombat() or not PM.f then PM.applyPending = true return false end
        PM.applyPending = nil
        local c = PM.resolve()
        local pad = PM.f.pad
        for _, s in ipairs(PM.SLOTS) do
            local e = c.slots[s]
            pad:SetAttribute("wc-ps-" .. s, (e and e.k and e.n ~= "?") and e.n or nil)            -- not learned: drawn dim, never cast
        end
        pad:SetAttribute("wc-py", (c.yc and c.yc.k) and c.yc.n or nil)
        local tn = (c.touch and c.touch.k) and c.touch.n or nil
        PM.touchName = tn
        -- m129: the Touch spell rides the pick cells (a tap on the picked plate; m130: the party row's cells 0-4 themselves)
        PM.eachPick(function(btn) btn:SetAttribute("wc-touch", tn) end)
        if not PM.rowBuilt then PM.buildRow() end
        if PM.smartApply then pcall(PM.smartApply, true) end                -- (its picks ride the partycfg below)
        if PM.sendCfg then pcall(PM.sendCfg, why) end
        return true
    end

    -- ---------------------------------------------------------------- the party row (2.2): 40 secure cells, opts.onPress
    -- m129: cols 0-4 = the plates' PICK cells (m130: the PARTYPICK snippet wrapped on the cell itself picks / touches / targets); the
    -- old target (0-4) and touch-spell (5-9) cells are gone (m131: 5 = the pet's pick cell, 6-9 spare)
    PM.COL = { pick = 0, on = 10, off = 11, focus = 12, menu = 17, resend = 22, pbsel = 23,
               pbops = { remove = 31, up = 32, down = 33, add = 34 }, mdone = 35, atk = 36 }
    -- m130: a pick cell (out of combat): wrapped once, its unit, the Touch spell, the pad's ref (again whenever the pad is made)
    function PM.pickCell(btn, col)
        if inCombat() or not btn then return end
        btn:SetAttribute("wc-pu", PM.U[col])
        btn:SetAttribute("wc-touch", PM.touchName)
        btn:SetAttribute("wc-pdec", nil)
        if PM.f and PM.f.pad then pcall(SecureHandlerSetFrameRef, btn, "wc-pad", PM.f.pad) end
        if PM.pickW[btn] then return end
        local hdr = routerHeader()
        if hdr and pcall(SecureHandlerWrapScript, btn, "OnClick", hdr, PM.PARTYPICK) then
            PM.pickW[btn] = true
            btn:HookScript("PostClick", function(self) pcall(PM.afterPick, self) end)
        end
    end
    function PM.eachPick(fn)
        local r = WC.pad and type(WC.pad.rows) == "table" and WC.pad.rows.party or nil
        if not (r and r.secure and type(r.cells) == "table") then return end
        for i = 0, PM.PET_COL do if r.cells[i] then pcall(fn, r.cells[i], i) end end      -- m131: + the pet's cell
    end
    function PM.cellInit(btn, col)
        for _, k in ipairs({ "type", "spell", "unit", "clickbutton" }) do btn:SetAttribute(k, nil) end
        local f = PM.f
        if col <= PM.PET_COL then
            PM.pickCell(btn, col)
        elseif col <= 9 then
            return
        elseif (col == PM.COL.on or col == PM.COL.off or col == PM.COL.mdone) and f then
            btn:SetAttribute("type", "click")
            btn:SetAttribute("clickbutton", (col == PM.COL.on and f.on) or (col == PM.COL.off and f.off) or f.mdone)
        elseif col >= PM.COL.focus and col <= PM.COL.focus + 4 then
            btn:SetAttribute("type", "focus"); btn:SetAttribute("unit", PM.U[col - PM.COL.focus])
        elseif col == PM.COL.atk then
            btn:SetAttribute("type", "macro"); btn:SetAttribute("macrotext", "/startattack")   -- m91 (Hub's hub22)
        end
    end
    function PM.ack(op, ok, why, slot, id)
        WC.send(WC.encode({ type = "partyack", v = PV, op = op, ok = ok and true or false, why = why, slot = slot, id = id }))
        WC.push()
        if why == "combat" and type(WC.spellsRefusedCombat) == "function" then pcall(WC.spellsRefusedCombat) end
    end
    function PM.onCell(col)
        if type(col) ~= "number" then return end
        if col >= PM.COL.menu and col <= PM.COL.menu + 4 then
            if type(WC.pmenu) == "table" and type(WC.pmenu.read) == "function" then pcall(WC.pmenu.read, PM.U[col - PM.COL.menu], "party") end
        elseif col == PM.COL.resend then
            if PM.resend then PM.resend() end
        elseif col >= PM.COL.pbsel and col <= PM.COL.pbops.add then
            if PM.pbOp then PM.pbOp(col) end
        elseif col <= PM.PET_COL then
            return                                                       -- m129: the pick ran in the secure click (PM.afterPick; m131 + the pet's)
        elseif col == PM.COL.atk then
            return                                                       -- m91: the attack ran in the secure click
        elseif col > PM.COL.mdone or (col > PM.PET_COL and col <= 9) then
            PM.ack("cell", false, "unknown column")
        end
    end
    function PM.buildRow()
        if not (WC.pad and type(WC.pad.secureRow) == "function") then return nil end
        PM.row = WC.pad.secureRow("party", 40, PM.cellInit, PM.onCell, { onPress = true })
        PM.rowBuilt = not inCombat()
        WC.atkCell = PM.row and { row = "party", col = PM.COL.atk } or nil     -- m91
        return PM.row
    end

    -- a setting (the Controller room's Party tab; Book's padtab 26-37): slot "a" .. "zry" = id | false (Empty); "yc" = id | false
    -- (Inspect); "touch" = id | false (Target). A spell must be in the chooser. Refused in a fight (secure attributes are locked)
    function PM.put(slot, v)
        local op = (slot == "yc" or slot == "touch") and slot or "slot"
        local id = type(v) == "number" and v or nil
        if inCombat() then PM.ack(op, false, "combat", slot, id) return false, "combat" end
        local isSlot = false
        for _, s in ipairs(PM.SLOTS) do if s == slot then isSlot = true end end
        if op == "slot" and not isSlot then PM.ack(op, false, "no such slot", slot, id) return false, "no such slot" end
        if v ~= false and not id then PM.ack(op, false, "nothing to set", slot) return false, "nothing to set" end
        local c = PM.resolve()
        if id then
            local found = false
            for _, e in ipairs(c.spells) do if e.id == id then found = true end end
            if not found then PM.ack(op, false, "not a party spell", slot, id) return false, "not a party spell" end
        end
        local st = PM.store()
        if op == "slot" then st.slots[slot] = id or false
        elseif op == "yc" then st.yc = id
        else st.touch = id end
        PM.ack(op, true, nil, slot, id)
        PM.apply("set")
        return true
    end
    -- m110: box = Party mode's state (on, the picked unit "u"); lb = each unit's button under LB (self / p1..p4), as last read
    -- m129 (party_design.md E / A.4): selWant = what Select is now ("bound" / "off" / "dialog" / "aside": Hub's top tab), rng(unit) =
    -- the one range answer (true / false / nil), grouped = in a party (Hub's own row: grouped or Party mode on, the user 2026-11-11)
    WC.padMap.party = { cfg = function() return PM.cfgNow and PM.cfgNow() or PM.resolve() end, put = PM.put, on = PM.on,
                        box = PM.box, lb = function() return OV.lbmap.last end,
                        selWant = function() return PM.sel or PM.selWant(R.aside, false) end, rng = function(u) return PM.rngOf(u) end,
                        grouped = function() return PM.grouped() end,
                        -- m131: the entries in pick order (you, the pet while out, party1..4) and the moves over them
                        units = function() return PM.units() end, nav = function(n, box, dir) return OV.partyNav(n, box, dir) end,
                        slots = PM.SLOTS, cols = PM.COL,
                        pbRemove = function(k) return PM.pbRemove and PM.pbRemove(k) end, padd = function(id) return PM.padd and PM.padd(id) end }

    -- ---------------------------------------------------------------- Smart Y's party buffs (3.6, 4.5): picks per unit, the list's ops
    -- the helpful aura names on `u` now; secret = a name could not be read (in a dungeon, a fight): no pick for that unit
    function PM.auras(u)
        local up, secret = {}, false
        for i = 1, 40 do
            local a = T(function() return C_UnitAuras.GetAuraDataByIndex(u, i, "HELPFUL") end)
            if type(a) ~= "table" then break end
            local n = T(function() return a.name end)
            if n == nil then break end
            if type(n) ~= "string" or not WC.isPlain(n) then secret = true break end
            up[n] = true
        end
        return up, secret
    end
    PM.PB_UNITS = { "player", "pet", "party1", "party2", "party3", "party4" }      -- m131: + the pet
    -- out of combat only, Smart Y on: per unit the first buff of the list this character knows and the unit lacks -> "wc-pb-<unit>"
    function PM.smartApply(quiet)
        if inCombat() or not PM.f then PM.smartPending = true return false end
        PM.smartPending = nil
        local picks = {}
        if OV.smartOn() then
            local st = PM.store()
            local names = PM.book()
            for _, u in ipairs(PM.units()) do
                local up, secret = PM.auras(u)
                if not secret then
                    for _, id in ipairs(st.buffs) do
                        local n = OV.sbName(id)
                        if n and names[n] and not up[n] then picks[u] = { id = id, n = n, i = PM.icon(id) } break end
                    end
                end
            end
        end
        local sig = {}
        for _, u in ipairs(PM.PB_UNITS) do
            local want = picks[u] and picks[u].n or nil
            if A(PM.f.pad, "wc-pb-" .. u) ~= want then PM.f.pad:SetAttribute("wc-pb-" .. u, want) end
            if picks[u] then sig[#sig + 1] = u .. "=" .. picks[u].id end
        end
        local s = table.concat(sig, ",")
        PM.picks = picks
        if s ~= PM.pickSig then
            PM.pickSig = s
            if not quiet then pcall(PM.sendCfg, "pick") end
        end
        return true
    end
    function PM.smartSoon()
        if PM.smartQueued then return end
        PM.smartQueued = true
        C_Timer.After(0.3, function() PM.smartQueued = nil; pcall(PM.smartApply) end)
    end
    -- Smart Y switched on / off (OV.setSmartOn): the picks and the document follow
    function PM.smartChanged()
        pcall(PM.smartApply, true)
        pcall(PM.sendCfg, "smart")
    end
    -- party row cols 23-30: select list row k = col - 22 (fresh 2 s); 31 / 32 / 33 remove / up / down on it; 34 a fresh "add" pick
    -- (the spell follows as Book's two taps: WC.smartYWants / WC.smartYSpell, op "padd"). Answered partyack op "pb".
    PM.PB_FRESH = 2
    function PM.pbOp(col)
        local now = GetTime()
        local ops = PM.COL.pbops
        if col < ops.remove then PM.pbSel = { row = col - PM.COL.pbsel + 1, at = now } return end
        local sel = PM.pbSel and now - PM.pbSel.at <= PM.PB_FRESH and PM.pbSel.row or nil
        PM.pbSel = nil
        if inCombat() then PM.ack("pb", false, "combat") return end
        if col == ops.add then OV.sbPick = { op = "padd", at = now } return end
        local list = PM.store().buffs
        if not sel or not list[sel] then PM.ack("pb", false, "no row picked") return end
        local id = list[sel]
        if col == ops.remove then table.remove(list, sel)
        elseif col == ops.up and sel > 1 then list[sel], list[sel - 1] = list[sel - 1], list[sel]
        elseif col == ops.down and sel < #list then list[sel], list[sel + 1] = list[sel + 1], list[sel]
        else PM.ack("pb", false, "nothing to do", nil, id) return end
        PM.ack("pb", true, nil, nil, id)
        PM.listChanged()
    end
    -- the spell after a fresh "add" pick (WC.smartYSpell's op "padd"): one of this character's party buffs, not listed yet, 8 at most
    function PM.padd(id)
        if inCombat() then PM.ack("pb", false, "combat", nil, id) return false end
        local names = PM.book()
        local n = type(id) == "number" and OV.sbName(id) or nil
        local pid = nil
        for _, b in ipairs(OV.PARTY_BUFFS) do if OV.sbName(b) == n then pid = b end end
        if not (n and pid and names[n]) or OV.isTracking(n) then PM.ack("pb", false, "not a party buff this character knows", nil, id) return false end
        local list = PM.store().buffs
        for _, x in ipairs(list) do if OV.sbName(x) == n then PM.ack("pb", false, "already listed", nil, id) return false end end
        if #list >= PM.BUFFS_MAX then PM.ack("pb", false, PM.BUFFS_MAX .. " at most", nil, id) return false end
        list[#list + 1] = pid
        PM.ack("pb", true, nil, nil, pid)
        PM.listChanged()
        return true
    end
    function PM.listChanged()
        pcall(PM.smartApply, true)
        pcall(PM.sendCfg, "list")
    end
    -- the Controller room's Party tab (PadTabCtl p33, by the controller): Remove list row k; Add = PM.padd(id)
    function PM.pbRemove(k)
        if inCombat() then PM.ack("pb", false, "combat") return false end
        local list = PM.store().buffs
        if type(k) ~= "number" or not list[k] then PM.ack("pb", false, "no row picked") return false end
        local id = table.remove(list, k)
        PM.ack("pb", true, nil, nil, id)
        PM.listChanged()
        return true
    end

    -- ---------------------------------------------------------------- the documents (section 1)
    PM.PADMAP = { row = "party", pick = PM.COL.pick, on = PM.COL.on, off = PM.COL.off, focus = PM.COL.focus,
                  menu = PM.COL.menu, resend = PM.COL.resend, pbsel = PM.COL.pbsel, pbops = PM.COL.pbops, mdone = PM.COL.mdone,
                  ptab = { row = "padtab", tab = 26, slot0 = 27, none = 37 } }
    -- partycfg's `c`: what every button / touch does, the chooser, Smart Y's party list and picks, the pad's columns
    function PM.cfgNow()
        local c, st, names = PM.resolve()
        local list = {}
        for _, id in ipairs(st.buffs) do
            local o = PM.spellObj(id, names)
            if o then o.res = nil; list[#list + 1] = o end
        end
        local known = {}
        for _, id in ipairs(OV.PARTY_BUFFS) do
            local n = OV.sbName(id)
            if n and names[n] then known[#known + 1] = { id = id, n = n, i = PM.icon(id) } end
        end
        -- m132: the picks in fights too (armed before it on the pad's wc-pb-<unit>; Y casts them in the fight with no In combat action)
        local pick = nil
        for u, e in pairs(PM.picks or {}) do pick = pick or {}; pick[u] = { id = e.id, n = e.n, i = e.i } end
        c.sy = { on = OV.smartOn(), list = list, known = known, pick = pick }
        c.pad = PM.PADMAP
        return c
    end
    function PM.sendCfg(why, noPush)
        WC.send(WC.encode({ type = "partycfg", v = PV, c = PM.cfgNow() }))
        if not noPush then WC.push() end
    end

    -- m131 (the user, 2026-11-11: "WHEN NO PET MAKE THE PLAYER FULL SIZED"): a pet out, from PLAIN reads only - as the HUD's pet strip
    -- (Trail.lua P.petHas): UnitExists when it answers a plain boolean, else a plain name, else HasPetUI
    function PM.petHas()
        local e = TB(function() return UnitExists("pet") end)
        if e ~= nil then return e end
        if TS(function() return UnitName("pet") end) then return true end
        local h = TB(function() return HasPetUI() end)
        if h ~= nil then return h end
        return TN(function() return HasPetUI() end) == 1
    end
    function PM.units()
        local out = { "player" }
        if PM.petHas() then out[2] = "pet" end                           -- m131: you, your pet, then party1..4
        -- m83 (the user, 2026-11-04: "party mode still not showing the other person in my party"): UnitExists alone may answer a
        -- secret (never == true); the party's own count (GetNumSubgroupMembers, else GetNumGroupMembers - 1) counts too, and a unit
        -- with a plain name is there
        local n = PM.plainNum(function() return GetNumSubgroupMembers() end)
        if not n then local g = PM.plainNum(function() return GetNumGroupMembers() end); n = g and math.max(0, g - 1) or 0 end
        for i = 1, 4 do
            local u = "party" .. i
            if i <= n or TB(function() return UnitExists(u) end) == true or TS(function() return UnitName(u) end) ~= nil then   -- TB: plain only
                out[#out + 1] = u
            end
        end
        return out
    end
    function PM.plainNum(fn)
        local v = T(fn)
        if type(v) == "number" and OV.hmPlain(v) then return v end
        return nil
    end
    -- m129 (party_design.md A.4): RANGE FROM ONE PLACE for both screens - the game's own party range (UnitInRange, as its party
    -- frames dim), only when it says it checked, plainly; your own row never dims. true / false / nil (not known: no dim)
    function PM.rngOf(u)
        if u == "player" or u == "pet" then return nil end               -- m131: nor the pet (no party range for it)
        local ok, inR, checked = pcall(UnitInRange, u)
        -- m85: plain FIRST - comparing a secret throws
        if ok and type(checked) == "boolean" and WC.isPlain(checked) and type(inR) == "boolean" and WC.isPlain(inR) and checked then
            return inR
        end
        return nil
    end
    -- m129: a member's plain facts only (no health, no mana, no guessed numbers: party_design.md A.5)
    function PM.member(u)
        local idx = (u == "player" and 0) or (u == "pet" and PM.PET_COL) or tonumber(u:match("%d+"))
        local n = TS(function() return UnitName(u) end)
        local ok, _, cls = CALL(function() return UnitClass(u) end)
        cls = ok and type(cls) == "string" and WC.isPlain(cls) and cls or nil
        local col = cls and _G.RAID_CLASS_COLORS and _G.RAID_CLASS_COLORS[cls] and _G.RAID_CLASS_COLORS[cls].colorStr or nil
        local okp, _, ptok = CALL(function() return UnitPowerType(u) end)
        local lv = PM.plainNum(function() return UnitLevel(u) end)
        local m = { u = u, i = idx, n = n or "?", c = cls, col = (type(col) == "string" and WC.isPlain(col)) and col or nil,
                    role = TS(function() return UnitGroupRolesAssigned(u) end) or "NONE",
                    dead = T(function() return UnitIsDeadOrGhost(u) end) == true,
                    off = T(function() return UnitIsConnected(u) end) == false,
                    pt = (okp and type(ptok) == "string" and WC.isPlain(ptok)) and ptok or nil,
                    lv = (lv and lv > 0) and lv or nil }
        m.rng = PM.rngOf(u)
        -- m131: the pet: no class colour or role of a player's, never "offline"
        if u == "pet" then m.c, m.col, m.role, m.off = nil, nil, "NONE", false end
        return m
    end
    -- party's `p`
    function PM.state()
        local m = {}
        -- m85 (the Thor's log, 2026-11-04: no party document at all while the game's roster had three): one member's error no longer
        -- loses the whole document; the first error of each kind is reported once (cap probe "partyerr", the app logs it as WCPARTY)
        for _, u in ipairs(PM.units()) do
            local okM, mem = pcall(PM.member, u)
            if okM and type(mem) == "table" then m[#m + 1] = mem
            else
                local e = tostring(mem)
                PM.errSeen = PM.errSeen or {}
                if not PM.errSeen[e] then
                    PM.errSeen[e] = true
                    pcall(function() WC.send(WC.encode({ type = "cap", probe = "partyerr", v = PV, u = u, err = e:sub(1, 300) })) end)
                end
            end
        end
        local on = PM.on()
        local box = nil
        if on then box = math.max(1, math.min(#m, A(PM.pad(), "wc-box") or 1)) end
        return { on = on, aside = R.aside or nil, box = box, fight = fighting(), grp = PM.grouped(), sel = PM.sel or PM.selWant(R.aside, false),
                 m = m }
    end
    PM.lastParty = nil
    -- force = send even when nothing changed (the resend, the heartbeat); push: "flush" (WC.push), "throttle", or nil (rides a snapshot)
    -- m85: a failed send says why, once per error (the pcall around it hid every failure before)
    function PM.trySend(force, push)
        local ok, e = pcall(PM.sendParty, force, push)
        if ok then return e end
        e = tostring(e)
        PM.errSeen = PM.errSeen or {}
        if not PM.errSeen[e] then
            PM.errSeen[e] = true
            pcall(function() WC.send(WC.encode({ type = "cap", probe = "partyerr", v = PV, u = "doc", err = e:sub(1, 300) })); WC.push() end)
        end
    end
    function PM.sendParty(force, push)
        local doc = { type = "party", v = PV, rs = PM.rs, p = PM.state() }
        PM.sentN = type(doc.p) == "table" and type(doc.p.m) == "table" and #doc.p.m or nil      -- m82: the login re-checks compare it
        local key = WC.encode(doc)
        if key == PM.lastParty and not force then return false end
        PM.lastParty = key
        WC.send(key)
        if push == "flush" then WC.push() elseif push == "throttle" then WC.pushThrottled() end
        return true
    end
    -- a field may have changed: one party document per 0.5 s at most (coalesced), then throttled. The roster and on / off: at once, flushed
    function PM.changed(why)
        if why == "roster" or why == "onoff" then
            if why == "roster" then
                PM.rs = PM.rs + 1
                -- m129: the roster's driver re-clamps the pick on its own beat: the box document follows a moment later
                if PM.on() and type(C_Timer) == "table" and not PM.rosterBox then
                    PM.rosterBox = true
                    C_Timer.After(0.3, function() PM.rosterBox = nil; pcall(PM.sendBox, "state") end)
                end
            end
            PM.trySend(true, "flush")
            return
        end
        if PM.partySoon then return end
        PM.partySoon = true
        C_Timer.After(0.5, function() PM.partySoon = nil; PM.trySend(false, "throttle") end)
    end
    -- RESEND (party col 22): everything the app needs after its own restart, flushed
    function PM.resend()
        PM.sendCfg("resend", true)
        PM.sendParty(true, nil)
        PM.n = PM.n + 1
        local b = PM.box()
        b.k = "state"
        WC.send(WC.encode({ type = "partybox", v = PV, b = b }))
        PM.ack("resend", true)                                          -- (its push flushes all four)
    end
    -- RIDE THE CORE HEARTBEAT (Chat.lua's pattern): an app restart loses nothing; they land in that snapshot's own push
    PM.RIDE = { heartbeat = true, login = true, zone = true, manual = true }
    if type(WC.snapshot) == "function" and type(hooksecurefunc) == "function" then
        hooksecurefunc(WC, "snapshot", function(reason)
            if PM.RIDE[reason] then
                pcall(PM.sendCfg, reason, true)
                PM.trySend(true, nil)
            end
        end)
    end

    function PM.onEvent(e, a1, a2, a3, a4)
        if e == "PLAYER_REGEN_ENABLED" then
            -- m132: nothing taken in the fight turns Party mode off at its end (the user: usable in combat)
            if not PM.rowBuilt or PM.applyPending then pcall(PM.apply, "fight-end") end
            if PM.f then pcall(PM.link) end
            PM.smartSoon()                                              -- the auras read plain again; the picks may be set again
            PM.changed("fight")
        elseif e == "PLAYER_REGEN_DISABLED" then
            PM.changed("fight")
        elseif e == "PLAYER_ENTERING_WORLD" or e == "SPELLS_CHANGED" or e == "PLAYER_LOGIN" then
            if not PM.applySoon then
                PM.applySoon = true
                C_Timer.After(0.3, function() PM.applySoon = nil; pcall(PM.apply, e == "SPELLS_CHANGED" and "spells" or "login") end)
            end
            if e == "PLAYER_ENTERING_WORLD" then
                PM.changed("roster")
                -- m82 (the user, 2026-11-04: "when logging back in I am in a party, but it only shows me"): at a login the party's unit
                -- tokens come a moment after the world, with no roster event of their own; looked at again at 2, 5 and 10 s, sent only
                -- when the member count differs from what was sent
                for _, s in ipairs({ 2, 5, 10 }) do
                    C_Timer.After(s, function()
                        local n = #PM.units()
                        if n ~= PM.sentN then PM.changed("roster") end
                    end)
                end
            end
        elseif e == "GROUP_ROSTER_UPDATE" or (e == "UNIT_PET" and a1 == "player") then
            PM.changed("roster")                                        -- m131: a pet out / gone renumbers the entries
            PM.smartSoon()
        elseif e == "UNIT_AURA" then
            if (a1 == "player" or a1 == "pet" or (type(a1) == "string" and a1:match("^party%d$"))) and not inCombat() then PM.smartSoon() end
        elseif e == "UNIT_LEVEL" then
            if a1 == "player" or a1 == "pet" or (type(a1) == "string" and a1:match("^party%d$")) then PM.changed(e) end
        elseif e == "UNIT_CONNECTION" or e == "PLAYER_ROLES_ASSIGNED" or e == "UNIT_DISPLAYPOWER" or e == "PLAYER_DEAD"
            or e == "PLAYER_ALIVE" or e == "PLAYER_UNGHOST" then
            PM.changed(e)
        end
    end

    PM.ev = CreateFrame("Frame")
    for _, e in ipairs({ "PLAYER_REGEN_ENABLED", "PLAYER_REGEN_DISABLED", "PLAYER_ENTERING_WORLD", "SPELLS_CHANGED", "PLAYER_LOGIN",
                         "GROUP_ROSTER_UPDATE", "UNIT_CONNECTION", "PLAYER_ROLES_ASSIGNED", "UNIT_LEVEL",
                         "UNIT_DISPLAYPOWER", "PLAYER_DEAD", "PLAYER_ALIVE", "PLAYER_UNGHOST", "UNIT_AURA", "UNIT_PET" }) do
        pcall(function() PM.ev:RegisterEvent(e) end)
    end
    -- range (no event of its own) needs a clock: once a second, and a document only when something moved (m132: no fight taker noted)
    if type(C_Timer) == "table" and type(C_Timer.NewTicker) == "function" then
        pcall(C_Timer.NewTicker, 1, function() pcall(PM.changed, "tick") end)
    end
    PM.ev:SetScript("OnEvent", function(_, e, ...)
        if not PM.f then pcall(PM.make) end
        if PM.onEvent then pcall(PM.onEvent, e, ...) end
    end)
    pcall(PM.make)
    pcall(PM.buildRow)                  -- registered at Spells' LOAD: numbered before the bag rows Bags adds at runtime (2.1)
end
partyMode()

-- m129 THE COMBAT LOG RETIRED (party_design.md A.5; the user, 2026-11-11: no health or mana on the bottom): m92 / m95-97 / m112 turned
-- on Advanced Combat Logging and the game's combat log for the app's guessed party health; nothing reads it now, so nothing starts it.
-- THE GIVE-BACK STAYS ("leave no trace"): the Advanced Combat Logging WE turned on (WowCompanionSpellsDB.aclOurs), or - once, for
-- 1.0.17 / 1.0.18, which forced it on for everyone without keeping that (aclReset1) - goes back to the game's default, off. Out of a
-- fight, at a login; said once in a "clog" document.
do
    local clf = CreateFrame("Frame")
    local function giveBack(why)
        if InCombatLockdown() then return end
        if type(WowCompanionSpellsDB) ~= "table" then WowCompanionSpellsDB = {} end
        local db = WowCompanionSpellsDB
        local get = (C_CVar and C_CVar.GetCVar) or _G.GetCVar
        local set = (C_CVar and C_CVar.SetCVar) or _G.SetCVar
        local adv = type(get) == "function" and select(2, pcall(get, "advancedCombatLogging")) or nil
        local gaveBack = false
        if adv == "1" and (db.aclOurs or not db.aclReset1) and type(set) == "function" then gaveBack = pcall(set, "advancedCombatLogging", "0") end
        db.aclOurs, db.aclReset1 = nil, true
        if gaveBack then
            pcall(function() WC.send(WC.encode({ type = "clog", v = PV, why = why, off = true, advBack = true })); WC.push() end)
        end
    end
    clf:RegisterEvent("PLAYER_ENTERING_WORLD")
    clf:SetScript("OnEvent", function()
        if C_Timer and C_Timer.After then C_Timer.After(2, function() pcall(giveBack, "login") end) else pcall(giveBack, "login") end
    end)
end
