-- i155 (issue #88, 1.0.21: "Unable to post items on auctionhouse. button is gray on ever item i try to post"): the auction document
-- carries the Sell form's state IN NUMBERS (the report's summary keeps numbers only, one level deep): tn the tab, sb the sellable
-- stacks, sw what stops Post (-1 no item, 0 nothing, AH.sellWhy's number), sst / sp / sq / sbo / sauto. AH.sellWhy's words unchanged.
local N, ACT, ON, build, AH = __N, __ACT, __ON, __build, __AH
local out = {}
local function chk(n, c, got) out[#out + 1] = (c and "PASS " or "FAIL ") .. n .. ((not c and got ~= nil) and (" -> " .. tostring(got)) or "") end

GAME.money = 100000
GAME.bags = { { id = 2589, name = "Linen Cloth", n = 20 }, { id = 774, name = "Malachite", n = 1 } }
local AHS = { auto = true, ready = true }
AuctionCategories = { { name = "Weapons" } }          -- a real table (the stub's dummy never ends an ipairs)
ItemLocation = { CreateFromBagAndSlot = function(_, bag, s) return { bag = bag, s = s } end }
C_Item = {
    GetItemID = function(loc) local it = GAME.bags[loc.s]; return it and it.id end,
    DoesItemExist = function(loc) return GAME.bags[loc.s] ~= nil end,
    GetStackCount = function(loc) local it = GAME.bags[loc.s]; return it and it.n end,
    GetItemLink = function(loc) local it = GAME.bags[loc.s]; return it and ("|Hitem:" .. it.id .. "|h[" .. it.name .. "]|h") end,
    GetItemInfo = function() return "x", nil, nil, nil, nil, nil, nil, nil, nil, nil, 25 end,
    GetItemName = function(loc) local it = GAME.bags[loc.s]; return it and it.name end,
    GetItemIcon = function() return 134 end, GetItemQuality = function() return 1 end, GetCurrentItemLevel = function() return 5 end,
}
Enum = { ItemCommodityStatus = { Unknown = 0, Item = 1, Commodity = 2 }, AuctionHouseSortOrder = { Price = 0 } }
Constants = { AuctionConstants = { DEFAULT_AUCTION_PRICE_MULTIPLIER = 1.5 } }
C_AuctionHouse = {
    IsThrottledMessageSystemReady = function() return AHS.ready end,
    IsSellItemValid = function() return true end,
    GetItemKeyFromItem = function(loc) return { itemID = GAME.bags[loc.s].id, itemLevel = 5 } end,
    GetItemCommodityStatus = function(loc) return GAME.bags[loc.s].id == 2589 and 2 or 1 end,
    GetAvailablePostCount = function(loc) return GAME.bags[loc.s].n end,
    ShouldAutoPopulatePrice = function() return AHS.auto end,
    SupportsCopperValues = function() return false end,
    CalculateCommodityDeposit = function() return 60 end, CalculateItemDeposit = function() return 60 end,
    SendSearchQuery = function() end,
    GetNumCommoditySearchResults = function() return 0 end, GetNumItemSearchResults = function() return 0 end,
    GetItemKeyInfo = function() return nil end,
    GetBrowseResults = function() return {} end, HasFullBrowseResults = function() return true end,
    GetNumOwnedAuctions = function() return 0 end,
}

ON.AUCTION_HOUSE_SHOW()
local a = build().auction
chk("the auction document", a ~= nil)
chk("Browse: tn = 1, no Sell numbers", a and a.tn == 1 and a.sw == nil and a.sb == nil, a and a.tn)
ACT.ahtab("sell")
a = build().auction
chk("Sell, no item: tn = 2, sw = -1, sb = 2", a and a.tn == 2 and a.sw == -1 and a.sb == 2, a and (tostring(a.tn) .. "/" .. tostring(a.sw) .. "/" .. tostring(a.sb)))
-- the game's auto price off: Post waits for a typed price (sw 5, sp 0, sauto 0); the words stay the game's own
AHS.auto = false
chk("the Malachite chosen", ACT.ahsitem(0, 2, 774) == true)
a = build().auction
chk("no price: sw = 5 (price), sp = 0, sauto = 0", a.sw == 5 and a.sp == 0 and a.sauto == 0, tostring(a.sw) .. "/" .. tostring(a.sp))
chk("the form's words unchanged (Type a price)", a.sell and a.sell.why == "Type a price", a.sell and a.sell.why)
chk("an item: Buyout only (sbo 1), quantity 1", a.sbo == 1 and a.sq == 1, tostring(a.sbo) .. "/" .. tostring(a.sq))
-- the price typed (as the keyboard's callback does), the game busy: sw = 4 (throttle)
AH.sell.unit, AH.sell.user = 5000, true
AHS.ready = false
a = build().auction
chk("price typed, the game's throttle: sw = 4, sp = 1", a.sw == 4 and a.sp == 1, tostring(a.sw) .. "/" .. tostring(a.sp))
AHS.ready = true
a = build().auction
chk("ready: sw = 0 (Post can go), no why", a.sw == 0 and a.sell.why == nil, tostring(a.sw))
-- not enough money for the deposit
GAME.money = 10
a = build().auction
chk("no money: sw = 2 (deposit)", a.sw == 2, tostring(a.sw))
GAME.money = 100000
-- the commodity with the game's auto price on: a price at once (sp 1), sw 0, the stack as the quantity
AHS.auto = true
chk("the Linen Cloth chosen", ACT.ahsitem(0, 1, 2589) == true)
a = build().auction
chk("commodity, auto price: sw = 0, sp = 1, sq = 20, sauto = 1", a.sw == 0 and a.sp == 1 and a.sq == 20 and a.sauto == 1,
    tostring(a.sw) .. "/" .. tostring(a.sp) .. "/" .. tostring(a.sq))
-- AH.sellWhy still hands back the words first (ACT.ahspost's refusal reads only those)
local w, n = AH.sellWhy({ loc = { s = 9 }, qty = 1 })
chk("sellWhy: the words, then the number", w == "Choose an item" and n == 1, tostring(w) .. "/" .. tostring(n))
-- the item gone from the bags: the form empties (sw back to -1)
GAME.bags[1] = nil
a = build().auction
chk("the chosen stack gone: sw = -1", a.sw == -1, tostring(a.sw))
ACT.ahtab("owned")
a = build().auction
chk("Auctions tab: tn = 3, no Sell numbers", a.tn == 3 and a.sw == nil, tostring(a.tn))
return table.concat(out, "\n")
