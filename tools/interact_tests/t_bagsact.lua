-- i157 (bags-act1, the user's pick 2026-11-13: "a two-hander compares vs main hand + off hand together; empty slot shows all gains"):
-- N.tipOf's compare. A two-hander shows BOTH worn hands, its stat changes counted against the pair (the game's paired comparison,
-- under the main hand: with = 17; the off hand pair = 16, none of its own); a build without the paired form gives nothing there (the app
-- counts then). An off-hand piece while a two-hander is worn compares with that two-hander. Every entry says how many of its last
-- lines are the game's changes (dn).
local out = {}
local function chk(n, c) out[#out + 1] = (c and "PASS " or "FAIL ") .. n end
local WC = WowCompanion
local function ln(t) return { leftText = t, leftColor = { r = 1, g = 1, b = 1 } } end
local WORN = { [16] = 801, [17] = 802 }
local LOC = { [500] = "INVTYPE_2HWEAPON", [501] = "INVTYPE_SHIELD", [502] = "INVTYPE_CHEST", [801] = "INVTYPE_WEAPON",
              [802] = "INVTYPE_SHIELD", [803] = "INVTYPE_2HWEAPON", [900] = "INVTYPE_CHEST" }
C_TooltipInfo = {
    GetBagItem = function(bag, slot)
        local id = ({ 500, 501, 502 })[slot]
        return { hyperlink = "|Hitem:" .. id .. "|h", lines = { ln("Bag item " .. id) } }
    end,
    GetInventoryItem = function(unit, slot) if WORN[slot] then return { lines = { ln("Worn " .. WORN[slot]) } } end end,
}
local CALLS = {}
local PAIRED = true          -- this build has the paired form
C_TooltipComparison = { GetItemComparisonDelta = function(item, worn, paired, add)
    CALLS[#CALLS + 1] = { worn = worn and worn.hyperlink, paired = paired and paired.hyperlink, add = add }
    if paired and not PAIRED then error("Usage: GetItemComparisonDelta(comparisonItem, equippedItem)") end
    if paired and PAIRED == "ignored" then return { "|cff00ff00+9|r Strength" } end   -- a build that ignores the extra arguments
    if paired then return { "|cff00ff00+5|r Strength", "|cffff2020-200|r Armor" } end
    return { "|cff00ff00+9|r Strength" }
end }
C_Item.GetItemInfoInstant = function(x)
    local id = type(x) == "number" and x or tonumber(tostring(x):match("item:(%d+)"))
    return id, "Weapon", "Sword", LOC[id] or ""
end
GetInventoryItemID = function(unit, slot) return WORN[slot] end
GetInventoryItemLink = function(unit, slot) if WORN[slot] then return "|Hitem:" .. WORN[slot] .. "|h" end end

-- 1. a two-hander against a sword and a shield
local t = WC.tipOf("bag 0 1 500")
local c = t and t.cmp or {}
chk("2H: both worn hands compared", #c == 2 and c[1].slot == 16 and c[2].slot == 17)
chk("2H: the pair's changes under the main hand (with = 17, dn = 2)", c[1] and c[1].with == 17 and c[1].dn == 2
    and c[1].lines[#c[1].lines].t == "-200 Armor" and c[1].lines[#c[1].lines].c == "ff2020")
chk("2H: the game asked for the pair (the off hand's link, addPairedStats), then the plain form to tell an ignored pair (i158)", #CALLS == 2 and CALLS[2].paired == nil and CALLS[1].worn == "|Hitem:801|h"
    and CALLS[1].paired == "|Hitem:802|h" and CALLS[1].add == true)
chk("2H: the off hand marked pair, no changes of its own", c[2] and c[2].pair == 16 and c[2].dn == nil and #c[2].lines == 1)

-- 2. a build without the paired form: no changes given (the app counts against the pair)
PAIRED = false; CALLS = {}
t = WC.tipOf("bag 0 1 500"); c = t and t.cmp or {}
chk("no paired form: the main hand with = 17 and no dn (the app counts)", c[1] and c[1].with == 17 and c[1].dn == nil and #c[1].lines == 1)
-- i158: a build that ignores the extra arguments (the same lines as the plain form): not the pair's, nothing given
PAIRED = "ignored"; CALLS = {}
t = WC.tipOf("bag 0 1 500"); c = t and t.cmp or {}
chk("paired args ignored: the main hand's lines are not taken for the pair's (no dn)", c[1] and c[1].with == 17 and c[1].dn == nil
    and #c[1].lines == 1)
PAIRED = true

-- 3. a two-hander with only a main hand worn: one compare, the plain form
WORN = { [16] = 801 }; CALLS = {}
t = WC.tipOf("bag 0 1 500"); c = t and t.cmp or {}
chk("2H, the off hand empty: the main hand alone, its own changes", #c == 1 and c[1].slot == 16 and c[1].with == nil and c[1].dn == 1
    and CALLS[1].paired == nil)

-- 4. a shield while a two-hander is worn (the off hand empty): compared with the two-hander
WORN = { [16] = 803 }; CALLS = {}
t = WC.tipOf("bag 0 2 501"); c = t and t.cmp or {}
chk("a shield vs a worn two-hander: compared with slot 16", #c == 1 and c[1].slot == 16 and c[1].dn == 1)
-- and with a one-hander worn: the off hand only (empty: no compare from the game; the app shows its gains)
WORN = { [16] = 801 }
t = WC.tipOf("bag 0 2 501")
chk("a shield vs an empty off hand: no compare from the game", t and t.cmp == nil)

-- 5. a plain chest: dn counts the game's lines
WORN = { [5] = 900 }
t = WC.tipOf("bag 0 3 502"); c = t and t.cmp or {}
chk("a chest: one compare, dn = 1", #c == 1 and c[1].slot == 5 and c[1].dn == 1 and c[1].pair == nil and c[1].with == nil)
chk("the version is i158 or later", tonumber(WC.interactIV:match("^i(%d+)$")) >= 158)
return table.concat(out, "\n")
