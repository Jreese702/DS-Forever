-- i161 (voice1, the user 2026-11-16: voice to text in the message box and ALL search boxes, "A opens the keyboard, X opens our own voice
-- prompt"; the pick: "Every box that takes words ... auction search, mail subject and letter ... Not numbers, mail 'To' names"): X on
-- Send Mail's Subject and letter and on the auction house's search opens the SAME field as A with a voice flag (doc.kbd.v = 1); Mail's
-- To, the money and the level range keep X as before; no game call is added; the flag goes with the field.
local N, ACT, ON, build, AH, MAIL, padPress = __N, __ACT, __ON, __build, __AH, __MAIL, __padPress
local out = {}
local function chk(n, c, got) out[#out + 1] = (c and "PASS " or "FAIL ") .. n .. ((not c and got ~= nil) and (" -> " .. tostring(got)) or "") end

chk("the words fields: exactly Subject, the letter and the auction search",
    N.VOICE_FIELD["mailedit subject"] and N.VOICE_FIELD["mailedit body"] and N.VOICE_FIELD["ahedit text"]
    and not N.VOICE_FIELD["mailedit to"] and not N.VOICE_FIELD["mailedit gold"] and not N.VOICE_FIELD["ahedit min"]
    and not N.VOICE_FIELD["ahedit max"])

-- Send Mail open (the test's stand-in: the mailbox's own state)
MAIL.isOpen = function() return true end
MAIL.tab = "send"

-- the commands themselves
local ok = ACT.mailedit("subject", "v")
chk("mailedit subject v: the field opens", ok == true and N.kbd == "mailsubject", N.kbd)
chk("... flagged for the voice page", N.kbdV == true, N.kbdV)
N.kbdClose()
chk("the field closed: no flag left", N.kbd == nil and N.kbdV == nil)
ACT.mailedit("subject")
chk("mailedit subject (A): the field, no voice flag", N.kbd == "mailsubject" and N.kbdV == nil, N.kbdV)
N.kbdClose()
ACT.mailedit("to", "v")
chk("mailedit to v: To is never a voice field (the keyboard only)", N.kbd == "mailto" and N.kbdV == nil, N.kbdV)
N.kbdClose()
ACT.mailedit("gold", "v")
chk("mailedit gold v: a money box never listens", N.kbd == "mailgold" and N.kbdV == nil, N.kbdV)
N.kbdClose()
ACT.mailedit("body", "v")
chk("mailedit body v: the letter listens", N.kbd == "mailbody" and N.kbdV == true)
local okB, d = pcall(build)
local doc = okB and type(d) == "table" and d or nil
chk("the document says it: kbd.v = 1", doc ~= nil and doc.kbd ~= nil and doc.kbd.name == "mailbody" and doc.kbd.v == 1,
    doc and doc.kbd and doc.kbd.v)
N.kbdClose()
local okB2, d2 = pcall(build)
chk("closed: no kbd in the document", okB2 and type(d2) == "table" and d2.kbd == nil)

-- the controller: X on the field under the cursor (on the release, as every face button here)
N.holding = true; N.stale = nil; N.fight = nil; N.frameSeen = true
N.cur = "mailedit subject"
padPress("PAD3", true, "frame")
chk("X down: nothing yet", N.kbd == nil, N.kbd)
padPress("PAD3", false, "frame")
chk("X up on Subject: the field opens on the voice page", N.kbd == "mailsubject" and N.kbdV == true, N.kbd)
-- while the app's keyboard is up the presses are the app's: a second X changes nothing here
padPress("PAD3", false, "frame")
chk("with the keyboard up, X is the app's (no second open)", N.kbd == "mailsubject")
N.kbdClose()
N.cur = "mailedit body"
padPress("PAD3", false, "frame")
chk("X on the letter: its voice page", N.kbd == "mailbody" and N.kbdV == true)
N.kbdClose()
N.cur = "mailedit to"
padPress("PAD3", false, "frame")
chk("X on To: no field opened (X keeps its old job there)", N.kbd == nil, N.kbd)
N.cur = "mailedit gold"
padPress("PAD3", false, "frame")
chk("X on a money box: no field opened", N.kbd == nil, N.kbd)
-- A on Subject is the keyboard, no flag
N.cur = "mailedit subject"
pcall(N.act)
chk("A on Subject: the keyboard (no voice flag)", N.kbd == nil or N.kbdV == nil, N.kbdV)
N.kbdClose()

-- the auction house's search box
GAME.money = 100000
AuctionCategories = { { name = "Weapons" } }
Enum = { ItemCommodityStatus = { Unknown = 0, Item = 1, Commodity = 2 }, AuctionHouseSortOrder = { Price = 0 } }
C_AuctionHouse = { SendBrowseQuery = function() end, SendSearchQuery = function() end, GetBrowseResults = function() return {} end,
    HasFullBrowseResults = function() return true end, GetNumOwnedAuctions = function() return 0 end,
    IsThrottledMessageSystemReady = function() return true end }
pcall(ON.AUCTION_HOUSE_SHOW)
local okA = ACT.ahedit("text", "v")
if okA then
    chk("ahedit text v: the search listens", N.kbd == "ahtext" and N.kbdV == true, N.kbd)
    N.kbdClose()
    ACT.ahedit("min", "v")
    chk("ahedit min v: the level range never listens", N.kbd == "ahmin" and N.kbdV == nil, N.kbdV)
    N.kbdClose()
else
    chk("the auction house opened in the stand-in game", false, okA)
end

-- the header note
chk("the version is i161 or later (i161v; i165 in the 11-16 merge)", tonumber(WowCompanion.interactIV:match("^i(%d+)$")) >= 161, WowCompanion.interactIV)

return table.concat(out, "\n")
