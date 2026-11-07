-- i137 THE TRADE WINDOW (the user, 2026-11-07: "We really need to make a good trade window"; layout A, the gold / silver / copper stepper):
-- the document (both sides' seven slots, the money, who accepted, the bags), the game's own calls for putting an item in, taking it back,
-- the "Will not be traded" slot and the money, the purse limit, and the controller's moves between the slots, the money, the buttons
-- and the bags
local N, ACT, ON, build = __N, __ACT, __ON, __build
local out = {}
local function chk(n, c, got) out[#out + 1] = (c and "PASS " or "FAIL ") .. n .. ((not c and got ~= nil) and (" -> " .. tostring(got)) or "") end

-- the game's trade, as far as the tests need it
TRADE = { p = {}, t = {}, pm = 0, tm = 0, set = nil }
GAME.money = 100000                                                    -- 10 gold in the purse
GAME.bags = { { id = 2589, name = "Linen Cloth", n = 20 }, { id = 2672, name = "Stringy Wolf Meat", n = 5 }, { id = 774, name = "Malachite", n = 1 } }
local origin = nil                                                     -- where the item on the cursor came from (for ClearCursor)
C_Container.PickupContainerItem = function(bag, s) GAME.cursor = GAME.bags[s]; GAME.bags[s] = nil; origin = s end
ClearCursor = function() if GAME.cursor and origin then GAME.bags[origin] = GAME.cursor end; GAME.cursor = nil; origin = nil end
function GetTradePlayerItemInfo(k) local it = TRADE.p[k]; if it then return it.name, 134, it.n, 1 end end
function GetTradeTargetItemInfo(k) local it = TRADE.t[k]; if it then return it.name, 135, it.n, 2 end end
function GetTradePlayerItemLink(k) return nil end
function GetTradeTargetItemLink(k) return nil end
function ClickTradeButton(k)
    if GAME.cursor then local was = TRADE.p[k]; TRADE.p[k] = GAME.cursor; GAME.cursor = was
    elseif TRADE.p[k] then GAME.cursor, TRADE.p[k] = TRADE.p[k], nil
        for s = 1, 10 do if not GAME.bags[s] then origin = s; break end end
    end
end
function GetPlayerTradeMoney() return TRADE.pm end
function GetTargetTradeMoney() return TRADE.tm end
C_TradeInfo = { SetTradeMoney = function(v) TRADE.set = v; TRADE.pm = v end }
function UnitName(u) if u == "NPC" then return "Brannoc" end return "Me" end
function UnitLevel(u) return 12 end
function UnitClass(u) return "Warrior", "WARRIOR" end

ON.TRADE_SHOW()
local d = build()
local td = d.trade
chk("TRADE_SHOW: a trade document", td ~= nil)
chk("the partner's name", td and td.who == "Brannoc", td and td.who)
chk("seven slots each side", td and #td.mine == 7 and #td.theirs == 7)
chk("the bags beside it, with a cell each", td and #td.bags == 3 and td.bags[1].slot ~= nil)
chk("the stepper's cells (edit / set / g+)", td and td.act and td.act.edit and td.act.set and td.act["g+"] and td.act["c--"])

-- an item in: the first empty traded slot, by the game's own pickup + ClickTradeButton
local ok, why = ACT.tradein("0", "1", "2589")
chk("tradein: Linen Cloth into slot 1", ok and TRADE.p[1] and TRADE.p[1].name == "Linen Cloth", why)
chk("... and out of the bag", GAME.bags[1] == nil)
ok = ACT.tradein("0", "2", "2672")
chk("the next item into slot 2", ok and TRADE.p[2] and TRADE.p[2].name == "Stringy Wolf Meat")
ok, why = ACT.tradein("0", "1", "2589")
chk("a bag slot that is now empty is refused (that item moved)", not ok and why == "that item moved", why)

-- the "Will not be traded" slot: picked first, then the next item goes there
ok = ACT.tradepick("7")
ok = ACT.tradein("0", "3", "774")
chk("tradepick 7 then tradein: Malachite in the seventh slot", ok and TRADE.p[7] and TRADE.p[7].name == "Malachite" and N.trd.dest == nil)

-- an item back
ok, why = ACT.tradeout("1")
chk("tradeout 1: back to the bags, the slot empty", ok and TRADE.p[1] == nil and GAME.cursor == nil, why)
ok, why = ACT.tradeout("1")
chk("tradeout on an empty slot is refused", not ok and why == "that slot is empty", why)

-- the money stepper: opened with what is offered, a gold up, set with the game's call
ACT.trademoney("edit")
chk("the stepper opens at 0g 0s 0c", N.trd.edit and N.trd.edit.g == 0 and N.trd.edit.s == 0 and N.trd.edit.c == 0)
ACT.tradeadj("g", "1"); ACT.tradeadj("s", "50"); ACT.tradeadj("c", "-1")
chk("g +1, s +50 (to 50), c -1 stays 0", N.trd.edit.g == 1 and N.trd.edit.s == 50 and N.trd.edit.c == 0)
d = build()
chk("the document carries the stepper (edit g / s / c; sel = the coin moved last, copper)", d.trade.edit and d.trade.edit.g == 1 and d.trade.edit.s == 50 and d.trade.edit.sel == 3)
ok = ACT.trademoney("set")
chk("set: C_TradeInfo.SetTradeMoney(15000), the stepper closed", ok and TRADE.set == 15000 and N.trd.edit == nil, TRADE.set)
ACT.trademoney("edit")
chk("the stepper opens again at what is offered (1g 50s)", N.trd.edit.g == 1 and N.trd.edit.s == 50)
ACT.tradeadj("g", "50")
chk("never past the purse: 51g asked, 10g in the purse -> 10g", N.trd.edit.g == 10 and N.trd.edit.s == 0 and N.trd.edit.c == 0,
    N.trd.edit.g .. "g " .. N.trd.edit.s .. "s")
ACT.trademoney("off")
chk("off: the stepper closes, nothing set", N.trd.edit == nil and TRADE.set == 15000)

-- who accepted
ON.TRADE_ACCEPT_UPDATE(0, 1)
d = build()
chk("TRADE_ACCEPT_UPDATE(0, 1): the partner accepted, you not", d.trade.tacc == true and d.trade.acc == nil)

-- the partner's items show their names; the money of each side
TRADE.t[1] = { name = "Bronze Bar", n = 4 }; TRADE.tm = 2500
d = build()
chk("their slot 1: Bronze Bar x4, their money 25s", d.trade.theirs[1].name == "Bronze Bar" and d.trade.theirs[1].count == 4 and d.trade.tmoney == 2500)
chk("their filled slot has a look cell (its tooltip)", d.trade.theirs[1].slot ~= nil and d.trade.theirs[2].slot == nil)

-- the controller: the zones and the moves
local zones = N.trd.zones(d.trade)
N.zones = zones
local names = {}
for _, z in ipairs(zones) do names[#names + 1] = z.name end
chk("zones (i138: no Trade / Cancel stops): tslots, tmoney, tbags", table.concat(names, ",") == "tslots,tmoney,tbags", table.concat(names, ","))
local function at(id) for zi, z in ipairs(N.zones) do for ii, it in ipairs(z.items) do if it.id == id then return z, ii end end end end
local function move(dir) local z, ii = at(N.cur); return N.trd.move(z, ii, dir) end
N.cur = "tslot p 1"; move("right")
chk("Right from your slot 1: their slot 1 (filled)", N.cur == "tslot t 1", N.cur)
N.cur = "tslot p 2"; move("right")
chk("Right from your slot 2 (their 2 is empty): the bags", N.cur:match("^tradein ") ~= nil, N.cur)
N.cur = "tslot p 7"; move("down")
chk("Down from slot 7: the money", N.cur == "tmoney", N.cur)
move("down")
chk("Down from the money: stays (no buttons to stop on)", N.cur == "tmoney", N.cur)
move("up")
chk("Up from the money: your slot 7", N.cur == "tslot p 7", N.cur)
-- i138: the left stick is the D-pad here; the right stick counts the bags' scroll steps
isOpen_was = true
N.holding = true
N.cur = "tslot p 1"
local okL = pcall(N.trd.stick, "Left", 0.9, 0.1)
chk("the left stick right (past half way): one step right (their slot 1)", okL and N.cur == "tslot t 1", N.cur)
N.trd.stick("Left", 0, 0)
local bs0 = N.trd.bs
N.trd.stick("Right", 0, -0.9)
chk("the right stick down: one bag-scroll step", N.trd.bs == bs0 + 1, N.trd.bs)
N.trd.stick("Right", 0, 0)
N.trd.editStart()
N.cur = "tmoney"; move("right"); move("up"); move("up")
chk("in the stepper (opened at the 1g 50s offered): Right picks silver, Up twice = 52s", N.trd.edit.sel == 2 and N.trd.edit.s == 52, N.trd.edit.sel .. " " .. N.trd.edit.s)
N.trd.edit = nil

ON.TRADE_CLOSED()
chk("TRADE_CLOSED: the stepper and the picks cleared", N.trd.edit == nil and N.trd.dest == nil and N.trd.acc == 0)

return table.concat(out, "\n")
