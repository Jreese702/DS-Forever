"""chat37 (the user, 2026-11-16: "the messages filter should work different, instead of filtering to one message type I want it to open
a menu where I can check a radio box for all of the message types that I care about seeing so that I can mix and match my preferences"):
the REAL Chat.lua (with Safety.lua and TapPad.lua) under tools/wowstub.py. The app's checklist has the game's Combat boxes, so:
- the game's experience, honor and reputation lines are mirrored, each as its own kind ("xp", "honor", "rep"), as plain sentences;
- skill-ups are their own "skill" (no longer "system"), and a system line stays "system";
- the version says chat38 (the 1.0.33 merge of chat37c); nothing here is a trace (no SetBinding / SetCVar / macro / bar write) and nothing is sent to the game.
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from wowstub import ADDONS, boot, docs      # noqa: E402

CHAT = open(os.path.join(ADDONS, "WowCompanion", "Chat.lua"), encoding="utf-8").read()

PRE = r'''
WowCompanion.capture.take = function() return false, "busy" end
WowCompanion.capture.release = function() return true end
SENTCHAT = {}
C_ChatInfo = { SendChatMessage = function(text, kind) SENTCHAT[#SENTCHAT + 1] = tostring(kind) .. "|" .. tostring(text) end }
function IsInGuild() return false end
function IsInGroup() return false end
function IsInRaid() return false end
C_AddOns = { IsAddOnLoaded = function() return true end, GetAddOnInfo = function(a) return a, a, "", true, nil end }
'''

passed = 0


def ok(cond, what):
    global passed
    assert cond, what
    passed += 1


L = boot(["safety", "tappad"], pre=PRE)
load = L.eval('function(src, name) local f, e = loadstring(src, name); if not f then error(e) end; return f end')
load(CHAT, "@Chat.lua")("WowCompanion")
L.execute('HOLDER = "pad:chat"; fire("PLAYER_LOGIN"); runTimers(NOW + 3)')

# the game's own lines, as its events carry them (text, sender ...)
L.execute('fire("CHAT_MSG_COMBAT_XP_GAIN", "Kobold Vermin dies, you gain 45 experience.", "")')
L.execute('fire("CHAT_MSG_COMBAT_HONOR_GAIN", "You have been awarded 12 honor points.", "")')
L.execute('fire("CHAT_MSG_COMBAT_FACTION_CHANGE", "Reputation with Stormwind increased by 25.", "")')
L.execute('fire("CHAT_MSG_SKILL", "Your skill in Swords has increased to 12.", "")')
L.execute('fire("CHAT_MSG_SYSTEM", "You are now Away: AFK", "")')
L.execute("runTimers(NOW + 5); tick(1.0)")

chats = [d for d in docs(L, typ="chat") if isinstance(d.get("chat"), dict)]
ok(chats, "a chat document was sent")
doc = chats[-1]["chat"]
ok(doc.get("v") == "chat43", "the version is chat43 (chat37c merged, then chat40, chat41, chat42, chat43): %r" % doc.get("v"))
lines = doc.get("lines") or []
by = {}
for ln in lines:
    by.setdefault(ln.get("ch"), []).append(ln)
ok("xp" in by and by["xp"][0]["text"] == "Kobold Vermin dies, you gain 45 experience.", "experience is its own kind: %r" % lines)
ok(by["xp"][0].get("style") == "plain", "experience is a plain sentence")
ok("honor" in by and by["honor"][0].get("style") == "plain", "honor is its own kind, plain: %r" % by.get("honor"))
ok("rep" in by and by["rep"][0]["text"].startswith("Reputation with Stormwind"), "reputation is its own kind: %r" % by.get("rep"))
ok("skill" in by and by["skill"][0]["text"].startswith("Your skill in Swords"), "skill-ups are their own kind: %r" % by.get("skill"))
ok("system" in by and by["system"][0]["text"] == "You are now Away: AFK", "a system line stays system: %r" % by.get("system"))
ok(not any(ln.get("ch") == "system" and "skill" in ln.get("text", "") for ln in lines), "no skill-up reads as system")

# nothing sent to the game, nothing written (the stub's spies), no Lua error
ok(int(L.eval('#SENTCHAT')) == 0, "nothing sent to the game")
spied = [L.eval('SPY[%d]' % i) for i in range(1, int(L.eval('#SPY')) + 1)]
ok(not spied, "no SetBinding / SetCVar / macro / bar / window call: %r" % spied)
errs = [L.eval('ERRS[%d]' % i) for i in range(1, int(L.eval('#ERRS')) + 1)]
ok(not errs, "no event error: %r" % errs)

print("test_chat_types: %d checks passed" % passed)
