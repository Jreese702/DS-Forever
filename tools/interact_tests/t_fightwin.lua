-- i136 (test76, the user: "Hide the game's" ... "they were opening in combat"): every window the NPC-windows setting covers, opening
-- while the game still counts a fight, is dimmed on the top screen (ours shows), stays dimmed through its documents (no release),
-- the document says what the game's A will act on, and the moment the fight ends the controller is ours (held, still dimmed)
local N, ACT, ON = __N, __ACT, __ON
local out = {}
local function chk(n, c) out[#out + 1] = (c and "PASS " or "FAIL ") .. n end
local FrameMT = { __index = {
    GetAlpha = function(self) return self.alpha or 1 end, SetAlpha = function(self, a) self.alpha = a end,
    IsShown = function(self) return self.shown ~= false end, HookScript = function() end, GetRect = function() return 0, 0, 100, 100 end,
    GetEffectiveScale = function() return 1 end, GetChildren = function() end, GetRegions = function() end,
    GetParent = function() return nil end,
} }
local NAMES = { "GossipFrame", "QuestFrame", "MerchantFrame", "ContainerFrameCombinedBags", "GamepadBagBar", "ClassTrainerFrame",
                "PetStableFrame", "PetitionFrame", "ItemTextFrame", "MailFrame", "OpenMailFrame", "AuctionHouseFrame", "FlightMapFrame",
                "TaxiFrame", "BankFrame", "LootFrame" }
for _, n in ipairs(NAMES) do rawset(_G, n, setmetatable({ alpha = 1 }, FrameMT)) end
-- the game's cursor rests on a button reading "Buy Linen Cloth"
local focus = setmetatable({ GetText = function() return "Buy Linen Cloth" end }, FrameMT)
GetMouseFoci = function() return { focus } end
WowCompanionDB = { lootTop = false }
GAME.t = 3000
N.setMode(true)
ACT.trust()
local KINDS = {
    { "gossip", "GOSSIP_SHOW", "GossipFrame" }, { "quest", "QUEST_DETAIL", "QuestFrame" }, { "merchant", "MERCHANT_SHOW", "MerchantFrame" },
    { "trainer", "TRAINER_SHOW", "ClassTrainerFrame" }, { "stable", "PET_STABLE_SHOW", "PetStableFrame" },
    { "petition", "PETITION_SHOW", "PetitionFrame" }, { "book", "ITEM_TEXT_BEGIN", "ItemTextFrame" }, { "mail", "MAIL_SHOW", "MailFrame" },
    { "auction", "AUCTION_HOUSE_SHOW", "AuctionHouseFrame" }, { "taxi", "TAXIMAP_OPENED", "TaxiFrame" }, { "bank", "BANKFRAME_OPENED", "BankFrame" },
    { "loot", "LOOT_OPENED", "LootFrame" },
}
local function capFor(slot)
    for i = #SENT, 1, -1 do local t = SENT[i]; if t.type == "cap" and t.probe == "npchide" and t.w == slot then return t end end
end
for _, k in ipairs(KINDS) do
    local slot, ev, fname = k[1], k[2], k[3]
    InCombatLockdown = function() return false end
    N.sync(false, nil)                                -- (nothing open: whatever was ours goes back)
    N.unhide()
    _G[fname].alpha = 1
    GAME.t = GAME.t + 1
    InCombatLockdown = function() return true end    -- the fight's flag still up a moment after the kill
    if ON[ev] then pcall(ON[ev]) end
    N.early(slot)
    local c = capFor(slot)
    local a0 = _G[fname].alpha
    -- its document, still in the fight: dimmed kept (no release putting it back), the game's A said
    N.sync(true, { [slot] = {} })
    local fd = N.fightDoc()
    chk(slot .. " in a fight: the game's window dimmed (" .. tostring(a0) .. ", after its document " .. tostring(_G[fname].alpha) ..
        "), logged '" .. tostring(c and c.why) .. "', A said ('" .. tostring(fd and fd.a) .. "')",
        a0 == 0 and _G[fname].alpha == 0 and c and c.ok == true and c.how == "fight" and c.why == "combat: dimmed in fight"
        and fd and fd.a == "Buy Linen Cloth" and N.holding ~= true)
    -- the fight ends: the controller is ours at once, the window still dimmed, the line gone
    InCombatLockdown = function() return false end
    N.sync(true, { [slot] = {} })
    chk(slot .. ": the fight ends -> ours (holding " .. tostring(N.holding) .. "), still dimmed (" .. tostring(_G[fname].alpha) ..
        "), the A line gone", N.holding == true and _G[fname].alpha == 0 and N.fightDoc() == nil)
end
-- a fight with the Loot window kept on the top screen by its own switch: the game's loot window stays
InCombatLockdown = function() return false end
N.sync(false, nil); N.unhide()
WowCompanionDB.lootTop = true
LootFrame.alpha = 1
InCombatLockdown = function() return true end
if ON.LOOT_OPENED then pcall(ON.LOOT_OPENED) end
N.early("loot")
chk("the Loot window's own 'top' switch on: the game's loot window kept even in a fight (" .. tostring(LootFrame.alpha) .. ")",
    LootFrame.alpha == 1)
WowCompanionDB.lootTop = false
InCombatLockdown = function() return false end
return table.concat(out, "\n")
