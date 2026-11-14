-- i160 (issue #233, a player at a vendor: "I cannot compare whether an item is stronger or weaker than the one I have equipped, or see its
-- sell value"): the game's sell price line (C_TooltipInfo line type SellPrice, its copper in `price`, no text of its own) is kept in
-- every tooltip we send as { t = SELL_PRICE .. ":", m = copper }; a line of any other type with a price, a zero price or a secret one
-- adds nothing.
local out = {}
local function chk(n, c) out[#out + 1] = (c and "PASS " or "FAIL ") .. n end
local WC = WowCompanion
local function ln(t) return { leftText = t, leftColor = { r = 1, g = 1, b = 1 } } end
SELL_PRICE = "Sell Price"
Enum = Enum or {}
Enum.TooltipDataLineType = { SellPrice = 11 }
local LINES = {
    [1] = { ln("Bag item 501"), ln("Binds when picked up"), { type = 11, price = 12345 } },
    [2] = { ln("Bag item 502"), { type = 11, price = 0 } },
    [3] = { ln("Bag item 503"), { type = 5, price = 99 } },
    [4] = { ln("Bag item 504"), { type = 11, price = 250, leftText = "Vendor:" } },
}
C_TooltipInfo = {
    GetBagItem = function(bag, slot) return { hyperlink = "|Hitem:" .. (500 + slot) .. "|h", lines = LINES[slot] } end,
    GetMerchantItem = function(i) return { lines = { ln("Vendor item"), { type = 11, price = 30 } } } end,
}
C_Item.GetItemInfoInstant = function(x) return 501, "Misc", "Junk", "" end
GetInventoryItemID = function() return nil end

local t = WC.tipOf("bag 0 1 501")
local L1 = t and t.lines or {}
chk("the item's own lines as before", L1[1] and L1[1].t == "Bag item 501" and L1[2] and L1[2].t == "Binds when picked up")
chk("the sell price line kept: the game's words and its copper", L1[3] and L1[3].t == "Sell Price:" and L1[3].m == 12345 and #L1 == 3)
t = WC.tipOf("bag 0 2 502")
chk("a zero price adds nothing", t and t.lines and #t.lines == 1)
t = WC.tipOf("bag 0 3 503")
chk("another line type with a price adds nothing", t and t.lines and #t.lines == 1 and t.lines[1].m == nil)
t = WC.tipOf("bag 0 4 504")
chk("a sell price line with words of its own keeps them", t and t.lines and t.lines[2] and t.lines[2].t == "Vendor:" and t.lines[2].m == 250)
t = WC.tipOf("buy 1 501")
chk("a vendor's item too", t and t.lines and t.lines[2] and t.lines[2].m == 30)
LINES[1][3] = { type = 11, price = WC.SECRET }
t = WC.tipOf("bag 0 1 501")
chk("a secret price is never used", t and t.lines and #t.lines == 2)
Enum.TooltipDataLineType = nil
chk("the version is i160 or later", tonumber(WC.interactIV:match("^i(%d+)$")) >= 160)
return table.concat(out, "\n")
