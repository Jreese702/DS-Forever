-- i156 (bags-tip1, the user 2026-11-13: "the tool tip shows one thing for a half second and has to load the rest"; bags_design_1113.md
-- fix 3): N.tipOf reads a bag item's tooltip ONCE. The compare's item (its guid, else its link) comes from that same read; it used to
-- read the item's tooltip a second time for it. The compare and the game's own stat changes are as before.
local out = {}
local function chk(n, c) out[#out + 1] = (c and "PASS " or "FAIL ") .. n end
local WC = WowCompanion
local BAGREADS, WORNREADS, DELTA_ITEM = 0, 0, nil
local function ln(t, r, g, b) return { leftText = t, leftColor = { r = r or 1, g = g or 1, b = b or 1 } } end
C_TooltipInfo = {
    GetBagItem = function(bag, slot)
        BAGREADS = BAGREADS + 1
        if bag == 0 and slot == 1 then
            return { guid = "Item-1-0-ABC", hyperlink = "|Hitem:500|h", lines = { ln("Tunic of Testing", 0.12, 1, 0), ln("Chest"), ln("40 Armor") } }
        elseif bag == 0 and slot == 2 then
            return { hyperlink = "|Hitem:117|h", lines = { ln("Tough Jerky"), ln("Use: Restores 61 health over 18 sec.", 0, 1, 0) } }
        elseif bag == 0 and slot == 3 then
            return { lines = {} }                                     -- nothing yet (the game still loading it)
        end
    end,
    GetInventoryItem = function(unit, slot)
        WORNREADS = WORNREADS + 1
        if slot == 5 then return { lines = { ln("Old Shirt of Wear"), ln("Chest"), ln("20 Armor") } } end
    end,
}
C_TooltipComparison = { GetItemComparisonDelta = function(item, worn) DELTA_ITEM = item; return { "|cff00ff00+20|r Armor" } end }
C_Item.GetItemInfoInstant = function(id)
    if id == 500 then return 500, "Armor", "Cloth", "INVTYPE_CHEST" end
    return id, "Consumable", "Food & Drink", ""
end
GetInventoryItemID = function(unit, slot) if slot == 5 then return 900 end end
GetInventoryItemLink = function(unit, slot) if slot == 5 then return "|Hitem:900|h" end end

local t = WC.tipOf("bag 0 1 500")
chk("gear: the item's own tooltip read once (was twice)", BAGREADS == 1)
chk("gear: its lines", type(t) == "table" and type(t.lines) == "table" and t.lines[1].t == "Tunic of Testing" and #t.lines == 3)
chk("gear: the worn chest compared", type(t.cmp) == "table" and #t.cmp == 1 and t.cmp[1].slot == 5 and WORNREADS == 1)
chk("gear: the comparison is given the item's guid from that one read", type(DELTA_ITEM) == "table" and DELTA_ITEM.guid == "Item-1-0-ABC")
local cl = t.cmp and t.cmp[1].lines or {}
chk("gear: the game's stat change, green", cl[#cl] and cl[#cl].t == "+20 Armor" and cl[#cl].c == "00ff00")

BAGREADS = 0
local j = WC.tipOf("bag 0 2 117")
chk("not gear: read once, no compare", BAGREADS == 1 and j and j.cmp == nil and j.lines[1].t == "Tough Jerky")

BAGREADS, DELTA_ITEM = 0, nil
local e = WC.tipOf("bag 0 3 501")
chk("nothing yet: none (Bags keeps it as loading, never as final)", type(e) == "table" and e.none == true and BAGREADS == 1)

-- a tooltip with no guid: the comparison is given its link, as before
C_TooltipInfo.GetBagItem = function() return { hyperlink = "|Hitem:500|h", lines = { ln("Tunic of Testing") } } end
WC.tipOf("bag 0 1 500")
chk("no guid: the comparison is given the link", type(DELTA_ITEM) == "table" and DELTA_ITEM.hyperlink == "|Hitem:500|h")
chk("the version moved on", WC.interactIV == "i156")
return table.concat(out, "\n")
