-- i167 (ah-post-grid, the user 2026-11-19: "We need to seriously improve the ah experience"): the Sell tab's grid with a folding header
-- per bag; picking an item puts the cursor on Post; X is Post; after a post the form clears at once and the cursor goes back to the
-- item's cell (or the nearest left), never the top; the throttle is looked at again; a B that closed the keyboard is not also a step
-- back; a second press on the selected category goes up a level; Reset; the item rows' tooltips; the mailbox's Open All.
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

N.mode, N.holding, N.stale, N.fight, N.frameSeen = true, true, nil, nil, true
ON.AUCTION_HOUSE_SHOW()
ACT.ahtab("sell")
local function nav() local d = build(); return d, d.nav end
local d, nv = nav()
local s = d.auction.sell
chk("the grid: AH.SCOLS = 4 a row, sent to the app", s.cols == 4, s.cols)
chk("a group per bag, named, counted", s.groups and #s.groups == 2 and s.groups[1].name == "Backpack" and s.groups[1].n == 4
    and s.groups[2].name == "Small Bag" and s.groups[2].n == 2, s.groups and #s.groups)
chk("each header has a touch cell", s.groups[1].slot ~= nil and s.groups[2].slot ~= nil)
chk("the rows: header, 4 items, header, 2 items", #AH.gridRows == 4 and #AH.gridRows[2] == 4 and #AH.gridRows[4] == 2, #AH.gridRows)
chk("the cursor starts on the first header", nv.cur == "ahsfold 0", nv.cur)
N.move("down"); chk("Down: the first item", N.cur == "ahsitem 0 1 2589", N.cur)
N.move("right"); N.move("right"); chk("Right twice: the third", N.cur == "ahsitem 0 3 818", N.cur)
N.move("down"); chk("Down: the next bag's header", N.cur == "ahsfold 1", N.cur)
N.move("down"); chk("Down: its first item", N.cur == "ahsitem 1 1 2592", N.cur)
N.move("up"); N.move("up"); chk("Up twice: back in the backpack's row (the same column clamped)", N.cur == "ahsitem 0 1 2589", N.cur)
N.move("up"); N.move("up"); chk("Up past the top: the Sell tab", N.cur == "ahtab sell", N.cur)
-- fold the backpack (A on its header)
N.cur = "ahsfold 0"; pcall(N.act)
d, nv = nav()
chk("A on a header folds it", d.auction.sell.groups[1].shut == true and #AH.gridRows == 3, #AH.gridRows)
N.move("down"); chk("Down from the folded header: the next bag", N.cur == "ahsfold 1", N.cur)
N.cur = "ahsfold 0"; pcall(N.act); nav()
chk("A again unfolds it", #AH.gridRows == 4)

-- pick the Tigerseye (A): the cursor on Post, never the gold box - even while the throttle greys Post
AHS.ready = false
N.cur = "ahsitem 0 3 818"; pcall(N.act)
d, nv = nav()
chk("picked: the form has it", d.auction.sell.item and d.auction.sell.item.id == 818)
chk("picked: the cursor on Post (throttled)", nv.cur == "ahspost", nv.cur)
chk("the throttle is looked at again (a timer)", #timers > 0 and AH.polling == true, #timers)
AHS.ready = true
runTimers()
d, nv = nav()
chk("ready: Post can go, the cursor still on it", d.auction.sell.why == nil and nv.cur == "ahspost", nv.cur)
-- a finger's look kept from the Buy tab does not turn X into hold-to-compare here
N.look = "ahres 1"
-- i168: Start = Post: kept from the game while an item is in the form; press 1 the confirm, press 2 posts
chk("Start is kept for the Sell form", N.npcExtra.PADFORWARD == true)
padPress("PADFORWARD", true, "frame"); padPress("PADFORWARD", false, "frame")
d, nv = nav()
chk("Start: the confirm (armed), the cursor on its Post", d.auction.sell.armed == true and nv.cur == "ahspost", nv.cur)
padPress("PADFORWARD", true, "frame"); padPress("PADFORWARD", false, "frame")
chk("Start again: the game's PostCommodity", AHS.posted ~= nil and AHS.posted.loc.s == 3)
d, nv = nav()
chk("posted: the form clears at once (no grey Post left)", d.auction.sell.item == nil and AH.sell == nil)
chk("posted: the cursor back on the item's cell, not the top", nv.cur == "ahsitem 0 3 818", nv.cur)
chk("posted: the posting word", d.auction.sell.posting == true)
-- the stack leaves the bags: the nearest one left
BAGS[0][3] = nil
d, nv = nav()
chk("the item gone: the nearest cell (the same place)", nv.cur == "ahsitem 0 4 1210", nv.cur)
ON.AUCTION_HOUSE_AUCTION_CREATED()
d = build()
chk("the game's word, no form touched", d.auction.sell.msg and d.auction.sell.msg.words ~= nil and AH.sell == nil)
-- a new item picked, then AUCTION_CREATED of the last one: the new form stays
N.cur = "ahsitem 0 4 1210"; pcall(N.act)
ON.AUCTION_HOUSE_AUCTION_CREATED()
chk("a late Auction created leaves the new form alone", AH.sell ~= nil and AH.sell.id == 1210)
-- B from the form: one step, the cursor back on the cell
pcall(N.back)
d, nv = nav()
chk("B: the form goes, the cursor on the cell", AH.sell == nil and nv.cur == "ahsitem 0 4 1210", nv.cur)

-- A on the quantity row = Max, Left / Right step it; Y is nothing on the Sell tab
N.cur = "ahsitem 0 1 2589"; pcall(N.act); nav()
N.cur = "ahsq"; nav()
local q0 = AH.sell.qty
-- i171 (the user's ruling 2026-11-19): Y = minus, X = plus (Y is the left face button on the Thor's Nintendo layout)
padPress("PAD4", true, "frame"); padPress("PAD4", false, "frame")
chk("Y on the Sell tab: one less", AH.sell.qty == q0 - 1, AH.sell.qty)
padPress("PAD3", true, "frame"); padPress("PAD3", false, "frame")
chk("X: one more", AH.sell.qty == q0, AH.sell.qty)
chk("X never posts", AH.sell ~= nil and not AH.sell.armedAt)
N.move("left")
chk("Left from Quantity: the grid (the chosen item)", N.cur == "ahsitem 0 1 2589", N.cur)

-- THE KEYBOARD'S B: A on the gold box opens it; B closes it (the app taps "kbd off" on the press); the release is not a step back
N.cur = "ahsedit pg"; pcall(N.act)
chk("A on gold: the keyboard", N.kbd == "ahspg", N.kbd)
padPress("PAD2", true, "frame")
ACT.kbd("off")                                  -- the app's tap lands before the release
padPress("PAD2", false, "frame")
chk("the keyboard's B: the field gone, the item still in the form", N.kbd == nil and AH.sell ~= nil and AH.sell.id == 2589)
padPress("PAD2", true, "frame"); padPress("PAD2", false, "frame")
chk("the next B: one step back (the form goes)", AH.sell == nil)

-- the category toggle and Reset
ACT.ahtab("browse")
ACT.ahcat("1")
chk("a category: browsed", #AH.path == 1 and AHS.sent == 1, #AH.path)
ACT.ahcat("1.1")
chk("its sub-category", #AH.path == 2)
ACT.ahcat("1.1")
chk("the sub-category again: deselected (its parent)", #AH.path == 1 and AH.path[1] == 1, #AH.path)
ACT.ahcat("1")
chk("the category again: collapsed, nothing selected", #AH.path == 0 and AH.results == nil, #AH.path)
AH.search.text, AH.search.min = "sword", "10"
ACT.ahcat("2")
d = build()
chk("Reset has a cell and a stop", d.auction.act.reset ~= nil)
padPress("PAD4", true, "frame"); padPress("PAD4", false, "frame")
chk("Y = Reset: word, range, category gone", AH.search.text == "" and AH.search.min == "" and #AH.path == 0 and AH.results == nil)

-- tooltips: an item's listing by its own link; the item key with the required level; asked again while the data is missing
AH.key = { itemID = 1234, itemLevel = 20, itemSuffix = 0 }
local tp = N.tipOf("ahrow 1")
chk("ahrow: the row's own link", tp and tp.lines and #tp.lines == 2 and TIPS.link and TIPS.link:find("1234"), TIPS.link)
AH.rs = { { itemKey = { itemID = 555, itemLevel = 30, itemSuffix = 7 } } }
TIPS.keyData = nil
timers = {}
tp = N.tipOf("ahres 1")
chk("ahres: GetItemKey with the required level", TIPS.key and TIPS.key[1] == 555 and TIPS.key[2] == 30 and TIPS.key[3] == 7 and TIPS.key[4] == 12)
chk("no data yet: the item asked for, a look again soon", tp and tp.none and AHS.asked == 555 and #timers == 1, AHS.asked)

-- THE MAILBOX's Open All: money then items, one call a step, never a COD letter, stops when the bags are full
local INBOX = { { money = 500, cod = 0, items = { "Linen" } }, { money = 0, cod = 300, items = { "Ore" } }, { money = 0, cod = 0, items = { "A", "B" } },
                { money = 0, cod = 0, items = { "Peacebloom" } } }
C_Item.GetItemFamily = function(id) return id == 2447 and 32 or 0 end
local calls = {}
function GetInboxNumItems() return #INBOX end
function GetInboxHeaderInfo(i) local m = INBOX[i]; if m then
    local n = 0; for _ in pairs(m.items) do n = n + 1 end
    return 1, nil, "x", "s", m.money, m.cod, 3, n, true, false, false, false, false, 1 end end
function GetInboxItem(i, a) local m = INBOX[i]; local it = m and m.items[a]; if it then return it, (it == "Peacebloom") and 2447 or 1, 1, 1, 1 end end
function GetInboxText() return "" end
function TakeInboxMoney(i) calls[#calls + 1] = "money " .. i; INBOX[i].money = 0 end
function TakeInboxItem(i, a) calls[#calls + 1] = "item " .. i .. " " .. a
  if INBOX[i].items[a] == "Peacebloom" and (GAME.herbFree or 0) > 0 then GAME.herbFree = GAME.herbFree - 1 else GAME.free = GAME.free - 1 end
  INBOX[i].items[a] = nil end
MAIL.isOpen = function() return true end
MAIL.tab, MAIL.open = "inbox", nil
local oldIsOpen = nil
GAME.free = 2
timers = {}
ON.MAIL_SHOW()
d = build()
chk("the inbox has an Open All cell", d.mail and d.mail.act and d.mail.act.openall ~= nil)
padPress("PAD3", true, "frame"); padPress("PAD3", false, "frame")
chk("X on the inbox: Open All starts with the first letter's money", calls[1] == "money 1" and MAIL.all ~= nil, calls[1])
d = build()
chk("the document says it runs", d.mail.all and d.mail.all.on == true)
runTimers()
chk("then its item", calls[2] == "item 1 1", calls[2])
runTimers()
chk("the COD letter is skipped: the third letter's item", calls[3] == "item 3 1", calls[3])
runTimers()
chk("bags full: it stops, nothing more taken, the game's words", #calls == 3 and MAIL.all == nil and MAIL.allMsg and MAIL.allMsg.ok == false, #calls)
chk("the COD letter untouched", INBOX[2].items[1] == "Ore" and INBOX[2].cod == 300)
GAME.free = 1
GAME.herbFree = 1
ACT.mailopenall()
chk("again with room: the rest", calls[4] == "item 3 2", calls[4])
runTimers()
chk("general bags full but the herb fits the herb bag: taken", calls[5] == "item 4 1" and GAME.free == 0, calls[5])
runTimers()
chk("nothing left: it ends by itself", MAIL.all == nil, MAIL.all)
ACT.mailopenall(); ACT.mailopenall()
chk("the same button again stops it", MAIL.all == nil)
-- i169 (the 1501 merge): the AH grid (i167a / i168) carried with fix/stream-cursor's stay cell (i167s); i166 never comes back
chk("the version is i169 or later (i167a + i168 + i167s)", tonumber(WowCompanion.interactIV:match("^i(%d+)$")) >= 169, WowCompanion.interactIV)
return table.concat(out, "\n")
