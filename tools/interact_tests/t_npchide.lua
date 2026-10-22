-- i135: every NPC window kind the "hidden" setting covers is dimmed on the top screen when it opens; the money watchdog judges a
-- vendor only (gold auto-looted while the loot window is hidden no longer drops the proof); each open logs what was hidden or why not
local N, ACT, ON = __N, __ACT, __ON
local out = {}
local function chk(n, c) out[#out + 1] = (c and "PASS " or "FAIL ") .. n end
local FrameMT = { __index = {
    GetAlpha = function(self) return self.alpha or 1 end, SetAlpha = function(self, a) self.alpha = a end,
    IsShown = function(self) return self.shown ~= false end, HookScript = function() end, GetRect = function() return 0, 0, 100, 100 end,
    GetEffectiveScale = function() return 1 end, GetChildren = function() end, GetRegions = function() end,
} }
local NAMES = { "GossipFrame", "QuestFrame", "MerchantFrame", "ContainerFrameCombinedBags", "GamepadBagBar", "ClassTrainerFrame",
                "PetStableFrame", "PetitionFrame", "ItemTextFrame", "MailFrame", "OpenMailFrame", "AuctionHouseFrame", "FlightMapFrame",
                "TaxiFrame", "BankFrame", "LootFrame" }
for _, n in ipairs(NAMES) do rawset(_G, n, setmetatable({ alpha = 1 }, FrameMT)) end
GAME.t = 1000
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
    N.unhide()
    GAME.t = GAME.t + 1
    if ON[ev] then pcall(ON[ev]) end
    N.early(slot)
    local c = capFor(slot)
    local fr = c and c.fr and table.concat(c.fr, ",") or "-"
    chk(slot .. ": hidden on the top screen at once (" .. fname .. " alpha " .. tostring(_G[fname].alpha) .. "), logged (" ..
        tostring(c and c.ok) .. ", " .. fr .. ")", _G[fname].alpha == 0 and c ~= nil and c.ok == true and c.how == "early"
        and fr:find(fname, 1, true) ~= nil)
end
-- the vendor's companions: the bags it opens hidden with it
chk("merchant: the bags it opens hidden with it (" .. tostring(ContainerFrameCombinedBags.alpha) .. ")", ContainerFrameCombinedBags.alpha == 0 or N.hideDiag.merchant.fr[2] ~= nil)
-- THE REGRESSION: gold looted while only the loot window is hidden (no vendor): the proof stays
N.unhide()
for _, n in ipairs(NAMES) do _G[n].alpha = 1 end
if ON.MERCHANT_CLOSED then pcall(ON.MERCHANT_CLOSED) end
if ON.LOOT_OPENED then pcall(ON.LOOT_OPENED) end
N.early("loot")
GAME.t = GAME.t + 10
N.watchMoney()
chk("gold auto-looted while the loot window is hidden (no vendor): the proof stays (distrust " .. tostring(N.distrust) .. ")",
    N.distrust == nil and N.trusted == true)
-- the vendor opens after it: hidden
if ON.LOOT_CLOSED then pcall(ON.LOOT_CLOSED) end
N.unhide()
MerchantFrame.alpha = 1
if ON.MERCHANT_SHOW then pcall(ON.MERCHANT_SHOW) end
N.early("merchant")
chk("... and the vendor after it is still hidden (alpha " .. tostring(MerchantFrame.alpha) .. ")", MerchantFrame.alpha == 0)
-- the watchdog still judges a vendor: money moved there with no action of ours drops the proof, the windows come back, logged why
GAME.t = GAME.t + 10
N.watchMoney()
chk("at the vendor, money moved by no action of ours: the proof dropped (" .. tostring(N.distrust) .. "), the window back ("
    .. tostring(MerchantFrame.alpha) .. ")", N.distrust == "money" and MerchantFrame.alpha == 1)
ClassTrainerFrame.alpha = 1
if ON.TRAINER_SHOW then pcall(ON.TRAINER_SHOW) end
N.early("trainer")
local c = capFor("trainer")
chk("... a trainer opened after that: not hidden, and the log says why (" .. tostring(c and c.why) .. ")",
    ClassTrainerFrame.alpha == 1 and c and c.ok == false and c.why == "distrust:money")
local hd = N.hideDiag
chk("the per-window hide record (the document's nav.hidec): merchant hidden earlier, trainer not, why distrust:money",
    hd.merchant and hd.merchant.ok == true and hd.trainer and hd.trainer.ok == false and hd.trainer.why == "distrust:money")
return table.concat(out, "\n")
