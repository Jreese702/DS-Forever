-- i172 (feat/ah-favorites, the user 2026-11-19: "I should be able to see my favorites list to star in the AH, and also be able to set
-- favorites on things"): Favorites under Reset (C_AuctionHouse.SearchForFavorites), the star on every result and on the item opened
-- (C_AuctionHouse.SetFavoriteItem(itemKey, not IsFavoriteItem)), RT on the controller, a finger's row through the iqty cell, the two
-- cells kept on every tab, and the pad rows' use at the auction house.
local N, ACT, ON, build, AH, padPress = __N, __ACT, __ON, __build, __AH, __padPress
local out = {}
local function chk(n, c, got) out[#out + 1] = (c and "PASS " or "FAIL ") .. n .. ((not c and got ~= nil) and (" -> " .. tostring(got)) or "") end

-- the pad rows as TapPad keeps them: what each build asks for, per row
local asked = {}
WowCompanion.pad.retain = function(ns, present) local n = 0; for _ in pairs(present) do n = n + 1 end; asked[ns] = n end

local FAV = {}                       -- itemID -> true: the game's favourites
local calls = {}
local function keyOf(id) return { itemID = id, itemLevel = 0, itemSuffix = 0 } end
local BROWSE = { 101, 102, 103 }     -- what SendBrowseQuery finds
local shown = {}                     -- the browse results the game holds now
local AHS = { ready = true, max = false, avail = true }
local top = {}
for i = 1, 17 do top[i] = { name = "C" .. i, subCategories = {} } end
for j = 1, 9 do top[2].subCategories[j] = { name = "S" .. j, subCategories = {} } end
for j = 1, 12 do top[2].subCategories[1].subCategories[j] = { name = "T" .. j } end
AuctionCategories = top
local function results(ids) local t = {}; for i, id in ipairs(ids) do t[i] = { itemKey = keyOf(id), minPrice = 100 * i, totalQuantity = i } end; return t end
C_AuctionHouse = setmetatable({
    IsThrottledMessageSystemReady = function() return AHS.ready end,
    SendBrowseQuery = function(q) calls[#calls + 1] = "browse"; shown = results(BROWSE) end,
    SearchForFavorites = function(sorts)
        calls[#calls + 1] = "favorites " .. tostring(type(sorts) == "table" and #sorts)
        local ids = {}; for id in pairs(FAV) do ids[#ids + 1] = id end; table.sort(ids); shown = results(ids)
    end,
    GetBrowseResults = function() return shown end,
    GetItemKeyInfo = function(k) return { itemName = "Item " .. k.itemID, iconFileID = 1, quality = 2, isCommodity = k.itemID == 103 } end,
    HasFavorites = function() return next(FAV) ~= nil end,
    HasMaxFavorites = function() return AHS.max end,
    FavoritesAreAvailable = function() return AHS.avail end,
    IsFavoriteItem = function(k) return FAV[k.itemID] == true end,
    SetFavoriteItem = function(k, on) calls[#calls + 1] = "set " .. k.itemID .. " " .. tostring(on); FAV[k.itemID] = on or nil end,
    SendSearchQuery = function(k) calls[#calls + 1] = "search " .. k.itemID end,
    GetNumCommoditySearchResults = function() return 0 end, GetNumItemSearchResults = function() return 0 end,
    GetNumOwnedAuctions = function() return 0 end,
    GetFilterGroups = function() return {} end,
}, { __index = function() return function() end end })
Enum = { AuctionHouseSortOrder = { Price = 0, Name = 1, Level = 2, Buyout = 3, Bid = 4 }, AuctionHouseFilter = {} }
local function last() return calls[#calls] end

N.mode, N.holding, N.stale, N.fight, N.frameSeen = true, true, nil, nil, true
ON.AUCTION_HOUSE_SHOW()
local d = build()
local au = d.auction
chk("the star's cell (isell) and the Favorites cell (ipick) are published", au.act.fav ~= nil and au.act.favs ~= nil)
chk("no favourites yet: hasFav is off", au.hasFav == nil)
chk("Favorites stands under Reset", d.nav and N.zones and (function()
    for _, z in ipairs(N.zones) do if z.name == "ahcats" then return z.items[1].id == "ahreset" and z.items[2].id == "ahcat fav" end end
end)())
chk("the cursor starts on the first category, not Reset nor Favorites", N.cur == "ahcat 1", N.cur)
local ok, why = ACT.ahcat("fav")
chk("Favorites with none: refused, nothing asked", ok == false and why == "No favorites" and #calls == 0, why)

-- browse a category; star its second result by the controller's RT
ACT.ahcat("1")
d = build()
chk("a category's results, none starred", #d.auction.results == 3 and not d.auction.results[2].fav)
chk("RT is kept from the game on Browse", N.npcExtra.PADRTRIGGER == true)
N.cur = "ahres 2"
padPress("PADRTRIGGER", true, "frame"); padPress("PADRTRIGGER", false, "frame")
chk("RT on a row: SetFavoriteItem(its key, true)", last() == "set 102 true", last())
d = build()
chk("the row shows its star", d.auction.results[2].fav == true and not d.auction.results[1].fav)
chk("hasFav on", d.auction.hasFav == true)
N.busy = nil
padPress("PADRTRIGGER", true, "frame"); padPress("PADRTRIGGER", false, "frame")
chk("RT again: the star off", last() == "set 102 false" and FAV[102] == nil, last())
N.cur = "ahcat 1"
local n0 = #calls
padPress("PADRTRIGGER", true, "frame"); padPress("PADRTRIGGER", false, "frame")
chk("RT on a category: nothing", #calls == n0)

-- a finger: the iqty cell names the row (k-1 = row k), then the star's cell
ACT.qty("3"); ACT.ahfav()
chk("a finger on row 3's star: qty 3 then ahfav", last() == "set 103 true", last())
ACT.qty("1"); ACT.ahfav()
chk("row 1 too (the count cell is always sent)", last() == "set 101 true", last())
n0 = #calls
GAME.t = 1000; ACT.qty("2"); GAME.t = 1010
ok = ACT.ahfav()
chk("a row number too old: never used (and nothing open to star)", ok == false and #calls == n0)
GAME.t = nil
chk("a row number is used once", AH.pick == nil)

-- the most favourites, and favourites off on this realm
AHS.max = true
ok, why = ACT.ahfav("2")
chk("full: a new star refused", ok == false and why == "Favorites full" and FAV[102] == nil, why)
ok = ACT.ahfav("1")
chk("full: a star can still come off", ok == true and FAV[101] == nil)
AHS.max = false
AHS.avail = false
ok, why = ACT.ahfav("2")
chk("unavailable: refused", ok == false and why == "Favorites unavailable", why)
AHS.avail = true

-- the item opened: RT and the header's star use its key
ACT.ahres("2")
d = build()
chk("the item opened carries its star (off)", d.auction.item and not d.auction.item.fav)
N.busy = nil
padPress("PADRTRIGGER", true, "frame"); padPress("PADRTRIGGER", false, "frame")
chk("RT in the item: SetFavoriteItem(the item's key, true)", last() == "set 102 true", last())
d = build()
chk("the item's star on", d.auction.item.fav == true)
ACT.ahfav()
chk("the header's star (no row number): the item opened", last() == "set 102 false", last())
ACT.qty("1")
ok = ACT.ahfav()
chk("a row number while an item is open: refused (the app thought it was the list)", ok == false and FAV[101] == nil)
ACT.ahfav("key")
ACT.ahback(); build()

-- THE FAVORITES LIST: the game's SearchForFavorites with the browse's sorts, its results as the browse results
FAV = { [103] = true, [101] = true }
ACT.ahcat("fav")
chk("Favorites: SearchForFavorites with the browse sorts", last() == "favorites 2", last())
d = build()
chk("the list is the favourites, starred", #d.auction.results == 2 and d.auction.results[1].id == 101 and d.auction.results[1].fav
    and d.auction.results[2].id == 103 and d.auction.fav == true, #d.auction.results)
chk("the cursor on Favorites, selected", N.ahSel == "ahcat fav" and N.cur == "ahcat fav", N.cur)
ACT.ahsort("browse", "Name")
chk("a header sorts the favourites (not a browse)", last() == "favorites 2", last())
-- unstar one in the list: it stays (the game's list keeps the row), its star off
ACT.ahfav("1")
d = build()
chk("unstarred in the list: still there, star off", last() == "set 101 false" and #d.auction.results == 2 and not d.auction.results[1].fav)
-- open one, B back: still the favourites
ACT.ahres("2"); build()
ACT.ahback()
d = build()
chk("B from an item: back on the favourites", AH.fav == true and d.auction.fav == true and N.cur == "ahres 2", N.cur)
padPress("PAD2", true, "frame"); padPress("PAD2", false, "frame")
d = build()
chk("B again: the favourites close, the window stays", AH.fav == nil and AH.results == nil and d.auction ~= nil)
ACT.ahcat("fav")
ACT.ahcat("fav")
chk("Favorites pressed again: folded", AH.fav == nil and AH.results == nil)
ACT.ahcat("fav"); ACT.ahcat("2")
chk("a category after Favorites: a plain browse", AH.fav == nil and last() == "browse", last())
ACT.ahcat("fav"); ACT.ahreset()
chk("Reset clears Favorites", AH.fav == nil)
ACT.ahcat("fav"); ACT.ahsearch()
chk("Search clears Favorites", AH.fav == nil and last() == "browse", last())

-- the cells are kept on every tab (never freed into the pad's rest) and the rows' use stays in the 40 columns
ACT.ahcat("2.1")
d = build()
chk("Browse deep (17 + 9 + 12 categories): ipick within 40", asked.ipick <= 40, asked.ipick)
chk("Browse: iact within 40", asked.iact <= 40, asked.iact)
local favCol, favsCol = d.auction.act.fav, d.auction.act.favs
ACT.ahtab("sell"); d = build()
chk("Sell: both cells kept, same columns", d.auction.act.fav == favCol and d.auction.act.favs == favsCol)
chk("Sell: RT is not kept (the game's)", not N.npcExtra.PADRTRIGGER)
ACT.ahtab("owned"); d = build()
chk("Auctions: both cells kept", d.auction.act.fav == favCol and d.auction.act.favs == favsCol)
padPress("PADRTRIGGER", true, "frame"); padPress("PADRTRIGGER", false, "frame")
chk("RT on Auctions: nothing", last() == "browse", last())
ACT.ahtab("browse"); d = build()
chk("Browse again: RT kept", N.npcExtra.PADRTRIGGER == true)
ON.AUCTION_HOUSE_CLOSED()
ON.AUCTION_HOUSE_SHOW()
chk("a new window: no Favorites shown", AH.fav == nil)
chk("the version is i172 or later", tonumber(WowCompanion.interactIV:match("^i(%d+)$")) >= 172, WowCompanion.interactIV)
return table.concat(out, "\n")
