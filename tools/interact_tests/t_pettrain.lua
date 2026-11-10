-- i141 (issue 90, the player: "Pet training causes an addon error and requires a restart after use"; the log: "WowCompanionInteract
-- forbidden: BuyTrainerService()"): at a PET trainer our code never buys - "train <i>" only selects (the game's own selection call)
-- and counts the select for the app (trainer.ptc), whose next tap is the secure "itrain" cell (the game's own Train button). A class
-- trainer keeps its direct buy (the user: training works for their characters).
local N, ACT, ON = __N, __ACT, __ON
local out = {}
local function chk(n, c) out[#out + 1] = (c and "PASS " or "FAIL ") .. n end
local FrameMT = { __index = {
    GetAlpha = function(self) return self.alpha or 1 end, SetAlpha = function(self, a) self.alpha = a end,
    IsShown = function(self) return self.shown ~= false end, IsVisible = function(self) return self.shown ~= false end,
    HookScript = function() end, GetRect = function() return 0, 0, 100, 100 end,
    GetEffectiveScale = function() return 1 end, GetChildren = function() end, GetRegions = function() end,
    GetParent = function() return nil end,
} }
rawset(_G, "ClassTrainerFrame", setmetatable({ alpha = 1 }, FrameMT))
GAME.t = 3000
TYPE_NOW = 1
Enum = { TrainerType = { Class = 0, Pet = 1 } }
C_Trainer = { GetTrainerType = function() return TYPE_NOW end, GetCategorizeTrainerUI = function() return false end }
BOUGHT, SELECTED = {}, {}
function GetNumTrainerServices() return 2 end
function GetTrainerServiceInfo(i) return (i == 1 and "Bite" or "Growl"), "available", 132278, 1, "Rank 1" end
function GetTrainerServiceCost(i) return 100, false end
function IsTradeskillTrainer() return false end
function BuyTrainerService(i) BOUGHT[#BOUGHT + 1] = i end
function SelectTrainerService(i) SELECTED[#SELECTED + 1] = i end
if ON.TRAINER_SHOW then pcall(ON.TRAINER_SHOW) end

local ok, why = ACT.train("1")
chk("pet trainer: train 1 answers ok (" .. tostring(why) .. ")", ok == true)
chk("pet trainer: never BuyTrainerService from our code", #BOUGHT == 0)
chk("pet trainer: the service is selected (SelectTrainerService 1)", SELECTED[1] == 1)
chk("pet trainer: the select is counted for the app (N.ptc 1)", N.ptc == 1)
ACT.train("2")
chk("each select counted (N.ptc 2)", N.ptc == 2 and SELECTED[2] == 2 and #BOUGHT == 0)

TYPE_NOW = 0
ok = ACT.train("1")
chk("class trainer: the direct buy as before", ok == true and BOUGHT[1] == 1 and N.ptc == 2)
return table.concat(out, "\n")
