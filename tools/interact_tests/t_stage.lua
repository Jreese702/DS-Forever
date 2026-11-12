-- i153 (#183, 1.0.25: "Not able to send messages in chat/guild/whisper/party/etc"): the whole of Interact.lua ran, so its stage marker
-- reads "end" with its version, the shared capture exists, and Chat.lua's login probe reports both (istage / iv).
local out = {}
local function chk(n, c) out[#out + 1] = (c and "PASS " or "FAIL ") .. n end
local WC = WowCompanion
chk("the file ran to its last line", WC.interactStage == "end")
chk("its version is kept", type(WC.interactIV) == "string" and WC.interactIV:match("^i%d+$") ~= nil)
chk("the capture is there", type(WC.capture) == "table" and type(WC.capture.take) == "function")
local p = WC.oursProbe()
chk("the probe says it ran", p.istage == "end" and p.iv == WC.interactIV)
chk("the probe sees the capture", type(p.capv) == "number" and p.capv > 0)
return table.concat(out, "\n")
