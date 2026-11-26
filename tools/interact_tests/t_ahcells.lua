-- i174 (the user, 2026-11-26: "When buying stuff on the AH for some reason the buttons are just staying gray", then "I was in dual
-- screen, its just how its been working. It still works but visually its gray"): an item's listings had their own ibuy cells beside
-- the browse results' (up to 60). TapPad has 40 columns a row and a freed column rests 30 s, so opening an item from a long result
-- list freed every result's cell into the rest and left the listings none: the app drew Buy Now (and the confirm's Buy Now) grey,
-- while the controller's A (the add-on's own press, no cell) still bought. The pad here is TapPad's own slotFor / retain, read out
-- of TapPad.lua (40 columns, the 30 s rest).
local N, ACT, ON, build, AH = __N, __ACT, __ON, __build, __AH
local out = {}
local function chk(n, c, got) out[#out + 1] = (c and "PASS " or "FAIL ") .. n .. ((not c and got ~= nil) and (" -> " .. tostring(got)) or "") end

-- TapPad's slot functions, from its own source
local src
for _, p in ipairs({ "../companion/app/src/main/assets/companion/addons/WowCompanion/TapPad.lua",
                     "companion/app/src/main/assets/companion/addons/WowCompanion/TapPad.lua" }) do
    local f = io.open(p, "r"); if f then src = f:read("a"); f:close(); break end
end
chk("TapPad.lua read", src ~= nil)
local cols = tonumber(src:match("\nlocal COLS = (%d+)"))
local rest = tonumber(src:match("\nlocal QUARANTINE = (%d+)"))
local body = src:match("(function pad%.slotFor.-\nend)\n") .. "\n" .. src:match("(function pad%.retain.-\nend)\n")
local realPad = {}
local loader = load("local pad, COLS, QUARANTINE = ...\n" .. body)
loader(realPad, cols, rest)
realPad.ns = {}
chk("TapPad: 40 columns, a 30 s rest", cols == 40 and rest == 30, tostring(cols) .. " " .. tostring(rest))
WowCompanion.pad.slotFor, WowCompanion.pad.retain = realPad.slotFor, realPad.retain
WowCompanion.pad.ns = realPad.ns
GAME.t = 1000

local function keyOf(id) return { itemID = id, itemLevel = 0, itemSuffix = 0 } end
local NRES, NROWS = 60, 25
local calls = {}
local shown = {}
local AHS = { ready = true }
AuctionCategories = { { name = "Weapons", subCategories = {} } }
C_AuctionHouse = setmetatable({
    IsThrottledMessageSystemReady = function() return AHS.ready end,
    SendBrowseQuery = function() local t = {}; for i = 1, NRES do t[i] = { itemKey = keyOf(500 + i), minPrice = 100 * i, totalQuantity = 1 } end; shown = t end,
    GetBrowseResults = function() return shown end,
    GetItemKeyInfo = function(k) return { itemName = "Sword " .. k.itemID, iconFileID = 1, quality = 2, isCommodity = false } end,
    HasFavorites = function() return false end,
    IsFavoriteItem = function() return false end,
    SendSearchQuery = function(k) calls[#calls + 1] = "search " .. k.itemID end,
    GetNumCommoditySearchResults = function() return 0 end,
    GetNumItemSearchResults = function() return NROWS end,
    GetItemSearchResultInfo = function(k, i)
        if i > NROWS then return nil end
        return { auctionID = 9000 + i, buyoutAmount = 1000 * i, bidAmount = 0, quantity = 1, containsOwnerItem = (i == 2), timeLeft = 2,
                 itemLink = "|cff|Hitem:" .. k.itemID .. "|h[x]|h|r" }
    end,
    PlaceBid = function(id, price) calls[#calls + 1] = "bid " .. id .. " " .. price end,
    GetNumOwnedAuctions = function() return 0 end,
    GetFilterGroups = function() return {} end,
}, { __index = function() return function() end end })
Enum = { AuctionHouseSortOrder = { Price = 0, Name = 1, Level = 2, Buyout = 3, Bid = 4 }, AuctionHouseFilter = {} }
local function last() return calls[#calls] end
local function list() if ACT.ahlist then return ACT.ahlist() end; return false, "no ahlist" end

N.mode, N.holding, N.stale, N.fight, N.frameSeen = true, true, nil, nil, true
ON.AUCTION_HOUSE_SHOW()
ACT.ahcat("1")
local d = build()
chk("60 results shown", #d.auction.results == NRES, #d.auction.results)
local resCol = d.auction.results[1].slot
chk("the first result has its cell", resCol ~= nil)

-- open the 5th result (an item, 25 listings), at once: the results' cells were just in use
ACT.ahres("5")
GAME.t = 1001
d = build()
local it = d.auction.item
chk("the item is open with its listings", it and #it.rows == NROWS, it and #it.rows)
-- every listing a finger can buy: a cell to press. Either the row's own cell, or the AH's one listing cell (the row's number by iqty)
local function canTap(r) return r.slot ~= nil or (d.auction.act["list"] ~= nil and r.i <= 40) end
local tappable = 0
for _, r in ipairs(it.rows) do if canTap(r) then tappable = tappable + 1 end end
chk("every listing can be pressed by a finger at once (the strip's Buy Now is not grey)", tappable == NROWS, tappable)
chk("the listing cell is on iact (always asked while the AH is open)", d.auction.act.list ~= nil and CELLS == CELLS)

-- a finger buys listing 4: the iqty cell names it (k-1 = row k), the listing cell arms it; the same again (the confirm's Buy Now) buys
local n0 = #calls
ACT.qty("4"); local ok1 = list()
chk("press 1 (row 4 by number): armed, nothing bought", ok1 == true and #calls == n0 and AH.buy and AH.buy.kind == "item" and AH.buy.row == 4,
    AH.buy and AH.buy.row)
d = build()
chk("the document says armed on row 4", d.auction.buy and d.auction.buy.armed == true and d.auction.buy.row == 4)
ACT.qty("4"); local ok2 = list()
chk("press 2 (the confirm's Buy Now on row 4): the game's PlaceBid", ok2 == true and last() == "bid 9004 4000", last())
-- the row number is used once, and an old one is never used
n0 = #calls
local ok3, why3 = list()
chk("no row number: refused, nothing armed or bought", ok3 == false and AH.buy == nil and #calls == n0, why3)
GAME.t = 1002; ACT.qty("6"); GAME.t = 1010
local ok4 = list()
chk("a row number too old: refused", ok4 == false and AH.buy == nil, tostring(ok4))
-- the game's own refusals stay (yours, no buyout)
GAME.t = 1011; ACT.qty("2"); local ok5, why5 = list()
chk("your own listing: refused", ok5 == false and why5 == "that one is yours", why5)
-- an armed row, then a press with another row's number arms that one (never buys it)
ACT.qty("3"); list()
n0 = #calls
ACT.qty("5"); list()
chk("another row's number re-arms, never buys", #calls == n0 and AH.buy and AH.buy.row == 5)

-- back to the results at once: they still have their cells (they were kept while the item was open)
if AH.buy then ACT.ahback() end; ACT.ahback()
GAME.t = 1012
d = build()
chk("back on the results", d.auction.item == nil and d.auction.results ~= nil)
chk("the results kept their cells (same columns)", d.auction.results[1].slot == resCol, d.auction.results[1].slot)
local nres = 0
for _, r in ipairs(d.auction.results) do if r.slot then nres = nres + 1 end end
chk("40 results have cells (the row's 40 columns)", nres == 40, nres)
chk("the version is i174 or later", tonumber(WowCompanion.interactIV:match("^i(%d+)")) >= 174, WowCompanion.interactIV)
ON.AUCTION_HOUSE_CLOSED()
return table.concat(out, "\n")
