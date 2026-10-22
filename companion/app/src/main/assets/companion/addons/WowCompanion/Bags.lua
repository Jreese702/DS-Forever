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

local ADDON = ...
local WC = _G.WowCompanion
local S, SN, CALL = WC.S, WC.SN, WC.CALL
local BB = { binds = {}, buttons = {}, applied = {} }   -- Bind to a Button, the companion's own (its functions after bindableNow)

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
                -- the proof a tap landed (the secure action itself already ran)
                WC.emit({ type = "cap", bagsTap = { bag = bag, slot = col + 1,
                    combat = InCombatLockdown() and true or false } })
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
    pcall(WC.pad.op, "bagsort", nil, function()
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
-- Rows: plain (insecure) rows "bmv<bag>" (column = slot - 1) and "bmvq" (column = worn slot - 1),
-- registered LAST (a few seconds after the world loads) so they take the highest pad rows and
-- nobody else's row moves. Equipment changes are refused in combat; a pick expires after 10 s.
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

local function cursorBusy()
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

-- ONE DELETE for another window: Interact's bags-full loot screen (the user, 2026-10-01: "it asks me what I would like to
-- delete to make space"). The player picks the item AND confirms in that window; this call is the one game action.
-- Never automatic. The slot must still hold [itemID] ("moved"), not in combat, nothing on the cursor, a player bag only.
-- RARE (blue) and EPIC (purple): the user's answer 2026-10-01 for that screen, "Yes, with a second confirmation": refused
-- ("rare") unless the window asked its extra "Are you sure?" and passes [rareConfirmed] = true. Legendary and better (not
-- asked about) stay refused ("legendary"). The Bags room's own Destroy keeps refusing rare and better (its own ruling).
-- Returns true, or false + why.
function WC.bagsDestroy(bag, slot, itemID, rareConfirmed)
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
    C_Timer.After(0.6, function()
        local still = bagItemID(bag, slot)
        WC.emit({ type = "cap", bagsDestroy = { confirm = (still ~= id), at = to, id = id } })
    end)
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
    local to = { inv = slot }
    if p.kind ~= "bag" then mvReply({ ok = false, why = "unsupported", to = to }); return end
    if InCombatLockdown() then mvReply({ ok = false, why = "combat", to = to }); fightRefused(); return end
    if cursorBusy() then mvReply({ ok = false, why = "cursor", to = to }); return end
    if bagItemID(p.bag, p.slot) ~= p.id then mvReply({ ok = false, why = "moved", to = to }); return end
    pickupBag(p.bag, p.slot)
    CALL(function() return EquipCursorItem(slot) end)
    local clean = settle()
    movedAt = GetTime()
    mvReply({ ok = clean, why = (not clean) and "refused" or nil,
              from = { kind = "bag", bag = p.bag, slot = p.slot }, to = to })
end

local mvRows = {}
function WC.bagsMoveRows(bagList)
    if not (WC.pad and WC.pad.op) or not WC._bagsMoveReady then return end
    for _, e in ipairs(bagList) do
        local bag = e.bag
        if not mvRows[bag] then
            mvRows[bag] = true
            -- a namespace makes it a 40-column row; the key it carries is not used, only the column
            pcall(WC.pad.op, "bmv" .. bag, "bmv" .. bag, function(_, col)
                -- right after a "cursor" tap (bmvq col 36) this tap only moves the controller's cursor here
                if WC._bagsCursorTap and WC._bagsCursorTap(bag, (col or 0) + 1) then return end
                onBagCell(bag, (col or 0) + 1)
            end)
        end
    end
    if not mvRows.q then
        mvRows.q = true
        -- columns 0..18 = the worn slots; 19 and up = the Bags room by touch (controller mode, below)
        pcall(WC.pad.op, "bmvq", "bmvq", function(_, col)
            col = col or 0
            if col >= 19 then if WC._bagsTouch then WC._bagsTouch(col) end; return end
            -- right after a "target" tap (col 26) a column 1..5 names the bag slot for the bag in the hand (EQ)
            if WC._bagsTargetTap and WC._bagsTargetTap(col) then return end
            if WC._bagsShutTap and WC._bagsShutTap(col) then return end
            onInvCell(col + 1)
        end)
    end
end

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
                local ok, t = pcall(WC.tipOf, key)
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
    if WC._bagsTipForget then WC._bagsTipForget() end      -- an item may have changed under the cursor: read its tooltip again
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
                    -- a quest item or a quest starter, as the game marks it in this slot (sent only when true)
                    local okQ, qi = CALL(function() return C_Container.GetContainerItemQuestInfo(bag, slot) end)
                    if okQ and type(qi) == "table" then
                        if flag(qi, "isQuestItem") == true or type(field(qi, "questID")) == "number" then row.quest = true end
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

    local here = ctx
    if here == "mail" and not mailSendShown() then here = nil end
    WC.bagsPadRows(bags)
    WC.bagsMoveRows(bags)
    WC._bagsLayout = bags               -- the controller's D-pad walks these, in this order
    return {
        items = items, free = free, total = total, bags = bags, money = SN(GetMoney),
        freeGeneral = freeGeneral,          -- free slots in bags that take anything (where loot can go)
        -- the game's own open / close of the bags (backpack row cols 39 / 38) can be tapped now
        gameDoors = WC._bagsDoorsLive and WC._bagsDoorsLive() or false,
        -- the old item macros / binds from before the 2026-10-09 ruling, while any exist (BB.old; bmvq 25 cleans them up)
        wcbOld = BB.oldDoc,
        -- the pin picker's tooltips, only while it is open (bmvq 23 / 24)
        ptips = WC._bagsPinTips and WC._bagsPinTips(items) or nil,
        ctx = here,
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
    if event == "PLAYER_REGEN_ENABLED" and syncDoors then syncDoors() end
    -- GET_ITEM_INFO_RECEIVED fires for every item anything asks about (chat links, tooltips,
    -- other addons). Only a name WE are waiting for is worth a re-collect. (pcall: the arg is
    -- used as a table key, and nothing has measured whether an event arg can be secret.)
    if event == "GET_ITEM_INFO_RECEIVED" then
        local ok, hit = pcall(function() return pending[a1] end)
        if not (ok and hit) then return end
    elseif CTX_OPEN[event] then
        ctx = CTX_OPEN[event]
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
    end
    sendSoon(event)
end)

-- the drag rows go on LAST, a few seconds after the world loads, so they take the highest pad rows
-- and no other room's row moves (the chat session's rule for late rows)
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
local BIND_READY = true
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

-- a binding command as the player reads it: the game's own BINDING_NAME_<command> text ("Action
-- Button 7"), or for one of our item binds the item's name. nil when the game has no name for it (a
-- CLICK binding, say): the overlay then shows the command itself.
local function bindingLabel(cmd)
    if type(cmd) ~= "string" or cmd == "" then return nil end
    local g = S(function() return _G["BINDING_NAME_" .. cmd] end)
    if type(g) == "string" and g ~= "" and g:byte(1) > 3 then return g end
    -- ours: our own secure button for that chord (CLICK WowCompanionBagBind_<chord>), or an old item macro (MACRO WCB <id>)
    local chord = cmd:match("^CLICK WowCompanionBagBind_(.+):LeftButton$")
    local id = (chord and BB.binds[chord]) or tonumber(cmd:match("^MACRO WCB (%d+)$") or "")
    if id then
        local okN, nm = CALL(function() return C_Item.GetItemNameByID(id) end)
        nm = okN and S(function() return nm end)
        if type(nm) == "string" and nm ~= "" and nm:byte(1) > 3 then return nm end
    end
    return cmd:match("^MACRO (.+)$")               -- another macro: its name
end

-- what each bindable button does now, for the overlay (read-only; sent only while it is open)
local function bindableNow()
    local out = {}
    for _, k in ipairs(HEARD_ORDER) do
        local a = S(function() return GetBindingAction(k, true) end)
        if type(a) ~= "string" or (a ~= "" and a:byte(1) <= 3) then a = nil end
        a = (a ~= "" and a) or nil
        out[#out + 1] = { k = k, a = a, n = a and bindingLabel(a) or nil }
    end
    return out
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
function BB.charKey()
    local n = S(function() return UnitName("player") end)
    local r = S(function() return GetRealmName() end)
    return (type(n) == "string" and n or "?") .. "-" .. (type(r) == "string" and r or "?")
end

function BB.load()
    local db = type(WowCompanionDB) == "table" and WowCompanionDB.bagBinds or nil
    local mine = type(db) == "table" and db[BB.charKey()] or nil
    BB.binds = {}
    if type(mine) == "table" then
        for k, id in pairs(mine) do
            if type(k) == "string" and tonumber(id) and not chordRefusal(k) then BB.binds[k] = tonumber(id)
            elseif type(k) == "string" and tonumber(id) and k:match("([^%-]+)$") == "PADFORWARD" then BB.dropped = true end
        end
    end
    if BB.dropped then BB.save() end                    -- Start's old bind gone from our record too (the pause wheel's now)
end

function BB.save()
    if type(WowCompanionDB) ~= "table" then WowCompanionDB = {} end
    if type(WowCompanionDB.bagBinds) ~= "table" then WowCompanionDB.bagBinds = {} end
    local out = {}
    for k, id in pairs(BB.binds) do out[k] = id end
    WowCompanionDB.bagBinds[BB.charKey()] = next(out) and out or nil
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
    for k, id in pairs(BB.binds) do
        local b = BB.button(k)
        b:SetAttribute("item", "item:" .. id)
        CALL(function() return SetOverrideBindingClick(BB.owner, false, k, b:GetName(), "LeftButton") end)
        BB.applied[k] = true
    end
    return true
end

function BB.set(chord, id) BB.binds[chord] = id; BB.save(); return BB.apply() end

function BB.ours(cmd) return type(cmd) == "string" and (cmd:sub(1, 26) == "CLICK WowCompanionBagBind_" or cmd:sub(1, 10) == "MACRO WCB ") end

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
    bf:RegisterEvent("PLAYER_REGEN_ENABLED")
    bf:SetScript("OnEvent", function(_, e)
        if e == "PLAYER_LOGIN" then BB.load(); BB.apply(); BB.oldDoc = BB.old()
        elseif BB.pending then BB.apply() end
    end)
end
local COLS = 10                   -- the app's grid (v3, the room's full width: 10 to a row): the D-pad walks the same columns
local MENU = { "split", "bind", "destroy" }    -- USER PICK 2026-09-28 17:05: the destructive one last
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
-- Interact's WC.tipOf("bag <bag> <slot> <itemID>") = the game's own bag-item tooltip, with "Currently Equipped" and the
-- stat changes for gear (cmp: the app shows it by itself, the user's ruling). Read once per cursor position / item (the cache empties on every bags collect), never
-- while an item is in the hand or a menu / dialog is open. An Interact without WC.tipOf: no tooltip lines (the app
-- falls back to the item's name).
-- (c) ONLY WHERE THE CURSOR RESTS (tracking 2026-10-22, the bags lag): reading the game's tooltip on every press cost each step;
-- now a new slot's tooltip is read TIP_REST after the cursor reached it, if it is still there, and sent then (the app shows the
-- item's own name and type meanwhile, and drops a tooltip whose key is not its slot's).
local TIP_REST = 0.12
local tipCache = { key = nil, val = nil }
local tipWant = nil
local function tipKey()
    if not (ctl.on and ctl.mode == "grid" and not ctl.pick) or type(WC.tipOf) ~= "function" then return nil end
    local id = bagItemID(ctl.bag, ctl.slot)
    if not id then return nil end
    return "bag " .. ctl.bag .. " " .. ctl.slot .. " " .. id
end
function WC._bagsTip()
    local key = tipKey()
    if not key then return nil end
    if tipCache.key == key then return tipCache.val end
    if tipWant ~= key then
        tipWant = key
        C_Timer.After(TIP_REST, function()
            if tipWant ~= key then return end           -- the cursor moved on: that slot's own timer reads it
            tipWant = nil
            if tipKey() ~= key then return end
            local ok, t = pcall(WC.tipOf, key)
            tipCache.key = key; tipCache.val = (ok and type(t) == "table") and t or nil
            sendCtlSoon()
        end)
    end
    return nil
end
function WC._bagsTipForget() tipCache.key = nil; tipCache.val = nil; tipWant = nil end

function WC._bagsCtlDoc()
    -- off, the last note still rides along: a refused take ("take-refused-npc", "-no way in", ...) or a
    -- lost controller must show in the log, not vanish behind a bare "off"
    -- off, the app still needs to know if the game's bags are open: then its OPEN door would do nothing, and it takes
    -- with col 29 instead
    if not ctl.on then return { on = false, note = ctl.note, seq = ctl.seq, open = ctl.open and true or false, shut = BAR.shutList() } end
    return {
        on = true, mode = ctl.mode, cur = { bag = ctl.bag, slot = ctl.slot }, open = ctl.open and true or false,
        pick = ctl.pick and { bag = ctl.pick.bag, slot = ctl.pick.slot } or nil,
        menu = MENU[ctl.menu], amount = ctl.amount, note = ctl.note, seq = ctl.seq,
        usable = (ctl.mode == "menu") and menuUsable() or nil, rare = ctl.rare,
        use = ctl.use, sid = ctl.sid, bindReady = BIND_READY, bindMods = BIND_MODS,
        -- a refused bind names what already uses the button (the game's binding command)
        takenBy = ctl.takenBy, takenByName = ctl.takenBy and bindingLabel(ctl.takenBy) or nil,
        bound = ctl.bound, chord = ctl.chord,
        keys = (ctl.mode == "bind") and ctl.keys or nil,
        tip = WC._bagsTip(),
        eq = EQ.doc and EQ.doc() or nil,
        shut = BAR.shutList(),
        bar = (ctl.zone == "bar" and ctl.mode == "grid") and BAR.items()[ctl.barAt or 1] or nil,
        act = ctl.act,
        barmenu = BAR.menuDoc(),
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
        elseif ctl.open and ctl.mode == "grid" and ctl.pick == nil then want = "PAD2" end
    end
    if want == (ctl.passing or false) or not (cap and cap.pass) then return end
    local keys = {}
    if want then for k in want:gmatch("%S+") do keys[#keys + 1] = k end end
    local ok, done = pcall(cap.pass, "bags", keys)
    if ok and done then ctl.passing = want end
end

local function report(note)
    syncPass()
    ctl.note = note; ctl.seq = ctl.seq + 1
    if note ~= "bind-taken" then ctl.takenBy = nil end
    if note == nil or not note:find("^bind") and note ~= "bound" then ctl.chord = nil end
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
    ctl.left = why                      -- the 1 s check takes it back only after a window, never after the player left
    report("left-" .. why)
end

local function toGrid(note) ctl.mode = "grid"; ctl.bound = nil; ctl.rare = nil; report(note) end

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
function BAR.act(it)
    if not it then return end
    if it.k == "bag" then
        report(BAR.toggle(it.id))
    elseif it.k == "sort" then
        if InCombatLockdown() then report("sort-combat"); fightRefused(); return end
        local ok = CALL(function() return C_Container.SortBags() end)
        if not ok then ok = CALL(function() return SortBags() end) end
        WC.emit({ type = "cap", bagsSort = { ok = ok and true or false } })
        report(ok and "sorted" or "sort-refused")
    elseif it.k == "search" then
        -- THE COMPANION'S OWN KEYBOARD types (the user, 2026-10-09: "Yes, controller keyboard": Chat's, drawn by the app, fed by the app's
        -- own reader): Bags stands still meanwhile (mode "search": every press is the keyboard's, none acts here), and Start is held
        -- from the game (the keyboard's Search key; the game's Start would open its menu). Over when the app says so (bmvq 27 + col 17),
        -- the room is left, or after BAR.SEARCH_TTL
        ctl.mode = "search"; ctl.searchAt = GetTime()
        local cap = capture()
        if cap and type(cap.extra) == "function" and (tonumber(cap.version) or 1) >= 4 then pcall(cap.extra, "bags", { "PADFORWARD" }) end
        ctl.act = { k = "search", seq = ctl.seq + 1 }
        report("search")
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

function EQ.list(eq)
    local out = {}
    for _, t in ipairs(eq.slots or EQ.TARGETS) do out[#out + 1] = EQ.target(t, eq) end
    return out
end

-- where the cursor starts: an empty slot it can go in, else the smallest bag it can replace, else the first
function EQ.start(list)
    local best, bestN
    for i, r in ipairs(list) do
        if r.ok and not r.id then return i end
        if r.ok and (not bestN or (r.slots or 0) < bestN) then best, bestN = i, r.slots or 0 end
    end
    return best or 1
end

function EQ.doc()
    local eq = ctl.eq
    if not eq or (ctl.mode ~= "equip" and ctl.mode ~= "equipping" and ctl.mode ~= "bindask") then return nil end
    return { bag = eq.bag, slot = eq.slot, id = eq.id, size = eq.size, at = ctl.eqAt, to = ctl.eqTarget,
             kind = eq.kind, fam = eq.fam, move = eq.move, from = eq.fromBag, icon = eq.icon,
             targets = (ctl.mode == "equip") and EQ.list(eq) or nil }
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
    if EQ.asking() then
        ctl.mode = "bindask"; report("equip-bind")             -- syncPass hands A / B to the game's question
        C_Timer.After(0.25, EQ.waitBind)
        return
    end
    EQ.done(settle() and "equipped" or "equip-refused")       -- still on the cursor = refused: it goes back
end

function EQ.waitBind()
    if not (ctl.on and ctl.mode == "bindask") then return end
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
-- used is drawn disabled and the focus skips it). Destroy always can: for rare and better it opens the
-- dialog that says why not.
menuUsable = function()
    local info = infoAt(ctl.bag, ctl.slot)
    local n = info and SN(function() return info.stackCount end) or 1
    local cap = capture()
    return { n > 1, BIND_READY and cap ~= nil and cap.chord ~= nil, true }
end
local function menuStep(dir)
    local ok = menuUsable()
    local i = ctl.menu
    for _ = 1, #MENU do
        i = (i - 1 + dir) % #MENU + 1
        if ok[i] then ctl.menu = i; return end
    end
end

-- BIND (user pick 2026-09-28 17:05, UI Art's B5 sheets "as drawn"): the sheet stays open until B.
-- A refused button says why and the next press is asked for again; a bound one shows "Bound to ..."
-- and is NOT asked again, so B (a plain press then) closes the sheet.
local armBind
local function onChord(chord, id)
    ctl.chord = chord
    local why = chordRefusal(chord)
    if not why then
        local taken = S(function() return GetBindingAction(chord, true) end)
        if type(taken) == "string" and taken ~= "" and taken:byte(1) > 3
           and not BB.ours(taken) then                  -- an earlier item bind of ours may be replaced
            ctl.takenBy = taken; why = "bind-taken"
        end
    end
    if not why then
        -- OURS ONLY (the user's ruling 2026-10-09): our secure item button + an override, never the player's macros or set (BB)
        if InCombatLockdown() then why = "bind-combat"
        elseif BB.set(chord, id) then
            ctl.bound = chord; ctl.keys = bindableNow(); report("bound")
            return
        else why = "bind-failed" end
    end
    report(why)
    if not armBind(id) then toGrid("bind-notready") end
end
armBind = function(id)
    local cap = capture()
    if not (cap and cap.chord) then return false end
    local okC, armed = pcall(cap.chord, "bags", function(chord) onChord(chord, id) end,
        function() toGrid("bind-cancelled") end)
    return okC and armed == true
end

-- THE GRID'S BUTTONS (the user's FINAL word 2026-09-30, after trying "b": "I want a to move, you for more and x to use or
-- equip", and "when its in my bags it should just always auto compare instead of having to hold a button"):
--   A = Move (into the hand; then A puts it down, B cancels)
--   X = the item's action (Use / Equip / ...: the app taps the slot's secure cell)
--   Y = More (Split Stack / Bind to a Button / Destroy)
--   gear: "Currently Equipped" shows by itself (the tooltip's cmp from WC.tipOf), no button
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
            -- a BAG: into the hand, and the cursor up to the bag bar to choose its slot (EQ, the user 2026-10-06)
            if EQ.slotsFor(id) then EQ.begin(id); return end
            -- USE is protected: the app taps this slot's secure cell once per new use.seq
            ctl.use = { bag = ctl.bag, slot = ctl.slot, seq = ctl.seq + 1 }; report("use"); return
        elseif Y then
            if ctl.pick then return end
            if not id then report("empty"); return end
            -- the menu opens on its first row that can be used (Split, unless the stack is one item)
            ctl.mode = "menu"; ctl.menu = #MENU; menuStep(1); report(nil); return
        end
    elseif ctl.mode == "menu" then
        if key == "PADDUP" then menuStep(-1); report(nil)
        elseif key == "PADDDOWN" then menuStep(1); report(nil)
        elseif B then toGrid(nil)
        elseif A then
            local what = MENU[ctl.menu]
            local info = infoAt(ctl.bag, ctl.slot)
            if not info then toGrid("empty"); return end
            if what == "destroy" then
                -- the game makes the player TYPE "DELETE" for rare and better: a controller cannot
                -- (so for those the dialog says it cannot be done here, with one Okay)
                local q = SN(function() return info.quality end) or 0
                ctl.armId = itemAt(ctl.bag, ctl.slot); ctl.mode = "destroy"
                if q >= 3 then ctl.rare = true; report("destroy-rare") else report(nil) end
            elseif what == "split" then
                local n = SN(function() return info.stackCount end) or 1
                if n < 2 then toGrid("split-single"); return end
                ctl.armId = itemAt(ctl.bag, ctl.slot); ctl.amount = 1; ctl.mode = "split"; report(nil)
            elseif what == "bind" then
                local cap = capture()
                if InCombatLockdown() then toGrid("bind-combat"); fightRefused(); return end
                if not BIND_READY or not (cap and cap.chord) then toGrid("bind-notready"); return end
                if not armBind(itemAt(ctl.bag, ctl.slot)) then toGrid("bind-notready"); return end
                ctl.chord = nil; ctl.bound = nil; ctl.keys = bindableNow(); ctl.mode = "bind"; report(nil)
            end
        end
    elseif ctl.mode == "bind" then
        -- only after a bind (nothing asked any more): B closes the sheet. While a button is being
        -- asked for, B goes to the capture's cancel instead and the others do nothing.
        if B then toGrid(nil) end
    elseif ctl.mode == "destroy" and ctl.rare then
        if A or B then toGrid(nil) end                 -- Okay: nothing was destroyed
    elseif ctl.mode == "destroy" then
        if B then toGrid("cancelled")
        elseif A then
            -- the confirmation named ONE item: anything else in that slot now is not destroyed
            if itemAt(ctl.bag, ctl.slot) ~= ctl.armId then toGrid("moved"); return end
            if cursorBusy() then toGrid("cursor"); return end
            pickupBag(ctl.bag, ctl.slot)
            CALL(function() return DeleteCursorItem() end)
            toGrid(settle() and "destroyed" or "refused")
        end
    elseif ctl.mode == "search" then
        -- the companion's keyboard has the presses (BAR.act "search"): nothing of Bags' acts meanwhile
        return
    elseif ctl.mode == "barmenu" then
        BAR.menuPress(key, A, B)
    elseif ctl.mode == "equip" then
        if key == "PADDLEFT" or key == "PADDRIGHT" then
            local n = #((ctl.eq and ctl.eq.slots) or EQ.TARGETS)
            ctl.eqAt = math.max(1, math.min(n, (ctl.eqAt or 1) + (key == "PADDRIGHT" and 1 or -1))); report(nil)
        elseif B then EQ.done("cancelled")
        elseif A then EQ.go() end
    elseif ctl.mode == "bindask" then
        -- A / B are the game's while it asks (passed, syncPass): one that reaches Bags means the pass is not in force. Say so in
        -- the log, and pass again (the next report runs syncPass afresh), so the player's next press answers the game
        if A or B then ctl.passing = false; report("bindask-" .. key) end
        -- "equipping": the game is working: nothing here
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

local function onLost(why)
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
local function npcWindowOpen()
    if ctx then return true end
    for _, n in ipairs(NPC_FRAMES) do
        local f = _G[n]
        local ok, shown = pcall(function() return f and f:IsShown() end)
        if ok and shown then return true end
    end
    return false
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
    if npcWindowOpen() then skip("take-skip-window"); return end
    -- ALL the bag windows, not only the shown ones: with separate bags the game opens them one by
    -- one, and the ones after the first must not flash up either
    local hide = {}
    for _, n in ipairs(BAG_FRAMES) do if _G[n] then hide[#hide + 1] = n end end
    -- a press only ever arrives after the hand-over: the first one ends a queued / pending wait
    ctl.spec = { press = function(key) if ctl.pending then ctl.pending = nil; report("taken") end; onPress(key) end,
                 lost = onLost, hide = hide }
    -- capture v9 (Interact i78): the game window's own close button for the generic / fight leave, and "the game's window
    -- opened" so the take pre-empts a room still holding (it gets lost("taken")). Only set where they are true, and only
    -- for a capture that knows them (an unknown or wrong field answers "bad spec")
    if (tonumber(cap.version) or 1) >= 9 then
        if _G.ContainerFrameCombinedBagsCloseButton then ctl.spec.leave = "ContainerFrameCombinedBagsCloseButton" end
        if viaWindow then ctl.spec.window = true end
    end
    local ok, got, why = pcall(cap.take, "bags", ctl.spec)
    if ok and got then
        ctl.on = true; ctl.mode = "grid"; ctl.pick = nil; ctl.left = nil
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
function WC._bagsDoorsLive()
    return doors.open ~= nil and doors.close ~= nil and not doors.stale and not InCombatLockdown()
end

local function onBagsShown() ctl.open = true; syncDoors(); tryTake(true) end
local function onBagsHidden()
    syncDoors()
    if #shownBagFrames() > 0 then return end        -- another bag window is still open
    if not ctl.open and not ctl.on then return end  -- B already closed them
    ctl.open = false
    if not ctl.on then return end       -- never held (skipped / refused): keep that reason in the log
    -- a bag swap's own churn (the game hides its window and shows it again): Bags keeps the controller (EQ.CHURN)
    if ctl.eqPutAt and GetTime() - ctl.eqPutAt < EQ.CHURN then syncPass(); return end
    -- the app's close door (the room left by touch / the bar): a non-B leave
    if doors.closingAt and GetTime() - doors.closingAt < 1 then doors.closingAt = nil; leave("room"); return end
    -- the game closed its windows itself: with B passed to it, that was the player's B
    leave(ctl.passing and "B" or "closed")
end

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
local DROP_HELD, FREE = 39, 28

-- an armed chord (the bind sheet asking) is the capture's; a touch Back must disarm it: taking again resets it
local function disarm()
    local cap = capture()
    if ctl.mode == "bind" and cap and ctl.spec then pcall(cap.take, "bags", ctl.spec) end
end

-- right after a "target" tap (col 26): bmvq column t = the bag slot t, the target of the bag in the hand (EQ). True = taken
-- (never a worn-slot action then, even outside the equip mode)
function WC._bagsTargetTap(col)
    if not ctl.targetAt or (GetTime() - ctl.targetAt) > CURSOR_TTL then return false end
    ctl.targetAt = nil
    if not (ctl.on and ctl.mode == "equip" and ctl.eq) then return true end
    for i, t in ipairs(ctl.eq.slots or EQ.TARGETS) do
        if t == col then ctl.eqAt = i; report(nil); return true end
    end
    return true
end

-- right after a "shut" tap (col 27): bmvq column b = bag b opened / closed in the room (BAR). True = taken
function WC._bagsShutTap(col)
    if not ctl.shutAt or (GetTime() - ctl.shutAt) > CURSOR_TTL then return false end
    ctl.shutAt = nil
    if col == BAR.SEARCH_DONE_COL then BAR.searchDone(); return true end      -- the app's keyboard closed
    for _, e in ipairs(layout()) do
        if e.bag == col then
            report(BAR.toggle(col))
            if ctl.on and ctl.zone ~= "bar" and BAR.isShut(ctl.bag) then BAR.leave() end   -- the cursor's bag closed: onto an open one
            return true
        end
    end
    return true
end

function WC._bagsCursorTap(bag, slot)
    if not ctl.cursorAt or (GetTime() - ctl.cursorAt) > CURSOR_TTL then return false end
    ctl.cursorAt = nil
    if not ctl.on then tryTake() end
    if not ctl.on then return true end            -- not taken (the reason is logged): nothing else happens
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
    if not ctl.pending then return true end
    local cap = capture()
    local ok, st = pcall(function() return cap and cap.state and cap.state() end)
    if ok and type(st) == "table" and st.holder == "bags" then ctl.pending = nil; report("taken"); return true end
    return false
end
WC._bagsHeldNow = heldNow

function WC._bagsTouch(col)
    if col == TOUCH_IN then
        tryTake(ctl.open)                   -- the game's bags already open: that is the window's take
    elseif col == TOUCH_OUT then
        if ctl.on then leave("room") end
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
        if not ctl.on then tryTake() end
        if not heldNow() then return end
        local key = VIRTUAL[col]
        if key == "PAD2" and ctl.mode == "bind" then disarm() end
        onPress(key)
    elseif MENU_ROWS[col] and ctl.mode == "barmenu" then
        if heldNow() and MENU_ROWS[col] <= #BAR.MENU then ctl.barMenu = MENU_ROWS[col]; BAR.menuChoose(MENU_ROWS[col]) end
    elseif MENU_ROWS[col] then
        if not (heldNow() and ctl.mode == "menu") then return end
        local ok = menuUsable()
        if not ok[MENU_ROWS[col]] then return end
        ctl.menu = MENU_ROWS[col]
        onPress("PAD1")
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

function WC.collectEquipped()
    local out = {}
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
            row.tip = tipLines(slot)
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
    }
end
