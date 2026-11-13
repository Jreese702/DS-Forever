-- i159 (bags-nocmp1, the user 2026-11-13: "the bag items are still showing currently equipped, I dont want to see that, but I do want to
-- see the stat changes at the bottom of that weapon if I equip it"): WC.tipOf(key, { nw = true }) (the Bags room) reads NO worn tooltip:
-- each compare entry is only the game's stat changes (" ", the header, the changes; dn), marked nw. Without the option (vendors, loot
-- rolls, chat links) the worn pieces' tooltips are read as before.
local out = {}
local function chk(n, c) out[#out + 1] = (c and "PASS " or "FAIL ") .. n end
local WC = WowCompanion
local function ln(t) return { leftText = t, leftColor = { r = 1, g = 1, b = 1 } } end
local WORN = { [5] = 900 }
local LOC = { [500] = "INVTYPE_2HWEAPON", [502] = "INVTYPE_CHEST", [801] = "INVTYPE_WEAPON", [802] = "INVTYPE_SHIELD", [900] = "INVTYPE_CHEST" }
local WORNREADS = 0
C_TooltipInfo = {
    GetBagItem = function(bag, slot)
        local id = ({ 500, 501, 502 })[slot]
        return { hyperlink = "|Hitem:" .. id .. "|h", lines = { ln("Bag item " .. id) } }
    end,
    GetInventoryItem = function(unit, slot) WORNREADS = WORNREADS + 1; if WORN[slot] then return { lines = { ln("Worn " .. WORN[slot]) } } end end,
}
C_TooltipComparison = { GetItemComparisonDelta = function(item, worn, paired)
    if paired then return { "|cff00ff00+5|r Strength", "|cffff2020-200|r Armor" } end
    return { "|cff00ff00+9|r Stamina", "|cffff2020-3|r Spirit" }
end }
C_Item.GetItemInfoInstant = function(x)
    local id = type(x) == "number" and x or tonumber(tostring(x):match("item:(%d+)"))
    return id, "Armor", "Cloth", LOC[id] or ""
end
GetInventoryItemID = function(unit, slot) return WORN[slot] end
GetInventoryItemLink = function(unit, slot) if WORN[slot] then return "|Hitem:" .. WORN[slot] .. "|h" end end

-- 1. the Bags room's read: a chest against a worn chest, no worn tooltip read
local t = WC.tipOf("bag 0 3 502", { nw = true })
local c = t and t.cmp or {}
chk("nw: no worn tooltip read", WORNREADS == 0)
chk("nw: the bag item's own lines as before", t and t.lines and t.lines[1].t == "Bag item 502")
chk("nw: one compare, marked nw, dn = 2", #c == 1 and c[1].slot == 5 and c[1].nw == true and c[1].dn == 2)
chk("nw: its lines are only the blank, the header and the changes", c[1] and #c[1].lines == 4 and c[1].lines[1].t == " "
    and c[1].lines[2].c == "ffd100" and c[1].lines[3].t == "+9 Stamina" and c[1].lines[3].c == "00ff00"
    and c[1].lines[4].t == "-3 Spirit" and c[1].lines[4].c == "ff2020")

-- 2. every other caller: the worn tooltip read and shown first, as before
t = WC.tipOf("bag 0 3 502"); c = t and t.cmp or {}
chk("no option: the worn tooltip read", WORNREADS == 1)
chk("no option: the worn piece's lines first, no nw", c[1] and c[1].nw == nil and c[1].lines[1].t == "Worn 900" and #c[1].lines == 5 and c[1].dn == 2)

-- 3. a two-hander against a sword and a shield, nw: the pair's changes under the main hand, the off hand empty of lines
WORN = { [16] = 801, [17] = 802 }; WORNREADS = 0
t = WC.tipOf("bag 0 1 500", { nw = true }); c = t and t.cmp or {}
chk("nw 2H: both hands, no worn tooltip read", #c == 2 and WORNREADS == 0)
chk("nw 2H: the main hand with = 17, the pair's changes", c[1] and c[1].with == 17 and c[1].dn == 2 and c[1].nw == true and #c[1].lines == 4)
chk("nw 2H: the off hand pair = 16, no lines", c[2] and c[2].pair == 16 and c[2].nw == true and #c[2].lines == 0)
chk("the version is i159 or later", tonumber(WC.interactIV:match("^i(%d+)$")) >= 159)
return table.concat(out, "\n")
