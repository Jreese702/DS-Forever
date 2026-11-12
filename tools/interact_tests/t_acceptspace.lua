-- i144 (the user, 2026-11-11: "Accepting that quest with a full bag asked me to delete an Item but I didn't actually need more space";
-- the quest was begun from a bag item, [Ripped Missive]): with full bags, Make Space opens before the accept ONLY when the quest may
-- hand out an item. Begun from a bag item = its slot frees; QuestieDB knows it gives nothing = accept; QuestieDB says an item, or no
-- QuestieDB = Make Space first (as before); free slots = accept (as before).
local N, ACT, ON, LOOT = __N, __ACT, __ON, __LOOT
local out = {}
local function chk(n, c) out[#out + 1] = (c and "PASS " or "FAIL ") .. n end
local FrameMT = { __index = {
    GetAlpha = function(self) return self.alpha or 1 end, SetAlpha = function(self, a) self.alpha = a end,
    IsShown = function(self) return self.shown ~= false end, HookScript = function() end, GetRect = function() return 0, 0, 100, 100 end,
    GetEffectiveScale = function() return 1 end, GetChildren = function() end, GetRegions = function() end,
    GetParent = function() return nil end,
} }
rawset(_G, "QuestFrame", setmetatable({ alpha = 1, shown = true }, FrameMT))
WowCompanionDB = { lootTop = false }
GAME.t = 3000
N.setMode(true)
ACT.trust()
local Q = { id = 0, title = "" }
GetQuestID = function() return Q.id end
GetTitleText = function() return Q.title end
GetQuestText = function() return "Some words." end
GetObjectiveText = function() return "" end
QuestGetAutoAccept = function() return false end
local accepted = 0
AcceptQuest = function() accepted = accepted + 1 end
C_Container.GetContainerNumFreeSlots = function(bag)
    if bag ~= 0 then return 0, 0 end
    local n = 0
    for s = 1, 4 do if not GAME.bags[s] then n = n + 1 end end
    return n, 0
end
local function fullBags(starter, stack)
    GAME.bags = { { id = 2589, name = "Linen Cloth", n = 5 }, { id = 2592, name = "Wool Cloth", n = 3 },
                  { id = 117, name = "Tough Jerky", n = 2 }, { id = starter or 159, name = starter and "Ripped Missive" or "Refreshing Spring Water", n = stack or 1 } }
end
local function offer(id, title, startItem)
    GAME.t = GAME.t + 1
    LOOT.full, LOOT.ms, LOOT.retry = false, nil, nil
    Q.id, Q.title = id, title
    ON.QUEST_DETAIL(startItem or 0)
    accepted = 0
end
local function qdb(known)      -- known[id] = sourceItemId (0 = none), absent = not in the database
    rawset(_G, "LibQuestieDB", { RequireContract = function(v) return v == 1 end, QuestDB = {
        name = function(id) if known[id] ~= nil then return "Quest " .. id end end,
        sourceItemId = function(id) local v = known[id]; if v ~= 0 then return v end end,
    } })
end

-- 1. full bags, the quest begun from a single bag item (no QuestieDB): accept at once
fullBags(30000)
offer(201, "Ripped Missive", 30000)
local ok = ACT.accept("201")
chk("item-begun quest, full bags: accepted at once", ok and accepted == 1 and LOOT.ms ~= "quest")

-- 1b. the starter is a stack of 2: using one frees nothing -> as before (no QuestieDB: Make Space)
fullBags(30000, 2)
offer(202, "Stacked Missive", 30000)
ok = ACT.accept("202")
chk("item-begun quest from a stack, full bags: Make Space first", ok and accepted == 0 and LOOT.ms == "quest")

-- 4. no QuestieDB, an NPC's quest: Make Space first (unchanged)
fullBags()
offer(203, "Kobold Camp Cleanup")
ok = ACT.accept("203")
chk("no QuestieDB, full bags: Make Space first", ok and accepted == 0 and LOOT.ms == "quest")
ok = ACT.accept("203", "anyway")
chk("and accept anyway still accepts", ok and accepted == 1)

-- 2. QuestieDB knows the quest gives nothing: accept
qdb({ [204] = 0, [205] = 1307, [207] = 0 })
fullBags()
offer(204, "Wolves Across the Border")
ok = ACT.accept("204")
chk("QuestieDB: no item given, full bags: accepted at once", ok and accepted == 1 and LOOT.ms ~= "quest")

-- 3. QuestieDB says the quest hands out an item: Make Space first
fullBags()
offer(205, "Gold Dust Exchange")
ok = ACT.accept("205")
chk("QuestieDB: an item given, full bags: Make Space first", ok and accepted == 0 and LOOT.ms == "quest")

-- 3b. a quest QuestieDB does not know: unknown, Make Space first
fullBags()
offer(206, "A Forever-New Quest")
ok = ACT.accept("206")
chk("QuestieDB does not know the quest: Make Space first", ok and accepted == 0 and LOOT.ms == "quest")

-- 3c. QuestieDB failing its contract: as missing
rawset(_G, "LibQuestieDB", { RequireContract = function() return false end, QuestDB = { name = function() return "x" end,
    sourceItemId = function() return nil end } })
fullBags()
offer(207, "Contract Broken")
ok = ACT.accept("207")
chk("QuestieDB with another contract: Make Space first", ok and accepted == 0 and LOOT.ms == "quest")

-- 1c. i145: begun from a bag item, but QuestieDB says it hands out ANOTHER item: the server checks room for that item before it uses
-- up the starter, so Make Space first
qdb({ [209] = 1307, [210] = 30000, [211] = 0 })
fullBags(30000)
offer(209, "Missive With A Parcel", 30000)
ok = ACT.accept("209")
chk("item-begun quest handing out another item, full bags: Make Space first", ok and accepted == 0 and LOOT.ms == "quest")

-- 1d. begun from a bag item that is also the item it hands out: accept at once
fullBags(30000)
offer(210, "Keep The Missive", 30000)
ok = ACT.accept("210")
chk("item-begun quest handing out its own starter, full bags: accepted at once", ok and accepted == 1 and LOOT.ms ~= "quest")

-- 1e. begun from a bag item, QuestieDB says it hands out nothing: accept at once
fullBags(30000)
offer(211, "Plain Missive", 30000)
ok = ACT.accept("211")
chk("item-begun quest, QuestieDB says no item, full bags: accepted at once", ok and accepted == 1 and LOOT.ms ~= "quest")

-- 5. free slots: accept (unchanged), whatever QuestieDB says
qdb({ [208] = 1307 })
fullBags(); GAME.bags[4] = nil
offer(208, "Free Slot")
ok = ACT.accept("208")
chk("a free slot: accepted at once", ok and accepted == 1 and LOOT.ms ~= "quest")

-- the start item does not leak into the next offer
ON.QUEST_FINISHED()
chk("the start item is forgotten when the window closes", LOOT.qStart == nil)

return table.concat(out, "\n")
