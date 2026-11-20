"""chat37 (issue #400, 2026-11-16: "The chat is constantly spammed with 'No player named (name) (realm) is currently playing'"; its report:
QuestTrail still installed until that start). QuestTrail's Shared Trail whispers its peers; one gone offline answers with the game's
ERR_CHAT_PLAYER_NOT_FOUND_S. chat25 kept those lines out while QuestTrail is loaded, but chat26 also asked for the app's QuestTrail
mode (WC.qtMode), which is never on since QuestTrail was retired as a quest-tracking mode (2026-11-12): the filter was dead. The REAL
Chat.lua (with Safety.lua and TapPad.lua) under tools/wowstub.py:
- QuestTrail loaded, its mode never set: the line is kept out of both chats (the game's chat filter and our chat document)
- QuestTrail not loaded: the line shows (another add-on's, or the game's own answer)
- an answer to a whisper the player sent in the last 2 minutes still shows (C_ChatInfo.SendChatMessage or SendChatMessage)
- any other system line shows; nothing of the game is written (the stub's spies stay empty)
Run: python -B tools/test_notfound_filter.py
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from wowstub import ADDONS, boot      # noqa: E402

CHAT = open(os.path.join(ADDONS, "WowCompanion", "Chat.lua"), encoding="utf-8").read()
passes = fails = 0


def check(ok, what, got=None):
    global passes, fails
    if ok:
        passes += 1; print('PASS', what)
    else:
        fails += 1; print('FAIL', what, '->', got)


PRE = r'''
ERR_CHAT_PLAYER_NOT_FOUND_S = "No player named '%s' is currently playing."
QT_LOADED = true
C_AddOns = { IsAddOnLoaded = function(a) if a == "QuestTrail" then return QT_LOADED end return true end,
             GetAddOnInfo = function(a) return a, a, "", true, nil end }
SENTCHAT = {}
C_ChatInfo = { SendChatMessage = function(text, kind, lang, target) SENTCHAT[#SENTCHAT + 1] = tostring(target) end }
function SendChatMessage(text, kind, lang, target) SENTCHAT[#SENTCHAT + 1] = tostring(target) end
FILTERS = {}
function ChatFrame_AddMessageEventFilter(ev, fn) FILTERS[ev] = fn end
WowCompanion.capture.take = function() return false, "busy" end
WowCompanion.capture.release = function() return true end
'''

L = boot(["safety", "tappad"], pre=PRE)
load = L.eval('function(src, name) local f, e = loadstring(src, name); if not f then error(e) end; return f end')
load(CHAT, "@Chat.lua")("WowCompanion")
L.execute('fire("PLAYER_LOGIN"); runTimers(NOW + 3)')

hide = L.eval('function(t) return WowCompanion.notFound.hide(t) and true or false end')
game = L.eval('function(t) local f = FILTERS.CHAT_MSG_SYSTEM; if not f then return "none" end; return f(nil, "CHAT_MSG_SYSTEM", t) and true or false end')
line = "No player named 'Petra Stonewell-Realm' is currently playing."

check(L.eval('WowCompanion.qtMode') is None, 'the retired mode is never set (the app no longer sends qtmode on)')
check('local CHATV = "chat43"' in CHAT, 'Chat.lua is chat43 (chat37n merged, then chat40, chat41, chat42, chat43)')
check(hide(line) is True, 'QuestTrail loaded, mode off: our chat keeps the offline-peer line out', hide(line))
check(game(line) is True, 'QuestTrail loaded, mode off: the game\'s chat filter keeps it out too', game(line))

L.execute('QT_LOADED = false')
check(hide(line) is False and game(line) is False, 'QuestTrail not loaded: the line shows', (hide(line), game(line)))
L.execute('QT_LOADED = true')

L.execute('C_ChatInfo.SendChatMessage("hi", "WHISPER", nil, "Petra Stonewell-Realm")')
check(hide(line) is False, 'the player\'s own whisper (C_ChatInfo) in the last 2 minutes: its answer shows', hide(line))
L.execute('NOW = NOW + 121')
check(hide(line) is True, '2 minutes later the same name is QuestTrail\'s again', hide(line))
L.execute('SendChatMessage("yo", "WHISPER", nil, "Bram")')
check(hide("No player named 'Bram' is currently playing.") is False, 'the player\'s own whisper (SendChatMessage): its answer shows')

# the cause, measured: 1.0.32's Chat.lua (chat36) let the same line through with the mode off
import subprocess  # noqa: E402
try:
    OLD = subprocess.run(['git', '-C', os.path.dirname(os.path.dirname(os.path.abspath(__file__))), 'show',
                          '1d1906b2:companion/app/src/main/assets/companion/addons/WowCompanion/Chat.lua'],
                         capture_output=True, check=True).stdout.decode('utf-8')
except Exception:
    OLD = None
if OLD:
    L0 = boot(["safety", "tappad"], pre=PRE)
    L0.eval('function(src, name) local f, e = loadstring(src, name); if not f then error(e) end; return f end')(OLD, "@Chat.lua")("WowCompanion")
    L0.execute('fire("PLAYER_LOGIN"); runTimers(NOW + 3)')
    h0 = L0.eval('function(t) return WowCompanion.notFound.hide(t) and true or false end')
    check(h0(line) is False, 'chat36 (1.0.32), QuestTrail loaded and its mode off: the line came through (the report)', h0(line))
check(hide("You are now Away.") is False and game("You are now Away.") is False, 'any other system line shows')

spy = [L.eval('SPY[%d]' % i) for i in range(1, int(L.eval('#SPY')) + 1)]
check(not spy, 'nothing of the game written (no binding, CVar, macro, Blizzard window)', spy)

print('\n%d passed, %d failed' % (passes, fails))
sys.exit(1 if fails else 0)
