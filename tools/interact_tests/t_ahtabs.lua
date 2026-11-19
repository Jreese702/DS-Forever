-- i171 (fix/ah-tabs-lr, the user 2026-11-19 on 1502): "l and R in the auction house skips the sell tab"; the Sell confirm's second Post
-- "staying grayed out" and Reset "graying out" (the cell budget: iact's 40 columns, a freed one quarantined 30 s, run here with TapPad's
-- own allocator); the confirm's A posts and B keeps the form; the quantity is Y less / X more (the user's ruling).
local N, ACT, ON, build, AH, MAIL, padPress = __N, __ACT, __ON, __build, __AH, __MAIL, __padPress
local out = {}
local function chk(n, c, got) out[#out + 1] = (c and "PASS " or "FAIL ") .. n .. ((not c and got ~= nil) and (" -> " .. tostring(got)) or "") end

-- two bags: the backpack (4 slots) and bag 1 (4 slots)
GAME.money = 1000000
local BAGS = { [0] = { { id = 2589, name = "Linen Cloth", n = 20 }, { id = 774, name = "Malachite", n = 1 }, { id = 818, name = "Tigerseye", n = 1 },
                       { id = 1210, name = "Shadowgem", n = 1 } },
               [1] = { { id = 2592, name = "Wool Cloth", n = 5 }, { id = 2770, name = "Copper Ore", n = 3 } } }
C_Container = {
  GetContainerNumSlots = function(bag) return BAGS[bag] and 4 or 0 end,
  GetBagName = function(bag) return bag == 0 and "Backpack" or "Small Bag" end,
  GetContainerItemInfo = function(bag, s) local it = BAGS[bag] and BAGS[bag][s]; if it then
      return { itemID = it.id, iconFileID = 133, stackCount = it.n, quality = 1, hyperlink = "|cff|Hitem:" .. it.id .. "|h[" .. it.name .. "]|h|r" } end end,
  -- the mail tests: bag 0 general (GAME.free), bag 1 a herb bag (family 32, GAME.herbFree)
  GetContainerNumFreeSlots = function(bag) if bag == 0 then return GAME.free or 0, 0 elseif bag == 1 then return GAME.herbFree or 0, 32 end return 0, 0 end,
}
local AHS = { ready = true, sent = 0 }
AuctionCategories = { { name = "Weapons", subCategories = { { name = "Swords" } } }, { name = "Armor" } }
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
    RequestLoadItemDataByID = function(id) AHS.asked = id end,
}
Enum = { ItemCommodityStatus = { Unknown = 0, Item = 1, Commodity = 2 }, AuctionHouseSortOrder = { Price = 0 }, TooltipDataLineType = { SellPrice = 11 } }
Constants = { AuctionConstants = { DEFAULT_AUCTION_PRICE_MULTIPLIER = 1.5 } }
C_AuctionHouse = {
    IsThrottledMessageSystemReady = function() return AHS.ready end,
    IsSellItemValid = function() return true end,
    GetItemKeyFromItem = function(loc) return { itemID = at(loc).id, itemLevel = 5 } end,
    GetItemCommodityStatus = function(loc) return 2 end,
    GetAvailablePostCount = function(loc) return at(loc).n end,
    ShouldAutoPopulatePrice = function() return true end,
    SupportsCopperValues = function() return false end,
    CalculateCommodityDeposit = function() return 60 end, CalculateItemDeposit = function() return 60 end,
    SendSearchQuery = function() end,
    SendBrowseQuery = function() AHS.sent = AHS.sent + 1 end,
    GetNumCommoditySearchResults = function() return 0 end, GetNumItemSearchResults = function() return 0 end,
    GetItemKeyInfo = function() return nil end,
    GetBrowseResults = function() return {} end, HasFullBrowseResults = function() return true end,
    GetNumOwnedAuctions = function() return 0 end,
    PostCommodity = function(loc, dur, qty, unit) AHS.posted = { loc = loc, qty = qty }; return false end,
    GetItemSearchResultInfo = function(key, i) return { itemLink = "|cff|Hitem:" .. key.itemID .. ":0:0:0:0:0:-17|h[Blade of the Bear]|h|r", auctionID = 9 } end,
    GetItemKeyRequiredLevel = function() return 12 end,
}
local TIPS = {}
C_TooltipInfo = {
    GetHyperlink = function(l) TIPS.link = l; return { lines = { { leftText = "Blade of the Bear" }, { leftText = "+5 Strength" } } } end,
    GetItemKey = function(id, lvl, suf, req) TIPS.key = { id, lvl, suf, req }; return TIPS.keyData end,
    GetItemByID = function() return nil end,
}
local timers = {}
C_Timer = { After = function(s, f) timers[#timers + 1] = f end, NewTicker = function() return { Cancel = function() end } end,
            NewTimer = function() return { Cancel = function() end } end }
local function runTimers() local t = timers; timers = {}; for _, f in ipairs(t) do f() end end

-- TapPad's allocator, as it runs in the game: 40 columns, a freed column unused for 30 s
local P = { ns = {} }
local NOW = 1000
GetTime = function() return NOW end
WowCompanion.pad.slotFor = function(ns, key)
  local t = P.ns[ns]; if not t then t = { byKey = {}, byCol = {}, freedAt = {} }; P.ns[ns] = t end
  local c = t.byKey[key]; if c then return c end
  for col = 0, 39 do
    if t.byCol[col] == nil and (not t.freedAt[col] or NOW - t.freedAt[col] > 30) then t.byKey[key] = col; t.byCol[col] = key; t.freedAt[col] = nil; return col end
  end
  return nil
end
WowCompanion.pad.retain = function(ns, present)
  local t = P.ns[ns]; if not t then return end
  for key, col in pairs(t.byKey) do if not present[key] then t.byKey[key] = nil; t.byCol[col] = nil; t.freedAt[col] = NOW end end
end
local function press(k) padPress(k, true, "frame"); padPress(k, false, "frame") end
local function tick() NOW = NOW + 1; return build() end

N.mode, N.holding, N.stale, N.fight, N.frameSeen = true, true, nil, nil, true
ON.AUCTION_HOUSE_SHOW()
ACT.ahtab("browse")
local d = tick()
-- L / R: the game's order Buy, Sell, Auctions, one step each way, no wrap
chk("L / R are kept on the Buy tab", N.npcExtra.PADLSHOULDER == true and N.npcExtra.PADRSHOULDER == true)
press("PADRSHOULDER"); d = tick()
chk("R from Buy: Sell (it was skipped)", AH.tab == "sell", AH.tab)
press("PADRSHOULDER"); d = tick()
chk("R from Sell: Auctions", AH.tab == "owned", AH.tab)
press("PADRSHOULDER"); d = tick()
chk("R on the last tab: stays (no wrap)", AH.tab == "owned", AH.tab)
press("PADLSHOULDER"); d = tick()
chk("L from Auctions: Sell", AH.tab == "sell", AH.tab)
press("PADLSHOULDER"); d = tick()
chk("L from Sell: Buy", AH.tab == "browse", AH.tab)
press("PADLSHOULDER"); d = tick()
chk("L on the first tab: stays", AH.tab == "browse", AH.tab)
chk("tabStep: the game's order", AH.tabStep("browse", 1) == "sell" and AH.tabStep("sell", 1) == "owned" and AH.tabStep("owned", -1) == "sell"
    and AH.tabStep("browse", -1) == nil and AH.tabStep("owned", 1) == nil)

-- the Sell form with an item: Start = Post is kept, and L / R with it (the claim never swallows them)
press("PADRSHOULDER"); d = tick()
ACT.ahsitem("0", "1", "2589"); d = tick()
chk("Sell with an item: Start kept for Post", N.npcExtra.PADFORWARD == true)
chk("... and L / R kept beside it", N.npcExtra.PADLSHOULDER == true and N.npcExtra.PADRSHOULDER == true)
press("PADLSHOULDER"); d = tick()
chk("L with an item in the form: Buy", AH.tab == "browse", AH.tab)
press("PADRSHOULDER"); d = tick()
chk("R back: Sell", AH.tab == "sell", AH.tab)

local favCols
-- THE CELL BUDGET, the worst case seen: the search's filters open (its level range and Clear), then the tabs walked three times
AH.search.open = true
pcall(ACT.ahfilters, "on")
for round = 1, 3 do
  for _, t in ipairs({ "browse", "sell", "owned", "sell", "browse" }) do
    ACT.ahtab(t)
    if t == "sell" and not AH.sell then ACT.ahsitem("0", "1", "2589") end
    d = tick()
    chk("round " .. round .. " " .. t .. ": Reset has a cell", d.auction.act.reset ~= nil)
    chk("round " .. round .. " " .. t .. ": Post has a cell", d.auction.act.spost ~= nil)
    chk("round " .. round .. " " .. t .. ": the durations and Buyout only have cells", d.auction.act.sd_1 ~= nil and d.auction.act.sd_3 ~= nil and d.auction.act.sbo ~= nil)
    -- i172 merged on i171 (code 1504): the favourites' star (isell, after the form's 14) and Favorites (ipick, before the categories)
    chk("round " .. round .. " " .. t .. ": the star and Favorites have cells", d.auction.act.fav ~= nil and d.auction.act.favs ~= nil)
    favCols = favCols or { d.auction.act.fav, d.auction.act.favs }
    chk("round " .. round .. " " .. t .. ": their columns never moved", d.auction.act.fav == favCols[1] and d.auction.act.favs == favCols[2])
    local function used(ns) local n = 0; for _ in pairs((P.ns[ns] or { byKey = {} }).byKey) do n = n + 1 end; return n end
    chk("round " .. round .. " " .. t .. ": isell and ipick within 40", used("isell") <= 40 and used("ipick") <= 40, used("isell") .. "/" .. used("ipick"))
    -- the buttons the AH holds never collide: Select (the star) on Buy only, Start (Post) on Sell with an item only, L / R everywhere
    local x = N.npcExtra or {}
    chk("round " .. round .. " " .. t .. ": L / R held", x.PADLSHOULDER == true and x.PADRSHOULDER == true)
    if t == "browse" then chk("round " .. round .. " browse: Select held for the star, Start not", x.PADBACK == true and not x.PADFORWARD)
    elseif t == "sell" then chk("round " .. round .. " sell: Start held for Post, Select not", x.PADFORWARD == true and not x.PADBACK)
    else chk("round " .. round .. " owned: neither Select nor Start", not x.PADBACK and not x.PADFORWARD) end
  end
end
chk("Reset's column never moved", (function() local c = P.ns.iact.byKey["ahreset"]; return c ~= nil end)())
chk("the star is isell's 15th fixed cell (after the form's 14)", P.ns.isell.byKey["ahfav"] == 14, P.ns.isell.byKey["ahfav"])
chk("Favorites is ipick's first cell", P.ns.ipick.byKey["ahcat fav"] == 0, P.ns.ipick.byKey["ahcat fav"])
chk("the form's cells are on isell", P.ns.isell and P.ns.isell.byKey["ahspost"] ~= nil and not P.ns.iact.byKey["ahspost"])

-- the confirm: A on its Post posts (the cursor starts there), B keeps the form
pcall(ACT.ahfilters, "off"); AH.search.open = false
ACT.ahtab("sell"); if not AH.sell then ACT.ahsitem("0", "1", "2589") end
d = tick()
AHS.posted = nil
ACT.ahspost(); d = tick()
chk("armed: the confirm, the cursor on its Post", d.auction.sell.armed == true and d.nav.cur == "ahspost", d.nav.cur)
press("PAD2"); d = tick()
chk("B on the confirm: back to the form, nothing posted", not d.auction.sell.armed and AH.sell ~= nil and AHS.posted == nil)
ACT.ahspost(); d = tick()
press("PAD1")
chk("A on the confirm's Post: the game's PostCommodity", AHS.posted ~= nil)

-- a commodity's buy quantity: Y less, X more (the Sell form's way)
ACT.ahtab("browse")
C_AuctionHouse.GetItemKeyInfo = function() return { isCommodity = true } end
C_AuctionHouse.GetNumCommoditySearchResults = function() return 1 end
C_AuctionHouse.GetCommoditySearchResultInfo = function() return { quantity = 50 } end
AH.key = { itemID = 2589 }; AH.qty = 5
d = tick()
press("PAD3")
chk("X on a commodity: one more", AH.qty == 6, AH.qty)
press("PAD4")
chk("Y on a commodity: one less", AH.qty == 5, AH.qty)
chk("the version is i171 or later", tonumber(WowCompanion.interactIV:match("^i(%d+)$")) >= 171, WowCompanion.interactIV)
return table.concat(out, "\n")
