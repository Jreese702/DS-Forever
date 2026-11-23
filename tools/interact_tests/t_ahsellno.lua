-- i173 (issue 590: "Not all bag items in the AH Sell panel"): the items the game refuses at the auction house
-- (C_AuctionHouse.IsSellItemValid false) used to vanish from the Sell grid with no word. Now they ride in sell.no, greyed in the app with
-- the game's own reason for the slot (bound / quest / damaged, else "no"), never a cursor stop or a tap cell; the bag headers count them.
local N, ACT, ON, build, AH = __N, __ACT, __ON, __build, __AH
local out = {}
local function chk(n, c, got) out[#out + 1] = (c and "PASS " or "FAIL ") .. n .. ((not c and got ~= nil) and (" -> " .. tostring(got)) or "") end

GAME.money = 1000000
-- the backpack: two sellable, a bound one, a quest item, a damaged piece, one the game refuses for a reason it does not give;
-- bag 2: only a bound item (a bag with nothing sellable)
local BAGS = { [0] = { { id = 2589, name = "Linen Cloth", n = 20 }, { id = 6948, name = "Hearthstone", n = 1, bound = true },
                       { id = 774, name = "Malachite", n = 1 }, { id = 5000, name = "Quest Thing", n = 1, quest = true },
                       { id = 2488, name = "Worn Axe", n = 1, dur = { 3, 20 } }, { id = 9999, name = "Odd Thing", n = 1, refuse = true } },
               [2] = { { id = 6949, name = "Bound Ring", n = 1, bound = true } } }
C_Container = {
  GetContainerNumSlots = function(bag) return BAGS[bag] and 6 or 0 end,
  GetBagName = function(bag) return bag == 0 and "Backpack" or "Small Bag" end,
  GetContainerItemInfo = function(bag, s) local it = BAGS[bag] and BAGS[bag][s]; if it then
      return { itemID = it.id, iconFileID = 133, stackCount = it.n, quality = 1, isBound = it.bound or false,
               hyperlink = "|cff|Hitem:" .. it.id .. "|h[" .. it.name .. "]|h|r" } end end,
  GetContainerItemQuestInfo = function(bag, s) local it = BAGS[bag] and BAGS[bag][s]
      return { isQuestItem = (it and it.quest) or false, questID = nil, isActive = false } end,
  GetContainerItemDurability = function(bag, s) local it = BAGS[bag] and BAGS[bag][s]; if it and it.dur then return it.dur[1], it.dur[2] end end,
  GetContainerNumFreeSlots = function() return 0, 0 end,
}
AuctionCategories = { { name = "Weapons" } }
ItemLocation = { CreateFromBagAndSlot = function(_, bag, s) return { bag = bag, s = s } end }
local function at(loc) return BAGS[loc.bag] and BAGS[loc.bag][loc.s] end
C_Item = {
    GetItemID = function(loc) local it = at(loc); return it and it.id end,
    DoesItemExist = function(loc) return at(loc) ~= nil end,
    GetStackCount = function(loc) local it = at(loc); return it and it.n end,
    GetItemLink = function(loc) local it = at(loc); return it and ("|Hitem:" .. it.id .. "|h[" .. it.name .. "]|h") end,
    GetItemInfo = function() return "x", nil, nil, nil, nil, nil, nil, nil, nil, nil, 25 end,
    GetItemName = function(loc) local it = at(loc); return it and it.name end,
    GetItemIcon = function() return 134 end, GetItemQuality = function() return 1 end, GetCurrentItemLevel = function() return 5 end,
    GetItemInfoInstant = function(x) return tonumber(tostring(x):match("item:(%d+)")) end,
    RequestLoadItemDataByID = function() end,
}
Enum = { ItemCommodityStatus = { Unknown = 0, Item = 1, Commodity = 2 }, AuctionHouseSortOrder = { Price = 0 } }
Constants = { AuctionConstants = { DEFAULT_AUCTION_PRICE_MULTIPLIER = 1.5 } }
local VALID = {}
C_AuctionHouse = {
    IsThrottledMessageSystemReady = function() return true end,
    -- the game's own rule: bound, quest, damaged and the odd one refused
    IsSellItemValid = function(loc) local it = at(loc); VALID[#VALID + 1] = it and it.id
        return it ~= nil and not it.bound and not it.quest and not it.dur and not it.refuse end,
    GetItemKeyFromItem = function(loc) return { itemID = at(loc).id, itemLevel = 5 } end,
    GetItemCommodityStatus = function() return 2 end,
    GetAvailablePostCount = function(loc) return at(loc).n end,
    ShouldAutoPopulatePrice = function() return true end,
    SupportsCopperValues = function() return false end,
    CalculateCommodityDeposit = function() return 60 end, CalculateItemDeposit = function() return 60 end,
    SendSearchQuery = function() end, SendBrowseQuery = function() end,
    GetNumCommoditySearchResults = function() return 0 end, GetNumItemSearchResults = function() return 0 end,
    GetItemKeyInfo = function() return nil end,
    GetBrowseResults = function() return {} end, HasFullBrowseResults = function() return true end,
    GetNumOwnedAuctions = function() return 0 end,
}
C_Timer = { After = function() end, NewTicker = function() return { Cancel = function() end } end, NewTimer = function() return { Cancel = function() end } end }

N.mode, N.holding, N.stale, N.fight, N.frameSeen = true, true, nil, nil, true
ON.AUCTION_HOUSE_SHOW()
ACT.ahtab("sell")
local d = build()
local s = d.auction.sell
chk("i173: the version is i173 or later", tonumber(WowCompanion.interactIV:match("^i(%d+)$")) >= 173, WowCompanion.interactIV)
chk("the grid's items: only what the game takes (Linen, Malachite)", #s.bags == 2 and s.bags[1].id == 2589 and s.bags[2].id == 774, #s.bags)
local byId = {}
for _, b in ipairs(s.no or {}) do byId[b.id] = b end
chk("THE FIX: the refused items are listed, not dropped (5)", s.no ~= nil and #s.no == 5, s.no and #s.no)
chk("the Hearthstone: bound (the slot's isBound)", byId[6948] and byId[6948].no == "bound" and byId[6948].bag == 0 and byId[6948].s == 2,
    byId[6948] and byId[6948].no)
chk("the quest item: quest (GetContainerItemQuestInfo)", byId[5000] and byId[5000].no == "quest", byId[5000] and byId[5000].no)
chk("the worn axe: damaged (durability 3 of 20)", byId[2488] and byId[2488].no == "damaged", byId[2488] and byId[2488].no)
chk("a refusal with no reason the game names: no", byId[9999] and byId[9999].no == "no", byId[9999] and byId[9999].no)
chk("each carries its icon, stack and quality for the greyed tile", byId[6948].icon == 133 and byId[6948].n == 1 and byId[6948].q == 1)
chk("no refused item has a tap cell", byId[6948].slot == nil and byId[5000].slot == nil and byId[9999].slot == nil)
chk("the sellable ones still have theirs", s.bags[1].slot ~= nil and s.bags[2].slot ~= nil)
chk("the backpack's header: 2 sellable, 4 refused", s.groups[1].bag == 0 and s.groups[1].n == 2 and s.groups[1].x == 4,
    s.groups[1].n .. "/" .. tostring(s.groups[1].x))
chk("a bag with only a refused item still gets its header (after the backpack)", #s.groups == 2 and s.groups[2].bag == 2
    and s.groups[2].n == 0 and s.groups[2].x == 1, #s.groups)
chk("the report's auction line: sb 2, sx 5", d.auction.sb == 2 and d.auction.sx == 5, tostring(d.auction.sb) .. "/" .. tostring(d.auction.sx))
-- the cursor walks only the items the game takes: header, Linen, Malachite, the next header
local nv = d.nav
chk("the cursor starts on the first header", nv.cur == "ahsfold 0", nv.cur)
N.move("down"); chk("Down: the first sellable item", N.cur == "ahsitem 0 1 2589", N.cur)
N.move("right"); chk("Right: the next sellable one (the refused Hearthstone is no stop)", N.cur == "ahsitem 0 3 774", N.cur)
N.move("right"); chk("Right again: nothing further in the row", N.cur == "ahsitem 0 3 774", N.cur)
N.move("down"); chk("Down: the next bag's header", N.cur == "ahsfold 2", N.cur)
-- a refused item picked by its command (an old app): the game's refusal, as before
local ok = ACT.ahsitem("0", "2", "6948")
chk("picking a refused item is still refused (the game's rule)", not ok and AH.sell == nil, ok)
-- nothing refused: no `no` list, no x counts (the document as before)
BAGS[0] = { { id = 2589, name = "Linen Cloth", n = 20 } }; BAGS[2] = nil
d = build()
chk("nothing refused: no list, no x", d.auction.sell.no == nil and d.auction.sell.groups[1].x == nil and #d.auction.sell.groups == 1)
return table.concat(out, "\n")
