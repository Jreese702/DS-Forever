-- WowCompanion / Bags.lua
-- The BAGS room: what is in your bags, so you can search it with a real keyboard instead of
-- scrolling a grid with a controller.
--
-- Every data source here was verified present in the client binary AND measured non-secret,
-- which is why Bags was chosen as the first data-fed room: it works identically whether or not
-- the flush behaviour is good, because an inventory does not go stale in a way that lies.
--   C_Container.GetContainerNumSlots(bag)
--   C_Container.GetContainerItemInfo(bag, slot) -> a table
--   C_Container.GetContainerItemID(bag, slot)   -> the id even when the item is not cached yet
--   C_Container.GetContainerNumFreeSlots(bag)
--   C_Item.GetItemInfoInstant(id) / C_Item.GetItemInfo(id)   (18 returns -- use CALL)
--
-- USER RULINGS (2026-09-27): one row per STACK and in BAG ORDER -- the room shows the bags
-- exactly as they are, because every action works on one stack at a time. Sorting and a merged
-- "clean" view may come later as toggles; the wire stays per-slot either way.
--
-- bags-take5 (the same report; the user, 2026-11-16 on 1478: "read my DS my bag is behaving weird and opening the bag on top for some
-- reason"): THE CAUSE, traced from the device logs (no code change beyond this note; bags-take1 is the fix at the source). The last
-- NPC window of the session was a VENDOR: 15:49:43 "doc holder=npc" at Morg Gnarltree <Armorer> (the user checking 1478's new vendor
-- figures and sell prices), so MERCHANT_SHOW set ctx = "merchant". Interact believes the FRAME over the events (its isOpen: once the
-- frame was seen, IsShown decides; buildDoc re-looks when they disagree, "a close event that never arrived"), so it let go of the
-- vendor and every room worked after it (16:37 the bar held, Settings / Chat / Macros / Controller rooms). Bags believed ctx ALONE
-- (1477 and 1478 alike), cleared only by MERCHANT_CLOSED; with that close not seen, every take after it was skipped
-- "take-skip-window" (16:42:50, :56, :59 - the first Bags entries after the vendor): the game's Combined Backpack on top, our room
-- with no cursor. Every other listed window is ruled out: the Interact ones (Merchant, Gossip, Quest, Bank, Mail, Trade, Auction
-- House, Trainer) would have held the pad and shown Interact's pop-up, not the Bags room (bot3); CharacterFrame / GuildBankFrame
-- were not on the top screen (top3), no Character room or guild bank was entered, and no 1477 -> 1478 Lua shows, keeps or alphas
-- one (the Controller / Spellbook leave just before, Book.lua / PadTabCtl.lua, is unchanged). 1477 looked fine only because no vendor
-- was visited on it before the bags were opened. bags-take1 makes ctx true only while its own frame is shown (CTXW.now), so the
-- vendor's stale state is let go at the next take (said once as ctxStale) and the bags are taken; a window really shown still skips,
-- by name. Nothing in the game is called.
--
-- bags-take4 (the same report; the user, 2026-11-16 on 1478: "read my DS my bag is behaving weird and opening the bag on top for some
-- reason"): bags-take3's orphan rule is TAKEN OUT (reverted, with Interact i166). Taking anyway after 1.5 s over-took at a real guild
-- bank (no Interact window), at the game's own Character window with the NPC-windows bit off, and at a real vendor / bank whose
-- Interact document came late. The trace could not name the window behind 1478's "take-skip-window": the Controller / Spellbook leave
-- just before it (Book.lua, PadTabCtl.lua) is unchanged since 1477, and no 1478 change shows or keeps a listed window or sets ctx. So a
-- shown listed window still skips; the skip names it ("take-skip-window:<frame>", bags-take1) so the next log says which one, and the
-- take is tried again when it goes (bags-take1 / bags-take2). Nothing in the game is called.
--
-- bags-take2 (the same report, review of bags-take1): the retry and the 1 s check skipped on ctl.left, and ctl.left was cleared only by a
-- successful take, so a leave from an EARLIER visit (another room "taken" -- the user was in the Spellbook / Controller rooms just
-- before -- Home's "free", B) stopped every later retry: the bags stayed on top again. A fresh bag open (the bags were closed) and the
-- room's touch entry now start a new visit (ctl.left = nil); a leave during the visit still stops the take back as before.
--
-- bags-take1 (the user, 2026-11-16, test build 1478:
-- "read my DS my bag is behaving weird and opening the bag on top for some reason"; the top screen showed the GAME's Combined Backpack, the bottom our room with no cursor, the log "note=take-skip-window"
-- three times with no NPC window up): the open-window state (ctx, set by MERCHANT_SHOW / MAIL_SHOW ...) was cleared ONLY by its
-- matching close event, and the take trusted it alone, so one close event the add-on never saw (an earlier vendor visit) skipped
-- every take after it for good: the game's bags stayed on top, the room held nothing. Now ctx is believed only while its own frame is
-- shown (CTXW.now, a 1 s grace for the open event that comes before the frame); a stale one is let go (ctl doc ctxStale) and the
-- SELL / DEPOSIT labels follow it too. A skip names the window ("take-skip-window:MerchantFrame", "...:ctx-merchant"), and the take is
-- tried again when that window goes (its OnHide or close event, CTXW.retry) while the game's bags are still open, with the 1 s
-- check's exceptions. Read-only (IsShown, HookScript): no game window is opened or closed, nothing protected is called.
--
-- bags-kit2 (issue #89, 1.0.21: "replacing an existing armor kit with another one does not work, the confirmation screen loops"): while
-- the room's "Choose an item" list is up and the GAME asks its own question about the pick (StaticPopup REPLACE_ENCHANT and the like,
-- or any pop-up the game's controller focuses), A / B are PASSED to the game, as for a bag's bind question ("bindask"). Before, the
-- room kept A: every Yes re-used the kit on the item, the game asked again, and the question came back for ever (KIT.asking).
--
-- bags-empty1 (issue #29, 1.0.16 streaming: "bad argument #1 to '?' (Usage: local result = C_Item.IsEquippableItem(itemInfo))" from
-- SecureTemplates.lua:433, self = Button <TapPad.lua:291>, actionType "item", then "WowCompanion has been blocked from an action only
-- available to the Blizzard UI" - SetPreferredGamepadInteractTarget()): a click on a secure "item" cell whose slot is EMPTY (a bag
-- row's "<bag> <slot>", or binv's worn slot) makes the game's own item action call IsEquippableItem(nil) and raise; the error frame
-- that opens takes the gamepad UI's focus and the gamepad bar stays blocked until a /reload (the reporter's chain). Now each such cell
-- checks its slot just before the click (PreClick, out of combat): empty = no secure action for that click (type nil; PostClick puts
-- "item" back), so nothing raises. A filled slot's click is exactly as before. In a fight attributes cannot change, so an empty cell
-- still raises there; its tap proof says so (empty / emptyFight) so a next report shows whether that case is still hit.
-- bags-empty2 (issue #29 review: "a tap on an empty slot still raises, opens the error frame and blocks the gamepad bar until /reload" in
-- combat): at PLAYER_REGEN_DISABLED (the lockdown has not begun, attributes can still be written) every guarded cell whose slot is
-- empty gets type nil for the whole fight; PLAYER_REGEN_ENABLED puts "item" back. Cost: an item looted into an empty slot mid-fight
-- cannot be used by tap until the fight ends (a no-op, never an error). A slot that empties DURING the fight (the last potion used)
-- keeps its "item" and can still raise; its tap proof says emptyFight without fightOff, so a next report shows whether that is hit.
--
-- bags-unbind1 (issue #213, 1.0.27: "I cannot clear a binding made through the bag screen. I binded an item to left stick and can no
-- longer remove it."): BB.set was the only writer of BB.binds, so a Bind to a Button could be replaced but never let go, and BB.load
-- put it back at every login. Now, on the bind sheet, pressing the button THIS item is already bound to clears it (BB.clear: the
-- record dropped + saved, the override let go by BB.apply; out of combat only, refused in a fight as bind-combat). Note "unbound",
-- ctl.unbound = the chord. The sheet is told the item's own buttons (ctl.mine) and every item bind of this character (ctl.bbinds,
-- diagnostics), so a next report shows what is held. A button held by an item no longer in the bags: bind any item over it, then
-- press it again. Overrides only, as before: nothing of the player's is touched.
--
-- bags-tip1 (the user, 2026-11-13: "When looking at items in bags it sometimes feels really laggy and the tool tip shows one thing for a
-- half second and has to load the rest and reloads the tool tip. I want it to feel fast and load that tool tip as its full description
-- each time"; E:/wow-build/bags_design_1113.md section 1): the cursor's tooltip is KEPT per item (TIP, many items, read ahead around
-- the cursor out of a fight) and rides with the cursor's own message, so no rest and no second send; a bags collect no longer
-- forgets them (only an item gone or changed, or what is worn); only complete tooltips are kept ("Retrieving item information" /
-- no lines = loading, read again on ITEM_DATA_LOAD_RESULT / TOOLTIP_DATA_UPDATE); the 19 worn tooltips are read only when worn gear
-- changes (WORN). Read-only calls only; the 0.2 s send limit is unchanged.
--
-- bags-onerow1 (the user, 2026-11-13: "Bags share one row"; the pad was full: with every bag and the reagent bag the six per-bag drag
-- rows bmv0..bmv5 took 56 of 56 rows at the Thor's UI scale, so bmv5, bmvq and the radials' / cast bar's rows had no row): ONE shared
-- plain drag row "bmv" (column = slot - 1) for every bag. The bag a bmv tap means is NAMED by the tap just before it, on Bags' own
-- plain row "bagsort" (it was one cell; now 40: column 0 = the game's sort as before, column MV.BASE + bag = "the next bmv tap is in
-- that bag"). A name is kept MV.TTL seconds and used by ONE bmv tap; a bmv tap with no fresh name is REFUSED (why "nobag"), the half-
-- done drag pick and a pending cursor tap with it: a bag is never guessed. Plain rows only, no game action, the same in a fight.
-- Rows: "bmvq" goes on at this file's load (before the radials' and the cast bar's lazy rows), "bmv" is PINNED to the pad's last row
-- (TapPad pad19), so at the smallest UI scale (54 rows) the shared drag row is the one that drops. The bags doc says the route (mv).
-- bags-onerow3 (review of bags-onerow1, the user 2026-11-13: "Bags share one row"): a finger on a slot is now THREE taps (bmvq 36, the
-- bag's name, the bmv cell) and a put-down by touch is a fourth (bmvq 31). When the cursor move is lost (a nameless bmv, a bmv that
-- missed its 36, or a 36 whose bmv never came), the touch A / Y / bag-X that follows within CURSOR_TTL is REFUSED (note
-- "cursor-miss", no game action), never acted on the slot the cursor was still on. The controller's own buttons are not touched.
-- bags-act1 (the user's picks, 2026-11-13, on E:/wow-build/bags_design_1113.md: "Compare action in the bag item Y menu (gear only)",
-- "side by side: bag item left, worn item(s) right", "confirm stays A = Yes, B = No; rare and better stay REFUSED", "never in combat",
-- "'Suggested' section at the top of the Destroy picker, RANKED MIX", "a two-hander compares vs main hand + off hand together; empty
-- slot shows all gains"):
--  * COMPARE (ACT, mode "compare"): a fourth Y-menu row, usable on gear only (the item's equip location, not its tooltip). It opens
--    the app's compare sheet (the bag item left, what it would replace right, the game's stat changes under each); the room keeps the
--    item's tooltip in that mode (tipKey). Nothing happens in the game; B = back to the grid; the sheet scrolls in the app (the D-pad
--    up / down, the right stick, a finger). A finger on the menu's Compare row: bmvq 27, then column 8 (ACT.COMPARE_COL).
--  * DESTROY HARDENED: the room's delete goes through WC.bagsDestroy (the item still there, never in combat, nothing on the cursor,
--    a player bag only, legendary refused) - rare and better stay refused by the room (its box: delete it in the game); the delete
--    is confirmed by the slot again at the next bag update (1.5 s at most), not at once. cursorBusy() asks GetCursorInfo (anything
--    on the cursor, not only an item). One item per press, from a press only; never the game's pop-up. Destroy is greyed in a fight.
--  * SUGGESTED (ACT.suggest, mode "dpick"): Destroy opens a picker whose top section is a ranked list - grey / no-value items (the
--    lowest stack value first), then gear worse than worn (a lower item level or the game's stat changes all losses) or unusable (a
--    red tooltip line), then the cheapest white stacks - never quest items, items a quest in the log needs (WC.questNeedItems), quest
--    starters, rare and better, hearthstones, keys or profession tools; the cursor's own item below it ("This item", focused at
--    first). Select, then A = the same single confirm (B there = back to the picker). No suggestion = the confirm at once, as before.
-- bags-act2 (review of bags-act1; the user's rulings quoted there: "never in combat", "rare and better stay REFUSED"):
--  * the delete's slot check: a bag update that still shows the item (the lock, before the server's answer) keeps waiting; only the
--    slot gone answers at once, the 1.5 s deadline answers either way (it said "destroy-unsure" at the first bag update before).
--  * "worse than worn" by the game's changes needs EVERY change line to be a loss ("-..."): a line in another colour or without a
--    sign is not a loss (an upgrade whose gains came in another colour was offered as worse).
--  * never suggested also: a shaman's totems (5175-5178) and soul shards (6265) - no sell value, so they ranked as junk first.
--  * in a fight the picker's re-rank (a value loading, B from the confirm) reads no tooltips: the kept ones only.
--  * the picker's gold row, when its item is gone: onto the cursor's own item ("This item"), never the first suggestion.
--  * the compare sheet closes ("moved") at the bag update when its item left the slot, not only at the next press.
-- bags-kit3 (the user's picks, 2026-11-13: "items a pick can't go on are HIDDEN (only 'fits but no free cell in a fight' stays greyed);
-- Feed Pet lists only what C_PetInfo.CanPetEatItem allows ... already-enchanted gear is shown ... the Bags room's target list serves every
-- route"): "Choose an item" lists only what the pick can go on, from every route; KIT.ctx below.
-- bags-kit3 review (2026-11-13, the same picks: "items a pick can't go on are HIDDEN"): the list can now change under the cursor while
-- the pick is on (the game's item context arriving late, loot), so A answers the ITEM the cursor is on (KIT.key), never the row number:
-- a row gone since the app last drew the list is not used ("pick-moved", the list is drawn again); a changed pick type reports.
-- bags-kit4 (issue #332, 1.0.32: "mage comprehension skill does not work, it opens the backpack room but states that there are no
-- elligible items in bags despite unread scrolls"): an item-condition pick ("cond") lists the bags' non-gear items the game says it can go
-- on (DoesItemMatchSpellItemCondition true), first; before, only gear was ever listed for it (KIT.list).
-- bags-merge1 (the 1.0.30 test build merge, code 1453): bags-act1 / bags-act2 (feat/bags-actions), bags-kit3 (fix/kit-filter) and uneq1 / uneq2 (feat/unequip) side by side, each kept as written.
-- bags-nocmp1 (the user, 2026-11-13: "the bag items are still showing currently equipped, I dont want to see that, but I do want to see
-- the stat changes at the bottom of that weapon if I equip it"): the room asks Interact for its tooltips WITHOUT the worn pieces' own
-- text (WC.tipOf(key, TIP.NW), Interact i159): each compare entry is only the game's stat changes (nw = true). The hover card shows
-- those changes at the foot of the bag item's own tooltip, no "Currently Equipped" card; the Compare sheet takes the worn pieces' text
-- from the equipped rows (WORN, read once per gear change). One worn-tooltip read less per slot per item; the cache is unchanged.
-- uneq1 (the user's picks, 2026-11-13: "Unequip lives in the Character room ... Y = More opens a small menu with 'Unequip' first";
-- "WEAPONS (slots 16,17,18) MAY be unequipped in combat, armor is refused in combat"; "Never let a stale bag pick equip anything"):
-- WC.bagsUnequip(slot), reached by Character.lua's arming cell then one bmvq column (WC._bagsUnequipArm / _bagsUnequipTap, after
-- WC.bagsMove). The game's own pick-up and put-down into the first free plain bag slot; a blocked pick-up in a fight = "combat".
--
-- uneq2 (review of uneq1; the user's pick: "Never let a stale bag pick equip anything"): the Character room's More menu opening
-- (`moreon`) voids a Bags drag pick too (WC._bagsVoidPick), so a lost arm tap can no longer turn the slot tap into a drop; and a
-- Bags-room tap (bmvq column 19+) disarms a pending arm, so a lost slot tap can never make a later Bags tap take a piece off.
--
-- char-more1 (the user, 2026-11-13, on the Character room's More menu: "if unequip is the only thing in this more menu then what even is
-- the point? we should have options there like unequip, replace and enchant"). Read-only additions for the room's Replace and Enchant:
--  * a gear row carries `nouse` when the game won't let this character wear it (WC._bagsNoUse: its required level, the game's own
--    C_PlayerInfo.CanUseItem, a red line on its kept tooltip): Replace lists only what can go on;
--  * a kit row carries `kfy` / `kfn` (",5,7,"): the worn slots the GAME said the kit may / may not go on, asked whenever that kit's pick
--    was on (KIT.learn: KIT.match's own calls for every worn piece), kept per worn piece (a new piece there is asked again);
--  * a kit used from its own bag cell (the Character room's Apply / Enchant) names the pick that follows, as the Bags room's X does;
--  * a drop on a worn slot (the drag; the room's Replace of a ring / trinket / a dual wielder's one-hander) that makes the game ask "bind it
--    to you?" leaves the cursor alone while the game asks (its own A / B answer it), instead of clearing it at once (which said no).
-- Plain rows and calls as before: no protected call, no Blizzard open / close, no binding, macro or CVar.
--
-- char-more2 (review of char-more1; the user: "we should have options there like unequip, replace and enchant"): while a drag's "bind it
-- to you?" waits (BINDWAIT.on) the Bags room hands A / B to the game as well (syncPass, as for "bindask"): before, only the Character
-- room did, so a drag in the Bags room left the piece on the cursor with no pad answer for up to BINDWAIT.MAX.

-- bags-bindbox1 (the user, 2026-11-13: "when doing bind to a button from bags it should open the normal spell binding menu"): the Y menu's
-- Bind to a Button opens the SPELLBOOK'S Bind box (Book b59 WC.spellsBindItem) for the item, hosted: Bags keeps the controller and hands
-- every press to the box (mode "bindbox", WC.spellsBindHosted.press), the box asks the capture for Select / L3 / R3 through Bags
-- (ACT.host.extra), and when the box ends (kept, cancelled, its answer closed) Bags is back on the grid on the same slot (ACT.host.done).
-- The box's tabs, keep / undo and refusals are the spell box's; the item goes where a spell would (Normal: the game's own PlaceAction
-- on that slot; L3 / R3: PadMap's stick entry; In combat: PadMap's fight layer), and pressing the button it is already on clears it
-- (issue #213's rule, now inside the box). The old bind sheet (the capture's chord, onChord / armBind) is gone. Binds it made before:
-- BB still applies them at login (RB / RT / Select as before); one on L3 / R3 MOVES into PadMap's stick entry for that stick (BB.migrate:
-- the same "/use item" on the same button, now shown and cleared in the box), or is let go when that stick has something of its own
-- already (the router's priority binding hid it). A lost controller closes the box quietly (abort). No new game call of Bags' own.
--
-- bags-qstart1 (issue #252, 1.0.29: "Items with quests attached to them cannot be started from the bag menu"; the user, 2026-11-13:
-- "yes on the triage things"): an item that BEGINS a quest (the game's "This Item Begins a Quest": C_Container.GetContainerItemQuestInfo
-- gives a quest id, not active) has no "Use:" spell, is not readable and is not worn, so its row carried none of use / readable / equip
-- and the room's tooltip offered no action at all - after the pick-up prompt's "Not now" (Dialogs d27) it could not be started from the
-- bags. Its row now says qstart = true while that quest is neither in the log nor done (the prompt's own test, D.qsAt), and the app
-- offers "Start Quest" on X: the slot's own secure item cell, the game's right-click, which opens the game's quest page. Read-only
-- calls only (C_QuestLog.IsOnQuest / IsQuestFlaggedCompleted); nothing else changes.
--
-- bags-open1 (wk-containers; the user, 2026-11-16: "opening containers from my bags is a little cluncky, there is not a button although
-- weirdly x does work to open them, it opens the loot menu back in the home room, it should just open the loot menu inside of the bags and
-- then keep the player inside of the bag room."): (1) A BAG ITEM THAT OPENS says so. Its row carries opens = true from the game's own
-- signals - C_Container.GetContainerItemInfo's hasLoot (what the game's own controller bag shows its Open for), or the item's tooltip
-- line ITEM_OPENABLE ("<Right Click to Open>") - and the app offers "Open" on X as it offers Start Quest / Use (X was already the slot's
-- secure item cell, the game's right-click: unchanged). A box the game says is LOCKED (its tooltip line LOCKED) carries lockedBox =
-- true instead: the app shows "Locked" faint, and X there is refused here ("locked") - the game is never asked. The tooltip is read only
-- for boxes (hasLoot, or a Miscellaneous item with no other action), and fresh at the X press. (2) THE LOOT WINDOW STAYS IN THE ROOM:
-- a bag cell of ours clicked a moment ago (OPEN.TTL, its PreClick) and a loot window opening while Bags holds = the loot is the room's
-- own (spec.lootUnder, capture v21 / Interact i161): the room is SUSPENDED under the loot window (the app shows the loot window over the
-- Bags room, same controls), not lost, and resumes on the same slot when it closes (spec.resumed: "loot-resumed"). Out of a fight only;
-- a fight that starts meanwhile lets the room go as before. Bags full: the game's own "Inventory is full" and Interact's Make Space, as
-- for any loot. Read-only calls only; nothing new is called in the game.
-- bags-vslots1 (feat/vendor-bag-slots; the user, 2026-11-16, on the vendor window's bag / ammo figures: "It should show like bag slots out
-- of how many available and for ammo it should show ammo/total available space. The numbers on their own don't mean anything"): the
-- worn ammo (equipped.ammo) now says its STACK SIZE (`stack`: C_Item.GetItemMaxStackSizeByID, else GetItemInfo's stack count; left out
-- when unread), so the app can say what a quiver / ammo pouch holds (its slots x that stack) beside the ammo in it (the bag rows'
-- counts). The bags used / total come from what the doc already says (each bag's slots, free and family). Read-only calls only.
-- bags-gear1 THE EQUIP SLIDE-OUT (the user, 2026-11-16: "Choose which ring or weapon slot to replace"; "X opens a slide-out of ARMOR
-- (item icons) next to the item ... plus a blank entry that unequips"; "The Y text menu stays for enchant and bind (use-effect items)";
-- "When pressed, X animates the options sliding out"; the picks: D1a "X equips it at once, as today" when the piece has one place to go):
--  * X on GEAR WITH TWO PLACES TO GO (a ring, a trinket, a dual wielder's one-hander), out of a fight, no game window open: the bag bar's
--    chooser (EQ) in its `gear` kind - the target slots beside the tile (the worn pieces), the D-pad left / right, A = equip it THERE
--    (equipTo: the game's own pick-up and EquipCursorItem(slot), the drag's way, out of onInvCell), B = back. The game's "bind it to
--    you?" gets A / B (mode "bindask", syncPass) and its answer is read off the slot. The start: an empty slot, else the lower item level.
--    One place to go, a fight, a window open: X is the slot's secure cell as before. Gear the game won't let this character wear
--    (`nouse`): nothing opens ("equip-nouse"; the app's X is faint).
--  * a finger: bmvq 26, then column slot - 1 names the target (the bag bar's 26 + column, WC._bagsTargetTap); and the C10 hole: while
--    the room holds outside its grid, a worn-slot column with no fresh 26 is SWALLOWED (it was a pick of the worn piece, which a later
--    drop would have taken off).
--  * the worn rows say their equip location (`equip`): the Character room's rail greys a two-hander over a worn off hand (and an off-hand
--    piece under a worn two-hander) when the bags are full.
--  * the Character room's Bind to a Button for a worn piece (Character c19) names its slot as the Unequip arm does: WC._bagsUnequipArm
--    ("bind"), then the bmvq column; the slot goes to WC.character.bindWorn.
-- Plain rows and the game's own calls only: no protected call from here in a fight, no Blizzard open / close, no binding, macro or CVar.
-- bags-de1 (the user's list, 2026-11-16: "Disenchant (and similar actions): A custom grid of bag items that can be disenchanted. Hover shows the
-- full tooltip, as in Bags. It stays open after each one; B closes it. The disenchant cast bar is visible."; their picks the same day: every
-- RECOMMENDED option of design_disenchant.md): THE DISENCHANT GRID, the room's mode "bulk" (DE, below KIT): the bags' green-and-better
-- weapons and armour in bag order, the full tooltip by the cursor, ONE press = one disenchant through the item's own secure cell, the grid
-- stays open, B stops a cast / a waiting pick / closes (back to the Bags grid it came from, else Home). Blue and purple ask once ("Disenchant?"
-- A Yes / B No); a small green arrow on a tile whose stat changes are all gains; the strip's Disenchant icon opens it (BAR "de"); an auto loot
-- keeps the grid (Interact i161), any other loot pop-up takes the pad and the grid takes it back at LOOT_CLOSED. With it, the pick's target
-- cells can never equip any more (the shapes "t" / "c" / "u", KIT.cellFor), binv 39 is /stopcasting, KIT.isCurrent / spellDoc ask C_Spell
-- first, and END_BOUND_TRADEABLE is handed to the game like the other pick questions. No new pad row (bmvq 27 + column 9 for a finger on
-- the icon; binv's own cells); nothing saved; no game window opened or closed from here.
-- Review of bags-de1 (before release; the user's words above: "It stays open after each one"): WC.S never answers nil - a nil, a secret
-- or a throw comes back as a one-byte SENTINEL string (Safety.lua) - so the grid's "a cast runs" read every idle moment as a cast and A
-- was refused "busy" for good; every game string the grid reads now passes KIT.txt (this file's own byte(1) > 3 test) first. A pick of
-- ANOTHER spell left waiting (a kit, an enchant, Feed Pet) is never answered by the grid's A (refused "pick", the A greyed).
-- bags-voice1 (the user, 2026-11-16: voice to text in the message box and ALL search boxes, "A opens the keyboard, X opens our own voice
-- prompt"; the pick: "Every box that takes words ... Bags search"): X on the strip's Search is the same request as A (BAR.act "search":
-- mode "search", Start held from the game, the app's own reader types) with the act named "voice", so the app opens the keyboard on its
-- voice page. A finger on the box's microphone is the strip's touch X (bmvq 22). Nothing of the game's is called: the words stay in the
-- app (the search dims the grid there), exactly as typed ones.
-- bags-swap1 (Character c20; the user, 2026-11-16: "on weapons that are both 1 hand or can actually be swapped to the offhand or vice versa
-- then that option should be listed in the pop out options."): THE SWAP BETWEEN TWO WORN SLOTS for the Character room's pop-out (the other
-- hand's one-hander, the other ring, the other trinket). Character.lua's arm "wswap" = WC._bagsUnequipArm("swap"); the next bmvq column
-- (slot - 1) names the slot the piece comes FROM, the one after it the slot it goes TO, each within UQ.TTL (both columns' doubled presses
-- swallowed, never a pick or a drop). WC.bagsSwapWorn moves it the game's own way, the paper doll's drag: PickupInventoryItem(from),
-- EquipCursorItem(to); what was in TO goes back into FROM when it may go there, else first into the first free plain bag slot (none:
-- "full", nothing moved). Refused in a fight (no insecure pick-up then), with anything on the cursor, inside SETTLE, an empty FROM, a piece
-- the game's slot rules keep out of TO ("bad": EQ.gearSlots, a one-hander's off hand only for a class that dual wields). The answer goes
-- to WC.character.swapAck (and the drag's reply, by "swap"), confirmed 0.6 s later. No window opened or closed, nothing written.
-- bags-swap2 (Character c21; THE USER, 2026-11-16, picked "Allow it in fights": the hand swap works in combat; if the game refuses, the
-- pop-out says so): THE WEAPON HAND SWAP IN A FIGHT goes the way weapon Unequip already goes in one (uneq1's route): the game's own
-- pick-up and EquipCursorItem as the call itself, WATCHED (ADDON_ACTION_BLOCKED / FORBIDDEN naming our add-on, or the piece never
-- reaching the cursor / not landing: the cursor is cleared so the piece goes back on). There is no secure route for a worn-to-worn move
-- (the secure templates' "item" action only equips a bag piece or uses one: Replace's bag secure cell; type=macro is ruled out), and
-- neither call is protected. Only BOTH hands (16 and 17: UQ.WEAPON) are tried in a fight; rings, trinkets and anything else stay out of
-- a fight ("combat", fightRefused). A block = "combat" (the app: "In combat"), any other refusal = "refused" ("The game refused it"),
-- bags full for a displaced shield = "full"; a block arriving after the answer is answered by the watcher as a swap (UQ.doneReply).
-- Only on the player's press (the arm + two columns); nothing written, no window opened or closed.

local ADDON = ...
local WC = _G.WowCompanion
local S, SN, CALL = WC.S, WC.SN, WC.CALL
local BB = { binds = {}, buttons = {}, applied = {} }   -- the old Bind to a Button's binds (bags-bindbox1: applied, migrated; after chordRefusal)

-- CAPTURE v8 (Interact i74, the user's ruling): a room KEEPS the controller through a fight and flashes red. Wherever Bags
-- refuses an action because of the fight, it says so: capture.refused("bags") bumps the fight count and Home pulses the
-- red flash. Safe to call on any combat refusal: it answers false, "no fight" when Bags is not holding through one.
local function fightRefused()
    local cap = WC.capture
    if type(cap) == "table" and type(cap.refused) == "function" then pcall(cap.refused, "bags") end
end

-- 0 = backpack, 1..4 = equipped bags. NOT 5+: on the Classic lineage 5..10 are BANK bags, which
-- are only readable while the bank is open. The user ruled a bank you can browse from anywhere
-- too confusing, so the bank must never leak in through a loop bound.
local PLAYER_BAGS = { 0, 1, 2, 3, 4 }
-- The retail reagent bag IS a player bag. Include it only if this build defines it.
local REAGENT_BAG = nil
do
    local rb = S(function() return Enum.BagIndex.ReagentBag end)
    if type(rb) == "number" and rb > 4 then PLAYER_BAGS[#PLAYER_BAGS + 1] = rb; REAGENT_BAG = rb end
end

-- Field names on the container-info table are a modern-API guess, so read them defensively:
-- if a name is wrong we get the ABSENT sentinel rather than a crash, and the miss is visible
-- on the wire instead of silently producing an empty room.
local function field(tbl, key)
    if type(tbl) ~= "table" then return nil end
    return S(function() return tbl[key] end)
end

local function flag(tbl, key)
    local v = field(tbl, key)
    if type(v) == "boolean" then return v end
    return nil
end

-- bags-open1: BOXES. OPEN.click = the last click of one of our bag cells (its PreClick, before the game's own right-click runs), so a
-- loot window that opens right after it is the room's own (OPEN.mine, set further down where the controller state is known).
local OPEN = { TTL = 5, click = nil, under = nil, LINES = 30 }
local function gameWord(name)
    local v = S(function() return _G[name] end)
    if type(v) == "string" and v ~= "" and v:byte(1) > 3 then return v end
    return nil
end
-- the item's own tooltip, read only: does the game say LOCKED ("Locked") or ITEM_OPENABLE ("<Right Click to Open>")? (locked, opens)
function OPEN.read(bag, slot)
    if not (C_TooltipInfo and C_TooltipInfo.GetBagItem) then return false, false end
    local ok, data = CALL(function() return C_TooltipInfo.GetBagItem(bag, slot) end)
    local lines = ok and type(data) == "table" and data.lines
    if type(lines) ~= "table" then return false, false end
    local lk, op = gameWord("LOCKED"), gameWord("ITEM_OPENABLE")
    local locked, opens = false, false
    for i = 1, math.min(#lines, OPEN.LINES) do
        local ln = lines[i]
        local t = type(ln) == "table" and S(function() return ln.leftText end) or nil
        if type(t) == "string" then
            t = t:match("^%s*(.-)%s*$")
            if lk and t == lk then locked = true end
            if op and t == op then opens = true end
        end
    end
    return locked, opens
end
-- a box worth a tooltip read at a bags collect: the game says it has loot, or a Miscellaneous item (class 15: the lockboxes, clams and
-- bags of loot) that is not junk and has no other action. The X press reads it anyway (OPEN.lockedAt)
function OPEN.candidate(row, hasLoot)
    if hasLoot == true then return true end
    return row.cls == 15 and row.quality ~= 0 and not (row.use or row.readable or row.equip or row.quest or row.qstart)
end
-- the box at bag / slot is LOCKED now (fresh, for the X press)
function OPEN.lockedAt(bag, slot)
    local locked = OPEN.read(bag, slot)
    return locked == true
end

-- ---------------------------------------------------------------- gear: item level + stats
-- USER RULING 2026-09-27: the item panel compares gear in the bags with what is worn in that
-- slot, with FULL stat differences like the game's comparison tooltip. So every equippable bag
-- item and every worn item carries its item level and its stats. The app does the diff: an item
-- can go in two slots (rings, trinkets, one-handers) and the app shows it against each.
--
-- MEASURED 2026-09-27: GetItemInfoInstant returns "INVTYPE_NON_EQUIP_IGNORE", not "", for an
-- item that cannot be worn (food, quest items, a book). An empty-string test sent it as a slot
-- and the room offered EQUIP on wolf meat.
local NOT_WORN = { INVTYPE_NON_EQUIP_IGNORE = true, INVTYPE_BAG = true, INVTYPE_QUIVER = true }

local function ilvlOf(link)
    if type(link) ~= "string" then return nil end
    local v = SN(function() return C_Item.GetDetailedItemLevelInfo(link) end)
    if v then return v end
    local ok, _, _, _, lvl = CALL(function() return C_Item.GetItemInfo(link) end)
    return ok and SN(function() return lvl end) or nil
end

-- { {k = "ITEM_MOD_STAMINA_SHORT", n = "Stamina", v = 3}, ... }, sorted by key.
-- n is the game's own label (_G[k]), so the app never carries a stat-name table.
local function statsOf(link)
    if type(link) ~= "string" then return nil end
    local ok, t = CALL(function() return C_Item.GetItemStats(link) end)
    if not (ok and type(t) == "table") then ok, t = CALL(function() return GetItemStats(link) end) end
    if not (ok and type(t) == "table") then return nil end
    local out = {}
    local okP = pcall(function()
        for k, v in pairs(t) do
            local key = S(function() return k end)
            local val = SN(function() return v end)
            if type(key) == "string" and key ~= "" and key:byte(1) > 3 and val then
                local label = S(function() return _G[key] end)
                out[#out + 1] = { k = key, v = val,
                    n = (type(label) == "string" and label ~= "" and label:byte(1) > 3) and label or key }
            end
        end
    end)
    if not okP then return nil end
    table.sort(out, function(a, b) return a.k < b.k end)
    return out
end

-- ---------------------------------------------------------------- open-window state
-- A right-click on a bag slot means something else while certain windows are open: SELL at a
-- vendor, DEPOSIT at the bank or guild bank, ATTACH on the mail Send tab, TRADE, and the
-- auction house takes it for posting. USER RULING: the button says what it will actually do.
local ctx = nil
local CTX_OPEN = {
    MERCHANT_SHOW = "merchant", BANKFRAME_OPENED = "bank", GUILDBANKFRAME_OPENED = "guildbank",
    MAIL_SHOW = "mail", TRADE_SHOW = "trade", AUCTION_HOUSE_SHOW = "auction",
}
local CTX_CLOSE = {
    MERCHANT_CLOSED = "merchant", BANKFRAME_CLOSED = "bank", GUILDBANKFRAME_CLOSED = "guildbank",
    MAIL_CLOSED = "mail", TRADE_CLOSED = "trade", AUCTION_HOUSE_CLOSED = "auction",
}
-- bags-take1: ctx is believed only while ITS window is really shown (one table: the main chunk's local count). A close event the
-- add-on never saw left ctx set for good, and every take after it was skipped "take-skip-window" with no window up (1478, the user
-- 2026-11-16: "my bag is behaving weird and opening the bag on top for some reason"). GRACE: the open event may come a moment before
-- the game's own frame shows. Read-only: IsShown only, nothing in the game is called.
local CTXW = { GRACE = 1.0, at = 0, stale = nil,
               FRAME = { merchant = "MerchantFrame", bank = "BankFrame", guildbank = "GuildBankFrame", mail = "MailFrame",
                         trade = "TradeFrame", auction = "AuctionHouseFrame" } }
function CTXW.shown(n)
    local f = n and _G[n]
    local ok, shown = pcall(function() return f and f:IsShown() end)
    return (ok and shown) and true or false
end
-- the open window's name now, a stale one let go (CTXW.stale keeps its name for the next report)
function CTXW.now()
    if not ctx then return nil end
    if CTXW.shown(CTXW.FRAME[ctx]) then return ctx end
    if (GetTime() or 0) - CTXW.at < CTXW.GRACE then return ctx end
    CTXW.stale = ctx; ctx = nil
    return nil
end
-- the mailbox: only the Send tab attaches; on the Inbox tab a right-click uses the item as usual
local function mailSendShown()
    local ok, shown = pcall(function() return SendMailFrame and SendMailFrame:IsShown() end)
    return ok and shown == true
end

-- ---------------------------------------------------------------- item actions: the tap pad
-- USER RULING 2026-09-27: one shared tap pad for every room. Bags owns one SECURE row per bag,
-- "bag0".."bag4" (+ the reagent bag), one cell per slot: type=item, item="<bag> <slot>". A tap
-- on a cell is a click, i.e. exactly what a right-click on that slot does -- USE / EQUIP / READ,
-- or SELL / DEPOSIT / ... while that window is open. The attributes are static, so nothing has
-- to change in combat, and a click works in combat wherever the game allows the item (the
-- user's "contextual" ruling). Rows are (re)built only when a bag's size changes; TapPad does
-- all secure work out of combat and queues it otherwise.
local rowSlots = {}
-- THE GAME'S OWN BAG WINDOW ON EVERY ROUTE (the user 2026-10-01 via Home: "it should act the EXACT same for all rooms";
-- "Every room opens the game's own window behind it, as Professions does"). Two spare cells of the backpack's secure
-- row: col 39 OPENS the game's bags, col 38 CLOSES them, each a SECURE CLICK of the game's own backpack button
-- (MainMenuBarBackpackButton: its click is the game's ToggleBag(0) = the combined bags the bag key opens). Never an
-- Open / Close / Toggle from addon code (the 09-28 taint). The click toggles, so each cell is made a no-op whenever it
-- would do the wrong thing: open only while the bags are hidden, close only while they show. Attributes cannot change
-- in a fight, so the cells are switched out of combat only; in a fight `gameDoors` is false and the app uses the old
-- take / leave columns (bmvq 29 / 30).
-- bags-empty1 (issue #29): a secure "item" cell with an empty slot gets no secure action for that click (see the header).
-- isEmpty() answers true only when the game says the slot holds nothing; lastEmpty = the last empty click, for the tap proof.
local lastEmpty = { at = -math.huge, key = nil, fight = false, off = false }
local guarded = {}                                      -- bags-empty2: every guarded cell (a set), for the fight's switch
local function emptyGuard(btn, key, isEmpty)
    btn.wcSlotKey, btn.wcIsEmpty = key, isEmpty
    guarded[btn] = true
    if btn.wcEmptyHooked then return end
    btn.wcEmptyHooked = true
    pcall(btn.HookScript, btn, "PreClick", function(self)
        -- bags-open1: a bag cell of ours is clicked (the X route or a finger's Open): a loot window right after it is the room's own
        local k = self.wcSlotKey
        if type(k) == "string" and k:sub(1, 1) == "b" then OPEN.click = { key = k, at = GetTime() } end
        local f = self.wcIsEmpty
        if type(f) ~= "function" then return end
        local okE, empty = pcall(f)
        if not (okE and empty == true) then return end
        local fight = InCombatLockdown() and true or false
        lastEmpty.at, lastEmpty.key, lastEmpty.fight = GetTime(), self.wcSlotKey, fight
        lastEmpty.off = fight and self:GetAttribute("type") ~= "item"
        if fight or self:GetAttribute("type") ~= "item" then return end
        self:SetAttribute("type", nil)
        self.wcEmptyOff = true
    end)
    pcall(btn.HookScript, btn, "PostClick", function(self)
        if self.wcEmptyOff and not InCombatLockdown() then
            self:SetAttribute("type", "item")
            self.wcEmptyOff = nil
        end
    end)
end
-- the tap proof's flags: this click (key) found its slot empty just now
local function emptyFlags(t, key)
    if lastEmpty.key == key and GetTime() - lastEmpty.at < 1 then
        if lastEmpty.fight then t.emptyFight = true; t.fightOff = lastEmpty.off or nil else t.empty = true end
    end
    return t
end
-- bags-empty2: the fight's switch. PLAYER_REGEN_DISABLED: empty cells off for the fight; PLAYER_REGEN_ENABLED: back to "item".
local function emptyFightOff()
    if InCombatLockdown() then return end
    for btn in pairs(guarded) do
        local okE, empty = pcall(btn.wcIsEmpty)
        if okE and empty == true and btn:GetAttribute("type") == "item" and pcall(btn.SetAttribute, btn, "type", nil) then
            btn.wcFightOff = true
        end
    end
end
local function emptyFightOn()
    if InCombatLockdown() then return end
    for btn in pairs(guarded) do
        if btn.wcFightOff and pcall(btn.SetAttribute, btn, "type", "item") then btn.wcFightOff = nil end
    end
end
local function bagSlotEmpty(bag, slot)
    return function()
        local ok, id = CALL(function() return C_Container.GetContainerItemID(bag, slot) end)
        return ok and id == nil
    end
end
local function wornSlotEmpty(slot)
    return function()
        local ok, id = CALL(function() return GetInventoryItemID("player", slot) end)
        return ok and id == nil
    end
end
local DOOR_OPEN, DOOR_CLOSE = 39, 38
local doors = { open = nil, close = nil, stale = true }
local syncDoors                                         -- set once the bag windows are known (below)

function WC.bagsPadRows(bagList)
    if not (WC.pad and WC.pad.secureRow) then return end
    for _, e in ipairs(bagList) do
        local bag, n = e.bag, e.slots
        if rowSlots[bag] ~= n then
            rowSlots[bag] = n
            -- the backpack's row has all 40 cells: its slots, then the two doors (no backpack has 38 slots)
            local cells = (bag == 0 and n < DOOR_CLOSE) and (DOOR_OPEN + 1) or n
            pcall(WC.pad.secureRow, "bag" .. bag, cells, function(btn, col)
                if col < n then
                    btn:SetAttribute("type", "item")
                    btn:SetAttribute("item", bag .. " " .. (col + 1))
                    emptyGuard(btn, "b" .. bag .. ":" .. (col + 1), bagSlotEmpty(bag, col + 1))
                elseif col == DOOR_OPEN then doors.open = btn
                elseif col == DOOR_CLOSE then
                    doors.close = btn
                    -- the close door is the app's non-B leave (another room picked): mark it just BEFORE the secure click
                    -- (the windows' OnHide runs inside it), so the release says "room", not "B"
                    pcall(btn.HookScript, btn, "PreClick", function() doors.closingAt = GetTime() end)
                end
            end, function(col)
                if bag == 0 and (col == DOOR_OPEN or col == DOOR_CLOSE) then
                    WC.emit({ type = "cap", bagsDoor = { act = (col == DOOR_OPEN) and "open" or "close",
                        combat = InCombatLockdown() and true or false } })
                    return
                end
                -- char-more1: a kit used from its own cell (the Character room's Apply / Enchant) names the pick that follows
                if WC._kitUsedAt then pcall(WC._kitUsedAt, bag, col + 1) end
                -- the proof a tap landed (the secure action itself already ran)
                WC.emit({ type = "cap", bagsTap = emptyFlags({ bag = bag, slot = col + 1,
                    combat = InCombatLockdown() and true or false }, "b" .. bag .. ":" .. (col + 1)) })
            end)
            if bag == 0 and syncDoors then syncDoors() end
        end
    end
end

-- The bag window's own CLEAN UP (user ruling 2026-09-27: the sort button runs the GAME's sort,
-- so the real bags are reordered and the grid still matches the game 1:1). One plain pad cell,
-- "bagsort". Refused in combat, as the game refuses it. The reordering itself comes back as
-- ordinary BAG_UPDATE_DELAYED traffic.
if WC.pad and WC.pad.op then
    -- bags-onerow1: 40 columns now; column >= 10 (MV.BASE) names the bag of the next "bmv" tap (WC._bagsNameTap, below), never a sort
    pcall(WC.pad.op, "bagsort", "bagsort", function(_, col)
        col = col or 0
        if col >= 10 then if WC._bagsNameTap then WC._bagsNameTap(col) end; return end
        if col ~= 0 then return end
        if InCombatLockdown() then
            WC.emit({ type = "cap", bagsSort = { ok = false, why = "combat" } })
            fightRefused()
            return
        end
        local ok = CALL(function() return C_Container.SortBags() end)
        if not ok then ok = CALL(function() return SortBags() end) end
        WC.emit({ type = "cap", bagsSort = { ok = ok and true or false } })
    end)
end

-- ---------------------------------------------------------------- drag & drop on the tap pad
-- USER RULINGS 2026-09-28: drags bag->bag (move / swap / stack), bag->equipment slot (equip),
-- equipment slot->bag (unequip). THE LOCK, AMENDED FOR DRAGS: one drag = one game action, sent as
-- a NO-ACTION "pick" tap and then the action tap. The pick only records the source here; the
-- action tap does the pick-up AND the put-down in ONE handler, so nothing is ever left on the
-- game's cursor between two taps -- a tap that goes astray leaves no item hanging.
--
-- Rows: plain (insecure) rows "bmv" (column = slot - 1, the bag named just before on bagsort: bags-onerow1) and "bmvq"
-- (column = worn slot - 1), registered at this file's load ("bmv" pinned to the pad's last row). Equipment changes are refused in combat; a pick expires after 10 s.
local PICK_TTL = 10
local picked = nil          -- { kind = "bag", bag, slot, id } or { kind = "inv", slot, id }
-- MEASURED 2026-09-28 14:27 (3 drags, all OK): every drop was followed ~3 ms later by a SECOND
-- press on the same drop cell. The app sends exactly two taps; the extra comes from the input
-- path. It was harmless (the moved item had not arrived, so it read "empty"), but one frame later
-- it would PICK the item just moved, and the next drag's first tap would drop it somewhere. So a
-- press within SETTLE seconds of a finished move is ignored. A real drag's pick + drop both come
-- BEFORE the move, so they are never caught by this.
local SETTLE = 0.4
local movedAt = -100

local function mvReply(t) WC.emit({ type = "cap", bagsMove = t }) end


local function settling()
    if (GetTime() - movedAt) < SETTLE then mvReply({ ok = false, why = "settle" }); return true end
    return false
end

-- bags-act1 (bags_design_1113.md: "Change cursorBusy() to GetCursorInfo() == nil"): ANYTHING on the cursor (a spell, money, a macro
-- as well as an item) is busy; GetCursorInfo answers a kind string, nil when empty. CursorHasItem stays as the second test.
local function cursorBusy()
    local okI, kind = CALL(function() return GetCursorInfo() end)
    if okI and type(kind) == "string" then return true end
    local ok, busy = CALL(function() return CursorHasItem() end)
    return ok and busy == true
end

-- put back anything the two pick-ups left on the cursor (a refused swap, a slot it cannot go in)
local function settle()
    if cursorBusy() then CALL(function() return ClearCursor() end); return false end
    return true
end

local function bagItemID(bag, slot) return SN(function() return C_Container.GetContainerItemID(bag, slot) end) end
local function invItemID(slot) return SN(function() return GetInventoryItemID("player", slot) end) end

local function fresh() return picked and (GetTime() - picked.t) <= PICK_TTL end

local function pickupBag(bag, slot)
    return CALL(function() return C_Container.PickupContainerItem(bag, slot) end)
end

-- a tap on a bag cell: a pick (nothing held), or the drop of a pick onto this slot
local function onBagCell(bag, slot)
    if settling() then return end
    if not fresh() then
        local id = bagItemID(bag, slot)
        if not id then mvReply({ ok = false, why = "empty", to = { bag = bag, slot = slot } }); picked = nil; return end
        picked = { kind = "bag", bag = bag, slot = slot, id = id, t = GetTime() }
        mvReply({ picked = { bag = bag, slot = slot, id = id } })
        return
    end
    local p = picked; picked = nil
    local to = { bag = bag, slot = slot }
    if p.kind == "bag" and p.bag == bag and p.slot == slot then mvReply({ ok = false, why = "same", to = to }); return end
    -- SPEC_COMBAT item 4 (the user, agreed 2026-10-01: "fully usable: everything the game allows in combat"): moving an item
    -- between bag slots is allowed in a fight; only taking worn gear off (an equipment change) is refused, as the game does
    if p.kind ~= "bag" and InCombatLockdown() then mvReply({ ok = false, why = "combat", to = to }); fightRefused(); return end
    if cursorBusy() then mvReply({ ok = false, why = "cursor", to = to }); return end
    if p.kind == "bag" then
        if bagItemID(p.bag, p.slot) ~= p.id then mvReply({ ok = false, why = "moved", to = to }); return end
        pickupBag(p.bag, p.slot); pickupBag(bag, slot)
    else    -- unequip onto this bag slot
        if invItemID(p.slot) ~= p.id then mvReply({ ok = false, why = "moved", to = to }); return end
        CALL(function() return PickupInventoryItem(p.slot) end); pickupBag(bag, slot)
    end
    local clean = settle()
    movedAt = GetTime()
    mvReply({ ok = clean, why = (not clean) and "refused" or nil,
              from = { kind = p.kind, bag = p.bag, slot = p.slot }, to = to })
end

-- ---------------------------------------------------------------- moves for another window (the bank)
-- Interact's BANK window (the user, 2026-09-30: "We are missing the bank") moves items through these two, never with a
-- pick-up of its own: ONE call = the pick and the put-down, with the drag's guards, so one press stays one move.
-- The bank is only ever touched while the game's bank window is open (it is hidden by alpha then, still shown): the
-- ruling "no bank browsable from anywhere" stands, and nothing here sends the bank's contents anywhere.
-- [kind] "bank" = the character bank, "account" = the Warband bank (the user picked the sheet with both tabs, 2026-09-30)
local function bankType(kind) return kind == "account" and "Account" or "Character" end

local function bankOpen(kind)
    local ok, shown = pcall(function() return _G.BankFrame and _G.BankFrame:IsShown() end)
    if not (ok and shown == true) then return false end
    -- and the game agrees that bank can be used now (where this build has the call); the Warband bank needs the call
    local okU, can = CALL(function() return C_Bank.CanUseBank(Enum.BankType[bankType(kind)]) end)
    if okU and type(can) == "boolean" then return can end
    return kind ~= "account"
end

local function inList(list, v) for _, x in ipairs(list) do if x == v then return true end end return false end

-- The bank's containers ON THIS CLIENT (Interact read its BagIndex enum and its camelot bank frame, 2026-09-30): -1 is the
-- KEYRING here, not the bank, and 5 the reagent bag. The character bank is CharacterBankTab_1..9 = 6..14. The game's own
-- frame lists the BOUGHT tabs with C_Bank.FetchPurchasedBankTabData, in its order (the first one is the main bank). The
-- Warband bank is AccountBankTab_1..9 = 15..23 ("account": the user picked the sheet with its tab).
local function bankBags(kind)
    local out = {}
    local function add(id)
        if id and id > 0 and not inList(PLAYER_BAGS, id) and not inList(out, id) then out[#out + 1] = id end
    end
    local okT, tabs = CALL(function() return C_Bank.FetchPurchasedBankTabData(Enum.BankType[bankType(kind)]) end)
    if okT and type(tabs) == "table" then
        for _, t in ipairs(tabs) do add(SN(function() return t.ID end)) end
    end
    if #out == 0 then       -- a build without that call: the enum's names (a tab not bought has no slots)
        local pre = kind == "account" and "AccountBankTab_" or "CharacterBankTab_"
        for i = 1, 9 do add(SN(function() return Enum.BagIndex[pre .. i] end)) end
    end
    return out
end

-- a container a move may use: the player's bags always, a bank's only while that bank can be used
local function movable(bag, slot)
    if type(bag) ~= "number" or type(slot) ~= "number" or slot < 1 then return false, "bad" end
    if inList(bankBags("bank"), bag) then if not bankOpen("bank") then return false, "closed" end
    elseif inList(bankBags("account"), bag) then if not bankOpen("account") then return false, "closed" end
    elseif not inList(PLAYER_BAGS, bag) then return false, "bad" end
    local n = SN(function() return C_Container.GetContainerNumSlots(bag) end) or 0
    if slot > n then return false, "bad" end
    return true
end

function WC.bagsMove(fromBag, fromSlot, toBag, toSlot)
    local to = { bag = toBag, slot = toSlot }
    local function no(why) mvReply({ ok = false, why = why, to = to, by = "call" }); return false, why end
    local okF, whyF = movable(fromBag, fromSlot); if not okF then return no(whyF) end
    local okT, whyT = movable(toBag, toSlot); if not okT then return no(whyT) end
    if (GetTime() - movedAt) < SETTLE then return no("settle") end
    if fromBag == toBag and fromSlot == toSlot then return no("same") end
    if not bagItemID(fromBag, fromSlot) then return no("empty") end
    if InCombatLockdown() then return no("combat") end
    if cursorBusy() then return no("cursor") end
    picked = nil                                   -- a half-done drag of the Bags room is void now
    pickupBag(fromBag, fromSlot); pickupBag(toBag, toSlot)
    local clean = settle()
    movedAt = GetTime()
    mvReply({ ok = clean, why = (not clean) and "refused" or nil, from = { kind = "bag", bag = fromBag, slot = fromSlot },
              to = to, by = "call" })
    if clean then return true end
    return false, "refused"
end

-- uneq1 UNEQUIP FROM THE CHARACTER ROOM (the user found it: "no way to unequip gear"; the user's picks 2026-11-13, E:/wow-build unequip
-- design C: "Unequip lives in the Character room: choose a worn slot, Y = More opens a small menu with 'Unequip' first"; "WEAPONS
-- (slots 16,17,18) MAY be unequipped in combat, armor is refused in combat"; "Target: first free plain-bag slot, backpack first; bags
-- full -> row disabled 'Bags full'; ammo (slot 0) not offered"; "Never let a stale bag pick equip anything").
-- ONE CALL = the game's own pick-up of the worn piece and its put-down in the first free plain bag slot (the game's paper doll does
-- PickupInventoryItem, then a click on a bag slot), so nothing is left on the cursor. No window opened or closed, nothing written.
-- THE ROUTE: Character.lua's arming cell "uneq" (its `eqset` namespace on Auras' row auracancel: no new pad row) calls
-- WC._bagsUnequipArm; the NEXT bmvq column 0..18 within UQ.TTL names the worn slot (column = slot - 1) and is taken here, never by
-- onInvCell, so a drag pick left by the Bags room can never be dropped (= equipped) by it: the arm also voids that pick. A slot tap
-- arriving later (up to PICK_TTL) is swallowed ("late"), and the doubled press the input path adds is swallowed for SETTLE.
-- IN A FIGHT: armour is refused (fightRefused, the game's own rule); a weapon (16 main hand, 17 off hand, 18 ranged / relic) is tried.
-- No secure route exists for this (the secure templates' "item" action only equips or uses; type=macro is ruled out: no macros), so
-- the pick-up is the insecure call itself, watched: if the game blocks it (ADDON_ACTION_BLOCKED / FORBIDDEN naming our add-on) or the
-- piece never reaches the cursor, the cursor is cleared (the piece goes back on) and the answer is "combat" (the app: "In combat").
-- UNMEASURED on the device: whether the game allows it in a fight (decision 2's measurement, a device check).
local UQ = { at = nil, TTL = 2.0, WEAPON = { [16] = true, [17] = true, [18] = true }, watchAt = nil, blocked = nil,
             swallowCol = nil, swallowUntil = 0, WATCH = 0.5 }
function UQ.reply(t)
    t.by = "unequip"
    mvReply(t)
    local C = WC.character
    if type(C) == "table" and type(C.unequipAck) == "function" then pcall(C.unequipAck, t) end
end
-- bags-swap1: a swap's answer, to the Character room (Character c20's swapAck) and as the drag's reply
function UQ.swapReply(t)
    t.by = "swap"
    mvReply(t)
    local C = WC.character
    if type(C) == "table" and type(C.swapAck) == "function" then pcall(C.swapAck, t) end
end
-- the game's blocked / forbidden events: ours during an unequip's pick-up (synchronous, or a moment after it)
do
    local bf = CreateFrame("Frame")
    pcall(function() bf:RegisterEvent("ADDON_ACTION_BLOCKED"); bf:RegisterEvent("ADDON_ACTION_FORBIDDEN") end)
    pcall(bf.SetScript, bf, "OnEvent", function(_, event, addon, what)
        if event ~= "ADDON_ACTION_BLOCKED" and event ~= "ADDON_ACTION_FORBIDDEN" then return end
        if not UQ.watchAt or (GetTime() - UQ.watchAt) > UQ.WATCH then return end
        if addon ~= nil and addon ~= ADDON then return end
        UQ.blocked = tostring(what or event)
        if UQ.done then                                  -- it came after the call had answered: put the piece back, say so
            UQ.done = nil
            settle()
            fightRefused()
            local reply = UQ.doneReply or UQ.reply       -- bags-swap2: a swap's late block answers as a swap
            UQ.doneReply = nil
            reply({ ok = false, why = "combat", to = UQ.done_at, late = true, blocked = UQ.blocked })
        end
    end)
end

-- Unequip worn [slot] (1..19) into the first free plain bag slot. Returns true, or false + why
-- (bad / empty / settle / combat / cursor / full / refused). Confirmed 0.6 s later (the slot empties on the next bag update).
function WC.bagsUnequip(slot)
    local at = { inv = slot }
    local function no(why) UQ.reply({ ok = false, why = why, to = at }); return false, why end
    if type(slot) ~= "number" or slot < 1 or slot > 19 or slot % 1 ~= 0 then return no("bad") end    -- 0 = ammo: not offered
    local id = invItemID(slot)
    if not id then return no("empty") end
    if (GetTime() - movedAt) < SETTLE then return no("settle") end
    local fight = InCombatLockdown() and true or false
    if fight and not UQ.WEAPON[slot] then fightRefused(); return no("combat") end
    if cursorBusy() then return no("cursor") end
    picked = nil                                   -- a half-done drag of the Bags room is void now
    local bag, s = WC.bagsFirstEmpty("bags", id)
    if not bag then return no("full") end
    UQ.blocked, UQ.done = nil, nil
    UQ.watchAt = GetTime()
    CALL(function() return PickupInventoryItem(slot) end)
    if UQ.blocked or not cursorBusy() then         -- blocked, or nothing came onto the cursor: nothing moved
        settle(); movedAt = GetTime()
        if fight then fightRefused() end
        return no(fight and "combat" or "refused")
    end
    pickupBag(bag, s)
    local clean = settle()
    movedAt = GetTime()
    if UQ.blocked or not clean then                -- the put-down refused: settle() put the piece back on
        if fight then fightRefused() end
        return no(fight and "combat" or "refused")
    end
    UQ.done, UQ.done_at, UQ.doneReply = true, at, UQ.reply   -- a blocked event still to come is answered by the watcher
    UQ.reply({ ok = true, from = at, to = { bag = bag, slot = s }, id = id, fight = fight or nil })
    C_Timer.After(UQ.WATCH, function() UQ.done = nil end)
    C_Timer.After(0.6, function()
        UQ.reply({ confirm = (invItemID(slot) ~= id), at = at, id = id })
    end)
    return true
end

-- the arming tap (Character.lua's "uneq" cell): the next bmvq column 0..18 names the slot
-- bags-gear1: [kind] "bind" (Character c19's "wbind" cell) = that slot's piece goes to the Spellbook's Bind box (WC.character.bindWorn)
-- bags-swap1: [kind] "swap" (Character c20's "wswap" cell) = the next column names the slot FROM, the one after it the slot TO (bagsSwapWorn)
function WC._bagsUnequipArm(kind)
    UQ.at = GetTime()
    UQ.kind = (kind == "bind" or kind == "swap") and kind or nil
    UQ.swapFrom = nil
    picked = nil                                   -- never a drop: a stale bag pick is void from here
end

-- uneq2: the Character room's More menu opened (Character.lua's "moreon"): a Bags drag pick is void from here as well
function WC._bagsVoidPick() picked = nil end

-- a bmvq column 0..18: taken (true) right after the arm, or as its late / doubled press; never reaches onInvCell then
function WC._bagsUnequipTap(col)
    local now = GetTime()
    if col >= 19 then UQ.at = nil; UQ.kind = nil; UQ.swapFrom = nil; return false end   -- uneq2: a Bags-room tap: the arm is stale
    if UQ.swallowCol == col and now < UQ.swallowUntil then return true end      -- the input path's second press
    if UQ.swallowCol2 == col and now < UQ.swallowUntil2 then return true end    -- bags-swap1: the swap's FROM column's second press
    if not UQ.at then return false end
    local age = now - UQ.at
    local kind = UQ.kind
    UQ.at, UQ.kind = nil, nil
    if age > PICK_TTL then UQ.swapFrom = nil; return false end
    -- bags-swap1: the FROM column's doubled press may come after the TO column: it is swallowed too (never a pick of a worn piece)
    if kind == "swap" then UQ.swallowCol2, UQ.swallowUntil2 = col, now + SETTLE + UQ.TTL end
    UQ.swallowCol, UQ.swallowUntil = col, now + SETTLE
    if kind == "swap" then
        -- bags-swap1: the FROM slot; the arm stays for the TO column
        if age > UQ.TTL then UQ.swapReply({ ok = false, why = "late", to = { inv = col + 1 } }); return true end
        UQ.swapFrom, UQ.at, UQ.kind = col + 1, now, "swap2"
        return true
    end
    if kind == "swap2" then
        local from = UQ.swapFrom
        UQ.swapFrom = nil
        if age > UQ.TTL or not from then UQ.swapReply({ ok = false, why = "late", to = { inv = col + 1 } }); return true end
        WC.bagsSwapWorn(from, col + 1)
        return true
    end
    if kind == "bind" then
        -- bags-gear1: the Character room's Bind to a Button names this worn slot (never an unequip, never a drop)
        local C = WC.character
        if type(C) == "table" and type(C.bindWorn) == "function" then pcall(C.bindWorn, col + 1, age > UQ.TTL) end
        return true
    end
    if age > UQ.TTL then UQ.reply({ ok = false, why = "late", to = { inv = col + 1 } }); return true end
    WC.bagsUnequip(col + 1)
    return true
end

-- ONE DELETE for another window: Interact's bags-full loot screen (the user, 2026-10-01: "it asks me what I would like to
-- delete to make space"). The player picks the item AND confirms in that window; this call is the one game action.
-- Never automatic. The slot must still hold [itemID] ("moved"), not in combat, nothing on the cursor, a player bag only.
-- RARE (blue) and EPIC (purple): the user's answer 2026-10-01 for that screen, "Yes, with a second confirmation": refused
-- ("rare") unless the window asked its extra "Are you sure?" and passes [rareConfirmed] = true. Legendary and better (not
-- asked about) stay refused ("legendary"). The Bags room's own Destroy keeps refusing rare and better (its own ruling).
-- Returns true, or false + why.
-- bags-act1: the slot is confirmed at the game's NEXT BAG UPDATE (BAG_UPDATE_DELAYED, WC._bagsDestroyCheck), 1.5 s at most, not at a
-- fixed 0.6 s; [done] (optional) is called once with true (the slot no longer holds the item) or false. One delete waits at a time.
local DESTROYED = { wait = nil, LATEST = 1.5 }
function WC._bagsDestroyCheck(fromEvent)
    local w = DESTROYED.wait
    if not w then return end
    if fromEvent and GetTime() == w.t then return end           -- a bag update queued before the delete itself: not its answer
    local still = bagItemID(w.bag, w.slot)
    -- bags-act2: a bag update that still shows the item (its lock, the server not answered yet) is not the answer: wait on (the
    -- deadline answers either way)
    if fromEvent and still == w.id then return end
    DESTROYED.wait = nil
    WC.emit({ type = "cap", bagsDestroy = { confirm = (still ~= w.id), at = { bag = w.bag, slot = w.slot }, id = w.id } })
    if w.done then pcall(w.done, still ~= w.id) end
end
function WC.bagsDestroy(bag, slot, itemID, rareConfirmed, done)
    local to = { bag = bag, slot = slot }
    local function no(why) WC.emit({ type = "cap", bagsDestroy = { ok = false, why = why, at = to } }); return false, why end
    if type(bag) ~= "number" or type(slot) ~= "number" or not inList(PLAYER_BAGS, bag) then return no("bad") end
    local id = bagItemID(bag, slot)
    if not id then return no("empty") end
    if itemID ~= nil and id ~= itemID then return no("moved") end
    local okI, info = CALL(function() return C_Container.GetContainerItemInfo(bag, slot) end)
    local q = okI and type(info) == "table" and SN(function() return info.quality end) or 0
    if q >= 5 then return no("legendary") end
    if q >= 3 and rareConfirmed ~= true then return no("rare") end
    if InCombatLockdown() then return no("combat") end
    if cursorBusy() then return no("cursor") end
    picked = nil                                   -- a half-done drag of the Bags room is void now
    pickupBag(bag, slot)
    -- nothing came onto the cursor (the slot locked, ...): nothing to delete
    if not cursorBusy() then return no("refused") end
    CALL(function() return DeleteCursorItem() end)
    -- the item stayed on the cursor: the game refused; it goes back where it was
    if not settle() then return no("refused") end
    -- THE ITEM LEFT THE CURSOR = DELETED. The slot itself empties only on the game's next bag update (Interact, the user's
    -- "the item vanishes and it says refused", 2026-10-02: the same-instant slot check saw it still there), so the slot is
    -- confirmed a moment later, logged, and the call answers true now
    WC.emit({ type = "cap", bagsDestroy = { ok = true, at = to, id = id } })
    if DESTROYED.wait then WC._bagsDestroyCheck() end          -- an earlier one still waiting: answered now
    local w = { bag = bag, slot = slot, id = id, done = done, t = GetTime() }
    DESTROYED.wait = w
    C_Timer.After(DESTROYED.LATEST, function() if DESTROYED.wait == w then WC._bagsDestroyCheck() end end)
    return true
end

-- bitwise AND for the bag families (the game has bit.band; a plain fallback for anything without it)
local function band(a, b)
    if bit and bit.band then return bit.band(a, b) end
    local r, p = 0, 1
    while a > 0 and b > 0 do
        local x, y = a % 2, b % 2
        if x == 1 and y == 1 then r = r + p end
        a, b, p = (a - x) / 2, (b - y) / 2, p * 2
    end
    return r
end

-- The first empty slot "bags" | "bank" | "account" -> bag, slot (nil, why when there is none). A special bag (a quiver, a soul bag:
-- the class-only bags, the user's 09-28 ruling) only takes what fits it: with [itemID] its family is checked, without one
-- special bags are skipped. The reagent bag is skipped too (only reagents go in it). Stacks are not merged.
function WC.bagsFirstEmpty(where, itemID)
    local list
    if where == "bank" or where == "account" then
        if not bankOpen(where) then return nil, "closed" end
        list = bankBags(where)
    elseif where == "bags" then
        list = {}
        local rb = SN(function() return Enum.BagIndex.ReagentBag end)
        for _, b in ipairs(PLAYER_BAGS) do if b ~= rb then list[#list + 1] = b end end
    else
        return nil, "bad"
    end
    local itemFam = itemID and SN(function()
        if C_Item and C_Item.GetItemFamily then return C_Item.GetItemFamily(itemID) end
        return GetItemFamily(itemID)
    end)
    for _, bag in ipairs(list) do
        local okF, _, fam = CALL(function() return C_Container.GetContainerNumFreeSlots(bag) end)
        fam = (okF and type(fam) == "number") and fam or 0
        local fits = fam == 0 or (itemFam ~= nil and band(itemFam, fam) ~= 0)
        local n = fits and (SN(function() return C_Container.GetContainerNumSlots(bag) end) or 0) or 0
        for slot = 1, n do
            if not bagItemID(bag, slot) then return bag, slot end
        end
    end
    return nil, "full"
end

-- char-more1: the game's own bind question for a piece being equipped (StaticPopup EQUIP_BIND / _TRADEABLE / _REFUNDABLE; read only)
local function bindAsking()
    for i = 1, 4 do
        local f = _G["StaticPopup" .. i]
        if type(f) == "table" and S(function() return f:IsShown() end) == true then
            local which = S(function() return f.which end)
            if type(which) == "string" and which:find("^EQUIP_BIND") then return true end
        end
    end
    return false
end
local BINDWAIT = { STEP = 0.25, MAX = 60 }
WC._bagsBindWait = BINDWAIT     -- for the harness
-- once the game's question is answered (or after MAX), the piece is put back only if it is STILL on the cursor (a No): anything the
-- player picked up since is left alone
local function settleAfterBind(t0, id)
    if bindAsking() and GetTime() - t0 < BINDWAIT.MAX then
        C_Timer.After(BINDWAIT.STEP, function() settleAfterBind(t0, id) end)
        return
    end
    BINDWAIT.on = nil
    local ok, kind, cid = CALL(function() return GetCursorInfo() end)
    if ok and kind == "item" and cid == id then CALL(function() return ClearCursor() end) end
    movedAt = GetTime()
    if WC._bagsSyncPass then pcall(WC._bagsSyncPass) end          -- char-more2: A / B back to the room
end

-- bags-gear1: THE ONE EQUIP INTO A CHOSEN SLOT (the drag's drop, and the Bags room's slide-out): bag / slot's piece (item id) into worn
-- slot inv, the game's own way (its pick-up, then EquipCursorItem(inv)). Returns "ok", "ask" (the game asks "bind it to you?": the piece
-- stays on the cursor while it asks, its own A / B answer it; settleAfterBind puts it back only if the answer left it there) or the why
-- of a refusal (combat / cursor / moved / refused). Every answer also goes out as the drag's reply (bagsMove).
local function equipTo(bag, slot, inv, id)
    local to = { inv = inv }
    local from = { kind = "bag", bag = bag, slot = slot }
    if InCombatLockdown() then mvReply({ ok = false, why = "combat", to = to }); fightRefused(); return "combat" end
    if cursorBusy() then mvReply({ ok = false, why = "cursor", to = to }); return "cursor" end
    if bagItemID(bag, slot) ~= id then mvReply({ ok = false, why = "moved", to = to }); return "moved" end
    pickupBag(bag, slot)
    CALL(function() return EquipCursorItem(inv) end)
    if bindAsking() then
        -- char-more1: the game asks "bind it to you?" for this piece: its own A / B answer it (Character.lua / the Bags room pass them),
        -- so the cursor is NOT cleared now (that was a No); settleAfterBind puts the piece back only if the answer left it there
        movedAt = GetTime()
        BINDWAIT.on = true
        if WC._bagsSyncPass then pcall(WC._bagsSyncPass) end      -- char-more2: the Bags room's A / B to the game's question
        mvReply({ ok = true, ask = "bind", from = from, to = to })
        local t0 = GetTime()
        C_Timer.After(BINDWAIT.STEP, function() settleAfterBind(t0, id) end)
        return "ask"
    end
    local clean = settle()
    movedAt = GetTime()
    mvReply({ ok = clean, why = (not clean) and "refused" or nil, from = from, to = to })
    return clean and "ok" or "refused"
end

-- a tap on a worn-slot cell: a pick of worn gear, or the drop of a bag pick = equip it there
local function onInvCell(slot)
    if settling() then return end
    if not fresh() then
        local id = invItemID(slot)
        if not id then mvReply({ ok = false, why = "empty", to = { inv = slot } }); picked = nil; return end
        picked = { kind = "inv", slot = slot, id = id, t = GetTime() }
        mvReply({ picked = { inv = slot, id = id } })
        return
    end
    local p = picked; picked = nil
    if p.kind ~= "bag" then mvReply({ ok = false, why = "unsupported", to = { inv = slot } }); return end
    equipTo(p.bag, p.slot, slot, p.id)
end
WC._bagsEquipTo = function(bag, slot, inv, id) return equipTo(bag, slot, inv, id) end     -- bags-gear1: for EQ (below) and the harness

-- bags-onerow1 (the user, 2026-11-13: "Bags share one row"): see the header. MV = the shared drag row's route, also in the bags doc.
local MV = { ROW = "bmv", NAME_ROW = "bagsort", BASE = 10, TTL = 1.5, bag = nil, at = nil }
WC._bagsMV = MV                 -- for the harness
-- a tap on bagsort column BASE + bag: the bag of the NEXT bmv tap (a bag that is not the player's: no name at all)
function WC._bagsNameTap(col)
    local bag = (col or 0) - MV.BASE
    MV.bag, MV.at = nil, nil
    if not inList(PLAYER_BAGS, bag) then mvReply({ ok = false, why = "nobag", bag = bag }); return end
    MV.bag, MV.at = bag, GetTime()
end
-- the named bag, used once; nil when there is none or it is older than TTL
local function takeName()
    local bag, at = MV.bag, MV.at
    MV.bag, MV.at = nil, nil
    if bag == nil or at == nil or (GetTime() - at) > MV.TTL or (GetTime() - at) < 0 then return nil end
    return bag
end
local mvRows = {}
local function mvRegister()
    if not (WC.pad and WC.pad.op) then return end
    if not mvRows.q then
        mvRows.q = true
        -- columns 0..18 = the worn slots; 19 and up = the Bags room by touch (controller mode, below)
        pcall(WC.pad.op, "bmvq", "bmvq", function(_, col)
            col = col or 0
            -- uneq1: right after the Character room's Unequip arm, the column names the worn slot to take off (first: never a drop);
            -- uneq2: a column 19+ (the Bags room) disarms it and goes on below
            if WC._bagsUnequipTap and WC._bagsUnequipTap(col) then return end
            if col >= 19 then if WC._bagsTouch then WC._bagsTouch(col) end; return end
            -- right after a "target" tap (col 26) a column 1..5 names the bag slot for the bag in the hand (EQ); bags-gear1: a column
            -- slot - 1 names the worn slot for the gear of the slide-out
            if WC._bagsTargetTap and WC._bagsTargetTap(col) then return end
            if WC._bagsShutTap and WC._bagsShutTap(col) then return end
            -- bags-gear1 (C10): the room holds outside its grid and no fresh 26 named this column: never a pick of the worn piece
            if WC._bagsSwallowInv and WC._bagsSwallowInv(col) then return end
            onInvCell(col + 1)
        end)
    end
    if not mvRows.s then
        mvRows.s = true
        -- a namespace makes it a 40-column row; the key it carries is not used, only the column. Pinned to the pad's last row.
        pcall(WC.pad.op, MV.ROW, MV.ROW, function(_, col)
            local slot = (col or 0) + 1
            local bag = takeName()
            if not bag then
                -- never a guess: the drag's pick and a pending cursor tap are void too (no game action either way)
                picked = nil
                if WC._bagsCursorVoid then WC._bagsCursorVoid() end
                mvReply({ ok = false, why = "nobag", to = { slot = slot } })
                return
            end
            -- right after a "cursor" tap (bmvq col 36) this tap only moves the controller's cursor here
            if WC._bagsCursorTap and WC._bagsCursorTap(bag, slot) then return end
            if WC._bagsCursorMiss then WC._bagsCursorMiss() end    -- bags-onerow3: a slot tap that moved no cursor
            onBagCell(bag, slot)
        end, { last = true })
    end
end
mvRegister()                    -- at this file's load: bmvq before every lazy row, bmv on the last row
function WC.bagsMoveRows() mvRegister() end

-- item ids whose name had not loaded when we last looked; GET_ITEM_INFO_RECEIVED re-collects.
-- A load is REQUESTED once per id per session: an id the server never answers would otherwise
-- request -> receive(failed) -> re-collect -> request again, every frame.
local pending = {}
local requested = {}
local classOf = {}              -- item id -> { classID, subclassID } (GetItemInfoInstant; never changes for an item)

-- THE RADIAL'S PIN PICKER (Home, the user's sign-off 2026-10-06, PIN_SPEC state 3): the app's "Pin an item" window shows the
-- game's own tooltip beside its grid for whichever USABLE item the cursor is on, so while the picker is open the bags doc
-- carries them all: ptips = { { id, lines }, ... }, one per item id (its first stack in bag order), from Interact's
-- WC.tipOf (no "Currently Equipped": a pin is not a compare). Each bag / slot / id is read ONCE while the picker is open.
-- Bags' own row "bmvq": 23 = the picker opened, 24 = closed; one tap each, nothing per cursor move. On for PICK_TTL s at
-- most (an app that died with the picker open must not leave the tooltips in every doc); the picker taps 23 again while
-- it stays open. One table, so the file's main chunk keeps its local count.
local pinTips = { ON = 23, OFF = 24, TTL = 300, at = nil, cache = {} }
function WC._bagsPinTipsSet(on)
    pinTips.at = on and GetTime() or nil
    pinTips.cache = {}
end
function WC._bagsPinTips(items)
    if not pinTips.at then return nil end
    if (GetTime() - pinTips.at) > pinTips.TTL then pinTips.at = nil; pinTips.cache = {}; return nil end
    if type(WC.tipOf) ~= "function" then return nil end
    local out, seen, cache = {}, {}, {}
    for _, r in ipairs(items) do
        if r.use and r.id and not seen[r.id] then
            seen[r.id] = true
            local key = "bag " .. r.bag .. " " .. r.slot .. " " .. r.id
            local lines = pinTips.cache[key]
            if lines == nil then
                local ok, t = pcall(WC.tipOf, key, { nw = true })     -- bags-nocmp1: only the lines are used, no worn tooltips
                lines = (ok and type(t) == "table" and type(t.lines) == "table") and t.lines or false
            end
            if not r.pending then cache[key] = lines end      -- a name still loading: read it again next time
            if lines then out[#out + 1] = { id = r.id, lines = lines } end
        end
    end
    pinTips.cache = cache
    return out
end

function WC.collectBags()
    -- bags-tip1: no longer forgets every tooltip (that dropped the card back to its stand-in at every refresh); WC._bagsTipKeep
    -- (after the loop) drops only the items gone from their slots, and a changed link is caught where a tooltip is used
    local items, free, total, bags = {}, 0, 0, {}
    local freeGeneral = 0
    pending = {}

    for _, bag in ipairs(PLAYER_BAGS) do
        local okN, slots = CALL(function() return C_Container.GetContainerNumSlots(bag) end)
        if okN and type(slots) == "number" and slots > 0 then
            total = total + slots
            local okF, f, fam = CALL(function() return C_Container.GetContainerNumFreeSlots(bag) end)
            local bagFree = (okF and type(f) == "number") and f or nil
            if bagFree then free = free + bagFree end
            local entry = { bag = bag, slots = slots, free = bagFree }
            -- what the bag takes (the user 2026-10-01, the bags-full loot screen: "only ... bag space that allow lootable
            -- Items (no quivers or reagent bags)"): fam 0 = anything, else only that family; the reagent bag only reagents
            entry.fam = (okF and type(fam) == "number") and fam or nil
            if bag == REAGENT_BAG then entry.reagent = true end
            if bagFree and entry.fam == 0 and not entry.reagent then freeGeneral = freeGeneral + bagFree end
            -- the bag's own item (name + icon) for the room's section label; the backpack has none
            if bag > 0 then
                local inv = SN(function() return C_Container.ContainerIDToInventoryID(bag) end)
                local bid = inv and SN(function() return GetInventoryItemID("player", inv) end)
                if bid then
                    entry.id = bid
                    entry.icon = SN(function() return GetInventoryItemTexture("player", inv) end)
                    local okB, bn = CALL(function() return C_Item.GetItemNameByID(bid) end)
                    if okB then
                        local nm = S(function() return bn end)
                        if type(nm) == "string" and nm ~= "" and nm:byte(1) > 3 then entry.name = nm end
                    end
                end
            end
            bags[#bags + 1] = entry

            for slot = 1, slots do
                local okI, info = CALL(function() return C_Container.GetContainerItemInfo(bag, slot) end)
                if not (okI and type(info) == "table") then info = nil end
                local id = field(info, "itemID")
                -- MEASURED 2026-09-27: a freshly looted stack can have NO info table yet at
                -- BAG_UPDATE_DELAYED -- the free count had already dropped but the two new
                -- stacks were missing from the list until the next heartbeat. The id itself is
                -- available straight away, so the row is sent with what IS known and the name
                -- follows on GET_ITEM_INFO_RECEIVED.
                if type(id) ~= "number" then
                    id = SN(function() return C_Container.GetContainerItemID(bag, slot) end)
                end
                if type(id) == "number" then
                    local row = {
                        bag = bag, slot = slot, id = id,
                        count = field(info, "stackCount"),
                        quality = field(info, "quality"),
                        icon = field(info, "iconFileID"),
                        locked = flag(info, "isLocked"),
                        bound = flag(info, "isBound"),
                        readable = flag(info, "isReadable"),
                        noValue = flag(info, "hasNoValue"),
                    }
                    -- name/type: GetItemInfoInstant is synchronous and never nil, so it is
                    -- the reliable one; GetItemInfo returns 18 values and may be uncached.
                    local okInst, _, itemType, itemSubType, equipLoc, icon2, classID, subclassID =
                        CALL(function() return C_Item.GetItemInfoInstant(id) end)
                    if okInst then
                        row.itype = S(function() return itemType end)
                        row.isub = S(function() return itemSubType end)
                        -- the game's NUMERIC class / subclass (Home's consumables radial, 2026-10-05: food & drink, potion /
                        -- elixir / flask, bandage, usable quest items): the same in every client language, unlike itype /
                        -- isub. Kept per item id (an item's class never changes)
                        local cs = classOf[id]
                        if not cs then
                            cs = { SN(function() return classID end), SN(function() return subclassID end) }
                            classOf[id] = cs
                        end
                        row.cls, row.sub = cs[1], cs[2]
                        local loc = S(function() return equipLoc end)
                        if type(loc) == "string" and loc ~= "" and loc:byte(1) > 3 and not NOT_WORN[loc] then
                            row.equip = loc
                            local link = field(info, "hyperlink")
                            if type(link) ~= "string" then
                                link = S(function() return C_Container.GetContainerItemLink(bag, slot) end)
                            end
                            row.ilvl = ilvlOf(link)
                            row.stats = statsOf(link)
                            -- a piece carried in the bags wears too (f-durability): its percentage, only when it has one
                            local okD, dc, dm = CALL(function() return C_Container.GetContainerItemDurability(bag, slot) end)
                            if okD and WC.isPlain(dc) and WC.isPlain(dm) and type(dc) == "number" and type(dm) == "number" and dm > 0 then
                                row.dur = math.floor(dc / dm * 100)
                            end
                        elseif loc == "INVTYPE_BAG" or loc == "INVTYPE_QUIVER" then
                            row.equip = loc          -- right-click puts a bag in a free bag slot
                        end
                        if type(row.icon) ~= "number" then row.icon = SN(function() return icon2 end) end
                    end
                    -- does it have a "Use:" effect? (the pop-up offers USE only when it does)
                    local okSp, spName = CALL(function() return C_Item.GetItemSpell(id) end)
                    if okSp and type(spName) == "string" then row.use = true end
                    -- an item that enhances another (Item Enhancement: armor kits, stones, oils, enchant scrolls), or seen to start the
                    -- game's "pick an item" (WC._kitLearned): the Character room lists these under a gear slot's Apply
                    if row.use and (row.cls == 8 or (row.cls == 0 and row.sub == 6) or (WC._kitLearned and WC._kitLearned[id])) then row.kit = true end
                    -- char-more1: Replace lists only what this character can wear (WC._bagsNoUse); Enchant leaves out a kit the game
                    -- said no to for the piece worn now (kfy / kfn, WC._kitFitOf)
                    if row.equip and not NOT_WORN[row.equip] and WC._bagsNoUse and WC._bagsNoUse(bag, slot, id) then row.nouse = true end
                    if row.kit and WC._kitFitOf then row.kfy, row.kfn = WC._kitFitOf(id) end
                    -- a quest item or a quest starter, as the game marks it in this slot (sent only when true)
                    local okQ, qi = CALL(function() return C_Container.GetContainerItemQuestInfo(bag, slot) end)
                    if okQ and type(qi) == "table" then
                        if flag(qi, "isQuestItem") == true or type(field(qi, "questID")) == "number" then row.quest = true end
                        -- bags-qstart1 (issue #252): a starter whose quest is neither taken nor done - its right-click starts it
                        local qid = field(qi, "questID")
                        if type(qid) == "number" and qid > 0 and flag(qi, "isActive") ~= true
                           and S(function() return C_QuestLog.IsOnQuest(qid) end) ~= true
                           and S(function() return C_QuestLog.IsQuestFlaggedCompleted(qid) end) ~= true then row.qstart = true end
                    end
                    -- bags-open1: a box. The game's hasLoot (its controller bag's Open), else its tooltip's open line; LOCKED wins over both
                    local hasLoot = flag(info, "hasLoot")
                    if hasLoot == true then row.opens = true end
                    if OPEN.candidate(row, hasLoot) then
                        local lk, op = OPEN.read(bag, slot)
                        if lk then row.lockedBox = true; row.opens = nil
                        elseif op then row.opens = true end
                    end

                    local okNm, nm = CALL(function() return C_Item.GetItemNameByID(id) end)
                    if okNm then row.name = S(function() return nm end) end
                    if type(row.name) ~= "string" or row.name == "" or row.name:byte(1) <= 3 then
                        row.name = nil
                        row.pending = true
                        pending[id] = true
                        if not requested[id] then
                            requested[id] = true
                            CALL(function() return C_Item.RequestLoadItemDataByID(id) end)
                        end
                    end
                    items[#items + 1] = row
                end
            end
        end
    end

    if WC._bagsTipKeep then WC._bagsTipKeep(items) end
    local here = CTXW.now()             -- bags-take1: a stale ctx no longer labels the buttons SELL / DEPOSIT
    if here == "mail" and not mailSendShown() then here = nil end
    WC.bagsPadRows(bags)
    WC.bagsMoveRows(bags)
    WC._bagsLayout = bags               -- the controller's D-pad walks these, in this order
    return {
        items = items, free = free, total = total, bags = bags, money = SN(GetMoney),
        freeGeneral = freeGeneral,          -- free slots in bags that take anything (where loot can go)
        mv = { r = MV.ROW, n = MV.NAME_ROW, b = MV.BASE },   -- bags-onerow1: the shared drag row, the row + first column that name a bag
        -- the game's own open / close of the bags (backpack row cols 39 / 38) can be tapped now
        gameDoors = WC._bagsDoorsLive and WC._bagsDoorsLive() or false,
        -- the old item macros / binds from before the 2026-10-09 ruling, while any exist (BB.old; bmvq 25 cleans them up)
        wcbOld = BB.oldDoc,
        -- the pin picker's tooltips, only while it is open (bmvq 23 / 24)
        ptips = WC._bagsPinTips and WC._bagsPinTips(items) or nil,
        ctx = here,
        de = WC._de and WC._de.stripDoc() or nil,          -- bags-de1: the strip's Disenchant icon (only when the spell is known)
        combat = InCombatLockdown() and true or false,     -- Clean Up is refused in combat
        -- the shared tap pad's geometry: the app taps row "bag<id>", column slot-1. describe()
        -- leaves a secure row OUT while its cells might be stale, so the app never taps blind.
        pad = WC.pad and WC.pad.describe and WC.pad.describe() or nil,
        -- worn gear rides INSIDE the bags section, so the room gets it without a hub change
        equipped = WC.collectEquipped and WC.collectEquipped() or nil,
        -- the controller state rides in EVERY bags table: Core's 15 s heartbeat sends collectBags()
        -- too, and a bags section without it read as "controller off" in the app (found 2026-09-28
        -- 17:42 in the Thor's log before the user's test: the room would have left Bags every 15 s)
        ctl = WC._bagsCtlDoc and WC._bagsCtlDoc() or nil,
    }
end

-- ---------------------------------------------------------------- live bag updates
-- Bag changes used to reach the app only on Core's 15 s heartbeat: WC.snapshot() dedupes on
-- the ACTION BAR alone, so a loot that did not touch the bar was dropped, and so was the
-- GET_ITEM_INFO_RECEIVED that follows it. Bags therefore owns its own events and its own
-- dedupe, and sends a bags-only "full" -- the app merges named sections of a "full", so no
-- other room is disturbed and the hub needs no new message type.
--
-- COST: WC.push() goes through the 0.2 s flush throttle and coalesces within a frame, and a
-- change is sent only when the encoded bags actually differ, so standing still is free and a
-- loot costs at most one flush it would probably have shared with the position stream anyway.
local lastBags = nil
local lastDoc = nil         -- the last bags table sent: a controller press sends its new ctl alone ("bagsctl")
local queued = false
local lastCtl = nil         -- the last ctl the app was sent, in either document

local function sendBags(reason)
    queued = false
    local b = WC.collectBags()
    b.ctl = WC._bagsCtlDoc and WC._bagsCtlDoc() or nil
    local json = WC.encode(b)
    if json == lastBags then return end
    lastBags = json; lastDoc = b
    lastCtl = b.ctl and WC.encode(b.ctl) or nil
    WC.emit({ type = "full", v = WC.VERSION, reason = reason, bags = b })
end

local function sendSoon(reason)
    if queued then return end
    queued = true
    -- end of frame: BAG_UPDATE_DELAYED, PLAYER_MONEY and a burst of GET_ITEM_INFO_RECEIVED
    -- from one loot all land together and must cost one collect, not five
    C_Timer.After(0, function() sendBags(reason) end)
end

-- a controller press changes only the cursor / menu, not the bags: send the new ctl ALONE, a light "bagsctl" document (the bags
-- lag, tracking 2026-10-22: re-encoding every slot of every bag on each press was most of it), once per frame however many presses
-- land in it. The last bags table keeps the new ctl, so the next full send carries it too.
local ctlQueued = false
local function sendCtlSoon()
    if ctlQueued or queued then return end
    ctlQueued = true
    C_Timer.After(0, function()
        ctlQueued = false
        if queued then return end                   -- a full collect is on its way anyway
        if not lastDoc then sendBags("ctl"); return end
        local c = WC._bagsCtlDoc()
        lastDoc.ctl = c
        local json = WC.encode(c)
        if json == lastCtl then return end
        lastCtl = json
        lastBags = nil                              -- the bags table's ctl moved on: the next collect is sent whatever it holds
        WC.emit({ type = "bagsctl", v = WC.VERSION, ctl = c })
    end)
end

local bf = CreateFrame("Frame")
-- NOT ITEM_LOCK_CHANGED: it fires on every pick-up and put-down, so moving one item would cost
-- two extra flushes; the lock state rides along with the BAG_UPDATE_DELAYED that follows.
-- UPDATE_INVENTORY_DURABILITY / UPDATE_INVENTORY_ALERTS (f-durability): a hit, a death or a repair changes the worn pieces'
-- durability and the game's warning figure (DurabilityFrame listens to the same two) without any bag event
local BAG_EVENTS = { "BAG_UPDATE_DELAYED", "GET_ITEM_INFO_RECEIVED", "PLAYER_MONEY",
                     "PLAYER_EQUIPMENT_CHANGED", "PLAYER_REGEN_DISABLED", "PLAYER_REGEN_ENABLED",
                     "UPDATE_INVENTORY_DURABILITY", "UPDATE_INVENTORY_ALERTS" }
for e in pairs(CTX_OPEN) do BAG_EVENTS[#BAG_EVENTS + 1] = e end
for e in pairs(CTX_CLOSE) do BAG_EVENTS[#BAG_EVENTS + 1] = e end
local bagMissing = {}
for _, e in ipairs(BAG_EVENTS) do
    local ok, registered = pcall(function() return bf:RegisterEvent(e) end)
    if not ok or registered == false then bagMissing[#bagMissing + 1] = e end
end
if #bagMissing > 0 then
    pcall(function() WC.send(WC.encode({ type = "cap", bagsMissingEvents = bagMissing })) end)
end

bf:SetScript("OnEvent", function(_, event, a1)
    -- bags-act1: a delete waiting for its slot to empty is confirmed at the bag update
    if event == "BAG_UPDATE_DELAYED" and WC._bagsDestroyCheck then pcall(WC._bagsDestroyCheck, true) end
    -- bags-act2: the compare sheet's item left its slot: the sheet closes (it showed whatever came there)
    if event == "BAG_UPDATE_DELAYED" and WC._bagsCompareCheck then pcall(WC._bagsCompareCheck) end
    -- bags-tip1: what is worn changed: every kept bag tooltip (its compare) and the worn ones are read again; durability: the worn ones
    if event == "PLAYER_EQUIPMENT_CHANGED" then
        if WC._bagsTipForget then WC._bagsTipForget() end
        if WC._bagsWornDirty then WC._bagsWornDirty() end
    elseif event == "UPDATE_INVENTORY_DURABILITY" and WC._bagsWornDirty then WC._bagsWornDirty() end
    if event == "PLAYER_REGEN_ENABLED" and syncDoors then syncDoors() end
    if event == "PLAYER_REGEN_DISABLED" then pcall(emptyFightOff) elseif event == "PLAYER_REGEN_ENABLED" then pcall(emptyFightOn) end
    -- bags-de1: the pick cells that could cast or /use are cleared for the fight (binv 19 / 39 and the target-only cells stay live)
    if event == "PLAYER_REGEN_DISABLED" and WC._kit then pcall(WC._kit.fightOff) end
    -- GET_ITEM_INFO_RECEIVED fires for every item anything asks about (chat links, tooltips,
    -- other addons). Only a name WE are waiting for is worth a re-collect. (pcall: the arg is
    -- used as a table key, and nothing has measured whether an event arg can be secret.)
    if event == "GET_ITEM_INFO_RECEIVED" then
        if WC._bagsDpickLoaded then pcall(WC._bagsDpickLoaded, a1) end      -- bags-act1: a Suggested value that was loading
        local ok, hit = pcall(function() return pending[a1] end)
        if not (ok and hit) then return end
    elseif CTX_OPEN[event] then
        ctx = CTX_OPEN[event]; CTXW.at = GetTime() or 0; CTXW.stale = nil
        -- the mailbox's Send/Inbox tabs change what a right-click does without any bag event,
        -- so follow the Send frame itself (hooked once, the first time the mailbox opens)
        if event == "MAIL_SHOW" and not WC._bagsMailHooked then
            WC._bagsMailHooked = pcall(function()
                SendMailFrame:HookScript("OnShow", function() sendSoon("mailtab") end)
                SendMailFrame:HookScript("OnHide", function() sendSoon("mailtab") end)
            end)
        end
    elseif CTX_CLOSE[event] then
        if ctx == CTX_CLOSE[event] then ctx = nil end
        -- bags-take1: a take skipped for this window is tried again once it is gone (the bags still open)
        if CTXW.retry then pcall(C_Timer.After, 0, CTXW.retry) end
    end
    sendSoon(event)
end)

-- a few seconds after the world loads the bags are collected and the pad published again (bags-onerow1: the drag rows themselves
-- are registered at this file's load now, "bmv" pinned to the pad's last row)
do
    local mf = CreateFrame("Frame")
    mf:RegisterEvent("PLAYER_ENTERING_WORLD")
    mf:SetScript("OnEvent", function()
        if WC._bagsMoveReady then return end
        C_Timer.After(4, function()
            WC._bagsMoveReady = true
            lastBags = nil
            sendSoon("moverows")          -- collectBags registers the rows, then publishes the pad
        end)
    end)
end

-- the pad moved (UI scale, resolution) or its secure cells were rebuilt after combat: the app
-- must get the new geometry before its next tap, not on the next heartbeat
if WC.pad and WC.pad.onLayout then
    WC.pad.onLayout(function() lastBags = nil; sendSoon("pad") end)
end

-- ---------------------------------------------------------------- text-box focus
-- USER RULING 2026-09-27 (the amended lock): one touch = one game action, and an Esc to close an
-- open chat box may come first. The app can only do that safely if it KNOWS a box is open: a
-- blind Esc opens the Game Menu. GetCurrentKeyBoardFocus() is the game's own answer (verified
-- present in the binary). Sent only on change, so it costs nothing while nobody types.
local lastTyping = nil
C_Timer.NewTicker(0.2, function()
    local ok, focus = pcall(GetCurrentKeyBoardFocus)
    local typing = ok and type(focus) == "table"
    if typing == lastTyping then return end
    lastTyping = typing
    WC.emit({ type = "full", v = WC.VERSION, ui = { typing = typing } })
end)

-- ---------------------------------------------------------------- item actions: why not keys
-- MEASURED 2026-09-27 (key probe, since removed; commits d63aef8..e812b65): on the Thor a plain
-- key via `input keyevent` arrives, SHIFT arrived 4/4, CTRL 2/9, ALT 0/9, and numpad keys arrive
-- as CHARACTERS (NUMPADDIVIDE = "/", which opens chat). A secure macro button bound with
-- SetOverrideBindingClick fired exactly once per press.
-- USER RULING (same day, after the quests session proved the tap channel): item actions go through
-- the ONE SHARED TAP PAD -- a tap on the top screen = one click on an invisible secure item cell.
-- No key pool, no Esc-first, works with a chat box open.

-- ---------------------------------------------------------------- CONTROLLER MODE
-- USER RULINGS 2026-09-28 14:40 / 14:50: opening the game's bags (the controller's bag button)
-- hands the controller to the Bags room. A = pick up / put down (move), B = cancel, or close the
-- bags, X = use or equip, Y = "more": Bind / Destroy / Split. Bind captures a button or combo
-- other than A/B/X/Y; one the game already uses is refused. Destroy is confirmed; Split takes an
-- amount on the D-pad and puts the new stack in the first empty slot.
--
-- The controller is TAKEN through the interact session's shared capture (WowCompanion.capture,
-- owned by interact, frozen API): it hears the buttons before the game's bindings and hands the
-- presses to us inside the hardware event, so every action except USE runs right here. The game's
-- use call is protected, so X only reports the request and the app taps Bags' own secure cell for
-- that slot (the same cell the item card's button taps). No capture (interact's addon absent or older) = no controller mode, and
-- nothing else changes. The capture never runs in combat; an NPC window takes it over.
--
-- BIND: MEASURED 2026-09-28 15:53 (interact, addon i15 on the Thor; AGENT_LOG "BUTTON TEST
-- ANSWERED"): besides the eight, the capture frame hears exactly these six. The left bumper and the
-- left trigger are the game's Ctrl and Shift (GamePadEmulateCtrl / GamePadEmulateShift), so they are
-- never a chord and always go on to the game. A chord WITH a modifier held ("SHIFT-PADRSHOULDER") has
-- not been pressed by anyone yet -- whether the game reports the modifier while the capture frame has
-- the press is unknown -- so those stay behind BIND_MODS until one is measured.
local BIND_MODS = false
local HEARD = { PADRSHOULDER = true, PADRTRIGGER = true, PADLSTICK = true, PADRSTICK = true,
                PADBACK = true }
-- NOT START (PADFORWARD): the user's ruling 2026-10-09, "Start opens our pause wheel from every room" (with the DS toolbar on), so
-- Bind to a Button no longer offers it, and a bind saved on it before is let go at login (BB.load)
local HEARD_ORDER = { "PADRSHOULDER", "PADRTRIGGER", "PADLSTICK", "PADRSTICK", "PADBACK" }

-- may this chord be bound? (the game's chord string: "PADRSHOULDER", "SHIFT-PADBACK", ...)
local function chordRefusal(chord)
    if type(chord) ~= "string" or chord == "" then return "bind-bad" end
    local base = chord:match("([^%-]+)$")
    if not HEARD[base] then return "bind-unheard" end
    if chord:find("-", 1, true) and not BIND_MODS then return "bind-modifier" end
    return nil
end

-- ---------------------------------------------------------------- BIND TO A BUTTON: THE COMPANION'S OWN (the user's RULING 2026-10-09)
-- The user: "everything with the controller settings (outside of changing spells on the normal toolbars) should kind of be an
-- accessory to the addon and not affect peoples normal set up on their charactors". The old bind made a per-character macro
-- "WCB <itemID>", bound it with SetBindingMacro and SAVED the player's binding set: it wrote into their own setup. Now a bind is
-- ours alone: one secure item button per chord (WowCompanionBagBind_<chord>, type "item"), an OVERRIDE binding to it
-- (SetOverrideBindingClick: the game never saves overrides), set and cleared out of combat only, and our own record of it in our
-- add-on's saved variables (WowCompanionDB.bagBinds[<name-realm>][<chord>] = item id: the table is everyone's, only that key is
-- Bags'), put back at every login. Nothing of the player's -- macros, binding set -- is touched. Only a free button is taken (a
-- button the game or another add-on already uses is refused, as before), so an override never hides anything of theirs.
-- THE OLD ONES (BB.old): the six buttons whose own (not override) binding is "MACRO WCB <id>", and the character's "WCB <id>"
-- macros. Listed in the bags doc (wcbOld) while any exist; ONE tap (bmvq col 25) cleans them up out of combat: each old bind
-- becomes our override (the item keeps its button), the player's binding for it goes back to free (one SaveBindings: the set
-- as it was before Bags bound it), and the WCB macros are deleted. None existed on the Thor or the PC on 2026-10-09 (their WTF
-- caches: the "DS ..." macros are Spells' / Home's, not these).
-- Safety k6 (the 11-20 hunter, test build 1513): WC.charKey(), nil until the realm is known. It was "Name-?" / "Name-" + a marker
-- (S()'s markers are strings) / "?-?": nothing is read or saved per character without the whole key now
function BB.charKey()
    return ((function() if type(WC.charKey) == "function" then return (WC.charKey()) end; local ok, k = pcall(function() local n, r = UnitName("player"), GetRealmName(); if type(n) == "string" and type(r) == "string" and n:find("%S") and r:find("%S") and r ~= "?" and r ~= "_" then return n .. "-" .. r end end); return ok and k or nil end)())
end

-- bags-rk1 (the k6 review, round 3: "Bags overwrites the real bag binds when the key appears after login"): BB.load runs once, at
-- PLAYER_LOGIN, and BB.save writes the whole record from memory. With no key at the login (the realm empty, the hint not there yet, the
-- kept realm not resolved yet) BB.binds was {}; once the key came (the realm back, the hint, Safety's 2 s / 30 s re-checks) the next save
-- wrote that near-empty set over the REAL bagBinds["Name-Realm"] (every earlier bind lost). Now BB.key is the key BB.load read under, and
-- a save under any other key first reads that key's record (BB.sync: the stored binds, this session's own key-less binds on top); the
-- first time the key appears (timers after the login, a world entry, a fight's end) BB.sync reads and applies it too. A key that CHANGES
-- (a kept realm the game's own answer replaced) takes the new key's record as stored: the old key's set is never copied onto it.
function BB.read(me)
    local db = type(WowCompanionDB) == "table" and WowCompanionDB.bagBinds or nil
    local mine = me and type(db) == "table" and db[me] or nil
    local out = {}
    if type(mine) == "table" then
        for k, id in pairs(mine) do
            if type(k) == "string" and tonumber(id) and not chordRefusal(k) then out[k] = tonumber(id)
            elseif type(k) == "string" and tonumber(id) and k:match("([^%-]+)$") == "PADFORWARD" then BB.dropped = true end
        end
    end
    return out
end

function BB.load()
    local me = BB.charKey()
    BB.key = me
    BB.binds = BB.read(me)
    if BB.dropped then BB.save() end                    -- Start's old bind gone from our record too (the pause wheel's now)
end

-- bags-rk1: the key now against the one BB.binds was read under; true = BB.binds is now the key's own record (and applied)
function BB.sync()
    local me = BB.charKey()
    if not me or me == BB.key then return false end
    local had = BB.key
    local stored = BB.read(me)
    local mine = false
    if had == nil then                                  -- binds made while no key was known: this character's, kept on top
        for k, id in pairs(BB.binds) do stored[k] = id; mine = true end
    end
    BB.key, BB.binds = me, stored
    if mine or BB.dropped then BB.save() end
    BB.apply()                                          -- in a fight: waits for PLAYER_REGEN_ENABLED (BB.pending)
    return true
end

function BB.save()
    local me = BB.charKey()
    if not me then return end                           -- k6: no character key yet: nothing saved (BB.load reads it again)
    if me ~= BB.key then BB.sync(); return end          -- bags-rk1: never the whole record of another (or no) key over this one's
    if type(WowCompanionDB) ~= "table" then WowCompanionDB = {} end
    if type(WowCompanionDB.bagBinds) ~= "table" then WowCompanionDB.bagBinds = {} end
    local out = {}
    for k, id in pairs(BB.binds) do out[k] = id end
    WowCompanionDB.bagBinds[me] = next(out) and out or nil
end

-- one secure item button per chord, made out of combat, off screen, never under a finger (no art: nothing to see)
function BB.button(chord)
    local b = BB.buttons[chord]
    if b then return b end
    b = CreateFrame("Button", "WowCompanionBagBind_" .. chord, UIParent, "SecureActionButtonTemplate")
    pcall(function() b:SetSize(1, 1); b:SetPoint("TOPLEFT", UIParent, "TOPLEFT", -50, 50); b:EnableMouse(false) end)
    pcall(b.RegisterForClicks, b, "AnyUp", "AnyDown")
    b:SetAttribute("type", "item")
    BB.buttons[chord] = b
    return b
end

-- our overrides as BB.binds says (out of combat; in a fight it waits for PLAYER_REGEN_ENABLED)
function BB.apply()
    if InCombatLockdown() then BB.pending = true; return false end
    BB.pending = nil
    BB.owner = BB.owner or CreateFrame("Frame", "WowCompanionBagBinds", UIParent)
    for k in pairs(BB.applied) do
        if not BB.binds[k] then CALL(function() return SetOverrideBinding(BB.owner, false, k, nil) end); BB.applied[k] = nil end
    end
    -- bags-unbind1 (issue #213): a let-go button holds no item any more (out of combat here)
    for k, b in pairs(BB.buttons) do if not BB.binds[k] then pcall(b.SetAttribute, b, "item", nil) end end
    for k, id in pairs(BB.binds) do
        local b = BB.button(k)
        b:SetAttribute("item", "item:" .. id)
        CALL(function() return SetOverrideBindingClick(BB.owner, false, k, b:GetName(), "LeftButton") end)
        BB.applied[k] = true
    end
    return true
end

-- bags-unbind1 (issue #213): the one way to let a bind go. Out of combat only (an override cannot change in a fight)
function BB.clear(chord)
    if InCombatLockdown() or BB.binds[chord] == nil then return false end
    BB.binds[chord] = nil; BB.save(); return BB.apply()
end
-- bags-bindbox1: a bind on L3 / R3 moves into PadMap's stick entry (m157: "/use item:<id>" on the stick's own button, shown and cleared
-- in the Bind box), out of combat; a stick that holds something of its own already keeps it (the router's priority binding hid ours,
-- so ours goes). RB / RT / Select stay BB's. Not ready (no PadMap, the item's name not loaded yet): tried again later (BB.migrate).
BB.STICK = { PADLSTICK = 9, PADRSTICK = 10 }
function BB.migrate()
    if InCombatLockdown() then BB.pending = true; BB.migrated = false; return false end
    local pm = WC.padMap
    if type(pm) ~= "table" or type(pm.putItem) ~= "function" or type(pm.stickSaved) ~= "function" then BB.migrated = false; return false end
    local left, changed = false, false
    for chord, pos in pairs(BB.STICK) do
        local id = BB.binds[chord]
        if id then
            local okw, sv = pcall(pm.stickSaved, 1, pos)
            if okw and type(sv) == "table" and (sv.bind or sv.e) then
                BB.binds[chord] = nil; changed = true                         -- the stick's own wins, as it did
            elseif okw and type(sv) == "table" then
                local page = type(pm.page) == "function" and pm.page() or 1
                local okp, ok = pcall(pm.putItem, page, 1, pos, id)
                if okp and ok then BB.binds[chord] = nil; changed = true else left = true end
            else left = true end
        end
    end
    if changed then BB.save(); BB.apply() end
    BB.migrated = not left
    return not left
end

-- the old binds and macros (see above): { binds = { {k, id} }, macros = { name } }, or nil when there are none
function BB.old()
    local binds, macros = {}, {}
    local chords = { "PADFORWARD" }                      -- Start too: it was bindable before 2026-10-09
    for _, k in ipairs(HEARD_ORDER) do chords[#chords + 1] = k end
    for _, k in ipairs(chords) do
        local a = S(function() return GetBindingAction(k, false) end)
        local id = type(a) == "string" and tonumber(a:match("^MACRO WCB (%d+)$") or "") or nil
        if id then binds[#binds + 1] = { k = k, id = id } end
    end
    local okN, _, nC = CALL(function() return GetNumMacros() end)
    local base = tonumber(S(function() return MAX_ACCOUNT_MACROS end)) or 120
    if okN and type(nC) == "number" then
        for i = base + 1, base + nC do
            local okM, name = CALL(function() return GetMacroInfo(i) end)
            if okM and type(name) == "string" and name:match("^WCB %d+$") then macros[#macros + 1] = name end
        end
    end
    if #binds == 0 and #macros == 0 then return nil end
    return { binds = binds, macros = macros }
end

-- ONE tap (bmvq col 25): the old ones cleaned up, out of combat (see above)
function BB.cleanOld()
    if InCombatLockdown() then return "wcb-combat" end
    local o = BB.old()
    if not o then BB.oldDoc = nil; return "wcb-none" end
    for _, b in ipairs(o.binds) do
        if BB.binds[b.k] == nil then BB.binds[b.k] = b.id end            -- the item keeps its button: ours now, never saved
        CALL(function() return SetBinding(b.k) end)                      -- the player's binding: free again, as before Bags
    end
    if #o.binds > 0 then CALL(function() return SaveBindings(GetCurrentBindingSet()) end) end
    for _, name in ipairs(o.macros) do CALL(function() return DeleteMacro(name) end) end
    BB.save(); BB.apply()
    BB.oldDoc = BB.old()
    return "wcb-cleaned"
end

do
    local bf = CreateFrame("Frame")
    bf:RegisterEvent("PLAYER_LOGIN")
    bf:RegisterEvent("PLAYER_ENTERING_WORLD")
    bf:RegisterEvent("PLAYER_REGEN_ENABLED")
    bf:SetScript("OnEvent", function(_, e)
        if e == "PLAYER_LOGIN" then
            BB.load(); BB.apply(); BB.oldDoc = BB.old()
            BB.migrated = nil
            for n = 1, 3 do C_Timer.After(2 * n, function() if not BB.migrated then BB.migrate() end end) end   -- bags-bindbox1
            -- bags-rk1: no key at the login: asked again (every 5 s for a minute, then every 30 s for half an hour) until it is known
            local function later(n)
                if BB.key ~= nil or n > 72 then return end
                C_Timer.After(n <= 12 and 5 or 30, function() pcall(BB.sync); later(n + 1) end)
            end
            if BB.key == nil then later(1) end
        elseif e == "PLAYER_ENTERING_WORLD" then        -- bags-rk1: a world entry with the key known now
            if BB.key == nil and BB.binds then pcall(BB.sync) end
        else                                            -- a fight's end: what waited for it, and an old stick bind not moved yet
            if BB.key == nil then pcall(BB.sync) end    -- bags-rk1
            if BB.pending then BB.apply() end
            if BB.migrated == false then BB.migrate() end
        end
    end)
end
local COLS = 10                   -- the app's grid (v3, the room's full width: 10 to a row): the D-pad walks the same columns
-- USER PICK 2026-09-28 17:05: the destructive one last. bags-act1 (the user's pick 2026-11-13): Compare (gear only) before it
local MENU = { "split", "bind", "compare", "destroy" }
-- bags-act1: Compare and the Destroy picker (one table: the main chunk's local count). Filled in below the menu.
local ACT = { COMPARE_COL = 8, MAX = 40 }
local ctl = { on = false, mode = "grid", bag = 0, slot = 1, pick = nil, menu = 1, amount = 1,
              seq = 0, note = nil, use = nil, open = false, shut = {} }
-- a /reload starts seq at 0 again; the app keys "have I tapped this use yet" on (sid, use.seq)
ctl.sid = math.floor((GetTime() or 0) * 1000) % 1000000
local BAG_FRAMES = { "ContainerFrameCombinedBags" }
for i = 1, 13 do BAG_FRAMES[#BAG_FRAMES + 1] = "ContainerFrame" .. i end

local function capture() return WC.capture end        -- set by WowCompanionInteract when it loads

-- what the app draws (inside bags.ctl); the seq tells it a new note / use request arrived
local menuUsable            -- below: which Y-menu rows can be used
local EQ = {}               -- below: equipping a bag (after toGrid)
local BAR = {}              -- below: the cursor on the room's top strip, closed bags (after toGrid)
-- THE TOOLTIP of the item under the cursor (the user 2026-09-30: no right panel, "reuse some of the tool tip stuff"):
-- Interact's WC.tipOf("bag <bag> <slot> <itemID>") = the game's own bag-item tooltip, with (bags-nocmp1: only) the
-- stat changes for gear (cmp: the app shows it by itself, the user's ruling). Never read while an item is in the hand or a menu /
-- dialog is open. An Interact without WC.tipOf: no tooltip lines (the app falls back to the item's name).
-- (c) ONLY WHERE THE CURSOR RESTS (tracking 2026-10-22, the bags lag): an item NOT YET READ is read TIP.REST after the cursor
-- reached it, if it is still there, and sent then.
-- bags-tip1 (the user, 2026-11-13: "the tool tip shows one thing for a half second and has to load the rest and reloads the tool
-- tip. I want it to feel fast and load that tool tip as its full description each time"; E:/wow-build/bags_design_1113.md 1):
--  * MANY ITEMS ARE KEPT (TIP.c, one per "bag <bag> <slot> <id>"), not one: a bags collect no longer forgets them all. An item's
--    tooltip is forgotten only when its id (the key) or its link changes, or when it leaves the bags (WC._bagsTipKeep); all of them
--    on PLAYER_EQUIPMENT_CHANGED / PLAYER_LEVEL_UP (the compare and the red lines depend on what is worn and the level).
--  * A KEPT ONE RIDES WITH THE CURSOR'S OWN MESSAGE: no rest, no second send. One older than TIP.FRESH is still sent at once and read
--    again behind it (the cooldown / durability text); a new read that says the same sends nothing.
--  * READ AHEAD: while the room holds the grid, out of a fight, the slots nearest the cursor are read TIP.PER_TICK each TIP.TICK
--    (Professions' TIP_PER_TICK pattern), nothing sent: the cursor finds them kept.
--  * ONLY COMPLETE ONES ARE KEPT: no lines (none) or the game's "Retrieving item information" = still loading (sent with
--    loading = true, never kept); ITEM_DATA_LOAD_RESULT / TOOLTIP_DATA_UPDATE read the cursor's again (TIP.TRIES at most).
-- Read-only calls only (C_TooltipInfo through Interact); nothing happens in the game.
local TIP = { REST = 0.12, FRESH = 8, TICK = 0.1, PER_TICK = 2, MAX = 200, TRIES = 6,
              c = {}, n = 0, want = nil, wait = nil, loading = nil, tries = 0, queue = nil, qFor = nil, ticking = false,
              again = false, reads = 0, pre = 0, rows = {},
              NW = { nw = true } }         -- bags-nocmp1: Interact's compare without the worn pieces' tooltips (only the stat changes)
local function tipKey()
    -- bags-act1: the compare sheet (mode "compare") keeps the item's tooltip too (its compare is the sheet's right side)
    -- bags-gear1: and the slide-out of a piece of gear (EQ's gear kind: the cursor stays on that piece)
    -- bags-de1: the Disenchant grid ("bulk") shows the cursor's item's tooltip too (its cursor moves ctl.bag / ctl.slot)
    local gear = ctl.mode == "equip" and ctl.eq and ctl.eq.gear
    if not (ctl.on and (ctl.mode == "grid" or ctl.mode == "compare" or ctl.mode == "bulk" or gear) and not ctl.pick) or type(WC.tipOf) ~= "function" then return nil end
    local id = bagItemID(ctl.bag, ctl.slot)
    if not id then return nil end
    return "bag " .. ctl.bag .. " " .. ctl.slot .. " " .. id
end
function TIP.link(bag, slot) return S(function() return C_Container.GetContainerItemLink(bag, slot) end) end
-- a tooltip the game has finished: lines, and none of them (nor the compare's) the game's "Retrieving item information"
function TIP.complete(t)
    if type(t) ~= "table" or t.none or type(t.lines) ~= "table" or #t.lines == 0 then return false end
    local wait = S(function() return RETRIEVING_ITEM_INFO end)
    if type(wait) ~= "string" or wait == "" then wait = "Retrieving item information" end
    local function has(lines)
        for _, l in ipairs(lines) do if type(l) == "table" and l.t == wait then return true end end
        return false
    end
    if has(t.lines) then return false end
    if type(t.cmp) == "table" then
        for _, c in ipairs(t.cmp) do if type(c) == "table" and type(c.lines) == "table" and has(c.lines) then return false end end
    end
    return true
end
-- the kept tooltip of key, if the slot still holds the same item (its link)
function TIP.get(key, bag, slot)
    local e = TIP.c[key]
    if e and e.link ~= TIP.link(bag, slot) then TIP.c[key] = nil; TIP.n = TIP.n - 1; e = nil end
    return e
end
-- read key's tooltip now: (tooltip, kept). A loading one comes back marked loading and is not kept.
function TIP.read(key, bag, slot)
    TIP.reads = TIP.reads + 1
    local ok, t = pcall(WC.tipOf, key, TIP.NW)
    if not (ok and type(t) == "table") then return nil, false end
    if not TIP.complete(t) then t.loading = true; return t, false end
    if TIP.n >= TIP.MAX then TIP.c, TIP.n = {}, 0 end            -- a long session: start again
    if not TIP.c[key] then TIP.n = TIP.n + 1 end
    TIP.c[key] = { link = TIP.link(bag, slot), val = t, at = GetTime(), sig = TIP.rows[key] }
    return t, true
end
-- read the cursor's item and send it when it says something new (old = the kept one it replaces, if any)
function TIP.readCursor(key)
    if tipKey() ~= key then return end
    local old = TIP.get(key, ctl.bag, ctl.slot)
    local oldJson = old and WC.encode(old.val) or nil
    local t, kept = TIP.read(key, ctl.bag, ctl.slot)
    if kept then
        TIP.wait = nil; TIP.loading = nil
        if WC.encode(t) ~= oldJson then sendCtlSoon() end
    elseif old then
        old.at = GetTime()                                       -- a loading re-read: the complete one stays (read again later)
    elseif t then
        if TIP.loading ~= key then TIP.loading = key; TIP.tries = 0 end
        TIP.wait = { key = key, val = t }
        sendCtlSoon()
    end
end
-- the slots of the open bags nearest the cursor first (the grid as the room draws it: COLS a row, bag under bag)
function TIP.around()
    -- bags-de1: in the Disenchant grid its own items, nearest the cursor first (the grid as the app draws it: COLS a row, list order)
    if ctl.mode == "bulk" and WC._de then
        local out, items = {}, WC._de.list()
        local at = math.max(1, ctl.bsel or 1) - 1
        for n, e in ipairs(items) do
            local d = math.abs(math.floor((n - 1) / COLS) - math.floor(at / COLS)) + math.abs((n - 1) % COLS - at % COLS)
            out[#out + 1] = { e.b, e.s, d }
        end
        table.sort(out, function(a, b) if a[3] ~= b[3] then return a[3] < b[3] end; if a[1] ~= b[1] then return a[1] < b[1] end; return a[2] < b[2] end)
        return out
    end
    local list, row0, cr, cc = {}, 0, 0, 0
    for _, e in ipairs(BAR.open()) do
        if e.bag == ctl.bag then cr = row0 + math.floor((ctl.slot - 1) / COLS); cc = (ctl.slot - 1) % COLS end
        row0 = row0 + math.floor((e.slots + COLS - 1) / COLS)
    end
    row0 = 0
    for _, e in ipairs(BAR.open()) do
        for s = 1, e.slots do
            local r, c = row0 + math.floor((s - 1) / COLS), (s - 1) % COLS
            list[#list + 1] = { e.bag, s, math.abs(r - cr) + math.abs(c - cc) }
        end
        row0 = row0 + math.floor((e.slots + COLS - 1) / COLS)
    end
    table.sort(list, function(a, b) if a[3] ~= b[3] then return a[3] < b[3] end; if a[1] ~= b[1] then return a[1] < b[1] end; return a[2] < b[2] end)
    return list
end
function TIP.step()
    TIP.ticking = false
    -- the room let go, a menu / the hand, or a fight: no reads ahead (Chat's rule: out of combat when it can be). The cursor's own
    -- item still reads in a fight, as before
    if not tipKey() or InCombatLockdown() then return end
    local here = ctl.bag .. " " .. ctl.slot
    if TIP.qFor ~= here or not TIP.queue then TIP.queue = TIP.around(); TIP.qFor = here end
    local done = 0
    while done < TIP.PER_TICK and #TIP.queue > 0 do
        local q = table.remove(TIP.queue, 1)
        local id = bagItemID(q[1], q[2])
        if id then
            local key = "bag " .. q[1] .. " " .. q[2] .. " " .. id
            -- the cursor's own item is its rest timer's (TIP.want), never read twice
            if key ~= TIP.want and not TIP.get(key, q[1], q[2]) then TIP.read(key, q[1], q[2]); TIP.pre = TIP.pre + 1; done = done + 1 end
        end
    end
    if done > 0 and ctl.mode == "bulk" then TIP.deNew = true end
    if #TIP.queue > 0 then TIP.ticking = true; C_Timer.After(TIP.TICK, TIP.step)
    elseif TIP.deNew then TIP.deNew = nil; sendCtlSoon() end      -- bags-de1: the grid's green arrows come from what was read ahead
end
function TIP.ahead()
    -- review: in a fight TIP.step does nothing, so no timer either (_bagsTip runs at every ctl document)
    if TIP.ticking or InCombatLockdown() then return end
    if TIP.queue and #TIP.queue == 0 and TIP.qFor == (ctl.bag .. " " .. ctl.slot) then return end   -- all read for this spot
    TIP.ticking = true
    C_Timer.After(TIP.TICK, TIP.step)
end
function WC._bagsTip()
    local key = tipKey()
    if not key then return nil end
    if ctl.mode == "grid" or ctl.mode == "bulk" then TIP.ahead() end     -- bags-act1: no reads ahead behind the compare sheet
    local e = TIP.get(key, ctl.bag, ctl.slot)
    if e and (GetTime() - e.at) < TIP.FRESH then return e.val end
    if TIP.want ~= key then
        TIP.want = key
        C_Timer.After(TIP.REST, function()
            if TIP.want ~= key then return end           -- the cursor moved on: that slot's own timer reads it
            TIP.want = nil
            local k = tipKey() == key and TIP.get(key, ctl.bag, ctl.slot)
            if k and (GetTime() - k.at) < TIP.FRESH then sendCtlSoon(); return end   -- read ahead meanwhile: just sent
            TIP.readCursor(key)
        end)
    end
    if e then return e.val end                           -- kept but old: sent at once, read again behind it
    if TIP.wait and TIP.wait.key == key then return TIP.wait.val end
    return nil
end
-- everything forgotten (what is worn or the level changed)
function WC._bagsTipForget() TIP.c, TIP.n = {}, 0; TIP.want = nil; TIP.wait = nil; TIP.queue = nil end
-- a bags collect: keep the tooltips of the items still where they were (their links are checked when used)
-- review: and of the items whose stack, bound state or durability changed with the same link (a stack used up, a death's or a
-- repair's durability on gear in the bags, a piece gone soulbound): the link does not show those, the tooltip text does
local function rowSig(r) return tostring(r.count) .. "|" .. tostring(r.bound) .. "|" .. tostring(r.dur) end
function WC._bagsTipKeep(items)
    local here = {}
    for _, r in ipairs(items) do here["bag " .. r.bag .. " " .. r.slot .. " " .. r.id] = rowSig(r) end
    TIP.rows = here
    for k, e in pairs(TIP.c) do
        local sig = here[k]
        if not sig or (e.sig ~= nil and e.sig ~= sig) then TIP.c[k] = nil; TIP.n = TIP.n - 1 else e.sig = sig end
    end
    TIP.queue = nil                                      -- the bags changed: the read-ahead looks again
end
-- the game finished loading something: the cursor's loading tooltip is read again (at most TIP.TRIES times, once a frame)
function WC._bagsTipLoaded()
    if not TIP.loading or TIP.again then return end
    if TIP.tries >= TIP.TRIES then return end
    TIP.again = true
    C_Timer.After(0, function()
        TIP.again = false
        local k = TIP.loading
        if not k then return end
        TIP.tries = TIP.tries + 1
        TIP.readCursor(k)
    end)
end
-- for the off-device tests (tools/test_bags_tip.py)
function WC._bagsTipStats() return { n = TIP.n, reads = TIP.reads, pre = TIP.pre, loading = TIP.loading } end
do
    local tf = CreateFrame("Frame")
    local missing = {}
    for _, e in ipairs({ "ITEM_DATA_LOAD_RESULT", "TOOLTIP_DATA_UPDATE", "PLAYER_LEVEL_UP" }) do
        local ok, registered = pcall(function() return tf:RegisterEvent(e) end)
        if not ok or registered == false then missing[#missing + 1] = e end
    end
    if #missing > 0 then
        pcall(function() WC.send(WC.encode({ type = "cap", bagsTipMissingEvents = missing })) end)
    end
    tf:SetScript("OnEvent", function(_, e)
        if e == "PLAYER_LEVEL_UP" then WC._bagsTipForget(); if WC._bagsWornDirty then WC._bagsWornDirty() end
        else WC._bagsTipLoaded() end
    end)
end

function WC._bagsCtlDoc()
    -- off, the last note still rides along: a refused take ("take-refused-npc", "-no way in", ...) or a
    -- lost controller must show in the log, not vanish behind a bare "off"
    -- off, the app still needs to know if the game's bags are open: then its OPEN door would do nothing, and it takes
    -- with col 29 instead
    -- bags-de1: resume = the Disenchant grid lost the pad to a loot pop-up and takes it back when the loot window closes
    if not ctl.on then return { on = false, note = ctl.note, seq = ctl.seq, open = ctl.open and true or false, shut = BAR.shutList(),
                                resume = (WC._de and WC._de.resume) and true or nil,
                                ctxStale = CTXW.stale } end      -- bags-take1: an open-window state let go (its close never came)
    -- bags-de1: the Disenchant grid's document FIRST: its cursor moves ctl.bag / ctl.slot (cur and the tooltip below follow it)
    local bulk = (ctl.mode == "bulk" and WC._de) and WC._de.doc() or nil
    return {
        on = true, mode = ctl.mode, cur = { bag = ctl.bag, slot = ctl.slot }, open = ctl.open and true or false,
        pick = ctl.pick and { bag = ctl.pick.bag, slot = ctl.pick.slot } or nil,
        menu = MENU[ctl.menu], amount = ctl.amount, note = ctl.note, seq = ctl.seq,
        usable = (ctl.mode == "menu") and menuUsable() or nil, rare = ctl.rare,
        -- bags-act1: the menu's rows by name (usable follows this order), the Destroy picker, and the item a confirm is about
        menus = (ctl.mode == "menu") and MENU or nil,
        dpick = (ctl.mode == "dpick") and { list = ACT.list, sel = ctl.dsel or 1 } or nil,
        dest = (ctl.mode == "destroy" and ctl.dest) and { bag = ctl.dest.bag, slot = ctl.dest.slot } or nil,
        use = ctl.use, sid = ctl.sid,
        -- bags-bindbox1: the item the Spellbook's Bind box is open for (mode "bindbox"); the old binds still applied (diagnostics)
        boxItem = (ctl.mode == "bindbox") and ctl.armId or nil,
        bbinds = next(BB.binds) and BB.binds or nil,
        tip = WC._bagsTip(),
        eq = EQ.doc and EQ.doc() or nil,
        shut = BAR.shutList(),
        bar = (ctl.zone == "bar" and ctl.mode == "grid") and BAR.items()[ctl.barAt or 1] or nil,
        act = ctl.act,
        barmenu = BAR.menuDoc(),
        target = (ctl.mode == "target" and WC._kit) and WC._kit.targetDoc() or nil,   -- bags-kit3: the pick type (cx), for the report
        bulk = bulk,                                                                 -- bags-de1: the Disenchant grid
    }
end

-- NO GAME WINDOW CALL FROM HERE, EVER (measured 2026-09-28 22:57: Bags called the game's close-all-bags and
-- open-all-bags functions from addon code; the game's bag code then ran tainted and its controller bar was blocked
-- 2,050 times until a /reload). So Bags never opens or closes a game window. When the game's bag windows are open
-- and Bags is in its grid with nothing picked, B is PASSED to the game (interact's capture v2), and the game's own
-- B closes them in the game's own code; the windows' OnHide then hands the controller back. Everywhere else B is
-- Bags' own (Back / Cancel), and with no game window open (the Bags room entered by touch) B just hands back.
-- A BAG BEING EQUIPPED that binds when worn: the game asks "bind it to you?" in its own dialog, so while it asks A and B
-- are passed to the game too (mode "bindask", see EQ): the game's own controller answers its own question.
local function syncPass()
    local cap = capture()
    local want = false
    if ctl.on and not ctl.pending then
        if ctl.mode == "bindask" then want = "PAD1 PAD2"
        -- char-more2: a drag onto a worn slot that the game asks "bind it to you?" about (BINDWAIT): the game's own A / B answer it
        elseif BINDWAIT.on and bindAsking() then want = "PAD1 PAD2"
        -- bags-kit2 (issue #89): the game's own question about the pick (Replace enchant?) is answered by the game's own A / B
        elseif (ctl.mode == "target" or ctl.mode == "bulk") and WC._kit and WC._kit.asking() then want = "PAD1 PAD2"   -- bags-de1: bulk too
        elseif ctl.open and ctl.mode == "grid" and ctl.pick == nil then want = "PAD2" end
    end
    if want == (ctl.passing or false) or not (cap and cap.pass) then return end
    local keys = {}
    if want then for k in want:gmatch("%S+") do keys[#keys + 1] = k end end
    local ok, done = pcall(cap.pass, "bags", keys)
    if ok and done then ctl.passing = want end
end

WC._bagsSyncPass = function() syncPass() end      -- char-more2: for the drag's bind wait (above syncPass in the file)
local function report(note)
    -- bags-bindbox1: the Bind box is Bags' only while Bags is in "bindbox" and holds: anything else that moved the room on closes it
    if ACT.boxUp and not (ctl.on and ctl.mode == "bindbox") then
        ACT.boxUp = nil
        if type(WC.spellsBindHosted) == "table" then pcall(WC.spellsBindHosted.abort, note or "left") end
    end
    syncPass()
    ctl.note = note; ctl.seq = ctl.seq + 1
    sendCtlSoon()
end

local function layout() return WC._bagsLayout or {} end
local function bagIndex(bag) for i, e in ipairs(layout()) do if e.bag == bag then return i end end end
local function itemAt(bag, slot) return bagItemID(bag, slot) end
local function infoAt(bag, slot)
    local ok, info = CALL(function() return C_Container.GetContainerItemInfo(bag, slot) end)
    return ok and type(info) == "table" and info or nil
end

-- the D-pad walks the app's grid: 6 columns, each bag its own block, bags in order
local function moveCursor(key)
    local L = BAR.open(); local i
    for n, e2 in ipairs(L) do if e2.bag == ctl.bag then i = n end end
    if not i then i = 1; if L[1] then ctl.bag, ctl.slot = L[1].bag, 1 end end
    local e = L[i]
    if not e then                                   -- every bag closed: only the bar is left
        if key == "PADDUP" and not ctl.pick then BAR.enter(0) end
        return
    end
    local s = math.max(1, math.min(ctl.slot, e.slots))
    local row, col = math.floor((s - 1) / COLS), (s - 1) % COLS
    local lastRow = math.floor((e.slots - 1) / COLS)
    if key == "PADDLEFT" then
        if s > 1 then s = s - 1 elseif i > 1 then i = i - 1; s = L[i].slots end
    elseif key == "PADDRIGHT" then
        if s < e.slots then s = s + 1 elseif i < #L then i = i + 1; s = 1 end
    elseif key == "PADDUP" then
        if row > 0 then s = s - COLS
        elseif i > 1 then
            i = i - 1; local p = L[i]
            s = math.min(math.floor((p.slots - 1) / COLS) * COLS + col + 1, p.slots)
        elseif not ctl.pick then BAR.enter(col); return     -- the grid's top row: up onto the room's bar (not with an item in hand)
        end
    elseif key == "PADDDOWN" then
        if row < lastRow then s = math.min(s + COLS, e.slots)
        elseif i < #L then i = i + 1; s = math.min(col + 1, L[i].slots) end
    end
    ctl.bag = L[i].bag; ctl.slot = s
end

local function firstEmpty()
    for _, e in ipairs(layout()) do
        for s = 1, e.slots do if not itemAt(e.bag, s) then return e.bag, s end end
    end
end

-- hand the controller back, saying why ("B" = the player left with B: the app goes Home, the user's ruling of
-- 2026-09-28 23:12; "room" = the Bags room was left by touch). No game window is touched.
local function leave(why)
    local cap = capture()
    if cap then
        local ok, done, how = pcall(cap.release, "bags", why)
        -- capture i81: in a fight a room's own release answers "tap leave" -- the leave goes through Interact's leave cell
        -- (Home taps it), which then tells Bags lost(...). Bags keeps the controller till then and never retries its own
        -- release (SPEC_COMBAT item 11: never "the game has the pad while a room is shown")
        if ok and done == false and how == "tap leave" then report("leave-tap-" .. tostring(why)); return end
    end
    ctl.on = false; ctl.pick = nil; ctl.mode = "grid"; ctl.passing = false; ctl.pending = nil; ctl.zone = nil
    OPEN.under = nil                    -- bags-open1: a loot window over the room is the capture's to keep (v21's release drops the room)
    ctl.left = why                      -- the 1 s check takes it back only after a window, never after the player left
    -- bags-bindbox1: report() below sees the room off and closes a Bind box that was up (abort)
    report("left-" .. why)
end

local function toGrid(note) ctl.mode = "grid"; ctl.rare = nil; ctl.dest = nil; ctl.fromPick = nil; report(note) end

-- ---------------------------------------------------------------- ARMOR KITS, STONES, ENCHANTS: "CHOOSE AN ITEM" (the user, 2026-11-07: "there
-- is no way to apply my light armor kits ... enchants, and sharpening stones ... We need to make a way to use them from both the bags room as
-- well as the charactor room"; pick: the game-style pick list). The game's own flow, untouched: USING such an item (X on it here, the game's
-- right-click) puts the game in "pick an item" (SpellCanTargetItem). While it is, the room's cursor is a list of what it can go on - what you
-- wear first, then your bags' gear - and A USES that item: a bag item through its own secure bag cell (the game's right-click on it while
-- picking = target it), worn gear through the secure row "binv" (type item, item = the slot: the game's /use <slot>, its target). B stops
-- the pick: the secure cell binv 19 (type macro "/stopspelltarget", the game's own slash command - SpellStopTargeting from add-on code is
-- FORBIDDEN, measured on the Thor 2026-11-08: "WowCompanion forbidden: SpellStopTargeting()", the user's error applying a leg kit).
-- No binding: secure clicks of the game's own, as with the mouse.
-- DISENCHANT (issues #126 / #39, the player 2026-11-11: "disenchantment of items not possible. Game tries to equip instead. Items
-- specifically for disenchanting don't get listed at all"). Two causes, both in this file: (1) a bag row of the pick was answered by the
-- bag's own secure cell, type "item": the game's secure "item" action EQUIPS any equippable item not worn (securetemplates.lua: IsEquippable
-- and not IsEquipped -> EquipItemByName) before it ever uses it, so a pick's bag gear - Disenchant's whole list, and an armor kit on bag gear
-- - was equipped instead of targeted. Now each bag GEAR row of the pick has its own secure cell, binv 20..39 (KIT.CELL0), type macro
-- "/use <bag> <slot>": the game's own /use, which is the right-click's UseContainerItem(bag, slot), and while a spell is targeting
-- (SpellIsTargeting) that is the item-target click. The macrotext is set out of combat only (a cell that would need a change in a fight
-- greys its row: never a plain use). (2) the list put worn gear and every consumable first and checked its 40-row cap only for bag gear,
-- so Disenchant's items were crowded out: a Disenchant pick (SpellIsTargeting while Disenchant, 13262, is the current spell) lists ONLY
-- the bags' weapons and armour of quality 2 (green) or better.
-- (review 2026-11-11) a cell is held per (bag, slot) for the whole pick (KIT.byKey "/use <bag> <slot>" -> col): the list is built again on
-- every report and press, and a cell picked by the row's place would move to another item when the bags change mid-pick (loot), so a
-- tap on the list the app last had could disenchant a different item. New pairs take free cells; the hold ends when the pick does.
-- bags-kit3 (the user's picks, 2026-11-13, filter_design.md: "items a pick can't go on are HIDDEN (only 'fits but no free cell in a fight'
-- stays greyed); Feed Pet lists only what C_PetInfo.CanPetEatItem allows and trusts the game for too-low food; already-enchanted gear is
-- shown and the game's Replace enchant? popup is answered with A/B as today; the Bags room's target list serves every route"): the PICK
-- TYPE is worked out from the game on every step (KIT.ctx: de / feed / enchant / cond / spell), not from "an item used in the last 2 s",
-- so a kit or enchant applied from the Character room or the action bar gets the same filtered list as one from the Bags room's X.
--   enchant = C_Spell.TargetSpellIsEnchanting: only what C_Item.DoesItemMatchTargetEnchantingSpell allows, worn first, no consumables
--   cond    = C_Spell.TargetSpellChecksItemCondition (kits, stones, oils if the client marks them): C_Item.DoesItemMatchSpellItemCondition
--   feed    = Feed Pet (6991, C_Spell.GetTargetSpellID or the current spell): only the bags' items C_PetInfo.CanPetEatItem allows, highest
--             item level first, up to KIT.MAX; if that call fails, the list as before (food first)
--   de      = Disenchant, unchanged; spell = any other pick, unchanged (consumables + gear; gear only right after a bag item's use)
-- A kit whose pick says neither enchant nor cond (not known yet on the device) stays "spell": worn + bag gear, unfiltered. The values
-- ride on the itarget document and the target list (cx) for a later device check. Read-only; UPDATE_SPELL_TARGET_ITEM_CONTEXT and
-- ENCHANT_SPELL_SELECTED are listened to as well as the 0.25 s step.
-- bags-de1 (the Disenchant grid's review, the user's picks 2026-11-16: "Disenchant ... It stays open after each one; B closes it"): the pick's
-- target cells were "/use <bag> <slot>" macros, and a /use whose pick has ended (moving, the GCD, a same-frame interrupt) is a plain
-- right-click, which EQUIPS gear (issue #126 again: a BoE piece goes to the game's "bind it?", a soulbound one swaps silently). Every bag
-- target cell is now one of two shapes that can never equip, set out of combat (KIT.cst; securetemplates.lua OnActionButtonClick targets
-- target-bag / target-slot after ANY click, only while SpellCanTargetItem / SpellCanTargetItemID answer true):
--   "t" a pick is waiting (cast from the bar, a kit used): no type, only target-bag / target-slot - it answers the pick and never casts or uses;
--       the pick already ended = nothing happens
--   "c" no pick waiting (the Disenchant grid's A): type "spell", spell 13262 + the same target-bag / target-slot - the game casts, and only
--       if it is now picking does the click target the item; a cast that does not start does nothing
--   "u" the device fallback ONLY: Disenchant is the current spell but both SpellCanTargetItem and SpellCanTargetItemID answer false - the
--       old /use macro, voided at the click unless SpellIsTargeting is still true (never a plain use)
-- One cell per item (binv 20..38; 39 is /stopcasting now); its PreClick checks the slot still holds its item and, out of combat, clears
-- the cell when not ("moved"). PLAYER_REGEN_DISABLED clears every "c" / "u" cell (KIT.fightOff): only "t" cells and binv 19 / 39 stay
-- live in a fight. KIT.isCurrent / spellDoc ask C_Spell first (the 11.0 move, SpellBookAPITransitionGuide), the old globals second.
local KIT = { was = false, last = nil, DE = 13262, FEED = 6991, CELL0 = 20, CELLS = 40, CELL_LAST = 38, STOPCAST_COL = 39, MAX = 40,
              cells = {}, cst = {}, byKey = {}, STOP_HOLD = 1.5, evt = nil, evtN = 0 }
WC._kit = KIT                                   -- the room's document (above) reads it
WC._kitLearned = WC._kitLearned or {}           -- item ids seen to start a pick (the bag rows' `kit` flag: the Character room's list)
-- review of bags-de1: a game string out of S() is real only past S's sentinels ("" secret, "" absent, "" threw: S never answers nil);
-- [v] must already be plain (out of S, or checked with WC.isPlain): nil otherwise
function KIT.txt(v) if type(v) == "string" and v ~= "" and v:byte(1) > 3 then return v end end
-- a spell's name: C_Spell first (C_Spell.GetSpellName / GetSpellInfo().name), the old GetSpellInfo second; nil when none is plain
function KIT.spellName(id)
    local n = KIT.txt(S(function() return C_Spell.GetSpellName(id) end))
    if not n then
        local si = S(function() return C_Spell.GetSpellInfo(id) end)
        n = type(si) == "table" and KIT.txt(S(function() return si.name end)) or nil
    end
    if not n then
        local okN, nm = CALL(function() return GetSpellInfo(id) end)
        if okN and type(nm) == "string" and WC.isPlain(nm) then n = KIT.txt(nm) end
    end
    return n
end
-- the spell `v` (an id or a name) is the current spell: C_Spell.IsCurrentSpell first, the old global second
function KIT.cur(v)
    if S(function() return C_Spell.IsCurrentSpell(v) end) == true then return true end
    local okC, c = CALL(function() return IsCurrentSpell(v) end)
    return okC and c == true
end
-- the game is targeting and the spell `id` (or a spell of its name) is the current spell
function KIT.isCurrent(id)
    local okT, t = CALL(function() return SpellIsTargeting() end)
    if not (okT and t == true) then return false end
    if KIT.cur(id) then return true end
    local name = KIT.spellName(id)
    return name ~= nil and KIT.cur(name)
end
-- Disenchant is waiting for its item (issue #126): the game's target spell (bags-de1: as Feed Pet's), else targeting with Disenchant current
function KIT.de()
    if SN(function() return C_Spell.GetTargetSpellID() end) == KIT.DE then return true end
    return KIT.isCurrent(KIT.DE)
end
-- Feed Pet is waiting for its item (bags-kit3): the game's target spell, else the current spell as for Disenchant
function KIT.feed()
    if SN(function() return C_Spell.GetTargetSpellID() end) == KIT.FEED then return true end
    return KIT.isCurrent(KIT.FEED)
end
-- a pick is on now
function KIT.on()
    local ok, v = CALL(function() return SpellCanTargetItem() end)
    if ok and v == true then return true end
    ok, v = CALL(function() return SpellCanTargetItemID() end)
    if ok and v == true then return true end
    return KIT.de()
end
-- bags-kit3: the pick's type (nil = no pick). The named spells first (most specific), then what the game says of the pick
function KIT.ctx()
    if not KIT.on() then return nil end
    if KIT.de() then return "de" end
    if KIT.feed() then return "feed" end
    if S(function() return C_Spell.TargetSpellIsEnchanting() end) == true then return "enchant" end
    if S(function() return C_Spell.TargetSpellChecksItemCondition() end) == true then return "cond" end
    return "spell"
end
-- what the game said of the pick, for the report (bags-kit3 device check): e / c = enchanting / checks item condition (nil = no answer),
-- id = the target spell, ev / evn = the last pick event heard and how many
-- bags-de1: sct / scti = SpellCanTargetItem / SpellCanTargetItemID (the design's device check: "t" cells need one of them true)
function KIT.probe()
    local e = S(function() return C_Spell.TargetSpellIsEnchanting() end)
    local c = S(function() return C_Spell.TargetSpellChecksItemCondition() end)
    local sct = S(function() return SpellCanTargetItem() end)
    local scti = S(function() return SpellCanTargetItemID() end)
    local p = { ctx = KIT.ctx(), e = (e == true or e == false) and e or nil, c = (c == true or c == false) and c or nil,
                id = SN(function() return C_Spell.GetTargetSpellID() end), ev = KIT.evt, evn = KIT.evtN }
    if sct == true or sct == false then p.sct = sct end              -- false kept: "both false" is the device fallback's sign
    if scti == true or scti == false then p.scti = scti end
    return p
end
-- bags-de1: the shape a waiting pick's cell takes: "t" (target only) whenever the game says it can target an item now; "u" (the old /use)
-- only when it says no to both and Disenchant is the current spell (the device fallback, see the header above KIT)
function KIT.pickShape()
    local ok, v = CALL(function() return SpellCanTargetItem() end)
    if ok and v == true then return "t" end
    ok, v = CALL(function() return SpellCanTargetItemID() end)
    if ok and v == true then return "t" end
    if KIT.de() then return "u" end
    return "t"
end
-- a cell's secure attributes for its state (nil = cleared: no action, no target)
function KIT.attrs(btn, st)
    local sh = st and st.sh
    btn:SetAttribute("type", (sh == "c" and "spell") or (sh == "u" and "macro") or nil)
    btn:SetAttribute("spell", sh == "c" and KIT.DE or nil)
    btn:SetAttribute("macrotext", sh == "u" and ("/use " .. st.b .. " " .. st.s) or nil)
    btn:SetAttribute("target-bag", (sh == "t" or sh == "c") and st.b or nil)
    btn:SetAttribute("target-slot", (sh == "t" or sh == "c") and st.s or nil)
end
-- set cell `col` to item `id` in (b, s) with shape `sh`; out of combat only; true when set
function KIT.setCell(col, b, s, id, sh)
    local btn = KIT.cells[col]
    if not btn or InCombatLockdown() then return false end
    local st = { b = b, s = s, id = id, sh = sh, at = GetTime() }
    if not pcall(KIT.attrs, btn, st) then return false end
    KIT.cst[col] = st
    return true
end
-- cell `col` cleared (no action, no target) and let go; out of combat only (attributes are fixed in a fight)
function KIT.clearCell(col)
    local btn = KIT.cells[col]
    if btn and not InCombatLockdown() then pcall(KIT.attrs, btn, nil); KIT.cst[col] = nil end
    for k, c in pairs(KIT.byKey) do if c == col then KIT.byKey[k] = nil end end
end
-- PLAYER_REGEN_DISABLED (the lockdown has not begun): every cell that could cast or /use is cleared for the fight; "t" cells (target only:
-- they never cast, use or equip), binv 19 (/stopspelltarget) and binv 39 (/stopcasting) stay live
function KIT.fightOff()
    for col = KIT.CELL0, KIT.CELL_LAST do
        local st = KIT.cst[col]
        if st and st.sh ~= "t" then KIT.clearCell(col) end
    end
end
-- a bag item's target cell (issue #126; bags-de1 shapes): the cell held for (bag, slot) when it still names item `id` in shape `sh`, else
-- that cell or a free one of binv CELL0..CELL_LAST set now (out of combat only). [evict]: when every cell is held, the least recently used
-- is taken (the Disenchant grid: one press at a time; the pick's list never evicts: a stale list's tap must still hit the item it showed).
-- nil when no cell can be had (or the fight keeps it from being set)
function KIT.cellFor(bag, slot, id, sh, evict)
    sh = sh or "t"
    local key = bag .. " " .. slot .. " " .. tostring(id)      -- one cell per ITEM: another item in the slot never takes over its cell
    local col = KIT.byKey[key]
    local st = col and KIT.cst[col]
    if st and st.b == bag and st.s == slot and st.id == id and st.sh == sh then st.at = GetTime(); return col end
    if InCombatLockdown() then return nil end
    if not (col and KIT.cells[col]) then
        col = nil
        local held = {}
        for _, c in pairs(KIT.byKey) do held[c] = true end
        for c = KIT.CELL0, KIT.CELL_LAST do
            if KIT.cells[c] and not held[c] then col = c; break end
        end
        if not col and evict then
            -- every cell held: the least recently used one goes
            for c = KIT.CELL0, KIT.CELL_LAST do
                local s2 = KIT.cst[c]
                if KIT.cells[c] and (not col or (s2 and KIT.cst[col] and s2.at < KIT.cst[col].at)) then col = c end
            end
            if col then for k, c in pairs(KIT.byKey) do if c == col then KIT.byKey[k] = nil end end end
        end
        if not col then return nil end
    end
    if not KIT.setCell(col, bag, slot, id, sh) then return nil end
    KIT.byKey[key] = col
    return col
end
-- the cell's PreClick (bags-de1): the slot no longer holds the cell's item ("moved"), or a "u" cell whose pick has ended ("nopick") = the
-- cell is cleared just before the click (out of combat), so the click does nothing. KIT.void = the last such, for the tap's proof
function KIT.guard(btn, col)
    if btn.wcKitGuard then return end
    btn.wcKitGuard = true
    pcall(btn.HookScript, btn, "PreClick", function()
        if InCombatLockdown() then return end
        local st = KIT.cst[col]
        if not st then return end
        local why
        if bagItemID(st.b, st.s) ~= st.id then why = "moved"
        elseif st.sh == "u" and S(function() return SpellIsTargeting() end) ~= true then why = "nopick" end
        if why then KIT.void = { col = col, why = why, at = GetTime() }; KIT.clearCell(col) end
    end)
end
-- the place of the bag item (bag, slot) in the pick's list (the grid's X while a pick is on goes to it; review 2026-11-11)
function KIT.rowOf(bag, slot)
    for i, e in ipairs(KIT.list()) do if e.src == "bag" and e.bag == bag and e.s == slot then return i end end
end
-- bags-kit3 review: a row's item (where + what), so the cursor stays on the item the player sees when the list changes under it
function KIT.key(e)
    if not e then return nil end
    if e.src == "inv" then return "i" .. tostring(e.slot) .. ":" .. tostring(e.id) end
    return "b" .. tostring(e.bag) .. ":" .. tostring(e.s) .. ":" .. tostring(e.id)
end
function KIT.find(list, key)
    if not key then return nil end
    for i, e in ipairs(list) do if KIT.key(e) == key then return i end end
end
-- the room's cursor in `list`: on its item if still listed, else the same place (clamped) and that row's item from now on
function KIT.syncSel(list)
    local i = KIT.find(list, ctl.tkey)
    if not i then
        i = math.max(1, math.min(#list, ctl.tsel or 1))
        ctl.tkey = KIT.key(list[i])
    end
    ctl.tsel = i
    return i
end
-- the room's target document: the list as the app will draw it, the cursor on its item (the item A then answers)
function KIT.targetDoc()
    local list = KIT.list()
    return { kit = KIT.kitDoc(), list = list, sel = KIT.syncSel(list), cx = KIT.probe() }
end
-- bags-kit3: can the pick go on the item at `loc`? The game's own checks, called directly (never ItemButtonUtil's, which answers for the
-- bank while it is open): enchant = DoesItemMatchTargetEnchantingSpell (paperdollframe.lua's CanApplyItemEnchant), cond =
-- DoesItemMatchSpellItemCondition; other picks the game checks on the click itself. No answer = offered (the game still says no)
-- char-more1: the game's plain answer (true / false), nil when it gave none or the pick type has no check
function KIT.answer(loc, ctx)
    if not loc then return nil end
    local fn
    if ctx == "enchant" then fn = function() return C_Item.DoesItemMatchTargetEnchantingSpell(loc) end
    elseif ctx == "cond" then fn = function() return C_Item.DoesItemMatchSpellItemCondition(loc) end
    else return nil end
    local m = S(fn)
    if m == true or m == false then return m end
    return nil
end
function KIT.match(loc, ctx)
    if not loc then return true end
    local m = KIT.answer(loc, ctx)
    if m == nil then return true end
    return m
end
local INV_ORDER = { 5, 7, 10, 8, 6, 3, 1, 9, 16, 17, 18, 15, 2, 11, 12, 13, 14, 4, 19 }   -- chest, legs, hands, feet first (kits)
-- the worn gear the pick can go on (bags-kit3: what does not fit is left out); none for Disenchant / Feed Pet (bag items only)
function KIT.worn(ctx)
    local out = {}
    if ctx == "de" or ctx == "feed" then return out end
    for _, slot in ipairs(INV_ORDER) do
        local id = SN(function() return GetInventoryItemID("player", slot) end)
        if id then
            local loc = CALL(function() return ItemLocation end) and select(2, CALL(function() return ItemLocation:CreateFromEquipmentSlot(slot) end)) or nil
            if KIT.match(loc, ctx) then
                out[#out + 1] = { src = "inv", slot = slot, id = id,
                    name = S(function() return (C_Item.GetItemNameByID(id)) end) or S(function() return (GetItemInfo(id)) end),
                    icon = SN(function() return GetInventoryItemTexture("player", slot) end),
                    q = SN(function() return GetInventoryItemQuality("player", slot) end), ok = true }
            end
        end
    end
    return out
end
-- char-more1 THE KITS' FITS, LEARNED FROM THE GAME (the user, 2026-11-13: Enchant = "enchant/kit/stone/oil items in the bags that fit"):
-- whether a kit may go on a piece can only be asked while that kit's pick is on, so it is asked then, for every worn piece at once, and
-- kept: WC._kitFits[kit id][worn slot] = { w = the worn piece's id, ok = the game's answer }. A kit row then says the answers that still
-- hold for what is worn now (kfy / kfn). Only the picks with a real check (enchant / cond); the rest are never guessed. Session only.
WC._kitFits = WC._kitFits or {}
function KIT.learn(kitId, ctx)
    if type(kitId) ~= "number" or (ctx ~= "enchant" and ctx ~= "cond") then return false end
    local t = WC._kitFits[kitId] or {}
    local n = 0
    for _, slot in ipairs(INV_ORDER) do
        local id = invItemID(slot)
        if id then
            local loc = CALL(function() return ItemLocation end) and select(2, CALL(function() return ItemLocation:CreateFromEquipmentSlot(slot) end)) or nil
            local m = KIT.answer(loc, ctx)
            if m ~= nil then
                local was = t[slot]
                if not (was and was.w == id and was.ok == m) then n = n + 1 end
                t[slot] = { w = id, ok = m }
            end
        end
    end
    WC._kitFits[kitId] = t
    return n > 0                                     -- something new: the bags are sent again
end
-- a kit's answers for the pieces worn now: kfy, kfn (",5,7," each; nil = none)
function WC._kitFitOf(kitId)
    local t = WC._kitFits[kitId]
    if not t then return nil, nil end
    local y, n = ",", ","
    for slot = 1, 19 do
        local r = t[slot]
        if r and invItemID(slot) == r.w then
            if r.ok then y = y .. slot .. "," else n = n .. slot .. "," end
        end
    end
    return (y ~= ",") and y or nil, (n ~= ",") and n or nil
end
-- the kit whose pick is on now (KIT.pickKit, set as it starts) is asked about, once its type is known
local function kitLearnNow()
    if KIT.pickKit and KIT.on() and KIT.learn(KIT.pickKit, KIT.ctx()) then sendSoon("kitfit") end
end
KIT.learnNow = kitLearnNow
-- the worn slots the pick can go on, as ",5,10," (the itarget document: the Character room taps its slot only when it is listed)
function KIT.wornOk(ctx)
    local s = ","
    for _, r in ipairs(KIT.worn(ctx)) do s = s .. r.slot .. "," end
    return s
end
-- bags-kit3 Feed Pet: the bags' items the pet can eat (the game's own gamepad Feed Pet check, containerframe.lua), highest item level
-- first, at most KIT.MAX; nil when the check cannot be asked (the caller lists as before). Too-low food: the game's own error
function KIT.food()
    local okF, f = CALL(function() return C_PetInfo.CanPetEatItem end)
    if not (okF and type(f) == "function") then return nil end
    local rows = {}
    for _, e in ipairs(layout()) do
        local n = SN(function() return C_Container.GetContainerNumSlots(e.bag) end) or 0
        for s = 1, n do
            local id = bagItemID(e.bag, s)
            if id then
                local v = S(function() return C_PetInfo.CanPetEatItem(id) end)
                if v == true then
                    local info = infoAt(e.bag, s)
                    local okI, _, _, _, il = CALL(function() return GetItemInfo(id) end)
                    rows[#rows + 1] = { src = "bag", bag = e.bag, s = s, id = id,
                        name = S(function() return (C_Item.GetItemNameByID(id)) end) or S(function() return (GetItemInfo(id)) end),
                        icon = info and SN(function() return info.iconFileID end) or nil,
                        q = info and SN(function() return info.quality end) or nil, ok = true,
                        il = okI and SN(function() return il end) or 0, at = #rows + 1 }
                elseif v ~= false then
                    return nil                                       -- no plain answer: the list as before
                end
            end
        end
    end
    table.sort(rows, function(a, b) if a.il ~= b.il then return a.il > b.il end return a.at < b.at end)
    for i = #rows, KIT.MAX + 1, -1 do rows[i] = nil end
    for _, r in ipairs(rows) do r.at = nil end
    return rows
end
function KIT.list(ctx)
    ctx = ctx or KIT.ctx() or "spell"
    if ctx == "feed" then
        local f = KIT.food()
        if f then return f end
        ctx = "spell"                                                -- CanPetEatItem could not be asked: the list as before
    end
    local de = ctx == "de"
    local out = KIT.worn(ctx)                                        -- Disenchant: never worn gear (the game takes bag items only)
    -- (the player's report, 2026-11-10, issue 90: "feed pet brings up item select, but doesnt show consumables"): a pick a SPELL started
    -- (not an armor kit or stone used from the bags just now) and not an enchant / item-condition pick lists the bags' consumables too,
    -- food and drink first; the game itself says no on the click to anything the spell cannot take
    if ctx == "spell" and not (KIT.last and GetTime() - KIT.last.t < 2) then
        local food, other = {}, {}
        for _, e in ipairs(layout()) do
            local n = SN(function() return C_Container.GetContainerNumSlots(e.bag) end) or 0
            for s = 1, n do
                local id = bagItemID(e.bag, s)
                if id then
                    local okI, _, _, _, _, _, cls, sub = CALL(function() return C_Item.GetItemInfoInstant(id) end)
                    cls, sub = okI and SN(function() return cls end) or nil, okI and SN(function() return sub end) or nil
                    if cls == 0 then
                        local info = infoAt(e.bag, s)
                        local row = { src = "bag", bag = e.bag, s = s, id = id,
                            name = S(function() return (C_Item.GetItemNameByID(id)) end) or S(function() return (GetItemInfo(id)) end),
                            icon = info and SN(function() return info.iconFileID end) or nil,
                            q = info and SN(function() return info.quality end) or nil, ok = true }
                        if sub == 5 then food[#food + 1] = row else other[#other + 1] = row end
                    end
                end
            end
        end
        local front = {}
        for _, r in ipairs(food) do front[#front + 1] = r end
        for _, r in ipairs(other) do front[#front + 1] = r end
        -- (issue #126) the consumables before the worn gear, never past the cap: 10 rows are left for the bags' gear
        local room = math.max(0, KIT.MAX - #out - 10)
        for i = math.min(#front, room), 1, -1 do table.insert(out, 1, front[i]) end
    end
    -- bags-kit4 (issue #332, 1.0.32: "mage comprehension skill does not work, it opens the backpack room but states that there are no
    -- elligible items in bags despite unread scrolls"; its report: itarget ctx=cond, the target spell 1296017): an item-condition pick
    -- whose target is NOT gear (a scroll to read) listed only worn and bag gear, so its list was empty. The bags' other items the game
    -- itself says the pick can go on (DoesItemMatchSpellItemCondition answers true) come first now; an item it says no to, or gives no
    -- answer for, stays out (an armor kit's list is unchanged). Each such row is its own bag cell (a non-gear item: the right-click)
    if ctx == "cond" then
        local front = {}
        for _, e in ipairs(layout()) do
            local n = SN(function() return C_Container.GetContainerNumSlots(e.bag) end) or 0
            for s = 1, n do
                if #front >= KIT.MAX then break end
                local id = bagItemID(e.bag, s)
                if id then
                    local okI, _, _, _, loc = CALL(function() return C_Item.GetItemInfoInstant(id) end)
                    loc = okI and S(function() return loc end) or nil
                    local gear = type(loc) == "string" and loc ~= "" and loc:byte(1) > 3 and not NOT_WORN[loc]
                    if not gear then
                        local il = select(2, CALL(function() return ItemLocation:CreateFromBagAndSlot(e.bag, s) end))
                        if KIT.answer(il, "cond") == true then
                            local info = infoAt(e.bag, s)
                            front[#front + 1] = { src = "bag", bag = e.bag, s = s, id = id,
                                name = S(function() return (C_Item.GetItemNameByID(id)) end) or S(function() return (GetItemInfo(id)) end),
                                icon = info and SN(function() return info.iconFileID end) or nil,
                                q = info and SN(function() return info.quality end) or nil, ok = true }
                        end
                    end
                end
            end
        end
        local room = math.max(0, KIT.MAX - #out - 10)
        for i = math.min(#front, room), 1, -1 do table.insert(out, 1, front[i]) end
    end
    local shape                                                      -- bags-de1: the waiting pick's cell shape, asked once
    for _, e in ipairs(layout()) do
        local n = SN(function() return C_Container.GetContainerNumSlots(e.bag) end) or 0
        for s = 1, n do
            if #out >= KIT.MAX then break end
            local id = bagItemID(e.bag, s)
            if id then
                local okI, _, _, _, loc, _, cls = CALL(function() return C_Item.GetItemInfoInstant(id) end)
                loc = okI and S(function() return loc end) or nil
                cls = okI and SN(function() return cls end) or nil
                if type(loc) == "string" and loc ~= "" and loc:byte(1) > 3 and not NOT_WORN[loc] then
                    local info = infoAt(e.bag, s)
                    local q = info and SN(function() return info.quality end) or nil
                    -- Disenchant: weapons (2) and armour (4) of quality 2 or better only
                    if not de or ((cls == 2 or cls == 4) and type(q) == "number" and q >= 2) then
                        local il = select(2, CALL(function() return ItemLocation:CreateFromBagAndSlot(e.bag, s) end))
                        -- bags-kit3: what the pick cannot go on is left out (before a cell is held for it)
                        if de or KIT.match(il, ctx) then
                            -- its target cell (bags-de1: "t", target only; "u" only the device fallback), never the bag cell's equip
                            shape = shape or KIT.pickShape()
                            local cell = KIT.cellFor(e.bag, s, id, shape)
                            -- the one grey row left: it fits, but a fight keeps its cell from being set
                            out[#out + 1] = { src = "bag", bag = e.bag, s = s, id = id, cell = cell,
                                name = S(function() return (C_Item.GetItemNameByID(id)) end) or S(function() return (GetItemInfo(id)) end),
                                icon = info and SN(function() return info.iconFileID end) or nil,
                                q = q, ok = cell and true or nil }
                        end
                    end
                end
            end
        end
    end
    return out
end
-- what is being applied: a named spell's own name (Disenchant, Feed Pet), else the last item used from the bags, if it was just now
-- bags-de1: C_Spell.GetSpellInfo first (name / iconID / castTime), the old GetSpellInfo second; ct = the cast time in ms when known
local function spellDoc(id)
    local si = S(function() return C_Spell.GetSpellInfo(id) end)
    if type(si) == "table" then
        local name = KIT.txt(S(function() return si.name end))
        if name then
            return { spell = id, name = name, icon = SN(function() return si.iconID end), ct = SN(function() return si.castTime end) }
        end
    end
    local ok, name, _, icon, ct = CALL(function() return GetSpellInfo(id) end)
    if ok and type(name) == "string" and WC.isPlain(name) then
        return { spell = id, name = name, icon = SN(function() return icon end), ct = SN(function() return ct end) }
    end
end
function KIT.kitDoc()
    if KIT.de() then local d = spellDoc(KIT.DE); if d then return d end end              -- issue #126: the spell's own name over the list
    if KIT.on() and KIT.feed() then local d = spellDoc(KIT.FEED); if d then return d end end   -- bags-kit3: Feed Pet named too
    local l = KIT.last
    if not l or GetTime() - l.t > 30 then return nil end
    return { id = l.id, name = S(function() return (C_Item.GetItemNameByID(l.id)) end) or S(function() return (GetItemInfo(l.id)) end),
             icon = SN(function() return C_Item.GetItemIconByID(l.id) end) }
end
-- a bag item was used (X, or the app's tap on its cell): remembered so a pick that starts now names it, and learns it as a kit
function KIT.used(bag, slot)
    local id = bagItemID(bag, slot)
    if id then KIT.last = { id = id, t = GetTime() } end
end
-- char-more1: a tap on a bag item's own secure cell (its use ran just now): a kit / stone / oil / enchant (a "Use:" item that is not gear)
-- is remembered as KIT.used is, and a pick that is on already is that kit's
function WC._kitUsedAt(bag, slot)
    local id = bagItemID(bag, slot)
    if not id then return end
    local okSp, spName = CALL(function() return C_Item.GetItemSpell(id) end)
    if not (okSp and type(spName) == "string") then return end
    local okI, _, _, _, loc = CALL(function() return C_Item.GetItemInfoInstant(id) end)
    loc = okI and S(function() return loc end) or nil
    if type(loc) == "string" and loc ~= "" and not NOT_WORN[loc] then return end        -- gear: its cell equipped it
    KIT.used(bag, slot)
    if KIT.on() then KIT.pickKit = id; kitLearnNow() end
end
-- bags-kit2 (issue #89): the GAME is asking about the pick - its replace / bind question for the item just chosen, or any pop-up
-- its controller focuses (its A / B answer that one, never our list). Read only; the pop-up's name, nil when none asks
-- bags-de1: END_BOUND_TRADEABLE too (disenchanting a bind-on-pickup item still inside its trade window: the game asks, gamedialogdefs.lua)
KIT.ASKS = { REPLACE_ENCHANT = true, TRADE_REPLACE_ENCHANT = true, BIND_ENCHANT = true, ACTION_WILL_BIND_ITEM = true,
             REPLACE_TRADESKILL_ENCHANT = true, END_BOUND_TRADEABLE = true }
function KIT.asking()
    for i = 1, 4 do
        local f = _G["StaticPopup" .. i]
        if type(f) == "table" and S(function() return f:IsShown() end) == true then
            local which = S(function() return f.which end)
            if type(which) == "string" and KIT.ASKS[which] then return which end
            if S(function() return f.gamepadFocused end) == true then return type(which) == "string" and which or "?" end
        end
    end
    return nil
end
-- the pick started / ended: the room's cursor follows; any route says it (an "itarget" document: the Character room waits for it)
function KIT.step()
    -- bags-kit2 (issue #89): the game's question about the pick came or went: report, so syncPass hands A / B over (or back)
    -- bags-de1: the Disenchant grid ("bulk") hands the game's own question to the game the same way (END_BOUND_TRADEABLE)
    local listing = ctl.on and (ctl.mode == "target" or ctl.mode == "bulk")
    local ask = listing and KIT.asking() or nil
    if ask ~= KIT.askWas then
        KIT.askWas = ask
        if listing then report(ask and ("pick-ask-" .. ask) or "pick-answered") end
    end
    local on = KIT.on()
    -- the pick's cells free again (and cleared); bags-de1: never while the Disenchant grid is open (its pick goes on and off with every
    -- disenchant; its cells are let go one at a time, the least recently used first, KIT.cellFor)
    if not on and next(KIT.byKey) ~= nil and not InCombatLockdown() and not (ctl.on and ctl.mode == "bulk") then
        for _, c in pairs(KIT.byKey) do KIT.clearCell(c) end
        KIT.byKey = {}
    end
    -- bags-kit3 review: the pick's type changed while the list is up (the game's item context came late): the app draws it again
    local cxNow = on and KIT.ctx() or nil
    if cxNow ~= KIT.ctxWas then
        local was = KIT.ctxWas
        KIT.ctxWas = cxNow
        if was and cxNow and ctl.on and ctl.mode == "target" then report(nil) end
        if cxNow then kitLearnNow() end                 -- char-more1: the pick's type came late: its kit is asked about now
    end
    if on ~= KIT.was then
        KIT.was = on
        if on and KIT.last and GetTime() - KIT.last.t < 2 then WC._kitLearned[KIT.last.id] = true end
        -- char-more1: the kit this pick is for (used just now), asked about for every worn piece; nobody's once the pick ends
        if on then KIT.pickKit = (KIT.last and GetTime() - KIT.last.t < 2) and KIT.last.id or KIT.pickKit; kitLearnNow()
        else KIT.pickKit = nil end
        -- bags-kit3: the pick's type and the worn slots it can go on (the Character room taps its slot only when listed), and what
        -- the game said of the pick (cx: the device check of TargetSpellChecksItemCondition for kits)
        local cx = on and KIT.probe() or nil
        WC.emit({ type = "itarget", on = on, kit = on and KIT.kitDoc() or nil, ctx = cx and cx.ctx or nil,
                  wornOk = cx and KIT.wornOk(cx.ctx) or nil, cx = cx })
        if ctl.on and not on and ctl.mode == "target" then toGrid(ctl.applied and "applied" or "pick-ended"); ctl.applied = nil end
    end
    -- (review 2026-11-11, issue #126) the room's grid while a pick is on goes to the list on EVERY step, not only as the pick starts: a
    -- room taken mid-pick (Disenchant cast from the bar, then the Bags room) stayed on the grid, and its X / Use tapped the bag's own
    -- cell, which EQUIPS gear. Not in the moments after B's /stopspelltarget (the pick is ending)
    if ctl.on and on and ctl.mode == "grid" and not ctl.pick and not (KIT.stopT and GetTime() - KIT.stopT < KIT.STOP_HOLD) then
        -- bags-de1: a Disenchant pick opens the Disenchant grid over the Bags grid (B goes back to it), never the old list
        if KIT.ctx() == "de" and WC._de then WC._de.open(true, "de-pick")
        else ctl.mode = "target"; ctl.tsel = 1; ctl.tkey = nil; report("pick-item") end
    end
end
C_Timer.NewTicker(0.25, function() pcall(KIT.step) end)
-- bags-kit3: the game's own word that the pick's item context changed (read-only listeners; the step above still runs every 0.25 s)
do
    local kf = CreateFrame("Frame")
    for _, e in ipairs({ "UPDATE_SPELL_TARGET_ITEM_CONTEXT", "ENCHANT_SPELL_SELECTED" }) do pcall(kf.RegisterEvent, kf, e) end
    kf:SetScript("OnEvent", function(_, e) KIT.evt = e; KIT.evtN = KIT.evtN + 1; pcall(KIT.step) end)
end
-- worn gear as a pick's target: the secure row "binv", one cell per slot (type item, item = the slot: the game's /use <slot>)
-- col 19 = STOP THE PICK: the game's own /stopspelltarget (a secure macro attribute, the cancel the add-on itself may not make)
-- cols 20..38 = the bag items' target cells (issue #126; bags-de1: the shapes "t" / "c" / "u", KIT.cellFor, set out of combat)
-- col 39 = STOP THE CAST (bags-de1): the game's own /stopcasting (the Disenchant grid's B during a cast; SpellStopCasting is protected)
KIT.STOP_COL = 19
if WC.pad and WC.pad.secureRow then
    pcall(WC.pad.secureRow, "binv", KIT.CELLS, function(btn, col)
        if col == KIT.STOP_COL then btn:SetAttribute("type", "macro"); btn:SetAttribute("macrotext", "/stopspelltarget"); return end
        if col == KIT.STOPCAST_COL then btn:SetAttribute("type", "macro"); btn:SetAttribute("macrotext", "/stopcasting"); return end
        if col >= KIT.CELL0 then
            KIT.cells[col] = btn
            KIT.guard(btn, col)
            pcall(KIT.attrs, btn, KIT.cst[col])
            return
        end
        btn:SetAttribute("type", "item")
        btn:SetAttribute("item", tostring(col + 1))
        emptyGuard(btn, "w" .. (col + 1), wornSlotEmpty(col + 1))
    end, function(col)
        if col == KIT.STOP_COL then
            WC.emit({ type = "cap", kitStop = true })
            if WC._de then pcall(WC._de.stopped) end                -- bags-de1: the grid's B waited for the stop to land, then closes
            return
        end
        if col == KIT.STOPCAST_COL then WC.emit({ type = "cap", kitStopCast = true }); return end
        if col >= KIT.CELL0 then
            -- bags-de1: the cell's shape and item, and why it did nothing when its PreClick voided it (the tap's proof)
            local st, v = KIT.cst[col], KIT.void
            WC.emit({ type = "cap", kitTap = { cell = col, sh = st and st.sh or nil, id = st and st.id or nil,
                void = (v and v.col == col and GetTime() - v.at < 1) and v.why or nil, combat = InCombatLockdown() and true or false } })
            return
        end
        WC.emit({ type = "cap", kitTap = emptyFlags({ slot = col + 1, combat = InCombatLockdown() and true or false }, "w" .. (col + 1)) })
    end)
end

-- ---------------------------------------------------------------- bags-de1: THE DISENCHANT GRID (mode "bulk"; see the header)
-- The user, 2026-11-16 (their list): "A custom grid of bag items that can be disenchanted. Hover shows the full tooltip, as in Bags. It stays
-- open after each one; B closes it. The disenchant cast bar is visible." Their picks the same day (design_disenchant.md, every RECOMMENDED
-- option): Disenchant only, built so others can join later; blue and purple ask once each time ("Disenchant?" A Yes / B No, Destroy's ask);
-- loot the game's way (Auto Loot: a small strip of what came; without it the loot pop-up, then the grid comes back); bag order; a Disenchant
-- icon on the Bags room's top strip; a small green arrow on tiles whose stat changes are all gains.
--   OPENS: any cast of the spell (the game opens its bags for the pick: onBagsShown -> tryTake -> "bulk"; a Bags grid already held goes to
--     it too, KIT.step), or the strip's icon (BAR "de" / a finger: bmvq 27 then column DE.TAP_COL). Opening it is never a game action.
--   LISTS: the bags' weapons and armour of quality 2 (green) and up, in bag order; locked ones greyed (lk); refused ones hidden for the session
--     (per item AND Enchanting rank: a skill-up offers it again); never worn gear.
--   A: ONE press = one secure tap on that item's own cell (KIT.cellFor "c": cast + target, or "t" while a pick already waits); refused while
--     casting ("busy"), while a loot window is open ("loot") and in a fight ("combat": the room's red flash); blue and better ask first.
--   B, in order: in a fight always Home (i151; a waiting pick stopped on the way, never a cast: it may be the fight's); OUR cast running =
--     /stopcasting (binv 39) and stay (never another spell's cast); a pick waiting = /stopspelltarget (binv 19), then close; else close:
--     back to the Bags grid when opened from it, else Home (leave "B").
--   LOOT: Interact i161 lets the grid keep the pad through an AUTO loot (spec.lootPass); any other loot pop-up takes the pad and the grid
--     takes it back itself at LOOT_CLOSED, the cursor kept (DE.resume, the ordinary take). Loot left on the top screen (the Hub's switch):
--     A waits for LOOT_CLOSED.
-- Read-only calls + the player's own secure taps; nothing saved (no macro, binding or CVar); no game window opened or closed from here.
local DE = { SPELL = 13262, MAX = 100, ASK_Q = 3, REFUSE_S = 1.2, DONE_S = 3, GOT_S = 6, RESUME_S = 30, STOP_WAIT = 1.0, PEND_S = 1.0,
             TAP_COL = 9, refused = {}, doneN = 0, failN = 0, gotN = 0, tries = 0,
             REFUSALS = { "ERR_CANT_BE_DISENCHANTED", "ERR_CANT_BE_DISENCHANTED_SKILL", "SPELL_FAILED_CANT_BE_DISENCHANTED",
                          "SPELL_FAILED_CANT_BE_DISENCHANTED_SKILL", "SPELL_FAILED_LOW_CASTLEVEL" } }
WC._de = DE
-- the player knows the spell: C_SpellBook.ContainsAnyDisenchantSpell (the design's check), else IsPlayerSpell / IsSpellKnown
function DE.known()
    for _, f in ipairs({ function() return C_SpellBook.ContainsAnyDisenchantSpell() end, function() return IsPlayerSpell(DE.SPELL) end,
                         function() return IsSpellKnown(DE.SPELL) end }) do
        local v = S(f)
        if v == true or v == false then return v end
    end
    return false
end
-- its name, icon and cast time (ms), kept once read (read again only out of a fight, while the name is still missing)
function DE.spell()
    local c = DE.info
    if c and (c.n or InCombatLockdown()) then return c end
    local d = spellDoc(DE.SPELL) or {}
    local ct = d.ct
    DE.info = { n = d.name, i = d.icon, ct = (type(ct) == "number" and ct >= 0) and math.floor(ct + 0.5) or nil }
    return DE.info
end
-- the strip's word on the spell (bags doc `de`): only when the player knows it
function DE.stripDoc()
    if not DE.known() then return nil end
    local sp = DE.spell()
    return { n = sp.n, i = sp.i, ct = sp.ct, col = DE.TAP_COL }
end
-- the Enchanting rank (skill line 333; 0 = unknown): a refused item is offered again after a skill-up
function DE.rank()
    local ok, p1, p2 = CALL(function() return GetProfessions() end)
    if not ok then return 0 end
    for _, i in pairs({ p1, p2 }) do
        if type(i) == "number" then
            local okI, _, _, rank, _, _, _, line = CALL(function() return GetProfessionInfo(i) end)
            if okI and SN(function() return line end) == 333 then return SN(function() return rank end) or 0 end
        end
    end
    return 0
end
function DE.key(e) return "b" .. tostring(e.b) .. ":" .. tostring(e.s) .. ":" .. tostring(e.id) end
-- a worn slot `sl` the gear (equip location `loc`) could go in is EMPTY (an upgrade there: anything beats nothing); not the off hand while a
-- two-hander is worn (it would take the two-hander off), nor a one-hander's off hand for a class that cannot dual wield
function DE.emptyFor(loc)
    for _, sl in ipairs(ACT.SLOTS[loc] or {}) do
        local skip = sl == 17 and ((loc == "INVTYPE_WEAPON" and not ACT.dualWield()) or ACT.loc(invItemID(16)) == "INVTYPE_2HWEAPON")
        if not skip and not invItemID(sl) then return true end
    end
    return false
end
-- the small green arrow (the user's pick 6a: "a small green arrow on tiles whose stat changes are all gains"): from the KEPT tooltip only (read
-- ahead out of a fight): usable (no red line), and an empty slot it fits or the game's stat changes against a worn piece all gains
function DE.up(b, s, id, loc)
    local e = TIP.get("bag " .. b .. " " .. s .. " " .. id, b, s)
    if not e or ACT.unusable(e.val) then return false end
    return DE.emptyFor(loc) or ACT.allGain(e.val)
end
-- THE LIST, in bag order (the user's pick 4a)
function DE.list()
    local out, rk = {}, DE.rank()
    for _, e in ipairs(layout()) do
        local n = SN(function() return C_Container.GetContainerNumSlots(e.bag) end) or 0
        for s = 1, n do
            if #out >= DE.MAX then return out end
            local id = bagItemID(e.bag, s)
            if id and not DE.refused[id .. ":" .. rk] then
                local okI, _, _, _, loc, _, cls = CALL(function() return C_Item.GetItemInfoInstant(id) end)
                loc = okI and S(function() return loc end) or nil
                cls = okI and SN(function() return cls end) or nil
                if (cls == 2 or cls == 4) and type(loc) == "string" and loc ~= "" and loc:byte(1) > 3 and not NOT_WORN[loc] then
                    local info = infoAt(e.bag, s)
                    local q = info and SN(function() return info.quality end) or nil
                    if type(q) == "number" and q >= 2 then
                        local row = { b = e.bag, s = s, id = id, q = q }
                        if flag(info, "isLocked") == true then row.lk = true end
                        if DE.up(e.bag, s, id, loc) then row.up = true end
                        out[#out + 1] = row
                    end
                end
            end
        end
    end
    return out
end
-- the cursor on its ITEM when it is still listed, else the same place (clamped) and that item from now on; the room's ctl.bag / ctl.slot
-- follow it (the tooltip, TIP). Returns its index (0 = an empty list)
function DE.sync(list)
    local i
    if ctl.bkey then for n, e in ipairs(list) do if DE.key(e) == ctl.bkey then i = n; break end end end
    if not i then
        i = math.max(1, math.min(#list, ctl.bsel or 1))
        ctl.bkey = list[i] and DE.key(list[i]) or nil
    end
    ctl.bsel = i
    local e = list[i]
    if e then ctl.bag, ctl.slot = e.b, e.s end
    return e and i or 0
end
-- a cast or channel of the player's runs now (UnitCastingInfo / UnitChannelInfo: plain for "player"), or ours has not ended yet
function DE.casting()
    for _, f in ipairs({ function() return (UnitCastingInfo("player")) end, function() return (UnitChannelInfo("player")) end }) do
        if KIT.txt(S(f)) then return true end          -- (review: S's "absent" is a string too: never read as a cast)
    end
    return DE.cast ~= nil
end
-- OUR cast runs (the grid's own disenchant, or a cast of the spell's name): only that one is B's to stop - never another spell's cast
function DE.ourCast()
    if DE.cast ~= nil then return true end
    local n = KIT.txt(S(function() return (UnitCastingInfo("player")) end))
    local sp = DE.spell().n
    return n ~= nil and sp ~= nil and n == sp
end
-- review of bags-de1: a pick of ANOTHER spell waits (never the grid's to answer)
function DE.otherPick() return KIT.on() and KIT.ctx() ~= "de" end
-- a press still waiting for its cast to start (its tap on the way, the cast not begun): a second A now would cast again ("busy")
function DE.pending()
    local t = DE.tap
    return t ~= nil and not t.ended and DE.cast == nil and GetTime() - t.at < DE.PEND_S
end
-- the room's document while the grid is open (bags.ctl.bulk)
function DE.doc()
    local sp = DE.spell()
    local list = DE.list()
    local d = { n = sp.n, i = sp.i, ct = sp.ct, list = list, sel = DE.sync(list), busy = (DE.casting() or DE.otherPick()) or nil, loot = DE.lootOpen or nil,
                fight = InCombatLockdown() or nil, pick = KIT.on() or nil, back = ctl.deFrom and "grid" or "home",
                stop = (DE.ourCast() and not InCombatLockdown()) or nil }               -- B stops our cast (else B closes)
    local c = DE.cast
    if c then d.cast = { b = c.b, s = c.s, id = c.id } end
    d.done, d.fail = DE.done, DE.fail
    local g = DE.got
    if g and GetTime() - g.at < DE.GOT_S then d.got = { n = g.n, items = g.items, auto = g.auto } end
    d.ask = ctl.deAsk
    if d.pick then d.cx = KIT.probe() end           -- the device check: sct / scti for the target-only cells
    return d
end
-- open the grid: [fromGrid] = over the Bags grid (B goes back to it; the grid's cursor is kept), else over nothing (B goes Home)
function DE.open(fromGrid, note)
    ctl.deFrom = fromGrid and { bag = ctl.bag, slot = ctl.slot, zone = ctl.zone, barAt = ctl.barAt } or nil
    ctl.mode = "bulk"; ctl.zone = nil; ctl.pick = nil; ctl.deAsk = nil; ctl.rare = nil; ctl.dest = nil
    ctl.bsel = 1; ctl.bkey = nil
    DE.resume = nil
    report(note or "de-open")
end
-- the cells that would cast are cleared when the grid closes (out of combat: in a fight KIT.fightOff did it at the pull)
function DE.clearCast()
    if InCombatLockdown() then return end
    for col = KIT.CELL0, KIT.CELL_LAST do
        local st = KIT.cst[col]
        if st and st.sh == "c" then KIT.clearCell(col) end
    end
end
-- close it: back to the Bags grid it came from (its cursor where it was), else Home (the player's B: leave "B"); in a fight always Home
function DE.close(note)
    ctl.deAsk = nil
    DE.clearCast()
    DE.resume, DE.stopThen = nil, nil
    local from = ctl.deFrom
    ctl.deFrom = nil
    if from and not InCombatLockdown() then
        ctl.bag, ctl.slot = from.bag, from.slot
        if from.zone == "bar" then ctl.zone = "bar"; ctl.barAt = from.barAt end
        toGrid(note or "de-closed")
        return
    end
    ctl.mode = "grid"
    leave("B")
end
-- binv 19's tap landed (the grid's B with a pick waiting): now it closes
function DE.stopped()
    if not DE.stopThen then return end
    DE.stopThen = nil
    if ctl.on and ctl.mode == "bulk" then DE.close("cancelled") end
end
-- a refusal the app shows on the tile (red flash): [e] the item, else the cursor's
function DE.refuse(e, why)
    DE.failN = DE.failN + 1
    e = e or {}
    DE.fail = { n = DE.failN, b = e.b, s = e.s, id = e.id, why = why }
    report("de-" .. why)
end
-- our disenchant ended badly ([why]: "cast" = it failed / was interrupted, "stop" = the player's B, "refused" = the game said it cannot)
function DE.failed(t, why)
    if t.ended then
        if why == "refused" and DE.fail and DE.fail.id == t.id then DE.fail.why = "refused"; if ctl.on then report("de-refused") end end
        return
    end
    t.ended = true
    DE.cast = nil
    DE.failN = DE.failN + 1
    DE.fail = { n = DE.failN, b = t.b, s = t.s, id = t.id, why = why }
    if ctl.on then report("de-" .. why) end
end
-- A on the item: the checks, then ONE secure tap on its own cell (the shape that can never equip)
function DE.fire(e, asked)
    if InCombatLockdown() then DE.refuse(e, "combat"); fightRefused(); return end
    if DE.casting() or DE.pending() then DE.refuse(e, "busy"); return end
    if DE.lootOpen then DE.refuse(e, "loot"); return end
    -- review of bags-de1: ANOTHER spell's pick waiting (a kit, an enchant, Feed Pet: a cast by touch on the top screen): the grid's A would
    -- answer it with this item (a "t" cell targets whatever pick waits) - refused; B stops that pick
    if DE.otherPick() then DE.refuse(e, "pick"); return end
    if bagItemID(e.b, e.s) ~= e.id then ctl.bkey = nil; report("de-moved"); return end      -- the asked item left its slot: nothing used
    local sh = KIT.on() and KIT.pickShape() or "c"
    local col = KIT.cellFor(e.b, e.s, e.id, sh, true)
    if not col then report("de-nocell"); return end
    DE.tap = { b = e.b, s = e.s, id = e.id, at = GetTime(), cell = col, sh = sh }
    DE.stopping = nil
    ctl.use = { cell = col, seq = ctl.seq + 1, ct = DE.spell().ct }        -- ct: the app's cast bar starts at the press (predicted)
    report(asked and "de-go-asked" or "de-go")
end
-- the D-pad walks the grid as the app draws it: COLS a row, in list order
function DE.move(key, list)
    local n = #list
    if n == 0 then return end
    local i = DE.sync(list)
    if key == "PADDLEFT" then i = math.max(1, i - 1)
    elseif key == "PADDRIGHT" then i = math.min(n, i + 1)
    elseif key == "PADDUP" then if i > COLS then i = i - COLS end
    elseif key == "PADDDOWN" then
        if i + COLS <= n then i = i + COLS
        elseif math.ceil(i / COLS) < math.ceil(n / COLS) then i = n end              -- the last row is short: its last item
    end
    ctl.bsel, ctl.bkey = i, DE.key(list[i])
    ctl.bag, ctl.slot = list[i].b, list[i].s
end
-- B (see the header: casting / a pick waiting / close)
function DE.back()
    -- in a fight B always goes Home (i151; the design's B rule 4): a waiting pick is stopped on the way, never a cast (it may be the fight's)
    if InCombatLockdown() then
        if KIT.on() then ctl.use = { stop = true, seq = ctl.seq + 1 }; KIT.stopT = GetTime() end
        DE.close(nil)
        return
    end
    if DE.ourCast() then
        ctl.use = { stopcast = true, seq = ctl.seq + 1 }; DE.stopping = GetTime(); report("de-stop")
        return
    end
    if KIT.on() then
        ctl.use = { stop = true, seq = ctl.seq + 1 }; KIT.stopT = GetTime(); DE.stopThen = GetTime()
        report("de-stopping")
        C_Timer.After(DE.STOP_WAIT, function()
            if DE.stopThen and GetTime() - DE.stopThen >= DE.STOP_WAIT - 0.05 then DE.stopThen = nil; if ctl.on and ctl.mode == "bulk" then DE.close("cancelled") end end
        end)
        return
    end
    DE.close(nil)
end
-- every press while the grid holds
function DE.press(key)
    local A, B = key == "PAD1", key == "PAD2"
    -- the game's own question about this disenchant (END_BOUND_TRADEABLE): its A / B are passed (syncPass); one that still reaches the
    -- room acts on nothing here (the pass is made again)
    if (A or B) and KIT.asking() then ctl.passing = false; report("de-gameask-" .. key); return end
    if ctl.deAsk then
        -- the blue / purple ask (the user's pick 2a: "Disenchant?" A Yes / B No)
        local a = ctl.deAsk
        if A then ctl.deAsk = nil; DE.fire(a, true) elseif B then ctl.deAsk = nil; report("de-ask-no") end
        return
    end
    local list = DE.list()
    if key == "PADDUP" or key == "PADDDOWN" or key == "PADDLEFT" or key == "PADDRIGHT" then DE.move(key, list); report(nil); return end
    if B then DE.back(); return end
    if not A then return end
    if InCombatLockdown() then DE.refuse(nil, "combat"); fightRefused(); return end
    if #list == 0 then report("de-empty"); return end
    local at
    if ctl.bkey then for n, e in ipairs(list) do if DE.key(e) == ctl.bkey then at = n; break end end end
    if not at then ctl.bkey = nil; report("de-moved"); return end   -- the item the cursor showed is gone: nothing used, drawn again
    local e = list[at]
    if e.lk then DE.refuse(e, "locked"); return end
    if DE.casting() or DE.pending() then DE.refuse(e, "busy"); return end
    if DE.lootOpen then DE.refuse(e, "loot"); return end
    if (e.q or 0) >= DE.ASK_Q then ctl.deAsk = { b = e.b, s = e.s, id = e.id, q = e.q }; report("de-ask"); return end
    DE.fire(e, false)
end
-- a finger on a tile (36 + its bmv cell): the cursor there (no game action); the app sends A itself for the tile already under it
function DE.cursorTo(bag, slot)
    for n, e in ipairs(DE.list()) do
        if e.b == bag and e.s == slot then ctl.bsel, ctl.bkey = n, DE.key(e); ctl.bag, ctl.slot = bag, slot; report(nil); return true end
    end
    return false
end
-- the strip's icon (the controller's A on it, or a finger: bmvq 27 then DE.TAP_COL): the grid over the Bags grid
function DE.fromStrip()
    if not DE.known() then report("de-unknown"); return end
    if ctl.on and ctl.mode == "grid" and not ctl.pick then DE.open(true, "de-open") end
end
-- the loot window's slots right after our disenchant (the strip of what came; read-only)
function DE.readGot(auto)
    local n = SN(function() return GetNumLootItems() end) or 0
    local items = {}
    for i = 1, math.min(n, 8) do
        local ok, tex, _, qty, _, q = CALL(function() return GetLootSlotInfo(i) end)
        local icon = ok and SN(function() return tex end) or nil
        if icon then items[#items + 1] = { i = icon, c = SN(function() return qty end) or 1, q = SN(function() return q end) } end
    end
    DE.gotN = DE.gotN + 1
    DE.got = { n = DE.gotN, items = items, at = GetTime(), auto = auto or nil }
end
-- the game's refusal of our disenchant: its own words (the globals), else a message naming the spell ("... cannot be disenchanted")
function DE.isRefusal(msg)
    for _, k in ipairs(DE.REFUSALS) do
        local g = KIT.txt(S(function() return _G[k] end))
        if g and g == msg then return true end
    end
    local n = DE.spell().n
    if type(n) ~= "string" or #n < 6 then return false end
    return msg:lower():find(n:lower():sub(1, #n - 2), 1, true) ~= nil
end
-- the grid lost the pad to the loot pop-up: it takes it back itself once the loot window closed (the ordinary take, the cursor kept)
function DE.retake()
    local r = DE.resume
    if not r then return end
    if GetTime() - r.at > DE.RESUME_S then DE.resume = nil; return end
    if ctl.on then
        DE.resume = nil
        if ctl.mode ~= "bulk" then ctl.deFrom = r.from; ctl.mode = "bulk"; ctl.bkey, ctl.bsel = r.bkey, r.bsel; report("de-back") end
        return
    end
    local cap = capture()
    local okS, st = pcall(function() return cap and cap.state and cap.state() end)
    if okS and type(st) == "table" and st.holder == nil and DE.take then
        DE.take()
        if ctl.on then
            DE.resume = nil
            ctl.deFrom = r.from; ctl.mode = "bulk"; ctl.bkey, ctl.bsel = r.bkey, r.bsel
            report("de-back")
            return
        end
    end
    DE.tries = DE.tries + 1
    if DE.tries <= 8 then C_Timer.After(0.25, DE.retake) else DE.resume = nil end
end
-- the events (read-only): our cast's start / end, the game's refusal, the loot window, the pull
function DE.event(e, a1, a2, a3)
    local now = GetTime()
    if e == "LOOT_OPENED" then
        DE.lootOpen = true
        if DE.lastDone and now - DE.lastDone < DE.DONE_S then DE.readGot(S(function() return a1 end) == true) end
        if ctl.on and ctl.mode == "bulk" then report("de-loot") end
        return
    end
    if e == "LOOT_CLOSED" then
        DE.lootOpen = nil
        if DE.resume then DE.tries = 0; C_Timer.After(0.1, DE.retake) end
        if ctl.on and ctl.mode == "bulk" then report("de-loot-closed") end
        return
    end
    if e == "UI_ERROR_MESSAGE" then
        local t = DE.tap
        if not t or now - t.at > DE.REFUSE_S then return end
        local msg = KIT.txt(S(function() return a2 end))
        if not msg or not DE.isRefusal(msg) then return end
        DE.refused[t.id .. ":" .. DE.rank()] = true                -- hidden for the session (until a skill-up)
        DE.failed(t, "refused")
        return
    end
    if SN(function() return a3 end) ~= DE.SPELL then return end
    if e == "UNIT_SPELLCAST_START" then
        local t = DE.tap
        if t and not t.ended and now - t.at < 3 then DE.cast = t; if ctl.on and ctl.mode == "bulk" then report("de-casting") end end
    elseif e == "UNIT_SPELLCAST_SUCCEEDED" then
        local t = DE.cast or (DE.tap and now - DE.tap.at < 3 and DE.tap) or nil
        DE.cast = nil
        if t and not t.ended then
            t.ended = true
            DE.doneN = DE.doneN + 1
            DE.done = { n = DE.doneN, b = t.b, s = t.s, id = t.id }
            DE.lastDone = now
            if ctl.on and ctl.mode == "bulk" then report("de-done") end
        end
    else                                                            -- FAILED / FAILED_QUIET / INTERRUPTED
        local t = DE.cast or (DE.tap and now - DE.tap.at < 2 and DE.tap) or nil
        if t then DE.failed(t, (DE.stopping and now - DE.stopping < 2) and "stop" or "cast") end
    end
end
do
    local df = CreateFrame("Frame")
    for _, e in ipairs({ "UNIT_SPELLCAST_START", "UNIT_SPELLCAST_SUCCEEDED", "UNIT_SPELLCAST_FAILED", "UNIT_SPELLCAST_FAILED_QUIET",
                         "UNIT_SPELLCAST_INTERRUPTED" }) do pcall(df.RegisterUnitEvent, df, e, "player") end
    for _, e in ipairs({ "UI_ERROR_MESSAGE", "LOOT_OPENED", "LOOT_CLOSED" }) do pcall(df.RegisterEvent, df, e) end
    pcall(df.SetScript, df, "OnEvent", function(_, e, ...) pcall(DE.event, e, ...) end)
end

-- ---------------------------------------------------------------- THE ROOM'S TOP STRIP (the user, 2026-10-09: "in bags, I am not able to move my
-- cursor up to the actual bags to open or close them, same with the cleanup button and search bar")
-- Up from the grid's top row puts the cursor on the strip the app draws above it (ctl.zone "bar"): every bag slot that holds a bag,
-- then Clean Up, then Search; left / right walk it, down goes back to the grid where the cursor left it. A acts:
--   a bag  = open / close it in the room (ctl.shut: a CLOSED bag is left out of the grid and of the D-pad's walk);
--   Clean Up = the game's own sort (C_Container.SortBags, inside the press; refused in a fight, as the game refuses it);
--   Search = a request (ctl.act, keyed by sid + seq like Use) the app answers by putting the search box in focus with its keyboard.
-- B / X / Y act as on the grid (B leaves the room). THE SAME FROM EVERY ROUTE: a finger on a bag slot toggles the SAME ctl.shut (bmvq 27,
-- then the bag's column), so the room shows the same bags however they were closed. Session state only (a /reload opens them all).
function BAR.isShut(bag) return ctl.shut[bag] == true end
function BAR.shutList()
    local out = {}
    for bag in pairs(ctl.shut) do out[#out + 1] = bag end
    table.sort(out)
    return out
end
function BAR.open()
    local out = {}
    for _, e in ipairs(layout()) do if not ctl.shut[e.bag] then out[#out + 1] = e end end
    return out
end
-- the strip's items, left to right as the app draws them
function BAR.items()
    local out = {}
    for _, e in ipairs(layout()) do out[#out + 1] = { k = "bag", id = e.bag } end
    out[#out + 1] = { k = "sort" }
    if DE.known() then out[#out + 1] = { k = "de" } end            -- bags-de1: the Disenchant grid's icon (the user's pick 5a)
    out[#out + 1] = { k = "search" }
    return out
end
function BAR.enter(col)
    local n = #BAR.items()
    ctl.zone = "bar"
    ctl.barAt = math.max(1, math.min(n, math.floor((col or 0) * n / COLS) + 1))
end
-- down: back onto the grid, where the cursor left it (or the first open bag when that one was closed meanwhile)
function BAR.leave()
    local L = BAR.open()
    if not L[1] then return false end
    ctl.zone = nil
    for _, e in ipairs(L) do if e.bag == ctl.bag then return true end end
    ctl.bag, ctl.slot = L[1].bag, 1
    return true
end
function BAR.toggle(bag)
    if ctl.shut[bag] then ctl.shut[bag] = nil; return "bag-opened" end
    ctl.shut[bag] = true
    return "bag-closed"
end
function BAR.act(it, voice)
    if not it then return end
    if it.k == "bag" then
        report(BAR.toggle(it.id))
    elseif it.k == "sort" then
        if InCombatLockdown() then report("sort-combat"); fightRefused(); return end
        local ok = CALL(function() return C_Container.SortBags() end)
        if not ok then ok = CALL(function() return SortBags() end) end
        WC.emit({ type = "cap", bagsSort = { ok = ok and true or false } })
        report(ok and "sorted" or "sort-refused")
    elseif it.k == "de" then
        DE.fromStrip()                                              -- bags-de1: the Disenchant grid (no game action)
    elseif it.k == "search" then
        -- THE COMPANION'S OWN KEYBOARD types (the user, 2026-10-09: "Yes, controller keyboard": Chat's, drawn by the app, fed by the app's
        -- own reader): Bags stands still meanwhile (mode "search": every press is the keyboard's, none acts here), and Start is held
        -- from the game (the keyboard's Search key; the game's Start would open its menu). Over when the app says so (bmvq 27 + col 17),
        -- the room is left, or after BAR.SEARCH_TTL
        ctl.mode = "search"; ctl.searchAt = GetTime()
        local cap = capture()
        if cap and type(cap.extra) == "function" and (tonumber(cap.version) or 1) >= 4 then pcall(cap.extra, "bags", { "PADFORWARD" }) end
        -- bags-voice1: X asks for the app's voice page on the same box (the app keeps its keyboard when voice is off)
        ctl.act = { k = voice and "voice" or "search", seq = ctl.seq + 1 }
        report(voice and "voice" or "search")
    end
end
BAR.SEARCH_TTL = 300
BAR.SEARCH_DONE_COL = 17         -- after the "shut" prefix (bmvq 27): not a bag id, so it can say "the keyboard closed"
-- the keyboard closed (the app's tap, or the TTL): Bags' presses again, the cursor still on Search
function BAR.searchDone(why)
    if ctl.mode ~= "search" then return false end
    local cap = capture()
    if cap and type(cap.extra) == "function" and (tonumber(cap.version) or 1) >= 4 then pcall(cap.extra, "bags", {}) end
    ctl.mode = "grid"; ctl.searchAt = nil
    report(why or "search-done")
    return true
end
-- a press while the cursor is on the strip; false = not the strip's (B / X / Y / up: as on the grid)
function BAR.press(key, A)
    local items = BAR.items()
    ctl.barAt = math.max(1, math.min(#items, ctl.barAt or 1))
    if key == "PADDLEFT" then ctl.barAt = math.max(1, ctl.barAt - 1); report(nil)
    elseif key == "PADDRIGHT" then ctl.barAt = math.min(#items, ctl.barAt + 1); report(nil)
    elseif key == "PADDDOWN" then if BAR.leave() then report(nil) end
    elseif key == "PADDUP" then
    elseif A then BAR.act(items[ctl.barAt])
    elseif key == "PAD3" and items[ctl.barAt] and items[ctl.barAt].k == "search" then BAR.act(items[ctl.barAt], true)    -- bags-voice1
    elseif key == "PAD4" then BAR.openMenu(items[ctl.barAt])
    else return false end
    return true
end

-- ---------------------------------------------------------------- A WORN BAG'S OWN "MORE" (the user after the strip test, 2026-10-09: "tooltips on the
-- actual bags needs a more button to be able to move/unequip bags"; the game's own bag-slot menu has Unequip Bag): Y on a worn bag of the
-- strip (not the backpack) = mode "barmenu", Move to Another Slot / Unequip, the D-pad up / down, A chooses, B back to the strip.
--   Move    = the bag in the hand and the bag bar's chooser (EQ, eq.move): A on another bag slot = PickupBagFromSlot + PutItemInBag, the
--             game's own swap (each bag keeps what is inside it).
--   Unequip = the bag into a free slot of another bag that takes anything (PickupBagFromSlot + that slot's pick-up). The game takes off
--             only an EMPTY bag, so a bag with items says so first ("not-empty").
-- Out of combat only; engine calls, no window opened, nothing of the player's setup written.
BAR.MENU = { "move", "unequip" }
function BAR.bagMenuUsable(t)
    local ok, why = {}, {}
    local fight = InCombatLockdown()
    local others = 0
    if t ~= REAGENT_BAG then for _, s2 in ipairs(EQ.TARGETS) do if s2 ~= t then others = others + 1 end end end
    ok[1] = (not fight) and others > 0
    why[1] = (fight and "combat") or (others == 0 and "no-slot") or nil
    local n = SN(function() return C_Container.GetContainerNumSlots(t) end) or 0
    local okF, free = CALL(function() return C_Container.GetContainerNumFreeSlots(t) end)
    local items = n - ((okF and type(free) == "number") and free or n)
    local room = EQ.outSlot(t)
    ok[2] = (not fight) and items == 0 and room ~= nil
    why[2] = (fight and "combat") or (items > 0 and "not-empty") or (room == nil and "no-room") or nil
    return ok, why
end
function BAR.openMenu(it)
    if not (it and it.k == "bag" and it.id ~= 0) then return end
    ctl.mode = "barmenu"; ctl.barBag = it.id; ctl.barMenu = 1
    report(nil)
end
function BAR.menuPress(key, A, B)
    if key == "PADDUP" or key == "PADDDOWN" then ctl.barMenu = (ctl.barMenu == 1) and 2 or 1; report(nil)
    elseif B then ctl.mode = "grid"; report(nil)                    -- back to the strip, on the same bag
    elseif A then BAR.menuChoose(ctl.barMenu) end
end
function BAR.menuChoose(i)
    local t = ctl.barBag
    local ok, why = BAR.bagMenuUsable(t)
    if not ok[i] then
        if why[i] == "combat" then fightRefused() end
        report((i == 1 and "move-" or "unequip-") .. (why[i] or "refused"))
        return
    end
    if i == 1 then EQ.beginMove(t) else BAR.unequip(t) end
end
function BAR.unequip(t)
    local inv = SN(function() return C_Container.ContainerIDToInventoryID(t) end)
    local ob, os = EQ.outSlot(t)
    ctl.mode = "grid"; ctl.zone = "bar"
    if not (inv and ob) then report("unequip-no-room"); return end
    if cursorBusy() then report("cursor"); return end
    CALL(function() return PickupBagFromSlot(inv) end)
    pickupBag(ob, os)
    ctl.eqPutAt = GetTime()                                         -- the game's bag window churns after a bag change (EQ.CHURN)
    local done = settle()
    if done then ctl.shut[t] = nil end
    report(done and "unequipped" or "refused")
end
-- what the bag menu shows: its bag, the row, and which rows can be used now (and why not)
function BAR.menuDoc()
    if ctl.mode ~= "barmenu" then return nil end
    local ok, why = BAR.bagMenuUsable(ctl.barBag)
    return { bag = ctl.barBag, at = ctl.barMenu, ok = { ok[1] == true, ok[2] == true }, why = { why[1] or false, why[2] or false } }
end

-- ---------------------------------------------------------------- EQUIP A BAG (the user, 2026-10-06: "On the bag bar")
-- The user: "I am not able to equip bags, we need to design a way to equip a bag and select the bag that its replacing";
-- their pick (EQUIP_A_bagbar): X on a bag puts it IN THE HAND and the cursor goes up to the BAG BAR, onto an empty slot if
-- there is one, else the smallest bag it can replace; the D-pad walks the bag slots, A = Equip Here, B = Cancel.
-- WHY NOT THE OLD X: that was the game's right-click, which fills only a FREE bag slot (all four full = nothing at all).
-- THE GAME'S OWN WAY (its source, blizzard_mainmenubarbagbuttons; Forever's GamepadBagBar in the combined bags does just
-- this): the bag on the cursor, then PutItemInBag(the slot's inventory id). Engine calls only: no window opened or closed.
-- THE GAME'S RULES: an empty worn bag swaps freely; one with items swaps only for a bag big enough to hold them (the game
-- moves them over, and the old bag lands where the new one was); the new bag cannot come from INSIDE the bag it replaces
-- (Bags first moves it to a free slot of another bag, then equips it once the game shows it there: it empties a slot only
-- on its next update); a bind-on-equip bag asks "bind it to you?" (the game's own dialog: A / B are passed to the game
-- while it asks, syncPass); in a fight the game changes no equipment but weapons, so Equip is refused there.
-- SPECIAL BAGS (the user after b84: "factor in special bags like quiver or profession bags"): a profession bag (herbs,
-- enchanting, mining, soul shards, ...) or a quiver / ammo pouch takes only its own kind of item. So a bag with items is
-- replaced by a special bag only when EVERY item in it fits (the game's GetItemFamily: an item fits when the bag's family
-- is 0 or they share a bit), else "wrong-type". The reagent bag only goes in the reagent slot, and only it goes there.
-- Quivers are a hunter's (the class-exclusive ruling): the same family rule covers them; the game has the last word.
-- THE WINDOW CHURN (b84, measured 21:53:27): a bag swap makes the game hide its bag window and show it again; that hide
-- used to hand the controller back for ~0.3 s ("taken" again after, the router's padmap in between). A hide within
-- EQ.CHURN seconds of a put is not a leave now.
-- ctl.eq = the bag in the hand {bag, slot, id, slots = the bag slots it may go in, size}; ctl.eqAt = the target's index;
-- ctl.eqTarget = the bag slot it is going into; modes: "equip" (choosing), "equipping" (the game working), "bindask".
EQ.TARGETS = { 1, 2, 3, 4 }
EQ.sizes = {}
EQ.CHURN = 2.0

-- bitwise and (the game has bit.band; the plain loop is for anything without it)
function EQ.band(a, b)
    if type(bit) == "table" and type(bit.band) == "function" then return bit.band(a, b) end
    local r, p = 0, 1
    while a > 0 and b > 0 do
        if a % 2 == 1 and b % 2 == 1 then r = r + p end
        a, b, p = math.floor(a / 2), math.floor(b / 2), p * 2
    end
    return r
end

-- the game's item family: for a bag, the kind of item it takes (0 = anything); for an item, the bags it may go in
function EQ.fam(id)
    local ok, f = CALL(function()
        if C_Item and C_Item.GetItemFamily then return C_Item.GetItemFamily(id) end
        return GetItemFamily(id)
    end)
    return (ok and type(f) == "number") and f or nil
end

-- the game's own word for a bag's kind ("Bag", "Herb Bag", "Soul Bag", "Quiver", "Ammo Pouch"): its item subtype
function EQ.kind(id)
    local ok, _, _, sub = CALL(function() return C_Item.GetItemInfoInstant(id) end)
    return (ok and type(sub) == "string" and sub ~= "") and sub or nil
end

-- does everything in bag [t] fit a bag of family [fam]?
function EQ.allFit(t, fam)
    local n = SN(function() return C_Container.GetContainerNumSlots(t) end) or 0
    for s = 1, n do
        local id = itemAt(t, s)
        if id and EQ.band(EQ.fam(id) or 0, fam) == 0 then return false end
    end
    return true
end

-- "10 Slot Bag": a bag's size from the game's own tooltip line (CONTAINER_SLOTS = "%d Slot %s")
function EQ.slotsIn(text)
    if type(text) ~= "string" then return nil end
    local f = S(function() return CONTAINER_SLOTS end)
    local head, mid
    if type(f) == "string" then head, mid = f:match("^(.-)%%d(.-)%%s") end
    if not head then head, mid = "", " Slot " end
    if text:sub(1, #head) ~= head then return nil end
    local digits = text:match("^(%d+)", #head + 1)
    if not digits or text:sub(#head + #digits + 1, #head + #digits + #mid) ~= mid then return nil end
    return tonumber(digits)
end

function EQ.size(bag, slot, id)
    if EQ.sizes[id] then return EQ.sizes[id] end
    local ok, data = CALL(function() return C_TooltipInfo.GetBagItem(bag, slot) end)
    local lines = ok and type(data) == "table" and data.lines
    if type(lines) ~= "table" then return nil end
    for i = 1, #lines do
        local ln = lines[i]
        local n = type(ln) == "table" and EQ.slotsIn(S(function() return ln.leftText end)) or nil
        if n then EQ.sizes[id] = n; return n end
    end
    return nil
end

-- a bag (or quiver) item: the bag slots it can go in (a reagent bag only in the reagent slot); nil for anything else
function EQ.slotsFor(id)
    local ok, _, _, _, loc, _, cls, sub = CALL(function() return C_Item.GetItemInfoInstant(id) end)
    if not ok or (loc ~= "INVTYPE_BAG" and loc ~= "INVTYPE_QUIVER") then return nil end
    if REAGENT_BAG and cls == 1 and sub == 11 then return { REAGENT_BAG } end       -- Container / Reagent Bag
    return EQ.TARGETS
end

-- a free slot in a bag other than [t] that takes anything: where a new bag inside [t] goes first
function EQ.outSlot(t)
    for _, e in ipairs(layout()) do
        if e.bag ~= t and e.bag ~= REAGENT_BAG and (e.fam or 0) == 0 then
            for s = 1, e.slots do if not itemAt(e.bag, s) then return e.bag, s end end
        end
    end
end

-- one bag slot as the target of the bag in the hand: what is in it, and why the game would refuse it (nil = it takes it)
function EQ.target(t, eq)
    local inv = SN(function() return C_Container.ContainerIDToInventoryID(t) end)
    local cur = inv and invItemID(inv) or nil
    local r = { bag = t }
    if cur then
        local slots = SN(function() return C_Container.GetContainerNumSlots(t) end) or 0
        local okF, free = CALL(function() return C_Container.GetContainerNumFreeSlots(t) end)
        r.id = cur; r.slots = slots
        r.items = math.max(0, slots - ((okF and type(free) == "number") and free or slots))
        local okN, nm = CALL(function() return C_Item.GetItemNameByID(cur) end)
        local n = okN and S(function() return nm end)
        if type(n) == "string" and n ~= "" and n:byte(1) > 3 then r.name = n end
        local ic = SN(function() return GetInventoryItemTexture("player", inv) end)
        if ic then r.icon = ic end
        r.kind = EQ.kind(cur); r.fam = EQ.fam(cur)
    end
    if not inv then r.why = "no-slot"
    elseif InCombatLockdown() then r.why = "combat"
    elseif eq.move then                                  -- a worn bag to another slot: the two only swap places
    elseif cur and r.items > 0 and eq.size and eq.size < r.items then r.why = "too-small"
    elseif cur and r.items > 0 and (eq.fam or 0) ~= 0 and not EQ.allFit(t, eq.fam) then r.why = "wrong-type"
    elseif eq.bag == t then
        if EQ.outSlot(t) then r.out = true else r.why = "no-room" end
    end
    r.ok = r.why == nil
    return r
end

-- bags-gear1: one worn slot as the target of a piece of gear (the slide-out): what is worn there, and why not now (nil = it can go)
function EQ.gearTarget(inv)
    local cur = invItemID(inv)
    local r = { inv = inv }
    if cur then
        r.id = cur
        r.icon = SN(function() return GetInventoryItemTexture("player", inv) end)
        r.quality = SN(function() return GetInventoryItemQuality("player", inv) end)
        r.ilvl = ilvlOf(S(function() return GetInventoryItemLink("player", inv) end))
        local okN, nm = CALL(function() return C_Item.GetItemNameByID(cur) end)
        local n = okN and S(function() return nm end)
        if type(n) == "string" and n ~= "" and n:byte(1) > 3 then r.name = n end
    end
    if InCombatLockdown() then r.why = "combat" end
    r.ok = r.why == nil
    return r
end

-- bags-gear1: the worn slots a piece of gear may go in (Bags' ACT.SLOTS; a one-hander's off hand only for a class that dual wields, a
-- two-hander the main hand only), nil when it is not gear (a bag, ammo, food ...)
function EQ.gearSlots(id)
    local loc = ACT.loc(id)
    local s = loc and ACT.SLOTS[loc]
    if not s then return nil end
    if loc == "INVTYPE_WEAPON" and not ACT.dualWield() then return { 16 } end
    return s
end

-- bags-swap1 (Character c20; the user, 2026-11-16: "on weapons that are both 1 hand or can actually be swapped to the offhand or vice
-- versa then that option should be listed in the pop out options."): worn [from]'s piece into worn [to], the paper doll's own drag
-- (PickupInventoryItem, EquipCursorItem); what was in [to] goes into [from] when it may go there, else first into the first free plain
-- bag slot. Out of a fight only (bags-swap2: the two hands in a fight too, watched). Returns true, or false + why (bad / combat / cursor / settle / empty / full / refused); the answer goes
-- to the Character room (UQ.swapReply), confirmed 0.6 s later off the slot itself.
local function fitsIn(id, inv)
    local sl = id and EQ.gearSlots(id)
    if not sl then return false end
    for _, v in ipairs(sl) do if v == inv then return true end end
    return false
end
function WC.bagsSwapWorn(from, to)
    local at = { inv = to }
    local fight = false
    local function no(why)
        if fight and why == "combat" then fightRefused() end
        UQ.swapReply({ ok = false, why = why, from = { inv = from }, to = at, fight = fight or nil }); return false, why
    end
    if type(from) ~= "number" or type(to) ~= "number" or from < 1 or from > 19 or to < 1 or to > 19 or from == to then return no("bad") end
    fight = InCombatLockdown() and true or false
    -- bags-swap2: in a fight only the two hands (the weapon swap, uneq1's watched route); rings / trinkets / the rest out of one only
    if fight and not (UQ.WEAPON[from] and UQ.WEAPON[to] and from ~= 18 and to ~= 18) then return no("combat") end
    if cursorBusy() then return no("cursor") end
    if (GetTime() - movedAt) < SETTLE then return no("settle") end
    local idA = invItemID(from)
    if not idA then return no("empty") end
    if not fitsIn(idA, to) then return no("bad") end
    local idB = invItemID(to)
    local back = idB and fitsIn(idB, from)
    picked = nil                                   -- a half-done drag of the Bags room is void now
    UQ.blocked, UQ.done = nil, nil
    UQ.watchAt = GetTime()                         -- bags-swap2: the game's block of any call below is ours (the watcher)
    -- bags-swap2: a refused step: put back what the cursor holds; a block in a fight = combat, else the game refused it
    local function failed() settle(); movedAt = GetTime(); return no(UQ.blocked and "combat" or "refused") end
    if idB and not back then
        -- what is in TO can't go into FROM (a shield, a main-hand-only weapon): into the bags first, as Unequip does
        local bag, s = WC.bagsFirstEmpty("bags", idB)
        if not bag then return no("full") end
        CALL(function() return PickupInventoryItem(to) end)
        if UQ.blocked or not cursorBusy() then return failed() end
        pickupBag(bag, s)
        if UQ.blocked or not settle() or invItemID(to) then return failed() end
    end
    CALL(function() return PickupInventoryItem(from) end)
    if UQ.blocked or not cursorBusy() then return failed() end
    CALL(function() return EquipCursorItem(to) end)
    if UQ.blocked then return failed() end
    -- the game swaps two worn pieces itself; a piece it left on the cursor (TO's) goes into FROM, now empty
    if back and cursorBusy() then CALL(function() return EquipCursorItem(from) end) end
    if UQ.blocked then return failed() end
    local clean = settle()
    movedAt = GetTime()
    if not clean or invItemID(to) ~= idA then return no("refused") end
    UQ.done, UQ.done_at, UQ.doneReply = true, at, UQ.swapReply    -- a blocked event still to come is answered by the watcher
    UQ.swapReply({ ok = true, from = { inv = from }, to = at, id = idA, fight = fight or nil })
    C_Timer.After(UQ.WATCH, function() UQ.done = nil end)
    C_Timer.After(0.6, function()
        UQ.swapReply({ confirm = (invItemID(to) == idA), from = { inv = from }, to = at, id = idA })
    end)
    return true
end

function EQ.list(eq)
    local out = {}
    if eq.gear then
        for _, inv in ipairs(eq.slots) do out[#out + 1] = EQ.gearTarget(inv) end
        return out
    end
    for _, t in ipairs(eq.slots or EQ.TARGETS) do out[#out + 1] = EQ.target(t, eq) end
    return out
end

-- where the cursor starts: an empty slot it can go in, else the smallest bag it can replace, else the first
-- bags-gear1 (gear): an empty worn slot, else the piece of the lower item level
function EQ.start(list)
    local best, bestN
    for i, r in ipairs(list) do
        if r.ok and not r.id then return i end
        local n = r.inv and (r.ilvl or 0) or (r.slots or 0)
        if r.ok and (not bestN or n < bestN) then best, bestN = i, n end
    end
    return best or 1
end

function EQ.doc()
    local eq = ctl.eq
    if not eq or (ctl.mode ~= "equip" and ctl.mode ~= "equipping" and ctl.mode ~= "bindask") then return nil end
    return { bag = eq.bag, slot = eq.slot, id = eq.id, size = eq.size, at = ctl.eqAt, to = ctl.eqTarget,
             kind = eq.kind, fam = eq.fam, move = eq.move, from = eq.fromBag, icon = eq.icon, gear = eq.gear,
             targets = (ctl.mode == "equip") and EQ.list(eq) or nil }
end

-- bags-gear1: X on a piece of gear with two places to go: the slide-out of its worn slots (out of a fight, no game window)
function EQ.beginGear(id, slots)
    if InCombatLockdown() then report("equip-combat"); fightRefused(); return end
    ctl.eq = { gear = true, bag = ctl.bag, slot = ctl.slot, id = id, slots = slots }
    ctl.eqAt = EQ.start(EQ.list(ctl.eq)); ctl.eqTarget = nil
    ctl.mode = "equip"; report(nil)
end

-- bags-gear1: A on the slide-out: the piece into the chosen worn slot (equipTo, the drag's own way); the game's bind question waits
function EQ.goGear(eq, tg)
    if InCombatLockdown() then report("equip-combat"); fightRefused(); return end
    if itemAt(eq.bag, eq.slot) ~= eq.id then EQ.done("moved"); return end
    if not tg.ok then report("equip-" .. tostring(tg.why)); return end
    if cursorBusy() then EQ.done("cursor"); return end
    ctl.eqTarget = tg.inv
    picked = nil                                         -- a half-done drag of the room is void now
    local r = WC._bagsEquipTo(eq.bag, eq.slot, tg.inv, eq.id)
    if r == "ask" then
        ctl.mode = "bindask"; report("equip-bind")       -- syncPass hands A / B to the game's question
        C_Timer.After(0.25, EQ.waitGear)
        return
    end
    EQ.done(r == "ok" and "equipped" or ("equip-" .. tostring(r)))
end

-- bags-gear1: the game's bind question answered (or settleAfterBind gave up): the slot says whether the piece went on
function EQ.waitGear()
    if not (ctl.on and ctl.mode == "bindask" and ctl.eq and ctl.eq.gear) then return end
    if bindAsking() then C_Timer.After(0.25, EQ.waitGear); return end
    ctl.mode = "equipping"; report(nil)
    C_Timer.After(0.5, function()                       -- the game's answer reaches the slot on its next update
        local eq, inv = ctl.eq, ctl.eqTarget
        if not (ctl.on and ctl.mode == "equipping" and eq and eq.gear) then return end
        EQ.done((inv and invItemID(inv) == eq.id) and "equipped" or "equip-cancelled")
    end)
end

function EQ.begin(id)
    if InCombatLockdown() then report("equip-combat"); fightRefused(); return end
    ctl.eq = { bag = ctl.bag, slot = ctl.slot, id = id, slots = EQ.slotsFor(id), size = EQ.size(ctl.bag, ctl.slot, id),
               fam = EQ.fam(id), kind = EQ.kind(id) }
    ctl.eqAt = EQ.start(EQ.list(ctl.eq)); ctl.eqTarget = nil
    ctl.mode = "equip"; report(nil)
end

-- a worn bag to another bag slot (the strip's More, Move to Another Slot): the bag bar's chooser with that bag in the hand
function EQ.beginMove(t)
    local inv = SN(function() return C_Container.ContainerIDToInventoryID(t) end)
    local id = inv and invItemID(inv)
    if not id then ctl.mode = "grid"; report("move-empty"); return end
    local slots = {}
    for _, s2 in ipairs(EQ.TARGETS) do if s2 ~= t then slots[#slots + 1] = s2 end end
    ctl.eq = { move = true, fromBag = t, inv = inv, id = id, slots = slots, kind = EQ.kind(id), fam = EQ.fam(id),
               icon = SN(function() return GetInventoryItemTexture("player", inv) end) }
    ctl.eqAt = 1; ctl.eqTarget = nil
    ctl.mode = "equip"; report(nil)
end

-- back to the grid, the cursor on the slot the new bag came from (after a swap the old bag lies there)
function EQ.done(note)
    local eq = ctl.eq
    if eq and eq.bag then ctl.bag, ctl.slot = eq.bag, eq.slot end
    if eq and eq.move then ctl.zone = "bar" end            -- a worn bag's move ends back on the strip
    ctl.eq = nil; ctl.eqAt = nil; ctl.eqTarget = nil
    toGrid(note)
end

-- A on the bag bar
function EQ.go()
    local eq = ctl.eq
    local tg = eq and EQ.list(eq)[ctl.eqAt or 1]
    if not tg then EQ.done(nil); return end
    if eq.gear then EQ.goGear(eq, tg); return end        -- bags-gear1: a piece of gear into a worn slot
    if InCombatLockdown() then report("equip-combat"); fightRefused(); return end
    if EQ.heldId(eq) ~= eq.id then EQ.done("moved"); return end
    if not tg.ok then report("equip-" .. tg.why); return end          -- the tooltip says why; the bag stays in the hand
    if cursorBusy() then EQ.done("cursor"); return end
    ctl.eqTarget = tg.bag
    if tg.out then
        local ob, os = EQ.outSlot(tg.bag)
        pickupBag(eq.bag, eq.slot); pickupBag(ob, os)
        if not settle() then EQ.done("refused"); return end
        eq.bag, eq.slot, eq.tries = ob, os, 0
        ctl.mode = "equipping"; report("equip-out")
        C_Timer.After(0.6, EQ.put)
        return
    end
    EQ.put()
end

-- what is where the bag in the hand comes from: its bag slot (a move) or its bag slot in a bag (an equip)
function EQ.heldId(eq)
    if eq.move then return eq.inv and invItemID(eq.inv) or nil end
    return itemAt(eq.bag, eq.slot)
end

-- the bag on the cursor, into the slot: the game's own PutItemInBag (as its bag bar does)
function EQ.put()
    local eq, t = ctl.eq, ctl.eqTarget
    if not (ctl.on and eq and t) then return end
    if EQ.heldId(eq) ~= eq.id then
        eq.tries = (eq.tries or 0) + 1                      -- taken out first: the game shows it there on its next update
        if eq.tries <= 3 then C_Timer.After(0.4, EQ.put); return end
        EQ.done("moved"); return
    end
    if InCombatLockdown() then EQ.done("equip-combat"); return end
    if cursorBusy() then EQ.done("cursor"); return end
    local inv = SN(function() return C_Container.ContainerIDToInventoryID(t) end)
    if eq.move then CALL(function() return PickupBagFromSlot(eq.inv) end) else pickupBag(eq.bag, eq.slot) end
    CALL(function() return PutItemInBag(inv) end)
    ctl.eqPutAt = GetTime()                                    -- the game's window churn follows (see EQ.CHURN)
    ctl.mode = "equipping"; report(nil)
    C_Timer.After(0.3, EQ.check)
end

-- the game's bind question for a bag: StaticPopup "EQUIP_BIND" / "_TRADEABLE" / "_REFUNDABLE" (read only)
function EQ.asking()
    for i = 1, 4 do
        local f = _G["StaticPopup" .. i]
        if type(f) == "table" and S(function() return f:IsShown() end) == true then
            local which = S(function() return f.which end)
            if type(which) == "string" and which:find("^EQUIP_BIND") then return true end
        end
    end
    return false
end

function EQ.check()
    if not (ctl.on and ctl.mode == "equipping") then return end
    if ctl.eq and ctl.eq.gear then return end                  -- bags-gear1: the gear's own wait (EQ.waitGear) reads its slot
    if EQ.asking() then
        ctl.mode = "bindask"; report("equip-bind")             -- syncPass hands A / B to the game's question
        C_Timer.After(0.25, EQ.waitBind)
        return
    end
    EQ.done(settle() and "equipped" or "equip-refused")       -- still on the cursor = refused: it goes back
end

function EQ.waitBind()
    if not (ctl.on and ctl.mode == "bindask") then return end
    if ctl.eq and ctl.eq.gear then return end                  -- bags-gear1: EQ.waitGear's
    if EQ.asking() then C_Timer.After(0.25, EQ.waitBind); return end
    ctl.mode = "equipping"; ctl.eqPutAt = GetTime()           -- answered: A / B are Bags' again; the churn may follow
    C_Timer.After(0.5, function()                              -- the game's answer reaches the slot on its next update
        if not (ctl.on and ctl.mode == "equipping") then return end
        local t, eq = ctl.eqTarget, ctl.eq
        local inv = t and SN(function() return C_Container.ContainerIDToInventoryID(t) end)
        local now = inv and invItemID(inv)
        settle()
        EQ.done((eq and now == eq.id) and "equipped" or "equip-cancelled")
    end)
end

do
    local ef = CreateFrame("Frame")
    for _, e in ipairs({ "EQUIP_BIND_CONFIRM", "EQUIP_BIND_TRADEABLE_CONFIRM", "EQUIP_BIND_REFUNDABLE_CONFIRM" }) do
        pcall(ef.RegisterEvent, ef, e)
    end
    ef:SetScript("OnEvent", function() if ctl.mode == "equipping" then C_Timer.After(0, EQ.check) end end)
end

-- which Y-menu rows can be used on the cursor's item (UI Art's SPEC 2026-09-28: a row that cannot be
-- used is drawn disabled and the focus skips it). Destroy can out of a fight: for rare and better it opens the
-- dialog that says why not.
-- bags-act1: Compare only on gear (its equip location, not its tooltip: that may still be loading); Destroy never in a fight.
menuUsable = function()
    local info = infoAt(ctl.bag, ctl.slot)
    local n = info and SN(function() return info.stackCount end) or 1
    local cap = capture()
    return { n > 1, cap ~= nil and type(WC.spellsBindItem) == "function", ACT.isGear(itemAt(ctl.bag, ctl.slot)), not InCombatLockdown() }
end

-- ---------------------------------------------------------------- bags-act1: COMPARE + THE DESTROY PICKER (see the header)
-- the item's equip location when it is worn gear (not a bag, a quiver, food ...), else nil
function ACT.loc(id)
    if not id then return nil end
    local ok, _, _, _, loc = CALL(function() return C_Item.GetItemInfoInstant(id) end)
    loc = ok and S(function() return loc end) or nil
    if type(loc) ~= "string" or loc == "" or loc:byte(1) <= 3 or NOT_WORN[loc] then return nil end
    return loc
end
function ACT.isGear(id) return ACT.loc(id) ~= nil end
-- the worn slots an equip location goes in (Interact's EQUIP_SLOTS); a one-hander's off hand only for a class that dual wields
ACT.SLOTS = {
    INVTYPE_HEAD = { 1 }, INVTYPE_NECK = { 2 }, INVTYPE_SHOULDER = { 3 }, INVTYPE_BODY = { 4 },
    INVTYPE_CHEST = { 5 }, INVTYPE_ROBE = { 5 }, INVTYPE_WAIST = { 6 }, INVTYPE_LEGS = { 7 },
    INVTYPE_FEET = { 8 }, INVTYPE_WRIST = { 9 }, INVTYPE_HAND = { 10 }, INVTYPE_FINGER = { 11, 12 },
    INVTYPE_TRINKET = { 13, 14 }, INVTYPE_CLOAK = { 15 }, INVTYPE_WEAPON = { 16, 17 },
    INVTYPE_2HWEAPON = { 16 }, INVTYPE_WEAPONMAINHAND = { 16 }, INVTYPE_WEAPONOFFHAND = { 17 },
    INVTYPE_SHIELD = { 17 }, INVTYPE_HOLDABLE = { 17 }, INVTYPE_RANGED = { 18 }, INVTYPE_RANGEDRIGHT = { 18 },
    INVTYPE_THROWN = { 18 }, INVTYPE_RELIC = { 18 }, INVTYPE_TABARD = { 19 },
}
function ACT.dualWield()
    local ok, dw = CALL(function() return CanDualWield() end)
    dw = ok and S(function() return dw end)
    return dw == true or dw == 1
end
-- never suggested: the hearthstone, profession tools (picks, knives, hammers, rods, spanners, fishing poles ...)
ACT.KEEP = { [6948] = true, [2901] = true, [7005] = true, [5956] = true, [6219] = true, [6218] = true, [6339] = true, [11130] = true,
             [11145] = true, [16207] = true, [22461] = true, [22462] = true, [22463] = true, [10498] = true, [9149] = true,
             [20815] = true, [4471] = true, [5060] = true, [6256] = true, [6365] = true, [6366] = true, [6367] = true, [12225] = true,
             [19022] = true, [19970] = true, [25978] = true, [756] = true, [778] = true, [1959] = true,
             -- bags-act2: a shaman's Earth / Fire / Water / Air totems and soul shards (no sell value: they ranked as junk)
             [5175] = true, [5176] = true, [5177] = true, [5178] = true, [6265] = true }
-- the game's item classes never suggested: reagents (5: a shaman's Ankh), projectiles (6), quest (12), keys (13), profession (19)
ACT.KEEP_CLASS = { [5] = true, [6] = true, [12] = true, [13] = true, [19] = true }
ACT.RED = "ff2020"            -- RED_FONT_COLOR: a tooltip line the player does not meet (a level, a skill, an armour type)
ACT.GAIN = "00ff00"           -- the game's stat-change gain colour (Interact's delta lines)
-- the stack's sell value in copper (C_Item.GetItemInfo's 11th return); nil while the game is loading it (asked for, re-built then)
function ACT.value(id, count, noValue)
    if noValue then return 0 end
    local ok, v = CALL(function() return select(11, C_Item.GetItemInfo(id)) end)
    v = ok and SN(function() return v end) or nil
    if v == nil then
        ACT.wait = ACT.wait or {}
        ACT.wait[id] = true
        CALL(function() return C_Item.RequestLoadItemDataByID(id) end)
        return nil
    end
    return v * (count or 1)
end
-- the item's tooltip (kept, or read now: out of a fight only, the picker is never opened in one)
function ACT.tip(bag, slot, id)
    local key = "bag " .. bag .. " " .. slot .. " " .. id
    local e = TIP.get(key, bag, slot)
    if e then return e.val end
    -- bags-act2: a re-rank in a fight (a value loaded, B from the confirm) reads no tooltips: the kept ones only
    if type(WC.tipOf) ~= "function" or InCombatLockdown() then return nil end
    local t = TIP.read(key, bag, slot)
    return t
end
-- a tooltip line in the game's red (not the name line)
function ACT.unusable(t)
    if type(t) ~= "table" or type(t.lines) ~= "table" then return false end
    for i = 2, #t.lines do
        local l = t.lines[i]
        if type(l) == "table" and (l.c == ACT.RED or l.rc == ACT.RED) then return true end
    end
    return false
end
-- char-more1 (the user, 2026-11-13: Replace lists the bag pieces that can go in the slot, "hide items the player can't equip (red/unusable
-- line)"): true when the game won't let this character wear the piece in bag / slot (item id): its required level is above the player's,
-- the game's own C_PlayerInfo.CanUseItem says no (class / proficiency), or its KEPT tooltip has a red line. Read-only, no tooltip read here
-- (the collect must stay cheap: an item not read yet is offered, the game refuses it itself)
function WC._bagsNoUse(bag, slot, id)
    if type(id) ~= "number" then return false end
    local okI, _, _, _, _, minL = CALL(function() return GetItemInfo(id) end)
    minL = okI and SN(function() return minL end) or nil
    local lvl = SN(function() return UnitLevel("player") end)
    if minL and lvl and minL > lvl then return true end
    local can = S(function() return C_PlayerInfo.CanUseItem(id) end)
    if can == false then return true end
    local e = TIP.get("bag " .. bag .. " " .. slot .. " " .. id, bag, slot)
    return e ~= nil and ACT.unusable(e.val)
end

-- the game's stat changes against one worn slot: true = all losses, false = a gain, nil = none given
function ACT.allLoss(t, slot)
    if type(t) ~= "table" or type(t.cmp) ~= "table" then return nil end
    for _, c in ipairs(t.cmp) do
        if type(c) == "table" and c.slot == slot and type(c.lines) == "table" and type(c.dn) == "number" and c.dn > 0 then
            local n = #c.lines
            for i = n - c.dn + 1, n do
                local l = c.lines[i]
                -- bags-act2: every line must read as a loss ("-3 Stamina", not the gain colour); anything else = not all losses
                if type(l) ~= "table" or l.c == ACT.GAIN or type(l.t) ~= "string" or not l.t:match("^%s*%-") then return false end
            end
            return true
        end
    end
    return nil
end
-- bags-de1 (the user's pick 6a: "a small green arrow on tiles whose stat changes are all gains"): true when the game's stat changes against
-- some worn piece are ALL gains (the gain colour, or a "+" line not in red); a "-" line, or none given, = not all gains
function ACT.allGain(t)
    if type(t) ~= "table" or type(t.cmp) ~= "table" then return false end
    for _, c in ipairs(t.cmp) do
        if type(c) == "table" and type(c.lines) == "table" and type(c.dn) == "number" and c.dn > 0 then
            local n, all = #c.lines, true
            for i = n - c.dn + 1, n do
                local l = c.lines[i]
                local gain = type(l) == "table" and type(l.t) == "string" and not l.t:match("^%s*%-")
                    and (l.c == ACT.GAIN or (l.t:match("^%s*%+") ~= nil and l.c ~= ACT.RED))
                if not gain then all = false; break end
            end
            if all then return true end
        end
    end
    return false
end
-- gear worse than EVERY worn piece it could replace: a lower item level, or the game's stat changes all losses. An empty slot it
-- could go in = not worse (it is a gain there)
function ACT.worse(row, t)
    local slots = ACT.SLOTS[row.loc]
    if not slots then return false end
    local any = false
    for _, sl in ipairs(slots) do
        if not (row.loc == "INVTYPE_WEAPON" and sl == 17 and not ACT.dualWield()) then
            local wid = SN(function() return GetInventoryItemID("player", sl) end)
            if not wid then return false end
            local wl = ilvlOf(S(function() return GetInventoryItemLink("player", sl) end))
            local lower = type(row.ilvl) == "number" and type(wl) == "number" and row.ilvl < wl
            if not (lower or ACT.allLoss(t, sl) == true) then return false end
            any = true
        end
    end
    return any
end
-- what the quests in the log need (Quests' WC.questNeedItems: ids, and the item objectives' own words)
function ACT.questNeeds()
    if type(WC.questNeedItems) ~= "function" then return { ids = {}, words = {} } end
    local ok, q = pcall(WC.questNeedItems)
    if not (ok and type(q) == "table") then return { ids = {}, words = {} } end
    q.ids = type(q.ids) == "table" and q.ids or {}
    q.words = type(q.words) == "table" and q.words or {}
    return q
end
function ACT.needed(q, id, name)
    if q.ids[id] then return true end
    local n = type(name) == "string" and name ~= "" and name:lower() or nil
    if not n then return false end
    for _, w in ipairs(q.words) do if type(w) == "string" and w:find(n, 1, true) then return true end end
    return false
end
-- one bag item for the Suggested list: { g = 1 junk / 2 gear / 3 cheap white, why, v } or nil (never suggested)
function ACT.consider(bag, slot, id, info, q)
    local quality = SN(function() return info.quality end)
    if quality == nil or quality >= 3 then return nil end                         -- rare and better: never (and unknown)
    if flag(info, "isLocked") == true or ACT.KEEP[id] then return nil end
    local okI, _, _, _, loc, _, cls, sub = CALL(function() return C_Item.GetItemInfoInstant(id) end)
    cls = okI and SN(function() return cls end) or nil
    sub = okI and SN(function() return sub end) or nil
    if cls and ACT.KEEP_CLASS[cls] then return nil end
    if cls == 2 and sub == 20 then return nil end                                -- a fishing pole
    local okQ, qi = CALL(function() return C_Container.GetContainerItemQuestInfo(bag, slot) end)
    if okQ and type(qi) == "table" and (flag(qi, "isQuestItem") == true or type(field(qi, "questID")) == "number") then return nil end
    local okN, nm = CALL(function() return C_Item.GetItemNameByID(id) end)
    if ACT.needed(q, id, okN and S(function() return nm end) or nil) then return nil end
    local count = SN(function() return info.stackCount end) or 1
    local noValue = flag(info, "hasNoValue") == true
    local v = ACT.value(id, count, noValue)
    if quality == 0 or noValue then return { g = 1, why = "junk", v = v } end
    local gl = ACT.loc(id)
    if gl then
        local t = ACT.tip(bag, slot, id)
        if ACT.unusable(t) then return { g = 2, why = "unusable", v = v } end
        local link = S(function() return C_Container.GetContainerItemLink(bag, slot) end)
        if ACT.worse({ loc = gl, ilvl = ilvlOf(link) }, t) then return { g = 2, why = "worse", v = v } end
        return nil                                                               -- an upgrade or a side-grade: kept
    end
    loc = okI and S(function() return loc end) or nil
    if loc == "INVTYPE_BAG" or loc == "INVTYPE_QUIVER" then return nil end
    if quality == 1 then return { g = 3, why = "cheap", v = v } end
    return nil
end
-- THE PICKER'S LIST: Suggested (sec "s", ranked: junk, then worse / unusable gear, then cheap whites; each the lowest value first)
function ACT.suggest()
    local q = ACT.questNeeds()
    ACT.wait = nil
    local groups = { {}, {}, {} }
    for _, bag in ipairs(PLAYER_BAGS) do
        local n = SN(function() return C_Container.GetContainerNumSlots(bag) end) or 0
        for slot = 1, n do
            local info = infoAt(bag, slot)
            local id = info and SN(function() return info.itemID end) or nil
            if id then
                local e = ACT.consider(bag, slot, id, info, q)
                if e then e.bag, e.slot, e.id = bag, slot, id; table.insert(groups[e.g], e) end
            end
        end
    end
    local function before(a, b)
        if (a.v == nil) ~= (b.v == nil) then return b.v == nil end             -- a value still loading goes last in its group
        if a.v ~= nil and a.v ~= b.v then return a.v < b.v end
        if a.bag ~= b.bag then return a.bag < b.bag end
        return a.slot < b.slot
    end
    local list = {}
    for g = 1, 3 do
        table.sort(groups[g], before)
        for _, e in ipairs(groups[g]) do
            if #list < ACT.MAX then list[#list + 1] = { bag = e.bag, slot = e.slot, id = e.id, v = e.v, why = e.why, sec = "s" } end
        end
    end
    return list
end
-- the list plus the cursor's own item ("This item", sec "i") when it is not suggested already; its index
function ACT.withCursor(list)
    for i, e in ipairs(list) do if e.bag == ctl.bag and e.slot == ctl.slot then return list, i end end
    local cid = itemAt(ctl.bag, ctl.slot)
    if not cid then return list, nil end
    local info = infoAt(ctl.bag, ctl.slot)
    list[#list + 1] = { bag = ctl.bag, slot = ctl.slot, id = cid, sec = "i",
                        v = info and ACT.value(cid, SN(function() return info.stackCount end) or 1, flag(info, "hasNoValue") == true) or nil }
    return list, #list
end
-- open the picker for the cursor's item: true = opened; false = nothing suggested (the confirm opens at once, as before)
function ACT.openPicker()
    local list = ACT.suggest()
    if #list == 0 then ACT.list = nil; return false end
    local sel
    ACT.list, sel = ACT.withCursor(list)
    ctl.dsel = sel or 1
    ctl.mode = "dpick"
    return true
end
-- the list again (a value loaded, an item moved), the selection kept on the same item where it still is
function ACT.rebuild()
    local was = ACT.list and ACT.list[ctl.dsel or 1]
    local list = ACT.withCursor(ACT.suggest())
    ACT.list = list
    local sel
    if was then for i, e in ipairs(list) do if e.bag == was.bag and e.slot == was.slot and e.id == was.id then sel = i end end end
    -- bags-act2: its item gone: onto the cursor's own item ("This item"), never silently onto the first suggestion
    if not sel then for i, e in ipairs(list) do if e.bag == ctl.bag and e.slot == ctl.slot then sel = i end end end
    sel = sel or 1
    ctl.dsel = math.min(sel, math.max(1, #list))
    if #list == 0 then ACT.list = nil; toGrid("dpick-empty") end
end
-- bags-act2: at the bag update, the compare sheet whose item left its slot closes
function WC._bagsCompareCheck()
    if ctl.on and ctl.mode == "compare" and itemAt(ctl.bag, ctl.slot) ~= ctl.armId then toGrid("moved") end
end
function WC._bagsDpickLoaded(id)
    if not (ACT.wait and ACT.wait[id]) or ctl.mode ~= "dpick" then return end
    ACT.wait[id] = nil
    if next(ACT.wait) == nil then ACT.rebuild(); if ctl.mode == "dpick" then report(nil) end end
end
-- the confirm for one item (bag, slot): rare and better = the box that says why not; from the picker, B goes back to it
function ACT.confirm(bag, slot, fromPick)
    local info = infoAt(bag, slot)
    if not info then if fromPick then ACT.rebuild(); report("moved") else toGrid("empty") end; return end
    local q = SN(function() return info.quality end) or 0
    ctl.armId = itemAt(bag, slot)
    ctl.dest = { bag = bag, slot = slot }
    ctl.fromPick = fromPick or nil
    ctl.mode = "destroy"
    -- the game makes the player TYPE "DELETE" for rare and better: a controller cannot, so the box says to do it in the game
    if q >= 3 then ctl.rare = true; report("destroy-rare") else ctl.rare = nil; report(nil) end
end
-- B / No / Okay on the confirm: back to the picker it came from, else the grid
function ACT.back(note)
    if ctl.fromPick and ACT.list then
        ctl.mode = "dpick"; ctl.dest = nil; ctl.rare = nil; ctl.fromPick = nil
        ACT.rebuild()
        if ctl.mode == "dpick" then report(note) end
    else toGrid(note) end
end
-- A / Yes: ONE delete, through WC.bagsDestroy (its guards); the slot is confirmed at the next bag update
function ACT.destroy()
    local d = ctl.dest or { bag = ctl.bag, slot = ctl.slot }
    if itemAt(d.bag, d.slot) ~= ctl.armId then toGrid("moved"); return end
    if InCombatLockdown() then toGrid("destroy-combat"); fightRefused(); return end
    local ok, why = WC.bagsDestroy(d.bag, d.slot, ctl.armId, false, function(gone)
        if ctl.on then report(gone and "destroyed" or "destroy-unsure") end
    end)
    if ok then toGrid("destroying"); return end
    if why == "combat" then fightRefused() end
    toGrid((why == "cursor" or why == "moved" or why == "empty") and why or ("destroy-" .. tostring(why)))
end
local function menuStep(dir)
    local ok = menuUsable()
    local i = ctl.menu
    for _ = 1, #MENU do
        i = (i - 1 + dir) % #MENU + 1
        if ok[i] then ctl.menu = i; return end
    end
end

-- bags-bindbox1 BIND TO A BUTTON = THE SPELLBOOK'S BIND BOX for the item (Book b59), hosted by Bags: Bags keeps the controller, the box
-- gets every press (mode "bindbox"), asks the capture for its extra keys through Bags, and hands the room back when it ends
ACT.host = {
    name = "bags",
    extra = function(list)
        local cap = capture()
        if not (cap and type(cap.extra) == "function" and (tonumber(cap.version) or 1) >= 4) then return false end
        local ok, got = pcall(cap.extra, "bags", list)
        return ok and got and true or false
    end,
    -- the box ended (kept, cancelled, its answer closed): the grid, on the same slot
    done = function(note)
        ACT.boxUp = nil
        if ctl.on and ctl.mode == "bindbox" then toGrid(note) end
    end,
}
function ACT.bindBox()
    if InCombatLockdown() then toGrid("bind-combat"); fightRefused(); return end
    local id = itemAt(ctl.bag, ctl.slot)
    if not id then toGrid("empty"); return end
    if type(WC.spellsBindItem) ~= "function" then toGrid("bind-notready"); return end
    ctl.armId = id; ctl.mode = "bindbox"; ACT.boxUp = true
    local ok, opened, why = pcall(WC.spellsBindItem, id, ACT.host)
    if not (ok and opened) then
        ACT.boxUp = nil
        toGrid((ok and why == "combat") and "bind-combat" or "bind-notready")
        return
    end
    report("bindbox")
end

-- THE GRID'S BUTTONS (the user's FINAL word 2026-09-30, after trying "b": "I want a to move, you for more and x to use or
-- equip", and "when its in my bags it should just always auto compare instead of having to hold a button"):
--   A = Move (into the hand; then A puts it down, B cancels)
--   X = the item's action (Use / Equip / ...: the app taps the slot's secure cell)
--   Y = More (Split Stack / Bind to a Button / Compare / Destroy)
--   gear: its stat changes show by themselves at the tooltip's foot (bags-nocmp1; the tooltip's cmp from WC.tipOf), no button
-- With an item in the hand only A and B act.
local function onPress(key)
    local d = key == "PADDUP" or key == "PADDDOWN" or key == "PADDLEFT" or key == "PADDRIGHT"
    local A, B, X, Y = key == "PAD1", key == "PAD2", key == "PAD3", key == "PAD4"
    if ctl.mode == "grid" then
        if ctl.zone == "bar" and BAR.press(key, A) then return end
        if ctl.zone == "bar" and (X or Y) then return end           -- the strip has no X / Y; B leaves the room as on the grid
        if d then moveCursor(key); report(nil); return end
        local id = itemAt(ctl.bag, ctl.slot)
        if A then
            if not ctl.pick then
                if not id then report("empty"); return end
                ctl.pick = { bag = ctl.bag, slot = ctl.slot, id = id }; report("picked"); return
            end
            local p = ctl.pick; ctl.pick = nil
            if p.bag == ctl.bag and p.slot == ctl.slot then report("cancelled"); return end
            if itemAt(p.bag, p.slot) ~= p.id then report("moved"); return end
            if cursorBusy() then report("cursor"); return end
            pickupBag(p.bag, p.slot); pickupBag(ctl.bag, ctl.slot)
            report(settle() and "placed" or "refused"); return
        elseif B then
            if ctl.pick then ctl.pick = nil; report("cancelled") else leave("B") end
            return
        elseif X then
            if ctl.pick then return end                       -- in the hand: only A / B
            if not id then report("empty"); return end
            -- (review 2026-11-11, issue #126) a pick is on: never the bag cell (it EQUIPS gear); the list, on this item's row
            if KIT.on() then
                -- bags-de1: a Disenchant pick = the Disenchant grid, on this item when it is listed (never the bag cell: it EQUIPS gear)
                if KIT.ctx() == "de" then local b0, s0 = ctl.bag, ctl.slot; DE.open(true, "de-pick"); DE.cursorTo(b0, s0); return end
                ctl.mode = "target"; ctl.tsel = KIT.rowOf(ctl.bag, ctl.slot) or 1; ctl.tkey = nil; report("pick-item"); return
            end
            -- a BAG: into the hand, and the cursor up to the bag bar to choose its slot (EQ, the user 2026-10-06)
            if EQ.slotsFor(id) then EQ.begin(id); return end
            -- bags-open1: a box the game says is Locked: never asked (the app shows "Locked" faint)
            if OPEN.lockedAt(ctl.bag, ctl.slot) then report("locked"); return end
            -- bags-gear1 (the user, 2026-11-16: "Choose which ring or weapon slot to replace"): GEAR WITH TWO PLACES TO GO, out of a
            -- fight and with no game window open: the slide-out of its worn slots. One place, a fight, a window: the secure cell below
            local gs = EQ.gearSlots(id)
            if gs and #gs > 1 and not ctx and not InCombatLockdown() then
                if WC._bagsNoUse(ctl.bag, ctl.slot, id) then report("equip-nouse"); return end
                EQ.beginGear(id, gs); return
            end
            -- USE is protected: the app taps this slot's secure cell once per new use.seq
            KIT.used(ctl.bag, ctl.slot)                       -- an armor kit / stone starts the game's "pick an item"
            ctl.use = { bag = ctl.bag, slot = ctl.slot, seq = ctl.seq + 1 }; report("use"); return
        elseif Y then
            if ctl.pick then return end
            if not id then report("empty"); return end
            -- the menu opens on its first row that can be used (Split, unless the stack is one item)
            ctl.mode = "menu"; ctl.menu = #MENU; menuStep(1); report(nil); return
        end
    elseif ctl.mode == "bulk" then
        DE.press(key)                                   -- bags-de1: the Disenchant grid
    elseif ctl.mode == "menu" then
        if key == "PADDUP" then menuStep(-1); report(nil)
        elseif key == "PADDDOWN" then menuStep(1); report(nil)
        elseif B then toGrid(nil)
        elseif A then
            local what = MENU[ctl.menu]
            local info = infoAt(ctl.bag, ctl.slot)
            if not info then toGrid("empty"); return end
            if what == "destroy" then
                -- bags-act1: never in a fight (greyed there; a press that still lands is refused and says so)
                if InCombatLockdown() then toGrid("destroy-combat"); fightRefused(); return end
                -- the picker with its Suggested list first; nothing to suggest = this item's confirm at once, as before
                if ACT.openPicker() then report(nil) else ACT.confirm(ctl.bag, ctl.slot, false) end
            elseif what == "compare" then
                -- bags-act1: the app's compare sheet for this gear; nothing happens in the game
                if not ACT.isGear(itemAt(ctl.bag, ctl.slot)) then toGrid("compare-none"); return end
                ctl.armId = itemAt(ctl.bag, ctl.slot); ctl.mode = "compare"; report(nil)
            elseif what == "split" then
                local n = SN(function() return info.stackCount end) or 1
                if n < 2 then toGrid("split-single"); return end
                ctl.armId = itemAt(ctl.bag, ctl.slot); ctl.amount = 1; ctl.mode = "split"; report(nil)
            elseif what == "bind" then
                ACT.bindBox()                                   -- bags-bindbox1: the Spellbook's Bind box for this item
            end
        end
    elseif ctl.mode == "bindbox" then
        -- bags-bindbox1: every press is the Bind box's (B backs out there, and the box hands the grid back); no box = the grid again
        local hb = WC.spellsBindHosted
        local ok, took = false, false
        if type(hb) == "table" and type(hb.press) == "function" then ok, took = pcall(hb.press, key) end
        if not (ok and took) then ACT.boxUp = nil; toGrid("bind-gone") end
    elseif ctl.mode == "destroy" and ctl.rare then
        if A or B then ACT.back(nil) end               -- Okay: nothing was destroyed (back to the picker it came from)
    elseif ctl.mode == "destroy" then
        if B then ACT.back("cancelled")
        elseif A then
            -- bags-act1: the confirmation named ONE item: anything else in that slot now is not destroyed (ACT.destroy checks), and the
            -- delete itself is WC.bagsDestroy's (never in a fight, nothing on the cursor, a player bag, re-checked at the bag update)
            ACT.destroy()
        end
    elseif ctl.mode == "dpick" then
        -- bags-act1: the Destroy picker. Up / down = the gold row, A = that item's confirm, B = back to the grid
        local n = ACT.list and #ACT.list or 0
        if key == "PADDUP" or key == "PADDDOWN" then
            ctl.dsel = math.max(1, math.min(n, (ctl.dsel or 1) + (key == "PADDUP" and -1 or 1))); report(nil)
        elseif B then toGrid(nil)
        elseif A then
            local e = ACT.list and ACT.list[ctl.dsel or 1]
            if not e then toGrid("empty"); return end
            if InCombatLockdown() then toGrid("destroy-combat"); fightRefused(); return end
            if itemAt(e.bag, e.slot) ~= e.id then ACT.rebuild(); if ctl.mode == "dpick" then report("moved") end; return end
            ACT.confirm(e.bag, e.slot, true)
        end
    elseif ctl.mode == "compare" then
        -- bags-act1: the compare sheet. B = back to the grid; the D-pad scrolls the sheet in the app (nothing here); the item gone = closed
        if itemAt(ctl.bag, ctl.slot) ~= ctl.armId then toGrid("moved"); return end
        if B then toGrid(nil) end
    elseif ctl.mode == "search" then
        -- the companion's keyboard has the presses (BAR.act "search"): nothing of Bags' acts meanwhile
        return
    elseif ctl.mode == "barmenu" then
        BAR.menuPress(key, A, B)
    elseif ctl.mode == "equip" then
        -- bags-gear2: a piece of gear's targets are a menu (the app's rows, one under the other): up / down walk it too
        local gearUD = ctl.eq and ctl.eq.gear and (key == "PADDUP" or key == "PADDDOWN")
        if key == "PADDLEFT" or key == "PADDRIGHT" or gearUD then
            local n = #((ctl.eq and ctl.eq.slots) or EQ.TARGETS)
            local step = (key == "PADDRIGHT" or key == "PADDDOWN") and 1 or -1
            ctl.eqAt = math.max(1, math.min(n, (ctl.eqAt or 1) + step)); report(nil)
        elseif B then EQ.done("cancelled")
        elseif A then EQ.go() end
    elseif ctl.mode == "bindask" then
        -- A / B are the game's while it asks (passed, syncPass): one that reaches Bags means the pass is not in force. Say so in
        -- the log, and pass again (the next report runs syncPass afresh), so the player's next press answers the game
        if A or B then ctl.passing = false; report("bindask-" .. key) end
        -- "equipping": the game is working: nothing here
    elseif ctl.mode == "target" then
        -- "Choose an item": up / down walk the list, A uses the chosen item (the pick's target), B stops the pick
        -- bags-kit2 (issue #89): while the game asks about the pick, A / B are its own (passed); one that still reaches the room
        -- (the pass not yet in force) never uses the item again - that was the loop - and the pass is made again
        if (A or B) and KIT.asking() then ctl.passing = false; report("pick-ask-" .. key); return end
        local list = KIT.list()
        if key == "PADDUP" or key == "PADDDOWN" then
            local at = KIT.find(list, ctl.tkey) or ctl.tsel or 1                -- bags-kit3 review: from the item the cursor is on
            ctl.tsel = math.max(1, math.min(#list, at + (key == "PADDUP" and -1 or 1))); ctl.tkey = KIT.key(list[ctl.tsel]); report(nil)
        elseif B then
            -- B: the app taps binv 19 (/stopspelltarget) for this use.seq; the list closes now
            ctl.use = { stop = true, seq = ctl.seq + 1 }; KIT.stopT = GetTime(); toGrid("cancelled")
        elseif A then
            if #list == 0 then report("empty"); return end
            -- bags-kit3 review: the item the cursor is on, wherever its row is now; gone (or not yet drawn) = not used, drawn again
            local at = KIT.find(list, ctl.tkey)
            if not at then ctl.tkey = nil; report("pick-moved"); return end
            ctl.tsel = at
            local e = list[at]
            if e.ok == nil then report("pick-cannot"); return end
            ctl.applied = true
            if e.src == "inv" then ctl.use = { inv = e.slot, seq = ctl.seq + 1 }
            elseif e.cell then ctl.use = { cell = e.cell, seq = ctl.seq + 1 }     -- bag gear: its /use cell (issue #126), never the bag cell
            else ctl.use = { bag = e.bag, slot = e.s, seq = ctl.seq + 1 } end
            report("apply")
        end
    elseif ctl.mode == "split" then
        local info = infoAt(ctl.bag, ctl.slot)
        local n = info and SN(function() return info.stackCount end) or 1
        if d then
            local step = (key == "PADDUP" and 10) or (key == "PADDDOWN" and -10) or (key == "PADDRIGHT" and 1) or -1
            ctl.amount = math.max(1, math.min(n - 1, ctl.amount + step)); report(nil)
        elseif B then toGrid("cancelled")
        elseif A then
            if itemAt(ctl.bag, ctl.slot) ~= ctl.armId then toGrid("moved"); return end
            local eb, es = firstEmpty()
            if not eb then report("no-space"); return end     -- the dialog stays and says so
            if cursorBusy() then toGrid("cursor"); return end
            CALL(function() return C_Container.SplitContainerItem(ctl.bag, ctl.slot, ctl.amount) end)
            pickupBag(eb, es)
            toGrid(settle() and "split" or "refused")
        end
    end
end

-- bags-open1 (capture v21): asked by the capture when a loot window opens while Bags holds - is it the room's own? A bag cell of ours
-- clicked within OPEN.TTL (the X route, or a finger's Open), the hold really ours, out of a fight. Once per click; noted "loot-under"
function OPEN.mine()
    local c = OPEN.click
    if not (ctl.on and not ctl.pending and c and GetTime() - c.at <= OPEN.TTL) then return false end
    if InCombatLockdown() then return false end
    OPEN.click = nil
    OPEN.under = c.key
    report("loot-under")
    return true
end
-- the loot window over the room closed: Bags holds again as it was (same mode, same slot), told nothing else
function OPEN.resumed(why)
    OPEN.under = nil
    report("loot-resumed")
end

local function onLost(why)
    OPEN.under = nil
    if ACT.boxUp then ACT.boxUp = nil; if type(WC.spellsBindHosted) == "table" then pcall(WC.spellsBindHosted.abort, why) end end
    -- bags-de1: the Disenchant grid lost the pad to a loot window's pop-up (the user's pick 3a: "our loot pop-up comes up ... then the grid
    -- comes back"): it takes the pad back itself when the loot window closes, the cursor where it was (DE.retake). "left-lootwait": the app
    -- keeps the Bags room on screen under the pop-up (never Home)
    local wasBulk = ctl.on and ctl.mode == "bulk"
    if wasBulk and why == "npc" and (DE.lootOpen or (DE.lastDone and GetTime() - DE.lastDone < DE.DONE_S)) then
        DE.resume = { at = GetTime(), from = ctl.deFrom, bkey = ctl.bkey, bsel = ctl.bsel }
        ctl.on = false; ctl.pick = nil; ctl.mode = "grid"; ctl.passing = false; ctl.zone = nil; ctl.deAsk = nil
        ctl.left = "lootwait"
        report("left-lootwait")
        return
    end
    if wasBulk then DE.resume = nil; ctl.deFrom = nil; ctl.deAsk = nil end
    ctl.on = false; ctl.pick = nil; ctl.mode = "grid"; ctl.passing = false; ctl.zone = nil
    -- capture v8: a B Bags leaves to the game (pass) cannot reach the game in a fight, so the capture releases Bags with
    -- lost("B"): that is the player's own B (the app goes Home, and the 1 s check does not take it back)
    if why == "B" then ctl.left = "B"; report("left-B"); return end
    -- capture v9 / v11: a later room (or the game's window for another room) won the controller: never take it back,
    -- the player chose that one (Bags' retake would make it the latest requester and win it back)
    if why == "taken" then ctl.left = "taken"; report("lost-taken"); return end
    ctl.left = "lost"
    report("lost-" .. tostring(why))
end

local function shownBagFrames()
    local out = {}
    for _, n in ipairs(BAG_FRAMES) do
        local f = _G[n]
        local ok, shown = pcall(function() return f and f:IsShown() end)
        if ok and shown then out[#out + 1] = n end
    end
    return out
end

-- an NPC or interaction window is open: the bags opened FOR it (a vendor opens them), and that window
-- owns the controller -- interact's pop-up, or the game's own window when the companion is not in
-- charge of NPC windows (then the capture's holder is nil, so the holder alone cannot tell us)
-- USER RULING 2026-09-30 (relayed): the game's CHARACTER window opens the bags with it on the controller; the
-- Character room gets the controller then, and Bags skips it the way it does for a vendor (the bags stay reachable by
-- touch). Measured by the character session (12:17:29): Bags' take can come 4 ms BEFORE CharacterFrame's own OnShow, so
-- this check alone is not enough -- the OnShow hook below hands the controller over too.
local NPC_FRAMES = { "MerchantFrame", "GossipFrame", "QuestFrame", "BankFrame", "MailFrame", "TradeFrame",
                     "AuctionHouseFrame", "GuildBankFrame", "ClassTrainerFrame", "CharacterFrame" }
-- bags-take1: answers WHAT is open (the frame's name, or "ctx-<name>" for an open event whose frame has not shown yet), or nil.
-- ctx alone is no longer believed (CTXW.now: a close event that never came left it set for good); a stale one is let go here.
local function npcWindowOpen()
    local c = CTXW.now()
    if c then return CTXW.shown(CTXW.FRAME[c]) and CTXW.FRAME[c] or ("ctx-" .. c) end
    for _, n in ipairs(NPC_FRAMES) do
        if CTXW.shown(n) then return n end
    end
    return nil
end

-- why the controller was NOT taken, once per reason (the 1 s check would otherwise repeat it)
local function skip(why) if ctl.note ~= why then report(why) end end

-- [viaWindow]: the take comes from the GAME's bag window opening (every route since the doors, see bagsPadRows)
local function tryTake(viaWindow)
    if ctl.on then return end
    local cap = capture()
    if not cap then skip("take-skip-nocapture"); return end       -- interact's addon absent or older
    if (tonumber(cap.version) or 1) < 2 then skip("take-skip-version"); return end   -- v2: pass + release reasons
    -- SPEC_COMBAT item 9 ("opening a room during a fight takes the controller"): no combat skip of Bags' own any more. A
    -- capture from i81 answers ok + "pending" in a fight (it really holds once Home taps the enter cell); an older one
    -- answers "combat", which is logged as the refusal
    -- bags-take1: the note names the window ("take-skip-window:MerchantFrame"), and the take is tried again when it goes (CTXW.retry)
    local win = npcWindowOpen()
    if win then
        CTXW.wait = { at = GetTime() or 0, win = win }
        skip("take-skip-window:" .. win); return
    end
    CTXW.wait = nil
    -- ALL the bag windows, not only the shown ones: with separate bags the game opens them one by
    -- one, and the ones after the first must not flash up either
    local hide = {}
    for _, n in ipairs(BAG_FRAMES) do if _G[n] then hide[#hide + 1] = n end end
    -- a press only ever arrives after the hand-over: the first one ends a queued / pending wait
    ctl.spec = { press = function(key) if ctl.pending then ctl.pending = nil; report("taken") end; onPress(key) end,
                 lost = onLost, hide = hide,
                 -- bags-de1 (capture v21, Interact i161): the Disenchant grid keeps the pad through an AUTO loot window (the user's pick
                 -- 3a: "the mats go straight to your bags"); an older capture ignores the field
                 lootPass = function() return ctl.on and ctl.mode == "bulk" end }
    -- capture v9 (Interact i78): the game window's own close button for the generic / fight leave, and "the game's window
    -- opened" so the take pre-empts a room still holding (it gets lost("taken")). Only set where they are true, and only
    -- for a capture that knows them (an unknown or wrong field answers "bad spec")
    if (tonumber(cap.version) or 1) >= 9 then
        if _G.ContainerFrameCombinedBagsCloseButton then ctl.spec.leave = "ContainerFrameCombinedBagsCloseButton" end
        if viaWindow then ctl.spec.window = true end
    end
    -- bags-open1: capture v21 (Interact i161): a loot window the room opened itself is shown over the room, which resumes after it
    if (tonumber(cap.version) or 1) >= 21 then ctl.spec.lootUnder = OPEN.mine; ctl.spec.resumed = OPEN.resumed end
    local ok, got, why = pcall(cap.take, "bags", ctl.spec)
    if ok and got then
        ctl.on = true; ctl.mode = "grid"; ctl.pick = nil; ctl.left = nil; OPEN.under = nil
        -- (review 2026-11-11, issue #126) taken while a pick is on (Disenchant cast from the bar): straight to its list, never the grid
        if KIT.on() then
            -- bags-de1: a Disenchant pick (the game opened its bags for it: any cast of the spell) = the Disenchant grid (B goes Home)
            if KIT.ctx() == "de" then ctl.mode = "bulk"; ctl.deFrom = nil; ctl.bsel = 1; ctl.bkey = nil; ctl.deAsk = nil; DE.resume = nil
            else ctl.mode = "target"; ctl.tsel = 1; ctl.tkey = nil end
        end
        -- capture v11 (Interact i82): a take that finds another holder is QUEUED (ok, "queued"), granted when that holder
        -- lets go; no presses before the hand-over. Bags shows the room meanwhile and says so in its note
        ctl.pending = (why == "pending" or why == "queued") or nil
        local L = layout()
        if not bagIndex(ctl.bag) and L[1] then ctl.bag, ctl.slot = L[1].bag, 1 end
        report((why == "queued" and "taken-queued") or (ctl.pending and "taken-pending") or "taken")
    else
        report("take-refused-" .. tostring(ok and why or "error"))
    end
end

-- bags-take1: a take skipped for an open window is tried again once that window is gone (its OnHide, its close event, below), while
-- the game's bags are still open: the game's bag window never stays up on the top screen with the room holding nothing. The same
-- exceptions as the 1 s check (the room left by touch, freed by Home, the player's B, a later room won): no take back then.
CTXW.WAIT = 120
CTXW.retry = function()
    local w = CTXW.wait
    if not w or ctl.on or not ctl.open then return end
    if (GetTime() or 0) - w.at > CTXW.WAIT then CTXW.wait = nil; return end
    if ctl.left == "room" or ctl.left == "free" or ctl.left == "B" or ctl.left == "taken" then return end
    if npcWindowOpen() then return end
    tryTake(true)
end

syncDoors = function()
    if not (doors.open and doors.close) then return end
    if InCombatLockdown() then doors.stale = true; return end
    local backpack = _G.MainMenuBarBackpackButton
    if not backpack then doors.stale = true; return end
    local shown = #shownBagFrames() > 0
    pcall(doors.open.SetAttribute, doors.open, "clickbutton", backpack)
    pcall(doors.close.SetAttribute, doors.close, "clickbutton", backpack)
    pcall(doors.open.SetAttribute, doors.open, "type", (not shown) and "click" or nil)
    pcall(doors.close.SetAttribute, doors.close, "type", shown and "click" or nil)
    doors.stale = false
end
-- the doors can be tapped: built, switched to the windows' state, and not in a fight
DE.take = function() tryTake() end            -- bags-de1: the grid's own take back after a loot window (DE.retake)
function WC._bagsDoorsLive()
    return doors.open ~= nil and doors.close ~= nil and not doors.stale and not InCombatLockdown()
end

-- bags-take2: a FRESH open (the bags were closed) is a new entry: an earlier visit's leave ("taken" by the Spellbook room, Home's
-- "free", B, a touch leave) no longer stops this visit's retry or the 1 s check (ctl.left is a leave of THIS visit only)
local function onBagsShown()
    if not ctl.open and not ctl.on then ctl.left = nil end
    ctl.open = true; syncDoors(); tryTake(true)
end
local function onBagsHidden()
    syncDoors()
    if #shownBagFrames() > 0 then return end        -- another bag window is still open
    CTXW.wait = nil                                 -- bags-take1: the bags closed: nothing to take back for
    if not ctl.open and not ctl.on then return end  -- B already closed them
    ctl.open = false
    if not ctl.on then return end       -- never held (skipped / refused): keep that reason in the log
    -- a bag swap's own churn (the game hides its window and shows it again): Bags keeps the controller (EQ.CHURN)
    if ctl.eqPutAt and GetTime() - ctl.eqPutAt < EQ.CHURN then syncPass(); return end
    -- the app's close door (the room left by touch / the bar): a non-B leave
    if doors.closingAt and GetTime() - doors.closingAt < 1 then doors.closingAt = nil; leave("room"); return end
    -- bags-de1: the Disenchant grid stays: the game closes its bags after each pick when they were closed before the cast
    -- (containerframe.lua wasBagOpenBeforeItemTargeting), never the player's leave
    if ctl.mode == "bulk" then syncPass(); return end
    -- the game closed its windows itself: with B passed to it, that was the player's B
    leave(ctl.passing and "B" or "closed")
end

WC._bagsOnHidden = function() onBagsHidden() end     -- bags-de1: for the off-device tests (tools/test_disenchant.py)
-- hook the bag windows themselves, whatever opened them (the controller's bag button, a key, a click)
do
    local hf = CreateFrame("Frame")
    hf:RegisterEvent("PLAYER_LOGIN")
    hf:SetScript("OnEvent", function()
        for _, n in ipairs(BAG_FRAMES) do
            local f = _G[n]
            if f and f.HookScript then
                pcall(f.HookScript, f, "OnShow", onBagsShown)
                pcall(f.HookScript, f, "OnHide", onBagsHidden)
            end
        end
        -- a window that opens the bags with it, shown AFTER Bags took the controller (measured for the Character
        -- window: Bags' take came 4 ms first): Bags hands the controller back at once. The Character window says
        -- "character" (the Character room takes it; the app must not go Home); the NPC windows say "window" (the
        -- mailbox, a vendor...: the user 2026-09-30 "Mailbox ... opens backpack in the top screen" -- Interact
        -- hides those bags by alpha and holds the controller for its own window). Windows the game loads on
        -- demand (the auction house, the trainer) are not here yet: the check at take time covers them.
        for _, n in ipairs(NPC_FRAMES) do
            local f = _G[n]
            local why = n == "CharacterFrame" and "character" or "window"
            if f and f.HookScript then
                pcall(f.HookScript, f, "OnShow", function() if ctl.on then leave(why) end end)
                -- bags-take1: the window that kept Bags from taking is gone: try again (CTXW.retry)
                pcall(f.HookScript, f, "OnHide", function() if CTXW.wait then pcall(C_Timer.After, 0, CTXW.retry) end end)
            end
        end
    end)
    -- an NPC took the keys: take them back when it is done and the bags are still open. NOT after the player left the
    -- room by touch ("room") or Home freed it ("free"): the game's bags may still be open on top then, and taking back
    -- would flip the bottom screen back to the Bags room (Home's rule 2026-09-30: no room keeps the controller after
    -- it's left). The next touch in, or the game opening its bags again, takes it as before.
    C_Timer.NewTicker(1.0, function()
        if ctl.mode == "search" and GetTime() - (ctl.searchAt or 0) > BAR.SEARCH_TTL then BAR.searchDone("search-timeout") end
        if ctl.on and ctl.pending and WC._bagsHeldNow then WC._bagsHeldNow(); return end
        if not ctl.open or ctl.on then return end
        if ctl.left == "room" or ctl.left == "free" or ctl.left == "B" or ctl.left == "taken" then return end
        local cap = capture()
        local st = cap and cap.state and select(2, pcall(cap.state))
        if type(st) == "table" and st.holder == nil then tryTake() end
    end)
end

-- USER (2026-09-28 ~20:50, relayed): "when I open bags with the button the cursor works great, when I touch
-- the bags button the cursor and controls stay on the top screen". Entering the Bags ROOM by touch takes the
-- controller WITHOUT opening any game window (the D-pad and every item call work with the bags closed); leaving
-- the room by touch hands it back. Two spare columns of Bags' own drag row "bmvq": 29 = entered, 30 = left.
local TOUCH_IN, TOUCH_OUT = 29, 30
-- USER 2026-09-29 10:03 (relayed): "always show, change the wording on pick up to Move". Everything the controller
-- does in the Bags room also answers a finger, as ONE tap on Bags' own row "bmvq" (the pad's row budget is untouched):
--   31 A, 32 Y, 33 B, 34 left, 35 right, 37 up, 38 down (the user's final word 2026-09-30: A Move / Put Down / Choose /
--   Yes / Okay, Y More, B Back / No / Cancel, the D-pad the amount). Use (X) by touch is the app's own tap on the slot's
--   secure cell (one touch = the game's right-click)
--   19 / 20 / 21 = choose Split / Bind / Destroy in the menu      36 = the NEXT bmv tap moves the cursor there
--   27 = the NEXT bmvq tap on column 0..5 opens / closes that bag in the room (BAR: a finger on the strip's bag slot; no capture
--        needed, the same ctl.shut the controller's A toggles); column 17 after it = the app's search keyboard closed (BAR.searchDone)
--   25 = clean up the OLD item binds (the WCB macros + their saved bindings, BB.cleanOld): no capture needed, out of combat
--   22 = X by touch, for a BAG (the card's Equip: into the hand, the cursor up to the bag bar; EQ)
--   26 = the NEXT bmvq tap on column 1..5 names the bag slot the bag in the hand goes in (EQ; a tap on the room's bag bar)
--   23 / 24 = the radial's pin picker opened / closed: the bags doc carries every usable item's tooltip (ptips) while open;
--        no capture, no game action
--   28 = let go of the shared capture, NO game action (Home's generic rule, 2026-09-30, tracking's yes under the user's
--        delegation: a current doc names Bags the holder while the Bags room is not on screen). Never closes the game's
--        bags, never presses anything in the game; frees the capture even if Bags thinks it already let go
--   39 = drop what is held (the panel's Cancel while holding, user 2026-09-30): ONLY that. Not B: B with nothing
--        held leaves the room, so a Cancel that lands after the item was put down must not send the player Home
-- A tap on a slot moves the controller's cursor there first (36 + that slot's bmv cell, no game action), so Move,
-- More and the card always act on the slot the player touched. Without the controller held, a tap takes it first.
local VIRTUAL = { [22] = "PAD3", [31] = "PAD1", [32] = "PAD4", [33] = "PAD2", [34] = "PADDLEFT", [35] = "PADDRIGHT",
                  [37] = "PADDUP", [38] = "PADDDOWN" }
local CURSOR_NEXT, CURSOR_TTL = 36, 2.0
local MENU_ROWS = { [19] = 1, [20] = 2, [21] = 3 }
ACT.ROW_NAME = { [19] = "split", [20] = "bind", [21] = "destroy" }
local DROP_HELD, FREE = 39, 28

-- right after a "target" tap (col 26): bmvq column t = the bag slot t, the target of the bag in the hand (EQ). True = taken
-- (never a worn-slot action then, even outside the equip mode)
function WC._bagsTargetTap(col)
    if not ctl.targetAt or (GetTime() - ctl.targetAt) > CURSOR_TTL then return false end
    ctl.targetAt = nil
    if not (ctl.on and ctl.mode == "equip" and ctl.eq) then return true end
    local gear = ctl.eq.gear                                -- bags-gear1: the gear's targets are worn slots, column = slot - 1
    for i, t in ipairs(ctl.eq.slots or EQ.TARGETS) do
        if (gear and t == col + 1) or (not gear and t == col) then ctl.eqAt = i; report(nil); return true end
    end
    return true
end

-- bags-gear1 (C10): a worn-slot column (0..18) while the room holds OUTSIDE its grid, with no fresh 26 before it (lost, or more than
-- CURSOR_TTL early): swallowed. It fell through to onInvCell before - a PICK of the worn piece, live for PICK_TTL, which a later drop
-- would have taken off into a bag. True = taken (nothing happens)
function WC._bagsSwallowInv(col)
    if not (ctl.on and ctl.mode ~= "grid") or type(col) ~= "number" or col > 18 then return false end
    mvReply({ ok = false, why = "swallowed", to = { inv = col + 1 } })
    return true
end

-- right after a "shut" tap (col 27): bmvq column b = bag b opened / closed in the room (BAR). True = taken
function WC._bagsShutTap(col)
    if not ctl.shutAt or (GetTime() - ctl.shutAt) > CURSOR_TTL then return false end
    ctl.shutAt = nil
    if col == BAR.SEARCH_DONE_COL then BAR.searchDone(); return true end      -- the app's keyboard closed
    if col == ACT.COMPARE_COL then ACT.menuPick("compare"); return true end   -- bags-act1: the menu's Compare row by touch
    if col == DE.TAP_COL then                                                  -- bags-de1: a finger on the strip's Disenchant icon
        if not ctl.on then tryTake() end
        if WC._bagsHeldNow() then DE.fromStrip() end
        return true
    end
    for _, e in ipairs(layout()) do
        if e.bag == col then
            report(BAR.toggle(col))
            if ctl.on and ctl.zone ~= "bar" and BAR.isShut(ctl.bag) then BAR.leave() end   -- the cursor's bag closed: onto an open one
            return true
        end
    end
    return true
end

-- bags-onerow1: a bmv tap with no bag name voids a pending cursor tap; bags-onerow3: and the touch A / Y / X right after it is refused
function WC._bagsCursorVoid() ctl.cursorAt = nil; ctl.missAt = GetTime() end
function WC._bagsCursorMiss() if ctl.on then ctl.missAt = GetTime() end end   -- a bmv tap that was no cursor move (its 36 lost)
-- bags-onerow3: true (once) when the last finger's cursor move was lost, or its 36 is still waiting for its bmv cell
local function cursorMissed()
    local now = GetTime()
    local miss = (ctl.missAt and (now - ctl.missAt) <= CURSOR_TTL) or (ctl.cursorAt and (now - ctl.cursorAt) <= CURSOR_TTL)
    ctl.missAt, ctl.cursorAt = nil, nil
    return miss and true or false
end
function WC._bagsCursorTap(bag, slot)
    if not ctl.cursorAt or (GetTime() - ctl.cursorAt) > CURSOR_TTL then return false end
    ctl.cursorAt, ctl.missAt = nil, nil
    if not ctl.on then tryTake() end
    if not ctl.on then return true end            -- not taken (the reason is logged): nothing else happens
    -- bags-act1: in the Destroy picker a finger on a row is 36 + that item's bmv cell: the gold row goes there (no game action)
    if ctl.mode == "dpick" then
        for i, e in ipairs(ACT.list or {}) do
            if e.bag == bag and e.slot == slot then if WC._bagsHeldNow() then ctl.dsel = i; report(nil) end; break end
        end
        return true
    end
    -- bags-de1: a finger on a tile of the Disenchant grid: its cursor there (no game action; the app's tap on it again is A)
    if ctl.mode == "bulk" then if WC._bagsHeldNow() and not ctl.deAsk then DE.cursorTo(bag, slot) end; return true end
    if ctl.mode ~= "grid" then return true end    -- a menu / dialog is open: its own buttons act
    if not bagIndex(bag) then return true end
    ctl.bag, ctl.slot = bag, slot
    report(nil)
    return true
end

-- CAPTURE v11's CONTRACT (Interact i82b): true = held now; true + "queued" = behind another holder; true + "pending" = in a
-- fight until the enter tap. Never act as holder before a plain true or state().holder == "bags": a finger on Bags' own
-- cells waits for that (the room shows, nothing happens); this also notices the hand-over Bags is never told about
local function heldNow()
    if not ctl.on then return false end
    if OPEN.under then return false end         -- bags-open1: the loot window over the room has the controller until it closes
    if not ctl.pending then return true end
    local cap = capture()
    local ok, st = pcall(function() return cap and cap.state and cap.state() end)
    if ok and type(st) == "table" and st.holder == "bags" then ctl.pending = nil; report("taken"); return true end
    return false
end
WC._bagsHeldNow = heldNow
-- bags-act1: a finger on a Y-menu row: that row chosen (A), only if it can be used now
function ACT.menuPick(name)
    if not (heldNow() and ctl.mode == "menu") then return end
    local i
    for n, w in ipairs(MENU) do if w == name then i = n end end
    if not i or not menuUsable()[i] then return end
    ctl.menu = i
    onPress("PAD1")
end

function WC._bagsTouch(col)
    if col == TOUCH_IN then
        if not ctl.on then ctl.left = nil end   -- bags-take2: entering the room is a new visit (an earlier leave no longer holds)
        tryTake(ctl.open)                   -- the game's bags already open: that is the window's take
    elseif col == TOUCH_OUT then
        if ctl.on then leave("room") else DE.resume = nil end       -- bags-de1: the room left under a loot pop-up: no take back
    elseif col == FREE then
        if ctl.on then leave("free"); return end
        -- the capture may still name Bags while Bags thinks it let go: let go there too (release refuses any other holder)
        local cap = capture()
        local ok, done = false, false
        if cap then ok, done = pcall(cap.release, "bags", "free") end
        ctl.left = "free"
        report((ok and done) and "left-free" or "free-nothing")
    elseif col == CURSOR_NEXT then
        ctl.cursorAt = GetTime()
    elseif col == 25 then                               -- the old item binds cleaned up (BB.cleanOld)
        report(BB.cleanOld())
        sendSoon("wcb")
    elseif col == 27 then                               -- the next bmvq column 0..5 = a bag to open / close (WC._bagsShutTap)
        ctl.shutAt = GetTime()
    elseif col == 26 then                               -- the next bmvq column 1..5 = a bag slot (WC._bagsTargetTap)
        ctl.targetAt = GetTime()
    elseif col == 23 or col == 24 then                  -- the pin picker (pinTips.ON / OFF, above collectBags)
        WC._bagsPinTipsSet(col == 23)
        sendSoon("ptips")
    elseif col == DROP_HELD then
        if heldNow() and ctl.mode == "grid" and ctl.pick then ctl.pick = nil; report("cancelled") end
    elseif VIRTUAL[col] then
        -- bags-onerow3: A / Y / X by touch act on the slot the finger named; its cursor move lost = nothing (no other slot)
        if (col == 31 or col == 32 or col == 22) and cursorMissed() then report("cursor-miss"); return end
        if not ctl.on then tryTake() end
        if not heldNow() then return end
        local key = VIRTUAL[col]
        onPress(key)
    elseif MENU_ROWS[col] and ctl.mode == "barmenu" then
        if heldNow() and MENU_ROWS[col] <= #BAR.MENU then ctl.barMenu = MENU_ROWS[col]; BAR.menuChoose(MENU_ROWS[col]) end
    elseif MENU_ROWS[col] then
        -- bags-act1: the Y menu's rows by NAME (19 Split, 20 Bind, 21 Destroy, as before; Compare = bmvq 27 then column 8)
        ACT.menuPick(ACT.ROW_NAME[col])
    end
end

-- ---------------------------------------------------------------- equipped gear
-- The character's worn items. All of these were verified present in the client binary:
-- GetInventoryItemID / GetInventoryItemQuality / GetInventoryItemTexture /
-- GetInventoryItemDurability / GetAverageItemLevel.
--
-- Slot ids are the classic 1..19 layout. Names are sent so the app does not have to carry a
-- slot table, and so a slot that this build numbers differently still reads correctly.
local SLOTS = {
    [1]="Head", [2]="Neck", [3]="Shoulder", [4]="Shirt", [5]="Chest", [6]="Waist",
    [7]="Legs", [8]="Feet", [9]="Wrist", [10]="Hands", [11]="Finger 1", [12]="Finger 2",
    [13]="Trinket 1", [14]="Trinket 2", [15]="Back", [16]="Main Hand", [17]="Off Hand",
    [18]="Ranged", [19]="Tabard",
}

-- The game's own TOOLTIP for a worn slot, line by line in the game's colours, for the Character room's card
-- (character session's request; UI Art's Character SPEC 3.1). C_TooltipInfo.GetInventoryItem is in the binary
-- ("Usage: local data = C_TooltipInfo.GetInventoryItem(unit, slot [, hideUselessStats])"); NOT measured. Each
-- line: t = the left text, c = its colour as "rrggbb"; r / rc = the right text and its colour when there is one.
-- A secret or unreadable line is left out, never guessed.
local TIP_MAX = 30
local function hexOf(col)
    if type(col) ~= "table" then return nil end
    local r = SN(function() return col.r end); local g = SN(function() return col.g end); local b = SN(function() return col.b end)
    if not (r and g and b) then return nil end
    local function h(v) return math.max(0, math.min(255, math.floor(v * 255 + 0.5))) end
    return string.format("%02x%02x%02x", h(r), h(g), h(b))
end
local function readable(v)
    local t = S(function() return v end)
    if type(t) == "string" and t ~= "" and t:byte(1) > 3 then return t end
    return nil
end
local function tipLines(slot)
    if not (C_TooltipInfo and C_TooltipInfo.GetInventoryItem) then return nil end
    local ok, data = WC.CALL(function() return C_TooltipInfo.GetInventoryItem("player", slot) end)
    if not ok or type(data) ~= "table" then return nil end
    local lines = type(data.lines) == "table" and data.lines or nil
    if not lines then return nil end
    local out = {}
    for i = 1, math.min(#lines, TIP_MAX) do
        local ln = lines[i]
        if type(ln) == "table" then
            local t = readable(ln.leftText)
            if t then
                local row = { t = t, c = hexOf(ln.leftColor) }
                local rt = readable(ln.rightText)
                if rt then row.r = rt; row.rc = hexOf(ln.rightColor) end
                out[#out + 1] = row
            end
        end
    end
    return #out > 0 and out or nil
end

-- THE GAME'S DURABILITY WARNING (f-durability): the little armoured figure the game shows by the minimap when gear is damaged
-- (yellow) or broken (red), DurabilityFrame. READ, never written: whether the game shows it, and each of its pieces as the game
-- has drawn them (texture or atlas, crop, place and size inside the frame, the colour the game gave it). So the rooms draw the
-- game's own figure, coloured by the game, with nothing assumed about its art. Pieces are found by walking the frame's regions
-- (no names assumed). Nil when this client has no such frame.
local DUR_MAX_PARTS = 24
local function hex3(r, g, b)
    if not (type(r) == "number" and type(g) == "number" and type(b) == "number") then return nil end
    local function h(v) return math.max(0, math.min(255, math.floor(v * 255 + 0.5))) end
    return string.format("%02x%02x%02x", h(r), h(g), h(b))
end
local function durWarning()
    local f = _G.DurabilityFrame
    if type(f) ~= "table" then return nil end
    if S(function() return f:IsShown() end) ~= true then return { show = false } end
    local fl, ft = SN(function() return f:GetLeft() end), SN(function() return f:GetTop() end)
    local fw, fh = SN(function() return f:GetWidth() end), SN(function() return f:GetHeight() end)
    local fsc = SN(function() return f:GetEffectiveScale() end)
    if not (fl and ft and fw and fh and fsc and fw > 0 and fh > 0 and fsc > 0) then return { show = true } end
    local parts = {}
    local function take(rg)
        if #parts >= DUR_MAX_PARTS or type(rg) ~= "table" then return end
        if S(function() return rg:GetObjectType() end) ~= "Texture" then return end
        if S(function() return rg:IsShown() end) ~= true then return end
        local l, t = SN(function() return rg:GetLeft() end), SN(function() return rg:GetTop() end)
        local w, h = SN(function() return rg:GetWidth() end), SN(function() return rg:GetHeight() end)
        local sc = SN(function() return rg:GetEffectiveScale() end) or fsc
        if not (l and t and w and h and w > 0 and h > 0) then return end
        local k = sc / fsc
        local part = { x = l * k - fl, y = ft - t * k, w = w * k, h = h * k }
        local atlas = S(function() return rg:GetAtlas() end)
        if type(atlas) == "string" and atlas ~= "" and atlas:byte(1) > 3 then
            local ok, ai = CALL(function() return C_Texture.GetAtlasInfo(atlas) end)
            if ok and type(ai) == "table" then
                part.f = SN(function() return ai.file end)
                part.tc = { SN(function() return ai.leftTexCoord end), SN(function() return ai.rightTexCoord end),
                            SN(function() return ai.topTexCoord end), SN(function() return ai.bottomTexCoord end) }
            end
        else
            part.f = SN(function() return rg:GetTextureFileID() end)
            local ok, ulx, uly, llx, lly, urx, ury, lrx, lry = CALL(function() return rg:GetTexCoord() end)
            if ok and type(ulx) == "number" and type(lry) == "number" then
                part.tc = { math.min(ulx, llx), math.max(urx, lrx), math.min(uly, ury), math.max(lly, lry) }
            end
        end
        if not part.f then return end
        local okC, r, g, b = CALL(function() return rg:GetVertexColor() end)
        if okC then part.c = hex3(r, g, b) end
        parts[#parts + 1] = part
    end
    local function walk(fr, depth)
        local okR, regs = CALL(function() return { fr:GetRegions() } end)
        if okR and type(regs) == "table" then for _, rg in ipairs(regs) do take(rg) end end
        if depth > 0 then
            local okK, kids = CALL(function() return { fr:GetChildren() } end)
            if okK and type(kids) == "table" then
                for _, kid in ipairs(kids) do if S(function() return kid:IsShown() end) == true then walk(kid, depth - 1) end end
            end
        end
    end
    walk(f, 2)
    return { show = true, w = fw, h = fh, parts = #parts > 0 and parts or nil }
end
-- the game's colours for a damaged / broken piece (INVENTORY_ALERT_COLORS, what its figure and its tooltips use)
local function alertColours()
    local t = _G.INVENTORY_ALERT_COLORS
    if type(t) ~= "table" then return nil end
    local out = {}
    for i = 1, 2 do
        local c = t[i]
        if type(c) == "table" then
            out[i] = hex3(SN(function() return c.r end), SN(function() return c.g end), SN(function() return c.b end))
        end
    end
    return (out[1] or out[2]) and out or nil
end

-- bags-tip1 (E:/wow-build/bags_design_1113.md 1, fix 4): THE WORN TOOLTIPS ARE READ WHEN WORN GEAR CHANGES, not at every collect (every
-- bags collect re-read all 19, and collects follow every bag, money and action-bar change, many a second in a fight). A slot is read
-- again when its link (or a weapon's temporary enchant) changed, after PLAYER_EQUIPMENT_CHANGED / UPDATE_INVENTORY_DURABILITY /
-- PLAYER_LEVEL_UP (WC._bagsWornDirty), or WORN.FRESH s after the last full read (the timed text in them). A tooltip still
-- loading is never kept.
local WORN = { dirty = true, at = -1e9, FRESH = 60, tips = {} }
function WC._bagsWornDirty() WORN.dirty = true end
function WC._bagsWornReads() return WORN.reads or 0 end
local function wornSig(slot, link)
    if slot ~= 16 and slot ~= 17 then return link end
    local ok, hm, _, mc, mid, ho, _, oc, oid = CALL(function() return GetWeaponEnchantInfo() end)
    if not ok then return link end
    local function v(x) local t = S(function() return x end); return (type(t) == "number" or type(t) == "boolean") and tostring(t) or "-" end
    if slot == 16 then return tostring(link) .. "|" .. v(hm) .. v(mc) .. v(mid) end
    return tostring(link) .. "|" .. v(ho) .. v(oc) .. v(oid)
end
local function wornTip(slot, link, all)
    local sig = wornSig(slot, link)
    local w = WORN.tips[slot]
    if not all and w and w.sig == sig then return w.t end
    WORN.reads = (WORN.reads or 0) + 1
    local t = tipLines(slot)
    WORN.tips[slot] = (t and TIP.complete({ lines = t })) and { sig = sig, t = t } or nil
    return t
end

function WC.collectEquipped()
    local out = {}
    local all = WORN.dirty or (GetTime() - WORN.at) > WORN.FRESH
    if all then WORN.dirty = false; WORN.at = GetTime() end
    for slot = 1, 19 do
        local id = SN(function() return GetInventoryItemID("player", slot) end)
        local row = { slot = slot, name = SLOTS[slot] }
        if id then
            row.id = id
            row.icon = SN(function() return GetInventoryItemTexture("player", slot) end)
            row.quality = SN(function() return GetInventoryItemQuality("player", slot) end)
            local okd, cur, max = WC.CALL(function() return GetInventoryItemDurability(slot) end)
            if okd and type(cur) == "number" and type(max) == "number" and max > 0 then
                row.dur = math.floor(cur / max * 100)
            end
            -- the GAME's own verdict on the piece (f-durability): 1 = damaged (yellow), 2 = broken (red), what its warning
            -- figure and its paper doll colour by; nil when all is well
            local alert = SN(function() return GetInventoryAlertStatus(slot) end)
            if alert and alert > 0 then row.alert = alert end
            local okn, nm = WC.CALL(function() return C_Item.GetItemNameByID(id) end)
            if okn then row.item = S(function() return nm end) end
            local link = S(function() return GetInventoryItemLink("player", slot) end)
            row.ilvl = ilvlOf(link)
            row.stats = statsOf(link)
            row.tip = wornTip(slot, link, all)
            -- bags-gear1: its equip location (a two-hander in the main hand: the Character room's "Bags full" for an off-hand piece)
            local okL, _, _, _, wloc = CALL(function() return C_Item.GetItemInfoInstant(id) end)
            wloc = okL and S(function() return wloc end) or nil
            if type(wloc) == "string" and wloc ~= "" and wloc:byte(1) > 3 then row.equip = wloc end
            -- an on-use ability (a trinket, say), for the Character room's card and Bind (character session's
            -- request, 2026-09-28): the same test the bag rows use, plus its spell. use = true like a bag row.
            local okS, spName, spID = WC.CALL(function() return C_Item.GetItemSpell(id) end)
            if okS then
                local nm = S(function() return spName end)
                if type(nm) == "string" and nm ~= "" and nm:byte(1) > 3 then
                    row.use = true; row.useName = nm
                    row.useSpell = SN(function() return spID end)
                end
            end
        end
        out[#out + 1] = row
    end
    -- CLASS-ONLY parts of the doll (user ruling 2026-09-28 22:15 "factor in class exclusive stuff"; the character
    -- session's request, with the game's own calls): the AMMO slot is inventory slot 0 with a count (hunters),
    -- and three flags decide the doll's bottom row as the game's paper doll does. Relic classes show the
    -- relic art in the ranged slot, and the ammo slot hides.
    local ammo
    local aid = SN(function() return GetInventoryItemID("player", 0) end)
    if aid then
        ammo = { id = aid, icon = SN(function() return GetInventoryItemTexture("player", 0) end),
                 quality = SN(function() return GetInventoryItemQuality("player", 0) end),
                 count = SN(function() return GetInventoryItemCount("player", 0) end) }
        local okn, nm = WC.CALL(function() return C_Item.GetItemNameByID(aid) end)
        if okn then ammo.item = S(function() return nm end) end
        -- bags-vslots1: its stack size (200 arrows in Classic), what one quiver / pouch slot holds - the vendor's "ammo / space"
        local st = SN(function() return C_Item.GetItemMaxStackSizeByID(aid) end)
        if not st then
            local okI, _, _, _, _, _, _, _, stk = CALL(function() return C_Item.GetItemInfo(aid) end)
            if not okI then okI, _, _, _, _, _, _, _, stk = CALL(function() return GetItemInfo(aid) end) end
            if okI then st = SN(function() return stk end) end
        end
        if st and st > 0 then ammo.stack = st end
    end
    local function flag(fn)
        local ok, v = WC.CALL(fn)
        if not ok then return nil end
        v = S(function() return v end)
        if v == true or v == false then return v end
        return nil
    end
    return {
        slots = out,
        ilvl = SN(function() return GetAverageItemLevel() end),
        ammo = ammo,
        rangedShown = flag(function() return C_PaperDollInfo.IsRangedSlotShown() end),
        ammoNeeded = flag(function() return C_PaperDollInfo.AmmoNeeded() end),
        relic = flag(function() return UnitHasRelicSlot("player") end),
        durw = durWarning(),
        alertc = alertColours(),
        -- bags-act1: the class can wear a one-hander in the off hand too (the app's compare: an empty off hand is a slot then)
        dw = ACT.dualWield() or nil,
    }
end
